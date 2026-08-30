# Vehicle integration and local assets

## Evidence and scope

This app targets the BYD Seal DiLink environment observed on system version
`13.1.33.2503250.1`. It is an independent, private experiment, not a BYD application or a
vehicle-control system.

The relevant BYD API family is the vendor class commonly exposed as
`android.hardware.bydauto.speed.BYDAutoSpeedDevice`. Historical documentation describes
accelerator depth, brake depth, and vehicle speed getters, with pedal percentages and speed in
km/h. The project probes it reflectively so the application can build without bundling the
proprietary SDK.

The official Chinese API manual may be retained locally outside Git at
`reference/BYD_DiLink_API_V1.0.5.pdf`. It is useful for names and intended semantics, but it is not
proof of availability, permission grants, or update rate on the current Seal firmware.

## Permission reality

The target car exposed vendor classes but rejected a normal application call with a
`BYDAUTO_SPEED_GET` `SecurityException`. Treat that permission as signature-protected unless the
exact device proves otherwise.

`BydReadOnlyPermissionContext` is deliberately narrow. It recognizes only the speed-read
permissions needed by this app for compatibility with vendor wrappers that inspect the supplied
`Context`. It does not grant Android permissions, alter PackageManager state, grant setters, or
bypass a server-side Binder permission check.

Live telemetry is therefore a runtime capability, not a deployment assumption:

- Valid pedal/speed values may drive the same simulation used by on-screen pedals.
- `AUTO` may fall back to simulator inputs.
- Explicit live mode remains safe when telemetry is absent, denied, stale, or out of range.
- Do not use root, `pm grant`, package impersonation, CAN writes, or undocumented setters.

Vehicle mode/gear APIs may differ between firmware generations. Keep their mapping best-effort and
read-only, and preserve usable manual transmission-position controls.

## Local FMOD and Assetto inputs

The repository intentionally contains neither the FMOD SDK nor redistributable car/audio content.
Configure the user's local official copies in ignored root `local.properties`:

```properties
assettoCorsa.dir=D:/path/to/Steam/steamapps/common/assettocorsa
fmod.sdk.dir=C:/path/to/fmodstudioapi11011android
```

`assettoCorsa.dir` is read-only input and must contain the official common and Kunos car banks:

- `content/sfx/common.strings.bank`
- `content/sfx/common.bank`
- `content/cars/ks_nissan_skyline_r34/sfx/ks_nissan_skyline_r34.bank`
- `content/cars/ks_nissan_skyline_r34/skins/00_bayside_blue/preview.jpg`
- `content/cars/ks_alfa_romeo_4c/sfx/ks_alfa_romeo_4c.bank`
- `content/cars/ks_alfa_romeo_4c/skins/0_rosso_alfa/preview.jpg`

Three additional user-supplied car folders are read-only input under the repository's ignored
`audio_samples/` directory:

- `audio_samples/fx_lamborghini_huracan_trofeo_evo2/sfx/fx_lamborghini_huracan_trofeo_evo2.bank`
- `audio_samples/tr_lamborghini_aventador_sv/sfx/tr_lamborghini_aventador_sv.bank`
- `audio_samples/Toyota_Supra_MK4/sfx/zesty_toyota_supra_mk4_shuto_street.bank`

`fmod.sdk.dir` must be the extracted official **FMOD Studio API 1.10.11 for Android** root with
`api/lowlevel` and `api/studio`. The build stages `fmod.jar`, headers, and the `armeabi-v7a`,
`arm64-v8a`, and `x86_64` core/Studio libraries under `mobile/build/generated/fmodSdk`.

Before packaging, Gradle verifies the FMOD version and these exact SHA-256 values:

