#!/usr/bin/env python3
"""Produce release causal observations from an original-bank Audio Lab trace.

The resource verifier deliberately has no FMOD probing code.  This producer is
the other half of that boundary: it exports the exhaustive Android host paths,
asks the x86_64 Audio Lab process to execute those exact paths against the
original bank, binds every returned voice to one authored source occurrence,
and only then writes the v1 observation document consumed by
``causal_full_event_resource_proof.py``.

There is intentionally no fixture/synthetic mode in this CLI.  Unit tests may
inject an observer function into :func:`produce_causal_full_event_observations`,
but batch promotion always crosses the hashed native-trace subprocess boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Callable, Mapping, Sequence

try:
    from causal_full_event_resource_proof import (
        ASSETTO_LOGICAL_CHANNEL_CAP,
        ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        DSP_BUFFER_FRAMES,
        HOST_CONTROL_HZ,
        HOST_TICK_FRAMES,
        HOST_WITNESS_BINDINGS_SCHEMA,
        OBSERVATION_SCHEMA,
        PERSPECTIVES,
        PROGRAM_MODES,
        SAMPLE_RATE_HZ,
        _explore_group_phase_contexts,
        _parse_host_state_graph,
        _plan_contract,
        _source_binding_oracle_registry,
        canonical_json_bytes,
        effect_node_key,
        explore_host_control_reachability,
        prove_causal_full_event_resources,
    )
    from export_full_event_session_state_graph import (
        produce_full_event_session_state_graph,
    )
except ImportError:
    from tools.profile_generation.causal_full_event_resource_proof import (
        ASSETTO_LOGICAL_CHANNEL_CAP,
        ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        DSP_BUFFER_FRAMES,
        HOST_CONTROL_HZ,
        HOST_TICK_FRAMES,
        HOST_WITNESS_BINDINGS_SCHEMA,
        OBSERVATION_SCHEMA,
        PERSPECTIVES,
        PROGRAM_MODES,
        SAMPLE_RATE_HZ,
        _explore_group_phase_contexts,
        _parse_host_state_graph,
        _plan_contract,
        _source_binding_oracle_registry,
        canonical_json_bytes,
        effect_node_key,
        explore_host_control_reachability,
        prove_causal_full_event_resources,
    )
    from tools.profile_generation.export_full_event_session_state_graph import (
        produce_full_event_session_state_graph,
    )


CAPTURE_REQUEST_SCHEMA = "byd-original-bank-causal-capture-request-v1"
NATIVE_TRACE_SCHEMA = "byd-original-bank-causal-session-trace-v1"
PRODUCER_SCHEMA = "byd-causal-observation-producer-v1"
CAMERA_STOP_MODE = "FMOD_STUDIO_STOP_ALLOWFADEOUT"
NATIVE_TOOL_RELATIVE_PATH = Path("tools") / "capture_causal_full_event_session.py"


class CausalObservationProducerError(ValueError):
    """Native evidence is absent, ambiguous, stale, or not release-grade."""


NativeObserver = Callable[[Mapping[str, Any]], Mapping[str, Any]]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _self_hashed(value: Mapping[str, Any], field: str) -> dict[str, Any]:
    result = dict(value)
    if field in result:
        raise CausalObservationProducerError(f"{field} already exists")
    result[field] = hashlib.sha256(canonical_json_bytes(result)).hexdigest()

    return result


def _validate_self_hash(value: Mapping[str, Any], field: str, label: str) -> None:
    digest = value.get(field)
    if not isinstance(digest, str) or len(digest) != 64:
        raise CausalObservationProducerError(f"{label} has no {field}")
    body = dict(value)
    body.pop(field, None)
    if hashlib.sha256(canonical_json_bytes(body)).hexdigest() != digest:
        raise CausalObservationProducerError(f"{label} {field} differs")


def _scenario_id(perspective: str, mode: str, suffix: str) -> str:
    return f"{perspective}-{mode}-{suffix}"


def _engine_node_key(
    perspective: str,
    event_path: str,
    parameters: Mapping[str, Any],
) -> str:
    identity = {
        "perspective": perspective,
        "eventPath": event_path,
        "parameters": dict(parameters),
    }

    return "engine-node:" + hashlib.sha256(
        canonical_json_bytes(identity)
    ).hexdigest()


def _mode_engine_parameters(
    raw_parameters: Mapping[str, Any],
    mode: str,
) -> dict[str, float]:
    parameters: dict[str, float] = {}
    for name, value in raw_parameters.items():
        if (
            not isinstance(name, str)
            or not name
            or isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(float(value))
        ):
            raise CausalObservationProducerError(
                "engine atlas node parameters are invalid"
            )
        parameters[name] = float(value)
    if "throttle" in parameters and mode in {"LOAD", "COAST"}:
        parameters["throttle"] = 1.0 if mode == "LOAD" else 0.0

    return parameters


def _engine_source_probe_spec(
    plan: Mapping[str, Any],
    perspective: str,
    mode: str,
) -> dict[str, Any] | None:
    engine = plan["perspectives"][perspective]
    event_path = str(engine["eventPath"])
    raw_nodes = engine.get("nodes")
    if not isinstance(raw_nodes, list) or not raw_nodes:
        return None
    unique_parameters: dict[bytes, dict[str, float]] = {}
    for node in raw_nodes:
        if not isinstance(node, Mapping) or not isinstance(
            node.get("parameters"), Mapping
        ):
            raise CausalObservationProducerError(
                f"{perspective} engine atlas node has no exact parameters"
            )
        parameters = _mode_engine_parameters(node["parameters"], mode)
        unique_parameters.setdefault(canonical_json_bytes(parameters), parameters)
    ordered_parameters = [
        unique_parameters[key]
        for key in sorted(unique_parameters)
    ]
    if not ordered_parameters:
        return None
    # Hold every original-bank engine node for two DSP blocks.  This is an
    # auxiliary source-identity sweep, not an Android host-control trajectory;
    # keeping it on exact DSP boundaries avoids collapsing 200 Hz host ticks
    # while still observing the same prepared EventInstance at a settled node.
    host_path: list[dict[str, Any]] = []
    probe_nodes: list[dict[str, Any]] = []
    for index, parameters in enumerate(ordered_parameters):
        start_host_frame = index * DSP_BUFFER_FRAMES * 2
        node_key = _engine_node_key(perspective, event_path, parameters)
        probe_nodes.append(
            {
                "nodeKey": node_key,
                "hostFrame": start_host_frame,
                "parameters": parameters,
            }
        )
        host_path.append(
            {
                "hostFrame": start_host_frame,
                "selectedPerspective": perspective,
                "programMode": mode,
                "hostValues": {},
                "engineParameters": parameters,
                "engineNodeKey": node_key,
                "emissions": [],
            }
        )

    return {
        "id": _scenario_id(
            perspective,
            mode,
            "engine-source-audibility-sweep",
        ),
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "source-audibility-probe",
        "initialHostPhaseFrames": 0,
        "hostPath": host_path,
        "engineProbeNodes": probe_nodes,
        "finiteTriggerRecords": [],
        "requiredTailDrain": True,
    }


def _camera_engine_parameters(
    plan: Mapping[str, Any],
    perspective: str,
    mode: str,
) -> dict[str, float]:
    engine = plan["perspectives"][perspective]
    raw_nodes = engine.get("nodes")
    if isinstance(raw_nodes, list) and raw_nodes:
        bindings = engine.get("hostParameterBindings")
        rpm_parameter = next(
            (
                str(binding["parameter"])
                for binding in bindings
                if isinstance(binding, Mapping)
                and binding.get("source") == "EngineSimulation.rpm"
                and isinstance(binding.get("parameter"), str)
            ),
            "rpms",
        ) if isinstance(bindings, list) else "rpms"
        candidates = [
            node
            for node in raw_nodes
            if isinstance(node, Mapping)
            and isinstance(node.get("parameters"), Mapping)
            and isinstance(node["parameters"].get(rpm_parameter), (int, float))
            and not isinstance(node["parameters"].get(rpm_parameter), bool)
        ]
        if candidates:
            rpm_values = sorted(
                {
                    float(node["parameters"][rpm_parameter])
                    for node in candidates
                    if math.isfinite(float(node["parameters"][rpm_parameter]))
                }
            )
            positive_values = [value for value in rpm_values if value > 0.0]
            target_rpm = (
                positive_values[len(positive_values) // 2]
                if positive_values
                else rpm_values[len(rpm_values) // 2]
            )
            selected = min(
                candidates,
                key=lambda node: abs(
                    float(node["parameters"][rpm_parameter]) - target_rpm
                ),
            )

            return _mode_engine_parameters(selected["parameters"], mode)

    # Legacy test plans do not carry the adaptive engine grid.  These are
    # direct authored FMOD parameter names, never source-identity evidence.
    return {
        "rpms": 2_500.0,
        "throttle": {"LOAD": 1.0, "COAST": 0.0, "BOTH": 0.5}[mode],
    }


def _build_witness_bindings(
    plan: Mapping[str, Any],
    state_graph: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind only the empirically required peak/re-entry scenario ids.

    Whether a re-entry witness is mandatory depends on the explored physical
    overlap.  Computing it from the same state machine avoids the circular and
    unsafe convention of putting a re-entry id on every finite group.
    """

    contract = _plan_contract(plan)
    machines, _session = _parse_host_state_graph(contract, state_graph)
    bindings: list[dict[str, Any]] = []
    for perspective in PERSPECTIVES:
        for mode in PROGRAM_MODES:
            for group_key, group in sorted(contract.groups.items()):
                if not group.maximum_capture_frames:
                    continue
                maximum = 0
                for phase in range(DSP_BUFFER_FRAMES):
                    explored = _explore_group_phase_contexts(
                        machines[group_key],
                        group_id=group_key,
                        initial_phase_frames=phase,
                        maximum_capture_frames_by_activation_perspective=(
                            group.maximum_capture_frames
                        ),
                    )[(perspective, mode)]
                    maximum = max(maximum, int(explored["maximumLiveRings"]))
                witness_id = _scenario_id(
                    perspective,
                    mode,
                    f"{group_key}-host-control-peak",
                )
                bindings.append(
                    {
                        "perspective": perspective,
                        "programMode": mode,
                        "eventPath": group.event_path,
                        "groupId": group.group_id,
                        "peakWitnessScenarioIds": [witness_id] if maximum else [],
                        "minimumReentryWitnessScenarioIds": (
                            [witness_id] if maximum > 1 else []
                        ),
                    }
                )

    return {
        "schema": HOST_WITNESS_BINDINGS_SCHEMA,
        "atlasFamilyId": plan["id"],
        "planSha256": plan["planSha256"],
        "bindings": bindings,
    }


