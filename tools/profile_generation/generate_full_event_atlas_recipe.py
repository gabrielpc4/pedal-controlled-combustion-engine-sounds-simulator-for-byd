#!/usr/bin/env python3
"""Compile a DSP-safe full-event NRT atlas plan from a conservation recipe.

The source-isolated recipe remains build evidence only.  Runtime engine audio
comes from complete FMOD event renders so shared buses, nonlinear DSP, and
simultaneous tracks are never duplicated or flattened per source.  Initial
axes contain every authored RPM boundary/controller knot and the two throttle
endpoints.  Midpoint/authored-throttle oracle probes must refine the grid until
the configured PCM and spectral gates pass; an unvalidated plan is never
release eligible.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import sys
import tempfile
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import (  # noqa: E402
    ProfileRecipeError,
    canonical_json_bytes,
)


ATLAS_PLAN_SCHEMA = "byd-full-event-atlas-plan-v3"
ATLAS_RUNTIME_SCHEMA = "byd-full-event-atlas-runtime-v3"
ATLAS_REALIZATION_SCHEMA = "byd-full-event-atlas-realization-v3"
ATLAS_PACK_REPORT_SCHEMA = "byd-full-event-atlas-pack-v3"
CAPTURE_FRAMES = 96_000
WARMUP_FRAMES = 36_000
PCM_BYTES_PER_FRAME = 4
FINITE_ATTACK_CACHE_FRAMES = 4_096
FINITE_STREAMING_RING_BUFFER_FRAMES = 12_288
FINITE_RING_STEREO_FLOAT32_BYTES_PER_FRAME = 8
ATLAS_RECONSTRUCTION = "independentFmodMasterProgramsRootRpmBilinear-v1"
ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP = 2048
ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET = 256
ENGINE_MODE_PROGRAM_SCHEMA = "byd-full-event-engine-mode-programs-v1"
ENGINE_MODE_PROGRAMS = ("loadOnly", "coastOnly")
ENGINE_PROGRAM_CAPTURE_COUNT_PER_GRID_POINT = 1 + len(ENGINE_MODE_PROGRAMS)
FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA = "byd-full-event-fmod-channel-arbitration-oracle-v2"
FMOD_VOICE_BUDGET_INPUT_SCHEMA = "byd-full-event-fmod-voice-budget-input-v1"
FMOD_VOICE_BUDGET_REPORT_SCHEMA = "byd-fmod11011-software-voice-budget-oracle-v1"
FMOD_VOICE_BUDGET_REPORT_RELATIVE_PATH = (
    Path("build/new-car-audio/oracles/fmod11011-software256/report.json")
)
FMOD_VOICE_BUDGET_CASE_IDS = {
    "equal257",
    "incomingQuiet257",
    "continuousQuiet257",
    "continuousLowPriority257",
    "incomingHighPriority257",
    "incomingQuietHighPriority257",
    "continuousQuietHighPriority257",
    "twoEqualIncoming258",
}


def _finite(value: object, description: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ProfileRecipeError(f"{description} is not numeric") from exc
    if not math.isfinite(result):
        raise ProfileRecipeError(f"{description} is not finite")
    return result


def _observed_fmod_voice_budget_evidence() -> dict[str, Any]:
    """Bind the global 2048-logical/256-real experiment into every plan.

    The stress fixture proves a bounded FMOD policy only; it does not make an
    Android premix over budget reproducible.  A missing, malformed, or changed
    report therefore leaves the plan explicitly blocked rather than allowing a
    stale report to certify newly generated captures.
    """

    repository_root = SCRIPT_ROOT.parents[1]
    report_path = repository_root / FMOD_VOICE_BUDGET_REPORT_RELATIVE_PATH
    evidence: dict[str, Any] = {
        "schema": FMOD_VOICE_BUDGET_INPUT_SCHEMA,
        "reportPath": str(FMOD_VOICE_BUDGET_REPORT_RELATIVE_PATH),
        "reportSchema": FMOD_VOICE_BUDGET_REPORT_SCHEMA,
        "status": "BLOCKED_MISSING_OR_INVALID_FMOD_2048_256_REPORT",
        "releaseUse": "boundedGlobalPolicyOnly; everyPremixedFamilyPerspectiveStillRequiresRawSupportedScenarioAtOrBelow256OrSourceStems",
    }
    if not report_path.is_file():
        return evidence
    try:
        raw = report_path.read_bytes()
        report = json.loads(raw.decode("utf-8"))
        basis = report.get("basis") if isinstance(report, Mapping) else None
        cases = report.get("cases") if isinstance(report, Mapping) else None
        observed = (
            basis.get("observedRuntimeConfiguration")
            if isinstance(basis, Mapping)
            else None
        )
        case_ids = {
            str(case.get("id"))
            for case in cases
            if isinstance(case, Mapping) and isinstance(case.get("id"), str)
        } if isinstance(cases, list) else set()
        valid = (
            isinstance(report, Mapping)
            and report.get("schema") == FMOD_VOICE_BUDGET_REPORT_SCHEMA
            and report.get("result") == "PASS_WITH_BOUNDED_CLAIMS"
            and isinstance(basis, Mapping)
            and basis.get("studioInitializeMaxChannels")
            == ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP
            and basis.get("softwareChannelsRequested")
            == ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
            and isinstance(observed, Mapping)
            and observed.get("softwareChannels")
            == ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
            and observed.get("dspBufferFrames") == 256
            and case_ids == FMOD_VOICE_BUDGET_CASE_IDS
        )
    except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError):
        valid = False
        raw = b""
    if not valid:
        return evidence
    return {
        **evidence,
        "status": "PASS_WITH_BOUNDED_CLAIMS",
        "reportSha256": hashlib.sha256(raw).hexdigest(),
        "provenPolicy": {
            "at257RealBoundary": "newLogicalChannelVirtualNoLogicalSteal",
            "at258RealBoundary": "twoNewLogicalChannelsVirtual",
            "selectionOrderInFixture": "numericPriorityThenAudibilityThenCreationAge",
            "promotionAfterRealRelease": "convergesByThird256FrameDspBoundary",
        },
    }


def _axis(values: set[float]) -> list[float]:
    return sorted({round(_finite(value, "atlas axis value"), 8) for value in values})


def _node_asset(event_suffix: str, rpm: float, throttle: float) -> str:
    identity = canonical_json_bytes(
        {"event": event_suffix, "rpms": rpm, "throttle": throttle}
    )
    digest = hashlib.sha256(identity).hexdigest()[:16]
    return f"node_{event_suffix}_{digest}.wav"


def _engine_mode_program_asset(
    event_suffix: str,
    rpm: float,
    throttle: float,
    mode: str,
) -> str:
    if mode not in ENGINE_MODE_PROGRAMS:
        raise ProfileRecipeError(f"unsupported engine mode program {mode}")
    identity = canonical_json_bytes(
        {
            "event": event_suffix,
            "rpms": rpm,
            "throttle": throttle,
            "independentEngineProgramMode": mode,
        }
    )
    digest = hashlib.sha256(identity).hexdigest()[:16]
    return f"node_{event_suffix}_{mode}_{digest}.wav"


def current_axes(perspective: Mapping[str, Any]) -> Mapping[str, Sequence[float]]:
    """Return the required release axes."""

    value = perspective.get("axes")
    if not isinstance(value, Mapping):
        raise ProfileRecipeError("atlas perspective axes are absent")
    return value


def current_nodes(perspective: Mapping[str, Any]) -> Sequence[Mapping[str, Any]]:
    """Return the required release nodes."""

    value = perspective.get("nodes")
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        raise ProfileRecipeError("atlas perspective nodes are absent")
    return value


def atlas_plan_content_sha256(plan: Mapping[str, Any]) -> str:
    """Hash all audio-defining plan content without creating hash cycles.

    Release/oracle status and size estimates are deliberately excluded.  The
    digest changes whenever the bank, nodes, axes, capture contract, effects,
    or runtime reconstruction algorithm changes.
    """

    content = {
        "schema": plan.get("schema"),
        "id": plan.get("id"),
        "bankSha256": plan.get("bankSha256"),
        "sourceRecipeSha256": plan.get("sourceRecipeSha256"),
        "perspectives": plan.get("perspectives"),
        "effects": plan.get("effects"),
        "refinementGate": plan.get("refinementGate"),
        "reconstruction": plan.get("reconstruction"),
        "interpolationContract": plan.get("interpolationContract"),
        "hostMixContract": plan.get("hostMixContract"),
        "packedStorage": plan.get("packedStorage"),
    }
    return hashlib.sha256(canonical_json_bytes(content)).hexdigest()


def refresh_plan_sha256(plan: Mapping[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(dict(plan))
    result["planSha256"] = atlas_plan_content_sha256(result)
    return result


def _effect_node_asset(
    event_suffix: str,
    parameters: Mapping[str, Any],
    source_guid: str | None,
) -> str:
    digest = hashlib.sha256(
        canonical_json_bytes(
            {
                "event": event_suffix,
                "parameters": parameters,
                "requiredSourceGuid": source_guid,
            }
        )
    ).hexdigest()[:16]
    return f"effect_{event_suffix}_{digest}.wav"


def _engine_axes(program: Mapping[str, Any]) -> tuple[list[float], list[float]]:
    rpm_values: set[float] = set()
    authored_throttle_probes: set[float] = {0.0, 1.0}
    for layer in program.get("layers", []):
        rpm_values.update((float(layer["startRpm"]), float(layer["endRpm"])))
        placement_groups = layer.get("parameterPlacements", {})
        if isinstance(placement_groups, Mapping):
            for parameter, placements in placement_groups.items():
                if not isinstance(placements, Sequence) or isinstance(
                    placements, (str, bytes)
                ):
                    raise ProfileRecipeError("engine layer parameter placements are invalid")
                for placement in placements:
                    if not isinstance(placement, Mapping):
                        raise ProfileRecipeError("engine layer parameter placement is invalid")
                    if str(parameter).casefold() == "rpms":
                        rpm_values.update(
                            (
                                _finite(placement.get("start"), "engine RPM placement start"),
                                _finite(placement.get("end"), "engine RPM placement end"),
                            )
                        )
                    elif str(parameter).casefold() == "throttle":
                        authored_throttle_probes.update(
                            (
                                _finite(
                                    placement.get("start"),
                                    "engine throttle placement start",
                                ),
                                _finite(
                                    placement.get("end"),
                                    "engine throttle placement end",
                                ),
                            )
                        )
        projection = layer.get("projectionEvidence")
        if not isinstance(projection, dict):
            continue
        trigger_placements = projection.get("triggerPlacements", {})
        if isinstance(trigger_placements, dict):
            for parameter, placements in trigger_placements.items():
                for placement in placements:
                    if str(parameter).casefold() == "rpms":
                        rpm_values.update(
                            (float(placement["start"]), float(placement["end"]))
                        )
                    elif str(parameter).casefold() == "throttle":
                        authored_throttle_probes.update(
                            (float(placement["start"]), float(placement["end"]))
                        )
        for controller in projection.get("controllers", []):
            parameter = str(controller.get("parameter") or "").casefold()
            points = controller.get("points", [])
            if parameter == "rpms":
                rpm_values.update(float(point["x"]) for point in points)
            elif parameter == "throttle":
                authored_throttle_probes.update(float(point["x"]) for point in points)
    if len(rpm_values) < 2:
        raise ProfileRecipeError("engine atlas has fewer than two RPM anchors")
    rpm_axis = _axis(rpm_values)
    throttle_probe_axis = _axis(
        {min(1.0, max(0.0, value)) for value in authored_throttle_probes}
    )
    return rpm_axis, throttle_probe_axis


def _initial_engine_nodes(
    event_suffix: str, rpm_axis: Sequence[float]
) -> list[dict[str, Any]]:
    return [
        {
            "rpm": rpm,
            "throttle": throttle,
            "parameters": {"rpms": rpm, "throttle": throttle},
            # This is a loop-relative baseline, not a claimed static alignment
            # between different RPM captures.  The runtime performs a bounded
            # correlation search after varispeed when it prepares a zero-gain
            # neighbour for activation.
            "phaseOffsetFrames": 0.0,
            "temporaryAssetName": _node_asset(event_suffix, rpm, throttle),
            "modeProgramTemporaryAssetNames": {
                mode: _engine_mode_program_asset(event_suffix, rpm, throttle, mode)
                for mode in ENGINE_MODE_PROGRAMS
            },
        }
        for throttle in (0.0, 1.0)
        for rpm in rpm_axis
    ]


def _engine_mode_program_contract(
    program: Mapping[str, Any],
    event_bindings: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Define three independent master-output programs for one engine event.

    FMOD may merge role routes through compressors, filters, sends, panning, and
    other stateful DSP. Therefore its post-mix output is not generally additive.
    FULL, LOAD_ONLY, and COAST_ONLY are each rendered through a fresh complete
    event graph; Android selects one program directly and never reconstructs it
    from source stems.
    """

    layers = program.get("layers")
    if not isinstance(layers, Sequence) or isinstance(layers, (str, bytes)):
        raise ProfileRecipeError("engine program has no continuous layer partition")
    by_guid: dict[str, str] = {}
    role_evidence: list[dict[str, Any]] = []
    for layer in layers:
        if not isinstance(layer, Mapping):
            raise ProfileRecipeError("engine program contains a malformed layer")
        source_guid = str(layer.get("sourceGuid") or "")
        source_role = str(layer.get("role") or "")
        if not source_guid or source_guid in by_guid:
            raise ProfileRecipeError("engine program has a missing or duplicate source GUID")
        if source_role not in {"LOAD", "COAST", "IDLE", "TEXTURE", "LIMITER"}:
            raise ProfileRecipeError(
                f"engine source {source_guid} has unsupported program role {source_role}"
            )
        normalized_role = (
            "LOAD"
            if source_role in {"LOAD", "LIMITER"}
            else "COAST"
            if source_role == "COAST"
            else "UNAFFECTED"
        )
        by_guid[source_guid] = normalized_role
        role_evidence.append(
            {
                "sourceGuid": source_guid,
                "declaredRoleHint": source_role,
                "normalizedRoleHint": normalized_role,
                "authoritativeCaptureClassification": (
                    "sameLiveEventAuthoredRouteGainResponseAtThrottleZeroAndOne"
                ),
                "classificationUsesSampleName": False,
            }
        )

    event_sources = {str(binding.get("sourceGuid") or "") for binding in event_bindings}
    if "" in event_sources:
        raise ProfileRecipeError("engine event binding has no source GUID")
    finite_sources = {
        str(binding["sourceGuid"])
        for binding in event_bindings
        if (binding.get("runtimeMapping") or {}).get("kind") == "engineEventTransient"
    }
    continuous_sources = set(by_guid)
    if continuous_sources & finite_sources:
        raise ProfileRecipeError("engine event source is both continuous and finite")
    if continuous_sources | finite_sources != event_sources:
        missing = sorted(event_sources - continuous_sources - finite_sources)
        extra = sorted(continuous_sources | finite_sources - event_sources)
        raise ProfileRecipeError(
            f"engine program source partition differs from event bindings; missing={missing} extra={extra}"
        )

    sources_by_role = {
        role: sorted(source for source, source_role in by_guid.items() if source_role == role)
        for role in ("LOAD", "COAST", "UNAFFECTED")
    }
    if not continuous_sources:
        raise ProfileRecipeError("engine program has no continuous source partition")
    role_partition_certifications = {
        role: {
            "sourceCount": len(sources_by_role[role]),
            "isEmpty": not sources_by_role[role],
            "status": (
                "PASS_EXACT_GRAPH_EMPTY_ROLE_PARTITION"
                if not sources_by_role[role]
                else "PASS_EXACT_GRAPH_NONEMPTY_ROLE_PARTITION"
            ),
            "classificationBasis": (
                "exactContinuousSourceGuidPartitionFromAuthoredRouteGainResponse"
            ),
            "emptyCaptureDisposition": (
                "modeStillRetainsUNAFFECTEDButMustContainNoOppositeRoleSource"
                if not sources_by_role[role]
                else "notApplicableNonEmptyRole"
            ),
            "sourceReassignmentOrSyntheticFallback": "forbidden",
        }
        for role in ("LOAD", "COAST")
    }

    return {
        "schema": ENGINE_MODE_PROGRAM_SCHEMA,
        "status": "BLOCKED_PENDING_INDEPENDENT_MASTER_PROGRAM_ORACLE",
        "fullBed": {
            "retainedSourceGuids": sorted(continuous_sources),
            "mutedFiniteEngineEventSourceGuids": sorted(finite_sources),
            "claim": "continuousEngineEventOnly; finiteEngineEventSourcesReplayThroughLifecycleAtlas",
        },
        "sourcePartition": {
            "LOAD": sources_by_role["LOAD"],
            "COAST": sources_by_role["COAST"],
            "UNAFFECTED": sources_by_role["UNAFFECTED"],
            "evidence": sorted(role_evidence, key=lambda item: item["sourceGuid"]),
            "exactContinuousGuidSetEquality": True,
            "rolePartitionCertifications": role_partition_certifications,
        },
        "capture": {
            "method": "independentFreshEventMasterOutputPrograms-v1",
            "full": "retainEveryContinuousCoreChannelAndMuteFiniteEngineEventChannels",
            "loadOnly": "forceThrottleOneRetainLOADAndUNAFFECTEDMuteEveryCOASTRoute",
            "coastOnly": "forceThrottleZeroRetainCOASTAndUNAFFECTEDMuteEveryLOADRoute",
            "unaffected": "retainInEveryIndependentProgram",
            "emptyRole": "retainUNAFFECTEDOnlyAndNeverCopyOrSynthesizeAnOppositeRoleSource",
            "roleClassification": (
                "measureTheActualAuthoredChannelGroupRouteGainAtThrottleZeroAndOne; "
                "sampleNamesAndDeclaredRecipeRolesAreNonAuthoritativeHints"
            ),
            "sourceBankMutation": "forbidden",
            "eventIsolation": "freshFmodSystemAndEventInstanceForEveryOutputProgram",
            "writerOutput": "captureAfterCompleteAuthoredEventCommonAndMasterDspRoute",
            "additiveRecomposition": "forbiddenForSharedStatefulOrNonlinearFmodDsp",
        },
        "runtime": {
            "BOTH": "selectFULLAtLivePedalThrottle",
            "LOAD": "selectLOAD_ONLYAtForcedThrottleOne",
            "COAST": "selectCOAST_ONLYAtForcedThrottleZero",
            "programCombination": "neverSampleAddIndependentPrograms",
            "fixedModeTrim": "postMasterScaleSelectedIndependentProgram",
            "bothTrim": "liveThrottleWeightedPostMasterScaleOfFULL",
        },
        "requiredOracle": {
            "sourceSchedulingIdentity": (
                "everyRetainedExcludedAndEndpointInvariantChannelReconciledToItsExactAuthoredBindingRoute"
            ),
            "masterOutputParity": "eachProgramComparedToSameOriginalBankModeAfterCompleteFmodRoute",
            "freshState": "eachProgramUsesIndependentSystemEventDecoderAndDspWarmup",
            "sourcePurity": "LOAD_ONLYHasNoCOASTRouteAndCOAST_ONLYHasNoLOADRoute",
            "dynamicRouteSafety": "maskReappliedEveryDspBlockAndNewOrUnclassifiedRouteBlocksRelease",
            "emptyRoleProof": "emptyRoleProgramContainsOnlyCertifiedUNAFFECTEDRoutes",
        },
    }


