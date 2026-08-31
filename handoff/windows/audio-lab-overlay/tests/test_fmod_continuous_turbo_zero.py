from __future__ import annotations

import unittest
from pathlib import Path
import struct
import tempfile

from tools.probe_fmod_continuous_turbo_zero import (
    _curve_value,
    _read_fmod_writer_pcm16,
    select_stable_zero_control,
)


class ContinuousTurboZeroSelectionTests(unittest.TestCase):
    def test_selects_interior_not_authored_step_boundary(self) -> None:
        curve = [[0.0, 0.0], [0.04, 0.0], [0.040001, 0.5], [1.0, 1.0]]
        selected = select_stable_zero_control(curve, 0.75)
        self.assertEqual(selected, 0.02)
        self.assertEqual(_curve_value(curve, selected), 0.0)

    def test_selects_nearest_of_multiple_reachable_zero_intervals(self) -> None:
        curve = [
            [0.0, 0.0],
            [0.1, 0.0],
            [0.2, 1.0],
            [0.7, 1.0],
            [0.8, 0.0],
            [1.0, 0.0],
            [1.5, 0.0],
        ]
        self.assertAlmostEqual(select_stable_zero_control(curve, 0.75), 0.9)
        self.assertAlmostEqual(select_stable_zero_control(curve, 0.15), 0.05)

    def test_unreachable_headroom_is_not_selected(self) -> None:
        curve = [[0.0, 1.0], [1.0, 1.0], [1.1, 0.0], [1.5, 0.0]]
        with self.assertRaisesRegex(ValueError, "no reachable exact-zero interval"):
            select_stable_zero_control(curve, 0.75)

    def test_single_zero_knot_is_not_misrepresented_as_stable_interval(self) -> None:
        curve = [[0.0, 1.0], [0.5, 0.0], [1.0, 1.0]]
        with self.assertRaisesRegex(ValueError, "no reachable exact-zero interval"):
            select_stable_zero_control(curve, 0.75)

    def test_fmod_40_byte_pcm_fmt_chunk_keeps_last_stereo_frame(self) -> None:
        pcm = struct.pack("<hhhh", 1, -1, 32767, -32768)
        fmt = struct.pack("<HHIIHH", 1, 2, 48000, 192000, 4, 16) + bytes(24)
        body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt
        body += b"data" + struct.pack("<I", len(pcm)) + pcm
        riff = b"RIFF" + struct.pack("<I", len(body)) + body
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "writer.wav"
            path.write_bytes(riff)
            payload, frames = _read_fmod_writer_pcm16(path)
        self.assertEqual(payload, pcm)
        self.assertEqual(frames, 2)

    def test_accepts_only_pinned_fmod_four_byte_outer_size_quirk(self) -> None:
        pcm = struct.pack("<hhhh", 1, 2, 3, 4)
        fmt = struct.pack("<HHIIHH", 1, 2, 48000, 192000, 4, 16) + bytes(24)
        body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt
        body += b"data" + struct.pack("<I", len(pcm)) + pcm
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "writer.wav"
            path.write_bytes(b"RIFF" + struct.pack("<I", len(body) - 4) + body)
            payload, frames = _read_fmod_writer_pcm16(path)
            self.assertEqual((payload, frames), (pcm, 2))
            path.write_bytes(b"RIFF" + struct.pack("<I", len(body) - 8) + body)
            with self.assertRaisesRegex(ValueError, "RIFF length differs"):
                _read_fmod_writer_pcm16(path)


if __name__ == "__main__":
    unittest.main()
