"""Discover installed Assetto Corsa cars and their simulator capabilities."""

from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path

from .car_config import load_car_spec
from .drivetrain import load_drivetrain_spec


CORE_EVENTS = ("engine_int", "engine_ext", "gear_int", "gear_ext", "transmission", "turbo", "limiter", "backfire_int", "backfire_ext")


@dataclass(frozen=True)
class CarCatalogEntry:
    id: str
    name: str
    brand: str
    official: bool
    available: bool
    bank: str
    traction: str = ""
    gears: int = 0
    turbos: int = 0
    quirks: tuple[str, ...] = ()
    error: str = ""

    def json(self) -> dict[str, object]:
        return asdict(self)


def _ui(directory: Path) -> dict[str, object]:
    try:
        return json.loads((directory / "ui" / "ui_car.json").read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}


def discover_cars(assetto_root: Path, *, include_mods: bool = True) -> tuple[CarCatalogEntry, ...]:
    result: list[CarCatalogEntry] = []
    for directory in sorted((assetto_root / "content" / "cars").iterdir()):
        if not directory.is_dir():
            continue
        ui = _ui(directory)
        author = str(ui.get("author", "")).casefold()
        official = not author or "kunos" in author
        if not include_mods and not official:
            continue
        name = str(ui.get("name") or directory.name)
        brand = str(ui.get("brand") or "")
        banks = sorted((directory / "sfx").glob("*.bank")) if (directory / "sfx").is_dir() else []
        bank = next((p for p in banks if p.stem == directory.name), banks[0] if banks else None)
        try:
            car = load_car_spec(assetto_root, directory.name)
            drivetrain = load_drivetrain_spec(assetto_root, directory.name)
            quirks: list[str] = []
            if drivetrain.traction.upper() not in ("RWD", "FWD"):
                quirks.append(f"{drivetrain.traction} all-wheel drive")
            if car.turbo is None:
                quirks.append("naturally aspirated")
            else:
                quirks.append("turbocharged")
            if drivetrain.autoclutch_forced:
                quirks.append("forced autoclutch")
            if not drivetrain.supports_shifter:
                quirks.append("sequential gearbox")
            if not bank:
                raise FileNotFoundError("no FMOD car bank")
            result.append(CarCatalogEntry(directory.name, car.display_name or name, brand, official, True, str(bank), drivetrain.traction, len(drivetrain.forward_ratios), 1 if car.turbo else 0, tuple(quirks)))
        except Exception as exc:
            result.append(CarCatalogEntry(directory.name, name, brand, official, False, str(bank or ""), error=f"{type(exc).__name__}: {exc}"))
    return tuple(result)


def discover_bank_library(bank_root: Path) -> tuple[CarCatalogEntry, ...]:
    """Expose every standalone car bank as an audio audition target.

    A bank collection contains no car data, so the simulation supplies physics
    from its installed reference car while FMOD receives the selected bank
    directly. This keeps the catalog honest: a bank may still fail to play if
    it lacks Assetto Corsa's standard engine event pair.
    """

    resolved_root = bank_root.expanduser().resolve()
    if not resolved_root.is_dir():
        raise FileNotFoundError(f"bank library does not exist: {resolved_root}")

    banks = sorted(path for path in resolved_root.glob("*.bank") if path.is_file())
    if not banks:
        raise FileNotFoundError(f"bank library contains no .bank files: {resolved_root}")

    return tuple(
        CarCatalogEntry(
            id=bank.stem,
            name=_bank_display_name(bank.stem),
            brand=_bank_brand(bank.stem),
            official=bank.stem.casefold().startswith("ks_"),
            available=True,
            bank=str(bank),
            quirks=("bank-library audition", "reference-car physics"),
        )
        for bank in banks
    )


def _bank_display_name(bank_id: str) -> str:
    return bank_id.removeprefix("ks_").replace("_", " ").title()


def _bank_brand(bank_id: str) -> str:
    return _bank_display_name(bank_id).split(" ", 1)[0]
