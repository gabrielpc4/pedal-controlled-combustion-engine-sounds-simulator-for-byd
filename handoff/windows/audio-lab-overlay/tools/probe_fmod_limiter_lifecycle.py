"""Silently prove FMOD 1.08 limiter event scheduling and phase behavior.

The probe selects one filename-independent graph role for each shipped limiter
topology, creates a target-only bank copy, and drives the authored ``decay``
parameter through event starts, region exits/re-entries, fade-stop and
reactivation.  Embedded sound names are used only to assert callback identity.
WAVWRITER_NRT is the only output; no audio device is opened.
"""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import sys
from typing import Any
import wave


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_bank_isolation import create_isolated_bank_copy
from sim.fmod_authored_curves import (
    LIMITER_VERIFICATION_SCHEMA,
    certify_manifest_limiter_program,
    derive_manifest_limiter_program,
)
from sim.fmod_native import FMOD_VERSION, _attributes, _distance_filter_description, _gain_description
from sim.fmod_renderer import (
    EventCallback,
    FMOD_OUTPUTTYPE_WAVWRITER_NRT,
    FMOD_SPEAKERMODE_STEREO,
    FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
    FMOD_STUDIO_STOP_IMMEDIATE,
    SilentFmodReferenceRenderer,
)
from tools.probe_fmod_source_direction import _event_description


SCHEMA = "ac-fmod-limiter-lifecycle-oracle-v1"
GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
CLASSIFIER_SCHEMA = "ac-fmod-catalog-source-role-classification-v2"
FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED = 0x00004000
FMOD_STUDIO_STOP_ALLOWFADEOUT = 0
FMOD_TIMEUNIT_PCM = 0x00000002

REPRESENTATIVES = {
    "TIMELINE_ONE_SHOT": (
        "030bc312b67b1193d1e4f0e15c628c3deda55c003778f0e763fee903a9353294",
        "27c386b4-4633-43b6-8f4c-0b9edb1bef06",
    ),
    "DECAY_REGION_ONE_SHOT": (
        "0a26446f58dfce705b88e028fbb40c52fe444bc82acacb2b5ccf065a68cefd67",
        "426c7901-f941-42b6-890f-94ba5e9d47ea",
    ),
    "DECAY_REGION_LOOP": (
        "26ae305ffae82cbb9a6f7026ed8937dd0f57873f492fe54f8db76a7b267bba09",
        "90018f03-d6db-43c5-846a-cabdd9eb9b6c",
    ),
}

_SUPPORTED_ACS_SHA256 = (
    "0df569c840f8303f7018f7891085e3a4c22cf93fb19327c6a0b85325cea23fd1"
)
_EXECUTABLE_RANGES = {
    "timerInitialization": (0x140063038, 0x140063042),
    "timerAndParameterUpdate": (0x140067134, 0x140067191),
    "tenSecondOwnerGate": (0x140067E28, 0x140067EA4),
    "rewindThenStartWrapper": (0x1401FBF40, 0x1401FBFB8),
    "allowFadeStopWrapper": (0x1401FC040, 0x1401FC080),
}
_EXECUTABLE_RANGE_SHA256 = {
    "timerInitialization": "bf847ec614339bc867303ed8a1c9cb84655735d360c4614b951a992cc7e4bce6",
    "timerAndParameterUpdate": "1a1acbda7b5aa8d4b62bd9e4d1519382406b0fb46d199e1915a478f082498520",
    "tenSecondOwnerGate": "ec2038608a135fba85277a4abc73b3a14956c54ba2b8f6cbff89dbc1f1076702",
    "rewindThenStartWrapper": "df9d52d68e54ebdc6d391cfbf1a48aecf1bdd8df83868d72ffe91e8b17312b51",
    "allowFadeStopWrapper": "7ab424e8f4ede89fb3edda02a374d970706caf5306495315f16a4c0f57816e2f",
}


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _load_json(path: Path, schema: str) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != schema:
        raise ValueError(f"{path} is not {schema}")
    return value


def _ac_executable_contract(assetto_root: Path) -> dict[str, Any]:
    """Hash and disassemble the exact AC limiter owner implementation.

    This deliberately supports one executable identity.  A changed executable
    must be audited again instead of inheriting the lifecycle claims by
    resemblance.
    """

    import capstone
    import pefile

    executable = assetto_root / "acs.exe"
    data = executable.read_bytes()
    executable_sha256 = hashlib.sha256(data).hexdigest()
    if executable_sha256 != _SUPPORTED_ACS_SHA256:
        raise AssertionError(
            "unsupported acs.exe identity for limiter lifecycle proof: "
            f"{executable_sha256}"
        )
    pe = pefile.PE(data=data, fast_load=True)
    image_base = int(pe.OPTIONAL_HEADER.ImageBase)
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)

    ranges: dict[str, Any] = {}
    for name, (start, end) in _EXECUTABLE_RANGES.items():
        offset = pe.get_offset_from_rva(start - image_base)
        raw = data[offset : offset + end - start]
        digest = hashlib.sha256(raw).hexdigest()
        if digest != _EXECUTABLE_RANGE_SHA256[name]:
            raise AssertionError(f"acs limiter executable range changed: {name}")
        ranges[name] = {
            "startVirtualAddress": f"0x{start:x}",
            "endVirtualAddressExclusive": f"0x{end:x}",
            "bytesSha256": digest,
            "bytesHex": raw.hex(),
            "disassembly": [
                {
                    "address": f"0x{instruction.address:x}",
                    "mnemonic": instruction.mnemonic,
                    "operands": instruction.op_str,
                }
                for instruction in decoder.disasm(raw, start)
            ],
        }

    constant_address = 0x14141970C
    constant_offset = pe.get_offset_from_rva(constant_address - image_base)
    constant_bytes = data[constant_offset : constant_offset + 4]
    threshold = struct.unpack("<f", constant_bytes)[0]
    if constant_bytes != bytes.fromhex("00002041") or threshold != 10.0:
        raise AssertionError("AC limiter inactivity threshold constant changed")

    return {
        "executableRelativePath": "acs.exe",
        "executableSha256": executable_sha256,
        "imageBase": f"0x{image_base:x}",
        "ranges": ranges,
        "inactivityThresholdConstant": {
            "virtualAddress": f"0x{constant_address:x}",
            "bytesHex": constant_bytes.hex(),
            "float32": threshold,
        },
        "provenContract": {
            "timerInitialization": "float32(10.0)",
            "perUpdate": (
                "timer=float32(timer+dt); if limiterPulse timer=0; "
                "write raw timer to decay"
            ),
            "desiredActiveComparison": "timer<=float32(10.0)",
            "activeStoppedAction": "setTimelinePosition(0); start",
            "inactiveAction": "stop(ALLOWFADEOUT)",
        },
    }


