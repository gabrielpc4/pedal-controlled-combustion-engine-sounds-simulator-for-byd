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
`AssettoDrivetrain` remains the engine and transmission model for real pedals, Park/Neutral free
revving, and manual shifting.

Automatic D-mode shifting intentionally has one presentation policy layered over the authored
bank. After an upshift, the RPM at which the next gear landed is remembered and becomes that
gear's automatic downshift threshold. This keeps the gear until it has fallen below its own
landing point instead of using the bank's generic `auto_down_rpm`. The historical main-branch
exceptions remain only for 1→2 at partial throttle (6,400 RPM) and 2→1 (4,000 RPM); all other
upshifts, clutch profiles, shift times, and over-rev protection still come from the bank.

SIMULATED PEDALS intentionally uses a BYD Seal AWD road-speed model. Its full-throttle curve is
digitized from the supplied Seal trace and reaches 100 km/h in approximately 3.97 seconds; partial
throttle scales that acceleration linearly. It coasts with passive speed-dependent resistance,
uses a 190 km/h cap, and stops immediately in Park. For every selected bank, an independent
`EqualSpeedGearMapping` derives an internal `fmodDrivetrainSpeed` so each bank's authored upshift
RPM lands at the end of every intermediate equal 0–190 km/h band, while the authored limiter lands
at 190 km/h in the final gear. This mapping is not a speed-triggered shift table: automatic
upshifts/downshifts still compare the live FMOD RPM with each bank's authored RPM thresholds and
use its authored durations. P/N bypasses the mapping and remains a free-rev path using engine
inertia alone.

The input resolver normalizes both sources to the same 0..1 throttle/brake signals before they
reach the drivetrain. REAL PEDALS is selected only when a single telemetry poll contains a valid
speed, accelerator, and brake value; a partial or unavailable poll falls back to SIMULATED rather
than allowing the Assetto model to invent a second speed source. Switching from REAL to SIMULATED
seeds the Seal model from the last continuous presentation speed, so changing controls cannot
teleport the virtual car back to zero. These are source-boundary safeguards, not extra vehicle
forces: after selection, both modes share the same drivetrain, gear, clutch, turbo, and FMOD frame
path.

BYD reports are treated as truncated `[N, N + 1)` km/h values. `QuantizedPresentationSpeedEstimator`
uses only boundary timing and bounded pedal direction to produce a continuous presentation speed.
Raw truncated speed remains authoritative for the physical vehicle path and display. The continuous
value is converted into `fmodDrivetrainSpeed` for the engine/RPM/audio path so FMOD never receives a
synthetic stepped wave. SIMULATED PEDALS integrates a fractional Seal-model speed internally, then
truncates its public/raw representation while using the same presentation reconstruction policy as
REAL.

## Authored FMOD

FMOD 2.03.14 owns event graphs, source material, automation, randomisation, effects, and gains.
The native bridge sends only the physical parameters and lifecycle transitions required by the
bank. It has no per-car gain, LOAD/COAST mode, forced-load, drag, regen, or event-suppression
layer. The mixer can apply temporary event mute/solo and host engine/effects gains strictly for
interactive diagnosis; those controls reset outside the current session.
Engine, transmission, turbo, limiter, shift, gear-grind, backfire, traction-control, and authored
start events are used only when present. Tires, wind, chassis, and doors remain excluded.

Cabin and exterior switch the authored interior/exterior engine event and listener. The switch is
performed inside the active FMOD system without a host-side crossfade or restart.

## Mixer

The mixer is diagnostics with temporary event-level mute/solo and host-gain controls. Native
sound-start/stop callbacks and Core channel ownership associate each active voice with its exact
event path and raw sound name. Cards report state, voice count, FMOD audibility, and route gain;
identical event/source pairs may be aggregated. UI polling is separate from the 3 ms audio-control
loop, so no mixer allocations occur on the realtime path.

## Packaging and failure behavior

The installer publishes `byd-fmod-bank-pack-v3` packages under
`fmod-banks/original_cars_pack/<id>`. Common Assetto banks load before the selected car bank.
Missing or invalid packages fail closed with an install message; a modified or old pack is never
silently substituted. The generated modded group remains inactive until a later release explicitly
enables it.
