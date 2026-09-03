# Project context

Engine Sounds Simulator is a private Android dashboard for a BYD Seal DiLink head unit. It turns
read-only vehicle telemetry, or the built-in simulated pedals, into the authored Assetto Corsa
engine and drivetrain sound. The app never controls the vehicle.

## Current product boundary

The first supported catalog contains exactly 22 official cars sourced from
`assetto_corsa_installation/content/cars`. The separate `modded_car_packs` group is generated for
future work but is inactive and cannot be installed or selected by the dashboard. Only the
`original_cars_pack` group is accepted at runtime.

The dashboard APK contains the runtime and previews. The separate `audio-installer` APK carries
the generated bank packages and publishes them atomically into the dashboard's private storage.
See [FMOD bank installation](fmod-bank-installation.md).

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
gains. Simulated speed is already continuous and therefore bypasses that estimator.

Both modes pass the same normalized throttle/brake values, transmission position, authored gear
calibration, and FMOD frame. REAL PEDALS is used only when a telemetry poll has all three required
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
assemble the dashboard and installer. The dashboard build number is incremented on an APK build.
Generated packages and APKs are ignored by Git.

```sh
python3 tools/build_fmod_bank_packs.py --force
./gradlew :mobile:assembleDebug :audio-installer:assembleDebug --no-daemon -PcarApk=true
```

Install the dashboard, install the installer, and use **INSTALL ORIGINAL CARS**. The installer can
remove all published packs with **DELETE ALL**. The mixer is diagnostic; its temporary mute/solo
and host-gain controls reset when the car changes or the app starts.

## Documents

- [Architecture](architecture.md): runtime boundaries and FMOD ownership.
- [Vehicle integration and assets](vehicle-integration-and-assets.md): local input and asset rules.
- [FMOD bank calibration](fmod-bank-calibration.md): reference-bank inspection workflow.
- [FMOD bank installation](fmod-bank-installation.md): two-group package format and installer.
- [Car audio validation](car-audio-validation.md): manual validation procedure and evidence.
- [New-car exceptions](new-cars-exceptions.md): documented source or bank limitations.
