#!/usr/bin/env python3
"""Prepare one standalone catalog, with bank archives separate from small UI metadata."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GROUPS = {"original": "original_cars_pack", "modded": "modded_car_packs"}
DEPENDENCIES = {"assetto-common", "assetto-common-strings"}


def prepare(group_name: str, output: Path) -> None:
    group = GROUPS[group_name]
    packs_root = ROOT / "fmod_bank_packs"
    index = json.loads((packs_root / "index.json").read_text())
    assert index["schema"] == "byd-fmod-bank-index-v2"
    packs = [p for p in index["packs"] if p["active"] and (p["group"] == group or p["id"] in DEPENDENCIES)]
    source = (ROOT / "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/FmodBankProfile.kt").read_text()
    profiles = {
        match[0] for match in re.findall(r'^        profile\("([^"]+)", "[^"]+"(, moddedCarsPackId)?\),$', source, re.M)
        if bool(match[1]) == (group_name == "modded")
    }
    car_ids = {p["id"] for p in packs if p["id"] not in DEPENDENCIES}
    assert car_ids == profiles, f"Catalog/archive mismatch: {car_ids ^ profiles}. Rebuild bank packs."
    assert DEPENDENCIES <= {p["id"] for p in packs}
    output.mkdir(parents=True, exist_ok=True)
    assets = output / "embedded_banks"
    assets.mkdir(exist_ok=True)
    expected = {p["id"] for p in packs}
    for stale in assets.iterdir():
        if stale.is_dir() and stale.name not in expected:
            shutil.rmtree(stale)
    for pack in packs:
        archive = packs_root / pack["asset"]
        destination = assets / pack["id"]
        destination.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as payload:
            manifest_bytes = payload.read("manifest.json")
            manifest = json.loads(manifest_bytes)
            assert manifest["schema"] == "byd-fmod-bank-pack-v3"
            assert manifest["id"] == pack["id"] and manifest["group"] == pack["group"]
            assert sum(f["path"].startswith("bank/") and f["path"].endswith(".bank") for f in manifest["files"]) == 1
            for entry in manifest["files"]:
                digest = hashlib.sha256()
                size = 0
                with payload.open(entry["path"]) as stream:
                    while chunk := stream.read(256 * 1024):
                        digest.update(chunk)
                        size += len(chunk)
                assert size == entry["bytes"] and digest.hexdigest() == entry["sha256"], entry["path"]
                if entry["path"].startswith("profiles/"):
                    physics = payload.read(entry["path"])
                    assert json.loads(physics)["profileId"] == pack["id"]
                    (destination / "physics.json").write_bytes(physics)
                elif entry["path"].startswith("preview/"):
                    (destination / "preview").write_bytes(payload.read(entry["path"]))
            if pack["id"] not in DEPENDENCIES:
                assert any(f["path"] == f'profiles/{pack["id"]}/physics.json' for f in manifest["files"])
            (destination / "manifest.json").write_bytes(manifest_bytes)
        target = destination / "payload.bydbank"
        if not target.exists() or target.stat().st_size != archive.stat().st_size or target.stat().st_mtime_ns != archive.stat().st_mtime_ns:
            shutil.copy2(archive, target)
    print(f"Prepared {len(car_ids)} {group_name} cars and {len(DEPENDENCIES)} shared banks in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", choices=GROUPS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.group, args.output)
