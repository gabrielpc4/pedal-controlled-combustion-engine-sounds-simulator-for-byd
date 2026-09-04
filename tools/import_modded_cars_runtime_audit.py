#!/usr/bin/env python3
"""Turn debug-only Android traces into a reviewable modded-bank playback audit.

The static inventory deliberately describes authoring only. This importer joins its immutable
bank SHA/event paths with a captured Android scenario, but never treats a 0% momentary FMOD
audibility value as proof that an encoded source is silent. A source is called audible only when
a VoiceState snapshot observes positive audibility; zero-only sources remain evidence for a
targeted follow-up rather than a reason to alter the authored bank mix.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
STATIC_INVENTORY = ROOT / "docs" / "modded-cars-audio-inventory.json"
JSON_OUTPUT = ROOT / "docs" / "modded-cars-runtime-audit.json"
MARKDOWN_OUTPUT = ROOT / "docs" / "modded-cars-runtime-audit.md"


def read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected an object")
    return value


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))


def positive_float(value: str | None) -> float:
    try:
        return max(0.0, float(value or 0.0))
    except ValueError:
        return 0.0


def find_trace(root: Path, profile_id: str) -> Path | None:
    direct = root / profile_id
    candidates = [direct] if direct.is_dir() else []
    candidates.extend(path for path in root.glob(f"**/{profile_id}/*") if path.is_dir())
    candidates.extend(path for path in root.glob("**/capture-*") if path.is_dir())
    compatible: list[Path] = []
    for path in candidates:
        metadata = path / "metadata.json"
        if not metadata.is_file():
            continue
        if read_json(metadata).get("profileId") == profile_id:
            compatible.append(path)
    return max(compatible, key=lambda path: path.stat().st_mtime) if compatible else None


def car_catalog_paths(rows: Iterable[dict[str, str]]) -> set[str]:
    return {
        row["eventPath"]
        for row in rows
        if row.get("kind") == "BANK_EVENT_CATALOG" and row.get("eventPath", "").startswith("event:/cars/")
    }


def source_is_distance_muted(
    source: dict[str, Any],
    listener_distance: float,
) -> bool:
    """Return true only for a source whose own authored distance curve mutes it here.

    This is intentionally stricter than a 0% voice meter. It requires a source volume
    automation for FMOD's automatic distance parameter and an explicit -42 dB-or-lower knot at
    or before the active listener distance. That lets the report call out true geometry-gated
    silence without claiming silent PCM or hiding a lifecycle error.
    """

    automations = source.get("authoredFact", {}).get("conditions", {}).get("automation", [])
    for automation in automations:
        if (
            automation.get("inputParameterName", "").lower() != "distance"
            or automation.get("propertyLabel") != "volume automation (dB)"
        ):
            continue
        curve = automation.get("curve", [])
        previous = None
        for point in sorted(curve, key=lambda point: float(point.get("x", 0.0))):
            if float(point.get("x", 0.0)) <= listener_distance:
                previous = point
            else:
                break
        if previous is not None and float(previous.get("y", 0.0)) <= -41.9:
            return True
    return False


def source_is_muted_at_parameter(
    source: dict[str, Any],
    parameter_name: str,
    value: float,
) -> bool:
    """Return true only when a source's own volume curve reaches its mute floor here."""

    automations = source.get("authoredFact", {}).get("conditions", {}).get("automation", [])
    for automation in automations:
        if (
            automation.get("inputParameterName", "").lower() != parameter_name
            or automation.get("propertyLabel") != "volume automation (dB)"
        ):
            continue
        previous = None
        for point in sorted(automation.get("curve", []), key=lambda point: float(point.get("x", 0.0))):
            if float(point.get("x", 0.0)) <= value:
                previous = point
            else:
                break
        if previous is not None and float(previous.get("y", 0.0)) <= -41.9:
            return True
    return False


def event_is_muted_by_app_policy(event: dict[str, Any]) -> bool:
    """Recognise a deliberate app policy that lands every source on an authored mute knot."""

    sources = event.get("sources", [])
    policy_parameters = event.get("appPolicy", {}).get("parameters", [])
    throttle = next(
        (
            parameter.get("value")
            for parameter in policy_parameters
            if parameter.get("name") == "throttle" and isinstance(parameter.get("value"), (float, int))
        ),
        None,
    )
    return bool(sources) and throttle is not None and all(
        source_is_muted_at_parameter(source, "throttle", float(throttle))
        for source in sources
    )


