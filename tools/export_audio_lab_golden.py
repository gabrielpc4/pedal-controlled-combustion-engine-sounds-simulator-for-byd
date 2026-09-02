#!/usr/bin/env python3
"""Export deterministic Audio Lab drivetrain traces consumed by Android tests."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIO_LAB = ROOT.parent / "assetto_corsa_audio_lab"
OUTPUT = ROOT / "mobile" / "src" / "androidTest" / "assets" / "audio_lab_golden_alfa_4c.json"
DT = 0.003


def _load_alfa_specs():
    sys.path.insert(0, str(AUDIO_LAB))
    from sim.bank_pack import load_pack_physics

    pack = ROOT / "fmod_bank_packs" / "alfa-romeo-4c.bydbank"
    if not pack.is_file():
        raise FileNotFoundError("Generate byd-fmod-bank-pack-v2 packages before exporting traces")

    return load_pack_physics(pack, "alfa-romeo-4c")


def _frame_payload(index: int, throttle: float, brake: float, frame) -> dict[str, object]:
    return {
        "index": index,
        "throttleInput": throttle,
        "brakeInput": brake,
        "rpm": frame.rpm,
        "speedMetersPerSecond": frame.speed_mps,
        "gear": frame.gear,
        "drivetrainSpeedRadiansPerSecond": frame.drivetrain_speed,
        "effectiveThrottle": frame.effective_throttle,
        "clutch": frame.clutch,
        "boost": frame.boost,
        "bov": frame.bov,
        "bovDecaySeconds": frame.bov_decay,
        "limiterPulse": frame.limiter_pulse,
        "backfireTriggered": frame.backfire_triggered,
        "shiftStarted": frame.shift_started,
        "shiftRejected": frame.shift_rejected,
        "shifting": frame.shifting,
        "shiftDirection": frame.gear_direction,
        "tractionLimitActive": frame.traction_limit_active,
        "tractionLimitPulse": frame.traction_limit_pulse,
    }


def _drive_trace(engine, drivetrain_spec) -> list[dict[str, object]]:
    from sim.drivetrain import AutomaticDrivetrain

    simulation = AutomaticDrivetrain(engine, drivetrain_spec, initial_gear=1)
    result: list[dict[str, object]] = []
    for index in range(900):
        if index < 50:
            throttle, brake = 0.0, 0.0
        elif index < 300:
            throttle, brake = 0.40, 0.0
        elif index < 580:
            throttle, brake = 1.0, 0.0
        elif index < 700:
            throttle, brake = 0.10, 0.0
        else:
            throttle, brake = 0.0, 0.45
        simulation.set_throttle(throttle)
        simulation.set_brake(brake)
        if index == 430:
            simulation.request_shift(1)
        frame = simulation.step(DT)
        result.append(_frame_payload(index, throttle, brake, frame))
    return result


def _neutral_trace(engine, drivetrain_spec) -> list[dict[str, object]]:
    from sim.drivetrain import AutomaticDrivetrain

    simulation = AutomaticDrivetrain(engine, drivetrain_spec, initial_gear=0)
    simulation.set_auto_shift(False)
    result: list[dict[str, object]] = []
    for index in range(600):
        throttle = 0.0 if index < 40 or index >= 420 else (1.0 if index < 260 else 0.25)
        simulation.set_throttle(throttle)
        simulation.set_brake(0.0)
        frame = simulation.step(DT)
        result.append(_frame_payload(index, throttle, 0.0, frame))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    arguments = parser.parse_args()
    engine, drivetrain = _load_alfa_specs()
    payload = {
        "schema": "byd-audio-lab-drivetrain-golden-v1",
        "profileId": "alfa-romeo-4c",
        "fixedStepSeconds": DT,
        "scenarios": {
            "drive": _drive_trace(engine, drivetrain),
            "neutral": _neutral_trace(engine, drivetrain),
        },
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Wrote {sum(len(rows) for rows in payload['scenarios'].values())} Audio Lab frames to {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
