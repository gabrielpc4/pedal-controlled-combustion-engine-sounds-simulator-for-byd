#!/usr/bin/env python3
"""Generate the human-facing subtitle catalog for every selectable Assetto car.

The car-specific `ui_car.json` belongs to the source installation/mod package and is the
authoritative description of the exact variant the app ships.  It is deliberately preferred
over guessing from a road-car name: race, tuned, and community variants commonly do not match
the corresponding production-car specification.
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PACK_BUILDER = ROOT / "tools" / "build_fmod_bank_packs.py"
KOTLIN_OUTPUT = ROOT / "mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/CarSubtitleCatalog.kt"
DOCUMENT_OUTPUT = ROOT / "docs/car-subtitles.md"


def load_pack_builder() -> Any:
    spec = importlib.util.spec_from_file_location("byd_pack_builder_subtitles", PACK_BUILDER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {PACK_BUILDER}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def read_ui_metadata(source: Any) -> dict[str, Any]:
    candidates = (source.source_directory / "ui" / "ui_car.json", source.source_directory / "ui" / "dlc_ui_car.json")
    path = next((candidate for candidate in candidates if candidate.is_file()), None)
    if path is None:
        raise RuntimeError(f"{source.pack_id}: missing ui_car.json/dlc_ui_car.json")
    # Official Assetto files contain literal line breaks in several description strings. Python's
    # strict=False accepts those source files without changing their meaningful metadata fields.
    return json.loads(path.read_text(encoding="utf-8-sig", errors="replace"), strict=False)


def first_tag(tags: set[str], candidates: tuple[str, ...]) -> str | None:
    return next((candidate for candidate in candidates if candidate in tags), None)


def approximate_price(name: str, source_group: str, category: str) -> str | None:
    """Return a deliberately broad Brazilian-market hint, never a fake exact quote.

    Webmotors listings are sparse for race cars and most mods, so those stay explicitly without
    a market comparison.  Road-car bands are intentionally rounded to avoid false precision.
    """
    if source_group != "original_cars_pack" or category in {"GT", "GT2", "GT3", "GT4", "F1", "LMP1", "LMP2", "Race Car", "Single-Seater"}:
        return None
    upper = name.upper()
    bands = (
        (("PAGANI",), "~R$8M"), (("LAMBORGHINI", "FERRARI", "MCLAREN"), "~R$2–4M"),
        (("PORSCHE", "MERCEDES-AMG", "MERCEDES SLS"), "~R$700k–2M"),
        (("AUDI R8", "NISSAN GT-R", "NISSAN SKYLINE"), "~R$500–900k"),
        (("CORVETTE", "BMW M", "BMW Z4"), "~R$300–700k"),
        (("ALFA", "LOTUS", "MASERATI", "RUF", "KTM"), "~R$250–900k"),
        (("FORD MUSTANG", "TOYOTA SUPRA", "TOYOTA GT86"), "~R$250–600k"),
        (("ABARTH", "MAZDA", "ALFA MITO", "ALFA GIULIETTA"), "~R$100–300k"),
    )
    for keywords, value in bands:
        if any(keyword in upper for keyword in keywords):
            return value
    return "~R$100–500k"


def subtitle(source: Any, metadata: dict[str, Any]) -> str:
    tags = {str(tag).strip().lower() for tag in metadata.get("tags", [])}
    specs = metadata.get("specs") if isinstance(metadata.get("specs"), dict) else {}
    vehicle_class = str(metadata.get("class") or "").strip().lower()

    category = first_tag(tags, ("f1", "lmp1", "lmp2", "gt1", "gt2", "gt3", "gt4", "gt", "prototype c"))
    if category is None:
        if "singleseater" in tags:
            category = "single-seater"
        elif vehicle_class == "race" or "race" in tags:
            category = "race car"
        elif "suv" in tags:
            category = "SUV"
        elif "hot hatchback" in tags:
            category = "hot hatch"
        elif "supercar" in tags:
            category = "supercar"
        elif "street" in tags or vehicle_class == "street":
            category = "road car"
        else:
            category = vehicle_class or "Assetto Corsa car"
    category = category.upper() if category.startswith(("gt", "f1", "lmp")) else category.title()

    drive = first_tag(tags, ("awd", "rwd", "fwd"))
    drive_label = drive.upper() if drive else None

    bhp = str(specs.get("bhp") or "")
    power = re.search(r"(\d[\d,]*)\s*(?:\+)?\s*bhp", bhp, re.IGNORECASE)
    power_label = f"{power.group(1).replace(',', '')} HP" if power else None

    # These three properties are present in the source metadata for every supplied profile and
    # stay factual for special racing/tuned variants where a stock-engine web spec would mislead.
    category_label = category
    price = approximate_price(str(metadata.get("name") or source.pack_id), source.group, category_label)
    return " · ".join(part for part in (category_label, drive_label, power_label, price) if part)


def kotlin_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def write_outputs(rows: list[tuple[Any, dict[str, Any], str]]) -> None:
    entries = "\n".join(f'        "{source.pack_id}" to "{kotlin_escape(value)}",' for source, _, value in rows)
    KOTLIN_OUTPUT.write_text(
        """package com.gabrielpc.enginesoundsimulator.audio