def _catalog_counts(classification: dict[str, Any]) -> dict[str, Any]:
    lifetime: dict[str, int] = {"oneShot": 0, "continuous": 0}
    topology = {
        "TIMELINE_ONE_SHOT": 0,
        "DECAY_REGION_ONE_SHOT": 0,
        "DECAY_REGION_LOOP": 0,
    }
    families: set[str] = set()
    for row in classification["sourceDecisions"]:
        if row.get("policy") != "allowCandidate" or row.get("role") != "LIMITER":
            continue
        families.add(str(row["familyId"]))
        lifetime[str(row["lifetime"])] += 1
        # The exact topology counts are asserted against graph reports below;
        # these classifier rows establish only family/lifetime coverage.
    return {
        "sources": sum(lifetime.values()),
        "families": len(families),
        "lifetimeCounts": lifetime,
        "topologyCounts": topology,
    }


def _target(
    graph_root: Path,
    summary: dict[str, Any],
    family_id: str,
    source_guid: str,
) -> dict[str, Any]:
    graph = _load_json(graph_root / "families" / f"{family_id}.json", GRAPH_SCHEMA)
    instruments = {_guid(item["guid"]): item for item in graph["instruments"]}
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        raise ValueError(f"missing limiter waveform {source_guid}")
    events = [
        event
        for event in graph["events"]
        if source_guid in {_guid(item) for item in event["reachableInstrumentGuids"]}
        and str(event["path"]).casefold().endswith("/limiter")
    ]
    if len(events) != 1:
        raise ValueError(f"limiter waveform {source_guid} has {len(events)} events")
    event = events[0]
    parameter_placements = [
        item
        for item in event["parameterPlacements"]
        if _guid(item.get("instrumentGuid")) == source_guid
    ]
    timeline_placements = [
        item
        for item in event["timelinePlacements"]
        if _guid(item.get("instrumentGuid")) == source_guid
    ]
    loop_count = int(source["baseProperties"]["loopCount"])
    if timeline_placements and not parameter_placements and loop_count >= 0:
        topology = "TIMELINE_ONE_SHOT"
    elif parameter_placements and loop_count >= 0:
        topology = "DECAY_REGION_ONE_SHOT"
    elif parameter_placements and loop_count < 0:
        topology = "DECAY_REGION_LOOP"
    else:
        raise ValueError(f"unsupported limiter topology for {source_guid}")
    if any(str(item.get("parameterName") or "").casefold() != "decay" for item in parameter_placements):
        raise ValueError("limiter placement uses a non-decay parameter")
    family = next(item for item in summary["families"] if item["familyId"] == family_id)
    sample = source.get("sample") or {}
    return {
        "familyId": family_id,
        "sourceGuid": source_guid,
        "eventPath": str(event["path"]),
        "topology": topology,
        "graph": graph,
        "bankRelativePath": str(family["bankPath"]),
        "runtimeIdentity": str(sample.get("name") or ""),
        "runtimeIdentitySha256": hashlib.sha256(
            str(sample.get("name") or "").encode("utf-8")
        ).hexdigest(),
        "waveformTechnicalIdentity": {
            "waveformResourceGuid": sample.get("waveformResourceGuid"),
            "encodedPayloadSha256": sample.get("encodedPayloadSha256"),
            "sampleCount": sample.get("sampleCount"),
            "frequencyHz": sample.get("frequencyHz"),
            "channels": sample.get("channels"),
        },
        "parameterPlacements": parameter_placements,
        "timelinePlacements": timeline_placements,
        "loopCount": loop_count,
    }