def _group_scenario_spec(entry: Mapping[str, Any]) -> dict[str, Any]:
    perspective = str(entry["perspective"])
    mode = str(entry["programMode"])
    group_key = str(entry["groupKey"])

    return {
        "id": _scenario_id(
            perspective,
            mode,
            f"{group_key}-host-control-peak",
        ),
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "host-control-peak",
        "initialHostPhaseFrames": entry["peakWitnessInitialHostPhaseFrames"],
        "hostPath": entry["peakWitnessHostPath"],
        "finiteTriggerRecords": [
            {**record, "groupKey": group_key}
            for record in entry["peakWitnessTriggerHostRecords"]
        ],
        "requiredTailDrain": True,
    }


def _session_scenario_spec(entry: Mapping[str, Any]) -> dict[str, Any]:
    perspective = str(entry["perspective"])
    mode = str(entry["programMode"])

    return {
        "id": _scenario_id(perspective, mode, "session-host-control-peak"),
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "host-control-peak",
        "initialHostPhaseFrames": entry[
            "ringPoolPeakWitnessInitialHostPhaseFrames"
        ],
        "hostPath": entry["ringPoolPeakHostPath"],
        "finiteTriggerRecords": entry["ringPoolPeakTriggerHostRecords"],
        "requiredTailDrain": True,
    }


