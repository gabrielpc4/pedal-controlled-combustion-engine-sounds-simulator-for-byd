# Backfire debug scenario

The debug APK includes an opt-in ADB scenario for hearing and measuring the Alfa Romeo 4C
backfire event. It is not present in release builds and does not change normal driving.

```sh
adb -s emulator-5554 shell am broadcast \
  -a com.gabrielpc.enginesoundsimulator.action.DEBUG_TELEMETRY \
  --es command BACKFIRE_ONLY --es profile alfa-romeo-4c
```

The scenario repeatedly holds full throttle in P to arm the authored backfire logic, then closes
the throttle long enough for a one-shot to fire and rearm. Start/stop callbacks, voice duration,
RPM, throttle, and backfire-trigger frames are captured by the existing debug telemetry ring.
`BACKFIRE_ONLY` additionally mutes every other FMOD event, including engine and limiter, and
protects the backfire event from existing mixer mute/solo selections, so it is suitable for
listening to the effect in isolation.

Export the capture after listening:

```sh
adb -s emulator-5554 shell am broadcast \
  -a com.gabrielpc.enginesoundsimulator.action.DEBUG_TELEMETRY \
  --es command STOP
```

The previous validation produced four Alfa `backfire_int` starts and four matching voice plays;
three measured one-shots lasted approximately 0.399 s, 0.474 s, and 0.913 s. The first callback
did not expose a joinable sample duration, but it also stopped normally. This confirms triggering
and rearming; it does not replace a full cabin/exterior driving sweep.
