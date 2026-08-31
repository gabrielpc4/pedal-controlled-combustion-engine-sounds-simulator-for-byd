"""Silently certify release-selected FMOD engine-event one-shot sources.

The oracle operates on classifier-approved waveform GUIDs and temporary bank
copies.  Runtime sound names are used only to join an FMOD callback/channel to
the already selected GUID; they never choose a role or appear in a release
record.  WAVWRITER_NRT output is cropped at the master-writer DSP clock on the
exact Studio update where the target channel is scheduled, preserving authored
timeline silence even when an event's pitched channel-group clock uses a
different time domain.
"""

from __future__ import annotations

import argparse
from collections import Counter
import copy
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import struct
import sys
import wave
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import (
    ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION,
    ENGINE_TRANSIENT_VERIFICATION_SCHEMA,
    certify_manifest_engine_transient_source,
    derive_manifest_one_shot_curves,
)
from tools.probe_fmod_global_voice_arbitration import _OracleRuntime
from tools.probe_fmod_turbo_transients import (
    _event_runtime_identity_map,
    _runtime_bank,
    _target,
)


SCHEMA = "ac-fmod-engine-transient-oracle-v1"
PARTIAL_SCHEMA = "ac-fmod-engine-transient-oracle-partial-v2-fresh-process"
WORKER_REQUEST_SCHEMA = "ac-fmod-engine-transient-worker-request-v1"
WORKER_RESULT_SCHEMA = "ac-fmod-engine-transient-worker-result-v1"
CLASSIFIER_SCHEMA = "ac-fmod-catalog-source-role-classification-v2"
GRAPH_SUMMARY_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
PLAN_SCHEMA = "aclib-release-capture-plan-v2"
DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_CLASSIFICATION = (
    PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json"
)
DEFAULT_PLAN = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\aclib\capture-plan-v2-shift-silence-working.json"
)
DEFAULT_OUTPUT_ROOT = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\oracles\engine-transient-oracle-v1"
)
ALLOWED_ROLES = frozenset(
    (
        "ENGINE_FALLING_CANDIDATE",
        "ENGINE_TRANSIENT_CANDIDATE",
        "ENGINE_THROTTLE_INDEPENDENT_CANDIDATE",
    )
)
DSP_BUFFER_FRAMES = 256
SAMPLE_RATE_HZ = 48000
CHANNELS = 2
MAXIMUM_RENDER_UPDATES = math.ceil(30.0 * SAMPLE_RATE_HZ / DSP_BUFFER_FRAMES)
WORKER_TIMEOUT_SECONDS = 180


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
            raise AssertionError(f"unexpected writer PCM geometry: {path}")
        payload = source.readframes(source.getnframes())
    return payload, np.frombuffer(payload, dtype="<i2").reshape(-1, 2)


def _write_pcm16_stereo(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(CHANNELS)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE_HZ)
        output.writeframes(payload)


def _embedded_pcm16_evidence(bank: Path, target: dict[str, Any]) -> dict[str, Any]:
    """Verify the graph's numeric FSB5 subsound and report native silence."""

    import numpy as np

    technical = target["source"]["sample"]
    if int(technical["soundBankIndex"]) != 0:
        raise AssertionError("engine transient PCM oracle supports FSB index zero")
    payload = bank.read_bytes()
    fsb_offset = payload.find(b"FSB5")
    if fsb_offset < 0:
        raise AssertionError("target bank has no embedded FSB5 payload")
    version, sample_total, headers_size, names_size, data_size, audio_type = (
        struct.unpack_from("<6I", payload, fsb_offset + 4)
    )
    if audio_type != 2:
        raise AssertionError("engine transient source is not FSB5 PCM16")
    header_size = 0x40 if version == 0 else 0x3C
    cursor = fsb_offset + header_size
    metadata: list[tuple[int, int]] = []
    for _index in range(sample_total):
        encoded = struct.unpack_from("<Q", payload, cursor)[0]
        cursor += 8
        more = bool(encoded & 1)
        metadata.append(
            (
                ((encoded >> 7) & ((1 << 27) - 1)) * 32,
                (encoded >> 34) & ((1 << 30) - 1),
            )
        )
        while more:
            chunk = struct.unpack_from("<I", payload, cursor)[0]
            cursor += 4
            more = bool(chunk & 1)
            cursor += (chunk >> 1) & ((1 << 24) - 1)
    subsound = int(technical["subsoundIndex"])
    data_start = fsb_offset + header_size + headers_size + names_size
    start = data_start + metadata[subsound][0]
    end_offset = metadata[subsound + 1][0] if subsound + 1 < len(metadata) else data_size
    encoded_payload = payload[start : data_start + end_offset]
    if hashlib.sha256(encoded_payload).hexdigest() != technical["encodedPayloadSha256"]:
        raise AssertionError("embedded target payload differs from graph hash")
    frame_count = int(technical["sampleCount"])
    channels = int(technical["channels"])
    if metadata[subsound][1] != frame_count:
        raise AssertionError("embedded target frame count differs from graph")
    samples = np.frombuffer(
        encoded_payload,
        dtype="<i2",
        count=frame_count * channels,
    ).reshape(frame_count, channels)
    nonzero = np.flatnonzero(np.any(samples != 0, axis=1))
    return {
        "accepted": True,
        "encoding": "FSB5_PCM16_LE",
        "soundBankIndex": int(technical["soundBankIndex"]),
        "subsoundIndex": subsound,
        "encodedPayloadSha256": technical["encodedPayloadSha256"],
        "sampleRateHz": int(technical["frequencyHz"]),
        "channels": channels,
        "frameCount": frame_count,
        "authoredLeadingSilentFrames": int(nonzero[0]) if len(nonzero) else frame_count,
        "authoredTrailingSilentFrames": (
            int(frame_count - 1 - nonzero[-1]) if len(nonzero) else frame_count
        ),
        "allAuthoredSamplesZero": not bool(len(nonzero)),
        "sampleNameUsed": False,
    }


class _EngineRuntime(_OracleRuntime):
    """Oracle runtime with exact pitch plus channel/master-clock inspection."""

    def _bind_extra(self) -> None:
        super()._bind_extra()
        self.core.FMOD_Channel_GetPitch.argtypes = [C.c_void_p, C.POINTER(C.c_float)]
        self.core.FMOD_Channel_GetPitch.restype = C.c_int
        self.core.FMOD_Channel_GetDSPClock.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_ulonglong),
            C.POINTER(C.c_ulonglong),
        ]
        self.core.FMOD_Channel_GetDSPClock.restype = C.c_int
        self.core.FMOD_System_GetMasterChannelGroup.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        self.core.FMOD_System_GetMasterChannelGroup.restype = C.c_int
        self.core.FMOD_ChannelGroup_GetDSPClock.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_ulonglong),
            C.POINTER(C.c_ulonglong),
        ]
        self.core.FMOD_ChannelGroup_GetDSPClock.restype = C.c_int

    def target_channels(self, instance_key: str, source_guid: str) -> list[dict[str, Any]]:
        master_group = C.c_void_p()
        self._check(
            self.core.FMOD_System_GetMasterChannelGroup(
                self.low_level, C.byref(master_group)
            ),
            "get engine-transient master channel group",
        )
        master_clock = C.c_ulonglong()
        master_parent_clock = C.c_ulonglong()
        self._check(
            self.core.FMOD_ChannelGroup_GetDSPClock(
                master_group, C.byref(master_clock), C.byref(master_parent_clock)
            ),
            "read engine-transient master DSP clock",
        )
        record = self.instances[instance_key]
        event_group = C.c_void_p()
        self._check(
            self.studio.FMOD_Studio_EventInstance_GetChannelGroup(
                record["handle"], C.byref(event_group)
            ),
            "get engine-transient event channel group",
        )
        pending = [event_group]
        result: list[dict[str, Any]] = []
        while pending:
            group = pending.pop()
            channel_count = C.c_int()
            self._check(
                self.core.FMOD_ChannelGroup_GetNumChannels(group, C.byref(channel_count)),
                "count engine-transient group channels",
            )
            for index in range(channel_count.value):
                channel = C.c_void_p()
                self._check(
                    self.core.FMOD_ChannelGroup_GetChannel(group, index, C.byref(channel)),
                    "get engine-transient channel",
                )
                sound = C.c_void_p()
                self._check(
                    self.core.FMOD_Channel_GetCurrentSound(channel, C.byref(sound)),
                    "get engine-transient current sound",
                )
                name = C.create_string_buffer(1024)
                self._check(
                    self.core.FMOD_Sound_GetName(sound, name, len(name)),
                    "read engine-transient runtime identity",
                )
                joined = self.runtime_name_to_source.get(
                    name.value.decode("utf-8", "replace")
                )
                if joined != source_guid:
                    continue
                pitch = C.c_float()
                priority = C.c_int()
                position = C.c_uint()
                virtual = C.c_int()
                audibility = C.c_float()
                local_clock = C.c_ulonglong()
                parent_clock = C.c_ulonglong()
                self._check(self.core.FMOD_Channel_GetPitch(channel, C.byref(pitch)), "read channel pitch")
                self._check(self.core.FMOD_Channel_GetPriority(channel, C.byref(priority)), "read channel priority")
                self._check(self.core.FMOD_Channel_GetPosition(channel, C.byref(position), 2), "read channel PCM position")
                self._check(self.core.FMOD_Channel_IsVirtual(channel, C.byref(virtual)), "read virtual state")
                self._check(self.core.FMOD_Channel_GetAudibility(channel, C.byref(audibility)), "read audibility")
                self._check(
                    self.core.FMOD_Channel_GetDSPClock(
                        channel, C.byref(local_clock), C.byref(parent_clock)
                    ),
                    "read target DSP clock",
                )
                result.append(
                    {
                        "voiceToken": self._voice_token(channel),
                        "pitch": float(pitch.value),
                        "priority": int(priority.value),
                        "pcmPosition": int(position.value),
                        "isVirtual": bool(virtual.value),
                        "audibility": float(audibility.value),
                        "localDspClock": int(local_clock.value),
                        "parentDspClock": int(parent_clock.value),
                        "writerMasterDspClock": int(master_clock.value),
                    }
                )
            group_count = C.c_int()
            self._check(
                self.core.FMOD_ChannelGroup_GetNumGroups(group, C.byref(group_count)),
                "count child channel groups",
            )
            for index in range(group_count.value):
                child = C.c_void_p()
                self._check(
                    self.core.FMOD_ChannelGroup_GetGroup(group, index, C.byref(child)),
                    "get child channel group",
                )
                pending.append(child)
        return result


def _select_target(
    runtime: _EngineRuntime,
    target: dict[str, Any],
    derived: dict[str, Any],
    *,
    prefix: str,
    parameters: dict[str, float] | None = None,
    event_volume: float = 1.0,
    event_pitch: float = 1.0,
    maximum_attempts: int = 4096,
) -> tuple[str, dict[str, Any], int]:
    selected_parameters = parameters or {
        str(name): float(value)
        for name, value in derived["captureParameterValues"].items()
    }
    for attempt in range(maximum_attempts):
        key = f"{prefix}{attempt:04d}"
        runtime.create_instance(
            key,
            target["eventPath"],
            parameters=selected_parameters,
            volume=event_volume,
            pitch=event_pitch,
        )
        runtime.start(key)
        runtime.flush(f"{prefix}-start-{attempt}")
        runtime.update(f"{prefix}-first-{attempt}")
        channels = runtime.target_channels(key, target["sourceGuid"])
        if channels:
            local_clock = int(channels[0]["localDspClock"])
            parent_clock = int(channels[0]["parentDspClock"])
            writer_clock = int(channels[0]["writerMasterDspClock"])
            if (
                len(channels) != 1
                or local_clock < 0
                or parent_clock < local_clock
                or writer_clock < 0
                or writer_clock % DSP_BUFFER_FRAMES
            ):
                raise AssertionError(
                    "target did not enter on an exact DSP boundary: "
                    f"channels={channels}"
                )
            return key, channels[0], attempt + 1
        runtime.stop_release(key)
    raise AssertionError(
        f"target {target['sourceGuid']} was not selected in {maximum_attempts} takes"
    )


