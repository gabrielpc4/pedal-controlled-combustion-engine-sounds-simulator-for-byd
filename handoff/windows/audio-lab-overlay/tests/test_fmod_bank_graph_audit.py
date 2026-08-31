from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import tempfile
import unittest

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_native import Guid
from sim.fmod_probe import SilentFmodBankProbe
from tools.audit_fmod_bank_graph import (
    BANK_GRAPH_TOOL_CAPABILITIES,
    DEFAULT_PARSER_ROOT,
    BankGraphAuditError,
    audit_bank_graph,
    _require_compatible_parser,
    validate_bank_graph_report,
)


def _minimal_report(bank_sha256: str) -> dict:
    return {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "toolCapabilities": dict(BANK_GRAPH_TOOL_CAPABILITIES),
        "sourceIsolationOffsets": {
            "encoding": "absolute-source-bank-byte-offset+ieee754-binary32-little-endian",
            "waveformInstrumentBodies": 1,
            "validatedAgainstSourceBytes": True,
        },
        "bank": {"fileVersion": 0x50, "sha256": bank_sha256},
        "counts": {"events": 0, "controllers": 0},
        "coverage": {
            "eventsWithCompleteSampleMapping": 0,
            "controllersWithCurve": 0,
        },
        "featureKinds": {},
        "unknownChunks": [],
        "instruments": [
            {
                "guid": "wave",
                "kind": "WaveformInstrumentNode",
                "baseProperties": {
                    "triggerChancePercent": 100.0,
                    "triggerChancePercentRawUInt32": 1120403456,
                    "triggerChancePercentFileOffset": 64,
                },
            }
        ],
        "events": [],
        "silentRuntimeOracle": {
            "outputType": "NOSOUND_NRT",
            "eventGuidPathMappings": 0,
        },
    }


class SilentProbeGuidTests(unittest.TestCase):
    def test_guid_text_round_trips_runtime_layout(self) -> None:
        text = "00112233-4455-6677-8899-aabbccddeeff"
        self.assertEqual(SilentFmodBankProbe._guid_text(Guid.parse(text)), text)


