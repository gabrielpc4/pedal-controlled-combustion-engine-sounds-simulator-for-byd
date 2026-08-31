from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from tools.audit_fmod_bank_graph import BANK_GRAPH_TOOL_CAPABILITIES
from tools.audit_fmod_catalog_graphs import (
    DEFAULT_PARSER_ROOT,
    FamilyTarget,
    build_summary,
    catalog_family_targets,
    role_classification_evidence,
    run_catalog_audit,
)


def _empty_report(family_id: str = "a" * 64) -> dict:
    return {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "bank": {"fileName": "car.bank", "fileVersion": 0x50, "sha256": family_id},
        "counts": {
            "events": 0,
            "controllers": 0,
            "instruments": 0,
            "embeddedSamples": 0,
            "curves": 0,
        },
        "coverage": {
            "eventsWithCompleteSampleMapping": 0,
            "eventsWithSamples": 0,
            "controllersWithCurve": 0,
            "controllersWithInputParameter": 0,
            "controllersWithTimelineInput": 0,
            "controllersWithUnknownInput": 0,
            "instrumentOrRouteControllers": 0,
        },
        "featureKinds": {
            "instruments": [],
            "modulators": [],
            "effectNodes": [],
            "buses": [],
            "transitions": [],
            "controllerInputs": [],
            "curvePointTypes": [],
        },
        "unknownChunks": [],
        "toolCapabilities": dict(BANK_GRAPH_TOOL_CAPABILITIES),
        "sourceIsolationOffsets": {
            "encoding": "absolute-source-bank-byte-offset+ieee754-binary32-little-endian",
            "waveformInstrumentBodies": 0,
            "validatedAgainstSourceBytes": True,
        },
        "controllers": [],
        "effects": [],
        "instruments": [],
        "events": [],
        "silentRuntimeOracle": {
            "apiVersion": "0x00010812",
            "eventGuidPathMappings": 0,
            "outputType": "NOSOUND_NRT",
        },
    }


class RoleEvidenceTests(unittest.TestCase):
    def test_role_evidence_is_explicitly_non_authoritative(self) -> None:
        report = _empty_report()
        report["controllers"] = [
            {
                "guid": "rising",
                "inputKind": "parameter",
                "inputParameterName": "throttle",
                "propertyIndex": 0,
                "points": [{"x": 0.0, "y": -42.0}, {"x": 1.0, "y": 0.0}],
            },
            {
                "guid": "falling",
                "inputKind": "parameter",
                "inputParameterName": "throttle",
                "propertyIndex": 0,
                "points": [{"x": 0.0, "y": 0.0}, {"x": 1.0, "y": -42.0}],
            },
        ]
        report["instruments"] = [
            {"guid": "on", "sample": {"name": "engine_on"}, "controllerGuids": ["rising"]},
            {"guid": "off", "sample": {"name": "engine_off"}, "controllerGuids": ["falling"]},
            {"guid": "unknown", "sample": {"name": "idle"}, "controllerGuids": []},
        ]
        report["events"] = [
            {
                "path": "event:/cars/test/engine_ext",
                "reachableInstrumentGuids": ["on", "off", "unknown"],
                "mappingComplete": True,
            }
        ]
        evidence = role_classification_evidence(report)
        self.assertFalse(evidence["roleIsolationProven"])
        self.assertEqual(evidence["status"], "evidenceOnlyNotRoleClassification")
        self.assertEqual(
            evidence["throttleVolumeEndpointTrendCounts"],
            {"rising": 1, "falling": 1, "flat": 0, "mixed": 0, "missing": 1},
        )
        self.assertEqual(evidence["weakSampleNameTokenCounts"], {"idle": 1, "off": 1, "on": 1})


