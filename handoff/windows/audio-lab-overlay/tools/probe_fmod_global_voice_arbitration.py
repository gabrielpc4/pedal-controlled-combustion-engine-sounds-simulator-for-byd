"""Silently probe FMOD 1.08 global voice arbitration with an official AC bank.

This oracle is deliberately separate from the release compiler.  It makes one
copy-only derivative of the official Porsche 911 GT3 Cup 2017 bank, changing
only parser-attributed instrument trigger-chance fields so four exact direct
sources remain:

* one low-RPM ENGINE_EVENT transient,
* one mid-RPM ENGINE_EVENT transient from a different authored program,
* the authored continuous idle loop, and
* the authored fixed gear-up one-shot.

Every run uses ``WAVWRITER_NRT`` at 48 kHz stereo with a 256-frame DSP buffer.
No playback device is opened.  The report distinguishes observations made at
DSP-update boundaries from within-buffer behavior, which this API-level oracle
cannot see.
"""

from __future__ import annotations

import argparse
from collections import Counter
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
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_native import (
    FMOD_VERSION,
    _attributes,
    _distance_filter_description,
    _gain_description,
)
from sim.fmod_renderer import (
    EventCallback,
    FMOD_OUTPUTTYPE_WAVWRITER_NRT,
    FMOD_SPEAKERMODE_STEREO,
    FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
    FMOD_STUDIO_STOP_IMMEDIATE,
    SilentFmodReferenceRenderer,
)
from tools.probe_fmod_source_direction import _event_description


SCHEMA = "ac-fmod-global-voice-arbitration-oracle-v1"
GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
SUMMARY_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
CAPTURE_PLAN_SCHEMA = 2
FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED = 0x00004000
FMOD_STUDIO_EVENT_PROPERTY_CHANNELPRIORITY = 0
FMOD_TIMEUNIT_PCM = 0x00000002
DSP_BUFFER_FRAMES = 256
SOFTWARE_CHANNELS = 256
LOGICAL_CHANNELS = 2048

DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_CAPTURE_PLAN = PROJECT_ROOT / ".aclib-local" / "capture-plan-v2-working.json"
DEFAULT_OUTPUT_ROOT = (
    PROJECT_ROOT / ".aclib-local" / "fmod-global-voice-arbitration-v1"
)

FAMILY_ID = "8c34a92ecdcd8a31c14676615bb77c2c86a37ef888decc721d2f7e147221eac1"
REPRESENTATIVE_CAR_ID = "ks_porsche_911_gt3_cup_2017"
ENGINE_EVENT = "event:/cars/ks_porsche_911_gt3_cup_2017/engine_int"
GEAR_EVENT = "event:/cars/ks_porsche_911_gt3_cup_2017/gear_int"

# These are technical graph identities, not sample-name classification.  Their
# roles and distinct ENGINE_EVENT program ancestry are validated against the
# capture plan before FMOD is started.
TARGETS = {
    "lowTransient": {
        "guid": "0deb3aae-3fe8-476d-b453-7bfaf1dafcf5",
        "eventPath": ENGINE_EVENT,
        "role": "ENGINE_TRANSIENT",
        "programId": "engineevent_engine_int_0deb3aae3fe8",
        "rpm": 7000.0,
    },
    "midTransient": {
        "guid": "4fcff6f0-d109-4f07-86ed-ac83d9752e30",
        "eventPath": ENGINE_EVENT,
        "role": "ENGINE_TRANSIENT",
        "programId": "engineevent_engine_int_4fcff6f0d109",
        "rpm": 14000.0,
    },
    "idleLoop": {
        "guid": "f49b94fb-338b-4160-81de-fa7d454f8542",
        "eventPath": ENGINE_EVENT,
        "role": "IDLE",
        "programId": None,
        "rpm": 1200.0,
    },
    "fixedGearUp": {
        "guid": "8f0f7966-2e0c-4300-b19b-078a211412d0",
        "eventPath": GEAR_EVENT,
        "role": "SHIFT_UP",
        "programId": None,
        "state": 1.0,
    },
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _read_json(path: Path, schema: object | None = None) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"JSON root is not an object: {path}")
    if schema is not None:
        observed = document.get("schema", document.get("schemaVersion"))
        if observed != schema:
            raise ValueError(
                f"schema mismatch for {path}: expected {schema!r}, got {observed!r}"
            )
    return document


def run_length_encode(values: Iterable[str]) -> list[dict[str, Any]]:
    """Return a compact, deterministic representation of an observed order."""

    result: list[dict[str, Any]] = []
    for value in values:
        value = str(value)
        if result and result[-1]["source"] == value:
            result[-1]["count"] += 1
        else:
            result.append({"source": value, "count": 1})
    return result


