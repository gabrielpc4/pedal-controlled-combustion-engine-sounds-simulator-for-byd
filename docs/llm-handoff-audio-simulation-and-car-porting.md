# LLM handoff: audio layering, simulation, shifting, and car porting

Read this document when you need to understand **how this Android app produces engine sound and drives the tachometer**, or when you are porting **additional cars** (for example from the separate Assetto Corsa RPM simulator at `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`) into this project.

This repo also contains supporting docs. Use them as deeper references; this file is the **single narrative** an LLM should read first.

| Topic | Deeper doc |
| --- | --- |
| FMOD recovery, WAV extraction, licensing | [sample-engine-audio.md](sample-engine-audio.md) |
| Full app architecture | [full-implementation.md](full-implementation.md) |
| EV physics / Seal calibration | [byd-seal-performance-calibration.md](byd-seal-performance-calibration.md) |
| UI vs simulation decisions | [ui-display-and-simulation-decisions.md](ui-display-and-simulation-decisions.md) |
| Tuning panel semantics | [tuning-interface.md](tuning-interface.md) |
| Project workflow / BYD constraints | [llm-handoff.md](llm-handoff.md) |

---

## 1. Mental model (read this first)

This app is **not** a game engine plugin and **does not** call Assetto Corsa at runtime.

It is a **BYD DiLink dashboard** that:

1. Reads **accelerator, brake, speed** (and optionally **gearbox P/N/D**) from the car via reflection on `android.hardware.bydauto.*`, or falls back to **simulator pedals**.
2. Runs a **200 Hz EV road model** (mass, axle torque curves, drag, brakes) calibrated for a BYD Seal Performance.
3. Runs a **fictional presentation gearbox** that converts road speed into **synthetic engine RPM**, shift events, and tachometer display — **without changing EV wheel torque**.
4. Mixes **pre-authored WAV loops** (layers + one-shot effects) in real time using **varispeed** (playback rate tied to RPM). There is **no procedural synth fallback**.

The Assetto Corsa RPM simulator is useful as **authoring reference**: which loops exist, at which RPM roots they crossfade, shift timing, redline, gear count. The Android app needs **Kotlin profile definitions + packaged WAV assets**, not AC runtime hooks.

---

## 2. End-to-end data flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Input (20 ms BYD poll OR manual sliders)                               │
│    accelerator 0–100%  →  throttle 0..1                                 │
│    brake 0–100%      →  brake 0..1                                     │
│    speed km/h (integer from BYD or rounded from SIM physics)            │
│    gearbox P/N/D (BYD LIVE only)                                        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  DriveController @ 200 Hz (5 ms fixed step)                           │
│    resolveDriveInput()           → pedals + speed source label          │
│    resolveTransmissionControl()  → P/N/D (manual or locked to BYD)      │
│    EngineSimulation.update()     → DrivetrainState                      │
│    build EngineAudioFrame        → rpm, throttle, shifts, layer mix     │
└───────────────────────────────┬─────────────────────────────────────────┘
                                ▼
┌──────────────────────────┐    ┌──────────────────────────────────────────┐
│  UI (Compose)            │    │  EngineAudioEngine (dedicated thread)    │
│  - Tach needle ← rpm     │    │    SampleEngineRenderer.render()         │
│  - Speed digits ← raw    │    │      mix all LoopVoice + EffectVoice   │
│    integer km/h          │    │      → stereo PCM16                      │
│  - Gear ← gear or P/N    │    │    mapStereoAcrossChannels()             │
└──────────────────────────┘    │      → 2/4/6/8 ch AudioTrack            │
                                └──────────────────────────────────────────┘
