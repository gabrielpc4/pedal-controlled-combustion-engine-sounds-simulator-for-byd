#!/usr/bin/env python3
"""Short, hash-resumable FMOD source-selection and channel-budget preflight.

This preflight deliberately runs before any full-event NRT WAV realization.
It proves each retained *effect* source can be reached through its authored
event scheduler, after muting all other waveform siblings, without accepting
a lucky first SmartRandom/playlist take.  It also records short raw FMOD
logical/real channel snapshots for supported cabin/exterior engine and effect
scenarios.  No atlas PCM is retained: rendered WAVs live only in a temporary
directory for the duration of an individual proof.

The result is evidence, not an oracle PASS.  Full realization must still run
the longer source, static, dynamic, lifecycle and combined-mix oracles.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
import tempfile
import time
from typing import Any, Iterable, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes  # noqa: E402
from realize_nrt_recipe import (  # noqa: E402
    _load_audio_lab,
    _sha256,
    _write_atomic,
)
from refine_full_event_atlas import (  # noqa: E402
    ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
    _callback_source_guid_map,
    _channel_snapshot_scenario,
    _effect_isolation_muted_sources,
    _source_guid,
)


SCHEMA = "byd-full-event-effect-selection-preflight-v2"
STATE_SCHEMA = "byd-full-event-effect-selection-preflight-state-v2"
DEFAULT_DURATION_FRAMES = 1_024
DEFAULT_MAXIMUM_TAKES = 128


class PreflightError(ValueError):
    pass


def _canonical_sha256(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    _write_atomic(path, canonical_json_bytes(value) + b"\n")


def _source_bytes_sha256(paths: Iterable[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(str(path.resolve()).encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _required_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PreflightError(f"{field} must be a non-empty string")
    return value.strip()


def _required_mapping(value: object, field: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise PreflightError(f"{field} must be an object")
    return value


def _normalized_parameters(raw: object, field: str) -> dict[str, float]:
    mapping = _required_mapping(raw, field)
    result: dict[str, float] = {}
    for key, value in mapping.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise PreflightError(f"{field}.{key} must be a finite number")
        numeric = float(value)
        if not numeric == numeric or numeric in (float("inf"), float("-inf")):
            raise PreflightError(f"{field}.{key} must be finite")
        result[str(key)] = numeric
    return dict(sorted(result.items()))


def _load_json(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PreflightError(f"cannot read JSON {path}: {exc}") from exc
    return _required_mapping(value, str(path))


def _families(atlas_root: Path, staged_root: Path) -> list[dict[str, Any]]:
    """Load exactly one byte-identical plan for every deduplicated family."""

    by_id: dict[str, dict[str, Any]] = {}
    for plan_path in sorted(atlas_root.glob("cars/*/atlas-plan.json")):
        plan = _load_json(plan_path)
        family_id = _required_string(plan.get("id"), f"{plan_path}.id")
        car_id = plan_path.parent.name
        graph_path = staged_root / car_id / "graph.json"
        intake_path = staged_root / car_id / "intake.json"
        graph = _load_json(graph_path)
        intake = _load_json(intake_path)
        destination = Path(_required_string(intake.get("destination"), f"{intake_path}.destination"))
        bank_info = _required_mapping(graph.get("bank"), f"{graph_path}.bank")
        bank_name = _required_string(bank_info.get("fileName"), f"{graph_path}.bank.fileName")
        bank_path = destination / "sfx" / bank_name
        if not bank_path.is_file():
            raise PreflightError(f"source bank is missing: {bank_path}")
        expected_bank_sha = _required_string(plan.get("bankSha256"), f"{plan_path}.bankSha256")
        actual_bank_sha = _sha256(bank_path)
        if actual_bank_sha != expected_bank_sha:
            raise PreflightError(
                f"source bank hash differs from plan for {car_id}: "
                f"{actual_bank_sha} != {expected_bank_sha}"
            )
        candidate = {
            "familyId": family_id,
            "carId": car_id,
            "planPath": plan_path,
            "plan": plan,
            "planSha256": _required_string(plan.get("planSha256"), f"{plan_path}.planSha256"),
            "graphPath": graph_path,
            "graph": graph,
            "graphSha256": _sha256(graph_path),
            "bankPath": bank_path,
            "bankSha256": actual_bank_sha,
        }
        existing = by_id.get(family_id)
        if existing is None:
            candidate["aliasCarIds"] = [car_id]
            candidate["aliasPlanSha256s"] = [candidate["planSha256"]]
            by_id[family_id] = candidate
            continue
        # Assetto aliases can legitimately address the same byte-identical
        # bank through car-specific event paths, which changes the plan SHA.
        # The release batch deduplicates these into one bank family; use the
        # deterministic representative but retain every alias identity in the
        # evidence rather than claiming byte-identical plan JSON.
        if existing["bankSha256"] != candidate["bankSha256"]:
            raise PreflightError(f"family {family_id} aliases do not share a source bank")
        existing["aliasCarIds"].append(car_id)
        existing["aliasPlanSha256s"].append(candidate["planSha256"])
    if not by_id:
        raise PreflightError(f"no plans under {atlas_root}/cars")
    return sorted(by_id.values(), key=lambda item: (str(item["familyId"]), str(item["carId"])))


def _waveform_identity(
    graph: Mapping[str, Any], event_path: str, source_guid: str
) -> tuple[str, list[str]]:
    """Resolve a source name and record any callback-name collision.

    FMOD's callback exposes a diagnostic sound name rather than an instrument
    GUID.  A collision is not silently accepted: the temporary derivative
    mutes every other reachable waveform GUID, so a callback from the
    derivative can still be attributed to the one unmuted source.  The report
    retains the colliding GUID list as auditable evidence of that stronger
    source-isolation proof.
    """

    normalized_guid = _source_guid(source_guid)
    instruments = {
        _source_guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, Mapping)
        and _source_guid(item.get("guid"))
        and item.get("kind") == "WaveformInstrumentNode"
    }
    expected = instruments.get(normalized_guid)
    if expected is None:
        raise PreflightError(f"source GUID {normalized_guid} is not a waveform instrument")
    sample = expected.get("sample")
    name = sample.get("name") if isinstance(sample, Mapping) else None
    expected_name = _required_string(name, f"waveform {normalized_guid}.sample.name")
    event = next(
        (
            item
            for item in graph.get("events", [])
            if isinstance(item, Mapping) and item.get("path") == event_path
        ),
        None,
    )
    if not isinstance(event, Mapping):
        raise PreflightError(f"graph has no event {event_path}")
    reachable = event.get("reachableInstrumentGuids")
    if not isinstance(reachable, Sequence) or isinstance(reachable, (str, bytes)):
        raise PreflightError(f"event {event_path} has no reachable waveform evidence")
    matching = sorted(
        guid
        for guid in (_source_guid(value) for value in reachable)
        if guid in instruments
        and isinstance(instruments[guid].get("sample"), Mapping)
        and instruments[guid]["sample"].get("name") == expected_name
    )
    if normalized_guid not in matching:
        raise PreflightError(
            f"callback name {expected_name!r} is not reachable from source {normalized_guid}: "
            f"reachable GUIDs={matching}"
        )
    return expected_name, matching


def _finite_number(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PreflightError(f"{field} must be a finite number")
    numeric = float(value)
    if not math.isfinite(numeric):
        raise PreflightError(f"{field} must be finite")
    return numeric


def _placement_reentry_start_parameters(
    runtime_mapping: Mapping[str, Any],
    parameters: Mapping[str, float],
    field: str,
) -> tuple[dict[str, float], list[dict[str, Any]]]:
    """Derive one exact host-controllable state outside all source placements.

    A PlaySequential cursor is meaningful only while one EventInstance stays
    alive. We therefore retrigger it through the event's authored
    outside-to-inside placement transition, rather than stopping/restarting
    the event or changing its child order. This is deliberately fail-closed:
    a source with no graph-proven parameter-domain endpoint outside one of its
    placement chains cannot be selected by this preflight.
    """

    entry = _required_mapping(
        runtime_mapping.get("parameterPlacementEntry"),
        f"{field}.parameterPlacementEntry",
    )
    membership = _required_mapping(
        entry.get("membership"),
        f"{field}.parameterPlacementEntry.membership",
    )
    placements_by_parameter = _required_mapping(
        membership.get("placements"),
        f"{field}.parameterPlacementEntry.membership.placements",
    )
    domains = _required_mapping(
        runtime_mapping.get("parameterDomains"), f"{field}.parameterDomains"
    )
    result = dict(parameters)
    candidates: list[tuple[str, float, list[dict[str, Any]]]] = []
    for raw_parameter in sorted(placements_by_parameter):
        parameter = _required_string(raw_parameter, f"{field}.placement parameter")
        raw_placements = placements_by_parameter[raw_parameter]
        if (
            not isinstance(raw_placements, Sequence)
            or isinstance(raw_placements, (str, bytes))
            or not raw_placements
        ):
            raise PreflightError(f"{field}.placements.{parameter} must be a non-empty array")
        raw_domain = domains.get(parameter)
        if (
            not isinstance(raw_domain, Sequence)
            or isinstance(raw_domain, (str, bytes))
            or len(raw_domain) != 2
        ):
            raise PreflightError(
                f"{field}.parameterDomains.{parameter} must give an exact [minimum, maximum]"
            )
        lower = _finite_number(raw_domain[0], f"{field}.parameterDomains.{parameter}[0]")
        upper = _finite_number(raw_domain[1], f"{field}.parameterDomains.{parameter}[1]")
        if lower >= upper:
            raise PreflightError(f"{field}.parameterDomains.{parameter} must be strictly increasing")
        placement_evidence: list[dict[str, Any]] = []
        starts: list[float] = []
        ends: list[float] = []
        for index, raw_placement in enumerate(raw_placements):
            placement = _required_mapping(
                raw_placement, f"{field}.placements.{parameter}[{index}]"
            )
            start = _finite_number(
                placement.get("start"), f"{field}.placements.{parameter}[{index}].start"
            )
            end = _finite_number(
                placement.get("end"), f"{field}.placements.{parameter}[{index}].end"
            )
            if start > end:
                raise PreflightError(f"{field}.placements.{parameter}[{index}] has inverted bounds")
            starts.append(start)
            ends.append(end)
            placement_evidence.append(
                {
                    "instrumentGuid": _required_string(
                        placement.get("instrumentGuid"),
                        f"{field}.placements.{parameter}[{index}].instrumentGuid",
                    ),
                    "layoutGuid": _required_string(
                        placement.get("layoutGuid"),
                        f"{field}.placements.{parameter}[{index}].layoutGuid",
                    ),
                    "parameterGuid": _required_string(
                        placement.get("parameterGuid"),
                        f"{field}.placements.{parameter}[{index}].parameterGuid",
                    ),
                    "start": start,
                    "end": end,
                    "includeEnd": placement.get("includeEnd") is True,
                }
            )
        # Boundaries are inclusive in the current release contract. Use a
        # strictly outside domain endpoint, never a guessed epsilon.
        if all(lower < start for start in starts):
            candidates.append((parameter, lower, placement_evidence))
        elif all(upper > end for end in ends):
            candidates.append((parameter, upper, placement_evidence))
    if not candidates:
        raise PreflightError(
            f"{field} has no host-controllable parameter-domain endpoint outside all placement chains"
        )
    parameter, outside, evidence = candidates[0]
    result[parameter] = outside
    return dict(sorted(result.items())), [
        {
            "parameter": parameter,
            "outsideValue": outside,
            "parameterDomain": [
                _finite_number(
                    domains[parameter][0], f"{field}.parameterDomains.{parameter}[0]"
                ),
                _finite_number(
                    domains[parameter][1], f"{field}.parameterDomains.{parameter}[1]"
                ),
            ],
            "placements": evidence,
        }
    ]


def _effect_source_tasks(family: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Build one short proof per retained source-binding capture state.

    A source GUID alone is insufficient: finite sources can be reachable only
    inside a placement interval.  The binding's authored capture parameters
    are therefore the proof input.  Choosing the numerically first atlas node
    would test a point outside the source placement (for example, a gear
    source at state=0 instead of its authored state=1 capture) and produce a
    false scheduler blocker.
    """

    plan = _required_mapping(family.get("plan"), "family.plan")
    graph = _required_mapping(family.get("graph"), "family.graph")
    chosen: dict[tuple[str, str, str, bytes], dict[str, Any]] = {}
    for raw_event in plan.get("effects", []):
        event = _required_mapping(raw_event, "plan.effects[]")
        event_path = _required_string(event.get("eventPath"), "effect.eventPath")
        perspectives = event.get("perspectives")
        if not isinstance(perspectives, Sequence) or isinstance(perspectives, (str, bytes)):
            raise PreflightError(f"effect {event_path} has no perspective scope")
        nodes = event.get("nodes")
        if not isinstance(nodes, Sequence) or isinstance(nodes, (str, bytes)) or not nodes:
            raise PreflightError(f"effect {event_path} has no nodes")
        for raw_node in nodes:
            node = _required_mapping(raw_node, f"effect {event_path}.nodes[]")
            source_guid = _source_guid(node.get("requiredSourceGuid"))
            if not source_guid:
                raise PreflightError(f"effect {event_path} node has no requiredSourceGuid")
            diagnostic_name = _required_string(
                node.get("requiredDiagnosticName"),
                f"effect {event_path} {source_guid}.requiredDiagnosticName",
            )
            graph_name, colliding_source_guids = _waveform_identity(
                graph, event_path, source_guid
            )
            if diagnostic_name != graph_name:
                raise PreflightError(
                    f"plan/graph diagnostic identity differs for {event_path} {source_guid}: "
                    f"{diagnostic_name!r} != {graph_name!r}"
                )
            bindings = node.get("sourceBindings")
            if not isinstance(bindings, Sequence) or isinstance(bindings, (str, bytes)):
                raise PreflightError(f"effect {event_path} {source_guid} has no source binding")
            matching_bindings = [
                _required_mapping(item, f"effect {event_path} {source_guid}.sourceBindings[]")
                for item in bindings
                if isinstance(item, Mapping)
                and _source_guid(item.get("sourceGuid")) == source_guid
            ]
            if not matching_bindings:
                raise PreflightError(f"effect {event_path} {source_guid} has no matching source binding")
            for binding in matching_bindings:
                runtime_mapping = _required_mapping(
                    binding.get("runtimeMapping"),
                    f"effect {event_path} {source_guid}.runtimeMapping",
                )
                parameters = _normalized_parameters(
                    runtime_mapping.get("captureParameters"),
                    f"effect {event_path} {source_guid}.captureParameters",
                )
                binding_id = _required_string(
                    binding.get("bindingId"),
                    f"effect {event_path} {source_guid}.bindingId",
                )
                scheduling_group = _required_mapping(
                    runtime_mapping.get("schedulingGroup"),
                    f"effect {event_path} {source_guid}.schedulingGroup",
                )
                selection = _required_mapping(
                    scheduling_group.get("selection"),
                    f"effect {event_path} {source_guid}.schedulingGroup.selection",
                )
                play_mode = str(selection.get("playMode") or "")
                start_parameters: dict[str, float] | None = None
                placement_reentry: list[dict[str, Any]] = []
                if play_mode == "PlaylistPlayMode_PlaySequential":
                    start_parameters, placement_reentry = _placement_reentry_start_parameters(
                        runtime_mapping,
                        parameters,
                        f"effect {event_path} {source_guid}.runtimeMapping",
                    )
                    take_lifecycle = "singleEventInstancePlacementReentry-v1"
                else:
                    take_lifecycle = "newEventInstancePerTake-v1"
                candidate = {
                    "eventPath": event_path,
                    "sourceGuid": source_guid,
                    "bindingId": binding_id,
                    "diagnosticName": diagnostic_name,
                    "parameters": parameters,
                    "startParameters": start_parameters,
                    "placementReentry": placement_reentry,
                    "takeLifecycle": take_lifecycle,
                    "perspectives": sorted(str(value) for value in perspectives),
                    "callbackDiagnosticNameReachableSourceGuids": colliding_source_guids,
                    "lifetime": str(node.get("lifetime") or ""),
                }
                key = (event_path, source_guid, binding_id, canonical_json_bytes(parameters))
                chosen[key] = candidate
    tasks: list[dict[str, Any]] = []
    for value in chosen.values():
        task = {
            "schema": "byd-full-event-effect-source-selection-task-v1",
            "familyId": str(family["familyId"]),
            "familyPlanSha256": str(family["planSha256"]),
            "sourceBankSha256": str(family["bankSha256"]),
            "eventPath": value["eventPath"],
            "sourceGuid": value["sourceGuid"],
            "bindingId": value["bindingId"],
            "diagnosticName": value["diagnosticName"],
            "parameters": value["parameters"],
            "startParameters": value["startParameters"],
            "placementReentry": value["placementReentry"],
            "takeLifecycle": value["takeLifecycle"],
            "perspectives": value["perspectives"],
            "callbackDiagnosticNameReachableSourceGuids": value[
                "callbackDiagnosticNameReachableSourceGuids"
            ],
            "durationFrames": DEFAULT_DURATION_FRAMES,
            "maximumDeterministicTakes": DEFAULT_MAXIMUM_TAKES,
        }
        task["taskSpecSha256"] = _canonical_sha256(task)
        tasks.append(task)
    return sorted(tasks, key=lambda item: (item["familyId"], item["eventPath"], item["sourceGuid"]))


