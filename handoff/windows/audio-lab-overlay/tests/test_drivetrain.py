from __future__ import annotations

import unittest

from sim.assetto import find_assetto_root
from sim.ac_ini import parse_ac_ini
from sim.car_config import load_car_spec
from sim.drivetrain import AutomaticDrivetrain, _final_drive_ratio, load_drivetrain_spec


class FinalDriveConfigurationTests(unittest.TestCase):
    def test_valid_drivetrain_final_remains_authoritative(self) -> None:
        drivetrain = parse_ac_ini("[GEARS]\nFINAL=3.9\n")

        self.assertEqual(_final_drive_ratio({}, drivetrain), 3.9)

    def test_invalid_drivetrain_final_uses_first_authored_setup_option(self) -> None:
        drivetrain = parse_ac_ini("[GEARS]\nFINAL=9999\n")
        files = {
            "setup.ini": b"[FINAL_GEAR_RATIO]\nRATIOS=final.rto\n",
            "final.rto": b"Stock|5.2\nShort|5.4\nShorter|5.6\n",
        }

        self.assertEqual(_final_drive_ratio(files, drivetrain), 5.2)

    def test_invalid_drivetrain_final_without_authored_setup_fails_closed(self) -> None:
        drivetrain = parse_ac_ini("[GEARS]\nFINAL=9999\n")

        with self.assertRaisesRegex(ValueError, "setup.ini is missing"):
            _final_drive_ratio({}, drivetrain)


class TatuusAutomaticDrivetrainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.root = find_assetto_root()
            cls.engine_spec = load_car_spec(cls.root, "tatuusfa1")
            cls.drivetrain_spec = load_drivetrain_spec(cls.root, "tatuusfa1")
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc

    def make_sim(self, gear: int = 1) -> AutomaticDrivetrain:
        sim = AutomaticDrivetrain(self.engine_spec, self.drivetrain_spec, initial_gear=gear)
        sim._session_elapsed_ms = 301.0
        return sim

    def test_stock_values_and_float_derived_shift_times(self) -> None:
        spec = self.drivetrain_spec
        self.assertEqual(spec.forward_ratios, (2.36, 1.94, 1.56, 1.29, 1.10, 0.92))
        self.assertEqual(spec.reverse_ratio, -3.818)
        self.assertEqual(spec.final_drive, 3.10)
        self.assertAlmostEqual(spec.gear_up_time_s, 0.1300000101327896)
        self.assertAlmostEqual(spec.gear_down_time_s, 0.18000000715255737)
        self.assertEqual(spec.auto_up_rpm, 6300)
        self.assertEqual(spec.auto_down_rpm, 4500)
        self.assertEqual(spec.auto_gas_cutoff_s, 0.28)
        self.assertEqual(spec.vehicle.mass_kg, 530)
        self.assertEqual(spec.vehicle.rear_wheel_radius_m, 0.285)

    def test_neutral_has_no_launch_rule_below_upshift_rpm(self) -> None:
        sim = self.make_sim(gear=0)
        sim.rpm = 6299.0
        sim.set_throttle(1.0)
        frame = sim.step(0.003)
        self.assertFalse(frame.shift_started)
        self.assertEqual(frame.gear, 0)

        sim.rpm = 6301.0
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_started)
        self.assertTrue(frame.gear_changed)
        self.assertEqual(frame.gear_direction, 1)
        self.assertEqual(frame.gear, 0)
        self.assertEqual(frame.requested_gear, 1)

    def test_upshift_enters_neutral_immediately_and_completes_on_strict_timer(self) -> None:
        sim = self.make_sim(gear=1)
        sim.rpm = 6301.0
        sim._clutch_signal = 1.0
        sim.set_throttle(1.0)
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_started)
        self.assertTrue(frame.auto_gas_cut_active)
        self.assertEqual(frame.effective_throttle, 0.0)
        self.assertEqual(frame.driver_throttle, 1.0)
        self.assertEqual(frame.throttle, 0.0)
        self.assertEqual(frame.gear, 0)
        self.assertEqual(frame.requested_gear, 2)
        self.assertEqual(frame.shift_phase, "neutral_up")

        # Accepted frame is timer tick one.  Float32 0.003 reaches .132 on
        # tick 44, and AC completes on tick 45 because duration < elapsed.
        for _ in range(43):
            frame = sim.step(0.003)
            self.assertFalse(frame.shift_completed)
        self.assertTrue(frame.shifting)
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_completed)
        self.assertTrue(frame.gear_engaged)
        self.assertEqual(frame.gear, 2)
        self.assertEqual(frame.gear_direction, 1)

    def test_second_gear_uses_calculated_threshold_from_start(self) -> None:
        sim = self.make_sim(gear=2)
        sim.set_speed(10.0)
        sim._clutch_signal = 1.0
        sim.set_throttle(0.5)
        calculated = self.drivetrain_spec.auto_up_rpm * 1.94 / 2.36
        sim.rpm = calculated + 1.0
        self.assertFalse(sim.step(0.003).shift_started)

        sim.set_throttle(0.0)
        sim.rpm = calculated - 1.0
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_started)
        self.assertEqual(frame.requested_gear, 1)
        self.assertEqual(frame.gear_direction, -1)

    def test_calculated_landing_rpm_directly_controls_downshift(self) -> None:
        sim = self.make_sim(gear=2)
        sim.set_speed(20.0)
        sim._clutch_signal = 1.0
        sim._upshift_landing_rpm[2] = 5500.0
        sim.set_throttle(0.0)

        sim.rpm = 5501.0
        self.assertFalse(sim.step(0.003).shift_started)

        sim.rpm = 5499.0
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_started)
        self.assertEqual(frame.requested_gear, 1)

    def test_calculated_threshold_does_not_reverse_while_throttle_is_held(self) -> None:
        sim = self.make_sim(gear=2)
        sim.set_speed(20.0)
        sim._clutch_signal = 1.0
        sim._upshift_landing_rpm[2] = 5500.0
        sim.rpm = 5300.0
        sim.set_throttle(1.0)
        self.assertFalse(sim.step(0.003).shift_started)

    def test_ratio_calculation_is_available_before_first_upshift(self) -> None:
        sim = self.make_sim(gear=2)
        expected = self.drivetrain_spec.auto_up_rpm * 1.94 / 2.36
        self.assertAlmostEqual(sim._upshift_landing_rpm[2], expected)
        self.assertAlmostEqual(sim.automatic_downshift_rpm, expected)

    def test_low_speed_lift_requests_downshift_independent_of_rpm(self) -> None:
        sim = self.make_sim(gear=3)
        sim.set_speed(1.0)
        sim.rpm = 5000.0
        sim._clutch_signal = 1.0
        sim.set_throttle(0.0)
        frame = sim.step(0.003)
        self.assertTrue(frame.shift_started)
        self.assertEqual(frame.requested_gear, 2)

    def test_downshift_clutch_profile_starts_on_frame_after_request(self) -> None:
        sim = self.make_sim(gear=2)
        sim.set_auto_shift(False)
        sim.set_speed(10.0)
        sim.rpm = 4000.0
        sim._clutch_signal = 1.0
        sim.request_shift(-1)
        request = sim.step(0.003)
        self.assertTrue(request.shift_started)
        self.assertEqual(request.clutch, 1.0)

        first = sim.step(0.003)
        second = sim.step(0.003)
        self.assertEqual(first.clutch, 1.0)
        self.assertAlmostEqual(second.clutch, 0.7, delta=0.02)
        self.assertTrue(second.autoblip_active)

    def test_auto_shifter_does_not_make_nested_request_during_change(self) -> None:
        sim = self.make_sim(gear=2)
        sim.set_auto_shift(False)
        sim.set_speed(10.0)
        sim.rpm = 4000.0
        sim._clutch_signal = 1.0
        sim.request_shift(-1)
        self.assertTrue(sim.step(0.003).shift_started)

        sim.set_auto_shift(True)
        sim.rpm = 6400.0
        sim.set_throttle(1.0)
        during = sim.step(0.003)
        self.assertTrue(during.shifting)
        self.assertFalse(during.shift_started)
        self.assertFalse(during.auto_gas_cut_active)

    def test_rejected_shift_has_one_frame_pulse_and_accepted_shift_does_not(self) -> None:
        sim = self.make_sim(gear=self.drivetrain_spec.maximum_gear)
        sim.set_auto_shift(False)
        sim.request_shift(1)

        rejected = sim.step(0.003)
        self.assertTrue(rejected.shift_rejected)
        self.assertFalse(rejected.shift_started)
        self.assertFalse(sim.step(0.003).shift_rejected)

        sim.set_gear(2)
        sim.request_shift(-1)
        accepted = sim.step(0.003)
        self.assertTrue(accepted.shift_started)
        self.assertFalse(accepted.shift_rejected)

    def test_traction_limit_pulse_is_the_rising_edge_of_real_slip_state(self) -> None:
        sim = self.make_sim(gear=1)
        sim.set_auto_shift(False)
        ratio = abs(
            self.drivetrain_spec.ratio_for_gear(1)
            * self.drivetrain_spec.final_drive
        )
        synchronized_speed = (
            sim.rpm
            / (60.0 / (2.0 * 3.141592653589793))
            / ratio
            * self.drivetrain_spec.vehicle.rear_wheel_radius_m
        )
        sim.set_speed(synchronized_speed)
        sim._clutch_signal = 1.0
        sim.set_throttle(0.05)
        sim.set_driving_tyre_slip(self.drivetrain_spec.auto_slip_threshold + 0.1)

        first = sim.step(0.003)
        second = sim.step(0.003)

        self.assertTrue(first.traction_limit_active)
        self.assertTrue(first.traction_limit_pulse)
        self.assertTrue(second.traction_limit_active)
        self.assertFalse(second.traction_limit_pulse)

    def test_keyboard_brake_has_no_release_filter(self) -> None:
        sim = self.make_sim()
        sim.set_keyboard_brake(True)
        held = sim.step(0.003)
        self.assertGreater(held.brake, 0.0)
        self.assertLessEqual(held.brake, 1.0)
        sim.set_keyboard_brake(False)
        released = sim.step(0.003)
        self.assertEqual(released.brake, 0.0)

    def test_drivetrain_speed_is_driven_shaft_radians_per_second(self) -> None:
        sim = self.make_sim()
        sim.set_speed(28.5)
        frame = sim.step(0.003)
        self.assertAlmostEqual(frame.drivetrain_speed, frame.speed_mps / 0.285, places=7)
        self.assertEqual(frame.speed_kph, frame.speed_kmh)

    def test_audio_transients_keep_free_rev_detector_semantics(self) -> None:
        sim = self.make_sim(gear=0)
        sim.set_auto_shift(False)
        sim.set_throttle(1.0)
        for _ in range(400):
            frame = sim.step(0.003)
        self.assertGreater(frame.rpm, self.engine_spec.backfire.minimum_rpm)
        self.assertGreater(frame.boost, 0.0)

        # Exactly zero is rejected by AC's backfire detector.  A following
        # small positive release sample crosses its peak-relative fire gate.
        sim.set_throttle(0.0)
        lift = sim.step(0.003)
        self.assertFalse(lift.backfire_triggered)
        sim.set_throttle(0.1)
        release_sample = sim.step(0.003)
        self.assertTrue(release_sample.backfire_triggered)
        self.assertIn(release_sample.limiter_pulse, (True, False))
        self.assertGreaterEqual(release_sample.bov_decay, 0.0)

if __name__ == "__main__":
    unittest.main()
