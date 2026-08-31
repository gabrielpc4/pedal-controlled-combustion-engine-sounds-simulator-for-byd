#!/usr/bin/env python3
"""Resumable, deduplicated compiler for staged ``build/new-car-audio`` cars."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any, Mapping, Sequence

from causal_full_event_resource_proof import (
    OBSERVATION_SCHEMA,
    PACKED_SHARD_PROOF_SCHEMA,
    PROOF_SCHEMA,
    RUNTIME_UPDATE_SCHEMA,
    apply_causal_runtime_resource_update,
    bind_causal_proof_to_packed_shards,
    causal_runtime_resource_updates,
    effect_node_key,
    prove_causal_full_event_resources,
    validate_applied_causal_runtime_resource_update,
)
from generate_android_profile_recipe import (
    build_catalog_profile_input,
    build_recipe,
    canonical_json_bytes,
    load_curve_projector,
    parse_guid_paths,
)
from generate_full_event_atlas_recipe import (
    ATLAS_PACK_REPORT_SCHEMA,
    ATLAS_PLAN_SCHEMA,
    ATLAS_REALIZATION_SCHEMA,
    ATLAS_RUNTIME_SCHEMA,
    build_atlas_plan,
    build_runtime_index_template,
)
from refine_full_event_atlas import (
    ORACLE_IMPLEMENTATION,
    ORACLE_SCHEMA,
    STATE_SCHEMA,
    _state_contract_sha256,
)


SCRIPT_ROOT = Path(__file__).resolve().parent
MINIMUM_FREE_DISK_RESERVE_BYTES = 2 * 1024 * 1024 * 1024
RELEASE_REPORT_AND_INDEX_RESERVE_BYTES = 512 * 1024 * 1024
FAMILY_OUTPUT_OVERHEAD_RESERVE_BYTES = 8 * 1024 * 1024
PACK_ARCHIVE_OVERHEAD_RESERVE_BYTES_PER_FAMILY = 8 * 1024 * 1024
CAUSAL_OBSERVATIONS_FILE_NAME = "causal-observations.json"
CAUSAL_CAPTURE_REQUEST_FILE_NAME = "causal-capture-request.json"
CAUSAL_NATIVE_TRACE_FILE_NAME = "causal-native-trace.json"
CAUSAL_RESOURCE_PROOF_FILE_NAME = "causal-resource-proof.json"
CAUSAL_PACKED_SHARD_PROOF_FILE_NAME = "causal-packed-shard-proof.json"
CAUSAL_RUNTIME_UPDATE_FILE_NAME = "causal-runtime-resource-update.json"
CAPTURE_REQUEST_SCHEMA = "byd-original-bank-causal-capture-request-v1"
NATIVE_TRACE_SCHEMA = "byd-original-bank-causal-session-trace-v1"
PRODUCER_SCHEMA = "byd-causal-observation-producer-v1"


def _causal_implementation_contract_sha256(audio_lab_root: Path) -> str:
    """Bind persisted causal evidence to every producer/verifier entry point."""

    files = (
        SCRIPT_ROOT / "produce_causal_full_event_observations.py",
        SCRIPT_ROOT / "causal_full_event_resource_proof.py",
        SCRIPT_ROOT / "export_full_event_session_state_graph.py",
        audio_lab_root / "tools" / "capture_causal_full_event_session.py",
        audio_lab_root / "sim" / "fmod_bank_isolation.py",
        audio_lab_root / "sim" / "fmod_native.py",
        audio_lab_root / "sim" / "fmod_renderer.py",
    )
    missing = [str(path) for path in files if not path.is_file()]
    if missing:
        raise ValueError(
            "causal evidence implementation source is absent: " + ", ".join(missing)
        )
    manifest = {
        str(path.relative_to(audio_lab_root) if path.is_relative_to(audio_lab_root) else path.name): _sha256(path)
        for path in files
    }

    return hashlib.sha256(canonical_json_bytes(manifest)).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(canonical_json_bytes(value) + b"\n")
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


@contextmanager
def _family_staging_lock(directory: Path):
    """Prevent two workers from mutating one resumable family staging area."""

    lock = directory / ".render.lock"
    if lock.exists():
        try:
            owner = int(lock.read_text(encoding="ascii").strip())
            os.kill(owner, 0)
        except ProcessLookupError:
            lock.unlink(missing_ok=True)
        except (OSError, ValueError):
            raise ValueError(f"family staging lock requires inspection: {lock}")
        else:
            raise ValueError(f"family {directory.name} is already rendering in pid {owner}")
    lock.write_text(str(os.getpid()), encoding="ascii")
    try:
        yield
    finally:
        lock.unlink(missing_ok=True)


def _validate_resumable_plan_chain(
    seed_plan: Mapping[str, Any],
    resumed_plan: Mapping[str, Any],
    oracle_state: Mapping[str, Any] | None,
) -> None:
    """Reject a same-bank staging plan that is not a certified descendant."""

    if (
        seed_plan.get("schema") != ATLAS_PLAN_SCHEMA
        or resumed_plan.get("schema") != ATLAS_PLAN_SCHEMA
        or resumed_plan.get("id") != seed_plan.get("id")
        or resumed_plan.get("bankSha256") != seed_plan.get("bankSha256")
    ):
        raise ValueError("family staging plan belongs to another schema/atlas/bank")
    if oracle_state is None:
        if canonical_json_bytes(resumed_plan) != canonical_json_bytes(seed_plan):
            raise ValueError(
                "family staging plan has no oracle state proving its refinement"
            )

        return
    state_plan = oracle_state.get("plan")
    if (
        oracle_state.get("schema") != STATE_SCHEMA
        or oracle_state.get("atlasFamilyId") != seed_plan.get("id")
        or oracle_state.get("sourceBankSha256") != seed_plan.get("bankSha256")
        or oracle_state.get("initialPlanSha256") != seed_plan.get("planSha256")
        or not isinstance(state_plan, Mapping)
    ):
        raise ValueError("family oracle state does not descend from its seed plan")

    # Batch promotion changes only the release gate after all render/oracle
    # bytes have been certified.  Ignore that one post-render field while
    # requiring every executable/render-facing field to equal the persisted
    # oracle state exactly.
    resumed_render_plan = dict(resumed_plan)
    resumed_render_plan.pop("releaseGate", None)
    state_render_plan = dict(state_plan)
    state_render_plan.pop("releaseGate", None)
    if canonical_json_bytes(resumed_render_plan) != canonical_json_bytes(
        state_render_plan
    ):
        raise ValueError("family staging plan differs from its oracle state")


def _staged_paths(car_directory: Path) -> tuple[Path, Path, Path, dict[str, Any]]:
    intake = json.loads((car_directory / "intake.json").read_text(encoding="utf-8"))
    destination = Path(intake["destination"]).resolve(strict=True)
    graph = car_directory / "graph.json"
    guids = destination / "sfx" / "GUIDs.txt"
    bank_name = json.loads(graph.read_text(encoding="utf-8"))["bank"]["fileName"]
    bank = destination / "sfx" / bank_name
    return graph, guids.resolve(strict=True), bank.resolve(strict=True), intake


def _profile_metadata(intake: Mapping[str, Any]) -> dict[str, Any]:
    destination = Path(str(intake["destination"]))
    ui_path = destination / "ui" / "ui_car.json"
    ui = json.loads(ui_path.read_text(encoding="utf-8-sig")) if ui_path.is_file() else {}
    return {
        "displayName": str(ui.get("name") or intake["plan"]["car_id"]),
        "preview": intake.get("preview"),
        "physics": {
            "source": "assettoCorsaStagedCarData",
            "carDirectory": str(destination),
            "requiresIndependentProfileCompilation": True,
        },
    }


_PATH_DERIVED_BINDING_IDENTITY_KEYS = {
    "authoredBindingKey",
    "bindingId",
    "requiredAuthoredBindingKey",
}


def _canonical_alias_audio_topology(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Remove only car-alias path identity from an otherwise exact plan.

    Assetto donor packs may expose the same byte-identical bank under multiple
    ``event:/cars/<car-id>/...`` paths.  Binding ids are hashes of those paths,
    so comparing their bytes rejects a genuine alias.  Sequential canonical
    ids retain the complete equality/reuse graph between every binding
    reference while the rest of the v3 plan remains byte-for-byte significant.
    This deliberately canonicalizes the complete plan, including independent
    FULL/LOAD_ONLY/COAST_ONLY contracts, rather than a hand-picked subset that
    could silently omit a future audio behavior field.
    """

    identity_tokens: dict[str, str] = {}

    def canonical_event_path(value: str) -> str:
        prefix = "event:/cars/"
        if not value.startswith(prefix):
            return value
        remainder = value[len(prefix) :]
        _car_id, separator, event_tail = remainder.partition("/")
        if not separator or not event_tail:
            return value

        return f"{prefix}__bank_alias__/{event_tail}"

    def normalize(value: Any, *, key: str | None = None) -> Any:
        if key in _PATH_DERIVED_BINDING_IDENTITY_KEYS and isinstance(value, str):
            token = identity_tokens.get(value)
            if token is None:
                token = f"binding:__bank_alias_{len(identity_tokens):06d}__"
                identity_tokens[value] = token

            return token
        if key == "eventPath" and isinstance(value, str):
            return canonical_event_path(value)
        if isinstance(value, Mapping):
            return {
                child_key: normalize(value[child_key], key=str(child_key))
                for child_key in sorted(value)
            }
        if isinstance(value, list):
            return [normalize(item) for item in value]

        return value

    document = {
        key: value
        for key, value in plan.items()
        if key not in {"planSha256", "sourceRecipeSha256"}
    }

    return normalize(document)