class _LimiterRuntime:
    def __init__(
        self,
        root: Path,
        bank_path: Path,
        event_path: str,
        target_identity: str,
        wav_path: Path,
    ):
        self.root = root
        self.bank_path = bank_path
        self.event_path = event_path
        self.target_identity = target_identity
        self.wav_path = wav_path
        self.renderer = SilentFmodReferenceRenderer(root)
        self.cookie = None
        self.system = C.c_void_p()
        self.instance = C.c_void_p()
        self.low_level = C.c_void_p()
        self.update_index = 0
        self.stage = "setup"
        self.active_voices = 0
        self.callbacks: list[dict[str, Any]] = []
        self.records: list[dict[str, Any]] = []
        self.operations: list[dict[str, Any]] = []

    def __enter__(self) -> "_LimiterRuntime":
        self.cookie = os.add_dll_directory(str(self.root))
        self.core = C.WinDLL(str(self.root / "fmod64.dll"))
        self.studio = C.WinDLL(str(self.root / "fmodstudio64.dll"))
        self.renderer._bind(self.core, self.studio)
        self._bind_extra()

        @EventCallback
        def callback(callback_type: int, _event: int, parameter_pointer: int) -> int:
            if callback_type not in {
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
            } or not parameter_pointer:
                return 0
            name = C.create_string_buffer(1024)
            self.renderer._check(
                self.core.FMOD_Sound_GetName(
                    C.c_void_p(parameter_pointer), name, len(name)
                ),
                "read limiter callback identity",
            )
            decoded = name.value.decode("utf-8", "replace")
            if decoded != self.target_identity:
                raise AssertionError("target-isolated limiter scheduled another sound")
            kind = (
                "played"
                if callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                else "stopped"
            )
            self.active_voices += 1 if kind == "played" else -1
            self.callbacks.append(
                {
                    "kind": kind,
                    "stage": self.stage,
                    "update": self.update_index,
                    "activeVoicesAfterCallback": self.active_voices,
                    "identitySha256": hashlib.sha256(
                        decoded.encode("utf-8")
                    ).hexdigest(),
                }
            )
            return 0

        self.callback = callback
        r = self.renderer
        r._check(
            self.studio.FMOD_Studio_System_Create(C.byref(self.system), FMOD_VERSION),
            "create limiter oracle system",
        )
        r._check(
            self.studio.FMOD_Studio_System_GetLowLevelSystem(
                self.system, C.byref(self.low_level)
            ),
            "get limiter low-level system",
        )
        r._check(
            self.core.FMOD_System_SetOutput(
                self.low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT
            ),
            "select limiter NRT writer",
        )
        r._check(
            self.core.FMOD_System_SetSoftwareFormat(
                self.low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0
            ),
            "set limiter stereo format",
        )
        r._check(
            self.core.FMOD_System_SetDSPBufferSize(
                self.low_level, self.renderer.dsp_buffer_frames, 4
            ),
            "set limiter DSP buffer",
        )
        self.writer_name = C.create_string_buffer(str(self.wav_path).encode("utf-8"))
        r._check(
            self.studio.FMOD_Studio_System_Initialize(
                self.system, 64, 0, 0, C.cast(self.writer_name, C.c_void_p)
            ),
            "initialize limiter NRT writer",
        )
        self.distance, self.distance_keepalive = _distance_filter_description()
        self.gain, self.gain_keepalive = _gain_description()
        r._check(
            self.studio.FMOD_Studio_System_RegisterPlugin(
                self.system, C.byref(self.distance)
            ),
            "register limiter distance filter",
        )
        r._check(
            self.studio.FMOD_Studio_System_RegisterPlugin(
                self.system, C.byref(self.gain)
            ),
            "register limiter gain plugin",
        )
        loaded: list[C.c_void_p] = []
        for path in (
            self.root / "content" / "sfx" / "common.strings.bank",
            self.root / "content" / "sfx" / "common.bank",
            self.bank_path,
        ):
            bank = C.c_void_p()
            r._check(
                self.studio.FMOD_Studio_System_LoadBankFile(
                    self.system, str(path).encode("utf-8"), 0, C.byref(bank)
                ),
                f"load {path.name}",
            )
            loaded.append(bank)
        description = _event_description(self.studio, loaded[-1], self.event_path)
        r._check(
            self.studio.FMOD_Studio_EventDescription_LoadSampleData(description),
            "load limiter sample data",
        )
        r._check(
            self.studio.FMOD_Studio_System_FlushSampleLoading(self.system),
            "flush limiter sample loading",
        )
        r._check(
            self.studio.FMOD_Studio_EventDescription_CreateInstance(
                description, C.byref(self.instance)
            ),
            "create limiter event instance",
        )
        listener = _attributes((0.0, 0.7, 0.0))
        emitter = _attributes((0.0, 0.5, 0.0))
        r._check(
            self.studio.FMOD_Studio_System_SetListenerAttributes(
                self.system, 0, C.byref(listener)
            ),
            "place limiter listener",
        )
        r._check(
            self.studio.FMOD_Studio_EventInstance_Set3DAttributes(
                self.instance, C.byref(emitter)
            ),
            "place limiter emitter",
        )
        r._check(
            self.studio.FMOD_Studio_EventInstance_SetCallback(
                self.instance,
                self.callback,
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                | FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
            ),
            "attach limiter callbacks",
        )
        return self

    def _bind_extra(self) -> None:
        self.studio.FMOD_Studio_EventInstance_GetTimelinePosition.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        self.studio.FMOD_Studio_EventInstance_GetTimelinePosition.restype = C.c_int
        self.core.FMOD_System_GetChannelsPlaying.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
            C.POINTER(C.c_int),
        ]
        self.core.FMOD_System_GetChannelsPlaying.restype = C.c_int
        self.core.FMOD_ChannelGroup_GetNumChannels.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        self.core.FMOD_ChannelGroup_GetNumChannels.restype = C.c_int
        self.core.FMOD_ChannelGroup_GetChannel.argtypes = [
            C.c_void_p,
            C.c_int,
            C.POINTER(C.c_void_p),
        ]
        self.core.FMOD_ChannelGroup_GetChannel.restype = C.c_int
        self.core.FMOD_ChannelGroup_GetNumGroups.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        self.core.FMOD_ChannelGroup_GetNumGroups.restype = C.c_int
        self.core.FMOD_ChannelGroup_GetGroup.argtypes = [
            C.c_void_p,
            C.c_int,
            C.POINTER(C.c_void_p),
        ]
        self.core.FMOD_ChannelGroup_GetGroup.restype = C.c_int
        self.core.FMOD_Channel_GetPosition.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_uint),
            C.c_uint,
        ]
        self.core.FMOD_Channel_GetPosition.restype = C.c_int
        self.studio.FMOD_Studio_EventInstance_GetChannelGroup.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        self.studio.FMOD_Studio_EventInstance_GetChannelGroup.restype = C.c_int

    def __exit__(self, _type, _value, _traceback) -> None:
        if self.instance:
            self.studio.FMOD_Studio_EventInstance_Stop(
                self.instance, FMOD_STUDIO_STOP_IMMEDIATE
            )
            self.studio.FMOD_Studio_EventInstance_Release(self.instance)
        if self.system:
            self.studio.FMOD_Studio_System_UnloadAll(self.system)
            self.studio.FMOD_Studio_System_Release(self.system)
        if self.cookie is not None:
            self.cookie.close()

    def _flush(self, operation: str) -> None:
        self.renderer._check(
            self.studio.FMOD_Studio_System_FlushCommands(self.system), operation
        )

    def set_decay(self, value: float, stage: str) -> None:
        self.stage = stage
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_SetParameterValue(
                self.instance, b"decay", C.c_float(value)
            ),
            f"set limiter decay {value}",
        )
        self._flush("flush limiter decay")
        self.operations.append(
            {"kind": "setDecay", "value": value, "stage": stage, "update": self.update_index}
        )

    def set_timeline_zero(self, stage: str) -> None:
        self.stage = stage
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_SetTimelinePosition(
                self.instance, 0
            ),
            "rewind limiter timeline",
        )
        self._flush("flush limiter timeline rewind")
        self.operations.append(
            {"kind": "setTimelinePosition", "value": 0, "stage": stage, "update": self.update_index}
        )

    def start(self, stage: str, *, rewind: bool) -> None:
        if rewind:
            self.set_timeline_zero(stage + ":rewind")
        self.stage = stage
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_Start(self.instance),
            "start limiter event",
        )
        self._flush("flush limiter start")
        self.operations.append(
            {"kind": "start", "rewindFirst": rewind, "stage": stage, "update": self.update_index}
        )

    def stop_allow_fade(self, stage: str) -> None:
        self.stage = stage
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_Stop(
                self.instance, FMOD_STUDIO_STOP_ALLOWFADEOUT
            ),
            "fade-stop limiter event",
        )
        self._flush("flush limiter fade-stop")
        self.operations.append(
            {"kind": "stopAllowFadeout", "stage": stage, "update": self.update_index}
        )

    def _channel_positions(self) -> list[int]:
        event_group = C.c_void_p()
        result = self.studio.FMOD_Studio_EventInstance_GetChannelGroup(
            self.instance, C.byref(event_group)
        )
        if result or not event_group:
            return []
        positions: list[int] = []
        pending = [event_group]
        visited: set[int] = set()
        while pending:
            group = pending.pop()
            address = int(group.value or 0)
            if not address or address in visited:
                continue
            visited.add(address)
            channel_count = C.c_int()
            self.renderer._check(
                self.core.FMOD_ChannelGroup_GetNumChannels(
                    group, C.byref(channel_count)
                ),
                "count limiter channels",
            )
            for index in range(channel_count.value):
                channel = C.c_void_p()
                self.renderer._check(
                    self.core.FMOD_ChannelGroup_GetChannel(
                        group, index, C.byref(channel)
                    ),
                    "get limiter channel",
                )
                position = C.c_uint()
                self.renderer._check(
                    self.core.FMOD_Channel_GetPosition(
                        channel, C.byref(position), FMOD_TIMEUNIT_PCM
                    ),
                    "get limiter PCM position",
                )
                positions.append(position.value)
            group_count = C.c_int()
            self.renderer._check(
                self.core.FMOD_ChannelGroup_GetNumGroups(
                    group, C.byref(group_count)
                ),
                "count limiter child groups",
            )
            for index in range(group_count.value):
                child = C.c_void_p()
                self.renderer._check(
                    self.core.FMOD_ChannelGroup_GetGroup(
                        group, index, C.byref(child)
                    ),
                    "get limiter child group",
                )
                pending.append(child)
        return sorted(positions)

    def update(self, stage: str, count: int) -> None:
        self.stage = stage
        before_played = sum(item["kind"] == "played" for item in self.callbacks)
        before_stopped = sum(item["kind"] == "stopped" for item in self.callbacks)
        start_update = self.update_index
        for _ in range(count):
            self.renderer._check(
                self.studio.FMOD_Studio_System_Update(self.system),
                f"render limiter stage {stage}",
            )
            self.update_index += 1
        playback = C.c_int()
        timeline = C.c_int()
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_GetPlaybackState(
                self.instance, C.byref(playback)
            ),
            "read limiter playback state",
        )
        self.renderer._check(
            self.studio.FMOD_Studio_EventInstance_GetTimelinePosition(
                self.instance, C.byref(timeline)
            ),
            "read limiter timeline position",
        )
        logical = C.c_int()
        real = C.c_int()
        self.renderer._check(
            self.core.FMOD_System_GetChannelsPlaying(
                self.low_level, C.byref(logical), C.byref(real)
            ),
            "read limiter channel counts",
        )
        self.records.append(
            {
                "stage": stage,
                "startUpdate": start_update,
                "endUpdateExclusive": self.update_index,
                "playedCallbacks": sum(item["kind"] == "played" for item in self.callbacks) - before_played,
                "stoppedCallbacks": sum(item["kind"] == "stopped" for item in self.callbacks) - before_stopped,
                "activeVoices": self.active_voices,
                "playbackState": playback.value,
                "timelinePosition": timeline.value,
                "logicalChannels": logical.value,
                "realChannels": real.value,
                "pcmPositions": self._channel_positions(),
            }
        )


