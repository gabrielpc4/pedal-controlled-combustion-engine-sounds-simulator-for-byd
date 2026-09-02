# FMOD pitch path

The FMOD `rpms` parameter is deliberately driven from `EngineAudioFrame.rpm`, which is the
continuous presentation RPM produced by `EngineSimulation`. It is never driven from raw BYD speed.

The path is:

```text
truncated BYD km/h -> QuantizedPresentationSpeedEstimator -> presentation RPM
                 -> AssettoDrivetrain -> native FMOD `rpms`
```

The estimator interprets a report of `N` as `[N, N + 1)`, predicts inside that interval from
boundary timing and pedal direction. The Audio Lab drivetrain consumes that continuous road-speed
constraint at a fixed 3 ms step; raw integer telemetry never reaches its clutch, drivetrain-speed,
RPM, or FMOD parameter path. The audio worker also runs at 3 ms with
`FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE` and a `256 x 4` FMOD DSP buffer. Parameter writes preserve
Studio seek speed rather than forcing immediate jumps.

Use `CarAudioRuntimeValidationTest` to prove direct parameter updates are accepted by the native
bridge. A real-car low-speed listening pass is still required to judge an authored bank's own
layer transitions; no host-side smoothing can eliminate a discontinuity intentionally authored in
the source event.
