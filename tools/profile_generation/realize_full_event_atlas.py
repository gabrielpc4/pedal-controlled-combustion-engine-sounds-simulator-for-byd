#!/usr/bin/env python3
"""NRT-render every node in a full-event atlas plan through FMOD 1.10.11."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
import tempfile
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes  # noqa: E402
from generate_full_event_atlas_recipe import (  # noqa: E402
    ATLAS_PLAN_SCHEMA,
    ATLAS_REALIZATION_SCHEMA,
    current_nodes,
)
from realize_nrt_recipe import (  # noqa: E402
    NrtRecipeError,
    _canonical_wav,
    _load_audio_lab,
    _pcm_metrics,
    _read_pcm,
    _repair_loop,
    _sha256,
    _stereo_pcm,
    _write_atomic,
)


ENGINE_PROGRAM_GAINS = {
    "full": {"LOAD": 1.0, "COAST": 1.0, "UNAFFECTED": 1.0, "EXCLUDED": 0.0},
    "loadOnly": {"LOAD": 1.0, "COAST": 0.0, "UNAFFECTED": 1.0, "EXCLUDED": 0.0},
    "coastOnly": {"LOAD": 0.0, "COAST": 1.0, "UNAFFECTED": 1.0, "EXCLUDED": 0.0},
}


def _synchronized_loop_repair(
    pcms: Mapping[str, bytes],
    frame_count: int,
    find_best_loop_bounds: Any,
    crossfade_loop_seam: Any,
    *,
    sample_rate: int,
) -> tuple[dict[str, bytes], int, int, dict[str, float | None], bool]:
    guard = min(round(sample_rate * 0.020), max(1, frame_count // 12))
    full_seam = find_best_loop_bounds(
        pcms["full"],
        nominal_start=guard,
        nominal_end=frame_count - guard,
        search_frames=min(round(sample_rate * 0.015), guard),
    )
    seams = {
        label: find_best_loop_bounds(
            pcm,
            nominal_start=full_seam.start_frame,
            nominal_end=full_seam.end_frame,
            search_frames=0,
        )
        for label, pcm in pcms.items()
    }
    finite_peaks = [
        seam.peak_dbfs for seam in seams.values() if math.isfinite(seam.peak_dbfs)
    ]
    repaired = bool(finite_peaks and max(finite_peaks) > -36.0)
    if repaired:
        crossfade_frames = min(
            round(sample_rate * 0.020),
            (full_seam.end_frame - full_seam.start_frame) // 8,
        )
        repaired_pcms: dict[str, bytes] = {}
        repaired_seams: dict[str, Any] = {}
        for label, pcm in pcms.items():
            repaired_pcm, seam = crossfade_loop_seam(
                pcm,
                full_seam.start_frame,
                full_seam.end_frame,
                crossfade_frames=crossfade_frames,
            )
            repaired_pcms[label] = repaired_pcm
            repaired_seams[label] = seam
        pcms = repaired_pcms
        seams = repaired_seams
    unsafe = {
        label: seam.peak_dbfs
        for label, seam in seams.items()
        if math.isfinite(seam.peak_dbfs) and seam.peak_dbfs > -18.0
    }
    if unsafe:
        raise NrtRecipeError(f"engine program loop repair left unsafe outputs: {unsafe}")
    loop_starts = {seam.start_frame for seam in seams.values()}
    loop_ends = {seam.end_frame for seam in seams.values()}
    if len(loop_starts) != 1 or len(loop_ends) != 1:
        raise NrtRecipeError("independent engine programs received different loop geometry")

    return (
        dict(pcms),
        next(iter(loop_starts)),
        next(iter(loop_ends)),
        {
            label: seam.peak_dbfs if math.isfinite(seam.peak_dbfs) else None
            for label, seam in seams.items()
        },
        repaired,
    )


def _engine_sound_role_candidates(
    plan: Mapping[str, Any], perspective: str
) -> dict[str, tuple[str, ...]]:
    program = plan["perspectives"][perspective]
    event_path = str(program["eventPath"])
    candidates: dict[str, list[str]] = {}
    for source in program["requiredSourceCoverage"]:
        candidates.setdefault(str(source["diagnosticName"]), []).append("UNAFFECTED")
    for effect in plan["effects"]:
        if str(effect["eventPath"]) != event_path:
            continue
        for node in effect["nodes"]:
            if node.get("hostGainClass") == "engineEvent":
                candidates.setdefault(str(node["requiredDiagnosticName"]), []).append(
                    "EXCLUDED"
                )
    if not candidates:
        raise NrtRecipeError(f"{perspective} engine event has no source identity partition")

    return {name: tuple(roles) for name, roles in candidates.items()}


def _render_node(
    renderer: Any,
    bank: Path,
    event: str,
    node: Mapping[str, Any],
    output: Path,
    find_best_loop_bounds: Any,
    crossfade_loop_seam: Any,
    *,
    event_id_lookup_bank_path: Path | None = None,
) -> dict[str, Any]:
    lifetime = str(node.get("lifetime") or "continuous")
    required_name = node.get("requiredDiagnosticName")
    with tempfile.NamedTemporaryFile(
        prefix=".atlas-render-", suffix=".wav", dir=output.parent, delete=False
    ) as temporary:
        raw_output = Path(temporary.name)
    raw_output.unlink(missing_ok=True)
    try:
        rendered = renderer.render_event(
            bank,
            event,
            raw_output,
            parameters={
                str(key): float(value)
                for key, value in node.get("parameters", {}).items()
            },
            duration_frames=int(node.get("durationFrames", 96_000)),
            warmup_frames=int(node.get("warmupFrames", 36_000)),
            required_sound_name=(str(required_name) if required_name else None),
            maximum_takes=(
                int(node.get("maximumRequiredSourceTakes", 128))
                if required_name
                else 1
            ),
            event_id_lookup_bank_path=event_id_lookup_bank_path,
        )
        if required_name and required_name not in rendered.scheduled_sound_names:
            raise NrtRecipeError(
                f"full event {event} did not schedule required {required_name}"
            )
        pcm, frame_count, sample_rate, channels = _read_pcm(
            raw_output, require_nrt_format=True
        )
        pcm = _stereo_pcm(pcm, channels)
        if lifetime == "continuous":
            pcm, loop_start, loop_end, seam_dbfs, crossfaded = _repair_loop(
                pcm,
                frame_count,
                find_best_loop_bounds,
                crossfade_loop_seam,
                sample_rate=sample_rate,
            )
            loop_bounds = (loop_start, loop_end)
        else:
            loop_bounds = None
            loop_start = 0
            loop_end = frame_count
            seam_dbfs = None
            crossfaded = False
        if output.exists():
            raise NrtRecipeError(f"refusing to overwrite {output}")
        _write_atomic(
            output,
            _canonical_wav(
                pcm, sample_rate=sample_rate, loop_bounds=loop_bounds
            ),
        )
        return {
            "temporaryAssetName": output.name,
            "eventPath": rendered.event_path,
            "parameters": rendered.parameters,
            "requiredDiagnosticName": required_name,
            "scheduledDiagnosticNames": list(rendered.scheduled_sound_names),
            "frameCount": frame_count,
            "loopStartFrame": loop_start,
            "loopEndFrameExclusive": loop_end,
            "hasLoop": loop_bounds is not None,
            "loopSeamPeakDbfs": seam_dbfs,
            "loopCrossfaded": crossfaded,
            "wavSha256": _sha256(output),
            **_pcm_metrics(pcm),
        }
    finally:
        raw_output.unlink(missing_ok=True)


def _render_engine_node(
    renderer: Any,
    plan: Mapping[str, Any],
    perspective: str,
    bank: Path,
    event: str,
    node: Mapping[str, Any],
    output: Path,
    find_best_loop_bounds: Any,
    crossfade_loop_seam: Any,
    *,
    event_id_lookup_bank_path: Path | None = None,
) -> dict[str, Any]:
    mode_program_names = node.get("modeProgramTemporaryAssetNames")
    if (
        not isinstance(mode_program_names, Mapping)
        or set(mode_program_names) != {"loadOnly", "coastOnly"}
    ):
        raise NrtRecipeError("engine node has no LOAD_ONLY/COAST_ONLY program destinations")
    destinations = {
        "full": output,
        "loadOnly": output.parent / str(mode_program_names["loadOnly"]),
        "coastOnly": output.parent / str(mode_program_names["coastOnly"]),
    }
    if any(path.exists() for path in destinations.values()):
        raise NrtRecipeError("refusing to overwrite an engine mode-program capture")

    with tempfile.TemporaryDirectory(
        prefix=".atlas-engine-programs-", dir=output.parent
    ) as temporary_text:
        base_parameters = {
            str(key): float(value)
            for key, value in node.get("parameters", {}).items()
        }
        program_parameters = {
            "full": base_parameters,
            "loadOnly": {**base_parameters, "throttle": 1.0},
            "coastOnly": {**base_parameters, "throttle": 0.0},
        }
        pcms: dict[str, bytes] = {}
        program_evidence: dict[str, Any] = {}
        scheduled_names: set[str] = set()
        frame_count: int | None = None
        sample_rate: int | None = None
        event_path: str | None = None
        for label in ("full", "loadOnly", "coastOnly"):
            program_directory = Path(temporary_text) / label
            rendered = renderer.render_event_channel_masks(
                bank,
                event,
                program_directory,
                parameters=program_parameters[label],
                sound_roles=_engine_sound_role_candidates(plan, perspective),
                masks=[(label, ENGINE_PROGRAM_GAINS[label])],
                duration_frames=int(node.get("durationFrames", 96_000)),
                warmup_frames=int(node.get("warmupFrames", 36_000)),
                event_id_lookup_bank_path=event_id_lookup_bank_path,
            )
            if event_path is None:
                event_path = rendered.event_path
            elif rendered.event_path != event_path:
                raise NrtRecipeError("independent engine programs resolved different FMOD events")
            scheduled_names.update(rendered.scheduled_sound_names)
            path = rendered.output_paths[label]
            pcm, frames, rate, channels = _read_pcm(path, require_nrt_format=True)
            pcm = _stereo_pcm(pcm, channels)
            if frame_count is None:
                frame_count = frames
                sample_rate = rate
            elif frames != frame_count or rate != sample_rate:
                raise NrtRecipeError("independent engine programs differ in capture geometry")
            pcms[label] = pcm
            program_evidence[label] = {
                "parameters": program_parameters[label],
                "channelRoles": [
                    {
                        key: value
                        for key, value in item.items()
                        if key
                        in {
                            "channelId",
                            "soundName",
                            "role",
                            "declaredRoles",
                            "roleIdentityProbe",
                        }
                    }
                    for item in renderer.last_channel_mask_channels
                ],
            }
        if set(pcms) != set(ENGINE_PROGRAM_GAINS):
            raise NrtRecipeError("independent engine program capture omitted a mode")
        assert frame_count is not None and sample_rate is not None
        repaired_pcms, loop_start, loop_end, seam_peaks, repaired = (
            _synchronized_loop_repair(
                pcms,
                frame_count,
                find_best_loop_bounds,
                crossfade_loop_seam,
                sample_rate=sample_rate,
            )
        )
        for label, destination in destinations.items():
            _write_atomic(
                destination,
                _canonical_wav(
                    repaired_pcms[label],
                    sample_rate=sample_rate,
                    loop_bounds=(loop_start, loop_end),
                ),
            )

    mode_programs = {
        label: {
            "temporaryAssetName": destination.name,
            "wavSha256": _sha256(destination),
            **_pcm_metrics(repaired_pcms[label]),
        }
        for label, destination in destinations.items()
        if label != "full"
    }

    return {
        "temporaryAssetName": output.name,
        "eventPath": event_path,
        "parameters": {
            str(key): float(value) for key, value in node.get("parameters", {}).items()
        },
        "scheduledDiagnosticNames": sorted(scheduled_names),
        "frameCount": frame_count,
        "loopStartFrame": loop_start,
        "loopEndFrameExclusive": loop_end,
        "hasLoop": True,
        "loopSeamPeakDbfs": seam_peaks["full"],
        "loopCrossfaded": repaired,
        "wavSha256": _sha256(output),
        **_pcm_metrics(repaired_pcms["full"]),
        "modePrograms": mode_programs,
        "modeProgramOracle": {
            "method": "independentFreshEventMasterOutputPrograms-v1",
            "roleClassification": "authoredRouteGainResponseAtThrottleEndpoints",
            "full": "liveThrottleNodeWithEveryContinuousCoreRoleRetained",
            "loadOnly": "forcedThrottleOneWithEveryCOASTRouteMuted",
            "coastOnly": "forcedThrottleZeroWithEveryLOADRouteMuted",
            "unaffected": "retainedInEveryProgram",
            "finiteEngineEventSources": "mutedAndRenderedSeparatelyByLifecycleAtlas",
            "eventInstanceIsolation": "freshFmodSystemAndEventInstancePerProgram",
            "additiveRecomposition": "forbiddenBecauseSharedFmodDspMayBeStatefulOrNonlinear",
            "loopSeamPeakDbfs": seam_peaks,
            "programs": program_evidence,
            "status": "PASS",
        },
    }


def realize_atlas(
    plan: Mapping[str, Any],
    bank_path: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    output_directory: Path,
    *,
    maximum_nodes: int | None = None,
) -> dict[str, Any]:
    if plan.get("schema") != ATLAS_PLAN_SCHEMA:
        raise NrtRecipeError(f"plan is not {ATLAS_PLAN_SCHEMA}")
    bank = bank_path.resolve(strict=True)
    bank_sha_before = _sha256(bank)
    if bank_sha_before != plan.get("bankSha256"):
        raise NrtRecipeError("atlas plan bank SHA-256 differs from source bank")
    output_directory.mkdir(parents=True, exist_ok=True)
    (
        _create_isolated_bank_copy,
        _fully_muted_multi_instrument_guids,
        renderer_type,
        loop_tools,
    ) = _load_audio_lab(audio_lab_root)
    find_best_loop_bounds, crossfade_loop_seam = loop_tools
    renderer = renderer_type(assetto_root.resolve(strict=True))
    tasks: list[tuple[str | None, str, dict[str, Any]]] = []
    for perspective in ("cabin", "exterior"):
        atlas = plan["perspectives"][perspective]
        for raw_node in current_nodes(atlas):
            node = dict(raw_node)
            node["lifetime"] = "continuous"
            node["durationFrames"] = atlas["capture"]["durationFrames"]
            node["warmupFrames"] = atlas["capture"]["warmupFrames"]
            tasks.append((perspective, str(atlas["eventPath"]), node))
    for effect in plan["effects"]:
        tasks.extend(
            (None, str(effect["eventPath"]), dict(node))
            for node in effect["nodes"]
        )
    if maximum_nodes is not None:
        tasks = tasks[:maximum_nodes]
    captures = []
    for perspective, event, node in tasks:
        render = (
            _render_engine_node(
                renderer,
                plan,
                perspective,
                bank,
                event,
                node,
                output_directory / str(node["temporaryAssetName"]),
                find_best_loop_bounds,
                crossfade_loop_seam,
            )
            if perspective is not None
            else _render_node(
                renderer,
                bank,
                event,
                node,
                output_directory / str(node["temporaryAssetName"]),
                find_best_loop_bounds,
                crossfade_loop_seam,
            )
        )
        captures.append(render)
    bank_sha_after = _sha256(bank)
    if bank_sha_after != bank_sha_before:
        raise NrtRecipeError("source bank changed during full-event atlas render")
    return {
        "schema": ATLAS_REALIZATION_SCHEMA,
        "planSha256": plan["planSha256"],
        "atlasFamilyId": plan["id"],
        "sourceBankSha256Before": bank_sha_before,
        "sourceBankSha256After": bank_sha_after,
        "sourceBankUnchanged": True,
        "fullRun": maximum_nodes is None,
        "captureCount": len(captures),
        "captures": captures,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--maximum-nodes", type=int)
    args = parser.parse_args(argv)
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        report = realize_atlas(
            plan,
            args.bank,
            args.audio_lab_root,
            args.assetto_root,
            args.output_directory,
            maximum_nodes=args.maximum_nodes,
        )
        args.report_output.parent.mkdir(parents=True, exist_ok=True)
        args.report_output.write_bytes(canonical_json_bytes(report) + b"\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