| Input | SHA-256 |
| --- | --- |
| `common.strings.bank` | `f9b633795f1c1634f1f1f7e9fed8a5c53c9c6b46554cc52b7e7880d8b3481381` |
| `common.bank` | `821df0944062f5bf134b184daf099ab68fcdb549d06be1c13e721bfbfc5a6b3e` |
| `ks_nissan_skyline_r34.bank` | `a50ba96017868f37c50804350ea7a159b1f13ef347af95aca28dd1b8743bbc93` |
| `fx_lamborghini_huracan_trofeo_evo2.bank` | `74f5053dfcae0529027b37da993ece36d2ff3d26102af8370bfe6589d8f2479c` |
| `tr_lamborghini_aventador_sv.bank` | `b83116900c41666fedf7b7256793d3d8808930a40ab938f1b089efd13bf63e42` |
| `ks_alfa_romeo_4c.bank` | `3e2c5d4341afda3131aa6095cdbacc46aa76592fca3b365cae00ae4fe6e3bf76` |
| `zesty_toyota_supra_mk4_shuto_street.bank` | `64cfba3e153903430d95ec339b81930085708a1f5a74145b01c46d93aa067c0d` |

The verified banks are staged unmodified and uncompressed as `fmod/*.bank` inside the local APK.
An unknown or modified bank is a build error. Hash pinning identifies tested bytes; it does not
grant a license or redistribution right. The Assetto installation and `audio_samples/` tree must
never be modified by the build. SDK files, banks, previews, generated assets, APKs, decoded/raw
samples, and reference material remain untracked and must not be published. There is no
decoded-WAV or procedural fallback.

## Profile event contracts

Each profile has a capability map separate from its event allowlist. `DEDICATED_EVENT` means FMOD
gets a separately instantiated event and the mixer may expose it. `EMBEDDED_IN_ENGINE` means the
authored sound is part of `engine_int`, follows that graph's controls, and has no synthetic event
or independent gain row.

| Profile | Dedicated allowlist and parameters | Embedded capabilities | Known stubs/exclusions |
| --- | --- | --- | --- |
| Skyline R34 | `engine_int(rpms, throttle)`; `turbo(boost, bov, bov_decay)`; `limiter(decay)`; `gear_int(state)`; `backfire_int(throttle)` | None | No transmission/start; BOV parameters are declared but not promoted as audible |
| Huracan Trofeo EVO2 | `engine_int(rpms, throttle)`; `gear_int(state)`; `backfire_ext(throttle)`; `transmission(drivetrain_speed, throttle)` | Limiter in `engine_int` | `backfire_int` and `limiter` are empty; turbo is excluded because the car is naturally aspirated |
| Aventador SV | `engine_int(rpms, throttle)`; `transmission(drivetrain_speed, throttle)` | Shift, backfire, and limiter character in monolithic `engine_int` | `gear_int`, `backfire_int`, and `limiter` are empty; turbo is excluded because the car is naturally aspirated; transmission is a verified, coast-biased authored event |
| Alfa Romeo 4C | `engine_int(rpms, throttle)`; `turbo(boost, bov, bov_decay)`; `limiter(decay)`; `gear_int(state)`; `backfire_int(throttle)` | None | No transmission/start; boost is audible but BOV controls are inert |
| Toyota Supra MK4 | `engine_int(rpms, throttle)`; `turbo(boost, bov, bov_decay)`; `gear_int(state)`; `backfire_int(throttle)` | Limiter, start, and shutdown in `engine_int` | `limiter` and `transmission` are empty; BOV is active but `bov_decay` is unused |

The Huracan exception is intentional: `backfire_ext` is its only sample-bearing authored backfire
event, so that exact GUID is routed to the app's BACKFIRE capability. This does not permit any
other exterior event. Event paths, GUIDs, parameter names, and capability routes are explicit in
`FmodCarProfile` and must be validated after bank load. Never replace the allowlists with bank
enumeration.

The mixer follows the dedicated allowlist:

| Profile | Independent mixer rows |
| --- | --- |
| Skyline R34 | Engine, Turbo, Limiter, Shifts, Backfire |
| Huracan Trofeo EVO2 | Engine, Shifts, Backfire, Transmission |
| Aventador SV | Engine, Transmission |
| Alfa Romeo 4C | Engine, Turbo, Limiter, Shifts, Backfire |
| Toyota Supra MK4 | Engine, Turbo, Shifts, Backfire |

Changing the selected profile releases its old instances and loads the new allowlist. The selected
profile and supported event-level gain/toggle values are persisted; stale profile ids fall back to
the R34. **Load Only** is a persisted audition mode that matches the desktop lab by forcing engine
and transmission audio throttle to one. The default uses the authored load/coast
blend. **Coast Only** is the mutually exclusive zero-throttle override. Neither mode
changes turbo, backfire detection, tach behavior, or EV physics.