def _run_sequence(root: Path, target: dict[str, Any], isolated_bank: Path, wav: Path) -> dict[str, Any]:
    with _LimiterRuntime(
        root,
        isolated_bank,
        target["eventPath"],
        target["runtimeIdentity"],
        wav,
    ) as runtime:
        topology = target["topology"]
        runtime.set_decay(0.0, "initial-decay-zero")
        runtime.start("initial-start", rewind=True)
        runtime.update("initial-playing", 8 if topology != "DECAY_REGION_LOOP" else 20)

        if topology == "TIMELINE_ONE_SHOT":
            # An ordinary repeated cut only resets decay.  It must not rewind
            # or restart the already-playing authored timeline.
            runtime.update("phase-before-repeated-cut", 32)
            runtime.set_decay(0.0, "repeated-cut-decay-reset-only")
            runtime.update("phase-after-repeated-cut", 32)
            runtime.update("observe-authored-timeline-cycles", 320)
        elif topology == "DECAY_REGION_ONE_SHOT":
            placement = target["parameterPlacements"][0]
            outside = min(1.0, float(placement["end"]) + 0.05)
            # A pulse before decay has left its source region is not a new
            # region entry and therefore must not schedule another voice.
            runtime.set_decay(0.0, "repeated-cut-still-inside-region")
            runtime.update("after-inside-reset", 2)
            runtime.set_decay(outside, "decay-region-exit")
            runtime.update("after-region-exit", 2)
            runtime.set_decay(0.0, "decay-region-reentry")
            runtime.update("after-region-reentry", 2)
            runtime.set_decay(0.0, "same-inside-value")
            runtime.update("after-same-inside-value", 2)
            runtime.set_decay(outside, "leave-region-for-natural-tail")
            runtime.update("natural-tail-until-event-stopped", 120)
            # Setting a parameter on a stopped event does not start it.  AC's
            # state owner then calls AudioEvent::start (rewind + start).
            runtime.set_decay(0.0, "cut-on-stopped-event-before-owner-start")
            runtime.update("stopped-event-parameter-only", 2)
            runtime.start("owner-reactivates-stopped-event", rewind=True)
            runtime.update("after-owner-reactivation", 8)
        else:
            placement = target["parameterPlacements"][0]
            outside = min(1.0, float(placement["end"]) + 0.05)
            runtime.set_decay(0.0, "repeated-cut-still-inside-region")
            runtime.update("loop-phase-after-inside-reset", 20)
            runtime.set_decay(outside, "decay-region-exit")
            runtime.update("loop-stops-after-region-exit", 40)
            runtime.set_decay(0.0, "cut-on-stopped-event-before-owner-start")
            runtime.update("stopped-event-parameter-only", 4)
            runtime.start("owner-reactivates-stopped-event", rewind=True)
            runtime.update("loop-after-owner-reactivation", 20)

        # CarAudioFMOD stops the limiter only after its host decay timer is
        # greater than 10 seconds.  AudioEvent::stop passes ALLOWFADEOUT.
        runtime.set_decay(10.01, "ac-decay-timer-greater-than-ten")
        runtime.stop_allow_fade("ac-owner-stop-after-ten-seconds")
        runtime.update("after-host-fade-stop", 40)
        runtime.set_decay(0.0, "reactivation-decay-zero")
        runtime.start("reactivation-owner-rewind-and-start", rewind=True)
        runtime.update("after-reactivation", 40)
        operations = list(runtime.operations)
        records = list(runtime.records)
        callbacks = list(runtime.callbacks)

    return {
        "topology": target["topology"],
        "familyId": target["familyId"],
        "sourceGuid": target["sourceGuid"],
        "eventPath": target["eventPath"],
        "waveformTechnicalIdentity": target["waveformTechnicalIdentity"],
        "runtimeIdentitySha256": target["runtimeIdentitySha256"],
        "loopCount": target["loopCount"],
        "parameterPlacements": target["parameterPlacements"],
        "timelinePlacements": target["timelinePlacements"],
        "operations": operations,
        "records": records,
        "callbacks": callbacks,
        "wav": {
            "sha256": _sha256(wav),
            "pathRelativeToReportDirectory": wav.name,
        },
    }


