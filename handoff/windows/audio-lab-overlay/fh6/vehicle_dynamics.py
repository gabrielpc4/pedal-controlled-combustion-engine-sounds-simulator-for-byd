"""Deterministic mock longitudinal dynamics for a BYD Seal AWD reference.

This is deliberately separate from the transport-neutral vehicle adapter.  A
real adapter remains speed-authoritative; this model only gives the dashboard
pedals a plausible vehicle to drive when the mock provider is selected.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass

from fh6.input import Selector


@dataclass(frozen=True)
class BYDSealAWDSpec:
    # Official SEAL Excellence AWD physical baseline.  Peak power, launch time
    # and governor follow the user-supplied 408 kW / 3.97 s / 190 km/h trace.
    mass_kg: float = 2185.0
    drag_coefficient: float = 0.219
    frontal_area_m2: float = 2.20
    rolling_resistance_coefficient: float = 0.0105
    peak_power_kw: float = 408.0
    peak_drive_force_n: float = 15625.0
    maximum_speed_kph: float = 190.0
    reverse_maximum_speed_kph: float = 30.0
    target_zero_to_100_s: float = 3.97
    lift_regen_deceleration_mps2: float = 1.20
    maximum_regen_power_kw: float = 120.0
    maximum_friction_deceleration_mps2: float = 8.5
    regen_efficiency: float = 0.72

    def json(self) -> dict[str, float]:
        return asdict(self)


@dataclass(frozen=True)
class VehicleDynamicsFrame:
    speed_kph: float
    acceleration_mps2: float
    drive_force_n: float
    regen_force_n: float
    friction_brake_force_n: float
    resistance_force_n: float
    regen_power_kw: float
    recovered_energy_kwh: float


class BYDSealAWDModel:
    """One-dimensional force model with EV lift-off regeneration.

    Throttle requests tractive force, brake blends the same regeneration limit
    with friction braking, and a released accelerator applies lift-off regen.
    The low-speed taper avoids an unphysical sign reversal at a stop.
    """

    def __init__(self, spec: BYDSealAWDSpec | None = None):
        self.spec = spec or BYDSealAWDSpec()
        self.speed_mps = 0.0
        self.recovered_energy_kwh = 0.0
        self.frame = VehicleDynamicsFrame(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    def set_speed(self, speed_kph: float) -> None:
        if not math.isfinite(speed_kph):
            raise ValueError("speed must be finite")
        self.speed_mps = max(0.0, float(speed_kph) / 3.6)

    def reset(self, speed_kph: float = 0.0) -> None:
        self.set_speed(speed_kph)
        self.recovered_energy_kwh = 0.0
        self.frame = VehicleDynamicsFrame(speed_kph, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    def step(
        self,
        dt: float,
        throttle_pct: float,
        brake_pct: float,
        *,
        ignition: bool,
        selector: Selector,
    ) -> VehicleDynamicsFrame:
        if not math.isfinite(dt) or dt <= 0.0:
            return self.frame
        throttle = max(0.0, min(1.0, throttle_pct / 100.0))
        brake = max(0.0, min(1.0, brake_pct / 100.0))
        speed = self.speed_mps
        spec = self.spec

        resistance = 0.0
        if speed > 0.0:
            rolling = spec.mass_kg * 9.80665 * spec.rolling_resistance_coefficient
            aerodynamic = 0.5 * 1.225 * spec.drag_coefficient * spec.frontal_area_m2 * speed * speed
            resistance = rolling + aerodynamic

        drive_force = 0.0
        drive_enabled = ignition and selector in {Selector.DRIVE, Selector.REVERSE} and brake < 0.02
        if drive_enabled and throttle > 0.0:
            force_limit = spec.peak_drive_force_n * throttle
            power_limit = spec.peak_power_kw * 1000.0 * throttle / max(speed, 1.0)
            drive_force = min(force_limit, power_limit)

        # Released-throttle regen is deliberately present even with brake=0.
        # Pressing the brake requests stronger blended regeneration first.
        regen_request = 0.0
        if ignition and selector in {Selector.DRIVE, Selector.REVERSE} and speed > 0.05:
            lift_request = (1.0 - throttle) * spec.lift_regen_deceleration_mps2
            brake_regen_request = brake * 2.0
            requested_deceleration = max(lift_request, brake_regen_request)
            low_speed_taper = min(1.0, speed / 1.5)
            force_limit = spec.mass_kg * requested_deceleration * low_speed_taper
            power_limit = spec.maximum_regen_power_kw * 1000.0 / max(speed, 1.0)
            regen_request = min(force_limit, power_limit)

        friction_force = spec.mass_kg * spec.maximum_friction_deceleration_mps2 * brake
        # Regen owns the first portion of brake demand; friction supplies only
        # the remainder, preventing the two from being blindly stacked.
        friction_force = max(0.0, friction_force - regen_request)

        if selector is Selector.PARK:
            drive_force = 0.0
            regen_request = 0.0
            friction_force = max(friction_force, spec.mass_kg * 10.0 if speed > 0.01 else 0.0)

        net_force = drive_force - resistance - regen_request - friction_force
        acceleration = net_force / spec.mass_kg
        next_speed = speed + acceleration * dt
        if next_speed < 0.0:
            next_speed = 0.0
            acceleration = -speed / dt

        speed_limit = spec.reverse_maximum_speed_kph if selector is Selector.REVERSE else spec.maximum_speed_kph
        maximum_mps = speed_limit / 3.6
        if next_speed > maximum_mps:
            next_speed = maximum_mps
            acceleration = (next_speed - speed) / dt

        regen_power_kw = regen_request * speed / 1000.0
        self.recovered_energy_kwh += regen_power_kw * spec.regen_efficiency * dt / 3600.0
        self.speed_mps = next_speed
        self.frame = VehicleDynamicsFrame(
            speed_kph=next_speed * 3.6,
            acceleration_mps2=acceleration,
            drive_force_n=drive_force,
            regen_force_n=regen_request,
            friction_brake_force_n=friction_force,
            resistance_force_n=resistance,
            regen_power_kw=regen_power_kw,
            recovered_energy_kwh=self.recovered_energy_kwh,
        )
        return self.frame
