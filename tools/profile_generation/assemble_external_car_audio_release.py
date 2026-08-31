#!/usr/bin/env python3
"""Assemble one verified external-car audio release for Android and USB.

This is the final release boundary.  It deliberately does not render or refine
audio.  It consumes a complete, already-certified 36-car/32-family atlas root,
cross-checks its per-car catalog inputs against the prepared Android catalog,
then stages the deterministic family packs and lazy runtime assets.  Nothing is
published when any family is missing, blocked, mismatched, or orphaned.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Any, Callable, Iterator, Mapping, Sequence
import uuid
import zipfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from tools.car_catalog import build_car_catalog_packs as catalog_builder


CATALOG_INPUT_SCHEMA = "byd-car-catalog-input-v1"
USB_INVENTORY_SCHEMA = "byd-external-car-audio-usb-inventory-v1"
RELEASE_REPORT_SCHEMA = "byd-external-car-audio-release-v1"
USB_INVENTORY_FILE_NAME = "byd-audio-library-inventory.json"
USB_CHECKSUM_FILE_NAME = "SHA256SUMS"
ANDROID_ROOT_CATALOG_FILE_NAME = "atlas-catalog-v2.json"


class ExternalAudioReleaseError(catalog_builder.CatalogBuildError):
    """A complete external release cannot be published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ExternalAudioReleaseError(message)


def _read_object(path: Path, label: str) -> dict[str, Any]:
    value = catalog_builder._read_json(path)
    _require(isinstance(value, dict), f"{label} is not a JSON object: {path}")

    return value


def _canonical_json_payload(value: Any) -> bytes:
    return catalog_builder.canonical_json_bytes(value) + b"\n"


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _safe_resolved_reference(raw_path: Any, expected: Path, label: str) -> None:
    _require(isinstance(raw_path, str) and raw_path, f"{label} path is missing")
    referenced = Path(raw_path)
    if not referenced.is_absolute():
        referenced = expected.parents[2] / referenced
    try:
        referenced = referenced.resolve(strict=True)
        expected = expected.resolve(strict=True)
    except OSError as error:
        raise ExternalAudioReleaseError(f"{label} path cannot be resolved: {error}") from error
    _require(referenced == expected, f"{label} points to {referenced}, expected {expected}")


def _status_blockers(value: Any, path: str) -> list[str]:
    """Find release-facing BLOCKED markers without matching descriptive prose."""

    if isinstance(value, Mapping):
        result: list[str] = []
        for key, child in value.items():
            child_path = f"{path}.{key}"
            normalized_key = str(key).casefold()
            if (
                isinstance(child, bool)
                and child
                and (normalized_key == "draftblocked" or normalized_key.endswith("blocked"))
            ):
                result.append(child_path)
            if (
                isinstance(child, str)
                and (
                    normalized_key == "status"
                    or normalized_key.endswith("status")
                )
                and child.casefold().startswith("blocked")
            ):
                result.append(child_path)
            result.extend(_status_blockers(child, child_path))

        return result
    if isinstance(value, list):
        return [
            blocker
            for index, child in enumerate(value)
            for blocker in _status_blockers(child, f"{path}[{index}]")
        ]

    return []


def _directory_names(directory: Path, *, include_hidden: bool = False) -> set[str]:
    _require(directory.is_dir(), f"required directory is missing: {directory}")

    return {
        child.name
        for child in directory.iterdir()
        if child.is_dir() and (include_hidden or not child.name.startswith("."))
    }


def _validate_empty_staging(atlas_root: Path) -> None:
    staging = atlas_root / ".staging"
    if not staging.exists():
        return
    _require(staging.is_dir(), f"atlas staging path is not a directory: {staging}")
    remaining = sorted(str(path.relative_to(staging)) for path in staging.rglob("*"))
    _require(not remaining, f"atlas root still has unfinished staging artifacts: {remaining[:8]}")