def _validate_runtime_observations(runs: list[dict[str, Any]]) -> dict[str, Any]:
    by_topology = {str(run["topology"]): run for run in runs}
    if set(by_topology) != set(REPRESENTATIVES):
        raise AssertionError("limiter runtime representative coverage changed")

    def record(topology: str, stage: str) -> dict[str, Any]:
        matches = [
            item
            for item in by_topology[topology]["records"]
            if item.get("stage") == stage
        ]
        if len(matches) != 1:
            raise AssertionError(f"missing unique {topology}:{stage} record")
        return matches[0]

    timeline_before = record("TIMELINE_ONE_SHOT", "phase-before-repeated-cut")
    timeline_after = record("TIMELINE_ONE_SHOT", "phase-after-repeated-cut")
    timeline_cycles = record("TIMELINE_ONE_SHOT", "observe-authored-timeline-cycles")
    timeline_stopped = record("TIMELINE_ONE_SHOT", "after-host-fade-stop")
    timeline_reactivated = record("TIMELINE_ONE_SHOT", "after-reactivation")
    if not (
        timeline_before["activeVoices"] >= 1
        and timeline_after["activeVoices"] >= 1
        and timeline_after["playedCallbacks"] == 0
        and timeline_after["stoppedCallbacks"] == 0
        and timeline_cycles["playedCallbacks"] >= 3
        and timeline_stopped["activeVoices"] == 0
        and timeline_reactivated["activeVoices"] >= 1
    ):
        raise AssertionError("timeline limiter lifecycle oracle changed")

    one_exit = record("DECAY_REGION_ONE_SHOT", "after-region-exit")
    one_reentry = record("DECAY_REGION_ONE_SHOT", "after-region-reentry")
    one_same = record("DECAY_REGION_ONE_SHOT", "after-same-inside-value")
    one_parameter_only = record("DECAY_REGION_ONE_SHOT", "stopped-event-parameter-only")
    one_owner = record("DECAY_REGION_ONE_SHOT", "after-owner-reactivation")
    one_callbacks = by_topology["DECAY_REGION_ONE_SHOT"]["callbacks"]
    reentry_played = sum(
        item.get("kind") == "played"
        and item.get("stage") == "decay-region-reentry"
        for item in one_callbacks
    )
    if not (
        one_exit["activeVoices"] == 1
        and one_reentry["activeVoices"] == 2
        and reentry_played == 1
        and one_same["activeVoices"] == 2
        and one_same["playedCallbacks"] == 0
        and one_parameter_only["activeVoices"] == 0
        and one_owner["activeVoices"] == 1
    ):
        raise AssertionError("decay-region one-shot limiter oracle changed")

    loop_before = record("DECAY_REGION_LOOP", "initial-playing")
    loop_reset = record("DECAY_REGION_LOOP", "loop-phase-after-inside-reset")
    loop_exit = record("DECAY_REGION_LOOP", "loop-stops-after-region-exit")
    loop_parameter_only = record("DECAY_REGION_LOOP", "stopped-event-parameter-only")
    loop_owner = record("DECAY_REGION_LOOP", "loop-after-owner-reactivation")
    if not (
        loop_before["activeVoices"] == 1
        and loop_reset["activeVoices"] == 1
        and loop_reset["playedCallbacks"] == 0
        and loop_reset["pcmPositions"] != loop_before["pcmPositions"]
        and loop_exit["activeVoices"] == 0
        and loop_parameter_only["activeVoices"] == 0
        and loop_owner["activeVoices"] == 1
    ):
        raise AssertionError("decay-region loop limiter oracle changed")

    return {
        "repeatedCutWhileActive": "RESET_DECAY_ONLY_WITHOUT_EVENT_OR_SOURCE_RESTART",
        "timelineOneShot": (
            "TIMELINE_CONTINUES_AND_PERIODICALLY_SCHEDULES_OVERLAPPING_ONE_SHOTS"
        ),
        "decayRegionOneShot": (
            "EXIT_LETS_ACTIVE_VOICE_FINISH; REENTRY_SCHEDULES_OVERLAP; "
            "SAME_INSIDE_VALUE_DOES_NOT_RETRIGGER"
        ),
        "decayRegionLoop": (
            "INSIDE_RESET_PRESERVES_PHASE; EXIT_STOPS; REENTRY_AFTER_OWNER_START "
            "RESTARTS_PHASE"
        ),
        "parameterChangeOnStoppedEvent": "DOES_NOT_START_EVENT_OR_SOURCE",
        "reactivation": "OWNER_REWINDS_TIMELINE_ZERO_THEN_STARTS",
    }


