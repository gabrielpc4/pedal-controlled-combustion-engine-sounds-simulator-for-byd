# Car audio validation

The native-bank route is validated in two layers:

1. Source-bank inspection uses Audio Lab when an event route or custom plugin needs investigation.
   It is diagnostic only: Android does not capture or export its output.
2. `CarAudioRuntimeValidationTest` opens each installed profile in both listener perspectives with
   `NativeFmodBankBridge`. It sweeps forty rapidly changing continuous RPM, throttle, boost,
   throttle-lift, and shift frames through the production FMOD control path.

The runtime test requires the installer to publish every physical pack first. It asserts that each
bank opens, that a permitted engine event is active, and that every update completes. It writes one
line per profile/perspective to `CarAudioRuntimeValidation`, including the exact permitted events
that the source bank exposed. It does not attempt to measure PCM amplitude because FMOD owns the
event graph and Android's output route.

## Reproduce

```sh
python3 tools/build_fmod_bank_packs.py
./gradlew :mobile:assembleDebug :mobile:assembleDebugAndroidTest :audio-installer:assembleDebug --no-daemon
adb -s emulator-5554 install -r mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<number>-debug.apk
adb -s emulator-5554 install -r audio-installer/build/outputs/apk/debug/engine-sounds-audio-installer-debug.apk
adb -s emulator-5554 shell am start -n com.gabrielpc.enginesoundsinstaller/.AudioInstallerActivity
```

Tap **INSTALL ALL**, wait until the installer reports completion, then run:

```sh
./gradlew :mobile:connectedDebugAndroidTest --no-daemon
adb -s emulator-5554 logcat -d -s CarAudioRuntimeValidation:I '*:S'
```

The emulator validates native library loading, package installation, direct event discovery, and
continuous parameter updates. A real-car listening pass remains required to judge pitch feel,
cabin acoustics, and BYD DSP speaker routing.
