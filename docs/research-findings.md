# Research Findings: BYD Seal Vehicle Telemetry

Last updated: 2026-08-15

## Research question

Determine the lowest-latency practical way for an Android application running on a BYD Seal head unit to read accelerator and brake intensity, initially to display the values and later to drive a synthesized motor-sound engine.

## Executive conclusion

The direct API exists in BYD's proprietary DiLink framework. Use `BYDAutoSpeedDevice` and `AbsBYDAutoSpeedListener`, guarded by `android.permission.BYDAUTO_SPEED_GET`. Standard Android Automotive pedal properties are not the primary route for this firmware.

The unresolved issue is whether an ordinary development-signed APK can receive `BYDAUTO_SPEED_GET` on system version `13.1.33.2503250.1`. Official BYD documentation says the APK needs a system signature; a community system log shows the permission as signature-only; and the supplied reference motor-sound APK is BYD-signed. The first implementation must therefore be a capability/permission probe that fails explicitly and records evidence.

## Evidence model

Claims below are tagged as:

- **Official** - BYD/Android/AOSP primary material.
- **Artifact** - directly observed in the supplied project, screenshot, or APK.
- **Community** - public reverse engineering or captured logs.
- **Inference** - the best current conclusion, pending an on-car test.

## Target head unit and firmware

**Artifact:** the supplied version screenshot reports:

- System: `13.1.33.2503250.1`
- FWC: `18.3.5.2411180.2.18.3.2.2312260.1`
- DSP: `2411083_V1.0.1`
- Hardware: `V02.E03.00.32.03`
- Audio: `4.00.13`

The screenshot also exposes IMEI and ICCID. Those identifiers are intentionally omitted here.

**Official:** BYD Norway published the exact `13.1.33.2503250.1` Seal multimedia release on its Seal software-update page.

**Community:** public BYD platform tables map the `13.1.33` controller branch to DiLink 50P, Qualcomm 665, and Android 10, commonly used in Seal/Han/Tang vehicles.

**Inference:** the leading `13` is a BYD controller/firmware branch identifier, not proof of Android 13. Runtime `Build.VERSION.SDK_INT` must still be captured on the vehicle.

Sources:

- <https://byd.no/eie-byd/din-byd-seal-4x4>
- <https://github.com/wheregoes/byd-dolphin-hacking>
- <https://wiki.defective.tech/en/BYD/Firmware>

## Direct BYD telemetry path

### Official API

The official V1.0.5 Chinese manual defines:

```text
android.hardware.bydauto.speed.BYDAutoSpeedDevice
android.hardware.bydauto.speed.AbsBYDAutoSpeedListener
```

Core methods:

| Signal | Getter | Listener callback | Documented range |
| --- | --- | --- | --- |
| Accelerator pedal depth | `int getAccelerateDeepness()` | `onAccelerateDeepnessChanged(int value)` | 0-100 percent |
| Brake pedal depth | `int getBrakeDeepness()` | `onBrakeDeepnessChanged(int value)` | 0-100 percent |
| Vehicle speed | `double getCurrentSpeed()` | `onSpeedChanged(double value)` | 0-282 km/h |

Lifecycle methods:

```java
BYDAutoSpeedDevice.getInstance(Context)
registerListener(AbsBYDAutoSpeedListener)
unregisterListener(AbsBYDAutoSpeedListener)
```

Required manifest declaration found in working examples and the reference APK:

```xml
<uses-permission android:name="android.permission.BYDAUTO_SPEED_GET" />
```

The API manual says listeners push updates when observed values change. It does not promise a sampling frequency, maximum latency, thread, or delivery while a value remains constant.

### Reverse-engineered service path

**Community:** one reverse-engineered architecture is:

```text
App
  -> BYDAutoManager / android.hardware.bydauto wrapper
  -> Android Binder
  -> DiCarServer (system process / UID 1000)
  -> auto.default.so
  -> SPI
  -> vehicle MCU
```

This explains why the public-facing classes can deliver low-latency in-process callbacks while still being privilege-controlled by the system service. This architecture has not yet been verified on the exact target car.

Source: <https://github.com/wheregoes/byd-dolphin-hacking>

### Community feature IDs - diagnostic only

Decompiled newer BYD SDK stubs expose the speed device as device type `1013` and map these raw feature IDs:

