"""Certify optional ENGINE_START PCM captured from engine_int event-start timeline.

Each family may expose a crank/ignition slice at FMOD event start. Families without
audible event-start content simply omit the recipe; Android skips the feature when
effects.engineStart is false.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes


SCHEMA = "ac-fmod-engine-start-oracle-v1"
SAMPLE_RATE_HZ = 48000
CHANNELS = 2
DEFAULT_DURATION_SECONDS = 3.0


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_canonical(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    temporary.replace(path)


def build_verification(
    *,
    family_id: str,
    representative_car_id: str,
    event_path: str,
    idle_rpm: float,
    capture_wav: Path,
    duration_seconds: float = DEFAULT_DURATION_SECONDS,
) -> dict[str, object]:
    frame_count = int(round(duration_seconds * SAMPLE_RATE_HZ))
    return {
        "schema": SCHEMA,
        "familyId": family_id,
        "representativeCarId": representative_car_id,
        "eventPath": event_path,
        "captureParameters": {"rpms": idle_rpm, "throttle": 0.0},
        "durationFrames": frame_count,
        "captureWavSha256": _sha256(capture_wav),
        "captureWavRelativePath": capture_wav.name,
        "manifestRole": "ENGINE_START",
        "programTrigger": "ENGINE_START",
        "trackTrigger": "engineStart",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--family-id", required=True)
    parser.add_argument("--car-id", required=True)
    parser.add_argument("--event-path", default="event:/cars/{car}/engine_int")
    parser.add_argument("--idle-rpm", type=float, required=True)
    parser.add_argument("--capture-wav", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=float, default=DEFAULT_DURATION_SECONDS)
    args = parser.parse_args()

    if not args.capture_wav.is_file():
        raise SystemExit(f"capture wav not found: {args.capture_wav}")

    verification = build_verification(
        family_id=args.family_id,
        representative_car_id=args.car_id,
        event_path=args.event_path,
        idle_rpm=args.idle_rpm,
        capture_wav=args.capture_wav,
        duration_seconds=args.duration_seconds,
    )
    _write_canonical(args.output, verification)
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
