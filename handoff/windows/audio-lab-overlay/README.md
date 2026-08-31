# Assetto Corsa + Forza Horizon 6 virtual powertrain labs

This workspace now contains two independent local applications:

- **Assetto Corsa 2014** — the working native FMOD 1.08 car-audio and recovered
  automatic-assist simulator described below. Launch with `run.cmd` on port
  8765.
- **Forza Horizon 6** — a new stock 1998 Toyota Supra RZ MKIV virtual
powertrain, realtime vehicle-adapter contract, predictive speed/RPM path,
P/R/N/D gearbox, drag-and-hold BYD Seal AWD pedal simulation with lift-off
regeneration, and installed-asset fidelity gate. Launch with `run-fh6.cmd`
  on port 8766.

The FH6 app is intentionally honest about its current audio boundary: the
installed granular recordings use proprietary method-22 TransformIT archives,
and no substitute audio is played until the local decoder cache and compatible
FMOD renderer pass validation. The input, gearbox, event routing, dashboard,
archive audit/cache and verification suite are runnable now.

```powershell
.\run-fh6.ps1
```

See [the FH6 implementation and fidelity gate](docs/FORZA_HORIZON_6_POWERTRAIN.md).

---

# Assetto Corsa 2014 — native powertrain audio and automatic tachometer

This is a standalone local driving simulator for the installed **Assetto Corsa car catalog**. Select a car in the header; it uses
that car's installed physics data and loads its unmodified FMOD bank through
the same FMOD Studio 1.08.12 runtime shipped with Assetto Corsa 1.16.4. Hold
the throttle and the car launches in first, operates its six-speed sequential
gearbox with AC's automatic-gearbox assist, and drives the original powertrain
sound events.

The Tatuus remains the strongest verified reference because Kunos ships its editable
FMOD source with separate interior/exterior engine and backfire events, turbo,
limiter, gear changes, and transmission whine. The lab deliberately focuses on
that core powertrain mix. It does not play tyre, wheel, brake, chassis, damage,
skid, or traction-control events.

## Run it

Double-click **`run.cmd`**, or run:

```powershell
.\run.ps1
```

The server discovers the Steam installation, opens
`http://127.0.0.1:8765/`, and begins in first gear at the Tatuus's 1,250 RPM
idle, matching an on-track/race-start session. Controls:

- Hold the on-screen throttle, `Space`, or `W` to accelerate automatically
  through all six gears.
- Hold the brake, `S`, or `Down Arrow` to slow down and invoke automatic
  downshifts. Both pedals also have analog sliders.
- Press `1`, `2`, or `3` for cockpit, bonnet, and exhaust listening positions;
  press `M` to mute.
- Select any installed car from the header. The engine, tachometer, ratios,
  automatic-shift thresholds, driven axle and available native core events are
  rebuilt on the dedicated physics/audio thread.

The digital throttle uses AC's recovered 4.0/s held-key rise and instantaneous
key-up release. The analog slider supplies immediate values and can cross the
game's positive lift-off window used to trigger backfires.

If discovery fails:

```powershell
.\run.ps1 --assetto-root "D:\Program Files (x86)\Steam\steamapps\common\assettocorsa"
```

Diagnostics:

```powershell
# UI and physics without opening an audio device
.\run.ps1 --no-audio

# Test the recovered state machines and API
python -m unittest discover -v
```

No Python packages, Node modules, or browser-audio permission are required.

## What “automatic” means in Assetto Corsa

This is AC's `AUTO_SHIFTER` assist operating each car's own gearbox,
not a generic torque-converter automatic. The implementation follows the
recovered final-executable order:

```text
Autoclutch → AutoBlip → AutoShifter → GearChanger → Drivetrain
```

Ratios, final drive, shift durations, auto-shift RPM thresholds, clutch,
autoclutch and autoblip profiles come from the selected car. The recovered
strict clutch/slip/cutoff gates and neutral-between-ratios lifecycle are shared
with AC's final executable.

