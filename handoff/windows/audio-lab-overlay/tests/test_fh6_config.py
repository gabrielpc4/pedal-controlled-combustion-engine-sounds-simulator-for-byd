from __future__ import annotations

import unittest

from fh6.config import find_fh6_root, load_reference_config


class FH6ConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.root = find_fh6_root()
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc

    def test_stock_supra_authored_stack_is_read_from_install(self) -> None:
        config = load_reference_config(self.root)
        self.assertEqual(config.car_id, "TOY_SupraRZ_98")
        self.assertEqual(config.rpm_scalar, 0.95)
        self.assertEqual(config.synths["Engine"].name, "G_I6TC_Asian_Street_6_Eng")
        self.assertEqual(config.synths["Exhaust"].name, "G_I6TC_Asian_Street_11_Exh")
        self.assertEqual(config.synths["Intake"].name, "G_I6TTC_Asian_Street_1_Int")
        self.assertEqual(config.synths["Turbo"].name, "Turbo_Turbine_SportsCar_Tbo")
        self.assertTrue(all(item.installed for item in config.synths.values()))
        self.assertEqual(config.effects["Backfire"], "I6")
        self.assertEqual(config.effects["Burbles"], "ModernI6SportsCar")
        self.assertEqual(config.effects["TurboBOV"], "JDM")
        self.assertEqual(config.effects["GearCrack"], "Manual")
        self.assertEqual(config.startup_event, "AV_STARTUP_ClassicSportsCar_01")

    def test_drivetrain_is_visibly_gated_until_local_database_decode(self) -> None:
        config = load_reference_config(self.root)
        self.assertFalse(config.drivetrain.exact_from_installed_database)
        self.assertIn("engineering seed", config.drivetrain.source)


if __name__ == "__main__":
    unittest.main()
