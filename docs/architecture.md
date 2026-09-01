# Architecture

## Purpose

The application adds a presentation-only combustion-engine layer to a BYD road-speed input. It
owns the tachometer, presentation gears, and audio only; it never changes vehicle control.

```text
BYD getters or simulated pedals
             |
       DriveController
             |
     EngineSimulation  ---> Compose dashboard snapshot
             |
       EngineAudioFrame (continuous presentation RPM)
             |
 EngineAudioEngine -> native FMOD Studio -> authored bank events -> Android audio route
```

Start from `DriveController`, `EngineSimulation`, `EngineAudioEngine`, and `FmodBankProfile`.

## Input and presentation simulation

`DriveController` owns lifecycle, input selection, selected-car state, persistence, and the
handoff to the audio worker. `EngineSimulation` owns virtual road speed, pedal smoothing,
transmission position, and presentation RPM. In Drive, `LoadedEngineDynamics` adds bounded
combustion-style crank response without influencing the electric vehicle's movement. Park and
Neutral use `FreeRevEngineDynamics` instead.

The BYD speed value is treated as truncated: a report of `N` km/h represents `[N, N + 1)`.
`QuantizedPresentationSpeedEstimator` reconstructs a continuous presentation-only speed from
boundary timing and bounded pedal direction. Gearbox decisions, speed display, launch control,
and virtual vehicle forces retain the authoritative raw-speed route. FMOD receives presentation
RPM only, never a whole-km/h input, so a telemetry boundary cannot directly become an authored
parameter step.

## FMOD-bank audio

`FmodBankProfile` contains the display and presentation-drive data for one installable FMOD bank.
The bank itself remains authoritative for its stereo source material, event graph, randomisation,
effects, and parameter automation. `FmodBankStore` verifies a package before atomically publishing
the `.bank` and optional `GUIDs.txt` under the dashboard's private storage. The original Assetto
`common.strings.bank` and `common.bank` are installed once as FMOD dependencies and loaded before
the selected car bank, so official source banks without a local event-name table retain their
authored event routes.

`EngineAudioEngine` has one 4 ms, audio-priority control worker. It opens the selected bank through
`NativeFmodBankBridge`, then advances its `rpms`, `throttle`, `drivetrain_speed`, `boost`, `bov`,
and `bov_decay` controls. Its short smoother receives the already-continuous presentation RPM and
is the only extra host-side filter. Native FMOD events used are:

- `engine_int` or `engine_ext` for the continuous engine, selected per listener perspective.
- `transmission`, `transmission_ext`, `turbo`, and `limiter` when the source bank provides them.
- `gear_int`, `gear_ext`, `backfire_int`, `backfire_ext`, and `start` when triggered by the current
  drive state.

Tires, wind, chassis, doors, traction, and gear-grind events are intentionally never discovered or
started. There is no PCM decoding, synthetic engine, unrelated-car fallback, or alternative audio
renderer.

## Native build boundary

FMOD 1.10.11 is supplied outside the repository through `fmod.sdk.dir`. Gradle copies headers and
native libraries into generated build staging. `tools/repair_fmod_dynsym.py` fixes only the copied
legacy ELF marker ordering needed for modern NDK linking; it never changes the downloaded SDK.
`fmod_bank_bridge.cpp` contains the direct Studio calls and compatible registrations for the Assetto
`FMOD Distance Filter` and `FMOD Gain` plugins.

## Realtime and failure rules

- Bank archive I/O, checksum verification, and car switching stay outside the control loop.
- A car, perspective, or LOAD/COAST/BOTH switch replaces the old Studio system atomically.
- A missing bank or FMOD load failure fails closed and reports the selected car; it never plays a
  different car.
- Compose only reads snapshots and estimated event-group levels. It does not manipulate FMOD.
- On-device listening is required for BYD DSP routing and perceived realism; the emulator validates
  lifecycle and event loading, not cabin acoustics.

## Verification

Run unit tests, build both APKs, use the installer, then run the instrumentation sweep. The sweep
opens every installed profile in both perspectives and drives fast continuous RPM/throttle updates
through the same JNI bridge used by the dashboard. Its `CarAudioRuntimeValidation` log lines list
the allowed events that activated for each source bank.
