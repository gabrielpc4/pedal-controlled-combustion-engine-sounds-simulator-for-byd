#!/usr/bin/env python3
"""Hash-resumable, fail-closed full-event FMOD atlas oracle and refiner.

The oracle never compares raw PCM samples from independent FMOD event renders:
their loop phase is arbitrary.  It instead compares a target render and the
phase-aligned, target-rate reconstruction through envelope, log-band, pitch,
and gain metrics.  A failed cell is promoted to a real FMOD anchor and the
same deterministic probe is evaluated again on the next iteration.
"""

from __future__ import annotations

import argparse
import array
import copy
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import sys
import tempfile
from typing import Any, Mapping, Sequence


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import canonical_json_bytes
from generate_full_event_atlas_recipe import (
    ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
    ATLAS_PLAN_SCHEMA,
    ATLAS_REALIZATION_SCHEMA,
    FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA,
    FMOD_VOICE_BUDGET_INPUT_SCHEMA,
    _effect_node_asset,
    _engine_mode_program_asset,
    _node_asset,
    _midpoints,
    _selected_perspective_effect_resources,
    current_axes,
    current_nodes,
    refresh_plan_sha256,
)
from realize_full_event_atlas import _render_engine_node, _render_node
from playlist_selection import playlist_seed, select_playlist_member
from realize_nrt_recipe import (
    NrtRecipeError,
    _canonical_wav,
    _load_audio_lab,
    _pcm_metrics,
    _read_pcm,
    _read_smpl_loop,
    _sha256,
    _write_atomic,
)


ORACLE_SCHEMA = "byd-full-event-atlas-oracle-v1"
STATE_SCHEMA = "byd-full-event-atlas-oracle-state-v1"
ORACLE_IMPLEMENTATION = "phase-invariant-full-event-fmod-oracle-v5"
ANALYSIS_FRAMES = 12_000
CORRELATION_FRAMES = 960
CORRELATION_SEARCH = 960


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(canonical_json_bytes(value) + b"\n")
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _pcm_frames(pcm: bytes) -> list[float]:
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    # Android reads PCM16 through Short.MAX_VALUE, including its intentionally
    # slightly-below-minus-one representation of -32768.  Keep that exact
    # convention here so cubic interpolation/correlation are reproducible.
    return [value / 32767.0 for value in samples]


def _pcm16_from_float(stereo: Sequence[float]) -> bytes:
    """Quantize a bounded stereo host reference without hiding clipping."""

    samples = array.array("h")
    for value in stereo:
        if not math.isfinite(value) or value < -1.0 or value > 1.0:
            raise NrtRecipeError("host reference PCM would clip or contain NaN")
        samples.append(max(-32768, min(32767, round(value * 32767.0))))
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def _node_pcm(path: Path) -> tuple[list[float], int, int]:
    pcm, frames, rate, channels = _read_pcm(path, require_nrt_format=True)
    if rate != 48_000 or channels != 2:
        raise NrtRecipeError(f"atlas node {path} is not PCM16/48k/stereo")
    loop = _read_smpl_loop(path)
    if loop is None:
        raise NrtRecipeError(f"atlas node {path} has no loop")
    return _pcm_frames(pcm), loop[0], loop[1]


def _loop_sample(samples: Sequence[float], start: int, end: int, position: float) -> tuple[float, float]:
    length = end - start
    if length < 4:
        raise NrtRecipeError("atlas loop is too short for interpolation")
    floor = math.floor(position)
    fraction = position - floor

    def frame(relative: int, channel: int) -> float:
        return samples[(start + relative % length) * 2 + channel]

    def cubic(channel: int) -> float:
        y0 = frame(floor - 1, channel)
        y1 = frame(floor, channel)
        y2 = frame(floor + 1, channel)
        y3 = frame(floor + 2, channel)
        a0 = y3 - y2 - y0 + y1
        a1 = y0 - y1 - a0
        a2 = y2 - y0
        return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1

    return cubic(0), cubic(1)


def _resampled_loop(
    samples: Sequence[float],
    start: int,
    end: int,
    *,
    ratio: float,
    phase_offset: float,
    frames: int,
) -> list[float]:
    if not math.isfinite(ratio) or ratio <= 0.0:
        raise NrtRecipeError("atlas playback ratio is invalid")
    result: list[float] = []
    for frame in range(frames):
        left, right = _loop_sample(samples, start, end, phase_offset + frame * ratio)
        result.extend((left, right))
    return result


def _rms(stereo: Sequence[float]) -> float:
    return math.sqrt(sum(value * value for value in stereo) / max(1, len(stereo)))


def _apply_post_sum_master(stereo: Sequence[float]) -> list[float]:
    """Apply the zero-lookahead linked limiter after event host gains."""

    if len(stereo) % 2:
        raise NrtRecipeError("host mix must contain whole stereo frames")
    result = [0.0] * len(stereo)
    frames = len(stereo) // 2
    gain = 1.0
    for frame in range(frames):
        left = stereo[frame * 2]
        right = stereo[frame * 2 + 1]
        peak = max(abs(left), abs(right))
        target = min(1.0, 0.98 / max(1.0e-12, peak))
        if target < gain:
            gain = target
        else:
            gain += (1.0 - gain) / 4_800.0
        result[frame * 2] = left * gain
        result[frame * 2 + 1] = right * gain
    return result


def _apply_host_mix_contract(
    engine: Sequence[float],
    effects: Sequence[Sequence[float]] = (),
    effect_host_gain_classes: Sequence[str] = (),
    *,
    load_program_gain: float = 1.0,
    coast_program_gain: float = 1.0,
    engine_event_effect_roles: Sequence[str | None] = (),
) -> list[float]:
    """Mix one selected FMOD engine program with independently scheduled effects."""

    if len(engine) % 2:
        raise NrtRecipeError("engine contribution must contain whole stereo frames")
    if effect_host_gain_classes and len(effect_host_gain_classes) != len(effects):
        raise NrtRecipeError("effect host gain class count differs from contributions")
    if engine_event_effect_roles and len(engine_event_effect_roles) != len(effects):
        raise NrtRecipeError("engine-event effect role count differs from contributions")
    mixed = [value * 0.5 for value in engine]
    for index, effect in enumerate(effects):
        if len(effect) != len(engine):
            raise NrtRecipeError("effect contribution length differs from engine")
        gain_class = (
            effect_host_gain_classes[index]
            if effect_host_gain_classes
            else "effectEvent"
        )
        if gain_class == "engineEvent":
            role = (
                engine_event_effect_roles[index]
                if engine_event_effect_roles
                else None
            )
            if role == "LOAD":
                program_gain = load_program_gain
            elif role == "COAST":
                program_gain = coast_program_gain
            elif role in {None, "IDLE", "BOTH", "UNCLASSIFIED"}:
                program_gain = 1.0
            else:
                raise NrtRecipeError(f"unknown engine-event transient role: {role}")
            gain = 0.5 * program_gain
        elif gain_class == "effectEvent":
            gain = 1.0
        else:
            raise NrtRecipeError(f"unknown effect host gain class: {gain_class}")
        for sample_index, value in enumerate(effect):
            mixed[sample_index] += value * gain
    return _apply_post_sum_master(mixed)


def _coarse_alignment(reference: Sequence[float], candidate: tuple[Sequence[float], int, int, float], *, base_phase_offset: float = 0.0) -> float:
    """Runtime-identical bounded stereo correlation with deterministic ties.

    The first pass visits the full signed range at an eight-frame offset
    stride while sampling every fourth source/history frame.  It then scores
    every integer offset in a plus/minus-eight-frame neighbourhood around the
    winning coarse offset.  Both passes sum the independent left and right
    dot products and energies.  A quiet reference keeps the supplied baseline
    phase instead of inventing a correlation result.
    """

    samples, start, end, ratio = candidate
    if len(reference) < CORRELATION_FRAMES * 2:
        raise NrtRecipeError("correlation reference is shorter than 960 stereo frames")

    def score(offset: int, *, frame_stride: int) -> float | None:
        dot = 0.0
        reference_energy = 0.0
        candidate_energy = 0.0
        count = 0
        for frame in range(0, CORRELATION_FRAMES, frame_stride):
            left, right = _loop_sample(
                samples,
                start,
                end,
                base_phase_offset + offset + frame * ratio,
            )
            reference_left = reference[frame * 2]
            reference_right = reference[frame * 2 + 1]
            dot += reference_left * left + reference_right * right
            reference_energy += reference_left * reference_left + reference_right * reference_right
            candidate_energy += left * left + right * right
            count += 2
        if math.sqrt(reference_energy / max(1, count)) < 0.001:
            return None
        return dot / math.sqrt(max(1.0e-20, reference_energy * candidate_energy))

    def pick(offsets: Sequence[int], *, frame_stride: int) -> int | None:
        best: tuple[float, int] | None = None
        for offset in offsets:
            value = score(offset, frame_stride=frame_stride)
            if value is None:
                return None
            # Max score, then smallest magnitude, then the negative offset.
            candidate_key = (value, -abs(offset), -offset)
            if best is None or candidate_key > (best[0], -abs(best[1]), -best[1]):
                best = (value, offset)
        return None if best is None else best[1]

    coarse = pick(
        tuple(range(-CORRELATION_SEARCH, CORRELATION_SEARCH + 1, 8)),
        frame_stride=4,
    )
    if coarse is None:
        return 0.0
    fine = pick(
        tuple(
            range(
                max(-CORRELATION_SEARCH, coarse - 8),
                min(CORRELATION_SEARCH, coarse + 8) + 1,
            )
        ),
        frame_stride=1,
    )
    return float(coarse if fine is None else fine)


def _bilinear_corners(
    rpm_axis: Sequence[float], throttle_axis: Sequence[float], rpm: float, throttle: float
) -> list[tuple[float, float, float]]:
    def bounds(axis: Sequence[float], value: float) -> tuple[float, float, float]:
        if value <= axis[0]:
            return axis[0], axis[0], 0.0
        if value >= axis[-1]:
            return axis[-1], axis[-1], 0.0
        # Match Android's lowerIndex/upperIndex exactly: an interior value
        # equal to an authored knot belongs to the following cell, retaining
        # that cell's zero-gain upper neighbours for phase preparation.
        for lower, upper in zip(axis, axis[1:]):
            if lower <= value < upper:
                return lower, upper, (value - lower) / (upper - lower)
        raise NrtRecipeError("probe is outside atlas axis")

    rpm_low, rpm_high, rpm_mix = bounds(rpm_axis, rpm)
    throttle_low, throttle_high, throttle_mix = bounds(throttle_axis, throttle)
    raw = [
        (rpm_low, throttle_low, (1.0 - rpm_mix) * (1.0 - throttle_mix)),
        (rpm_high, throttle_low, rpm_mix * (1.0 - throttle_mix)),
        (rpm_low, throttle_high, (1.0 - rpm_mix) * throttle_mix),
        (rpm_high, throttle_high, rpm_mix * throttle_mix),
    ]
    combined: dict[tuple[float, float], float] = {}
    for node_rpm, node_throttle, weight in raw:
        combined[(node_rpm, node_throttle)] = combined.get((node_rpm, node_throttle), 0.0) + weight
    return [(node_rpm, node_throttle, weight) for (node_rpm, node_throttle), weight in combined.items()]


def reconstruct_probe(
    perspective: Mapping[str, Any],
    node_directory: Path,
    *,
    rpm: float,
    throttle: float,
    frames: int = ANALYSIS_FRAMES,
) -> tuple[list[float], list[dict[str, Any]]]:
    if frames <= 0:
        raise NrtRecipeError("probe reconstruction frame count must be positive")
    axes = current_axes(perspective)
    nodes = {
        (float(node["rpm"]), float(node["throttle"])): node
        for node in current_nodes(perspective)
    }
    mixed = [0.0] * (frames * 2)
    alignments: list[dict[str, Any]] = []
    corners = sorted(
        _bilinear_corners(
        [float(value) for value in axes["rpm"]],
        [float(value) for value in axes["throttle"]],
        rpm,
        throttle,
        ),
        key=lambda item: (-item[2], item[0], item[1]),
    )
    alignment_reference: list[float] | None = None
    for node_rpm, node_throttle, weight in corners:
        node = nodes[(node_rpm, node_throttle)]
        path = node_directory / str(node["temporaryAssetName"])
        samples, start, end = _node_pcm(path)
        ratio = 1.0 if node_rpm <= 0.0 else min(4.0, max(0.1, rpm / node_rpm))
        # The FMOD target render is intentionally unavailable here.  Cold start
        # picks the highest-weight corner as its deterministic reference;
        # subsequent corners are aligned only to audio the Android runtime can
        # synthesize from already mapped PCM.
        phase_offset = (
            0.0
            if alignment_reference is None
            else _coarse_alignment(alignment_reference, (samples, start, end, ratio))
        )
        rendered = _resampled_loop(
            samples,
            start,
            end,
            ratio=ratio,
            phase_offset=float(node.get("phaseOffsetFrames", 0.0)) + phase_offset,
            frames=frames,
        )
        for index, value in enumerate(rendered):
            # Raw bilinear weights preserve coherent amplitude because they sum
            # to one.  Equal-power square-root gains would add +6dB at a four
            # corner centre after successful phase alignment.
            mixed[index] += weight * value
        alignment_reference = mixed.copy()
        alignments.append(
            {
                "nodeRpm": node_rpm,
                "nodeThrottle": node_throttle,
                "rawBilinearWeight": weight,
                "playbackRatio": ratio,
                "correlationOffsetFrames": phase_offset,
            }
        )
    return mixed, alignments