def _camera_scenario_spec(
    plan: Mapping[str, Any],
    perspective: str,
    mode: str,
) -> dict[str, Any]:
    opposite = "exterior" if perspective == "cabin" else "cabin"
    engine_parameters = {
        activation_perspective: _camera_engine_parameters(
            plan,
            activation_perspective,
            mode,
        )
        for activation_perspective in PERSPECTIVES
    }

    return {
        "id": _scenario_id(perspective, mode, "camera-switch-tail"),
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "camera-switch-tail",
        "initialHostPhaseFrames": 0,
        "hostPath": [
            {
                "hostFrame": 0,
                "selectedPerspective": opposite,
                "programMode": mode,
                "hostValues": {},
                "engineParametersByActivationPerspective": engine_parameters,
                "emissions": [],
            },
            {
                "hostFrame": HOST_TICK_FRAMES,
                "selectedPerspective": perspective,
                "programMode": mode,
                "hostValues": {},
                "engineParametersByActivationPerspective": engine_parameters,
                "emissions": [],
            },
        ],
        "finiteTriggerRecords": [],
        "cameraSwitch": {
            "fromPerspective": opposite,
            "toPerspective": perspective,
            "hostFrame": HOST_TICK_FRAMES,
            "oldEngineStopMode": CAMERA_STOP_MODE,
            "newEngineAction": "rewindTimelineToZeroThenStartSamePreparedInstance",
            "persistentEffectAction": "retainSameOriginalBankEventInstances",
        },
        "requiredTailDrain": True,
    }


