# Fresh-chat LLM handoff

Read this before changing the project, then verify the working tree and source. This document
describes the lossless Assetto Corsa catalog and persistent-driving architecture; older documents or
commits may still mention packaged WAV profiles or Activity-owned playback.

## Repositories and workflow

- Android checkout: `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`
- Offline AC compiler/reference oracle: `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`
- Android remote: `git@github.com:gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd.git`
- Primary branch: `main`

Before editing, run `git status -sb`, `git branch --show-current`, and `git log -1 --oneline`. Do not
pull, reset, clean, or overwrite a dirty tree until its ownership is understood. Preserve unrelated
work, including local IDE configuration.

For source changes, run the relevant unit/native tests, Android-test compilation, lint, and APK
assembly. Installation or foreground launch is a separate acceptance step: do not steal focus or
open speakers while the user is using the computer. A connected emulator can be installed silently,
but UI/audio gates remain pending until the user authorizes an interactive run.

Never commit APKs/AABs, Gradle output, raw/decoded recordings, FLAC files, `.aclib` packs, generated
catalogs, or game preview images. `mobile/build-number.properties` is source state and changes on
assembly.

## Product and safety boundary

This is a private, experimental BYD DiLink dashboard. It maps read-only accelerator/brake/speed and
gearbox input into a calibrated Seal Performance EV road model plus an independent combustion-car
tachometer/audio presentation. Target firmware observed is `13.1.33.2503250.1` (family `2503`). It is
not road-certified.

- Do not add BYD setters, CAN injection, rooting, firmware changes, package impersonation, or broad
  permission bypasses.
- Never collect or persist VIN, IMEI, ICCID, location, credentials, or other vehicle identifiers.
- Unavailable explicit BYD LIVE pedal signals resolve to zero; do not substitute stale touchscreen
  input.
- Synthetic audio can mask warnings. Physical testing must be parked or passenger-operated on a
  controlled route, at low volume.
- No wake lock or boot receiver belongs in this design.

`BydReadOnlyPermissionContext` remains narrowly scoped to the vendor read permissions. Firmware
`2503` still requires on-car proof that accelerator, brake, speed, and gearbox getters return
plausible values; reflection success alone is not that proof.

## Current architecture

The shipping app is the `mobile` module. `automotive` and `shared` are unused templates.

`DriveRuntimeService` is the exclusive process owner of:

- `DriveController` and the 200 Hz primitive core;
- BYD getter polling and input arbitration;
- the Seal longitudinal model and imported-ratio presentation gearbox;
- effect triggering and pops/bangs audition;
- native FLAC decode, native PCM mixer, streaming `AudioTrack`, audio focus, and diagnostics.

The service is foreground and sticky only for an explicitly active driving session. Home and app
switching leave it running. Recents dismissal or notification **Stop** fades and tears it down,
persists a stopped-by-user marker, abandons focus, removes the notification, and suppresses a sticky
restart. Opening the dashboard explicitly clears that marker. There is no boot start.

`MainActivity` binds in `onStart()` and unbinds in `onStop()`. A visible-only adapter requests one
immediate state and then snapshots at up to 60 Hz. Hiding the Activity cancels its sampler and all
Compose-only meters, lists, strings, thumbnails, animations, and debug presentation. Manual touch or
keyboard pedals release on focus loss/stop; live BYD input continues in the service.

## Catalog and media boundary

The selector index has **178 usable official/official-DLC cars**, deduplicated by exact bank hash into
**153 sound families**. The APK does not contain their converted media. The offline compiler produces
a private strict `catalog-v1.json`, previews, and atomic `.aclib` family packs. The app imports them
through the Storage Access Framework into app-private storage.

The selector is lazy/searchable and shows installed state, favorites, previews, and the selected-car
image. A favorite is only user classification; it does not bypass install or validation.

The pack validator checks official IDs/family membership, exact schema fields, safe ZIP paths and
sizes, image signatures, FLAC STREAMINFO, SHA-256 values, decoded frame counts/PCM, curves, triggers,
exclusive-end loops, and provenance. Installation is staged and atomic. Converted Assetto Corsa
media remains private even when its source is an official Kunos/DLC installation; do not redistribute
it without explicit rights.

## Audio facts

- Compiler output is final PCM16, 48 kHz stereo encoded losslessly as pinned FLAC level 8.
- Android decodes only the selected family, off-thread, with native `libFLAC`, and verifies the
  decoded bytes before activation.
- Installed-pack PCM is immutable native planar PCM16. The persistent native mixer performs cubic
  interpolation, phase/loop handling, gain smoothing, effects, limiting, and interleaved PCM output
  without per-buffer allocation.
- All continuous loops and enabled one-shots decode before the new profile swaps in. The prior car
  stays audible during preparation, crosses over at a buffer boundary, and is freed off-thread.
- Output is always PCM16/48 kHz/`CHANNEL_OUT_STEREO`; there is no multichannel mode or channel cycle.
- The adaptive target is 30–80 ms, starts at 50 ms, grows on underrun/low queue, and shrinks only
  after a 60-second clean window.
- Audio focus ducks, transiently fades/resumes, and stays silent after permanent loss until focus is
  genuinely reacquired.
- A persisted startup mute avoids both selected-family decode and `AudioTrack` creation. Runtime
  mute after audio has started remains phase-preserving and retains the active decoded family.
- Soft decoded budget: `min(64 MiB, memoryClass/8)`. Hard rejected budget:
  `min(192 MiB, memoryClass/4)`. Only one decoded family is retained.

