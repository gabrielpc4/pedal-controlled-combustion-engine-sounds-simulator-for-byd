# Fresh-chat LLM handoff

Read this document before changing the project. It reflects the current implementation; verify Git
state and source before assuming it is still current.

## Repository and required workflow

- Project checkout: `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`.
- Do not work in `C:\Users\Gabriel\Documents\ChatGPT\BYDMotorSound`; it may be a separate empty
  local repository.
- Remote: `git@github.com:gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd.git`.
- **Primary branch: `main`.** All current work lives here. Pull `origin/main` before changing anything.
- Check `git status -sb`, `git branch --show-current`, `git log -1 --oneline` after pulling.

The user expects this after every change, including documentation:

1. Build and run relevant tests/lint.
2. Generate the debug APK, install it, and launch it in the emulator foreground.
3. Commit and push.

Never commit APKs/AABs, Gradle build output, reference APKs/PDFs, raw/decoded audio samples, or
preview images. They are intentionally ignored.

## Product boundary

This is a private, experimental Android dashboard that turns read-only pedal/speed input from a
BYD Seal DiLink head unit into simulated combustion-engine audio. Target firmware observed is
`13.1.33.2503250.1` (family `2503`). It is not road-certified.

- Do not add vehicle setters, CAN injection, rooting, firmware changes, or package replacement
  classes below `android.hardware.bydauto.*`.
- Never expose or persist IMEI, ICCID, VIN, location, credentials, or other vehicle identifiers.
- Treat on-car testing as parked/controlled testing. App audio can mask alerts.
- Audio and simulation are Activity-owned and stop when the Activity stops. Do not turn this into
  background playback without an explicit, reviewed service design.

## Current implementation

The shipping application is the `mobile` module. `automotive` and `shared` are unused template
modules, not the DiLink app.

`mobile` contains:

- a full-screen Compose dashboard designed around a 1920 x 990 safe viewport;
- P/N/D simulation, touch and keyboard simulator pedals, editable tuning curves/parameters, car
  preview selection, and car-specific specs/effect controls;
- a 200 Hz longitudinal simulator based on BYD Seal Performance public/digitized calibration;
- a **speed-coupled D-mode tach**: raw whole-km/h live speed is reconstructed into a continuous
  speed estimate before driving RPM/audio, preventing abrupt sound changes at integer speed steps;
- equal top-speed bands derived from the selected sample profile's sound-gear count; each ratio is
  derived to reach normal shift RPM at its band boundary, with hysteresis and safety upshifts;
- P/N free-rev behavior, while D remains road-speed-coupled; do not reintroduce old pedal-force
  D-mode tach logic, combustion clutch bog, or launch lag;
- a sample-only, profile-driven audio renderer: layered RPM/load/coast/idle WAV loops, persistent
  fractional cursors, cubic interpolation, stereo preservation, crossfades, optional effects, and
  no procedural synth fallback;
- one fixed true-stereo `AudioTrack` with focus/lifecycle handling. On-car testing established that
  this is the route BYD distributes to the complete factory speaker system; do not restore logical
  quad/5.1/7.1 negotiation or its former UI selector;
- read-only reflective BYD pedal/speed probing with 20 ms polling and simulator fallback.

Car profiles are extensible data/configuration. A new profile needs its source files listed in
`mobile/build.gradle.kts`, an `EngineSampleProfile`, verified RPM/sample mappings, preview asset,
specs, and tests. Keep source material local and ignored unless the user has explicit redistribution
rights.

## Key code map

| Area | Main files | Notes |
| --- | --- | --- |
| Dashboard/UI | `MainActivity.kt`, `TuningPanel.kt`, `SoundEffectsPanel.kt` | Compose layout, pedals, shifter, tuning, effects, and 60 Hz mixer |
| Drive coordination | `drive/DriveController.kt` | 200 Hz loop, input arbitration, profile changes, telemetry/audio/simulation state |
| Simulation | `simulation/EngineSimulation.kt`, `TransmissionPosition.kt` | D speed/RPM coupling, SIM road physics, P/N behavior, shifts |
| Audio | `audio/EngineAudioEngine.kt`, `SampleEngineRenderer.kt`, `EngineSampleProfile.kt`, `WavPcmDecoder.kt` | track lifecycle, sample decode/mix, profile automation, source-rate handling |
| Profile persistence | `audio/SelectedCarRepository.kt`, `SoundEffectsRepository.kt` | selected profile and per-profile effect state |
| BYD telemetry | `telemetry/BydSpeedReader.kt`, `BydReadOnlyPermissionContext.kt` | reflection, validation, read-only compatibility context |
| Tuning | `tuning/TuningConfig.kt`, `TuningRepository.kt` | persisted, live-editable simulation/audio controls |
| Meter bridge | `audio/RealtimeLayerMeterBus.kt` | preallocated audio-thread publication and per-frame UI snapshots |

## BYD input: evidence and current limitation

