# FMOD bank calibration

This project keeps the original bank as the audio authority. Adding a car is therefore an event
compatibility and presentation-drive task, not an export or loop-calibration task.

1. Add one `FmodBankProfile` with the user-supplied display name, preview, RPM range, and
   presentation gears. Do not infer an audio relationship from a similar model name.
2. Add the exact source-bank mapping to `tools/build_fmod_bank_packs.py`. Include `GUIDs.txt` when
   the source provides it. A shared package is allowed only after comparing source-bank SHA-256.
3. Keep the original `common.strings.bank` and `common.bank` installer packages available. They
   provide event names and shared FMOD dependencies for official banks that ship without a local
   `GUIDs.txt`. Open the car bank with Audio Lab or the Android validation test and confirm
   `engine_int` exists. `engine_ext`, transmission, turbo, limiter, gear, backfire, and start are
   used only if that same car bank exposes the matching event.
4. Keep the direct presentation RPM route. Whole-km/h BYD values must first pass through
   `QuantizedPresentationSpeedEstimator`; do not set an FMOD `rpms` parameter from raw telemetry.
5. Build packages, install them, and run `CarAudioRuntimeValidationTest`. It exercises rapid
   continuous RPM and throttle changes in both perspectives through the production JNI bridge.

If a bank is silent, missing `engine_int`, requires an unsupported custom plugin, or has a
documented source-event anomaly, record that exact bank and event in `new-cars-exceptions.md`.
Do not hide it with decoded audio or a guessed replacement.