def _exact_source_catalog(plan: Mapping[str, Any]) -> dict[str, Any]:
    engines: dict[str, Any] = {}
    for perspective in PERSPECTIVES:
        event = plan["perspectives"][perspective]
        engines[perspective] = {
            "eventPath": event["eventPath"],
            "hostParameterBindings": event.get("hostParameterBindings", []),
            "axes": event.get("axes"),
            "nodes": [
                {
                    "nodeKey": _engine_node_key(
                        perspective,
                        event["eventPath"],
                        node["parameters"],
                    ),
                    "parameters": node["parameters"],
                }
                for node in event.get("nodes", [])
            ],
            "sources": [
                {
                    "sourceGuid": item["sourceGuid"],
                    "diagnosticName": item.get(
                        "diagnosticNameNotUsedForClassification",
                        item.get("diagnosticName"),
                    ),
                }
                for item in event["requiredSourceCoverage"]
            ],
        }
    effects: list[dict[str, Any]] = []
    for event in plan["effects"]:
        variants = event["runtimeLifecycleParameterVariantContract"]["variants"]
        by_binding = {
            item["authoredBindingKey"]: item
            for item in variants
        }
        for binding_key, variant in sorted(by_binding.items()):
            nodes = [
                node
                for node in event["nodes"]
                if node["requiredAuthoredBindingKey"] == binding_key
            ]
            effects.append(
                {
                    "eventPath": event["eventPath"],
                    "authoredBindingKey": binding_key,
                    "sourceGuid": variant["sourceGuid"],
                    "diagnosticName": variant.get("diagnosticName"),
                    "lifetime": variant["lifetime"],
                    "eventInstanceOwnership": variant["runtimeMapping"][
                        "eventInstanceOwnership"
                    ],
                    "schedulingGroup": variant.get(
                        "schedulingGroup",
                        variant["runtimeMapping"].get("schedulingGroup"),
                    ),
                    "nodes": [
                        {
                            "nodeKey": effect_node_key(
                                event["eventPath"],
                                node["requiredSourceGuid"],
                                node["requiredAuthoredBindingKey"],
                                node["parameters"],
                            ),
                            "parameters": node["parameters"],
                            "durationFrames": node["durationFrames"],
                        }
                        for node in nodes
                    ],
                }
            )

    return {"engines": engines, "effects": effects}


