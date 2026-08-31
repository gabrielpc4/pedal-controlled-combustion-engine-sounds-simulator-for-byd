"""Fail-closed causal resource proof for one full-event atlas family.

This module deliberately contains no FMOD probing code.  It consumes exact per-DSP-block
observations produced by the Audio Lab renderer, validates them against the authored plan, and
derives the only resource bounds Android is allowed to allocate:

* physical live finite logical-ring instances per scheduling group;
* Float32 stereo ring-pool bytes from each group's fixed circular-ring size;
* currently mapped source regions plus old/new transition overlap;
* raw FMOD logical/real channels, including every source hidden by an Android premix;
* a lossless antichain of mapped-node transition sets for post-pack node-to-shard binding.

The proof accepts no scalar-only peak claim.  Every accepted ring has an exact start/end and exact
nonzero contributor-node identities, every callback voice has a reconciled source identity, every
snapshot is one contiguous 256-frame DSP boundary, and every required perspective/mode/trajectory
combination must drain all finite tails.  Virtual channels are accepted only when FMOD proves that
exact binding inaudible at that node and another required observation proves the same binding
real/audible (or a whole-placement oracle certifies it silent).  Demand beyond Assetto's channel
budgets is rejected rather than approximated.
"""

from __future__ import annotations

from collections import Counter, deque
import copy
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


PLAN_SCHEMA = "byd-full-event-atlas-plan-v3"
OBSERVATION_SCHEMA = "byd-full-event-causal-resource-observations-v1"
HOST_REACHABILITY_SCHEMA = "byd-full-event-host-control-reachability-v1"
HOST_STATE_GRAPH_SCHEMA = "byd-full-event-host-control-state-graph-v1"
HOST_WITNESS_BINDINGS_SCHEMA = "byd-full-event-host-control-witness-bindings-v1"
PROOF_SCHEMA = "byd-full-event-causal-resource-proof-v1"
PACKED_SHARD_PROOF_SCHEMA = "byd-full-event-causal-packed-shard-proof-v1"
RUNTIME_UPDATE_SCHEMA = "byd-full-event-causal-runtime-resource-update-v1"
DSP_BUFFER_FRAMES = 256
SAMPLE_RATE_HZ = 48_000
HOST_CONTROL_HZ = 200
HOST_TICK_FRAMES = SAMPLE_RATE_HZ // HOST_CONTROL_HZ
ASSETTO_LOGICAL_CHANNEL_CAP = 2_048
ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET = 256
STEREO_FLOAT32_BYTES_PER_FRAME = 8
PROGRAM_MODES = ("LOAD", "COAST", "BOTH")
PERSPECTIVES = ("cabin", "exterior")
VOICE_KINDS = {"engineContinuous", "effectContinuous", "effectFinite"}
AUXILIARY_TRAJECTORY_KINDS = frozenset({"source-audibility-probe"})
VIRTUAL_INAUDIBILITY_EPSILON = 1.0e-7
VIRTUALIZATION_PROOF_SCHEMA = "byd-fmod-virtual-voice-inaudibility-v1"
STATIC_SILENCE_PROOF_SCHEMA = "byd-authored-entire-placement-silence-v1"
SOURCE_SOLO_BINDING_ORACLE_SCHEMA = (
    "byd-original-bank-source-solo-binding-oracle-v1"
)
REALIZATION_BINDING_ORACLE_SCHEMA = "byd-realization-source-binding-oracle-v1"
_ActiveRingState = tuple[tuple[int, str], ...]
_ActiveSessionRingState = tuple[tuple[int, str, str], ...]


class CausalResourceProofError(ValueError):
    """Raised when evidence is incomplete, internally inconsistent, or over budget."""


@dataclass(frozen=True)
class _Node:
    key: str
    event_path: str
    source_guid: str
    authored_binding_key: str
    group_key: str
    lifetime: str
    duration_frames: int
    perspectives: frozenset[str]


@dataclass(frozen=True)
class _Group:
    key: str
    event_path: str
    group_id: str
    source_guids: frozenset[str]
    authored_binding_keys: frozenset[str]
    semantic_triggers: frozenset[str]
    perspectives: frozenset[str]
    maximum_contributors: Mapping[str, int]
    maximum_fmod_channels: Mapping[str, int]
    maximum_capture_frames: Mapping[str, int]
    streaming_ring_frames: Mapping[str, int]


@dataclass(frozen=True)
class _PlanContract:
    family_id: str
    plan_sha256: str
    nodes: Mapping[str, _Node]
    groups: Mapping[str, _Group]
    continuous_nodes: Mapping[str, frozenset[str]]
    finite_nodes: Mapping[str, frozenset[str]]
    continuous_region_bound: Mapping[str, int]
    continuous_fmod_channel_bound: Mapping[str, int]
    finite_materialization_bound: Mapping[str, int]
    engine_event_paths: Mapping[str, str]
    engine_source_guids: Mapping[str, frozenset[str]]
    engine_fmod_channel_bound: Mapping[str, int]


@dataclass(frozen=True)
class _Voice:
    token: str
    kind: str
    source_guid: str
    event_path: str
    ring_id: str | None
    authored_binding_key: str | None
    activation_perspective: str | None
    is_virtual: bool
    audibility: float
    authored_route_gain: float
    statically_silent: bool


@dataclass(frozen=True)
class _HostTransition:
    target_state: str
    emissions: frozenset[tuple[str, str]]


@dataclass(frozen=True)
class _HostMachine:
    initial_states: tuple[tuple[str, frozenset[tuple[str, str]]], ...]
    transitions: Mapping[str, tuple[_HostTransition, ...]]
    state_contexts: Mapping[str, tuple[str, str]]
    host_values: Mapping[str, Mapping[str, float]]


@dataclass(frozen=True)
class _HostSessionGroup:
    group_key: str
    owner: str
    binding_indexes: tuple[int, ...]


@dataclass(frozen=True)
class _HostSessionMachine:
    initial_states: tuple[tuple[str, frozenset[tuple[str, str]]], ...]
    state_contexts: Mapping[str, tuple[str, str]]
    memberships: Mapping[str, tuple[bool, ...]]
    host_values: Mapping[str, Mapping[str, float]]
    groups: tuple[_HostSessionGroup, ...]


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def effect_node_key(
    event_path: str,
    source_guid: str,
    authored_binding_key: str,
    parameters: Mapping[str, Any],
) -> str:
    """Stable pre/post-pack identity for one exact effect capture node."""

    identity = {
        "eventPath": str(event_path),
        "sourceGuid": _guid(source_guid),
        "authoredBindingKey": _authored_binding_key(authored_binding_key),
        "parameters": dict(parameters),
    }
    return "effect-node:" + hashlib.sha256(canonical_json_bytes(identity)).hexdigest()


def scheduling_group_key(event_path: str, group_id: str) -> str:
    identity = {
        "eventPath": _strict_string(event_path, "scheduling-group event path"),
        "groupId": _strict_string(group_id, "authored scheduling-group id"),
    }

    return "scheduling-group:" + hashlib.sha256(canonical_json_bytes(identity)).hexdigest()


def prove_causal_full_event_resources(
    plan: Mapping[str, Any],
    observations: Mapping[str, Any],
    *,
    required_trajectory_kinds: Sequence[str],
    implementation_source_root: Path,
) -> dict[str, Any]:
    """Validate exact causal snapshots and return a release-grade source-region proof."""

    contract = _plan_contract(plan)
    _require(observations.get("schema") == OBSERVATION_SCHEMA, "observation schema differs")
    _require(observations.get("atlasFamilyId") == contract.family_id, "observation family differs")
    _require(observations.get("planSha256") == contract.plan_sha256, "observation plan hash differs")
    _require(
        observations.get("dspBufferFrames") == DSP_BUFFER_FRAMES
        and observations.get("sampleRateHz") == SAMPLE_RATE_HZ,
        "causal observation does not use the exact 48 kHz/256-frame runtime boundary",
    )
    _require(
        observations.get("hostControlHz") == HOST_CONTROL_HZ
        and observations.get("hostTickFrames") == HOST_TICK_FRAMES,
        "causal observation does not use the exact 200 Hz/240-frame host-control cadence",
    )
    _require(
        observations.get("assettoStudioLogicalChannelCap") == ASSETTO_LOGICAL_CHANNEL_CAP
        and observations.get("assettoSoftwareRealChannelBudget")
        == ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        "causal observation does not use Assetto's 2048-logical/256-real configuration",
    )
    required_kinds = tuple(dict.fromkeys(str(value) for value in required_trajectory_kinds))
    _require(required_kinds and all(required_kinds), "causal trajectory requirement is empty")
    _require(len(required_kinds) == len(required_trajectory_kinds), "causal trajectory kinds repeat")
    scenarios = _sequence(observations.get("scenarios"), "observation scenarios")
    _require(scenarios, "causal observation has no scenarios")
    source_binding_oracles = _source_binding_oracle_registry(
        observations.get("sourceBindingOraclesBySha256")
    )
    scenario_ids: set[str] = set()
    covered_matrix: set[tuple[str, str, str]] = set()
    summaries: list[dict[str, Any]] = []
    per_perspective_accumulators = {
        perspective: _PerspectiveAccumulator(contract, perspective)
        for perspective in PERSPECTIVES
    }
    for raw_scenario in scenarios:
        scenario = _mapping(raw_scenario, "causal scenario")
        identifier = _nonempty_string(scenario.get("id"), "causal scenario id")
        _require(identifier not in scenario_ids, f"duplicate causal scenario id {identifier}")
        scenario_ids.add(identifier)
        perspective = _choice(scenario.get("perspective"), PERSPECTIVES, f"{identifier} perspective")
        mode = _choice(scenario.get("programMode"), PROGRAM_MODES, f"{identifier} program mode")
        kind = _nonempty_string(scenario.get("trajectoryKind"), f"{identifier} trajectory kind")
        _require(
            kind in required_kinds or kind in AUXILIARY_TRAJECTORY_KINDS,
            f"{identifier} has an undeclared trajectory kind",
        )
        if kind in required_kinds:
            covered_matrix.add((perspective, mode, kind))
        summary = _evaluate_scenario(
            contract,
            per_perspective_accumulators[perspective],
            scenario,
            identifier=identifier,
            perspective=perspective,
            mode=mode,
            trajectory_kind=kind,
            source_binding_oracles=source_binding_oracles,
        )
        summaries.append(summary)

    expected_matrix = {
        (perspective, mode, kind)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
        for kind in required_kinds
    }
    missing_matrix = sorted(expected_matrix - covered_matrix)
    _require(not missing_matrix, f"causal scenario matrix is incomplete: {missing_matrix}")
    used_source_binding_oracles = set().union(*(
        accumulator.source_binding_oracle_shas
        for accumulator in per_perspective_accumulators.values()
    ))
    _require(
        used_source_binding_oracles == set(source_binding_oracles),
        "source-binding oracle registry has unused or missing voice evidence",
    )
    computed_reachability = explore_host_control_reachability(
        plan,
        _mapping(
            observations.get("hostControlStateGraph"),
            "host-control state graph",
        ),
        _mapping(
            observations.get("hostControlWitnessBindings"),
            "host-control witness bindings",
        ),
        implementation_source_root=implementation_source_root,
    )
    supplied_reachability = _mapping(
        observations.get("hostControlReachability"),
        "host-control reachability proof",
    )
    _require(
        canonical_json_bytes(supplied_reachability)
        == canonical_json_bytes(computed_reachability),
        "host-control reachability differs from deterministic exploration",
    )
    reachability_summary = _validate_host_control_reachability(
        contract,
        supplied_reachability,
        summaries,
    )

    virtual_voice_identities = set().union(*(
        accumulator.virtual_voice_identities
        for accumulator in per_perspective_accumulators.values()
    ))
    statically_silent_voice_identities = set().union(*(
        accumulator.statically_silent_voice_identities
        for accumulator in per_perspective_accumulators.values()
    ))
    virtual_observation_nodes: dict[
        tuple[str, ...],
        set[tuple[str, int]],
    ] = {}
    real_audible_observation_nodes: dict[
        tuple[str, ...],
        set[tuple[str, int]],
    ] = {}
    for accumulator in per_perspective_accumulators.values():
        for identity, nodes in accumulator.virtual_voice_observation_nodes.items():
            virtual_observation_nodes.setdefault(identity, set()).update(nodes)
        for identity, nodes in (
            accumulator.real_audible_voice_observation_nodes.items()
        ):
            real_audible_observation_nodes.setdefault(identity, set()).update(nodes)
    unrepresented_virtual_voices = sorted(
        (
            identity,
            scenario_id,
            frame,
        )
        for identity, nodes in virtual_observation_nodes.items()
        if identity not in statically_silent_voice_identities
        for scenario_id, frame in nodes
        if not (
            real_audible_observation_nodes.get(identity, set())
            - {(scenario_id, frame)}
        )
    )
    _require(
        not unrepresented_virtual_voices,
        "virtual voice identities are never real/audible at another required node/probe "
        "and have no entire-placement silence certificate: "
        f"{unrepresented_virtual_voices}",
    )

    perspective_reports: dict[str, Any] = {}
    for perspective, accumulator in per_perspective_accumulators.items():
        session_contexts = [
            item
            for item in reachability_summary["sessionContexts"]
            if item["perspective"] == perspective
        ]
        _require(
            len(session_contexts) == len(PROGRAM_MODES)
            and accumulator.maximum_ring_pool_bytes
            == max(
                int(item["maximumReachableSessionRingPoolBytes"])
                for item in session_contexts
            )
            and accumulator.maximum_live_ring_total
            == max(
                int(item["maximumReachablePhysicalLiveRings"])
                for item in session_contexts
            )
            and accumulator.maximum_finite_effect_logical
            == max(
                int(item["maximumReachableFiniteFmodChannels"])
                for item in session_contexts
            )
            and accumulator.maximum_new_contributors
            == max(
                int(item["maximumReachableNewContributorsPerDspUpdate"])
                for item in session_contexts
            ),
            f"{perspective} measured resource peaks differ from the correlated global session proof",
        )
        expected_finite = frozenset().union(*contract.finite_nodes.values())
        expected_continuous = contract.continuous_nodes[perspective]
        _require(
            accumulator.observed_finite_nodes == expected_finite,
            f"{perspective} finite contributor coverage differs: "
            f"missing={sorted(expected_finite - accumulator.observed_finite_nodes)} "
            f"extra={sorted(accumulator.observed_finite_nodes - expected_finite)}",
        )
        _require(
            accumulator.observed_continuous_nodes == expected_continuous,
            f"{perspective} continuous-node coverage differs: "
            f"missing={sorted(expected_continuous - accumulator.observed_continuous_nodes)} "
            f"extra={sorted(accumulator.observed_continuous_nodes - expected_continuous)}",
        )
        finite_groups = {
            group_key: group
            for group_key, group in contract.groups.items()
            if any(
                contract.nodes[key].lifetime != "continuous"
                for key in expected_finite
                if contract.nodes[key].group_key == group_key
            )
        }
        _require(
            accumulator.observed_groups == set(finite_groups),
            f"{perspective} finite scheduling-group coverage differs",
        )
        expected_engine_sources = contract.engine_source_guids[perspective]
        _require(
            accumulator.observed_engine_source_guids == expected_engine_sources,
            f"{perspective} engine source coverage differs: "
            f"missing={sorted(expected_engine_sources - accumulator.observed_engine_source_guids)} "
            f"extra={sorted(accumulator.observed_engine_source_guids - expected_engine_sources)}",
        )
        _require(
            expected_engine_sources
            <= (
                accumulator.real_audible_engine_source_guids
                | accumulator.statically_silent_engine_source_guids
            ),
            f"{perspective} engine sources are never real/audible and have no "
            "entire-placement silence certificate: "
            f"{sorted(expected_engine_sources - accumulator.real_audible_engine_source_guids - accumulator.statically_silent_engine_source_guids)}",
        )
        for group_key, group in finite_groups.items():
            expected_capture = max(group.maximum_capture_frames.values())
            _require(
                max(
                    (
                        capture
                        for (activation_perspective, candidate_group), capture
                        in accumulator.maximum_observed_capture_by_activation_and_group.items()
                        if candidate_group == group_key
                    ),
                    default=0,
                ) == expected_capture,
                f"{perspective} group {group_key} never exercised its maximum captured tail",
            )
        peak_counts = accumulator.peak_ring_counts_by_activation_and_group
        peak_counts_by_group = Counter(
            {
                group_key: sum(
                    count
                    for (activation_perspective, candidate_group), count
                    in peak_counts.items()
                    if candidate_group == group_key
                )
                for group_key in finite_groups
            }
        )
        ring_bytes = sum(
            count
            * finite_groups[group_key].streaming_ring_frames[activation_perspective]
            * STEREO_FLOAT32_BYTES_PER_FRAME
            for (activation_perspective, group_key), count in peak_counts.items()
        )
        _require(
            ring_bytes == accumulator.maximum_ring_pool_bytes,
            f"{perspective} peak ring matrix does not reproduce its session pool bytes",
        )
        _require(ring_bytes >= 0, f"{perspective} ring bytes overflowed")
        perspective_reports[perspective] = {
            "status": "PASS",
            "physicalLiveLogicalRingInstancesBySchedulingGroup": {
                key: peak_counts_by_group[key]
                for key in sorted(finite_groups)
            },
            "physicalLiveLogicalRingInstancesByActivationPerspectiveAndGroup": {
                activation_perspective: {
                    group_key: peak_counts.get((activation_perspective, group_key), 0)
                    for group_key in sorted(finite_groups)
                }
                for activation_perspective in PERSPECTIVES
            },
            "streamingRingBufferFramesBySchedulingGroup": {
                key: max(finite_groups[key].streaming_ring_frames.values())
                for key in sorted(finite_groups)
            },
            "streamingRingBufferFramesByActivationPerspectiveAndGroup": {
                activation_perspective: {
                    group_key: finite_groups[group_key].streaming_ring_frames.get(
                        activation_perspective,
                        0,
                    )
                    for group_key in sorted(finite_groups)
                }
                for activation_perspective in PERSPECTIVES
            },
            "finiteRingPoolBytesBySchedulingGroup": {
                key: (
                    sum(
                        peak_counts.get((activation_perspective, key), 0)
                        * finite_groups[key].streaming_ring_frames.get(
                            activation_perspective,
                            0,
                        )
                        * STEREO_FLOAT32_BYTES_PER_FRAME
                        for activation_perspective in PERSPECTIVES
                    )
                )
                for key in sorted(finite_groups)
            },
            "maximumPhysicalLiveLogicalRingInstances": accumulator.maximum_live_ring_total,
            "finiteRingPoolBytes": ring_bytes,
            "finiteRingPoolFormula": "sum(physicalLiveLogicalRingInstancesByActivationPerspectiveAndGroup[activationPerspective][groupKey]*streamingRingBufferFramesByActivationPerspectiveAndGroup[activationPerspective][groupKey]*8)",
            "maximumCausalMappedSourceRegions": accumulator.maximum_mapped_regions,
            "maximumCausalMappedSourceRegionsDuringTransition": accumulator.maximum_transition_regions,
            "maximalMappedRegionTransitionSets": [
                {
                    "setSha256": hashlib.sha256(canonical_json_bytes(sorted(values))).hexdigest(),
                    "nodeKeys": sorted(values),
                }
                for values in sorted(
                    accumulator.maximal_transition_sets,
                    key=lambda item: (len(item), sorted(item)),
                )
            ],
            "maximumRetainedEffectMappedNodeInstancesDuringCausalTransition": max(
                (len(values) for values in accumulator.maximal_session_transition_sets),
                default=0,
            ),
            "maximalSessionRetainedEffectMappedRegionTransitionSets": [
                {
                    "setSha256": hashlib.sha256(
                        canonical_json_bytes(
                            [
                                {
                                    "activationPerspective": activation_perspective,
                                    "nodeKey": node_key,
                                }
                                for activation_perspective, node_key in sorted(values)
                            ]
                        )
                    ).hexdigest(),
                    "mappingInstances": [
                        {
                            "activationPerspective": activation_perspective,
                            "nodeKey": node_key,
                        }
                        for activation_perspective, node_key in sorted(values)
                    ],
                }
                for values in sorted(
                    accumulator.maximal_session_transition_sets,
                    key=lambda item: (len(item), sorted(item)),
                )
            ],
            "maximumSystemLogicalChannels": accumulator.maximum_system_logical,
            "maximumSystemRealChannels": accumulator.maximum_system_real,
            "maximumEngineLogicalChannels": accumulator.maximum_engine_logical,
            "maximumEngineRealChannels": accumulator.maximum_engine_real,
            "maximumEffectLogicalChannels": accumulator.maximum_effect_logical,
            "maximumEffectRealChannels": accumulator.maximum_effect_real,
            "maximumFiniteEffectLogicalChannels": accumulator.maximum_finite_effect_logical,
            "maximumFiniteEffectRealChannels": accumulator.maximum_finite_effect_real,
            "maximumNewFiniteContributorRegionsPerDspUpdate": accumulator.maximum_new_contributors,
            "allObservedChannelsReal": not accumulator.virtual_voice_identities,
            "virtualVoiceIdentityCount": len(
                accumulator.virtual_voice_identities
            ),
            "realAudibleVoiceIdentityCount": len(
                accumulator.real_audible_voice_identities
            ),
            "engineSourceCoverage": sorted(
                accumulator.observed_engine_source_guids
            ),
            "engineRealAudibleSourceCoverage": sorted(
                accumulator.real_audible_engine_source_guids
            ),
            "engineStaticallySilentSourceCoverage": sorted(
                accumulator.statically_silent_engine_source_guids
            ),
            "finiteContributorNodeCoverage": sorted(accumulator.observed_finite_nodes),
            "continuousNodeCoverage": sorted(accumulator.observed_continuous_nodes),
        }

    result = {
        "schema": PROOF_SCHEMA,
        "status": "PASS",
        "atlasFamilyId": contract.family_id,
        "planSha256": contract.plan_sha256,
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "hostControlHz": HOST_CONTROL_HZ,
        "hostTickFrames": HOST_TICK_FRAMES,
        "assettoStudioLogicalChannelCap": ASSETTO_LOGICAL_CHANNEL_CAP,
        "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        "requiredTrajectoryKinds": list(required_kinds),
        "sourceBindingOracleCount": len(source_binding_oracles),
        "sourceBindingOracleRegistrySha256": hashlib.sha256(
            canonical_json_bytes(source_binding_oracles)
        ).hexdigest(),
        "acceptedAuxiliaryTrajectoryKinds": sorted(
            AUXILIARY_TRAJECTORY_KINDS
        ),
        "virtualVoicePolicy": {
            "schema": VIRTUALIZATION_PROOF_SCHEMA,
            "audibilityEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
            "authoredRouteGainEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
            "requiresRealAudibleIdentityAtAnotherObservationOrWholePlacementSilence": True,
        },
        "requiredPerspectiveProgramModeTrajectoryMatrix": [
            {"perspective": perspective, "programMode": mode, "trajectoryKind": kind}
            for perspective, mode, kind in sorted(expected_matrix)
        ],
        "hostControlReachability": reachability_summary,
        "scenarioCount": len(summaries),
        "scenarios": summaries,
        "perPerspective": perspective_reports,
    }
    return _with_self_hash(result)


