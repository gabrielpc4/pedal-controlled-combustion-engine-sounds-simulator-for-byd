from __future__ import annotations

import unittest

from tools.probe_fmod_global_voice_arbitration import (
    DSP_BUFFER_FRAMES,
    LOGICAL_CHANNELS,
    SOFTWARE_CHANNELS,
    STATIC_SCENARIOS,
    diff_voice_snapshots,
    run_length_encode,
    summarize_voices,
)


def _voice(
    token: int,
    source: str,
    *,
    category: str = "engineTransient",
    virtual: bool = False,
    position: int = 0,
    priority: int = 128,
    audibility: float = 1.0,
) -> dict:
    return {
        "voiceToken": token,
        "source": source,
        "category": category,
        "isVirtual": virtual,
        "pcmPosition": position,
        "priority": priority,
        "audibility": audibility,
    }


class GlobalVoiceArbitrationPureLogicTest(unittest.TestCase):
    def test_snapshot_summary_preserves_source_category_and_positions(self) -> None:
        summary = summarize_voices(
            [
                _voice(1, "lowTransient", position=4096),
                _voice(2, "midTransient", virtual=True, position=32),
                _voice(
                    3,
                    "idleLoop",
                    category="continuousLoop",
                    position=2048,
                    priority=0,
                    audibility=0.25,
                ),
            ]
        )
        self.assertEqual(summary["logical"], 3)
        self.assertEqual(summary["real"], 2)
        self.assertEqual(summary["virtual"], 1)
        self.assertEqual(summary["virtualPcmPositionMaximum"], 32)
        self.assertEqual(summary["realPcmPositionMinimum"], 2048)
        self.assertEqual(summary["bySource"]["midTransient"]["virtual"], 1)
        self.assertEqual(summary["byCategory"]["continuousLoop"]["real"], 1)
        self.assertEqual(summary["priorityCounts"], {"0": 1, "128": 2})

    def test_handle_level_diff_distinguishes_completion_promotion_and_demotion(self) -> None:
        before = [
            _voice(1, "lowTransient", position=9000),
            _voice(2, "midTransient", virtual=True, position=64),
            _voice(3, "idleLoop", category="continuousLoop", position=4000),
        ]
        after = [
            _voice(2, "midTransient", position=96),
            _voice(
                3,
                "idleLoop",
                category="continuousLoop",
                virtual=True,
                position=4020,
            ),
            _voice(4, "fixedGearUp", category="fixedOneShot", position=16),
        ]
        change = diff_voice_snapshots(before, after)
        self.assertEqual([item["voiceToken"] for item in change["completed"]], [1])
        self.assertEqual([item["voiceToken"] for item in change["appeared"]], [4])
        self.assertEqual([item["voiceToken"] for item in change["promoted"]], [2])
        self.assertEqual([item["voiceToken"] for item in change["demoted"]], [3])
        self.assertEqual(change["promoted"][0]["pcmPositionBefore"], 64)
        self.assertEqual(change["promoted"][0]["pcmPositionAfter"], 96)

    def test_run_length_encoding_keeps_cross_program_block_order(self) -> None:
        observed = (
            ["lowTransient"] * 256
            + ["midTransient"] * 22
            + ["lowTransient"] * 23
        )
        self.assertEqual(
            run_length_encode(observed),
            [
                {"source": "lowTransient", "count": 256},
                {"source": "midTransient", "count": 22},
                {"source": "lowTransient", "count": 23},
            ],
        )

    def test_scenario_matrix_bounds_real_logical_and_static_competition(self) -> None:
        ids = [item["id"] for item in STATIC_SCENARIOS]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertIn("static-default-virtualization", ids)
        self.assertIn("static-high-priority-virtualization", ids)
        self.assertIn("static-low-priority-virtualization", ids)
        self.assertIn("logical-cap-default-steal", ids)
        self.assertTrue(any(item["loopVolume"] < 1.0 for item in STATIC_SCENARIOS))
        self.assertTrue(
            any(item["transientVolume"] < 1.0 for item in STATIC_SCENARIOS)
        )
        self.assertTrue(any(item["fixedPriority"] == 0 for item in STATIC_SCENARIOS))
        self.assertTrue(any(item["fixedPriority"] == 255 for item in STATIC_SCENARIOS))
        self.assertTrue(
            all(item["maxChannels"] in {LOGICAL_CHANNELS, 257} for item in STATIC_SCENARIOS)
        )
        self.assertEqual(SOFTWARE_CHANNELS, 256)
        self.assertEqual(DSP_BUFFER_FRAMES, 256)


if __name__ == "__main__":
    unittest.main()
