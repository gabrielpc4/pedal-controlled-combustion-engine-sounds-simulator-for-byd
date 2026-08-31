from __future__ import annotations

import ctypes as C
import unittest

from sim.fmod_renderer import (
    SilentFmodReferenceRenderer,
    _MaskChannel,
    _MaskChannelEvidence,
)


class _FakeCore:
    def __init__(self) -> None:
        self.volumes: list[tuple[int, float]] = []

    def FMOD_System_GetChannel(self, _system, channel_id, output) -> int:
        output._obj.value = 1_000 + int(channel_id)

        return 0

    def FMOD_Channel_SetVolume(self, channel, volume) -> int:
        self.volumes.append((int(channel.value), float(volume.value)))

        return 0

    def FMOD_Channel_SetPosition(self, _channel, _position, _unit) -> int:
        return 0


def _evidence(channel_id: int, *, current_volume: float) -> _MaskChannelEvidence:
    return _MaskChannelEvidence(
        channel_id=channel_id,
        sound_name="engine",
        base_volume=current_volume,
        audibility=1.0,
        channel_groups=(
            {"pointer": 11, "name": "Engine route", "volume": 1.0},
            {"pointer": 12, "name": "Master Bus", "volume": 1.0},
        ),
    )


class ChannelMaskReplacementTests(unittest.TestCase):
    def test_replacement_route_restores_unmasked_snapshot_volume(self) -> None:
        renderer = SilentFmodReferenceRenderer.__new__(SilentFmodReferenceRenderer)
        renderer._read_active_mask_channels = lambda *_args: {
            7: _evidence(7, current_volume=0.0)
        }
        snapshot = _MaskChannel(
            sound_name="engine",
            role="LOAD",
            base_volume=0.75,
            binding_route=("engine", (11,)),
        )
        core = _FakeCore()

        renderer._apply_channel_mask(
            core,
            C.c_void_p(1),
            {3: snapshot},
            {"engine": ("LOAD",)},
            {"LOAD": 1.0, "COAST": 0.0, "UNAFFECTED": 0.0, "EXCLUDED": 0.0},
            reset_positions=False,
        )

        self.assertEqual(len(core.volumes), 1)
        self.assertEqual(core.volumes[0][0], 1_007)
        self.assertAlmostEqual(core.volumes[0][1], 0.75)


if __name__ == "__main__":
    unittest.main()
