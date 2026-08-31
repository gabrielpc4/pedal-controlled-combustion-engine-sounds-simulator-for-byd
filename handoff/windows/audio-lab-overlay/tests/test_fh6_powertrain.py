from __future__ import annotations

import unittest

from fh6.config import find_fh6_root, load_reference_config
from fh6.input import PowertrainControl, ResampledVehicle, Selector
from fh6.powertrain import FH6Powertrain


def vehicle(speed: float, throttle: float, brake: float = 0.0) -> ResampledVehicle:
    return ResampledVehicle(speed, throttle, brake, 0.0, 0.0, False, False)


class FH6PowertrainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.config = load_reference_config(find_fh6_root())
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc

    def test_ignition_startup_is_one_shot_and_park_free_revs(self) -> None:
        powertrain = FH6Powertrain(self.config)
        self.assertTrue(powertrain.set_control(PowertrainControl(True, Selector.PARK), 0.0))
        first = powertrain.step(0.001, vehicle(0.0, 80.0))
        second = powertrain.step(0.001, vehicle(0.0, 80.0))
        self.assertTrue(first.startup_triggered)
        self.assertFalse(second.startup_triggered)
        self.assertEqual(first.gear, 0)
        self.assertGreaterEqual(second.rpm, self.config.idle_rpm)

    def test_drive_speed_is_authoritative(self) -> None:
        powertrain = FH6Powertrain(self.config)
        powertrain.set_control(PowertrainControl(True, Selector.DRIVE), 40.125)
        low = powertrain.step(0.001, vehicle(40.125, 0.0))
        loaded = powertrain.step(0.001, vehicle(40.125, 100.0))
        self.assertAlmostEqual(low.speed_kph, loaded.speed_kph)
        self.assertAlmostEqual(low.road_coupled_rpm, loaded.road_coupled_rpm)

    def test_moving_park_and_reverse_are_rejected(self) -> None:
        powertrain = FH6Powertrain(self.config)
        powertrain.set_control(PowertrainControl(True, Selector.DRIVE), 20.0)
        self.assertFalse(powertrain.set_control(PowertrainControl(True, Selector.REVERSE), 20.0))
        self.assertFalse(powertrain.set_control(PowertrainControl(True, Selector.PARK), 20.0))
        self.assertEqual(powertrain.control.selector, Selector.DRIVE)

    def test_upshift_records_ratio_landing_and_downshifts_without_hysteresis(self) -> None:
        powertrain = FH6Powertrain(self.config)
        powertrain.set_control(PowertrainControl(True, Selector.DRIVE), 0.0)
        ratio1 = self.config.drivetrain.ratios[0]
        circumference = 2.0 * 3.141592653589793 * self.config.drivetrain.driven_tyre_radius_m
        speed = self.config.drivetrain.upshift_rpm[0] / 60.0 * circumference / (ratio1 * self.config.drivetrain.final_drive) * 3.6
        request = powertrain.step(0.001, vehicle(speed, 80.0))
        self.assertTrue(request.shift_started)
        self.assertEqual(request.shift_direction, 1)
        for _ in range(200):
            engaged = powertrain.step(0.001, vehicle(speed, 80.0))
        self.assertEqual(engaged.gear, 2)
        landing = powertrain._landing_rpm[2]
        ratio2 = self.config.drivetrain.ratios[1]
        landing_speed = landing / 60.0 * circumference / (ratio2 * self.config.drivetrain.final_drive) * 3.6
        down = powertrain.step(0.001, vehicle(landing_speed, 0.0))
        self.assertTrue(down.shift_started, "the exact calculated landing RPM must pass with no hysteresis")
        self.assertEqual(down.shift_direction, -1)

    def test_lift_drives_bov_burble_backfire_and_antilag_signals(self) -> None:
        powertrain = FH6Powertrain(self.config)
        powertrain.set_control(PowertrainControl(True, Selector.DRIVE), 100.0)
        for _ in range(1000):
            powertrain.step(0.001, vehicle(100.0, 100.0))
        lift = powertrain.step(0.001, vehicle(100.0, 0.0))
        self.assertTrue(lift.bov_triggered)
        self.assertTrue(lift.burble_triggered)
        self.assertTrue(lift.backfire_triggered)
        self.assertTrue(lift.anti_lag_active)


if __name__ == "__main__":
    unittest.main()
