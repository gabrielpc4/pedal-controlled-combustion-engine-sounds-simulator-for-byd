"""Fresh-process PCM oracle for exact-zero continuous turbo loops.

This tool deliberately stops short of certifying a runtime policy.  FMOD
1.08 can restore an exact-zero virtualized voice at more than one stable PCM
phase even when the bank and request are byte-identical.  The tool therefore
collects hash-bound baseline, pitch-only, zero-gap, and brief recovery renders
in separate Python processes and preserves every observed branch for later
source-bound certification.

The release compiler must not consume this document until its result is
upgraded by a finite-branch certifier.  In particular, Channel::getPosition
while a voice is virtual is evidence only and is never treated as phase truth.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from tools.probe_fmod_engine_transients import (
    _EngineRuntime,
    _fractional_phase_alignment_metrics,
)
from tools.probe_fmod_turbo_transients import _event_runtime_identity_map


REQUEST_SCHEMA = "ac-fmod-continuous-turbo-zero-worker-request-v1"
RESULT_SCHEMA = "ac-fmod-continuous-turbo-zero-worker-result-v1"
RAW_PROOF_SCHEMA = "ac-fmod-continuous-turbo-zero-raw-proof-v1"
DSP_BUFFER_FRAMES = 256
WORKER_TIMEOUT_SECONDS = 180
DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_CLASSIFICATION = (
    PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json"
)
DEFAULT_OUTPUT_ROOT = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\oracles\continuous-source-oracle-v1"
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_sha(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _load_json(path: Path, schema: str | None = None) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root is not an object: {path}")
    if schema is not None and value.get("schema") != schema:
        raise ValueError(f"{path} is not a {schema} document")
    return value


def _write_canonical(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    os.replace(temporary, path)


def _read_fmod_writer_pcm16(path: Path) -> tuple[bytes, int]:
    """Read FMOD 1.08's valid but noncanonical 40-byte PCM fmt chunk.

    Python's :mod:`wave` treats four bytes of this pinned writer's data as an
    extension even though the RIFF chunk offsets and sizes are internally
    consistent.  Parsing the bounded RIFF chunks directly avoids silently
    losing one stereo frame.
    """

    raw = path.read_bytes()
    if len(raw) < 44 or raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError(f"not a RIFF/WAVE file: {path}")
    declared_total = struct.unpack_from("<I", raw, 4)[0] + 8
    # FMOD 1.08 WAVWRITER_NRT writes a 40-byte PCM fmt chunk but computes the
    # outer RIFF size as if fmt were 36 bytes.  The nested data chunk still
    # ends exactly at EOF.  Accept only that pinned four-byte header quirk (or
    # a canonical exact size), never arbitrary trailing bytes.
    if len(raw) - declared_total not in {0, 4}:
        raise ValueError(f"FMOD writer RIFF length differs: {path}")
    cursor = 12
    geometry: tuple[int, int, int, int, int, int] | None = None
    pcm: bytes | None = None
    while cursor + 8 <= len(raw):
        kind = raw[cursor : cursor + 4]
        size = struct.unpack_from("<I", raw, cursor + 4)[0]
        start = cursor + 8
        end = start + size
        if end > len(raw):
            raise ValueError(f"truncated RIFF chunk in {path}")
        if kind == b"fmt ":
            if size < 16 or geometry is not None:
                raise ValueError(f"invalid/duplicate fmt chunk in {path}")
            geometry = struct.unpack_from("<HHIIHH", raw, start)
        elif kind == b"data":
            if pcm is not None:
                raise ValueError(f"duplicate data chunk in {path}")
            pcm = raw[start:end]
        cursor = end + (size & 1)
    if geometry != (1, 2, 48000, 192000, 4, 16):
        raise ValueError(f"unexpected FMOD writer PCM geometry in {path}: {geometry}")
    if pcm is None or len(pcm) % 4:
        raise ValueError(f"missing/non-frame-aligned PCM payload in {path}")
    return pcm, len(pcm) // 4


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _curve_value(curve: list[list[float]], control: float) -> float:
    if not curve:
        raise ValueError("runtime curve is empty")
    if control <= float(curve[0][0]):
        return float(curve[0][1])
    if control >= float(curve[-1][0]):
        return float(curve[-1][1])
    for left, right in zip(curve, curve[1:]):
        x0, y0 = map(float, left)
        x1, y1 = map(float, right)
        if x0 <= control <= x1:
            if x1 == x0:
                return y1
            fraction = (control - x0) / (x1 - x0)
            return y0 + fraction * (y1 - y0)
    raise AssertionError("curve lookup fell outside a bounded domain")


def select_stable_zero_control(
    curve: list[list[float]], capture_control: float, *, reachable_maximum: float = 1.0
) -> float:
    """Select an interior point of the nearest reachable exact-zero interval."""

    spans: list[tuple[float, float]] = []
    for left, right in zip(curve, curve[1:]):
        x0, y0 = map(float, left)
        x1, y1 = map(float, right)
        low = max(0.0, x0)
        high = min(reachable_maximum, x1)
        if high > low and y0 == 0.0 and y1 == 0.0:
            spans.append((low, high))
    if not spans:
        raise ValueError("continuous turbo curve has no reachable exact-zero interval")
    low, high = min(
        spans,
        key=lambda span: abs(((span[0] + span[1]) * 0.5) - capture_control),
    )
    result = (low + high) * 0.5
    if not low < result < high or _curve_value(curve, result) != 0.0:
        raise AssertionError("zero-control selector did not choose a stable interior")
    return result


def _read_canonical_pcm16_stereo(path: Path) -> Any:
    import numpy as np
    import wave

    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError(f"noncanonical cropped PCM: {path}")
        frame_count = source.getnframes()
        payload = source.readframes(frame_count)
    if len(payload) != frame_count * 4:
        raise ValueError(f"truncated cropped PCM: {path}")
    return np.frombuffer(payload, dtype="<i2").reshape(frame_count, 2).copy()


def fit_exact_zero_transition(target_pcm: Any, pitch_reference_pcm: Any) -> dict[str, Any]:
    """Fit FMOD's bounded zero transition without asymptotic smoothing."""

    import numpy as np

    target = np.asarray(target_pcm, dtype=np.int16)
    reference = np.asarray(pitch_reference_pcm, dtype=np.int16)
    if target.shape != reference.shape or target.ndim != 2 or target.shape[1] != 2:
        raise ValueError("zero transition needs equal stereo PCM windows")
    if len(target) == 0 or len(target) > DSP_BUFFER_FRAMES * 64:
        raise ValueError("zero transition window is empty or unbounded")
    nonzero = np.flatnonzero(np.any(target != 0, axis=1))
    exact_zero_frame = int(nonzero[-1]) + 1 if len(nonzero) else 0
    target_f64 = target.astype(float)
    reference_f64 = reference.astype(float)
    best: tuple[float, float, int, int] | None = None
    for lag in range(-4, 5):
        shifted = np.zeros_like(reference_f64)
        if lag < 0:
            shifted[-lag:] = reference_f64[:lag]
        elif lag > 0:
            shifted[:-lag] = reference_f64[lag:]
        else:
            shifted[:] = reference_f64
        starts = (
            (0,)
            if exact_zero_frame == 0
            else range(max(0, exact_zero_frame - 512), exact_zero_frame)
        )
        for fade_start in starts:
            envelope = np.ones(len(target), dtype=float)
            fade_frames = exact_zero_frame - fade_start
            if fade_frames:
                envelope[fade_start:exact_zero_frame] = 1.0 - (
                    np.arange(fade_frames, dtype=float) / fade_frames
                )
            envelope[exact_zero_frame:] = 0.0
            residual = target_f64 - np.rint(shifted * envelope[:, None])
            candidate = (
                float(np.max(np.abs(residual))),
                float(np.mean(residual * residual)),
                lag,
                fade_start,
            )
            if best is None or candidate[:2] < best[:2]:
                best = candidate
    assert best is not None
    maximum_error, mse, lag, fade_start = best
    return {
        "accepted": maximum_error <= 1.0,
        "policy": (
            "IMMEDIATE_EXACT_ZERO"
            if exact_zero_frame == 0
            else "RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO"
        ),
        "frameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
        "gainInterpolation": "LINEAR_PER_WRITER_FRAME",
        "retainPreZeroGainWriterFrames": fade_start,
        "linearFadeWriterFrames": exact_zero_frame - fade_start,
        "exactZeroFromWriterFrame": exact_zero_frame,
        "pitchOnlyReferencePhaseOffsetWriterFrames": lag,
        "pitchOnlyReferencePhaseOffsetBoundWriterFrames": 4,
        "residualMaximumAbsolutePcmLsb": maximum_error,
        "residualMeanSquarePcmLsb": mse,
        "acceptanceBoundMaximumAbsolutePcmLsb": 1.0,
    }


