#!/usr/bin/env python3
"""Pack realized atlas nodes into bounded mmap-friendly PCM16 WAV shards."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import tempfile
from typing import Any, Mapping, Sequence

from generate_android_profile_recipe import canonical_json_bytes
from generate_full_event_atlas_recipe import (
    ATLAS_PACK_REPORT_SCHEMA,
    ATLAS_PLAN_SCHEMA,
    ATLAS_REALIZATION_SCHEMA,
    build_runtime_index_template,
    current_nodes,
)
from realize_nrt_recipe import NrtRecipeError, _read_pcm, _read_smpl_loop, _sha256


DEFAULT_MAXIMUM_SHARD_BYTES = 256 * 1024 * 1024
DEFAULT_MAXIMUM_RUNTIME_INDEX_BYTES = 16 * 1024 * 1024
COMPACT_EFFECT_RUNTIME_SCHEMA = "byd-full-event-effect-runtime-v5"
COMPACT_EFFECT_NODE_ENCODING_SCHEMA = "byd-full-event-effect-node-array-v1"
COMPACT_EFFECT_NODE_FIELDS = (
    "variantBindingRef",
    "parameters",
    "shardName",
    "startFrame",
    "endFrameExclusive",
    "loopStartFrame",
    "loopEndFrameExclusive",
)
EFFECT_EXECUTION_CONTRACT = {
    "schema": "byd-full-event-effect-execution-contract-v1",
    "continuous": {
        "algorithm": "perSourceAxisAlignedMultilinear-v1",
        "axisSource": "sourceBinding.parameterAxes",
        "axisBounds": "clampToAuthoredEndpointThenBinarySearchLowerUpper",
        "cornerGainFormula": "rawNDimensionalMultilinearWeight",
        "duplicateCornerPolicy": "sumDuplicateAxesThenMapOneNodeOnce",
        "nodeIdentity": "requiredAuthoredBindingKeyPlusCanonicalParameters",
        "nodePlaybackRatio": 1.0,
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
        "finiteRepeat": "renderAndPlayExactlyCapturedFiniteDuration",
    },
    "schedulingGroupComposition": "sumIndependentSimultaneousGroups; alternativesOnlyWithinSameGroupId",
}


def _validate_host_mix_contract(plan: Mapping[str, Any]) -> None:
    """Fail closed unless the packed runtime can apply every event's gain.

    Engine-event finite sources are intentionally not ordinary effect events:
    they share their owning engine event's host gain.  Keeping that fact on
    every node and variant prevents a pack consumer from applying a blanket
    unity effect gain and making an ignition/creation sound six dB too loud.
    """

    contract = plan.get("hostMixContract")
    if not isinstance(contract, Mapping):
        raise NrtRecipeError("atlas plan has no host mix contract")
    classes = contract.get("hostGainClasses")
    if not isinstance(classes, Mapping):
        raise NrtRecipeError("host mix contract has no gain classes")
    expected = {"engineEvent": 0.5, "effectEvent": 1.0}
    for name, gain in expected.items():
        entry = classes.get(name)
        if not isinstance(entry, Mapping) or float(entry.get("gainLinear", -1.0)) != gain:
            raise NrtRecipeError(f"host mix class {name} is missing its exact gain")
    for event in plan.get("effects", []):
        if not isinstance(event, Mapping):
            raise NrtRecipeError("host mix contract has malformed effect event")
        event_path = str(event.get("eventPath") or "")
        required_class = (
            "engineEvent"
            if event_path.endswith(("/engine_int", "/engine_ext"))
            else "effectEvent"
        )
        for node in event.get("nodes", []):
            if not isinstance(node, Mapping) or node.get("hostGainClass") != required_class:
                raise NrtRecipeError(
                    f"effect node host gain class disagrees with event: {event_path}"
                )
        variants = (
            event.get("runtimeLifecycleParameterVariantContract", {}).get(
                "variants", []
            )
            if isinstance(event.get("runtimeLifecycleParameterVariantContract"), Mapping)
            else []
        )
        for variant in variants:
            mapping = variant.get("runtimeMapping") if isinstance(variant, Mapping) else None
            if not isinstance(mapping, Mapping) or mapping.get("hostGainClass") != required_class:
                raise NrtRecipeError(
                    f"effect variant host gain class disagrees with event: {event_path}"
                )


def _event_group_runtime_scalars(
    event: Mapping[str, Any], group_id: str, scheduler: Mapping[str, Any]
) -> dict[str, int]:
    """Return the executable raw-source/corner peak for one authored group.

    The complete per-perspective resource derivation remains in the plan and
    oracle evidence.  The runtime needs only the largest exact scalar for a
    group so it can reserve its finite ring without re-deriving N-D corners or
    silently using the Android composite as one FMOD channel.
    """

    resources = event.get("perspectiveResources")
    if not isinstance(resources, Mapping):
        raise NrtRecipeError(f"runtime effect {event.get('eventPath')} has no resource proof")
    candidates: list[tuple[int, int, int, int]] = []
    for perspective, resource in resources.items():
        if not isinstance(resource, Mapping):
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} has invalid {perspective} resource proof"
            )
        finite = resource.get("finite")
        groups = finite.get("groups") if isinstance(finite, Mapping) else None
        if not isinstance(groups, list):
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} has no finite group proof"
            )
        for record in groups:
            if not isinstance(record, Mapping) or record.get("groupId") != group_id:
                continue
            corners = record.get("maximumSourceCornerContributorsPerLogicalRing")
            raw_channels = record.get("maximumFmodSourceChannelsPerLogicalRing")
            maximum_capture_frames = record.get("maximumCaptureFramesPerLogicalRing")
            streaming_ring_frames = record.get("streamingRingBufferFrames")
            if (
                not isinstance(corners, int)
                or isinstance(corners, bool)
                or corners < 0
                or not isinstance(raw_channels, int)
                or isinstance(raw_channels, bool)
                or raw_channels < 0
                or not isinstance(maximum_capture_frames, int)
                or isinstance(maximum_capture_frames, bool)
                or maximum_capture_frames < 0
                or not isinstance(streaming_ring_frames, int)
                or isinstance(streaming_ring_frames, bool)
                or streaming_ring_frames < 1
            ):
                raise NrtRecipeError(
                    f"runtime effect {event.get('eventPath')} group {group_id} has invalid resource scalar"
                )
            candidates.append(
                (corners, raw_channels, maximum_capture_frames, streaming_ring_frames)
            )
    if not candidates:
        contract = event.get("runtimeLifecycleParameterVariantContract")
        variants = contract.get("variants") if isinstance(contract, Mapping) else None
        if not isinstance(variants, list):
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} group {group_id} has no variants"
            )
        matched = [
            variant
            for variant in variants
            if isinstance(variant, Mapping)
            and isinstance(variant.get("schedulingGroup"), Mapping)
            and variant["schedulingGroup"].get("groupId") == group_id
        ]
        composition = scheduler.get("composition")
        if not matched or composition not in {"simultaneousLayer", "playlistAlternative"}:
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} group {group_id} has no referenced resource scalar"
            )
        corners: list[int] = []
        for variant in matched:
            mapping = variant.get("runtimeMapping")
            axes = mapping.get("parameterAxes") if isinstance(mapping, Mapping) else None
            if not isinstance(axes, Mapping):
                raise NrtRecipeError(
                    f"runtime effect {event.get('eventPath')} group {group_id} has no parameter axes"
                )
            corners.append(
                2
                ** sum(
                    1
                    for values in axes.values()
                    if isinstance(values, Sequence)
                    and not isinstance(values, (str, bytes))
                    and len(values) > 1
                )
            )
        candidates.append(
            (
                max(corners) if composition == "playlistAlternative" else sum(corners),
                1 if composition == "playlistAlternative" else len(matched),
                0,
                0,
            )
        )
    return {
        "maximumSourceCornerContributorsPerLogicalRing": max(
            item[0] for item in candidates
        ),
        "maximumFmodSourceChannelsPerLogicalRing": max(
            item[1] for item in candidates
        ),
        "maximumCaptureFramesPerLogicalRing": max(item[2] for item in candidates),
        "streamingRingBufferFrames": max(item[3] for item in candidates),
    }


def _runtime_effect_execution_contract(
    events: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Deduplicate invariant N-D interpolation semantics outside events."""

    normalized: list[dict[str, Any]] = []
    for event in events:
        contract = event.get("effectInterpolationContract")
        if not isinstance(contract, Mapping):
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} has no interpolation contract"
            )
        value = copy.deepcopy(dict(contract))
        value.pop("schema", None)
        continuous = value.get("continuous")
        one_shot = value.get("oneShot")
        if not isinstance(continuous, dict) or not isinstance(one_shot, dict):
            raise NrtRecipeError(
                f"runtime effect {event.get('eventPath')} interpolation contract is malformed"
            )
        # These counts are event/group resource proof, not universal playback
        # semantics.  The exact group scalars replace them in v5.
        continuous.pop("maximumMappedNodesPerEvent", None)
        one_shot.pop("retrigger", None)
        normalized.append(value)
    expected = {
        key: value
        for key, value in EFFECT_EXECUTION_CONTRACT.items()
        if key != "schema"
    }
    if any(canonical_json_bytes(value) != canonical_json_bytes(expected) for value in normalized):
        raise NrtRecipeError(
            "effect interpolation differs from the exact v5 execution contract"
        )
    return copy.deepcopy(EFFECT_EXECUTION_CONTRACT)