def _continuous_layer_is_active(
    layer: Mapping[str, Any], *, rpm: float, throttle: float
) -> tuple[bool, bool]:
    """Evaluate an engine layer's exact final-value placement membership."""

    values = layer.get("runtimeParameters")
    if not isinstance(values, Mapping):
        raise ProfileRecipeError(
            f"engine layer {layer.get('sourceGuid')} has no GUID-bound runtime defaults"
        )
    current = {
        str(parameter): _finite(value, f"engine layer runtime default {parameter}")
        for parameter, value in values.items()
    }
    bindings = layer.get("hostParameterBindings")
    if not isinstance(bindings, Sequence) or isinstance(bindings, (str, bytes)):
        raise ProfileRecipeError(
            f"engine layer {layer.get('sourceGuid')} has no host parameter bindings"
        )
    for binding in bindings:
        if not isinstance(binding, Mapping):
            raise ProfileRecipeError("engine layer host parameter binding is invalid")
        parameter = str(binding.get("parameter") or "")
        source = binding.get("source")
        if source == "EngineSimulation.rpm":
            current[parameter] = rpm
        elif source == "EngineSimulation.throttle":
            current[parameter] = throttle
        elif "constant" in binding:
            current[parameter] = _finite(
                binding["constant"], f"engine layer host constant {parameter}"
            )
        else:
            raise ProfileRecipeError(
                f"engine layer {layer.get('sourceGuid')} has unsupported host binding"
            )

    membership = layer.get("continuousParameterMembership")
    if membership is None:
        return True, False
    if not isinstance(membership, Mapping) or membership.get("schema") != (
        "byd-fmod-continuous-placement-membership-v1"
    ):
        raise ProfileRecipeError(
            f"engine layer {layer.get('sourceGuid')} has invalid continuous membership"
        )
    membership_value = membership.get("membership")
    placements = (
        membership_value.get("placements")
        if isinstance(membership_value, Mapping)
        else None
    )
    automatic_dependencies = membership.get("automaticPlacementDependencies", [])
    if not isinstance(automatic_dependencies, Sequence) or isinstance(
        automatic_dependencies, (str, bytes)
    ):
        raise ProfileRecipeError("engine layer automatic placement dependencies are invalid")
    if not isinstance(placements, Mapping):
        raise ProfileRecipeError(
            f"engine layer {layer.get('sourceGuid')} has invalid continuous membership"
        )
    for parameter, spans in placements.items():
        if not isinstance(spans, Sequence) or isinstance(spans, (str, bytes)):
            raise ProfileRecipeError("engine layer membership spans are invalid")
        value = current.get(str(parameter))
        if value is None:
            raise ProfileRecipeError(
                f"engine layer membership parameter {parameter} has no current value"
            )
        for span in spans:
            if not isinstance(span, Mapping):
                raise ProfileRecipeError("engine layer membership span is invalid")
            start = _finite(span.get("start"), "engine placement start")
            end = _finite(span.get("end"), "engine placement end")
            if value < start or value > end:
                return False, False
            if value == end and span.get("includeEnd") is not True:
                return False, False
    # Automatic FMOD parameters (for example Event Cone Angle) are owned by
    # the renderer, not Android or a graph default.  Retain their source as a
    # potential raw channel for the conservative multiplicity bound and block
    # release until callback snapshots at each engine cell resolve it.
    return True, bool(automatic_dependencies)


def _engine_logical_channel_multiplicity(
    program: Mapping[str, Any], nodes: Sequence[Mapping[str, Any]]
) -> dict[str, Any]:
    """Preserve raw FMOD source-channel demand beneath a premixed bed.

    Android's full-event node is one decoded stream, but FMOD can schedule many
    continuous waveform channels to produce it.  This record is deliberately
    separate from the Android playback-node count so the global Assetto voice
    oracle can validate that premixing did not change channel admission.
    """

    layers = program.get("layers")
    if not isinstance(layers, Sequence) or isinstance(layers, (str, bytes)):
        raise ProfileRecipeError("engine program layers are invalid")
    groups: dict[str, dict[str, Any]] = {}
    for layer in layers:
        if not isinstance(layer, Mapping):
            raise ProfileRecipeError("engine layer is invalid")
        source_guid = str(layer.get("sourceGuid") or "")
        scheduler = layer.get("schedulingGroup")
        if not source_guid or not isinstance(scheduler, Mapping):
            raise ProfileRecipeError(
                "engine layer has no exact scheduling-group channel topology"
            )
        if scheduler.get("complete") is not True:
            raise ProfileRecipeError(
                f"engine layer {source_guid} has unsupported scheduling topology: "
                f"{scheduler.get('incompleteReason')}"
            )
        group_id = str(scheduler.get("groupId") or "")
        composition = str(scheduler.get("composition") or "")
        if not group_id or composition not in {"simultaneousLayer", "playlistAlternative"}:
            raise ProfileRecipeError(
                f"engine layer {source_guid} has invalid scheduling-group topology"
            )
        existing = groups.setdefault(
            group_id,
            {
                "groupId": group_id,
                "composition": composition,
                "scheduler": copy.deepcopy(dict(scheduler)),
                "layers": [],
            },
        )
        if existing["composition"] != composition or canonical_json_bytes(
            existing["scheduler"]
        ) != canonical_json_bytes(scheduler):
            raise ProfileRecipeError(
                f"engine scheduling group {group_id} has inconsistent topology"
            )
        existing["layers"].append(layer)

    cells: list[dict[str, Any]] = []
    maximum = 0
    for node in sorted(
        nodes, key=lambda item: (float(item["rpm"]), float(item["throttle"]))
    ):
        rpm = _finite(node.get("rpm"), "engine node RPM")
        throttle = _finite(node.get("throttle"), "engine node throttle")
        group_records: list[dict[str, Any]] = []
        count = 0
        for group_id, group in sorted(groups.items()):
            activities = [
                (
                    layer,
                    *_continuous_layer_is_active(layer, rpm=rpm, throttle=throttle),
                )
                for layer in group["layers"]
            ]
            active_layers = [layer for layer, active, _automatic in activities if active]
            active_guids = sorted(str(layer["sourceGuid"]) for layer in active_layers)
            automatic_guids = sorted(
                str(layer["sourceGuid"])
                for layer, active, automatic in activities
                if active and automatic
            )
            multiplicity = (
                len(active_guids)
                if group["composition"] == "simultaneousLayer"
                else min(1, len(active_guids))
            )
            count += multiplicity
            group_records.append(
                {
                    "groupId": group_id,
                    "composition": group["composition"],
                    "activeCandidateSourceGuids": active_guids,
                    "automaticPlacementPotentialSourceGuids": automatic_guids,
                    "logicalSourceChannelMultiplicity": multiplicity,
                    "authoredMembers": copy.deepcopy(
                        group["scheduler"].get("members", [])
                    ),
                }
            )
        maximum = max(maximum, count)
        cells.append(
            {
                "parameters": {"rpms": rpm, "throttle": throttle},
                "logicalSourceChannelMultiplicity": count,
                "schedulingGroups": group_records,
            }
        )
    return {
        "schema": "byd-full-event-engine-logical-channel-multiplicity-v1",
        "scope": "rawFmodContinuousWaveformSourcesBeforeAndroidFullEventPremix",
        "cellMembership": "exactFinalValueContinuousParameterPlacementAND",
        "playlistMultiplicity": "oneSelectedAuthoredAlternativeWhenAnyCandidateIsActive",
        "simultaneousMultiplicity": "oneForEveryActiveAuthoredLayer",
        "cells": cells,
        "maximumLogicalSourceChannelsAtAtlasNode": maximum,
        "compositeCreationOrder": {
            "status": "BLOCKED_PENDING_CALLBACK_TRACE_PER_ENGINE_CELL",
            "reason": "authored topology preserves candidates but not the observed FMOD channel creation order",
            "requiredEvidence": "perDspBlockCallbackTraceAndLogicalRealChannelSnapshot",
        },
        "automaticPlacementResolution": {
            "status": (
                "BLOCKED_PENDING_CALLBACK_TRACE_PER_ENGINE_CELL"
                if any(
                    group["automaticPlacementPotentialSourceGuids"]
                    for cell in cells
                    for group in cell["schedulingGroups"]
                )
                else "NOT_APPLICABLE"
            ),
            "policy": "countPotentialAutomaticSourcesUntilRendererCallbackSnapshotsResolveActualMembership",
        },
    }