def _render_once_local(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    path: Path,
    *,
    parameters: dict[str, float] | None = None,
    event_volume: float = 1.0,
    event_pitch: float = 1.0,
) -> dict[str, Any]:
    """Render one selected voice and retain its complete DSP-clock lifetime."""

    identity = _event_runtime_identity_map(target)
    stopped = False
    with _EngineRuntime(
        assetto_root,
        bank,
        identity,
        path,
        max_channels=64,
        event_paths=(target["eventPath"],),
    ) as runtime:
        key, first, attempts = _select_target(
            runtime,
            target,
            derived,
            prefix="capture",
            parameters=parameters,
            event_volume=event_volume,
            event_pitch=event_pitch,
        )
        observed_local_clock = int(first["localDspClock"])
        start_clock = int(first["writerMasterDspClock"])
        rendered_after_start = 0
        for update in range(MAXIMUM_RENDER_UPDATES):
            runtime.update(f"capture-tail-{update}")
            rendered_after_start += DSP_BUFFER_FRAMES
            stopped = any(
                item["kind"] == "stopped"
                and item["category"] == key
                and item["source"] == target["sourceGuid"]
                for item in runtime.callbacks
            )
            if stopped:
                break
        if not stopped:
            raise AssertionError(
                f"target voice did not stop within 30 seconds: {target['sourceGuid']}"
            )
        callbacks = [
            item
            for item in runtime.callbacks
            if item["category"] == key and item["source"] == target["sourceGuid"]
        ]
        if Counter(item["kind"] for item in callbacks) != {"played": 1, "stopped": 1}:
            raise AssertionError("target capture lifecycle callbacks are not one played/stopped pair")
        observation = {
            "scheduleStartDspClockFrames": start_clock,
            "channelLocalDspClockAtScheduleObservation": int(first["localDspClock"]),
            "immediateParentDspClockAtScheduleObservation": int(
                first["parentDspClock"]
            ),
            "writerMasterDspClockAtScheduleObservation": int(
                first["writerMasterDspClock"]
            ),
            "renderedFramesThroughStoppedCallback": rendered_after_start,
            "captureChannelPitch": float(first["pitch"]),
            "softwareChannelPriority": int(first["priority"]),
            "selectionAttempts": attempts,
            "targetCallbacks": callbacks,
        }
    _payload, writer_pcm = _read_pcm16_stereo(path)
    end = start_clock + rendered_after_start
    if end > len(writer_pcm):
        raise AssertionError(
            f"writer ended before DSP-clock crop {end}>{len(writer_pcm)}"
        )
    observation["writerFrameCount"] = len(writer_pcm)
    observation["croppedPcm"] = writer_pcm[start_clock:end].copy()
    return observation


