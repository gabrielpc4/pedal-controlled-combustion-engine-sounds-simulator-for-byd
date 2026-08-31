from __future__ import annotations

import time
import unittest

from server import Simulation
from sim.assetto import find_assetto_root


class SimulationServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        try:
            root = find_assetto_root()
        except FileNotFoundError as exc:
            self.skipTest(str(exc))
        self.simulation = Simulation(root, enable_audio=False)
        self.simulation.start()

    def tearDown(self) -> None:
        self.simulation.close()

    def test_controls_reach_live_state(self) -> None:
        initial = self.simulation.state()
        self.simulation.apply_control(
            {
                "throttle": 0.6,
                "brake": 0.2,
                "camera": "exhaust",
                "muted": True,
                "volume": 0.4,
            }
        )
        time.sleep(0.08)
        state = self.simulation.state()
        self.assertGreater(state["sequence"], initial["sequence"])
        self.assertGreater(state["throttle"], 0.5)
        self.assertAlmostEqual(state["brake"], 0.2)
        self.assertEqual(state["gear"], 1)
        self.assertEqual(state["gearLabel"], "1")
        self.assertTrue(state["automatic"])
        self.assertIn("speedKph", state)
        self.assertIn("drivetrainSpeed", state)
        self.assertEqual(state["camera"], "exhaust")
        self.assertTrue(state["muted"])
        self.assertEqual(state["volume"], 0.4)
        self.assertFalse(state["audio"]["available"])

    def test_rejects_unknown_control(self) -> None:
        with self.assertRaisesRegex(ValueError, "unknown control"):
            self.simulation.apply_control({"gear": 1})

    def test_accepts_backfire_audition_as_one_shot(self) -> None:
        self.simulation.apply_control({"auditionBackfire": True, "engineMuted": True})
        self.assertTrue(self.simulation.state()["engineMuted"])
        with self.assertRaisesRegex(ValueError, "auditionBackfire"):
            self.simulation.apply_control({"auditionBackfire": False})

    def test_rejects_invalid_brake_control(self) -> None:
        with self.assertRaisesRegex(ValueError, "brakeMode"):
            self.simulation.apply_control({"brakeMode": "filtered"})
        with self.assertRaisesRegex(ValueError, "brake must be"):
            self.simulation.apply_control({"brake": True})

    def test_keyboard_pedal_uses_assetto_ramp(self) -> None:
        self.simulation.apply_control({"throttle": 1.0, "throttleMode": "keyboard"})
        time.sleep(0.08)
        rising = self.simulation.state()["throttle"]
        self.assertGreater(rising, 0.15)
        self.assertLess(rising, 0.55)

        time.sleep(0.24)
        self.assertGreater(self.simulation.state()["throttle"], 0.9)
        self.simulation.apply_control({"throttle": 0.0, "throttleMode": "keyboard"})
        time.sleep(0.03)
        falling = self.simulation.state()["throttle"]
        self.assertEqual(falling, 0.0)

    def test_config_exposes_original_dash_thresholds(self) -> None:
        config = self.simulation.config()
        self.assertEqual(config["defaultCarId"], "tatuusfa1")
        self.assertEqual(config["car"]["id"], "tatuusfa1")
        self.assertEqual(config["car"]["name"], "Tatuus FA01")
        self.assertEqual(config["engine"]["limiterRpm"], 6500)
        self.assertEqual(config["engine"]["shiftLights"], [5800, 5900, 6000, 6100, 6200])
        self.assertTrue(config["transmission"]["automatic"])
        self.assertEqual(config["transmission"]["forwardRatios"], [2.36, 1.94, 1.56, 1.29, 1.1, 0.92])
        self.assertEqual(config["transmission"]["upshiftRpm"], 6300)
        self.assertEqual(config["transmission"]["downshiftRpm"], 4500)
        self.assertEqual(config["audioScope"], "powertrain")
        self.assertIn("transmission", config["soundEvents"])
        self.assertNotIn("wheel", config["soundEvents"])
        self.assertEqual([mic["id"] for mic in config["microphones"]], ["cockpit", "bonnet", "exhaust"])


if __name__ == "__main__":
    unittest.main()
