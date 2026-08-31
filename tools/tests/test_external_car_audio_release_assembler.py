from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.car_catalog import build_car_catalog_packs as catalog
from tools.profile_generation import assemble_external_car_audio_release as release
from tools.tests import test_car_catalog_pack_builder as catalog_tests


def _physics() -> dict[str, object]:
    return {
        "minimumRpm": 0.0,
        "maximumRpm": 7_500.0,
        "idleRpm": 900.0,
        "redlineRpm": 7_000.0,
        "limiterRpm": 7_000.0,
        "upshiftRpm": 6_800.0,
        "gearRatios": [3.0, 2.0, 1.0],
        "soundFinalDriveRatio": 3.5,
        "soundDrivenWheelRadiusMeters": 0.35,
        "drivetrainSpeedControl": {
            "parameterName": "drivetrain_speed",
            "unit": "drivenWheelRadiansPerSecond",
            "formula": "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
            "signed": True,
        },
        "turbos": [],
        "turboBoostNormalization": {
            "kind": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
            "divisor": 0.0,
            "minimum": 0.0,
            "maximum": 1.0,
        },
        "backfire": {
            "maximumGas": 0.3,
            "minimumRpm": 3_500.0,
            "maximumRpm": 15_000.0,
            "triggerGas": 0.8,
            "minimumIntentThrottle": 0.4,
            "minimumIntentSeconds": 1.0,
        },
        "limiterFrequencyHz": 0.0,
        "upshiftDurationSeconds": 0.08,
        "downshiftDurationSeconds": 0.12,
    }


def _write_complete_fixture(root: Path) -> tuple[str, str, list[str], Path]:
    helper = catalog_tests.ReleaseFamilyTests(
        methodName="test_release_family_validates_every_evidence_chain"
    )
    runtime_id, bank_sha, car_ids = helper._write_fixture(root)
    family_plan = json.loads(
        (root / "families" / runtime_id / "plan.json").read_text(encoding="utf-8")
    )
    oracle = json.loads(
        (root / "families" / runtime_id / "oracle-status.json").read_text(encoding="utf-8")
    )
    initial_plan_sha = oracle["initialPlanSha256"]
    bank_family_id = "shared_bank_family"
    cars = [
        {
            "id": f"ac_{car_id}",
            "sourceCarId": car_id,
            "displayName": car_id.replace("_", " ").title(),
            "bankFamilyId": bank_family_id,
            "bankSha256": bank_sha,
            "previewAssetName": f"car_previews/{car_id}.jpg",
            "physics": _physics(),
            "specifications": {"assettoCorsaCarId": car_id},
        }
        for car_id in car_ids
    ]
    source = {
        "schema": catalog.SOURCE_SCHEMA,
        "catalogVersion": catalog.CATALOG_VERSION,
        "inventorySha256": "f" * 64,
        "auditSha256": "0" * 64,
        "cars": cars,
        "families": [
            {
                "bankFamilyId": bank_family_id,
                "bankSha256": bank_sha,
                "representativeCarId": car_ids[0],
                "memberCarIds": car_ids,
                "requiredRetainedSourceGuidCount": 1,
            }
        ],
    }
    source_path = root / "source.json"
    catalog._write_json(source_path, source)
    for car_id in car_ids:
        conservation_path = root / "cars" / car_id / "source-conservation-report.json"
        conservation = json.loads(conservation_path.read_text(encoding="utf-8"))
        conservation["sourceConservationAudit"].update(
            {
                "coreReachableSourceGuids": ["source-shift"],
                "emittedNrtSourceGuids": ["source-shift"],
            }
        )
        catalog._write_json(conservation_path, conservation)
    audio_signature = "9" * 64
    rows = []
    for car in cars:
        car_id = car["sourceCarId"]
        catalog_input_path = root / "cars" / car_id / "catalog-input.json"
        catalog._write_json(
            catalog_input_path,
            {
                "schema": release.CATALOG_INPUT_SCHEMA,
                "carId": car_id,
                "audioProgramFamilyId": runtime_id,
                "missingProfileFields": [],
                "packRequirement": {
                    "id": runtime_id,
                    "assetDirectory": runtime_id,
                    "atlasPlanSha256": initial_plan_sha,
                    "bankSha256": bank_sha,
                },
                "profile": {
                    "displayName": car["displayName"],
                    "physics": {
                        "source": "assettoCorsaStagedCarData",
                        "requiresIndependentProfileCompilation": True,
                    },
                    "preview": {
                        "carId": car_id,
                        "path": f"/fixture/{car_id}/preview.jpg",
                        "source": "ui/preview.jpg",
                        "bytes": 1_024,
                        "sha256": "8" * 64,
                    },
                },
            },
        )
        rows.append(
            {
                "carId": car_id,
                "audioProgramFamilyId": runtime_id,
                "catalogInput": str(catalog_input_path),
                "releaseStatus": "PASS",
            }
        )
    batch = {
        "schema": catalog.BATCH_SCHEMA,
        "releaseStatus": "PASS",
        "carCount": len(car_ids),
        "familyCount": 1,
        "deduplicatedCarCount": len(car_ids) - 1,
        "cars": rows,
        "families": [
            {
                "id": runtime_id,
                "carIds": car_ids,
                "planSha256": initial_plan_sha,
                "audioSignatureSha256": audio_signature,
                "aliasProofs": [
                    {
                        "carId": car_id,
                        "bankSha256": bank_sha,
                        "audioSignatureSha256": audio_signature,
                    }
                    for car_id in car_ids
                ],
            }
        ],
        "familyResults": [
            {"familyId": runtime_id, "status": "RENDERED_AND_PACKED"}
        ],
    }
    catalog._write_json(root / "catalog.json", batch)
    # Assert fixture construction did not accidentally lose the final adaptive plan.
    assert family_plan["planSha256"] == oracle["finalPlanSha256"]

    return runtime_id, bank_sha, car_ids, source_path


