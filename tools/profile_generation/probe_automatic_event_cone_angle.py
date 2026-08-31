#!/usr/bin/env python3
"""Prove an FMOD automatic Event Cone Angle gate without writing it as host input.

This is deliberately a small callback trace, not a car-family atlas render.
It isolates one retained waveform source in a derivative bank, holds its
ordinary authored parameters inside their placements, and changes only the
renderer's 3-D listener geometry.  The report fails closed if the source does
not have exactly one automatic cone-angle placement or either capture writes
that parameter explicitly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes
from realize_nrt_recipe import _load_audio_lab, _sha256, _write_atomic
from refine_full_event_atlas import _effect_isolation_muted_sources, _source_guid


SCHEMA = "byd-fmod-automatic-event-cone-angle-callback-trace-v1"


def _event(graph: Mapping[str, Any], event_path: str) -> Mapping[str, Any]:
    matches = [
        item for item in graph.get("events", [])
        if isinstance(item, Mapping) and item.get("path") == event_path
    ]
    if len(matches) != 1:
        raise ValueError(f"expected one graph event {event_path!r}, got {len(matches)}")
    return matches[0]


def _automatic_placement(
    event: Mapping[str, Any], source_guid: str
) -> Mapping[str, Any]:
    placements = [
        item for item in event.get("parameterPlacements", [])
        if isinstance(item, Mapping)
        and _source_guid(item.get("instrumentGuid")) == source_guid
        and str(item.get("parameterName") or "").casefold() == "event cone angle"
    ]
    if len(placements) != 1:
        raise ValueError(
            f"source {source_guid} needs exactly one Event Cone Angle placement, got {len(placements)}"
        )
    return placements[0]


def _source_name(graph: Mapping[str, Any], source_guid: str) -> str:
    matches = [
        item for item in graph.get("instruments", [])
        if isinstance(item, Mapping) and _source_guid(item.get("guid")) == source_guid
    ]
    if len(matches) != 1:
        raise ValueError(f"expected one source instrument {source_guid}, got {len(matches)}")
    sample = matches[0].get("sample")
    name = sample.get("name") if isinstance(sample, Mapping) else None
    if not isinstance(name, str) or not name:
        raise ValueError(f"source {source_guid} has no diagnostic sample name")
    return name


def _trace(
    *,
    renderer: Any,
    source_bank: Path,
    isolated_bank: Path,
    event_path: str,
    output: Path,
    parameters: Mapping[str, float],
    listener_position: tuple[float, float, float],
) -> dict[str, Any]:
    rendered = renderer.render_event_mix(
        source_bank,
        output,
        duration_frames=48_000,
        emitter_position=(0.0, 0.0, 0.0),
        listener_position=listener_position,
        events=[
            {
                "eventName": event_path,
                "bankPath": isolated_bank,
                "eventIdLookupBankPath": source_bank,
                "startFrame": 0,
                "hostGainLinear": 1.0,
                "parameters": dict(parameters),
            }
        ],
    )
    return {
        "wavSha256": _sha256(output),
        "scheduledSoundNamesByInstance": [
            list(names) for names in rendered.scheduled_sound_names_by_instance
        ],
        "channelSnapshots": list(rendered.channel_snapshots),
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--event-path", required=True)
    parser.add_argument("--source-guid", required=True)
    parser.add_argument("--rpms", type=float, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        bank = args.bank.resolve(strict=True)
        graph = json.loads(args.graph.read_text(encoding="utf-8"))
        source_guid = _source_guid(args.source_guid)
        event = _event(graph, args.event_path)
        placement = _automatic_placement(event, source_guid)
        diagnostic_name = _source_name(graph, source_guid)
        parameters = {"rpms": float(args.rpms), "throttle": 0.0}
        if "Event Cone Angle" in parameters:
            raise ValueError("automatic parameter may not be host written")
        if not float(placement["start"]) <= 0.0 <= float(placement["end"]):
            raise ValueError("front cone angle 0 is not inside the authored placement")
        if not 180.0 > float(placement["end"]):
            raise ValueError("rear cone angle 180 is unexpectedly inside authored placement")
        create_isolated_bank_copy, fully_muted_multi_instrument_guids, renderer_type, _loops = _load_audio_lab(
            args.audio_lab_root.resolve(strict=True)
        )
        muted, _waveforms = _effect_isolation_muted_sources(graph, args.event_path, source_guid)
        disabled = fully_muted_multi_instrument_guids(dict(graph), muted)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="automatic-cone-isolation-", dir=args.output_dir) as temporary_text:
            isolated = create_isolated_bank_copy(
                bank,
                dict(graph),
                muted,
                Path(temporary_text) / bank.name,
                disabled_parent_guids=disabled,
            )
            renderer = renderer_type(args.assetto_root.resolve(strict=True))
            front = _trace(
                renderer=renderer,
                source_bank=bank,
                isolated_bank=isolated.output_path,
                event_path=args.event_path,
                output=args.output_dir / "front-cone-0.wav",
                parameters=parameters,
                listener_position=(0.0, 0.0, -1.0),
            )
            rear = _trace(
                renderer=renderer,
                source_bank=bank,
                isolated_bank=isolated.output_path,
                event_path=args.event_path,
                output=args.output_dir / "rear-cone-180.wav",
                parameters=parameters,
                listener_position=(0.0, 0.0, 1.0),
            )
        front_names = front["scheduledSoundNamesByInstance"]
        rear_names = rear["scheduledSoundNamesByInstance"]
        front_pass = front_names == [[diagnostic_name]]
        rear_pass = rear_names == [[]]
        report = {
            "schema": SCHEMA,
            "bankSha256": _sha256(bank),
            "eventPath": args.event_path,
            "sourceGuid": source_guid,
            "diagnosticName": diagnostic_name,
            "automaticPlacement": {
                "parameter": "Event Cone Angle",
                "parameterGuid": placement["parameterGuid"],
                "layoutGuid": placement["layoutGuid"],
                "start": placement["start"],
                "end": placement["end"],
                "includeEnd": placement["includeEnd"],
            },
            "hostWrittenParameters": parameters,
            "automaticParameterHostWrite": False,
            "frontConeAngle": {"expectedDegrees": 0, **front, "pass": front_pass},
            "rearConeAngle": {"expectedDegrees": 180, **rear, "pass": rear_pass},
            "status": "PASS" if front_pass and rear_pass else "BLOCKED",
        }
        _write_atomic(args.report_output, canonical_json_bytes(report) + b"\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
