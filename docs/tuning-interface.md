# Live tuning interface

The dashboard's **TUNE** action opens the 1920:990 tuning workstation. Changes are sent through the
local `DriveRuntimeService` binder, applied to the running 200 Hz core, and stored in app-private
preferences. Closing or backgrounding the Activity stops Compose sampling and presentation work; it
does not stop telemetry, the EV model, presentation gearbox, effects, or audio.

Displayed torque uses approximate motor-shaft kgfm and values labeled HP use metric PS/cv
arithmetic. Persisted calibration and drivetrain calculations remain SI (Nm, kW, metres, seconds).
Whole-number formatting and unit conversion occur only at the UI boundary. See
[UI display and simulation decisions](ui-display-and-simulation-decisions.md).

## Simulation controls

- Synthetic tach maximum, idle, redline, limiter, normal upshift RPM, and RPM response
- Imported presentation gearbox ratios and profile-specific up/down shift durations
- Seal front/rear/combined motor ratings and editable A2MAC1 wheel-torque curves
- Motor maximum speed, fixed EV reduction, efficiency, traction ceiling
- Vehicle mass, rotating-mass factor, wheel radius, drag area, rolling resistance, top speed
- SIM coast regeneration, throttle attack/release, and brake response
- Six-point Sport-like pedal-to-requested-torque curve
- Combined wheel-torque/power, AWD distribution, response, and layer-coverage graphs

The physical EV model remains independent of the imported combustion gears. In **D**, continuous
road speed is mapped through the selected car's real relative ratios. Top gear is scaled to reach
upshift RPM at configured top speed. **P** and **N** retain target-based free revving.

The higher gear's expected landing RPM is calculated as:

```text
landingRpm = upshiftRpm * nextRatio / currentRatio
```

On released throttle it downshifts at that value. The tuning UI must not add a 150 RPM offset or RPM
hysteresis. Shift dwell prevents overlapping events; kickdown and emergency upshift remain separate.

## Audio controls

The tuning/mixer presentation shows the installed car, its native RPM domain, master gain, and
available track coverage. Permitted continuous coverage can include:

- IDLE
- COAST
- TEXTURE
- INTAKE and EXHAUST character
- TURBO and SPOOL
- TRANSMISSION

Triggered availability can include BOV, limiter, up/down shifts, overrun, pops, bangs, and cracks.
Controls are hidden when the selected family does not provide the corresponding role.

`IDLE` is part of the normal program and follows the pack's authored RPM/gain curve. The excluded
`LOAD` role has no row, toggle, decode path, or alternate mix mode. Bank-authored curves are immutable
pack metadata so a gain edit cannot desynchronize pitch/RPM mapping.

Per-track mute/solo/gain and per-effect enable state remain car-specific. The engine/transmission
mute allows the enabled non-engine effects to be heard. Checked-effect isolation suppresses the
continuous engine/transmission program while retaining selected available effects. **Audition pops
and bangs** is shown only when the family provides them and sends the same trigger used by a natural
throttle-lift condition.

The renderer smooths control changes. Output is always PCM16, 48 kHz stereo; no channel-layout or
surround control exists.

## Catalog controls outside TUNE

The car selector is lazy and searchable across all 178 official/official-DLC entries. It shows
installed state, favorites, and imported thumbnails; the dashboard shows the selected-car image.
Favorites persist and sort ahead of non-favorites, but do not imply a pack is installed.

The Storage Access Framework imports a strict local `catalog-v1.json` and one or more private
`.aclib` sound-family packs. Import and validation run on a service-owned I/O worker, not the main or
audio thread. A selected installed car keeps playing until a newly selected family has fully decoded
and atomically swaps in.

## Dashboard controls outside TUNE

| Control | Effect |
| --- | --- |
| **P / N / D** | Select presentation transmission position; D is speed-coupled |
| **INPUT** | Cycle AUTO / SIM / BYD LIVE |
| **ENGINE AUDIO** | Smoothly mute/unmute the program |
| Car selector/search | Select an installed car; show installed/favorite/image state |
| Favorite | Persist/unpersist the selected catalog car as good |
| Effects/isolation/audition | Configure only effects supplied by the selected pack |

Touch/keyboard pedals release on Activity stop or window-focus loss. This prevents a simulated pedal
from sticking while another app is visible. Live BYD pedal and speed input continues in the service.

## Persistence and lifecycle

`TuningRepository` sanitizes and stores scalar/curve values. Car selection, favorites, per-track
mix, effect enable state, and sound enabled state use their own app-private repositories. Imported
catalogs/packs live in app-private files and are not APK resources.

While visible, the Activity requests one immediate state and then builds Compose-facing models at up
to 60 Hz. On stop it cancels the sampler plus meter, string, list, thumbnail, animation, and debug
work. Returning resumes presentation from current service state without resetting RPM, gear,
`AudioTrack`, or loop phase.

The controls alter only the virtual EV road model and combustion presentation. They do not write to
any BYD ECU or change the real car's throttle, brakes, or fixed-ratio drive units.

## Validation boundary

Host tests can validate sanitization, ratios, curves, selector/favorite ordering, lifecycle policy,
and fixed audio format. Emulator instrumentation is still required for actual Compose fit, SAF
interaction, visible-only sampling, and background/return UI behavior. The BYD head unit is required
for vendor telemetry, focus/DSP policy, latency, and cabin listening. Use
[the acceptance checklist](aclib-background-acceptance.md); do not treat a Gradle build as proof of
those device-specific gates.
