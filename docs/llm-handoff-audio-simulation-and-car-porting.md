# Audio, simulation, and car profiles — context for LLMs

This document describes **how this Android app currently works**: input, synthetic tach/RPM, shifting, and sample-based engine audio. It is written for an LLM that also has access to the repo files and to related work elsewhere (for example the Assetto Corsa RPM simulator at `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`).

It explains what is implemented today. It does not prescribe what a future change should look like — another model may replace the WAV pipeline, change mix logic, or port cars differently.

Other docs in this repo go deeper on specific topics:

| Topic | Doc |
| --- | --- |
| FMOD recovery, WAV extraction | [sample-engine-audio.md](sample-engine-audio.md) |
| App architecture | [full-implementation.md](full-implementation.md) |
| EV physics / Seal calibration | [byd-seal-performance-calibration.md](byd-seal-performance-calibration.md) |
| UI vs simulation | [ui-display-and-simulation-decisions.md](ui-display-and-simulation-decisions.md) |
| Tuning panel | [tuning-interface.md](tuning-interface.md) |
| Project workflow | [llm-handoff.md](llm-handoff.md) |

---

## 1. What the app is

A **BYD DiLink dashboard** (Compose UI, `mobile` module) that:

1. Reads **accelerator, brake, speed**, and optionally **gearbox P/N/D** from the car via reflection on `android.hardware.bydauto.*`, or uses **on-screen simulator pedals**.
2. Runs a **200 Hz longitudinal EV model** (mass, axle torque curves, drag, brakes) calibrated toward a BYD Seal Performance.
3. Runs a **presentation gearbox** that maps road speed to **synthetic engine RPM**, shift events, and tach display. This layer is separate from EV wheel torque.
4. Today, engine sound comes from **pre-authored WAV loops and one-shots**, mixed in real time with **varispeed** (playback rate tied to RPM). There is no procedural synth path in the current code.

The Assetto Corsa RPM simulator is a **separate project** that can hold authoring knowledge (loop names, root RPMs, shift feel). This Android app does not call it at runtime.

---

## 2. Data flow (current code)

```
Input (20 ms BYD poll or manual sliders)
  → DriveController @ 200 Hz
      → EngineSimulation → DrivetrainState (rpm, gear, shifts, speed)
      → EngineAudioFrame → EngineAudioEngine → SampleEngineRenderer → AudioTrack
  → Compose UI (tach, speed, mixer, tuning)
```

| Area | Main files |
| --- | --- |
| Drive loop | `drive/DriveController.kt` |
| Simulation | `simulation/EngineSimulation.kt` |
| Audio frame | `audio/EngineAudioFrame.kt` |
| Audio output | `audio/EngineAudioEngine.kt` |
| Sample mixer | `audio/SampleEngineRenderer.kt` |
| Profiles | `audio/EngineSampleProfile.kt`, `HuracanProfile.kt`, `AdditionalCarProfiles.kt` |
| WAV decode | `audio/WavPcmDecoder.kt` |
| Mix mode prefs | `audio/AudioMixModeRepository.kt` |
| BYD telemetry | `telemetry/BydSpeedReader.kt`, `BydGearboxMapping.kt` |

---

## 3. Two parallel models: EV physics vs presentation gearbox

| | EV physics | Presentation gearbox |
| --- | --- | --- |
| Purpose | Acceleration / braking | Tach RPM, shift sounds, gear label |
| Changes when user shifts? | No | Yes |
| Uses numeric `gearRatios[]` at runtime? | No | Only **count** (`gearRatios.size`) |
| Feeds audio RPM? | Indirectly via speed | Directly via `drivetrain.rpm` |

Wheel torque uses digitized front/rear axle curves, motor cap, mass, drag, brakes. An upshift changes synthetic RPM and may fire shift one-shots; it does not change EV acceleration in the current simulation.

---

## 4. Tachometer and RPM

### 4.1 UI sources

**Analog gauge** (`MainActivity.kt` → `TachometerGauge`):

| Element | Source |
| --- | --- |
| Needle | `drivetrain.rpm` vs profile `maxRpm` |
| Center label | Gear number in **D**; **P** or **N** otherwise |
| Digital speed | `drivetrain.rawSpeedKmh` (integer km/h) |
| Red zone | `redlineRpm` |
| SHIFT overlay | `isShifting` + direction |