def validate_atlas_topology(
    *,
    atlas_root: Path,
    source: Mapping[str, Any],
    batch: Mapping[str, Any],
) -> dict[str, str]:
    """Cross-check every source car, batch row, family, and catalog-input file."""

    program_by_bank_family = catalog_builder._batch_family_map(batch, source)
    source_cars = {str(item["sourceCarId"]): item for item in source["cars"]}
    source_families = {str(item["bankFamilyId"]): item for item in source["families"]}
    batch_rows = batch.get("cars")
    batch_families = batch.get("families")
    family_results = batch.get("familyResults")
    _require(isinstance(batch_rows, list), "atlas catalog has no car rows")
    _require(isinstance(batch_families, list), "atlas catalog has no family rows")
    _require(isinstance(family_results, list), "atlas catalog has no family completion results")
    _require(
        batch.get("deduplicatedCarCount")
        == catalog_builder.EXPECTED_ACTIVE_CARS - catalog_builder.EXPECTED_BANK_FAMILIES,
        "atlas catalog deduplicated-car count differs",
    )

    rows_by_car: dict[str, Mapping[str, Any]] = {}
    for index, row in enumerate(batch_rows):
        _require(isinstance(row, Mapping), f"atlas car row {index} is not an object")
        car_id = row.get("carId")
        _require(
            isinstance(car_id, str) and car_id in source_cars and car_id not in rows_by_car,
            f"atlas car row {index} has an unknown or duplicate car",
        )
        rows_by_car[car_id] = row
    _require(set(rows_by_car) == set(source_cars), "atlas catalog car set differs from source catalog")

    family_rows_by_runtime: dict[str, Mapping[str, Any]] = {}
    for index, family_row in enumerate(batch_families):
        _require(isinstance(family_row, Mapping), f"atlas family row {index} is not an object")
        runtime_id = family_row.get("id")
        _require(
            isinstance(runtime_id, str)
            and catalog_builder.PACK_ID_PATTERN.fullmatch(runtime_id) is not None
            and runtime_id not in family_rows_by_runtime,
            f"atlas family row {index} has an unsafe or duplicate id",
        )
        car_ids = family_row.get("carIds")
        alias_proofs = family_row.get("aliasProofs")
        plan_sha = family_row.get("planSha256")
        _require(
            isinstance(car_ids, list)
            and car_ids == sorted(set(car_ids))
            and car_ids,
            f"atlas family {runtime_id} has invalid car membership",
        )
        _require(
            isinstance(alias_proofs, list) and len(alias_proofs) == len(car_ids),
            f"atlas family {runtime_id} has incomplete alias proofs",
        )
        _require(
            catalog_builder.SHA256_PATTERN.fullmatch(str(plan_sha or "")) is not None,
            f"atlas family {runtime_id} has an invalid initial plan hash",
        )
        family_rows_by_runtime[runtime_id] = family_row

    expected_runtime_ids = set(program_by_bank_family.values())
    _require(
        set(family_rows_by_runtime) == expected_runtime_ids,
        "atlas catalog family ids differ from source-family mappings",
    )
    result_by_runtime: dict[str, Mapping[str, Any]] = {}
    for index, result in enumerate(family_results):
        _require(isinstance(result, Mapping), f"atlas family result {index} is not an object")
        runtime_id = result.get("familyId")
        _require(
            isinstance(runtime_id, str)
            and runtime_id in expected_runtime_ids
            and runtime_id not in result_by_runtime,
            f"atlas family result {index} has an unknown or duplicate family id",
        )
        _require(
            result.get("status") in {"RENDERED_AND_PACKED", "SKIPPED_HASH_VALID"},
            f"atlas family {runtime_id} did not finish rendering and packing",
        )
        result_by_runtime[runtime_id] = result
    _require(
        set(result_by_runtime) == expected_runtime_ids,
        "atlas family completion results are missing or orphaned",
    )
    _require(
        _directory_names(atlas_root / "cars") == set(source_cars),
        "atlas cars directory contains missing or orphan car directories",
    )
    _require(
        _directory_names(atlas_root / "families") == expected_runtime_ids,
        "atlas families directory contains missing or orphan family directories",
    )
    _validate_empty_staging(atlas_root)

    source_family_for_car = {
        str(car_id): str(family["bankFamilyId"])
        for family in source["families"]
        for car_id in family["memberCarIds"]
    }
    for car_id, row in sorted(rows_by_car.items()):
        source_car = source_cars[car_id]
        bank_family_id = source_family_for_car[car_id]
        source_family = source_families[bank_family_id]
        runtime_id = program_by_bank_family[bank_family_id]
        _require(
            row.get("audioProgramFamilyId") == runtime_id,
            f"{car_id} atlas program differs from its bank family",
        )
        input_path = atlas_root / "cars" / car_id / "catalog-input.json"
        _safe_resolved_reference(row.get("catalogInput"), input_path, f"{car_id} catalog input")
        car_input = _read_object(input_path, f"{car_id} catalog input")
        catalog_builder._require_exact_keys(
            car_input,
            {
                "schema",
                "carId",
                "audioProgramFamilyId",
                "missingProfileFields",
                "packRequirement",
                "profile",
            },
            f"{car_id} catalog input",
        )
        _require(car_input.get("schema") == CATALOG_INPUT_SCHEMA, f"{car_id} catalog-input schema differs")
        _require(car_input.get("carId") == car_id, f"{car_id} catalog-input identity differs")
        _require(
            car_input.get("audioProgramFamilyId") == runtime_id,
            f"{car_id} catalog-input atlas id differs",
        )
        _require(
            car_input.get("missingProfileFields") == [],
            f"{car_id} catalog input still has missing profile fields",
        )
        requirement = car_input.get("packRequirement")
        _require(isinstance(requirement, Mapping), f"{car_id} catalog-input pack requirement is missing")
        catalog_builder._require_exact_keys(
            requirement,
            {"id", "assetDirectory", "atlasPlanSha256", "bankSha256"},
            f"{car_id} catalog-input pack requirement",
        )
        initial_plan_sha = family_rows_by_runtime[runtime_id]["planSha256"]
        _require(
            requirement
            == {
                "id": runtime_id,
                "assetDirectory": runtime_id,
                "atlasPlanSha256": initial_plan_sha,
                "bankSha256": source_family["bankSha256"],
            },
            f"{car_id} catalog-input pack mapping differs from its exact source family",
        )
        profile = car_input.get("profile")
        _require(isinstance(profile, Mapping), f"{car_id} catalog-input profile is missing")
        _require(
            profile.get("displayName") == source_car["displayName"],
            f"{car_id} catalog-input display name differs from the source catalog",
        )
        preview = profile.get("preview")
        _require(
            isinstance(preview, Mapping)
            and preview.get("carId") == car_id
            and isinstance(preview.get("bytes"), int)
            and not isinstance(preview.get("bytes"), bool)
            and preview["bytes"] > 0
            and catalog_builder.SHA256_PATTERN.fullmatch(str(preview.get("sha256") or ""))
            is not None,
            f"{car_id} catalog-input preview evidence is incomplete",
        )
        conservation = _read_object(
            atlas_root / "cars" / car_id / "source-conservation-report.json",
            f"{car_id} source conservation report",
        )
        audit = conservation.get("sourceConservationAudit")
        _require(isinstance(audit, Mapping), f"{car_id} source conservation audit is missing")
        core_guids = audit.get("coreReachableSourceGuids")
        emitted_guids = audit.get("emittedNrtSourceGuids")
        _require(
            isinstance(core_guids, list)
            and isinstance(emitted_guids, list)
            and all(isinstance(value, str) and value for value in core_guids)
            and all(isinstance(value, str) and value for value in emitted_guids)
            and len(core_guids) == len(set(core_guids))
            and len(emitted_guids) == len(set(emitted_guids))
            and set(core_guids) == set(emitted_guids)
            and audit.get("exactGuidSetEquality") is True
            and audit.get("unmappedCoreBindings") == [],
            f"{car_id} source conservation GUID set is incomplete, duplicated, or mismatched",
        )
        _require(
            len(core_guids) == source_family["requiredRetainedSourceGuidCount"],
            f"{car_id} retained source count differs from its audited bank family",
        )

    for bank_family_id, source_family in sorted(source_families.items()):
        runtime_id = program_by_bank_family[bank_family_id]
        family_row = family_rows_by_runtime[runtime_id]
        expected_members = source_family["memberCarIds"]
        _require(
            family_row.get("carIds") == expected_members,
            f"{runtime_id} batch/source family membership differs",
        )
        proofs = family_row["aliasProofs"]
        proof_by_car: dict[str, Mapping[str, Any]] = {}
        for proof in proofs:
            _require(isinstance(proof, Mapping), f"{runtime_id} has a malformed alias proof")
            car_id = proof.get("carId")
            _require(
                isinstance(car_id, str) and car_id in expected_members and car_id not in proof_by_car,
                f"{runtime_id} alias proof has an unknown or duplicate car",
            )
            _require(
                proof.get("bankSha256") == source_family["bankSha256"]
                and proof.get("audioSignatureSha256") == family_row.get("audioSignatureSha256"),
                f"{runtime_id}/{car_id} alias proof differs from the exact bank family",
            )
            proof_by_car[car_id] = proof
        _require(set(proof_by_car) == set(expected_members), f"{runtime_id} alias proof coverage differs")

        family_directory = atlas_root / "families" / runtime_id
        plan = _read_object(family_directory / "plan.json", f"{runtime_id} plan")
        oracle = _read_object(family_directory / "oracle-status.json", f"{runtime_id} oracle")
        runtime = _read_object(family_directory / "runtime-index.json", f"{runtime_id} runtime")
        _require(
            oracle.get("initialPlanSha256") == family_row["planSha256"],
            f"{runtime_id} oracle does not descend from the batch plan",
        )
        _require(
            oracle.get("finalPlanSha256") == plan.get("planSha256"),
            f"{runtime_id} oracle/final plan mapping differs",
        )
        blockers = _status_blockers(runtime, runtime_id)
        _require(not blockers, f"{runtime_id} runtime still has BLOCKED status at {blockers}")

    return program_by_bank_family


