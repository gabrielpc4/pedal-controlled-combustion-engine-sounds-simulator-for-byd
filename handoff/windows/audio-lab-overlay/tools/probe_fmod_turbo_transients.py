"""Silently certify every official FMOD turbo-event one-shot source.

This oracle never opens an audio device or modifies the Assetto Corsa install.
Each authored waveform GUID is rendered through a temporary target-only bank,
then bound to its parsed gain/pitch automation, scheduling topology, final PCM,
and live FMOD software-channel priority.  Embedded sound names are used only
as a runtime identity join after semantic classification; they are never
emitted into release records or used to decide BOV versus turbo behavior.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import wave
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import (
    TURBO_CONTROL_GAIN_ORACLE_VERSION,
    TURBO_PCM_CAPTURE_ORACLE_VERSION,
    TURBO_TRANSIENT_VERIFICATION_SCHEMA,
    certify_manifest_turbo_transient_source,
    derive_manifest_turbo_transient_source,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer
from tools.probe_fmod_global_voice_arbitration import _OracleRuntime


SCHEMA = "ac-fmod-turbo-transient-oracle-v1"
CLASSIFIER_SCHEMA = "ac-fmod-catalog-source-role-classification-v2"
GRAPH_SUMMARY_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
PRIORITY_SCHEMA = "ac-fmod-source-priority-catalog-oracle-v1"
DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_CLASSIFICATION = (
    PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json"
)
DEFAULT_PRIORITY_PROOF = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\priority-oracle-v1\proof.json"
)
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / ".aclib-local" / "turbo-transient-oracle-v1"
DSP_BUFFER_FRAMES = 256
SAMPLE_RATE_HZ = 48000
CHANNELS = 2
BYTES_PER_FRAME = 4
# FMOD 1.08.12's WAVWRITER_NRT output trails the Studio scheduling/mixer
# timeline by eight configured 256-frame blocks.  Every target capture renders
# this bounded tail and removes it before any source/loop boundary is measured.
WRITER_PIPELINE_LATENCY_FRAMES = 2048
ROUTED_GAIN_ABSOLUTE_TOLERANCE = 2.0e-4
ROUTED_GAIN_MAXIMUM_ADAPTIVE_DEPTH = 24
ROUTED_GAIN_PROBE_FRACTIONS = (
    0.125,
    0.25,
    0.375,
    0.5,
    0.625,
    0.75,
    0.875,
)


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


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
    temporary.replace(path)


def _read_pcm16_stereo(path: Path) -> tuple[bytes, Any]:
    import numpy as np

    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate() != SAMPLE_RATE_HZ
            or source.getnchannels() != CHANNELS
            or source.getsampwidth() != 2
        ):
            raise AssertionError(f"noncanonical turbo PCM render: {path}")
        payload = source.readframes(source.getnframes())
    samples = np.frombuffer(payload, dtype="<i2").reshape(-1, 2).copy()
    return payload, samples


def _write_pcm16_stereo(path: Path, payload: bytes) -> None:
    if len(payload) % BYTES_PER_FRAME:
        raise AssertionError("stereo PCM payload is not frame aligned")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(CHANNELS)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE_HZ)
        target.writeframes(payload)


def _linear_curve_value(curve: list[list[float]], x: float) -> float:
    if not curve:
        raise ValueError("manifest curve is empty")
    if x <= float(curve[0][0]):
        return float(curve[0][1])
    if x >= float(curve[-1][0]):
        return float(curve[-1][1])
    for left, right in zip(curve, curve[1:]):
        x0, y0 = map(float, left)
        x1, y1 = map(float, right)
        if x <= x1:
            if x1 == x0:
                return y1
            amount = (x - x0) / (x1 - x0)
            return y0 + (y1 - y0) * amount
    raise AssertionError("curve interpolation fell through")


def _float32(value: float) -> float:
    """Round one control value exactly as FMOD Studio 1.08 receives it."""

    return struct.unpack("<f", struct.pack("<f", float(value)))[0]


def _adaptive_empirical_curve(
    evaluate: Any,
    knots: Iterable[float],
    *,
    tolerance: float = ROUTED_GAIN_ABSOLUTE_TOLERANCE,
    maximum_depth: int = ROUTED_GAIN_MAXIMUM_ADAPTIVE_DEPTH,
) -> tuple[list[list[float]], list[dict[str, float]], float]:
    """Linearize a live FMOD fader curve and bound near-vertical intervals.

    Every probe is first rounded to binary32, matching
    ``EventInstance_SetParameterValue``.  Smooth intervals are accepted only
    after seven deterministic interior probes fit the emitted linear segment.
    FMOD 1.08 can expose quantized routable-fader transitions; those are
    narrowed to at most one 2^-24 domain interval (or adjacent binary32
    controls) and reported separately instead of being called smooth/exact.
    """

    ordered_knots = sorted({_float32(float(value)) for value in knots})
    if len(ordered_knots) < 2 or ordered_knots[-1] <= ordered_knots[0]:
        raise AssertionError("empirical routed-gain knots do not span a domain")
    cache: dict[float, float] = {}

    def value(control: float) -> float:
        control = _float32(control)
        if control not in cache:
            gain = float(evaluate(control))
            if not math.isfinite(gain) or gain < 0.0:
                raise AssertionError(
                    f"empirical routed gain is non-finite/negative at {control}"
                )
            cache[control] = gain
        return cache[control]

    emitted: dict[float, float] = {}
    transitions: list[dict[str, float]] = []
    maximum_smooth_error = 0.0

    def visit(left: float, right: float, depth: int) -> None:
        nonlocal maximum_smooth_error
        left = _float32(left)
        right = _float32(right)
        if right <= left:
            return
        left_gain = value(left)
        right_gain = value(right)
        probes: list[tuple[float, float, float]] = []
        maximum_error = 0.0
        for fraction in ROUTED_GAIN_PROBE_FRACTIONS:
            control = _float32(left + ((right - left) * fraction))
            if control <= left or control >= right:
                continue
            observed = value(control)
            amount = (control - left) / (right - left)
            interpolated = left_gain + ((right_gain - left_gain) * amount)
            error = abs(observed - interpolated)
            maximum_error = max(maximum_error, error)
            probes.append((control, observed, error))
        if not probes and abs(right_gain - left_gain) > tolerance:
            # Adjacent binary32 controls can straddle a real FMOD fader step.
            # There is no representable control at which to take an interior
            # sample, but a continuous manifest curve must still report this
            # one-ULP transition rather than call the segment smooth.
            maximum_error = abs(right_gain - left_gain) * 0.5
        if maximum_error <= tolerance:
            emitted[left] = left_gain
            emitted[right] = right_gain
            maximum_smooth_error = max(maximum_smooth_error, maximum_error)
            return

        middle = _float32(left + ((right - left) * 0.5))
        if depth < maximum_depth and left < middle < right:
            visit(left, middle, depth + 1)
            visit(middle, right, depth + 1)
            return

        emitted[left] = left_gain
        emitted[right] = right_gain
        transitions.append(
            {
                "minimum": left,
                "maximum": right,
                "width": right - left,
                "leftRelativeGain": left_gain,
                "rightRelativeGain": right_gain,
                "maximumObservedAbsoluteLinearGainError": maximum_error,
            }
        )

    for left, right in zip(ordered_knots, ordered_knots[1:]):
        visit(left, right, 0)
    curve = [[x, emitted[x]] for x in sorted(emitted)]
    if curve[0][0] != ordered_knots[0] or curve[-1][0] != ordered_knots[-1]:
        raise AssertionError("empirical routed-gain curve lost its domain bounds")
    transitions.sort(key=lambda item: (item["minimum"], item["maximum"]))
    return curve, transitions, maximum_smooth_error


def _target(
    graph_root: Path,
    summary_families: dict[str, dict[str, Any]],
    row: dict[str, Any],
) -> dict[str, Any]:
    family_id = str(row["familyId"])
    source_guid = _guid(row["sourceGuid"])
    family = summary_families.get(family_id)
    if family is None:
        raise ValueError(f"family {family_id} is absent from graph summary")
    graph = _load_json(
        graph_root / "families" / f"{family_id}.json", GRAPH_SCHEMA
    )
    source = next(
        (
            item
            for item in graph["instruments"]
            if _guid(item.get("guid")) == source_guid
        ),
        None,
    )
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        raise ValueError(f"source {source_guid} is absent from family graph")
    event_paths = row.get("eventPaths")
    if not isinstance(event_paths, list) or len(event_paths) != 1:
        raise ValueError(f"source {source_guid} does not have exactly one event")
    event_path = str(event_paths[0])
    event = next(
        (item for item in graph["events"] if str(item.get("path")) == event_path),
        None,
    )
    if event is None or event.get("mappingComplete") is not True:
        raise ValueError(f"event graph is absent/incomplete: {event_path}")
    runtime_name = str((source.get("sample") or {}).get("name") or "")
    if not runtime_name:
        raise ValueError(f"source {source_guid} has no runtime identity")
    return {
        "familyId": family_id,
        "family": family,
        "graph": graph,
        "sourceGuid": source_guid,
        "source": source,
        "event": event,
        "eventPath": event_path,
        "runtimeName": runtime_name,
        "runtimeIdentitySha256": hashlib.sha256(
            runtime_name.encode("utf-8")
        ).hexdigest(),
        "row": row,
    }


def _runtime_bank(
    assetto_root: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
) -> tuple[Path, dict[str, Any]]:
    graph = target["graph"]
    instruments = {
        _guid(item.get("guid")): item
        for item in graph["instruments"]
        if isinstance(item, dict)
    }
    reachable = {
        _guid(guid)
        for guid in target["event"]["reachableInstrumentGuids"]
    }
    source_guid = target["sourceGuid"]
    if source_guid not in reachable:
        raise AssertionError("classified target is not reachable from its event")
    installed = assetto_root / str(target["family"]["bankPath"])
    installed_hash = _sha256(installed)
    if installed_hash != target["familyId"]:
        raise AssertionError(f"installed source bank identity changed: {installed}")
    # Preserve every multi-instrument on the exact selected ancestry.  Other
    # program roots must be disabled as well as their waveform leaves: Porsche
    # 911 GT1 has four simultaneous SMART_RANDOM roots and muting leaves alone
    # still produces sibling SOUND_PLAYED callbacks in FMOD 1.08.
    preserved = {source_guid} | {
        _guid(item.get("parentInstrumentGuid"))
        for item in derived["selectionPath"]
    }
    muted = {
        guid
        for guid in reachable - preserved
        if instruments.get(guid, {}).get("kind")
        in {"WaveformInstrumentNode", "MultiInstrumentNode"}
        and float(
            (instruments[guid].get("baseProperties") or {}).get(
                "triggerChancePercent", 0.0
            )
        )
        > 0.0
    }
    if not muted:
        return installed, {
            "sourceBankSha256": installed_hash,
            "isolatedBankSha256": installed_hash,
            "eventWasAlreadySingleWaveform": True,
            "mutedReachableInstrumentCount": 0,
            "mutedInstrumentKindCounts": {},
            "preservedSelectionAncestorCount": len(preserved) - 1,
            "targetAndSelectionAncestryWereNotPatched": True,
            "changedBytesOnlyParserAttributedTriggerChanceFields": True,
        }
    isolated_path = output_root / "isolated-banks" / f"{source_guid}.bank"
    patches: list[dict[str, Any]] = []
    source_size = installed.stat().st_size
    with installed.open("rb") as raw:
        for guid in sorted(muted):
            instrument = instruments[guid]
            properties = instrument.get("baseProperties") or {}
            try:
                offset = int(properties["triggerChancePercentFileOffset"])
                raw_uint32 = int(properties["triggerChancePercentRawUInt32"])
                percent = float(properties["triggerChancePercent"])
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(
                    f"instrument lacks parser-attributed trigger chance: {guid}"
                ) from exc
            if offset < 0 or offset + 4 > source_size:
                raise ValueError(f"trigger chance is outside bank: {guid}@{offset}")
            raw.seek(offset)
            expected = struct.pack("<I", raw_uint32)
            if raw.read(4) != expected or expected != struct.pack("<f", percent):
                raise ValueError(
                    f"parser/source trigger-chance bytes disagree: {guid}@{offset}"
                )
            patches.append(
                {
                    "instrumentGuid": guid,
                    "instrumentKind": instrument["kind"],
                    "offset": offset,
                    "originalChancePercent": percent,
                }
            )
    offsets = [int(item["offset"]) for item in patches]
    if len(offsets) != len(set(offsets)):
        raise ValueError("target isolation has shared trigger-chance offsets")
    isolated_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{isolated_path.name}.",
            suffix=".tmp",
            dir=isolated_path.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
        shutil.copyfile(installed, temporary_path)
        with temporary_path.open("r+b", buffering=0) as isolated:
            for patch in patches:
                isolated.seek(int(patch["offset"]))
                isolated.write(b"\0\0\0\0")
            isolated.flush()
            os.fsync(isolated.fileno())
        os.replace(temporary_path, isolated_path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    if _sha256(installed) != installed_hash or installed.stat().st_size != source_size:
        raise AssertionError("installed bank changed during target isolation")
    if isolated_path.stat().st_size != source_size:
        raise AssertionError("isolated bank size changed")
    allowed_offsets = {
        int(item["offset"]) + byte_index
        for item in patches
        for byte_index in range(4)
    }
    changed_offsets: list[int] = []
    absolute = 0
    with installed.open("rb") as before_file, isolated_path.open("rb") as after_file:
        while True:
            before_block = before_file.read(1024 * 1024)
            after_block = after_file.read(1024 * 1024)
            if len(before_block) != len(after_block):
                raise AssertionError("isolated bank block lengths differ")
            if not before_block:
                break
            changed_offsets.extend(
                absolute + index
                for index, (before, after) in enumerate(
                    zip(before_block, after_block)
                )
                if before != after
            )
            absolute += len(before_block)
    if not changed_offsets or not set(changed_offsets).issubset(allowed_offsets):
        raise AssertionError("target isolation changed bytes outside proven fields")
    with isolated_path.open("rb") as isolated:
        for patch in patches:
            isolated.seek(int(patch["offset"]))
            if isolated.read(4) != b"\0\0\0\0":
                raise AssertionError("target isolation left a muted chance nonzero")
    kind_counts = Counter(str(item["instrumentKind"]) for item in patches)
    return isolated_path, {
        "sourceBankSha256": installed_hash,
        "isolatedBankSha256": _sha256(isolated_path),
        "eventWasAlreadySingleWaveform": False,
        "mutedReachableInstrumentCount": len(patches),
        "mutedInstrumentKindCounts": dict(sorted(kind_counts.items())),
        "preservedSelectionAncestorCount": len(preserved) - 1,
        "targetAndSelectionAncestryWereNotPatched": not bool(
            preserved & {str(item["instrumentGuid"]) for item in patches}
        ),
        "changedByteCount": len(changed_offsets),
        "changedBytesOnlyParserAttributedTriggerChanceFields": True,
    }


def _event_runtime_identity_map(target: dict[str, Any]) -> dict[str, str]:
    """Map every reachable callback identity without using names semantically.

    Duplicate embedded names exist when multiple authored leaf nodes reference
    one waveform resource.  The target name is mapped to the target GUID after
    graph-proven target/ancestor isolation; every other name receives an opaque
    hash identity so callbacks remain observable without being misclassified.
    """

    instruments = {
        _guid(item.get("guid")): item
        for item in target["graph"]["instruments"]
        if isinstance(item, dict)
    }
    names: set[str] = set()
    for guid_value in target["event"]["reachableInstrumentGuids"]:
        instrument = instruments.get(_guid(guid_value), {})
        if instrument.get("kind") != "WaveformInstrumentNode":
            continue
        runtime_name = str((instrument.get("sample") or {}).get("name") or "")
        if runtime_name:
            names.add(runtime_name)
    if target["runtimeName"] not in names:
        raise AssertionError("target runtime identity is not reachable")
    return {
        runtime_name: (
            target["sourceGuid"]
            if runtime_name == target["runtimeName"]
            else "opaque-nontarget-"
            + hashlib.sha256(runtime_name.encode("utf-8")).hexdigest()
        )
        for runtime_name in sorted(names)
    }


def _capture_duration_frames(
    target: dict[str, Any], derived: dict[str, Any]
) -> int:
    predicted = _predicted_source_playback_frames(target, derived)
    duration = predicted + SAMPLE_RATE_HZ
    if derived["programMode"] == "TIMELINE_PERIODIC_ONE_SHOT":
        period = int(derived["timelineGeometry"]["repeatPeriodTicks"])
        cycle = max(
            1,
            round(
                float(
                    derived["captureIsolation"][
                        "predictedRenderedSourceCycleFrames"
                    ]
                )
            ),
        )
        # Short time-locked sources repeat many times inside one authored
        # timeline region; capture two cycles for recurrence evidence.  Long
        # sources (Tatuus-style) occupy essentially the complete period, so the
        # exact authored period is the safe outer boundary.
        duration = min(period, max(cycle, 2 * cycle + DSP_BUFFER_FRAMES))
        if duration <= 0:
            raise AssertionError("timeline-safe capture duration is empty")
    return duration


def _predicted_source_playback_frames(
    target: dict[str, Any], derived: dict[str, Any]
) -> int:
    sample = target["source"]["sample"]
    base_semitones = float(
        derived["basePitch"]["effectiveAuthoredBaseSemitonesAtCapture"]
    )
    capture_semitones = sum(
        float(item["captureSemitones"])
        for item in derived["pitchAutomation"]
    )
    rate = 2.0 ** ((base_semitones + capture_semitones) / 12.0)
    seek_percent = sum(
        float(item["initialSeekPercent"])
        for item in derived["sourceGeometry"]["nodes"]
    )
    remaining = max(0.0, 1.0 - seek_percent / 100.0)
    predicted = (
        float(sample["sampleCount"])
        * SAMPLE_RATE_HZ
        / float(sample["frequencyHz"])
        * remaining
        / rate
    )
    return max(1, math.ceil(predicted))


def _embedded_pcm16_boundary_evidence(
    bank: Path, target: dict[str, Any]
) -> dict[str, Any]:
    """Prove the target's native PCM boundaries without using its name.

    Official AC turbo leaves are PCM16 FSB5 subsounds.  Reading the graph's
    numeric bank/subsound identity lets the oracle distinguish authored source
    silence from WAVWRITER scheduling silence while keeping sample names out of
    both semantic decisions and emitted records.
    """

    import numpy as np

    technical = target["source"]["sample"]
    if int(technical["soundBankIndex"]) != 0:
        raise AssertionError("turbo PCM boundary oracle supports FSB index zero only")
    payload = bank.read_bytes()
    fsb_offset = payload.find(b"FSB5")
    if fsb_offset < 0:
        raise AssertionError("target bank has no embedded FSB5 payload")
    try:
        version, sample_total, headers_size, names_size, data_size, audio_type = (
            struct.unpack_from("<6I", payload, fsb_offset + 4)
        )
    except struct.error as exc:
        raise AssertionError("truncated FSB5 header") from exc
    if audio_type != 2:
        raise AssertionError("turbo source-bound PCM oracle requires FSB5 PCM16")
    header_size = 0x40 if version == 0 else 0x3C
    cursor = fsb_offset + header_size
    metadata: list[tuple[int, int]] = []
    for _index in range(sample_total):
        try:
            encoded = struct.unpack_from("<Q", payload, cursor)[0]
        except struct.error as exc:
            raise AssertionError("truncated FSB5 sample metadata") from exc
        cursor += 8
        has_more_chunks = bool(encoded & 1)
        data_offset = ((encoded >> 7) & ((1 << 27) - 1)) * 32
        sample_count = (encoded >> 34) & ((1 << 30) - 1)
        metadata.append((data_offset, sample_count))
        while has_more_chunks:
            try:
                chunk = struct.unpack_from("<I", payload, cursor)[0]
            except struct.error as exc:
                raise AssertionError("truncated FSB5 sample chunk") from exc
            cursor += 4
            has_more_chunks = bool(chunk & 1)
            chunk_size = (chunk >> 1) & ((1 << 24) - 1)
            cursor += chunk_size
    if cursor > fsb_offset + header_size + headers_size:
        raise AssertionError("FSB5 sample headers exceed the declared boundary")
    subsound = int(technical["subsoundIndex"])
    if subsound < 0 or subsound >= len(metadata):
        raise AssertionError("target FSB5 subsound index is out of range")
    data_start = fsb_offset + header_size + headers_size + names_size
    start = data_start + metadata[subsound][0]
    end_offset = (
        metadata[subsound + 1][0]
        if subsound + 1 < len(metadata)
        else data_size
    )
    end = data_start + end_offset
    encoded_payload = payload[start:end]
    if hashlib.sha256(encoded_payload).hexdigest() != str(
        technical["encodedPayloadSha256"]
    ):
        raise AssertionError("embedded target PCM hash differs from graph evidence")
    channels = int(technical["channels"])
    frame_count = int(technical["sampleCount"])
    if metadata[subsound][1] != frame_count or channels not in (1, 2, 6, 8):
        raise AssertionError("embedded target PCM geometry differs from graph evidence")
    required_bytes = frame_count * channels * 2
    if required_bytes > len(encoded_payload):
        raise AssertionError("embedded target PCM payload is truncated")
    samples = np.frombuffer(
        encoded_payload, dtype="<i2", count=frame_count * channels
    ).reshape(frame_count, channels)
    nonzero = np.flatnonzero(np.any(samples != 0, axis=1))
    if len(nonzero):
        leading = int(nonzero[0])
        trailing = int(frame_count - 1 - nonzero[-1])
        all_zero = False
    else:
        leading = trailing = frame_count
        all_zero = True
    # The block inference below preserves the residual zero prefix inside the
    # inferred block.  More than one authored silent source frame would need a
    # source-resampler phase oracle rather than this bounded normalization.
    if not all_zero and leading > 1:
        raise AssertionError(
            "target has unsupported authored leading silence for block normalization"
        )
    return {
        "accepted": True,
        "encoding": "FSB5_PCM16_LE",
        "soundBankIndex": int(technical["soundBankIndex"]),
        "subsoundIndex": subsound,
        "encodedPayloadSha256": str(technical["encodedPayloadSha256"]),
        "sampleRateHz": int(technical["frequencyHz"]),
        "channels": channels,
        "frameCount": frame_count,
        "authoredLeadingSilentFrames": leading,
        "authoredTrailingSilentFrames": trailing,
        "allAuthoredSamplesZero": all_zero,
        "sampleNameUsed": False,
    }


def _normalize_independent_one_shot_renders(
    first: Any,
    second: Any,
    *,
    predicted_playback_frames: int,
) -> tuple[Any, dict[str, Any]]:
    """Remove only quantized-silent whole DSP scheduling blocks.

    FMOD 1.08 WAVWRITER_NRT can place an otherwise bit-identical one-shot one
    256-frame update later in an independent system.  SmartRandom selection
    retries add further whole blocks.  The earliest quantized-nonzero frame is
    rounded *down* to its DSP block for each render; every removed sample must
    be zero, and the complete authored-duration windows must then be bit exact.
    """

    import numpy as np

    if predicted_playback_frames <= 0:
        raise AssertionError("predicted one-shot playback duration is empty")
    first_nonzero = np.flatnonzero(np.any(first != 0, axis=1))
    second_nonzero = np.flatnonzero(np.any(second != 0, axis=1))
    if bool(len(first_nonzero)) != bool(len(second_nonzero)):
        raise AssertionError("independent turbo renders disagree on target silence")
    raw_bit_exact = bool(np.array_equal(first, second))
    if not len(first_nonzero):
        if len(first) < predicted_playback_frames or len(second) < predicted_playback_frames:
            raise AssertionError("silent target render is shorter than authored duration")
        normalized = first[:predicted_playback_frames].copy()
        if not np.array_equal(normalized, second[:predicted_playback_frames]):
            raise AssertionError("silent target render is not repeatable")
        return normalized, {
            "independentRenderRawBitExact": raw_bit_exact,
            "independentRenderBitExact": True,
            "independentRenderComparisonMode": (
                "ALL_ZERO_TARGET_FULL_AUTHORED_DURATION"
            ),
            "independentRenderStartOffsetsFrames": [0, 0],
            "independentRenderStartOffsetDifferenceFrames": 0,
            "independentRenderComparedFrameCount": predicted_playback_frames,
            "dspSchedulingBlockFrames": DSP_BUFFER_FRAMES,
            "removedPrefixSamplesAllZero": True,
            "retainedQuantizedSilentPrefixFrames": [
                predicted_playback_frames,
                predicted_playback_frames,
            ],
            "maximumAudibleSampleLoss": 0,
        }

    first_onset = int(first_nonzero[0])
    second_onset = int(second_nonzero[0])
    first_start = (first_onset // DSP_BUFFER_FRAMES) * DSP_BUFFER_FRAMES
    second_start = (second_onset // DSP_BUFFER_FRAMES) * DSP_BUFFER_FRAMES
    if np.any(first[:first_start] != 0) or np.any(second[:second_start] != 0):
        raise AssertionError("DSP scheduling normalization would remove audible PCM")
    if (
        len(first) - first_start < predicted_playback_frames
        or len(second) - second_start < predicted_playback_frames
    ):
        raise AssertionError("aligned target render is shorter than authored duration")
    first_window = first[
        first_start : first_start + predicted_playback_frames
    ]
    second_window = second[
        second_start : second_start + predicted_playback_frames
    ]
    if not np.array_equal(first_window, second_window):
        maximum_error = int(
            np.max(
                np.abs(
                    first_window.astype(np.int32)
                    - second_window.astype(np.int32)
                )
            )
        )
        raise AssertionError(
            "turbo repeat render differs after DSP-block scheduling alignment "
            f"(maximum error {maximum_error} LSB)"
        )
    return first_window.copy(), {
        "independentRenderRawBitExact": raw_bit_exact,
        "independentRenderBitExact": True,
        "independentRenderComparisonMode": (
            "DSP_BLOCK_SCHEDULING_NORMALIZED_PCM16"
        ),
        "independentRenderStartOffsetsFrames": [first_start, second_start],
        "independentRenderStartOffsetDifferenceFrames": abs(
            first_start - second_start
        ),
        "independentRenderComparedFrameCount": predicted_playback_frames,
        "dspSchedulingBlockFrames": DSP_BUFFER_FRAMES,
        "removedPrefixSamplesAllZero": True,
        "retainedQuantizedSilentPrefixFrames": [
            first_onset - first_start,
            second_onset - second_start,
        ],
        "maximumAudibleSampleLoss": 0,
    }


def _render_capture(
    renderer: SilentFmodReferenceRenderer,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    path: Path,
    duration_frames: int,
) -> None:
    selection_depth = len(derived.get("selectionPath") or [])
    selection_span = math.prod(
        max(1, len(item.get("orderedChildren") or []))
        for item in (derived.get("selectionPath") or [])
    )
    optional_identity = (
        {
            "required_sound_name": target["runtimeName"],
            "maximum_takes": min(4096, max(64, 32 * selection_span)),
        }
        if selection_depth
        else {}
    )
    if selection_depth:
        writer_path = path.with_name(f".{path.stem}.selected-writer.wav")
        identity_map = _event_runtime_identity_map(target)
        parameters = {
            str(name): float(value)
            for name, value in derived["captureParameterValues"].items()
        }
        maximum_attempts = int(optional_identity["maximum_takes"])
        selected_start_frame: int | None = None
        requested_updates = math.ceil(
            (duration_frames + WRITER_PIPELINE_LATENCY_FRAMES)
            / DSP_BUFFER_FRAMES
        )
        with _OracleRuntime(
            renderer.root,
            bank,
            identity_map,
            writer_path,
            max_channels=64,
            event_paths=(target["eventPath"],),
        ) as runtime:
            for attempt in range(maximum_attempts):
                key = f"capture{attempt:04d}"
                start_frame = runtime.update_index * DSP_BUFFER_FRAMES
                runtime.create_instance(
                    key, target["eventPath"], parameters=parameters
                )
                runtime.start(key)
                runtime.flush(f"capture-start-{attempt}")
                runtime.update(f"capture-first-{attempt}")
                snapshot = runtime.snapshot()
                voices = [
                    item
                    for item in snapshot["voices"]
                    if item["instanceKey"] == key
                ]
                target_voices = [
                    item
                    for item in voices
                    if item["source"] == target["sourceGuid"]
                    and float(item["audibility"]) > 1.0e-8
                ]
                audible_nontarget = [
                    item
                    for item in voices
                    if item["source"] != target["sourceGuid"]
                    and float(item["audibility"]) > 1.0e-8
                ]
                if target_voices:
                    if audible_nontarget:
                        raise AssertionError(
                            "selected target take also contains an audible sibling"
                        )
                    selected_start_frame = start_frame
                    for update in range(1, requested_updates):
                        runtime.update(f"capture-tail-{attempt}-{update}")
                    break
                runtime.stop_release(key)
            else:
                raise AssertionError(
                    f"target {target['sourceGuid']} was not selected in "
                    f"{maximum_attempts} deterministic takes"
                )
        if selected_start_frame is None:
            raise AssertionError("selected capture start was not recorded")
        writer_payload, writer_pcm = _read_pcm16_stereo(writer_path)
        del writer_payload
        selected_pcm = writer_pcm[
            selected_start_frame
            + WRITER_PIPELINE_LATENCY_FRAMES : selected_start_frame
            + WRITER_PIPELINE_LATENCY_FRAMES
            + duration_frames
        ]
        if len(selected_pcm) != duration_frames:
            raise AssertionError("selected target capture was truncated")
        _write_pcm16_stereo(path, selected_pcm.astype("<i2").tobytes())
        writer_path.unlink(missing_ok=True)
        return
    uncropped_path = path.with_name(f".{path.stem}.uncropped-writer.wav")
    rendered = renderer.render_event(
        bank,
        target["eventPath"],
        uncropped_path,
        parameters={
            str(name): float(value)
            for name, value in derived["captureParameterValues"].items()
        },
        duration_frames=duration_frames + WRITER_PIPELINE_LATENCY_FRAMES,
        warmup_frames=0,
        **optional_identity,
    )
    if (
        not rendered.scheduled_sound_names
        or set(rendered.scheduled_sound_names) != {target["runtimeName"]}
    ):
        raise AssertionError(
            f"target-only turbo scheduling changed for {target['sourceGuid']}"
        )
    _payload, uncropped_pcm = _read_pcm16_stereo(uncropped_path)
    aligned = uncropped_pcm[
        WRITER_PIPELINE_LATENCY_FRAMES :
        WRITER_PIPELINE_LATENCY_FRAMES + duration_frames
    ]
    if len(aligned) != duration_frames:
        raise AssertionError("target capture latency crop was truncated")
    _write_pcm16_stereo(path, aligned.astype("<i2").tobytes())
    uncropped_path.unlink(missing_ok=True)


def _capture_pcm(
    renderer: SilentFmodReferenceRenderer,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
    *,
    render: bool = True,
) -> dict[str, Any]:
    import numpy as np

    source_guid = target["sourceGuid"]
    work = output_root / "capture-work"
    captures = output_root / "captures"
    work.mkdir(parents=True, exist_ok=True)
    captures.mkdir(parents=True, exist_ok=True)
    first_path = work / f"{source_guid}-a.wav"
    second_path = work / f"{source_guid}-b.wav"
    duration_frames = _capture_duration_frames(target, derived)
    if render:
        _render_capture(renderer, bank, target, derived, first_path, duration_frames)
        _render_capture(renderer, bank, target, derived, second_path, duration_frames)
    elif not first_path.is_file() or not second_path.is_file():
        raise AssertionError(
            f"existing independent turbo renders are absent for {source_guid}"
        )
    first_payload, first = _read_pcm16_stereo(first_path)
    second_payload, second = _read_pcm16_stereo(second_path)
    embedded_boundary = _embedded_pcm16_boundary_evidence(bank, target)
    mode = str(derived["programMode"])
    if mode == "TIMELINE_PERIODIC_ONE_SHOT":
        if first_payload != second_payload:
            raise AssertionError(
                f"timeline turbo repeat render differs for {source_guid}"
            )
        repeat_evidence = {
            "independentRenderRawBitExact": True,
            "independentRenderBitExact": True,
            "independentRenderComparisonMode": "RAW_RENDER_PCM16",
            "independentRenderStartOffsetsFrames": [0, 0],
            "independentRenderStartOffsetDifferenceFrames": 0,
            "independentRenderComparedFrameCount": len(first),
            "dspSchedulingBlockFrames": DSP_BUFFER_FRAMES,
            "removedPrefixSamplesAllZero": True,
            "retainedQuantizedSilentPrefixFrames": [0, 0],
            "maximumAudibleSampleLoss": 0,
        }
        predicted_cycle = float(
            derived["captureIsolation"]["predictedRenderedSourceCycleFrames"]
        )
        expected_cycle = max(1, round(predicted_cycle))
        required_lookahead = int(
            derived["captureIsolation"]["minimumRenderedLookAheadFrames"]
        )
        search_radius = 64
        available_after_search = len(first) - (expected_cycle + search_radius)
        if available_after_search < required_lookahead - search_radius:
            raise AssertionError(
                f"timeline source-cycle look-ahead is truncated for {source_guid}"
            )
        analysis_frames = min(2048, available_after_search)
        if analysis_frames < 64:
            raise AssertionError("timeline recurrence analysis window is too short")
        maximum_reference_start = available_after_search - analysis_frames
        reference_starts = range(0, maximum_reference_start + 1, 16)
        reference_start = max(
            reference_starts,
            key=lambda start: float(
                np.mean(
                    first[start : start + analysis_frames].astype(np.float64)
                    ** 2
                )
            ),
        )
        reference = first[
            reference_start : reference_start + analysis_frames
        ].astype(np.float64)
        reference_energy = float(np.mean(reference * reference))
        if reference_energy < 0.25:
            raise AssertionError(
                f"timeline recurrence reference is quantized silent for {source_guid}"
            )
        candidates: list[tuple[float, float, int]] = []
        for boundary in range(
            expected_cycle - search_radius,
            expected_cycle + search_radius + 1,
        ):
            repeated = first[
                boundary
                + reference_start : boundary
                + reference_start
                + analysis_frames
            ].astype(np.float64)
            difference = reference - repeated
            normalized_error = float(
                np.mean(difference * difference) / reference_energy
            )
            denominator = math.sqrt(
                max(
                    1.0e-24,
                    float(np.sum(reference * reference))
                    * float(np.sum(repeated * repeated)),
                )
            )
            correlation = float(np.sum(reference * repeated)) / denominator
            candidates.append((normalized_error, -correlation, boundary))
        normalized_error, negative_correlation, final_frames = min(candidates)
        correlation = -negative_correlation
        prediction_error = abs(final_frames - predicted_cycle)
        if (
            prediction_error > 1.0
            or normalized_error > 0.5
            or correlation < 0.75
        ):
            raise AssertionError(
                f"timeline recurrence boundary is not proven for {source_guid}: "
                f"prediction={prediction_error:.6f} frames, "
                f"normalizedError={normalized_error:.6f}, "
                f"correlation={correlation:.6f}"
            )
        loop_replacement_error = int(
            np.max(
                np.abs(
                    first[final_frames].astype(np.int32)
                    - first[0].astype(np.int32)
                )
            )
        )
        if loop_replacement_error > 512:
            raise AssertionError(
                f"timeline PCM loop replacement exceeds 512 LSB for {source_guid}"
            )
        source_cycle_isolation = {
            "accepted": True,
            "boundarySelection": (
                "MINIMUM_NORMALIZED_PCM_RECURRENCE_ERROR_AROUND_PARSED_SOURCE_GEOMETRY"
            ),
            "parsedPredictedBoundaryFrames": predicted_cycle,
            "selectedBoundaryFrame": final_frames,
            "predictionErrorFrames": prediction_error,
            "searchRadiusFrames": search_radius,
            "referenceStartFrame": reference_start,
            "analysisFrameCount": analysis_frames,
            "normalizedMeanSquareRecurrenceError": normalized_error,
            "recurrenceCorrelation": correlation,
            "renderedLookAheadFrames": len(first) - final_frames,
            "requiredMinimumLookAheadFrames": required_lookahead,
            "notTrimmedAtLastNonzeroSample": True,
            "loopReplacementMaximumErrorLsb": loop_replacement_error,
            "loopReplacementMaximumErrorDbfs": (
                -240.0
                if loop_replacement_error == 0
                else 20.0
                * math.log10(loop_replacement_error / 32768.0)
            ),
            "independentRenderBitExact": True,
        }
        cycle_extra: dict[str, Any] = {
            "captureMode": "TIME_LOCKED_SOURCE_CYCLE",
            "loopStartFrame": 0,
            "loopEndFrameExclusive": final_frames,
            "programTimelinePeriodFrames": int(
                derived["timelineGeometry"]["repeatPeriodTicks"]
            ),
            "predictedRenderedSourceCycleFrames": predicted_cycle,
            "sourceCycleBoundaryErrorBoundFrames": 1,
            "sourcePlaybackMode": (
                "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT"
            ),
            "sourceCycleIsolation": source_cycle_isolation,
        }
        if len(first) >= 2 * final_frames:
            left = first[:final_frames].astype(np.float64)
            right = first[final_frames : 2 * final_frames].astype(np.float64)
            difference = left - right
            reference_rms = float(np.sqrt(np.mean(left * left)))
            difference_rms = float(np.sqrt(np.mean(difference * difference)))
            cycle_extra["adjacentCycleComparison"] = {
                "bitExact": bool(np.array_equal(left, right)),
                "referenceRmsLsb": reference_rms,
                "differenceRmsLsb": difference_rms,
                "snrDb": (
                    300.0
                    if difference_rms == 0.0
                    else 20.0
                    * math.log10(
                        max(reference_rms, 1.0e-12)
                        / max(difference_rms, 1.0e-12)
                    )
                ),
            }
    else:
        predicted_playback_frames = _predicted_source_playback_frames(
            target, derived
        )
        first, repeat_evidence = _normalize_independent_one_shot_renders(
            first,
            second,
            predicted_playback_frames=predicted_playback_frames,
        )
        first_payload = first.astype("<i2").tobytes()
        final_frames = predicted_playback_frames
        cycle_extra = {"captureMode": "ONE_SHOT_ROUTED_PCM"}
    final_payload = first_payload[: final_frames * BYTES_PER_FRAME]
    final_path = captures / f"{source_guid}.wav"
    _write_pcm16_stereo(final_path, final_payload)
    peak = int(np.max(np.abs(first.astype(np.int32)))) if first.size else 0
    peak_dbfs = (
        20.0 * math.log10(peak / 32768.0) if peak > 0 else -240.0
    )
    all_zero = not any(final_payload)
    return {
        "accepted": True,
        "oracleVersion": TURBO_PCM_CAPTURE_ORACLE_VERSION,
        "captureParameterValues": copy.deepcopy(derived["captureParameterValues"]),
        "scheduledSourceGuid": source_guid,
        **repeat_evidence,
        "embeddedSourcePcmBoundaryEvidence": embedded_boundary,
        "frameCount": final_frames,
        "playbackStartFrame": 0,
        "playbackEndFrameExclusive": final_frames,
        "pcmPayloadSha256": hashlib.sha256(final_payload).hexdigest(),
        "peakPcmDbfs": peak_dbfs,
        "allPcmSamplesZero": all_zero,
        "audibilityDisposition": (
            "AUTHORED_TARGET_SILENT" if all_zero else "AUDIBLE_TARGET_PCM"
        ),
        "renderDurationFrames": duration_frames,
        "writerPipelineLatencyFramesRemoved": WRITER_PIPELINE_LATENCY_FRAMES,
        "terminationTimingErrorBoundFrames": DSP_BUFFER_FRAMES,
        "finalWavRelativePath": final_path.relative_to(output_root).as_posix(),
        "finalWavSha256": _sha256(final_path),
        **cycle_extra,
    }


def _active_voice(
    snapshot: dict[str, Any], instance_key: str, source_guid: str
) -> dict[str, Any] | None:
    matches = [
        item
        for item in snapshot["voices"]
        if item.get("instanceKey") == instance_key
        and item.get("source") == source_guid
    ]
    return min(matches, key=lambda item: int(item["voiceToken"])) if matches else None


def _target_routed_gain(
    runtime: _OracleRuntime,
    instance_key: str,
    target: dict[str, Any],
) -> float:
    """Read the target channel's authored group/channel fader product.

    ``FMOD_Channel_GetAudibility`` is unsuitable for a gain oracle when the
    same control also drives property-1 pitch: FMOD's audibility estimate is
    pitch/frequency dependent.  The product of the exact group ancestry and
    channel faders isolates property-0/property-4 gain automation.
    """

    runtime.core.FMOD_ChannelGroup_GetVolume.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_float),
    ]
    runtime.core.FMOD_ChannelGroup_GetVolume.restype = C.c_int
    runtime.core.FMOD_Channel_GetVolume.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_float),
    ]
    runtime.core.FMOD_Channel_GetVolume.restype = C.c_int
    record = runtime.instances[instance_key]
    event_group = C.c_void_p()
    runtime._check(
        runtime.studio.FMOD_Studio_EventInstance_GetChannelGroup(
            record["handle"], C.byref(event_group)
        ),
        "get turbo target event channel group",
    )
    pending: list[tuple[C.c_void_p, float]] = [(event_group, 1.0)]
    seen: set[int] = set()
    gains: list[float] = []
    while pending:
        group, ancestor_gain = pending.pop()
        address = int(group.value or 0)
        if not address or address in seen:
            continue
        seen.add(address)
        group_volume = C.c_float()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetVolume(
                group, C.byref(group_volume)
            ),
            "read turbo target group volume",
        )
        routed_gain = ancestor_gain * float(group_volume.value)
        channel_count = C.c_int()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetNumChannels(
                group, C.byref(channel_count)
            ),
            "count turbo target group channels",
        )
        for index in range(channel_count.value):
            channel = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_ChannelGroup_GetChannel(
                    group, index, C.byref(channel)
                ),
                "get turbo target channel",
            )
            sound = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_Channel_GetCurrentSound(
                    channel, C.byref(sound)
                ),
                "get turbo target current sound",
            )
            name_buffer = C.create_string_buffer(1024)
            runtime._check(
                runtime.core.FMOD_Sound_GetName(
                    sound, name_buffer, len(name_buffer)
                ),
                "read turbo target current sound identity",
            )
            runtime_name = name_buffer.value.decode("utf-8", "replace")
            if (
                runtime.runtime_name_to_source.get(runtime_name)
                != target["sourceGuid"]
            ):
                continue
            channel_volume = C.c_float()
            runtime._check(
                runtime.core.FMOD_Channel_GetVolume(
                    channel, C.byref(channel_volume)
                ),
                "read turbo target channel volume",
            )
            gains.append(routed_gain * float(channel_volume.value))
        child_count = C.c_int()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetNumGroups(
                group, C.byref(child_count)
            ),
            "count turbo target child groups",
        )
        for index in range(child_count.value):
            child = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_ChannelGroup_GetGroup(
                    group, index, C.byref(child)
                ),
                "get turbo target child group",
            )
            pending.append((child, routed_gain))
    if not gains:
        raise AssertionError("target routed-gain channel is absent")
    if max(gains) - min(gains) > 1.0e-6:
        raise AssertionError("overlapping target channels disagree on routed gain")
    return gains[0]


def _start_target_instance(
    runtime: _OracleRuntime,
    target: dict[str, Any],
    parameters: dict[str, float],
    *,
    maximum_attempts: int = 128,
) -> tuple[str, dict[str, Any]]:
    source_guid = target["sourceGuid"]
    for attempt in range(maximum_attempts):
        key = f"take{attempt:03d}"
        runtime.create_instance(key, target["eventPath"], parameters=parameters)
        runtime.start(key)
        runtime.flush(f"start-{attempt}")
        runtime.update(f"start-render-{attempt}")
        snapshot = runtime.snapshot()
        voice = _active_voice(snapshot, key, source_guid)
        if voice is not None:
            return key, voice
        runtime.stop_release(key)
    raise AssertionError(
        f"target source {source_guid} was not selected in {maximum_attempts} takes"
    )


def _gain_probe_value(
    derived: dict[str, Any], parameter: str, capture_value: float
) -> float | None:
    curve = derived["controlGainCurves"][parameter]
    candidates = [
        (abs(float(x) - capture_value), -float(y), float(x))
        for x, y in curve
        if abs(float(x) - capture_value) > 1.0e-5 and float(y) >= 0.1
    ]
    if not candidates:
        return None
    # Prefer a materially different point while retaining measurable gain.
    return max(candidates, key=lambda item: (item[0], item[1]))[2]


def _curve_and_schedule_probe(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    capture_verification: dict[str, Any],
    output_root: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_guid = target["sourceGuid"]
    event_path = target["eventPath"]
    scratch = output_root / "runtime-scratch" / f"{source_guid}.wav"
    scratch.parent.mkdir(parents=True, exist_ok=True)
    identity_map = _event_runtime_identity_map(target)
    capture_values = {
        str(name): float(value)
        for name, value in derived["captureParameterValues"].items()
    }
    gain_errors: list[float] = []
    pitch_errors: list[float] = []
    observations: list[dict[str, Any]] = []
    target_pcm_silent = (
        capture_verification.get("audibilityDisposition")
        == "AUTHORED_TARGET_SILENT"
        and capture_verification.get("allPcmSamplesZero") is True
    )
    with _OracleRuntime(
        assetto_root,
        bank,
        identity_map,
        scratch,
        max_channels=2048,
        event_paths=(event_path,),
    ) as runtime:
        key, initial_voice = _start_target_instance(
            runtime, target, capture_values
        )
        token = int(initial_voice["voiceToken"])
        capture_audibility = float(initial_voice["audibility"])
        capture_routed_gain = _target_routed_gain(runtime, key, target)
        observations.append(
            {
                "kind": "CAPTURE_OPERATING_POINT",
                "voiceToken": token,
                "audibility": capture_audibility,
                "routedGain": capture_routed_gain,
                "parameters": copy.deepcopy(capture_values),
            }
        )

        current_key = key
        routed_gain_restart_count = 0

        def restart_at_capture(stage: str) -> None:
            nonlocal current_key, routed_gain_restart_count
            if current_key in runtime.instances:
                runtime.stop_release(current_key)
            current_key, _voice = _start_target_instance(
                runtime, target, capture_values
            )
            routed_gain_restart_count += 1
            for control_name, control_value in sorted(capture_values.items()):
                runtime.set_parameter(current_key, control_name, control_value)
            runtime.flush(f"{stage}-capture-controls")
            runtime.update(f"{stage}-capture-controls")

        routed_gain_probe_serial = 0

        def routed_gain_at(parameter: str, control_value: float) -> float:
            nonlocal routed_gain_probe_serial
            routed_gain_probe_serial += 1
            runtime.set_parameter(current_key, parameter, _float32(control_value))
            runtime.flush(
                f"routed-gain-{parameter}-{routed_gain_probe_serial}-set"
            )
            runtime.update(
                f"routed-gain-{parameter}-{routed_gain_probe_serial}-render"
            )
            voice = _active_voice(runtime.snapshot(), current_key, source_guid)
            if voice is None:
                restart_at_capture(
                    f"routed-gain-{parameter}-{routed_gain_probe_serial}-restart"
                )
                runtime.set_parameter(
                    current_key, parameter, _float32(control_value)
                )
                runtime.flush(
                    f"routed-gain-{parameter}-{routed_gain_probe_serial}-retry-set"
                )
                runtime.update(
                    f"routed-gain-{parameter}-{routed_gain_probe_serial}-retry-render"
                )
                voice = _active_voice(
                    runtime.snapshot(), current_key, source_guid
                )
                if voice is None:
                    raise AssertionError(
                        f"target voice cannot survive {parameter} routed-gain probe"
                    )
            return _target_routed_gain(runtime, current_key, target)

        verified_control_gain_curves: dict[str, dict[str, Any]] = {}
        static_silent_axes = set(
            derived["normalization"].get("staticallySilentControlAxes", [])
        )
        for parameter, static_curve in sorted(
            derived["controlGainCurves"].items()
        ):
            parameter_identity = derived["controlParameters"][parameter]
            domain = tuple(float(value) for value in parameter_identity["domain"])
            capture_value = float(capture_values[parameter])
            for control_name, control_value in sorted(capture_values.items()):
                runtime.set_parameter(current_key, control_name, control_value)
            runtime.flush(f"routed-gain-{parameter}-axis-reset")
            runtime.update(f"routed-gain-{parameter}-axis-reset")
            if _active_voice(runtime.snapshot(), current_key, source_guid) is None:
                restart_at_capture(f"routed-gain-{parameter}-axis-restart")
            axis_capture_routed_gain = _target_routed_gain(
                runtime, current_key, target
            )
            if abs(axis_capture_routed_gain - capture_routed_gain) > max(
                1.0e-8, abs(capture_routed_gain) * 2.0e-6
            ):
                raise AssertionError(
                    f"{parameter} capture routed gain is not repeatable"
                )

            knots = {domain[0], domain[1], capture_value}
            for controller in derived["controllers"]:
                if (
                    controller["parameter"] == parameter
                    and int(controller["propertyIndex"]) in {0, 4}
                ):
                    points = controller["points"]
                    knots.update(
                        min(domain[1], max(domain[0], float(point["x"])))
                        for point in points
                    )
                    # Exact zero-shape FMOD 1.08 routable-fader segments can
                    # expose quantized transitions.  Seed their four observed
                    # quarter-step decision boundaries; adaptive binary32
                    # refinement still measures and proves every transition.
                    for left, right in zip(points, points[1:]):
                        if abs(float(left["shape"])) <= 1.0e-6:
                            left_x = float(left["x"])
                            right_x = float(right["x"])
                            for fraction in (0.125, 0.375, 0.625, 0.875):
                                knots.add(
                                    min(
                                        domain[1],
                                        max(
                                            domain[0],
                                            left_x
                                            + ((right_x - left_x) * fraction),
                                        ),
                                    )
                                )

            axis_probe_start = routed_gain_probe_serial
            axis_restart_start = routed_gain_restart_count
            if parameter in static_silent_axes or axis_capture_routed_gain == 0.0:
                if axis_capture_routed_gain == 0.0 and not target_pcm_silent:
                    raise AssertionError(
                        f"audible target PCM has zero {parameter} capture routed gain"
                    )

                def evaluate_absolute(value: float) -> float:
                    return routed_gain_at(parameter, value)

                absolute_curve, absolute_transitions, maximum_smooth_error = (
                    _adaptive_empirical_curve(evaluate_absolute, knots)
                )
                if (
                    any(point[1] > 1.0e-12 for point in absolute_curve)
                    or absolute_transitions
                ):
                    raise AssertionError(
                        f"zero {parameter} capture has an audible routed operating point"
                    )
                verified_curve = [[domain[0], 0.0], [domain[1], 0.0]]
                transitions: list[dict[str, float]] = []
                normalized_capture_gain = 0.0
            else:
                def evaluate_relative(value: float) -> float:
                    return (
                        routed_gain_at(parameter, value)
                        / axis_capture_routed_gain
                    )

                verified_curve, transitions, maximum_smooth_error = (
                    _adaptive_empirical_curve(evaluate_relative, knots)
                )
                normalized_capture_gain = _linear_curve_value(
                    verified_curve, capture_value
                )
                if (
                    abs(normalized_capture_gain - 1.0)
                    > ROUTED_GAIN_ABSOLUTE_TOLERANCE
                ):
                    raise AssertionError(
                        f"{parameter} empirical curve is not normalized at capture"
                    )

            maximum_transition_width = max(
                (item["width"] for item in transitions), default=0.0
            )
            verified_control_gain_curves[parameter] = {
                "control": parameter,
                "domain": [domain[0], domain[1]],
                "captureValue": capture_value,
                "captureRoutedGain": axis_capture_routed_gain,
                "captureCurveValue": normalized_capture_gain,
                "curve": verified_curve,
                "oracleProbeCount": (
                    routed_gain_probe_serial - axis_probe_start
                ),
                "oracleRestartCount": (
                    routed_gain_restart_count - axis_restart_start
                ),
                "absoluteLinearGainTolerance": (
                    ROUTED_GAIN_ABSOLUTE_TOLERANCE
                ),
                "maximumObservedAbsoluteLinearGainErrorOutsideTransitions": (
                    maximum_smooth_error
                ),
                "transitionIntervals": transitions,
                "maximumTransitionDomainWidth": maximum_transition_width,
                "float32ControlInputs": True,
                "runtimeMeasurement": (
                    "TARGET_CHANNEL_GROUP_ANCESTRY_TIMES_CHANNEL_FADER"
                ),
            }

            # Retain the static parser projection only as a diagnostic.  The
            # certified manifest uses the source-bound empirical curve below.
            probe_value = _gain_probe_value(
                {"controlGainCurves": {parameter: verified_curve}},
                parameter,
                capture_value,
            )
            if probe_value is not None and axis_capture_routed_gain > 0.0:
                observed_gain = routed_gain_at(parameter, probe_value)
                observed_ratio = observed_gain / axis_capture_routed_gain
                verified_expected = _linear_curve_value(
                    verified_curve, probe_value
                )
                static_expected = _linear_curve_value(
                    static_curve, probe_value
                )
                if observed_ratio <= 0.0 and verified_expected <= 0.0:
                    error_db = 0.0
                else:
                    error_db = abs(
                        20.0
                        * math.log10(
                            max(observed_ratio, 1.0e-12)
                            / max(verified_expected, 1.0e-12)
                        )
                    )
                gain_errors.append(error_db)
                observations.append(
                    {
                        "kind": "SOURCE_BOUND_ROUTED_GAIN_PARAMETER_MOTION",
                        "parameter": parameter,
                        "value": probe_value,
                        "staticProjectionRelativeGain": static_expected,
                        "verifiedRelativeGain": verified_expected,
                        "observedRelativeRoutedGain": observed_ratio,
                        "verifiedErrorDb": error_db,
                    }
                )

        # Give live-pitch and scheduling checks a fresh source voice and phase;
        # empirical gain sampling is allowed to consume/restart short leaves.
        if current_key in runtime.instances:
            runtime.stop_release(current_key)
        key, _fresh_voice = _start_target_instance(
            runtime, target, capture_values
        )

        for pitch in derived["pitchAutomation"]:
            parameter = str(pitch["parameter"])
            curve = pitch["playbackRateCurve"]
            capture_value = capture_values[parameter]
            probe_candidates = [
                (abs(float(x) - capture_value), float(x), float(rate))
                for x, rate in curve
                if abs(float(x) - capture_value) > 0.05
                and 0.25 <= float(rate) <= 4.0
                and _linear_curve_value(
                    verified_control_gain_curves[parameter]["curve"],
                    float(x),
                )
                >= 0.05
            ]
            if not probe_candidates:
                continue
            _distance, probe_value, expected_rate = max(probe_candidates)

            def position_after(updates: int) -> tuple[int, int]:
                start_voice = _active_voice(runtime.snapshot(), key, source_guid)
                if start_voice is None:
                    raise AssertionError("target ended before live pitch probe")
                start = int(start_voice["pcmPosition"])
                for index in range(updates):
                    runtime.update(f"pitch-run-{parameter}-{index}")
                end_voice = _active_voice(runtime.snapshot(), key, source_guid)
                if end_voice is None:
                    raise AssertionError("target ended during live pitch probe")
                return start, int(end_voice["pcmPosition"])

            runtime.set_parameter(key, parameter, capture_value)
            runtime.flush(f"pitch-capture-{parameter}")
            runtime.update(f"pitch-capture-settle-{parameter}")
            capture_start, capture_end = position_after(24)
            capture_delta = capture_end - capture_start
            runtime.set_parameter(key, parameter, probe_value)
            runtime.flush(f"pitch-probe-{parameter}")
            runtime.update(f"pitch-probe-settle-{parameter}")
            probe_start, probe_end = position_after(24)
            probe_delta = probe_end - probe_start
            if capture_delta <= 0 or probe_delta <= 0:
                raise AssertionError("live pitch probe did not advance source PCM")
            observed_rate = probe_delta / capture_delta
            cents = abs(1200.0 * math.log2(observed_rate / expected_rate))
            pitch_errors.append(cents)
            observations.append(
                {
                    "kind": "LIVE_PROPERTY_ONE_ACTIVE_VOICE_RATE",
                    "parameter": parameter,
                    "captureValue": capture_value,
                    "probeValue": probe_value,
                    "capturePcmDelta": capture_delta,
                    "probePcmDelta": probe_delta,
                    "expectedRelativeRate": expected_rate,
                    "observedRelativeRate": observed_rate,
                    "errorCents": cents,
                }
            )
            runtime.set_parameter(key, parameter, capture_value)
            runtime.flush(f"pitch-reset-{parameter}")
            runtime.update(f"pitch-reset-{parameter}")

        mode = derived["programMode"]
        callbacks_before_lifecycle = list(runtime.callbacks)
        if mode == "BOOST_RELEASE_REGION_ONE_SHOT":
            region = derived["parameterRegion"]
            outside = float(region["parameterDomain"][1])
            inside = float(derived["captureBoost"])
            runtime.set_parameter(key, "boost", outside)
            runtime.flush("release-exit")
            runtime.update("release-exit")
            stopped_on_exit = any(
                item["kind"] == "stopped"
                and item["category"] == key
                for item in runtime.callbacks[len(callbacks_before_lifecycle) :]
            )
            target_played_before = sum(
                item["kind"] == "played" and item["source"] == source_guid
                for item in runtime.callbacks
            )
            maximum_active_target = 1
            maximum_active_program = 1
            for cycle in range(160):
                runtime.set_parameter(key, "boost", inside)
                runtime.flush(f"release-entry-{cycle}")
                runtime.update(f"release-entry-{cycle}")
                target_played = sum(
                    item["kind"] == "played" and item["source"] == source_guid
                    for item in runtime.callbacks
                )
                snapshot = runtime.snapshot()
                active = sum(
                    item["instanceKey"] == key and item["source"] == source_guid
                    for item in snapshot["voices"]
                )
                active_program = sum(
                    item["instanceKey"] == key for item in snapshot["voices"]
                )
                maximum_active_target = max(maximum_active_target, active)
                maximum_active_program = max(
                    maximum_active_program, active_program
                )
                if target_played > target_played_before:
                    break
                runtime.set_parameter(key, "boost", outside)
                runtime.flush(f"release-rearm-{cycle}")
                runtime.update(f"release-rearm-{cycle}")
            else:
                raise AssertionError("target boost-release leaf did not re-enter")
            scheduling = {
                "accepted": not stopped_on_exit,
                "programMode": mode,
                "eventStartInsideScheduled": True,
                "eventStartOutsideThenEntrySchedules": True,
                "decreasingMaximumEdgeReentryScheduled": True,
                "activeVoiceExitBehaviorVerified": not stopped_on_exit,
                # A SmartRandom root can deliberately choose a different
                # child on re-entry.  Overlap is a program-root scheduling
                # property, so any second child under the isolated root is
                # the correct proof; requiring the same target leaf to repeat
                # can outlive a short source's natural PCM duration.
                "overlapBehaviorVerified": maximum_active_program >= 2,
                "overlapEvidenceScope": "ISOLATED_PROGRAM_ROOT_ANY_CHILD",
                "maximumObservedActiveTargetVoices": maximum_active_target,
                "maximumObservedActiveProgramVoices": maximum_active_program,
                "contract": "START_INSIDE_OR_OUTSIDE_ARMED_EXIT_LETS_FINISH_REENTRY_OVERLAPS",
            }
        elif mode == "TIMELINE_PERIODIC_ONE_SHOT":
            period = int(derived["timelineGeometry"]["repeatPeriodTicks"])
            played_before = sum(
                item["kind"] == "played" and item["source"] == source_guid
                for item in runtime.callbacks
            )
            for index in range(math.ceil(period / DSP_BUFFER_FRAMES) + 4):
                runtime.update(f"timeline-period-{index}")
                played = sum(
                    item["kind"] == "played" and item["source"] == source_guid
                    for item in runtime.callbacks
                )
                if played > played_before:
                    break
            else:
                raise AssertionError("timeline turbo leaf did not repeat at its period")
            scheduling = {
                "accepted": True,
                "programMode": mode,
                "eventStartScheduled": True,
                "secondTimelineScheduleObserved": True,
                "activeVoiceExitBehaviorVerified": True,
                "overlapBehaviorVerified": True,
                "periodTicks": period,
                "contract": "PERSISTENT_EVENT_TIMELINE_PERIODIC_ONE_SHOT",
            }
        elif mode == "PARAMETER_SHEET_EVENT_START_ONE_SHOT":
            played_before = sum(
                item["kind"] == "played" and item["source"] == source_guid
                for item in runtime.callbacks
            )
            for value in (1.0, 0.0, 0.5):
                runtime.set_parameter(key, "boost", value)
                runtime.flush("full-domain-motion")
                runtime.update("full-domain-motion")
            played_after = sum(
                item["kind"] == "played" and item["source"] == source_guid
                for item in runtime.callbacks
            )
            scheduling = {
                "accepted": played_after == played_before,
                "programMode": mode,
                "eventStartScheduled": True,
                "parameterMotionDidNotRetrigger": played_after == played_before,
                "activeVoiceExitBehaviorVerified": True,
                "overlapBehaviorVerified": True,
                "contract": "ONE_VOICE_PER_EVENT_START_NO_PARAMETER_REENTRY",
            }
        else:
            raise AssertionError(f"unknown turbo program mode: {mode}")
        if not scheduling["accepted"]:
            raise AssertionError(f"turbo scheduling gate failed for {source_guid}")
        curve = {
            "accepted": True,
            "oracleVersion": TURBO_CONTROL_GAIN_ORACLE_VERSION,
            "maximumGainErrorDb": max(gain_errors, default=0.0),
            "maximumPitchErrorCents": max(pitch_errors, default=0.0),
            "gainProbeCount": len(gain_errors),
            "pitchProbeCount": len(pitch_errors),
            "verifiedControlGainCurves": verified_control_gain_curves,
            "observations": observations,
        }
        runtime._raise_callback_errors()
    scratch.unlink(missing_ok=True)
    return curve, scheduling


def _priority_rows(proof: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    if proof.get("schema") != PRIORITY_SCHEMA or proof.get("result") != "PASS_SOURCE_BOUND_COMPLETE":
        raise ValueError("priority proof is not a complete source-bound oracle")
    return {
        (str(item["familyId"]), _guid(item["sourceGuid"])): item
        for item in proof["sourceObservations"]
    }


def probe_catalog(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    priority_path: Path,
    output_root: Path,
    *,
    selected_source_guids: Iterable[str] = (),
    limit: int | None = None,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    summary = _load_json(graph_root / "summary.json", GRAPH_SUMMARY_SCHEMA)
    classification = _load_json(classification_path.resolve(strict=True), CLASSIFIER_SCHEMA)
    priority_proof = _load_json(priority_path.resolve(strict=True))
    priority = _priority_rows(priority_proof)
    summary_families = {
        str(item["familyId"]): item for item in summary["families"]
    }
    wanted = {_guid(value) for value in selected_source_guids if _guid(value)}
    rows = [
        row
        for row in classification["sourceDecisions"]
        if row.get("policy") == "allowCandidate"
        and row.get("role") == "TURBO_TRANSIENT_CANDIDATE"
        and (not wanted or _guid(row.get("sourceGuid")) in wanted)
    ]
    rows.sort(key=lambda row: (str(row["familyId"]), _guid(row["sourceGuid"])))
    if limit is not None:
        rows = rows[:limit]
    if not rows:
        raise ValueError("no turbo-transient rows selected")

    partial_path = output_root / "partial.json"
    completed: dict[str, dict[str, Any]] = {}
    if partial_path.is_file():
        partial = _load_json(partial_path)
        if (
            partial.get("classificationSha256") != _sha256(classification_path)
            or partial.get("priorityProofSha256") != _sha256(priority_path)
        ):
            raise ValueError("turbo partial belongs to different inputs")
        completed = {
            _guid(key): value
            for key, value in partial.get("sourceVerifications", {}).items()
            if isinstance(value, dict)
        }

    renderer = SilentFmodReferenceRenderer(root, dsp_buffer_frames=DSP_BUFFER_FRAMES)
    bank_records: dict[str, dict[str, Any]] = {}
    pending_pcm_upgrades = 0
    for index, row in enumerate(rows, 1):
        source_guid = _guid(row["sourceGuid"])
        target = _target(graph_root, summary_families, row)
        derived = derive_manifest_turbo_transient_source(target["graph"], row)
        derived_sha = _canonical_sha(derived)
        old = completed.get(source_guid)
        old_curve_is_current = (
            old is not None
            and old.get("derivedSourceSha256") == derived_sha
            and isinstance(old.get("curveVerification"), dict)
            and old["curveVerification"].get("oracleVersion")
            == TURBO_CONTROL_GAIN_ORACLE_VERSION
        )
        if (
            old_curve_is_current
            and isinstance(old.get("capture"), dict)
            and old["capture"].get("oracleVersion")
            != TURBO_PCM_CAPTURE_ORACLE_VERSION
        ):
            # Upgrade the already source-bound independent renders through the
            # stricter DSP-block scheduling normalizer without replaying the
            # expensive adaptive routed-gain oracle.  The old payload's
            # derivation, bank isolation, runtime identity, scheduling, curve,
            # and priority proofs remain hash-bound below.
            installed_bank = root / str(target["family"]["bankPath"])
            installed_sha = _sha256(installed_bank)
            if installed_sha != (old.get("isolation") or {}).get(
                "sourceBankSha256"
            ):
                raise AssertionError(
                    f"turbo source bank changed during PCM proof upgrade: {source_guid}"
                )
            migrated = copy.deepcopy(old)
            migrated["capture"] = _capture_pcm(
                renderer,
                # Trigger-chance isolation never changes the embedded FSB5
                # payload.  The installed bank is therefore the byte-identical
                # PCM boundary oracle and avoids rebuilding/hashing a temporary
                # isolated copy during this one-time proof migration.
                installed_bank,
                target,
                derived,
                output_root,
                render=False,
            )
            migrated.pop("verificationPayloadSha256", None)
            migrated["verificationPayloadSha256"] = _canonical_sha(migrated)
            certify_manifest_turbo_transient_source(derived, migrated)
            completed[source_guid] = migrated
            pending_pcm_upgrades += 1
            if pending_pcm_upgrades % 10 == 0:
                _write_canonical(
                    partial_path,
                    {
                        "schema": SCHEMA + "-partial",
                        "classificationSha256": _sha256(classification_path),
                        "priorityProofSha256": _sha256(priority_path),
                        "sourceVerifications": dict(sorted(completed.items())),
                    },
                )
            print(
                f"[{index}/{len(rows)}] upgraded turbo PCM proof {source_guid}",
                flush=True,
            )
            continue
        if (
            old_curve_is_current
            and isinstance(old.get("capture"), dict)
            and old["capture"].get("oracleVersion")
            == TURBO_PCM_CAPTURE_ORACLE_VERSION
        ):
            # A matching derivation hash alone is not enough: the first oracle
            # revision sampled only one static-curve point and missed a real
            # FMOD routable-fader interpolation mode.  Reuse only a payload
            # which the current fail-closed certifier accepts in full.
            certify_manifest_turbo_transient_source(derived, old)
            print(f"[{index}/{len(rows)}] reuse turbo {source_guid}", flush=True)
            continue
        installed_bank = root / str(target["family"]["bankPath"])
        before = _sha256(installed_bank)
        runtime_bank, isolation = _runtime_bank(
            root, target, derived, output_root
        )
        pcm = _capture_pcm(renderer, runtime_bank, target, derived, output_root)
        curve, scheduling = _curve_and_schedule_probe(
            root, runtime_bank, target, derived, pcm, output_root
        )
        priority_row = priority.get((target["familyId"], source_guid))
        if priority_row is None:
            raise AssertionError(f"priority proof is absent for {source_guid}")
        software_priority = int(priority_row["softwareChannelPriority"])
        payload = {
            "schema": TURBO_TRANSIENT_VERIFICATION_SCHEMA,
            "familyId": target["familyId"],
            "sourceGuid": source_guid,
            "eventPath": derived["eventPath"],
            "programMode": derived["programMode"],
            "programPlacementRootInstrumentGuid": derived[
                "programPlacementRootInstrumentGuid"
            ],
            "derivedSourceSha256": derived_sha,
            "renderer": {
                "runtime": "FMOD Studio API 1.08.12",
                "mode": "WAVWRITER_NRT",
                "sampleRateHz": SAMPLE_RATE_HZ,
                "channels": CHANNELS,
                "sampleFormat": "signedPcm16LittleEndian",
                "dspBufferFrames": DSP_BUFFER_FRAMES,
                "audioDeviceOpened": False,
                "targetOnly": True,
            },
            "runtimeIdentity": {
                "usedOnlyForCallbackJoin": True,
                "sha256": target["runtimeIdentitySha256"],
                "sampleNameEmitted": False,
            },
            "isolation": isolation,
            "capture": pcm,
            "curveVerification": curve,
            "schedulingVerification": scheduling,
            "voicePolicy": {
                "sourceBoundChannelObserved": True,
                "softwareChannelPriority": software_priority,
                "priorityOracleSchema": PRIORITY_SCHEMA,
                "priorityOracleProofSha256": _sha256(priority_path),
                "priorityObservation": priority_row,
            },
        }
        payload["verificationPayloadSha256"] = _canonical_sha(payload)
        certified = certify_manifest_turbo_transient_source(derived, payload)
        if certified["fidelity"]["exactnessClaim"] is not True:
            raise AssertionError(f"turbo source did not certify: {source_guid}")
        completed[source_guid] = payload
        after = _sha256(installed_bank)
        bank_records[target["familyId"]] = {
            "familyId": target["familyId"],
            "sha256Before": before,
            "sha256After": after,
            "unchanged": before == after,
        }
        if after != before:
            raise AssertionError(f"installed bank changed: {installed_bank}")
        _write_canonical(
            partial_path,
            {
                "schema": SCHEMA + "-partial",
                "classificationSha256": _sha256(classification_path),
                "priorityProofSha256": _sha256(priority_path),
                "sourceVerifications": dict(sorted(completed.items())),
            },
        )
        print(
            f"[{index}/{len(rows)}] certified turbo {source_guid} "
            f"mode={derived['programMode']} pcm={pcm['audibilityDisposition']}",
            flush=True,
        )

    if pending_pcm_upgrades % 10:
        _write_canonical(
            partial_path,
            {
                "schema": SCHEMA + "-partial",
                "classificationSha256": _sha256(classification_path),
                "priorityProofSha256": _sha256(priority_path),
                "sourceVerifications": dict(sorted(completed.items())),
            },
        )

    selected_ids = {_guid(row["sourceGuid"]) for row in rows}
    verifications = [completed[source] for source in sorted(selected_ids)]
    if len(verifications) != len(rows):
        raise AssertionError("selected turbo verification coverage is incomplete")
    mode_counts = Counter(item["programMode"] for item in verifications)
    disposition_counts = Counter(
        item["capture"]["audibilityDisposition"] for item in verifications
    )
    priority_counts = Counter(
        int(item["voicePolicy"]["softwareChannelPriority"])
        for item in verifications
    )
    gain_candidates = [
        (
            float(point[1]),
            str(item["familyId"]),
            _guid(item["sourceGuid"]),
            str(control),
            float(point[0]),
            str(item["verificationPayloadSha256"]),
        )
        for item in verifications
        for control, verification in item["curveVerification"][
            "verifiedControlGainCurves"
        ].items()
        for point in verification["curve"]
    ]
    maximum_gain = max(gain_candidates)
    selected_family_ids = sorted({str(item["familyId"]) for item in verifications})
    complete_bank_records: list[dict[str, Any]] = []
    for family_id in selected_family_ids:
        family = summary_families[family_id]
        installed_bank = root / str(family["bankPath"])
        installed_sha = _sha256(installed_bank)
        source_hashes = {
            str(item["isolation"]["sourceBankSha256"])
            for item in verifications
            if str(item["familyId"]) == family_id
        }
        if source_hashes != {installed_sha}:
            raise AssertionError(
                f"installed turbo source bank identity changed: {installed_bank}"
            )
        complete_bank_records.append(
            {
                "familyId": family_id,
                "sha256Before": installed_sha,
                "sha256After": installed_sha,
                "unchanged": True,
            }
        )
    full_catalog = not wanted and limit is None
    if full_catalog and (
        len(verifications) != 171
        or len({item["familyId"] for item in verifications}) != 59
        or mode_counts
        != {
            "BOOST_RELEASE_REGION_ONE_SHOT": 143,
            "TIMELINE_PERIODIC_ONE_SHOT": 25,
            "PARAMETER_SHEET_EVENT_START_ONE_SHOT": 3,
        }
    ):
        raise AssertionError("official turbo catalog counts changed")
    return {
        "schema": SCHEMA,
        "basis": {
            "runtime": "FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "sampleNamesUsedForSemantics": False,
            "sampleNamesUsedOnlyForRuntimeIdentityJoin": True,
            "installedBanksModified": False,
            "targetIsolation": "other reachable waveform trigger chances patched to zero in temporary copies",
        },
        "inputs": {
            "classificationSha256": _sha256(classification_path),
            "priorityProofSha256": _sha256(priority_path),
        },
        "counts": {
            "sources": len(verifications),
            "families": len({item["familyId"] for item in verifications}),
            "programModes": dict(sorted(mode_counts.items())),
            "pcmDispositions": dict(sorted(disposition_counts.items())),
            "softwareChannelPriorities": {
                str(key): value for key, value in sorted(priority_counts.items())
            },
        },
        "catalogBounds": {
            "maximumCaptureRelativeControlGain": {
                "value": maximum_gain[0],
                "familyId": maximum_gain[1],
                "sourceGuid": maximum_gain[2],
                "control": maximum_gain[3],
                "controlValue": maximum_gain[4],
                "sourceVerificationPayloadSha256": maximum_gain[5],
                "basis": "MAXIMUM_VERTEX_OF_ALL_SOURCE_BOUND_EMPIRICAL_CONTROL_GAIN_CURVES",
            }
        },
        "sourceVerifications": verifications,
        "sourceBanks": complete_bank_records,
        "result": (
            "PASS_SOURCE_BOUND_COMPLETE" if full_catalog else "PASS_SELECTED_SOURCES"
        ),
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument("--classification", type=Path, default=DEFAULT_CLASSIFICATION)
    parser.add_argument("--priority-proof", type=Path, default=DEFAULT_PRIORITY_PROOF)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--source-guid", action="append", default=[])
    parser.add_argument("--limit", type=int)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    report = probe_catalog(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        args.priority_proof,
        args.output_root,
        selected_source_guids=args.source_guid,
        limit=args.limit,
    )
    proof = args.output_root.resolve() / "proof.json"
    _write_canonical(proof, report)
    print(
        json.dumps(
            {
                "result": report["result"],
                "proof": str(proof),
                "counts": report["counts"],
                "audioDeviceOpened": False,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
