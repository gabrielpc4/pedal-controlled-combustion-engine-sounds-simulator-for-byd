# FMOD bank installation

The dashboard APK contains runtime code and the 23 official-car previews. The separate
`audio-installer` APK carries the generated bank packages and publishes them through the
dashboard's exported provider.

## Package groups

`tools/build_fmod_bank_packs.py` reads official cars only from
`assetto_corsa_installation/content/cars`. It creates:

- `original_cars_pack`: official cars sourced from the Assetto Corsa installation.
- `modded_car_packs`: profiles discovered under `new_cars`.

Both groups are installable independently. The installer can publish the original group, the
modded group, or both; shared FMOD dependencies are installed automatically in all cases. The
current original-bank audit covers only the 23 official profiles, not the modded group.

Each active car package contains one source `.bank`, an optional preview selected from
`ui/dlc_preview.png` or an official skin preview, and that car's exported
`profiles/<id>/physics.json`. Shared original `common.strings.bank` and `common.bank` packages are
included as dependencies. The generated `fmod_bank_packs/` directory is ignored and must not be
committed.

```sh
python3 tools/build_fmod_bank_packs.py --force
./gradlew :audio-installer:assembleDebug --no-daemon
```

## Android installation

Install the dashboard before the installer, open **ENGINE FMOD BANKS**, and tap
The installer publishes each selected pack at:

```text
content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs/original_cars_pack/<pack-id>
```

The provider accepts only schema `byd-fmod-bank-pack-v3`, verifies paths, byte count, and SHA-256,
then atomically replaces the target directory. **DELETE ALL** removes every published group so a
fresh installation can start over. Missing bank, preview, or required `physics.json` metadata is a
package-generation error rather than a deferred dashboard failure.

## Runtime

Common original Assetto banks are loaded before the selected car bank. The dashboard rejects old,
modified, or missing packs rather than selecting another car. FMOD starts at authored idle and
plays only the engine and drivetrain events actually present in that bank; tires, wind, chassis,
and doors are excluded. The mixer shows the resulting FMOD hierarchy without changing its gain or
routing.