def _midpoints(axis: Sequence[float]) -> list[float]:
    return [round((left + right) * 0.5, 8) for left, right in zip(axis, axis[1:])]


def _effect_variant_corner_count(variant: Mapping[str, Any]) -> int:
    """Return the live N-D contributors needed for one selected variant.

    A finite event does not create one Android output voice per contributor:
    its exact non-zero contributors are mixed into one logical finite ring
    instance at the semantic trigger.  Continuous events retain their corners
    live, so their contributor count is also their playback voice count.
    """

    axes = variant.get("parameterAxes", {})
    if not isinstance(axes, Mapping):
        return 0
    return 2 ** sum(
        1
        for values in axes.values()
        if isinstance(values, Sequence) and not isinstance(values, (str, bytes))
        and len(values) > 1
    )


def _effect_variant_perspectives(variant: Mapping[str, Any]) -> set[str]:
    mapping = variant.get("runtimeMapping")
    if not isinstance(mapping, Mapping):
        return set()
    values = mapping.get("perspectives")
    if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
        values = [mapping.get("perspective")]
    return {
        str(value)
        for value in values
        if str(value) in {"cabin", "exterior"}
    }


def _effect_variant_lifecycles(variant: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    mapping = variant.get("runtimeMapping")
    values = mapping.get("semanticLifecycle", []) if isinstance(mapping, Mapping) else []
    if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
        return []
    return [value for value in values if isinstance(value, Mapping)]


def _finite_lifecycle_is_runtime_proven(mapping: Mapping[str, Any]) -> bool:
    """Fail closed until source-specific original-bank lifecycle evidence lands."""

    topology = mapping.get("finiteLifecycleTopology")
    if topology is None:
        return True
    return isinstance(topology, Mapping) and topology.get("status") != (
        "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE"
    )


def _parameter_placement_entry(
    variant: Mapping[str, Any],
) -> Mapping[str, Any] | None:
    for lifecycle in _effect_variant_lifecycles(variant):
        if lifecycle.get("trigger") == "PARAMETER_PLACEMENT_ENTRY":
            entry = lifecycle.get("parameterPlacementEntry")
            if isinstance(entry, Mapping):
                return entry
    return None


def _inside_parameter_placement_entry(
    variant: Mapping[str, Any], state: Mapping[str, float]
) -> bool:
    """Evaluate the proven FMOD final-value AND membership rule exactly."""

    entry = _parameter_placement_entry(variant)
    if not isinstance(entry, Mapping):
        return False
    membership = entry.get("membership")
    placements = membership.get("placements") if isinstance(membership, Mapping) else None
    if not isinstance(placements, Mapping) or not placements:
        return False
    for parameter, spans in placements.items():
        if not isinstance(spans, Sequence) or isinstance(spans, (str, bytes)):
            return False
        value = state.get(str(parameter))
        if value is None:
            return False
        for span in spans:
            if not isinstance(span, Mapping):
                return False
            start = _finite(span.get("start"), "parameter placement start")
            end = _finite(span.get("end"), "parameter placement end")
            if value < start:
                return False
            if value > end:
                return False
            if value == end and span.get("includeEnd") is not True:
                return False
    return True


def _placement_candidate_states(
    variants: Sequence[Mapping[str, Any]],
) -> tuple[list[dict[str, float]], dict[str, float]]:
    """Construct representative final-value cells plus one shared outside state.

    ``nextafter`` represents the authored open side without inventing a fixed
    epsilon.  The shared upper state is outside every retained placement, so
    a transition from it to any candidate is a valid final-value re-entry.
    """

    values_by_parameter: dict[str, set[float]] = {}
    for variant in variants:
        entry = _parameter_placement_entry(variant)
        membership = entry.get("membership") if isinstance(entry, Mapping) else None
        placements = membership.get("placements") if isinstance(membership, Mapping) else None
        if not isinstance(placements, Mapping):
            continue
        for parameter, spans in placements.items():
            if not isinstance(spans, Sequence) or isinstance(spans, (str, bytes)):
                continue
            candidates = values_by_parameter.setdefault(str(parameter), set())
            for span in spans:
                if not isinstance(span, Mapping):
                    continue
                start = _finite(span.get("start"), "parameter placement start")
                end = _finite(span.get("end"), "parameter placement end")
                candidates.update(
                    {
                        math.nextafter(start, -math.inf),
                        start,
                        (start + end) * 0.5,
                        end,
                        math.nextafter(end, math.inf),
                    }
                )
    if not values_by_parameter:
        return [], {}
    names = sorted(values_by_parameter)
    states = [
        dict(zip(names, values))
        for values in itertools.product(
            *(sorted(values_by_parameter[name]) for name in names)
        )
    ]
    outside = {
        name: max(values_by_parameter[name]) + max(1.0, abs(max(values_by_parameter[name])) * 1e-6)
        for name in names
    }
    return states, outside


def _group_selected_corner_contributors(
    group: Sequence[Mapping[str, Any]],
    selected: Sequence[Mapping[str, Any]],
) -> int:
    if not selected:
        return 0
    scheduler = group[0].get("schedulingGroup", {})
    counts = [_effect_variant_corner_count(variant) for variant in selected]
    if isinstance(scheduler, Mapping) and scheduler.get("composition") == "playlistAlternative":
        return max(counts, default=0)
    return sum(counts)


def _group_selected_fmod_source_channels(
    group: Sequence[Mapping[str, Any]],
    selected: Sequence[Mapping[str, Any]],
) -> int:
    """Count raw FMOD waveform channels, never Android mix contributors."""

    if not selected:
        return 0
    scheduler = group[0].get("schedulingGroup", {})
    if not isinstance(scheduler, Mapping):
        raise ProfileRecipeError("effect source group has no scheduling contract")
    composition = scheduler.get("composition")
    if composition == "playlistAlternative":
        return 1
    if composition == "simultaneousLayer":
        return len(selected)
    raise ProfileRecipeError(f"effect source group has unsupported composition {composition}")


def _effect_variant_capture_node_count(variant: Mapping[str, Any]) -> int:
    """Return every emitted source node for pre-armed finite-cache accounting.

    This is intentionally a product of all authored capture-axis values, not a
    current-cell corner count.  The cache holds attack frames for every finite
    node before a trigger, whereas a logical ring materializes only the lower/
    upper nonzero corners selected by that one trigger.
    """

    axes = variant.get("parameterAxes")
    if not isinstance(axes, Mapping):
        raise ProfileRecipeError("effect source has no parameter axes for cache accounting")
    count = 1
    for parameter, values in axes.items():
        if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
            raise ProfileRecipeError(
                f"effect source axis {parameter} is not a finite value sequence"
            )
        if not values:
            raise ProfileRecipeError(f"effect source axis {parameter} is empty")
        count *= len(values)
    return count


def _selected_perspective_effect_resources(
    variants: Sequence[Mapping[str, Any]],
    perspective: str,
) -> dict[str, Any]:
    """Emit a proof-shaped, non-conflated resource model for one perspective.

    This intentionally does *not* claim a global voice ceiling.  Assetto
    initializes Studio with 2,048 logical channels and a 256 real software
    channel budget.  Its priority, steal, and virtual-voice behavior must be
    captured empirically before Android can claim any tail-overlap limit.
    """

    scoped = [variant for variant in variants if perspective in _effect_variant_perspectives(variant)]
    groups: dict[str, list[Mapping[str, Any]]] = {}
    for variant in scoped:
        scheduler = variant.get("schedulingGroup")
        if not isinstance(scheduler, Mapping):
            continue
        group_id = str(scheduler.get("groupId") or "")
        if group_id:
            groups.setdefault(group_id, []).append(variant)

    continuous_mmap_corners = 0
    continuous_fmod_source_channels = 0
    finite_groups: list[tuple[str, list[Mapping[str, Any]]]] = []
    for group_id, group in sorted(groups.items()):
        continuous = [variant for variant in group if variant.get("lifetime") == "continuous"]
        finite = [variant for variant in group if variant.get("lifetime") != "continuous"]
        if continuous:
            continuous_mmap_corners += _group_selected_corner_contributors(group, continuous)
            continuous_fmod_source_channels += _group_selected_fmod_source_channels(
                group, continuous
            )
        if finite:
            finite_groups.append((group_id, finite))

    entry_variants = [
        variant
        for _, group in finite_groups
        for variant in group
        if _parameter_placement_entry(variant) is not None
    ]
    candidates, outside = _placement_candidate_states(entry_variants)
    if outside and any(_inside_parameter_placement_entry(variant, outside) for variant in entry_variants):
        raise ProfileRecipeError(
            f"{perspective} has no shared outside state for parameter-placement entry proof"
        )

    defaults: dict[str, float] = {}
    for _, group in finite_groups:
        for variant in group:
            mapping = variant.get("runtimeMapping")
            values = mapping.get("parameters") if isinstance(mapping, Mapping) else None
            if not isinstance(values, Mapping):
                continue
            for parameter, value in values.items():
                defaults[str(parameter)] = _finite(value, f"runtime parameter default {parameter}")

    group_records: list[dict[str, Any]] = []
    initial_logical = 0
    initial_contributors = 0
    initial_fmod_source_channels = 0
    finite_attack_cache_bytes = 0
    maximum_capture_frames_by_group: dict[str, int] = {}
    for group_id, group in finite_groups:
        scheduler = group[0].get("schedulingGroup", {})
        placement_sources = [
            variant for variant in group if _parameter_placement_entry(variant) is not None
        ]
        nonplacement_sources = [
            variant for variant in group if _parameter_placement_entry(variant) is None
        ]
        initial_selected = [
            variant for variant in placement_sources
            if _inside_parameter_placement_entry(variant, defaults)
        ]
        initial_count = _group_selected_corner_contributors(group, initial_selected)
        if initial_count:
            initial_logical += 1
            initial_contributors += initial_count
            initial_fmod_source_channels += _group_selected_fmod_source_channels(
                group, initial_selected
            )
        host_gain_classes = {
            str((variant.get("runtimeMapping") or {}).get("hostGainClass") or "")
            for variant in group
        }
        if len(host_gain_classes) != 1:
            raise ProfileRecipeError(
                f"finite scheduling group {group_id} has no single host gain bus"
            )
        finite_frames = []
        for variant in group:
            duration_frames = variant.get("captureDurationFrames")
            if (
                not isinstance(duration_frames, int)
                or isinstance(duration_frames, bool)
                or duration_frames <= 0
            ):
                raise ProfileRecipeError(
                    f"finite source {variant.get('sourceGuid')} has no exact capture duration"
                )
            finite_frames.append(duration_frames)
            finite_attack_cache_bytes += (
                min(duration_frames, FINITE_ATTACK_CACHE_FRAMES)
                * _effect_variant_capture_node_count(variant)
                * PCM_BYTES_PER_FRAME
            )
        maximum_capture_frames_by_group[group_id] = max(finite_frames, default=0)
        group_records.append(
            {
                "groupId": group_id,
                "composition": scheduler.get("composition"),
                "hostGainClass": next(iter(host_gain_classes)),
                "semanticTriggers": sorted(
                    {
                        str(lifecycle.get("trigger"))
                        for variant in group
                        for lifecycle in _effect_variant_lifecycles(variant)
                        if lifecycle.get("trigger")
                    }
                ),
                "logicalRingVoicesPerSemanticTrigger": 1,
                "maximumSourceCornerContributorsPerLogicalRing": _group_selected_corner_contributors(group, group),
                "maximumFmodSourceChannelsPerLogicalRing": _group_selected_fmod_source_channels(group, group),
                "maximumCaptureFramesPerLogicalRing": maximum_capture_frames_by_group[group_id],
                "streamingRingBufferFrames": FINITE_STREAMING_RING_BUFFER_FRAMES,
                "initialPlacementEntry": {
                    "logicalRingVoices": 1 if initial_count else 0,
                    "sourceCornerContributors": initial_count,
                    "fmodSourceChannels": (
                        _group_selected_fmod_source_channels(group, initial_selected)
                        if initial_count
                        else 0
                    ),
                },
                "parameterPlacementSourceCount": len(placement_sources),
                "nonPlacementSourceGuids": sorted(
                    str(variant.get("sourceGuid")) for variant in nonplacement_sources
                ),
                "tailOverlapArbitration": {
                    "status": "BLOCKED_PENDING_ASSETTO_2048_LOGICAL_256_REAL_CHANNEL_ARBITRATION_ORACLE",
                    "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                    "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                    "requiresRepresentativeOverlapAndGlobalChannelLifecycleEvidence": True,
                },
            }
        )

    peak_entry = {
        "logicalRingVoices": 0,
        "sourceCornerContributors": 0,
        "fmodSourceChannels": 0,
        "targetParameters": None,
        "priorParameters": outside or None,
        "groupIds": [],
    }
    for state in candidates:
        selected_groups: list[str] = []
        contributors = 0
        fmod_source_channels = 0
        for group_id, group in finite_groups:
            selected = [
                variant
                for variant in group
                if _parameter_placement_entry(variant) is not None
                and _inside_parameter_placement_entry(variant, state)
            ]
            count = _group_selected_corner_contributors(group, selected)
            if count:
                selected_groups.append(group_id)
                contributors += count
                fmod_source_channels += _group_selected_fmod_source_channels(
                    group, selected
                )
        candidate = (len(selected_groups), contributors, fmod_source_channels)
        previous = (
            int(peak_entry["logicalRingVoices"]),
            int(peak_entry["sourceCornerContributors"]),
            int(peak_entry["fmodSourceChannels"]),
        )
        if candidate > previous:
            peak_entry = {
                "logicalRingVoices": len(selected_groups),
                "sourceCornerContributors": contributors,
                "fmodSourceChannels": fmod_source_channels,
                "targetParameters": state,
                "priorParameters": outside,
                "groupIds": selected_groups,
            }

    nonplacement_group_ids = sorted(
        record["groupId"]
        for record in group_records
        if record["nonPlacementSourceGuids"]
    )
    nonplacement_records = [
        record for record in group_records if record["nonPlacementSourceGuids"]
    ]
    nonplacement_trigger_contributors = sum(
        int(record["maximumSourceCornerContributorsPerLogicalRing"])
        for record in nonplacement_records
    )
    nonplacement_trigger_rings = len(nonplacement_records)
    nonplacement_trigger_fmod_source_channels = sum(
        int(record["maximumFmodSourceChannelsPerLogicalRing"])
        for record in nonplacement_records
    )
    nonplacement_initial = [
        record
        for record in nonplacement_records
        if "ENGINE_EVENT_START" in record["semanticTriggers"]
    ]
    initial_logical += len(nonplacement_initial)
    initial_contributors += sum(
        int(record["maximumSourceCornerContributorsPerLogicalRing"])
        for record in nonplacement_initial
    )
    initial_fmod_source_channels += sum(
        int(record["maximumFmodSourceChannelsPerLogicalRing"])
        for record in nonplacement_initial
    )
    finite_mmaps_per_update = (
        int(peak_entry["sourceCornerContributors"])
        + nonplacement_trigger_contributors
    )
    return {
        "schema": "byd-full-event-effect-resource-contract-v2",
        "perspective": perspective,
        "continuous": {
            "maximumMmapPlaybackCornerVoices": continuous_mmap_corners,
            "maximumMappedSourceCorners": continuous_mmap_corners,
            "maximumFmodLogicalSourceChannels": continuous_fmod_source_channels,
            "mappingPolicy": "mapOnlyCurrentSourceCorners",
        },
        "finite": {
            "logicalVoiceModel": "onePreallocatedFiniteRingVoicePerSchedulingGroupInstance",
            "cornerContributorMix": "mixEverySelectedNonZeroNDimensionalCornerIntoTheLogicalRingAtSemanticTrigger",
            "ringSampleFormat": "float32OrFloat64Stereo; noPcm16PremixQuantizationOrClipping",
            "selectionAtomicity": "evaluateSchedulingGroupAndMemberChancesOnceThenMaterializeAllSelectedContributorsAtomically",
            "sourceCornerRegions": "audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; noAudioCallbackMmapAllocationLockOrPcm16PremixStorage",
            "finiteAttackCacheBytes": finite_attack_cache_bytes,
            "finiteAttackCacheContract": {
                "schema": "byd-full-event-finite-attack-cache-v1",
                "nodeFrames": f"min(nodeFrames,{FINITE_ATTACK_CACHE_FRAMES})",
                "format": "stereo-pcm16-le",
                "bytesPerFrame": PCM_BYTES_PER_FRAME,
                "population": "everyFiniteNodeVisibleInSelectedPerspectivePrearmedBeforeSemanticTrigger",
            },
            "finiteRingPoolBytes": None,
            "finiteRingPoolContract": {
                "schema": "byd-full-event-finite-ring-pool-v1",
                "status": "BLOCKED_PENDING_PER_FAMILY_PERSPECTIVE_CAUSAL_TAIL_PEAK",
                "format": "stereo-float32",
                "bytesPerFrame": FINITE_RING_STEREO_FLOAT32_BYTES_PER_FRAME,
                "formula": "sum(physicalLiveLogicalRingInstancesBySchedulingGroup[groupId]*streamingRingBufferFrames[groupId]*bytesPerFrame)",
                "tailOverlapUses": "maximumCaptureFramesPerLogicalRing; not the circular ring capacity",
                "physicalLiveLogicalRingInstancesBySchedulingGroup": None,
                "requiresRawFmodChannelSnapshotReconciliation": True,
            },
            "initialPlacementEntryPeak": {
                "hostParameters": defaults,
                "logicalRingVoices": initial_logical,
                "sourceCornerContributors": initial_contributors,
                "fmodSourceChannels": initial_fmod_source_channels,
            },
            "outsideToInsideEntryPeak": peak_entry,
            "nonPlacementSemanticTriggerWorstCase": {
                "logicalRingVoices": nonplacement_trigger_rings,
                "sourceCornerContributors": nonplacement_trigger_contributors,
                "fmodSourceChannels": nonplacement_trigger_fmod_source_channels,
                "groupIds": nonplacement_group_ids,
                "status": "REQUIRES_TRIGGER_TRAJECTORY_ORACLE",
            },
            "combinedOneDspUpdateUpperBound": {
                "logicalRingVoices": int(peak_entry["logicalRingVoices"])
                + nonplacement_trigger_rings,
                "sourceCornerContributors": finite_mmaps_per_update,
                "fmodSourceChannels": int(peak_entry["fmodSourceChannels"])
                + nonplacement_trigger_fmod_source_channels,
                "composition": "parameterPlacementEntriesPlusIndependentNonPlacementSemanticTriggers",
            },
            "maximumSourceCornerContributorsPerUpdate": finite_mmaps_per_update,
            "maximumFmodLogicalSourceChannelsPerUpdate": int(
                peak_entry["fmodSourceChannels"]
            )
            + nonplacement_trigger_fmod_source_channels,
            "maximumMappedSourceCornerRegionsDuringMaterialization": finite_mmaps_per_update,
            "nonPlacementTriggerGroupsRequireSeparateOracle": nonplacement_group_ids,
            "groups": group_records,
        },
        "totalActiveContributorsForCpu": {
            "continuousMmapCorners": continuous_mmap_corners,
            "continuousFmodLogicalSourceChannels": continuous_fmod_source_channels,
            "finiteCornerContributorsPerUpdatePeak": finite_mmaps_per_update,
            "finiteLogicalRingVoicesPerOneDspUpdate": int(
                peak_entry["logicalRingVoices"]
            ) + nonplacement_trigger_rings,
            "finiteFmodLogicalSourceChannelsPerOneDspUpdate": int(
                peak_entry["fmodSourceChannels"]
            ) + nonplacement_trigger_fmod_source_channels,
        },
        "peakProof": {
            "status": "BLOCKED_PENDING_DYNAMIC_LIFECYCLE_AND_OVERFLOW_ORACLE",
            "reason": "finite tail overlap needs Assetto 2048-logical/256-real priority, steal, virtualization, and runtime-arbitration evidence",
        },
    }


def _validate_exact_engine_event_role_variant(
    event_path: str, variant: Mapping[str, Any]
) -> None:
    source_guid = str(variant.get("sourceGuid") or "")
    role = variant.get("engineProgramRole")
    evidence = variant.get("engineProgramRoleEvidence")
    identity = evidence.get("bindingIdentity") if isinstance(evidence, Mapping) else None
    scheduling_group = variant.get("schedulingGroup")
    probe_values = evidence.get("probeValues") if isinstance(evidence, Mapping) else None
    if role not in {"LOAD", "COAST", "UNAFFECTED"}:
        raise ProfileRecipeError(
            f"engine-event source {source_guid} has no exact LOAD/COAST/unaffected role"
        )
    if (
        not isinstance(evidence, Mapping)
        or evidence.get("schema") != "byd-full-event-engine-program-role-v2"
        or evidence.get("status")
        != "PASS_EXACT_AUTHORED_BINDING_ROUTE_CLASSIFICATION"
        or evidence.get("classificationUsesDiagnosticName") is not False
        or evidence.get("role") != role
        or not isinstance(identity, Mapping)
        or identity.get("eventPath") != event_path
        or identity.get("sourceGuid") != source_guid
        or identity.get("authoredBindingKey") != variant.get("authoredBindingKey")
        or not isinstance(scheduling_group, Mapping)
        or identity.get("schedulingGroupId") != scheduling_group.get("groupId")
        or not isinstance(probe_values, list)
        or not probe_values
    ):
        raise ProfileRecipeError(
            f"engine-event source {source_guid} role is not bound to its exact authored route"
        )


def _effect_event_plans(recipe: Mapping[str, Any]) -> list[dict[str, Any]]:
    extraction = {
        item["assetName"]: item for item in recipe["extraction"]["sources"]
    }
    grouped: dict[str, list[Mapping[str, Any]]] = {}
    for binding in recipe["sourceConservationAudit"]["coreEventBindings"]:
        event_path = str(binding["eventPath"])
        runtime_mapping = binding.get("runtimeMapping")
        if not isinstance(runtime_mapping, Mapping):
            # A graph-reachable mechanical source can be conservation-only
            # when the selected NativeFmodAudio host never creates its event
            # path.  It must not be treated as a missing WAV or an Android
            # effect program.
            continue
        runtime_kind = runtime_mapping.get("kind")
        if (
            (event_path.endswith("/engine_int") or event_path.endswith("/engine_ext"))
            and runtime_kind != "engineEventTransient"
        ):
            continue
        grouped.setdefault(event_path, []).append(binding)
    result: list[dict[str, Any]] = []
    for event_path, bindings in sorted(grouped.items()):
        sources = [extraction[item["assetName"]] for item in bindings]
        authored_states = {
            canonical_json_bytes(source["primaryCapture"].get("parameters", {})):
            source["primaryCapture"].get("parameters", {})
            for source in sources
        }
        lifetimes = sorted({source["lifetime"] for source in sources})
        suffix = event_path.rsplit("/", 1)[-1]
        perspective_scope = sorted(
            {
                perspective
                for binding in bindings
                for perspective in (
                    (binding.get("runtimeMapping") or {}).get("perspectives")
                    or [
                        (binding.get("runtimeMapping") or {}).get("perspective")
                    ]
                )
                if perspective in {"cabin", "exterior"}
            }
        )
        variant_contract = [
            {
                "bindingId": str(
                    (binding.get("runtimeMapping") or {}).get(
                        "authoredBindingKey"
                    )
                    or ""
                ),
                "authoredBindingKey": str(
                    (binding.get("runtimeMapping") or {}).get(
                        "authoredBindingKey"
                    )
                    or ""
                ),
                "sourceGuid": str(binding["sourceGuid"]),
                "diagnosticName": source["diagnosticNameNotUsedForClassification"],
                "lifetime": source["lifetime"],
                "captureDurationFrames": source["primaryCapture"]["durationFrames"],
                "parameters": source["primaryCapture"].get("parameters", {}),
                "parameterAxes": source["primaryCapture"].get(
                    "parameterAxes", {}
                ),
                "runtimeMapping": binding.get("runtimeMapping"),
                "engineProgramRole": (binding.get("runtimeMapping") or {}).get(
                    "engineProgramRole"
                ),
                "engineProgramRoleEvidence": copy.deepcopy(
                    (binding.get("runtimeMapping") or {}).get(
                        "engineProgramRoleEvidence"
                    )
                ),
                "schedulingGroup": (binding.get("runtimeMapping") or {}).get(
                    "schedulingGroup"
                ),
            }
            for binding, source in sorted(
                zip(bindings, sources), key=lambda item: str(item[0]["sourceGuid"])
            )
        ]
        for variant in variant_contract:
            key = str(variant["authoredBindingKey"])
            if not re.fullmatch(r"binding:[0-9a-f]{64}", key):
                raise ProfileRecipeError(
                    f"effect source {variant['sourceGuid']} has no lossless authored binding key"
                )
            host_gain_class = (variant.get("runtimeMapping") or {}).get(
                "hostGainClass"
            )
            engine_program_role = variant.get("engineProgramRole")
            if host_gain_class == "engineEvent":
                _validate_exact_engine_event_role_variant(event_path, variant)
            elif engine_program_role is not None:
                raise ProfileRecipeError(
                    f"effect-event source {variant['sourceGuid']} unexpectedly owns an engine program role"
                )
        if len({item["authoredBindingKey"] for item in variant_contract}) != len(
            variant_contract
        ):
            raise ProfileRecipeError(
                f"event {event_path} duplicates an authored binding key"
            )
        variant_by_guid: dict[str, Mapping[str, Any]] = {}
        for variant in variant_contract:
            source_guid = str(variant["sourceGuid"])
            if source_guid in variant_by_guid:
                raise ProfileRecipeError(
                    f"event {event_path} reuses source GUID {source_guid} for multiple authored bindings; graph occurrence expansion is required"
                )
            variant_by_guid[source_guid] = variant
        variants_by_scheduling_group: dict[str, list[Mapping[str, Any]]] = {}
        for variant in variant_contract:
            scheduler = variant.get("schedulingGroup")
            if not isinstance(scheduler, Mapping):
                continue
            group_id = str(scheduler.get("groupId") or "")
            if group_id:
                variants_by_scheduling_group.setdefault(group_id, []).append(variant)
        for group_id, group in variants_by_scheduling_group.items():
            gain_classes = {
                str((variant.get("runtimeMapping") or {}).get("hostGainClass") or "")
                for variant in group
            }
            if len(gain_classes) != 1 or gain_classes - {"engineEvent", "effectEvent"}:
                raise ProfileRecipeError(
                    f"scheduling group {group_id} mixes host gain classes; separate host buses are required"
                )
        # A finite source can only be captured/interpolated inside its proven
        # final-value placement gate.  Keeping an outside controller knot
        # would create a silent node and let a nearest/corner runtime choose a
        # state FMOD never schedules.  Static values remain the exact capture
        # defaults; every dynamic axis value is filtered against its own
        # placement while the other values stay at that known-inside capture.
        for variant in variant_contract:
            entry = _parameter_placement_entry(variant)
            if entry is None or variant.get("lifetime") == "continuous":
                continue
            base = {
                str(key): _finite(value, f"effect capture parameter {key}")
                for key, value in dict(variant.get("parameters") or {}).items()
            }
            filtered_axes: dict[str, list[float]] = {}
            for parameter, values in dict(variant.get("parameterAxes") or {}).items():
                retained: list[float] = []
                for value in values:
                    trial = {**base, str(parameter): _finite(value, f"effect axis {parameter}")}
                    if _inside_parameter_placement_entry(variant, trial):
                        retained.append(float(value))
                if not retained:
                    raise ProfileRecipeError(
                        f"finite placement source {variant['sourceGuid']} has no audible {parameter} interpolation knot"
                    )
                filtered_axes[str(parameter)] = sorted(set(retained))
            variant["parameterAxes"] = filtered_axes
        parameter_axes: dict[str, set[float]] = {}
        for variant in variant_contract:
            for parameter, values in variant.get("parameterAxes", {}).items():
                parameter_axes.setdefault(str(parameter), set()).update(
                    float(value) for value in values
                )
        normalized_parameter_axes = {
            parameter: sorted(values)
            for parameter, values in sorted(parameter_axes.items())
        }
        nodes: list[dict[str, Any]] = []
        for source in sources:
            capture = source["primaryCapture"]
            binding = variant_by_guid[str(source["sourceGuid"])]
            source_axes = binding.get("parameterAxes", {})
            parameter_names = sorted(source_axes)
            capture_states = (
                [
                    {
                        **dict(capture.get("parameters", {})),
                        **dict(zip(parameter_names, values)),
                    }
                    for values in itertools.product(
                        *(source_axes[name] for name in parameter_names)
                    )
                ]
                if parameter_names
                else [dict(capture.get("parameters", {}))]
            )
            host_gain_class = (binding.get("runtimeMapping") or {}).get(
                "hostGainClass"
            )
            if host_gain_class not in {"engineEvent", "effectEvent"}:
                raise ProfileRecipeError(
                    f"effect source {source['sourceGuid']} has no exact host gain class"
                )
            for parameters in capture_states:
                nodes.append(
                    {
                        "parameters": parameters,
                        "lifetime": source["lifetime"],
                        # Every effect PCM node is a deterministic target-only
                        # full-event contribution.  This keeps a creation/gear
                        # transient from carrying a second engine bed and also
                        # proves every random/playlist branch independently.
                        "requiredSourceGuid": source["sourceGuid"],
                        "requiredAuthoredBindingKey": binding[
                            "authoredBindingKey"
                        ],
                        "requiredDiagnosticName": source[
                            "diagnosticNameNotUsedForClassification"
                        ],
                        "hostGainClass": host_gain_class,
                        "sourceBindings": [binding],
                        "durationFrames": capture["durationFrames"],
                        "warmupFrames": capture["warmupFrames"],
                        "temporaryAssetName": _effect_node_asset(
                            suffix,
                            parameters,
                            source["sourceGuid"],
                        ),
                    }
                )
        continuous_by_group: dict[str, list[dict[str, Any]]] = {}
        for variant in variant_contract:
            if variant["lifetime"] != "continuous":
                continue
            scheduler = variant["schedulingGroup"]
            if not isinstance(scheduler, Mapping):
                continue
            continuous_by_group.setdefault(str(scheduler.get("groupId")), []).append(
                variant
            )

        maximum_continuous_mapped_nodes = 0
        continuous_voices = 0
        for group in continuous_by_group.values():
            scheduler = group[0].get("schedulingGroup", {})
            count = [_effect_variant_corner_count(item) for item in group]
            if scheduler.get("composition") == "playlistAlternative":
                maximum_continuous_mapped_nodes += max(count, default=0)
                # A playlist chooses one source, but that live source needs
                # every current N-D interpolation corner as a PCM voice.
                continuous_voices += max(count, default=0)
            else:
                maximum_continuous_mapped_nodes += sum(count)
                # Independent simultaneous layers each own their current
                # corners; counting one voice per source underreports both
                # mixer voices and mapped PCM regions.
                continuous_voices += sum(count)
        one_shot_by_group: dict[str, list[dict[str, Any]]] = {}
        for variant in variant_contract:
            if variant["lifetime"] == "continuous":
                continue
            scheduler = variant["schedulingGroup"]
            if isinstance(scheduler, Mapping):
                one_shot_by_group.setdefault(str(scheduler.get("groupId")), []).append(
                    variant
                )
        def group_polyphony_policy(
            group_id: str, group: Sequence[Mapping[str, Any]]
        ) -> dict[str, Any]:
            """Retain group retrigger evidence without inventing a voice cap."""

            retriggers = sorted(
                {
                    str(lifecycle.get("retrigger") or "")
                    for variant in group
                    for lifecycle in (
                        (variant.get("runtimeMapping") or {}).get(
                            "semanticLifecycle", []
                        )
                        or []
                    )
                    if isinstance(lifecycle, Mapping)
                }
            )
            if not retriggers:
                # Keep an inspection/dry plan lossless even when a retained
                # finite binding has no original-bank lifecycle proof yet.
                # The group is explicitly non-executable; packing/promotion
                # still refuses it via runtimeMappingBlocked and this status.
                return {
                    "groupId": group_id,
                    "classification": "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
                    "fmodPolyphonyEvidence": "unavailableUntilExactLifecycleIsProven",
                    "retriggerEvidence": [],
                    "overlap": "unprovenNoRuntimeAdmission",
                    "status": "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
                }
            return {
                "groupId": group_id,
                "classification": "requiresGlobalFmodChannelArbitration",
                "fmodPolyphonyEvidence": "pending; Assetto Studio logical cap=2048 and software real budget=256 are not per-group caps",
                "retriggerEvidence": retriggers,
                "overlap": "retainEveryStartedVoiceUntilCapturedEnd",
            }

        one_shot_group_policies = {
            group_id: group_polyphony_policy(group_id, group)
            for group_id, group in sorted(one_shot_by_group.items())
        }
        perspective_resources = {
            perspective: _selected_perspective_effect_resources(variant_contract, perspective)
            for perspective in perspective_scope
        }
        # Compatibility fields describe a single update only, excluding prior
        # finite tails.  Exact selected-perspective accounting lives in
        # ``perspectiveResources`` and remains release-blocked until the
        # The global Assetto channel/lifecycle oracle supplies a real peak proof.
        maximum_simultaneous_voices = max(
            (
                int(value["continuous"]["maximumMmapPlaybackCornerVoices"])
                + int(value["finite"]["combinedOneDspUpdateUpperBound"]["logicalRingVoices"])
                for value in perspective_resources.values()
            ),
            default=0,
        )
        allocated_worst_case_voices = maximum_simultaneous_voices
        maximum_mapped_nodes = max(
            (
                int(value["continuous"]["maximumMappedSourceCorners"])
                + int(value["finite"]["maximumSourceCornerContributorsPerUpdate"])
                for value in perspective_resources.values()
            ),
            default=0,
        )
        allocated_worst_case_mapped_nodes = maximum_mapped_nodes
        effect_interpolation = {
            "schema": "byd-full-event-effect-interpolation-v1",
            "continuous": {
                "algorithm": "perSourceAxisAlignedMultilinear-v1",
                "axisSource": "sourceBinding.parameterAxes",
                "axisBounds": "clampToAuthoredEndpointThenBinarySearchLowerUpper",
                "cornerGainFormula": "rawNDimensionalMultilinearWeight",
                "duplicateCornerPolicy": "sumDuplicateAxesThenMapOneNodeOnce",
                "nodeIdentity": "requiredAuthoredBindingKeyPlusCanonicalParameters",
                "nodePlaybackRatio": 1.0,
                "maximumMappedNodesPerEvent": maximum_mapped_nodes,
                "mmapPolicy": "mapOnlyCurrentSourceCorners; unmapAfterSourceDeactivation",
                "lifecycle": "startOnSemanticTriggerUpdateParametersWhileActiveStopOnSemanticDeactivation",
            },
            "oneShot": {
                "algorithm": "perSourceAxisAlignedMultilinearFiniteRing-v2",
                "axisSource": "sourceBinding.parameterAxes",
                "axisBounds": "clampToAuthoredEndpointThenBinarySearchLowerUpper",
                "cornerGainFormula": "rawNDimensionalMultilinearWeight",
                "duplicateCornerPolicy": "sumDuplicateAxesThenMixOneFiniteRingContributorOnce",
                "nodeIdentity": "requiredAuthoredBindingKeyPlusCanonicalParameters",
                "selection": "chooseSchedulingGroupMembersThenMixEveryNonZeroCornerForEachSelectedMemberIntoOneLogicalGroupRing",
                "logicalVoice": {
                    "model": "onePreallocatedFiniteRingVoicePerSchedulingGroupInstance",
                    "materialization": "evaluateGroupAndMemberSelectionOnceThenAtomicallyMixWeightedFloat32OrFloat64StereoContributorsFromExactMappedNodes",
                    "pcm16Premix": "forbidden",
                    "tail": "retainMixedRingUntilEverySelectedCapturedContributorEnds",
                    "sourceCornerRegions": "audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; noAudioCallbackMmapAllocationLockOrPcm16PremixStorage",
                },
                "retrigger": {
                    "bySchedulingGroup": [
                        one_shot_group_policies[key]
                        for key in sorted(one_shot_group_policies)
                    ]
                },
                "finiteRepeat": "renderAndPlayExactlyCapturedFiniteDuration",
            },
            "schedulingGroupComposition": "sumIndependentSimultaneousGroups; alternativesOnlyWithinSameGroupId",
        }
        result.append(
            {
                "eventPath": event_path,
                "eventSuffix": suffix,
                "perspectives": perspective_scope,
                "sourceGuids": sorted({source["sourceGuid"] for source in sources}),
                "hostGainClasses": sorted(
                    {str(node["hostGainClass"]) for node in nodes}
                ),
                "lifetimes": lifetimes,
                "runtimeTriggers": sorted(
                    {
                        trigger
                        for binding in bindings
                        for trigger in (
                            binding.get("runtimeMapping", {}) or {}
                        ).get("triggers", [])
                    }
                ),
                "authoredStates": [
                    authored_states[key] for key in sorted(authored_states)
                ],
                "parameterAxes": normalized_parameter_axes,
                "maximumSimultaneousVoices": maximum_simultaneous_voices,
                "maximumSimultaneousVoicesMeaning": "knownOneDspUpdateAcrossOneSelectedPerspectiveExcludingPriorFiniteTails; not an FMOD semantic peak",
                "allocatedWorstCasePlaybackVoices": allocated_worst_case_voices,
                "maximumMappedNodes": maximum_mapped_nodes,
                "allocatedWorstCaseMappedNodes": allocated_worst_case_mapped_nodes,
                "perspectiveResources": perspective_resources,
                "oneShotPolyphonyPolicy": {
                    "schema": "byd-full-event-finite-channel-arbitration-v3",
                "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                "globalPeakStatus": "BLOCKED_PENDING_ASSETTO_PRIORITY_STEAL_VIRTUALIZATION_AND_RUNTIME_ARBITRATION_ORACLE",
                    "groups": [
                        one_shot_group_policies[key]
                        for key in sorted(one_shot_group_policies)
                    ],
                },
                "nodes": sorted(
                    nodes,
                    key=lambda item: (
                        canonical_json_bytes(item["parameters"]),
                        str(item["requiredSourceGuid"] or ""),
                    ),
                ),
                "capture": {
                    "mode": "sourceIsolatedFullEventFmodNrt",
                    "durationFrames": (
                        max(
                            int(node["durationFrames"])
                            for node in nodes
                        )
                        if "continuous" in lifetimes
                        else None
                    ),
                    "warmupFrames": (
                        max(int(node["warmupFrames"]) for node in nodes)
                        if "continuous" in lifetimes
                        else 0
                    ),
                    "variantCoverage": "everyScheduledSourceIdentity",
                },
                "runtimeMappingBlocked": any(
                    item["runtimeMapping"] is None
                    or (item["runtimeMapping"] or {}).get("hostGainClass")
                    not in {"engineEvent", "effectEvent"}
                    or not isinstance(item.get("schedulingGroup"), Mapping)
                    or item["schedulingGroup"].get("complete") is not True
                    or not isinstance(
                        (item.get("runtimeMapping") or {}).get("semanticLifecycle"),
                        list,
                    )
                    or not _finite_lifecycle_is_runtime_proven(
                        item.get("runtimeMapping") or {}
                    )
                    for item in variant_contract
                ),
                "effectInterpolationContract": effect_interpolation,
                # Every retained source keeps an explicit lifecycle, authored
                # parameter point, and semantic trigger mapping.  A null entry
                # is deliberately retained and blocks the release instead of
                # silently dropping the event.
                "runtimeLifecycleParameterVariantContract": {
                    "complete": all(
                        isinstance(item["runtimeMapping"], Mapping)
                        and item["runtimeMapping"].get("triggers")
                        and item["runtimeMapping"].get("hostGainClass")
                        in {"engineEvent", "effectEvent"}
                        and isinstance(
                            item["runtimeMapping"].get("semanticLifecycle"), list
                        )
                        and _finite_lifecycle_is_runtime_proven(
                            item["runtimeMapping"]
                        )
                        and isinstance(item["schedulingGroup"], Mapping)
                        and item["schedulingGroup"].get("complete") is True
                        for item in variant_contract
                    ),
                    "variants": variant_contract,
                },
            }
        )
    return result


def _validate_selected_perspective_resource_contract(
    effects: Sequence[Mapping[str, Any]]
) -> None:
    """Require a complete resource contract without imposing an arbitrary cap.

    A selected perspective owns continuous mmap corners and finite logical
    rings with different lifetimes.  Summing corner contributors as playback
    voices both overstates finite output voices and hides the real CPU/mmap
    cost.  The release oracle, not plan compilation, is responsible for
    proving the actual reachable peak and safety-policy overflow behavior.
    """

    for perspective in ("cabin", "exterior"):
        for event in effects:
            if perspective not in event["perspectives"]:
                continue
            resources = event.get("perspectiveResources", {}).get(perspective)
            if not isinstance(resources, Mapping):
                raise ProfileRecipeError(
                    f"{event.get('eventPath')} has no {perspective} resource contract"
                )
            continuous = resources.get("continuous")
            finite = resources.get("finite")
            if not isinstance(continuous, Mapping) or not isinstance(finite, Mapping):
                raise ProfileRecipeError(
                    f"{event.get('eventPath')} has malformed {perspective} resources"
                )
            for key in (
                "maximumMmapPlaybackCornerVoices",
                "maximumMappedSourceCorners",
            ):
                if int(continuous.get(key, -1)) < 0:
                    raise ProfileRecipeError(f"{event.get('eventPath')} has invalid {key}")
            for key in (
                "maximumSourceCornerContributorsPerUpdate",
            ):
                if int(finite.get(key, -1)) < 0:
                    raise ProfileRecipeError(f"{event.get('eventPath')} has invalid {key}")
            update_peak = finite.get("combinedOneDspUpdateUpperBound")
            if not isinstance(update_peak, Mapping) or int(
                update_peak.get("logicalRingVoices", -1)
            ) < 0:
                raise ProfileRecipeError(
                    f"{event.get('eventPath')} has no finite one-update logical-voice bound"
                )


def build_atlas_plan(recipe: Mapping[str, Any]) -> dict[str, Any]:
    audit = recipe.get("sourceConservationAudit", {})
    if audit.get("exactGuidSetEquality") is not True:
        raise ProfileRecipeError("source conservation must pass before atlas planning")
    perspectives: dict[str, Any] = {}
    extraction = {item["assetName"]: item for item in recipe["extraction"]["sources"]}
    bindings_by_event: dict[str, list[Mapping[str, Any]]] = {}
    for binding in audit["coreEventBindings"]:
        bindings_by_event.setdefault(str(binding["eventPath"]), []).append(binding)
    total_nodes = 0
    for perspective in ("cabin", "exterior"):
        program = recipe["programs"][perspective]
        event_path = program.get("eventPath")
        if not event_path:
            raise ProfileRecipeError(f"{perspective} engine event is absent")
        rpm_axis, authored_throttle_probes = _engine_axes(program)
        suffix = str(event_path).rsplit("/", 1)[-1]
        nodes = _initial_engine_nodes(suffix, rpm_axis)
        logical_channel_multiplicity = _engine_logical_channel_multiplicity(
            program, nodes
        )
        event_bindings = bindings_by_event.get(str(event_path), [])
        role_by_source_guid = {
            str(layer["sourceGuid"]): (
                str(layer["role"])
                if str(layer["role"]) in {"LOAD", "COAST"}
                else "UNAFFECTED"
            )
            for layer in program.get("layers", [])
        }
        total_nodes += len(nodes)
        perspectives[perspective] = {
            "eventPath": event_path,
            "hostParameterBindings": copy.deepcopy(
                program.get("hostParameterBindings", [])
            ),
            "initialAxes": {"rpm": rpm_axis, "throttle": [0.0, 1.0]},
            "initialNodes": nodes,
            "axes": {"rpm": list(rpm_axis), "throttle": [0.0, 1.0]},
            "nodes": copy.deepcopy(nodes),
            "logicalChannelMultiplicity": logical_channel_multiplicity,
            "mandatoryOracleProbes": {
                "rpmMidpoints": _midpoints(rpm_axis),
                "authoredThrottleKnots": authored_throttle_probes,
                "cellCenters": True,
            },
            "packedAssetName": f"engine_{perspective}_atlas.wav",
            "capture": {
                "mode": "fullEventFmodNrt",
                "durationFrames": CAPTURE_FRAMES,
                "warmupFrames": WARMUP_FRAMES,
                "pcmFormat": "stereo-pcm16-le-48000",
            },
            "engineModePrograms": _engine_mode_program_contract(
                program,
                event_bindings,
            ),
            "requiredSourceCoverage": [
                {
                    "sourceGuid": binding["sourceGuid"],
                    "diagnosticName": extraction[binding["assetName"]][
                        "diagnosticNameNotUsedForClassification"
                    ],
                    "engineProgramRole": role_by_source_guid[str(binding["sourceGuid"])],
                }
                for binding in sorted(
                    event_bindings,
                    # Engine-event one-shots are emitted in their own isolated
                    # lifecycle effect atlas; the continuous bed coverage must
                    # not demand that a warm capture include them.
                    key=lambda item: (
                        (item.get("runtimeMapping") or {}).get("kind")
                        != "engineLayer",
                        str(item["sourceGuid"]),
                    ),
                )
                if (binding.get("runtimeMapping") or {}).get("kind") == "engineLayer"
            ],
        }
    effects = _effect_event_plans(recipe)
    _validate_selected_perspective_resource_contract(effects)
    observed_voice_budget = _observed_fmod_voice_budget_evidence()
    plan_seed = {
        "schema": ATLAS_PLAN_SCHEMA,
        "bankSha256": recipe["bank"]["sha256"],
        "perspectives": perspectives,
        "effects": effects,
        "refinementGate": {
            "method": "adaptiveFullEventNrtOracle",
            "comparison": "phaseInvariantEnvelopeBandPitchGain",
            "rawPcmNrmseReleaseEligible": False,
            "maximumEnvelopeNormalizedRmsError": 0.08,
            "maximumBandEnergyErrorDb": 0.25,
            "maximumPitchErrorCents": 3.0,
            "maximumGainErrorDb": 0.25,
            "requireEveryProbeToPass": True,
            "onFailure": "insertProbeAsAnchorAndRepeat",
            "minimumCellWidth": {"rpm": 1.0, "throttle": 0.001},
            "unresolvedMinimumCellFailure": "BLOCK_RELEASE",
            # Refinement must remain genuinely adaptive, but its PCM growth
            # cannot be open-ended on a machine with a bounded release disk.
            # The cap is three times each authored grid, not a fidelity
            # approximation: exceeding it fails the oracle and preserves the
            # resumable evidence rather than dropping an unresolved cell.
            "adaptiveStoragePolicy": {
                "schema": "byd-full-event-adaptive-storage-policy-v1",
                "maximumIterations": 8,
                "maximumNodesPerPerspective": {
                    perspective: len(value["nodes"]) * 3
                    for perspective, value in perspectives.items()
                },
                "onNodeLimit": "BLOCK_RELEASE_KEEP_RESUMABLE_EVIDENCE",
                "nodePcmBytes": (
                    CAPTURE_FRAMES
                    * PCM_BYTES_PER_FRAME
                    * ENGINE_PROGRAM_CAPTURE_COUNT_PER_GRID_POINT
                ),
            },
            "dynamicTrajectoryOracle": {
                "required": True,
                "dspBlockFrames": 256,
                "minimumSlowRampFrames": 288_000,
                "minimumReleaseTailFrames": 96_000,
                "requiredScenarios": [
                    "load_slow_rpm_ramp",
                    "load_fast_rpm_ramp",
                    "coast_rpm_ramp",
                    "both_throttle_sweep",
                    "both_rpm_reversal",
                    "both_steady_hold",
                ],
                "maximumBoundaryDiscontinuityExcessLinear": 0.08,
            },
            "combinedEngineEffectMixOracle": {
                "required": True,
                "requiredCoverage": "everyExecutableEffectSchedulingGroup",
                "minimumReleaseTailFrames": 96_000,
                "hostMixContract": "byd-full-event-atlas-host-mix-v1",
                "unprovenBehavior": "BLOCK_RELEASE",
            },
            "globalFmodChannelArbitrationOracle": {
                "required": True,
                "schema": FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA,
                "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                "rendererInitializationOrder": [
                    "FMOD_Studio_System_Create",
                    "FMOD_Studio_System_GetLowLevelSystem",
                    "FMOD_System_SetSoftwareChannels(256)",
                    "FMOD_Studio_System_Initialize(2048)",
                ],
                "requiredScenarios": [
                    "assettoDefaultCombinedEngineAndEffects",
                    "assettoAggressiveCombinedRpmThrottleTurboShift",
                    "assettoTwoHundredFiftySeventhRealVoiceContention",
                ],
                "requiredEvidence": [
                    "perDspBlockLogicalAndRealChannelSnapshots",
                    "callbackTracePerScheduledEventInstance",
                    "rawSourceGuidMultiplicityReconcilesWithEveryPremixedEngineAndFiniteComposite",
                    "causalFiniteTailOverlapPeakForEverySupportedFamilyPerspectiveScenario",
                    "observed257thRealVoiceArbitrationOutcome",
                    "runtimeArbitrationPolicyMatchesObservedOutcome",
                ],
                "premixAdmissionParity": {
                    "requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget": True,
                    "realBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                    "scenarioDemand": "continuousRawSourcesPlusEveryCausallyLiveFiniteTailSource",
                    "onExceeded": "BLOCK_RELEASE_REQUIRE_SOURCE_STEMS_FOR_PER_SOURCE_PRIORITY_AUDIBILITY_AND_VIRTUALIZATION",
                    "scalarOnlyProofIsSufficient": False,
                },
                "observedVoiceBudgetOracle": observed_voice_budget,
                "unprovenBehavior": "BLOCK_RELEASE",
            },
        },
        "reconstruction": {
            "algorithm": ATLAS_RECONSTRUCTION,
            "sampleInterpolation": "cubicCatmullRom",
            "varispeedRatio": "nodeRpm<=0?1.0:clamp(targetRpm/nodeRpm,0.1,4.0)",
            "phaseStart": "loopStartFramePlusPhaseOffsetFrames",
            "phaseAlignment": "correlationAlignedLoopPhase",
            "cellMix": "rawBilinearConstantAmplitude",
            "duplicateEndpointCorners": "sampleAndAdvanceOnce",
        },
        "interpolationContract": {
            "algorithm": ATLAS_RECONSTRUCTION,
            "nodeRootRpmPlaybackRatio": {
                "formula": "targetRpm/nodeRpm",
                "zeroRootRatio": 1.0,
                "minimum": 0.1,
                "maximum": 4.0,
            },
            "phaseAlignment": "correlationAlignedLoopPhase",
            "phaseReference": "targetRpmNormalizedProgress",
            "crossfade": "none; raw bilinear gains after zero-gain preparation",
            "activation": {
                "prepareOnlyAtZeroWeight": True,
                "mappedCellCorners": "allUniqueLowerUpperRpmByLowerUpperThrottleIncludingZeroWeightNeighbors",
                "zeroWeightNeighborPhasePolicy": "correlationAlignAtCellCreationAndAdvanceEveryOutputFrame",
                "gainFormula": "rawBilinearWeight",
                "audibleRamp": "none",
                "unreadyPolicy": "holdPreviousReadyCell",
                "neverMapMoreThanNodes": 4,
            },
            "correlation": {
                "channelScore": "sumStereoDotProducts",
                "windowFrames": 960,
                "searchOffsetFrames": 960,
                "candidateAnchor": "loopStartPlusPhaseOffsetFrames",
                "minimumRmsLinear": 0.001,
                "tieBreak": "smallestAbsoluteOffsetThenNegative",
                "coarseOffsetStrideFrames": 8,
                "coarseReferenceFrameStride": 4,
                "fineSearchHalfWidthFrames": 8,
                "fineReferenceFrameStride": 1,
                "offsetIteration": "ascendingInclusive",
                "coldStart": "highestWeightCornerReferenceThenAlignEveryMappedCornerIncludingZeroWeight",
            },
        },
        "hostMixContract": {
            "schema": "byd-full-event-atlas-host-mix-v1",
            "engineEventHostGainLinear": 0.5,
            "effectEventHostGainLinear": 1.0,
            "hostGainClasses": {
                "engineEvent": {
                    "gainLinear": 0.5,
                    "appliesTo": "continuousEngineBedAndFiniteSourcesInsideSameEngineEventInstance",
                },
                "effectEvent": {
                    "gainLinear": 1.0,
                    "appliesTo": "separatelyStartedNonEngineEffectEventInstances",
                },
            },
            "engineProgramGainContract": {
                "schema": "byd-full-event-engine-program-gain-v4",
                "normalUnityRepresentation": {
                    "formula": "directFULLProgram",
                    "loadGain": 1.0,
                    "coastGain": 1.0,
                    "requiredOracle": "fullEventOriginalBankNormalNormalComparison",
                    "claim": "exactFMODParityAtNormalNormalOnly",
                },
                "programSelection": {
                    "BOTH": "directFULLProgramAtLivePedalThrottle",
                    "LOAD": "directLOAD_ONLYProgramAtForcedThrottleOne",
                    "COAST": "directCOAST_ONLYProgramAtForcedThrottleZero",
                },
                "sampleCombination": "neverAddOrSubtractIndependentPrograms",
                "customTrim": {
                    "LOAD": "postMasterScaleSelectedLOAD_ONLYProgramByLoadGain",
                    "COAST": "postMasterScaleSelectedCOAST_ONLYProgramByCoastGain",
                    "BOTH": "postMasterScaleFULLByLiveThrottleWeightedLoadAndCoastGain",
                    "limitation": "sharedFmodDspMakesArbitraryPostMixPerSourceGainMathematicallyUnavailable",
                },
                "requiredProof": {
                    "independentState": "freshFmodSystemAndEventInstancePerProgram",
                    "roleIsolation": "oppositeRoleRoutesMutedBeforeWarmupAndThroughoutCapture",
                    "normalUnityIdentity": "BOTHAtUnityUsesUnmodifiedFullEventProgramBeforeCommonHostMix",
                    "customScope": "postMasterTrimDoesNotClaimNonUnityInternalFmodSourceParity",
                    "sourceRoleCoverage": "everyContinuousAndEngineEventTransientContributorIsLOADCOASTOrUnaffectedWithExactAuthoredBindingEvidence",
                },
                "engineEventTransientPolicy": {
                    "LOAD": "multiplyByLoadGainBeforeEngineEventHostGain",
                    "COAST": "multiplyByCoastGainBeforeEngineEventHostGain",
                    "unaffected": "multiplyByOneBeforeEngineEventHostGain",
                    "evidence": "sourceRoleClassifierAndModeProgramOracleMustAgree",
                },
                "appliesBeforeHostGainTo": [
                    "continuousEngineBed",
                    "engineEventTransient",
                ],
                "doesNotApplyTo": "effectEventContributions",
                "stage": "beforeEngineEvent0.5HostGainAndBeforeCommonPostSumLimiter",
            },
            "postSumMaster": {
                "algorithm": "stereoLinkedCausalPeakLimiter-v1",
                "ceilingLinear": 0.98,
                "lookaheadFrames": 0,
                "outputDelayFrames": 0,
                "preRoll": "none",
                "blockState": "continuousAcrossRenderBlocks",
                "stopTail": "none",
                "attackFrames": 1,
                "releaseFrames": 4_800,
                "releaseStepPerFrame": "(1.0-currentGain)/releaseFrames",
                "detector": "maxAbsoluteStereoSampleCurrentFrame",
                "targetGain": "min(1.0,ceilingLinear/detectorPeak)",
                "gainSmoothing": "attackImmediateReleaseLinearTowardOne",
            },
            "requiresCombinedEngineEffectMixOracle": True,
        },
    }
    family_id = f"atlas_{str(recipe['bank']['sha256'])[:24]}"
    engine_pcm_bytes = (
        total_nodes
        * ENGINE_PROGRAM_CAPTURE_COUNT_PER_GRID_POINT
        * CAPTURE_FRAMES
        * PCM_BYTES_PER_FRAME
    )
    effect_pcm_bytes = sum(
        int(node["durationFrames"]) * PCM_BYTES_PER_FRAME
        for event in plan_seed["effects"]
        for node in event["nodes"]
    )
    initial_pcm_bytes = engine_pcm_bytes + effect_pcm_bytes
    # The staging directory holds source nodes, pack output, and atomic resume
    # generations concurrently.  This intentionally excludes future adaptive
    # anchors, which are reported separately rather than hidden in a fake exact
    # estimate.
    staging_bytes = initial_pcm_bytes * 3 + 128 * 1024 * 1024
    result = {
        **plan_seed,
        "id": family_id,
        "sourceRecipeSha256": recipe["recipeSha256"],
        "sourceConservationEvidence": {
            "keptOutsideRuntimeCatalog": True,
            "coreSourceGuidCount": len(audit["coreReachableSourceGuids"]),
            "exactGuidSetEquality": True,
        },
        "initialEngineGridPointCount": total_nodes,
        "initialEngineNodeCount": (
            total_nodes * ENGINE_PROGRAM_CAPTURE_COUNT_PER_GRID_POINT
        ),
        "initialEnginePcmBytes": engine_pcm_bytes,
        "initialEffectPcmBytes": effect_pcm_bytes,
        "initialTotalPcmBytes": initial_pcm_bytes,
        "initialStagingBytesEstimate": staging_bytes,
        "diskEstimate": {
            "basis": "PCM16Stereo48kPerCapturedNodePlusPackAndResumeCopies",
            "initialNodePcmBytes": initial_pcm_bytes,
            "initialStagingBytesEstimate": staging_bytes,
            "adaptiveAnchorAndProbeBytes": "additional; reported from resumable oracle state",
        },
        "packedStorage": {
            "engineFiles": 2,
            "enginePcmRegionsPerGridPoint": ENGINE_PROGRAM_CAPTURE_COUNT_PER_GRID_POINT,
            "effectFiles": 1,
            "effectsPackedAssetName": "effects_atlas.wav",
            "deleteTemporaryNodeWavsAfterHashVerifiedPacking": True,
        },
        "releaseGate": {
            "status": "BLOCKED",
            "reason": "adaptive full-event atlas oracle has not run",
        },
    }
    return refresh_plan_sha256(result)


