"""Inventory installed FH6 granular archives without extracting their data."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from fh6.config import find_fh6_root, load_reference_config
from fh6.method22 import DecodeCache, archive_sha256, inspect_archive


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fh6-root")
    parser.add_argument("--cache-root", default=".fh6-cache/audio")
    args = parser.parse_args()
    root = find_fh6_root(args.fh6_root)
    config = load_reference_config(root)
    cache = DecodeCache(Path(args.cache_root).resolve())
    report: dict[str, object] = {"car": config.car_id, "upgrade": config.upgrade, "archives": {}}
    archives: dict[str, object] = report["archives"]  # type: ignore[assignment]
    for layer, synth in config.synths.items():
        entries = inspect_archive(synth.archive)
        validation = cache.validate(synth.archive)
        archives[layer] = {
            "path": str(synth.archive),
            "sha256": archive_sha256(synth.archive),
            "entryCount": len(entries),
            "method22Count": sum(entry.method22 for entry in entries),
            "expectedBytes": sum(entry.size for entry in entries),
            "cache": {
                "valid": validation.valid,
                "decodedEntries": validation.decoded_entries,
                "errors": validation.errors,
            },
        }
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