def _validate_zip_against_report(
    *,
    pack_path: Path,
    runtime_id: str,
    requirement: Mapping[str, Any],
    pack_report: Mapping[str, Any],
) -> dict[str, Any]:
    _require(pack_path.is_file(), f"staged pack is missing: {pack_path}")
    archive_bytes = pack_path.stat().st_size
    _require(
        archive_bytes == pack_report.get("packBytes")
        and archive_bytes <= catalog_builder.MAXIMUM_ARCHIVE_BYTES,
        f"{runtime_id} archive size differs or exceeds the importer limit",
    )
    archive_sha = catalog_builder.sha256_file(pack_path)
    _require(archive_sha == pack_report.get("packSha256"), f"{runtime_id} archive hash differs")
    with zipfile.ZipFile(pack_path, "r") as archive:
        members = archive.infolist()
        names = [item.filename for item in members]
        _require(
            names and names[0] == "manifest.json" and names[1:] == sorted(names[1:]),
            f"{runtime_id} ZIP member order is not canonical",
        )
        _require(len(names) == len(set(names)), f"{runtime_id} ZIP has duplicate members")
        _require(
            len(names) <= catalog_builder.MAXIMUM_MEMBER_COUNT,
            f"{runtime_id} ZIP exceeds the importer member limit",
        )
        _require(
            all(
                item.compress_type == zipfile.ZIP_STORED
                and item.date_time == (1980, 1, 1, 0, 0, 0)
                and item.create_system == 3
                and item.extra == b""
                and item.comment == b""
                and item.file_size <= catalog_builder.MAXIMUM_MEMBER_BYTES
                for item in members
            )
            and archive.comment == b"",
            f"{runtime_id} ZIP member metadata is not deterministic/importable",
        )
        manifest_bytes = archive.read("manifest.json")
        _require(
            len(manifest_bytes) <= catalog_builder.MAXIMUM_MANIFEST_BYTES,
            f"{runtime_id} manifest exceeds the importer limit",
        )
        manifest = json.loads(manifest_bytes)
        _require(
            manifest_bytes == catalog_builder.canonical_json_bytes(manifest),
            f"{runtime_id} manifest is not canonical JSON",
        )
        _require(
            manifest.get("schemaVersion") == catalog_builder.PACK_MANIFEST_SCHEMA_VERSION
            and manifest.get("packId") == requirement.get("packId")
            and manifest.get("packVersion") == requirement.get("packVersion")
            and _sha256_bytes(manifest_bytes) == requirement.get("manifestSha256"),
            f"{runtime_id} manifest identity differs from the root catalog",
        )
        files = manifest.get("files")
        _require(isinstance(files, list) and len(files) == len(names) - 1, f"{runtime_id} manifest member count differs")
        expected_names = [str(item.get("path")) for item in files]
        _require(expected_names == names[1:], f"{runtime_id} manifest/ZIP member set differs")
        extracted_bytes = 0
        for item in files:
            _require(isinstance(item, Mapping), f"{runtime_id} manifest contains a malformed file")
            path = str(item.get("path") or "")
            digest = hashlib.sha256()
            observed_bytes = 0
            header = bytearray()
            with archive.open(path, "r") as source:
                while block := source.read(1024 * 1024):
                    if len(header) < 44:
                        header.extend(block[: 44 - len(header)])
                    digest.update(block)
                    observed_bytes += len(block)
            extracted_bytes += observed_bytes
            _require(
                observed_bytes == item.get("sizeBytes")
                and digest.hexdigest() == item.get("sha256"),
                f"{runtime_id}/{path} payload differs from its manifest",
            )
            _require(
                item.get("sampleRate") == 48_000 and item.get("channels") == 2,
                f"{runtime_id}/{path} is not declared 48 kHz stereo",
            )
            # The canonical geometry has already been checked before packing.
            # Re-check frame count directly from the immutable RIFF payload here
            # without materializing a second multi-hundred-megabyte file.
            _require(header[:4] == b"RIFF" and header[8:12] == b"WAVE", f"{runtime_id}/{path} is not RIFF/WAVE")
            data_size = int.from_bytes(header[40:44], "little", signed=False)
            _require(
                observed_bytes == 44 + data_size and data_size // 4 == item.get("frameCount"),
                f"{runtime_id}/{path} WAV frame count differs",
            )
        _require(
            extracted_bytes == pack_report.get("payloadBytes")
            and extracted_bytes <= catalog_builder.MAXIMUM_EXTRACTED_BYTES,
            f"{runtime_id} extracted payload differs or exceeds the importer limit",
        )

    return {
        "sourceFileName": pack_path.name,
        "packId": requirement["packId"],
        "packVersion": requirement["packVersion"],
        "manifestSha256": requirement["manifestSha256"],
        "archiveSha256": archive_sha,
        "archiveBytes": archive_bytes,
        "fileCount": pack_report["fileCount"],
        "payloadBytes": pack_report["payloadBytes"],
    }


