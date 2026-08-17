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

## A2MAC1 acceleration measurement

A2MAC1 published a chassis/road-test chart for a Seal AWD in its Energy Management Strategy material. The accompanying post reports 3.97 s to 100 km/h, 190 km/h maximum speed, 408 kW peak high-voltage battery power, 3,170 Nm peak front wheel torque, 3,975 Nm peak rear wheel torque, and a rear share that rises from about 55% to 71% before power reduction. [A2MAC1 source post](https://www.linkedin.com/posts/a2mac1_how-far-can-we-push-an-ev-to-uncover-its-activity-7335191005635260416-tG-Z)

Evidence policy:

- retain BYD's official 390 kW motor-output rating; A2MAC1's 408 kW is high-voltage battery input power and includes conversion/drivetrain losses;
- retain BYD's official 180 km/h limiter; the measured 190 km/h may reflect tolerance, test state, software, or a modified vehicle;
- use the separately measured front/rear wheel-torque traces because BYD does not publish those curves;
- target the observed approximately four-second acceleration without claiming every stock vehicle reproduces that exact run.

The graph was digitized against its printed axes. The default editable points are the following approximate values; small line thickness/noise and chart rasterization make them measurements of the chart, not raw A2MAC1 data:

| Road speed | Front wheel torque | Rear wheel torque | Rear share |
| ---: | ---: | ---: | ---: |
| 0–28 km/h | 3,170 Nm | 3,975 Nm | 55.6% |
| 58 km/h | 2,878 Nm | 3,953 Nm | 57.9% |
| 71 km/h | 2,418 Nm | 3,521 Nm | 59.3% |
| 83 km/h | 1,977 Nm | 3,070 Nm | 60.8% |
| 101 km/h | 1,458 Nm | 2,504 Nm | 63.2% |
| 115 km/h | 1,161 Nm | 2,197 Nm | 65.4% |
| 137 km/h | 844 Nm | 1,833 Nm | 68.5% |
| 155 km/h | 700 Nm | 1,583 Nm | 69.3% |
| 168 km/h | 604 Nm | 1,439 Nm | 70.4% |
| 180 km/h | 537 Nm | 1,324 Nm | 71.1% |

The simulator evaluates these as two independent normalized wheel-torque curves versus road speed. The resulting combined peak is 7,145 Nm. Because they are wheel torques, they are not multiplied again by the guessed reduction or drivetrain efficiency. A separate 390 kW motor-output/efficiency ceiling remains as a sanity bound.

This measured shape supersedes the earlier generic constant-torque/constant-power approximation for vehicle acceleration. It still preserves the correct EV behavior: immediate strong torque, a smooth decline with speed, and no dependency on fictional sound RPM or gears.

## Independent cross-checks found after the A2MAC1 calibration

The A2MAC1-derived model was checked against measurements from unrelated vehicles and test teams rather than being tuned only to one chart:

| Source | Independent result | How it is used |
| --- | --- | --- |
| Driving Enthusiast, Australian Performance AWD | 0–60 km/h 2.06 s; 0–100 km/h 3.96 s; 60–110 km/h 2.58 s; quarter mile 12.44 s at 178.3 km/h; peak 1.071 g | The first three values now have simulation regression bands. Quarter-mile and peak-g remain external checks because the compact model does not simulate rollout or test-device filtering. |
| km77, AWD at about 60% SOC | 0–100 km/h 3.8 s; 80–120 km/h 2.7 s | The 80–120 result now has a regression band and shows that useful performance is not confined to a 95% SOC launch. |
| IDA/VBox track test | 0–50 km/h 1.8 s; 0–100 km/h 4.13 s; 0–150 km/h 8.7 s; 80–120 km/h 2.6 s | Confirms the general low-speed and rolling-acceleration shape. Repeated runs slowed toward five seconds, evidence of thermal/SOC limiting that the current stateless curve does not model. |
| Driving Enthusiast / IDA braking | 100–0 km/h in 37.8 m / 35.9 m | The virtual full-brake model now has a 35–39 m regression band. This is service braking, not accelerator-lift regeneration. |

Sources:

- [Driving Enthusiast instrumented review](https://drivingenthusiast.com.au/2024/10/2024-byd-seal-performance-review-video/)
- [km77 instrumented driving impressions](https://www.km77.com/coches/byd/seal/2024/estandar/informacion/byd-seal-2024-impresiones-de-conduccion?amp=1)
- [IDA/VBox track test](https://blog.idaoffice.org/posts/byd-seal-and-530-horsepower-our-test-at-the-track/)

BYD's own owner manual defines a distinct full-throttle experience: at least 95% high-voltage battery SOC, SPORT mode, and the acceleration-timer page displayed. That is a documented condition for the headline launch, not evidence of the pedal curve itself. [Official BYD owner manual](https://www.byd.com/content/dam/byd-site/eu/support/service/manual/byd-seal/20231225/BYD%20SEAL%20Owner%27s%20Manual-Left-hand%20Drive-EN%2857.2M%29.pdf)

The agreement is strong enough to retain the A2MAC1 axle curves. It is not evidence that every firmware, battery state, tire, road, or temperature produces one invariant curve.

## Hardware details confirmed by deeper teardown and regulatory research

- The export AWD motor identifiers are front `YS210XYA` and rear `TZ200XYC`; the front machine is an AC induction/asynchronous motor and the rear is a permanent-magnet synchronous motor. The Australian vehicle approval and owner handbook are stronger evidence than reposts that incorrectly call both motors permanent-magnet machines. [Australian vehicle type approval](https://www.rover.infrastructure.gov.au/PublishedApprovals/VTADetails/RVDEngines/?apprversionid=a5edf1fd-a311-ef11-9f89-6045bdc40e28&id=9a129752-955b-ee11-8def-00224893b94f)
- A teardown of the rear 8-in-1 drive unit reports a 16,000 RPM rotor limit and a two-stage, three-shaft 10.81:1 reduction. This supports the values already exposed in the UI, but does **not** establish that the front drive unit has the same reduction. [Chinese reproduction of the Nikkei BP teardown](https://www.sohu.com/a/849102335_121124214)
- FEV's teardown summary reports 172 series-connected Blade cells and about 550 V nominal battery voltage. These facts help interpret A2MAC1's battery-side 408 kW measurement, but the present sound/vehicle-response model does not simulate pack voltage or current. [FEV/MarkLines benchmark summary](https://www.marklines.com/en/teardown/fev-byd-seal_ev_2023my_eu)
- Ono Sokki has measured mode-by-mode pedal opening versus driveshaft output, SOC-dependent output, chip-out deceleration, regeneration, road load, rolling resistance, efficiency, vibration, and motor sound on a 2022 China-spec AWD Seal. The numerical datasets are commercial and were not publicly accessible, so their existence must not be mistaken for recovered values. [Ono Sokki/MarkLines report catalog](https://www.marklines.com/ja/teardown/onosokki-byd-seal)

## What owner reports and reviews clarify — and what they do not

Multiple professional reviews and owner discussions independently describe a deliberately progressive power ramp, with Sport sharper than Normal/Eco but generally less abrupt than a Tesla. Some owners report a brief response delay, while others find Sport effectively immediate. Auto Hold, iTAC state, SOC, software version, and whether the car is already rolling plausibly explain some disagreement. Examples: [Autocar](https://open.em.autocar.co.uk/car-review/byd/seal), [Team-BHP](https://www.team-bhp.com/forum/electric-cars/279583-byd-seal-review.html), [Reddit owner discussion](https://www.reddit.com/r/BYD/comments/1c6l9ee/byd_seal_throttle_response/), and [later Reddit owner discussion](https://www.reddit.com/r/BYD/comments/1r8cr0z/byd_seal_excellence_strange_throttle_response_lag/).

These reports support a smoothed rather than step-like default. They do not provide timestamps, pedal traces, requested torque, or CAN logs, so they do not justify adding a guessed fixed launch delay. The 120 ms exponential attack remains tied to the roughly 0.3-second torque buildup visible in the supplied A2MAC1 plot; release timing remains an approximation.

BYD confirms that iTAC can transfer torque, reduce it, or command negative torque when managing slip, and that it uses motor-rotation sensing for finer detection than conventional wheel-speed-only control. The current front/rear curves describe the measured straight-line run, not iTAC's transient behavior on a changing-friction surface. [BYD iTAC technical explanation](https://www.byd.com/id/newsroom/byd-indonesia-kupas-tuntas-inovasi-teknologi-kendaraan-listrik-p)

The owner manual confirms Standard and High accelerator-lift regeneration modes but publishes no deceleration or torque numbers. Reviews consistently characterize even High as mild and not true one-pedal driving. Therefore accelerator-lift regen is still not assigned an invented factory-exact curve; brake-pedal input continues to control the app's explicit braking response.

## Road-load and acceleration assumptions

These values are derived or calibrated and are clearly exposed in the tuning UI:

| Setting | Default | Basis |
| --- | ---: | --- |
| Motor maximum speed | 16,000 RPM | Seal rear-drive-unit teardown reporting the rotor maximum; not needed to claim top-speed operation at exactly this RPM |
| Fixed motor reduction | 10.81:1 | Published teardown measurement of the rear three-shaft, two-stage drive unit; used as a combined-model approximation |
| Nominal wheel radius | 0.347 m | Calculated from the published 235/45 R19 tire size; loaded radius will be slightly smaller |
| Front/rear peak wheel torque | 3,170 / 3,975 Nm | A2MAC1 measurement |
| Drivetrain efficiency | 0.92 | Engineering assumption used only for the motor-power sanity ceiling because axle torque is already measured at the wheels |
| Maximum launch acceleration | 10.0 m/s² | Non-binding safety/tuning ceiling above the acceleration produced by the measured 7,145 Nm peak |
| Rotating-mass factor | 1.10 | Derived effective inertia needed to reconcile measured wheel torque with the chart's speed/time trace |
| Drag area, CdA | 0.504 m² | Published Cd 0.219 multiplied by an inferred 2.30 m² frontal area |
| Rolling resistance coefficient | 0.010 | Road-tire engineering assumption |
| Throttle attack/release | 120/90 ms | Full-request buildup informed by the measured torque rise; not a factory pedal map |

The fixed-step test reaches 100 km/h within 3.90–4.02 seconds under the configured full-throttle reference conditions, closely surrounding A2MAC1's measured 3.97 s. BYD's 3.8 s remains the official claim rather than something this one measured trace can invalidate. State of charge, battery and motor temperature, tire grip, road slope, air density, payload, software, and measurement method affect an actual vehicle.

The drivetrain still remains a compact longitudinal model. It now preserves the measured front/rear split and its speed-dependent change, but it does not model individual tires, axle slip, iTAC transients, suspension load transfer, battery voltage, temperatures, or lateral torque control. A later raw-data export from A2MAC1 or an instrumented stock car could replace the digitized points without changing the architecture.

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
- displayed RPM in **D** is derived from continuous road speed through the active presentation ratio and sound final drive;
- the whole-number BYD reading is smoothed before it can move the tach or sample playback.

Each selectable sound profile supplies its presentation ratios. SHORT uses five deliberately close
low-speed thresholds, ORIGINAL derives its thresholds from those ratios and normal shift RPM, and
HYBRID continuously blends the schedules according to throttle demand. The sound final drive affects
only presentation RPM; it does not feed torque back into the Seal road model.

Axle torque curves remain digitized against a **180 km/h** chart reference (`TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH`); raising the vehicle top-speed slider to 190 does not rescale measured wheel torque.

This preserves the enjoyable rise, shift, RPM drop, and sound progression of a game while removing clutch slip, bogging, combustion torque buildup, and shift interruption from the electric vehicle response.

## UI display layer (cosmetic only)

The tuning graphs and sliders do **not** show raw wheel torque or raw wheel power. Presentation conversions are intentional and documented in [UI display and simulation decisions](ui-display-and-simulation-decisions.md). Summary:

| Quantity | Stored / simulated | Shown in UI |
| --- | --- | --- |
| Torque | Wheel Nm from A2MAC1 curves | ≈ motor-shaft kgfm (`wheel Nm ÷ reduction`) |
| Power | Wheel kW from `P = T × ω` | Scaled to match `peakPowerKw`, then PS/cv labeled **HP** |
| Motor rating slider | `peakPowerKw` (default 390) | ~530 HP (PS/cv) at default |

The 390 kW motor rating and 670 Nm motor torque in persisted config remain the physics authority. The ~338 kW peak implied by wheel torque at ~76 km/h is the honest wheel-power peak; the UI scale exists so the graphed maximum aligns with the brochure rating.

## Verification encoded in tests

`EngineSimulationTest` checks:

- the published 670 Nm, 390 kW, 2,185 kg anchors, **190 km/h** presentation top speed, and the original **8,600 RPM** redline;
- the 3,170/3,975 Nm axle peaks, 7,145 Nm total, and approximately 56% to 71% rearward distribution change;
- stronger low-speed acceleration and a progressive high-speed taper;
- 0–100 km/h inside the 3.90–4.02 second calibration band;
- 0–60, 60–110, and 80–120 km/h split times against independent instrumented tests;
- 100–0 km/h full-service-brake stopping distance against two independent track measurements;
- no single-step wheel-torque interruption during a synthetic upshift;
- no first-gear RPM reversal at any tested positive pedal position;
- expected shift RPM drop, braking, live-speed synchronization, and limiter display hysteresis;
- `SyntheticGearboxCalibrationTest` verifies the derived ratios hit **8,600 RPM** at 190 km/h in 7th gear.

## Further source used for the reduction

The 10.81:1 reduction and 16,000 RPM rotor details come from reporting on a Nikkei BP teardown of the Seal AWD rear electric drive unit. This is useful physical evidence but is not an official BYD performance-map publication:

- [Chinese reproduction of the Seal electric-drive teardown](https://www.chezhubidu.com/mobile/getDetail/973180)