def _family_audio_signature(plan: Mapping[str, Any]) -> str:
    """Prove aliased GUID paths describe one exact byte-identical bank plan."""

    return hashlib.sha256(
        canonical_json_bytes(_canonical_alias_audio_topology(plan))
    ).hexdigest()


def _valid_completed_family(
    directory: Path,
    plan: Mapping[str, Any],
    graph_path: Path,
    audio_lab_root: Path,
) -> bool:
    """Accept a finished family only when its source-bound oracle still matches.

    Shard hashes alone are insufficient: a renderer, scheduler, oracle, or
    interpolation implementation edit can leave byte-valid but no-longer
    certified PCM on disk.  Recompute the same implementation-byte contract
    used by resumable refinement and fail closed on every missing report/hash.
    """

    plan_path = directory / "plan.json"
    realization_path = directory / "realization-report.json"
    report_path = directory / "pack-report.json"
    runtime_path = directory / "runtime-index.json"
    state_path = directory / "oracle-state.json"
    oracle_path = directory / "oracle-status.json"
    causal_observations_path = directory / CAUSAL_OBSERVATIONS_FILE_NAME
    causal_capture_request_path = directory / CAUSAL_CAPTURE_REQUEST_FILE_NAME
    causal_native_trace_path = directory / CAUSAL_NATIVE_TRACE_FILE_NAME
    causal_proof_path = directory / CAUSAL_RESOURCE_PROOF_FILE_NAME
    causal_packed_shard_proof_path = directory / CAUSAL_PACKED_SHARD_PROOF_FILE_NAME
    causal_runtime_update_path = directory / CAUSAL_RUNTIME_UPDATE_FILE_NAME
    if not all(
        path.is_file()
        for path in (
            plan_path,
            realization_path,
            report_path,
            runtime_path,
            state_path,
            oracle_path,
            causal_observations_path,
            causal_capture_request_path,
            causal_native_trace_path,
            causal_proof_path,
            causal_packed_shard_proof_path,
            causal_runtime_update_path,
        )
    ):
        return False
    try:
        final_plan_file = json.loads(plan_path.read_text(encoding="utf-8"))
        realization = json.loads(realization_path.read_text(encoding="utf-8"))
        report = json.loads(report_path.read_text(encoding="utf-8"))
        runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
        state = json.loads(state_path.read_text(encoding="utf-8"))
        oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
        causal_proof = json.loads(causal_proof_path.read_text(encoding="utf-8"))
        causal_observations = json.loads(
            causal_observations_path.read_text(encoding="utf-8")
        )
        causal_capture_request = json.loads(
            causal_capture_request_path.read_text(encoding="utf-8")
        )
        causal_native_trace = json.loads(
            causal_native_trace_path.read_text(encoding="utf-8")
        )
        causal_packed_shard_proof = json.loads(
            causal_packed_shard_proof_path.read_text(encoding="utf-8")
        )
        causal_runtime_update = json.loads(
            causal_runtime_update_path.read_text(encoding="utf-8")
        )
        validate_applied_causal_runtime_resource_update(
            runtime,
            causal_runtime_update,
        )
        contract = _state_contract_sha256(
            plan, _sha256(graph_path), audio_lab_root.resolve(strict=True)
        )
        state_plan = state.get("plan")
        final_plan = final_plan_file
        causal_resource = report.get("causalResourceProof")
        causal_producer = causal_observations.get("producer")
        capture_request_body = (
            dict(causal_capture_request)
            if isinstance(causal_capture_request, Mapping)
            else {}
        )
        capture_request_sha256 = capture_request_body.pop("requestSha256", None)
        capture_request_self_hash_valid = (
            isinstance(capture_request_sha256, str)
            and len(capture_request_sha256) == 64
            and hashlib.sha256(canonical_json_bytes(capture_request_body)).hexdigest()
            == capture_request_sha256
        )
        native_trace_body = (
            dict(causal_native_trace)
            if isinstance(causal_native_trace, Mapping)
            else {}
        )
        native_trace_sha256 = native_trace_body.pop("traceSha256", None)
        native_trace_self_hash_valid = (
            isinstance(native_trace_sha256, str)
            and len(native_trace_sha256) == 64
            and hashlib.sha256(
                canonical_json_bytes(native_trace_body)
            ).hexdigest()
            == native_trace_sha256
        )
        oracle_sha256 = _sha256(oracle_path)
        realization_sha256 = _sha256(realization_path)
        graph_sha256 = _sha256(graph_path)
        if (
            not isinstance(final_plan, Mapping)
            or not isinstance(realization, Mapping)
            or not isinstance(report, Mapping)
            or not isinstance(runtime, Mapping)
            or not isinstance(state, Mapping)
            or not isinstance(oracle, Mapping)
            or not isinstance(causal_observations, Mapping)
            or not isinstance(causal_capture_request, Mapping)
            or not isinstance(causal_native_trace, Mapping)
            or not isinstance(causal_proof, Mapping)
            or not isinstance(causal_packed_shard_proof, Mapping)
            or not isinstance(causal_runtime_update, Mapping)
            or plan.get("schema") != ATLAS_PLAN_SCHEMA
            or final_plan.get("schema") != ATLAS_PLAN_SCHEMA
            or final_plan.get("id") != plan.get("id")
            or final_plan.get("bankSha256") != plan.get("bankSha256")
            or realization.get("schema") != ATLAS_REALIZATION_SCHEMA
            or realization.get("atlasFamilyId") != final_plan.get("id")
            or realization.get("planSha256") != final_plan.get("planSha256")
            or realization.get("sourceBankSha256Before")
            != final_plan.get("bankSha256")
            or realization.get("sourceBankSha256After")
            != final_plan.get("bankSha256")
            or realization.get("sourceBankUnchanged") is not True
            or realization.get("fullRun") is not True
            or report.get("schema") != ATLAS_PACK_REPORT_SCHEMA
            or report.get("atlasFamilyId") != final_plan.get("id")
            or runtime.get("schema") != ATLAS_RUNTIME_SCHEMA
            or runtime.get("id") != final_plan.get("id")
            or state.get("schema") != STATE_SCHEMA
            or state.get("oracleImplementation") != ORACLE_IMPLEMENTATION
            or state.get("initialPlanSha256") != plan.get("planSha256")
            or state.get("contractSha256") != contract
            or not isinstance(state_plan, Mapping)
            or state_plan.get("planSha256") != final_plan.get("planSha256")
            or not isinstance(final_plan.get("planSha256"), str)
            or report.get("planSha256") != final_plan["planSha256"]
            or runtime.get("planSha256") != final_plan["planSha256"]
            or oracle.get("schema") != ORACLE_SCHEMA
            or oracle.get("status") != "PASS"
            or oracle.get("initialPlanSha256") != plan.get("planSha256")
            or oracle.get("finalPlanSha256") != final_plan["planSha256"]
            or runtime.get("draftBlocked") is not False
            or runtime.get("oracleReportSha256") != oracle_sha256
            or runtime.get("interpolationContract", {}).get("oracleStatus")
            != "PASS"
            or runtime.get("interpolationContract", {}).get("oracleReportSha256")
            != oracle_sha256
            or final_plan.get("releaseGate", {}).get("status") != "PASS"
            or final_plan.get("releaseGate", {}).get("oracleReportSha256")
            != oracle_sha256
            or not isinstance(causal_resource, Mapping)
            or causal_observations.get("schema") != OBSERVATION_SCHEMA
            or not isinstance(causal_producer, Mapping)
            or causal_producer.get("schema") != PRODUCER_SCHEMA
            or causal_producer.get("syntheticEvidenceAccepted") is not False
            or causal_producer.get("atlasFamilyId") != final_plan.get("id")
            or causal_producer.get("planSha256") != final_plan.get("planSha256")
            or causal_producer.get("bankSha256")
            != final_plan.get("bankSha256")
            or causal_capture_request.get("schema") != CAPTURE_REQUEST_SCHEMA
            or causal_capture_request.get("atlasFamilyId")
            != final_plan.get("id")
            or causal_capture_request.get("planSha256")
            != final_plan.get("planSha256")
            or causal_capture_request.get("bankSha256")
            != final_plan.get("bankSha256")
            or causal_capture_request.get("graphSha256") != graph_sha256
            or causal_capture_request.get("realizationSha256")
            != realization_sha256
            or not capture_request_self_hash_valid
            or causal_producer.get("captureRequestSha256")
            != causal_capture_request.get("requestSha256")
            or causal_producer.get("nativeTraceSha256")
            != causal_native_trace.get("traceSha256")
            or causal_resource.get("nativeTraceSha256")
            != causal_native_trace.get("traceSha256")
            or causal_native_trace.get("schema") != NATIVE_TRACE_SCHEMA
            or causal_native_trace.get("status") != "PASS"
            or causal_native_trace.get("requestSha256")
            != causal_capture_request.get("requestSha256")
            or causal_native_trace.get("evidenceKind")
            != "nativeOriginalBankFmodNrtSession"
            or causal_native_trace.get("syntheticEvidence") is not False
            or causal_native_trace.get("atlasFamilyId") != final_plan.get("id")
            or causal_native_trace.get("planSha256")
            != final_plan.get("planSha256")
            or causal_native_trace.get("bankSha256")
            != final_plan.get("bankSha256")
            or causal_native_trace.get("graphSha256") != graph_sha256
            or causal_native_trace.get("realizationSha256")
            != realization_sha256
            or not native_trace_self_hash_valid
            or causal_proof.get("schema") != PROOF_SCHEMA
            or causal_packed_shard_proof.get("schema")
            != PACKED_SHARD_PROOF_SCHEMA
            or causal_runtime_update.get("schema") != RUNTIME_UPDATE_SCHEMA
            or causal_resource.get("proofSha256")
            != causal_proof.get("proofSha256")
            or causal_resource.get("packedShardProofSha256")
            != causal_packed_shard_proof.get("proofSha256")
            or causal_resource.get("runtimeResourceUpdateSha256")
            != causal_runtime_update.get("proofSha256")
            or causal_runtime_update.get("causalResourceProofSha256")
            != causal_proof.get("proofSha256")
            or causal_runtime_update.get("packedShardProofSha256")
            != causal_packed_shard_proof.get("proofSha256")
            or causal_resource.get("captureRequestSha256")
            != causal_capture_request.get("requestSha256")
            or causal_resource.get("observationsFile")
            != CAUSAL_OBSERVATIONS_FILE_NAME
            or causal_resource.get("observationsFileSha256")
            != _sha256(causal_observations_path)
            or causal_resource.get("captureRequestFile")
            != CAUSAL_CAPTURE_REQUEST_FILE_NAME
            or causal_resource.get("captureRequestFileSha256")
            != _sha256(causal_capture_request_path)
            or causal_resource.get("nativeTraceFile")
            != CAUSAL_NATIVE_TRACE_FILE_NAME
            or causal_resource.get("nativeTraceFileSha256")
            != _sha256(causal_native_trace_path)
            or causal_resource.get("proofFile")
            != CAUSAL_RESOURCE_PROOF_FILE_NAME
            or causal_resource.get("proofFileSha256") != _sha256(causal_proof_path)
            or causal_resource.get("packedShardProofFile")
            != CAUSAL_PACKED_SHARD_PROOF_FILE_NAME
            or causal_resource.get("packedShardProofFileSha256")
            != _sha256(causal_packed_shard_proof_path)
            or causal_resource.get("runtimeUpdateFile")
            != CAUSAL_RUNTIME_UPDATE_FILE_NAME
            or causal_resource.get("runtimeUpdateFileSha256")
            != _sha256(causal_runtime_update_path)
        ):
            return False
        if (
            causal_resource.get("implementationContractSha256")
            != _causal_implementation_contract_sha256(audio_lab_root)
        ):
            return False
        recomputed_causal_proof = prove_causal_full_event_resources(
            final_plan,
            causal_observations,
            required_trajectory_kinds=[
                "host-control-peak",
                "camera-switch-tail",
            ],
            implementation_source_root=SCRIPT_ROOT.parents[1],
        )
        if canonical_json_bytes(recomputed_causal_proof) != canonical_json_bytes(
            causal_proof
        ):
            return False
        recomputed_packed_shard_proof = bind_causal_proof_to_packed_shards(
            final_plan,
            recomputed_causal_proof,
            _compact_effect_node_shard_map(runtime),
            engine_transition_mapping_instance_bounds=(
                _engine_transition_mapping_instance_bounds(runtime)
            ),
        )
        if canonical_json_bytes(
            recomputed_packed_shard_proof
        ) != canonical_json_bytes(causal_packed_shard_proof):
            return False
        recomputed_causal_update = causal_runtime_resource_updates(
            final_plan,
            recomputed_causal_proof,
            recomputed_packed_shard_proof,
        )
        if canonical_json_bytes(recomputed_causal_update) != canonical_json_bytes(
            causal_runtime_update
        ):
            return False
        runtime_payload = canonical_json_bytes(runtime) + b"\n"
        runtime_report = report.get("runtimeIndex")
        if (
            runtime_path.read_bytes() != runtime_payload
            or
            not isinstance(runtime_report, Mapping)
            or runtime_report.get("bytes") != len(runtime_payload)
            or runtime_report.get("canonicalJsonNewlineSha256")
            != hashlib.sha256(runtime_payload).hexdigest()
        ):
            return False
        report_shards = report.get("shards")
        runtime_shards = runtime.get("shards")
        if (
            not isinstance(report_shards, list)
            or not report_shards
            or not isinstance(runtime_shards, list)
            or not runtime_shards
        ):
            return False
        report_by_name: dict[str, Mapping[str, Any]] = {}
        for shard in report_shards:
            if not isinstance(shard, Mapping):
                return False
            name = shard.get("shardName")
            if (
                not isinstance(name, str)
                or not name
                or Path(name).name != name
                or name in report_by_name
            ):
                return False
            report_by_name[name] = shard
        runtime_by_name: dict[str, Mapping[str, Any]] = {}
        for shard in runtime_shards:
            if not isinstance(shard, Mapping):
                return False
            name = shard.get("name")
            if (
                not isinstance(name, str)
                or not name
                or Path(name).name != name
                or name in runtime_by_name
            ):
                return False
            runtime_by_name[name] = shard
        if set(report_by_name) != set(runtime_by_name):
            return False
        assets_directory = directory / "assets"
        if not assets_directory.is_dir():
            return False
        actual_asset_names = {
            path.name for path in assets_directory.iterdir() if path.is_file()
        }
        if actual_asset_names != set(report_by_name):
            return False
        for name, shard in report_by_name.items():
            runtime_shard = runtime_by_name[name]
            if (
                runtime_shard.get("sha256") != shard.get("sha256")
                or runtime_shard.get("bytes") != shard.get("bytes")
            ):
                return False
            path = assets_directory / name
            if not path.is_file():
                return False
            if _sha256(path) != shard["sha256"]:
                return False
            if path.stat().st_size != shard.get("bytes"):
                return False
        return True
    except (
        AttributeError,
        OSError,
        TypeError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ):
        return False


