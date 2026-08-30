# Handoff: unresolved audible pitch stepping

## Current state

- Repository: this checkout (all paths below are repository-relative unless stated otherwise).
- Branch: `codex/fmod-skyline-bank-runtime`
- Last committed implementation: `2ae277c Bound FMOD audio pitch trajectory`
- The most recently tested emulator package was version `1.0.60` / code `60`, built from that
  commit. Rebuild and install it on the target machine before relying on this fact.
- The user has listened to this build and reports that the problem is **still present**. Do not
  describe the issue as fixed without a new subjective listening confirmation.

The user reports a synthetic, stepped pitch change on every car and even on the Skyline turbo.
It is especially obvious on a downshift, where pitch rises then immediately falls. The same
complaint existed before the FMOD migration, when the app used its own WAV/AudioTrack renderer.
That makes a bank-specific or Skyline-specific explanation unlikely.

## What has already been changed and disproven

1. `03cdfcf` replaced direct integer-speed use with `QuantizedSpeedEstimator`.
   - Real BYD speed is reported as whole km/h.
   - The estimator predicts continuous motion between quantizer-boundary crossings rather than
     feeding integer values directly into RPM.
   - Simulator mode has always kept continuous speed.

2. `5553d2b` changed FMOD Studio from asynchronous to
   `FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE`.
   - A temporary trace showed that in asynchronous mode the sent parameter values were accepted
     but `finalvalue` could remain held for roughly 15–55 ms.
   - Synchronous mode made the queried final values equal the sent values at the 400 Hz control
     cadence.
   - This did **not** remove the user-visible/audible symptom.

3. `45181df` changed the RPM/drivetrain follower from a first-order exponential follower to a
   critically damped second-order follower.
   - The math removed abrupt velocity changes at 200 Hz source update boundaries.
   - This also did **not** remove the symptom.

4. `2ae277c` replaced the engine RPM presentation follower with a bounded, log-pitch follower.
   - File: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/FmodContinuousParameterInterpolator.kt`
   - It limits RPM presentation to 5 octaves/s (15 cents per 400 Hz control tick) and uses
     bounded acceleration; it changes neither vehicle torque, tachometer, shift decisions, nor
     telemetry.
   - Unit test added: `abruptLaunchCatchUpHasBoundedPerceptualPitchRate`.
   - The user still hears stepping.

## Important runtime facts

- `DriveController` simulation fixed step: 200 Hz / 5 ms.
  - File: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/drive/DriveController.kt`
- FMOD Kotlin control worker: nominally 400 Hz / 2.5 ms, `THREAD_PRIORITY_AUDIO`.
  - File: `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/EngineAudioEngine.kt`
- Native runtime currently uses `FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE`, applies all event
  parameter values, then calls `FMOD_Studio_System_Update` on every control update.
  - File: `mobile/src/main/cpp/fmod_bridge.cpp`, `FmodRuntime::update`.
- Device DSP configuration currently requests and validates `256 x 4` at 48 kHz stereo.
  - `kDeviceDspBufferFrames = 256` in `fmod_bridge.cpp`.
- Emulator FMOD log from the installed build:

  ```text
  FMOD::supportsLowLatency : Low latency = false ... Acceptable Block Size = false (1088)
  AudioDevice::init : Min buffer size: 17440 bytes
  AudioDevice::init : Actual buffer size: 17440 bytes
  ```

  The emulator therefore does not provide a low-latency output route. This may magnify blockwise
  artifacts, but should be measured rather than assumed to be the sole cause.

- The separate desktop Assetto audio lab (location is machine-specific) uses FMOD 1.08.12 with
  default Studio init flags (not synchronous). It calls
  `SetParameterValue` then `Studio_System_Update`; for audition it holds both engine events'
  `throttle` at `1.0`.
  - Relevant file in that lab checkout: `sim/fmod_native.py`.

## Likely remaining hypotheses (in priority order)

1. **Block/control-rate artifact shared with the previous WAV renderer.**
   The prior WAV renderer rendered one block at a time, and FMOD currently updates Studio
   parameters at a block/control cadence. Test a smaller FMOD mixer block (`64 x 4`) and an
   actual measured control-loop timing trace before assuming the nominal 400 Hz rate is met.
   Do not change this permanently unless it is stable and demonstrably improves captured output.

