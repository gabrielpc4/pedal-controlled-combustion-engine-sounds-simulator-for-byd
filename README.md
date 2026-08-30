# Engine Sounds Simulator for BYD

A private Android dashboard experiment for a BYD Seal DiLink head unit. It turns read-only
vehicle signals, or built-in simulator pedals, into a fictional combustion-engine tachometer and
FMOD-native car audio. It does not control the vehicle.

The app supports five locally supplied Assetto Corsa bank profiles:

- Nissan Skyline GT-R R34
- Lamborghini Huracan Trofeo EVO2
- Lamborghini Aventador SV
- Alfa Romeo 4C
- Toyota Supra MK4

Each profile has an explicit cockpit/powertrain event allowlist. Tire, skid, road, wind, chassis,
bodywork, collision, door, horn, and other incidental events are never instantiated. Some banks
author shifts, limiter, start, shutdown, or backfire inside `engine_int`; the UI exposes a mixer
control only when a sound has a separately controllable event. **Load Only** matches the desktop
audio lab by holding engine and transmission audio throttle at one. The default remains the bank's authored
pedal-controlled load/coast blend; **Coast Only** instead holds that one engine parameter at zero.
Turbo, backfire detection, tach, and EV torque always retain real inputs.

The native compatibility layer preserves the old banks' expected DSP names and serialized state.
Its fixed-cabin Distance Filter stores the authored parameters and 3D attributes while passing PCM
through; Gain applies its dB and phase-invert controls with a short smoothing ramp, and Distortion
applies input amplification followed by hard clipping.

Assetto's engine-speed parameters generally have no authored seek smoothing. The app therefore
presents RPM and transmission speed through a 400 Hz, allocation-free control path with a 20 ms
critically damped audio-only follower. Unlike the previous first-order filter, it preserves pitch
velocity across each new 200 Hz source target instead of merely producing fractional positions
with a stepped slope. In authored blend mode, engine and transmission audio throttle also use a
35 ms attack/120 ms release crossfade so an instantaneous EV pedal release cannot hard-swap
dissimilar load/coast layers. Device FMOD evaluates Studio commands synchronously on the 400 Hz
owner thread and uses 64-frame blocks on a 1.33 ms mixer quantum; four buffers retain a 5.33 ms
queued depth. The audio worker also drops missed submission deadlines rather than replaying them
back-to-back. The audio presentation limits each delivered RPM change to the 400 Hz pitch bound
and each turbo-boost change to 0.005, so a late worker wakeup creates audio catch-up rather than a
larger or coalesced bank-parameter step. This avoids Android's observed 15–55 ms asynchronous parameter
batching and reduces mixer-bound pitch zippering without adding combustion lag to the BYD
simulation or delaying event edges.
The other confirmed staircase source was road-speed quantization. Simulator physics stays
continuous `Double` data through RPM and transmission control. For the real car's whole-km/h
signal, the app measures the time between integer boundary crossings and reconstructs fractional
motion on every 5 ms controller tick, including bounded prediction across a late 20 ms vendor poll.
Engine and transmission controls consume that reconstructed state; the dashboard still shows the
unmodified whole-km/h reading. Fractional telemetry, if a future firmware supplies it, bypasses
quantizer reconstruction.

The app is designed for the 1920 x 990 safe dashboard area observed on the rotating head unit.
FMOD output is stereo; speaker distribution beyond Android's stream belongs to the vehicle DSP.

## Start here

Durable engineering context is in [docs/README.md](docs/README.md):

- [Architecture](docs/architecture.md)
- [Vehicle integration and local assets](docs/vehicle-integration-and-assets.md)

Those documents record the read-only/privacy boundaries, exact bank inputs and hashes, profile
contracts, and the required rendered-audio verification process. The current code and tests remain
the source of truth.

## Local prerequisites

The app module is `mobile`. Building requires the official **FMOD Studio API 1.10.11 for
Android**, the user's unmodified Assetto Corsa installation for the common/R34/Alfa banks, and
three user-supplied car folders under ignored `audio_samples/`. Add the two machine paths to the
ignored root `local.properties`; forward slashes avoid Java-properties escaping on Windows:

```properties
assettoCorsa.dir=D:/Program Files (x86)/Steam/steamapps/common/assettocorsa
fmod.sdk.dir=C:/path/to/fmodstudioapi11011android
```

Required ignored local folders:

```text
audio_samples/fx_lamborghini_huracan_trofeo_evo2/
audio_samples/tr_lamborghini_aventador_sv/
audio_samples/Toyota_Supra_MK4/
```

Gradle checks the FMOD version and every bank hash before staging `fmod.jar`, headers, native
libraries, and uncompressed banks under `mobile/build/generated/`. FMOD SDK files, game/mod
content, previews, decoded samples, and generated artifacts remain local and untracked. There is
no WAV or synthetic-audio fallback.

## Build and verify

```powershell
$env:JAVA_HOME = '<Android Studio JBR path>'
.\gradlew.bat :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest :mobile:lintDebug --no-daemon
```

Assembly produces a locally numbered debug APK under
`mobile/build/outputs/apk/debug/engine-sounds-simulator-build-<number>-debug.apk`.

For device verification, start the Android Virtual Device whose id is
`BYD_Seal_1920x1080`, then run:

```powershell
.\gradlew.bat :mobile:connectedDebugAndroidTest --no-daemon
adb install --bypass-low-target-sdk-block -r mobile\build\outputs\apk\debug\engine-sounds-simulator-build-<number>-debug.apk
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity
```

For every profile, the required acceptance pass must exercise deterministic idle, load, coast,
shift, lift/backfire, limiter, turbo where applicable, and transmission where applicable while
checking FMOD's rendered PCM and event/sample callbacks—not merely successful event calls. The
automated render uses a deterministic `NOSOUND_NRT` FMOD system: it proves that the allowlisted bank
graph produces finite, non-silent PCM without sending that audio to Android speakers. Connected
Android verification is still required for each new change. The current build passed its JVM,
lint, debug/app-test assembly, all 16 connected tests on `BYD_Seal_1920x1080`, and five-profile NRT
PCM/callback gates. A parked physical-BYD pass has not yet verified this build's speaker routing,
latency, factory-DSP balance, or cabin acoustics.

Use the app only while parked or in a controlled environment. Its audio can mask navigation,
alerts, and other safety cues.
