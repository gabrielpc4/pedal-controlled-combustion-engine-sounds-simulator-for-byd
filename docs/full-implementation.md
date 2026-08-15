# Full engine-sound implementation

Last verified: 2026-08-15

This document is the durable implementation handoff for the interactive motor-sound application that followed the original pedal-telemetry POC. Read this together with [the BYD telemetry notes](research-findings.md), not instead of them.

## Delivered behavior

The `mobile` module is now a complete, landscape dashboard application:

- reads accelerator, brake, and road speed from the existing read-only BYD DiLink probe when the corresponding signals are valid;
- falls back automatically to touch/keyboard simulator pedals when the vendor API is unavailable;
- advances a stateful engine, transmission, clutch/converter, and virtual vehicle at a fixed 200 Hz;
- performs automatic upshifts, braking downshifts, safe kickdowns, torque cuts, clutch interruption, ratio swaps, and rev matching;
- synthesizes a responsive fictional V10 continuously from RPM, load, throttle, shift, overrun, and limiter state;
- experimentally requests stereo, quad, 5.1, or 7.1 logical PCM output and mirrors the same engine program to every initialized logical channel;
- exposes live audio route, channel, buffer, sample-rate, session, and underrun diagnostics;
- renders the requested car-and-tachometer composition against the 1920 x 990 safe-area target measured on the emulator; the actual car dimensions and insets remain unmeasured.

The result is not a pedal-to-needle animation. Throttle produces torque; torque and coupling change angular speed; vehicle inertia, gearing, engine losses, braking, and shifts determine what the needle does.

## Source map

| File | Responsibility |
| --- | --- |
| `simulation/EngineSimulation.kt` | Engine/vehicle state, 200 Hz integration, torque curve, gearing, shift controller |
| `audio/EngineSynthesizer.kt` | Allocation-free procedural V10 source |
| `audio/EngineAudioEngine.kt` | Audio focus, device inspection, channel negotiation, continuous `AudioTrack` writer |
| `drive/DriveController.kt` | BYD/manual input selection and coordination of telemetry, simulation, and audio |
| `MainActivity.kt` | Full-screen Compose dashboard, gauge, pedals, diagnostics, and input controls |
| `telemetry/BydSpeedReader.kt` | Existing read-only reflective DiLink capability probe and getter polling |
| `simulation/EngineSimulationTest.kt` | Inertia/acceleration, shift, braking, and idle behavior tests |
| `audio/EngineSynthesizerTest.kt` | PCM signal/RMS behavior and exact channel mirroring tests |

## Runtime architecture

```text
BYD getters (when available)       Simulator touch / W-S keys
             \                       /
              +---- DriveController ----+
                         | 200 Hz
                  EngineSimulation
                    /           \
             UI snapshot      EngineAudioFrame
                 30 Hz              |
             Compose UI       EngineSynthesizer
                                     |
                              mono PCM16 program
                                     |
                        duplicate into N channels
                                     |
                    one continuous streaming AudioTrack
```

Threads have intentionally separate duties:

- the BYD reader owns its vendor-getter polling worker;
- `DriveController` owns the fixed-step simulation worker;
- `EngineAudioEngine` owns a high-priority audio writer;
- the main thread samples immutable state approximately every 33 ms for Compose, while simulation and audio control remain at 200 Hz.

The hot synthesis/write path reuses its PCM arrays and performs no intentional allocation per write. The same thread currently refreshes route/underrun diagnostics every 48 writes, which can allocate framework strings and an immutable diagnostics snapshot; move that sampling off the writer if profiling shows a real-time penalty.

## Input policy

The header input button cycles these modes:

| Mode | Behavior |
| --- | --- |
| `AUTO` | Use valid live BYD accelerator/brake values; otherwise use simulator pedals |
| `SIM` | Always use touch/keyboard pedals and integrate virtual road speed |
| `BYD LIVE` | Require live BYD pedal values; the UI says `BYD UNAVAILABLE` until they are valid |

When a valid BYD road-speed value is present in live-pedal mode, it replaces simulated vehicle speed. In the current build, valid live pedals can coexist with an invalid/missing speed value; in that case `externalSpeedKmh` is null and virtual speed is still integrated. This is acceptable only for the bench POC. Before moving-car testing, live mode should require valid speed or enter a clearly disabled/frozen state rather than inventing vehicle motion. The same engine, shift, gauge, and audio model is otherwise used in both modes.

The vendor interaction remains read-only. Reflection invokes the vendor `getInstance(Context)` factory and the documented accelerator, brake, and current-speed getters; no vehicle setter permission or setter call was added.

