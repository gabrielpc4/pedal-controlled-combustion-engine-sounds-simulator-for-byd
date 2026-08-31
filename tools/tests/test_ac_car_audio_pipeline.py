from __future__ import annotations

import unittest
import json
import tempfile
import wave
import zipfile
from pathlib import Path

from tools.ac_car_audio_pipeline import (
    ArchiveMember,
    PipelineError,
    archive_members_from_listing,
    build_bydpack,
    build_car_intake_plans,
    enrich_graph,
    inspect_pcm16_wav,
    normalize_archive_member,
    parse_graph_guids,
    parse_guids,
    parse_subsound_selection,
    reconcile_graph_with_runtime_oracle,
)


def members(*names: str) -> tuple[ArchiveMember, ...]:
    return archive_members_from_listing(names)


class ArchivePathTests(unittest.TestCase):
    def test_normalizes_leading_dot_without_changing_car_root(self) -> None:
        self.assertEqual(
            normalize_archive_member("./wrapper/content/cars/car_a/sfx/car_a.bank"),
            ("wrapper/content/cars/car_a/sfx/car_a.bank", False),
        )

    def test_rejects_parent_absolute_drive_backslash_and_glob_paths(self) -> None:
        unsafe = (
            "../escape.bank",
            "/absolute/car.bank",
            "C:/absolute/car.bank",
            "car\\sfx\\car.bank",
            "car/sfx/[car].bank",
        )
        for path in unsafe:
            with self.subTest(path=path), self.assertRaises(PipelineError):
                normalize_archive_member(path)

    def test_rejects_duplicate_file_members(self) -> None:
        with self.assertRaisesRegex(PipelineError, "duplicate file member"):
            archive_members_from_listing(("car/data.acd", "car/data.acd"))


