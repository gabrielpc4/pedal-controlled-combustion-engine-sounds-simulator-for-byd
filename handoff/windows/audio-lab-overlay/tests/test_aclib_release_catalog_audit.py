from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from sim.aclib_catalog import canonical_json_bytes
from tools import audit_aclib_release_catalog as aggregate


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class CompleteReleaseCatalogAuditTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.packs = self.root / "packs"
        self.previews = self.root / "previews"
        self.packs.mkdir()
        self.previews.mkdir()

        preview_a = b"preview-a"
        preview_b = b"preview-b"
        (self.previews / "car_a.jpg").write_bytes(preview_a)
        (self.previews / "car_b.jpg").write_bytes(preview_b)
        self.family_a = "a" * 64
        self.family_b = "b" * 64
        self.catalog = {
            "catalogSha256": "c" * 64,
            "cars": [
                {
                    "id": "car_a",
                    "name": "Car A",
                    "familyId": self.family_a,
                    "previewPath": "previews/car_a.jpg",
                    "previewSha256": _sha(preview_a),
                },
                {
                    "id": "car_b",
                    "name": "Car B",
                    "familyId": self.family_b,
                    "previewPath": "previews/car_b.jpg",
                    "previewSha256": _sha(preview_b),
                },
            ],
            "soundFamilies": [
                {"id": self.family_a, "memberIds": ["car_a"]},
                {"id": self.family_b, "memberIds": ["car_b"]},
            ],
        }
        self.plan = {
            "catalogSha256": self.catalog["catalogSha256"],
            "audioFormat": {
                "codec": "FLAC",
                "compressionLevel": 8,
                "sampleRate": 48_000,
                "channels": 2,
                "bitsPerSample": 16,
            },
            "families": [
                {"familyId": self.family_a, "memberCarIds": ["car_a"]},
                {"familyId": self.family_b, "memberCarIds": ["car_b"]},
            ],
        }
        self.plan_hash = _sha(canonical_json_bytes(self.plan))
        self.manifests = {
            self.family_a: self._manifest(self.family_a, "car_a", "Car A"),
            self.family_b: self._manifest(self.family_b, "car_b", "Car B"),
        }
        for family_id in self.manifests:
            (self.packs / f"{family_id}.aclib").write_bytes(family_id.encode("ascii"))
        self.catalog_path = self.root / "catalog.json"
        self.plan_path = self.root / "plan.json"
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        self.plan_path.write_text(json.dumps(self.plan), encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _manifest(self, family_id: str, car_id: str, name: str) -> dict:
        preview_path = f"previews/{car_id}.jpg"
        return {
            "familyId": family_id,
            "memberCarIds": [car_id],
            "cars": [
                {
                    "id": car_id,
                    "name": name,
                    "previewPath": preview_path,
                    "engine": {"idleRpm": 900.0},
                }
            ],
            "tracks": [
                {
                    "id": "idle",
                    "role": "IDLE",
                    "path": "audio/idle.flac",
                    "frameCount": 4_800,
                    "channels": 2,
                    "peakDbfs": -12.0,
                    "gainDb": 0.0,
                    "rpmCurve": [[0.0, 1.0], [10_000.0, 1.0]],
                    "gainCurve": [[0.0, 1.0], [1.0, 1.0]],
                }
            ],
            "assets": [
                {
                    "path": preview_path,
                    "sha256": self.catalog["cars"][0 if car_id == "car_a" else 1][
                        "previewSha256"
                    ],
                }
            ],
            "provenance": {
                "catalogSha256": self.catalog["catalogSha256"],
                "capturePlanSha256": self.plan_hash,
                "familyAttenuationDb": -3.0,
                "defaultMixPeakDbfs": -3.1,
            },
        }

    def _run(self) -> dict:
        def fake_validate(path: Path, *, codec: object) -> dict:
            return copy.deepcopy(self.manifests[path.stem])

        with (
            mock.patch.object(aggregate, "EXPECTED_USABLE_CARS", 2),
            mock.patch.object(aggregate, "EXPECTED_SOUND_FAMILIES", 2),
            mock.patch.object(aggregate, "validate_catalog"),
            mock.patch.object(aggregate, "validate_release_capture_plan"),
            mock.patch.object(aggregate, "validate_aclib", side_effect=fake_validate),
            mock.patch.object(aggregate, "validate_release_manifest", side_effect=lambda value: value),
        ):
            return aggregate.audit_complete_release(
                catalog_path=self.catalog_path,
                capture_plan_path=self.plan_path,
                packs_directory=self.packs,
                previews_directory=self.previews,
                codec=object(),
                workers=1,
            )

    def test_complete_release_passes_and_records_decoded_proof(self) -> None:
        report = self._run()
        self.assertTrue(report["passed"])
        self.assertTrue(report["decodedPcmVerified"])
        self.assertEqual(report["counts"]["cars"], 2)
        self.assertEqual(report["counts"]["packs"], 2)
        self.assertEqual(report["counts"]["previews"], 2)
        self.assertEqual(report["counts"]["uniqueMedia"], 2)
        self.assertEqual(report["storage"]["maximumFamilyDecodedPcmBytes"], 19_200)
        self.assertEqual(
            report["storage"]["minimumDeviceSoftDecodedBudgetBytes"],
            32 * 1024 * 1024,
        )
        self.assertRegex(report["auditSha256"], r"^[0-9a-f]{64}$")

    def test_shared_physical_media_is_counted_once(self) -> None:
        shared = copy.deepcopy(self.manifests[self.family_a]["tracks"][0])
        shared["id"] = "idle_second_semantic_role"
        self.manifests[self.family_a]["tracks"].append(shared)
        report = self._run()
        family = next(item for item in report["packs"] if item["familyId"] == self.family_a)
        self.assertEqual(family["tracks"], 2)
        self.assertEqual(family["uniqueMedia"], 1)
        self.assertEqual(family["uniqueDecodedPcmBytes"], 19_200)
        self.assertEqual(family["semanticDecodedPcmBytes"], 38_400)
        self.assertEqual(family["deduplicatedDecodedPcmBytes"], 19_200)
        self.assertEqual(report["counts"]["sharedMediaReferences"], 1)

    def test_mixed_capture_plan_hash_fails(self) -> None:
        self.manifests[self.family_b]["provenance"]["capturePlanSha256"] = "d" * 64
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "another capture plan"):
            self._run()

    def test_family_above_minimum_device_decoded_budget_fails(self) -> None:
        # Stereo PCM16 is four decoded bytes per frame.
        self.manifests[self.family_a]["tracks"][0]["frameCount"] = (
            aggregate.MINIMUM_DEVICE_SOFT_DECODED_BUDGET_BYTES // 4 + 1
        )
        with self.assertRaisesRegex(
            aggregate.CompleteReleaseAuditError,
            "exceeding the minimum-device soft budget",
        ):
            self._run()

    def test_missing_pack_fails_before_validation(self) -> None:
        (self.packs / f"{self.family_b}.aclib").unlink()
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "pack closure mismatch"):
            self._run()

    def test_unexpected_pack_fails_before_validation(self) -> None:
        (self.packs / f"{'d' * 64}.aclib").write_bytes(b"unexpected")
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "unexpected="):
            self._run()

    def test_inaudible_idle_at_idle_rpm_fails(self) -> None:
        self.manifests[self.family_a]["tracks"][0]["rpmCurve"] = [
            [0.0, 0.0],
            [10_000.0, 0.0],
        ]
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "no audible authored IDLE"):
            self._run()

    def test_external_preview_hash_fails(self) -> None:
        (self.previews / "car_b.jpg").write_bytes(b"changed")
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "preview hash differs"):
            self._run()

    def test_pack_preview_hash_must_match_catalog(self) -> None:
        self.manifests[self.family_a]["assets"][0]["sha256"] = "e" * 64
        with self.assertRaisesRegex(aggregate.CompleteReleaseAuditError, "pack preview hash differs"):
            self._run()


if __name__ == "__main__":
    unittest.main()
