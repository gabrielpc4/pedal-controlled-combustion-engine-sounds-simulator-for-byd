"""Exclusive-end PCM loop selection, seam measurement, and local repair."""

from __future__ import annotations

import array
import math
import os
from dataclasses import dataclass


BYTES_PER_STEREO_PCM16_FRAME = 4


class LoopError(ValueError):
    pass


@dataclass(frozen=True)
class LoopSeam:
    start_frame: int
    end_frame: int
    sample_delta: int
    derivative_delta: int

    @property
    def peak_dbfs(self) -> float:
        peak = max(self.sample_delta, self.derivative_delta)
        return -math.inf if peak == 0 else 20.0 * math.log10(peak / 32768.0)


def _samples(pcm: bytes) -> array.array[int]:
    if len(pcm) % BYTES_PER_STEREO_PCM16_FRAME:
        raise LoopError("PCM length is not an integral stereo PCM16 frame count")
    values = array.array("h")
    values.frombytes(pcm)
    if os.sys.byteorder != "little":
        values.byteswap()
    return values


def _validate_bounds(frame_count: int, start_frame: int, end_frame: int) -> None:
    if not (0 <= start_frame < end_frame <= frame_count):
        raise LoopError(
            f"exclusive-end loop [{start_frame},{end_frame}) is outside 0..{frame_count}"
        )
    if end_frame - start_frame < 3:
        raise LoopError("loop must contain at least three frames")


def measure_loop_seam(pcm: bytes, start_frame: int, end_frame: int) -> LoopSeam:
    values = _samples(pcm)
    frame_count = len(values) // 2
    _validate_bounds(frame_count, start_frame, end_frame)
    return _measure_values(values, start_frame, end_frame)


def _measure_values(
    values: array.array[int], start_frame: int, end_frame: int
) -> LoopSeam:
    last = end_frame - 1
    previous = last - 1
    following = start_frame + 1
    sample_delta = max(
        abs(int(values[last * 2 + channel]) - int(values[start_frame * 2 + channel]))
        for channel in range(2)
    )
    derivative_delta = max(
        abs(
            (int(values[last * 2 + channel]) - int(values[previous * 2 + channel]))
            - (
                int(values[following * 2 + channel])
                - int(values[start_frame * 2 + channel])
            )
        )
        for channel in range(2)
    )
    return LoopSeam(start_frame, end_frame, sample_delta, derivative_delta)


def find_best_loop_bounds(
    pcm: bytes,
    *,
    nominal_start: int,
    nominal_end: int,
    search_frames: int = 240,
) -> LoopSeam:
    """Search a bounded neighborhood for the quietest value/slope seam."""

    values = _samples(pcm)
    frame_count = len(values) // 2
    _validate_bounds(frame_count, nominal_start, nominal_end)
    starts = range(
        max(0, nominal_start - search_frames),
        min(nominal_end - 2, nominal_start + search_frames) + 1,
    )
    ends = range(
        max(nominal_start + 3, nominal_end - search_frames),
        min(frame_count, nominal_end + search_frames) + 1,
    )
    best: LoopSeam | None = None
    best_score: tuple[int, int, int] | None = None
    for start in starts:
        for end in ends:
            if end - start < 3:
                continue
            seam = _measure_values(values, start, end)
            score = (
                seam.sample_delta * 2 + seam.derivative_delta,
                abs(start - nominal_start) + abs(end - nominal_end),
                start,
            )
            if best_score is None or score < best_score:
                best = seam
                best_score = score
    assert best is not None
    return best


def crossfade_loop_seam(
    pcm: bytes,
    start_frame: int,
    end_frame: int,
    *,
    crossfade_frames: int = 480,
) -> tuple[bytes, LoopSeam]:
    """Repair a loop locally and return PCM plus its adjusted loop bounds.

    The final crossfade window approaches the original head window.  The new
    loop begins immediately after that head window, so the exclusive-end wrap
    follows the same adjacent source samples instead of jumping back to frame
    zero of the crossfade.
    """

    values = _samples(pcm)
    frame_count = len(values) // 2
    _validate_bounds(frame_count, start_frame, end_frame)
    length = end_frame - start_frame
    count = min(int(crossfade_frames), max(1, (length - 2) // 3))
    if count < 2:
        raise LoopError("loop is too short to crossfade")
    for index in range(count):
        alpha = (index + 1.0) / count
        tail_frame = end_frame - count + index
        head_frame = start_frame + index
        for channel in range(2):
            tail_index = tail_frame * 2 + channel
            head_index = head_frame * 2 + channel
            mixed = round(
                int(values[tail_index]) * (1.0 - alpha)
                + int(values[head_index]) * alpha
            )
            values[tail_index] = max(-32768, min(32767, mixed))
    if os.sys.byteorder != "little":
        values.byteswap()
    repaired = values.tobytes()
    adjusted_start = start_frame + count
    return repaired, measure_loop_seam(repaired, adjusted_start, end_frame)