def _select_and_isolate(
    *,
    renderer: Any,
    family: Mapping[str, Any],
    task: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
    temporary_root: Path,
) -> dict[str, Any]:
    graph = _required_mapping(family.get("graph"), "family.graph")
    event_path = _required_string(task.get("eventPath"), "task.eventPath")
    source_guid = _source_guid(task.get("sourceGuid"))
    muted, all_waveforms = _effect_isolation_muted_sources(graph, event_path, source_guid)
    if source_guid not in all_waveforms:
        raise PreflightError(f"source {source_guid} was not in isolated event waveform set")
    bank_path = Path(str(family["bankPath"])).resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="effect-selection-isolation-", dir=temporary_root) as temp_text:
        temporary = Path(temp_text)
        if muted:
            disabled = fully_muted_multi_instrument_guids(dict(graph), muted)
            isolated_bank = create_isolated_bank_copy(
                bank_path,
                dict(graph),
                muted,
                temporary / bank_path.name,
                disabled_parent_guids=disabled,
            ).output_path
            isolation_method = "sourceSoloEventRoutingAndBusDsp-v1"
        else:
            # No derivative is necessary when graph reachability proves this
            # is the event's only waveform.  The bank-isolation helper rejects
            # an empty patch by design; using the original read-only bank here
            # is stronger than inventing a no-op mutation.
            disabled = set()
            isolated_bank = bank_path
            isolation_method = "singleReachableWaveformNoIsolationRequired-v1"
        wav = temporary / "selection.wav"
        rendered = renderer.render_event(
            isolated_bank,
            event_path,
            wav,
            parameters=_normalized_parameters(task.get("parameters", {}), "task.parameters"),
            start_parameters=(
                None
                if task.get("startParameters") is None
                else _normalized_parameters(task.get("startParameters"), "task.startParameters")
            ),
            duration_frames=DEFAULT_DURATION_FRAMES,
            warmup_frames=0,
            required_sound_name=_required_string(task.get("diagnosticName"), "task.diagnosticName"),
            maximum_takes=DEFAULT_MAXIMUM_TAKES,
            event_id_lookup_bank_path=bank_path,
            take_lifecycle=_required_string(task.get("takeLifecycle"), "task.takeLifecycle"),
        )
        scheduled_by_take = [list(names) for names in rendered.scheduled_sound_names_by_take]
        selected = list(rendered.scheduled_sound_names)
        expected = _required_string(task.get("diagnosticName"), "task.diagnosticName")
        if set(selected) != {expected}:
            raise PreflightError(
                f"selected source contribution is contaminated: expected {expected!r}, got {selected!r}"
            )
        matching_takes = [
            index for index, names in enumerate(scheduled_by_take)
            if set(names) == {expected}
        ]
        if not matching_takes:
            raise PreflightError(f"no deterministic take selected required source {expected!r}")
        trace = {
            "scheduledDiagnosticNamesByTake": scheduled_by_take,
            "selectedDiagnosticNames": selected,
            "matchingTakeIndexes": matching_takes,
        }
        return {
            "pass": True,
            "mutedWaveformSourceCount": len(muted),
            "disabledMultiInstrumentParentCount": len(disabled),
            "sourceIsolationMethod": isolation_method,
            "scheduledTraceSha256": _canonical_sha256(trace),
            "matchingTakeIndexes": matching_takes,
            "selectedDiagnosticNames": selected,
            "takeCountObserved": len(scheduled_by_take),
            "temporaryWavRetained": False,
        }


