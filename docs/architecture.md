# Architecture

## Purpose

The application combines an electric-vehicle road simulation with a **presentation-only**
combustion-engine layer. The presentation layer produces gauge RPM, shifts, and audio; it must not
change vehicle control or claim to be an accurate combustion drivetrain model.

The code is arranged around a one-way realtime data flow:

```text
BYD getters or simulator controls
             |
       DriveController (fixed-step coordinator)
             |
        EngineSimulation ------> Compose UI snapshot
             |
       EngineAudioFrame
             |
SampleEngineRenderer -> stereo PCM AudioTrack -> factory DSP/speakers
```

The names above are search anchors, not a promise that every implementation detail will remain in
one file. Start from `DriveController`, `EngineSimulation`, `EngineAudioEngine`, and
`SampleEngineRenderer` when tracing the current implementation.

## Separation of responsibilities

### Input and coordination

`DriveController` owns application-level lifecycle, input selection, fixed-step updates, selected
car state, user preferences, and the handoff from simulation to audio. The telemetry reader and
the on-screen simulator are alternate input sources; they should feed the same normalized input
model rather than create separate audio or physics paths.

### Simulation and presentation gearbox

`EngineSimulation` owns virtual road speed, braking, pedal smoothing, transmission position, and
the state shown on the dashboard. In Drive, the fictional tachometer follows a continuous estimate
of road speed rather than raw integer samples. In Park/Neutral, it can use free-rev behavior.

The selected sample profile supplies the sound-facing RPM domain and presentation gearing. Those
gears are a storytelling and audio device: changing a presentation gear must not introduce a
clutch, torque interruption, or feedback into the electric road-force calculation.

### Sample engine audio

`EngineSampleProfile` is the data boundary between a car recording set and the renderer. A profile
describes its own RPM domain, playback rate, continuous layers, optional effects, automation
curves, preview, and presentation parameters. Do not normalize every profile to a guessed common
redline or sample rate just to simplify a UI assumption.

`SampleEngineRenderer` decodes and continuously mixes authored PCM loops. It preserves source
stereo, honours embedded WAV loop metadata where present, keeps phase/cursors continuous, and uses
interpolation for varispeed playback. Assetto Corsa and FMOD are authoring/reference sources only;
the installed Android app uses packaged WAV assets and does not depend on either at runtime.

`EngineAudioEngine` owns one continuous, low-latency stereo `AudioTrack` and audio focus. Fixed
stereo is intentional: on-car testing established this route reaches the full BYD factory speaker
system. Do not reintroduce channel-layout negotiation, channel duplication, or an output selector
without new, reproducible evidence on the target car.

### UI and persistence

Compose renders snapshots rather than touching audio or telemetry directly. Tuning, selected car,
layer mix, effect choices, and per-car volume are user preferences; validate and clamp persisted
data at their boundary. Treat the tuning UI as a simulation/audio authoring surface, not proof of
real vehicle specifications.

## Realtime rules

These constraints exist to keep car audio continuous on a constrained head unit:

- Keep file I/O, asset decoding, collection churn, logging, and blocking synchronization outside
  the render/write loop.
- Reuse buffers and avoid allocations in the audio hot path. Meter data crosses to the UI through
  preallocated primitive storage rather than per-write Compose state.
- Stop/restart renderer resources atomically when a sample profile or renderer configuration
  genuinely requires it. A car switch must release the old renderer and `AudioTrack` before the
  new profile takes ownership.
- Preserve source channels and use each profile's verified source/playback rate. Diagnose a
  car-specific rate or asset problem locally; do not globally degrade unaffected profiles.
- A renderer or asset-loading failure must fail closed (silence), not silently fall back to a
  synthetic engine or an unrelated car's samples.

## Safe change patterns

### Adding or changing a car

Keep the profile declarative. Add only assets for which the user has local playback rights,
register them explicitly in the asset-preparation task, define the profile and its specs, and add
coverage that every required asset decodes and every intended operating region is audible. Build
configuration is the authoritative list of packaged local assets; do not rely on a directory scan.

### Changing audio behavior

Test a sweep across the profile's actual RPM domain and both throttle/lift conditions. Check for
layer gaps, phase resets, rate mismatch, clipping, memory growth, and underruns. Subjective
listening must include the real head unit because emulator output cannot establish BYD DSP routing
or perceived cabin quality.

### Changing vehicle integration or physics

Maintain the read-only boundary and keep observed data separate from estimated calibration. A
speed-derived signal must be smoothed before it controls presentation RPM/audio, especially when
the source reports whole km/h values. Any claim of real-car fidelity needs a cited measurement or
must be labelled as an approximation in code/tests, not hidden in a UI label.

## Verification

The normal local gate is:

```powershell
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon
```

Then install the newest generated debug APK and foreground
`com.gabrielpc.enginesoundsimulator/.MainActivity`. The emulator can validate startup, rendering,
asset packaging, deterministic simulation, and basic audio lifecycle. It cannot validate BYD
permissions, pedal latency, DSP speaker routing, or cabin acoustics.
