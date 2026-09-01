# FMOD pitch path

The FMOD `rpms` parameter is deliberately driven from `EngineAudioFrame.rpm`, which is the
continuous presentation RPM produced by `EngineSimulation`. It is never driven from raw BYD speed.

The path is:

```text
truncated BYD km/h -> QuantizedPresentationSpeedEstimator -> presentation RPM
                 -> FmodControlSmoother -> native FMOD `rpms`
```

The estimator interprets a report of `N` as `[N, N + 1)`, predicts inside that interval from
boundary timing and pedal direction, and keeps raw telemetry authoritative for displayed speed and
shift decisions. The audio worker runs at 4 ms with `FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE` and a
`64 x 4` FMOD DSP buffer. Therefore the native event receives the same continuous RPM target on
each Studio update instead of a synthetic whole-km/h staircase.

Use `CarAudioRuntimeValidationTest` to prove direct parameter updates are accepted by the native
bridge. A real-car low-speed listening pass is still required to judge an authored bank's own
layer transitions; no host-side smoothing can eliminate a discontinuity intentionally authored in
the source event.
