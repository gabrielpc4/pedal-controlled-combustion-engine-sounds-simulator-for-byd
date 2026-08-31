"""Deterministically audit an AC FMOD bank without extracting or playing audio."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_probe import SilentFmodBankProbe


DEFAULT_PARSER_ROOT = PROJECT_ROOT / ".aclib-tools" / "FModBankParser"
AUDIT_PROJECT = PROJECT_ROOT / "tools" / "fmod_bank_graph_audit" / "FmodBankGraphAudit.csproj"
AUDIT_DLL = (
    PROJECT_ROOT
    / "tools"
    / "fmod_bank_graph_audit"
    / "bin"
    / "Release"
    / "net8.0"
    / "FmodBankGraphAudit.dll"
)
EXPECTED_BANK_VERSION = 0x50
BANK_GRAPH_AUDIT_SCHEMA = "ac-fmod-bank-graph-audit-v3"
SOURCE_ISOLATION_OFFSETS_CAPABILITY = (
    "waveform-trigger-chance-absolute-f32le-source-verified-v1"
)
BANK_GRAPH_TOOL_CAPABILITIES = {
    "sourceIsolationOffsets": SOURCE_ISOLATION_OFFSETS_CAPABILITY,
}


class BankGraphAuditError(RuntimeError):
    """Raised when the parser cannot produce a complete, attributable graph."""


class BankGraphAuditIncomplete(BankGraphAuditError):
    """Carries a parsed report whose event/sample graph failed completeness."""

    def __init__(self, message: str, report: dict[str, Any]):
        super().__init__(message)
        self.report = report


def _require_compatible_parser(parser_root: Path) -> None:
    parser_project = parser_root / "FModBankParser" / "FModBankParser.csproj"
    fmod5_project = parser_root / "Fmod5Sharp" / "Fmod5Sharp" / "Fmod5Sharp.csproj"
    controller_source = parser_root / "FModBankParser" / "Nodes" / "ControllerNode.cs"
    missing = [
        path for path in (parser_project, fmod5_project, controller_source) if not path.is_file()
    ]
    if missing:
        raise BankGraphAuditError(
            "FModBankParser or its Fmod5Sharp submodule is not initialized: "
            + ", ".join(str(path) for path in missing)
        )
    if "<TargetFramework>net8.0</TargetFramework>" not in parser_project.read_text(
        encoding="utf-8-sig"
    ) or "net8.0;netstandard2.0" not in fmod5_project.read_text(encoding="utf-8-sig"):
        raise BankGraphAuditError(
            "the local parser clone is not retargeted for the pinned .NET 8 audit build"
        )
    source = controller_source.read_text(encoding="utf-8-sig")
    if "readonly FModGuid InputGuid" not in source or "InputGuid = new FModGuid(Ar)" not in source:
        raise BankGraphAuditError(
            "the AC 1.08 compatibility adaptation is missing: ControllerNode must retain "
            "the pre-0x5a controller input GUID instead of discarding it"
        )
    layout_source = (
        parser_root / "FModBankParser" / "Nodes" / "ParameterLayoutNode.cs"
    ).read_text(encoding="utf-8-sig")
    if "legacy[i].Position + legacy[i].Length" not in layout_source:
        raise BankGraphAuditError(
            "the AC 1.08 compatibility adaptation is missing: ParameterLayoutNode "
            "must retain legacy trigger-box position and length"
        )
    reader_source = (parser_root / "FModBankParser" / "FModReader.cs").read_text(
        encoding="utf-8-sig"
    )
    if "UnknownChunkCounts" not in reader_source:
        raise BankGraphAuditError(
            "the compatibility adaptation must expose skipped/unknown chunk counts"
        )
    instrument_source = (
        parser_root
        / "FModBankParser"
        / "Nodes"
        / "Instruments"
        / "InstrumentNode.cs"
    ).read_text(encoding="utf-8-sig")
    if (
        "VolumeFileOffset = Ar.BaseStream.Position" not in instrument_source
        or "TriggerChancePercentFileOffset = Ar.BaseStream.Position"
        not in instrument_source
    ):
        raise BankGraphAuditError(
            "the compatibility adaptation must expose absolute instrument-property "
            "offsets for verified temporary-bank isolation"
        )


def build_static_auditor(parser_root: Path = DEFAULT_PARSER_ROOT) -> None:
    """Build the pinned local adapter once before a single or batch audit."""

    parser_root = parser_root.resolve()
    _require_compatible_parser(parser_root)
    build = subprocess.run(
        [
            "dotnet",
            "build",
            str(AUDIT_PROJECT),
            "--configuration",
            "Release",
            "--nologo",
            "--verbosity",
            "quiet",
            f"--property:FmodBankParserRoot={parser_root}",
        ],
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if build.returncode:
        detail = build.stderr.strip() or build.stdout.strip() or "no build output"
        raise BankGraphAuditError(f"FModBankParser audit build failed: {detail}")


def _run_static_audit(bank_path: Path) -> dict[str, Any]:
    completed = subprocess.run(
        ["dotnet", str(AUDIT_DLL), str(bank_path)],
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        check=False,
    )
    try:
        report = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        detail = completed.stderr.strip() or completed.stdout.strip() or "no output"
        raise BankGraphAuditError(f"FModBankParser audit failed: {detail}") from exc
    if completed.returncode:
        incomplete = [event["guid"] for event in report.get("events", []) if not event["mappingComplete"]]
        raise BankGraphAuditIncomplete(
            "event-to-sample graph is incomplete for: " + ", ".join(incomplete),
            report,
        )
    return report


def _attach_silent_runtime_paths(
    report: dict[str, Any],
    assetto_root: Path,
    bank_path: Path,
    probe: SilentFmodBankProbe | None = None,
) -> None:
    owns_probe = probe is None
    if probe is None:
        probe = SilentFmodBankProbe(assetto_root)
    try:
        runtime_events = dict(probe.probe_events(bank_path))
    finally:
        if owns_probe:
            probe.close()
    parser_guids = {str(event["guid"]) for event in report["events"]}
    runtime_guids = set(runtime_events)
    if parser_guids != runtime_guids:
        missing_runtime = sorted(parser_guids - runtime_guids)
        missing_parser = sorted(runtime_guids - parser_guids)
        raise BankGraphAuditError(
            "parser/runtime event GUID mismatch; "
            f"missingRuntime={missing_runtime}, missingParser={missing_parser}"
        )
    for event in report["events"]:
        event["path"] = runtime_events[str(event["guid"])]
    report["silentRuntimeOracle"] = {
        "apiVersion": "0x00010812",
        "eventGuidPathMappings": len(runtime_events),
        "outputType": "NOSOUND_NRT",
    }


def _waveform_trigger_chance_records(
    report: dict[str, Any],
) -> list[tuple[str, int, int, float]]:
    """Return structurally valid isolation offsets for every waveform body.

    The FMOD graph schema predates temporary-bank source isolation.  Keeping a
    distinct, exact capability marker lets v3 caches produced before offset
    attribution invalidate themselves instead of being mistaken for usable
    source-isolation evidence.
    """

    instruments = report.get("instruments")
    if not isinstance(instruments, list):
        raise BankGraphAuditError("bank graph instrument array is absent")
    records: list[tuple[str, int, int, float]] = []
    for instrument in instruments:
        if not isinstance(instrument, dict):
            raise BankGraphAuditError("bank graph contains a malformed instrument")
        if instrument.get("kind") != "WaveformInstrumentNode":
            continue
        guid = str(instrument.get("guid") or "")
        properties = instrument.get("baseProperties")
        if not isinstance(properties, dict):
            raise BankGraphAuditError(
                f"waveform instrument has no isolatable InstrumentBody: {guid}"
            )
        offset = properties.get("triggerChancePercentFileOffset")
        raw_bits = properties.get("triggerChancePercentRawUInt32")
        percent = properties.get("triggerChancePercent")
        if (
            isinstance(offset, bool)
            or not isinstance(offset, int)
            or offset < 0
            or isinstance(raw_bits, bool)
            or not isinstance(raw_bits, int)
            or not 0 <= raw_bits <= 0xFFFFFFFF
            or isinstance(percent, bool)
            or not isinstance(percent, (int, float))
            or not math.isfinite(float(percent))
        ):
            raise BankGraphAuditError(
                f"waveform trigger-chance offset evidence is invalid: {guid}"
            )
        expected = struct.pack("<I", raw_bits)
        try:
            encoded_percent = struct.pack("<f", float(percent))
        except (OverflowError, struct.error) as exc:
            raise BankGraphAuditError(
                f"waveform trigger-chance value is not binary32: {guid}"
            ) from exc
        if encoded_percent != expected:
            raise BankGraphAuditError(
                f"waveform trigger-chance value/raw bits disagree: {guid}"
            )
        records.append((guid, offset, raw_bits, float(percent)))
    offsets = [record[1] for record in records]
    if len(offsets) != len(set(offsets)):
        raise BankGraphAuditError(
            "waveform instruments contain duplicate trigger-chance file offsets"
        )
    return records


def _attach_tool_capabilities(report: dict[str, Any]) -> None:
    records = _waveform_trigger_chance_records(report)
    report["toolCapabilities"] = dict(BANK_GRAPH_TOOL_CAPABILITIES)
    report["sourceIsolationOffsets"] = {
        "encoding": "absolute-source-bank-byte-offset+ieee754-binary32-little-endian",
        "waveformInstrumentBodies": len(records),
        "validatedAgainstSourceBytes": True,
    }


def _validate_source_isolation_offsets(
    report: dict[str, Any], source_bank_path: Path
) -> int:
    """Verify all attributed offsets and the report hash from one read-only handle."""

    source = source_bank_path.resolve(strict=True)
    records = _waveform_trigger_chance_records(report)
    with source.open("rb") as stream:
        before = os.fstat(stream.fileno())
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        if digest.hexdigest() != report["bank"].get("sha256"):
            raise BankGraphAuditError(
                "bank graph source hash does not match the exact source bank bytes"
            )
        for guid, offset, raw_bits, _percent in records:
            if offset + 4 > before.st_size:
                raise BankGraphAuditError(
                    f"waveform trigger-chance offset is outside the source bank: "
                    f"{guid}@{offset}"
                )
            stream.seek(offset)
            if stream.read(4) != struct.pack("<I", raw_bits):
                raise BankGraphAuditError(
                    f"waveform trigger-chance offset/raw bits disagree with source: "
                    f"{guid}@{offset}"
                )
        after = os.fstat(stream.fileno())
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise BankGraphAuditError("source bank changed during offset validation")
    return len(records)


def validate_bank_graph_report(
    report: dict[str, Any],
    *,
    expected_bank_sha256: str | None = None,
    source_bank_path: Path | None = None,
) -> None:
    if report.get("schema") != BANK_GRAPH_AUDIT_SCHEMA:
        raise BankGraphAuditError("bank graph audit schema is unsupported")
    if report.get("toolCapabilities") != BANK_GRAPH_TOOL_CAPABILITIES:
        raise BankGraphAuditError(
            "bank graph audit lacks the current source-isolation offset capability"
        )
    bank = report.get("bank")
    counts = report.get("counts")
    coverage = report.get("coverage")
    events = report.get("events")
    if not all(isinstance(item, dict) for item in (bank, counts, coverage)) or not isinstance(
        events, list
    ):
        raise BankGraphAuditError("bank graph audit structure is incomplete")
    if not isinstance(report.get("featureKinds"), dict) or not isinstance(
        report.get("unknownChunks"), list
    ):
        raise BankGraphAuditError("bank graph feature-kind evidence is absent")
    if expected_bank_sha256 is not None and bank.get("sha256") != expected_bank_sha256:
        raise BankGraphAuditError("cached bank graph source hash does not match its family")
    version = int(bank["fileVersion"])
    if version != EXPECTED_BANK_VERSION:
        raise BankGraphAuditError(
            f"expected AC FMOD bank file version 0x{EXPECTED_BANK_VERSION:02x}, got 0x{version:02x}"
        )
    if coverage["eventsWithCompleteSampleMapping"] != counts["events"]:
        raise BankGraphAuditError("not every event has a complete instrument/sample mapping")
    if coverage["controllersWithCurve"] != counts["controllers"]:
        raise BankGraphAuditError("one or more controller curve references are unresolved")
    if not all(event.get("mappingComplete") for event in events):
        raise BankGraphAuditError("event mapping completeness flags are inconsistent")
    oracle = report.get("silentRuntimeOracle")
    if not isinstance(oracle, dict) or oracle.get("outputType") != "NOSOUND_NRT":
        raise BankGraphAuditError("silent runtime GUID/path evidence is absent")
    if oracle.get("eventGuidPathMappings") != counts["events"]:
        raise BankGraphAuditError("silent runtime event mapping count is incomplete")
    if any(not str(event.get("path", "")).startswith("event:/") for event in events):
        raise BankGraphAuditError("one or more runtime event paths are absent")
    records = _waveform_trigger_chance_records(report)
    offset_evidence = report.get("sourceIsolationOffsets")
    if offset_evidence != {
        "encoding": "absolute-source-bank-byte-offset+ieee754-binary32-little-endian",
        "waveformInstrumentBodies": len(records),
        "validatedAgainstSourceBytes": True,
    }:
        raise BankGraphAuditError(
            "bank graph source-isolation offset coverage marker is inconsistent"
        )
    if source_bank_path is not None:
        _validate_source_isolation_offsets(report, source_bank_path)


def audit_bank_graph(
    bank_path: Path,
    *,
    assetto_root: Path,
    parser_root: Path = DEFAULT_PARSER_ROOT,
    probe: SilentFmodBankProbe | None = None,
    build: bool = True,
) -> dict[str, Any]:
    """Return a complete canonicalizable graph audit for one installed car bank."""

    bank_path = bank_path.resolve(strict=True)
    assetto_root = assetto_root.resolve(strict=True)
    parser_root = parser_root.resolve()
    if build:
        build_static_auditor(parser_root)
    else:
        _require_compatible_parser(parser_root)
        if not AUDIT_DLL.is_file():
            raise BankGraphAuditError("static auditor has not been built")
    report = _run_static_audit(bank_path)
    _attach_silent_runtime_paths(report, assetto_root, bank_path, probe)
    _attach_tool_capabilities(report)
    validate_bank_graph_report(report, source_bank_path=bank_path)
    return report


def _write_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = canonical_json_bytes(value) + b"\n"
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(content)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bank", type=Path, help="installed AC car .bank to inspect read-only")
    parser.add_argument("--assetto-root", type=Path, help="explicit Assetto Corsa installation")
    parser.add_argument("--parser-root", type=Path, default=DEFAULT_PARSER_ROOT)
    parser.add_argument("--output", type=Path, help="atomic JSON destination; stdout if omitted")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    root = find_assetto_root(args.assetto_root)
    report = audit_bank_graph(args.bank, assetto_root=root, parser_root=args.parser_root)
    if args.output:
        _write_atomic(args.output.resolve(), report)
    else:
        sys.stdout.buffer.write(canonical_json_bytes(report) + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
