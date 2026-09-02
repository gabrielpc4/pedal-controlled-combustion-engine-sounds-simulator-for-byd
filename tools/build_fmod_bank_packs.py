#!/usr/bin/env python3
"""Build v2 installer payloads containing original banks and matching physics.

Each car archive contains one verified source ``.bank``, its optional
``GUIDs.txt`` lookup table, and the original Assetto physics metadata for every
profile that owns that bank. The two shared banks remain independent packages.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_CARS = ROOT.parent / "original_cars"
NEW_CARS = ROOT.parent / "new_cars"
ASSETTO_BANKS = ROOT.parent / "assettocorsa_banks"
ASSETTO_SHARED_BANKS = ROOT / "audio_banks" / "fmod"
ASSETTO_INSTALLATION = ROOT.parent / "assetto_corsa_installation"
AUDIO_LAB = ROOT.parent / "assetto_corsa_audio_lab"
OUTPUT = ROOT / "fmod_bank_packs"
SCHEMA = "byd-fmod-bank-pack-v2"
PHYSICS_SCHEMA = "byd-assetto-physics-v1"


@dataclass(frozen=True)
class BankSource:
    pack_id: str
    display_name: str
    bank_path: Path
    guids_path: Path | None


@dataclass(frozen=True)
class PhysicsProfile:
    profile_id: str
    bank_pack_id: str
    source_directory: Path


DIRECTORY_NAMES = {
    "lamborghini_huracan_trofeo_evo2_cabin": "lamborghini-huracan-trofeo-evo2",
    "lamborghini_aventador_sv_cabin": "lamborghini-aventador-sv",
    "nissan_skyline_r34_cabin": "nissan-skyline-gt-r34-v-spec",
    "ferrari-458-italia-gte-ferruccio": "ferrari-458_Italia_gte_ferruccio",
}

PROFILE_NAMES = {
    "lamborghini_huracan_trofeo_evo2_cabin": "Lamborghini Huracán Trofeo EVO2",
    "lamborghini_aventador_sv_cabin": "Lamborghini Aventador SV",
    "nissan_skyline_r34_cabin": "Nissan Skyline GT-R R34",
    "alfa-romeo-4c": "Alfa Romeo 4C",
    "aston-martin-dbrs9-gt3": "Aston Martin DBS",
    "audi-r8-lms-gt2": "Audi R8",
    "audi-tt-cup-2015": "Audi TT",
    "bmw-m8-gtlm": "BMW M8 Competition",
    "bugatti-chiron-pur-sport": "Bugatti Chiron",
    "cadillac-escalade-esv": "Cadillac Escalade",
    "chevrolet-camaro-concept": "Chevrolet Camaro",
    "chevrolet-corvette-c6-z06-stanced": "Chevrolet Corvette C6 Z06",
    "chevrolet-corvette-c7-stingray-hellspec": "Chevrolet Corvette C7 Stingray",
    "ferrari-360-challenge-stradale": "Ferrari 360",
    "ferrari-458-italia-tune": "Ferrari 458 Italia",
    "ferrari-458-italia-gte-ferruccio": "Ferrari 458 Spider",
    "ferrari-488-gte-evo-michelotto": "Ferrari 488 Pista",
    "ferrari-f1-2000": "Ferrari F1 2000",
    "ferrari-f430-gt2-2007": "Ferrari 430",
    "ferrari-laferrari-trio": "Ferrari LaFerrari",
    "ferrari-sf90-xx-stradale-2024": "Ferrari SF90 Stradale",
    "lexus-lfa": "Lexus LFA",
    "lexus-lfa-no-hesi-spec": "Lexus LFA No Hesi Spec",
    "lexus-lfa-nurburgring-edition": "Lexus LFA Nurburgring Edition",
    "mercedes-amg-project-one-hypercar": "Mercedes-AMG Project One Hypercar",
    "mercedes-benz-amg-gt3-evo-2020-sprint": "Mercedes-Benz AMG GT3 EVO 2020",
    "mitsubishi-eclipse-gsx-r": "Mitsubishi Eclipse",
    "mitsubishi-lancer-evolution-viii-gsr": "Mitsubishi Lancer Evolution VIII",
    "nissan-350z": "Nissan 350Z",
    "nissan-gt-r-nismo-godzilla": "Nissan GT-R NISMO Godzilla",
    "porsche-911-992-turbo-s-pdk": "Porsche 911 Turbo S PDK",
    "porsche-911-gt3-rs-hellspec": "Porsche 911 GT3 RS",
    "porsche-911-turbo-s": "Porsche 911 Turbo S",
    "porsche-carrera-gt-rs": "Porsche Carrera GT",
    "toyota-supra-wangan": "Toyota Supra",
}

ASSETTO_PROFILES = {
    "assetto-audi-r8-lms-2016": ("Audi R8 LMS 2016", "ks_audi_r8_lms_2016.bank"),
    "assetto-audi-r8-plus": ("Audi R8 Plus", "ks_audi_r8_plus.bank"),
    "assetto-audi-tt-cup": ("Audi TT Cup", "ks_audi_tt_cup.bank"),
    "assetto-bmw-m4": ("BMW M4", "ks_bmw_m4.bank"),
    "assetto-corvette-c7-stingray": ("Chevrolet Corvette C7 Stingray", "ks_corvette_c7_stingray.bank"),
    "assetto-ferrari-458": ("Ferrari 458 Italia", "ferrari_458.bank"),
    "assetto-ferrari-458-gt2": ("Ferrari 458 GT2", "ferrari_458_GT2.bank"),
    "assetto-ferrari-488-gtb": ("Ferrari 488 GTB", "ks_ferrari_488_gtb.bank"),
    "assetto-ferrari-488-gt3": ("Ferrari 488 GT3", "ks_ferrari_488_gt3.bank"),
    "assetto-ferrari-fxx-k": ("Ferrari FXX K", "ks_ferrari_fxx_k.bank"),
    "assetto-ferrari-laferrari": ("Ferrari LaFerrari", "ferrari_LaFerrari.bank"),
    "assetto-lamborghini-aventador-sv": ("Lamborghini Aventador SV", "ks_lamborghini_aventador_sv.bank"),
    "assetto-lamborghini-gallardo-sl": ("Lamborghini Gallardo Superleggera", "ks_lamborghini_gallardo_sl.bank"),
    "assetto-lamborghini-huracan-performante": ("Lamborghini Huracán Performante", "ks_lamborghini_huracan_performante.bank"),
    "assetto-lamborghini-huracan-st": ("Lamborghini Huracán ST", "ks_lamborghini_huracan_st.bank"),
    "assetto-mercedes-amg-gt3": ("Mercedes-AMG GT3", "ks_mercedes_amg_gt3.bank"),
    "assetto-nissan-370z": ("Nissan 370Z", "ks_nissan_370z.bank"),
    "assetto-nissan-gtr": ("Nissan GT-R", "ks_nissan_gtr.bank"),
    "assetto-porsche-911-gt3-rs": ("Porsche 911 GT3 RS", "ks_porsche_911_gt3_rs.bank"),
    "assetto-porsche-991-turbo-s": ("Porsche 911 Turbo S (991)", "ks_porsche_991_turbo_s.bank"),
    "assetto-toyota-supra-mkiv": ("Toyota Supra Mk IV", "ks_toyota_supra_mkiv.bank"),
}

ASSETTO_SHARED_PACKS = (
    ("assetto-common-strings", "Assetto Corsa event strings", "common.strings.bank"),
    ("assetto-common", "Assetto Corsa shared audio", "common.bank"),
)

PROFILE_ALIASES = {
    "lexus-lfa-concept-gt500": "lamborghini_aventador_sv_cabin",
    "nissan-370z-widebody": "nissan-350z",
}

OFFICIAL_PHYSICS_SOURCES = {
    "alfa-romeo-4c": "ks_alfa_romeo_4c",
    "nissan_skyline_r34_cabin": "ks_nissan_skyline_r34",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(256 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_from_car_directory(pack_id: str, display_name: str) -> BankSource:
    directory_name = DIRECTORY_NAMES.get(pack_id, pack_id)
    roots = (ORIGINAL_CARS, NEW_CARS)
    matches = [root / directory_name for root in roots if (root / directory_name).is_dir()]
    if len(matches) != 1:
        raise RuntimeError(f"{pack_id}: expected exactly one source car directory, found {matches}")
    sfx = matches[0] / "sfx"
    banks = sorted(sfx.glob("*.bank"))
    if pack_id == "porsche-911-gt3-rs-hellspec":
        bank = ASSETTO_BANKS / "ks_porsche_911_gt3_rs.bank"
        if not bank.is_file():
            raise RuntimeError(f"{pack_id}: documented fallback bank is missing: {bank}")
        return BankSource(pack_id, display_name, bank, None)
    if len(banks) != 1:
        raise RuntimeError(f"{pack_id}: expected exactly one sfx/*.bank, found {banks}")
    guids = sfx / "GUIDs.txt"
    return BankSource(pack_id, display_name, banks[0], guids if guids.is_file() else None)


def discover_sources() -> list[BankSource]:
    sources = [source_from_car_directory(pack_id, name) for pack_id, name in PROFILE_NAMES.items()]
    for pack_id, (display_name, filename) in ASSETTO_PROFILES.items():
        bank = ASSETTO_BANKS / filename
        if not bank.is_file():
            raise RuntimeError(f"{pack_id}: official Assetto bank is missing: {bank}")
        sources.append(BankSource(pack_id, display_name, bank, None))
    for pack_id, display_name, filename in ASSETTO_SHARED_PACKS:
        bank = ASSETTO_SHARED_BANKS / filename
        if not bank.is_file():
            raise RuntimeError(f"{pack_id}: shared Assetto bank is missing: {bank}")
        sources.append(BankSource(pack_id, display_name, bank, None))
    return sorted(sources, key=lambda source: source.pack_id)


def source_car_directory(profile_id: str) -> Path:
    directory_name = DIRECTORY_NAMES.get(profile_id, profile_id)
    matches = [root / directory_name for root in (ORIGINAL_CARS, NEW_CARS) if (root / directory_name).is_dir()]
    if len(matches) != 1:
        raise RuntimeError(f"{profile_id}: expected exactly one physics source directory, found {matches}")
    return matches[0]


def physics_profiles() -> list[PhysicsProfile]:
    official_cars = ASSETTO_INSTALLATION / "content" / "cars"
    profiles: list[PhysicsProfile] = []
    for profile_id in PROFILE_NAMES:
        official_id = OFFICIAL_PHYSICS_SOURCES.get(profile_id)
        source = official_cars / official_id if official_id else source_car_directory(profile_id)
        profiles.append(PhysicsProfile(profile_id, profile_id, source))
    for profile_id, bank_pack_id in PROFILE_ALIASES.items():
        profiles.append(PhysicsProfile(profile_id, bank_pack_id, source_car_directory(profile_id)))
    for profile_id, (_, bank_filename) in ASSETTO_PROFILES.items():
        profiles.append(PhysicsProfile(profile_id, profile_id, official_cars / Path(bank_filename).stem.lower()))

    if len(profiles) != 58 or len({profile.profile_id for profile in profiles}) != len(profiles):
        raise RuntimeError(f"expected exactly 58 unique physics profiles, found {len(profiles)}")
    missing = [profile for profile in profiles if not profile.source_directory.is_dir()]
    if missing:
        raise RuntimeError(f"missing physics source directories: {missing}")
    return sorted(profiles, key=lambda profile: profile.profile_id)


def load_physics(profile: PhysicsProfile) -> dict[str, object]:
    if not AUDIO_LAB.is_dir():
        raise RuntimeError(f"Assetto Corsa Audio Lab is missing: {AUDIO_LAB}")
    sys.path.insert(0, str(AUDIO_LAB.parent))
    from assetto_corsa_audio_lab.sim.car_config import load_car_spec
    from assetto_corsa_audio_lab.sim.drivetrain import load_drivetrain_spec

    candidates = [profile.source_directory.name.casefold()]
    banks = sorted((profile.source_directory / "sfx").glob("*.bank"))
    if len(banks) == 1 and banks[0].stem.casefold() not in candidates:
        candidates.append(banks[0].stem.casefold())

    failures: list[str] = []
    for source_id in candidates:
        with tempfile.TemporaryDirectory(prefix=f"physics-{profile.profile_id}-") as temporary:
            synthetic_root = Path(temporary)
            car_directory = synthetic_root / "content" / "cars" / source_id
            car_directory.parent.mkdir(parents=True)
            car_directory.symlink_to(profile.source_directory, target_is_directory=True)
            try:
                car = load_car_spec(synthetic_root, source_id)
                drivetrain = load_drivetrain_spec(synthetic_root, source_id)
            except Exception as error:
                failures.append(f"{source_id}: {type(error).__name__}: {error}")
                continue
            return {
                "schema": PHYSICS_SCHEMA,
                "profileId": profile.profile_id,
                "sourceCarId": source_id,
                "sourceDirectory": profile.source_directory.name,
                "car": asdict(car),
                "drivetrain": asdict(drivetrain),
            }
    raise RuntimeError(f"{profile.profile_id}: could not decode physics: {'; '.join(failures)}")


def file_entry(path: Path, archive_path: str) -> dict[str, object]:
    return {"path": archive_path, "bytes": path.stat().st_size, "sha256": sha256(path)}


def build_archive(
    source: BankSource,
    physics_by_pack: dict[str, list[tuple[str, dict[str, object]]]],
    force: bool,
) -> dict[str, object]:
    archive = OUTPUT / f"{source.pack_id}.bydbank"
    if archive.is_file() and not force:
        return {"id": source.pack_id, "name": source.display_name, "asset": archive.name, "bytes": archive.stat().st_size}

    with tempfile.TemporaryDirectory(prefix=f"{source.pack_id}-", dir=OUTPUT) as temporary:
        stage = Path(temporary)
        bank_directory = stage / "bank"
        bank_directory.mkdir()
        bank_copy = bank_directory / source.bank_path.name
        shutil.copy2(source.bank_path, bank_copy)
        files = [file_entry(bank_copy, f"bank/{bank_copy.name}")]
        if source.guids_path is not None:
            guids_copy = bank_directory / "GUIDs.txt"
            shutil.copy2(source.guids_path, guids_copy)
            files.append(file_entry(guids_copy, "bank/GUIDs.txt"))
        for profile_id, physics in physics_by_pack.get(source.pack_id, []):
            physics_copy = stage / "profiles" / profile_id / "physics.json"
            physics_copy.parent.mkdir(parents=True)
            physics_copy.write_text(json.dumps(physics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            files.append(file_entry(physics_copy, f"profiles/{profile_id}/physics.json"))
        manifest = {"schema": SCHEMA, "id": source.pack_id, "version": 1, "files": files}
        manifest_path = stage / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary_archive = stage / archive.name
        with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_STORED) as archive_file:
            archive_file.write(manifest_path, "manifest.json")
            for entry in files:
                archive_file.write(stage / str(entry["path"]), str(entry["path"]))
        temporary_archive.replace(archive)
    return {"id": source.pack_id, "name": source.display_name, "asset": archive.name, "bytes": archive.stat().st_size}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Recreate every archive even when it already exists.")
    arguments = parser.parse_args()
    OUTPUT.mkdir(exist_ok=True)
    physics_by_pack: dict[str, list[tuple[str, dict[str, object]]]] = {}
    for profile in physics_profiles():
        physics_by_pack.setdefault(profile.bank_pack_id, []).append((profile.profile_id, load_physics(profile)))
    packs = [build_archive(source, physics_by_pack, arguments.force) for source in discover_sources()]
    (OUTPUT / "index.json").write_text(
        json.dumps({"schema": "byd-fmod-bank-index-v1", "packs": packs}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Prepared {len(packs)} native FMOD bank packages in {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
