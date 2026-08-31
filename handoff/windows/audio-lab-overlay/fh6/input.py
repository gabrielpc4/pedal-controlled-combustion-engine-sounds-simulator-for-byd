"""Transport-neutral vehicle input and zero-buffer predictive resampling."""

from __future__ import annotations

import math
import time
from dataclasses import dataclass
from enum import Enum
from threading import RLock
from typing import Protocol


class Selector(str, Enum):
    PARK = "P"
    REVERSE = "R"
    NEUTRAL = "N"
    DRIVE = "D"


@dataclass(frozen=True)
class VehicleSample:
    timestamp_ns: int
    speed_kph: float
    throttle_pct: float
    brake_pct: float

    def __post_init__(self) -> None:
        if self.timestamp_ns < 0:
            raise ValueError("timestamp_ns must be non-negative")
        for name, value in (("speed_kph", self.speed_kph), ("throttle_pct", self.throttle_pct), ("brake_pct", self.brake_pct)):
            if not math.isfinite(value):
                raise ValueError(f"{name} must be finite")
        if not 0.0 <= self.throttle_pct <= 100.0 or not 0.0 <= self.brake_pct <= 100.0:
            raise ValueError("pedal percentages must be between 0 and 100")


@dataclass(frozen=True)
class PowertrainControl:
    ignition: bool
    selector: Selector


class VehicleSampleProvider(Protocol):
    def latest(self) -> VehicleSample | None: ...


@dataclass(frozen=True)
class ResampledVehicle:
    speed_kph: float
    throttle_pct: float
    brake_pct: float
    acceleration_mps2: float
    age_ms: float
    stale: bool
    dropout: bool


class PredictiveResampler:
    """Predicts from the newest sample without intentionally buffering it.

    New measurements affect the next audio-control block.  Prediction is
    capped at 100 ms.  The correction controller limits jerk while converging
    to a stable authoritative speed exactly.
    """

    def __init__(
        self,
        *,
        prediction_limit_s: float = 0.100,
        stale_s: float = 0.500,
        dropout_s: float = 2.000,
        correction_s: float = 0.025,
        max_acceleration_mps2: float = 1000.0,
        max_jerk_mps3: float = 100000.0,
    ):
        self.prediction_limit_s = prediction_limit_s
        self.stale_s = stale_s
        self.dropout_s = dropout_s
        self.correction_s = correction_s
        self.max_acceleration_mps2 = max_acceleration_mps2
        self.max_jerk_mps3 = max_jerk_mps3
        self._previous: VehicleSample | None = None
        self._latest: VehicleSample | None = None
        self._output_mps = 0.0
        self._output_accel = 0.0
        self._initialized = False
        self._lock = RLock()

    def submit(self, sample: VehicleSample) -> bool:
        with self._lock:
            if self._latest is not None and sample.timestamp_ns <= self._latest.timestamp_ns:
                return False
            self._previous, self._latest = self._latest, sample
            if not self._initialized:
                self._output_mps = abs(sample.speed_kph) / 3.6
                self._output_accel = 0.0
                self._initialized = True
            return True

    def reset(self) -> None:
        with self._lock:
            self._previous = self._latest = None
            self._output_mps = self._output_accel = 0.0
            self._initialized = False

    def _measured_acceleration(self) -> float:
        if self._previous is None or self._latest is None:
            return 0.0
        dt = (self._latest.timestamp_ns - self._previous.timestamp_ns) / 1e9
        if not 0.001 <= dt <= 1.0:
            return 0.0
        value = (abs(self._latest.speed_kph) - abs(self._previous.speed_kph)) / 3.6 / dt
        # Vehicle acceleration prediction is bounded independently of the
        # much faster error-correction actuator.
        return max(-30.0, min(30.0, value))

    def sample(self, now_ns: int | None = None, dt: float = 0.001) -> ResampledVehicle:
        with self._lock:
            latest = self._latest
            if latest is None:
                return ResampledVehicle(0.0, 0.0, 0.0, 0.0, math.inf, True, True)
            now_ns = time.monotonic_ns() if now_ns is None else now_ns
            age_s = max(0.0, (now_ns - latest.timestamp_ns) / 1e9)
            measured_accel = self._measured_acceleration()
            horizon = min(age_s, self.prediction_limit_s)
            target = max(0.0, abs(latest.speed_kph) / 3.6 + measured_accel * horizon)

            if age_s >= self.stale_s:
                # Do not extrapolate an absent vehicle indefinitely.
                target = abs(latest.speed_kph) / 3.6
                measured_accel = 0.0

            error = target - self._output_mps
            if abs(error) < 0.025 and abs(measured_accel) < 1e-7:
                # Finish a settled correction exactly.  The threshold is only
                # 0.09 km/h and avoids an infinite exponential tail.
                self._output_mps = target
                self._output_accel = 0.0
            else:
                desired_accel = max(
                    -self.max_acceleration_mps2,
                    min(self.max_acceleration_mps2, error / max(self.correction_s, dt)),
                )
                jerk_step = self.max_jerk_mps3 * dt
                self._output_accel += max(-jerk_step, min(jerk_step, desired_accel - self._output_accel))
                previous_output = self._output_mps
                self._output_mps = max(0.0, self._output_mps + self._output_accel * dt)
                if (target - previous_output) * (target - self._output_mps) <= 0.0:
                    self._output_mps = target
                    self._output_accel = measured_accel

            return ResampledVehicle(
                speed_kph=self._output_mps * 3.6,
                throttle_pct=latest.throttle_pct,
                brake_pct=latest.brake_pct,
                acceleration_mps2=self._output_accel,
                age_ms=age_s * 1000.0,
                stale=age_s >= self.stale_s,
                dropout=age_s >= self.dropout_s,
            )