def _perspective_parameters(perspective: Mapping[str, Any], aggressive: bool) -> dict[str, float]:
    axes = _required_mapping(perspective.get("axes"), "perspective.axes")
    rpm = axes.get("rpm")
    throttle = axes.get("throttle")
    if (
        not isinstance(rpm, Sequence) or isinstance(rpm, (str, bytes)) or not rpm
        or not isinstance(throttle, Sequence) or isinstance(throttle, (str, bytes)) or not throttle
    ):
        raise PreflightError("perspective axes need non-empty rpm/throttle arrays")
    return {
        "rpms": float(max(rpm) if aggressive else min(rpm)),
        "throttle": float(max(throttle) if aggressive else min(throttle)),
    }


def _channel_snapshot_tasks(family: Mapping[str, Any]) -> list[dict[str, Any]]:
    plan = _required_mapping(family.get("plan"), "family.plan")
    tasks: list[dict[str, Any]] = []
    for perspective_name in ("cabin", "exterior"):
        perspective = _required_mapping(
            _required_mapping(plan.get("perspectives"), "plan.perspectives").get(perspective_name),
            f"plan.perspectives.{perspective_name}",
        )
        for aggressive in (False, True):
            tasks.append(
                {
                    "id": f"engine-{'aggressive' if aggressive else 'default'}-{perspective_name}",
                    "kind": "preflightEngineHold",
                    "perspective": perspective_name,
                    "events": [
                        {
                            "eventName": _required_string(perspective.get("eventPath"), "perspective.eventPath"),
                            "parameters": _perspective_parameters(perspective, aggressive),
                            "hostGainLinear": 0.5,
                        }
                    ],
                }
            )
    for raw_event in plan.get("effects", []):
        event = _required_mapping(raw_event, "plan.effects[]")
        nodes = event.get("nodes")
        if not isinstance(nodes, Sequence) or isinstance(nodes, (str, bytes)) or not nodes:
            raise PreflightError("effect snapshot task has no nodes")
        first = _required_mapping(nodes[0], "effect.nodes[0]")
        for perspective_name in event.get("perspectives", []):
            perspective_name = _required_string(perspective_name, "effect.perspectives[]")
            perspective = _required_mapping(plan["perspectives"].get(perspective_name), "effect perspective")
            tasks.append(
                {
                    "id": f"effect-{str(event.get('eventSuffix') or 'unknown')}-{perspective_name}",
                    "kind": "preflightCombinedEngineEffect",
                    "perspective": perspective_name,
                    "events": [
                        {
                            "eventName": _required_string(perspective.get("eventPath"), "perspective.eventPath"),
                            "parameters": _perspective_parameters(perspective, True),
                            "hostGainLinear": 0.5,
                        },
                        {
                            "eventName": _required_string(event.get("eventPath"), "effect.eventPath"),
                            "parameters": _normalized_parameters(first.get("parameters", {}), "effect node parameters"),
                            "hostGainLinear": 0.5 if str(first.get("hostGainClass")) == "engineEvent" else 1.0,
                        },
                    ],
                }
            )
    return tasks