**Mixer HUD** (`DashboardScreens.kt` → `BarTachometerHud`) uses the same `drivetrain` fields.

Needle RPM is continuous; speed digits are whole km/h. `QuantizedSpeedEstimator` smooths integer BYD (or SIM-rounded) speed before RPM/audio use.

### 4.2 P / N / D

| Position | Throttle drives wheels? | Auto shifts? | RPM model |
| --- | --- | --- | --- |
| **D** | Yes | Yes | Road-speed coupled |
| **N** | No | No | Free-rev from pedal |
| **P** | No | No | Free-rev; speed held at 0 |

**Free-rev (N/P):**

```
targetRPM = idleRpm + filteredThrottle × (redlineRpm − idleRpm)
```

Rev-up time constant **0.55 s**, rev-down **0.90 s** (fixed in code).

**Drive (D) — road-speed coupled:**

```
wheelRpm = vehicleSpeedMps / (2π × wheelRadiusMeters) × 60
targetRPM = idleRpm + wheelRpm × evenlySpacedGearRatio(currentGearIndex)
          clamped to [idleRpm .. limiterRpm]
```

Smoothed with `syntheticRpmResponseMs` (default **20 ms** in tuning).

### 4.3 Equal-width speed bands

Gear count = `profile.gearRatios.size` (often **7**).

```
upshiftSpeedKmh(g) = topSpeedKmh × (g + 1) / gearCount
```

Default `topSpeedKmh = 190`, 7 gears → ~**27.14 km/h** per band.

At each band top, RPM targets **upshiftRpm**. Runtime derives:

```
evenlySpacedGearRatio(g) = (upshiftRpm − idleRpm) / boundaryWheelRpm(g)
```

The numeric values stored in `gearRatios` (e.g. Huracán `[3.75, 2.38, …]`) are used in the **TuningPanel gear graph**, not in this runtime ratio calculation.

### 4.4 Shift logic (`EngineSimulation.kt`)

| Constant | Value |
| --- | --- |
| Normal upshift throttle floor | **0.10** |
| Emergency upshift RPM fraction | **0.98** of redline |
| Downshift speed hysteresis | **4.0 km/h** |
| Kickdown throttle | **> 0.78** |
| Kickdown speed margin | **10.0 km/h** below remembered upshift boundary |
| Shift dwell | **0.150 s** default |
| Gear index swap during shift | progress **≥ 0.38** |

**Normal upshift (D):** throttle > 0.10 and `speedKmh >= upshiftSpeedKmh(currentGearIndex)`.

**Emergency upshift:** `rpmForSpeed(currentGear) >= redlineRpm × 0.98`.

**Downshift:** speed ≤ remembered upshift boundary − 4 km/h (floor 2 km/h), or kickdown with throttle > 0.78 and speed below boundary − 10 km/h.

Each upshift stores current speed as the downshift boundary for the target gear.

Shift presentation: `shiftSerial` increments, `shiftDirection` UP/DOWN, durations from profile (e.g. Huracán 60 ms up / 150 ms down). RPM target blends between gears during the shift window.

### 4.5 Integer speed smoothing

`QuantizedSpeedEstimator` (`externalSpeedSmoothingMs`, default **120 ms**) sits between integer speed readings and simulation/audio.

---

## 5. Audio: sample profiles (current implementation)

### 5.1 Profile shape

```kotlin
EngineSampleProfile(
    id, displayName,
    assetDirectory,           // assets/sample_engine/{assetDirectory}/
    previewAssetName,         // assets/car_previews/
    outputSampleRate,         // authored WAV rate
    playbackSampleRate,       // AudioTrack rate (may differ)
    idleRpm, maximumRpm, redlineRpm, limiterRpm, upshiftRpm,
    gearRatios,
    layers: List<SampleLayerSpec>,
    effects: List<SampleEffectSpec>,
    ...
)
```

Registry: `EngineSampleProfiles.all`.

**Cars in repo today**

| ID | Definition | Layers | Notes |
| --- | --- | --- | --- |
| `lamborghini_huracan_trofeo_evo2_cabin` | `HuracanProfile.kt` | 24 | FMOD-style curves; 44100 → 48000 playback |
| `lamborghini_aventador_sv_cabin` | `AdditionalCarProfiles.kt` `bandProfile()` | 16 | Band factory; 48000 native |

### 5.2 Layer roles