def _adaptive_family_pcm_upper_bound(plan: Mapping[str, Any]) -> int:
    """Return the plan-bound PCM maximum, including permitted engine anchors."""

    gate = plan.get("refinementGate")
    policy = gate.get("adaptiveStoragePolicy") if isinstance(gate, Mapping) else None
    if (
        not isinstance(policy, Mapping)
        or policy.get("schema") != "byd-full-event-adaptive-storage-policy-v1"
        or not isinstance(policy.get("maximumNodesPerPerspective"), Mapping)
        or not isinstance(policy.get("nodePcmBytes"), int)
        or isinstance(policy.get("nodePcmBytes"), bool)
    ):
        raise ValueError("atlas plan has no executable adaptive storage policy")
    limits = policy["maximumNodesPerPerspective"]
    engine_nodes = 0
    for perspective in ("cabin", "exterior"):
        count = limits.get(perspective)
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            raise ValueError(
                f"atlas adaptive storage policy has invalid {perspective} node count"
            )
        engine_nodes += count
    node_bytes = int(policy["nodePcmBytes"])
    if node_bytes < 1:
        raise ValueError("atlas adaptive storage policy has invalid PCM node bytes")
    effects = int(plan.get("initialEffectPcmBytes") or 0)
    if effects < 0:
        raise ValueError("atlas plan has invalid effect PCM estimate")
    return engine_nodes * node_bytes + effects


