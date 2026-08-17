# UI display and simulation presentation decisions

> **Current behavior:** **D** mode derives sample RPM from a continuous estimate of road speed and
> the selected profile's ratio stack. Whole-number BYD speed readings are reconstructed before
> reaching the tach/audio. The sample bank's ratio stack controls presentation shift spacing and
> does not change real or simulated EV wheel force.

Last updated: 2026-08-16

This document records **product and presentation decisions** that are easy to misread as physics bugs. It complements the calibration evidence in [BYD Seal Performance calibration](byd-seal-performance-calibration.md) and the control inventory in [Live tuning interface](tuning-interface.md).

The recurring rule: **saved values and the 200 Hz simulation stay in SI units**. Cosmetic conversions happen only at the UI boundary in `VehicleDisplayUnits.kt` and `TuningPanel.kt`.

## Why this file exists

Several tuning readouts intentionally disagree with raw wheel measurements:

| What the user sees | What the model stores | Why they differ |
| --- | --- | --- |
| ≈ 67 kgfm on torque graphs | 7,145 Nm combined wheel torque | wheel torque divided by reduction, then converted to kgfm |
| ≈ 530 HP on power graphs | ~338 kW peak wheel power from curves | wheel kW scaled so the graphed peak matches motor rating, then converted to PS/cv |
| Label **HP** everywhere | Metric PS/cv arithmetic | Brazilian/marketing convention: people say “HP” but mean cv/PS |

None of these display transforms change acceleration, shift logic, or audio synthesis.

---

## 1. UI display layer (`VehicleDisplayUnits.kt`)

### 1.1 Boundary rule

- **Persisted / simulated:** Nm, kW, m/s, kg, normalized curve points.
- **Displayed / edited in graphs and sliders:** kgfm, values labeled **HP**, whole numbers, km/h, %, ms.
- **Conversion location:** `VehicleDisplayUnits.kt` for shared helpers; thin `EngineTuning` extensions in `TuningPanel.kt` for graph-specific wiring.

`DisplayUnitsTest.kt` locks the default Seal profile conversions.

### 1.2 Torque: wheel Nm → ≈ motor-shaft kgfm

**Problem:** A2MAC1 reports **wheel torque** (3,170 / 3,975 Nm peaks). Showing those Nm values directly, or converting them to kgfm without context, produces numbers like **729 kgfm** that look nothing like BYD’s published motor rating (~670 Nm / ~68 kgfm).

**Decision:** divide wheel torque by the configured **motor reduction ratio** (default **10.81:1**, from rear-drive teardown reporting) before converting Nm → kgfm. Label the result **≈ MOTOR TORQUE (kgfm)**.

```text
display_kgfm = (wheel_Nm / motorReductionRatio) / 9.80665
```

**What this is:** a coarse visual alignment with motor-shaft language, not a recovered factory map.

**What this is not:**

- It does not claim the front axle uses the same reduction as the rear.
- It does not feed back into `EngineSimulation.kt`; wheel Nm remain the acceleration input.
- Sliders **FRONT PEAK** / **REAR PEAK** edit the underlying wheel-Nm peaks through the same display conversion.

**Rejected alternative:** show raw wheel Nm or wheel kgfm. Rejected because the numbers overwhelm the panel and read like a data-entry mistake next to the 670 Nm motor rating.

### 1.3 Power: wheel kW → ≈ motor HP (actually PS/cv)

**Problem:** Physical wheel power computed from the digitized torque curves peaks near **338 kW (~453 imperial HP)** around **76 km/h**. BYD advertises **390 kW**, commonly quoted as **~531 cv/PS** in Brazil.

Those are different layers of the power chain:

```text
Battery (A2MAC1 measured up to ~408 kW HV input)
    → motor/inverter rating (390 kW published)
    → drivetrain losses
    → wheel power (what the torque curves imply, ~338 kW peak)
```

**Decision (two UI-only steps):**

1. **Scale** graphed wheel power proportionally so the curve peak equals the configured motor rating `peakPowerKw` (default 390 kW):

```text
display_kW = wheel_kW × (peakPowerKw / peakWheelPowerKwFromCurves)
```

