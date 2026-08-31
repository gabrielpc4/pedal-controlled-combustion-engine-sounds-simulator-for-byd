"""Realize property-index-1 FMOD fallback plans through a PCM oracle callback.

This module contains no FMOD or filesystem dependency.  A caller supplies a
target-only render callback and receives manifest-ready RPM-window tracks plus
their final PCM payloads.  All adaptive choices and spectral/RMS acceptance
tests are deterministic and fail closed through ``FmodAuthoredCurveError``.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
from typing import Any, Callable, Mapping

from .fmod_authored_curves import FmodAuthoredCurveError


WINDOWED_CAPTURE_FALLBACK_SCHEMA = "ac-fmod-authored-windowed-capture-fallback-v1"
WINDOWED_CAPTURE_REALIZATION_SCHEMA = "ac-fmod-windowed-capture-realization-v1"


def _fail(code: str, detail: str) -> None:
    raise FmodAuthoredCurveError(code, detail)


def _finite(value: object, code: str, detail: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        _fail(code, detail)
    if not math.isfinite(result):
        _fail(code, detail)
    return result


def _canonical_hash(value: object) -> str:
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        _fail("invalidWindowedCaptureRecipe", str(error))
    return hashlib.sha256(encoded).hexdigest()


def _linear_curve(points: list[list[float]], x: float) -> float:
    if not points:
        return 0.0
    if x <= float(points[0][0]):
        return float(points[0][1])
    if x >= float(points[-1][0]):
        return float(points[-1][1])
    low = 0
    high = len(points) - 1
    while high - low > 1:
        middle = (low + high) // 2
        if float(points[middle][0]) <= x:
            low = middle
        else:
            high = middle
    left_x, left_y = map(float, points[low])
    right_x, right_y = map(float, points[high])
    if right_x <= left_x:
        return left_y
    t = (x - left_x) / (right_x - left_x)
    return left_y + (right_y - left_y) * t


def _pcm16_stereo(payload: object, expected_frames: int) -> Any:
    import numpy as np

    if not isinstance(payload, (bytes, bytearray, memoryview)):
        _fail("invalidOraclePcm", "render callback pcm16le must be bytes-like")
    raw = bytes(payload)
    if len(raw) != expected_frames * 4:
        _fail(
            "invalidOraclePcm",
            f"callback returned {len(raw)} PCM bytes for {expected_frames} stereo frames",
        )
    return np.frombuffer(raw, dtype="<i2").reshape(-1, 2).astype(np.float64) / 32768.0


def _rms_and_stationarity(samples: Any) -> tuple[float, float]:
    import numpy as np

    centered = samples - np.mean(samples, axis=0, keepdims=True)
    rms = float(np.sqrt(np.mean(centered * centered)))
    blocks = np.array_split(centered, 32)
    block_db = []
    for block in blocks:
        value = float(np.sqrt(np.mean(block * block)))
        block_db.append(20.0 * math.log10(max(value, 1.0e-12)))
    median = float(np.median(block_db))
    mad = float(np.median(np.abs(np.asarray(block_db) - median)))
    return rms, mad


def _spectral_frames(samples: Any) -> tuple[Any, Any, int]:
    import numpy as np

    centered = samples - np.mean(samples, axis=0, keepdims=True)
    channel_rms = np.sqrt(np.mean(centered * centered, axis=0))
    channel = centered[:, int(np.argmax(channel_rms))]
    frame_size = 32768
    hop = 8192
    if len(channel) < frame_size:
        _fail("unstablePitchEstimate", "oracle PCM is shorter than 32768 frames")
    starts = range(0, len(channel) - frame_size + 1, hop)
    window = np.hanning(frame_size)
    spectra = np.stack(
        [np.abs(np.fft.rfft(channel[start : start + frame_size] * window)) ** 2 for start in starts]
    )
    frequencies = np.fft.rfftfreq(frame_size, 1.0 / 48000.0)
    frequency_mask = (frequencies >= 40.0) & (frequencies <= 12000.0)
    spectra = spectra[:, frequency_mask]
    frequencies = frequencies[frequency_mask]
    median_power = np.median(spectra, axis=0)
    if not np.any(median_power > 0.0):
        _fail("unstablePitchEstimate", "oracle PCM has no active spectrum")
    active_threshold = float(np.max(median_power)) * 1.0e-6
    active_bins = int(np.count_nonzero(median_power >= active_threshold))
    return spectra, frequencies, active_bins


def _log_spectrum(power: Any, frequencies: Any) -> tuple[Any, Any]:
    import numpy as np

    maximum_cents = int(math.floor(1200.0 * math.log2(12000.0 / 40.0)))
    cents = np.arange(maximum_cents + 1, dtype=np.float64)
    target_frequencies = 40.0 * np.exp2(cents / 1200.0)
    db = 10.0 * np.log10(np.maximum(power, float(np.max(power)) * 1.0e-12))
    interpolated = np.interp(target_frequencies, frequencies, db)
    interpolated = np.maximum(interpolated, float(np.max(interpolated)) - 80.0)
    # Remove a 31-cent local spectral envelope before correlation.  This is
    # still a log-spectrum correlation, but broad engine/noise coloration can
    # no longer produce a wide, poorly separated peak that hides harmonic
    # translation.
    kernel = np.ones(31, dtype=np.float64) / 31.0
    local_mean = np.convolve(interpolated, kernel, mode="same")
    return cents, interpolated - local_mean


def _correlation_shift(target: Any, runtime: Any) -> tuple[float, float, float]:
    import numpy as np

    shifts = np.arange(-240, 241, dtype=np.int32)
    correlations = np.empty(len(shifts), dtype=np.float64)
    indices = np.arange(len(target), dtype=np.float64)
    for index, shift in enumerate(shifts):
        shifted = np.interp(indices - float(shift), indices, runtime, left=np.nan, right=np.nan)
        mask = np.isfinite(shifted)
        left = target[mask]
        right = shifted[mask]
        left = left - np.mean(left)
        right = right - np.mean(right)
        denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
        correlations[index] = float(np.dot(left, right) / denominator) if denominator else -1.0
    best_index = int(np.argmax(correlations))
    best_shift = float(shifts[best_index])
    if 0 < best_index < len(correlations) - 1:
        left = correlations[best_index - 1]
        center = correlations[best_index]
        right = correlations[best_index + 1]
        denominator = left - 2.0 * center + right
        if abs(denominator) > 1.0e-12:
            best_shift += 0.5 * (left - right) / denominator
    exclusion = np.abs(shifts - shifts[best_index]) <= 12
    second = float(np.max(correlations[~exclusion])) if np.any(~exclusion) else -1.0
    return best_shift, float(correlations[best_index]), float(correlations[best_index] - second)


def measure_relative_log_spectral_pitch(target_pcm: Any, runtime_pcm: Any) -> dict[str, Any]:
    """Measure target-vs-runtime pitch mismatch under the published 1-cent gate."""

    import numpy as np

    frames = min(len(target_pcm), len(runtime_pcm))
    target_pcm = target_pcm[:frames]
    runtime_pcm = runtime_pcm[:frames]
    target_spectra, frequencies, target_active = _spectral_frames(target_pcm)
    runtime_spectra, runtime_frequencies, runtime_active = _spectral_frames(runtime_pcm)
    if not np.array_equal(frequencies, runtime_frequencies):
        _fail("unstablePitchEstimate", "target/runtime spectral grids differ")

    target_median = np.median(target_spectra, axis=0)
    runtime_median = np.median(runtime_spectra, axis=0)
    _cents, target_log = _log_spectrum(target_median, frequencies)
    _cents, runtime_log = _log_spectrum(runtime_median, frequencies)
    shift, correlation, separation = _correlation_shift(target_log, runtime_log)

    bootstrap_shifts = []
    group_count = min(8, len(target_spectra), len(runtime_spectra))
    for group in range(group_count):
        target_group = target_spectra[group::group_count]
        runtime_group = runtime_spectra[group::group_count]
        if not len(target_group) or not len(runtime_group):
            continue
        _cents, target_group_log = _log_spectrum(np.median(target_group, axis=0), frequencies)
        _cents, runtime_group_log = _log_spectrum(np.median(runtime_group, axis=0), frequencies)
        group_shift, _correlation, _separation = _correlation_shift(
            target_group_log, runtime_group_log
        )
        bootstrap_shifts.append(group_shift)
    bootstrap_median = float(np.median(bootstrap_shifts))
    bootstrap_mad = float(
        np.median(np.abs(np.asarray(bootstrap_shifts) - bootstrap_median))
    )
    active_bins = min(target_active, runtime_active)
    accepted = (
        active_bins >= 24
        and correlation >= 0.6
        and separation >= 0.05
        and bootstrap_mad <= 1.5
    )
    if not accepted:
        _fail(
            "unstablePitchEstimate",
            "log-spectrum confidence gate failed: "
            f"active={active_bins}, correlation={correlation:.6f}, "
            f"separation={separation:.6f}, bootstrapMad={bootstrap_mad:.6f}",
        )
    return {
        "pitchErrorCents": abs(shift),
        "signedTargetMinusRuntimeCents": shift,
        "activeSpectralBins": active_bins,
        "normalizedCorrelation": correlation,
        "bestToSecondPeakSeparation": separation,
        "bootstrapMadCents": bootstrap_mad,
        "bootstrapEstimatesCents": bootstrap_shifts,
    }


def _cubic_varispeed(samples: Any, ratio: float, maximum_frames: int) -> Any:
    import numpy as np

    if not math.isfinite(ratio) or ratio <= 0.0:
        _fail("invalidRuntimePitchRatio", "varispeed ratio must be positive")
    possible = int(math.floor((len(samples) - 3) / ratio))
    frame_count = min(maximum_frames, possible)
    if frame_count < 32768:
        _fail("insufficientRuntimePcm", "varispeed render is too short for analysis")
    positions = 1.0 + np.arange(frame_count, dtype=np.float64) * ratio
    base = np.floor(positions).astype(np.int64)
    fraction = (positions - base)[:, None]
    p0 = samples[base - 1]
    p1 = samples[base]
    p2 = samples[base + 1]
    p3 = samples[base + 2]
    f2 = fraction * fraction
    f3 = f2 * fraction
    return 0.5 * (
        2.0 * p1
        + (-p0 + p2) * fraction
        + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2
        + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f3
    )


def _recipe_at_rpm(plan: dict[str, Any], rpm: float, analysis_frames: int) -> dict[str, Any]:
    seeds = plan.get("seedCaptureRecipes")
    if not isinstance(seeds, list) or not seeds:
        _fail("invalidWindowedCapturePlan", "seed capture recipes are absent")
    nearest = min(
        seeds,
        key=lambda item: abs(float(item["recipePayload"]["captureRootRpm"]) - rpm),
    )
    payload = copy.deepcopy(nearest["recipePayload"])
    scale = float(plan["nativeSpeedToRpmScale"])
    native_value = rpm / scale
    parameter = str(plan["nativeSpeedParameter"])
    payload["captureParameterValues"][parameter] = round(native_value, 8)
    payload["captureRootRpm"] = round(rpm, 8)
    payload["referenceRenderer"]["analysisFrames"] = int(analysis_frames)
    return payload


def _adaptive_linearize(function: Callable[[float], float], knots: list[float], tolerance: float) -> list[list[float]]:
    points: dict[float, float] = {float(x): float(function(float(x))) for x in knots}
    for _depth in range(16):
        additions: list[float] = []
        ordered = sorted(points)
        for left, right in zip(ordered, ordered[1:]):
            middle = (left + right) * 0.5
            actual = float(function(middle))
            predicted = (points[left] + points[right]) * 0.5
            if abs(actual - predicted) > tolerance:
                additions.append(middle)
        if not additions:
            return [[round(x, 8), round(points[x], 10)] for x in sorted(points)]
        for value in additions:
            points[value] = float(function(value))
    _fail("windowCurveLinearizationFailed", "RPM window curve exceeded adaptive depth")


def realize_windowed_capture_fallback(
    fallback_plan: dict[str, Any],
    render_callback: Callable[[dict[str, Any]], Mapping[str, Any]],
) -> dict[str, Any]:
    """Adaptively realize one property-index-1 fallback using target PCM.

    The callback receives a canonical target-only recipe payload and must
    return ``pcm16le``, ``frameCount``, ``scheduledSourceGuids``,
    ``loopStartFrame`` and ``loopEndFrameExclusive``.  It may optionally return
    ``packPcm16le``; otherwise the analysis PCM becomes the packed payload.
    """

    import numpy as np

    if not isinstance(fallback_plan, dict) or fallback_plan.get("schema") != WINDOWED_CAPTURE_FALLBACK_SCHEMA:
        _fail("invalidWindowedCapturePlan", f"expected {WINDOWED_CAPTURE_FALLBACK_SCHEMA}")
    if not callable(render_callback):
        _fail("invalidWindowedCaptureCallback", "render callback is not callable")
    source_guid = str(fallback_plan.get("sourceGuid") or "")
    if not source_guid:
        _fail("invalidWindowedCapturePlan", "source GUID is absent")
    boundaries = [float(value) for value in fallback_plan["initialPartitionBoundariesRpm"]]
    if len(boundaries) < 2 or boundaries != sorted(set(boundaries)):
        _fail("invalidWindowedCapturePlan", "partition boundaries are invalid")
    gate = fallback_plan["adaptiveOracleGate"]
    pitch_limit = float(gate["splitEachWindowUntilPitchErrorCentsAtMost"])
    gain_limit = float(gate["splitEachWindowUntilGainErrorDbAtMost"])
    maximum_windows = int(gate["maximumFinalWindows"])
    tolerance = float(
        fallback_plan["manifestWindowRealization"]["linearization"][
            "absoluteAmplitudeTolerance"
        ]
    )
    projected_curve = [[float(x), float(y)] for x, y in fallback_plan["rpmCurve"]]
    analysis_frames = 384000
    cache: dict[tuple[float, int], dict[str, Any]] = {}

    def render(rpm: float, frames: int = analysis_frames) -> dict[str, Any]:
        key = (round(float(rpm), 8), int(frames))
        if key in cache:
            return cache[key]
        payload = _recipe_at_rpm(fallback_plan, rpm, frames)
        recipe_hash = _canonical_hash(payload)
        result = render_callback(copy.deepcopy(payload))
        if not isinstance(result, Mapping):
            _fail("invalidWindowedCaptureCallback", "render callback returned no mapping")
        try:
            frame_count = int(result.get("frameCount"))
        except (TypeError, ValueError):
            _fail("invalidOraclePcm", "render callback frameCount is invalid")
        if frame_count != frames:
            _fail("invalidOraclePcm", f"render returned {frame_count}, expected {frames} frames")
        scheduled = [str(value) for value in result.get("scheduledSourceGuids", [])]
        if scheduled != [source_guid]:
            _fail("targetIsolationMismatch", f"scheduled source GUIDs differ: {scheduled}")
        pcm_bytes = bytes(result.get("pcm16le", b""))
        pcm = _pcm16_stereo(pcm_bytes, frame_count)
        rms, stationarity = _rms_and_stationarity(pcm)
        long_half_gain_drift_db = 0.0
        if rms > 4.0 / 32768.0 and stationarity > 0.5:
            if frames == analysis_frames:
                return render(rpm, analysis_frames * 2)
            first_rms, _first_mad = _rms_and_stationarity(pcm[: len(pcm) // 2])
            second_rms, _second_mad = _rms_and_stationarity(pcm[len(pcm) // 2 :])
            long_half_gain_drift_db = abs(
                20.0
                * math.log10(max(second_rms, 1.0e-12) / max(first_rms, 1.0e-12))
            )
            # Authored engine loops can have deliberate within-cycle AM, so
            # arbitrary 1/32 blocks are not interchangeable.  A doubled
            # 16-second render is accepted when its halves agree within 0.1 dB.
            # A callback may alternatively prove two complete independent
            # renders bit-identical; that makes the finite-window RMS oracle
            # deterministic even for a deliberately amplitude-modulated loop.
            if (
                long_half_gain_drift_db > 0.1
                and result.get("independentRenderBitExact") is not True
            ):
                _fail(
                    "nonstationaryOraclePcm",
                    f"32-block MAD={stationarity:.6f} dB and long-half drift="
                    f"{long_half_gain_drift_db:.6f} dB",
                )
        pack_bytes = bytes(result.get("packPcm16le", pcm_bytes))
        if len(pack_bytes) % 4:
            _fail("invalidOraclePcm", "pack PCM is not stereo-frame aligned")
        pack_frames = len(pack_bytes) // 4
        try:
            loop_start = int(result.get("loopStartFrame", 0))
            loop_end = int(result.get("loopEndFrameExclusive", pack_frames))
        except (TypeError, ValueError):
            _fail("invalidOracleLoop", "callback loop bounds are invalid")
        if not (0 <= loop_start < loop_end <= pack_frames):
            _fail("invalidOracleLoop", "callback loop bounds are outside pack PCM")
        record = {
            "rpm": float(rpm),
            "recipePayload": payload,
            "captureRecipeSha256": recipe_hash,
            "pcm": pcm,
            "pcm16le": pcm_bytes,
            "packPcm16le": pack_bytes,
            "frameCount": frame_count,
            "packFrameCount": pack_frames,
            "loopStartFrame": loop_start,
            "loopEndFrameExclusive": loop_end,
            "pcmPayloadSha256": hashlib.sha256(pack_bytes).hexdigest(),
            "rms": rms,
            "stationarityMadDb": stationarity,
            "stationarityLongHalfGainDriftDb": long_half_gain_drift_db,
            "independentRenderBitExact": result.get("independentRenderBitExact") is True,
        }
        cache[key] = record
        return record

    def projected_gain(rpm: float) -> float:
        return max(0.0, _linear_curve(projected_curve, rpm))

    def build_tracks(current_boundaries: list[float]) -> list[dict[str, Any]]:
        widths = [right - left for left, right in zip(current_boundaries, current_boundaries[1:])]
        tracks = []
        for index, (left, right) in enumerate(zip(current_boundaries, current_boundaries[1:])):
            left_half = 0.25 * min(widths[index - 1], widths[index]) if index else 0.0
            right_half = 0.25 * min(widths[index], widths[index + 1]) if index + 1 < len(widths) else 0.0
            support_left = left - left_half
            support_right = right + right_half
            candidates = {support_left, support_right, left, right, (left + right) * 0.5}
            candidates.update(
                x for x, _gain in projected_curve if support_left <= x <= support_right
            )
            midpoint = (left + right) * 0.5
            root = min(
                candidates,
                key=lambda value: (-projected_gain(value), abs(value - midpoint), value),
            )
            root_gain = projected_gain(root)
            if root <= 0.0 or root_gain <= 0.0:
                # A cell whose projected gain is exactly zero is intentionally omitted.
                if max(projected_gain(value) for value in candidates) == 0.0:
                    continue
                _fail("invalidWindowReference", "window has no positive audible root")

            def envelope(rpm: float) -> float:
                value = 1.0
                if left_half > 0.0 and rpm < left + left_half:
                    t = (rpm - (left - left_half)) / (2.0 * left_half)
                    value *= math.sin(math.pi * max(0.0, min(1.0, t)) / 2.0)
                if right_half > 0.0 and rpm > right - right_half:
                    t = (rpm - (right - right_half)) / (2.0 * right_half)
                    value *= math.cos(math.pi * max(0.0, min(1.0, t)) / 2.0)
                if rpm < support_left or rpm > support_right:
                    return 0.0
                return value

            def track_gain(rpm: float) -> float:
                return envelope(rpm) * projected_gain(rpm) / root_gain

            knots = sorted(
                {
                    support_left,
                    support_right,
                    left,
                    right,
                    left - left_half,
                    left + left_half,
                    right - right_half,
                    right + right_half,
                    *(x for x, _gain in projected_curve if support_left <= x <= support_right),
                }
            )
            curve = _adaptive_linearize(track_gain, knots, tolerance)
            capture = render(root)
            tracks.append(
                {
                    "cell": [left, right],
                    "support": [support_left, support_right],
                    "rootRpm": root,
                    "rootProjectedGain": root_gain,
                    "rpmCurve": curve,
                    "capture": capture,
                }
            )
        return tracks

    verification_records: list[dict[str, Any]] = []
    while True:
        if len(boundaries) - 1 > maximum_windows:
            _fail("windowLimitExceeded", "adaptive realization exceeded maximum windows")
        tracks = build_tracks(boundaries)
        probes: set[float] = set()
        for left, right in zip(boundaries, boundaries[1:]):
            probes.update(
                (
                    left + 0.25 * (right - left),
                    (left + right) * 0.5,
                    left + 0.75 * (right - left),
                )
            )
        for index, boundary in enumerate(boundaries[1:-1], 1):
            half = 0.25 * min(boundaries[index] - boundaries[index - 1], boundaries[index + 1] - boundaries[index])
            probes.update((boundary - half, boundary - half * 0.5, boundary, boundary + half * 0.5, boundary + half))
        violations: list[dict[str, Any]] = []
        iteration_records = []
        for rpm in sorted(probes):
            target = render(rpm)
            target_gain = projected_gain(rpm)
            active = [
                (track, _linear_curve(track["rpmCurve"], rpm))
                for track in tracks
                if _linear_curve(track["rpmCurve"], rpm) > 0.0
            ]
            if target["rms"] <= 4.0 / 32768.0 or target_gain == 0.0:
                if active:
                    maximum_predicted = max(gain for _track, gain in active)
                    if maximum_predicted > 1.0e-5:
                        violations.append({"rpm": rpm, "score": 2.0, "reason": "targetSilentRuntimeAudible"})
                continue
            runtime_parts = []
            for track, gain in active:
                ratio = rpm / float(track["rootRpm"])
                runtime_parts.append(
                    _cubic_varispeed(track["capture"]["pcm"], ratio, len(target["pcm"])) * gain
                )
            if not runtime_parts:
                violations.append({"rpm": rpm, "score": 2.0, "reason": "noRuntimeTrack"})
                continue
            common_frames = min(len(target["pcm"]), *(len(part) for part in runtime_parts))
            runtime_pcm = sum(part[:common_frames] for part in runtime_parts)
            target_pcm = target["pcm"][:common_frames]
            target_rms, _mad = _rms_and_stationarity(target_pcm)
            runtime_rms, _runtime_mad = _rms_and_stationarity(runtime_pcm)
            gain_error_db = abs(
                20.0
                * math.log10(max(runtime_rms, 1.0e-12) / max(target_rms, 1.0e-12))
            )
            pitch = measure_relative_log_spectral_pitch(target_pcm, runtime_pcm)
            pitch_error = float(pitch["pitchErrorCents"])
            record = {
                "rpm": rpm,
                "activeRootsRpm": [track["rootRpm"] for track, _gain in active],
                "pitchErrorCents": pitch_error,
                "gainErrorDb": gain_error_db,
                "pitchConfidence": pitch,
            }
            iteration_records.append(record)
            score = max(pitch_error / pitch_limit, gain_error_db / gain_limit)
            if score > 1.0:
                violations.append({"rpm": rpm, "score": score, "reason": "oracleBound", "record": record})
        verification_records = iteration_records
        if not violations:
            break
        if len(boundaries) - 1 >= maximum_windows:
            worst = max(violations, key=lambda item: (item["score"], -item["rpm"]))
            _fail("windowLimitExceeded", f"oracle violation remains at {worst['rpm']} RPM")
        worst = max(violations, key=lambda item: (item["score"], -item["rpm"]))
        rpm = float(worst["rpm"])
        containing = [
            (index, left, right)
            for index, (left, right) in enumerate(zip(boundaries, boundaries[1:]))
            if left < rpm < right
        ]
        if containing:
            _index, left, right = containing[0]
            split = rpm
            if min(split - left, right - split) < (right - left) * 0.05:
                split = (left + right) * 0.5
        else:
            index = min(range(1, len(boundaries) - 1), key=lambda value: abs(boundaries[value] - rpm))
            left_width = boundaries[index] - boundaries[index - 1]
            right_width = boundaries[index + 1] - boundaries[index]
            if left_width >= right_width:
                split = (boundaries[index - 1] + boundaries[index]) * 0.5
            else:
                split = (boundaries[index] + boundaries[index + 1]) * 0.5
        if split in boundaries:
            _fail("adaptiveSplitStalled", f"cannot split around {rpm} RPM")
        boundaries.append(split)
        boundaries.sort()

    final_tracks = build_tracks(boundaries)
    release_tracks = []
    pcm_by_track: dict[str, bytes] = {}
    for index, track in enumerate(final_tracks):
        capture = track["capture"]
        track_id = f"{source_guid}:rpm-window:{index:03d}"
        pcm_by_track[track_id] = capture["packPcm16le"]
        release_tracks.append(
            {
                "trackId": track_id,
                "sourceGuid": source_guid,
                "eventPath": fallback_plan["eventPath"],
                "role": fallback_plan["role"],
                "cellRpm": track["cell"],
                "supportRpm": track["support"],
                "rootRpm": track["rootRpm"],
                "rpmCurve": track["rpmCurve"],
                "gainCurve": fallback_plan["gainCurve"],
                "frameCount": capture["packFrameCount"],
                "pcmPayloadSha256": capture["pcmPayloadSha256"],
                "loopStartFrame": capture["loopStartFrame"],
                "loopEndFrameExclusive": capture["loopEndFrameExclusive"],
                "captureRecipeSha256": capture["captureRecipeSha256"],
                "captureParameterValues": capture["recipePayload"]["captureParameterValues"],
            }
        )
    release_record = {
        "schema": WINDOWED_CAPTURE_REALIZATION_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": fallback_plan["eventPath"],
        "role": fallback_plan["role"],
        "finalPartitionBoundariesRpm": boundaries,
        "tracks": release_tracks,
        "verification": {
            "pitchErrorCentsAtMost": pitch_limit,
            "gainErrorDbAtMost": gain_limit,
            "probeResults": verification_records,
            "targetOnly": True,
            "sampleNamesUsed": False,
            "exactWithinReportedOracleBounds": True,
        },
        "fallbackPlanSha256": _canonical_hash(fallback_plan),
    }
    release_record["realizationPayloadSha256"] = _canonical_hash(release_record)
    return {
        "releaseRecord": release_record,
        "pcm16leByTrackId": pcm_by_track,
    }


__all__ = [
    "WINDOWED_CAPTURE_REALIZATION_SCHEMA",
    "measure_relative_log_spectral_pitch",
    "realize_windowed_capture_fallback",
]
