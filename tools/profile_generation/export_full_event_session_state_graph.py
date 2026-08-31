"""Compile the canonical finite-lifecycle session graph consumed by the causal verifier.

Only lifecycle topology with source-solo-proven parameter-placement entry is executable here.
Timeline starts and descriptive/suffix-derived trigger guesses fail closed.  The emitted graph is
bound to both the exact atlas plan semantics and the Kotlin implementation files which consume the
same event-instance ownership and placement contracts.
"""

from __future__ import annotations

from itertools import product
import hashlib
import math
from pathlib import Path
from typing import Any, Mapping, Sequence

from tools.profile_generation.causal_full_event_resource_proof import (
    DSP_BUFFER_FRAMES,
    HOST_STATE_GRAPH_SCHEMA,
    HOST_TICK_FRAMES,
    PLAN_SCHEMA,
    PROGRAM_MODES,
    PERSPECTIVES,
    SAMPLE_RATE_HZ,
    CausalResourceProofError,
    canonical_json_bytes,
    scheduling_group_key,
)


PRODUCER_SCHEMA = "byd-full-event-session-state-graph-producer-v1"
OWNERSHIP_SCHEMA = "byd-fmod-event-instance-ownership-v1"
FINITE_TOPOLOGY_SCHEMA = "byd-fmod-finite-lifecycle-topology-v1"
PLACEMENT_SCHEMA = "byd-fmod-parameter-placement-entry-v1"
PROMOTABLE_TOPOLOGY_STATUS = "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE"
PARAMETER_PLACEMENT_TRIGGER = "PARAMETER_PLACEMENT_ENTRY"
SUPPORTED_OWNERS = {
    "selectedPerspectiveEngineEventInstance",
    "profileAudioSessionPersistentEventInstance",
}
IMPLEMENTATION_SOURCE_PATHS = (
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AtlasAudioSessionEffects.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AtlasAudioSessionState.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AtlasEffectControlModel.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AtlasEffectScheduler.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AtlasParameterPlacementState.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/FullEventAtlasEffectsRenderer.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/FullEventAtlasProgram.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/FullEventAtlasRenderer.kt",
    "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/EngineAudioEngine.kt",
)
MAXIMUM_EQUIVALENCE_CELL_COMBINATIONS = 100_000


class SessionStateGraphProducerError(CausalResourceProofError):
    """The plan cannot produce an exact executable finite-lifecycle graph."""


