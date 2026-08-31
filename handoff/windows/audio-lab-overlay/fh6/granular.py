"""Deterministic planning for FH6 acceleration/deceleration loop playback.

This module does not decode or copy recordings.  It discovers already-
validated cache WAVs and computes the neighboring root loops, pitch ratios and
crossfade gains that the native renderer consumes.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from pathlib import Path


LOOP_PATTERN = re.compile(r"(?:^|_)(Acc|Dec)_(\d+)_ADPCM\.wav$", re.IGNORECASE)
IDLE_PATTERN = re.compile(r"^Idle_(?:Acc|Dec)_(\d+)_ADPCM\.wav$", re.IGNORECASE)


@dataclass(frozen=True)
class RootLoop:
    path: Path
    family: str
    root_rpm: float


@dataclass(frozen=True)
class LoopVoice:
    loop: RootLoop
    gain: float
    pitch_ratio: float


def discover_loops(directory: Path) -> tuple[RootLoop, ...]:
    result: list[RootLoop] = []
    for path in sorted(directory.rglob("*.wav")):
        idle = IDLE_PATTERN.match(path.name)
        if idle:
            result.append(RootLoop(path, "idle", float(idle.group(1))))
            continue
        match = LOOP_PATTERN.search(path.name)
        if match:
            result.append(RootLoop(path, match.group(1).lower(), float(match.group(2))))
    return tuple(result)


class GranularPlanner:
    def __init__(self, loops: tuple[RootLoop, ...]):
        if not loops:
            raise ValueError("at least one root loop is required")
        self.loops = loops

    def _family(self, rpm: float, rpm_rate: float, throttle: float) -> str:
        if rpm <= min(loop.root_rpm for loop in self.loops) * 1.08 and any(loop.family == "idle" for loop in self.loops):
            return "idle"
        if rpm_rate < -1.0 or (rpm_rate <= 1.0 and throttle < 0.35):
            return "dec"
        return "acc"

    def voices(self, rpm: float, rpm_rate: float, throttle: float) -> tuple[LoopVoice, ...]:
        family = self._family(rpm, rpm_rate, throttle)
        candidates = sorted((loop for loop in self.loops if loop.family == family), key=lambda item: item.root_rpm)
        if not candidates and family == "idle":
            candidates = sorted((loop for loop in self.loops if loop.family == "dec"), key=lambda item: item.root_rpm)
        if not candidates:
            candidates = sorted(self.loops, key=lambda item: item.root_rpm)
        target = max(1.0, rpm)
        lower = candidates[0]
        upper = candidates[-1]
        for loop in candidates:
            if loop.root_rpm <= target:
                lower = loop
            if loop.root_rpm >= target:
                upper = loop
                break
        if lower == upper:
            return (LoopVoice(lower, 1.0, target / max(1.0, lower.root_rpm)),)
        position = (target - lower.root_rpm) / (upper.root_rpm - lower.root_rpm)
        position = max(0.0, min(1.0, position))
        # Constant-power overlap prevents the neighboring loops from losing
        # energy halfway through the authored RPM region.
        low_gain = math.cos(position * math.pi * 0.5)
        high_gain = math.sin(position * math.pi * 0.5)
        return (
            LoopVoice(lower, low_gain, target / lower.root_rpm),
            LoopVoice(upper, high_gain, target / upper.root_rpm),
        )
