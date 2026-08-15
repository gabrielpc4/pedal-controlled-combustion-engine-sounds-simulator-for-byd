# BYD Seal Performance calibration

Last researched and implemented: 2026-08-15

This is the durable calibration record for the physical vehicle response used by the simulator. The target is the first-generation BYD Seal Performance/Excellence AWD sold in Brazil with the 82.56 kWh battery. The synthetic engine sound, tachometer scale, and seven presentation gears remain fictional and independently editable.

## What BYD publishes

The following are manufacturer figures, not estimates:

| Property | Calibration anchor |
| --- | ---: |
| Drivetrain | AWD, front induction motor plus rear permanent-magnet synchronous motor |
| Front motor | 160 kW, 310 Nm |
| Rear motor | 230 kW, 360 Nm |
| Combined maximum output | 390 kW, 670 Nm |
| 0–100 km/h | 3.8 s |
| Electronically limited top speed | 180 km/h |
| Brazilian/European curb mass | 2,185 kg |
| Tire | 235/45 R19 |
| Drag coefficient | 0.219 |
| Battery | 82.56 kWh |

Primary manufacturer sources:

- [BYD Brazil Seal model page](https://www.bydautomotive.com.br/modelos/byd-seal)
- [BYD global Seal flyer](https://www.byd.com/material/byd-site/america-public/flyer/seal-flyer-en-RHD-20230825.pdf)
- [BYD New Zealand March 2025 specification sheet](https://www.bydauto.co.nz/storage/uploads/a83c924d-b561-4a79-9ee6-fcbc34ddf8d7/BYD-SEAL-Spec-sheet_1st-March-2025.pdf)
- [BYD Australia Seal page](https://bydautomotive.com.au/seal)
- [BYD Seal owner manual](https://www.byd.com/content/dam/byd-site/eu/support/service/manual/byd-seal/20231225/BYD%20SEAL%20Owner%27s%20Manual-Left-hand%20Drive-EN%2857.2M%29.pdf)

The owner manual describes ECO, NORMAL, SPORT, and SNOW modes, but it does not publish the accelerator transfer table, motor-control current requests, torque ramp limits, or a complete torque-versus-speed dyno curve. Public reviews can describe Sport as sharper or note a small initial tip-in delay, but those observations are not a proprietary calibration map. Therefore the app must not label its editable Sport pedal curve as factory-exact.

Independent driving evidence is directionally consistent but not quantitative enough to recover that map:

- [Team-BHP's road impression](https://www.team-bhp.com/news/2024-byd-seal-our-observations-after-day-driving) describes Sport response as sharper while power delivery remains linear.
- [Autocar's road test](https://open.em.autocar.co.uk/car-review/byd/seal) reports that a full-pedal request is still ramped rather than applied as an instantaneous step.
- [An instrumented owner/track test](https://blog.idaoffice.org/posts/byd-seal-and-530-horsepower-our-test-at-the-track/) measured 0–50 km/h in 1.8 s and 0–100 km/h in about 4.1 s in one set of conditions, illustrating why an advertised reference run is not a complete response curve.

These are secondary observations with different vehicles, software, battery conditions, tires, and measurement methods. They support a prompt but smoothed default; they do not justify copying any exact point from the prose.

## Electric motor envelope

The published peak torque and peak power determine a physically consistent base-speed anchor:

```text
base motor speed = power / torque
                 = 390,000 W / 670 Nm
                 = 582.1 rad/s
                 = approximately 5,559 RPM
```

The default editable motor curve therefore holds 670 Nm from zero through normalized motor speed 0.347, then follows an approximately constant-power hyperbola to 16,000 RPM. `EngineSimulation.motorTorqueAtRpm` also applies an explicit 390 kW ceiling, so dragging a curve point upward cannot accidentally exceed the configured power limit.

```text
Motor RPM:       0   1600   3200   4800   5550   7200   9600  12800  16000
Torque factor: 1.0    1.0    1.0    1.0    1.0   .771   .578   .434    .347
Torque (Nm):    670    670    670    670    670    517    387    291     232
Power (kW):       0    112    225    337    389    390    389    390     389
```

This is an engineering reconstruction constrained by BYD's published maxima, not a leaked factory dyno table. It captures the defining EV behavior the app needs: maximum torque immediately at low motor speed, then decreasing torque as constant power takes over. Acceleration consequently starts strongest and tapers with road speed; it can no longer become artificially stronger merely because the sound tachometer passes a certain RPM.

## Road-load and acceleration assumptions

These values are derived or calibrated and are clearly exposed in the tuning UI:

| Setting | Default | Basis |
| --- | ---: | --- |
| Motor maximum speed | 16,000 RPM | Seal rear-drive-unit teardown reporting the rotor maximum; not needed to claim top-speed operation at exactly this RPM |
| Fixed motor reduction | 10.81:1 | Published teardown measurement of the rear three-shaft, two-stage drive unit; used as a combined-model approximation |
| Nominal wheel radius | 0.347 m | Calculated from the published 235/45 R19 tire size; loaded radius will be slightly smaller |
| Drivetrain efficiency | 0.92 | Engineering assumption for motor-shaft torque to tire force |
| Maximum launch acceleration | 8.0 m/s² | Effective traction/current-delivery ceiling calibrated with the published 3.8 s result |
| Drag area, CdA | 0.504 m² | Published Cd 0.219 multiplied by an inferred 2.30 m² frontal area |
| Rolling resistance coefficient | 0.010 | Road-tire engineering assumption |
| Throttle attack/release | 60/90 ms | Responsive Sport-like default; not a published BYD transfer function |

The fixed-step test reaches 100 km/h within 3.70–3.90 seconds under the configured full-throttle reference conditions. That is a calibration check, not a claim that the compact model reproduces every real run: state of charge, battery and motor temperature, tire grip, road slope, air density, payload, and BYD's front/rear torque allocation all affect an actual vehicle.

The AWD system has two different motor types and can actively redistribute torque. Combining both motors into one 670 Nm source and one reduction is intentionally a longitudinal approximation. A later instrumented on-car capture can replace the inferred curve and response filter without changing the architecture.

## Sport pedal default

The editable pedal request points are:

```text
Pedal:           0%   10%   25%   50%   75%  100%
Torque request:  0%   13%   31%   60%   84%  100%
```

This deliberately gives useful response early in the pedal while remaining monotonic and controllable. It is a Sport-like approximation, not a factory BYD curve. Once real pedal position and independently measured longitudinal acceleration can be logged safely on the target car, fit this table from measured data and preserve separate runs for battery state, temperature, speed, and road slope.

## Synthetic gears are not a transmission

The real Seal uses fixed-ratio single-speed electric drive units. In this app:

- motor torque, road force, acceleration, drag, and braking are calculated without the synthetic gear ratio;
- the seven editable ratios affect only the fictional engine RPM, sound, gauge, and shift event;
- a synthetic shift never cuts wheel torque, opens a clutch, adds rev-match torque, or changes vehicle acceleration;
- the displayed RPM follows road speed through the selected presentation ratio with a short editable response filter;
- braking reduces real/virtual road speed, and that lower speed then lowers synthetic RPM and selects lower presentation gears.

This preserves the enjoyable rise, shift, RPM drop, and sound progression of a game while removing clutch slip, bogging, combustion torque buildup, and shift interruption from the electric vehicle response.

## Verification encoded in tests

`EngineSimulationTest` checks:

- the published 670 Nm, 390 kW, 2,185 kg, and 180 km/h anchors;
- constant low-speed torque followed by a 390 kW high-speed envelope;
- stronger low-speed acceleration and a progressive high-speed taper;
- 0–100 km/h inside the 3.70–3.90 second calibration band;
- no single-step wheel-torque interruption during a synthetic upshift;
- no first-gear RPM reversal at any tested positive pedal position;
- expected shift RPM drop, braking, live-speed synchronization, and limiter display hysteresis.

## Further source used for the reduction

The 10.81:1 reduction and 16,000 RPM rotor details come from reporting on a Nikkei BP teardown of the Seal AWD rear electric drive unit. This is useful physical evidence but is not an official BYD performance-map publication:

- [Chinese reproduction of the Seal electric-drive teardown](https://www.chezhubidu.com/mobile/getDetail/973180)