def _usb_inventory(
    *,
    source_catalog_path: Path,
    atlas_root: Path,
    root_catalog: Mapping[str, Any],
    assembly_report: Mapping[str, Any],
    pack_directory: Path,
    pack_version: int,
) -> dict[str, Any]:
    family_by_id = {item["id"]: item for item in root_catalog["families"]}
    source = catalog_builder._load_source_catalog(source_catalog_path)
    source_family_by_bank = {item["bankFamilyId"]: item for item in source["families"]}
    entries: list[dict[str, Any]] = []
    for pack_report in sorted(assembly_report["packs"], key=lambda item: item["audioProgramFamilyId"]):
        runtime_id = pack_report["audioProgramFamilyId"]
        descriptor = family_by_id[runtime_id]
        filename = f"{runtime_id}-v{pack_version}.bydpack"
        verified = _validate_zip_against_report(
            pack_path=pack_directory / filename,
            runtime_id=runtime_id,
            requirement=descriptor["packRequirement"],
            pack_report=pack_report,
        )
        source_family = source_family_by_bank[pack_report["bankFamilyId"]]
        entries.append(
            {
                "audioProgramFamilyId": runtime_id,
                "bankFamilyId": pack_report["bankFamilyId"],
                "bankSha256": source_family["bankSha256"],
                "memberCarIds": source_family["memberCarIds"],
                **verified,
            }
        )
    expected_names = {entry["sourceFileName"] for entry in entries}
    actual_names = {path.name for path in pack_directory.iterdir() if path.is_file()}
    _require(actual_names == expected_names, f"staged USB pack set contains missing/orphan files: {sorted(actual_names ^ expected_names)}")
    root_catalog_payload = _canonical_json_payload(root_catalog)

    return {
        "schema": USB_INVENTORY_SCHEMA,
        "status": "PASS",
        "packVersion": pack_version,
        "sourceCatalog": {
            "schema": source["schema"],
            "sha256": catalog_builder.sha256_file(source_catalog_path),
        },
        "atlasCatalog": {
            "schema": catalog_builder.BATCH_SCHEMA,
            "sha256": catalog_builder.sha256_file(atlas_root / "catalog.json"),
        },
        "androidCatalog": {
            "schema": root_catalog["schema"],
            "catalogVersion": root_catalog["catalogVersion"],
            "sha256": _sha256_bytes(root_catalog_payload),
            "bytes": len(root_catalog_payload),
        },
        "carCount": len(root_catalog["cars"]),
        "familyCount": len(root_catalog["families"]),
        "packCount": len(entries),
        "totalArchiveBytes": sum(item["archiveBytes"] for item in entries),
        "totalPayloadBytes": sum(item["payloadBytes"] for item in entries),
        "packs": entries,
    }


