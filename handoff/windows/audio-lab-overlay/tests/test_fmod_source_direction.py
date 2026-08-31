from __future__ import annotations

import unittest

from tools.probe_fmod_source_direction import _direction, _disposition, _rpm_probe


class DirectionOracleDecisionTests(unittest.TestCase):
    def test_requires_repeatable_one_way_callback_evidence(self) -> None:
        self.assertEqual(_direction([1, 1], [0, 0]), "increasingOnly")
        self.assertEqual(_direction([0, 0], [1, 1]), "decreasingOnly")
        self.assertEqual(_direction([1, 1], [1, 1]), "both")
        self.assertEqual(_direction([0, 0], [0, 0]), "neither")
        self.assertEqual(_direction([1, 0], [0, 0]), "inconsistent")

    def test_only_single_direction_results_receive_a_role(self) -> None:
        self.assertEqual(_disposition("increasingOnly")["role"], "EXCLUDED_LOAD")
        self.assertEqual(
            _disposition("decreasingOnly")["role"],
            "ENGINE_TRANSIENT_CANDIDATE",
        )
        for direction in ("both", "neither", "inconsistent"):
            self.assertEqual(_disposition(direction)["policy"], "ambiguous")

    def test_rpm_probe_uses_authored_peak_inside_trigger_placement(self) -> None:
        entry = {
            "sourceGuid": "source",
            "nonFilenameEvidence": {
                "automationControllers": [
                    {
                        "inputParameterName": "rpms",
                        "points": [
                            {"x": 6000.0, "y": -42.0},
                            {"x": 6500.0, "y": 0.0},
                            {"x": 7000.0, "y": 0.0},
                            {"x": 7500.0, "y": -42.0},
                        ],
                    }
                ],
                "eventMemberships": [
                    {
                        "triggerTopology": {
                            "placements": [
                                {
                                    "parameterName": "rpms",
                                    "start": 5900.0,
                                    "end": 7600.0,
                                }
                            ]
                        }
                    }
                ],
            },
        }
        self.assertEqual(_rpm_probe(entry), 6750.0)


if __name__ == "__main__":
    unittest.main()
