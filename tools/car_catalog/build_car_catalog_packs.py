#!/usr/bin/env python3
"""Prepare the 36-car Android catalog and assemble release-gated family packs.

The preparation phase reads the active inventory, the repaired FMOD graph audit,
and each staged Assetto Corsa car's real physics. It writes a compact catalog
source document and deterministic, resized preview assets.

The assembly phase consumes finalized full-event atlas outputs. It refuses to
emit the Android runtime catalog or any ``.bydpack`` unless every conservation,
NRT realization, adaptive oracle, runtime mapping, shard, and WAV-format gate
passes. Audio remains outside the APK; only the catalog JSON and previews are
written to Android assets.
"""

from __future__ import annotations

import argparse
import copy
import configparser
import csv
from dataclasses import dataclass
import hashlib
import importlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
from typing import Any, Mapping, Sequence
import zipfile


SOURCE_SCHEMA = "byd-car-atlas-catalog-source-v1"
CATALOG_SCHEMA = "byd-car-atlas-catalog-v1"
CATALOG_VERSION = 1
RELEASE_CATALOG_SCHEMA = "byd-car-atlas-catalog-v2"
RELEASE_CATALOG_VERSION = 2
AUDIT_SCHEMA = "byd-ac-catalog-audit-summary-v2"
BATCH_SCHEMA = "byd-full-event-atlas-batch-v1"
PLAN_SCHEMA = "byd-full-event-atlas-plan-v3"
RUNTIME_SCHEMA = "byd-full-event-atlas-runtime-v3"
REALIZATION_SCHEMA = "byd-full-event-atlas-realization-v3"
ATLAS_PACK_REPORT_SCHEMA = "byd-full-event-atlas-pack-v3"
SEALED_FAMILY_SCHEMA = "byd-full-event-sealed-family-v1"
SEALED_FAMILY_DESCRIPTOR_FILE_NAME = "sealed-family.json"
SEALED_PACK_DIRECTORY_NAME = "sealed-packs"
ORACLE_SCHEMA = "byd-full-event-atlas-oracle-v1"
HOST_MIX_SCHEMA = "byd-full-event-atlas-host-mix-v1"
COMBINED_ENGINE_EFFECT_MIX_ORACLE_SCHEMA = "byd-combined-engine-effect-mix-oracle-v1"
COMPACT_EFFECT_RUNTIME_SCHEMA = "byd-full-event-effect-runtime-v5"
COMPACT_EFFECT_NODE_ENCODING_SCHEMA = "byd-full-event-effect-node-array-v1"
COMPACT_EFFECT_NODE_FIELDS = (
    "variantBindingRef",
    "parameters",
    "shardName",
    "startFrame",
    "endFrameExclusive",
    "loopStartFrame",
    "loopEndFrameExclusive",
)
COMPACT_EFFECT_EXECUTION_SCHEMA = "byd-full-event-effect-execution-contract-v1"
COMPACT_EFFECT_SELECTION_SCHEMA = "byd-full-event-playlist-selection-v1"
PACK_MANIFEST_SCHEMA_VERSION = 1
EXPECTED_ACTIVE_CARS = 36
EXPECTED_BANK_FAMILIES = 32
PACK_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,95}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SAFE_IDENTIFIER_PATTERN = re.compile(r"[^a-z0-9]+")
SHARD_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,159}\.wav$")
PREVIEW_NAME_PATTERN = re.compile(r"^car_previews/[a-z0-9][a-z0-9_]{0,95}\.jpg$")
SCHEDULING_GROUP_ID_PATTERN = re.compile(r"^(layer|multi):[A-Za-z0-9._-]{1,96}$")
MAXIMUM_MANIFEST_BYTES = 128 * 1024
MAXIMUM_ARCHIVE_BYTES = 1_024 * 1_024 * 1_024
MAXIMUM_MEMBER_BYTES = 384 * 1_024 * 1_024
MAXIMUM_EXTRACTED_BYTES = 1_536 * 1_024 * 1_024
MAXIMUM_MEMBER_COUNT = 2_048
# Keep the release boundary aligned with the Android importer's per-member
# contract. The atlas generator may deliberately choose smaller shards, but a
# second undocumented release-only ceiling must not reject an otherwise valid
# pack.
MAXIMUM_ATLAS_SHARD_BYTES = MAXIMUM_MEMBER_BYTES
MAXIMUM_PREVIEW_BYTES = 256 * 1024
MAXIMUM_ALL_PREVIEW_BYTES = 4 * 1024 * 1024
MAXIMUM_ANDROID_ROOT_CATALOG_BYTES = 512 * 1024
TARGET_ANDROID_RUNTIME_CATALOG_BYTES = 8 * 1024 * 1024
MAXIMUM_ANDROID_RUNTIME_CATALOG_BYTES = 16 * 1024 * 1024
MAXIMUM_ANDROID_FAMILY_RUNTIME_BYTES = 4 * 1024 * 1024
MAXIMUM_SOUND_FINAL_DRIVE_RATIO = 20.0
GLOBAL_BACKFIRE_MINIMUM_INTENT_THROTTLE = 0.4
GLOBAL_BACKFIRE_MINIMUM_INTENT_SECONDS = 1.0
HOST_MIX_CONTRACT = {
    "schema": HOST_MIX_SCHEMA,
    "engineEventHostGainLinear": 0.5,
    "effectEventHostGainLinear": 1.0,
    "hostGainClasses": {
        "engineEvent": {
            "gainLinear": 0.5,
            "appliesTo": "continuousEngineBedAndFiniteSourcesInsideSameEngineEventInstance",
        },
        "effectEvent": {
            "gainLinear": 1.0,
            "appliesTo": "separatelyStartedNonEngineEffectEventInstances",
        },
    },
    "postSumMaster": {
        "algorithm": "stereoLinkedCausalPeakLimiter-v1",
        "ceilingLinear": 0.98,
        "lookaheadFrames": 0,
        "outputDelayFrames": 0,
        "preRoll": "none",
        "blockState": "continuousAcrossRenderBlocks",
        "stopTail": "none",
        "attackFrames": 1,
        "releaseFrames": 4_800,
        "releaseStepPerFrame": "(1.0-currentGain)/releaseFrames",
        "detector": "maxAbsoluteStereoSampleCurrentFrame",
        "targetGain": "min(1.0,ceilingLinear/detectorPeak)",
        "gainSmoothing": "attackImmediateReleaseLinearTowardOne",
    },
    "requiresCombinedEngineEffectMixOracle": True,
}


class CatalogBuildError(ValueError):
    """A release-blocking catalog or pack invariant failed."""


@dataclass(frozen=True)
class WavMetadata:
    size_bytes: int
    sha256: str
    sample_rate: int
    channels: int
    frame_count: int


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)

    return digest.hexdigest()


def safe_identifier(value: str) -> str:
    normalized = SAFE_IDENTIFIER_PATTERN.sub("_", value.casefold()).strip("_")
    if not normalized:
        raise CatalogBuildError(f"identifier {value!r} has no safe characters")

    return normalized


def profile_id(car_id: str) -> str:
    result = f"ac_{safe_identifier(car_id)}"
    _require(len(result) <= 96, f"profile id derived from {car_id!r} is too long")

    return result


def _read_json(path: Path) -> Any:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise CatalogBuildError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value

        return result

    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, json.JSONDecodeError, CatalogBuildError) as error:
        raise CatalogBuildError(f"could not read valid JSON from {path}: {error}") from error


def _write_atomic(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(content)
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _write_json(path: Path, value: Any) -> None:
    _write_atomic(path, canonical_json_bytes(value) + b"\n")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CatalogBuildError(message)


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - value.keys())
    extra = sorted(value.keys() - expected)
    _require(not missing and not extra, f"{label} keys differ: missing={missing}, extra={extra}")