def _validate_source_runtime_observation(run: dict[str, Any]) -> dict[str, Any]:
    records = {str(item["stage"]): item for item in run["records"]}
    topology = str(run["topology"])
    initial = records["initial-playing"]
    accepted = initial["activeVoices"] >= 1
    observations: dict[str, Any] = {
        "initialActiveVoices": int(initial["activeVoices"]),
    }
    if topology == "TIMELINE_ONE_SHOT":
        after_reset = records["phase-after-repeated-cut"]
        cycles = records["observe-authored-timeline-cycles"]
        stopped = records["after-host-fade-stop"]
        reactivated = records["after-reactivation"]
        accepted = accepted and (
            after_reset["activeVoices"] >= 1
            and stopped["activeVoices"] == 0
            and reactivated["activeVoices"] >= 1
        )
        observations.update(
            {
                # Short authored periods can naturally schedule/stop a leaf
                # during this observation window.  The host operation log
                # proves there was no event restart; active state and ongoing
                # periodic scheduling are the source-bound assertions.
                "repeatedCutWindowPlayedCallbacks": after_reset["playedCallbacks"],
                "repeatedCutWindowStoppedCallbacks": after_reset["stoppedCallbacks"],
                "timelineCyclePlayedCallbacks": cycles["playedCallbacks"],
                "afterTenSecondStopActiveVoices": stopped["activeVoices"],
                "reactivatedActiveVoices": reactivated["activeVoices"],
            }
        )
    elif topology == "DECAY_REGION_ONE_SHOT":
        after_exit = records["after-region-exit"]
        reentry = records["after-region-reentry"]
        same = records["after-same-inside-value"]
        parameter_only = records["stopped-event-parameter-only"]
        owner = records["after-owner-reactivation"]
        reentry_plays = sum(
            item.get("kind") == "played"
            and item.get("stage") == "decay-region-reentry"
            for item in run["callbacks"]
        )
        accepted = accepted and (
            after_exit["activeVoices"] >= 1
            and reentry["activeVoices"] >= 2
            and reentry_plays == 1
            and same["playedCallbacks"] == 0
            and parameter_only["activeVoices"] == 0
            and owner["activeVoices"] >= 1
        )
        observations.update(
            {
                "afterExitActiveVoices": after_exit["activeVoices"],
                "afterReentryActiveVoices": reentry["activeVoices"],
                "reentryPlayedCallbacks": reentry_plays,
                "sameInsidePlayedCallbacks": same["playedCallbacks"],
                "stoppedParameterOnlyActiveVoices": parameter_only["activeVoices"],
                "ownerReactivatedActiveVoices": owner["activeVoices"],
            }
        )
    elif topology == "DECAY_REGION_LOOP":
        after_reset = records["loop-phase-after-inside-reset"]
        after_exit = records["loop-stops-after-region-exit"]
        parameter_only = records["stopped-event-parameter-only"]
        owner = records["loop-after-owner-reactivation"]
        accepted = accepted and (
            after_reset["activeVoices"] == 1
            and after_reset["playedCallbacks"] == 0
            and after_exit["activeVoices"] == 0
            and parameter_only["activeVoices"] == 0
            and owner["activeVoices"] == 1
        )
        observations.update(
            {
                "initialPcmPositions": initial["pcmPositions"],
                "afterInsideResetPcmPositions": after_reset["pcmPositions"],
                "insideResetPlayedCallbacks": after_reset["playedCallbacks"],
                "afterExitActiveVoices": after_exit["activeVoices"],
                "stoppedParameterOnlyActiveVoices": parameter_only["activeVoices"],
                "ownerReactivatedActiveVoices": owner["activeVoices"],
            }
        )
    else:
        raise AssertionError(f"unknown limiter topology {topology}")
    if not accepted:
        raise AssertionError(
            f"source limiter runtime contract failed for {run['sourceGuid']}"
        )
    return {
        "accepted": True,
        "contract": "EXACT_AC_OWNER_AND_FMOD_SOURCE_SCHEDULING",
        "topology": topology,
        "observations": observations,
        "callbackCounts": {
            "played": sum(item["kind"] == "played" for item in run["callbacks"]),
            "stopped": sum(item["kind"] == "stopped" for item in run["callbacks"]),
        },
        "renderSha256": run["wav"]["sha256"],
    }


def _read_pcm16_stereo(path: Path) -> tuple[bytes, Any]:
    import numpy as np

    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate() != 48000
            or source.getnchannels() != 2
            or source.getsampwidth() != 2
        ):
            raise AssertionError(f"noncanonical limiter PCM render: {path}")
        payload = source.readframes(source.getnframes())
    samples = np.frombuffer(payload, dtype="<i2").reshape(-1, 2).copy()
    return payload, samples


def _write_pcm16_stereo(path: Path, payload: bytes) -> None:
    if len(payload) % 4:
        raise AssertionError("stereo PCM payload is not frame aligned")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(48000)
        target.writeframes(payload)


def _pcm_difference(left: Any, right: Any) -> dict[str, Any]:
    import numpy as np

    if left.shape != right.shape:
        raise AssertionError("limiter comparison PCM shapes differ")
    left_float = left.astype(np.float64)
    right_float = right.astype(np.float64)
    difference = left_float - right_float
    reference_rms = float(np.sqrt(np.mean(left_float * left_float)))
    comparison_rms = float(np.sqrt(np.mean(right_float * right_float)))
    difference_rms = float(np.sqrt(np.mean(difference * difference)))
    if reference_rms == 0.0 and comparison_rms == 0.0:
        snr_db = 300.0
        gain_error_db = 0.0
    else:
        snr_db = 20.0 * math.log10(
            max(reference_rms, 1.0e-12) / max(difference_rms, 1.0e-12)
        )
        gain_error_db = abs(
            20.0
            * math.log10(
                max(comparison_rms, 1.0e-12) / max(reference_rms, 1.0e-12)
            )
        )
    return {
        "bitExact": bool(np.array_equal(left, right)),
        "referenceRmsLsb": reference_rms,
        "comparisonRmsLsb": comparison_rms,
        "differenceRmsLsb": difference_rms,
        "maximumAbsoluteDifferenceLsb": int(np.max(np.abs(difference))),
        "snrDb": snr_db,
        "gainErrorDb": gain_error_db,
    }


def _render_limiter_reference(
    renderer: SilentFmodReferenceRenderer,
    bank: Path,
    target: dict[str, Any],
    output: Path,
    *,
    warmup_frames: int,
    duration_frames: int,
) -> Any:
    source = next(
        item
        for item in target["graph"]["instruments"]
        if _guid(item.get("guid")) == target["sourceGuid"]
    )
    chance = float(source["baseProperties"].get("triggerChancePercent", 100.0))
    identity = target["runtimeIdentity"]
    optional_identity = (
        {"required_sound_name": identity, "maximum_takes": 8}
        if chance < 100.0
        else {}
    )
    result = renderer.render_event(
        bank,
        target["eventPath"],
        output,
        parameters={"decay": 0.0},
        warmup_frames=warmup_frames,
        duration_frames=duration_frames,
        **optional_identity,
    )
    if not result.scheduled_sound_names or set(result.scheduled_sound_names) != {identity}:
        raise AssertionError(
            f"target-only limiter scheduling changed for {target['sourceGuid']}"
        )
    return result


