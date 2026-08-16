# Fresh-chat LLM handoff

This document is the practical starting point for a new coding agent. Read it, then read the
linked engineering documents before changing vehicle, audio, or simulator behavior.

## Source of truth and Git

- The real project checkout is `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`.
- A separate `C:\Users\Gabriel\Documents\ChatGPT\BYDMotorSound` directory may exist as an empty,
  unborn Git repository containing only local `docs/` and `tmp/`. Do **not** implement or commit
  there.
- Current sample-engine development branch: `codex/sample-engine`; confirm whether it has been merged before starting new work.
- Remote: `origin` -> `git@github.com:gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd.git`.
- Confirm the current state yourself with `git status -sb` and `git log -1 --oneline`; another
  agent may have moved the branch after this handoff was written.

The user requires this workflow after **every** change, including documentation-only changes:

1. Build and verify the relevant tests.
2. Install the updated debug APK and launch it in the emulator foreground so the user can test.
3. Commit and push the change.

Never commit APKs, AABs, build output, reference APKs, PDFs, or other supplied artifacts. They are
ignored intentionally. Start new work from an up-to-date `main`. Use a short-lived `codex/` branch
for the change, then make sure the requested pushed result is reachable from the repository branch
agreed with the user.

## Product and safety boundary

This is an experimental, read-only Android engine-sound dashboard for a BYD Seal on DiLink firmware
`13.1.33.2503250.1` (family `2503`). It is not road-certified.

- Never add BYD vehicle setters, CAN injection, rooting, firmware changes, or packaged replacement
  classes under `android.hardware.bydauto.*`.
- Never expose or log IMEI, ICCID, VIN, location, ADB credentials, or other vehicle identifiers.
- Test on the actual car only while parked or in a controlled setting with appropriate attention to
  warning/navigation/ADAS audio. Synthetic audio can mask important sounds.
- The current app stops its simulator/audio when its Activity stops. Background playback needs a
  separately reviewed service design; do not silently change this behavior.

## What is implemented

The production APK is the `mobile` module. The `automotive` and `shared` modules are an untouched
AAOS media-template shell and are not the BYD application.

`mobile` provides:

- a 200 Hz EV longitudinal model calibrated from public Seal Performance anchors and digitized
  A2MAC1 wheel-torque measurements;
- independent presentation-only sound gears: shifts affect sound/RPM only and never interrupt EV
  wheel torque;
- a full-screen 1920 x 990 dashboard with simulator touch/keyboard pedals and a tuning workstation
  whose curves and parameters are editable in the UI;
- UI torque in kgf·m and power in values labeled HP (metric PS/cv), with wheel-derived graph values cosmetically scaled to motor ratings — see [UI display decisions](ui-display-and-simulation-decisions.md);
- profile-based multi-layer sample audio, audio focus handling, and experimental logical
  stereo/quad/5.1/7.1 output; local recordings under `audio_samples/` are ignored and must never be committed;
- profile-specific optional cabin powertrain effects (gear changes, transmission, and verified
  exhaust overrun) mixed in the same renderer, with persisted per-car checkboxes in `CAR EFFECTS`;
- read-only reflective probing/polling of BYD pedal and speed getters, with simulator fallback;
- durable app-private diagnostics, including crash retention and shift transitions.

The current design intentionally behaves like an EV with simulated sound gears. Do not reintroduce
combustion-engine clutch bog, torque interruption, or launch lag into the vehicle model.

## Architecture map

