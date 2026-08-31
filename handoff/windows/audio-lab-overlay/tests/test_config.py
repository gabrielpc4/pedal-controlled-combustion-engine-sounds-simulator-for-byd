from __future__ import annotations

import unittest

from sim.assetto import find_assetto_root
from sim.car_config import interpolate_curve, load_car_spec


class InstalledTatuusDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.root = find_assetto_root()
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc
        cls.spec = load_car_spec(cls.root)

    def test_exact_engine_and_instrument_values(self) -> None:
        spec = self.spec
        self.assertEqual(spec.display_name, "Tatuus FA01")
        self.assertEqual(spec.idle_rpm, 1250)
        self.assertEqual(spec.limiter_rpm, 6500)
        self.assertEqual(spec.limiter_hz, 30)
        self.assertAlmostEqual(spec.engine_inertia, 0.120)
        self.assertAlmostEqual(spec.gearbox_inertia, 0.018)
        self.assertEqual(spec.tachometer_maximum, 7000)
        self.assertEqual(spec.shift_lights, (5800, 5900, 6000, 6100, 6200))
        self.assertEqual(spec.shift_blink_rpm, 6300)
        self.assertEqual(spec.shift_blink_hz, 5)
        self.assertEqual(spec.backfire.maximum_gas, 0.3)

    def test_exact_camera_and_emitter_inputs(self) -> None:
        spec = self.spec
        self.assertEqual(spec.engine_position, "rear")
        self.assertEqual(spec.bonnet_camera, (0.0, 0.7, 0.1))
        self.assertAlmostEqual(spec.wheelbase, 2.65)
        self.assertAlmostEqual(spec.cg_location, 0.41)
        self.assertAlmostEqual(-(spec.wheelbase * spec.cg_location) + 0.5, -0.5865)

    def test_lut_interpolation_matches_known_points(self) -> None:
        self.assertEqual(interpolate_curve(self.spec.torque_curve, 3500), 129)
        self.assertAlmostEqual(interpolate_curve(self.spec.torque_curve, 3750), 121.5)
        self.assertEqual(interpolate_curve(self.spec.throttle_curve, 0.5), 0.75)
        self.assertAlmostEqual(interpolate_curve(self.spec.throttle_curve, 0.55), 0.775)


if __name__ == "__main__":
    unittest.main()
