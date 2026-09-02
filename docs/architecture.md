# Architecture

## Runtime flow

```text
BYD telemetry or simulated pedals
            |
      DriveController
            |
 EngineSimulation -> AssettoDrivetrain -> dashboard snapshot
            |
      EngineAudioFrame
            |
 EngineAudioEngine -> native FMOD Studio -> authored bank events
```

`DriveController` owns lifecycle, input selection, car selection, and the small set of persistent
choices that remain: selected car, cabin/exterior perspective, and manual/automatic mode.
`EngineSimulation` is an adapter around the per-car `AssettoDrivetrain` model exported from the
original installation. FMOD is started directly at the car's authored idle state when the bank is
loaded. Activity lifecycle stop is only resource cleanup; there is no application start/stop
button, ignition state, ramp, fade, or synthetic shutdown.

## Physics and speed

Each official profile carries its own original physics metadata: idle and limiter RPM, shift-light
and automatic shift RPM, ratios, final drive, wheel radius, clutch, turbo, and authored timing.
`AssettoDrivetrain` is the sole engine and transmission model. It is also used for Park/Neutral
free revving and manual shifting. The app does not add a 190 km/h limit, equal gear intervals,
generic RPM maximum, custom hysteresis/cooldown, launch controller, throttle-RPM boost, virtual
drag, or virtual regenerative braking.

BYD reports are treated as truncated `[N, N + 1)` km/h values. `QuantizedPresentationSpeedEstimator`
uses only boundary timing and bounded pedal direction to produce a continuous presentation speed.
Raw truncated speed remains authoritative for display and drivetrain/shift/load decisions; the
presentation value is used only for road-coupled tachometer and pitch so FMOD never receives a
synthetic stepped wave.

## Authored FMOD

FMOD 2.03.14 owns event graphs, source material, automation, randomisation, effects, and gains.
The native bridge sends only the physical parameters and lifecycle transitions required by the
bank. It does not write event, bus, channel-group, or source volume, and it has no app gain,
per-car gain, LOAD/COAST mode, forced-load, mute, solo, drag, regen, or event-suppression layer.
Engine, transmission, turbo, limiter, shift, gear-grind, backfire, traction-control, and authored
start events are used only when present. Tires, wind, chassis, and doors remain excluded.

Cabin and exterior switch the authored interior/exterior engine event and listener. The switch is
performed inside the active FMOD system without a host-side crossfade or restart.

## Mixer

The mixer is read-only diagnostics. Native sound-start/stop callbacks and Core channel ownership
associate each active voice with its exact event path and raw sound name. Cards report state,
voice count, FMOD audibility, and route gain; identical event/source pairs may be aggregated. UI
polling is separate from the 3 ms audio-control loop, so no mixer allocations or gain writes occur
on the realtime path.

## Packaging and failure behavior

The installer publishes `byd-fmod-bank-pack-v3` packages under
`fmod-banks/original_cars_pack/<id>`. Common Assetto banks load before the selected car bank.
Missing or invalid packages fail closed with an install message; a modified or old pack is never
silently substituted. The generated modded group remains inactive until a later release explicitly
enables it.
