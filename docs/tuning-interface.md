# Live tuning interface

The dashboard's **TUNE** button opens a full-screen workstation designed for the head unit's 1920 x 990 safe viewport. Changes are applied to the 200 Hz simulation/audio pipeline immediately and saved automatically in app-private `SharedPreferences`. **RESET** restores the Seal-response and synthetic-sound defaults; **CLOSE** returns to driving without discarding edits.

The interface presents torque in kgfm and power in HP. Displayed and edited values are converted at the UI boundary; saved calibration values and drivetrain calculations remain in SI units, so changing the presentation units does not alter the vehicle model.

## Editable controls

### Vehicle

- Synthetic tachometer maximum, idle, redline, sound limiter, automatic upshift, minimum downshift floor, and RPM response time
- Official peak motor torque/power plus A2MAC1 front/rear peak wheel torque
- Motor maximum speed, fixed electric reduction, drivetrain efficiency, and traction ceiling
- Vehicle mass, rotating-mass factor, wheel radius, drag area, rolling resistance, and top speed
- Live combined wheel-torque/power graph with a road-speed cursor

The tachometer scale, red zone, perfect-shift band, dashboard label, limiter, and presentation-shift logic all use the same live synthetic configuration. Tachometer maximum, redline, and limiter remain separate values. Physical wheel torque is computed from the electric settings and is independent of the synthetic gears.

### AWD curves

- Twelve-point front wheel-torque curve digitized from the A2MAC1 trace
- Twelve-point rear wheel-torque curve digitized from the A2MAC1 trace
- Derived front/rear distribution graph, rising from approximately 56% to 71% rear by default

Curve points are edited directly by dragging them. Their horizontal order is constrained and every edit passes through the same sanitizer used when a saved profile is loaded. Both axle graphs use normalized road speed horizontally and wheel torque vertically. The configured motor-power/efficiency ceiling remains active as a sanity bound.

### Response

- Six-point Sport-like pedal-to-requested-motor-torque curve
- Throttle attack/release and brake response time constants
- Lift-off RPM retention and decay response
- Live response preview for all pedal filters and the lift-off RPM fall

Throttle endpoints remain fixed at 0/0 and 100/100. The 120 ms default attack is informed by the full-request torque rise visible in A2MAC1's acceleration chart, but the pedal map itself remains an approximation because BYD does not publish it.

### Gearing

- Synthetic final-drive ratio
- Upshift/downshift duration and post-shift dwell
- Every ratio in the seven-speed presentation gearbox
- Computed shift-landing graph; every landing RPM is also that higher gear's automatic downshift point

Adjacent gear bounds prevent an invalid inverted ratio stack. The graph updates as either the gear ratios or upshift RPM change. These controls affect sound RPM and shift events only; a ratio edit cannot change electric acceleration.

### Audio

- Master gain
- Exhaust, intake, mechanical, overrun, and shift-impact layers
- Second through fifth firing-order harmonics
- Live spectral contribution graph

Values are smoothed inside the real-time renderer, so dragging a slider does not introduce an abrupt block-level volume discontinuity.

## Persistence and safety

`TuningRepository` serializes every scalar, gear ratio, and curve point. Loaded and incoming values are bounded before reaching the simulation or audio thread. The explicit BYD vehicle-input safety behavior is unchanged: unavailable vehicle telemetry resolves to zero pedals rather than a stale touchscreen value.

The controls affect the virtual EV road model and the synthesized engine response. They do not write to any BYD vehicle ECU or alter the real car's throttle, braking, or fixed-ratio drive units.

## Emulator validation

The interface was installed and exercised on the accelerated 1920 x 1080 emulator with a 1920 x 990 application viewport. All four panels fit without clipping. Curve dragging was tested, the process was force-stopped and relaunched, and the edited curve reloaded from persistent storage. The profile was reset to its defaults afterward, and the emulator was left running the app.
