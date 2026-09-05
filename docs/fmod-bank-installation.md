# FMOD bank installation

The dashboard APK contains runtime code and official-car previews. Car banks are delivered through
the BYD file manager, or through the optional companion installer APK. This is deliberate: it keeps the dashboard as
the only APK that must be sideloaded and retains the same checksum validation and atomic publish
step before a bank reaches FMOD.

## Package groups

`tools/build_fmod_bank_packs.py` reads official cars only from
`assetto_corsa_installation/content/cars`. It creates:

- `original_cars_pack`: official cars sourced from the Assetto Corsa installation.
- `modded_car_packs`: profiles discovered under `modded_cars`.

Both groups are importable independently. The generated modded bundle also includes the shared
original FMOD dependencies required by every selected car. Copy both bundles to make both catalogs
available. The current original-bank audit covers the usable official profiles, not the modded
group.

Each active car package contains one source `.bank`, an optional preview selected from
`ui/dlc_preview.png` or an official skin preview, and that car's exported
`profiles/<id>/physics.json`. Shared original `common.strings.bank` and `common.bank` packages are
included as dependencies. The generated `fmod_bank_packs/` directory is ignored and must not be
committed.

```sh
python3 tools/build_fmod_bank_packs.py --force
./gradlew :mobile:assembleRelease --no-daemon -PcarApk=true
python3 tools/export_file_manager_car_packs.py --groups all
```

## BYD file-manager installation

`manual_car_pack_bundles/` is the complete file-manager delivery folder. First install the signed dashboard APK
from `DASHBOARD_APK/` through the vehicle's enabled USB APK route; the folder explains that this
APK must not be copied to `Android/data`. Then choose either
`AUDIO_PACKS/ORIGINAL_CARS` or `AUDIO_PACKS/MODDED_CARS`, start with `BATCH_01`, and read that
batch's `COPY_TO_BYD_INTERNAL_STORAGE.txt`. The required final path is:

```text
Internal storage/Android/data/com.gabrielpc.enginesoundsimulator/files/fmod-bank-import/
```

Copy the entire `fmod-bank-import` folder there, preserving its group subfolders. Open the
dashboard while parked, wait for the import-complete message, then repeat with the next batch.
Each batch is capped at 512 MiB so importing a full catalog does not require staging the entire
catalog alongside its installed copy. The background importer validates the schema, paths, byte
counts, and SHA-256 hashes, then atomically publishes every valid archive into private app storage.
Successful staging archives are deleted from the car to avoid retaining a second multi-gigabyte
copy. A broken archive remains in the staging folder and is reported in the dashboard message.

Do not copy files to `/data/user/0`; that is private Android storage and is inaccessible to a normal
file manager. The `Android/data/.../files` destination is the app-owned portion of shared internal
storage and needs no broad storage permission.

## Runtime

Common original Assetto banks are loaded before the selected car bank. The dashboard rejects old,
modified, or missing packs rather than selecting another car. FMOD starts at authored idle and
plays only the engine and drivetrain events actually present in that bank; tires, wind, chassis,
and doors are excluded. The mixer shows the resulting FMOD hierarchy without changing its gain or
routing.
## USB installer APK

`engine-sounds-audio-installer-release.apk` is intentionally small and contains no bank payload.
Install it alongside the dashboard, open it, press **CHOOSE USB FOLDER**, and select the folder
containing the `.bydbank` files (for example `Third Party Apps 55/AUDIO_PACKS`). The installer
scans subfolders, then the buttons copy original cars, modded cars, or both through the
dashboard's verified provider. It does not need broad storage permission and does not write
directly into private `/data/user/0` paths.
