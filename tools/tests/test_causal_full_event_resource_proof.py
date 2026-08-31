from __future__ import annotations

import copy
import hashlib
from pathlib import Path
import unittest

from tools.profile_generation.causal_full_event_resource_proof import (
    DSP_BUFFER_FRAMES,
    HOST_CONTROL_HZ,
    HOST_TICK_FRAMES,
    HOST_WITNESS_BINDINGS_SCHEMA,
    PROGRAM_MODES,
    PERSPECTIVES,
    SAMPLE_RATE_HZ,
    STATIC_SILENCE_PROOF_SCHEMA,
    VIRTUALIZATION_PROOF_SCHEMA,
    VIRTUAL_INAUDIBILITY_EPSILON,
    CausalResourceProofError,
    apply_causal_runtime_resource_update,
    bind_causal_proof_to_packed_shards,
    canonical_json_bytes,
    causal_runtime_resource_updates,
    effect_node_key,
    explore_host_control_reachability,
    prove_causal_full_event_resources,
    scheduling_group_key,
)
from tools.profile_generation.export_full_event_session_state_graph import (
    SessionStateGraphProducerError,
    produce_full_event_session_state_graph,
)
from tools.profile_generation.generate_android_profile_recipe import (
    _simultaneous_layer_selection_runtime_contract,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PLAN_SHA256 = hashlib.sha256(b"fixture-atlas-plan").hexdigest()
FINITE_GROUP_ID = "group:finite"
FINITE_EVENT_PATH = "event:/fixture/transient"
CONTINUOUS_EVENT_PATH = "event:/fixture/continuous"
CONTINUOUS_GROUP_ID = "group:continuous"
CONTINUOUS_SOURCE_GUID = "00000000-0000-0000-0000-000000000010"
FINITE_SOURCE_GUID = "00000000-0000-0000-0000-000000000020"


def _binding_key(name: str) -> str:
    return "binding:" + hashlib.sha256(name.encode()).hexdigest()


def _make_voice_virtual(
    snapshot: dict,
    voice: dict,
    *,
    audibility: float = 0.0,
    authored_route_gain: float = 0.0,
) -> None:
    voice["isVirtual"] = True
    voice["audibility"] = audibility
    voice["authoredRouteGain"] = authored_route_gain
    identity_field = (
        "activationPerspective"
        if voice["kind"] == "engineContinuous"
        else "authoredBindingKey"
    )
    voice["virtualizationProof"] = {
        "schema": VIRTUALIZATION_PROOF_SCHEMA,
        "eventPath": voice["eventPath"],
        "sourceGuid": voice["sourceGuid"],
        identity_field: voice[identity_field],
        "isVirtualReportedByFmod": True,
        "measuredAudibility": audibility,
        "measuredAuthoredRouteGain": authored_route_gain,
        "audibilityEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
        "authoredRouteGainEpsilon": VIRTUAL_INAUDIBILITY_EPSILON,
    }
    snapshot["systemRealChannels"] -= 1


def _scheduling_group(group_id: str, source_guid: str) -> dict:
    return {
        "groupId": group_id,
        "composition": "simultaneousLayer",
        "selection": {
            "kind": "always",
            "triggerChance": {
                "source": "waveformInstrument.baseProperties.triggerChancePercent",
                "percent": None,
                "defaultPercentWhenNull": 100.0,
                "activation": "independentPerSemanticTrigger",
                "acceptance": "uniformTimes100 < triggerChancePercent",
            },
        },
        "selectionRuntimeContract": _simultaneous_layer_selection_runtime_contract(),
        "members": [
            {
                "sourceGuid": source_guid,
                "authoredOrder": 0,
                "weight": 1.0,
                "triggerChancePercent": None,
            }
        ],
        "timelinePlacements": [],
        "complete": True,
        "incompleteReason": None,
    }


def _variant(
    *,
    source_guid: str,
    lifetime: str,
    group_id: str,
    trigger: str,
) -> dict:
    scheduling_group = _scheduling_group(group_id, source_guid)
    authored_binding_key = _binding_key(f"{group_id}:{source_guid}")
    ownership = {
        "schema": "byd-fmod-event-instance-ownership-v1",
        "key": "exactEventPath",
        "owner": "profileAudioSessionPersistentEventInstance",
        "created": "onceAtProfileAudioSessionCreation",
        "survives": "listenerPerspectiveAndProgramModeChanges",
        "resets": "profileAudioSessionEnd",
        "activationGeneration": "onePerProfileAudioSession",
    }
    placement = {
        "schema": "byd-fmod-parameter-placement-entry-v1",
        "stateScope": "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
        "initialState": {
            "when": "exactEventInstanceCreated",
            "inside": "startOnceAtCurrentHostParameterValue",
            "outside": "remainSilentUntilOutsideToInsideEntry",
        },
        "membership": {
            "parameterCombination": "allParameterGroupsMustContainCurrentValue",
            "placementsWithinParameter": "allInstrumentChainPlacementsMustContainCurrentValue",
            "startBoundary": "inclusive",
            "endBoundary": "includeEndFromAuthoredParameterPlacement",
            "placements": {
                "rpms": [
                    {
                        "start": 1_000.0,
                        "end": 2_000.0,
                        "includeEnd": True,
                        "parameterGuid": "00000000-0000-0000-0000-000000000101",
                        "layoutGuid": "00000000-0000-0000-0000-000000000102",
                        "instrumentGuid": "00000000-0000-0000-0000-000000000103",
                    }
                ]
            },
            "parameterValues": [
                {
                    "parameter": "rpms",
                    "parameterGuid": "00000000-0000-0000-0000-000000000101",
                    "layoutGuid": "00000000-0000-0000-0000-000000000102",
                    "value": {
                        "kind": "hostBinding",
                        "binding": {
                            "parameter": "rpms",
                            "source": "EngineSimulation.rpm",
                        },
                    },
                }
            ],
        },
        "transition": {
            "sampleBoundary": "eachDspBlockAfterHostParameterUpdateForHostBoundParameters",
            "trigger": "combinedMembershipOutsideToInside",
            "exit": "combinedMembershipInsideToOutsideArmsNextEntry",
            "directions": ["increasing", "decreasing", "discontinuousJump"],
        },
    }
    runtime_mapping = {
        "authoredBindingKey": authored_binding_key,
        "perspectives": list(PERSPECTIVES),
        "schedulingGroup": scheduling_group,
        "triggers": [trigger],
        "eventInstanceOwnership": ownership,
        "hostGainClass": "effectEvent",
    }
    if lifetime != "continuous":
        runtime_mapping.update(
            {
                "parameterDomains": {"rpms": [0.0, 3_000.0]},
                "parameterPlacementEntry": placement,
                "semanticLifecycle": [
                    {
                        "trigger": "PARAMETER_PLACEMENT_ENTRY",
                        "parameterPlacementEntry": copy.deepcopy(placement),
                    }
                ],
                "finiteLifecycleTopology": {
                    "schema": "byd-fmod-finite-lifecycle-topology-v1",
                    "status": "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE",
                    "topology": "parameterPlacementOnly",
                    "triggers": ["PARAMETER_PLACEMENT_ENTRY"],
                },
            }
        )
    return {
        "bindingId": f"binding:{source_guid}",
        "authoredBindingKey": authored_binding_key,
        "sourceGuid": source_guid,
        "lifetime": lifetime,
        "schedulingGroup": scheduling_group,
        "runtimeMapping": runtime_mapping,
    }


def _perspective_resource(*, finite: bool) -> dict:
    return {
        "continuous": {
            "maximumMappedSourceCorners": 0 if finite else 4,
            "maximumFmodLogicalSourceChannels": 0 if finite else 1,
        },
        "finite": {
            "maximumMappedSourceCornerRegionsDuringMaterialization": 8 if finite else 0,
            "groups": [
                {
                    "groupId": FINITE_GROUP_ID,
                    "semanticTriggers": ["PARAMETER_PLACEMENT_ENTRY"],
                    "maximumSourceCornerContributorsPerLogicalRing": 4,
                    "maximumFmodSourceChannelsPerLogicalRing": 1,
                    "maximumCaptureFramesPerLogicalRing": 512,
                    "streamingRingBufferFrames": 12_288,
                }
            ] if finite else [],
        },
    }


def _plan() -> dict:
    continuous_source = CONTINUOUS_SOURCE_GUID
    finite_source = FINITE_SOURCE_GUID
    return {
        "schema": "byd-full-event-atlas-plan-v3",
        "id": "fixture-family",
        "planSha256": PLAN_SHA256,
        "perspectives": {
            perspective: {
                "eventPath": f"event:/fixture/engine_{perspective}",
                "requiredSourceCoverage": [
                    {
                        "sourceGuid": (
                            "00000000-0000-0000-0000-000000000001"
                            if perspective == "cabin"
                            else "00000000-0000-0000-0000-000000000002"
                        )
                    }
                ],
                "logicalChannelMultiplicity": {
                    "maximumLogicalSourceChannelsAtAtlasNode": 1,
                },
            }
            for perspective in PERSPECTIVES
        },
        "effects": [
            {
                "eventPath": CONTINUOUS_EVENT_PATH,
                "runtimeLifecycleParameterVariantContract": {
                    "variants": [
                        _variant(
                            source_guid=continuous_source,
                            lifetime="continuous",
                            group_id=CONTINUOUS_GROUP_ID,
                            trigger="TURBO_LOOP",
                        )
                    ]
                },
                "perspectiveResources": {
                    perspective: _perspective_resource(finite=False)
                    for perspective in PERSPECTIVES
                },
                "nodes": [
                    {
                        "requiredSourceGuid": continuous_source,
                        "requiredAuthoredBindingKey": _binding_key(
                            f"{CONTINUOUS_GROUP_ID}:{continuous_source}"
                        ),
                        "lifetime": "continuous",
                        "parameters": {"boost": boost},
                        "durationFrames": 48_000,
                    }
                    for boost in (0.0, 0.33, 0.66, 1.0)
                ],
            },
            {
                "eventPath": FINITE_EVENT_PATH,
                "runtimeLifecycleParameterVariantContract": {
                    "variants": [
                        _variant(
                            source_guid=finite_source,
                            lifetime="oneShot",
                            group_id=FINITE_GROUP_ID,
                            trigger="PARAMETER_PLACEMENT_ENTRY",
                        )
                    ]
                },
                "perspectiveResources": {
                    perspective: _perspective_resource(finite=True)
                    for perspective in PERSPECTIVES
                },
                "nodes": [
                    {
                        "requiredSourceGuid": finite_source,
                        "requiredAuthoredBindingKey": _binding_key(
                            f"{FINITE_GROUP_ID}:{finite_source}"
                        ),
                        "lifetime": "oneShot",
                        "parameters": {"throttle": throttle},
                        "durationFrames": 512,
                    }
                    for throttle in (0.0, 0.33, 0.66, 1.0)
                ],
            },
        ],
    }


def _plan_with_two_bindings_reusing_one_source_guid() -> dict:
    plan = _plan()
    event = next(
        event for event in plan["effects"]
        if event["eventPath"] == FINITE_EVENT_PATH
    )
    second_binding_key = _binding_key(
        f"{FINITE_GROUP_ID}:{FINITE_SOURCE_GUID}:second-authored-occurrence"
    )
    second_variant = _variant(
        source_guid=FINITE_SOURCE_GUID,
        lifetime="oneShot",
        group_id=FINITE_GROUP_ID,
        trigger="PARAMETER_PLACEMENT_ENTRY",
    )
    second_variant["authoredBindingKey"] = second_binding_key
    second_variant["runtimeMapping"]["authoredBindingKey"] = second_binding_key
    event["runtimeLifecycleParameterVariantContract"]["variants"].append(
        second_variant
    )
    event["nodes"].extend(
        {
            "requiredSourceGuid": FINITE_SOURCE_GUID,
            "requiredAuthoredBindingKey": second_binding_key,
            "lifetime": "oneShot",
            "parameters": {"throttle": throttle},
            "durationFrames": 512,
        }
        for throttle in (0.0, 0.33, 0.66, 1.0)
    )
    for resource in event["perspectiveResources"].values():
        resource["finite"]["maximumMappedSourceCornerRegionsDuringMaterialization"] = 16
        group = resource["finite"]["groups"][0]
        group["maximumSourceCornerContributorsPerLogicalRing"] = 8
        group["maximumFmodSourceChannelsPerLogicalRing"] = 2

    return plan


def _plan_with_second_finite_group(
    *,
    first_span: tuple[float, float, bool],
    second_span: tuple[float, float, bool],
) -> dict:
    plan = _plan()
    event = next(
        event
        for event in plan["effects"]
        if event["eventPath"] == FINITE_EVENT_PATH
    )
    first_variant = event["runtimeLifecycleParameterVariantContract"]["variants"][0]
    first_placement = first_variant["runtimeMapping"]["parameterPlacementEntry"]
    first_range = first_placement["membership"]["placements"]["rpms"][0]
    first_range["start"], first_range["end"], first_range["includeEnd"] = first_span
    first_variant["runtimeMapping"]["semanticLifecycle"][0][
        "parameterPlacementEntry"
    ] = copy.deepcopy(first_placement)

    second_group_id = "group:finite-second"
    second_source_guid = "00000000-0000-0000-0000-000000000021"
    second_variant = _variant(
        source_guid=second_source_guid,
        lifetime="oneShot",
        group_id=second_group_id,
        trigger="PARAMETER_PLACEMENT_ENTRY",
    )
    second_placement = second_variant["runtimeMapping"]["parameterPlacementEntry"]
    second_range = second_placement["membership"]["placements"]["rpms"][0]
    second_range["start"], second_range["end"], second_range["includeEnd"] = second_span
    second_variant["runtimeMapping"]["semanticLifecycle"][0][
        "parameterPlacementEntry"
    ] = copy.deepcopy(second_placement)
    event["runtimeLifecycleParameterVariantContract"]["variants"].append(
        second_variant
    )
    second_binding_key = second_variant["authoredBindingKey"]
    event["nodes"].extend(
        {
            "requiredSourceGuid": second_source_guid,
            "requiredAuthoredBindingKey": second_binding_key,
            "lifetime": "oneShot",
            "parameters": {"throttle": throttle},
            "durationFrames": 512,
        }
        for throttle in (0.0, 0.33, 0.66, 1.0)
    )
    for resource in event["perspectiveResources"].values():
        resource["finite"]["maximumMappedSourceCornerRegionsDuringMaterialization"] = 8
        resource["finite"]["groups"].append(
            {
                "groupId": second_group_id,
                "semanticTriggers": ["PARAMETER_PLACEMENT_ENTRY"],
                "maximumSourceCornerContributorsPerLogicalRing": 4,
                "maximumFmodSourceChannelsPerLogicalRing": 1,
                "maximumCaptureFramesPerLogicalRing": 512,
                "streamingRingBufferFrames": 12_288,
            }
        )

    return plan


def _state_graph(plan: dict) -> dict:
    return produce_full_event_session_state_graph(
        plan,
        implementation_source_root=REPOSITORY_ROOT,
    )


def _witness_bindings(plan: dict) -> dict:
    groups = sorted({
        (event["eventPath"], variant["schedulingGroup"]["groupId"])
        for event in plan["effects"]
        for variant in event["runtimeLifecycleParameterVariantContract"]["variants"]
        if variant["lifetime"] != "continuous"
    })
    return {
        "schema": HOST_WITNESS_BINDINGS_SCHEMA,
        "atlasFamilyId": plan["id"],
        "planSha256": plan["planSha256"],
        "bindings": [
            {
                "perspective": perspective,
                "programMode": mode,
                "eventPath": event_path,
                "groupId": group_id,
                "peakWitnessScenarioIds": [
                    f"{perspective}-{mode}-{scheduling_group_key(event_path, group_id)}-peak"
                ],
                "minimumReentryWitnessScenarioIds": [
                    f"{perspective}-{mode}-{scheduling_group_key(event_path, group_id)}-peak"
                ],
            }
            for perspective in PERSPECTIVES
            for mode in PROGRAM_MODES
            for event_path, group_id in groups
        ],
    }


def _event_node_records(plan: dict, event_path: str) -> list[dict]:
    event = next(event for event in plan["effects"] if event["eventPath"] == event_path)
    variants = {
        variant["authoredBindingKey"]: variant
        for variant in event["runtimeLifecycleParameterVariantContract"]["variants"]
    }
    return [
        {
            "key": effect_node_key(
                event_path,
                node["requiredSourceGuid"],
                node["requiredAuthoredBindingKey"],
                node["parameters"],
            ),
            "bindingKey": node["requiredAuthoredBindingKey"],
            "sourceGuid": variants[node["requiredAuthoredBindingKey"]]["sourceGuid"],
            "durationFrames": node["durationFrames"],
        }
        for node in event["nodes"]
    ]


def _scenario(
    plan: dict,
    reachability_entry: dict,
) -> dict:
    perspective = reachability_entry["perspective"]
    mode = reachability_entry["programMode"]
    if "groupKey" in reachability_entry:
        group_key = reachability_entry["groupKey"]
        scenario_id = f"{perspective}-{mode}-{group_key}-peak"
        phase = reachability_entry["peakWitnessInitialHostPhaseFrames"]
        trigger_records = [
            {**record, "groupKey": group_key}
            for record in reachability_entry["peakWitnessTriggerHostRecords"]
        ]
        host_path = reachability_entry["peakWitnessHostPath"]
    else:
        scenario_id = f"{perspective}-{mode}-peak"
        phase = reachability_entry["ringPoolPeakWitnessInitialHostPhaseFrames"]
        trigger_records = reachability_entry["ringPoolPeakTriggerHostRecords"]
        host_path = reachability_entry["ringPoolPeakHostPath"]
    continuous_nodes = _event_node_records(plan, CONTINUOUS_EVENT_PATH)
    continuous_bindings = {
        node["bindingKey"]: node for node in continuous_nodes
    }
    finite_groups: dict[str, dict] = {}
    for event in plan["effects"]:
        event_path = event["eventPath"]
        event_nodes = _event_node_records(plan, event_path)
        for variant in event["runtimeLifecycleParameterVariantContract"]["variants"]:
            if variant["lifetime"] == "continuous":
                continue
            binding_key = variant["authoredBindingKey"]
            group_id = variant["schedulingGroup"]["groupId"]
            group_key = scheduling_group_key(event_path, group_id)
            group = finite_groups.setdefault(
                group_key,
                {
                    "eventPath": event_path,
                    "groupId": group_id,
                    "nodes": [],
                    "bindings": {},
                },
            )
            binding_nodes = [
                node for node in event_nodes if node["bindingKey"] == binding_key
            ]
            group["nodes"].extend(binding_nodes)
            if binding_nodes:
                group["bindings"][binding_key] = binding_nodes[0]
    for group_key, group in finite_groups.items():
        if not group["nodes"] or not group["bindings"]:
            raise AssertionError(f"fixture finite group {group_key} has no exact nodes")

    rings = [
        {
            "instanceId": f"{scenario_id}-ring-{index}",
            "eventPath": finite_groups[trigger["groupKey"]]["eventPath"],
            "groupKey": trigger["groupKey"],
            "groupId": finite_groups[trigger["groupKey"]]["groupId"],
            "triggerHostFrame": trigger_frame,
            "startFrame": (
                (trigger_frame + DSP_BUFFER_FRAMES - 1)
                // DSP_BUFFER_FRAMES
                * DSP_BUFFER_FRAMES
            ),
            "endFrameExclusive": (
                (trigger_frame + DSP_BUFFER_FRAMES - 1)
                // DSP_BUFFER_FRAMES
                * DSP_BUFFER_FRAMES
                + max(
                    node["durationFrames"]
                    for node in finite_groups[trigger["groupKey"]]["nodes"]
                )
            ),
            "contributorNodeKeys": sorted(
                node["key"]
                for node in finite_groups[trigger["groupKey"]]["nodes"]
            ),
            "activationPerspective": trigger["activationPerspective"],
        }
        for index, trigger in enumerate(trigger_records)
        for trigger_frame in [trigger["hostFrame"]]
    ]
    final_frame = max(ring["endFrameExclusive"] for ring in rings)
    final_frame = (
        (final_frame + DSP_BUFFER_FRAMES - 1)
        // DSP_BUFFER_FRAMES
        * DSP_BUFFER_FRAMES
    )
    snapshots = []
    for frame in range(0, final_frame + DSP_BUFFER_FRAMES, DSP_BUFFER_FRAMES):
        delivered_states = [
            state
            for state in host_path
            if (
                (state["hostFrame"] + DSP_BUFFER_FRAMES - 1)
                // DSP_BUFFER_FRAMES
                * DSP_BUFFER_FRAMES
            ) <= frame
        ]
        current_state = delivered_states[-1] if delivered_states else host_path[0]
        selected_perspective = current_state["selectedPerspective"]
        selected_mode = current_state["programMode"]
        delivered_path = delivered_states if delivered_states else [host_path[0]]
        activation_marker = delivered_path[0]["hostFrame"]
        for before, after in zip(delivered_path, delivered_path[1:]):
            if before["selectedPerspective"] != after["selectedPerspective"]:
                activation_marker = after["hostFrame"]
        active_rings = [
            ring
            for ring in rings
            if ring["startFrame"] <= frame < ring["endFrameExclusive"]
        ]
        engine_source = (
            "00000000-0000-0000-0000-000000000001"
            if selected_perspective == "cabin"
            else "00000000-0000-0000-0000-000000000002"
        )
        voices = [
            {
                "voiceToken": (
                    f"{scenario_id}-engine-{selected_perspective}-{activation_marker}"
                ),
                "kind": "engineContinuous",
                "sourceGuid": engine_source,
                "eventPath": f"event:/fixture/engine_{selected_perspective}",
                "activationPerspective": selected_perspective,
                "isVirtual": False,
                "audibility": 1.0,
                "authoredRouteGain": 1.0,
            }
        ]
        voices.extend(
            {
                "voiceToken": f"{scenario_id}-continuous-{binding_key}",
                "kind": "effectContinuous",
                "sourceGuid": node["sourceGuid"],
                "authoredBindingKey": binding_key,
                "eventPath": CONTINUOUS_EVENT_PATH,
                "isVirtual": False,
                "audibility": 1.0,
                "authoredRouteGain": 1.0,
            }
            for binding_key, node in sorted(continuous_bindings.items())
        )
        for ring in active_rings:
            group = finite_groups[ring["groupKey"]]
            voices.extend(
                {
                    "voiceToken": f"{ring['instanceId']}-{binding_key}",
                    "kind": "effectFinite",
                    "sourceGuid": node["sourceGuid"],
                    "authoredBindingKey": binding_key,
                    "eventPath": group["eventPath"],
                    "logicalRingInstanceId": ring["instanceId"],
                    "isVirtual": False,
                    "audibility": 1.0,
                    "authoredRouteGain": 1.0,
                }
                for binding_key, node in sorted(group["bindings"].items())
            )
        snapshots.append(
            {
                "afterDspBlockStartFrame": frame,
                "selectedPerspective": selected_perspective,
                "programMode": selected_mode,
                "engineProgramThrottle": {
                    "LOAD": 1.0,
                    "COAST": 0.0,
                    "BOTH": 0.4,
                }[selected_mode],
                "voices": voices,
                "systemLogicalChannels": len(voices),
                "systemRealChannels": len(voices),
                "engineActiveSourceGuidsByActivationPerspective": {
                    activation_perspective: (
                        [engine_source]
                        if activation_perspective == selected_perspective
                        else []
                    )
                    for activation_perspective in PERSPECTIVES
                },
                "continuousEffectNodeKeys": sorted(
                    node["key"] for node in continuous_nodes
                ),
                "retainedContinuousEffectNodeKeysByActivationPerspective": {
                    activation_perspective: sorted(
                        node["key"] for node in continuous_nodes
                    )
                    for activation_perspective in PERSPECTIVES
                },
                "finiteLogicalRings": copy.deepcopy(active_rings),
            }
        )
    return {
        "id": scenario_id,
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "host-control-peak",
        "initialHostPhaseFrames": phase,
        "tailDrained": True,
        "snapshots": snapshots,
    }


def _camera_tail_scenario(plan: dict, perspective: str, mode: str) -> dict:
    opposite = "exterior" if perspective == "cabin" else "cabin"
    source_by_perspective = {
        "cabin": "00000000-0000-0000-0000-000000000001",
        "exterior": "00000000-0000-0000-0000-000000000002",
    }
    snapshots = []
    for frame, selected, active_perspectives in (
        (0, opposite, (opposite,)),
        (DSP_BUFFER_FRAMES, perspective, (opposite, perspective)),
        (DSP_BUFFER_FRAMES * 2, perspective, (perspective,)),
    ):
        voices = [
            {
                "voiceToken": f"camera-{perspective}-{mode}-engine-{activation_perspective}",
                "kind": "engineContinuous",
                "sourceGuid": source_by_perspective[activation_perspective],
                "eventPath": f"event:/fixture/engine_{activation_perspective}",
                "activationPerspective": activation_perspective,
                "isVirtual": False,
                "audibility": 1.0,
                "authoredRouteGain": 1.0,
            }
            for activation_perspective in active_perspectives
        ]
        snapshots.append(
            {
                "afterDspBlockStartFrame": frame,
                "selectedPerspective": selected,
                "programMode": mode,
                "engineProgramThrottle": {
                    "LOAD": 1.0,
                    "COAST": 0.0,
                    "BOTH": 0.4,
                }[mode],
                "voices": voices,
                "systemLogicalChannels": len(voices),
                "systemRealChannels": len(voices),
                "engineActiveSourceGuidsByActivationPerspective": {
                    activation_perspective: (
                        [source_by_perspective[activation_perspective]]
                        if activation_perspective in active_perspectives
                        else []
                    )
                    for activation_perspective in PERSPECTIVES
                },
                "continuousEffectNodeKeys": [],
                "retainedContinuousEffectNodeKeysByActivationPerspective": {
                    activation_perspective: []
                    for activation_perspective in PERSPECTIVES
                },
                "finiteLogicalRings": [],
            }
        )

    return {
        "id": f"{perspective}-{mode}-camera-switch-tail",
        "perspective": perspective,
        "programMode": mode,
        "trajectoryKind": "camera-switch-tail",
        "initialHostPhaseFrames": 0,
        "tailDrained": True,
        "snapshots": snapshots,
    }


def _attach_source_binding_oracles(scenarios: list[dict]) -> dict[str, dict]:
    registry: dict[str, dict] = {}
    for scenario in scenarios:
        for snapshot in scenario["snapshots"]:
            for voice in snapshot["voices"]:
                diagnostic_name = (
                    "fixture-source-"
                    + voice["sourceGuid"]
                    + "-"
                    + str(
                        voice.get("authoredBindingKey")
                        or voice.get("activationPerspective")
                    )
                )
                evidence = {
                    "schema": "byd-original-bank-source-solo-binding-oracle-v1",
                    "originalBankSha256": "a" * 64,
                    "graphSha256": "b" * 64,
                    "eventPath": voice["eventPath"],
                    "sourceGuid": voice["sourceGuid"],
                    "authoredBindingKey": voice.get("authoredBindingKey"),
                    "activationPerspective": voice.get("activationPerspective"),
                    "callbackSoundIdentity": diagnostic_name,
                    "parameters": {},
                    "mutedReachableWaveformGuids": [],
                    "disabledFullyMutedParentGuids": [],
                    "derivativeBankSha256": "c" * 64,
                    "derivativeDifferingByteOffsets": [],
                    "scheduledSoundIdentities": [diagnostic_name],
                    "originalBankUnchangedAfterProbe": True,
                }
                oracle_sha = hashlib.sha256(
                    canonical_json_bytes(evidence)
                ).hexdigest()
                registry.setdefault(oracle_sha, evidence)
                voice["sourceBindingOracleSha256"] = oracle_sha

    return registry


def _observations(plan: dict, *, include_camera_tail: bool = False) -> dict:
    graph = _state_graph(plan)
    bindings = _witness_bindings(plan)
    reachability = explore_host_control_reachability(
        plan,
        graph,
        bindings,
        implementation_source_root=REPOSITORY_ROOT,
    )
    scenarios = [
        _scenario(plan, entry)
        for entry in reachability["groups"] + reachability["sessionContexts"]
    ]
    if include_camera_tail:
        scenarios.extend(
            _camera_tail_scenario(plan, perspective, mode)
            for perspective in PERSPECTIVES
            for mode in PROGRAM_MODES
        )
    source_binding_oracles = _attach_source_binding_oracles(scenarios)
    return {
        "schema": "byd-full-event-causal-resource-observations-v1",
        "atlasFamilyId": plan["id"],
        "planSha256": plan["planSha256"],
        "dspBufferFrames": DSP_BUFFER_FRAMES,
        "sampleRateHz": SAMPLE_RATE_HZ,
        "hostControlHz": HOST_CONTROL_HZ,
        "hostTickFrames": HOST_TICK_FRAMES,
        "assettoStudioLogicalChannelCap": 2_048,
        "assettoSoftwareRealChannelBudget": 256,
        "hostControlStateGraph": graph,
        "hostControlWitnessBindings": bindings,
        "hostControlReachability": reachability,
        "sourceBindingOraclesBySha256": source_binding_oracles,
        "scenarios": scenarios,
    }


def _rehash_proof(value: dict) -> None:
    value.pop("proofSha256", None)
    value["proofSha256"] = hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _rehash_reachability(value: dict) -> None:
    value.pop("exhaustiveStateSpaceProofSha256", None)
    value["exhaustiveStateSpaceProofSha256"] = hashlib.sha256(
        canonical_json_bytes(value)
    ).hexdigest()


class HostControlReachabilityExplorerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.plan = _plan()
        self.graph = _state_graph(self.plan)
        self.bindings = _witness_bindings(self.plan)

    def explore(self) -> dict:
        return explore_host_control_reachability(
            self.plan,
            self.graph,
            self.bindings,
            implementation_source_root=REPOSITORY_ROOT,
        )

    def test_explores_every_phase_to_a_fixed_point_and_finds_same_dsp_overlap(self) -> None:
        proof = self.explore()
        self.assertTrue(proof["stateSpaceExhaustive"])
        self.assertEqual(proof["initialHostPhaseFrames"], list(range(256)))
        self.assertEqual(len(proof["groups"]), 6)
        for group in proof["groups"]:
            self.assertEqual(len(group["maximumLiveRingsByInitialHostPhase"]), 256)
            self.assertGreaterEqual(group["maximumReachablePhysicalLiveRings"], 2)
            self.assertEqual(group["minimumHostTicksBetweenStarts"], 2)
            self.assertGreater(group["exhaustiveStateCount"], 256)
            self.assertGreater(group["exhaustiveTransitionCount"], 256)
            self.assertEqual(
                group["peakWitnessTriggerHostFrames"],
                sorted(group["peakWitnessTriggerHostFrames"]),
            )

    def test_result_is_deterministic(self) -> None:
        self.assertEqual(self.explore(), self.explore())

    def test_rejects_falsified_graph_hash(self) -> None:
        self.graph["groupMachines"][0]["states"][0]["transitions"].pop()
        with self.assertRaisesRegex(CausalResourceProofError, "self hash differs"):
            self.explore()

    def test_rejects_falsified_control_implementation_manifest(self) -> None:
        self.graph["implementationSources"][0]["sha256"] = hashlib.sha256(b"other").hexdigest()
        self.graph["hostControlImplementationSha256"] = hashlib.sha256(
            canonical_json_bytes(self.graph["implementationSources"])
        ).hexdigest()
        self.graph.pop("graphSha256")
        self.graph["graphSha256"] = hashlib.sha256(canonical_json_bytes(self.graph)).hexdigest()
        with self.assertRaisesRegex(CausalResourceProofError, "deterministic executable-contract producer"):
            self.explore()

    def test_rejects_declared_incomplete_state_space_even_with_valid_hash(self) -> None:
        self.graph["stateSpaceComplete"] = False
        self.graph.pop("graphSha256")
        self.graph["graphSha256"] = hashlib.sha256(canonical_json_bytes(self.graph)).hexdigest()
        with self.assertRaisesRegex(CausalResourceProofError, "deterministic executable-contract producer"):
            self.explore()

    def test_rejects_unreachable_state_even_with_valid_hash(self) -> None:
        self.graph["groupMachines"][0]["states"].append(
            {
                "id": "unreachable",
                "selectedPerspective": "cabin",
                "programMode": "LOAD",
                "transitions": [{"targetStateId": "unreachable", "emissions": []}],
            }
        )
        self.graph.pop("graphSha256")
        self.graph["graphSha256"] = hashlib.sha256(canonical_json_bytes(self.graph)).hexdigest()
        with self.assertRaisesRegex(CausalResourceProofError, "deterministic executable-contract producer"):
            self.explore()

    def test_rejects_missing_phase_mode_context(self) -> None:
        self.graph["groupMachines"][0]["states"][-1]["programMode"] = "LOAD"
        self.graph.pop("graphSha256")
        self.graph["graphSha256"] = hashlib.sha256(canonical_json_bytes(self.graph)).hexdigest()
        with self.assertRaisesRegex(CausalResourceProofError, "deterministic executable-contract producer"):
            self.explore()

    def test_rejects_missing_witness_binding(self) -> None:
        self.bindings["bindings"].pop()
        with self.assertRaisesRegex(CausalResourceProofError, "matrix is incomplete"):
            self.explore()

    def test_global_session_preserves_cross_group_host_value_correlation(self) -> None:
        plan = _plan_with_second_finite_group(
            first_span=(0.0, 1_000.0, False),
            second_span=(2_000.0, 3_000.0, True),
        )
        graph = _state_graph(plan)
        self.assertNotIn(
            (True, True),
            {
                tuple(state["membership"])
                for state in graph["sessionMachine"]["states"]
            },
        )
        reachability = explore_host_control_reachability(
            plan,
            graph,
            _witness_bindings(plan),
            implementation_source_root=REPOSITORY_ROOT,
        )
        group_sum = sum(
            entry["maximumReachablePhysicalLiveRings"]
            for entry in reachability["groups"]
            if entry["perspective"] == "cabin"
            and entry["programMode"] == "LOAD"
        )
        session = next(
            entry
            for entry in reachability["sessionContexts"]
            if entry["perspective"] == "cabin"
            and entry["programMode"] == "LOAD"
        )
        self.assertEqual(group_sum, 4)
        self.assertEqual(session["maximumReachablePhysicalLiveRings"], 3)
        self.assertEqual(
            session["maximumReachableSessionRingPoolBytes"],
            3 * 12_288 * 8,
        )

    def test_global_session_combines_simultaneous_group_starts_atomically(self) -> None:
        plan = _plan_with_second_finite_group(
            first_span=(1_000.0, 2_000.0, True),
            second_span=(1_000.0, 2_000.0, True),
        )
        reachability = explore_host_control_reachability(
            plan,
            _state_graph(plan),
            _witness_bindings(plan),
            implementation_source_root=REPOSITORY_ROOT,
        )
        session = next(
            entry
            for entry in reachability["sessionContexts"]
            if entry["perspective"] == "cabin"
            and entry["programMode"] == "LOAD"
        )
        groups_by_frame: dict[int, set[str]] = {}
        for record in session["ringPoolPeakTriggerHostRecords"]:
            groups_by_frame.setdefault(record["hostFrame"], set()).add(
                record["groupKey"]
            )
        self.assertTrue(any(len(groups) == 2 for groups in groups_by_frame.values()))
        self.assertEqual(
            session["maximumReachableNewContributorsPerDspUpdate"],
            8,
        )


class SessionStateGraphProducerTest(unittest.TestCase):
    def produce(self, plan: dict) -> dict:
        return produce_full_event_session_state_graph(
            plan,
            implementation_source_root=REPOSITORY_ROOT,
        )

    def test_produces_only_from_exact_owner_placement_and_binding_contracts(self) -> None:
        plan = _plan()
        graph = self.produce(plan)
        group = graph["groupMachines"][0]
        self.assertEqual(group["eventPath"], FINITE_EVENT_PATH)
        self.assertEqual(group["groupId"], FINITE_GROUP_ID)
        self.assertEqual(
            {binding["authoredBindingKey"] for binding in group["bindings"]},
            {_binding_key(f"{FINITE_GROUP_ID}:{FINITE_SOURCE_GUID}")},
        )
        self.assertTrue(any(initial["emissions"] for initial in group["initialStates"]))
        self.assertTrue(any(not initial["emissions"] for initial in group["initialStates"]))
        self.assertTrue(
            all(
                isinstance(state["hostValues"].get("EngineSimulation.rpm"), float)
                for state in group["states"]
            )
        )
        self.assertTrue(
            all("hostValues" in state for state in graph["sessionMachine"]["states"])
        )
        self.assertEqual(
            group["schedulingGroup"]["selectionRuntimeContract"]["stateScope"],
            "selectionKindSpecificSeeSelectionStateOwnership",
        )
        self.assertIn(
            "profileAudioSessionGeneration",
            group["schedulingGroup"]["selectionRuntimeContract"]["seedDerivation"][
                "formula"
            ],
        )

    def test_rejects_missing_owner_in_any_executable_variant(self) -> None:
        plan = _plan()
        continuous = plan["effects"][0]["runtimeLifecycleParameterVariantContract"]["variants"][0]
        continuous["runtimeMapping"].pop("eventInstanceOwnership")
        with self.assertRaisesRegex(SessionStateGraphProducerError, "event-instance ownership"):
            self.produce(plan)

    def test_rejects_owner_contract_disagreement_within_exact_event_path(self) -> None:
        plan = _plan_with_two_bindings_reusing_one_source_guid()
        variants = plan["effects"][1]["runtimeLifecycleParameterVariantContract"]["variants"]
        variants[1]["runtimeMapping"]["eventInstanceOwnership"]["activationGeneration"] = (
            "differentGeneration"
        )
        with self.assertRaisesRegex(SessionStateGraphProducerError, "disagree"):
            self.produce(plan)

    def test_rejects_unproven_finite_topology(self) -> None:
        plan = _plan()
        finite = plan["effects"][1]["runtimeLifecycleParameterVariantContract"]["variants"][0]
        finite["runtimeMapping"]["finiteLifecycleTopology"]["status"] = (
            "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE"
        )
        with self.assertRaisesRegex(SessionStateGraphProducerError, "not source-solo placement PASS"):
            self.produce(plan)

    def test_rejects_tampered_initial_placement_semantics(self) -> None:
        plan = _plan()
        finite = plan["effects"][1]["runtimeLifecycleParameterVariantContract"]["variants"][0]
        finite["runtimeMapping"]["parameterPlacementEntry"]["initialState"]["inside"] = (
            "neverStart"
        )
        finite["runtimeMapping"]["semanticLifecycle"][0]["parameterPlacementEntry"] = copy.deepcopy(
            finite["runtimeMapping"]["parameterPlacementEntry"]
        )
        with self.assertRaisesRegex(SessionStateGraphProducerError, "initial-state semantics"):
            self.produce(plan)

    def test_rejects_stale_playlist_selection_scope_or_seed_contract(self) -> None:
        plan = _plan()
        finite = plan["effects"][1]["runtimeLifecycleParameterVariantContract"][
            "variants"
        ][0]
        selection = finite["schedulingGroup"]["selectionRuntimeContract"]
        selection["stateScope"] = "perEventInstancePerGroupIdPerPerspective"
        selection["seedDerivation"][
            "formula"
        ] = "sha256('stale-v1-with-perspective')"
        finite["runtimeMapping"]["schedulingGroup"] = copy.deepcopy(
            finite["schedulingGroup"]
        )
        with self.assertRaisesRegex(SessionStateGraphProducerError, "frozen playlist v3"):
            self.produce(plan)

    def test_engine_event_throttle_uses_load_coast_and_both_program_semantics(self) -> None:
        plan = _plan()
        finite = plan["effects"][1]["runtimeLifecycleParameterVariantContract"]["variants"][0]
        mapping = finite["runtimeMapping"]
        mapping["hostGainClass"] = "engineEvent"
        placement = mapping["parameterPlacementEntry"]
        placement["membership"]["placements"] = {
            "throttle": [
                {
                    "start": 0.5,
                    "end": 1.0,
                    "includeEnd": True,
                    "parameterGuid": "00000000-0000-0000-0000-000000000111",
                    "layoutGuid": "00000000-0000-0000-0000-000000000112",
                    "instrumentGuid": "00000000-0000-0000-0000-000000000113",
                }
            ]
        }
        placement["membership"]["parameterValues"] = [
            {
                "parameter": "throttle",
                "parameterGuid": "00000000-0000-0000-0000-000000000111",
                "layoutGuid": "00000000-0000-0000-0000-000000000112",
                "value": {
                    "kind": "hostBinding",
                    "binding": {
                        "parameter": "throttle",
                        "source": "EngineSimulation.throttle",
                    },
                },
            }
        ]
        mapping["parameterDomains"] = {"throttle": [0.0, 1.0]}
        mapping["semanticLifecycle"][0]["parameterPlacementEntry"] = copy.deepcopy(placement)

        graph = self.produce(plan)
        states = graph["groupMachines"][0]["states"]
        by_mode = {
            mode: {
                tuple(state["membership"])
                for state in states
                if state["programMode"] == mode
            }
            for mode in PROGRAM_MODES
        }
        self.assertEqual(by_mode["LOAD"], {(True,)})
        self.assertEqual(by_mode["COAST"], {(False,)})
        self.assertEqual(by_mode["BOTH"], {(False,), (True,)})


class AuthoredBindingCausalVoiceTest(unittest.TestCase):
    def test_one_authored_binding_with_four_interpolation_nodes_is_one_raw_voice(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        proof = prove_causal_full_event_resources(
            plan,
            observations,
            required_trajectory_kinds=["host-control-peak"],
            implementation_source_root=REPOSITORY_ROOT,
        )
        for report in proof["perPerspective"].values():
            peak_rings = report["maximumPhysicalLiveLogicalRingInstances"]
            self.assertGreaterEqual(peak_rings, 2)
            self.assertEqual(
                report["maximumFiniteEffectLogicalChannels"],
                peak_rings,
            )
            self.assertEqual(
                report["maximumSystemLogicalChannels"],
                2 + peak_rings,
            )

    def test_rejects_substitution_of_an_authored_binding_even_when_source_guid_matches(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        scenario = observations["scenarios"][0]
        finite_voice = next(
            voice
            for snapshot in scenario["snapshots"]
            for voice in snapshot["voices"]
            if voice["kind"] == "effectFinite"
        )
        finite_voice["authoredBindingKey"] = _binding_key("substituted-binding")
        with self.assertRaisesRegex(CausalResourceProofError, "authored bindings"):
            prove_causal_full_event_resources(
                plan,
                observations,
                required_trajectory_kinds=["host-control-peak"],
                implementation_source_root=REPOSITORY_ROOT,
            )

    def test_two_authored_bindings_reusing_one_source_guid_are_two_raw_voices(self) -> None:
        plan = _plan_with_two_bindings_reusing_one_source_guid()
        proof = prove_causal_full_event_resources(
            plan,
            _observations(plan),
            required_trajectory_kinds=["host-control-peak"],
            implementation_source_root=REPOSITORY_ROOT,
        )
        for report in proof["perPerspective"].values():
            peak_rings = report["maximumPhysicalLiveLogicalRingInstances"]
            self.assertEqual(
                report["maximumFiniteEffectLogicalChannels"],
                peak_rings * 2,
            )
            self.assertEqual(
                report["maximumSystemLogicalChannels"],
                2 + peak_rings * 2,
            )

    def test_session_pool_keeps_old_activation_perspective_rings_after_camera_switch(self) -> None:
        plan = _plan()
        proof = prove_causal_full_event_resources(
            plan,
            _observations(plan),
            required_trajectory_kinds=["host-control-peak"],
            implementation_source_root=REPOSITORY_ROOT,
        )
        group_key = scheduling_group_key(FINITE_EVENT_PATH, FINITE_GROUP_ID)
        exterior = proof["perPerspective"]["exterior"]
        activation_peak = exterior[
            "physicalLiveLogicalRingInstancesByActivationPerspectiveAndGroup"
        ]
        self.assertEqual(activation_peak["cabin"][group_key], 1)
        self.assertEqual(activation_peak["exterior"][group_key], 1)
        self.assertEqual(exterior["finiteRingPoolBytes"], 2 * 12_288 * 8)

    def test_camera_switch_snapshot_accounts_old_and_new_engine_raw_voices(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        scenario = next(
            item
            for item in observations["scenarios"]
            if item["perspective"] == "exterior" and item["programMode"] == "LOAD"
        )
        switch_index = next(
            index
            for index, snapshot in enumerate(scenario["snapshots"])
            if index > 0
            and snapshot["selectedPerspective"] == "exterior"
            and scenario["snapshots"][index - 1]["selectedPerspective"] == "cabin"
        )
        snapshot = scenario["snapshots"][switch_index]
        cabin_source = "00000000-0000-0000-0000-000000000001"
        snapshot["voices"].append(
            {
                "voiceToken": f"{scenario['id']}-old-cabin-engine-tail",
                "kind": "engineContinuous",
                "sourceGuid": cabin_source,
                "eventPath": "event:/fixture/engine_cabin",
                "activationPerspective": "cabin",
                "isVirtual": False,
                "audibility": 1.0,
                "authoredRouteGain": 1.0,
            }
        )
        snapshot["engineActiveSourceGuidsByActivationPerspective"]["cabin"].append(
            cabin_source
        )
        snapshot["systemLogicalChannels"] += 1
        snapshot["systemRealChannels"] += 1

        proof = prove_causal_full_event_resources(
            plan,
            observations,
            required_trajectory_kinds=["host-control-peak"],
            implementation_source_root=REPOSITORY_ROOT,
        )
        self.assertGreaterEqual(
            proof["perPerspective"]["exterior"]["maximumSystemLogicalChannels"],
            3,
        )


class CausalProofAdversarialTest(unittest.TestCase):
    def prove(self, plan: dict, observations: dict) -> dict:
        return prove_causal_full_event_resources(
            plan,
            observations,
            required_trajectory_kinds=["host-control-peak"],
            implementation_source_root=REPOSITORY_ROOT,
        )

    def prove_release(self, plan: dict) -> dict:
        return prove_causal_full_event_resources(
            plan,
            _observations(plan, include_camera_tail=True),
            required_trajectory_kinds=["host-control-peak", "camera-switch-tail"],
            implementation_source_root=REPOSITORY_ROOT,
        )

    def test_rejects_virtual_voice_without_exact_inaudibility_proof(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        snapshot["voices"][0]["isVirtual"] = True
        snapshot["systemRealChannels"] -= 1
        with self.assertRaisesRegex(CausalResourceProofError, "virtual voice proof"):
            self.prove(plan, observations)

    def test_rejects_source_binding_oracle_for_another_exact_source(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        voice = observations["scenarios"][0]["snapshots"][0]["voices"][0]
        original_oracle = observations["sourceBindingOraclesBySha256"][
            voice["sourceBindingOracleSha256"]
        ]
        substituted_oracle = copy.deepcopy(original_oracle)
        substituted_oracle["sourceGuid"] = (
            "00000000-0000-0000-0000-000000000002"
        )
        substituted_sha = hashlib.sha256(
            canonical_json_bytes(substituted_oracle)
        ).hexdigest()
        observations["sourceBindingOraclesBySha256"][
            substituted_sha
        ] = substituted_oracle
        voice["sourceBindingOracleSha256"] = substituted_sha

        with self.assertRaisesRegex(
            CausalResourceProofError,
            "substitutes event/source identity",
        ):
            self.prove(plan, observations)

    def test_accepts_inaudible_virtual_binding_represented_real_elsewhere(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        _make_voice_virtual(snapshot, snapshot["voices"][0])

        proof = self.prove(plan, observations)

        self.assertEqual(proof["schema"], "byd-full-event-causal-resource-proof-v1")

    def test_rejects_virtual_binding_with_audible_fmod_measurement(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        _make_voice_virtual(snapshot, snapshot["voices"][0], audibility=0.1)

        with self.assertRaisesRegex(CausalResourceProofError, "not proven inaudible"):
            self.prove(plan, observations)

    def test_rejects_virtual_binding_with_audible_authored_route(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        _make_voice_virtual(
            snapshot,
            snapshot["voices"][0],
            authored_route_gain=0.1,
        )

        with self.assertRaisesRegex(CausalResourceProofError, "not proven inaudible"):
            self.prove(plan, observations)

    def test_rejects_virtual_binding_never_real_or_audible_elsewhere(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        identity = (
            "engineContinuous",
            "event:/fixture/engine_cabin",
            "00000000-0000-0000-0000-000000000001",
            "cabin",
        )
        changed = 0
        for scenario in observations["scenarios"]:
            for snapshot in scenario["snapshots"]:
                for voice in snapshot["voices"]:
                    candidate = (
                        voice["kind"],
                        voice["eventPath"],
                        voice["sourceGuid"],
                        voice.get("activationPerspective"),
                    )
                    if candidate == identity:
                        _make_voice_virtual(snapshot, voice)
                        changed += 1
        self.assertGreater(changed, 0)

        with self.assertRaisesRegex(CausalResourceProofError, "never real/audible"):
            self.prove(plan, observations)

    def test_rejects_real_duplicate_only_at_the_same_virtual_observation_node(self) -> None:
        plan = _plan()
        plan["perspectives"]["cabin"]["logicalChannelMultiplicity"][
            "maximumLogicalSourceChannelsAtAtlasNode"
        ] = 2
        observations = _observations(plan)
        identity = (
            "engineContinuous",
            "event:/fixture/engine_cabin",
            "00000000-0000-0000-0000-000000000001",
            "cabin",
        )
        duplicate_snapshot = None
        duplicate_voice = None
        for scenario in observations["scenarios"]:
            for snapshot in scenario["snapshots"]:
                for voice in snapshot["voices"]:
                    candidate = (
                        voice["kind"],
                        voice["eventPath"],
                        voice["sourceGuid"],
                        voice.get("activationPerspective"),
                    )
                    if candidate != identity:
                        continue
                    _make_voice_virtual(snapshot, voice)
                    if duplicate_snapshot is None:
                        duplicate_snapshot = snapshot
                        duplicate_voice = copy.deepcopy(voice)
        self.assertIsNotNone(duplicate_snapshot)
        self.assertIsNotNone(duplicate_voice)
        assert duplicate_snapshot is not None and duplicate_voice is not None
        duplicate_voice["voiceToken"] += "-same-node-real-duplicate"
        duplicate_voice["isVirtual"] = False
        duplicate_voice["audibility"] = 1.0
        duplicate_voice["authoredRouteGain"] = 1.0
        duplicate_voice.pop("virtualizationProof")
        duplicate_snapshot["voices"].append(duplicate_voice)
        duplicate_snapshot["systemLogicalChannels"] += 1
        duplicate_snapshot["systemRealChannels"] += 1
        duplicate_snapshot["engineActiveSourceGuidsByActivationPerspective"][
            "cabin"
        ].append(duplicate_voice["sourceGuid"])

        with self.assertRaisesRegex(
            CausalResourceProofError,
            "another required node/probe",
        ):
            self.prove(plan, observations)

    def test_accepts_exact_entire_placement_silence_certificate(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        identity = (
            "engineContinuous",
            "event:/fixture/engine_cabin",
            "00000000-0000-0000-0000-000000000001",
            "cabin",
        )
        certified = False
        for scenario in observations["scenarios"]:
            for snapshot in scenario["snapshots"]:
                for voice in snapshot["voices"]:
                    candidate = (
                        voice["kind"],
                        voice["eventPath"],
                        voice["sourceGuid"],
                        voice.get("activationPerspective"),
                    )
                    if candidate != identity:
                        continue
                    _make_voice_virtual(snapshot, voice)
                    if not certified:
                        voice["staticSilenceProof"] = {
                            "schema": STATIC_SILENCE_PROOF_SCHEMA,
                            "status": "PASS",
                            "scope": "entireAuthoredPlacement",
                            "eventPath": voice["eventPath"],
                            "sourceGuid": voice["sourceGuid"],
                            "maximumAbsoluteAudibility": 0.0,
                            "maximumAbsoluteRouteGain": 0.0,
                            "oracleReportSha256": "a" * 64,
                        }
                        certified = True
        self.assertTrue(certified)

        self.prove(plan, observations)

    def test_rejects_nonzero_entire_placement_silence_certificate(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        voice = snapshot["voices"][0]
        _make_voice_virtual(snapshot, voice)
        voice["staticSilenceProof"] = {
            "schema": STATIC_SILENCE_PROOF_SCHEMA,
            "status": "PASS",
            "scope": "entireAuthoredPlacement",
            "eventPath": voice["eventPath"],
            "sourceGuid": voice["sourceGuid"],
            "maximumAbsoluteAudibility": 0.01,
            "maximumAbsoluteRouteGain": 0.0,
            "oracleReportSha256": "a" * 64,
        }

        with self.assertRaisesRegex(CausalResourceProofError, "static silence proof"):
            self.prove(plan, observations)

    def test_rejects_more_than_256_real_channels(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        snapshot = observations["scenarios"][0]["snapshots"][0]
        template = snapshot["voices"][0]
        while len(snapshot["voices"]) <= 256:
            copy_voice = copy.deepcopy(template)
            copy_voice["voiceToken"] = f"overflow-{len(snapshot['voices'])}"
            snapshot["voices"].append(copy_voice)
            snapshot["engineActiveSourceGuidsByActivationPerspective"][
                template["activationPerspective"]
            ].append(template["sourceGuid"])
        snapshot["systemLogicalChannels"] = len(snapshot["voices"])
        snapshot["systemRealChannels"] = len(snapshot["voices"])
        with self.assertRaisesRegex(CausalResourceProofError, "channel budgets"):
            self.prove(plan, observations)

    def test_rejects_ring_group_substitution(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        ring = next(
            ring
            for snapshot in observations["scenarios"][0]["snapshots"]
            for ring in snapshot["finiteLogicalRings"]
        )
        ring["groupId"] = "another-group"
        with self.assertRaisesRegex(CausalResourceProofError, "key differs"):
            self.prove(plan, observations)

    def test_rejects_wrong_activation_perspective_for_explored_camera_path(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        scenario = next(
            item
            for item in observations["scenarios"]
            if item["perspective"] == "exterior" and item["programMode"] == "LOAD"
        )
        ring = next(
            ring
            for snapshot in scenario["snapshots"]
            for ring in snapshot["finiteLogicalRings"]
            if ring["activationPerspective"] == "cabin"
        )
        ring["activationPerspective"] = "exterior"
        with self.assertRaisesRegex(CausalResourceProofError, "changes identity|exact explored peak path"):
            self.prove(plan, observations)

    def test_rejects_missing_required_trajectory(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        with self.assertRaisesRegex(CausalResourceProofError, "matrix is incomplete"):
            prove_causal_full_event_resources(
                plan,
                observations,
                required_trajectory_kinds=["host-control-peak", "camera-switch-tail"],
                implementation_source_root=REPOSITORY_ROOT,
            )

    def test_accepts_source_audibility_probe_outside_required_matrix(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        probe = _camera_tail_scenario(plan, "cabin", "LOAD")
        probe["id"] = "cabin-LOAD-engine-source-audibility-sweep"
        probe["trajectoryKind"] = "source-audibility-probe"
        observations["sourceBindingOraclesBySha256"].update(
            _attach_source_binding_oracles([probe])
        )
        observations["scenarios"].append(probe)

        self.prove(plan, observations)

    def test_rejects_required_engine_source_missing_from_every_probe(self) -> None:
        plan = _plan()
        plan["perspectives"]["cabin"]["requiredSourceCoverage"].append(
            {"sourceGuid": "00000000-0000-0000-0000-000000000003"}
        )

        with self.assertRaisesRegex(CausalResourceProofError, "engine source coverage differs"):
            self.prove(plan, _observations(plan))

    def test_rejects_falsified_reachability_count_even_with_rehashed_claim(self) -> None:
        plan = _plan()
        observations = _observations(plan)
        reachability = observations["hostControlReachability"]
        reachability["groups"][0]["exhaustiveStateCount"] += 1
        _rehash_reachability(reachability)
        with self.assertRaisesRegex(CausalResourceProofError, "deterministic exploration"):
            self.prove(plan, observations)

    def test_runtime_update_recomputes_ring_peak_bytes(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        node_to_shard = {
            node["key"]: f"effect-{index % 2}.pcm"
            for index, node in enumerate(
                _event_node_records(plan, CONTINUOUS_EVENT_PATH)
                + _event_node_records(plan, FINITE_EVENT_PATH)
            )
        }
        packed = bind_causal_proof_to_packed_shards(
            plan,
            proof,
            node_to_shard,
            engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
        )
        perspective = proof["perPerspective"]["cabin"]
        group_key = scheduling_group_key(FINITE_EVENT_PATH, FINITE_GROUP_ID)
        perspective["finiteRingPoolBytesBySchedulingGroup"][group_key] += 8
        perspective["finiteRingPoolBytes"] += 8
        _rehash_proof(proof)
        with self.assertRaisesRegex(CausalResourceProofError, "ring bytes differ"):
            causal_runtime_resource_updates(plan, proof, packed)

    def test_runtime_update_publishes_session_common_pool_from_camera_tail_proof(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        node_to_shard = {
            node["key"]: f"effect-{index % 2}.pcm"
            for index, node in enumerate(
                _event_node_records(plan, CONTINUOUS_EVENT_PATH)
                + _event_node_records(plan, FINITE_EVENT_PATH)
            )
        }
        packed = bind_causal_proof_to_packed_shards(
            plan,
            proof,
            node_to_shard,
            engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
        )
        update = causal_runtime_resource_updates(plan, proof, packed)
        self.assertEqual(update["status"], "PASS")
        self.assertEqual(
            update["sessionCommon"]["finiteRingPoolBytes"],
            max(
                selected["effects"]["finiteRingPoolBytes"]
                for selected in update["perSelectedPerspective"].values()
            ),
        )
        self.assertLessEqual(
            update["sessionCommon"]["maximumCausalFmodRealChannels"],
            256,
        )
        self.assertEqual(
            update["resourceBoundsSchema"],
            "byd-full-event-atlas-runtime-resource-bounds-v3",
        )
        self.assertEqual(
            update["resourceBoundsScope"],
            "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
        )
        self.assertEqual(update["session"]["proofStatus"], "PASS")
        cabin_session = update["session"]["perSelectedEnginePerspective"]["cabin"]
        self.assertEqual(
            cabin_session["maximumMappedShardInstancesDuringTransitionSafeUpperBound"],
            cabin_session["engineMaximumMappedShardInstancesDuringCellTransition"]
            + cabin_session["retainedCabinEffectsMaximumMappedShardInstances"]
            + cabin_session["retainedExteriorEffectsMaximumMappedShardInstances"],
        )
        self.assertEqual(
            update["perSelectedPerspective"]["cabin"]["effects"]["resourceModel"],
            "profileSessionRetainedEffectsResourceBounds-v3",
        )
        self.assertNotIn(
            "maximumUniqueMappedShardsDuringTransitionSafeUpperBound",
            update["perSelectedPerspective"]["cabin"]["total"],
        )

    def test_runtime_update_is_applied_only_to_exact_resource_v3_runtime(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        node_to_shard = {
            node["key"]: f"effect-{index % 2}.pcm"
            for index, node in enumerate(
                _event_node_records(plan, CONTINUOUS_EVENT_PATH)
                + _event_node_records(plan, FINITE_EVENT_PATH)
            )
        }
        packed = bind_causal_proof_to_packed_shards(
            plan,
            proof,
            node_to_shard,
            engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
        )
        update = causal_runtime_resource_updates(plan, proof, packed)
        runtime = {
            "id": plan["id"],
            "planSha256": plan["planSha256"],
            "resourceBounds": {
                "schema": "byd-full-event-atlas-runtime-resource-bounds-v3",
                "scope": "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
                "perPerspective": {
                    perspective: {"engine": {}, "effects": {}, "total": {}}
                    for perspective in PERSPECTIVES
                },
                "session": {
                    "proofStatus": "BLOCKED_PENDING_SESSION_MAPPING_INSTANCE_PROOF"
                },
            },
        }
        apply_causal_runtime_resource_update(runtime, update)
        self.assertEqual(runtime["resourceBounds"]["session"], update["session"])
        self.assertEqual(
            runtime["resourceBounds"]["perPerspective"]["cabin"]["effects"][
                "finiteRingPoolBytes"
            ],
            update["perSelectedPerspective"]["cabin"]["effects"][
                "finiteRingPoolBytes"
            ],
        )

        tampered = copy.deepcopy(update)
        tampered["sessionCommon"]["maximumCausalFmodLogicalChannels"] = 257
        tampered["sessionCommon"]["maximumCausalFmodRealChannels"] = 257
        _rehash_proof(tampered)
        with self.assertRaisesRegex(
            CausalResourceProofError,
            "not exactly representable",
        ):
            apply_causal_runtime_resource_update(runtime, tampered)

    def test_session_mapping_instances_do_not_collapse_same_shard_across_perspectives(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        node_to_shard = {
            node["key"]: "shared-effects.pcm"
            for node in (
                _event_node_records(plan, CONTINUOUS_EVENT_PATH)
                + _event_node_records(plan, FINITE_EVENT_PATH)
            )
        }
        packed = bind_causal_proof_to_packed_shards(
            plan,
            proof,
            node_to_shard,
            engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
        )
        cabin = packed["session"]["perSelectedEnginePerspective"]["cabin"]
        self.assertEqual(
            cabin["retainedCabinEffectsMaximumMappedShardInstances"],
            1,
        )
        self.assertEqual(
            cabin["retainedExteriorEffectsMaximumMappedShardInstances"],
            1,
        )
        self.assertEqual(
            cabin["maximumMappedShardInstancesDuringTransitionSafeUpperBound"],
            3,
        )

    def test_runtime_update_rejects_tampered_session_mapping_instance_sum(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        node_to_shard = {
            node["key"]: "shared-effects.pcm"
            for node in (
                _event_node_records(plan, CONTINUOUS_EVENT_PATH)
                + _event_node_records(plan, FINITE_EVENT_PATH)
            )
        }
        packed = bind_causal_proof_to_packed_shards(
            plan,
            proof,
            node_to_shard,
            engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
        )
        packed["session"]["perSelectedEnginePerspective"]["cabin"][
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound"
        ] += 1
        packed.pop("proofSha256")
        packed["proofSha256"] = hashlib.sha256(
            canonical_json_bytes(packed)
        ).hexdigest()
        with self.assertRaisesRegex(CausalResourceProofError, "mapping-instance sum differs"):
            causal_runtime_resource_updates(plan, proof, packed)

    def test_packed_binding_rejects_falsified_antichain_node(self) -> None:
        plan = _plan()
        proof = self.prove_release(plan)
        transition = proof["perPerspective"]["cabin"][
            "maximalMappedRegionTransitionSets"
        ][0]
        transition["nodeKeys"][0] = "effect-node:" + "f" * 64
        transition["setSha256"] = hashlib.sha256(
            canonical_json_bytes(sorted(transition["nodeKeys"]))
        ).hexdigest()
        _rehash_proof(proof)
        with self.assertRaisesRegex(CausalResourceProofError, "outside the plan"):
            bind_causal_proof_to_packed_shards(
                plan,
                proof,
                {},
                engine_transition_mapping_instance_bounds={"cabin": 1, "exterior": 1},
            )


if __name__ == "__main__":
    unittest.main()
