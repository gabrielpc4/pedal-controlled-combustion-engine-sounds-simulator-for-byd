# Architecture

## Purpose

The application combines an electric-vehicle road simulation with a **presentation-only**
combustion-engine layer. Gauge RPM, virtual shifts, and audio must not affect vehicle control or
pretend that the BYD has a combustion drivetrain.

The runtime follows one-way data flow:

```text
BYD getters or simulator controls
             |
       DriveController (200 Hz fixed-step coordinator)
             |
        EngineSimulation ------> immutable Compose UI snapshot
             |
       EngineAudioFrame (latest value, short synchronized publication)
             |
       EngineAudioEngine (audio focus + 400 Hz control worker)
             |
       FmodControlPlanner -> continuous pitch/load followers -> direct JNI buffer
             |
       FMOD Studio events -> native mixer -> stereo vehicle route
```

Start from `DriveController`, `EngineSimulation`, `EngineAudioEngine`, `FmodControlPlanner`, and
the native bridge when tracing the implementation.

## Separation of responsibilities

### Input and coordination

`DriveController` owns application-level lifecycle, input selection, fixed-step updates, user
preferences, and the handoff from simulation to audio. Live telemetry and on-screen simulator
pedals feed the same normalized input model; they must not create separate physics or audio paths.

### EV simulation and presentation gearbox

`EngineSimulation` owns virtual road speed, braking, pedal smoothing, transmission position, and
dashboard state. Its fictional gearbox uses the selected profile's gear count, divides the
0-190 km/h range into equal speed bands, and maps each band into that profile's RPM domain. The
last gear therefore reaches the profile redline at exactly 190 km/h. Launch control, kickdown,
automatic shifts, and media-button manual shifts are presentation behavior only: a virtual shift
must not add a clutch, combustion torque lag, or torque interruption to the EV road-force model.

Simulator physics keeps road speed as continuous `Double` data end to end; it is never rounded
before coupled RPM or authored transmission speed is calculated. Live BYD speed arrives as
whole-km/h samples, so only that external path uses `QuantizedSpeedEstimator`. It derives velocity
from elapsed integer boundary crossings, advances fractional speed at the 200 Hz controller rate,
and allows only a bounded 0.08-bin overrun when the next 20 ms vendor poll is late. Direction
changes reset the learned rate and a stopped zero settles exactly to zero. The resulting continuous
drivetrain state feeds both engine RPM and `drivetrain_speed`; the raw whole-number sample remains
available for the dashboard. A future fractional telemetry signal automatically bypasses the
quantizer-specific reconstruction and uses an ordinary continuous follower.

`FmodCarProfile` is immutable bank, capability, event, turbo/backfire, tach, and gear metadata.
The five calibrated display/simulation contracts are:

| Profile | Idle | Upshift | Redline | Limiter | Tach max | Gears | Shift up/down |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Skyline R34 | 800 RPM | 7,900 RPM | 8,000 RPM | 8,000 RPM | 8,500 RPM | 6 | 95/220 ms |
| Huracan Trofeo EVO2 | 1,040 RPM | 8,200 RPM | 8,200 RPM | 8,350 RPM | 9,000 RPM | 7 | 60/150 ms |
| Aventador SV | 850 RPM | 8,400 RPM | 8,400 RPM | 8,500 RPM | 9,000 RPM | 7 | 80/260 ms |
| Alfa Romeo 4C | 850 RPM | 6,300 RPM | 6,500 RPM | 6,750 RPM | 7,700 RPM | 6 | 100/200 ms |
| Toyota Supra MK4 | 980 RPM | 7,950 RPM | 8,000 RPM | 8,000 RPM | 8,500 RPM | 6 | 100/150 ms |

The Lambo shift/redline values deliberately preserve the original app profiles while their source
limiter values remain separate. Reference gear ratios remain profile metadata even though
road-speed band placement is deliberately equalized for the BYD:

| Profile | Ratios, first to last |
| --- | --- |
| Skyline R34 | 3.827, 2.360, 1.685, 1.312, 1.000, 0.793 |
| Huracan Trofeo EVO2 | 3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84 |
| Aventador SV | 3.91, 2.44, 1.81, 1.46, 1.18, 0.97, 0.84 |
| Alfa Romeo 4C | 3.9, 2.269, 1.435, 0.978, 0.755, 0.622 |
| Toyota Supra MK4 | 2.5000, 2.0000, 1.5217, 1.2000, 1.0312, 0.8571 |

Do not normalize one car to another car's RPM range or infer events from bank enumeration.

### FMOD audio

`EngineAudioEngine` owns Android audio focus and one FMOD lifecycle. It publishes only the latest
`EngineAudioFrame`; a dedicated 400 Hz worker plans parameters, applies an allocation-free 20 ms
critically damped follower to RPM and authored drivetrain speed, applies a 35 ms attack/120 ms release
crossfade to engine and transmission audio throttle, and performs one batched JNI update. These followers are
presentation-only and do not feed the tach or EV physics. Continuous
state may coalesce, while monotonic serials preserve shift, limiter, BOV, and backfire edges
between worker ticks.

