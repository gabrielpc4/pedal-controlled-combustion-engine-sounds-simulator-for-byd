"""Classify FMOD v3 graph sources without consulting sample filenames."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.fmod_graph_roles import (
    BANK_GRAPH_SCHEMA,
    classify_bank_graph_sources,
    classify_catalog_graph_directory,
)


DEFAULT_INPUT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
BACKLOG_SCHEMA = "ac-fmod-source-role-manual-oracle-backlog-v1"


def classify_input(path: Path) -> dict[str, Any]:
    path = path.resolve()
    if path.is_dir():
        return classify_catalog_graph_directory(path)
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("schema") != BANK_GRAPH_SCHEMA:
        raise ValueError(f"{path} is not a {BANK_GRAPH_SCHEMA} report")
    return classify_bank_graph_sources(report)


def _write_output(path: Path | None, result: dict[str, Any]) -> None:
    payload = canonical_json_bytes(result)
    if path is None:
        sys.stdout.buffer.write(payload)
        sys.stdout.buffer.write(b"\n")
        return
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(payload + b"\n")
    temporary.replace(path)


def manual_oracle_backlog_document(result: dict[str, Any]) -> dict[str, Any]:
    entries = result.get("manualOracleBacklog")
    if not isinstance(entries, list):
        raise ValueError("classification result has no manual-oracle backlog")
    return {
        "schema": BACKLOG_SCHEMA,
        "inputSchema": result.get("schema"),
        "basis": {
            "usesSampleNames": False,
            "unknownPolicy": "failClosed",
            "scope": "only unresolved LOAD-exclusion decisions",
        },
        "counts": {
            "entries": len(entries),
            "families": len(
                {
                    item.get("familyId")
                    for item in entries
                    if isinstance(item, dict) and item.get("familyId")
                }
            ),
        },
        "entries": entries,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Classify waveform sources from FMOD bank graph audit v3 topology and "
            "controller curves; sample filenames are never used."
        )
    )
    parser.add_argument(
        "input",
        nargs="?",
        type=Path,
        default=DEFAULT_INPUT,
        help="one v3 report or a catalog audit directory (default: %(default)s)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="write canonical JSON atomically instead of stdout",
    )
    parser.add_argument(
        "--backlog-output",
        type=Path,
        help="also write the compact unresolved manual-oracle backlog",
    )
    args = parser.parse_args(argv)
    try:
        result = classify_input(args.input)
        _write_output(args.output, result)
        if args.backlog_output is not None:
            _write_output(
                args.backlog_output,
                manual_oracle_backlog_document(result),
            )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