def bind_causal_proof_to_packed_shards(
    plan: Mapping[str, Any],
    proof: Mapping[str, Any],
    node_to_shard: Mapping[str, str],
    *,
    engine_transition_mapping_instance_bounds: Mapping[str, int],
) -> dict[str, Any]:
    """Bind the lossless source-region antichain to actual packed shard identities."""

    contract = _plan_contract(plan)
    _require(proof.get("schema") == PROOF_SCHEMA and proof.get("status") == "PASS", "causal proof is not PASS")
    _validate_self_hash(proof, "causal proof")
    required_trajectories = set(
        _sequence(
            proof.get("requiredTrajectoryKinds"),
            "causal proof required trajectories",
        )
    )
    _require(
        {"host-control-peak", "camera-switch-tail"} <= required_trajectories,
        "runtime promotion lacks mandatory host-control and camera-switch-tail trajectories",
    )
    _require(
        proof.get("virtualVoicePolicy")
        == {
            "schema": VIRTUALIZATION_PROOF_SCHEMA,
            "audibilityEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
            "authoredRouteGainEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
            "requiresRealAudibleIdentityAtAnotherObservationOrWholePlacementSilence": True,
        },
        "runtime promotion lacks the exact virtual-voice inaudibility policy",
    )
    _require(
        proof.get("atlasFamilyId") == contract.family_id
        and proof.get("planSha256") == contract.plan_sha256,
        "causal proof does not match the supplied atlas plan",
    )
    per_perspective = _mapping(proof.get("perPerspective"), "causal per-perspective proof")
    packed: dict[str, Any] = {}
    retained_effect_maximum = {perspective: 0 for perspective in PERSPECTIVES}
    session_transition_hashes: set[str] = set()
    for perspective in PERSPECTIVES:
        report = _mapping(per_perspective.get(perspective), f"causal {perspective} proof")
        _require(
            set(_sequence(report.get("finiteContributorNodeCoverage"), f"{perspective} finite coverage"))
            == set(contract.finite_nodes[perspective])
            and set(_sequence(report.get("continuousNodeCoverage"), f"{perspective} continuous coverage"))
            == set(contract.continuous_nodes[perspective]),
            f"{perspective} causal node coverage does not match the plan",
        )
        engine_bound = _nonnegative_int(
            engine_transition_mapping_instance_bounds.get(perspective),
            f"{perspective} engine transition mapping-instance bound",
        )
        maximum = 0
        peak_set_hashes: list[str] = []
        for raw_set in _sequence(
            report.get("maximalMappedRegionTransitionSets"),
            f"{perspective} mapped transition sets",
        ):
            record = _mapping(raw_set, f"{perspective} mapped transition set")
            keys = tuple(
                _strict_string(value, f"{perspective} transition node key")
                for value in _sequence(record.get("nodeKeys"), f"{perspective} transition node keys")
            )
            _require(keys, f"{perspective} transition set is empty")
            _require(len(keys) == len(set(keys)), f"{perspective} transition set repeats a node")
            _require(
                set(keys) <= set(contract.nodes),
                f"{perspective} transition set contains a node outside the plan",
            )
            expected_set_sha256 = hashlib.sha256(
                canonical_json_bytes(sorted(keys))
            ).hexdigest()
            _require(
                record.get("setSha256") == expected_set_sha256,
                f"{perspective} transition-set hash differs",
            )
            try:
                shard_count = len({
                    _nonempty_string(node_to_shard[str(key)], f"packed shard for {key}")
                    for key in keys
                })
            except KeyError as error:
                raise CausalResourceProofError(
                    f"packed shard binding is missing causal node {error.args[0]}"
                ) from error
            if shard_count > maximum:
                maximum = shard_count
                peak_set_hashes = [str(record.get("setSha256"))]
            elif shard_count == maximum:
                peak_set_hashes.append(str(record.get("setSha256")))
        # A perspective with no effect PCM legitimately has no transition set.
        if int(report.get("maximumCausalMappedSourceRegionsDuringTransition", -1)) == 0:
            maximum = 0
            peak_set_hashes = []
        maximum_session_effect_instances = 0
        maximum_session_effect_instances_by_activation = {
            activation_perspective: 0
            for activation_perspective in PERSPECTIVES
        }
        session_peak_hashes: list[str] = []
        maximum_prepack_instances = 0
        for raw_set in _sequence(
            report.get("maximalSessionRetainedEffectMappedRegionTransitionSets"),
            f"{perspective} session-retained effect transition sets",
        ):
            record = _mapping(
                raw_set,
                f"{perspective} session-retained effect transition set",
            )
            instances = [
                _mapping(item, f"{perspective} retained effect mapping instance")
                for item in _sequence(
                    record.get("mappingInstances"),
                    f"{perspective} retained effect mapping instances",
                )
            ]
            parsed_instances = [
                (
                    _choice(
                        item.get("activationPerspective"),
                        PERSPECTIVES,
                        f"{perspective} retained effect activation perspective",
                    ),
                    _strict_string(
                        item.get("nodeKey"),
                        f"{perspective} retained effect node key",
                    ),
                )
                for item in instances
            ]
            _require(
                parsed_instances == sorted(parsed_instances)
                and len(parsed_instances) == len(set(parsed_instances))
                and all(
                    node_key in contract.nodes
                    and activation_perspective
                    in contract.nodes[node_key].perspectives
                    for activation_perspective, node_key in parsed_instances
                ),
                f"{perspective} retained effect transition contains duplicate/unknown participant nodes",
            )
            expected_hash = hashlib.sha256(
                canonical_json_bytes(
                    [
                        {
                            "activationPerspective": activation_perspective,
                            "nodeKey": node_key,
                        }
                        for activation_perspective, node_key in parsed_instances
                    ]
                )
            ).hexdigest()
            _require(
                record.get("setSha256") == expected_hash,
                f"{perspective} retained effect transition-set hash differs",
            )
            try:
                mapped_instances = {
                    (
                        activation_perspective,
                        _nonempty_string(
                            node_to_shard[node_key],
                            f"packed shard for {node_key}",
                        ),
                    )
                    for activation_perspective, node_key in parsed_instances
                }
            except KeyError as error:
                raise CausalResourceProofError(
                    f"packed shard binding is missing retained causal node {error.args[0]}"
                ) from error
            counts = Counter(
                activation_perspective
                for activation_perspective, _shard_name in mapped_instances
            )
            for activation_perspective in PERSPECTIVES:
                retained_effect_maximum[activation_perspective] = max(
                    retained_effect_maximum[activation_perspective],
                    counts[activation_perspective],
                )
                maximum_session_effect_instances_by_activation[
                    activation_perspective
                ] = max(
                    maximum_session_effect_instances_by_activation[
                        activation_perspective
                    ],
                    counts[activation_perspective],
                )
            if len(mapped_instances) > maximum_session_effect_instances:
                maximum_session_effect_instances = len(mapped_instances)
                session_peak_hashes = [expected_hash]
            elif len(mapped_instances) == maximum_session_effect_instances:
                session_peak_hashes.append(expected_hash)
            maximum_prepack_instances = max(
                maximum_prepack_instances,
                len(parsed_instances),
            )
            session_transition_hashes.add(expected_hash)
        _require(
            maximum_prepack_instances
            == _nonnegative_int(
                report.get(
                    "maximumRetainedEffectMappedNodeInstancesDuringCausalTransition"
                ),
                f"{perspective} retained prepack node-instance maximum",
            ),
            f"{perspective} retained prepack mapping-instance claim differs from its exact sets",
        )
        packed[perspective] = {
            "status": "PASS",
            "maximumSelectedPerspectiveEffectMappedShardInstancesDuringCausalTransition": maximum,
            "maximumSessionRetainedEffectMappedShardInstancesDuringCausalTransition": (
                maximum_session_effect_instances
            ),
            "maximumSessionRetainedEffectMappedShardInstancesByActivationPerspective": (
                maximum_session_effect_instances_by_activation
            ),
            "engineMaximumMappedShardInstancesDuringCellTransition": engine_bound,
            "peakTransitionSetSha256s": sorted(set(peak_set_hashes)),
            "sessionPeakTransitionSetSha256s": sorted(set(session_peak_hashes)),
        }
    session_by_selected_engine = {
        selected_perspective: {
            "engineMaximumMappedShardInstancesDuringCellTransition": (
                _nonnegative_int(
                    engine_transition_mapping_instance_bounds.get(selected_perspective),
                    f"{selected_perspective} engine mapping-instance bound",
                )
            ),
            "retainedCabinEffectsMaximumMappedShardInstances": (
                retained_effect_maximum["cabin"]
            ),
            "retainedExteriorEffectsMaximumMappedShardInstances": (
                retained_effect_maximum["exterior"]
            ),
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound": (
                _nonnegative_int(
                    engine_transition_mapping_instance_bounds.get(selected_perspective),
                    f"{selected_perspective} engine mapping-instance bound",
                )
                + retained_effect_maximum["cabin"]
                + retained_effect_maximum["exterior"]
            ),
        }
        for selected_perspective in PERSPECTIVES
    }
    result = {
        "schema": PACKED_SHARD_PROOF_SCHEMA,
        "status": "PASS",
        "atlasFamilyId": proof.get("atlasFamilyId"),
        "planSha256": proof.get("planSha256"),
        "perPerspective": packed,
        "session": {
            "mappingInstanceIdentity": "activationPerspectivePlusShardName",
            "retainedEffectPerspectives": list(PERSPECTIVES),
            "perSelectedEnginePerspective": session_by_selected_engine,
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound": max(
                value["maximumMappedShardInstancesDuringTransitionSafeUpperBound"]
                for value in session_by_selected_engine.values()
            ),
            "proofStatus": "PASS",
            "causalTransitionSetSha256s": sorted(session_transition_hashes),
        },
    }
    return _with_self_hash(result)


def causal_runtime_resource_updates(
    plan: Mapping[str, Any],
    proof: Mapping[str, Any],
    packed_shard_proof: Mapping[str, Any],
) -> dict[str, Any]:
    """Return exact fields the pack/batch promotion stage must copy into runtime resource v3."""

    contract = _plan_contract(plan)
    _require(proof.get("schema") == PROOF_SCHEMA and proof.get("status") == "PASS", "causal proof is not PASS")
    _validate_self_hash(proof, "causal proof")
    required_trajectories = set(
        _sequence(
            proof.get("requiredTrajectoryKinds"),
            "causal proof required trajectories",
        )
    )
    _require(
        {"host-control-peak", "camera-switch-tail"} <= required_trajectories,
        "runtime promotion lacks mandatory host-control and camera-switch-tail trajectories",
    )
    _require(
        proof.get("atlasFamilyId") == contract.family_id
        and proof.get("planSha256") == contract.plan_sha256,
        "causal proof does not match the supplied atlas plan",
    )
    _require(
        packed_shard_proof.get("schema") == PACKED_SHARD_PROOF_SCHEMA
        and packed_shard_proof.get("status") == "PASS"
        and packed_shard_proof.get("atlasFamilyId") == proof.get("atlasFamilyId")
        and packed_shard_proof.get("planSha256") == proof.get("planSha256"),
        "packed shard proof does not match the causal proof",
    )
    _validate_self_hash(packed_shard_proof, "packed shard proof")
    proof_perspectives = _mapping(proof.get("perPerspective"), "causal perspectives")
    shard_perspectives = _mapping(packed_shard_proof.get("perPerspective"), "packed perspectives")
    _require(
        set(shard_perspectives) == set(PERSPECTIVES),
        "packed perspective matrix differs",
    )
    packed_session = _mapping(
        packed_shard_proof.get("session"),
        "packed session mapping-instance proof",
    )
    _require(
        packed_session.get("mappingInstanceIdentity")
        == "activationPerspectivePlusShardName"
        and packed_session.get("retainedEffectPerspectives") == list(PERSPECTIVES)
        and packed_session.get("proofStatus") == "PASS",
        "packed session mapping-instance contract differs/is not PASS",
    )
    packed_session_by_selected = _mapping(
        packed_session.get("perSelectedEnginePerspective"),
        "packed session selected-engine perspectives",
    )
    _require(
        set(packed_session_by_selected) == set(PERSPECTIVES),
        "packed session selected-engine perspective matrix differs",
    )
    retained_effect_counts: dict[str, int] = {}
    recomputed_session_maximum = 0
    for selected_perspective in PERSPECTIVES:
        record = _mapping(
            packed_session_by_selected[selected_perspective],
            f"packed session {selected_perspective}",
        )
        _require(
            set(record)
            == {
                "engineMaximumMappedShardInstancesDuringCellTransition",
                "retainedCabinEffectsMaximumMappedShardInstances",
                "retainedExteriorEffectsMaximumMappedShardInstances",
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
            },
            f"packed session {selected_perspective} fields differ",
        )
        engine_count = _nonnegative_int(
            record["engineMaximumMappedShardInstancesDuringCellTransition"],
            f"packed session {selected_perspective} engine mappings",
        )
        cabin_count = _nonnegative_int(
            record["retainedCabinEffectsMaximumMappedShardInstances"],
            f"packed session {selected_perspective} cabin effect mappings",
        )
        exterior_count = _nonnegative_int(
            record["retainedExteriorEffectsMaximumMappedShardInstances"],
            f"packed session {selected_perspective} exterior effect mappings",
        )
        total = _nonnegative_int(
            record["maximumMappedShardInstancesDuringTransitionSafeUpperBound"],
            f"packed session {selected_perspective} total mappings",
        )
        _require(
            total == engine_count + cabin_count + exterior_count,
            f"packed session {selected_perspective} mapping-instance sum differs",
        )
        for activation_perspective, count in (
            ("cabin", cabin_count),
            ("exterior", exterior_count),
        ):
            prior = retained_effect_counts.setdefault(activation_perspective, count)
            _require(
                prior == count,
                "packed session retained-effect maximum changes with selected engine perspective",
            )
        recomputed_session_maximum = max(recomputed_session_maximum, total)
    _require(
        packed_session.get(
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound"
        ) == recomputed_session_maximum,
        "packed session global mapping-instance maximum differs",
    )
    updates: dict[str, Any] = {}
    channel_summary: dict[str, Any] = {}
    for perspective in PERSPECTIVES:
        causal = _mapping(proof_perspectives.get(perspective), f"causal {perspective}")
        _mapping(shard_perspectives.get(perspective), f"packed {perspective}")
        finite_groups = {
            group_key: group
            for group_key, group in contract.groups.items()
            if group.maximum_capture_frames
        }
        peak_map = _mapping(
            causal.get("physicalLiveLogicalRingInstancesBySchedulingGroup"),
            f"{perspective} ring peak map",
        )
        ring_frames = _mapping(
            causal.get("streamingRingBufferFramesBySchedulingGroup"),
            f"{perspective} ring-frame map",
        )
        bytes_by_group = _mapping(
            causal.get("finiteRingPoolBytesBySchedulingGroup"),
            f"{perspective} ring-byte map",
        )
        peak_by_activation = _mapping(
            causal.get(
                "physicalLiveLogicalRingInstancesByActivationPerspectiveAndGroup"
            ),
            f"{perspective} activation-perspective ring peak map",
        )
        frames_by_activation = _mapping(
            causal.get(
                "streamingRingBufferFramesByActivationPerspectiveAndGroup"
            ),
            f"{perspective} activation-perspective ring-frame map",
        )
        _require(
            set(peak_map) == set(finite_groups)
            and set(ring_frames) == set(finite_groups)
            and set(bytes_by_group) == set(finite_groups),
            f"{perspective} causal ring maps do not match the finite groups",
        )
        recomputed_ring_bytes = 0
        expected_peak_by_group: Counter[str] = Counter()
        expected_bytes_by_group: Counter[str] = Counter()
        _require(
            set(peak_by_activation) == set(PERSPECTIVES)
            and set(frames_by_activation) == set(PERSPECTIVES),
            f"{perspective} activation-perspective ring matrix differs",
        )
        for activation_perspective in PERSPECTIVES:
            activation_peaks = _mapping(
                peak_by_activation[activation_perspective],
                f"{perspective}/{activation_perspective} ring peaks",
            )
            activation_frames = _mapping(
                frames_by_activation[activation_perspective],
                f"{perspective}/{activation_perspective} ring frames",
            )
            _require(
                set(activation_peaks) == set(finite_groups)
                and set(activation_frames) == set(finite_groups),
                f"{perspective}/{activation_perspective} ring matrix does not cover every finite group",
            )
            for group_key, group in finite_groups.items():
                peak = _nonnegative_int(
                    activation_peaks[group_key],
                    f"{perspective}/{activation_perspective}/{group_key} ring peak",
                )
                expected_frames = group.streaming_ring_frames.get(
                    activation_perspective,
                    0,
                )
                _require(
                    activation_frames[group_key] == expected_frames,
                    f"{perspective}/{activation_perspective}/{group_key} streaming ring frames differ from the plan",
                )
                _require(
                    peak == 0 or expected_frames > 0,
                    f"{perspective}/{activation_perspective}/{group_key} activates an unavailable finite group",
                )
                expected_peak_by_group[group_key] += peak
                expected_bytes_by_group[group_key] += (
                    peak
                    * expected_frames
                    * STEREO_FLOAT32_BYTES_PER_FRAME
                )
        for group_id, group in finite_groups.items():
            peak = _nonnegative_int(peak_map[group_id], f"{perspective} {group_id} ring peak")
            expected_frames = max(group.streaming_ring_frames.values())
            _require(
                ring_frames[group_id] == expected_frames,
                f"{perspective} {group_id} streaming ring frames differ from the plan",
            )
            _require(
                peak == expected_peak_by_group[group_id],
                f"{perspective} {group_id} aggregate ring peak differs from its activation matrix",
            )
            expected_bytes = expected_bytes_by_group[group_id]
            _require(
                bytes_by_group[group_id] == expected_bytes,
                f"{perspective} {group_id} ring bytes differ from its exact peak",
            )
            recomputed_ring_bytes += expected_bytes
        _require(
            causal.get("finiteRingPoolBytes") == recomputed_ring_bytes,
            f"{perspective} finite ring pool byte sum differs",
        )
        maximum_logical = _nonnegative_int(
            causal.get("maximumSystemLogicalChannels"),
            f"{perspective} causal logical channels",
        )
        maximum_real = _nonnegative_int(
            causal.get("maximumSystemRealChannels"),
            f"{perspective} causal real channels",
        )
        virtual_identity_count = _nonnegative_int(
            causal.get("virtualVoiceIdentityCount"),
            f"{perspective} causal virtual identity count",
        )
        _require(
            causal.get("allObservedChannelsReal")
            is (virtual_identity_count == 0)
            and maximum_logical <= ASSETTO_LOGICAL_CHANNEL_CAP
            and maximum_real <= ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
            and maximum_real <= maximum_logical,
            f"{perspective} causal raw-channel peak cannot be represented by atomic premixes",
        )
        updates[perspective] = {
            "engine": {
                "maximumCausalFmodLogicalChannels": causal.get(
                    "maximumEngineLogicalChannels"
                ),
                "maximumCausalFmodRealChannels": causal.get(
                    "maximumEngineRealChannels"
                ),
                "stopAllowFadeoutOldAndNewActivationOverlapIncluded": True,
                "peakProofStatus": "PASS",
            },
            "effects": {
                "resourceModel": "profileSessionRetainedEffectsResourceBounds-v3",
                "physicalLiveLogicalRingInstancesBySchedulingGroup": dict(
                    _mapping(
                        causal.get("physicalLiveLogicalRingInstancesBySchedulingGroup"),
                        f"{perspective} ring instances",
                    )
                ),
                "physicalLiveLogicalRingInstancesByActivationPerspectiveAndGroup": {
                    activation_perspective: dict(
                        _mapping(
                            peak_by_activation[activation_perspective],
                            f"{perspective}/{activation_perspective} ring instances",
                        )
                    )
                    for activation_perspective in PERSPECTIVES
                },
                "maximumCausalFiniteLogicalRingVoices": causal.get(
                    "maximumPhysicalLiveLogicalRingInstances"
                ),
                "maximumCausalMappedSourceRegions": causal.get("maximumCausalMappedSourceRegions"),
                "maximumCausalMappedSourceRegionsDuringTransition": causal.get(
                    "maximumCausalMappedSourceRegionsDuringTransition"
                ),
                "maximumCausalFmodLogicalChannels": causal.get("maximumEffectLogicalChannels"),
                "maximumCausalFmodRealChannels": causal.get("maximumEffectRealChannels"),
                "finiteRingPoolBytes": causal.get("finiteRingPoolBytes"),
                "finiteRingPoolStatus": "PASS",
                "peakProofStatus": "PASS",
                "maximumMappedShardInstancesDuringCausalTransition": (
                    retained_effect_counts[perspective]
                ),
            },
            "total": {
                "maximumCausalFmodLogicalChannels": maximum_logical,
                "maximumCausalFmodRealChannels": maximum_real,
                "virtualVoiceIdentityCount": virtual_identity_count,
                "peakProofStatus": "PASS",
            },
        }
        channel_summary[perspective] = {
            "maximumLogicalChannels": causal.get("maximumSystemLogicalChannels"),
            "maximumRealChannels": causal.get("maximumSystemRealChannels"),
            "allObservedChannelsReal": causal.get("allObservedChannelsReal"),
            "virtualVoiceIdentityCount": virtual_identity_count,
        }
    result = {
        "schema": RUNTIME_UPDATE_SCHEMA,
        "status": "PASS",
        "atlasFamilyId": proof.get("atlasFamilyId"),
        "planSha256": proof.get("planSha256"),
        "causalResourceProofSha256": proof.get("proofSha256"),
        "packedShardProofSha256": packed_shard_proof.get("proofSha256"),
        "causalVerifierImplementationSha256": hashlib.sha256(
            Path(__file__).read_bytes()
        ).hexdigest(),
        "sessionStateGraphProducerImplementationSha256": hashlib.sha256(
            (Path(__file__).with_name("export_full_event_session_state_graph.py")).read_bytes()
        ).hexdigest(),
        "resourceBoundsSchema": "byd-full-event-atlas-runtime-resource-bounds-v3",
        "resourceBoundsScope": (
            "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects"
        ),
        "channelArbitration": {
            "status": "PASS",
            "assettoStudioLogicalChannelCap": ASSETTO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
            "requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget": True,
            "virtualVoicePolicy": proof.get("virtualVoicePolicy"),
            "perPerspectiveCausalPeak": channel_summary,
        },
        "sessionCommon": {
            "finiteRingPoolBytes": max(
                int(update["effects"]["finiteRingPoolBytes"])
                for update in updates.values()
            ),
            "maximumCausalFiniteLogicalRingVoices": max(
                int(update["effects"]["maximumCausalFiniteLogicalRingVoices"])
                for update in updates.values()
            ),
            "maximumCausalFmodLogicalChannels": max(
                int(update["total"]["maximumCausalFmodLogicalChannels"])
                for update in updates.values()
            ),
            "maximumCausalFmodRealChannels": max(
                int(update["total"]["maximumCausalFmodRealChannels"])
                for update in updates.values()
            ),
            "finiteRingPoolStatus": "PASS",
            "peakProofStatus": "PASS",
        },
        "session": {
            "mappingInstanceIdentity": "activationPerspectivePlusShardName",
            "retainedEffectPerspectives": list(PERSPECTIVES),
            "perSelectedEnginePerspective": {
                perspective: dict(
                    _mapping(
                        packed_session_by_selected[perspective],
                        f"packed session {perspective}",
                    )
                )
                for perspective in PERSPECTIVES
            },
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound": (
                recomputed_session_maximum
            ),
            "proofStatus": "PASS",
        },
        "perSelectedPerspective": updates,
    }
    return _with_self_hash(result)