## Engine and drivetrain model

Current fictional profile, deliberately not branded as a real vehicle:

| Parameter | Value |
| --- | ---: |
| Name / layout | Apex V10 / four stroke |
| Tachometer / idle / redline / limiter | 10,000 / 950 / 8,600 / 8,850 RPM |
| Automatic upshift / base downshift | 8,250 / 2,250 RPM |
| Maximum torque | 585 Nm |
| Engine inertia | 0.42 kg m2 |
| Vehicle mass | 1,640 kg |
| Wheel radius | 0.337 m |
| Final drive | 3.82 |
| Gears | 3.14, 2.10, 1.57, 1.24, 1.02, 0.84, 0.69 |

The normalized torque curve is interpolated smoothly through:

```text
RPM:    850  1500  2500  3800  5200  6500  7500  8300  8850
Torque: .34   .48   .68   .84   .96  1.00   .97   .89   .69
```

At every 5 ms fixed step:

1. Raw throttle and brake are clamped and passed through exact exponential response filters.
2. A valid external road-speed sample is applied before drivetrain math; the first live sample selects a safe cruise gear and synchronizes RPM without reporting a fake acceleration spike.
3. Combustion torque is `maxTorque * torqueCurve(rpm) * throttle`, then modified by shift cut, limiter, and brake override.
4. Idle feed-forward/control, mechanical friction, and closed-throttle pumping losses contribute to net engine torque.
5. Clutch-slip torque is bounded by clutch capacity and applied equal-and-opposite: it is subtracted from the engine equation and passed through the gear/final-drive ratio to the wheels.
6. Net engine torque divided by engine inertia advances RPM; the launch clutch engagement rises progressively as the engine spins above idle.
7. In simulator mode, transmitted wheel force, aero drag, rolling resistance, engine braking, and service braking integrate vehicle speed. Reported acceleration comes from the actual clamped speed delta.
8. During downshifts, bounded positive rev-match torque spins the engine toward the new coupled speed; no RPM value is directly forced.

Braking does not subtract a cosmetic amount from RPM. It slows the virtual wheels and, through the engaged driveline, pulls the engine down. This is why brake response and downshifts feel different from simply releasing throttle.

### Automatic shifts

Upshifts begin above 8,250 RPM with meaningful throttle and a higher gear available. The torque curve peaks at 6,500 RPM and tapers toward the original 8,600 RPM redline and 8,850 RPM fuel cutoff. Independently, a projected over-rev near 97% of redline forces a safe sequential upshift even at closed throttle, which protects live mode when it receives a large road-speed change. A 1-gear downshift is permitted for low RPM, braking, or kickdown only when the projected lower-gear RPM remains below 94% of redline.

The implemented shift phases overlap smoothly:

```text
torque cut -> clutch open -> ratio swap at 38% -> RPM synchronization -> clutch/torque restore
```

An upshift takes 270 ms; a downshift takes 340 ms and adds bounded rev-match torque. New shifts are inhibited for 450 ms after completed clutch re-engagement to prevent hunting. The gauge labels an active upshift as `PERFECT SHIFT` and a downshift as `REV MATCH`.

## Procedural sound model

The repository contains no downloaded or paid engine recordings. The default source is project-written procedural synthesis, which keeps the public project reproducible and avoids redistributing raw commercial samples.

For a four-stroke engine, the firing fundamental is:

```text
firingHz = rpm / 60 * cylinders / 2
```

The renderer combines:

- the firing fundamental and four RPM/load-dependent harmonics;
- nonlinear exhaust-pulse saturation and a simple exhaust body state;
- RPM-dependent crank/mechanical and gear-whine components;
- filtered intake noise whose intensity follows throttle and RPM;
- a short low-frequency shift thump;
- stochastic overrun crackles after a sufficiently large high-RPM lift;
- a limiter gate and a shift-level dip;
- final soft clipping.

RPM, load, and throttle are smoothed at audio rate to prevent zipper noise. A deterministic xorshift source supplies noise without allocations. One synthesizer instance renders one mono program, then the audio engine duplicates each sample into the negotiated logical channel count.

## Audio routing and multichannel truth

The setting cycles `AUTO -> 7.1 -> 5.1 -> QUAD -> STEREO`.

`AUTO` inspects channel-count metadata for all output devices currently enumerated by Android and requests the highest advertised layout. That metadata is not limited to the route that the track will ultimately use, so an unrelated HDMI or USB device can make `AUTO` optimistic. A forced layout falls back to progressively smaller layouts when `AudioTrack` cannot initialize it. The output uses:

- one continuous `AudioTrack` in `MODE_STREAM`;
- PCM 16-bit at the primary-output rate advertised by Android, normally 48 kHz but not necessarily native to every routed or multichannel sink;
- `USAGE_GAME` and `CONTENT_TYPE_MUSIC`;
- API-24/25 low-latency attributes or API-26+ performance mode;
- a capacity of at least four native bursts, tuned toward two bursts where supported;
- one blocking writer on an audio-priority thread;
- reduced gain for 4/6/8-channel mirroring in an attempt to retain downmix headroom.

The 5.1 and 7.1 experiments currently copy the same full-band sample into every logical channel, including LFE. That satisfies exact logical mirroring but is not a conventional surround mix. OEM bass management may filter, omit, or sum LFE, and a downmixer may combine correlated copies with unexpected gain. Keep test volume low. A production surround path should either omit LFE or feed it a separately limited, low-pass signal after the actual HAL/downmix behavior is known.

The application requests audio focus before creating the renderer. A denied request now prevents playback; duck, transient loss, recovery, and permanent loss update diagnostic state and use short gain ramps to avoid discontinuities. Shutdown and spontaneous renderer failure both release focus. Gain ramps are unit-tested and the lifecycle was exercised on the emulator, but platform focus acquisition/listener paths are not isolated behind a fake in the JVM suite. Actual coexistence with calls, navigation, ADAS, and system warnings still requires on-car policy testing.

Important: an 8-channel `AudioTrack` is not proof that eight physical BYD speakers receive discrete channels. Android applications submit logical channels. Audio policy selects a bus, and the BYD amplifier/DSP maps that bus to physical front/rear/center/subwoofer speakers. If the vehicle media bus is stereo, Android may downmix 5.1/7.1 before the BYD DSP distributes it across the cabin. Such downmixing can also disqualify the low-latency fast path.

Therefore the production preference is:

1. choose native-rate stereo when the car exposes only a stereo media sink and let the factory DSP distribute it;
2. retain 5.1/7.1 only when on-car AudioFlinger/audio-policy evidence confirms a matching HAL output;
3. never claim physical speaker coverage based only on `AudioTrack.channelCount`.

The header/footer show the requested mode, active logical channel count/layout, routed device, sample rate, buffer, session ID, and underruns. Output-device capability metadata is retained in `AudioOutputState.advertisedChannels` but is not currently rendered in the UI.

## Head-unit UI and controls

The dashboard targets a 1920:990 design ratio. The emulator configuration used for this build measured a 1920 x 990 safe content area inside a 1920 x 1080 display after its 90-pixel system/navigation inset. That measurement does not establish the BYD panel's final `WindowInsets`, density, overscan, or bar height; record those on the car before calling the fit exact.

The **TUNE** control opens a persistent live-editing workstation for engine parameters, torque and throttle curves, pedal dynamics, all seven gear ratios, shift timing, audio layers, and firing harmonics. Graphs visualize torque/power, response timing, RPM drop, and spectrum; torque and throttle curves are edited by dragging their control points. See [Live tuning interface](tuning-interface.md).

The layout scales both dimensions together to preserve the 1920:990 design ratio and letterboxes any remainder. `WindowInsets.safeDrawing` removes system-bar and cutout areas before that fit is calculated.

Controls:

- touch and drag vertically on the right accelerator pedal;
- touch and drag vertically on the left brake pedal;
- keyboard `W` or Up Arrow for full throttle;
- keyboard `S`, Down Arrow, or Space for full brake;
- tap the input header to cycle AUTO/SIM/BYD LIVE;
- tap engine audio to mute/unmute;
- tap output mode to cycle channel policy.