def _capture_pcm(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    import numpy as np

    source_guid = target["sourceGuid"]
    work = output_root / "work" / "capture"
    final_root = output_root / "captures"
    work.mkdir(parents=True, exist_ok=True)
    final_root.mkdir(parents=True, exist_ok=True)
    first_path = work / f"{source_guid}-a.wav"
    second_path = work / f"{source_guid}-b.wav"
    first = _render_once(assetto_root, bank, target, derived, first_path)
    second = _render_once(assetto_root, bank, target, derived, second_path)
    left = first.pop("croppedPcm")
    right = second.pop("croppedPcm")
    if len(left) != len(right) or not np.array_equal(left, right):
        maximum_error = (
            int(np.max(np.abs(left.astype(np.int32) - right.astype(np.int32))))
            if len(left) == len(right) and len(left)
            else None
        )
        raise AssertionError(
            f"DSP-clock-aligned independent PCM differs for {source_guid}; "
            f"frames={len(left)}/{len(right)} maxError={maximum_error}"
        )
    if first["captureChannelPitch"] != second["captureChannelPitch"]:
        raise AssertionError("independent target channel pitch differs")
    if first["softwareChannelPriority"] != second["softwareChannelPriority"]:
        raise AssertionError("independent target channel priority differs")
    independent_process_evidence = [
        first.pop("freshProcessEvidence"),
        second.pop("freshProcessEvidence"),
    ]
    if len({item["workerProcessId"] for item in independent_process_evidence}) != 2:
        raise AssertionError("independent PCM renders reused one worker process")
    payload = left.astype("<i2").tobytes()
    final_path = final_root / f"{source_guid}.wav"
    _write_pcm16_stereo(final_path, payload)
    nonzero = np.flatnonzero(np.any(left != 0, axis=1))
    all_zero = not bool(len(nonzero))
    peak = int(np.max(np.abs(left.astype(np.int32)))) if len(left) else 0
    peak_dbfs = 20.0 * math.log10(max(peak / 32768.0, 1.0e-12))
    embedded = _embedded_pcm16_evidence(bank, target)
    first_path.unlink(missing_ok=True)
    second_path.unlink(missing_ok=True)
    return {
        "accepted": True,
        "oracleVersion": ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION,
        "scheduledSourceGuid": source_guid,
        "captureParameterValues": derived["captureParameterValues"],
        "audibilityDisposition": (
            "AUTHORED_TARGET_SILENT" if all_zero else "AUDIBLE_TARGET_PCM"
        ),
        "allPcmSamplesZero": all_zero,
        "frameCount": len(left),
        "pcmPayloadSha256": hashlib.sha256(payload).hexdigest(),
        "peakPcmDbfs": peak_dbfs,
        "playbackStartFrame": 0,
        "playbackEndFrameExclusive": len(left),
        "terminationTimingErrorBoundFrames": DSP_BUFFER_FRAMES,
        "dspClockAlignmentErrorBoundFrames": 0,
        "independentRenderBitExact": True,
        "independentFreshProcessRenders": independent_process_evidence,
        "writerFrameIndexEqualsParentDspClock": True,
        "timelineAuthoredSilentPrefixPreserved": True,
        "finalWavRelativePath": final_path.relative_to(output_root).as_posix(),
        "finalWavSha256": _sha256(final_path),
        "embeddedSourcePcmEvidence": embedded,
        "dspClockAlignment": {
            "method": "TARGET_SCHEDULE_FRAME_EQUALS_MASTER_WRITER_DSP_CLOCK_AT_DISCOVERY_UPDATE",
            "independentScheduleStartDspClockFrames": [
                first["scheduleStartDspClockFrames"],
                second["scheduleStartDspClockFrames"],
            ],
            "channelLocalDspClockAtScheduleObservation": [
                first["channelLocalDspClockAtScheduleObservation"],
                second["channelLocalDspClockAtScheduleObservation"],
            ],
            "immediateParentDspClockAtScheduleObservation": [
                first["immediateParentDspClockAtScheduleObservation"],
                second["immediateParentDspClockAtScheduleObservation"],
            ],
            "writerMasterDspClockAtScheduleObservation": [
                first["writerMasterDspClockAtScheduleObservation"],
                second["writerMasterDspClockAtScheduleObservation"],
            ],
            "writerCropStartFrames": [
                first["scheduleStartDspClockFrames"],
                second["scheduleStartDspClockFrames"],
            ],
            "writerFrameCounts": [
                first["writerFrameCount"],
                second["writerFrameCount"],
            ],
            "comparedFrameCount": len(left),
            "dspBufferFrames": DSP_BUFFER_FRAMES,
            "sampleNameUsedForSchedulingSemantics": False,
        },
        "captureChannelPitch": first["captureChannelPitch"],
        "softwareChannelPriority": first["softwareChannelPriority"],
        "selectionAttempts": [
            first["selectionAttempts"],
            second["selectionAttempts"],
        ],
    }


def _rpm_probe_value(derived: dict[str, Any]) -> float:
    capture = float(derived["captureRpm"])
    rpm_regions = [
        item
        for item in derived["triggerSemantics"]["runtimeRegions"]
        if item["parameter"] == "rpms"
    ]
    if rpm_regions:
        low, high = map(float, rpm_regions[0]["parameterDomain"])
    else:
        # Cone/throttle-only region programs still expose the engine event's
        # RPM control and may have live AutoPitch.  The static derivation's
        # normalized RPM curve is already bound to that exact event parameter
        # domain, so use its endpoints for a non-semantic motion probe.
        rpm_points = [float(item[0]) for item in derived["rpmCurve"]]
        low, high = min(rpm_points), max(rpm_points)
    probe = min(high, max(low, capture * 1.25))
    if math.isclose(probe, capture, rel_tol=0.0, abs_tol=1.0):
        probe = min(high, max(low, capture * 0.8))
    if math.isclose(probe, capture, rel_tol=0.0, abs_tol=1.0):
        raise AssertionError("RPM domain has no distinct pitch probe")
    return probe


def _pitch_probe_local(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    scratch = output_root / "work" / "pitch" / f"{target['sourceGuid']}.wav"
    scratch.parent.mkdir(parents=True, exist_ok=True)
    with _EngineRuntime(
        assetto_root,
        bank,
        _event_runtime_identity_map(target),
        scratch,
        max_channels=64,
        event_paths=(target["eventPath"],),
    ) as runtime:
        key, first, attempts = _select_target(runtime, target, derived, prefix="pitch")
        token = int(first["voiceToken"])
        capture_pitch = float(first["pitch"])
        probe_rpm = _rpm_probe_value(derived)
        runtime.set_parameter(key, "rpms", probe_rpm)
        runtime.update("pitch-probe")
        moved = [
            item
            for item in runtime.target_channels(key, target["sourceGuid"])
            if int(item["voiceToken"]) == token
        ]
        if len(moved) != 1:
            raise AssertionError("target voice ended before live pitch observation")
        probe_pitch = float(moved[0]["pitch"])
    scratch.unlink(missing_ok=True)
    capture_rpm = float(derived["captureRpm"])
    observed_ratio = probe_pitch / capture_pitch
    live_error = abs(observed_ratio - probe_rpm / capture_rpm)
    static_error = abs(observed_ratio - 1.0)
    if live_error <= 1.0e-5 and static_error > 1.0e-6:
        mode = "LIVE_RPM_RATIO"
        error = live_error
    elif static_error <= 1.0e-6:
        mode = "STATIC_BAKED_PITCH"
        error = static_error
    else:
        raise AssertionError(
            f"unsupported active pitch ratio {observed_ratio} for {target['sourceGuid']}"
        )
    return {
        "accepted": True,
        "mode": mode,
        "captureRpm": capture_rpm,
        "probeRpm": probe_rpm,
        "captureChannelPitch": capture_pitch,
        "probeChannelPitch": probe_pitch,
        "observedPlaybackRateRatio": observed_ratio,
        "expectedPlaybackRateRatio": (
            probe_rpm / capture_rpm if mode == "LIVE_RPM_RATIO" else 1.0
        ),
        "maximumPlaybackRateRatioError": error,
        "updatesWhileVoiceActive": True,
        "sourceBoundChannelObserved": True,
        "selectionAttempts": attempts,
        "sampleNameUsedForPitchSemantics": False,
    }


def _inside_region(value: float, region: dict[str, Any]) -> bool:
    minimum = float(region["minimum"])
    maximum = float(region["maximum"])
    return (value > minimum or (value == minimum and region["includeMinimum"])) and (
        value < maximum or (value == maximum and region["includeMaximum"])
    )


def _zero_gain_control(derived: dict[str, Any]) -> tuple[str, float] | None:
    # Prefer the non-placement throttle automation.  Parameter-region exit is
    # a scheduling operation, not a guarantee that a playing one-shot's source
    # volume controller evaluates outside the placement.  Keeping zero-gain
    # and re-arm controls separate is therefore essential.
    regions = derived["triggerSemantics"]["runtimeRegions"]
    throttle_regions = [item for item in regions if item["parameter"] == "throttle"]
    throttle_zero = [
        float(x)
        for x, y in derived["gainCurve"]
        if float(y) == 0.0
        and all(_inside_region(float(x), region) for region in throttle_regions)
    ]
    if throttle_zero:
        capture = float(derived["captureThrottle"])
        return "throttle", max(throttle_zero, key=lambda value: abs(value - capture))
    rpm_regions = [item for item in regions if item["parameter"] == "rpms"]
    rpm_zero = [
        float(x)
        for x, y in derived["rpmCurve"]
        if float(y) == 0.0
        and float(x) > 0.0
        and all(_inside_region(float(x), region) for region in rpm_regions)
    ]
    if rpm_zero:
        capture = float(derived["captureRpm"])
        return "rpms", min(rpm_zero, key=lambda value: abs(value - capture))
    return None


def _rearm_control(derived: dict[str, Any]) -> tuple[str, float, float]:
    for region in derived["triggerSemantics"]["runtimeRegions"]:
        low, high = map(float, region["parameterDomain"])
        minimum = float(region["minimum"])
        maximum = float(region["maximum"])
        if low < minimum:
            outside = (low + minimum) * 0.5
        elif high > maximum:
            outside = (maximum + high) * 0.5
        else:
            continue
        control = str(region["parameter"])
        return control, outside, float(derived["captureParameterValues"][control])
    raise AssertionError("engine transient has no reachable placement-exit value")


def _zero_gain_probe_local(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
    *,
    observation_hold_updates: int,
) -> dict[str, Any]:
    scratch = output_root / "work" / "zero" / f"{target['sourceGuid']}.wav"
    scratch.parent.mkdir(parents=True, exist_ok=True)
    zero_control = _zero_gain_control(derived)
    rearm_control, outside_value, inside_value = _rearm_control(derived)
    with _EngineRuntime(
        assetto_root,
        bank,
        _event_runtime_identity_map(target),
        scratch,
        max_channels=64,
        event_paths=(target["eventPath"],),
    ) as runtime:
        key, first, attempts = _select_target(runtime, target, derived, prefix="zero")
        token = int(first["voiceToken"])
        for update in range(4):
            runtime.update(f"zero-preroll-{update}")
        before = [
            item
            for item in runtime.target_channels(key, target["sourceGuid"])
            if int(item["voiceToken"]) == token
        ]
        if len(before) != 1 or int(before[0]["pcmPosition"]) <= 0:
            raise AssertionError("target phase did not advance before zero-gain probe")
        zero_observations: list[dict[str, Any]] = []
        if zero_control is not None:
            control, zero_value = zero_control
            runtime.set_parameter(key, control, zero_value)
            for update in range(observation_hold_updates):
                runtime.update(f"zero-hold-{update}")
                current = [
                    item
                    for item in runtime.target_channels(key, target["sourceGuid"])
                    if int(item["voiceToken"]) == token
                ]
                if len(current) != 1:
                    raise AssertionError("target ended during zero-gain hold")
                zero_observations.append(current[0])
            positions = [int(item["pcmPosition"]) for item in zero_observations]
            audibilities = [float(item["audibility"]) for item in zero_observations]
            frozen = positions[-1]
            position_settling_updates = next(
                (
                    index
                    for index in range(len(positions))
                    if all(value == frozen for value in positions[index:])
                ),
                len(positions),
            )
            audibility_settling_updates = next(
                (
                    index
                    for index in range(len(audibilities))
                    if all(value == 0.0 for value in audibilities[index:])
                ),
                len(audibilities),
            )
            settling_updates = max(
                position_settling_updates, audibility_settling_updates
            )
            if len(audibilities) < 4 or any(
                value != 0.0 for value in audibilities[-4:]
            ):
                raise AssertionError(
                    "exact-zero authored gain did not settle to silence"
                )
            phase_policy = "PENDING_SOURCE_BOUND_POST_RESTORE_PCM_COMPARISON"
        else:
            control = None
            zero_value = None
            frozen = int(before[0]["pcmPosition"])
            settling_updates = 0
            phase_policy = (
                "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"
            )
        # Exit one authored placement to re-arm the program.  When a distinct
        # source-volume control produced the zero, restore it while still
        # outside so prior-voice phase resume is independently observable.
        runtime.set_parameter(key, rearm_control, outside_value)
        for settle in range(2):
            runtime.update(f"zero-placement-exit-{settle}")
        if control is not None and control != rearm_control:
            runtime.set_parameter(
                key, control, float(derived["captureParameterValues"][control])
            )
            prior_resumed_outside = False
            for settle in range(4):
                runtime.update(f"zero-gain-restore-outside-{settle}")
                prior = [
                    item
                    for item in runtime.target_channels(key, target["sourceGuid"])
                    if int(item["voiceToken"]) == token
                ]
                if len(prior) != 1:
                    raise AssertionError("frozen prior voice ended before gain restore")
                prior_resumed_outside = int(prior[0]["pcmPosition"]) > frozen
                if prior_resumed_outside:
                    break
            # Some authored parameter controllers do not route a new gain to
            # an already-playing source while its placement is outside.  In
            # that case phase resumes on the same inside transition that
            # schedules the next voice; the loop below proves both facts.
        elif control is not None:
            prior_resumed_outside = False
        else:
            prior_after_exit = [
                item
                for item in runtime.target_channels(key, target["sourceGuid"])
                if int(item["voiceToken"]) == token
            ]
            if len(prior_after_exit) != 1:
                raise AssertionError("active prior voice was cut on placement exit")
            prior_resumed_outside = (
                int(prior_after_exit[0]["pcmPosition"]) > frozen
            )
            if not prior_resumed_outside:
                raise AssertionError("nonzero prior voice did not advance outside placement")

        reentry_voices: list[dict[str, Any]] | None = None
        prior_present_at_reentry = False
        maximum_reentries = max(
            4,
            8
            * math.prod(
                max(1, len(item["orderedChildren"]))
                for item in derived.get("selectionPath", [])
            ),
        )
        for cycle in range(maximum_reentries):
            runtime.set_parameter(key, rearm_control, inside_value)
            prior_resumed = False
            for settle in range(max(4, observation_hold_updates)):
                runtime.update(f"zero-reenter-{cycle}-{settle}")
                voices = runtime.target_channels(key, target["sourceGuid"])
                prior = [item for item in voices if int(item["voiceToken"]) == token]
                new_voices = [
                    item for item in voices if int(item["voiceToken"]) != token
                ]
                if len(prior) > 1:
                    raise AssertionError("prior target voice identity duplicated")
                if prior:
                    prior_resumed = int(prior[0]["pcmPosition"]) > frozen
                else:
                    prior_stopped = any(
                        item["kind"] == "stopped"
                        and item["category"] == key
                        and item["source"] == target["sourceGuid"]
                        for item in runtime.callbacks
                    )
                    if not prior_stopped:
                        raise AssertionError(
                            "prior voice disappeared without a stopped callback"
                        )
                if new_voices and (prior_resumed or not prior):
                    reentry_voices = voices
                    prior_present_at_reentry = bool(prior)
                    break
            if reentry_voices is not None:
                break
            if prior and not prior_resumed:
                raise AssertionError("frozen prior voice did not resume on re-entry")
            runtime.set_parameter(key, rearm_control, outside_value)
            for settle in range(4):
                runtime.update(f"zero-exit-{cycle}-{settle}")
        if reentry_voices is None:
            remaining = runtime.target_channels(key, target["sourceGuid"])
            prior_remaining = [
                item for item in remaining if int(item["voiceToken"]) == token
            ]
            prior_stopped = any(
                item["kind"] == "stopped"
                and item["category"] == key
                and item["source"] == target["sourceGuid"]
                for item in runtime.callbacks
            )
            if prior_remaining or not prior_stopped:
                raise AssertionError(
                    "re-entry neither scheduled a voice nor reached natural end"
                )
            reentry_voices = []
    scratch.unlink(missing_ok=True)
    if phase_policy == "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE":
        zero_runtime_semantic = {
            "kind": "NOT_APPLICABLE",
            "logicalVoiceDeadlineAdvancesAtWriterTime": True,
            "decodeCursorTreatment": "NORMAL_ACTIVE_VOICE",
            "zeroTransition": {
                "policy": "NOT_APPLICABLE",
                "reason": (
                    "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"
                ),
            },
        }
    else:
        positions = [int(item["pcmPosition"]) for item in zero_observations]
        virtual_states = [bool(item["isVirtual"]) for item in zero_observations]
        virtual_start = next(
            (index for index, value in enumerate(virtual_states) if value), None
        )
        change_indices = [
            index
            for index in range(1, len(positions))
            if positions[index] != positions[index - 1]
            and (virtual_start is None or index >= virtual_start)
        ]
        change_deltas = [
            positions[index] - positions[index - 1] for index in change_indices
        ]
        change_intervals = [
            right - left for left, right in zip(change_indices, change_indices[1:])
        ]
        zero_runtime_semantic = {
            "kind": "PENDING_POST_RESTORE_PCM_COMPARISON",
            "logicalVoiceDeadlineAdvancesAtWriterTime": True,
            "writerDspBlockFrames": DSP_BUFFER_FRAMES,
            "sourceCursorUnit": "EMBEDDED_SOURCE_PCM_FRAMES",
            "embeddedSourceSampleRateHz": int(
                derived["sourceGeometry"]["sampleTechnicalEvidence"]["frequencyHz"]
            ),
            "virtualStateFirstObservationIndex": virtual_start,
            "firstVirtualCursorServiceAfterDspBlocks": (
                change_indices[0] - virtual_start
                if virtual_start is not None and change_indices
                else None
            ),
            "virtualCursorServicePeriodDspBlocks": (
                change_intervals[0] if change_intervals else None
            ),
            "virtualCursorAdvanceSourceFramesPerService": (
                change_deltas[0] if change_deltas else 0
            ),
            "validatedObservationDspBlocks": len(positions),
            "queriedVirtualCursorChangeIndices": change_indices,
            "queriedVirtualCursorChangeSourceFrames": change_deltas,
            "queriedVirtualCursorChangeIntervalsDspBlocks": change_intervals,
            "channelGetPositionWhileVirtualIsNotUsedAsRuntimePhaseTruth": True,
        }
    return {
        "accepted": True,
        "phasePolicy": phase_policy,
        "reentryPolicy": (
            (
                "PRESERVE_PRIOR_UNTIL_SOURCE_BOUND_NATURAL_END_AND_SCHEDULE_NEW_ON_REENTRY;"
                "OVERLAP_IF_PRIOR_REMAINS_ALIVE"
            )
            if reentry_voices
            else "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER"
        ),
        "control": control,
        "zeroGainValue": zero_value,
        "rearmControl": rearm_control,
        "rearmOutsideValue": outside_value,
        "reentryInsideValue": inside_value,
        "priorVoiceResumedWhileOutsidePlacement": prior_resumed_outside,
        "runtimeSemantic": zero_runtime_semantic,
        "phaseBeforeZero": int(before[0]["pcmPosition"]),
        "frozenPhase": frozen,
        "zeroGainAutomationSettlingUpdates": settling_updates,
        "zeroHoldUpdates": len(zero_observations),
        "zeroGainPcmPositionObservations": [
            int(item["pcmPosition"]) for item in zero_observations
        ],
        "zeroGainVirtualStateObservations": [
            bool(item["isVirtual"]) for item in zero_observations
        ],
        "zeroGainAudibilityObservations": [
            float(item["audibility"]) for item in zero_observations
        ],
        "becameVirtualDuringZeroHold": any(item["isVirtual"] for item in zero_observations),
        "reentryTargetVoiceCount": len(reentry_voices),
        "priorVoicePresentAtReentry": prior_present_at_reentry,
        "priorVoicePcmPositionAtReentry": next(
            (
                int(item["pcmPosition"])
                for item in reentry_voices
                if int(item["voiceToken"]) == token
            ),
            None,
        ),
        "newVoicePcmPositionsAtReentry": [
            int(item["pcmPosition"])
            for item in reentry_voices
            if int(item["voiceToken"]) != token
        ],
        "reentryOutcome": (
            "PRIOR_RETAINED_AND_OVERLAPPED_NEW_VOICE"
            if prior_present_at_reentry
            else (
                "PRIOR_NATURALLY_ENDED_BEFORE_NEW_VOICE"
                if reentry_voices
                else "PRIOR_NATURALLY_ENDED_AND_REENTRY_DID_NOT_SCHEDULE_NEW_VOICE"
            )
        ),
        "maximumPhaseObservationErrorFrames": 0,
        "selectionAttempts": attempts,
        "sampleNameUsedForGainSemantics": False,
    }


def _zero_resume_render_local(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    path: Path,
    *,
    apply_zero: bool,
    pitch_only_reference: bool,
    live_rpm_pitch: bool,
    preroll_updates: int,
    zero_hold_updates: int,
) -> dict[str, Any]:
    """Render a baseline or exact-zero gap with an identical logical timeline."""

    zero_control = _zero_gain_control(derived)
    if zero_control is None:
        raise AssertionError("zero/resume render requested without reachable exact zero")
    control, zero_value = zero_control
    with _EngineRuntime(
        assetto_root,
        bank,
        _event_runtime_identity_map(target),
        path,
        max_channels=64,
        event_paths=(target["eventPath"],),
    ) as runtime:
        key, first, attempts = _select_target(
            runtime, target, derived, prefix="zero-pcm"
        )
        token = int(first["voiceToken"])
        start_clock = int(first["writerMasterDspClock"])
        rendered_after_start = 0
        hold_channel_observations: list[dict[str, Any]] = []
        restore_channel_observations: list[dict[str, Any]] = []

        def observe_channel(label: str) -> dict[str, Any]:
            voices = [
                item
                for item in runtime.target_channels(key, target["sourceGuid"])
                if int(item["voiceToken"]) == token
            ]
            return {
                "label": label,
                "writerFrameAfterSchedule": rendered_after_start,
                "priorVoice": copy.deepcopy(voices[0]) if voices else None,
                "targetVoiceCount": len(
                    runtime.target_channels(key, target["sourceGuid"])
                ),
            }
        for update in range(preroll_updates):
            runtime.update(f"zero-pcm-preroll-{update}")
            rendered_after_start += DSP_BUFFER_FRAMES
        if apply_zero:
            runtime.set_parameter(key, control, zero_value)
        elif pitch_only_reference and control == "rpms" and live_rpm_pitch:
            runtime.set_pitch(
                key,
                float(zero_value)
                / float(derived["captureParameterValues"]["rpms"]),
            )
        for update in range(zero_hold_updates):
            runtime.update(f"zero-pcm-hold-{update}")
            rendered_after_start += DSP_BUFFER_FRAMES
            hold_channel_observations.append(
                observe_channel(f"zero-pcm-hold-{update}")
            )
        if apply_zero:
            runtime.set_parameter(
                key, control, float(derived["captureParameterValues"][control])
            )
        elif pitch_only_reference and control == "rpms" and live_rpm_pitch:
            runtime.set_pitch(key, 1.0)
        restored_start_frame = rendered_after_start
        stopped = False
        for update in range(MAXIMUM_RENDER_UPDATES):
            runtime.update(f"zero-pcm-tail-{update}")
            rendered_after_start += DSP_BUFFER_FRAMES
            if update < 16:
                restore_channel_observations.append(
                    observe_channel(f"zero-pcm-restore-{update}")
                )
            stopped = any(
                item["kind"] == "stopped"
                and item["category"] == key
                and item["source"] == target["sourceGuid"]
                for item in runtime.callbacks
            )
            if stopped:
                break
        if not stopped:
            raise AssertionError("zero/resume target did not stop naturally")
        callbacks = [
            item
            for item in runtime.callbacks
            if item["category"] == key and item["source"] == target["sourceGuid"]
        ]
        if Counter(item["kind"] for item in callbacks) != {
            "played": 1,
            "stopped": 1,
        }:
            raise AssertionError("zero/resume lifecycle is not one played/stopped pair")
    _raw, writer_pcm = _read_pcm16_stereo(path)
    end = start_clock + rendered_after_start
    if end > len(writer_pcm):
        raise AssertionError("zero/resume writer ended before target crop")
    return {
        "applyExactZeroGap": apply_zero,
        "pitchOnlyReference": pitch_only_reference,
        "liveRpmPitch": live_rpm_pitch,
        "zeroControl": control,
        "zeroValue": zero_value,
        "prerollUpdates": preroll_updates,
        "zeroHoldUpdates": zero_hold_updates,
        "restoredStartFrame": restored_start_frame,
        "initialChannelObservation": copy.deepcopy(first),
        "zeroHoldChannelObservations": hold_channel_observations,
        "restoreChannelObservations": restore_channel_observations,
        "targetCallbacks": callbacks,
        "scheduleStartDspClockFrames": start_clock,
        "renderedFramesThroughStoppedCallback": rendered_after_start,
        "selectionAttempts": attempts,
        "croppedPcm": writer_pcm[start_clock:end].copy(),
    }


def _brief_zero_recovery_render_local(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    path: Path,
    *,
    zero_updates_before_recovery: int,
    recovery_updates_before_second_zero: int,
    second_zero_observation_updates: int,
) -> dict[str, Any]:
    """Exercise zero->positive->zero at exact DSP-update boundaries."""

    zero_control = _zero_gain_control(derived)
    if zero_control is None:
        raise AssertionError("brief-zero probe requested without reachable zero")
    control, zero_value = zero_control
    positive_value = float(derived["captureParameterValues"][control])
    observations: list[dict[str, Any]] = []
    with _EngineRuntime(
        assetto_root,
        bank,
        _event_runtime_identity_map(target),
        path,
        max_channels=64,
        event_paths=(target["eventPath"],),
    ) as runtime:
        key, first, attempts = _select_target(
            runtime, target, derived, prefix="brief-zero"
        )
        token = int(first["voiceToken"])
        start_clock = int(first["writerMasterDspClock"])
        rendered_after_start = 0

        def update(label: str) -> None:
            nonlocal rendered_after_start
            runtime.update(label)
            rendered_after_start += DSP_BUFFER_FRAMES
            voices = runtime.target_channels(key, target["sourceGuid"])
            prior = [item for item in voices if int(item["voiceToken"]) == token]
            observations.append(
                {
                    "label": label,
                    "writerFrameAfterUpdate": rendered_after_start,
                    "priorVoice": copy.deepcopy(prior[0]) if prior else None,
                    "targetVoiceCount": len(voices),
                }
            )

        for index in range(4):
            update(f"brief-zero-preroll-{index}")
        first_zero_frame = rendered_after_start
        runtime.set_parameter(key, control, zero_value)
        for index in range(zero_updates_before_recovery):
            update(f"brief-zero-first-zero-{index}")
        positive_return_frame = rendered_after_start
        runtime.set_parameter(key, control, positive_value)
        for index in range(recovery_updates_before_second_zero):
            update(f"brief-zero-recovery-{index}")
        second_zero_frame = rendered_after_start
        runtime.set_parameter(key, control, zero_value)
        for index in range(second_zero_observation_updates):
            update(f"brief-zero-second-zero-{index}")
    _raw, writer_pcm = _read_pcm16_stereo(path)
    end = start_clock + rendered_after_start
    if end > len(writer_pcm):
        raise AssertionError("brief-zero writer ended before observation crop")
    return {
        "control": control,
        "zeroValue": zero_value,
        "positiveValue": positive_value,
        "zeroUpdatesBeforeRecovery": zero_updates_before_recovery,
        "recoveryUpdatesBeforeSecondZero": recovery_updates_before_second_zero,
        "secondZeroObservationUpdates": second_zero_observation_updates,
        "firstZeroWriterFrame": first_zero_frame,
        "positiveReturnWriterFrame": positive_return_frame,
        "secondZeroWriterFrame": second_zero_frame,
        "scheduleStartDspClockFrames": start_clock,
        "renderedFrameCount": rendered_after_start,
        "writerDspBlockFrames": DSP_BUFFER_FRAMES,
        "selectionAttempts": attempts,
        "observations": observations,
        "croppedPcm": writer_pcm[start_clock:end].copy(),
    }


def _fresh_worker(
    operation: str,
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
    *,
    extras: dict[str, Any] | None = None,
) -> dict[str, Any]:
    graph_root = str(target.get("_graphRoot") or "")
    classification_path = str(target.get("_classificationPath") or "")
    if not graph_root or not classification_path:
        raise AssertionError("fresh worker graph/classification context is absent")
    request = {
        "schema": WORKER_REQUEST_SCHEMA,
        "operation": operation,
        "assettoRoot": str(assetto_root.resolve(strict=True)),
        "graphRoot": str(Path(graph_root).resolve(strict=True)),
        "classificationPath": str(Path(classification_path).resolve(strict=True)),
        "familyId": target["familyId"],
        "sourceGuid": target["sourceGuid"],
        "derivedSourceSha256": _canonical_sha(derived),
        "runtimeBankPath": str(bank.resolve(strict=True)),
        "runtimeBankSha256": _sha256(bank),
        "outputRoot": str(output_root.resolve()),
        "launcherProcessId": os.getpid(),
        "extras": copy.deepcopy(extras or {}),
    }
    request_sha = _canonical_sha(request)
    label = hashlib.sha256(
        canonical_json_bytes(
            {
                "requestSha256": request_sha,
                "nonce": str((extras or {}).get("renderId") or operation),
            }
        )
    ).hexdigest()[:16]
    work = output_root / "work" / "workers"
    request_path = work / "requests" / f"{target['sourceGuid']}-{operation}-{label}.json"
    result_path = work / "results" / f"{target['sourceGuid']}-{operation}-{label}.json"
    _write_canonical(request_path, request)
    result_path.parent.mkdir(parents=True, exist_ok=True)
    if result_path.exists():
        result_path.unlink()
    environment = dict(os.environ)
    environment["PYTHONUNBUFFERED"] = "1"
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
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=WORKER_TIMEOUT_SECONDS,
        check=False,
    )
    if completed.returncode != 0 or not result_path.is_file():
        raise AssertionError(
            f"fresh worker {operation} failed for {target['sourceGuid']}: "
            f"exit={completed.returncode} stdout={completed.stdout[-2000:]!r} "
            f"stderr={completed.stderr[-4000:]!r}"
        )
    result = _load_json(result_path, WORKER_RESULT_SCHEMA)
    payload = result.get("payload")
    if (
        result.get("requestSha256") != request_sha
        or result.get("operation") != operation
        or not isinstance(payload, dict)
        or result.get("payloadSha256") != _canonical_sha(payload)
        or result.get("freshProcessBoundary") is not True
        or not isinstance(result.get("workerProcessId"), int)
        or result.get("workerProcessId") == os.getpid()
    ):
        raise AssertionError("fresh worker request/result hash contract differs")
    payload["freshProcessEvidence"] = {
        "requestSchema": WORKER_REQUEST_SCHEMA,
        "resultSchema": WORKER_RESULT_SCHEMA,
        "operation": operation,
        "requestSha256": request_sha,
        "requestFileRelativePath": request_path.relative_to(output_root).as_posix(),
        "requestFileSha256": _sha256(request_path),
        "resultFileRelativePath": result_path.relative_to(output_root).as_posix(),
        "resultFileSha256": _sha256(result_path),
        "payloadSha256": result["payloadSha256"],
        "processBoundary": "ONE_NEW_PYTHON_PROCESS_FOR_THIS_OPERATION",
        "launcherProcessId": os.getpid(),
        "workerProcessId": result["workerProcessId"],
        "timeoutSeconds": WORKER_TIMEOUT_SECONDS,
    }
    return payload


def _render_once(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    path: Path,
    *,
    parameters: dict[str, float] | None = None,
    event_volume: float = 1.0,
    event_pitch: float = 1.0,
) -> dict[str, Any]:
    payload = _fresh_worker(
        "render",
        assetto_root,
        bank,
        target,
        derived,
        path.parents[2] if path.parents[1].name == "work" else path.parent,
        extras={
            "renderId": path.stem,
            "writerPath": str(path.resolve()),
            "parameters": parameters,
            "eventVolume": event_volume,
            "eventPitch": event_pitch,
        },
    )
    cropped_path = Path(str(payload.pop("croppedWavPath"))).resolve(strict=True)
    if _sha256(cropped_path) != payload.pop("croppedWavSha256"):
        raise AssertionError("fresh render cropped WAV hash differs")
    _raw, pcm = _read_pcm16_stereo(cropped_path)
    payload["croppedPcm"] = pcm.copy()
    cropped_path.unlink(missing_ok=True)
    return payload


def _pitch_probe(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    return _fresh_worker(
        "pitch",
        assetto_root,
        bank,
        target,
        derived,
        output_root,
    )


def _zero_gain_probe(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    output_root: Path,
    capture: dict[str, Any],
    pitch: dict[str, Any],
) -> dict[str, Any]:
    import numpy as np

    total_blocks = max(1, int(capture["frameCount"]) // DSP_BUFFER_FRAMES)
    hold_updates = min(40, max(2, (total_blocks - 12) // 2))
    lifecycle = _fresh_worker(
        "zeroGain",
        assetto_root,
        bank,
        target,
        derived,
        output_root,
        extras={"observationHoldUpdates": hold_updates},
    )
    if lifecycle["phasePolicy"] == (
        "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"
    ):
        return lifecycle

    brief_sequences: list[dict[str, Any]] = []
    for zero_updates in (1, 2):
        repeats: list[dict[str, Any]] = []
        repeat_pcm: list[Any] = []
        for repeat in range(2):
            payload = _fresh_worker(
                "briefZeroRecoveryRender",
                assetto_root,
                bank,
                target,
                derived,
                output_root,
                extras={
                    "renderId": f"brief-zero-{zero_updates}-{repeat}",
                    "writerPath": str(
                        (
                            output_root
                            / "work"
                            / "zero-pcm"
                            / (
                                f"{target['sourceGuid']}-brief-"
                                f"{zero_updates}-{repeat}.wav"
                            )
                        ).resolve()
                    ),
                    "zeroUpdatesBeforeRecovery": zero_updates,
                    "recoveryUpdatesBeforeSecondZero": 1,
                    "secondZeroObservationUpdates": 8,
                },
            )
            cropped_path = Path(str(payload.pop("croppedWavPath"))).resolve(
                strict=True
            )
            if _sha256(cropped_path) != payload.pop("croppedWavSha256"):
                raise AssertionError("brief-zero cropped WAV hash differs")
            _raw, pcm = _read_pcm16_stereo(cropped_path)
            repeat_pcm.append(pcm.copy())
            cropped_path.unlink(missing_ok=True)
            repeats.append(payload)
        if not np.array_equal(repeat_pcm[0], repeat_pcm[1]):
            raise AssertionError("brief-zero transition is not bit-exact on repeat")
        workers = [item.pop("freshProcessEvidence") for item in repeats]
        if len({item["workerProcessId"] for item in workers}) != 2:
            raise AssertionError("brief-zero transition reused one worker")
        pcm = repeat_pcm[0]
        first_zero = int(repeats[0]["firstZeroWriterFrame"])
        positive_return = int(repeats[0]["positiveReturnWriterFrame"])
        second_zero = int(repeats[0]["secondZeroWriterFrame"])
        block_metrics = []
        for start in range(first_zero, len(pcm), DSP_BUFFER_FRAMES):
            block = pcm[start : start + DSP_BUFFER_FRAMES]
            block_metrics.append(
                {
                    "writerFrameOffsetFromFirstZero": start - first_zero,
                    "maximumAbsolutePcmLsb": int(
                        np.max(np.abs(block.astype(np.int32)))
                    ),
                    "pcmPayloadSha256": hashlib.sha256(
                        block.astype("<i2", copy=False).tobytes()
                    ).hexdigest(),
                }
            )
        brief_sequences.append(
            {
                **repeats[0],
                "accepted": True,
                "independentRenderBitExact": True,
                "pcmPayloadSha256": hashlib.sha256(
                    pcm.astype("<i2", copy=False).tobytes()
                ).hexdigest(),
                "firstZeroToPositiveReturnWriterFrames": (
                    positive_return - first_zero
                ),
                "positiveReturnToSecondZeroWriterFrames": (
                    second_zero - positive_return
                ),
                "writerBlockMetricsFromFirstZero": block_metrics,
                "independentFreshProcessRenders": workers,
            }
        )
    lifecycle["briefZeroTransitionVerification"] = {
        "accepted": True,
        "controlUpdateQuantumWriterFrames": DSP_BUFFER_FRAMES,
        "frameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
        "runtimeConclusion": {
            "positiveGainReturnBeforePhaseHoldPolicy": (
                "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_"
                "WITHOUT_PHASE_OR_DEADLINE_HOLD"
            ),
            "subsequentExactZeroCrossingPolicy": (
                "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
                "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
            ),
        },
        "sequences": brief_sequences,
        "sampleNameUsedForSemantics": False,
    }

    preroll_updates = min(4, max(1, total_blocks // 8))
    comparison_hold_updates = min(
        20, max(2, total_blocks - preroll_updates - 12)
    )
    work = output_root / "work" / "zero-pcm"
    work.mkdir(parents=True, exist_ok=True)

    def render_variant(
        apply_zero: bool, *, pitch_only_reference: bool = False
    ) -> dict[str, Any]:
        label = (
            "gap"
            if apply_zero
            else "pitch-reference" if pitch_only_reference else "baseline"
        )
        writer = work / f"{target['sourceGuid']}-{label}.wav"
        payload = _fresh_worker(
            "zeroResumeRender",
            assetto_root,
            bank,
            target,
            derived,
            output_root,
            extras={
                "renderId": f"zero-resume-{label}",
                "writerPath": str(writer.resolve()),
                "applyZero": apply_zero,
                "pitchOnlyReference": pitch_only_reference,
                "liveRpmPitch": pitch.get("mode") == "LIVE_RPM_RATIO",
                "prerollUpdates": preroll_updates,
                "zeroHoldUpdates": comparison_hold_updates,
            },
        )
        cropped_path = Path(str(payload.pop("croppedWavPath"))).resolve(strict=True)
        if _sha256(cropped_path) != payload.pop("croppedWavSha256"):
            raise AssertionError("zero/resume cropped WAV hash differs")
        _raw, pcm = _read_pcm16_stereo(cropped_path)
        payload["croppedPcm"] = pcm.copy()
        cropped_path.unlink(missing_ok=True)
        writer.unlink(missing_ok=True)
        return payload

    baseline = render_variant(False)
    gap = render_variant(True)
    pitch_reference = render_variant(False, pitch_only_reference=True)
    baseline_pcm_i16 = baseline.pop("croppedPcm")
    gap_pcm_i16 = gap.pop("croppedPcm")
    baseline_pcm = baseline_pcm_i16.astype(np.float64)
    gap_pcm = gap_pcm_i16.astype(np.float64)
    restore_frame = int(gap["restoredStartFrame"])
    if restore_frame != int(baseline["restoredStartFrame"]):
        raise AssertionError(
            f"restore={baseline['restoredStartFrame']}/{restore_frame}"
        )
    deadline_extension = len(gap_pcm) - len(baseline_pcm)
    zero_hold_frames = comparison_hold_updates * DSP_BUFFER_FRAMES
    if (
        deadline_extension < 0
        or deadline_extension > zero_hold_frames
        or deadline_extension % DSP_BUFFER_FRAMES
    ):
        raise AssertionError(
            "zero-gain deadline extension is outside the bounded hold: "
            f"frames={len(baseline_pcm)}/{len(gap_pcm)} "
            f"extension={deadline_extension}"
        )
    pending_runtime_semantic = lifecycle.get("runtimeSemantic")
    if not isinstance(pending_runtime_semantic, dict):
        raise AssertionError("direct zero-gain channel lifecycle is absent")
    virtual_start_observation = pending_runtime_semantic.get(
        "virtualStateFirstObservationIndex"
    )
    holds_phase = virtual_start_observation is not None
    phase_hold_latency = (
        int(virtual_start_observation) * DSP_BUFFER_FRAMES
        if holds_phase
        else 0
    )
    deadline_inferred_phase_hold_latency = zero_hold_frames - deadline_extension
    deadline_hold_onset_error = abs(
        deadline_inferred_phase_hold_latency - phase_hold_latency
    )
    if (
        (holds_phase and (phase_hold_latency <= 0 or deadline_hold_onset_error > 256))
        or (not holds_phase and deadline_extension != 0)
    ):
        raise AssertionError(
            "direct virtual-state onset and natural-deadline extension differ: "
            f"virtualIndex={virtual_start_observation} latency={phase_hold_latency} "
            f"deadlineInferred={deadline_inferred_phase_hold_latency} "
            f"error={deadline_hold_onset_error}"
        )
    zero_start_frame = preroll_updates * DSP_BUFFER_FRAMES
    zero_output = gap_pcm_i16[zero_start_frame:restore_frame]
    if len(zero_output) != zero_hold_frames:
        raise AssertionError("zero-output PCM window differs from authored hold")
    zero_output_blocks = [
        zero_output[start : start + DSP_BUFFER_FRAMES]
        for start in range(0, len(zero_output), DSP_BUFFER_FRAMES)
    ]
    pitch_reference_pcm = pitch_reference["croppedPcm"]
    pitch_reference_zero = pitch_reference_pcm[zero_start_frame:restore_frame]
    if len(pitch_reference_zero) != len(zero_output):
        raise AssertionError("pitch-only zero-transition reference length differs")
    transition_gain_blocks: list[dict[str, Any]] = []
    for block_index, (target_block_i16, reference_block_i16) in enumerate(
        zip(
            zero_output_blocks,
            [
                pitch_reference_zero[start : start + DSP_BUFFER_FRAMES]
                for start in range(0, len(pitch_reference_zero), DSP_BUFFER_FRAMES)
            ],
        )
    ):
        target_block = target_block_i16.astype(np.float64)
        reference_block = reference_block_i16.astype(np.float64)
        best: tuple[float, int, float, Any] | None = None
        for lag in range(-4, 5):
            if lag < 0:
                target_aligned = target_block[-lag:]
                reference_aligned = reference_block[: lag or None]
            elif lag > 0:
                target_aligned = target_block[:-lag]
                reference_aligned = reference_block[lag:]
            else:
                target_aligned = target_block
                reference_aligned = reference_block
            denominator = float(np.sum(reference_aligned * reference_aligned))
            fitted_gain = (
                max(
                    0.0,
                    float(np.sum(reference_aligned * target_aligned))
                    / denominator,
                )
                if denominator > 0.0
                else 0.0
            )
            residual = target_aligned - reference_aligned * fitted_gain
            mse = float(np.mean(residual * residual))
            candidate = (mse, lag, fitted_gain, residual)
            if best is None or candidate[0] < best[0]:
                best = candidate
        assert best is not None
        mse, lag, fitted_gain, residual = best
        target_rms = float(np.sqrt(np.mean(target_block * target_block)))
        residual_rms = math.sqrt(mse)
        transition_gain_blocks.append(
            {
                "writerFrameOffset": block_index * DSP_BUFFER_FRAMES,
                "fittedLinearGain": fitted_gain,
                "phaseLagWriterFrames": lag,
                "targetMaximumAbsolutePcmLsb": int(
                    np.max(np.abs(target_block_i16.astype(np.int32)))
                ),
                "residualMaximumAbsolutePcmLsb": float(
                    np.max(np.abs(residual))
                ),
                "residualBelowTargetDb": (
                    240.0
                    if residual_rms == 0.0
                    else 20.0
                    * math.log10(max(target_rms, 1.0e-12) / residual_rms)
                ),
            }
        )
    target_transition = zero_output.astype(np.float64)
    reference_transition = pitch_reference_zero.astype(np.float64)
    nonzero_transition_frames = np.flatnonzero(
        np.any(zero_output != 0, axis=1)
    )
    exact_zero_frame = (
        int(nonzero_transition_frames[-1]) + 1
        if len(nonzero_transition_frames)
        else 0
    )
    best_transition: tuple[float, float, int, int, Any] | None = None
    for lag in range(-4, 5):
        shifted_reference = np.zeros_like(reference_transition)
        if lag < 0:
            shifted_reference[-lag:] = reference_transition[:lag]
        elif lag > 0:
            shifted_reference[:-lag] = reference_transition[lag:]
        else:
            shifted_reference[:] = reference_transition
        starts = (
            [0]
            if exact_zero_frame == 0
            else range(max(0, exact_zero_frame - 512), exact_zero_frame)
        )
        for fade_start in starts:
            envelope = np.ones(len(target_transition), dtype=np.float64)
            fade_frames = exact_zero_frame - fade_start
            if fade_frames > 0:
                envelope[fade_start:exact_zero_frame] = 1.0 - (
                    np.arange(fade_frames, dtype=np.float64) / fade_frames
                )
            envelope[exact_zero_frame:] = 0.0
            predicted = np.rint(shifted_reference * envelope[:, None])
            residual = target_transition - predicted
            candidate = (
                float(np.max(np.abs(residual))),
                float(np.mean(residual * residual)),
                lag,
                fade_start,
                predicted,
            )
            if best_transition is None or candidate[:2] < best_transition[:2]:
                best_transition = candidate
    assert best_transition is not None
    (
        _transition_maximum_error,
        _transition_mse,
        transition_lag,
        transition_fade_start,
        transition_predicted,
    ) = best_transition
    transition_residual = target_transition - transition_predicted
    transition_target_rms = float(
        np.sqrt(np.mean(target_transition * target_transition))
    )
    transition_residual_rms = float(
        np.sqrt(np.mean(transition_residual * transition_residual))
    )
    transition_residual_snr = (
        240.0
        if transition_residual_rms == 0.0
        else 20.0
        * math.log10(
            max(transition_target_rms, 1.0e-12) / transition_residual_rms
        )
    )
    transition_fit = {
        "accepted": float(np.max(np.abs(transition_residual))) <= 1.0,
        "policy": (
            "IMMEDIATE_EXACT_ZERO"
            if exact_zero_frame == 0
            else "RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO"
        ),
        "model": "PIECEWISE_LINEAR_GAIN_IN_WRITER_FRAME_DOMAIN",
        "retainPreZeroGainWriterFrames": transition_fade_start,
        "linearFadeWriterFrames": exact_zero_frame - transition_fade_start,
        "exactZeroFromWriterFrame": exact_zero_frame,
        "pitchOnlyReferencePhaseOffsetWriterFrames": transition_lag,
        "phaseOffsetBoundWriterFrames": 4,
        "residualMaximumAbsolutePcmLsb": float(
            np.max(np.abs(transition_residual))
        ),
        "residualBelowTargetDb": transition_residual_snr,
        "retainedGain": "PRE_ZERO_AUTHORED_COMBINED_GAIN",
        "pitchDuringPassThrough": (
            "LIVE_CURRENT_RPM_PITCH" if pitch.get("mode") == "LIVE_RPM_RATIO"
            else "AUTHORED_STATIC_BAKED_PITCH"
        ),
        "acceptanceBoundMaximumAbsolutePcmLsb": 1.0,
    }
    zero_output_gate = {
        "accepted": True,
        "method": "TARGET_ONLY_PCM16_DURING_EXACT_ZERO_CONTROL_HOLD",
        "zeroStartWriterFrame": zero_start_frame,
        "zeroEndWriterFrameExclusive": restore_frame,
        "frameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
        "allPcmSamplesZeroFromFirstWriterFrame": not np.any(zero_output),
        "maximumAbsolutePcmLsbPerWriterBlock": [
            int(np.max(np.abs(block.astype(np.int32)))) if len(block) else 0
            for block in zero_output_blocks
        ],
        "pitchOnlyReferenceGainFitPerWriterBlock": transition_gain_blocks,
        "gainFitModel": "CONSTANT_LINEAR_GAIN_PER_256_WRITER_FRAMES",
        "compactTransitionFit": transition_fit,
        "writerDspBlockFrames": DSP_BUFFER_FRAMES,
    }
    if transition_fit["accepted"] is not True:
        diagnostic = output_root / "blocked" / (
            f"{target['sourceGuid']}-zero-output-transition.json"
        )
        _write_canonical(diagnostic, zero_output_gate)
        raise AssertionError(
            f"exact-zero output transition has no compact fit: {diagnostic}"
        )
    def aligned_metric(
        settle_updates: int, lag: int
    ) -> tuple[float, float, float, int, int, int, Any, Any]:
        candidate_gap_start = restore_frame + settle_updates * DSP_BUFFER_FRAMES
        nominal_baseline_start = candidate_gap_start - deadline_extension
        candidate_baseline_start = nominal_baseline_start + lag
        if candidate_baseline_start < 0:
            return (
                -float("inf"), float("inf"), float("inf"), 0,
                candidate_baseline_start, candidate_gap_start, None, None,
            )
        frames = min(
            len(gap_pcm) - DSP_BUFFER_FRAMES - candidate_gap_start,
            len(baseline_pcm) - DSP_BUFFER_FRAMES - candidate_baseline_start,
        )
        if frames < 1024:
            return (
                -float("inf"), float("inf"), float("inf"), 0,
                candidate_baseline_start, candidate_gap_start, None, None,
            )
        candidate_left = baseline_pcm[
            candidate_baseline_start : candidate_baseline_start + frames
        ]
        candidate_right = gap_pcm[
            candidate_gap_start : candidate_gap_start + frames
        ]
        candidate_difference = candidate_left - candidate_right
        candidate_left_rms = float(np.sqrt(np.mean(candidate_left * candidate_left)))
        candidate_right_rms = float(np.sqrt(np.mean(candidate_right * candidate_right)))
        candidate_difference_rms = float(
            np.sqrt(np.mean(candidate_difference * candidate_difference))
        )
        candidate_gain_error = abs(
            20.0
            * math.log10(
                max(candidate_left_rms, 1.0e-12)
                / max(candidate_right_rms, 1.0e-12)
            )
        )
        candidate_snr = (
            240.0
            if candidate_difference_rms == 0.0
            else 20.0
            * math.log10(
                max(candidate_left_rms, 1.0e-12) / candidate_difference_rms
            )
        )
        return (
            candidate_snr,
            candidate_gain_error,
            candidate_difference_rms,
            frames,
            candidate_baseline_start,
            candidate_gap_start,
            candidate_left,
            candidate_right,
        )

    best_settle, best_lag, best_metric = max(
        (
            (settle, lag, aligned_metric(settle, lag))
            for settle in range(1, 17)
            for lag in range(-512, 513)
        ),
        key=lambda item: (item[2][0], item[0], -abs(item[1])),
    )
    (
        difference_below_reference_db,
        gain_error_db,
        _difference_rms,
        compared_frames,
        baseline_compare_start,
        gap_compare_start,
        left,
        right,
    ) = best_metric
    if left is None or right is None:
        raise AssertionError("zero/resume PCM alignment search found no window")
    difference = left - right
    correlation_denominator = float(
        np.sqrt(np.sum(left * left) * np.sum(right * right))
    )
    normalized_correlation = (
        1.0
        if correlation_denominator == 0.0 and np.array_equal(left, right)
        else (
            float(np.sum(left * right)) / correlation_denominator
            if correlation_denominator > 0.0
            else 0.0
        )
    )
    pcm_bit_exact_bound = difference_below_reference_db >= 60.0
    maximum_absolute_difference_lsb = int(np.max(np.abs(difference)))
    integer_phase_accepted = gain_error_db <= 0.02 and (
        normalized_correlation >= 0.999
        or maximum_absolute_difference_lsb <= 1
    )
    fractional_phase = None
    if not integer_phase_accepted:
        fractional_phase = _fractional_phase_alignment_metrics(right, left)
    fractional_phase_accepted = bool(
        isinstance(fractional_phase, dict)
        and float(fractional_phase["gainErrorDb"]) <= 0.02
        and float(fractional_phase["normalizedCorrelation"]) >= 0.999
        and float(fractional_phase["differenceBelowReferenceDb"]) >= 35.0
        and float(fractional_phase["earlyLateEstimateDifferenceFrames"])
        <= 0.01
    )
    accepted = integer_phase_accepted or fractional_phase_accepted
    restore_fractional_offset = (
        float(fractional_phase["phaseOffsetReferenceFrames"])
        if fractional_phase_accepted and fractional_phase is not None
        else 0.0
    )
    restore_phase_offset = float(best_lag) + restore_fractional_offset
    restore_phase_treatment = (
        "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET"
        if abs(restore_phase_offset) <= 1.0e-9
        else "APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET"
    )
    if fractional_phase_accepted and fractional_phase is not None:
        difference_below_reference_db = float(
            fractional_phase["differenceBelowReferenceDb"]
        )
        gain_error_db = float(fractional_phase["gainErrorDb"])
        normalized_correlation = float(fractional_phase["normalizedCorrelation"])
        maximum_absolute_difference_lsb = int(
            math.ceil(float(fractional_phase["maximumAbsoluteDifferencePcmLsb"]))
        )
        pcm_bit_exact_bound = difference_below_reference_db >= 60.0
    pitch_reference_fresh_process = pitch_reference.pop("freshProcessEvidence")
    comparison = {
        "accepted": accepted,
        "comparison": "POST_RESTORE_PHASE_AT_FROZEN_LOGICAL_AGE",
        "prerollUpdates": preroll_updates,
        "zeroHoldUpdates": comparison_hold_updates,
        "restoreFrame": restore_frame,
        "baselineComparisonStartFrame": baseline_compare_start,
        "zeroGapComparisonStartFrame": gap_compare_start,
        "bestBaselinePhaseLagFrames": best_lag,
        "postRestorePhaseTreatment": restore_phase_treatment,
        "restoreCapturePcmPhaseOffsetFrames": restore_phase_offset,
        "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames": 512.0,
        "fractionalPhaseOnlyAlignment": fractional_phase,
        "postRestoreSettlingUpdates": best_settle,
        "comparedFrameCount": len(left),
        "zeroHoldFrames": zero_hold_frames,
        "logicalDeadlineExtensionFrames": deadline_extension,
        "phaseAdvanceBeforeHoldFrames": phase_hold_latency,
        "naturalDeadlineInferredPhaseHoldLatencyWriterFrames": (
            deadline_inferred_phase_hold_latency
        ),
        "naturalDeadlineHoldOnsetErrorWriterFrames": deadline_hold_onset_error,
        "naturalDeadlineHoldOnsetErrorBoundWriterFrames": 256,
        "gainErrorDb": gain_error_db,
        "differenceBelowReferenceDb": difference_below_reference_db,
        "normalizedCorrelation": normalized_correlation,
        "postRestorePcmMeets60DbDifferenceBound": pcm_bit_exact_bound,
        "residualDifferenceBoundDb": difference_below_reference_db,
        "maximumAbsoluteDifferenceLsb": maximum_absolute_difference_lsb,
        "zeroOutputGateVerification": zero_output_gate,
        "pitchOnlyTransitionReference": {
            **{
                key: value
                for key, value in pitch_reference.items()
                if key != "croppedPcm"
            },
            "freshProcess": pitch_reference_fresh_process,
        },
        "acceptanceBounds": {
            "maximumGainErrorDb": 0.02,
            "minimumNormalizedCorrelationForPhase": 0.999,
            "maximumAbsoluteDifferencePcmLsbAlternative": 1,
            "pcmBitExactDifferenceTargetDb": 60.0,
            "minimumFractionalPhaseNormalizedCorrelation": 0.999,
            "minimumFractionalPhaseDifferenceBelowReferenceDb": 35.0,
            "maximumFractionalPhaseEarlyLateDifferenceFrames": 0.01,
            "maximumAbsoluteRestoreCapturePcmPhaseOffsetFrames": 512.0,
        },
        "baselineFreshProcess": baseline.pop("freshProcessEvidence"),
        "zeroGapFreshProcess": gap.pop("freshProcessEvidence"),
        "baselineObservation": baseline,
        "zeroGapObservation": gap,
    }
    if not accepted:
        diagnostic = output_root / "blocked" / f"{target['sourceGuid']}-zero-resume.json"
        _write_canonical(diagnostic, comparison)
        raise AssertionError(
            f"post-restore PCM does not prove retained logical phase: {diagnostic}"
        )
    lifecycle["phasePolicy"] = (
        "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE"
        if holds_phase
        else "ADVANCE_LOGICAL_AND_DECODE_PHASE_AT_ACTIVE_PITCH_WHILE_EXACT_ZERO"
    )
    compact_transition = zero_output_gate["compactTransitionFit"]
    zero_transition = {
        "policy": compact_transition["policy"],
        "frameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
        "gainInterpolation": "LINEAR_PER_WRITER_FRAME",
        "gainAtTransitionStart": 1.0,
        "gainAtExactZero": 0.0,
        "retainPreZeroGainWriterFrames": compact_transition[
            "retainPreZeroGainWriterFrames"
        ],
        "linearFadeWriterFrames": compact_transition["linearFadeWriterFrames"],
        "exactZeroFromWriterFrame": compact_transition[
            "exactZeroFromWriterFrame"
        ],
        "pitchDuringTransition": compact_transition["pitchDuringPassThrough"],
        "phaseTreatment": restore_phase_treatment,
        "restoreCapturePcmPhaseOffsetFrames": restore_phase_offset,
        "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames": 512.0,
        "positiveGainReturnBeforePhaseHoldPolicy": (
            "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_"
            "WITHOUT_PHASE_OR_DEADLINE_HOLD"
        ),
        "subsequentExactZeroCrossingPolicy": (
            "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
            "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
        ),
        "residualMaximumAbsolutePcmLsb": compact_transition[
            "residualMaximumAbsolutePcmLsb"
        ],
        "acceptanceBoundMaximumAbsolutePcmLsb": compact_transition[
            "acceptanceBoundMaximumAbsolutePcmLsb"
        ],
    }
    common_runtime = {
        "mixerZeroGateAction": (
            "APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;"
            "DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING"
        ),
        "ordinaryNonzeroGainSmoothingUnaffected": True,
        "writerDspBlockFrames": DSP_BUFFER_FRAMES,
        "zeroTransition": zero_transition,
        "channelGetPositionWhileVirtualIsRuntimeAuthoritative": False,
    }
    if holds_phase:
        lifecycle["runtimeSemantic"] = {
            "kind": "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
            **common_runtime,
            "decodePhaseBeforeHold": "CURRENT_ACTIVE_VOICE_PITCH",
            "phaseHoldLatencyWriterFrames": phase_hold_latency,
            "phaseAndDeadlineAdvanceWriterFramesBeforeHold": phase_hold_latency,
            "phaseHoldLatencyFrameDomain": (
                "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
            ),
            "holdDecodePhaseAfterLatency": True,
            "pauseNaturalEndDeadlineWhileHeld": True,
            "reaudibilizationBeforeDeadline": "CONTINUE_FROM_HELD_LOGICAL_PHASE",
        }
    else:
        lifecycle["runtimeSemantic"] = {
            "kind": "ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO",
            **common_runtime,
            "decodePhaseWhileExactZero": "CURRENT_ACTIVE_VOICE_PITCH",
            "naturalEndDeadlineAdvancesWhileExactZero": True,
            "reaudibilizationBeforeDeadline": (
                "CONTINUE_FROM_ADVANCED_LOGICAL_PHASE"
            ),
        }
    lifecycle["postRestorePcmVerification"] = comparison
    return lifecycle


def _run_worker(request_path: Path, result_path: Path) -> None:
    request = _load_json(request_path.resolve(strict=True), WORKER_REQUEST_SCHEMA)
    request_sha = _canonical_sha(request)
    if int(request.get("launcherProcessId", -1)) == os.getpid():
        raise AssertionError("worker did not cross a process boundary")
    graph_root = Path(str(request["graphRoot"])).resolve(strict=True)
    classification_path = Path(str(request["classificationPath"])).resolve(strict=True)
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    row = next(
        (
            item
            for item in classification["sourceDecisions"]
            if str(item["familyId"]) == str(request["familyId"])
            and _guid(item["sourceGuid"]) == _guid(request["sourceGuid"])
        ),
        None,
    )
    if row is None:
        raise AssertionError("worker source row is absent")
    summary = _load_json(graph_root / "summary.json", GRAPH_SUMMARY_SCHEMA)
    families = {str(item["familyId"]): item for item in summary["families"]}
    target = _target(graph_root, families, row)
    derived = derive_manifest_one_shot_curves(target["graph"], row)
    if _canonical_sha(derived) != request["derivedSourceSha256"]:
        raise AssertionError("worker derived-source hash differs")
    assetto_root = Path(str(request["assettoRoot"])).resolve(strict=True)
    bank = Path(str(request["runtimeBankPath"])).resolve(strict=True)
    bank_sha = _sha256(bank)
    if bank_sha != request["runtimeBankSha256"]:
        raise AssertionError("worker runtime-bank hash differs")
    output_root = Path(str(request["outputRoot"])).resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    extras = request.get("extras") or {}
    operation = str(request["operation"])
    if operation == "render":
        writer = Path(str(extras["writerPath"])).resolve()
        local = _render_once_local(
            assetto_root,
            bank,
            target,
            derived,
            writer,
            parameters=(
                {
                    str(name): float(value)
                    for name, value in extras["parameters"].items()
                }
                if isinstance(extras.get("parameters"), dict)
                else None
            ),
            event_volume=float(extras.get("eventVolume", 1.0)),
            event_pitch=float(extras.get("eventPitch", 1.0)),
        )
        pcm = local.pop("croppedPcm")
        cropped = writer.with_name(f".{writer.stem}.cropped.wav")
        _write_pcm16_stereo(cropped, pcm.astype("<i2").tobytes())
        payload = {
            **local,
            "croppedWavPath": str(cropped),
            "croppedWavSha256": _sha256(cropped),
        }
    elif operation == "pitch":
        payload = _pitch_probe_local(
            assetto_root, bank, target, derived, output_root
        )
    elif operation == "zeroGain":
        payload = _zero_gain_probe_local(
            assetto_root,
            bank,
            target,
            derived,
            output_root,
            observation_hold_updates=int(extras["observationHoldUpdates"]),
        )
    elif operation == "zeroResumeRender":
        writer = Path(str(extras["writerPath"])).resolve()
        local = _zero_resume_render_local(
            assetto_root,
            bank,
            target,
            derived,
            writer,
            apply_zero=bool(extras["applyZero"]),
            pitch_only_reference=bool(extras.get("pitchOnlyReference", False)),
            live_rpm_pitch=bool(extras.get("liveRpmPitch", False)),
            preroll_updates=int(extras["prerollUpdates"]),
            zero_hold_updates=int(extras["zeroHoldUpdates"]),
        )
        pcm = local.pop("croppedPcm")
        cropped = writer.with_name(f".{writer.stem}.cropped.wav")
        _write_pcm16_stereo(cropped, pcm.astype("<i2").tobytes())
        payload = {
            **local,
            "croppedWavPath": str(cropped),
            "croppedWavSha256": _sha256(cropped),
        }
    elif operation == "briefZeroRecoveryRender":
        writer = Path(str(extras["writerPath"])).resolve()
        local = _brief_zero_recovery_render_local(
            assetto_root,
            bank,
            target,
            derived,
            writer,
            zero_updates_before_recovery=int(
                extras["zeroUpdatesBeforeRecovery"]
            ),
            recovery_updates_before_second_zero=int(
                extras["recoveryUpdatesBeforeSecondZero"]
            ),
            second_zero_observation_updates=int(
                extras["secondZeroObservationUpdates"]
            ),
        )
        pcm = local.pop("croppedPcm")
        cropped = writer.with_name(f".{writer.stem}.cropped.wav")
        _write_pcm16_stereo(cropped, pcm.astype("<i2").tobytes())
        payload = {
            **local,
            "croppedWavPath": str(cropped),
            "croppedWavSha256": _sha256(cropped),
        }
    else:
        raise AssertionError(f"unsupported worker operation: {operation}")
    if _sha256(bank) != bank_sha:
        raise AssertionError("worker runtime bank changed")
    result = {
        "schema": WORKER_RESULT_SCHEMA,
        "requestSha256": request_sha,
        "operation": operation,
        "freshProcessBoundary": True,
        "workerProcessId": os.getpid(),
        "payload": payload,
        "payloadSha256": _canonical_sha(payload),
    }
    _write_canonical(result_path.resolve(), result)



def _curve_value(curve: list[list[float]], control: float) -> float:
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
    raise AssertionError("curve domain lookup failed")


def _audible_probe_rpm(derived: dict[str, Any]) -> float:
    capture = float(derived["captureRpm"])
    rpm_region = next(
        item
        for item in derived["triggerSemantics"]["runtimeRegions"]
        if item["parameter"] == "rpms"
    )
    low = float(rpm_region["minimum"])
    high = float(rpm_region["maximum"])
    span = high - low
    candidates = {
        low + span * fraction for fraction in (0.1, 0.25, 0.5, 0.75, 0.9)
    }
    candidates.update(
        float(point[0])
        for point in derived["rpmCurve"]
        if low <= float(point[0]) <= high
    )
    audible = [
        value
        for value in candidates
        if value > 0.0
        and not math.isclose(value, capture, rel_tol=0.01, abs_tol=10.0)
        and _curve_value(derived["rpmCurve"], value) >= 0.05
    ]
    if not audible:
        raise AssertionError("no distinct audible RPM exists for DSP routing probe")
    return max(audible, key=lambda value: abs(math.log(value / capture)))


def _fractional_phase_alignment_metrics(
    target_pcm: Any, reference_pcm: Any
) -> dict[str, Any]:
    """Compare two deterministic renders after one bounded phase-only shift.

    FMOD's instrument AutoPitch and EventInstance pitch controls can produce
    the same final Channel pitch through different resampler paths.  At voice
    onset those paths differ by up to a small fractional source frame.  A raw
    PCM subtraction would mislabel that phase origin as routed DSP timbre.
    The shift below is estimated once for the complete stereo waveform and is
    accepted only when early/late estimates agree.  No gain, EQ, or spectral
    correction is fitted.
    """

    import numpy as np

    left = np.asarray(target_pcm, dtype=np.float64)
    right = np.asarray(reference_pcm, dtype=np.float64)
    frames = min(len(left), len(right))
    if frames < 4096 or left.ndim != 2 or right.ndim != 2 or left.shape[1] != 2:
        raise AssertionError("fractional phase comparison requires stereo PCM")
    left = left[:frames]
    right = right[:frames]
    edge = DSP_BUFFER_FRAMES * 2
    frequencies = np.fft.rfftfreq(frames)
    reference_spectra = [np.fft.rfft(right[:, channel]) for channel in range(2)]

    def shifted(offset: float) -> Any:
        phase = np.exp(2j * np.pi * frequencies * offset)
        return np.column_stack(
            [
                np.fft.irfft(reference_spectra[channel] * phase, n=frames)
                for channel in range(2)
            ]
        )

    def score(offset: float, start: int, end: int) -> float:
        candidate = shifted(offset)[start:end]
        target = left[start:end]
        difference = target - candidate
        return -float(np.mean(difference * difference))

    def search(start: int, end: int) -> tuple[float, float]:
        best_offset = 0.0
        best_score = -float("inf")
        for center, radius, step in (
            (0.0, 4.0, 0.125),
            (None, 0.125, 0.005),
            (None, 0.005, 0.0002),
        ):
            origin = best_offset if center is None else center
            count = int(round((2.0 * radius) / step))
            for index in range(count + 1):
                offset = origin - radius + index * step
                value = score(offset, start, end)
                if value > best_score:
                    best_offset = offset
                    best_score = value
        return best_offset, best_score

    comparison_start = edge
    comparison_end = frames - edge
    midpoint = comparison_start + (comparison_end - comparison_start) // 2
    early_offset, _ = search(comparison_start, midpoint)
    late_offset, _ = search(midpoint, comparison_end)
    full_offset, _ = search(comparison_start, comparison_end)
    aligned = shifted(full_offset)[comparison_start:comparison_end]
    target = left[comparison_start:comparison_end]
    difference = target - aligned
    target_rms = float(np.sqrt(np.mean(target * target)))
    aligned_rms = float(np.sqrt(np.mean(aligned * aligned)))
    difference_rms = float(np.sqrt(np.mean(difference * difference)))
    gain_error_db = abs(
        20.0
        * math.log10(max(target_rms, 1.0e-12) / max(aligned_rms, 1.0e-12))
    )
    snr_db = (
        240.0
        if difference_rms == 0.0
        else 20.0 * math.log10(max(target_rms, 1.0e-12) / difference_rms)
    )
    denominator = float(
        np.sqrt(np.sum(target * target) * np.sum(aligned * aligned))
    )
    correlation = (
        float(np.sum(target * aligned)) / denominator if denominator > 0.0 else 0.0
    )
    return {
        "method": "ONE_STEREO_FOURIER_FRACTIONAL_FRAME_PHASE_SHIFT_ONLY",
        "phaseOffsetReferenceFrames": full_offset,
        "phaseOffsetFrameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
        "maximumAbsolutePhaseSearchFrames": 4.0,
        "earlyEstimateFrames": early_offset,
        "lateEstimateFrames": late_offset,
        "earlyLateEstimateDifferenceFrames": abs(early_offset - late_offset),
        "comparedFrameCount": len(target),
        "edgeCropFramesPerSide": edge,
        "gainErrorDb": gain_error_db,
        "differenceBelowReferenceDb": snr_db,
        "normalizedCorrelation": correlation,
        "maximumAbsoluteDifferencePcmLsb": float(np.max(np.abs(difference))),
        "gainOrSpectralCorrectionFitted": False,
    }


def _dynamic_dsp_probe(
    assetto_root: Path,
    bank: Path,
    target: dict[str, Any],
    derived: dict[str, Any],
    pitch: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    """Distinguish bank-wide graph ambiguity from source-routed automation."""

    import numpy as np

    controllers = derived["unsupported"][
        "bankWideUnattributedRpmThrottleDspAutomation"
    ]
    if not controllers:
        return {
            "accepted": True,
            "unattributedControllerCount": 0,
            "runtimeTreatment": "NO_UNATTRIBUTED_RUNTIME_DSP_AUTOMATION",
            "targetOnlyComparisonRequired": False,
        }
    probe_rpm = _audible_probe_rpm(derived)
    capture_rpm = float(derived["captureRpm"])
    gain = _curve_value(derived["rpmCurve"], probe_rpm)
    if gain <= 0.0:
        raise AssertionError("DSP probe selected an authored-silent RPM")
    probe_parameters = {
        str(name): float(value)
        for name, value in derived["captureParameterValues"].items()
    }
    probe_parameters["rpms"] = probe_rpm
    reference_parameters = {
        str(name): float(value)
        for name, value in derived["captureParameterValues"].items()
    }
    pitch_ratio = (
        probe_rpm / capture_rpm
        if pitch["mode"] == "LIVE_RPM_RATIO"
        else 1.0
    )
    work = output_root / "work" / "dsp"
    work.mkdir(parents=True, exist_ok=True)
    target_renders = []
    reference_renders = []
    for repeat in range(2):
        target_renders.append(
            _render_once(
                assetto_root,
                bank,
                target,
                derived,
                work / f"{target['sourceGuid']}-parameter-{repeat}.wav",
                parameters=probe_parameters,
            )
        )
        reference_renders.append(
            _render_once(
                assetto_root,
                bank,
                target,
                derived,
                work / f"{target['sourceGuid']}-equivalent-{repeat}.wav",
                parameters=reference_parameters,
                event_volume=gain,
                event_pitch=pitch_ratio,
            )
        )
    target_pcm = [item.pop("croppedPcm") for item in target_renders]
    reference_pcm = [item.pop("croppedPcm") for item in reference_renders]
    if not np.array_equal(target_pcm[0], target_pcm[1]) or not np.array_equal(
        reference_pcm[0], reference_pcm[1]
    ):
        raise AssertionError("DSP routing comparison is not bit-exact on repeat")
    phase = _fractional_phase_alignment_metrics(target_pcm[0], reference_pcm[0])
    process_evidence = [
        item.pop("freshProcessEvidence")
        for item in [*target_renders, *reference_renders]
    ]
    if len({item["workerProcessId"] for item in process_evidence}) != 4:
        raise AssertionError("DSP comparison reused a worker process")
    for path in work.glob(f"{target['sourceGuid']}-*.wav"):
        path.unlink(missing_ok=True)
    not_routed = (
        phase["gainErrorDb"] <= 0.02
        and phase["differenceBelowReferenceDb"] >= 60.0
        and phase["normalizedCorrelation"] >= 0.999999
        and phase["earlyLateEstimateDifferenceFrames"] <= 0.01
    )
    return {
        "accepted": not_routed,
        "unattributedControllerCount": len(controllers),
        "runtimeTreatment": (
            "TARGET_ONLY_PROVEN_NOT_ROUTED_TO_SOURCE"
            if not_routed
            else "UNRESOLVED_SOURCE_ROUTED_DSP_AUTOMATION"
        ),
        "targetOnlyComparisonRequired": True,
        "captureRpm": capture_rpm,
        "probeRpm": probe_rpm,
        "expectedRelativeGain": gain,
        "equivalentEventPitch": pitch_ratio,
        "comparedFrameCount": phase["comparedFrameCount"],
        "gainErrorDb": phase["gainErrorDb"],
        "differenceBelowReferenceDb": phase["differenceBelowReferenceDb"],
        "maximumAbsoluteDifferenceLsb": phase[
            "maximumAbsoluteDifferencePcmLsb"
        ],
        "phaseOnlyAlignment": phase,
        "independentTargetRepeatBitExact": True,
        "independentEquivalentRepeatBitExact": True,
        "independentFreshProcessRenders": process_evidence,
        "acceptanceBounds": {
            "maximumGainErrorDb": 0.02,
            "minimumDifferenceBelowReferenceDb": 60.0,
            "minimumNormalizedCorrelation": 0.999999,
            "maximumEarlyLatePhaseEstimateDifferenceFrames": 0.01,
        },
        "sampleNameUsedForDspSemantics": False,
    }


def _release_source_guids(plan: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    families = plan.get("families")
    if plan.get("schemaVersion") != 2 or not isinstance(families, list):
        raise ValueError("release plan is not capture-plan schema version 2")
    for family in families:
        if not isinstance(family, dict):
            continue
        for recipe in family.get("recipes", []):
            if not isinstance(recipe, dict) or recipe.get("role") != "ENGINE_TRANSIENT":
                continue
            source_guid = _guid(recipe.get("sourceGuid"))
            if source_guid:
                result.add(source_guid)
    return result


def probe_catalog(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    plan_path: Path,
    output_root: Path,
    *,
    selected_source_guids: Iterable[str] = (),
    limit: int | None = None,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    classification_path = classification_path.resolve(strict=True)
    plan_path = plan_path.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    summary = _load_json(graph_root / "summary.json", GRAPH_SUMMARY_SCHEMA)
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    plan = _load_json(plan_path)
    classification_file_sha = _sha256(classification_path)
    classification_canonical_sha = _canonical_sha(classification)
    plan_file_sha = _sha256(plan_path)
    plan_canonical_sha = _canonical_sha(plan)
    implementation_file_sha = _sha256(Path(__file__).resolve())
    release_guids = _release_source_guids(plan)
    summary_families = {
        str(item["familyId"]): item for item in summary["families"]
    }
    requested = {_guid(value) for value in selected_source_guids if _guid(value)}
    rows = [
        row
        for row in classification["sourceDecisions"]
        if _guid(row.get("sourceGuid")) in release_guids
        and row.get("policy") == "allowCandidate"
        and row.get("lifetime") == "oneShot"
        and row.get("role") in ALLOWED_ROLES
        and (not requested or _guid(row.get("sourceGuid")) in requested)
    ]
    rows.sort(key=lambda row: (str(row["familyId"]), _guid(row["sourceGuid"])))
    if limit is not None:
        rows = rows[:limit]
    if not rows:
        raise ValueError("no release-selected engine transient rows")

    partial_path = output_root / "partial.json"
    completed: dict[str, dict[str, Any]] = {}
    if partial_path.is_file():
        partial = _load_json(partial_path)
        if (
            partial.get("schema") != PARTIAL_SCHEMA
            or partial.get("classificationFileSha256") != classification_file_sha
            or partial.get("classificationCanonicalJsonSha256")
            != classification_canonical_sha
            or partial.get("capturePlanFileSha256") != plan_file_sha
            or partial.get("capturePlanCanonicalJsonSha256") != plan_canonical_sha
            or partial.get("probeImplementationFileSha256")
            != implementation_file_sha
        ):
            raise ValueError("engine-transient partial belongs to different inputs")
        completed = {
            _guid(key): value
            for key, value in partial.get("sourceVerifications", {}).items()
            if isinstance(value, dict)
        }

    bank_records: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(rows, 1):
        source_guid = _guid(row["sourceGuid"])
        target = _target(graph_root, summary_families, row)
        # These private fields are worker reconstruction context only.  They
        # never enter the derived release record or source semantics.
        target["_graphRoot"] = str(graph_root)
        target["_classificationPath"] = str(classification_path)
        derived = derive_manifest_one_shot_curves(target["graph"], row)
        derived_sha = _canonical_sha(derived)
        old = completed.get(source_guid)
        if old is not None and old.get("derivedSourceSha256") == derived_sha:
            certified = certify_manifest_engine_transient_source(derived, old)
            if certified["fidelity"]["exactnessClaim"] is not True:
                raise AssertionError("reused engine transient did not certify")
            print(f"[{index}/{len(rows)}] reuse engine transient {source_guid}", flush=True)
            continue
        installed = root / str(target["family"]["bankPath"])
        before = _sha256(installed)
        runtime_bank, isolation = _runtime_bank(root, target, derived, output_root)
        capture = _capture_pcm(root, runtime_bank, target, derived, output_root)
        pitch = _pitch_probe(root, runtime_bank, target, derived, output_root)
        zero_gain = _zero_gain_probe(
            root, runtime_bank, target, derived, output_root, capture, pitch
        )
        dynamic_dsp = _dynamic_dsp_probe(
            root, runtime_bank, target, derived, pitch, output_root
        )
        payload = {
            "schema": ENGINE_TRANSIENT_VERIFICATION_SCHEMA,
            "familyId": target["familyId"],
            "sourceGuid": source_guid,
            "eventPath": derived["eventPath"],
            "programPlacementRootInstrumentGuid": derived[
                "programPlacementRootInstrumentGuid"
            ],
            "derivedSourceSha256": derived_sha,
            "renderer": {
                "runtime": "FMOD Studio API 1.08.12",
                "sampleRateHz": SAMPLE_RATE_HZ,
                "channels": CHANNELS,
                "sampleFormat": "signedPcm16LittleEndian",
                "audioDeviceOpened": False,
                "targetOnly": True,
            },
            "runtimeIdentity": {
                "usedOnlyForChannelAndCallbackJoin": True,
                "sha256": target["runtimeIdentitySha256"],
                "sampleNameEmitted": False,
            },
            "isolation": isolation,
            "capture": capture,
            "pitchVerification": pitch,
            "zeroGainVirtualization": zero_gain,
            "dynamicDspVerification": dynamic_dsp,
            "voicePolicy": {
                "sourceBoundChannelObserved": True,
                "softwareChannelPriority": capture["softwareChannelPriority"],
            },
        }
        payload["verificationPayloadSha256"] = _canonical_sha(payload)
        # Persist a diagnostic before the fail-closed certifier rejects an
        # actual source-routed DSP.  It is not part of the immutable proof.
        if dynamic_dsp.get("accepted") is not True:
            diagnostic = output_root / "blocked" / f"{source_guid}.json"
            _write_canonical(diagnostic, payload)
            raise AssertionError(
                f"source-routed DSP automation needs windowed PCM variants: "
                f"{source_guid}; diagnostic={diagnostic}"
            )
        certified = certify_manifest_engine_transient_source(derived, payload)
        if certified["fidelity"]["exactnessClaim"] is not True:
            raise AssertionError(f"engine transient did not certify: {source_guid}")
        completed[source_guid] = payload
        after = _sha256(installed)
        if before != after:
            raise AssertionError(f"installed bank changed: {installed}")
        bank_records[target["familyId"]] = {
            "familyId": target["familyId"],
            "sha256Before": before,
            "sha256After": after,
            "unchanged": True,
        }
        _write_canonical(
            partial_path,
            {
                "schema": PARTIAL_SCHEMA,
                "classificationFileSha256": classification_file_sha,
                "classificationCanonicalJsonSha256": classification_canonical_sha,
                "capturePlanFileSha256": plan_file_sha,
                "capturePlanCanonicalJsonSha256": plan_canonical_sha,
                "probeImplementationFileSha256": implementation_file_sha,
                "sourceVerifications": dict(sorted(completed.items())),
            },
        )
        print(
            f"[{index}/{len(rows)}] certified engine transient {source_guid} "
            f"pcm={capture['audibilityDisposition']} pitch={pitch['mode']} "
            f"dsp={dynamic_dsp['runtimeTreatment']}",
            flush=True,
        )

    selected_ids = {_guid(row["sourceGuid"]) for row in rows}
    verifications = [completed[source] for source in sorted(selected_ids)]
    if len(verifications) != len(rows):
        raise AssertionError("engine-transient verification coverage is incomplete")
    full_release_scope = not requested and limit is None
    family_count = len({item["familyId"] for item in verifications})
    if full_release_scope and (len(verifications) != 60 or family_count != 24):
        raise AssertionError("release engine-transient scope changed from 60/24")
    pitch_counts = Counter(
        item["pitchVerification"]["mode"] for item in verifications
    )
    disposition_counts = Counter(
        item["capture"]["audibilityDisposition"] for item in verifications
    )
    timeline_count = sum(
        bool(
            derive_manifest_one_shot_curves(
                _target(
                    graph_root,
                    summary_families,
                    next(
                        row
                        for row in rows
                        if _guid(row["sourceGuid"]) == _guid(item["sourceGuid"])
                    ),
                )["graph"],
                next(
                    row
                    for row in rows
                    if _guid(row["sourceGuid"]) == _guid(item["sourceGuid"])
                ),
            )["timelineAutomation"]
        )
        for item in verifications
    )
    complete_banks: list[dict[str, Any]] = []
    for family_id in sorted({item["familyId"] for item in verifications}):
        installed = root / str(summary_families[family_id]["bankPath"])
        current = _sha256(installed)
        observed = {
            item["isolation"]["sourceBankSha256"]
            for item in verifications
            if item["familyId"] == family_id
        }
        if observed != {current}:
            raise AssertionError(f"installed bank identity changed: {installed}")
        complete_banks.append(
            {
                "familyId": family_id,
                "sha256Before": current,
                "sha256After": current,
                "unchanged": True,
            }
        )
    return {
        "schema": SCHEMA,
        "basis": {
            "runtime": "FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "sampleNamesUsedForSemantics": False,
            "sampleNamesUsedOnlyForRuntimeIdentityJoin": True,
            "installedBanksModified": False,
            "processIsolation": (
                "every render, pitch, and zero-gain operation ran in one fresh "
                "hashed Python worker process"
            ),
            "scheduleAlignment": (
                "target schedule frame equals the master WAV-writer DSP clock "
                "on the discovery update; event-local clocks are evidence only"
            ),
        },
        "inputs": {
            "classificationFileSha256": classification_file_sha,
            "classificationCanonicalJsonSha256": classification_canonical_sha,
            "capturePlanFileSha256": plan_file_sha,
            "capturePlanCanonicalJsonSha256": plan_canonical_sha,
            "probeImplementationFileSha256": implementation_file_sha,
        },
        "counts": {
            "sources": len(verifications),
            "families": family_count,
            "timelineAutomatedSources": timeline_count,
            "pitchModes": dict(sorted(pitch_counts.items())),
            "pcmDispositions": dict(sorted(disposition_counts.items())),
            "softwareChannelPriorities": dict(
                sorted(
                    Counter(
                        str(item["voicePolicy"]["softwareChannelPriority"])
                        for item in verifications
                    ).items()
                )
            ),
        },
        "sourceVerifications": verifications,
        "sourceBanks": complete_banks,
        "result": (
            "PASS_SOURCE_BOUND_COMPLETE_RELEASE_SCOPE"
            if full_release_scope
            else "PASS_SELECTED_SOURCES"
        ),
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument("--classification", type=Path, default=DEFAULT_CLASSIFICATION)
    parser.add_argument("--capture-plan", type=Path, default=DEFAULT_PLAN)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--source-guid", action="append", default=[])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--worker-request", type=Path)
    parser.add_argument("--worker-result", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if (args.worker_request is None) != (args.worker_result is None):
        raise ValueError("worker request and result must be supplied together")
    if args.worker_request is not None:
        _run_worker(args.worker_request, args.worker_result)
        return 0
    report = probe_catalog(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        args.capture_plan,
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
                "proofSha256": _sha256(proof),
                "counts": report["counts"],
                "audioDeviceOpened": False,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