def _snapshot(
    *, renderer: Any,
    family: Mapping[str, Any],
    task: Mapping[str, Any],
    temporary_root: Path,
) -> dict[str, Any]:
    bank_path = Path(str(family["bankPath"])).resolve(strict=True)
    events = [
        {
            **event,
            "bankPath": bank_path,
            "eventIdLookupBankPath": bank_path,
            "startFrame": 0 if index == 0 else 256,
        }
        for index, event in enumerate(task["events"])
    ]
    with tempfile.TemporaryDirectory(prefix="effect-channel-snapshot-", dir=temporary_root) as temp_text:
        output = Path(temp_text) / "snapshot.wav"
        rendered = renderer.render_event_mix(
            bank_path,
            output,
            events=events,
            duration_frames=DEFAULT_DURATION_FRAMES,
        )
        result = _preflight_candidate_snapshot_scenario(
            family["plan"],
            perspective=_required_string(task.get("perspective"), "snapshot perspective"),
            identifier=_required_string(task.get("id"), "snapshot id"),
            kind=_required_string(task.get("kind"), "snapshot kind"),
            snapshots=list(rendered.channel_snapshots),
            scheduled_by_instance=[list(names) for names in rendered.scheduled_sound_names_by_instance],
            event_paths=[str(event["eventName"]) for event in task["events"]],
        )
        result["scheduledSoundNamesByInstance"] = [
            list(names) for names in rendered.scheduled_sound_names_by_instance
        ]
        result["scheduledTraceSha256"] = _canonical_sha256(
            result["scheduledSoundNamesByInstance"]
        )
        result["temporaryWavRetained"] = False
        return result