def _disk_forecast(
    families: Mapping[str, Mapping[str, Any]],
    *,
    completed_family_ids: set[str] | None = None,
) -> dict[str, Any]:
    """Bound release disk use for one-at-a-time realizations and stored packs.

    The final output intentionally retains both mmap WAV assets and their
    downloadable stored ZIP packs.  Only the active family's source/probe
    node directory is transient, and it is deleted after hash-verified pack
    construction.  Adaptive growth is bounded by the plan's own policy, so
    this is a preflight upper bound rather than a best-effort initial estimate.
    """

    family_pcm: dict[str, int] = {
        family_id: _adaptive_family_pcm_upper_bound(item["plan"])
        for family_id, item in families.items()
    }
    completed = set(completed_family_ids or ())
    if not completed <= set(family_pcm):
        raise ValueError("disk forecast completed-family set is not in the batch")
    remaining = set(family_pcm) - completed
    # Account separately for retained family evidence/WAV headers and for each
    # stored archive's manifest/central-directory envelope.  A per-family
    # reserve remains valid if sharding grows; one global 8 MiB allowance did
    # not formally bound 32 independent ZIP manifests.
    final_assets = sum(family_pcm.values())
    final_family_outputs = final_assets + (
        len(family_pcm) * FAMILY_OUTPUT_OVERHEAD_RESERVE_BYTES
    )
    remaining_family_outputs = sum(
        family_pcm[family_id] + FAMILY_OUTPUT_OVERHEAD_RESERVE_BYTES
        for family_id in remaining
    )
    archive_bytes = final_assets + (
        len(family_pcm) * PACK_ARCHIVE_OVERHEAD_RESERVE_BYTES_PER_FAMILY
    )
    active_staging = max(
        (
            family_pcm[family_id] * 3 + 128 * 1024 * 1024
            for family_id in remaining
        ),
        default=0,
    )
    # Free-space checks are incremental.  Hash-valid completed family outputs
    # already consume filesystem blocks and must not be charged a second time
    # on resume; every future .bydpack is still charged because release
    # assembly retains both assets and archives.
    maximum_peak = (
        remaining_family_outputs
        + archive_bytes
        + active_staging
        + RELEASE_REPORT_AND_INDEX_RESERVE_BYTES
    )
    return {
        "schema": "byd-full-event-atlas-disk-forecast-v2",
        "realizationConcurrency": 1,
        "familyAdaptivePcmUpperBounds": family_pcm,
        "hashValidCompletedFamilyIdsAtPreflight": sorted(completed),
        "remainingFamilyIdsAtPreflight": sorted(remaining),
        "finalPackedWavAssetsBytesUpperBound": final_assets,
        "finalFamilyOutputsBytesUpperBound": final_family_outputs,
        "remainingFamilyOutputsBytesUpperBound": remaining_family_outputs,
        "storedBydpackArchiveBytesUpperBound": archive_bytes,
        "largestSingleFamilyTransientStagingBytesUpperBound": active_staging,
        "reportAndRuntimeReserveBytes": RELEASE_REPORT_AND_INDEX_RESERVE_BYTES,
        "maximumPeakBytesUpperBound": maximum_peak,
        "maximumAdditionalBytesUpperBoundAtPreflight": maximum_peak,
        "minimumFreeReserveBytes": MINIMUM_FREE_DISK_RESERVE_BYTES,
        "transientCleanup": "deleteOnlyFamilyStaging/nodesAfterHashVerifiedPackingBeforeFinalMove",
    }


def _remove_transient_node_directory(staging: Path, nodes: Path) -> None:
    """Remove only regenerated source/probe PCM after a successful pack."""

    if not nodes.exists():
        return
    resolved_staging = staging.resolve(strict=True)
    resolved_nodes = nodes.resolve(strict=True)
    if resolved_nodes.parent != resolved_staging or resolved_nodes.name != "nodes":
        raise ValueError(f"refusing to remove non-staging node directory: {nodes}")
    shutil.rmtree(resolved_nodes)


