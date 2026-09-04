# Project context

Engine Sounds Simulator is a private Android dashboard for a BYD Seal DiLink head unit. It turns
read-only vehicle telemetry, or the built-in simulated pedals, into the authored Assetto Corsa
engine and drivetrain sound. The app never controls the vehicle.

## Current product boundary

The current catalog contains 111 usable official cars sourced from
`assetto_corsa_installation/content/cars`, including the Nissan Skyline GT-R R34, plus two shared
original-bank dependencies. The separate `modded_car_packs` group contains 35 user-supplied cars
discovered under `modded_cars`. Both groups can be installed
independently and the dashboard only exposes profiles whose verified package is actually
installed. The original and modded inventories are independent evidence sets: a limitation in one
must never cause a fallback to a similarly named car in the other.

The dashboard APK contains the runtime and previews. Car-bank archives are copied through the
BYD file manager into the dashboard's app-specific external-storage staging folder; the dashboard
then verifies and atomically imports them into private storage itself. No companion installer APK
is required. See [FMOD bank installation](fmod-bank-installation.md).

## Source of truth

Use the current Kotlin/C++ source and the original Assetto installation data. Audio Lab is the
reference for authored physics and FMOD event ordering. The FMOD bank, its parameters, and the
original per-car physics remain authoritative for real pedals, RPM, clutch, turbo, and authored
events. SIMULATED PEDALS is intentionally different: its road-speed envelope follows the BYD Seal
AWD and caps at 190 km/h. In D, both pedal modes use the same presentation gearbox, mapping every
sound gear to an equal road-speed band and the selected bank's limiter at the band boundary. P/N
remains a pure free-rev path.

The real BYD speed is truncated to an integer for the physical input. The bounded
presentation-speed estimator is retained only to keep tachometer and pitch continuous between
telemetry updates; it never changes the reported speed, gear decisions, vehicle load, or FMOD
gains. SIMULATED PEDALS keeps fractional speed only inside its Seal integrator, truncates the value
before the shared drivetrain, and uses the same estimator as REAL so both modes experience the
same integer-speed limitation.

Both modes pass the same normalized throttle/brake values, transmission position, authored gear
physics, equal-speed `fmodDrivetrainSpeed` mapping, and FMOD frame. REAL PEDALS is used only when a telemetry poll has all three required
signals (speed, accelerator, and brake); this avoids mixing live pedals with an invented fallback
speed. If the operator switches to SIMULATED while moving, its Seal speed model starts at the last
continuous presentation speed instead of resetting the dashboard to zero.

## Safety and privacy

- Vehicle access is read-only. Do not add CAN setters, rooting, firmware changes, or permission
  bypasses.
- Never commit vehicle identifiers, credentials, locations, complete driving traces, raw banks,
  generated packages, or reference APKs.
- Test while parked or in a controlled environment. The synthesized engine can mask safety alerts.

## Build and release

The local FMOD 2.03.14 SDK is supplied through `fmod.sdk.dir`. Build the bank packages first, then
assemble the signed dashboard and export the file-manager bundles. The dashboard build number is
incremented on an APK build. Generated packages, bundles, and APKs are ignored by Git.

```sh
python3 tools/build_fmod_bank_packs.py --force
./gradlew :mobile:assembleRelease --no-daemon -PcarApk=true
python3 tools/export_file_manager_car_packs.py --groups all
```

Install the dashboard APK from `manual_car_pack_bundles/DASHBOARD_APK` through the vehicle's
enabled USB APK route. Then copy one `fmod-bank-import` batch at a time from
`AUDIO_PACKS/*/BATCH_*` to the exact path documented inside its
`COPY_TO_BYD_INTERNAL_STORAGE.txt`, and open the dashboard while parked. The dashboard imports and
deletes verified staging archives automatically. The mixer is diagnostic; temporary mute/solo
controls reset when the car changes or the app starts. Per-car transmission, gear-shift, and turbo
trims remain user preferences until reset, while Settings also has a global transmission multiplier
that defaults to 0.5x and is applied before the per-car transmission trim.

## Documents

- [Architecture](architecture.md): runtime boundaries and FMOD ownership.
- [Vehicle integration and assets](vehicle-integration-and-assets.md): local input and asset rules.
- [FMOD bank calibration](fmod-bank-calibration.md): reference-bank inspection workflow.
- [FMOD bank installation](fmod-bank-installation.md): two-group package format and file-manager delivery.
- [Car audio validation](car-audio-validation.md): manual validation procedure and evidence.
- [Car subtitles](car-subtitles.md): generated, source-specific dashboard descriptions for every
  selectable original and modded profile.
- [Original-car audio inventory](original-cars-audio-inventory.md): generated authored-bank and
  runtime-audit reference for all usable official profiles.
- [Modded-car audio inventory](modded-cars-audio-inventory.md): generated authored bank graph,
  source, automation, and current app-policy reference for all modded profiles.
- [Modded-car Android runtime audit](modded-cars-runtime-audit.md): controlled Android playback
  evidence for the same modded profiles. It distinguishes FMOD lifecycle evidence from a 0% meter.
- [New-car exceptions](new-cars-exceptions.md): documented source, authoring, or runtime
  limitations that remain after the audit.