def _checksum_payload(pack_directory: Path, inventory_payload: bytes) -> bytes:
    rows = [
        f"{catalog_builder.sha256_file(path)}  {path.name}"
        for path in sorted(pack_directory.glob("*.bydpack"), key=lambda item: item.name)
    ]
    rows.append(f"{_sha256_bytes(inventory_payload)}  {USB_INVENTORY_FILE_NAME}")

    return ("\n".join(rows) + "\n").encode("ascii")


@contextmanager
def _adjacent_staging_directory(target: Path) -> Iterator[Path]:
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{target.name}.staging-", dir=target.parent))
    try:
        yield staging
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _fsync_staged_directories(root: Path) -> None:
    """Make every already-synced staged file name durable before publishing its tree."""

    directories = [root, *(path for path in root.rglob("*") if path.is_dir())]
    for directory in sorted(directories, key=lambda path: len(path.parts), reverse=True):
        _require(not directory.is_symlink(), f"release staging contains a symlinked directory: {directory}")
        _fsync_directory(directory)


def _publish_trees_atomically(
    staged_targets: Sequence[tuple[Path, Path]],
    finalize: Callable[[], None] | None = None,
) -> None:
    """Publish complete trees and final evidence with rollback on an in-process failure."""

    backups: list[tuple[Path, Path | None]] = []
    published: list[tuple[Path, Path | None]] = []
    try:
        for staged, target in staged_targets:
            _require(staged.is_dir(), f"release staging directory is missing: {staged}")
            _fsync_staged_directories(staged)
            target.parent.mkdir(parents=True, exist_ok=True)
            backup: Path | None = None
            if target.exists():
                _require(target.is_dir() and not target.is_symlink(), f"release target is not a directory: {target}")
                backup = target.parent / f".{target.name}.backup-{uuid.uuid4().hex}"
                os.replace(target, backup)
            backups.append((target, backup))
            if backup is not None:
                _fsync_directory(target.parent)
            os.replace(staged, target)
            published.append((target, backup))
            _fsync_directory(target.parent)
        if finalize is not None:
            finalize()
    except BaseException:
        for target, backup in reversed(published):
            if target.exists():
                failed = target.parent / f".{target.name}.failed-{uuid.uuid4().hex}"
                os.replace(target, failed)
                shutil.rmtree(failed, ignore_errors=True)
            if backup is not None and backup.exists():
                os.replace(backup, target)
                _fsync_directory(target.parent)
        for target, backup in reversed(backups[len(published) :]):
            if backup is not None and backup.exists() and not target.exists():
                os.replace(backup, target)
                _fsync_directory(target.parent)
        raise
    for _target, backup in backups:
        if backup is not None:
            shutil.rmtree(backup)
            _fsync_directory(backup.parent)


