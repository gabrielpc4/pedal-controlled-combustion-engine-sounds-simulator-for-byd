from __future__ import annotations

from collections import Counter
import copy
import json
import unittest
from pathlib import Path

from sim.fmod_continuous_turbo import (
    FmodContinuousTurboError,
    derive_continuous_turbo_source,
)
from sim.fmod_graph_roles import classify_bank_graph_sources


class CatalogContinuousTurboTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.graph_root = (
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3"
            / "families"
        )
        if not cls.graph_root.is_dir():
            raise unittest.SkipTest("complete graph-v3 family corpus is absent")

    def test_all_66_sources_have_a_total_controller_disposition(self) -> None:
        records = []
        for path in sorted(self.graph_root.glob("*.json")):
            graph = json.loads(path.read_text(encoding="utf-8"))
            classification = classify_bank_graph_sources(graph)
            for row in classification["sources"]:
                if row["role"] != "TURBO_CONTINUOUS_CANDIDATE":
                    continue
                record = derive_continuous_turbo_source(graph, row)
                self.assertEqual(
                    {item["controllerGuid"] for item in record["controllerDispositions"]},
                    {
                        item["controllerGuid"]
                        for item in row["decisionEvidence"]["automationControllers"]
                    },
                )
                records.append(record)
        self.assertEqual(len(records), 66)
        self.assertEqual(len({item["sourceGuid"] for item in records}), 66)
        self.assertEqual(
            Counter(item["programMode"] for item in records),
            Counter(
                {
                    "TIMELINE_PERSISTENT_LOOP": 54,
                    "BOOST_REGION_PERSISTENT_LOOP": 12,
                }
            ),
        )
        self.assertEqual(
            Counter(
                (item["inputParameterName"], item["propertyIndex"])
                for record in records
                for item in record["controllerDispositions"]
            ),
            Counter(
                {
                    ("boost", 0): 66,
                    ("boost", 1): 59,
                    ("boost", 4): 8,
                    ("", 0): 1,
                }
            ),
        )

    def test_unknown_controller_property_fails_closed(self) -> None:
        path = next(self.graph_root.glob("*.json"))
        for candidate in self.graph_root.glob("*.json"):
            graph = json.loads(candidate.read_text(encoding="utf-8"))
            rows = [
                row
                for row in classify_bank_graph_sources(graph)["sources"]
                if row["role"] == "TURBO_CONTINUOUS_CANDIDATE"
            ]
            if rows:
                path = candidate
                row = rows[0]
                break
        else:  # pragma: no cover - guarded by the complete-corpus test.
            self.fail("no continuous turbo source")
        graph = json.loads(path.read_text(encoding="utf-8"))
        changed = copy.deepcopy(graph)
        controller_guid = row["decisionEvidence"]["automationControllers"][0][
            "controllerGuid"
        ]
        controller = next(
            item for item in changed["controllers"] if item["guid"] == controller_guid
        )
        controller["propertyIndex"] = 9
        changed_row = copy.deepcopy(row)
        changed_row["decisionEvidence"]["automationControllers"][0][
            "propertyIndex"
        ] = 9
        with self.assertRaises(FmodContinuousTurboError):
            derive_continuous_turbo_source(changed, changed_row)


if __name__ == "__main__":
    unittest.main()