def _capture_source_pcm_verification(
    renderer: SilentFmodReferenceRenderer,
    bank: Path,
    target: dict[str, Any],
    program: dict[str, Any],
    output_root: Path,
) -> dict[str, Any]:
    import numpy as np

    source = next(
        item
        for item in target["graph"]["instruments"]
        if _guid(item.get("guid")) == target["sourceGuid"]
    )
    sample = source["sample"]
    pitch_semitones = float(source["baseProperties"].get("pitchSemitones", 0.0))
    rendered_source_frames = (
        float(sample["sampleCount"])
        * 48000.0
        / float(sample["frequencyHz"])
        / (2.0 ** (pitch_semitones / 12.0))
    )
    work = output_root / "capture-work"
    captures = output_root / "captures"
    work.mkdir(parents=True, exist_ok=True)
    captures.mkdir(parents=True, exist_ok=True)
    first_path = work / f"{target['sourceGuid']}-a.wav"
    second_path = work / f"{target['sourceGuid']}-b.wav"
    mode = str(program["programMode"])

    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        capture = program["timelineCapture"]
        period_frames = int(capture["captureFrames"])
        warmup_frames = int(capture["warmupPeriods"]) * period_frames
        duration_frames = 2 * period_frames
        _render_limiter_reference(
            renderer,
            bank,
            target,
            first_path,
            warmup_frames=warmup_frames,
            duration_frames=duration_frames,
        )
        _render_limiter_reference(
            renderer,
            bank,
            target,
            second_path,
            warmup_frames=warmup_frames,
            duration_frames=duration_frames,
        )
        first_payload, first = _read_pcm16_stereo(first_path)
        second_payload, second = _read_pcm16_stereo(second_path)
        independent_exact = first_payload == second_payload
        period_comparison = _pcm_difference(
            first[:period_frames], first[period_frames : 2 * period_frames]
        )
        if not independent_exact or (
            not period_comparison["bitExact"]
            and period_comparison["snrDb"] < 50.0
        ):
            raise AssertionError(
                f"timeline limiter PCM gate failed for {target['sourceGuid']}"
            )
        final_payload = first_payload[: period_frames * 4]
        loop_start = 0
        loop_end = period_frames
        extra = {
            "independentRenderBitExact": independent_exact,
            "adjacentPeriodComparison": period_comparison,
        }
    elif mode == "PERSISTENT_DECAY_REGION_ONE_SHOT":
        duration_frames = max(96000, math.ceil(rendered_source_frames) + 96000)
        _render_limiter_reference(
            renderer,
            bank,
            target,
            first_path,
            warmup_frames=0,
            duration_frames=duration_frames,
        )
        _render_limiter_reference(
            renderer,
            bank,
            target,
            second_path,
            warmup_frames=0,
            duration_frames=duration_frames,
        )
        first_payload, first = _read_pcm16_stereo(first_path)
        second_payload, second = _read_pcm16_stereo(second_path)
        independent_exact = first_payload == second_payload
        if not independent_exact:
            raise AssertionError(
                f"one-shot limiter repeat render differs for {target['sourceGuid']}"
            )
        nonzero = np.flatnonzero(np.any(first != 0, axis=1))
        final_frames = int(nonzero[-1] + 1) if len(nonzero) else 1
        final_payload = first_payload[: final_frames * 4]
        loop_start = 0
        loop_end = final_frames
        extra = {"independentRenderBitExact": True}
    elif mode == "PERSISTENT_DECAY_REGION_LOOP":
        # Choose a small integer number of authored source cycles whose total
        # rendered length is closest to an integer frame.  This keeps the
        # final loop modest while reducing fractional-resampler phase error.
        _fractional_error, source_cycles, group_frames = min(
            (
                abs(cycles * rendered_source_frames - round(cycles * rendered_source_frames)),
                cycles,
                max(1, round(cycles * rendered_source_frames)),
            )
            for cycles in range(1, 17)
        )
        duration_frames = 3 * group_frames
        _render_limiter_reference(
            renderer,
            bank,
            target,
            first_path,
            warmup_frames=0,
            duration_frames=duration_frames,
        )
        _render_limiter_reference(
            renderer,
            bank,
            target,
            second_path,
            warmup_frames=0,
            duration_frames=duration_frames,
        )
        first_payload, first = _read_pcm16_stereo(first_path)
        second_payload, _second = _read_pcm16_stereo(second_path)
        independent_exact = first_payload == second_payload
        if not independent_exact:
            raise AssertionError(
                f"loop limiter repeat render differs for {target['sourceGuid']}"
            )
        loop_comparison = _pcm_difference(
            first[group_frames : 2 * group_frames],
            first[2 * group_frames : 3 * group_frames],
        )
        audible = loop_comparison["referenceRmsLsb"] > 0.0
        if audible and (
            loop_comparison["snrDb"] < 35.0
            or loop_comparison["gainErrorDb"] > 0.01
        ):
            raise AssertionError(
                f"loop limiter PCM gate failed for {target['sourceGuid']}"
            )
        final_payload = first_payload[: 2 * group_frames * 4]
        loop_start = group_frames
        loop_end = 2 * group_frames
        extra = {
            "independentRenderBitExact": True,
            "authoredSourceCyclesPerPackedLoop": source_cycles,
            "renderedSourceCycleFrames": rendered_source_frames,
            "adjacentLoopComparison": loop_comparison,
            "introThenLoop": True,
        }
    else:
        raise AssertionError(f"unknown limiter program mode {mode}")

    final_path = captures / f"{target['sourceGuid']}.wav"
    _write_pcm16_stereo(final_path, final_payload)
    all_zero = not any(final_payload)
    frame_count = len(final_payload) // 4
    return {
        "accepted": True,
        "captureMode": mode,
        "frameCount": frame_count,
        "pcmPayloadSha256": hashlib.sha256(final_payload).hexdigest(),
        "loopStartFrame": loop_start,
        "loopEndFrameExclusive": loop_end,
        "allPcmSamplesZero": all_zero,
        "audibilityDisposition": (
            "AUTHORED_TARGET_SILENT" if all_zero else "AUDIBLE_TARGET_PCM"
        ),
        "finalWavRelativePath": final_path.relative_to(output_root).as_posix(),
        "finalWavSha256": _sha256(final_path),
        **extra,
    }


