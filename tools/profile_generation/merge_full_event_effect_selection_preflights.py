#!/usr/bin/env python3
"""Deterministically merge isolated source-selection preflight partitions."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes  # noqa: E402
from realize_nrt_recipe import _write_atomic  # noqa: E402
from preflight_full_event_effect_selection import (  # noqa: E402
    SCHEMA,
    _channel_snapshot_tasks,
    _effect_source_tasks,
    _families,
)


class MergeError(ValueError):
    pass


def _sha(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _load(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise MergeError(f"cannot load preflight report {path}: {exc}") from exc
    if not isinstance(value, Mapping):
        raise MergeError(f"preflight report {path} is not an object")
    return value


def _snapshot_spec(family: Mapping[str, Any], task: Mapping[str, Any]) -> str:
    return _sha(
        {
            "familyId": family["familyId"],
            "planSha256": family["planSha256"],
            "bankSha256": family["bankSha256"],
            "task": task,
            "durationFrames": 1_024,
        }
    )


def merge(
    *,
    atlas_root: Path,
    staged_root: Path,
    partition_root: Path,
    output: Path,
    partition_count: int,
) -> Mapping[str, Any]:
    if partition_count < 2:
        raise MergeError("merge requires at least two isolated partitions")
    families = _families(atlas_root.resolve(strict=True), staged_root.resolve(strict=True))
    expected_sources = {
        str(task["taskSpecSha256"]): task
        for family in families
        for task in _effect_source_tasks(family)
    }
    expected_snapshots = {
        _snapshot_spec(family, task): (family, task)
        for family in families
        for task in _channel_snapshot_tasks(family)
    }
    reports: list[Mapping[str, Any]] = []
    source_records: dict[str, Mapping[str, Any]] = {}
    snapshot_records: dict[str, Mapping[str, Any]] = {}
    shared_implementation_sha: str | None = None
    shared_preflight_contract_sha: str | None = None
    for index in range(partition_count):
        report_path = partition_root / f"partition-{index}" / "report.json"
        report = _load(report_path)
        partition = report.get("taskPartition")
        if (
            report.get("schema") != SCHEMA
            or report.get("status") != "PASS"
            or not isinstance(partition, Mapping)
            or partition.get("count") != partition_count
            or partition.get("index") != index
        ):
            raise MergeError(f"partition {index} is not a passing matching preflight report")
        implementation_sha = report.get("implementationSourceSha256")
        preflight_contract_sha = report.get("preflightContractSha256")
        if not isinstance(implementation_sha, str) or not isinstance(preflight_contract_sha, str):
            raise MergeError(f"partition {index} has no implementation/contract hash")
        if shared_implementation_sha is None:
            shared_implementation_sha = implementation_sha
            shared_preflight_contract_sha = preflight_contract_sha
        elif (
            implementation_sha != shared_implementation_sha
            or preflight_contract_sha != shared_preflight_contract_sha
        ):
            raise MergeError("partitions used different preflight implementations or contracts")
        sources = report.get("sourceSelection", {}).get("tasks") if isinstance(report.get("sourceSelection"), Mapping) else None
        snapshots = report.get("rawChannelSnapshots", {}).get("scenarios") if isinstance(report.get("rawChannelSnapshots"), Mapping) else None
        if not isinstance(sources, list) or not isinstance(snapshots, list):
            raise MergeError(f"partition {index} lacks source/snapshot evidence")
        for item in sources:
            if not isinstance(item, Mapping):
                raise MergeError(f"partition {index} has malformed source task")
            task_sha = item.get("taskSpecSha256")
            if not isinstance(task_sha, str) or task_sha not in expected_sources:
                raise MergeError(f"partition {index} has unknown source task {task_sha!r}")
            if task_sha in source_records:
                raise MergeError(f"source task {task_sha} appears in multiple partitions")
            if item.get("pass") is not True:
                raise MergeError(f"source task {task_sha} did not pass")
            expected_partition = int(task_sha[:16], 16) % partition_count
            if expected_partition != index:
                raise MergeError(f"source task {task_sha} is in the wrong partition")
            source_records[task_sha] = item
        for item in snapshots:
            if not isinstance(item, Mapping):
                raise MergeError(f"partition {index} has malformed snapshot scenario")
            task_sha = item.get("taskSpecSha256")
            if not isinstance(task_sha, str) or task_sha not in expected_snapshots:
                raise MergeError(f"partition {index} has unknown snapshot task {task_sha!r}")
            if index != 0 or task_sha in snapshot_records:
                raise MergeError(f"snapshot task {task_sha} is duplicated or not in partition zero")
            if item.get("pass") is not True:
                raise MergeError(f"snapshot task {task_sha} did not pass")
            snapshot_records[task_sha] = item
        reports.append(report)
    missing_sources = sorted(set(expected_sources) - set(source_records))
    missing_snapshots = sorted(set(expected_snapshots) - set(snapshot_records))
    if missing_sources or missing_snapshots:
        raise MergeError(
            f"preflight partitions are incomplete: {len(missing_sources)} source and "
            f"{len(missing_snapshots)} snapshot tasks missing"
        )
    sources = sorted(
        source_records.values(),
        key=lambda item: (str(item["familyId"]), str(item["eventPath"]), str(item["sourceGuid"])),
    )
    snapshots = sorted(
        snapshot_records.values(),
        key=lambda item: (str(item["familyId"]), str(item.get("id") or "")),
    )
    by_family: dict[str, dict[str, Any]] = {}
    for family in families:
        family_id = str(family["familyId"])
        selected = [item for item in sources if item.get("familyId") == family_id]
        snapshot_items = [item for item in snapshots if item.get("familyId") == family_id]
        by_family[family_id] = {
            "representativeCarId": family["carId"],
            "aliasCarIds": list(family["aliasCarIds"]),
            "aliasPlanSha256s": list(family["aliasPlanSha256s"]),
            "planSha256": family["planSha256"],
            "bankSha256": family["bankSha256"],
            "effectSourceTaskCount": len(selected),
            "effectSourcePassCount": len(selected),
            "snapshotScenarioCount": len(snapshot_items),
            "snapshotPassCount": len(snapshot_items),
            "maximumLogicalChannels": max((int(item.get("maximumLogicalChannels", 0)) for item in snapshot_items), default=0),
            "maximumRealChannels": max((int(item.get("maximumRealChannels", 0)) for item in snapshot_items), default=0),
            "pass": bool(selected) and bool(snapshot_items),
        }
    report = {
        "schema": SCHEMA,
        "status": "PASS" if all(item["pass"] for item in by_family.values()) else "BLOCKED",
        "mergeSchema": "byd-full-event-effect-selection-preflight-merge-v1",
        "partitionAlgorithm": "unsignedFirst16HexTaskSpecSha256ModuloPartitionCount-v1",
        "partitionCount": partition_count,
        "implementationSourceSha256": shared_implementation_sha,
        "preflightContractSha256": shared_preflight_contract_sha,
        "partitionReports": [
            {
                "index": index,
                "reportSha256": _sha(report),
                "contractSha256": report.get("contractSha256"),
            }
            for index, report in enumerate(reports)
        ],
        "retainedAtlasPcm": False,
        "sourceSelection": {
            "taskCount": len(sources),
            "passCount": len(sources),
            "allPass": bool(sources),
            "tasks": sources,
        },
        "rawChannelSnapshots": {
            "scenarioCount": len(snapshots),
            "passCount": len(snapshots),
            "allPass": bool(snapshots),
            "scenarios": snapshots,
        },
        "families": by_family,
        "failureCount": 0,
    }
    _write_atomic(output, canonical_json_bytes(report) + b"\n")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--atlas-root", type=Path, required=True)
    parser.add_argument("--staged-root", type=Path, required=True)
    parser.add_argument("--partition-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--partition-count", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        report = merge(
            atlas_root=args.atlas_root,
            staged_root=args.staged_root,
            partition_root=args.partition_root,
            output=args.output,
            partition_count=args.partition_count,
        )
    except (MergeError, OSError, ValueError) as exc:
        print(f"preflight merge failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"status": report["status"], "familyCount": len(report["families"])}, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
