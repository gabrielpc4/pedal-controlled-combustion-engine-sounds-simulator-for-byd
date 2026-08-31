"""Parse the permissive INI dialect used by Assetto Corsa car data."""

from __future__ import annotations

import configparser


def parse_ac_ini(source: str) -> configparser.ConfigParser:
    """Return an INI parser while matching AC's tolerance for stray labels.

    Kunos INIs predate Python's configparser conventions. They commonly use
    ``VALUE=1; comment`` (without whitespace), may place VERSION keys before
    the first section, and some mods contain standalone editor labels such as
    ``DAMAGE`` or ``KMH``. Assetto Corsa ignores those labels, so the Lab does
    too instead of rejecting an otherwise usable car.
    """

    cleaned: list[str] = []
    seen_section = False
    preamble: list[str] = []
    for raw in source.splitlines():
        line = raw.split(";", 1)[0].strip()
        if not line or line.startswith("#"):
            continue
        starts_section = line.startswith("[")
        if starts_section:
            if not seen_section and preamble:
                cleaned.extend(("[HEADER]", *preamble))
                preamble.clear()
            seen_section = True
        elif "=" not in line and ":" not in line:
            continue
        if not seen_section:
            preamble.append(line)
        else:
            cleaned.append(line)
    if preamble:
        cleaned.extend(("[HEADER]", *preamble))

    parser = configparser.ConfigParser(
        interpolation=None,
        inline_comment_prefixes=(";", "#"),
        strict=False,
    )
    parser.read_string("\n".join(cleaned))

    return parser
