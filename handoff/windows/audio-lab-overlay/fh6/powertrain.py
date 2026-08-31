"""Speed-authoritative FH6 virtual gearbox and event state machine."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .config import FH6CarConfig
from .input import PowertrainControl, ResampledVehicle, Selector


@dataclass(frozen=True)
class PowertrainFrame:
    rpm: float
    road_coupled_rpm: float
    speed_kph: float
    acceleration_mps2: float
    throttle: float
    brake: float
    boost: float
    selector: str
    ignition: bool
    gear: int
    gear_label: str
    shifting: bool
    shift_phase: str
    shift_progress: float
    shift_started: bool
    shift_completed: bool
    shift_direction: int
    startup_triggered: bool
    bov_triggered: bool
    burble_triggered: bool
    backfire_triggered: bool
    anti_lag_active: bool
    limiter_active: bool
    input_stale: bool
    input_dropout: bool
    ratio_fidelity_exact: bool


class FH6Powertrain:
    """Virtual powertrain whose road speed always comes from the adapter."""

    def __init__(self, config: FH6CarConfig):
        self.config = config
        self.control = PowertrainControl(False, Selector.PARK)
        self.gear = 0
        self._target_gear = 0
        self._from_gear = 0
        self._shift_elapsed = 0.0
        self._shift_start_rpm = config.idle_rpm
        self._rpm = 0.0
        self._previous_throttle = 0.0
        self._previous_boost = 0.0
        self._landing_rpm: dict[int, float] = {}
        self._startup_latch = False
        self._free_rev_velocity = 0.0

    @property
    def shifting(self) -> bool:
        return self._target_gear != 0

    def _road_rpm(self, speed_kph: float, gear: int) -> float:
        if gear <= 0 or speed_kph <= 0.0:
            return self.config.idle_rpm
        drivetrain = self.config.drivetrain
        wheel_rps = (speed_kph / 3.6) / (2.0 * math.pi * drivetrain.driven_tyre_radius_m)
        return wheel_rps * 60.0 * drivetrain.final_drive * drivetrain.ratios[gear - 1]

    def _safe_gear(self, speed_kph: float) -> int:
        maximum = self.config.maximum_rpm * 0.96
        valid = [gear for gear in range(1, len(self.config.drivetrain.ratios) + 1) if self._road_rpm(speed_kph, gear) <= maximum]
        if not valid:
            return len(self.config.drivetrain.ratios)
        # Highest numerical ratio that is safe gives immediate response.
        return valid[0]

    def set_control(self, control: PowertrainControl, speed_kph: float) -> bool:
        previous = self.control
        selector = control.selector
        limit = self.config.drivetrain.low_speed_selector_limit_kph
        if selector in {Selector.PARK, Selector.REVERSE} and abs(speed_kph) > limit:
            return False
        self.control = control
        self._startup_latch = control.ignition and not previous.ignition
        if not control.ignition:
            self.gear = self._target_gear = 0
        elif selector == Selector.DRIVE and previous.selector != Selector.DRIVE:
            self.gear = self._safe_gear(speed_kph)
        elif selector == Selector.REVERSE:
            self.gear = -1
        elif selector in {Selector.PARK, Selector.NEUTRAL}:
            self.gear = 0
        return True

    def _start_shift(self, target: int) -> bool:
        if self.shifting or target == self.gear or target < 1 or target > len(self.config.drivetrain.ratios):
            return False
        self._from_gear = self.gear
        self._target_gear = target
        self._shift_elapsed = 0.0
        self._shift_start_rpm = self._rpm
        if target > self.gear:
            landing = self._shift_start_rpm * (
                self.config.drivetrain.ratios[target - 1] / self.config.drivetrain.ratios[self.gear - 1]
            )
            self._landing_rpm[target] = landing
        self.gear = 0
        return True

    def _free_rev(self, dt: float, throttle: float, ignition: bool) -> float:
        if not ignition:
            target = 0.0
        else:
            target = self.config.idle_rpm + throttle * (self.config.maximum_rpm - self.config.idle_rpm)
        error = target - self._rpm
        acceleration = max(-22000.0, min(15000.0, error * (5.0 if error > 0 else 8.0)))
        # A tiny inertial state avoids a one-frame RPM teleport while still
        # reacting in the very next block.
        self._free_rev_velocity += (acceleration - self._free_rev_velocity) * min(1.0, dt * 20.0)
        return max(0.0, self._rpm + self._free_rev_velocity * dt)

    def step(self, dt: float, vehicle: ResampledVehicle) -> PowertrainFrame:
        if dt <= 0:
            raise ValueError("dt must be positive")
        throttle = max(0.0, min(1.0, vehicle.throttle_pct / 100.0))
        brake = max(0.0, min(1.0, vehicle.brake_pct / 100.0))
        shift_started = shift_completed = False
        direction = 0
        startup = self._startup_latch
        self._startup_latch = False

        ignition = self.control.ignition
        selector = self.control.selector
        drive = ignition and selector == Selector.DRIVE

        if drive and not self.shifting and self.gear > 0:
            rpm = self._road_rpm(vehicle.speed_kph, self.gear)
            # The road-coupled value is current before the shift request. It
            # is the exact source for the next-ratio landing calculation.
            self._rpm = rpm
            maximum_gear = len(self.config.drivetrain.ratios)
            up_index = min(self.gear - 1, len(self.config.drivetrain.upshift_rpm) - 1)
            up_rpm = self.config.drivetrain.upshift_rpm[up_index]
            if self.gear < maximum_gear and throttle > 0.20 and rpm >= up_rpm:
                shift_started = self._start_shift(self.gear + 1)
                direction = 1
            elif self.gear > 1 and throttle <= 0.20:
                # No hysteresis: use the exact ratio-calculated RPM recorded
                # when this gear previously landed.
                threshold = self._landing_rpm.get(self.gear)
                # The relative epsilon only absorbs binary floating-point
                # roundoff when converting RPM -> speed -> RPM. It is several
                # orders of magnitude below one RPM and is not hysteresis.
                if threshold is not None and rpm <= threshold * (1.0 + 1e-12):
                    shift_started = self._start_shift(self.gear - 1)
                    direction = -1

        phase = "coupled" if drive else "free"
        progress = 0.0
        if self.shifting:
            self._shift_elapsed += dt
            progress = min(1.0, self._shift_elapsed / self.config.drivetrain.shift_duration_s)
            neutral_end = self.config.drivetrain.neutral_fraction
            if progress <= neutral_end:
                phase = "neutral"
                # During the authored neutral interval, RPM initially carries
                # its pre-shift inertia and starts moving toward the target.
                shaped = 0.18 * (progress / max(neutral_end, 1e-6))
            else:
                phase = "synchronizing"
                x = (progress - neutral_end) / max(1.0 - neutral_end, 1e-6)
                shaped = 0.18 + 0.82 * (x * x * (3.0 - 2.0 * x))
            target_rpm = self._road_rpm(vehicle.speed_kph, self._target_gear)
            self._rpm = self._shift_start_rpm + (target_rpm - self._shift_start_rpm) * shaped
            if progress >= 1.0:
                old = self._from_gear
                self.gear = self._target_gear
                self._target_gear = 0
                shift_completed = True
                direction = 1 if self.gear > old else -1
                phase = "coupled"
                self._rpm = self._road_rpm(vehicle.speed_kph, self.gear)
        elif drive and self.gear > 0:
            self._rpm = self._road_rpm(vehicle.speed_kph, self.gear)
        elif ignition and selector == Selector.REVERSE:
            ratio = self.config.drivetrain.reverse_ratio
            wheel_rps = (vehicle.speed_kph / 3.6) / (2.0 * math.pi * self.config.drivetrain.driven_tyre_radius_m)
            self._rpm = max(self.config.idle_rpm, wheel_rps * 60 * self.config.drivetrain.final_drive * ratio)
        else:
            self._rpm = self._free_rev(dt, throttle, ignition)

        if ignition:
            self._rpm = max(self.config.idle_rpm, min(self._rpm, self.config.maximum_rpm * 1.015))
        else:
            self._rpm = max(0.0, self._rpm)

        # Effect triggers are transport-neutral control signals. Original
        # authored samples/curves remain the audio renderer's responsibility.
        boost_target = throttle * max(0.0, min(1.0, (self._rpm - 1800.0) / 2800.0))
        boost = self._previous_boost + (boost_target - self._previous_boost) * min(1.0, dt * (3.2 if boost_target > self._previous_boost else 9.0))
        lift = self._previous_throttle > 0.62 and throttle < 0.18
        bov = lift and self._previous_boost > 0.22
        burble = lift and self._rpm > 2600.0
        backfire = lift and self._rpm > 3500.0
        anti_lag = self.config.effects.get("AntiLag", "") != "" and lift and self._rpm > 4200.0
        limiter = ignition and self._rpm >= self.config.maximum_rpm
        self._previous_throttle = throttle
        self._previous_boost = boost

        shown_gear = 0 if self.shifting else self.gear
        if not self.shifting and selector == Selector.PARK:
            label = "P"
        elif shown_gear < 0:
            label = "R"
        elif shown_gear == 0:
            label = "N"
        else:
            label = str(shown_gear)
        road = self._road_rpm(vehicle.speed_kph, self.gear if self.gear > 0 else self._target_gear)
        return PowertrainFrame(
            rpm=self._rpm,
            road_coupled_rpm=road,
            speed_kph=vehicle.speed_kph,
            acceleration_mps2=vehicle.acceleration_mps2,
            throttle=throttle,
            brake=brake,
            boost=boost,
            selector=selector.value,
            ignition=ignition,
            gear=shown_gear,
            gear_label=label,
            shifting=self.shifting,
            shift_phase=phase,
            shift_progress=progress,
            shift_started=shift_started,
            shift_completed=shift_completed,
            shift_direction=direction,
            startup_triggered=startup,
            bov_triggered=bov,
            burble_triggered=burble,
            backfire_triggered=backfire,
            anti_lag_active=anti_lag,
            limiter_active=limiter,
            input_stale=vehicle.stale,
            input_dropout=vehicle.dropout,
            ratio_fidelity_exact=self.config.drivetrain.exact_from_installed_database,
        )
