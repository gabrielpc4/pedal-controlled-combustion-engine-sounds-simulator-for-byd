# Full engine-sound implementation

Last verified: 2026-08-15

This document is the durable implementation handoff for the interactive motor-sound application that followed the original pedal-telemetry POC. Read this together with [the BYD telemetry notes](research-findings.md), not instead of them.

## Delivered behavior

The `mobile` module is now a complete, landscape dashboard application:

- reads accelerator, brake, and road speed from the existing read-only BYD DiLink probe when the corresponding signals are valid;
- falls back automatically to touch/keyboard simulator pedals when the vendor API is unavailable;
- advances a Seal Performance-calibrated electric road model and an independent synthetic engine layer at a fixed 200 Hz;
- performs presentation-only automatic upshifts, braking downshifts, safe kickdowns, and ratio swaps without interrupting electric wheel torque;
- synthesizes a responsive fictional V10 continuously from RPM, load, throttle, shift, overrun, and limiter state;
- experimentally requests stereo, quad, 5.1, or 7.1 logical PCM output and mirrors the same engine program to every initialized logical channel;
- exposes live audio route, channel, buffer, sample-rate, session, and underrun diagnostics;
- renders the requested car-and-tachometer composition against the 1920 x 990 safe-area target measured on the emulator; the actual car dimensions and insets remain unmeasured.

The road model is not a pedal-to-needle animation: throttle requests motor torque, and motor speed, the 390 kW ceiling, mass, traction, drag, rolling resistance, and braking determine vehicle acceleration. Fictional gears then turn road speed into the sound RPM shown on the gauge. Those gears never feed back into wheel torque.

## Source map

| File | Responsibility |
| --- | --- |
| `simulation/EngineSimulation.kt` | EV road state, 200 Hz motor/vehicle integration, and independent sound-RPM shift controller |
| `audio/EngineSynthesizer.kt` | Allocation-free procedural V10 source |
| `audio/EngineAudioEngine.kt` | Audio focus, device inspection, channel negotiation, continuous `AudioTrack` writer |
| `drive/DriveController.kt` | BYD/manual input selection and coordination of telemetry, simulation, and audio |
| `MainActivity.kt` | Full-screen Compose dashboard, gauge, pedals, diagnostics, and input controls |
| `telemetry/BydSpeedReader.kt` | Existing read-only reflective DiLink capability probe and getter polling |
| `diagnostics/PersistentDiagnosticLog.kt` | Bounded, synced app-private transition/crash event trail |
| `BydMotorSoundApplication.kt` | Early diagnostic installation and dashboard lifecycle events |
| `simulation/EngineSimulationTest.kt` | EV envelope/acceleration, synthetic shift, braking, and idle behavior tests |
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

## Electric vehicle and synthetic engine model

The physical longitudinal defaults use published Seal Performance AWD anchors. The complete evidence/assumption split and source links are recorded in [BYD Seal Performance calibration](byd-seal-performance-calibration.md).

| Physical parameter | Default |
| --- | ---: |
| Front / rear / combined maximum output | 160 / 230 / 390 kW |
| Front / rear / combined maximum torque | 310 / 360 / 670 Nm |
| Published 0–100 km/h / top speed | 3.8 s / 180 km/h |
| Vehicle mass | 2,185 kg |
| Motor-speed envelope | 0–16,000 RPM |
| A2MAC1 front / rear peak wheel torque | 3,170 / 3,975 Nm |
| A2MAC1 measured acceleration | 3.97 s to 100 km/h |
| Effective fixed reduction | 10.81:1 |
| Wheel radius | 0.347 m |
| Drivetrain efficiency / traction ceiling | 0.92 / 10.0 m/s² |
| Rotating-mass factor | 1.10 |
| Drag area / rolling coefficient | 0.504 m² / 0.010 |

BYD's official motor ratings remain the authority for 390 kW and 670 Nm. Vehicle acceleration now uses separately editable front/rear wheel-torque curves digitized from A2MAC1's measured acceleration chart. They total 7,145 Nm at peak; the rear share rises from approximately 56% at launch to 71% near the official top speed. The wheel-torque curves taper continuously with road speed and remain bounded by the configured motor-power/efficiency sanity ceiling. See [the calibration record](byd-seal-performance-calibration.md) for the digitized points and evidence policy.

At every 5 ms fixed step:

