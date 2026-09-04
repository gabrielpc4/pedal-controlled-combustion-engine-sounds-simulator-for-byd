# Cursor handoff: BYD Motor Sound / Assetto Corsa FMOD simulator

This document is a working handoff for another coding agent (especially Cursor). It records the
current workspace, the important history, and the decisions that shaped the implementation.
Every decision in this file is changeable. Treat the user's latest request and the current source
code as authoritative; use this document as context, not as an unchangeable specification.

## 1. Product in one paragraph

`BYDMotorSound` is a private Android dashboard for a BYD Seal/DiLink vehicle multimedia unit. It
reads vehicle telemetry when available, or accepts touch-based simulated pedals, and drives
Assetto Corsa's authored FMOD engine and drivetrain sounds. The app is read-only with respect to
the vehicle. It must never write CAN/vehicle state, bypass permissions, root the device, or try to
control the car.

The Android app and the separate Audio Lab are different applications. The Audio Lab is a useful
reference and diagnostic tool, but the Android app is the product that the user tests in the
emulator and in the car.

## 2. Workspace and external files

### Main repository

* Repository: `/Users/gabrielcarvalho/Downloads/BYDMotorSound`
* Remote: `git@github.com:gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd.git`
* Current branch at the time of this handoff: `gpc/fmod-based`
* Current HEAD: `93fc884` (`Load installed car previews from bank packages`)
* The checkout was clean and tracking `origin/gpc/fmod-based` when this document was started.
* `local.properties` is machine-local and ignored. Current configured values include:
  * Android SDK: `/Users/gabrielcarvalho/Library/Android/sdk`
  * FMOD Android SDK: `/Users/gabrielcarvalho/Downloads/fmodstudioapi20314android`
  * `assettoCorsa.dir` still contains a Windows path from the old setup and is not a valid Mac
    source by itself; do not silently rely on it.

### Assetto and mod sources

* Official Assetto Corsa installation used by the generator:
  `/Users/gabrielcarvalho/Downloads/assetto_corsa_installation`
* Official car source folders are under
  `assetto_corsa_installation/content/cars`.
* Shared original banks are under
  `assetto_corsa_installation/content/sfx`.
* Modded source folders are under `/Users/gabrielcarvalho/Downloads/new_cars`.
  Each extracted car normally has `sfx/*.bank`, `preview1.jpg` or `.png`, and `info.txt`.
  Original compressed files were moved to `new_cars/_original_compressed_files` during intake.
* A second historical folder exists at `/Users/gabrielcarvalho/Downloads/original_cars`.
  It is not the authoritative source for the current official pack.
* Extra preview files supplied by the user are at
  `/Users/gabrielcarvalho/Downloads/car_previews`.
* Original banks and generated packages should not be committed to Git. Generated output is
  intentionally ignored because it is large.

### Audio Lab

* Audio Lab root: `/Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab`
* It contains `server.py`, `sim/`, `web/`, native FMOD integration, and a catalog of bank packs in
  `app_fmod_banks`.
* macOS launcher: `/Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab/run-macos.sh`.
  It starts the local web server at `http://127.0.0.1:8765/`, uses the FMOD API path from
  `FMOD_API_ROOT` (defaulting to `~/Downloads/FMOD Programmers API/api`), and passes
  `app_fmod_banks` as the bank root when it exists.
* FMOD API download supplied by the user:
  `/Users/gabrielcarvalho/Downloads/FMOD Programmers API/api`.
  The Android build currently uses the separate 2.03.14 SDK configured above.
* The Audio Lab has historically been the best place to inspect which authored source/event is
  active, to compare neutral revving, and to observe shift/turbo/limiter ordering. Do not assume
  that a similarly named car uses the same audio family; 350Z and 370Z were explicitly called out
  as different cars.

## 3. Android modules and entry points

Gradle modules are `mobile`, `audio-installer`, `automotive`, and `shared` (see `settings.gradle.kts`).

### Dashboard (`mobile`)

* Application ID: `com.gabrielpc.enginesoundsimulator`
* Activity: `com.gabrielpc.enginesoundsimulator.MainActivity`
* Main control owner: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/drive/DriveController.kt`
* Simulation: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/simulation/`
  * `EngineSimulation.kt` adapts the active `AssettoDrivetrain`.
  * `AssettoDrivetrain.kt` is the authored per-car drivetrain model.
  * `AssettoPhysics.kt` and its loader parse the exported physics JSON.
  * `BydSealSimulatedPedalsMotion.kt` implements the simulated-pedal road envelope.
  * `QuantizedPresentationSpeedEstimator.kt` prevents integer BYD telemetry from becoming a
    stepped audio/tach signal.
