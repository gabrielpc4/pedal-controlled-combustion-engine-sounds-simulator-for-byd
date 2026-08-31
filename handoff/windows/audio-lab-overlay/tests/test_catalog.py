from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from sim.assetto import find_assetto_root
from sim.catalog import discover_bank_library, discover_cars
from sim.drivetrain import AutomaticDrivetrain, load_drivetrain_spec
from sim.car_config import load_car_spec


class StandaloneBankCatalogTests(unittest.TestCase):
    def test_standalone_bank_library_exposes_every_bank_file(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("ks_audi_tt_cup.bank", "ferrari_458.bank", "readme.txt"):
                (root / name).touch()

            entries = discover_bank_library(root)

        self.assertEqual(["ferrari_458", "ks_audi_tt_cup"], [entry.id for entry in entries])
        self.assertTrue(all(entry.available for entry in entries))
        self.assertTrue(all("reference-car physics" in entry.quirks for entry in entries))


class InstalledCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = find_assetto_root(None)
        cls.cars = discover_cars(cls.root)

    def test_all_complete_kunos_cars_load(self) -> None:
        failures = [car for car in self.cars if car.official and not car.available and "no data folder" not in car.error]
        self.assertEqual([], failures)

    def test_fwd_awd_na_and_turbo_variants_advance(self) -> None:
        ids = ("abarth500", "ks_audi_sport_quattro", "ferrari_458", "tatuusfa1")
        for car_id in ids:
            with self.subTest(car=car_id):
                spec = load_car_spec(self.root, car_id)
                drive = load_drivetrain_spec(self.root, car_id)
                frame = AutomaticDrivetrain(spec, drive, initial_gear=1).step(0.003)
                self.assertGreater(frame.rpm, 0)

    def test_multi_turbo_sections_are_not_collapsed(self) -> None:
        spec = load_car_spec(self.root, "bmw_1m")
        self.assertEqual(2, len(spec.turbos))
        self.assertAlmostEqual(sum(t.maximum_boost for t in spec.turbos), spec.turbo.maximum_boost)
        self.assertAlmostEqual(sum(t.display_max_boost for t in spec.turbos), spec.turbo.display_max_boost)