def apply_causal_runtime_resource_update(
    runtime: dict[str, Any],
    update: Mapping[str, Any],
) -> None:
    """Apply one deterministically verified causal update to a draft runtime v3.

    This is intentionally strict and has no v2 fallback.  The caller must have
    produced ``update`` by calling :func:`causal_runtime_resource_updates` on
    the final plan, causal proof, and packed-shard proof.  Promotion can then
    compare the applied runtime fields to this exact self-hashed value instead
    of trusting a hand-edited ``PASS`` string.
    """

    _require(
        update.get("schema") == RUNTIME_UPDATE_SCHEMA
        and update.get("status") == "PASS",
        "causal runtime resource update is not PASS",
    )
    _validate_self_hash(update, "causal runtime resource update")
    _require(
        update.get("atlasFamilyId") == runtime.get("id")
        and update.get("planSha256") == runtime.get("planSha256"),
        "causal runtime resource update does not match the runtime",
    )
    _sha256_string(
        update.get("causalResourceProofSha256"),
        "causal resource proof SHA-256",
    )
    _sha256_string(
        update.get("packedShardProofSha256"),
        "packed shard proof SHA-256",
    )
    _require(
        update.get("causalVerifierImplementationSha256")
        == hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        and update.get("sessionStateGraphProducerImplementationSha256")
        == hashlib.sha256(
            Path(__file__).with_name("export_full_event_session_state_graph.py").read_bytes()
        ).hexdigest(),
        "causal proof implementation hash differs",
    )
    _require(
        update.get("resourceBoundsSchema")
        == "byd-full-event-atlas-runtime-resource-bounds-v3"
        and update.get("resourceBoundsScope")
        == "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
        "causal runtime update does not target resource-bounds v3",
    )
    resource_bounds = _mapping(runtime.get("resourceBounds"), "runtime resource bounds")
    _require(
        resource_bounds.get("schema") == update.get("resourceBoundsSchema")
        and resource_bounds.get("scope") == update.get("resourceBoundsScope"),
        "runtime resource-bounds contract differs from the causal update",
    )
    per_perspective = _mapping(
        resource_bounds.get("perPerspective"),
        "runtime per-perspective resources",
    )
    update_perspectives = _mapping(
        update.get("perSelectedPerspective"),
        "causal update perspectives",
    )
    _require(
        set(per_perspective) == set(PERSPECTIVES)
        and set(update_perspectives) == set(PERSPECTIVES),
        "causal runtime update perspective matrix differs",
    )
    session_common = _mapping(
        update.get("sessionCommon"),
        "causal update session peaks",
    )
    maximum_logical = _nonnegative_int(
        session_common.get("maximumCausalFmodLogicalChannels"),
        "causal update session logical channels",
    )
    maximum_real = _nonnegative_int(
        session_common.get("maximumCausalFmodRealChannels"),
        "causal update session real channels",
    )
    _require(
        session_common.get("finiteRingPoolStatus") == "PASS"
        and session_common.get("peakProofStatus") == "PASS"
        and maximum_logical == maximum_real
        and maximum_real <= ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        "causal update session resource peak is not exactly representable",
    )
    for perspective in PERSPECTIVES:
        target = _mapping(
            per_perspective[perspective],
            f"runtime {perspective} resources",
        )
        source = _mapping(
            update_perspectives[perspective],
            f"causal update {perspective} resources",
        )
        _require(
            set(source) == {"engine", "effects", "total"},
            f"causal update {perspective} resource components differ",
        )
        for component in ("engine", "effects", "total"):
            target_component = target.get(component)
            _require(
                isinstance(target_component, dict),
                f"runtime {perspective} {component} resource is not mutable",
            )
            source_component = _mapping(
                source[component],
                f"causal update {perspective} {component}",
            )
            target_component.update(source_component)
    session = _mapping(update.get("session"), "causal update session mappings")
    _require(
        set(session)
        == {
            "mappingInstanceIdentity",
            "retainedEffectPerspectives",
            "perSelectedEnginePerspective",
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
            "proofStatus",
        }
        and session.get("mappingInstanceIdentity")
        == "activationPerspectivePlusShardName"
        and session.get("retainedEffectPerspectives") == list(PERSPECTIVES)
        and session.get("proofStatus") == "PASS",
        "causal update session mapping contract differs/is not PASS",
    )
    _require(
        isinstance(resource_bounds, dict),
        "runtime resource bounds are not mutable",
    )
    resource_bounds["session"] = {
        key: (
            {
                selected: dict(_mapping(record, f"causal session {selected}"))
                for selected, record in _mapping(
                    value,
                    "causal session selected-engine perspectives",
                ).items()
            }
            if key == "perSelectedEnginePerspective"
            else list(value)
            if key == "retainedEffectPerspectives"
            else value
        )
        for key, value in session.items()
    }


def validate_applied_causal_runtime_resource_update(
    runtime: Mapping[str, Any],
    update: Mapping[str, Any],
) -> None:
    """Reject a runtime whose executable resource fields differ from ``update``."""

    candidate = copy.deepcopy(dict(runtime))
    apply_causal_runtime_resource_update(candidate, update)
    _require(
        canonical_json_bytes(candidate) == canonical_json_bytes(runtime),
        "runtime does not contain the exact verified causal resource update",
    )


def explore_host_control_reachability(
    plan: Mapping[str, Any],
    state_graph: Mapping[str, Any],
    witness_bindings: Mapping[str, Any],
    *,
    implementation_source_root: Path,
) -> dict[str, Any]:
    """Explore the exported host-control machine instead of trusting claimed peak scalars.

    The exporter is tied to the exact Android control implementation by SHA-256.  This explorer
    independently traverses every reachable control state for every one of the 256 possible
    240-frame-host/256-frame-DSP phases.  Active finite rings are part of the explored state, so a
    fixed point proves the tail-overlap peak without an arbitrary capture horizon.
    """

    from tools.profile_generation.export_full_event_session_state_graph import (
        produce_full_event_session_state_graph,
    )

    contract = _plan_contract(plan)
    generated_state_graph = produce_full_event_session_state_graph(
        plan,
        implementation_source_root=implementation_source_root,
    )
    _validate_named_self_hash(state_graph, "graphSha256", "host-control graph")
    _require(
        canonical_json_bytes(state_graph) == canonical_json_bytes(generated_state_graph),
        "host-control state graph differs from the deterministic executable-contract producer",
    )
    implementation_sha256 = _sha256_string(
        generated_state_graph.get("hostControlImplementationSha256"),
        "generated host-control implementation SHA-256",
    )
    machines, session_machine = _parse_host_state_graph(
        contract,
        state_graph,
    )
    bindings = _parse_host_witness_bindings(contract, witness_bindings)
    entries: list[dict[str, Any]] = []
    phase_explorations: dict[tuple[str, int], Mapping[tuple[str, str], Mapping[str, Any]]] = {}
    for perspective in PERSPECTIVES:
        for mode in PROGRAM_MODES:
            for group_key, group in sorted(contract.groups.items()):
                if not group.maximum_capture_frames:
                    continue
                phase_peaks: list[int] = []
                exhaustive_state_count = 0
                exhaustive_transition_count = 0
                analysis_horizon = 0
                peak_trigger_frames: tuple[int, ...] = ()
                peak_trigger_records: tuple[tuple[int, str], ...] = ()
                peak_host_path: list[dict[str, Any]] = []
                peak_activation_counts: dict[str, int] = {}
                peak_phase = 0
                global_peak_value = -1
                for phase in range(DSP_BUFFER_FRAMES):
                    phase_key = (group_key, phase)
                    if phase_key not in phase_explorations:
                        phase_explorations[phase_key] = _explore_group_phase_contexts(
                            machines[group_key],
                            group_id=group_key,
                            initial_phase_frames=phase,
                            maximum_capture_frames_by_activation_perspective=(
                                group.maximum_capture_frames
                            ),
                        )
                    explored = phase_explorations[phase_key][(perspective, mode)]
                    phase_peaks.append(explored["maximumLiveRings"])
                    exhaustive_state_count += explored["stateCount"]
                    exhaustive_transition_count += explored["transitionCount"]
                    analysis_horizon = max(
                        analysis_horizon,
                        explored["analysisHorizonFrames"],
                    )
                    candidate_frames = tuple(explored["peakTriggerHostFrames"])
                    if (
                        explored["maximumLiveRings"] > global_peak_value
                        or (
                            explored["maximumLiveRings"] == global_peak_value
                            and candidate_frames
                            and (not peak_trigger_frames or candidate_frames < peak_trigger_frames)
                        )
                    ):
                        global_peak_value = explored["maximumLiveRings"]
                        peak_trigger_frames = candidate_frames
                        peak_activation_counts = dict(
                            explored["peakLiveRingsByActivationPerspective"]
                        )
                        peak_trigger_records = tuple(
                            explored["peakTriggerHostRecords"]
                        )
                        peak_host_path = list(explored["peakHostPath"])
                        peak_phase = phase
                maximum_reachable = max(phase_peaks)
                minimum_reentry_ticks = _minimum_group_reentry_ticks(
                    machines[group_key],
                    group_key,
                )
                identity = (perspective, mode, group_key)
                binding = bindings[identity]
                if maximum_reachable > 0:
                    _require(
                        binding[0],
                        f"{identity} has no empirical peak witness binding",
                    )
                else:
                    _require(
                        not binding[0],
                        f"{identity} binds a peak witness although no ring is reachable",
                    )
                if maximum_reachable > 1:
                    _require(
                        minimum_reentry_ticks is not None,
                        f"{perspective}/{mode}/{group_key} overlaps tails without a repeatable start",
                    )
                if maximum_reachable > 1:
                    _require(
                        binding[1],
                        f"{identity} has no empirical minimum-reentry witness binding",
                    )
                else:
                    _require(
                        not binding[1],
                        f"{identity} binds a re-entry witness although no overlap is reachable",
                    )
                entries.append(
                    {
                        "perspective": perspective,
                        "programMode": mode,
                        "groupKey": group_key,
                        "eventPath": group.event_path,
                        "groupId": group.group_id,
                        "semanticTriggers": sorted(group.semantic_triggers),
                        "maximumLiveRingsByInitialHostPhase": phase_peaks,
                        "maximumReachablePhysicalLiveRings": maximum_reachable,
                        "exhaustiveStateCount": exhaustive_state_count,
                        "exhaustiveTransitionCount": exhaustive_transition_count,
                        "analysisHorizonFrames": max(
                            analysis_horizon,
                            max(group.maximum_capture_frames.values())
                            + math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES),
                        ),
                        "minimumHostTicksBetweenStarts": minimum_reentry_ticks,
                        "peakWitnessInitialHostPhaseFrames": peak_phase,
                        "peakWitnessTriggerHostFrames": list(peak_trigger_frames),
                        "peakWitnessTriggerHostRecords": [
                            {
                                "hostFrame": frame,
                                "activationPerspective": activation_perspective,
                            }
                            for frame, activation_perspective in peak_trigger_records
                        ],
                        "peakWitnessHostPath": peak_host_path,
                        "peakLiveRingsByActivationPerspective": peak_activation_counts,
                        "peakWitnessScenarioIds": list(binding[0]),
                        "minimumReentryWitnessScenarioIds": list(binding[1]),
                    }
                )
    session_phase_explorations: dict[
        int,
        Mapping[tuple[str, str], Mapping[str, Any]],
    ] = {}
    session_entries: list[dict[str, Any]] = []
    for perspective in PERSPECTIVES:
        for mode in PROGRAM_MODES:
            phase_metrics = {
                "ringPoolBytes": [],
                "liveRings": [],
                "finiteFmodChannels": [],
                "newContributorsPerDspUpdate": [],
                "newFmodChannelsPerDspUpdate": [],
            }
            exhaustive_state_count = 0
            exhaustive_transition_count = 0
            analysis_horizon = 0
            peak_phase = 0
            peak_value = -1
            peak_trigger_records: tuple[tuple[int, str, str], ...] = ()
            peak_host_path: list[dict[str, Any]] = []
            peak_ring_matrix: dict[str, dict[str, int]] = {}
            for phase in range(DSP_BUFFER_FRAMES):
                if phase not in session_phase_explorations:
                    session_phase_explorations[phase] = _explore_session_phase_contexts(
                        contract,
                        session_machine,
                        initial_phase_frames=phase,
                    )
                explored = session_phase_explorations[phase][(perspective, mode)]
                for metric in phase_metrics:
                    phase_metrics[metric].append(int(explored[metric]))
                exhaustive_state_count += int(explored["stateCount"])
                exhaustive_transition_count += int(explored["transitionCount"])
                analysis_horizon = max(
                    analysis_horizon,
                    int(explored["analysisHorizonFrames"]),
                )
                candidate_value = int(explored["ringPoolBytes"])
                candidate_records = tuple(explored["ringPoolPeakTriggerHostRecords"])
                if (
                    candidate_value > peak_value
                    or (
                        candidate_value == peak_value
                        and candidate_records
                        and (
                            not peak_trigger_records
                            or candidate_records < peak_trigger_records
                        )
                    )
                ):
                    peak_value = candidate_value
                    peak_phase = phase
                    peak_trigger_records = candidate_records
                    peak_host_path = list(explored["ringPoolPeakHostPath"])
                    peak_ring_matrix = {
                        activation_perspective: dict(group_counts)
                        for activation_perspective, group_counts in explored[
                            "ringPoolPeakLiveRingsByActivationPerspectiveAndGroup"
                        ].items()
                    }
            session_entries.append(
                {
                    "perspective": perspective,
                    "programMode": mode,
                    "maximumRingPoolBytesByInitialHostPhase": phase_metrics[
                        "ringPoolBytes"
                    ],
                    "maximumLiveRingsByInitialHostPhase": phase_metrics["liveRings"],
                    "maximumFiniteFmodChannelsByInitialHostPhase": phase_metrics[
                        "finiteFmodChannels"
                    ],
                    "maximumNewContributorsPerDspUpdateByInitialHostPhase": (
                        phase_metrics["newContributorsPerDspUpdate"]
                    ),
                    "maximumNewFmodChannelsPerDspUpdateByInitialHostPhase": (
                        phase_metrics["newFmodChannelsPerDspUpdate"]
                    ),
                    "maximumReachableSessionRingPoolBytes": max(
                        phase_metrics["ringPoolBytes"]
                    ),
                    "maximumReachablePhysicalLiveRings": max(
                        phase_metrics["liveRings"]
                    ),
                    "maximumReachableFiniteFmodChannels": max(
                        phase_metrics["finiteFmodChannels"]
                    ),
                    "maximumReachableNewContributorsPerDspUpdate": max(
                        phase_metrics["newContributorsPerDspUpdate"]
                    ),
                    "maximumReachableNewFmodChannelsPerDspUpdate": max(
                        phase_metrics["newFmodChannelsPerDspUpdate"]
                    ),
                    "exhaustiveStateCount": exhaustive_state_count,
                    "exhaustiveTransitionCount": exhaustive_transition_count,
                    "analysisHorizonFrames": analysis_horizon,
                    "ringPoolPeakWitnessInitialHostPhaseFrames": peak_phase,
                    "ringPoolPeakTriggerHostRecords": [
                        {
                            "hostFrame": frame,
                            "activationPerspective": activation_perspective,
                            "groupKey": group_key,
                        }
                        for frame, activation_perspective, group_key
                        in peak_trigger_records
                    ],
                    "ringPoolPeakHostPath": peak_host_path,
                    "ringPoolPeakLiveRingsByActivationPerspectiveAndGroup": (
                        peak_ring_matrix
                    ),
                }
            )
    result = {
        "schema": HOST_REACHABILITY_SCHEMA,
        "status": "PASS",
        "atlasFamilyId": contract.family_id,
        "planSha256": contract.plan_sha256,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "hostTickFrames": HOST_TICK_FRAMES,
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "hostControlImplementationSha256": implementation_sha256,
        "hostControlStateGraphSha256": state_graph.get("graphSha256"),
        "stateSpaceExhaustive": True,
        "initialHostPhaseFrames": list(range(DSP_BUFFER_FRAMES)),
        "groups": entries,
        "sessionContexts": session_entries,
    }

    return _with_named_self_hash(result, "exhaustiveStateSpaceProofSha256")


def _parse_host_state_graph(
    contract: _PlanContract,
    state_graph: Mapping[str, Any],
) -> tuple[dict[str, _HostMachine], _HostSessionMachine]:
    _require(state_graph.get("schema") == HOST_STATE_GRAPH_SCHEMA, "host-control graph schema differs")
    _validate_named_self_hash(state_graph, "graphSha256", "host-control graph")
    _require(
        state_graph.get("atlasFamilyId") == contract.family_id
        and state_graph.get("planSha256") == contract.plan_sha256,
        "host-control graph does not match the atlas plan",
    )
    _require(
        state_graph.get("sampleRateHz") == SAMPLE_RATE_HZ
        and state_graph.get("hostTickFrames") == HOST_TICK_FRAMES
        and state_graph.get("dspBufferFrames") == DSP_BUFFER_FRAMES,
        "host-control graph cadence differs",
    )
    _sha256_string(
        state_graph.get("hostControlImplementationSha256"),
        "host-control graph implementation SHA-256",
    )
    _require(
        state_graph.get("stateSpaceComplete") is True,
        "host-control graph exporter did not declare a complete transition relation",
    )
    machine_records: dict[str, Mapping[str, Any]] = {}
    for raw_machine in _sequence(
        state_graph.get("groupMachines"),
        "host-control scheduling-group machines",
    ):
        machine_record = _mapping(raw_machine, "host-control scheduling-group machine")
        event_path = _strict_string(
            machine_record.get("eventPath"),
            "host-control scheduling-group event path",
        )
        group_id = _strict_string(
            machine_record.get("groupId"),
            "host-control authored scheduling-group id",
        )
        group_key = _strict_string(
            machine_record.get("groupKey"),
            "host-control exact scheduling-group key",
        )
        _require(
            group_key == scheduling_group_key(event_path, group_id),
            f"{event_path}/{group_id} host-control scheduling-group key differs",
        )
        group = contract.groups.get(group_key)
        _require(
            group is not None
            and group.event_path == event_path
            and group.group_id == group_id
            and bool(group.maximum_capture_frames),
            f"host-control machine references unknown finite group {event_path}/{group_id}",
        )
        _require(group_key not in machine_records, f"host-control graph repeats group {group_key}")
        raw_bindings = [
            _mapping(value, f"{group_key} authored binding")
            for value in _sequence(
                machine_record.get("bindings"),
                f"{group_key} authored bindings",
            )
        ]
        binding_keys = {
            _authored_binding_key(
                binding.get("authoredBindingKey"),
                f"{group_key} authored binding key",
            )
            for binding in raw_bindings
        }
        _require(
            len(binding_keys) == len(raw_bindings)
            and binding_keys == set(group.authored_binding_keys),
            f"{group_key} host-control authored-binding coverage differs",
        )
        machine_records[group_key] = machine_record
    expected_group_keys = {
        group_key
        for group_key, group in contract.groups.items()
        if group.maximum_capture_frames
    }
    _require(
        set(machine_records) == expected_group_keys,
        "host-control scheduling-group machine coverage differs from the finite plan",
    )

    machines = {
        group_key: _parse_host_group_machine(contract, group_key, machine_record)
        for group_key, machine_record in machine_records.items()
    }

    return machines, _parse_host_session_machine(
        contract,
        state_graph.get("sessionMachine"),
        machine_records,
    )


def _parse_host_session_machine(
    contract: _PlanContract,
    value: object,
    machine_records: Mapping[str, Mapping[str, Any]],
) -> _HostSessionMachine:
    record = _mapping(value, "global host-control session machine")
    _require(
        record.get("schema") == "byd-full-event-global-session-machine-v1",
        "global host-control session-machine schema differs",
    )
    expected_binding_order: list[tuple[str, str]] = []
    expected_group_records: list[tuple[str, str, str, str, tuple[int, ...]]] = []
    next_binding_index = 0
    for group_key, machine_record in machine_records.items():
        raw_bindings = _sequence(
            machine_record.get("bindings"),
            f"{group_key} host-control authored bindings",
        )
        binding_indexes = tuple(
            range(next_binding_index, next_binding_index + len(raw_bindings))
        )
        for raw_binding in raw_bindings:
            binding = _mapping(raw_binding, f"{group_key} host-control authored binding")
            expected_binding_order.append(
                (
                    group_key,
                    _authored_binding_key(
                        binding.get("authoredBindingKey"),
                        f"{group_key} authored binding key",
                    ),
                )
            )
        ownership = _mapping(
            machine_record.get("eventInstanceOwnership"),
            f"{group_key} event-instance ownership",
        )
        owner = _strict_string(
            ownership.get("owner"),
            f"{group_key} event-instance owner",
        )
        _require(
            owner in {
                "selectedPerspectiveEngineEventInstance",
                "profileAudioSessionPersistentEventInstance",
            },
            f"{group_key} has an unsupported global session owner",
        )
        expected_group_records.append(
            (
                _strict_string(machine_record.get("eventPath"), f"{group_key} event path"),
                _strict_string(machine_record.get("groupId"), f"{group_key} group id"),
                group_key,
                owner,
                binding_indexes,
            )
        )
        next_binding_index += len(raw_bindings)
    binding_order = [
        (
            _strict_string(
                _mapping(raw_binding, "global session binding order").get("groupKey"),
                "global session binding group key",
            ),
            _authored_binding_key(
                _mapping(raw_binding, "global session binding order").get(
                    "authoredBindingKey"
                ),
                "global session authored binding key",
            ),
        )
        for raw_binding in _sequence(
            record.get("bindingOrder"),
            "global session binding order",
        )
    ]
    _require(
        binding_order == expected_binding_order,
        "global session authored-binding order differs from exact scheduling groups",
    )
    raw_groups = _sequence(record.get("groups"), "global session groups")
    _require(
        len(raw_groups) == len(expected_group_records),
        "global session group coverage differs",
    )
    parsed_groups: list[_HostSessionGroup] = []
    for raw_group, expected in zip(raw_groups, expected_group_records):
        group = _mapping(raw_group, "global session group")
        event_path, group_id, group_key, owner, binding_indexes = expected
        _require(
            group.get("eventPath") == event_path
            and group.get("groupId") == group_id
            and group.get("groupKey") == group_key
            and _mapping(
                group.get("eventInstanceOwnership"),
                f"{group_key} global ownership",
            ).get("owner") == owner,
            f"{group_key} global session group identity/owner differs",
        )
        observed_indexes = tuple(
            _nonnegative_int(value, f"{group_key} binding index")
            for value in _sequence(
                group.get("bindingIndexes"),
                f"{group_key} binding indexes",
            )
        )
        _require(
            observed_indexes == binding_indexes,
            f"{group_key} global session binding indexes differ",
        )
        parsed_groups.append(
            _HostSessionGroup(
                group_key=group_key,
                owner=owner,
                binding_indexes=binding_indexes,
            )
        )
    _require(
        record.get("transitionContract")
        == {
            "sourceStateTargets": "everyCanonicalSessionStateAtEach200HzHostTick",
            "sharedHostInputCorrelation": "oneExactHostInputCellEvaluatesAllBindings",
            "persistentOwnerEntry": "emitOncePerGroupWhenAnyBindingChangesOutsideToInside",
            "selectedPerspectiveEngineOwnerEntry": (
                "onPerspectiveActivationResetEvaluateTargetMembershipThenEmitOncePerInsideGroup;"
                "otherwiseEmitOncePerGroupWhenAnyBindingChangesOutsideToInside"
            ),
            "atomicGroupEmission": "atMostOneLogicalRingStartPerSchedulingGroupPerHostTick",
        },
        "global session transition contract differs",
    )
    states = _sequence(record.get("states"), "global session states")
    _require(states, "global session machine has no states")
    state_contexts: dict[str, tuple[str, str]] = {}
    memberships: dict[str, tuple[bool, ...]] = {}
    state_host_values: dict[str, dict[str, float]] = {}
    identity_set: set[tuple[str, str, tuple[bool, ...]]] = set()
    for raw_state in states:
        state = _mapping(raw_state, "global session state")
        _require(
            set(state)
            == {"id", "selectedPerspective", "programMode", "membership", "hostValues"},
            "global session state fields differ",
        )
        raw_host_values = _mapping(
            state.get("hostValues"),
            "global session host values",
        )
        parsed_host_values: dict[str, float] = {}
        for source, value in raw_host_values.items():
            _strict_string(source, "global session host-value source")
            _require(
                isinstance(value, (int, float))
                and not isinstance(value, bool)
                and math.isfinite(float(value)),
                f"global session host value {source} is not finite",
            )
            parsed_host_values[str(source)] = float(value)
        state_id = _strict_string(state.get("id"), "global session state id")
        _require(state_id not in state_contexts, f"global session repeats state {state_id}")
        perspective = _choice(
            state.get("selectedPerspective"),
            PERSPECTIVES,
            f"{state_id} selected perspective",
        )
        mode = _choice(
            state.get("programMode"),
            PROGRAM_MODES,
            f"{state_id} program mode",
        )
        raw_membership = _sequence(
            state.get("membership"),
            f"{state_id} membership",
        )
        _require(
            len(raw_membership) == len(binding_order)
            and all(isinstance(item, bool) for item in raw_membership),
            f"{state_id} global membership shape differs",
        )
        membership = tuple(raw_membership)
        identity = (perspective, mode, membership)
        _require(identity not in identity_set, "global session repeats a host-input state")
        identity_set.add(identity)
        state_contexts[state_id] = (perspective, mode)
        memberships[state_id] = membership
        state_host_values[state_id] = parsed_host_values
    _require(
        set(state_contexts.values())
        == {
            (perspective, mode)
            for perspective in PERSPECTIVES
            for mode in PROGRAM_MODES
        },
        "global session state context matrix is incomplete",
    )
    initial_states: list[tuple[str, frozenset[tuple[str, str]]]] = []
    seen_initial_ids: set[str] = set()
    for raw_initial in _sequence(
        record.get("initialStates"),
        "global session initial states",
    ):
        initial = _mapping(raw_initial, "global session initial state")
        _require(
            set(initial)
            == {
                "stateId",
                "emissions",
                "activationMembershipEvaluatedFromCurrentHostValues",
            }
            and initial.get("activationMembershipEvaluatedFromCurrentHostValues") is True,
            "global session initial-state contract differs",
        )
        state_id = _strict_string(initial.get("stateId"), "global session initial state id")
        _require(
            state_id in state_contexts and state_id not in seen_initial_ids,
            "global session initial-state coverage differs",
        )
        seen_initial_ids.add(state_id)
        emissions = _parse_host_session_emissions(
            contract,
            initial.get("emissions"),
            perspective=state_contexts[state_id][0],
            label=f"{state_id} global initial",
        )
        expected_emitted_groups = {
            group.group_key
            for group in parsed_groups
            if any(memberships[state_id][index] for index in group.binding_indexes)
        }
        _require(
            {group_key for group_key, _ in emissions} == expected_emitted_groups,
            f"{state_id} global initial emissions differ from exact membership",
        )
        initial_states.append((state_id, emissions))
    _require(
        seen_initial_ids == set(state_contexts),
        "global session initial states do not cover every canonical state",
    )

    return _HostSessionMachine(
        initial_states=tuple(initial_states),
        state_contexts=state_contexts,
        memberships=memberships,
        host_values=state_host_values,
        groups=tuple(parsed_groups),
    )