def build_capture_request(
    plan: Mapping[str, Any],
    *,
    state_graph: Mapping[str, Any],
    witness_bindings: Mapping[str, Any],
    reachability: Mapping[str, Any],
    bank_path: Path,
    graph_path: Path,
    realization_path: Path,
) -> dict[str, Any]:
    scenario_specs = [
        _group_scenario_spec(entry)
        for entry in reachability["groups"]
    ]
    scenario_specs.extend(
        _session_scenario_spec(entry)
        for entry in reachability["sessionContexts"]
    )
    scenario_specs.extend(
        _camera_scenario_spec(plan, perspective, mode)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
    )
    scenario_specs.extend(
        scenario
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
        for scenario in [
            _engine_source_probe_spec(plan, perspective, mode)
        ]
        if scenario is not None
    )
    scenario_specs = [
        _self_hashed(item, "scenarioRequestSha256")
        for item in scenario_specs
    ]
    ids = [item["id"] for item in scenario_specs]
    if len(ids) != len(set(ids)):
        raise CausalObservationProducerError("capture scenario ids repeat")
    request = {
        "schema": CAPTURE_REQUEST_SCHEMA,
        "atlasFamilyId": plan["id"],
        "planSha256": plan["planSha256"],
        "bankPath": str(bank_path),
        "bankSha256": _sha256(bank_path),
        "graphPath": str(graph_path),
        "graphSha256": _sha256(graph_path),
        "realizationPath": str(realization_path),
        "realizationSha256": _sha256(realization_path),
        "runtimeBoundary": {
            "sampleRateHz": SAMPLE_RATE_HZ,
            "dspBufferFrames": DSP_BUFFER_FRAMES,
            "hostControlHz": HOST_CONTROL_HZ,
            "hostTickFrames": HOST_TICK_FRAMES,
            "assettoStudioLogicalChannelCap": ASSETTO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        },
        "sourceIdentityPolicy": {
            "diagnosticNameMaySelectIdentity": False,
            "ambiguousRuntimeSourceIdentity": "failClosed",
            "requiredIdentity": "eventPath+sourceGuid+authoredBindingKey",
        },
        "eventInstancePolicy": {
            "originalBankOnly": True,
            "cameraOldEngineStopMode": CAMERA_STOP_MODE,
            "persistentEffectsSurviveCameraAndProgramModeChanges": True,
            "freshFiniteInstanceSubstitution": "forbidden",
        },
        "virtualVoicePolicy": {
            "schema": "byd-fmod-virtual-voice-inaudibility-v1",
            "audibilityEpsilon": 1.0e-7,
            "authoredRouteGainEpsilon": 1.0e-7,
            "virtualBindingMustBeRealAudibleElsewhereOrCertifiedSilent": True,
        },
        "atlasPlan": plan,
        "hostControlStateGraph": state_graph,
        "hostControlWitnessBindings": witness_bindings,
        "hostControlReachability": reachability,
        "exactSourceCatalog": _exact_source_catalog(plan),
        "scenarios": scenario_specs,
    }

    return _self_hashed(request, "requestSha256")