def build_runtime_index_template(plan: Mapping[str, Any]) -> dict[str, Any]:
    per_perspective_bounds: dict[str, dict[str, Any]] = {}
    for perspective in ("cabin", "exterior"):
        engine_multiplicity = plan["perspectives"][perspective].get(
            "logicalChannelMultiplicity"
        )
        if (
            not isinstance(engine_multiplicity, Mapping)
            or engine_multiplicity.get("schema")
            != "byd-full-event-engine-logical-channel-multiplicity-v1"
            or not isinstance(
                engine_multiplicity.get("maximumLogicalSourceChannelsAtAtlasNode"),
                int,
            )
        ):
            raise ProfileRecipeError(
                f"{perspective} has no raw FMOD engine channel multiplicity proof"
            )
        scoped_effects = [
            event
            for event in plan["effects"]
            if perspective in event["perspectives"]
        ]
        scoped_resources = [
            event["perspectiveResources"][perspective] for event in scoped_effects
        ]
        continuous_mmap_voices = sum(
            int(item["continuous"]["maximumMmapPlaybackCornerVoices"])
            for item in scoped_resources
        )
        continuous_mapped_corners = sum(
            int(item["continuous"]["maximumMappedSourceCorners"])
            for item in scoped_resources
        )
        finite_update_ring_voices = sum(
            int(item["finite"]["combinedOneDspUpdateUpperBound"]["logicalRingVoices"])
            for item in scoped_resources
        )
        finite_initial_ring_voices = sum(
            int(item["finite"]["initialPlacementEntryPeak"]["logicalRingVoices"])
            for item in scoped_resources
        )
        finite_initial_contributors = sum(
            int(item["finite"]["initialPlacementEntryPeak"]["sourceCornerContributors"])
            for item in scoped_resources
        )
        finite_update_contributors = sum(
            int(item["finite"]["maximumSourceCornerContributorsPerUpdate"])
            for item in scoped_resources
        )
        effect_fmod_continuous_channels = sum(
            int(item["continuous"]["maximumFmodLogicalSourceChannels"])
            for item in scoped_resources
        )
        effect_fmod_finite_channels = sum(
            int(item["finite"]["maximumFmodLogicalSourceChannelsPerUpdate"])
            for item in scoped_resources
        )
        finite_attack_cache_bytes = sum(
            int(item["finite"]["finiteAttackCacheBytes"])
            for item in scoped_resources
        )
        for item in scoped_resources:
            ring_contract = item["finite"].get("finiteRingPoolContract")
            if (
                not isinstance(ring_contract, Mapping)
                or ring_contract.get("schema")
                != "byd-full-event-finite-ring-pool-v1"
                or ring_contract.get("status")
                != "BLOCKED_PENDING_PER_FAMILY_PERSPECTIVE_CAUSAL_TAIL_PEAK"
                or item["finite"].get("finiteRingPoolBytes") is not None
            ):
                raise ProfileRecipeError(
                    f"{perspective} effect resource has no unproved finite ring-pool contract"
                )
        effect_voices = continuous_mmap_voices + finite_update_ring_voices
        effect_nodes = continuous_mapped_corners + finite_update_contributors
        per_perspective_bounds[perspective] = {
            "selection": "selectedEnginePerspectiveWithSessionRetainedCabinAndExteriorEffects",
            "engine": {
                "maximumMappedNodes": 4,
                "mappedNodeMeaning": "PCM node regions; not open mmap files",
                "maximumPlaybackVoices": 1,
                "maximumFmodLogicalSourceChannelsAtAtlasNode": engine_multiplicity[
                    "maximumLogicalSourceChannelsAtAtlasNode"
                ],
                "fmodLogicalChannelMultiplicity": {
                    "schema": engine_multiplicity["schema"],
                    "scope": engine_multiplicity["scope"],
                    "maximumLogicalSourceChannelsAtAtlasNode": engine_multiplicity[
                        "maximumLogicalSourceChannelsAtAtlasNode"
                    ],
                    "compositeCreationOrder": copy.deepcopy(
                        engine_multiplicity["compositeCreationOrder"]
                    ),
                    "automaticPlacementResolution": copy.deepcopy(
                        engine_multiplicity["automaticPlacementResolution"]
                    ),
                    "fullCellEvidence": "plan.logicalChannelMultiplicity.cells; excluded from runtime index",
                },
                "androidPremixedBedIsNotFmodChannelAccounting": True,
                "maximumMappedShardInstancesDuringCellTransition": None,
                "requiresPackedNodeShardProof": True,
            },
            "effects": {
                "semanticSimultaneousEventCount": len(scoped_effects),
                "resourceModel": "profileSessionRetainedEffectsResourceBounds-v3",
                "maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails": effect_voices,
                "maximumContinuousMmapPlaybackCornerVoices": continuous_mmap_voices,
                "maximumFiniteLogicalRingVoicesPerOneDspUpdate": finite_update_ring_voices,
                "initialPlacementEntryPeak": {
                    "finiteLogicalRingVoices": finite_initial_ring_voices,
                    "finiteSourceCornerContributors": finite_initial_contributors,
                },
                "outsideToInsideEntryPeak": {
                    "finiteLogicalRingVoices": finite_update_ring_voices,
                    "finiteSourceCornerContributors": finite_update_contributors,
                },
                "maximumMappedNodesPerUpdate": effect_nodes,
                "maximumSourceCornerRegionsDuringMaterialization": effect_nodes,
                "maximumContinuousMappedSourceCorners": continuous_mapped_corners,
                "maximumFiniteSourceCornerContributorsPerUpdate": finite_update_contributors,
                "maximumFiniteMappedSourceCornerRegionsDuringMaterialization": finite_update_contributors,
                "maximumFmodContinuousSourceChannels": effect_fmod_continuous_channels,
                "maximumFmodFiniteSourceChannelsPerOneDspUpdate": effect_fmod_finite_channels,
                "finiteAttackCacheBytes": finite_attack_cache_bytes,
                "finiteAttackCacheMeaning": "sum(min(nodeFrames,4096)*stereoPcm16BytesPerFrame)ForEveryFiniteNodePrearmedInSelectedPerspective",
                "finiteRingPoolBytes": None,
                "finiteRingPoolStatus": "BLOCKED_PENDING_PER_FAMILY_PERSPECTIVE_CAUSAL_TAIL_PEAK",
                "finiteRingPoolFormula": "sum(physicalLiveLogicalRingInstancesBySchedulingGroup[groupId]*streamingRingBufferFrames[groupId]*8)",
                "mappedNodeMeaning": "source PCM regions; finite regions are released after ring materialization",
                "peakProofStatus": "BLOCKED_PENDING_ASSETTO_2048_LOGICAL_256_REAL_CHANNEL_PRIORITY_STEAL_VIRTUALIZATION_AND_RUNTIME_ARBITRATION_ORACLE",
                # The exact packed-node locations are finalized per pack; this
                # is the safe upper bound before those hashes exist.
                "maximumMappedShardInstancesSafeUpperBound": effect_nodes,
            },
            "total": {
                "semanticSimultaneousEventCount": 1 + len(scoped_effects),
                "maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails": 1 + effect_voices,
                "maximumMappedNodesPerUpdate": 4 + effect_nodes,
                "maximumSourceCornerRegionsDuringMaterialization": 4 + effect_nodes,
                "maximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails": (
                    int(engine_multiplicity["maximumLogicalSourceChannelsAtAtlasNode"])
                    + effect_fmod_continuous_channels
                    + effect_fmod_finite_channels
                ),
                "fmodRawSourceAccounting": "engineContinuousSourcesPlusEffectContinuousSourcesPlusNewFiniteSources; priorFiniteTailsRequireGlobalArbitrationOracle",
                "peakProofStatus": "BLOCKED_PENDING_ASSETTO_2048_LOGICAL_256_REAL_CHANNEL_PRIORITY_STEAL_VIRTUALIZATION_AND_RUNTIME_ARBITRATION_ORACLE",
            },
        }
    return {
        "schema": ATLAS_RUNTIME_SCHEMA,
        "id": plan["id"],
        "draftBlocked": True,
        "planSha256": plan["planSha256"],
        "reconstruction": copy.deepcopy(plan["reconstruction"]),
        "interpolationContract": copy.deepcopy(plan["interpolationContract"]),
        "hostMixContract": copy.deepcopy(plan["hostMixContract"]),
        "modeRows": {
            "LOAD": {"throttle": 1.0, "livePedalIgnored": True},
            "COAST": {"throttle": 0.0, "livePedalIgnored": True},
            "BOTH": {"throttle": "livePedal"},
        },
        "hostParameterModeContract": {
            "schema": "byd-full-event-host-parameter-mode-v1",
            "bindings": {
                "EngineSimulation.throttle": {
                    "LOAD": 1.0,
                    "COAST": 0.0,
                    "BOTH": "livePedal",
                    "appliesTo": "everyRuntimeMappingHostBindingWhoseSourceIsEngineSimulation.throttleIncludingEngineEventTransients",
                }
            },
            "authoredStaticParameters": "neverOverriddenByModeRows; for example backfire throttle remains its retained authored 0.01",
        },
        "perspectives": {
            perspective: {
                "packedAssetName": value["packedAssetName"],
                "engineModePrograms": copy.deepcopy(
                    value["engineModePrograms"]
                ),
                "hostParameterBindings": copy.deepcopy(
                    value.get("hostParameterBindings", [])
                ),
                "rpmAxis": list(current_axes(value)["rpm"]),
                "throttleAxis": list(current_axes(value)["throttle"]),
                "nodes": [
                    {
                        "rpm": node["rpm"],
                        "throttle": node["throttle"],
                        "temporaryAssetName": node["temporaryAssetName"],
                        "phaseOffsetFrames": node.get("phaseOffsetFrames", 0.0),
                        "startFrame": None,
                        "endFrameExclusive": None,
                        "loopStartFrame": None,
                        "loopEndFrameExclusive": None,
                        "modeProgramTemporaryAssetNames": copy.deepcopy(
                            node["modeProgramTemporaryAssetNames"]
                        ),
                        "modePrograms": None,
                    }
                    for node in current_nodes(value)
                ],
            }
            for perspective, value in plan["perspectives"].items()
        },
        "hotCellPolicy": {
            "neighborSelection": "binarySearchLowerUpperOnSortedAxes",
            "maximumMappedLoopNodesPerPerspective": 4,
            "LOADOrCOASTMappedNodesPerPerspective": 2,
            "BOTHMappedNodesPerPerspective": 4,
            "cellReplacement": (
                "hold the previous ready cell until its leaving node is clamped to "
                "zero at/after the boundary; then prepare the entering node while "
                "its raw bilinear weight is zero"
            ),
            "wholeAtlasHeapDecodeForbidden": True,
            "packedWavAccess": "read-only mmap of PCM data chunk",
        },
        "resourceBounds": {
            "schema": "byd-full-event-atlas-runtime-resource-bounds-v3",
            "scope": "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
            "perPerspective": per_perspective_bounds,
            "session": {
                "mappingInstanceIdentity": "activationPerspectivePlusShardName",
                "retainedEffectPerspectives": ["cabin", "exterior"],
                "perSelectedEnginePerspective": {
                    perspective: {
                        "engineMaximumMappedShardInstancesDuringCellTransition": None,
                        "retainedCabinEffectsMaximumMappedShardInstances": None,
                        "retainedExteriorEffectsMaximumMappedShardInstances": None,
                        "maximumMappedShardInstancesDuringTransitionSafeUpperBound": None,
                    }
                    for perspective in ("cabin", "exterior")
                },
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound": None,
                "proofStatus": "BLOCKED_PENDING_SESSION_MAPPING_INSTANCE_PROOF",
            },
        },
        "effects": {
            "packedAssetName": "effects_atlas.wav",
            "resourceModel": "profileSessionRetainedEffectsResourceBounds-v3",
            "channelArbitration": {
                "schema": FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA,
                "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                "premixAdmissionParity": copy.deepcopy(
                    plan["refinementGate"]["globalFmodChannelArbitrationOracle"][
                        "premixAdmissionParity"
                    ]
                ),
                "status": "BLOCKED_PENDING_PER_FAMILY_PERSPECTIVE_RAW_SOURCE_CHANNEL_SNAPSHOT_RECONCILIATION",
            },
            "maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails": max(
                int(value["effects"]["maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails"])
                for value in per_perspective_bounds.values()
            ),
            "maximumMappedNodesPerUpdate": max(
                int(value["effects"]["maximumMappedNodesPerUpdate"])
                for value in per_perspective_bounds.values()
            ),
            "maximumSourceCornerRegionsDuringMaterialization": max(
                int(value["effects"]["maximumSourceCornerRegionsDuringMaterialization"])
                for value in per_perspective_bounds.values()
            ),
            "events": [
                {
                    "eventPath": event["eventPath"],
                    "eventSuffix": event["eventSuffix"],
                    "perspectives": event["perspectives"],
                    "runtimeTriggers": event["runtimeTriggers"],
                    "runtimeMappingBlocked": event["runtimeMappingBlocked"],
                    "hostGainClasses": event["hostGainClasses"],
                    "parameterAxes": copy.deepcopy(event["parameterAxes"]),
                    "maximumSimultaneousVoices": event["maximumSimultaneousVoices"],
                    "allocatedWorstCasePlaybackVoices": event[
                        "allocatedWorstCasePlaybackVoices"
                    ],
                    "maximumMappedNodes": event["maximumMappedNodes"],
                    "allocatedWorstCaseMappedNodes": event[
                        "allocatedWorstCaseMappedNodes"
                    ],
                    "perspectiveResources": copy.deepcopy(
                        event["perspectiveResources"]
                    ),
                    "oneShotPolyphonyPolicy": copy.deepcopy(
                        event["oneShotPolyphonyPolicy"]
                    ),
                    "effectInterpolationContract": copy.deepcopy(
                        event["effectInterpolationContract"]
                    ),
                    "runtimeLifecycleParameterVariantContract": copy.deepcopy(
                        event["runtimeLifecycleParameterVariantContract"]
                    ),
                    "nodes": [
                        {
                            "parameters": node["parameters"],
                            "lifetime": node["lifetime"],
                            "requiredSourceGuid": node.get("requiredSourceGuid"),
                            "requiredDiagnosticName": node.get("requiredDiagnosticName"),
                            "hostGainClass": node["hostGainClass"],
                            "sourceBindings": copy.deepcopy(node.get("sourceBindings", [])),
                            "temporaryAssetName": node["temporaryAssetName"],
                            "startFrame": None,
                            "endFrameExclusive": None,
                            "loopStartFrame": None,
                            "loopEndFrameExclusive": None,
                        }
                        for node in event["nodes"]
                    ],
                }
                for event in plan["effects"]
            ],
        },
    }


def _write_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(canonical_json_bytes(value) + b"\n")
    try:
        temporary_path.replace(path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-recipe", type=Path, required=True)
    parser.add_argument("--plan-output", type=Path, required=True)
    parser.add_argument("--runtime-index-template-output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        recipe = json.loads(args.source_recipe.read_text(encoding="utf-8"))
        plan = build_atlas_plan(recipe)
        _write_atomic(args.plan_output, plan)
        _write_atomic(
            args.runtime_index_template_output,
            build_runtime_index_template(plan),
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
