"""Fixed-step free-rev engine model driven by Assetto Corsa car data."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .car_config import CarSpec, interpolate_curve


RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * math.pi)


@dataclass(frozen=True)
class EngineFrame:
    rpm: float
    throttle: float
    mapped_throttle: float
    boost: float
    limiter_active: bool
    limiter_pulse: bool
    backfire_triggered: bool
    bov: float
    bov_decay: float
    torque: float


class FreeRevEngine:
    """A deterministic, neutral-gear approximation of AC's torque integrator.

    The inputs, torque/pedal LUTs, inertia, coast reference, turbo lag and fuel
    cut frequency all come directly from the selected car's ``data.acd``.
    """

    def __init__(self, spec: CarSpec):
        self.spec = spec
        self.rpm = spec.idle_rpm
        self.throttle = 0.0
        self.target_throttle = 0.0
        self._keyboard_mode = False
        self._keyboard_pressed = False
        self._keyboard_gas = 0.0
        self.boost = 0.0
        self._turbo_q = 0.0
        self._turbo_qs = [0.0 for _ in spec.turbos]
        self.bov = 0.0
        self.bov_decay = 10.0
        self.elapsed = 0.0
        self.angular_acceleration = 0.0
        self._backfire_peak_gas = 0.6
        self._backfire_arm_level = spec.backfire.trigger_gas
        self._backfire_fire_below = 0.25
        self._backfire_armed = False
        self._backfire_timer = 0.0
        self._limiter_counter = 0

    def set_throttle(self, value: float) -> None:
        self._keyboard_mode = False
        self.target_throttle = min(1.0, max(0.0, float(value)))

    def set_keyboard_throttle(self, pressed: bool) -> None:
        """Use AC's KeyboardCarControl gas ramp for a digital pedal."""

        if not self._keyboard_mode:
            self._keyboard_gas = self.throttle
        self._keyboard_mode = True
        self._keyboard_pressed = bool(pressed)

    def _coast_torque(self, rpm: float) -> float:
        if rpm <= self.spec.idle_rpm:
            return 0.0
        reference = self.spec.coast_reference_rpm
        minimum = self.spec.idle_rpm
        non_linearity = self.spec.coast_non_linearity
        denominator = (1.0 - non_linearity) * reference - minimum
        c1 = -self.spec.coast_reference_torque / denominator if denominator else 0.0
        nonlinear_rpm = non_linearity * reference
        c2 = (
            self.spec.coast_reference_torque / (nonlinear_rpm * nonlinear_rpm)
            if nonlinear_rpm
            else 0.0
        )
        delta = rpm - minimum
        sign = 1.0 if rpm >= 0.0 else -1.0
        # AC represents coast as a signed contribution to output torque.
        return c1 * delta - c2 * delta * delta * sign

    def step(self, dt: float) -> EngineFrame:
        dt = min(0.02, max(0.0001, float(dt)))
        self.elapsed += dt

        if self._keyboard_mode:
            if self._keyboard_pressed:
                self._keyboard_gas = min(1.0, self._keyboard_gas + dt * 4.0)
            else:
                # KeyboardCarControl::acquireControls bypasses the coefficient
                # helper on key-up and clears both gas and intGas immediately.
                self._keyboard_gas = 0.0
            self.throttle = self._keyboard_gas
        else:
            # Analog player input then passes the audio control SmoothValue
            # with alpha=0, so it snaps instead of opponent-car smoothing.
            self.throttle = self.target_throttle
        mapped_throttle = interpolate_curve(self.spec.throttle_curve, self.throttle)

        # Final acs.exe keeps a peak-relative armed gate. A peak above the
        # initial 0.6 rescales both thresholds; after reaching full pedal the
        # Tatuus arms above 0.8 and fires once below 0.4. Exactly zero is
        # rejected, matching the game's analog/control-layer release path.
        if self.throttle > self._backfire_peak_gas and self.throttle != 0.0:
            self._backfire_peak_gas = self.throttle
            self._backfire_arm_level = self.spec.backfire.trigger_gas * self.throttle
            self._backfire_fire_below = self.spec.backfire.maximum_gas * self.throttle
        if self.throttle > self._backfire_arm_level:
            self._backfire_armed = True
        backfire = (
            self._backfire_armed
            and 0.0 < self.throttle < self._backfire_fire_below
            and self.rpm > self.spec.backfire.minimum_rpm
            and self.rpm <= self.spec.backfire.maximum_rpm
            and self._backfire_timer > 1.0
        )
        if backfire:
            self._backfire_armed = False
        elif self._backfire_armed:
            self._backfire_timer = min(10.0, self._backfire_timer + dt)

        # Recovered from the final acs.exe Engine/Drivetrain path: the INI's
        # nominal limiter frequency is converted to a number of exact 3 ms
        # physics steps. Every step above the threshold re-arms that counter;
        # while it is non-zero the mapped engine gas is forced to zero.
        limiter = self.spec.limiter_rpm
        if self.spec.limiter_hz > 0.0:
            limiter_steps = int(int(1000.0 / self.spec.limiter_hz) / 3)
        else:
            limiter_steps = 50
        if limiter > 0.0 and self.rpm > limiter:
            self._limiter_counter = max(1, limiter_steps)
        limiter_active = self._limiter_counter > 0
        if limiter_active:
            self._limiter_counter -= 1
        effective_throttle = 0.0 if limiter_active else mapped_throttle

        if self.spec.turbos:
            boosts: list[float] = []
            for index, turbo in enumerate(self.spec.turbos):
                turbo_input = min(1.0, max(0.0, effective_throttle * max(self.rpm, 0.0) / max(1.0, turbo.reference_rpm)))
                target = turbo_input**turbo.gamma
                q = self._turbo_qs[index]
                lag = turbo.lag_up if target > q else turbo.lag_down
                q += min(1.0, max(0.0, dt * lag)) * (target - q)
                if turbo.wastegate > 0.0 and turbo.maximum_boost * q > turbo.wastegate:
                    q = turbo.wastegate / max(0.001, turbo.maximum_boost)
                self._turbo_qs[index] = q
                boosts.append(turbo.maximum_boost * q)
            self._turbo_q = self._turbo_qs[0]
            self.boost = sum(boosts)
            self.bov = 1.0 if self.boost * (1.0 - effective_throttle) > self.spec.turbos[0].bov_threshold else 0.0
            self.bov_decay = 0.0 if self.bov > 0.0 else min(10.0, self.bov_decay + dt)

        power_torque = interpolate_curve(self.spec.torque_curve, self.rpm) * (1.0 + self.boost)
        coast_torque = self._coast_torque(self.rpm)
        net_torque = coast_torque + effective_throttle * (power_torque - coast_torque)
        if self.rpm < self.spec.idle_rpm:
            net_torque = max(net_torque, 15.0)

        # Neutral has a zero ratio in Drivetrain::step2WD, so AC uses engine
        # inertia alone; gearbox inertia is not reflected into the crankshaft.
        inertia = max(0.001, self.spec.engine_inertia)
        self.angular_acceleration = net_torque / inertia
        self.rpm += self.angular_acceleration * RPM_PER_RADIAN_SECOND * dt
        self.rpm = max(0.0, self.rpm)

        return EngineFrame(
            rpm=self.rpm,
            throttle=self.throttle,
            mapped_throttle=mapped_throttle,
            boost=self.boost,
            limiter_active=limiter_active,
            limiter_pulse=limiter_active,
            backfire_triggered=backfire,
            bov=self.bov,
            bov_decay=self.bov_decay,
            torque=net_torque,
        )
