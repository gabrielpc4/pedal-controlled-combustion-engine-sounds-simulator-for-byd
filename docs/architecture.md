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

## Authored FMOD and explicit app policy

FMOD 2.03.14 owns each bank's event graphs, source material, automation, randomisation, effects,
and authored gains. The bridge passes physical RPM, drivetrain speed, boost, BOV, shift, limiter,
and lifecycle data without rewriting the authored graph. It also has a small, intentional app
policy layer which must not be mistaken for bank authoring:

- engine and transmission events receive authored throttle at the full-load endpoint (`1.0`), so
  pedal position controls the drivetrain rather than attenuating or swapping those load layers;
- backfire also receives its authored full-load endpoint (`1.0`);
- the diagnostic host defaults are engine gain `1.0` and effects gain `2.0`; per-car mixer trims
  multiply only transmission, gear-shift, and turbo event families;
- traction limiting remains in the drivetrain, but traction-control sounds are deliberately
  stopped; and
- tires, wind, chassis, and doors are excluded from playback. Wind and tyres remain discoverable
  so the inventory can label them as intentionally excluded rather than missing.

Engine, transmission, turbo, limiter, shift, gear-grind, backfire, and authored start events are
used only when present. The generated original-car inventory labels every behavior as bank
authoring, app policy, runtime observation, or unresolved evidence.

Cabin and exterior switch the authored interior/exterior engine event and listener. The switch is
performed inside the active FMOD system without a host-side crossfade or restart. The drivetrain
and FMOD control/update worker share a persisted global cadence (100 Hz by default, 30..330 Hz in
10 Hz steps, or 333 Hz with MAX). This changes control/simulation timing only; FMOD's native
mixer and DSP sample rate remain unchanged.

## Mixer

The mixer is diagnostics with temporary event-level mute/solo and host-gain controls. Native
sound-start/stop callbacks and Core channel ownership associate each active voice with its exact
event path and raw sound name. Cards report state, voice count, FMOD audibility, and route gain;
identical event/source pairs may be aggregated, but sources from different event paths never share a
card. The same card-level mute/solo applies to every raw sample that FMOD rotates through that
event/source identity, rather than only to whichever sample name is visible at one instant. Voice
polling runs on a background worker only while Mixer is visible; it is not part of the FMOD control
loop and is not performed on other screens.

### Debug voice trace

`RuntimeFeatureFlags.ENABLE_FMOD_VOICE_TELEMETRY` enables a debug-only native trace tagged
`FmodBankRuntime`. It records monotonic timestamps, event/path, raw source, a runtime-stable voice
serial, `VOICE_PLAYED`/`VOICE_STOPPED`, audibility, virtual voices, callback voice counts, and the
RPM/gear/boost/BOV/shift context. The flag is derived from `BuildConfig.DEBUG`, so release APKs do
not format or emit this high-rate diagnostic stream. A source card is intentionally keyed by
`event path + raw sound name`: it may contain multiple simultaneous Core voices and remains a
separate source from an identically named sound owned by another event. If an event's playlist or
parameter selects a different raw sample, the event-level control remains attached to the owning
event while the source cards remain diagnostic observations of each raw name.

## Packaging and failure behavior

The installer publishes `byd-fmod-bank-pack-v3` packages under the selected original or modded
group. Common Assetto banks load before the selected car bank. Missing or invalid packages fail
closed with an install message; a modified or old pack is never silently substituted. Original and
modded packages are independently installable, while the current audit scope remains the 23
official profiles.