def _compact_runtime_index(runtime: Mapping[str, Any]) -> dict[str, Any]:
    """Deduplicate executable effect semantics from the APK-facing index.

    The full plan/state/oracle files retain the complete source-conservation
    and authoring evidence.  The runtime index carries only parser/renderer
    data: one source binding per identity, one scheduling group per event,
    and compact node references.  This is lossless for runtime behavior while
    avoiding repeated playlist contracts and source bindings on every node.

    ``vN``/``gN`` are deliberately local, deterministic table identifiers;
    they are not a replacement for authored source or group identity.  The
    binding still retains its exact source GUID and each group retains its
    authored group ID.  That prevents a very large random-event bank from
    repeating a full event path plus GUID in every PCM-node record while
    keeping both reference directions independently verifiable.
    """

    compact = copy.deepcopy(dict(runtime))
    effects = compact.get("effects")
    if not isinstance(effects, dict):
        raise NrtRecipeError("runtime has no effects section to compact")
    events = effects.get("events")
    if not isinstance(events, list):
        raise NrtRecipeError("runtime effects section has no event list")
    execution_contract = _runtime_effect_execution_contract(events)
    source_variants: dict[tuple[str, str], Mapping[str, Any]] = {}
    authored_groups: dict[tuple[str, str], dict[str, Any]] = {}
    selection_contracts_by_key: dict[bytes, dict[str, Any]] = {}
    selection_contract_key_by_group: dict[tuple[str, str], bytes] = {}
    events_by_path: dict[str, dict[str, Any]] = {}
    for event in events:
        if not isinstance(event, dict):
            raise NrtRecipeError("runtime effect event is not an object")
        event_path = str(event.get("eventPath") or "")
        contract = event.get("runtimeLifecycleParameterVariantContract")
        if (
            not event_path
            or not isinstance(contract, Mapping)
            or contract.get("complete") is not True
            or not isinstance(contract.get("variants"), list)
        ):
            raise NrtRecipeError(
                f"runtime effect {event_path or '<unknown>'} has no complete variant contract"
            )
        for variant in contract["variants"]:
            if not isinstance(variant, Mapping):
                raise NrtRecipeError(f"runtime effect {event_path} has malformed variant")
            source_guid = str(variant.get("sourceGuid") or "")
            authored_binding_key = str(variant.get("authoredBindingKey") or "")
            mapping = variant.get("runtimeMapping")
            scheduler = variant.get("schedulingGroup")
            if (
                not source_guid
                or not re.fullmatch(r"binding:[0-9a-f]{64}", authored_binding_key)
                or not isinstance(mapping, Mapping)
                or not isinstance(scheduler, Mapping)
                or scheduler.get("complete") is not True
            ):
                raise NrtRecipeError(
                    f"runtime effect {event_path} has incomplete executable variant"
                )
            group_id = str(scheduler.get("groupId") or "")
            if not group_id:
                raise NrtRecipeError(f"runtime effect {event_path} has unnamed scheduler group")
            group_record = copy.deepcopy(dict(scheduler))
            selection_contract = group_record.pop("selectionRuntimeContract", None)
            if not isinstance(selection_contract, Mapping):
                raise NrtRecipeError(
                    f"runtime effect {event_path} group {group_id} has no selection runtime contract"
                )
            selection_key = canonical_json_bytes(selection_contract)
            selection_contracts_by_key.setdefault(
                selection_key, copy.deepcopy(dict(selection_contract))
            )
            group_record.update(
                _event_group_runtime_scalars(event, group_id, scheduler)
            )
            identity = (event_path, group_id)
            previous_group = authored_groups.get(identity)
            if previous_group is None:
                authored_groups[identity] = group_record
                selection_contract_key_by_group[identity] = selection_key
            elif canonical_json_bytes(previous_group) != canonical_json_bytes(group_record):
                raise NrtRecipeError(
                    f"runtime effect {event_path} has inconsistent scheduler group {group_id}"
                )
            elif selection_contract_key_by_group[identity] != selection_key:
                raise NrtRecipeError(
                    f"runtime effect {event_path} group {group_id} has inconsistent selection contract"
                )
            source_identity = (event_path, authored_binding_key)
            previous_binding = source_variants.get(source_identity)
            if previous_binding is None:
                source_variants[source_identity] = variant
            elif canonical_json_bytes(previous_binding) != canonical_json_bytes(variant):
                raise NrtRecipeError(
                    f"runtime effect {event_path} has inconsistent authored binding {authored_binding_key}"
                )

        if not event_path or event_path in events_by_path:
            raise NrtRecipeError("runtime effects contain duplicate or unnamed events")
        events_by_path[event_path] = event

    group_ref_by_identity = {
        identity: f"g{index}"
        for index, identity in enumerate(sorted(authored_groups))
    }
    binding_ref_by_identity = {
        identity: f"v{index}"
        for index, identity in enumerate(sorted(source_variants))
    }
    selection_ref_by_key = {
        key: f"s{index}"
        for index, key in enumerate(sorted(selection_contracts_by_key))
    }
    groups: list[dict[str, Any]] = []
    for identity in sorted(authored_groups):
        event_path, group_id = identity
        groups.append(
            {
                "id": group_ref_by_identity[identity],
                "groupId": group_id,
                "selectionRuntimeContractRef": selection_ref_by_key[
                    selection_contract_key_by_group[identity]
                ],
                **authored_groups[identity],
            }
        )
    selection_contracts = [
        {"id": selection_ref_by_key[key], "contract": selection_contracts_by_key[key]}
        for key in sorted(selection_contracts_by_key)
    ]
    mapping_profiles_by_key: dict[bytes, dict[str, Any]] = {}
    compact_mapping_by_identity: dict[tuple[str, str], dict[str, Any]] = {}
    for identity in sorted(source_variants):
        event_path, authored_binding_key = identity
        variant = source_variants[identity]
        mapping = variant.get("runtimeMapping")
        scheduler = variant.get("schedulingGroup")
        if not isinstance(mapping, Mapping) or not isinstance(scheduler, Mapping):
            raise NrtRecipeError("runtime source binding lost its mapping or scheduler")
        group_id = str(scheduler.get("groupId") or "")
        compact_mapping = copy.deepcopy(dict(mapping))
        compact_mapping.pop("schedulingGroup", None)
        # These values are capture/evidence duplicates.  Playback obtains the
        # source identity from this table and the node coordinate from its
        # emitted node; the remaining mapping is the complete executable
        # lifecycle/parameter contract.
        compact_mapping.pop("captureParameters", None)
        compact_mapping.pop("variantSourceGuid", None)
        compact_mapping.pop("authoredBindingKey", None)
        compact_mapping_by_identity[identity] = compact_mapping
        mapping_profiles_by_key.setdefault(
            canonical_json_bytes(compact_mapping), compact_mapping
        )
    mapping_ref_by_key = {
        key: f"m{index}"
        for index, key in enumerate(sorted(mapping_profiles_by_key))
    }
    mapping_ref_by_identity = {
        identity: mapping_ref_by_key[canonical_json_bytes(mapping)]
        for identity, mapping in compact_mapping_by_identity.items()
    }
    mapping_profiles = [
        {
            "id": mapping_ref_by_key[key],
            "runtimeMapping": mapping_profiles_by_key[key],
        }
        for key in sorted(mapping_profiles_by_key)
    ]
    bindings: list[dict[str, Any]] = []
    for identity in sorted(source_variants):
        event_path, authored_binding_key = identity
        variant = source_variants[identity]
        scheduler = variant.get("schedulingGroup")
        if not isinstance(scheduler, Mapping):
            raise NrtRecipeError("runtime source binding lost its scheduler")
        group_id = str(scheduler.get("groupId") or "")
        bindings.append(
            {
                "id": binding_ref_by_identity[identity],
                "sourceGuid": str(variant["sourceGuid"]),
                "authoredBindingKey": authored_binding_key,
                "runtimeMappingRef": mapping_ref_by_identity[identity],
                "schedulingGroupRef": group_ref_by_identity[(event_path, group_id)],
            }
        )

    binding_by_identity = {
        identity: binding_ref_by_identity[identity]
        for identity in source_variants
    }
    compact_events: list[dict[str, Any]] = []
    for event_path in sorted(events_by_path):
        event = events_by_path[event_path]
        contract = event.pop("runtimeLifecycleParameterVariantContract", None)
        if not isinstance(contract, Mapping) or not isinstance(contract.get("variants"), list):
            raise NrtRecipeError(f"runtime effect {event_path} lost its variant contract")
        binding_refs = sorted(
            {
                binding_by_identity[(event_path, str(variant.get("authoredBindingKey") or ""))]
                for variant in contract["variants"]
                if isinstance(variant, Mapping)
            }
        )
        event["variantBindingRefs"] = binding_refs
        event["schedulingGroupRefs"] = sorted(
            {
                str(
                    next(
                        binding["schedulingGroupRef"]
                        for binding in bindings
                        if binding["id"] == reference
                    )
                )
                for reference in binding_refs
            }
        )
        # These are derivable from the authoritative source binding and node
        # tables.  Leaving them at event level used several megabytes in the
        # 32-family APK catalog without adding executable behavior.
        for duplicate_key in (
            "allocatedWorstCaseMappedNodes",
            "allocatedWorstCasePlaybackVoices",
            "hostGainClasses",
            "maximumMappedNodes",
            "maximumSimultaneousVoices",
            "maximumSimultaneousVoicesMeaning",
            "parameterAxes",
            "perspectiveResources",
            "oneShotPolyphonyPolicy",
            "effectInterpolationContract",
        ):
            event.pop(duplicate_key, None)
        nodes = event.get("nodes")
        if not isinstance(nodes, list):
            raise NrtRecipeError(f"runtime effect {event_path} has no nodes")
        for node in nodes:
            if not isinstance(node, dict):
                raise NrtRecipeError(f"runtime effect {event_path} has malformed node")
            source_guid = str(node.get("requiredSourceGuid") or "")
            authored_binding_key = str(node.get("requiredAuthoredBindingKey") or "")
            binding_ref = binding_ref_by_identity.get((event_path, authored_binding_key))
            source_bindings = node.pop("sourceBindings", None)
            if (
                binding_ref is None
                or not isinstance(source_bindings, list)
                or len(source_bindings) != 1
                or str(source_bindings[0].get("sourceGuid") or "") != source_guid
                or str(source_bindings[0].get("authoredBindingKey") or "")
                != authored_binding_key
                or node.get("hostGainClass")
                != next(
                    profile["runtimeMapping"].get("hostGainClass")
                    for binding in bindings
                    if binding["id"] == binding_ref
                    for profile in mapping_profiles
                    if profile["id"] == binding["runtimeMappingRef"]
                )
            ):
                raise NrtRecipeError(
                    f"runtime effect {event_path} node source binding is not exact"
                )
            node["variantBindingRef"] = binding_ref
            for evidence_key in (
                "hostGainClass",
                "lifetime",
                "requiredDiagnosticName",
                "requiredSourceGuid",
                "requiredAuthoredBindingKey",
                "temporaryAssetName",
                "warmupFrames",
            ):
                node.pop(evidence_key, None)
            values = [node.get(field) for field in COMPACT_EFFECT_NODE_FIELDS]
            if (
                not isinstance(values[0], str)
                or not isinstance(values[1], Mapping)
                or not isinstance(values[2], str)
                or not isinstance(values[3], int)
                or isinstance(values[3], bool)
                or not isinstance(values[4], int)
                or isinstance(values[4], bool)
                or (values[5] is None) != (values[6] is None)
                or (values[5] is not None and (
                    not isinstance(values[5], int)
                    or isinstance(values[5], bool)
                    or not isinstance(values[6], int)
                    or isinstance(values[6], bool)
                ))
            ):
                raise NrtRecipeError(
                    f"runtime effect {event_path} node has no packed array geometry"
                )
            node.clear()
            node.update({"_compactArray": values})
        event["nodes"] = [node["_compactArray"] for node in event["nodes"]]
        compact_events.append(event)
    effects["events"] = compact_events
    effects["runtimeContract"] = {
        "schema": COMPACT_EFFECT_RUNTIME_SCHEMA,
        "variantBindingIdentity": "familyLocalVnRefPlusExactAuthoredBindingKeyAndSourceGuid",
        "schedulingGroupIdentity": "familyLocalGnRefPlusExactAuthoredGroupId",
        "runtimeMappingProfileIdentity": "familyLocalMnRefPlusCanonicalExecutableMapping",
        "nodeBinding": "nodes[][0] is variantBindingRef resolving to authoredBindingKey",
        "nodeEncoding": {
            "schema": COMPACT_EFFECT_NODE_ENCODING_SCHEMA,
            "fields": list(COMPACT_EFFECT_NODE_FIELDS),
            "sourceIdentity": "nodes[][0] resolves to variantBindings[].authoredBindingKeyAndSourceGuid",
            "finiteDurationFrames": "nodes[][4]-nodes[][3]",
        },
        "execution": execution_contract,
        "selectionRuntimeContractTable": "selectionRuntimeContracts[].id",
        "evidence": {
            "outsideRuntimeIndex": "family plan.json, oracle-state.json, oracle-status.json, realization-report.json",
            "runtimeSemantics": "deduplicatedIntoVariantBindingsAndSchedulingGroups",
        },
    }
    effects["variantBindings"] = bindings
    effects["schedulingGroups"] = groups
    effects["selectionRuntimeContracts"] = selection_contracts
    effects["runtimeMappingProfiles"] = mapping_profiles
    binding_ids = [str(item["id"]) for item in effects["variantBindings"]]
    group_ids = [str(item["id"]) for item in effects["schedulingGroups"]]
    if len(binding_ids) != len(set(binding_ids)) or len(group_ids) != len(set(group_ids)):
        raise NrtRecipeError("compact runtime has duplicate binding or scheduler IDs")
    binding_id_set = set(binding_ids)
    group_id_set = set(group_ids)
    bindings_by_id = {str(item["id"]): item for item in effects["variantBindings"]}
    profile_ids = [str(item["id"]) for item in effects["runtimeMappingProfiles"]]
    if len(profile_ids) != len(set(profile_ids)):
        raise NrtRecipeError("compact runtime has duplicate mapping profile IDs")
    profiles_by_id = {
        str(item["id"]): item for item in effects["runtimeMappingProfiles"]
    }
    selection_ids = [str(item.get("id") or "") for item in selection_contracts]
    if len(selection_ids) != len(set(selection_ids)):
        raise NrtRecipeError("compact runtime has duplicate or absent selection contracts")
    if effects["events"] and not selection_ids:
        raise NrtRecipeError("compact runtime effects have no selection contracts")
    selection_id_set = set(selection_ids)
    for binding in effects["variantBindings"]:
        if (
            str(binding.get("runtimeMappingRef") or "") not in profiles_by_id
            or str(binding.get("schedulingGroupRef") or "") not in group_id_set
        ):
            raise NrtRecipeError("compact runtime binding has an orphan mapping/group reference")
    if any(
        str(group.get("selectionRuntimeContractRef") or "")
        not in selection_id_set
        for group in effects["schedulingGroups"]
    ):
        raise NrtRecipeError("compact runtime group has an orphan selection contract")
    referenced_bindings: set[str] = set()
    referenced_groups: set[str] = set()
    for event in effects["events"]:
        event_path = str(event["eventPath"])
        event_bindings = [str(value) for value in event.get("variantBindingRefs", [])]
        event_groups = [str(value) for value in event.get("schedulingGroupRefs", [])]
        if (
            len(event_bindings) != len(set(event_bindings))
            or len(event_groups) != len(set(event_groups))
            or any(value not in binding_id_set for value in event_bindings)
            or any(value not in group_id_set for value in event_groups)
        ):
            raise NrtRecipeError("compact runtime event has invalid binding or scheduler references")
        referenced_bindings.update(event_bindings)
        referenced_groups.update(event_groups)
        for node in event["nodes"]:
            if not isinstance(node, list) or len(node) != len(COMPACT_EFFECT_NODE_FIELDS):
                raise NrtRecipeError("compact runtime node does not match its array encoding")
            reference = str(node[0] or "")
            if reference not in event_bindings:
                raise NrtRecipeError("compact runtime node has missing or cross-event binding")
    if referenced_bindings != binding_id_set or referenced_groups != group_id_set:
        raise NrtRecipeError("compact runtime contains orphan binding or scheduler records")
    return compact