* FMOD/audio: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/`
  * `EngineAudioEngine.kt` owns the FMOD worker lifecycle and sends fixed-step frames to native.
  * `NativeFmodBankBridge.kt` is the Kotlin JNI boundary.
  * `FmodBankContentProvider.kt` is the exported write-only provider used by the installer.
  * `FmodBankStore.kt` verifies and atomically publishes bank archives in private app storage.
  * `FmodBankProfile.kt` is the static catalog metadata.
  * `FmodMixer.kt` and `DashboardScreens.kt` provide diagnostic source cards.
* Native bridge: `mobile/src/main/cpp/fmod_bank_bridge.cpp` and `CMakeLists.txt`.
* Current manifest deliberately requests only read permissions for BYD speed, gearbox, and
  vehicle pedals. Do not add setter permissions.

### Bank installer (`audio-installer`)

* Application ID: `com.gabrielpc.enginesoundsinstaller`
* Activity: `com.gabrielpc.enginesoundsinstaller.AudioInstallerActivity`
* It embeds the generated `.bydbank` archives in its APK and copies selected archives through the
  dashboard content provider. Buttons are `INSTALL ORIGINAL CARS`, `INSTALL MODDED CARS`,
  `INSTALL BOTH`, and `DELETE ALL`.
* The APK is intentionally large (about 2.1 GB with the current catalog). A fresh install can
  need several gigabytes of temporary Android staging space.

## 4. Current bank packaging and catalog

The generator is `tools/build_fmod_bank_packs.py`.

* Official cars are an explicit list sourced only from the installed Assetto content. The current
  generated index has **23 official profiles** (the original 22-car plan plus the Nissan Skyline
  R34 that the user later requested) and two shared dependencies.
* Modded discovery scans `new_cars` and currently yields **33 modded profiles**.
* Current groups in `fmod_bank_packs/index.json` are both active:
  * `original_cars_pack`
  * `modded_car_packs`
* The current generated index has 25 original-group packages (23 cars plus
  `assetto-common-strings` and `assetto-common`) and 33 modded car packages.
* Each car archive contains one bank, optional preview, and
  `profiles/<profile-id>/physics.json` with schema `byd-assetto-physics-v1`.
* Archive schema accepted by Android is `byd-fmod-bank-pack-v3`.
* Index schema is `byd-fmod-bank-index-v2`.
* Common banks are always installed automatically by the installer, even when only modded cars
  are selected.
* `fmod_bank_packs/` is generated and ignored by Git. `audio_packs/` and older WAV artifacts are
  historical/legacy material; do not reintroduce their runtime path unless the user explicitly
  changes direction.

### Official profile IDs

The explicit list in both the generator and `FmodBankProfile.kt` is:

`alfa-romeo-4c`, `assetto-audi-r8-lms-2016`, `assetto-audi-r8-plus`, `assetto-audi-tt-cup`,
`assetto-bmw-m4`, `assetto-corvette-c7-stingray`, `assetto-ferrari-458`,
`assetto-ferrari-458-gt2`, `assetto-ferrari-488-gtb`, `assetto-ferrari-488-gt3`,
`assetto-ferrari-fxx-k`, `assetto-ferrari-laferrari`, `assetto-lamborghini-aventador-sv`,
`assetto-lamborghini-gallardo-sl`, `assetto-lamborghini-huracan-performante`,
`assetto-lamborghini-huracan-st`, `assetto-mercedes-amg-gt3`, `assetto-nissan-370z`,
`assetto-nissan-gtr`, `assetto-nissan-skyline-r34`, `assetto-porsche-911-gt3-rs`,
`assetto-porsche-991-turbo-s`, `assetto-toyota-supra-mkiv`.

### Modded profile IDs

The current 33 IDs are the `modded-...` entries in `FmodBankProfile.kt` and the generated index:

`modded-aston-martin-dbrs9-gt3`, `modded-audi-r8-lms-gt2`, `modded-audi-tt-cup-2015`,
`modded-bmw-m8-gtlm`, `modded-bugatti-chiron-pur-sport`, `modded-cadillac-escalade-esv`,
`modded-chevrolet-camaro-concept`, `modded-chevrolet-corvette-c6-z06-stanced`,
`modded-chevrolet-corvette-c7-stingray-hellspec`, `modded-ferrari-360-challenge-stradale`,
`modded-ferrari-458-italia-gte-ferruccio`, `modded-ferrari-458-italia-tune`,
`modded-ferrari-488-gte-evo-michelotto`, `modded-ferrari-f1-2000`,
`modded-ferrari-f430-gt2-2007`, `modded-ferrari-laferrari-trio`,
`modded-ferrari-sf90-xx-stradale-2024`, `modded-lexus-lfa`, `modded-lexus-lfa-concept-gt500`,
`modded-lexus-lfa-no-hesi-spec`, `modded-lexus-lfa-nurburgring-edition`,
`modded-mercedes-amg-project-one-hypercar`, `modded-mercedes-benz-amg-gt3-evo-2020-sprint`,
`modded-mitsubishi-eclipse-gsx-r`, `modded-mitsubishi-lancer-evolution-viii-gsr`,
`modded-nissan-350z`, `modded-nissan-370z-widebody`, `modded-nissan-gt-r-nismo-godzilla`,
`modded-porsche-911-992-turbo-s-pdk`, `modded-porsche-911-gt3-rs-hellspec`,
`modded-porsche-911-turbo-s`, `modded-porsche-carrera-gt-rs`, and
`modded-toyota-supra-wangan`.

## 5. Runtime behavior that exists now

### Control flow

```text
BYD telemetry or touch pedals
        -> DriveController (fixed 3 ms simulation loop)
        -> EngineSimulation / AssettoDrivetrain
        -> DriveSnapshot + EngineAudioFrame
        -> EngineAudioEngine (3 ms FMOD control loop)
        -> JNI native FMOD Studio bank playback
