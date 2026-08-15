# Live tuning interface

The dashboard's **TUNE** button opens a full-screen workstation designed for the head unit's 1920 x 990 safe viewport. Changes are applied to the 200 Hz simulation/audio pipeline immediately and saved automatically in app-private `SharedPreferences`. **RESET** restores the Seal-response and synthetic-sound defaults; **CLOSE** returns to driving without discarding edits.

## Editable controls

### Vehicle

- Synthetic tachometer maximum, idle, redline, sound limiter, automatic upshift, downshift RPM, and RPM response time
- Peak motor torque and power, motor maximum speed, fixed electric reduction, drivetrain efficiency, and traction ceiling
- Vehicle mass, wheel radius, drag area, rolling resistance, and top speed
- Live motor torque/power graph with a cursor derived from road speed and actual motor reduction

The tachometer scale, red zone, perfect-shift band, dashboard label, limiter, and presentation-shift logic all use the same live synthetic configuration. Tachometer maximum, redline, and limiter remain separate values. Physical wheel torque is computed from the electric settings and is independent of the synthetic gears.

### Curves

- Nine-point normalized EV motor torque curve
- Six-point Sport-like pedal-to-requested-motor-torque curve
- Throttle attack/release and brake response time constants
- Live response preview for all three pedal filters

Curve points are edited directly by dragging them. Their horizontal order is constrained, throttle endpoints remain fixed at 0/0 and 100/100, and every edit passes through the same sanitizer used when a saved profile is loaded. The motor graph's live cursor is based on road speed; the pedal graph shows current pedal input. The configured peak-power ceiling remains active even if a torque point is dragged above the physical constant-power envelope.

### Gearing

- Synthetic final-drive ratio
- Upshift/downshift duration and post-shift dwell
- Every ratio in the seven-speed presentation gearbox
- Computed RPM-after-shift graph for all adjacent gear changes

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
