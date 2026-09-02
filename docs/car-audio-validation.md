# Car audio validation

Validation is performed against the original Assetto installation and Audio Lab, then by a
controlled manual drive cycle on Android. Persistent Android test sources and fixtures are not
part of the repository; temporary probes belong outside the checkout and are deleted after use.

## Package and catalog checks

```sh
python3 tools/build_fmod_bank_packs.py --force
python3 tools/validate_car_audio.py
```

The generated index must contain 22 active `original_cars_pack` entries, each with its own bank,
preview, and physics metadata. The 33 prepared modded entries must be inactive. Confirm that the
installer publishes only the active group and that selecting a missing pack reports an error.

## Android manual cycle

Install the dashboard and installer on the target emulator or head unit, install the original pack,
and open each car in both CABIN and EXTERIOR. The former tuning and override controls are removed;
use the natural automatic mode. For each car, accelerate until about two authored gear changes occur,
release the throttle, and observe the RPM returning to the authored idle. Record only aggregate
observations needed to reproduce a failure.

Useful runtime evidence is available in logcat: selected profile, raw/presentation speed, RPM,
gear, physical FMOD parameters, active event/source names, and load failures. Check that engine
voices belong to the selected engine event, disabled events do not appear, and the mixer meters
reflect FMOD audibility rather than car master volume.

The emulator verifies loading and lifecycle. A real-car listening pass remains necessary for cabin
acoustics, speaker routing, and perceived pitch.