def _finite_number(value: Any, label: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise CatalogBuildError(f"{label} is not numeric") from error
    _require(math.isfinite(number), f"{label} is not finite")

    return number


def _load_active_inventory(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        rows = [dict(row) for row in csv.DictReader(source) if row.get("bank_role") == "active"]
    ids = [row.get("car_id", "") for row in rows]
    _require(len(rows) == EXPECTED_ACTIVE_CARS, f"expected {EXPECTED_ACTIVE_CARS} active cars, found {len(rows)}")
    _require(all(ids), "active inventory contains an empty car_id")
    _require(len(set(ids)) == len(ids), "active inventory contains duplicate car_id values")

    return rows


def _load_audit(path: Path, active_car_ids: set[str]) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    audit = _read_json(path)
    _require(isinstance(audit, dict) and audit.get("schema") == AUDIT_SCHEMA, f"{path} is not {AUDIT_SCHEMA}")
    totals = audit.get("totals")
    _require(isinstance(totals, dict), "catalog audit has no totals")
    required_totals = {
        "activeCars": EXPECTED_ACTIVE_CARS,
        "uniqueBankFamilies": EXPECTED_BANK_FAMILIES,
        "failedCars": 0,
        "allCarConservationChecksPass": True,
        "allFamilyConservationChecksPass": True,
        "zeroUnresolvedCoreMappings": True,
    }
    for key, expected in required_totals.items():
        _require(totals.get(key) == expected, f"catalog audit total {key} is {totals.get(key)!r}, expected {expected!r}")
    _require(audit.get("failures") == [], "catalog audit still contains car failures")

    families = audit.get("families")
    _require(isinstance(families, list) and len(families) == EXPECTED_BANK_FAMILIES, "catalog audit family count differs")
    by_car: dict[str, dict[str, Any]] = {}
    for family in families:
        _require(isinstance(family, dict), "catalog audit contains a non-object family")
        family_id = str(family.get("familyId") or "")
        bank_sha = str(family.get("bankSha256") or "")
        members = family.get("memberCarIds")
        conservation = family.get("conservation")
        _require(family.get("status") == "success", f"audit family {family_id} is not successful")
        _require(PACK_ID_PATTERN.fullmatch(family_id) is not None, f"audit family id {family_id!r} is unsafe")
        _require(SHA256_PATTERN.fullmatch(bank_sha) is not None, f"audit family {family_id} has invalid bank hash")
        _require(isinstance(members, list) and members, f"audit family {family_id} has no members")
        _require(isinstance(conservation, dict) and conservation.get("passes") is True, f"audit family {family_id} failed conservation")
        _require(conservation.get("unresolvedRetainedSourceGuidCount") == 0, f"audit family {family_id} has unresolved retained sources")
        _require(not conservation.get("missingFromOutputEnumeration"), f"audit family {family_id} misses output sources")
        for car_id in members:
            _require(car_id in active_car_ids, f"audit family {family_id} contains unknown car {car_id}")
            _require(car_id not in by_car, f"audit car {car_id} appears in more than one family")
            by_car[str(car_id)] = family
    _require(set(by_car) == active_car_ids, "audit family membership differs from active inventory")

    return audit, by_car


def _load_audio_lab_modules(audio_lab_root: Path) -> tuple[Any, Any, Any, Any]:
    root_text = str(audio_lab_root)
    if root_text not in sys.path:
        sys.path.insert(0, root_text)
    try:
        car_config = importlib.import_module("sim.car_config")
        drivetrain = importlib.import_module("sim.drivetrain")
        acd = importlib.import_module("sim.acd")
        ac_ini = importlib.import_module("sim.ac_ini")
    except (ImportError, AttributeError) as error:
        raise CatalogBuildError(f"could not load Audio Lab physics modules from {audio_lab_root}: {error}") from error

    return car_config, drivetrain, acd, ac_ini


def _physics_ini_files(acd_module: Any, car_directory: Path) -> dict[str, bytes]:
    try:
        return acd_module.load_car_data(car_directory)
    except Exception as error:
        raise CatalogBuildError(f"could not read physics for {car_directory.name}: {error}") from error


def _parse_ini(ac_ini_module: Any, acd_module: Any, files: Mapping[str, bytes], name: str) -> configparser.ConfigParser:
    _require(name in files, f"physics is missing {name}")
    return ac_ini_module.parse_ac_ini(acd_module.text_file(files, name))


def _authored_idle_rpm(
    engine_spec: Any,
    engine_ini: configparser.ConfigParser,
    car_id: str,
) -> tuple[float, dict[str, Any]]:
    primary = _finite_number(engine_spec.idle_rpm, f"{car_id} ENGINE_DATA.MINIMUM")
    if 250.0 <= primary <= 5_000.0:
        return primary, {"source": "engine.ini:[ENGINE_DATA].MINIMUM", "fallbackUsed": False}
    try:
        fallback = engine_ini.getfloat("THROTTLE_LUA", "IDLE_RPM")
    except (configparser.Error, ValueError) as error:
        raise CatalogBuildError(
            f"{car_id} has invalid ENGINE_DATA.MINIMUM={primary} and no authored THROTTLE_LUA.IDLE_RPM"
        ) from error
    fallback = _finite_number(fallback, f"{car_id} THROTTLE_LUA.IDLE_RPM")
    _require(250.0 <= fallback <= 5_000.0, f"{car_id} authored fallback idle {fallback} is invalid")

    return fallback, {
        "source": "engine.ini:[THROTTLE_LUA].IDLE_RPM",
        "fallbackUsed": True,
        "rejectedPrimary": primary,
        "reason": "ENGINE_DATA.MINIMUM is outside the physical idle range",
    }


def _authored_shift_indicators(
    instruments: configparser.ConfigParser | None,
    idle_rpm: float,
    limiter_rpm: float,
) -> list[dict[str, Any]]:
    if instruments is None:
        return []
    result: list[dict[str, Any]] = []
    for section in instruments.sections():
        if not section.upper().startswith("LED_"):
            continue
        for key in ("RPM_SWITCH", "BLINK_SWITCH"):
            try:
                value = instruments.getfloat(section, key)
            except (configparser.Error, ValueError):
                continue
            if math.isfinite(value) and idle_rpm < value <= limiter_rpm:
                result.append({"section": section, "key": key, "rpm": value})

    return sorted(result, key=lambda item: (item["rpm"], item["section"], item["key"]))


def _authored_final_drive_options(source: str, car_id: str, ratio_table_name: str) -> list[dict[str, Any]]:
    options: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(source.splitlines(), start=1):
        line = raw_line.split(";", 1)[0].split("#", 1)[0].strip()
        if not line:
            continue
        _require("|" in line, f"{car_id} {ratio_table_name}:{line_number} is not a label|ratio row")
        label, raw_ratio = (part.strip() for part in line.split("|", 1))
        _require(bool(label), f"{car_id} {ratio_table_name}:{line_number} has no option label")
        ratio = _finite_number(raw_ratio, f"{car_id} {ratio_table_name}:{line_number} ratio")
        _require(
            0.0 < ratio <= MAXIMUM_SOUND_FINAL_DRIVE_RATIO,
            f"{car_id} {ratio_table_name}:{line_number} final-drive ratio {ratio} is outside (0, {MAXIMUM_SOUND_FINAL_DRIVE_RATIO}]",
        )
        options.append(
            {
                "index": len(options),
                "label": label,
                "ratio": ratio,
                "line": line_number,
            }
        )
    _require(bool(options), f"{car_id} {ratio_table_name} has no authored final-drive options")

    return options


def resolve_sound_final_drive(
    *,
    car_id: str,
    drivetrain_final_drive: Any,
    setup_ini: configparser.ConfigParser | None = None,
    ratio_table_sources: Mapping[str, str] | None = None,
) -> tuple[float, dict[str, Any]]:
    try:
        direct = float(drivetrain_final_drive)
    except (TypeError, ValueError):
        direct = math.nan
    if math.isfinite(direct) and 0.0 < direct <= MAXIMUM_SOUND_FINAL_DRIVE_RATIO:
        return direct, {
            "source": "drivetrain.ini:[GEARS].FINAL",
            "authoredValue": direct,
            "fallbackUsed": False,
        }

    rejected = direct if math.isfinite(direct) else str(drivetrain_final_drive)
    _require(
        setup_ini is not None and setup_ini.has_section("FINAL_GEAR_RATIO"),
        f"{car_id} drivetrain FINAL={rejected!r} is invalid/outlying and setup.ini has no [FINAL_GEAR_RATIO]",
    )
    try:
        ratio_table_name = setup_ini.get("FINAL_GEAR_RATIO", "RATIOS").strip()
    except configparser.Error as error:
        raise CatalogBuildError(
            f"{car_id} drivetrain FINAL={rejected!r} is invalid/outlying and setup.ini has no FINAL_GEAR_RATIO.RATIOS"
        ) from error
    _require(bool(ratio_table_name), f"{car_id} setup.ini FINAL_GEAR_RATIO.RATIOS is empty")
    _require(
        Path(ratio_table_name).name == ratio_table_name
        and "/" not in ratio_table_name
        and "\\" not in ratio_table_name
        and ratio_table_name not in {".", ".."},
        f"{car_id} setup.ini references unsafe final-drive table {ratio_table_name!r}",
    )
    sources = ratio_table_sources or {}
    _require(
        ratio_table_name in sources,
        f"{car_id} setup.ini references missing final-drive table {ratio_table_name!r}",
    )
    options = _authored_final_drive_options(sources[ratio_table_name], car_id, ratio_table_name)
    selected = options[0]

    return selected["ratio"], {
        "source": (
            "setup.ini:[FINAL_GEAR_RATIO].RATIOS -> "
            f"{ratio_table_name}:first authored option"
        ),
        "authoredValue": selected["ratio"],
        "fallbackUsed": True,
        "rejectedDrivetrainFinal": rejected,
        "rejectedReason": f"not finite or outside (0, {MAXIMUM_SOUND_FINAL_DRIVE_RATIO}]",
        "ratioTable": ratio_table_name,
        "selectedIndex": selected["index"],
        "selectedLabel": selected["label"],
        "authoredOptions": options,
    }


def _loaded_numeric_field(
    parser: configparser.ConfigParser,
    section: str,
    key: str,
    fallback: float,
    label: str,
    source_file: str = "engine.ini",
) -> tuple[float, dict[str, Any]]:
    try:
        value = parser.getfloat(section, key)
    except (configparser.Error, ValueError):
        value = fallback
        evidence = {
            "source": f"Assetto Corsa runtime default because {source_file}:[{section}].{key} is absent or invalid",
            "fallbackUsed": True,
            "defaultValue": fallback,
        }
    else:
        evidence = {"source": f"{source_file}:[{section}].{key}", "fallbackUsed": False}
    value = _finite_number(value, label)

    return value, evidence


def _turbo_controls(
    *,
    car_id: str,
    engine_spec: Any,
    engine_ini: configparser.ConfigParser,
) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    sections = sorted(
        (section for section in engine_ini.sections() if section.upper().startswith("TURBO_")),
        key=lambda section: int(section.rsplit("_", 1)[1]),
    )
    loaded_turbos = tuple(engine_spec.turbos)
    _require(len(sections) == len(loaded_turbos), f"{car_id} Audio Lab turbo count differs from engine.ini")
    stages: list[dict[str, Any]] = []
    evidence: list[dict[str, Any]] = []
    bov_threshold, bov_evidence = _loaded_numeric_field(
        engine_ini,
        "BOV",
        "PRESSURE_THRESHOLD",
        0.5,
        f"{car_id} BOV pressure threshold",
    )
    for ordinal, (section, loaded) in enumerate(zip(sections, loaded_turbos)):
        authored_index = int(section.rsplit("_", 1)[1])
        _require(authored_index == ordinal, f"{car_id} turbo indices are not contiguous from zero")
        fields: dict[str, float] = {}
        field_evidence: dict[str, Any] = {}
        for output_name, key, fallback, loaded_name in (
            ("lagUp", "LAG_UP", 0.99, "lag_up"),
            ("lagDown", "LAG_DN", 0.99, "lag_down"),
            ("maximumBoost", "MAX_BOOST", 0.0, "maximum_boost"),
            ("wastegate", "WASTEGATE", 0.0, "wastegate"),
            ("referenceRpm", "REFERENCE_RPM", 1.0, "reference_rpm"),
            ("gamma", "GAMMA", 1.0, "gamma"),
        ):
            value, item_evidence = _loaded_numeric_field(
                engine_ini,
                section,
                key,
                fallback,
                f"{car_id} {section}.{key}",
            )
            loaded_value = _finite_number(getattr(loaded, loaded_name), f"{car_id} Audio Lab {section}.{key}")
            _require(
                math.isclose(value, loaded_value, rel_tol=0.0, abs_tol=1e-9),
                f"{car_id} catalog {section}.{key} differs from Audio Lab",
            )
            fields[output_name] = value
            field_evidence[output_name] = item_evidence
        loaded_bov = _finite_number(loaded.bov_threshold, f"{car_id} Audio Lab BOV pressure threshold")
        _require(math.isclose(bov_threshold, loaded_bov, rel_tol=0.0, abs_tol=1e-9), f"{car_id} BOV threshold differs from Audio Lab")
        stage = {
            "index": ordinal,
            **fields,
            "bovPressureThreshold": bov_threshold,
        }
        _require(stage["lagUp"] > 0.0 and stage["lagDown"] > 0.0, f"{car_id} turbo {ordinal} lag is not positive")
        _require(stage["maximumBoost"] > 0.0, f"{car_id} turbo {ordinal} maximum boost is not positive")
        _require(stage["wastegate"] >= 0.0, f"{car_id} turbo {ordinal} wastegate is negative")
        _require(stage["referenceRpm"] > 0.0 and stage["gamma"] > 0.0, f"{car_id} turbo {ordinal} RPM/gamma is not positive")
        _require(stage["bovPressureThreshold"] >= 0.0, f"{car_id} turbo {ordinal} BOV threshold is negative")
        stages.append(stage)
        evidence.append(
            {
                "index": ordinal,
                "section": section,
                "fields": {**field_evidence, "bovPressureThreshold": bov_evidence},
            }
        )
    divisor = sum((stage["maximumBoost"] for stage in stages), 0.0)
    normalization = {
        "kind": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
        "divisor": divisor,
        "minimum": 0.0,
        "maximum": 1.0,
    }
    normalization_evidence = {
        "source": "Audio Lab/Assetto CarAudioFMOD turbo boost parameter mapping",
        "formula": "sum(maximumBoost * spoolState) / sum(maximumBoost)",
        "spoolFormula": (
            "input=clamp(effectiveThrottle*rpm/max(1,referenceRpm),0,1); "
            "target=input^gamma; q+=clamp(dt*lag,0,1)*(target-q); "
            "wastegate clamps maximumBoost*q"
        ),
        "bovFormula": "sumPhysicalBoost*(1-effectiveThrottle) > firstTurbo.bovPressureThreshold",
        "bovDecayUnit": "secondsSinceBovRelease",
        "explicitlyNaturallyAspirated": not stages,
    }

    return stages, normalization, {"stages": evidence, "normalization": normalization_evidence}


def _backfire_controls(
    *,
    car_id: str,
    engine_spec: Any,
    sounds_ini: configparser.ConfigParser,
) -> tuple[dict[str, Any], dict[str, Any]]:
    values: dict[str, float] = {}
    evidence: dict[str, Any] = {}
    loaded_backfire = engine_spec.backfire
    for output_name, key, fallback, loaded_name in (
        ("maximumGas", "MAXGAS", 0.4, "maximum_gas"),
        ("minimumRpm", "MINRPM", 3_500.0, "minimum_rpm"),
        ("maximumRpm", "MAXRPM", 15_000.0, "maximum_rpm"),
        ("triggerGas", "TRIGGERGAS", 0.8, "trigger_gas"),
    ):
        raw_value, item_evidence = _loaded_numeric_field(
            sounds_ini,
            "BACKFIRE",
            key,
            fallback,
            f"{car_id} BACKFIRE.{key}",
            source_file="sounds.ini",
        )
        effective = min(0.3, raw_value) if output_name == "maximumGas" else raw_value
        loaded_value = _finite_number(getattr(loaded_backfire, loaded_name), f"{car_id} Audio Lab BACKFIRE.{key}")
        _require(
            math.isclose(effective, loaded_value, rel_tol=0.0, abs_tol=1e-9),
            f"{car_id} catalog BACKFIRE.{key} differs from Audio Lab",
        )
        values[output_name] = effective
        evidence[output_name] = {
            **item_evidence,
            "authoredOrDefaultValue": raw_value,
            "effectiveValue": effective,
            **({"assettoExecutableHardCap": 0.3} if output_name == "maximumGas" else {}),
        }
    _require(0.0 <= values["maximumGas"] <= 0.3, f"{car_id} effective BACKFIRE.MAXGAS is invalid")
    _require(values["minimumRpm"] >= 0.0 and values["maximumRpm"] >= values["minimumRpm"], f"{car_id} backfire RPM range is invalid")
    _require(0.0 <= values["triggerGas"] <= 1.0, f"{car_id} BACKFIRE.TRIGGERGAS is invalid")
    controls = {
        **values,
        "minimumIntentThrottle": GLOBAL_BACKFIRE_MINIMUM_INTENT_THROTTLE,
        "minimumIntentSeconds": GLOBAL_BACKFIRE_MINIMUM_INTENT_SECONDS,
    }
    evidence["intentGate"] = {
        "source": "application global user-intent policy",
        "combination": "additional minimum; donor gas and RPM gates still apply",
    }

    return controls, evidence


def derive_physics(
    *,
    car_id: str,
    engine_spec: Any,
    drivetrain_spec: Any,
    engine_ini: configparser.ConfigParser,
    instruments_ini: configparser.ConfigParser | None,
    raw_final_drive: Any | None = None,
    setup_ini: configparser.ConfigParser | None = None,
    final_drive_ratio_tables: Mapping[str, str] | None = None,
    sounds_ini: configparser.ConfigParser | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    idle_rpm, idle_evidence = _authored_idle_rpm(engine_spec, engine_ini, car_id)
    limiter_rpm = _finite_number(engine_spec.limiter_rpm, f"{car_id} limiter")
    upshift_rpm = _finite_number(drivetrain_spec.auto_up_rpm, f"{car_id} auto upshift")
    maximum_rpm = _finite_number(engine_spec.tachometer_maximum, f"{car_id} tachometer maximum")
    indicators = _authored_shift_indicators(instruments_ini, idle_rpm, limiter_rpm)
    if indicators:
        redline_rpm = min(limiter_rpm, max(upshift_rpm, indicators[-1]["rpm"]))
        redline_source = "max(valid digital shift indicator, automatic upshift), capped by limiter"
    else:
        redline_rpm = limiter_rpm
        redline_source = "engine limiter; car has no valid digital shift-indicator threshold"

    gear_ratios = [
        _finite_number(value, f"{car_id} gear {index + 1}")
        for index, value in enumerate(drivetrain_spec.forward_ratios)
    ]
    upshift_seconds = _finite_number(drivetrain_spec.gear_up_time_s, f"{car_id} upshift duration")
    downshift_seconds = _finite_number(drivetrain_spec.gear_down_time_s, f"{car_id} downshift duration")
    final_drive, final_drive_evidence = resolve_sound_final_drive(
        car_id=car_id,
        drivetrain_final_drive=(drivetrain_spec.final_drive if raw_final_drive is None else raw_final_drive),
        setup_ini=setup_ini,
        ratio_table_sources=final_drive_ratio_tables,
    )
    loaded_final_drive = _finite_number(drivetrain_spec.final_drive, f"{car_id} Audio Lab final drive")
    _require(
        math.isclose(final_drive, loaded_final_drive, rel_tol=0.0, abs_tol=1e-9),
        f"{car_id} catalog final drive {final_drive} differs from Audio Lab {loaded_final_drive}",
    )
    turbos, turbo_normalization, turbo_evidence = _turbo_controls(
        car_id=car_id,
        engine_spec=engine_spec,
        engine_ini=engine_ini,
    )
    backfire, backfire_evidence = _backfire_controls(
        car_id=car_id,
        engine_spec=engine_spec,
        sounds_ini=sounds_ini or configparser.ConfigParser(),
    )
    limiter_frequency_hz = _finite_number(engine_spec.limiter_hz, f"{car_id} limiter frequency")
    authored_limiter_frequency_hz, limiter_frequency_evidence = _loaded_numeric_field(
        engine_ini,
        "ENGINE_DATA",
        "LIMITER_HZ",
        0.0,
        f"{car_id} ENGINE_DATA.LIMITER_HZ",
    )
    _require(
        math.isclose(limiter_frequency_hz, authored_limiter_frequency_hz, rel_tol=0.0, abs_tol=1e-9),
        f"{car_id} catalog limiter frequency differs from Audio Lab",
    )
    traction = str(drivetrain_spec.traction).strip().upper()
    front_radius = _finite_number(
        drivetrain_spec.vehicle.front_wheel_radius_m,
        f"{car_id} front tyre radius",
    )
    rear_radius = _finite_number(
        drivetrain_spec.vehicle.rear_wheel_radius_m,
        f"{car_id} rear tyre radius",
    )
    _require(traction, f"{car_id} has no drivetrain traction type")
    if traction == "FWD":
        driven_radius = front_radius
        driven_radius_rule = "FWD uses FRONT tyre RADIUS"
    elif traction.startswith("AWD"):
        driven_radius = 0.5 * (front_radius + rear_radius)
        driven_radius_rule = "AWD uses arithmetic mean of FRONT and REAR tyre RADIUS"
    else:
        driven_radius = rear_radius
        driven_radius_rule = "RWD/other uses REAR tyre RADIUS, matching Audio Lab"
    physics = {
        "minimumRpm": 0.0,
        "maximumRpm": maximum_rpm,
        "idleRpm": idle_rpm,
        "redlineRpm": redline_rpm,
        "limiterRpm": limiter_rpm,
        "upshiftRpm": upshift_rpm,
        "gearRatios": gear_ratios,
        "soundFinalDriveRatio": final_drive,
        "soundDrivenWheelRadiusMeters": driven_radius,
        "drivetrainSpeedControl": {
            "parameterName": "drivetrain_speed",
            "unit": "drivenWheelRadiansPerSecond",
            "formula": "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
            "signed": True,
        },
        "turbos": turbos,
        "turboBoostNormalization": turbo_normalization,
        "backfire": backfire,
        "limiterFrequencyHz": limiter_frequency_hz,
        "upshiftDurationSeconds": upshift_seconds,
        "downshiftDurationSeconds": downshift_seconds,
    }
    validate_physics(physics, car_id)
    evidence = {
        "idleRpm": idle_evidence,
        "limiterRpm": {"source": "engine.ini:[ENGINE_DATA].LIMITER"},
        "upshiftRpm": {"source": "drivetrain.ini:[AUTO_SHIFTER].UP or ai.ini:[GEARS].UP"},
        "maximumRpm": {"source": "max(limiter + 500 RPM, digital tachometer RPM_MAX)"},
        "redlineRpm": {"source": redline_source, "validShiftIndicators": indicators},
        "gearRatios": {"source": "drivetrain.ini:[GEARS].GEAR_1..GEAR_COUNT"},
        "soundFinalDriveRatio": final_drive_evidence,
        "soundDrivenWheelRadiusMeters": {
            "source": "tyres.ini:[FRONT/REAR].RADIUS selected by drivetrain.ini:[TRACTION].TYPE",
            "traction": traction,
            "frontRadiusMeters": front_radius,
            "rearRadiusMeters": rear_radius,
            "selectionRule": driven_radius_rule,
            "fallbackUsed": False,
        },
        "drivetrainSpeedControl": {
            "source": "Audio Lab AutomaticDrivetrain/Assetto CarAudioFMOD",
            "finalDriveIncluded": False,
        },
        "turbos": turbo_evidence,
        "backfire": backfire_evidence,
        "limiterFrequencyHz": limiter_frequency_evidence,
        "shiftDurations": {"source": "drivetrain.ini:[GEARBOX].CHANGE_UP_TIME/CHANGE_DN_TIME"},
    }

    return physics, evidence


def validate_physics(physics: Mapping[str, Any], car_id: str) -> None:
    required = {
        "minimumRpm",
        "maximumRpm",
        "idleRpm",
        "redlineRpm",
        "limiterRpm",
        "upshiftRpm",
        "gearRatios",
        "soundFinalDriveRatio",
        "soundDrivenWheelRadiusMeters",
        "drivetrainSpeedControl",
        "turbos",
        "turboBoostNormalization",
        "backfire",
        "limiterFrequencyHz",
        "upshiftDurationSeconds",
        "downshiftDurationSeconds",
    }
    _require_exact_keys(physics, required, f"{car_id} physics")
    minimum = _finite_number(physics["minimumRpm"], f"{car_id} minimum RPM")
    maximum = _finite_number(physics["maximumRpm"], f"{car_id} maximum RPM")
    idle = _finite_number(physics["idleRpm"], f"{car_id} idle RPM")
    redline = _finite_number(physics["redlineRpm"], f"{car_id} redline RPM")
    limiter = _finite_number(physics["limiterRpm"], f"{car_id} limiter RPM")
    upshift = _finite_number(physics["upshiftRpm"], f"{car_id} upshift RPM")
    _require(minimum == 0.0, f"{car_id} minimumRpm must remain 0 for ignition compatibility")
    _require(250.0 <= idle <= 5_000.0, f"{car_id} idle RPM is outside the supported physical range")
    _require(idle + 1_000.0 <= limiter <= 25_000.0, f"{car_id} limiter RPM is not above idle")
    _require(maximum >= limiter, f"{car_id} maximum RPM is below limiter")
    _require(idle < upshift <= redline <= limiter <= maximum, f"{car_id} RPM thresholds are not ordered")
    ratios = physics["gearRatios"]
    _require(isinstance(ratios, list) and len(ratios) >= 2, f"{car_id} needs at least two forward gears")
    cleaned = [_finite_number(value, f"{car_id} gear ratio") for value in ratios]
    _require(all(value > 0.0 for value in cleaned), f"{car_id} has a non-positive gear ratio")
    _require(all(left > right for left, right in zip(cleaned, cleaned[1:])), f"{car_id} gear ratios are not strictly descending")
    final_drive = _finite_number(physics["soundFinalDriveRatio"], f"{car_id} sound final drive")
    driven_radius = _finite_number(
        physics["soundDrivenWheelRadiusMeters"],
        f"{car_id} sound driven-wheel radius",
    )
    _require(
        0.0 < final_drive <= MAXIMUM_SOUND_FINAL_DRIVE_RATIO,
        f"{car_id} sound final drive is outside (0, {MAXIMUM_SOUND_FINAL_DRIVE_RATIO}]",
    )
    _require(0.15 <= driven_radius <= 1.0, f"{car_id} sound driven-wheel radius is not physical")
    _require(
        physics["drivetrainSpeedControl"]
        == {
            "parameterName": "drivetrain_speed",
            "unit": "drivenWheelRadiansPerSecond",
            "formula": "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
            "signed": True,
        },
        f"{car_id} drivetrain_speed semantics differ",
    )
    turbos = physics["turbos"]
    _require(isinstance(turbos, list), f"{car_id} turbos is not an array")
    maximum_boost_sum = 0.0
    for index, turbo in enumerate(turbos):
        _require(isinstance(turbo, dict), f"{car_id} turbo {index} is not an object")
        _require_exact_keys(
            turbo,
            {
                "index",
                "lagUp",
                "lagDown",
                "maximumBoost",
                "wastegate",
                "referenceRpm",
                "gamma",
                "bovPressureThreshold",
            },
            f"{car_id} turbo {index}",
        )
        _require(turbo["index"] == index, f"{car_id} turbo indices are not contiguous")
        lag_up = _finite_number(turbo["lagUp"], f"{car_id} turbo {index} lagUp")
        lag_down = _finite_number(turbo["lagDown"], f"{car_id} turbo {index} lagDown")
        maximum_boost = _finite_number(turbo["maximumBoost"], f"{car_id} turbo {index} maximumBoost")
        wastegate = _finite_number(turbo["wastegate"], f"{car_id} turbo {index} wastegate")
        reference_rpm = _finite_number(turbo["referenceRpm"], f"{car_id} turbo {index} referenceRpm")
        gamma = _finite_number(turbo["gamma"], f"{car_id} turbo {index} gamma")
        bov_threshold = _finite_number(
            turbo["bovPressureThreshold"],
            f"{car_id} turbo {index} bovPressureThreshold",
        )
        _require(lag_up > 0.0 and lag_down > 0.0, f"{car_id} turbo {index} lag is not positive")
        _require(maximum_boost > 0.0, f"{car_id} turbo {index} maximum boost is not positive")
        _require(wastegate >= 0.0 and reference_rpm > 0.0 and gamma > 0.0 and bov_threshold >= 0.0, f"{car_id} turbo {index} controls are invalid")
        maximum_boost_sum += maximum_boost
    normalization = physics["turboBoostNormalization"]
    _require(isinstance(normalization, dict), f"{car_id} turbo normalization is not an object")
    _require_exact_keys(
        normalization,
        {"kind", "divisor", "minimum", "maximum"},
        f"{car_id} turbo normalization",
    )
    _require(
        normalization["kind"] == "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
        f"{car_id} turbo normalization kind differs",
    )
    divisor = _finite_number(normalization["divisor"], f"{car_id} turbo normalization divisor")
    _require(math.isclose(divisor, maximum_boost_sum, rel_tol=0.0, abs_tol=1e-9), f"{car_id} turbo normalization divisor differs")
    _require(
        _finite_number(normalization["minimum"], f"{car_id} turbo normalization minimum") == 0.0
        and _finite_number(normalization["maximum"], f"{car_id} turbo normalization maximum") == 1.0,
        f"{car_id} turbo normalization range differs",
    )
    backfire = physics["backfire"]
    _require(isinstance(backfire, dict), f"{car_id} backfire is not an object")
    _require_exact_keys(
        backfire,
        {
            "maximumGas",
            "minimumRpm",
            "maximumRpm",
            "triggerGas",
            "minimumIntentThrottle",
            "minimumIntentSeconds",
        },
        f"{car_id} backfire",
    )
    maximum_gas = _finite_number(backfire["maximumGas"], f"{car_id} backfire maximumGas")
    minimum_backfire_rpm = _finite_number(backfire["minimumRpm"], f"{car_id} backfire minimumRpm")
    maximum_backfire_rpm = _finite_number(backfire["maximumRpm"], f"{car_id} backfire maximumRpm")
    trigger_gas = _finite_number(backfire["triggerGas"], f"{car_id} backfire triggerGas")
    _require(0.0 <= maximum_gas <= 0.3 and 0.0 <= trigger_gas <= 1.0, f"{car_id} backfire gas bounds are invalid")
    _require(minimum_backfire_rpm >= 0.0 and maximum_backfire_rpm >= minimum_backfire_rpm, f"{car_id} backfire RPM bounds are invalid")
    _require(
        backfire["minimumIntentThrottle"] == GLOBAL_BACKFIRE_MINIMUM_INTENT_THROTTLE
        and backfire["minimumIntentSeconds"] == GLOBAL_BACKFIRE_MINIMUM_INTENT_SECONDS,
        f"{car_id} global backfire intent gate differs",
    )
    limiter_frequency = _finite_number(physics["limiterFrequencyHz"], f"{car_id} limiter frequency")
    _require(0.0 <= limiter_frequency <= 1_000.0, f"{car_id} limiter frequency is invalid")
    for key in ("upshiftDurationSeconds", "downshiftDurationSeconds"):
        duration = _finite_number(physics[key], f"{car_id} {key}")
        _require(0.0 < duration <= 1.0, f"{car_id} {key} is outside 0..1 second")


def _preview_map(preview_report: Path) -> dict[str, dict[str, Any]]:
    value = _read_json(preview_report)
    _require(isinstance(value, dict) and isinstance(value.get("cars"), list), "preview report has no cars")
    result: dict[str, dict[str, Any]] = {}
    for item in value["cars"]:
        car_id = str(item.get("carId") or "")
        _require(car_id and car_id not in result, f"preview report duplicates {car_id!r}")
        result[car_id] = item

    return result


def _write_preview(source: Path, destination: Path) -> dict[str, Any]:
    try:
        from PIL import Image, ImageOps
    except ImportError as error:
        raise CatalogBuildError("Pillow is required to create deterministic Android previews") from error
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
        image.thumbnail((960, 540), Image.Resampling.LANCZOS, reducing_gap=3.0)
        output = io.BytesIO()
        image.save(
            output,
            format="JPEG",
            quality=88,
            optimize=True,
            progressive=False,
            subsampling="4:2:0",
            exif=b"",
        )
        size = image.size
    content = output.getvalue()
    _write_atomic(destination, content)

    return {
        "source": str(source),
        "sourceSha256": sha256_file(source),
        "output": str(destination),
        "outputSha256": hashlib.sha256(content).hexdigest(),
        "outputBytes": len(content),
        "width": size[0],
        "height": size[1],
    }


def prepare_catalog_source(
    *,
    inventory_path: Path,
    audit_path: Path,
    preview_report_path: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    preview_assets_directory: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    rows = _load_active_inventory(inventory_path)
    active_ids = {row["car_id"] for row in rows}
    audit, family_by_car = _load_audit(audit_path, active_ids)
    previews = _preview_map(preview_report_path)
    _require(set(previews) == active_ids, "preview report does not cover exactly the active cars")
    car_config, drivetrain, acd, ac_ini = _load_audio_lab_modules(audio_lab_root)
    cars: list[dict[str, Any]] = []
    physics_evidence: list[dict[str, Any]] = []
    preview_evidence: list[dict[str, Any]] = []
    seen_profile_ids: set[str] = set()
    seen_preview_names: set[str] = set()
    for row in sorted(rows, key=lambda item: (item["display_name"].casefold(), item["car_id"])):
        car_id = row["car_id"]
        _require(bool(row["display_name"].strip()), f"{car_id} has no display name")
        car_directory = assetto_root / "content" / "cars" / car_id
        _require(car_directory.is_dir(), f"staged car directory is missing: {car_directory}")
        try:
            engine_spec = car_config.load_car_spec(assetto_root, car_id)
            drivetrain_spec = drivetrain.load_drivetrain_spec(assetto_root, car_id)
        except Exception as error:
            raise CatalogBuildError(f"Audio Lab could not load {car_id} physics: {error}") from error
        files = _physics_ini_files(acd, car_directory)
        engine_ini = _parse_ini(ac_ini, acd, files, "engine.ini")
        drivetrain_ini = _parse_ini(ac_ini, acd, files, "drivetrain.ini")
        instruments_ini = (
            _parse_ini(ac_ini, acd, files, "digital_instruments.ini")
            if "digital_instruments.ini" in files
            else None
        )
        sounds_ini = (
            _parse_ini(ac_ini, acd, files, "sounds.ini")
            if "sounds.ini" in files
            else configparser.ConfigParser()
        )
        setup_ini = _parse_ini(ac_ini, acd, files, "setup.ini") if "setup.ini" in files else None
        final_drive_ratio_tables: dict[str, str] = {}
        if setup_ini is not None and setup_ini.has_section("FINAL_GEAR_RATIO"):
            ratio_table_name = setup_ini.get("FINAL_GEAR_RATIO", "RATIOS", fallback="").strip()
            if ratio_table_name in files:
                final_drive_ratio_tables[ratio_table_name] = acd.text_file(files, ratio_table_name)
        physics, evidence = derive_physics(
            car_id=car_id,
            engine_spec=engine_spec,
            drivetrain_spec=drivetrain_spec,
            engine_ini=engine_ini,
            instruments_ini=instruments_ini,
            raw_final_drive=drivetrain_ini.get("GEARS", "FINAL", fallback=None),
            setup_ini=setup_ini,
            final_drive_ratio_tables=final_drive_ratio_tables,
            sounds_ini=sounds_ini,
        )
        normalized = safe_identifier(car_id)
        car_profile_id = profile_id(car_id)
        preview_name = f"car_previews/{normalized}.jpg"
        _require(PREVIEW_NAME_PATTERN.fullmatch(preview_name) is not None, f"preview path is not APK-safe: {preview_name}")
        _require(car_profile_id not in seen_profile_ids, f"normalized profile id collides: {car_profile_id}")
        _require(preview_name not in seen_preview_names, f"normalized preview path collides: {preview_name}")
        seen_profile_ids.add(car_profile_id)
        seen_preview_names.add(preview_name)
        preview_item = previews[car_id]
        preview_source = Path(str(preview_item.get("path") or "")).resolve(strict=True)
        expected_preview_sha = str(preview_item.get("sha256") or "")
        _require(sha256_file(preview_source) == expected_preview_sha, f"preview source hash changed for {car_id}")
        preview_evidence.append(
            {
                "carId": car_id,
                **_write_preview(preview_source, preview_assets_directory / f"{normalized}.jpg"),
            }
        )
        family = family_by_car[car_id]
        specifications = {
            "assettoCorsaCarId": car_id,
            **({"brand": row["brand"]} if row.get("brand") else {}),
            **({"year": row["year"]} if row.get("year") else {}),
            **({"class": row["class"]} if row.get("class") else {}),
        }
        cars.append(
            {
                "id": car_profile_id,
                "sourceCarId": car_id,
                "displayName": row["display_name"],
                "bankFamilyId": family["familyId"],
                "bankSha256": family["bankSha256"],
                "previewAssetName": preview_name,
                "physics": physics,
                "specifications": specifications,
            }
        )
        physics_evidence.append({"carId": car_id, "profileId": car_profile_id, "fields": evidence})

    expected_preview_files = {Path(name).name for name in seen_preview_names}
    actual_preview_files = {
        path.name for path in preview_assets_directory.iterdir() if path.is_file()
    }
    _require(
        actual_preview_files == expected_preview_files,
        "preview asset directory contains stale or missing files: "
        f"missing={sorted(expected_preview_files - actual_preview_files)}, "
        f"extra={sorted(actual_preview_files - expected_preview_files)}",
    )
    preview_bytes = sum(item["outputBytes"] for item in preview_evidence)
    largest_preview_bytes = max(item["outputBytes"] for item in preview_evidence)
    _require(largest_preview_bytes <= MAXIMUM_PREVIEW_BYTES, "a generated preview exceeds 256 KiB")
    _require(preview_bytes <= MAXIMUM_ALL_PREVIEW_BYTES, "generated previews exceed the 4 MiB APK budget")

    families = [
        {
            "bankFamilyId": family["familyId"],
            "bankSha256": family["bankSha256"],
            "representativeCarId": family["representativeCarId"],
            "memberCarIds": sorted(family["memberCarIds"]),
            "requiredRetainedSourceGuidCount": family["conservation"]["requiredRetainedSourceGuidCount"],
        }
        for family in sorted(audit["families"], key=lambda item: item["familyId"])
    ]
    source = {
        "schema": SOURCE_SCHEMA,
        "catalogVersion": CATALOG_VERSION,
        "inventorySha256": sha256_file(inventory_path),
        "auditSha256": sha256_file(audit_path),
        "cars": cars,
        "families": families,
    }
    report = {
        "schema": "byd-car-atlas-catalog-preparation-report-v1",
        "status": "PASS",
        "activeCarCount": len(cars),
        "bankFamilyCount": len(families),
        "deduplicatedCarCount": len(cars) - len(families),
        "totalPreviewBytes": preview_bytes,
        "largestPreviewBytes": largest_preview_bytes,
        "physicsEvidence": physics_evidence,
        "previewEvidence": preview_evidence,
    }

    return source, report


def _load_source_catalog(path: Path) -> dict[str, Any]:
    value = _read_json(path)
    _require(isinstance(value, dict) and value.get("schema") == SOURCE_SCHEMA, f"{path} is not {SOURCE_SCHEMA}")
    _require_exact_keys(
        value,
        {"schema", "catalogVersion", "inventorySha256", "auditSha256", "cars", "families"},
        "source catalog",
    )
    _require(value.get("catalogVersion") == CATALOG_VERSION, "source catalog version differs")
    _require(SHA256_PATTERN.fullmatch(str(value.get("inventorySha256") or "")) is not None, "source inventory hash is invalid")
    _require(SHA256_PATTERN.fullmatch(str(value.get("auditSha256") or "")) is not None, "source audit hash is invalid")
    _require(isinstance(value.get("cars"), list) and len(value["cars"]) == EXPECTED_ACTIVE_CARS, "source catalog car count differs")
    _require(isinstance(value.get("families"), list) and len(value["families"]) == EXPECTED_BANK_FAMILIES, "source catalog family count differs")

    families_by_id: dict[str, Mapping[str, Any]] = {}
    family_members: dict[str, str] = {}
    bank_hashes: set[str] = set()
    for family in value["families"]:
        _require(isinstance(family, dict), "source catalog contains a non-object family")
        _require_exact_keys(
            family,
            {
                "bankFamilyId",
                "bankSha256",
                "representativeCarId",
                "memberCarIds",
                "requiredRetainedSourceGuidCount",
            },
            "source catalog family",
        )
        family_id = str(family["bankFamilyId"])
        bank_sha = str(family["bankSha256"])
        members = family["memberCarIds"]
        _require(PACK_ID_PATTERN.fullmatch(family_id) is not None, f"source family id {family_id!r} is unsafe")
        _require(family_id not in families_by_id, f"source catalog duplicates family {family_id}")
        _require(SHA256_PATTERN.fullmatch(bank_sha) is not None, f"source family {family_id} bank hash is invalid")
        _require(bank_sha not in bank_hashes, f"source catalog contains two families for bank {bank_sha}")
        _require(isinstance(members, list) and members == sorted(set(members)) and members, f"source family {family_id} members are not unique and sorted")
        _require(family["representativeCarId"] in members, f"source family {family_id} representative is not a member")
        source_count = family["requiredRetainedSourceGuidCount"]
        _require(isinstance(source_count, int) and not isinstance(source_count, bool) and source_count > 0, f"source family {family_id} retained-source count is invalid")
        for member in members:
            _require(isinstance(member, str) and member and member not in family_members, f"source car {member!r} belongs to multiple families")
            family_members[member] = family_id
        families_by_id[family_id] = family
        bank_hashes.add(bank_sha)

    seen_ids: set[str] = set()
    seen_previews: set[str] = set()
    source_car_ids: set[str] = set()
    allowed_specification_keys = {"assettoCorsaCarId", "brand", "year", "class"}
    for car in value["cars"]:
        _require(isinstance(car, dict), "source catalog contains a non-object car")
        _require_exact_keys(
            car,
            {
                "id",
                "sourceCarId",
                "displayName",
                "bankFamilyId",
                "bankSha256",
                "previewAssetName",
                "physics",
                "specifications",
            },
            "source catalog car",
        )
        car_id = str(car["sourceCarId"])
        generated_id = str(car["id"])
        family_id = str(car["bankFamilyId"])
        preview_name = str(car["previewAssetName"])
        display_name = str(car["displayName"])
        _require(car_id and car_id not in source_car_ids, f"source catalog duplicates car {car_id!r}")
        _require(generated_id == profile_id(car_id) and generated_id not in seen_ids, f"source car {car_id} has an invalid/colliding profile id")
        _require(display_name.strip() == display_name and 0 < len(display_name) <= 120, f"source car {car_id} display name is invalid")
        _require(PREVIEW_NAME_PATTERN.fullmatch(preview_name) is not None, f"source car {car_id} preview path is unsafe")
        _require(preview_name == f"car_previews/{safe_identifier(car_id)}.jpg", f"source car {car_id} preview path is not canonical")
        _require(preview_name not in seen_previews, f"source catalog duplicates preview {preview_name}")
        family = families_by_id.get(family_id)
        _require(family is not None and family_members.get(car_id) == family_id, f"source car {car_id} family membership differs")
        _require(car["bankSha256"] == family["bankSha256"], f"source car {car_id} bank hash differs from its family")
        physics = car["physics"]
        _require(isinstance(physics, dict), f"source car {car_id} physics is not an object")
        validate_physics(physics, car_id)
        specifications = car["specifications"]
        _require(isinstance(specifications, dict), f"source car {car_id} specifications are not an object")
        _require(set(specifications) <= allowed_specification_keys, f"source car {car_id} specifications contain unknown fields")
        _require(specifications.get("assettoCorsaCarId") == car_id, f"source car {car_id} provenance id differs")
        _require(
            all(isinstance(item, str) and item.strip() == item and item for item in specifications.values()),
            f"source car {car_id} specifications contain an invalid value",
        )
        seen_ids.add(generated_id)
        seen_previews.add(preview_name)
        source_car_ids.add(car_id)
    _require(source_car_ids == set(family_members), "source catalog car/family membership differs")

    return value


def _batch_family_map(batch: Mapping[str, Any], source: Mapping[str, Any]) -> dict[str, str]:
    _require(batch.get("schema") == BATCH_SCHEMA, f"atlas batch is not {BATCH_SCHEMA}")
    _require(batch.get("carCount") == EXPECTED_ACTIVE_CARS, "atlas batch car count differs")
    _require(batch.get("familyCount") == EXPECTED_BANK_FAMILIES, "atlas batch did not deduplicate to 32 bank families")
    rows = batch.get("cars")
    _require(isinstance(rows, list), "atlas batch has no car mapping")
    program_by_car: dict[str, str] = {}
    for row in rows:
        car_id = str(row.get("carId") or "")
        program_id = str(row.get("audioProgramFamilyId") or "")
        _require(car_id and program_id and car_id not in program_by_car, "atlas batch has an invalid car mapping")
        program_by_car[car_id] = program_id
    expected_car_ids = {car["sourceCarId"] for car in source["cars"]}
    _require(set(program_by_car) == expected_car_ids, "atlas batch car mapping differs from source catalog")
    result: dict[str, str] = {}
    for family in source["families"]:
        ids = {program_by_car[car_id] for car_id in family["memberCarIds"]}
        _require(len(ids) == 1, f"bank family {family['bankFamilyId']} was rendered more than once")
        program_id = ids.pop()
        previous = result.setdefault(family["bankFamilyId"], program_id)
        _require(previous == program_id, f"bank family {family['bankFamilyId']} has conflicting atlas ids")
    _require(len(set(result.values())) == EXPECTED_BANK_FAMILIES, "different bank families share an atlas id")

    return result


def read_canonical_atlas_wav(path: Path) -> WavMetadata:
    size_bytes = path.stat().st_size
    _require(size_bytes >= 44, f"WAV is too short: {path}")
    with path.open("rb") as source:
        riff_header = source.read(12)
        _require(riff_header[:4] == b"RIFF" and riff_header[8:12] == b"WAVE", f"WAV has no RIFF/WAVE header: {path}")
        _require(struct.unpack_from("<I", riff_header, 4)[0] == size_bytes - 8, f"WAV RIFF size differs: {path}")
        fmt_header = source.read(8)
        _require(len(fmt_header) == 8 and fmt_header[:4] == b"fmt ", f"WAV does not start with fmt: {path}")
        fmt_size = struct.unpack_from("<I", fmt_header, 4)[0]
        _require(fmt_size == 16, f"WAV fmt chunk is not canonical PCM: {path}")
        fmt = source.read(fmt_size)
        _require(len(fmt) == fmt_size, f"WAV fmt chunk is truncated: {path}")
        data_header = source.read(8)
        _require(len(data_header) == 8 and data_header[:4] == b"data", f"WAV does not contain one canonical data chunk: {path}")
        pcm_size = struct.unpack_from("<I", data_header, 4)[0]
        _require(source.tell() + pcm_size == size_bytes, f"WAV has truncated PCM or extra chunks: {path}")
    format_code, channels, sample_rate, byte_rate, block_align, bits = struct.unpack("<HHIIHH", fmt)
    _require(format_code == 1 and channels == 2 and sample_rate == 48_000 and bits == 16, f"WAV is not PCM16/48k/stereo: {path}")
    _require(block_align == 4 and byte_rate == 192_000, f"WAV byte geometry is invalid: {path}")
    _require(pcm_size % block_align == 0 and pcm_size >= 32 * block_align, f"WAV PCM length is invalid: {path}")

    return WavMetadata(
        size_bytes=size_bytes,
        sha256=sha256_file(path),
        sample_rate=sample_rate,
        channels=channels,
        frame_count=pcm_size // block_align,
    )


def validate_sealed_family_archive(
    *,
    atlas_root: Path,
    family_directory: Path,
    runtime_id: str,
    plan_sha256: str,
    bank_sha256: str,
    runtime: Mapping[str, Any],
    atlas_pack_report: Mapping[str, Any],
) -> dict[str, Any]:
    """Stream-validate a reclaimed family's canonical final ``.bydpack``."""

    descriptor_path = family_directory / SEALED_FAMILY_DESCRIPTOR_FILE_NAME
    descriptor = _read_json(descriptor_path)
    _require(isinstance(descriptor, dict), f"{runtime_id} sealed descriptor is not an object")
    _require_exact_keys(
        descriptor,
        {
            "schema",
            "status",
            "atlasFamilyId",
            "planSha256",
            "bankSha256",
            "memberCarIds",
            "archiveRelativePath",
            "packRequirement",
            "archive",
            "runtimeIndexSha256",
            "atlasPackReportSha256",
        },
        f"{runtime_id} sealed descriptor",
    )
    _require(
        descriptor.get("schema") == SEALED_FAMILY_SCHEMA
        and descriptor.get("status") == "PASS"
        and descriptor.get("atlasFamilyId") == runtime_id
        and descriptor.get("planSha256") == plan_sha256
        and descriptor.get("bankSha256") == bank_sha256,
        f"{runtime_id} sealed descriptor identity differs",
    )
    runtime_path = family_directory / "runtime-index.json"
    pack_report_path = family_directory / "pack-report.json"
    _require(
        descriptor.get("runtimeIndexSha256") == sha256_file(runtime_path)
        and descriptor.get("atlasPackReportSha256") == sha256_file(pack_report_path),
        f"{runtime_id} sealed descriptor report/runtime attestation differs",
    )
    raw_relative = descriptor.get("archiveRelativePath")
    _require(isinstance(raw_relative, str) and raw_relative, f"{runtime_id} sealed archive path is missing")
    relative = Path(raw_relative)
    _require(
        not relative.is_absolute()
        and len(relative.parts) == 2
        and relative.parts[0] == SEALED_PACK_DIRECTORY_NAME
        and relative.suffix == ".bydpack"
        and relative.name == relative.parts[1],
        f"{runtime_id} sealed archive path is unsafe",
    )
    expected_root = (atlas_root / SEALED_PACK_DIRECTORY_NAME).resolve(strict=True)
    archive_path = (atlas_root / relative).resolve(strict=True)
    _require(
        archive_path.parent == expected_root,
        f"{runtime_id} sealed archive escapes its release directory",
    )
    requirement = descriptor.get("packRequirement")
    archive_evidence = descriptor.get("archive")
    _require(isinstance(requirement, dict), f"{runtime_id} sealed pack requirement is missing")
    _require_exact_keys(
        requirement,
        {"packId", "packVersion", "manifestSha256"},
        f"{runtime_id} sealed pack requirement",
    )
    _require(
        requirement.get("packId") == f"byd.atlas.{runtime_id}"
        and isinstance(requirement.get("packVersion"), int)
        and not isinstance(requirement.get("packVersion"), bool)
        and 1 <= requirement["packVersion"] <= 2_147_483_647
        and SHA256_PATTERN.fullmatch(str(requirement.get("manifestSha256") or ""))
        is not None,
        f"{runtime_id} sealed pack requirement is invalid",
    )
    _require(isinstance(archive_evidence, dict), f"{runtime_id} sealed archive evidence is missing")
    _require_exact_keys(
        archive_evidence,
        {"sha256", "bytes", "fileCount", "payloadBytes"},
        f"{runtime_id} sealed archive evidence",
    )
    archive_bytes = archive_path.stat().st_size
    _require(
        archive_bytes == archive_evidence.get("bytes")
        and archive_bytes <= MAXIMUM_ARCHIVE_BYTES
        and sha256_file(archive_path) == archive_evidence.get("sha256"),
        f"{runtime_id} sealed archive bytes/hash differ",
    )
    declared = runtime.get("shards")
    report_shards = atlas_pack_report.get("shards")
    _require(isinstance(declared, list) and declared, f"{runtime_id} runtime declares no shards")
    _require(isinstance(report_shards, list), f"{runtime_id} atlas pack report has no shards")
    declared_by_name = {
        str(item.get("name")): item for item in declared if isinstance(item, Mapping)
    }
    report_by_name = {
        str(item.get("shardName")): item
        for item in report_shards
        if isinstance(item, Mapping)
    }
    _require(
        len(declared_by_name) == len(declared)
        and len(report_by_name) == len(report_shards)
        and set(declared_by_name) == set(report_by_name),
        f"{runtime_id} sealed archive shard declarations differ",
    )
    wav_metadata: dict[str, WavMetadata] = {}
    with zipfile.ZipFile(archive_path, "r") as archive:
        members = archive.infolist()
        names = [item.filename for item in members]
        _require(
            names and names[0] == "manifest.json" and names[1:] == sorted(names[1:])
            and len(names) == len(set(names))
            and len(names) <= MAXIMUM_MEMBER_COUNT,
            f"{runtime_id} sealed ZIP member set/order differs",
        )
        _require(
            all(
                item.compress_type == zipfile.ZIP_STORED
                and item.date_time == (1980, 1, 1, 0, 0, 0)
                and item.create_system == 3
                and item.extra == b""
                and item.comment == b""
                and item.file_size <= MAXIMUM_MEMBER_BYTES
                for item in members
            )
            and archive.comment == b"",
            f"{runtime_id} sealed ZIP metadata is not deterministic/importable",
        )
        manifest_bytes = archive.read("manifest.json")
        _require(len(manifest_bytes) <= MAXIMUM_MANIFEST_BYTES, f"{runtime_id} sealed manifest is oversized")
        manifest = json.loads(manifest_bytes)
        _require(
            isinstance(manifest, dict)
            and manifest_bytes == canonical_json_bytes(manifest)
            and manifest.get("schemaVersion") == PACK_MANIFEST_SCHEMA_VERSION
            and manifest.get("packId") == requirement["packId"]
            and manifest.get("packVersion") == requirement["packVersion"]
            and hashlib.sha256(manifest_bytes).hexdigest()
            == requirement["manifestSha256"],
            f"{runtime_id} sealed manifest identity differs",
        )
        files = manifest.get("files")
        _require(isinstance(files, list), f"{runtime_id} sealed manifest has no files")
        expected_member_names = [str(item.get("path")) for item in files if isinstance(item, Mapping)]
        _require(
            len(expected_member_names) == len(files)
            and expected_member_names == names[1:],
            f"{runtime_id} sealed manifest/ZIP member set differs",
        )
        payload_bytes = 0
        for index, item in enumerate(files):
            _require(isinstance(item, dict), f"{runtime_id} sealed manifest file {index} is invalid")
            _require_exact_keys(
                item,
                {"path", "sizeBytes", "sha256", "sampleRate", "channels", "frameCount"},
                f"{runtime_id} sealed manifest file {index}",
            )
            member_path = str(item["path"])
            prefix = f"sample_engine/{runtime_id}/"
            _require(member_path.startswith(prefix), f"{runtime_id} sealed member path differs")
            shard_name = member_path[len(prefix) :]
            _require(
                SHARD_NAME_PATTERN.fullmatch(shard_name) is not None
                and shard_name in declared_by_name
                and shard_name not in wav_metadata,
                f"{runtime_id} sealed member has an invalid/duplicate shard",
            )
            digest = hashlib.sha256()
            observed_bytes = 0
            header = bytearray()
            with archive.open(member_path, "r") as source:
                while block := source.read(1024 * 1024):
                    if len(header) < 44:
                        header.extend(block[: 44 - len(header)])
                    digest.update(block)
                    observed_bytes += len(block)
            payload_bytes += observed_bytes
            _require(
                len(header) == 44
                and header[:4] == b"RIFF"
                and header[8:12] == b"WAVE"
                and header[12:16] == b"fmt "
                and struct.unpack_from("<I", header, 16)[0] == 16
                and header[36:40] == b"data",
                f"{runtime_id}/{shard_name} sealed WAV header is not canonical",
            )
            riff_size = struct.unpack_from("<I", header, 4)[0]
            format_code, channels, sample_rate, byte_rate, block_align, bits = struct.unpack_from(
                "<HHIIHH", header, 20
            )
            pcm_size = struct.unpack_from("<I", header, 40)[0]
            metadata = WavMetadata(
                size_bytes=observed_bytes,
                sha256=digest.hexdigest(),
                sample_rate=sample_rate,
                channels=channels,
                frame_count=pcm_size // block_align if block_align else 0,
            )
            declaration = declared_by_name[shard_name]
            report_item = report_by_name[shard_name]
            _require(
                riff_size == observed_bytes - 8
                and format_code == 1
                and channels == 2
                and sample_rate == 48_000
                and byte_rate == 192_000
                and block_align == 4
                and bits == 16
                and pcm_size % block_align == 0
                and pcm_size >= 32 * block_align
                and observed_bytes == 44 + pcm_size
                and item["sizeBytes"] == observed_bytes
                and item["sha256"] == metadata.sha256
                and item["sampleRate"] == sample_rate
                and item["channels"] == channels
                and item["frameCount"] == metadata.frame_count
                and declaration.get("bytes") == observed_bytes
                and declaration.get("sha256") == metadata.sha256
                and report_item.get("bytes") == observed_bytes
                and report_item.get("sha256") == metadata.sha256,
                f"{runtime_id}/{shard_name} sealed WAV evidence differs",
            )
            wav_metadata[shard_name] = metadata
    _require(
        set(wav_metadata) == set(declared_by_name)
        and payload_bytes == archive_evidence.get("payloadBytes")
        and payload_bytes <= MAXIMUM_EXTRACTED_BYTES
        and len(wav_metadata) == archive_evidence.get("fileCount"),
        f"{runtime_id} sealed archive coverage/count differs",
    )

    return {
        "descriptor": descriptor,
        "archivePath": archive_path,
        "packRequirement": requirement,
        "packReport": {
            "packPath": str(archive_path),
            "packSha256": archive_evidence["sha256"],
            "packBytes": archive_evidence["bytes"],
            "manifestSha256": requirement["manifestSha256"],
            "fileCount": archive_evidence["fileCount"],
            "payloadBytes": archive_evidence["payloadBytes"],
        },
        "wavMetadata": wav_metadata,
    }


def _collect_shard_references(value: Any) -> list[str]:
    references: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "shardName":
                _require(isinstance(child, str), "runtime shardName is not a string")
                references.append(child)
            else:
                references.extend(_collect_shard_references(child))
    elif isinstance(value, list):
        for child in value:
            references.extend(_collect_shard_references(child))

    return references


def _compact_effect_node_records(effects: Mapping[str, Any]) -> list[list[Any]]:
    """Return v4 effect-node arrays after their contract has been validated."""

    events = effects.get("events")
    _require(isinstance(events, list), "compact effects have no event array")
    records: list[list[Any]] = []
    for event in events:
        _require(isinstance(event, dict), "compact effects include an invalid event")
        nodes = event.get("nodes")
        _require(isinstance(nodes, list), "compact effects include no node array")
        for node in nodes:
            _require(
                isinstance(node, list) and len(node) == len(COMPACT_EFFECT_NODE_FIELDS),
                "compact effect node array is invalid",
            )
            records.append(node)
    return records


def _validate_source_conservation(atlas_root: Path, member_car_ids: Sequence[str], bank_sha: str) -> list[str]:
    hashes: list[str] = []
    for car_id in member_car_ids:
        path = atlas_root / "cars" / car_id / "source-conservation-report.json"
        recipe = _read_json(path)
        audit = recipe.get("sourceConservationAudit")
        _require(isinstance(audit, dict) and audit.get("exactGuidSetEquality") is True, f"{car_id} source conservation equality did not pass")
        _require(not audit.get("unmappedCoreBindings"), f"{car_id} has unmapped core bindings")
        _require(recipe.get("bank", {}).get("sha256") == bank_sha, f"{car_id} source recipe bank hash differs")
        hashes.append(sha256_file(path))

    return hashes


def _required_scheduling_group_ids(plan: Mapping[str, Any], runtime_id: str) -> list[str]:
    effects = plan.get("effects")
    _require(isinstance(effects, list), f"{runtime_id} plan effects are missing")
    group_ids: set[str] = set()
    for event_index, event in enumerate(effects):
        _require(isinstance(event, dict), f"{runtime_id} plan effect {event_index} is not an object")
        lifecycle = event.get("runtimeLifecycleParameterVariantContract")
        _require(isinstance(lifecycle, dict), f"{runtime_id} plan effect {event_index} has no lifecycle contract")
        variants = lifecycle.get("variants")
        _require(isinstance(variants, list) and variants, f"{runtime_id} plan effect {event_index} has no lifecycle variants")
        for variant_index, variant in enumerate(variants):
            _require(isinstance(variant, dict), f"{runtime_id} plan effect {event_index} variant {variant_index} is not an object")
            scheduling_group = variant.get("schedulingGroup")
            _require(
                isinstance(scheduling_group, dict),
                f"{runtime_id} plan effect {event_index} variant {variant_index} has no scheduling group",
            )
            group_id = scheduling_group.get("groupId")
            _require(
                isinstance(group_id, str)
                and SCHEDULING_GROUP_ID_PATTERN.fullmatch(group_id) is not None,
                f"{runtime_id} plan effect {event_index} variant {variant_index} has an unsafe scheduling group id",
            )
            group_ids.add(group_id)

    _require(group_ids, f"{runtime_id} plan has no executable effect scheduling groups")

    return sorted(group_ids)


def _validate_combined_engine_effect_mix_oracle(
    oracle: Mapping[str, Any],
    plan: Mapping[str, Any],
    runtime_id: str,
) -> None:
    combined = oracle.get("combinedEngineEffectMixOracle")
    _require(isinstance(combined, dict), f"{runtime_id} oracle has no combined engine/effect mix report")
    required_fields = {
        "schema",
        "required",
        "status",
        "allScenariosPass",
        "scenarioCount",
        "requiredSchedulingGroupIds",
        "scenarios",
        "lifecycleOracle",
        "parameterPlacementLifecycleOracle",
        "playlistSelectionOracle",
        "globalFmodChannelArbitrationOracle",
    }
    _require(
        required_fields <= set(combined),
        f"{runtime_id} combined engine/effect mix report is missing required fields",
    )
    _require(
        combined.get("schema") == COMBINED_ENGINE_EFFECT_MIX_ORACLE_SCHEMA,
        f"{runtime_id} combined engine/effect mix report uses an old or unknown schema",
    )
    _require(combined.get("required") is True, f"{runtime_id} combined engine/effect mix oracle is not required")
    _require(
        combined.get("status") == "PASS" and combined.get("allScenariosPass") is True,
        f"{runtime_id} combined engine/effect mix oracle did not pass",
    )
    required_group_ids = _required_scheduling_group_ids(plan, runtime_id)
    reported_group_ids = combined.get("requiredSchedulingGroupIds")
    _require(
        isinstance(reported_group_ids, list) and all(isinstance(group_id, str) for group_id in reported_group_ids),
        f"{runtime_id} combined engine/effect mix scheduling groups are invalid",
    )
    _require(
        reported_group_ids == required_group_ids,
        f"{runtime_id} combined engine/effect mix required scheduling groups differ from the plan",
    )
    scenario_count = combined.get("scenarioCount")
    scenarios = combined.get("scenarios")
    _require(isinstance(scenario_count, int) and scenario_count > 0, f"{runtime_id} combined engine/effect mix has no scenarios")
    _require(isinstance(scenarios, list) and len(scenarios) == scenario_count, f"{runtime_id} combined engine/effect mix scenario count differs")
    covered_group_ids: set[str] = set()
    scenario_ids: set[str] = set()
    for index, scenario in enumerate(scenarios):
        _require(isinstance(scenario, dict), f"{runtime_id} combined engine/effect mix scenario {index} is not an object")
        _require(
            {"id", "pass", "schedulingGroupIds"} <= set(scenario),
            f"{runtime_id} combined engine/effect mix scenario {index} is missing required fields",
        )
        scenario_id = scenario.get("id")
        _require(
            isinstance(scenario_id, str) and scenario_id and scenario_id not in scenario_ids,
            f"{runtime_id} combined engine/effect mix has an invalid or duplicate scenario id",
        )
        scenario_ids.add(scenario_id)
        _require(scenario.get("pass") is True, f"{runtime_id} combined engine/effect mix scenario {scenario_id} failed")
        scenario_groups = scenario.get("schedulingGroupIds")
        _require(
            isinstance(scenario_groups, list) and scenario_groups and all(isinstance(group_id, str) for group_id in scenario_groups),
            f"{runtime_id} combined engine/effect mix scenario {scenario_id} has invalid scheduling-group coverage",
        )
        _require(
            len(scenario_groups) == len(set(scenario_groups)),
            f"{runtime_id} combined engine/effect mix scenario {scenario_id} repeats a scheduling group",
        )
        covered_group_ids.update(scenario_groups)
    _require(
        covered_group_ids == set(required_group_ids),
        f"{runtime_id} combined engine/effect mix scenario coverage differs from required scheduling groups",
    )
    lifecycle = combined.get("lifecycleOracle")
    playlist = combined.get("playlistSelectionOracle")
    _require(
        isinstance(lifecycle, dict) and lifecycle.get("allPass") is True,
        f"{runtime_id} combined engine/effect lifecycle oracle did not pass",
    )
    _require(
        isinstance(playlist, dict) and playlist.get("allPass") is True,
        f"{runtime_id} playlist selection oracle did not pass",
    )
    _validate_parameter_placement_lifecycle_oracle(combined, plan, runtime_id)
    channel_gate = (plan.get("refinementGate") or {}).get(
        "globalFmodChannelArbitrationOracle"
    )
    channel_report = combined.get("globalFmodChannelArbitrationOracle")
    observed_budget = channel_gate.get("observedVoiceBudgetOracle")
    _require(
        isinstance(channel_gate, dict)
        and channel_gate.get("required") is True
        and channel_gate.get("schema")
        == "byd-full-event-fmod-channel-arbitration-oracle-v2"
        and channel_gate.get("assettoStudioLogicalChannelCap") == 2048
        and channel_gate.get("assettoSoftwareRealChannelBudget") == 256
        and channel_gate.get("rendererInitializationOrder")
        == [
            "FMOD_Studio_System_Create",
            "FMOD_Studio_System_GetLowLevelSystem",
            "FMOD_System_SetSoftwareChannels(256)",
            "FMOD_Studio_System_Initialize(2048)",
        ]
        and isinstance(channel_gate.get("requiredScenarios"), list)
        and isinstance(channel_gate.get("requiredEvidence"), list)
        and isinstance(observed_budget, dict)
        and observed_budget.get("schema")
        == "byd-full-event-fmod-voice-budget-input-v1"
        and observed_budget.get("status") == "PASS_WITH_BOUNDED_CLAIMS"
        and SHA256_PATTERN.fullmatch(str(observed_budget.get("reportSha256") or ""))
        is not None,
        f"{runtime_id} plan has no executable global FMOD channel arbitration gate",
    )
    _require(
        isinstance(channel_report, dict)
        and channel_report.get("schema")
        == "byd-full-event-fmod-channel-arbitration-oracle-v2"
        and channel_report.get("required") is True
        and channel_report.get("status") == "PASS"
        and channel_report.get("assettoStudioLogicalChannelCap") == 2048
        and channel_report.get("assettoSoftwareRealChannelBudget") == 256
        and channel_report.get("rendererInitializationOrder")
        == channel_gate["rendererInitializationOrder"]
        and channel_report.get("requiredScenarios")
        == channel_gate["requiredScenarios"]
        and channel_report.get("requiredEvidence")
        == channel_gate["requiredEvidence"]
        and channel_report.get("premixAdmissionParity")
        == channel_gate.get("premixAdmissionParity")
        and channel_report.get("observedVoiceBudgetOracle") == observed_budget
        and isinstance(channel_report.get("policy"), dict)
        and isinstance(channel_report.get("scenarios"), list)
        and len(channel_report["scenarios"]) == len(channel_gate["requiredScenarios"]),
        f"{runtime_id} global FMOD channel arbitration oracle did not pass",
    )


def _placement_entry_sources(
    plan: Mapping[str, Any], runtime_id: str
) -> dict[tuple[str, str], Mapping[str, Any]]:
    """Return every plan variant with the executable placement-entry trigger."""

    effects = plan.get("effects")
    _require(isinstance(effects, list), f"{runtime_id} plan effects are missing")
    expected: dict[tuple[str, str], Mapping[str, Any]] = {}
    for event_index, event in enumerate(effects):
        _require(isinstance(event, dict), f"{runtime_id} plan effect {event_index} is not an object")
        event_path = event.get("eventPath")
        lifecycle = event.get("runtimeLifecycleParameterVariantContract")
        _require(
            isinstance(event_path, str) and isinstance(lifecycle, dict),
            f"{runtime_id} plan effect {event_index} has no lifecycle contract",
        )
        variants = lifecycle.get("variants")
        _require(isinstance(variants, list), f"{runtime_id} plan effect {event_index} variants are invalid")
        for variant_index, variant in enumerate(variants):
            _require(isinstance(variant, dict), f"{runtime_id} plan effect {event_index} variant {variant_index} is not an object")
            source_guid = variant.get("sourceGuid")
            mapping = variant.get("runtimeMapping")
            semantic = mapping.get("semanticLifecycle") if isinstance(mapping, dict) else None
            if not isinstance(semantic, list):
                continue
            entry = next(
                (
                    item
                    for item in semantic
                    if isinstance(item, dict)
                    and item.get("trigger") == "PARAMETER_PLACEMENT_ENTRY"
                ),
                None,
            )
            if entry is None:
                continue
            _require(
                isinstance(source_guid, str) and source_guid
                and isinstance(entry.get("parameterPlacementEntry"), dict),
                f"{runtime_id} plan placement-entry variant is incomplete",
            )
            key = (event_path, source_guid)
            _require(key not in expected, f"{runtime_id} plan duplicates placement-entry source {key}")
            expected[key] = entry["parameterPlacementEntry"]
    return expected


def _validate_parameter_placement_lifecycle_oracle(
    combined: Mapping[str, Any], plan: Mapping[str, Any], runtime_id: str
) -> None:
    """Fail closed unless every finite parameter-sheet entry has source-solo proof."""

    report = combined.get("parameterPlacementLifecycleOracle")
    _require(isinstance(report, dict), f"{runtime_id} has no parameter-placement lifecycle oracle")
    _require(
        report.get("schema") == "byd-full-event-parameter-placement-lifecycle-oracle-v1"
        and report.get("allPass") is True,
        f"{runtime_id} parameter-placement lifecycle oracle did not pass",
    )
    expected = _placement_entry_sources(plan, runtime_id)
    sources = report.get("sources")
    _require(
        isinstance(report.get("sourceCount"), int)
        and report["sourceCount"] == len(expected)
        and isinstance(sources, list)
        and len(sources) == len(expected),
        f"{runtime_id} parameter-placement lifecycle source coverage differs from the plan",
    )
    actual: set[tuple[str, str]] = set()
    for index, source in enumerate(sources):
        _require(isinstance(source, dict), f"{runtime_id} placement lifecycle source {index} is not an object")
        event_path = source.get("eventPath")
        source_guid = source.get("sourceGuid")
        key = (event_path, source_guid)
        _require(
            key in expected and key not in actual
            and source.get("placementEntry") == expected[key]
            and source.get("pass") is True
            and isinstance(source.get("runtimeInitialMembership"), bool),
            f"{runtime_id} placement lifecycle source {index} has invalid identity or contract",
        )
        actual.add(key)
        vectors = source.get("vectors")
        _require(
            isinstance(vectors, list) and vectors,
            f"{runtime_id} placement lifecycle source {source_guid} has no vectors",
        )
        for vector_index, vector in enumerate(vectors):
            _require(
                isinstance(vector, dict)
                and isinstance(vector.get("id"), str)
                and vector.get("pass") is True
                and isinstance(vector.get("expectedCallbacks"), int)
                and vector["expectedCallbacks"] >= 0
                and isinstance(vector.get("scheduledSoundNames"), list)
                and SHA256_PATTERN.fullmatch(str(vector.get("callbackTraceSha256") or "")) is not None
                and SHA256_PATTERN.fullmatch(str(vector.get("oracleWavSha256") or "")) is not None
                and vector.get("sourceIsolationMethod")
                == "sourceSoloEventRoutingAndBusDsp-v1",
                f"{runtime_id} placement lifecycle source {source_guid} vector {vector_index} is incomplete",
            )
    _require(
        actual == set(expected),
        f"{runtime_id} parameter-placement lifecycle sources differ from the plan",
    )


def _validate_finite_effect_interpolation_oracle(
    oracle: Mapping[str, Any], plan: Mapping[str, Any], runtime_id: str
) -> None:
    """Require source-level intermediate residual evidence for every varying finite source."""

    report = oracle.get("finiteEffectInterpolationOracle")
    _require(
        isinstance(report, dict)
        and report.get("schema") == "byd-full-event-finite-interpolation-oracle-v1"
        and report.get("runtimeAlgorithm")
        == "perSourceAxisAlignedMultilinearFiniteRing-v2"
        and report.get("allPass") is True,
        f"{runtime_id} finite effect interpolation oracle did not pass",
    )
    expected: set[tuple[str, str]] = set()
    for event in plan.get("effects", []):
        if not isinstance(event, dict):
            continue
        event_path = event.get("eventPath")
        lifecycle = event.get("runtimeLifecycleParameterVariantContract", {})
        variants = lifecycle.get("variants", []) if isinstance(lifecycle, dict) else []
        for variant in variants:
            axes = variant.get("parameterAxes") if isinstance(variant, dict) else None
            if (
                isinstance(event_path, str)
                and isinstance(variant, dict)
                and variant.get("lifetime") != "continuous"
                and isinstance(variant.get("sourceGuid"), str)
                and isinstance(axes, dict)
                and any(isinstance(values, list) and len(values) > 1 for values in axes.values())
            ):
                expected.add((event_path, variant["sourceGuid"]))
    residuals = report.get("sourceResiduals")
    _require(
        isinstance(residuals, list),
        f"{runtime_id} finite interpolation source residuals are missing",
    )
    actual: set[tuple[str, str]] = set()
    for index, residual in enumerate(residuals):
        _require(
            isinstance(residual, dict)
            and isinstance(residual.get("eventPath"), str)
            and isinstance(residual.get("sourceGuid"), str)
            and residual.get("allPass") is True
            and isinstance(residual.get("probeCount"), int)
            and residual["probeCount"] > 0
            and all(
                isinstance(residual.get(key), (int, float))
                for key in (
                    "maximumEnvelopeNormalizedRmsError",
                    "maximumBandEnergyErrorDb",
                    "maximumAbsoluteGainErrorDb",
                )
            ),
            f"{runtime_id} finite interpolation residual {index} is incomplete",
        )
        identity = (residual["eventPath"], residual["sourceGuid"])
        _require(
            identity in expected and identity not in actual,
            f"{runtime_id} finite interpolation residual identity differs from the plan",
        )
        actual.add(identity)
    _require(
        actual == expected,
        f"{runtime_id} finite interpolation residual coverage differs from the plan",
    )


def _validate_oracle(
    oracle: Mapping[str, Any],
    plan: Mapping[str, Any],
    runtime_id: str,
    bank_sha: str,
    plan_sha: str,
) -> None:
    _require(oracle.get("schema") == ORACLE_SCHEMA, f"{runtime_id} oracle uses an old or unknown schema")
    _require(oracle.get("atlasFamilyId") == runtime_id, f"{runtime_id} oracle family differs")
    _require(oracle.get("finalPlanSha256") == plan_sha, f"{runtime_id} oracle final plan differs")
    _require(oracle.get("sourceBankSha256") == bank_sha, f"{runtime_id} oracle bank hash differs")
    _require(oracle.get("status") == "PASS" and oracle.get("allProbesPass") is True, f"{runtime_id} oracle did not pass")
    probe_count = oracle.get("probeCount")
    probes = oracle.get("probes")
    _require(isinstance(probe_count, int) and probe_count > 0, f"{runtime_id} oracle has no probes")
    _require(isinstance(probes, list) and len(probes) == probe_count, f"{runtime_id} oracle probe count differs")
    for index, probe in enumerate(probes):
        _require(isinstance(probe, dict) and probe.get("pass") is True, f"{runtime_id} oracle probe {index} failed")
        _require(SHA256_PATTERN.fullmatch(str(probe.get("oracleWavSha256") or "")) is not None, f"{runtime_id} oracle probe {index} hash is invalid")
        _require(SHA256_PATTERN.fullmatch(str(probe.get("reconstructionSha256") or "")) is not None, f"{runtime_id} reconstruction probe {index} hash is invalid")
    _validate_finite_effect_interpolation_oracle(oracle, plan, runtime_id)
    _validate_combined_engine_effect_mix_oracle(oracle, plan, runtime_id)


def _compact_group_resource_scalars(
    plan_event: Mapping[str, Any], group_id: str, runtime_id: str
) -> dict[str, int]:
    """Resolve the lossless v5 finite-ring scalars from plan evidence.

    Runtime v5 intentionally drops the repeated per-perspective resource trees,
    but cannot replace them with an unproved smaller reservation.  The assembler
    has both documents, so it verifies the compact scalar is the maximum exact
    value over every selected perspective before producing an installable pack.
    """

    resources = plan_event.get("perspectiveResources")
    _require(
        isinstance(resources, dict),
        f"{runtime_id} plan event {plan_event.get('eventPath')} has no resource proof",
    )
    candidates: list[tuple[int, int, int, int]] = []
    for perspective, resource in resources.items():
        _require(
            isinstance(resource, dict),
            f"{runtime_id} plan event has malformed {perspective} resource proof",
        )
        finite = resource.get("finite")
        groups = finite.get("groups") if isinstance(finite, dict) else None
        _require(
            isinstance(groups, list),
            f"{runtime_id} plan event has no finite group proof",
        )
        for record in groups:
            if not isinstance(record, dict) or record.get("groupId") != group_id:
                continue
            corners = record.get("maximumSourceCornerContributorsPerLogicalRing")
            channels = record.get("maximumFmodSourceChannelsPerLogicalRing")
            maximum_capture_frames = record.get("maximumCaptureFramesPerLogicalRing")
            streaming_ring_frames = record.get("streamingRingBufferFrames")
            _require(
                isinstance(corners, int)
                and not isinstance(corners, bool)
                and corners >= 0
                and isinstance(channels, int)
                and not isinstance(channels, bool)
                and channels >= 0
                and isinstance(maximum_capture_frames, int)
                and not isinstance(maximum_capture_frames, bool)
                and maximum_capture_frames >= 0
                and isinstance(streaming_ring_frames, int)
                and not isinstance(streaming_ring_frames, bool)
                and streaming_ring_frames >= 1,
                f"{runtime_id} plan group {group_id} has invalid finite resource scalars",
            )
            candidates.append(
                (corners, channels, maximum_capture_frames, streaming_ring_frames)
            )
    if not candidates:
        # Continuous groups are accounted by the plan's continuous resource
        # branch rather than ``finite.groups``.  Derive the same current-cell
        # corner scalar the packer writes, from the authoritative source axes.
        contract = plan_event.get("runtimeLifecycleParameterVariantContract")
        variants = contract.get("variants") if isinstance(contract, dict) else None
        _require(
            isinstance(variants, list),
            f"{runtime_id} compact group {group_id} has no source variants",
        )
        matched = [
            variant
            for variant in variants
            if isinstance(variant, dict)
            and isinstance(variant.get("schedulingGroup"), dict)
            and variant["schedulingGroup"].get("groupId") == group_id
        ]
        _require(
            matched,
            f"{runtime_id} compact group {group_id} has no plan resource proof",
        )
        composition = matched[0]["schedulingGroup"].get("composition")
        _require(
            composition in {"playlistAlternative", "simultaneousLayer"},
            f"{runtime_id} compact group {group_id} has unsupported composition",
        )
        corners: list[int] = []
        for variant in matched:
            mapping = variant.get("runtimeMapping")
            axes = mapping.get("parameterAxes") if isinstance(mapping, dict) else None
            _require(
                isinstance(axes, dict),
                f"{runtime_id} compact group {group_id} has no parameter axes",
            )
            corners.append(
                2
                ** sum(
                    1
                    for values in axes.values()
                    if isinstance(values, list) and len(values) > 1
                )
            )
        candidates.append(
            (
                max(corners) if composition == "playlistAlternative" else sum(corners),
                1 if composition == "playlistAlternative" else len(matched),
                0,
                0,
            )
        )
    return {
        "maximumSourceCornerContributorsPerLogicalRing": max(item[0] for item in candidates),
        "maximumFmodSourceChannelsPerLogicalRing": max(item[1] for item in candidates),
        "maximumCaptureFramesPerLogicalRing": max(item[2] for item in candidates),
        "streamingRingBufferFrames": max(item[3] for item in candidates),
    }


def _validate_compact_effect_runtime(
    effects: Mapping[str, Any], runtime_id: str, plan_effects: Sequence[Mapping[str, Any]]
) -> None:
    """Validate the compact APK runtime tables in both reference directions."""

    contract = effects.get("runtimeContract")
    _require(
        isinstance(contract, dict)
        and contract.get("schema") == COMPACT_EFFECT_RUNTIME_SCHEMA
        and contract.get("variantBindingIdentity")
        == "familyLocalVnRefPlusExactAuthoredBindingKeyAndSourceGuid"
        and contract.get("schedulingGroupIdentity")
        == "familyLocalGnRefPlusExactAuthoredGroupId"
        and contract.get("runtimeMappingProfileIdentity")
        == "familyLocalMnRefPlusCanonicalExecutableMapping"
        and contract.get("nodeBinding")
        == "nodes[][0] is variantBindingRef resolving to authoredBindingKey"
        and contract.get("nodeEncoding")
        == {
            "schema": COMPACT_EFFECT_NODE_ENCODING_SCHEMA,
            "fields": list(COMPACT_EFFECT_NODE_FIELDS),
            "sourceIdentity": "nodes[][0] resolves to variantBindings[].authoredBindingKeyAndSourceGuid",
            "finiteDurationFrames": "nodes[][4]-nodes[][3]",
        },
        f"{runtime_id} effect runtime does not use the compact v5 contract",
    )
    execution = contract.get("execution")
    _require(
        isinstance(execution, dict)
        and execution.get("schema") == COMPACT_EFFECT_EXECUTION_SCHEMA
        and isinstance(execution.get("continuous"), dict)
        and isinstance(execution.get("oneShot"), dict)
        and execution["continuous"].get("algorithm")
        == "perSourceAxisAlignedMultilinear-v1"
        and execution["continuous"].get("nodeIdentity")
        == "requiredAuthoredBindingKeyPlusCanonicalParameters"
        and execution["oneShot"].get("algorithm")
        == "perSourceAxisAlignedMultilinearFiniteRing-v2"
        and execution["oneShot"].get("cornerGainFormula")
        == "rawNDimensionalMultilinearWeight"
        and execution["oneShot"].get("logicalVoice", {}).get("pcm16Premix")
        == "forbidden"
        and execution["oneShot"].get("nodeIdentity")
        == "requiredAuthoredBindingKeyPlusCanonicalParameters"
        and execution["oneShot"].get("logicalVoice", {}).get("sourceCornerRegions")
        == "audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; noAudioCallbackMmapAllocationLockOrPcm16PremixStorage"
        and contract.get("selectionRuntimeContractTable")
        == "selectionRuntimeContracts[].id",
        f"{runtime_id} compact effect execution contract is incomplete",
    )
    events = effects.get("events")
    bindings = effects.get("variantBindings")
    groups = effects.get("schedulingGroups")
    profiles = effects.get("runtimeMappingProfiles")
    selection_contracts = effects.get("selectionRuntimeContracts")
    _require(
        isinstance(events, list)
        and isinstance(bindings, list)
        and isinstance(groups, list)
        and isinstance(profiles, list)
        and isinstance(selection_contracts, list),
        f"{runtime_id} compact effect tables are missing",
    )
    selection_by_id: dict[str, Mapping[str, Any]] = {}
    for selection in selection_contracts:
        _require(
            isinstance(selection, dict)
            and isinstance(selection.get("id"), str)
            and selection["id"].startswith("s")
            and selection["id"] not in selection_by_id
            and isinstance(selection.get("contract"), dict)
            and selection["contract"].get("schema") == COMPACT_EFFECT_SELECTION_SCHEMA,
            f"{runtime_id} compact selection runtime contract is invalid",
        )
        selection_by_id[selection["id"]] = selection["contract"]
    profiles_by_id: dict[str, Mapping[str, Any]] = {}
    for profile in profiles:
        _require(isinstance(profile, dict), f"{runtime_id} compact mapping profile is not an object")
        identifier = profile.get("id")
        mapping = profile.get("runtimeMapping")
        _require(
            isinstance(identifier, str)
            and identifier.startswith("m")
            and identifier not in profiles_by_id
            and isinstance(mapping, dict)
            and mapping.get("hostGainClass") in {"engineEvent", "effectEvent"}
            and "schedulingGroupRef" not in mapping,
            f"{runtime_id} compact mapping profile is invalid",
        )
        placement_entry = mapping.get("parameterPlacementEntry")
        lifecycles = mapping.get("semanticLifecycle")
        if placement_entry is not None:
            matching_entries = [
                lifecycle.get("parameterPlacementEntry")
                for lifecycle in lifecycles
                if isinstance(lifecycle, dict)
                and lifecycle.get("trigger") == "PARAMETER_PLACEMENT_ENTRY"
            ] if isinstance(lifecycles, list) else []
            _require(
                len(matching_entries) == 1
                and canonical_json_bytes(matching_entries[0])
                == canonical_json_bytes(placement_entry),
                f"{runtime_id} placement-entry mapping/lifecycle contract differs",
            )
        profiles_by_id[identifier] = profile
    bindings_by_id: dict[str, Mapping[str, Any]] = {}
    for binding in bindings:
        _require(isinstance(binding, dict), f"{runtime_id} compact binding is not an object")
        identifier = binding.get("id")
        source_guid = binding.get("sourceGuid")
        authored_binding_key = binding.get("authoredBindingKey")
        group_ref = binding.get("schedulingGroupRef")
        mapping_ref = binding.get("runtimeMappingRef")
        _require(
            isinstance(identifier, str)
            and identifier.startswith("v")
            and identifier not in bindings_by_id
            and isinstance(source_guid, str)
            and bool(source_guid)
            and isinstance(authored_binding_key, str)
            and re.fullmatch(r"binding:[0-9a-f]{64}", authored_binding_key) is not None
            and isinstance(group_ref, str)
            and isinstance(mapping_ref, str)
            and mapping_ref in profiles_by_id,
            f"{runtime_id} compact binding identity or mapping ref is invalid",
        )
        bindings_by_id[identifier] = binding
    groups_by_id: dict[str, Mapping[str, Any]] = {}
    group_ref_by_authored_id: dict[str, str] = {}
    for group in groups:
        _require(isinstance(group, dict), f"{runtime_id} compact scheduling group is not an object")
        identifier = group.get("id")
        group_id = group.get("groupId")
        _require(
            isinstance(identifier, str)
            and identifier.startswith("g")
            and identifier not in groups_by_id
            and isinstance(group_id, str)
            and SCHEDULING_GROUP_ID_PATTERN.fullmatch(group_id) is not None
            and group_id not in group_ref_by_authored_id
            and group.get("complete") is True
            and isinstance(group.get("selectionRuntimeContractRef"), str)
            and group["selectionRuntimeContractRef"] in selection_by_id
            and isinstance(group.get("maximumSourceCornerContributorsPerLogicalRing"), int)
            and not isinstance(group["maximumSourceCornerContributorsPerLogicalRing"], bool)
            and group["maximumSourceCornerContributorsPerLogicalRing"] >= 0
            and isinstance(group.get("maximumFmodSourceChannelsPerLogicalRing"), int)
            and not isinstance(group["maximumFmodSourceChannelsPerLogicalRing"], bool)
            and group["maximumFmodSourceChannelsPerLogicalRing"] >= 0
            and isinstance(group.get("maximumCaptureFramesPerLogicalRing"), int)
            and not isinstance(group["maximumCaptureFramesPerLogicalRing"], bool)
            and group["maximumCaptureFramesPerLogicalRing"] >= 0
            and isinstance(group.get("streamingRingBufferFrames"), int)
            and not isinstance(group["streamingRingBufferFrames"], bool)
            and group["streamingRingBufferFrames"] >= 0,
            f"{runtime_id} compact scheduling group identity is invalid",
        )
        groups_by_id[identifier] = group
        group_ref_by_authored_id[group_id] = identifier
    _require(
        bindings_by_id and groups_by_id and profiles_by_id,
        f"{runtime_id} compact runtime has no bindings/groups/profiles",
    )
    _require(
        all(str(binding.get("schedulingGroupRef")) in groups_by_id for binding in bindings_by_id.values()),
        f"{runtime_id} compact binding has an orphan scheduling group",
    )
    referenced_bindings: set[str] = set()
    referenced_groups: set[str] = set()
    referenced_profiles: set[str] = set()
    plan_by_path: dict[str, Mapping[str, Any]] = {}
    for plan_event in plan_effects:
        _require(
            isinstance(plan_event, Mapping)
            and isinstance(plan_event.get("eventPath"), str)
            and plan_event["eventPath"] not in plan_by_path,
            f"{runtime_id} plan has duplicate or malformed effect event",
        )
        plan_by_path[plan_event["eventPath"]] = plan_event
    binding_event_paths: dict[str, str] = {}
    group_event_paths: dict[str, str] = {}
    for event in events:
        _require(isinstance(event, dict), f"{runtime_id} compact event is not an object")
        event_path = event.get("eventPath")
        event_bindings = event.get("variantBindingRefs")
        event_groups = event.get("schedulingGroupRefs")
        nodes = event.get("nodes")
        _require(
            isinstance(event_path, str)
            and isinstance(event_bindings, list)
            and isinstance(event_groups, list)
            and isinstance(nodes, list)
            and len(event_bindings) == len(set(event_bindings))
            and len(event_groups) == len(set(event_groups))
            and all(ref in bindings_by_id for ref in event_bindings)
            and all(ref in groups_by_id for ref in event_groups),
            f"{runtime_id} compact event has invalid binding/group refs",
        )
        expected_groups = {
            str(bindings_by_id[ref]["schedulingGroupRef"])
            for ref in event_bindings
        }
        _require(expected_groups == set(event_groups), f"{runtime_id} compact event group refs differ from its bindings")
        plan_event = plan_by_path.get(event_path)
        _require(plan_event is not None, f"{runtime_id} compact event has no source plan event")
        original_contract = plan_event.get("runtimeLifecycleParameterVariantContract")
        original_variants = (
            original_contract.get("variants") if isinstance(original_contract, Mapping) else None
        )
        _require(
            isinstance(original_variants, list),
            f"{runtime_id} plan event {event_path} has no variant evidence",
        )
        original_by_binding: dict[str, Mapping[str, Any]] = {}
        for variant in original_variants:
            authored_binding_key = variant.get("authoredBindingKey") if isinstance(variant, Mapping) else None
            _require(
                isinstance(variant, Mapping)
                and isinstance(variant.get("sourceGuid"), str)
                and isinstance(authored_binding_key, str)
                and re.fullmatch(r"binding:[0-9a-f]{64}", authored_binding_key) is not None
                and authored_binding_key not in original_by_binding,
                f"{runtime_id} plan event {event_path} has duplicate or malformed binding evidence",
            )
            original_by_binding[authored_binding_key] = variant
        _require(
            len(event_bindings) == len(original_by_binding),
            f"{runtime_id} compact event binding count loses authored binding identities",
        )
        compact_binding_keys = [
            str(bindings_by_id[reference]["authoredBindingKey"])
            for reference in event_bindings
        ]
        _require(
            len(compact_binding_keys) == len(set(compact_binding_keys))
            and set(compact_binding_keys) == set(original_by_binding),
            f"{runtime_id} compact event duplicates or loses authored binding identities",
        )
        for binding_ref in event_bindings:
            previous_event_path = binding_event_paths.setdefault(binding_ref, event_path)
            _require(
                previous_event_path == event_path,
                f"{runtime_id} compact binding is reused by multiple events",
            )
            binding = bindings_by_id[binding_ref]
            authored_binding_key = str(binding["authoredBindingKey"])
            source_guid = str(binding["sourceGuid"])
            original_variant = original_by_binding.get(authored_binding_key)
            _require(
                original_variant is not None
                and original_variant.get("sourceGuid") == source_guid,
                f"{runtime_id} compact binding identity is absent from the source plan",
            )
            original_scheduler = original_variant.get("schedulingGroup")
            _require(
                isinstance(original_scheduler, Mapping),
                f"{runtime_id} source {source_guid} has no scheduling evidence",
            )
            group_ref = str(binding["schedulingGroupRef"])
            group = groups_by_id[group_ref]
            _require(
                group.get("groupId") == original_scheduler.get("groupId"),
                f"{runtime_id} compact binding scheduling group differs from the source plan",
            )
            original_mapping = dict(copy.deepcopy(original_variant.get("runtimeMapping") or {}))
            original_mapping.pop("schedulingGroup", None)
            original_mapping.pop("captureParameters", None)
            original_mapping.pop("variantSourceGuid", None)
            original_mapping.pop("authoredBindingKey", None)
            compact_mapping = profiles_by_id[str(binding["runtimeMappingRef"])].get("runtimeMapping")
            _require(
                isinstance(compact_mapping, Mapping)
                and canonical_json_bytes(compact_mapping) == canonical_json_bytes(original_mapping),
                f"{runtime_id} compact source mapping differs from the source plan",
            )
            original_group = dict(copy.deepcopy(original_scheduler))
            original_selection = original_group.pop("selectionRuntimeContract", None)
            original_group.update(_compact_group_resource_scalars(plan_event, str(group["groupId"]), runtime_id))
            compact_group = dict(group)
            compact_group.pop("id", None)
            compact_selection_ref = compact_group.pop("selectionRuntimeContractRef", None)
            _require(
                isinstance(original_selection, Mapping)
                and compact_selection_ref in selection_by_id
                and canonical_json_bytes(selection_by_id[compact_selection_ref])
                == canonical_json_bytes(original_selection)
                and canonical_json_bytes(compact_group) == canonical_json_bytes(original_group),
                f"{runtime_id} compact scheduling group differs from source evidence",
            )
        for group_ref in event_groups:
            previous_event_path = group_event_paths.setdefault(group_ref, event_path)
            _require(
                previous_event_path == event_path,
                f"{runtime_id} compact scheduling group is reused by multiple events",
            )
        for node_index, node in enumerate(nodes):
            _require(
                isinstance(node, list) and len(node) == len(COMPACT_EFFECT_NODE_FIELDS),
                f"{runtime_id} compact effect node {node_index} has invalid array geometry",
            )
            reference, parameters, shard_name, start, end, loop_start, loop_end = node
            _require(
                reference in event_bindings
                and isinstance(parameters, dict)
                and isinstance(shard_name, str)
                and isinstance(start, int)
                and not isinstance(start, bool)
                and isinstance(end, int)
                and not isinstance(end, bool)
                and ((loop_start is None) == (loop_end is None))
                and (
                    loop_start is None
                    or (
                        isinstance(loop_start, int)
                        and not isinstance(loop_start, bool)
                        and isinstance(loop_end, int)
                        and not isinstance(loop_end, bool)
                    )
                ),
                f"{runtime_id} compact effect node has a missing/cross-event binding",
            )
        referenced_bindings.update(event_bindings)
        referenced_groups.update(event_groups)
        referenced_profiles.update(
            str(bindings_by_id[reference]["runtimeMappingRef"])
            for reference in event_bindings
        )
    _require(referenced_bindings == set(bindings_by_id), f"{runtime_id} compact runtime has orphan bindings")
    _require(referenced_groups == set(groups_by_id), f"{runtime_id} compact runtime has orphan scheduling groups")
    _require(referenced_profiles == set(profiles_by_id), f"{runtime_id} compact runtime has orphan mapping profiles")


def validate_release_family(
    *,
    atlas_root: Path,
    runtime_id: str,
    bank_sha: str,
    member_car_ids: Sequence[str],
) -> tuple[dict[str, Any], Path | None, dict[str, Any]]:
    family_directory = atlas_root / "families" / runtime_id
    runtime_path = family_directory / "runtime-index.json"
    plan_path = family_directory / "plan.json"
    realization_path = family_directory / "realization-report.json"
    pack_report_path = family_directory / "pack-report.json"
    oracle_path = family_directory / "oracle-status.json"
    runtime = _read_json(runtime_path)
    plan = _read_json(plan_path)
    realization = _read_json(realization_path)
    pack_report = _read_json(pack_report_path)
    oracle = _read_json(oracle_path)
    _require(PACK_ID_PATTERN.fullmatch(runtime_id) is not None, f"runtime family id {runtime_id!r} is unsafe")
    _require(runtime.get("schema") == RUNTIME_SCHEMA and runtime.get("id") == runtime_id, f"{runtime_id} runtime index identity differs")
    _require(runtime.get("draftBlocked") is False, f"{runtime_id} runtime remains draft-blocked")
    _require(runtime.get("hostMixContract") == HOST_MIX_CONTRACT, f"{runtime_id} runtime host/master mix contract differs")
    _require(plan.get("schema") == PLAN_SCHEMA and plan.get("id") == runtime_id, f"{runtime_id} final plan identity differs")
    _require(plan.get("bankSha256") == bank_sha, f"{runtime_id} plan bank hash differs")
    _require(plan.get("hostMixContract") == HOST_MIX_CONTRACT, f"{runtime_id} plan host/master mix contract differs")
    source_evidence = plan.get("sourceConservationEvidence")
    _require(isinstance(source_evidence, dict) and source_evidence.get("exactGuidSetEquality") is True, f"{runtime_id} plan lacks conservation equality")
    release_gate = plan.get("releaseGate")
    _require(isinstance(release_gate, dict) and release_gate.get("status") == "PASS", f"{runtime_id} plan release gate did not pass")
    plan_sha = str(plan.get("planSha256") or "")
    _require(SHA256_PATTERN.fullmatch(plan_sha) is not None, f"{runtime_id} final plan hash is invalid")
    _require(runtime.get("planSha256") == plan_sha, f"{runtime_id} runtime references another plan")
    _validate_oracle(oracle, plan, runtime_id, bank_sha, plan_sha)
    oracle_sha = sha256_file(oracle_path)
    _require(release_gate.get("oracleReportSha256") == oracle_sha, f"{runtime_id} plan oracle hash differs")
    _require(runtime.get("oracleReportSha256") == oracle_sha, f"{runtime_id} runtime oracle hash differs")
    _require(release_gate.get("convergedIterations") == oracle.get("convergedIterations"), f"{runtime_id} convergence count differs")

    runtime_report = pack_report.get("runtimeIndex")
    runtime_payload = runtime_path.read_bytes()
    _require(
        runtime_payload == canonical_json_bytes(runtime) + b"\n",
        f"{runtime_id} runtime index is not canonical newline-terminated JSON",
    )
    _require(
        isinstance(runtime_report, dict)
        and runtime_report.get("schema") == COMPACT_EFFECT_RUNTIME_SCHEMA
        and runtime_report.get("bytes") == len(runtime_payload)
        and runtime_report.get("canonicalJsonNewlineSha256")
        == hashlib.sha256(runtime_payload).hexdigest(),
        f"{runtime_id} pack report does not attest the exact final runtime index",
    )

    _require(realization.get("schema") == REALIZATION_SCHEMA, f"{runtime_id} realization schema differs")
    _require_exact_keys(
        realization,
        {
            "schema",
            "planSha256",
            "atlasFamilyId",
            "sourceBankSha256Before",
            "sourceBankSha256After",
            "sourceBankUnchanged",
            "fullRun",
            "captureCount",
            "captures",
        },
        f"{runtime_id} realization report",
    )
    _require(realization.get("planSha256") == plan_sha and realization.get("atlasFamilyId") == runtime_id, f"{runtime_id} realization references another plan")
    _require(realization.get("sourceBankSha256Before") == bank_sha, f"{runtime_id} pre-render bank hash differs")
    _require(realization.get("sourceBankSha256After") == bank_sha, f"{runtime_id} post-render bank hash differs")
    _require(realization.get("sourceBankUnchanged") is True and realization.get("fullRun") is True, f"{runtime_id} realization was partial or changed its bank")
    _require(pack_report.get("schema") == ATLAS_PACK_REPORT_SCHEMA, f"{runtime_id} pack report schema differs")
    _require(pack_report.get("planSha256") == plan_sha and pack_report.get("atlasFamilyId") == runtime_id, f"{runtime_id} pack report references another plan")
    captures = realization.get("captures")
    _require(isinstance(captures, list), f"{runtime_id} realization captures are missing")
    capture_hashes: dict[str, str] = {}
    captures_by_name: dict[str, Mapping[str, Any]] = {}

    def record_capture_asset(name: Any, wav_sha: Any, label: str) -> None:
        _require(
            isinstance(name, str)
            and bool(name)
            and name not in capture_hashes
            and SHA256_PATTERN.fullmatch(str(wav_sha or "")) is not None,
            f"{runtime_id} {label} has an invalid or duplicate identity",
        )
        capture_hashes[name] = str(wav_sha)

    for index, capture in enumerate(captures):
        _require(isinstance(capture, dict), f"{runtime_id} realization capture {index} is invalid")
        name = capture.get("temporaryAssetName")
        _require(
            isinstance(name, str)
            and name
            and name not in captures_by_name,
            f"{runtime_id} realization capture {index} has invalid or duplicate identity",
        )
        captures_by_name[name] = capture
        record_capture_asset(name, capture.get("wavSha256"), f"realization capture {index}")
    plan_perspectives = plan.get("perspectives")
    _require(isinstance(plan_perspectives, dict), f"{runtime_id} plan perspectives are missing")
    expected_capture_names: set[str] = set()
    expected_asset_names: set[str] = set()
    for perspective in ("cabin", "exterior"):
        value = plan_perspectives.get(perspective)
        _require(isinstance(value, dict), f"{runtime_id} plan {perspective} perspective is missing")
        nodes = value.get("nodes")
        _require(isinstance(nodes, list), f"{runtime_id} plan {perspective} nodes are missing")
        for node_index, node in enumerate(nodes):
            name = node.get("temporaryAssetName") if isinstance(node, dict) else None
            mode_program_names = node.get("modeProgramTemporaryAssetNames") if isinstance(node, dict) else None
            _require(
                isinstance(name, str) and name and name not in expected_capture_names,
                f"{runtime_id} plan has an invalid or duplicate engine capture identity",
            )
            _require(
                isinstance(mode_program_names, dict)
                and set(mode_program_names) == {"loadOnly", "coastOnly"},
                f"{runtime_id} plan {perspective} engine node {node_index} lacks independent mode-program identities",
            )
            expected_capture_names.add(name)
            expected_asset_names.add(name)
            capture = captures_by_name.get(name)
            mode_programs = capture.get("modePrograms") if isinstance(capture, Mapping) else None
            _require(
                isinstance(mode_programs, dict)
                and set(mode_programs) == {"loadOnly", "coastOnly"},
                f"{runtime_id} realization engine capture {name} lacks independent mode programs",
            )
            for mode in ("loadOnly", "coastOnly"):
                program_capture = mode_programs[mode]
                expected_name = mode_program_names[mode]
                _require(
                    isinstance(program_capture, dict)
                    and program_capture.get("temporaryAssetName") == expected_name,
                    f"{runtime_id} realization engine capture {name} has a mismatched {mode} program",
                )
                record_capture_asset(
                    program_capture.get("temporaryAssetName"),
                    program_capture.get("wavSha256"),
                    f"realization engine capture {name} {mode} program",
                )
                expected_asset_names.add(str(expected_name))
    for event in plan.get("effects", []):
        nodes = event.get("nodes") if isinstance(event, dict) else None
        _require(isinstance(nodes, list), f"{runtime_id} plan effect nodes are missing")
        for node in nodes:
            name = node.get("temporaryAssetName") if isinstance(node, dict) else None
            _require(
                isinstance(name, str) and name and name not in expected_capture_names,
                f"{runtime_id} plan has an invalid or duplicate effect capture identity",
            )
            expected_capture_names.add(name)
            expected_asset_names.add(name)
            _require(
                "modePrograms" not in captures_by_name.get(name, {}),
                f"{runtime_id} realization effect capture {name} unexpectedly has engine mode programs",
            )
    _require(
        set(captures_by_name) == expected_capture_names,
        f"{runtime_id} realization capture set differs from the final plan",
    )
    _require(
        set(capture_hashes) == expected_asset_names,
        f"{runtime_id} realization PCM asset set differs from the final plan",
    )
    _require(
        realization.get("captureCount") == len(captures) == pack_report.get("nodeCount")
        and pack_report.get("assetCount") == len(capture_hashes)
        and pack_report.get("sourceAssetHashes") == capture_hashes,
        f"{runtime_id} realization/pack node or PCM asset counts/hashes differ",
    )
    mode_rows = runtime.get("modeRows")
    _require(
        mode_rows == {
            "LOAD": {"throttle": 1.0, "livePedalIgnored": True},
            "COAST": {"throttle": 0.0, "livePedalIgnored": True},
            "BOTH": {"throttle": "livePedal"},
        },
        f"{runtime_id} LOAD/COAST/BOTH contract differs",
    )
    perspectives = runtime.get("perspectives")
    _require(isinstance(perspectives, dict) and set(perspectives) == {"cabin", "exterior"}, f"{runtime_id} does not contain cabin and exterior")
    effects = runtime.get("effects")
    _require(isinstance(effects, dict) and isinstance(effects.get("events"), list), f"{runtime_id} effects are missing")
    _require(all(event.get("runtimeMappingBlocked") is False for event in effects["events"]), f"{runtime_id} has a blocked effect mapping")
    plan_effects = plan.get("effects")
    _require(isinstance(plan_effects, list), f"{runtime_id} plan effects are missing")
    _validate_compact_effect_runtime(effects, runtime_id, plan_effects)

    declared = runtime.get("shards")
    _require(isinstance(declared, list) and declared, f"{runtime_id} declares no shards")
    declared_by_name: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(declared):
        _require(isinstance(item, dict), f"{runtime_id} shard declaration {index} is not an object")
        _require_exact_keys(
            item,
            {"name", "sha256", "bytes"},
            f"{runtime_id} shard declaration {index}",
        )
        name = str(item.get("name") or "")
        _require(
            SHARD_NAME_PATTERN.fullmatch(name) is not None and name not in declared_by_name,
            f"{runtime_id} has an invalid or duplicate shard name",
        )
        declared_by_name[name] = item
    compact_effect_nodes = _compact_effect_node_records(effects)
    runtime_node_count = sum(
        len(value.get("nodes", []))
        for value in perspectives.values()
        if isinstance(value, dict)
    ) + len(compact_effect_nodes)
    _require(
        runtime_node_count == pack_report.get("nodeCount"),
        f"{runtime_id} runtime/pack node counts differ",
    )
    references = _collect_shard_references({"perspectives": perspectives}) + [
        str(node[2]) for node in compact_effect_nodes
    ]
    _require(set(references) == set(declared_by_name), f"{runtime_id} runtime references and declared shards differ")
    _require(all(references.count(name) >= 1 for name in declared_by_name), f"{runtime_id} contains an unused shard")
    report_shards = pack_report.get("shards")
    _require(isinstance(report_shards, list), f"{runtime_id} pack report has no shards")
    report_by_name = {item.get("shardName"): item for item in report_shards}
    _require(len(report_by_name) == len(report_shards) and set(report_by_name) == set(declared_by_name), f"{runtime_id} pack report shard set differs")
    assets = family_directory / "assets"
    sealed = None
    sealed_descriptor_path = family_directory / SEALED_FAMILY_DESCRIPTOR_FILE_NAME
    if sealed_descriptor_path.is_file():
        sealed = validate_sealed_family_archive(
            atlas_root=atlas_root,
            family_directory=family_directory,
            runtime_id=runtime_id,
            plan_sha256=plan_sha,
            bank_sha256=bank_sha,
            runtime=runtime,
            atlas_pack_report=pack_report,
        )
    wav_metadata: dict[str, WavMetadata] = {}
    assets_for_pack: Path | None = None
    if assets.is_dir():
        actual_names = {path.name for path in assets.iterdir() if path.is_file()}
        _require(actual_names == set(declared_by_name), f"{runtime_id} asset directory does not contain exactly the declared shards")
        for name, declaration in sorted(declared_by_name.items()):
            metadata = read_canonical_atlas_wav(assets / name)
            _require(
                metadata.size_bytes <= MAXIMUM_ATLAS_SHARD_BYTES,
                f"{runtime_id}/{name} exceeds the 384 MiB importer member limit",
            )
            report_item = report_by_name[name]
            _require(declaration.get("sha256") == metadata.sha256, f"{runtime_id}/{name} runtime hash differs")
            _require(declaration.get("bytes") == metadata.size_bytes, f"{runtime_id}/{name} runtime size differs")
            _require(report_item.get("sha256") == metadata.sha256, f"{runtime_id}/{name} pack-report hash differs")
            _require(report_item.get("bytes") == metadata.size_bytes, f"{runtime_id}/{name} pack-report size differs")
            wav_metadata[name] = metadata
        assets_for_pack = assets
        if sealed is not None:
            _require(
                sealed["wavMetadata"] == wav_metadata,
                f"{runtime_id} sealed/archive WAV metadata differs from unpacked assets",
            )
    else:
        _require(
            not assets.exists(),
            f"{runtime_id} asset path is not a directory",
        )
        _require(
            sealed is not None,
            f"{runtime_id} has neither unpacked shards nor a verified sealed archive",
        )
        wav_metadata = sealed["wavMetadata"]

    def validate_node_ranges(nodes: Any, label: str, *, engine: bool = False) -> None:
        _require(isinstance(nodes, list), f"{runtime_id} {label} nodes are missing")
        for index, node in enumerate(nodes):
            _require(isinstance(node, dict), f"{runtime_id} {label} node {index} is invalid")
            if engine:
                _require_exact_keys(
                    node,
                    {
                        "rpm",
                        "throttle",
                        "shardName",
                        "startFrame",
                        "endFrameExclusive",
                        "loopStartFrame",
                        "loopEndFrameExclusive",
                        "phaseOffsetFrames",
                        "modePrograms",
                    },
                    f"{runtime_id} {label} node {index}",
                )
            shard_name = node.get("shardName")
            _require(shard_name in wav_metadata, f"{runtime_id} {label} node {index} references an unknown shard")
            raw_start = node.get("startFrame")
            raw_end = node.get("endFrameExclusive")
            _require(
                isinstance(raw_start, int)
                and not isinstance(raw_start, bool)
                and isinstance(raw_end, int)
                and not isinstance(raw_end, bool),
                f"{runtime_id} {label} node {index} frame range is not integer JSON",
            )
            start = raw_start
            end = raw_end
            frames = wav_metadata[str(shard_name)].frame_count
            _require(0 <= start < end <= frames, f"{runtime_id} {label} node {index} lies outside its WAV")
            loop_start = node.get("loopStartFrame")
            loop_end = node.get("loopEndFrameExclusive")
            _require(
                (loop_start is None) == (loop_end is None),
                f"{runtime_id} {label} node {index} has a partial loop range",
            )
            if loop_start is not None:
                _require(
                    isinstance(loop_start, int)
                    and not isinstance(loop_start, bool)
                    and isinstance(loop_end, int)
                    and not isinstance(loop_end, bool),
                    f"{runtime_id} {label} node {index} loop range is not integer JSON",
                )
                normalized_loop_start = loop_start
                normalized_loop_end = loop_end
                _require(
                    start <= normalized_loop_start < normalized_loop_end <= end,
                    f"{runtime_id} {label} node {index} loop lies outside its node",
                )
            if not engine:
                continue
            mode_programs = node.get("modePrograms")
            _require(
                isinstance(mode_programs, dict)
                and set(mode_programs) == {"loadOnly", "coastOnly"},
                f"{runtime_id} {label} node {index} lacks exact LOAD_ONLY/COAST_ONLY geometry",
            )
            for mode in ("loadOnly", "coastOnly"):
                geometry = mode_programs[mode]
                _require(
                    isinstance(geometry, dict),
                    f"{runtime_id} {label} node {index} {mode} geometry is invalid",
                )
                _require_exact_keys(
                    geometry,
                    {
                        "shardName",
                        "startFrame",
                        "endFrameExclusive",
                        "loopStartFrame",
                        "loopEndFrameExclusive",
                    },
                    f"{runtime_id} {label} node {index} {mode} geometry",
                )
                stem_start = geometry.get("startFrame")
                stem_end = geometry.get("endFrameExclusive")
                stem_loop_start = geometry.get("loopStartFrame")
                stem_loop_end = geometry.get("loopEndFrameExclusive")
                _require(
                    geometry.get("shardName") == shard_name
                    and isinstance(stem_start, int)
                    and not isinstance(stem_start, bool)
                    and isinstance(stem_end, int)
                    and not isinstance(stem_end, bool)
                    and 0 <= stem_start < stem_end <= frames
                    and stem_end - stem_start == end - start
                    and (stem_loop_start is None) == (stem_loop_end is None)
                    and (stem_loop_start is None) == (loop_start is None),
                    f"{runtime_id} {label} node {index} {mode} does not share FULL capture geometry",
                )
                if stem_loop_start is not None:
                    _require(
                        isinstance(stem_loop_start, int)
                        and not isinstance(stem_loop_start, bool)
                        and isinstance(stem_loop_end, int)
                        and not isinstance(stem_loop_end, bool)
                        and stem_start <= stem_loop_start < stem_loop_end <= stem_end
                        and stem_loop_start - stem_start == loop_start - start
                        and stem_loop_end - stem_start == loop_end - start,
                        f"{runtime_id} {label} node {index} {mode} loop geometry differs from FULL",
                    )

    for perspective, perspective_runtime in sorted(perspectives.items()):
        _require(isinstance(perspective_runtime, dict), f"{runtime_id} {perspective} runtime is invalid")
        _require_exact_keys(
            perspective_runtime,
            {"rpmAxis", "throttleAxis", "nodes"},
            f"{runtime_id} {perspective} runtime",
        )
        validate_node_ranges(
            perspective_runtime.get("nodes"),
            f"{perspective} engine",
            engine=True,
        )
    for node_index, node in enumerate(compact_effect_nodes):
        _variant_ref, _parameters, shard_name, start, end, loop_start, loop_end = node
        _require(
            shard_name in wav_metadata,
            f"{runtime_id} compact effect node {node_index} references an unknown shard",
        )
        frames = wav_metadata[str(shard_name)].frame_count
        _require(
            0 <= start < end <= frames,
            f"{runtime_id} compact effect node {node_index} lies outside its WAV",
        )
        if loop_start is not None:
            _require(
                start <= loop_start < loop_end <= end,
                f"{runtime_id} compact effect node {node_index} loop lies outside its node",
            )

    conservation_hashes = _validate_source_conservation(atlas_root, member_car_ids, bank_sha)
    evidence = {
        "runtimeIndexSha256": sha256_file(runtime_path),
        "finalPlanSha256": plan_sha,
        "realizationReportSha256": sha256_file(realization_path),
        "atlasPackReportSha256": sha256_file(pack_report_path),
        "oracleReportSha256": oracle_sha,
        "sourceConservationReportSha256s": sorted(conservation_hashes),
        "shardCount": len(wav_metadata),
        "shardBytes": sum(item.size_bytes for item in wav_metadata.values()),
        "oracleProbeCount": oracle["probeCount"],
    }

    return runtime, assets_for_pack, {
        "evidence": evidence,
        "wavMetadata": wav_metadata,
        "sealedPack": sealed,
    }


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    info.flag_bits = 0

    return info


def build_family_pack(
    *,
    runtime_id: str,
    assets_directory: Path,
    runtime: Mapping[str, Any],
    wav_metadata: Mapping[str, WavMetadata],
    output_path: Path,
    pack_version: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    pack_id = f"byd.atlas.{runtime_id}"
    _require(PACK_ID_PATTERN.fullmatch(pack_id) is not None, f"generated pack id {pack_id!r} is invalid")
    _require(
        isinstance(pack_version, int)
        and not isinstance(pack_version, bool)
        and 1 <= pack_version <= 2_147_483_647,
        "pack version must fit the Android positive Int contract",
    )
    files = [
        {
            "path": f"sample_engine/{runtime_id}/{name}",
            "sizeBytes": metadata.size_bytes,
            "sha256": metadata.sha256,
            "sampleRate": metadata.sample_rate,
            "channels": metadata.channels,
            "frameCount": metadata.frame_count,
        }
        for name, metadata in sorted(wav_metadata.items())
    ]
    _require(len(files) == len(runtime.get("shards", [])), f"{runtime_id} manifest shard count differs")
    manifest = {
        "schemaVersion": PACK_MANIFEST_SCHEMA_VERSION,
        "packId": pack_id,
        "packVersion": pack_version,
        "files": files,
    }
    manifest_bytes = canonical_json_bytes(manifest)
    _require(len(manifest_bytes) <= MAXIMUM_MANIFEST_BYTES, f"{runtime_id} manifest exceeds importer limit")
    _require(len(files) + 1 <= MAXIMUM_MEMBER_COUNT, f"{runtime_id} pack has too many ZIP members")
    _require(all(item["sizeBytes"] <= MAXIMUM_MEMBER_BYTES for item in files), f"{runtime_id} pack has an oversized member")
    payload_bytes = sum(item["sizeBytes"] for item in files)
    _require(payload_bytes <= MAXIMUM_EXTRACTED_BYTES, f"{runtime_id} pack exceeds importer extraction limit")
    _require(all(len(item["path"]) <= 240 for item in files), f"{runtime_id} pack has an overlong asset path")
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{output_path.name}.", suffix=".tmp", dir=output_path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, "w", allowZip64=True) as archive:
            archive.writestr(_zip_info("manifest.json"), manifest_bytes)
            for item in files:
                source = assets_directory / Path(item["path"]).name
                with source.open("rb") as input_stream, archive.open(_zip_info(item["path"]), "w") as output_stream:
                    while block := input_stream.read(1024 * 1024):
                        output_stream.write(block)
        with temporary_path.open("rb") as completed_pack:
            os.fsync(completed_pack.fileno())
        _require(temporary_path.stat().st_size <= MAXIMUM_ARCHIVE_BYTES, f"{runtime_id} pack exceeds importer archive limit")
        pack_sha = sha256_file(temporary_path)
        if output_path.exists() and sha256_file(output_path) == pack_sha:
            temporary_path.unlink()
        else:
            os.replace(temporary_path, output_path)
    finally:
        temporary_path.unlink(missing_ok=True)
    requirement = {
        "packId": pack_id,
        "packVersion": pack_version,
        "manifestSha256": manifest_sha,
    }
    report = {
        "packPath": str(output_path),
        "packSha256": sha256_file(output_path),
        "packBytes": output_path.stat().st_size,
        "manifestSha256": manifest_sha,
        "fileCount": len(files),
        "payloadBytes": payload_bytes,
    }

    return requirement, report


def _runtime_eager_capabilities(runtime: Mapping[str, Any], runtime_id: str) -> dict[str, Any]:
    """Extract the tiny UI/control facts safe to read before lazy runtime load."""

    perspectives = runtime.get("perspectives")
    effects = runtime.get("effects")
    _require(
        isinstance(perspectives, dict)
        and set(perspectives) == {"cabin", "exterior"}
        and isinstance(effects, dict)
        and isinstance(effects.get("events"), list),
        f"{runtime_id} cannot derive eager family capabilities",
    )
    effect_controls: dict[str, dict[str, Any]] = {}
    for perspective in ("cabin", "exterior"):
        scoped = [
            event
            for event in effects["events"]
            if isinstance(event, Mapping)
            and perspective in event.get("perspectives", [])
        ]
        _require(
            len(scoped) == sum(
                1
                for event in effects["events"]
                if isinstance(event, Mapping)
                and isinstance(event.get("perspectives"), list)
                and perspective in event["perspectives"]
            ),
            f"{runtime_id} effect perspective scope is malformed",
        )
        triggers: set[str] = set()
        for event in scoped:
            values = event.get("runtimeTriggers")
            _require(
                isinstance(values, list) and all(isinstance(value, str) and value for value in values),
                f"{runtime_id} effect runtime triggers are malformed",
            )
            triggers.update(values)
        effect_controls[perspective] = {
            "hasTurboEvent": any(
                str(event.get("eventSuffix") or "") == "turbo" for event in scoped
            ),
            "runtimeTriggers": sorted(triggers),
        }
    return {
        "perspectives": ["cabin", "exterior"],
        "effectControls": effect_controls,
    }


def assemble_release(
    *,
    source_catalog_path: Path,
    atlas_root: Path,
    pack_output_directory: Path,
    runtime_index_output_directory: Path,
    pack_version: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source = _load_source_catalog(source_catalog_path)
    batch = _read_json(atlas_root / "catalog.json")
    program_by_bank_family = _batch_family_map(batch, source)
    families_by_id: dict[str, dict[str, Any]] = {}
    runtime_assets_by_id: dict[str, tuple[str, bytes, str]] = {}
    validated_families: list[dict[str, Any]] = []
    for family in source["families"]:
        bank_family_id = family["bankFamilyId"]
        runtime_id = program_by_bank_family[bank_family_id]
        runtime, assets, validated = validate_release_family(
            atlas_root=atlas_root,
            runtime_id=runtime_id,
            bank_sha=family["bankSha256"],
            member_car_ids=family["memberCarIds"],
        )
        validated_families.append(
            {
                "source": family,
                "runtimeId": runtime_id,
                "runtime": runtime,
                "assets": assets,
                "validated": validated,
            }
        )
        runtime_payload = canonical_json_bytes(runtime) + b"\n"
        _require(
            len(runtime_payload) <= MAXIMUM_ANDROID_FAMILY_RUNTIME_BYTES,
            f"{runtime_id} standalone runtime index exceeds the 4 MiB APK-family limit",
        )
        runtime_asset_name = f"families/{runtime_id}.json"
        _require(
            runtime_asset_name == f"families/{runtime_id}.json"
            and PACK_ID_PATTERN.fullmatch(runtime_id) is not None,
            f"{runtime_id} runtime asset path is unsafe",
        )
        runtime_assets_by_id[runtime_id] = (
            runtime_asset_name,
            runtime_payload,
            hashlib.sha256(runtime_payload).hexdigest(),
        )

    pack_output_directory.parent.mkdir(parents=True, exist_ok=True)
    expected_pack_names = {
        f"{item['runtimeId']}-v{pack_version}.bydpack"
        for item in validated_families
    }
    if pack_output_directory.is_dir():
        existing_pack_names = {
            path.name for path in pack_output_directory.iterdir()
            if path.is_file() and path.suffix == ".bydpack"
        }
        _require(
            existing_pack_names <= expected_pack_names,
            f"pack output directory contains stale family packs: {sorted(existing_pack_names - expected_pack_names)}",
        )
    expected_runtime_asset_names = {
        f"{runtime_id}.json" for runtime_id in runtime_assets_by_id
    }
    if runtime_index_output_directory.is_dir():
        existing_runtime_asset_names = {
            path.name
            for path in runtime_index_output_directory.iterdir()
            if path.is_file() and path.suffix == ".json"
        }
        _require(
            existing_runtime_asset_names <= expected_runtime_asset_names,
            "runtime-index output directory contains stale family assets: "
            f"{sorted(existing_runtime_asset_names - expected_runtime_asset_names)}",
        )

    pack_reports: list[dict[str, Any]] = []
    runtime_index_output_directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=".byd-release-packs.",
        suffix=".tmp",
        dir=pack_output_directory.parent,
    ) as staging_text:
        staging = Path(staging_text)
        staged: list[tuple[Path, Path]] = []
        staged_runtime_assets: list[tuple[Path, Path]] = []
        runtime_staging = staging / "families"
        runtime_staging.mkdir()
        for item in validated_families:
            family = item["source"]
            bank_family_id = family["bankFamilyId"]
            runtime_id = item["runtimeId"]
            runtime = item["runtime"]
            validated = item["validated"]
            pack_name = f"{runtime_id}-v{pack_version}.bydpack"
            staged_path = staging / pack_name
            final_path = pack_output_directory / pack_name
            sealed = validated.get("sealedPack")
            if isinstance(sealed, Mapping):
                requirement = dict(sealed["packRequirement"])
                _require(
                    requirement.get("packVersion") == pack_version,
                    f"{runtime_id} sealed pack version differs from requested release",
                )
                source_archive = Path(str(sealed["archivePath"]))
                with source_archive.open("rb") as source, staged_path.open("wb") as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
                    destination.flush()
                    os.fsync(destination.fileno())
                pack_report = dict(sealed["packReport"])
                _require(
                    sha256_file(staged_path) == pack_report.get("packSha256")
                    and staged_path.stat().st_size == pack_report.get("packBytes"),
                    f"{runtime_id} staged sealed pack copy differs",
                )
                pack_report["packPath"] = str(final_path)
            else:
                assets_directory = item["assets"]
                _require(
                    isinstance(assets_directory, Path),
                    f"{runtime_id} has no source assets for pack construction",
                )
                requirement, pack_report = build_family_pack(
                    runtime_id=runtime_id,
                    assets_directory=assets_directory,
                    runtime=runtime,
                    wav_metadata=validated["wavMetadata"],
                    output_path=staged_path,
                    pack_version=pack_version,
                )
            runtime_asset_name, runtime_payload, runtime_sha = runtime_assets_by_id[
                runtime_id
            ]
            staged_runtime_path = runtime_staging / f"{runtime_id}.json"
            _write_atomic(staged_runtime_path, runtime_payload)
            staged_runtime_assets.append(
                (staged_runtime_path, runtime_index_output_directory / staged_runtime_path.name)
            )
            families_by_id[runtime_id] = {
                "id": runtime_id,
                "assetDirectory": runtime_id,
                "packRequirement": requirement,
                "runtimeAssetName": runtime_asset_name,
                "runtimeBytes": len(runtime_payload),
                "runtimeSha256": runtime_sha,
                "eagerCapabilities": _runtime_eager_capabilities(runtime, runtime_id),
            }
            pack_reports.append(
                {
                    "bankFamilyId": bank_family_id,
                    "bankSha256": family["bankSha256"],
                    "audioProgramFamilyId": runtime_id,
                    "memberCarIds": family["memberCarIds"],
                    **validated["evidence"],
                    **pack_report,
                    "packPath": str(final_path),
                }
            )
            staged.append((staged_path, final_path))
        pack_output_directory.mkdir(parents=True, exist_ok=True)
        for staged_path, final_path in staged:
            os.replace(staged_path, final_path)
        runtime_index_output_directory.mkdir(parents=True, exist_ok=True)
        for staged_path, final_path in staged_runtime_assets:
            os.replace(staged_path, final_path)
    _require(len(families_by_id) == EXPECTED_BANK_FAMILIES, "release family count differs after assembly")
    source_car_by_id = {car["sourceCarId"]: car for car in source["cars"]}
    cars = []
    for source_car_id, car in sorted(source_car_by_id.items(), key=lambda item: (item[1]["displayName"].casefold(), item[1]["id"])):
        runtime_id = program_by_bank_family[car["bankFamilyId"]]
        cars.append(
            {
                "id": car["id"],
                "displayName": car["displayName"],
                "audioProgramFamilyId": runtime_id,
                "previewAssetName": car["previewAssetName"],
                "physics": car["physics"],
                "specifications": car["specifications"],
            }
        )
    catalog = {
        "schema": RELEASE_CATALOG_SCHEMA,
        "catalogVersion": RELEASE_CATALOG_VERSION,
        "cars": cars,
        "families": [families_by_id[key] for key in sorted(families_by_id)],
    }
    catalog_payload = canonical_json_bytes(catalog) + b"\n"
    catalog_bytes = len(catalog_payload)
    _require(
        catalog_bytes <= MAXIMUM_ANDROID_ROOT_CATALOG_BYTES,
        "final Android root catalog exceeds its 512 KiB loader limit",
    )
    family_runtime_bytes = sum(
        len(payload) for _name, payload, _sha in runtime_assets_by_id.values()
    )
    total_runtime_catalog_bytes = catalog_bytes + family_runtime_bytes
    _require(
        total_runtime_catalog_bytes <= MAXIMUM_ANDROID_RUNTIME_CATALOG_BYTES,
        "root plus standalone Android family runtime assets exceed the 16 MiB hard limit",
    )
    report = {
        "schema": "byd-car-atlas-release-assembly-report-v1",
        "status": "PASS",
        "catalogSha256": hashlib.sha256(catalog_payload).hexdigest(),
        "catalogBytes": catalog_bytes,
        "maximumCatalogBytes": MAXIMUM_ANDROID_ROOT_CATALOG_BYTES,
        "familyRuntimeAssetDirectory": str(runtime_index_output_directory),
        "familyRuntimeAssetCount": len(runtime_assets_by_id),
        "familyRuntimeBytes": family_runtime_bytes,
        "totalRuntimeCatalogBytes": total_runtime_catalog_bytes,
        "runtimeCatalogOperationalTargetBytes": TARGET_ANDROID_RUNTIME_CATALOG_BYTES,
        "runtimeCatalogHardMaximumBytes": MAXIMUM_ANDROID_RUNTIME_CATALOG_BYTES,
        "runtimeCatalogWithinOperationalTarget": (
            total_runtime_catalog_bytes <= TARGET_ANDROID_RUNTIME_CATALOG_BYTES
        ),
        "familyRuntimeAssets": [
            {
                "id": runtime_id,
                "runtimeAssetName": name,
                "runtimeBytes": len(payload),
                "runtimeSha256": runtime_sha,
            }
            for runtime_id, (name, payload, runtime_sha) in sorted(
                runtime_assets_by_id.items()
            )
        ],
        "carCount": len(cars),
        "familyCount": len(families_by_id),
        "packCount": len(pack_reports),
        "packs": sorted(pack_reports, key=lambda item: item["audioProgramFamilyId"]),
    }

    return catalog, report


def _prepare_command(args: argparse.Namespace) -> int:
    try:
        source, report = prepare_catalog_source(
            inventory_path=args.inventory.resolve(strict=True),
            audit_path=args.audit.resolve(strict=True),
            preview_report_path=args.preview_report.resolve(strict=True),
            audio_lab_root=args.audio_lab_root.resolve(strict=True),
            assetto_root=args.assetto_root.resolve(strict=True),
            preview_assets_directory=args.preview_assets_directory.resolve(),
        )
    except (CatalogBuildError, OSError) as error:
        _write_json(
            args.report_output.resolve(),
            {"schema": "byd-car-atlas-catalog-preparation-report-v1", "status": "BLOCKED", "error": str(error)},
        )
        raise
    _write_json(args.source_output.resolve(), source)
    _write_json(args.report_output.resolve(), report)

    return 0


def _assemble_command(args: argparse.Namespace) -> int:
    try:
        catalog, report = assemble_release(
            source_catalog_path=args.source_catalog.resolve(strict=True),
            atlas_root=args.atlas_root.resolve(strict=True),
            pack_output_directory=args.pack_output_directory.resolve(),
            runtime_index_output_directory=args.runtime_index_output_directory.resolve(),
            pack_version=args.pack_version,
        )
    except (CatalogBuildError, OSError) as error:
        _write_json(
            args.report_output.resolve(),
            {"schema": "byd-car-atlas-release-assembly-report-v1", "status": "BLOCKED", "error": str(error)},
        )
        raise
    _write_json(args.catalog_output.resolve(), catalog)
    _write_json(args.report_output.resolve(), report)

    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare", help="compile physics and deterministic preview assets")
    prepare.add_argument("--inventory", type=Path, required=True)
    prepare.add_argument("--audit", type=Path, required=True)
    prepare.add_argument("--preview-report", type=Path, required=True)
    prepare.add_argument("--audio-lab-root", type=Path, required=True)
    prepare.add_argument("--assetto-root", type=Path, required=True)
    prepare.add_argument("--preview-assets-directory", type=Path, required=True)
    prepare.add_argument("--source-output", type=Path, required=True)
    prepare.add_argument("--report-output", type=Path, required=True)
    prepare.set_defaults(handler=_prepare_command)
    assemble = subparsers.add_parser("assemble", help="gate release atlases and build .bydpack files")
    assemble.add_argument("--source-catalog", type=Path, required=True)
    assemble.add_argument("--atlas-root", type=Path, required=True)
    assemble.add_argument("--pack-output-directory", type=Path, required=True)
    assemble.add_argument(
        "--runtime-index-output-directory",
        type=Path,
        required=True,
        help="APK asset directory for lazy standalone family runtime JSON files",
    )
    assemble.add_argument("--pack-version", type=int, default=1)
    assemble.add_argument("--catalog-output", type=Path, required=True)
    assemble.add_argument("--report-output", type=Path, required=True)
    assemble.set_defaults(handler=_assemble_command)
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except (CatalogBuildError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
