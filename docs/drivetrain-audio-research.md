# Drivetrain, game-audio, and asset research

Last updated: 2026-08-15

This note records the research behind the full implementation and the licensing decisions that should survive future sessions.

## What professional vehicle simulations do

Production middleware and mature open simulators agree on the central rule: throttle requests combustion torque, not RPM. RPM is rotational state obtained by integrating net torque against engine inertia while a clutch or torque converter couples the engine to road speed through the selected gear.

Common architecture:

```text
driver input -> engine torque curve/loss maps -> engine inertia
             -> clutch or torque converter -> gear/final drive
             -> wheel torque -> tyre force -> vehicle acceleration
```

The same-road-speed reflected vehicle inertia at the crank is approximately:

```text
IvehicleAtEngine = vehicleMass * (wheelRadius / totalRatio)^2
```

That relationship naturally makes low gears rev quickly and high gears feel more heavily loaded. Braking should act on wheels/vehicle; the engaged drivetrain then pulls RPM down. An automatic shift should cut torque, open the clutch, swap the ratio, synchronize shaft speeds, and re-engage, not teleport the needle.

Primary/reference evidence:

- [NVIDIA PhysX vehicle guide](https://nvidiagameworks.github.io/PhysX/4.1/documentation/physxguide/Manual/Vehicles.html) describes the engine as a one-degree-of-freedom rotating rigid body, torque curve, inertia, torsional clutch, ratios, neutral shift time, and autobox hysteresis.
- [PhysX vehicle source](https://github.com/NVIDIAGameWorks/PhysX/blob/4.1/physx/source/physxvehicle/src/PxVehicleUpdate.cpp) implements throttle times peak torque times the torque curve, throttle-dependent damping, clutch slip torque, and a coupled engine/wheel solve. PhysX is BSD-3-Clause.
- [Unreal Chaos Vehicles](https://dev.epicgames.com/documentation/unreal-engine/chaos-modular-vehicles-quickstart) exposes torque curves, inertia, engine braking, clutch strength, shift time, up/down RPM, ratios, and final drive. Its implementation/assets remain under Epic's EULA; use the documented architecture as a reference, not copied code.
- [Project Chrono powertrains](https://api.chrono.projectchrono.org/vehicle_powertrain.html) corroborate full/zero-throttle torque maps and shaft-based models. Its [automatic transmission source](https://github.com/projectchrono/chrono/blob/main/src/chrono_vehicle/powertrain/ChAutomaticTransmissionSimpleMap.cpp) uses separate up/down bands. Chrono is BSD-3-Clause.
- [Project Chrono torque converter](https://github.com/projectchrono/chrono/blob/main/src/chrono/physics/ChShaftsTorqueConverter.cpp) models converter speed ratio, capacity factor, and torque ratio.
- [VDrift drivetrain source](https://github.com/VDrift/vdrift/blob/master/src/physics/cardynamics.cpp) independently uses clutch capacity, crank inertia, engine friction, roughly 0.2-second shifts, clutch release/re-engagement, redline shifts, and safe downshifts. VDrift is GPL-3.0: study only unless the whole project's license strategy changes.
- [MathWorks mapped SI engine](https://www.mathworks.com/help/vdynblks/ref/mappedsiengine.html) uses mapped torque plus a first-order dynamic response. It is a research reference, not a dependency.

The implementation in this repository is original, compact Kotlin informed by those principles. No source from Unreal, VDrift, MathWorks, or the listed projects was copied.

## Shift-point research

The acceleration-optimal upshift at a given road speed is where next-gear wheel torque becomes greater than current-gear wheel torque:

```text
rpmNext = rpm * nextRatio / currentRatio
currentWheelTorque = fullTorque(rpm) * currentRatio
nextWheelTorque = fullTorque(rpmNext) * nextRatio
```

In a detailed implementation, trigger slightly before the crossover to include controller latency and use a lower, demand-dependent threshold at part throttle. A downshift must first project lower-gear RPM and reject over-rev. Kickdown should require a meaningful wheel-torque advantage and hysteresis.

The present implementation uses a fixed 8,250-RPM performance upshift. Each higher gear derives its downshift threshold from the exact ratio-based RPM landing of the preceding upshift, with separate synthetic- and road-speed projections rejecting over-rev. The crossover calculation remains a possible future physical-efficiency refinement, but it does not control the fictional sound gears.

## Engine-sound synthesis references

- [DasEtwas/enginesound](https://github.com/DasEtwas/enginesound) is an MIT procedural acoustic engine model and can render exact-RPM seamless loops. It is the safest open offline loop-generation pipeline if the procedural source is later upgraded.
- [ange-yaghi/engine-sim](https://github.com/ange-yaghi/engine-sim) is MIT and demonstrates combustion pulses, inertia, throttle/load response, and exhaust acoustics. It is an audio-oriented demonstration, not engineering validation.
- [Antonio-R1/engine-sound-generator](https://github.com/Antonio-R1/engine-sound-generator) is MIT and demonstrates exhaust waveguides, reflections, and muffler sections in JS/C++/WASM.
- [FMOD's granular vehicle examples](https://www.fmod.com/assets/html5/core_api/demo.html) are useful design references, but example media must not be redistributed and automotive/embedded use requires [custom FMOD licensing](https://www.fmod.com/licensing).
- [Jonas-Hack/Granular-Synthesis-for-Engine-Audio](https://github.com/Jonas-Hack/Granular-Synthesis-for-Engine-Audio) demonstrates RPM-driven grain selection and crossfades, but no usable license was found. Study only.

## Ranked recording/middleware options

Prices below were observed during research and can change. Re-check before purchase.

### 1. Original procedural or MIT-assisted generated loops

Best immediate development path. Keep the current project-written synthesizer or generate a documented RPM/load loop bank with an MIT tool such as DasEtwas/enginesound. MIT governs the upstream code and included materials; it does not automatically declare every generated output to be MIT-licensed or guarantee that third-party presets/input material are clear. Preserve the upstream notice when code or MIT-covered configuration is incorporated, retain the exact generator revision/configuration, and use only project-created or separately cleared inputs.

### 2. Sonniss GameAudioGDC bundles

[Sonniss GDC bundles](https://sonniss.com/gameaudiogdc/) contain professional recordings; a prior bundle included a Soundholder engine sampler with several fixed-RPM loops. The [GDC bundle license](https://sonniss.com/gdc-bundle-license/) allows game/mobile/interactive synchronization and modification but prohibits standalone redistribution. Licensed sounds may be synchronized and embedded as functional application content, but editing, compression, looping, or other processing does not make a sound freely redistributable by itself. Keep original and processed source files out of public Git, retain a dated license copy and download provenance, and do not use the bundle for AI/ML training.

### 3. Soundholder Mazda RX-8 RENESIS

[Soundholder RX-8](https://sonniss.com/sound-effects-tag/rx8/) was observed with storefront prices of USD 80 list / USD 56 sale; prices, tax, and currency display can change. It has 129 WAV files, interior/designed/onboard/exhaust/intake perspectives, ramps, and fixed RPM material suitable for a high-rev presentation. The [Sonniss standard license](https://sonniss.com/license/) permits commercial synchronization and editing but not standalone redistribution of original or modified source sounds.

Because this product is an aftermarket real-car sound generator rather than a conventional game, obtain written Sonniss confirmation before production use. Suggested clearance wording: "aftermarket Android in-car, pedal-controlled real-time engine sound application." Preserve the invoice and applicable license revision, verify current user/workstation or seat terms, and restrict source-library access accordingly. Only application-embedded deliverables should enter a distributable build or artifact repository.

### 4. Soundholder Game Audio Engines

The broader [Soundholder engine collection](https://sonniss.com/sound-effects-tag/loops/) was observed near USD 250 and contains many vehicles with idle, RPM loop/ramp, and perspective coverage. Apply the same Sonniss licensing caveat and keep source WAVs out of Git.

### 5. Krotos Igniter

[Krotos Igniter](https://www.krotosaudio.com/products/igniter/) is professional vehicle authoring software; the storefront displayed roughly USD 491 including VAT during research, but regional pricing and tax can change. Its [product manual](https://www.lootaudio.com/_media/images/sampleism/Krotos%20Audio/Igniter/Igniter%2BManual.pdf) describes game/film use of designed output. Render a custom RPM/load bank offline and never bundle the authoring plugin or source library. Because this is an automotive sound-generator product rather than a conventional game, obtain written permission for the exact use before bundling rendered output.

### 6. Crankcase Audio REV

[Crankcase Audio REV](https://www.crankcaseaudio.com/) is AAA runtime/authoring middleware with acceleration/deceleration recordings, shifts, clutch, torque, and engine braking. [Published pricing](https://www.crankcaseaudio.com/pricing) was roughly USD 4,500 for a first runtime platform plus USD 4,500 for authoring. Android is supported; automotive likely requires custom terms.

## Assets to avoid without custom terms

- Skril Studio's [Rotary X8 documentation](https://www.skrilstudio.com/Docs/Rotary%20X8%20-%20Vol.%201%20Engine%20Sound%20Documentation.pdf) and [Realistic Engine Sounds 2 Plus documentation](https://skrilstudio.com/Docs/Realistic%20Engine%20Sounds%202%20Plus%20Edition%20Documentation.pdf) explicitly prohibit use in real products such as car/toy sound generators. This project matches the prohibited example; do not use even the free edition without bespoke written permission.
- [BOOM Library Cars V8](https://www.boomlibrary.com/sound-effects/cars-v8/) is high quality, but [BOOM's standard terms](https://www.boomlibrary.com/terms-conditions/) prohibit this kind of real-time audio-generator exploitation; obtain an Audio Developer/custom license.
- [Unity Universal Sound FX](https://marketplace.unity.com/packages/audio/sound-fx/universal-sound-fx-17256) contains useful fixed-RPM material, but [Imphenzia's terms](https://www.imphenzia.com/license-terms) prohibit sound-generator uses without a separate agreement.
- Libraries sold through A Sound Effect require case-by-case review. Its [standard EULA](https://cloud.asoundeffect.com/Businesses/A%20Sound%20Effect/ASE_EULA.pdf) restricts preinstalled-hardware uses and products primarily consisting of sound effects; obtain custom written clearance before treating a pack as suitable for this app.
- FMOD example media is not redistributable under [FMOD's legal terms](https://www.fmod.com/legal); automotive/simulator/embedded use is [custom-licensed](https://www.fmod.com/licensing).
- Any repository without an explicit license is study-only.

## Android audio evidence

- [AOSP multichannel support](https://source.android.com/docs/core/audio/implement-policy#multi-channel_support) states unsupported multichannel content is downmixed to stereo.
- [AAOS audio policy configuration](https://source.android.com/docs/automotive/audio/audio-policy-configuration) and [multizone routing](https://source.android.com/docs/automotive/audio/audio-multizone-routing) show that `AudioAttributes`/context and OEM policy select output buses/zones. Applications do not name individual cabin speakers.
- [Android AudioRouting](https://developer.android.com/reference/android/media/AudioRouting) documents routed/preferred device behavior; a preferred device is not a physical-speaker selector.
- [Android low-latency guidance](https://developer.android.com/ndk/guides/audio/opensl/opensl-prog-notes#performance) recommends matching the native rate/burst and avoiding conversions.
- [Android audio focus guidance](https://developer.android.com/media/optimize/audio-focus) requires respecting loss/duck events.
- [AOSP audio debugging](https://source.android.com/docs/core/audio/debugging) documents `dumpsys media.audio_flinger` for route/mixer diagnosis.
- [Oboe low-latency guidance](https://developer.android.com/games/sdk/oboe/low-latency-audio) is the likely future native-audio path. Java `AudioTrack` remains simpler for the current API-25 logical-channel experiment.

The renderer now gates startup on a granted focus request, updates diagnostic state on focus changes, smoothly ducks/mutes, and releases focus on shutdown or permanent loss. On-car testing must still verify the OEM focus policy alongside navigation, calls, ADAS, and warning audio; an application-level focus implementation cannot prove those system interactions by itself.

The `mobile` module compiles against SDK 37 but deliberately targets SDK 25 to remain compatible with the legacy DiLink vendor framework and hidden boot-classpath API. This is a sideload-only engineering choice, not a Play-distribution configuration. Modern test devices may reject installation unless ADB's explicit low-target-SDK bypass is used.

## Licensing decision for the current build

No third-party sound recording, engine middleware, Unity/Unreal asset, or external simulator code is included. The app ships project-written procedural PCM and an AI-generated fictional car concept. Generation provenance is recorded in [the source-material log](source-material/README.md#generated-ui-asset), but generation does not guarantee exclusivity, copyrightability, or freedom from design/trademark similarity; the image remains a prototype pending review.

The repository currently has no root code or asset license. Public visibility does not itself authorize reuse, modification, or redistribution. Before accepting external reuse or contributions, choose explicit code terms and separately state the artwork terms. Any future commercial or free sample-library content must remain governed by its own license and provenance record rather than inheriting the repository license.
