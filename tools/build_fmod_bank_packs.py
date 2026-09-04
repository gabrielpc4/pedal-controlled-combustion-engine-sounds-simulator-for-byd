#!/usr/bin/env python3
"""Build the two-group native FMOD bank catalog.

The original group is sourced only from the installed Assetto Corsa content;
the modded group is sourced from ``new_cars``. Both groups are packaged so the
installer can publish either one or both independently.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INSTALLATION = ROOT.parent / "assetto_corsa_installation"
NEW_CARS = ROOT.parent / "new_cars"
AUDIO_LAB = ROOT.parent / "assetto_corsa_audio_lab"
OUTPUT = ROOT / "fmod_bank_packs"
SCHEMA = "byd-fmod-bank-pack-v3"
INDEX_SCHEMA = "byd-fmod-bank-index-v2"
PHYSICS_SCHEMA = "byd-assetto-physics-v1"
ORIGINAL_GROUP = "original_cars_pack"
MODDED_GROUP = "modded_car_packs"


@dataclass(frozen=True)
class CarSource:
    pack_id: str
    display_name: str
    group: str
    source_directory: Path
    bank_path: Path
    preview_path: Path | None
    active: bool
    requires_physics: bool = True


# The current product catalog is intentionally explicit. Similar names are
# never treated as the same car or sound family.
ORIGINAL_CARS = (
    ("alfa-romeo-4c", "Alfa Romeo 4C", "ks_alfa_romeo_4c"),
    ("assetto-audi-r8-lms-2016", "Audi R8 LMS 2016", "ks_audi_r8_lms_2016"),
    ("assetto-audi-r8-plus", "Audi R8 Plus", "ks_audi_r8_plus"),
    ("assetto-audi-tt-cup", "Audi TT Cup", "ks_audi_tt_cup"),
    ("assetto-bmw-m4", "BMW M4", "ks_bmw_m4"),
    ("assetto-corvette-c7-stingray", "Chevrolet Corvette C7 Stingray", "ks_corvette_c7_stingray"),
    ("assetto-ferrari-458", "Ferrari 458 Italia", "ferrari_458"),
    ("assetto-ferrari-458-gt2", "Ferrari 458 GT2", "ferrari_458_gt2"),
    ("assetto-ferrari-488-gtb", "Ferrari 488 GTB", "ks_ferrari_488_gtb"),
    ("assetto-ferrari-488-gt3", "Ferrari 488 GT3", "ks_ferrari_488_gt3"),
    ("assetto-ferrari-fxx-k", "Ferrari FXX K", "ks_ferrari_fxx_k"),
    ("assetto-ferrari-laferrari", "Ferrari LaFerrari", "ferrari_laferrari"),
    ("assetto-lamborghini-aventador-sv", "Lamborghini Aventador SV", "ks_lamborghini_aventador_sv"),
    ("assetto-lamborghini-gallardo-sl", "Lamborghini Gallardo Superleggera", "ks_lamborghini_gallardo_sl"),
    ("assetto-lamborghini-huracan-performante", "Lamborghini Huracán Performante", "ks_lamborghini_huracan_performante"),
    ("assetto-lamborghini-huracan-st", "Lamborghini Huracán ST", "ks_lamborghini_huracan_st"),
    ("assetto-mercedes-amg-gt3", "Mercedes-AMG GT3", "ks_mercedes_amg_gt3"),
    ("assetto-nissan-370z", "Nissan 370Z", "ks_nissan_370z"),
    ("assetto-nissan-gtr", "Nissan GT-R", "ks_nissan_gtr"),
    ("assetto-nissan-skyline-r34", "Nissan Skyline GT-R R34", "ks_nissan_skyline_r34"),
    ("assetto-porsche-911-gt3-rs", "Porsche 911 GT3 RS", "ks_porsche_911_gt3_rs"),
    ("assetto-porsche-991-turbo-s", "Porsche 911 Turbo S (991)", "ks_porsche_991_turbo_s"),
    ("assetto-toyota-supra-mkiv", "Toyota Supra Mk IV", "ks_toyota_supra_mkiv"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(256 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def slug(value: str) -> str:
    value = value.casefold().replace("&", "and")
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value or "car"


def read_display_name(directory: Path) -> str:
    info = directory / "info.txt"
    if info.is_file():
        for line in info.read_text(encoding="utf-8", errors="replace").splitlines():
            text = line.strip()
            if text and not text.startswith("#"):
                if "=" in text:
                    return text.split("=", 1)[1].strip()
                if ":" in text:
                    return text.split(":", 1)[1].strip()
                return text
    return directory.name.replace("_", " ").replace("-", " ").title()


def preview_for_original(directory: Path) -> Path | None:
    preferred = [directory / "ui" / "dlc_preview.png"]
    preferred.extend(sorted((directory / "skins").glob("*/preview.jpg")))
    preferred.extend(sorted((directory / "skins").glob("*/preview.png")))
    preferred.extend([directory / "preview1.jpg", directory / "preview1.png"])
    return next((path for path in preferred if path.is_file()), None)


def preview_for_modded(directory: Path) -> Path | None:
    # Modded intake folders ship a user-facing preview1 image at the car root.
    # Skin previews are in-game liveries and must not replace that artwork.
    preferred = [directory / "preview1.jpg", directory / "preview1.png"]
    preferred.extend([directory / "ui" / "dlc_preview.png"])
    preferred.extend(sorted((directory / "skins").glob("*/preview.jpg")))
    preferred.extend(sorted((directory / "skins").glob("*/preview.png")))
    return next((path for path in preferred if path.is_file()), None)


def bank_for(directory: Path) -> Path:
    banks = sorted((directory / "sfx").glob("*.bank"))
    if len(banks) != 1:
        raise RuntimeError(f"{directory.name}: expected exactly one sfx/*.bank, found {banks}")
    return banks[0]


def discover_original_sources() -> list[CarSource]:
    cars_root = INSTALLATION / "content" / "cars"
    if not cars_root.is_dir():
        raise RuntimeError(f"Assetto Corsa installation is missing: {cars_root}")
    sources: list[CarSource] = []
    for pack_id, display_name, source_id in ORIGINAL_CARS:
        directory = cars_root / source_id
        if not directory.is_dir():
            raise RuntimeError(f"{pack_id}: original car directory is missing: {directory}")
        preview = preview_for_original(directory)
        if preview is None:
            raise RuntimeError(f"{pack_id}: original car has no official preview image")
        sources.append(CarSource(
            pack_id=pack_id,
            display_name=display_name,
            group=ORIGINAL_GROUP,
            source_directory=directory,
            bank_path=bank_for(directory),
            preview_path=preview,
            active=True,
        ))
    return sources


def discover_modded_sources() -> list[CarSource]:
    if not NEW_CARS.is_dir():
        return []
    sources: list[CarSource] = []
    for directory in sorted(path for path in NEW_CARS.iterdir() if path.is_dir()):
        try:
            bank = bank_for(directory)
        except RuntimeError as error:
            print(f"warning: {error}", file=sys.stderr)
            continue
        sources.append(CarSource(
            pack_id=f"modded-{slug(directory.name)}",
            display_name=read_display_name(directory),
            group=MODDED_GROUP,
            source_directory=directory,
            bank_path=bank,
            preview_path=preview_for_modded(directory),
            active=True,
        ))
    return sources


def load_physics(source: CarSource) -> dict[str, object]:
    """Export the physics contract required by every selectable car package.

    A bank without its matching physics file may install successfully but cannot be
    driven by the dashboard.  Treat that as a package-generation failure instead
    of producing a bank archive that fails later on the head unit.
    """
    if not AUDIO_LAB.is_dir():
        raise RuntimeError(
            f"{source.pack_id}: Audio Lab is required to export matching physics: {AUDIO_LAB}"
        )
    sys.path.insert(0, str(AUDIO_LAB.parent))
    from assetto_corsa_audio_lab.sim.car_config import load_car_spec
    from assetto_corsa_audio_lab.sim.drivetrain import load_drivetrain_spec

    candidates = [source.source_directory.name.casefold(), source.bank_path.stem.casefold()]
    failures: list[str] = []
    for source_id in dict.fromkeys(candidates):
        with tempfile.TemporaryDirectory(prefix=f"physics-{source.pack_id}-") as temporary:
            root = Path(temporary)
            car_directory = root / "content" / "cars" / source_id
            car_directory.parent.mkdir(parents=True)
            car_directory.symlink_to(source.source_directory, target_is_directory=True)
            try:
                car = load_car_spec(root, source_id)
                drivetrain = load_drivetrain_spec(root, source_id)
            except Exception as error:  # a modded package must not block originals
                failures.append(f"{source_id}: {type(error).__name__}: {error}")
                continue
            physics = {
                "schema": PHYSICS_SCHEMA,
                "profileId": source.pack_id,
                "sourceCarId": source_id,
                "sourceDirectory": source.source_directory.name,
                "car": asdict(car),
                "drivetrain": asdict(drivetrain),
            }
            validate_physics(source, physics)
            return physics
    raise RuntimeError(f"{source.pack_id}: could not decode matching physics: {'; '.join(failures)}")


def validate_physics(source: CarSource, physics: dict[str, object]) -> None:
    """Fail before packaging if a car would be missing the Android physics contract."""
    if physics.get("schema") != PHYSICS_SCHEMA:
        raise RuntimeError(f"{source.pack_id}: unexpected physics schema {physics.get('schema')!r}")
    if physics.get("profileId") != source.pack_id:
        raise RuntimeError(f"{source.pack_id}: physics profile does not match its package")
    if physics.get("sourceDirectory") != source.source_directory.name:
        raise RuntimeError(f"{source.pack_id}: physics source directory does not match its bank source")
    if not isinstance(physics.get("car"), dict) or not isinstance(physics.get("drivetrain"), dict):
        raise RuntimeError(f"{source.pack_id}: physics export is missing car or drivetrain data")


def canonical_physics_bytes(physics: dict[str, object]) -> bytes:
    return (json.dumps(physics, indent=2, sort_keys=True) + "\n").encode("utf-8")


def archive_matches_source(source: CarSource, physics: dict[str, object] | None, archive: Path) -> bool:
    """Return true only when a retained archive still matches the source contract."""
    if not archive.is_file():
        return False
    try:
        with zipfile.ZipFile(archive) as archive_file:
            manifest = json.loads(archive_file.read("manifest.json"))
            if (
                manifest.get("schema") != SCHEMA
                or manifest.get("id") != source.pack_id
                or manifest.get("group") != source.group
                or manifest.get("active") is not source.active
            ):
                return False
            file_entries = {
                entry.get("path"): entry
                for entry in manifest.get("files", [])
                if isinstance(entry, dict) and isinstance(entry.get("path"), str)
            }
            bank_archive_path = f"bank/{source.bank_path.name}"
            if file_entries.get(bank_archive_path, {}).get("sha256") != sha256(source.bank_path):
                return False
            if source.requires_physics:
                if physics is None:
                    return False
                physics_archive_path = f"profiles/{source.pack_id}/physics.json"
                expected_physics_sha = hashlib.sha256(canonical_physics_bytes(physics)).hexdigest()
                if file_entries.get(physics_archive_path, {}).get("sha256") != expected_physics_sha:
                    return False
            if source.preview_path is not None:
                preview_archive_path = f"preview/{source.preview_path.name}"
                if file_entries.get(preview_archive_path, {}).get("sha256") != sha256(source.preview_path):
                    return False
    except (KeyError, OSError, ValueError, zipfile.BadZipFile):
        return False
    return True


def file_entry(path: Path, archive_path: str) -> dict[str, object]:
    return {"path": archive_path, "bytes": path.stat().st_size, "sha256": sha256(path)}


def build_archive(source: CarSource, physics: dict[str, object] | None, force: bool) -> dict[str, object]:
    if source.requires_physics:
        if physics is None:
            raise RuntimeError(f"{source.pack_id}: selectable car package requires physics.json")
        validate_physics(source, physics)
    archive = OUTPUT / f"{source.pack_id}.bydbank"
    if archive_matches_source(source, physics, archive) and not force:
        return {
            "id": source.pack_id,
            "name": source.display_name,
            "group": source.group,
            "active": source.active,
            "asset": archive.name,
            "bytes": archive.stat().st_size,
        }

    with tempfile.TemporaryDirectory(prefix=f"{source.pack_id}-", dir=OUTPUT) as temporary:
        stage = Path(temporary)
        bank_copy = stage / "bank" / source.bank_path.name
        bank_copy.parent.mkdir(parents=True)
        shutil.copy2(source.bank_path, bank_copy)
        files = [file_entry(bank_copy, f"bank/{bank_copy.name}")]
        if source.preview_path is not None:
            preview_copy = stage / "preview" / source.preview_path.name
            preview_copy.parent.mkdir(parents=True)
            shutil.copy2(source.preview_path, preview_copy)
            files.append(file_entry(preview_copy, f"preview/{preview_copy.name}"))
        if physics is not None:
            physics_copy = stage / "profiles" / source.pack_id / "physics.json"
            physics_copy.parent.mkdir(parents=True)
            physics_copy.write_bytes(canonical_physics_bytes(physics))
            files.append(file_entry(physics_copy, f"profiles/{source.pack_id}/physics.json"))
        manifest = {
            "schema": SCHEMA,
            "id": source.pack_id,
            "group": source.group,
            "active": source.active,
            "version": 1,
            "files": files,
        }
        manifest_path = stage / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary_archive = stage / archive.name
        with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_STORED) as archive_file:
            archive_file.write(manifest_path, "manifest.json")
            for entry in files:
                archive_file.write(stage / str(entry["path"]), str(entry["path"]))
        temporary_archive.replace(archive)
    return {
        "id": source.pack_id,
        "name": source.display_name,
        "group": source.group,
        "active": source.active,
        "asset": archive.name,
        "bytes": archive.stat().st_size,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Recreate every archive.")
    arguments = parser.parse_args()
    OUTPUT.mkdir(exist_ok=True)
    original = discover_original_sources()
    modded = discover_modded_sources()
    sources = original + modded
    expected_archives = {f"{source.pack_id}.bydbank" for source in sources}
    expected_archives.update(("assetto-common.bydbank", "assetto-common-strings.bydbank"))
    for stale in OUTPUT.glob("*.bydbank"):
        if stale.name not in expected_archives:
            stale.unlink()
    packs = []
    exceptions = []
    for source in sources:
        try:
            packs.append(build_archive(source, load_physics(source), arguments.force))
        except Exception as error:
            if source.active:
                raise
            exceptions.append({"id": source.pack_id, "name": source.display_name, "detail": str(error)})
    common_root = INSTALLATION / "content" / "sfx"
    for pack_id, display_name, filename in (
        ("assetto-common-strings", "Assetto Corsa event strings", "common.strings.bank"),
        ("assetto-common", "Assetto Corsa shared audio", "common.bank"),
    ):
        source = CarSource(
            pack_id=pack_id,
            display_name=display_name,
            group=ORIGINAL_GROUP,
            source_directory=common_root,
            bank_path=common_root / filename,
            preview_path=None,
            active=True,
            requires_physics=False,
        )
        package = build_archive(source, None, arguments.force)
        package["dependency"] = True
        packs.append(package)
    index = {
        "schema": INDEX_SCHEMA,
        "version": 2,
        "groups": [
            {"id": ORIGINAL_GROUP, "name": "Original Assetto Corsa cars", "active": True},
            {"id": MODDED_GROUP, "name": "Modded cars", "active": True},
        ],
        "packs": sorted(packs, key=lambda item: (item["group"], item["id"])),
        "exceptions": exceptions,
    }
    (OUTPUT / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(original)} original and {len(modded)} modded FMOD bank packages in {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