## Sound-control calibration

The RPM/tach/gear contract is recorded in [Architecture](architecture.md). Additional authored
sound controls are profile-specific:

| Profile | Turbo model | Limiter route | Lift/backfire gate | Transmission |
| --- | --- | --- | --- | --- |
| Skyline R34 | 3,400 RPM reference, gamma 2, lag 0.9988/0.995, normalized cap 0.333 | Dedicated, 50 Hz decay pulses | Arm >0.8, fire <0.3 above 4,750 RPM | None |
| Huracan Trofeo EVO2 | None (naturally aspirated) | Embedded in engine | Arm >0.8, fire <0.3 above 3,500 RPM | Up to 260 rad/s |
| Aventador SV | None (naturally aspirated) | Embedded in monolithic engine | Authored in monolithic engine; 3,800 RPM/0.28 metadata is not a separate one-shot | Up to 350 rad/s; real authored event with a coast-biased response |
| Alfa Romeo 4C | 2,400 RPM reference, gamma 2.5, lag 0.995/0.99, normalized cap 0.95625 | Dedicated, 50 Hz decay pulses | Arm >0.8, exact-zero release above 6,500 RPM | None |
| Toyota Supra MK4 | 3,000 RPM reference, gamma 4, lag 0.996/0.996, normalized cap 1.0 | Embedded in engine | Arm >0.8, fire <0.3 above 5,250 RPM | Empty stub; omitted |

Turbo dynamics are audio-only and never delay or scale BYD axle torque. Dedicated shift/backfire/
limiter triggers use monotonic serials so a delayed 400 Hz control tick cannot lose a short edge. An
embedded capability is left to the engine graph rather than duplicating it with a fake one-shot.

Engine RPM and authored transmission speed pass through a 20 ms audio-only critically damped
follower on the 400 Hz FMOD worker. Its continuous velocity prevents each new 200 Hz source target
from changing pitch slope abruptly; it never feeds back into the tach or EV dynamics. Engine and
transmission audio throttle use an asymmetric 35 ms attack/120 ms
release follower, giving the bank hundreds of intermediate mix values instead of an instantaneous
load/coast layer replacement. The raw pedal used by turbo, BOV, backfire detection, and EV
physics bypasses those followers, as do shift direction, limiter decay, and all monotonic event
serials. Turbo boost then receives an audio-only 0.005-per-command delivery cap after the planner,
leaving BOV detection and vehicle behavior unchanged. Device Studio evaluation runs synchronously
on the 400 Hz owner thread; the 64-frame 48 kHz mixer consumes controls on a 1.33 ms block quantum.
The control scheduler drops missed deadlines rather than replaying them in one mixer block. An RPM
presentation step is likewise capped to one nominal 400 Hz control interval, so a late worker
wakeup catches up over later commands instead of making a larger or coalesced pitch change. This retains
the synchronous solution to the measured 15–55 ms asynchronous parameter plateaus while keeping a
5.33 ms four-buffer queued depth and avoiding drivetrain lag in the EV model.

A separate source-side staircase was traced to quantized road speed. Simulator physics preserves
continuous `Double` speed through its fictional gearbox and both engine/transmission FMOD controls.
The real car's integer BYD samples pass through a quantizer-aware estimator which measures the
elapsed time between whole-km/h crossings and advances fractional speed on each 5 ms simulation
tick. Prediction beyond the current bin is tightly bounded for a late 20 ms vendor poll, direction
reversals reset the learned rate, and a stopped zero settles exactly. Both FMOD routes consume the
same reconstructed result while the dashboard retains the raw reported whole number. If fractional
telemetry is ever observed, it bypasses quantizer reconstruction.

## Compatibility DSPs

Before any bank load, the native runtime registers compatibility plugins named `FMOD Distance
Filter`, `FMOD Gain`, and `FMOD Distortion`. Each reproduces the original name, version, and
serialized parameter descriptors so old Assetto graphs can deserialize, but they do not all pass
PCM through unchanged:

- Distance Filter retains maximum-distance, frequency, and 3D-attribute state and is a pass-through
  for this fixed-cabin listener/emitter model. It does not simulate changing exterior distance.