```

`DriveController` persists the selected car, perspective, and manual/automatic choice. Car
selection is filtered to profiles whose bank and physics are installed. The default profile is
Alfa Romeo 4C, but after a wipe with only modded packs installed, the first installed modded
profile is selected (currently Aston Martin DBS / DBRS9 GT3).

FMOD starts at authored idle when a bank is loaded. The old artificial `OFF/STARTING/RUNNING/
STOPPING` ignition state, start animation, synthetic start ramp, and application Start/Stop button
were removed. Activity/service stop still releases resources. Header mute intentionally stops and
restarts the whole FMOD engine when unmuted to clear stale event/decoder state.

### Real pedals

* BYD speed is truncated with `floor` to `[N, N+1)` before it is used as raw input.
* Throttle values at or above 99% normalize to 100% so launch/full-throttle behavior is possible.
* The quantized presentation estimator uses boundary timing and bounded prediction only for
  audible road-coupled RPM/tach/pitch. It must not alter raw speed display, gear decisions,
  launch control, vehicle load, or FMOD gain.
* Fractional telemetry, if ever supported, should bypass quantization reconstruction.

### Simulated pedals

* The user deliberately asked for a BYD Seal AWD-like road-speed response, with partial throttle
  scaling from the full-throttle curve and a 190 km/h cap.
* In D, equal presentation speed bands are currently used so the selected bank's limiter aligns
  with the final 190 km/h boundary. This is a user-requested experiment and is not pure original
  Assetto behavior.
* Park/Neutral stays on the free-rev path and should not receive road-speed gear calibration.
* The user previously experimented with slow propulsion, drag/uphill resistance, and regen; later
  requests removed or restored these several times. Inspect current code and the latest request
  before changing them. Do not assume historical experiments are still wanted.

### FMOD and mixer

FMOD owns authored event graphs, randomization, automation, source material, and bank routing.
Android sends physical parameters such as RPM, throttle, drivetrain speed, boost, BOV, limiter,
shift, backfire, and traction state. Cabin/exterior changes the selected authored perspective.
Tires, wind, chassis, doors, and other explicitly excluded events should stay excluded unless the
user asks otherwise.

The mixer is diagnostic. It receives native voice/source snapshots with event path, source name,
audibility, route gain, virtual/silent state, and voice count. Cards are grouped in adaptive
semantic sections and engine is intentionally shown last. Every card has temporary event-level
mute/solo controls; controls reset when changing car or reopening the app. Host engine/effects
gain controls have existed for comparison work and currently default to engine `1.0` and effects
`2.0` in `EngineAudioEngine`; these are diagnostic controls, not authored FMOD mix values. The user
has repeatedly changed their desired gain policy, so confirm before removing or changing them.

Cards that have appeared may remain visible after becoming silent, while truly unused READY cards
were requested to be removed. Recent-entry highlighting is intended to be temporary (about one
second) and must not permanently highlight old cards. A card's mute/solo key must represent the
event/source identity, not only the transient sound currently selected by a bank.

## 6. Historical decisions and lessons

This project has gone through several deliberately reversible generations. The following is
context, not a mandate:

1. It began with WAV extraction/playback because it was simple to inspect. WAV conversion caused
   muffled/incomplete or mismatched sounds for some cars and could not reproduce all FMOD event
   behavior.
2. FMOD native bank playback became the main direction. FMOD 2.03.14 is the current Android SDK
   because it resolved a severe synthetic pitch-stepping problem seen with an earlier setup.
3. Integer BYD speed caused audible pitch steps. A quantization-aware presentation estimator was
   added after experiments with fixed 120 ms corrections. The intended model treats telemetry as
   truncated integer bins, predicts continuously between boundaries, seeds low-speed motion
   from pedals, cancels on brake/direction change, and settles at exact zero. It must remain
   presentation-only.
4. The user asked repeatedly for Audio Lab parity: 3 ms physics, authored throttle/load logic,
   native FMOD event lifecycle, neutral/P free revving, turbo/BOV, limiter, shift, backfire, and
   transmission behavior. Some later requests intentionally deviated from pure Lab behavior for
   simulated pedals (BYD Seal response and equal 190 km/h gear bands).
5. The user requested explicit separation of official and modded cars. Official sources must come
   from the installed Assetto content; modded sources must come from `new_cars`; similarly named
   cars must not share a bank without evidence.
6. The user later requested an installer that can install original, modded, or both groups and a
   DELETE ALL operation. That is the current packaging direction.
7. The user created `fmod-pure` as an intocable baseline and then `gpc/fmod-based` from it for
   authorized modifications. `fmod-pure` is a reference branch; do not rewrite it casually.
8. The project once had many tuning controls (LOAD/COAST/BOTH, forced LOAD, throttle RPM
   response/bump, virtual drag, regen, custom shift RPM/timing, 190 km/h mapping, per-effect
   overrides). They were removed, restored, or replaced across different requests. The current
   source, current branch, and newest user instruction win over this history.
9. A repeated failure mode was routing several FMOD sources into a single-looking mixer card or
   showing master volume as each source's percentage. Use actual native event/source ownership
   and audibility when touching the mixer.
10. Audio Lab's neutral rev behavior was considered more realistic than early Android versions.
    It remains a diagnostic reference, but do not change either app solely from memory; capture
    logs and inspect source/parameter timelines first.

## 7. Current emulator and launch script

The primary AVD is `BYD_Multimedia_with_Hardware_Controls`, Android API 35, arm64, 1920x1080,
about 1.5 GB configured RAM, and a 10 GB data partition. The emulator was reset with `-wipe-data`
to make the large installer APK fit. That reset removed installed apps and preferences.

The user also runs a separate work AVD named `ballerz_pixel8`. It may occupy `emulator-5554`; the
BYD AVD may then use `emulator-5556`. Do not identify the BYD emulator by “any emulator process” or
by the first `adb devices` row.

The launcher script is `/Users/gabrielcarvalho/Documents/open_byd_simulator.sh`. It now loops over
connected devices, asks each device for `adb -s <serial> emu avd name`, and reports “already
running” only when the name exactly equals `BYD_Multimedia_with_Hardware_Controls`. This fix was
made outside the Git repository. It starts the AVD with `-avd BYD_Multimedia_with_Hardware_Controls
-no-boot-anim`.

Useful checks:

```sh
adb devices -l
adb -s <serial> emu avd name
adb -s <serial> shell getprop ro.boot.qemu.avd_name
adb -s <serial> shell df -h /data/user/0
```

## 8. Build, install, and run

The build number is persisted in `mobile/build-number.properties`. A car APK assemble with
`-PcarApk=true` increments it. The last built dashboard was Build 144.

From the repository:

```sh
cd /Users/gabrielcarvalho/Downloads/BYDMotorSound
python3 tools/build_fmod_bank_packs.py --force
./gradlew :mobile:assembleDebug :audio-installer:assembleDebug --no-daemon -PcarApk=true
```

Expected current output names:

* `mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<N>-debug.apk`
* `audio-installer/build/outputs/apk/debug/engine-sounds-audio-installer-debug.apk`

Install the dashboard before the installer. Select the correct connected serial, especially when
`ballerz_pixel8` is also running:

```sh
adb -s <BYD_SERIAL> install -r mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<N>-debug.apk
adb -s <BYD_SERIAL> install -r audio-installer/build/outputs/apk/debug/engine-sounds-audio-installer-debug.apk
adb -s <BYD_SERIAL> shell am start -n com.gabrielpc.enginesoundsinstaller/.AudioInstallerActivity
```

Tap `INSTALL MODDED CARS` for the current modded-only setup, or use the appropriate group button.
After installation, launch the dashboard:

```sh
adb -s <BYD_SERIAL> shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

