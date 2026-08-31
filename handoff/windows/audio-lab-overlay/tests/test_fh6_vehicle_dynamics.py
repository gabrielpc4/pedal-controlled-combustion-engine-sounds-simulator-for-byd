from __future__ import annotations

import unittest

from fh6.input import Selector
from fh6.vehicle_dynamics import BYDSealAWDModel


class BYDSealAWDDynamicsTests(unittest.TestCase):
    def test_chart_calibrated_zero_to_100(self) -> None:
        model = BYDSealAWDModel()
        elapsed = 0.0
        while model.frame.speed_kph < 100.0 and elapsed < 8.0:
            model.step(0.001, 100.0, 0.0, ignition=True, selector=Selector.DRIVE)
            elapsed += 0.001
        self.assertAlmostEqual(elapsed, model.spec.target_zero_to_100_s, delta=0.08)

    def test_lift_off_regeneration_slows_vehicle_without_brake(self) -> None:
        model = BYDSealAWDModel()
        model.set_speed(100.0)
        before = model.speed_mps
        frame = model.step(0.1, 0.0, 0.0, ignition=True, selector=Selector.DRIVE)
        self.assertLess(model.speed_mps, before)
        self.assertGreater(frame.regen_force_n, 0.0)
        self.assertGreater(frame.regen_power_kw, 0.0)
        self.assertEqual(frame.friction_brake_force_n, 0.0)
        self.assertGreater(frame.recovered_energy_kwh, 0.0)

    def test_brake_blends_regen_and_friction(self) -> None:
        model = BYDSealAWDModel()
        model.set_speed(80.0)
        frame = model.step(0.01, 0.0, 100.0, ignition=True, selector=Selector.DRIVE)
        self.assertGreater(frame.regen_force_n, 0.0)
        self.assertGreater(frame.friction_brake_force_n, 0.0)
        self.assertLess(frame.acceleration_mps2, -5.0)

    def test_park_and_ignition_off_cannot_produce_drive_force(self) -> None:
        model = BYDSealAWDModel()
        parked = model.step(0.1, 100.0, 0.0, ignition=True, selector=Selector.PARK)
        off = model.step(0.1, 100.0, 0.0, ignition=False, selector=Selector.DRIVE)
        self.assertEqual(parked.drive_force_n, 0.0)
        self.assertEqual(off.drive_force_n, 0.0)

    def test_governor_caps_speed(self) -> None:
        model = BYDSealAWDModel()
        model.set_speed(189.9)
        for _ in range(1000):
            model.step(0.001, 100.0, 0.0, ignition=True, selector=Selector.DRIVE)
        self.assertLessEqual(model.frame.speed_kph, model.spec.maximum_speed_kph)


if __name__ == "__main__":
    unittest.main()