`FmodControlPlanner` maps EV inputs into sound-only FMOD domains. RPM and pedal position drive the
engine event; optional audio-only turbo dynamics drive authored boost parameters; limiter and
backfire state machines produce edges; road speed becomes `drivetrain_speed` only for profiles
with an authored transmission event. None of those models feeds back into road force. **Load Only**
matches the desktop lab by holding engine and transmission audio throttle at one; **Coast Only**
holds only `engine_int.throttle` at zero. The modes are mutually exclusive. Turbo, backfire detection, tach, and EV
physics continue to receive the real pedal/speed values. The R34 and Alfa dedicated limiter
controllers both use 50 Hz decay pulses. Native serial-delta handling rearms delayed dedicated
limiter pulses rather than losing them during a scheduler stall.

Profiles route each semantic capability either to a dedicated event or to material embedded in
`engine_int`. Embedded sounds do not get a fake event instance or an independent mixer row. This
matters because an FMOD event description can resolve successfully while its graph contains no
sample-bearing instrument.

The explicit per-profile event allowlists are:

| Profile | Separately instantiated events | Embedded/empty/excluded behavior |
| --- | --- | --- |
| Skyline R34 | `engine_int`, `turbo`, `limiter`, `gear_int`, `backfire_int` | No transmission/start event; declared BOV controls are not treated as audible capabilities |
| Huracan Trofeo EVO2 | `engine_int`, `gear_int`, `backfire_ext`, `transmission` | Limiter material is embedded in `engine_int`; `limiter` and `backfire_int` are empty stubs; bank turbo is excluded because the car is naturally aspirated |
| Aventador SV | `engine_int`, `transmission` | Shift/backfire/high-RPM limiter character is monolithic in `engine_int`; `gear_int`, `backfire_int`, and `limiter` are empty stubs; turbo is excluded because the car is naturally aspirated; the dedicated transmission is a real, coast-biased authored event verified by NRT PCM and callbacks |
| Alfa Romeo 4C | `engine_int`, `turbo`, `limiter`, `gear_int`, `backfire_int` | No transmission/start event; boost is audible, but declared BOV controls are inert and must not be advertised as working |
| Toyota Supra MK4 | `engine_int`, `turbo`, `gear_int`, `backfire_int` | Limiter/start/shutdown are embedded in `engine_int`; `limiter` and `transmission` are empty stubs; BOV is active but `bov_decay` is unused |

The Huracan's `backfire_ext` is the only sample-bearing authored backfire event, so it is explicitly
routed to the app's BACKFIRE capability despite its name. That narrow exception does not authorize
exterior engine, tire, wind, collision, or other exterior events.

The JNI backend initializes a 48 kHz stereo FMOD Studio system and registers compatibility DSPs
named `FMOD Distance Filter`, `FMOD Gain`, and `FMOD Distortion` before loading strings, common,
then the selected car bank. All three reproduce the plugin names, versions, and serialized
parameter descriptors expected by the old graphs. Their signal behavior is deliberately explicit:

- Distance Filter stores maximum-distance, frequency, and 3D-attribute state but passes PCM
  through for the app's fixed cockpit listener/emitter arrangement.
- Gain converts its dB control to linear amplitude, honors signal inversion, and smooths changes
  over a short sample ramp to avoid discontinuities.
- Distortion maps its level to input amplification and then hard-clips the result to the valid
  sample range.

These are compatibility implementations for the bank graphs; they are not a general recreation of
Assetto's changing exterior-distance acoustics.

Device output uses synchronous Studio evaluation on the single 400 Hz FMOD owner thread with a
64-frame/four-buffer low-level mixer. An on-device parameter trace showed that asynchronous mode
accepted every floating-point RPM but held FMOD's evaluated value for alternating 15–55 ms
plateaus; synchronous mode made every evaluated value equal the value sent without degrading owner
thread timing. At 48 kHz, the device mixer now consumes commands on 1.33 ms block boundaries, so a
normal 2.5 ms control update does not share a mixer block with its neighbor. The control scheduler
drops missed deadlines instead of submitting several stale states back-to-back. Audio presentation
caps every delivered RPM step to the nominal 400 Hz perceptual bound and turbo boost to 0.005; a
late worker wakeup adds catch-up time instead of emitting a larger or coalesced parameter change. Four buffers
retain a 5.33 ms queued depth. The real BYD whole-km/h source is reconstructed before it reaches
RPM and audio controls. Deterministic `NOSOUND_NRT` validation uses the same synchronous Studio
contract with a 512-frame/four-buffer mixer so its bounded render windows remain stable and
reproducible.