class IntakeSelectionTests(unittest.TestCase):
    def test_selects_minimal_complete_root_and_excludes_old_or_alternate_banks(self) -> None:
        archive_members = members(
            "Bundle/content/cars/car_a/data.acd",
            "Bundle/content/cars/car_a/data/engine.ini",
            "Bundle/content/cars/car_a/sfx/car_a.bank",
            "Bundle/content/cars/car_a/sfx/car_a_alternate.bank",
            "Bundle/content/cars/car_a/sfx/GUIDs.txt",
            "Bundle/content/cars/car_a/sfx/GUIDs.txt_Alternate",
            "Bundle/content/cars/car_a/sfx/sfx_old/car_a.bank",
            "Bundle/content/cars/car_a/ui/ui_car.json",
            "Bundle/content/cars/car_a/ui/badge.png",
            "Bundle/content/cars/car_a/ui/preview.jpg",
            "Bundle/content/cars/car_a/skins/z_skin/preview.jpg",
            "Bundle/content/cars/car_a/skins/a_skin/preview.jpg",
            "Bundle/content/cars/car_a/skins/a_skin/body.dds",
        )

        (plan,) = build_car_intake_plans(Path("bundle.zip"), archive_members)

        self.assertEqual(plan.car_id, "car_a")
        self.assertEqual(plan.archive_root, "Bundle/content/cars/car_a")
        self.assertEqual(plan.active_bank_member, "Bundle/content/cars/car_a/sfx/car_a.bank")
        self.assertIn("Bundle/content/cars/car_a/data.acd", plan.selected_members)
        self.assertIn("Bundle/content/cars/car_a/data/engine.ini", plan.selected_members)
        self.assertIn("Bundle/content/cars/car_a/ui/ui_car.json", plan.selected_members)
        self.assertIn("Bundle/content/cars/car_a/skins/a_skin/preview.jpg", plan.selected_members)
        self.assertNotIn("Bundle/content/cars/car_a/skins/z_skin/preview.jpg", plan.selected_members)
        self.assertNotIn("Bundle/content/cars/car_a/skins/a_skin/body.dds", plan.selected_members)
        self.assertEqual(
            plan.preferred_preview_member,
            "Bundle/content/cars/car_a/ui/preview.jpg",
        )
        self.assertCountEqual(
            plan.excluded_bank_members,
            (
                "Bundle/content/cars/car_a/sfx/car_a_alternate.bank",
                "Bundle/content/cars/car_a/sfx/sfx_old/car_a.bank",
            ),
        )

    def test_accepts_a_single_nonmatching_direct_bank_name(self) -> None:
        archive_members = members(
            "car_a/data/engine.ini",
            "car_a/sfx/donor_sound.bank",
            "car_a/sfx/GUIDs.txt",
            "car_a/ui/ui_car.json",
            "car_a/ui/badge.png",
        )

        (plan,) = build_car_intake_plans(Path("bundle.7z"), archive_members)

        self.assertEqual(plan.active_bank_member, "car_a/sfx/donor_sound.bank")
        self.assertFalse(plan.has_data_acd)
        self.assertEqual(plan.loose_data_files, 1)

    def test_refuses_to_guess_between_multiple_nonmatching_banks(self) -> None:
        archive_members = members(
            "car_a/data.acd",
            "car_a/sfx/first.bank",
            "car_a/sfx/second.bank",
            "car_a/sfx/GUIDs.txt",
            "car_a/ui/ui_car.json",
            "car_a/ui/badge.png",
        )

        with self.assertRaisesRegex(PipelineError, "cannot choose an active bank"):
            build_car_intake_plans(Path("bundle.rar"), archive_members)

    def test_discovers_two_independent_car_roots_in_one_archive(self) -> None:
        archive_members = members(
            "content/cars/car_a/data.acd",
            "content/cars/car_a/sfx/car_a.bank",
            "content/cars/car_a/sfx/GUIDs.txt",
            "content/cars/car_a/ui/ui_car.json",
            "content/cars/car_a/ui/badge.png",
            "content/cars/car_b/data.acd",
            "content/cars/car_b/sfx/car_b.bank",
            "content/cars/car_b/sfx/GUIDs.txt",
            "content/cars/car_b/ui/ui_car.json",
            "content/cars/car_b/ui/badge.png",
        )

        plans = build_car_intake_plans(Path("pack.zip"), archive_members)

        self.assertEqual([plan.car_id for plan in plans], ["car_a", "car_b"])

    def test_prefers_first_skin_preview_over_badge_when_ui_preview_is_absent(self) -> None:
        archive_members = members(
            "car_a/data.acd",
            "car_a/sfx/car_a.bank",
            "car_a/sfx/GUIDs.txt",
            "car_a/ui/ui_car.json",
            "car_a/ui/badge.png",
            "car_a/skins/z/preview.jpg",
            "car_a/skins/a/preview.png",
        )

        (plan,) = build_car_intake_plans(Path("bundle.zip"), archive_members)

        self.assertEqual(plan.preferred_preview_member, "car_a/skins/a/preview.png")


