"""Read-only regression audit for the known Huracán loop-seam sources.

The three inputs live outside this project and are never modified or copied.
Only hashes, measurements, and proposed exclusive-end loop bounds are emitted.
"""

from __future__ import annotations

import array
import hashlib
import math
import os
import wave
from pathlib import Path
from typing import Any

from .loop_tools import find_best_loop_bounds, measure_loop_seam


HURACAN_REGRESSION_FILES = (
    ("c1", "s039_hur_c1.wav"),
    ("c3", "s134_hur_c3.wav"),
    ("limiter", "s073_hur_lim.wav"),
)
SAFE_LOOP_SEAM_DBFS = -36.0
PROFILE_PEAK_CEILING_DBFS = -3.1


class HuracanRegressionError(ValueError):
    pass


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _pcm_statistics(pcm: bytes) -> tuple[str, float, int]:
    values = array.array("h")
    values.frombytes(pcm)
    if os.sys.byteorder != "little":
        values.byteswap()
    peak = max((abs(int(value)) for value in values), default=0)
    peak_dbfs = -math.inf if peak == 0 else 20.0 * math.log10(peak / 32768.0)
    rail_samples = sum(value in (-32768, 32767) for value in values)
    return hashlib.sha256(pcm).hexdigest(), peak_dbfs, rail_samples


def audit_huracan_loop_sources(source_root: Path) -> dict[str, Any]:
    """Measure the mandatory c1/c3/limiter cases without touching their files."""

    root = source_root.resolve()
    results: list[dict[str, Any]] = []
    for track_id, filename in HURACAN_REGRESSION_FILES:
        path = root / filename
        if not path.is_file():
            raise HuracanRegressionError(f"missing Huracán regression source: {path}")
        with wave.open(str(path), "rb") as source:
            metadata = (
                source.getnchannels(),
                source.getsampwidth(),
                source.getframerate(),
                source.getcomptype(),
                source.getnframes(),
            )
            if metadata[:2] != (2, 2) or metadata[3] != "NONE":
                raise HuracanRegressionError(
                    f"{filename} must be uncompressed stereo PCM16"
                )
            pcm = source.readframes(source.getnframes())
        channels, sample_width, sample_rate, _compression, frame_count = metadata
        del channels, sample_width
        pcm_sha, peak_dbfs, rail_samples = _pcm_statistics(pcm)
        original = measure_loop_seam(pcm, 0, frame_count)
        guard = min(960, max(1, frame_count // 12))
        selected = find_best_loop_bounds(
            pcm,
            nominal_start=guard,
            nominal_end=frame_count - guard,
            search_frames=min(320, guard),
        )
        if selected.peak_dbfs > SAFE_LOOP_SEAM_DBFS:
            raise HuracanRegressionError(
                f"{track_id} has no safe bounded loop seam: "
                f"{selected.peak_dbfs:.2f} dBFS"
            )
        attenuation_db = min(0.0, PROFILE_PEAK_CEILING_DBFS - peak_dbfs)
        results.append(
            {
                "id": track_id,
                "sourceFile": filename,
                "sourceFileSha256": _sha256_file(path),
                "sourcePcmSha256": pcm_sha,
                "sampleRate": sample_rate,
                "channels": 2,
                "bitsPerSample": 16,
                "frameCount": frame_count,
                "sourcePeakDbfs": round(peak_dbfs, 6),
                "sourceRailSampleCount": rail_samples,
                "requiredAttenuationDb": round(attenuation_db, 6),
                "originalLoop": {
                    "startFrame": 0,
                    "endFrame": frame_count,
                    "seamPeakDbfs": round(original.peak_dbfs, 6),
                },
                "selectedExclusiveLoop": {
                    "startFrame": selected.start_frame,
                    "endFrame": selected.end_frame,
                    "sampleDelta": selected.sample_delta,
                    "derivativeDelta": selected.derivative_delta,
                    "seamPeakDbfs": round(selected.peak_dbfs, 6),
                },
                "requiresCanonical48KhzRender": sample_rate != 48000,
            }
        )
    return {
        "schemaVersion": 1,
        "sourceRoot": str(root),
        "measurementOnly": True,
        "safeLoopSeamDbfs": SAFE_LOOP_SEAM_DBFS,
        "profilePeakCeilingDbfs": PROFILE_PEAK_CEILING_DBFS,
        "tracks": results,
        "blockers": [
            "These legacy sources are 44.1 kHz; they are not eligible for a 48 kHz pack without a canonical rerender.",
            "c1 contains rail-valued source samples, so attenuation prevents new over-range but cannot restore already clipped peaks.",
        ],
    }