An accepted request fires the original gear sound and immediately enters
neutral. The target engages only after AC's strict fixed-step timer: 132 ms
for an upshift and 183 ms for a downshift at 3 ms per physics tick. Upshifts
apply the Tatuus's 150 ms drivetrain engine cut and the assist's 280 ms gas
cut. Downshifts use the installed 10/190/250 ms clutch curve and 0.7 autoblip.

AC's assist has no ordinary low-speed neutral-to-first launch rule; surrounding
race/session code normally supplies first gear. Accordingly, the lab starts in
first rather than inventing a different AutoShifter branch.

## Core sound mix

The simulator discovers the following core events by path in each selected
bank and runs whichever the author supplied:

- `engine_int` / `engine_ext`: separate looping interior and exterior graphs,
  including the bank's engine/exhaust layers, RPM pitch regions, load/coast
  blends, EQ, compression, filters, and 3D processing;
- `turbo`: persistent authored turbo layer driven by normalized physical boost;
- `limiter`: persistent limiter event driven by AC's decay-reset lifecycle;
- `backfire_int` / `backfire_ext`: five interior and nine exterior randomized
  one-shots—the pops, bangs, and cracks—with bank-authored level and pitch;
- `transmission`: persistent transmission whine driven by the raw drivetrain
  angular velocity in radians per second and post-assist throttle;
- `gear_int` / `gear_ext`: camera-selected up/down shift sounds, triggered when
  the gear request is accepted with the original FMOD `state` mapping.

The Tatuus turbo event exposes BOV controls but its authored BOV mixer lane has
no sample. The simulator preserves that silence instead of substituting an
unrelated blow-off recording.

## The three listening positions

- **Cockpit** uses the persistent `engine_int` event and camera-selected
  interior backfire/gear sounds.
- **Bonnet** uses the 3D `engine_ext` event with the listener at the exact
  `BONNET_CAMERA_POS` from `car.ini`.
- **Exhaust** keeps the same continuous `engine_ext` instance and moves the
  listener 0.82 m behind the rear-engine anchor. AC has no separate exhaust
  engine event; the bank's spatialiser and cone response create the change.

Switching cockpit/exterior follows AC: stop the excluded engine instance with
`ALLOWFADEOUT`, start the selected one, and continue feeding both parameter
sets. Moving bonnet-to-exhaust changes only the listener, so the engine loops
do not restart.

## Fidelity boundary

The audio runtime, installed bank, recordings, event graphs, parameter curves,
random multisounds, and camera routing are native. The automatic-assist rules,
gear-request lifecycle, fixed-step timers, gas/engine cuts, clutch profile,
autoblip, limiter, turbo/BOV, and backfire detector are recovered from the
user's final `acs.exe`/PDB and run at 333⅓ Hz.

The closed full vehicle solver cannot be linked as a library. Road speed is
therefore produced by a bounded straight-line solver using the installed mass,
ratios, wheel inertias/radii, torque and throttle LUTs, rolling resistance,
brake data, and aero coefficient LUTs. Contact-patch/clutch constraints,
dynamic tyre radius/load sensitivity, ride-height aero, fuel mass, and full
suspension/rigid-body coupling remain approximations. The API exposes this
honestly as `vehicleDynamicsExact: false`; it does not weaken the recovered
automatic-shift state machine or native FMOD mix.

See [the technical reconstruction](docs/ASSETTO_CORSA_AUDIO.md) for the event
graph, verified values, equations, and exactness boundary.

The private, lossless Android-pack workflow is documented separately in
[the local pack compiler guide](docs/ASSETTO_CORSA_PACK_COMPILER.md). It covers
the 178-car/153-family catalog, strict `.aclib` contract, silent commands,
regression evidence, and the source-instrument fidelity gate.

## Ownership and privacy

This repository contains no Kunos bank, WAV, or extracted asset. It reads each
installed `data.acd` and loads `common.bank`, `common.strings.bank`, and the
selected car bank in place.
The server binds only to `127.0.0.1` and sends nothing over the internet.