def _integer_alignment_metrics(target: Any, reference: Any) -> dict[str, Any]:
    import numpy as np

    left = np.asarray(target, dtype=float)
    right = np.asarray(reference, dtype=float)
    if left.shape != right.shape or len(left) < 1024:
        raise ValueError("phase alignment needs equal bounded PCM windows")
    difference = left - right
    left_rms = float(np.sqrt(np.mean(left * left)))
    right_rms = float(np.sqrt(np.mean(right * right)))
    difference_rms = float(np.sqrt(np.mean(difference * difference)))
    denominator = float(np.sqrt(np.sum(left * left) * np.sum(right * right)))
    return {
        "normalizedCorrelation": (
            float(np.sum(left * right)) / denominator if denominator else 0.0
        ),
        "gainErrorDb": abs(
            20.0
            * math.log10(max(left_rms, 1.0e-12) / max(right_rms, 1.0e-12))
        ),
        "differenceBelowReferenceDb": (
            240.0
            if difference_rms == 0.0
            else 20.0 * math.log10(max(left_rms, 1.0e-12) / difference_rms)
        ),
        "maximumAbsoluteDifferencePcmLsb": int(
            np.max(np.abs(difference.astype(int)))
        ),
    }


def measure_restore_phase(
    baseline_pcm: Any,
    gap_pcm: Any,
    *,
    zero_start_frame: int,
    restore_frame: int,
    first_virtual_frame: int | None,
) -> dict[str, Any]:
    """Measure post-restore phase without consulting virtual PCM position."""

    import numpy as np

    baseline = np.asarray(baseline_pcm, dtype=np.int16)
    gap = np.asarray(gap_pcm, dtype=np.int16)
    if baseline.shape != gap.shape or baseline.ndim != 2 or baseline.shape[1] != 2:
        raise ValueError("restore comparison needs equal stereo PCM")
    hold_frames = restore_frame - zero_start_frame
    if hold_frames <= 0 or hold_frames % DSP_BUFFER_FRAMES:
        raise ValueError("zero hold is not a positive writer-block interval")
    if first_virtual_frame is None:
        phase_advance = hold_frames
        phase_kind = "ADVANCE_FOR_COMPLETE_ZERO_INTERVAL_NO_VIRTUAL_TRANSITION"
    else:
        phase_advance = first_virtual_frame - zero_start_frame
        if not 0 < phase_advance <= hold_frames:
            raise ValueError("real-to-virtual onset is outside the zero interval")
        phase_kind = "ADVANCE_UNTIL_DIRECT_REAL_TO_VIRTUAL_TRANSITION_THEN_HOLD"
    candidates: list[tuple[float, float, int, int, dict[str, Any], Any, Any]] = []
    for settle_updates in range(1, 13):
        target_start = restore_frame + settle_updates * DSP_BUFFER_FRAMES
        nominal_reference_start = (
            zero_start_frame + phase_advance + settle_updates * DSP_BUFFER_FRAMES
        )
        for lag in range(-512, 513):
            reference_start = nominal_reference_start + lag
            frames = min(
                len(gap) - target_start - DSP_BUFFER_FRAMES,
                len(baseline) - reference_start - DSP_BUFFER_FRAMES,
            )
            if reference_start < 0 or frames < 4096:
                continue
            target = gap[target_start : target_start + frames]
            reference = baseline[reference_start : reference_start + frames]
            metrics = _integer_alignment_metrics(target, reference)
            candidates.append(
                (
                    float(metrics["normalizedCorrelation"]),
                    -float(metrics["gainErrorDb"]),
                    settle_updates,
                    lag,
                    metrics,
                    target,
                    reference,
                )
            )
    if not candidates:
        raise ValueError("no bounded post-restore comparison window exists")
    _, _, settle_updates, lag, metrics, target, reference = max(
        candidates, key=lambda item: (item[0], item[1], -abs(item[3]), -item[2])
    )
    fractional = None
    accepted = (
        float(metrics["gainErrorDb"]) <= 0.02
        and (
            float(metrics["normalizedCorrelation"]) >= 0.999
            or int(metrics["maximumAbsoluteDifferencePcmLsb"]) <= 1
        )
    )
    fractional_offset = 0.0
    if not accepted:
        fractional = _fractional_phase_alignment_metrics(target, reference)
        accepted = (
            float(fractional["gainErrorDb"]) <= 0.02
            and float(fractional["normalizedCorrelation"]) >= 0.999
            and float(fractional["earlyLateEstimateDifferenceFrames"]) <= 0.01
        )
        if accepted:
            fractional_offset = float(fractional["phaseOffsetReferenceFrames"])
            metrics = {
                "normalizedCorrelation": fractional["normalizedCorrelation"],
                "gainErrorDb": fractional["gainErrorDb"],
                "differenceBelowReferenceDb": fractional[
                    "differenceBelowReferenceDb"
                ],
                "maximumAbsoluteDifferencePcmLsb": math.ceil(
                    float(fractional["maximumAbsoluteDifferencePcmLsb"])
                ),
            }
    offset = float(lag) + fractional_offset
    return {
        "accepted": accepted,
        "comparison": "POST_RESTORE_CAPTURE_PCM_PHASE_ONLY",
        "phaseAdvancePolicy": phase_kind,
        "phaseAndDeadlineAdvanceWriterFramesBeforeHold": phase_advance,
        "channelGetPositionWhileVirtualUsedAsPredictor": False,
        "postRestoreSettlingUpdates": settle_updates,
        "integerPhaseOffsetFrames": lag,
        "fractionalPhaseOnlyAlignment": fractional,
        "restoreCapturePcmPhaseOffsetFrames": offset,
        "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames": 512.0,
        **metrics,
        "acceptanceBounds": {
            "maximumGainErrorDb": 0.02,
            "minimumNormalizedCorrelation": 0.999,
            "maximumAbsoluteDifferencePcmLsbAlternative": 1,
            "maximumFractionalEarlyLateDifferenceFrames": 0.01,
            "maximumAbsoluteRestorePhaseOffsetFrames": 512.0,
        },
    }