Permitted core roles are `IDLE`, `COAST`, `TEXTURE`, `INTAKE`, `EXHAUST`, `TURBO`, `SPOOL`, `BOV`,
`TRANSMISSION`, `LIMITER`, shifts, `OVERRUN`, `POP`, `BANG`, and `CRACK`. `IDLE` is required and uses
its authored AC curve. Missing optional effects are valid. `LOAD`, the excluded on-throttle bank
role, must not appear in compiler plans, manifests, media, decode branches, or UI; schema tests
enforce that absence.

Read [Audio, simulation, and Assetto Corsa car packs](llm-handoff-audio-simulation-and-car-porting.md)
before changing capture, profiles, mixing, memory, or gearbox behavior.

## Gearbox rule that must be preserved

The selected AC car's actual relative ratios drive the presentation tach. Top gear is scaled to hit
upshift RPM at configured top speed. EV wheel force is still independent.

```text
landingRpm = upshiftRpm * nextRatio / currentRatio
```

After an upshift, released-throttle downshift occurs at that calculated landing RPM. There is no
150 RPM compensation and no RPM hysteresis. Shift dwell prevents overlapping animations but must not
move the threshold. Kickdown and emergency upshift remain separate paths.

## Key code map

| Area | Main files |
| --- | --- |
| Service and binder | `drive/DriveRuntimeService.kt`, `DriveRuntimeSessionStore.kt`, `DriveUiLifecycleGate.kt` |
| Core and diagnostics | `drive/DriveController.kt`, `DriveRuntimeDiagnostics.kt` |
| Simulation | `simulation/EngineSimulation.kt`, `TransmissionPosition.kt` |
| Catalog/import | `catalog/OfficialCarIndex.kt`, `CarCatalog.kt`, `AclibPackImporter.kt`, `SoundFamilyManifestV1.kt` |
| Decode/load | `audio/NativeFlacDecoder.kt`, `NativeSoundFamilyLoader.kt` |
| Native mix/output | `audio/NativePcmMixer.kt`, `SampleEngineRenderer.kt`, `EngineAudioEngine.kt`, `AdaptiveAudioBuffer.kt`, `src/main/cpp/native_flac.cpp` |
| UI | `MainActivity.kt`, `DashboardScreens.kt`, `TuningPanel.kt`, `SoundEffectsPanel.kt`, `DebugPanel.kt` |
| Telemetry | `telemetry/BydSpeedReader.kt`, `BydReadOnlyPermissionContext.kt`, `BydGearboxMapping.kt` |

## Diagnostics

Persistent low-rate events are bounded in app-private storage. The debug **MARK CRACKLE** action
captures timing context immediately; JSONL export through the Storage Access Framework includes a
runtime snapshot plus recent events. Relevant fields include car, RPM, gear, speed, buffer target,
queued frames, underruns, loop wraps, render p99/max, GC counters, peak, and over-range samples.
Never format or fsync diagnostics on the 200 Hz or audio render path.

For a debug build, the historical log can also be read with:

```powershell
$adb = 'D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
```

## Silent build workflow

Use bounded workers while other desktop work is in progress:

```powershell
$project = 'D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound'
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
Set-Location $project

.\gradlew.bat :mobile:externalNativeBuildDebug `
    :mobile:testDebugUnitTest `
    :mobile:compileDebugAndroidTestKotlin `
    :mobile:lintDebug `
    :mobile:assembleDebug `
    --max-workers=2 --console=plain
```

The generated APK is named:

```text
mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<build>-debug.apk
```

`assembleRelease` intentionally emits an unsigned APK: the repository has no release keystore or
secret-bearing Gradle signing configuration. Produce an installable release only with an explicitly
supplied local keystore and process-scoped password variables:

```powershell
$env:BYD_RELEASE_KEYSTORE_PASSWORD = '<local secret>'
$env:BYD_RELEASE_KEY_PASSWORD = '<local secret>'
.\tools\sign-release-apk.ps1 `
    -UnsignedApk .\mobile\build\outputs\apk\release\engine-sounds-simulator-build-<build>-release.apk `
    -OutputApk D:\private\BYDMotorSound-<build>-release-signed.apk `
    -Keystore D:\private\byd-release.jks `
    -Alias byd-release
Remove-Item Env:BYD_RELEASE_KEYSTORE_PASSWORD,Env:BYD_RELEASE_KEY_PASSWORD
```

The script verifies both v1 and v2 signatures. It asks the signer for API-23 signature coverage to
force retention of v1; the APK manifest still controls the application's actual API-25 minimum.
Never commit the keystore, passwords, or signed artifact. The standard Android debug key is suitable
only for a clearly labelled local sideload test, never a production release.

Do not launch it merely to claim verification. Record which gates actually ran. The emulator cannot
validate BYD permissions, OEM focus policy, amplifier/DSP latency, cabin sound, or audible seams.
The complete silent compiler commands and physical acceptance procedure are in
[Lossless packs and persistent driving acceptance](aclib-background-acceptance.md).

## Required reading order

1. This handoff
2. [Audio, simulation, and car packs](llm-handoff-audio-simulation-and-car-porting.md)
3. [Full implementation](full-implementation.md)
4. [Acceptance checklist](aclib-background-acceptance.md)
5. [BYD Seal calibration](byd-seal-performance-calibration.md)
6. [UI/simulation decisions](ui-display-and-simulation-decisions.md)
7. [Persistent diagnostics](persistent-diagnostics.md)
8. [BYD research notes](research-findings.md)