| Role | Typical content |
| --- | --- |
| **IDLE** | Low-RPM loop |
| **LOAD** | On-throttle body |
| **COAST** | Lift-off / overrun |
| **TEXTURE** | Noise fill |
| **LIMITER** | Near redline |

Each layer: looping WAV, `startRpm`/`endRpm`, `autopitchRootRpm`, automation curves.

Varispeed: `playbackRatio = rpm / autopitchRootRpm` (clamped). Cubic interpolation; phase advances when inaudible (FMOD-style timeline continuity).

WAVs are copied into assets by an explicit list in `mobile/build.gradle.kts` (`prepareSampleEngineAssets`); local sources live under `audio_samples/`.

---

## 6. Two mix modes

The renderer supports two paths, selected by `coastLayerMixEnabled` on `EngineAudioFrame` (from `AudioMixModeRepository`, **default ON**).

### 6.1 Coast layer mix (default, app focus)

When `coastLayerMixEnabled == true`:

| Behavior | Detail |
| --- | --- |
| **LOAD** | Gain forced to 0 |
| **COAST** | Full RPM-band gain; throttle curve ignored |
| **IDLE** | Special amplitude fade ~1350–2950 RPM (`idleCoastMixAmplitude`) |
| **Profile output gain** | Uses full-throttle curve point (`outputGainAt(1.0)`) |
| **Idle layer fade time** | 120 ms (vs normal layer fade) |
| **MIXER UI** | LOAD rows hidden; per-track **GAIN** sliders active |
| **Layer mix volume** | User multiplier 0..8× applied |
| **Asset load** | LOAD layer WAVs are not decoded into memory |

This is the mix mode the project is oriented toward for new work.

### 6.2 Legacy throttle mix

When `coastLayerMixEnabled == false`:

| Behavior | Detail |
| --- | --- |
| **LOAD / COAST / IDLE** | Original FMOD-style throttle + RPM automation |
| **Profile output gain** | Follows live throttle |
| **MIXER UI** | All layer rows visible; GAIN multipliers fixed at 1.0× |

Toggle: DEBUG panel → **AUDIO MIX MODE**, or Gradle `coastLayerMixEnabledByDefault` / persisted pref `coast_layer_mix_enabled`. Header shows **LEGACY MIX** tag when legacy is active.

Legacy pref key `coast_only_full_gain` is still read for migration.

---

## 7. Mixing pipeline (`SampleEngineRenderer.render`)

1. Smooth RPM and throttle (`AudioTuning`: 16 ms / 10 ms defaults).
2. Per layer: `SampleLayerSpec.gainAt(rpm, throttle, coastLayerMixEnabled)` then user mute/solo/volume.
3. Accumulate loop voices + effects; one-shots on triggers.
4. Bus:

```
mixed = loopSum × continuousProgramGain + effectSum
commonGain = 0.65 × masterGain × profileOutputGain × enabledGain
output = hard clip at ±1.0 → PCM16
```

**Master gain:** per-car `CarMasterVolumeRepository` × global `AudioTuning.masterGain / 0.72`.

**Effects triggers:**

| Trigger | Fires when |
| --- | --- |
| `SHIFT_UP` / `SHIFT_DOWN` | `shiftSerial` changes |
| `TRANSMISSION_LOOP` | Continuous if enabled |
| `THROTTLE_LIFT` | Armed at throttle ≥ 0.35; fires once at ≤ 0.08 |

**Solo effects mode:** mutes continuous loops; only checked effects play.

### Realtime memory/deadline rules

- `WavPcmDecoder` retains interleaved PCM16, exactly two bytes per source sample. Do not expand the
  whole bank to float arrays; conversion happens only for cubic taps being mixed.
- The decoder streams the WAV data chunk directly into its retained `ShortArray`, avoiding a second
  full-size byte buffer during profile startup.
- The per-sample 48 kHz loop uses indexed traversal. Do not use `for (voice in voices)`, sequences,
  collection transforms, logging, or immutable state updates inside that loop.
- Meter/diagnostic snapshots publish every 12 AudioTrack writes; route queries publish every 48.
- A rising underrun count increases the effective AudioTrack buffer by one native burst, capped by
  its preallocated capacity. Preserve all layers selected by the active mix when optimizing.

---

## 8. What audio receives each frame

`EngineAudioFrame` fields:

