# Pitch-stepping handoff

The active native-bank implementation must preserve these rules:

1. BYD raw speed is truncated, so `N` means `[N, N + 1)` km/h.
2. `QuantizedPresentationSpeedEstimator` reconstructs the continuous road-speed constraint used by
   the Audio Lab drivetrain. It must not change the authoritative speed display or vehicle forces.
3. `AssettoDrivetrain` converts that constraint into continuous RPM and drivetrain angular speed.
4. `EngineAudioEngine` transfers the exact physics frame and updates FMOD synchronously at 3 ms.
   There is no second RPM follower and no integer telemetry value may reach an FMOD parameter.
5. Native parameter writes preserve the bank's authored seek speed; never force an immediate seek.

If a listener still reports pitch stepping, capture the presentation RPM passed to
`NativeFmodBankBridge` and test the affected source bank in Audio Lab. First distinguish a smooth
host control trajectory from an authored FMOD event-layer transition. Do not add a decoded-audio
fallback or substitute another car to hide the problem.