`peakWheelPowerKwFromCurves()` scans the current front/rear curves and power ceiling — see `VehicleDisplayUnits.kt`.

2. **Convert** to the unit shown as **HP** using **metric horsepower (PS/cv)**, not imperial mechanical HP:

```text
display_HP = display_kW × 1.3596216173039
```

390 kW → **~530** after rounding (marketing **531** is the same rating with brochure rounding).

**Rejected alternatives:**

| Alternative | Why not |
| --- | --- |
| Show raw wheel power | Honest physics, but contradicts the advertised 390 kW / 531 cv story |
| Use imperial HP (÷ 0.7457) | Gives ~523 HP for 390 kW — correct in the US, wrong for the Brazilian “531 HP” expectation |
| Plot 390 kW flat | Would lie about the speed-dependent shape from A2MAC1 |

**Slider behavior:** **PEAK POWER** still edits stored `peakPowerKw`. The slider label is **HP**, but the value is PS/cv (`horsepowerToKilowatts` / `kilowattsToHorsepower` in `VehicleDisplayUnits.kt`).

### 1.4 Rounding and copy

- Graph markers and axis ticks use **integers** (`roundToInt()`).
- Combined graph subtitle states both transforms explicitly:
  - torque uses reduction **n : 1**
  - power is **scaled to motor rating, shown as HP (PS/cv)**

---

## 2. Graph annotation and axis policy (`TuningPanel.kt`)

### 2.1 Per-point labels on every graph

**Decision:** every landmark / draggable point / bar / response marker shows:

1. **Series value above** the dot — in the series color (torque kgfm, power HP, %, RPM, gain %, etc.).
2. **X coordinate below** the dot — with unit, in axis label gray (`GRAPH_AXIS_LABEL_COLOR`).
3. **Separate X-axis tick row** at the bottom — numbers only (no unit), preserved for quick scanning.

Vertical placement uses `markerLabelYBelow(markerBottomY, plotBottom)`:

- prefer **16 px below** the marker when there is room inside the plot;
- otherwise park labels just above the dedicated axis-tick row (`plotBottom + 14`).

**Motivation:** speed labels were previously clamped to one Y row and looked “missing” on the yellow power trace. The two-row layout (per-marker X + shared axis ticks) fixes overlap without dropping information.

### 2.2 Axis ticks from data, not uniform grids

**Decision:** replace evenly spaced grid ticks with **landmarks derived from the data**:

| Graph | Tick sources |
| --- | --- |
| Torque + power | Inflection points, power peak, power-limit onset, curve breakpoints |
| Editable axle curves | Each control point’s X/Y |
| Torque distribution | Launch, crossover (~50% rear), curve breakpoints, top speed |
| Gear landing | Idle, downshift, upshift, computed landing RPMs |
| Audio spectrum | Gain landmarks + harmonic index |
| Response preview | 0 ms, attack, release, brake time constants |

`axisTicksFromValues()` merges close values and enforces minimum spacing so labels do not collide.

**Rejected alternative:** fixed 0/25/50/75/100 % grids. Rejected because the interesting behavior is at measured breakpoints, not arbitrary divisions.

### 2.3 Graphs covered

- **AXLE TORQUE + POWER** — dual Y axes (≈ motor kgfm left, ≈ motor HP right)
- **Front / rear wheel torque** — editable curves
- **Torque distribution** — front/rear % at each landmark
- **Sport pedal response** — throttle request curve
- **Response preview** — attack / release / brake exponentials
- **Gear landing** — post-shift RPM bars
- **Audio spectrum** — harmonic gains

---

## 3. Simulation presentation decisions (`EngineSimulation.kt`)

These affect **behavior**, not just labels. They are documented here because they were chosen in the same tuning sessions.

### 3.1 Synthetic gears never touch EV wheel torque

The Seal uses fixed-ratio single-speed drive units. In this app:

- **Real:** wheel torque, acceleration, drag, braking — from electric model + A2MAC1 curves.
- **Fictional:** seven presentation gear ratios, tachometer RPM, shift sounds, gauge band.