def summarize_voices(voices: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Summarize one exact channel snapshot without discarding categories."""

    rows = list(voices)

    def bucket(subset: list[dict[str, Any]]) -> dict[str, Any]:
        real = [item for item in subset if not item["isVirtual"]]
        virtual = [item for item in subset if item["isVirtual"]]
        return {
            "logical": len(subset),
            "real": len(real),
            "virtual": len(virtual),
            "realPcmPositionMinimum": min(
                (int(item["pcmPosition"]) for item in real), default=None
            ),
            "realPcmPositionMaximum": max(
                (int(item["pcmPosition"]) for item in real), default=None
            ),
            "virtualPcmPositionMinimum": min(
                (int(item["pcmPosition"]) for item in virtual), default=None
            ),
            "virtualPcmPositionMaximum": max(
                (int(item["pcmPosition"]) for item in virtual), default=None
            ),
            "priorityCounts": {
                str(key): value
                for key, value in sorted(
                    Counter(int(item["priority"]) for item in subset).items()
                )
            },
            "audibilityMinimum": min(
                (float(item["audibility"]) for item in subset), default=None
            ),
            "audibilityMaximum": max(
                (float(item["audibility"]) for item in subset), default=None
            ),
        }

    by_source = {
        source: bucket([item for item in rows if item["source"] == source])
        for source in sorted({str(item["source"]) for item in rows})
    }
    by_category = {
        category: bucket([item for item in rows if item["category"] == category])
        for category in sorted({str(item["category"]) for item in rows})
    }
    return {**bucket(rows), "bySource": by_source, "byCategory": by_category}


def diff_voice_snapshots(
    before: Iterable[dict[str, Any]], after: Iterable[dict[str, Any]]
) -> dict[str, Any]:
    """Identify exact handle-level completion, promotion, and demotion changes."""

    left = {int(item["voiceToken"]): item for item in before}
    right = {int(item["voiceToken"]): item for item in after}
    completed = [left[token] for token in sorted(left.keys() - right.keys())]
    appeared = [right[token] for token in sorted(right.keys() - left.keys())]
    promoted = []
    demoted = []
    for token in sorted(left.keys() & right.keys()):
        old = left[token]
        new = right[token]
        if old["isVirtual"] and not new["isVirtual"]:
            promoted.append(
                {
                    "voiceToken": token,
                    "source": new["source"],
                    "category": new["category"],
                    "instanceKey": new.get("instanceKey"),
                    "pcmPositionBefore": old["pcmPosition"],
                    "pcmPositionAfter": new["pcmPosition"],
                }
            )
        elif not old["isVirtual"] and new["isVirtual"]:
            demoted.append(
                {
                    "voiceToken": token,
                    "source": new["source"],
                    "category": new["category"],
                    "instanceKey": new.get("instanceKey"),
                    "pcmPositionBefore": old["pcmPosition"],
                    "pcmPositionAfter": new["pcmPosition"],
                }
            )
    return {
        "completed": [
            {
                "voiceToken": item["voiceToken"],
                "source": item["source"],
                "category": item["category"],
                "instanceKey": item.get("instanceKey"),
                "wasVirtual": item["isVirtual"],
                "pcmPosition": item["pcmPosition"],
            }
            for item in completed
        ],
        "appeared": [
            {
                "voiceToken": item["voiceToken"],
                "source": item["source"],
                "category": item["category"],
                "instanceKey": item.get("instanceKey"),
                "isVirtual": item["isVirtual"],
                "pcmPosition": item["pcmPosition"],
            }
            for item in appeared
        ],
        "promoted": promoted,
        "demoted": demoted,
    }


def _source_counts(labels: Iterable[str]) -> dict[str, int]:
    return {key: value for key, value in sorted(Counter(labels).items())}


def _differing_offsets(left_path: Path, right_path: Path) -> tuple[int, ...]:
    result: list[int] = []
    absolute = 0
    with left_path.open("rb") as left, right_path.open("rb") as right:
        while True:
            before = left.read(1024 * 1024)
            after = right.read(1024 * 1024)
            if len(before) != len(after):
                raise ValueError("oracle derivative changed bank size")
            if not before:
                break
            result.extend(
                absolute + index
                for index, (old, new) in enumerate(zip(before, after))
                if old != new
            )
            absolute += len(before)
    return tuple(result)


def _create_target_derivative(
    source_bank: Path,
    graph: dict[str, Any],
    target_guids: set[str],
    output_path: Path,
) -> dict[str, Any]:
    """Mute every non-target waveform and multi-instrument in a bank copy.

    The shared release isolator intentionally accepts waveform bodies only.
    This oracle also has to suppress Porsche smart-random parents: a selected
    child whose own chance is zero can still be scheduled by that parent in
    FMOD 1.08.  Therefore this dedicated proof patches the same parser-proven
    ``InstrumentBody`` chance field on all *non-target* waveform and
    multi-instrument nodes.  It remains copy-only and byte-bounded.
    """

    source = source_bank.resolve(strict=True)
    output = output_path.resolve()
    if output == source:
        raise ValueError("oracle derivative must not overwrite its source bank")
    source_sha_before = _sha256(source)
    if graph.get("bank", {}).get("sha256") != source_sha_before:
        raise ValueError("graph/source identity mismatch for oracle derivative")
    source_size = source.stat().st_size
    patches: list[dict[str, Any]] = []
    with source.open("rb") as raw:
        for instrument in graph.get("instruments", []):
            if not isinstance(instrument, dict):
                continue
            guid = _guid(instrument.get("guid"))
            kind = str(instrument.get("kind") or "")
            if guid in target_guids:
                if kind != "WaveformInstrumentNode":
                    raise ValueError(f"oracle target is not a waveform: {guid}")
                continue
            if kind not in {"WaveformInstrumentNode", "MultiInstrumentNode"}:
                continue
            properties = instrument.get("baseProperties") or {}
            chance = float(properties.get("triggerChancePercent") or 0.0)
            if chance <= 0.0:
                continue
            offset = int(properties["triggerChancePercentFileOffset"])
            raw_uint32 = int(properties["triggerChancePercentRawUInt32"])
            if offset < 0 or offset + 4 > source_size:
                raise ValueError(f"oracle chance offset is outside the bank: {guid}")
            expected = struct.pack("<I", raw_uint32)
            raw.seek(offset)
            if raw.read(4) != expected or expected != struct.pack("<f", chance):
                raise ValueError(f"parser/source chance bytes disagree: {guid}@{offset}")
            patches.append(
                {
                    "sourceGuid": guid,
                    "instrumentKind": kind,
                    "offset": offset,
                    "originalChancePercent": chance,
                }
            )
    offsets = [int(item["offset"]) for item in patches]
    if len(offsets) != len(set(offsets)):
        raise ValueError("oracle targets share an attributed chance-field offset")
    if not patches:
        raise ValueError("oracle derivative has no non-target instruments to mute")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
        shutil.copyfile(source, temporary_path)
        with temporary_path.open("r+b", buffering=0) as target:
            for patch in patches:
                target.seek(int(patch["offset"]))
                target.write(b"\0\0\0\0")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    if source.stat().st_size != source_size or _sha256(source) != source_sha_before:
        raise AssertionError("installed bank changed while building oracle derivative")
    if output.stat().st_size != source_size:
        raise AssertionError("oracle derivative size differs from source bank")
    differing = _differing_offsets(source, output)
    allowed = {
        int(item["offset"]) + byte_index
        for item in patches
        for byte_index in range(4)
    }
    if not differing or not set(differing).issubset(allowed):
        raise AssertionError("oracle derivative changed bytes outside chance fields")
    with output.open("rb") as isolated:
        for patch in patches:
            isolated.seek(int(patch["offset"]))
            if isolated.read(4) != b"\0\0\0\0":
                raise AssertionError("oracle derivative left a selected chance nonzero")
    return {
        "outputPath": str(output),
        "outputSha256": _sha256(output),
        "sourceSha256": source_sha_before,
        "sourceSize": source_size,
        "patches": patches,
        "differingByteOffsets": differing,
    }


def _fixture(
    assetto_root: Path,
    graph_root: Path,
    capture_plan_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    summary = _read_json(graph_root / "summary.json", SUMMARY_SCHEMA)
    family = next(
        (item for item in summary.get("families", []) if item.get("familyId") == FAMILY_ID),
        None,
    )
    if family is None:
        raise ValueError(f"official oracle family is absent: {FAMILY_ID}")
    if family.get("representativeCarId") != REPRESENTATIVE_CAR_ID:
        raise ValueError("official oracle representative changed")
    graph_path = graph_root / str(family["cachePath"])
    graph = _read_json(graph_path, GRAPH_SCHEMA)
    bank_path = assetto_root / str(family["bankPath"])
    source_sha_before = _sha256(bank_path)
    if source_sha_before != FAMILY_ID or graph.get("bank", {}).get("sha256") != FAMILY_ID:
        raise ValueError("official Porsche source-bank identity mismatch")

    plan = _read_json(capture_plan_path, CAPTURE_PLAN_SCHEMA)
    plan_family = next(
        (item for item in plan.get("families", []) if item.get("familyId") == FAMILY_ID),
        None,
    )
    if plan_family is None:
        raise ValueError("official Porsche family is absent from capture plan")

    instruments = {
        _guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    events = {
        str(item.get("path")): item
        for item in graph.get("events", [])
        if isinstance(item, dict)
    }
    recipes = {
        _guid(item.get("sourceGuid")): item
        for item in plan_family.get("recipes", [])
        if isinstance(item, dict) and _guid(item.get("sourceGuid"))
    }
    programs = {
        str(item.get("id")): item
        for item in plan_family.get("oneShotPrograms", [])
        if isinstance(item, dict)
    }

    target_guids = {_guid(item["guid"]) for item in TARGETS.values()}
    target_details: dict[str, Any] = {}
    runtime_name_to_source: dict[str, str] = {}
    for label, target in TARGETS.items():
        guid = _guid(target["guid"])
        instrument = instruments.get(guid)
        if instrument is None or instrument.get("kind") != "WaveformInstrumentNode":
            raise ValueError(f"oracle target waveform is absent: {label}/{guid}")
        event_path = str(target["eventPath"])
        event = events.get(event_path)
        if event is None or guid not in {_guid(item) for item in event["reachableInstrumentGuids"]}:
            raise ValueError(f"oracle target is not reachable from {event_path}: {label}")
        recipe = recipes.get(guid)
        if recipe is None or recipe.get("role") != target["role"]:
            raise ValueError(f"capture-plan role changed for {label}")
        if recipe.get("eventPath") != event_path:
            raise ValueError(f"capture-plan event changed for {label}")
        program_id = target.get("programId")
        if program_id:
            program = programs.get(str(program_id))
            track_id = "track_" + guid.replace("-", "")
            if (
                program is None
                or program.get("trigger") != "ENGINE_EVENT"
                or program.get("rootNodeIds") != [track_id]
                or track_id
                not in {
                    str(node.get("id"))
                    for node in program.get("nodes", [])
                    if isinstance(node, dict)
                }
            ):
                raise ValueError(f"ENGINE_EVENT program ancestry changed for {label}")
        sample = instrument.get("sample") or {}
        runtime_name = str(sample.get("name") or "")
        if not runtime_name or runtime_name in runtime_name_to_source:
            raise ValueError(f"oracle target runtime identity is empty or duplicated: {label}")
        runtime_name_to_source[runtime_name] = label
        properties = instrument.get("baseProperties") or {}
        target_details[label] = {
            "sourceGuid": guid,
            "eventPath": event_path,
            "manifestRole": target["role"],
            "programId": program_id,
            "sample": {
                "runtimeIdentitySha256": hashlib.sha256(
                    runtime_name.encode("utf-8")
                ).hexdigest(),
                "encodedPayloadSha256": sample.get("encodedPayloadSha256"),
                "sampleCount": sample.get("sampleCount"),
                "frequencyHz": sample.get("frequencyHz"),
                "channels": sample.get("channels"),
            },
            "baseProperties": {
                "loopCount": properties.get("loopCount"),
                "autoPitchReference": properties.get("autoPitchReference"),
                "volumeDb": properties.get("volumeDb"),
                "triggerChancePercent": properties.get("triggerChancePercent"),
            },
        }

    # Mute every other schedulable waveform and smart-random parent in the
    # copied bank.  Parameter topology is untouched and all four targets are
    # direct tracks, so their distinct authored regions remain authoritative.
    isolated_path = output_root / "porsche-gt3-cup-four-targets.bank"
    isolated = _create_target_derivative(
        bank_path,
        graph,
        target_guids,
        isolated_path,
    )
    source_sha_after = _sha256(bank_path)
    if source_sha_after != source_sha_before:
        raise AssertionError("installed Assetto Corsa bank changed during oracle setup")
    patched = {item["sourceGuid"] for item in isolated["patches"]}
    if target_guids & patched:
        raise AssertionError("an oracle target was muted in the derivative bank")
    return {
        "familyId": FAMILY_ID,
        "representativeCarId": REPRESENTATIVE_CAR_ID,
        "sourceBankPath": str(bank_path),
        "sourceBankSha256Before": source_sha_before,
        "sourceBankSha256After": source_sha_after,
        "sourceBankUnchanged": source_sha_before == source_sha_after,
        "graphReportPath": str(graph_path),
        "capturePlanPath": str(capture_plan_path),
        "isolatedBankPath": isolated["outputPath"],
        "isolatedBankSha256": isolated["outputSha256"],
        "mutedInstrumentCount": len(isolated["patches"]),
        "mutedInstrumentKindCounts": {
            key: value
            for key, value in sorted(
                Counter(item["instrumentKind"] for item in isolated["patches"]).items()
            )
        },
        "changedByteCount": len(isolated["differingByteOffsets"]),
        "changedBytesOnlyVerifiedTriggerChanceFields": True,
        "targets": target_details,
        "_runtimeNameToSource": runtime_name_to_source,
    }


class _OracleRuntime:
    """One isolated, no-device FMOD system used by a single bounded case."""

    def __init__(
        self,
        assetto_root: Path,
        bank_path: Path,
        runtime_name_to_source: dict[str, str],
        output_wav: Path,
        *,
        max_channels: int,
        software_channels: int = SOFTWARE_CHANNELS,
        event_paths: Iterable[str] | None = None,
    ) -> None:
        self.root = assetto_root.resolve(strict=True)
        self.bank_path = bank_path.resolve(strict=True)
        self.output_wav = output_wav.resolve()
        self.output_wav.parent.mkdir(parents=True, exist_ok=True)
        self.runtime_name_to_source = dict(runtime_name_to_source)
        self.max_channels = int(max_channels)
        self.software_channels = int(software_channels)
        self.event_paths = tuple(
            dict.fromkeys(event_paths or (ENGINE_EVENT, GEAR_EVENT))
        )
        if not self.event_paths:
            raise ValueError("at least one event path is required")
        self.renderer = SilentFmodReferenceRenderer(
            self.root, dsp_buffer_frames=DSP_BUFFER_FRAMES
        )
        self.cookie = os.add_dll_directory(str(self.root))
        self.core = C.WinDLL(str(self.root / "fmod64.dll"))
        self.studio = C.WinDLL(str(self.root / "fmodstudio64.dll"))
        self.renderer._bind(self.core, self.studio)
        self._bind_extra()
        self.system = C.c_void_p()
        self.low_level = C.c_void_p()
        self.instances: dict[str, dict[str, Any]] = {}
        self.callbacks: list[dict[str, Any]] = []
        self.callback_keepalive: list[Any] = []
        self.callback_errors: list[str] = []
        self.update_index = 0
        self.studio_update_index = 0
        self.mixer_suspended = False
        self.stage = "setup"
        self._next_voice_token = 1
        self._voice_tokens: dict[int, int] = {}
        self._closed = False
        self.loaded_banks: list[C.c_void_p] = []
        self.descriptions: dict[str, C.c_void_p] = {}
        self.writer_name: Any = None
        self.plugin_keepalive: tuple[Any, ...] = ()
        self._initialize()

    def _bind_extra(self) -> None:
        core = self.core
        studio = self.studio
        core.FMOD_System_SetSoftwareChannels.argtypes = [C.c_void_p, C.c_int]
        core.FMOD_System_SetSoftwareChannels.restype = C.c_int
        core.FMOD_System_GetChannelsPlaying.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
            C.POINTER(C.c_int),
        ]
        core.FMOD_System_GetChannelsPlaying.restype = C.c_int
        core.FMOD_System_MixerSuspend.argtypes = [C.c_void_p]
        core.FMOD_System_MixerSuspend.restype = C.c_int
        core.FMOD_System_MixerResume.argtypes = [C.c_void_p]
        core.FMOD_System_MixerResume.restype = C.c_int
        core.FMOD_ChannelGroup_GetNumChannels.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        core.FMOD_ChannelGroup_GetNumChannels.restype = C.c_int
        core.FMOD_ChannelGroup_GetChannel.argtypes = [
            C.c_void_p,
            C.c_int,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_ChannelGroup_GetChannel.restype = C.c_int
        core.FMOD_ChannelGroup_GetNumGroups.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        core.FMOD_ChannelGroup_GetNumGroups.restype = C.c_int
        core.FMOD_ChannelGroup_GetGroup.argtypes = [
            C.c_void_p,
            C.c_int,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_ChannelGroup_GetGroup.restype = C.c_int
        core.FMOD_Channel_IsVirtual.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
        core.FMOD_Channel_IsVirtual.restype = C.c_int
        core.FMOD_Channel_GetPosition.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_uint),
            C.c_uint,
        ]
        core.FMOD_Channel_GetPosition.restype = C.c_int
        core.FMOD_Channel_GetCurrentSound.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_Channel_GetCurrentSound.restype = C.c_int
        core.FMOD_Channel_GetPriority.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
        core.FMOD_Channel_GetPriority.restype = C.c_int
        core.FMOD_Sound_GetDefaults.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_float),
            C.POINTER(C.c_int),
        ]
        core.FMOD_Sound_GetDefaults.restype = C.c_int
        core.FMOD_Channel_GetAudibility.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_float),
        ]
        core.FMOD_Channel_GetAudibility.restype = C.c_int
        studio.FMOD_Studio_EventInstance_SetVolume.argtypes = [
            C.c_void_p,
            C.c_float,
        ]
        studio.FMOD_Studio_EventInstance_SetVolume.restype = C.c_int
        studio.FMOD_Studio_EventInstance_SetPitch.argtypes = [
            C.c_void_p,
            C.c_float,
        ]
        studio.FMOD_Studio_EventInstance_SetPitch.restype = C.c_int
        studio.FMOD_Studio_EventInstance_SetProperty.argtypes = [
            C.c_void_p,
            C.c_int,
            C.c_float,
        ]
        studio.FMOD_Studio_EventInstance_SetProperty.restype = C.c_int
        studio.FMOD_Studio_EventInstance_GetChannelGroup.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        studio.FMOD_Studio_EventInstance_GetChannelGroup.restype = C.c_int

    def _check(self, result: int, operation: str) -> None:
        self.renderer._check(result, operation)

    def _initialize(self) -> None:
        self._check(
            self.studio.FMOD_Studio_System_Create(C.byref(self.system), FMOD_VERSION),
            "create global-arbitration Studio system",
        )
        self._check(
            self.studio.FMOD_Studio_System_GetLowLevelSystem(
                self.system, C.byref(self.low_level)
            ),
            "get global-arbitration low-level system",
        )
        self._check(
            self.core.FMOD_System_SetOutput(
                self.low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT
            ),
            "select no-device WAVWRITER_NRT output",
        )
        self._check(
            self.core.FMOD_System_SetSoftwareFormat(
                self.low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0
            ),
            "set global-arbitration stereo format",
        )
        self._check(
            self.core.FMOD_System_SetDSPBufferSize(
                self.low_level, DSP_BUFFER_FRAMES, 4
            ),
            "set global-arbitration DSP buffer",
        )
        self._check(
            self.core.FMOD_System_SetSoftwareChannels(
                self.low_level, self.software_channels
            ),
            "set global-arbitration software voices",
        )
        self.writer_name = C.create_string_buffer(str(self.output_wav).encode("utf-8"))
        self._check(
            self.studio.FMOD_Studio_System_Initialize(
                self.system,
                self.max_channels,
                0,
                0,
                C.cast(self.writer_name, C.c_void_p),
            ),
            "initialize no-device global-arbitration writer",
        )
        distance, distance_keepalive = _distance_filter_description()
        gain, gain_keepalive = _gain_description()
        self._check(
            self.studio.FMOD_Studio_System_RegisterPlugin(
                self.system, C.byref(distance)
            ),
            "register AC distance filter",
        )
        self._check(
            self.studio.FMOD_Studio_System_RegisterPlugin(self.system, C.byref(gain)),
            "register AC gain compatibility DSP",
        )
        self.plugin_keepalive = (
            distance,
            distance_keepalive,
            gain,
            gain_keepalive,
        )
        for path in (
            self.root / "content" / "sfx" / "common.strings.bank",
            self.root / "content" / "sfx" / "common.bank",
            self.bank_path,
        ):
            bank = C.c_void_p()
            self._check(
                self.studio.FMOD_Studio_System_LoadBankFile(
                    self.system, str(path).encode("utf-8"), 0, C.byref(bank)
                ),
                f"load global-arbitration bank {path.name}",
            )
            self.loaded_banks.append(bank)
        car_bank = self.loaded_banks[-1]
        for event_path in self.event_paths:
            description = _event_description(self.studio, car_bank, event_path)
            self._check(
                self.studio.FMOD_Studio_EventDescription_LoadSampleData(description),
                f"load sample data for {event_path}",
            )
            self.descriptions[event_path] = description
        self._check(
            self.studio.FMOD_Studio_System_FlushSampleLoading(self.system),
            "flush global-arbitration sample loading",
        )
        listener = _attributes((0.0, 0.7, 0.0))
        self._check(
            self.studio.FMOD_Studio_System_SetListenerAttributes(
                self.system, 0, C.byref(listener)
            ),
            "place global-arbitration listener",
        )

    def create_instance(
        self,
        category: str,
        event_path: str,
        *,
        parameters: dict[str, float],
        volume: float = 1.0,
        pitch: float = 1.0,
        priority: int | None = None,
        voice_category: str | None = None,
    ) -> None:
        if category in self.instances:
            raise ValueError(f"duplicate oracle category: {category}")
        observed_category = voice_category or category
        instance = C.c_void_p()
        self._check(
            self.studio.FMOD_Studio_EventDescription_CreateInstance(
                self.descriptions[event_path], C.byref(instance)
            ),
            f"create {category} instance",
        )
        emitter = _attributes((0.0, 0.5, 0.0))
        self._check(
            self.studio.FMOD_Studio_EventInstance_Set3DAttributes(
                instance, C.byref(emitter)
            ),
            f"place {category} instance",
        )
        for name, value in sorted(parameters.items()):
            self._check(
                self.studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, name.encode("ascii"), C.c_float(value)
                ),
                f"set {category} parameter {name}",
            )
        self._check(
            self.studio.FMOD_Studio_EventInstance_SetVolume(
                instance, C.c_float(volume)
            ),
            f"set {category} event volume",
        )
        if not math.isfinite(pitch) or pitch <= 0.0:
            raise ValueError("FMOD event pitch must be finite and positive")
        self._check(
            self.studio.FMOD_Studio_EventInstance_SetPitch(
                instance, C.c_float(pitch)
            ),
            f"set {category} event pitch",
        )
        if priority is not None:
            if not 0 <= int(priority) <= 256:
                raise ValueError("FMOD channel priority must be in 0..256")
            self._check(
                self.studio.FMOD_Studio_EventInstance_SetProperty(
                    instance,
                    FMOD_STUDIO_EVENT_PROPERTY_CHANNELPRIORITY,
                    C.c_float(priority),
                ),
                f"set {category} channel-priority property",
            )

        @EventCallback
        def callback(callback_type: int, _event: int, sound_pointer: int) -> int:
            if callback_type not in {
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
            } or not sound_pointer:
                return 0
            name_buffer = C.create_string_buffer(1024)
            result = self.core.FMOD_Sound_GetName(
                C.c_void_p(sound_pointer), name_buffer, len(name_buffer)
            )
            if result:
                self.callback_errors.append(
                    f"read {category} callback sound identity: FMOD result {result}"
                )
                return 0
            runtime_name = name_buffer.value.decode("utf-8", "replace")
            source = self.runtime_name_to_source.get(runtime_name)
            if source is None:
                self.callback_errors.append(
                    "target-isolated bank scheduled an unexpected sound identity: "
                    + repr(runtime_name)
                )
                return 0
            self.callbacks.append(
                {
                    "kind": (
                        "played"
                        if callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                        else "stopped"
                    ),
                    "category": observed_category,
                    "source": source,
                    "stage": self.stage,
                    "dspUpdate": self.update_index + 1,
                    "studioUpdate": self.studio_update_index + 1,
                    "mixerSuspended": self.mixer_suspended,
                }
            )
            return 0

        self._check(
            self.studio.FMOD_Studio_EventInstance_SetCallback(
                instance,
                callback,
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                | FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
            ),
            f"attach {category} lifecycle callback",
        )
        self.callback_keepalive.append(callback)
        self.instances[category] = {
            "handle": instance,
            "eventPath": event_path,
            "voiceCategory": observed_category,
            "started": False,
            "volume": float(volume),
            "pitch": float(pitch),
            "priorityOverride": priority,
        }

    def set_parameter(self, category: str, name: str, value: float) -> None:
        instance = self.instances[category]["handle"]
        self._check(
            self.studio.FMOD_Studio_EventInstance_SetParameterValue(
                instance, name.encode("ascii"), C.c_float(value)
            ),
            f"set {category} parameter {name}",
        )

    def set_pitch(self, category: str, value: float) -> None:
        if not math.isfinite(value) or value <= 0.0:
            raise ValueError("FMOD event pitch must be finite and positive")
        instance = self.instances[category]["handle"]
        self._check(
            self.studio.FMOD_Studio_EventInstance_SetPitch(
                instance, C.c_float(value)
            ),
            f"set {category} event pitch",
        )
        self.instances[category]["pitch"] = float(value)

    def start(self, category: str) -> None:
        instance = self.instances[category]["handle"]
        self._check(
            self.studio.FMOD_Studio_EventInstance_Start(instance),
            f"start {category} instance",
        )
        self.instances[category]["started"] = True

    def stop_release(self, category: str) -> None:
        """Retire one measured instance without disturbing other programs."""

        record = self.instances.pop(category)
        instance = record["handle"]
        self._check(
            self.studio.FMOD_Studio_EventInstance_Stop(
                instance, FMOD_STUDIO_STOP_IMMEDIATE
            ),
            f"stop {category} instance",
        )
        self._check(
            self.studio.FMOD_Studio_EventInstance_Release(instance),
            f"release {category} instance",
        )
        self.flush(f"retire-{category}")
        self.update(f"retire-{category}")

    def flush(self, stage: str | None = None) -> None:
        if stage is not None:
            self.stage = stage
        self._check(
            self.studio.FMOD_Studio_System_FlushCommands(self.system),
            "flush global-arbitration commands",
        )
        self._raise_callback_errors()

    def update(self, stage: str) -> None:
        self.stage = stage
        self._check(
            self.studio.FMOD_Studio_System_Update(self.system),
            f"render global-arbitration update {stage}",
        )
        self.studio_update_index += 1
        if not self.mixer_suspended:
            self.update_index += 1
        self._raise_callback_errors()

    def suspend_mixer(self) -> None:
        if self.mixer_suspended:
            raise AssertionError("FMOD mixer is already suspended")
        self._check(
            self.core.FMOD_System_MixerSuspend(self.low_level),
            "suspend no-device mixer for zero-frame scheduling",
        )
        self.mixer_suspended = True

    def resume_mixer(self) -> None:
        if not self.mixer_suspended:
            raise AssertionError("FMOD mixer is not suspended")
        self._check(
            self.core.FMOD_System_MixerResume(self.low_level),
            "resume no-device mixer after zero-frame scheduling",
        )
        self.mixer_suspended = False

    def _raise_callback_errors(self) -> None:
        if self.callback_errors:
            raise AssertionError("; ".join(self.callback_errors))

    def _voice_token(self, channel: C.c_void_p) -> int:
        address = int(channel.value or 0)
        if not address:
            raise AssertionError("FMOD returned a null channel handle")
        token = self._voice_tokens.get(address)
        if token is None:
            token = self._next_voice_token
            self._next_voice_token += 1
            self._voice_tokens[address] = token
        return token

    def snapshot(self) -> dict[str, Any]:
        voices: list[dict[str, Any]] = []
        seen_channel_addresses: set[int] = set()
        for instance_key, record in self.instances.items():
            if not record["started"]:
                continue
            category = str(record["voiceCategory"])
            event_group = C.c_void_p()
            self._check(
                self.studio.FMOD_Studio_EventInstance_GetChannelGroup(
                    record["handle"], C.byref(event_group)
                ),
                f"get {instance_key} channel group",
            )
            pending: list[tuple[C.c_void_p, int]] = [(event_group, 0)]
            seen_groups: set[int] = set()
            while pending:
                group, depth = pending.pop()
                group_address = int(group.value or 0)
                if not group_address or group_address in seen_groups:
                    continue
                seen_groups.add(group_address)
                channel_count = C.c_int()
                self._check(
                    self.core.FMOD_ChannelGroup_GetNumChannels(
                        group, C.byref(channel_count)
                    ),
                    f"count {instance_key} channels",
                )
                for index in range(channel_count.value):
                    channel = C.c_void_p()
                    self._check(
                        self.core.FMOD_ChannelGroup_GetChannel(
                            group, index, C.byref(channel)
                        ),
                        f"get {instance_key} channel",
                    )
                    address = int(channel.value or 0)
                    if address in seen_channel_addresses:
                        raise AssertionError("FMOD channel appeared in two event groups")
                    seen_channel_addresses.add(address)
                    current_sound = C.c_void_p()
                    self._check(
                        self.core.FMOD_Channel_GetCurrentSound(
                            channel, C.byref(current_sound)
                        ),
                        f"get {instance_key} current sound",
                    )
                    name_buffer = C.create_string_buffer(1024)
                    self._check(
                        self.core.FMOD_Sound_GetName(
                            current_sound, name_buffer, len(name_buffer)
                        ),
                        f"read {instance_key} current sound identity",
                    )
                    runtime_name = name_buffer.value.decode("utf-8", "replace")
                    source = self.runtime_name_to_source.get(runtime_name)
                    if source is None:
                        raise AssertionError(
                            "snapshot found a sound outside the four-target derivative"
                        )
                    is_virtual = C.c_int()
                    position = C.c_uint()
                    priority = C.c_int()
                    default_frequency = C.c_float()
                    sound_default_priority = C.c_int()
                    audibility = C.c_float()
                    self._check(
                        self.core.FMOD_Channel_IsVirtual(
                            channel, C.byref(is_virtual)
                        ),
                        f"read {instance_key} virtual state",
                    )
                    self._check(
                        self.core.FMOD_Channel_GetPosition(
                            channel, C.byref(position), FMOD_TIMEUNIT_PCM
                        ),
                        f"read {instance_key} PCM position",
                    )
                    self._check(
                        self.core.FMOD_Channel_GetPriority(
                            channel, C.byref(priority)
                        ),
                        f"read {instance_key} channel priority",
                    )
                    self._check(
                        self.core.FMOD_Sound_GetDefaults(
                            current_sound,
                            C.byref(default_frequency),
                            C.byref(sound_default_priority),
                        ),
                        f"read {instance_key} sound defaults",
                    )
                    self._check(
                        self.core.FMOD_Channel_GetAudibility(
                            channel, C.byref(audibility)
                        ),
                        f"read {instance_key} channel audibility",
                    )
                    voices.append(
                        {
                            "voiceToken": self._voice_token(channel),
                            "instanceKey": instance_key,
                            "category": category,
                            "source": source,
                            "groupDepth": depth,
                            "isVirtual": bool(is_virtual.value),
                            "pcmPosition": int(position.value),
                            "priority": int(priority.value),
                            "soundDefaultPriority": int(
                                sound_default_priority.value
                            ),
                            "audibility": float(audibility.value),
                        }
                    )
                child_count = C.c_int()
                self._check(
                    self.core.FMOD_ChannelGroup_GetNumGroups(
                        group, C.byref(child_count)
                    ),
                    f"count {instance_key} child groups",
                )
                for index in range(child_count.value):
                    child = C.c_void_p()
                    self._check(
                        self.core.FMOD_ChannelGroup_GetGroup(
                            group, index, C.byref(child)
                        ),
                        f"get {instance_key} child group",
                    )
                    pending.append((child, depth + 1))
        total = C.c_int()
        real = C.c_int()
        self._check(
            self.core.FMOD_System_GetChannelsPlaying(
                self.low_level, C.byref(total), C.byref(real)
            ),
            "read global channel counts",
        )
        summary = summarize_voices(voices)
        if summary["logical"] != total.value or summary["real"] != real.value:
            raise AssertionError(
                "event-group enumeration disagrees with FMOD global channel counts: "
                f"snapshot={summary['logical']}/{summary['real']} "
                f"system={total.value}/{real.value}"
            )
        return {
            "dspUpdate": self.update_index,
            "studioUpdate": self.studio_update_index,
            "systemLogical": int(total.value),
            "systemReal": int(real.value),
            "summary": summary,
            "voices": sorted(
                voices,
                key=lambda item: (
                    item["category"],
                    item["source"],
                    item["isVirtual"],
                    item["pcmPosition"],
                    item["voiceToken"],
                ),
            ),
        }

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            if self.mixer_suspended:
                self.core.FMOD_System_MixerResume(self.low_level)
                self.mixer_suspended = False
            for record in self.instances.values():
                instance = record["handle"]
                if instance:
                    self.studio.FMOD_Studio_EventInstance_Stop(
                        instance, FMOD_STUDIO_STOP_IMMEDIATE
                    )
                    self.studio.FMOD_Studio_EventInstance_Release(instance)
            if self.system:
                self.studio.FMOD_Studio_System_UnloadAll(self.system)
                self.studio.FMOD_Studio_System_Release(self.system)
        finally:
            self.cookie.close()

    def __enter__(self) -> "_OracleRuntime":
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        self.close()


def _start_and_update(runtime: _OracleRuntime, category: str, stage: str) -> None:
    runtime.start(category)
    runtime.flush(stage)
    runtime.update(stage)


def _set_rpm_and_update(
    runtime: _OracleRuntime, category: str, rpm: float, stage: str
) -> None:
    runtime.set_parameter(category, "rpms", rpm)
    runtime.flush(stage)
    runtime.update(stage)


def _compact_transition(
    runtime: _OracleRuntime,
    before: dict[str, Any],
    after: dict[str, Any],
) -> dict[str, Any] | None:
    changes = diff_voice_snapshots(before["voices"], after["voices"])
    if not any(changes.values()):
        return None
    return {
        "dspUpdate": runtime.update_index,
        "summaryBefore": before["summary"],
        "summaryAfter": after["summary"],
        **changes,
    }


def _create_outer_transient_instance(
    runtime: _OracleRuntime,
    instance_key: str,
    *,
    pitch: float,
    volume: float = 1.0,
    priority: int | None = None,
) -> None:
    runtime.create_instance(
        instance_key,
        ENGINE_EVENT,
        parameters={"rpms": 7000.0, "throttle": 0.0},
        pitch=pitch,
        volume=volume,
        priority=priority,
        voice_category="engineTransient",
    )
    _start_and_update(runtime, instance_key, f"start-{instance_key}")


def _run_cross_program_case(
    assetto_root: Path,
    isolated_bank: Path,
    runtime_name_to_source: dict[str, str],
    output_wav: Path,
) -> dict[str, Any]:
    with _OracleRuntime(
        assetto_root,
        isolated_bank,
        runtime_name_to_source,
        output_wav,
        max_channels=LOGICAL_CHANNELS,
    ) as runtime:
        # Twenty-two of the first 256 outer-program voices also keep their
        # event instances alive so the nested program can be entered later.
        # Their outer voices are part of the measured oldest-real set; no
        # preparatory sound is discarded or hidden from the proof.
        preparer_keys = [f"midPreparer{index:03d}" for index in range(22)]
        for key in preparer_keys:
            _create_outer_transient_instance(runtime, key, pitch=0.01)
        low_real_keys = [f"oldestLow{index:03d}" for index in range(234)]
        for key in low_real_keys:
            _create_outer_transient_instance(runtime, key, pitch=0.01)
        measured_callback_mark = 0
        # Age the first, exactly-full real set while no virtual voices exist.
        # The explicit slow accumulation pitch keeps the short official source
        # alive without changing the 256-frame DSP update quantum.
        for age_index in range(31):
            runtime.update(f"age-oldest-real-set-{age_index + 1}")

        # 14900 is still inside the outer program but outside the nested one;
        # 14000 then enters only the nested program.  The outer placement does
        # not re-fire because it was never exited.
        for key in preparer_keys:
            _set_rpm_and_update(runtime, key, 14900.0, f"arm-inner-{key}")
            _set_rpm_and_update(runtime, key, 14000.0, f"enter-inner-{key}")

        low_virtual_keys = [f"newestLow{index:03d}" for index in range(23)]
        for key in low_virtual_keys:
            _create_outer_transient_instance(runtime, key, pitch=0.01)
        runtime.update("materialize-cross-program-peak")
        peak = runtime.snapshot()
        callbacks_at_peak = list(runtime.callbacks[measured_callback_mark:])
        played_at_peak = [
            item["source"] for item in callbacks_at_peak if item["kind"] == "played"
        ]
        stopped_at_peak = [
            item["source"] for item in callbacks_at_peak if item["kind"] == "stopped"
        ]
        transitions: list[dict[str, Any]] = []
        promoted_cohorts: list[str] = []
        previous = peak
        maximum_tail_updates = 1400
        # Free the 234 ordinary real slots first.  The two virtual cohorts and
        # the 22 real mid voices stay slow, making virtual-to-real promotion
        # observable instead of allowing virtual one-shots to finish first.
        accelerated_real_keys = sorted(
            {
                str(item["instanceKey"])
                for item in peak["voices"]
                if not item["isVirtual"]
                and item["source"] == "lowTransient"
                and str(item.get("instanceKey") or "").startswith("oldestLow")
            }
        )
        for key in accelerated_real_keys:
            runtime.set_pitch(key, 4.0)
        runtime.flush("accelerate-ordinary-real-set-for-promotion-tail")
        for tail_index in range(maximum_tail_updates):
            runtime.update(f"cross-program-tail-{tail_index + 1}")
            current = runtime.snapshot()
            transition = _compact_transition(runtime, previous, current)
            if transition is not None:
                transitions.append(transition)
                for item in transition["promoted"]:
                    instance_key = str(item.get("instanceKey") or "")
                    if instance_key.startswith("oldestLow"):
                        promoted_cohorts.append("olderDisplacedLow")
                    elif instance_key.startswith("newestLow"):
                        promoted_cohorts.append("newestLow")
                    else:
                        promoted_cohorts.append("unexpected")
            previous = current
            if current["summary"]["virtual"] == 0:
                break
        else:
            raise AssertionError("cross-program virtual voices did not drain in bound")

        peak_summary = peak["summary"]
        real_sources = [
            item["source"] for item in peak["voices"] if not item["isVirtual"]
        ]
        virtual_sources = [
            item["source"] for item in peak["voices"] if item["isVirtual"]
        ]
        virtual_instance_cohorts = [
            (
                "olderDisplacedLow"
                if str(item.get("instanceKey") or "").startswith("oldestLow")
                else "newestLow"
                if str(item.get("instanceKey") or "").startswith("newestLow")
                else "unexpected"
            )
            for item in peak["voices"]
            if item["isVirtual"]
        ]
        expected_spawn_runs = [
            {"source": "lowTransient", "count": 256},
            {"source": "midTransient", "count": 22},
            {"source": "lowTransient", "count": 23},
        ]
        expected_virtual_counts = {"lowTransient": 23, "midTransient": 22}
        older_real_newer_virtual = (
            peak_summary["logical"] == 301
            and peak_summary["real"] == 256
            and _source_counts(real_sources) == {"lowTransient": 256}
            and _source_counts(virtual_sources) == expected_virtual_counts
        )
        low_summary = peak_summary["bySource"]["lowTransient"]
        virtual_near_onset = (
            low_summary["virtualPcmPositionMaximum"] is not None
            and low_summary["realPcmPositionMinimum"] is not None
            and low_summary["virtualPcmPositionMaximum"]
            < low_summary["realPcmPositionMinimum"]
        )
        audibility_overrides_age = (
            peak_summary["bySource"]["midTransient"]["real"] == 22
            and peak_summary["bySource"]["midTransient"]["virtual"] == 0
            and peak_summary["bySource"]["lowTransient"]["virtual"] == 45
            and peak_summary["bySource"]["midTransient"]["audibilityMinimum"]
            > peak_summary["bySource"]["lowTransient"]["audibilityMaximum"]
            and set(peak_summary["bySource"]["midTransient"]["priorityCounts"])
            == set(peak_summary["bySource"]["lowTransient"]["priorityCounts"])
        )
        promotion_transitions = [
            item for item in transitions if item["promoted"]
        ]
        first_free_transition = next(
            (
                item
                for item in transitions
                if item["summaryAfter"]["real"] < SOFTWARE_CHANNELS
                and item["summaryAfter"]["virtual"] > 0
            ),
            None,
        )
        first_promotion_transition = (
            promotion_transitions[0] if promotion_transitions else None
        )
        promotion_delay_updates = (
            int(first_promotion_transition["dspUpdate"])
            - int(first_free_transition["dspUpdate"])
            if first_free_transition is not None
            and first_promotion_transition is not None
            else None
        )
        both_cohorts_same_boundary = (
            len(promotion_transitions) == 1
            and _source_counts(promoted_cohorts)
            == {"newestLow": 23, "olderDisplacedLow": 22}
        )
        return {
            "id": "cross-program-source-fifo",
            "configuration": {
                "systemInitializeMaxChannels": LOGICAL_CHANNELS,
                "softwareChannels": SOFTWARE_CHANNELS,
                "eventVolume": 1.0,
                "accumulationPitchMultiplier": 0.01,
                "ordinaryRealPromotionTailPitchMultiplier": 4.0,
                "virtualAndMidPromotionTailPitchMultiplier": 0.01,
                "acceleratedRealInstanceCount": len(accelerated_real_keys),
                "channelPriorityOverride": None,
            },
            "spawnPlan": expected_spawn_runs,
            "observedPlayedCallbacksAtPeak": run_length_encode(played_at_peak),
            "observedStoppedCallbacksAtPeak": run_length_encode(stopped_at_peak),
            "peak": peak,
            "tailUpdatesRendered": previous["dspUpdate"] - peak["dspUpdate"],
            "stateChangeCount": len(transitions),
            "stateChanges": transitions,
            "peakVirtualCohortCounts": _source_counts(virtual_instance_cohorts),
            "promotionHandleEnumerationAtBoundary": run_length_encode(
                promoted_cohorts
            ),
            "observations": {
                "all301SchedulesSurvivedToPeak": (
                    len(played_at_peak) == 301 and not stopped_at_peak
                ),
                "oldest256RealNewest45VirtualBySourceBlocks": older_real_newer_virtual,
                "newerMoreAudibleMidProgramDisplacedOlderLowVoices": (
                    audibility_overrides_age
                ),
                "virtualLowPositionsBelowEveryRealLowPositionAtPeak": (
                    virtual_near_onset
                ),
                "bothVirtualCohortsPromotedAtSameDspUpdateBoundary": (
                    both_cohorts_same_boundary
                ),
                "firstObservedFreeRealSlotDspUpdate": (
                    first_free_transition["dspUpdate"]
                    if first_free_transition is not None
                    else None
                ),
                "firstPromotionDspUpdate": (
                    first_promotion_transition["dspUpdate"]
                    if first_promotion_transition is not None
                    else None
                ),
                "promotionDelayDspUpdatesAfterFirstObservedFreeSlot": (
                    promotion_delay_updates
                ),
                "individualPromotionOrderWithinOneDspBoundaryObservable": False,
                "promotionObservedOnlyAtDspUpdateBoundaries": True,
            },
            "outputWav": str(output_wav),
        }


STATIC_SCENARIOS: tuple[dict[str, Any], ...] = (
    {
        "id": "static-default-virtualization",
        "maxChannels": LOGICAL_CHANNELS,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": None,
        "followFixed": True,
    },
    {
        "id": "static-quiet-virtualization",
        "maxChannels": LOGICAL_CHANNELS,
        "loopVolume": 0.001,
        "transientVolume": 1.0,
        "fixedVolume": 0.001,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": None,
        "followFixed": True,
    },
    {
        "id": "transient-quiet-virtualization",
        "maxChannels": LOGICAL_CHANNELS,
        "loopVolume": 1.0,
        "transientVolume": 0.001,
        "fixedVolume": 512.0,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": None,
        "followFixed": True,
    },
    {
        "id": "static-high-priority-virtualization",
        "maxChannels": LOGICAL_CHANNELS,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": 0,
        "transientPriority": None,
        "fixedPriority": 0,
        "followFixed": True,
    },
    {
        "id": "static-low-priority-virtualization",
        "maxChannels": LOGICAL_CHANNELS,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": 255,
        "transientPriority": None,
        "fixedPriority": 255,
        "followFixed": True,
    },
    {
        "id": "logical-cap-default-steal",
        "maxChannels": 257,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": None,
        "followFixed": False,
    },
    {
        "id": "logical-cap-fixed-high-priority-steal",
        "maxChannels": 257,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": 0,
        "followFixed": False,
    },
    {
        "id": "logical-cap-fixed-low-priority-steal",
        "maxChannels": 257,
        "loopVolume": 1.0,
        "transientVolume": 1.0,
        "fixedVolume": 1.0,
        "loopPriority": None,
        "transientPriority": None,
        "fixedPriority": 255,
        "followFixed": False,
    },
)


def _run_static_case(
    assetto_root: Path,
    isolated_bank: Path,
    runtime_name_to_source: dict[str, str],
    output_wav: Path,
    scenario: dict[str, Any],
) -> dict[str, Any]:
    with _OracleRuntime(
        assetto_root,
        isolated_bank,
        runtime_name_to_source,
        output_wav,
        max_channels=int(scenario["maxChannels"]),
    ) as runtime:
        runtime.create_instance(
            "continuousLoop",
            ENGINE_EVENT,
            parameters={"rpms": 1200.0, "throttle": 0.0},
            volume=float(scenario["loopVolume"]),
            priority=scenario["loopPriority"],
        )
        _start_and_update(runtime, "continuousLoop", "start-continuous-loop")
        loop_start = runtime.snapshot()
        transient_keys = [f"engineTransient{index:03d}" for index in range(256)]
        for key in transient_keys:
            _create_outer_transient_instance(
                runtime,
                key,
                pitch=0.01,
                volume=float(scenario["transientVolume"]),
                priority=scenario["transientPriority"],
            )
        runtime.update("materialize-static-transient-pressure")
        before_fixed = runtime.snapshot()
        callback_mark = len(runtime.callbacks)
        runtime.create_instance(
            "fixedOneShot",
            GEAR_EVENT,
            parameters={"state": 1.0},
            volume=float(scenario["fixedVolume"]),
            priority=scenario["fixedPriority"],
        )
        _start_and_update(runtime, "fixedOneShot", "admit-fixed-one-shot")
        after_fixed = runtime.snapshot()
        admission_callbacks = runtime.callbacks[callback_mark:]

        transitions: list[dict[str, Any]] = []
        fixed_promotions: list[dict[str, Any]] = []
        previous = after_fixed
        fixed_at_admission = [
            item
            for item in after_fixed["voices"]
            if item["source"] == "fixedGearUp"
        ]
        fixed_initially_virtual = bool(
            fixed_at_admission and fixed_at_admission[0]["isVirtual"]
        )
        maximum_tail_updates = 1100 if scenario["followFixed"] else 2
        for key in transient_keys:
            runtime.set_pitch(key, 1.0)
        runtime.flush("restore-transient-authored-rate-for-static-tail")
        for tail_index in range(maximum_tail_updates):
            runtime.update(f"static-tail-{tail_index + 1}")
            current = runtime.snapshot()
            transition = _compact_transition(runtime, previous, current)
            if transition is not None:
                transitions.append(transition)
                fixed_promotions.extend(
                    item
                    for item in transition["promoted"]
                    if item["source"] == "fixedGearUp"
                )
            previous = current
            fixed_now = [
                item for item in current["voices"] if item["source"] == "fixedGearUp"
            ]
            if scenario["followFixed"] and not fixed_now:
                break

        stopped_on_admission = [
            item
            for item in admission_callbacks
            if item["kind"] == "stopped"
        ]
        appeared_after_fixed = diff_voice_snapshots(
            before_fixed["voices"], after_fixed["voices"]
        )
        return {
            "id": scenario["id"],
            "configuration": {
                key: value
                for key, value in scenario.items()
                if key not in {"id", "followFixed"}
            }
            | {
                "transientAccumulationPitchMultiplier": 0.01,
                "transientTailPitchMultiplier": 1.0,
            },
            "loopStart": loop_start,
            "beforeFixedAdmission": before_fixed,
            "afterFixedAdmission": after_fixed,
            "admissionCallbacks": admission_callbacks,
            "admissionVoiceChanges": appeared_after_fixed,
            "tailUpdatesRendered": previous["dspUpdate"] - after_fixed["dspUpdate"],
            "stateChangeCount": len(transitions),
            "stateChanges": transitions,
            "observations": {
                "loopWasOnlyVoiceAtStart": (
                    loop_start["summary"]["logical"] == 1
                    and loop_start["summary"]["bySource"].get(
                        "idleLoop", {}
                    ).get("logical")
                    == 1
                ),
                "softwareBudgetReachedBeforeFixed": (
                    before_fixed["summary"]["logical"] == 257
                    and before_fixed["summary"]["real"] == SOFTWARE_CHANNELS
                ),
                "fixedScheduled": any(
                    item["kind"] == "played"
                    and item["source"] == "fixedGearUp"
                    for item in admission_callbacks
                ),
                "fixedPresentAfterAdmission": bool(fixed_at_admission),
                "fixedInitiallyVirtual": fixed_initially_virtual,
                "fixedInitiallyReal": bool(
                    fixed_at_admission and not fixed_at_admission[0]["isVirtual"]
                ),
                "fixedPromotedAtUpdateBoundary": bool(fixed_promotions),
                "fixedPromotion": fixed_promotions[0] if fixed_promotions else None,
                "soundStoppedCallbackOnAdmission": bool(stopped_on_admission),
                "stoppedSourcesOnAdmission": run_length_encode(
                    item["source"] for item in stopped_on_admission
                ),
                "logicalCapWasActive": int(scenario["maxChannels"]) < LOGICAL_CHANNELS,
            },
            "outputWav": str(output_wav),
        }


def _case_voice_summary(case: dict[str, Any], key: str) -> dict[str, Any]:
    return case.get(key, {}).get("summary", {})


def _interpret(cases: list[dict[str, Any]]) -> dict[str, Any]:
    by_id = {case["id"]: case for case in cases}
    cross = by_id["cross-program-source-fifo"]
    default = by_id["static-default-virtualization"]
    quiet_static = by_id["static-quiet-virtualization"]
    quiet_transient = by_id["transient-quiet-virtualization"]
    high_priority = by_id["static-high-priority-virtualization"]
    low_priority = by_id["static-low-priority-virtualization"]

    def real_count(case: dict[str, Any], source: str) -> int:
        return int(
            _case_voice_summary(case, "afterFixedAdmission")
            .get("bySource", {})
            .get(source, {})
            .get("real", 0)
        )

    priority_changes_selection = (
        real_count(high_priority, "idleLoop")
        != real_count(low_priority, "idleLoop")
        or real_count(high_priority, "fixedGearUp")
        != real_count(low_priority, "fixedGearUp")
    )
    audibility_changes_selection = (
        real_count(quiet_static, "idleLoop")
        != real_count(quiet_transient, "idleLoop")
        or real_count(quiet_static, "fixedGearUp")
        != real_count(quiet_transient, "fixedGearUp")
    )
    logical_cases = [
        case for case in cases if case["observations"].get("logicalCapWasActive")
    ]
    logical_stop_observed = any(
        case["observations"]["soundStoppedCallbackOnAdmission"]
        for case in logical_cases
    )
    default_after = _case_voice_summary(default, "afterFixedAdmission")["bySource"]
    quiet_transient_after = _case_voice_summary(
        quiet_transient, "afterFixedAdmission"
    )["bySource"]
    same_priority_audibility_selection = (
        set(default_after["idleLoop"]["priorityCounts"])
        == set(default_after["lowTransient"]["priorityCounts"])
        and default_after["idleLoop"]["audibilityMaximum"]
        < default_after["lowTransient"]["audibilityMinimum"]
        and default_after["idleLoop"]["virtual"] == 1
        and default_after["lowTransient"]["virtual"] == 0
    )
    priority_precedes_audibility = (
        min(map(int, quiet_transient_after["fixedGearUp"]["priorityCounts"]))
        > min(map(int, quiet_transient_after["lowTransient"]["priorityCounts"]))
        and quiet_transient_after["fixedGearUp"]["audibilityMinimum"]
        > quiet_transient_after["lowTransient"]["audibilityMaximum"]
        and quiet_transient_after["fixedGearUp"]["virtual"] == 1
        and quiet_transient_after["lowTransient"]["real"] == 255
    )
    high_priority_fixed_promoted = high_priority["observations"][
        "fixedPromotedAtUpdateBoundary"
    ]
    logical_stopped_sources = {
        tuple(
            (item["source"], int(item["count"]))
            for item in case["observations"]["stoppedSourcesOnAdmission"]
        )
        for case in logical_cases
    }
    proven: list[str] = []
    if cross["observations"]["oldest256RealNewest45VirtualBySourceBlocks"]:
        proven.append(
            "At equal default priority in this official multi-program event, the "
            "oldest 256 of 301 scheduled transient voices were real and the newest "
            "45 were virtual across two distinct source/program blocks."
        )
    if cross["observations"][
        "newerMoreAudibleMidProgramDisplacedOlderLowVoices"
    ]:
        proven.append(
            "At equal channel priority, the newer and more audible mid-program "
            "voices were all real while 45 low-program voices were virtual; global "
            "selection is therefore not an age-only cross-program FIFO."
        )
    if cross["observations"][
        "virtualLowPositionsBelowEveryRealLowPositionAtPeak"
    ]:
        proven.append(
            "Within the low-program source, every virtual voice remained nearer "
            "onset than every real voice at the peak DSP-update snapshot."
        )
    if cross["observations"][
        "bothVirtualCohortsPromotedAtSameDspUpdateBoundary"
    ]:
        proven.append(
            "Both virtual event-instance cohorts became real at the same observed "
            "256-frame DSP-update boundary; their individual within-boundary order "
            "is not observable."
        )
    promotion_delay = cross["observations"].get(
        "promotionDelayDspUpdatesAfterFirstObservedFreeSlot"
    )
    if isinstance(promotion_delay, int):
        proven.append(
            "The first virtual-to-real promotion was observed "
            f"{promotion_delay} DSP updates after the first snapshot with free real "
            "slots in this bounded completion case."
        )
    if priority_changes_selection:
        proven.append(
            "Explicit FMOD channel-priority changes altered real/virtual selection "
            "between persistent/fixed voices and engine transients."
        )
    if audibility_changes_selection:
        proven.append(
            "Event-volume/audibility changes altered real/virtual selection between "
            "persistent/fixed voices and engine transients."
        )
    if same_priority_audibility_selection:
        proven.append(
            "With both at reported priority 64, the quieter idle loop was virtual "
            "while all louder engine transients were real, supporting audibility "
            "selection inside an equal-priority class."
        )
    if priority_precedes_audibility:
        proven.append(
            "A louder gear one-shot at priority 128 remained virtual while quieter "
            "priority-64 transients remained real, supporting priority before "
            "audibility across these classes."
        )
    if high_priority_fixed_promoted:
        proven.append(
            "A priority-0 fixed one-shot was initially virtual with temporarily "
            "unused real capacity, then became real at the next observed DSP update."
        )
    if logical_stop_observed:
        proven.append(
            "At the configured logical-channel cap, fixed-one-shot admission caused "
            "a SOUND_STOPPED callback; this is distinct from software-budget "
            "virtualization."
        )
    if logical_stopped_sources == {(('idleLoop', 1),)}:
        proven.append(
            "At the 257-logical-voice cap, the existing idle loop was stopped on "
            "new fixed-voice admission in all three tested fixed-priority cases."
        )
    return {
        "proven": proven,
        "observedBooleans": {
            "crossProgramOlderRealNewerVirtual": cross["observations"][
                "oldest256RealNewest45VirtualBySourceBlocks"
            ],
            "crossProgramAudibilityOverridesAge": cross["observations"][
                "newerMoreAudibleMidProgramDisplacedOlderLowVoices"
            ],
            "bothVirtualCohortsPromotedAtSameBoundary": cross["observations"][
                "bothVirtualCohortsPromotedAtSameDspUpdateBoundary"
            ],
            "observedPromotionDelayDspUpdates": promotion_delay,
            "priorityChangesSelection": priority_changes_selection,
            "audibilityChangesSelection": audibility_changes_selection,
            "samePriorityAudibilitySelection": same_priority_audibility_selection,
            "priorityPrecedesAudibilityInTestedClasses": priority_precedes_audibility,
            "highPriorityFixedPromotedAfterInitialVirtualState": (
                high_priority_fixed_promoted
            ),
            "logicalCapStopObserved": logical_stop_observed,
            "logicalCapStoppedSourceInvariantAcrossCases": (
                logical_stopped_sources == {(('idleLoop', 1),)}
            ),
            "defaultFixedInitiallyVirtual": default["observations"][
                "fixedInitiallyVirtual"
            ],
        },
        "notProven": [
            "Within-one-256-frame-buffer completion or promotion timing; snapshots "
            "exist only after each FMOD Studio update.",
            "A universal FIFO rule for arbitrary FMOD priorities, audibilities, DSP "
            "graphs, spatial positions, or every official car family.",
            "Exact individual spawn ordinal for same-source voices; source-block "
            "order and stable concurrent channel handles are proven instead.",
            "That a virtual voice is frozen at exactly PCM frame zero; only its "
            "observed bounded near-onset positions are reported.",
            "Whether age or audibility selected the idle loop as the logical-cap "
            "stop target; the fixture makes it both the oldest and quieter voice.",
            "The exact comparator for equal numeric priority and exactly equal "
            "audibility across different sources.",
            "Android mixer equivalence; this is an FMOD 1.08.12 reference-oracle "
            "measurement used to specify the Android implementation.",
        ],
    }


def _summary_markdown(report: dict[str, Any]) -> str:
    interpretation = report["interpretation"]
    lines = [
        "# FMOD 1.08 global voice arbitration oracle",
        "",
        "This run used only WAVWRITER_NRT; no playback device was opened.",
        "Short official transient sources were accumulated in separate event "
        "instances at a documented 0.01 event-pitch multiplier while retaining "
        "the 256-frame DSP quantum. Selected real instances were then accelerated "
        "to make promotion observable; every case records these controls.",
        "",
        "## Proven by this run",
        "",
    ]
    lines.extend(
        f"- {item}" for item in interpretation["proven"]
    )
    lines.extend(["", "## Deliberately not claimed", ""])
    lines.extend(f"- {item}" for item in interpretation["notProven"])
    lines.extend(
        [
            "",
            "## Reproduction",
            "",
            "```powershell",
            (
                "python tools/probe_fmod_global_voice_arbitration.py "
                "--assetto-root \"D:\\Program Files (x86)\\Steam\\steamapps\\common\\assettocorsa\""
            ),
            "```",
            "",
        ]
    )
    return "\n".join(lines)


def probe_catalog(
    assetto_root: Path,
    graph_root: Path,
    capture_plan_path: Path,
    output_root: Path,
    *,
    scenario_ids: set[str] | None = None,
) -> dict[str, Any]:
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    capture_plan_path = capture_plan_path.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    fixture = _fixture(root, graph_root, capture_plan_path, output_root)
    runtime_name_to_source = fixture.pop("_runtimeNameToSource")
    isolated_bank = Path(fixture["isolatedBankPath"])

    cases: list[dict[str, Any]] = []
    if scenario_ids is None or "cross-program-source-fifo" in scenario_ids:
        cases.append(
            _run_cross_program_case(
                root,
                isolated_bank,
                runtime_name_to_source,
                output_root / "cross-program-source-fifo.wav",
            )
        )
    selected_static = [
        item
        for item in STATIC_SCENARIOS
        if scenario_ids is None or item["id"] in scenario_ids
    ]
    for scenario in selected_static:
        cases.append(
            _run_static_case(
                root,
                isolated_bank,
                runtime_name_to_source,
                output_root / f"{scenario['id']}.wav",
                scenario,
            )
        )
    expected_ids = {"cross-program-source-fifo", *[item["id"] for item in STATIC_SCENARIOS]}
    if scenario_ids is not None:
        unknown = scenario_ids - expected_ids
        if unknown:
            raise ValueError("unknown scenario IDs: " + ", ".join(sorted(unknown)))
    if {case["id"] for case in cases} != expected_ids:
        # Partial runs are useful while developing the oracle, but a proof
        # report must not silently imply catalog-wide interpretation.
        interpretation = {
            "proven": [],
            "observedBooleans": {},
            "notProven": [
                "This is a deliberately partial scenario run; execute all scenarios "
                "before using it as release evidence."
            ],
        }
        result = "PARTIAL_NOT_RELEASE_EVIDENCE"
    else:
        interpretation = _interpret(cases)
        result = "PASS_WITH_BOUNDED_CLAIMS"
    report = {
        "schema": SCHEMA,
        "basis": {
            "runtime": "Assetto Corsa FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "sampleRateHz": 48000,
            "channels": 2,
            "dspBufferFrames": DSP_BUFFER_FRAMES,
            "softwareRealVoiceBudget": SOFTWARE_CHANNELS,
            "normalLogicalVoiceLimit": LOGICAL_CHANNELS,
            "targetIsolation": (
                "verifiedCopyOnlyWaveformAndMultiInstrumentTriggerChanceZero"
            ),
            "installedBankModified": False,
            "sampleNamesUsedForSemantics": False,
            "sampleNamesUsedOnlyForRuntimeIdentityJoin": True,
            "observationResolution": "one snapshot after each 256-frame DSP update",
            "shortOneShotAccumulationControl": (
                "separateEventInstancesAtEventPitchMultiplier0.01"
            ),
            "promotionCompletionControl": (
                "selectedRealInstancesAtPitch4.0WhileVirtualCohortsRemain0.01"
            ),
            "runtimePitchControlsClaimedAsAuthoredBehavior": False,
        },
        "fixture": fixture,
        "cases": cases,
        "interpretation": interpretation,
        "result": result,
    }
    return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument("--capture-plan", type=Path, default=DEFAULT_CAPTURE_PLAN)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument(
        "--scenario",
        action="append",
        dest="scenarios",
        help="run only this case ID (repeatable; partial output is not release evidence)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    report = probe_catalog(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.capture_plan,
        args.output_root,
        scenario_ids=set(args.scenarios) if args.scenarios else None,
    )
    output_root = args.output_root.resolve()
    proof_path = output_root / "proof.json"
    proof_path.write_bytes(canonical_json_bytes(report) + b"\n")
    (output_root / "README.md").write_text(
        _summary_markdown(report), encoding="utf-8", newline="\n"
    )
    print(
        json.dumps(
            {
                "result": report["result"],
                "proof": str(proof_path),
                "caseCount": len(report["cases"]),
                "provenClaimCount": len(report["interpretation"]["proven"]),
                "audioDeviceOpened": False,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