| Field | Source |
| --- | --- |
| `rpm`, `throttle` | `drivetrain` |
| `shiftSerial`, `shiftDirection` | shift state |
| `layerMix`, `enabledEffectMask`, `soloEffects` | persisted per-car prefs |
| `tuning` | `AudioTuning` + car master volume |
| `coastLayerMixEnabled` | `AudioMixModeRepository` |

Speed, gear index, and brake are not passed to the renderer in the current design. Shift sounds follow **`shiftSerial`**, not gear number.

---

## 9. BYD input modes

| Mode | Pedals | Speed | P/N/D |
| --- | --- | --- | --- |
| **AUTO** | BYD if valid, else SIM | BYD if valid | Manual UI |
| **BYD LIVE** | BYD if valid | BYD if valid | Locked to BYD gearbox |
| **SIMULATOR** | Sliders | Integrated physics | Manual UI |

Gearbox: `BYDAutoGearboxDevice` via reflection; mapping in `BydGearboxMapping.kt`.

---

## 10. How cars are registered today (reference)

Current pattern when adding a profile to this repo:

1. WAV files locally under `audio_samples/...`
2. Each file listed in `mobile/build.gradle.kts` → `LocalEngineProfileAssets`
3. Kotlin profile (`HuracanProfile.kt` full curves, or `bandProfile()` factory)
4. Entry in `EngineSampleProfiles.all`
5. Preview image in `car_previews/`

**Authoring styles in code:**

- **Full curves** — one `SampleLayerSpec` per recovered layer with explicit `AutomationCurve` lists.
- **Band factory** — lists of `RootedSample(asset, rpm, gainDb)`; factory builds crossfaded RPM bands.

Selecting a car → `DriveController.applySelectedCar()` reloads mix prefs, effect mask, tuning limits, restarts audio.

---

## 11. Persistence

| Pref file | Purpose |
| --- | --- |
| `selected_car` | Last car |
| `car_master_volume` | Per-car master 0..1.2 (default 0.72) |
| `sample_layer_mix` | Mixer mute/solo/volume per track |
| `sample_sound_effects` | Effect toggles |
| `engine_tuning` | TUNE panel |
| `audio_experiments` | `coast_layer_mix_enabled` |

---

## 12. Formulas (as implemented)

```
upshiftSpeedKmh(g) = topSpeedKmh × (g + 1) / gearCount
ratio(g) = (upshiftRpm − idleRpm) / boundaryWheelRpm(g)

D-mode:  targetRPM = idleRpm + wheelRpm × ratio(gear)
N/P:     targetRPM = idleRpm + throttle × (redlineRpm − idleRpm)

playbackRatio = rpm / autopitchRootRpm

Normal upshift:     throttle > 0.10 AND speed ≥ upshiftSpeedKmh(g)
Emergency upshift:  rpmForSpeed(g) ≥ redline × 0.98
Downshift:          speed ≤ boundary − 4  OR  kickdown (throttle > 0.78, speed < boundary − 10)

Smoothing: value += (target − value) × (1 − exp(−dt / τ))
```

---

## 13. Doc/code notes (as of this writing)

1. `ui-display-and-simulation-decisions.md` §3.2 may still describe throttle-driven D RPM; runtime D-mode is road-speed coupled (§3.3 aligns with code).
2. `gearRatios` numeric values are for tuning UI; runtime uses equal bands from `topSpeedKmh`.
3. SIM coast regen is hardcoded in `EngineProfile` (2.5 m/s²), not exposed in TUNE UI.
4. BYD gearbox mapping uses fallback constants if SDK fields are missing.

---

## 14. Related external project

**Assetto Corsa RPM simulator:** `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`

That codebase may contain RPM curves, layer roots, and shift behavior used while authoring. This Android repo currently consumes **Kotlin profiles + packaged WAV assets**; there is no runtime link between the two.

---

## 15. Useful source files to open

- `EngineSampleProfile.kt`, `SampleEngineRenderer.kt`, `EngineSimulation.kt`
- `HuracanProfile.kt` and `AdditionalCarProfiles.kt` (two profile styles)
- `AudioMixModeRepository.kt` (coast vs legacy default)
- `mobile/build.gradle.kts` (asset enumeration)
- [sample-engine-audio.md](sample-engine-audio.md) (extraction history)

Tests: `EngineSimulationTest.kt`, `SampleEngineRendererTest.kt`, `DriveControllerInputTest.kt`, `BydTransmissionControlTest.kt`.