A synthetic upshift **must not** cut, delay, or multiply wheel torque. Regression: `EngineSimulationTest` asserts no torque discontinuity at shift.

See [calibration doc — Synthetic gears](byd-seal-performance-calibration.md#synthetic-gears-are-not-a-transmission).

### 3.2 P / N / D shifter (2026-08)

A column shifter beside the pedals selects **P**, **N**, or **D**. It is runtime-only (not stored in engine tuning). `DriveController.setTransmissionPosition()` passes the choice into `DriverInput.transmissionPosition` on every 200 Hz step. Position changes are logged as `transmission_position_changed`.

| Position | Behavior |
|----------|----------|
| **D** | RPM is a free-running fictional state: pedal percentage adds positive RPM force, lift-off adds a strong constant negative force, and brake adds more negative force. Automatic presentation shifts remain. Road speed never targets or floors RPM. |
| **N** | Neutral: RPM free-revs with throttle and falls on lift-off; **no** automatic gear changes; throttle does **not** drive the wheels (coast/brake still affect SIM speed). |
| **P** | Park: same free-rev RPM model as **N**, but SIM speed is held at zero. |

The tachometer shows the gear number in **D** and **P**/**N** on the range readout when not in drive.

#### Neutral / park RPM model

In **N** and **P**, the synthetic tachometer target is throttle-driven, not road-coupled:

```text
targetRPM = idleRpm + filteredThrottle × (redlineRpm − idleRpm)
```

`filteredThrottle` still passes through the Sport-like pedal curve and the editable attack/release exponentials (defaults **120 ms** / **90 ms**), so partial pedal input is not a step change.

**Steady-state at partial throttle:** holding ~50% pedal stabilizes RPM midway between idle and redline — the same equilibrium idea as a real engine in neutral (throttle opening balances internal friction). The needle stops climbing once it reaches the target; it does not run away.

**Rev inertia (not editable in TUNE):** **N**/**P** use asymmetric fixed time constants in `EngineSimulation`:

| Direction | Constant | Default | Rationale |
|-----------|----------|---------|-----------|
| Revving up | `NEUTRAL_REV_UP_RESPONSE_SECONDS` | **0.55 s** | Crank/flywheel inertia with no wheel load |
| Revving down | `NEUTRAL_REV_DOWN_RESPONSE_SECONDS` | **0.90 s** | Slower coast-down toward idle after lift-off |

This target-seeking model is intentionally separate from the road-speed-coupled **D** model in §3.3.

**Tests:** `neutralPositionRaisesRpmWithThrottleAtStandstill`, `neutralPositionLiftOffDropsRpmTowardIdleAtStandstill`, `neutralPositionDoesNotAutoShiftWhileRevving`, `parkPositionKeepsSimulatorSpeedAtZero`, plus existing lift-off hunting guards in **D**.

### 3.3 Speed-coupled Drive RPM model

In **D**, continuous road speed is converted through the selected sample profile's presentation
ratio stack:

```text
wheelRPM = roadSpeed / tireCircumference
targetRPM = idleRPM + wheelRPM × gearRatio × soundFinalDrive
rpm = exponentialFollow(rpm, targetRPM)
```

The BYD framework reports whole-number speed. A predictive critically damped estimator maintains
a continuous speed value between integer changes and tracks the observed direction, so both rising
and falling road speed move the RPM/audio without one-km/h steps. The default response is 120 ms;
a second 55 ms tach follow removes residual control-rate edges. Both values are editable under
**DELAYS** and the 1 Hz diagnostic heartbeat records raw speed, continuous speed, and their delta.

The sound gearbox uses the selected sample bank's ratios and normal shift RPM.

Each upshift remembers the road-speed boundary that actually selected its new gear. Downshifts use
that remembered boundary with 4 km/h hysteresis. A road-speed near-redline guard
can upshift without throttle so coasting or externally driven speed cannot strand the sound engine
on its limiter. Presentation gears never feed back into the physical EV acceleration model.

**Tests:** `quantizedSpeedEstimatorMakesIntegerStepsContinuousInBothDirections`,
`driveRpmIsDeterminedByRoadSpeedRatherThanThrottleForce`,
`ratioBasedGearboxPreservesNormalProgression`,
`downshiftUsesTheBoundaryThatSelectedTheGear`, and
`integerNoiseNearThresholdDoesNotCauseShiftHunting`.

### 3.4 Simulator coast regen (2026-08)

**Problem:** in **SIM** mode, releasing the accelerator slowed the virtual car only through aero drag and rolling resistance — much slower than a real Seal’s mild lift-off regen.

**Decision:** when `InputMode.SIMULATOR`, `DriverInput.simulateCoastRegen = true` applies a constant deceleration `simulatorCoastRegenMps2` (default **2.50 m/s²**) during lift-off integration only. This deliberately strong simulator setting makes virtual road speed and its coupled sound RPM fall promptly when the touchscreen pedal is released. The speed setting is not applied in BYD LIVE mode and is not a factory regen map.

**Tuning:** **SIM COAST REGEN** slider in the Response tab (0–4.00 m/s²). A dedicated preference revision migrates existing installations to the stronger default without resetting unrelated tuning.

**Test:** `simulatorCoastRegenSlowsVirtualVehicleFasterThanDragAlone`.

**Explicit non-goal:** model BYD Standard/High regen modes from the owner manual — no published Nm or m/s² tables exist.

### 3.5 Motor-power sanity ceiling (unchanged)

Wheel torque from curves is capped by:

```text
powerLimitedTorque = peakPowerKw × 1000 × drivetrainEfficiency / wheelOmega
```

Default `peakPowerKw = 390`, `drivetrainEfficiency = 0.92`. This bounds the **physics model**; the UI power scaling in §1.3 is separate and only affects graphs/labels.

---

## 4. Product naming (2026-08)

| Item | Value |
| --- | --- |
| GitHub repository | `pedal-controlled-combustion-engine-sounds-simulator-for-byd` |
| Launcher / display name | **Engine Sounds Simulator** |
| `applicationId` / Java package | `com.gabrielpc.enginesoundsimulator` |

The package id was renamed from `com.gabrielpc.bydmotorsound` to match the product name. **Installing over the old APK is not an upgrade path** — uninstall the previous build or sideload fresh. Diagnostics and `run-as` paths use the new id.

---

## 5. What to change carefully in future work

1. **Do not pipe display kgfm/HP back into `EngineSimulation`** without an explicit product decision and regression pass.
2. **If A2MAC1 curves are replaced**, `peakWheelPowerKwFromCurves()` changes → UI power scale auto-adjusts so the peak still matches `peakPowerKw`. Verify graph labels after curve edits.
3. **If imperial HP is ever required** (e.g. US-only build), add a explicit unit toggle — do not silently switch `kilowattsToHorsepower()` without updating tests and copy.
4. **Reintroducing lift-off RPM retention** requires solving gear hunting first; read §3.2 and archived tests before restoring sliders.
5. **Graph label density** is intentionally high; if a new graph is added, follow §2.1 (value above, X below, axis row preserved).

---

## 6. Code map

| Concern | File |
| --- | --- |
| Display conversions and peak wheel-power scan | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/VehicleDisplayUnits.kt` |
| Graph drawing, landmarks, markers | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/TuningPanel.kt` |
| Physical EV integration | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/simulation/EngineSimulation.kt` |
| SIM coast regen flag | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/drive/DriveController.kt` |
| Persisted defaults and sanitization | `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/tuning/TuningConfig.kt` |
| Conversion regressions | `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/DisplayUnitsTest.kt` |
| Drivetrain regressions | `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/simulation/EngineSimulationTest.kt` |

---

## 7. Related reading

- [BYD Seal Performance calibration](byd-seal-performance-calibration.md) — measured anchors, A2MAC1 digitization, evidence policy
- [Live tuning interface](tuning-interface.md) — panel layout and editable controls
- [Full implementation](full-implementation.md) — 200 Hz loop, audio, shifts
- [Fresh-chat LLM handoff](llm-handoff.md) — checkout, workflow, architecture index