class BatchSummaryTests(unittest.TestCase):
    def test_summary_separates_timeline_inputs_from_unknown_guids(self) -> None:
        family_id = "a" * 64
        report = _empty_report(family_id)
        report["counts"]["controllers"] = 3
        report["coverage"].update(
            {
                "controllersWithCurve": 3,
                "controllersWithInputParameter": 1,
                "controllersWithTimelineInput": 1,
                "controllersWithUnknownInput": 1,
            }
        )
        report["featureKinds"]["controllerInputs"] = [
            {"kind": "parameter", "count": 1},
            {"kind": "timeline", "count": 1},
            {"kind": "unknownGuid", "count": 1},
        ]
        report["controllers"] = [
            {
                "guid": "p",
                "inputKind": "parameter",
                "inputParameterGuid": "1",
                "inputParameterName": "throttle",
                "propertyIndex": 0,
                "points": [],
            },
            {
                "guid": "t",
                "inputKind": "timeline",
                "inputParameterGuid": "2",
                "inputParameterName": None,
                "propertyIndex": 0,
                "points": [{"xRawUInt32": 48000}],
            },
            {
                "guid": "u",
                "inputKind": "unknownGuid",
                "inputParameterGuid": "3",
                "inputParameterName": None,
                "propertyIndex": 0,
                "points": [],
            },
        ]
        target = FamilyTarget(family_id, "car", ("car",), "content/cars/car/sfx/car.bank", Path("car.bank"))
        catalog = {"catalogSha256": "catalog", "counts": {"usableCars": 1, "soundFamilies": 1}}
        summary = build_summary(
            catalog=catalog,
            targets=[target],
            reports={family_id: report},
            failures={},
            reused_family_ids=set(),
            audited_this_run_ids={family_id},
            parser_metadata={},
        )
        self.assertEqual(summary["timelineControllerEvidence"]["controllers"], 1)
        self.assertEqual(summary["timelineControllerEvidence"]["rawPositionMinimum"], 48000)
        self.assertEqual([item["guid"] for item in summary["unresolvedParameterGuids"]], ["3"])

    def test_success_cache_resumes_without_building_or_probing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bank = root / "content" / "cars" / "car" / "sfx" / "car.bank"
            bank.parent.mkdir(parents=True)
            bank_bytes = bytearray(132)
            struct.pack_into("<f", bank_bytes, 64, 100.0)
            bank.write_bytes(bank_bytes)
            family_id = hashlib.sha256(bank_bytes).hexdigest()
            report = _empty_report(family_id)
            report["counts"]["instruments"] = 1
            report["instruments"] = [
                {
                    "guid": "wave",
                    "kind": "WaveformInstrumentNode",
                    "baseProperties": {
                        "triggerChancePercent": 100.0,
                        "triggerChancePercentRawUInt32": 1120403456,
                        "triggerChancePercentFileOffset": 64,
                    },
                }
            ]
            report["sourceIsolationOffsets"]["waveformInstrumentBodies"] = 1
            catalog = {
                "catalogSha256": "catalog",
                "cars": [
                    {
                        "id": "car",
                        "provenance": {
                            "bankPath": "content/cars/car/sfx/car.bank",
                            "bankSha256": family_id,
                        },
                    }
                ],
                "soundFamilies": [
                    {
                        "id": family_id,
                        "representativeCarId": "car",
                        "memberIds": ["car"],
                    }
                ],
                "counts": {"usableCars": 1, "soundFamilies": 1},
            }
            output = root / "output"
            cache = output / "families" / f"{family_id}.json"
            cache.parent.mkdir(parents=True)
            cache.write_text(json.dumps(report), encoding="utf-8")
            catalog_path = root / "unused.json"
            catalog_path.write_text("{}", encoding="utf-8")
            with patch(
                "tools.audit_fmod_catalog_graphs._load_catalog", return_value=catalog
            ), patch(
                "tools.audit_fmod_catalog_graphs.build_static_auditor"
            ) as build:
                summary = run_catalog_audit(
                    assetto_root=root,
                    catalog_path=catalog_path,
                    output_root=output,
                    parser_root=DEFAULT_PARSER_ROOT,
                    max_new_families=0,
                )
            build.assert_not_called()
            self.assertEqual(summary["status"]["reusedFromCache"], 1)
            self.assertEqual(summary["status"]["invalidatedCache"], 0)
            self.assertEqual(
                summary["sourceIsolationOffsets"][
                    "offsetsValidatedAgainstExactSourceBytes"
                ],
                1,
            )
            self.assertTrue(summary["status"]["allFamiliesSuccessful"])

    def test_pre_offset_v3_cache_is_invalidated_and_atomically_rebuilt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bank = root / "content" / "cars" / "car" / "sfx" / "car.bank"
            bank.parent.mkdir(parents=True)
            bank.write_bytes(b"unchanged source")
            family_id = hashlib.sha256(bank.read_bytes()).hexdigest()
            fresh = _empty_report(family_id)
            stale = _empty_report(family_id)
            stale.pop("toolCapabilities")
            stale.pop("sourceIsolationOffsets")
            catalog = {
                "catalogSha256": "catalog",
                "cars": [
                    {
                        "id": "car",
                        "provenance": {
                            "bankPath": "content/cars/car/sfx/car.bank",
                            "bankSha256": family_id,
                        },
                    }
                ],
                "soundFamilies": [
                    {
                        "id": family_id,
                        "representativeCarId": "car",
                        "memberIds": ["car"],
                    }
                ],
                "counts": {"usableCars": 1, "soundFamilies": 1},
            }
            output = root / "output"
            cache = output / "families" / f"{family_id}.json"
            cache.parent.mkdir(parents=True)
            cache.write_text(json.dumps(stale), encoding="utf-8")
            catalog_path = root / "unused.json"
            catalog_path.write_text("{}", encoding="utf-8")
            with patch(
                "tools.audit_fmod_catalog_graphs._load_catalog", return_value=catalog
            ), patch(
                "tools.audit_fmod_catalog_graphs.build_static_auditor"
            ), patch(
                "tools.audit_fmod_catalog_graphs.SilentFmodBankProbe"
            ), patch(
                "tools.audit_fmod_catalog_graphs.audit_bank_graph", return_value=fresh
            ) as audit:
                summary = run_catalog_audit(
                    assetto_root=root,
                    catalog_path=catalog_path,
                    output_root=output,
                    parser_root=DEFAULT_PARSER_ROOT,
                )
            audit.assert_called_once()
            rebuilt = json.loads(cache.read_text(encoding="utf-8"))
            self.assertEqual(rebuilt["toolCapabilities"], BANK_GRAPH_TOOL_CAPABILITIES)
            self.assertEqual(summary["status"]["invalidatedCache"], 1)
            self.assertEqual(summary["status"]["auditedThisRun"], 1)
            self.assertTrue(summary["status"]["allFamiliesSuccessful"])


if __name__ == "__main__":
    unittest.main()