```

**Key files**

| Layer | Path |
| --- | --- |
| Drive loop | `mobile/src/main/java/.../drive/DriveController.kt` |
| Simulation | `mobile/src/main/java/.../simulation/EngineSimulation.kt` |
| Transmission enum | `mobile/src/main/java/.../simulation/TransmissionPosition.kt` |
| Audio frame DTO | `mobile/src/main/java/.../audio/EngineAudioFrame.kt` |
| Audio output | `mobile/src/main/java/.../audio/EngineAudioEngine.kt` |
| Sample mixer | `mobile/src/main/java/.../audio/SampleEngineRenderer.kt` |
| Profile model | `mobile/src/main/java/.../audio/EngineSampleProfile.kt` |
| WAV decode | `mobile/src/main/java/.../audio/WavPcmDecoder.kt` |
| BYD telemetry | `mobile/src/main/java/.../telemetry/BydSpeedReader.kt` |

---

## 3. Synthetic gears vs real EV physics (critical)

These are **deliberately separate**:

| | **EV physics** | **Presentation gearbox** |
| --- | --- | --- |
| Purpose | How fast the car accelerates/brakes | Tach RPM, shift sounds, gear number on HUD |
| Affected by shifts? | **No** | **Yes** |
| Uses `gearRatios` values at runtime? | **No** | **Only gear count** (`gearRatios.size`) |
| Feeds audio RPM? | Indirectly via speed | **Directly** via `drivetrain.rpm` |

Wheel torque comes from digitized front/rear axle curves, motor power cap, mass, drag, and brakes. Shifting from 3rd to 4th **does not** change acceleration — it only changes the synthetic RPM mapping and may fire shift one-shots.

---

## 4. Tachometer and RPM simulation

### 4.1 What the tach displays

**Analog gauge** (`MainActivity.kt` → `TachometerGauge`):

| Element | Source |
| --- | --- |
| Needle / arc | `drivetrain.rpm` vs profile `maxRpm` (gauge rounds up to next 1000) |
| Center label | Gear number if **D**, else **P** or **N** |
| Digital speed (bottom) | `drivetrain.rawSpeedKmh` — **whole integer km/h** |
| Red zone | From `redlineRpm` |
| SHIFT overlay | `isShifting` + direction |

**Mixer bar HUD** (`DashboardScreens.kt` → `BarTachometerHud`): speed integer, gear, horizontal RPM bar — same underlying `drivetrain` fields.

The **needle uses continuous RPM**; the **speed digits use integer km/h** (BYD integer or SIM rounded). A continuous speed estimator sits between integer readings and RPM so sound/tach do not jump at whole-km/h boundaries.

### 4.2 P / N / D behavior

| Position | Wheels driven by throttle? | Auto shifts? | Speed integration | RPM model |
| --- | --- | --- | --- | --- |
| **D** | Yes | Yes | Full (BYD or SIM) | **Road-speed coupled** |
| **N** | No | No | SIM coast/brake still moves speed | **Free-rev** from pedal |
| **P** | No | No | Speed forced to **0** | **Free-rev** from pedal |

**Free-rev (N/P):**

```
targetRPM = idleRpm + filteredThrottle × (redlineRpm − idleRpm)
```

Time constants: rev-up **0.55 s**, rev-down **0.90 s** (fixed, not in tuning panel).

**Drive (D) — road-speed coupled RPM:**

```
wheelRpm = vehicleSpeedMps / (2π × wheelRadiusMeters) × 60

targetRPM = idleRpm + wheelRpm × evenlySpacedGearRatio(currentGearIndex)
          = clamp(idleRpm .. limiterRpm)
