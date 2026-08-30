# Android FMOD pitch trace

> Historical FMOD trace. The active WAV migration does not include FMOD; its committed experiment
> remains available on `gpc/fmod-pitch-attempt`.

Captured on `BYD_Seal_1920x1080` with the Skyline R34 bank during the same deterministic
simulated-pedal launch. The temporary probe sampled the requested simulation RPM, the interpolated
float passed through JNI, and both values returned by
`FMOD_Studio_EventInstance_GetParameterValue` immediately after `Studio_System_Update`.

| Device Studio mode | Control samples | Sent value accepted exactly | Final/evaluated value exact | Repeated final values | Worker p50 / p95 / max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Asynchronous, requested 5 ms | 2,800 | 2,800 | 5 | 2,564 | 2.512 / 4.062 / 5.637 ms |
| Synchronous on 400 Hz owner | 2,797 | 2,797 | 2,797 | 1 (steady initial RPM) | 2.538 / 3.967 / 5.351 ms |

The asynchronous final value alternated between roughly 15 ms and 50–55 ms plateaus. One early
excerpt shows the application sending `823.149, 825.029, 830.933, 835.479, 845.256, 846.943,
851.047` RPM while FMOD held its evaluated value at `823.149` RPM, then jumped directly to
`851.047` RPM. At the launch-control catch, the largest evaluated jump was about 2,901 RPM.

With `FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE`, every evaluated RPM equaled the sent float and control
worker timing did not regress. The temporary hot-path probe was removed after this A/B capture; the
native integration test retains the synchronous-mode diagnostics contract.

## Rendered-output follow-up

A subsequent host-loopback capture recorded the emulator's actual 48 kHz stereo output during an
isolated ten-second linear `1,000 -> 5,000 RPM` sweep with only `engine_int` enabled. This exposed a
separate issue before JNI: the first-order 7.5 ms follower produced fractional values on every
400 Hz tick, but its pitch velocity changed whenever a new 200 Hz target arrived. In the regression
ramp, the adjacent per-tick velocity change was about 3.303 RPM/tick even though position never
plateaued.

The pitch routes now use a 20 ms critically damped second-order follower. It keeps velocity
continuous across source targets and reduces the same steady-ramp velocity change to about
0.026 RPM/tick. Throttle retains its asymmetric first-order follower because its purpose is an
amplitude crossfade, not pitch motion. The filter remains audio-only and does not alter vehicle
speed, tach RPM, shift decisions, or EV torque.
