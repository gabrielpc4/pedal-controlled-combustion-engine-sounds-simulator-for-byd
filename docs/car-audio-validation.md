# Car audio validation

Validation is performed against the original Assetto installation and Audio Lab, then by a
controlled manual drive cycle on Android. Persistent Android test sources and fixtures are not
part of the repository; temporary probes belong outside the checkout and are deleted after use.

## Package and catalog checks

```sh
python3 tools/build_fmod_bank_packs.py --force
python3 tools/validate_car_audio.py
```

The generated index must contain 23 active `original_cars_pack` entries, each with its own bank,
preview, and physics metadata. It currently also contains 33 independently installable modded
entries; they are outside the original-bank validation sweep. Confirm that the selected group
publishes only verified packages and that selecting a missing pack reports an error.

## Android manual cycle

Install the dashboard and installer on the target emulator or head unit, install the original pack,
and open each car in both CABIN and EXTERIOR. The former tuning and override controls are removed;
use the natural automatic mode. For each car, accelerate until about two authored gear changes occur,
release the throttle, and observe the RPM returning to the authored idle. Record only aggregate
observations needed to reproduce a failure.

Useful runtime evidence is available through the debug-only ADB capture: selected profile,
raw/presentation speed, RPM, gear, physical FMOD parameters, active event/source names,
voice lifecycle, and load failures. Check that engine voices belong to the selected engine event,
intentionally disabled events are labelled as such, and the mixer meters reflect FMOD audibility
rather than car master volume.

The emulator verifies loading and lifecycle. A real-car listening pass remains necessary for cabin
acoustics, speaker routing, and perceived pitch.

## Controlled debug capture

The high-rate capture is deliberately absent from release builds and is dormant in a debug build
until enabled over ADB. It uses bounded pre-allocated rings, so it does not add per-frame Logcat
formatting or storage I/O to the audio worker:

```sh
adb shell am broadcast -a com.gabrielpc.enginesoundsimulator.action.DEBUG_TELEMETRY \
  --es command SCENARIO --es profile assetto-ferrari-488-gtb
adb shell am broadcast -a com.gabrielpc.enginesoundsimulator.action.DEBUG_TELEMETRY \
  --es command STOP
```

The exported directory contains compact metadata plus simulation, audio-consumption, native
lifecycle, and event-catalog CSV files. Keep those raw files outside Git. The inventory compiler
imports them only after validating the exact profile, bank SHA-256, and complete selected-car event
catalog. Valid events from a shared original dependency bank are checked against the global GUID
map and excluded from the selected car's source attribution.

The first controlled sweep imported one capture for each of the 23 original profiles. It found no
exact-zero PCM stream in the direct bank audit; a voice with zero audibility was therefore
classified as virtual, route-zero, or authored automation rather than automatically reported as a
missing sample. The persistent findings and their evidence boundary are in
`docs/original-cars-exceptions.md`.

For CPU measurements, the debug receiver also accepts `PERF_START`, `PERF_STATUS`, and
`PERF_STOP`. These report simulation and FMOD worker CPU time, control duration, setter batches,
and mixer snapshot cost without enabling the full trace. Measure the Android process with
`adb shell top -H` and the emulator/QEMU process separately on the host.