class GraphAndSelectionTests(unittest.TestCase):
    def test_graph_guid_parser_omits_irrelevant_donor_collisions(self) -> None:
        graph = {
            "bank": {"bankGuid": "11111111-1111-1111-1111-111111111111"},
            "events": [{"guid": "22222222-2222-2222-2222-222222222222"}],
        }
        mappings, evidence = parse_graph_guids(
            "{11111111-1111-1111-1111-111111111111} bank:/car_a\n"
            "{22222222-2222-2222-2222-222222222222} event:/cars/car_a/engine_int\n"
            "{33333333-3333-3333-3333-333333333333} bank:/donor_a\n"
            "{33333333-3333-3333-3333-333333333333} bank:/donor_b\n",
            graph,
            car_id="car_a",
        )

        self.assertNotIn("33333333-3333-3333-3333-333333333333", mappings)
        self.assertEqual(len(evidence["omittedUnreferencedCollisions"]), 1)

    def test_graph_guid_parser_resolves_referenced_collision_by_exact_namespace(self) -> None:
        graph = {
            "bank": {"bankGuid": "11111111-1111-1111-1111-111111111111"},
            "events": [],
        }
        mappings, evidence = parse_graph_guids(
            "{11111111-1111-1111-1111-111111111111} bank:/car_a\n"
            "{11111111-1111-1111-1111-111111111111} bank:/CAR_A\n",
            graph,
            car_id="car_a",
        )

        self.assertEqual(
            mappings["11111111-1111-1111-1111-111111111111"],
            "bank:/car_a",
        )
        self.assertEqual(len(evidence["resolvedReferencedCollisions"]), 1)

    def test_guids_enrich_events_and_flatten_subsounds(self) -> None:
        guid_paths = parse_guids(
            "{11111111-1111-1111-1111-111111111111} bank:/car_a\n"
            "{22222222-2222-2222-2222-222222222222} event:/cars/car_a/engine_int\n"
        )
        raw_graph = {
            "schema": "source-v1",
            "bank": {"bankGuid": "11111111-1111-1111-1111-111111111111", "sha256": "abc"},
            "events": [
                {
                    "guid": "22222222-2222-2222-2222-222222222222",
                    "reachableInstrumentGuids": ["33333333-3333-3333-3333-333333333333"],
                }
            ],
            "instruments": [
                {
                    "guid": "33333333-3333-3333-3333-333333333333",
                    "sample": {"subsoundIndex": 7, "name": "engine_3000"},
                    "baseProperties": {"autoPitchReference": 3000},
                    "controllerGuids": [],
                }
            ],
        }

        graph = enrich_graph(raw_graph, guid_paths, car_id="car_a")

        self.assertEqual(graph["bank"]["path"], "bank:/car_a")
        self.assertEqual(graph["events"][0]["path"], "event:/cars/car_a/engine_int")
        self.assertEqual(graph["samples"][0]["subsoundIndex"], 7)
        self.assertEqual(graph["samples"][0]["eventPaths"], ["event:/cars/car_a/engine_int"])

    def test_parses_deduplicated_zero_based_subsounds(self) -> None:
        self.assertEqual(parse_subsound_selection("9, 2,9,0"), (0, 2, 9))
        with self.assertRaises(PipelineError):
            parse_subsound_selection("-1")

    def test_runtime_oracle_removes_a_proven_static_only_event_partition(self) -> None:
        active_sha = "a" * 64
        orphan_sha = "b" * 64
        raw_graph = {
            "counts": {"events": 2},
            "coverage": {"eventsWithCompleteSampleMapping": 1, "eventsWithSamples": 2},
            "events": [
                {
                    "guid": "active",
                    "reachableInstrumentGuids": ["active-source", "orphan-source"],
                    "mappedSampleIds": [f"active|{active_sha}", f"orphan|{orphan_sha}"],
                    "resolverSampleIds": [f"active|{active_sha}"],
                    "mappingComplete": False,
                },
                {
                    "guid": "orphan",
                    "reachableInstrumentGuids": ["orphan-source"],
                    "mappedSampleIds": [f"orphan|{orphan_sha}"],
                    "resolverSampleIds": [f"orphan|{orphan_sha}"],
                    "mappingComplete": True,
                },
            ],
            "instruments": [
                {
                    "guid": "active-source",
                    "sample": {"name": "active", "encodedPayloadSha256": active_sha},
                },
                {
                    "guid": "orphan-source",
                    "sample": {"name": "orphan", "encodedPayloadSha256": orphan_sha},
                },
            ],
        }
        oracle = {
            "schema": "byd-ac-fmod-runtime-event-oracle-v1",
            "carId": "car_a",
            "bankSha256": "bank-sha",
            "activeEventGuids": ["active"],
            "staticOnlyEventGuids": ["orphan"],
            "sampleGraphRepairs": [
                {
                    "activeEventGuid": "active",
                    "staticOnlyEventGuids": ["orphan"],
                }
            ],
        }

        graph, evidence = reconcile_graph_with_runtime_oracle(
            raw_graph,
            oracle,
            car_id="car_a",
            bank_sha256="bank-sha",
        )

        self.assertEqual([event["guid"] for event in graph["events"]], ["active"])
        self.assertEqual(graph["events"][0]["reachableInstrumentGuids"], ["active-source"])
        self.assertEqual(graph["events"][0]["mappedSampleIds"], [f"active|{active_sha}"])
        self.assertTrue(graph["events"][0]["mappingComplete"])
        self.assertEqual(graph["counts"]["events"], 1)
        self.assertTrue(evidence["allActiveSampleMappingsComplete"])

    def test_runtime_oracle_rejects_a_non_exact_sample_partition(self) -> None:
        active_sha = "a" * 64
        orphan_sha = "b" * 64
        different_sha = "c" * 64
        raw_graph = {
            "counts": {"events": 2},
            "coverage": {},
            "events": [
                {
                    "guid": "active",
                    "reachableInstrumentGuids": ["active-source"],
                    "mappedSampleIds": [f"active|{active_sha}"],
                    "resolverSampleIds": [f"different|{different_sha}"],
                    "mappingComplete": False,
                },
                {
                    "guid": "orphan",
                    "reachableInstrumentGuids": ["orphan-source"],
                    "mappedSampleIds": [f"orphan|{orphan_sha}"],
                    "resolverSampleIds": [f"orphan|{orphan_sha}"],
                    "mappingComplete": True,
                },
            ],
            "instruments": [
                {
                    "guid": "active-source",
                    "sample": {"name": "active", "encodedPayloadSha256": active_sha},
                },
                {
                    "guid": "orphan-source",
                    "sample": {"name": "orphan", "encodedPayloadSha256": orphan_sha},
                },
            ],
        }
        oracle = {
            "schema": "byd-ac-fmod-runtime-event-oracle-v1",
            "carId": "car_a",
            "bankSha256": "bank-sha",
            "activeEventGuids": ["active"],
            "staticOnlyEventGuids": ["orphan"],
            "sampleGraphRepairs": [
                {"activeEventGuid": "active", "staticOnlyEventGuids": ["orphan"]}
            ],
        }

        with self.assertRaisesRegex(PipelineError, "exact resolver/static-only partition"):
            reconcile_graph_with_runtime_oracle(
                raw_graph,
                oracle,
                car_id="car_a",
                bank_sha256="bank-sha",
            )


