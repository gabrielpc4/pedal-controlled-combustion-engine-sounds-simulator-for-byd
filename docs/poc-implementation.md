# Diagnostic POC implementation

Last verified: 2026-08-15

## What is implemented

The `mobile` module is the BYD DiLink diagnostic APK. The template `automotive` module is intentionally unchanged because it requires the standard `android.hardware.type.automotive` AAOS feature, which a BYD DiLink head unit may not advertise.

Core files:

- `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/MainActivity.kt` — adaptive Compose dashboard and Activity lifecycle.
- `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/telemetry/BydSpeedReader.kt` — capability probe, reflection bindings, polling, validation, and cadence metrics.
- `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/telemetry/TelemetryValidationTest.kt` — value/sentinel and timing-statistics unit tests.
- `mobile/src/main/AndroidManifest.xml` — read-only BYD permissions and launcher configuration.

The reader performs only these vehicle calls:

```text
BYDAutoSpeedDevice.getInstance(Context)
getAccelerateDeepness()
getBrakeDeepness()
getCurrentSpeed()
```

It never reflects or calls a setter.

## Runtime behavior

1. The Activity starts the reader in `onStart()` and stops it in `onStop()`.
2. A worker thread inspects Android/build information and the two BYD speed permissions.
3. It resolves `android.hardware.bydauto.speed.BYDAutoSpeedDevice` from the car's class loader.
4. It creates the speed device and independently discovers each getter on the runtime object.
5. At least one getter must exist; missing getters remain visible as individual errors.
6. One worker polls sequentially with a 20 ms delay after each batch. Reads cannot overlap and delayed reads do not cause catch-up bursts.
7. The UI refreshes from the latest immutable snapshot every 50 ms.

Displayed information includes:

- accelerator and brake percentage plus raw value;
- speed and raw value;
- time since each value last changed;
- delivery mode, effective sample rate, last-read age, and getter-batch duration;
- last/mean/p95/maximum polling interval;
- Android version, model, build display, and AAOS feature state;
- whether `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET` are defined, their protection level, and whether they are granted;
- runtime class/loader, available getters, and discovered listener registration signature;
- narrow exception/error text with reflection wrappers removed.

Zero pedal input is valid and is never used to represent failure. Invalid values retain their raw number and are labeled. Known diagnostic sentinels include SDK unavailable, feature unbound, statistic unavailable, uninitialized, permission denied, framework booting, no data, and device not registered.

## Compatibility choices

- `compileSdk = 37`
- `minSdk = 25`
- `targetSdk = 25`

Target API 25 is intentional for this sideload-only vehicle build. It matches known working BYD integrations and reduces hidden/vendor API compatibility risk. The module disables only lint's Google Play target-SDK check; this APK is not currently a Play Store artifact.

Adaptive launcher icons live in `mipmap-anydpi-v26`; density-specific WebP icons cover API 25.

No BYD classes under `android.hardware.bydauto.*` are packaged in the APK. The string class name is resolved against the implementation supplied by the vehicle.

## Build and verification

From PowerShell in the project root:

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

Verified result:

```text
BUILD SUCCESSFUL
APK: mobile/build/outputs/apk/debug/mobile-debug.apk
```

The build was also inspected to confirm:

- launcher Activity is present;
- minimum/target SDK are both 25;
- requested BYD permissions are only `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET`;
- no `BYDAUTO_*_SET` permission exists;
- no `android.hardware.bydauto` class is present in the APK DEX files.

The AndroidX manifest merger adds its own package-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; this is unrelated to BYD vehicle access.

## Install on the car

With the car safely parked and its authorized ADB connection available:

```powershell
adb devices
adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

The same APK can be copied to the head unit and installed through its package installer if preferred.

## First on-car evidence to capture

Photograph or transcribe the **Capability diagnostics** panel without exposing IMEI, ICCID, VIN, location, or ADB keys. Record:

1. reader state;
2. both permission lines;
3. class loader/runtime class lines;
4. getter presence;
5. listener API line;
6. raw accelerator, brake, and speed at rest;
7. response while the pedals are moved safely;
8. measured rate, p95 interval, and call time;
9. the complete last error if one appears.

Likely outcomes:

- **ACTIVE with plausible values:** direct access works; add a compile-only listener stub/JAR next and compare callback latency.
- **Class present, permission denied/service error:** implement the independently documented read-only shell helper path.
- **Class missing:** inspect the runtime BYD framework for a renamed/generic device API before considering external OBD/CAN.

Do not test by operating the screen while driving. Do not add vehicle setters merely to test whether they are accepted.
