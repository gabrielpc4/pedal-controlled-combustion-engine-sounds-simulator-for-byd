# FMOD bank installation

## Standalone apps

The `original` and `modded` dashboard variants each contain their complete car catalog and the two
shared FMOD dependencies. Install one APK to use that catalog offline; both apps can coexist:

| Variant | Application ID | Cars |
| --- | --- | --- |
| Original | `com.gabrielpc.enginesoundsimulator.original` | 105 original cars |
| Modded | `com.gabrielpc.enginesoundsimulator.modded` | 36 modded-catalog cars, including the Skyline R34 |

```sh
python3 tools/build_fmod_bank_packs.py
./gradlew :mobile:assembleOriginalRelease :mobile:assembleModdedRelease --no-daemon
```

The signed APKs are in `mobile/build/outputs/apk/original/release/` and
`mobile/build/outputs/apk/modded/release/`. Payload preparation checks that the selected catalog
exactly matches the current archives, verifies every payload checksum, and checks each physics
profile ID before packaging. The app reads the catalog and selected car's small physics metadata
at startup. The audio worker unpacks and verifies only that car and the shared dependencies into
`no_backup/embedded-audio/fmod-banks/`, then opens those three banks in FMOD. Other cars remain
inside the APK until selected. Previews are separate small assets and never trigger bank extraction.
Verified extracted banks are reused; an APK update replaces a cached pack when its manifest changes.
The APK remains on disk alongside the extracted banks for cars used so far, so allow additional
storage beyond the APK's size. Interrupted preparation leaves no published partial bank.

Each app filters every selection path to its catalog. The picker opens with that catalog selected;
a saved car outside it is replaced by its first available car (Alfa Romeo 4C for Original, Aston Martin
DBS for Modded). Shared banks are dependencies and never appear as cars.

## Switching to external banks

Build the same app identity with `-PbankDelivery=external` to omit embedded assets in a later update:

```sh
./gradlew :mobile:assembleOriginalRelease :mobile:assembleModdedRelease -PbankDelivery=external --no-daemon
```

Keep the same signing certificate and increase the version code. These variants retain their
catalog filters, but discover banks through the existing verified external importer. Stage bank
archives beneath `Android/data/<application-id>/files/fmod-bank-import/`, using the application ID
above. Previously extracted embedded banks live separately and are not treated as externally
installed packs. Deliver the external packs when switching modes; a car becomes available once its
own pack and both shared dependencies have been imported.

The `separate` variant retains the original dashboard identity and exposes both externally installed
groups. Build it with `:mobile:assembleSeparateRelease`; the file-manager exporter selects this APK.

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
./gradlew :mobile:assembleSeparateRelease --no-daemon
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

For a single-copy USB delivery, the same `fmod-bank-import` tree may be placed directly under
`Android/data/com.gabrielpc.enginesoundsimulator/files/` before copying the whole `Android`
folder to the root of the vehicle's Internal storage. The dashboard accepts all active archives
in one pass; no installer APK or batch folders are required.

Do not copy files to `/data/user/0`; that is private Android storage and is inaccessible to a normal
file manager. The `Android/data/.../files` destination is the app-owned portion of shared internal
storage and needs no broad storage permission.

## Runtime

Common original Assetto banks are loaded before the selected car bank. The dashboard rejects old,
modified, or missing packs rather than selecting another car. FMOD starts at authored idle and
plays only the engine and drivetrain events actually present in that bank; tires, wind, chassis,
and doors are excluded. The mixer shows the resulting FMOD hierarchy without changing its gain or
routing.
## Installer APKs

Build `assembleModdedRelease` and `assembleOriginalRelease` to produce two large installers:
`engine-sounds-audio-installer-moddedRelease.apk` and
`engine-sounds-audio-installer-originalRelease.apk`. Install the dashboard first, then install
the desired companion and press its single install button. The modded and original installers have
different package IDs, so both can be installed together; each carries only its own group plus the
shared dependencies. `DELETE ALL` remains available in either installer.