The provider inventory can be checked without opening the UI:

```sh
adb -s <BYD_SERIAL> shell content query \
  --uri content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs
```

The installer may take a long time while copying about 1.6 GB of selected modded packages. Do not
kill it just because the UI appears unchanged; check the provider inventory and installer status.

## 9. Validation approach

The user values empirical checks. When diagnosing audio or physics, capture both parameter and
event evidence instead of guessing:

* Keep shift-sound override and pops-and-bangs override off unless explicitly testing them.
* Test from idle in D and in P/N separately. For D, observe RPM, gear, clutch engagement,
  drivetrain speed, throttle, boost/BOV, limiter, and event/source snapshots through at least two
  shifts and lift-off back to idle.
* Check cabin and exterior independently; source counts and locations legitimately differ.
* Confirm that raw speed, presentation speed, and predicted acceleration/deceleration agree with
  the intended responsibility boundaries.
* For mixer work, verify the actual event path/source identity, not only the displayed label. Check
  that muted/soloed event keys continue to apply when a bank swaps the sound inside the event.
* Use `adb logcat` with focused tags and screenshots/UI dumps on the 1920x1080 AVD. The emulator's
  audio output is a separate concern from Mac output routing; a standalone audio probe APK was
  previously used to prove emulator audio could be heard.
