"""Silently certify FMOD software-channel priority for release source GUIDs.

The graph classifier and release plan choose semantics.  This oracle uses the
embedded sample name only as a runtime identity join, starts the exact authored
event/parameter operating point through FMOD 1.08.12, and reads the live
``FMOD_Channel_GetPriority`` value after one 256-frame no-device DSP update.
Names never enter the proof.  A shared runtime identity may certify multiple
source GUIDs only when the graph proves those GUIDs reference that same name.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes, validate_catalog
from sim.assetto import find_assetto_root
from tools.aclib_release import DEFAULT_GRAPH_ROOT, DEFAULT_RELEASE_PLAN
from tools.probe_fmod_global_voice_arbitration import (
    LOGICAL_CHANNELS,
    _OracleRuntime,
)


SCHEMA = "ac-fmod-source-priority-catalog-oracle-v1"
DEFAULT_CATALOG = PROJECT_ROOT / ".aclib-local" / "catalog-v1.json"
DEFAULT_GLOBAL_ORACLE = (
    PROJECT_ROOT
    / ".aclib-local"
    / "fmod-global-voice-arbitration-v1"
    / "proof.json"
)
DEFAULT_OUTPUT_ROOT = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\priority-oracle-v1"
)
DEFAULT_ROLES = ("BOV", "OVERRUN", "POP", "BANG", "CRACK")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root is not an object: {path}")
    return value


def _write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    temporary.replace(path)


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _parameter_key(parameters: dict[str, Any]) -> tuple[tuple[str, float], ...]:
    return tuple(sorted((str(key), float(value)) for key, value in parameters.items()))


def _event_waveforms(
    graph: dict[str, Any], event_path: str
) -> tuple[dict[str, dict[str, Any]], set[str]]:
    instruments = {
        _guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    event = next(
        (
            item
            for item in graph.get("events", [])
            if isinstance(item, dict) and item.get("path") == event_path
        ),
        None,
    )
    if event is None or event.get("mappingComplete") is not True:
        raise ValueError(f"event graph is absent/incomplete: {event_path}")
    reachable = {
        _guid(guid)
        for guid in event.get("reachableInstrumentGuids", [])
        if instruments.get(_guid(guid), {}).get("kind") == "WaveformInstrumentNode"
    }
    return instruments, reachable


def _identity_maps(
    instruments: dict[str, dict[str, Any]], reachable: Iterable[str]
) -> tuple[dict[str, str], dict[str, list[str]]]:
    runtime_to_identity: dict[str, str] = {}
    identity_to_guids: dict[str, list[str]] = defaultdict(list)
    for guid in sorted(set(reachable)):
        sample = instruments[guid].get("sample")
        if not isinstance(sample, dict) or not isinstance(sample.get("name"), str):
            raise ValueError(f"source {guid} has no runtime identity")
        runtime_name = sample["name"]
        identity = _sha256_text(runtime_name)
        previous = runtime_to_identity.setdefault(runtime_name, identity)
        if previous != identity:
            raise AssertionError("runtime identity hashing is inconsistent")
        identity_to_guids[identity].append(guid)
    return runtime_to_identity, dict(identity_to_guids)


def _probe_parameter_group(
    root: Path,
    bank: Path,
    event_path: str,
    parameters: dict[str, float],
    runtime_to_identity: dict[str, str],
    desired_identities: set[str],
    output_wav: Path,
    *,
    maximum_attempts: int,
) -> dict[str, list[dict[str, Any]]]:
    observations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    with _OracleRuntime(
        root,
        bank,
        runtime_to_identity,
        output_wav,
        max_channels=LOGICAL_CHANNELS,
        event_paths=(event_path,),
    ) as runtime:
        for attempt in range(maximum_attempts):
            key = f"priority{attempt:05d}"
            runtime.create_instance(key, event_path, parameters=parameters)
            runtime.start(key)
            runtime.flush(f"start-{attempt}")
            runtime.update(f"render-{attempt}")
            snapshot = runtime.snapshot()
            for voice in snapshot["voices"]:
                if voice.get("instanceKey") != key:
                    continue
                identity = str(voice["source"])
                priority = int(voice["priority"])
                if priority != int(voice["soundDefaultPriority"]):
                    raise AssertionError(
                        "live channel priority disagrees with its post-start sound default"
                    )
                observations[identity].append(
                    {
                        "attempt": attempt,
                        "priority": priority,
                        "isVirtual": bool(voice["isVirtual"]),
                        "groupDepth": int(voice["groupDepth"]),
                    }
                )
            runtime.stop_release(key)
            if desired_identities.issubset(observations):
                break
    return dict(observations)


def probe_catalog(
    assetto_root: Path,
    catalog_path: Path,
    plan_path: Path,
    graph_root: Path,
    global_oracle_path: Path,
    output_root: Path,
    *,
    roles: Iterable[str] = DEFAULT_ROLES,
    attempts_per_identity: int = 32,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    catalog_path = catalog_path.resolve(strict=True)
    plan_path = plan_path.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    global_oracle_path = global_oracle_path.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    catalog = _read_json(catalog_path)
    validate_catalog(catalog, require_complete=True)
    plan = _read_json(plan_path)
    if plan.get("schemaVersion") != 2 or plan.get("catalogSha256") != catalog["catalogSha256"]:
        raise ValueError("priority oracle requires the matching schema-v2 release plan")
    selected_roles = tuple(sorted(set(map(str, roles))))
    cars = {str(car["id"]): car for car in catalog["cars"]}
    families_by_id = {
        str(family["id"]): family for family in catalog["soundFamilies"]
    }
    expected = [
        (str(family["familyId"]), recipe)
        for family in plan["families"]
        for recipe in family["recipes"]
        if str(recipe["role"]) in selected_roles
    ]
    expected_keys = {(family_id, str(recipe["sourceGuid"])) for family_id, recipe in expected}
    plan_sha = _sha256_file(plan_path)
    partial_path = output_root / "partial.json"
    completed: dict[str, dict[str, Any]] = {}
    if partial_path.is_file():
        partial = _read_json(partial_path)
        if partial.get("capturePlanFileSha256") != plan_sha:
            raise ValueError("priority partial belongs to another capture plan")
        completed = {
            str(key): value
            for key, value in partial.get("sourceObservations", {}).items()
            if isinstance(value, dict)
        }

    plan_families = {str(item["familyId"]): item for item in plan["families"]}
    for family_index, family_id in enumerate(sorted({item[0] for item in expected}), 1):
        planned = plan_families[family_id]
        wanted_recipes = [
            recipe
            for recipe in planned["recipes"]
            if str(recipe["role"]) in selected_roles
            and f"{family_id}:{recipe['sourceGuid']}" not in completed
        ]
        if not wanted_recipes:
            continue
        catalog_family = families_by_id[family_id]
        representative = cars[str(catalog_family["representativeCarId"])]
        bank = root / representative["provenance"]["bankPath"]
        before_sha = _sha256_file(bank)
        if before_sha != family_id:
            raise ValueError(f"installed bank hash changed for {family_id}")
        graph_path = graph_root / "families" / f"{family_id}.json"
        graph = _read_json(graph_path)
        grouped: dict[
            tuple[str, tuple[tuple[str, float], ...]], list[dict[str, Any]]
        ] = defaultdict(list)
        for recipe in wanted_recipes:
            grouped[(str(recipe["eventPath"]), _parameter_key(recipe["parameters"]))].append(recipe)
        for group_index, ((event_path, parameter_key), recipes) in enumerate(
            sorted(grouped.items()), 1
        ):
            instruments, reachable = _event_waveforms(graph, event_path)
            runtime_to_identity, identity_to_guids = _identity_maps(instruments, reachable)
            desired: set[str] = set()
            guid_to_identity: dict[str, str] = {}
            for recipe in recipes:
                guid = str(recipe["sourceGuid"])
                sample = instruments[guid]["sample"]
                identity = _sha256_text(str(sample["name"]))
                desired.add(identity)
                guid_to_identity[guid] = identity
            attempts = max(32, min(4096, len(desired) * attempts_per_identity))
            wav = output_root / "scratch" / f"{family_id}-{group_index}.wav"
            observed = _probe_parameter_group(
                root,
                bank,
                event_path,
                dict(parameter_key),
                runtime_to_identity,
                desired,
                wav,
                maximum_attempts=attempts,
            )
            wav.unlink(missing_ok=True)
            missing = desired - observed.keys()
            if missing:
                raise AssertionError(
                    f"{family_id} {event_path} did not schedule {len(missing)} "
                    f"target runtime identities in {attempts} deterministic takes"
                )
            parameter_sha = hashlib.sha256(
                canonical_json_bytes(dict(parameter_key))
            ).hexdigest()
            for recipe in recipes:
                guid = str(recipe["sourceGuid"])
                identity = guid_to_identity[guid]
                rows = observed[identity]
                priorities = {int(row["priority"]) for row in rows}
                if len(priorities) != 1:
                    raise AssertionError(
                        f"source {guid} reported multiple channel priorities: {priorities}"
                    )
                completed[f"{family_id}:{guid}"] = {
                    "familyId": family_id,
                    "sourceGuid": guid,
                    "eventPath": event_path,
                    "manifestRole": str(recipe["role"]),
                    "softwareChannelPriority": next(iter(priorities)),
                    "runtimeIdentitySha256": identity,
                    "runtimeIdentitySourceGuidCount": len(identity_to_guids[identity]),
                    "parameterValuesSha256": parameter_sha,
                    "observationCount": len(rows),
                    "observedVirtual": any(bool(row["isVirtual"]) for row in rows),
                    "groupDepths": sorted({int(row["groupDepth"]) for row in rows}),
                }
        if _sha256_file(bank) != before_sha:
            raise AssertionError(f"installed bank changed during priority probe: {family_id}")
        _write_json_atomic(
            partial_path,
            {
                "schema": SCHEMA + "-partial",
                "capturePlanFileSha256": plan_sha,
                "sourceObservations": dict(sorted(completed.items())),
            },
        )
        print(
            f"[{family_index}] priority family={family_id} "
            f"certified={sum(key.startswith(family_id + ':') for key in completed)}",
            flush=True,
        )

    if set(completed) != {f"{family}:{guid}" for family, guid in expected_keys}:
        missing = {f"{family}:{guid}" for family, guid in expected_keys} - set(completed)
        extra = set(completed) - {f"{family}:{guid}" for family, guid in expected_keys}
        raise AssertionError(
            f"priority catalog coverage mismatch missing={len(missing)} extra={len(extra)}"
        )
    observations = sorted(
        completed.values(), key=lambda item: (item["familyId"], item["sourceGuid"])
    )
    role_counts: dict[str, dict[str, int]] = {}
    for role in selected_roles:
        counts = Counter(
            int(item["softwareChannelPriority"])
            for item in observations
            if item["manifestRole"] == role
        )
        role_counts[role] = {str(key): value for key, value in sorted(counts.items())}
    report = {
        "schema": SCHEMA,
        "basis": {
            "runtime": "Assetto Corsa FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "dspBufferFrames": 256,
            "installedBanksModified": False,
            "semanticClassificationUsesSampleNames": False,
            "sampleNamesUsedOnlyForRuntimeIdentityJoin": True,
            "priorityRead": "FMOD_Channel_GetPriority_AFTER_ONE_DSP_UPDATE",
            "postStartSoundDefaultCrossCheck": True,
        },
        "catalogSha256": catalog["catalogSha256"],
        "capturePlanFileSha256": plan_sha,
        "globalArbitrationOracleSha256": _sha256_file(global_oracle_path),
        "requestedRoles": list(selected_roles),
        "sourceCount": len(observations),
        "rolePriorityCounts": role_counts,
        "sourceObservations": observations,
        "result": "PASS_SOURCE_BOUND_COMPLETE",
    }
    return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--capture-plan", type=Path, default=DEFAULT_RELEASE_PLAN)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument("--global-oracle", type=Path, default=DEFAULT_GLOBAL_ORACLE)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--role", action="append", dest="roles")
    parser.add_argument("--attempts-per-identity", type=int, default=32)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    report = probe_catalog(
        find_assetto_root(args.assetto_root),
        args.catalog,
        args.capture_plan,
        args.graph_root,
        args.global_oracle,
        args.output_root,
        roles=args.roles or DEFAULT_ROLES,
        attempts_per_identity=args.attempts_per_identity,
    )
    proof = args.output_root.resolve() / "proof.json"
    _write_json_atomic(proof, report)
    print(
        json.dumps(
            {
                "result": report["result"],
                "proof": str(proof),
                "sourceCount": report["sourceCount"],
                "rolePriorityCounts": report["rolePriorityCounts"],
                "audioDeviceOpened": False,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
