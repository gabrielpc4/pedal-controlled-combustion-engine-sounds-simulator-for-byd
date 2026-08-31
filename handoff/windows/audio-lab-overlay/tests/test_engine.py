from __future__ import annotations

import unittest

from sim.assetto import find_assetto_root
from sim.car_config import load_car_spec
from sim.engine import FreeRevEngine


class ExactNeutralEngineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.spec = load_car_spec(find_assetto_root())
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc

    def make_engine(self) -> FreeRevEngine:
        return FreeRevEngine(self.spec)

    def test_zero_pedal_holds_configured_idle(self) -> None:
        engine = self.make_engine()
        for _ in range(1000):
            frame = engine.step(0.003)
        self.assertAlmostEqual(frame.rpm, 1250.0, places=7)
        self.assertAlmostEqual(frame.torque, 0.0, places=7)

    def test_player_pedal_and_first_neutral_step_are_immediate(self) -> None:
        engine = self.make_engine()
        engine.set_throttle(1.0)
        frame = engine.step(0.003)
        self.assertEqual(frame.throttle, 1.0)
        self.assertEqual(frame.mapped_throttle, 1.0)
        self.assertAlmostEqual(frame.rpm, 1266.73, delta=0.03)

    def test_turbo_lag_is_dt_times_ini_rate(self) -> None:
        engine = self.make_engine()
        engine.rpm = 2000.0
        engine.set_throttle(1.0)
        frame = engine.step(0.003)
        expected_q = 0.003 * self.spec.turbo.lag_up  # target is one at reference RPM
        self.assertAlmostEqual(frame.boost, self.spec.turbo.maximum_boost * expected_q, places=8)

    def test_coast_generator_hits_reference_torque(self) -> None:
        engine = self.make_engine()
        self.assertAlmostEqual(engine._coast_torque(1250), 0.0)
        self.assertAlmostEqual(engine._coast_torque(7000), -60.0)

    def test_limiter_counter_and_steady_bounce(self) -> None:
        engine = self.make_engine()
        engine.rpm = 6501.0
        engine.set_throttle(0.0)
        active_steps = 0
        while engine.step(0.003).limiter_active:
            active_steps += 1
            self.assertLess(active_steps, 30)
        self.assertEqual(active_steps, 11)

        engine = self.make_engine()
        engine.set_throttle(1.0)
        last_second: list[float] = []
        for index in range(1500):
            frame = engine.step(0.003)
            if index >= 1167:
                last_second.append(frame.rpm)
        self.assertLessEqual(max(last_second), 6530)
        self.assertGreaterEqual(min(last_second), 6350)
        self.assertTrue(any(rpm < self.spec.limiter_rpm for rpm in last_second))
        self.assertTrue(any(rpm > self.spec.limiter_rpm for rpm in last_second))

    def test_rapid_lift_triggers_configured_backfire_gate(self) -> None:
        engine = self.make_engine()
        engine.set_throttle(1.0)
        for _ in range(400):
            engine.step(0.003)
        self.assertGreater(engine.rpm, self.spec.backfire.minimum_rpm)
        # The executable rejects exactly zero. A real/analog pedal crosses a
        # small positive value on release, which fires the armed event once.
        engine.set_throttle(0.0)
        self.assertFalse(engine.step(0.003).backfire_triggered)
        engine.set_throttle(0.1)
        self.assertTrue(engine.step(0.003).backfire_triggered)
        self.assertFalse(engine.step(0.003).backfire_triggered)

    def test_backfire_fuel_timer_starts_only_after_arming(self) -> None:
        engine = self.make_engine()
        for _ in range(400):
            engine.step(0.003)
        engine.set_throttle(1.0)
        for _ in range(100):
            engine.step(0.003)
        engine.set_throttle(0.1)
        self.assertFalse(engine.step(0.003).backfire_triggered)


if __name__ == "__main__":
    unittest.main()