class IsolationOffsetValidationTests(unittest.TestCase):
    def test_exact_source_bytes_are_required_for_every_waveform_body(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "car.bank"
            content = bytearray(96)
            struct.pack_into("<f", content, 64, 100.0)
            source.write_bytes(content)
            report = _minimal_report(hashlib.sha256(content).hexdigest())
            validate_bank_graph_report(report, source_bank_path=source)

            changed = bytearray(content)
            struct.pack_into("<f", changed, 64, 50.0)
            source.write_bytes(changed)
            report["bank"]["sha256"] = hashlib.sha256(changed).hexdigest()
            with self.assertRaisesRegex(
                BankGraphAuditError, "offset/raw bits disagree with source"
            ):
                validate_bank_graph_report(report, source_bank_path=source)

    def test_pre_offset_v3_report_is_not_reusable(self) -> None:
        report = _minimal_report("a" * 64)
        report.pop("toolCapabilities")
        report.pop("sourceIsolationOffsets")
        with self.assertRaisesRegex(BankGraphAuditError, "lacks the current"):
            validate_bank_graph_report(report)

    def test_marker_cannot_hide_missing_waveform_offset_fields(self) -> None:
        report = _minimal_report("a" * 64)
        del report["instruments"][0]["baseProperties"][
            "triggerChancePercentFileOffset"
        ]
        with self.assertRaisesRegex(BankGraphAuditError, "evidence is invalid"):
            validate_bank_graph_report(report)


class RealBankGraphAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.assetto_root = find_assetto_root()
            _require_compatible_parser(DEFAULT_PARSER_ROOT)
        except (FileNotFoundError, BankGraphAuditError) as exc:
            raise unittest.SkipTest(str(exc)) from exc

        cls.tatuus_bank = (
            cls.assetto_root
            / "content"
            / "cars"
            / "tatuusfa1"
            / "sfx"
            / "tatuusfa1.bank"
        )
        cls.supra_bank = (
            cls.assetto_root
            / "content"
            / "cars"
            / "ks_toyota_supra_mkiv"
            / "sfx"
            / "ks_toyota_supra_mkiv.bank"
        )
        cls.tatuus = audit_bank_graph(
            cls.tatuus_bank,
            assetto_root=cls.assetto_root,
        )
        cls.supra = audit_bank_graph(
            cls.supra_bank,
            assetto_root=cls.assetto_root,
        )

    def test_real_ac_108_banks_have_complete_event_sample_graphs(self) -> None:
        expected = {
            "tatuusfa1.bank": (19, 64, 58, 76),
            "ks_toyota_supra_mkiv.bank": (19, 63, 51, 82),
        }
        for report in (self.tatuus, self.supra):
            with self.subTest(bank=report["bank"]["fileName"]):
                self.assertEqual(report["bank"]["fileVersion"], 0x50)
                counts = report["counts"]
                self.assertEqual(
                    (
                        counts["events"],
                        counts["instruments"],
                        counts["embeddedSamples"],
                        counts["curves"],
                    ),
                    expected[report["bank"]["fileName"]],
                )
                self.assertEqual(
                    report["coverage"]["eventsWithCompleteSampleMapping"],
                    counts["events"],
                )
                self.assertEqual(
                    report["coverage"]["controllersWithCurve"],
                    counts["controllers"],
                )
                self.assertTrue(all(event["mappingComplete"] for event in report["events"]))
                self.assertEqual(
                    report["silentRuntimeOracle"]["eventGuidPathMappings"],
                    counts["events"],
                )
                self.assertEqual(
                    report["toolCapabilities"], BANK_GRAPH_TOOL_CAPABILITIES
                )
                waveform_count = sum(
                    item["kind"] == "WaveformInstrumentNode"
                    for item in report["instruments"]
                )
                self.assertEqual(
                    report["sourceIsolationOffsets"]["waveformInstrumentBodies"],
                    waveform_count,
                )
                self.assertTrue(
                    all(
                        "triggerChancePercentFileOffset" in item["baseProperties"]
                        and "triggerChancePercentRawUInt32" in item["baseProperties"]
                        for item in report["instruments"]
                        if item["kind"] == "WaveformInstrumentNode"
                    )
                )

    def test_tatuus_engine_layer_matches_shipped_authoring_values(self) -> None:
        instrument = next(
            item
            for item in self.tatuus["instruments"]
            if (item.get("sample") or {}).get("name") == "ext_fa01_on_4250"
        )
        self.assertEqual(instrument["guid"], "0767e162-09f6-412f-8ccb-82fdcdddc252")
        self.assertEqual(instrument["baseProperties"]["autoPitchReference"], 4250.0)
        self.assertEqual(instrument["baseProperties"]["volumeDb"], -2.5)

        controllers = {
            controller["guid"]: controller
            for controller in self.tatuus["controllers"]
            if controller["guid"] in instrument["controllerGuids"]
        }
        throttle = next(
            controller
            for controller in controllers.values()
            if controller["inputParameterName"] == "throttle"
        )
        self.assertEqual(throttle["propertyIndex"], 0)
        self.assertEqual(
            [(point["x"], point["y"], point["shape"]) for point in throttle["points"]],
            [
                (0.05, -42.0, -0.34993118),
                (0.47000003, -6.3913035, 0.19940375),
                (0.85, 0.39130354, 0.0),
            ],
        )
        rpm_curves = sorted(
            (
                [(point["x"], point["y"]) for point in controller["points"]]
                for controller in controllers.values()
                if controller["inputParameterName"] == "rpms"
            ),
            key=lambda points: points[0][0],
        )
        self.assertEqual(
            rpm_curves,
            [[(1400.0, 0.0), (1800.0, 1.0)], [(3400.0, 1.0), (3800.0, 0.0)]],
        )
        event = next(
            event
            for event in self.tatuus["events"]
            if instrument["guid"] in event["reachableInstrumentGuids"]
        )
        self.assertEqual(event["path"], "event:/cars/tatuusfa1/engine_ext")
        placement = next(
            item
            for item in event["parameterPlacements"]
            if item["instrumentGuid"] == instrument["guid"]
        )
        self.assertEqual(
            (placement["parameterName"], placement["start"], placement["end"]),
            ("rpms", 1400.0, 3800.0),
        )

    def test_audit_is_byte_deterministic(self) -> None:
        repeated = audit_bank_graph(
            self.tatuus_bank,
            assetto_root=self.assetto_root,
        )
        self.assertEqual(canonical_json_bytes(repeated), canonical_json_bytes(self.tatuus))


if __name__ == "__main__":
    unittest.main()
