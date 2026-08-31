from __future__ import annotations

import unittest

from fh6.input import PredictiveResampler, VehicleSample


class PredictiveResamplerTests(unittest.TestCase):
    def test_first_sample_has_no_intentional_buffer(self) -> None:
        resampler = PredictiveResampler()
        sample = VehicleSample(1_000_000_000, 37.125, 31.5, 0.0)
        self.assertTrue(resampler.submit(sample))
        frame = resampler.sample(1_001_000_000, 0.001)
        self.assertAlmostEqual(frame.speed_kph, 37.125, places=6)
        self.assertEqual(frame.throttle_pct, 31.5)

    def test_prediction_is_limited_to_one_hundred_ms(self) -> None:
        resampler = PredictiveResampler(max_jerk_mps3=1e9)
        resampler.submit(VehicleSample(1_000_000_000, 10.0, 20.0, 0.0))
        resampler.submit(VehicleSample(1_100_000_000, 20.0, 20.0, 0.0))
        near = resampler.sample(1_200_000_000, 0.001)
        far = resampler.sample(1_700_000_000, 0.001)
        self.assertLessEqual(near.speed_kph, 30.0 + 1e-6)
        self.assertTrue(far.stale)

    def test_irregular_or_out_of_order_timestamp_is_rejected(self) -> None:
        resampler = PredictiveResampler()
        self.assertTrue(resampler.submit(VehicleSample(100, 1.0, 0.0, 0.0)))
        self.assertFalse(resampler.submit(VehicleSample(100, 2.0, 0.0, 0.0)))
        self.assertFalse(resampler.submit(VehicleSample(90, 3.0, 0.0, 0.0)))

    def test_dropout_state_is_safe_and_finite(self) -> None:
        resampler = PredictiveResampler()
        resampler.submit(VehicleSample(1_000_000_000, 80.25, 0.0, 20.0))
        frame = resampler.sample(3_100_000_000, 0.001)
        self.assertTrue(frame.stale)
        self.assertTrue(frame.dropout)
        self.assertGreaterEqual(frame.speed_kph, 0.0)


if __name__ == "__main__":
    unittest.main()
