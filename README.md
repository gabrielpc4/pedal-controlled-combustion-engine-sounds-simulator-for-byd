# Pedal-Controlled Combustion Engine Sounds Simulator for BYD

**Engine Sounds Simulator** brings responsive, pedal-driven combustion-engine sound to the BYD Seal head unit. The app reads accelerator, brake, and road speed from DiLink in real time, maps them through a calibrated electric drivetrain model, and drives a profile-based sample engine whose RPM, shifts, and limiter follow your right foot — without touching vehicle control.

Built for the rotating BYD tablet on DiLink firmware `13.1.33.2503250.1` (`2503`). Vehicle integration is read-only: no setters, no CAN injection, no rooted firmware.

## What it does

- **Live pedal control** — accelerator and brake depth from `BYDAutoSpeedDevice`, with automatic fallback to touch/keyboard pedals when DiLink telemetry is unavailable
- **Calibrated Seal Performance response** — a 200 Hz EV road model anchored on BYD's published 390 kW / 670 Nm figures and A2MAC1 measured front/rear wheel-torque curves
- **Presentation gears that never bog the car** — seven synthetic ratios shape tachometer RPM and sound only; wheel torque stays continuous through every shift
- **Profile-based engine audio** — a true-stereo near-car exterior event reconstructed from bank-authored RPM, throttle, gain, pitch, and loop controls
- **Cabin audio routing** — stereo, quad, 5.1, or 7.1 logical output with live route, buffer, and underrun diagnostics
- **Full-screen dashboard** — landscape tachometer, virtual pedals, **P / N / D** shifter, input-mode controls, and a persistent tuning workstation for curves, ratios, and audio layers
- **On-device diagnostics** — durable shift, lifecycle, and crash logs for field tuning without a live `logcat` session

## How it works

```text
BYD pedals & speed (DiLink)     Touch / keyboard pedals
              \                       /
               +---- DriveController ----+
                          | 200 Hz
                   EngineSimulation
                     /           \
              Dashboard UI    EngineAudioFrame
                  30 Hz              |
                           SampleEngineRenderer
                                     |
                          cabin AudioTrack output
```

The electric model handles mass, drag, rolling resistance, and measured axle torque. The sound layer sits on top: fictional gears convert road speed into gauge RPM and shift timing while the EV underneath keeps behaving like a Seal.

## Target vehicle

| Item | Value |
| --- | --- |
| Vehicle | BYD Seal (Performance AWD) |
| Head unit | DiLink `13.1.33.2503250.1` / family `2503` |
| APK module | `mobile` (full-screen sideload app) |
| Display name | Engine Sounds Simulator |

## Build

Requirements: Android Studio with SDK 37, embedded JDK as `JAVA_HOME`.

The `mobile` module compiles against SDK 37 and targets SDK 25 for DiLink vendor-framework compatibility.
Audio builds also require the locally decoded profile WAVs documented in [Profile-based sample engine audio](docs/sample-engine-audio.md). They are intentionally Git-ignored and not redistributable from this repository.

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk`

## Install

Sideload the debug APK on the BYD tablet or a matching emulator. On Android versions that block legacy target-SDK apps, use `adb install --bypass-low-target-sdk-block -r ...` on a dedicated test device only.

```powershell
adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

Use only while parked or in a controlled environment. Synthetic engine audio can mask turn signals, ADAS alerts, navigation, and other safety cues.

## Documentation

- [Sample-based engine audio](docs/sample-engine-audio.md) — local asset packaging, seamless RPM/load blending, diagnostics, and code-driven validation.
- [Engineering context](docs/README.md)
- [Full implementation](docs/full-implementation.md)
- [BYD Seal Performance calibration](docs/byd-seal-performance-calibration.md)
- [Live tuning interface](docs/tuning-interface.md)
- [Emulator validation](docs/emulator-validation.md)
- [Persistent diagnostics](docs/persistent-diagnostics.md)
- [Research findings](docs/research-findings.md)
- [Source provenance](docs/source-material/README.md)

## Safety and scope

This is an independent enthusiast project, not an official BYD product. It requests only read-oriented DiLink speed permissions, never writes to vehicle ECUs, and stops audio when the dashboard is not visible. Treat it as a sound and tuning tool for parked or supervised use until you have validated permissions, speaker routing, and audio-focus behaviour on your exact firmware.

## License

No root software or asset license is declared yet. Normal copyright defaults apply until explicit terms are added.