```

RPM follows target with exponential smoothing (`syntheticRpmResponseMs`, default **20 ms** in tuning).

### 4.3 Equal-width speed bands and derived ratios

Gear **count** = number of entries in profile `gearRatios` (default **7**).

Upshift speed boundary for gear index `g` (0-based):

```
upshiftSpeedKmh(g) = topSpeedKmh × (g + 1) / gearCount
```

With default `topSpeedKmh = 190` and 7 gears → band width ≈ **27.14 km/h** per gear.

At each band top, synthetic RPM should hit **normal shift RPM** (`upshiftRpm`). The runtime derives:

```
boundaryWheelRpm = (boundaryKmh / 3.6) / (2π × wheelRadius) × 60
coupledRpm = upshiftRpm − idleRpm
evenlySpacedGearRatio(g) = coupledRpm / boundaryWheelRpm
```

**Important:** The numeric values in `gearRatios` (e.g. Huracán `[3.75, 2.38, …]`) are **not** used for this runtime mapping. They appear in the **TuningPanel gear-landing graph** only. Runtime uses `evenlySpacedGearRatio()` from `topSpeedKmh` + gear count.

### 4.4 When upshifts happen

Constants in `EngineSimulation`:

| Constant | Value |
| --- | --- |
| `SHIFT_THROTTLE_THRESHOLD` | **0.10** — min throttle for normal upshift |
| `EMERGENCY_UPSHIFT_RPM_FRACTION` | **0.98** — near-redline upshift without throttle |
| `DOWNSHIFT_SPEED_HYSTERESIS_KMH` | **4.0** |
| Kickdown throttle | **> 0.78** |
| `KICKDOWN_SPEED_MARGIN_KMH` | **10.0** |
| `shiftDwellSeconds` | **0.150 s** default between shifts |
| Gear index swap during shift | at progress **≥ 0.38** |

**Normal upshift** (in D, dwell elapsed, not already shifting):

- `currentGearIndex < lastIndex`
- `filteredThrottle > 0.10`
- `speedKmh >= upshiftSpeedKmh(currentGearIndex)`

**Emergency upshift** (external speed forcing RPM up without throttle):

- `rpmForSpeed(currentGear) >= redlineRpm × 0.98`

**Downshift**:

- Remember `downshiftBoundaryKmhByGear[gear]` = vehicle speed at the upshift that entered this gear
- Trigger if `speedKmh <= max(rememberedBoundary − 4.0, 2.0)`
- **Or** kickdown: `throttle > 0.78` AND `speedKmh < rememberedBoundary − 10.0`

On upshift start, the current speed is stored as the downshift boundary for the **target** gear.

**Shift presentation:** `isShifting`, `shiftProgress`, `shiftSerial` increment, `shiftDirection` UP/DOWN. Duration from profile: Huracán 60 ms up / 150 ms down; Aventador 80 ms / 260 ms (overridable via tuning when car selected).

During shift, RPM target switches from old gear's road coupling to new gear's at 38% progress, with faster smoothing (`max(shiftDuration × 0.30, 18 ms)`).

### 4.5 Integer speed reconstruction

Both BYD integer speed and SIM physics speed pass through `QuantizedSpeedEstimator`:

- Predictive critically-damped smoothing (`externalSpeedSmoothingMs`, default **120 ms**)
- Prevents tach/audio steps when BYD reports whole km/h only
- SIM path: `rawExternalSpeedKmh = round(physicalSpeedMps × 3.6)` then same estimator

---

## 5. Audio: profiles, layers, and how they are read

### 5.1 One profile = one car sound

```kotlin
EngineSampleProfile(
    id, displayName,
    assetDirectory,              // under assets/sample_engine/
    previewAssetName,            // under assets/car_previews/
    outputSampleRate,            // authored WAV rate (validation)
    playbackSampleRate,          // AudioTrack + renderer rate (may differ)
    idleRpm, maximumRpm, redlineRpm, limiterRpm, upshiftRpm,
    gearRatios,                  // count matters; values mostly for tuning UI
    upshiftDurationSeconds, downshiftDurationSeconds,
    layers: List<SampleLayerSpec>,
    effects: List<SampleEffectSpec>,
    throttleOutputGainDb,        // optional route-level trim vs pedal
)
```

Registry: `EngineSampleProfiles.all` in `EngineSampleProfile.kt`.

**Current cars**

| ID | Authoring style | Layers | Effects | Rates |
| --- | --- | --- | --- | --- |
| `lamborghini_huracan_trofeo_evo2_cabin` | Full FMOD reconstruction (`HuracanProfile.kt`) | 24 | transmission + shift up/down | 44100 authored → **48000 playback** |
| `lamborghini_aventador_sv_cabin` | Generic bands (`AdditionalCarProfiles.kt`) | 16 | + overrun on throttle lift | 48000 native |

### 5.2 Layer roles

| Role | Purpose | Typical automation |
| --- | --- | --- |
| **IDLE** | Stationary / low RPM loop | +8 dB boost constant; throttle fades idle out |
| **LOAD** | On-throttle engine body | `throttleGainDb` opens at high pedal |
| **COAST** | Lift-off / overrun harmonics | `throttleGainDb` favors low pedal |
| **TEXTURE** | Noise / sine fill | Moderate throttle curve |
| **LIMITER** | Hard redline layer | RPM dB automation near limiter |

Each layer is one **looping WAV** with:

- `startRpm` / `endRpm` — silent outside range
- `autopitchRootRpm` — varispeed root: `playbackRatio = rpm / root`
- `baseGainDb`, `throttleGainDb`, `rpmAmplitudeCurves`, `rpmGainDbCurves`

**Layer gain (normal mode):**

```
if rpm ∉ [startRpm, endRpm] → 0
amplitude = product of rpmAmplitudeCurves(rpm)
decibels = baseGainDb + (IDLE ? +8dB : 0) + throttleCurve(throttle) + sum(rpmGainDbCurves)
gain = amplitude × 10^(decibels/20)
```

### 5.3 Effects (one-shots and loops)

| Trigger | When it fires |
| --- | --- |
| `SHIFT_UP` / `SHIFT_DOWN` | `shiftSerial` changes; direction ±1; control bit enabled; RPM ≥ minimum |
| `TRANSMISSION_LOOP` | Continuous if enabled; gain scales with RPM and throttle |
| `THROTTLE_LIFT` | Arm throttle ≥ 0.35; fire once when throttle ≤ 0.08 |

Effect groups (UI toggles): **Gear changes**, **Transmission**, **Exhaust overrun** — bitmask in `enabledEffectMask`.

### 5.4 WAV loading and varispeed

**Path:** `assets/sample_engine/{assetDirectory}/{assetName}`

**Build:** `prepareSampleEngineAssets` in `mobile/build.gradle.kts` copies enumerated WAVs from local `audio_samples/...` into generated assets. **Every WAV must be listed explicitly** — nothing is copied by glob.

**Decoder** (`WavPcmDecoder.kt`): PCM16, mono/stereo, reads `smpl` loop points → `PcmLoopData` with float samples per channel.

**Playback** (`SampleEngineRenderer` → `LoopVoice`):

```
playbackRatio = (rpm / autopitchRootRpm) × 2^(basePitchSemitones/12)   // clamped 0.1..4.0
phaseIncrement = sourceSampleRate / outputSampleRate × playbackRatio
```

**Cubic (Hermite) interpolation** between samples; **phase advances even when inaudible** (FMOD timeline continuity). Loop wraps at `smpl` loop region.

**Rate conversion:** No separate resampler — changing `outputSampleRate` vs authored rate is handled by `phaseIncrement`. Huracán uses 48 kHz playback on 44.1 kHz sources to avoid BYD head-unit resampling.

---

## 6. Audio: mixing and gain chain

### 6.1 Per-block pipeline (`SampleEngineRenderer.render`)

1. Smooth `rpm` and `throttle` (from `AudioTuning`: 16 ms / 10 ms defaults).
2. For each layer: compute authored `gainAt(rpm, throttle)`, apply user **layer mix** (mute/solo/volume).
3. Each voice: smooth gain, cubic read L/R, accumulate.
4. Effects: same; one-shots deactivate at end of buffer.
5. Bus mix:

```
mixedL/R = loopSum × continuousProgramGain + effectSum