def _validate_native_trace(
    trace: Mapping[str, Any],
    request: Mapping[str, Any],
) -> list[Mapping[str, Any]]:
    if trace.get("schema") != NATIVE_TRACE_SCHEMA:
        raise CausalObservationProducerError("native causal trace schema differs")
    _validate_self_hash(trace, "traceSha256", "native causal trace")
    if (
        trace.get("status") != "PASS"
        or trace.get("requestSha256") != request["requestSha256"]
        or trace.get("atlasFamilyId") != request["atlasFamilyId"]
        or trace.get("planSha256") != request["planSha256"]
        or trace.get("bankSha256") != request["bankSha256"]
        or trace.get("bankPath") != request["bankPath"]
        or trace.get("graphSha256") != request["graphSha256"]
        or trace.get("realizationSha256") != request["realizationSha256"]
        or trace.get("sourceBankKind") != "originalAssettoCarBank"
        or trace.get("evidenceKind") != "nativeOriginalBankFmodNrtSession"
        or trace.get("syntheticEvidence") is not False
    ):
        raise CausalObservationProducerError(
            "native causal trace is not a PASS for the exact original bank request"
        )
    runtime = trace.get("nativeRuntime")
    if not isinstance(runtime, Mapping):
        raise CausalObservationProducerError("native causal trace has no runtime identity")
    required_runtime = {
        "architecture": "x86_64",
        "outputMode": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
        "sampleRateHz": SAMPLE_RATE_HZ,
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "studioLogicalChannelCap": ASSETTO_LOGICAL_CHANNEL_CAP,
        "softwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    }
    for key, expected in required_runtime.items():
        if runtime.get(key) != expected:
            raise CausalObservationProducerError(
                f"native causal runtime {key} differs"
            )
    for key in ("coreLibrarySha256", "studioLibrarySha256"):
        value = runtime.get(key)
        if (
            not isinstance(value, str)
            or len(value) != 64
            or any(character not in "0123456789abcdef" for character in value)
        ):
            raise CausalObservationProducerError(
                f"native causal runtime {key} is absent"
            )
    try:
        source_binding_oracles = _source_binding_oracle_registry(
            trace.get("sourceBindingOraclesBySha256")
        )
    except ValueError as exc:
        raise CausalObservationProducerError(str(exc)) from exc
    raw_scenarios = trace.get("scenarios")
    if not isinstance(raw_scenarios, list) or not raw_scenarios:
        raise CausalObservationProducerError("native causal trace has no scenarios")
    by_id: dict[str, Mapping[str, Any]] = {}
    for item in raw_scenarios:
        if not isinstance(item, Mapping) or not isinstance(item.get("id"), str):
            raise CausalObservationProducerError("native causal scenario is invalid")
        if item["id"] in by_id:
            raise CausalObservationProducerError("native causal scenario id repeats")
        by_id[item["id"]] = item
    expected = {str(item["id"]): item for item in request["scenarios"]}
    if set(by_id) != set(expected):
        raise CausalObservationProducerError(
            "native causal scenario coverage differs from the exact request"
        )
    used_source_binding_oracles: set[str] = set()
    for scenario_id, spec in expected.items():
        observed = by_id[scenario_id]
        if any(
            observed.get(key) != spec[key]
            for key in (
                "id",
                "perspective",
                "programMode",
                "trajectoryKind",
                "initialHostPhaseFrames",
                "scenarioRequestSha256",
            )
        ):
            raise CausalObservationProducerError(
                f"native scenario {scenario_id} identity differs"
            )
        provenance = observed.get("originalBankEvidence")
        if (
            not isinstance(provenance, Mapping)
            or provenance.get("bankPath") != request["bankPath"]
            or provenance.get("bankSha256") != request["bankSha256"]
            or provenance.get("graphSha256") != request["graphSha256"]
            or provenance.get("realizationSha256")
            != request["realizationSha256"]
            or provenance.get("eventInstances") != "originalBankEventDescriptions"
            or provenance.get("channelEnumeration")
            != "FMOD_System_GetChannelsPlaying+perChannelVirtualState"
            or provenance.get("sourceBinding")
            != "callbackSoundIdentity+eventInstanceChannelGroup+sourceSoloAuthoredBindingOracle"
            or provenance.get("diagnosticNamesUsedForClassification") is not False
            or provenance.get("appliedHostPathSha256")
            != hashlib.sha256(
                canonical_json_bytes(spec["hostPath"])
            ).hexdigest()
            or provenance.get("appliedFiniteTriggerRecordsSha256")
            != hashlib.sha256(
                canonical_json_bytes(spec["finiteTriggerRecords"])
            ).hexdigest()
        ):
            raise CausalObservationProducerError(
                f"native scenario {scenario_id} lacks exact original-bank provenance"
            )
        snapshots = observed.get("snapshots")
        if not isinstance(snapshots, list) or not snapshots:
            raise CausalObservationProducerError(
                f"native scenario {scenario_id} has no snapshots"
            )
        for snapshot in snapshots:
            if not isinstance(snapshot, Mapping):
                raise CausalObservationProducerError(
                    f"native scenario {scenario_id} snapshot is invalid"
                )
            voices = snapshot.get("voices")
            if not isinstance(voices, list):
                raise CausalObservationProducerError(
                    f"native scenario {scenario_id} has no voice enumeration"
                )
            for voice in voices:
                if not isinstance(voice, Mapping):
                    raise CausalObservationProducerError(
                        f"native scenario {scenario_id} voice is invalid"
                    )
                oracle_sha = voice.get("sourceBindingOracleSha256")
                if (
                    not isinstance(oracle_sha, str)
                    or oracle_sha not in source_binding_oracles
                ):
                    raise CausalObservationProducerError(
                        f"native scenario {scenario_id} voice source-binding oracle is absent"
                    )
                used_source_binding_oracles.add(oracle_sha)
        if spec["trajectoryKind"] == "camera-switch-tail":
            camera = observed.get("cameraSwitchEvidence")
            switch_host_frame = int(spec["cameraSwitch"]["hostFrame"])
            switch_dsp_frame = (
                (switch_host_frame + DSP_BUFFER_FRAMES - 1)
                // DSP_BUFFER_FRAMES
                * DSP_BUFFER_FRAMES
            )
            old_perspective = spec["cameraSwitch"]["fromPerspective"]
            new_perspective = spec["cameraSwitch"]["toPerspective"]

            def active_engine_perspectives(snapshot: Mapping[str, Any]) -> set[str]:
                voices = snapshot.get("voices")
                if not isinstance(voices, list):
                    return set()

                return {
                    str(voice.get("activationPerspective"))
                    for voice in voices
                    if isinstance(voice, Mapping)
                    and voice.get("kind") == "engineContinuous"
                }

            frames_with_old_tail: list[int] = []
            overlap_frames: list[int] = []
            first_drain_frame: int | None = None
            for snapshot in snapshots:
                if not isinstance(snapshot, Mapping):
                    continue
                frame = snapshot.get("afterDspBlockStartFrame")
                if not isinstance(frame, int) or frame < switch_dsp_frame:
                    continue
                active = active_engine_perspectives(snapshot)
                if old_perspective in active:
                    frames_with_old_tail.append(frame)
                    if new_perspective in active:
                        overlap_frames.append(frame)
                elif frames_with_old_tail and first_drain_frame is None:
                    first_drain_frame = frame
            old_ordinal = camera.get("oldEngineEventOrdinal") if isinstance(camera, Mapping) else None
            new_ordinal = camera.get("newEngineEventOrdinal") if isinstance(camera, Mapping) else None
            old_instance_token = (
                camera.get("oldEngineEventInstanceToken")
                if isinstance(camera, Mapping)
                else None
            )
            new_instance_token = (
                camera.get("newEngineEventInstanceToken")
                if isinstance(camera, Mapping)
                else None
            )
            if (
                not isinstance(camera, Mapping)
                or camera.get("oldEngineStopMode") != CAMERA_STOP_MODE
                or camera.get("oldEngineStopHostFrame") != switch_host_frame
                or camera.get("oldEngineStopDspFrame") != switch_dsp_frame
                or camera.get("oldEngineTailObservedAfterStop") is not True
                or camera.get("oldEngineTailDspFrames") != frames_with_old_tail
                or camera.get("newAndOldEngineOverlapObserved") is not True
                or camera.get("newAndOldEngineOverlapDspFrames")
                != overlap_frames
                or camera.get("oldEngineTailDrainedNaturally") is not True
                or camera.get("oldEngineTailDrainDspFrame") != first_drain_frame
                or camera.get("persistentEffectInstanceTokensStable") is not True
                or isinstance(old_ordinal, bool)
                or not isinstance(old_ordinal, int)
                or old_ordinal < 0
                or isinstance(new_ordinal, bool)
                or not isinstance(new_ordinal, int)
                or new_ordinal < 0
                or new_ordinal == old_ordinal
                or isinstance(old_instance_token, bool)
                or not isinstance(old_instance_token, int)
                or old_instance_token <= 0
                or isinstance(new_instance_token, bool)
                or not isinstance(new_instance_token, int)
                or new_instance_token <= 0
                or new_instance_token == old_instance_token
                or camera.get("switchStartedEventOrdinals") != [new_ordinal]
                or camera.get("switchStoppedEventOrdinals") != [old_ordinal]
                or camera.get("oldEngineStopAppliedToExactInstance") is not True
                or camera.get("newEngineStartAppliedToExactInstance") is not True
                or not frames_with_old_tail
                or not overlap_frames
                or first_drain_frame is None
            ):
                raise CausalObservationProducerError(
                    f"native camera scenario {scenario_id} lacks original-bank fade-tail evidence"
                )

    if used_source_binding_oracles != set(source_binding_oracles):
        raise CausalObservationProducerError(
            "native causal trace contains unused or missing source-binding oracles"
        )

    return [by_id[str(spec["id"])] for spec in request["scenarios"]]


def produce_causal_full_event_observations(
    plan: Mapping[str, Any],
    *,
    bank_path: Path,
    graph_path: Path,
    realization_path: Path,
    implementation_source_root: Path,
    native_observer: NativeObserver,
) -> dict[str, Any]:
    bank_path = bank_path.resolve(strict=True)
    graph_path = graph_path.resolve(strict=True)
    realization_path = realization_path.resolve(strict=True)
    if plan.get("bankSha256") != _sha256(bank_path):
        raise CausalObservationProducerError("plan and original bank SHA-256 differ")
    state_graph = produce_full_event_session_state_graph(
        plan,
        implementation_source_root=implementation_source_root,
    )
    witness_bindings = _build_witness_bindings(plan, state_graph)
    reachability = explore_host_control_reachability(
        plan,
        state_graph,
        witness_bindings,
        implementation_source_root=implementation_source_root,
    )
    request = build_capture_request(
        plan,
        state_graph=state_graph,
        witness_bindings=witness_bindings,
        reachability=reachability,
        bank_path=bank_path,
        graph_path=graph_path,
        realization_path=realization_path,
    )
    trace = native_observer(request)
    scenarios = _validate_native_trace(trace, request)
    observations = {
        "schema": OBSERVATION_SCHEMA,
        "atlasFamilyId": plan["id"],
        "planSha256": plan["planSha256"],
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "hostControlHz": HOST_CONTROL_HZ,
        "hostTickFrames": HOST_TICK_FRAMES,
        "assettoStudioLogicalChannelCap": ASSETTO_LOGICAL_CHANNEL_CAP,
        "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        "producer": {
            "schema": PRODUCER_SCHEMA,
            "atlasFamilyId": plan["id"],
            "planSha256": plan["planSha256"],
            "captureRequestSha256": request["requestSha256"],
            "nativeTraceSha256": trace["traceSha256"],
            "bankSha256": request["bankSha256"],
            "graphSha256": request["graphSha256"],
            "realizationSha256": request["realizationSha256"],
            "syntheticEvidenceAccepted": False,
        },
        "hostControlStateGraph": state_graph,
        "hostControlWitnessBindings": witness_bindings,
        "hostControlReachability": reachability,
        "sourceBindingOraclesBySha256": trace[
            "sourceBindingOraclesBySha256"
        ],
        "scenarios": [
            {
                key: value
                for key, value in scenario.items()
                if key not in {"originalBankEvidence", "cameraSwitchEvidence"}
            }
            for scenario in scenarios
        ],
    }
    # This call is intentionally redundant with the later batch proof.  It
    # keeps a malformed native trace from ever being materialized under the
    # release observation filename.
    prove_causal_full_event_resources(
        plan,
        observations,
        required_trajectory_kinds=["host-control-peak", "camera-switch-tail"],
        implementation_source_root=implementation_source_root,
    )

    return observations


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(canonical_json_bytes(value) + b"\n")
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _subprocess_observer(
    *,
    audio_lab_root: Path,
    fmod_api_root: Path,
    working_directory: Path,
) -> NativeObserver:
    native_tool = audio_lab_root / NATIVE_TOOL_RELATIVE_PATH
    if not native_tool.is_file():
        raise CausalObservationProducerError(
            f"Audio Lab causal native tool is absent: {native_tool}"
        )

    def observe(request: Mapping[str, Any]) -> Mapping[str, Any]:
        request_path = working_directory / "causal-capture-request.json"
        trace_path = working_directory / "causal-native-trace.json"
        _write_json_atomic(request_path, request)
        trace_path.unlink(missing_ok=True)
        environment = dict(os.environ)
        environment["FMOD_API_ROOT"] = str(fmod_api_root)
        completed = subprocess.run(
            [
                "arch",
                "-x86_64",
                "/usr/bin/python3",
                str(native_tool),
                "--request",
                str(request_path),
                "--output",
                str(trace_path),
            ],
            cwd=audio_lab_root,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        if completed.returncode or not trace_path.is_file():
            detail = (completed.stderr or completed.stdout).strip()
            raise CausalObservationProducerError(
                "Audio Lab causal native capture failed"
                + (f": {detail[-4000:]}" if detail else " without diagnostics")
            )
        try:
            result = json.loads(trace_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise CausalObservationProducerError(
                "Audio Lab causal native trace is unreadable"
            ) from exc
        if not isinstance(result, Mapping):
            raise CausalObservationProducerError(
                "Audio Lab causal native trace root is not an object"
            )

        return result

    return observe


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--realization", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--fmod-api-root", type=Path, required=True)
    parser.add_argument("--implementation-source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        if not isinstance(plan, Mapping):
            raise CausalObservationProducerError("atlas plan root is not an object")
        output = args.output.resolve()
        observer = _subprocess_observer(
            audio_lab_root=args.audio_lab_root.resolve(strict=True),
            fmod_api_root=args.fmod_api_root.resolve(strict=True),
            working_directory=output.parent,
        )
        observations = produce_causal_full_event_observations(
            plan,
            bank_path=args.bank,
            graph_path=args.graph,
            realization_path=args.realization,
            implementation_source_root=args.implementation_source_root.resolve(
                strict=True
            ),
            native_observer=observer,
        )
        _write_json_atomic(output, observations)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.error(str(exc))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
