from __future__ import annotations

import math
import unittest
from pathlib import Path

from fh6.granular import GranularPlanner, RootLoop


class GranularPlannerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.planner = GranularPlanner((
            RootLoop(Path("Idle_Dec_900_ADPCM.wav"), "idle", 900.0),
            RootLoop(Path("Acc_Acc_2000_ADPCM.wav"), "acc", 2000.0),
            RootLoop(Path("Acc_Acc_4000_ADPCM.wav"), "acc", 4000.0),
            RootLoop(Path("Dec_Dec_2000_ADPCM.wav"), "dec", 2000.0),
            RootLoop(Path("Dec_Dec_4000_ADPCM.wav"), "dec", 4000.0),
        ))

    def test_root_rpm_has_unity_pitch(self) -> None:
        voice = self.planner.voices(2000.0, 100.0, 1.0)[0]
        self.assertEqual(voice.loop.family, "acc")
        self.assertAlmostEqual(voice.pitch_ratio, 1.0)

    def test_neighbor_crossfade_has_constant_power(self) -> None:
        voices = self.planner.voices(3000.0, 100.0, 1.0)
        self.assertEqual(len(voices), 2)
        self.assertAlmostEqual(sum(voice.gain ** 2 for voice in voices), 1.0)
        self.assertAlmostEqual(voices[0].pitch_ratio, 1.5)
        self.assertAlmostEqual(voices[1].pitch_ratio, 0.75)

    def test_deceleration_and_idle_select_separate_recordings(self) -> None:
        self.assertEqual(self.planner.voices(3000.0, -10.0, 0.0)[0].loop.family, "dec")
        self.assertEqual(self.planner.voices(900.0, 0.0, 0.0)[0].loop.family, "idle")


if __name__ == "__main__":
    unittest.main()