def _target_observation(
    runtime: _EngineRuntime,
    instance_key: str,
    source_guid: str,
    token: int,
    *,
    label: str,
    writer_frame_after_schedule: int,
) -> dict[str, Any]:
    voices = runtime.target_channels(instance_key, source_guid)
    selected = [item for item in voices if int(item["voiceToken"]) == token]
    if len(selected) > 1:
        raise AssertionError("continuous target voice duplicated")
    return {
        "label": label,
        "writerFrameAfterSchedule": writer_frame_after_schedule,
        "targetVoiceCount": len(voices),
        "targetVoice": copy.deepcopy(selected[0]) if selected else None,
    }


def _render_worker(request: dict[str, Any]) -> dict[str, Any]:
    from tools.probe_fmod_continuous_sources import (
        _write_pcm16_wav,
    )

    bank = Path(str(request["runtimeBankPath"])).resolve(strict=True)
    if _sha256(bank) != request["runtimeBankSha256"]:
        raise AssertionError("continuous-zero worker bank hash changed")
    root = Path(str(request["assettoRoot"])).resolve(strict=True)
    output_wav = Path(str(request["writerPath"])).resolve()
    output_wav.parent.mkdir(parents=True, exist_ok=True)
    scenario = str(request["scenario"])
    if scenario not in {"BASELINE", "PITCH_ONLY_REFERENCE", "ZERO_GAP", "BRIEF_ZERO"}:
        raise ValueError(f"unknown continuous-zero scenario: {scenario}")
    source_guid = _guid(request["sourceGuid"])
    event_path = str(request["eventPath"])
    capture_boost = float(request["captureBoost"])
    zero_boost = float(request["zeroBoost"])
    zero_pitch_rate = float(request["zeroPitchRate"])
    if not all(math.isfinite(value) for value in (capture_boost, zero_boost, zero_pitch_rate)):
        raise ValueError("continuous-zero controls are nonfinite")
    if zero_pitch_rate <= 0.0:
        raise ValueError("continuous-zero pitch rate is nonpositive")
    runtime_identity = {str(request["runtimeName"]): source_guid}
    observations: list[dict[str, Any]] = []
    callback_before_release: list[dict[str, Any]] = []
    rendered_frames = 0
    schedule_clock = -1
    with _EngineRuntime(
        root,
        bank,
        runtime_identity,
        output_wav,
        max_channels=2048,
        event_paths=(event_path,),
    ) as runtime:
        key = "continuous-zero"
        runtime.create_instance(key, event_path, parameters={"boost": capture_boost})
        runtime.start(key)
        runtime.flush("continuous-zero-start")
        first: dict[str, Any] | None = None
        for index in range(16):
            runtime.update(f"continuous-zero-select-{index}")
            voices = runtime.target_channels(key, source_guid)
            if voices:
                if len(voices) != 1:
                    raise AssertionError("isolated continuous loop scheduled multiple voices")
                first = voices[0]
                break
        if first is None:
            raise AssertionError("isolated continuous loop did not schedule")
        token = int(first["voiceToken"])
        schedule_clock = int(first["writerMasterDspClock"])
        if schedule_clock < 0 or schedule_clock % DSP_BUFFER_FRAMES:
            raise AssertionError("continuous loop schedule clock is not block aligned")

        def update(label: str) -> None:
            nonlocal rendered_frames
            runtime.update(label)
            rendered_frames += DSP_BUFFER_FRAMES
            observations.append(
                _target_observation(
                    runtime,
                    key,
                    source_guid,
                    token,
                    label=label,
                    writer_frame_after_schedule=rendered_frames,
                )
            )

        preroll_updates = int(request["prerollUpdates"])
        hold_updates = int(request["holdUpdates"])
        tail_updates = int(request["tailUpdates"])
        for index in range(preroll_updates):
            update(f"preroll-{index}")
        first_zero_frame: int | None = None
        positive_return_frame: int | None = None
        second_zero_frame: int | None = None
        if scenario == "ZERO_GAP":
            first_zero_frame = rendered_frames
            runtime.set_parameter(key, "boost", zero_boost)
            runtime.flush("zero-gap-enter")
            for index in range(hold_updates):
                update(f"zero-gap-hold-{index}")
            positive_return_frame = rendered_frames
            runtime.set_parameter(key, "boost", capture_boost)
            runtime.flush("zero-gap-restore")
        elif scenario == "PITCH_ONLY_REFERENCE":
            runtime.set_pitch(key, zero_pitch_rate)
            runtime.flush("pitch-reference-enter")
            for index in range(hold_updates):
                update(f"pitch-reference-hold-{index}")
            runtime.set_pitch(key, 1.0)
            runtime.flush("pitch-reference-restore")
        elif scenario == "BRIEF_ZERO":
            first_zero_frame = rendered_frames
            runtime.set_parameter(key, "boost", zero_boost)
            runtime.flush("brief-zero-enter")
            for index in range(int(request["briefFirstZeroUpdates"])):
                update(f"brief-first-zero-{index}")
            positive_return_frame = rendered_frames
            runtime.set_parameter(key, "boost", capture_boost)
            runtime.flush("brief-positive-return")
            for index in range(int(request["briefPositiveUpdates"])):
                update(f"brief-positive-{index}")
            second_zero_frame = rendered_frames
            runtime.set_parameter(key, "boost", zero_boost)
            runtime.flush("brief-second-zero")
            for index in range(int(request["briefSecondZeroUpdates"])):
                update(f"brief-second-zero-{index}")
            runtime.set_parameter(key, "boost", capture_boost)
            runtime.flush("brief-final-restore")
        else:
            for index in range(hold_updates):
                update(f"baseline-hold-{index}")
        restore_frame = rendered_frames
        for index in range(tail_updates):
            update(f"tail-{index}")
        callback_before_release = copy.deepcopy(runtime.callbacks)
        if [item["kind"] for item in callback_before_release] != ["played"]:
            raise AssertionError("continuous loop retriggered or stopped during bounded render")
        runtime.stop_release(key)
    if _sha256(bank) != request["runtimeBankSha256"]:
        raise AssertionError("continuous-zero worker changed isolated bank")
    payload, frame_count = _read_fmod_writer_pcm16(output_wav)
    end_clock = schedule_clock + rendered_frames
    if end_clock > frame_count:
        raise AssertionError(
            f"continuous-zero writer ended early: {schedule_clock}+{rendered_frames}>{frame_count}"
        )
    cropped_payload = payload[schedule_clock * 4 : end_clock * 4]
    cropped_path = output_wav.with_name(f".{output_wav.stem}.cropped.wav")
    _write_pcm16_wav(cropped_path, cropped_payload)
    first_virtual = next(
        (
            int(item["writerFrameAfterSchedule"])
            for item in observations
            if isinstance(item.get("targetVoice"), dict)
            and item["targetVoice"]["isVirtual"] is True
        ),
        None,
    )
    first_return_real = None
    if first_virtual is not None:
        first_return_real = next(
            (
                int(item["writerFrameAfterSchedule"])
                for item in observations
                if int(item["writerFrameAfterSchedule"]) > first_virtual
                and isinstance(item.get("targetVoice"), dict)
                and item["targetVoice"]["isVirtual"] is False
            ),
            None,
        )
    return {
        "scenario": scenario,
        "sourceGuid": source_guid,
        "eventPath": event_path,
        "captureBoost": capture_boost,
        "zeroBoost": zero_boost,
        "zeroPitchRate": zero_pitch_rate,
        "scheduleStartWriterDspClockFrames": schedule_clock,
        "renderedFrameCount": rendered_frames,
        "prerollUpdates": int(request["prerollUpdates"]),
        "holdUpdates": int(request["holdUpdates"]),
        "tailUpdates": int(request["tailUpdates"]),
        "firstZeroWriterFrame": first_zero_frame,
        "positiveReturnWriterFrame": positive_return_frame,
        "secondZeroWriterFrame": second_zero_frame,
        "restoreWriterFrame": restore_frame,
        "firstVirtualWriterFrame": first_virtual,
        "firstReturnRealWriterFrame": first_return_real,
        "observations": observations,
        "callbacksBeforeRelease": callback_before_release,
        "channelGetPositionWhileVirtualIsRuntimeAuthoritative": False,
        "allFmodCallsReturnedOk": True,
        "croppedWavPath": str(cropped_path),
        "croppedWavSha256": _sha256(cropped_path),
        "croppedPcmPayloadSha256": hashlib.sha256(cropped_payload).hexdigest(),
        "croppedFrameCount": len(cropped_payload) // 4,
    }