* The repository no longer contains persistent Android unit/instrumentation test sources; earlier
  tests were intentionally removed after validation. Temporary scripts/log captures should be kept
  outside the repository and deleted after use.

## 10. Known caveats and places to re-check

* Earlier documents described a “22 originals only / modded inactive” release. The current docs,
  generated index, and runtime support both groups with 23 official profiles. Do not restore the
  historical catalog boundary without an explicit product decision.
* `docs/new-cars-exceptions.md` retains historical modded observations (silent exterior layers,
  delayed interior activation, etc.). These are not proof that the current packages fail; retest
  before fixing.
* Audio percentage displays have historically been confused with car master volume. The native
  snapshot/audibility path is the intended source of truth.
* The current generated package files are large and ignored. Rebuilding with `--force` can take
  time and changes local generated output without changing Git.
* The macOS Audio Lab launcher uses `arch -x86_64 /usr/bin/python3` and depends on the local FMOD
  API. A Lab “offline” banner can be an emulator/simulator connection issue, not necessarily an
  FMOD-bank issue.
* Never claim a sound is exact or a car is fixed from a screenshot alone. Record what was measured,
  what remains a hypothesis, and which device/build was used.

## 11. Working rules for the next agent

1. Read this handoff and the repository docs, then inspect current Git status and branch before
   editing.
2. Preserve unrelated user changes and do not reset or checkout destructively.
3. Keep `fmod-pure` intact as the reference baseline. Changes normally belong on
   `gpc/fmod-based` or a new `gpc/...` branch created from the requested base.
4. Remove dead/outdated code when changing a generation; backwards compatibility is not required
   unless the user asks for it.
5. Add a short code comment when making a non-obvious behavior decision that a later agent could
   mistake for an error or omission, as the user specifically requested.
6. Do not treat any historical tuning value, gain, shift delay, or car-family assumption as
   permanent. Confirm it against current source, the bank, Audio Lab, or the latest user request.
7. For external mutations (installing, deleting emulator data, committing, pushing), state what is
   being changed and verify the resulting state. The user has generally requested commits and
   pushes after important changes, but confirm current scope when a change is unrelated.

## 12. Last verified snapshot

At handoff creation:

* Git branch: `gpc/fmod-based`
* Git HEAD: `93fc884`
* Dashboard APK: Build 144, approximately 38 MB
* Installer APK: approximately 2.1 GB
* BYD AVD data filesystem: approximately 10 GB total, approximately 9.8 GB free immediately
  after the wipe
* `ballerz_pixel8` was on `emulator-5554`
* BYD AVD was on `emulator-5556`
* 33 modded cars plus two shared dependencies were installed through the installer
* Dashboard was open with the modded Aston Martin DBS profile visible and its preview image loaded

This snapshot will become stale as soon as another build, install, branch change, or user decision
occurs. Re-run the checks above before relying on it.