1. Raw accelerator position is evaluated through the editable Sport-like pedal curve, then accelerator and brake requests pass through exponential response filters.
2. A valid external road-speed sample replaces virtual speed. The first live sample selects a safe synthetic sound gear without reporting a fake acceleration spike.
3. In simulator mode, normalized road speed selects independent front and rear wheel torque from the digitized editable curves.
4. Requested wheel torque is scaled by the filtered Sport-like pedal request and bounded by the configured motor-power/efficiency sanity ceiling.
5. Wheel torque divided by tire radius produces drive force; the non-binding configurable traction ceiling remains available for tuning.
6. In simulator mode only, lift-off can apply a constant coast-regen deceleration (`simulatorCoastRegenMps2`, default 0.50 m/s²) so virtual speed drops faster than aero drag alone.
7. Service braking, aerodynamic drag, and rolling resistance are subtracted; net force divided by physical mass plus an effective rotating-mass factor advances vehicle speed. Reported acceleration is the actual clamped speed delta.
8. The independent sound RPM target is road-coupled through the current presentation gear ratio and filtered with `syntheticRpmResponseSeconds`. There is no lift-off RPM retention layer.
9. The sound gearbox can swap ratios and create an audible/visible shift, but it never changes motor torque, wheel force, or physical acceleration.

Braking therefore slows the real or virtual vehicle first. Pedal lift unloads the fictional engine sound without modifying EV wheel force. Lower road speed can request a lower presentation gear; there is no simulated clutch feeding torque back into the vehicle.

### Synthetic automatic shifts

Upshifts begin above the configured sound shift point with meaningful throttle and a higher presentation gear available. A projected synthetic over-rev near 97% of redline forces a safe sequential upshift, including the first externally supplied live-speed sample or an unsafe live-speed change. Every higher gear derives its downshift point from the exact RPM landing of the preceding upshift; the editable global value is retained only as a minimum floor. Synthetic-RPM and road-speed projections reject an over-rev before any downshift.

The ratio changes at 38% of the configured shift animation. An upshift lasts 270 ms and a downshift 340 ms by default; a 450 ms completed-shift dwell prevents ordinary hunting. An explicit zero-pedal return to the gear's landing threshold can downshift immediately. Shift RPM follows a ratio-derived target, but EV road force remains continuous throughout the presentation event. Synthetic RPM is always road-coupled on lift-off; retention/decay was removed after it caused gear hunting.

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

The **TUNE** control opens a persistent live-editing workstation. It exposes the Seal-response motor ratings, measured front/rear wheel-torque peaks and curves, live AWD distribution, motor speed and reduction, efficiency, traction ceiling, mass/rotating-mass factor, tire radius, drag, rolling resistance, top speed, Sport-like pedal curve and timing, synthetic RPM response, SIM coast regen, all seven presentation ratios, shift timing, audio layers, and firing harmonics. Graphs visualize ≈ motor torque/power (UI-scaled), torque distribution, response timing, shift landing/downshift points, and spectrum. Display-unit policy is in [UI display and simulation decisions](ui-display-and-simulation-decisions.md). Control inventory: [Live tuning interface](tuning-interface.md).

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

- the default physical profile contains the published 670 Nm, 390 kW, 2,185 kg, and 180 km/h anchors;
- the digitized axle curves reproduce the 3,170/3,975 Nm peaks and approximately 56% to 71% rear-share progression;
- sustained throttle increases sound RPM progressively with road speed rather than jumping directly to a pedal-derived target;
- first-gear sound RPM does not reverse at any tested positive throttle input;
- full-throttle virtual acceleration reaches 100 km/h inside the 3.90–4.02 second A2MAC1 calibration band;
- low-speed acceleration is stronger than high-speed acceleration, and a synthetic upshift causes no wheel-torque discontinuity;
- automatic shifts begin near the shift point, drop RPM, and honor completed-gear dwell;
- joining live speed selects a safe ratio, and projected over-rev forces a throttle-independent emergency upshift;
- virtual/live acceleration has the correct sign and bounds, including zero acceleration when braking at rest;
- the sound limiter uses hysteresis instead of buffer-rate chatter;
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
- BYD does not publish the complete motor dyno curves or Sport pedal transfer table. The A2MAC1 axle curves materially improve the longitudinal reconstruction, but they were digitized from a raster chart and may describe a modified or differently configured vehicle. Raw test data and instrumented validation on this exact Brazilian car remain necessary.
- Add named profile import/export, a speaker-walk diagnostic, and in-app telemetry/audio recording after first-car validation.
