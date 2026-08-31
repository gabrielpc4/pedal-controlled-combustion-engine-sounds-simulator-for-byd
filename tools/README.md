# Assetto Corsa car-audio intake

`ac_car_audio_pipeline.py` keeps mod archives, Audio Lab car roots, FMOD graph
analysis, and WAV output separate from Android source assets. Every command
prints a JSON report, and `--report PATH` writes the same report atomically.

Inventory all supported archives without extracting them:

```sh
python3 tools/ac_car_audio_pipeline.py inventory \
  --archives-dir /Users/gabrielcarvalho/Downloads/new_cars \
  --report build/new-car-audio/inventory.json
```

Preview or execute bounded, safe intake. Intake streams only selected files
through `bsdtar`; it never asks the archive tool to write archive-controlled
paths. A car root is atomically renamed into Audio Lab only after every file
has been copied and hashed.

```sh
python3 tools/ac_car_audio_pipeline.py intake \
  --archive /path/to/car.zip --dry-run

python3 tools/ac_car_audio_pipeline.py intake \
  --archive /path/to/car.zip
```

Run the already-compiled macOS FMOD graph audit and enrich event GUIDs with the
paths from `sfx/GUIDs.txt`:

```sh
python3 tools/ac_car_audio_pipeline.py audit --car-id car_folder_name
```

The graph uses zero-based `subsoundIndex` values. Extraction converts those to
vgmstream's one-based selector and retains the bank's original loop metadata:

```sh
python3 tools/ac_car_audio_pipeline.py extract \
  --car-id car_folder_name --subsounds 4,5,6 --dry-run

python3 tools/ac_car_audio_pipeline.py extract \
  --car-id car_folder_name --selection-file selection.json
```

Generated per-car files live under `build/new-car-audio/<car-id>/` by default:

- `intake.json`
- `audit.json`, `graph.raw.json`, and GUID-enriched `graph.json`
- `selection.json`, `extraction.json`, and `wav/*.wav`

Intake also materializes one small car preview under
`build/new-car-audio/previews/<car-id>/`. Selection order is `ui/preview`, the
first skin preview, then `ui/badge`. `previews.json` and `previews.csv` map the
generated image back to the exact car and archive-relative source.

Once a car's selected WAV directory is final, create the external Android pack:

```sh
python3 tools/ac_car_audio_pipeline.py pack \
  --pack-id audi.tt.cup.2015 --pack-version 1 \
  --wav-root build/new-car-audio/audi_tt_cup_2015/wav
```

The `.bydpack` is reproducible: it has a root `manifest.json`, manifest-only
WAV members in sorted order, fixed ZIP timestamps and permissions, and the
exact metadata required by the Android importer (`sizeBytes`, SHA-256, sample
rate, channels, and frame count). It is written under
`build/new-car-audio/packs/` unless `--output` is supplied.

Use `--replace` deliberately when replacing an existing lab car root or WAV.

## Full-event Android atlas catalog

`car_catalog/build_car_catalog_packs.py` is the release boundary between the
36 staged cars, the 32 deduplicated FMOD bank families, and Android. The first
phase reads each car's actual Assetto physics, resizes its preview, and writes a
compact source catalog. It records the exact source used for every RPM/gear
field. The Corvette C6 mod's invalid `ENGINE_DATA.MINIMUM=-9000` is not guessed:
the report records its authored `THROTTLE_LUA.IDLE_RPM=700` fallback.
Donor sound coupling also carries the exact forward ratios, final-drive ratio,
and driven-wheel radius. FWD uses the front tyre, RWD uses the rear, and AWD
uses the same front/rear radius mean as Audio Lab. No radius fallback exists.
Invalid or outlying drivetrain final-drive values fail closed unless the same
mod declares an authored `[FINAL_GEAR_RATIO]` table in `setup.ini`. In that
case the first/default option is used with complete provenance. For example,
the No Hesi 370Z's placeholder `FINAL=9999` resolves through `final.rto` to its
first option, `Stock|5.2`; the catalog and Audio Lab apply the identical rule.
Each profile also carries the complete authored `TURBO_n` array and boost
normalization, or an explicit empty array for a naturally aspirated car. The
same evidence report records the effective Assetto backfire bounds (including
the executable's `MAXGAS=0.3` cap), limiter frequency, and the exact
`drivetrain_speed = signed speed m/s / driven tyre radius` unit contract. The
application's 40%-for-1-second backfire intent policy remains an additional
minimum; it does not replace a donor car's gas or RPM bounds.

```sh
python3 tools/car_catalog/build_car_catalog_packs.py prepare \
  --inventory docs/new-cars-inventory.csv \
  --audit build/new-car-audio/catalog-audit-summary.json \
  --preview-report build/new-car-audio/previews/previews.json \
  --audio-lab-root /Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab \
  --assetto-root /Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab/macos_bank_lab \
  --preview-assets-directory mobile/src/main/assets/car_previews \
  --source-output docs/generated/new-cars-android-catalog-source.json \
  --report-output docs/generated/new-cars-android-catalog-preparation-report.json
```

After all full-event NRT atlas families have passed their adaptive Audio Lab
oracle, assemble the USB-installable family packs and the catalog bundled in
the main APK. This final command also verifies every per-car `catalog-input`,
rejects orphan car/family directories, writes a canonical USB inventory plus
`SHA256SUMS`, and publishes the complete USB and Android catalog trees only
after every staged artifact has passed:

```sh
python3 tools/profile_generation/assemble_external_car_audio_release.py \
  --source-catalog docs/generated/new-cars-android-catalog-source.json \
  --atlas-root build/new-car-audio/full-event-atlases \
  --usb-output-directory build/new-car-audio/usb-audio-library \
  --pack-version 1 \
  --android-catalog-directory mobile/src/main/assets/car_catalog \
  --report-output build/new-car-audio/android-catalog-release-report.json
```

Assembly fails closed unless all 36 cars map to exactly 32 bank families and
every family has exact source-GUID conservation, an unchanged source bank, a
complete NRT realization, a PASS oracle with every probe passing, no blocked
runtime mapping, the versioned engine/effect host-gain plus shared anti-clipping
master contract, and a byte/hash-exact set of canonical PCM16/48 kHz/stereo
atlas shards. Each deterministic `.bydpack` contains WAV shards only. The USB
directory also contains `byd-audio-library-inventory.json` with the exact
archive/manifest identity of every pack and a conventional `SHA256SUMS` file.
The APK root catalog contains cars, physics, pack requirements, and
hash-verified runtime descriptors; each family runtime index is a separate
lazy-loaded JSON asset under `car_catalog/families`. Full audit evidence stays
in the build report.