| Area | Key files | Responsibility |
| --- | --- | --- |
| UI | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/MainActivity.kt`, `TuningPanel.kt`, `SoundEffectsPanel.kt` | Compose dashboard, pedals, P/N/D shifter, target viewport, tuning and effect controls |
| Controller | `drive/DriveController.kt` | 200 Hz worker, input arbitration, transmission position, simulation/audio coordination, transition/heartbeat logging |
| Simulation | `simulation/EngineSimulation.kt`, `simulation/TransmissionPosition.kt` | EV road force, synthetic RPM/gears, P/N/D behavior, shifts, live-speed handling |
| Audio | `audio/EngineAudioEngine.kt`, `EngineSampleProfile.kt`, `SampleEngineRenderer.kt`, `SoundEffectsRepository.kt`, `WavPcmDecoder.kt` | AudioTrack lifecycle, profile automation, engine/effect mixing, per-car persistence, resampling, focus, and diagnostics |
| Telemetry | `telemetry/BydSpeedReader.kt`, `telemetry/BydReadOnlyPermissionContext.kt` | reflective BYD capability probe, restricted client-context compatibility, and 20 ms getter polling |
| Tuning | `tuning/TuningConfig.kt`, `TuningRepository.kt` | editable/persisted engine, curve, vehicle, timing, and audio parameters |
| UI display | `VehicleDisplayUnits.kt`, `TuningPanel.kt` | cosmetic kgfm/HP conversions and graph annotation; does not alter physics |
| Diagnostics | `EngineSoundsSimulatorApplication.kt`, `diagnostics/PersistentDiagnosticLog.kt` | process/lifecycle/crash and bounded persistent event storage |

The manifest deliberately targets SDK 25 for the DiLink compatibility experiment while compiling
against SDK 37. It requests only `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET`.

## Current gear and RPM behavior

A **P / N / D** column shifter passes the selected position into the 200 Hz simulation. **N** free-revs toward a throttle-position target with no automatic shifts or wheel drive. **P** uses the same tach model and holds SIM speed at zero.

Current **D** behavior:

- RPM is an independent integrated fake gauge. Road speed never targets, floors, synchronizes, or selects a gear.
- Raw pedal percentage, smoothed only for response, scales positive RPM force linearly.
- The maximum positive force is multiplied by normalized delivered Seal power at current road speed. A measured-torque bridge covers 0–30 km/h because mechanical power is zero at rest.
- Lift-off applies a constant strong negative RPM force immediately; brake adds a larger proportional negative force.
- Upshifts and downshifts transform only fake RPM by the selected sound profile's adjacent ratios. They never alter EV road force.
- Live speed remains useful for the power-envelope lookup and displayed speed/acceleration, but abrupt live-speed changes cannot directly move the needle or gear.
- The old road-coupled helper/calibration code and tests were deleted rather than retained as a compatibility path.

Full detail: [UI display §3.3](ui-display-and-simulation-decisions.md#33-independent-drive-rpm-force-model).

`DriveControllerScriptedIntegrationTest` is the preferred no-UI regression test: it drives the
real controller directly to third gear, releases throttle, verifies the strong negative force
settles it through the lower ratios into first, then asserts that the persistent log contains no
upward shift after the lift marker.

## Persistent diagnostics

The app writes low-rate, fsynced events to:

```text
/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log
```

It rotates to `drive-events.previous.log` at 256 KiB (about 512 KiB retained total). It logs
session/activity lifecycle, controller start/stop/failures, BYD telemetry probe/read transitions, input-source changes, every shift
start/completion, 1 Hz drivetrain heartbeats, audio focus/start/track/error state, and uncaught
exceptions. Never call it for every 200 Hz simulation tick or audio render buffer.

For the independent Drive RPM model, each heartbeat includes `rpm_power_permille` (the current
Seal propulsion-envelope multiplier), `rpm_push_per_s`, and `rpm_drag_per_s`. These make the
pedal/power/lift/brake behavior auditable after the Activity or process closes.

For a debug APK:

```powershell
$adb = 'D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
& $adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log |
    Select-String 'shift_started|shift_completed|drive_heartbeat'
```

The log remains readable after `adb shell am force-stop com.gabrielpc.enginesoundsimulator`. See
[persistent-diagnostics.md](persistent-diagnostics.md) for details and privacy restrictions.

## Build, install, run, and test

Host-specific paths currently known to work:

```powershell
$project = 'D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound'
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$adb = 'D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
Set-Location $project

.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon

& $adb install --bypass-low-target-sdk-block -r mobile\build\outputs\apk\debug\mobile-debug.apk
& $adb install --bypass-low-target-sdk-block -r mobile\build\outputs\apk\androidTest\debug\mobile-debug-androidTest.apk
& $adb shell am instrument -w -r com.gabrielpc.enginesoundsimulator.test/androidx.test.runner.AndroidJUnitRunner
& $adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

The connected emulator has been `emulator-5554`, using AVD `BYD_Seal_1920x1080` (Android 16/API 36,
x86_64, 1920 x 1080 at 160 dpi). The app's intended safe dashboard viewport is 1920 x 990.
Android 16 needs `--bypass-low-target-sdk-block` because the app intentionally targets SDK 25.

At this handoff, 50 JVM tests and 2 instrumentation tests pass. Lint has no errors; its warnings
are pre-existing compatibility/dependency/resource warnings, not blockers.

## Open vehicle-validation work

The first target-head-unit test established that the BYD speed class/getters exist but an ordinary application context is denied the signature-only `BYDAUTO_SPEED_GET`. The next validation questions are:

1. Does the restricted `BydReadOnlyPermissionContext` satisfy the getter checks on firmware `2503`, and are accelerator/brake/speed values plausible?
2. If it fails, does the persistent stack trace show a second server-side/Binder check that requires the separately reviewed shell-helper fallback?
3. Can the abstract BYD listener be integrated safely using a compile-only vendor SDK, or is 20 ms
   polling the safe fallback?
4. Does the car media route expose multichannel logical output, or does its DSP distribute a
   low-latency stereo route to the whole cabin? A logical 7.1 track does not prove discrete
   physical speaker access.
5. What are the actual pedal-to-acoustic latency and audio-focus interactions with vehicle alerts?

Follow the on-car checklists rather than guessing. The emulator cannot validate vendor API access,
the BYD amplifier/DSP channel mapping, or acoustic delay.

## Essential reading order

1. [Engineering context](README.md)
2. [Full implementation](full-implementation.md)
3. [BYD Seal calibration](byd-seal-performance-calibration.md)
4. [UI display and simulation decisions](ui-display-and-simulation-decisions.md)
5. [Persistent diagnostics](persistent-diagnostics.md)
6. [POC implementation and test plan](poc-implementation.md) and
   [POC plan](poc-plan.md)
7. [BYD API manual notes](byd-dilink-api-v1.0.5.md) and
   [research findings](research-findings.md)

Use the source-material index for provenance. Prefer documented evidence over assumptions and keep
any new estimates explicitly labeled as estimates.