def _blocked_runtime_status_paths(value: object, path: str = "runtime") -> list[str]:
    """Return every release-facing status still blocked in a packed runtime."""

    if isinstance(value, Mapping):
        result: list[str] = []
        for key, item in value.items():
            child = f"{path}.{key}"
            if key in {
                "status",
                "finiteRingPoolStatus",
                "peakProofStatus",
                "proofStatus",
            } and isinstance(item, str) and item.startswith("BLOCKED"):
                result.append(child)
            result.extend(_blocked_runtime_status_paths(item, child))
        return result
    if isinstance(value, list):
        return [
            blocked
            for index, item in enumerate(value)
            for blocked in _blocked_runtime_status_paths(item, f"{path}[{index}]")
        ]
    return []


def _compact_effect_node_shard_map(runtime: Mapping[str, Any]) -> dict[str, str]:
    effects = runtime.get("effects")
    if not isinstance(effects, Mapping):
        raise ValueError("runtime has no compact effect table")
    contract = effects.get("runtimeContract")
    if (
        not isinstance(contract, Mapping)
        or contract.get("schema") != "byd-full-event-effect-runtime-v5"
    ):
        raise ValueError("runtime effects are not compact v5")
    raw_bindings = effects.get("variantBindings")
    if not isinstance(raw_bindings, list):
        raise ValueError("runtime compact effects have no variant bindings")
    bindings: dict[str, tuple[str, str]] = {}
    for raw_binding in raw_bindings:
        if not isinstance(raw_binding, Mapping):
            raise ValueError("runtime compact effect binding is not an object")
        binding_id = str(raw_binding.get("id") or "")
        source_guid = str(raw_binding.get("sourceGuid") or "")
        authored_binding_key = str(raw_binding.get("authoredBindingKey") or "")
        if (
            not binding_id
            or not source_guid
            or not authored_binding_key.startswith("binding:")
            or binding_id in bindings
        ):
            raise ValueError("runtime compact effect binding identity differs")
        bindings[binding_id] = (source_guid, authored_binding_key)
    events = effects.get("events")
    if not isinstance(events, list):
        raise ValueError("runtime compact effects have no events")
    result: dict[str, str] = {}
    for raw_event in events:
        if not isinstance(raw_event, Mapping):
            raise ValueError("runtime compact effect event is not an object")
        event_path = str(raw_event.get("eventPath") or "")
        nodes = raw_event.get("nodes")
        if not event_path or not isinstance(nodes, list):
            raise ValueError("runtime compact effect event identity differs")
        for raw_node in nodes:
            if not isinstance(raw_node, list) or len(raw_node) != 7:
                raise ValueError("runtime compact effect node encoding differs")
            binding = bindings.get(str(raw_node[0]))
            parameters = raw_node[1]
            shard_name = raw_node[2]
            if (
                binding is None
                or not isinstance(parameters, Mapping)
                or not isinstance(shard_name, str)
                or not shard_name
            ):
                raise ValueError("runtime compact effect node reference differs")
            source_guid, authored_binding_key = binding
            key = effect_node_key(
                event_path,
                source_guid,
                authored_binding_key,
                parameters,
            )
            previous = result.setdefault(key, shard_name)
            if previous != shard_name:
                raise ValueError("one causal effect node maps to multiple packed shards")
    return result


def _engine_transition_mapping_instance_bounds(
    runtime: Mapping[str, Any],
) -> dict[str, int]:
    resource_bounds = runtime.get("resourceBounds")
    per_perspective = (
        resource_bounds.get("perPerspective")
        if isinstance(resource_bounds, Mapping)
        else None
    )
    if not isinstance(per_perspective, Mapping):
        raise ValueError("runtime has no per-perspective packed resource bounds")
    result: dict[str, int] = {}
    for perspective in ("cabin", "exterior"):
        resources = per_perspective.get(perspective)
        engine = resources.get("engine") if isinstance(resources, Mapping) else None
        value = (
            engine.get("maximumMappedShardInstancesDuringCellTransition")
            if isinstance(engine, Mapping)
            else None
        )
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(
                f"runtime has no packed {perspective} engine transition mapping bound"
            )
        result[perspective] = value

    return result


