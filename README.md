# BYD Motor Sound

An experimental Android engine-sound dashboard for a BYD Seal running DiLink firmware `13.1.33.2503250.1` (`2503`). It keeps the vehicle integration read-only and combines live pedal/speed telemetry with a simulator fallback. This is a bench and controlled-test prototype, not a road-certified vehicle feature.

The current application provides:

- a 200 Hz Seal Performance EV road model using BYD's published anchors plus A2MAC1's measured 3,170/3,975 Nm front/rear wheel-torque traces;
- seven presentation-only sound gears whose shifts and RPM drops never interrupt electric wheel torque;
- touch and keyboard accelerator/brake controls for emulator and bench testing;
- automatic use of valid live BYD accelerator, brake, and road-speed signals when exposed by the read-only DiLink API;
- an original procedural V10 sound source driven by RPM, load, throttle, shifts, overrun, and limiter state;
- negotiated stereo/quad/5.1/7.1 logical PCM output with routing and underrun diagnostics;
- a car-and-tachometer dashboard targeting the 1920 x 990 safe area measured on the emulator, pending actual-car `WindowInsets` measurement;
- a persistent live-tuning workstation with draggable front/rear wheel-torque and Sport-pedal curves, AWD distribution, complete road-load and sound-engine calibration, all seven presentation ratios, audio-layer mixing, and responsive graphs;
- the original telemetry capability/permission diagnostics retained behind the controller.

It invokes the documented BYD speed getters through reflection and does not package BYD framework classes. The manifest requests only the read-oriented `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET` permissions. No vehicle setter is implemented.

## Project status

The integrated simulator/audio APK builds and passes unit tests and Android lint. It still needs validation on the target vehicle to determine whether firmware `2503` permits direct third-party reads, how the BYD audio policy maps logical channels to physical cabin speakers, and what pedal-to-acoustic latency the factory DSP adds.

The DiLink application is the `mobile` module. The generated `automotive` module is an untouched AAOS media template and is not the current vehicle APK.

## Build

Requirements:

- Android Studio with Android SDK 37;
- the Android Studio embedded JDK available as `JAVA_HOME`;
- PowerShell or another shell capable of running the Gradle wrapper.

The `mobile` APK compiles against SDK 37 but deliberately targets SDK 25 for compatibility with the legacy DiLink vendor framework. It is a sideload build, not currently suitable for Google Play publication. This legacy target also means modern Android behavior and security defaults cannot be assumed.

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

Output:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

## Install

Only test while the vehicle is safely parked, or in a controlled environment with a passenger operating the app. The current build has no enforced driving lockout or calibrated maximum-volume ceiling. Synthetic audio can mask turn signals, ADAS warnings, navigation prompts, emergency vehicles, and other safety cues.

```powershell
adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
adb shell am start -n com.gabrielpc.bydmotorsound/.MainActivity
```

Android versions that block installation of very old target-SDK applications may require the host-side `adb install --bypass-low-target-sdk-block -r ...` option. Use it only on a dedicated test device after confirming that the installed ADB version supports it; it does not make the application production-ready.

Wireless ADB is a privileged maintenance channel. Enable it only in a trusted environment and do not publish ADB keys, passwords, IMEI, ICCID, VIN, or location data.

## Documentation

- [Engineering context](docs/README.md)
- [Full implementation](docs/full-implementation.md)
- [BYD Seal Performance calibration](docs/byd-seal-performance-calibration.md)
- [Live tuning interface](docs/tuning-interface.md)
- [Emulator validation record](docs/emulator-validation.md)
- [Drivetrain, game-audio, and sound-asset research](docs/drivetrain-audio-research.md)
- [Implemented POC](docs/poc-implementation.md)
- [POC and on-car test plan](docs/poc-plan.md)
- [BYD DiLink API V1.0.5 notes](docs/byd-dilink-api-v1.0.5.md)
- [Research findings](docs/research-findings.md)
- [Source provenance](docs/source-material/README.md)

## Important limitations

The BYD reader polls the three getters every 20 ms. The historical API also documents change listeners, but the relevant listener is an abstract vendor class and is not safely constructible through reflection alone. If direct getters work on the car, add a trustworthy compile-only listener integration and measure callback versus polling latency.

Android logical 5.1/7.1 output does not prove discrete access to physical cabin speakers. The BYD media bus and amplifier/DSP may downmix or distribute stereo to the full cabin. Validate the active HAL channel mask with `dumpsys media.audio_flinger` and confirm the physical speakers by listening on the actual vehicle.

Surround mirroring is experimental. The present 5.1/7.1 modes copy a full-band signal into every logical channel, including LFE. That is not a conventional surround mix and may interact unpredictably with OEM bass management or downmix gain. Prefer native-route stereo until the actual HAL and speaker mapping have been measured.

The application now refuses to start its renderer when the initial audio-focus request is denied, ramps duck/mute changes to avoid clicks, and releases focus on every shutdown path. That is necessary but not sufficient evidence of safe coexistence with warning, communication, navigation, or ADAS audio; verify the OEM policy on the car.

Playback is intentionally owned by the visible Activity in this release. Opening another application or otherwise stopping the dashboard also stops the simulator and audio; continuous background playback would require a separately reviewed service and notification policy.

This is independent research, not an official BYD product. It must remain read-only until every permission and runtime behavior is understood on the exact target firmware.

## License status

This repository does not currently contain a root software or asset license. Publishing source on GitHub does not by itself grant permission to copy, modify, or redistribute it; normal copyright defaults apply until the project owner selects explicit code and artwork terms. Any future sampled-audio license must be tracked separately and must not be assumed to follow the eventual source-code license.
