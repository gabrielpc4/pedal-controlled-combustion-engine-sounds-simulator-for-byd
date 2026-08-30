# Project context for future work

This directory contains durable engineering context, not a changelog or a duplicate of the Kotlin
source. A calibration number or UI label changing should not make these documents obsolete.

## Read this first

Engine Sounds Simulator is a private Android dashboard for a BYD Seal DiLink head unit. It turns
**read-only** vehicle telemetry, or built-in simulator controls, into a fictional combustion-engine
tachometer and bank-native FMOD audio. It never controls the vehicle.

Five selectable FMOD-native profiles are defined: Nissan Skyline GT-R R34, Lamborghini Huracan
Trofeo EVO2, Lamborghini Aventador SV, Alfa Romeo 4C, and Toyota Supra MK4. Every profile declares
its bank identity, tach/gear calibration, separately audible event allowlist, and semantic
capability routes. A capability can be a dedicated event or material embedded in `engine_int`;
an empty event stub is never treated as playable merely because its GUID exists.

The compatibility DSPs are behavior-specific rather than uniformly inert. The Distance Filter is
a descriptor/state-compatible fixed-cabin pass-through, Gain applies dB gain and optional polarity
inversion with smoothing, and Distortion applies amplification followed by a hard clip.

Only cockpit/core powertrain audio is in scope. The application intentionally does not load tire,
road, wind, chassis, bodywork, collision, door, horn, or other incidental car sounds. The two
naturally aspirated Lamborghinis do not instantiate bank turbo events, even when an unused turbo
graph is present.

The intended environment is a rotated BYD tablet. The observed target software is
`13.1.33.2503250.1` (family `2503`), but each DiLink firmware is a separate compatibility target.

## Source-of-truth order

When information conflicts, use this order:

1. Current Kotlin/C++ source, Gradle configuration, and automated tests.
2. Direct observation on the exact head unit and firmware being tested.
3. Primary vendor material and locally retained reference artifacts.
4. Community reverse engineering and older implementation notes.

Do not infer a permission grant, audio route, bank license, vehicle parameter, or API behavior
from a similarly named class, a different firmware, or an old document.

## Non-negotiable boundaries

- Vehicle access is read-only. Do not add setters, CAN transmission, rooting, firmware changes,
  package spoofing, or broader permission bypasses.
- Never expose, commit, or log vehicle identifiers, credentials, location, or complete driving
  traces. Some supplied screenshots contain IMEI/ICCID and must be treated as sensitive.
- FMOD SDK files, Assetto Corsa banks, previews, reference APKs, and generated build inputs remain
  local and ignored. They are not part of the repository's redistribution rights.
- A missing or invalid FMOD runtime/bank must fail closed with a diagnostic; never substitute a
  synthetic engine, decoded WAV, another car, or an empty bank event.
- Treat vehicle testing as parked or controlled testing. Synthetic sound can mask safety alerts.
- Audio may continue through the foreground runtime service after the Activity is backgrounded.
  Audio focus loss, the service stop action, and application shutdown must release FMOD cleanly.

## Working agreement

Use the checkout that contains this repository's `.git` directory and preserve unrelated changes.
For every source or documentation change:

1. Run unit tests, assemble the debug APK and test APK, and run lint.
2. Start `BYD_Seal_1920x1080`, run connected tests, install the generated APK, and foreground
   `com.gabrielpc.enginesoundsimulator/.MainActivity` when the emulator is available.
3. Run the deterministic FMOD `NOSOUND_NRT` check for every supported profile. Require finite,
   non-silent PCM together with event-start and sound-played callback evidence; a successful bank
   load, parameter trace, or UI animation is insufficient evidence that a sound is audible.
4. Commit and push only source and documentation. Do not commit APKs, build output, banks, FMOD
   binaries, raw/decoded samples, reference APKs, or private reference material.

`NOSOUND_NRT` renders into the native meter without using Android's speaker route. It validates the
bank graph and control stimulus, not BYD routing, latency, factory-DSP balance, or cabin acoustics;
those require a separate parked head-unit pass. Do not describe the complete test matrix as passed
without recorded results for the current build.

The build increments its local number only when explicitly assembled with `-PcarApk=true` and
names artifacts `engine-sounds-simulator-build-<number>-<variant>.apk`. The local counter and
generated APKs are intentionally ignored.

## Canonical documents

- [Architecture](architecture.md) explains runtime boundaries, profile selection, capability
  routing, the FMOD control flow, and realtime rules.
- [Vehicle integration and assets](vehicle-integration-and-assets.md) records BYD API evidence,
  exact local FMOD/Assetto inputs, bank hashes, event contracts, and testing discipline.

If a future change invalidates an invariant in these documents, update the relevant document in
the same change. Keep implementation minutiae in code and tests.
