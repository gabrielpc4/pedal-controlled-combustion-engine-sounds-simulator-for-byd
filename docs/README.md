# BYD Motor Sound - Engineering Context

Last updated: 2026-08-15

This directory is the durable technical memory for the project. Future work should start here instead of repeating the original research or assuming that the BYD head unit behaves like a standard Android Automotive OS device.

## Bottom line

The best candidate for low-latency pedal telemetry is BYD's proprietary DiLink API:

- `android.hardware.bydauto.speed.BYDAutoSpeedDevice`
- `getAccelerateDeepness()` -> accelerator depth, 0-100 percent
- `getBrakeDeepness()` -> brake depth, 0-100 percent
- `getCurrentSpeed()` -> speed, 0-282 km/h
- `AbsBYDAutoSpeedListener` for change callbacks
- manifest permission `android.permission.BYDAUTO_SPEED_GET`

The principal risk is the process/permission route, not the API shape or APK installation. BYD's official V1.0.5 manual says vehicle API applications require a system signature, and a captured DiLink log defines `BYDAUTO_SPEED_GET` as signature-only on at least one build. However, the separately supplied Electro APK is third-party signed, installs on this Seal, contains accelerator/brake feature IDs, and initializes wireless ADB. Current community code independently demonstrates that a shell-identity `app_process` helper can read the same pedal getters without root. The direct app path still needs to be tested first on firmware `2503`.

Do not begin with standard `CarPropertyManager`. The current AOSP accelerator/brake percentage properties are VHAL version 4 properties from much newer Android Automotive releases, while the target head unit is a BYD DiLink branch commonly mapped to Android 10.

## Target vehicle

| Item | Value |
| --- | --- |
| Vehicle | BYD Seal |
| System version | `13.1.33.2503250.1` |
| Relevant firmware family | `13.1.33` / `2503` |
| FWC | `18.3.5.2411180.2.18.3.2.2312260.1` |
| DSP | `2411083_V1.0.1` |
| Hardware | `V02.E03.00.32.03` |
| Audio | `4.00.13` |

The source screenshot is `../reference/car_software_version.jpg`. It also contains IMEI and ICCID values; never reproduce those values in issues, logs, documentation, or screenshots intended for sharing.

## Documentation map

- [Full engine-sound implementation](full-implementation.md) - architecture, drivetrain, input policy, procedural audio, logical multichannel routing, UI, controls, verification, and on-car acceptance.
- [BYD Seal Performance calibration](byd-seal-performance-calibration.md) - published vehicle anchors, derived EV motor curve, Sport-pedal uncertainty, road-load assumptions, and synthetic-gear separation.
- [Emulator validation](emulator-validation.md) - exact software-ARM fallback, final APK identity, viewport, and pedal-test evidence.
- [Drivetrain and audio research](drivetrain-audio-research.md) - professional simulation evidence, sound-synthesis references, commercial asset options, and licensing exclusions.
- [Research findings](research-findings.md) - platform identification, official versus community evidence, Android Automotive comparison, permissions, known failure modes, and source links.
- [BYD DiLink API V1.0.5 notes](byd-dilink-api-v1.0.5.md) - English engineering notes and complete module/method inventory extracted from the 159-page Chinese manual.
- [Reference APK analysis](reference-apk-analysis.md) - static inspection of the supplied BYD motor-sound APK and what can and cannot be inferred from it.
- [POC implementation and test plan](poc-plan.md) - recommended project changes, runtime capability probe, callback implementation, diagnostics, latency measurement, success criteria, and fallbacks.
- [Implemented diagnostic POC](poc-implementation.md) - current source layout, build artifact, behavior, limitations, and exact install/test commands.
- [Source-material index](source-material/README.md) - provenance, local artifacts, URLs, hashes, and evidence-quality rules.

## Current project state

- The Android Studio project contains `mobile`, `automotive`, and `shared` modules.
- `automotive` remains an Android Automotive media-template shell. It requires `android.hardware.type.automotive`, has no launcher Activity, targets API 37, and depends on the template `shared` media service.
- `shared/MyMusicService.kt` is template boilerplate with empty media callbacks.
- The DiLink telemetry probe and the full engine-sound dashboard are implemented together in `mobile`, which produces a regular full-screen APK for the rotating BYD tablet. It uses reflection and packages no BYD framework stubs.
- The reader polls the documented accelerator, brake, and speed getters every 20 ms. `DriveController` then feeds a 200 Hz Seal-calibrated EV road model, independent synthetic sound gears, and a continuous audio renderer; simulator pedals take over when live values are unavailable.
- The project is a public Git repository. APKs, local reference artifacts, raw licensed audio, and build/emulator downloads must remain ignored.

## Decisions that should survive future sessions

1. Preserve the read-only capability probe as the gate in front of the audio engine.
2. Prefer BYD listener callbacks over polling for production telemetry.
3. Record raw callback timestamps before adding smoothing or UI throttling.
4. Keep the exact permission/signature result for firmware `13.1.33.2503250.1` as test evidence; do not generalize from another BYD firmware.
5. Use a compile-only BYD SDK/stub dependency. Never package replacement classes under `android.hardware.bydauto.*` in the APK.
6. Do not use vehicle-control setters, root the head unit, flash firmware, or inject CAN frames as part of this POC.
7. If direct pedal depth is blocked, keep simulator input for development and evaluate BYD speed callbacks/derived longitudinal acceleration as the first vehicle fallback; the reference application itself appears to use this strategy.
8. Treat OBD-II/CAN as an external, read-only fallback. Standard OBD support for pedal position is optional and brake intensity is normally proprietary.

## Next action

The complete simulator/audio dashboard is implemented and verified locally. Install the built `mobile-debug.apk` and run the parked on-car validation checklists in both [the original POC notes](poc-implementation.md) and [the full implementation handoff](full-implementation.md). The first vehicle session should answer these questions:

- Are the BYD classes present?
- Is `BYDAUTO_SPEED_GET` defined and granted?
- Can `BYDAutoSpeedDevice` be instantiated?
- Do accelerator, brake, and speed getters return plausible values?
- Do callbacks arrive, and at what cadence?

The initial APK deliberately uses 20 ms polling because the listener is an abstract BYD class and the current project has no trustworthy compile-only vendor SDK. The UI reports the listener signature found at runtime. If direct getters work, callback integration is the next iteration.