def event_is_geometry_gated_for_all_listeners(static_car: dict[str, Any], event: dict[str, Any]) -> bool:
    """Recognise an authored, all-listener distance mute for a continuous event.

    The audit drives both cabin and exterior. When every declared source is explicitly muted by
    its own distance automation at both documented listener distances, no FMOD Core voice is the
    expected outcome. This does not generalise a one-perspective mute or a transient 0% meter.
    """

    sources = event.get("sources", [])
    geometry = static_car.get("physicsSummary", {}).get("spatialGeometry", {})
    distances = (
        geometry.get("cabinEngineDistanceM"),
        geometry.get("exteriorEngineDistanceM"),
    )
    if not sources or any(distance is None for distance in distances):
        return False
    return all(
        source_is_distance_muted(source, float(distance))
        for source in sources
        for distance in distances
    )


def audit_car(static_car: dict[str, Any], trace_root: Path) -> dict[str, Any]:
    profile_id = static_car["id"]
    trace = find_trace(trace_root, profile_id)
    expected_paths = {event["identity"]["path"] for event in static_car["events"]}
    if trace is None:
        return {
            "id": profile_id,
            "displayName": static_car["displayName"],
            "status": "notCaptured",
            "reason": "No trace whose metadata profileId matches this car was supplied.",
        }

    metadata = read_json(trace / "metadata.json")
    native = read_rows(trace / "native.csv")
    catalog = read_rows(trace / "bank_event_catalog.csv")
    observed_paths = car_catalog_paths(catalog)
    mismatched_sha = metadata.get("bankSha256") != static_car["source"]["bankSha256"]
    starts = Counter(row["eventName"] for row in native if row.get("kind") == "EVENT_START")
    stops = Counter(row["eventName"] for row in native if row.get("kind") == "EVENT_STOP")
    shifts = [row for row in native if row.get("kind") == "SHIFT_DISPATCH"]
    parameter_failures = sorted({
        (
            row.get("eventName", ""),
            row.get("rawSoundName", ""),
            row.get("fmodResult", ""),
        )
        for row in native
        if row.get("kind") == "PARAMETER_WRITE_FAILURE"
    })
    voices: dict[tuple[str, str], dict[str, Any]] = defaultdict(
        lambda: {"snapshots": 0, "voiceStarts": 0, "voiceStops": 0, "maxAudibility": 0.0, "maxRouteGain": 0.0, "virtualSnapshots": 0},
    )
    for row in native:
        kind = row.get("kind")
        if kind not in {"VOICE_STATE", "VOICE_PLAYED", "VOICE_STOPPED"}:
            continue
        event_name = row.get("eventName", "")
        raw_sound = row.get("rawSoundName", "")
        if not event_name or not raw_sound:
            continue
        voice = voices[(event_name, raw_sound)]
        if kind == "VOICE_STATE":
            voice["snapshots"] += 1
            voice["maxAudibility"] = max(voice["maxAudibility"], positive_float(row.get("audibility")))
            voice["maxRouteGain"] = max(voice["maxRouteGain"], positive_float(row.get("routeGain")))
            voice["virtualSnapshots"] += int(row.get("virtualVoiceCount", "0") or 0) > 0
        elif kind == "VOICE_PLAYED":
            voice["voiceStarts"] += 1
        else:
            voice["voiceStops"] += 1

    source_observations = [
        {
            "event": event,
            "rawSound": raw,
            "voiceStarts": details["voiceStarts"],
            "voiceStops": details["voiceStops"],
            "stateSnapshots": details["snapshots"],
            "maxAudibility": round(details["maxAudibility"], 6),
            "maxRouteGain": round(details["maxRouteGain"], 6),
            "virtualSnapshots": details["virtualSnapshots"],
            "audibilityConclusion": (
                "audibleObserved" if details["maxAudibility"] > 0.0
                else "zeroOnlyInScenarioNotProofOfSilentPCM"
            ),
        }
        for (event, raw), details in sorted(voices.items())
    ]
    event_observations = []
    continuous_missing: list[str] = []
    continuous_without_voices: list[str] = []
    for event in static_car["events"]:
        suffix = event["identity"]["suffix"]
        path = event["identity"]["path"]
        classification = event["appPolicy"]["classification"]
        start_count = starts[suffix]
        source_observed = any(observed_event == suffix for observed_event, _ in voices)
        source_count = event["authoredFact"].get("sourceCount", 0)
        geometry_gated = event_is_geometry_gated_for_all_listeners(static_car, event)
        app_policy_muted = event_is_muted_by_app_policy(event)
        event_observations.append({
            "path": path,
            "suffix": suffix,
            "appClassification": classification,
            "eventStarts": start_count,
            "eventStops": stops[suffix],
            "observedInCatalog": path in observed_paths,
            "observation": "started" if start_count else "notStartedInThisScenario",
        })
        # Cabin/exterior engine and supported transmission are supposed to be opened by the
        # deterministic scenario. Other effects depend on bank gates and are informative only.
        if (
            suffix in {"engine_int", "engine_ext", "transmission", "transmission_ext"}
            and source_count > 0
            and start_count == 0
            and not source_observed
            and not geometry_gated
            and not app_policy_muted
        ):
            continuous_missing.append(path)
        # The scenario traverses both listeners and enough drivetrain range for every authored
        # continuous graph.  An opened continuous event with declared sources but no Core voice
        # is stronger evidence than a 0% meter: it means FMOD never instantiated a source at all.
        if (
            suffix in {"engine_int", "engine_ext", "transmission", "transmission_ext"}
            and start_count > 0
            and source_count > 0
            and not source_observed
            and not geometry_gated
            and not app_policy_muted
        ):
            continuous_without_voices.append(path)
        if geometry_gated and not source_observed:
            event_observations[-1]["observation"] = "notInstantiatedBecauseAuthoredDistanceGeometry"
        elif app_policy_muted and not source_observed:
            event_observations[-1]["observation"] = "notInstantiatedBecauseCurrentAppThrottlePolicy"

    catalog_missing = sorted(expected_paths - observed_paths)
    catalog_unexpected = sorted(observed_paths - expected_paths)
    errors: list[str] = []
    if metadata.get("profileId") != profile_id:
        errors.append("trace profile does not match the expected profile")
    if mismatched_sha:
        errors.append("installed bank SHA-256 does not match the static source inventory")
    if catalog_missing:
        errors.append("installed bank catalog is missing authored car event paths")
    if continuous_missing:
        errors.append("continuous engine/transmission event did not start during its applicable scenario")
    if continuous_without_voices:
        errors.append("continuous event started but FMOD never instantiated an authored source")
    if parameter_failures:
        errors.append("FMOD rejected one or more authored parameter writes")
    return {
        "id": profile_id,
        "displayName": static_car["displayName"],
        "status": "needsInvestigation" if errors else "captured",
        "trace": str(trace),
        "bankSha256": metadata.get("bankSha256"),
        "simulationRecords": metadata.get("simulationRecords"),
        "audioRecords": metadata.get("audioRecords"),
        "nativeRecords": metadata.get("nativeRecords"),
        "catalogMissingPaths": catalog_missing,
        "catalogUnexpectedPaths": catalog_unexpected,
        "continuousEventsMissingFromScenario": continuous_missing,
        "continuousEventsWithoutVoices": continuous_without_voices,
        "parameterWriteFailures": [
            {"event": event, "parameter": parameter, "fmodResult": result}
            for event, parameter, result in parameter_failures
        ],
        "shiftDispatchCount": len(shifts),
        "eventObservations": event_observations,
        "sourceObservations": source_observations,
        "errors": errors,
    }


