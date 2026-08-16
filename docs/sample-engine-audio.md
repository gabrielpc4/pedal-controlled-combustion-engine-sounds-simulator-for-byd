# Profile-based sample engine audio

## Status

The app is sample-only and offers five selectable interior engine profiles: Lamborghini Huracán Super Trofeo EVO2, Ferrari F430 GT2, Ferrari 812 N-Largo, BMW M8 Coupé, and Lamborghini Aventador SV. There is no procedural renderer, fallback sound, preview player, or perspective selector. Missing or invalid required WAVs put audio in a visible `ERROR` state and persist `sample_engine_load_failed`.

An `EngineSampleProfile` owns the identity, preview, authored output sample rate, native RPM domain, idle/redline/limiter, simulated gearbox calibration, asset directory, and every sample layer. The arrows beside the dashboard car select the adjacent profile, persist its ID, apply its gearbox/RPM defaults, and restart the audio renderer against only that bank. Selection and loading are recorded as `car_profile_changed`, `audio_profile_selected`, and `sample_engine_loaded` events.

## Local assets and licensing boundary

The original bank and decoded recordings live under the ignored directory:

`audio_samples/<source-car-folder>`

Each supported folder has a local `converted` directory. The build copies only the continuous engine streams named in `mobile/build.gradle.kts` into the corresponding generated `assets/sample_engine/<profile>/` directory. It also copies `preview1.jpg` to `assets/car_previews/`; the 812 pack has no root `preview1.jpg`, so its skin preview is the explicit fallback. Throttle-lift/FOT one-shots, turbo, transmission, ignition, tire, wind, and other environmental noises are excluded. The older Supra experiment is intentionally not in the selectable catalog.

Extraction uses the official `vgmstream-cli` decoder. Each asset filename begins with its FSB5 subsong index. Preserve the original single-play duration and append available loop metadata with:

```powershell
vgmstream-cli.exe -i -L -s 39 -o converted\s039_hur_c1.wav sfx\fx_lamborghini_huracan_trofeo_evo2.bank
```

Repeat for the indices named by `EngineSampleProfile.kt`/`mobile/build.gradle.kts`: `10, 31, 32, 37, 38, 39, 44, 49, 59, 61, 65, 73, 77, 78, 81, 89, 93, 113, 117, 126, 127, 134, 139, 149`. Do not extract with the default two-loop/fade playback duration, because that bakes duplicate audio and a fade into the runtime source.

Neither banks, decoded audio, nor source preview images are committed. The supplied mods do not grant a standalone-application redistribution license. Do not publish an APK containing these recordings or images without permission from their rights holders.

### Profile reconstruction confidence

The Huracán profile is the exact recovered FMOD control graph described below. The F430, BMW, and Aventador profiles use recovered continuous interior stream names/root RPMs plus their source-car RPM/gear data, then use a generic adjacent-band crossfade where the original route automation was not recoverable. The 812 bank strips stream names; its continuous roots are recovered, but load/coast roles cannot be proven, so those voices use a neutral throttle blend rather than invented coast assignments. These distinctions are deliberate and remain visible in source rather than being presented as equally exact bank reconstructions.

## Recovered bank model

The source is an FMOD bank version `0x50` containing 165 streams, 20 events, 198 instruments, 42 parameters, and 382 automation curves. The selected engine event uses:

- RPM parameter `0..10000`;
- throttle parameter `0..2` (the app drives its normal `0..1` pedal portion);
- the 24 continuous cabin waveform layers packaged by this build;
- independent load, high-load, coast, texture, idle, and limiter routing;
- per-instrument RPM trigger regions, base levels, pitch offsets, and optional autopitch roots;
- per-instrument RPM amplitude and decibel automation;
- route-level throttle-to-decibel automation;
- embedded WAV `smpl` loop points.

`EngineSampleProfile.kt` is the durable numeric reconstruction. Values were decoded from the bank event/controller graph rather than inferred from WAV filenames. The renderer linearly interpolates recovered control points. FMOD curve tangent/shape metadata is not yet emulated, so transition curvature can still differ slightly from the original middleware.

