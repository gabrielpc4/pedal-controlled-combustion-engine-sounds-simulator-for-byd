# Emulator validation record

This file preserves the reproducible desktop validation performed on 2026-08-15. It is not evidence that BYD vendor telemetry or the physical speaker topology works; the generic emulator has neither the DiLink framework nor the car audio HAL.

## Current hardware-accelerated validation

Later on 2026-08-15, SVM and the Windows hypervisor were enabled and the host was restarted. The current emulator then reported:

```text
WHPX(10.0.26100) is installed and usable.
```

The software ARM fallback was replaced with:

- Android Emulator `37.1.11.0`, build `15917651`;
- AVD `BYD_Seal_1920x1080`;
- Android 16 / API 36 Google Play x86_64 image;
- 4 virtual cores, 4 GB RAM, WHPX acceleration, host GPU;
- 1920 x 1080 at 160 dpi, producing the same measured 1920 x 990 safe viewport;
- emulator audio input/output enabled, with no `-no-audio` flag.

Launch command:

```powershell
$emulator = 'D:\Users\sgabr\AppData\Local\Android\Sdk\emulator\emulator.exe'
& $emulator -avd BYD_Seal_1920x1080 -accel on -gpu host -memory 4096 -cores 4 -no-snapshot -no-boot-anim
```

Because the application intentionally targets SDK 25 for DiLink compatibility, Android 16 required:

```powershell
adb install --bypass-low-target-sdk-block -r mobile\build\outputs\apk\debug\mobile-debug.apk
```

Android also displayed its one-time old-target-SDK compatibility warning, which was acknowledged after confirming the expected package. This is a development sideload workflow, not a Play-distribution configuration.

The Android audio diagnostics confirmed:

- the application held the top audio-focus entry;
- an `AudioTrack` with `USAGE_GAME` / `CONTENT_TYPE_MUSIC` was started;
- logical stereo, 48 kHz, session 393;
- route `sdk_gphone64_x86_64 (#2)`;
- 4,360-frame effective buffer and zero reported underruns during idle, throttle, and brake checks.

The host audio backend is now enabled and the Android track is actively routed. Android diagnostics cannot prove the Windows speaker volume or perceived sound quality, but the previous `-no-audio` limitation is removed.

Final accelerated build evidence:

- APK size `13,258,042` bytes and SHA-256 `D62B4994C565AE77ECFEF5BE7FFFDA1ECA6C1FCD0B6AB020CAAD0FD21366C6C2`;
- 27 unit tests, debug assembly, and Android lint passed with zero failures or lint issues;
- the dashboard displays a 0-10 tachometer with its 8,600 RPM red zone;
- the separately modeled fuel cutoff is 8,850 RPM and the performance upshift point is 8,250 RPM;
- the torque curve peaks at 6,500 RPM and uses nine absolute-RPM points;
- the live-tuning menu exposes tachometer maximum, redline, and fuel cutoff as separate persisted controls;
- the installed app reported a 1920 x 990 viewport, logical stereo at 48 kHz, and zero underruns at idle;
- a sustained 95% touchscreen launch remained in first gear and climbed smoothly through 2,730 RPM without the previous clutch-engagement reversal;
- no application fatal exception or ANR was logged.

The accelerated emulator was left open with the application running for interactive testing.

## Historical pre-reboot limitation

The host is an AMD Ryzen 7 5800X3D system, but hardware virtualization/SVM was disabled in firmware. The current x86_64 Android Emulator therefore could not obtain WHPX/hardware acceleration. Enabling SVM in BIOS/UEFI is the correct long-term fix; it was not changed automatically because that requires a host reboot and firmware interaction.

## Software-emulated fallback

The working fallback uses the official Android Emulator 34.2.16 archive, build `12038310`:

- download: `https://dl.google.com/android/repository/emulator-windows_x64-12038310.zip`
- verified archive SHA-256: `FBC546728E5C08924B2B0A7F1922DF0696FDE440F44CEAF5DA908942774DD1B3`
- unpacked, ignored local tool path: `build/tools/emulator-34.2.16/emulator/`
- system image: `system-images;android-27;google_apis;arm64-v8a`
- AVD: `BYD_Seal_API27_ARM64`
- guest verified as Android 8.1 / API 27 / `arm64-v8a` / AArch64

Launch command used:

```powershell
$projectRoot = 'D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound'
$sdkRoot = 'D:\Users\sgabr\AppData\Local\Android\Sdk'
$emulator = Join-Path $projectRoot 'build\tools\emulator-34.2.16\emulator\emulator.exe'
$systemImage = Join-Path $sdkRoot 'system-images\android-27\google_apis\arm64-v8a'

& $emulator `
  -avd BYD_Seal_API27_ARM64 `
  -sysdir $systemImage `
  -gpu swiftshader_indirect `
  -accel off `
  -memory 4096 `
  -cores 4 `
  -no-snapshot `
  -no-boot-anim `
  -no-audio `
  -qemu -machine virt
```

`-no-audio` was chosen for a stable unattended UI validation under very slow software CPU emulation. It means this emulator run cannot validate sound quality, host audibility, acoustic latency, or physical speaker routing. Remove that flag after enabling hardware virtualization for an audible desktop run.

After boot, the test display was configured as follows:

```powershell
adb shell wm size 1920x1080
adb shell wm density 160
```

The app's `WindowInsets.safeDrawing` measurement reported a `1920 x 990` content viewport, leaving the emulator's 90-pixel navigation region outside the dashboard. This confirms the requested layout target in this emulator only; the BYD panel must still be measured.

## Historical software-fallback build evidence

Validated APK:

- path: `mobile/build/outputs/apk/debug/mobile-debug.apk`
- size: `13,257,983` bytes
- SHA-256: `89F354B976AAB083537F5E6A201D6AEF748D0F3FD218E9926EF34DD129060CF1`
- package: `com.gabrielpc.bydmotorsound`
- version: `1.0` (`versionCode=1`), min/target SDK 25

Build verification completed with 21 unit tests, zero failures/errors/skips, debug APK assembly, and Android lint. The final APK was installed at `2026-08-15 06:02:52` local time and launched as the resumed Activity.

The following ignored screenshots were captured from the final installed APK:

- `build/emulator/byd-final-idle.png`: dashboard at the corrected 950 RPM idle and 1920 x 990 viewport;
- `build/emulator/byd-final-throttle.png`: touch throttle at 70%, 2,520 RPM, and 24.8 km/h;
- `build/emulator/byd-final-brake.png`: touch brake at 92%, approximately -1.1 g, with the rev-match indicator active.

Throttle and brake values returned to zero after gesture release. The generic guest correctly stayed in simulator fallback because it does not expose BYD's `android.hardware.bydauto.speed.BYDAutoSpeedDevice`.

Software ARM emulation is substantially slower than a real head unit or a hardware-accelerated emulator and occasionally caused an Android System UI not-responding prompt. Choosing **Wait** allowed it to recover; the application itself did not crash or report an ANR. Do not use this setup for latency or frame-time conclusions.
