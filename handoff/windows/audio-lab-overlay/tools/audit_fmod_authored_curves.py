"""Audit bounded FMOD-authored continuous-source curve compilation.

The audit consumes only bank graph v3 structure and the filename-independent
source-role classifier.  It intentionally reports unsupported semantics as a
release backlog instead of guessing at them.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.fmod_authored_curves import (
    AUTHORED_CURVE_SCHEMA,
    FmodAuthoredCurveError,
    derive_manifest_source_curves,
    derive_windowed_capture_fallback,
)
from sim.fmod_graph_roles import classify_bank_graph_sources


AUDIT_SCHEMA = "ac-fmod-authored-curve-catalog-audit-v1"
DEFAULT_INPUT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
_SUPPORTED_SUFFIXES = frozenset(("engine_ext", "engine_int", "transmission"))


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _source_chain(
    report: dict[str, Any], source_guid: str
) -> list[dict[str, Any]]:
    instruments = {
        _guid(item.get("guid")): item
        for item in report.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    parents: dict[str, list[str]] = {}
    for parent_guid, instrument in instruments.items():
        for child in instrument.get("childInstruments", []):
            child_guid = _guid(child.get("guid")) if isinstance(child, dict) else _guid(child)
            if child_guid:
                parents.setdefault(child_guid, []).append(parent_guid)
    chain: list[dict[str, Any]] = []
    current = source_guid
    visited: set[str] = set()
    while current and current not in visited and current in instruments:
        visited.add(current)
        chain.append(instruments[current])
        options = sorted(set(parents.get(current, [])))
        current = options[0] if len(options) == 1 else ""
    return chain


def _peak_pre_routing_db(report: dict[str, Any], source_guid: str) -> float:
    chain = _source_chain(report, source_guid)
    owners: set[str] = set()
    referenced: set[str] = set()
    base = 0.0
    for instrument in chain:
        owners.add(_guid(instrument.get("guid")))
        properties = instrument.get("baseProperties") or {}
        owners.add(_guid(properties.get("routableGuid")))
        base += float(properties.get("volumeDb", 0.0))
        referenced.update(_guid(item) for item in instrument.get("controllerGuids", []))
    peak = base
    for controller in report.get("controllers", []):
        if (
            not isinstance(controller, dict)
            or _guid(controller.get("guid")) not in referenced
            or _guid(controller.get("propertyOwnerGuid")) not in owners
            or controller.get("propertyIndex") != 0
        ):
            continue
        values = [
            float(point["y"])
            for point in controller.get("points", [])
            if isinstance(point, dict) and isinstance(point.get("y"), (int, float))
        ]
        if values:
            peak += max(values)
    return peak


def _property_one_fallback(
    report: dict[str, Any],
    row: dict[str, Any],
    eligible_rows: list[dict[str, Any]],
) -> dict[str, Any]:
    source_guid = _guid(row.get("sourceGuid"))
    chain = _source_chain(report, source_guid)
    owners: set[str] = set()
    referenced: set[str] = set()
    for instrument in chain:
        owners.add(_guid(instrument.get("guid")))
        owners.add(_guid((instrument.get("baseProperties") or {}).get("routableGuid")))
        referenced.update(_guid(item) for item in instrument.get("controllerGuids", []))
    property_one: list[dict[str, Any]] = []
    rpm_seeds: set[float] = set()
    throttle_candidates: list[tuple[float, float]] = []
    for controller in report.get("controllers", []):
        if not isinstance(controller, dict) or _guid(controller.get("guid")) not in referenced:
            continue
        if _guid(controller.get("propertyOwnerGuid")) not in owners:
            continue
        parameter = str(controller.get("inputParameterName") or "").casefold()
        points = [item for item in controller.get("points", []) if isinstance(item, dict)]
        if controller.get("propertyIndex") == 1:
            property_one.append(
                {
                    "controllerGuid": _guid(controller.get("guid")),
                    "parameter": parameter,
                    "points": [
                        {
                            "x": float(item["x"]),
                            "rawValue": float(item["y"]),
                            "shape": float(item.get("shape", 0.0)),
                            "type": int(item.get("type", 0)),
                        }
                        for item in points
                    ],
                }
            )
            if parameter == "rpms":
                rpm_seeds.update(float(item["x"]) for item in points)
        elif controller.get("propertyIndex") == 0 and parameter == "rpms":
            rpm_seeds.update(float(item["x"]) for item in points)
        elif controller.get("propertyIndex") == 0 and parameter == "throttle":
            throttle_candidates.extend(
                (float(item["y"]), float(item["x"])) for item in points
            )
    event_paths = set(row.get("eventPaths") or [])
    for event in report.get("events", []):
        if not isinstance(event, dict) or str(event.get("path")) not in event_paths:
            continue
        for placement in event.get("parameterPlacements", []):
            if (
                isinstance(placement, dict)
                and _guid(placement.get("instrumentGuid")) in {
                    _guid(item.get("guid")) for item in chain
                }
                and str(placement.get("parameterName") or "").casefold() == "rpms"
            ):
                rpm_seeds.update((float(placement["start"]), float(placement["end"])))

    source_peak = _peak_pre_routing_db(report, source_guid)
    peer_peaks = [
        _peak_pre_routing_db(report, _guid(item.get("sourceGuid")))
        for item in eligible_rows
        if set(item.get("eventPaths") or []) & event_paths
    ]
    loudest_peer = max(peer_peaks, default=source_peak)
    raw_values = [
        point["rawValue"]
        for controller in property_one
        for point in controller["points"]
    ]
    return {
        "kind": "targetOnlyAdaptiveRpmWindows",
        "reason": "propertyIndex1PitchAutomationHasNoManifestPitchCurve",
        "sourceGuid": source_guid,
        "eventPaths": sorted(event_paths),
        "captureThrottle": (
            max(throttle_candidates)[1] if throttle_candidates else 0.0
        ),
        "seedRpmValues": sorted(rpm_seeds),
        "propertyOneControllers": property_one,
        "authoredRawPitchSpan": (
            max(raw_values) - min(raw_values) if raw_values else 0.0
        ),
        "adaptiveOracleGate": {
            "measurement": "targetOnlyLongWindowFundamentalAndRms",
            "splitEachWindowUntilPitchErrorCentsAtMost": 5.0,
            "splitEachWindowUntilGainErrorDbAtMost": 0.25,
            "maximumWindows": 64,
            "onUnstablePitchEstimate": "failClosedDoNotReleaseSource",
        },
        "audibilityEstimate": {
            "kind": "authoredPointUpperBoundBeforeRoutingNotPcmLoudness",
            "peakPreRoutingDb": source_peak,
            "relativeToLoudestEligibleEventSourceDb": source_peak - loudest_peer,
            "peakPreRoutingLinearAmplitude": 10.0 ** (source_peak / 20.0),
        },
    }


def _candidate(row: dict[str, Any]) -> bool:
    suffixes = row.get("eventSuffixes")
    return (
        row.get("policy") == "allowCandidate"
        and row.get("lifetime") == "continuous"
        and isinstance(suffixes, list)
        and bool({str(item).casefold() for item in suffixes} & _SUPPORTED_SUFFIXES)
    )


def audit_catalog(root: Path) -> dict[str, Any]:
    """Compile every eligible source and return a deterministic support audit."""

    resolved = root.resolve()
    family_root = resolved / "families" if (resolved / "families").is_dir() else resolved
    paths = sorted(family_root.glob("*.json"))
    if not paths:
        raise ValueError(f"no graph reports found under {resolved}")

    candidate_counts: Counter[str] = Counter()
    supported_counts: Counter[str] = Counter()
    failure_counts: Counter[str] = Counter()
    modulator_counts: Counter[str] = Counter()
    family_candidate_counts: Counter[str] = Counter()
    family_supported_counts: Counter[str] = Counter()
    supported: list[dict[str, Any]] = []
    backlog: list[dict[str, Any]] = []
    windowed_fallbacks: list[dict[str, Any]] = []
    unsupported_modulators: list[dict[str, Any]] = []
    unattributed_dsp: dict[tuple[str, str], dict[str, Any]] = {}
    curve_points = {"rpm": [], "gain": []}
    maximum_observed_error = {"rpm": 0.0, "gain": 0.0}

    for path in paths:
        report = json.loads(path.read_text(encoding="utf-8"))
        family_id = str((report.get("bank") or {}).get("sha256") or path.stem)
        classified = classify_bank_graph_sources(report)
        eligible_rows = [
            row
            for row in classified.get("sources", [])
            if isinstance(row, dict) and _candidate(row)
        ]
        for row in eligible_rows:
            role = str(row.get("role") or "missing")
            category = "transmission" if role == "TRANSMISSION" else "engine"
            candidate_counts[category] += 1
            family_candidate_counts[family_id] += 1
            try:
                result = derive_manifest_source_curves(report, row)
            except FmodAuthoredCurveError as exc:
                failure_counts[exc.code] += 1
                failure = {
                    "familyId": family_id,
                    "sourceGuid": str(row.get("sourceGuid") or ""),
                    "eventPaths": list(row.get("eventPaths") or []),
                    "role": role,
                    "code": exc.code,
                    "detail": exc.detail,
                }
                if exc.code == "unsupportedPropertyIndex" and "property 1" in exc.detail:
                    public_plan = derive_windowed_capture_fallback(report, row)
                    audit_only = _property_one_fallback(report, row, eligible_rows)
                    fallback = {
                        "familyId": family_id,
                        **public_plan,
                        "audibilityEstimate": audit_only["audibilityEstimate"],
                    }
                    failure["fallbackStrategy"] = fallback["kind"]
                    windowed_fallbacks.append(fallback)
                backlog.append(failure)
                continue

            supported_counts[category] += 1
            family_supported_counts[family_id] += 1
            curve_points["rpm"].append(len(result["rpmCurve"]))
            curve_points["gain"].append(len(result["gainCurve"]))
            fidelity = result["fidelity"]
            maximum_observed_error["rpm"] = max(
                maximum_observed_error["rpm"],
                float(fidelity["rpmDenseGridMaxObservedLinearError"]),
            )
            maximum_observed_error["gain"] = max(
                maximum_observed_error["gain"],
                float(fidelity["gainDenseGridMaxObservedLinearError"]),
            )
            supported.append(
                {
                    "familyId": family_id,
                    "sourceGuid": result["sourceGuid"],
                    "eventPath": result["eventPath"],
                    "role": result["role"],
                    "nativeSpeedParameter": result["nativeSpeedParameter"],
                    "autoPitchReferenceRpm": result["autoPitchReferenceRpm"],
                    "captureRootRpm": result["captureRootRpm"],
                    "captureThrottle": result["captureThrottle"],
                    "rpmCurvePointCount": len(result["rpmCurve"]),
                    "gainCurvePointCount": len(result["gainCurve"]),
                    "requiresFinalRuntimeOracle": not fidelity["exactnessClaim"],
                }
            )
            for item in result["unsupported"]["sourceModulators"]:
                modulator_type = str(item.get("type") or "missing")
                modulator_counts[modulator_type] += 1
                unsupported_modulators.append(
                    {
                        "familyId": family_id,
                        "sourceGuid": result["sourceGuid"],
                        **item,
                    }
                )
            for item in result["unsupported"][
                "bankWideUnattributedRpmThrottleDspAutomation"
            ]:
                key = (family_id, str(item.get("controllerGuid") or ""))
                unattributed_dsp[key] = {"familyId": family_id, **item}

    candidate_total = sum(candidate_counts.values())
    supported_total = sum(supported_counts.values())
    all_families = {str(path.stem) for path in paths}
    candidate_families = set(family_candidate_counts)
    supported_families = set(family_supported_counts)
    fully_supported_families = {
        family_id
        for family_id, count in family_candidate_counts.items()
        if family_supported_counts[family_id] == count
    }

    def point_stats(values: list[int]) -> dict[str, int]:
        return {
            "minimum": min(values, default=0),
            "maximum": max(values, default=0),
            "total": sum(values),
        }

    return {
        "schema": AUDIT_SCHEMA,
        "inputSchema": "ac-fmod-bank-graph-audit-v3",
        "curveSchema": AUTHORED_CURVE_SCHEMA,
        "basis": {
            "usesSampleNames": False,
            "unknownPolicy": "failClosed",
            "scope": "allowed continuous engine_int, engine_ext, and transmission sources",
            "transmissionAxis": "shaftRpm=drivetrain_speedRadiansPerSecond*60/(2*pi)",
            "exactnessClaim": False,
            "requiredFinalGate": "targetOnlyFmod108RuntimeOracleComparison",
        },
        "counts": {
            "catalogFamilies": len(paths),
            "candidateFamilies": len(candidate_families),
            "familiesWithAtLeastOneSupportedSource": len(supported_families),
            "fullySupportedCandidateFamilies": len(fully_supported_families),
            "partiallySupportedCandidateFamilies": len(supported_families - fully_supported_families),
            "unsupportedCandidateFamilies": len(candidate_families - supported_families),
            "candidateSources": candidate_total,
            "supportedSources": supported_total,
            "directlySupportedSources": supported_total,
            "unsupportedSources": candidate_total - supported_total,
            "sourcesWithWindowedCaptureFallback": len(windowed_fallbacks),
            "sourcesWithDirectOrWindowedReleasePlan": supported_total
            + len(windowed_fallbacks),
            "sourcesWithoutReleasePlan": candidate_total
            - supported_total
            - len(windowed_fallbacks),
            "sourcesRequiringFinalRuntimeOracle": supported_total
            + len(windowed_fallbacks),
            "unsupportedSourceModulators": len(unsupported_modulators),
            "bankWideUnattributedDspAutomationControllers": len(unattributed_dsp),
        },
        "candidateSourceCounts": dict(sorted(candidate_counts.items())),
        "supportedSourceCounts": dict(sorted(supported_counts.items())),
        "failureCodeCounts": dict(sorted(failure_counts.items())),
        "unsupportedModulatorTypeCounts": dict(sorted(modulator_counts.items())),
        "curvePointStats": {
            "rpm": point_stats(curve_points["rpm"]),
            "gain": point_stats(curve_points["gain"]),
        },
        "maximumObservedNormalizedLinearizationError": maximum_observed_error,
        "supportedSources": sorted(
            supported, key=lambda item: (item["familyId"], item["eventPath"], item["sourceGuid"])
        ),
        "failureBacklog": sorted(
            backlog, key=lambda item: (item["code"], item["familyId"], item["sourceGuid"])
        ),
        "windowedCaptureFallbacks": sorted(
            windowed_fallbacks,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
        "unsupportedSourceModulators": sorted(
            unsupported_modulators,
            key=lambda item: (item["familyId"], item["sourceGuid"], item["guid"]),
        ),
        "bankWideUnattributedDspAutomation": sorted(
            unattributed_dsp.values(),
            key=lambda item: (item["familyId"], item["controllerGuid"]),
        ),
    }


def _write_atomic(path: Path, value: object) -> None:
    resolved = path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    temporary = resolved.with_suffix(resolved.suffix + ".tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    temporary.replace(resolved)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = audit_catalog(args.input)
        if args.output is None:
            sys.stdout.buffer.write(canonical_json_bytes(result) + b"\n")
        else:
            _write_atomic(args.output, result)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
