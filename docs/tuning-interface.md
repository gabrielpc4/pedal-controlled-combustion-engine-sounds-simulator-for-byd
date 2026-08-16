# Live tuning interface

The dashboard's **TUNE** button opens a full-screen workstation designed for the head unit's 1920 x 990 safe viewport. Changes are applied to the 200 Hz simulation/audio pipeline immediately and saved automatically in app-private `SharedPreferences`. **RESET** restores the Seal-response and selected sample-profile defaults; **CLOSE** returns to driving without discarding edits.

The interface presents torque in **≈ motor-shaft kgfm** and power in values labeled **HP** that use **metric PS/cv** arithmetic. Every visible readout uses whole numbers; normalized controls use percentages, wheel radius uses millimetres, and drag area uses square centimetres. Displayed and edited values are converted at the UI boundary; saved calibration values and drivetrain calculations remain in SI units (Nm, kW), so changing the presentation units does not alter the vehicle model. See [UI display and simulation decisions](ui-display-and-simulation-decisions.md) for the full rationale.

## Editable controls

### Vehicle

- Synthetic tachometer maximum, idle, redline, sound limiter, independent **MAX RPM FORCE**, **LIFT-OFF RPM FORCE**, and **BRAKE RPM FORCE**, plus **FULL PEDAL SWEET SPOT** and **FULL PEDAL KICK** controls
- Official peak motor torque/power plus A2MAC1 front/rear peak wheel torque
- Motor maximum speed, fixed electric reduction, drivetrain efficiency, and traction ceiling
- Vehicle mass, rotating-mass factor, wheel radius, drag area, rolling resistance, and top speed
- Live combined wheel-torque/power graph with a road-speed cursor, landmark-based axis ticks, per-point value/X labels, and separately labelled ≈ motor kgfm / ≈ motor HP Y axes

The tachometer scale, red zone, dashboard label, and limiter use the same live synthetic configuration. Tachometer maximum, redline, and limiter remain separate values. Full pedal rapidly reaches the sweet spot, then the response curve deliberately slows the upper-range climb. Physical wheel torque is computed from the electric settings and is independent of the fake tach.

### AWD curves

- Twelve-point front wheel-torque curve digitized from the A2MAC1 trace
- Twelve-point rear wheel-torque curve digitized from the A2MAC1 trace
- Derived front/rear distribution graph, rising from approximately 56% to 71% rear by default

Curve points are edited directly by dragging them. Their horizontal order is constrained and every edit passes through the same sanitizer used when a saved profile is loaded. Axle graphs show ≈ motor kgfm above each point and road speed below; the distribution graph shows front/rear share % above and km/h below. The configured motor-power/efficiency ceiling remains active as a physics sanity bound.

### Response

- Six-point Sport-like pedal-to-requested-motor-torque curve
- Throttle attack/release and brake response time constants
- In SIM mode, displayed speed follows normalized fake RPM, including the fast lift-off/brake fall
- Live response preview for attack, release, and brake exponentials, with per-marker % labels and millisecond X labels

Throttle endpoints remain fixed at 0/0 and 100/100. The 120 ms default attack is informed by the full-request torque rise visible in A2MAC1's acceleration chart, but the pedal map itself remains an approximation because BYD does not publish it.

Synthetic RPM in **D** is force-integrated and independent of road speed. Pedal percentage pushes it upward, lift-off pulls it down, and brake intensifies that fall. Unlimited virtual gears shift at the editable shift RPM and land at the sweet spot; there is no ratio stack or top gear. **N**/**P** retain the target-based free-rev model documented in [§3.2](ui-display-and-simulation-decisions.md#32-p--n--d-shifter-2026-08).

## Dashboard controls (outside TUNE)

These affect runtime behavior immediately but are **not** persisted in `TuningRepository`:

| Control | Location | Effect |
| --- | --- | --- |
| **P / N / D** shifter | Column beside pedals | Selects `TransmissionPosition`; **D** enables the independent direct force-integrated RPM model |
| **INPUT** | Header | Cycles AUTO / SIM / BYD LIVE |
| **ENGINE AUDIO** | Header | Mutes/unmutes sample playback |
| **CH OUTPUT** | Header | Cycles logical channel layout |

See [UI display and simulation decisions §3.2](ui-display-and-simulation-decisions.md#32-p--n--d-shifter-2026-08) for neutral steady-state and inertia timing.

### Audio

- Master gain
- Selected car-profile identity and native RPM domain
- Live coverage graph for idle, load, coast, texture, and limiter layers
- Bank-authored RPM and throttle automation remains fixed in the code profile so it cannot be accidentally desynchronized from the recordings

Values are smoothed inside the real-time renderer, so dragging a slider does not introduce an abrupt block-level volume discontinuity.

## Persistence and safety

`TuningRepository` serializes every scalar and curve point. Loaded and incoming values are bounded before reaching the simulation or audio thread. The explicit BYD vehicle-input safety behavior is unchanged: unavailable vehicle telemetry resolves to zero pedals rather than a stale touchscreen value.

The controls affect the virtual EV road model and sample-engine response. They do not write to any BYD vehicle ECU or alter the real car's throttle, braking, or fixed-ratio drive units.

## Emulator validation

The interface was installed and exercised on the accelerated 1920 x 1080 emulator with a 1920 x 990 application viewport. All four panels fit without clipping. Curve dragging was tested, the process was force-stopped and relaunched, and the edited curve reloaded from persistent storage. The profile was reset to its defaults afterward, and the emulator was left running the app.
