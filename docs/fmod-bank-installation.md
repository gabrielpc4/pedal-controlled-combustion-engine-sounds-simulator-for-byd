# FMOD bank installation

The dashboard APK contains code and car previews only. It never contains a playable engine bank.
The separate `audio-installer` APK carries the generated packages and copies them into the
dashboard's private `fmod-banks` store through its exported, write-only provider.

## Build the packages

Run from the repository root after the source directories are available:

```sh
python3 tools/build_fmod_bank_packs.py
./gradlew :audio-installer:assembleDebug --no-daemon
```

The builder creates one package for every source car bank, plus the original Assetto
`common.strings.bank` and `common.bank` packages required by official banks that omit their own
event-name table. A car package carries exactly one original `.bank`, plus `GUIDs.txt` when the
source provides it. The builder validates the source selection, writes SHA-256 metadata, and uses
stored zip entries so the installer can stream the original bank without an intermediate decode.
Every car package also carries `profiles/<profile-id>/physics.json`, exported from that car's
original Assetto data. The dashboard and Audio Lab consume the same metadata. The generated
`fmod_bank_packs/` directory is ignored and must not be committed.

## Install on Android

Install the dashboard before the installer, open **ENGINE FMOD BANKS**, then tap **INSTALL ALL**.
The installer streams each package to:

```text
content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs/<pack-id>
```

The dashboard accepts only `byd-fmod-bank-pack-v2`, verifies paths, byte count, and SHA-256 into a
staging directory, then atomically publishes the bank, GUID lookup, and physics metadata. A retry
safely replaces only the target pack. **DELETE ALL** removes every published bank so a fresh
installation can start from zero. A v1 pack reports a clear delete-and-reinstall instruction.

## Runtime behavior

An alias profile may deliberately share a byte-identical owner package, but all other profiles use
their own source bank. The two installed common Assetto banks load before every car bank; they are
metadata and shared FMOD dependencies, never a substitute car. The app reports a load error instead
of selecting a different car. Once installed, the native FMOD bridge opens the banks directly from
private storage and starts only the permitted engine, transmission, turbo, limiter, shift, backfire,
traction-control, gear-grind, and start events. Tires, wind, chassis, and doors remain excluded.
