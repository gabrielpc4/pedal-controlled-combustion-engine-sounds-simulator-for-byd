# Source Material and Provenance

Last verified: 2026-08-15

This file distinguishes authoritative documentation from local artifact inspection and community reverse engineering. Future code and claims should retain these distinctions.

## Local artifacts supplied during research

These artifacts are deliberately excluded from the public repository. They remain user-owned local references; this file retains only provenance and hashes needed to reproduce the research.

### Vehicle version screenshot

- Local filename: `reference/car_software_version.jpg`
- Use: exact vehicle multimedia/FWC/DSP/hardware/audio versions.
- Privacy: contains IMEI and ICCID. Do not copy those identifiers into documentation or public output.

### Original motor-sound APK

- Local filename: `reference/original_byd_motor_sound.apk`
- Package: `com.car.chaopaoshenglangbyd`
- Version: `1.7.0` (`versionCode 42`)
- Static analysis is recorded in `../reference-apk-analysis.md`.
- The APK is a behavioral reference, not a redistributable SDK and not proof that an unsigned application has the same permissions.

## Locally retained official PDF

- Path: `BYD_DiLink_API_V1.0.5.pdf`
- Original title: `比亚迪智慧开放平台 API 说明书`
- English title used here: `BYD Intelligent Open Platform API Manual`
- Document version: V1.0.5
- Document date: 2018-07-25
- Physical pages: 159
- File size: 3,682,835 bytes
- SHA-256: `73E7DC96E3D66B39AB9707796FCEB7452D392602001124661E5A5666E2BF7A49`
- Original official URL: <https://oip.byd.com/uploads/20210824/46cd2d7c2e878b0a1c6b066967e7f6fd.pdf>
- The engineering extraction/translation is in `../byd-dilink-api-v1.0.5.md`.

No newer publicly downloadable official API PDF was found during the search. The BYD developer portal exposes an SDK/API documentation category, but current SDK packages and signing workflows may require registration/login.

## Official online sources

- BYD DiLink/Open Platform home: <https://oip.byd.com/>
- BYD developer agreement: <https://oip.byd.com/addons/cms/about/index?category=3>
- BYD vehicle application/API overview: <https://oip.byd.com/addons/cms/article/detail?article_id=4&title=%E8%BD%A6%E5%9E%8B%E7%89%88%E6%9C%AC%E5%BC%80%E6%94%BE%E8%A7%84%E5%88%99>
- BYD SDK/API documentation index: <https://oip.byd.com/addons/cms/document/index?document_type_id=1>
- BYD Norway Seal software update page: <https://byd.no/eie-byd/din-byd-seal-4x4>
- Android `VehiclePropertyIds`: <https://developer.android.com/reference/android/car/VehiclePropertyIds>
- AOSP `VehicleProperty.aidl`: <https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl>
- AOSP VHAL interface/version documentation: <https://source.android.com/docs/automotive/vhal/vhal-interface>
- Android permission protection constants: <https://developer.android.com/reference/android/content/pm/PermissionInfo#PROTECTION_SIGNATURE>

## Community/reverse-engineered sources

- BYD platform and firmware mapping: <https://github.com/wheregoes/byd-dolphin-hacking>
- Reverse-engineered BYD application/API notes: <https://github.com/wheregoes/byd-apps>
- Decompiled BYD SDK stubs and sample application: <https://github.com/tonysmith1sme/Car-Staus-Helper>
- Decompiled `BYDAutoSpeedDevice`: <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/byd_sdk_33/src/main/java/android/hardware/bydauto/speed/BYDAutoSpeedDevice.java>
- Decompiled speed listener: <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/byd_sdk_33/src/main/java/android/hardware/bydauto/speed/AbsBYDAutoSpeedListener.java>
- Community manifest permission example: <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/app/src/main/AndroidManifest.xml>
- Captured DiLink log: <https://github.com/tonysmith1sme/Car-Staus-Helper/blob/master/log/log111.txt>
- Community firmware archive/index: <https://wiki.defective.tech/en/BYD/Firmware>

Community materials are useful for implementation clues and firmware comparison. They are not guarantees for the target Seal or authorization to redistribute BYD code.

## Evidence labels used in these docs

- **Official** - BYD, Android, or AOSP primary documentation.
- **Artifact** - directly observed in the supplied screenshot/APK/project.
- **Community** - public reverse engineering, logs, repositories, or firmware catalogs.
- **Inference** - a conclusion drawn from two or more observations; must be validated on the target vehicle.

When evidence conflicts, retain the conflict. In particular, BYD permission behavior appears to vary by firmware and signing context.
