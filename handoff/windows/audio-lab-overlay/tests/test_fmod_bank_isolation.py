from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import wave

from sim.assetto import find_assetto_root
from sim.fmod_bank_isolation import (
    FmodBankIsolationError,
    create_isolated_bank_copy,
    plan_disabled_parent_isolation,
    plan_instrument_isolation,
    plan_single_shot_parent_isolation,
)
from sim.fmod_graph_roles import (
    ROLE_ENGINE_FALLING,
    ROLE_EXCLUDED_LOAD,
    classify_bank_graph_sources,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_sdk_audit import FmodSdkAuditError, audit_shipped_fmod_authoring
from tools.audit_fmod_bank_graph import BankGraphAuditError, audit_bank_graph


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _fixture_graph(bank: Path) -> dict:
    payload = bytearray(bank.read_bytes())
    return {
        "bank": {
            "fileVersion": 80,
            "sha256": hashlib.sha256(payload).hexdigest(),
        },
        "instruments": [
            {
                "guid": "a" * 32,
                "kind": "WaveformInstrumentNode",
                "baseProperties": {
                    "triggerChancePercent": 100.0,
                    "triggerChancePercentRawUInt32": struct.unpack(
                        "<I", payload[64:68]
                    )[0],
                    "triggerChancePercentFileOffset": 64,
                },
            },
            {
                "guid": "b" * 32,
                "kind": "WaveformInstrumentNode",
                "baseProperties": {
                    "triggerChancePercent": 100.0,
                    "triggerChancePercentRawUInt32": struct.unpack(
                        "<I", payload[128:132]
                    )[0],
                    "triggerChancePercentFileOffset": 128,
                },
            },
            {
                "guid": "c" * 32,
                "kind": "MultiInstrumentNode",
                "baseProperties": {
                    "loopCount": -1,
                    "triggerChancePercent": 66.5,
                    "triggerChancePercentRawUInt32": struct.unpack(
                        "<I", payload[177:181]
                    )[0],
                    "triggerChancePercentFileOffset": 177,
                    "volumeFileOffset": 160,
                },
            },
        ],
    }


class BankIsolationUnitTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bank = self.root / "source.bank"
        payload = bytearray((index * 29 + 7) & 0xFF for index in range(256))
        payload[64:68] = struct.pack("<f", 100.0)
        payload[128:132] = struct.pack("<f", 100.0)
        payload[160:164] = struct.pack("<f", 0.0)
        payload[168:172] = struct.pack("<i", -1)
        payload[177:181] = struct.pack("<f", 66.5)
        self.bank.write_bytes(payload)
        self.graph = _fixture_graph(self.bank)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_copy_patch_is_byte_bounded_and_source_is_unchanged(self) -> None:
        source_before = self.bank.read_bytes()
        output = self.root / "isolated.bank"
        result = create_isolated_bank_copy(
            self.bank, self.graph, ["{" + "A" * 32 + "}"], output
        )
        self.assertEqual(self.bank.read_bytes(), source_before)
        isolated = output.read_bytes()
        self.assertEqual(isolated[64:68], b"\0\0\0\0")
        self.assertEqual(isolated[:64], source_before[:64])
        self.assertEqual(isolated[68:], source_before[68:])
        self.assertEqual(result.source_sha256, hashlib.sha256(source_before).hexdigest())
        self.assertEqual(len(result.patches), 1)
        self.assertTrue(set(result.differing_byte_offsets) <= set(range(64, 68)))

    def test_plan_rejects_wrong_hash_missing_guid_and_non_waveform(self) -> None:
        wrong_hash = json.loads(json.dumps(self.graph))
        wrong_hash["bank"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(FmodBankIsolationError, "SHA-256"):
            plan_instrument_isolation(self.bank, wrong_hash, ["a" * 32])
        with self.assertRaisesRegex(FmodBankIsolationError, "absent"):
            plan_instrument_isolation(self.bank, self.graph, ["d" * 32])
        structural = json.loads(json.dumps(self.graph))
        structural["instruments"][0]["kind"] = "MultiInstrumentNode"
        with self.assertRaisesRegex(FmodBankIsolationError, "not a waveform"):
            plan_instrument_isolation(self.bank, structural, ["a" * 32])

    def test_plan_rejects_offset_whose_bytes_do_not_match_parser_evidence(self) -> None:
        corrupted = json.loads(json.dumps(self.graph))
        corrupted["instruments"][0]["baseProperties"][
            "triggerChancePercentRawUInt32"
        ] = 0
        with self.assertRaisesRegex(FmodBankIsolationError, "bytes disagree"):
            plan_instrument_isolation(self.bank, corrupted, ["a" * 32])

    def test_copy_refuses_to_write_inside_an_assetto_content_tree(self) -> None:
        installed = self.root / "content" / "cars" / "car" / "sfx" / "car.bank"
        installed.parent.mkdir(parents=True)
        installed.write_bytes(self.bank.read_bytes())
        graph = _fixture_graph(installed)
        with self.assertRaisesRegex(FmodBankIsolationError, "outside"):
            create_isolated_bank_copy(
                installed,
                graph,
                ["a" * 32],
                installed.with_name("temporary.bank"),
            )

    def test_single_shot_parent_patch_is_bounded_and_preserves_authored_graph(self) -> None:
        source_before = self.bank.read_bytes()
        planned = plan_single_shot_parent_isolation(
            self.bank, self.graph, ["c" * 32]
        )
        self.assertEqual(len(planned), 1)
        self.assertEqual(planned[0].loop_count_offset, 168)
        self.assertEqual(planned[0].original_loop_count, -1)
        self.assertEqual(planned[0].original_trigger_chance_percent, 66.5)

        output = self.root / "single-shot.bank"
        result = create_isolated_bank_copy(
            self.bank,
            self.graph,
            ["a" * 32],
            output,
            single_shot_parent_guids=["c" * 32],
        )
        self.assertEqual(self.bank.read_bytes(), source_before)
        isolated = output.read_bytes()
        self.assertEqual(isolated[64:68], struct.pack("<f", 0.0))
        self.assertEqual(isolated[168:172], struct.pack("<i", 0))
        self.assertEqual(isolated[177:181], struct.pack("<f", 100.0))
        self.assertEqual(len(result.single_shot_parent_patches), 1)
        self.assertTrue(
            set(result.differing_byte_offsets)
            <= set(range(64, 68)) | set(range(168, 172)) | set(range(177, 181))
        )

    def test_single_shot_parent_patch_rejects_wrong_layout_or_loop_state(self) -> None:
        wrong_version = json.loads(json.dumps(self.graph))
        wrong_version["bank"]["fileVersion"] = 81
        with self.assertRaisesRegex(FmodBankIsolationError, "file version 80"):
            plan_single_shot_parent_isolation(
                self.bank, wrong_version, ["c" * 32]
            )
        not_repeating = json.loads(json.dumps(self.graph))
        not_repeating["instruments"][2]["baseProperties"]["loopCount"] = 0
        with self.assertRaisesRegex(FmodBankIsolationError, "bytes disagree"):
            plan_single_shot_parent_isolation(
                self.bank, not_repeating, ["c" * 32]
            )

    def test_fully_muted_parent_trigger_patch_is_byte_bounded(self) -> None:
        source_before = self.bank.read_bytes()
        planned = plan_disabled_parent_isolation(
            self.bank, self.graph, ["c" * 32]
        )
        self.assertEqual(len(planned), 1)
        self.assertEqual(planned[0].trigger_chance_offset, 177)
        output = self.root / "disabled-parent.bank"
        result = create_isolated_bank_copy(
            self.bank,
            self.graph,
            ["a" * 32, "b" * 32],
            output,
            disabled_parent_guids=["c" * 32],
        )
        self.assertEqual(self.bank.read_bytes(), source_before)
        self.assertEqual(output.read_bytes()[177:181], struct.pack("<f", 0.0))
        self.assertEqual(len(result.disabled_parent_patches), 1)
        self.assertTrue(
            set(result.differing_byte_offsets)
            <= set(range(64, 68))
            | set(range(128, 132))
            | set(range(177, 181))
        )

        invalid = json.loads(json.dumps(self.graph))
        invalid["instruments"][2]["kind"] = "WaveformInstrumentNode"
        with self.assertRaisesRegex(FmodBankIsolationError, "not a Multi"):
            plan_disabled_parent_isolation(self.bank, invalid, ["c" * 32])


class TatuusBankIsolationOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.assetto_root = find_assetto_root()
            cls.bank = (
                cls.assetto_root
                / "content"
                / "cars"
                / "tatuusfa1"
                / "sfx"
                / "tatuusfa1.bank"
            )
            cls.graph = audit_bank_graph(cls.bank, assetto_root=cls.assetto_root)
            cls.authoring = audit_shipped_fmod_authoring(cls.assetto_root)
        except (FileNotFoundError, BankGraphAuditError, FmodSdkAuditError) as exc:
            raise unittest.SkipTest(str(exc)) from exc

    @staticmethod
    def _authoring_sets(authoring: dict) -> tuple[set[str], set[str]]:
        excluded = {
            instrument["id"].strip("{}").casefold()
            for event in authoring["events"]
            for group in event["groups"]
            if group["manifestRole"] == "EXCLUDED_LOAD"
            for instrument in group["instruments"]
        }
        allowed = {
            instrument["id"].strip("{}").casefold()
            for event in authoring["events"]
            for group in event["groups"]
            if group["manifestRole"] in {"COAST", "EXHAUST"}
            for instrument in group["instruments"]
        }
        return excluded, allowed

    def test_excluded_derivative_loads_and_schedules_only_allowed_sources(self) -> None:
        excluded, allowed = self._authoring_sets(self.authoring)
        classified = classify_bank_graph_sources(self.graph)
        self.assertEqual(len(excluded), 14)
        self.assertEqual(len(allowed), 12)
        self.assertEqual(
            excluded,
            {
                row["sourceGuid"]
                for row in classified["sources"]
                if row["role"] == ROLE_EXCLUDED_LOAD
            },
        )
        self.assertEqual(
            allowed,
            {
                row["sourceGuid"]
                for row in classified["sources"]
                if row["role"] == ROLE_ENGINE_FALLING
            },
        )
        source_sha = _sha256(self.bank)
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text)
            excluded_path = temporary / "excluded.bank"
            all_muted_path = temporary / "all-muted.bank"
            create_isolated_bank_copy(
                self.bank, self.graph, excluded, excluded_path
            )
            create_isolated_bank_copy(
                self.bank, self.graph, excluded | allowed, all_muted_path
            )
            excluded_graph = audit_bank_graph(
                excluded_path, assetto_root=self.assetto_root, build=False
            )
            by_guid = {item["guid"]: item for item in excluded_graph["instruments"]}
            self.assertTrue(
                all(
                    by_guid[guid]["baseProperties"]["triggerChancePercent"] == 0.0
                    for guid in excluded
                )
            )
            self.assertTrue(
                all(
                    by_guid[guid]["baseProperties"]["triggerChancePercent"] > 0.0
                    for guid in allowed
                )
            )

            renderer = SilentFmodReferenceRenderer(self.assetto_root)
            excluded_wav = temporary / "excluded.wav"
            all_muted_wav = temporary / "all-muted.wav"
            retained = renderer.render_event(
                excluded_path,
                "engine_ext",
                excluded_wav,
                parameters={"rpms": 3600.0, "throttle": 0.1},
                duration_frames=4096,
                warmup_frames=0,
            )
            silent = renderer.render_event(
                all_muted_path,
                "engine_ext",
                all_muted_wav,
                parameters={"rpms": 3600.0, "throttle": 0.1},
                duration_frames=4096,
                warmup_frames=0,
            )
            self.assertEqual(retained.scheduled_sound_names, ("ext_fa01_off_4250",))
            self.assertEqual(silent.scheduled_sound_names, ())
            with wave.open(str(excluded_wav), "rb") as source:
                self.assertNotEqual(set(source.readframes(source.getnframes())), {0})
            with wave.open(str(all_muted_wav), "rb") as source:
                self.assertEqual(set(source.readframes(source.getnframes())), {0})
        self.assertEqual(_sha256(self.bank), source_sha)


if __name__ == "__main__":
    unittest.main()
