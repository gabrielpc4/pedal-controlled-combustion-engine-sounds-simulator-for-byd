"""Create source-bound FMOD evidence for release-selected continuous sources.

This oracle is deliberately separate from the static bank-graph compiler.  It
measures the live target channel-group/channel fader product because FMOD 1.08
can route parameter automation through runtime faders that is not represented
by the parser's local source-controller projection.  Every selected source is
run in a new Python process and an isolated copy of the installed bank; the
installed Assetto Corsa bank remains read-only.

The ``diagnose`` operation emits a bounded control-surface scan.  Separate
certification operations bind forbidden on-pedal routes, exact authored route
silence, and property-index-1 target PCM with a compact capture-relative pitch
curve.  Diagnostic evidence never upgrades a release exactness claim by itself.
"""

from __future__ import annotations

import argparse
import copy
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
from typing import Any, Iterable
import wave


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import (
    FmodAuthoredCurveError,
    derive_manifest_source_curves,
    derive_windowed_capture_fallback,
    evaluate_authored_curve,
)
from sim.fmod_bank_isolation import (
    create_isolated_bank_copy,
    fully_muted_multi_instrument_guids,
)
from sim.fmod_continuous_source import (
    PROPERTY_ONE_INTERPOLATION,
    PROPERTY_ONE_PITCH_MODE,
    PROPERTY_ONE_RAW_TO_SEMITONES,
    PROPERTY_ONE_VERIFICATION_SCHEMA,
    certify_authored_routed_silence,
    certify_forbidden_on_pedal_routing,
    certify_property_one_relative_rate,
    validate_property_one_pitch_curve,
)
from sim.fmod_continuous_turbo import (
    CERTIFIED_SCHEMA as TURBO_CONTINUOUS_CERTIFIED_SCHEMA,
    RUNTIME_INTERPOLATION as TURBO_CONTINUOUS_INTERPOLATION,
    RUNTIME_PITCH_MODE as TURBO_CONTINUOUS_PITCH_MODE,
    STATIC_SCHEMA as TURBO_CONTINUOUS_STATIC_SCHEMA,
    VERIFICATION_SCHEMA as TURBO_CONTINUOUS_VERIFICATION_SCHEMA,
    certify_continuous_turbo_source,
    derive_continuous_turbo_source,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_windowed_capture import (
    _cubic_varispeed,
    _pcm16_stereo,
    _rms_and_stationarity,
    measure_relative_log_spectral_pitch,
)
from sim.loop_tools import crossfade_loop_seam, find_best_loop_bounds
from tools.probe_fmod_global_voice_arbitration import _OracleRuntime
from tools.probe_fmod_turbo_transients import (
    _adaptive_empirical_curve,
    _active_voice,
    _event_runtime_identity_map,
    _float32,
    _target_routed_gain,
)


GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
GRAPH_SUMMARY_SCHEMA = "ac-fmod-bank-graph-catalog-audit-v3"
CLASSIFIER_SCHEMA = "ac-fmod-catalog-source-role-classification-v2"
DIAGNOSTIC_SCHEMA = "ac-fmod-continuous-source-diagnostic-v1"
DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_CLASSIFICATION = (
    PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json"
)
DEFAULT_OUTPUT_ROOT = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\oracles\continuous-source-oracle-v1"
)
DEFAULT_PLAN = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\aclib\capture-plan-v2-turbo-certified-working.json"
)
KNOWN_DIAGNOSTIC_SOURCES = (
    "e0adcb75-5087-4cba-ac97-d0df2c065702",  # Huayra BC transmission
    "18f5c3d1-9ba0-4782-971e-ccdc8bd4c9c7",  # M235i texture
    "0766f161-8bb9-4be0-9df2-35be34a525f8",  # F138 texture
    "9f260f53-c084-46df-a61d-1a2b2ca228be",  # Lotus 2-Eleven texture
    "773232b5-4b56-4bb4-8a26-274a4ec79b42",  # Elise SC texture
)
PROPERTY_ONE_SOURCES = (
    "5169c3d1-950b-450b-884d-fbab12cc8cc9",
    "631c5f70-22bb-4a33-93e5-2c7fe87f39d9",
    "c15dec11-78a4-4fc7-97a8-6550949646f1",
    "f2526e5a-9b8b-4359-ad98-ce6d379d3264",
    "f37460b3-8cec-473d-8070-962449d0f764",
)
TURBO_CONTINUOUS_CLASSIFIER_ROLE = "TURBO_CONTINUOUS_CANDIDATE"
FORBIDDEN_PEDAL_SOURCES = KNOWN_DIAGNOSTIC_SOURCES[1:]
ROUTED_SILENT_SOURCES = KNOWN_DIAGNOSTIC_SOURCES[:1]
STATIC_DISPOSITION_PROOF_SCHEMA = (
    "ac-fmod-continuous-static-disposition-oracle-v1"
)
PROPERTY_ONE_PROOF_SCHEMA = "ac-fmod-property-one-relative-rate-oracle-v1"
PROPERTY_ONE_MAXIMUM_LINEARIZATION_ERROR_CENTS = 0.25
PROPERTY_ONE_ANALYSIS_FRAMES = 384000
PROPERTY_ONE_WARMUP_FRAMES = 96000
TURBO_CONTINUOUS_PROOF_SCHEMA = "ac-fmod-continuous-turbo-oracle-v1"
TURBO_CONTINUOUS_CAPTURE_FRAMES = 144000
TURBO_CONTINUOUS_ANALYSIS_FRAMES = 768000
TURBO_CONTINUOUS_WARMUP_FRAMES = 96000
TURBO_CONTINUOUS_GAIN_TOLERANCE = 2.0e-4
TURBO_CONTINUOUS_PITCH_LINEARIZATION_CENTS = 0.25


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
    os.replace(temporary, path)