def _envelope(stereo: Sequence[float], block: int = 480) -> list[float]:
    return [_rms(stereo[index * 2 : min(len(stereo), (index + block) * 2)]) for index in range(0, len(stereo) // 2, block)]


def _fft(values: Sequence[float]) -> list[complex]:
    """Small dependency-free radix-2 FFT for phase-invariant band energies."""

    size = len(values)
    if size < 2 or size & (size - 1):
        raise NrtRecipeError("oracle FFT requires a power-of-two window")
    result = [complex(value, 0.0) for value in values]
    bit_reversed = 0
    for index in range(1, size):
        bit = size >> 1
        while bit_reversed & bit:
            bit_reversed ^= bit
            bit >>= 1
        bit_reversed ^= bit
        if index < bit_reversed:
            result[index], result[bit_reversed] = result[bit_reversed], result[index]
    width = 2
    while width <= size:
        unit = complex(math.cos(-2.0 * math.pi / width), math.sin(-2.0 * math.pi / width))
        half = width // 2
        for base in range(0, size, width):
            factor = 1.0 + 0.0j
            for index in range(base, base + half):
                paired = factor * result[index + half]
                result[index + half] = result[index] - paired
                result[index] += paired
                factor *= unit
        width *= 2
    return result


def _band_energies(mono: Sequence[float]) -> list[float]:
    window_size = 4096
    if len(mono) < window_size:
        raise NrtRecipeError("oracle PCM is too short for spectral analysis")
    # A centred Hann window and broad, integrated bands make the energy metric
    # invariant to loop phase while remaining sensitive to harmonic/timbre
    # changes.  It intentionally does not compare FFT bin phase.
    center = (len(mono) - window_size) // 2
    windowed = [
        mono[center + index] * (0.5 - 0.5 * math.cos(2.0 * math.pi * index / (window_size - 1)))
        for index in range(window_size)
    ]
    spectrum = _fft(windowed)
    bands = ((20.0, 125.0), (125.0, 250.0), (250.0, 500.0), (500.0, 1_000.0), (1_000.0, 2_000.0), (2_000.0, 4_000.0), (4_000.0, 8_000.0), (8_000.0, 16_000.0))
    return [
        max(
            1.0e-18,
            sum(
                spectrum[index].real * spectrum[index].real + spectrum[index].imag * spectrum[index].imag
                for index in range(max(1, math.ceil(low * window_size / 48_000.0)), min(window_size // 2, math.floor(high * window_size / 48_000.0) + 1))
            ),
        )
        for low, high in bands
    ]


def _pitch_hz(stereo: Sequence[float]) -> float | None:
    mono = [(stereo[index] + stereo[index + 1]) * 0.5 for index in range(0, min(len(stereo), 12_288), 2 * 8)]
    if _rms(mono) < 0.001 or len(mono) < 300:
        return None
    mean = sum(mono) / len(mono)
    mono = [value - mean for value in mono]
    best: tuple[float, int] | None = None
    # 48 kHz / 8: 25-2,000 Hz fundamental range.
    for lag in range(3, min(240, len(mono) // 2)):
        dot = sum(mono[index] * mono[index + lag] for index in range(len(mono) - lag))
        energy = math.sqrt(
            sum(value * value for value in mono[:-lag]) * sum(value * value for value in mono[lag:])
        )
        score = dot / max(1.0e-18, energy)
        if best is None or score > best[0]:
            best = (score, lag)
    if best is None or best[0] < 0.20:
        return None
    return 48_000.0 / 8.0 / best[1]


def phase_invariant_metrics(reference: Sequence[float], reconstruction: Sequence[float]) -> dict[str, Any]:
    if len(reference) != len(reconstruction) or not reference:
        raise NrtRecipeError("oracle comparison PCM lengths differ")
    reference_rms = _rms(reference)
    reconstruction_rms = _rms(reconstruction)
    reference_envelope = _envelope(reference)
    reconstruction_envelope = _envelope(reconstruction)
    envelope_error = math.sqrt(
        sum((actual - expected) ** 2 for actual, expected in zip(reconstruction_envelope, reference_envelope))
        / max(1, len(reference_envelope))
    ) / max(1.0e-9, math.sqrt(sum(value * value for value in reference_envelope) / max(1, len(reference_envelope))))
    reference_mono = [(reference[index] + reference[index + 1]) * 0.5 for index in range(0, min(len(reference), 8192), 2)]
    reconstruction_mono = [(reconstruction[index] + reconstruction[index + 1]) * 0.5 for index in range(0, min(len(reconstruction), 8192), 2)]
    expected_bands = _band_energies(reference_mono)
    actual_bands = _band_energies(reconstruction_mono)
    band_errors = [
        10.0 * math.log10(actual / expected)
        for expected, actual in zip(expected_bands, actual_bands)
    ]
    meaningful_band_floor = max(max(expected_bands), max(actual_bands)) * 1.0e-4
    meaningful_band_errors = [
        abs(error)
        for error, expected, actual in zip(band_errors, expected_bands, actual_bands)
        if max(expected, actual) >= meaningful_band_floor
    ]
    expected_pitch = _pitch_hz(reference)
    actual_pitch = _pitch_hz(reconstruction)
    pitch_cents = (
        None
        if expected_pitch is None or actual_pitch is None
        else 1_200.0 * math.log2(actual_pitch / expected_pitch)
    )
    return {
        "method": "phaseInvariantEnvelopeGoertzelBandAutocorrelationPitch-v1",
        "rawPcmNrmseUsed": False,
        "envelopeNormalizedRmsError": envelope_error,
        "maximumBandEnergyErrorDb": max(meaningful_band_errors, default=0.0),
        "bandEnergyErrorsDb": band_errors,
        "gainErrorDb": 20.0 * math.log10(max(1.0e-12, reconstruction_rms) / max(1.0e-12, reference_rms)),
        "pitchErrorCents": pitch_cents,
        "referencePitchHz": expected_pitch,
        "reconstructionPitchHz": actual_pitch,
    }


def _metrics_pass(metrics: Mapping[str, Any], gate: Mapping[str, Any]) -> bool:
    pitch = metrics["pitchErrorCents"]
    return (
        metrics["envelopeNormalizedRmsError"] <= gate["maximumEnvelopeNormalizedRmsError"]
        and metrics["maximumBandEnergyErrorDb"] <= gate["maximumBandEnergyErrorDb"]
        and abs(metrics["gainErrorDb"]) <= gate["maximumGainErrorDb"]
        and (pitch is None or abs(pitch) <= gate["maximumPitchErrorCents"])
    )


def _probe_key(perspective: str, rpm: float, throttle: float) -> str:
    return hashlib.sha256(canonical_json_bytes({"perspective": perspective, "rpm": rpm, "throttle": throttle})).hexdigest()


def _task_spec_sha256(bank_sha256: str, event: str, node: Mapping[str, Any]) -> str:
    """Bind every reusable WAV to the complete FMOD render contract."""

    return hashlib.sha256(
        canonical_json_bytes(
            {
                "oracleImplementation": ORACLE_IMPLEMENTATION,
                "bankSha256": bank_sha256,
                "eventPath": event,
                "parameters": node.get("parameters", {}),
                "lifetime": node.get("lifetime"),
                "requiredSourceGuid": node.get("requiredSourceGuid"),
                "requiredDiagnosticName": node.get("requiredDiagnosticName"),
                "hostGainClass": node.get("hostGainClass"),
                "sourceBindings": node.get("sourceBindings", ()),
                "phaseOffsetFrames": node.get("phaseOffsetFrames"),
                "durationFrames": node.get("durationFrames"),
                "warmupFrames": node.get("warmupFrames"),
            }
        )
    ).hexdigest()


def _state_contract_sha256(
    plan: Mapping[str, Any], graph_sha256: str, audio_lab_root: Path
) -> str:
    implementation_files = (
        SCRIPT_ROOT / "refine_full_event_atlas.py",
        SCRIPT_ROOT / "realize_full_event_atlas.py",
        SCRIPT_ROOT / "realize_nrt_recipe.py",
        SCRIPT_ROOT / "generate_full_event_atlas_recipe.py",
        SCRIPT_ROOT / "generate_android_profile_recipe.py",
        SCRIPT_ROOT / "playlist_selection.py",
        SCRIPT_ROOT / "probe_fmod11011_software_voice_budget.py",
        SCRIPT_ROOT / "pack_full_event_atlas.py",
        SCRIPT_ROOT / "batch_generate_full_event_atlases.py",
        audio_lab_root / "sim" / "fmod_renderer.py",
        audio_lab_root / "sim" / "fmod_bank_isolation.py",
    )
    missing = [str(path) for path in implementation_files if not path.is_file()]
    if missing:
        raise NrtRecipeError(
            "oracle implementation source is absent: " + ", ".join(missing)
        )
    return hashlib.sha256(
        canonical_json_bytes(
            {
                "oracleImplementation": ORACLE_IMPLEMENTATION,
                "implementationSourceSha256": {
                    path.name: _sha256(path) for path in implementation_files
                },
                "bankSha256": plan["bankSha256"],
                "graphSha256": graph_sha256,
                "interpolationContract": plan["interpolationContract"],
                "refinementGate": plan["refinementGate"],
                "captureMode": "fullEventFmodNrt/PCM16/48k/stereo",
            }
        )
    ).hexdigest()


def _probes(perspective: Mapping[str, Any]) -> list[tuple[float, float]]:
    axes = current_axes(perspective)
    rpm_axis = [float(value) for value in axes["rpm"]]
    throttle_axis = [float(value) for value in axes["throttle"]]
    candidates = {(rpm, throttle) for rpm in _midpoints(rpm_axis) for throttle in _midpoints(throttle_axis)}
    authored = [float(value) for value in perspective["mandatoryOracleProbes"]["authoredThrottleKnots"]]
    for throttle in authored:
        if throttle not in throttle_axis:
            candidates.update((rpm, throttle) for rpm in _midpoints(rpm_axis))
    existing = {(float(node["rpm"]), float(node["throttle"])) for node in current_nodes(perspective)}
    return sorted(candidates - existing)


def _refresh_nodes(perspective: dict[str, Any]) -> None:
    axes = current_axes(perspective)
    event_suffix = str(perspective["eventPath"]).rsplit("/", 1)[-1]
    old = {(float(node["rpm"]), float(node["throttle"])): node for node in current_nodes(perspective)}
    nodes: list[dict[str, Any]] = []
    for throttle in axes["throttle"]:
        for rpm in axes["rpm"]:
            key = (float(rpm), float(throttle))
            nodes.append(copy.deepcopy(old.get(key, {
                "rpm": rpm,
                "throttle": throttle,
                "parameters": {"rpms": rpm, "throttle": throttle},
                "phaseOffsetFrames": 0.0,
                "temporaryAssetName": _node_asset(event_suffix, rpm, throttle),
                "modeProgramTemporaryAssetNames": {
                    mode: _engine_mode_program_asset(
                        event_suffix,
                        rpm,
                        throttle,
                        mode,
                    )
                    for mode in ("loadOnly", "coastOnly")
                },
            })))
    perspective["nodes"] = nodes


def _add_anchor(perspective: dict[str, Any], rpm: float, throttle: float) -> None:
    axes = perspective["axes"]
    axes["rpm"] = sorted({*axes["rpm"], round(rpm, 8)})
    axes["throttle"] = sorted({*axes["throttle"], round(throttle, 8)})
    _refresh_nodes(perspective)


def _assert_adaptive_anchor_storage_limit(
    plan: Mapping[str, Any], failures: Sequence[tuple[str, float, float]]
) -> None:
    """Fail before a refinement round would exceed its declared PCM budget.

    Refinement promotion is all-or-blocked: it never drops a failing probe to
    remain within disk.  Computing the complete next axis product first also
    avoids persisting a half-promoted iteration when one perspective would
    exceed the auditable storage policy.
    """

    gate = plan.get("refinementGate")
    policy = gate.get("adaptiveStoragePolicy") if isinstance(gate, Mapping) else None
    if not isinstance(policy, Mapping) or policy.get("schema") != "byd-full-event-adaptive-storage-policy-v1":
        raise NrtRecipeError("refinement has no versioned adaptive storage policy")
    limits = policy.get("maximumNodesPerPerspective")
    if not isinstance(limits, Mapping):
        raise NrtRecipeError("adaptive storage policy has no perspective node limits")
    requested: dict[str, tuple[set[float], set[float]]] = {}
    for perspective_name, rpm, throttle in failures:
        perspective = plan["perspectives"].get(perspective_name)
        if not isinstance(perspective, Mapping):
            raise NrtRecipeError(f"adaptive failure has no {perspective_name} perspective")
        axes = current_axes(perspective)
        rpms, throttles = requested.setdefault(
            perspective_name,
            (set(float(value) for value in axes["rpm"]), set(float(value) for value in axes["throttle"])),
        )
        rpms.add(round(float(rpm), 8))
        throttles.add(round(float(throttle), 8))
    for perspective_name, (rpms, throttles) in requested.items():
        limit = limits.get(perspective_name)
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
            raise NrtRecipeError(
                f"adaptive storage policy has invalid {perspective_name} node limit"
            )
        next_count = len(rpms) * len(throttles)
        if next_count > limit:
            raise NrtRecipeError(
                f"adaptive refinement would require {next_count} {perspective_name} nodes "
                f"above its declared {limit}-node storage cap; release is blocked"
            )


def _tasks(plan: Mapping[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    result: list[tuple[str, dict[str, Any]]] = []
    for perspective in ("cabin", "exterior"):
        value = plan["perspectives"][perspective]
        for raw in current_nodes(value):
            node = dict(raw)
            node.update({"enginePerspective": perspective, "lifetime": "continuous", "durationFrames": value["capture"]["durationFrames"], "warmupFrames": value["capture"]["warmupFrames"]})
            result.append((str(value["eventPath"]), node))
    for event in plan["effects"]:
        result.extend((str(event["eventPath"]), dict(node)) for node in event["nodes"])
    return result


def _effect_isolation_muted_sources(
    graph: Mapping[str, Any], event: str, source_guid: str
) -> tuple[set[str], set[str]]:
    """Return source-event siblings to mute while leaving engine events intact."""

    normalized_source = source_guid.strip().strip("{}").casefold()
    instruments = {
        str(item.get("guid") or "").strip().strip("{}").casefold(): item
        for item in graph.get("instruments", [])
        if isinstance(item, Mapping)
    }
    source = instruments.get(normalized_source)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        raise NrtRecipeError(
            f"isolated effect source is not a waveform: {normalized_source}"
        )
    reachable = next(
        (
            item.get("reachableInstrumentGuids", [])
            for item in graph.get("events", [])
            if isinstance(item, Mapping)
            and str(item.get("path") or "").casefold() == event.casefold()
        ),
        None,
    )
    if not isinstance(reachable, Sequence) or isinstance(reachable, (str, bytes)):
        raise NrtRecipeError(f"effect event has no reachable-instrument evidence: {event}")
    event_waveforms = {
        str(guid).strip().strip("{}").casefold()
        for guid in reachable
        if str(guid).strip().strip("{}").casefold() in instruments
        and instruments[str(guid).strip().strip("{}").casefold()].get("kind")
        == "WaveformInstrumentNode"
    }
    if normalized_source not in event_waveforms:
        raise NrtRecipeError(
            f"effect source is not reachable from its event: {normalized_source}"
        )
    return event_waveforms - {normalized_source}, event_waveforms


def _source_selection_capture_contract(
    graph: Mapping[str, Any], source_guid: str
) -> dict[str, Any]:
    """Return the bounded authored branch-search setup for one waveform.

    FMOD chooses a leaf through every containing ``MultiInstrumentNode``.
    Capturing the first start of a source-solo derivative therefore proves
    neither that a target sibling is unreachable nor that it was selected.
    This mirrors Audio Lab's finite-source compiler rule: the product of the
    authored multi-child spans determines a bounded deterministic take search
    (at least 64, at most 4096).  Negative-loop multi parents are made
    single-shot *only in the temporary derivative*, so one take means one
    authored selection opportunity rather than a capture-time loop shortcut.

    A waveform must have one unambiguous source-to-root chain.  The recipe
    generator already rejects multi-parent scheduling bindings; repeat that
    invariant here because the oracle must not choose a convenient parent
    when source GUIDs are reused.
    """

    normalized_source = _source_guid(source_guid)
    instruments: dict[str, Mapping[str, Any]] = {}
    parents: dict[str, list[str]] = {}
    raw_instruments = graph.get("instruments")
    if not isinstance(raw_instruments, Sequence) or isinstance(
        raw_instruments, (str, bytes)
    ):
        raise NrtRecipeError("selection graph has no instrument list")
    for raw in raw_instruments:
        if not isinstance(raw, Mapping):
            raise NrtRecipeError("selection graph has a malformed instrument")
        guid = _source_guid(raw.get("guid"))
        if not guid:
            raise NrtRecipeError("selection graph instrument has no GUID")
        if guid in instruments:
            raise NrtRecipeError(f"selection graph duplicates instrument {guid}")
        instruments[guid] = raw
        children = raw.get("childInstruments", [])
        if not isinstance(children, Sequence) or isinstance(children, (str, bytes)):
            raise NrtRecipeError(f"selection instrument {guid} has malformed children")
        for child in children:
            if not isinstance(child, Mapping):
                raise NrtRecipeError(f"selection instrument {guid} has malformed child")
            child_guid = _source_guid(child.get("guid"))
            if not child_guid:
                raise NrtRecipeError(f"selection instrument {guid} child has no GUID")
            parents.setdefault(child_guid, []).append(guid)
    source = instruments.get(normalized_source)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        raise NrtRecipeError(
            f"selection source is not a waveform instrument: {normalized_source}"
        )

    chain_leaf_to_root: list[str] = [normalized_source]
    current = normalized_source
    while True:
        current_parents = parents.get(current, [])
        if not current_parents:
            break
        if len(current_parents) != 1:
            raise NrtRecipeError(
                f"selection source {normalized_source} has ambiguous parent bindings: "
                f"{sorted(current_parents)}"
            )
        parent = current_parents[0]
        if parent in chain_leaf_to_root:
            raise NrtRecipeError("selection graph has an instrument cycle")
        if parent not in instruments:
            raise NrtRecipeError(f"selection parent {parent} is absent from graph")
        chain_leaf_to_root.append(parent)
        current = parent

    selection_span = 1
    multi_parent_guids: list[str] = []
    single_shot_parent_guids: list[str] = []
    for guid in chain_leaf_to_root[1:]:
        instrument = instruments[guid]
        if instrument.get("kind") != "MultiInstrumentNode":
            continue
        children = instrument.get("childInstruments")
        if not isinstance(children, Sequence) or isinstance(children, (str, bytes)) or not children:
            raise NrtRecipeError(
                f"selection multi parent {guid} has no authored children"
            )
        multi_parent_guids.append(guid)
        selection_span *= len(children)
        if selection_span > 128:
            # Audio Lab's shared finite-source selection gate proves every
            # larger authored topology through the bounded 4096-take search.
            selection_span = 128
        properties = instrument.get("baseProperties")
        loop_count = properties.get("loopCount") if isinstance(properties, Mapping) else None
        if isinstance(loop_count, bool) or not isinstance(loop_count, int):
            raise NrtRecipeError(
                f"selection multi parent {guid} has invalid loop count"
            )
        if loop_count < 0:
            single_shot_parent_guids.append(guid)

    return {
        "schema": "byd-fmod-source-selection-capture-v1",
        "instrumentChainLeafToRoot": chain_leaf_to_root,
        "multiParentGuids": multi_parent_guids,
        "singleShotParentGuids": sorted(single_shot_parent_guids),
        "selectionSpan": selection_span,
        "maximumTakes": (
            1
            if not multi_parent_guids
            else min(4096, max(64, 32 * selection_span))
        ),
    }


def _scheduling_group_diagnostic_names(
    graph: Mapping[str, Any], variant: Mapping[str, Any]
) -> list[str]:
    """Resolve the exact callback-name candidate set for one scheduler group.

    This is deliberately *candidate-set* evidence.  FMOD can choose one
    authored playlist member at initial Start, so an initial callback may say
    a valid sibling rather than the binding currently being source-solo
    captured.  Exact GUID proof still comes from the later target-only entry
    take; this helper only prevents a callback from an unrelated source from
    being mistaken for the group becoming active.
    """

    scheduler = variant.get("schedulingGroup")
    if not isinstance(scheduler, Mapping):
        raise NrtRecipeError("placement-entry source has no scheduling group")
    members = scheduler.get("members")
    if not isinstance(members, Sequence) or isinstance(members, (str, bytes)) or not members:
        raise NrtRecipeError("placement-entry scheduling group has no members")
    instruments = {
        _source_guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, Mapping) and _source_guid(item.get("guid"))
    }
    names: set[str] = set()
    for member in members:
        if not isinstance(member, Mapping):
            raise NrtRecipeError("placement-entry scheduling member is malformed")
        source_guid = _source_guid(member.get("sourceGuid"))
        source = instruments.get(source_guid)
        sample = source.get("sample") if isinstance(source, Mapping) else None
        name = sample.get("name") if isinstance(sample, Mapping) else None
        if not isinstance(name, str) or not name:
            raise NrtRecipeError(
                f"placement-entry scheduler member {source_guid} has no callback name"
            )
        names.add(name)
    return sorted(names)


def _effective_source_selection_capture(
    base_capture: Mapping[str, Any], variant: Mapping[str, Any]
) -> dict[str, Any]:
    """Tighten only a fully-proven direct PlaySequential selection cycle.

    SmartRandom remains on the conservative 64--4096 bounded search.  A
    direct Multi Instrument PlaySequential group with every chance at 100 and
    contiguous authored order, however, is not random: one entry per member
    is both necessary and sufficient to visit every branch of the same live
    EventInstance.  This prevents multiplying source-solo lifecycle cost by
    an unrelated random-search bound while retaining the complete authored
    sequence.  Nested Multi trees and every incomplete/chance-bearing group
    deliberately fall back to the generic compiler bound.
    """

    result = copy.deepcopy(dict(base_capture))
    bounded = result.get("maximumTakes")
    if isinstance(bounded, bool) or not isinstance(bounded, int) or bounded < 1:
        raise NrtRecipeError("selection capture has an invalid generic take bound")
    result["boundedSearchMaximumTakes"] = bounded
    scheduler = variant.get("schedulingGroup")
    if not isinstance(scheduler, Mapping):
        result["effectiveTakeLimitReason"] = "noSchedulingGroupUsesBoundedSearch"
        return result
    selection = scheduler.get("selection")
    members = scheduler.get("members")
    if (
        not isinstance(selection, Mapping)
        or selection.get("playMode") != "PlaylistPlayMode_PlaySequential"
        or not isinstance(members, Sequence)
        or isinstance(members, (str, bytes))
        or not members
        or result.get("multiParentGuids") is None
        or len(result["multiParentGuids"]) != 1
        or result.get("selectionSpan") != len(members)
    ):
        result["effectiveTakeLimitReason"] = "nonDirectOrNonSequentialUsesBoundedSearch"
        return result
    authored_orders: list[int] = []
    chances: list[float] = []
    for member in members:
        if not isinstance(member, Mapping):
            result["effectiveTakeLimitReason"] = "malformedMemberUsesBoundedSearch"
            return result
        order = member.get("authoredOrder")
        if isinstance(order, bool) or not isinstance(order, int) or order < 0:
            result["effectiveTakeLimitReason"] = "nonContiguousOrderUsesBoundedSearch"
            return result
        authored_orders.append(order)
        chance = member.get("triggerChancePercent")
        try:
            normalized_chance = 100.0 if chance is None else float(chance)
        except (TypeError, ValueError):
            result["effectiveTakeLimitReason"] = "invalidMemberChanceUsesBoundedSearch"
            return result
        if not math.isfinite(normalized_chance):
            result["effectiveTakeLimitReason"] = "invalidMemberChanceUsesBoundedSearch"
            return result
        chances.append(normalized_chance)
    group_chance = scheduler.get("groupTriggerChancePercent")
    try:
        normalized_group_chance = (
            100.0 if group_chance is None else float(group_chance)
        )
    except (TypeError, ValueError):
        result["effectiveTakeLimitReason"] = "invalidGroupChanceUsesBoundedSearch"
        return result
    if (
        not math.isfinite(normalized_group_chance)
        or normalized_group_chance != 100.0
        or any(chance != 100.0 for chance in chances)
        or sorted(authored_orders) != list(range(len(members)))
    ):
        result["effectiveTakeLimitReason"] = "chanceOrOrderUsesBoundedSearch"
        return result
    result["maximumTakes"] = len(members)
    result["effectiveTakeLimitReason"] = "oneExactPlaySequentialAuthoredCycle"
    return result


def _exact_zero_gain_curve_evidence(
    graph: Mapping[str, Any],
    selection_capture: Mapping[str, Any],
    parameters: Mapping[str, Any],
) -> list[dict[str, Any]]:
    """Return exact authored property-4 zero-gain points for this binding.

    FMOD property index 4 is the normalized linear gain controller used by
    Audio Lab's authored-curve parser.  At a recorded control point whose
    value is exactly zero, a source can correctly emit SOUND_PLAYED while
    rendering no PCM.  This is distinct from a missed selection.  We only
    recognize an exact point owned by the retained source-to-root chain; no
    interpolation or approximate gain inference is accepted here.
    """

    raw_chain = selection_capture.get("instrumentChainLeafToRoot")
    if not isinstance(raw_chain, list) or not raw_chain:
        raise NrtRecipeError("selection capture has no instrument-chain evidence")
    owners = {_source_guid(guid) for guid in raw_chain}
    parameter_values = {
        str(name).casefold(): float(value)
        for name, value in parameters.items()
    }
    evidence: list[dict[str, Any]] = []
    for controller in graph.get("controllers", []):
        if not isinstance(controller, Mapping):
            raise NrtRecipeError("gain-curve graph has a malformed controller")
        if _source_guid(controller.get("propertyOwnerGuid")) not in owners:
            continue
        raw_index = controller.get("propertyIndex")
        if isinstance(raw_index, bool) or raw_index != 4:
            continue
        if controller.get("inputKind") != "parameter":
            continue
        parameter = str(controller.get("inputParameterName") or "").casefold()
        if parameter not in parameter_values:
            continue
        points = controller.get("points")
        if not isinstance(points, Sequence) or isinstance(points, (str, bytes)):
            raise NrtRecipeError("gain curve has no point list")
        for point in points:
            if not isinstance(point, Mapping):
                raise NrtRecipeError("gain curve has a malformed point")
            x = point.get("x")
            y = point.get("y")
            if (
                isinstance(x, bool)
                or isinstance(y, bool)
                or not isinstance(x, (int, float))
                or not isinstance(y, (int, float))
                or not math.isfinite(float(x))
                or not math.isfinite(float(y))
            ):
                raise NrtRecipeError("gain curve point is not finite")
            if float(x) == parameter_values[parameter] and float(y) == 0.0:
                evidence.append(
                    {
                        "controllerGuid": str(controller.get("guid") or ""),
                        "propertyOwnerGuid": _source_guid(
                            controller.get("propertyOwnerGuid")
                        ),
                        "parameter": parameter,
                        "exactPoint": {"x": float(x), "y": 0.0},
                        "meaning": "normalizedLinearGainExactZero",
                    }
                )
    return sorted(
        evidence,
        key=lambda item: (
            item["controllerGuid"],
            item["propertyOwnerGuid"],
            item["parameter"],
        ),
    )


def _render_isolated_effect_node(
    renderer: Any,
    bank: Path,
    event: str,
    node: Mapping[str, Any],
    output: Path,
    graph: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
    loop_tools: tuple[Any, Any],
) -> dict[str, Any]:
    """Render an effect contribution without another source's steady bed.

    The temporary bank disables every other waveform instrument, including a
    continuous engine bed when this is an engine-event creation sound, while
    retaining the source's original event routing, bus DSP, and ancestor
    automation.  This source-solo capture is deliberately *not* a subtraction
    of independently rendered banks: subtracting across an unproven nonlinear
    bus would make a deceptively clean but invalid transient asset.
    """

    source_guid = str(node["requiredSourceGuid"]).strip().strip("{}").casefold()
    muted, _event_waveforms = _effect_isolation_muted_sources(
        graph, event, source_guid
    )
    disabled_parents = fully_muted_multi_instrument_guids(dict(graph), muted)
    with tempfile.TemporaryDirectory(prefix="atlas-effect-isolation-", dir=output.parent) as temporary_text:
        temporary = Path(temporary_text)
        isolated = create_isolated_bank_copy(
            bank,
            dict(graph),
            muted,
            temporary / bank.name,
            disabled_parent_guids=disabled_parents,
        )
        render = _render_node(
            renderer,
            isolated.output_path,
            event,
            # A source-solo derivative retains the original multi-instrument
            # scheduler.  SmartRandom/playlist variants can therefore select
            # a muted sibling on an individual event start; retain the normal
            # bounded deterministic take search so every authored branch gets
            # its own exact source capture instead of accepting a lucky first
            # take or silently omitting the variant.
            node,
            output,
            *loop_tools,
            event_id_lookup_bank_path=bank,
        )
        if set(render["scheduledDiagnosticNames"]) != {
            str(node["requiredDiagnosticName"])
        }:
            raise NrtRecipeError(f"isolated effect contribution is contaminated: {source_guid}")
    return {
        **render,
        "sourceIsolatedFullEventContribution": True,
        "sourceIsolationMethod": "sourceSoloEventRoutingAndBusDsp-v1",
        "mutedWaveformSources": len(muted),
        "disabledMultiInstrumentParents": len(disabled_parents),
    }


def _render_missing(
    plan: Mapping[str, Any], state: dict[str, Any], state_path: Path, node_directory: Path, renderer: Any, bank: Path, loop_tools: tuple[Any, Any], graph: Mapping[str, Any], create_isolated_bank_copy: Any, fully_muted_multi_instrument_guids: Any,
) -> None:
    rendered = state.setdefault("captures", {})
    for ordinal, (event, node) in enumerate(_tasks(plan), start=1):
        name = str(node["temporaryAssetName"])
        existing = rendered.get(name)
        path = node_directory / name
        task_sha = _task_spec_sha256(str(plan["bankSha256"]), event, node)
        mode_program_names = node.get("modeProgramTemporaryAssetNames")
        cached_mode_programs = existing.get("modePrograms") if isinstance(existing, Mapping) else None
        mode_programs_valid = mode_program_names is None or (
            isinstance(mode_program_names, Mapping)
            and isinstance(cached_mode_programs, Mapping)
            and all(
                (node_directory / str(mode_program_names[mode])).is_file()
                and isinstance(cached_mode_programs.get(mode), Mapping)
                and cached_mode_programs[mode].get("wavSha256")
                == _sha256(node_directory / str(mode_program_names[mode]))
                for mode in ("loadOnly", "coastOnly")
            )
        )
        if isinstance(existing, Mapping) and path.is_file() and existing.get("wavSha256") == _sha256(path) and mode_programs_valid:
            if existing.get("taskSpecSha256") != task_sha:
                raise NrtRecipeError(f"cached atlas node task differs: {path}")
            continue
        if path.exists():
            raise NrtRecipeError(f"untracked atlas node exists: {path}")
        print(f"atlas render {ordinal}/{len(_tasks(plan))}: {name}", flush=True)
        render = (
            _render_isolated_effect_node(
                renderer,
                bank,
                event,
                node,
                path,
                graph,
                create_isolated_bank_copy,
                fully_muted_multi_instrument_guids,
                loop_tools,
            )
            if node.get("requiredSourceGuid")
            else _render_engine_node(
                renderer,
                plan,
                str(node["enginePerspective"]),
                bank,
                event,
                node,
                path,
                *loop_tools,
            )
        )
        rendered[name] = {
            **render,
            "taskSpecSha256": task_sha,
        }
        # Persist after every expensive NRT render.  A process interruption can
        # then resume from hash-verified captures instead of treating them as
        # untrusted, overwriteable files.
        _write_json_atomic(state_path, state)


def _source_coverage(plan: Mapping[str, Any], captures: Mapping[str, Mapping[str, Any]]) -> dict[str, Any]:
    """Prove each retained source identity was scheduled by an emitted event take.

    A full event is not source-isolated, so this evidence is intentionally about
    FMOD's scheduled diagnostic identities.  Random branches that never appear
    in the deterministic node/take set are reported as missing and keep the
    family blocked rather than being represented by a lucky take.
    """

    scopes: list[tuple[str, list[Mapping[str, Any]], list[str]]] = []
    for perspective_name, perspective in plan["perspectives"].items():
        scopes.append(
            (
                perspective_name,
                list(perspective["requiredSourceCoverage"]),
                [str(node["temporaryAssetName"]) for node in current_nodes(perspective)],
            )
        )
    for event in plan["effects"]:
        scopes.append(
            (
                str(event["eventPath"]),
                list(event["runtimeLifecycleParameterVariantContract"]["variants"]),
                [str(node["temporaryAssetName"]) for node in event["nodes"]],
            )
        )
    result: list[dict[str, Any]] = []
    for scope, required, names in scopes:
        scheduled = {
            diagnostic
            for name in names
            for diagnostic in captures.get(name, {}).get("scheduledDiagnosticNames", [])
        }
        missing = [
            {"sourceGuid": item["sourceGuid"], "diagnosticName": item["diagnosticName"]}
            for item in required
            if item["diagnosticName"] not in scheduled
        ]
        required_isolated_names = [
            name
            for name in names
            if captures.get(name, {}).get("requiredDiagnosticName") is not None
        ]
        contaminated = [
            name
            for name in required_isolated_names
            if captures.get(name, {}).get("sourceIsolatedFullEventContribution") is not True
        ]
        result.append(
            {
                "scope": scope,
                "expectedSourceCount": len(required),
                "scheduledDiagnosticNames": sorted(scheduled),
                "missing": missing,
                "unprovenIsolatedContributions": contaminated,
                "pass": not missing and not contaminated,
            }
        )
    return {"allRetainedSourceIdentitiesCovered": all(item["pass"] for item in result), "scopes": result}


def _dynamic_trajectory_specs(perspective: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Fixed DSP-block trajectories that exercise every runtime mode/switch."""

    axis = [float(value) for value in current_axes(perspective)["rpm"]]
    low = next((value for value in axis if value > 0.0), axis[0])
    high = axis[-2] if len(axis) > 2 else axis[-1]
    middle = (low + high) * 0.5
    def series(
        identifier: str,
        mode: str,
        seconds: float,
        values: Any,
    ) -> dict[str, Any]:
        blocks = int(seconds * 48_000 // 256)
        return {
            "id": identifier,
            "mode": mode,
            "durationFrames": blocks * 256,
            "points": [values(index / (blocks - 1)) for index in range(blocks)],
        }

    def held_ramp(t: float, start: float, end: float) -> float:
        return min(1.0, max(0.0, (t - start) / max(1.0e-9, end - start)))

    return [
        # Six seconds exercises multiple cells at a physically meaningful
        # rate, with a half-second settlement at each endpoint.
        series("load_slow_rpm_ramp", "LOAD", 6.0, lambda t: (low + (high - low) * held_ramp(t, 1.0 / 12.0, 11.0 / 12.0), 1.0)),
        # A fast transition still holds both ends long enough to observe the
        # runtime's post-crossing phase preparation and cell hand-off.
        series("load_fast_rpm_ramp", "LOAD", 3.0, lambda t: (low + (high - low) * held_ramp(t, 1.0 / 6.0, 1.0 / 3.0), 1.0)),
        series("coast_rpm_ramp", "COAST", 6.0, lambda t: (high - (high - low) * held_ramp(t, 1.0 / 12.0, 11.0 / 12.0), 0.0)),
        series("both_throttle_sweep", "BOTH", 6.0, lambda t: (middle, held_ramp(t, 1.0 / 12.0, 11.0 / 12.0))),
        series("both_rpm_reversal", "BOTH", 6.0, lambda t: (low + (high - low) * (held_ramp(t, 1.0 / 12.0, 0.5) if t <= 0.5 else 1.0 - held_ramp(t, 0.5, 11.0 / 12.0)), 0.5)),
        series("both_steady_hold", "BOTH", 4.0, lambda _t: (middle, 0.37)),
    ]


def _dynamic_boundary_peak(stereo: Sequence[float], block_frames: int = 256) -> float:
    return max(
        (
            max(
                abs(stereo[frame * 2 + channel] - stereo[(frame - 1) * 2 + channel])
                for channel in (0, 1)
            )
            for frame in range(block_frames, len(stereo) // 2, block_frames)
        ),
        default=0.0,
    )


def _dynamic_window_metrics(
    reference: Sequence[float], reconstruction: Sequence[float]
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Require phase-invariant parity throughout a long parameter trajectory."""

    frames = len(reference) // 2
    if frames != len(reconstruction) // 2 or frames < ANALYSIS_FRAMES:
        raise NrtRecipeError("dynamic oracle PCM is too short or mismatched")
    window_frames = ANALYSIS_FRAMES
    # Six evenly distributed windows include both settled ends and the moving
    # portions of the trajectory; envelope error remains local to every window.
    starts = sorted(
        {
            round((frames - window_frames) * fraction / 5)
            for fraction in range(6)
        }
    )
    windows: list[dict[str, Any]] = []
    for start in starts:
        end = start + window_frames
        metrics = phase_invariant_metrics(
            reference[start * 2 : end * 2], reconstruction[start * 2 : end * 2]
        )
        windows.append({"startFrame": start, "frameCount": window_frames, "metrics": metrics})
    pitch_errors = [
        abs(float(item["metrics"]["pitchErrorCents"]))
        for item in windows
        if item["metrics"]["pitchErrorCents"] is not None
    ]
    aggregate = {
        "method": "worstOfSixPhaseInvariantTrajectoryWindows-v1",
        "rawPcmNrmseUsed": False,
        "envelopeNormalizedRmsError": max(
            float(item["metrics"]["envelopeNormalizedRmsError"])
            for item in windows
        ),
        "maximumBandEnergyErrorDb": max(
            float(item["metrics"]["maximumBandEnergyErrorDb"])
            for item in windows
        ),
        "gainErrorDb": max(
            abs(float(item["metrics"]["gainErrorDb"])) for item in windows
        ),
        "pitchErrorCents": max(pitch_errors, default=None),
    }
    return aggregate, windows


def _reconstruct_dynamic_trajectory(
    perspective: Mapping[str, Any], node_directory: Path, points: Sequence[tuple[float, float]]
) -> list[float]:
    """Execute the same four-node, phase-preserving runtime policy offline."""

    axes = current_axes(perspective)
    nodes = {(float(node["rpm"]), float(node["throttle"])): node for node in current_nodes(perspective)}
    loaded = {key: _node_pcm(node_directory / str(node["temporaryAssetName"])) for key, node in nodes.items()}
    phases: dict[tuple[float, float], float] = {}
    active: set[tuple[float, float]] = set()
    history: list[float] = []
    result: list[float] = []
    for rpm, throttle in points:
        corners = sorted(
            _bilinear_corners([float(value) for value in axes["rpm"]], [float(value) for value in axes["throttle"]], rpm, throttle),
            key=lambda item: (-item[2], item[0], item[1]),
        )
        chunk = [0.0] * (256 * 2)
        cold_reference: list[float] | None = None
        cold_mix = [0.0] * (CORRELATION_FRAMES * 2)
        # A hot cell owns all unique lower/upper corners, including the
        # zero-gain neighbour at an exact axis boundary.  Mapping/alignment and
        # phase advance are never gated by its instantaneous bilinear gain;
        # only its contribution to `chunk` is skipped at gain zero.
        next_active = {
            (node_rpm, node_throttle)
            for node_rpm, node_throttle, _weight in corners
        }
        for node_rpm, node_throttle, weight in corners:
            key = (node_rpm, node_throttle)
            samples, start, end = loaded[key]
            ratio = 1.0 if node_rpm <= 0.0 else min(4.0, max(0.1, rpm / node_rpm))
            phase = phases.get(key, float(nodes[key].get("phaseOffsetFrames", 0.0)))
            if key not in active:
                reference = history[-CORRELATION_FRAMES * 2 :] if len(history) >= CORRELATION_FRAMES * 2 else cold_reference
                if reference is not None:
                    phase += _coarse_alignment(reference, (samples, start, end, ratio), base_phase_offset=phase)
                    # A history correlation makes the candidate match the
                    # already-emitted 960-frame tail.  Its next emitted frame
                    # must continue after that window, exactly as Android's
                    # `continueAfterHistory=true` mapCell path does.  Cold
                    # start aligns to a sibling reference only, so it begins
                    # at the selected offset rather than skipping 960 frames.
                    if len(history) >= CORRELATION_FRAMES * 2:
                        phase += CORRELATION_FRAMES * ratio
            synthesized = _resampled_loop(samples, start, end, ratio=ratio, phase_offset=phase, frames=CORRELATION_FRAMES)
            if weight > 0.0:
                for index, value in enumerate(synthesized):
                    cold_mix[index] += weight * value
                for index, value in enumerate(synthesized[: 256 * 2]):
                    chunk[index] += weight * value
            if len(history) < CORRELATION_FRAMES * 2 and weight > 0.0:
                # At cold start the reference is the synthesized weighted mix
                # of already processed audible corners.  Zero-gain corners are
                # aligned to it but never added to the mix.
                cold_reference = cold_mix.copy()
            phases[key] = phase + ratio * 256.0
        active = next_active
        history.extend(chunk)
        history = history[-CORRELATION_FRAMES * 2 :]
        result.extend(chunk)
    return result


def _run_dynamic_oracle(
    plan: Mapping[str, Any], state: dict[str, Any], state_path: Path, node_directory: Path, renderer: Any, bank: Path
) -> list[dict[str, Any]]:
    """Compare scheduled FMOD parameter trajectories with runtime-realizable PCM."""

    reports: list[dict[str, Any]] = []
    warmup = 36_096
    for perspective_name in ("cabin", "exterior"):
        perspective = plan["perspectives"][perspective_name]
        for spec in _dynamic_trajectory_specs(perspective):
            points = [(float(rpm), float(throttle)) for rpm, throttle in spec["points"]]
            key = f"{perspective_name}:{spec['id']}"
            output = node_directory / "dynamic" / f"{hashlib.sha256(key.encode()).hexdigest()}.wav"
            updates = [(warmup + index * 256, {"rpms": rpm, "throttle": throttle}) for index, (rpm, throttle) in enumerate(points)]
            task_sha = hashlib.sha256(canonical_json_bytes({
                "kind": "sharedSystemDynamicEngineTrajectoryWithChannelSnapshots-v2",
                "trajectory": spec,
                "warmup": warmup,
                "updates": updates,
                "bank": plan["bankSha256"],
                "hostGainLinear": 0.5,
                "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
            })).hexdigest()
            cached = state.setdefault("dynamicCaptures", {}).get(key)
            if isinstance(cached, Mapping) and output.is_file() and cached.get("taskSpecSha256") == task_sha and cached.get("wavSha256") == _sha256(output):
                capture = cached
            else:
                if output.exists():
                    raise NrtRecipeError(f"cached dynamic trajectory differs: {output}")
                output.parent.mkdir(parents=True, exist_ok=True)
                rendered = renderer.render_event_mix(
                    bank,
                    output,
                    duration_frames=len(points) * 256,
                    warmup_frames=warmup,
                    events=[{
                        "eventName": perspective["eventPath"],
                        "startFrame": 0,
                        "hostGainLinear": 0.5,
                        "parameters": {"rpms": points[0][0], "throttle": points[0][1]},
                        "parameterUpdates": updates,
                    }],
                )
                capture = {
                    "wavSha256": _sha256(output),
                    "eventPath": rendered.event_paths[0],
                    "taskSpecSha256": task_sha,
                    "scheduledSoundNamesByInstance": [
                        list(names) for names in rendered.scheduled_sound_names_by_instance
                    ],
                    "channelSnapshots": list(rendered.channel_snapshots),
                }
                state["dynamicCaptures"][key] = capture
                _write_json_atomic(state_path, state)
            pcm, _frames, _rate, _channels = _read_pcm(output, require_nrt_format=True)
            # The shared reference already received the exact 0.5 engine host
            # gain before FMOD rendered it.  Apply only the common causal
            # master stage; applying the engine host gain a second time would
            # make this channel-snapshot-capable path disagree with Android.
            reference = _apply_post_sum_master(
                _pcm_frames(pcm)[: len(points) * 256 * 2]
            )
            reconstruction = _apply_host_mix_contract(
                _reconstruct_dynamic_trajectory(perspective, node_directory, points)
            )
            metrics, windows = _dynamic_window_metrics(reference, reconstruction)
            reference_boundary = _dynamic_boundary_peak(reference)
            reconstruction_boundary = _dynamic_boundary_peak(reconstruction)
            boundary_excess = max(0.0, reconstruction_boundary - reference_boundary)
            passed = _metrics_pass(metrics, plan["refinementGate"]) and boundary_excess <= 0.08
            reports.append({"perspective": perspective_name, "id": spec["id"], "mode": spec["mode"], "durationFrames": spec["durationFrames"], "oracleWavSha256": capture["wavSha256"], "scheduledSoundNamesByInstance": capture["scheduledSoundNamesByInstance"], "channelSnapshots": capture["channelSnapshots"], "reconstructionSha256": hashlib.sha256(canonical_json_bytes({"metrics": metrics, "boundaryExcess": boundary_excess, "windows": windows})).hexdigest(), "metrics": metrics, "windows": windows, "boundaryDiscontinuityExcessLinear": boundary_excess, "pass": passed})
    return reports


def _effect_group_targets(plan: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Select one exact emitted source-node target for each scheduling group."""

    result: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for event in sorted(plan["effects"], key=lambda item: str(item["eventPath"])):
        variants = event["runtimeLifecycleParameterVariantContract"]["variants"]
        for variant in sorted(variants, key=lambda item: str(item["sourceGuid"])):
            scheduler = variant.get("schedulingGroup")
            if not isinstance(scheduler, Mapping):
                continue
            group_id = str(scheduler.get("groupId") or "")
            key = (str(event["eventPath"]), group_id)
            if not group_id or key in seen:
                continue
            source_guid = str(variant["sourceGuid"])
            matching_nodes = sorted(
                (
                    node
                    for node in event["nodes"]
                    if str(node.get("requiredSourceGuid") or "") == source_guid
                ),
                key=lambda node: canonical_json_bytes(node["parameters"]),
            )
            if not matching_nodes:
                raise NrtRecipeError(
                    f"scheduling group {group_id} has no emitted source node"
                )
            mapping = variant.get("runtimeMapping")
            perspectives = mapping.get("perspectives") if isinstance(mapping, Mapping) else None
            if not isinstance(perspectives, Sequence) or isinstance(perspectives, (str, bytes)) or not perspectives:
                raise NrtRecipeError(
                    f"effect variant {variant['sourceGuid']} has no explicit perspective scope"
                )
            result.append(
                {
                    "event": event,
                    "variant": variant,
                    "node": matching_nodes[0],
                    "groupId": group_id,
                    "perspective": str(sorted(perspectives)[0]),
                }
            )
            seen.add(key)
    return result


def _effect_node_signal(node: Mapping[str, Any], path: Path, frames: int) -> list[float]:
    """Use the exact effect node/lifecycle semantics for one host contribution."""

    pcm, frame_count, _rate, _channels = _read_pcm(path, require_nrt_format=True)
    signal = _pcm_frames(pcm)
    if str(node["lifetime"]) == "continuous":
        loop = _read_smpl_loop(path)
        if loop is None:
            raise NrtRecipeError(f"continuous effect node has no loop: {path.name}")
        return _resampled_loop(
            signal, loop[0], loop[1], ratio=1.0, phase_offset=0.0, frames=frames
        )
    signal = signal[: frame_count * 2]
    return (signal + [0.0] * (frames * 2))[: frames * 2]


def _effect_axis_corners(
    axis: Sequence[float], value: float
) -> list[tuple[float, float]]:
    """Return Android-identical lower/upper weights for one effect axis."""

    if not axis:
        raise NrtRecipeError("effect interpolation axis is empty")
    ordered = [float(item) for item in axis]
    if ordered != sorted(ordered) or len(set(ordered)) != len(ordered):
        raise NrtRecipeError("effect interpolation axis is not strictly ordered")
    if value <= ordered[0]:
        return [(ordered[0], 1.0)]
    if value >= ordered[-1]:
        return [(ordered[-1], 1.0)]
    for lower, upper in zip(ordered, ordered[1:]):
        if lower <= value < upper:
            mix = (value - lower) / (upper - lower)
            return [(lower, 1.0 - mix), (upper, mix)]
    raise NrtRecipeError("effect interpolation value is outside its authored axis")


def _finite_effect_multilinear_corners(
    variant: Mapping[str, Any], parameters: Mapping[str, Any]
) -> list[tuple[dict[str, float], float]]:
    """Exact runtime N-D lower/upper raw-weight contributor set.

    This is intentionally independent of an FMOD target render.  It is the
    same information a finite ring materializer has when it chooses nodes at a
    semantic trigger, so the effect oracle cannot tune individual corners to
    the target after seeing it.
    """

    axes = variant.get("parameterAxes")
    if not isinstance(axes, Mapping):
        raise NrtRecipeError("finite effect variant has no parameter axes")
    dimensions: list[tuple[str, list[tuple[float, float]]]] = []
    for name in sorted(axes):
        values = axes[name]
        if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
            raise NrtRecipeError("finite effect axis is malformed")
        if name not in parameters:
            raise NrtRecipeError(f"finite effect parameters lack axis {name}")
        dimensions.append((str(name), _effect_axis_corners(values, float(parameters[name]))))
    base = {
        str(key): float(value)
        for key, value in dict(variant.get("parameters") or {}).items()
    }
    combined: dict[bytes, tuple[dict[str, float], float]] = {}
    for choices in itertools.product(*(values for _name, values in dimensions)):
        state = dict(base)
        weight = 1.0
        for (name, _values), (point, point_weight) in zip(dimensions, choices):
            state[name] = point
            weight *= point_weight
        key = canonical_json_bytes(state)
        previous = combined.get(key)
        combined[key] = (state, weight + (0.0 if previous is None else previous[1]))
    return [
        (state, weight)
        for _key, (state, weight) in sorted(combined.items())
        if weight > 0.0
    ]


def _effect_midpoint_probes(variant: Mapping[str, Any]) -> list[dict[str, float]]:
    axes = variant.get("parameterAxes")
    if not isinstance(axes, Mapping):
        return []
    base = {
        str(key): float(value)
        for key, value in dict(variant.get("parameters") or {}).items()
    }
    dimensions: list[tuple[str, list[float]]] = []
    for name in sorted(axes):
        values = [float(value) for value in axes[name]]
        if len(values) < 2:
            continue
        dimensions.append((str(name), _midpoints(values)))
    if not dimensions:
        return []
    return [
        {**base, **dict(zip((name for name, _values in dimensions), values))}
        for values in itertools.product(*(values for _name, values in dimensions))
    ]


def _finite_effect_interpolation_oracle(
    plan: Mapping[str, Any],
    state: dict[str, Any],
    state_path: Path,
    node_directory: Path,
    renderer: Any,
    bank: Path,
    graph: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
    loop_tools: tuple[Any, Any],
) -> dict[str, Any]:
    """Compare source-solo intermediate probes to the literal finite-ring mix.

    Every reconstruction is formed from the source's deterministic lower/upper
    N-D corner set before its target render is read.  Failed probes remain
    evidence and block release; refinement of source axes is deliberately not
    hidden behind a nearest-node fallback.
    """

    reports: list[dict[str, Any]] = []
    captures = state.setdefault("effectInterpolationProbeCaptures", {})
    for event in sorted(plan["effects"], key=lambda item: str(item["eventPath"])):
        variants = event["runtimeLifecycleParameterVariantContract"]["variants"]
        for variant in sorted(variants, key=lambda item: str(item["sourceGuid"])):
            if variant.get("lifetime") == "continuous":
                continue
            probes = _effect_midpoint_probes(variant)
            source_guid = _source_guid(variant.get("sourceGuid"))
            source_nodes = {
                canonical_json_bytes(dict(node.get("parameters") or {})): node
                for node in event["nodes"]
                if _source_guid(node.get("requiredSourceGuid")) == source_guid
            }
            if not source_nodes:
                raise NrtRecipeError(f"finite interpolation source {source_guid} has no nodes")
            for parameters in probes:
                corners = _finite_effect_multilinear_corners(variant, parameters)
                matching: list[tuple[Mapping[str, Any], float]] = []
                for corner_parameters, weight in corners:
                    node = source_nodes.get(canonical_json_bytes(corner_parameters))
                    if node is None:
                        raise NrtRecipeError(
                            f"finite interpolation source {source_guid} lacks exact corner {corner_parameters}"
                        )
                    matching.append((node, weight))
                duration = max(
                    ANALYSIS_FRAMES,
                    *(int(node["durationFrames"]) for node, _weight in matching),
                )
                target = {
                    **dict(matching[0][0]),
                    "parameters": parameters,
                    "durationFrames": duration,
                    "warmupFrames": 0,
                    "temporaryAssetName": "effect-interpolation-probe.wav",
                }
                task = {
                    "kind": "finiteNDimensionalInterpolation-v1",
                    "bank": plan["bankSha256"],
                    "event": event["eventPath"],
                    "sourceGuid": source_guid,
                    "parameters": parameters,
                    "corners": [
                        {"parameters": values, "rawWeight": weight}
                        for values, weight in corners
                    ],
                    "durationFrames": duration,
                }
                task_sha = hashlib.sha256(canonical_json_bytes(task)).hexdigest()
                output = node_directory / "effect-interpolation-probes" / f"{task_sha}.wav"
                cache = captures.get(task_sha)
                if not (
                    isinstance(cache, Mapping)
                    and output.is_file()
                    and cache.get("taskSpecSha256") == task_sha
                    and cache.get("wavSha256") == _sha256(output)
                ):
                    if output.exists():
                        raise NrtRecipeError(f"effect interpolation cache differs: {output}")
                    output.parent.mkdir(parents=True, exist_ok=True)
                    render = _render_isolated_effect_node(
                        renderer,
                        bank,
                        str(event["eventPath"]),
                        target,
                        output,
                        graph,
                        create_isolated_bank_copy,
                        fully_muted_multi_instrument_guids,
                        loop_tools,
                    )
                    cache = {
                        "taskSpecSha256": task_sha,
                        "wavSha256": _sha256(output),
                        "scheduledDiagnosticNames": render.get("scheduledDiagnosticNames"),
                        "sourceIsolationMethod": render.get("sourceIsolationMethod"),
                    }
                    captures[task_sha] = cache
                    _write_json_atomic(state_path, state)
                reference_pcm, _frames, _rate, _channels = _read_pcm(
                    output, require_nrt_format=True
                )
                reference = (_pcm_frames(reference_pcm) + [0.0] * (duration * 2))[: duration * 2]
                reconstruction = [0.0] * (duration * 2)
                for node, weight in matching:
                    signal = _effect_node_signal(
                        node,
                        node_directory / str(node["temporaryAssetName"]),
                        duration,
                    )
                    for index, value in enumerate(signal):
                        reconstruction[index] += weight * value
                metrics = phase_invariant_metrics(reference, reconstruction)
                passed = _metrics_pass(metrics, plan["refinementGate"])
                reports.append(
                    {
                        "eventPath": event["eventPath"],
                        "sourceGuid": source_guid,
                        "parameters": parameters,
                        "cornerCount": len(corners),
                        "cornerWeights": [
                            {"parameters": values, "rawWeight": weight}
                            for values, weight in corners
                        ],
                        "oracleWavSha256": cache["wavSha256"],
                        "metrics": metrics,
                        "pass": passed,
                    }
                )
    by_source: dict[tuple[str, str], list[Mapping[str, Any]]] = {}
    for report in reports:
        by_source.setdefault((str(report["eventPath"]), str(report["sourceGuid"])), []).append(report)
    source_residuals = [
        {
            "eventPath": event_path,
            "sourceGuid": source_guid,
            "probeCount": len(items),
            "allPass": all(item["pass"] for item in items),
            "maximumEnvelopeNormalizedRmsError": max(
                float(item["metrics"]["envelopeNormalizedRmsError"]) for item in items
            ),
            "maximumBandEnergyErrorDb": max(
                float(item["metrics"]["maximumBandEnergyErrorDb"]) for item in items
            ),
            "maximumAbsoluteGainErrorDb": max(
                abs(float(item["metrics"]["gainErrorDb"])) for item in items
            ),
        }
        for (event_path, source_guid), items in sorted(by_source.items())
    ]
    return {
        "schema": "byd-full-event-finite-interpolation-oracle-v1",
        "runtimeAlgorithm": "perSourceAxisAlignedMultilinearFiniteRing-v2",
        "allPass": all(item["pass"] for item in reports),
        "probeCount": len(reports),
        "probes": reports,
        "sourceResiduals": source_residuals,
    }


def _playlist_selection_oracle(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Execute real authored playlist data twice and retain a reproducible trace."""

    traces: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    profile_audio_session_generation = 1
    for event in plan["effects"]:
        for variant in event["runtimeLifecycleParameterVariantContract"]["variants"]:
            mapping = variant.get("runtimeMapping")
            perspectives = mapping.get("perspectives") if isinstance(mapping, Mapping) else []
            scheduler = variant.get("schedulingGroup")
            if not isinstance(scheduler, Mapping) or scheduler.get("composition") != "playlistAlternative":
                continue
            group_id = str(scheduler.get("groupId") or "")
            identity = (str(event["eventPath"]), group_id)
            if not group_id or identity in seen:
                continue
            selection = scheduler.get("selection")
            members = scheduler.get("members")
            if not isinstance(selection, Mapping) or not isinstance(members, Sequence):
                traces.append({"eventPath": event["eventPath"], "groupId": group_id, "pass": False, "reason": "playlistSchemaMissingSelectionOrMembers"})
                continue
            play_mode = str(selection.get("playMode") or "")
            state = playlist_seed(
                plan["id"],
                str(event["eventPath"]),
                profile_audio_session_generation,
                group_id,
            )
            cursor = 0
            last: int | None = None
            outcomes: list[dict[str, Any]] = []
            try:
                for _ in range(64):
                    outcome = select_playlist_member(
                        play_mode=play_mode,
                        members=members,
                        group_trigger_chance_percent=scheduler.get("groupTriggerChancePercent"),
                        state=state,
                        sequential_cursor=cursor,
                        last_selected_order=last,
                    )
                    outcomes.append(outcome)
                    state = int(outcome["state"])
                    cursor = int(outcome["sequentialCursor"])
                    last = outcome["lastSelectedOrder"]
                # Repeat from seed to prove deterministic PRNG, chance draw,
                # cursor, history, and weighted boundary behavior as one trace.
                replay_state = playlist_seed(
                    plan["id"],
                    str(event["eventPath"]),
                    profile_audio_session_generation,
                    group_id,
                )
                replay_cursor = 0
                replay_last: int | None = None
                replay: list[dict[str, Any]] = []
                for _ in range(64):
                    outcome = select_playlist_member(
                        play_mode=play_mode,
                        members=members,
                        group_trigger_chance_percent=scheduler.get("groupTriggerChancePercent"),
                        state=replay_state,
                        sequential_cursor=replay_cursor,
                        last_selected_order=replay_last,
                    )
                    replay.append(outcome)
                    replay_state = int(outcome["state"])
                    replay_cursor = int(outcome["sequentialCursor"])
                    replay_last = outcome["lastSelectedOrder"]
                selected = [item["selectedOrder"] for item in outcomes if item["selectedOrder"] is not None]
                authored_orders = [int(member["authoredOrder"]) for member in members]
                sequential_ok = (
                    play_mode != "PlaylistPlayMode_PlaySequential"
                    or selected == [authored_orders[index % len(authored_orders)] for index in range(len(selected))]
                )
                traces.append(
                    {
                        "eventPath": event["eventPath"],
                        "groupId": group_id,
                        "perspectives": sorted(
                            str(value) for value in perspectives
                        ),
                        "profileAudioSessionGeneration": profile_audio_session_generation,
                        "playMode": play_mode,
                        "selectionCount": len(selected),
                        "selectedOrders": selected,
                        "traceSha256": hashlib.sha256(canonical_json_bytes(outcomes)).hexdigest(),
                        "pass": outcomes == replay and sequential_ok,
                    }
                )
            except (TypeError, ValueError, KeyError) as exc:
                traces.append({"eventPath": event["eventPath"], "groupId": group_id, "pass": False, "reason": str(exc)})
            seen.add(identity)
    return {"schema": "byd-full-event-playlist-selection-oracle-v1", "allPass": all(item["pass"] for item in traces), "groupCount": len(traces), "groups": traces}


def _placement_contains(value: float, placement: Mapping[str, Any]) -> bool:
    """Evaluate one authored FMOD parameter placement exactly."""

    start = float(placement["start"])
    end = float(placement["end"])
    if not math.isfinite(value) or not math.isfinite(start) or not math.isfinite(end):
        raise NrtRecipeError("parameter-placement membership is non-finite")
    if end < start:
        raise NrtRecipeError("parameter-placement end precedes start")
    return value >= start and (value <= end if placement.get("includeEnd") is True else value < end)


def _placement_membership(
    placement_entry: Mapping[str, Any], parameters: Mapping[str, Any]
) -> bool:
    """Apply every parameter and ancestor placement as an AND gate."""

    membership = placement_entry.get("membership")
    if not isinstance(membership, Mapping):
        raise NrtRecipeError("parameter-placement entry has no membership contract")
    if membership.get("parameterCombination") != "allParameterGroupsMustContainCurrentValue":
        raise NrtRecipeError("parameter-placement parameter combination is not executable")
    if membership.get("placementsWithinParameter") != "allInstrumentChainPlacementsMustContainCurrentValue":
        raise NrtRecipeError("parameter-placement chain combination is not executable")
    groups = membership.get("placements")
    if not isinstance(groups, Mapping) or not groups:
        raise NrtRecipeError("parameter-placement entry has no authored placements")
    for parameter, placements in groups.items():
        if parameter not in parameters:
            raise NrtRecipeError(
                f"parameter-placement host state lacks {parameter!r}"
            )
        if not isinstance(placements, Sequence) or isinstance(placements, (str, bytes)):
            raise NrtRecipeError("parameter-placement list is malformed")
        value = float(parameters[parameter])
        if not all(
            isinstance(placement, Mapping) and _placement_contains(value, placement)
            for placement in placements
        ):
            return False
    return True


def _placement_outside_values(
    placement_entry: Mapping[str, Any],
    parameter_domains: Mapping[str, Any],
    inside: Mapping[str, float],
    parameter: str,
) -> list[tuple[str, float]]:
    """Return deterministic low/high values that make this source leave its gate."""

    raw_domain = parameter_domains.get(parameter)
    if not isinstance(raw_domain, Sequence) or isinstance(raw_domain, (str, bytes)) or len(raw_domain) != 2:
        raise NrtRecipeError(f"parameter-placement {parameter!r} has no authored domain")
    lower, upper = (float(raw_domain[0]), float(raw_domain[1]))
    if not math.isfinite(lower) or not math.isfinite(upper) or upper < lower:
        raise NrtRecipeError(f"parameter-placement {parameter!r} domain is invalid")
    values: list[tuple[str, float]] = []
    for direction, candidate in (("increasing", lower), ("decreasing", upper)):
        trial = dict(inside)
        trial[parameter] = candidate
        if not _placement_membership(placement_entry, trial):
            values.append((direction, candidate))
    return values


def _placement_entry_targets(plan: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Find finite sources needing placement-entry lifecycle evidence.

    A source with a blocked topology intentionally has no executable Android
    trigger yet.  It still needs the exact same original-bank source-solo
    probe as a promoted ``PARAMETER_PLACEMENT_ENTRY`` source, so discovery
    must use its retained placement contract rather than the absence of that
    deliberately withheld trigger.
    """

    result: list[dict[str, Any]] = []
    for event in sorted(plan["effects"], key=lambda item: str(item["eventPath"])):
        for variant in event["runtimeLifecycleParameterVariantContract"]["variants"]:
            mapping = variant.get("runtimeMapping") or {}
            lifecycles = mapping.get("semanticLifecycle") or []
            placement_lifecycle = next(
                (
                    lifecycle
                    for lifecycle in lifecycles
                    if isinstance(lifecycle, Mapping)
                    and lifecycle.get("trigger") == "PARAMETER_PLACEMENT_ENTRY"
                ),
                None,
            )
            topology = mapping.get("finiteLifecycleTopology")
            blocked_topology = (
                isinstance(topology, Mapping)
                and topology.get("status")
                == "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE"
            )
            entry = (
                placement_lifecycle.get("parameterPlacementEntry")
                if isinstance(placement_lifecycle, Mapping)
                else mapping.get("parameterPlacementEntry")
            )
            if placement_lifecycle is None and not (
                blocked_topology and isinstance(entry, Mapping)
            ):
                continue
            if variant.get("lifetime") == "continuous":
                raise NrtRecipeError("continuous source uses finite placement-entry lifecycle")
            if not isinstance(entry, Mapping):
                raise NrtRecipeError("placement-entry lifecycle lacks exact placement contract")
            binding_key = str(variant.get("authoredBindingKey") or "")
            if not binding_key:
                raise NrtRecipeError(
                    "placement-entry source has no authored binding identity"
                )
            matching_nodes = [
                node
                for node in event["nodes"]
                if _source_guid(node.get("requiredSourceGuid"))
                == _source_guid(variant.get("sourceGuid"))
                and str(node.get("requiredAuthoredBindingKey") or "")
                == binding_key
            ]
            if not matching_nodes:
                raise NrtRecipeError(
                    "placement-entry authored binding has no emitted PCM nodes"
                )
            result.append(
                {
                    "event": event,
                    "variant": variant,
                    "entry": entry,
                    "blockedTopology": blocked_topology,
                    "nodes": matching_nodes,
                }
            )
    return result


def _parameter_placement_lifecycle_oracle(
    plan: Mapping[str, Any],
    state: dict[str, Any],
    state_path: Path,
    node_directory: Path,
    renderer: Any,
    bank: Path,
    graph: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
) -> dict[str, Any]:
    """Prove source-solo FMOD initial-entry and both-direction re-entry.

    This is deliberately callback/evidence based, not a best-fitting PCM
    comparison.  Each run mutes every other waveform in the source event, so
    a duplicated diagnostic name in a sibling cannot certify the wrong GUID.
    """

    reports: list[dict[str, Any]] = []
    for target in _placement_entry_targets(plan):
        event = target["event"]
        variant = target["variant"]
        entry = target["entry"]
        source_guid = _source_guid(variant.get("sourceGuid"))
        diagnostic_name = str(variant.get("diagnosticName") or "")
        if not diagnostic_name:
            raise NrtRecipeError(
                f"placement-entry source {source_guid} has no callback diagnostic identity"
            )
        raw_capture_parameters = variant.get("parameters")
        if not isinstance(raw_capture_parameters, Mapping):
            raise NrtRecipeError("placement-entry source has no authored capture parameters")
        capture_inside = {
            str(key): float(value) for key, value in raw_capture_parameters.items()
        }
        if not _placement_membership(entry, capture_inside):
            raise NrtRecipeError(
                f"placement-entry source {source_guid} capture parameters are not inside its gate"
            )
        runtime_mapping = variant.get("runtimeMapping") or {}
        raw_runtime_parameters = runtime_mapping.get("parameters")
        if not isinstance(raw_runtime_parameters, Mapping):
            raise NrtRecipeError("placement-entry source has no retained runtime defaults")
        runtime_parameters = {
            str(key): float(value) for key, value in raw_runtime_parameters.items()
        }
        domains = (variant.get("runtimeMapping") or {}).get("parameterDomains")
        if not isinstance(domains, Mapping):
            raise NrtRecipeError("placement-entry source has no retained parameter domains")
        membership = entry["membership"]
        groups = membership["placements"]
        parameter_values = membership.get("parameterValues")
        if not isinstance(parameter_values, Sequence):
            raise NrtRecipeError("placement-entry source has no parameter value contract")
        host_bound_parameters = {
            str(item.get("parameter"))
            for item in parameter_values
            if isinstance(item, Mapping)
            and isinstance(item.get("value"), Mapping)
            and item["value"].get("kind") == "hostBinding"
        }
        # The lifecycle starts from actual graph defaults. Host-owned values
        # are substituted with an inside test point; static parameters retain
        # the exact graph default. This catches a dormant source whose capture
        # node was intentionally rendered at an inside midpoint.
        inside = dict(runtime_parameters)
        for parameter in host_bound_parameters:
            if parameter not in capture_inside:
                raise NrtRecipeError(
                    f"placement-entry host parameter {parameter!r} lacks a capture point"
                )
            inside[parameter] = capture_inside[parameter]
        runtime_initial_membership = _placement_membership(entry, inside)
        selection_capture = _effective_source_selection_capture(
            _source_selection_capture_contract(graph, source_guid), variant
        )
        initial_candidate_names = _scheduling_group_diagnostic_names(graph, variant)
        initial_outside: dict[str, float] | None = None
        for parameter in sorted(groups):
            if parameter not in host_bound_parameters:
                continue
            outside_values = _placement_outside_values(
                entry, domains, inside, parameter
            )
            if outside_values:
                initial_outside = dict(inside)
                initial_outside[parameter] = outside_values[0][1]
                break

        if runtime_initial_membership and selection_capture["multiParentGuids"]:
            if initial_outside is None:
                raise NrtRecipeError(
                    "playlist placement source starts inside but has no host-controlled "
                    "outside state for exact sibling lifecycle coverage"
                )
            # A fresh FMOD EventInstance may deterministically choose the same
            # playlist sibling every time.  Begin inside once to retain real
            # initial-activation candidate evidence, then make exact
            # source-solo outside-to-inside entries in that *same* instance.
            # Only those later target takes prove this binding's GUID.
            vectors: list[dict[str, Any]] = [
                {
                    "id": "initial-runtime-state",
                    "parameters": initial_outside,
                    "startParameters": inside,
                    "takeLifecycle": "singleEventInstanceInitialInsideThenPlacementReentry-v1",
                    "expectTarget": True,
                    "audibleParameters": inside,
                    "directions": ["initialRuntimeState", "selectionReentry"],
                    "initialActivationCandidateNames": initial_candidate_names,
                    "initialActivationTakeIndex": 0,
                    "initialActivationIdentityResolution": "candidateSetOnly",
                }
            ]
        else:
            # Direct sources can be identified at fresh Start.  A playlist
            # source that is initially outside records its group candidate set
            # (which must stay silent) here and is proven exactly by the
            # source-solo reentry/provenance vectors below.
            vectors = [
                {
                    "id": "initial-runtime-state",
                    "parameters": inside,
                    "startParameters": None,
                    "takeLifecycle": "newEventInstancePerTake-v1",
                    "expectTarget": (
                        runtime_initial_membership
                        and not bool(selection_capture["multiParentGuids"])
                    ),
                    "audibleParameters": inside,
                    "directions": ["initialRuntimeState"],
                    "initialActivationCandidateNames": initial_candidate_names,
                    "initialActivationTakeIndex": 0,
                    "initialActivationIdentityResolution": (
                        "exactTarget"
                        if not selection_capture["multiParentGuids"]
                        else "candidateSetOnly"
                    ),
                }
            ]
        if not runtime_initial_membership:
            # This is source-isolated FMOD provenance only. Android does not
            # write static/default parameters, but the run proves that the
            # retained source would become audible only if its actual graph
            # gate were explicitly put inside.
            if initial_outside is None:
                raise NrtRecipeError(
                    "dormant placement source has no host-controlled outside state "
                    "for exact source-solo provenance"
                )
            vectors.append(
                {
                    "id": "capture-inside-provenance",
                    "parameters": capture_inside,
                    "startParameters": initial_outside,
                    "takeLifecycle": "singleEventInstancePlacementReentry-v1",
                    "expectTarget": True,
                    "audibleParameters": capture_inside,
                    "directions": ["provenanceInside"],
                    "runtimeApplicable": False,
                }
            )
        for parameter in sorted(groups):
            if parameter not in host_bound_parameters:
                # This source is gated by an authored/default parameter (for
                # example Porsche's valved default), which Android never
                # rewrites. Its initial-inside vector above is the executable
                # lifecycle; inventing a host update would prove the wrong
                # runtime.
                continue
            outside_values = _placement_outside_values(
                entry, domains, inside, parameter
            )
            for direction, outside in outside_values:
                before = dict(inside)
                before[parameter] = outside
                if _placement_membership(entry, before):
                    raise NrtRecipeError("placement lifecycle outside vector remains inside")
                vectors.append(
                    {
                        "id": f"{parameter}-{direction}-reentry",
                        "parameters": inside,
                        "startParameters": before,
                        "takeLifecycle": "singleEventInstancePlacementReentry-v1",
                        "expectTarget": True,
                        "audibleParameters": inside,
                        "directions": [direction],
                    }
                )
            # The FMOD data records whether a placement includes its end
            # boundary. Exercise each authored end from a known outside state;
            # if an ancestor makes that endpoint outside, the expected result
            # is explicitly silence rather than a made-up inclusive range.
            if outside_values:
                boundary_before = dict(inside)
                boundary_before[parameter] = outside_values[0][1]
                for placement in groups[parameter]:
                    at_end = dict(inside)
                    at_end[parameter] = float(placement["end"])
                    vectors.append(
                        {
                            "id": f"{parameter}-{placement['instrumentGuid']}-end-boundary",
                            "parameters": at_end,
                            "startParameters": boundary_before,
                            "takeLifecycle": "singleEventInstancePlacementReentry-v1",
                            "expectTarget": _placement_membership(entry, at_end),
                            "audibleParameters": at_end,
                            "directions": ["endBoundary"],
                        }
                    )
        muted, _waveforms = _effect_isolation_muted_sources(
            graph, str(event["eventPath"]), source_guid
        )
        disabled = fully_muted_multi_instrument_guids(dict(graph), muted)
        source_tail_frames = max(
            int(node["durationFrames"])
            for node in target["nodes"]
        )
        maximum_takes = int(selection_capture["maximumTakes"])
        single_shot_parent_guids = list(selection_capture["singleShotParentGuids"])
        vector_reports: list[dict[str, Any]] = []
        for vector in vectors:
            # A source-solo lifecycle probe cannot assume an authored
            # playlist/SmartRandom branch on its first event start.  Reuse the
            # bounded exact-name take search used by finite-node realization:
            # every positive vector is accepted only from the take that
            # scheduled the required diagnostic source, while a negative
            # boundary vector records every take and fails if that source ever
            # appears.  A single shared-system render was not sufficient here:
            # it could observe a valid sibling and falsely fail (or certify)
            # this authored binding.
            duration_frames = 256 * math.ceil(
                (source_tail_frames + 256) / 256
            )
            audible_parameters = vector.get("audibleParameters")
            if not isinstance(audible_parameters, Mapping):
                raise NrtRecipeError("placement lifecycle vector lacks audible parameters")
            zero_gain_evidence = _exact_zero_gain_curve_evidence(
                graph, selection_capture, audible_parameters
            )
            expect_target = bool(vector["expectTarget"])
            audibility_expectation = (
                "authoredExactZeroGainSilent"
                if expect_target and zero_gain_evidence
                else (
                    "targetAudible"
                    if expect_target
                    else "notAssertedWhenTargetCallbackIsAbsent"
                )
            )
            task = {
                "kind": "parameterPlacementEntryLifecycle-v2-authoredTakeSearch",
                "bank": plan["bankSha256"],
                "event": event["eventPath"],
                "sourceGuid": source_guid,
                "authoredBindingKey": variant.get("authoredBindingKey"),
                "diagnosticName": diagnostic_name,
                "entry": entry,
                "parameters": vector["parameters"],
                "startParameters": vector["startParameters"],
                "takeLifecycle": vector["takeLifecycle"],
                "expectTarget": vector["expectTarget"],
                "audibleParameters": audible_parameters,
                "zeroGainEvidence": zero_gain_evidence,
                "audibilityExpectation": audibility_expectation,
                "initialActivationCandidateNames": vector.get(
                    "initialActivationCandidateNames"
                ),
                "initialActivationTakeIndex": vector.get(
                    "initialActivationTakeIndex"
                ),
                "initialActivationIdentityResolution": vector.get(
                    "initialActivationIdentityResolution"
                ),
                "selectionCapture": selection_capture,
                "maximumTakes": maximum_takes,
                "durationFrames": duration_frames,
            }
            task_sha = hashlib.sha256(canonical_json_bytes(task)).hexdigest()
            output = node_directory / "placement-lifecycle" / f"{task_sha}.wav"
            cache = state.setdefault("placementLifecycleCaptures", {}).get(task_sha)
            if not (
                isinstance(cache, Mapping)
                and output.is_file()
                and cache.get("taskSpecSha256") == task_sha
                and cache.get("wavSha256") == _sha256(output)
            ):
                if output.exists():
                    raise NrtRecipeError(f"placement lifecycle cache differs: {output}")
                output.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.TemporaryDirectory(
                    prefix="atlas-placement-lifecycle-isolation-", dir=output.parent
                ) as temporary_text:
                    isolated = create_isolated_bank_copy(
                        bank,
                        dict(graph),
                        muted,
                        Path(temporary_text) / bank.name,
                        disabled_parent_guids=disabled,
                        single_shot_parent_guids=single_shot_parent_guids,
                    )
                    rendered = renderer.render_event(
                        isolated.output_path,
                        str(event["eventPath"]),
                        output,
                        parameters=dict(vector["parameters"]),
                        start_parameters=(
                            dict(vector["startParameters"])
                            if isinstance(vector["startParameters"], Mapping)
                            else None
                        ),
                        duration_frames=duration_frames,
                        warmup_frames=0,
                        required_sound_name=diagnostic_name,
                        maximum_takes=maximum_takes,
                        event_id_lookup_bank_path=bank,
                        take_lifecycle=str(vector["takeLifecycle"]),
                        allow_missing_required_sound_name=not bool(
                            vector["expectTarget"]
                        ),
                    )
                pcm, _frames, _rate, _channels = _read_pcm(
                    output, require_nrt_format=True
                )
                scheduled_by_take = [
                    list(names) for names in rendered.scheduled_sound_names_by_take
                ]
                target_takes = [
                    index
                    for index, names in enumerate(scheduled_by_take)
                    if names and set(names) == {diagnostic_name}
                ]
                cache = {
                    "taskSpecSha256": task_sha,
                    "wavSha256": _sha256(output),
                    "scheduledSoundNames": list(rendered.scheduled_sound_names),
                    "scheduledSoundNamesByTake": scheduled_by_take,
                    "targetTakeIndexes": target_takes,
                    "callbackTraceSha256": hashlib.sha256(
                        canonical_json_bytes(scheduled_by_take)
                    ).hexdigest(),
                    "pcmRms": _rms(_pcm_frames(pcm)),
                    "sourceIsolationMethod": "sourceSoloBoundedAuthoredTakeSearchAndBusDsp-v2",
                    "selectionCapture": selection_capture,
                }
                state["placementLifecycleCaptures"][task_sha] = cache
                _write_json_atomic(state_path, state)
            names = cache.get("scheduledSoundNames")
            scheduled_by_take = cache.get("scheduledSoundNamesByTake")
            target_takes = cache.get("targetTakeIndexes")
            if not isinstance(scheduled_by_take, list) or not all(
                isinstance(names_for_take, list)
                and all(isinstance(name, str) for name in names_for_take)
                for names_for_take in scheduled_by_take
            ):
                raise NrtRecipeError("placement lifecycle take trace is malformed")
            observed_target = any(
                diagnostic_name in names_for_take
                for names_for_take in scheduled_by_take
            )
            callback_ok = observed_target is expect_target
            if expect_target:
                callback_ok = callback_ok and isinstance(names, list) and set(names) == {
                    diagnostic_name
                }
            initial_candidate_names = vector.get("initialActivationCandidateNames")
            initial_take_index = vector.get("initialActivationTakeIndex")
            initial_candidate_observed: list[str] | None = None
            initial_candidate_ok = True
            if initial_candidate_names is not None:
                if (
                    not isinstance(initial_candidate_names, list)
                    or not initial_candidate_names
                    or not all(isinstance(name, str) and name for name in initial_candidate_names)
                    or isinstance(initial_take_index, bool)
                    or not isinstance(initial_take_index, int)
                    or not 0 <= initial_take_index < len(scheduled_by_take)
                ):
                    raise NrtRecipeError(
                        "initial activation candidate-set vector is malformed"
                    )
                candidate_set = set(initial_candidate_names)
                initial_candidate_observed = sorted(
                    set(scheduled_by_take[initial_take_index]) & candidate_set
                )
                if vector["id"] == "initial-runtime-state":
                    initial_candidate_ok = (
                        bool(initial_candidate_observed)
                        if runtime_initial_membership
                        else not initial_candidate_observed
                    )
            pcm_rms = float(cache.get("pcmRms") or 0.0)
            # A source-solo callback must have an audible tail in its full
            # capture.  For a deliberate non-entry, callback absence is the
            # authoritative lifecycle proof: a parent bus may contain a tail
            # from a previous finite child, so requiring zero output would
            # assert a stronger, unrelated routing property.
            audible = (
                pcm_rms <= 1.0e-7
                if audibility_expectation == "authoredExactZeroGainSilent"
                else (pcm_rms > 1.0e-7 if expect_target else True)
            )
            vector_reports.append(
                {
                    "id": vector["id"],
                    "directions": vector["directions"],
                    "expectTarget": expect_target,
                    "scheduledSoundNames": names,
                    "scheduledSoundNamesByTake": scheduled_by_take,
                    "targetTakeIndexes": target_takes,
                    "callbackTraceSha256": cache.get("callbackTraceSha256"),
                    "oracleWavSha256": cache.get("wavSha256"),
                    "pcmRms": cache.get("pcmRms"),
                    "audibilityExpectation": audibility_expectation,
                    "zeroGainEvidence": zero_gain_evidence,
                    "sourceIsolationMethod": cache.get("sourceIsolationMethod"),
                    "selectionCapture": cache.get("selectionCapture"),
                    "initialActivationCandidateNames": initial_candidate_names,
                    "initialActivationCandidateObserved": initial_candidate_observed,
                    "initialActivationIdentityResolution": vector.get(
                        "initialActivationIdentityResolution"
                    ),
                    "initialActivationCandidatePass": initial_candidate_ok,
                    "runtimeApplicable": vector.get("runtimeApplicable", True),
                    "pass": callback_ok and initial_candidate_ok and audible,
                }
            )
        reports.append(
            {
                "eventPath": event["eventPath"],
                "perspectives": list(event.get("perspectives", [])),
                "sourceGuid": source_guid,
                "authoredBindingKey": variant.get("authoredBindingKey"),
                "diagnosticName": diagnostic_name,
                "finiteLifecycleTopology": runtime_mapping.get(
                    "finiteLifecycleTopology"
                ),
                "placementEntry": entry,
                "runtimeInitialMembership": runtime_initial_membership,
                "vectors": vector_reports,
                "pass": bool(vector_reports)
                and all(vector["pass"] for vector in vector_reports),
            }
        )
    return {
        "schema": "byd-full-event-parameter-placement-lifecycle-oracle-v1",
        "allPass": all(report["pass"] for report in reports),
        "sourceCount": len(reports),
        "sources": reports,
    }


def run_parameter_placement_lifecycle_preflight(
    plan: Mapping[str, Any],
    *,
    bank_path: Path,
    graph_path: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    output_directory: Path,
    state_path: Path,
) -> dict[str, Any]:
    """Run only source-solo finite placement lifecycle evidence.

    This deliberately does *not* render engine/effect atlas nodes, adaptive
    probes, or a pack.  It is the cheap fail-closed bridge from an authored
    finite placement topology to executable lifecycle evidence.  A resumable
    state is tied to the full plan, graph, and implementation hash so a code
    or contract change cannot promote stale callbacks.
    """

    if plan.get("schema") != ATLAS_PLAN_SCHEMA:
        raise NrtRecipeError("lifecycle preflight input is not a full-event atlas plan")
    bank = bank_path.resolve(strict=True)
    if _sha256(bank) != plan.get("bankSha256"):
        raise NrtRecipeError("lifecycle preflight bank SHA-256 differs from plan")
    graph = json.loads(graph_path.read_text(encoding="utf-8"))
    contract_sha = _state_contract_sha256(
        plan, _sha256(graph_path), audio_lab_root.resolve(strict=True)
    )
    expected = [
        target
        for target in _placement_entry_targets(plan)
        if target.get("blockedTopology") is True
    ]
    if not expected:
        raise NrtRecipeError(
            "lifecycle preflight found no blocked finite parameter-placement sources"
        )
    if state_path.is_file():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if (
            state.get("schema")
            != "byd-full-event-parameter-placement-lifecycle-preflight-state-v1"
            or state.get("planSha256") != plan.get("planSha256")
            or state.get("sourceBankSha256") != plan.get("bankSha256")
            or state.get("contractSha256") != contract_sha
        ):
            raise NrtRecipeError(
                "lifecycle preflight state belongs to a different plan/bank/implementation"
            )
    else:
        state = {
            "schema": "byd-full-event-parameter-placement-lifecycle-preflight-state-v1",
            "planSha256": plan["planSha256"],
            "sourceBankSha256": plan["bankSha256"],
            "contractSha256": contract_sha,
            "placementLifecycleCaptures": {},
        }
    (
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
        renderer_type,
        _loop_tools,
    ) = _load_audio_lab(audio_lab_root)
    renderer = renderer_type(assetto_root.resolve(strict=True))
    output_directory.mkdir(parents=True, exist_ok=True)
    report = _parameter_placement_lifecycle_oracle(
        plan,
        state,
        state_path,
        output_directory,
        renderer,
        bank,
        graph,
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
    )
    expected_keys = {
        (
            str(target["event"]["eventPath"]),
            _source_guid(target["variant"].get("sourceGuid")),
            str(target["variant"].get("authoredBindingKey") or ""),
        )
        for target in expected
    }
    observed_keys = {
        (
            str(source.get("eventPath") or ""),
            _source_guid(source.get("sourceGuid")),
            str(source.get("authoredBindingKey") or ""),
        )
        for source in report["sources"]
        if isinstance(source, Mapping)
    }
    if observed_keys != expected_keys or report["sourceCount"] != len(expected_keys):
        raise NrtRecipeError(
            "lifecycle preflight source coverage differs from blocked placement bindings"
        )
    report.update(
        {
            "schema": "byd-full-event-parameter-placement-lifecycle-preflight-v1",
            "status": "PASS" if report["allPass"] else "BLOCKED",
            "planSha256": plan["planSha256"],
            "sourceBankSha256": plan["bankSha256"],
            "contractSha256": contract_sha,
            "expectedBlockedBindingCount": len(expected_keys),
            "sourceCoverageExact": observed_keys == expected_keys,
        }
    )
    _write_json_atomic(state_path, state)
    return report


def _lifecycle_overlap_oracle(
    plan: Mapping[str, Any],
    state: dict[str, Any],
    state_path: Path,
    node_directory: Path,
    renderer: Any,
    bank: Path,
    graph: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
) -> dict[str, Any]:
    """Observe five real overlaps without inventing a per-group FMOD cap.

    Assetto Studio is initialized with 2,048 logical channels and a 256-real
    software budget.  A fifth source start therefore cannot be reported as an
    FMOD rejection merely because an old Android four-ring policy would reject
    it.  This oracle records FMOD's actual callbacks for five starts; global
    real-channel priority/steal/virtualization remains a separate required
    trajectory gate.
    """

    reports: list[dict[str, Any]] = []
    for target in _effect_group_targets(plan):
        node = target["node"]
        if str(node["lifetime"]) == "continuous":
            continue
        event = target["event"]
        variant = target["variant"]
        group_id = target["groupId"]
        start_frames = [index * 256 for index in range(5)]
        fifth_frame = start_frames[-1]
        duration = int(node["durationFrames"]) + fifth_frame
        duration = math.ceil(duration / 256) * 256
        task = {
            "kind": "oneShotOverlapLifecycle-v3-observeFiveFmodStarts",
            "bank": plan["bankSha256"],
            "event": event["eventPath"],
            "group": group_id,
            "source": node["requiredSourceGuid"],
            "parameters": node["parameters"],
            "startFrames": start_frames,
            "fifthFrame": fifth_frame,
            "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
            "durationFrames": duration,
        }
        task_sha = hashlib.sha256(canonical_json_bytes(task)).hexdigest()
        output = node_directory / "lifecycle" / f"{task_sha}.wav"
        cache = state.setdefault("lifecycleCaptures", {}).get(task_sha)
        if not (
            isinstance(cache, Mapping)
            and output.is_file()
            and cache.get("taskSpecSha256") == task_sha
            and cache.get("wavSha256") == _sha256(output)
        ):
            if output.exists():
                raise NrtRecipeError(f"lifecycle cache differs: {output}")
            output.parent.mkdir(parents=True, exist_ok=True)
            muted, _waveforms = _effect_isolation_muted_sources(
                graph, str(event["eventPath"]), str(node["requiredSourceGuid"])
            )
            disabled = fully_muted_multi_instrument_guids(dict(graph), muted)
            with tempfile.TemporaryDirectory(prefix="atlas-lifecycle-isolation-", dir=output.parent) as temporary_text:
                isolated = create_isolated_bank_copy(
                    bank,
                    dict(graph),
                    muted,
                    Path(temporary_text) / bank.name,
                    disabled_parent_guids=disabled,
                )
                rendered = renderer.render_event_mix(
                    bank,
                    output,
                    duration_frames=duration,
                    events=[
                        {
                            "eventName": event["eventPath"],
                            "bankPath": isolated.output_path,
                            "eventIdLookupBankPath": bank,
                            "startFrame": frame,
                            "hostGainLinear": 1.0,
                            "parameters": node["parameters"],
                        }
                        for frame in start_frames
                    ],
                )
            scheduled_by_instance = [
                list(names) for names in rendered.scheduled_sound_names_by_instance
            ]
            frame_count = rendered.frame_count
            cache = {
                "taskSpecSha256": task_sha,
                "wavSha256": _sha256(output),
                "scheduledSoundNamesByInstance": scheduled_by_instance,
                "channelSnapshots": list(rendered.channel_snapshots),
                "frameCount": frame_count,
            }
            state["lifecycleCaptures"][task_sha] = cache
            _write_json_atomic(state_path, state)
        expected_name = str(node["requiredDiagnosticName"])
        scheduled = cache.get("scheduledSoundNamesByInstance", [])
        accepted = len(scheduled) == len(start_frames) and all(
            set(names) == {expected_name} for names in scheduled
        )
        full_tails = int(cache.get("frameCount", 0)) >= int(node["durationFrames"]) + start_frames[-1]
        reports.append(
            {
                "eventPath": event["eventPath"],
                "perspectives": list(event.get("perspectives", [])),
                "groupId": group_id,
                "sourceGuid": node["requiredSourceGuid"],
                "triggerAttempts": [
                    {
                        "frame": frame,
                        "result": "fmodStartRequested",
                    }
                    for frame in start_frames
                ],
                "acceptedStarts": start_frames,
                "fmodFifthStartFrame": fifth_frame,
                "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                "observedScheduledNamesByAcceptedVoice": scheduled,
                "channelSnapshots": cache.get("channelSnapshots", []),
                "fmodFifthVoiceScheduled": (
                    len(scheduled) == len(start_frames)
                    and set(scheduled[-1]) == {expected_name}
                ),
                "runtimeArbitration": "notClaimedHere; globalAssetto2048Logical256RealPriorityStealVirtualizationOracleRequired",
                "fullTailsRendered": full_tails,
                "pass": accepted and full_tails,
            }
        )
    return {"schema": "byd-full-event-lifecycle-oracle-v2", "allPass": bool(reports) and all(item["pass"] for item in reports), "oneShotGroupCount": len(reports), "groups": reports}


def _callback_source_guid_map(
    plan: Mapping[str, Any], perspective_name: str
) -> dict[str, set[str]]:
    """Return every source GUID that can honestly explain a callback name.

    FMOD's sound-played callback does not expose the instrument GUID.  A
    channel trace is useful only when every callback sound name resolves to
    exactly one retained source identity for the selected perspective.  Do
    not use PCM proximity or a chosen reconstruction to break a tie.
    """

    perspective = plan["perspectives"].get(perspective_name)
    if not isinstance(perspective, Mapping):
        raise NrtRecipeError(f"channel snapshot has unknown perspective {perspective_name}")
    result: dict[str, set[str]] = {}
    for item in perspective.get("requiredSourceCoverage", []):
        if not isinstance(item, Mapping):
            raise NrtRecipeError("engine source coverage entry is invalid")
        name = str(item.get("diagnosticName") or "")
        source_guid = _source_guid(item.get("sourceGuid"))
        if not name or not source_guid:
            raise NrtRecipeError("engine source coverage has no diagnostic/GUID identity")
        result.setdefault(name, set()).add(source_guid)
    for event in plan.get("effects", []):
        if not isinstance(event, Mapping) or perspective_name not in event.get("perspectives", []):
            continue
        contract = event.get("runtimeLifecycleParameterVariantContract")
        variants = contract.get("variants") if isinstance(contract, Mapping) else None
        if not isinstance(variants, Sequence) or isinstance(variants, (str, bytes)):
            raise NrtRecipeError("effect has no source-identity runtime variants")
        for variant in variants:
            if not isinstance(variant, Mapping):
                raise NrtRecipeError("effect runtime variant is invalid")
            mapping = variant.get("runtimeMapping")
            scopes = mapping.get("perspectives") if isinstance(mapping, Mapping) else None
            if isinstance(scopes, Sequence) and not isinstance(scopes, (str, bytes)) and perspective_name not in scopes:
                continue
            name = str(variant.get("diagnosticName") or "")
            source_guid = _source_guid(variant.get("sourceGuid"))
            if not name or not source_guid:
                raise NrtRecipeError("effect runtime variant has no diagnostic/GUID identity")
            result.setdefault(name, set()).add(source_guid)
    return result


def _channel_snapshot_scenario(
    plan: Mapping[str, Any],
    *,
    perspective: str,
    identifier: str,
    kind: str,
    snapshots: object,
    scheduled_by_instance: object,
) -> dict[str, Any]:
    """Validate a raw FMOD trajectory rather than declare a scalar bound.

    The renderer samples FMOD immediately after every DSP block.  The output
    is intentionally a source-identity *reconciliation*, not a claim that a
    single Android premix stream equals one FMOD channel: every observed
    callback has to map uniquely to an emitted raw source GUID and no sampled
    real demand may exceed Assetto's 256-channel budget.
    """

    if not isinstance(snapshots, Sequence) or isinstance(snapshots, (str, bytes)):
        return {
            "id": identifier,
            "kind": kind,
            "perspective": perspective,
            "pass": False,
            "reason": "missingPerDspBlockChannelSnapshots",
        }
    normalized: list[dict[str, Any]] = []
    for raw in snapshots:
        if not isinstance(raw, Mapping):
            return {"id": identifier, "kind": kind, "perspective": perspective, "pass": False, "reason": "malformedChannelSnapshot"}
        logical = raw.get("logicalChannels")
        real = raw.get("realChannels")
        frame = raw.get("afterDspBlockStartFrame")
        if (
            isinstance(logical, bool) or not isinstance(logical, int) or logical < 0
            or isinstance(real, bool) or not isinstance(real, int) or real < 0
            or isinstance(frame, bool) or not isinstance(frame, int) or frame < 0
        ):
            return {"id": identifier, "kind": kind, "perspective": perspective, "pass": False, "reason": "invalidChannelSnapshotCounter"}
        normalized.append({"afterDspBlockStartFrame": frame, "logicalChannels": logical, "realChannels": real})
    if not normalized:
        return {"id": identifier, "kind": kind, "perspective": perspective, "pass": False, "reason": "emptyChannelSnapshots"}
    callback_names: list[str] = []
    if not isinstance(scheduled_by_instance, Sequence) or isinstance(scheduled_by_instance, (str, bytes)):
        return {"id": identifier, "kind": kind, "perspective": perspective, "pass": False, "reason": "missingCallbackTracePerScheduledEventInstance"}
    for names in scheduled_by_instance:
        if not isinstance(names, Sequence) or isinstance(names, (str, bytes)):
            return {"id": identifier, "kind": kind, "perspective": perspective, "pass": False, "reason": "malformedCallbackTrace"}
        callback_names.extend(str(name) for name in names)
    names_to_guids = _callback_source_guid_map(plan, perspective)
    bindings: list[dict[str, str]] = []
    unresolved: list[str] = []
    ambiguous: list[dict[str, Any]] = []
    for name in callback_names:
        candidates = sorted(names_to_guids.get(name, set()))
        if len(candidates) == 1:
            bindings.append({"diagnosticName": name, "sourceGuid": candidates[0]})
        elif not candidates:
            unresolved.append(name)
        else:
            ambiguous.append({"diagnosticName": name, "sourceGuids": candidates})
    max_logical = max(item["logicalChannels"] for item in normalized)
    max_real = max(item["realChannels"] for item in normalized)
    passed = (
        max_logical <= ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP
        and max_real <= ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
        and not unresolved
        and not ambiguous
    )
    return {
        "id": identifier,
        "kind": kind,
        "perspective": perspective,
        "channelSnapshotCount": len(normalized),
        "maximumLogicalChannels": max_logical,
        "maximumRealChannels": max_real,
        "rawSourceGuidCallbackBindings": bindings,
        "unresolvedCallbackNames": sorted(set(unresolved)),
        "ambiguousCallbackNames": ambiguous,
        "pass": passed,
        "reason": None if passed else "rawCallbackGuidReconciliationOrAssettoChannelBudgetFailed",
    }


def _global_fmod_channel_arbitration_oracle(
    plan: Mapping[str, Any],
    scenario_inputs: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Prove this family's raw FMOD demand stayed within the real budget.

    The standalone 257th-voice experiment establishes the behavior *outside*
    the software budget.  This family gate deliberately accepts a premix only
    when every supported, callback-reconciled scenario stays at or below 256;
    it otherwise fail-closes and asks for source stems.
    """

    gate = plan.get("refinementGate", {}).get(
        "globalFmodChannelArbitrationOracle"
    )
    if not isinstance(gate, Mapping):
        raise NrtRecipeError("plan has no global FMOD channel arbitration gate")
    required_scenarios = gate.get("requiredScenarios")
    required_evidence = gate.get("requiredEvidence")
    if (
        gate.get("schema") != FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA
        or gate.get("required") is not True
        or gate.get("assettoStudioLogicalChannelCap")
        != ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP
        or gate.get("assettoSoftwareRealChannelBudget")
        != ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
        or not isinstance(required_scenarios, list)
        or not isinstance(required_evidence, list)
    ):
        raise NrtRecipeError("global FMOD channel arbitration gate is malformed")
    observed_budget = gate.get("observedVoiceBudgetOracle")
    observed_budget_passes = (
        isinstance(observed_budget, Mapping)
        and observed_budget.get("schema") == FMOD_VOICE_BUDGET_INPUT_SCHEMA
        and observed_budget.get("status") == "PASS_WITH_BOUNDED_CLAIMS"
        and isinstance(observed_budget.get("reportSha256"), str)
        and len(observed_budget["reportSha256"]) == 64
    )
    scenarios = [
        _channel_snapshot_scenario(
            plan,
            perspective=str(item["perspective"]),
            identifier=str(item["id"]),
            kind=str(item["kind"]),
            snapshots=item.get("channelSnapshots"),
            scheduled_by_instance=item.get("scheduledSoundNamesByInstance"),
        )
        for item in scenario_inputs
    ]
    dynamic_by_perspective = {
        str(item["perspective"])
        for item in scenario_inputs
        if str(item.get("kind")) == "dynamicEngineTrajectory"
    }
    mandatory_perspectives = set(plan.get("perspectives", {}).keys())
    all_scenarios_pass = bool(scenarios) and all(item["pass"] for item in scenarios)
    dynamic_coverage_pass = mandatory_perspectives <= dynamic_by_perspective
    def aggregate(identifier: str, members: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
        by_perspective: dict[str, dict[str, int]] = {}
        for member in members:
            perspective_name = str(member.get("perspective") or "")
            if not perspective_name:
                continue
            summary = by_perspective.setdefault(
                perspective_name,
                {
                    "scenarioCount": 0,
                    "maximumLogicalChannels": 0,
                    "maximumRealChannels": 0,
                    "rawSourceGuidCallbackBindingCount": 0,
                },
            )
            summary["scenarioCount"] += 1
            summary["maximumLogicalChannels"] = max(
                summary["maximumLogicalChannels"], int(member.get("maximumLogicalChannels", 0))
            )
            summary["maximumRealChannels"] = max(
                summary["maximumRealChannels"], int(member.get("maximumRealChannels", 0))
            )
            summary["rawSourceGuidCallbackBindingCount"] += len(
                member.get("rawSourceGuidCallbackBindings", [])
            )
        return {
            "id": identifier,
            "scope": "aggregatedCallbackReconciledPerDspBlockSnapshots",
            "sourceScenarioIds": [str(member["id"]) for member in members],
            "perPerspective": by_perspective,
            "pass": bool(members) and all(member.get("pass") is True for member in members),
        }

    default_members = [
        item
        for item in scenarios
        if item["kind"] == "combinedEngineEffectActivation"
        or (item["kind"] == "dynamicEngineTrajectory" and "steady_hold" in item["id"])
    ]
    aggressive_members = [
        item for item in scenarios if item not in default_members
    ]
    report_scenarios = [
        aggregate("assettoDefaultCombinedEngineAndEffects", default_members),
        aggregate("assettoAggressiveCombinedRpmThrottleTurboShift", aggressive_members),
        {
            "id": "assettoTwoHundredFiftySeventhRealVoiceContention",
            "scope": "independentFmod11011CoreFixture; notPremixAdmissionSubstitute",
            "observedVoiceBudgetOracleSha256": (
                observed_budget.get("reportSha256") if isinstance(observed_budget, Mapping) else None
            ),
            "pass": observed_budget_passes,
        },
    ]
    report_scenarios_pass = all(item["pass"] for item in report_scenarios)
    status = (
        "PASS"
        if observed_budget_passes and all_scenarios_pass and dynamic_coverage_pass and report_scenarios_pass
        else (
            "BLOCKED_PENDING_PER_FAMILY_RAW_SUPPORTED_SCENARIO_RECONCILIATION"
            if observed_budget_passes
            else "BLOCKED_PENDING_OBSERVED_257TH_REAL_CHANNEL_ARBITRATION_POLICY"
        )
    )
    return {
        "schema": FMOD_CHANNEL_ARBITRATION_ORACLE_SCHEMA,
        "required": True,
        "status": status,
        "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
        "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        "rendererInitializationOrder": list(
            gate.get("rendererInitializationOrder", [])
        ),
        "premixAdmissionParity": copy.deepcopy(
            gate.get("premixAdmissionParity")
        ),
        "observedVoiceBudgetOracle": copy.deepcopy(observed_budget),
        "familyPerspectiveScenarios": scenarios,
        "requiredScenarios": list(required_scenarios),
        "requiredEvidence": list(required_evidence),
        "scenarios": report_scenarios,
        "policy": (
            {
                "scope": "directCoreDecodedPcmFixtureOnly",
                "releaseAction": "failClosedUnlessEverySupportedFamilyPerspectiveScenarioRawDemandIsAtOrBelow256OrSourceStemsModelPerSourceArbitration",
            }
            if observed_budget_passes
            else None
        ),
        "reason": (
            None
            if status == "PASS"
            else (
                "the observed FMOD voice-budget policy is present but a required "
                "per-family raw source/callback trajectory is absent, unresolved, "
                "ambiguous, or exceeds the 256-real-channel budget"
                if observed_budget_passes
                else "the 257th-real-channel FMOD policy report is absent or invalid"
            )
        ),
    }


def _source_guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _observed_effect_contributions(
    event: Mapping[str, Any],
    parameters: Mapping[str, Any],
    scheduled_names: Sequence[str],
) -> tuple[list[Mapping[str, Any]], list[dict[str, str]]]:
    """Map callback-observed sounds to one fixed emitted source node each.

    The callback exposes FMOD sound names, not instrument GUIDs.  The oracle
    therefore refuses an ambiguous diagnostic name instead of searching nodes
    for whichever reconstruction has the lowest error.  The returned sequence
    follows callback order and is selected before any PCM metric is computed.
    """

    expected_parameters = canonical_json_bytes(dict(parameters))
    nodes = list(event.get("nodes", []))
    if not scheduled_names:
        raise NrtRecipeError("combined event activation scheduled no observable sounds")
    result: list[Mapping[str, Any]] = []
    evidence: list[dict[str, str]] = []
    for name in scheduled_names:
        matches = [
            node
            for node in nodes
            if str(node.get("requiredDiagnosticName") or "") == name
            and canonical_json_bytes(dict(node.get("parameters") or {}))
            == expected_parameters
        ]
        identities = {_source_guid(node.get("requiredSourceGuid")) for node in matches}
        if not matches:
            raise NrtRecipeError(
                f"combined callback sound {name!r} has no emitted node at exact parameters"
            )
        if len(identities) != 1 or "" in identities:
            raise NrtRecipeError(
                f"combined callback sound {name!r} does not identify one source GUID"
            )
        if len(matches) != 1:
            raise NrtRecipeError(
                f"combined callback sound {name!r} has duplicate emitted nodes for one source"
            )
        node = matches[0]
        result.append(node)
        evidence.append(
            {
                "diagnosticName": name,
                "sourceGuid": _source_guid(node.get("requiredSourceGuid")),
                "temporaryAssetName": str(node["temporaryAssetName"]),
            }
        )
    return result, evidence


def _combined_engine_effect_mix_oracle(
    plan: Mapping[str, Any],
    state: dict[str, Any],
    state_path: Path,
    node_directory: Path,
    renderer: Any,
    bank: Path,
    graph: Mapping[str, Any],
    create_isolated_bank_copy: Any,
    fully_muted_multi_instrument_guids: Any,
    dynamic_trajectories: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Compare external effect activations in one original-bank Studio System.

    The normal-bank capture and the callback choose the exact scheduled branch
    before reconstruction.  Isolated source captures remain separate evidence
    for individual variants.  A finite source embedded in an engine event is
    an ``engineEvent`` contribution, so it shares that instance's 0.5 host
    gain with the loop bed; it never needs a derivative bank in the shared
    system.  Only separately started effect events use unity gain.
    """

    required_groups = [target["groupId"] for target in _effect_group_targets(plan)]
    scenarios: list[dict[str, Any]] = []
    minimum_tail = int(plan["refinementGate"]["combinedEngineEffectMixOracle"]["minimumReleaseTailFrames"])
    for target in _effect_group_targets(plan):
        perspective = plan["perspectives"][target["perspective"]]
        rpm_axis = [float(value) for value in current_axes(perspective)["rpm"]]
        rpm = rpm_axis[len(rpm_axis) // 2]
        throttle = 0.5
        node = target["node"]
        event = target["event"]
        same_engine_event = str(event["eventPath"]).casefold() == str(
            perspective["eventPath"]
        ).casefold()
        if same_engine_event:
            rpm = float(node["parameters"].get("rpms", rpm))
            throttle = float(node["parameters"].get("throttle", throttle))
        duration = max(minimum_tail, int(node["durationFrames"]))
        duration = math.ceil(duration / 256) * 256
        # A creation transient inside engine_int/ext is audible at the start
        # of that same event instance.  Cropping after an engine warmup would
        # silently remove the very source the runtime will play at frame zero.
        # External events instead start after the bed warmup so their reference
        # begins with the effect activation and a settled engine loop.
        warmup = 0 if same_engine_event else 36_096
        task = {
            "kind": "sharedSystemOriginalBankWholeEventActivation-v2",
            "bank": plan["bankSha256"],
            "engineEvent": perspective["eventPath"],
            "effectEvent": event["eventPath"],
            "effectGroup": target["groupId"],
            "source": node["requiredSourceGuid"],
            "engineParameters": {"rpms": rpm, "throttle": throttle},
            "effectParameters": node["parameters"],
            "duration": duration,
            "warmup": warmup,
            "hostMix": plan["hostMixContract"],
        }
        task_sha = hashlib.sha256(canonical_json_bytes(task)).hexdigest()
        output = node_directory / "combined" / f"{task_sha}.wav"
        cache = state.setdefault("combinedMixCaptures", {}).get(task_sha)
        if not (
            isinstance(cache, Mapping)
            and output.is_file()
            and cache.get("taskSpecSha256") == task_sha
            and cache.get("wavSha256") == _sha256(output)
        ):
            if output.exists():
                raise NrtRecipeError(f"combined mix cache differs: {output}")
            output.parent.mkdir(parents=True, exist_ok=True)
            events = [
                {
                    "eventName": perspective["eventPath"],
                    "startFrame": 0,
                    "hostGainLinear": 0.5,
                    "parameters": {"rpms": rpm, "throttle": throttle},
                }
            ]
            if not same_engine_event:
                events.append(
                    {
                        "eventName": event["eventPath"],
                        "startFrame": warmup,
                        "hostGainLinear": 1.0,
                        "parameters": node["parameters"],
                    }
                )
            rendered = renderer.render_event_mix(
                bank,
                output,
                duration_frames=duration,
                warmup_frames=warmup,
                events=events,
            )
            event_paths = list(rendered.event_paths)
            scheduled_by_instance = [
                list(names) for names in rendered.scheduled_sound_names_by_instance
            ]
            cache = {
                "taskSpecSha256": task_sha,
                "wavSha256": _sha256(output),
                "eventPaths": event_paths,
                "scheduledSoundNamesByInstance": scheduled_by_instance,
                "channelSnapshots": list(rendered.channel_snapshots),
                "sourceIsolationMethod": "separateSourceSoloCapture-v1",
            }
            state["combinedMixCaptures"][task_sha] = cache
            _write_json_atomic(state_path, state)
        pcm, _frames, _rate, _channels = _read_pcm(output, require_nrt_format=True)
        scheduled = cache.get("scheduledSoundNamesByInstance", [])
        try:
            expected_instances = 1 if same_engine_event else 2
            if len(scheduled) != expected_instances:
                raise NrtRecipeError(
                    "shared original-bank render did not retain its expected instances"
                )
            effect_scheduled_names = (
                [
                    name
                    for name in scheduled[0]
                    if name
                    in {
                        str(candidate.get("requiredDiagnosticName") or "")
                        for candidate in event["nodes"]
                    }
                ]
                if same_engine_event
                else list(scheduled[1])
            )
            observed_nodes, observed_bindings = _observed_effect_contributions(
                event, node["parameters"], effect_scheduled_names
            )
            expected_source = _source_guid(node["requiredSourceGuid"])
            if expected_source not in {
                binding["sourceGuid"] for binding in observed_bindings
            }:
                raise NrtRecipeError(
                    "target scheduling group did not activate its expected source"
                )
            engine, alignments = reconstruct_probe(
                perspective, node_directory, rpm=rpm, throttle=throttle, frames=duration
            )
            effects = [
                _effect_node_signal(
                    observed,
                    node_directory / str(observed["temporaryAssetName"]),
                    duration,
                )
                for observed in observed_nodes
            ]
            effect_gain_classes = [
                str(observed.get("hostGainClass") or "")
                for observed in observed_nodes
            ]
            reference = _apply_post_sum_master(_pcm_frames(pcm))
            reconstruction = _apply_host_mix_contract(
                engine, effects, effect_gain_classes
            )
            metrics, windows = _dynamic_window_metrics(reference, reconstruction)
            passed = _metrics_pass(metrics, plan["refinementGate"])
            reason = None
        except NrtRecipeError as exc:
            observed_bindings = []
            alignments = []
            metrics = None
            windows = []
            passed = False
            reason = str(exc)
        scenarios.append(
            {
                "id": (
                    f"{event['eventPath']}|{target['groupId']}|"
                    f"{node['requiredSourceGuid']}"
                ),
                "eventPath": event["eventPath"],
                "groupId": target["groupId"],
                "schedulingGroupIds": [target["groupId"]],
                "sourceGuid": node["requiredSourceGuid"],
                "perspective": target["perspective"],
                "durationFrames": duration,
                "releaseTailFrames": duration,
                "sharedStudioSystem": True,
                "sourceIsolationMethod": cache.get("sourceIsolationMethod"),
                "oracleWavSha256": cache["wavSha256"],
                "scheduledSoundNamesByInstance": scheduled,
                "channelSnapshots": cache.get("channelSnapshots", []),
                "observedSourceBindings": observed_bindings,
                "observedEffectHostGainClasses": (
                    effect_gain_classes if metrics is not None else []
                ),
                "candidateSelection": "callbackObservedSoundNamePlusExactParametersNoMetricSearch-v1",
                "enginePhaseAlignments": alignments,
                "metrics": metrics,
                "windows": windows,
                "pass": passed,
                "reason": reason,
            }
        )
    lifecycle = _lifecycle_overlap_oracle(
        plan,
        state,
        state_path,
        node_directory,
        renderer,
        bank,
        graph,
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
    )
    placement_lifecycle = _parameter_placement_lifecycle_oracle(
        plan,
        state,
        state_path,
        node_directory,
        renderer,
        bank,
        graph,
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
    )
    playlist = _playlist_selection_oracle(plan)
    channel_inputs: list[dict[str, Any]] = [
        {
            "id": f"dynamic:{item['perspective']}:{item['id']}",
            "kind": "dynamicEngineTrajectory",
            "perspective": item["perspective"],
            "channelSnapshots": item.get("channelSnapshots"),
            "scheduledSoundNamesByInstance": item.get("scheduledSoundNamesByInstance"),
        }
        for item in dynamic_trajectories
    ]
    channel_inputs.extend(
        {
            "id": f"combined:{item['id']}",
            "kind": "combinedEngineEffectActivation",
            "perspective": item["perspective"],
            "channelSnapshots": item.get("channelSnapshots"),
            "scheduledSoundNamesByInstance": item.get("scheduledSoundNamesByInstance"),
        }
        for item in scenarios
    )
    for group in lifecycle["groups"]:
        for perspective_name in group.get("perspectives", []):
            channel_inputs.append(
                {
                    "id": f"fiveOverlap:{group['eventPath']}:{group['groupId']}:{perspective_name}",
                    "kind": "finiteFiveOverlapTail",
                    "perspective": perspective_name,
                    "channelSnapshots": group.get("channelSnapshots"),
                    "scheduledSoundNamesByInstance": group.get("observedScheduledNamesByAcceptedVoice"),
                }
            )
    for source in placement_lifecycle["sources"]:
        for vector in source["vectors"]:
            for perspective_name in source.get("perspectives", []):
                channel_inputs.append(
                    {
                        "id": f"placement:{source['sourceGuid']}:{vector['id']}:{perspective_name}",
                        "kind": "parameterPlacementEntryTail",
                        "perspective": perspective_name,
                        "channelSnapshots": vector.get("channelSnapshots"),
                        "scheduledSoundNamesByInstance": vector.get("scheduledSoundNamesByInstance"),
                    }
                )
    channel_arbitration = _global_fmod_channel_arbitration_oracle(
        plan, channel_inputs
    )
    all_scenarios = bool(scenarios) and all(item["pass"] for item in scenarios)
    fully_proven = (
        all_scenarios
        and lifecycle["allPass"]
        and placement_lifecycle["allPass"]
        and playlist["allPass"]
        and channel_arbitration["status"] == "PASS"
    )
    return {
        "schema": "byd-combined-engine-effect-mix-oracle-v1",
        "required": bool(plan["refinementGate"]["combinedEngineEffectMixOracle"]["required"]),
        "status": "PASS" if fully_proven else "BLOCKED",
        "allScenariosPass": all_scenarios,
        "scenarioCount": len(scenarios),
        "requiredSchedulingGroupIds": sorted(required_groups),
        "scenarios": scenarios,
        "lifecycleOracle": lifecycle,
        "parameterPlacementLifecycleOracle": placement_lifecycle,
        "playlistSelectionOracle": playlist,
        "globalFmodChannelArbitrationOracle": channel_arbitration,
        "reason": (
            None
            if fully_proven
            else "oneOrMoreSharedMixPlacementLifecyclePlaylistOrGlobalChannelArbitrationGatesFailed"
        ),
    }


def refine_atlas(
    initial_plan: Mapping[str, Any], *, bank_path: Path, graph_path: Path, audio_lab_root: Path, assetto_root: Path, node_directory: Path, state_path: Path, maximum_iterations: int
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    if initial_plan.get("schema") != ATLAS_PLAN_SCHEMA:
        raise NrtRecipeError("oracle input is not a full-event atlas plan")
    storage_policy = initial_plan.get("refinementGate", {}).get(
        "adaptiveStoragePolicy"
    )
    if (
        not isinstance(storage_policy, Mapping)
        or storage_policy.get("schema") != "byd-full-event-adaptive-storage-policy-v1"
        or not isinstance(storage_policy.get("maximumIterations"), int)
        or isinstance(storage_policy.get("maximumIterations"), bool)
    ):
        raise NrtRecipeError("oracle plan has no executable adaptive storage policy")
    if maximum_iterations > int(storage_policy["maximumIterations"]):
        raise NrtRecipeError(
            "requested refinement iterations exceed the plan's adaptive storage policy"
        )
    bank = bank_path.resolve(strict=True)
    if _sha256(bank) != initial_plan.get("bankSha256"):
        raise NrtRecipeError("oracle bank SHA-256 differs from plan")
    graph = json.loads(graph_path.read_text(encoding="utf-8"))
    state_contract = _state_contract_sha256(
        initial_plan, _sha256(graph_path), audio_lab_root.resolve(strict=True)
    )
    if state_path.is_file():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if state.get("schema") != STATE_SCHEMA or state.get("atlasFamilyId") != initial_plan.get("id") or state.get("sourceBankSha256") != initial_plan.get("bankSha256"):
            raise NrtRecipeError("oracle state belongs to another atlas/bank")
        if state.get("contractSha256") != state_contract:
            raise NrtRecipeError("oracle implementation/interpolation/gate changed; existing state is not reusable")
        plan = state["plan"]
        if plan.get("planSha256") != initial_plan.get("planSha256"):
            raise NrtRecipeError("oracle state and supplied resumable plan differ")
    else:
        state = {"schema": STATE_SCHEMA, "oracleImplementation": ORACLE_IMPLEMENTATION, "atlasFamilyId": initial_plan["id"], "sourceBankSha256": initial_plan["bankSha256"], "initialPlanSha256": initial_plan["planSha256"], "contractSha256": state_contract, "plan": copy.deepcopy(dict(initial_plan)), "captures": {}, "iterations": [], "probeCache": {}}
        plan = state["plan"]
    create_isolated_bank_copy, fully_muted_multi_instrument_guids, renderer_type, loop_tools = _load_audio_lab(audio_lab_root)
    renderer = renderer_type(assetto_root.resolve(strict=True))
    node_directory.mkdir(parents=True, exist_ok=True)
    for iteration in range(maximum_iterations):
        _render_missing(plan, state, state_path, node_directory, renderer, bank, loop_tools, graph, create_isolated_bank_copy, fully_muted_multi_instrument_guids)
        results: list[dict[str, Any]] = []
        failures: list[tuple[str, float, float]] = []
        for perspective_name in ("cabin", "exterior"):
            perspective = plan["perspectives"][perspective_name]
            for rpm, throttle in _probes(perspective):
                key = _probe_key(perspective_name, rpm, throttle)
                cache = state["probeCache"].get(key)
                path = node_directory / "probes" / f"{key}.wav"
                raw = {"rpm": rpm, "throttle": throttle, "parameters": {"rpms": rpm, "throttle": throttle}, "lifetime": "continuous", "durationFrames": perspective["capture"]["durationFrames"], "warmupFrames": perspective["capture"]["warmupFrames"], "temporaryAssetName": path.name}
                task_sha = _task_spec_sha256(str(plan["bankSha256"]), str(perspective["eventPath"]), raw)
                if isinstance(cache, Mapping) and path.is_file() and cache.get("wavSha256") == _sha256(path):
                    if cache.get("taskSpecSha256") != task_sha:
                        raise NrtRecipeError(f"cached oracle probe task differs: {path}")
                else:
                    if path.exists():
                        raise NrtRecipeError(f"untracked oracle probe exists: {path}")
                    path.parent.mkdir(parents=True, exist_ok=True)
                    cache = {**_render_node(renderer, bank, str(perspective["eventPath"]), raw, path, *loop_tools), "taskSpecSha256": task_sha}
                    state["probeCache"][key] = cache
                    _write_json_atomic(state_path, state)
                oracle_pcm, _, _, _ = _read_pcm(path, require_nrt_format=True)
                reference = _apply_host_mix_contract(
                    _pcm_frames(oracle_pcm)[: ANALYSIS_FRAMES * 2]
                )
                reconstruction, alignments = reconstruct_probe(
                    perspective, node_directory, rpm=rpm, throttle=throttle
                )
                reconstruction = _apply_host_mix_contract(reconstruction)
                metrics = phase_invariant_metrics(reference, reconstruction)
                passed = _metrics_pass(metrics, plan["refinementGate"])
                results.append({"perspective": perspective_name, "rpm": rpm, "throttle": throttle, "oracleWavSha256": cache["wavSha256"], "reconstructionSha256": hashlib.sha256(canonical_json_bytes({"metrics": metrics, "alignments": alignments})).hexdigest(), "metrics": metrics, "phaseAlignments": alignments, "pass": passed})
                if not passed:
                    failures.append((perspective_name, rpm, throttle))
        state["iterations"].append({"iteration": iteration + 1, "probeCount": len(results), "failureCount": len(failures), "probes": results})
        if not failures:
            break
        _assert_adaptive_anchor_storage_limit(plan, failures)
        for perspective_name, rpm, throttle in failures:
            minimum = plan["refinementGate"]["minimumCellWidth"]
            axes = current_axes(plan["perspectives"][perspective_name])
            if any(abs(rpm - value) < float(minimum["rpm"]) for value in axes["rpm"]) and any(abs(throttle - value) < float(minimum["throttle"]) for value in axes["throttle"]):
                continue
            _add_anchor(plan["perspectives"][perspective_name], rpm, throttle)
        _write_json_atomic(state_path, state)
    final_plan = refresh_plan_sha256(plan)
    state["plan"] = final_plan
    final_probes = state["iterations"][-1]["probes"] if state["iterations"] else []
    effects_complete = all(
        not event["runtimeMappingBlocked"]
        and event["runtimeLifecycleParameterVariantContract"]["complete"]
        for event in final_plan["effects"]
    )
    source_coverage = _source_coverage(final_plan, state["captures"])
    all_pass = bool(final_probes) and all(item["pass"] for item in final_probes)
    dynamic = _run_dynamic_oracle(final_plan, state, state_path, node_directory, renderer, bank) if all_pass else []
    dynamic_required = final_plan["refinementGate"]["dynamicTrajectoryOracle"]["required"]
    dynamic_pass = bool(dynamic) and all(item["pass"] for item in dynamic)
    finite_interpolation = (
        _finite_effect_interpolation_oracle(
            final_plan,
            state,
            state_path,
            node_directory,
            renderer,
            bank,
            graph,
            create_isolated_bank_copy,
            fully_muted_multi_instrument_guids,
            loop_tools,
        )
        if all_pass
        else {
            "schema": "byd-full-event-finite-interpolation-oracle-v1",
            "runtimeAlgorithm": "perSourceAxisAlignedMultilinearFiniteRing-v2",
            "allPass": False,
            "probeCount": 0,
            "probes": [],
            "sourceResiduals": [],
            "reason": "engine atlas probes did not pass",
        }
    )
    combined_mix = _combined_engine_effect_mix_oracle(
        final_plan,
        state,
        state_path,
        node_directory,
        renderer,
        bank,
        graph,
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
        dynamic,
    )
    status = "PASS" if all_pass and dynamic_pass and finite_interpolation["allPass"] and effects_complete and source_coverage["allRetainedSourceIdentitiesCovered"] and combined_mix["status"] == "PASS" and combined_mix["allScenariosPass"] else "BLOCKED"
    oracle = {"schema": ORACLE_SCHEMA, "atlasFamilyId": final_plan["id"], "sourceBankSha256": final_plan["bankSha256"], "finalPlanSha256": final_plan["planSha256"], "status": status, "allProbesPass": all_pass, "probeCount": len(final_probes), "probes": final_probes, "dynamicTrajectoryOracleRequired": dynamic_required, "dynamicTrajectoryCount": len(dynamic), "dynamicTrajectories": dynamic, "allDynamicTrajectoriesPass": dynamic_pass, "finiteEffectInterpolationOracle": finite_interpolation, "combinedEngineEffectMixOracle": combined_mix, "convergedIterations": len(state["iterations"]), "effectRuntimeMappingComplete": effects_complete, "sourceCoverage": source_coverage, "resumeStateSha256": hashlib.sha256(canonical_json_bytes(state)).hexdigest()}
    realization = {"schema": ATLAS_REALIZATION_SCHEMA, "planSha256": final_plan["planSha256"], "atlasFamilyId": final_plan["id"], "sourceBankSha256Before": final_plan["bankSha256"], "sourceBankSha256After": _sha256(bank), "sourceBankUnchanged": _sha256(bank) == final_plan["bankSha256"], "fullRun": True, "captureCount": len(state["captures"]), "captures": [state["captures"][key] for key in sorted(state["captures"])]}
    _write_json_atomic(state_path, state)
    return final_plan, realization, oracle


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--node-directory", type=Path, required=True)
    parser.add_argument("--state-output", type=Path, required=True)
    parser.add_argument("--plan-output", type=Path)
    parser.add_argument("--realization-output", type=Path)
    parser.add_argument("--oracle-output", type=Path)
    parser.add_argument(
        "--parameter-placement-lifecycle-preflight-only",
        action="store_true",
        help=(
            "run source-solo finite placement lifecycle callbacks only; do not "
            "render atlas nodes, adaptive probes, or a pack"
        ),
    )
    parser.add_argument("--parameter-placement-lifecycle-preflight-output", type=Path)
    parser.add_argument("--maximum-iterations", type=int, default=8)
    args = parser.parse_args(argv)
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        if args.parameter_placement_lifecycle_preflight_only:
            if args.parameter_placement_lifecycle_preflight_output is None:
                raise ValueError(
                    "--parameter-placement-lifecycle-preflight-output is required "
                    "with --parameter-placement-lifecycle-preflight-only"
                )
            report = run_parameter_placement_lifecycle_preflight(
                plan,
                bank_path=args.bank,
                graph_path=args.graph,
                audio_lab_root=args.audio_lab_root,
                assetto_root=args.assetto_root,
                output_directory=args.node_directory,
                state_path=args.state_output,
            )
            _write_json_atomic(
                args.parameter_placement_lifecycle_preflight_output, report
            )
            return 0
        if (
            args.plan_output is None
            or args.realization_output is None
            or args.oracle_output is None
        ):
            raise ValueError(
                "--plan-output, --realization-output, and --oracle-output are "
                "required unless running the lifecycle preflight"
            )
        final_plan, realization, oracle = refine_atlas(plan, bank_path=args.bank, graph_path=args.graph, audio_lab_root=args.audio_lab_root, assetto_root=args.assetto_root, node_directory=args.node_directory, state_path=args.state_output, maximum_iterations=args.maximum_iterations)
        _write_json_atomic(args.plan_output, final_plan)
        _write_json_atomic(args.realization_output, realization)
        _write_json_atomic(args.oracle_output, oracle)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