class PackBuilderTests(unittest.TestCase):
    def test_builds_reproducible_exact_android_pack(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wav_root = root / "wav"
            wav_root.mkdir()
            for name, channels in (("load.wav", 2), ("coast.wav", 1)):
                with wave.open(str(wav_root / name), "wb") as output:
                    output.setnchannels(channels)
                    output.setsampwidth(2)
                    output.setframerate(44_100)
                    output.writeframes(b"\x00\x00" * channels * 64)
            first = root / "first.bydpack"
            second = root / "second.bydpack"

            first_report = build_bydpack(
                "test.engine",
                3,
                wav_root,
                asset_root=None,
                output=first,
                replace=False,
                dry_run=False,
            )
            second_report = build_bydpack(
                "test.engine",
                3,
                wav_root,
                asset_root=None,
                output=second,
                replace=False,
                dry_run=False,
            )

            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first_report["sha256"], second_report["sha256"])
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(
                    archive.namelist(),
                    [
                        "manifest.json",
                        "sample_engine/test.engine/coast.wav",
                        "sample_engine/test.engine/load.wav",
                    ],
                )
                self.assertTrue(all(info.date_time == (1980, 1, 1, 0, 0, 0) for info in archive.infolist()))
                manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual(set(manifest), {"schemaVersion", "packId", "packVersion", "files"})
            self.assertEqual(manifest["schemaVersion"], 1)
            self.assertEqual(manifest["packId"], "test.engine")
            self.assertEqual(manifest["packVersion"], 3)
            self.assertEqual(
                set(manifest["files"][0]),
                {"path", "sizeBytes", "sha256", "sampleRate", "channels", "frameCount"},
            )
            self.assertEqual(manifest["files"][0]["frameCount"], 64)

    def test_inspector_rejects_non_pcm16(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            wav = Path(temporary) / "pcm8.wav"
            with wave.open(str(wav), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(1)
                output.setframerate(22_050)
                output.writeframes(b"\x80" * 64)

            with self.assertRaisesRegex(PipelineError, "PCM16"):
                inspect_pcm16_wav(wav)


if __name__ == "__main__":
    unittest.main()