def _event_callback_candidates(
    plan: Mapping[str, Any], *, perspective: str, event_path: str
) -> tuple[dict[str, set[str]], dict[str, dict[str, set[str]]]]:
    """Return retained source candidates for one started event instance.

    A raw FMOD SOUND_PLAYED callback carries its diagnostic name but no
    instrument GUID.  Scope candidates to the exact event instance and
    perspective; cross-event name matches are not acceptable reconciliation.
    """

    perspective_data = _required_mapping(
        _required_mapping(plan.get("perspectives"), "plan.perspectives").get(perspective),
        f"plan.perspectives.{perspective}",
    )
    result: dict[str, set[str]] = {}
    categories: dict[str, dict[str, set[str]]] = {}

    def add(name: str, guid: str, category: str) -> None:
        result.setdefault(name, set()).add(guid)
        categories.setdefault(name, {}).setdefault(category, set()).add(guid)

    if perspective_data.get("eventPath") == event_path:
        coverage = perspective_data.get("requiredSourceCoverage")
        if not isinstance(coverage, Sequence) or isinstance(coverage, (str, bytes)):
            raise PreflightError(f"engine event {event_path} has no retained source coverage")
        for raw in coverage:
            item = _required_mapping(raw, "engine requiredSourceCoverage[]")
            name = _required_string(item.get("diagnosticName"), "engine source diagnosticName")
            guid = _source_guid(item.get("sourceGuid"))
            if not guid:
                raise PreflightError(f"engine callback candidate {name!r} has no source GUID")
            add(name, guid, "engineContinuous")
    effects = plan.get("effects")
    if not isinstance(effects, Sequence) or isinstance(effects, (str, bytes)):
        raise PreflightError("plan has no effects")
    matching_events = [
        _required_mapping(item, "plan.effects[]")
        for item in effects
        if isinstance(item, Mapping) and item.get("eventPath") == event_path
    ]
    if len(matching_events) > 1:
        raise PreflightError(
            f"callback event {event_path} has {len(matching_events)} retained effect mappings"
        )
    if matching_events:
        category = (
            "engineEventTransient"
            if perspective_data.get("eventPath") == event_path
            else "effectEvent"
        )
        contract = _required_mapping(
            matching_events[0].get("runtimeLifecycleParameterVariantContract"),
            f"effect {event_path}.runtimeLifecycleParameterVariantContract",
        )
        variants = contract.get("variants")
        if not isinstance(variants, Sequence) or isinstance(variants, (str, bytes)):
            raise PreflightError(f"effect {event_path} has no retained variants")
        for raw in variants:
            variant = _required_mapping(raw, f"effect {event_path}.variants[]")
            mapping = _required_mapping(variant.get("runtimeMapping"), "effect variant runtimeMapping")
            scopes = mapping.get("perspectives")
            if (
                not isinstance(scopes, Sequence)
                or isinstance(scopes, (str, bytes))
                or perspective not in scopes
            ):
                continue
            name = _required_string(variant.get("diagnosticName"), "effect variant diagnosticName")
            guid = _source_guid(variant.get("sourceGuid"))
            if not guid:
                raise PreflightError(f"effect callback candidate {name!r} has no source GUID")
            add(name, guid, category)
    if not result:
        raise PreflightError(f"effect {event_path} has no retained {perspective} callback candidates")
    return result, categories