def _run_worker(request_path: Path, result_path: Path) -> None:
    request = _load_json(request_path.resolve(strict=True), REQUEST_SCHEMA)
    request_sha = _canonical_sha(request)
    if int(request.get("launcherProcessId", -1)) == os.getpid():
        raise AssertionError("continuous-zero worker did not cross process boundary")
    payload = _render_worker(request)
    result = {
        "schema": RESULT_SCHEMA,
        "requestSha256": request_sha,
        "scenario": request["scenario"],
        "freshProcessBoundary": True,
        "workerProcessId": os.getpid(),
        "payload": payload,
        "payloadSha256": _canonical_sha(payload),
    }
    _write_canonical(result_path.resolve(), result)


def _launch_worker(
    *,
    root: Path,
    bank: Path,
    context: dict[str, Any],
    static: dict[str, Any],
    output_root: Path,
    scenario: str,
    repeat: int,
    capture_boost: float,
    zero_boost: float,
    zero_pitch_rate: float,
    preroll_updates: int,
    hold_updates: int,
    tail_updates: int,
    brief_first_zero_updates: int = 0,
    brief_positive_updates: int = 0,
    brief_second_zero_updates: int = 0,
) -> dict[str, Any]:
    source_guid = context["sourceGuid"]
    label = f"{scenario.casefold()}-{repeat:03d}"
    source_root = output_root / "turbo-continuous" / source_guid / "zero-oracle"
    request_path = source_root / "requests" / f"{label}.json"
    result_path = source_root / "results" / f"{label}.json"
    writer_path = source_root / "renders" / f"{label}.wav"
    request = {
        "schema": REQUEST_SCHEMA,
        "scenario": scenario,
        "assettoRoot": str(root.resolve(strict=True)),
        "runtimeBankPath": str(bank.resolve(strict=True)),
        "runtimeBankSha256": _sha256(bank),
        "familyId": context["familyId"],
        "sourceGuid": source_guid,
        "eventPath": context["eventPath"],
        "runtimeName": context["runtimeName"],
        "derivedSourceSha256": _canonical_sha(static),
        "captureBoost": capture_boost,
        "zeroBoost": zero_boost,
        "zeroPitchRate": zero_pitch_rate,
        "prerollUpdates": preroll_updates,
        "holdUpdates": hold_updates,
        "tailUpdates": tail_updates,
        "briefFirstZeroUpdates": brief_first_zero_updates,
        "briefPositiveUpdates": brief_positive_updates,
        "briefSecondZeroUpdates": brief_second_zero_updates,
        "writerPath": str(writer_path.resolve()),
        "launcherProcessId": os.getpid(),
        "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
    }
    request_sha = _canonical_sha(request)
    _write_canonical(request_path, request)
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.unlink(missing_ok=True)
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(__file__).resolve()),
            "--worker-request",
            str(request_path),
            "--worker-result",
            str(result_path),
        ],
        cwd=str(PROJECT_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=WORKER_TIMEOUT_SECONDS,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    log_path = source_root / "logs" / f"{label}.json"
    _write_canonical(
        log_path,
        {
            "exitCode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        },
    )
    if completed.returncode != 0 or not result_path.is_file():
        raise AssertionError(
            f"fresh continuous-zero worker failed: {source_guid}/{label} "
            f"exit={completed.returncode} stderr={completed.stderr[-4000:]!r}"
        )
    result = _load_json(result_path, RESULT_SCHEMA)
    payload = result.get("payload")
    if (
        result.get("requestSha256") != request_sha
        or result.get("scenario") != scenario
        or result.get("freshProcessBoundary") is not True
        or not isinstance(result.get("workerProcessId"), int)
        or result["workerProcessId"] == os.getpid()
        or not isinstance(payload, dict)
        or result.get("payloadSha256") != _canonical_sha(payload)
    ):
        raise AssertionError("continuous-zero request/result hash contract changed")
    cropped = Path(str(payload["croppedWavPath"])).resolve(strict=True)
    if _sha256(cropped) != payload["croppedWavSha256"]:
        raise AssertionError("continuous-zero cropped WAV changed")
    payload = copy.deepcopy(payload)
    payload["freshProcessEvidence"] = {
        "requestSchema": REQUEST_SCHEMA,
        "resultSchema": RESULT_SCHEMA,
        "requestSha256": request_sha,
        "requestFileRelativePath": request_path.relative_to(output_root).as_posix(),
        "requestFileSha256": _sha256(request_path),
        "resultFileRelativePath": result_path.relative_to(output_root).as_posix(),
        "resultFileSha256": _sha256(result_path),
        "payloadSha256": result["payloadSha256"],
        "workerProcessId": result["workerProcessId"],
        "launcherProcessId": os.getpid(),
        "processBoundary": "ONE_NEW_PYTHON_PROCESS_FOR_THIS_RENDER",
        "timeoutSeconds": WORKER_TIMEOUT_SECONDS,
        "logRelativePath": log_path.relative_to(output_root).as_posix(),
        "logFileSha256": _sha256(log_path),
    }
    return payload


def collect_raw_source_proof(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    source_guid: str,
    output_root: Path,
    *,
    gap_repeats: int = 12,
) -> dict[str, Any]:
    """Collect raw branches; never assert a final runtime exactness claim."""

    from tools.probe_fmod_continuous_sources import _isolated_bank, _source_context

    if gap_repeats < 2 or gap_repeats > 128:
        raise ValueError("gap repeats must be in 2..128")
    root = assetto_root.resolve(strict=True)
    output_root = output_root.resolve()
    context = _source_context(
        graph_root.resolve(strict=True),
        classification_path.resolve(strict=True),
        source_guid,
    )
    static = context["derived"]
    verification_path = (
        output_root / "turbo-continuous" / context["sourceGuid"] / "verification.json"
    )
    verification = _load_json(
        verification_path.resolve(strict=True),
        "ac-fmod-continuous-turbo-source-verification-v1",
    )
    if (
        static.get("schema") != "ac-fmod-static-continuous-turbo-source-v1"
        or static.get("programMode") != "TIMELINE_PERSISTENT_LOOP"
        or verification.get("disposition") != "AUDIBLE_TARGET_PCM"
        or verification.get("derivedSourceSha256") != _canonical_sha(static)
    ):
        raise ValueError("source is not an audible verified timeline turbo loop")
    gain_curve = verification["gainVerification"]["captureRelativeRoutedGainCurve"]
    capture_boost = float(verification["capture"]["captureBoost"])
    zero_boost = select_stable_zero_control(gain_curve, capture_boost)
    pitch_curve = verification["pitchVerification"][
        "captureRelativePlaybackRateCurve"
    ]
    zero_pitch_rate = _curve_value(pitch_curve, zero_boost)
    bank, isolation = _isolated_bank(root, context, output_root)
    common = {
        "root": root,
        "bank": bank,
        "context": context,
        "static": static,
        "output_root": output_root,
        "capture_boost": capture_boost,
        "zero_boost": zero_boost,
        "zero_pitch_rate": zero_pitch_rate,
        "preroll_updates": 4,
        "hold_updates": 20,
        "tail_updates": 40,
    }
    renders: dict[str, list[dict[str, Any]]] = {
        "baseline": [],
        "pitchOnlyReference": [],
        "zeroGap": [],
        "briefZeroOneBlock": [],
        "briefZeroTwoBlocks": [],
    }
    for repeat in range(2):
        renders["baseline"].append(
            _launch_worker(scenario="BASELINE", repeat=repeat, **common)
        )
        renders["pitchOnlyReference"].append(
            _launch_worker(scenario="PITCH_ONLY_REFERENCE", repeat=repeat, **common)
        )
    for repeat in range(gap_repeats):
        renders["zeroGap"].append(
            _launch_worker(scenario="ZERO_GAP", repeat=repeat, **common)
        )
    for first_zero_updates, key in (
        (1, "briefZeroOneBlock"),
        (2, "briefZeroTwoBlocks"),
    ):
        for repeat in range(2):
            renders[key].append(
                _launch_worker(
                    scenario="BRIEF_ZERO",
                    repeat=repeat,
                    brief_first_zero_updates=first_zero_updates,
                    brief_positive_updates=1,
                    brief_second_zero_updates=8,
                    **common,
                )
            )
    raw: dict[str, Any] = {
        "schema": RAW_PROOF_SCHEMA,
        "result": "PENDING_FINITE_BRANCH_RUNTIME_POLICY",
        "familyId": context["familyId"],
        "sourceGuid": context["sourceGuid"],
        "eventPath": context["eventPath"],
        "derivedSourceSha256": _canonical_sha(static),
        "sourceVerificationPayloadSha256": verification[
            "verificationPayloadSha256"
        ],
        "captureBoost": capture_boost,
        "zeroBoost": zero_boost,
        "zeroBoostSelection": "INTERIOR_OF_NEAREST_REACHABLE_EXACT_ZERO_INTERVAL",
        "zeroPitchRate": zero_pitch_rate,
        "freshGapRenderCount": gap_repeats,
        "minimumFreshGapRendersForDeterministicClaim": 2,
        "minimumFreshGapRendersForBoundedStochasticClaim": 64,
        "virtualChannelPcmPositionTreatment": "EVIDENCE_ONLY_NOT_RUNTIME_PHASE_TRUTH",
        "isolation": isolation,
        "renders": renders,
        "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
        "exactnessClaim": False,
        "blocker": "FINITE_BRANCH_PREDICTOR_OR_STABLE_64_RUN_DISTRIBUTION_NOT_CERTIFIED",
    }
    raw["rawProofPayloadSha256"] = _canonical_sha(raw)
    proof_path = (
        output_root
        / "turbo-continuous"
        / context["sourceGuid"]
        / "zero-oracle"
        / "raw-proof.json"
    )
    _write_canonical(proof_path, raw)
    return raw


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker-request", type=Path)
    parser.add_argument("--worker-result", type=Path)
    subparsers = parser.add_subparsers(dest="command")
    probe = subparsers.add_parser("probe-source")
    probe.add_argument("--assetto-root", type=Path)
    probe.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    probe.add_argument("--classification", type=Path, default=DEFAULT_CLASSIFICATION)
    probe.add_argument("--source-guid", required=True)
    probe.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    probe.add_argument("--gap-repeats", type=int, default=12)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.worker_request or args.worker_result:
        if not args.worker_request or not args.worker_result or args.command is not None:
            raise ValueError("worker mode needs both paths and no subcommand")
        _run_worker(args.worker_request, args.worker_result)
        return 0
    if args.command != "probe-source":
        raise ValueError("probe-source is required")
    proof = collect_raw_source_proof(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        args.source_guid,
        args.output_root,
        gap_repeats=args.gap_repeats,
    )
    print(
        f"continuous turbo zero raw source={proof['sourceGuid']} "
        f"gaps={proof['freshGapRenderCount']} result={proof['result']}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