def _node_info(path: Path) -> dict[str, Any]:
    pcm, frame_count, sample_rate, channels = _read_pcm(
        path, require_nrt_format=True
    )
    if channels != 2 or sample_rate != 48_000:
        raise NrtRecipeError(f"atlas node {path} is not PCM16/48k/stereo")
    loop = _read_smpl_loop(path)
    return {
        "path": path,
        "pcm": pcm,
        "frameCount": frame_count,
        "loop": loop,
        "wavSha256": _sha256(path),
    }


def _node_temporary_asset_names(node: Mapping[str, Any]) -> list[str]:
    names = [str(node["temporaryAssetName"])]
    mode_programs = node.get("modeProgramTemporaryAssetNames")
    if mode_programs is not None:
        if not isinstance(mode_programs, Mapping) or set(mode_programs) != {
            "loadOnly",
            "coastOnly",
        }:
            raise NrtRecipeError("engine node has an invalid mode-program asset map")
        names.extend(
            str(mode_programs[mode]) for mode in ("loadOnly", "coastOnly")
        )
    if len(names) != len(set(names)):
        raise NrtRecipeError("atlas node reuses one asset for multiple engine programs")

    return names


def _partition_by_rpm(
    nodes: Sequence[Mapping[str, Any]],
    infos: Mapping[str, Mapping[str, Any]],
    maximum_bytes: int,
) -> list[list[Mapping[str, Any]]]:
    groups: list[list[Mapping[str, Any]]] = []
    current_rpm: float | None = None
    for node in sorted(nodes, key=lambda item: (item["rpm"], item["throttle"])):
        rpm = float(node["rpm"])
        if current_rpm is None or rpm != current_rpm:
            groups.append([])
            current_rpm = rpm
        groups[-1].append(node)
    shards: list[list[Mapping[str, Any]]] = []
    current: list[Mapping[str, Any]] = []
    current_bytes = 0
    for group in groups:
        group_bytes = sum(
            len(infos[name]["pcm"])
            for node in group
            for name in _node_temporary_asset_names(node)
        )
        if group_bytes > maximum_bytes:
            raise NrtRecipeError(
                f"one RPM row requires {group_bytes} bytes, above shard limit"
            )
        if current and current_bytes + group_bytes > maximum_bytes:
            shards.append(current)
            current = []
            current_bytes = 0
        current.extend(group)
        current_bytes += group_bytes
    if current:
        shards.append(current)
    return shards


