# FMOD pitch path

The FMOD `rpms` parameter is deliberately driven from `EngineAudioFrame.rpm`, which is the
continuous presentation RPM produced by `EngineSimulation`. It is never driven from raw BYD speed.

The path is:

```text
truncated BYD km/h -> QuantizedPresentationSpeedEstimator -> presentation RPM
                 -> AssettoDrivetrain -> native FMOD `rpms`
```

The estimator interprets a report of `N` as `[N, N + 1)`, predicts inside that interval from
boundary timing and pedal direction. The drivetrain and FMOD control worker share one cadence,
defaulting to 100 Hz and selectable in Settings from 30 Hz through 330 Hz, or 333 Hz with MAX.
Raw integer telemetry never reaches its clutch, drivetrain-speed, RPM, or FMOD parameter path.
This cadence controls simulation and parameter transfer; FMOD still renders through its configured
`256 x 4` DSP buffer. Parameter writes preserve Studio seek speed rather than forcing immediate
jumps.

Use `CarAudioRuntimeValidationTest` to prove direct parameter updates are accepted by the native
bridge. A real-car low-speed listening pass is still required to judge an authored bank's own
layer transitions; no host-side smoothing can eliminate a discontinuity intentionally authored in
the source event.
