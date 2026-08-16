# Live tuning interface

The dashboard's **TUNE** button opens a full-screen workstation designed for the head unit's 1920 x 990 safe viewport. Changes are applied to the 200 Hz simulation/audio pipeline immediately and saved automatically in app-private `SharedPreferences`. **RESET** restores the Seal-response and synthetic-sound defaults; **CLOSE** returns to driving without discarding edits.

The interface presents torque in **≈ motor-shaft kgfm** and power in values labeled **HP** that use **metric PS/cv** arithmetic. Every visible readout uses whole numbers; normalized controls use percentages, wheel radius uses millimetres, drag area uses square centimetres, and ratios use an integer `n:100` form. Displayed and edited values are converted at the UI boundary; saved calibration values and drivetrain calculations remain in SI units (Nm, kW), so changing the presentation units does not alter the vehicle model. See [UI display and simulation decisions](ui-display-and-simulation-decisions.md) for the full rationale.

## Editable controls

### Vehicle

- Synthetic tachometer maximum, idle, redline, sound limiter, and automatic upshift
- **DRIVE RPM** sliders: max rise rate, lift-off fall, brake extra fall, launch full-power speed threshold
- Live combined wheel-torque/power graph with a road-speed cursor, landmark-based axis ticks, per-point value/X labels, and separately labelled ≈ motor kgfm / ≈ motor HP Y axes

Seal Performance calibration (mass, drag, axle peaks, motor rating, top speed, etc.) remains stored in `TuningRepository` and powers the graph plus EV physics, but is **not** exposed as editable sliders.

The tachometer scale, red zone, perfect-shift band, dashboard label, limiter, and presentation-shift logic all use the same live synthetic configuration. Tachometer maximum, redline, and limiter remain separate values. Physical wheel torque is computed from the hidden electric settings and is independent of the synthetic gears.

### AWD curves

- Twelve-point front wheel-torque curve digitized from the A2MAC1 trace
- Twelve-point rear wheel-torque curve digitized from the A2MAC1 trace
- Derived front/rear distribution graph, rising from approximately 56% to 71% rear by default

Curve points are edited directly by dragging them. Their horizontal order is constrained and every edit passes through the same sanitizer used when a saved profile is loaded. Axle graphs show ≈ motor kgfm above each point and road speed below; the distribution graph shows front/rear share % above and km/h below. The configured motor-power/efficiency ceiling remains active as a physics sanity bound.

### Response

- Six-point Sport-like pedal-to-requested-motor-torque curve
- Throttle attack/release and brake response time constants
- Simulator coast regen (SIM mode only) — constant lift-off deceleration for virtual speed
- Live response preview for attack, release, and brake exponentials, with per-marker % labels and millisecond X labels

Throttle endpoints remain fixed at 0/0 and 100/100. The 120 ms default attack is informed by the full-request torque rise visible in A2MAC1's acceleration chart, but the pedal map itself remains an approximation because BYD does not publish it.

### Gearing

- Synthetic final-drive ratio
- Upshift/downshift duration and post-shift dwell
- Every ratio in the seven-speed presentation gearbox
- Computed shift-landing graph with shift-event X axis, landing-RPM Y axis, and labelled downshift threshold; every landing RPM is also that higher gear's automatic downshift point

Adjacent gear bounds prevent an invalid inverted ratio stack. The graph updates as either the gear ratios or upshift RPM change. These controls affect sound RPM and shift events only; a ratio edit cannot change electric acceleration.

Synthetic RPM in **D** is a throttle-driven force integrator scaled by the wheel-power curve, with lift-off fall at the configured coast rate (see [§3.3](ui-display-and-simulation-decisions.md#33-d-mode-throttle-driven-rpm-2026-08)). **N**/**P** use the free-rev model in [§3.2](ui-display-and-simulation-decisions.md#32-p--n--d-shifter-2026-08).

## Dashboard controls (outside TUNE)

These affect runtime behavior immediately but are **not** persisted in `TuningRepository`:

| Control | Location | Effect |
| --- | --- | --- |
| **P / N / D** shifter | Column beside pedals | Selects `TransmissionPosition`; only **D** enables auto-shifts and throttle-driven RPM |
| **INPUT** | Header | Cycles AUTO / SIM / BYD LIVE |
| **ENGINE AUDIO** | Header | Mutes/unmutes synthesis |
| **CH OUTPUT** | Header | Cycles logical channel layout |

See [UI display and simulation decisions §3.2](ui-display-and-simulation-decisions.md#32-p--n--d-shifter-2026-08) for neutral steady-state and inertia timing.

### Audio

- Master gain
- Exhaust, intake, mechanical, overrun, and shift-impact layers
- Second through fifth firing-order harmonics
- Live spectral contribution graph with harmonic X axis and gain-percentage Y axis

Values are smoothed inside the real-time renderer, so dragging a slider does not introduce an abrupt block-level volume discontinuity.

## Persistence and safety

`TuningRepository` serializes every scalar, gear ratio, and curve point. Loaded and incoming values are bounded before reaching the simulation or audio thread. The explicit BYD vehicle-input safety behavior is unchanged: unavailable vehicle telemetry resolves to zero pedals rather than a stale touchscreen value.

The controls affect the virtual EV road model and the synthesized engine response. They do not write to any BYD vehicle ECU or alter the real car's throttle, braking, or fixed-ratio drive units.

## Emulator validation

The interface was installed and exercised on the accelerated 1920 x 1080 emulator with a 1920 x 990 application viewport. All four panels fit without clipping. Curve dragging was tested, the process was force-stopped and relaunched, and the edited curve reloaded from persistent storage. The profile was reset to its defaults afterward, and the emulator was left running the app.