def _partition_sequential(
    nodes: Sequence[Mapping[str, Any]],
    infos: Mapping[str, Mapping[str, Any]],
    maximum_bytes: int,
) -> list[list[Mapping[str, Any]]]:
    shards: list[list[Mapping[str, Any]]] = []
    current: list[Mapping[str, Any]] = []
    current_bytes = 0
    for node in nodes:
        node_bytes = len(infos[str(node["temporaryAssetName"])]["pcm"])
        if node_bytes > maximum_bytes:
            raise NrtRecipeError(f"one atlas node exceeds the shard limit")
        if current and current_bytes + node_bytes > maximum_bytes:
            shards.append(current)
            current = []
            current_bytes = 0
        current.append(node)
        current_bytes += node_bytes
    if current:
        shards.append(current)
    return shards


def _write_pcm_wav_atomic(path: Path, pcm_parts: Sequence[bytes]) -> str:
    data_bytes = sum(len(part) for part in pcm_parts)
    riff_size = 4 + (8 + 16) + (8 + data_bytes)
    if riff_size > 0xFFFF_FFFF:
        raise NrtRecipeError(f"packed WAV {path.name} exceeds RIFF32")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(b"RIFF" + struct.pack("<I", riff_size) + b"WAVE")
        temporary.write(b"fmt " + struct.pack("<I", 16))
        temporary.write(struct.pack("<HHIIHH", 1, 2, 48_000, 192_000, 4, 16))
        temporary.write(b"data" + struct.pack("<I", data_bytes))
        for pcm in pcm_parts:
            temporary.write(pcm)
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)
    return _sha256(path)


