# Car audio validation

The audio library is tested in two layers so a quick control change cannot
hide a broken car:

1. The authoring check uses Audio Lab's headless FMOD renderer. For every car
   bank it changes RPM and throttle eight times in 1.5 seconds, at 53.3 ms
   intervals, and captures both `engine_int` and `engine_ext`. The report records
   RMS, peak, non-silent segments, hard-clipping ratio, scheduled sound names,
   and any FMOD exception.
2. The Android instrumentation check loads every installed pack one at a time
   and renders 24 stereo blocks while RPM and throttle change rapidly. It logs
   one line per selectable profile under the `CarAudioRuntimeValidation` tag,
   including peak, RMS, non-silent block count, and the largest sample-to-sample
   step. Shared-pack aliases are exercised through their resolved owner pack.

## Reproduce the authoring check

Run from the repository root on Apple Silicon with the local x86 FMOD API:

```sh
FMOD_API_ROOT="/Users/gabrielcarvalho/Downloads/FMOD Programmers API/api" \
/usr/bin/arch -x86_64 /usr/bin/python3 tools/validate_car_audio.py
```

The JSONL report is written to the ignored path
`build/validation/car-audio.jsonl`. A zero exit status means there are no
unexpected silent or failed captures. `warning_clipping` is a report-only
warning for a saturated source capture; the Android renderer still applies its
headroom limiter and the WAV itself is checked separately.

## Latest bank audit

The completed audit covered 58 selectable profiles and 111 validations:

| Result | Count | Meaning |
| --- | ---: | --- |
| `ok` | 92 | Audible and stable under the rapid trajectory |
| `warning_clipping` | 16 | FMOD source reached the hard-clip threshold; no capture failed |
| `expected_silence` | 3 | The three documented external routes that are silent in the source bank |
| `silent` / `error` / `short_or_unstable` | 0 | No new unclassified failure |

The three expected external silences are Aston Martin DBS, Corvette C6 Z06
Stanced, and Corvette C7 Stingray HellSpec. Their documented interior fallback
keeps the exterior selector audible. The source-side clipping warnings are not
silence: they are retained for listening review instead of silently changing
the supplied recordings.

After installing all packs, run the runtime measurement with:

```sh
./gradlew :mobile:connectedDebugAndroidTest --no-daemon
adb -s emulator-5554 logcat -d -s CarAudioRuntimeValidation:I '*:S'
```

The companion installer must be used first because the main APK intentionally
contains no generated WAV payloads.

The latest emulator run produced 58 log lines, one for each selectable profile;
all 58 rendered 24/24 non-silent blocks. The logged peak, RMS, and
`maxFrameStep` values are intentionally retained in logcat for comparison when
testing a new pack or a new renderer change.