commonGain = 0.65 × masterGain × profileOutputGain × enabledGain

output = transparentLimit(mixed × commonGain)   // hard clip ±1.0 only
PCM16 = output × 32767
```

### 6.2 Master and profile trim

| Stage | Source |
| --- | --- |
| `masterGain` | Per-car `CarMasterVolumeRepository` × global `AudioTuning.masterGain / 0.72` |
| `profileOutputGain` | `profile.outputGainAt(throttle)` from optional throttle curve |
| `enabledGain` | User engine ON/OFF mute |
| `continuousProgramGain` | 0 if **SOLO EFFECTS** (mutes loops, keeps effects) |

**Duplication gain** (`EngineAudioEngine`): when mirroring stereo to 4/6/8 channels, gain is reduced (0.38 quad, 0.27 5.1, 0.23 7.1) to avoid clipping.

### 6.3 User mixer (MIXER screen)

`LayerMixRepository` persists per `{profileId}.{trackId}`: volume (0..8×), mute, solo.

Default behavior: volume multiplier **only applies when COAST EXP experiment is ON**; otherwise multiplier forced to 1.0. LOAD rows hidden during experiment.

Solo: any track solo → non-solo tracks silent; also blocks shift one-shots.

### 6.4 COAST EXP experiment (debug flag)

When `coastOnlyFullGain` is true:

- **LOAD** layers forced to 0 gain
- **COAST** ignores throttle curve (full RPM-band gain)
- **IDLE** uses special fade above ~1350–2950 RPM
- Per-track GAIN sliders active in MIXER

---

## 7. What audio receives each frame

`EngineAudioFrame` (built in `DriveController.step`):

| Field | From |
| --- | --- |
| `rpm` | `drivetrain.rpm` |
| `throttle` | `drivetrain.smoothedThrottle` |
| `shiftSerial`, `shiftDirection` | shift state |
| `enabledEffectMask`, `soloEffects` | per-car prefs |
| `layerMix` | per-car prefs |
| `tuning` | `AudioTuning` + car master volume |
| `coastOnlyFullGain` | experiment flag |

**Not sent to audio:** speed, gear index, brake, engineLoad, limiter flag. Audio only cares about **RPM, throttle, shifts, and mix settings**.

Shift sounds are triggered by **`shiftSerial` change**, not by gear number directly.

---

## 8. BYD input modes

| Mode | Pedals | Speed | P/N/D |
| --- | --- | --- | --- |
| **AUTO** | BYD if valid, else SIM | BYD if valid | Manual |
| **BYD LIVE** | BYD if valid, else zeros | BYD if valid | **Locked to BYD gearbox** |
| **SIMULATOR** | Manual sliders | Integrated physics | Manual |

Gearbox read: `BYDAutoGearboxDevice.getGearboxAutoModeType()` + `getGearboxCode()` via reflection; mapped P/N/D in `BydGearboxMapping.kt`. Permission: `BYDAUTO_GEARBOX_GET`.

---

## 9. Porting a new car (e.g. from Assetto Corsa knowledge)

You are **not** wiring the AC app into Android. You are **authoring a new `EngineSampleProfile`** and **packaging WAVs**.

### 9.1 What to extract from AC / bank work

For each candidate car, collect:

| Data | Used for |
| --- | --- |
| Loop WAV files (idle, load/on-throttle, coast/off-throttle, limiter, textures) | `SampleLayerSpec.assetName` |
| Root RPM per loop | `autopitchRootRpm` |
| Crossfade regions / automation | `HuracanProfile`-style curves **or** band list for `bandProfile()` |
| Shift up/down one-shots | `SampleEffectSpec` SHIFT_UP/DOWN |
| Transmission whine loop | TRANSMISSION_LOOP effect |
| Overrun / lift-off sample | THROTTLE_LIFT effect (optional) |
| idle/redline/limiter/upshift RPM | profile metadata + simulation when car selected |
| Gear count | length of `gearRatios` list |
| Authored sample rate | `outputSampleRate` |
| Preview image | `car_previews/` |

### 9.2 Two authoring paths in this codebase

**A. Full reconstruction** (Huracán pattern) — `HuracanProfile.kt`

- One `SampleLayerSpec` per recovered FMOD layer
- Explicit `AutomationCurve` for throttle and RPM
- Use when you have **complete** bank automation data

**B. Band factory** (Aventador pattern) — `AdditionalCarProfiles.kt` → `bandProfile()`

- Provide lists of `RootedSample(asset, rpm, gainDb)` for idle, load, coast, optional texture
- Factory builds RPM bands with crossfade widths (~55% of band, min 220 RPM)
- Shared throttle curves for load/coast/texture
- **Fastest path** when you have named loops + root RPMs from AC work

### 9.3 Checklist to register a car

1. **Place WAVs** in `audio_samples/<your_folder>/converted/` (local, gitignored unless user has rights).

2. **Register in `mobile/build.gradle.kts`** — new `LocalEngineProfileAssets` entry with every `assetName` and preview image.

3. **Create profile Kotlin** — `XxxProfile.kt` or `bandProfile(...)` call.

4. **Append to `EngineSampleProfiles.all`** and optional `specifications` map for dashboard stats.

5. **Set rates** — `outputSampleRate` = authored rate; if 44.1 kHz and BYD path prefers 48 kHz, set `playbackSampleRate = 48_000`.

6. **Wire effects** — map AC shift/transmission/overrun samples to `SampleEffectSpec` with correct triggers and control bits.

7. **Tests** — `SampleEngineRendererTest.everySelectableCarHasACompleteDistinctSampleProfile` must pass (layers audible across RPM range, rate consistency).

8. **On-device** — `ExampleInstrumentedTest` validates packaged WAVs decode.

Selecting a car in app → `DriveController.applySelectedCar()` reloads layer mix, effect mask, tuning RPM limits from profile, restarts audio engine.

### 9.4 Mapping AC RPM behavior to this app

| AC concept | Android equivalent |
| --- | --- |
| Engine RPM at speed | `evenlySpacedGearRatio` + D-mode coupling (not AC physics) |
| Gear count | `gearRatios.size` |
| Shift RPM threshold | `upshiftRpm` + `upshiftSpeedKmh` bands |
| Redline / limiter | `redlineRpm`, `limiterRpm` + LIMITER layers |
| Layer crossfade vs RPM | `SampleLayerSpec` curves or `bandLayers()` |
| Shift sound | `SampleEffectSpec` on `shiftSerial` change |
| Master volume | `CarMasterVolumeRepository` per profile id |

Do **not** copy AC drivetrain torque or clutch models — EV physics stays Seal-calibrated.

---

## 10. Persistence (survives reinstall if app data kept)

| Pref file | Keys | Purpose |
| --- | --- | --- |
| `selected_car` | `profile_id` | Last selected car |
| `car_master_volume` | `{profileId}.master_gain` | Per-car master 0..1.2 (default 0.72) |
| `sample_layer_mix` | `{profileId}.{trackId}.volume/muted/solo` | Mixer |
| `sample_sound_effects` | effect masks per profile | Effect toggles |
| `engine_tuning` | global simulation/audio tuning | TUNE panel |

New cars automatically get default volume 0.72 until user adjusts.

---

## 11. Formulas quick reference

```
// Upshift speed threshold (gear index g, 0-based)
upshiftSpeedKmh(g) = topSpeedKmh × (g + 1) / gearCount