The red sports-car illustration is an AI-generated fictional concept created for this app with no intentional manufacturer branding or text. Generation does not guarantee that a design is unique, copyrightable in every jurisdiction, or free of similarity to protected vehicle designs or marks. Treat it as a prototype asset pending visual/IP review. Source asset: `res/drawable-nodpi/apex_v10_car.png`; generation provenance and hash are recorded in [the source-material log](source-material/README.md#generated-ui-asset).

## Verification performed

The following command passes:

```powershell
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:lintDebug
```

Tests verify that:

- sustained throttle increases RPM progressively rather than jumping directly to a pedal-derived target;
- automatic shifts begin near the shift point, drop RPM, and honor completed-gear dwell;
- joining live speed selects a safe ratio, and projected over-rev forces a throttle-independent emergency upshift;
- virtual/live acceleration has the correct sign and bounds, including zero acceleration when braking at rest;
- the fuel cut uses limiter hysteresis instead of buffer-rate chatter;
- braking decelerates more strongly than coasting;
- the stopped engine returns to the configured 950 RPM idle;
- the synthesizer produces nonzero, varying PCM with greater RMS under load, keeps limiter phase continuous across arbitrary buffer sizes, and ramps state gains; the tests do not establish perceived sound quality or audibility on the car;
- channel duplication writes exactly the same sample to every logical channel;
- explicit BYD LIVE mode fails safe to zero pedals when telemetry is unavailable, while AUTO alone may use simulator fallback;
- rapid controller lifecycle transitions cannot revive an obsolete simulation loop.

The automated suite covers drivetrain behavior, audio continuity/channel replication, input arbitration, and tuning sanitization/interpolation. The [emulator validation record](emulator-validation.md) preserves the accelerated emulator configuration, APK hash, viewport measurement, and final touch-throttle/brake evidence.

APK output:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

## Actual-car acceptance procedure

Only test parked, or have a passenger operate diagnostics in a controlled environment. The debug build has no enforced drive lockout or calibrated maximum-volume ceiling. Synthetic audio can mask turn signals, ADAS warnings, navigation prompts, calls, emergency vehicles, and other safety cues; it is not approved for public-road use.

1. Install and launch the debug APK.
2. Confirm `AUTO` changes from `SIM FALLBACK` to `BYD PEDALS` when the DiLink getter probe becomes active.
3. Check accelerator and brake movement at rest and compare displayed response with physical pedal motion.
4. Verify speed becomes the external-speed source during a safe passenger-operated test.
5. Note the audio session ID and active logical layout from the footer.
6. While audio is playing, capture on the host:

```powershell
adb shell dumpsys media.audio_flinger > byd_audio_flinger.txt
adb shell dumpsys media.audio_policy > byd_audio_policy.txt
```

7. Find the active track by session ID/PID. Compare its channel mask with the containing output thread/HAL channel mask. Track=7.1 plus output=stereo proves downmix; both 8-channel proves multichannel reaches the HAL, not the final physical speakers.
8. Confirm physical cabin coverage by listening at low volume in every seating position. A later diagnostic should solo FL/FR/FC/BL/BR/SL/SR and use a separately level-limited, low-frequency LFE test tone. Listening is a practical end-to-end check, not the only possible proof: an OEM routing description, electrical measurement, or calibrated multichannel acoustic capture can provide stronger evidence. Bass management may deliberately redirect the LFE or main-channel bass.
9. Watch `underruns`. If they rise, increase the effective buffer by one native burst.
10. Measure pedal-to-acoustic latency externally. `AudioTimestamp` cannot include unknown amplifier/DSP delay.

## Known limitations and next work

- The real-car getters are still polled every 20 ms. Reflection can call the getters, but `AbsBYDAutoSpeedListener` is an abstract vendor class and cannot be instantiated by `java.lang.reflect.Proxy`. Add a trustworthy compile-only BYD SDK/stub or a carefully reviewed runtime subclass mechanism before callback testing.
- Exact on-car BYD permission behavior remains unknown until firmware `13.1.33.2503250.1` is tested.
- Live-pedal mode currently falls back to integrating virtual speed if BYD speed is invalid. Require a valid speed signal or fail closed before any moving-car test.
- The included synthesis is responsive and dependency-free, not a recorded AAA sample bank. See [drivetrain and audio research](drivetrain-audio-research.md) before licensing recordings.
- `AUTO` can inspect Android capability metadata, but only actual-car dumpsys and listening can establish physical speaker routing.
- Full-band LFE mirroring is experimental. Audio-focus handling is implemented, but its interaction with vehicle warnings and other media remains unverified on the BYD audio policy.
- The 1920 x 990 area is an emulator measurement. Actual-car insets remain unknown; the dashboard now preserves its ratio and letterboxes when the safe area differs.
- Playback is Activity-owned and intentionally stops when the dashboard is no longer visible. Background/foreground-service operation is not included in this release.
- The `mobile` APK deliberately targets SDK 25 for DiLink compatibility. It is a sideload prototype, not a Google Play-ready application, and modern devices may block installation without a low-target-SDK test override.
- There is no enforced drive lockout or production volume policy. Do not use the current build on public roads.
- The current simplified coupling is game-oriented rather than an engineering-grade vehicle model. It intentionally prioritizes stable, tunable feel.
- Add named profile import/export, a speaker-walk diagnostic, and in-app telemetry/audio recording after first-car validation.