The profile uses source-car data for idle (`1040 RPM`), limiter (`8350 RPM`), seven adjacent presentation ratios (`3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84`), and source shift durations (`60 ms` up, `150 ms` down). The bank axis remains `0..10000 RPM`; it is not stretched to the limiter. The normal automatic shift target is `8200 RPM`, ahead of the hard limiter and inside the limiter-layer transition.

## Realtime rendering

1. All WAVs required by the selected profile decode before `AudioTrack` starts. Both source channels are preserved and rendered as a true-stereo engine program at that profile's authored 44.1 or 48 kHz rate.
2. Simulation and bank use one RPM axis. There is no redline remapping.
3. Each layer evaluates its own RPM amplitude curves, RPM decibel curves, throttle route, base gain, pitch root, and base pitch.
4. Each layer owns a persistent fractional cursor. Cubic interpolation handles varispeed and 44.1-to-48 kHz conversion.
5. The decoder honors embedded `smpl` start/end points. The pre-loop intro plays once, then only the authored loop segment wraps.
6. Timelines advance while inaudible, matching an always-running FMOD event and avoiding restarts when a fade reopens.
7. Layer, enable, focus, and master gains are smoothed. Calibrated mix headroom protects the output, while samples inside the PCM range remain linear and unchanged; limiting occurs only on a genuine full-scale overload.

### Load versus lift-off tone

The authored throttle routes deliberately crossfade between different recordings. Around 7,000 RPM, full load is dominated by `l2a`, `l2a_high`, `n_up`, and `sine`; lift-off instead emphasizes `c1`, `c2`, and `n2`. The original load group contained more noisy, overlapping high-frequency material, while the coast group had fewer and more tonally coherent harmonic loops. Consequently, lift-off sounded clearer and subjectively louder even when telemetry reported a lower PCM peak. The app now retains the C1/C2 tonal loops at a restrained `-9 dB` under load and reduces the always-running `engine_noise_7` layer by `3.1 dB`. This narrows the tonal difference without eliminating the intended load/coast response.

The audio thread performs no file I/O or persistent logging. It publishes bounded diagnostics consumed by the 200 Hz drive controller once per second.

## Persistent telemetry

`drive_heartbeat` includes:

- profile status and decoded layer count;
- simulation RPM, requested sample RPM, rendered (smoothed) RPM, and delta;
- rendered throttle;
- the strongest active layer IDs with playback-rate and gain percentages;
- rendered frames, authored loop wraps, peak, and pre-limiter over-range count;
- startup and steady-state `AudioTrack` underruns.

One-time events include `sample_engine_loaded`, `sample_engine_load_failed`, and `audio_track_active`, including profile ID, source format, and native RPM domain.

The log is `/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log`. For a debug install:

```powershell
adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
```

## Code-driven on-device validation

The diagnostics screen has `RUN AUDIO TEST`. It can also start without UI input:

```powershell
adb shell am force-stop com.gabrielpc.enginesoundsimulator
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity --ez run_sample_audio_validation true
```

The sequence selects simulator input, Drive, and sound, then applies 25%, 55%, 100%, and released-throttle stages. A valid run shows `sample_status=ACTIVE`, `sample_loops=24`, increasing frames/wraps, changing target/render RPM and active layers, and no renderer exception.

## Automated coverage

`SampleEngineRendererTest` checks all selectable profile IDs/previews/rates and full-load/lift-off audibility every 25 RPM from idle to limiter. It additionally checks the fully reconstructed interior profile, recovered load/coast throttle direction, lossless stereo preservation, `smpl` metadata after the data chunk, direct profile RPM mapping, logical multichannel mapping, an end-to-end sweep with runtime telemetry, and fail-closed behavior for an incomplete bank.

The APK must also be inspected for every generated profile asset because unit tests deliberately use generated signals rather than copyrighted local recordings.