def _pack_shards(
    groups: Sequence[Sequence[Mapping[str, Any]]],
    infos: Mapping[str, Mapping[str, Any]],
    output_directory: Path,
    base_name: str,
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    locations: dict[str, dict[str, Any]] = {}
    reports: list[dict[str, Any]] = []
    for shard_index, nodes in enumerate(groups):
        shard_name = f"{base_name}_{shard_index:03d}.wav"
        shard_path = output_directory / shard_name
        if shard_path.exists():
            raise NrtRecipeError(f"refusing to overwrite {shard_path}")
        asset_names = [
            name for node in nodes for name in _node_temporary_asset_names(node)
        ]
        pcm_parts = [infos[name]["pcm"] for name in asset_names]
        packed_sha = _write_pcm_wav_atomic(shard_path, pcm_parts)
        frame_offset = 0
        for node in nodes:
            for name in _node_temporary_asset_names(node):
                info = infos[name]
                frame_count = int(info["frameCount"])
                loop = info["loop"]
                locations[name] = {
                    "shardName": shard_name,
                    "startFrame": frame_offset,
                    "endFrameExclusive": frame_offset + frame_count,
                    "loopStartFrame": (
                        None if loop is None else frame_offset + int(loop[0])
                    ),
                    "loopEndFrameExclusive": (
                        None if loop is None else frame_offset + int(loop[1])
                    ),
                }
                frame_offset += frame_count
        reports.append(
            {
                "shardName": shard_name,
                "bytes": shard_path.stat().st_size,
                "frames": frame_offset,
                "nodeCount": len(nodes),
                "assetCount": len(asset_names),
                "sha256": packed_sha,
            }
        )
    return locations, reports


def _engine_transition_shard_proof(
    perspective: Mapping[str, Any]
) -> dict[str, Any]:
    """Prove the actual shard bound over every adjacent engine-cell handoff.

    The Android contract may prepare an entering cell only once each leaving
    row/column has reached a zero raw bilinear weight.  The proof therefore
    computes ``old cell union new cell minus safely-zero old-only nodes`` for
    every horizontal and vertical adjacent-cell transition using the packed
    node locations, rather than relying on an assumed number of RPM shards.
    """

    rpm_axis = [float(value) for value in perspective["rpmAxis"]]
    throttle_axis = [float(value) for value in perspective["throttleAxis"]]
    nodes = {
        (float(node["rpm"]), float(node["throttle"])): node
        for node in perspective["nodes"]
    }
    for node in nodes.values():
        mode_programs = node.get("modePrograms")
        if not isinstance(mode_programs, Mapping) or set(mode_programs) != {
            "loadOnly",
            "coastOnly",
        }:
            raise NrtRecipeError("engine transition proof has no LOAD_ONLY/COAST_ONLY regions")
        if any(
            not isinstance(geometry, Mapping)
            or str(geometry.get("shardName") or "") != str(node["shardName"])
            for geometry in mode_programs.values()
        ):
            raise NrtRecipeError("engine mode programs are not co-located with FULL")
    if len(rpm_axis) < 2 or len(throttle_axis) < 2:
        raise NrtRecipeError("engine transition proof needs two axis points")

    def cell(rpm_index: int, throttle_index: int) -> set[tuple[float, float]]:
        return {
            (rpm_axis[row], throttle_axis[column])
            for row in (rpm_index, rpm_index + 1)
            for column in (throttle_index, throttle_index + 1)
        }

    cases: list[dict[str, Any]] = []

    def record(
        direction: str,
        old_indices: tuple[int, int],
        new_indices: tuple[int, int],
        safely_zero_old_only: set[tuple[float, float]],
    ) -> None:
        old = cell(*old_indices)
        new = cell(*new_indices)
        retained = (old | new) - safely_zero_old_only
        names = sorted(str(nodes[key]["shardName"]) for key in retained)
        cases.append(
            {
                "direction": direction,
                "oldCell": {"rpmIndex": old_indices[0], "throttleIndex": old_indices[1]},
                "newCell": {"rpmIndex": new_indices[0], "throttleIndex": new_indices[1]},
                "safelyZeroOldOnlyNodes": [
                    {"rpm": key[0], "throttle": key[1]}
                    for key in sorted(safely_zero_old_only)
                ],
                "retainedShardNames": names,
                "uniqueShardCount": len(set(names)),
            }
        )

    for rpm_index in range(len(rpm_axis) - 2):
        for throttle_index in range(len(throttle_axis) - 1):
            old = cell(rpm_index, throttle_index)
            new = cell(rpm_index + 1, throttle_index)
            record(
                "increasingRpm",
                (rpm_index, throttle_index),
                (rpm_index + 1, throttle_index),
                old - new,
            )
            record(
                "decreasingRpm",
                (rpm_index + 1, throttle_index),
                (rpm_index, throttle_index),
                new - old,
            )
    for rpm_index in range(len(rpm_axis) - 1):
        for throttle_index in range(len(throttle_axis) - 2):
            old = cell(rpm_index, throttle_index)
            new = cell(rpm_index, throttle_index + 1)
            record(
                "increasingThrottle",
                (rpm_index, throttle_index),
                (rpm_index, throttle_index + 1),
                old - new,
            )
            record(
                "decreasingThrottle",
                (rpm_index, throttle_index + 1),
                (rpm_index, throttle_index),
                new - old,
            )
    # A two-point axis has one cell and no adjacent handoff.  Its cold map is
    # still a real resource state and supplies the relevant bound.
    if not cases:
        retained = cell(0, 0)
        names = sorted(str(nodes[key]["shardName"]) for key in retained)
        cases.append(
            {
                "direction": "coldMapOnly",
                "oldCell": None,
                "newCell": {"rpmIndex": 0, "throttleIndex": 0},
                "safelyZeroOldOnlyNodes": [],
                "retainedShardNames": names,
                "uniqueShardCount": len(set(names)),
            }
        )
    maximum = max(item["uniqueShardCount"] for item in cases)
    worst = min(
        (item for item in cases if item["uniqueShardCount"] == maximum),
        key=lambda item: (
            item["direction"],
            -1 if item["oldCell"] is None else item["oldCell"]["rpmIndex"],
            -1 if item["oldCell"] is None else item["oldCell"]["throttleIndex"],
        ),
    )
    return {
        "algorithm": "adjacentCellUnionMinusZeroWeightLeavingNodes-v1",
        "cellReplacementPolicy": "prepareEnteringOnlyAfterLeavingNodesHaveRawBilinearWeightZero",
        "checkedTransitionCount": len(cases),
        "maximumUniqueMappedShards": maximum,
        "worstCase": worst,
    }


def _realization_asset_hashes(
    plan: Mapping[str, Any],
    realization: Mapping[str, Any],
) -> tuple[dict[str, str], list[Mapping[str, Any]]]:
    """Validate one complete v2 realization and return every PCM asset hash.

    A realization capture is one authored atlas node. Engine captures own
    three distinct master-output assets (FULL, LOAD_ONLY, and COAST_ONLY),
    while effect captures own only their source-isolated asset. Keeping those
    counts separate prevents a missing mode program from being hidden by the
    unchanged engine-node capture count.
    """

    required_root_keys = {
        "schema",
        "planSha256",
        "atlasFamilyId",
        "sourceBankSha256Before",
        "sourceBankSha256After",
        "sourceBankUnchanged",
        "fullRun",
        "captureCount",
        "captures",
    }
    if set(realization) != required_root_keys:
        raise NrtRecipeError("realization report has unsupported or missing root fields")
    if realization.get("schema") != ATLAS_REALIZATION_SCHEMA:
        raise NrtRecipeError(f"realization report is not {ATLAS_REALIZATION_SCHEMA}")
    if realization.get("planSha256") != plan.get("planSha256"):
        raise NrtRecipeError("realization report does not belong to atlas plan")
    if realization.get("atlasFamilyId") != plan.get("id"):
        raise NrtRecipeError("realization report belongs to another atlas family")
    bank_sha256 = plan.get("bankSha256")
    if (
        realization.get("sourceBankSha256Before") != bank_sha256
        or realization.get("sourceBankSha256After") != bank_sha256
        or realization.get("sourceBankUnchanged") is not True
    ):
        raise NrtRecipeError("realization report does not prove an unchanged source bank")
    if realization.get("fullRun") is not True:
        raise NrtRecipeError("partial atlas realization cannot be packed")

    expected_by_capture: dict[str, Mapping[str, Any] | None] = {}
    all_nodes: list[Mapping[str, Any]] = []
    for perspective in ("cabin", "exterior"):
        perspective_value = plan.get("perspectives", {}).get(perspective)
        if not isinstance(perspective_value, Mapping):
            raise NrtRecipeError(f"atlas plan has no {perspective} perspective")
        for node in current_nodes(perspective_value):
            if not isinstance(node, Mapping):
                raise NrtRecipeError("atlas plan contains a malformed engine node")
            name = str(node.get("temporaryAssetName") or "")
            mode_programs = node.get("modeProgramTemporaryAssetNames")
            if (
                not name
                or not isinstance(mode_programs, Mapping)
                or set(mode_programs) != {"loadOnly", "coastOnly"}
                or any(
                    not str(mode_programs[mode])
                    for mode in ("loadOnly", "coastOnly")
                )
                or name in expected_by_capture
            ):
                raise NrtRecipeError("atlas plan has an invalid engine capture identity")
            expected_by_capture[name] = mode_programs
            all_nodes.append(node)
    effects = plan.get("effects")
    if not isinstance(effects, Sequence) or isinstance(effects, (str, bytes)):
        raise NrtRecipeError("atlas plan effects are absent")
    for event in effects:
        if not isinstance(event, Mapping):
            raise NrtRecipeError("atlas plan contains a malformed effect event")
        nodes = event.get("nodes")
        if not isinstance(nodes, Sequence) or isinstance(nodes, (str, bytes)):
            raise NrtRecipeError("atlas plan contains malformed effect nodes")
        for node in nodes:
            if not isinstance(node, Mapping):
                raise NrtRecipeError("atlas plan contains a malformed effect node")
            name = str(node.get("temporaryAssetName") or "")
            if not name or "modeProgramTemporaryAssetNames" in node or name in expected_by_capture:
                raise NrtRecipeError("atlas plan has an invalid effect capture identity")
            expected_by_capture[name] = None
            all_nodes.append(node)

    captures = realization.get("captures")
    if not isinstance(captures, list):
        raise NrtRecipeError("realization report captures are absent")
    if (
        not isinstance(realization.get("captureCount"), int)
        or isinstance(realization.get("captureCount"), bool)
        or realization["captureCount"] != len(captures)
        or len(captures) != len(expected_by_capture)
    ):
        raise NrtRecipeError("realization capture count differs from atlas nodes")

    asset_hashes: dict[str, str] = {}
    observed_captures: set[str] = set()

    def record_asset(name: Any, wav_sha256: Any, label: str) -> None:
        if (
            not isinstance(name, str)
            or not name
            or name in asset_hashes
            or not isinstance(wav_sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", wav_sha256) is None
        ):
            raise NrtRecipeError(f"{label} has an invalid or duplicate asset identity")
        asset_hashes[name] = wav_sha256

    for index, capture in enumerate(captures):
        if not isinstance(capture, Mapping):
            raise NrtRecipeError(f"realization capture {index} is not an object")
        name = capture.get("temporaryAssetName")
        if not isinstance(name, str) or name not in expected_by_capture or name in observed_captures:
            raise NrtRecipeError(f"realization capture {index} has an unknown or duplicate node identity")
        observed_captures.add(name)
        record_asset(name, capture.get("wavSha256"), f"realization capture {index}")
        expected_mode_programs = expected_by_capture[name]
        actual_mode_programs = capture.get("modePrograms")
        if expected_mode_programs is None:
            if "modePrograms" in capture:
                raise NrtRecipeError("effect realization capture unexpectedly contains engine mode programs")
            continue
        if not isinstance(actual_mode_programs, Mapping) or set(actual_mode_programs) != {
            "loadOnly",
            "coastOnly",
        }:
            raise NrtRecipeError("engine realization capture lacks exact mode programs")
        for mode in ("loadOnly", "coastOnly"):
            program = actual_mode_programs[mode]
            if (
                not isinstance(program, Mapping)
                or program.get("temporaryAssetName") != expected_mode_programs[mode]
            ):
                raise NrtRecipeError(
                    f"engine realization {mode} identity differs from the plan"
                )
            record_asset(
                program.get("temporaryAssetName"),
                program.get("wavSha256"),
                f"engine realization {mode} program",
            )

    if observed_captures != set(expected_by_capture):
        raise NrtRecipeError("realization report does not cover every atlas node")
    expected_assets = {
        name
        for node in all_nodes
        for name in _node_temporary_asset_names(node)
    }
    if set(asset_hashes) != expected_assets:
        raise NrtRecipeError("realization report does not cover every atlas PCM asset")

    return asset_hashes, all_nodes


def pack_atlas(
    plan: Mapping[str, Any],
    realization: Mapping[str, Any],
    node_directory: Path,
    output_directory: Path,
    maximum_shard_bytes: int,
    *,
    delete_nodes: bool,
    maximum_runtime_index_bytes: int = DEFAULT_MAXIMUM_RUNTIME_INDEX_BYTES,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(plan, Mapping):
        raise NrtRecipeError("atlas plan is not an object")
    if not isinstance(realization, Mapping):
        raise NrtRecipeError("realization report is not an object")
    if plan.get("schema") != ATLAS_PLAN_SCHEMA:
        raise NrtRecipeError(f"plan is not {ATLAS_PLAN_SCHEMA}")
    if maximum_runtime_index_bytes <= 0:
        raise NrtRecipeError("maximum runtime index bytes must be positive")
    _validate_host_mix_contract(plan)
    expected_hashes, all_nodes = _realization_asset_hashes(plan, realization)
    expected_names = set(expected_hashes)
    infos: dict[str, dict[str, Any]] = {}
    for name in sorted(expected_names):
        path = (node_directory / name).resolve(strict=True)
        if _sha256(path) != expected_hashes[name]:
            raise NrtRecipeError(f"node hash differs from realization report: {name}")
        infos[name] = _node_info(path)
    for perspective in ("cabin", "exterior"):
        for node in current_nodes(plan["perspectives"][perspective]):
            names = _node_temporary_asset_names(node)
            full = infos[names[0]]
            for mode, name in zip(("LOAD_ONLY", "COAST_ONLY"), names[1:]):
                program = infos[name]
                if (
                    program["frameCount"] != full["frameCount"]
                    or program["loop"] != full["loop"]
                ):
                    raise NrtRecipeError(
                        f"engine {mode} program geometry differs from FULL"
                    )
    output_directory.mkdir(parents=True, exist_ok=True)
    runtime = build_runtime_index_template(plan)
    shard_reports: list[dict[str, Any]] = []
    locations: dict[str, dict[str, Any]] = {}
    for perspective in ("cabin", "exterior"):
        nodes = current_nodes(plan["perspectives"][perspective])
        groups = _partition_by_rpm(nodes, infos, maximum_shard_bytes)
        new_locations, reports = _pack_shards(
            groups, infos, output_directory, f"engine_{perspective}_atlas"
        )
        locations.update(new_locations)
        shard_reports.extend(
            {"kind": "engine", "perspective": perspective, **item}
            for item in reports
        )
    effect_nodes = [node for event in plan["effects"] for node in event["nodes"]]
    if effect_nodes:
        groups = _partition_sequential(effect_nodes, infos, maximum_shard_bytes)
        new_locations, reports = _pack_shards(
            groups, infos, output_directory, "effects_atlas"
        )
        locations.update(new_locations)
        shard_reports.extend({"kind": "effects", **item} for item in reports)

    def finalize_node(node: dict[str, Any]) -> None:
        name = str(node.pop("temporaryAssetName"))
        node.update(locations[name])
        mode_program_names = node.pop("modeProgramTemporaryAssetNames", None)
        if mode_program_names is not None:
            if not isinstance(mode_program_names, Mapping) or set(mode_program_names) != {
                "loadOnly",
                "coastOnly",
            }:
                raise NrtRecipeError("runtime engine node has invalid mode-program names")
            node["modePrograms"] = {
                mode: locations[str(mode_program_names[mode])]
                for mode in ("loadOnly", "coastOnly")
            }
            full_shard = str(node["shardName"])
            if any(
                str(geometry["shardName"]) != full_shard
                for geometry in node["modePrograms"].values()
            ):
                raise NrtRecipeError(
                    "engine FULL/LOAD_ONLY/COAST_ONLY regions must share one mmap shard"
                )
        else:
            node.pop("modePrograms", None)

    for value in runtime["perspectives"].values():
        for evidence_key in (
            "packedAssetName",
            "engineModePrograms",
            "hostParameterBindings",
        ):
            value.pop(evidence_key, None)
        for node in value["nodes"]:
            finalize_node(node)
    runtime["effects"].pop("packedAssetName", None)
    for event in runtime["effects"]["events"]:
        for node in event["nodes"]:
            finalize_node(node)
    # Replace each component's pre-pack node-count bound with the narrower,
    # auditable mapping-instance bound from the actual node-to-shard map.  The
    # session total intentionally remains blocked: retained cabin and exterior
    # effect participants require the separate causal v3 proof.
    resource_bounds = runtime.get("resourceBounds")
    if isinstance(resource_bounds, dict):
        per_perspective = resource_bounds.get("perPerspective")
        if isinstance(per_perspective, dict):
            for perspective, bound in per_perspective.items():
                if not isinstance(bound, dict):
                    continue
                perspective_runtime = runtime["perspectives"].get(perspective)
                if not isinstance(perspective_runtime, Mapping):
                    raise NrtRecipeError(f"runtime has no {perspective} perspective")
                engine_proof = _engine_transition_shard_proof(perspective_runtime)
                scoped_shards = {
                    str(node["shardName"])
                    for event in runtime["effects"]["events"]
                    if perspective in event.get("perspectives", [])
                    for node in event["nodes"]
                }
                effects_bound = bound.get("effects")
                engine_bound = bound.get("engine")
                if isinstance(engine_bound, dict):
                    engine_bound["maximumMappedShardInstancesDuringCellTransition"] = engine_proof[
                        "maximumUniqueMappedShards"
                    ]
                    engine_bound["transitionShardProof"] = engine_proof
                if isinstance(effects_bound, dict):
                    effects_bound["maximumMappedShardInstancesSafeUpperBound"] = len(
                        scoped_shards
                    )
    runtime["draftBlocked"] = True
    runtime_shards: list[dict[str, Any]] = []
    runtime_shard_names: set[str] = set()
    for item in shard_reports:
        name = str(item["shardName"])
        if name in runtime_shard_names:
            raise NrtRecipeError(f"packed runtime duplicates shard {name}")
        runtime_shard_names.add(name)
        runtime_shards.append(
            {
                "name": name,
                "sha256": str(item["sha256"]),
                "bytes": int(item["bytes"]),
            }
        )
    runtime["shards"] = runtime_shards
    runtime = _compact_runtime_index(runtime)
    runtime_payload = canonical_json_bytes(runtime) + b"\n"
    runtime_bytes = len(runtime_payload)
    runtime_sha256 = hashlib.sha256(runtime_payload).hexdigest()
    if runtime_bytes > maximum_runtime_index_bytes:
        raise NrtRecipeError(
            f"packed runtime index is {runtime_bytes} bytes, above "
            f"{maximum_runtime_index_bytes}-byte APK loader limit"
        )
    if delete_nodes:
        for info in infos.values():
            Path(info["path"]).unlink()
    report = {
        "schema": ATLAS_PACK_REPORT_SCHEMA,
        "planSha256": plan["planSha256"],
        "atlasFamilyId": plan["id"],
        "maximumShardBytes": maximum_shard_bytes,
        "largestShardBytes": max((item["bytes"] for item in shard_reports), default=0),
        "familyBytes": sum(item["bytes"] for item in shard_reports),
        "temporaryNodesDeleted": delete_nodes,
        "nodeCount": len(all_nodes),
        "assetCount": len(expected_names),
        "shards": shard_reports,
        "sourceAssetHashes": expected_hashes,
        "runtimeIndex": {
            "schema": COMPACT_EFFECT_RUNTIME_SCHEMA,
            "bytes": runtime_bytes,
            "canonicalJsonNewlineSha256": runtime_sha256,
            "maximumBytes": maximum_runtime_index_bytes,
            "evidenceOutsideRuntimeIndex": [
                "plan.json",
                "oracle-state.json",
                "oracle-status.json",
                "realization-report.json",
            ],
        },
    }
    return runtime, report


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(canonical_json_bytes(value) + b"\n")
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--realization-report", type=Path, required=True)
    parser.add_argument("--node-directory", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--runtime-index-output", type=Path, required=True)
    parser.add_argument("--pack-report-output", type=Path, required=True)
    parser.add_argument(
        "--maximum-shard-bytes", type=int, default=DEFAULT_MAXIMUM_SHARD_BYTES
    )
    parser.add_argument(
        "--maximum-runtime-index-bytes",
        type=int,
        default=DEFAULT_MAXIMUM_RUNTIME_INDEX_BYTES,
    )
    parser.add_argument("--keep-node-wavs", action="store_true")
    args = parser.parse_args(argv)
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        realization = json.loads(args.realization_report.read_text(encoding="utf-8"))
        runtime, report = pack_atlas(
            plan,
            realization,
            args.node_directory,
            args.output_directory,
            args.maximum_shard_bytes,
            delete_nodes=not args.keep_node_wavs,
            maximum_runtime_index_bytes=args.maximum_runtime_index_bytes,
        )
        _write_json_atomic(args.runtime_index_output, runtime)
        _write_json_atomic(args.pack_report_output, report)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