2. **Synthetic virtual-gear trajectory, particularly during downshifts.**
   `EngineSimulation` changes `currentGearIndex` at 38% of a 95 ms upshift / 220 ms downshift;
   its source RPM target can reverse shortly thereafter. A downshift can therefore produce a
   rise followed by a fall even when every individual parameter update is smooth.
   Preserve the vehicle's gear and torque behavior, but consider a dedicated audio shift
   trajectory that has a single monotonic, eased transition to the post-shift RPM target.
   Keep its state separate from the dashboard tach and physical EV model.

3. **The FMOD bank's own parameter/layer changes.**
   `rpms` and `boost` can switch authored sample layers. If measured output confirms hard
   spectral discontinuities despite a smooth input trajectory, a two-instance crossfade or an
   authored-bank parameter seek speed is the next class of solution. Do not add WAV fallback.

4. **Android/emulator output route.**
   Isolate a synthetic FMOD pitch sweep and compare it with a bank engine-only and turbo-only
   sweep. If all three show the same staircase, it is output/runtime scheduling rather than bank
   behavior. A real BYD/device run is ultimately required because this AVD advertises no
   low-latency route.

## Required next diagnostic

The user explicitly requested a recording/spectral analysis of an isolated track. Do this before
claiming another fix:

1. Use the real FMOD device runtime, not only `NOSOUND_NRT` meter validation.
2. Capture separate traces for:
   - Skyline `engine_int`, full load, a slow monotonic RPM sweep.
   - Skyline `turbo`, smooth monotonic boost sweep.
   - A scripted downshift from the real `EngineSimulation` / `DriveController` state.
   - Ideally a simple generated FMOD tone with a smooth frequency sweep as an output-path
     control.
3. Record system or emulator loopback using the host platform's available capture path and generate
   a spectrogram/pitch contour. Compare event-parameter timestamps, control worker timestamps,
   and captured audio boundaries.
4. Quantify the duration/magnitude of pitch plateaus and discontinuities. The diagnostic must
   distinguish a physical gear-ratio transition from a repeated blockwise pitch staircase.

An interrupted helper began creating this untracked temporary test:

`mobile/src/androidTest/java/com/gabrielpc/enginesoundsimulator/audio/FmodHostCaptureDiagnosticTest.kt`

It has no validated result yet. Review or discard it deliberately. Two additional untracked
directories (`META-INF/`, `org/`) were produced by interrupted tooling and are not part of the
implementation.

## Portable build/run outline

Install Android Studio/SDK and configure this repository's ignored `local.properties` with the
FMOD Android SDK path through `fmod.sdk.dir`. The private bank and preview copies are read from
the repository's ignored `audio_banks/` directory, so an Assetto Corsa installation is no longer
required to build this project.

On macOS/Linux:

```sh
export JAVA_HOME="/path/to/Android-Studio-jbr-or-JDK"
./gradlew :mobile:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug :mobile:assembleDebugAndroidTest --no-daemon
./gradlew :mobile:connectedDebugAndroidTest --no-daemon

adb devices
adb -s <device-or-emulator-serial> install -r -t mobile/build/outputs/apk/debug/<debug-apk>.apk
adb -s <device-or-emulator-serial> shell am force-stop com.gabrielpc.enginesoundsimulator
adb -s <device-or-emulator-serial> shell monkey -p com.gabrielpc.enginesoundsimulator 1
```

Use the project-specific emulator if it is available on the new machine; otherwise create an
x86_64 Android emulator with comparable audio output and record its actual FMOD diagnostics.

## Constraints to preserve

- Direct FMOD bank playback only; no WAV fallback.
- Keep EV physical torque instantaneous and do not introduce combustion clutch/drivetrain lag.
- Keep automatic/manual shifting, kickdown, launch-control policy, ignition policy, 190 km/h
  top speed, and profile-specific RPM/redline logic.
- Any audio-only trajectory changes must not change telemetry, tach behavior, gearbox decisions,
  or EV axle torque.
- Coast Only remains optional and default-off; it is not the pitch-stepping solution.
