# Downshift AutoBlip investigation

This note records the empirical investigation that led to the current AutoBlip comparison
decision. It is intentionally kept with the source so a future implementation can distinguish
an authored FMOD behavior from a host-side RPM error.

## Test setup

- Car: Audi R8 LMS 2016.
- Transmission: Drive (`D`).
- Input sequence: approximately 60% throttle until fourth gear, release, then approximately
  80% brake until the 4th-to-3rd downshift and its clutch re-engagement were observable.
- Sampling: normal drivetrain frames plus a 3 ms diagnostic trace during shifts and for the
  post-shift clutch tail.
- Builds: 164 with the authored AutoBlip contribution, and 165 with only that contribution
  disabled. The raw traces were captured outside the repository while testing.

## What the logs showed

The 4th-to-3rd shift began near 4,488 RPM, 53 km/h, and an internal FMOD drivetrain speed of
about 121.3 km/h. The bank's clutch profile opened the clutch almost immediately, held it open
for about 160 ms, and re-engaged by about 250 ms. Its authored AutoBlip profile raised throttle
to about 0.5 for roughly 50 ms and decayed to zero by about 105 ms.

With AutoBlip enabled, the trace showed three audible phases: AutoBlip raised the engine speed
while the clutch was open, the AutoBlip decayed and RPM fell, and clutch re-engagement then
produced a second rise. The shift completed around 5,020 RPM and the following clutch coupling
peaked near 5,300 RPM.

With AutoBlip disabled, the first rise disappeared. At shift completion the engine was around
4,024 RPM with zero throttle contribution from AutoBlip. The authored clutch re-engagement still
raised it to about 5,257 RPM before it fell continuously. This proves that disabling AutoBlip
removes the double pulse, but does not remove the single rev-match rise caused by the bank's
clutch/drivetrain coupling.

## Conclusions and current decision

1. The earlier artificial `fmodDrivetrainSpeed` jump was not present in this test; the internal
   speed continued falling through the shift.
2. The strongest perceived oscillation was the combination of the bank's AutoBlip envelope and
   the bank's clutch re-engagement envelope, not a raw-speed quantization reset.
3. AutoBlip is now disabled through the comparison switch in `AssettoDrivetrain`. This is an
   intentional diagnostic decision because it removed the larger two-stage oscillation while
   preserving the rest of the authored drivetrain behavior.
4. The clutch profile has not been rewritten. Changing it would make the simulator diverge from
   the bank's natural FMOD intent and therefore requires a separate, explicit decision.

Detailed traces remain available in debug builds until the downshift behavior is considered
resolved. Release builds disable that high-rate trace through the centralized runtime flags.