def _derive_and_apply_causal_runtime_resources(
    plan: Mapping[str, Any],
    runtime: dict[str, Any],
    observations: Mapping[str, Any],
    *,
    implementation_source_root: Path,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Recompute every release-facing resource scalar from final evidence."""

    proof = prove_causal_full_event_resources(
        plan,
        observations,
        required_trajectory_kinds=["host-control-peak", "camera-switch-tail"],
        implementation_source_root=implementation_source_root,
    )
    packed_shard_proof = bind_causal_proof_to_packed_shards(
        plan,
        proof,
        _compact_effect_node_shard_map(runtime),
        engine_transition_mapping_instance_bounds=(
            _engine_transition_mapping_instance_bounds(runtime)
        ),
    )
    update = causal_runtime_resource_updates(plan, proof, packed_shard_proof)
    apply_causal_runtime_resource_update(runtime, update)
    validate_applied_causal_runtime_resource_update(runtime, update)

    return proof, packed_shard_proof, update


def _promote_runtime_release_contract(
    runtime: dict[str, Any],
    oracle: Mapping[str, Any],
    oracle_sha256: str,
    causal_resource_update: Mapping[str, Any],
) -> None:
    """Promote every runtime-facing release gate atomically after oracle PASS.

    The packer runs before the final oracle hash exists, so it correctly emits
    a draft runtime.  This function is the only promotion point.  It copies a
    compact reference to the full external oracle rather than duplicating
    callback/GUID evidence in the APK asset, then refuses a pack if *any*
    executable resource or channel status remains blocked.
    """

    validate_applied_causal_runtime_resource_update(runtime, causal_resource_update)
    if oracle.get("status") != "PASS":
        raise ValueError("cannot promote runtime when full-event oracle is not PASS")
    combined = oracle.get("combinedEngineEffectMixOracle")
    channel = (
        combined.get("globalFmodChannelArbitrationOracle")
        if isinstance(combined, Mapping)
        else None
    )
    effects = runtime.get("effects")
    runtime_channel = effects.get("channelArbitration") if isinstance(effects, Mapping) else None
    if not isinstance(channel, Mapping) or channel.get("status") != "PASS":
        raise ValueError("cannot promote runtime without passing per-family channel arbitration")
    if not isinstance(runtime_channel, dict):
        raise ValueError("runtime has no executable effects channel arbitration contract")
    resource_bounds = runtime.get("resourceBounds")
    if (
        not isinstance(resource_bounds, Mapping)
        or resource_bounds.get("schema")
        != "byd-full-event-atlas-runtime-resource-bounds-v3"
        or resource_bounds.get("scope")
        != "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects"
    ):
        raise ValueError("runtime has no exact session-retained resource-bounds v3 contract")
    per_perspective_resources = resource_bounds.get("perPerspective")
    if not isinstance(per_perspective_resources, Mapping):
        raise ValueError("runtime resource bounds have no per-perspective resources")
    for perspective, raw_resources in per_perspective_resources.items():
        resources = raw_resources if isinstance(raw_resources, Mapping) else {}
        effect_resources = resources.get("effects")
        if (
            not isinstance(effect_resources, Mapping)
            or effect_resources.get("finiteRingPoolStatus") != "PASS"
        ):
            raise ValueError(
                f"runtime {perspective} finiteRingPoolStatus is not PASS"
            )
    session_resources = resource_bounds.get("session")
    if (
        not isinstance(session_resources, Mapping)
        or session_resources.get("proofStatus") != "PASS"
    ):
        raise ValueError("runtime session mapping-instance proofStatus is not PASS")
    detail = channel.get("familyPerspectiveScenarios")
    if not isinstance(detail, list) or not detail:
        raise ValueError("channel arbitration PASS has no per-family snapshot evidence")
    by_perspective: dict[str, dict[str, int]] = {}
    for scenario in detail:
        if not isinstance(scenario, Mapping) or scenario.get("pass") is not True:
            raise ValueError("channel arbitration includes an invalid family scenario")
        perspective = scenario.get("perspective")
        if perspective not in {"cabin", "exterior"}:
            raise ValueError("channel arbitration scenario has no selected perspective")
        summary = by_perspective.setdefault(
            str(perspective),
            {
                "scenarioCount": 0,
                "maximumLogicalChannels": 0,
                "maximumRealChannels": 0,
            },
        )
        summary["scenarioCount"] += 1
        summary["maximumLogicalChannels"] = max(
            summary["maximumLogicalChannels"], int(scenario.get("maximumLogicalChannels", -1))
        )
        summary["maximumRealChannels"] = max(
            summary["maximumRealChannels"], int(scenario.get("maximumRealChannels", -1))
        )
    if set(by_perspective) != {"cabin", "exterior"}:
        raise ValueError("channel arbitration has no complete cabin/exterior evidence")
    runtime_channel.update(
        {
            "status": "PASS",
            "oracleSchema": channel.get("schema"),
            "oracleReportSha256": oracle_sha256,
            "familyPerspectiveSnapshotSummary": by_perspective,
            "evidenceLocation": "oracle-status.json#combinedEngineEffectMixOracle.globalFmodChannelArbitrationOracle",
        }
    )
    runtime["draftBlocked"] = False
    runtime["oracleReportSha256"] = oracle_sha256
    interpolation = runtime.get("interpolationContract")
    if not isinstance(interpolation, dict):
        raise ValueError("runtime has no interpolation contract")
    interpolation.update({"oracleStatus": "PASS", "oracleReportSha256": oracle_sha256})
    blocked = _blocked_runtime_status_paths(runtime)
    if blocked:
        raise ValueError(
            "PASS oracle cannot produce a release runtime with blocked executable contracts: "
            + ", ".join(blocked)
        )


def _run_family(
    family_id: str,
    representative: Mapping[str, Any],
    output_root: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    fmod_api_root: Path,
    maximum_shard_bytes: int,
) -> dict[str, Any]:
    final_directory = output_root / "families" / family_id
    plan = representative["plan"]
    if _valid_completed_family(
        final_directory,
        plan,
        Path(str(representative["graph"])),
        audio_lab_root,
    ):
        return {"familyId": family_id, "status": "SKIPPED_HASH_VALID"}
    if final_directory.exists():
        raise ValueError(
            f"family {family_id} exists but is incomplete/hash-invalid; move it aside explicitly"
        )
    staging_root = output_root / ".staging"
    staging_root.mkdir(parents=True, exist_ok=True)
    # This directory is intentionally persistent.  Full FMOD NRT rendering can
    # take hours; atomic JSON state plus per-WAV hashes let an interrupted run
    # resume without overwriting or trusting a partial capture.
    staging = staging_root / family_id
    staging.mkdir(parents=True, exist_ok=True)
    with _family_staging_lock(staging):
        nodes = staging / "nodes"
        assets = staging / "assets"
        plan_path = staging / "plan.json"
        seed_plan_path = staging / "seed-plan.json"
        realization_path = staging / "realization-report.json"
        oracle_path = staging / "oracle-status.json"
        oracle_state_path = staging / "oracle-state.json"
        runtime_path = staging / "runtime-index.json"
        pack_report_path = staging / "pack-report.json"
        causal_observations_path = staging / CAUSAL_OBSERVATIONS_FILE_NAME
        causal_capture_request_path = staging / CAUSAL_CAPTURE_REQUEST_FILE_NAME
        causal_native_trace_path = staging / CAUSAL_NATIVE_TRACE_FILE_NAME
        causal_proof_path = staging / CAUSAL_RESOURCE_PROOF_FILE_NAME
        causal_packed_shard_proof_path = (
            staging / CAUSAL_PACKED_SHARD_PROOF_FILE_NAME
        )
        causal_runtime_update_path = staging / CAUSAL_RUNTIME_UPDATE_FILE_NAME
        if seed_plan_path.is_file():
            seed = json.loads(seed_plan_path.read_text(encoding="utf-8"))
            if seed.get("planSha256") != plan["planSha256"]:
                raise ValueError(
                    f"family {family_id} generator plan changed; move its staging directory aside before rerendering"
                )
        else:
            _write_json_atomic(seed_plan_path, plan)
            seed = dict(plan)
        if plan_path.is_file():
            resumed = json.loads(plan_path.read_text(encoding="utf-8"))
            oracle_state = (
                json.loads(oracle_state_path.read_text(encoding="utf-8"))
                if oracle_state_path.is_file()
                else None
            )
            _validate_resumable_plan_chain(seed, resumed, oracle_state)
        else:
            _write_json_atomic(plan_path, plan)
        environment = dict(os.environ)
        environment["FMOD_API_ROOT"] = str(fmod_api_root)
        subprocess.run(
            [
                "arch",
                "-x86_64",
                "/usr/bin/python3",
                str(SCRIPT_ROOT / "refine_full_event_atlas.py"),
                "--plan",
                str(plan_path),
                "--bank",
                str(representative["bank"]),
                "--graph",
                str(representative["graph"]),
                "--audio-lab-root",
                str(audio_lab_root),
                "--assetto-root",
                str(assetto_root),
                "--node-directory",
                str(nodes),
                "--state-output",
                str(oracle_state_path),
                "--plan-output",
                str(plan_path),
                "--realization-output",
                str(realization_path),
                "--oracle-output",
                str(oracle_path),
            ],
            check=True,
            env=environment,
        )
        oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
        if oracle["status"] != "PASS":
            return {
                "familyId": family_id,
                "status": "BLOCKED_RESUMABLE",
                "oracleProbeCount": oracle["probeCount"],
                "reason": "oracle metrics or exact effect mapping did not pass",
            }
        causal_capture = subprocess.run(
            [
                "arch",
                "-x86_64",
                "/usr/bin/python3",
                str(SCRIPT_ROOT / "produce_causal_full_event_observations.py"),
                "--plan",
                str(plan_path),
                "--bank",
                str(representative["bank"]),
                "--graph",
                str(representative["graph"]),
                "--realization",
                str(realization_path),
                "--audio-lab-root",
                str(audio_lab_root),
                "--fmod-api-root",
                str(fmod_api_root),
                "--implementation-source-root",
                str(SCRIPT_ROOT.parents[1]),
                "--output",
                str(causal_observations_path),
            ],
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        if (
            causal_capture.returncode
            or not causal_observations_path.is_file()
            or not causal_capture_request_path.is_file()
            or not causal_native_trace_path.is_file()
        ):
            detail = (causal_capture.stderr or causal_capture.stdout).strip()
            return {
                "familyId": family_id,
                "status": "BLOCKED_RESUMABLE",
                "oracleProbeCount": oracle["probeCount"],
                "reason": (
                    "original-bank causal host-control/camera-tail capture failed"
                    + (f": {detail[-4000:]}" if detail else " without diagnostics")
                ),
            }
        subprocess.run(
            [
                os.sys.executable,
                str(SCRIPT_ROOT / "pack_full_event_atlas.py"),
                "--plan",
                str(plan_path),
                "--realization-report",
                str(realization_path),
                "--node-directory",
                str(nodes),
                "--output-directory",
                str(assets),
                "--runtime-index-output",
                str(runtime_path),
                "--pack-report-output",
                str(pack_report_path),
                "--maximum-shard-bytes",
                str(maximum_shard_bytes),
            ],
            check=True,
        )
        final_plan = json.loads(plan_path.read_text(encoding="utf-8"))
        oracle_sha = _sha256(oracle_path)
        runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
        causal_observations = json.loads(
            causal_observations_path.read_text(encoding="utf-8")
        )
        causal_proof, causal_packed_shard_proof, causal_runtime_update = (
            _derive_and_apply_causal_runtime_resources(
                final_plan,
                runtime,
                causal_observations,
                implementation_source_root=SCRIPT_ROOT.parents[1],
            )
        )
        _write_json_atomic(causal_proof_path, causal_proof)
        _write_json_atomic(
            causal_packed_shard_proof_path,
            causal_packed_shard_proof,
        )
        _write_json_atomic(causal_runtime_update_path, causal_runtime_update)
        release_gate = dict(final_plan.get("releaseGate") or {})
        release_gate.update(
            {
                "status": oracle["status"],
                "reason": (
                    "adaptive full-event NRT oracle passed"
                    if oracle["status"] == "PASS"
                    else "adaptive full-event NRT oracle or effect mapping is blocked"
                ),
                "oracleReportSha256": oracle_sha,
                "causalResourceProofSha256": causal_proof["proofSha256"],
                "causalPackedShardProofSha256": causal_packed_shard_proof[
                    "proofSha256"
                ],
                "causalRuntimeResourceUpdateSha256": causal_runtime_update[
                    "proofSha256"
                ],
                "convergedIterations": oracle["convergedIterations"],
            }
        )
        final_plan["releaseGate"] = release_gate
        _promote_runtime_release_contract(
            runtime,
            oracle,
            oracle_sha,
            causal_runtime_update,
        )
        _write_json_atomic(plan_path, final_plan)
        _write_json_atomic(runtime_path, runtime)
        # The packer writes a provisional runtime before the final oracle hash
        # and draft gate are known. Bind the report to these final canonical
        # bytes so a post-pack trigger/offset/ref edit cannot reuse valid WAV
        # shards and still pass assembly.
        runtime_payload = canonical_json_bytes(runtime) + b"\n"
        pack_report = json.loads(pack_report_path.read_text(encoding="utf-8"))
        pack_report["causalResourceProof"] = {
            "schema": causal_proof["schema"],
            "proofSha256": causal_proof["proofSha256"],
            "packedShardProofSha256": causal_packed_shard_proof["proofSha256"],
            "runtimeResourceUpdateSha256": causal_runtime_update["proofSha256"],
            "implementationContractSha256": (
                _causal_implementation_contract_sha256(audio_lab_root)
            ),
            "observationsFile": CAUSAL_OBSERVATIONS_FILE_NAME,
            "observationsFileSha256": _sha256(causal_observations_path),
            "captureRequestFile": CAUSAL_CAPTURE_REQUEST_FILE_NAME,
            "captureRequestFileSha256": _sha256(causal_capture_request_path),
            "captureRequestSha256": causal_observations["producer"][
                "captureRequestSha256"
            ],
            "nativeTraceFile": CAUSAL_NATIVE_TRACE_FILE_NAME,
            "nativeTraceSha256": causal_observations["producer"][
                "nativeTraceSha256"
            ],
            "nativeTraceFileSha256": _sha256(causal_native_trace_path),
            "proofFile": CAUSAL_RESOURCE_PROOF_FILE_NAME,
            "proofFileSha256": _sha256(causal_proof_path),
            "packedShardProofFile": CAUSAL_PACKED_SHARD_PROOF_FILE_NAME,
            "packedShardProofFileSha256": _sha256(
                causal_packed_shard_proof_path
            ),
            "runtimeUpdateFile": CAUSAL_RUNTIME_UPDATE_FILE_NAME,
            "runtimeUpdateFileSha256": _sha256(causal_runtime_update_path),
        }
        runtime_report = dict(pack_report.get("runtimeIndex") or {})
        evidence_files = list(runtime_report.get("evidenceOutsideRuntimeIndex") or [])
        for file_name in (
            CAUSAL_OBSERVATIONS_FILE_NAME,
            CAUSAL_CAPTURE_REQUEST_FILE_NAME,
            CAUSAL_NATIVE_TRACE_FILE_NAME,
            CAUSAL_RESOURCE_PROOF_FILE_NAME,
            CAUSAL_PACKED_SHARD_PROOF_FILE_NAME,
            CAUSAL_RUNTIME_UPDATE_FILE_NAME,
        ):
            if file_name not in evidence_files:
                evidence_files.append(file_name)
        runtime_report.update(
            {
                "bytes": len(runtime_payload),
                "canonicalJsonNewlineSha256": hashlib.sha256(
                    runtime_payload
                ).hexdigest(),
                "evidenceOutsideRuntimeIndex": evidence_files,
            }
        )
        pack_report["runtimeIndex"] = runtime_report
        _write_json_atomic(pack_report_path, pack_report)
        # A complete family has immutable packed WAVs plus reports.  The
        # source-solo nodes, oracle probes, and differential temporary WAVs
        # are regenerated artifacts, so discard only that verified staging
        # child before exposing the final family.  Failed/blocked families
        # return earlier and retain their complete resumable node cache.
        _remove_transient_node_directory(staging, nodes)
        final_directory.parent.mkdir(parents=True, exist_ok=True)
        os.replace(staging, final_directory)
    return {"familyId": family_id, "status": "RENDERED_AND_PACKED"}


def compile_batch(
    staged_root: Path,
    output_root: Path,
    audio_lab_root: Path,
    *,
    selected_car_ids: set[str] | None,
    realize: bool,
    workers: int,
    fmod_api_root: Path | None,
    maximum_shard_bytes: int,
) -> dict[str, Any]:
    output_root.mkdir(parents=True, exist_ok=True)
    projector = load_curve_projector(audio_lab_root)
    cars: list[dict[str, Any]] = []
    families: dict[str, dict[str, Any]] = {}
    for graph_path in sorted(staged_root.glob("*/graph.json")):
        car_id = graph_path.parent.name
        if selected_car_ids is not None and car_id not in selected_car_ids:
            continue
        graph_path, guids_path, bank_path, intake = _staged_paths(graph_path.parent)
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
        embedded_guid_paths = graph.get("guidPaths")
        guid_paths = (
            {
                str(key).strip().strip("{}").casefold(): str(value)
                for key, value in embedded_guid_paths.items()
            }
            if isinstance(embedded_guid_paths, dict)
            else parse_guid_paths(
                guids_path.read_text(encoding="utf-8-sig", errors="strict")
            )
        )
        try:
            recipe = build_recipe(
                graph, guid_paths, car_id=car_id, curve_projector=projector
            )
            plan = build_atlas_plan(recipe)
        except ValueError as exc:
            raise ValueError(f"{car_id}: {exc}") from exc
        profile = build_catalog_profile_input(recipe, _profile_metadata(intake))
        profile["audioProgramFamilyId"] = plan["id"]
        profile["packRequirement"] = {
            "id": plan["id"],
            "assetDirectory": plan["id"],
            "atlasPlanSha256": plan["planSha256"],
            "bankSha256": plan["bankSha256"],
        }
        car_output = output_root / "cars" / car_id
        _write_json_atomic(car_output / "source-conservation-report.json", recipe)
        _write_json_atomic(car_output / "atlas-plan.json", plan)
        _write_json_atomic(car_output / "catalog-input.json", profile)
        family_id = plan["id"]
        cars.append(
            {
                "carId": car_id,
                "audioProgramFamilyId": family_id,
                "catalogInput": str(car_output / "catalog-input.json"),
                "releaseStatus": "BLOCKED_PENDING_ORACLE",
            }
        )
        previous = families.get(family_id)
        audio_signature = _family_audio_signature(plan)
        if previous is None:
            families[family_id] = {
                "plan": plan,
                "bank": bank_path,
                "graph": graph_path,
                "carIds": [car_id],
                "audioSignatureSha256": audio_signature,
                "aliasProofs": [
                    {
                        "carId": car_id,
                        "bankSha256": plan["bankSha256"],
                        "sourcePlanSha256": plan["planSha256"],
                        "audioSignatureSha256": audio_signature,
                    }
                ],
            }
        else:
            if previous["audioSignatureSha256"] != audio_signature:
                raise ValueError(
                    "byte-identical bank aliases differ in audio topology for "
                    f"{family_id}: existing={sorted(previous['carIds'])}, new={car_id}, "
                    f"existingSignature={previous['audioSignatureSha256']}, "
                    f"newSignature={audio_signature}"
                )
            previous["carIds"].append(car_id)
            previous["aliasProofs"].append(
                {
                    "carId": car_id,
                    "bankSha256": plan["bankSha256"],
                    "sourcePlanSha256": plan["planSha256"],
                    "audioSignatureSha256": audio_signature,
                }
            )
    # Every alias-specific plan remains beside its car as audit evidence, but
    # Android installs one representative family.  Bind all alias catalog
    # requirements to that exact representative seed plan; leaving each car's
    # path-derived plan hash here would make the final topology validator
    # reject every deduplicated family.
    for car in cars:
        family_id = str(car["audioProgramFamilyId"])
        catalog_input_path = Path(str(car["catalogInput"]))
        profile = json.loads(catalog_input_path.read_text(encoding="utf-8"))
        profile["packRequirement"]["atlasPlanSha256"] = families[family_id][
            "plan"
        ]["planSha256"]
        _write_json_atomic(catalog_input_path, profile)
    estimated_engine_bytes = sum(
        int(value["plan"]["initialEnginePcmBytes"]) for value in families.values()
    )
    estimated_effect_bytes = sum(
        int(value["plan"]["initialEffectPcmBytes"]) for value in families.values()
    )
    estimated_total_bytes = estimated_engine_bytes + estimated_effect_bytes
    staging_estimate = sum(
        int(value["plan"]["initialStagingBytesEstimate"])
        for value in families.values()
    )
    completed_family_ids: set[str] = set()
    if realize:
        for family_id, representative in sorted(families.items()):
            if _valid_completed_family(
                output_root / "families" / family_id,
                representative["plan"],
                Path(str(representative["graph"])),
                audio_lab_root,
            ):
                completed_family_ids.add(family_id)
    disk_forecast = _disk_forecast(
        families,
        completed_family_ids=completed_family_ids,
    )
    free_bytes = shutil.disk_usage(output_root.parent).free
    required_free_bytes = (
        int(disk_forecast["maximumPeakBytesUpperBound"])
        + int(disk_forecast["minimumFreeReserveBytes"])
    )
    disk_forecast["availableBytesAtPreflight"] = free_bytes
    disk_forecast["requiredFreeBytesIncludingReserve"] = required_free_bytes
    disk_forecast["preflightPass"] = free_bytes >= required_free_bytes
    if realize and workers != 1:
        raise ValueError(
            "real atlas generation requires --workers 1 so its disk forecast remains valid"
        )
    if realize and free_bytes < required_free_bytes:
        raise ValueError(
            "per-family atlas realization/packing needs at most "
            f"{required_free_bytes} free bytes including its 2 GiB reserve; "
            f"only {free_bytes} bytes are free"
        )
    family_results: list[dict[str, Any]] = []
    if realize:
        if fmod_api_root is None:
            raise ValueError("--realize requires --fmod-api-root")
        assetto_root = audio_lab_root / "macos_bank_lab"
        for family_id, representative in sorted(families.items()):
            family_results.append(
                _run_family(
                    family_id,
                    representative,
                    output_root,
                    audio_lab_root,
                    assetto_root,
                    fmod_api_root,
                    maximum_shard_bytes,
                )
            )
    results_by_family = {
        str(result["familyId"]): result for result in family_results
    }
    successful_statuses = {"RENDERED_AND_PACKED", "SKIPPED_HASH_VALID"}
    all_families_pass = (
        realize
        and set(results_by_family) == set(families)
        and all(
            result.get("status") in successful_statuses
            for result in results_by_family.values()
        )
    )
    for car in cars:
        result = results_by_family.get(str(car["audioProgramFamilyId"]))
        car["releaseStatus"] = (
            "PASS"
            if result is not None and result.get("status") in successful_statuses
            else "BLOCKED_PENDING_ORACLE"
        )
    catalog = {
        "schema": "byd-full-event-atlas-batch-v1",
        "releaseStatus": (
            "PASS" if all_families_pass else "BLOCKED_PENDING_PER_FAMILY_ORACLE"
        ),
        "carCount": len(cars),
        "familyCount": len(families),
        "deduplicatedCarCount": len(cars) - len(families),
        "initialEnginePcmBytes": estimated_engine_bytes,
        "initialEffectPcmBytes": estimated_effect_bytes,
        "initialTotalPcmBytes": estimated_total_bytes,
        "initialStagingBytesEstimate": staging_estimate,
        "diskForecast": disk_forecast,
        "cars": cars,
        "families": [
            {
                "id": family_id,
                "representativeCarId": value["carIds"][0],
                "carIds": sorted(value["carIds"]),
                "planSha256": value["plan"]["planSha256"],
                "audioSignatureSha256": value["audioSignatureSha256"],
                "aliasProofs": value["aliasProofs"],
                "initialEngineNodeCount": value["plan"]["initialEngineNodeCount"],
                "initialEnginePcmBytes": value["plan"]["initialEnginePcmBytes"],
                "initialEffectPcmBytes": value["plan"]["initialEffectPcmBytes"],
                "initialTotalPcmBytes": value["plan"]["initialTotalPcmBytes"],
                "initialStagingBytesEstimate": value["plan"]["initialStagingBytesEstimate"],
            }
            for family_id, value in sorted(families.items())
        ],
        "familyResults": sorted(family_results, key=lambda item: item["familyId"]),
    }
    _write_json_atomic(output_root / "catalog.json", catalog)
    return catalog


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged-root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--car-id", action="append", default=[])
    parser.add_argument("--realize", action="store_true")
    parser.add_argument("--workers", type=int, default=1, choices=(1, 2, 3))
    parser.add_argument("--fmod-api-root", type=Path)
    parser.add_argument("--maximum-shard-bytes", type=int, default=256 * 1024 * 1024)
    args = parser.parse_args(argv)
    try:
        compile_batch(
            args.staged_root.resolve(strict=True),
            args.output_root.resolve(),
            args.audio_lab_root.resolve(strict=True),
            selected_car_ids=set(args.car_id) if args.car_id else None,
            realize=args.realize,
            workers=args.workers,
            fmod_api_root=(
                args.fmod_api_root.resolve(strict=True)
                if args.fmod_api_root is not None
                else None
            ),
            maximum_shard_bytes=args.maximum_shard_bytes,
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
