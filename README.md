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
- [FMOD bank installation](docs/fmod-bank-installation.md)

Those documents state the project boundaries and point back to the code as the source of truth.

## Build

Set `fmod.sdk.dir` in `local.properties` to the supplied Android FMOD Studio API. Generate bank
packages first:

```sh
python3 tools/build_fmod_bank_packs.py
```

### Standalone Original / Modded apps (bundled banks)

Each APK contains its full catalog. No file-manager import is required.

```sh
./gradlew :mobile:assembleOriginalRelease :mobile:assembleModdedRelease --no-daemon
```

- Original: `com.gabrielpc.enginesoundsimulator.original`
- Modded: `com.gabrielpc.enginesoundsimulator.modded`

To build the same app IDs without embedded banks (external import workflow):

```sh
./gradlew :mobile:assembleOriginalRelease :mobile:assembleModdedRelease -PbankDelivery=external --no-daemon
```

### Separate-catalog dashboard (legacy two-group UI)

```sh
./gradlew :mobile:assembleSeparateRelease --no-daemon
python3 tools/export_file_manager_car_packs.py --groups all
```

Install `manual_car_pack_bundles/DASHBOARD_APK` through the vehicle's enabled USB APK route, then
copy each `AUDIO_PACKS/*/BATCH_*` folder to the path in its `COPY_TO_BYD_INTERNAL_STORAGE.txt`.

## Install (emulator / test device)

```sh
adb install --bypass-low-target-sdk-block -r mobile/build/outputs/apk/<flavor>/<type>/engine-sounds-simulator-build-<number>-<variant>.apk
```

Use the app only while parked or in a controlled environment. Its audio can mask navigation,
alerts, and other safety cues.
