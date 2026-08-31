from __future__ import annotations

import unittest
from types import SimpleNamespace

from sim.fmod_native import (
    CORE_EVENT_NAMES,
    EFFECT_OUTPUT_SCALE,
    ENGINE_OUTPUT_SCALE,
    FULL_LOAD_AUDIO_THROTTLE,
    MAX_BACKFIRE_AUDIO_THROTTLE,
    NativeFmodAudio,
    event_output_gain,
)


class AudioPolicyTests(unittest.TestCase):
    def test_hosted_core_event_set_includes_every_drivetrain_event(self) -> None:
        self.assertTrue(
            {
                "start",
                "transmission_ext",
                "tractioncontrol_int",
                "tractioncontrol_ext",
                "gear_grind",
            }.issubset(CORE_EVENT_NAMES)
        )

    def test_engine_and_transmission_stay_at_full_load(self) -> None:
        self.assertEqual(FULL_LOAD_AUDIO_THROTTLE, 1.0)

        for throttle in (0.0, 0.37, 1.0):
            audio, parameters = self.make_audio_bridge()

            audio.update(self.make_frame(throttle=throttle), 0.003)

            for event in (
                "engine_int",
                "engine_ext",
                "transmission",
                "transmission_ext",
            ):
                with self.subTest(throttle=throttle, event=event):
                    self.assertEqual(parameters[(event, "throttle")], 1.0)

    def test_backfire_uses_its_loud_low_throttle_endpoint(self) -> None:
        self.assertEqual(MAX_BACKFIRE_AUDIO_THROTTLE, 0.01)

        audio, parameters = self.make_audio_bridge()
        audio.update(self.make_frame(throttle=0.62), 0.003)

        self.assertEqual(parameters[("backfire_int", "throttle")], 0.01)
        self.assertEqual(parameters[("backfire_ext", "throttle")], 0.01)

    def test_engine_is_half_and_effects_are_unity_gain(self) -> None:
        self.assertEqual(ENGINE_OUTPUT_SCALE, 0.5)
        self.assertEqual(EFFECT_OUTPUT_SCALE, 1.0)
        self.assertEqual(event_output_gain("engine_int", 1.0), 0.5)
        self.assertEqual(event_output_gain("engine_ext", 1.0), 0.5)
        for event in (
            "transmission",
            "transmission_ext",
            "turbo",
            "limiter",
            "gear_int",
            "gear_ext",
            "gear_grind",
            "tractioncontrol_int",
            "tractioncontrol_ext",
            "start",
            "backfire_int",
            "backfire_ext",
        ):
            with self.subTest(event=event):
                self.assertEqual(event_output_gain(event, 1.0), 1.0)

    def test_master_mute_and_engine_isolation_still_apply(self) -> None:
        self.assertEqual(event_output_gain("backfire_ext", 1.0, muted=True), 0.0)
        self.assertEqual(event_output_gain("engine_ext", 1.0, engine_muted=True), 0.0)
        self.assertEqual(event_output_gain("transmission", 1.0, engine_muted=True), 0.0)
        self.assertEqual(event_output_gain("transmission_ext", 1.0, engine_muted=True), 0.0)
        self.assertEqual(event_output_gain("backfire_ext", 1.0, engine_muted=True), 1.0)

    def test_transmission_pair_tracks_the_selected_camera_with_exact_fallback(self) -> None:
        audio, _parameters = self.make_audio_bridge()
        self.assertEqual(
            audio._perspective_event("transmission", "transmission_ext"),
            "transmission",
        )

        audio.camera = "bonnet"
        self.assertEqual(
            audio._perspective_event("transmission", "transmission_ext"),
            "transmission_ext",
        )

        del audio._instances["transmission_ext"]
        self.assertEqual(
            audio._perspective_event("transmission", "transmission_ext"),
            "transmission",
        )

    def test_traction_starts_only_the_selected_perspective_from_real_limit_state(self) -> None:
        audio, parameters = self.make_audio_bridge()
        started: list[str] = []
        audio._event_is_playing = lambda _name: False
        audio._start_event = started.append

        audio.update(
            self.make_frame(throttle=0.8, traction_limit_active=True),
            0.01,
        )

        self.assertEqual(started, ["tractioncontrol_int"])
        self.assertEqual(parameters[("tractioncontrol_int", "decay")], 0.0)
        self.assertEqual(parameters[("tractioncontrol_ext", "decay")], 0.0)

    def test_gear_grind_fires_only_for_a_rejected_shift_pulse(self) -> None:
        audio, _parameters = self.make_audio_bridge()
        started: list[str] = []
        audio._event_is_playing = lambda _name: False
        audio._start_event = started.append

        audio.update(self.make_frame(throttle=0.4), 0.01)
        self.assertNotIn("gear_grind", started)

        audio.update(
            self.make_frame(throttle=0.4, shift_rejected=True),
            0.01,
        )
        self.assertEqual(started, ["gear_grind"])

    @staticmethod
    def make_frame(*, throttle: float, **overrides: object) -> SimpleNamespace:
        values = dict(
            rpm=3200.0,
            throttle=throttle,
            driver_throttle=0.91,
            mapped_throttle=0.73,
            effective_throttle=0.54,
            drivetrain_speed=42.0,
            boost=0.0,
            bov=0.0,
            limiter_pulse=False,
            backfire_triggered=False,
            shift_started=False,
            shift_rejected=False,
            traction_limit_active=False,
            traction_limit_pulse=False,
        )
        values.update(overrides)
        return SimpleNamespace(**values)

    @staticmethod
    def make_audio_bridge() -> tuple[NativeFmodAudio, dict[tuple[str, str], float]]:
        audio = object.__new__(NativeFmodAudio)
        audio._closed = False
        audio._instances = {
            name: name
            for name in (
                "engine_int",
                "engine_ext",
                "transmission",
                "transmission_ext",
                "tractioncontrol_int",
                "tractioncontrol_ext",
                "gear_grind",
                "backfire_int",
                "backfire_ext",
            )
        }
        audio._limiter_decay = 10.0
        audio._limiter_running = False
        audio._traction_decay = 10.0
        audio._bov_decay = 10.0
        audio.camera = "cockpit"
        audio.spec = SimpleNamespace(turbo=None)
        audio._system = object()
        audio._studio = SimpleNamespace(FMOD_Studio_System_Update=lambda _system: 0)

        parameters: dict[tuple[str, str], float] = {}
        audio._set_parameter = lambda instance, name, value: parameters.__setitem__(
            (instance, name), value
        )
        audio._apply_gains = lambda: None
        audio._check = lambda _result, _operation: None

        return audio, parameters


if __name__ == "__main__":
    unittest.main()