def render_markdown(audits: list[dict[str, Any]]) -> str:
    captured = sum(audit["status"] != "notCaptured" for audit in audits)
    needs_investigation = sum(audit["status"] == "needsInvestigation" for audit in audits)
    lines = [
        "# Modded Cars Android Runtime Audit",
        "",
        "This is runtime evidence paired with `modded-cars-audio-inventory.md`. It does not replace the authored-bank inventory.",
        "",
        "## Interpretation rules",
        "",
        "- `audibleObserved` means an FMOD `VOICE_STATE` snapshot had positive audibility at least once.",
        "- `zeroOnlyInScenarioNotProofOfSilentPCM` means the source was instantiated but never had positive audibility in this scenario. FMOD automation, route gain, perspective and virtualisation can all cause that; it is explicitly not a claim that the encoded audio is silent.",
        "- `notInstantiatedBecauseAuthoredDistanceGeometry` means every declared source is explicitly at its authored -42 dB distance knot for both documented listener positions. It is expected geometry, not a missing voice.",
        "- `notInstantiatedBecauseCurrentAppThrottlePolicy` means the app's documented full-load throttle policy puts every declared source at its own authored mute knot. It documents a policy consequence, not a corrupt source.",
        "- Effects such as backfire, limiter and gear are scenario-sensitive. A missing start is evidence to investigate only when the continuous events or immutable bank identity also fail.",
        "",
        f"Captured {captured}/{len(audits)} profiles. {needs_investigation} need targeted follow-up.",
    ]
    for audit in audits:
        lines.extend(["", f"## {audit['displayName']} (`{audit['id']}`)", ""])
        if audit["status"] == "notCaptured":
            lines.append(f"- Not captured: {audit['reason']}")
            continue
        lines.append(f"- Status: `{audit['status']}`. Trace bank SHA-256: `{audit['bankSha256']}`.")
        lines.append(
            f"- Trace volume: {audit['simulationRecords']} simulation frames, {audit['audioRecords']} audio-control frames, {audit['nativeRecords']} native lifecycle records, {audit['shiftDispatchCount']} shift dispatches."
        )
        if audit["errors"]:
            lines.append("- Errors: " + "; ".join(audit["errors"]) + ".")
        if audit["continuousEventsWithoutVoices"]:
            lines.append(
                "- Continuous event(s) opened without any source being instantiated: " +
                ", ".join(f"`{path}`" for path in audit["continuousEventsWithoutVoices"]) + "."
            )
        if audit["parameterWriteFailures"]:
            lines.append(
                "- FMOD parameter write failure(s): " +
                ", ".join(
                    f"`{failure['event']}.{failure['parameter']}` (result {failure['fmodResult']})"
                    for failure in audit["parameterWriteFailures"]
                ) + "."
            )
        if audit["catalogUnexpectedPaths"]:
            lines.append("- Shared/global catalog paths are omitted here; unexpected car paths: " + ", ".join(f"`{path}`" for path in audit["catalogUnexpectedPaths"]) + ".")
        lines.extend(["", "| Event | Starts | Result |", "| --- | ---: | --- |"])
        for event in audit["eventObservations"]:
            lines.append(f"| `{event['path']}` | {event['eventStarts']} | `{event['observation']}` |")
        lines.extend(["", "| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |", "| --- | --- | ---: | ---: | --- |"])
        for source in audit["sourceObservations"]:
            lines.append(
                f"| `{source['rawSound']}` | `{source['event']}` | {source['voiceStarts']} | {source['maxAudibility']:.3f} | `{source['audibilityConclusion']}` |"
            )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace_root", type=Path, help="directory containing pulled capture-* folders")
    parser.add_argument("--static-inventory", type=Path, default=STATIC_INVENTORY)
    parser.add_argument("--json-output", type=Path, default=JSON_OUTPUT)
    parser.add_argument("--markdown-output", type=Path, default=MARKDOWN_OUTPUT)
    args = parser.parse_args()
    static = read_json(args.static_inventory)
    cars = static.get("cars")
    if not isinstance(cars, list) or len(cars) != 33:
        raise ValueError("static inventory must contain at least one modded car")
    audits = [audit_car(car, args.trace_root) for car in sorted(cars, key=lambda car: car["id"])]
    document = {
        "schema": "byd-modded-cars-runtime-audit-v1",
        "staticInventorySchema": static.get("schema"),
        "audits": audits,
    }
    args.json_output.write_text(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown_output.write_text(render_markdown(audits), encoding="utf-8")
    print(f"Imported {sum(audit['status'] != 'notCaptured' for audit in audits)}/{len(audits)} modded runtime traces.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
