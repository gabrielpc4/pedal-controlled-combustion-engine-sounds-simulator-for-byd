#!/usr/bin/env python3
"""Empirically prove FMOD PlaySequential cursor scope for source-solo captures.

Each requested non-first child is first traced in the unmodified bank, then
captured from a source-solo derivative. The trace keeps a single EventInstance
alive and advances it only with the source's authored outside-to-inside
parameter-placement transition. It never stops/restarts the event, reorders
children, or masks the original scheduling proof.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import tempfile
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes  # noqa: E402
from preflight_full_event_effect_selection import (  # noqa: E402
    _placement_reentry_start_parameters,
)
from realize_nrt_recipe import _load_audio_lab, _sha256, _write_atomic  # noqa: E402
from refine_full_event_atlas import _effect_isolation_muted_sources, _source_guid  # noqa: E402


SCHEMA = "byd-fmod-playsequential-event-instance-cursor-probe-v2"


def _mapping(value: object, field: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{field} must be an object")
    return value


def _source_binding_parameters(
    plan: Mapping[str, Any], event_path: str, source_guid: str
) -> tuple[str, dict[str, float], dict[str, float], list[dict[str, Any]]]:
    matches: list[Mapping[str, Any]] = []
    for raw_event in plan.get("effects", []):
        event = _mapping(raw_event, "plan.effects[]")
        if event.get("eventPath") != event_path:
            continue
        for raw_node in event.get("nodes", []):
            node = _mapping(raw_node, "effect.nodes[]")
            for raw_binding in node.get("sourceBindings", []):
                binding = _mapping(raw_binding, "sourceBindings[]")
                if _source_guid(binding.get("sourceGuid")) == source_guid:
                    matches.append(binding)
    if not matches:
        raise ValueError(f"plan has no source binding for {source_guid}")
    values: set[bytes] = set()
    selected: tuple[str, dict[str, float], dict[str, float], list[dict[str, Any]]] | None = None
    for binding in matches:
        runtime = _mapping(binding.get("runtimeMapping"), "binding.runtimeMapping")
        capture = _mapping(runtime.get("captureParameters"), "captureParameters")
        params = {str(key): float(value) for key, value in capture.items()}
        start_parameters, placement_evidence = _placement_reentry_start_parameters(
            runtime,
            params,
            f"source binding {source_guid}.runtimeMapping",
        )
        key = canonical_json_bytes(
            {
                "parameters": params,
                "startParameters": start_parameters,
                "placementReentry": placement_evidence,
            }
        )
        values.add(key)
        diagnostic = str(binding.get("diagnosticName") or "").strip()
        if not diagnostic:
            raise ValueError(f"source binding {source_guid} has no diagnostic name")
        selected = diagnostic, params, start_parameters, placement_evidence
    if len(values) != 1 or selected is None:
        raise ValueError(f"source binding {source_guid} has non-unique capture parameters")
    return selected


def _sequential_parent(
    graph: Mapping[str, Any], source_guid: str
) -> tuple[Mapping[str, Any], int, list[str], list[str]]:
    parents: list[Mapping[str, Any]] = []
    for raw in graph.get("instruments", []):
        instrument = _mapping(raw, "graph.instruments[]")
        children = instrument.get("childInstruments", [])
        if not isinstance(children, Sequence) or isinstance(children, (str, bytes)):
            continue
        if any(
            isinstance(child, Mapping)
            and _source_guid(child.get("guid")) == source_guid
            for child in children
        ):
            parents.append(instrument)
    if len(parents) != 1:
        raise ValueError(f"source {source_guid} has {len(parents)} multi-instrument parents")
    parent = parents[0]
    playlist = _mapping(parent.get("playlist"), "parent.playlist")
    if playlist.get("playMode") != "PlaylistPlayMode_PlaySequential":
        raise ValueError(f"parent {parent.get('guid')} is not PlaySequential")
    raw_children = parent["childInstruments"]
    if len(raw_children) == 0 or not all(isinstance(child, Mapping) for child in raw_children):
        raise ValueError(f"parent {parent.get('guid')} has an invalid child list")
    ordered_children: list[tuple[int, Mapping[str, Any]]] = []
    for raw_child in raw_children:
        raw_order = raw_child.get("authoredOrder")
        if isinstance(raw_order, bool) or not isinstance(raw_order, int) or raw_order < 0:
            raise ValueError(
                f"parent {parent.get('guid')} has no explicit non-negative authoredOrder"
            )
        ordered_children.append((raw_order, raw_child))
    ordered_children.sort(key=lambda item: item[0])
    if [order for order, _child in ordered_children] != list(range(len(ordered_children))):
        raise ValueError(f"parent {parent.get('guid')} has non-contiguous authoredOrder values")
    child_guids = [_source_guid(child.get("guid")) for _order, child in ordered_children]
    index = child_guids.index(source_guid)
    if index == 0:
        raise ValueError("cursor probe needs a non-first sequential child")
    instruments = {
        _source_guid(item.get("guid")): _mapping(item, "graph.instruments[]")
        for item in graph.get("instruments", [])
        if isinstance(item, Mapping) and _source_guid(item.get("guid"))
    }
    child_names: list[str] = []
    for child_guid in child_guids:
        sample = _mapping(instruments[child_guid].get("sample"), f"{child_guid}.sample")
        child_names.append(str(sample.get("name") or "").strip())
    if not all(child_names):
        raise ValueError(f"parent {parent.get('guid')} has an unnamed playlist child")
    return parent, index, child_guids, child_names


def _trace_playlist_child_indexes(
    scheduled_by_take: Sequence[Sequence[str]], child_names: Sequence[str]
) -> list[int]:
    indexes: list[int] = []
    for take, names in enumerate(scheduled_by_take):
        matching = [index for index, name in enumerate(child_names) if name in names]
        if len(matching) != 1:
            raise ValueError(
                f"take {take} does not identify exactly one authored playlist child: {list(names)!r}"
            )
        indexes.append(matching[0])
    return indexes


def _probe_one(
    *,
    renderer: Any,
    bank: Path,
    graph: Mapping[str, Any],
    plan: Mapping[str, Any],
    event_path: str,
    source_guid: str,
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
    temp_root: Path,
) -> dict[str, Any]:
    source_guid = _source_guid(source_guid)
    diagnostic_name, parameters, start_parameters, placement_evidence = _source_binding_parameters(
        plan, event_path, source_guid
    )
    parent, authored_index, child_guids, child_names = _sequential_parent(graph, source_guid)
    child_count = len(child_guids)
    muted, _waveforms = _effect_isolation_muted_sources(graph, event_path, source_guid)
    disabled = fully_muted_multi_instrument_guids(dict(graph), muted)
    with tempfile.TemporaryDirectory(prefix="playlist-cursor-isolation-", dir=temp_root) as text:
        temporary = Path(text)
        isolated = create_isolated_bank_copy(
            bank,
            dict(graph),
            muted,
            temporary / bank.name,
            disabled_parent_guids=disabled,
        )
        # The original bank establishes scheduling semantics. No child is
        # muted here: every callback must map to exactly one authored child in
        # ascending source order over one persistent EventInstance.
        new_instance = renderer.render_event(
            bank,
            event_path,
            temporary / "new-instance.wav",
            parameters=parameters,
            start_parameters=start_parameters,
            duration_frames=1_024,
            warmup_frames=0,
            variant_index=child_count - 1,
            take_lifecycle="newEventInstancePerTake-v1",
        )
        new_instance_scheduled = [
            list(names) for names in new_instance.scheduled_sound_names_by_take
        ]
        new_instance_indexes = _trace_playlist_child_indexes(
            new_instance_scheduled, child_names
        )
        if len(set(new_instance_indexes)) != 1:
            raise ValueError(
                "new EventInstance did not reset to one deterministic initial playlist child: "
                f"{new_instance_indexes}"
            )
        expected_persistent_indexes = [
            (new_instance_indexes[0] + offset) % child_count
            for offset in range(child_count)
        ]
        original = renderer.render_event(
            bank,
            event_path,
            temporary / "placement-reentry-original.wav",
            parameters=parameters,
            start_parameters=start_parameters,
            duration_frames=1_024,
            warmup_frames=0,
            variant_index=child_count - 1,
            take_lifecycle="singleEventInstancePlacementReentry-v1",
        )
        original_scheduled = [list(names) for names in original.scheduled_sound_names_by_take]
        original_indexes = _trace_playlist_child_indexes(original_scheduled, child_names)
        if original_indexes != expected_persistent_indexes:
            raise ValueError(
                "same EventInstance placement reentry did not preserve ascending authored order "
                f"from the reset cursor {new_instance_indexes[0]}: {original_indexes}"
            )
        source_solo = renderer.render_event(
            isolated.output_path,
            event_path,
            temporary / "placement-reentry-source-solo.wav",
            parameters=parameters,
            start_parameters=start_parameters,
            duration_frames=1_024,
            warmup_frames=0,
            variant_index=child_count - 1,
            event_id_lookup_bank_path=bank,
            take_lifecycle="singleEventInstancePlacementReentry-v1",
        )
        scheduled = [list(names) for names in source_solo.scheduled_sound_names_by_take]
        matching = [
            index
            for index, names in enumerate(scheduled)
            if set(names) == {diagnostic_name}
        ]
        expected_target_take = expected_persistent_indexes.index(authored_index)
        if expected_target_take not in matching:
            raise ValueError(
                "source-solo placement reentry did not reach the target at its original authored "
                f"sequence position {expected_target_take}: {matching}"
            )
    return {
        "sourceGuid": source_guid,
        "diagnosticName": diagnostic_name,
        "parameters": parameters,
        "startParameters": start_parameters,
        "placementReentry": placement_evidence,
        "parentMultiInstrumentGuid": _source_guid(parent.get("guid")),
        "authoredChildIndex": authored_index,
        "authoredChildCount": child_count,
        "authoredChildSourceGuids": child_guids,
        "authoredChildDiagnosticNames": child_names,
        "newEventInstanceReset": {
            "pass": True,
            "selectedChildIndexes": new_instance_indexes,
            "scheduledDiagnosticNamesByTake": new_instance_scheduled,
            "takeLifecycle": "newEventInstancePerTake-v1",
        },
        "sameEventInstancePlacementReentry": {
            "pass": True,
            "expectedChildIndexesFromResetCursor": expected_persistent_indexes,
            "selectedChildIndexes": original_indexes,
            "scheduledDiagnosticNamesByTake": original_scheduled,
            "takeLifecycle": "singleEventInstancePlacementReentry-v1",
            "originalBankUnmasked": True,
        },
        "sourceSoloDerivative": {
            "pass": True,
            "expectedTargetTakeIndex": expected_target_take,
            "matchingTakeIndexes": matching,
            "scheduledDiagnosticNamesByTake": scheduled,
            "mutedSiblingSourceGuids": sorted(muted),
            "takeLifecycle": "singleEventInstancePlacementReentry-v1",
        },
        "pass": True,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--event-path", required=True)
    parser.add_argument("--source-guid", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        bank = args.bank.resolve(strict=True)
        graph = _mapping(json.loads(args.graph.read_text(encoding="utf-8")), "graph")
        plan = _mapping(json.loads(args.plan.read_text(encoding="utf-8")), "plan")
        create_isolated_bank_copy, fully_muted_multi_instrument_guids, renderer_type, _loops = _load_audio_lab(
            args.audio_lab_root.resolve(strict=True)
        )
        renderer = renderer_type(args.assetto_root.resolve(strict=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="playlist-cursor-probe-", dir=args.output.parent) as text:
            results = [
                _probe_one(
                    renderer=renderer,
                    bank=bank,
                    graph=graph,
                    plan=plan,
                    event_path=args.event_path,
                    source_guid=source_guid,
                    create_isolated_bank_copy=create_isolated_bank_copy,
                    fully_muted_multi_instrument_guids=fully_muted_multi_instrument_guids,
                    temp_root=Path(text),
                )
                for source_guid in args.source_guid
            ]
        report = {
            "schema": SCHEMA,
            "bankSha256": _sha256(bank),
            "eventPath": args.event_path,
            "sourceCount": len(results),
            "results": results,
            "allPass": bool(results) and all(item["pass"] for item in results),
        }
        _write_atomic(args.output, canonical_json_bytes(report) + b"\n")
    except Exception as exc:
        print(f"playlist cursor probe failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"allPass": report["allPass"], "sourceCount": len(results)}, sort_keys=True))
    return 0 if report["allPass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
