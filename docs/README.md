# Project context for future work

This directory deliberately contains only durable engineering context. It is not a changelog,
release record, or duplicate specification of the Kotlin source. A future change should not make
these documents false merely because a calibration number, car profile, UI control, or test case
changes.

## Read this first

Engine Sounds Simulator is a private Android dashboard for a BYD Seal DiLink head unit. It turns
**read-only** vehicle telemetry, or the built-in simulator controls, into a fictional
combustion-engine tachometer and sample-based engine audio. It never controls a vehicle.

The intended environment is a rotated BYD tablet. The observed target software is
`13.1.33.2503250.1` (family `2503`), but each DiLink firmware must be treated as a separate
compatibility target.

The repository's source, tests, and build configuration are the current truth. These documents
describe boundaries and reasons, not a second implementation to keep in sync.

## Source-of-truth order

When information conflicts, use this order:

1. Current Kotlin source, Gradle configuration, and automated tests.
2. Direct observation on the exact head unit and firmware being tested.
3. Primary vendor material and locally retained reference artifacts.
4. Community reverse engineering and older implementation notes.

Do not infer a permission grant, audio route, sample license, vehicle parameter, or API behavior
from a similarly named class, a different firmware, or an old document.

## Non-negotiable boundaries

- Vehicle access is read-only. Do not add setters, CAN transmission, rooting, firmware changes,
  package spoofing, or broader permission bypasses.
- Never expose, commit, or log vehicle identifiers, credentials, location, or complete driving
  traces. Some supplied screenshots contain IMEI/ICCID and must be treated as sensitive.
- The sample recordings and reference APKs are local, ignored inputs. They are not part of this
  repository's redistribution rights.
- Treat vehicle testing as parked or controlled testing. Synthetic sound can mask safety alerts.
- The app owns audio only while its visible Activity is running. Background playback needs a
  separate, explicitly reviewed design.

## Working agreement

Use the checkout that contains this repository's `.git` directory; do not assume a similarly
named folder is the active checkout. Pull `main` before work and preserve unrelated changes.

For every source or documentation change, the expected delivery is:

1. Run relevant tests, assemble the debug APK, and run lint.
2. Install and foreground the generated APK on the `Simple_Automotive` emulator when available.
3. Commit and push. Do not commit APKs, build output, raw samples, decoded samples, reference
   APKs, or private reference material.

The build increments a local build number on assembly and names the artifact
`engine-sounds-simulator-build-<number>-debug.apk`. That local counter and generated APK are
intentionally ignored.

## Canonical documents

- [Architecture](architecture.md) explains the durable runtime boundaries, realtime rules, and
  how to make safe changes.
- [Vehicle integration and assets](vehicle-integration-and-assets.md) records the BYD API
  evidence, testing discipline, and local sample-asset contract.

If a future change would invalidate an invariant in these documents, update the relevant document
in the same change. Do not recreate historical per-feature notes; point to the code and tests
instead.