def _parse_host_group_machine(
    contract: _PlanContract,
    group_key: str,
    machine_record: Mapping[str, Any],
) -> _HostMachine:
    group = contract.groups[group_key]
    raw_states = _sequence(machine_record.get("states"), f"{group_key} host-control states")
    _require(raw_states, f"{group_key} host-control machine has no states")
    state_contexts: dict[str, tuple[str, str]] = {}
    state_host_values: dict[str, dict[str, float]] = {}
    raw_transition_records: dict[str, list[Mapping[str, Any]]] = {}
    for raw_state in raw_states:
        state = _mapping(raw_state, "host-control session state")
        state_id = _strict_string(state.get("id"), "host-control state id")
        _require(state_id not in raw_transition_records, f"host-control graph repeats state {state_id}")
        state_contexts[state_id] = (
            _choice(
                state.get("selectedPerspective"),
                PERSPECTIVES,
                f"{state_id} selected perspective",
            ),
            _choice(
                state.get("programMode"),
                PROGRAM_MODES,
                f"{state_id} program mode",
            ),
        )
        raw_host_values = _mapping(
            state.get("hostValues"),
            f"{state_id} host values",
        )
        parsed_host_values: dict[str, float] = {}
        for source, value in raw_host_values.items():
            _strict_string(source, f"{state_id} host-value source")
            _require(
                isinstance(value, (int, float))
                and not isinstance(value, bool)
                and math.isfinite(float(value)),
                f"{state_id} host value {source} is not finite",
            )
            parsed_host_values[str(source)] = float(value)
        state_host_values[state_id] = parsed_host_values
        records = [
            _mapping(item, f"{state_id} transition")
            for item in _sequence(state.get("transitions"), f"{state_id} transitions")
        ]
        _require(records, f"{state_id} has no outgoing transition")
        raw_transition_records[state_id] = records
    state_ids = set(raw_transition_records)
    transitions: dict[str, tuple[_HostTransition, ...]] = {}
    for state_id, records in raw_transition_records.items():
        parsed: list[_HostTransition] = []
        seen_transitions: set[tuple[str, frozenset[tuple[str, str]]]] = set()
        for record in records:
            target = _strict_string(record.get("targetStateId"), f"{state_id} transition target")
            _require(target in state_ids, f"{state_id} targets unknown state {target}")
            target_perspective, _ = state_contexts[target]
            emissions = _parse_host_emissions(
                contract,
                record.get("emissions"),
                perspective=target_perspective,
                label=f"{state_id}->{target}",
                expected_group_key=group_key,
            )
            key = (target, emissions)
            _require(key not in seen_transitions, f"{state_id} repeats an exact transition")
            seen_transitions.add(key)
            parsed.append(_HostTransition(target_state=target, emissions=emissions))
        transitions[state_id] = tuple(parsed)
    initial_states: list[tuple[str, frozenset[tuple[str, str]]]] = []
    seen_initials: set[tuple[str, frozenset[tuple[str, str]]]] = set()
    for raw_initial in _sequence(machine_record.get("initialStates"), "host-control initial states"):
        initial = _mapping(raw_initial, "host-control initial state")
        state_id = _strict_string(initial.get("stateId"), "host-control initial state id")
        _require(state_id in state_ids, f"host-control graph has unknown initial state {state_id}")
        perspective, _ = state_contexts[state_id]
        emissions = _parse_host_emissions(
            contract,
            initial.get("emissions"),
            perspective=perspective,
            label=f"{state_id} initial",
            expected_group_key=group_key,
        )
        item = (state_id, emissions)
        _require(item not in seen_initials, "host-control graph repeats an exact initial state")
        seen_initials.add(item)
        initial_states.append(item)
    _require(initial_states, f"{group_key} host-control machine has no initial state")
    reachable = {state_id for state_id, _ in initial_states}
    pending = list(reachable)
    while pending:
        state_id = pending.pop()
        for transition in transitions[state_id]:
            if transition.target_state not in reachable:
                reachable.add(transition.target_state)
                pending.append(transition.target_state)
    _require(
        reachable == state_ids,
        f"host-control graph contains unreachable/unproven states {sorted(state_ids - reachable)}",
    )
    observed_contexts = {state_contexts[state_id] for state_id in reachable}
    expected_contexts = {
        (perspective, mode)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
    }
    _require(observed_contexts == expected_contexts, "host-control state context matrix is incomplete")
    emissions_by_context: dict[tuple[str, str], set[tuple[str, str]]] = {
        context: set() for context in expected_contexts
    }
    for state_id, emissions in initial_states:
        emissions_by_context[state_contexts[state_id]].update(emissions)
    for state_id in reachable:
        for transition in transitions[state_id]:
            emissions_by_context[state_contexts[transition.target_state]].update(
                transition.emissions
            )
    for perspective, mode in sorted(expected_contexts):
        observed_triggers = {
            trigger
            for emitted_group, trigger in emissions_by_context[(perspective, mode)]
            if emitted_group == group_key
        }
        expected_triggers = (
            set(group.semantic_triggers)
            if perspective in group.maximum_capture_frames
            else set()
        )
        _require(
            observed_triggers == expected_triggers,
            f"{perspective}/{mode}/{group_key} exported semantic-trigger coverage differs",
        )

    return _HostMachine(
        initial_states=tuple(initial_states),
        transitions=transitions,
        state_contexts=state_contexts,
        host_values=state_host_values,
    )


def _parse_host_emissions(
    contract: _PlanContract,
    value: object,
    *,
    perspective: str,
    label: str,
    expected_group_key: str,
) -> frozenset[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for raw_emission in _sequence(value, f"{label} emissions"):
        emission = _mapping(raw_emission, f"{label} emission")
        event_path = _strict_string(emission.get("eventPath"), f"{label} emission event")
        group_id = _strict_string(emission.get("groupId"), f"{label} emission group")
        group_key = scheduling_group_key(event_path, group_id)
        trigger = _strict_string(emission.get("semanticTrigger"), f"{label} emission trigger")
        group = contract.groups.get(group_key)
        _require(
            group is not None
            and group_key == expected_group_key
            and perspective in group.maximum_capture_frames
            and trigger in group.semantic_triggers,
            f"{label} emits an unknown/ineligible finite scheduling-group trigger",
        )
        result.append((group_key, trigger))
    _require(len(result) == len(set(result)), f"{label} repeats one semantic start")

    return frozenset(result)


def _parse_host_session_emissions(
    contract: _PlanContract,
    value: object,
    *,
    perspective: str,
    label: str,
) -> frozenset[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for raw_emission in _sequence(value, f"{label} emissions"):
        emission = _mapping(raw_emission, f"{label} emission")
        event_path = _strict_string(emission.get("eventPath"), f"{label} emission event")
        group_id = _strict_string(emission.get("groupId"), f"{label} emission group")
        group_key = scheduling_group_key(event_path, group_id)
        trigger = _strict_string(emission.get("semanticTrigger"), f"{label} emission trigger")
        group = contract.groups.get(group_key)
        _require(
            group is not None
            and perspective in group.maximum_capture_frames
            and trigger in group.semantic_triggers,
            f"{label} emits an unknown/ineligible finite scheduling-group trigger",
        )
        result.append((group_key, trigger))
    _require(len(result) == len(set(result)), f"{label} repeats one semantic start")

    return frozenset(result)


def _parse_host_witness_bindings(
    contract: _PlanContract,
    witness_bindings: Mapping[str, Any],
) -> dict[tuple[str, str, str], tuple[tuple[str, ...], tuple[str, ...]]]:
    _require(
        witness_bindings.get("schema") == HOST_WITNESS_BINDINGS_SCHEMA,
        "host-control witness-binding schema differs",
    )
    _require(
        witness_bindings.get("atlasFamilyId") == contract.family_id
        and witness_bindings.get("planSha256") == contract.plan_sha256,
        "host-control witness bindings do not match the atlas plan",
    )
    result: dict[tuple[str, str, str], tuple[tuple[str, ...], tuple[str, ...]]] = {}
    for raw_binding in _sequence(witness_bindings.get("bindings"), "host-control witness bindings"):
        binding = _mapping(raw_binding, "host-control witness binding")
        perspective = _choice(binding.get("perspective"), PERSPECTIVES, "witness perspective")
        mode = _choice(binding.get("programMode"), PROGRAM_MODES, "witness program mode")
        event_path = _strict_string(binding.get("eventPath"), "witness event path")
        group_id = _strict_string(binding.get("groupId"), "witness group id")
        group_key = scheduling_group_key(event_path, group_id)
        group = contract.groups.get(group_key)
        _require(
            group is not None and bool(group.maximum_capture_frames),
            "host-control witness binding references an unknown finite group",
        )
        identity = (perspective, mode, group_key)
        _require(identity not in result, f"host-control witness binding repeats {identity}")
        peaks = tuple(
            _strict_string(value, f"{identity} peak witness id")
            for value in _sequence(binding.get("peakWitnessScenarioIds"), f"{identity} peak witnesses")
        )
        reentries = tuple(
            _strict_string(value, f"{identity} re-entry witness id")
            for value in _sequence(
                binding.get("minimumReentryWitnessScenarioIds"),
                f"{identity} re-entry witnesses",
            )
        )
        _require(len(peaks) == len(set(peaks)), f"{identity} peak witnesses repeat")
        _require(len(reentries) == len(set(reentries)), f"{identity} re-entry witnesses repeat")
        result[identity] = (peaks, reentries)
    expected = {
        (perspective, mode, group_id)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
        for group_id, group in contract.groups.items()
        if group.maximum_capture_frames
    }
    _require(set(result) == expected, "host-control witness-binding matrix is incomplete")

    return result


def _explore_group_phase_contexts(
    machine: _HostMachine,
    *,
    group_id: str,
    initial_phase_frames: int,
    maximum_capture_frames_by_activation_perspective: Mapping[str, int],
) -> dict[tuple[str, str], dict[str, Any]]:
    maximum_capture_frames = max(
        maximum_capture_frames_by_activation_perspective.values()
    )
    state_records: dict[
        tuple[str, int, _ActiveRingState],
        tuple[
            tuple[str, int, _ActiveRingState] | None,
            tuple[tuple[int, str], ...],
            int,
        ],
    ] = {}
    pending: deque[tuple[str, int, _ActiveRingState]] = deque()
    contexts = {
        (perspective, mode)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
    }
    maximum_live = {context: -1 for context in contexts}
    peak_state: dict[
        tuple[str, str],
        tuple[str, int, _ActiveRingState] | None,
    ] = {context: None for context in contexts}
    transition_count = 0
    maximum_depth = 0

    def add_state(
        state: tuple[str, int, _ActiveRingState],
        *,
        parent: tuple[str, int, _ActiveRingState] | None,
        emission_records: tuple[tuple[int, str], ...],
        depth: int,
    ) -> None:
        nonlocal maximum_depth
        if state in state_records:
            return
        _require(
            len(state_records) < 1_000_000,
            f"{group_id}/phase{initial_phase_frames} "
            "host state space exceeds the verifier's fail-closed operational limit",
        )
        state_records[state] = (parent, emission_records, depth)
        pending.append(state)
        maximum_depth = max(maximum_depth, depth)
        live = len(state[2])
        context = machine.state_contexts[state[0]]
        if maximum_live[context] < live:
            maximum_live[context] = live
            peak_state[context] = state

    for state_id, emissions in machine.initial_states:
        count = sum(emitted_group == group_id for emitted_group, _ in emissions)
        activation_perspective = machine.state_contexts[state_id][0]
        capture_frames = maximum_capture_frames_by_activation_perspective.get(
            activation_perspective
        )
        _require(
            count == 0 or capture_frames is not None,
            "host graph emits a group outside its activation perspective",
        )
        ring_lifetime_blocks = (
            math.ceil(capture_frames / DSP_BUFFER_FRAMES)
            if capture_frames is not None
            else 0
        )
        ttls = tuple(
            [(ring_lifetime_blocks, activation_perspective)] * count
        )
        add_state(
            (state_id, initial_phase_frames, ttls),
            parent=None,
            emission_records=tuple(
                [(initial_phase_frames, activation_perspective)] * count
            ),
            depth=0,
        )
    while pending:
        state = pending.popleft()
        state_id, host_residue, active_ttls = state
        _, _, depth = state_records[state]
        current_delivery = (host_residue + DSP_BUFFER_FRAMES - 1) // DSP_BUFFER_FRAMES
        next_unwrapped_residue = host_residue + HOST_TICK_FRAMES
        next_delivery = (
            next_unwrapped_residue + DSP_BUFFER_FRAMES - 1
        ) // DSP_BUFFER_FRAMES
        delivery_delta = next_delivery - current_delivery
        _require(delivery_delta in {0, 1}, "host/DSP cadence advanced by an impossible block count")
        surviving = tuple(
            sorted(
                (ttl - delivery_delta, activation_perspective)
                for ttl, activation_perspective in active_ttls
                if ttl > delivery_delta
            )
        )
        next_residue = next_unwrapped_residue % DSP_BUFFER_FRAMES
        next_host_frame = initial_phase_frames + (depth + 1) * HOST_TICK_FRAMES
        for transition in machine.transitions[state_id]:
            transition_count += 1
            count = sum(
                emitted_group == group_id
                for emitted_group, _ in transition.emissions
            )
            activation_perspective = machine.state_contexts[
                transition.target_state
            ][0]
            capture_frames = maximum_capture_frames_by_activation_perspective.get(
                activation_perspective
            )
            _require(
                count == 0 or capture_frames is not None,
                "host graph emits a group outside its activation perspective",
            )
            ring_lifetime_blocks = (
                math.ceil(capture_frames / DSP_BUFFER_FRAMES)
                if capture_frames is not None
                else 0
            )
            next_ttls = tuple(
                sorted(
                    surviving
                    + ((ring_lifetime_blocks, activation_perspective),) * count
                )
            )
            add_state(
                (transition.target_state, next_residue, next_ttls),
                parent=state,
                emission_records=tuple(
                    [(next_host_frame, activation_perspective)] * count
                ),
                depth=depth + 1,
            )
    results: dict[tuple[str, str], dict[str, Any]] = {}
    for context in sorted(contexts):
        context_peak = peak_state[context]
        _require(
            context_peak is not None and maximum_live[context] >= 0,
            f"host explorer never reaches {context[0]}/{context[1]}",
        )
        path_states: list[tuple[str, tuple[tuple[int, str], ...], int]] = []
        cursor: tuple[str, int, _ActiveRingState] | None = context_peak
        while cursor is not None:
            parent, emissions, depth = state_records[cursor]
            path_states.append((cursor[0], emissions, depth))
            cursor = parent
        path_states.reverse()
        peak_trigger_records = tuple(
            record
            for _, records, _ in path_states
            for record in records
        )
        results[context] = {
            "maximumLiveRings": maximum_live[context],
            "stateCount": len(state_records),
            "transitionCount": transition_count,
            "analysisHorizonFrames": (
                maximum_depth * HOST_TICK_FRAMES
                + maximum_capture_frames
                + math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES)
            ),
            "peakTriggerHostFrames": tuple(
                frame for frame, _ in peak_trigger_records
            ),
            "peakTriggerHostRecords": peak_trigger_records,
            "peakHostPath": [
                {
                    "stateId": state_id,
                    "hostFrame": initial_phase_frames + depth * HOST_TICK_FRAMES,
                    "selectedPerspective": machine.state_contexts[state_id][0],
                    "programMode": machine.state_contexts[state_id][1],
                    "hostValues": dict(machine.host_values[state_id]),
                    "emissions": [
                        {
                            "groupKey": group_id,
                            "activationPerspective": activation_perspective,
                        }
                        for _, activation_perspective in records
                    ],
                }
                for state_id, records, depth in path_states
            ],
            "peakLiveRingsByActivationPerspective": dict(
                sorted(
                    Counter(
                        perspective
                        for _, perspective in context_peak[2]
                    ).items()
                )
            ),
        }

    return results


def _session_transition_emissions(
    machine: _HostSessionMachine,
    source_state_id: str,
    target_state_id: str,
) -> frozenset[tuple[str, str]]:
    source_membership = machine.memberships[source_state_id]
    target_membership = machine.memberships[target_state_id]
    source_perspective = machine.state_contexts[source_state_id][0]
    target_perspective = machine.state_contexts[target_state_id][0]
    result: set[tuple[str, str]] = set()
    for group in machine.groups:
        reset = (
            group.owner == "selectedPerspectiveEngineEventInstance"
            and source_perspective != target_perspective
        )
        entered = (
            any(target_membership[index] for index in group.binding_indexes)
            if reset
            else any(
                not source_membership[index] and target_membership[index]
                for index in group.binding_indexes
            )
        )
        if entered:
            result.add((group.group_key, "PARAMETER_PLACEMENT_ENTRY"))

    return frozenset(result)


def _explore_session_phase_contexts(
    contract: _PlanContract,
    machine: _HostSessionMachine,
    *,
    initial_phase_frames: int,
) -> dict[tuple[str, str], dict[str, Any]]:
    state_records: dict[
        tuple[str, int, _ActiveSessionRingState, tuple[tuple[str, str], ...]],
        tuple[
            tuple[str, int, _ActiveSessionRingState, tuple[tuple[str, str], ...]] | None,
            tuple[tuple[int, str, str], ...],
            int,
        ],
    ] = {}
    pending: deque[
        tuple[str, int, _ActiveSessionRingState, tuple[tuple[str, str], ...]]
    ] = deque()
    contexts = {
        (perspective, mode)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
    }
    metric_names = (
        "liveRings",
        "ringPoolBytes",
        "finiteFmodChannels",
        "newContributorsPerDspUpdate",
        "newFmodChannelsPerDspUpdate",
    )
    maxima = {
        context: {metric: -1 for metric in metric_names}
        for context in contexts
    }
    peak_states: dict[
        tuple[str, str],
        dict[
            str,
            tuple[str, int, _ActiveSessionRingState, tuple[tuple[str, str], ...]] | None,
        ],
    ] = {
        context: {metric: None for metric in metric_names}
        for context in contexts
    }
    transition_count = 0
    maximum_depth = 0

    def ring_resource_values(
        active_rings: _ActiveSessionRingState,
        update_starts: tuple[tuple[str, str], ...],
    ) -> dict[str, int]:
        ring_bytes = 0
        finite_channels = 0
        for _ttl, activation_perspective, group_key in active_rings:
            group = contract.groups[group_key]
            ring_bytes += (
                group.streaming_ring_frames[activation_perspective]
                * STEREO_FLOAT32_BYTES_PER_FRAME
            )
            finite_channels += group.maximum_fmod_channels[activation_perspective]
        new_contributors = 0
        new_fmod_channels = 0
        for activation_perspective, group_key in update_starts:
            group = contract.groups[group_key]
            new_contributors += group.maximum_contributors[activation_perspective]
            new_fmod_channels += group.maximum_fmod_channels[activation_perspective]

        return {
            "liveRings": len(active_rings),
            "ringPoolBytes": ring_bytes,
            "finiteFmodChannels": finite_channels,
            "newContributorsPerDspUpdate": new_contributors,
            "newFmodChannelsPerDspUpdate": new_fmod_channels,
        }

    def add_state(
        state: tuple[
            str,
            int,
            _ActiveSessionRingState,
            tuple[tuple[str, str], ...],
        ],
        *,
        parent: tuple[
            str,
            int,
            _ActiveSessionRingState,
            tuple[tuple[str, str], ...],
        ] | None,
        emission_records: tuple[tuple[int, str, str], ...],
        depth: int,
    ) -> None:
        nonlocal maximum_depth
        if state in state_records:
            return
        _require(
            len(state_records) < 1_000_000,
            f"session/phase{initial_phase_frames} host state space exceeds the verifier's "
            "fail-closed operational limit",
        )
        state_records[state] = (parent, emission_records, depth)
        pending.append(state)
        maximum_depth = max(maximum_depth, depth)
        context = machine.state_contexts[state[0]]
        values = ring_resource_values(state[2], state[3])
        for metric, value in values.items():
            if value > maxima[context][metric]:
                maxima[context][metric] = value
                peak_states[context][metric] = state

    for state_id, emissions in machine.initial_states:
        activation_perspective = machine.state_contexts[state_id][0]
        rings: list[tuple[int, str, str]] = []
        update_starts: list[tuple[str, str]] = []
        records: list[tuple[int, str, str]] = []
        for group_key, _trigger in emissions:
            group = contract.groups[group_key]
            capture_frames = group.maximum_capture_frames.get(activation_perspective)
            _require(
                capture_frames is not None,
                "global host graph emits a group outside its activation perspective",
            )
            rings.append(
                (
                    math.ceil(capture_frames / DSP_BUFFER_FRAMES),
                    activation_perspective,
                    group_key,
                )
            )
            update_starts.append((activation_perspective, group_key))
            records.append((initial_phase_frames, activation_perspective, group_key))
        add_state(
            (
                state_id,
                initial_phase_frames,
                tuple(sorted(rings)),
                tuple(sorted(update_starts)),
            ),
            parent=None,
            emission_records=tuple(sorted(records)),
            depth=0,
        )
    target_state_ids = tuple(sorted(machine.state_contexts))
    while pending:
        state = pending.popleft()
        state_id, host_residue, active_rings, update_starts = state
        _, _, depth = state_records[state]
        current_delivery = (host_residue + DSP_BUFFER_FRAMES - 1) // DSP_BUFFER_FRAMES
        next_unwrapped_residue = host_residue + HOST_TICK_FRAMES
        next_delivery = (
            next_unwrapped_residue + DSP_BUFFER_FRAMES - 1
        ) // DSP_BUFFER_FRAMES
        delivery_delta = next_delivery - current_delivery
        _require(
            delivery_delta in {0, 1},
            "host/DSP cadence advanced by an impossible block count",
        )
        surviving = tuple(
            sorted(
                (ttl - delivery_delta, activation_perspective, group_key)
                for ttl, activation_perspective, group_key in active_rings
                if ttl > delivery_delta
            )
        )
        next_residue = next_unwrapped_residue % DSP_BUFFER_FRAMES
        next_host_frame = initial_phase_frames + (depth + 1) * HOST_TICK_FRAMES
        for target_state_id in target_state_ids:
            transition_count += 1
            emissions = _session_transition_emissions(
                machine,
                state_id,
                target_state_id,
            )
            activation_perspective = machine.state_contexts[target_state_id][0]
            started_rings: list[tuple[int, str, str]] = []
            started_groups: list[tuple[str, str]] = []
            records: list[tuple[int, str, str]] = []
            for group_key, _trigger in emissions:
                group = contract.groups[group_key]
                capture_frames = group.maximum_capture_frames.get(activation_perspective)
                _require(
                    capture_frames is not None,
                    "global host graph emits a group outside its activation perspective",
                )
                started_rings.append(
                    (
                        math.ceil(capture_frames / DSP_BUFFER_FRAMES),
                        activation_perspective,
                        group_key,
                    )
                )
                started_groups.append((activation_perspective, group_key))
                records.append((next_host_frame, activation_perspective, group_key))
            next_update_starts = tuple(
                sorted(
                    (() if delivery_delta else update_starts)
                    + tuple(started_groups)
                )
            )
            add_state(
                (
                    target_state_id,
                    next_residue,
                    tuple(sorted(surviving + tuple(started_rings))),
                    next_update_starts,
                ),
                parent=state,
                emission_records=tuple(sorted(records)),
                depth=depth + 1,
            )
    maximum_capture_frames = max(
        capture
        for group in contract.groups.values()
        for capture in group.maximum_capture_frames.values()
    )
    results: dict[tuple[str, str], dict[str, Any]] = {}
    for context in sorted(contexts):
        peak = peak_states[context]["ringPoolBytes"]
        _require(
            peak is not None and all(value >= 0 for value in maxima[context].values()),
            f"global host explorer never reaches {context[0]}/{context[1]}",
        )
        path_states: list[
            tuple[str, tuple[tuple[int, str, str], ...], int]
        ] = []
        cursor = peak
        while cursor is not None:
            parent, emissions, depth = state_records[cursor]
            path_states.append((cursor[0], emissions, depth))
            cursor = parent
        path_states.reverse()
        trigger_records = tuple(
            record
            for _state_id, emissions, _depth in path_states
            for record in emissions
        )
        peak_counts = Counter(
            (activation_perspective, group_key)
            for _ttl, activation_perspective, group_key in peak[2]
        )
        results[context] = {
            **maxima[context],
            "stateCount": len(state_records),
            "transitionCount": transition_count,
            "analysisHorizonFrames": (
                maximum_depth * HOST_TICK_FRAMES
                + maximum_capture_frames
                + math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES)
            ),
            "ringPoolPeakTriggerHostRecords": trigger_records,
            "ringPoolPeakHostPath": [
                {
                    "stateId": state_id,
                    "hostFrame": initial_phase_frames + depth * HOST_TICK_FRAMES,
                    "selectedPerspective": machine.state_contexts[state_id][0],
                    "programMode": machine.state_contexts[state_id][1],
                    "hostValues": dict(machine.host_values[state_id]),
                    "emissions": [
                        {
                            "groupKey": group_key,
                            "activationPerspective": activation_perspective,
                        }
                        for _frame, activation_perspective, group_key in emissions
                    ],
                }
                for state_id, emissions, depth in path_states
            ],
            "ringPoolPeakLiveRingsByActivationPerspectiveAndGroup": {
                activation_perspective: {
                    group.group_key: peak_counts.get(
                        (activation_perspective, group.group_key),
                        0,
                    )
                    for group in machine.groups
                }
                for activation_perspective in PERSPECTIVES
            },
        }

    return results


