"""Build the deterministic first-party car and exact-bank family catalog."""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import defaultdict
from collections.abc import Callable, Iterable
from pathlib import Path, PurePosixPath
from typing import Any

from .acd import load_car_data, text_file
from .car_config import _parser, load_car_spec
from .drivetrain import load_drivetrain_spec
from .official_cars import (
    EXPECTED_OFFICIAL_DIRECTORIES,
    EXPECTED_SOUND_FAMILIES,
    EXPECTED_USABLE_CARS,
    OFFICIAL_KUNOS_CAR_IDS,
    UNUSABLE_OFFICIAL_CAR_IDS,
)


CATALOG_SCHEMA_VERSION = 1
KNOWN_EVENT_NAMES = frozenset(
    {
        "engine_int",
        "engine_ext",
        "gear_int",
        "gear_ext",
        "transmission",
        "turbo",
        "limiter",
        "backfire_int",
        "backfire_ext",
    }
)


class CatalogBuildError(ValueError):
    """Raised when the installed first-party catalog is incomplete or invalid."""


def _finite_number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CatalogBuildError(f"{label} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise CatalogBuildError(f"{label} must be a finite number")
    return result


def _relative_path(value: object, label: str, *, prefix: tuple[str, ...] = ()) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise CatalogBuildError(f"{label} must be a normalized relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != value:
        raise CatalogBuildError(f"{label} must be a normalized relative path")
    if prefix and tuple(path.parts[: len(prefix)]) != prefix:
        raise CatalogBuildError(f"{label} must begin with {'/'.join(prefix)}/")
    return value


def sha256_file(path: Path, *, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _stable_data_hash(car_directory: Path) -> str:
    archive = car_directory / "data.acd"
    if archive.is_file():
        return sha256_file(archive)
    data = car_directory / "data"
    if not data.is_dir():
        raise FileNotFoundError(f"no data folder or data.acd under {car_directory}")
    digest = hashlib.sha256()
    for path in sorted(item for item in data.rglob("*") if item.is_file()):
        relative = path.relative_to(data).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "little"))
        digest.update(relative)
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()


def _read_ui(car_directory: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            (car_directory / "ui" / "ui_car.json").read_text(encoding="utf-8-sig")
        )
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def _selected_bank(car_directory: Path) -> Path:
    preferred = car_directory / "sfx" / f"{car_directory.name}.bank"
    if preferred.is_file():
        return preferred
    banks = sorted((car_directory / "sfx").glob("*.bank"))
    if not banks:
        raise FileNotFoundError(f"no FMOD bank under {car_directory / 'sfx'}")
    return banks[0]


def _preview_source(car_directory: Path) -> Path | None:
    previews = sorted((car_directory / "skins").glob("*/preview.jpg"))
    if previews:
        return previews[0]
    for name in ("dlc_preview.png", "badge.png"):
        path = car_directory / "ui" / name
        if path.is_file():
            return path
    return None


def _effect_availability(events: Iterable[str]) -> dict[str, bool]:
    names = {event.rsplit("/", 1)[-1].casefold() for event in events}
    return {
        "idle": bool(names & {"engine_int", "engine_ext"}),
        "coast": bool(names & {"engine_int", "engine_ext"}),
        "texture": bool(names & {"engine_int", "engine_ext"}),
        "intake": "engine_int" in names,
        "exhaust": "engine_ext" in names,
        "turbo": "turbo" in names,
        "spool": "turbo" in names,
        "bov": "turbo" in names,
        "transmission": "transmission" in names,
        "limiter": "limiter" in names,
        "shift": bool(names & {"gear_int", "gear_ext"}),
        "overrun": bool(names & {"backfire_int", "backfire_ext"}),
        "popsBangsCracks": bool(names & {"backfire_int", "backfire_ext"}),
        "engineStart": False,
    }


def _landing_rpm(forward_ratios: tuple[float, ...], upshift_rpm: int) -> dict[str, float]:
    # Key N is the threshold used to downshift while currently in gear N.  It
    # is exactly the RPM reached when the preceding N-1 -> N upshift lands.
    return {
        str(gear + 1): round(
            float(upshift_rpm) * abs(forward_ratios[gear]) / abs(forward_ratios[gear - 1]),
            6,
        )
        for gear in range(1, len(forward_ratios))
        if forward_ratios[gear - 1]
    }


def _inline_lut(value: str) -> list[list[float]]:
    text = value.strip().strip("()")
    result: list[list[float]] = []
    for part in text.split("|"):
        if "=" not in part:
            continue
        x, y = part.split("=", 1)
        result.append([float(x.strip()), float(y.strip())])
    return result


def _turbo_controllers(files: dict[str, bytes]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for name in sorted(item for item in files if item.casefold().startswith("ctrl_turbo")):
        parser = _parser(text_file(files, name))
        controllers: list[dict[str, Any]] = []
        for section in parser.sections():
            if not section.upper().startswith("CONTROLLER_"):
                continue
            controllers.append(
                {
                    "section": section,
                    "input": parser.get(section, "INPUT", fallback="").strip().upper(),
                    "combinator": parser.get(section, "COMBINATOR", fallback="ADD").strip().upper(),
                    "lut": _inline_lut(parser.get(section, "LUT", fallback="")),
                    "filter": parser.getfloat(section, "FILTER", fallback=0.0),
                    "upLimit": parser.getfloat(section, "UP_LIMIT", fallback=0.0),
                    "downLimit": parser.getfloat(section, "DOWN_LIMIT", fallback=0.0),
                }
            )
        result.append(
            {
                "file": name,
                "sha256": hashlib.sha256(files[name]).hexdigest(),
                "controllers": controllers,
            }
        )
    return result


def _hybrid_config(files: dict[str, bytes]) -> dict[str, Any] | None:
    name = "ers.ini" if "ers.ini" in files else "kers.ini" if "kers.ini" in files else None
    if name is None:
        return None
    parser = _parser(text_file(files, name))
    controller_files = sorted(
        item for item in files if item.casefold().startswith(("ctrl_ers", "ctrl_kers"))
    )
    return {
        "file": name,
        "sha256": hashlib.sha256(files[name]).hexdigest(),
        "maximumEnergyKjPerLap": parser.getfloat("KINETIC", "MAX_KJ_PER_LAP", fallback=0.0),
        "dischargeTimeMs": parser.getfloat("KINETIC", "DISCHARGE_TIME", fallback=0.0),
        "hasButtonOverride": parser.getboolean("KINETIC", "HAS_BUTTON_OVERRIDE", fallback=False),
        "defaultController": parser.getint("KINETIC", "DEFAULT_CONTROLLER", fallback=0),
        "heatTorquePercent": parser.getfloat("HEAT", "TORQUE_PERC", fallback=0.0),
        "hasFrontMotors": parser.has_section("FRONT_MOTORS"),
        "frontDischargeTimeMs": parser.getfloat("FRONT_MOTORS", "DISCHARGE_TIME", fallback=0.0),
        "controllerFiles": [
            {"file": item, "sha256": hashlib.sha256(files[item]).hexdigest()}
            for item in controller_files
        ],
    }


def _alternate_gear_sets(files: dict[str, bytes]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for name in sorted(item for item in files if item.casefold().endswith(".rto")):
        options: list[dict[str, Any]] = []
        for raw_line in text_file(files, name).splitlines():
            line = raw_line.split(";", 1)[0].strip()
            if not line or "|" not in line:
                continue
            label, ratio = line.split("|", 1)
            options.append({"label": label.strip(), "ratio": float(ratio.strip())})
        result.append(
            {
                "file": name,
                "sha256": hashlib.sha256(files[name]).hexdigest(),
                "options": options,
            }
        )
    return result


def _quirks(car_id: str, files: dict[str, bytes], traction: str) -> list[str]:
    result: list[str] = []
    if traction not in ("RWD", "FWD"):
        result.append("allWheelDrive")
    if "ers.ini" in files or "kers.ini" in files:
        result.append("hybrid")
    if any(name.casefold().startswith("ctrl_turbo") for name in files):
        result.append("gearDependentTurboController")
    if car_id == "bmw_m3_e30_gra":
        result.append("requiresBmwM3E30GraAdditionalDsp")
    if car_id == "tatuusfa1":
        result.append("authoredBovLaneSilent")
    return result


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def build_official_catalog(
    assetto_root: Path,
    *,
    event_probe: Callable[[Path], Iterable[str]] | None = None,
    strict_complete: bool = True,
) -> dict[str, Any]:
    """Return the local catalog without copying any installed game asset.

    ``event_probe`` must be silent.  It is invoked once per unique bank hash,
    never once per car, and should return FMOD event paths or event names.
    """

    root = assetto_root.resolve()
    cars_root = root / "content" / "cars"
    installed_official = [
        car_id for car_id in OFFICIAL_KUNOS_CAR_IDS if (cars_root / car_id).is_dir()
    ]
    if strict_complete and len(installed_official) != EXPECTED_OFFICIAL_DIRECTORIES:
        missing = sorted(set(OFFICIAL_KUNOS_CAR_IDS) - set(installed_official))
        raise CatalogBuildError(
            f"complete Kunos installation required: found {len(installed_official)}/"
            f"{EXPECTED_OFFICIAL_DIRECTORIES}; missing={missing}"
        )

    staged: list[dict[str, Any]] = []
    failures: dict[str, str] = {}
    for car_id in installed_official:
        directory = cars_root / car_id
        try:
            bank = _selected_bank(directory)
            engine = load_car_spec(root, car_id)
            gearbox = load_drivetrain_spec(root, car_id)
            files = load_car_data(directory)
            ui = _read_ui(directory)
            bank_hash = sha256_file(bank)
            preview = _preview_source(directory)
            turbo_controllers = _turbo_controllers(files)
            hybrid_config = _hybrid_config(files)
            alternate_gears = _alternate_gear_sets(files)
            staged.append(
                {
                    "id": car_id,
                    "name": str(ui.get("name") or engine.display_name or car_id),
                    "brand": str(ui.get("brand") or ""),
                    "official": True,
                    "installed": True,
                    "favorite": False,
                    "familyId": bank_hash,
                    "previewPath": f"previews/{car_id}{preview.suffix.casefold()}" if preview else None,
                    "previewSource": (
                        preview.relative_to(root).as_posix() if preview is not None else None
                    ),
                    "previewSha256": sha256_file(preview) if preview is not None else None,
                    "previewMediaType": (
                        "image/jpeg"
                        if preview is not None and preview.suffix.casefold() in (".jpg", ".jpeg")
                        else "image/png"
                        if preview is not None and preview.suffix.casefold() == ".png"
                        else None
                    ),
                    "engine": {
                        "idleRpm": engine.idle_rpm,
                        "redlineRpm": engine.limiter_rpm,
                        "limiterRpm": engine.limiter_rpm,
                        "limiterHz": engine.limiter_hz,
                        "tachometerMaximumRpm": engine.tachometer_maximum,
                        "turboCount": len(engine.turbos),
                        "hybrid": hybrid_config is not None,
                        "hybridConfig": hybrid_config,
                        "turboControllers": turbo_controllers,
                    },
                    "gearbox": {
                        "traction": gearbox.traction,
                        "forwardRatios": list(gearbox.forward_ratios),
                        "reverseRatio": gearbox.reverse_ratio,
                        "finalDrive": gearbox.final_drive,
                        "upshiftRpm": gearbox.auto_up_rpm,
                        "downshiftLandingRpmByGear": _landing_rpm(
                            gearbox.forward_ratios, gearbox.auto_up_rpm
                        ),
                        "upshiftTimeMs": round(gearbox.gear_up_time_s * 1000.0, 6),
                        "downshiftTimeMs": round(gearbox.gear_down_time_s * 1000.0, 6),
                        "alternateGearSets": alternate_gears,
                    },
                    "quirks": _quirks(car_id, files, gearbox.traction),
                    "provenance": {
                        "kind": "kunosAssettoCorsa1164",
                        "bankPath": bank.relative_to(root).as_posix(),
                        "bankSha256": bank_hash,
                        "physicsSha256": _stable_data_hash(directory),
                    },
                    "_bank": bank,
                }
            )
        except Exception as exc:
            failures[car_id] = f"{type(exc).__name__}: {exc}"

    unexpected_failures = {
        key: value for key, value in failures.items() if key not in UNUSABLE_OFFICIAL_CAR_IDS
    }
    if unexpected_failures:
        raise CatalogBuildError(f"official car parse failures: {unexpected_failures}")
    if strict_complete and set(failures) != UNUSABLE_OFFICIAL_CAR_IDS:
        raise CatalogBuildError(
            "official placeholder set changed: "
            f"expected={sorted(UNUSABLE_OFFICIAL_CAR_IDS)} actual={sorted(failures)}"
        )
    if strict_complete and len(staged) != EXPECTED_USABLE_CARS:
        raise CatalogBuildError(
            f"expected {EXPECTED_USABLE_CARS} usable cars, found {len(staged)}"
        )

    members: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for car in staged:
        members[car["familyId"]].append(car)

    events_by_family: dict[str, tuple[str, ...]] = {}
    for family_id, family_cars in sorted(members.items()):
        if event_probe is None:
            events_by_family[family_id] = ()
            continue
        raw = event_probe(family_cars[0]["_bank"])
        events_by_family[family_id] = tuple(sorted({str(item) for item in raw}))

    families: list[dict[str, Any]] = []
    for family_id, family_cars in sorted(members.items()):
        member_ids = sorted(str(car["id"]) for car in family_cars)
        events = events_by_family[family_id]
        effects = _effect_availability(events)
        if {str(car["id"]) for car in family_cars} == {"tatuusfa1"}:
            effects["bov"] = False
        families.append(
            {
                "id": family_id,
                "representativeCarId": member_ids[0],
                "memberIds": member_ids,
                "sourceBankSha256": family_id,
                "events": list(events),
                "eventProbeStatus": "complete" if event_probe is not None else "notRun",
                "effects": effects,
            }
        )
        for car in family_cars:
            car["effects"] = dict(effects)
            car.pop("_bank", None)

    if strict_complete and len(families) != EXPECTED_SOUND_FAMILIES:
        raise CatalogBuildError(
            f"expected {EXPECTED_SOUND_FAMILIES} exact-bank families, found {len(families)}"
        )

    catalog: dict[str, Any] = {
        "schemaVersion": CATALOG_SCHEMA_VERSION,
        "audioPolicy": {
            "format": "FLAC",
            "sampleRate": 48000,
            "channels": 2,
            "bitsPerSample": 16,
        },
        "cars": sorted(staged, key=lambda item: str(item["id"])),
        "soundFamilies": families,
        "counts": {
            "installedOfficialDirectories": len(installed_official),
            "usableCars": len(staged),
            "soundFamilies": len(families),
            "unusablePlaceholders": len(failures),
        },
        "excludedOfficialPlaceholders": [
            {"id": car_id, "reason": failures[car_id]} for car_id in sorted(failures)
        ],
    }
    catalog["catalogSha256"] = hashlib.sha256(canonical_json_bytes(catalog)).hexdigest()
    validate_catalog(catalog, require_complete=strict_complete)
    return catalog


def validate_catalog(catalog: dict[str, Any], *, require_complete: bool = False) -> None:
    expected_top = {
        "schemaVersion",
        "audioPolicy",
        "cars",
        "soundFamilies",
        "counts",
        "excludedOfficialPlaceholders",
        "catalogSha256",
    }
    if set(catalog) != expected_top:
        raise CatalogBuildError("catalog top-level fields are invalid")
    if catalog.get("schemaVersion") != CATALOG_SCHEMA_VERSION:
        raise CatalogBuildError("unsupported catalog schemaVersion")
    cars = catalog.get("cars")
    families = catalog.get("soundFamilies")
    if not isinstance(cars, list) or not isinstance(families, list):
        raise CatalogBuildError("catalog cars and soundFamilies must be arrays")
    if catalog.get("audioPolicy") != {
        "format": "FLAC",
        "sampleRate": 48000,
        "channels": 2,
        "bitsPerSample": 16,
    }:
        raise CatalogBuildError("catalog audioPolicy must be FLAC PCM16/48 kHz/stereo")
    expected_effects = {
        "idle",
        "coast",
        "texture",
        "intake",
        "exhaust",
        "turbo",
        "spool",
        "bov",
        "transmission",
        "limiter",
        "shift",
        "overrun",
        "popsBangsCracks",
        "engineStart",
    }
    expected_car = {
        "id",
        "name",
        "brand",
        "official",
        "installed",
        "favorite",
        "familyId",
        "previewPath",
        "previewSource",
        "previewSha256",
        "previewMediaType",
        "engine",
        "gearbox",
        "quirks",
        "provenance",
        "effects",
    }
    expected_engine = {
        "idleRpm",
        "redlineRpm",
        "limiterRpm",
        "limiterHz",
        "tachometerMaximumRpm",
        "turboCount",
        "hybrid",
        "hybridConfig",
        "turboControllers",
    }
    expected_gearbox = {
        "traction",
        "forwardRatios",
        "reverseRatio",
        "finalDrive",
        "upshiftRpm",
        "downshiftLandingRpmByGear",
        "upshiftTimeMs",
        "downshiftTimeMs",
        "alternateGearSets",
    }
    sha_pattern = re.compile(r"^[0-9a-f]{64}$")
    for car in cars:
        if not isinstance(car, dict) or set(car) != expected_car:
            raise CatalogBuildError("catalog car fields are invalid")
        if (
            not isinstance(car["id"], str)
            or car["id"] not in OFFICIAL_KUNOS_CAR_IDS
            or not isinstance(car["name"], str)
            or not car["name"].strip()
            or not isinstance(car["brand"], str)
        ):
            raise CatalogBuildError("catalog car identity fields are invalid")
        if car["official"] is not True or car["installed"] is not True or not isinstance(car["favorite"], bool):
            raise CatalogBuildError(f"catalog car flags are invalid for {car.get('id')}")
        if not isinstance(car["familyId"], str) or not sha_pattern.fullmatch(car["familyId"]):
            raise CatalogBuildError(f"catalog familyId is invalid for {car.get('id')}")
        preview_values = (car["previewPath"], car["previewSource"], car["previewSha256"], car["previewMediaType"])
        if any(value is None for value in preview_values):
            if any(value is not None for value in preview_values):
                raise CatalogBuildError(f"preview metadata is partial for {car.get('id')}")
        else:
            _relative_path(car["previewPath"], "previewPath", prefix=("previews",))
            _relative_path(car["previewSource"], "previewSource", prefix=("content", "cars"))
            if not sha_pattern.fullmatch(str(car["previewSha256"])):
                raise CatalogBuildError(f"preview hash is invalid for {car.get('id')}")
            if car["previewMediaType"] not in {"image/jpeg", "image/png", "image/webp"}:
                raise CatalogBuildError(f"preview media type is invalid for {car.get('id')}")
        if not isinstance(car["engine"], dict) or set(car["engine"]) != expected_engine:
            raise CatalogBuildError(f"engine metadata fields are invalid for {car.get('id')}")
        engine = car["engine"]
        for name in ("idleRpm", "redlineRpm", "limiterRpm", "limiterHz", "tachometerMaximumRpm"):
            if _finite_number(engine[name], f"engine.{name}") <= 0.0:
                raise CatalogBuildError(f"engine.{name} must be positive")
        if not (
            float(engine["idleRpm"]) <= float(engine["redlineRpm"])
            and float(engine["redlineRpm"]) <= float(engine["tachometerMaximumRpm"])
        ):
            raise CatalogBuildError(f"engine RPM ordering is invalid for {car.get('id')}")
        if (
            isinstance(engine["turboCount"], bool)
            or not isinstance(engine["turboCount"], int)
            or engine["turboCount"] < 0
            or not isinstance(engine["hybrid"], bool)
        ):
            raise CatalogBuildError(f"engine flags are invalid for {car.get('id')}")
        if engine["hybrid"] != (engine["hybridConfig"] is not None):
            raise CatalogBuildError(f"hybrid metadata disagrees for {car.get('id')}")
        if not isinstance(engine["turboControllers"], list):
            raise CatalogBuildError(f"turboControllers is invalid for {car.get('id')}")
        for controller_file in engine["turboControllers"]:
            if not isinstance(controller_file, dict) or set(controller_file) != {"file", "sha256", "controllers"}:
                raise CatalogBuildError("turbo controller file fields are invalid")
            if (
                not isinstance(controller_file["file"], str)
                or not controller_file["file"].casefold().endswith(".ini")
                or not sha_pattern.fullmatch(str(controller_file["sha256"]))
                or not isinstance(controller_file["controllers"], list)
                or not controller_file["controllers"]
            ):
                raise CatalogBuildError("turbo controller provenance is invalid")
            for controller in controller_file["controllers"]:
                if not isinstance(controller, dict) or set(controller) != {"section", "input", "combinator", "lut", "filter", "upLimit", "downLimit"}:
                    raise CatalogBuildError("turbo controller fields are invalid")
                if (
                    not isinstance(controller["section"], str)
                    or not controller["section"]
                    or controller["input"] not in {"GAS", "GEAR", "RPMS"}
                    or controller["combinator"] not in {"ADD", "MULT"}
                    or not isinstance(controller["lut"], list)
                    or not controller["lut"]
                    or any(not isinstance(point, list) or len(point) != 2 for point in controller["lut"])
                ):
                    raise CatalogBuildError("turbo controller LUT is invalid")
                previous_x: float | None = None
                for point in controller["lut"]:
                    x = _finite_number(point[0], "turbo controller LUT x")
                    _finite_number(point[1], "turbo controller LUT y")
                    if previous_x is not None and x <= previous_x:
                        raise CatalogBuildError("turbo controller LUT x values must increase")
                    previous_x = x
                for name in ("filter", "upLimit", "downLimit"):
                    _finite_number(controller[name], f"turbo controller {name}")
        hybrid = engine["hybridConfig"]
        if hybrid is not None:
            expected_hybrid = {
                "file",
                "sha256",
                "maximumEnergyKjPerLap",
                "dischargeTimeMs",
                "hasButtonOverride",
                "defaultController",
                "heatTorquePercent",
                "hasFrontMotors",
                "frontDischargeTimeMs",
                "controllerFiles",
            }
            if not isinstance(hybrid, dict) or set(hybrid) != expected_hybrid:
                raise CatalogBuildError("hybrid metadata fields are invalid")
            if (
                not isinstance(hybrid["file"], str)
                or not hybrid["file"].casefold().endswith(".ini")
                or not sha_pattern.fullmatch(str(hybrid["sha256"]))
                or not isinstance(hybrid["controllerFiles"], list)
            ):
                raise CatalogBuildError("hybrid metadata provenance is invalid")
            for name in (
                "maximumEnergyKjPerLap",
                "dischargeTimeMs",
                "defaultController",
                "heatTorquePercent",
                "frontDischargeTimeMs",
            ):
                if _finite_number(hybrid[name], f"hybrid.{name}") < 0.0:
                    raise CatalogBuildError(f"hybrid.{name} must be non-negative")
            if not isinstance(hybrid["hasButtonOverride"], bool) or not isinstance(hybrid["hasFrontMotors"], bool):
                raise CatalogBuildError("hybrid flags must be boolean")
            for controller_file in hybrid["controllerFiles"]:
                if (
                    not isinstance(controller_file, dict)
                    or set(controller_file) != {"file", "sha256"}
                    or not isinstance(controller_file["file"], str)
                    or not controller_file["file"].casefold().endswith(".ini")
                    or not sha_pattern.fullmatch(str(controller_file["sha256"]))
                ):
                    raise CatalogBuildError("hybrid controller file is invalid")
        if not isinstance(car["gearbox"], dict) or set(car["gearbox"]) != expected_gearbox:
            raise CatalogBuildError(f"gearbox metadata fields are invalid for {car.get('id')}")
        gearbox = car["gearbox"]
        if gearbox["traction"] not in {"FWD", "RWD", "AWD", "AWD2"}:
            raise CatalogBuildError(f"traction is invalid for {car.get('id')}")
        ratios = gearbox["forwardRatios"]
        if (
            not isinstance(ratios, list)
            or not ratios
            or any(_finite_number(value, "forward ratio") <= 0.0 for value in ratios)
        ):
            raise CatalogBuildError(f"forward ratios are invalid for {car.get('id')}")
        if _finite_number(gearbox["reverseRatio"], "reverseRatio") >= 0.0:
            raise CatalogBuildError(f"reverse ratio is invalid for {car.get('id')}")
        for name in ("finalDrive", "upshiftRpm", "upshiftTimeMs", "downshiftTimeMs"):
            if _finite_number(gearbox[name], f"gearbox.{name}") <= 0.0:
                raise CatalogBuildError(f"gearbox.{name} must be positive")
        landings = gearbox["downshiftLandingRpmByGear"]
        if not isinstance(landings, dict) or any(
            not isinstance(key, str)
            or not key.isdigit()
            or _finite_number(value, "downshift landing RPM") <= 0.0
            for key, value in landings.items()
        ):
            raise CatalogBuildError(f"downshift landing map is invalid for {car.get('id')}")
        expected_landings = _landing_rpm(tuple(float(value) for value in ratios), int(gearbox["upshiftRpm"]))
        if landings != expected_landings:
            raise CatalogBuildError(f"ratio-calculated downshift RPM is invalid for {car.get('id')}")
        if not isinstance(gearbox["alternateGearSets"], list):
            raise CatalogBuildError("alternateGearSets must be an array")
        for gear_set in gearbox["alternateGearSets"]:
            if not isinstance(gear_set, dict) or set(gear_set) != {"file", "sha256", "options"}:
                raise CatalogBuildError("alternate gear set fields are invalid")
            if (
                not isinstance(gear_set["file"], str)
                or not gear_set["file"].casefold().endswith(".rto")
                or not sha_pattern.fullmatch(str(gear_set["sha256"]))
                or not isinstance(gear_set["options"], list)
            ):
                raise CatalogBuildError("alternate gear set provenance is invalid")
            for option in gear_set["options"]:
                if (
                    not isinstance(option, dict)
                    or set(option) != {"label", "ratio"}
                    or not isinstance(option["label"], str)
                    or not option["label"]
                    or _finite_number(option["ratio"], "alternate gear ratio") <= 0.0
                ):
                    raise CatalogBuildError("alternate gear option is invalid")
        if not isinstance(car["effects"], dict) or set(car["effects"]) != expected_effects or any(not isinstance(value, bool) for value in car["effects"].values()):
            raise CatalogBuildError(f"effect capability map is invalid for {car.get('id')}")
        provenance = car["provenance"]
        if not isinstance(provenance, dict) or set(provenance) != {"kind", "bankPath", "bankSha256", "physicsSha256"}:
            raise CatalogBuildError(f"provenance fields are invalid for {car.get('id')}")
        if provenance["bankSha256"] != car["familyId"] or not sha_pattern.fullmatch(str(provenance["physicsSha256"])):
            raise CatalogBuildError(f"provenance hashes are invalid for {car.get('id')}")
        if provenance["kind"] != "kunosAssettoCorsa1164":
            raise CatalogBuildError(f"provenance kind is invalid for {car.get('id')}")
        _relative_path(provenance["bankPath"], "provenance.bankPath", prefix=("content", "cars"))
        if not isinstance(car["quirks"], list) or any(
            quirk not in {
                "allWheelDrive",
                "authoredBovLaneSilent",
                "gearDependentTurboController",
                "hybrid",
                "requiresBmwM3E30GraAdditionalDsp",
            }
            for quirk in car["quirks"]
        ):
            raise CatalogBuildError(f"quirks are invalid for {car.get('id')}")

    expected_family = {
        "id",
        "representativeCarId",
        "memberIds",
        "sourceBankSha256",
        "events",
        "eventProbeStatus",
        "effects",
    }
    for family in families:
        if not isinstance(family, dict) or set(family) != expected_family:
            raise CatalogBuildError("sound family fields are invalid")
        if family["id"] != family["sourceBankSha256"] or not sha_pattern.fullmatch(str(family["id"])):
            raise CatalogBuildError("sound family hash is invalid")
        if family["eventProbeStatus"] not in ("complete", "notRun"):
            raise CatalogBuildError("sound family probe status is invalid")
        if (
            not isinstance(family["events"], list)
            or any(not isinstance(item, str) or not item.startswith("event:/") for item in family["events"])
            or family["events"] != sorted(set(family["events"]))
        ):
            raise CatalogBuildError("sound family events are invalid")
        if not isinstance(family["effects"], dict) or set(family["effects"]) != expected_effects or any(not isinstance(value, bool) for value in family["effects"].values()):
            raise CatalogBuildError("sound family effects are invalid")
        expected_family_effects = _effect_availability(family["events"])
        if set(family["memberIds"]) == {"tatuusfa1"}:
            expected_family_effects["bov"] = False
        if family["effects"] != expected_family_effects:
            raise CatalogBuildError("sound family effects do not match probed events")
        if (
            not isinstance(family["memberIds"], list)
            or not family["memberIds"]
            or family["memberIds"] != sorted(set(family["memberIds"]))
            or family["representativeCarId"] != family["memberIds"][0]
        ):
            raise CatalogBuildError("sound family membership is invalid")
    car_ids = [item.get("id") for item in cars if isinstance(item, dict)]
    family_ids = [item.get("id") for item in families if isinstance(item, dict)]
    if len(car_ids) != len(cars) or len(set(car_ids)) != len(car_ids):
        raise CatalogBuildError("car ids must be present and unique")
    if len(family_ids) != len(families) or len(set(family_ids)) != len(family_ids):
        raise CatalogBuildError("family ids must be present and unique")
    official_ids = set(OFFICIAL_KUNOS_CAR_IDS) - UNUSABLE_OFFICIAL_CAR_IDS
    if not set(car_ids).issubset(official_ids):
        raise CatalogBuildError("catalog contains a non-official or unusable car id")
    referenced = {item.get("familyId") for item in cars}
    if referenced != set(family_ids):
        raise CatalogBuildError("car family references do not match soundFamilies")
    cars_by_family = {
        family_id: sorted(car["id"] for car in cars if car["familyId"] == family_id)
        for family_id in family_ids
    }
    for family in families:
        if family["memberIds"] != cars_by_family[family["id"]]:
            raise CatalogBuildError("sound family members do not match catalog cars")
        if any(car["effects"] != family["effects"] for car in cars if car["familyId"] == family["id"]):
            raise CatalogBuildError("car and family effect maps disagree")
    counts = catalog.get("counts")
    if not isinstance(counts, dict) or set(counts) != {
        "installedOfficialDirectories",
        "usableCars",
        "soundFamilies",
        "unusablePlaceholders",
    }:
        raise CatalogBuildError("catalog counts fields are invalid")
    if counts != {
        "installedOfficialDirectories": len(cars) + len(catalog["excludedOfficialPlaceholders"]),
        "usableCars": len(cars),
        "soundFamilies": len(families),
        "unusablePlaceholders": len(catalog["excludedOfficialPlaceholders"]),
    }:
        raise CatalogBuildError("catalog counts do not match contents")
    excluded = catalog["excludedOfficialPlaceholders"]
    if not isinstance(excluded, list) or any(
        not isinstance(item, dict)
        or set(item) != {"id", "reason"}
        or item["id"] not in UNUSABLE_OFFICIAL_CAR_IDS
        or not isinstance(item["reason"], str)
        or not item["reason"]
        for item in excluded
    ):
        raise CatalogBuildError("excluded placeholder records are invalid")
    if len({item["id"] for item in excluded}) != len(excluded):
        raise CatalogBuildError("excluded placeholder ids must be unique")
    claimed_hash = catalog.get("catalogSha256")
    unhashed = dict(catalog)
    unhashed.pop("catalogSha256", None)
    expected_hash = hashlib.sha256(canonical_json_bytes(unhashed)).hexdigest()
    if claimed_hash != expected_hash:
        raise CatalogBuildError("catalogSha256 does not match canonical catalog contents")
    if require_complete:
        if len(cars) != EXPECTED_USABLE_CARS or len(families) != EXPECTED_SOUND_FAMILIES:
            raise CatalogBuildError("catalog is not the complete 178-car/153-family set")
    text = canonical_json_bytes(catalog).decode("utf-8").casefold()
    # Split into identifier tokens so words such as "download" are harmless.
    if "load" in re.findall(r"[a-z0-9]+", text):
        raise CatalogBuildError("catalog contains forbidden LOAD data")
