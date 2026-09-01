# Pitch-stepping handoff

The active native-bank implementation must preserve these rules:

1. BYD raw speed is truncated, so `N` means `[N, N + 1)` km/h.
2. `QuantizedPresentationSpeedEstimator` reconstructs only the presentation speed. Never use it
   to change vehicle speed display, gearbox decisions, launch control, or simulated force.
3. `EngineSimulation` converts presentation speed into presentation RPM before the audio handoff.
4. `EngineAudioEngine` passes that RPM through its short continuous follower and updates native
   FMOD synchronously at 4 ms. Never set an FMOD parameter from an integer telemetry value.
5. Direct throttle RPM response, P/N free revving, turbo response, and shift policy remain owned
   by their existing simulation paths.

If a listener still reports pitch stepping, capture the presentation RPM passed to
`NativeFmodBankBridge` and test the affected source bank in Audio Lab. First distinguish a smooth
host control trajectory from an authored FMOD event-layer transition. Do not add a decoded-audio
fallback or substitute another car to hide the problem.