def _minimum_group_reentry_ticks(
    machine: _HostMachine,
    group_id: str,
) -> int | None:
    def emission_count(emissions: frozenset[tuple[str, str]]) -> int:
        return sum(emitted_group == group_id for emitted_group, _ in emissions)

    reachable = {state_id for state_id, _ in machine.initial_states}
    pending = list(reachable)
    while pending:
        state_id = pending.pop()
        for transition in machine.transitions[state_id]:
            if transition.target_state not in reachable:
                reachable.add(transition.target_state)
                pending.append(transition.target_state)
    if any(emission_count(emissions) >= 2 for _, emissions in machine.initial_states):
        return 0
    if any(
        emission_count(transition.emissions) >= 2
        for state_id in reachable
        for transition in machine.transitions[state_id]
    ):
        return 0
    reverse_without_emission: dict[str, list[str]] = {state_id: [] for state_id in reachable}
    distance_to_next_start: dict[str, int] = {}
    queue: deque[str] = deque()
    for state_id in reachable:
        for transition in machine.transitions[state_id]:
            count = emission_count(transition.emissions)
            if count:
                if state_id not in distance_to_next_start:
                    distance_to_next_start[state_id] = 1
                    queue.append(state_id)
            else:
                reverse_without_emission[transition.target_state].append(state_id)
    while queue:
        state_id = queue.popleft()
        next_distance = distance_to_next_start[state_id] + 1
        for predecessor in reverse_without_emission[state_id]:
            if predecessor not in distance_to_next_start:
                distance_to_next_start[predecessor] = next_distance
                queue.append(predecessor)
    candidates: list[int] = []
    for state_id, emissions in machine.initial_states:
        if emission_count(emissions) and state_id in distance_to_next_start:
            candidates.append(distance_to_next_start[state_id])
    for state_id in reachable:
        for transition in machine.transitions[state_id]:
            if emission_count(transition.emissions) and transition.target_state in distance_to_next_start:
                candidates.append(distance_to_next_start[transition.target_state])

    return min(candidates) if candidates else None