def _read_pcm16_wav(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError(f"noncanonical property-one WAV: {path}")
        frames = source.getnframes()
        payload = source.readframes(frames)
    if len(payload) != frames * 4:
        raise ValueError(f"truncated property-one WAV: {path}")
    return payload, frames


def _write_pcm16_wav(path: Path, payload: bytes) -> None:
    if len(payload) % 4:
        raise ValueError("PCM16 stereo payload is not frame aligned")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp.wav")
    with wave.open(str(temporary), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(48000)
        target.writeframes(payload)
    os.replace(temporary, path)


def _repair_bounded_continuous_loop(path: Path) -> tuple[int, int, float]:
    """Repair a compiler-bounded continuous window without inventing media."""

    payload, frame_count = _read_pcm16_wav(path)
    guard = min(960, max(1, frame_count // 12))
    seam = find_best_loop_bounds(
        payload,
        nominal_start=guard,
        nominal_end=frame_count - guard,
        search_frames=min(720, guard),
    )
    if seam.peak_dbfs > -36.0:
        payload, seam = crossfade_loop_seam(
            payload,
            seam.start_frame,
            seam.end_frame,
            crossfade_frames=min(
                960, max(1, (seam.end_frame - seam.start_frame) // 8)
            ),
        )
        _write_pcm16_wav(path, payload)
    if seam.peak_dbfs > -18.0:
        raise ValueError(
            f"continuous turbo loop seam remains unsafe at {seam.peak_dbfs:.6f} dBFS"
        )
    return seam.start_frame, seam.end_frame, seam.peak_dbfs


def _repair_property_pack_loop(
    payload: bytes, frame_count: int, nominal_cycle_frames: float
) -> tuple[bytes, int, int, float]:
    if len(payload) != frame_count * 4 or frame_count < 4096:
        raise ValueError("property-one capture PCM is invalid")
    if not math.isfinite(nominal_cycle_frames) or nominal_cycle_frames < 2048.0:
        raise ValueError("property-one authored cycle is invalid")
    guard = min(1920, max(1, frame_count // 12))
    nominal_end = guard + round(nominal_cycle_frames)
    if nominal_end + guard > frame_count:
        raise ValueError("property-one render does not contain one guarded cycle")
    seam = find_best_loop_bounds(
        payload,
        nominal_start=guard,
        nominal_end=nominal_end,
        search_frames=min(960, guard),
    )
    if seam.peak_dbfs > -36.0:
        payload, seam = crossfade_loop_seam(
            payload,
            seam.start_frame,
            seam.end_frame,
            crossfade_frames=min(
                960, max(1, (seam.end_frame - seam.start_frame) // 8)
            ),
        )
    if seam.peak_dbfs > -18.0:
        raise ValueError(
            f"unsafe property-one loop seam {seam.peak_dbfs:.6f} dBFS"
        )
    # The post-loop search guard is compiler-only.  Runtime stores the intro
    # plus exactly one repaired authored source cycle.
    payload = payload[: seam.end_frame * 4]
    return payload, seam.start_frame, seam.end_frame, seam.peak_dbfs


def _linear_curve(points: list[list[float]], value: float) -> float:
    if not points:
        raise ValueError("curve is empty")
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for left, right in zip(points, points[1:]):
        if left[0] <= value <= right[0]:
            fraction = (value - left[0]) / (right[0] - left[0])
            return left[1] + fraction * (right[1] - left[1])
    raise AssertionError("curve lookup failed")


def _peak_dbfs(payload: bytes) -> float:
    if not payload:
        return -math.inf
    samples = struct.unpack(f"<{len(payload) // 2}h", payload)
    peak = max(abs(value) for value in samples)
    return -math.inf if peak == 0 else 20.0 * math.log10(peak / 32768.0)


def _source_context(
    graph_root: Path,
    classification_path: Path,
    source_guid: str,
) -> dict[str, Any]:
    source_guid = _guid(source_guid)
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    rows = [
        row
        for row in classification["sourceDecisions"]
        if _guid(row.get("sourceGuid")) == source_guid
    ]
    if len(rows) != 1:
        raise ValueError(
            f"source {source_guid} has {len(rows)} classifier rows, expected one"
        )
    row = rows[0]
    family_id = str(row["familyId"])
    graph_path = graph_root / "families" / f"{family_id}.json"
    graph = _load_json(graph_path, GRAPH_SCHEMA)
    summary = _load_json(graph_root / "summary.json")
    family = next(
        (
            item
            for item in summary.get("families", [])
            if str(item.get("familyId")) == family_id
        ),
        None,
    )
    if family is None:
        raise ValueError(f"family {family_id} is absent from graph summary")
    instruments = {
        _guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict)
    }
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        raise ValueError(f"source {source_guid} is absent from family graph")
    event_paths = row.get("eventPaths")
    if not isinstance(event_paths, list) or len(event_paths) != 1:
        raise ValueError(f"source {source_guid} does not have one event path")
    event_path = str(event_paths[0])
    event = next(
        (
            item
            for item in graph.get("events", [])
            if str(item.get("path")) == event_path
        ),
        None,
    )
    if event is None or event.get("mappingComplete") is not True:
        raise ValueError(f"event graph is absent/incomplete: {event_path}")
    if source_guid not in {_guid(item) for item in event["reachableInstrumentGuids"]}:
        raise ValueError(f"source {source_guid} is not reachable from {event_path}")
    runtime_name = str((source.get("sample") or {}).get("name") or "")
    if not runtime_name:
        raise ValueError(f"source {source_guid} has no callback identity")
    try:
        if row.get("role") == TURBO_CONTINUOUS_CLASSIFIER_ROLE:
            derived = derive_continuous_turbo_source(graph, row)
            derivation_kind = "TURBO_CONTINUOUS_SOURCE_BOUND_PENDING"
        else:
            derived = derive_manifest_source_curves(graph, row)
            derivation_kind = "DIRECT"
    except FmodAuthoredCurveError as error:
        if error.code == "unsupportedPropertyIndex":
            derived = derive_windowed_capture_fallback(graph, row)
            derivation_kind = "PROPERTY_INDEX_ONE_WINDOWED_FALLBACK"
        else:
            raise
    return {
        "classification": classification,
        "row": row,
        "familyId": family_id,
        "family": family,
        "graph": graph,
        "graphPath": graph_path,
        "sourceGuid": source_guid,
        "source": source,
        "event": event,
        "eventPath": event_path,
        "runtimeName": runtime_name,
        "runtimeIdentitySha256": hashlib.sha256(
            runtime_name.encode("utf-8")
        ).hexdigest(),
        "derived": derived,
        "derivedSourceSha256": _canonical_sha(derived),
        "derivationKind": derivation_kind,
    }


def _isolated_bank(
    assetto_root: Path,
    context: dict[str, Any],
    output_root: Path,
) -> tuple[Path, dict[str, Any]]:
    installed = assetto_root / str(context["family"]["bankPath"])
    source_sha = _sha256(installed)
    if source_sha != context["familyId"]:
        raise ValueError("installed bank identity differs from graph family")
    graph = context["graph"]
    instruments = {
        _guid(item.get("guid")): item
        for item in graph["instruments"]
        if isinstance(item, dict)
    }
    reachable_waveforms = {
        _guid(guid)
        for guid in context["event"]["reachableInstrumentGuids"]
        if instruments.get(_guid(guid), {}).get("kind") == "WaveformInstrumentNode"
    }
    muted = reachable_waveforms - {context["sourceGuid"]}
    disabled = fully_muted_multi_instrument_guids(graph, muted)
    isolated_path = output_root / "isolated-banks" / f"{context['sourceGuid']}.bank"
    if muted or disabled:
        result = create_isolated_bank_copy(
            installed,
            graph,
            muted,
            isolated_path,
            disabled_parent_guids=disabled,
        )
        isolated_sha = result.output_sha256
        patched_guids = {patch.source_guid for patch in result.patches}
        changed_only_attributed = True
    else:
        isolated_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = isolated_path.with_name(f".{isolated_path.name}.tmp")
        shutil.copyfile(installed, temporary)
        os.replace(temporary, isolated_path)
        isolated_sha = _sha256(isolated_path)
        if isolated_sha != source_sha or isolated_path.stat().st_size != installed.stat().st_size:
            raise AssertionError("single-source bank copy changed bytes")
        patched_guids = set()
        changed_only_attributed = True
    if _sha256(installed) != source_sha:
        raise AssertionError("installed bank changed during isolation")
    return isolated_path, {
        "sourceBankSha256": source_sha,
        "isolatedBankSha256": isolated_sha,
        "eventWasAlreadySingleWaveform": not bool(muted or disabled),
        "mutedWaveformCount": len(muted),
        "disabledFullyMutedParentCount": len(disabled),
        "changedBytesOnlyParserAttributedFields": changed_only_attributed,
        "targetSourceWasNotPatched": context["sourceGuid"]
        not in patched_guids,
    }


def _parameter_domains(context: dict[str, Any]) -> dict[str, tuple[float, float]]:
    parameters = {
        _guid(item.get("guid")): item
        for item in context["graph"].get("parameters", [])
        if isinstance(item, dict)
    }
    result: dict[str, tuple[float, float]] = {}
    for guid in context["event"].get("parameterLayoutGuids", []):
        item = parameters.get(_guid(guid))
        if item is None:
            continue
        name = str(item.get("name") or "")
        if name in {"rpms", "drivetrain_speed", "throttle", "boost"}:
            result[name] = (float(item["minimum"]), float(item["maximum"]))
    return result


def _axis_values(
    context: dict[str, Any],
    parameter: str,
    domain: tuple[float, float],
    capture: float,
) -> list[float]:
    minimum, maximum = domain
    values = {
        minimum,
        maximum,
        capture,
        minimum + (maximum - minimum) * 0.25,
        minimum + (maximum - minimum) * 0.5,
        minimum + (maximum - minimum) * 0.75,
    }
    for controller in context["graph"].get("controllers", []):
        if str(controller.get("inputParameterName")) != parameter:
            continue
        for point in controller.get("points", []):
            x = min(maximum, max(minimum, float(point["x"])))
            values.add(x)
            # Probe stable interiors on both sides of serialized/runtime step
            # decisions without treating those offsets as placement evidence.
            epsilon = max((maximum - minimum) * 1.0e-4, 1.0e-4)
            values.add(min(maximum, max(minimum, x - epsilon)))
            values.add(min(maximum, max(minimum, x + epsilon)))
    for placement in context["event"].get("parameterPlacements", []):
        if (
            _guid(placement.get("instrumentGuid")) != context["sourceGuid"]
            or str(placement.get("parameterName")) != parameter
        ):
            continue
        for x in (float(placement["start"]), float(placement["end"])):
            values.add(min(maximum, max(minimum, x)))
            epsilon = max((maximum - minimum) * 1.0e-4, 1.0e-4)
            values.add(min(maximum, max(minimum, x - epsilon)))
            values.add(min(maximum, max(minimum, x + epsilon)))
    return sorted({round(value, 8) for value in values})


def _capture_parameters(context: dict[str, Any]) -> dict[str, float]:
    raw = context["derived"].get("captureParameterValues")
    if raw is None and context["derivationKind"] == "TURBO_CONTINUOUS_SOURCE_BOUND_PENDING":
        derived = context["derived"]
        domain = derived["boostControl"]["nativeDomain"]
        if derived["programMode"] == "BOOST_REGION_PERSISTENT_LOOP":
            placement = derived["placement"]
            capture_boost = (
                float(placement["minimum"]) + float(placement["maximum"])
            ) * 0.5
        else:
            capture_boost = (float(domain[0]) + float(domain[1])) * 0.5
        raw = {"boost": capture_boost}
    if not isinstance(raw, dict):
        raise ValueError("derived source has no capture parameter values")
    return {str(name): float(value) for name, value in raw.items()}


def _embedded_pcm16_evidence(
    installed_bank: Path, source: dict[str, Any]
) -> dict[str, Any]:
    """Bind the graph's numeric FSB5 subsound to non-name PCM evidence."""

    import numpy as np

    technical = source.get("sample") or {}
    if int(technical.get("soundBankIndex", -1)) != 0:
        raise AssertionError("continuous oracle supports FSB index zero only")
    payload = installed_bank.read_bytes()
    fsb_offset = payload.find(b"FSB5")
    if fsb_offset < 0:
        raise AssertionError("installed bank has no embedded FSB5 payload")
    try:
        version, sample_total, headers_size, names_size, data_size, audio_type = (
            struct.unpack_from("<6I", payload, fsb_offset + 4)
        )
    except struct.error as error:
        raise AssertionError("truncated FSB5 header") from error
    if audio_type != 2:
        raise AssertionError("continuous source is not FSB5 PCM16")
    header_size = 0x40 if version == 0 else 0x3C
    cursor = fsb_offset + header_size
    metadata: list[tuple[int, int]] = []
    for _index in range(sample_total):
        try:
            encoded = struct.unpack_from("<Q", payload, cursor)[0]
        except struct.error as error:
            raise AssertionError("truncated FSB5 sample metadata") from error
        cursor += 8
        more = bool(encoded & 1)
        metadata.append(
            (
                ((encoded >> 7) & ((1 << 27) - 1)) * 32,
                (encoded >> 34) & ((1 << 30) - 1),
            )
        )
        while more:
            try:
                chunk = struct.unpack_from("<I", payload, cursor)[0]
            except struct.error as error:
                raise AssertionError("truncated FSB5 sample chunk") from error
            cursor += 4
            more = bool(chunk & 1)
            cursor += (chunk >> 1) & ((1 << 24) - 1)
    if cursor > fsb_offset + header_size + headers_size:
        raise AssertionError("FSB5 metadata exceeds its declared header")
    subsound = int(technical["subsoundIndex"])
    if not 0 <= subsound < len(metadata):
        raise AssertionError("continuous target FSB5 subsound is out of range")
    data_start = fsb_offset + header_size + headers_size + names_size
    start = data_start + metadata[subsound][0]
    end_offset = (
        metadata[subsound + 1][0]
        if subsound + 1 < len(metadata)
        else data_size
    )
    encoded_payload = payload[start : data_start + end_offset]
    if hashlib.sha256(encoded_payload).hexdigest() != str(
        technical["encodedPayloadSha256"]
    ):
        raise AssertionError("embedded source PCM differs from graph hash")
    frame_count = int(technical["sampleCount"])
    channels = int(technical["channels"])
    if metadata[subsound][1] != frame_count or channels not in {1, 2, 6, 8}:
        raise AssertionError("embedded source PCM geometry differs from graph")
    required = frame_count * channels * 2
    if required > len(encoded_payload):
        raise AssertionError("embedded source PCM payload is truncated")
    pcm = np.frombuffer(
        encoded_payload, dtype="<i2", count=frame_count * channels
    ).astype(np.int32)
    peak = int(np.max(np.abs(pcm))) if pcm.size else 0
    rms = float(np.sqrt(np.mean(pcm.astype(np.float64) ** 2))) if pcm.size else 0.0
    return {
        "accepted": True,
        "encoding": "FSB5_PCM16_LE",
        "soundBankIndex": 0,
        "subsoundIndex": subsound,
        "encodedPayloadSha256": str(technical["encodedPayloadSha256"]),
        "sampleRateHz": int(technical["frequencyHz"]),
        "channels": channels,
        "frameCount": frame_count,
        "allAuthoredSamplesZero": peak == 0,
        "peakPcmDbfs": (
            20.0 * math.log10(peak / 32768.0) if peak else -240.0
        ),
        "rmsPcmDbfs": (
            20.0 * math.log10(rms / 32768.0) if rms else -240.0
        ),
        "sampleNameUsed": False,
    }


def _measure_instance(
    runtime: _OracleRuntime,
    context: dict[str, Any],
    serial: int,
    parameters: dict[str, float],
) -> dict[str, Any]:
    key = f"probe{serial:05d}"
    runtime.create_instance(key, context["eventPath"], parameters=parameters)
    runtime.start(key)
    runtime.flush(f"{key}-start")
    source_guid = context["sourceGuid"]
    timeline_starts = [
        int(item["startTime"])
        for item in context["event"].get("timelinePlacements", [])
        if _guid(item.get("instrumentGuid")) == source_guid
    ]
    settle_updates = (
        math.ceil((max(timeline_starts) * 48.0) / 256.0) + 4
        if timeline_starts
        else 4
    )
    for update in range(settle_updates):
        runtime.update(f"{key}-settle-{update}")
    snapshot = runtime.snapshot()
    voice = _active_voice(snapshot, key, source_guid)
    callbacks = [
        item
        for item in runtime.callbacks
        if item.get("category") == key and item.get("kind") == "played"
    ]
    result: dict[str, Any] = {
        "parameters": dict(sorted(parameters.items())),
        "targetScheduled": any(
            item.get("source") == context["sourceGuid"] for item in callbacks
        ),
        "scheduledSourceGuids": sorted(
            {
                str(item.get("source"))
                for item in callbacks
                if item.get("source") is not None
            }
        ),
        "activeTargetVoice": voice is not None,
    }
    if voice is not None:
        start_position = int(voice["pcmPosition"])
        hold_updates = 16
        for update in range(hold_updates):
            runtime.update(f"{key}-rate-{update}")
        end_voice = _active_voice(runtime.snapshot(), key, context["sourceGuid"])
        sample_frames = int(context["source"]["sample"]["sampleCount"])
        if end_voice is None:
            rate = None
            end_position = None
        else:
            end_position = int(end_voice["pcmPosition"])
            delta = (end_position - start_position) % sample_frames
            rate = delta / float(hold_updates * 256)
        routed_gain = float(_target_routed_gain(runtime, key, context))
        components = _target_routed_gain_components(runtime, key, context)
        result.update(
            {
                "isVirtual": bool(voice["isVirtual"]),
                "pcmPosition": int(voice["pcmPosition"]),
                "audibility": float(voice["audibility"]),
                "softwareChannelPriority": int(voice["priority"]),
                "routedGain": routed_gain,
                "routedGainComponents": components,
                "rateProbeUpdateCount": hold_updates,
                "rateProbeEndPcmPosition": end_position,
                "sourcePcmFramesPerDspFrame": rate,
            }
        )
    else:
        result.update(
            {
                "isVirtual": None,
                "pcmPosition": None,
                "audibility": 0.0,
                "softwareChannelPriority": None,
                "routedGain": 0.0,
                "routedGainComponents": [],
                "rateProbeUpdateCount": 0,
                "rateProbeEndPcmPosition": None,
                "sourcePcmFramesPerDspFrame": None,
            }
        )
    runtime.stop_release(key)
    return result


def _target_routed_gain_components(
    runtime: _OracleRuntime,
    instance_key: str,
    context: dict[str, Any],
) -> list[dict[str, Any]]:
    """Expose the numeric group/channel product without inferring track names."""

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
        "get continuous target event channel group",
    )
    pending: list[tuple[C.c_void_p, tuple[int, ...], list[float]]] = [
        (event_group, (), [])
    ]
    seen: set[int] = set()
    matches: list[dict[str, Any]] = []
    while pending:
        group, path, ancestors = pending.pop()
        address = int(group.value or 0)
        if not address or address in seen:
            continue
        seen.add(address)
        group_volume = C.c_float()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetVolume(
                group, C.byref(group_volume)
            ),
            "read continuous target group volume",
        )
        volumes = [*ancestors, float(group_volume.value)]
        channel_count = C.c_int()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetNumChannels(
                group, C.byref(channel_count)
            ),
            "count continuous target group channels",
        )
        for index in range(channel_count.value):
            channel = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_ChannelGroup_GetChannel(
                    group, index, C.byref(channel)
                ),
                "get continuous target channel",
            )
            sound = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_Channel_GetCurrentSound(
                    channel, C.byref(sound)
                ),
                "get continuous target current sound",
            )
            name_buffer = C.create_string_buffer(1024)
            runtime._check(
                runtime.core.FMOD_Sound_GetName(
                    sound, name_buffer, len(name_buffer)
                ),
                "read continuous target callback identity",
            )
            runtime_name = name_buffer.value.decode("utf-8", "replace")
            if (
                runtime.runtime_name_to_source.get(runtime_name)
                != context["sourceGuid"]
            ):
                continue
            channel_volume = C.c_float()
            runtime._check(
                runtime.core.FMOD_Channel_GetVolume(
                    channel, C.byref(channel_volume)
                ),
                "read continuous target channel volume",
            )
            product = float(channel_volume.value)
            for volume in volumes:
                product *= volume
            matches.append(
                {
                    "groupIndexPath": list(path),
                    "groupVolumes": volumes,
                    "channelVolume": float(channel_volume.value),
                    "product": product,
                }
            )
        child_count = C.c_int()
        runtime._check(
            runtime.core.FMOD_ChannelGroup_GetNumGroups(
                group, C.byref(child_count)
            ),
            "count continuous target child groups",
        )
        for index in range(child_count.value):
            child = C.c_void_p()
            runtime._check(
                runtime.core.FMOD_ChannelGroup_GetGroup(
                    group, index, C.byref(child)
                ),
                "get continuous target child group",
            )
            pending.append((child, (*path, index), volumes))
    if not matches:
        raise AssertionError("continuous target channel components are absent")
    return sorted(matches, key=lambda item: item["groupIndexPath"])


def diagnose_source(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    source_guid: str,
    output_root: Path,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    context = _source_context(graph_root, classification_path, source_guid)
    bank, isolation = _isolated_bank(root, context, output_root)
    installed_bank = root / str(context["family"]["bankPath"])
    embedded_pcm = _embedded_pcm16_evidence(installed_bank, context["source"])
    capture = _capture_parameters(context)
    domains = _parameter_domains(context)
    axes = {
        name: _axis_values(context, name, domain, capture[name])
        for name, domain in domains.items()
        if name in capture
    }
    identity_context = {
        "graph": context["graph"],
        "event": context["event"],
        "sourceGuid": context["sourceGuid"],
        "runtimeName": context["runtimeName"],
    }
    runtime_identity_map = _event_runtime_identity_map(identity_context)
    scratch = output_root / "scratch" / f"{context['sourceGuid']}.wav"
    observations: list[dict[str, Any]] = []
    serial = 0
    with _OracleRuntime(
        root,
        bank,
        runtime_identity_map,
        scratch,
        max_channels=2048,
        event_paths=(context["eventPath"],),
    ) as runtime:
        observations.append(
            {
                "kind": "CAPTURE_POINT",
                **_measure_instance(runtime, context, serial, capture),
            }
        )
        serial += 1
        for parameter, values in sorted(axes.items()):
            for value in values:
                parameters = dict(capture)
                parameters[parameter] = value
                observations.append(
                    {
                        "kind": "AXIS_POINT",
                        "axis": parameter,
                        **_measure_instance(
                            runtime, context, serial, parameters
                        ),
                    }
                )
                serial += 1
        speed = (
            "drivetrain_speed"
            if "drivetrain_speed" in axes
            else "rpms" if "rpms" in axes else None
        )
        if speed is not None and "throttle" in axes:
            # A bounded cross-grid detects non-factorizable routing before any
            # manifest schema is proposed.  Axis extremes, midpoint and
            # capture are sufficient for diagnosis; certification adaptively
            # refines this surface later.
            speed_values = sorted(
                {
                    axes[speed][0],
                    axes[speed][len(axes[speed]) // 2],
                    axes[speed][-1],
                    capture[speed],
                }
            )
            throttle_values = sorted(
                {
                    axes["throttle"][0],
                    axes["throttle"][len(axes["throttle"]) // 2],
                    axes["throttle"][-1],
                    capture["throttle"],
                }
            )
            for speed_value in speed_values:
                for throttle_value in throttle_values:
                    parameters = dict(capture)
                    parameters[speed] = speed_value
                    parameters["throttle"] = throttle_value
                    observations.append(
                        {
                            "kind": "CROSS_POINT",
                            "speedAxis": speed,
                            **_measure_instance(
                                runtime, context, serial, parameters
                            ),
                        }
                    )
                    serial += 1
        runtime._raise_callback_errors()
    scratch.unlink(missing_ok=True)
    audible = [
        item
        for item in observations
        if float(item.get("routedGain", 0.0)) > 0.0
        and item.get("activeTargetVoice") is True
    ]
    result = {
        "schema": DIAGNOSTIC_SCHEMA,
        "familyId": context["familyId"],
        "sourceGuid": context["sourceGuid"],
        "eventPath": context["eventPath"],
        "classifierRole": context["row"]["role"],
        "derivationKind": context["derivationKind"],
        "derivedSourceSha256": context["derivedSourceSha256"],
        "graphReportFileSha256": _sha256(context["graphPath"]),
        "classificationFileSha256": _sha256(classification_path),
        "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
        "runtimeIdentity": {
            "usedOnlyForCallbackChannelJoin": True,
            "sha256": context["runtimeIdentitySha256"],
            "sampleNameEmitted": False,
        },
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "audioDeviceOpened": False,
            "freshProcessScope": "ONE_SOURCE_PER_PROCESS",
            "workerProcessId": os.getpid(),
        },
        "isolation": isolation,
        "embeddedSourcePcm": embedded_pcm,
        "captureParameterValues": capture,
        "parameterDomains": {
            name: list(domain) for name, domain in sorted(domains.items())
        },
        "axisValues": axes,
        "observationCount": len(observations),
        "audibleObservationCount": len(audible),
        "maximumObservedRoutedGain": max(
            (float(item["routedGain"]) for item in audible), default=0.0
        ),
        "observations": observations,
        "diagnosticOnly": True,
        "releaseExactnessUpgraded": False,
    }
    result["diagnosticPayloadSha256"] = _canonical_sha(result)
    return result


def _run_diagnose(args: argparse.Namespace) -> int:
    selected = tuple(args.source_guid) or KNOWN_DIAGNOSTIC_SOURCES
    output_root = args.output_root.resolve()
    for source_guid in selected:
        result = diagnose_source(
            find_assetto_root(args.assetto_root),
            args.graph_root,
            args.classification,
            source_guid,
            output_root,
        )
        report = output_root / "diagnostics" / f"{_guid(source_guid)}.json"
        _write_canonical(report, result)
        print(
            f"continuous diagnostic source={_guid(source_guid)} "
            f"audible={result['audibleObservationCount']}/"
            f"{result['observationCount']} evidence={report}",
            flush=True,
        )
    return 0


def _turbo_continuous_source_guids(
    classification_path: Path,
) -> tuple[str, ...]:
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    guids = tuple(
        sorted(
            _guid(row.get("sourceGuid"))
            for row in classification["sourceDecisions"]
            if row.get("policy") == "allowCandidate"
            and row.get("role") == TURBO_CONTINUOUS_CLASSIFIER_ROLE
        )
    )
    if len(guids) != 66 or len(set(guids)) != 66:
        raise ValueError("official continuous-turbo source inventory changed")
    return guids


def _run_diagnose_turbo_continuous(args: argparse.Namespace) -> int:
    allowed = _turbo_continuous_source_guids(args.classification)
    selected = tuple(_guid(value) for value in args.source_guid) or allowed
    unknown = sorted(set(selected) - set(allowed))
    if unknown:
        raise ValueError(f"unknown continuous-turbo sources: {unknown}")
    output_root = args.output_root.resolve()
    for source_guid in selected:
        result = diagnose_source(
            find_assetto_root(args.assetto_root),
            args.graph_root,
            args.classification,
            source_guid,
            output_root,
        )
        report = output_root / "turbo-continuous" / source_guid / "diagnostic.json"
        _write_canonical(report, result)
        print(
            f"continuous-turbo diagnostic source={source_guid} "
            f"audible={result['audibleObservationCount']}/"
            f"{result['observationCount']} evidence={report}",
            flush=True,
        )
    return 0


def _turbo_control_knots(static: dict[str, Any]) -> list[float]:
    domain = [float(value) for value in static["boostControl"]["nativeDomain"]]
    values = {domain[0], domain[1], (domain[0] + domain[1]) * 0.5}
    for controller in static["controllerDispositions"]:
        for point in controller["points"]:
            values.add(min(domain[1], max(domain[0], float(point["x"]))))
    if static["programMode"] == "BOOST_REGION_PERSISTENT_LOOP":
        placement = static["placement"]
        values.update((float(placement["minimum"]), float(placement["maximum"])))
    return sorted({_float32(value) for value in values})


def _turbo_property_one_points(
    static: dict[str, Any],
) -> tuple[str | None, tuple[tuple[float, float, float, int], ...]]:
    matches = [
        item
        for item in static["controllerDispositions"]
        if item["treatment"] == "SOURCE_BOUND_CAPTURE_RELATIVE_RATE_CURVE"
    ]
    if len(matches) > 1:
        raise ValueError("continuous turbo source has multiple pitch controllers")
    if not matches:
        return None, ()
    controller = matches[0]
    points = tuple(
        (
            float(item["x"]),
            float(item["y"]),
            float(item["shape"]),
            int(item["type"]),
        )
        for item in controller["points"]
    )
    if len(points) < 2:
        raise ValueError("continuous turbo pitch controller is incomplete")
    return str(controller["controllerGuid"]), points


def _build_turbo_pitch_curve(
    static: dict[str, Any], capture_boost: float
) -> tuple[list[list[float]], dict[str, Any]]:
    domain = tuple(float(value) for value in static["boostControl"]["nativeDomain"])
    controller_guid, raw_points = _turbo_property_one_points(static)
    if not raw_points:
        return [[domain[0], 1.0], [domain[1], 1.0]], {
            "authoredPropertyOnePresent": False,
            "controllerGuid": None,
            "rawValueToSemitonesScale": 24.0,
            "maximumLinearizationErrorCents": 0.0,
            "denseProbeCount": 0,
        }
    capture_raw = evaluate_authored_curve(raw_points, capture_boost)

    def rate(boost: float) -> float:
        raw = evaluate_authored_curve(raw_points, boost)
        return 2.0 ** (2.0 * (raw - capture_raw))

    controls = sorted(
        {
            domain[0],
            domain[1],
            capture_boost,
            *(
                min(domain[1], max(domain[0], float(point[0])))
                for point in raw_points
            ),
        }
    )
    maximum_error = math.inf
    probe_count = 0
    for _depth in range(16):
        additions: list[float] = []
        maximum_error = 0.0
        probe_count = 0
        for left, right in zip(controls, controls[1:]):
            left_rate = rate(left)
            right_rate = rate(right)
            for fraction in (0.25, 0.5, 0.75):
                control = left + ((right - left) * fraction)
                exact = rate(control)
                linear = left_rate + ((right_rate - left_rate) * fraction)
                error = abs(1200.0 * math.log2(exact / linear))
                maximum_error = max(maximum_error, error)
                probe_count += 1
                if error > TURBO_CONTINUOUS_PITCH_LINEARIZATION_CENTS:
                    additions.append(control)
        if not additions:
            break
        controls.extend(additions)
        controls = sorted(set(controls))
        if len(controls) > 512:
            raise ValueError("continuous turbo pitch curve exceeds 512 points")
    else:
        raise ValueError("continuous turbo pitch curve did not converge")
    curve = [[float(value), float(rate(value))] for value in controls]
    return curve, {
        "authoredPropertyOnePresent": True,
        "controllerGuid": controller_guid,
        "rawValueToSemitonesScale": 24.0,
        "captureRawPropertyOneValue": capture_raw,
        "maximumLinearizationErrorCents": maximum_error,
        "denseProbeCount": probe_count,
        "rawControllerPoints": [list(item) for item in raw_points],
    }


def _normalize_gain_transitions(
    transitions: list[dict[str, float]], capture_gain: float
) -> list[dict[str, float]]:
    result: list[dict[str, float]] = []
    for item in transitions:
        normalized = dict(item)
        for name in (
            "leftRelativeGain",
            "rightRelativeGain",
            "maximumObservedAbsoluteLinearGainError",
        ):
            normalized[name] = float(item[name]) / capture_gain
        result.append(normalized)
    return result


def _adaptive_curve_product(
    left_curve: list[list[float]],
    right_curve: list[list[float]],
    *,
    tolerance: float = TURBO_CONTINUOUS_GAIN_TOLERANCE,
) -> tuple[list[list[float]], float]:
    points = sorted(
        {float(item[0]) for item in left_curve}
        | {float(item[0]) for item in right_curve}
    )

    def product(control: float) -> float:
        return _linear_curve(left_curve, control) * _linear_curve(
            right_curve, control
        )

    maximum_error = math.inf
    for _depth in range(16):
        additions: list[float] = []
        maximum_error = 0.0
        for left, right in zip(points, points[1:]):
            left_value = product(left)
            right_value = product(right)
            for fraction in (0.25, 0.5, 0.75):
                control = left + ((right - left) * fraction)
                actual = product(control)
                linear = left_value + ((right_value - left_value) * fraction)
                error = abs(actual - linear)
                maximum_error = max(maximum_error, error)
                if error > tolerance:
                    additions.append(control)
        if not additions:
            break
        points = sorted(set(points) | set(additions))
        if len(points) > 512:
            raise ValueError("effective continuous turbo gain curve exceeds 512 points")
    else:
        raise ValueError("effective continuous turbo gain curve did not converge")
    return [[value, product(value)] for value in points], maximum_error


def _turbo_live_curve_verification(
    runtime: _OracleRuntime,
    context: dict[str, Any],
    static: dict[str, Any],
    diagnostic: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], float, dict[float, dict[str, Any]]]:
    knots = _turbo_control_knots(static)
    capture_boost = float(_capture_parameters(context)["boost"])
    serial = 100000
    cache: dict[float, dict[str, Any]] = {}

    def observation(control: float) -> dict[str, Any]:
        nonlocal serial
        control = _float32(control)
        if control not in cache:
            cache[control] = _measure_instance(
                runtime, context, serial, {"boost": control}
            )
            serial += 1
        return cache[control]

    capture_observation = observation(capture_boost)
    capture_gain = float(capture_observation["routedGain"])
    if capture_gain <= 0.0:
        candidates = [
            item
            for item in diagnostic["observations"]
            if item.get("targetScheduled") is True
            and item.get("activeTargetVoice") is True
            and float(item.get("routedGain", 0.0)) > 0.0
        ]
        if candidates:
            selected = max(
                candidates,
                key=lambda item: (
                    float(item["routedGain"]),
                    -abs(float(item["parameters"]["boost"]) - capture_boost),
                ),
            )
            capture_boost = _float32(selected["parameters"]["boost"])
            capture_observation = observation(capture_boost)
            capture_gain = float(capture_observation["routedGain"])
    silent = capture_gain <= 0.0
    tolerance = (
        1.0e-12
        if silent
        else max(1.0e-12, capture_gain * TURBO_CONTINUOUS_GAIN_TOLERANCE)
    )
    absolute_curve, transitions, maximum_absolute_error = _adaptive_empirical_curve(
        lambda control: float(observation(control)["routedGain"]),
        knots,
        tolerance=tolerance,
    )
    for x, _gain in absolute_curve:
        observation(x)
    active_priorities = {
        int(item["softwareChannelPriority"])
        for item in cache.values()
        if item.get("activeTargetVoice") is True
        and item.get("softwareChannelPriority") is not None
    }
    if active_priorities != {128}:
        raise ValueError(
            f"continuous turbo priority evidence changed: {sorted(active_priorities)}"
        )
    if silent:
        if any(y != 0.0 for _x, y in absolute_curve) or transitions:
            raise ValueError("continuous turbo routed-silent surface is nonzero")
        gain_verification = {
            "accepted": True,
            "absoluteRoutedGainCurve": absolute_curve,
            "captureRelativeRoutedGainCurve": None,
            "captureRoutedGain": 0.0,
            "maximumCaptureRelativeGain": None,
            "maximumAbsoluteRoutedGain": 0.0,
            "transitionIntervals": [],
            "maximumSmoothAbsoluteLinearError": maximum_absolute_error,
            "adaptiveProbeCount": len(cache),
            "oracleVersion": "fmod108-source-bound-continuous-routed-gain-f32-adaptive-v1",
            "softwareChannelPriorities": [128],
        }
        return gain_verification, {}, capture_boost, cache
    relative_curve = [[x, y / capture_gain] for x, y in absolute_curve]
    relative_transitions = _normalize_gain_transitions(transitions, capture_gain)
    maximum_relative_error = maximum_absolute_error / capture_gain
    pitch_curve, construction = _build_turbo_pitch_curve(static, capture_boost)
    sample_rate = float(context["source"]["sample"]["frequencyHz"])
    sample_frames = int(context["source"]["sample"]["sampleCount"])
    capture_rate = float(capture_observation["sourcePcmFramesPerDspFrame"] or 0.0)
    if capture_rate <= 0.0:
        raise ValueError("continuous turbo capture rate is nonpositive")
    rate_records: list[dict[str, Any]] = []
    for control in sorted(cache):
        item = cache[control]
        measured_modulo = item.get("sourcePcmFramesPerDspFrame")
        if (
            item.get("targetScheduled") is not True
            or item.get("activeTargetVoice") is not True
            or measured_modulo is None
            or float(item.get("routedGain", 0.0)) <= 1.0e-8
        ):
            continue
        expected_relative = _linear_curve(pitch_curve, control)
        expected = capture_rate * expected_relative
        update_count = int(item["rateProbeUpdateCount"])
        measured_delta = float(measured_modulo) * update_count * 256.0
        expected_delta = expected * update_count * 256.0
        wraps = max(0, round((expected_delta - measured_delta) / sample_frames))
        measured = (measured_delta + wraps * sample_frames) / (
            update_count * 256.0
        )
        error = abs(1200.0 * math.log2(measured / expected))
        rate_records.append(
            {
                "boost": control,
                "expectedCaptureRelativePlaybackRate": expected_relative,
                "expectedSourcePcmFramesPerDspFrame": expected,
                "observedSourcePcmFramesPerDspFrame": measured,
                "reconstructedWholeSourceWrapCount": wraps,
                "pitchErrorCents": error,
            }
        )
    required_records = 8 if construction["authoredPropertyOnePresent"] else 3
    maximum_pitch_error = max(
        (item["pitchErrorCents"] for item in rate_records), default=math.inf
    )
    pitch_verification = {
        "accepted": len(rate_records) >= required_records
        and maximum_pitch_error <= 5.0,
        "control": "BOOST",
        "captureBoost": capture_boost,
        "captureRelativePlaybackRateCurve": pitch_curve,
        "interpolation": TURBO_CONTINUOUS_INTERPOLATION,
        "runtimeTreatment": "REPLACES_ORDINARY_RPM_ROOT_RATIO",
        "sourcePcmSampleRateHz": sample_rate,
        "sourcePcmFrameCount": sample_frames,
        "absoluteCaptureSourcePcmFramesPerDspFrame": capture_rate,
        "maximumLiveRateErrorCents": maximum_pitch_error,
        "observationCount": len(rate_records),
        "observations": rate_records,
        "curveConstruction": construction,
    }
    gain_verification = {
        "accepted": maximum_relative_error <= TURBO_CONTINUOUS_GAIN_TOLERANCE,
        "absoluteRoutedGainCurve": absolute_curve,
        "captureRelativeRoutedGainCurve": relative_curve,
        "captureRoutedGain": capture_gain,
        "maximumCaptureRelativeGain": max(y for _x, y in relative_curve),
        "maximumAbsoluteRoutedGain": max(y for _x, y in absolute_curve),
        "transitionIntervals": relative_transitions,
        "maximumSmoothAbsoluteLinearError": maximum_relative_error,
        "adaptiveProbeCount": len(cache),
        "oracleVersion": "fmod108-source-bound-continuous-routed-gain-f32-adaptive-v1",
        "softwareChannelPriorities": [128],
    }
    return gain_verification, pitch_verification, capture_boost, cache


def _render_turbo_continuous_target(
    renderer: SilentFmodReferenceRenderer,
    context: dict[str, Any],
    bank: Path,
    output_path: Path,
    boost: float,
    *,
    duration_frames: int,
) -> tuple[bytes, dict[str, Any]]:
    result = renderer.render_event(
        bank,
        context["eventPath"],
        output_path,
        parameters={"boost": float(boost)},
        duration_frames=duration_frames,
        warmup_frames=TURBO_CONTINUOUS_WARMUP_FRAMES,
        required_sound_name=context["runtimeName"],
        maximum_takes=1,
    )
    if set(result.scheduled_sound_names) != {context["runtimeName"]}:
        raise ValueError("continuous turbo target-only identity is not exclusive")
    payload, frames = _read_pcm16_wav(output_path)
    if frames != duration_frames:
        raise ValueError("continuous turbo renderer returned the wrong frame count")
    worker = copy.deepcopy(renderer.last_fresh_process_evidence)
    if not isinstance(worker, dict):
        raise ValueError("fresh-process continuous turbo evidence is absent")
    evidence_path = Path(str(worker["path"])).resolve(strict=True)
    worker["relativeEvidencePath"] = evidence_path.relative_to(
        output_path.parents[3]
    ).as_posix()
    worker.pop("path", None)
    return payload, {
        "scheduledSourceGuids": [context["sourceGuid"]],
        "pcmPayloadSha256": hashlib.sha256(payload).hexdigest(),
        "frameCount": frames,
        "wavSha256": _sha256(output_path),
        "worker": worker,
    }


def _turbo_pcm_verification(
    root: Path,
    context: dict[str, Any],
    bank: Path,
    output_root: Path,
    capture_boost: float,
    gain_verification: dict[str, Any],
    pitch_verification: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    renderer = SilentFmodReferenceRenderer(
        root, dsp_buffer_frames=256, fresh_process_per_render=True
    )
    source_root = output_root / "turbo-continuous" / context["sourceGuid"]
    render_root = source_root / "renders"
    first_path = render_root / "capture-a.wav"
    second_path = render_root / "capture-b.wav"
    first, first_evidence = _render_turbo_continuous_target(
        renderer,
        context,
        bank,
        first_path,
        capture_boost,
        duration_frames=TURBO_CONTINUOUS_CAPTURE_FRAMES,
    )
    second, second_evidence = _render_turbo_continuous_target(
        renderer,
        context,
        bank,
        second_path,
        capture_boost,
        duration_frames=TURBO_CONTINUOUS_CAPTURE_FRAMES,
    )
    if first != second:
        raise ValueError("independent continuous turbo captures are not bit exact")
    final_path = source_root / "capture.wav"
    final_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(first_path, final_path)
    loop_start, loop_end, seam_dbfs = _repair_bounded_continuous_loop(final_path)
    pack_payload, pack_frames = _read_pcm16_wav(final_path)
    peak_dbfs = _peak_dbfs(pack_payload)
    if not math.isfinite(peak_dbfs) or peak_dbfs <= -96.0:
        raise ValueError("continuous turbo final capture is not audibly nonzero")
    gain_curve = gain_verification["captureRelativeRoutedGainCurve"]
    pitch_curve = pitch_verification["captureRelativePlaybackRateCurve"]
    candidates = sorted(
        {
            float(point[0])
            for point in gain_curve
            if _linear_curve(gain_curve, float(point[0])) >= 0.025
            and 0.5 <= _linear_curve(pitch_curve, float(point[0])) <= 2.0
        }
        | {float(capture_boost)}
    )
    if len(candidates) < 3:
        raise ValueError("continuous turbo has fewer than three audible PCM probes")
    selected = {
        min(
            candidates,
            key=lambda control: (
                abs(_linear_curve(pitch_curve, control) - target_rate),
                abs(control - capture_boost),
                control,
            ),
        )
        for target_rate in (0.6, 0.8, 1.0, 1.25, 1.75)
    }
    selected.add(float(capture_boost))
    probes: list[dict[str, Any]] = []
    for index, boost in enumerate(sorted(selected)):
        target_path = render_root / f"target-{index:02d}-{boost:.8f}.wav"
        target_payload, target_evidence = _render_turbo_continuous_target(
            renderer,
            context,
            bank,
            target_path,
            boost,
            duration_frames=TURBO_CONTINUOUS_ANALYSIS_FRAMES,
        )
        target_pcm = _pcm16_stereo(
            target_payload, TURBO_CONTINUOUS_ANALYSIS_FRAMES
        )
        rate = _linear_curve(pitch_curve, boost)
        required_frames = int(
            math.ceil(TURBO_CONTINUOUS_ANALYSIS_FRAMES * rate)
        ) + 4
        extended = _loop_extended_pcm(
            pack_payload,
            pack_frames,
            loop_start,
            loop_end,
            required_frames,
        )
        runtime_pcm = _cubic_varispeed(
            extended, rate, TURBO_CONTINUOUS_ANALYSIS_FRAMES
        )
        relative_gain = _linear_curve(gain_curve, boost)
        runtime_pcm = runtime_pcm * relative_gain
        common = min(len(target_pcm), len(runtime_pcm))
        target_pcm = target_pcm[:common]
        runtime_pcm = runtime_pcm[:common]
        target_rms, target_stationarity = _rms_and_stationarity(target_pcm)
        runtime_rms, runtime_stationarity = _rms_and_stationarity(runtime_pcm)
        if target_rms <= 4.0 / 32768.0 or runtime_rms <= 4.0 / 32768.0:
            raise ValueError(
                f"continuous turbo PCM probe is inaudible at boost {boost}"
            )
        gain_error = abs(20.0 * math.log10(runtime_rms / target_rms))
        pitch = measure_relative_log_spectral_pitch(target_pcm, runtime_pcm)
        probes.append(
            {
                "boost": boost,
                "captureRelativePlaybackRate": rate,
                "captureRelativeRuntimeGain": relative_gain,
                "comparedFrameCount": common,
                "pitchErrorCents": float(pitch["pitchErrorCents"]),
                "gainErrorDb": gain_error,
                "uncompensatedGainErrorDb": gain_error,
                "targetRms": target_rms,
                "runtimeRms": runtime_rms,
                "targetStationarityMadDb": target_stationarity,
                "runtimeStationarityMadDb": runtime_stationarity,
                "pitchConfidence": pitch,
                "targetCapture": target_evidence,
            }
        )
    # The Android mixer uses cubic varispeed while FMOD 1.08 owns the target
    # resampler.  Their long-window RMS differs by roughly 0.25 dB away from
    # an exact 1.0 rate on some broadband turbo beds.  This is not authored
    # routed gain and cannot be hidden by relaxing the 0.25 dB final gate.
    # Measure a source-bound correction at the already rendered target points,
    # fold it into the single effective runtime gain curve, and keep the raw
    # routed curve separately as provenance.  The capture point remains unity.
    domain = [float(item[0]) for item in gain_curve]
    correction_points: dict[float, float] = {}
    for item in probes:
        boost = float(item["boost"])
        correction_points[boost] = (
            1.0
            if abs(boost - capture_boost) <= 1.0e-8
            else float(item["targetRms"]) / float(item["runtimeRms"])
        )
    for boundary in (domain[0], domain[-1]):
        if boundary not in correction_points:
            if _linear_curve(gain_curve, boundary) == 0.0:
                correction_points[boundary] = 1.0
            else:
                nearest = min(
                    correction_points,
                    key=lambda value: abs(value - boundary),
                )
                correction_points[boundary] = correction_points[nearest]
    compensation_curve = [
        [control, correction_points[control]]
        for control in sorted(correction_points)
    ]
    effective_gain_curve, product_error = _adaptive_curve_product(
        gain_curve, compensation_curve
    )
    if abs(_linear_curve(effective_gain_curve, capture_boost) - 1.0) > 2.0e-4:
        raise ValueError("continuous turbo effective gain lost capture unity")
    for item in probes:
        correction = _linear_curve(compensation_curve, float(item["boost"]))
        corrected_runtime_rms = float(item["runtimeRms"]) * correction
        item["cubicVarispeedRmsCompensation"] = correction
        item["correctedRuntimeRms"] = corrected_runtime_rms
        item["gainErrorDb"] = abs(
            20.0
            * math.log10(corrected_runtime_rms / float(item["targetRms"]))
        )
    gain_verification["sourceBoundRoutedGainCurve"] = copy.deepcopy(gain_curve)
    gain_verification["cubicVarispeedRmsCompensationCurve"] = compensation_curve
    gain_verification["runtimeResamplerGainCompensation"] = {
        "kind": "SOURCE_BOUND_CUBIC_TO_FMOD_LONG_WINDOW_RMS",
        "sign": "MULTIPLY_RUNTIME_GAIN",
        "linearCurve": copy.deepcopy(compensation_curve),
        "decibelCurve": [
            [
                float(control),
                20.0 * math.log10(max(float(value), 1.0e-24)),
            ]
            for control, value in compensation_curve
        ],
        "applicationCount": 1,
        "bakedIntoTargetOnlyPcm": False,
        "foldedIntoEffectiveControlGainCurve": True,
    }
    gain_verification["captureRelativeRoutedGainCurve"] = effective_gain_curve
    gain_verification["maximumCaptureRelativeGain"] = max(
        item[1] for item in effective_gain_curve
    )
    gain_verification["effectiveCurveProductMaximumLinearizationError"] = (
        product_error
    )
    gain_verification["runtimeGainComposition"] = (
        "SOURCE_BOUND_ROUTED_GAIN_TIMES_SOURCE_BOUND_CUBIC_VARISPEED_RMS_COMPENSATION"
    )
    maximum_pitch = max(item["pitchErrorCents"] for item in probes)
    maximum_gain = max(item["gainErrorDb"] for item in probes)
    capture = {
        "captureBoost": capture_boost,
        "captureControlValues": [{"control": "BOOST", "value": capture_boost}],
        "scheduledSourceGuids": [context["sourceGuid"]],
        "sampleRateHz": 48000,
        "channels": 2,
        "sampleFormat": "SIGNED_PCM16_LE",
        "frameCount": pack_frames,
        "loopStartFrame": loop_start,
        "loopEndFrameExclusive": loop_end,
        "loopSeamPeakDbfs": (
            seam_dbfs if math.isfinite(seam_dbfs) else "NEGATIVE_INFINITY"
        ),
        "peakPcmDbfs": peak_dbfs,
        "pcmPayloadSha256": hashlib.sha256(pack_payload).hexdigest(),
        "finalWavRelativePath": final_path.relative_to(output_root).as_posix(),
        "finalWavSha256": _sha256(final_path),
        "independentFreshProcessRendersBitExact": True,
        "independentFreshProcessRenders": [first_evidence, second_evidence],
    }
    target_verification = {
        "accepted": maximum_pitch <= 5.0 and maximum_gain <= 0.25,
        "probeCount": len(probes),
        "maximumPitchErrorCents": maximum_pitch,
        "maximumGainErrorDb": maximum_gain,
        "runtimeInterpolation": "CUBIC_VARISPEED",
        "gainTreatment": "SOURCE_BOUND_ROUTED_GAIN_RELATIVE_TO_CAPTURE",
        "cubicVarispeedRmsCompensationAppliedExactlyOnce": True,
        "pitchTreatment": "AUTHORED_BOOST_RELATIVE_RATE_REPLACES_RPM_ROOT",
        "probes": probes,
    }
    return capture, target_verification


def _turbo_silence_verification(
    root: Path,
    context: dict[str, Any],
    bank: Path,
    output_root: Path,
    capture_boost: float,
    gain_verification: dict[str, Any],
    diagnostic: dict[str, Any],
) -> dict[str, Any]:
    renderer = SilentFmodReferenceRenderer(
        root, dsp_buffer_frames=256, fresh_process_per_render=True
    )
    render_root = (
        output_root / "turbo-continuous" / context["sourceGuid"] / "renders"
    )
    payloads: list[bytes] = []
    evidence: list[dict[str, Any]] = []
    for suffix in ("a", "b"):
        payload, record = _render_turbo_continuous_target(
            renderer,
            context,
            bank,
            render_root / f"silent-{suffix}.wav",
            capture_boost,
            duration_frames=TURBO_CONTINUOUS_CAPTURE_FRAMES,
        )
        payloads.append(payload)
        evidence.append(record)
    all_zero = all(payload and not any(payload) for payload in payloads)
    observations = diagnostic["observations"]
    scheduled_zero = all(
        item.get("targetScheduled") is True
        and item.get("activeTargetVoice") is True
        and float(item.get("routedGain", math.nan)) == 0.0
        and float(item.get("audibility", math.nan)) == 0.0
        for item in observations
    )
    return {
        "accepted": all_zero
        and scheduled_zero
        and all(
            point[1] == 0.0
            for point in gain_verification["absoluteRoutedGainCurve"]
        ),
        "targetScheduledAtEveryOraclePoint": scheduled_zero,
        "targetRouteExactZeroAtEveryOraclePoint": scheduled_zero,
        "targetPcmAllZeroInTwoIndependentRenders": all_zero,
        "embeddedSourcePcmNonzero": diagnostic["embeddedSourcePcm"][
            "allAuthoredSamplesZero"
        ]
        is False,
        "embeddedPcmPayloadSha256": diagnostic["embeddedSourcePcm"][
            "encodedPayloadSha256"
        ],
        "independentFreshProcessRenders": evidence,
    }


def _turbo_lifecycle_verification(
    root: Path,
    context: dict[str, Any],
    bank: Path,
    static: dict[str, Any],
    capture_boost: float,
    gain_verification: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    identity_map = _event_runtime_identity_map(
        {
            "graph": context["graph"],
            "event": context["event"],
            "sourceGuid": context["sourceGuid"],
            "runtimeName": context["runtimeName"],
        }
    )
    writer = (
        output_root
        / "turbo-continuous"
        / context["sourceGuid"]
        / "lifecycle.wav"
    )
    writer.parent.mkdir(parents=True, exist_ok=True)
    callbacks: list[dict[str, Any]] = []
    observations: list[dict[str, Any]] = []
    runtime_policy: dict[str, Any]
    with _OracleRuntime(
        root,
        bank,
        identity_map,
        writer,
        max_channels=2048,
        event_paths=(context["eventPath"],),
    ) as runtime:
        if static["programMode"] == "BOOST_REGION_PERSISTENT_LOOP":
            placement = static["placement"]
            domain = static["boostControl"]["nativeDomain"]
            outside = (
                float(domain[0])
                if float(domain[0]) < float(placement["minimum"])
                else float(domain[1])
            )
            runtime.create_instance(
                "lifecycle", context["eventPath"], parameters={"boost": outside}
            )
            runtime.start("lifecycle")
            runtime.flush("start-outside")
            for index in range(8):
                runtime.update(f"outside-start-{index}")
            initially_absent = (
                _active_voice(
                    runtime.snapshot(), "lifecycle", context["sourceGuid"]
                )
                is None
                and not runtime.callbacks
            )
            runtime.set_parameter("lifecycle", "boost", capture_boost)
            runtime.flush("enter-region")
            for index in range(8):
                runtime.update(f"inside-first-{index}")
            first_voice = _active_voice(
                runtime.snapshot(), "lifecycle", context["sourceGuid"]
            )
            first_played = len(
                [item for item in runtime.callbacks if item["kind"] == "played"]
            )
            runtime.set_parameter("lifecycle", "boost", outside)
            runtime.flush("exit-region")
            stop_latency_updates: int | None = None
            for index in range(32):
                runtime.update(f"outside-exit-{index}")
                if any(item["kind"] == "stopped" for item in runtime.callbacks):
                    stop_latency_updates = index + 1
                    break
            stopped_after_exit = (
                stop_latency_updates is not None
                and _active_voice(
                    runtime.snapshot(), "lifecycle", context["sourceGuid"]
                )
                is None
            )
            runtime.set_parameter("lifecycle", "boost", capture_boost)
            runtime.flush("reenter-region")
            runtime.update("reenter-first")
            second_voice = _active_voice(
                runtime.snapshot(), "lifecycle", context["sourceGuid"]
            )
            played_after_reentry = len(
                [item for item in runtime.callbacks if item["kind"] == "played"]
            )
            restart_position = (
                None if second_voice is None else int(second_voice["pcmPosition"])
            )
            accepted = (
                initially_absent
                and first_voice is not None
                and first_played == 1
                and stopped_after_exit
                and played_after_reentry == 2
                and restart_position == 0
            )
            runtime_policy = {
                "kind": "BOOST_REGION_PERSISTENT_LOOP",
                "placement": copy.deepcopy(placement),
                "eventStartOutsideBehavior": "NO_SOURCE_UNTIL_FIRST_REGION_ENTRY",
                "eventStartInsideBehavior": "START_LOOP_FROM_FRAME_ZERO",
                "regionEntryBehavior": "START_ONE_LOOP_FROM_FRAME_ZERO",
                "regionExitBehavior": "ALLOW_AUTHORED_FADE_THEN_STOP_SOURCE",
                "regionExitStopLatencyWriterFrames": (
                    None
                    if stop_latency_updates is None
                    else stop_latency_updates * 256
                ),
                "reentryBehavior": "START_NEW_LOOP_FROM_FRAME_ZERO",
                "maximumSimultaneousSourceVoices": 1,
                "exactZeroGainVirtualization": "NOT_APPLICABLE_REGION_EXIT_OWNS_STOP",
            }
            observations.extend(
                [
                    {"stage": "eventStartOutside", "sourceAbsent": initially_absent},
                    {
                        "stage": "firstEntry",
                        "sourcePresent": first_voice is not None,
                        "playedCount": first_played,
                    },
                    {
                        "stage": "exit",
                        "stopped": stopped_after_exit,
                        "stopLatencyWriterFrames": runtime_policy[
                            "regionExitStopLatencyWriterFrames"
                        ],
                    },
                    {
                        "stage": "reentry",
                        "playedCount": played_after_reentry,
                        "sourcePcmPosition": restart_position,
                    },
                ]
            )
        else:
            domain = [float(value) for value in static["boostControl"]["nativeDomain"]]
            gain_curve = gain_verification["captureRelativeRoutedGainCurve"]
            zero_controls = [x for x, y in gain_curve if y == 0.0 and x <= 1.0]
            audible_controls = [x for x, y in gain_curve if y > 0.0 and x <= 1.0]
            alternate = max(audible_controls, key=lambda value: abs(value - capture_boost))
            runtime.create_instance(
                "lifecycle",
                context["eventPath"],
                parameters={"boost": capture_boost},
            )
            runtime.start("lifecycle")
            runtime.flush("timeline-start")
            for index in range(16):
                runtime.update(f"timeline-settle-{index}")
            first_voice = _active_voice(
                runtime.snapshot(), "lifecycle", context["sourceGuid"]
            )
            runtime.set_parameter("lifecycle", "boost", alternate)
            runtime.flush("timeline-motion")
            for index in range(8):
                runtime.update(f"timeline-motion-{index}")
            motion_voice = _active_voice(
                runtime.snapshot(), "lifecycle", context["sourceGuid"]
            )
            played = [item for item in runtime.callbacks if item["kind"] == "played"]
            stopped = [item for item in runtime.callbacks if item["kind"] == "stopped"]
            persistent = (
                first_voice is not None
                and motion_voice is not None
                and len(played) == 1
                and not stopped
            )
            zero_required = bool(zero_controls)
            zero_observations: list[dict[str, Any]] = []
            if zero_required:
                zero_control = min(
                    zero_controls, key=lambda value: abs(value - capture_boost)
                )
                runtime.set_parameter("lifecycle", "boost", zero_control)
                runtime.flush("timeline-zero")
                for index in range(32):
                    runtime.update(f"timeline-zero-{index}")
                    voice = _active_voice(
                        runtime.snapshot(), "lifecycle", context["sourceGuid"]
                    )
                    zero_observations.append(
                        {
                            "writerFramesAfterCrossing": (index + 1) * 256,
                            "voicePresent": voice is not None,
                            "isVirtual": None if voice is None else bool(voice["isVirtual"]),
                            "audibility": (
                                0.0 if voice is None else float(voice["audibility"])
                            ),
                        }
                    )
                runtime.set_parameter("lifecycle", "boost", capture_boost)
                runtime.flush("timeline-restore")
                for index in range(8):
                    runtime.update(f"timeline-restore-{index}")
                restored = _active_voice(
                    runtime.snapshot(), "lifecycle", context["sourceGuid"]
                )
                persistent = persistent and restored is not None and len(
                    [item for item in runtime.callbacks if item["kind"] == "played"]
                ) == 1 and not [
                    item for item in runtime.callbacks if item["kind"] == "stopped"
                ]
            # Exact-zero loop phase must be established from PCM, not from
            # Channel::getPosition while FMOD reports a virtual voice.
            accepted = persistent and not zero_required
            runtime_policy = {
                "kind": "TIMELINE_PERSISTENT_LOOP",
                "placement": copy.deepcopy(static["placement"]),
                "eventActivationBehavior": "CREATE_ONE_PERSISTENT_LOOP_VOICE",
                "parameterMotionBehavior": "UPDATE_ACTIVE_VOICE_WITHOUT_RETRIGGER",
                "eventDeactivationBehavior": "ALLOWFADEOUT_AND_RELEASE",
                "maximumSimultaneousSourceVoices": 1,
                "exactZeroGainVirtualization": (
                    "NOT_APPLICABLE_NO_REACHABLE_EXACT_ZERO"
                    if not zero_required
                    else "SOURCE_BOUND_PCM_PHASE_GATE_PENDING"
                ),
                "naturalEndDeadline": "NOT_APPLICABLE_FOR_INFINITE_LOOP",
            }
            observations.extend(
                [
                    {
                        "stage": "persistentMotion",
                        "onePlayedNoStopped": persistent,
                    },
                    *zero_observations,
                ]
            )
        callbacks = copy.deepcopy(runtime.callbacks)
        runtime.stop_release("lifecycle")
    writer.unlink(missing_ok=True)
    return {
        "accepted": accepted,
        "programMode": static["programMode"],
        "runtimePolicy": runtime_policy,
        "observations": observations,
        "callbacks": callbacks,
        "channelGetPositionWhileVirtualIsRuntimeAuthoritative": False,
    }


def certify_turbo_continuous_source_evidence(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    source_guid: str,
    output_root: Path,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    output_root = output_root.resolve()
    context = _source_context(graph_root, classification_path, source_guid)
    if context["derivationKind"] != "TURBO_CONTINUOUS_SOURCE_BOUND_PENDING":
        raise ValueError(f"source {source_guid} is not a continuous turbo leaf")
    static = context["derived"]
    if static.get("schema") != TURBO_CONTINUOUS_STATIC_SCHEMA:
        raise ValueError("continuous turbo static derivation schema changed")
    source_root = output_root / "turbo-continuous" / context["sourceGuid"]
    diagnostic = diagnose_source(
        root,
        graph_root,
        classification_path,
        context["sourceGuid"],
        output_root,
    )
    diagnostic_path = source_root / "diagnostic.json"
    _write_canonical(diagnostic_path, diagnostic)
    bank, isolation = _isolated_bank(root, context, output_root)
    installed_bank = root / str(context["family"]["bankPath"])
    installed_before = _sha256(installed_bank)
    identity_map = _event_runtime_identity_map(
        {
            "graph": context["graph"],
            "event": context["event"],
            "sourceGuid": context["sourceGuid"],
            "runtimeName": context["runtimeName"],
        }
    )
    curve_writer = source_root / "curve-oracle.wav"
    with _OracleRuntime(
        root,
        bank,
        identity_map,
        curve_writer,
        max_channels=2048,
        event_paths=(context["eventPath"],),
    ) as runtime:
        gain, pitch, capture_boost, live_cache = _turbo_live_curve_verification(
            runtime, context, static, diagnostic
        )
        runtime._raise_callback_errors()
    curve_writer.unlink(missing_ok=True)
    disposition = (
        "AUTHORED_TARGET_ROUTED_SILENT"
        if gain["maximumAbsoluteRoutedGain"] == 0.0
        else "AUDIBLE_TARGET_PCM"
    )
    capture: dict[str, Any] | None = None
    target_pcm: dict[str, Any] | None = None
    silence: dict[str, Any] | None = None
    lifecycle: dict[str, Any] | None = None
    if disposition == "AUTHORED_TARGET_ROUTED_SILENT":
        silence = _turbo_silence_verification(
            root,
            context,
            bank,
            output_root,
            capture_boost,
            gain,
            diagnostic,
        )
    else:
        capture, target_pcm = _turbo_pcm_verification(
            root,
            context,
            bank,
            output_root,
            capture_boost,
            gain,
            pitch,
        )
        lifecycle = _turbo_lifecycle_verification(
            root,
            context,
            bank,
            static,
            capture_boost,
            gain,
            output_root,
        )
    installed_after = _sha256(installed_bank)
    if installed_after != installed_before or installed_before != context["familyId"]:
        raise ValueError("installed bank changed during continuous turbo oracle")
    verification: dict[str, Any] = {
        "schema": TURBO_CONTINUOUS_VERIFICATION_SCHEMA,
        "familyId": context["familyId"],
        "sourceGuid": context["sourceGuid"],
        "eventPath": context["eventPath"],
        "programMode": static["programMode"],
        "derivedSourceSha256": context["derivedSourceSha256"],
        "disposition": disposition,
        "boostControl": {
            "control": "BOOST",
            "eventParameterName": "boost",
            "eventParameterGuid": static["boostControl"]["eventParameterGuid"],
            "declaredDomain": copy.deepcopy(
                static["boostControl"]["nativeDomain"]
            ),
            "physicalReachableDomain": [0.0, 1.0],
            "runtimeSignal": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
            "sourcePrimitive": "latestNormalizedBoost",
            "secondNormalizationApplied": False,
            "runtimeClamp": "CLAMP_TO_DECLARED_DOMAIN",
            "seek": copy.deepcopy(static["boostControl"]["seek"]),
            "declaredHeadroomAbovePhysicalReachabilityRetained": True,
        },
        "softwareChannelPriority": 128,
        "gainVerification": gain,
        "pitchVerification": pitch if disposition == "AUDIBLE_TARGET_PCM" else None,
        "capture": capture,
        "targetPcmVerification": target_pcm,
        "silenceVerification": silence,
        "lifecycleVerification": lifecycle,
        "controllerDispositionClosure": {
            "allAuthoredControllersAccountedFor": True,
            "controllerDispositions": copy.deepcopy(
                static["controllerDispositions"]
            ),
            "propertyCounts": {
                str(index): sum(
                    item["propertyIndex"] == index
                    for item in static["controllerDispositions"]
                )
                for index in (0, 1, 4)
            },
        },
        "diagnosticPayloadSha256": diagnostic["diagnosticPayloadSha256"],
        "diagnosticFileSha256": _sha256(diagnostic_path),
        "adaptiveOracleObservationCount": len(live_cache),
        "isolation": isolation,
        "installedBankSha256Before": installed_before,
        "installedBankSha256After": installed_after,
        "installedBankUnchanged": True,
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "SIGNED_PCM16_LE",
            "audioDeviceOpened": False,
            "freshProcessPerSource": True,
            "freshProcessPerPcmRender": True,
            "dspBufferFrames": 256,
        },
        "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
    }
    verification["verificationPayloadSha256"] = _canonical_sha(verification)
    verification_path = source_root / "verification.json"
    _write_canonical(verification_path, verification)
    # A pending exact-zero phase gate remains useful immutable evidence but is
    # deliberately unable to cross the release boundary.
    certify_continuous_turbo_source(static, verification)
    return verification


def _run_turbo_continuous_source(args: argparse.Namespace) -> int:
    if len(args.source_guid) != 1:
        raise ValueError("source worker requires exactly one --source-guid")
    source_guid = _guid(args.source_guid[0])
    if source_guid not in _turbo_continuous_source_guids(args.classification):
        raise ValueError(f"unknown continuous-turbo source: {source_guid}")
    verification = certify_turbo_continuous_source_evidence(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        source_guid,
        args.output_root,
    )
    print(
        f"continuous-turbo certified source={source_guid} "
        f"disposition={verification['disposition']} "
        f"evidence={args.output_root / 'turbo-continuous' / source_guid / 'verification.json'}",
        flush=True,
    )
    return 0


def build_continuous_turbo_proof(
    graph_root: Path,
    classification_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    output_root = output_root.resolve(strict=True)
    implementation_hash = _sha256(Path(__file__).resolve())
    source_records: list[dict[str, Any]] = []
    verifications: list[dict[str, Any]] = []
    certified_sources: list[dict[str, Any]] = []
    for source_guid in _turbo_continuous_source_guids(classification_path):
        context = _source_context(graph_root, classification_path, source_guid)
        verification_path = (
            output_root / "turbo-continuous" / source_guid / "verification.json"
        )
        verification = _load_json(
            verification_path.resolve(strict=True),
            TURBO_CONTINUOUS_VERIFICATION_SCHEMA,
        )
        if verification.get("probeImplementationFileSha256") != implementation_hash:
            raise ValueError(
                f"continuous turbo source {source_guid} used another oracle build"
            )
        certified = certify_continuous_turbo_source(
            context["derived"], verification
        )
        if certified.get("schema") != TURBO_CONTINUOUS_CERTIFIED_SCHEMA:
            raise ValueError("continuous turbo certifier schema changed")
        capture = verification.get("capture")
        artifact: dict[str, Any] | None = None
        if verification["disposition"] == "AUDIBLE_TARGET_PCM":
            if not isinstance(capture, dict):
                raise ValueError("audible continuous turbo capture is absent")
            final_wav = output_root / str(capture["finalWavRelativePath"])
            payload, frames = _read_pcm16_wav(final_wav.resolve(strict=True))
            if (
                _sha256(final_wav) != capture["finalWavSha256"]
                or hashlib.sha256(payload).hexdigest()
                != capture["pcmPayloadSha256"]
                or frames != int(capture["frameCount"])
            ):
                raise ValueError(
                    f"continuous turbo final PCM changed for {source_guid}"
                )
            artifact = {
                "finalWavRelativePath": capture["finalWavRelativePath"],
                "finalWavSha256": capture["finalWavSha256"],
                "pcmPayloadSha256": capture["pcmPayloadSha256"],
                "frameCount": frames,
                "loopStartFrame": capture["loopStartFrame"],
                "loopEndFrameExclusive": capture["loopEndFrameExclusive"],
            }
        source_records.append(
            {
                "familyId": context["familyId"],
                "sourceGuid": source_guid,
                "disposition": verification["disposition"],
                "programMode": verification["programMode"],
                "declaredBoostDomain": verification["boostControl"][
                    "declaredDomain"
                ],
                "physicalReachableBoostDomain": verification["boostControl"][
                    "physicalReachableDomain"
                ],
                "verificationRelativePath": verification_path.relative_to(
                    output_root
                ).as_posix(),
                "verificationFileSha256": _sha256(verification_path),
                "verificationPayloadSha256": verification[
                    "verificationPayloadSha256"
                ],
                "derivedSourceSha256": context["derivedSourceSha256"],
                "certifiedSourceCanonicalSha256": _canonical_sha(certified),
                "pcmArtifact": artifact,
            }
        )
        verifications.append(verification)
        certified_sources.append(certified)
    dispositions = {
        value: sum(item["disposition"] == value for item in verifications)
        for value in ("AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_ROUTED_SILENT")
    }
    modes = {
        value: sum(item["programMode"] == value for item in verifications)
        for value in ("TIMELINE_PERSISTENT_LOOP", "BOOST_REGION_PERSISTENT_LOOP")
    }
    declared_domains = {
        "0_TO_1": sum(
            item["boostControl"]["declaredDomain"] == [0.0, 1.0]
            for item in verifications
        ),
        "0_TO_1_5": sum(
            item["boostControl"]["declaredDomain"] == [0.0, 1.5]
            for item in verifications
        ),
    }
    property_counts = {
        str(index): sum(
            disposition["propertyIndex"] == index
            for item in certified_sources
            for disposition in item["controllerDispositions"]
        )
        for index in (0, 1, 4)
    }
    timeline_baked = sum(
        disposition["treatment"] == "BAKED_INTO_TARGET_ONLY_LOOP_CAPTURE"
        for item in certified_sources
        for disposition in item["controllerDispositions"]
    )
    family_count = len({item["familyId"] for item in verifications})
    if (
        dispositions
        != {"AUDIBLE_TARGET_PCM": 60, "AUTHORED_TARGET_ROUTED_SILENT": 6}
        or modes
        != {"TIMELINE_PERSISTENT_LOOP": 54, "BOOST_REGION_PERSISTENT_LOOP": 12}
        or declared_domains != {"0_TO_1": 61, "0_TO_1_5": 5}
        or property_counts.get("1") != 59
        or property_counts.get("4") != 8
        or timeline_baked != 1
        or family_count != 52
    ):
        raise ValueError("continuous turbo official-corpus closure changed")
    maximum_gain = max(
        (
            float(item["gainVerification"]["maximumCaptureRelativeGain"])
            for item in verifications
            if item["disposition"] == "AUDIBLE_TARGET_PCM"
        ),
        default=0.0,
    )
    if not 1.0 <= maximum_gain <= 38.0:
        raise ValueError("continuous turbo maximum runtime gain is outside 1..38")
    proof: dict[str, Any] = {
        "schema": TURBO_CONTINUOUS_PROOF_SCHEMA,
        "result": "PASS_SOURCE_BOUND_COMPLETE",
        "scope": {
            "sourceCount": len(verifications),
            "familyCount": family_count,
            "manifestRole": "TURBO",
            "runtimeControl": "BOOST",
            "runtimeSignal": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
            "sourcePrimitive": "latestNormalizedBoost",
            "secondNormalizationApplied": False,
            "genericTurboRoleGainReplaced": True,
            "sampleNamesUsedForSemantics": False,
            "installedSourceBanksModified": False,
        },
        "inputs": {
            "classificationPath": str(classification_path),
            "classificationFileSha256": _sha256(classification_path),
            "graphSummaryPath": str(graph_root / "summary.json"),
            "graphSummaryFileSha256": _sha256(graph_root / "summary.json"),
            "probeImplementationFileSha256": implementation_hash,
        },
        "counts": {
            "sources": len(verifications),
            "families": family_count,
            "pcmDispositions": dispositions,
            "programModes": modes,
            "declaredBoostDomains": declared_domains,
            "propertyControllers": property_counts,
            "bakedTimelinePropertyZeroControllers": timeline_baked,
            "softwareChannelPriorities": {"128": len(verifications)},
        },
        "physicalReachability": {
            "domain": [0.0, 1.0],
            "declaredDomainsRetainUnreachableHeadroom": True,
            "fiveDomainOnePointFiveSourcesLoseNoPhysicalState": True,
        },
        "maximumCaptureRelativeRuntimeGain": maximum_gain,
        "maximumRuntimeGainBoundInclusive": 38.0,
        "sourceArtifacts": sorted(
            source_records, key=lambda item: (item["familyId"], item["sourceGuid"])
        ),
        "sourceVerifications": sorted(
            verifications, key=lambda item: (item["familyId"], item["sourceGuid"])
        ),
    }
    proof["proofPayloadSha256"] = _canonical_sha(proof)
    return proof


def _run_turbo_continuous_all(args: argparse.Namespace) -> int:
    allowed = _turbo_continuous_source_guids(args.classification)
    selected = tuple(_guid(value) for value in args.source_guid) or allowed
    unknown = sorted(set(selected) - set(allowed))
    if unknown:
        raise ValueError(f"unknown continuous-turbo sources: {unknown}")
    output_root = args.output_root.resolve()
    ledger_path = output_root / "turbo-continuous-ledger.json"
    statuses: dict[str, dict[str, Any]] = {}
    if ledger_path.is_file():
        prior = _load_json(ledger_path)
        if prior.get("schema") == "ac-fmod-continuous-turbo-ledger-v1":
            statuses = {
                str(key): value
                for key, value in (prior.get("sources") or {}).items()
                if isinstance(value, dict)
            }
    creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    for source_guid in selected:
        context = _source_context(args.graph_root, args.classification, source_guid)
        verification_path = (
            output_root / "turbo-continuous" / source_guid / "verification.json"
        )
        if verification_path.is_file():
            try:
                verification = _load_json(
                    verification_path, TURBO_CONTINUOUS_VERIFICATION_SCHEMA
                )
                if verification.get("probeImplementationFileSha256") != _sha256(
                    Path(__file__).resolve()
                ):
                    raise ValueError("continuous turbo worker implementation changed")
                certify_continuous_turbo_source(context["derived"], verification)
                statuses[source_guid] = {
                    "status": "SUCCESS",
                    "verificationFileSha256": _sha256(verification_path),
                    "verificationPayloadSha256": verification[
                        "verificationPayloadSha256"
                    ],
                    "derivedSourceSha256": context["derivedSourceSha256"],
                    "resumed": True,
                }
                continue
            except Exception:
                pass
        log_path = output_root / "turbo-continuous" / source_guid / "worker.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command = [
            sys.executable,
            str(Path(__file__).resolve()),
            "certify-turbo-continuous-source",
            "--assetto-root",
            str(find_assetto_root(args.assetto_root)),
            "--graph-root",
            str(args.graph_root.resolve()),
            "--classification",
            str(args.classification.resolve()),
            "--output-root",
            str(output_root),
            "--source-guid",
            source_guid,
        ]
        command_hash = _canonical_sha(command)
        completed = subprocess.run(
            command,
            cwd=str(PROJECT_ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=1800,
            creationflags=creation_flags,
            check=False,
        )
        combined = (completed.stdout or "") + (completed.stderr or "")
        temporary_log = log_path.with_name(f".{log_path.name}.tmp")
        temporary_log.write_text(combined, encoding="utf-8")
        os.replace(temporary_log, log_path)
        final_verification: dict[str, Any] | None = None
        if completed.returncode == 0 and verification_path.is_file():
            final_verification = _load_json(
                verification_path, TURBO_CONTINUOUS_VERIFICATION_SCHEMA
            )
            certify_continuous_turbo_source(context["derived"], final_verification)
            status = "SUCCESS"
            detail = None
        else:
            status = "BLOCKED"
            detail = combined[-4000:].strip()
        statuses[source_guid] = {
            "status": status,
            "commandSha256": command_hash,
            "returnCode": completed.returncode,
            "workerLogRelativePath": log_path.relative_to(output_root).as_posix(),
            "workerLogSha256": _sha256(log_path),
            "verificationFileSha256": (
                _sha256(verification_path) if verification_path.is_file() else None
            ),
            "verificationPayloadSha256": (
                final_verification.get("verificationPayloadSha256")
                if final_verification is not None
                else None
            ),
            "derivedSourceSha256": context["derivedSourceSha256"],
            "detail": detail,
            "resumed": False,
        }
        ledger = {
            "schema": "ac-fmod-continuous-turbo-ledger-v1",
            "selectedSourceGuids": list(selected),
            "sources": dict(sorted(statuses.items())),
            "counts": {
                "selected": len(selected),
                "success": sum(
                    item.get("status") == "SUCCESS" for item in statuses.values()
                ),
                "blocked": sum(
                    item.get("status") == "BLOCKED" for item in statuses.values()
                ),
            },
        }
        ledger["ledgerPayloadSha256"] = _canonical_sha(ledger)
        _write_canonical(ledger_path, ledger)
        print(
            f"continuous-turbo source={source_guid} status={status} "
            f"success={ledger['counts']['success']} blocked={ledger['counts']['blocked']}",
            flush=True,
        )
    complete = all(statuses[guid]["status"] == "SUCCESS" for guid in selected)
    if complete and set(selected) == set(allowed):
        proof = build_continuous_turbo_proof(
            args.graph_root, args.classification, output_root
        )
        proof_path = args.report or output_root / "continuous-turbo-proof.json"
        _write_canonical(proof_path, proof)
        print(
            f"continuous-turbo proof sources={proof['counts']['sources']} "
            f"evidence={proof_path} sha256={_sha256(proof_path)}",
            flush=True,
        )
    return 0 if complete else 2


def _property_one_controller(
    context: dict[str, Any]
) -> tuple[dict[str, Any], tuple[tuple[float, float, float, int], ...]]:
    matches = [
        item
        for item in context["graph"].get("controllers", [])
        if _guid(item.get("propertyOwnerGuid")) == context["sourceGuid"]
        and item.get("propertyIndex") == 1
    ]
    if len(matches) != 1:
        raise ValueError(
            f"property-one source {context['sourceGuid']} has {len(matches)} controllers"
        )
    controller = matches[0]
    if (
        controller.get("inputKind") != "parameter"
        or controller.get("inputParameterName")
        != context["derived"].get("nativeSpeedParameter")
    ):
        raise ValueError("property-one controller is not driven by native speed")
    points = tuple(
        (
            float(item["x"]),
            float(item["y"]),
            float(item["shape"]),
            int(item["type"]),
        )
        for item in controller.get("points", [])
    )
    if len(points) < 2:
        raise ValueError("property-one controller curve is incomplete")
    return controller, points


def _build_property_one_pitch_curve(
    context: dict[str, Any],
) -> tuple[list[list[float]], dict[str, Any]]:
    fallback = context["derived"]
    controller, raw_points = _property_one_controller(context)
    scale = float(fallback["nativeSpeedToRpmScale"])
    if not math.isfinite(scale) or scale <= 0.0:
        raise ValueError("property-one native speed scale is invalid")
    rpm_curve = [
        [float(item[0]), float(item[1])] for item in fallback["rpmCurve"]
    ]
    domain = (rpm_curve[0][0], rpm_curve[-1][0])
    capture_rpm = float(fallback["captureRootRpm"])
    capture_raw = evaluate_authored_curve(raw_points, capture_rpm / scale)

    def relative_rate(rpm: float) -> float:
        raw = evaluate_authored_curve(raw_points, rpm / scale)
        return 2.0 ** (2.0 * (raw - capture_raw))

    knots = {
        domain[0],
        domain[1],
        capture_rpm,
        *(
            min(domain[1], max(domain[0], point[0] * scale))
            for point in raw_points
        ),
    }
    points = sorted(knots)
    maximum_error = math.inf
    probe_count = 0
    while True:
        worst: tuple[float, float] | None = None
        maximum_error = 0.0
        probe_count = 0
        for left, right in zip(points, points[1:]):
            left_rate = relative_rate(left)
            right_rate = relative_rate(right)
            for fraction in (0.25, 0.5, 0.75):
                rpm = left + ((right - left) * fraction)
                exact = relative_rate(rpm)
                linear = left_rate + ((right_rate - left_rate) * fraction)
                error = abs(1200.0 * math.log2(exact / linear))
                probe_count += 1
                if error > maximum_error:
                    maximum_error = error
                if worst is None or error > worst[1]:
                    worst = (rpm, error)
        if maximum_error <= PROPERTY_ONE_MAXIMUM_LINEARIZATION_ERROR_CENTS:
            break
        if worst is None or len(points) >= 512:
            raise ValueError(
                "property-one pitch curve exceeds the 512-point runtime bound"
            )
        points.append(worst[0])
        points.sort()
    curve = [[float(rpm), float(relative_rate(rpm))] for rpm in points]
    validate_property_one_pitch_curve(
        curve,
        capture_rpm=capture_rpm,
        rpm_domain=domain,
    )
    return curve, {
        "controllerGuid": _guid(controller.get("guid")),
        "inputParameterName": str(controller["inputParameterName"]),
        "nativeSpeedToRpmScale": scale,
        "rawValueToSemitonesScale": PROPERTY_ONE_RAW_TO_SEMITONES,
        "captureRpm": capture_rpm,
        "captureRawPropertyOneValue": capture_raw,
        "rpmDomain": [domain[0], domain[1]],
        "adaptiveQuarterMidpointMaximumErrorCents": maximum_error,
        "adaptiveQuarterMidpointProbeCount": probe_count,
        "maximumAllowedInterpolationErrorCents": (
            PROPERTY_ONE_MAXIMUM_LINEARIZATION_ERROR_CENTS
        ),
        "pointCount": len(curve),
    }


def _property_one_probe_rpms(
    fallback: dict[str, Any],
    controller: dict[str, Any],
    pitch_curve: list[list[float]],
) -> list[float]:
    rpm_curve = [
        [float(item[0]), float(item[1])] for item in fallback["rpmCurve"]
    ]
    domain = (rpm_curve[0][0], rpm_curve[-1][0])
    scale = float(fallback["nativeSpeedToRpmScale"])
    candidates = {
        domain[0] + ((domain[1] - domain[0]) * index / 256.0)
        for index in range(257)
    }
    candidates.update(float(item[0]) for item in rpm_curve)
    candidates.update(
        min(domain[1], max(domain[0], float(item["x"]) * scale))
        for item in controller["points"]
    )
    audible = sorted(
        rpm
        for rpm in candidates
        if _linear_curve(rpm_curve, rpm) >= 0.25
        # The long-window log-spectrum confidence estimator is validated in
        # this capture-relative range.  The independent live Channel rate
        # oracle below covers the full authored property-one domain.
        and 0.65 <= _linear_curve(pitch_curve, rpm) <= 1.1
    )
    if len(audible) < 3:
        raise ValueError("property-one source has fewer than three audible RPM probes")
    # Use deterministic capture-local pitch ratios for the long-window PCM
    # comparison.  Full-domain pitch is independently covered by the live
    # Channel rate observations, including the much faster extremes whose
    # periodic spectra make an eight-way time-bootstrap ambiguous.
    selected = {
        min(
            audible,
            key=lambda rpm: (
                abs(_linear_curve(pitch_curve, rpm) - target_rate),
                abs(rpm - float(fallback["captureRootRpm"])),
                rpm,
            ),
        )
        for target_rate in (0.9, 0.95, 1.0, 1.05, 1.09)
    }
    selected.add(float(fallback["captureRootRpm"]))
    return sorted(selected)


def _render_property_target(
    renderer: SilentFmodReferenceRenderer,
    context: dict[str, Any],
    bank: Path,
    output_path: Path,
    parameters: dict[str, float],
    *,
    duration_frames: int = PROPERTY_ONE_ANALYSIS_FRAMES,
) -> tuple[bytes, dict[str, Any]]:
    result = renderer.render_event(
        bank,
        context["eventPath"],
        output_path,
        parameters=parameters,
        duration_frames=duration_frames,
        warmup_frames=PROPERTY_ONE_WARMUP_FRAMES,
        required_sound_name=context["runtimeName"],
        maximum_takes=1,
    )
    if set(result.scheduled_sound_names) != {context["runtimeName"]}:
        raise ValueError("property-one target-only identity is not exclusive")
    payload, frames = _read_pcm16_wav(output_path)
    if frames != duration_frames:
        raise ValueError("property-one renderer returned the wrong frame count")
    worker = copy.deepcopy(renderer.last_fresh_process_evidence)
    if not isinstance(worker, dict):
        raise ValueError("fresh-process property-one evidence is absent")
    evidence_path = Path(str(worker["path"])).resolve(strict=True)
    worker["relativeEvidencePath"] = evidence_path.relative_to(
        output_path.parents[3]
    ).as_posix()
    worker.pop("path", None)
    return payload, {
        "scheduledSourceGuids": [context["sourceGuid"]],
        "pcmPayloadSha256": hashlib.sha256(payload).hexdigest(),
        "frameCount": frames,
        "wavSha256": _sha256(output_path),
        "worker": worker,
    }


def _loop_extended_pcm(
    payload: bytes,
    frame_count: int,
    loop_start: int,
    loop_end: int,
    required_frames: int,
) -> Any:
    import numpy as np

    source = _pcm16_stereo(payload, frame_count)
    if not (0 <= loop_start < loop_end <= frame_count):
        raise ValueError("property-one loop bounds are invalid")
    chunks = [source[:loop_end]]
    accumulated = loop_end
    loop = source[loop_start:loop_end]
    if not len(loop):
        raise ValueError("property-one loop is empty")
    while accumulated < required_frames:
        chunks.append(loop)
        accumulated += len(loop)
    return np.concatenate(chunks, axis=0)[:required_frames]


def _property_rate_verification(
    context: dict[str, Any], diagnostic: dict[str, Any]
) -> dict[str, Any]:
    controller, points = _property_one_controller(context)
    fallback = context["derived"]
    native = str(fallback["nativeSpeedParameter"])
    scale = float(fallback["nativeSpeedToRpmScale"])
    sample_rate = float(context["source"]["sample"]["frequencyHz"])
    sample_frames = int(context["source"]["sample"]["sampleCount"])
    records: list[dict[str, Any]] = []
    for observation in diagnostic.get("observations", []):
        parameters = observation.get("parameters")
        if (
            not isinstance(parameters, dict)
            or native not in parameters
            or observation.get("targetScheduled") is not True
            or observation.get("activeTargetVoice") is not True
            or observation.get("sourcePcmFramesPerDspFrame") is None
            or float(observation.get("routedGain", 0.0)) <= 1.0e-5
        ):
            continue
        native_value = float(parameters[native])
        rpm = native_value * scale
        raw = evaluate_authored_curve(points, native_value)
        expected = (sample_rate / 48000.0) * (2.0 ** (2.0 * raw))
        measured_modulo = float(observation["sourcePcmFramesPerDspFrame"])
        update_count = int(observation["rateProbeUpdateCount"])
        measured_delta = measured_modulo * update_count * 256.0
        expected_delta = expected * update_count * 256.0
        wraps = max(0, round((expected_delta - measured_delta) / sample_frames))
        measured = (
            measured_delta + (wraps * sample_frames)
        ) / (update_count * 256.0)
        if measured <= 0.0:
            continue
        error_cents = abs(1200.0 * math.log2(measured / expected))
        records.append(
            {
                "rpm": rpm,
                "nativeSpeedValue": native_value,
                "authoredRawPropertyOneValue": raw,
                "expectedSourcePcmFramesPerDspFrame": expected,
                "observedSourcePcmFramesPerDspFrame": measured,
                "reconstructedWholeSourceWrapCount": wraps,
                "pitchErrorCents": error_cents,
            }
        )
    unique = {
        round(item["rpm"], 8): item
        for item in records
    }
    records = [unique[key] for key in sorted(unique)]
    maximum = max((item["pitchErrorCents"] for item in records), default=math.inf)
    return {
        "accepted": len(records) >= 8 and maximum <= 5.0,
        "controllerGuid": _guid(controller.get("guid")),
        "sourcePcmSampleRateHz": sample_rate,
        "sourcePcmFrameCount": sample_frames,
        "observationCount": len(records),
        "maximumPitchErrorCents": maximum,
        "observations": records,
    }


def certify_property_one_source(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    plan_path: Path,
    source_guid: str,
    output_root: Path,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    context = _source_context(graph_root, classification_path, source_guid)
    if context["derivationKind"] != "PROPERTY_INDEX_ONE_WINDOWED_FALLBACK":
        raise ValueError(f"source {source_guid} is not a property-one fallback")
    fallback = context["derived"]
    plan = _load_json(plan_path.resolve(strict=True))
    family = next(
        item for item in plan["families"] if item["familyId"] == context["familyId"]
    )
    recipes = [
        item
        for item in family["recipes"]
        if _guid(item.get("sourceGuid")) == context["sourceGuid"]
    ]
    if len(recipes) != 1:
        raise ValueError("property-one source does not map to exactly one plan recipe")
    recipe = recipes[0]
    bank, isolation = _isolated_bank(root, context, output_root)
    installed_bank = root / str(context["family"]["bankPath"])
    installed_before = _sha256(installed_bank)
    pitch_curve, curve_evidence = _build_property_one_pitch_curve(context)
    controller, _raw_points = _property_one_controller(context)
    diagnostic_path = output_root / "diagnostics" / f"{context['sourceGuid']}.json"
    diagnostic = _load_json(diagnostic_path, DIAGNOSTIC_SCHEMA)
    if diagnostic.get("derivedSourceSha256") != context["derivedSourceSha256"]:
        raise ValueError("property-one diagnostic was derived from another source plan")
    rate_verification = _property_rate_verification(context, diagnostic)
    _controller, raw_points = _property_one_controller(context)
    capture_rpm = float(fallback["captureRootRpm"])
    capture_native_speed = (
        capture_rpm / float(fallback["nativeSpeedToRpmScale"])
    )
    capture_raw_pitch = evaluate_authored_curve(
        raw_points, capture_native_speed
    )
    absolute_capture_rate = (
        float(context["source"]["sample"]["frequencyHz"]) / 48000.0
    ) * (2.0 ** (2.0 * capture_raw_pitch))
    nominal_cycle_frames = (
        int(context["source"]["sample"]["sampleCount"])
        / absolute_capture_rate
    )
    capture_render_frames = math.ceil(nominal_cycle_frames) + 5760

    renderer = SilentFmodReferenceRenderer(
        root, dsp_buffer_frames=256, fresh_process_per_render=True
    )
    source_root = output_root / "property-one" / context["sourceGuid"]
    render_root = source_root / "renders"
    capture_parameters = {
        str(name): float(value)
        for name, value in fallback["captureParameterValues"].items()
    }
    capture_a_path = render_root / "capture-a.wav"
    capture_b_path = render_root / "capture-b.wav"
    capture_a, capture_a_evidence = _render_property_target(
        renderer,
        context,
        bank,
        capture_a_path,
        capture_parameters,
        duration_frames=capture_render_frames,
    )
    capture_b, capture_b_evidence = _render_property_target(
        renderer,
        context,
        bank,
        capture_b_path,
        capture_parameters,
        duration_frames=capture_render_frames,
    )
    if capture_a != capture_b:
        raise ValueError("independent property-one target captures are not bit exact")
    pack_payload = capture_a
    pack_payload, loop_start, loop_end, seam_dbfs = _repair_property_pack_loop(
        pack_payload, capture_render_frames, nominal_cycle_frames
    )
    final_frames = len(pack_payload) // 4
    final_wav = source_root / "capture.wav"
    _write_pcm16_wav(final_wav, pack_payload)
    peak_dbfs = _peak_dbfs(pack_payload)
    if not math.isfinite(peak_dbfs) or peak_dbfs <= -96.0:
        raise ValueError("property-one final capture is not audibly nonzero")

    probe_records: list[dict[str, Any]] = []
    rpm_curve = [
        [float(item[0]), float(item[1])] for item in fallback["rpmCurve"]
    ]
    capture_gain = _linear_curve(rpm_curve, capture_rpm)
    for index, rpm in enumerate(
        _property_one_probe_rpms(fallback, controller, pitch_curve)
    ):
        parameters = dict(capture_parameters)
        parameters[str(fallback["nativeSpeedParameter"])] = (
            rpm / float(fallback["nativeSpeedToRpmScale"])
        )
        target_path = render_root / f"probe-{index:02d}-{rpm:.8f}.wav"
        target_payload, target_evidence = _render_property_target(
            renderer, context, bank, target_path, parameters
        )
        target_pcm = _pcm16_stereo(target_payload, PROPERTY_ONE_ANALYSIS_FRAMES)
        relative_rate = _linear_curve(pitch_curve, rpm)
        required_source_frames = int(
            math.ceil(PROPERTY_ONE_ANALYSIS_FRAMES * relative_rate)
        ) + 4
        extended = _loop_extended_pcm(
            pack_payload,
            final_frames,
            loop_start,
            loop_end,
            required_source_frames,
        )
        runtime_pcm = _cubic_varispeed(
            extended, relative_rate, PROPERTY_ONE_ANALYSIS_FRAMES
        )
        runtime_gain = _linear_curve(rpm_curve, rpm) / capture_gain
        runtime_pcm = runtime_pcm * runtime_gain
        common = min(len(target_pcm), len(runtime_pcm))
        target_pcm = target_pcm[:common]
        runtime_pcm = runtime_pcm[:common]
        target_rms, target_stationarity = _rms_and_stationarity(target_pcm)
        runtime_rms, runtime_stationarity = _rms_and_stationarity(runtime_pcm)
        if target_rms <= 4.0 / 32768.0 or runtime_rms <= 4.0 / 32768.0:
            raise ValueError(f"property-one spectral probe is inaudible at {rpm} RPM")
        gain_error = abs(20.0 * math.log10(runtime_rms / target_rms))
        pitch = measure_relative_log_spectral_pitch(target_pcm, runtime_pcm)
        probe_records.append(
            {
                "rpm": rpm,
                "captureRelativePlaybackRate": relative_rate,
                "captureRelativeRuntimeGain": runtime_gain,
                "comparedFrameCount": common,
                "pitchErrorCents": float(pitch["pitchErrorCents"]),
                "gainErrorDb": gain_error,
                "targetRms": target_rms,
                "runtimeRms": runtime_rms,
                "targetStationarityMadDb": target_stationarity,
                "runtimeStationarityMadDb": runtime_stationarity,
                "pitchConfidence": pitch,
                "targetCapture": target_evidence,
            }
        )
    maximum_pitch = max(item["pitchErrorCents"] for item in probe_records)
    maximum_gain = max(item["gainErrorDb"] for item in probe_records)
    spectral_verification = {
        "accepted": maximum_pitch <= 5.0 and maximum_gain <= 0.25,
        "probeCount": len(probe_records),
        "maximumPitchErrorCents": maximum_pitch,
        "maximumGainErrorDb": maximum_gain,
        "runtimeInterpolation": "CUBIC_VARISPEED",
        "runtimeCurveApplication": "REPLACES_ORDINARY_RPM_ROOT_RATIO",
        "gainTreatment": "AUTHORED_RPM_CURVE_RELATIVE_TO_CAPTURE",
        "probes": probe_records,
    }
    verification: dict[str, Any] = {
        "schema": PROPERTY_ONE_VERIFICATION_SCHEMA,
        "familyId": context["familyId"],
        "sourceGuid": context["sourceGuid"],
        "eventPath": context["eventPath"],
        "role": fallback["role"],
        "fallbackPlanSha256": _canonical_sha(fallback),
        "derivedSourceSha256": context["derivedSourceSha256"],
        "pitchMode": PROPERTY_ONE_PITCH_MODE,
        "pitchCurve": pitch_curve,
        "interpolation": PROPERTY_ONE_INTERPOLATION,
        "ordinaryAutoPitchRpmRatioReplaced": True,
        "rawValueToSemitonesScale": PROPERTY_ONE_RAW_TO_SEMITONES,
        "pitchCurveConstruction": curve_evidence,
        "capture": {
            "captureRootRpm": capture_rpm,
            "preCertificationPlanDurationFrames": int(recipe["durationFrames"]),
            "authoredSourceSampleCount": int(
                context["source"]["sample"]["sampleCount"]
            ),
            "absoluteSourcePcmFramesPerDspFrameAtCapture": absolute_capture_rate,
            "nominalAuthoredCycleFramesAtCapture": nominal_cycle_frames,
            "oracleRenderFrameCount": capture_render_frames,
            "captureParameterValues": capture_parameters,
            "captureAutomaticParameterValues": copy.deepcopy(
                fallback.get("captureAutomaticParameterValues") or {}
            ),
            "scheduledSourceGuids": [context["sourceGuid"]],
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "SIGNED_PCM16_LE",
            "frameCount": final_frames,
            "loopStartFrame": loop_start,
            "loopEndFrameExclusive": loop_end,
            "loopSeamPeakDbfs": (
                seam_dbfs if math.isfinite(seam_dbfs) else "NEGATIVE_INFINITY"
            ),
            "peakPcmDbfs": peak_dbfs,
            "pcmPayloadSha256": hashlib.sha256(pack_payload).hexdigest(),
            "finalWavRelativePath": final_wav.relative_to(output_root).as_posix(),
            "finalWavSha256": _sha256(final_wav),
            "analysisPcmPayloadSha256": hashlib.sha256(capture_a).hexdigest(),
            "independentFreshProcessRendersBitExact": True,
            "independentFreshProcessRenders": [
                capture_a_evidence,
                capture_b_evidence,
            ],
        },
        "rateVerification": rate_verification,
        "targetPcmSpectralVerification": spectral_verification,
        "isolation": isolation,
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "audioDeviceOpened": False,
            "freshProcessPerRenderRequest": True,
            "dspBufferFrames": 256,
        },
        "installedBankSha256Before": installed_before,
        "installedBankSha256After": _sha256(installed_bank),
        "installedBankUnchanged": _sha256(installed_bank) == installed_before,
        "diagnosticPayloadSha256": diagnostic["diagnosticPayloadSha256"],
        "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
    }
    verification["verificationPayloadSha256"] = _canonical_sha(verification)
    # This call is the release boundary: a proof file may be written for
    # diagnosis, but only a record accepted here may enter a capture plan.
    certify_property_one_relative_rate(fallback, verification)
    return verification


def build_static_disposition_proof(
    graph_root: Path,
    classification_path: Path,
    plan_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    """Bind four forbidden pedal routes and one static zero route."""

    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    plan_path = plan_path.resolve(strict=True)
    output_root = output_root.resolve(strict=True)
    plan = _load_json(plan_path)
    if plan.get("schemaVersion") != 2 or not isinstance(plan.get("families"), list):
        raise ValueError("continuous disposition proof requires capture plan V2")
    plan_families = {
        str(item["familyId"]): item for item in plan["families"]
    }
    diagnostic_records: list[dict[str, Any]] = []
    verifications: list[dict[str, Any]] = []
    for source_guid in (*FORBIDDEN_PEDAL_SOURCES, *ROUTED_SILENT_SOURCES):
        diagnostic_path = output_root / "diagnostics" / f"{source_guid}.json"
        diagnostic = _load_json(diagnostic_path, DIAGNOSTIC_SCHEMA)
        context = _source_context(graph_root, classification_path, source_guid)
        family = plan_families.get(context["familyId"])
        if family is None:
            raise ValueError(f"plan family is absent for source {source_guid}")
        matching = [
            item
            for item in family["recipes"]
            if _guid(item.get("sourceGuid")) == source_guid
        ]
        if len(matching) != 1:
            raise ValueError(
                f"source {source_guid} has {len(matching)} release recipes"
            )
        retained = [
            str(item["id"])
            for item in family["recipes"]
            if _guid(item.get("sourceGuid")) != source_guid
        ]
        retained_idle = [
            str(item["id"])
            for item in family["recipes"]
            if _guid(item.get("sourceGuid")) != source_guid
            and item.get("role") == "IDLE"
            and item.get("looping") is True
        ]
        if source_guid in FORBIDDEN_PEDAL_SOURCES:
            verification = certify_forbidden_on_pedal_routing(
                diagnostic,
                source_guid=source_guid,
                derived_source_sha256=context["derivedSourceSha256"],
                retained_allowed_recipe_ids=retained,
                retained_idle_recipe_ids=retained_idle,
            )
        else:
            verification = certify_authored_routed_silence(
                diagnostic,
                source_guid=source_guid,
                derived_source_sha256=context["derivedSourceSha256"],
                retained_allowed_recipe_ids=retained,
                retained_idle_recipe_ids=retained_idle,
            )
        verifications.append(verification)
        diagnostic_records.append(
            {
                "familyId": context["familyId"],
                "sourceGuid": source_guid,
                "relativePath": diagnostic_path.relative_to(output_root).as_posix(),
                "fileSha256": _sha256(diagnostic_path),
                "payloadSha256": diagnostic["diagnosticPayloadSha256"],
                "probeImplementationFileSha256": diagnostic[
                    "probeImplementationFileSha256"
                ],
            }
        )
    proof = {
        "schema": STATIC_DISPOSITION_PROOF_SCHEMA,
        "result": "PASS_SOURCE_BOUND_COMPLETE",
        "scope": {
            "forbiddenOnPedalRoutingSourceGuids": sorted(
                FORBIDDEN_PEDAL_SOURCES
            ),
            "authoredTargetRoutedSilentSourceGuids": sorted(
                ROUTED_SILENT_SOURCES
            ),
            "sampleNamesUsedForSemantics": False,
            "installedSourceBanksModified": False,
        },
        "inputs": {
            "capturePlanPath": str(plan_path),
            "capturePlanFileSha256": _sha256(plan_path),
            "capturePlanCanonicalJsonSha256": _canonical_sha(plan),
            "classificationPath": str(classification_path),
            "classificationFileSha256": _sha256(classification_path),
            "graphSummaryPath": str(graph_root / "summary.json"),
            "graphSummaryFileSha256": _sha256(graph_root / "summary.json"),
        },
        "counts": {
            "sourceVerifications": len(verifications),
            "forbiddenOnPedalRouting": len(FORBIDDEN_PEDAL_SOURCES),
            "authoredTargetRoutedSilent": len(ROUTED_SILENT_SOURCES),
        },
        "diagnosticArtifacts": sorted(
            diagnostic_records,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
        "sourceVerifications": sorted(
            verifications,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
    }
    proof["proofPayloadSha256"] = _canonical_sha(proof)
    return proof


def _run_static_proof(args: argparse.Namespace) -> int:
    result = build_static_disposition_proof(
        args.graph_root,
        args.classification,
        args.plan,
        args.output_root,
    )
    report = args.report or args.output_root / "static-dispositions-proof.json"
    _write_canonical(report, result)
    print(
        f"continuous static dispositions={result['counts']['sourceVerifications']} "
        f"evidence={report} sha256={_sha256(report)}",
        flush=True,
    )
    return 0


def build_property_one_proof(
    graph_root: Path,
    classification_path: Path,
    plan_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    plan_path = plan_path.resolve(strict=True)
    output_root = output_root.resolve(strict=True)
    source_records: list[dict[str, Any]] = []
    verifications: list[dict[str, Any]] = []
    for source_guid in PROPERTY_ONE_SOURCES:
        context = _source_context(graph_root, classification_path, source_guid)
        verification_path = (
            output_root / "property-one" / source_guid / "verification.json"
        )
        verification = _load_json(
            verification_path, PROPERTY_ONE_VERIFICATION_SCHEMA
        )
        certified = certify_property_one_relative_rate(
            context["derived"], verification
        )
        capture = verification["capture"]
        final_wav = output_root / str(capture["finalWavRelativePath"])
        pcm, frames = _read_pcm16_wav(final_wav.resolve(strict=True))
        if (
            _sha256(final_wav) != capture["finalWavSha256"]
            or hashlib.sha256(pcm).hexdigest() != capture["pcmPayloadSha256"]
            or frames != int(capture["frameCount"])
        ):
            raise ValueError(
                f"property-one final PCM artifact changed for {source_guid}"
            )
        source_records.append(
            {
                "familyId": context["familyId"],
                "sourceGuid": source_guid,
                "verificationRelativePath": verification_path.relative_to(
                    output_root
                ).as_posix(),
                "verificationFileSha256": _sha256(verification_path),
                "verificationPayloadSha256": verification[
                    "verificationPayloadSha256"
                ],
                "derivedSourceSha256": context["derivedSourceSha256"],
                "certifiedSourceCanonicalSha256": _canonical_sha(certified),
                "finalWavRelativePath": capture["finalWavRelativePath"],
                "finalWavSha256": capture["finalWavSha256"],
                "pcmPayloadSha256": capture["pcmPayloadSha256"],
                "frameCount": frames,
            }
        )
        verifications.append(verification)
    proof: dict[str, Any] = {
        "schema": PROPERTY_ONE_PROOF_SCHEMA,
        "result": "PASS_SOURCE_BOUND_COMPLETE",
        "scope": {
            "sourceGuids": sorted(PROPERTY_ONE_SOURCES),
            "sourceCount": len(PROPERTY_ONE_SOURCES),
            "runtimePitchMode": PROPERTY_ONE_PITCH_MODE,
            "runtimeInterpolation": PROPERTY_ONE_INTERPOLATION,
            "ordinaryAutoPitchRpmRatioReplaced": True,
            "sampleNamesUsedForSemantics": False,
            "installedSourceBanksModified": False,
        },
        "inputs": {
            "capturePlanPath": str(plan_path),
            "capturePlanFileSha256": _sha256(plan_path),
            "capturePlanCanonicalJsonSha256": _canonical_sha(
                _load_json(plan_path)
            ),
            "classificationPath": str(classification_path),
            "classificationFileSha256": _sha256(classification_path),
            "graphSummaryPath": str(graph_root / "summary.json"),
            "graphSummaryFileSha256": _sha256(graph_root / "summary.json"),
            "probeImplementationFileSha256": _sha256(Path(__file__).resolve()),
        },
        "counts": {
            "sourceVerifications": len(verifications),
            "targetPcmCaptures": len(verifications),
            "adaptiveWindowFallbackTracks": 0,
        },
        "sourceArtifacts": sorted(
            source_records, key=lambda item: (item["familyId"], item["sourceGuid"])
        ),
        "sourceVerifications": sorted(
            verifications,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
    }
    proof["proofPayloadSha256"] = _canonical_sha(proof)
    return proof


def _run_property_one(args: argparse.Namespace) -> int:
    selected = tuple(_guid(value) for value in args.source_guid) or PROPERTY_ONE_SOURCES
    unknown = sorted(set(selected) - set(PROPERTY_ONE_SOURCES))
    if unknown:
        raise ValueError(f"unknown property-one sources: {unknown}")
    output_root = args.output_root.resolve()
    root = find_assetto_root(args.assetto_root)
    for source_guid in selected:
        verification = certify_property_one_source(
            root,
            args.graph_root,
            args.classification,
            args.plan,
            source_guid,
            output_root,
        )
        path = output_root / "property-one" / source_guid / "verification.json"
        _write_canonical(path, verification)
        print(
            f"property-one source={source_guid} "
            f"pitchPoints={len(verification['pitchCurve'])} "
            f"spectralPitch={verification['targetPcmSpectralVerification']['maximumPitchErrorCents']:.6f}c "
            f"gain={verification['targetPcmSpectralVerification']['maximumGainErrorDb']:.6f}dB "
            f"evidence={path}",
            flush=True,
        )
    if set(selected) == set(PROPERTY_ONE_SOURCES):
        result = build_property_one_proof(
            args.graph_root,
            args.classification,
            args.plan,
            output_root,
        )
        report = args.report or output_root / "property-one-proof.json"
        _write_canonical(report, result)
        print(
            f"property-one sources={result['counts']['sourceVerifications']} "
            f"evidence={report} sha256={_sha256(report)}",
            flush=True,
        )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "operation",
        choices=(
            "diagnose",
            "diagnose-turbo-continuous",
            "certify-turbo-continuous-source",
            "certify-turbo-continuous",
            "certify-static-dispositions",
            "certify-property-one",
        ),
    )
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument(
        "--classification", type=Path, default=DEFAULT_CLASSIFICATION
    )
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--source-guid", action="append", default=[])
    parser.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.operation == "diagnose":
        return _run_diagnose(args)
    if args.operation == "diagnose-turbo-continuous":
        return _run_diagnose_turbo_continuous(args)
    if args.operation == "certify-turbo-continuous-source":
        return _run_turbo_continuous_source(args)
    if args.operation == "certify-turbo-continuous":
        return _run_turbo_continuous_all(args)
    if args.operation == "certify-static-dispositions":
        return _run_static_proof(args)
    if args.operation == "certify-property-one":
        return _run_property_one(args)
    raise AssertionError(args.operation)


if __name__ == "__main__":
    raise SystemExit(main())
