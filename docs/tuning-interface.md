# Live tuning interface

The dashboard's **TUNE** button opens a full-screen workstation designed for the head unit's 1920 x 990 safe viewport. Changes are applied to the 200 Hz simulation/audio pipeline immediately and saved automatically in app-private `SharedPreferences`. **RESET** restores the original 10,000 RPM-tach Apex V10 baseline (8,600 RPM redline, 8,850 RPM fuel cutoff); **CLOSE** returns to driving without discarding edits.

## Editable controls

### Engine

- Tachometer maximum, idle, redline, fuel cutoff, automatic upshift, and downshift RPM
- Peak torque, engine rotational inertia, vehicle mass, and wheel radius
- Live torque/power graph with a current-RPM cursor

The tachometer scale, red zone, perfect-shift band, dashboard label, limiter, and shift logic all use the same live configuration. Tachometer maximum, redline, and limiter remain separate values, matching the original profile.

### Curves

- Nine-point normalized torque curve
- Five-point pedal-to-requested-torque curve
- Throttle attack/release and brake response time constants
- Live response preview for all three pedal filters

Curve points are edited directly by dragging them. Their horizontal order is constrained, throttle endpoints remain fixed at 0/0 and 100/100, and every edit passes through the same sanitizer used when a saved profile is loaded. The live vertical cursor shows current engine RPM or pedal input.

### Gearing

- Final-drive ratio
- Upshift/downshift duration and post-shift dwell
- Every ratio in the seven-speed gearbox
- Computed RPM-after-shift graph for all adjacent gear changes

Adjacent gear bounds prevent an invalid inverted ratio stack. The graph updates as either the gear ratios or upshift RPM change.

### Audio

- Master gain
- Exhaust, intake, mechanical, overrun, and shift-impact layers
- Second through fifth firing-order harmonics
- Live spectral contribution graph

Values are smoothed inside the real-time renderer, so dragging a slider does not introduce an abrupt block-level volume discontinuity.

## Persistence and safety

`TuningRepository` serializes every scalar, gear ratio, and curve point. Loaded and incoming values are bounded before reaching the simulation or audio thread. The explicit BYD vehicle-input safety behavior is unchanged: unavailable vehicle telemetry resolves to zero pedals rather than a stale touchscreen value.

The controls affect the virtual drivetrain used by the emulator and the synthesized engine response. They do not write to any BYD vehicle ECU or alter the real car's throttle, braking, or transmission.

## Emulator validation

The interface was installed and exercised on the accelerated 1920 x 1080 emulator with a 1920 x 990 application viewport. All four panels fit without clipping. Curve dragging was tested, the process was force-stopped and relaunched, and the edited curve reloaded from persistent storage. The profile was reset to the original 10,000 RPM-tach calibration afterward, and the emulator was left running the app.
