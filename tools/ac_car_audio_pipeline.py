#!/usr/bin/env python3
"""Safely intake Assetto Corsa cars and inspect/extract their FMOD banks.

Generated artifacts are intentionally kept outside the APK source tree.  Each
car gets a machine-readable work directory containing its intake report, raw
and GUID-enriched FMOD graph, extraction selection, and extracted WAV files.
"""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import uuid
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LAB_CARS_DIR = Path(
    "/Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab/"
    "macos_bank_lab/content/cars"
)
DEFAULT_WORK_ROOT = REPO_ROOT / "build" / "new-car-audio"
DEFAULT_AUDIT_DLL = Path(
    "/Users/gabrielcarvalho/Downloads/assetto_corsa_audio_lab/tools/"
    "fmod_bank_graph_audit/bin/Release/net8.0/FmodBankGraphAudit.dll"
)

SUPPORTED_ARCHIVE_SUFFIXES = frozenset({".zip", ".rar", ".7z"})
ARCHIVE_LISTING_MAX_BYTES = 16 * 1024 * 1024
DEFAULT_MEMBER_MAX_BYTES = 512 * 1024 * 1024
DEFAULT_CAR_MAX_BYTES = 1024 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
GUID_LINE = re.compile(
    r"^\s*\{(?P<guid>[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})\}"
    r"\s+(?P<path>\S.*)\s*$"
)
WINDOWS_DRIVE = re.compile(r"^[A-Za-z]:")
ARCHIVE_GLOB_META = frozenset("*?[]")
UI_SUFFIXES = frozenset({".json", ".ini", ".txt", ".png", ".jpg", ".jpeg", ".webp"})
PREVIEW_SUFFIXES = frozenset({".png", ".jpg", ".jpeg", ".webp"})
ARTIFACT_MARKERS = frozenset(
    {
        "alt",
        "alternate",
        "alternates",
        "backup",
        "backups",
        "old",
        "old_sfx",
        "original",
        "sfx_backup",
        "sfx_old",
    }
)
PACK_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,95}$")
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
RUNTIME_EVENT_ORACLE_SCHEMA = "byd-ac-fmod-runtime-event-oracle-v1"


class PipelineError(RuntimeError):
    """An input or external-tool failure that should be shown to the operator."""


@dataclass(frozen=True)
class ArchiveMember:
    original: str
    normalized: str
    is_directory: bool


@dataclass(frozen=True)
class CarIntakePlan:
    archive: str
    car_id: str
    archive_root: str
    active_bank_member: str
    guids_member: str
    selected_members: tuple[str, ...]
    excluded_bank_members: tuple[str, ...]
    has_data_acd: bool
    loose_data_files: int
    preview_members: tuple[str, ...]
    preferred_preview_member: str


@dataclass(frozen=True)
class Pcm16WavInfo:
    sample_rate: int
    channels: int
    frame_count: int


def normalize_archive_member(raw: str) -> tuple[str, bool]:
    """Return a safe normalized POSIX member name.

    The selected members are later passed back to bsdtar as exact arguments.
    Rejecting glob syntax avoids libarchive treating an archive-controlled name
    as a pattern.  All writes themselves are streamed to explicitly-created
    destination files, never extracted by path from the archive.
    """

    if not raw or "\x00" in raw:
        raise PipelineError("archive contains an empty or NUL-containing path")
    if "\\" in raw:
        raise PipelineError(f"archive path uses a backslash: {raw!r}")
    if any(character in raw for character in ARCHIVE_GLOB_META):
        raise PipelineError(f"archive path uses unsupported glob syntax: {raw!r}")
    if any(ord(character) < 32 for character in raw):
        raise PipelineError(f"archive path contains a control character: {raw!r}")

    is_directory = raw.endswith("/")
    candidate = raw.rstrip("/")
    if candidate.startswith("/") or WINDOWS_DRIVE.match(candidate):
        raise PipelineError(f"archive path is absolute: {raw!r}")

    parts = candidate.split("/")
    while parts and parts[0] == ".":
        parts.pop(0)
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise PipelineError(f"archive path is not safely relative: {raw!r}")

    normalized = PurePosixPath(*parts).as_posix()
    return normalized, is_directory


def archive_members_from_listing(lines: Iterable[str]) -> tuple[ArchiveMember, ...]:
    members: list[ArchiveMember] = []
    file_names: set[str] = set()
    for line in lines:
        normalized, is_directory = normalize_archive_member(line)
        if not is_directory:
            if normalized in file_names:
                raise PipelineError(f"archive contains duplicate file member: {normalized}")
            file_names.add(normalized)
        members.append(ArchiveMember(line, normalized, is_directory))

    return tuple(members)


