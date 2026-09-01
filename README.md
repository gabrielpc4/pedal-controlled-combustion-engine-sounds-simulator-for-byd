# Engine Sounds Simulator for BYD

A private Android dashboard experiment for a BYD Seal DiLink head unit. It reads vehicle signals
only when the vendor API permits them, or uses built-in simulator pedals, to drive a fictional
combustion-engine tachometer and direct FMOD Studio-bank audio. It does not control the vehicle.

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

The dashboard module is `mobile`; `audio-installer` is the separate installer that transfers the
user-owned original FMOD banks into the dashboard's private storage. Set `fmod.sdk.dir` in
`local.properties` to the supplied Android FMOD Studio API. Generate the installer payloads from
the local source-bank folders first:

```sh
python3 tools/build_fmod_bank_packs.py
./gradlew :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :audio-installer:assembleDebug --no-daemon
```

Assembly produces the locally numbered dashboard APK and an FMOD-bank installer APK.

## Install

For the `Simple_Automotive` emulator or another dedicated test device:

```powershell
adb install --bypass-low-target-sdk-block -r mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<number>-debug.apk
adb install --bypass-low-target-sdk-block -r audio-installer/build/outputs/apk/debug/engine-sounds-audio-installer-debug.apk
adb shell am start -n com.gabrielpc.enginesoundsinstaller/.AudioInstallerActivity
```

Tap **INSTALL ALL**, wait for the progress bar to complete, then start the dashboard. The dashboard
contains no decoded audio fallback and remains silent until the selected native bank is installed.

Use the app only while parked or in a controlled environment. Its audio can mask navigation,
alerts, and other safety cues.