def _preflight_candidate_snapshot_scenario(
    plan: Mapping[str, Any],
    *,
    perspective: str,
    identifier: str,
    kind: str,
    snapshots: object,
    scheduled_by_instance: object,
    event_paths: Sequence[str],
) -> dict[str, Any]:
    """Record candidate-set-only raw callback reconciliation for preflight.

    This intentionally does *not* weaken the final oracle's exact GUID gate.
    It is the only honest interpretation available in this short raw-channel
    probe because FMOD's callback omits instrument GUIDs.  Exact source GUID
    evidence comes separately from the source-solo task for each retained
    effect variant.
    """

    strict = _channel_snapshot_scenario(
        plan,
        perspective=perspective,
        identifier=identifier,
        kind=kind,
        snapshots=snapshots,
        scheduled_by_instance=scheduled_by_instance,
    )
    # Do not leave the final-oracle-only fields in this weaker evidence type:
    # their names imply exact source GUID attribution, which FMOD did not
    # expose for this callback.
    candidate_base = {
        key: value
        for key, value in strict.items()
        if key
        not in {
            "rawSourceGuidCallbackBindings",
            "unresolvedCallbackNames",
            "ambiguousCallbackNames",
        }
    }
    if (
        not isinstance(scheduled_by_instance, Sequence)
        or isinstance(scheduled_by_instance, (str, bytes))
        or len(scheduled_by_instance) != len(event_paths)
        or "maximumLogicalChannels" not in strict
        or strict.get("maximumLogicalChannels", 0) > ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP
        or strict.get("maximumRealChannels", 0) > ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
    ):
        return {
            **candidate_base,
            "callbackIdentityResolution": "candidateSetOnly",
            "sourceSoloExactGuidEvidenceRequired": True,
        }
    occurrences: list[dict[str, Any]] = []
    unknown: list[dict[str, Any]] = []
    incompatible_multiplicity: list[dict[str, Any]] = []
    for instance_index, (event_path, names) in enumerate(
        zip(event_paths, scheduled_by_instance)
    ):
        if not isinstance(names, Sequence) or isinstance(names, (str, bytes)):
            return {
                **candidate_base,
                "pass": False,
                "reason": "malformedCallbackTrace",
                "callbackIdentityResolution": "candidateSetOnly",
                "sourceSoloExactGuidEvidenceRequired": True,
            }
        candidates_by_name, candidates_by_category = _event_callback_candidates(
            plan, perspective=perspective, event_path=event_path
        )
        observed_counts: dict[str, int] = {}
        for raw_name in names:
            name = str(raw_name)
            observed_counts[name] = observed_counts.get(name, 0) + 1
        for name, count in sorted(observed_counts.items()):
            candidates = sorted(candidates_by_name.get(name, set()))
            category_sets = {
                category: sorted(values)
                for category, values in sorted(
                    candidates_by_category.get(name, {}).items()
                )
            }
            item = {
                "eventInstanceIndex": instance_index,
                "eventPath": event_path,
                "diagnosticName": name,
                "candidateSourceGuids": candidates,
                "candidateSourcesByCategory": category_sets,
                "candidateGuidMultiplicity": len(candidates),
                "observedCallbackMultiplicity": count,
                "maximumCompatibleCallbackMultiplicity": len(candidates),
            }
            occurrences.append(item)
            if not candidates:
                unknown.append(item)
            elif count > len(candidates):
                incompatible_multiplicity.append(item)
    passed = not unknown and not incompatible_multiplicity
    return {
        **candidate_base,
        "pass": passed,
        "reason": (
            None
            if passed
            else "candidateSetCallbackGuidReconciliationOrMultiplicityFailed"
        ),
        "callbackIdentityResolution": "candidateSetOnly",
        "sourceSoloExactGuidEvidenceRequired": True,
        "callbackCandidateOccurrences": occurrences,
        "unknownEventPerspectiveCallbackNames": unknown,
        "incompatibleCallbackMultiplicities": incompatible_multiplicity,
        "exactCallbackGuidAttributionPass": False,
    }


