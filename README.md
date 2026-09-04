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

The dashboard module is `mobile`. User-owned FMOD banks are transferred through the vehicle file
manager and imported by the dashboard itself; no companion installer APK is required. Set
`fmod.sdk.dir` in `local.properties` to the supplied Android FMOD Studio API. Generate the
file-manager payloads from the local source-bank folders first:

```sh
python3 tools/build_fmod_bank_packs.py
./gradlew :mobile:assembleRelease --no-daemon -PcarApk=true
python3 tools/export_file_manager_car_packs.py --groups all
```

Assembly produces a signed, locally numbered dashboard APK. The exporter creates one delivery
folder containing that APK plus 512 MiB original/modded file-manager batches, each with its own
exact copy-path instructions.

## Install

For the `Simple_Automotive` emulator or another dedicated test device:

```powershell
adb install --bypass-low-target-sdk-block -r mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<number>-debug.apk
```

Install `manual_car_pack_bundles/DASHBOARD_APK` through the vehicle's enabled USB APK route, then
copy the `fmod-bank-import` folder from `AUDIO_PACKS/*/BATCH_*` to the exact Android/data path
stated in its `COPY_TO_BYD_INTERNAL_STORAGE.txt`. Import one batch at a time and wait for the
completion message before continuing. The dashboard contains no alternate audio fallback and
remains silent until the selected native bank is imported.

Use the app only while parked or in a controlled environment. Its audio can mask navigation,
alerts, and other safety cues.