| Symbol | Decimal | Hex |
| --- | ---: | --- |
| `SPEED_ACCELERATE_VALUE` | 1033220112 | `0x3D95B010` |
| `SPEED_ACCELERATOR_DEPTH_10D` | 282066992 | `0x10D00030` |
| `SPEED_ACCELERATOR_S` | 874512392 | `0x34200008` |
| `SPEED_ACCELERATOR_VALID_FLAG` | 874512408 | `0x34200018` |
| `SPEED_AUTO_SPEED` | 303038472 | `0x12100008` |
| `SPEED_AUTO_SPEED_FLAG` | 303038487 | `0x12100017` |
| `SPEED_BRAKE_S` | 874512400 | `0x34200010` |
| `SPEED_BRAKE_DEPTH_VALID_FLAG` | 874512409 | `0x34200019` |

Do not build the POC against raw IDs. Prefer the documented `BYDAutoSpeedDevice` wrapper. Raw IDs are useful only if later diagnostics establish that the high-level wrapper is present but maps the wrong feature on this firmware.

Source: <https://github.com/tonysmith1sme/Car-Staus-Helper>

## Permission and signing analysis

### Official requirements

The BYD V1.0.5 manual states that:

- developers must use BYD's published SDK;
- the SDK documented there was built against Android 7.1.2;
- applications need a system signature to install/run;
- actual input/output behavior in the SDK takes precedence over the prose document;
- API availability varies with vehicle configuration and vehicle power state;
- setter methods should normally be used only in ON power state;
- a setter return value only confirms command dispatch, not that vehicle state changed; listeners must confirm the result.

BYD's developer agreement says SDK-integrated applications are reviewed and signed/authorized by BYD before BYD uploads them to its application market. It also restricts external dissemination of vehicle data obtained through the platform.

Sources:

- <https://oip.byd.com/uploads/20210824/46cd2d7c2e878b0a1c6b066967e7f6fd.pdf>
- <https://oip.byd.com/addons/cms/about/index?category=3>

### Signature-level evidence

**Community:** a captured DiLink PackageManager log contains:

```text
Un-granting permission android.permission.BYDAUTO_SPEED_GET ... protectionLevel=2
```

Android defines base protection value `2` as `PROTECTION_SIGNATURE`.

Sources:

- <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/log/log111.txt#L1753-L1758>
- <https://developer.android.com/reference/android/content/pm/PermissionInfo#PROTECTION_SIGNATURE>

**Artifact:** the supplied motor-sound APK requests `BYDAUTO_SPEED_GET` and is signed with a certificate whose subject includes the `byd.com` domain and `OU=ITCENTER_H_TEST`. Its SHA-256 certificate digest is:

```text
0c9b09179f1fc8944b3c9fc6af5d41ffd2400e78d1bea566fd806f189f60ce6f
```

**Inference:** the reference application's ability to access the API cannot be treated as evidence that the project's ordinary debug certificate will work.

### Permission behavior may vary

Community reports and reverse-engineered samples span different DiLink generations. Some describe comparatively open/readable vehicle APIs, while other logs show signature-only definitions. The exact permission definition is a firmware/system-package property and can change without the class names changing.

Do not hard-code “permission will work” or “permission will never work.” The app should report:

- permission name absent;
- permission defined and granted;
- permission defined but denied;
- API class absent;
- class present but service unavailable;
- getter/listener success;
- security exception or remote-service failure.

## Why standard Android Automotive is not the first route

Modern Android Automotive defines these properties:

| AOSP property | ID | Type/access | Permission |
| --- | --- | --- | --- |
| `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE` | `0x1160030F` / 291504911 | float, continuous, 0-100 | `android.car.permission.READ_CAR_PEDALS` |
| `BRAKE_PEDAL_COMPRESSION_PERCENTAGE` | `0x11600310` / 291504912 | float, continuous, 0-100 | `android.car.permission.READ_CAR_PEDALS` |

However, AOSP marks both properties `@version 4` in `VehicleProperty.aidl`. The official VHAL compatibility table associates VHAL version 4 with Android 16. The target DiLink branch is commonly identified as Android 10 and uses proprietary BYD vehicle services.

Compiling against API 37 does not make a missing vehicle HAL/service appear on an Android 10 head unit. Unless BYD explicitly backported and exposed these properties, `CarPropertyManager` is not expected to provide them.

Sources:

- <https://developer.android.com/reference/android/car/VehiclePropertyIds>
- <https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl>
- <https://source.android.com/docs/automotive/vhal/vhal-interface>

## Callback cadence and latency evidence

The official manual gives no rate guarantee. A community log contains changing brake-depth callbacks roughly every 97-104 ms during one pedal movement, suggesting about 10 updates per second on that particular vehicle/firmware. The log is only a useful expectation, not a contract, and no equivalent accelerator sequence was found in the sampled log.