def _state_contract(
    families: Sequence[Mapping[str, Any]], source_hash: str
) -> str:
    return _canonical_sha256(
        {
            "schema": STATE_SCHEMA,
            "implementationSourceSha256": source_hash,
            "families": [
                {
                    "id": item["familyId"],
                    "planSha256": item["planSha256"],
                    "graphSha256": item["graphSha256"],
                    "bankSha256": item["bankSha256"],
                }
                for item in families
            ],
            "durationFrames": DEFAULT_DURATION_FRAMES,
            "maximumDeterministicTakes": DEFAULT_MAXIMUM_TAKES,
            "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        }
    )


def _record_error(exc: BaseException) -> dict[str, Any]:
    return {
        "pass": False,
        "errorType": type(exc).__name__,
        "error": str(exc),
    }


def run(
    *,
    atlas_root: Path,
    staged_root: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    output_root: Path,
    car_id: str | None = None,
    retry_failed: bool = False,
    partition_count: int = 1,
    partition_index: int = 0,
) -> Mapping[str, Any]:
    atlas_root = atlas_root.resolve(strict=True)
    staged_root = staged_root.resolve(strict=True)
    audio_lab_root = audio_lab_root.resolve(strict=True)
    assetto_root = assetto_root.resolve(strict=True)
    output_root.mkdir(parents=True, exist_ok=True)
    if (
        isinstance(partition_count, bool)
        or not isinstance(partition_count, int)
        or partition_count < 1
        or isinstance(partition_index, bool)
        or not isinstance(partition_index, int)
        or not 0 <= partition_index < partition_count
    ):
        raise PreflightError("partition index must be within a positive partition count")
    families = _families(atlas_root, staged_root)
    if car_id is not None:
        families = [item for item in families if item["carId"] == car_id]
        if len(families) != 1:
            raise PreflightError(f"expected exactly one family for car ID {car_id!r}")
    sources = [
        SCRIPT_ROOT / "preflight_full_event_effect_selection.py",
        SCRIPT_ROOT / "refine_full_event_atlas.py",
        SCRIPT_ROOT / "realize_full_event_atlas.py",
        audio_lab_root / "sim" / "fmod_renderer.py",
        audio_lab_root / "sim" / "fmod_bank_isolation.py",
    ]
    source_hash = _source_bytes_sha256(sources)
    preflight_contract_sha = _state_contract(families, source_hash)
    contract_sha = _canonical_sha256(
        {
            "preflightContractSha256": preflight_contract_sha,
            "partitionCount": partition_count,
            "partitionIndex": partition_index,
        }
    )
    state_path = output_root / "state.json"
    if state_path.is_file():
        state = _load_json(state_path)
        if (
            state.get("schema") != STATE_SCHEMA
            or state.get("contractSha256") != contract_sha
        ):
            raise PreflightError(
                "preflight code, bank, graph or plan changed; existing state is not reusable"
            )
    else:
        state = {
            "schema": STATE_SCHEMA,
            "contractSha256": contract_sha,
            "preflightContractSha256": preflight_contract_sha,
            "implementationSourceSha256": source_hash,
            "startedUnixMilliseconds": round(time.time() * 1_000),
            "effectSelection": {},
            "channelSnapshots": {},
        }
        _write_json(state_path, state)
    create_isolated_bank_copy, fully_muted_multi_instrument_guids, renderer_type, _loops = _load_audio_lab(audio_lab_root)
    renderer = renderer_type(assetto_root)
    transient = output_root / ".transient"
    transient.mkdir(exist_ok=True)
    all_effect_tasks: list[dict[str, Any]] = []
    all_snapshot_tasks: list[tuple[Mapping[str, Any], Mapping[str, Any]]] = []
    families_by_id = {str(item["familyId"]): item for item in families}
    for family in families:
        all_effect_tasks.extend(_effect_source_tasks(family))
        all_snapshot_tasks.extend((family, item) for item in _channel_snapshot_tasks(family))
    all_effect_tasks = [
        item
        for item in all_effect_tasks
        if int(str(item["taskSpecSha256"])[:16], 16) % partition_count
        == partition_index
    ]
    if partition_index:
        all_snapshot_tasks = []
    for ordinal, task in enumerate(all_effect_tasks, start=1):
        key = str(task["taskSpecSha256"])
        existing = state["effectSelection"].get(key)
        if isinstance(existing, Mapping) and existing.get("taskSpecSha256") == key and (
            existing.get("pass") is True or not retry_failed
        ):
            continue
        family = families_by_id[str(task["familyId"])]
        started = time.monotonic()
        try:
            result = _select_and_isolate(
                renderer=renderer,
                family=family,
                task=task,
                create_isolated_bank_copy=create_isolated_bank_copy,
                fully_muted_multi_instrument_guids=fully_muted_multi_instrument_guids,
                temporary_root=transient,
            )
        except BaseException as exc:  # Continue to enumerate every blocker.
            result = _record_error(exc)
        result["elapsedMilliseconds"] = round((time.monotonic() - started) * 1_000)
        state["effectSelection"][key] = {
            **task,
            **result,
            "ordinal": ordinal,
            "taskSpecSha256": key,
        }
        _write_json(state_path, state)
    for ordinal, (family, task) in enumerate(all_snapshot_tasks, start=1):
        identity = {
            "familyId": family["familyId"],
            "planSha256": family["planSha256"],
            "bankSha256": family["bankSha256"],
            "task": task,
            "durationFrames": DEFAULT_DURATION_FRAMES,
        }
        key = _canonical_sha256(identity)
        existing = state["channelSnapshots"].get(key)
        if isinstance(existing, Mapping) and existing.get("taskSpecSha256") == key and (
            existing.get("pass") is True or not retry_failed
        ):
            continue
        try:
            started = time.monotonic()
            result = _snapshot(
                renderer=renderer,
                family=family,
                task=task,
                temporary_root=transient,
            )
        except BaseException as exc:
            result = _record_error(exc)
        result["elapsedMilliseconds"] = round((time.monotonic() - started) * 1_000)
        state["channelSnapshots"][key] = {
            **identity,
            **result,
            "ordinal": ordinal,
            "taskSpecSha256": key,
        }
        _write_json(state_path, state)
    selection = list(state["effectSelection"].values())
    snapshots = list(state["channelSnapshots"].values())
    by_family: dict[str, dict[str, Any]] = {}
    for family in families:
        family_id = str(family["familyId"])
        selected = [item for item in selection if item.get("familyId") == family_id]
        family_snapshots = [item for item in snapshots if item.get("familyId") == family_id]
        by_family[family_id] = {
            "representativeCarId": family["carId"],
            "aliasCarIds": list(family["aliasCarIds"]),
            "aliasPlanSha256s": list(family["aliasPlanSha256s"]),
            "planSha256": family["planSha256"],
            "bankSha256": family["bankSha256"],
            "effectSourceTaskCount": len(selected),
            "effectSourcePassCount": sum(item.get("pass") is True for item in selected),
            "snapshotScenarioCount": len(family_snapshots),
            "snapshotPassCount": sum(item.get("pass") is True for item in family_snapshots),
            "maximumLogicalChannels": max((int(item.get("maximumLogicalChannels", 0)) for item in family_snapshots), default=0),
            "maximumRealChannels": max((int(item.get("maximumRealChannels", 0)) for item in family_snapshots), default=0),
            "selectionPass": bool(selected) and all(item.get("pass") is True for item in selected),
            "snapshotPass": (
                None
                if partition_index
                else bool(family_snapshots)
                and all(item.get("pass") is True for item in family_snapshots)
            ),
        }
    failures = [
        item for item in selection + snapshots if item.get("pass") is not True
    ]
    all_sources_pass = bool(selection) and all(item.get("pass") is True for item in selection)
    all_snapshots_pass = (
        True
        if partition_index
        else bool(snapshots) and all(item.get("pass") is True for item in snapshots)
    )
    elapsed_values = [
        int(item["elapsedMilliseconds"])
        for item in selection + snapshots
        if isinstance(item.get("elapsedMilliseconds"), int)
        and not isinstance(item.get("elapsedMilliseconds"), bool)
    ]
    report = {
        "schema": SCHEMA,
        "status": "PASS" if not failures and all_sources_pass and all_snapshots_pass else "BLOCKED",
        "contractSha256": contract_sha,
        "preflightContractSha256": preflight_contract_sha,
        "implementationSourceSha256": source_hash,
        "startedUnixMilliseconds": state["startedUnixMilliseconds"],
        "finishedUnixMilliseconds": round(time.time() * 1_000),
        "durationFrames": DEFAULT_DURATION_FRAMES,
        "maximumDeterministicTakes": DEFAULT_MAXIMUM_TAKES,
        "taskPartition": {
            "algorithm": "unsignedFirst16HexTaskSpecSha256ModuloPartitionCount-v1",
            "count": partition_count,
            "index": partition_index,
            "snapshotScenariosAssignedOnlyToPartitionIndex": 0,
        },
        "elapsedMilliseconds": {
            "total": sum(elapsed_values),
            "maximum": max(elapsed_values, default=0),
        },
        "retainedAtlasPcm": False,
        "sourceSelection": {
            "taskCount": len(selection),
            "passCount": sum(item.get("pass") is True for item in selection),
            "allPass": all_sources_pass,
            "tasks": selection,
        },
        "rawChannelSnapshots": {
            "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
            "scenarioCount": len(snapshots),
            "passCount": sum(item.get("pass") is True for item in snapshots),
            "allPass": all_snapshots_pass,
            "scenarios": snapshots,
        },
        "families": by_family,
        "failureCount": len(failures),
    }
    _write_json(output_root / "report.json", report)
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--atlas-root", type=Path, required=True)
    parser.add_argument("--staged-root", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--car-id")
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--partition-count", type=int, default=1)
    parser.add_argument("--partition-index", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        report = run(
            atlas_root=args.atlas_root,
            staged_root=args.staged_root,
            audio_lab_root=args.audio_lab_root,
            assetto_root=args.assetto_root,
            output_root=args.output_root,
            car_id=args.car_id,
            retry_failed=args.retry_failed,
            partition_count=args.partition_count,
            partition_index=args.partition_index,
        )
    except (PreflightError, OSError, ValueError) as exc:
        print(f"preflight failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"status": report["status"], "failureCount": report["failureCount"]}, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
