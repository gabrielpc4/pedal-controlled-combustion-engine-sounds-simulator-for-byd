# Vehicle integration and local assets

## Evidence and scope

This app targets the BYD Seal DiLink environment observed on system version
`13.1.33.2503250.1`. It is an independent, private experiment, not a BYD application or a
vehicle-control system.

The relevant BYD API family is the vendor class commonly exposed as
`android.hardware.bydauto.speed.BYDAutoSpeedDevice`. Its historical documentation describes
accelerator depth, brake depth, and vehicle speed getters, with values intended as percentages for
pedals and km/h for speed. The project probes it reflectively so the application can still build
without bundling the proprietary SDK.

The official Chinese API manual is retained locally, outside Git, at
`reference/BYD_DiLink_API_V1.0.5.pdf` when available. It is old vendor documentation: useful for
names and intended semantics, but not proof of current Seal availability, permission grants, or
update rate. The currently shipped firmware and the running device are authoritative.

## Permission reality

The target car exposed the vendor classes but rejected a normal application call with a
`BYDAUTO_SPEED_GET` `SecurityException`. The permission is treated as signature-protected unless
the exact device proves otherwise.

The app contains a deliberately narrow `BydReadOnlyPermissionContext` because public community
research found that some generations of BYD's client wrapper check the supplied `Context` before a
remote service call. It recognizes only the speed read permissions needed by this application. It
does **not** grant Android permissions, alter PackageManager state, grant setter permissions, or
bypass a server-side Binder permission check.

Therefore live telemetry is a runtime capability, never a deployment assumption:

- If valid pedal/speed values are available, the app can use them.
- `AUTO` can fall back to the simulator inputs.
- Explicit live mode must remain safe when telemetry is absent, denied, stale, or out of range.
- Do not use root, `pm grant`, package impersonation, raw CAN writes, or reflection to call
  undocumented setters to make this work.

Vehicle mode/gear APIs may differ between firmware generations. Treat their current mapping as a
best-effort read-only enhancement and preserve a usable manual transmission-position control.

## On-car test discipline

Test parked or with a passenger in a controlled environment. Before trusting a new firmware,
record only non-sensitive observations:

1. Whether the vendor classes and permission definitions exist.
2. Whether getters return plausible changing pedal and speed values.
3. Update cadence, integer quantization, and stale-value behavior.
4. Whether live source selection, speed smoothing, and audio focus recover cleanly.
5. Whether fixed stereo reaches the intended factory speaker system without audible skips.

Do not store identifiers or full driving traces in the app. A short, redacted manual test note is
more useful than a permanent private log.

## Sample asset contract

The app uses local WAV samples and preview images supplied outside the repository, primarily in
`audio_samples/`. They are intentionally ignored because their redistribution rights have not been
established. The Gradle asset-preparation task copies only an explicit allow-list into the APK;
this is both a reproducibility rule and a licensing guardrail.

The current implementation is sample-dependent. There is no procedural-synth fallback, and the
app does not require Assetto Corsa, FMOD, or a game installation at runtime. Bank extraction and
sample interpretation are authoring/research steps only.

When using third-party recordings:

- Confirm the user has the right to use the material in this private installation.
- Keep raw banks, decoded WAVs, game files, reference APKs, and previews out of Git.
- Preserve source rates, channel layout, loop metadata, and the profile's authored RPM domain.
- Prefer exterior idle only when that sample is explicitly mapped by the profile; do not infer a
  full exterior event from a single exterior loop.

## Audio route decision

The application always renders true stereo PCM. Real-car testing established that stereo is the
Android route BYD's DSP distributes across the complete factory system. The application must not
attempt to make its own surround layout, duplicate stereo into extra channels, or expose an output
format toggle unless new on-car evidence overturns that decision.

Speaker distribution after the stereo stream leaves the app belongs to the vehicle's audio system,
not this app. The implementation should preserve the recording's left/right channels and focus on
clean, continuous stereo delivery.

## Research provenance

The most durable external sources are the BYD Open Platform/API material, Android permission
documentation, observed on-car behavior, and public reverse-engineering projects. Community
material is a lead, not authority. If a future implementation depends on an external fact, cite a
primary source or label the assumption beside the code/test that uses it.