def _validate_host_control_reachability(
    contract: _PlanContract,
    reachability: Mapping[str, Any],
    scenarios: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Bind measured FMOD peaks to an exhaustive 200 Hz host-control proof.

    The FMOD probe proves the raw voices that actually exist at each DSP boundary.  It cannot by
    itself prove that a hand-picked drive trace reached the fastest legal re-entry sequence.  This
    companion contract is produced by the host-control state-space explorer.  All 256 initial
    host-tick offsets are mandatory because 240-frame control ticks and 256-frame DSP blocks do not
    stay aligned; the pattern repeats every 3,840 frames.
    """

    _require(reachability.get("schema") == HOST_REACHABILITY_SCHEMA, "host reachability schema differs")
    _require(reachability.get("status") == "PASS", "host reachability is not PASS")
    _validate_named_self_hash(
        reachability,
        "exhaustiveStateSpaceProofSha256",
        "host reachability",
    )
    _require(
        reachability.get("atlasFamilyId") == contract.family_id
        and reachability.get("planSha256") == contract.plan_sha256,
        "host reachability does not match the atlas plan",
    )
    _require(
        reachability.get("sampleRateHz") == SAMPLE_RATE_HZ
        and reachability.get("hostTickFrames") == HOST_TICK_FRAMES
        and reachability.get("dspBufferFrames") == DSP_BUFFER_FRAMES,
        "host reachability cadence differs",
    )
    proof_sha256 = _sha256_string(
        reachability.get("exhaustiveStateSpaceProofSha256"),
        "host reachability proof SHA-256",
    )
    implementation_sha256 = _sha256_string(
        reachability.get("hostControlImplementationSha256"),
        "host-control implementation SHA-256",
    )
    state_graph_sha256 = _sha256_string(
        reachability.get("hostControlStateGraphSha256"),
        "host-control state-graph SHA-256",
    )
    _require(
        reachability.get("stateSpaceExhaustive") is True,
        "host reachability state space is not exhaustive",
    )
    phases = _sequence(
        reachability.get("initialHostPhaseFrames"),
        "host reachability phases",
    )
    _require(
        phases == list(range(DSP_BUFFER_FRAMES)),
        "host reachability does not cover every 240f-to-256f initial phase",
    )
    scenario_by_id = {
        _strict_string(item.get("id"), "causal scenario id"): item
        for item in scenarios
    }
    entries = _sequence(reachability.get("groups"), "host reachability groups")
    expected_entries = {
        (perspective, mode, group_key)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
        for group_key, group in contract.groups.items()
        if group.maximum_capture_frames
    }
    observed_entries: set[tuple[str, str, str]] = set()
    result_entries: list[dict[str, Any]] = []
    reachable_peak_by_perspective_group: dict[tuple[str, str], int] = {}
    for raw_entry in entries:
        entry = _mapping(raw_entry, "host reachability group")
        perspective = _choice(entry.get("perspective"), PERSPECTIVES, "reachability perspective")
        mode = _choice(entry.get("programMode"), PROGRAM_MODES, "reachability program mode")
        event_path = _strict_string(entry.get("eventPath"), "reachability event path")
        group_id = _strict_string(entry.get("groupId"), "reachability group id")
        group_key = scheduling_group_key(event_path, group_id)
        _require(entry.get("groupKey") == group_key, "reachability scheduling-group key differs")
        identity = (perspective, mode, group_key)
        _require(identity in expected_entries, f"host reachability has unknown group/mode {identity}")
        _require(identity not in observed_entries, f"host reachability repeats {identity}")
        observed_entries.add(identity)
        group = contract.groups[group_key]
        triggers = frozenset(
            _strict_string(value, f"{group_id} reachable semantic trigger")
            for value in _sequence(entry.get("semanticTriggers"), f"{group_id} semantic triggers")
        )
        _require(triggers == group.semantic_triggers, f"{identity} semantic-trigger coverage differs")
        phase_peaks = [
            _nonnegative_int(value, f"{identity} phase peak")
            for value in _sequence(
                entry.get("maximumLiveRingsByInitialHostPhase"),
                f"{identity} phase peaks",
            )
        ]
        _require(len(phase_peaks) == DSP_BUFFER_FRAMES, f"{identity} does not report every initial phase")
        maximum_reachable = _nonnegative_int(
            entry.get("maximumReachablePhysicalLiveRings"),
            f"{identity} maximum reachable rings",
        )
        _require(max(phase_peaks, default=0) == maximum_reachable, f"{identity} phase peak disagrees")
        _require(
            _positive_int(entry.get("exhaustiveStateCount"), f"{identity} state count") > 0
            and _positive_int(entry.get("exhaustiveTransitionCount"), f"{identity} transition count") > 0,
            f"{identity} has no exhaustive state transitions",
        )
        _require(
            _positive_int(entry.get("analysisHorizonFrames"), f"{identity} analysis horizon")
            >= max(group.maximum_capture_frames.values())
            + math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES),
            f"{identity} horizon cannot reach steady tail overlap across every clock phase",
        )
        minimum_reentry_ticks = entry.get("minimumHostTicksBetweenStarts")
        if maximum_reachable > 1:
            _nonnegative_int(minimum_reentry_ticks, f"{identity} minimum re-entry ticks")
        elif minimum_reentry_ticks is not None:
            _nonnegative_int(minimum_reentry_ticks, f"{identity} minimum re-entry ticks")
        peak_phase = _nonnegative_int(
            entry.get("peakWitnessInitialHostPhaseFrames"),
            f"{identity} peak witness phase",
        )
        _require(
            peak_phase < DSP_BUFFER_FRAMES
            and phase_peaks[peak_phase] == maximum_reachable,
            f"{identity} peak witness phase does not attain the exhaustive peak",
        )
        peak_trigger_frames = [
            _nonnegative_int(value, f"{identity} peak trigger frame")
            for value in _sequence(
                entry.get("peakWitnessTriggerHostFrames"),
                f"{identity} peak trigger frames",
            )
        ]
        _require(
            peak_trigger_frames == sorted(peak_trigger_frames)
            and all(
                (frame - peak_phase) % HOST_TICK_FRAMES == 0
                for frame in peak_trigger_frames
            )
            and bool(peak_trigger_frames) == (maximum_reachable > 0),
            f"{identity} peak trigger path is not a causal host-tick path",
        )
        peak_trigger_records = [
            _mapping(value, f"{identity} peak trigger record")
            for value in _sequence(
                entry.get("peakWitnessTriggerHostRecords"),
                f"{identity} peak trigger records",
            )
        ]
        _require(
            [
                _nonnegative_int(record.get("hostFrame"), f"{identity} trigger frame")
                for record in peak_trigger_records
            ] == peak_trigger_frames,
            f"{identity} peak trigger records differ from its frame path",
        )
        for record in peak_trigger_records:
            _choice(
                record.get("activationPerspective"),
                PERSPECTIVES,
                f"{identity} trigger activation perspective",
            )
        peak_activation_counts = {
            _choice(key, PERSPECTIVES, f"{identity} peak activation perspective"):
            _positive_int(value, f"{identity} peak activation count")
            for key, value in _mapping(
                entry.get("peakLiveRingsByActivationPerspective"),
                f"{identity} peak live rings by activation perspective",
            ).items()
        }
        _require(
            sum(peak_activation_counts.values()) == maximum_reachable,
            f"{identity} activation-perspective peak does not sum to its live-ring peak",
        )
        peak_host_path = [
            _mapping(value, f"{identity} peak host path state")
            for value in _sequence(
                entry.get("peakWitnessHostPath"),
                f"{identity} peak host path",
            )
        ]
        _require(peak_host_path, f"{identity} peak host path is empty")
        _require(
            [
                _nonnegative_int(state.get("hostFrame"), f"{identity} host path frame")
                for state in peak_host_path
            ]
            == [
                peak_phase + index * HOST_TICK_FRAMES
                for index in range(len(peak_host_path))
            ],
            f"{identity} peak host path does not cover contiguous 200 Hz ticks",
        )
        _require(
            (
                _choice(
                    peak_host_path[-1].get("selectedPerspective"),
                    PERSPECTIVES,
                    f"{identity} final host perspective",
                ),
                _choice(
                    peak_host_path[-1].get("programMode"),
                    PROGRAM_MODES,
                    f"{identity} final host mode",
                ),
            ) == (perspective, mode),
            f"{identity} peak host path does not end in its target context",
        )
        path_trigger_records = [
            {
                "hostFrame": _nonnegative_int(
                    state.get("hostFrame"),
                    f"{identity} host path frame",
                ),
                "activationPerspective": _choice(
                    emission.get("activationPerspective"),
                    PERSPECTIVES,
                    f"{identity} host path activation perspective",
                ),
            }
            for state in peak_host_path
            for emission in (
                _mapping(value, f"{identity} host path emission")
                for value in _sequence(
                    state.get("emissions"),
                    f"{identity} host path emissions",
                )
            )
            if _strict_string(
                emission.get("groupKey"),
                f"{identity} host path emission group",
            ) == group_key
        ]
        _require(
            path_trigger_records == peak_trigger_records,
            f"{identity} peak host path emissions differ from its trigger records",
        )
        witness_ids = [
            _strict_string(value, f"{identity} peak witness scenario")
            for value in _sequence(entry.get("peakWitnessScenarioIds"), f"{identity} peak witnesses")
        ]
        _require(
            bool(witness_ids) == (maximum_reachable > 0),
            f"{identity} peak witness presence differs from reachability",
        )
        exact_peak_path_witnessed = False
        for witness_id in witness_ids:
            witness = scenario_by_id.get(witness_id)
            _require(witness is not None, f"{identity} references unknown witness {witness_id}")
            _require(
                witness.get("perspective") == perspective
                and witness.get("programMode") == mode
                and int(
                    _mapping(
                        witness.get("maximumLiveRingsBySchedulingGroup"),
                        f"{witness_id} ring peaks",
                    ).get(group_key, 0)
                )
                == maximum_reachable,
                f"{identity} witness {witness_id} does not realize its reachable peak",
            )
            witness_trigger_frames = [
                _nonnegative_int(value, f"{witness_id} peak trigger frame")
                for value in _sequence(
                    _mapping(
                        witness.get("ringTriggerHostFramesBySchedulingGroup"),
                        f"{witness_id} trigger map",
                    ).get(group_key, []),
                    f"{witness_id} group trigger frames",
                )
            ]
            witness_trigger_records = list(
                _sequence(
                    _mapping(
                        witness.get("ringTriggerHostRecordsBySchedulingGroup"),
                        f"{witness_id} trigger-record map",
                    ).get(group_key, []),
                    f"{witness_id} group trigger records",
                )
            )
            witness_activation_counts = _mapping(
                _mapping(
                    witness.get(
                        "peakLiveRingsByActivationPerspectiveBySchedulingGroup"
                    ),
                    f"{witness_id} activation peak map",
                ).get(group_key, {}),
                f"{witness_id} group activation peak",
            )
            exact_peak_path_witnessed = exact_peak_path_witnessed or (
                witness.get("initialHostPhaseFrames") == peak_phase
                and witness_trigger_frames == peak_trigger_frames
                and canonical_json_bytes(witness_trigger_records)
                == canonical_json_bytes(peak_trigger_records)
                and dict(witness_activation_counts) == peak_activation_counts
            )
        _require(
            exact_peak_path_witnessed == (maximum_reachable > 0),
            f"{identity} has no FMOD scenario for the exact explored peak path",
        )
        minimum_reentry_witness_ids = [
            _strict_string(value, f"{identity} minimum re-entry witness scenario")
            for value in _sequence(
                entry.get("minimumReentryWitnessScenarioIds"),
                f"{identity} minimum re-entry witnesses",
            )
        ]
        if maximum_reachable > 1:
            _require(
                minimum_reentry_witness_ids,
                f"{identity} has no measured minimum re-entry witness",
            )
            witnessed_tick_gaps: list[int] = []
            for witness_id in minimum_reentry_witness_ids:
                witness = scenario_by_id.get(witness_id)
                _require(witness is not None, f"{identity} references unknown re-entry witness {witness_id}")
                _require(
                    witness.get("perspective") == perspective
                    and witness.get("programMode") == mode,
                    f"{identity} re-entry witness {witness_id} belongs to another mode",
                )
                host_trigger_frames = [
                    _nonnegative_int(value, f"{witness_id} trigger frame")
                    for value in _sequence(
                        _mapping(
                            witness.get("ringTriggerHostFramesBySchedulingGroup"),
                            f"{witness_id} trigger map",
                        ).get(group_key, []),
                        f"{witness_id} group triggers",
                    )
                ]
                witnessed_tick_gaps.extend(
                    (after - before) // HOST_TICK_FRAMES
                    for before, after in zip(host_trigger_frames, host_trigger_frames[1:])
                    if after >= before
                    and (after - before) % HOST_TICK_FRAMES == 0
                )
            _require(
                witnessed_tick_gaps
                and min(witnessed_tick_gaps) == minimum_reentry_ticks,
                f"{identity} does not empirically attain its exhaustive minimum re-entry rate",
            )
        else:
            _require(
                not minimum_reentry_witness_ids,
                f"{identity} reports a re-entry witness for a single/no-start group",
            )
        observed_mode_peak = max(
            (
                int(
                    _mapping(
                        scenario.get("maximumLiveRingsBySchedulingGroup"),
                        f"{scenario.get('id')} ring peaks",
                    ).get(group_key, 0)
                )
                for scenario in scenarios
                if scenario.get("perspective") == perspective
                and scenario.get("programMode") == mode
            ),
            default=0,
        )
        _require(
            observed_mode_peak == maximum_reachable,
            f"{identity} FMOD scenarios do not attain the exhaustive host-control peak",
        )
        key = (perspective, group_key)
        reachable_peak_by_perspective_group[key] = max(
            reachable_peak_by_perspective_group.get(key, 0),
            maximum_reachable,
        )
        result_entries.append(
            {
                "perspective": perspective,
                "programMode": mode,
                "groupKey": group_key,
                "eventPath": event_path,
                "groupId": group_id,
                "semanticTriggers": sorted(triggers),
                "minimumHostTicksBetweenStarts": minimum_reentry_ticks,
                "maximumReachablePhysicalLiveRings": maximum_reachable,
                "peakLiveRingsByActivationPerspective": peak_activation_counts,
                "phasePeakSha256": hashlib.sha256(canonical_json_bytes(phase_peaks)).hexdigest(),
                "peakWitnessScenarioIds": sorted(set(witness_ids)),
                "minimumReentryWitnessScenarioIds": sorted(
                    set(minimum_reentry_witness_ids)
                ),
            }
        )
    _require(observed_entries == expected_entries, "host reachability group/mode matrix is incomplete")
    for (perspective, group_key), reachable_peak in reachable_peak_by_perspective_group.items():
        observed_peak = max(
            (
                int(
                    _mapping(
                        scenario.get("maximumLiveRingsBySchedulingGroup"),
                        f"{scenario.get('id')} ring peaks",
                    ).get(group_key, 0)
                )
                for scenario in scenarios
                if scenario.get("perspective") == perspective
            ),
            default=0,
        )
        _require(
            observed_peak == reachable_peak,
            f"{perspective} group {group_key} causal peak is not witnessed exactly",
        )
    session_contexts = _validate_global_session_reachability(
        contract,
        reachability.get("sessionContexts"),
        scenarios,
    )
    return {
        "schema": HOST_REACHABILITY_SCHEMA,
        "status": "PASS",
        "exhaustiveStateSpaceProofSha256": proof_sha256,
        "hostControlImplementationSha256": implementation_sha256,
        "hostControlStateGraphSha256": state_graph_sha256,
        "initialHostPhaseCount": len(phases),
        "hostDspPhaseCycleFrames": math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES),
        "groups": sorted(
            result_entries,
            key=lambda item: (item["perspective"], item["programMode"], item["groupKey"]),
        ),
        "sessionContexts": session_contexts,
    }


def _validate_global_session_reachability(
    contract: _PlanContract,
    value: object,
    scenarios: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    finite_groups = {
        group_key: group
        for group_key, group in contract.groups.items()
        if group.maximum_capture_frames
    }
    expected_contexts = {
        (perspective, mode)
        for perspective in PERSPECTIVES
        for mode in PROGRAM_MODES
    }
    observed_contexts: set[tuple[str, str]] = set()
    result: list[dict[str, Any]] = []
    for raw_entry in _sequence(value, "global session reachability contexts"):
        entry = _mapping(raw_entry, "global session reachability context")
        perspective = _choice(
            entry.get("perspective"),
            PERSPECTIVES,
            "global session perspective",
        )
        mode = _choice(
            entry.get("programMode"),
            PROGRAM_MODES,
            "global session program mode",
        )
        identity = (perspective, mode)
        _require(
            identity in expected_contexts and identity not in observed_contexts,
            f"global session reachability repeats/introduces context {identity}",
        )
        observed_contexts.add(identity)
        phase_fields = {
            "maximumRingPoolBytesByInitialHostPhase": (
                "maximumReachableSessionRingPoolBytes"
            ),
            "maximumLiveRingsByInitialHostPhase": (
                "maximumReachablePhysicalLiveRings"
            ),
            "maximumFiniteFmodChannelsByInitialHostPhase": (
                "maximumReachableFiniteFmodChannels"
            ),
            "maximumNewContributorsPerDspUpdateByInitialHostPhase": (
                "maximumReachableNewContributorsPerDspUpdate"
            ),
            "maximumNewFmodChannelsPerDspUpdateByInitialHostPhase": (
                "maximumReachableNewFmodChannelsPerDspUpdate"
            ),
        }
        parsed_phase_values: dict[str, list[int]] = {}
        for phase_field, maximum_field in phase_fields.items():
            phase_values = [
                _nonnegative_int(item, f"{identity} {phase_field}")
                for item in _sequence(entry.get(phase_field), f"{identity} {phase_field}")
            ]
            _require(
                len(phase_values) == DSP_BUFFER_FRAMES,
                f"{identity} global session metric does not cover every initial phase",
            )
            maximum = _nonnegative_int(
                entry.get(maximum_field),
                f"{identity} {maximum_field}",
            )
            _require(
                max(phase_values, default=0) == maximum,
                f"{identity} global session phase maximum disagrees for {maximum_field}",
            )
            parsed_phase_values[phase_field] = phase_values
        _require(
            _positive_int(entry.get("exhaustiveStateCount"), f"{identity} state count") > 0
            and _positive_int(
                entry.get("exhaustiveTransitionCount"),
                f"{identity} transition count",
            ) > 0,
            f"{identity} global session exploration has no transitions",
        )
        _require(
            _positive_int(
                entry.get("analysisHorizonFrames"),
                f"{identity} analysis horizon",
            )
            >= max(
                capture
                for group in finite_groups.values()
                for capture in group.maximum_capture_frames.values()
            )
            + math.lcm(HOST_TICK_FRAMES, DSP_BUFFER_FRAMES),
            f"{identity} global session horizon cannot drain its longest finite tail",
        )
        maximum_ring_bytes = _nonnegative_int(
            entry.get("maximumReachableSessionRingPoolBytes"),
            f"{identity} ring pool bytes",
        )
        maximum_live_rings = _nonnegative_int(
            entry.get("maximumReachablePhysicalLiveRings"),
            f"{identity} live rings",
        )
        maximum_finite_channels = _nonnegative_int(
            entry.get("maximumReachableFiniteFmodChannels"),
            f"{identity} finite channels",
        )
        _require(
            maximum_finite_channels <= ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
            f"{identity} exhaustive finite demand exceeds Assetto's real-channel budget",
        )
        peak_phase = _nonnegative_int(
            entry.get("ringPoolPeakWitnessInitialHostPhaseFrames"),
            f"{identity} ring-pool peak phase",
        )
        _require(
            peak_phase < DSP_BUFFER_FRAMES
            and parsed_phase_values["maximumRingPoolBytesByInitialHostPhase"][peak_phase]
            == maximum_ring_bytes,
            f"{identity} ring-pool witness phase does not attain the exhaustive maximum",
        )
        peak_matrix: dict[str, dict[str, int]] = {}
        raw_matrix = _mapping(
            entry.get("ringPoolPeakLiveRingsByActivationPerspectiveAndGroup"),
            f"{identity} ring-pool peak matrix",
        )
        _require(
            set(raw_matrix) == set(PERSPECTIVES),
            f"{identity} ring-pool activation-perspective matrix differs",
        )
        recomputed_bytes = 0
        peak_ring_count = 0
        for activation_perspective in PERSPECTIVES:
            raw_counts = _mapping(
                raw_matrix[activation_perspective],
                f"{identity}/{activation_perspective} ring-pool group counts",
            )
            _require(
                set(raw_counts) == set(finite_groups),
                f"{identity}/{activation_perspective} ring-pool group coverage differs",
            )
            counts: dict[str, int] = {}
            for group_key, group in finite_groups.items():
                count = _nonnegative_int(
                    raw_counts[group_key],
                    f"{identity}/{activation_perspective}/{group_key} ring count",
                )
                frames = group.streaming_ring_frames.get(activation_perspective, 0)
                _require(
                    count == 0 or frames > 0,
                    f"{identity} ring-pool peak activates an unavailable perspective/group",
                )
                counts[group_key] = count
                peak_ring_count += count
                recomputed_bytes += count * frames * STEREO_FLOAT32_BYTES_PER_FRAME
            peak_matrix[activation_perspective] = counts
        _require(
            recomputed_bytes == maximum_ring_bytes
            and peak_ring_count <= maximum_live_rings,
            f"{identity} global session peak matrix does not reproduce its exact pool bound",
        )
        trigger_records = [
            _mapping(item, f"{identity} session trigger record")
            for item in _sequence(
                entry.get("ringPoolPeakTriggerHostRecords"),
                f"{identity} session trigger records",
            )
        ]
        parsed_trigger_records: list[dict[str, Any]] = []
        for record in trigger_records:
            frame = _nonnegative_int(record.get("hostFrame"), f"{identity} trigger frame")
            activation_perspective = _choice(
                record.get("activationPerspective"),
                PERSPECTIVES,
                f"{identity} trigger activation perspective",
            )
            group_key = _strict_string(
                record.get("groupKey"),
                f"{identity} trigger group key",
            )
            _require(
                group_key in finite_groups
                and activation_perspective
                in finite_groups[group_key].maximum_capture_frames
                and (frame - peak_phase) % HOST_TICK_FRAMES == 0,
                f"{identity} session trigger is outside the exact group/cadence contract",
            )
            parsed_trigger_records.append(
                {
                    "hostFrame": frame,
                    "activationPerspective": activation_perspective,
                    "groupKey": group_key,
                }
            )
        _require(
            parsed_trigger_records
            == sorted(
                parsed_trigger_records,
                key=lambda item: (
                    item["hostFrame"],
                    item["activationPerspective"],
                    item["groupKey"],
                ),
            ),
            f"{identity} session trigger records are not canonical",
        )
        host_path = [
            _mapping(item, f"{identity} session host path state")
            for item in _sequence(
                entry.get("ringPoolPeakHostPath"),
                f"{identity} session host path",
            )
        ]
        _require(host_path, f"{identity} session host path is empty")
        _require(
            [
                _nonnegative_int(state.get("hostFrame"), f"{identity} host frame")
                for state in host_path
            ]
            == [
                peak_phase + index * HOST_TICK_FRAMES
                for index in range(len(host_path))
            ],
            f"{identity} session host path is not contiguous at 200 Hz",
        )
        _require(
            (
                _choice(
                    host_path[-1].get("selectedPerspective"),
                    PERSPECTIVES,
                    f"{identity} final perspective",
                ),
                _choice(
                    host_path[-1].get("programMode"),
                    PROGRAM_MODES,
                    f"{identity} final mode",
                ),
            ) == identity,
            f"{identity} session host path ends in another context",
        )
        path_records = sorted(
            (
                {
                    "hostFrame": _nonnegative_int(
                        state.get("hostFrame"),
                        f"{identity} host path frame",
                    ),
                    "activationPerspective": _choice(
                        emission.get("activationPerspective"),
                        PERSPECTIVES,
                        f"{identity} host path activation perspective",
                    ),
                    "groupKey": _strict_string(
                        emission.get("groupKey"),
                        f"{identity} host path group key",
                    ),
                }
                for state in host_path
                for emission in (
                    _mapping(item, f"{identity} host path emission")
                    for item in _sequence(
                        state.get("emissions"),
                        f"{identity} host path emissions",
                    )
                )
            ),
            key=lambda item: (
                item["hostFrame"],
                item["activationPerspective"],
                item["groupKey"],
            ),
        )
        _require(
            path_records == parsed_trigger_records,
            f"{identity} session host path emissions differ from trigger records",
        )
        exact_witness_ids: list[str] = []
        for scenario in scenarios:
            if (
                scenario.get("perspective") != perspective
                or scenario.get("programMode") != mode
                or scenario.get("trajectoryKind") != "host-control-peak"
                or scenario.get("initialHostPhaseFrames") != peak_phase
                or scenario.get("maximumRingPoolBytes") != maximum_ring_bytes
            ):
                continue
            scenario_matrix = _mapping(
                scenario.get("ringPoolPeakLiveRingsByActivationPerspectiveAndGroup"),
                f"{scenario.get('id')} ring-pool matrix",
            )
            if canonical_json_bytes(scenario_matrix) != canonical_json_bytes(peak_matrix):
                continue
            scenario_records = sorted(
                (
                    {
                        "hostFrame": _nonnegative_int(
                            record.get("hostFrame"),
                            f"{scenario.get('id')} trigger frame",
                        ),
                        "activationPerspective": _choice(
                            record.get("activationPerspective"),
                            PERSPECTIVES,
                            f"{scenario.get('id')} trigger activation perspective",
                        ),
                        "groupKey": group_key,
                    }
                    for group_key, raw_records in _mapping(
                        scenario.get("ringTriggerHostRecordsBySchedulingGroup"),
                        f"{scenario.get('id')} trigger records",
                    ).items()
                    for record in (
                        _mapping(item, f"{scenario.get('id')} trigger record")
                        for item in _sequence(
                            raw_records,
                            f"{scenario.get('id')}/{group_key} trigger records",
                        )
                    )
                ),
                key=lambda item: (
                    item["hostFrame"],
                    item["activationPerspective"],
                    item["groupKey"],
                ),
            )
            if scenario_records == parsed_trigger_records:
                exact_witness_ids.append(
                    _strict_string(scenario.get("id"), "global session witness id")
                )
        _require(
            exact_witness_ids,
            f"{identity} has no FMOD scenario for the exact correlated global session peak",
        )
        context_scenarios = [
            scenario
            for scenario in scenarios
            if scenario.get("perspective") == perspective
            and scenario.get("programMode") == mode
            and scenario.get("trajectoryKind") == "host-control-peak"
        ]
        _require(
            context_scenarios
            and max(
                int(scenario.get("maximumRingPoolBytes", -1))
                for scenario in context_scenarios
            ) == maximum_ring_bytes
            and max(
                int(scenario.get("maximumPhysicalLiveLogicalRings", -1))
                for scenario in context_scenarios
            ) == maximum_live_rings
            and max(
                int(scenario.get("maximumFiniteEffectLogicalChannels", -1))
                for scenario in context_scenarios
            ) == maximum_finite_channels
            and max(
                int(
                    scenario.get(
                        "maximumNewFiniteContributorRegionsPerDspUpdate",
                        -1,
                    )
                )
                for scenario in context_scenarios
            )
            == int(entry.get("maximumReachableNewContributorsPerDspUpdate"))
            and max(
                int(
                    scenario.get(
                        "maximumNewFiniteEffectLogicalChannelsPerDspUpdate",
                        -1,
                    )
                )
                for scenario in context_scenarios
            )
            == int(entry.get("maximumReachableNewFmodChannelsPerDspUpdate")),
            f"{identity} measured scenarios do not attain every exhaustive global session bound",
        )
        result.append(
            {
                "perspective": perspective,
                "programMode": mode,
                "maximumReachableSessionRingPoolBytes": maximum_ring_bytes,
                "maximumReachablePhysicalLiveRings": maximum_live_rings,
                "maximumReachableFiniteFmodChannels": maximum_finite_channels,
                "maximumReachableNewContributorsPerDspUpdate": entry.get(
                    "maximumReachableNewContributorsPerDspUpdate"
                ),
                "maximumReachableNewFmodChannelsPerDspUpdate": entry.get(
                    "maximumReachableNewFmodChannelsPerDspUpdate"
                ),
                "ringPoolPeakLiveRingsByActivationPerspectiveAndGroup": peak_matrix,
                "ringPoolPeakWitnessScenarioIds": sorted(exact_witness_ids),
                "phaseMetricSha256": hashlib.sha256(
                    canonical_json_bytes(parsed_phase_values)
                ).hexdigest(),
            }
        )
    _require(
        observed_contexts == expected_contexts,
        "global session reachability context matrix is incomplete",
    )

    return sorted(result, key=lambda item: (item["perspective"], item["programMode"]))


class _PerspectiveAccumulator:
    def __init__(self, contract: _PlanContract, perspective: str) -> None:
        self.contract = contract
        self.perspective = perspective
        self.maximum_live_rings_by_group: dict[str, int] = {
            group_id: 0
            for group_id, group in contract.groups.items()
            if group.maximum_capture_frames
        }
        self.maximum_observed_capture_by_activation_and_group: dict[
            tuple[str, str],
            int,
        ] = {}
        self.maximum_live_ring_total = 0
        self.maximum_ring_pool_bytes = 0
        self.peak_ring_counts_by_activation_and_group: dict[
            tuple[str, str],
            int,
        ] = {}
        self.maximum_mapped_regions = 0
        self.maximum_transition_regions = 0
        self.maximum_system_logical = 0
        self.maximum_system_real = 0
        self.maximum_effect_logical = 0
        self.maximum_effect_real = 0
        self.maximum_engine_logical = 0
        self.maximum_engine_real = 0
        self.maximum_finite_effect_logical = 0
        self.maximum_finite_effect_real = 0
        self.maximum_new_contributors = 0
        self.observed_finite_nodes: set[str] = set()
        self.observed_continuous_nodes: set[str] = set()
        self.observed_groups: set[str] = set()
        self.observed_engine_source_guids: set[str] = set()
        self.real_audible_engine_source_guids: set[str] = set()
        self.statically_silent_engine_source_guids: set[str] = set()
        self.virtual_voice_identities: set[tuple[str, ...]] = set()
        self.real_audible_voice_identities: set[tuple[str, ...]] = set()
        self.statically_silent_voice_identities: set[tuple[str, ...]] = set()
        self.source_binding_oracle_shas: set[str] = set()
        self.virtual_voice_observation_nodes: dict[
            tuple[str, ...],
            set[tuple[str, int]],
        ] = {}
        self.real_audible_voice_observation_nodes: dict[
            tuple[str, ...],
            set[tuple[str, int]],
        ] = {}
        self.maximal_transition_sets: list[frozenset[str]] = []
        self.maximal_session_transition_sets: list[
            frozenset[tuple[str, str]]
        ] = []

    def add_transition_set(self, values: frozenset[str]) -> None:
        if not values:
            return
        if any(values <= existing for existing in self.maximal_transition_sets):
            return
        self.maximal_transition_sets = [
            existing for existing in self.maximal_transition_sets if not existing < values
        ]
        self.maximal_transition_sets.append(values)

    def add_session_transition_set(
        self,
        values: frozenset[tuple[str, str]],
    ) -> None:
        if not values:
            return
        if any(values <= existing for existing in self.maximal_session_transition_sets):
            return
        self.maximal_session_transition_sets = [
            existing
            for existing in self.maximal_session_transition_sets
            if not existing < values
        ]
        self.maximal_session_transition_sets.append(values)


def _evaluate_scenario(
    contract: _PlanContract,
    accumulator: _PerspectiveAccumulator,
    scenario: Mapping[str, Any],
    *,
    identifier: str,
    perspective: str,
    mode: str,
    trajectory_kind: str,
    source_binding_oracles: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    _require(scenario.get("tailDrained") is True, f"{identifier} does not prove finite tail drain")
    initial_host_phase = _nonnegative_int(
        scenario.get("initialHostPhaseFrames"),
        f"{identifier} initial host phase",
    )
    _require(
        initial_host_phase < DSP_BUFFER_FRAMES,
        f"{identifier} initial host phase is outside one DSP block",
    )
    snapshots = [
        _mapping(item, f"{identifier} snapshot")
        for item in _sequence(scenario.get("snapshots"), f"{identifier} snapshots")
    ]
    _require(snapshots, f"{identifier} has no snapshots")
    frames = [
        _nonnegative_int(item.get("afterDspBlockStartFrame"), f"{identifier} snapshot frame")
        for item in snapshots
    ]
    _require(frames[0] == 0, f"{identifier} does not begin at DSP frame zero")
    _require(
        all(after - before == DSP_BUFFER_FRAMES for before, after in zip(frames, frames[1:])),
        f"{identifier} snapshots are not contiguous DSP boundaries",
    )
    ring_definitions: dict[str, dict[str, Any]] = {}
    snapshot_ring_ids: list[set[str]] = []
    current_node_sets: list[frozenset[str]] = []
    current_session_node_sets: list[frozenset[tuple[str, str]]] = []
    current_region_identity_sets: list[frozenset[str]] = []
    snapshot_contexts: list[tuple[str, str]] = []
    scenario_max_logical = 0
    scenario_max_real = 0
    scenario_max_rings = 0
    scenario_max_finite_effect_logical = 0
    scenario_max_new_contributors = 0
    scenario_max_new_finite_effect_logical = 0
    scenario_max_ring_pool_bytes = 0
    scenario_peak_ring_counts_by_activation_and_group: dict[
        tuple[str, str],
        int,
    ] = {}
    scenario_max_rings_by_group: dict[str, int] = {}
    scenario_peak_activation_counts_by_group: dict[str, dict[str, int]] = {}
    previous_ring_ids: set[str] = set()
    voice_definitions: dict[
        str,
        tuple[str, str, str, str | None, str | None, str | None],
    ] = {}
    retired_voice_tokens: set[str] = set()
    previous_voice_tokens: set[str] = set()
    observed_raw_bindings_by_ring: dict[str, set[str]] = {}
    raw_voice_token_by_ring_binding: dict[tuple[str, str], str] = {}
    target_context_seen = False
    for frame, snapshot in zip(frames, snapshots):
        selected_perspective = _choice(
            snapshot.get("selectedPerspective"),
            PERSPECTIVES,
            f"{identifier} selected perspective",
        )
        selected_mode = _choice(
            snapshot.get("programMode"),
            PROGRAM_MODES,
            f"{identifier} selected program mode",
        )
        target_context_seen = target_context_seen or (
            selected_perspective == perspective and selected_mode == mode
        )
        engine_program_throttle = snapshot.get("engineProgramThrottle")
        _require(
            isinstance(engine_program_throttle, (int, float))
            and not isinstance(engine_program_throttle, bool)
            and math.isfinite(float(engine_program_throttle))
            and 0.0 <= float(engine_program_throttle) <= 1.0,
            f"{identifier} engine program throttle is invalid",
        )
        if selected_mode == "LOAD":
            _require(float(engine_program_throttle) == 1.0, f"{identifier} LOAD does not use full load")
        elif selected_mode == "COAST":
            _require(float(engine_program_throttle) == 0.0, f"{identifier} COAST does not use zero load")
        raw_voices = [
            _mapping(item, f"{identifier} voice")
            for item in _sequence(snapshot.get("voices"), f"{identifier} voices")
        ]
        voices: list[_Voice] = []
        for raw_voice in raw_voices:
            kind = _choice(raw_voice.get("kind"), VOICE_KINDS, f"{identifier} voice kind")
            event_path = _strict_string(
                raw_voice.get("eventPath"),
                f"{identifier} voice event path",
            )
            source_guid = _guid(
                _strict_string(
                    raw_voice.get("sourceGuid"),
                    f"{identifier} voice source GUID",
                )
            )
            ring_id = None
            if kind == "effectFinite":
                ring_id = _strict_string(
                    raw_voice.get("logicalRingInstanceId"),
                    f"{identifier} finite voice ring id",
                )
            authored_binding_key = None
            if kind != "engineContinuous":
                authored_binding_key = _authored_binding_key(
                    raw_voice.get("authoredBindingKey"),
                    f"{identifier} effect voice authored binding key",
                )
            activation_perspective = None
            if kind == "engineContinuous":
                activation_perspective = _choice(
                    raw_voice.get("activationPerspective"),
                    PERSPECTIVES,
                    f"{identifier} engine voice activation perspective",
                )
            _validate_voice_source_binding_oracle(
                raw_voice,
                event_path=event_path,
                source_guid=source_guid,
                authored_binding_key=authored_binding_key,
                activation_perspective=activation_perspective,
                source_binding_oracles=source_binding_oracles,
                label=identifier,
            )
            accumulator.source_binding_oracle_shas.add(
                str(raw_voice["sourceBindingOracleSha256"])
            )
            audibility = raw_voice.get("audibility")
            authored_route_gain = raw_voice.get("authoredRouteGain")
            _require(
                isinstance(audibility, (int, float))
                and not isinstance(audibility, bool)
                and math.isfinite(float(audibility))
                and float(audibility) >= 0.0,
                f"{identifier} voice audibility is invalid",
            )
            _require(
                isinstance(authored_route_gain, (int, float))
                and not isinstance(authored_route_gain, bool)
                and math.isfinite(float(authored_route_gain))
                and float(authored_route_gain) >= 0.0,
                f"{identifier} voice authored route gain is invalid",
            )
            is_virtual = raw_voice.get("isVirtual") is True
            _require(
                isinstance(raw_voice.get("isVirtual"), bool),
                f"{identifier} voice virtual state is not boolean",
            )
            static_silence = raw_voice.get("staticSilenceProof")
            statically_silent = False
            if static_silence is not None:
                proof = _mapping(
                    static_silence,
                    f"{identifier} static silence proof",
                )
                proof_sha = proof.get("oracleReportSha256")
                expected_static_fields = {
                    "schema",
                    "status",
                    "scope",
                    "eventPath",
                    "sourceGuid",
                    "maximumAbsoluteAudibility",
                    "maximumAbsoluteRouteGain",
                    "oracleReportSha256",
                }
                if kind != "engineContinuous":
                    expected_static_fields.add("authoredBindingKey")
                _require(
                    set(proof) == expected_static_fields
                    and proof.get("schema") == STATIC_SILENCE_PROOF_SCHEMA
                    and proof.get("status") == "PASS"
                    and proof.get("scope") == "entireAuthoredPlacement"
                    and proof.get("eventPath") == event_path
                    and proof.get("sourceGuid") == source_guid
                    and (
                        kind == "engineContinuous"
                        or proof.get("authoredBindingKey") == authored_binding_key
                    )
                    and proof.get("maximumAbsoluteAudibility") == 0.0
                    and proof.get("maximumAbsoluteRouteGain") == 0.0
                    and isinstance(proof_sha, str)
                    and len(proof_sha) == 64
                    and all(character in "0123456789abcdef" for character in proof_sha),
                    f"{identifier} static silence proof is not exact",
                )
                statically_silent = True
            if is_virtual:
                virtualization = _mapping(
                    raw_voice.get("virtualizationProof"),
                    f"{identifier} virtual voice proof",
                )
                expected_virtual_fields = {
                    "schema",
                    "eventPath",
                    "sourceGuid",
                    "isVirtualReportedByFmod",
                    "measuredAudibility",
                    "measuredAuthoredRouteGain",
                    "audibilityEpsilon",
                    "authoredRouteGainEpsilon",
                }
                identity_field = (
                    "activationPerspective"
                    if kind == "engineContinuous"
                    else "authoredBindingKey"
                )
                expected_virtual_fields.add(identity_field)
                _require(
                    set(virtualization) == expected_virtual_fields
                    and virtualization.get("schema") == VIRTUALIZATION_PROOF_SCHEMA
                    and virtualization.get("eventPath") == event_path
                    and virtualization.get("sourceGuid") == source_guid
                    and virtualization.get(identity_field)
                    == (
                        activation_perspective
                        if kind == "engineContinuous"
                        else authored_binding_key
                    )
                    and virtualization.get("isVirtualReportedByFmod") is True
                    and virtualization.get("measuredAudibility") == float(audibility)
                    and virtualization.get("measuredAuthoredRouteGain")
                    == float(authored_route_gain)
                    and virtualization.get("audibilityEpsilon")
                    == VIRTUAL_INAUDIBILITY_EPSILON
                    and virtualization.get("authoredRouteGainEpsilon")
                    == VIRTUAL_INAUDIBILITY_EPSILON
                    and float(audibility) <= VIRTUAL_INAUDIBILITY_EPSILON
                    and float(authored_route_gain)
                    <= VIRTUAL_INAUDIBILITY_EPSILON,
                    f"{identifier} virtual voice is not proven inaudible at its exact node",
                )
            else:
                _require(
                    raw_voice.get("virtualizationProof") is None,
                    f"{identifier} real voice carries a virtual-only proof",
                )
            voice = _Voice(
                token=_strict_string(raw_voice.get("voiceToken"), f"{identifier} voice token"),
                kind=kind,
                source_guid=source_guid,
                event_path=event_path,
                ring_id=ring_id,
                authored_binding_key=authored_binding_key,
                activation_perspective=activation_perspective,
                is_virtual=is_virtual,
                audibility=float(audibility),
                authored_route_gain=float(authored_route_gain),
                statically_silent=statically_silent,
            )
            voices.append(voice)
        logical = _nonnegative_int(snapshot.get("systemLogicalChannels"), f"{identifier} logical channels")
        real = _nonnegative_int(snapshot.get("systemRealChannels"), f"{identifier} real channels")
        _require(logical == len(voices), f"{identifier} voice enumeration differs from system logical count")
        real_voices = sum(1 for voice in voices if not voice.is_virtual)
        _require(real == real_voices, f"{identifier} voice enumeration differs from system real count")
        _require(
            logical <= ASSETTO_LOGICAL_CHANNEL_CAP
            and real <= ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
            and real <= logical,
            f"{identifier} exceeds Assetto's logical/real channel budgets",
        )
        tokens = {voice.token for voice in voices}
        _require(len(tokens) == len(voices), f"{identifier} repeats a voice token in one snapshot")
        _require(
            not (tokens & retired_voice_tokens),
            f"{identifier} reuses a retired voice token generation",
        )
        retired_voice_tokens.update(previous_voice_tokens - tokens)
        previous_voice_tokens = tokens
        effect_logical = 0
        effect_real = 0
        finite_effect_logical = 0
        finite_effect_real = 0
        for voice in voices:
            definition = (
                voice.kind,
                voice.event_path,
                voice.source_guid,
                voice.ring_id,
                voice.authored_binding_key,
                voice.activation_perspective,
            )
            prior_definition = voice_definitions.setdefault(voice.token, definition)
            _require(
                prior_definition == definition,
                f"{identifier} voice token {voice.token} changes source/ring identity",
            )
            voice_identity = (
                voice.kind,
                voice.event_path,
                voice.source_guid,
                voice.authored_binding_key or "",
                voice.activation_perspective or "",
            )
            observation_node = (identifier, frame)
            if voice.is_virtual:
                accumulator.virtual_voice_identities.add(voice_identity)
                accumulator.virtual_voice_observation_nodes.setdefault(
                    voice_identity,
                    set(),
                ).add(observation_node)
                if voice.statically_silent:
                    accumulator.statically_silent_voice_identities.add(
                        voice_identity
                    )
            elif (
                voice.audibility > VIRTUAL_INAUDIBILITY_EPSILON
                and voice.authored_route_gain > VIRTUAL_INAUDIBILITY_EPSILON
            ):
                accumulator.real_audible_voice_identities.add(voice_identity)
                accumulator.real_audible_voice_observation_nodes.setdefault(
                    voice_identity,
                    set(),
                ).add(observation_node)
            if (
                voice.kind == "engineContinuous"
                and voice.activation_perspective == accumulator.perspective
            ):
                accumulator.observed_engine_source_guids.add(voice.source_guid)
                if voice.statically_silent:
                    accumulator.statically_silent_engine_source_guids.add(
                        voice.source_guid
                    )
                elif (
                    not voice.is_virtual
                    and voice.audibility > VIRTUAL_INAUDIBILITY_EPSILON
                    and voice.authored_route_gain > VIRTUAL_INAUDIBILITY_EPSILON
                ):
                    accumulator.real_audible_engine_source_guids.add(
                        voice.source_guid
                    )
            if voice.kind != "engineContinuous":
                effect_logical += 1
                effect_real += int(not voice.is_virtual)
            if voice.kind == "effectFinite":
                finite_effect_logical += 1
                finite_effect_real += int(not voice.is_virtual)

        engine_voices = [voice for voice in voices if voice.kind == "engineContinuous"]
        raw_engine_sources = _mapping(
            snapshot.get("engineActiveSourceGuidsByActivationPerspective"),
            f"{identifier} active engine sources by activation perspective",
        )
        _require(
            set(raw_engine_sources) == set(PERSPECTIVES),
            f"{identifier} engine activation-perspective source matrix differs",
        )
        engine_active_sources = {
            activation_perspective: [
                _guid(_strict_string(value, f"{identifier} active engine source"))
                for value in _sequence(
                    raw_engine_sources[activation_perspective],
                    f"{identifier} {activation_perspective} active engine sources",
                )
            ]
            for activation_perspective in PERSPECTIVES
        }
        _require(
            Counter(
                (voice.activation_perspective, voice.source_guid)
                for voice in engine_voices
            )
            == Counter(
                (activation_perspective, source_guid)
                for activation_perspective, source_guids in engine_active_sources.items()
                for source_guid in source_guids
            ),
            f"{identifier} engine raw voice multiset differs from its callback-proven active set",
        )
        for activation_perspective, source_guids in engine_active_sources.items():
            _require(
                len(source_guids)
                <= contract.engine_fmod_channel_bound[activation_perspective]
                and set(source_guids)
                <= contract.engine_source_guids[activation_perspective],
                f"{identifier} {activation_perspective} engine active set exceeds/substitutes its plan source coverage",
            )
        for voice in engine_voices:
            assert voice.activation_perspective is not None
            _require(
                voice.event_path
                == contract.engine_event_paths[voice.activation_perspective]
                and voice.source_guid
                in contract.engine_source_guids[voice.activation_perspective],
                f"{identifier} substitutes an unknown engine source",
            )

        continuous_keys = frozenset(
            str(value)
            for value in _sequence(
                snapshot.get("continuousEffectNodeKeys"),
                f"{identifier} continuous effect nodes",
            )
        )
        _require(len(continuous_keys) == len(_sequence(snapshot.get("continuousEffectNodeKeys"), f"{identifier} continuous effect nodes")), f"{identifier} repeats a continuous node")
        _require(
            continuous_keys <= contract.continuous_nodes[selected_perspective],
            f"{identifier} maps an unknown/inactive-perspective continuous node",
        )
        _require(
            len(continuous_keys)
            <= contract.continuous_region_bound[selected_perspective],
            f"{identifier} exceeds the compiled continuous-corner bound",
        )
        continuous_voice_bindings = Counter(
            voice.authored_binding_key
            for voice in voices
            if voice.kind == "effectContinuous"
        )
        expected_continuous_voice_bindings = Counter(
            {
                contract.nodes[key].authored_binding_key
                for key in continuous_keys
            }
        )
        _require(
            continuous_voice_bindings == expected_continuous_voice_bindings,
            f"{identifier} continuous raw voice multiset does not match the exact active authored bindings",
        )
        for voice in voices:
            if voice.kind != "effectContinuous":
                continue
            assert voice.authored_binding_key is not None
            binding_nodes = [
                contract.nodes[key]
                for key in continuous_keys
                if contract.nodes[key].authored_binding_key
                == voice.authored_binding_key
            ]
            _require(
                binding_nodes
                and all(node.lifetime == "continuous" for node in binding_nodes)
                and all(node.event_path == voice.event_path for node in binding_nodes)
                and all(node.source_guid == voice.source_guid for node in binding_nodes),
                f"{identifier} continuous voice substitutes its exact contributor binding",
            )
        _require(
            sum(voice.kind == "effectContinuous" for voice in voices)
            <= contract.continuous_fmod_channel_bound[selected_perspective],
            f"{identifier} exceeds the compiled continuous FMOD channel bound",
        )
        if selected_perspective == perspective:
            accumulator.observed_continuous_nodes.update(continuous_keys)

        retained_continuous_raw = _mapping(
            snapshot.get("retainedContinuousEffectNodeKeysByActivationPerspective"),
            f"{identifier} retained continuous effect nodes",
        )
        _require(
            set(retained_continuous_raw) == set(PERSPECTIVES),
            f"{identifier} retained effect participant matrix differs",
        )
        retained_continuous_by_activation: dict[str, frozenset[str]] = {}
        for activation_perspective in PERSPECTIVES:
            raw_keys = _sequence(
                retained_continuous_raw[activation_perspective],
                f"{identifier} {activation_perspective} retained continuous nodes",
            )
            keys = frozenset(str(value) for value in raw_keys)
            _require(
                len(keys) == len(raw_keys)
                and keys <= contract.continuous_nodes[activation_perspective]
                and len(keys)
                <= contract.continuous_region_bound[activation_perspective],
                f"{identifier} {activation_perspective} retained continuous hot set differs/exceeds its bound",
            )
            retained_continuous_by_activation[activation_perspective] = keys

        rings = [_mapping(item, f"{identifier} finite ring") for item in _sequence(snapshot.get("finiteLogicalRings"), f"{identifier} finite rings")]
        ring_ids: set[str] = set()
        mapped_keys = set(continuous_keys)
        mapped_region_identities = {f"continuous:{key}" for key in continuous_keys}
        session_mapped_keys = {
            (activation_perspective, key)
            for activation_perspective, keys
            in retained_continuous_by_activation.items()
            for key in keys
        }
        ring_voices: dict[str, list[_Voice]] = {}
        for voice in voices:
            if voice.kind == "effectFinite":
                assert voice.ring_id is not None
                ring_voices.setdefault(voice.ring_id, []).append(voice)
        group_counts: dict[str, int] = {}
        activation_group_counts: Counter[tuple[str, str]] = Counter()
        for ring in rings:
            ring_id = _nonempty_string(ring.get("instanceId"), f"{identifier} ring id")
            _require(ring_id not in ring_ids, f"{identifier} repeats finite ring {ring_id}")
            ring_ids.add(ring_id)
            event_path = _nonempty_string(ring.get("eventPath"), f"{identifier} ring event")
            group_id = _nonempty_string(ring.get("groupId"), f"{identifier} ring group")
            group_key = scheduling_group_key(event_path, group_id)
            _require(
                ring.get("groupKey") == group_key,
                f"{identifier} ring scheduling-group key differs",
            )
            group = contract.groups.get(group_key)
            activation_perspective = _choice(
                ring.get("activationPerspective"),
                PERSPECTIVES,
                f"{identifier} ring activation perspective",
            )
            _require(
                group is not None
                and group.event_path == event_path
                and activation_perspective in group.perspectives,
                f"{identifier} ring references an unknown scheduling group",
            )
            start = _nonnegative_int(ring.get("startFrame"), f"{identifier} ring start")
            end = _positive_int(ring.get("endFrameExclusive"), f"{identifier} ring end")
            _require(start % DSP_BUFFER_FRAMES == 0 and start <= frame < end, f"{identifier} ring lifetime is not causal at frame {frame}")
            trigger_host_frame = _nonnegative_int(
                ring.get("triggerHostFrame"),
                f"{identifier} ring trigger host frame",
            )
            _require(
                (trigger_host_frame - initial_host_phase) % HOST_TICK_FRAMES == 0
                and start == (
                    (trigger_host_frame + DSP_BUFFER_FRAMES - 1)
                    // DSP_BUFFER_FRAMES
                    * DSP_BUFFER_FRAMES
                ),
                f"{identifier} ring trigger is not delivered on its first causal DSP boundary",
            )
            contributors = tuple(str(value) for value in _sequence(ring.get("contributorNodeKeys"), f"{identifier} ring contributors"))
            contributor_set = frozenset(contributors)
            _require(contributors and len(contributor_set) == len(contributors), f"{identifier} ring contributors are empty/duplicated")
            _require(
                contributor_set <= contract.finite_nodes[activation_perspective],
                f"{identifier} ring has an unknown finite contributor",
            )
            _require(
                all(contract.nodes[key].group_key == group_key for key in contributor_set),
                f"{identifier} ring mixes contributors from another group",
            )
            _require(
                len(contributors)
                <= group.maximum_contributors[activation_perspective],
                f"{identifier} ring exceeds its N-D contributor bound",
            )
            exact_end = start + max(contract.nodes[key].duration_frames for key in contributor_set)
            _require(end == exact_end, f"{identifier} ring does not retain its longest exact captured contributor")
            _require(
                end - start
                <= group.maximum_capture_frames[activation_perspective],
                f"{identifier} ring exceeds its captured-tail bound",
            )
            definition = {
                "eventPath": event_path,
                "groupKey": group_key,
                "groupId": group_id,
                "activationPerspective": activation_perspective,
                "startFrame": start,
                "endFrameExclusive": end,
                "triggerHostFrame": trigger_host_frame,
                "contributorNodeKeys": tuple(sorted(contributor_set)),
            }
            prior = ring_definitions.setdefault(ring_id, definition)
            _require(prior == definition, f"{identifier} ring {ring_id} changes identity/lifetime")
            raw_ring_voices = ring_voices.get(ring_id, [])
            raw_count = len(raw_ring_voices)
            _require(
                raw_count > 0
                and raw_count
                <= group.maximum_fmod_channels[activation_perspective],
                f"{identifier} ring raw FMOD multiplicity is absent or exceeds proof",
            )
            contributors_by_binding: dict[str, list[_Node]] = {}
            for key in contributor_set:
                contributor = contract.nodes[key]
                contributors_by_binding.setdefault(
                    contributor.authored_binding_key,
                    [],
                ).append(contributor)
            expected_raw_bindings = Counter(
                binding_key
                for binding_key, binding_nodes in contributors_by_binding.items()
                if frame < start + max(node.duration_frames for node in binding_nodes)
            )
            raw_bindings = Counter(
                voice.authored_binding_key
                for voice in raw_ring_voices
            )
            _require(
                raw_bindings == expected_raw_bindings,
                f"{identifier} ring {ring_id} raw voice multiset differs from its causally active authored bindings",
            )
            for voice in raw_ring_voices:
                assert voice.authored_binding_key is not None
                binding_nodes = contributors_by_binding.get(voice.authored_binding_key, [])
                _require(
                    binding_nodes
                    and all(node.event_path == voice.event_path for node in binding_nodes)
                    and all(node.source_guid == voice.source_guid for node in binding_nodes)
                    and all(node.group_key == group_key for node in binding_nodes),
                    f"{identifier} ring {ring_id} substitutes its exact contributor binding",
                )
                token_key = (ring_id, voice.authored_binding_key)
                prior_token = raw_voice_token_by_ring_binding.setdefault(
                    token_key,
                    voice.token,
                )
                _require(
                    prior_token == voice.token,
                    f"{identifier} ring {ring_id} replaces a contributor voice before its captured end",
                )
            observed_raw_bindings_by_ring.setdefault(ring_id, set()).update(raw_bindings)
            group_counts[group_key] = group_counts.get(group_key, 0) + 1
            activation_group_counts[(activation_perspective, group_key)] += 1
            mapped_keys.update(contributor_set)
            session_mapped_keys.update(
                (activation_perspective, key)
                for key in contributor_set
            )
            mapped_region_identities.update(
                f"finite:{ring_id}:{key}" for key in contributor_set
            )
            if selected_perspective == perspective:
                accumulator.observed_finite_nodes.update(contributor_set)
                accumulator.observed_groups.add(group_key)
                capture_key = (activation_perspective, group_key)
                accumulator.maximum_observed_capture_by_activation_and_group[capture_key] = max(
                    accumulator.maximum_observed_capture_by_activation_and_group.get(
                        capture_key,
                        0,
                    ),
                    end - start,
                )
        _require(set(ring_voices) == ring_ids, f"{identifier} finite raw voices and logical rings do not reconcile")
        new_ring_ids = ring_ids - previous_ring_ids
        new_contributors_by_activation_perspective = Counter(
            {
                activation_perspective: sum(
                    len(ring_definitions[ring_id]["contributorNodeKeys"])
                    for ring_id in new_ring_ids
                    if ring_definitions[ring_id]["activationPerspective"]
                    == activation_perspective
                )
                for activation_perspective in PERSPECTIVES
            }
        )
        for activation_perspective, contributor_count in (
            new_contributors_by_activation_perspective.items()
        ):
            _require(
                contributor_count
                <= contract.finite_materialization_bound[activation_perspective],
                f"{identifier} starts more {activation_perspective} finite contributors in one DSP update than compiled",
            )
        new_contributors = sum(new_contributors_by_activation_perspective.values())
        new_finite_effect_logical = sum(
            1
            for voice in voices
            if voice.kind == "effectFinite" and voice.ring_id in new_ring_ids
        )
        if selected_perspective == perspective:
            accumulator.maximum_new_contributors = max(
                accumulator.maximum_new_contributors,
                new_contributors,
            )
        previous_ring_ids = ring_ids
        current_nodes = frozenset(mapped_keys)
        current_node_sets.append(current_nodes)
        current_session_node_sets.append(frozenset(session_mapped_keys))
        current_region_identity_sets.append(frozenset(mapped_region_identities))
        snapshot_contexts.append((selected_perspective, selected_mode))
        snapshot_ring_ids.append(ring_ids)
        if selected_perspective == perspective:
            for group_key, count in group_counts.items():
                accumulator.maximum_live_rings_by_group[group_key] = max(
                    accumulator.maximum_live_rings_by_group.get(group_key, 0),
                    count,
                )
            accumulator.maximum_live_ring_total = max(
                accumulator.maximum_live_ring_total,
                len(ring_ids),
            )
            accumulator.maximum_mapped_regions = max(
                accumulator.maximum_mapped_regions,
                len(mapped_region_identities),
            )
            accumulator.maximum_system_logical = max(accumulator.maximum_system_logical, logical)
            accumulator.maximum_system_real = max(accumulator.maximum_system_real, real)
            accumulator.maximum_effect_logical = max(accumulator.maximum_effect_logical, effect_logical)
            accumulator.maximum_effect_real = max(accumulator.maximum_effect_real, effect_real)
            accumulator.maximum_engine_logical = max(
                accumulator.maximum_engine_logical,
                len(engine_voices),
            )
            accumulator.maximum_engine_real = max(
                accumulator.maximum_engine_real,
                sum(not voice.is_virtual for voice in engine_voices),
            )
            accumulator.maximum_finite_effect_logical = max(accumulator.maximum_finite_effect_logical, finite_effect_logical)
            accumulator.maximum_finite_effect_real = max(accumulator.maximum_finite_effect_real, finite_effect_real)
            ring_pool_bytes = sum(
                count
                * contract.groups[group_key].streaming_ring_frames[activation_perspective]
                * STEREO_FLOAT32_BYTES_PER_FRAME
                for (activation_perspective, group_key), count
                in activation_group_counts.items()
            )
            candidate_counts = dict(sorted(activation_group_counts.items()))
            if (
                ring_pool_bytes > accumulator.maximum_ring_pool_bytes
                or (
                    ring_pool_bytes == accumulator.maximum_ring_pool_bytes
                    and sorted(candidate_counts.items())
                    < sorted(accumulator.peak_ring_counts_by_activation_and_group.items())
                )
            ):
                accumulator.maximum_ring_pool_bytes = ring_pool_bytes
                accumulator.peak_ring_counts_by_activation_and_group = candidate_counts
        if selected_perspective == perspective and selected_mode == mode:
            scenario_max_logical = max(scenario_max_logical, logical)
            scenario_max_real = max(scenario_max_real, real)
            scenario_max_rings = max(scenario_max_rings, len(ring_ids))
            scenario_max_finite_effect_logical = max(
                scenario_max_finite_effect_logical,
                finite_effect_logical,
            )
            scenario_max_new_contributors = max(
                scenario_max_new_contributors,
                new_contributors,
            )
            scenario_max_new_finite_effect_logical = max(
                scenario_max_new_finite_effect_logical,
                new_finite_effect_logical,
            )
            if (
                ring_pool_bytes > scenario_max_ring_pool_bytes
                or (
                    ring_pool_bytes == scenario_max_ring_pool_bytes
                    and sorted(candidate_counts.items())
                    < sorted(
                        scenario_peak_ring_counts_by_activation_and_group.items()
                    )
                )
            ):
                scenario_max_ring_pool_bytes = ring_pool_bytes
                scenario_peak_ring_counts_by_activation_and_group = candidate_counts
            for group_key, count in group_counts.items():
                prior = scenario_max_rings_by_group.get(group_key, -1)
                activation_counts = {
                    activation_perspective: activation_group_counts[
                        (activation_perspective, group_key)
                    ]
                    for activation_perspective in PERSPECTIVES
                    if activation_group_counts[(activation_perspective, group_key)]
                }
                if count > prior:
                    scenario_max_rings_by_group[group_key] = count
                    scenario_peak_activation_counts_by_group[group_key] = activation_counts

    # Second pass proves that a ring appears on every sampled DSP boundary of its exact captured
    # lifetime.  A missing middle boundary would otherwise undercount physical ring memory.
    for index, frame in enumerate(frames):
        expected = {
            ring_id
            for ring_id, definition in ring_definitions.items()
            if definition["startFrame"] <= frame < definition["endFrameExclusive"]
        }
        _require(snapshot_ring_ids[index] == expected, f"{identifier} drops/reappears a finite tail at frame {frame}")
    _require(not snapshot_ring_ids[-1], f"{identifier} final snapshot still owns finite rings")
    if ring_definitions:
        _require(frames[-1] >= max(item["endFrameExclusive"] for item in ring_definitions.values()), f"{identifier} stops before its final finite tail")
    for ring_id, definition in ring_definitions.items():
        expected_raw_bindings = {
            contract.nodes[key].authored_binding_key
            for key in definition["contributorNodeKeys"]
        }
        _require(
            observed_raw_bindings_by_ring.get(ring_id, set())
            == expected_raw_bindings,
            f"{identifier} ring {ring_id} never exposed every selected raw authored binding",
        )

    _require(
        target_context_seen,
        f"{identifier} never reaches its declared target perspective/program mode",
    )
    previous_nodes = frozenset()
    previous_session_nodes: frozenset[tuple[str, str]] = frozenset()
    previous_regions = frozenset()
    for current_nodes, current_session_nodes, current_regions, current_context in zip(
        current_node_sets,
        current_session_node_sets,
        current_region_identity_sets,
        snapshot_contexts,
    ):
        if current_context[0] == perspective:
            transition_regions = previous_regions | current_regions
            accumulator.maximum_transition_regions = max(
                accumulator.maximum_transition_regions,
                len(transition_regions),
            )
            accumulator.add_transition_set(previous_nodes | current_nodes)
            accumulator.add_session_transition_set(
                previous_session_nodes | current_session_nodes
            )
        previous_nodes = current_nodes
        previous_session_nodes = current_session_nodes
        previous_regions = current_regions

    return {
        "id": identifier,
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": trajectory_kind,
        "dspSnapshotCount": len(snapshots),
        "maximumSystemLogicalChannels": scenario_max_logical,
        "maximumSystemRealChannels": scenario_max_real,
        "maximumPhysicalLiveLogicalRings": scenario_max_rings,
        "maximumFiniteEffectLogicalChannels": scenario_max_finite_effect_logical,
        "maximumNewFiniteContributorRegionsPerDspUpdate": (
            scenario_max_new_contributors
        ),
        "maximumNewFiniteEffectLogicalChannelsPerDspUpdate": (
            scenario_max_new_finite_effect_logical
        ),
        "maximumRingPoolBytes": scenario_max_ring_pool_bytes,
        "ringPoolPeakLiveRingsByActivationPerspectiveAndGroup": {
            activation_perspective: {
                group_key: scenario_peak_ring_counts_by_activation_and_group.get(
                    (activation_perspective, group_key),
                    0,
                )
                for group_key, group in sorted(contract.groups.items())
                if group.maximum_capture_frames
            }
            for activation_perspective in PERSPECTIVES
        },
        "finiteRingInstanceCount": len(ring_definitions),
        "maximumLiveRingsBySchedulingGroup": {
            key: scenario_max_rings_by_group[key]
            for key in sorted(scenario_max_rings_by_group)
        },
        "peakLiveRingsByActivationPerspectiveBySchedulingGroup": {
            group_key: scenario_peak_activation_counts_by_group[group_key]
            for group_key in sorted(scenario_peak_activation_counts_by_group)
        },
        "ringStartFramesBySchedulingGroup": {
            group_key: sorted(
                definition["startFrame"]
                for definition in ring_definitions.values()
                if definition["groupKey"] == group_key
            )
            for group_key in sorted({
                str(definition["groupKey"])
                for definition in ring_definitions.values()
            })
        },
        "ringTriggerHostFramesBySchedulingGroup": {
            group_key: sorted(
                definition["triggerHostFrame"]
                for definition in ring_definitions.values()
                if definition["groupKey"] == group_key
            )
            for group_key in sorted({
                str(definition["groupKey"])
                for definition in ring_definitions.values()
            })
        },
        "ringTriggerHostRecordsBySchedulingGroup": {
            group_key: [
                {
                    "hostFrame": definition["triggerHostFrame"],
                    "activationPerspective": definition["activationPerspective"],
                }
                for definition in sorted(
                    (
                        definition
                        for definition in ring_definitions.values()
                        if definition["groupKey"] == group_key
                    ),
                    key=lambda item: (
                        item["triggerHostFrame"],
                        item["activationPerspective"],
                    ),
                )
            ]
            for group_key in sorted({
                str(definition["groupKey"])
                for definition in ring_definitions.values()
            })
        },
        "initialHostPhaseFrames": initial_host_phase,
        "tailDrained": True,
        "pass": True,
    }


def _source_binding_oracle_registry(
    value: object,
) -> dict[str, Mapping[str, Any]]:
    registry = _mapping(value, "source-binding oracle registry")
    _require(registry, "source-binding oracle registry is empty")
    parsed: dict[str, Mapping[str, Any]] = {}
    for raw_sha, raw_evidence in registry.items():
        oracle_sha = _sha256_string(raw_sha, "source-binding oracle SHA-256")
        evidence = _mapping(raw_evidence, f"source-binding oracle {oracle_sha}")
        _require(
            hashlib.sha256(canonical_json_bytes(evidence)).hexdigest()
            == oracle_sha,
            f"source-binding oracle {oracle_sha} canonical hash differs",
        )
        schema = _choice(
            evidence.get("schema"),
            (
                SOURCE_SOLO_BINDING_ORACLE_SCHEMA,
                REALIZATION_BINDING_ORACLE_SCHEMA,
            ),
            f"source-binding oracle {oracle_sha} schema",
        )
        _strict_string(
            evidence.get("eventPath"),
            f"source-binding oracle {oracle_sha} event path",
        )
        _guid(
            _strict_string(
                evidence.get("sourceGuid"),
                f"source-binding oracle {oracle_sha} source GUID",
            )
        )
        if schema == SOURCE_SOLO_BINDING_ORACLE_SCHEMA:
            expected_fields = {
                "schema",
                "originalBankSha256",
                "graphSha256",
                "eventPath",
                "sourceGuid",
                "authoredBindingKey",
                "activationPerspective",
                "callbackSoundIdentity",
                "parameters",
                "mutedReachableWaveformGuids",
                "disabledFullyMutedParentGuids",
                "derivativeBankSha256",
                "derivativeDifferingByteOffsets",
                "scheduledSoundIdentities",
                "originalBankUnchangedAfterProbe",
            }
            _require(
                set(evidence) == expected_fields,
                f"source-binding oracle {oracle_sha} source-solo fields differ",
            )
            for field in (
                "originalBankSha256",
                "graphSha256",
                "derivativeBankSha256",
            ):
                _sha256_string(
                    evidence.get(field),
                    f"source-binding oracle {oracle_sha} {field}",
                )
            callback_name = _strict_string(
                evidence.get("callbackSoundIdentity"),
                f"source-binding oracle {oracle_sha} callback identity",
            )
            scheduled = [
                _strict_string(
                    item,
                    f"source-binding oracle {oracle_sha} scheduled identity",
                )
                for item in _sequence(
                    evidence.get("scheduledSoundIdentities"),
                    f"source-binding oracle {oracle_sha} scheduled identities",
                )
            ]
            _require(
                scheduled and set(scheduled) == {callback_name},
                f"source-binding oracle {oracle_sha} is source-contaminated",
            )
            parameters = _mapping(
                evidence.get("parameters"),
                f"source-binding oracle {oracle_sha} parameters",
            )
            for parameter, parameter_value in parameters.items():
                _strict_string(
                    parameter,
                    f"source-binding oracle {oracle_sha} parameter",
                )
                _finite_number(
                    parameter_value,
                    f"source-binding oracle {oracle_sha} parameter value",
                )
            for field in (
                "mutedReachableWaveformGuids",
                "disabledFullyMutedParentGuids",
            ):
                values = [
                    _guid(
                        _strict_string(
                            item,
                            f"source-binding oracle {oracle_sha} {field}",
                        )
                    )
                    for item in _sequence(
                        evidence.get(field),
                        f"source-binding oracle {oracle_sha} {field}",
                    )
                ]
                _require(
                    values == sorted(set(values)),
                    f"source-binding oracle {oracle_sha} {field} is not canonical",
                )
            offsets = [
                _nonnegative_int(
                    item,
                    f"source-binding oracle {oracle_sha} derivative offset",
                )
                for item in _sequence(
                    evidence.get("derivativeDifferingByteOffsets"),
                    f"source-binding oracle {oracle_sha} derivative offsets",
                )
            ]
            _require(
                offsets == sorted(set(offsets))
                and evidence.get("originalBankUnchangedAfterProbe") is True,
                f"source-binding oracle {oracle_sha} mutation evidence differs",
            )
            binding = evidence.get("authoredBindingKey")
            activation = evidence.get("activationPerspective")
            _require(
                (binding is None and activation in PERSPECTIVES)
                or (
                    activation is None
                    and _authored_binding_key(
                        binding,
                        f"source-binding oracle {oracle_sha} authored binding",
                    )
                )
                == binding,
                f"source-binding oracle {oracle_sha} exact identity scope differs",
            )
        else:
            expected_fields = {
                "schema",
                "eventPath",
                "sourceGuid",
                "authoredBindingKey",
                "diagnosticName",
                "realizationSha256",
                "captures",
            }
            _require(
                set(evidence) == expected_fields,
                f"source-binding oracle {oracle_sha} realization fields differ",
            )
            _authored_binding_key(
                evidence.get("authoredBindingKey"),
                f"source-binding oracle {oracle_sha} authored binding",
            )
            _strict_string(
                evidence.get("diagnosticName"),
                f"source-binding oracle {oracle_sha} diagnostic identity",
            )
            _sha256_string(
                evidence.get("realizationSha256"),
                f"source-binding oracle {oracle_sha} realization SHA-256",
            )
            captures = [
                _mapping(
                    item,
                    f"source-binding oracle {oracle_sha} realization capture",
                )
                for item in _sequence(
                    evidence.get("captures"),
                    f"source-binding oracle {oracle_sha} realization captures",
                )
            ]
            _require(captures, f"source-binding oracle {oracle_sha} has no capture")
            capture_names: list[str] = []
            for capture in captures:
                _require(
                    set(capture)
                    == {"temporaryAssetName", "taskSpecSha256", "wavSha256"},
                    f"source-binding oracle {oracle_sha} capture fields differ",
                )
                capture_names.append(
                    _strict_string(
                        capture.get("temporaryAssetName"),
                        f"source-binding oracle {oracle_sha} capture name",
                    )
                )
                for field in ("taskSpecSha256", "wavSha256"):
                    _sha256_string(
                        capture.get(field),
                        f"source-binding oracle {oracle_sha} capture {field}",
                    )
            _require(
                capture_names == sorted(set(capture_names)),
                f"source-binding oracle {oracle_sha} captures are not canonical",
            )
        parsed[oracle_sha] = evidence

    return parsed


def _validate_voice_source_binding_oracle(
    voice: Mapping[str, Any],
    *,
    event_path: str,
    source_guid: str,
    authored_binding_key: str | None,
    activation_perspective: str | None,
    source_binding_oracles: Mapping[str, Mapping[str, Any]],
    label: str,
) -> None:
    oracle_sha = _sha256_string(
        voice.get("sourceBindingOracleSha256"),
        f"{label} voice source-binding oracle SHA-256",
    )
    oracle = source_binding_oracles.get(oracle_sha)
    _require(oracle is not None, f"{label} voice source-binding oracle is absent")
    assert oracle is not None
    _require(
        oracle.get("eventPath") == event_path
        and _guid(oracle.get("sourceGuid")) == source_guid,
        f"{label} voice source-binding oracle substitutes event/source identity",
    )
    if authored_binding_key is None:
        _require(
            oracle.get("schema") == SOURCE_SOLO_BINDING_ORACLE_SCHEMA
            and oracle.get("authoredBindingKey") is None
            and oracle.get("activationPerspective") == activation_perspective,
            f"{label} engine voice source-binding oracle scope differs",
        )
    else:
        _require(
            oracle.get("authoredBindingKey") == authored_binding_key
            and (
                oracle.get("schema") == REALIZATION_BINDING_ORACLE_SCHEMA
                or oracle.get("activationPerspective") is None
            ),
            f"{label} effect voice source-binding oracle scope differs",
        )


def _plan_contract(plan: Mapping[str, Any]) -> _PlanContract:
    _require(plan.get("schema") == PLAN_SCHEMA, "causal proof input is not a full-event atlas plan")
    family_id = _strict_string(plan.get("id"), "atlas family id")
    plan_sha256 = _sha256_string(plan.get("planSha256"), "atlas plan SHA-256")
    nodes: dict[str, _Node] = {}
    groups: dict[str, _Group] = {}
    continuous_nodes = {perspective: set() for perspective in PERSPECTIVES}
    finite_nodes = {perspective: set() for perspective in PERSPECTIVES}
    continuous_bounds = {perspective: 0 for perspective in PERSPECTIVES}
    continuous_fmod_bounds = {perspective: 0 for perspective in PERSPECTIVES}
    finite_materialization_bounds = {perspective: 0 for perspective in PERSPECTIVES}
    engine_event_paths: dict[str, str] = {}
    engine_source_guids: dict[str, frozenset[str]] = {}
    engine_fmod_channel_bounds: dict[str, int] = {}
    plan_perspectives = _mapping(plan.get("perspectives"), "atlas engine perspectives")
    for perspective in PERSPECTIVES:
        engine = _mapping(plan_perspectives.get(perspective), f"{perspective} engine program")
        engine_event_paths[perspective] = _strict_string(
            engine.get("eventPath"), f"{perspective} engine event path"
        )
        coverage = _sequence(
            engine.get("requiredSourceCoverage"),
            f"{perspective} engine source coverage",
        )
        source_guids = frozenset(
            _guid(_strict_string(_mapping(item, "engine source").get("sourceGuid"), "engine source GUID"))
            for item in coverage
        )
        _require(source_guids and len(source_guids) == len(coverage), f"{perspective} engine source coverage repeats/is empty")
        engine_source_guids[perspective] = source_guids
        multiplicity = _mapping(
            engine.get("logicalChannelMultiplicity"),
            f"{perspective} engine logical-channel multiplicity",
        )
        engine_fmod_channel_bounds[perspective] = _positive_int(
            multiplicity.get("maximumLogicalSourceChannelsAtAtlasNode"),
            f"{perspective} engine FMOD channel bound",
        )
    for raw_event in _sequence(plan.get("effects"), "atlas effects"):
        event = _mapping(raw_event, "atlas effect")
        event_path = _strict_string(event.get("eventPath"), "atlas effect path")
        contract = _mapping(event.get("runtimeLifecycleParameterVariantContract"), f"{event_path} variants")
        variants = [_mapping(item, f"{event_path} variant") for item in _sequence(contract.get("variants"), f"{event_path} variants")]
        binding_records: dict[
            str,
            tuple[str, str, frozenset[str], str],
        ] = {}
        group_bindings: dict[str, set[str]] = {}
        group_ids_by_key: dict[str, str] = {}
        for variant in variants:
            source_guid = _guid(_strict_string(variant.get("sourceGuid"), f"{event_path} source GUID"))
            authored_binding_key = _authored_binding_key(
                variant.get("authoredBindingKey"),
                f"{event_path} authored binding key",
            )
            lifetime = _choice(variant.get("lifetime"), ("continuous", "oneShot", "finiteRepeat"), f"{event_path} lifetime")
            mapping = _mapping(variant.get("runtimeMapping"), f"{event_path} runtime mapping")
            _require(
                _authored_binding_key(
                    mapping.get("authoredBindingKey"),
                    f"{event_path} runtime authored binding key",
                ) == authored_binding_key,
                f"{event_path} authored binding mirror differs",
            )
            perspective_values = mapping.get("perspectives")
            if not isinstance(perspective_values, Sequence) or isinstance(
                perspective_values, (str, bytes, bytearray)
            ):
                perspective_values = [mapping.get("perspective")]
            perspectives = frozenset(
                _choice(value, PERSPECTIVES, f"{event_path} variant perspective")
                for value in perspective_values
            )
            _require(perspectives, f"{event_path} variant has no perspective")
            scheduler = variant.get("schedulingGroup")
            if not isinstance(scheduler, Mapping):
                scheduler = mapping.get("schedulingGroup")
            scheduler = _mapping(scheduler, f"{event_path} scheduling group")
            group_id = _strict_string(scheduler.get("groupId"), f"{event_path} group id")
            group_key = scheduling_group_key(event_path, group_id)
            prior_group_id = group_ids_by_key.setdefault(group_key, group_id)
            _require(
                prior_group_id == group_id,
                f"{event_path} exact scheduling-group key collides",
            )
            binding_record = (source_guid, lifetime, perspectives, group_key)
            prior_binding = binding_records.setdefault(
                authored_binding_key,
                binding_record,
            )
            _require(
                prior_binding == binding_record,
                f"{event_path} authored binding {authored_binding_key} is inconsistent",
            )
            group_bindings.setdefault(group_key, set()).add(authored_binding_key)
        event_resources = _mapping(event.get("perspectiveResources"), f"{event_path} resources")
        group_resource_by_perspective: dict[str, dict[str, Mapping[str, Any]]] = {
            perspective: {} for perspective in PERSPECTIVES
        }
        for perspective, raw_resource in event_resources.items():
            _choice(perspective, PERSPECTIVES, f"{event_path} resource perspective")
            resource = _mapping(raw_resource, f"{event_path} {perspective} resource")
            continuous = _mapping(resource.get("continuous"), f"{event_path} {perspective} continuous resource")
            continuous_bounds[perspective] += _nonnegative_int(
                continuous.get("maximumMappedSourceCorners"),
                f"{event_path} {perspective} continuous corner bound",
            )
            continuous_fmod_bounds[perspective] += _nonnegative_int(
                continuous.get("maximumFmodLogicalSourceChannels"),
                f"{event_path} {perspective} continuous FMOD channel bound",
            )
            finite = _mapping(resource.get("finite"), f"{event_path} {perspective} finite resource")
            finite_materialization_bounds[perspective] += _nonnegative_int(
                finite.get("maximumMappedSourceCornerRegionsDuringMaterialization"),
                f"{event_path} {perspective} finite materialization bound",
            )
            for raw_group in _sequence(finite.get("groups"), f"{event_path} {perspective} finite groups"):
                record = _mapping(raw_group, f"{event_path} {perspective} finite group")
                group_id = _strict_string(record.get("groupId"), f"{event_path} finite group id")
                _require(
                    group_id not in group_resource_by_perspective[perspective],
                    f"{event_path} repeats {perspective} finite group {group_id}",
                )
                group_resource_by_perspective[perspective][group_id] = record

        for group_key, authored_bindings in group_bindings.items():
            group_id = group_ids_by_key[group_key]
            group_perspectives = frozenset(
                perspective
                for authored_binding in authored_bindings
                for perspective in binding_records[authored_binding][2]
            )
            finite = any(
                binding_records[authored_binding][1] != "continuous"
                for authored_binding in authored_bindings
            )
            maximum_contributors: dict[str, int] = {}
            maximum_fmod: dict[str, int] = {}
            maximum_capture: dict[str, int] = {}
            streaming_frames: dict[str, int] = {}
            semantic_triggers: frozenset[str] = frozenset()
            if finite:
                for perspective in group_perspectives:
                    record = group_resource_by_perspective[perspective].get(group_id)
                    _require(record is not None, f"{event_path} group {group_id} has no {perspective} finite resource")
                    maximum_contributors[perspective] = _positive_int(record.get("maximumSourceCornerContributorsPerLogicalRing"), f"{group_id} contributor bound")
                    maximum_fmod[perspective] = _positive_int(record.get("maximumFmodSourceChannelsPerLogicalRing"), f"{group_id} raw-channel bound")
                    maximum_capture[perspective] = _positive_int(record.get("maximumCaptureFramesPerLogicalRing"), f"{group_id} capture bound")
                    streaming_frames[perspective] = _positive_int(record.get("streamingRingBufferFrames"), f"{group_id} ring frames")
                    record_triggers = frozenset(
                        _strict_string(value, f"{group_id} semantic trigger")
                        for value in _sequence(record.get("semanticTriggers"), f"{group_id} semantic triggers")
                    )
                    _require(record_triggers, f"{group_id} has no semantic trigger")
                    _require(
                        not semantic_triggers or semantic_triggers == record_triggers,
                        f"{group_id} semantic triggers differ by perspective",
                    )
                    semantic_triggers = record_triggers
            prior = groups.get(group_key)
            group = _Group(
                key=group_key,
                event_path=event_path,
                group_id=group_id,
                source_guids=frozenset(
                    binding_records[authored_binding][0]
                    for authored_binding in authored_bindings
                ),
                authored_binding_keys=frozenset(authored_bindings),
                semantic_triggers=semantic_triggers,
                perspectives=group_perspectives,
                maximum_contributors=maximum_contributors,
                maximum_fmod_channels=maximum_fmod,
                maximum_capture_frames=maximum_capture,
                streaming_ring_frames=streaming_frames,
            )
            _require(prior is None or prior == group, f"exact group {group_key} is reused inconsistently")
            groups[group_key] = group

        for raw_node in _sequence(event.get("nodes"), f"{event_path} nodes"):
            node = _mapping(raw_node, f"{event_path} node")
            source_guid = _guid(_strict_string(node.get("requiredSourceGuid"), f"{event_path} node source"))
            authored_binding_key = _authored_binding_key(
                node.get("requiredAuthoredBindingKey"),
                f"{event_path} node authored binding key",
            )
            binding = binding_records.get(authored_binding_key)
            _require(binding is not None, f"{event_path} node has no exact authored binding")
            bound_source_guid, lifetime, perspectives, group_key = binding
            _require(
                source_guid == bound_source_guid,
                f"{event_path} node source GUID differs from its authored binding",
            )
            _require(
                node.get("lifetime") == lifetime,
                f"{event_path} node lifetime differs from its source binding",
            )
            parameters = _mapping(node.get("parameters"), f"{event_path} node parameters")
            key = effect_node_key(
                event_path,
                source_guid,
                authored_binding_key,
                parameters,
            )
            record = _Node(
                key=key,
                event_path=event_path,
                source_guid=source_guid,
                authored_binding_key=authored_binding_key,
                group_key=group_key,
                lifetime=lifetime,
                duration_frames=_positive_int(node.get("durationFrames"), f"{event_path} node duration"),
                perspectives=perspectives,
            )
            _require(key not in nodes, f"duplicate exact atlas effect node {key}")
            nodes[key] = record
            for perspective in perspectives:
                target = continuous_nodes if lifetime == "continuous" else finite_nodes
                target[perspective].add(key)

    _require(nodes and groups, "atlas plan has no executable effect nodes/groups")
    return _PlanContract(
        family_id=family_id,
        plan_sha256=plan_sha256,
        nodes=nodes,
        groups=groups,
        continuous_nodes={key: frozenset(value) for key, value in continuous_nodes.items()},
        finite_nodes={key: frozenset(value) for key, value in finite_nodes.items()},
        continuous_region_bound=continuous_bounds,
        continuous_fmod_channel_bound=continuous_fmod_bounds,
        finite_materialization_bound=finite_materialization_bounds,
        engine_event_paths=engine_event_paths,
        engine_source_guids=engine_source_guids,
        engine_fmod_channel_bound=engine_fmod_channel_bounds,
    )


def _guid(value: object) -> str:
    return str(value).strip().strip("{}").casefold()


def _authored_binding_key(
    value: object,
    label: str = "authored binding key",
) -> str:
    result = _strict_string(value, label)
    prefix = "binding:"
    digest = result[len(prefix):] if result.startswith(prefix) else ""
    _require(
        len(digest) == 64
        and all(character in "0123456789abcdef" for character in digest),
        f"{label} is not binding:<64 lowercase hex>",
    )

    return result


def _with_self_hash(value: Mapping[str, Any]) -> dict[str, Any]:
    result = dict(value)
    _require("proofSha256" not in result, "proof already contains a self hash")
    result["proofSha256"] = hashlib.sha256(canonical_json_bytes(result)).hexdigest()
    return result


def _with_named_self_hash(value: Mapping[str, Any], key: str) -> dict[str, Any]:
    result = dict(value)
    _require(key not in result, f"proof already contains {key}")
    result[key] = hashlib.sha256(canonical_json_bytes(result)).hexdigest()

    return result


def _validate_named_self_hash(
    value: Mapping[str, Any],
    key: str,
    label: str,
) -> None:
    expected = _sha256_string(value.get(key), f"{label} SHA-256")
    body = dict(value)
    body.pop(key, None)
    _require(
        hashlib.sha256(canonical_json_bytes(body)).hexdigest() == expected,
        f"{label} self hash differs",
    )


def _validate_self_hash(value: Mapping[str, Any], label: str) -> None:
    expected = _sha256_string(value.get("proofSha256"), f"{label} SHA-256")
    body = dict(value)
    body.pop("proofSha256", None)
    _require(
        hashlib.sha256(canonical_json_bytes(body)).hexdigest() == expected,
        f"{label} self hash differs",
    )


def _insert_error(label: str) -> CausalResourceProofError:
    return CausalResourceProofError(label)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise _insert_error(message)


def _mapping(value: object, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise _insert_error(f"{label} is not an object")
    return value


def _sequence(value: object, label: str) -> list[Any]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        raise _insert_error(f"{label} is not an array")
    return list(value)


def _nonempty_string(value: object, label: str) -> str:
    if not isinstance(value, (str, int)) or isinstance(value, bool) or not str(value).strip():
        raise _insert_error(f"{label} is empty")
    return str(value)


def _strict_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise _insert_error(f"{label} is not a nonempty string")
    return value


def _choice(value: object, choices: Iterable[str], label: str) -> str:
    result = _nonempty_string(value, label)
    if result not in set(choices):
        raise _insert_error(f"{label} has unsupported value {result}")
    return result


def _nonnegative_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise _insert_error(f"{label} is not a nonnegative integer")
    return value


def _positive_int(value: object, label: str) -> int:
    result = _nonnegative_int(value, label)
    if result == 0:
        raise _insert_error(f"{label} is not positive")
    return result


def _sha256_string(value: object, label: str) -> str:
    result = _nonempty_string(value, label)
    if len(result) != 64 or any(character not in "0123456789abcdef" for character in result):
        raise _insert_error(f"{label} is not lowercase SHA-256")
    return result