// Runtime ratio (NOT stored gearRatios[g])
ratio(g) = (upshiftRpm − idleRpm) / boundaryWheelRpm(g)

// D-mode target RPM
targetRPM = idleRpm + wheelRpm × ratio(currentGear)

// N/P target RPM
targetRPM = idleRpm + throttle × (redlineRpm − idleRpm)

// Layer varispeed
playbackRatio = rpm / autopitchRootRpm

// Layer gain
gain = amplitude(rpm) × 10^(dB(rpm, throttle) / 20)

// Normal upshift
throttle > 0.10 AND speedKmh ≥ upshiftSpeedKmh(g)

// Emergency upshift
rpmForSpeed(g) ≥ redlineRpm × 0.98

// Downshift
speedKmh ≤ max(rememberedUpshiftKmh − 4, 2)
OR (throttle > 0.78 AND speedKmh < rememberedUpshiftKmh − 10)

// Exponential smoothing
value += (target − value) × (1 − exp(−dt / τ))
```

---

## 12. Known doc/code gaps (verify in source)

1. **`ui-display-and-simulation-decisions.md` §3.2** — D-row says throttle-driven RPM; **code uses road-speed coupling** (§3.3 in that doc is correct).
2. **`gearRatios` numeric values** — tuning graph only; runtime uses equal bands from `topSpeedKmh`.
3. **SIM coast regen** — hardcoded 2.5 m/s² in `EngineProfile`, not yet in TuningConfig UI.
4. **BYD LIVE gearbox mapping** — fallback constants P=1,R=2,N=3,D=4 if SDK fields not discovered; R/S/M map to NEUTRAL for sound.

---

## 13. Tests to run after car or audio changes

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew :mobile:testDebugUnitTest :mobile:assembleDebug
```