def assemble_external_release(
    *,
    source_catalog_path: Path,
    atlas_root: Path,
    usb_output_directory: Path,
    android_catalog_directory: Path,
    report_output_path: Path,
    pack_version: int,
) -> dict[str, Any]:
    """Validate, stage, verify, and transactionally publish one full release."""

    source_catalog_path = source_catalog_path.resolve(strict=True)
    atlas_root = atlas_root.resolve(strict=True)
    source = catalog_builder._load_source_catalog(source_catalog_path)
    batch = _read_object(atlas_root / "catalog.json", "atlas catalog")
    validate_atlas_topology(atlas_root=atlas_root, source=source, batch=batch)
    _require(
        isinstance(pack_version, int)
        and not isinstance(pack_version, bool)
        and 1 <= pack_version <= 2_147_483_647,
        "pack version must fit the Android positive Int contract",
    )
    usb_output_directory = usb_output_directory.resolve()
    android_catalog_directory = android_catalog_directory.resolve()
    report_output_path = report_output_path.resolve()
    _require(
        usb_output_directory != android_catalog_directory
        and usb_output_directory not in android_catalog_directory.parents
        and android_catalog_directory not in usb_output_directory.parents,
        "USB and Android catalog output directories must be disjoint",
    )
    _require(
        report_output_path != usb_output_directory
        and report_output_path != android_catalog_directory
        and usb_output_directory not in report_output_path.parents
        and android_catalog_directory not in report_output_path.parents,
        "release report must live outside the published USB and Android catalog trees",
    )

    with _adjacent_staging_directory(usb_output_directory) as usb_staging, _adjacent_staging_directory(
        android_catalog_directory
    ) as android_staging:
        root_catalog, assembly_report = catalog_builder.assemble_release(
            source_catalog_path=source_catalog_path,
            atlas_root=atlas_root,
            pack_output_directory=usb_staging,
            runtime_index_output_directory=android_staging / "families",
            pack_version=pack_version,
        )
        root_catalog_path = android_staging / ANDROID_ROOT_CATALOG_FILE_NAME
        catalog_builder._write_json(root_catalog_path, root_catalog)
        root_payload = root_catalog_path.read_bytes()
        _require(
            root_payload == _canonical_json_payload(root_catalog),
            "staged Android root catalog is not canonical JSON",
        )
        expected_android_files = {ANDROID_ROOT_CATALOG_FILE_NAME} | {
            f"families/{family['id']}.json" for family in root_catalog["families"]
        }
        actual_android_files = {
            str(path.relative_to(android_staging))
            for path in android_staging.rglob("*")
            if path.is_file()
        }
        _require(
            actual_android_files == expected_android_files,
            f"staged Android catalog contains missing/orphan assets: {sorted(actual_android_files ^ expected_android_files)}",
        )
        inventory = _usb_inventory(
            source_catalog_path=source_catalog_path,
            atlas_root=atlas_root,
            root_catalog=root_catalog,
            assembly_report=assembly_report,
            pack_directory=usb_staging,
            pack_version=pack_version,
        )
        inventory_payload = _canonical_json_payload(inventory)
        catalog_builder._write_atomic(usb_staging / USB_INVENTORY_FILE_NAME, inventory_payload)
        checksum_payload = _checksum_payload(usb_staging, inventory_payload)
        catalog_builder._write_atomic(usb_staging / USB_CHECKSUM_FILE_NAME, checksum_payload)
        expected_usb_files = {
            USB_INVENTORY_FILE_NAME,
            USB_CHECKSUM_FILE_NAME,
            *(entry["sourceFileName"] for entry in inventory["packs"]),
        }
        actual_usb_files = {path.name for path in usb_staging.iterdir() if path.is_file()}
        _require(
            actual_usb_files == expected_usb_files,
            f"staged USB release contains missing/orphan files: {sorted(actual_usb_files ^ expected_usb_files)}",
        )

        report = {
            "schema": RELEASE_REPORT_SCHEMA,
            "status": "PASS",
            "carCount": inventory["carCount"],
            "familyCount": inventory["familyCount"],
            "packCount": inventory["packCount"],
            "packVersion": pack_version,
            "usbOutputDirectory": str(usb_output_directory),
            "androidCatalogDirectory": str(android_catalog_directory),
            "androidCatalogSha256": inventory["androidCatalog"]["sha256"],
            "usbInventorySha256": _sha256_bytes(inventory_payload),
            "usbChecksumsSha256": _sha256_bytes(checksum_payload),
            "totalArchiveBytes": inventory["totalArchiveBytes"],
            "totalPayloadBytes": inventory["totalPayloadBytes"],
            "packs": inventory["packs"],
            "validation": {
                "canonicalJson": "PASS",
                "catalogInputs": "PASS",
                "familyOracles": "PASS",
                "runtimeBlockedMarkers": "PASS",
                "sourceFamilyCarMapping": "PASS",
                "wavAndManifestHashes": "PASS",
                "importerLimits": {
                    "maximumArchiveBytes": catalog_builder.MAXIMUM_ARCHIVE_BYTES,
                    "maximumMemberBytes": catalog_builder.MAXIMUM_MEMBER_BYTES,
                    "maximumExtractedBytes": catalog_builder.MAXIMUM_EXTRACTED_BYTES,
                    "maximumMemberCount": catalog_builder.MAXIMUM_MEMBER_COUNT,
                    "maximumManifestBytes": catalog_builder.MAXIMUM_MANIFEST_BYTES,
                },
            },
        }
        _publish_trees_atomically(
            (
                (usb_staging, usb_output_directory),
                (android_staging, android_catalog_directory),
            ),
            finalize=lambda: catalog_builder._write_json(report_output_path, report),
        )

    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-catalog", type=Path, required=True)
    parser.add_argument("--atlas-root", type=Path, required=True)
    parser.add_argument("--usb-output-directory", type=Path, required=True)
    parser.add_argument("--android-catalog-directory", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--pack-version", type=int, default=1)
    args = parser.parse_args(argv)
    try:
        report = assemble_external_release(
            source_catalog_path=args.source_catalog,
            atlas_root=args.atlas_root,
            usb_output_directory=args.usb_output_directory,
            android_catalog_directory=args.android_catalog_directory,
            report_output_path=args.report_output,
            pack_version=args.pack_version,
        )
    except (ExternalAudioReleaseError, catalog_builder.CatalogBuildError, OSError, ValueError, zipfile.BadZipFile) as error:
        blocked = {
            "schema": RELEASE_REPORT_SCHEMA,
            "status": "BLOCKED",
            "error": str(error),
        }
        catalog_builder._write_json(args.report_output.resolve(), blocked)
        parser.error(str(error))
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
