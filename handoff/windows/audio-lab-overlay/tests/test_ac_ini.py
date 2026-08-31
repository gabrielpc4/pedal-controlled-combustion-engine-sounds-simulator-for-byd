from __future__ import annotations

import unittest

from sim.ac_ini import parse_ac_ini


class AssettoIniTests(unittest.TestCase):
    def test_ignores_standalone_mod_editor_labels(self) -> None:
        parsed = parse_ac_ini(
            """
            VERSION=1
            [GRAPHICS]
            DRIVEREYES=0,0.7,0
            DAMAGE
            KMH
            BONNET_CAMERA_POS=0,0.8,1
            """
        )

        self.assertEqual(parsed.getint("HEADER", "VERSION"), 1)
        self.assertEqual(parsed.get("GRAPHICS", "DRIVEREYES"), "0,0.7,0")
        self.assertEqual(parsed.get("GRAPHICS", "BONNET_CAMERA_POS"), "0,0.8,1")
        self.assertFalse(parsed.has_option("GRAPHICS", "DAMAGE"))


if __name__ == "__main__":
    unittest.main()
