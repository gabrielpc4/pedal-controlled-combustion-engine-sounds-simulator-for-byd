#!/usr/bin/env python3
"""Compile a static, source-preserving inventory for every installed modded car bank.

The original-car compiler is intentionally strict about official Assetto source paths. This
companion uses the same FMOD graph auditor, event policy table, physics exporter, and source
condition extraction, but resolves each modded bank through the GUIDs.txt shipped beside it.
It reads the supplied banks only; it never extracts or rewrites PCM.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_COMPILER = ROOT / "tools" / "generate_original_cars_audio_inventory.py"
PACK_BUILDER = ROOT / "tools" / "build_fmod_bank_packs.py"
JSON_OUTPUT = ROOT / "docs" / "modded-cars-audio-inventory.json"
MARKDOWN_OUTPUT = ROOT / "docs" / "modded-cars-audio-inventory.md"


def load_module(path: Path, name: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


inventory = load_module(ORIGINAL_COMPILER, "byd_original_inventory")
packs = load_module(PACK_BUILDER, "byd_pack_builder_modded")


def load_single_bank_guid_paths(source: Any) -> dict[str, tuple[str, ...]]:
    path = source.source_directory / "sfx" / "GUIDs.txt"
    if not path.is_file():
        raise inventory.InventoryError(f"{source.pack_id}: missing source GUIDs.txt at {path}")
    mappings: dict[str, set[str]] = {}
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        fields = line.split(None, 1)
        if len(fields) != 2:
            continue
        guid = inventory.guid_text(fields[0])
        authored_path = fields[1].strip()
        if guid and authored_path:
            mappings.setdefault(guid, set()).add(authored_path)
    return {guid: tuple(sorted(paths)) for guid, paths in sorted(mappings.items())}


def read_modded_graph(bank_path: Path) -> tuple[dict[str, Any], int]:
    """Keep a parseable partial graph as evidence instead of hiding it on audit warnings."""

    completed = subprocess.run(
        ("dotnet", str(inventory.AUDIT_DLL), str(bank_path)),
        cwd=inventory.AUDIO_LAB,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        check=False,
    )
    try:
        report = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        detail = completed.stderr.strip() or "no JSON output"
        raise inventory.InventoryError(f"{bank_path.name}: graph audit did not return JSON: {detail}") from error
    if not isinstance(report, dict):
        raise inventory.InventoryError(f"{bank_path.name}: graph audit returned a non-object")
    return report, completed.returncode


def resolve_modded_event_paths(report: dict[str, Any], source: Any) -> dict[str, Any]:
    guid_paths = load_single_bank_guid_paths(source)
    events = report.get("events")
    if not isinstance(events, list) or not events:
        raise inventory.InventoryError(f"{source.pack_id}: graph has no events")
    audio_events: list[dict[str, Any]] = []
    non_audio_event_count = 0
    prefixes: set[str] = set()
    resolved_paths: set[str] = set()
    for event in events:
        if not isinstance(event, dict):
            raise inventory.InventoryError(f"{source.pack_id}: malformed graph event")
        guid = inventory.guid_text(event.get("guid"))
        candidates = guid_paths.get(guid, ())
        event_paths = tuple(path for path in candidates if path.startswith("event:/cars/"))
        if not event_paths:
            # Studio snapshots can be authored in the same bank graph. They have no
            # playable car source and must not be misreported as a missing audio event.
            non_audio_event_count += 1
            continue
        if len(event_paths) != 1:
            raise inventory.InventoryError(
                f"{source.pack_id}: event {guid or '<missing>'} has ambiguous car paths {list(event_paths)}"
            )
        path = event_paths[0]
        if not path.startswith("event:/cars/"):
            raise inventory.InventoryError(f"{source.pack_id}: event {guid} has invalid car event path {path!r}")
        if path in resolved_paths:
            raise inventory.InventoryError(f"{source.pack_id}: duplicate event path {path!r}")
        event["path"] = path
        resolved_paths.add(path)
        prefixes.add(path.rsplit("/", 1)[0])
        audio_events.append(event)
    # A few mod banks contain auxiliary events under a differently-spelled
    # family (for example a wheel event). The app resolves each GUID/path
    # independently, so retaining that fact is safer than dropping a real event
    # by assuming a single folder-like family name.
    report["events"] = audio_events
    counts = dict(report.get("counts") or {})
    coverage = dict(report.get("coverage") or {})
    counts["events"] = len(audio_events)
    coverage["eventsWithCompleteSampleMapping"] = sum(event.get("mappingComplete") is True for event in audio_events)
    report["counts"] = counts
    report["coverage"] = coverage
    return {
        "rawGraphEvents": len(events),
        "excludedNonAudioGraphEvents": non_audio_event_count,
        "authoredEventFamilies": sorted(prefixes),
    }


def validate_modded_graph(report: Mapping[str, Any], bank_path: Path) -> None:
    """Validate immutable bank identity without rejecting a documented parser gap."""

    bank = report.get("bank")
    counts = report.get("counts")
    events = report.get("events")
    instruments = report.get("instruments")
    controllers = report.get("controllers")
    if not all(isinstance(item, Mapping) for item in (bank, counts)):
        raise inventory.InventoryError(f"{bank_path.name}: graph metadata is incomplete")
    if not all(isinstance(item, list) for item in (events, instruments, controllers)):
        raise inventory.InventoryError(f"{bank_path.name}: graph arrays are incomplete")
    if bank.get("sha256") != inventory.sha256_file(bank_path):
        raise inventory.InventoryError(f"{bank_path.name}: graph SHA-256 does not match source bank")
    if counts.get("events") != len(events) or counts.get("instruments") != len(instruments):
        raise inventory.InventoryError(f"{bank_path.name}: graph counts do not match arrays")
    for event in events:
        if not isinstance(event, Mapping) or not str(event.get("path") or "").startswith("event:/"):
            raise inventory.InventoryError(f"{bank_path.name}: an audio event path is unresolved")


def spatial_summary(physics: Mapping[str, Any]) -> dict[str, Any]:
    """Record the exact static emitter/listener geometry passed to Android FMOD.

    Some banks use FMOD's automatic `distance` parameter as an authored source gate. Keeping
    this geometry alongside the graph prevents an offline audit from mistaking such a gate for
    corrupt PCM merely because the source never materialises for the active listener positions.
    """

    car = physics["car"]
    wheelbase = float(car["wheelbase"])
    cg_location = float(car["cg_location"])
    rear_radius = float(car["rear_wheel_radius"])
    front_radius = float(car["front_wheel_radius"])
    engine_position = str(car.get("engine_position") or "").lower()
    if engine_position == "rear":
        engine = (0.0, rear_radius, -(wheelbase * cg_location) + 0.5)
    elif engine_position == "front":
        engine = (0.0, front_radius, wheelbase * (1.0 - cg_location))
    else:
        engine = (0.0, 0.5 * (front_radius + rear_radius), 0.0)

    cabin = tuple(float(value) for value in car["driver_eyes"])
    exterior = tuple(float(value) for value in car["bonnet_camera"])

    def distance(listener: tuple[float, float, float]) -> float:
        return math.dist(engine, listener)

    return {
        "engineEmitter": [round(value, 6) for value in engine],
        "cabinListener": [round(value, 6) for value in cabin],
        "exteriorListener": [round(value, 6) for value in exterior],
        "cabinEngineDistanceM": round(distance(cabin), 6),
        "exteriorEngineDistanceM": round(distance(exterior), 6),
    }


def static_car(source: Any) -> dict[str, Any]:
    if source.group != packs.MODDED_GROUP or not source.active:
        raise inventory.InventoryError(f"{source.pack_id}: not an active modded profile")
    if source.bank_path.resolve().parent != (source.source_directory / "sfx").resolve():
        raise inventory.InventoryError(f"{source.pack_id}: bank is outside its source sfx directory")
    physics = packs.load_physics(source)
    packs.validate_physics(source, physics)
    report, parser_exit_code = read_modded_graph(source.bank_path)
    graph_event_counts = resolve_modded_event_paths(report, source)
    bank_version = int(report.get("bank", {}).get("fileVersion", -1))
    # The modded collection legitimately mixes FMOD bank format 0x38 and the
    # current 0x50 format. Both load through the runtime's Studio version; the
    # official-only compiler remains strict so it can still detect a changed
    # installed Assetto source. Here we preserve the authored format as a fact.
    if bank_version not in (0x38, inventory.EXPECTED_BANK_FILE_VERSION):
        raise inventory.InventoryError(
            f"{source.pack_id}: unsupported FMOD bank file version 0x{bank_version:02x}"
        )
    expected_version = inventory.EXPECTED_BANK_FILE_VERSION
    try:
        inventory.EXPECTED_BANK_FILE_VERSION = bank_version
        validate_modded_graph(report, source.bank_path)
    finally:
        inventory.EXPECTED_BANK_FILE_VERSION = expected_version
    roles = inventory.classify_sources(report)
    raw_instruments = report["instruments"]
    raw_controllers = report["controllers"]
    raw_events = report["events"]
    instruments = {inventory.guid_text(item["guid"]): item for item in raw_instruments}
    controllers = {inventory.guid_text(item["guid"]): item for item in raw_controllers}
    parents = inventory.instrument_parent_map(instruments)
    summary = inventory.physics_summary(physics)
    summary["spatialGeometry"] = spatial_summary(physics)
    events = [
        inventory.event_record(
            event=event,
            bank_sha256=inventory.sha256_file(source.bank_path),
            instruments=instruments,
            parents=parents,
            controllers=controllers,
            role_by_source_guid=roles,
            has_turbo_physics=summary["turboCount"] > 0,
        )
        for event in raw_events
    ]
    events.sort(key=lambda event: event["identity"]["path"])
    suffixes = Counter(event["identity"]["suffix"] for event in events)
    return {
        "id": source.pack_id,
        "displayName": source.display_name,
        "scope": packs.MODDED_GROUP,
        "source": {
            "sourceDirectory": source.source_directory.name,
            "bankFileName": source.bank_path.name,
            "bankSha256": inventory.sha256_file(source.bank_path),
            "bankBytes": source.bank_path.stat().st_size,
            "guidsPath": "sfx/GUIDs.txt",
            "physicsJsonSha256": inventory.sha256_bytes(packs.canonical_physics_bytes(physics)),
            "sourceCarId": physics.get("sourceCarId"),
        },
        "physicsSummary": summary,
        "staticAudit": {
            "bankGuid": report["bank"].get("bankGuid"),
            "fileVersion": report["bank"].get("fileVersion"),
            "counts": report["counts"],
            "rawGraphEvents": graph_event_counts["rawGraphEvents"],
            "excludedNonAudioGraphEvents": graph_event_counts["excludedNonAudioGraphEvents"],
            "authoredEventFamilies": graph_event_counts["authoredEventFamilies"],
            "coverage": report["coverage"],
            "graphAuditExitCode": parser_exit_code,
            "authoredEventSuffixes": dict(sorted(suffixes.items())),
            "enginePair": {"engineInt": suffixes["engine_int"] == 1, "engineExt": suffixes["engine_ext"] == 1},
        },
        "events": events,
        "runtimeObservation": {
            "status": "pendingModdedRuntimeAudit",
            "meaning": "Static topology proves authoring and expected app treatment, not Android audibility. The paired runtime audit must establish voice starts, stops, routing, virtualisation, and genuine zero PCM where applicable.",
        },
    }


def markdown(cars: Sequence[Mapping[str, Any]]) -> str:
    total_events = sum(len(car["events"]) for car in cars)
    total_sources = sum(len(event["sources"]) for car in cars for event in car["events"])
    lines = [
        "# Modded Cars Audio Inventory",
        "",
        f"This generated inventory is the modded-car counterpart to `original-cars-audio-inventory.md`. It covers all {len(cars)} supplied `modded_cars` banks. Each event and source is recorded from the bank graph, the car-local `sfx/GUIDs.txt`, and matching exported physics; it does not claim that a source was heard until the Android runtime audit adds evidence.",
        "",
        "## Evidence rules",
        "",
        "- **Authored fact** is read directly from the supplied bank and its local GUID map.",
        "- **App policy** comes from the current Android bridge. It may change; it is not a fact about the bank.",
        "- **Expected runtime** means the event is eligible under its authored graph and the current app policy, not that the test has proven it audible.",
        "- A playing voice with 0% audibility requires PCM and FMOD-state inspection before it can be called a silent-audio defect.",
        "",
        f"## Catalog summary\n\n- {len(cars)} modded profiles, {total_events} authored events, and {total_sources} reachable waveform placements.",
    ]
    for car in cars:
        physics = car["physicsSummary"]
        lines.extend([
            "",
            f"## {car['displayName']} (`{car['id']}`)",
            "",
            f"- Bank `{car['source']['bankFileName']}`; SHA-256 `{car['source']['bankSha256']}`.",
            f"- Physics: idle {inventory.human_number(physics['idleRpm'])} RPM; limiter {inventory.human_number(physics['limiterRpm'])} RPM; {inventory.human_number(physics['forwardGearCount'])} forward gears; {inventory.human_number(physics['turboCount'])} turbo(s).",
            f"- Static coverage: {car['staticAudit']['counts']['events']} events; {car['staticAudit']['counts']['embeddedSamples']} embedded samples; complete source mapping {car['staticAudit']['coverage']['eventsWithCompleteSampleMapping']}/{car['staticAudit']['counts']['events']}.",
            "",
            "| Authored event | App treatment | Reachable sources |",
            "| --- | --- | ---: |",
        ])
        for event in car["events"]:
            lines.append(f"| `{event['identity']['path']}` | `{event['appPolicy']['classification']}` | {event['authoredFact']['sourceCount']} |")
        for event in car["events"]:
            lines.extend(["", f"### `{event['identity']['path']}`", ""])
            policy = event["appPolicy"]
            lines.append(f"- **Expected runtime:** `{policy['classification']}`. {policy['activation']}")
            if policy["parameters"]:
                lines.append("- **App parameters:** " + "; ".join(f"`{parameter['name']}` = {parameter['value']}" for parameter in policy["parameters"]) + ".")
            lines.extend(["", "| Raw sound | Role | Lifetime | Format | Authored activation |", "| --- | --- | --- | --- | --- |"])
            for source in event["sources"]:
                fact = source["authoredFact"]
                sample = fact["sample"]
                conditions = inventory.format_conditions(source)
                activation = "<br>".join(inventory.markdown_escape(condition) for condition in conditions) or "No explicit source-local gate recorded"
                lines.append(
                    f"| `{inventory.markdown_escape(fact['rawSoundName'])}` | `{source['staticInterpretation']['role']}` | `{fact['instrument']['lifetime']}` | "
                    f"{inventory.human_number(sample['channels'])}ch / {inventory.human_number(sample['frequencyHz'])}Hz / {inventory.human_number(sample['durationSeconds'])}s | {activation} |"
                )
    return "\n".join(lines) + "\n"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-output", type=Path, default=JSON_OUTPUT)
    parser.add_argument("--markdown-output", type=Path, default=MARKDOWN_OUTPUT)
    parser.add_argument("--parser-root", type=Path, default=inventory.DEFAULT_PARSER_ROOT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    inventory.validate_clean_audit_inputs(args.parser_root.resolve())
    inventory.build_auditor(args.parser_root.resolve())
    cars = sorted((static_car(source) for source in packs.discover_modded_sources()), key=lambda car: car["id"])
    if not cars:
        raise inventory.InventoryError("no modded profiles were discovered")
    document = {
        "schema": "byd-modded-cars-audio-inventory-v1",
        "scope": {"includedGroup": packs.MODDED_GROUP, "includedProfileCount": len(cars)},
        "cars": cars,
    }
    # This is an inventory for people as well as tooling. Keep the canonical
    # key ordering, but retain indentation so a per-car investigation can be
    # reviewed without a formatter or editor plugin.
    json_bytes = (json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    markdown_bytes = markdown(cars).encode("utf-8")
    outputs = ((args.json_output.resolve(), json_bytes), (args.markdown_output.resolve(), markdown_bytes))
    if args.check:
        differing = [str(path) for path, payload in outputs if not path.is_file() or path.read_bytes() != payload]
        if differing:
            raise inventory.InventoryError("modded inventory is out of date: " + ", ".join(differing))
    else:
        for path, payload in outputs:
            inventory.write_atomic(path, payload)
    print(f"Compiled modded-car static inventory: {len(cars)} cars, {sum(len(car['events']) for car in cars)} events.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except inventory.InventoryError as error:
        print(f"modded-car inventory failed: {error}", file=sys.stderr)
        raise SystemExit(1)