Important unit tests:

| Test file | What it guards |
| --- | --- |
| `EngineSimulationTest.kt` | Equal bands, downshift hysteresis, D RPM vs speed, P park speed |
| `SampleEngineRendererTest.kt` | Profile completeness, coast experiment, effects, solo |
| `DriveControllerInputTest.kt` | BYD vs SIM input arbitration |
| `BydTransmissionControlTest.kt` | BYD LIVE shifter lock |

Scripted integration (no UI): `DriveControllerScriptedIntegrationTest` (androidTest).

---

## 14. Related external project

**Assetto Corsa RPM simulator:** `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`

Use it to prototype RPM curves, layer roots, and shift feel. Deliverables for **this** repo are always:

- Kotlin `EngineSampleProfile` (or `bandProfile` inputs)
- Enumerated WAV list in `build.gradle.kts`
- Preview JPG
- Passing renderer tests

No runtime dependency between the two projects.

---

## 15. Suggested reading order for the next LLM

1. This document (full pass)
2. Skim `EngineSampleProfile.kt`, `SampleEngineRenderer.kt`, `EngineSimulation.kt`
3. Read one complete profile: `HuracanProfile.kt` **and** `AdditionalCarProfiles.kt` — understand both patterns
4. [sample-engine-audio.md](sample-engine-audio.md) — extraction tooling
5. [byd-seal-performance-calibration.md](byd-seal-performance-calibration.md) — why EV physics must stay separate
6. `mobile/build.gradle.kts` — asset enumeration pattern

When adding cars from AC: prefer **`bandProfile()`** unless you have full FMOD-style automation; match **root RPMs** and **gear count** to the AC simulator's behavior for that vehicle; keep **authored sample rates** honest.
