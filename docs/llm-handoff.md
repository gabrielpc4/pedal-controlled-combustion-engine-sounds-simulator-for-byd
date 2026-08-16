# Fresh-chat LLM handoff

This document is the practical starting point for a new coding agent. Read it, then read the
linked engineering documents before changing vehicle, audio, or simulator behavior.

## Source of truth and Git

- The real project checkout is `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`.
- A separate `C:\Users\Gabriel\Documents\ChatGPT\BYDMotorSound` directory may exist as an empty,
  unborn Git repository containing only local `docs/` and `tmp/`. Do **not** implement or commit
  there.
- Default/source branch: `main`.
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
- procedural synthesized engine audio, audio focus handling, and experimental logical
  stereo/quad/5.1/7.1 output;
- read-only reflective probing/polling of BYD pedal and speed getters, with simulator fallback;
- durable app-private diagnostics, including crash retention and shift transitions.

The current design intentionally behaves like an EV with simulated sound gears. Do not reintroduce
combustion-engine clutch bog, torque interruption, or launch lag into the vehicle model.

## Architecture map

| Area | Key files | Responsibility |
| --- | --- | --- |
| UI | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/MainActivity.kt`, `TuningPanel.kt` | Compose dashboard, pedals, P/N/D shifter, target viewport, tuning UI |
| Controller | `drive/DriveController.kt` | 200 Hz worker, input arbitration, transmission position, simulation/audio coordination, transition/heartbeat logging |
| Simulation | `simulation/EngineSimulation.kt`, `simulation/TransmissionPosition.kt` | EV road force, synthetic RPM/gears, P/N/D behavior, shifts, live-speed handling |
| Audio | `audio/EngineAudioEngine.kt`, `EngineSynthesizer.kt` | AudioTrack lifecycle, focus, routing diagnostics, PCM synthesis/mirroring |
| Telemetry | `telemetry/BydSpeedReader.kt`, `telemetry/BydReadOnlyPermissionContext.kt` | reflective BYD capability probe, restricted client-context compatibility, and 20 ms getter polling |
| Tuning | `tuning/TuningConfig.kt`, `TuningRepository.kt` | editable/persisted engine, curve, vehicle, timing, and audio parameters |
| UI display | `VehicleDisplayUnits.kt`, `TuningPanel.kt` | cosmetic kgfm/HP conversions and graph annotation; does not alter physics |
| Diagnostics | `EngineSoundsSimulatorApplication.kt`, `diagnostics/PersistentDiagnosticLog.kt` | process/lifecycle/crash and bounded persistent event storage |

The manifest deliberately targets SDK 25 for the DiLink compatibility experiment while compiling
against SDK 37. It requests only `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET`.

## Current gear behavior and regression history

Lift-off from third gear previously hunted `3 -> 2 -> 3` when a **lift-off RPM retention** model made displayed RPM lag below road-coupled RPM. That retention layer was **removed** (2026-08). In **D**, synthetic RPM is now a **throttle-driven integrator** scaled by the wheel-power curve, with lift-off fall at the configured coast rate. Tunable in **TUNE → Vehicle → DRIVE RPM**.

A **P / N / D** column shifter beside the pedals (2026-08) replaced an earlier header **RPM MODE** toggle. Only **D** uses throttle-driven RPM and automatic shifts. **N** free-revs with throttle (no wheel drive, no auto shifts). **P** matches **N** for RPM but holds SIM speed at zero. Neutral rev-up/down use fixed inertia constants (`0.55 s` / `0.90 s`). Full detail: [UI display §3.2–3.3](ui-display-and-simulation-decisions.md#32-p--n--d-shifter-2026-08).

Current **D** behavior:

- WOT rise force follows the power curve at road speed; lift-off falls at `driveCoastFallRpmPerSec` toward idle;
- braking adds extra tach fall via `driveBrakeExtraFallRpmPerSec`;
- coasting downshifts settle without upshift hunting when road speed is held constant;
- regressions in `EngineSimulationTest.kt` cover D-mode throttle/coast/brake paths plus neutral/park shifter cases.

Do not reintroduce lift-off retention without a design that avoids the old display/road mismatch. Full context: [UI display §3.3](ui-display-and-simulation-decisions.md#33-d-mode-throttle-driven-rpm-2026-08).

`DriveControllerScriptedIntegrationTest` is the preferred no-UI regression test: it drives the
real controller directly to third gear, releases throttle, verifies it settles in second, then
asserts that the persistent log contains no upward shift after the lift marker.

## Persistent diagnostics

The app writes low-rate, fsynced events to:

```text
/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log
```

It rotates to `drive-events.previous.log` at 256 KiB (about 512 KiB retained total). It logs
session/activity lifecycle, controller start/stop/failures, BYD telemetry probe/read transitions, input-source changes, every shift
start/completion, 1 Hz drivetrain heartbeats, audio focus/start/track/error state, and uncaught
exceptions. Never call it for every 200 Hz simulation tick or audio render buffer.

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
