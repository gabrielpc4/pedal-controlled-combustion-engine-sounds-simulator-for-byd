#!/usr/bin/env python3
"""Audit engine-event LOAD/COAST/BOTH role identity for every bank family.

The audit deliberately treats an authored binding as an exact event path plus
waveform-instrument route.  Embedded sample names are reported only as
diagnostics: the same name is allowed to occur on different routes, with a
different lifetime, or with a different role.  This keeps the report aligned
with the independent FULL/LOAD_ONLY/COAST_ONLY master-output contract.

No audio is rendered and no bank is modified.  The current graph recipe is
built in memory only so the report can compare current classifier output with
the already-generated atlas-plan artifacts.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import (  # noqa: E402
    ProfileRecipeError,
    build_recipe,
    canonical_json_bytes,
)


REPORT_SCHEMA = "byd-engine-event-role-audit-v2"
EXPECTED_ENGINE_MODE_PROGRAM_SCHEMA = "byd-full-event-engine-mode-programs-v1"
ENGINE_EVENT_SUFFIXES = frozenset({"engine_int", "engine_ext"})
EXECUTABLE_ROLES = frozenset({"LOAD", "COAST", "UNAFFECTED"})
ROLE_NORMALIZATION = {
    "LOAD": "LOAD",
    "LIMITER": "LOAD",
    "COAST": "COAST",
    "IDLE": "UNAFFECTED",
    "TEXTURE": "UNAFFECTED",
    "UNAFFECTED": "UNAFFECTED",
    "UNCLASSIFIED": "UNCLASSIFIED",
}


def _event_suffix(path: object) -> str:
    value = str(path or "").strip().casefold().rstrip("/")

    return value.rsplit("/", 1)[-1] if value else ""


def _mapping(value: object) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _sequence(value: object) -> Sequence[Any]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        return ()

    return value


def _role(raw_role: object) -> str | None:
    if raw_role is None:
        return None

    value = str(raw_role).strip().upper()

    return ROLE_NORMALIZATION.get(value, value)


def _instrument_routes(graph: Mapping[str, Any]) -> dict[str, list[str]]:
    instruments = {
        str(item.get("guid") or ""): item
        for item in _sequence(graph.get("instruments"))
        if isinstance(item, Mapping) and item.get("guid")
    }
    parents: dict[str, list[str]] = defaultdict(list)
    for parent_guid, instrument in instruments.items():
        for child in _sequence(instrument.get("childInstruments")):
            child_guid = (
                str(child.get("guid") or "")
                if isinstance(child, Mapping)
                else str(child or "")
            )
            if child_guid:
                parents[child_guid].append(parent_guid)

    routes: dict[str, list[str]] = {}
    for source_guid in instruments:
        route: list[str] = []
        visited: set[str] = set()
        current = source_guid
        while current:
            if current in visited:
                raise ProfileRecipeError(
                    f"instrument route cycles at {source_guid}"
                )
            visited.add(current)
            route.append(current)
            current_parents = sorted(set(parents.get(current, ())))
            if len(current_parents) > 1:
                raise ProfileRecipeError(
                    f"instrument {current} has multiple authored routes"
                )
            current = current_parents[0] if current_parents else ""
        routes[source_guid] = route

    return routes


def _route_identity(
    *,
    event_path: str,
    source_guid: str,
    instrument_route: Sequence[str],
    routable_guids: Sequence[str],
    scheduling_group_id: object,
    authored_binding_key: object,
) -> str:
    payload = {
        "schema": "byd-engine-event-role-audit-route-v1",
        "eventPath": event_path,
        "sourceGuid": source_guid,
        "instrumentChainSourceToRoot": list(instrument_route),
        "routableGuidsSourceToRoot": list(routable_guids),
        "schedulingGroupId": str(scheduling_group_id or ""),
        "authoredBindingKey": str(authored_binding_key or ""),
    }

    return "audit-route:" + hashlib.sha256(canonical_json_bytes(payload)).hexdigest()


def engine_binding_records(
    recipe: Mapping[str, Any], graph: Mapping[str, Any]
) -> list[dict[str, Any]]:
    instruments = {
        str(item.get("guid") or ""): item
        for item in _sequence(graph.get("instruments"))
        if isinstance(item, Mapping) and item.get("guid")
    }
    routes = _instrument_routes(graph)
    layer_by_identity: dict[tuple[str, str], Mapping[str, Any]] = {}
    programs = _mapping(recipe.get("programs"))
    for perspective in ("cabin", "exterior"):
        program = _mapping(programs.get(perspective))
        event_path = str(program.get("eventPath") or "")
        for layer in _sequence(program.get("layers")):
            if not isinstance(layer, Mapping):
                continue
            key = (event_path, str(layer.get("sourceGuid") or ""))
            if not all(key) or key in layer_by_identity:
                raise ProfileRecipeError(
                    f"engine layer identity is missing or duplicated: {key}"
                )
            layer_by_identity[key] = layer

    audit = _mapping(recipe.get("sourceConservationAudit"))
    records: list[dict[str, Any]] = []
    for binding in _sequence(audit.get("coreEventBindings")):
        if not isinstance(binding, Mapping):
            continue
        event_path = str(binding.get("eventPath") or "")
        if _event_suffix(event_path) not in ENGINE_EVENT_SUFFIXES:
            continue
        source_guid = str(binding.get("sourceGuid") or "")
        instrument = instruments.get(source_guid)
        if instrument is None:
            raise ProfileRecipeError(
                f"engine binding {event_path} references missing source {source_guid}"
            )
        source_sample = _mapping(instrument.get("sample"))
        runtime_mapping = _mapping(binding.get("runtimeMapping"))
        kind = str(runtime_mapping.get("kind") or "")
        layer = layer_by_identity.get((event_path, source_guid))
        if kind == "engineLayer":
            if layer is None:
                raise ProfileRecipeError(
                    f"continuous engine binding has no layer: {event_path} {source_guid}"
                )
            raw_role = layer.get("role")
            lifetime = "continuous"
            scheduler = _mapping(layer.get("schedulingGroup"))
            role_evidence = {
                "source": "programs.<perspective>.layers[].role",
                "rpmInterval": [layer.get("startRpm"), layer.get("endRpm")],
                "throttlePlacements": list(
                    _sequence(layer.get("throttlePlacements"))
                ),
                "throttleGainCurve": layer.get("throttleGainDb"),
                "warnings": list(_sequence(layer.get("warnings"))),
            }
        elif kind == "engineEventTransient":
            raw_role = runtime_mapping.get("engineProgramRole")
            lifetime = str(runtime_mapping.get("lifetime") or "")
            scheduler = _mapping(runtime_mapping.get("schedulingGroup"))
            evidence = _mapping(runtime_mapping.get("engineProgramRoleEvidence"))
            role_evidence = {
                "source": "runtimeMapping.engineProgramRole",
                **dict(evidence),
            }
        else:
            raw_role = runtime_mapping.get("engineProgramRole")
            lifetime = str(runtime_mapping.get("lifetime") or "UNKNOWN")
            scheduler = _mapping(runtime_mapping.get("schedulingGroup"))
            role_evidence = {
                "source": "runtimeMapping.engineProgramRole",
                "warnings": ["unknownEngineRuntimeMappingKind"],
            }
        scheduling_group_id = scheduler.get("groupId")
        authored_binding_key = runtime_mapping.get("authoredBindingKey")
        normalized_role = _role(raw_role)
        record = {
            "eventPath": event_path,
            "sourceGuid": source_guid,
            "diagnosticName": str(source_sample.get("name") or ""),
            "runtimeMappingKind": kind,
            "lifetime": lifetime,
            "rawRole": raw_role,
            "normalizedRole": normalized_role,
            "roleEvidence": role_evidence,
            "runtimeMappingRolePresent": "engineProgramRole" in runtime_mapping,
            "schedulingGroupId": str(scheduling_group_id or ""),
            "authoredBindingKey": str(authored_binding_key or ""),
            "instrumentChainSourceToRoot": routes.get(source_guid, [source_guid]),
        }
        record["routableGuidsSourceToRoot"] = [
            str(
                _mapping(instruments.get(instrument_guid, {}).get("baseProperties")).get(
                    "routableGuid"
                )
                or ""
            )
            for instrument_guid in record["instrumentChainSourceToRoot"]
        ]
        record["auditRouteIdentity"] = _route_identity(
            event_path=event_path,
            source_guid=source_guid,
            instrument_route=record["instrumentChainSourceToRoot"],
            routable_guids=record["routableGuidsSourceToRoot"],
            scheduling_group_id=scheduling_group_id,
            authored_binding_key=authored_binding_key,
        )
        records.append(record)

    return sorted(
        records,
        key=lambda item: (item["eventPath"], item["sourceGuid"]),
    )


def _role_counts(records: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts = Counter(str(item.get("normalizedRole")) for item in records)

    return dict(sorted(counts.items()))


def _raw_role_counts(records: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts = Counter(str(item.get("rawRole")) for item in records)

    return dict(sorted(counts.items()))


def _binding_summary(record: Mapping[str, Any]) -> dict[str, Any]:
    return {
        key: record.get(key)
        for key in (
            "eventPath",
            "sourceGuid",
            "diagnosticName",
            "auditRouteIdentity",
            "authoredBindingKey",
            "schedulingGroupId",
            "lifetime",
            "rawRole",
            "normalizedRole",
        )
    }


def _finite_role_evidence_is_exact(record: Mapping[str, Any]) -> bool:
    evidence = _mapping(record.get("roleEvidence"))
    identity = _mapping(evidence.get("bindingIdentity"))

    return (
        evidence.get("schema") == "byd-full-event-engine-program-role-v2"
        and evidence.get("status")
        == "PASS_EXACT_AUTHORED_BINDING_ROUTE_CLASSIFICATION"
        and evidence.get("classificationUsesDiagnosticName") is False
        and evidence.get("role") == record.get("normalizedRole")
        and identity.get("eventPath") == record.get("eventPath")
        and identity.get("sourceGuid") == record.get("sourceGuid")
        and identity.get("authoredBindingKey") == record.get("authoredBindingKey")
        and identity.get("schedulingGroupId") == record.get("schedulingGroupId")
        and bool(_sequence(evidence.get("probeValues")))
    )


def diagnostic_name_collisions(
    records: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    by_name: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for record in records:
        by_name[str(record.get("diagnosticName") or "")].append(record)
    result: list[dict[str, Any]] = []
    for diagnostic_name, group in sorted(by_name.items()):
        if len(group) < 2:
            continue
        lifetimes = sorted({str(item.get("lifetime")) for item in group})
        roles = sorted({str(item.get("normalizedRole")) for item in group})
        routes = {str(item.get("auditRouteIdentity")) for item in group}
        result.append(
            {
                "diagnosticName": diagnostic_name,
                "bindingCount": len(group),
                "differentAuthoredRoutes": len(routes) > 1,
                "crossLifetime": len(lifetimes) > 1,
                "crossRole": len(roles) > 1,
                "lifetimes": lifetimes,
                "normalizedRoles": roles,
                "bindings": [_binding_summary(item) for item in group],
            }
        )

    return result


def finite_scheduling_groups(
    records: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[Mapping[str, Any]]] = defaultdict(list)
    for record in records:
        if record.get("lifetime") == "continuous":
            continue
        grouped[
            (
                str(record.get("eventPath") or ""),
                str(record.get("schedulingGroupId") or ""),
            )
        ].append(record)
    result: list[dict[str, Any]] = []
    for (event_path, group_id), variants in sorted(grouped.items()):
        roles = sorted(
            {
                str(item.get("normalizedRole"))
                for item in variants
                if item.get("normalizedRole") in EXECUTABLE_ROLES
            }
        )
        invalid_roles = sorted(
            {
                str(item.get("normalizedRole"))
                for item in variants
                if item.get("normalizedRole") not in EXECUTABLE_ROLES
            }
        )
        result.append(
            {
                "eventPath": event_path,
                "groupId": group_id,
                "variantCount": len(variants),
                "explicitExecutableRoles": roles,
                "invalidOrUnclassifiedRoles": invalid_roles,
                "mixedExecutableRoles": len(roles) > 1,
                "variants": [_binding_summary(item) for item in variants],
            }
        )

    return result


def exact_lifetime_collisions(
    records: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[Mapping[str, Any]]] = defaultdict(list)
    for record in records:
        grouped[
            (
                str(record.get("eventPath") or ""),
                str(record.get("sourceGuid") or ""),
            )
        ].append(record)
    result = []
    for (event_path, source_guid), group in sorted(grouped.items()):
        lifetimes = sorted({str(item.get("lifetime")) for item in group})
        if len(lifetimes) <= 1:
            continue
        result.append(
            {
                "eventPath": event_path,
                "sourceGuid": source_guid,
                "lifetimes": lifetimes,
                "bindings": [_binding_summary(item) for item in group],
            }
        )

    return result


def _perspective_partitions(
    recipe: Mapping[str, Any], records: Sequence[Mapping[str, Any]]
) -> list[dict[str, Any]]:
    programs = _mapping(recipe.get("programs"))
    result: list[dict[str, Any]] = []
    for perspective in ("cabin", "exterior"):
        program = _mapping(programs.get(perspective))
        event_path = str(program.get("eventPath") or "")
        continuous = [
            item
            for item in records
            if item.get("eventPath") == event_path
            and item.get("lifetime") == "continuous"
        ]
        counts = _role_counts(continuous)
        empty_roles = [role for role in ("LOAD", "COAST") if counts.get(role, 0) == 0]
        result.append(
            {
                "perspective": perspective,
                "eventPath": event_path,
                "continuousBindingCount": len(continuous),
                "normalizedRoleCounts": counts,
                "emptyContinuousRoles": empty_roles,
                "emptyRoleCertifications": [
                    {
                        "role": role,
                        "status": "PASS_EXACT_GRAPH_EMPTY_ROLE_PARTITION",
                        "classificationBasis": (
                            "exactContinuousSourceGuidPartitionFromAuthoredRouteGainResponse"
                        ),
                        "sourceReassignmentOrSyntheticFallback": "forbidden",
                        "requiredCapture": "bitExactDigitalSilenceAtEveryNode",
                    }
                    for role in empty_roles
                ],
                "exactContinuousGuidSetPartition": True,
                "bindingRoleEvidenceForEmptyPartition": (
                    [
                        {
                            **_binding_summary(item),
                            "instrumentChainSourceToRoot": item.get(
                                "instrumentChainSourceToRoot"
                            ),
                            "routableGuidsSourceToRoot": item.get(
                                "routableGuidsSourceToRoot"
                            ),
                            "roleEvidence": item.get("roleEvidence"),
                        }
                        for item in continuous
                    ]
                    if empty_roles
                    else []
                ),
            }
        )

    return result


def audit_generated_plan(
    plan_path: Path, expected_finite_source_guids: Sequence[str]
) -> dict[str, Any]:
    if not plan_path.is_file():
        return {
            "path": str(plan_path),
            "status": "MISSING",
            "staleReasons": ["generatedPlanMissing"],
        }
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    perspectives = _mapping(plan.get("perspectives"))
    missing_program_contracts: list[str] = []
    missing_empty_role_certifications: list[str] = []
    nodes_missing_programs = 0
    node_count = 0
    capture_methods: set[str] = set()
    for perspective in ("cabin", "exterior"):
        value = _mapping(perspectives.get(perspective))
        program_contract = _mapping(value.get("engineModePrograms"))
        if not program_contract:
            missing_program_contracts.append(perspective)
        else:
            certifications = _mapping(
                _mapping(program_contract.get("sourcePartition")).get(
                    "rolePartitionCertifications"
                )
            )
            if (
                program_contract.get("schema") != EXPECTED_ENGINE_MODE_PROGRAM_SCHEMA
                or set(certifications) != {"LOAD", "COAST"}
                or any(
                    _mapping(certifications.get(role)).get("status")
                    not in {
                        "PASS_EXACT_GRAPH_EMPTY_ROLE_PARTITION",
                        "PASS_EXACT_GRAPH_NONEMPTY_ROLE_PARTITION",
                    }
                    for role in ("LOAD", "COAST")
                )
            ):
                missing_empty_role_certifications.append(perspective)
        capture_method = _mapping(program_contract.get("capture")).get("method")
        if capture_method:
            capture_methods.add(str(capture_method))
        nodes = _sequence(value.get("nodes", value.get("initialNodes")))
        for node in nodes:
            if not isinstance(node, Mapping):
                continue
            node_count += 1
            mode_programs = _mapping(node.get("modeProgramTemporaryAssetNames"))
            if set(mode_programs) != {"loadOnly", "coastOnly"}:
                nodes_missing_programs += 1

    plan_engine_finite_sources: set[str] = set()
    for effect in _sequence(plan.get("effects")):
        if not isinstance(effect, Mapping):
            continue
        event_path = str(effect.get("eventPath") or "")
        if _event_suffix(event_path) not in ENGINE_EVENT_SUFFIXES:
            continue
        contract = _mapping(effect.get("runtimeLifecycleParameterVariantContract"))
        for variant in _sequence(contract.get("variants")):
            if isinstance(variant, Mapping) and variant.get("sourceGuid"):
                plan_engine_finite_sources.add(str(variant["sourceGuid"]))
        for source_guid in _sequence(effect.get("sourceGuids")):
            plan_engine_finite_sources.add(str(source_guid))
        for node in _sequence(effect.get("nodes")):
            if isinstance(node, Mapping) and node.get("requiredSourceGuid"):
                plan_engine_finite_sources.add(str(node["requiredSourceGuid"]))

    expected = set(expected_finite_source_guids)
    stale_reasons: list[str] = []
    if missing_program_contracts:
        stale_reasons.append("missingEngineModeProgramContract")
    if missing_empty_role_certifications:
        stale_reasons.append("missingExactEmptyRolePartitionCertification")
    if nodes_missing_programs:
        stale_reasons.append("missingPerNodeIndependentModeProgramCaptures")
    if capture_methods != {"independentFreshEventMasterOutputPrograms-v1"}:
        stale_reasons.append("missingIndependentFreshEventProgramCaptureMethod")
    if expected - plan_engine_finite_sources:
        stale_reasons.append("finiteEngineEventBindingsNotSeparatedFromContinuousBed")

    return {
        "path": str(plan_path),
        "schema": plan.get("schema"),
        "status": "STALE" if stale_reasons else "CURRENT_CONTRACT_PRESENT",
        "staleReasons": stale_reasons,
        "missingPerspectiveModeProgramContracts": missing_program_contracts,
        "missingPerspectiveEmptyRoleCertifications": (
            missing_empty_role_certifications
        ),
        "engineNodeCount": node_count,
        "nodesMissingModeProgramCaptureNames": nodes_missing_programs,
        "captureMethods": sorted(capture_methods),
        "expectedFiniteEngineEventSourceCount": len(expected),
        "plannedFiniteEngineEventSourceCount": len(plan_engine_finite_sources),
        "missingFiniteEngineEventSourceGuids": sorted(
            expected - plan_engine_finite_sources
        ),
    }


def audit_family(
    *,
    family: Mapping[str, Any],
    graph: Mapping[str, Any],
    recipe: Mapping[str, Any],
    plan_path: Path,
) -> dict[str, Any]:
    records = engine_binding_records(recipe, graph)
    duplicate_names = diagnostic_name_collisions(records)
    finite_groups = finite_scheduling_groups(records)
    lifetime_collisions = exact_lifetime_collisions(records)
    unclassified = [
        item for item in records if item.get("normalizedRole") == "UNCLASSIFIED"
    ]
    missing_role = [item for item in records if item.get("normalizedRole") is None]
    invalid_role = [
        item
        for item in records
        if item.get("normalizedRole") is not None
        and item.get("normalizedRole") not in EXECUTABLE_ROLES | {"UNCLASSIFIED"}
    ]
    finite_records = [item for item in records if item.get("lifetime") != "continuous"]
    finite_without_exact_role_evidence = [
        item for item in finite_records if not _finite_role_evidence_is_exact(item)
    ]
    partitions = _perspective_partitions(recipe, records)
    family_blockers: list[str] = []
    if unclassified:
        family_blockers.append("UNCLASSIFIED_ENGINE_EVENT_ROLE")
    if missing_role:
        family_blockers.append("MISSING_ENGINE_EVENT_ROLE")
    if invalid_role:
        family_blockers.append("INVALID_ENGINE_EVENT_ROLE")
    if finite_without_exact_role_evidence:
        family_blockers.append("FINITE_ROLE_LACKS_EXACT_AUTHORED_BINDING_EVIDENCE")
    if any(item["mixedExecutableRoles"] for item in finite_groups):
        family_blockers.append("FINITE_SCHEDULING_GROUP_MIXES_ENGINE_PROGRAM_ROLES")
    if lifetime_collisions:
        family_blockers.append("EXACT_BINDING_IS_BOTH_CONTINUOUS_AND_FINITE")
    plan = audit_generated_plan(
        plan_path,
        [str(item["sourceGuid"]) for item in finite_records],
    )

    return {
        "familyId": family.get("id"),
        "bankSha256": family.get("bankSha256"),
        "carIds": list(_sequence(family.get("carIds"))),
        "canonicalCarId": list(_sequence(family.get("carIds")))[0],
        "counts": {
            "engineEventBindings": len(records),
            "continuousBindings": len(records) - len(finite_records),
            "finiteBindings": len(finite_records),
            "finiteBindingsWithExactAuthoredRoleEvidence": (
                len(finite_records) - len(finite_without_exact_role_evidence)
            ),
            "finiteBindingsWithoutExactAuthoredRoleEvidence": len(
                finite_without_exact_role_evidence
            ),
            "normalizedRoles": _role_counts(records),
            "rawRoles": _raw_role_counts(records),
            "finiteRuntimeMappingsMissingExplicitRoleField": sum(
                item.get("lifetime") != "continuous"
                and not bool(item.get("runtimeMappingRolePresent"))
                for item in records
            ),
            "unclassifiedBindings": len(unclassified),
            "missingSemanticRoleBindings": len(missing_role),
            "invalidSemanticRoleBindings": len(invalid_role),
            "finiteSchedulingGroups": len(finite_groups),
            "finiteGroupsWithMixedExecutableRoles": sum(
                bool(item["mixedExecutableRoles"]) for item in finite_groups
            ),
            "finiteGroupsWithInvalidOrUnclassifiedRoles": sum(
                bool(item["invalidOrUnclassifiedRoles"]) for item in finite_groups
            ),
            "exactContinuousFiniteBindingCollisions": len(lifetime_collisions),
            "certifiedEmptyContinuousRolePartitions": sum(
                len(_sequence(item.get("emptyRoleCertifications")))
                for item in partitions
            ),
            "duplicateDiagnosticNameGroups": len(duplicate_names),
            "bindingsInDuplicateDiagnosticNameGroups": sum(
                int(item["bindingCount"]) for item in duplicate_names
            ),
            "duplicateNamesOnDifferentRoutes": sum(
                bool(item["differentAuthoredRoutes"]) for item in duplicate_names
            ),
            "crossLifetimeDiagnosticNameGroups": sum(
                bool(item["crossLifetime"]) for item in duplicate_names
            ),
            "crossRoleDiagnosticNameGroups": sum(
                bool(item["crossRole"]) for item in duplicate_names
            ),
        },
        "perspectiveContinuousPartitions": partitions,
        "blockersBeforeSameLiveChannelMaskOracle": family_blockers,
        "continuousPcmSameLiveChannelMaskOracleStatus": (
            "BLOCKED_PENDING_SAME_EVENT_CHANNEL_MASK_ORACLE"
        ),
        "unclassifiedBindings": [
            _binding_summary(item)
            | {
                "instrumentChainSourceToRoot": item.get(
                    "instrumentChainSourceToRoot"
                ),
                "routableGuidsSourceToRoot": item.get(
                    "routableGuidsSourceToRoot"
                ),
                "roleEvidence": item.get("roleEvidence"),
            }
            for item in unclassified
        ],
        "missingRoleBindings": [_binding_summary(item) for item in missing_role],
        "invalidRoleBindings": [_binding_summary(item) for item in invalid_role],
        "finiteBindingsWithoutExactAuthoredRoleEvidence": [
            _binding_summary(item)
            | {"roleEvidence": item.get("roleEvidence")}
            for item in finite_without_exact_role_evidence
        ],
        "finiteSchedulingGroups": finite_groups,
        "exactContinuousFiniteBindingCollisions": lifetime_collisions,
        "duplicateDiagnosticNames": duplicate_names,
        "generatedPlan": plan,
    }


def _aggregate(families: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    count_keys = {
        key
        for family in families
        for key in _mapping(family.get("counts"))
        if key not in {"normalizedRoles", "rawRoles"}
    }
    totals = {
        key: sum(int(_mapping(family.get("counts")).get(key, 0)) for family in families)
        for key in sorted(count_keys)
    }
    normalized = Counter()
    raw = Counter()
    for family in families:
        counts = _mapping(family.get("counts"))
        normalized.update(
            {str(key): int(value) for key, value in _mapping(counts.get("normalizedRoles")).items()}
        )
        raw.update(
            {str(key): int(value) for key, value in _mapping(counts.get("rawRoles")).items()}
        )
    stale_plans = [
        str(family.get("familyId"))
        for family in families
        if _mapping(family.get("generatedPlan")).get("status") != "CURRENT_CONTRACT_PRESENT"
    ]
    blockers: dict[str, list[str]] = defaultdict(list)
    for family in families:
        for blocker in _sequence(family.get("blockersBeforeSameLiveChannelMaskOracle")):
            blockers[str(blocker)].append(str(family.get("familyId")))

    return {
        "familyCount": len(families),
        "counts": {
            **totals,
            "normalizedRoles": dict(sorted(normalized.items())),
            "rawRoles": dict(sorted(raw.items())),
            "familiesWithDuplicateDiagnosticNames": sum(
                int(_mapping(family.get("counts")).get("duplicateDiagnosticNameGroups", 0)) > 0
                for family in families
            ),
            "familiesWithCrossLifetimeDiagnosticNames": sum(
                int(_mapping(family.get("counts")).get("crossLifetimeDiagnosticNameGroups", 0)) > 0
                for family in families
            ),
            "familiesWithCrossRoleDiagnosticNames": sum(
                int(_mapping(family.get("counts")).get("crossRoleDiagnosticNameGroups", 0)) > 0
                for family in families
            ),
            "familiesWithFiniteEngineEventBindings": sum(
                int(_mapping(family.get("counts")).get("finiteBindings", 0)) > 0
                for family in families
            ),
            "familiesWithCertifiedEmptyContinuousRolePartitions": sum(
                int(
                    _mapping(family.get("counts")).get(
                        "certifiedEmptyContinuousRolePartitions", 0
                    )
                )
                > 0
                for family in families
            ),
            "staleGeneratedPlanFamilies": len(stale_plans),
        },
        "blockerFamilies": {
            key: sorted(value) for key, value in sorted(blockers.items())
        },
        "staleGeneratedPlanFamilyIds": sorted(stale_plans),
        "conclusions": {
            "diagnosticNamesAreSafeBindingKeys": False,
            "exactAuthoredBindingContinuousFiniteCollisionFound": bool(
                totals.get("exactContinuousFiniteBindingCollisions", 0)
            ),
            "finiteSchedulingGroupMixedExecutableRoleFound": bool(
                totals.get("finiteGroupsWithMixedExecutableRoles", 0)
            ),
            "generatedPlansMatchSameLiveChannelMaskContract": not stale_plans,
            "allFiniteEngineBindingsHaveExactExecutableRole": not any(
                totals.get(key, 0)
                for key in (
                    "unclassifiedBindings",
                    "missingSemanticRoleBindings",
                    "invalidSemanticRoleBindings",
                    "finiteBindingsWithoutExactAuthoredRoleEvidence",
                )
            ),
            "emptyContinuousRolesAreExplicitCertifiedPartitions": True,
            "allFamiliesStillRequireLiveOracle": True,
        },
    }


def inspect_discovered_recipes(new_car_root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for recipe_path in sorted(new_car_root.glob("**/recipe.json")):
        recipe = json.loads(recipe_path.read_text(encoding="utf-8"))
        transients = [
            item
            for item in _sequence(recipe.get("engineEventTransients"))
            if isinstance(item, Mapping)
        ]
        missing = [
            str(item.get("sourceGuid") or "")
            for item in transients
            if item.get("engineProgramRole") is None
        ]
        invalid = [
            str(item.get("sourceGuid") or "")
            for item in transients
            if item.get("engineProgramRole") is not None
            and _role(item.get("engineProgramRole")) not in EXECUTABLE_ROLES
        ]
        paired_plan_path = recipe_path.with_name("atlas-plan.json")
        paired_plan = audit_generated_plan(
            paired_plan_path,
            [str(item.get("sourceGuid") or "") for item in transients],
        )
        result.append(
            {
                "path": str(recipe_path),
                "carId": recipe.get("carId"),
                "recipeSha256": recipe.get("recipeSha256"),
                "finiteEngineEventBindingCount": len(transients),
                "finiteEngineEventRolesMissing": len(missing),
                "finiteEngineEventRolesInvalidOrUnclassified": len(invalid),
                "missingRoleSourceGuids": sorted(missing),
                "pairedPlan": paired_plan,
                "status": (
                    "STALE"
                    if missing
                    or invalid
                    or paired_plan.get("status") != "CURRENT_CONTRACT_PRESENT"
                    else "CURRENT_CONTRACT_PRESENT"
                ),
            }
        )

    return result


def build_report(catalog_path: Path, new_car_root: Path) -> dict[str, Any]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    family_results: list[dict[str, Any]] = []
    plan_root = catalog_path.parent
    for family in _sequence(catalog.get("families")):
        if not isinstance(family, Mapping):
            raise ProfileRecipeError("atlas catalog contains a malformed family")
        car_ids = list(_sequence(family.get("carIds")))
        if not car_ids:
            raise ProfileRecipeError("atlas family has no car IDs")
        canonical_car = str(car_ids[0])
        graph_path = new_car_root / canonical_car / "graph.json"
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
        guid_paths = _mapping(graph.get("guidPaths"))
        recipe = build_recipe(
            graph,
            {str(key): str(value) for key, value in guid_paths.items()},
            car_id=canonical_car,
        )
        family_results.append(
            audit_family(
                family=family,
                graph=graph,
                recipe=recipe,
                plan_path=plan_root / "cars" / canonical_car / "atlas-plan.json",
            )
        )

    report = {
        "schema": REPORT_SCHEMA,
        "scope": {
            "catalog": str(catalog_path),
            "catalogFamilyCount": len(_sequence(catalog.get("families"))),
            "catalogCarCount": len(_sequence(catalog.get("cars"))),
            "deduplicatedCarCount": catalog.get("deduplicatedCarCount"),
            "familyIdentity": "catalogAudioProgramFamilyIdBankSha256",
            "canonicalCarSelection": "firstCatalogFamilyCarId; aliases covered by bank/audio signature proof",
            "roleClassification": (
                "finiteBindingsUseExactEventPathSourceGuidAuthoredBindingKeyPlusGuidOwned"
                "ThrottlePlacementAndControllerEndpointKnotInteriorResponse;continuousRoles"
                "RemainSubjectToMandatorySameLiveEventCoreChannelRoutingMaskPcmOracle"
            ),
            "classificationGraphFields": [
                "events[].path",
                "events[].reachableInstrumentGuids[]",
                "events[].parameterPlacements[].{instrumentGuid,parameterGuid,layoutGuid,start,end,includeEnd}",
                "instruments[].{guid,kind,childInstruments[].guid,childInstruments[].authoredOrder}",
                "instruments[].baseProperties.{loopCount,routableGuid}",
                "instruments[].controllerGuids[]",
                "controllers[].{guid,inputKind,inputParameterGuid,propertyOwnerGuid,propertyIndex,points[]}",
                "parameters[].{guid,name,minimum,maximum,defaultValue,type}",
            ],
            "diagnosticNamePolicy": "instruments[].sample.name retained for reports only; forbidden for identity and role classification",
            "audioRendered": False,
            "bankModified": False,
        },
        "aggregate": _aggregate(family_results),
        "families": family_results,
        "discoveredGeneratedRecipes": inspect_discovered_recipes(new_car_root),
    }
    report["reportSha256"] = hashlib.sha256(canonical_json_bytes(report)).hexdigest()

    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("build/new-car-audio/full-event-atlas-plan/catalog.json"),
    )
    parser.add_argument(
        "--new-car-root",
        type=Path,
        default=Path("build/new-car-audio"),
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        report = build_report(args.catalog, args.new_car_root)
        payload = canonical_json_bytes(report) + b"\n"
        if args.output is None:
            sys.stdout.buffer.write(payload)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(payload)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)

        return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
