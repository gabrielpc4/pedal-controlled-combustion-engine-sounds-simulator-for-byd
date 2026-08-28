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
- `AudioTrack` output with focus/lifecycle diagnostics and logical stereo/quad/5.1/7.1 modes.
  Logical multichannel does not prove discrete physical speaker access; stereo routed through the
  vehicle DSP is usually the safest low-latency default;
- persistent diagnostics that survive app/process closure; and
- read-only reflective BYD pedal/speed probing with 20 ms polling and simulator fallback.

Car profiles are extensible data/configuration. A new profile needs its source files listed in
`mobile/build.gradle.kts`, an `EngineSampleProfile`, verified RPM/sample mappings, preview asset,
specs, and tests. Keep source material local and ignored unless the user has explicit redistribution
rights.

## Key code map

| Area | Main files | Notes |
| --- | --- | --- |
| Dashboard/UI | `MainActivity.kt`, `TuningPanel.kt`, `SoundEffectsPanel.kt` | Compose layout, pedals, shifter, tuning, effects, diagnostics entry |
| Drive coordination | `drive/DriveController.kt` | 200 Hz loop, input arbitration, profile changes, telemetry/audio/simulation state |
| Simulation | `simulation/EngineSimulation.kt`, `TransmissionPosition.kt` | D speed/RPM coupling, SIM road physics, P/N behavior, shifts |
| Audio | `audio/EngineAudioEngine.kt`, `SampleEngineRenderer.kt`, `EngineSampleProfile.kt`, `WavPcmDecoder.kt` | track lifecycle, sample decode/mix, profile automation, source-rate handling |
| Profile persistence | `audio/SelectedCarRepository.kt`, `SoundEffectsRepository.kt` | selected profile and per-profile effect state |
| BYD telemetry | `telemetry/BydSpeedReader.kt`, `BydReadOnlyPermissionContext.kt` | reflection, permission diagnostics, read-only compatibility context |
| Tuning | `tuning/TuningConfig.kt`, `TuningRepository.kt` | persisted, live-editable simulation/audio controls |
| Diagnostics | `EngineSoundsSimulatorApplication.kt`, `diagnostics/PersistentDiagnosticLog.kt` | lifecycle/crash retention and bounded event log |

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

Do not claim live pedal support works until the diagnostics panel and persistent log show plausible
accelerator/brake/speed values. If it is still denied, retain simulator input and record the exact
failure; do not attempt root, `pm grant`, spoofed packages, or broader permissions.

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
  UI/diagnostic state on every audio write; both create avoidable GC pressure.
- Diagnostic/meter state is published every 12 writes, route information every 48 writes, and a
  new `AudioTrack` underrun grows the effective buffer by one native burst up to capacity.
- Sample profiles retain their authored RPM domain. Do not stretch the tach/sample axis simply to
  match a guessed engine redline.
- The renderer performs app-side cubic resampling only where a profile's source rate differs from
  its selected playback rate. Keep profiles at their verified source/playback rates and investigate
  car-specific rate problems locally rather than changing every profile.
- Effects are optional per profile. `SOLO CHECKED EFFECTS` intentionally mutes continuous engine
  layers and plays only checked effects; an empty checked set is silent.
- Sample loading failures are visible in the app and persist `sample_engine_load_failed` diagnostics.

Read [sample-engine-audio.md](sample-engine-audio.md) before changing sample mappings. It records
the recovery/reconstruction confidence and licensing boundary.

**Audio / simulation context for other LLMs:** [llm-handoff-audio-simulation-and-car-porting.md](llm-handoff-audio-simulation-and-car-porting.md) describes the current WAV pipeline, coast vs legacy mix modes, tach/RPM/shift behavior, and how cars are registered today.

## Diagnostics and scripted validation

Persistent low-rate events are fsynced to:

```text
/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log
```

At 256 KiB it rotates to `drive-events.previous.log`. It records lifecycle, crashes, telemetry
probe/read changes, input-source changes, 1 Hz drive heartbeats, shifts, audio focus/track/error
state, and sample load information. Never write on the 200 Hz simulation tick or audio buffer loop.

Retrieve a debug build's log:

```powershell
$adb = 'D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
& $adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log |
    Select-String 'drive_heartbeat|shift|byd|sample_engine'
```

`DriveControllerScriptedIntegrationTest` is the preferred no-UI test for input/shift/tach behavior.
Use it before trying to diagnose timing with slow desktop UI automation.

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
`BuildConfig`, Android `versionCode`/`versionName`, the Diagnostics title, and the debug APK name:

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

The connected emulator is normally `emulator-5554`, AVD `BYD_Seal_1920x1080`, configured at
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
7. [Persistent diagnostics](persistent-diagnostics.md)
8. [BYD API/research notes](byd-dilink-api-v1.0.5.md), [research findings](research-findings.md),
   and [Electro APK analysis](electro-apk-analysis.md)

The emulator can validate UI, APK startup, sample decoding, renderer state, and deterministic
simulation behavior. It cannot validate BYD permissions, the vehicle DSP/speaker routing, actual
pedal latency, or real cabin acoustics. Keep observed facts distinct from estimates.
