# WAV engine calibration recipe

This is the reproducible process for calibrating any car profile in the WAV engine. It starts from
the accelerating character: `LOAD` recordings are the default continuous engine family. `COAST`
recordings are an alternative family that can replace LOAD when they sound better for a particular
car; they are not added by default. The Kotlin source remains authoritative if this document and
the implementation diverge.

## Rendering model to preserve

The app continuously mixes packaged WAV loops into one stereo `AudioTrack`. Every continuous loop
is varispeeded from its own root RPM, keeps its playback cursor, and overlaps its neighbours with
amplitude curves. Do not implement a car by selecting the single nearest-RPM WAV: the overlap is
what prevents holes and abrupt character changes during a sweep.

When source material originated in FMOD, retain the source's curve shape and interpolation type in
`CurvePoint` rather than replacing it with linear interpolation. The curve conversion already
lives in `AutomationCurve`; reuse it for each profile.

## Build a new profile

1. Inventory the source recordings. Record each WAV's role, root RPM, useful RPM range, loop
   bounds, base gain, and any alternate effect recording. Keep the source asset manifest in the
   Gradle profile-assets list and the runtime definitions together.
2. Build the initial program from `IDLE`, `LOAD`, `TEXTURE`, and `LIMITER`. Add any available
   COAST recordings to the profile so they can be selected, but do not mix them with LOAD.
3. Add overlapping RPM amplitude curves for every neighbouring band within each family. Fade the
   next layer in before the preceding layer fades out. Root RPM controls pitch; it is not the same
   as the band boundary.
4. Make LOAD the default primary source. If the car's COAST recordings better preserve its engine
   character, select COAST as the primary source instead; this replaces LOAD rather than layering
   both families together.
5. Add the limiter as its own layer and fade it in only at the redline. Add textures only after
   the selected primary sweep is coherent.
6. Add turbo, transmission, shift, flutter/dump, and exhaust-overrun recordings as effects. They
   are independent of continuous engine loops and need their own RPM, throttle, and decay rules.

Use `bandProfile` for ordinary cars with conventional RPM bands. Use a dedicated profile function
when the recording set has authored overlaps, multiple accents, nonstandard curves, or source
automation that the generic builder cannot describe.

## Initial mixer layout

The mixer exposes the continuous families separately:

1. Column one: `IDLE` followed by `COAST` layers.
2. Column two: `LOAD` layers only.
3. Column three: limiter and other non-LOAD effects first, then textures.

The current `groupMixerTracks` implementation already enforces this layout. A car with COAST
layers also gets an `ENGINE SOURCE` selector in the mixer header. `LOAD` is the default. Selecting
`COAST` swaps the continuous source family, reloads only that family, and preserves the vehicle
simulation and effect rules. It does not crossfade or add both continuous families together.

## Tuning order

Tune in this order, keeping the previous step stable while evaluating the next one:

1. Idle: stable at the car's idle RPM and fully faded before its first primary band dominates.
2. LOAD sweep: slow, continuous acceleration from idle to redline; no sudden timbre or level
   steps at hand-offs.
3. COAST source sweep: switch to COAST and repeat the same acceleration sweep. Compare it with
   LOAD; choose the family that better preserves the car's character.
4. Release behavior: compare a quick lift, gradual lift, and repeated throttle modulation with
   the selected family. Do not add the other family unless a car-specific mix explicitly calls for
   it.
5. Limiter, turbo, shift, and overrun: add one family at a time after the continuous sweep is
   correct. Their activity must not introduce clicks, duplicate loops, or audible pitch stepping.
6. Final subjective balance: only after the above passes may a car intentionally omit a COAST loop
   or use a non-generic load-only override. Record the listening reason next to that profile.

## Required profile record

For each new car, create a short calibration note containing:

| Item | Record |
| --- | --- |
| Output rate and channel layout | Native WAV rate and stereo/mono policy |
| Engine constants | Idle, redline, limiter, shift RPMs, gear ratios, shift durations |
| Continuous layers | Asset, role, RPM range, root RPM, base gain, throttle curve, RPM curves |
| Effects | Trigger, asset, alternates, loop bounds, minimum RPM, base gain |
| Source decision | Whether LOAD or COAST is primary, with the listening reason |
| Validation | Device, input mode, acceleration/lift cases heard, and automated tests run |

## Validation

Run `:mobile:testDebugUnitTest` after changing profile logic. Build and install the debug APK,
then listen on the target Android route with simulated pedals or controlled vehicle telemetry.
Check layer meters during acceleration and release: a visible meter proves the renderer is mixing
that layer, but the final decision remains a listening test.

Confirm the renderer owns one active `AudioTrack`. If Android reports that it cannot create an
`AudioTrack`, inspect the system audio service before changing a car profile: that is an output
route/resource problem, not a LOAD/COAST calibration problem.

## Skyline example

The Skyline R34 was calibrated using this process from its recovered interior engine topology and
effect set. Its approved setting remains LOAD; its COAST loops were removed from the profile after
listening because the retained interior LOAD blend better preserves the acceleration character
during deceleration. Its exact current values remain in
`AdditionalCarProfiles.kt`; the relevant commits are `be16e04`, `fa2b392`, and `aa9064b`.