- Gain applies the authored dB value, supports polarity inversion, and smooths parameter changes
  over 256 samples.
- Distortion amplifies according to its level and then hard-clips samples to `[-1, 1]`.

The implementations are deliberately bounded to the behavior needed by these bank graphs; they do
not authorize unrelated processing or changes to the authored event mix.

## Rendered-PCM validation

Metadata inspection is necessary but insufficient. A bank may expose a GUID, accept parameters,
preload, and return success from `start()` while rendering silence; the Lambo/Supra empty stubs are
concrete examples. Required profile verification therefore creates a second deterministic FMOD
system in `NOSOUND_NRT` mode and measures its off-screen output:

1. Load strings, common, and exactly one hash-verified car bank; resolve only its allowlist and
   flush selected sample loading.
2. Enable metering on the native master DSP/channel group and isolate one dedicated event at a
   time using the real event gains, not extracted WAVs.
3. Drive deterministic bounded windows for idle, load, coast, high RPM/limiter, shift up/down,
   lift/backfire, turbo/BOV where declared audible, and transmission where declared.
4. Record finite peak/RMS plus event-start and sound-played callbacks, including captured sample
   names where available. Require nonzero PCM and callback evidence where sound is expected, and
   verify that disable/gain changes measurably affect that same rendered signal.
5. Exercise monolithic `engine_int` across the regions containing embedded capabilities instead of
   attempting to start their empty event stubs.
6. Compare event timing and PCM character with the desktop lab using Assetto's 1.08 runtime, then
   repeat on Android/FMOD 1.10.11. A discrepancy or silent expected event remains a failed or
   unsupported capability until explained.

This method has produced finite PCM and callback evidence for the Aventador's dedicated
`transmission` event, including its stronger coast-biased response, so that event remains in the
runtime allowlist and mixer.

`NOSOUND_NRT` advances the FMOD graph and exposes its PCM to the native meter without sending it
through Android's audio route. It proves bank/event behavior under deterministic stimuli, not the
physical BYD speakers. Connected tests, repeated lifecycle runs, and the simulated-pedal trace
remain required after relevant changes. The current build passed the JVM suite, lint, debug/app-test
assembly, all 16 connected tests on `BYD_Seal_1920x1080`, and five-profile NRT PCM/callback checks.
A parked head-unit pass remains outstanding, so routing, physical pedal latency, factory-DSP
balance, and cabin fidelity are not yet verified for this build.

## Audio route decision

The FMOD output is configured for 48 kHz stereo. Real-car testing established that stereo is the
Android route the BYD DSP distributes across the factory system. Do not create an application-side
surround layout, duplicate channels, or expose an output-format selector without new reproducible
evidence on the target car.

Speaker distribution after the stream leaves the app belongs to the vehicle audio system. The
emulator can verify FMOD PCM production and lifecycle behavior after the required checks pass; it
cannot prove head-unit routing or cabin quality.

## On-car test discipline

Test parked or with a passenger in a controlled environment. Before trusting a new firmware,
record only non-sensitive observations:

1. Whether vendor classes and permission definitions exist.
2. Whether getters return plausible changing pedal and speed values.
3. Update cadence, integer quantization, and stale-value behavior.
4. Whether input fallback, smoothing, FMOD audio focus, and service lifecycle recover cleanly.
5. Whether stereo reaches the intended factory speakers without skips or missing one-shots.

Do not store identifiers or complete driving traces in the app. A short redacted manual test note
is more useful than a permanent private log.

## Verification targets

Use `BYD_Seal_1920x1080` for connected tests, rendered-PCM checks, and the simulated-pedal drive.
Exercise ignition, launch, automatic and media-button shifts, kickdown, Load Only, Coast Only, focus
loss/recovery, repeated start/stop, and every capability actually declared by each profile. Include
turbo/BOV or transmission only where the capability contract says it applies. Compare a
deterministic trace with the local desktop bank lab, but require a parked real-car pass before
claiming BYD latency, speaker, or acoustic fidelity. The current automated build gates are green;
that physical-cabin qualification remains separate and incomplete.

The most durable external evidence is BYD Open Platform material, Android permission/audio-focus
documentation, official FMOD documentation, locally verified bank metadata, and observed head-unit
behavior. Community research is a lead, not authority.
