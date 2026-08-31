"""Resume a silent graph audit across all deduplicated official AC banks."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Callable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import validate_catalog
from sim.assetto import find_assetto_root
from sim.fmod_probe import SilentFmodBankProbe
from tools.audit_fmod_bank_graph import (
    BANK_GRAPH_AUDIT_SCHEMA,
    BANK_GRAPH_TOOL_CAPABILITIES,
    DEFAULT_PARSER_ROOT,
    BankGraphAuditError,
    BankGraphAuditIncomplete,
    _write_atomic,
    audit_bank_graph,
    build_static_auditor,
    validate_bank_graph_report,
)


SUMMARY_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
DEFAULT_CATALOG = PROJECT_ROOT / ".aclib-local" / "catalog-v1.json"
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
GRAPH_TRAVERSED_INSTRUMENT_KINDS = {
    "EventInstrumentNode",
    "MultiInstrumentNode",
    "ScattererInstrumentNode",
    "SilenceInstrumentNode",
    "WaveformInstrumentNode",
}
ROLE_TOKENS = {
    "backfire",
    "bang",
    "bov",
    "coast",
    "crack",
    "exhaust",
    "gear",
    "idle",
    "intake",
    "limiter",
    "load",
    "off",
    "on",
    "overrun",
    "pop",
    "shift",
    "spool",
    "transmission",
    "turbo",
}
_TOKEN = re.compile(r"[a-z0-9]+")


@dataclass(frozen=True)
class FamilyTarget:
    family_id: str
    representative_car_id: str
    member_ids: tuple[str, ...]
    bank_relative_path: str
    bank_path: Path


def _load_catalog(path: Path) -> dict[str, Any]:
    catalog = json.loads(path.read_text(encoding="utf-8"))
    validate_catalog(catalog, require_complete=True)
    return catalog


def catalog_family_targets(
    catalog: dict[str, Any], assetto_root: Path
) -> list[FamilyTarget]:
    cars = {str(car["id"]): car for car in catalog["cars"]}
    result: list[FamilyTarget] = []
    for family in sorted(catalog["soundFamilies"], key=lambda item: str(item["id"])):
        representative = str(family["representativeCarId"])
        car = cars[representative]
        relative = str(car["provenance"]["bankPath"])
        family_id = str(family["id"])
        if str(car["provenance"]["bankSha256"]) != family_id:
            raise ValueError(f"catalog family/provenance hash mismatch for {representative}")
        bank_path = assetto_root.joinpath(*relative.split("/")).resolve(strict=True)
        result.append(
            FamilyTarget(
                family_id,
                representative,
                tuple(str(item) for item in family["memberIds"]),
                relative,
                bank_path,
            )
        )
    if len(result) != int(catalog["counts"]["soundFamilies"]):
        raise ValueError("catalog sound-family count is inconsistent")
    if len({target.family_id for target in result}) != len(result):
        raise ValueError("catalog contains duplicate sound-family hashes")
    return result


def _tokens(value: str) -> set[str]:
    return set(_TOKEN.findall(value.casefold())) & ROLE_TOKENS


def role_classification_evidence(report: dict[str, Any]) -> dict[str, Any]:
    """Emit raw clues and candidates; this deliberately does not assign roles."""

    controllers = {item["guid"]: item for item in report["controllers"]}
    events_by_instrument: dict[str, set[str]] = defaultdict(set)
    event_suffixes: Counter[str] = Counter()
    for event in report["events"]:
        path = str(event["path"])
        event_suffixes[path.rsplit("/", 1)[-1].casefold()] += 1
        for guid in event["reachableInstrumentGuids"]:
            events_by_instrument[str(guid)].add(path)

    trend_guids: dict[str, list[str]] = defaultdict(list)
    sample_tokens: Counter[str] = Counter()
    event_tokens: Counter[str] = Counter()
    waveform_count = 0
    for instrument in report["instruments"]:
        sample = instrument.get("sample")
        if not isinstance(sample, dict):
            continue
        waveform_count += 1
        guid = str(instrument["guid"])
        for token in _tokens(str(sample.get("name") or "")):
            sample_tokens[token] += 1
        for path in events_by_instrument.get(guid, ()):
            for token in _tokens(path):
                event_tokens[token] += 1

        trends: set[str] = set()
        for controller_guid in instrument.get("controllerGuids", []):
            controller = controllers.get(str(controller_guid))
            if (
                not controller
                or controller.get("inputParameterName") != "throttle"
                or int(controller.get("propertyIndex", -1)) != 0
            ):
                continue
            points = sorted(controller.get("points", []), key=lambda point: float(point["x"]))
            if len(points) < 2:
                continue
            delta = float(points[-1]["y"]) - float(points[0]["y"])
            trends.add("rising" if delta > 0.01 else "falling" if delta < -0.01 else "flat")
        trend = next(iter(trends)) if len(trends) == 1 else "mixed" if trends else "missing"
        trend_guids[trend].append(guid)

    ordered_trends = ("rising", "falling", "flat", "mixed", "missing")
    return {
        "status": "evidenceOnlyNotRoleClassification",
        "roleIsolationProven": False,
        "waveformInstruments": waveform_count,
        "throttleVolumeEndpointTrendCounts": {
            trend: len(trend_guids.get(trend, [])) for trend in ordered_trends
        },
        "loadLikeCandidateInstrumentGuids": sorted(trend_guids.get("rising", [])),
        "coastLikeCandidateInstrumentGuids": sorted(trend_guids.get("falling", [])),
        "ambiguousInstrumentGuids": sorted(
            trend_guids.get("flat", [])
            + trend_guids.get("mixed", [])
            + trend_guids.get("missing", [])
        ),
        "weakSampleNameTokenCounts": dict(sorted(sample_tokens.items())),
        "eventPathTokenCounts": dict(sorted(event_tokens.items())),
        "eventSuffixCounts": dict(sorted(event_suffixes.items())),
    }


def _kind_rows(counter: Counter[str], families: dict[str, set[str]]) -> list[dict[str, Any]]:
    return [
        {
            "kind": kind,
            "occurrences": counter[kind],
            "familyCount": len(families[kind]),
        }
        for kind in sorted(counter)
    ]


def _numeric_kind_rows(
    counter: Counter[int], labels: dict[int, set[str]], families: dict[int, set[str]]
) -> list[dict[str, Any]]:
    return [
        {
            "rawValue": value,
            "parserLabels": sorted(labels[value]),
            "occurrences": counter[value],
            "familyCount": len(families[value]),
        }
        for value in sorted(counter)
    ]


def _failure_from_exception(target: FamilyTarget, exc: Exception) -> dict[str, Any]:
    failure: dict[str, Any] = {
        "familyId": target.family_id,
        "representativeCarId": target.representative_car_id,
        "bankPath": target.bank_relative_path,
        "errorType": type(exc).__name__,
        "error": str(exc),
        "mappingIncompleteEvents": [],
    }
    if isinstance(exc, BankGraphAuditIncomplete):
        report = exc.report
        failure["bankFileVersion"] = report.get("bank", {}).get("fileVersion")
        failure["unknownChunks"] = report.get("unknownChunks", [])
        incomplete: list[dict[str, Any]] = []
        for event in report.get("events", []):
            if event.get("mappingComplete"):
                continue
            mapped = set(event.get("mappedSampleIds", []))
            resolved = set(event.get("resolverSampleIds", []))
            incomplete.append(
                {
                    "eventGuid": event.get("guid"),
                    "missingFromGraph": sorted(resolved - mapped),
                    "extraInGraph": sorted(mapped - resolved),
                }
            )
        failure["mappingIncompleteEvents"] = incomplete
    return failure


def build_summary(
    *,
    catalog: dict[str, Any],
    targets: list[FamilyTarget],
    reports: dict[str, dict[str, Any]],
    failures: dict[str, dict[str, Any]],
    reused_family_ids: set[str],
    audited_this_run_ids: set[str],
    parser_metadata: dict[str, Any],
    invalidated_cache: dict[str, str] | None = None,
) -> dict[str, Any]:
    invalidated_cache = invalidated_cache or {}
    kind_counters: dict[str, Counter[str]] = defaultdict(Counter)
    kind_families: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    curve_types: Counter[int] = Counter()
    curve_families: dict[int, set[str]] = defaultdict(set)
    dsp_values: Counter[int] = Counter()
    dsp_labels: dict[int, set[str]] = defaultdict(set)
    dsp_families: dict[int, set[str]] = defaultdict(set)
    unknown_chunks: Counter[int] = Counter()
    unknown_labels: dict[int, set[str]] = defaultdict(set)
    unknown_families: dict[int, set[str]] = defaultdict(set)
    plugin_dsps: Counter[str] = Counter()
    plugin_families: dict[str, set[str]] = defaultdict(set)
    playlist_modes: Counter[str] = Counter()
    playlist_families: dict[str, set[str]] = defaultdict(set)
    unresolved: dict[str, dict[str, Any]] = {}
    versions: Counter[int] = Counter()
    role_trends: Counter[str] = Counter()
    role_sample_tokens: Counter[str] = Counter()
    role_event_tokens: Counter[str] = Counter()
    timeline_controller_count = 0
    timeline_controller_families: set[str] = set()
    timeline_input_guids: set[str] = set()
    timeline_raw_positions: list[int] = []
    family_rows: list[dict[str, Any]] = []
    waveform_isolation_offsets = 0

    target_by_id = {target.family_id: target for target in targets}
    for family_id, report in sorted(reports.items()):
        target = target_by_id[family_id]
        versions[int(report["bank"]["fileVersion"])] += 1
        waveform_isolation_offsets += int(
            report["sourceIsolationOffsets"]["waveformInstrumentBodies"]
        )
        for category in (
            "instruments",
            "modulators",
            "effectNodes",
            "buses",
            "transitions",
            "controllerInputs",
        ):
            for item in report["featureKinds"].get(category, []):
                kind = str(item["kind"])
                kind_counters[category][kind] += int(item["count"])
                kind_families[category][kind].add(family_id)
        for item in report["featureKinds"].get("curvePointTypes", []):
            value = int(item["type"])
            curve_types[value] += int(item["count"])
            curve_families[value].add(family_id)
        for effect in report.get("effects", []):
            if effect.get("builtInDspValue") is not None:
                value = int(effect["builtInDspValue"])
                dsp_values[value] += 1
                dsp_labels[value].add(str(effect.get("builtInDsp") or value))
                dsp_families[value].add(family_id)
            elif effect.get("pluginName"):
                name = f"{effect['pluginName']}|{effect.get('pluginEffectName') or ''}"
                plugin_dsps[name] += 1
                plugin_families[name].add(family_id)
        for item in report.get("unknownChunks", []):
            value = int(item["id"])
            unknown_chunks[value] += int(item["count"])
            unknown_labels[value].add(str(item["hexId"]))
            unknown_families[value].add(family_id)
        for instrument in report["instruments"]:
            playlist = instrument.get("playlist")
            if playlist:
                mode = (
                    f"{playlist['playModeValue']}:{playlist['playMode']}|"
                    f"{playlist['selectionModeValue']}:{playlist['selectionMode']}"
                )
                playlist_modes[mode] += 1
                playlist_families[mode].add(family_id)
        unresolved_guids: set[str] = set()
        for controller in report["controllers"]:
            if controller.get("inputKind") == "timeline":
                timeline_controller_count += 1
                timeline_controller_families.add(family_id)
                timeline_input_guids.add(str(controller["inputParameterGuid"]))
                timeline_raw_positions.extend(
                    int(point["xRawUInt32"]) for point in controller.get("points", [])
                )
                continue
            if controller.get("inputKind") != "unknownGuid":
                continue
            guid = str(controller["inputParameterGuid"])
            unresolved_guids.add(guid)
            record = unresolved.setdefault(
                guid,
                {"guid": guid, "occurrences": 0, "familyIds": set(), "controllerGuids": set()},
            )
            record["occurrences"] += 1
            record["familyIds"].add(family_id)
            record["controllerGuids"].add(str(controller["guid"]))

        role = role_classification_evidence(report)
        role_trends.update(role["throttleVolumeEndpointTrendCounts"])
        role_sample_tokens.update(role["weakSampleNameTokenCounts"])
        role_event_tokens.update(role["eventPathTokenCounts"])
        family_rows.append(
            {
                "familyId": family_id,
                "representativeCarId": target.representative_car_id,
                "memberIds": list(target.member_ids),
                "bankPath": target.bank_relative_path,
                "status": "success",
                "cachePath": f"families/{family_id}.json",
                "bankFileVersion": report["bank"]["fileVersion"],
                "counts": report["counts"],
                "coverage": report["coverage"],
                "unknownChunks": report.get("unknownChunks", []),
                "unresolvedParameterGuids": sorted(unresolved_guids),
                "timelineControllerCount": sum(
                    controller.get("inputKind") == "timeline"
                    for controller in report["controllers"]
                ),
                "mappingIncompleteEvents": [],
                "roleClassificationEvidence": role,
            }
        )

    for family_id, failure in sorted(failures.items()):
        target = target_by_id[family_id]
        family_rows.append(
            {
                "familyId": family_id,
                "representativeCarId": target.representative_car_id,
                "memberIds": list(target.member_ids),
                "bankPath": target.bank_relative_path,
                "status": "failure",
                **{key: value for key, value in failure.items() if key not in {"familyId", "representativeCarId", "bankPath"}},
            }
        )
    pending_ids = sorted(set(target_by_id) - set(reports) - set(failures))
    for family_id in pending_ids:
        target = target_by_id[family_id]
        family_rows.append(
            {
                "familyId": family_id,
                "representativeCarId": target.representative_car_id,
                "memberIds": list(target.member_ids),
                "bankPath": target.bank_relative_path,
                "status": "pending",
            }
        )

    graph_unsupported = sorted(
        set(kind_counters["instruments"]) - GRAPH_TRAVERSED_INSTRUMENT_KINDS
    )
    unresolved_rows = [
        {
            "guid": guid,
            "occurrences": record["occurrences"],
            "familyIds": sorted(record["familyIds"]),
            "controllerGuids": sorted(record["controllerGuids"]),
        }
        for guid, record in sorted(unresolved.items())
    ]
    return {
        "schema": SUMMARY_SCHEMA,
        "catalog": {
            "catalogSha256": catalog["catalogSha256"],
            "usableCars": catalog["counts"]["usableCars"],
            "soundFamilies": catalog["counts"]["soundFamilies"],
        },
        "parser": parser_metadata,
        "status": {
            "complete": len(pending_ids) == 0,
            "allFamiliesSuccessful": len(pending_ids) == 0 and len(failures) == 0,
            "totalFamilies": len(targets),
            "successfulFamilies": len(reports),
            "failedFamilies": len(failures),
            "pendingFamilies": len(pending_ids),
            "reusedFromCache": len(reused_family_ids),
            "auditedThisRun": len(audited_this_run_ids),
            "invalidatedCache": len(invalidated_cache),
        },
        "cacheContract": {
            "reportSchema": BANK_GRAPH_AUDIT_SCHEMA,
            "requiredToolCapabilities": BANK_GRAPH_TOOL_CAPABILITIES,
            "invalidatedFamilies": [
                {"familyId": family_id, "reason": invalidated_cache[family_id]}
                for family_id in sorted(invalidated_cache)
            ],
        },
        "sourceIsolationOffsets": {
            "capability": BANK_GRAPH_TOOL_CAPABILITIES["sourceIsolationOffsets"],
            "successfulFamilyCount": len(reports),
            "waveformInstrumentBodies": waveform_isolation_offsets,
            "offsetsValidatedAgainstExactSourceBytes": waveform_isolation_offsets,
        },
        "bankFileVersions": [
            {"version": value, "hexVersion": f"0x{value:02x}", "familyCount": count}
            for value, count in sorted(versions.items())
        ],
        "featureKinds": {
            category: _kind_rows(kind_counters[category], kind_families[category])
            for category in (
                "instruments",
                "modulators",
                "effectNodes",
                "buses",
                "transitions",
                "controllerInputs",
            )
        },
        "unsupportedKinds": {
            "parserUnknownChunks": _numeric_kind_rows(
                unknown_chunks, unknown_labels, unknown_families
            ),
            "graphTraversalUnsupportedInstrumentKinds": graph_unsupported,
            "exactRendererUnimplementedCurvePointTypes": [
                {
                    "rawValue": value,
                    "occurrences": curve_types[value],
                    "familyCount": len(curve_families[value]),
                }
                for value in sorted(curve_types)
            ],
            "exactRendererUnimplementedModulatorKinds": _kind_rows(
                kind_counters["modulators"], kind_families["modulators"]
            ),
            "exactRendererUnimplementedBuiltInDspKinds": _numeric_kind_rows(
                dsp_values, dsp_labels, dsp_families
            ),
            "exactRendererUnimplementedPluginDspKinds": _kind_rows(
                plugin_dsps, plugin_families
            ),
            "exactRendererUnimplementedEffectNodeKinds": _kind_rows(
                kind_counters["effectNodes"], kind_families["effectNodes"]
            ),
            "exactRendererUnimplementedPlaylistModes": _kind_rows(
                playlist_modes, playlist_families
            ),
            "exactRendererUnimplementedTransitionKinds": _kind_rows(
                kind_counters["transitions"], kind_families["transitions"]
            ),
            "exactRendererUnimplementedControllerInputKinds": _kind_rows(
                kind_counters["controllerInputs"], kind_families["controllerInputs"]
            ),
        },
        "unresolvedParameterGuids": unresolved_rows,
        "timelineControllerEvidence": {
            "controllers": timeline_controller_count,
            "familyCount": len(timeline_controller_families),
            "distinctTimelineGuids": len(timeline_input_guids),
            "pointCount": len(timeline_raw_positions),
            "rawPositionMinimum": min(timeline_raw_positions) if timeline_raw_positions else None,
            "rawPositionMaximum": max(timeline_raw_positions) if timeline_raw_positions else None,
            "xEncoding": "uint32BitsStoredInLegacyCurveXField",
        },
        "mappingIncompleteFamilies": [
            failure
            for failure in sorted(failures.values(), key=lambda item: item["familyId"])
            if failure.get("mappingIncompleteEvents")
        ],
        "roleClassificationEvidence": {
            "status": "evidenceOnlyNotRoleClassification",
            "roleIsolationProven": False,
            "throttleVolumeEndpointTrendCounts": dict(sorted(role_trends.items())),
            "weakSampleNameTokenCounts": dict(sorted(role_sample_tokens.items())),
            "eventPathTokenCounts": dict(sorted(role_event_tokens.items())),
        },
        "failures": [failures[key] for key in sorted(failures)],
        "families": sorted(family_rows, key=lambda item: item["familyId"]),
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _git_revision(path: Path) -> str | None:
    completed = subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        check=False,
    )
    return completed.stdout.strip() if completed.returncode == 0 else None


def parser_metadata(parser_root: Path) -> dict[str, Any]:
    patch_root = PROJECT_ROOT / "tools" / "fmod_bank_graph_audit"
    return {
        "fmodBankParserRevision": _git_revision(parser_root),
        "fmod5SharpRevision": _git_revision(parser_root / "Fmod5Sharp"),
        "compatibilityPatchSha256": {
            "FModBankParser": _sha256(patch_root / "FModBankParser-ac108-net8.patch"),
            "Fmod5Sharp": _sha256(patch_root / "Fmod5Sharp-net8.patch"),
        },
        "runtimeOracleApiVersion": "0x00010812",
        "runtimeOracleOutputType": "NOSOUND_NRT",
        "graphReportSchema": BANK_GRAPH_AUDIT_SCHEMA,
        "toolCapabilities": BANK_GRAPH_TOOL_CAPABILITIES,
        "releaseGate": "unchangedAuditOnly",
    }


def run_catalog_audit(
    *,
    assetto_root: Path,
    catalog_path: Path = DEFAULT_CATALOG,
    output_root: Path = DEFAULT_OUTPUT_ROOT,
    parser_root: Path = DEFAULT_PARSER_ROOT,
    force: bool = False,
    family_ids: set[str] | None = None,
    max_new_families: int | None = None,
    progress: Callable[[str], None] | None = None,
) -> dict[str, Any]:
    catalog = _load_catalog(catalog_path.resolve(strict=True))
    targets = catalog_family_targets(catalog, assetto_root.resolve(strict=True))
    output_root = output_root.resolve()
    family_root = output_root / "families"
    summary_path = output_root / "summary.json"
    reports: dict[str, dict[str, Any]] = {}
    failures: dict[str, dict[str, Any]] = {}
    reused: set[str] = set()
    audited: set[str] = set()
    invalidated_cache: dict[str, str] = {}

    for target in targets:
        cache = family_root / f"{target.family_id}.json"
        if force or not cache.is_file():
            continue
        try:
            report = json.loads(cache.read_text(encoding="utf-8"))
            validate_bank_graph_report(
                report,
                expected_bank_sha256=target.family_id,
                source_bank_path=target.bank_path,
            )
            reports[target.family_id] = report
            reused.add(target.family_id)
        except (OSError, ValueError, json.JSONDecodeError, BankGraphAuditError) as exc:
            invalidated_cache[target.family_id] = f"{type(exc).__name__}: {exc}"
            if progress:
                progress(
                    f"invalidate cache {target.representative_car_id} "
                    f"{target.family_id[:12]}: {exc}"
                )
            continue

    candidates = [
        target
        for target in targets
        if target.family_id not in reports
        and (family_ids is None or target.family_id in family_ids)
    ]
    if max_new_families is not None:
        candidates = candidates[:max_new_families]

    metadata = parser_metadata(parser_root.resolve())
    if candidates:
        build_static_auditor(parser_root)
        with SilentFmodBankProbe(assetto_root) as probe:
            for index, target in enumerate(candidates, 1):
                if progress:
                    progress(
                        f"[{index}/{len(candidates)}] audit {target.representative_car_id} "
                        f"{target.family_id[:12]}"
                    )
                try:
                    report = audit_bank_graph(
                        target.bank_path,
                        assetto_root=assetto_root,
                        parser_root=parser_root,
                        probe=probe,
                        build=False,
                    )
                    validate_bank_graph_report(
                        report,
                        expected_bank_sha256=target.family_id,
                        source_bank_path=target.bank_path,
                    )
                    _write_atomic(family_root / f"{target.family_id}.json", report)
                    reports[target.family_id] = report
                    audited.add(target.family_id)
                    failures.pop(target.family_id, None)
                except Exception as exc:
                    failure = _failure_from_exception(target, exc)
                    failures[target.family_id] = failure
                    if progress:
                        progress(f"  FAILED {type(exc).__name__}: {exc}")
                summary = build_summary(
                    catalog=catalog,
                    targets=targets,
                    reports=reports,
                    failures=failures,
                    reused_family_ids=reused,
                    audited_this_run_ids=audited,
                    parser_metadata=metadata,
                    invalidated_cache=invalidated_cache,
                )
                _write_atomic(summary_path, summary)

    summary = build_summary(
        catalog=catalog,
        targets=targets,
        reports=reports,
        failures=failures,
        reused_family_ids=reused,
        audited_this_run_ids=audited,
        parser_metadata=metadata,
        invalidated_cache=invalidated_cache,
    )
    _write_atomic(summary_path, summary)
    return summary


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--parser-root", type=Path, default=DEFAULT_PARSER_ROOT)
    parser.add_argument("--force", action="store_true", help="ignore successful family caches")
    parser.add_argument(
        "--family",
        action="append",
        default=[],
        help="audit only a full family SHA-256 this run; repeatable",
    )
    parser.add_argument("--max-new-families", type=int, help="bounded resume/testing run")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    root = find_assetto_root(args.assetto_root)
    summary = run_catalog_audit(
        assetto_root=root,
        catalog_path=args.catalog,
        output_root=args.output_root,
        parser_root=args.parser_root,
        force=args.force,
        family_ids=set(args.family) or None,
        max_new_families=args.max_new_families,
        progress=lambda message: print(message, file=sys.stderr, flush=True),
    )
    status = summary["status"]
    print(json.dumps(status, sort_keys=True, separators=(",", ":")))
    return 0 if status["allFamiliesSuccessful"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
