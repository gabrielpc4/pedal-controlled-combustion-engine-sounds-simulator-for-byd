# Sample-based engine audio

## Status

The app has two engine program sources:

- `SAMPLE` is the default when the complete local cabin loop set is packaged.
- `SYNTH` is always available and is also the automatic fallback when any required sample cannot be decoded.

The source selector is in the dashboard header. The diagnostics screen reports requested and active source independently, so `SYNTH FALLBACK` cannot be mistaken for a working sample bank.

## Asset handling and licensing boundary

The source recordings and the extracted FMOD bank are not committed. `audio_samples/` is ignored by Git. A local build copies only the 23 engine-only cabin WAV files from:

`audio_samples/Toyota_Supra_MK4/converted`

into a generated build asset directory. Limiter, turbo, shift, transmission, fuel-pump, fan, tyre, wind, body, ignition, and shutdown recordings are excluded.

The public source tree contains the renderer and numeric playback manifest but no audio payload. The current mod package does not include a standalone-application redistribution license. Do not publish an APK containing these recordings without written permission from the rights holder.

## Runtime model

`SampleEngineRenderer` reconstructs the cabin engine event without linking FMOD:

1. Twenty-three PCM16 WAV files are decoded before the `AudioTrack` starts. Stereo sources are downmixed to the common mono engine program because the car output path mirrors that program through the negotiated logical channels.
2. Simulated engine RPM is mapped proportionally to the bank-authored 0–8,000 RPM parameter range.
3. Each loop has its recovered trigger range, autopitch root, and base pitch offset.
4. Adjacent RPM instruments overlap with normalized equal-power fades. Normalization prevents a level hole where asymmetric recovered regions overlap or end.
5. The bank-authored load, coast, and extra-body throttle gain points are interpolated in decibels. Load and coast remain concurrent through the pedal transition.
6. Each loop owns a persistent fractional cursor. Cubic cyclic interpolation performs both 44.1-to-48 kHz conversion and RPM varispeed without buffer-boundary resets.
7. Layer, enable, and focus gains are smoothed. The final mix has fixed headroom and a soft limiter.

Only active/fading voices are sampled. Audio rendering performs no file I/O or logging. Diagnostics snapshots are generated at a bounded cadence; the 200 Hz drive thread persists them once per second.

## Persistent telemetry

The ordinary `drive_heartbeat` now includes:

- active audio source;
- mapped audio RPM;
- decoded loop count;
- load and coast gain in tenths of a decibel;
- active loop IDs with pitch and gain percentages;
- rendered frame and loop-wrap counters;
- peak level and pre-limiter over-range counter;
- startup and subsequent `AudioTrack` underruns separately.

One-time events include `sample_engine_loaded`, `sample_engine_fallback`, `engine_sound_mode_changed`, and `audio_track_active` with the active program source.

The log remains at:

`/data/user/0/com.gabrielpc.enginesoundsimulator/files/diagnostics/drive-events.log`

Read it from a debuggable installation with:

```powershell
adb shell run-as com.gabrielpc.enginesoundsimulator cat files/diagnostics/drive-events.log
```

## Code-driven on-device validation

The diagnostics screen has `RUN AUDIO TEST`. The same deterministic validation can be started through ADB without touching the UI:

```powershell
adb shell am force-stop com.gabrielpc.enginesoundsimulator
adb shell am start -n com.gabrielpc.enginesoundsimulator/.MainActivity --ez run_sample_audio_validation true
```

The sequence selects simulator input, Drive, the sample source, and engine audio, then applies 25%, 55%, 100%, and released-throttle stages. It records `sample_validation_started`, each stage, ordinary one-second heartbeats, and `sample_validation_finished` with final counters. A valid run must show:

- `audio_source=SAMPLE`;
- `sample_loops=23`;
- increasing `sample_frames` and `wraps`;
- changing `audio_rpm` and active layers;
- load gain increasing under throttle and coast gain taking over after release;
- `steady_underruns=0` after the captured startup baseline;
- no fallback or renderer exception.

## Automated tests

`SampleEngineRendererTest` verifies:

- unique 23-file engine-only manifest;
- continuous load and coast coverage through the authored RPM range;
- no throttle dead zone;
- PCM16 stereo decoding/downmix;
- an end-to-end code-driven RPM/throttle sweep with audible output, active-layer telemetry, loop wraps, and bounded output.

The final APK must also be inspected for all 23 generated `assets/sample_engine/*.wav` entries because JVM tests intentionally do not depend on copyrighted local files.