Latency must be measured in the target vehicle using a monotonic timestamp taken at the callback entry. UI rendering cadence is a separate measurement: Compose/View invalidation may coalesce multiple events into one display frame even when callbacks arrive faster.

Source: <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/log/log111.txt#L6442-L6450>

## Reference application behavioral evidence

Detailed static analysis is in [reference-apk-analysis.md](reference-apk-analysis.md). The important conclusion is:

- its FAQ says engine sound is calculated from vehicle speed and changes only while speed changes;
- its tuning assets contain both vehicle-speed and acceleration scale parameters;
- it requests speed, sensor, gearbox, and bodywork/AC permissions;
- it is BYD-signed and protected by a shell/packer.

**Inference:** the production reference application likely derives an acceleration/load signal from speed, at least for some modes or supported vehicles, instead of relying exclusively on direct accelerator depth. This is a compatibility precedent for the proposed fallback.

## Fallback hierarchy

### 1. BYD speed plus calculated acceleration

Use `onSpeedChanged`/`getCurrentSpeed`, calculate the time derivative, apply a low-pass filter and dead band, and map positive acceleration to load. This is available through the same permission family and appears consistent with the reference application.

Limitations:

- derivative amplifies speed quantization/noise;
- it cannot reliably distinguish accelerator demand from downhill/coasting effects;
- regenerative braking and physical brake application can be conflated;
- it is delayed by both the source speed cadence and filtering.

### 2. External read-only OBD-II or CAN adapter

An external adapter avoids head-unit application signing, but adds Bluetooth/Wi-Fi/USB latency and a separate hardware dependency. Generic OBD-II accelerator/throttle PIDs are optional and may not represent the actual driver pedal. Brake pedal depth/pressure is generally manufacturer-specific. A useful implementation may require BYD-specific CAN signal identification.

Never transmit CAN frames as part of this project. The fallback is read-only telemetry.

### 3. Official BYD application onboarding

For a distributable application, register through BYD's Open Platform, obtain the current SDK, submit the app for review, and obtain the required signing/authorization. The public site advertises vehicle sensor/API access, but its public V1.0.5 PDF is old and does not prove Seal support for every method.

Sources:

- <https://oip.byd.com/>
- <https://oip.byd.com/addons/cms/article/detail?article_id=4&title=%E8%BD%A6%E5%9E%8B%E7%89%88%E6%9C%AC%E5%BC%80%E6%94%BE%E8%A7%84%E5%88%99>

## Current project implications

The current `automotive` module is configured as an Android Automotive media application:

- `android.hardware.type.automotive` is required;
- no launcher Activity exists;
- the shared module declares Google automotive media metadata and an exported `MediaBrowserServiceCompat`;
- the media callbacks contain no playback implementation.

For the telemetry probe, convert or replace this with a normal launcher Activity intended for the BYD tablet. Requiring the standard automotive hardware feature can cause installation rejection if DiLink does not advertise that feature, even though it runs in a car.

The current `minSdk 28` is compatible with an Android 10/API 29 target. A high compile SDK can remain, but all runtime calls must be guarded for the actual API level. The supplied reference application targeted API 30 and had minimum API 25.

## Safety and privacy constraints

- Initial tests must be parked or performed by a passenger in a controlled environment.
- The diagnostic UI must not require driver interaction while moving.
- Read-only vehicle APIs only during the POC.
- Do not log VIN, IMEI, ICCID, account tokens, precise location, or complete vehicle traces by default.
- Keep raw telemetry local unless the user explicitly authorizes export and the BYD developer terms permit it.
- Do not root/flash the vehicle or bypass signature checks as a first-line implementation strategy.

## Questions that remain open until the on-car test

1. What are `Build.VERSION.RELEASE`, `SDK_INT`, model, and BYD build properties on this exact head unit?
2. Does the package manager define `BYDAUTO_SPEED_GET`, and with which protection flags?
3. Is it granted to the project's debug-signed APK?
4. Are both BYD speed classes present in the boot/framework class path?
5. Does `getInstance` return normally or throw a security/service exception?
6. Do accelerator and brake getters return actual depth, constant zero, invalid values, or quantized steps?
7. Do listeners fire only on changes? At what rate and on which thread?
8. Does brake depth reflect physical pedal travel, requested deceleration, hydraulic pressure, or a normalized gateway signal?
9. Is accelerator depth scaled linearly from 0-100 on the Seal?
10. Does the service continue delivering events in the background and while another media application owns audio focus?