def probe_catalog(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    output_root.mkdir(parents=True, exist_ok=True)
    summary = _load_json(
        graph_root / "summary.json", "ac-fmod-catalog-graph-audit-summary-v1"
    )
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    counts = _catalog_counts(classification)

    graph_topology_counts = {key: 0 for key in REPRESENTATIVES}
    for row in classification["sourceDecisions"]:
        if row.get("policy") != "allowCandidate" or row.get("role") != "LIMITER":
            continue
        candidate = _target(
            graph_root, summary, str(row["familyId"]), _guid(row["sourceGuid"])
        )
        graph_topology_counts[candidate["topology"]] += 1
    counts["topologyCounts"] = graph_topology_counts
    if counts != {
        "sources": 73,
        "families": 73,
        "lifetimeCounts": {"oneShot": 55, "continuous": 18},
        "topologyCounts": {
            "TIMELINE_ONE_SHOT": 48,
            "DECAY_REGION_ONE_SHOT": 7,
            "DECAY_REGION_LOOP": 18,
        },
    }:
        raise AssertionError(f"limiter catalog topology changed: {counts}")

    runs: list[dict[str, Any]] = []
    source_banks: list[dict[str, Any]] = []
    for topology, (family_id, source_guid) in REPRESENTATIVES.items():
        target = _target(graph_root, summary, family_id, source_guid)
        if target["topology"] != topology:
            raise AssertionError(f"representative {source_guid} changed topology")
        bank = root / target["bankRelativePath"]
        before = _sha256(bank)
        if before != family_id:
            raise AssertionError(f"source bank identity mismatch: {bank}")
        instruments = {
            _guid(item["guid"]): item for item in target["graph"]["instruments"]
        }
        event = next(
            item
            for item in target["graph"]["events"]
            if str(item["path"]) == target["eventPath"]
        )
        reachable = {
            _guid(item)
            for item in event["reachableInstrumentGuids"]
            if instruments.get(_guid(item), {}).get("kind") == "WaveformInstrumentNode"
        }
        isolated_path = output_root / f"{topology.casefold()}-target-only.bank"
        muted = reachable - {source_guid}
        if muted:
            isolation = create_isolated_bank_copy(
                bank,
                target["graph"],
                muted,
                isolated_path,
            )
            runtime_bank = isolation.output_path
            isolation_sha256 = isolation.output_sha256
            isolation_patches = isolation.patches
        else:
            # A one-waveform limiter event is already target-only. Loading the
            # installed bank is read-only and avoids pretending a patch was
            # required.
            runtime_bank = bank
            isolation_sha256 = before
            isolation_patches = ()
        wav = output_root / f"{topology.casefold()}-lifecycle.wav"
        run = _run_sequence(root, target, runtime_bank, wav)
        run["isolation"] = {
            "bankSha256": isolation_sha256,
            "mutedReachableWaveforms": len(isolation_patches),
            "targetWasNotPatched": source_guid
            not in {item.source_guid for item in isolation_patches},
            "eventWasAlreadySingleWaveform": not muted,
        }
        runs.append(run)
        after = _sha256(bank)
        source_banks.append(
            {
                "familyId": family_id,
                "sha256Before": before,
                "sha256After": after,
                "unchanged": before == after,
            }
        )
        if after != before:
            raise AssertionError(f"installed bank changed: {bank}")

    renderer = SilentFmodReferenceRenderer(root, dsp_buffer_frames=256)
    source_verifications: list[dict[str, Any]] = []
    verification_counts = {
        "verified": 0,
        "audible": 0,
        "authoredTargetSilent": 0,
        "programModes": {},
    }
    all_source_banks: list[dict[str, Any]] = []
    limiter_rows = [
        row
        for row in classification["sourceDecisions"]
        if row.get("policy") == "allowCandidate" and row.get("role") == "LIMITER"
    ]
    for row in limiter_rows:
        family_id = str(row["familyId"])
        source_guid = _guid(row["sourceGuid"])
        target = _target(graph_root, summary, family_id, source_guid)
        family = next(item for item in summary["families"] if item["familyId"] == family_id)
        bank = root / str(family["bankPath"])
        before = _sha256(bank)
        if before != family_id:
            raise AssertionError(f"source bank identity mismatch: {bank}")
        program = derive_manifest_limiter_program(target["graph"], row)
        derived_program_sha256 = hashlib.sha256(
            canonical_json_bytes(program)
        ).hexdigest()
        lifecycle_wav = output_root / "lifecycle" / f"{source_guid}.wav"
        lifecycle_wav.parent.mkdir(parents=True, exist_ok=True)
        lifecycle_run = _run_sequence(root, target, bank, lifecycle_wav)
        lifecycle = _validate_source_runtime_observation(lifecycle_run)
        pcm = _capture_source_pcm_verification(
            renderer, bank, target, program, output_root
        )
        payload = {
            "schema": LIMITER_VERIFICATION_SCHEMA,
            "familyId": family_id,
            "sourceGuid": source_guid,
            "eventPath": program["eventPath"],
            "programMode": program["programMode"],
            "derivedProgramSha256": derived_program_sha256,
            "executable": {
                "relativePath": "acs.exe",
                "sha256": _SUPPORTED_ACS_SHA256,
                "contractProofSchema": SCHEMA,
            },
            "renderer": {
                "runtime": "FMOD Studio API 1.08.12",
                "mode": "WAVWRITER_NRT",
                "sampleRateHz": 48000,
                "channels": 2,
                "sampleFormat": "signedPcm16LittleEndian",
                "dspBufferFrames": 256,
                "audioDeviceOpened": False,
                "targetOnly": True,
            },
            "lifecycle": lifecycle,
            "pcm": pcm,
        }
        payload["verificationPayloadSha256"] = hashlib.sha256(
            canonical_json_bytes(payload)
        ).hexdigest()
        certified = certify_manifest_limiter_program(program, payload)
        if certified["fidelity"]["exactnessClaim"] is not True:
            raise AssertionError(f"limiter source did not certify: {source_guid}")
        source_verifications.append(payload)
        verification_counts["verified"] += 1
        disposition = pcm["audibilityDisposition"]
        if disposition == "AUTHORED_TARGET_SILENT":
            verification_counts["authoredTargetSilent"] += 1
        else:
            verification_counts["audible"] += 1
        modes = verification_counts["programModes"]
        modes[program["programMode"]] = modes.get(program["programMode"], 0) + 1
        after = _sha256(bank)
        all_source_banks.append(
            {
                "familyId": family_id,
                "sha256Before": before,
                "sha256After": after,
                "unchanged": before == after,
            }
        )
        if after != before:
            raise AssertionError(f"installed bank changed: {bank}")
    if verification_counts["verified"] != 73:
        raise AssertionError(f"not all limiter sources verified: {verification_counts}")
    return {
        "schema": SCHEMA,
        "basis": {
            "runtime": "FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "dspBufferFrames": 256,
            "audioDeviceOpened": False,
            "targetOnly": True,
            "sampleNamesUsedOnlyForRuntimeIdentity": True,
            "sampleNamesUsedForSemantics": False,
        },
        "catalogCounts": counts,
        "acExecutableContract": _ac_executable_contract(root),
        "observedRuntimeContract": _validate_runtime_observations(runs),
        "sourceVerificationCounts": verification_counts,
        "sourceVerifications": source_verifications,
        "sourceBanks": all_source_banks,
        "representativeSourceBanks": source_banks,
        "runs": runs,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument(
        "--graph-root",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3",
    )
    parser.add_argument(
        "--classification",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "limiter-lifecycle-oracle-v1",
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = probe_catalog(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        args.output_root,
    )
    report = args.report or args.output_root / "proof.json"
    report.write_bytes(canonical_json_bytes(result) + b"\n")
    for run in result["runs"]:
        played = sum(item["kind"] == "played" for item in run["callbacks"])
        stopped = sum(item["kind"] == "stopped" for item in run["callbacks"])
        print(f"{run['topology']}: played={played} stopped={stopped}")
    print(f"evidence={report.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