def list_archive_members(archive: Path, *, bsdtar: str = "bsdtar") -> tuple[ArchiveMember, ...]:
    archive = archive.expanduser().resolve()
    if not archive.is_file():
        raise PipelineError(f"archive does not exist: {archive}")
    if archive.suffix.casefold() not in SUPPORTED_ARCHIVE_SUFFIXES:
        raise PipelineError(f"unsupported archive type: {archive.name}")

    try:
        result = subprocess.run(
            [bsdtar, "-tf", str(archive)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError as error:
        raise PipelineError(f"bsdtar was not found: {bsdtar}") from error
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise PipelineError(f"bsdtar could not list {archive.name}: {detail}")
    if len(result.stdout) > ARCHIVE_LISTING_MAX_BYTES:
        raise PipelineError(f"archive listing exceeds {ARCHIVE_LISTING_MAX_BYTES} bytes")

    try:
        listing = result.stdout.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise PipelineError(f"archive paths are not valid UTF-8: {archive.name}") from error
    if not listing:
        raise PipelineError(f"archive is empty: {archive.name}")

    return archive_members_from_listing(listing)


def is_artifact_path(parts: Sequence[str]) -> bool:
    return any(part.casefold() in ARTIFACT_MARKERS for part in parts)


def _member_under_root(member: ArchiveMember, root_parts: tuple[str, ...]) -> tuple[str, ...] | None:
    parts = tuple(member.normalized.split("/"))
    if len(parts) <= len(root_parts) or parts[: len(root_parts)] != root_parts:
        return None

    return parts[len(root_parts) :]


def select_active_bank(bank_members: Sequence[ArchiveMember], car_id: str) -> ArchiveMember:
    exact = [member for member in bank_members if Path(member.normalized).stem.casefold() == car_id.casefold()]
    if len(exact) == 1:
        return exact[0]
    if len(exact) > 1:
        names = ", ".join(member.normalized for member in exact)
        raise PipelineError(f"multiple exact active banks for {car_id}: {names}")
    if len(bank_members) == 1:
        return bank_members[0]

    names = ", ".join(member.normalized for member in bank_members)
    raise PipelineError(f"cannot choose an active bank for {car_id}: {names}")


def build_car_intake_plans(archive: Path, members: Sequence[ArchiveMember]) -> tuple[CarIntakePlan, ...]:
    direct_banks: dict[tuple[str, ...], list[ArchiveMember]] = {}
    ignored_banks: list[ArchiveMember] = []
    for member in members:
        if member.is_directory or not member.normalized.casefold().endswith(".bank"):
            continue
        parts = tuple(member.normalized.split("/"))
        if len(parts) < 3 or parts[-2].casefold() != "sfx" or is_artifact_path(parts[:-2]):
            ignored_banks.append(member)
            continue
        direct_banks.setdefault(parts[:-2], []).append(member)

    plans: list[CarIntakePlan] = []
    for root_parts, banks in sorted(direct_banks.items(), key=lambda pair: pair[0]):
        car_id = root_parts[-1]
        active_bank = select_active_bank(banks, car_id)
        files: list[tuple[ArchiveMember, tuple[str, ...]]] = []
        for member in members:
            if member.is_directory:
                continue
            relative = _member_under_root(member, root_parts)
            if relative is not None:
                files.append((member, relative))

        guids = [
            member
            for member, relative in files
            if len(relative) == 2
            and relative[0].casefold() == "sfx"
            and relative[1].casefold() == "guids.txt"
        ]
        if len(guids) != 1:
            raise PipelineError(f"{car_id} must contain exactly one sfx/GUIDs.txt")

        data_acd = [
            member
            for member, relative in files
            if len(relative) == 1 and relative[0].casefold() == "data.acd"
        ]
        loose_data = [
            member
            for member, relative in files
            if len(relative) >= 2 and relative[0].casefold() == "data"
        ]
        if len(data_acd) > 1:
            raise PipelineError(f"{car_id} contains multiple data.acd files")
        if not data_acd and not loose_data:
            raise PipelineError(f"{car_id} has neither data.acd nor loose data files")

        ui_files = [
            member
            for member, relative in files
            if len(relative) >= 2
            and relative[0].casefold() == "ui"
            and Path(relative[-1]).suffix.casefold() in UI_SUFFIXES
            and not is_artifact_path(relative)
        ]
        ui_car = [
            member
            for member, relative in files
            if len(relative) == 2
            and relative[0].casefold() == "ui"
            and relative[1].casefold() == "ui_car.json"
        ]
        if len(ui_car) != 1:
            raise PipelineError(f"{car_id} must contain exactly one ui/ui_car.json")

        skin_previews = sorted(
            (
                member
                for member, relative in files
                if len(relative) == 3
                and relative[0].casefold() == "skins"
                and relative[2].casefold().startswith("preview.")
                and Path(relative[2]).suffix.casefold() in PREVIEW_SUFFIXES
            ),
            key=lambda member: member.normalized.casefold(),
        )
        selected_preview = skin_previews[:1]
        ui_previews = [
            member
            for member, relative in files
            if len(relative) == 2
            and relative[0].casefold() == "ui"
            and Path(relative[1]).stem.casefold() in {"preview", "badge", "dlc_preview"}
            and Path(relative[1]).suffix.casefold() in PREVIEW_SUFFIXES
        ]
        preferred_ui_previews = sorted(
            (
                member
                for member, relative in files
                if len(relative) == 2
                and relative[0].casefold() == "ui"
                and Path(relative[1]).stem.casefold() == "preview"
                and Path(relative[1]).suffix.casefold() in PREVIEW_SUFFIXES
            ),
            key=lambda member: member.normalized.casefold(),
        )
        badges = sorted(
            (
                member
                for member, relative in files
                if len(relative) == 2
                and relative[0].casefold() == "ui"
                and Path(relative[1]).stem.casefold() == "badge"
                and Path(relative[1]).suffix.casefold() in PREVIEW_SUFFIXES
            ),
            key=lambda member: member.normalized.casefold(),
        )
        preferred_preview = next(
            iter((*preferred_ui_previews, *selected_preview, *badges)),
            None,
        )
        if preferred_preview is None:
            raise PipelineError(f"{car_id} contains no preview or badge image")

        selected = {
            active_bank.original,
            guids[0].original,
            *(member.original for member in data_acd),
            *(member.original for member in loose_data),
            *(member.original for member in ui_files),
            *(member.original for member in selected_preview),
        }
        excluded = {
            *(member.original for member in banks if member != active_bank),
            *(
                member.original
                for member in ignored_banks
                if tuple(member.normalized.split("/"))[: len(root_parts)] == root_parts
            ),
        }
        previews = tuple(sorted({*(member.original for member in ui_previews), *(member.original for member in selected_preview)}))
        plans.append(
            CarIntakePlan(
                archive=str(archive.expanduser().resolve()),
                car_id=car_id,
                archive_root=PurePosixPath(*root_parts).as_posix(),
                active_bank_member=active_bank.original,
                guids_member=guids[0].original,
                selected_members=tuple(sorted(selected)),
                excluded_bank_members=tuple(sorted(excluded)),
                has_data_acd=bool(data_acd),
                loose_data_files=len(loose_data),
                preview_members=previews,
                preferred_preview_member=preferred_preview.original,
            )
        )

    if not plans:
        raise PipelineError(f"archive contains no active car bank: {archive.name}")

    return tuple(plans)


def discover_archives(directory: Path) -> tuple[Path, ...]:
    directory = directory.expanduser().resolve()
    if not directory.is_dir():
        raise PipelineError(f"archives directory does not exist: {directory}")
    archives = tuple(
        sorted(
            (
                path
                for path in directory.iterdir()
                if path.is_file() and path.suffix.casefold() in SUPPORTED_ARCHIVE_SUFFIXES
            ),
            key=lambda path: path.name.casefold(),
        )
    )
    if not archives:
        raise PipelineError(f"no supported archives found in {directory}")

    return archives


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(READ_CHUNK_BYTES), b""):
            digest.update(chunk)

    return digest.hexdigest()


def _atomic_write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as output:
            json.dump(payload, output, indent=2, sort_keys=True, ensure_ascii=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _atomic_write_preview_csv(path: Path, records: Sequence[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    fields = ("carId", "source", "path", "bytes", "sha256")
    try:
        with temporary.open("w", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
            writer.writeheader()
            writer.writerows(records)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def materialize_car_preview(
    plan: CarIntakePlan,
    *,
    installed_car_root: Path,
    preview_root: Path,
) -> dict[str, Any]:
    member = ArchiveMember(plan.preferred_preview_member, plan.preferred_preview_member, False)
    source_relative = _relative_destination(plan, member)
    source = installed_car_root / source_relative
    if not source.is_file():
        raise PipelineError(f"preferred preview was not installed: {source}")

    preview_root = preview_root.expanduser().resolve()
    suffix = source.suffix.casefold()
    destination = preview_root / plan.car_id / f"preview{suffix}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    try:
        shutil.copyfile(source, temporary)
        with temporary.open("rb") as preview:
            os.fsync(preview.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)

    mapping_path = preview_root / "previews.json"
    existing_records: list[dict[str, Any]] = []
    if mapping_path.is_file():
        try:
            existing = json.loads(mapping_path.read_text(encoding="utf-8"))
            if existing.get("schema") == "byd-ac-car-previews-v1" and isinstance(existing.get("cars"), list):
                existing_records = [record for record in existing["cars"] if isinstance(record, dict)]
        except (OSError, json.JSONDecodeError):
            existing_records = []
    previous = next((record for record in existing_records if record.get("carId") == plan.car_id), None)
    if previous is not None:
        previous_path = Path(str(previous.get("path", "")))
        if previous_path != destination and previous_path.is_file() and preview_root in previous_path.parents:
            previous_path.unlink()

    record = {
        "carId": plan.car_id,
        "source": source_relative.as_posix(),
        "path": str(destination),
        "bytes": destination.stat().st_size,
        "sha256": _sha256(destination),
    }
    records_by_car = {
        str(item.get("carId")): item
        for item in existing_records
        if item.get("carId") and item.get("carId") != plan.car_id
    }
    records_by_car[plan.car_id] = record
    records = [records_by_car[car_id] for car_id in sorted(records_by_car, key=str.casefold)]
    _atomic_write_json(
        mapping_path,
        {"schema": "byd-ac-car-previews-v1", "cars": records},
    )
    _atomic_write_preview_csv(preview_root / "previews.csv", records)
    return record


def _plan_member_map(plan: CarIntakePlan, members: Sequence[ArchiveMember]) -> dict[str, ArchiveMember]:
    by_original = {member.original: member for member in members if not member.is_directory}
    try:
        return {name: by_original[name] for name in plan.selected_members}
    except KeyError as error:
        raise PipelineError(f"selected archive member disappeared: {error.args[0]}") from error


def _relative_destination(plan: CarIntakePlan, member: ArchiveMember) -> Path:
    root_parts = tuple(plan.archive_root.split("/"))
    member_parts = tuple(member.normalized.split("/"))
    relative = member_parts[len(root_parts) :]
    if not relative or member_parts[: len(root_parts)] != root_parts:
        raise PipelineError(f"member is outside selected car root: {member.normalized}")

    return Path(*relative)


def stream_archive_member(
    archive: Path,
    member: ArchiveMember,
    destination: Path,
    *,
    bsdtar: str,
    member_max_bytes: int,
    remaining_car_bytes: int,
) -> int:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.part")
    with tempfile.TemporaryFile() as stderr:
        try:
            process = subprocess.Popen(
                [bsdtar, "-xOf", str(archive), "--", member.original],
                stdout=subprocess.PIPE,
                stderr=stderr,
            )
        except FileNotFoundError as error:
            raise PipelineError(f"bsdtar was not found: {bsdtar}") from error

        written = 0
        try:
            assert process.stdout is not None
            with temporary.open("wb") as output:
                while True:
                    chunk = process.stdout.read(READ_CHUNK_BYTES)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > member_max_bytes:
                        process.kill()
                        raise PipelineError(
                            f"archive member exceeds {member_max_bytes} bytes: {member.normalized}"
                        )
                    if written > remaining_car_bytes:
                        process.kill()
                        raise PipelineError("selected car files exceed the configured total byte limit")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            return_code = process.wait()
            if return_code != 0:
                stderr.seek(0)
                detail = stderr.read(8192).decode("utf-8", errors="replace").strip()
                raise PipelineError(f"bsdtar could not read {member.normalized}: {detail}")
            os.replace(temporary, destination)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            temporary.unlink(missing_ok=True)

    return written


def install_car_plan(
    plan: CarIntakePlan,
    members: Sequence[ArchiveMember],
    *,
    lab_cars_dir: Path,
    work_root: Path,
    preview_root: Path,
    bsdtar: str,
    replace: bool,
    dry_run: bool,
    member_max_bytes: int,
    car_max_bytes: int,
) -> dict[str, Any]:
    archive = Path(plan.archive)
    lab_cars_dir = lab_cars_dir.expanduser().resolve()
    destination = lab_cars_dir / plan.car_id
    report: dict[str, Any] = {
        "schema": "byd-ac-car-intake-v1",
        "dryRun": dry_run,
        "plan": asdict(plan),
        "destination": str(destination),
        "files": [],
        "selectedBytes": None,
        "preview": {
            "source": plan.preferred_preview_member,
            "root": str(preview_root.expanduser().resolve()),
        },
    }
    if dry_run:
        return report

    lab_cars_dir.mkdir(parents=True, exist_ok=True)
    if destination.exists() and not replace:
        raise PipelineError(f"car destination already exists (use --replace): {destination}")
    selected = _plan_member_map(plan, members)
    staging = Path(tempfile.mkdtemp(prefix=f".{plan.car_id}.staging-", dir=lab_cars_dir))
    total_bytes = 0
    file_records: list[dict[str, Any]] = []
    try:
        for original in plan.selected_members:
            member = selected[original]
            relative = _relative_destination(plan, member)
            output = staging / relative
            size = stream_archive_member(
                archive,
                member,
                output,
                bsdtar=bsdtar,
                member_max_bytes=member_max_bytes,
                remaining_car_bytes=car_max_bytes - total_bytes,
            )
            total_bytes += size
            file_records.append(
                {
                    "archiveMember": member.original,
                    "path": relative.as_posix(),
                    "bytes": size,
                    "sha256": _sha256(output),
                }
            )

        backup: Path | None = None
        try:
            if destination.exists():
                backup = lab_cars_dir / f".{plan.car_id}.backup-{uuid.uuid4().hex}"
                destination.rename(backup)
            staging.rename(destination)
        except Exception:
            if backup is not None and backup.exists() and not destination.exists():
                backup.rename(destination)
            raise
        else:
            if backup is not None:
                shutil.rmtree(backup)
    finally:
        if staging.exists():
            shutil.rmtree(staging)

    report["files"] = file_records
    report["selectedBytes"] = total_bytes
    report["preview"] = materialize_car_preview(
        plan,
        installed_car_root=destination,
        preview_root=preview_root,
    )
    _atomic_write_json(work_root.expanduser().resolve() / plan.car_id / "intake.json", report)
    return report


def parse_guids(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(text.lstrip("\ufeff").splitlines(), start=1):
        if not raw_line.strip():
            continue
        match = GUID_LINE.match(raw_line)
        if match is None:
            raise PipelineError(f"invalid GUIDs.txt line {line_number}: {raw_line!r}")
        guid = match.group("guid").casefold()
        path = match.group("path").strip()
        previous = result.get(guid)
        if previous is not None and previous != path:
            raise PipelineError(f"GUID {guid} maps to both {previous!r} and {path!r}")
        result[guid] = path

    if not result:
        raise PipelineError("GUIDs.txt contains no mappings")

    return result


def parse_graph_guids(
    text: str,
    graph: dict[str, Any],
    *,
    car_id: str,
) -> tuple[dict[str, str], dict[str, Any] | None]:
    """Resolve only graph-relevant collisions by an exact active namespace.

    Mod packs often concatenate GUID catalogs for dozens of donor cars. A GUID
    collision outside the installed bank graph is irrelevant; a collision on
    the active bank/event graph remains fatal unless exactly one path uses this
    car's case-sensitive Assetto namespace.
    """

    candidates: dict[str, list[str]] = {}
    for line_number, raw_line in enumerate(text.lstrip("\ufeff").splitlines(), start=1):
        if not raw_line.strip():
            continue
        match = GUID_LINE.match(raw_line)
        if match is None:
            raise PipelineError(f"invalid GUIDs.txt line {line_number}: {raw_line!r}")
        guid = match.group("guid").casefold()
        path = match.group("path").strip()
        paths = candidates.setdefault(guid, [])
        if path not in paths:
            paths.append(path)
    if not candidates:
        raise PipelineError("GUIDs.txt contains no mappings")

    bank_guid = str((graph.get("bank") or {}).get("bankGuid") or "").casefold()
    event_guids = {
        str(event.get("guid") or "").casefold()
        for event in graph.get("events", [])
        if isinstance(event, dict) and event.get("guid")
    }
    referenced = event_guids | ({bank_guid} if bank_guid else set())
    result: dict[str, str] = {}
    resolved: list[dict[str, Any]] = []
    omitted: list[dict[str, Any]] = []
    for guid, paths in candidates.items():
        if len(paths) == 1:
            result[guid] = paths[0]
            continue
        if guid not in referenced:
            omitted.append({"guid": guid, "paths": sorted(paths)})
            continue
        if guid == bank_guid:
            exact = [path for path in paths if path == f"bank:/{car_id}"]
            proof = "exact active bank namespace"
        else:
            prefix = f"event:/cars/{car_id}/"
            exact = [path for path in paths if path.startswith(prefix)]
            proof = "exact active event namespace"
        if len(exact) != 1:
            raise PipelineError(
                f"referenced GUID {guid} has no unique exact active namespace: {paths}"
            )
        result[guid] = exact[0]
        resolved.append(
            {
                "guid": guid,
                "selectedPath": exact[0],
                "candidatePaths": sorted(paths),
                "proof": proof,
            }
        )

    missing_events = sorted(event_guids - result.keys())
    if missing_events:
        raise PipelineError(
            "active event GUIDs are absent from GUIDs.txt: " + ", ".join(missing_events)
        )
    evidence = None
    if resolved or omitted:
        evidence = {
            "strategy": (
                "omit collisions outside the active graph; resolve referenced collisions "
                "only by an exact case-sensitive active namespace"
            ),
            "resolvedReferencedCollisions": resolved,
            "omittedUnreferencedCollisions": omitted,
        }
    return result, evidence


def select_car_bank(car_root: Path) -> Path:
    sfx = car_root / "sfx"
    banks = sorted(
        (path for path in sfx.glob("*.bank") if path.is_file()),
        key=lambda path: path.name.casefold(),
    )
    if not banks:
        raise PipelineError(f"no direct sfx/*.bank found for {car_root.name}")
    exact = [path for path in banks if path.stem.casefold() == car_root.name.casefold()]
    if len(exact) == 1:
        return exact[0]
    if len(exact) > 1 or len(banks) > 1:
        raise PipelineError(f"cannot choose active bank for {car_root.name}: {', '.join(map(str, banks))}")

    return banks[0]


def enrich_graph(raw_graph: dict[str, Any], guid_paths: dict[str, str], *, car_id: str) -> dict[str, Any]:
    events: list[dict[str, Any]] = []
    instrument_events: dict[str, list[str]] = {}
    for raw_event in raw_graph.get("events", []):
        event = dict(raw_event)
        guid = str(event.get("guid", "")).casefold()
        event_path = guid_paths.get(guid)
        event["path"] = event_path
        events.append(event)
        for instrument_guid in event.get("reachableInstrumentGuids", []):
            instrument_events.setdefault(str(instrument_guid).casefold(), []).append(event_path or guid)

    samples: list[dict[str, Any]] = []
    for instrument in raw_graph.get("instruments", []):
        sample = instrument.get("sample")
        if not isinstance(sample, dict):
            continue
        instrument_guid = str(instrument.get("guid", "")).casefold()
        samples.append(
            {
                "instrumentGuid": instrument_guid,
                "eventPaths": sorted(set(instrument_events.get(instrument_guid, []))),
                "subsoundIndex": sample.get("subsoundIndex"),
                "sample": sample,
                "baseProperties": instrument.get("baseProperties"),
                "controllerGuids": instrument.get("controllerGuids", []),
            }
        )

    samples.sort(
        key=lambda item: (
            item["subsoundIndex"] if isinstance(item["subsoundIndex"], int) else sys.maxsize,
            item["instrumentGuid"],
        )
    )
    mapped_events = sum(1 for event in events if event.get("path"))
    bank = dict(raw_graph.get("bank", {}))
    bank_guid = str(bank.get("bankGuid", "")).casefold()
    bank["path"] = guid_paths.get(bank_guid)
    return {
        "schema": "byd-ac-fmod-work-graph-v1",
        "carId": car_id,
        "sourceSchema": raw_graph.get("schema"),
        "bank": bank,
        "counts": raw_graph.get("counts", {}),
        "coverage": {
            **raw_graph.get("coverage", {}),
            "eventsWithGuidPath": mapped_events,
            "eventsWithoutGuidPath": len(events) - mapped_events,
        },
        "guidPaths": dict(sorted(guid_paths.items())),
        "parameters": raw_graph.get("parameters", []),
        "curves": raw_graph.get("curves", []),
        "controllers": raw_graph.get("controllers", []),
        "modulators": raw_graph.get("modulators", []),
        "effects": raw_graph.get("effects", []),
        "instruments": raw_graph.get("instruments", []),
        "events": events,
        "samples": samples,
        "featureKinds": raw_graph.get("featureKinds", {}),
        "unknownChunks": raw_graph.get("unknownChunks", []),
    }


def reconcile_graph_with_runtime_oracle(
    raw_graph: dict[str, Any],
    oracle: dict[str, Any],
    *,
    car_id: str,
    bank_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Reconcile static parser nodes against FMOD's active bank event list.

    Some third-party banks retain orphan EventNodes. The static parser quite
    correctly exposes them, but FMOD's Bank_GetEventList does not: they cannot
    be instantiated by the runtime. A correction is accepted only when a
    captured runtime oracle names the exact active/static-only GUID partition
    and an incomplete active event is a provable disjoint union of its resolver
    sources plus sources owned by declared static-only events.
    """

    if oracle.get("schema") != RUNTIME_EVENT_ORACLE_SCHEMA:
        raise PipelineError(
            f"runtime event oracle has unsupported schema: {oracle.get('schema')!r}"
        )
    if oracle.get("carId") != car_id:
        raise PipelineError(
            f"runtime event oracle carId mismatch: {oracle.get('carId')!r} != {car_id!r}"
        )
    if str(oracle.get("bankSha256") or "").casefold() != bank_sha256.casefold():
        raise PipelineError("runtime event oracle bank SHA-256 does not match installed bank")

    active_guids_raw = oracle.get("activeEventGuids")
    static_only_raw = oracle.get("staticOnlyEventGuids")
    repairs_raw = oracle.get("sampleGraphRepairs")
    if not isinstance(active_guids_raw, list) or not isinstance(static_only_raw, list):
        raise PipelineError("runtime event oracle must declare active/static-only event GUID arrays")
    if not isinstance(repairs_raw, list):
        raise PipelineError("runtime event oracle must declare sampleGraphRepairs")

    active_guids = [str(value).casefold() for value in active_guids_raw]
    static_only_guids = [str(value).casefold() for value in static_only_raw]
    if len(active_guids) != len(set(active_guids)):
        raise PipelineError("runtime event oracle contains duplicate active event GUIDs")
    if len(static_only_guids) != len(set(static_only_guids)):
        raise PipelineError("runtime event oracle contains duplicate static-only event GUIDs")
    if set(active_guids) & set(static_only_guids):
        raise PipelineError("runtime active/static-only event GUID sets overlap")

    reconciled = copy.deepcopy(raw_graph)
    raw_events = reconciled.get("events")
    raw_instruments = reconciled.get("instruments")
    if not isinstance(raw_events, list) or not isinstance(raw_instruments, list):
        raise PipelineError("static graph is missing events or instruments")
    events_by_guid = {
        str(event.get("guid") or "").casefold(): event
        for event in raw_events
        if isinstance(event, dict) and event.get("guid")
    }
    parser_guids = set(events_by_guid)
    active_set = set(active_guids)
    declared_static_only = set(static_only_guids)
    if active_set - parser_guids:
        raise PipelineError(
            "runtime event oracle contains GUIDs absent from static graph: "
            + ", ".join(sorted(active_set - parser_guids))
        )
    actual_static_only = parser_guids - active_set
    if actual_static_only != declared_static_only:
        raise PipelineError(
            "runtime event oracle static-only partition mismatch; "
            f"declared={sorted(declared_static_only)}, actual={sorted(actual_static_only)}"
        )

    sample_payload_sha_by_instrument: dict[str, str] = {}
    for instrument in raw_instruments:
        if not isinstance(instrument, dict):
            continue
        sample = instrument.get("sample")
        if not isinstance(sample, dict):
            continue
        guid = str(instrument.get("guid") or "").casefold()
        payload_sha256 = str(sample.get("encodedPayloadSha256") or "")
        if not guid or not payload_sha256:
            raise PipelineError("runtime reconciliation found a waveform without identity")
        sample_payload_sha_by_instrument[guid] = payload_sha256

    repairs: dict[str, dict[str, Any]] = {}
    for repair in repairs_raw:
        if not isinstance(repair, dict):
            raise PipelineError("runtime sample graph repair must be an object")
        event_guid = str(repair.get("activeEventGuid") or "").casefold()
        if not event_guid or event_guid in repairs:
            raise PipelineError("runtime sample graph repairs contain a missing/duplicate event GUID")
        repairs[event_guid] = repair

    applied_repairs: list[dict[str, Any]] = []
    for event_guid in active_guids:
        event = events_by_guid[event_guid]
        if event.get("mappingComplete") is True:
            if event_guid in repairs:
                raise PipelineError(f"runtime oracle repairs already-complete event {event_guid}")
            continue
        repair = repairs.pop(event_guid, None)
        if repair is None:
            raise PipelineError(f"runtime oracle has no repair for incomplete event {event_guid}")
        contamination_raw = repair.get("staticOnlyEventGuids")
        if not isinstance(contamination_raw, list) or not contamination_raw:
            raise PipelineError(f"runtime repair {event_guid} has no static-only event evidence")
        contamination_guids = {str(value).casefold() for value in contamination_raw}
        if not contamination_guids <= declared_static_only:
            raise PipelineError(
                f"runtime repair {event_guid} references undeclared static-only events"
            )
        contamination_samples = {
            str(sample_id)
            for guid in contamination_guids
            for sample_id in events_by_guid[guid].get("mappedSampleIds", [])
        }
        mapped_samples = {str(value) for value in event.get("mappedSampleIds", [])}
        resolver_samples = {str(value) for value in event.get("resolverSampleIds", [])}
        if resolver_samples & contamination_samples:
            raise PipelineError(
                f"runtime repair {event_guid} resolver/static-only samples overlap"
            )
        if mapped_samples != resolver_samples | contamination_samples:
            raise PipelineError(
                f"runtime repair {event_guid} is not an exact resolver/static-only partition"
            )

        def payload_hashes(sample_ids: set[str]) -> set[str]:
            result: set[str] = set()
            for sample_id in sample_ids:
                separator, payload_sha256 = sample_id.rpartition("|")[1:]
                if separator != "|" or not re.fullmatch(r"[0-9a-fA-F]{64}", payload_sha256):
                    raise PipelineError(
                        f"runtime repair {event_guid} contains an invalid sample identity"
                    )
                result.add(payload_sha256.casefold())
            return result

        contamination_payloads = payload_hashes(contamination_samples)
        resolver_payloads = payload_hashes(resolver_samples)
        if contamination_payloads & resolver_payloads:
            raise PipelineError(
                f"runtime repair {event_guid} resolver/static-only payloads overlap"
            )

        original_reachable = [
            str(value).casefold() for value in event.get("reachableInstrumentGuids", [])
        ]
        removed_instruments = sorted(
            guid
            for guid in original_reachable
            if sample_payload_sha_by_instrument.get(guid) in contamination_payloads
        )
        removed_payloads = {
            sample_payload_sha_by_instrument[guid] for guid in removed_instruments
        }
        if removed_payloads != contamination_payloads:
            raise PipelineError(
                f"runtime repair {event_guid} cannot attribute every static-only sample to an instrument"
            )
        remaining_reachable = [
            guid for guid in original_reachable if guid not in set(removed_instruments)
        ]
        remaining_payloads = {
            sample_payload_sha_by_instrument[guid]
            for guid in remaining_reachable
            if guid in sample_payload_sha_by_instrument
        }
        if remaining_payloads != resolver_payloads:
            raise PipelineError(
                f"runtime repair {event_guid} does not leave exactly the resolver sample set"
            )
        event["reachableInstrumentGuids"] = sorted(remaining_reachable)
        event["mappedSampleIds"] = sorted(resolver_samples)
        event["mappingComplete"] = True
        applied_repairs.append(
            {
                "activeEventGuid": event_guid,
                "staticOnlyEventGuids": sorted(contamination_guids),
                "removedWaveformInstrumentGuids": removed_instruments,
                "removedSampleIds": sorted(contamination_samples),
                "retainedResolverSampleIds": sorted(resolver_samples),
                "partitionProved": True,
            }
        )
    if repairs:
        raise PipelineError(
            "runtime oracle contains unused sample graph repairs: "
            + ", ".join(sorted(repairs))
        )

    active_events = [events_by_guid[guid] for guid in active_guids]
    reconciled["events"] = sorted(
        active_events, key=lambda event: str(event.get("guid") or "").casefold()
    )
    counts = dict(reconciled.get("counts") or {})
    counts["events"] = len(active_events)
    reconciled["counts"] = counts
    coverage = dict(reconciled.get("coverage") or {})
    coverage["eventsWithCompleteSampleMapping"] = sum(
        event.get("mappingComplete") is True for event in active_events
    )
    coverage["eventsWithSamples"] = sum(
        bool(event.get("resolverSampleIds")) for event in active_events
    )
    reconciled["coverage"] = coverage
    evidence = {
        "schema": RUNTIME_EVENT_ORACLE_SCHEMA,
        "runtime": oracle.get("runtime"),
        "runtimeEventCount": len(active_events),
        "staticParserEventCount": len(raw_events),
        "staticOnlyEventGuids": sorted(declared_static_only),
        "sampleGraphRepairs": applied_repairs,
        "pathRepairs": oracle.get("pathRepairs", []),
        "activeEventSetMatchesRuntime": True,
        "allActiveSampleMappingsComplete": all(
            event.get("mappingComplete") is True for event in active_events
        ),
    }
    return reconciled, evidence


def audit_car(
    car_id: str,
    *,
    lab_cars_dir: Path,
    work_root: Path,
    dotnet: str,
    audit_dll: Path,
    dry_run: bool,
) -> dict[str, Any]:
    car_root = lab_cars_dir.expanduser().resolve() / car_id
    if not car_root.is_dir():
        raise PipelineError(f"car is not installed in the lab: {car_root}")
    bank = select_car_bank(car_root)
    guids_file = car_root / "sfx" / "GUIDs.txt"
    if not guids_file.is_file():
        raise PipelineError(f"missing GUIDs.txt: {guids_file}")
    audit_dll = audit_dll.expanduser().resolve()
    if not audit_dll.is_file():
        raise PipelineError(f"compiled graph-audit DLL does not exist: {audit_dll}")

    command = [dotnet, str(audit_dll), str(bank)]
    work_directory = work_root.expanduser().resolve() / car_id
    report: dict[str, Any] = {
        "schema": "byd-ac-bank-audit-run-v1",
        "carId": car_id,
        "dryRun": dry_run,
        "bank": str(bank),
        "bankSha256": _sha256(bank),
        "guids": str(guids_file),
        "command": command,
        "rawGraph": str(work_directory / "graph.raw.json"),
        "graph": str(work_directory / "graph.json"),
    }
    if dry_run:
        return report

    work_directory.mkdir(parents=True, exist_ok=True)
    raw_temporary = work_directory / f".graph.raw.{uuid.uuid4().hex}.json"
    try:
        with raw_temporary.open("wb") as output:
            try:
                result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, check=False)
            except FileNotFoundError as error:
                raise PipelineError(f"dotnet was not found: {dotnet}") from error
        if result.returncode not in {0, 3}:
            detail = result.stderr.decode("utf-8", errors="replace").strip()
            raise PipelineError(f"FMOD graph audit failed ({result.returncode}): {detail}")
        try:
            raw_graph = json.loads(raw_temporary.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            detail = result.stderr.decode("utf-8", errors="replace").strip()
            raise PipelineError(f"FMOD graph audit did not produce valid JSON: {detail}") from error
        static_graph = raw_graph
        runtime_oracle_path = work_directory / "runtime-event-oracle.json"
        runtime_reconciliation: dict[str, Any] | None = None
        if runtime_oracle_path.is_file():
            try:
                runtime_oracle = json.loads(runtime_oracle_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
                raise PipelineError(
                    f"runtime event oracle is not valid JSON: {runtime_oracle_path}"
                ) from error
            static_graph, runtime_reconciliation = reconcile_graph_with_runtime_oracle(
                raw_graph,
                runtime_oracle,
                car_id=car_id,
                bank_sha256=report["bankSha256"],
            )
        guid_paths, guid_collision_resolution = parse_graph_guids(
            guids_file.read_text(encoding="utf-8-sig"),
            static_graph,
            car_id=car_id,
        )
        graph = enrich_graph(static_graph, guid_paths, car_id=car_id)
        if guid_collision_resolution is not None:
            graph["guidCollisionResolution"] = guid_collision_resolution
        if runtime_reconciliation is not None:
            graph["runtimeReconciliation"] = runtime_reconciliation
        os.replace(raw_temporary, work_directory / "graph.raw.json")
        _atomic_write_json(work_directory / "graph.json", graph)
    finally:
        raw_temporary.unlink(missing_ok=True)

    report["staticAuditExitCode"] = result.returncode
    report["runtimeReconciliationApplied"] = runtime_reconciliation is not None
    if runtime_reconciliation is not None:
        report["runtimeOracle"] = str(runtime_oracle_path)
    if guid_collision_resolution is not None:
        report["guidCollisionResolution"] = guid_collision_resolution
    report["auditExitCode"] = (
        0
        if graph["coverage"].get("eventsWithCompleteSampleMapping")
        == graph["counts"].get("events")
        else result.returncode
    )
    report["mappingComplete"] = report["auditExitCode"] == 0
    report["counts"] = graph["counts"]
    report["coverage"] = graph["coverage"]
    _atomic_write_json(work_directory / "audit.json", report)
    return report


def parse_subsound_selection(text: str) -> tuple[int, ...]:
    values: list[int] = []
    for item in text.split(","):
        item = item.strip()
        if not item:
            continue
        try:
            value = int(item)
        except ValueError as error:
            raise PipelineError(f"invalid zero-based subsound index: {item!r}") from error
        if value < 0:
            raise PipelineError(f"subsound indices cannot be negative: {value}")
        values.append(value)
    if not values:
        raise PipelineError("no subsound indices were selected")

    return tuple(sorted(set(values)))


def load_subsound_selection(arguments: argparse.Namespace) -> tuple[int, ...]:
    if arguments.subsounds:
        return parse_subsound_selection(arguments.subsounds)
    try:
        payload = json.loads(arguments.selection_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PipelineError(f"could not read selection file: {arguments.selection_file}") from error
    values = payload.get("subsounds") if isinstance(payload, dict) else payload
    if not isinstance(values, list) or not all(isinstance(value, int) for value in values):
        raise PipelineError("selection JSON must be an integer list or an object with a subsounds list")

    return parse_subsound_selection(",".join(str(value) for value in values))


def _safe_sample_name(value: Any) -> str:
    text = str(value or "sample").strip()
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", text).strip("._-")
    return (safe or "sample")[:80]


def _validate_wav(path: Path) -> None:
    with path.open("rb") as source:
        header = source.read(12)
    if len(header) != 12 or header[:4] not in {b"RIFF", b"RF64"} or header[8:] != b"WAVE":
        raise PipelineError(f"vgmstream produced an invalid WAV container: {path}")


def inspect_pcm16_wav(path: Path) -> Pcm16WavInfo:
    file_size = path.stat().st_size
    if file_size < 44:
        raise PipelineError(f"WAV is too short: {path}")
    with path.open("rb") as source:
        header = source.read(12)
        if header[:4] != b"RIFF" or header[8:] != b"WAVE":
            raise PipelineError(f"WAV must be a RIFF/WAVE file: {path}")
        if struct.unpack_from("<I", header, 4)[0] + 8 != file_size:
            raise PipelineError(f"WAV RIFF size does not match the file: {path}")

        audio_format: int | None = None
        channels: int | None = None
        sample_rate: int | None = None
        bits_per_sample: int | None = None
        data_bytes: int | None = None
        while source.tell() < file_size:
            chunk_header = source.read(8)
            if len(chunk_header) != 8:
                raise PipelineError(f"WAV has a truncated chunk header: {path}")
            chunk_id = chunk_header[:4]
            chunk_size = struct.unpack_from("<I", chunk_header, 4)[0]
            chunk_start = source.tell()
            chunk_end = chunk_start + chunk_size
            if chunk_end > file_size:
                raise PipelineError(f"WAV chunk exceeds the file: {path}")
            if chunk_id == b"fmt ":
                if audio_format is not None or chunk_size < 16:
                    raise PipelineError(f"WAV has a duplicate or short format chunk: {path}")
                format_data = source.read(16)
                audio_format, channels, sample_rate = struct.unpack_from("<HHI", format_data, 0)
                bits_per_sample = struct.unpack_from("<H", format_data, 14)[0]
            elif chunk_id == b"data":
                if data_bytes is not None:
                    raise PipelineError(f"WAV has multiple data chunks: {path}")
                data_bytes = chunk_size
            padded_end = chunk_end + (chunk_size & 1)
            if padded_end > file_size:
                raise PipelineError(f"WAV chunk padding exceeds the file: {path}")
            source.seek(padded_end)

    if audio_format != 1:
        raise PipelineError(f"WAV must use uncompressed PCM: {path}")
    if channels not in {1, 2}:
        raise PipelineError(f"WAV must be mono or stereo: {path}")
    if sample_rate is None or not 8_000 <= sample_rate <= 192_000:
        raise PipelineError(f"WAV sample rate is invalid: {path}")
    if bits_per_sample != 16:
        raise PipelineError(f"WAV must use PCM16: {path}")
    if data_bytes is None:
        raise PipelineError(f"WAV has no data chunk: {path}")
    frame_bytes = channels * 2
    if data_bytes % frame_bytes:
        raise PipelineError(f"WAV PCM data is not frame-aligned: {path}")
    frame_count = data_bytes // frame_bytes
    if frame_count < 32:
        raise PipelineError(f"WAV has fewer than 32 frames: {path}")

    return Pcm16WavInfo(sample_rate, channels, frame_count)


def extract_subsounds(
    car_id: str,
    subsounds: Sequence[int],
    *,
    lab_cars_dir: Path,
    work_root: Path,
    vgmstream: str,
    replace: bool,
    dry_run: bool,
) -> dict[str, Any]:
    car_root = lab_cars_dir.expanduser().resolve() / car_id
    bank = select_car_bank(car_root)
    work_directory = work_root.expanduser().resolve() / car_id
    graph_path = work_directory / "graph.json"
    try:
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PipelineError(f"run audit before extraction; graph is unavailable: {graph_path}") from error
    expected_hash = graph.get("bank", {}).get("sha256")
    actual_hash = _sha256(bank)
    if expected_hash != actual_hash:
        raise PipelineError("installed bank changed after graph audit; audit it again before extracting")

    by_subsound: dict[int, list[dict[str, Any]]] = {}
    for item in graph.get("samples", []):
        index = item.get("subsoundIndex")
        if isinstance(index, int):
            by_subsound.setdefault(index, []).append(item)
    missing = sorted(set(subsounds) - set(by_subsound))
    if missing:
        raise PipelineError(f"selected subsounds are not present in the graph: {missing}")

    output_directory = work_directory / "wav"
    records: list[dict[str, Any]] = []
    for index in sorted(set(subsounds)):
        usages = by_subsound[index]
        names = sorted(
            {
                str(usage.get("sample", {}).get("name", "sample"))
                for usage in usages
            }
        )
        sample_name = _safe_sample_name(names[0] if names else "sample")
        output_path = output_directory / f"subsound_{index:04d}_{sample_name}.wav"
        selector = index + 1
        command = [
            vgmstream,
            "-i",
            "-L",
            "-s",
            str(selector),
            "-o",
            str(output_path),
            str(bank),
        ]
        record: dict[str, Any] = {
            "zeroBasedSubsoundIndex": index,
            "vgmstreamSelector": selector,
            "sampleNames": names,
            "eventPaths": sorted(
                {
                    event_path
                    for usage in usages
                    for event_path in usage.get("eventPaths", [])
                }
            ),
            "path": str(output_path),
            "command": command,
        }
        if not dry_run:
            output_directory.mkdir(parents=True, exist_ok=True)
            if output_path.exists() and not replace:
                raise PipelineError(f"WAV already exists (use --replace): {output_path}")
            temporary = output_path.with_name(f".{output_path.name}.{uuid.uuid4().hex}.tmp.wav")
            temporary_command = command.copy()
            temporary_command[temporary_command.index(str(output_path))] = str(temporary)
            try:
                try:
                    result = subprocess.run(
                        temporary_command,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        check=False,
                    )
                except FileNotFoundError as error:
                    raise PipelineError(f"vgmstream-cli was not found: {vgmstream}") from error
                if result.returncode != 0:
                    detail = result.stderr.decode("utf-8", errors="replace").strip()
                    raise PipelineError(f"vgmstream failed for subsound {index}: {detail}")
                _validate_wav(temporary)
                os.replace(temporary, output_path)
            finally:
                temporary.unlink(missing_ok=True)
            record["bytes"] = output_path.stat().st_size
            record["sha256"] = _sha256(output_path)
        records.append(record)

    report = {
        "schema": "byd-ac-wav-extraction-v1",
        "carId": car_id,
        "dryRun": dry_run,
        "bank": str(bank),
        "bankSha256": actual_hash,
        "loopPolicy": "original-loop-metadata-preserved",
        "files": records,
    }
    if not dry_run:
        _atomic_write_json(work_directory / "selection.json", {"subsounds": sorted(set(subsounds))})
        _atomic_write_json(work_directory / "extraction.json", report)

    return report


def _validate_pack_asset_path(path: str) -> None:
    if (
        not path
        or len(path) > 240
        or path.startswith(("/", "\\"))
        or "\\" in path
        or "\x00" in path
    ):
        raise PipelineError(f"unsafe pack asset path: {path!r}")
    parts = path.split("/")
    if any(not part or part in {".", ".."} or ":" in part for part in parts):
        raise PipelineError(f"unsafe pack asset path: {path!r}")
    if parts[0] != "sample_engine" or not path.endswith(".wav"):
        raise PipelineError(f"pack WAV must be under sample_engine/ with a lowercase .wav suffix: {path!r}")


def _zip_info(path: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def _manifest_bytes(manifest: dict[str, Any]) -> bytes:
    return (
        json.dumps(manifest, indent=2, ensure_ascii=False, separators=(",", ": ")) + "\n"
    ).encode("utf-8")


def collect_pack_wavs(
    wav_root: Path,
    *,
    asset_root: str,
) -> tuple[tuple[Path, str, Pcm16WavInfo], ...]:
    wav_root = wav_root.expanduser().resolve()
    if not wav_root.is_dir():
        raise PipelineError(f"WAV root does not exist: {wav_root}")
    asset_root = asset_root.strip("/")
    if not asset_root:
        raise PipelineError("pack asset root cannot be empty")
    sources = sorted(
        (path for path in wav_root.rglob("*.wav") if path.is_file()),
        key=lambda path: path.relative_to(wav_root).as_posix().casefold(),
    )
    if not sources:
        raise PipelineError(f"WAV root contains no lowercase .wav files: {wav_root}")

    result: list[tuple[Path, str, Pcm16WavInfo]] = []
    casefold_paths: set[str] = set()
    for source in sources:
        if source.is_symlink():
            raise PipelineError(f"pack source cannot be a symbolic link: {source}")
        relative = source.relative_to(wav_root).as_posix()
        asset_path = f"{asset_root}/{relative}"
        _validate_pack_asset_path(asset_path)
        if asset_path.casefold() in casefold_paths:
            raise PipelineError(f"case-insensitive duplicate pack asset path: {asset_path}")
        casefold_paths.add(asset_path.casefold())
        result.append((source, asset_path, inspect_pcm16_wav(source)))

    return tuple(result)


def build_bydpack(
    pack_id: str,
    pack_version: int,
    wav_root: Path,
    *,
    asset_root: str | None,
    output: Path | None,
    replace: bool,
    dry_run: bool,
) -> dict[str, Any]:
    if PACK_ID_PATTERN.fullmatch(pack_id) is None:
        raise PipelineError("pack id must match ^[a-z0-9][a-z0-9._-]{0,95}$")
    if not 1 <= pack_version <= 2_147_483_647:
        raise PipelineError("pack version must be between 1 and 2147483647")
    resolved_asset_root = (asset_root or f"sample_engine/{pack_id}").strip("/")
    wavs = collect_pack_wavs(wav_root, asset_root=resolved_asset_root)
    manifest_files = [
        {
            "path": pack_path,
            "sizeBytes": source.stat().st_size,
            "sha256": _sha256(source),
            "sampleRate": info.sample_rate,
            "channels": info.channels,
            "frameCount": info.frame_count,
        }
        for source, pack_path, info in wavs
    ]
    manifest_files.sort(key=lambda item: item["path"])
    manifest = {
        "schemaVersion": 1,
        "packId": pack_id,
        "packVersion": pack_version,
        "files": manifest_files,
    }
    manifest_data = _manifest_bytes(manifest)
    if len(manifest_data) > 128 * 1024:
        raise PipelineError("manifest.json exceeds the Android importer limit")
    output_path = (
        output.expanduser().resolve()
        if output is not None
        else (DEFAULT_WORK_ROOT / "packs" / f"{pack_id}-v{pack_version}.bydpack").resolve()
    )
    report: dict[str, Any] = {
        "schema": "byd-audio-pack-build-v1",
        "dryRun": dry_run,
        "output": str(output_path),
        "zipMemberOrder": ["manifest.json", *(item["path"] for item in manifest_files)],
        "zipTimestamp": "1980-01-01T00:00:00",
        "manifest": manifest,
        "manifestSha256": hashlib.sha256(manifest_data).hexdigest(),
    }
    if dry_run:
        return report
    if output_path.exists() and not replace:
        raise PipelineError(f"pack already exists (use --replace): {output_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_name(f".{output_path.name}.{uuid.uuid4().hex}.tmp")
    sources_by_path = {pack_path: source for source, pack_path, _ in wavs}
    try:
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            allowZip64=True,
        ) as archive:
            archive.comment = b""
            archive.writestr(_zip_info("manifest.json"), manifest_data, compresslevel=9)
            for item in manifest_files:
                source = sources_by_path[item["path"]]
                with source.open("rb") as input_file, archive.open(
                    _zip_info(item["path"]), "w", force_zip64=True
                ) as output_file:
                    shutil.copyfileobj(input_file, output_file, length=READ_CHUNK_BYTES)
        with temporary.open("rb") as pack:
            os.fsync(pack.fileno())
        os.replace(temporary, output_path)
    finally:
        temporary.unlink(missing_ok=True)

    report["bytes"] = output_path.stat().st_size
    report["sha256"] = _sha256(output_path)
    return report


def _archive_arguments(arguments: argparse.Namespace) -> tuple[Path, ...]:
    if arguments.archive:
        return tuple(path.expanduser().resolve() for path in arguments.archive)

    return discover_archives(arguments.archives_dir)


def inventory_command(arguments: argparse.Namespace) -> dict[str, Any]:
    archives = _archive_arguments(arguments)
    archive_reports: list[dict[str, Any]] = []
    car_locations: dict[str, list[str]] = {}
    for archive in archives:
        members = list_archive_members(archive, bsdtar=arguments.bsdtar)
        plans = build_car_intake_plans(archive, members)
        archive_reports.append(
            {
                "archive": str(archive),
                "memberCount": len(members),
                "cars": [asdict(plan) for plan in plans],
            }
        )
        for plan in plans:
            car_locations.setdefault(plan.car_id, []).append(str(archive))
    collisions = {
        car_id: locations
        for car_id, locations in sorted(car_locations.items())
        if len(locations) > 1
    }
    return {
        "schema": "byd-ac-archive-inventory-v1",
        "archives": archive_reports,
        "summary": {
            "archiveCount": len(archives),
            "carCount": sum(len(report["cars"]) for report in archive_reports),
            "carIdCollisions": collisions,
        },
    }


def intake_command(arguments: argparse.Namespace) -> dict[str, Any]:
    archives = _archive_arguments(arguments)
    results: list[dict[str, Any]] = []
    seen_car_ids: dict[str, str] = {}
    planned: list[tuple[CarIntakePlan, tuple[ArchiveMember, ...]]] = []
    for archive in archives:
        members = list_archive_members(archive, bsdtar=arguments.bsdtar)
        plans = build_car_intake_plans(archive, members)
        for plan in plans:
            previous = seen_car_ids.get(plan.car_id)
            if previous is not None:
                raise PipelineError(
                    f"car id {plan.car_id!r} appears in both {previous} and {archive}"
                )
            seen_car_ids[plan.car_id] = str(archive)
            planned.append((plan, members))
    for plan, members in planned:
        results.append(
            install_car_plan(
                plan,
                members,
                lab_cars_dir=arguments.lab_cars_dir,
                work_root=arguments.work_root,
                preview_root=arguments.preview_root,
                bsdtar=arguments.bsdtar,
                replace=arguments.replace,
                dry_run=arguments.dry_run,
                member_max_bytes=arguments.max_member_bytes,
                car_max_bytes=arguments.max_car_bytes,
            )
        )
    return {
        "schema": "byd-ac-intake-run-v1",
        "dryRun": arguments.dry_run,
        "cars": results,
    }


def audit_command(arguments: argparse.Namespace) -> dict[str, Any]:
    return audit_car(
        arguments.car_id,
        lab_cars_dir=arguments.lab_cars_dir,
        work_root=arguments.work_root,
        dotnet=arguments.dotnet,
        audit_dll=arguments.audit_dll,
        dry_run=arguments.dry_run,
    )


def extract_command(arguments: argparse.Namespace) -> dict[str, Any]:
    return extract_subsounds(
        arguments.car_id,
        load_subsound_selection(arguments),
        lab_cars_dir=arguments.lab_cars_dir,
        work_root=arguments.work_root,
        vgmstream=arguments.vgmstream,
        replace=arguments.replace,
        dry_run=arguments.dry_run,
    )


def pack_command(arguments: argparse.Namespace) -> dict[str, Any]:
    return build_bydpack(
        arguments.pack_id,
        arguments.pack_version,
        arguments.wav_root,
        asset_root=arguments.asset_root,
        output=arguments.output,
        replace=arguments.replace,
        dry_run=arguments.dry_run,
    )


def _add_archive_source(parser: argparse.ArgumentParser) -> None:
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--archive", type=Path, action="append", help="archive to inspect; repeatable")
    source.add_argument("--archives-dir", type=Path, help="inspect every supported archive in this directory")
    parser.add_argument("--bsdtar", default="bsdtar")


def _add_common_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--report", type=Path, help="also atomically write the JSON report here")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    inventory = subparsers.add_parser("inventory", help="discover car roots and selected files")
    _add_archive_source(inventory)
    _add_common_output(inventory)
    inventory.set_defaults(handler=inventory_command)

    intake = subparsers.add_parser("intake", help="atomically install minimal car roots in Audio Lab")
    _add_archive_source(intake)
    intake.add_argument("--lab-cars-dir", type=Path, default=DEFAULT_LAB_CARS_DIR)
    intake.add_argument("--work-root", type=Path, default=DEFAULT_WORK_ROOT)
    intake.add_argument(
        "--preview-root",
        type=Path,
        default=DEFAULT_WORK_ROOT / "previews",
        help="generated preview images plus JSON/CSV mapping",
    )
    intake.add_argument("--replace", action="store_true")
    intake.add_argument("--dry-run", action="store_true")
    intake.add_argument("--max-member-bytes", type=int, default=DEFAULT_MEMBER_MAX_BYTES)
    intake.add_argument("--max-car-bytes", type=int, default=DEFAULT_CAR_MAX_BYTES)
    _add_common_output(intake)
    intake.set_defaults(handler=intake_command)

    audit = subparsers.add_parser("audit", help="run the compiled FMOD graph audit and map GUID paths")
    audit.add_argument("--car-id", required=True)
    audit.add_argument("--lab-cars-dir", type=Path, default=DEFAULT_LAB_CARS_DIR)
    audit.add_argument("--work-root", type=Path, default=DEFAULT_WORK_ROOT)
    audit.add_argument("--dotnet", default="dotnet")
    audit.add_argument("--audit-dll", type=Path, default=DEFAULT_AUDIT_DLL)
    audit.add_argument("--dry-run", action="store_true")
    _add_common_output(audit)
    audit.set_defaults(handler=audit_command)

    extract = subparsers.add_parser("extract", help="extract explicitly selected zero-based subsounds")
    extract.add_argument("--car-id", required=True)
    selection = extract.add_mutually_exclusive_group(required=True)
    selection.add_argument("--subsounds", help="comma-separated zero-based graph subsound indices")
    selection.add_argument("--selection-file", type=Path, help="JSON list or {subsounds: [...]} object")
    extract.add_argument("--lab-cars-dir", type=Path, default=DEFAULT_LAB_CARS_DIR)
    extract.add_argument("--work-root", type=Path, default=DEFAULT_WORK_ROOT)
    extract.add_argument("--vgmstream", default="vgmstream-cli")
    extract.add_argument("--replace", action="store_true")
    extract.add_argument("--dry-run", action="store_true")
    _add_common_output(extract)
    extract.set_defaults(handler=extract_command)

    pack = subparsers.add_parser("pack", help="build a deterministic Android .bydpack from PCM16 WAVs")
    pack.add_argument("--pack-id", required=True)
    pack.add_argument("--pack-version", type=int, required=True)
    pack.add_argument("--wav-root", type=Path, required=True)
    pack.add_argument(
        "--asset-root",
        help="manifest destination prefix; defaults to sample_engine/<pack-id>",
    )
    pack.add_argument("--output", type=Path)
    pack.add_argument("--replace", action="store_true")
    pack.add_argument("--dry-run", action="store_true")
    _add_common_output(pack)
    pack.set_defaults(handler=pack_command)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    if hasattr(arguments, "max_member_bytes") and arguments.max_member_bytes <= 0:
        parser.error("--max-member-bytes must be positive")
    if hasattr(arguments, "max_car_bytes") and arguments.max_car_bytes <= 0:
        parser.error("--max-car-bytes must be positive")
    try:
        report = arguments.handler(arguments)
        if arguments.report:
            _atomic_write_json(arguments.report.expanduser().resolve(), report)
        json.dump(report, sys.stdout, indent=2, sort_keys=True, ensure_ascii=False)
        sys.stdout.write("\n")
        return 0
    except PipelineError as error:
        json.dump({"error": str(error), "command": arguments.command}, sys.stderr, indent=2)
        sys.stderr.write("\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