The BYD framework class and getters exist on the target car, but the original ordinary-app probe
failed with `SecurityException` for signature-only `android.permission.BYDAUTO_SPEED_GET`.
The project declares `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET` and targets SDK 25 for this
DiLink compatibility experiment.

`BydReadOnlyPermissionContext` is a deliberately narrow wrapper passed to the vendor SDK. It only
reports the two speed-read permissions as granted to that SDK client path; it never grants SET
permissions or implements vehicle control. Community evidence indicates this bypasses the wrapper's
client-side check on some DiLink versions. Firmware `2503` still requires an on-car retest to
establish whether a second service/Binder-side enforcement blocks it.

Do not claim live pedal support works until the header selects BYD pedals and the controls respond
plausibly on the car. If it is still denied, retain simulator input; do not attempt root, `pm grant`,
spoofed packages, or broader permissions.

The listener class is abstract, so a standard Java dynamic proxy cannot instantiate it. Polling the
getters every 20 ms is the current safe path. Do not add bytecode-generation/DexMaker listener
machinery without a compile-only vendor SDK and a specific on-car reason.

## Audio facts that matter

- Samples are local `audio_samples/...` inputs. Gradle copies only explicitly enumerated WAVs and
  preview images into generated application assets.
- The renderer preserves both source channels and mixes all layers/effects into one continuous
  `AudioTrack`; it does not depend on Assetto Corsa, FMOD, or the game at runtime.
- Decoded WAVs stay as interleaved PCM16 rather than expanded float arrays. The decoder writes
  directly into retained storage, and the 48 kHz inner mixer loop must remain allocation-free.
  Do not replace indexed per-frame voice traversal with collection iterators or publish immutable
  UI state on every audio write; both create avoidable GC pressure.
- Meter primitives are published allocation-free every three writes (roughly 60 Hz at the normal
  256-frame/48 kHz route). Compose consumes them from `Choreographer` once per display frame. A new
  `AudioTrack` underrun still grows the effective buffer by one native burst up to capacity.
- Sample profiles retain their authored RPM domain. Do not stretch the tach/sample axis simply to
  match a guessed engine redline.
- The renderer performs app-side cubic resampling only where a profile's source rate differs from
  its selected playback rate. Keep profiles at their verified source/playback rates and investigate
  car-specific rate problems locally rather than changing every profile.
- Effects are optional per profile. `SOLO CHECKED EFFECTS` intentionally mutes continuous engine
  layers and plays only checked effects; an empty checked set is silent.
- Sample loading failures stop playback and remain represented by the audio state; no file or in-memory event log exists.

Read [sample-engine-audio.md](sample-engine-audio.md) before changing sample mappings. It records
the recovery/reconstruction confidence and licensing boundary.

**Audio / simulation context for other LLMs:** [llm-handoff-audio-simulation-and-car-porting.md](llm-handoff-audio-simulation-and-car-porting.md) describes the current WAV pipeline, coast vs legacy mix modes, tach/RPM/shift behavior, and how cars are registered today.

## Build, install, run, and artifact identity

Known host paths:

```powershell
$project = 'D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound'
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$adb = 'D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
Set-Location $project

.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon
```

Every assembly increments `mobile/build-number.properties`; the same number is embedded in
`BuildConfig`, Android `versionCode`/`versionName`, the dashboard header, and the debug APK name:

```text
mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<build>-debug.apk
```

Install and foreground the exact generated artifact:

```powershell
$apk = Get-ChildItem mobile\build\outputs\apk\debug\engine-sounds-simulator-build-*-debug.apk |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
& $adb install --bypass-low-target-sdk-block -r $apk
& $adb shell am force-stop com.gabrielpc.enginesoundsimulator
& $adb shell monkey -p com.gabrielpc.enginesoundsimulator -c android.intent.category.LAUNCHER 1
& $adb shell dumpsys activity activities | Select-String 'topResumedActivity'
```

The connected emulator is normally `emulator-5554`, AVD `Simple_Automotive`, configured at
1920 x 1080 / 160 dpi. The app safe dashboard viewport is 1920 x 990. Because the app targets SDK
25, recent emulator builds require `--bypass-low-target-sdk-block` for installation.

Build output and `mobile/build-number.properties` are local/ignored state. Never commit them or the
APK itself.

## Essential reading order

1. [Engineering context](README.md)
2. [Audio / simulation context for LLMs](llm-handoff-audio-simulation-and-car-porting.md)
3. [Full implementation](full-implementation.md)
4. [Sample engine audio](sample-engine-audio.md)
5. [BYD Seal calibration](byd-seal-performance-calibration.md)
6. [UI display and simulation decisions](ui-display-and-simulation-decisions.md)
7. [BYD API/research notes](byd-dilink-api-v1.0.5.md), [research findings](research-findings.md),
   and [Electro APK analysis](electro-apk-analysis.md)

The emulator can validate UI, APK startup, sample decoding, renderer state, and deterministic
simulation behavior. It cannot validate BYD permissions, the vehicle DSP/speaker routing, actual
pedal latency, or real cabin acoustics. Keep observed facts distinct from estimates.
