# Architecture

## Purpose

The application adds a presentation-only combustion-engine simulation to a BYD road-speed input.
It owns the tachometer, presentation gears, and audio only; it never controls the vehicle.

```text
BYD getters or simulated pedals
             |
       DriveController
             |
  EngineSimulation / AssettoDrivetrain ---> Compose dashboard snapshot
             |
       EngineAudioFrame
             |
 EngineAudioEngine -> native FMOD Studio -> authored bank events -> Android audio route
```

Start from `DriveController`, `EngineSimulation`, `AssettoDrivetrain`, `EngineAudioEngine`, and
`FmodBankProfile`.

## Physics and presentation speed

`DriveController` owns lifecycle, input selection, selected-car state, persistence, and two fixed
3 ms workers. `EngineSimulation` delegates running-engine behavior to `AssettoDrivetrain`, a Kotlin
port of the Audio Lab's vehicle, engine, clutch, automatic gearbox, turbo, limiter, backfire, and
traction-control ordering. Every car loads its own immutable `physics.json`; there is no unrelated
reference-car fallback. Simulated pedals run the complete longitudinal model, with user DRAG and
REGEN controls applied as additional forces. Park and Neutral remain mechanically disconnected and
use the same free-rev behavior represented by the Lab trace.

The BYD speed is treated as truncated: a report of `N` km/h represents `[N, N + 1)`.
`QuantizedPresentationSpeedEstimator` reconstructs continuous motion from boundary timing and
bounded pedal direction. The speed display remains authoritative. With real pedals, reconstructed
speed is the road-speed constraint supplied to the Assetto drivetrain, so integer telemetry never
reaches FMOD. With simulated pedals, the Assetto model owns speed and the dashboard still exposes a
deliberately truncated RAW value for BYD-like testing.

## FMOD-bank audio

`FmodBankProfile` contains display metadata for one installable FMOD bank. The bank remains
authoritative for stereo source material, event graphs, randomisation, effects, and parameter
automation. `FmodBankStore` verifies a package before atomically publishing its bank, optional
`GUIDs.txt`, and physics metadata under private app storage. Original Assetto
`common.strings.bank` and `common.bank` dependencies load before the car bank.

Each `byd-fmod-bank-pack-v2` carries profile-specific Assetto physics metadata. Old packs are
rejected instead of migrated. The Audio Lab consumes the same metadata.

`EngineAudioEngine` has one 3 ms audio-priority control worker. It transfers the latest exact
physics frame without a second host-side RPM smoother. The native bridge preserves authored FMOD
parameter seek speed and runs a 48 kHz stereo mixer with a `256 x 4` DSP buffer. Its behavior
matches the Lab:

- one active `engine_int` or `engine_ext`, with authored throttle fixed at full load;
- `transmission`/`transmission_ext` driven by actual drivetrain angular speed;
- independent `boost`, `bov`, and `bov_decay` turbo parameters;
- authored limiter, accepted shift, gear grind, backfire, traction-control, and start lifecycles;
- engine event gain `0.5` and effect gain `1.0` before the per-car master control;
- cockpit or bonnet listener position and the car's authored engine/backfire emitter position.

Tires, wind, chassis, and doors are excluded. Shift override OFF prevents gear and gear-grind event
starts; pops-and-bangs OFF prevents backfire. There is no decoded-PCM renderer, LOAD/COAST host
model, synthetic engine, unrelated-car fallback, or alternative renderer.

## Exact FMOD mixer

The mixer is a diagnostic view of the live hierarchy, not a list of guessed effects. Studio
sound-start/stop callbacks identify each raw source name and the Core channel hierarchy identifies
its owning `EventInstance`. A native snapshot aggregates only identical `event path + sound name`
pairs and reports active/recent/virtual state, simultaneous voice count, Core audibility, and route
gain. Completed one-shots remain visible for 1.5 seconds.

Per-source gain, mute, and solo multiply the current authored channel gain. They never replace
Studio automation. Controls persist by car, perspective, event path, and raw source name. Compose
polls immutable snapshots every 50 ms; no mixer allocation occurs on the 3 ms audio-control path.
Cards are grouped semantically and flow across the available width, so a recording genuinely
authored inside `engine_int` remains honestly attributed to that engine event.

## Native build boundary

FMOD 2.03.14 is supplied outside the repository through `fmod.sdk.dir`. Gradle copies its Core and
Studio headers and native libraries into generated build staging. The Android bridge registers
compatible implementations of the Assetto `FMOD Distance Filter` and `FMOD Gain` plugins.

## Realtime and failure rules

- Bank archive I/O, checksum verification, and car switching stay outside the control loop.
- A car switch replaces the Studio system atomically. Perspective switching changes the active
  authored event and listener inside the existing system.
- A missing bank or FMOD load failure fails closed and reports the selected car; it never plays a
  different car.
- Compose reads immutable real-voice snapshots and publishes user source controls.
- On-device listening is required for BYD DSP routing and perceived realism; the emulator validates
  lifecycle and event loading, not cabin acoustics.

## Verification

Run unit tests, build both APKs, use the installer, then run the instrumentation suite. A golden
trace compares Kotlin against the Audio Lab frame by frame. The native sweep opens all 58 profiles
in both perspectives, keeps shift/backfire overrides disabled, drives continuous trajectories, and
checks event ownership and meter semantics through the same JNI bridge used by the dashboard.