def produce_full_event_session_state_graph(
    plan: Mapping[str, Any],
    *,
    implementation_source_root: Path,
) -> dict[str, Any]:
    _require(plan.get("schema") == PLAN_SCHEMA, "session graph input is not an atlas plan")
    family_id = _string(plan.get("id"), "atlas family id")
    plan_sha256 = _sha256(plan.get("planSha256"), "atlas plan SHA-256")
    implementation_sources = _implementation_manifest(implementation_source_root)
    implementation_sha256 = hashlib.sha256(
        canonical_json_bytes(implementation_sources)
    ).hexdigest()
    group_machines: list[dict[str, Any]] = []
    event_owners: list[dict[str, Any]] = []
    exact_binding_keys: set[str] = set()
    exact_group_keys: set[str] = set()
    for raw_event in _array(plan.get("effects"), "atlas effects"):
        event = _object(raw_event, "atlas effect")
        event_path = _string(event.get("eventPath"), "atlas effect event path")
        variant_contract = _object(
            event.get("runtimeLifecycleParameterVariantContract"),
            f"{event_path} variant contract",
        )
        variants = [
            _object(item, f"{event_path} variant")
            for item in _array(variant_contract.get("variants"), f"{event_path} variants")
        ]
        _require(variants, f"{event_path} has no executable variants")
        finite_by_group: dict[str, list[dict[str, Any]]] = {}
        event_ownership: Mapping[str, Any] | None = None
        event_binding_keys: list[str] = []
        for variant in variants:
            lifetime = _string(variant.get("lifetime"), f"{event_path} lifetime")
            mapping = _object(variant.get("runtimeMapping"), f"{event_path} runtime mapping")
            binding_key = _binding_key(
                variant.get("authoredBindingKey"),
                f"{event_path} authored binding key",
            )
            _require(
                _binding_key(
                    mapping.get("authoredBindingKey"),
                    f"{event_path} runtime authored binding key",
                ) == binding_key,
                f"{event_path} authored binding mirror differs",
            )
            _require(binding_key not in exact_binding_keys, f"duplicate authored binding {binding_key}")
            exact_binding_keys.add(binding_key)
            event_binding_keys.append(binding_key)
            candidate_ownership = _ownership(
                mapping.get("eventInstanceOwnership"),
                event_path=event_path,
            )
            if event_ownership is None:
                event_ownership = candidate_ownership
            _require(
                canonical_json_bytes(candidate_ownership)
                == canonical_json_bytes(event_ownership),
                f"{event_path} variants disagree on event-instance ownership",
            )
            if lifetime == "continuous":
                continue
            scheduling_group = variant.get("schedulingGroup")
            if not isinstance(scheduling_group, Mapping):
                scheduling_group = mapping.get("schedulingGroup")
            scheduling_group = _object(
                scheduling_group,
                f"{event_path} scheduling group",
            )
            group_id = _string(
                scheduling_group.get("groupId"),
                f"{event_path} scheduling group id",
            )
            group_key = scheduling_group_key(event_path, group_id)
            finite_by_group.setdefault(group_key, []).append(
                {
                    "eventPath": event_path,
                    "groupId": group_id,
                    "groupKey": group_key,
                    "bindingKey": binding_key,
                    "mapping": mapping,
                    "schedulingGroup": scheduling_group,
                }
            )
        assert event_ownership is not None
        event_owners.append(
            {
                "eventPath": event_path,
                "eventInstanceOwnership": dict(event_ownership),
                "authoredBindingKeys": sorted(event_binding_keys),
            }
        )
        for group_key, group_variants in sorted(finite_by_group.items()):
            _require(group_key not in exact_group_keys, f"duplicate exact group {group_key}")
            exact_group_keys.add(group_key)
            group_machines.append(_compile_group_machine(group_variants))
    _require(group_machines, "atlas plan has no promotable finite scheduling group")
    event_owners.sort(key=lambda item: str(item["eventPath"]))
    group_machines.sort(
        key=lambda item: (str(item["eventPath"]), str(item["groupId"]))
    )
    session_machine = _compile_session_machine(group_machines)
    semantic_input = {
        "eventOwners": event_owners,
        "groups": [
            {
                "eventPath": item["eventPath"],
                "groupId": item["groupId"],
                "groupKey": item["groupKey"],
                "eventInstanceOwnership": item["eventInstanceOwnership"],
                "schedulingGroup": item["schedulingGroup"],
                "bindings": item["bindings"],
            }
            for item in group_machines
        ],
        "sessionMachine": session_machine,
    }
    result = {
        "schema": HOST_STATE_GRAPH_SCHEMA,
        "producer": {
            "schema": PRODUCER_SCHEMA,
            "finiteTopologyPolicy": "parameterPlacementOnly",
            "unsupportedTopologyPolicy": "failClosed",
            "hostInputReachability": (
                "allExactPlacementEquivalenceCellsMayFollowAnyOtherCellAtEach200HzHostTick"
            ),
        },
        "atlasFamilyId": family_id,
        "planSha256": plan_sha256,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "hostTickFrames": HOST_TICK_FRAMES,
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "implementationSources": implementation_sources,
        "hostControlImplementationSha256": implementation_sha256,
        "producerSemanticInputSha256": hashlib.sha256(
            canonical_json_bytes(semantic_input)
        ).hexdigest(),
        "stateSpaceComplete": True,
        "eventOwners": event_owners,
        "groupMachines": group_machines,
        "sessionMachine": session_machine,
    }
    result["graphSha256"] = hashlib.sha256(canonical_json_bytes(result)).hexdigest()

    return result


