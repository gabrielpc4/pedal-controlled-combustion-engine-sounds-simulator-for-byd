# Car audio validation

The native-bank route is validated in two layers:

1. Audio Lab consumes the same v2 pack physics and exports deterministic golden trajectories.
   Android compares drivetrain, clutch, turbo, limiter, shift, backfire, and traction state frame
   by frame.
2. `CarAudioRuntimeValidationTest` opens each installed profile in both listener perspectives with
   `NativeFmodBankBridge`. It sweeps continuous RPM, drivetrain-speed, boost, and perspective
   frames through the production FMOD control path with shift and backfire overrides disabled.

The runtime test requires the installer to publish every physical pack first. It asserts that each
bank opens and every update completes. It checks exact event ownership, proves disabled shift and
backfire events create no voices, and verifies source audibility meters are not a copy of master
volume. It writes exact source/event activation to `CarAudioRuntimeValidation`. It does not claim
byte-identical PCM because FMOD and the output device own final rendering.

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

The emulator validates native library loading, package installation, event/source ownership,
continuous parameters, meter semantics, and Audio Lab trace parity. A real-car listening pass
remains required to judge pitch feel, cabin acoustics, and BYD DSP speaker routing.