/**
 * Human-facing facts for the exact Assetto/mod variants packaged by this project.
 *
 * Generated by `tools/generate_car_subtitles.py`; do not hand-edit. Values come from each
 * source car's `ui_car.json` and deliberately describe the packaged variant rather than
 * assuming a production specification for a race or tuned car.
 */
internal object CarSubtitleCatalog {
    private const val fallback = "ASSETTO CORSA AUDIO PROFILE"

    private val subtitles = mapOf(
""" + entries + """
    )

    fun forProfileId(profileId: String): String = subtitles[profileId] ?: fallback
}
""",
        encoding="utf-8",
    )
    group_counts: dict[str, int] = {}
    for source, _, _ in rows:
        group_counts[source.group] = group_counts.get(source.group, 0) + 1
    lines = [
        "# Car subtitles",
        "",
        "This catalog supplies the subtitle beneath the selected car on the dashboard.",
        "It currently covers every selectable profile discovered by the pack builder.",
        "",
        "## Provenance and scope",
        "",
        "- The exact variant facts are parsed from each included car's `ui_car.json` (or `dlc_ui_car.json`): class/tags, drivetrain and advertised BHP.",
        "- The public [Assetto Corsa car-data structure reference](https://github.com/aiazzi-davide/AC_Car_Editor/blob/main/assettocorsa_car_data_documentation.md) documents this metadata boundary.",
        "- The public [Assetto catalog](https://assetto.patacuack.net/cars?server=0) was used to cross-check category naming. It is not used to overwrite the supplied mod variant's metadata.",
        "- Rounded road-car price bands are a Brazil-market indication informed by [Webmotors listings](https://www.webmotors.com.br/carros/estoque) (consulted 2026-09-04), not a quote or FIPE value.",
        "- Race/prototype/community variants show no market price when Webmotors has no comparable listing.",
        "- This avoids presenting a stock road-car engine claim for an Assetto race, stage, widebody or community-mod profile.",
        "",
        "## Coverage",
        "",
        *[f"- `{group}`: {count} profiles." for group, count in sorted(group_counts.items())],
        "",
        "## Generated values",
        "",
        "| Profile | Source name | Subtitle |",
        "| --- | --- | --- |",
        *[f"| `{source.pack_id}` | {metadata.get('name', source.pack_id)} | {value} |" for source, metadata, value in rows],
        "",
        "Regenerate after adding/removing a source car with:",
        "",
        "```sh\npython3 tools/generate_car_subtitles.py\n```",
    ]
    DOCUMENT_OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    builder = load_pack_builder()
    sources = [*builder.discover_original_sources(), *builder.discover_modded_sources()]
    rows = [(source, metadata := read_ui_metadata(source), subtitle(source, metadata)) for source in sources]
    ids = [source.pack_id for source, _, _ in rows]
    if len(ids) != len(set(ids)):
        raise RuntimeError("duplicate profile ids in source metadata")
    write_outputs(sorted(rows, key=lambda row: row[0].pack_id))
    print(f"generated {len(rows)} car subtitles")


if __name__ == "__main__":
    main()
