# Profile-based sample engine audio

## Status

The app is sample-only. The initial profile reconstructs the cabin engine event from `fx_lamborghini_huracan_trofeo_evo2.bank`; there is no procedural renderer or fallback sound. Missing or invalid required WAVs put audio in a visible `ERROR` state and persist `sample_engine_load_failed`.

The implementation is designed for additional cars. An `EngineSampleProfile` owns the identity, native RPM domain, idle/redline/limiter, simulated gearbox calibration, asset directory, and every sample layer. Adding a car means adding a profile and its local assets, not modifying the realtime mixer.

## Local assets and licensing boundary

The original bank and decoded recordings live under the ignored directory:

`audio_samples/fx_lamborghini_huracan_trofeo_evo2`

Twenty-four continuous cabin-engine streams are extracted to its `converted` directory. The build copies only those named files into `assets/sample_engine/lamborghini_huracan_trofeo_evo2/`. Throttle-lift one-shots, alternates, turbo, transmission, ignition, and environmental noises are excluded.

Extraction uses the official `vgmstream-cli` decoder. Each asset filename begins with its FSB5 subsong index. Preserve the original single-play duration and append available loop metadata with:

```powershell
vgmstream-cli.exe -i -L -s 78 -o converted\s078_hur_idle_low.wav sfx\fx_lamborghini_huracan_trofeo_evo2.bank
```

Repeat for the indices named by `EngineSampleProfile.kt`/`mobile/build.gradle.kts`: `10, 31, 32, 37, 38, 39, 44, 49, 59, 61, 65, 73, 77, 78, 81, 89, 93, 113, 117, 126, 127, 134, 139, 149`. Do not extract with the default two-loop/fade playback duration, because that bakes duplicate audio and a fade into the runtime source.

Neither the bank nor decoded audio is committed. The supplied mod does not grant a standalone-application redistribution license. Do not publish an APK containing these recordings without permission from the recording/mod rights holder.

## Recovered bank model

The source is an FMOD bank version `0x50` containing 165 streams, 20 events, 198 instruments, 42 parameters, and 382 automation curves. The internal/cabin engine event uses:

- RPM parameter `0..10000`;
- throttle parameter `0..2` (the app drives its normal `0..1` pedal portion);
- 29 RPM instruments, of which 24 continuous engine layers are used;
- independent load, high-load, coast, texture, idle, and limiter routing;
- per-instrument RPM trigger regions, base levels, pitch offsets, and optional autopitch roots;
- per-instrument RPM amplitude and decibel automation;
- route-level throttle-to-decibel automation;
- embedded WAV `smpl` loop points.

`EngineSampleProfile.kt` is the durable numeric reconstruction. Values were decoded from the bank event/controller graph rather than inferred from WAV filenames. The renderer linearly interpolates recovered control points. FMOD curve tangent/shape metadata is not yet emulated, so transition curvature can still differ slightly from the original middleware.

The profile uses source-car data for idle (`1040 RPM`), limiter (`8350 RPM`), seven ratios (`3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84`), final drive (`3.96`), and source shift durations (`60 ms` up, `150 ms` down). The bank axis remains `0..10000 RPM`; it is not stretched to the limiter. The normal automatic shift target is `8200 RPM`, ahead of the hard limiter and inside the limiter-layer transition.

## Realtime rendering

1. All 24 PCM16 WAVs decode before `AudioTrack` starts. Stereo sources are downmixed to one engine program, later mirrored to the negotiated logical cabin channels.
2. Simulation and bank use one RPM axis. There is no redline remapping.
3. Each layer evaluates its own RPM amplitude curves, RPM decibel curves, throttle route, base gain, pitch root, and base pitch.
4. Each layer owns a persistent fractional cursor. Cubic interpolation handles varispeed and 44.1-to-48 kHz conversion.
5. The decoder honors embedded `smpl` start/end points. The pre-loop intro plays once, then only the authored loop segment wraps.
6. Timelines advance while inaudible, matching an always-running FMOD event and avoiding restarts when a fade reopens.
7. Layer, enable, focus, and master gains are smoothed. Fixed mix headroom and a final soft clipper protect the output.

The audio thread performs no file I/O or persistent logging. It publishes bounded diagnostics consumed by the 200 Hz drive controller once per second.

## Persistent telemetry

`drive_heartbeat` includes:

- profile status and decoded layer count;
- simulation RPM, requested sample RPM, rendered (smoothed) RPM, and delta;
- rendered throttle;
- the strongest active layer IDs with playback-rate and gain percentages;
- rendered frames, authored loop wraps, peak, and pre-limiter over-range count;
- startup and steady-state `AudioTrack` underruns.

One-time events include `sample_engine_loaded`, `sample_engine_load_failed`, and `audio_track_active`, including profile ID and native RPM domain.

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

The sequence selects simulator input, Drive, and sound, then applies 25%, 55%, 100%, and released-throttle stages. A valid run shows `sample_status=ACTIVE`, `sample_loops=24`, increasing frames/wraps, changing target/render RPM and active layers, `steady_underruns=0`, and no renderer exception.

## Automated coverage

`SampleEngineRendererTest` checks unique 24-file profile integrity, full-load and lift-off audibility through idle-to-limiter, recovered load/coast throttle direction, stereo downmix, `smpl` metadata after the data chunk, direct profile RPM mapping, an end-to-end sweep with runtime telemetry, and fail-closed behavior for an incomplete bank.

The APK must also be inspected for all 24 generated profile assets because unit tests deliberately use generated signals rather than copyrighted local recordings.