def _compile_group_machine(group_variants: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    first = group_variants[0]
    event_path = str(first["eventPath"])
    group_id = str(first["groupId"])
    group_key = str(first["groupKey"])
    scheduling_group = _scheduling_group_contract(
        first["schedulingGroup"],
        event_path=event_path,
        group_id=group_id,
    )
    ownership: Mapping[str, Any] | None = None
    bindings: list[dict[str, Any]] = []
    for item in group_variants:
        _require(
            canonical_json_bytes(
                _scheduling_group_contract(
                    item["schedulingGroup"],
                    event_path=event_path,
                    group_id=group_id,
                )
            )
            == canonical_json_bytes(scheduling_group),
            f"{event_path}/{group_id} variants disagree on scheduling selection",
        )
        mapping = _object(item["mapping"], f"{event_path} runtime mapping")
        candidate_ownership = _ownership(
            mapping.get("eventInstanceOwnership"),
            event_path=event_path,
        )
        if ownership is None:
            ownership = candidate_ownership
        _require(
            canonical_json_bytes(candidate_ownership) == canonical_json_bytes(ownership),
            f"{event_path} variants disagree on event-instance ownership",
        )
        topology = _object(
            mapping.get("finiteLifecycleTopology"),
            f"{event_path} finite lifecycle topology",
        )
        _require(
            topology.get("schema") == FINITE_TOPOLOGY_SCHEMA
            and topology.get("status") == PROMOTABLE_TOPOLOGY_STATUS
            and topology.get("topology") == "parameterPlacementOnly",
            f"{event_path} finite lifecycle topology is not source-solo placement PASS",
        )
        triggers = _string_array(mapping.get("triggers"), f"{event_path} runtime triggers")
        _require(
            triggers == [PARAMETER_PLACEMENT_TRIGGER],
            f"{event_path} finite runtime trigger is not placement-only",
        )
        _require(
            _string_array(topology.get("triggers"), f"{event_path} topology triggers")
            == [PARAMETER_PLACEMENT_TRIGGER],
            f"{event_path} finite topology trigger is not placement-only",
        )
        semantic_lifecycle = [
            _object(value, f"{event_path} semantic lifecycle")
            for value in _array(mapping.get("semanticLifecycle"), f"{event_path} semantic lifecycle")
        ]
        _require(
            len(semantic_lifecycle) == 1
            and semantic_lifecycle[0].get("trigger") == PARAMETER_PLACEMENT_TRIGGER,
            f"{event_path} semantic lifecycle is not one exact placement entry",
        )
        placement = _placement(
            mapping.get("parameterPlacementEntry"),
            event_path=event_path,
        )
        lifecycle_placement = semantic_lifecycle[0].get("parameterPlacementEntry")
        _require(
            canonical_json_bytes(lifecycle_placement) == canonical_json_bytes(placement),
            f"{event_path} placement lifecycle mirror differs",
        )
        bindings.append(
            {
                "authoredBindingKey": item["bindingKey"],
                "hostGainClass": _choice_string(
                    mapping.get("hostGainClass"),
                    {"engineEvent", "effectEvent"},
                    f"{event_path} host gain class",
                ),
                "perspectives": sorted(
                    _variant_perspectives(mapping, event_path=event_path)
                ),
                "parameterDomains": mapping.get("parameterDomains"),
                "parameterPlacementEntry": placement,
            }
        )
    assert ownership is not None
    owner = str(ownership["owner"])
    _require(
        owner in SUPPORTED_OWNERS,
        f"{event_path} owner {owner} has no exact placement activation automaton",
    )
    states: list[dict[str, Any]] = []
    state_by_context_membership: dict[tuple[str, str, tuple[bool, ...]], str] = {}
    membership_by_state: dict[str, tuple[bool, ...]] = {}
    for perspective in PERSPECTIVES:
        for mode in PROGRAM_MODES:
            membership_states = _reachable_group_membership_states(
                bindings,
                perspective=perspective,
                program_mode=mode,
            )
            for membership, host_values in membership_states:
                state_identity = {
                    "perspective": perspective,
                    "programMode": mode,
                    "membership": list(membership),
                }
                state_id = "state:" + hashlib.sha256(
                    canonical_json_bytes(state_identity)
                ).hexdigest()
                state_by_context_membership[(perspective, mode, membership)] = state_id
                membership_by_state[state_id] = membership
                states.append(
                    {
                        "id": state_id,
                        "selectedPerspective": perspective,
                        "programMode": mode,
                        "membership": list(membership),
                        "hostValues": host_values,
                        "transitions": [],
                    }
                )
    _require(states, f"{event_path}/{group_id} has no reachable placement state")
    state_record_by_id = {state["id"]: state for state in states}
    for source_state in states:
        source_membership = membership_by_state[source_state["id"]]
        source_perspective = source_state["selectedPerspective"]
        transitions: list[dict[str, Any]] = []
        for (target_perspective, _mode, target_membership), target_id in sorted(
            state_by_context_membership.items(),
            key=lambda value: value[1],
        ):
            activation_reset = (
                owner == "selectedPerspectiveEngineEventInstance"
                and target_perspective != source_perspective
            )
            entered = any(target_membership) if activation_reset else any(
                not before and after
                for before, after in zip(source_membership, target_membership)
            )
            transitions.append(
                {
                    "targetStateId": target_id,
                    "emissions": [_emission(event_path, group_id)] if entered else [],
                    "activationReset": activation_reset,
                }
            )
        source_state["transitions"] = transitions
    initial_states = [
        {
            "stateId": state["id"],
            "emissions": (
                [_emission(event_path, group_id)]
                if any(membership_by_state[state["id"]])
                else []
            ),
            "activationMembershipEvaluatedFromCurrentHostValues": True,
        }
        for state in states
    ]

    return {
        "eventPath": event_path,
        "groupId": group_id,
        "groupKey": group_key,
        "eventInstanceOwnership": dict(ownership),
        "schedulingGroup": scheduling_group,
        "bindings": bindings,
        "initialStates": initial_states,
        "states": states,
    }


def _compile_session_machine(
    group_machines: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Compile one correlated host machine for every finite group in the session.

    Per-group projections are useful for binding source-solo witnesses, but they cannot prove a
    session pool: independently reachable membership vectors may be mutually exclusive because
    they are driven by the same RPM/throttle host value.  This machine evaluates every binding
    against one shared host-input cell and emits every scheduling-group start atomically.
    """

    flattened_bindings: list[dict[str, Any]] = []
    group_contracts: list[dict[str, Any]] = []
    for machine in group_machines:
        first_index = len(flattened_bindings)
        for binding in machine["bindings"]:
            flattened_bindings.append(
                {
                    **dict(binding),
                    "groupKey": machine["groupKey"],
                }
            )
        group_contracts.append(
            {
                "eventPath": machine["eventPath"],
                "groupId": machine["groupId"],
                "groupKey": machine["groupKey"],
                "eventInstanceOwnership": machine["eventInstanceOwnership"],
                "bindingIndexes": list(range(first_index, len(flattened_bindings))),
            }
        )
    _require(flattened_bindings, "session machine has no finite authored binding")

    states: list[dict[str, Any]] = []
    state_by_context_membership: dict[tuple[str, str, tuple[bool, ...]], str] = {}
    for perspective in PERSPECTIVES:
        for mode in PROGRAM_MODES:
            for membership, host_values in _reachable_binding_membership_states(
                flattened_bindings,
                perspective=perspective,
                program_mode=mode,
            ):
                identity = {
                    "selectedPerspective": perspective,
                    "programMode": mode,
                    "membership": list(membership),
                }
                state_id = "session-state:" + hashlib.sha256(
                    canonical_json_bytes(identity)
                ).hexdigest()
                state_by_context_membership[(perspective, mode, membership)] = state_id
                states.append({"id": state_id, **identity, "hostValues": host_values})
    _require(states, "session machine has no reachable host-input state")
    states.sort(key=lambda item: str(item["id"]))

    initial_states = []
    for state in states:
        membership = tuple(bool(value) for value in state["membership"])
        initial_states.append(
            {
                "stateId": state["id"],
                "emissions": [
                    _emission(str(group["eventPath"]), str(group["groupId"]))
                    for group in group_contracts
                    if any(membership[index] for index in group["bindingIndexes"])
                ],
                "activationMembershipEvaluatedFromCurrentHostValues": True,
            }
        )

    return {
        "schema": "byd-full-event-global-session-machine-v1",
        "bindingOrder": [
            {
                "groupKey": binding["groupKey"],
                "authoredBindingKey": binding["authoredBindingKey"],
            }
            for binding in flattened_bindings
        ],
        "groups": group_contracts,
        "transitionContract": {
            "sourceStateTargets": "everyCanonicalSessionStateAtEach200HzHostTick",
            "sharedHostInputCorrelation": "oneExactHostInputCellEvaluatesAllBindings",
            "persistentOwnerEntry": "emitOncePerGroupWhenAnyBindingChangesOutsideToInside",
            "selectedPerspectiveEngineOwnerEntry": (
                "onPerspectiveActivationResetEvaluateTargetMembershipThenEmitOncePerInsideGroup;"
                "otherwiseEmitOncePerGroupWhenAnyBindingChangesOutsideToInside"
            ),
            "atomicGroupEmission": "atMostOneLogicalRingStartPerSchedulingGroupPerHostTick",
        },
        "initialStates": initial_states,
        "states": states,
    }


def _reachable_group_membership_states(
    bindings: Sequence[Mapping[str, Any]],
    *,
    perspective: str,
    program_mode: str,
) -> list[tuple[tuple[bool, ...], dict[str, float]]]:
    _require(
        len({str(binding["hostGainClass"]) for binding in bindings}) == 1,
        "one scheduling group mixes host-gain classes",
    )

    return _reachable_binding_membership_states(
        bindings,
        perspective=perspective,
        program_mode=program_mode,
    )


def _reachable_binding_membership_states(
    bindings: Sequence[Mapping[str, Any]],
    *,
    perspective: str,
    program_mode: str,
) -> list[tuple[tuple[bool, ...], dict[str, float]]]:
    active = [
        binding for binding in bindings
        if perspective in binding["perspectives"]
    ]
    if not active:
        return [(tuple(False for _ in bindings), {})]
    source_domains: dict[str, tuple[float, float]] = {}
    source_boundaries: dict[str, set[float]] = {}
    binding_values: list[dict[str, tuple[str, float | str]]] = []
    for binding in bindings:
        placement = binding["parameterPlacementEntry"]
        membership = _object(placement.get("membership"), "placement membership")
        values: dict[str, tuple[str, float | str]] = {}
        parameter_domains = _object(
            binding.get("parameterDomains"),
            "placement parameter domains",
        )
        for raw_value in _array(
            membership.get("parameterValues"),
            "placement parameter values",
        ):
            record = _object(raw_value, "placement parameter value")
            parameter = _string(record.get("parameter"), "placement parameter")
            value = _object(record.get("value"), f"{parameter} placement value")
            kind = _string(value.get("kind"), f"{parameter} placement value kind")
            if kind == "authoredDefault":
                values[parameter] = (
                    kind,
                    _finite_number(value.get("value"), f"{parameter} authored default"),
                )
            elif kind == "hostBinding":
                host = _object(value.get("binding"), f"{parameter} host binding")
                source = _string(host.get("source"), f"{parameter} host source")
                domain = _number_pair(
                    parameter_domains.get(parameter),
                    f"{parameter} parameter domain",
                )
                prior = source_domains.setdefault(source, domain)
                _require(prior == domain, f"host source {source} has inconsistent domains")
                values[parameter] = (kind, source)
                boundaries = source_boundaries.setdefault(source, set(domain))
                for span in _array(
                    _object(membership.get("placements"), "placement spans").get(parameter),
                    f"{parameter} placement spans",
                ):
                    span = _object(span, f"{parameter} placement span")
                    boundaries.add(_finite_number(span.get("start"), f"{parameter} span start"))
                    boundaries.add(_finite_number(span.get("end"), f"{parameter} span end"))
            else:
                raise SessionStateGraphProducerError(
                    f"unsupported placement value kind {kind}"
                )
        binding_values.append(values)
    representatives: dict[str, tuple[float, ...]] = {}
    for source, domain in source_domains.items():
        minimum, maximum = domain
        source_gain_classes = {
            str(binding["hostGainClass"])
            for binding, value_contract in zip(bindings, binding_values)
            if any(
                kind == "hostBinding" and raw_value == source
                for kind, raw_value in value_contract.values()
            )
        }
        if source == "EngineSimulation.throttle" and source_gain_classes == {"engineEvent"}:
            fixed_value = {
                "LOAD": 1.0,
                "COAST": 0.0,
            }.get(program_mode)
            if fixed_value is not None:
                _require(
                    minimum <= fixed_value <= maximum,
                    f"engine-event {program_mode} throttle lies outside its authored domain",
                )
                representatives[source] = (fixed_value,)
                continue
        points = sorted(value for value in source_boundaries[source] if minimum <= value <= maximum)
        candidates = {minimum, maximum}
        for point in points:
            candidates.add(point)
            below = math.nextafter(point, -math.inf)
            above = math.nextafter(point, math.inf)
            if minimum <= below <= maximum:
                candidates.add(below)
            if minimum <= above <= maximum:
                candidates.add(above)
        for before, after in zip(points, points[1:]):
            candidates.add((before + after) * 0.5)
        representatives[source] = tuple(sorted(candidates))
    source_names = sorted(representatives)
    combination_count = math.prod(len(representatives[name]) for name in source_names)
    _require(
        combination_count <= MAXIMUM_EQUIVALENCE_CELL_COMBINATIONS,
        "placement equivalence-cell product exceeds the fail-closed producer limit",
    )
    states: dict[tuple[bool, ...], dict[str, float]] = {}
    values_product = product(*(representatives[name] for name in source_names))
    for selected_values in values_product:
        host_values = dict(zip(source_names, selected_values))
        vector: list[bool] = []
        for binding, value_contract in zip(bindings, binding_values):
            if perspective not in binding["perspectives"]:
                vector.append(False)
                continue
            placement = binding["parameterPlacementEntry"]
            membership = _object(placement.get("membership"), "placement membership")
            placements = _object(membership.get("placements"), "placement spans")
            inside = True
            for parameter, raw_spans in placements.items():
                kind, raw_value = value_contract[parameter]
                if kind == "authoredDefault":
                    value = float(raw_value)
                elif (
                    raw_value == "EngineSimulation.throttle"
                    and binding["hostGainClass"] == "engineEvent"
                    and program_mode in {"LOAD", "COAST"}
                ):
                    value = 1.0 if program_mode == "LOAD" else 0.0
                else:
                    value = host_values[str(raw_value)]
                for raw_span in _array(raw_spans, f"{parameter} placement spans"):
                    span = _object(raw_span, f"{parameter} placement span")
                    start = _finite_number(span.get("start"), f"{parameter} span start")
                    end = _finite_number(span.get("end"), f"{parameter} span end")
                    include_end = span.get("includeEnd") is True
                    _require(isinstance(span.get("includeEnd"), bool), "placement includeEnd is not boolean")
                    if not (value >= start and (value < end or (include_end and value == end))):
                        inside = False
                        break
                if not inside:
                    break
            vector.append(inside)
        membership = tuple(vector)
        candidate = {
            source: float(host_values[source])
            for source in sorted(host_values)
        }
        previous = states.get(membership)
        if previous is None or canonical_json_bytes(candidate) < canonical_json_bytes(previous):
            states[membership] = candidate

    return [(membership, states[membership]) for membership in sorted(states)]


def _scheduling_group_contract(
    value: object,
    *,
    event_path: str,
    group_id: str,
) -> dict[str, Any]:
    from tools.profile_generation.generate_android_profile_recipe import (
        _playlist_selection_runtime_contract,
        _simultaneous_layer_selection_runtime_contract,
    )

    group = _object(value, f"{event_path}/{group_id} scheduling group")
    _require(
        group.get("groupId") == group_id and group.get("complete") is True,
        f"{event_path}/{group_id} scheduling group is incomplete",
    )
    composition = _choice_string(
        group.get("composition"),
        {"simultaneousLayer", "playlistAlternative"},
        f"{event_path}/{group_id} scheduling composition",
    )
    expected_selection = (
        _simultaneous_layer_selection_runtime_contract()
        if composition == "simultaneousLayer"
        else {
            **_playlist_selection_runtime_contract(),
            "schedulerKind": "playlistAlternative",
        }
    )
    observed_selection = _object(
        group.get("selectionRuntimeContract"),
        f"{event_path}/{group_id} selection runtime contract",
    )
    _require(
        canonical_json_bytes(observed_selection)
        == canonical_json_bytes(expected_selection),
        f"{event_path}/{group_id} selection runtime contract is not frozen playlist v3",
    )
    members = [
        _object(item, f"{event_path}/{group_id} scheduling member")
        for item in _array(group.get("members"), f"{event_path}/{group_id} members")
    ]
    _require(members, f"{event_path}/{group_id} scheduling group has no members")

    return dict(group)


def _ownership(value: object, *, event_path: str) -> Mapping[str, Any]:
    ownership = _object(value, f"{event_path} event-instance ownership")
    expected_keys = {
        "schema",
        "key",
        "owner",
        "created",
        "survives",
        "resets",
        "activationGeneration",
    }
    _require(set(ownership) == expected_keys, f"{event_path} ownership key set differs")
    _require(
        ownership.get("schema") == OWNERSHIP_SCHEMA
        and ownership.get("key") == "exactEventPath",
        f"{event_path} ownership schema/key differs",
    )
    for key in expected_keys - {"schema", "key"}:
        _string(ownership[key], f"{event_path} ownership {key}")

    return ownership


def _placement(value: object, *, event_path: str) -> Mapping[str, Any]:
    placement = _object(value, f"{event_path} parameter placement entry")
    _require(
        set(placement) == {"schema", "stateScope", "initialState", "membership", "transition"}
        and placement.get("schema") == PLACEMENT_SCHEMA
        and placement.get("stateScope")
        == "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
        f"{event_path} placement schema/state scope differs",
    )
    initial = _object(placement.get("initialState"), f"{event_path} placement initial state")
    _require(
        set(initial) == {"when", "inside", "outside"}
        and initial.get("when") == "exactEventInstanceCreated"
        and initial.get("inside") == "startOnceAtCurrentHostParameterValue"
        and initial.get("outside") == "remainSilentUntilOutsideToInsideEntry",
        f"{event_path} placement initial-state semantics differ",
    )
    membership = _object(placement.get("membership"), f"{event_path} placement membership")
    _require(
        set(membership) == {
            "parameterCombination",
            "placementsWithinParameter",
            "startBoundary",
            "endBoundary",
            "placements",
            "parameterValues",
        }
        and membership.get("parameterCombination") == "allParameterGroupsMustContainCurrentValue"
        and membership.get("placementsWithinParameter") == "allInstrumentChainPlacementsMustContainCurrentValue"
        and membership.get("startBoundary") == "inclusive"
        and membership.get("endBoundary") == "includeEndFromAuthoredParameterPlacement",
        f"{event_path} placement membership semantics differ",
    )
    placements = _object(membership.get("placements"), f"{event_path} placement spans")
    parameter_values = [
        _object(value, f"{event_path} placement parameter value")
        for value in _array(
            membership.get("parameterValues"),
            f"{event_path} placement parameter values",
        )
    ]
    parameter_names = [
        _string(value.get("parameter"), f"{event_path} placement parameter")
        for value in parameter_values
    ]
    _require(
        placements
        and len(parameter_names) == len(set(parameter_names))
        and set(placements) == set(parameter_names),
        f"{event_path} placement parameter identities differ",
    )
    parameter_identity: dict[str, tuple[str, str]] = {}
    for record in parameter_values:
        _require(
            set(record) == {"parameter", "parameterGuid", "layoutGuid", "value"},
            f"{event_path} placement parameter-value fields differ",
        )
        parameter = _string(record.get("parameter"), f"{event_path} placement parameter")
        parameter_guid = _string(
            record.get("parameterGuid"),
            f"{event_path} placement parameter GUID",
        )
        layout_guid = _string(
            record.get("layoutGuid"),
            f"{event_path} placement layout GUID",
        )
        parameter_identity[parameter] = (parameter_guid, layout_guid)
        resolved_value = _object(
            record.get("value"),
            f"{event_path} placement parameter value",
        )
        kind = _string(
            resolved_value.get("kind"),
            f"{event_path} placement parameter-value kind",
        )
        if kind == "hostBinding":
            _require(
                set(resolved_value) == {"kind", "binding"},
                f"{event_path} placement host-binding fields differ",
            )
            binding = _object(
                resolved_value.get("binding"),
                f"{event_path} placement host binding",
            )
            _require(
                set(binding) == {"parameter", "source"}
                and binding.get("parameter") == parameter,
                f"{event_path} placement host binding identity differs",
            )
            _string(binding.get("source"), f"{event_path} placement host source")
        elif kind == "authoredDefault":
            _require(
                set(resolved_value) == {"kind", "value"},
                f"{event_path} placement authored-default fields differ",
            )
            _finite_number(
                resolved_value.get("value"),
                f"{event_path} placement authored default",
            )
        else:
            raise SessionStateGraphProducerError(
                f"{event_path} placement parameter-value kind is unsupported"
            )
    for parameter, raw_spans in placements.items():
        spans = [
            _object(value, f"{event_path} {parameter} placement span")
            for value in _array(
                raw_spans,
                f"{event_path} {parameter} placement spans",
            )
        ]
        _require(spans, f"{event_path} {parameter} has no placement span")
        parameter_guid, layout_guid = parameter_identity[parameter]
        for span in spans:
            _require(
                set(span) == {
                    "start",
                    "end",
                    "includeEnd",
                    "parameterGuid",
                    "layoutGuid",
                    "instrumentGuid",
                }
                and span.get("parameterGuid") == parameter_guid
                and span.get("layoutGuid") == layout_guid
                and isinstance(span.get("includeEnd"), bool),
                f"{event_path} {parameter} placement-span identity differs",
            )
            start = _finite_number(
                span.get("start"),
                f"{event_path} {parameter} placement start",
            )
            end = _finite_number(
                span.get("end"),
                f"{event_path} {parameter} placement end",
            )
            _require(end >= start, f"{event_path} {parameter} placement range is reversed")
            _string(
                span.get("instrumentGuid"),
                f"{event_path} {parameter} placement instrument GUID",
            )
    transition = _object(
        placement.get("transition"),
        f"{event_path} placement transition",
    )
    _require(
        set(transition) == {"sampleBoundary", "trigger", "exit", "directions"}
        and transition.get("sampleBoundary")
        == "eachDspBlockAfterHostParameterUpdateForHostBoundParameters"
        and transition.get("trigger") == "combinedMembershipOutsideToInside"
        and transition.get("exit") == "combinedMembershipInsideToOutsideArmsNextEntry"
        and _string_array(
            transition.get("directions"),
            f"{event_path} placement directions",
        ) == ["increasing", "decreasing", "discontinuousJump"],
        f"{event_path} placement transition semantics differ",
    )

    return placement


def _variant_perspectives(mapping: Mapping[str, Any], *, event_path: str) -> set[str]:
    values = mapping.get("perspectives")
    if not isinstance(values, Sequence) or isinstance(values, (str, bytes, bytearray)):
        values = [mapping.get("perspective")]
    result = {_string(value, f"{event_path} perspective") for value in values}
    _require(result and result <= set(PERSPECTIVES), f"{event_path} perspective differs")

    return result


def _emission(event_path: str, group_id: str) -> dict[str, Any]:
    return {
        "eventPath": event_path,
        "groupId": group_id,
        "semanticTrigger": PARAMETER_PLACEMENT_TRIGGER,
    }


def _implementation_manifest(source_root: Path) -> list[dict[str, str]]:
    root = source_root.resolve(strict=True)
    result: list[dict[str, str]] = []
    for relative in IMPLEMENTATION_SOURCE_PATHS:
        path = (root / relative).resolve(strict=True)
        _require(path.is_relative_to(root), f"implementation source escapes root: {relative}")
        result.append(
            {
                "path": relative,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )

    return result


def _binding_key(value: object, label: str) -> str:
    result = _string(value, label)
    digest = result[8:] if result.startswith("binding:") else ""
    _require(
        len(digest) == 64
        and all(character in "0123456789abcdef" for character in digest),
        f"{label} is not binding:<64 lowercase hex>",
    )
    return result


def _number_pair(value: object, label: str) -> tuple[float, float]:
    values = _array(value, label)
    _require(len(values) == 2, f"{label} is not a two-value domain")
    minimum = _finite_number(values[0], label)
    maximum = _finite_number(values[1], label)
    _require(minimum < maximum, f"{label} is not increasing")
    return minimum, maximum


def _finite_number(value: object, label: str) -> float:
    _require(
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value)),
        f"{label} is not finite",
    )
    return float(value)


def _sha256(value: object, label: str) -> str:
    result = _string(value, label)
    _require(
        len(result) == 64
        and all(character in "0123456789abcdef" for character in result),
        f"{label} is not lowercase SHA-256",
    )
    return result


def _string_array(value: object, label: str) -> list[str]:
    return [_string(item, label) for item in _array(value, label)]


def _object(value: object, label: str) -> Mapping[str, Any]:
    _require(isinstance(value, Mapping), f"{label} is not an object")
    return value


def _array(value: object, label: str) -> list[Any]:
    _require(
        isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)),
        f"{label} is not an array",
    )
    return list(value)


def _string(value: object, label: str) -> str:
    _require(isinstance(value, str) and bool(value.strip()), f"{label} is empty")
    return value


def _choice_string(value: object, choices: set[str], label: str) -> str:
    result = _string(value, label)
    _require(result in choices, f"{label} is unsupported")
    return result


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise SessionStateGraphProducerError(message)
