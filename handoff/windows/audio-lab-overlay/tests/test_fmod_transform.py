from __future__ import annotations

import unittest

from sim.fmod_native import _attributes


class FmodTransformTests(unittest.TestCase):
    def test_assetto_negates_car_z_for_fmod_forward(self) -> None:
        attributes = _attributes((1.0, 2.0, 3.0))
        self.assertEqual(
            (attributes.position.x, attributes.position.y, attributes.position.z),
            (1.0, 2.0, 3.0),
        )
        self.assertEqual(
            (attributes.forward.x, attributes.forward.y, attributes.forward.z),
            (0.0, 0.0, -1.0),
        )
        self.assertEqual(
            (attributes.up.x, attributes.up.y, attributes.up.z),
            (0.0, 1.0, 0.0),
        )


if __name__ == "__main__":
    unittest.main()