The backend validates only the selected profile's required GUIDs/parameters, preloads its selected
sample data, keeps persistent events alive, and reuses bounded one-shot pools. FMOD owns graph
automation and authored mixing. Never instantiate bank events by enumeration. There is no WAV,
procedural, or alternate-car fallback.

### UI, persistence, and service lifecycle

Compose renders snapshots and changes repositories; it never calls FMOD or telemetry directly.
The selected profile is persisted and a change causes an orderly release/load transition on the
audio side. Load Only, Coast Only, app/car master gain, and per-event enable/gain values are clamped at their
persistence boundary. The mixer is capability-based: it shows only separately controllable events
for the selected profile, while embedded capabilities follow the engine control. Selection and
mixer changes must not create duplicate FMOD systems or silently fall back to another bank.
Obsolete WAV layer/effect preferences are ignored rather than migrated into FMOD event controls.

The application-owned controller can keep audio active through `EngineRuntimeService` while the
Activity is backgrounded. Audio-focus callbacks suspend, duck, resume, or stop output as
appropriate. Shutdown order is native instances/banks/system, `org.fmod.FMOD.close()`, then focus
release; repeated start/stop must never leave a second FMOD system alive.

## Realtime rules

- Keep bank I/O, hash validation, sample preloading, collection churn, and logging outside the
  400 Hz control path and FMOD mixer callback.
- Reuse the planner state and direct JNI buffer. Do not allocate, block on disk, or enumerate
  events during an update.
- Treat continuous controls as latest-state data and edge serials as ordered events. Never infer a
  one-shot solely from a sampled Boolean.
- Apply focus ducking as master gain and suspend/resume the mixer for transient loss; do not reload
  banks for focus, Load Only, or Coast Only changes.
- Fail closed on an SDK, native-library, bank, DSP, GUID, parameter, or output initialization
  error. Surface one useful diagnostic and release partially created resources in reverse order.
- Keep FMOD output stereo. On-car testing established that this is the route the BYD DSP
  distributes across the factory speaker system.

## Safe change patterns

### Adding or changing a car

Keep the profile declarative. Add only locally supplied banks the user is entitled to use, pin
their hashes in the build, allowlist only intended powertrain events, distinguish dedicated from
embedded capabilities, record each parameter contract, and add package/native validation. A car
change must release its old event instances before loading the next bank; do not scan or play every
event a bank happens to contain.

### Changing audio behavior

Test deterministic idle, acceleration/load, coast, every shift, pedal lift/backfire, limiter,
optional turbo/transmission, focus, and lifecycle traces. Parameter and trigger assertions prove
control intent, but they do not prove that an event produced sound. The native verification pass
uses a second deterministic `NOSOUND_NRT` FMOD system. It must isolate each dedicated event (and
deterministic regions of monolithic `engine_int`), meter the native master mix, record finite
nonzero rendered-PCM peak/RMS, and capture event-start plus sound-played callback/sample-name
evidence over bounded windows. It must also prove that event disable/gain controls alter the
measured result and that excluded or empty events are not instantiated.

Compare the same parameter timeline with the local desktop Assetto 1.08 bank lab. Treat differences
between desktop and Android results as evidence to investigate, not a reason to lower a silence
threshold until a stub appears to pass. Record unsupported/inert controls honestly: notably Alfa
BOV, Supra `bov_decay`, and the empty standalone limiter events. The Aventador transmission remains
allowlisted because deterministic NRT metering and callbacks verify its real, coast-biased authored
PCM. That evidence does not establish its loudness or character through the BYD installation.
Subjective acceptance still requires the real head unit because NRT/emulator metering cannot
establish Android-to-BYD routing, latency, factory-DSP balance, or cabin acoustics.

### Changing vehicle integration or physics

Maintain the read-only boundary and keep observed data separate from estimated calibration. A
whole-km/h BYD signal must be estimated before it controls presentation RPM/audio, but continuous
simulator state must remain continuous. Feed the same continuous drivetrain result to engine and
transmission events, and confine integer rounding to UI labels. Label approximations in code/tests
rather than presenting them as measured vehicle specifications.

## Verification

The normal local gate is:

```powershell
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon
```

Then start `BYD_Seal_1920x1080`, run `:mobile:connectedDebugAndroidTest`, install the generated
debug APK, and foreground `com.gabrielpc.enginesoundsimulator/.MainActivity`. Run the rendered-PCM
check and simulator trace for all five profiles before declaring Android support verified. The
current build passed the JVM suite, lint, debug/app-test assembly, all 16 connected tests on that
AVD, and the five-profile `NOSOUND_NRT` PCM/callback gate. Those results verify asset packaging,
ABI/native loading, deterministic simulation, event control, lifecycle, and rendered bank output.
They do not verify BYD permissions, physical pedal latency, speaker routing, factory-DSP behavior,
or cabin acoustics; this build still needs a parked physical-car pass for those claims.
