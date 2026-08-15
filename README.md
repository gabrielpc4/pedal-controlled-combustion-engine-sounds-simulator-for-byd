# BYD Motor Sound

An experimental, read-only Android diagnostic for investigating low-latency accelerator and brake telemetry on a BYD Seal running DiLink firmware `13.1.33.2503250.1` (`2503`).

The current proof of concept displays:

- accelerator-pedal depth;
- brake-pedal depth;
- vehicle speed;
- raw BYD values and known error sentinels;
- permission, class-loader, and API availability diagnostics;
- getter-call duration, sample age/rate, and interval statistics.

It invokes the documented BYD speed getters through reflection and does not package BYD framework classes. The manifest requests only the read-oriented `BYDAUTO_SPEED_COMMON` and `BYDAUTO_SPEED_GET` permissions. No vehicle setter is implemented.

## Project status

The diagnostic APK builds and passes unit tests and Android lint. It still needs validation on the target vehicle to determine whether firmware `2503` permits direct third-party reads or requires a read-only helper launched through authorized ADB.

The DiLink diagnostic is the `mobile` module. The generated `automotive` module is an untouched AAOS media template and is not the current vehicle APK.

## Build

Requirements:

- Android Studio with Android SDK 37;
- the Android Studio embedded JDK available as `JAVA_HOME`;
- PowerShell or another shell capable of running the Gradle wrapper.

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

Output:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

## Install

Only test while the vehicle is safely parked.

```powershell
adb install -r mobile\build\outputs\apk\debug\mobile-debug.apk
adb shell am start -n com.gabrielpc.bydmotorsound/.MainActivity
```

Wireless ADB is a privileged maintenance channel. Enable it only in a trusted environment and do not publish ADB keys, passwords, IMEI, ICCID, VIN, or location data.

## Documentation

- [Engineering context](docs/README.md)
- [Implemented POC](docs/poc-implementation.md)
- [POC and on-car test plan](docs/poc-plan.md)
- [BYD DiLink API V1.0.5 notes](docs/byd-dilink-api-v1.0.5.md)
- [Research findings](docs/research-findings.md)
- [Source provenance](docs/source-material/README.md)

## Important limitation

The initial POC polls the three getters every 20 ms on one worker thread. The historical BYD API also documents change listeners, but the relevant listener is an abstract vendor class and is not safely constructible through reflection alone. If direct getters work on the car, the next iteration is a compile-only listener integration and a measured callback-versus-polling comparison.

This is independent research, not an official BYD product. It must remain read-only until every permission and runtime behavior is understood on the exact target firmware.
