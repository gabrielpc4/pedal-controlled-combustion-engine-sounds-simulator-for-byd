"""Fail-closed aggregate audit for the complete private AC release library.

The per-pack validator deliberately knows nothing about the other 152 packs.
This tool adds the release-wide invariants: one complete official catalog, one
immutable capture-plan hash, exact car/family closure, physical preview files,
and bit-exact FLAC decoding for every unique media payload.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from sim.aclib import sha256_file, validate_aclib, validate_release_manifest
from sim.aclib_catalog import canonical_json_bytes, validate_catalog
from sim.flac_codec import PinnedFlacCodec, bootstrap_pinned_flac
from tools.aclib_release import validate_release_capture_plan


AUDIT_SCHEMA = "aclib-complete-release-audit-v1"
EXPECTED_USABLE_CARS = 178
EXPECTED_SOUND_FAMILIES = 153
MINIMUM_AUDIBLE_DBFS_EXCLUSIVE = -96.0
DEFAULT_WORKERS = 4
# The runtime policy is min(64 MiB, memoryClass / 8). A 256 MiB Android
# device therefore has the smallest supported soft budget used by acceptance.
MINIMUM_DEVICE_SOFT_DECODED_BUDGET_BYTES = 32 * 1024 * 1024


class CompleteReleaseAuditError(ValueError):
    """Raised when catalog-wide release closure is not exact."""


def _compact_names(values: list[str], limit: int = 8) -> str:
    shown = values[:limit]
    suffix = "" if len(values) <= limit else f", ... (+{len(values) - limit})"
    return f"count={len(values)} [{', '.join(shown)}{suffix}]"


def _read_json(path: Path) -> dict[str, Any]:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise CompleteReleaseAuditError(f"{path} has duplicate JSON key {key!r}")
            result[key] = value
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CompleteReleaseAuditError(f"cannot read {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise CompleteReleaseAuditError(f"{path} must contain a JSON object")
    return value


def _curve_value(points: list[list[float]], x: float) -> float:
    if not points:
        return 1.0
    if x <= float(points[0][0]):
        return float(points[0][1])
    for left, right in zip(points, points[1:]):
        if x <= float(right[0]):
            width = float(right[0]) - float(left[0])
            if width <= 0.0:
                return float(right[1])
            fraction = (x - float(left[0])) / width
            return float(left[1]) + (
                float(right[1]) - float(left[1])
            ) * fraction
    return float(points[-1][1])


def _idle_bound_dbfs(manifest: dict[str, Any], idle_rpm: float) -> float:
    """Conservative peak-sum audibility bound for authored IDLE at zero pedal."""

    family_gain_db = float(manifest["provenance"]["familyAttenuationDb"])
    amplitude = 0.0
    for track in manifest["tracks"]:
        if track["role"] != "IDLE":
            continue
        rpm_gain = max(0.0, _curve_value(track["rpmCurve"], idle_rpm))
        pedal_gain = max(0.0, _curve_value(track["gainCurve"], 0.0))
        media_peak = 10.0 ** (
            (
                float(track["peakDbfs"])
                + float(track["gainDb"])
                + family_gain_db
            )
            / 20.0
        )
        amplitude += media_peak * rpm_gain * pedal_gain
    if amplitude <= 0.0:
        return -math.inf
    return 20.0 * math.log10(amplitude)


def _unique_decoded_bytes(manifest: dict[str, Any]) -> int:
    media_by_path: dict[str, int] = {}
    for track in manifest["tracks"]:
        decoded = int(track["frameCount"]) * int(track["channels"]) * 2
        previous = media_by_path.setdefault(track["path"], decoded)
        if previous != decoded:
            raise CompleteReleaseAuditError(
                f"family {manifest['familyId']} has inconsistent shared-media sizes"
            )
    return sum(media_by_path.values())


def _validate_pack_job(
    item: tuple[str, Path],
    codec: PinnedFlacCodec,
) -> tuple[str, Path, dict[str, Any]]:
    expected_family_id, pack_path = item
    try:
        manifest = validate_release_manifest(validate_aclib(pack_path, codec=codec))
    except Exception as exc:
        raise CompleteReleaseAuditError(
            f"pack {pack_path.name} failed decoded release validation: {exc}"
        ) from exc
    if manifest["familyId"] != expected_family_id:
        raise CompleteReleaseAuditError(
            f"pack {pack_path.name} contains family {manifest['familyId']}"
        )
    return expected_family_id, pack_path, manifest


def audit_complete_release(
    *,
    catalog_path: Path,
    capture_plan_path: Path,
    packs_directory: Path,
    previews_directory: Path,
    codec: PinnedFlacCodec,
    workers: int = DEFAULT_WORKERS,
) -> dict[str, Any]:
    """Validate and summarize one complete, immutable 178/153 release."""

    catalog_path = Path(catalog_path).resolve(strict=True)
    capture_plan_path = Path(capture_plan_path).resolve(strict=True)
    packs_directory = Path(packs_directory).resolve(strict=True)
    previews_directory = Path(previews_directory).resolve(strict=True)
    if not packs_directory.is_dir() or not previews_directory.is_dir():
        raise CompleteReleaseAuditError("packs and previews inputs must be directories")
    if isinstance(workers, bool) or workers < 1 or workers > 16:
        raise CompleteReleaseAuditError("workers must be in 1..16")

    catalog = _read_json(catalog_path)
    validate_catalog(catalog, require_complete=True)
    if (
        len(catalog["cars"]) != EXPECTED_USABLE_CARS
        or len(catalog["soundFamilies"]) != EXPECTED_SOUND_FAMILIES
    ):
        raise CompleteReleaseAuditError("catalog is not exactly 178 cars / 153 families")

    capture_plan = _read_json(capture_plan_path)
    validate_release_capture_plan(capture_plan, catalog, require_renderable=True)
    capture_plan_sha256 = hashlib.sha256(
        canonical_json_bytes(capture_plan)
    ).hexdigest()

    catalog_families = {family["id"]: family for family in catalog["soundFamilies"]}
    plan_families = {family["familyId"]: family for family in capture_plan["families"]}
    if len(plan_families) != len(capture_plan["families"]):
        raise CompleteReleaseAuditError("capture plan has duplicate familyId values")
    if set(plan_families) != set(catalog_families):
        raise CompleteReleaseAuditError("capture-plan families do not close over the catalog")
    for family_id, catalog_family in catalog_families.items():
        if sorted(plan_families[family_id]["memberCarIds"]) != catalog_family["memberIds"]:
            raise CompleteReleaseAuditError(
                f"capture-plan membership differs for family {family_id}"
            )

    expected_pack_names = {f"{family_id}.aclib" for family_id in catalog_families}
    actual_pack_paths = sorted(packs_directory.glob("*.aclib"), key=lambda path: path.name)
    actual_pack_names = {path.name for path in actual_pack_paths}
    if len(actual_pack_names) != len(actual_pack_paths):
        raise CompleteReleaseAuditError("pack filenames are not unique")
    missing_packs = sorted(expected_pack_names - actual_pack_names)
    unexpected_packs = sorted(actual_pack_names - expected_pack_names)
    if missing_packs or unexpected_packs:
        raise CompleteReleaseAuditError(
            "pack closure mismatch: "
            f"missing={_compact_names(missing_packs)} "
            f"unexpected={_compact_names(unexpected_packs)}"
        )

    jobs = [
        (path.stem, path)
        for path in actual_pack_paths
    ]
    if workers == 1:
        validated = [_validate_pack_job(job, codec) for job in jobs]
    else:
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="aclib-audit") as pool:
            validated = list(pool.map(lambda job: _validate_pack_job(job, codec), jobs))
    manifests = {family_id: (path, manifest) for family_id, path, manifest in validated}

    expected_preview_paths = {car["previewPath"] for car in catalog["cars"]}
    if None in expected_preview_paths or len(expected_preview_paths) != EXPECTED_USABLE_CARS:
        raise CompleteReleaseAuditError("catalog does not have one distinct preview per car")
    actual_preview_paths = {
        path.relative_to(previews_directory).as_posix()
        for path in previews_directory.rglob("*")
        if path.is_file()
    }
    # The directory itself is the contents of catalog's previews/ namespace.
    expected_preview_files = {
        Path(path).relative_to("previews").as_posix()
        for path in expected_preview_paths
    }
    if actual_preview_paths != expected_preview_files:
        missing_previews = sorted(expected_preview_files - actual_preview_paths)
        unexpected_previews = sorted(actual_preview_paths - expected_preview_files)
        raise CompleteReleaseAuditError(
            "preview closure mismatch: "
            f"missing={_compact_names(missing_previews)} "
            f"unexpected={_compact_names(unexpected_previews)}"
        )
    for car in catalog["cars"]:
        relative = Path(car["previewPath"]).relative_to("previews")
        preview = previews_directory / relative
        if sha256_file(preview) != car["previewSha256"]:
            raise CompleteReleaseAuditError(f"preview hash differs for {car['id']}")

    cars_by_id = {car["id"]: car for car in catalog["cars"]}
    seen_cars: set[str] = set()
    pack_reports: list[dict[str, Any]] = []
    minimum_idle_dbfs = math.inf
    maximum_decoded_bytes = 0
    maximum_default_mix_dbfs = -math.inf
    total_flac_bytes = 0
    total_unique_decoded_bytes = 0
    total_semantic_decoded_bytes = 0
    total_tracks = 0
    total_unique_media = 0

    for family_id in sorted(catalog_families):
        pack_path, manifest = manifests[family_id]
        expected_members = catalog_families[family_id]["memberIds"]
        if manifest["memberCarIds"] != expected_members:
            raise CompleteReleaseAuditError(f"pack membership differs for family {family_id}")
        manifest_car_ids = [car["id"] for car in manifest["cars"]]
        if sorted(manifest_car_ids) != expected_members or len(set(manifest_car_ids)) != len(manifest_car_ids):
            raise CompleteReleaseAuditError(f"pack car records differ for family {family_id}")
        overlap = seen_cars.intersection(expected_members)
        if overlap:
            raise CompleteReleaseAuditError(f"cars occur in multiple packs: {sorted(overlap)}")
        seen_cars.update(expected_members)
        if manifest["provenance"]["catalogSha256"] != catalog["catalogSha256"]:
            raise CompleteReleaseAuditError(f"pack {family_id} uses another catalog")
        if manifest["provenance"]["capturePlanSha256"] != capture_plan_sha256:
            raise CompleteReleaseAuditError(f"pack {family_id} uses another capture plan")

        assets_by_path = {asset["path"]: asset for asset in manifest["assets"]}
        idle_bounds: list[float] = []
        for car_record in manifest["cars"]:
            car_id = car_record["id"]
            catalog_car = cars_by_id[car_id]
            if car_record["name"] != catalog_car["name"]:
                raise CompleteReleaseAuditError(f"pack car name differs for {car_id}")
            if car_record["previewPath"] != catalog_car["previewPath"]:
                raise CompleteReleaseAuditError(f"pack preview path differs for {car_id}")
            asset = assets_by_path.get(car_record["previewPath"])
            if asset is None or asset["sha256"] != catalog_car["previewSha256"]:
                raise CompleteReleaseAuditError(f"pack preview hash differs for {car_id}")
            idle_dbfs = _idle_bound_dbfs(manifest, float(car_record["engine"]["idleRpm"]))
            if not idle_dbfs > MINIMUM_AUDIBLE_DBFS_EXCLUSIVE:
                raise CompleteReleaseAuditError(
                    f"family {family_id} has no audible authored IDLE for {car_id} "
                    f"at {car_record['engine']['idleRpm']} RPM ({idle_dbfs} dBFS)"
                )
            idle_bounds.append(idle_dbfs)
            minimum_idle_dbfs = min(minimum_idle_dbfs, idle_dbfs)

        unique_bytes = _unique_decoded_bytes(manifest)
        if unique_bytes > MINIMUM_DEVICE_SOFT_DECODED_BUDGET_BYTES:
            raise CompleteReleaseAuditError(
                f"family {family_id} needs {unique_bytes} decoded bytes, exceeding "
                f"the minimum-device soft budget of "
                f"{MINIMUM_DEVICE_SOFT_DECODED_BUDGET_BYTES} bytes"
            )
        semantic_bytes = sum(
            int(track["frameCount"]) * int(track["channels"]) * 2
            for track in manifest["tracks"]
        )
        unique_media = len({track["path"] for track in manifest["tracks"]})
        pack_bytes = pack_path.stat().st_size
        default_mix = float(manifest["provenance"]["defaultMixPeakDbfs"])
        maximum_decoded_bytes = max(maximum_decoded_bytes, unique_bytes)
        maximum_default_mix_dbfs = max(maximum_default_mix_dbfs, default_mix)
        total_flac_bytes += pack_bytes
        total_unique_decoded_bytes += unique_bytes
        total_semantic_decoded_bytes += semantic_bytes
        total_tracks += len(manifest["tracks"])
        total_unique_media += unique_media
        pack_reports.append(
            {
                "familyId": family_id,
                "packSha256": sha256_file(pack_path),
                "packBytes": pack_bytes,
                "memberCars": len(expected_members),
                "tracks": len(manifest["tracks"]),
                "uniqueMedia": unique_media,
                "uniqueDecodedPcmBytes": unique_bytes,
                "semanticDecodedPcmBytes": semantic_bytes,
                "deduplicatedDecodedPcmBytes": semantic_bytes - unique_bytes,
                "defaultMixPeakDbfs": default_mix,
                "minimumIdleBoundDbfs": min(idle_bounds),
            }
        )

    if seen_cars != set(cars_by_id):
        raise CompleteReleaseAuditError("pack car membership does not close over all 178 cars")

    report: dict[str, Any] = {
        "schema": AUDIT_SCHEMA,
        "passed": True,
        "catalogFileSha256": sha256_file(catalog_path),
        "catalogSha256": catalog["catalogSha256"],
        "capturePlanFileSha256": sha256_file(capture_plan_path),
        "capturePlanSha256": capture_plan_sha256,
        "decodedPcmVerified": True,
        "audioFormat": capture_plan["audioFormat"],
        "counts": {
            "cars": len(cars_by_id),
            "families": len(catalog_families),
            "packs": len(manifests),
            "previews": len(expected_preview_files),
            "tracks": total_tracks,
            "uniqueMedia": total_unique_media,
            "sharedMediaReferences": total_tracks - total_unique_media,
        },
        "storage": {
            "packBytes": total_flac_bytes,
            "sumUniqueDecodedPcmBytes": total_unique_decoded_bytes,
            "sumSemanticDecodedPcmBytes": total_semantic_decoded_bytes,
            "deduplicatedDecodedPcmBytes": (
                total_semantic_decoded_bytes - total_unique_decoded_bytes
            ),
            "maximumFamilyDecodedPcmBytes": maximum_decoded_bytes,
            "minimumDeviceSoftDecodedBudgetBytes": (
                MINIMUM_DEVICE_SOFT_DECODED_BUDGET_BYTES
            ),
        },
        "levels": {
            "maximumDefaultMixPeakDbfs": maximum_default_mix_dbfs,
            "minimumIdleBoundDbfs": minimum_idle_dbfs,
            "idleAudibilityFloorDbfsExclusive": MINIMUM_AUDIBLE_DBFS_EXCLUSIVE,
        },
        "packs": pack_reports,
    }
    report["auditSha256"] = hashlib.sha256(canonical_json_bytes(report)).hexdigest()
    return report


def _write_json_atomic(path: Path, value: object) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = canonical_json_bytes(value) + b"\n"
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(data)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--capture-plan", required=True)
    parser.add_argument("--packs", required=True, help="directory containing exactly 153 .aclib files")
    parser.add_argument("--previews", required=True, help="directory containing the 178 preview files")
    parser.add_argument("--tool-cache", default=str(Path(".aclib-local") / "tools"))
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--output", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        codec = PinnedFlacCodec(bootstrap_pinned_flac(Path(args.tool_cache)))
        report = audit_complete_release(
            catalog_path=Path(args.catalog),
            capture_plan_path=Path(args.capture_plan),
            packs_directory=Path(args.packs),
            previews_directory=Path(args.previews),
            codec=codec,
            workers=args.workers,
        )
        output = Path(args.output)
        _write_json_atomic(output, report)
        print(
            f"passed cars={report['counts']['cars']} families={report['counts']['families']} "
            f"packs={report['counts']['packs']} report={output.resolve()}"
        )
        return 0
    except Exception as exc:
        print(f"complete release audit failed: {type(exc).__name__}: {exc}", file=__import__("sys").stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
