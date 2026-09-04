#!/usr/bin/env python3
"""Compile a deterministic, static inventory of the shipped original FMOD banks.

The dashboard does not ship source banks, and a static graph cannot prove that a
voice was audible on Android.  This compiler deliberately records those things
separately: it extracts only authoring facts from the installed original Assetto
Corsa banks, records the Android bridge policy from a reviewed table, and marks
runtime observations as not captured until the debug telemetry importer fills
them in.  That distinction prevents a later reader from treating a sample name
or a static graph as proof of actual playback.

The Audio Lab's graph parser is used in its read-only C# mode.  macOS cannot run
the Lab's Windows-only silent FMOD oracle, so event paths are resolved from the
official installed ``content/sfx/GUIDs.txt`` instead.  The compiler fails rather
than guessing when a source bank, matching physics, GUID path, or graph mapping
is missing or ambiguous.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import math
import os
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT.parent
INSTALLATION = DOWNLOADS / "assetto_corsa_installation"
AUDIO_LAB = DOWNLOADS / "assetto_corsa_audio_lab"
DEFAULT_JSON_OUTPUT = ROOT / "docs" / "original-cars-audio-inventory.json"
DEFAULT_MARKDOWN_OUTPUT = ROOT / "docs" / "original-cars-audio-inventory.md"
DEFAULT_GUIDS_PATH = INSTALLATION / "content" / "sfx" / "GUIDs.txt"
DEFAULT_PARSER_ROOT = AUDIO_LAB / ".aclib-tools" / "FModBankParser"
AUDIT_PROJECT = AUDIO_LAB / "tools" / "fmod_bank_graph_audit" / "FmodBankGraphAudit.csproj"
AUDIT_DLL = AUDIO_LAB / "tools" / "fmod_bank_graph_audit" / "bin" / "Release" / "net8.0" / "FmodBankGraphAudit.dll"
AUDIT_PYTHON = AUDIO_LAB / "tools" / "audit_fmod_bank_graph.py"
AUDIT_PROGRAM = AUDIO_LAB / "tools" / "fmod_bank_graph_audit" / "Program.cs"
GRAPH_ROLES = AUDIO_LAB / "sim" / "fmod_graph_roles.py"
PARSER_PATCH = AUDIO_LAB / "tools" / "fmod_bank_graph_audit" / "FModBankParser-ac108-net8.patch"
FMOD5_PATCH = AUDIO_LAB / "tools" / "fmod_bank_graph_audit" / "Fmod5Sharp-net8.patch"

INVENTORY_SCHEMA = "byd-original-cars-audio-inventory-v1"
INVENTORY_VERSION = 1
RUNTIME_SUMMARY_SCHEMA = "byd-fmod-runtime-summary-v1"
RUNTIME_TRACE_SCHEMA = "byd-fmod-debug-trace-v1"
RUNTIME_NATIVE_LIFECYCLE_SCHEMA = "byd-fmod-native-lifecycle-v1"
RUNTIME_EVENT_CATALOG_SCHEMA = "byd-fmod-bank-event-catalog-v1"
RUNTIME_SUMMARY_VERSION = 1
EXPECTED_ORIGINAL_PROFILE_COUNT = 23
EXPECTED_BANK_FILE_VERSION = 0x50
TOOL_VERSION = "1.1.0"


class InventoryError(RuntimeError):
    """Raised when the static source contract is incomplete or ambiguous."""


@dataclass(frozen=True)
class AppEventPolicy:
    """Reviewed Android policy, intentionally kept distinct from authored FMOD data."""

    classification: str
    instance_lifecycle: str
    activation: str
    parameters: tuple[Mapping[str, Any], ...] = ()
    notes: tuple[str, ...] = ()


# This table mirrors fmod_bank_bridge.cpp.  It is data rather than inferred
# string matching so generated documentation cannot silently re-label a bank
# event as an app behavior when native runtime policy changes.  The compiler
# validates the bridge's policy markers before using it.
APP_EVENT_POLICIES: dict[str, AppEventPolicy] = {
    "engine_int": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load; the selected cabin/exterior engine instance starts immediately.",
        activation="Selected by listening perspective. Perspective changes stop the old selected event with allow-fade-out and start the other event.",
        parameters=(
            {"name": "rpms", "value": "live simulated RPM", "source": "app physical frame"},
            {"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},
        ),
        notes=("Engine host gain is currently 1.0 before temporary mixer overrides.",),
    ),
    "engine_ext": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load; the selected cabin/exterior engine instance starts immediately.",
        activation="Selected by listening perspective. Perspective changes stop the old selected event with allow-fade-out and start the other event.",
        parameters=(
            {"name": "rpms", "value": "live simulated RPM", "source": "app physical frame"},
            {"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},
        ),
        notes=("Engine host gain is currently 1.0 before temporary mixer overrides.",),
    ),
    "transmission": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and starts immediately when present.",
        activation="The bridge uses it as the cabin/exterior fallback whenever this bank has no transmission_ext. In that fallback case it remains running across a perspective transition while only listener placement changes.",
        parameters=(
            {"name": "drivetrain_speed", "value": "live fmodDrivetrainSpeed", "source": "app physical frame"},
            {"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},
        ),
        notes=("Effects host gain is currently 2.0 before the optional transmission category trim.",),
    ),
    "transmission_ext": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load when authored by a bank.",
        activation="When paired with transmission, it is selected for exterior listening and perspective switching stops/starts the selected transmission event. No current original bank exposes this suffix.",
        parameters=(
            {"name": "drivetrain_speed", "value": "live fmodDrivetrainSpeed", "source": "app physical frame"},
            {"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},
        ),
        notes=("Effects host gain is currently 2.0 before the optional transmission category trim.",),
    ),
    "turbo": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and starts immediately only when matching physics declares at least one turbo.",
        activation="Persistent while the bank is open; it is not restarted for a gear shift or perspective transition.",
        parameters=(
            {"name": "boost", "value": "live normalized turbo boost", "source": "app physical frame"},
            {"name": "bov", "value": "live BOV state", "source": "app physical frame"},
            {"name": "bov_decay", "value": "live BOV decay", "source": "app physical frame"},
        ),
        notes=("Effects host gain is currently 2.0 before the optional turbo category trim.",),
    ),
    "limiter": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and stays dormant until a limiter pulse.",
        activation="A limiter pulse resets decay to zero and starts the event. The app stops it with allow-fade-out after more than 10 seconds without a pulse.",
        parameters=({"name": "decay", "value": "seconds since latest limiter pulse", "source": "app lifecycle policy"},),
    ),
    "gear_int": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and remains dormant between shifts.",
        activation="Selected for cabin perspective at shift start when no current instance is playing. State is 1 for upshift and 0 for downshift.",
        parameters=({"name": "state", "value": "1 upshift / 0 downshift", "source": "app shift direction"},),
        notes=("The already-playing guard can intentionally suppress an overlapping repeat of the same event instance.",),
    ),
    "gear_ext": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and remains dormant between shifts.",
        activation="Selected for exterior perspective at shift start when no current instance is playing. State is 1 for upshift and 0 for downshift.",
        parameters=({"name": "state", "value": "1 upshift / 0 downshift", "source": "app shift direction"},),
        notes=("The already-playing guard can intentionally suppress an overlapping repeat of the same event instance.",),
    ),
    "gear_grind": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and remains dormant between rejected shifts.",
        activation="Started when the drivetrain rejects a shift and no existing gear-grind instance is playing.",
    ),
    "backfire_int": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and remains dormant between accepted backfire triggers.",
        activation="Selected for cabin perspective when the app reports a backfire trigger and neither backfire perspective instance is already playing.",
        parameters=({"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},),
    ),
    "backfire_ext": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load and remains dormant between accepted backfire triggers.",
        activation="Selected for exterior perspective when the app reports a backfire trigger and neither backfire perspective instance is already playing.",
        parameters=({"name": "throttle", "value": 1.0, "source": "intentional app full-load policy"},),
    ),
    "tractioncontrol_int": AppEventPolicy(
        classification="playable_but_suppressed",
        instance_lifecycle="Instance is created at bank load but is immediately stopped if it ever becomes active.",
        activation="The drivetrain can still calculate traction limiting, but this event is intentionally never allowed to remain audible.",
        parameters=({"name": "decay", "value": 10.0, "source": "intentional app suppression policy"},),
        notes=("This is an app policy, not an authored-bank absence.",),
    ),
    "tractioncontrol_ext": AppEventPolicy(
        classification="playable_but_suppressed",
        instance_lifecycle="Instance is created at bank load but is immediately stopped if it ever becomes active.",
        activation="The drivetrain can still calculate traction limiting, but this event is intentionally never allowed to remain audible.",
        parameters=({"name": "decay", "value": 10.0, "source": "intentional app suppression policy"},),
        notes=("This is an app policy, not an authored-bank absence.",),
    ),
    "start": AppEventPolicy(
        classification="playable",
        instance_lifecycle="Instance is created at bank load.",
        activation="Started once immediately after selected continuous events initialize; the app does not add an ignition RPM ramp.",
    ),
    "wind": AppEventPolicy(
        classification="recognized_but_excluded",
        instance_lifecycle="No event instance is created.",
        activation="Intentionally excluded from the simulator runtime.",
    ),
    "tyres": AppEventPolicy(
        classification="recognized_but_excluded",
        instance_lifecycle="No event instance is created.",
        activation="Intentionally excluded from the simulator runtime.",
    ),
    "skid_int": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: skid/tire surface audio is outside this engine-sound simulator.",
        notes=("This is a product boundary, not evidence that the authored skid event is malformed.",),
    ),
    "skid_ext": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: skid/tire surface audio is outside this engine-sound simulator.",
        notes=("This is a product boundary, not evidence that the authored skid event is malformed.",),
    ),
    "wheel": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: wheel/tire rolling audio is outside this engine-sound simulator.",
        notes=("This is a product boundary, not evidence that the authored wheel event is malformed.",),
    ),
    "bodywork": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: chassis/bodywork audio is outside this engine-sound simulator.",
        notes=("This is a product boundary, not evidence that the authored bodywork event is malformed.",),
    ),
    "door": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: door audio is outside this engine-sound simulator.",
        notes=("This is a product boundary, not evidence that the authored door event is malformed.",),
    ),
    "horn": AppEventPolicy(
        classification="product_excluded_not_recognized",
        instance_lifecycle="No event instance is created because the native bridge does not recognize this suffix.",
        activation="Intentional product exclusion: horn audio is not a simulated engine/drivetrain effect.",
        notes=("This is a product boundary, not evidence that the authored horn event is malformed.",),
    ),
}

PROPERTY_LABELS = {
    0: "volume automation (dB)",
    1: "pitch automation",
    4: "fade automation (0..1)",
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    # Markdown is the readable artifact.  Keep the machine sidecar canonical
    # and compact so complete per-source curves do not create an unnecessarily
    # large Git diff or invite hand editing.
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


def write_atomic(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(content)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def checked_run(args: Sequence[str], *, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        list(args),
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if completed.returncode:
        detail = completed.stderr.strip() or completed.stdout.strip() or "no output"
        raise InventoryError(f"command failed ({' '.join(args)}): {detail}")
    return completed.stdout


def git_text(directory: Path, *args: str) -> str:
    return checked_run(("git", "-C", str(directory), *args)).strip()


def git_porcelain(directory: Path) -> list[str]:
    """Return porcelain lines without stripping a leading clean-index space."""

    output = checked_run(("git", "-C", str(directory), "status", "--porcelain"))
    return [line for line in output.splitlines() if line]


def import_module(module_path: Path, name: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, module_path)
    if spec is None or spec.loader is None:
        raise InventoryError(f"could not load module {module_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def guid_text(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def event_suffix(path: str) -> str:
    return path.strip().rstrip("/").rsplit("/", 1)[-1].casefold()


def require_file(path: Path, description: str) -> None:
    if not path.is_file():
        raise InventoryError(f"{description} is missing: {path}")


def load_guid_paths(path: Path) -> dict[str, tuple[str, ...]]:
    """Read the official string-bank event mapping without guessing by event name."""

    require_file(path, "Assetto Corsa GUID mapping")
    result: dict[str, set[str]] = defaultdict(set)
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8-sig", errors="replace").splitlines(), start=1
    ):
        fields = line.split(None, 1)
        if len(fields) != 2 or not fields[1].startswith("event:/"):
            continue
        guid = guid_text(fields[0])
        event_path = fields[1].strip()
        if not guid or not event_path:
            raise InventoryError(f"invalid event GUID mapping at {path}:{line_number}")
        result[guid].add(event_path)
    if not result:
        raise InventoryError(f"no event mappings found in {path}")
    return {guid: tuple(sorted(paths)) for guid, paths in sorted(result.items())}


def validate_clean_audit_inputs(parser_root: Path) -> dict[str, Any]:
    """Pin exactly which non-repository tools the inventory is allowed to consume.

    Audio Lab can have unrelated local server edits.  This compiler deliberately
    ignores them, but it refuses to use a dirty graph-audit source file or a
    parser clone whose only changes are not the documented compatibility patches.
    """

    for path, label in (
        (AUDIT_PYTHON, "Audio Lab graph-audit adapter"),
        (AUDIT_PROJECT, "Audio Lab graph-audit project"),
        (AUDIT_PROGRAM, "Audio Lab graph-audit program"),
        (GRAPH_ROLES, "Audio Lab role classifier"),
        (PARSER_PATCH, "FModBankParser compatibility patch"),
        (FMOD5_PATCH, "Fmod5Sharp compatibility patch"),
    ):
        require_file(path, label)
    if not parser_root.is_dir():
        raise InventoryError(
            "FModBankParser is not provisioned under the ignored Audio Lab tools directory: "
            f"{parser_root}"
        )

    # Do not let a locally edited Lab auditor become invisible provenance.
    checked_run(
        (
            "git",
            "-C",
            str(AUDIO_LAB),
            "diff",
            "--exit-code",
            "--",
            str(AUDIT_PYTHON.relative_to(AUDIO_LAB)),
            str(AUDIT_PROJECT.relative_to(AUDIO_LAB)),
            str(AUDIT_PROGRAM.relative_to(AUDIO_LAB)),
            str(GRAPH_ROLES.relative_to(AUDIO_LAB)),
            str(PARSER_PATCH.relative_to(AUDIO_LAB)),
            str(FMOD5_PATCH.relative_to(AUDIO_LAB)),
        )
    )

    expected_parser_changes = {
        "FModBankParser/FModBankParser.csproj",
        "FModBankParser/FModReader.cs",
        "FModBankParser/Nodes/ControllerNode.cs",
        "FModBankParser/Nodes/Instruments/InstrumentNode.cs",
        "FModBankParser/Nodes/ParameterLayoutNode.cs",
        "Fmod5Sharp",
    }
    parser_status = git_porcelain(parser_root)
    parser_paths = {line[3:].strip().rstrip("/") for line in parser_status}
    if parser_paths != expected_parser_changes:
        raise InventoryError(
            "local FModBankParser has unexpected changes; expected only the documented "
            f"compatibility adaptation, got {sorted(parser_paths)}"
        )

    submodule = parser_root / "Fmod5Sharp"
    expected_submodule_changes = {"Fmod5Sharp/Fmod5Sharp.csproj"}
    submodule_status = git_porcelain(submodule)
    submodule_paths = {line[3:].strip() for line in submodule_status}
    if submodule_paths != expected_submodule_changes:
        raise InventoryError(
            "local Fmod5Sharp has unexpected changes; expected only the documented "
            f"net8 adaptation, got {sorted(submodule_paths)}"
        )

    # `--reverse --check` proves that the exact checked-in patch can undo the
    # local adaptation.  We do not build from any untracked or unrelated Lab code.
    checked_run(("git", "-C", str(parser_root), "apply", "--reverse", "--check", str(PARSER_PATCH)))
    checked_run(("git", "-C", str(submodule), "apply", "--reverse", "--check", str(FMOD5_PATCH)))

    return {
        "mode": "direct-csharp-static-parser-with-guid-text-path-map",
        "audioLabCommit": git_text(AUDIO_LAB, "rev-parse", "HEAD"),
        "parserCommit": git_text(parser_root, "rev-parse", "HEAD"),
        "fmod5SharpCommit": git_text(submodule, "rev-parse", "HEAD"),
        "auditSources": {
            str(path.relative_to(AUDIO_LAB)): sha256_file(path)
            for path in (AUDIT_PYTHON, AUDIT_PROJECT, AUDIT_PROGRAM, GRAPH_ROLES, PARSER_PATCH, FMOD5_PATCH)
        },
        "compatibilityPatches": {
            str(PARSER_PATCH.relative_to(AUDIO_LAB)): sha256_file(PARSER_PATCH),
            str(FMOD5_PATCH.relative_to(AUDIO_LAB)): sha256_file(FMOD5_PATCH),
        },
        "runtimeOracle": {
            "status": "notRun",
            "reason": "The Audio Lab silent FMOD oracle is Windows-only; official GUIDs.txt supplies static event paths on macOS.",
        },
    }


def build_auditor(parser_root: Path) -> None:
    audit_module = import_module(AUDIT_PYTHON, "byd_audio_lab_static_graph_audit")
    try:
        audit_module.build_static_auditor(parser_root)
    except Exception as error:  # The imported tool owns exact compatibility validation.
        raise InventoryError(f"could not build the pinned static FMOD graph auditor: {error}") from error
    require_file(AUDIT_DLL, "built static FMOD graph auditor")


def read_graph(bank_path: Path) -> dict[str, Any]:
    result = checked_run(("dotnet", str(AUDIT_DLL), str(bank_path)), cwd=AUDIO_LAB)
    try:
        report = json.loads(result)
    except json.JSONDecodeError as error:
        raise InventoryError(f"static graph auditor returned invalid JSON for {bank_path.name}") from error
    if not isinstance(report, dict):
        raise InventoryError(f"static graph auditor returned a non-object for {bank_path.name}")
    return report


def resolve_event_paths(
    report: dict[str, Any], *, source_car_id: str, guid_paths: Mapping[str, tuple[str, ...]]
) -> None:
    prefix = f"event:/cars/{source_car_id}/"
    events = report.get("events")
    if not isinstance(events, list) or not events:
        raise InventoryError(f"{source_car_id}: graph has no events")
    resolved_paths: set[str] = set()
    for event in events:
        if not isinstance(event, dict):
            raise InventoryError(f"{source_car_id}: graph contains a malformed event")
        guid = guid_text(event.get("guid"))
        candidates = tuple(path for path in guid_paths.get(guid, ()) if path.startswith(prefix))
        if len(candidates) != 1:
            raise InventoryError(
                f"{source_car_id}: event {guid or '<missing>'} has {len(candidates)} matching "
                f"GUIDs.txt paths under {prefix!r}: {list(candidates)}"
            )
        event["path"] = candidates[0]
        if candidates[0] in resolved_paths:
            raise InventoryError(f"{source_car_id}: duplicate event path {candidates[0]!r}")
        resolved_paths.add(candidates[0])


def validate_graph(report: Mapping[str, Any], bank_path: Path) -> None:
    bank = report.get("bank")
    counts = report.get("counts")
    coverage = report.get("coverage")
    events = report.get("events")
    instruments = report.get("instruments")
    controllers = report.get("controllers")
    if not all(isinstance(item, Mapping) for item in (bank, counts, coverage)):
        raise InventoryError(f"{bank_path.name}: graph metadata is incomplete")
    if not all(isinstance(item, list) for item in (events, instruments, controllers)):
        raise InventoryError(f"{bank_path.name}: graph arrays are incomplete")
    if bank.get("sha256") != sha256_file(bank_path):
        raise InventoryError(f"{bank_path.name}: graph SHA-256 does not match source bank")
    if int(bank.get("fileVersion", -1)) != EXPECTED_BANK_FILE_VERSION:
        raise InventoryError(
            f"{bank_path.name}: expected FMOD file version 0x{EXPECTED_BANK_FILE_VERSION:02x}, "
            f"got {bank.get('fileVersion')!r}"
        )
    if counts.get("events") != len(events) or counts.get("instruments") != len(instruments):
        raise InventoryError(f"{bank_path.name}: graph counts do not match arrays")
    if coverage.get("eventsWithCompleteSampleMapping") != len(events):
        raise InventoryError(f"{bank_path.name}: not every event has complete sample mapping")
    if coverage.get("controllersWithCurve") != counts.get("controllers"):
        raise InventoryError(f"{bank_path.name}: one or more controllers lack authored curves")
    for event in events:
        if not isinstance(event, Mapping):
            raise InventoryError(f"{bank_path.name}: malformed event")
        if event.get("mappingComplete") is not True:
            raise InventoryError(f"{bank_path.name}: incomplete source mapping for {event.get('guid')}")
        if event.get("mappedSampleIds") != event.get("resolverSampleIds"):
            raise InventoryError(f"{bank_path.name}: resolver sample IDs disagree for {event.get('guid')}")
        if not str(event.get("path") or "").startswith("event:/"):
            raise InventoryError(f"{bank_path.name}: event path is unresolved for {event.get('guid')}")


def loop_lifetime(properties: Mapping[str, Any]) -> str:
    loop_count = properties.get("loopCount")
    try:
        loop_count = int(loop_count)
    except (TypeError, ValueError):
        return "unknown"
    if loop_count == -1:
        return "continuous"
    if loop_count == 0:
        return "oneShot"
    if loop_count > 0:
        return f"finiteRepeat({loop_count})"
    return f"unknown({loop_count})"


def round_duration(sample: Mapping[str, Any]) -> float | None:
    try:
        frequency = float(sample["frequencyHz"])
        sample_count = float(sample["sampleCount"])
    except (KeyError, TypeError, ValueError):
        return None
    if frequency <= 0 or sample_count < 0:
        return None
    return round(sample_count / frequency, 6)


def human_number(value: Any) -> str:
    if value is None:
        return "unknown"
    if isinstance(value, float):
        return f"{value:.6g}"
    return str(value)


def compact_points(points: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for point in points:
        result.append(
            {
                "x": point.get("x"),
                "y": point.get("y"),
                "shape": point.get("shape"),
                "type": point.get("type"),
            }
        )
    return result


def instrument_parent_map(instruments: Mapping[str, Mapping[str, Any]]) -> dict[str, tuple[str, ...]]:
    parents: dict[str, set[str]] = defaultdict(set)
    for parent_guid, instrument in instruments.items():
        children = instrument.get("childInstruments")
        if not isinstance(children, list):
            continue
        for child in children:
            child_guid = guid_text(child.get("guid") if isinstance(child, Mapping) else child)
            if not child_guid:
                raise InventoryError(f"instrument {parent_guid}: child instrument has no GUID")
            parents[child_guid].add(parent_guid)
    return {guid: tuple(sorted(values)) for guid, values in parents.items()}


def source_ancestry(
    source_guid: str,
    instruments: Mapping[str, Mapping[str, Any]],
    parents: Mapping[str, tuple[str, ...]],
) -> list[tuple[str, Mapping[str, Any], int]]:
    """Return source-to-root ancestry; multiple parents are a real ambiguity."""

    chain: list[tuple[str, Mapping[str, Any], int]] = []
    current = source_guid
    seen: set[str] = set()
    depth = 0
    while current:
        if current in seen:
            raise InventoryError(f"instrument {source_guid}: ancestor cycle at {current}")
        seen.add(current)
        instrument = instruments.get(current)
        if instrument is None:
            raise InventoryError(f"instrument {source_guid}: missing ancestor {current}")
        chain.append((current, instrument, depth))
        parent_paths = parents.get(current, ())
        if len(parent_paths) > 1:
            raise InventoryError(
                f"instrument {source_guid}: multiple ancestor paths {list(parent_paths)}; "
                "static activation conditions are ambiguous"
            )
        current = parent_paths[0] if parent_paths else ""
        depth += 1
    return chain


def child_selection(parent: Mapping[str, Any], child_guid: str) -> dict[str, Any] | None:
    playlist = parent.get("playlist")
    if not isinstance(playlist, Mapping):
        return None
    for child in parent.get("childInstruments", []):
        if isinstance(child, Mapping) and guid_text(child.get("guid")) == child_guid:
            return {
                "parentInstrumentGuid": guid_text(parent.get("guid")),
                "playMode": playlist.get("playMode"),
                "selectionMode": playlist.get("selectionMode"),
                "childWeight": child.get("weight"),
                "authoredOrder": child.get("authoredOrder"),
            }
    raise InventoryError(
        f"playlist {guid_text(parent.get('guid'))}: source child {child_guid} is missing from its child list"
    )


def source_conditions(
    event: Mapping[str, Any],
    source_guid: str,
    ancestry: Sequence[tuple[str, Mapping[str, Any], int]],
    controllers: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    target_guids = {guid for guid, _instrument, _depth in ancestry}
    placements: list[dict[str, Any]] = []
    for placement in event.get("parameterPlacements", []):
        if not isinstance(placement, Mapping):
            raise InventoryError(f"event {event.get('path')}: malformed parameter placement")
        owner = guid_text(placement.get("instrumentGuid"))
        if owner not in target_guids:
            continue
        placements.append(
            {
                "kind": "parameterRange",
                "scope": "source" if owner == source_guid else "ancestor",
                "instrumentGuid": owner,
                "parameterGuid": guid_text(placement.get("parameterGuid")),
                "parameterName": str(placement.get("parameterName") or "").casefold(),
                "start": placement.get("start"),
                "end": placement.get("end"),
                "includeEnd": placement.get("includeEnd") is True,
            }
        )
    for placement in event.get("timelinePlacements", []):
        if not isinstance(placement, Mapping):
            raise InventoryError(f"event {event.get('path')}: malformed timeline placement")
        owner = guid_text(placement.get("instrumentGuid"))
        if owner not in target_guids:
            continue
        placements.append(
            {
                "kind": "timeline",
                "scope": "source" if owner == source_guid else "ancestor",
                "instrumentGuid": owner,
                "startTime": placement.get("startTime"),
                "length": placement.get("length"),
                "timeLocked": placement.get("timeLocked") is True,
            }
        )

    selection: list[dict[str, Any]] = []
    for index in range(1, len(ancestry)):
        child_guid = ancestry[index - 1][0]
        parent = ancestry[index][1]
        record = child_selection(parent, child_guid)
        if record is not None:
            selection.append(record)

    automation: list[dict[str, Any]] = []
    for instrument_guid, instrument, depth in ancestry:
        raw_controller_guids = instrument.get("controllerGuids")
        if not isinstance(raw_controller_guids, list):
            raise InventoryError(f"instrument {instrument_guid}: controller GUID list is malformed")
        for controller_guid_value in sorted({guid_text(value) for value in raw_controller_guids if guid_text(value)}):
            controller = controllers.get(controller_guid_value)
            if controller is None:
                raise InventoryError(
                    f"instrument {instrument_guid}: controller {controller_guid_value} is absent from graph"
                )
            property_index = controller.get("propertyIndex")
            try:
                property_index = int(property_index)
            except (TypeError, ValueError):
                property_index = -1
            points = controller.get("points")
            if not isinstance(points, list) or not points:
                raise InventoryError(
                    f"controller {controller_guid_value}: no usable authored curve points"
                )
            automation.append(
                {
                    "controllerGuid": controller_guid_value,
                    "scope": "source" if depth == 0 else "ancestor",
                    "scopeInstrumentGuid": instrument_guid,
                    "ancestorDepth": depth,
                    "propertyIndex": property_index,
                    "propertyLabel": PROPERTY_LABELS.get(property_index, f"FMOD property index {property_index}"),
                    "inputKind": controller.get("inputKind"),
                    "inputParameterGuid": guid_text(controller.get("inputParameterGuid")),
                    "inputParameterName": str(controller.get("inputParameterName") or "").casefold(),
                    "curve": compact_points(point for point in points if isinstance(point, Mapping)),
                }
            )
    placements.sort(
        key=lambda item: (
            item["kind"],
            str(item.get("parameterName") or ""),
            float(item.get("start") or item.get("startTime") or 0),
            item["instrumentGuid"],
        )
    )
    selection.sort(key=lambda item: (item["parentInstrumentGuid"], item.get("authoredOrder", -1)))
    automation.sort(key=lambda item: (item["ancestorDepth"], item["scopeInstrumentGuid"], item["controllerGuid"]))
    return {"placements": placements, "playlistSelection": selection, "automation": automation}


def app_policy_for_event(suffix: str, *, has_turbo_physics: bool) -> dict[str, Any]:
    policy = APP_EVENT_POLICIES.get(suffix)
    if policy is None:
        return {
            "classification": "unsupported_by_current_app",
            "instanceLifecycle": "No event instance is created by the current native bridge.",
            "activation": "The bridge only recognizes its reviewed event suffix allow-list.",
            "parameters": [],
            "notes": ["This is not proof that the original bank event is invalid or inaudible in Assetto Corsa."],
        }
    notes = list(policy.notes)
    activation = policy.activation
    if suffix == "turbo" and not has_turbo_physics:
        activation = (
            "The event is recognized and its instance is created, but matching physics declares no turbo, "
            "so the app does not start it or write boost/BOV parameters."
        )
        notes.append("The bank can still author a turbo event; its absence at runtime is an app physics gate.")
    return {
        "classification": policy.classification,
        "instanceLifecycle": policy.instance_lifecycle,
        "activation": activation,
        "parameters": [dict(item) for item in policy.parameters],
        "notes": notes,
    }


def runtime_not_captured() -> dict[str, Any]:
    return {
        "status": "notCaptured",
        "meaning": "Static inventory only. Android debug telemetry must establish voice creation, audibility, virtualisation, routing, and stop timing.",
    }


def runtime_not_observed(capture_ids: Sequence[str]) -> dict[str, Any]:
    """Describe a known catalog entry that a bounded runtime drive did not reach.

    This deliberately does *not* call the source silent. A capture only proves
    what its selected scenario reached; authored curves, randomization, or a
    different driving state can still activate the source later.
    """

    return {
        "status": "notObservedInImportedCapture",
        "meaning": "The imported Android capture enumerated this event's bank, but no unambiguous matching lifecycle observation occurred in its bounded scenario. This is not proof that the authored source is silent or unreachable.",
        "captureIds": sorted(capture_ids),
    }


def require_runtime_mapping(value: object, description: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise InventoryError(f"runtime evidence {description} must be a JSON object")
    return value


def require_runtime_text(value: object, description: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InventoryError(f"runtime evidence {description} must be a non-empty string")
    return value.strip()


def require_runtime_sha256(value: object, description: str) -> str:
    result = require_runtime_text(value, description).casefold()
    if len(result) != 64 or any(character not in "0123456789abcdef" for character in result):
        raise InventoryError(f"runtime evidence {description} must be a lowercase SHA-256 hex digest")
    return result


def require_runtime_nonnegative_int(value: object, description: str) -> int:
    if isinstance(value, bool):
        raise InventoryError(f"runtime evidence {description} must be a non-negative integer")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as error:
        raise InventoryError(f"runtime evidence {description} must be a non-negative integer") from error
    if not math.isfinite(parsed) or parsed < 0 or not parsed.is_integer():
        raise InventoryError(f"runtime evidence {description} must be a non-negative integer")
    # Native C++ serialises a few integer-valued metadata fields with fixed
    # decimal precision (for example ``44100.000000`` Hz). Accept that exact
    # numerical representation without accepting fractional identities.
    return int(parsed)


def optional_runtime_nonnegative_int(value: object, description: str) -> int | None:
    if value is None or value == "":
        return None
    return require_runtime_nonnegative_int(value, description)


def require_runtime_nonnegative_float(value: object, description: str) -> float:
    if isinstance(value, bool):
        raise InventoryError(f"runtime evidence {description} must be a finite non-negative number")
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise InventoryError(
            f"runtime evidence {description} must be a finite non-negative number"
        ) from error
    if not math.isfinite(result) or result < 0:
        raise InventoryError(f"runtime evidence {description} must be a finite non-negative number")
    return result


def optional_runtime_nonnegative_float(value: object, description: str) -> float | None:
    if value is None or value == "":
        return None
    return require_runtime_nonnegative_float(value, description)


def runtime_capture_id(capture: Mapping[str, Any]) -> str:
    """Give a compact evidence record a reproducible identity without raw traces.

    The trace CSV itself remains outside Git.  The generated inventory retains
    only this digest and the compact counters that support a diagnosis.
    """

    payload = dict(capture)
    payload.pop("captureId", None)
    return sha256_bytes(canonical_json_bytes(payload))


def runtime_metrics_template() -> dict[str, Any]:
    return {
        "eventStartedCount": 0,
        "eventStoppedCount": 0,
        "soundPlayedCount": 0,
        "soundStoppedCount": 0,
        "voiceStateSampleCount": 0,
        "maxVoiceCount": 0,
        "maxVirtualVoiceCount": 0,
        "maxCallbackVoiceCount": 0,
        "maxAudibility": 0.0,
        "maxRouteGain": 0.0,
        "everAudible": False,
        "everVirtual": False,
        "distinctVoiceCount": 0,
    }


def normalize_runtime_metrics(value: Mapping[str, Any], description: str) -> dict[str, Any]:
    """Validate compact, aggregated lifecycle evidence from a debug session."""

    result = runtime_metrics_template()
    count_fields = (
        "eventStartedCount",
        "eventStoppedCount",
        "soundPlayedCount",
        "soundStoppedCount",
        "voiceStateSampleCount",
        "maxVoiceCount",
        "maxVirtualVoiceCount",
        "maxCallbackVoiceCount",
        "distinctVoiceCount",
    )
    float_fields = ("maxAudibility", "maxRouteGain")
    bool_fields = ("everAudible", "everVirtual")
    for key in count_fields:
        if key in value:
            result[key] = require_runtime_nonnegative_int(value[key], f"{description}.{key}")
    for key in float_fields:
        if key in value:
            result[key] = require_runtime_nonnegative_float(value[key], f"{description}.{key}")
    for key in bool_fields:
        if key in value:
            if not isinstance(value[key], bool):
                raise InventoryError(f"runtime evidence {description}.{key} must be boolean")
            result[key] = value[key]
    if result["maxAudibility"] > 0:
        result["everAudible"] = True
    if result["maxVirtualVoiceCount"] > 0:
        result["everVirtual"] = True
    return result


def summarize_runtime_metrics(metrics: Mapping[str, Any]) -> dict[str, Any]:
    """Keep only fields that actually carry evidence, in deterministic order."""

    return {
        key: metrics[key]
        for key in (
            "eventStartedCount",
            "eventStoppedCount",
            "soundPlayedCount",
            "soundStoppedCount",
            "voiceStateSampleCount",
            "maxVoiceCount",
            "maxVirtualVoiceCount",
            "maxCallbackVoiceCount",
            "maxAudibility",
            "maxRouteGain",
            "everAudible",
            "everVirtual",
            "distinctVoiceCount",
        )
        if metrics.get(key) not in (0, 0.0, False)
    }


def read_runtime_csv(path: Path, required_fields: set[str]) -> list[dict[str, str]]:
    require_file(path, "runtime evidence CSV")
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        fieldnames = set(reader.fieldnames or ())
        missing = sorted(required_fields - fieldnames)
        if missing:
            raise InventoryError(f"runtime evidence {path.name} lacks required columns: {missing}")
        rows = []
        for row_number, row in enumerate(reader, start=2):
            if None in row:
                raise InventoryError(f"runtime evidence {path.name}:{row_number} has an unexpected extra column")
            rows.append({key: value or "" for key, value in row.items()})
    return rows


def runtime_event_catalog_from_rows(rows: Sequence[Mapping[str, str]], description: str) -> list[dict[str, str]]:
    catalog: list[dict[str, str]] = []
    paths: set[str] = set()
    guids: set[str] = set()
    for index, row in enumerate(rows, start=1):
        path = require_runtime_text(row.get("eventPath"), f"{description}[{index}].eventPath")
        guid = guid_text(require_runtime_text(row.get("eventGuid"), f"{description}[{index}].eventGuid"))
        suffix = require_runtime_text(row.get("eventSuffix"), f"{description}[{index}].eventSuffix").casefold()
        classification = require_runtime_text(
            row.get("appClassification"), f"{description}[{index}].appClassification"
        )
        if not path.startswith("event:/") or not guid:
            raise InventoryError(f"runtime evidence {description}[{index}] has an invalid event identity")
        if suffix != event_suffix(path):
            raise InventoryError(
                f"runtime evidence {description}[{index}] suffix {suffix!r} does not match event path {path!r}"
            )
        if path in paths or guid in guids:
            raise InventoryError(f"runtime evidence {description} has duplicate event path or GUID")
        paths.add(path)
        guids.add(guid)
        catalog.append(
            {
                "eventPath": path,
                "eventGuid": guid,
                "eventSuffix": suffix,
                "appClassification": classification,
            }
        )
    if not catalog:
        raise InventoryError(f"runtime evidence {description} is empty")
    return sorted(catalog, key=lambda item: item["eventPath"])


def source_descriptor_from_native_row(row: Mapping[str, str], description: str) -> dict[str, Any] | None:
    """Build the only safe static/source join available from FMOD callbacks.

    FMOD callbacks do not expose the authoring instrument GUID or encoded sample
    hash.  A source is therefore linked only when the event path plus raw name
    and callback-reported length/channel/rate tuple maps to exactly one static
    waveform placement.  The caller records anything else as unresolved.
    """

    raw_sound_name = row.get("rawSoundName", "").strip()
    length = optional_runtime_nonnegative_float(row.get("sampleLengthMs"), f"{description}.sampleLengthMs")
    channels = optional_runtime_nonnegative_int(row.get("sampleChannels"), f"{description}.sampleChannels")
    sample_rate = optional_runtime_nonnegative_int(row.get("sampleRateHz"), f"{description}.sampleRateHz")
    if not raw_sound_name and length is None and channels is None and sample_rate is None:
        return None
    # A zero-valued callback metadata tuple is FMOD's "unavailable" shape, not
    # a credible identity for an embedded waveform. Treat it as unresolved
    # rather than accidentally joining a voice-state snapshot to a source.
    if (
        not raw_sound_name
        or length is None
        or channels is None
        or sample_rate is None
        or length <= 0
        or channels <= 0
        or sample_rate <= 0
    ):
        return None
    return {
        "rawSoundName": raw_sound_name,
        "sampleLengthMs": round(length, 6),
        "sampleChannels": channels,
        "sampleRateHz": sample_rate,
    }


def native_trace_capture(trace_directory: Path) -> dict[str, Any]:
    """Normalize a debug trace directory without retaining its raw rows.

    The runtime telemetry writer intentionally runs outside this compiler and
    outside Git.  This reader turns the narrow lifecycle/callback evidence into
    compact counters that can be audited in the versioned inventory.
    """

    if not trace_directory.is_dir():
        raise InventoryError(f"runtime trace directory does not exist: {trace_directory}")
    metadata_path = trace_directory / "metadata.json"
    catalog_path = trace_directory / "bank_event_catalog.csv"
    native_path = trace_directory / "native.csv"
    require_file(metadata_path, "runtime trace metadata")
    try:
        metadata = require_runtime_mapping(json.loads(metadata_path.read_text(encoding="utf-8")), "metadata")
    except json.JSONDecodeError as error:
        raise InventoryError(f"runtime evidence {metadata_path} is not valid JSON") from error
    if metadata.get("format") != RUNTIME_TRACE_SCHEMA:
        raise InventoryError(
            f"runtime trace {metadata_path} has unsupported format {metadata.get('format')!r}; "
            f"expected {RUNTIME_TRACE_SCHEMA!r}"
        )
    if metadata.get("nativeSchema") != RUNTIME_NATIVE_LIFECYCLE_SCHEMA:
        raise InventoryError(
            f"runtime trace metadata.nativeSchema must be {RUNTIME_NATIVE_LIFECYCLE_SCHEMA!r}"
        )
    if metadata.get("bankEventCatalogSchema") != RUNTIME_EVENT_CATALOG_SCHEMA:
        raise InventoryError(
            f"runtime trace metadata.bankEventCatalogSchema must be {RUNTIME_EVENT_CATALOG_SCHEMA!r}"
        )
    profile_id = require_runtime_text(metadata.get("profileId"), "metadata.profileId")
    bank_sha256 = require_runtime_sha256(metadata.get("bankSha256"), "metadata.bankSha256")
    scenario = {
        "reason": require_runtime_text(metadata.get("reason"), "metadata.reason"),
        "inputMode": require_runtime_text(metadata.get("inputMode"), "metadata.inputMode"),
    }
    catalog_rows = read_runtime_csv(
        catalog_path,
        {"kind", "eventPath", "eventGuid", "eventSuffix", "appClassification"},
    )
    for row_number, row in enumerate(catalog_rows, start=2):
        if row.get("kind") != "BANK_EVENT_CATALOG":
            raise InventoryError(
                f"runtime evidence bank_event_catalog.csv:{row_number} has unexpected kind {row.get('kind')!r}"
            )
    catalog = runtime_event_catalog_from_rows(catalog_rows, "bank_event_catalog.csv")
    catalog_by_path = {item["eventPath"]: item for item in catalog}
    native_rows = read_runtime_csv(
        native_path,
        {
            "kind",
            "nativeTimestampSeconds",
            "simulationFrameId",
            "voiceSerial",
            "sampleDurationSeconds",
            "sampleLengthMs",
            "sampleChannels",
            "sampleRateHz",
            "fmodResult",
            "gear",
            "voiceCount",
            "virtualVoiceCount",
            "callbackVoiceCount",
            "audibility",
            "routeGain",
            "rpm",
            "drivetrainSpeed",
            "throttle",
            "boostNormalized",
            "boostAbsolute",
            "bov",
            "bovDecay",
            "shiftProgress",
            "shiftSerial",
            "stateFlags",
            "isShifting",
            "eventName",
            "eventPath",
            "rawSoundName",
        },
    )
    allowed_kinds = {
        "FRAME",
        "EVENT_START",
        "EVENT_STOP",
        "VOICE_PLAYED",
        "VOICE_STOPPED",
        "VOICE_STATE",
    }
    event_metrics: dict[str, dict[str, Any]] = defaultdict(runtime_metrics_template)
    source_metrics: dict[tuple[str, str, float, int, int], dict[str, Any]] = {}
    unresolved_sources: dict[tuple[str, str, str, str, str], dict[str, Any]] = {}
    source_voice_serials: dict[tuple[str, str, float, int, int], set[int]] = defaultdict(set)
    for row_number, row in enumerate(native_rows, start=2):
        kind = require_runtime_text(row.get("kind"), f"native.csv:{row_number}.kind")
        if kind not in allowed_kinds:
            raise InventoryError(f"runtime evidence native.csv:{row_number} has unsupported kind {kind!r}")
        event_path = row.get("eventPath", "").strip()
        if kind == "FRAME" and not event_path:
            continue
        if not event_path or event_path not in catalog_by_path:
            raise InventoryError(
                f"runtime evidence native.csv:{row_number} has an event path absent from bank_event_catalog.csv: "
                f"{event_path!r}"
            )
        metrics = event_metrics[event_path]
        if kind == "EVENT_START":
            metrics["eventStartedCount"] += 1
        elif kind == "EVENT_STOP":
            metrics["eventStoppedCount"] += 1
        elif kind == "VOICE_PLAYED":
            metrics["soundPlayedCount"] += 1
        elif kind == "VOICE_STOPPED":
            metrics["soundStoppedCount"] += 1
        elif kind == "VOICE_STATE":
            metrics["voiceStateSampleCount"] += 1
        for column, key in (
            ("voiceCount", "maxVoiceCount"),
            ("virtualVoiceCount", "maxVirtualVoiceCount"),
            ("callbackVoiceCount", "maxCallbackVoiceCount"),
        ):
            candidate = optional_runtime_nonnegative_int(row.get(column), f"native.csv:{row_number}.{column}")
            if candidate is not None:
                metrics[key] = max(metrics[key], candidate)
        for column, key in (("audibility", "maxAudibility"), ("routeGain", "maxRouteGain")):
            candidate = optional_runtime_nonnegative_float(row.get(column), f"native.csv:{row_number}.{column}")
            if candidate is not None:
                metrics[key] = max(metrics[key], candidate)
        metrics["everAudible"] = metrics["everAudible"] or metrics["maxAudibility"] > 0
        metrics["everVirtual"] = metrics["everVirtual"] or metrics["maxVirtualVoiceCount"] > 0
        if kind not in {"VOICE_PLAYED", "VOICE_STOPPED", "VOICE_STATE"}:
            continue
        descriptor = source_descriptor_from_native_row(row, f"native.csv:{row_number}")
        if descriptor is None:
            # Voice-state rows can legitimately omit callback sample metadata.
            # Preserve the evidence at event level and explain why it cannot be
            # attributed to a static source placement.
            if kind in {"VOICE_PLAYED", "VOICE_STOPPED"}:
                unresolved_key = (event_path, kind, row.get("rawSoundName", ""), row.get("sampleLengthMs", ""), row.get("sampleRateHz", ""))
                unresolved = unresolved_sources.setdefault(
                    unresolved_key,
                    {
                        "eventPath": event_path,
                        "kind": kind,
                        "reason": "callback source metadata was incomplete; static source identity was not guessed",
                        "rawSoundName": row.get("rawSoundName", "").strip() or None,
                    },
                )
                unresolved["count"] = int(unresolved.get("count", 0)) + 1
            continue
        key = (
            event_path,
            descriptor["rawSoundName"],
            descriptor["sampleLengthMs"],
            descriptor["sampleChannels"],
            descriptor["sampleRateHz"],
        )
        source = source_metrics.setdefault(key, {**descriptor, "eventPath": event_path, **runtime_metrics_template()})
        if kind == "VOICE_PLAYED":
            source["soundPlayedCount"] += 1
        elif kind == "VOICE_STOPPED":
            source["soundStoppedCount"] += 1
        elif kind == "VOICE_STATE":
            source["voiceStateSampleCount"] += 1
        for column, metric_key in (
            ("voiceCount", "maxVoiceCount"),
            ("virtualVoiceCount", "maxVirtualVoiceCount"),
            ("callbackVoiceCount", "maxCallbackVoiceCount"),
        ):
            candidate = optional_runtime_nonnegative_int(row.get(column), f"native.csv:{row_number}.{column}")
            if candidate is not None:
                source[metric_key] = max(source[metric_key], candidate)
        for column, metric_key in (("audibility", "maxAudibility"), ("routeGain", "maxRouteGain")):
            candidate = optional_runtime_nonnegative_float(row.get(column), f"native.csv:{row_number}.{column}")
            if candidate is not None:
                source[metric_key] = max(source[metric_key], candidate)
        source["everAudible"] = source["everAudible"] or source["maxAudibility"] > 0
        source["everVirtual"] = source["everVirtual"] or source["maxVirtualVoiceCount"] > 0
        serial = optional_runtime_nonnegative_int(row.get("voiceSerial"), f"native.csv:{row_number}.voiceSerial")
        if serial is not None:
            source_voice_serials[key].add(serial)
    for key, serials in source_voice_serials.items():
        source_metrics[key]["distinctVoiceCount"] = len(serials)
    capture = {
        "profileId": profile_id,
        "bankSha256": bank_sha256,
        "scenario": scenario,
        "eventCatalog": catalog,
        "eventObservations": [
            {"eventPath": path, "eventGuid": catalog_by_path[path]["eventGuid"], "metrics": summarize_runtime_metrics(metrics)}
            for path, metrics in sorted(event_metrics.items())
        ],
        "sourceObservations": [
            {
                "eventPath": source["eventPath"],
                "eventGuid": catalog_by_path[source["eventPath"]]["eventGuid"],
                "rawSoundName": source["rawSoundName"],
                "sampleLengthMs": source["sampleLengthMs"],
                "sampleChannels": source["sampleChannels"],
                "sampleRateHz": source["sampleRateHz"],
                "metrics": summarize_runtime_metrics(source),
            }
            for _key, source in sorted(source_metrics.items())
        ],
        "unresolvedSourceObservations": sorted(
            unresolved_sources.values(),
            key=lambda item: (item["eventPath"], item.get("rawSoundName") or "", item["kind"]),
        ),
        "inputProvenance": {
            "kind": "debugTraceDirectory",
            "files": {
                "metadata.json": sha256_file(metadata_path),
                "bank_event_catalog.csv": sha256_file(catalog_path),
                "native.csv": sha256_file(native_path),
            },
        },
    }
    capture["captureId"] = runtime_capture_id(capture)
    return capture


def runtime_event_catalog_from_objects(
    value: object,
    description: str,
) -> list[dict[str, str]]:
    if not isinstance(value, list):
        raise InventoryError(f"runtime evidence {description} must be a JSON array")
    rows: list[dict[str, str]] = []
    for index, item in enumerate(value, start=1):
        record = require_runtime_mapping(item, f"{description}[{index}]")
        rows.append(
            {
                "eventPath": require_runtime_text(record.get("eventPath"), f"{description}[{index}].eventPath"),
                "eventGuid": require_runtime_text(record.get("eventGuid"), f"{description}[{index}].eventGuid"),
                "eventSuffix": require_runtime_text(record.get("eventSuffix"), f"{description}[{index}].eventSuffix"),
                "appClassification": require_runtime_text(
                    record.get("appClassification"), f"{description}[{index}].appClassification"
                ),
            }
        )
    return runtime_event_catalog_from_rows(rows, description)


def normalize_runtime_summary_capture(
    value: object,
    *,
    description: str,
    summary_sha256: str,
) -> dict[str, Any]:
    """Validate the compact no-raw-trace interchange contract.

    A summary may be produced by ``--runtime-trace-dir`` elsewhere and passed
    back later with ``--runtime-summary``.  It is intentionally small enough to
    version only the diagnosis evidence, not every high-rate trace row.
    """

    record = require_runtime_mapping(value, description)
    profile_id = require_runtime_text(record.get("profileId"), f"{description}.profileId")
    bank_sha256 = require_runtime_sha256(record.get("bankSha256"), f"{description}.bankSha256")
    scenario_value = require_runtime_mapping(record.get("scenario"), f"{description}.scenario")
    scenario = {
        "reason": require_runtime_text(scenario_value.get("reason"), f"{description}.scenario.reason"),
        "inputMode": require_runtime_text(
            scenario_value.get("inputMode"), f"{description}.scenario.inputMode"
        ),
    }
    catalog = runtime_event_catalog_from_objects(record.get("eventCatalog"), f"{description}.eventCatalog")
    catalog_by_path = {item["eventPath"]: item for item in catalog}
    event_observations_value = record.get("eventObservations")
    if not isinstance(event_observations_value, list):
        raise InventoryError(f"runtime evidence {description}.eventObservations must be a JSON array")
    event_observations: list[dict[str, Any]] = []
    observed_event_paths: set[str] = set()
    for index, item in enumerate(event_observations_value, start=1):
        observation = require_runtime_mapping(item, f"{description}.eventObservations[{index}]")
        path = require_runtime_text(observation.get("eventPath"), f"{description}.eventObservations[{index}].eventPath")
        expected_catalog = catalog_by_path.get(path)
        if expected_catalog is None:
            raise InventoryError(
                f"runtime evidence {description}.eventObservations[{index}] references event outside its catalog: {path}"
            )
        guid = guid_text(
            require_runtime_text(observation.get("eventGuid"), f"{description}.eventObservations[{index}].eventGuid")
        )
        if guid != expected_catalog["eventGuid"]:
            raise InventoryError(
                f"runtime evidence {description}.eventObservations[{index}] GUID disagrees with its catalog"
            )
        if path in observed_event_paths:
            raise InventoryError(f"runtime evidence {description} duplicates event observation for {path}")
        observed_event_paths.add(path)
        event_observations.append(
            {
                "eventPath": path,
                "eventGuid": guid,
                "metrics": summarize_runtime_metrics(
                    normalize_runtime_metrics(
                        require_runtime_mapping(
                            observation.get("metrics"), f"{description}.eventObservations[{index}].metrics"
                        ),
                        f"{description}.eventObservations[{index}].metrics",
                    )
                ),
            }
        )
    source_observations_value = record.get("sourceObservations")
    if not isinstance(source_observations_value, list):
        raise InventoryError(f"runtime evidence {description}.sourceObservations must be a JSON array")
    source_observations: list[dict[str, Any]] = []
    source_keys: set[tuple[str, str, float, int, int, str]] = set()
    for index, item in enumerate(source_observations_value, start=1):
        observation = require_runtime_mapping(item, f"{description}.sourceObservations[{index}]")
        path = require_runtime_text(observation.get("eventPath"), f"{description}.sourceObservations[{index}].eventPath")
        expected_catalog = catalog_by_path.get(path)
        if expected_catalog is None:
            raise InventoryError(
                f"runtime evidence {description}.sourceObservations[{index}] references event outside its catalog: {path}"
            )
        guid = guid_text(
            require_runtime_text(observation.get("eventGuid"), f"{description}.sourceObservations[{index}].eventGuid")
        )
        if guid != expected_catalog["eventGuid"]:
            raise InventoryError(
                f"runtime evidence {description}.sourceObservations[{index}] GUID disagrees with its catalog"
            )
        stable_id_value = observation.get("stableId")
        stable_id = require_runtime_text(stable_id_value, f"{description}.sourceObservations[{index}].stableId") if stable_id_value else None
        raw_sound_name = require_runtime_text(
            observation.get("rawSoundName"), f"{description}.sourceObservations[{index}].rawSoundName"
        )
        length = require_runtime_nonnegative_float(
            observation.get("sampleLengthMs"), f"{description}.sourceObservations[{index}].sampleLengthMs"
        )
        channels = require_runtime_nonnegative_int(
            observation.get("sampleChannels"), f"{description}.sourceObservations[{index}].sampleChannels"
        )
        sample_rate = require_runtime_nonnegative_int(
            observation.get("sampleRateHz"), f"{description}.sourceObservations[{index}].sampleRateHz"
        )
        source_key = (path, raw_sound_name, round(length, 6), channels, sample_rate, stable_id or "")
        if source_key in source_keys:
            raise InventoryError(f"runtime evidence {description} duplicates source observation {source_key!r}")
        source_keys.add(source_key)
        source_observations.append(
            {
                "eventPath": path,
                "eventGuid": guid,
                "rawSoundName": raw_sound_name,
                "sampleLengthMs": round(length, 6),
                "sampleChannels": channels,
                "sampleRateHz": sample_rate,
                **({"stableId": stable_id} if stable_id else {}),
                "metrics": summarize_runtime_metrics(
                    normalize_runtime_metrics(
                        require_runtime_mapping(
                            observation.get("metrics"), f"{description}.sourceObservations[{index}].metrics"
                        ),
                        f"{description}.sourceObservations[{index}].metrics",
                    )
                ),
            }
        )
    unresolved_value = record.get("unresolvedSourceObservations", [])
    if not isinstance(unresolved_value, list):
        raise InventoryError(f"runtime evidence {description}.unresolvedSourceObservations must be a JSON array")
    unresolved: list[dict[str, Any]] = []
    for index, item in enumerate(unresolved_value, start=1):
        unresolved_record = require_runtime_mapping(item, f"{description}.unresolvedSourceObservations[{index}]")
        path = require_runtime_text(
            unresolved_record.get("eventPath"), f"{description}.unresolvedSourceObservations[{index}].eventPath"
        )
        if path not in catalog_by_path:
            raise InventoryError(
                f"runtime evidence {description}.unresolvedSourceObservations[{index}] references event outside its catalog"
            )
        unresolved.append(
            {
                "eventPath": path,
                "kind": require_runtime_text(
                    unresolved_record.get("kind"), f"{description}.unresolvedSourceObservations[{index}].kind"
                ),
                "reason": require_runtime_text(
                    unresolved_record.get("reason"), f"{description}.unresolvedSourceObservations[{index}].reason"
                ),
                "rawSoundName": (
                    require_runtime_text(
                        unresolved_record.get("rawSoundName"),
                        f"{description}.unresolvedSourceObservations[{index}].rawSoundName",
                    )
                    if unresolved_record.get("rawSoundName")
                    else None
                ),
                "count": require_runtime_nonnegative_int(
                    unresolved_record.get("count", 1), f"{description}.unresolvedSourceObservations[{index}].count"
                ),
            }
        )
    provenance_value = record.get("inputProvenance")
    if provenance_value is not None:
        provenance = require_runtime_mapping(provenance_value, f"{description}.inputProvenance")
    else:
        provenance = {"kind": "compactSummary", "summarySha256": summary_sha256}
    capture = {
        "profileId": profile_id,
        "bankSha256": bank_sha256,
        "scenario": scenario,
        "eventCatalog": catalog,
        "eventObservations": sorted(event_observations, key=lambda item: item["eventPath"]),
        "sourceObservations": sorted(
            source_observations,
            key=lambda item: (
                item["eventPath"],
                item["rawSoundName"],
                item["sampleLengthMs"],
                item["sampleChannels"],
                item["sampleRateHz"],
                item.get("stableId", ""),
            ),
        ),
        "unresolvedSourceObservations": sorted(
            unresolved, key=lambda item: (item["eventPath"], item.get("rawSoundName") or "", item["kind"])
        ),
        "inputProvenance": dict(provenance),
    }
    capture_id = record.get("captureId")
    calculated_capture_id = runtime_capture_id(capture)
    if capture_id is not None and require_runtime_sha256(capture_id, f"{description}.captureId") != calculated_capture_id:
        raise InventoryError(f"runtime evidence {description}.captureId does not match its compact evidence content")
    capture["captureId"] = calculated_capture_id
    return capture


def runtime_summary_captures(path: Path) -> list[dict[str, Any]]:
    """Read a compact, no-raw-trace runtime summary produced outside Git."""

    require_file(path, "runtime summary")
    try:
        root = require_runtime_mapping(json.loads(path.read_text(encoding="utf-8")), "runtime summary")
    except json.JSONDecodeError as error:
        raise InventoryError(f"runtime summary {path} is not valid JSON") from error
    if root.get("schema") != RUNTIME_SUMMARY_SCHEMA:
        raise InventoryError(
            f"runtime summary {path} has unsupported schema {root.get('schema')!r}; "
            f"expected {RUNTIME_SUMMARY_SCHEMA!r}"
        )
    if root.get("version") != RUNTIME_SUMMARY_VERSION:
        raise InventoryError(
            f"runtime summary {path} has unsupported version {root.get('version')!r}; "
            f"expected {RUNTIME_SUMMARY_VERSION}"
        )
    captures_value = root.get("captures")
    if not isinstance(captures_value, list) or not captures_value:
        raise InventoryError(f"runtime summary {path} must contain a non-empty captures array")
    summary_sha = sha256_file(path)
    captures = [
        normalize_runtime_summary_capture(
            item,
            description=f"runtime summary {path.name}.captures[{index}]",
            summary_sha256=summary_sha,
        )
        for index, item in enumerate(captures_value, start=1)
    ]
    return sorted(captures, key=lambda item: item["captureId"])


def runtime_summary_bundle(captures: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """Produce the portable compact evidence contract from parsed trace captures."""

    normalized_captures = [dict(capture) for capture in sorted(captures, key=lambda item: str(item["captureId"]))]
    return {
        "schema": RUNTIME_SUMMARY_SCHEMA,
        "version": RUNTIME_SUMMARY_VERSION,
        "purpose": "Compact Android FMOD runtime evidence for the original-car inventory. Raw high-frequency traces remain outside Git.",
        "captures": normalized_captures,
    }


def static_source_candidates(
    event: Mapping[str, Any],
    observation: Mapping[str, Any],
) -> list[Mapping[str, Any]]:
    """Return only exact/unique callback-to-static source candidates.

    Callback sample duration is in milliseconds and may be rounded by FMOD.  A
    one-millisecond tolerance is sufficient for that representation; if it
    leaves more than one authoring placement, the caller must retain an
    unresolved runtime observation instead of attributing it to a card.
    """

    stable_id = observation.get("stableId")
    raw_name = observation["rawSoundName"]
    length_ms = float(observation["sampleLengthMs"])
    channels = int(observation["sampleChannels"])
    sample_rate = int(observation["sampleRateHz"])
    candidates: list[Mapping[str, Any]] = []
    for source in event["sources"]:
        identity = source["identity"]
        sample = source["authoredFact"]["sample"]
        if stable_id and identity["stableId"] != stable_id:
            continue
        if source["authoredFact"].get("rawSoundName") != raw_name:
            continue
        static_duration = sample.get("durationSeconds")
        if static_duration is None or abs(float(static_duration) * 1000.0 - length_ms) > 1.0:
            continue
        if sample.get("channels") != channels or sample.get("frequencyHz") != sample_rate:
            continue
        candidates.append(source)
    return candidates


def runtime_evidence_record(
    capture: Mapping[str, Any],
    metrics: Mapping[str, Any],
    *,
    catalog_classification: str,
) -> dict[str, Any]:
    return {
        "captureId": capture["captureId"],
        "scenario": dict(capture["scenario"]),
        "catalogAppClassification": catalog_classification,
        "metrics": summarize_runtime_metrics(normalize_runtime_metrics(metrics, "normalized runtime metrics")),
    }


def apply_runtime_captures(inventory: dict[str, Any], captures: Sequence[Mapping[str, Any]]) -> None:
    """Join compact runtime evidence onto static identities without guessing.

    The full event catalog must agree with the static GUID/text mapping before a
    trace can alter the inventory.  Source evidence is stricter still: it must
    resolve to one stable waveform placement or remains an explicit unresolved
    callback observation under the affected car.
    """

    if not captures:
        inventory["runtimeEvidence"] = {
            "status": "notImported",
            "meaning": "No Android runtime summary was supplied. Every runtime observation remains notCaptured.",
            "captureCount": 0,
        }
        return
    cars_by_id = {str(car["id"]): car for car in inventory["cars"]}
    # A car bank can depend on another original bank that is already loaded by the
    # Android bridge.  The catalog callback therefore may contain valid events that
    # do not belong to the selected profile (for example Alfa's shared event bank
    # while a Ferrari is open).  Keep the selected profile's exact event set as the
    # authoritative join target, but validate those dependency entries against the
    # complete static inventory instead of silently treating them as a wrong bank.
    all_static_event_guids = {
        event["identity"]["path"]: guid_text(event["identity"]["guid"])
        for car in inventory["cars"]
        for event in car["events"]
    }
    capture_ids: set[str] = set()
    captures_by_car: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    shared_dependency_paths_by_capture: dict[str, list[str]] = {}
    event_evidence: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    source_evidence: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    car_unresolved: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for capture in sorted(captures, key=lambda item: str(item["captureId"])):
        capture_id = str(capture["captureId"])
        if capture_id in capture_ids:
            raise InventoryError(f"runtime evidence contains duplicate capture ID {capture_id}")
        capture_ids.add(capture_id)
        profile_id = str(capture["profileId"])
        car = cars_by_id.get(profile_id)
        if car is None:
            raise InventoryError(f"runtime evidence references profile outside original catalog: {profile_id}")
        expected_bank_sha = str(car["source"]["bankSha256"])
        if capture["bankSha256"] != expected_bank_sha:
            raise InventoryError(
                f"runtime evidence {capture_id} bank SHA does not match {profile_id}: "
                f"expected {expected_bank_sha}, got {capture['bankSha256']}"
            )
        static_events_by_path = {event["identity"]["path"]: event for event in car["events"]}
        static_event_guids = {
            event["identity"]["path"]: guid_text(event["identity"]["guid"])
            for event in car["events"]
        }
        catalog_by_path = {entry["eventPath"]: entry for entry in capture["eventCatalog"]}
        missing = sorted(set(static_events_by_path) - set(catalog_by_path))
        unexpected = sorted(set(catalog_by_path) - set(static_events_by_path))
        unknown_unexpected = sorted(
            path for path in unexpected if path not in all_static_event_guids
        )
        if missing or unknown_unexpected:
            raise InventoryError(
                f"runtime evidence {capture_id} event catalog does not match static {profile_id}; "
                f"missing={missing}, unknownUnexpected={unknown_unexpected}, "
                f"knownSharedDependencies={sorted(set(unexpected) - set(unknown_unexpected))}"
            )
        for path, catalog in catalog_by_path.items():
            expected_guid = static_event_guids.get(path) or all_static_event_guids.get(path)
            if expected_guid is None:
                # This is guarded above, but keeping the check local makes a future
                # refactor fail closed before it can index a missing GUID.
                raise InventoryError(f"runtime evidence {capture_id} has no static identity for {path}")
            if catalog["eventGuid"] != expected_guid:
                raise InventoryError(
                    f"runtime evidence {capture_id} GUID mismatch for {path}: "
                    f"expected {expected_guid}, got {catalog['eventGuid']}"
                )
        shared_dependency_paths_by_capture[capture_id] = sorted(
            path for path in unexpected if path in all_static_event_guids
        )
        captures_by_car[profile_id].append(capture)
        for observation in capture["eventObservations"]:
            path = observation["eventPath"]
            if path not in static_events_by_path:
                # Dependency events are still catalog-validated above, but this
                # selected-car inventory must not attribute their runtime metrics
                # to the selected profile's own event/source cards.
                continue
            event_evidence[(profile_id, path)].append(
                runtime_evidence_record(
                    capture,
                    observation["metrics"],
                    catalog_classification=catalog_by_path[path]["appClassification"],
                )
            )
        for observation in capture["sourceObservations"]:
            path = observation["eventPath"]
            if path not in static_events_by_path:
                continue
            event = static_events_by_path[path]
            candidates = static_source_candidates(event, observation)
            if len(candidates) != 1:
                car_unresolved[profile_id].append(
                    {
                        "kind": "runtimeSourceJoin",
                        "captureId": capture_id,
                        "eventPath": path,
                        "rawSoundName": observation["rawSoundName"],
                        "reason": (
                            "no matching static waveform placement"
                            if not candidates
                            else f"ambiguous callback-to-static source mapping ({len(candidates)} candidates)"
                        ),
                    }
                )
                continue
            stable_id = candidates[0]["identity"]["stableId"]
            source_evidence[(profile_id, stable_id)].append(
                runtime_evidence_record(
                    capture,
                    observation["metrics"],
                    catalog_classification=catalog_by_path[path]["appClassification"],
                )
            )
        for unresolved in capture["unresolvedSourceObservations"]:
            if unresolved.get("eventPath") not in static_events_by_path:
                continue
            car_unresolved[profile_id].append(
                {"kind": "runtimeCallback", "captureId": capture_id, **dict(unresolved)}
            )
    for profile_id, car in cars_by_id.items():
        applicable_captures = captures_by_car.get(profile_id, [])
        if not applicable_captures:
            continue
        capture_id_list = [str(capture["captureId"]) for capture in applicable_captures]
        observed_event_count = 0
        observed_source_count = 0
        for event in car["events"]:
            path = event["identity"]["path"]
            evidence = sorted(event_evidence.get((profile_id, path), []), key=lambda item: item["captureId"])
            if evidence:
                observed_event_count += 1
                event["runtimeObservation"] = {
                    "status": "observed",
                    "meaning": "Imported Android debug lifecycle/voice evidence for this exact cataloged event.",
                    "evidence": evidence,
                }
            else:
                event["runtimeObservation"] = runtime_not_observed(capture_id_list)
            for source in event["sources"]:
                stable_id = source["identity"]["stableId"]
                evidence = sorted(source_evidence.get((profile_id, stable_id), []), key=lambda item: item["captureId"])
                if evidence:
                    observed_source_count += 1
                    source["runtimeObservation"] = {
                        "status": "observed",
                        "meaning": "Imported Android callback evidence joined to this one static waveform placement.",
                        "evidence": evidence,
                    }
                else:
                    source["runtimeObservation"] = runtime_not_observed(capture_id_list)
        car["runtimeObservation"] = {
            "status": "catalogValidated",
            "meaning": "Every selected-car event path/GUID matched the Android pre-filter event catalog. Known events from shared original dependencies were validated against the global static GUID map and excluded from this car's event/source joins. Per-event and per-source evidence remains scenario-bounded.",
            "captureIds": sorted(capture_id_list),
            "observedEventCount": observed_event_count,
            "observedSourceCount": observed_source_count,
            "unresolvedSourceObservationCount": len(car_unresolved[profile_id]),
            "sharedDependencyEventPaths": sorted(
                {
                    path
                    for capture_id in capture_id_list
                    for path in shared_dependency_paths_by_capture.get(capture_id, [])
                }
            ),
        }
        car["unresolved"].extend(
            sorted(
                car_unresolved[profile_id],
                key=lambda item: (
                    item["captureId"],
                    item.get("eventPath", ""),
                    item.get("rawSoundName") or "",
                    item["kind"],
                ),
            )
        )
    inventory["runtimeEvidence"] = {
        "status": "imported",
        "meaning": "Compact debug evidence was joined only after exact profile, bank SHA, and complete selected-car event catalog validation. Known shared original dependency events are validated globally and excluded from selected-car attribution. Source joins are explicit and may remain unresolved rather than guessed.",
        "captureCount": len(captures),
        "captureIds": sorted(capture_ids),
        "profilesWithCaptureCount": len(captures_by_car),
    }


def compact_base_properties(properties: Mapping[str, Any]) -> dict[str, Any]:
    keys = (
        "volumeDb",
        "pitchSemitones",
        "loopCount",
        "autoPitchReference",
        "autoPitchAtMinimum",
        "initialSeekPosition",
        "initialSeekPercent",
        "triggerChancePercent",
        "routableGuid",
        "timelineGuid",
    )
    return {key: properties.get(key) for key in keys}


def source_record(
    *,
    bank_sha256: str,
    event: Mapping[str, Any],
    instrument: Mapping[str, Any],
    instruments: Mapping[str, Mapping[str, Any]],
    parents: Mapping[str, tuple[str, ...]],
    controllers: Mapping[str, Mapping[str, Any]],
    role_by_source_guid: Mapping[str, Mapping[str, Any]],
    app_policy: Mapping[str, Any],
) -> dict[str, Any]:
    source_guid = guid_text(instrument.get("guid"))
    sample = instrument.get("sample")
    if not source_guid or not isinstance(sample, Mapping):
        raise InventoryError(f"event {event.get('path')}: malformed waveform instrument")
    encoded_sha = str(sample.get("encodedPayloadSha256") or "")
    if not encoded_sha:
        raise InventoryError(f"source {source_guid}: sample has no payload SHA-256")
    properties = instrument.get("baseProperties")
    if not isinstance(properties, Mapping):
        raise InventoryError(f"source {source_guid}: base properties are absent")
    ancestry = source_ancestry(source_guid, instruments, parents)
    conditions = source_conditions(event, source_guid, ancestry, controllers)
    interpretation = role_by_source_guid.get(source_guid)
    if interpretation is None:
        raise InventoryError(f"source {source_guid}: role classifier did not emit a record")
    return {
        "identity": {
            "stableId": f"{bank_sha256}:{guid_text(event.get('guid'))}:{source_guid}:{encoded_sha}",
            "eventGuid": guid_text(event.get("guid")),
            "eventPath": event.get("path"),
            "instrumentGuid": source_guid,
            "samplePayloadSha256": encoded_sha,
        },
        "authoredFact": {
            "rawSoundName": sample.get("name"),
            "sample": {
                "waveformResourceGuid": guid_text(sample.get("waveformResourceGuid")),
                "soundBankIndex": sample.get("soundBankIndex"),
                "subsoundIndex": sample.get("subsoundIndex"),
                "encodedPayloadBytes": sample.get("encodedPayloadBytes"),
                "frequencyHz": sample.get("frequencyHz"),
                "channels": sample.get("channels"),
                "sampleCount": sample.get("sampleCount"),
                "durationSeconds": round_duration(sample),
            },
            "instrument": {
                "kind": instrument.get("kind"),
                "lifetime": loop_lifetime(properties),
                "baseProperties": compact_base_properties(properties),
            },
            "conditions": conditions,
        },
        "staticInterpretation": {
            "role": interpretation.get("role"),
            "selectionPolicy": interpretation.get("policy"),
            "eventClass": interpretation.get("eventClass"),
            "candidateRoles": interpretation.get("candidateManifestRoles"),
            "reasons": interpretation.get("reasons"),
            "throttleVolume": interpretation.get("throttleVolume"),
            "meaning": "Derived from graph topology and curves; it is not a runtime playback observation.",
        },
        "appPolicy": dict(app_policy),
        "runtimeObservation": runtime_not_captured(),
        "unresolved": [],
    }


def event_record(
    *,
    event: Mapping[str, Any],
    bank_sha256: str,
    instruments: Mapping[str, Mapping[str, Any]],
    parents: Mapping[str, tuple[str, ...]],
    controllers: Mapping[str, Mapping[str, Any]],
    role_by_source_guid: Mapping[str, Mapping[str, Any]],
    has_turbo_physics: bool,
) -> dict[str, Any]:
    path = str(event.get("path") or "")
    suffix = event_suffix(path)
    policy = app_policy_for_event(suffix, has_turbo_physics=has_turbo_physics)
    reachable = event.get("reachableInstrumentGuids")
    if not isinstance(reachable, list):
        raise InventoryError(f"event {path}: reachable instrument list is absent")
    sources: list[dict[str, Any]] = []
    for value in reachable:
        instrument = instruments.get(guid_text(value))
        if instrument is None:
            raise InventoryError(f"event {path}: reachable instrument {value!r} is absent")
        if instrument.get("kind") != "WaveformInstrumentNode":
            continue
        sources.append(
            source_record(
                bank_sha256=bank_sha256,
                event=event,
                instrument=instrument,
                instruments=instruments,
                parents=parents,
                controllers=controllers,
                role_by_source_guid=role_by_source_guid,
                app_policy=policy,
            )
        )
    sources.sort(
        key=lambda item: (
            str(item["authoredFact"].get("rawSoundName") or ""),
            item["identity"]["instrumentGuid"],
        )
    )
    return {
        "identity": {"guid": guid_text(event.get("guid")), "path": path, "suffix": suffix},
        "authoredFact": {
            "mappingComplete": event.get("mappingComplete") is True,
            "parameterLayouts": sorted(guid_text(value) for value in event.get("parameterLayoutGuids", [])),
            "parameterPlacements": sorted(
                (dict(value) for value in event.get("parameterPlacements", []) if isinstance(value, Mapping)),
                key=lambda item: (
                    str(item.get("parameterName") or ""),
                    float(item.get("start") or 0),
                    str(item.get("instrumentGuid") or ""),
                ),
            ),
            "timelinePlacements": sorted(
                (dict(value) for value in event.get("timelinePlacements", []) if isinstance(value, Mapping)),
                key=lambda item: (float(item.get("startTime") or 0), str(item.get("instrumentGuid") or "")),
            ),
            "sourceCount": len(sources),
        },
        "appPolicy": policy,
        "runtimeObservation": runtime_not_captured(),
        "sources": sources,
        "unresolved": [],
    }


def physics_summary(physics: Mapping[str, Any]) -> dict[str, Any]:
    car = physics.get("car")
    drivetrain = physics.get("drivetrain")
    if not isinstance(car, Mapping) or not isinstance(drivetrain, Mapping):
        raise InventoryError("physics export lacks car or drivetrain object")
    turbo_list = car.get("turbos")
    if not isinstance(turbo_list, (list, tuple)):
        raise InventoryError("physics export lacks turbo list")
    return {
        "idleRpm": car.get("idle_rpm"),
        "limiterRpm": car.get("limiter_rpm"),
        "tachometerMaximumRpm": car.get("tachometer_maximum"),
        "shiftLightRpm": car.get("shift_blink_rpm"),
        "autoUpRpm": drivetrain.get("auto_up_rpm"),
        "autoDownRpm": drivetrain.get("auto_down_rpm"),
        "gearUpTimeSeconds": drivetrain.get("gear_up_time_s"),
        "gearDownTimeSeconds": drivetrain.get("gear_down_time_s"),
        "forwardGearCount": len(drivetrain.get("forward_ratios", [])),
        "turboCount": len(turbo_list),
        "turbo": list(turbo_list),
        "backfire": car.get("backfire"),
    }


def classify_sources(report: dict[str, Any]) -> dict[str, Mapping[str, Any]]:
    if str(AUDIO_LAB) not in sys.path:
        sys.path.insert(0, str(AUDIO_LAB))
    try:
        from sim.fmod_graph_roles import classify_bank_graph_sources
    except Exception as error:
        raise InventoryError(f"could not import the tracked graph role classifier: {error}") from error
    try:
        classification = classify_bank_graph_sources(report)
    except Exception as error:
        raise InventoryError(f"graph role classification failed: {error}") from error
    records = classification.get("sources")
    if not isinstance(records, list):
        raise InventoryError("graph role classifier returned no source records")
    result: dict[str, Mapping[str, Any]] = {}
    for record in records:
        if not isinstance(record, Mapping):
            raise InventoryError("graph role classifier returned a malformed source record")
        source_guid = guid_text(record.get("sourceGuid"))
        if not source_guid or source_guid in result:
            raise InventoryError(f"graph role classifier emitted duplicate/missing source GUID {source_guid!r}")
        result[source_guid] = record
    return result


def compile_car(
    source: Any,
    *,
    pack_builder: Any,
    guid_paths: Mapping[str, tuple[str, ...]],
) -> dict[str, Any]:
    expected_directory = INSTALLATION / "content" / "cars" / source.source_directory.name
    if source.group != pack_builder.ORIGINAL_GROUP or not source.active:
        raise InventoryError(f"{source.pack_id}: source is not an active original profile")
    if source.source_directory.resolve() != expected_directory.resolve():
        raise InventoryError(f"{source.pack_id}: source directory is outside the official installation")
    expected_bank_parent = expected_directory / "sfx"
    if source.bank_path.resolve().parent != expected_bank_parent.resolve():
        raise InventoryError(f"{source.pack_id}: bank is not located at its official sfx directory")
    data_acd = expected_directory / "data.acd"
    require_file(source.bank_path, f"{source.pack_id} source bank")
    require_file(data_acd, f"{source.pack_id} original physics data.acd")

    physics = pack_builder.load_physics(source)
    pack_builder.validate_physics(source, physics)
    physics_bytes = pack_builder.canonical_physics_bytes(physics)
    report = read_graph(source.bank_path)
    resolve_event_paths(report, source_car_id=source.source_directory.name, guid_paths=guid_paths)
    validate_graph(report, source.bank_path)
    role_by_source_guid = classify_sources(report)

    raw_instruments = report.get("instruments")
    raw_controllers = report.get("controllers")
    raw_events = report.get("events")
    assert isinstance(raw_instruments, list) and isinstance(raw_controllers, list) and isinstance(raw_events, list)
    instruments: dict[str, Mapping[str, Any]] = {}
    for value in raw_instruments:
        if not isinstance(value, Mapping):
            raise InventoryError(f"{source.pack_id}: graph contains malformed instrument")
        guid = guid_text(value.get("guid"))
        if not guid or guid in instruments:
            raise InventoryError(f"{source.pack_id}: duplicate/missing instrument GUID {guid!r}")
        instruments[guid] = value
    controllers: dict[str, Mapping[str, Any]] = {}
    for value in raw_controllers:
        if not isinstance(value, Mapping):
            raise InventoryError(f"{source.pack_id}: graph contains malformed controller")
        guid = guid_text(value.get("guid"))
        if not guid or guid in controllers:
            raise InventoryError(f"{source.pack_id}: duplicate/missing controller GUID {guid!r}")
        controllers[guid] = value
    parents = instrument_parent_map(instruments)
    summary = physics_summary(physics)
    events = [
        event_record(
            event=event,
            bank_sha256=sha256_file(source.bank_path),
            instruments=instruments,
            parents=parents,
            controllers=controllers,
            role_by_source_guid=role_by_source_guid,
            has_turbo_physics=summary["turboCount"] > 0,
        )
        for event in raw_events
        if isinstance(event, Mapping)
    ]
    if len(events) != len(raw_events):
        raise InventoryError(f"{source.pack_id}: graph event list is malformed")
    events.sort(key=lambda item: item["identity"]["path"])
    event_suffixes = [event["identity"]["suffix"] for event in events]
    for required_engine_event in ("engine_int", "engine_ext"):
        if event_suffixes.count(required_engine_event) != 1:
            raise InventoryError(
                f"{source.pack_id}: expected exactly one {required_engine_event} event, "
                f"found {event_suffixes.count(required_engine_event)}"
            )
    return {
        "id": source.pack_id,
        "displayName": source.display_name,
        "scope": "original_cars_pack",
        "source": {
            "carDirectory": f"content/cars/{source.source_directory.name}",
            "bankPath": f"content/cars/{source.source_directory.name}/sfx/{source.bank_path.name}",
            "bankSha256": sha256_file(source.bank_path),
            "bankBytes": source.bank_path.stat().st_size,
            "dataAcdSha256": sha256_file(data_acd),
            "physicsJsonSha256": sha256_bytes(physics_bytes),
            "physicsSchema": physics.get("schema"),
            "sourceCarId": physics.get("sourceCarId"),
        },
        "physicsSummary": summary,
        "staticAudit": {
            "bankGuid": report.get("bank", {}).get("bankGuid"),
            "fileVersion": report.get("bank", {}).get("fileVersion"),
            "counts": report.get("counts"),
            "coverage": report.get("coverage"),
            "unknownChunks": report.get("unknownChunks"),
            "featureKinds": report.get("featureKinds"),
            "confirmedEnginePair": {
                "engineInt": "engine_int" in event_suffixes,
                "engineExt": "engine_ext" in event_suffixes,
            },
            "authoredTransmissionEvents": {
                "transmission": event_suffixes.count("transmission"),
                "transmissionExt": event_suffixes.count("transmission_ext"),
            },
        },
        "events": events,
        "runtimeObservation": runtime_not_captured(),
        "unresolved": [],
    }


def validate_bridge_policy_source() -> dict[str, Any]:
    bridge = ROOT / "mobile" / "src" / "main" / "cpp" / "fmod_bank_bridge.cpp"
    require_file(bridge, "Android FMOD bridge")
    content = bridge.read_text(encoding="utf-8")
    expected_markers = (
        "kFullLoadAudioThrottle = 1.0f",
        "kBackfireAudioThrottle = 1.0f",
        "isPlayableEventName",
        "name != \"wind\" && name != \"tyres\"",
        "startSelectedContinuousEventsLocked",
        "tractionDecay_ = 10.0f",
        "hostEngineGain_",
        "hostEffectsGain_",
    )
    missing = [marker for marker in expected_markers if marker not in content]
    if missing:
        raise InventoryError(
            "fmod_bank_bridge.cpp changed its reviewed policy markers; update APP_EVENT_POLICIES "
            f"instead of generating stale documentation. Missing: {missing}"
        )
    return {"path": "mobile/src/main/cpp/fmod_bank_bridge.cpp", "sha256": sha256_file(bridge)}


def collect_runtime_captures(
    runtime_trace_directories: Sequence[Path],
    runtime_summary_paths: Sequence[Path],
) -> list[dict[str, Any]]:
    """Collect external evidence in a stable order before static compilation.

    Trace directories are deliberately not copied into the repository.  Passing
    ``--runtime-summary`` later is equivalent to passing the original trace
    directory, provided the summary was emitted by this compiler's compact
    schema and still carries the same capture identity.
    """

    captures: list[dict[str, Any]] = []
    for path in sorted((item.resolve() for item in runtime_trace_directories), key=str):
        captures.append(native_trace_capture(path))
    for path in sorted((item.resolve() for item in runtime_summary_paths), key=str):
        captures.extend(runtime_summary_captures(path))
    capture_ids = [str(capture["captureId"]) for capture in captures]
    if len(capture_ids) != len(set(capture_ids)):
        raise InventoryError("the same runtime capture was supplied more than once")
    return sorted(captures, key=lambda item: str(item["captureId"]))


def compile_inventory(
    parser_root: Path,
    guid_path: Path,
    *,
    runtime_captures: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    pack_builder = import_module(ROOT / "tools" / "build_fmod_bank_packs.py", "byd_pack_builder")
    provenance = validate_clean_audit_inputs(parser_root)
    build_auditor(parser_root)
    guid_paths = load_guid_paths(guid_path)
    bridge = validate_bridge_policy_source()
    original_sources = pack_builder.discover_original_sources()
    if len(original_sources) != EXPECTED_ORIGINAL_PROFILE_COUNT:
        raise InventoryError(
            f"original catalog expected {EXPECTED_ORIGINAL_PROFILE_COUNT} profiles, got {len(original_sources)}; "
            "review this inventory scope intentionally before changing the contract"
        )
    profile_ids = [source.pack_id for source in original_sources]
    if len(profile_ids) != len(set(profile_ids)):
        raise InventoryError("original catalog contains duplicate profile IDs")
    cars = [compile_car(source, pack_builder=pack_builder, guid_paths=guid_paths) for source in original_sources]
    cars.sort(key=lambda item: item["id"])
    all_events = [event for car in cars for event in car["events"]]
    all_sources = [source for event in all_events for source in event["sources"]]
    if not all_sources:
        raise InventoryError("original inventory found no reachable waveform sources")
    inventory: dict[str, Any] = {
        "schema": INVENTORY_SCHEMA,
        "version": INVENTORY_VERSION,
        "purpose": "Static source-of-truth inventory for original Assetto Corsa car-bank diagnosis.",
        "scope": {
            "includedGroup": "original_cars_pack",
            "includedProfileCount": len(cars),
            "includedProfileIds": profile_ids,
            "excludedGroup": "modded_car_packs",
            "excludedReason": "Modded-bank analysis is explicitly phase 2.",
        },
        "evidenceModel": {
            "authoredFact": "Direct parser and original source files; no audio is extracted or modified.",
            "appPolicy": "Reviewed current Android bridge behavior, validated against its source markers.",
            "runtimeObservation": "Only imported Android debug evidence can establish lifecycle/audibility; absent or scenario-unreached evidence is never treated as proof of silence.",
            "staticInterpretation": "Topology/curve classification that is useful for diagnosis but is not proof of runtime playback.",
            "unresolved": "Known questions that must not be silently converted into facts.",
        },
        "provenance": {
            "compiler": {"path": "tools/generate_original_cars_audio_inventory.py", "version": TOOL_VERSION},
            "graphAuditor": provenance,
            "guidPathMap": {"path": "content/sfx/GUIDs.txt", "sha256": sha256_file(guid_path), "eventGuidCount": len(guid_paths)},
            "androidBridge": bridge,
        },
        "currentAppPolicy": {
            "hostGains": {
                "engine": 1.0,
                "effects": 2.0,
                "meaning": "Host multipliers currently layered on top of authored event automation; temporary mixer overrides are not inventory facts.",
            },
            "forcedInputs": {
                "engineThrottle": 1.0,
                "transmissionThrottle": 1.0,
                "backfireThrottle": 1.0,
                "meaning": "Intentional app policy; physical throttle still drives drivetrain simulation but does not attenuate these authored layers.",
            },
            "excludedEvents": [
                "wind",
                "tyres",
                "skid_int",
                "skid_ext",
                "wheel",
                "bodywork",
                "door",
                "horn",
            ],
            "tractionControl": "Physics may calculate traction limiting; authored traction-control sounds are intentionally forced silent.",
        },
        "summary": {
            "eventCount": len(all_events),
            "reachableWaveformSourceCount": len(all_sources),
            "engineIntProfileCount": sum(
                car["staticAudit"]["confirmedEnginePair"]["engineInt"] for car in cars
            ),
            "engineExtProfileCount": sum(
                car["staticAudit"]["confirmedEnginePair"]["engineExt"] for car in cars
            ),
            "transmissionProfileCount": sum(
                car["staticAudit"]["authoredTransmissionEvents"]["transmission"] > 0 for car in cars
            ),
            "transmissionExteriorProfileCount": sum(
                car["staticAudit"]["authoredTransmissionEvents"]["transmissionExt"] > 0 for car in cars
            ),
            "appPlayableEventCount": sum(event["appPolicy"]["classification"] == "playable" for event in all_events),
            "appSuppressedEventCount": sum(event["appPolicy"]["classification"] == "playable_but_suppressed" for event in all_events),
            "appExcludedEventCount": sum(event["appPolicy"]["classification"] == "recognized_but_excluded" for event in all_events),
            "productExcludedNotRecognizedEventCount": sum(
                event["appPolicy"]["classification"] == "product_excluded_not_recognized"
                for event in all_events
            ),
            "unsupportedEventCount": sum(event["appPolicy"]["classification"] == "unsupported_by_current_app" for event in all_events),
        },
        "cars": cars,
    }
    apply_runtime_captures(inventory, runtime_captures)
    return inventory


def markdown_escape(value: object) -> str:
    return str(value).replace("|", "\\|").replace("`", "\\`")


def format_curve(curve: Sequence[Mapping[str, Any]]) -> str:
    return ", ".join(
        f"({human_number(point.get('x'))} → {human_number(point.get('y'))}; shape {human_number(point.get('shape'))}; type {human_number(point.get('type'))})"
        for point in curve
    )


def format_conditions(source: Mapping[str, Any]) -> list[str]:
    conditions = source["authoredFact"]["conditions"]
    result: list[str] = []
    placements = conditions["placements"]
    if placements:
        rendered = []
        for placement in placements:
            if placement["kind"] == "parameterRange":
                endpoint = "]" if placement["includeEnd"] else ")"
                rendered.append(
                    f"{placement['parameterName']} ∈ [{human_number(placement['start'])}, {human_number(placement['end'])}{endpoint} ({placement['scope']})"
                )
            else:
                rendered.append(
                    f"timeline at {human_number(placement['startTime'])}s for {human_number(placement['length'])}s ({placement['scope']})"
                )
        result.append("Placement gate: " + "; ".join(rendered) + ".")
    else:
        result.append("No direct parameter/timeline placement was attributed to this source or its unique ancestors.")
    selection = conditions["playlistSelection"]
    if selection:
        result.append(
            "Playlist selection: "
            + "; ".join(
                f"{entry['playMode']} / {entry['selectionMode']} (weight {human_number(entry['childWeight'])}, order {human_number(entry['authoredOrder'])})"
                for entry in selection
            )
            + "."
        )
    automation = conditions["automation"]
    if automation:
        result.append(
            "Automation: "
            + "; ".join(
                f"{entry['inputParameterName'] or entry['inputKind']} → {entry['propertyLabel']} [{format_curve(entry['curve'])}] ({entry['scope']})"
                for entry in automation
            )
            + "."
        )
    return result


def format_runtime_metrics(metrics: Mapping[str, Any]) -> str:
    labels = {
        "eventStartedCount": "event starts",
        "eventStoppedCount": "event stops",
        "soundPlayedCount": "sound plays",
        "soundStoppedCount": "sound stops",
        "voiceStateSampleCount": "voice snapshots",
        "maxVoiceCount": "max voices",
        "maxVirtualVoiceCount": "max virtual voices",
        "maxCallbackVoiceCount": "max callback voices",
        "maxAudibility": "max audibility",
        "maxRouteGain": "max route gain",
        "everAudible": "ever audible",
        "everVirtual": "ever virtual",
        "distinctVoiceCount": "distinct voices",
    }
    return ", ".join(
        f"{labels.get(key, key)}={human_number(value)}"
        for key, value in metrics.items()
    ) or "cataloged without a lifecycle callback"


def format_runtime_observation(observation: Mapping[str, Any], *, source: bool) -> str:
    status = observation.get("status")
    if status == "notCaptured":
        return (
            "`notCaptured` — static inventory cannot prove playback, audibility, virtualisation, "
            "routing, or stop timing."
        )
    if status == "notObservedInImportedCapture":
        capture_count = len(observation.get("captureIds", []))
        return (
            f"`notObservedInImportedCapture` — no unambiguous callback was observed in {capture_count} "
            "bounded capture(s); this is not proof that the source is silent or unreachable."
        )
    if status == "observed":
        fragments: list[str] = []
        for evidence in observation.get("evidence", []):
            scenario = evidence.get("scenario", {})
            reason = scenario.get("reason", "unknown scenario")
            input_mode = scenario.get("inputMode", "unknown input mode")
            capture_id = str(evidence.get("captureId", ""))[:12]
            classification = evidence.get("catalogAppClassification", "unknown")
            fragments.append(
                f"`{capture_id}` ({reason}; {input_mode}; Android catalog `{classification}`): "
                f"{format_runtime_metrics(evidence.get('metrics', {}))}"
            )
        noun = "source" if source else "event"
        return f"`observed` — imported Android debug evidence for this exact {noun}: " + "; ".join(fragments) + "."
    return f"`{markdown_escape(status)}` — {markdown_escape(observation.get('meaning') or 'no renderer is defined for this runtime state')}."


def render_markdown(inventory: Mapping[str, Any]) -> str:
    runtime_evidence = inventory.get("runtimeEvidence", {})
    runtime_status = runtime_evidence.get("status", "notImported")
    lines: list[str] = []
    lines.extend(
        [
            "# Original Cars Audio Inventory",
            "",
            "This file is generated by `tools/generate_original_cars_audio_inventory.py`. It inventories the 23 current original Assetto Corsa profiles, including Nissan Skyline GT-R R34. It deliberately excludes the 33 modded profiles for phase 2.",
            "",
            "## How to read this inventory",
            "",
            "- **Authored fact** comes from the original bank, `GUIDs.txt`, and matching original physics. It does not claim a source was heard on Android.",
            "- **App policy** describes current `fmod_bank_bridge.cpp` behavior. It is an application decision, not necessarily the original FMOD intent.",
            "- **Static interpretation** is a topology and curve classification useful for triage. It does not replace live FMOD evidence.",
            "- **Runtime observation** comes only from imported Android debug capture summaries. `notCaptured` and `notObservedInImportedCapture` are explicitly not evidence of silence.",
            "",
            "The compiler fails closed when a source bank, matching physics export, GUID path, or event-to-source mapping is missing or ambiguous. It reads banks only; it does not extract, convert, or modify audio.",
            "",
            "## Runtime evidence status",
            "",
            (
                "- No Android capture has been imported yet. Generate a compact external summary with "
                "`python3 tools/generate_original_cars_audio_inventory.py --runtime-trace-dir <capture-dir> "
                "--write-runtime-summary <outside-repo>/runtime-summary.json`, then regenerate with "
                "`--runtime-summary <outside-repo>/runtime-summary.json`. Raw CSV traces must remain outside Git."
                if runtime_status == "notImported"
                else f"- {runtime_evidence.get('captureCount', 0)} imported capture(s) across "
                f"{runtime_evidence.get('profilesWithCaptureCount', 0)} profile(s). Every capture first matched the "
                "static bank SHA-256 and complete pre-filter event catalog; ambiguous source joins remain unresolved."
            ),
            "",
            "## Current Android policy recorded by this inventory",
            "",
            "- Engine host gain: **1.0×**. Ancillary/effects host gain: **2.0×** before temporary mixer overrides.",
            "- Engine, transmission, and backfire `throttle` parameters are intentionally held at **1.0**. Physical pedal input still affects drivetrain simulation.",
            "- `wind` and `tyres` are recognized then excluded. `skid_int`, `skid_ext`, `wheel`, `bodywork`, `door`, and `horn` are deliberately outside the product boundary and are not recognized by the bridge. Traction-control audio is intentionally suppressed while traction physics remains active.",
            "- A `turbo` bank event starts only when the matching original physics contains at least one turbo.",
            "",
            "## Provenance",
            "",
            f"- Graph parser mode: `{inventory['provenance']['graphAuditor']['mode']}`.",
            f"- Parser commit: `{inventory['provenance']['graphAuditor']['parserCommit']}`; Fmod5Sharp commit: `{inventory['provenance']['graphAuditor']['fmod5SharpCommit']}`.",
            f"- Assetto GUID map SHA-256: `{inventory['provenance']['guidPathMap']['sha256']}`.",
            f"- Android bridge SHA-256: `{inventory['provenance']['androidBridge']['sha256']}`.",
            "",
            "## Catalog summary",
            "",
            f"- {inventory['summary']['eventCount']} authored events and {inventory['summary']['reachableWaveformSourceCount']} reachable waveform instrument placements.",
            f"- Every original profile has exactly one `engine_int` and one `engine_ext` ({inventory['summary']['engineIntProfileCount']}/23 and {inventory['summary']['engineExtProfileCount']}/23 confirmed).",
            f"- {inventory['summary']['transmissionProfileCount']}/23 profiles author `transmission`; {inventory['summary']['transmissionExteriorProfileCount']}/23 author `transmission_ext`. The current catalog therefore uses the native `transmission` fallback for exterior listening whenever transmission exists.",
            f"- App classification: {inventory['summary']['appPlayableEventCount']} playable, {inventory['summary']['appSuppressedEventCount']} suppressed, {inventory['summary']['appExcludedEventCount']} recognized-but-excluded, {inventory['summary']['productExcludedNotRecognizedEventCount']} product-excluded/not-recognized, {inventory['summary']['unsupportedEventCount']} otherwise unsupported.",
        ]
    )
    for car in inventory["cars"]:
        source = car["source"]
        physics = car["physicsSummary"]
        lines.extend(
            [
                "",
                f"## {car['displayName']} (`{car['id']}`)",
                "",
                f"- Bank: `{source['bankPath']}` — SHA-256 `{source['bankSha256']}`.",
                f"- Original `data.acd` SHA-256: `{source['dataAcdSha256']}`. Exported physics SHA-256: `{source['physicsJsonSha256']}`.",
                f"- Physics context: idle {human_number(physics['idleRpm'])} RPM; limiter {human_number(physics['limiterRpm'])} RPM; auto up/down {human_number(physics['autoUpRpm'])}/{human_number(physics['autoDownRpm'])} RPM; {human_number(physics['forwardGearCount'])} forward gears; {human_number(physics['turboCount'])} turbo(s).",
                f"- Static graph: {car['staticAudit']['counts']['events']} events, {car['staticAudit']['counts']['instruments']} instruments, {car['staticAudit']['counts']['embeddedSamples']} embedded samples; every event mapping is complete.",
                (
                    f"- Runtime catalog: {car['runtimeObservation']['observedEventCount']} observed event(s), "
                    f"{car['runtimeObservation']['observedSourceCount']} unambiguously observed source(s), and "
                    f"{car['runtimeObservation']['unresolvedSourceObservationCount']} unresolved callback join(s). "
                    f"{len(car['runtimeObservation'].get('sharedDependencyEventPaths', []))} shared-dependency event(s) "
                    "were validated but excluded from this car's attribution."
                    if car["runtimeObservation"].get("status") == "catalogValidated"
                    else "- Runtime catalog: no imported Android capture for this profile."
                ),
                "",
                "### Event overview",
                "",
                "| Event | App treatment | Source placements |",
                "| --- | --- | ---: |",
            ]
        )
        for event in car["events"]:
            lines.append(
                f"| `{markdown_escape(event['identity']['path'])}` | `{event['appPolicy']['classification']}` | {event['authoredFact']['sourceCount']} |"
            )
        if car["unresolved"]:
            lines.extend(["", "### Unresolved evidence", ""])
            for unresolved in car["unresolved"]:
                lines.append(
                    "- "
                    + f"**{markdown_escape(unresolved['kind'])}:** capture `{markdown_escape(unresolved['captureId'][:12])}`; "
                    + f"event `{markdown_escape(unresolved.get('eventPath') or 'unknown')}`; "
                    + f"{markdown_escape(unresolved['reason'])}."
                )
        for event in car["events"]:
            identity = event["identity"]
            policy = event["appPolicy"]
            lines.extend(
                [
                    "",
                    f"### `{identity['path']}`",
                    "",
                    f"- **Authored fact:** GUID `{identity['guid']}`; complete source mapping; {event['authoredFact']['sourceCount']} reachable waveform source placement(s).",
                    f"- **App policy:** `{policy['classification']}`. {policy['instanceLifecycle']} {policy['activation']}",
                ]
            )
            if policy["parameters"]:
                lines.append(
                    "- **App parameters:** "
                    + "; ".join(
                        f"`{parameter['name']}` = {markdown_escape(parameter['value'])} ({parameter['source']})"
                        for parameter in policy["parameters"]
                    )
                    + "."
                )
            for note in policy["notes"]:
                lines.append(f"- **App policy note:** {note}")
            lines.append(
                "- **Runtime observation:** "
                + format_runtime_observation(event["runtimeObservation"], source=False)
            )
            lines.append("")
            lines.append("<details>")
            lines.append(f"<summary>Authored source placements ({len(event['sources'])})</summary>")
            lines.append("")
            for item in event["sources"]:
                authored = item["authoredFact"]
                sample = authored["sample"]
                instrument = authored["instrument"]
                interpretation = item["staticInterpretation"]
                lines.extend(
                    [
                        f"#### `{authored['rawSoundName']}` — instrument `{item['identity']['instrumentGuid']}`",
                        "",
                        f"- **Stable identity:** `{item['identity']['stableId']}`.",
                        f"- **Authored fact:** {instrument['lifetime']}; {human_number(sample['channels'])}-channel at {human_number(sample['frequencyHz'])} Hz; {human_number(sample['durationSeconds'])} s; payload SHA-256 `{item['identity']['samplePayloadSha256']}`.",
                        f"- **Instrument settings:** base volume {human_number(instrument['baseProperties']['volumeDb'])} dB; base pitch {human_number(instrument['baseProperties']['pitchSemitones'])} semitones; trigger chance {human_number(instrument['baseProperties']['triggerChancePercent'])}%; auto-pitch reference {human_number(instrument['baseProperties']['autoPitchReference'])}.",
                    ]
                )
                for condition in format_conditions(item):
                    lines.append(f"- **Authored activation:** {condition}")
                lines.append(
                    f"- **Static interpretation:** `{interpretation['role']}` / `{interpretation['selectionPolicy']}`; candidates {', '.join(f'`{value}`' for value in interpretation['candidateRoles']) or 'none'}; reasons {', '.join(f'`{value}`' for value in interpretation['reasons']) or 'none'}.")
                lines.append(
                    "- **Runtime observation:** "
                    + format_runtime_observation(item["runtimeObservation"], source=True)
                )
                lines.append("")
            lines.append("</details>")
    return "\n".join(lines) + "\n"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-output", type=Path, default=DEFAULT_JSON_OUTPUT)
    parser.add_argument("--markdown-output", type=Path, default=DEFAULT_MARKDOWN_OUTPUT)
    parser.add_argument("--parser-root", type=Path, default=DEFAULT_PARSER_ROOT)
    parser.add_argument("--guids", type=Path, default=DEFAULT_GUIDS_PATH)
    parser.add_argument(
        "--runtime-trace-dir",
        action="append",
        type=Path,
        default=[],
        metavar="DIRECTORY",
        help=(
            "Import one debug-only capture directory containing metadata.json, bank_event_catalog.csv, "
            "and native.csv. Raw trace rows remain outside Git. May be repeated."
        ),
    )
    parser.add_argument(
        "--runtime-summary",
        action="append",
        type=Path,
        default=[],
        metavar="FILE",
        help=(
            "Import one compact byd-fmod-runtime-summary-v1 JSON file previously emitted from debug "
            "trace data. May be repeated."
        ),
    )
    parser.add_argument(
        "--write-runtime-summary",
        type=Path,
        metavar="FILE",
        help=(
            "Write the compact runtime-summary bundle for supplied --runtime-trace-dir inputs. "
            "Use an untracked location; it intentionally excludes raw CSV rows."
        ),
    )
    parser.add_argument("--check", action="store_true", help="Fail if generated output differs from the versioned files.")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    runtime_captures = collect_runtime_captures(args.runtime_trace_dir, args.runtime_summary)
    if args.write_runtime_summary is not None:
        if not runtime_captures:
            raise InventoryError("--write-runtime-summary requires at least one --runtime-trace-dir or --runtime-summary")
        write_atomic(args.write_runtime_summary.resolve(), canonical_json_bytes(runtime_summary_bundle(runtime_captures)))
    inventory = compile_inventory(
        args.parser_root.resolve(),
        args.guids.resolve(),
        runtime_captures=runtime_captures,
    )
    json_bytes = canonical_json_bytes(inventory)
    markdown_bytes = render_markdown(inventory).encode("utf-8")
    destinations = ((args.json_output.resolve(), json_bytes), (args.markdown_output.resolve(), markdown_bytes))
    if args.check:
        differing = [str(path) for path, content in destinations if not path.is_file() or path.read_bytes() != content]
        if differing:
            raise InventoryError("generated inventory is out of date: " + ", ".join(differing))
    else:
        for path, content in destinations:
            write_atomic(path, content)
    print(
        "Compiled original-car static inventory: "
        f"{inventory['scope']['includedProfileCount']} cars, "
        f"{inventory['summary']['eventCount']} events, "
        f"{inventory['summary']['reachableWaveformSourceCount']} waveform placements."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InventoryError as error:
        print(f"original-car inventory failed: {error}", file=sys.stderr)
        raise SystemExit(1)
