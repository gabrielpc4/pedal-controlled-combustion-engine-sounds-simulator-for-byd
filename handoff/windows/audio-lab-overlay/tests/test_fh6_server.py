from __future__ import annotations

import time
import unittest

from fh6.config import find_fh6_root
from fh6_server import FH6Simulation


class FH6ServerTests(unittest.TestCase):
    def setUp(self) -> None:
        try:
            root = find_fh6_root()
        except FileNotFoundError as exc:
            self.skipTest(str(exc))
        self.simulation = FH6Simulation(root)
        self.simulation.start()

    def tearDown(self) -> None:
        self.simulation.close()

    def test_mock_controls_and_prnd_reach_live_state(self) -> None:
        self.simulation.apply_control({"ignition": True, "selector": "D", "speedKph": 42.125, "throttlePct": 33.3, "inputRateHz": 60})
        time.sleep(0.15)
        state = self.simulation.state()
        self.assertTrue(state["ignition"])
        self.assertEqual(state["selector"], "D")
        self.assertGreater(state["mock"]["speedKph"], 42.125)
        self.assertGreater(state["speedKph"], 41.0)
        self.assertAlmostEqual(state["throttlePct"], 33.3, places=1)
        self.assertGreater(state["mock"]["dynamics"]["driveForceN"], 0.0)
        self.assertFalse(state["audio"]["available"])
        self.assertEqual(self.simulation.config()["excludedSounds"], ["tyres", "brakes", "chassis", "damage"])

    def test_external_adapter_contract_accepts_decimal_sample(self) -> None:
        accepted = self.simulation.apply_sample({"speedKph": 51.2345, "throttlePct": 12.25, "brakePct": 0.5})
        self.assertTrue(accepted)
        time.sleep(0.01)
        self.assertTrue(self.simulation.apply_sample({"speedKph": 51.2345, "throttlePct": 12.25, "brakePct": 0.5}))
        time.sleep(0.22)
        state = self.simulation.state()
        self.assertAlmostEqual(state["speedKph"], 51.2345, places=3)

    def test_full_level_and_isolation_policy_are_exposed(self) -> None:
        time.sleep(0.02)
        full = self.simulation.state()["audioParameters"]
        self.assertEqual(full["EngineLevel"], 1.0)
        self.assertEqual(full["TransmissionLevel"], 1.0)
        self.assertEqual(full["EffectsLevel"], 1.0)
        self.simulation.apply_control({"isolated": True, "authenticLevels": False, "auditionPops": True})
        time.sleep(0.02)
        state = self.simulation.state()
        self.assertTrue(state["isolated"])
        self.assertFalse(state["authenticLevels"])
        self.assertEqual(state["audioParameters"]["EngineLevel"], 0.0)
        self.assertEqual(state["audioParameters"]["TransmissionLevel"], 0.0)
        self.assertEqual(state["audioParameters"]["EffectsLevel"], 1.0)


if __name__ == "__main__":
    unittest.main()
