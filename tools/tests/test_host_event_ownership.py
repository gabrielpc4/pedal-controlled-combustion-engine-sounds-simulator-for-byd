from __future__ import annotations

import unittest

from tools.profile_generation.generate_android_profile_recipe import (
    _effect_perspective,
    _event_instance_ownership,
    _host_event_ownership_evidence,
)


class HostEventOwnershipTests(unittest.TestCase):
    def test_new_core_host_events_are_runtime_delivered(self) -> None:
        for suffix in (
            "start",
            "transmission_ext",
            "tractioncontrol_int",
            "tractioncontrol_ext",
            "gear_grind",
        ):
            with self.subTest(suffix=suffix):
                host = _host_event_ownership_evidence(suffix)
                self.assertEqual(host["status"], "instantiated")
                self.assertEqual(host["runtimeDelivery"], "atlasRuntime")
                self.assertEqual(
                    _event_instance_ownership(suffix)["owner"],
                    "profileAudioSessionPersistentEventInstance",
                )

    def test_unknown_mechanical_event_remains_conservation_only(self) -> None:
        host = _host_event_ownership_evidence("mechanical_whine")
        self.assertEqual(host["status"], "staticOnlyHostUnreachable")
        self.assertEqual(
            host["runtimeDelivery"],
            "conservationOnlyNoNrtCaptureOrAndroidPlayback",
        )

    def test_external_transmission_replaces_shared_fallback_only_when_authored(self) -> None:
        self.assertIsNone(_effect_perspective("transmission"))
        self.assertEqual(
            _effect_perspective("transmission", has_transmission_ext=True),
            "cabin",
        )
        self.assertEqual(
            _effect_perspective("transmission_ext", has_transmission_ext=True),
            "exterior",
        )


if __name__ == "__main__":
    unittest.main()