def _tree_hashes(directory: Path) -> dict[str, str]:
    return {
        str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(directory.rglob("*"))
        if path.is_file()
    }


class ExternalCarAudioReleaseAssemblerTests(unittest.TestCase):
    def _assemble(self, root: Path) -> tuple[dict[str, object], Path, Path, Path]:
        _runtime_id, _bank_sha, _car_ids, source_path = _write_complete_fixture(root / "atlas")
        usb = root / "usb"
        android = root / "android" / "car_catalog"
        report = root / "release-report.json"
        with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
            catalog, "EXPECTED_BANK_FAMILIES", 1
        ):
            result = release.assemble_external_release(
                source_catalog_path=source_path,
                atlas_root=root / "atlas",
                usb_output_directory=usb,
                android_catalog_directory=android,
                report_output_path=report,
                pack_version=7,
            )

        return result, usb, android, report

    def test_valid_release_is_deterministic_and_rerunnable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, usb, android, report = self._assemble(root)
            first_usb = _tree_hashes(usb)
            first_android = _tree_hashes(android)
            first_report = report.read_bytes()

            with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
                catalog, "EXPECTED_BANK_FAMILIES", 1
            ):
                second = release.assemble_external_release(
                    source_catalog_path=root / "atlas" / "source.json",
                    atlas_root=root / "atlas",
                    usb_output_directory=usb,
                    android_catalog_directory=android,
                    report_output_path=report,
                    pack_version=7,
                )

            self.assertEqual(result, second)
            self.assertEqual(first_usb, _tree_hashes(usb))
            self.assertEqual(first_android, _tree_hashes(android))
            self.assertEqual(first_report, report.read_bytes())
            inventory = json.loads(
                (usb / release.USB_INVENTORY_FILE_NAME).read_text(encoding="utf-8")
            )
            self.assertEqual(inventory["status"], "PASS")
            self.assertEqual(inventory["carCount"], 2)
            self.assertEqual(inventory["familyCount"], 1)
            self.assertEqual(inventory["packCount"], 1)
            self.assertEqual(
                set(first_usb),
                {
                    release.USB_INVENTORY_FILE_NAME,
                    release.USB_CHECKSUM_FILE_NAME,
                    inventory["packs"][0]["sourceFileName"],
                },
            )
            root_catalog = json.loads(
                (android / release.ANDROID_ROOT_CATALOG_FILE_NAME).read_text(encoding="utf-8")
            )
            descriptor = root_catalog["families"][0]
            runtime_asset = android / descriptor["runtimeAssetName"]
            self.assertEqual(runtime_asset.stat().st_size, descriptor["runtimeBytes"])
            self.assertEqual(catalog.sha256_file(runtime_asset), descriptor["runtimeSha256"])
            with zipfile.ZipFile(usb / inventory["packs"][0]["sourceFileName"]) as archive:
                manifest = archive.read("manifest.json")
                self.assertEqual(
                    hashlib.sha256(manifest).hexdigest(),
                    descriptor["packRequirement"]["manifestSha256"],
                )

    def test_blocked_runtime_preserves_previous_output_trees(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, _bank_sha, _car_ids, source_path = _write_complete_fixture(root / "atlas")
            runtime_path = root / "atlas" / "families" / runtime_id / "runtime-index.json"
            runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
            runtime["draftBlocked"] = True
            catalog._write_json(runtime_path, runtime)
            usb = root / "usb"
            android = root / "android"
            usb.mkdir()
            android.mkdir()
            (usb / "keep.txt").write_text("old usb", encoding="utf-8")
            (android / "keep.txt").write_text("old android", encoding="utf-8")

            with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
                catalog, "EXPECTED_BANK_FAMILIES", 1
            ), self.assertRaisesRegex(release.ExternalAudioReleaseError, "BLOCKED|blocked"):
                release.assemble_external_release(
                    source_catalog_path=source_path,
                    atlas_root=root / "atlas",
                    usb_output_directory=usb,
                    android_catalog_directory=android,
                    report_output_path=root / "report.json",
                    pack_version=1,
                )

            self.assertEqual((usb / "keep.txt").read_text(encoding="utf-8"), "old usb")
            self.assertEqual((android / "keep.txt").read_text(encoding="utf-8"), "old android")

    def test_catalog_input_mismatch_and_orphan_car_are_rejected(self) -> None:
        cases = ("mismatch", "orphan")
        for case in cases:
            with self.subTest(case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _runtime_id, _bank_sha, car_ids, source_path = _write_complete_fixture(root / "atlas")
                if case == "mismatch":
                    path = root / "atlas" / "cars" / car_ids[0] / "catalog-input.json"
                    value = json.loads(path.read_text(encoding="utf-8"))
                    value["packRequirement"]["bankSha256"] = "7" * 64
                    catalog._write_json(path, value)
                    expected = "pack mapping differs"
                else:
                    orphan = root / "atlas" / "cars" / "orphan"
                    orphan.mkdir()
                    catalog._write_json(orphan / "catalog-input.json", {})
                    expected = "orphan car directories"

                with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
                    catalog, "EXPECTED_BANK_FAMILIES", 1
                ), self.assertRaisesRegex(release.ExternalAudioReleaseError, expected):
                    release.assemble_external_release(
                        source_catalog_path=source_path,
                        atlas_root=root / "atlas",
                        usb_output_directory=root / "usb",
                        android_catalog_directory=root / "android",
                        report_output_path=root / "report.json",
                        pack_version=1,
                    )
                self.assertFalse((root / "usb").exists())
                self.assertFalse((root / "android").exists())

    def test_runtime_attestation_mismatch_is_rejected_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, _bank_sha, _car_ids, source_path = _write_complete_fixture(root / "atlas")
            report_path = root / "atlas" / "families" / runtime_id / "pack-report.json"
            pack_report = json.loads(report_path.read_text(encoding="utf-8"))
            pack_report["runtimeIndex"]["canonicalJsonNewlineSha256"] = "1" * 64
            catalog._write_json(report_path, pack_report)

            with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
                catalog, "EXPECTED_BANK_FAMILIES", 1
            ), self.assertRaisesRegex(catalog.CatalogBuildError, "exact final runtime index"):
                release.assemble_external_release(
                    source_catalog_path=source_path,
                    atlas_root=root / "atlas",
                    usb_output_directory=root / "usb",
                    android_catalog_directory=root / "android",
                    report_output_path=root / "report.json",
                    pack_version=1,
                )

            self.assertFalse((root / "usb").exists())
            self.assertFalse((root / "android").exists())

    def test_missing_retained_source_guid_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _runtime_id, _bank_sha, car_ids, source_path = _write_complete_fixture(root / "atlas")
            conservation_path = (
                root / "atlas" / "cars" / car_ids[0] / "source-conservation-report.json"
            )
            conservation = json.loads(conservation_path.read_text(encoding="utf-8"))
            conservation["sourceConservationAudit"]["emittedNrtSourceGuids"] = []
            conservation["sourceConservationAudit"]["exactGuidSetEquality"] = False
            catalog._write_json(conservation_path, conservation)

            with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(
                catalog, "EXPECTED_BANK_FAMILIES", 1
            ), self.assertRaisesRegex(release.ExternalAudioReleaseError, "GUID set"):
                release.assemble_external_release(
                    source_catalog_path=source_path,
                    atlas_root=root / "atlas",
                    usb_output_directory=root / "usb",
                    android_catalog_directory=root / "android",
                    report_output_path=root / "report.json",
                    pack_version=1,
                )

            self.assertFalse((root / "usb").exists())
            self.assertFalse((root / "android").exists())

    def test_transaction_rolls_back_first_tree_when_second_publish_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staged_usb = root / "staged-usb"
            staged_android = root / "staged-android"
            usb = root / "usb"
            android = root / "android"
            for directory, name, content in (
                (staged_usb, "new.txt", "new usb"),
                (staged_android, "new.txt", "new android"),
                (usb, "old.txt", "old usb"),
                (android, "old.txt", "old android"),
            ):
                directory.mkdir()
                (directory / name).write_text(content, encoding="utf-8")
            actual_replace = release.os.replace

            def fail_second_publish(source: object, destination: object) -> None:
                if Path(source) == staged_android and Path(destination) == android:
                    raise OSError("injected second-tree publish failure")
                actual_replace(source, destination)

            with patch.object(release.os, "replace", side_effect=fail_second_publish), self.assertRaisesRegex(
                OSError, "injected second-tree"
            ):
                release._publish_trees_atomically(
                    ((staged_usb, usb), (staged_android, android))
                )

            self.assertEqual(_tree_hashes(usb), {"old.txt": hashlib.sha256(b"old usb").hexdigest()})
            self.assertEqual(
                _tree_hashes(android),
                {"old.txt": hashlib.sha256(b"old android").hexdigest()},
            )

    def test_transaction_rolls_back_both_trees_when_release_report_write_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staged_usb = root / "staged-usb"
            staged_android = root / "staged-android"
            usb = root / "usb"
            android = root / "android"
            for directory, name, content in (
                (staged_usb, "new.txt", "new usb"),
                (staged_android, "new.txt", "new android"),
                (usb, "old.txt", "old usb"),
                (android, "old.txt", "old android"),
            ):
                directory.mkdir()
                (directory / name).write_text(content, encoding="utf-8")

            def fail_report() -> None:
                raise OSError("injected report failure")

            with self.assertRaisesRegex(OSError, "injected report failure"):
                release._publish_trees_atomically(
                    ((staged_usb, usb), (staged_android, android)),
                    finalize=fail_report,
                )

            self.assertEqual(
                _tree_hashes(usb),
                {"old.txt": hashlib.sha256(b"old usb").hexdigest()},
            )
            self.assertEqual(
                _tree_hashes(android),
                {"old.txt": hashlib.sha256(b"old android").hexdigest()},
            )


if __name__ == "__main__":
    unittest.main()
