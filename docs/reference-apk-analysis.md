# Supplied BYD Motor-Sound APK - Static Analysis

Last analyzed: 2026-08-15

Artifact: `../reference/original_byd_motor_sound.apk`

This analysis records observable package metadata and human-readable assets. It does not claim a complete decompilation: the application is protected by a shell/packer, so internal implementation details are intentionally treated as unknown.

## Package metadata

| Field | Value |
| --- | --- |
| Package | `com.car.chaopaoshenglangbyd` |
| Chinese label | `超跑声浪` |
| Approximate label translation | Supercar sound / supercar sound wave |
| Version | `1.7.0` |
| Version code | `42` |
| Compile/platform API | 30 / Android 11 |
| Target API | 30 |
| Minimum API | 25 |
| Launcher Activity | `com.car.chaopaoshenglang.SplashAC.SplashAC` |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Application class | `s.h.e.l.l.S` |

The unusual application class and protected bytecode are consistent with an Ijiami-style Android shell/packer. This prevents reliable conclusions from ordinary decompilation without executing or unpacking the protected application. No such bypass was attempted or required for the current research.

## Requested permissions

Standard permissions:

- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `READ_PHONE_STATE`
- `INTERNET`
- `MODIFY_AUDIO_SETTINGS`
- `ACCESS_WIFI_STATE`
- `ACCESS_NETWORK_STATE`
- `FOREGROUND_SERVICE`
- `INSTALL_PACKAGES`
- `REQUEST_INSTALL_PACKAGES`

BYD permissions:

- `android.permission.BYDAUTO_BODYWORK_COMMON`
- `android.permission.BYDAUTO_BODYWORK_GET`
- `android.permission.BYDAUTO_AC_COMMON`
- `android.permission.BYDAUTO_AC_GET`
- `android.permission.BYDAUTO_AC_SET`
- `android.permission.BYDAUTO_SENSOR_GET`
- `android.permission.BYDAUTO_SPEED_GET`
- `android.permission.BYDAUTO_GEARBOX_GET`

The app does not request the modern AAOS `android.car.permission.READ_CAR_PEDALS` permission. This is direct artifact evidence that it targets BYD's proprietary vehicle API path.

Some requested permissions are broader than the sound feature strictly appears to need. Do not copy the full list into this project. The POC should request only `BYDAUTO_SPEED_GET` until another permission is justified by an implemented feature.

## Signing certificate

The APK verifies with a signer certificate whose subject includes:

```text
EMAILADDRESS=987654326@byd.com
OU=ITCENTER_H_TEST
CN=987654326
```

SHA-256 certificate digest:

```text
0c9b09179f1fc8944b3c9fc6af5d41ffd2400e78d1bea566fd806f189f60ce6f
```

This is strong evidence that the APK was signed inside a BYD testing/development signing process. It does not provide a key and must not be used as a certificate-pinning assumption.

## Human-readable FAQ asset

The APK contains `assets/qandan_config.json`. Its actionable content translates as follows:

1. To use the app, select a vehicle/sound, purchase it, open it from the garage, choose the real-driving mode, and drive normally. The app calculates the sound curve from vehicle speed to simulate a sports car.
2. Purchased sound vehicles remain available permanently.
3. The account is bound to the vehicle VIN and is intended to survive uninstall/reinstall.
4. In real-driving mode, sound does not change until the vehicle has actual speed.
5. Preview mode is stationary and ignores vehicle-speed changes. Real-driving mode changes sound in real time with vehicle speed.
6. A reported shutdown noise in one sound pack is claimed to be the recorded sports-car shutdown sound rather than corruption.
7. Parameter settings are intended mainly for official tuning; users are advised to keep defaults and can restore defaults.
8. Payment troubleshooting directs users to install the latest app-store version and contact support if needed.
9. Vehicle support is inferred from whether the app appears in the current vehicle's application store.
10. Background playback permits the sound app to play simultaneously with other audio apps; otherwise it does not mix by default.

Important interpretation:

- The FAQ explicitly describes a speed-driven sound curve.
- It does not say that accelerator pedal depth is used.
- This is a compatibility precedent for a speed-derived load fallback, not proof that no pedal API calls exist elsewhere in the protected code.

## Tuning configuration asset

The APK contains `assets/preferences_config.json` with these defaults:

| UI key | Internal name | Default | Likely meaning - inference only |
| --- | --- | ---: | --- |
| `SMT` | `s_loadSmooth` | 0.50 | Load smoothing |
| `OFFV` | `s_volumeOffload` | 5.00 | Off-load volume |
| `S0V` | `s_volumeS0` | 6.00 | Sound-state volume 0 |
| `S1V` | `s_volumeS1` | 6.00 | Sound-state volume 1 |
| `S2V` | `s_volumeS2` | 6.00 | Sound-state volume 2 |
| `S3V` | `s_volumeS3` | 6.00 | Sound-state volume 3 |
| `LPF` | `s_lowpassfilter` | 45 | Low-pass/filter parameter |
| `CSS` | `s_carSpeedScale` | 1 | Vehicle-speed scale |
| `CAS` | `s_carAccScale` | 0.30 | Vehicle-acceleration scale |
| `ECL` | `s_engineContol` | 75 | Engine-control/load parameter |
| `SVMI` | `s_VolAMin` | 0.05 | Minimum acceleration-volume factor |
| `SAMI0` | `s_AccVMin0` | 0.0 | Acceleration band minimum 0 |
| `SAMI1` | `s_AccVMin1` | 0.0 | Acceleration band minimum 1 |
| `SAMI2` | `s_AccVMin2` | 0.0 | Acceleration band minimum 2 |
| `SAMX0` | `s_AccVMax0` | 1.0 | Acceleration band maximum 0 |
| `SAMX1` | `s_AccVMax1` | 1.0 | Acceleration band maximum 1 |
| `SAMX2` | `s_AccVMax2` | 1.0 | Acceleration band maximum 2 |
| `SSD` | `s_ShiftDuration` | 0.1 | Simulated shift duration |

Names in the final column are interpretations of terse configuration symbols, not verified code behavior. The combination of `s_carSpeedScale`, `s_carAccScale`, load smoothing, and recorded speed files strongly suggests a speed/derived-acceleration input model.

## Recorded speed assets

The archive contains:

- `assets/incarspeed.txt`
- `assets/incarspeed_fast.txt`

They appear to be large recorded or synthetic vehicle-speed traces. Their presence is another clue that the application was designed and tuned around speed sequences. They could later be useful as inspiration for simulator fixtures, but their contents should not be copied into the new project without checking provenance/licensing.

## Audio and service behavior clues

- A foreground service is declared, consistent with continued sound playback outside the foreground Activity.
- `MODIFY_AUDIO_SETTINGS` is requested.
- The FAQ exposes a background-playback switch for mixing with other audio apps.
- Multiple native ABIs indicate a native component, plausibly an audio/DSP engine, but the protected code prevents confirmation.

These clues are relevant to the later audio phase, not the first telemetry POC. The new app should implement its own audio-focus and foreground-service behavior instead of copying hidden assumptions.

## What can be concluded

High confidence:

- The reference application targets BYD's proprietary APIs.
- It requests `BYDAUTO_SPEED_GET`.
- It is BYD-signed.
- Its user-facing description says sound responds to vehicle speed.
- It has explicit speed and acceleration scaling/tuning parameters.

Reasonable inference:

- It probably derives an acceleration/load signal from the speed stream for at least some sound logic.
- BYD signing is likely important to its vehicle-API access.

Not established:

- Whether it calls `getAccelerateDeepness()` or the associated callback internally.
- Its exact interpolation, filtering, audio-loop, pitch, or mixing algorithms.
- Whether its permissions work on firmware `13.1.33.2503250.1` solely because of its signature.
- Whether its packaged assets are licensed for reuse.

## Reproducible inspection commands

These commands were run with Android SDK Build Tools 36.0.0:

```powershell
aapt2 dump badging reference/original_byd_motor_sound.apk
aapt2 dump permissions reference/original_byd_motor_sound.apk
aapt2 dump xmltree reference/original_byd_motor_sound.apk --file AndroidManifest.xml
java -jar <android-sdk>/build-tools/36.0.0/lib/apksigner.jar verify --print-certs reference/original_byd_motor_sound.apk
tar -xOf reference/original_byd_motor_sound.apk assets/qandan_config.json
tar -xOf reference/original_byd_motor_sound.apk assets/preferences_config.json
```
