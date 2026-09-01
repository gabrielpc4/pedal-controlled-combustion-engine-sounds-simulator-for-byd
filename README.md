# Engine Sounds Simulator for BYD

A private Android dashboard experiment for a BYD Seal DiLink head unit. It reads vehicle signals
only when the vendor API permits them, or uses built-in simulator pedals, to drive a fictional
combustion-engine tachometer and profile-based sample audio. It does not control the vehicle.

The app is designed for the 1920 × 990 safe dashboard area observed on the rotating head unit.
Audio is intentionally delivered as fixed true stereo; the vehicle DSP distributes that route to
the factory speakers.

## Start here

The durable context for a future developer or LLM is in [docs/README.md](docs/README.md):

- [Architecture](docs/architecture.md)
- [Vehicle integration and local assets](docs/vehicle-integration-and-assets.md)

Those documents state the project boundaries and point back to the code as the source of truth.
They deliberately avoid mirroring volatile car profiles, tuning values, UI details, and historical
experiments.

## Build

The app module is `mobile`. The project expects the user-owned sample WAVs and previews in the
ignored `audio_samples/` tree; Gradle packages only the explicit allow-list in
`mobile/build.gradle.kts`.

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon
```

Assembly produces a locally numbered debug APK under
`mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<number>-debug.apk`.

## Install

For the `Simple_Automotive` emulator or another dedicated test device:

```powershell
adb install --bypass-low-target-sdk-block -r mobile\build\outputs\apk\debug\engine-sounds-simulator-build-<number>-debug.apk
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

Use the app only while parked or in a controlled environment. Its audio can mask navigation,
alerts, and other safety cues.
