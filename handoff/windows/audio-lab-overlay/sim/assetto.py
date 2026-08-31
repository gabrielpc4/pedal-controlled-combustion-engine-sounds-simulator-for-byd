"""Locate a legitimate local Assetto Corsa installation."""

from __future__ import annotations

import os
from pathlib import Path


def _is_install(path: Path) -> bool:
    car_root = path / "content" / "cars" / "tatuusfa1"
    has_car_data = (car_root / "data.acd").is_file() or (car_root / "data").is_dir()
    has_banks = all(
        item.is_file()
        for item in (
            path / "content" / "sfx" / "common.bank",
            path / "content" / "sfx" / "common.strings.bank",
            car_root / "sfx" / "tatuusfa1.bank",
        )
    )
    if not (has_car_data and has_banks):
        return False
    if os.name != "nt":
        return True
    return all((path / name).is_file() for name in ("acs.exe", "fmod64.dll", "fmodstudio64.dll"))


def find_assetto_root(explicit: str | Path | None = None) -> Path:
    """Return the first validated AC root, favoring explicit configuration."""

    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    configured = os.environ.get("ASSETTO_CORSA_ROOT")
    if configured:
        candidates.append(Path(configured).expanduser())

    if os.name == "nt":
        try:
            import winreg

            key_names = (
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Steam App 244210",
                r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Steam App 244210",
            )
            for key_name in key_names:
                try:
                    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_name) as key:
                        location, _kind = winreg.QueryValueEx(key, "InstallLocation")
                        candidates.append(Path(location))
                except OSError:
                    pass
        except ImportError:
            pass

        for drive in "CDEFG":
            candidates.extend(
                (
                    Path(f"{drive}:\\Program Files (x86)\\Steam\\steamapps\\common\\assettocorsa"),
                    Path(f"{drive}:\\Program Files\\Steam\\steamapps\\common\\assettocorsa"),
                    Path(f"{drive}:\\SteamLibrary\\steamapps\\common\\assettocorsa"),
                )
            )

    checked: set[str] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        key = str(resolved).casefold()
        if key in checked:
            continue
        checked.add(key)
        if _is_install(resolved):
            return resolved

    raise FileNotFoundError(
        "Assetto Corsa with the Tatuus FA01 was not found. Set "
        "ASSETTO_CORSA_ROOT or pass --assetto-root to the game folder."
    )
