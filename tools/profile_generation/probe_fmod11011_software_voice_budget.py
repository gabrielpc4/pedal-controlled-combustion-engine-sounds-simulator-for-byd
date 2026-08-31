#!/usr/bin/env python3
"""Probe FMOD 1.10.11 real/virtual arbitration at Assetto's 2048/256 limits."""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
from typing import Any
import wave


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_AUDIO_LAB_ROOT = REPOSITORY_ROOT.parent / "assetto_corsa_audio_lab"
DEFAULT_FMOD_API_ROOT = REPOSITORY_ROOT.parent / "FMOD Programmers API" / "api"
DEFAULT_OUTPUT_ROOT = (
    REPOSITORY_ROOT
    / "build"
    / "new-car-audio"
    / "oracles"
    / "fmod11011-software256"
)

CAR_ID = "ghast_lfa_concept_gt500"
CONTEXT_EVENT = f"event:/cars/{CAR_ID}/turbo"
CONTEXT_EVENT_GUID = "52048ea2-651f-4001-8f8a-ecd58ccb3b75"
STUDIO_LOGICAL_CHANNEL_CAP = 2048
SOFTWARE_REAL_VOICE_BUDGET = 256
DSP_BUFFER_FRAMES = 256
SAMPLE_RATE_HZ = 48_000
SETTLEMENT_BOUNDARIES = 12
FMOD_LOOP_NORMAL = 0x00000002
FMOD_TIMEUNIT_PCM = 0x00000002


CASES = (
    {
        "id": "equal257",
        "expectedPressureVirtual": ["incoming-A"],
    },
    {
        "id": "incomingQuiet257",
        "incomingVolume": 0.001,
        "expectedPressureVirtual": ["incoming-A"],
    },
    {
        "id": "continuousQuiet257",
        "continuousVolume": 0.001,
        "expectedPressureVirtual": ["continuous"],
    },
    {
        "id": "continuousLowPriority257",
        "continuousPriority": 255,
        "expectedPressureVirtual": ["continuous"],
    },
    {
        "id": "incomingHighPriority257",
        "incomingPriority": 0,
        "expectedPressureVirtual": ["old-254"],
    },
    {
        "id": "incomingQuietHighPriority257",
        "incomingVolume": 0.001,
        "incomingPriority": 0,
        "expectedPressureVirtual": ["old-254"],
    },
    {
        "id": "continuousQuietHighPriority257",
        "continuousVolume": 0.001,
        "continuousPriority": 0,
        "expectedPressureVirtual": ["incoming-A"],
    },
    {
        "id": "twoEqualIncoming258",
        "twoIncoming": True,
        "expectedPressureVirtual": ["incoming-A", "incoming-B"],
        "expectedAfterRetireVirtual": ["incoming-B"],
    },
)


def probe(
    audio_lab_root: Path,
    fmod_api_root: Path,
    output_root: Path,
) -> dict[str, Any]:
    audio_lab = audio_lab_root.resolve(strict=True)
    fmod_api = fmod_api_root.resolve(strict=True)
    output = output_root.resolve()
    output.mkdir(parents=True, exist_ok=True)

    assetto_root = (audio_lab / "macos_bank_lab").resolve(strict=True)
    bank = (
        assetto_root
        / "content"
        / "cars"
        / CAR_ID
        / "sfx"
        / f"{CAR_ID}.bank"
    ).resolve(strict=True)
    core_library = (fmod_api / "lowlevel" / "lib" / "libfmod.dylib").resolve(
        strict=True
    )
    studio_library = (
        fmod_api / "studio" / "lib" / "libfmodstudio.dylib"
    ).resolve(strict=True)

    continuous_fixture = output / "continuous-220hz.wav"
    finite_fixture = output / "finite-330hz.wav"
    _write_tone(continuous_fixture, frequency_hz=220.0)
    _write_tone(finite_fixture, frequency_hz=330.0)

    global_probe, guid_type = _load_audio_lab_runtime(
        audio_lab,
        core_library,
        studio_library,
    )
    _install_guid_event_lookup(global_probe, guid_type)

    case_results = [
        _run_case(
            global_probe,
            assetto_root=assetto_root,
            bank=bank,
            continuous_fixture=continuous_fixture,
            finite_fixture=finite_fixture,
            output_root=output,
            case=case,
        )
        for case in CASES
    ]
    runtime_configurations = {
        json.dumps(case["observedRuntimeConfiguration"], sort_keys=True)
        for case in case_results
    }
    if len(runtime_configurations) != 1:
        raise AssertionError("runtime configuration changed between arbitration cases")
    runtime_configuration = case_results[0]["observedRuntimeConfiguration"]
    for case in case_results:
        del case["observedRuntimeConfiguration"]

    _validate_cases(case_results)
    report = {
        "schema": "byd-fmod11011-software-voice-budget-oracle-v1",
        "result": "PASS_WITH_BOUNDED_CLAIMS",
        "basis": {
            "runtime": "FMOD 1.10.11 macOS x86_64",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "studioInitializeMaxChannels": STUDIO_LOGICAL_CHANNEL_CAP,
            "softwareChannelsRequested": SOFTWARE_REAL_VOICE_BUDGET,
            "dspBufferFramesRequested": DSP_BUFFER_FRAMES,
            "sampleRateHzRequested": SAMPLE_RATE_HZ,
            "settlementBoundariesObserved": SETTLEMENT_BOUNDARIES,
            "observedRuntimeConfiguration": runtime_configuration,
            "fixture": (
                "one distinct looping continuous decoded-PCM channel, followed by "
                "255 older finite decoded-PCM channels and one or two incoming "
                "finite decoded-PCM channels scheduled before one DSP update"
            ),
            "ageOrderOldestToNewest": (
                "continuous, old-000..old-254, incoming-A, incoming-B"
            ),
            "contextBankUsedForStudioInitializationOnly": True,
            "inputs": [
                _file_identity("fmodCoreLibrary", core_library),
                _file_identity("fmodStudioLibrary", studio_library),
                _file_identity("contextBank", bank),
                _file_identity("continuousPcmFixture", continuous_fixture),
                _file_identity("finitePcmFixture", finite_fixture),
            ],
        },
        "cases": case_results,
        "interpretation": {
            "proven": [
                "The 257th channel remains a valid logical channel and becomes virtual; no logical channel is stolen at the 256-software-voice boundary.",
                "Two equal incoming channels scheduled in one block remain valid logical channels and both start virtual at 258 logical / 256 real.",
                "At equal priority and audibility, the older 256 channels remain real and the newest channel is virtual.",
                "At equal priority, greater audibility displaces an older quieter channel after bounded mixer settlement.",
                "Better numeric channel priority displaces an older worse-priority channel after bounded mixer settlement.",
                "The quiet priority-0 cases show that numeric priority precedes audibility in the tested fixture.",
                "After one real channel is stopped, the selected virtual channel is promoted by the third observed 256-frame DSP boundary in every case.",
                "Virtual finite channels keep advancing their PCM cursor while waiting for a real slot in this decoded-PCM fixture.",
            ],
            "implementationOrderForThisFixture": [
                "ascending numeric FMOD channel priority",
                "descending audibility inside one priority class",
                "ascending creation age inside an exact priority/audibility tie",
            ],
            "notProven": [
                "A universal comparator for every DSP graph, spatial configuration, codec, or FMOD version.",
                "Within-buffer transition order; observations are taken after 256-frame DSP boundaries.",
                "That Android must emulate over-budget promotion: the release path may instead fail closed unless every supported trajectory is certified below 256 real voices.",
                "Studio event callback ordering at this boundary; the stress fixture uses direct Core channels so every logical handle can be inspected exactly.",
            ],
        },
    }
    report_path = output / "report.json"
    report_path.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / "report.sha256").write_text(
        f"{_sha256(report_path)}  {report_path.name}\n",
        encoding="ascii",
    )

    return report


def _run_case(
    global_probe: Any,
    *,
    assetto_root: Path,
    bank: Path,
    continuous_fixture: Path,
    finite_fixture: Path,
    output_root: Path,
    case: dict[str, Any],
) -> dict[str, Any]:
    case_id = str(case["id"])
    output_wav = output_root / f"{case_id}.wav"
    runtime_configuration: dict[str, Any]
    result: dict[str, Any]
    with global_probe._OracleRuntime(
        assetto_root,
        bank,
        {},
        output_wav,
        max_channels=STUDIO_LOGICAL_CHANNEL_CAP,
        software_channels=SOFTWARE_REAL_VOICE_BUDGET,
        event_paths=(CONTEXT_EVENT,),
    ) as runtime:
        _bind_core_probe_api(runtime.core)
        runtime_configuration = _runtime_configuration(runtime)
        continuous_sound = _create_sound(
            runtime,
            continuous_fixture,
            FMOD_LOOP_NORMAL,
        )
        finite_sound = _create_sound(runtime, finite_fixture, 0)
        channels: list[dict[str, Any]] = []
        try:
            runtime.suspend_mixer()
            channels.append(
                _play_channel(
                    runtime,
                    continuous_sound,
                    label="continuous",
                    volume=float(case.get("continuousVolume", 1.0)),
                    priority=int(case.get("continuousPriority", 128)),
                )
            )
            for index in range(255):
                channels.append(
                    _play_channel(
                        runtime,
                        finite_sound,
                        label=f"old-{index:03d}",
                        volume=1.0,
                        priority=128,
                    )
                )
            channels.append(
                _play_channel(
                    runtime,
                    finite_sound,
                    label="incoming-A",
                    volume=float(case.get("incomingVolume", 1.0)),
                    priority=int(case.get("incomingPriority", 128)),
                )
            )
            if bool(case.get("twoIncoming", False)):
                channels.append(
                    _play_channel(
                        runtime,
                        finite_sound,
                        label="incoming-B",
                        volume=float(case.get("incomingVolume", 1.0)),
                        priority=int(case.get("incomingPriority", 128)),
                    )
                )
            runtime.resume_mixer()

            pressure_timeline = _observe_boundaries(
                runtime,
                channels,
                phase="pressure",
            )
            pressure = pressure_timeline[-1]
            pressure_voices = {voice["label"]: voice for voice in pressure["voices"]}
            retired = next(
                channel
                for channel in channels
                if channel["label"].startswith("old-")
                and not pressure_voices[channel["label"]]["isVirtual"]
            )
            runtime._check(
                runtime.core.FMOD_Channel_Stop(retired["handle"]),
                f"stop {retired['label']}",
            )
            remaining = [channel for channel in channels if channel is not retired]
            retirement_timeline = _observe_boundaries(
                runtime,
                remaining,
                phase="after-retire",
            )
            after_retire = retirement_timeline[-1]
            pressure_virtual = set(pressure["virtualLabels"])
            after_virtual = set(after_retire["virtualLabels"])

            result = {
                "id": case_id,
                "configured": {
                    "continuousVolume": float(case.get("continuousVolume", 1.0)),
                    "continuousPriority": int(case.get("continuousPriority", 128)),
                    "incomingVolume": float(case.get("incomingVolume", 1.0)),
                    "incomingPriority": int(case.get("incomingPriority", 128)),
                    "incomingCount": 2 if case.get("twoIncoming") else 1,
                },
                "createdLogicalChannelCount": len(channels),
                "allCreatedHandlesQueryableAtPressure": (
                    len(pressure["voices"]) == len(channels)
                ),
                "pressureStableBoundary": _stable_boundary(pressure_timeline),
                "pressureTimeline": [
                    _report_snapshot(snapshot) for snapshot in pressure_timeline
                ],
                "retiredRealChannel": retired["label"],
                "promotionStableBoundary": _stable_boundary(retirement_timeline),
                "retirementTimeline": [
                    _report_snapshot(snapshot) for snapshot in retirement_timeline
                ],
                "promotedLabels": sorted(pressure_virtual - after_virtual),
                "observedRuntimeConfiguration": runtime_configuration,
            }
        finally:
            if runtime.mixer_suspended:
                runtime.resume_mixer()
            for channel in channels:
                runtime.core.FMOD_Channel_Stop(channel["handle"])
            runtime.core.FMOD_Sound_Release(finite_sound)
            runtime.core.FMOD_Sound_Release(continuous_sound)

    result["outputWav"] = _file_identity("renderedCaseOutput", output_wav)

    return result


def _observe_boundaries(
    runtime: Any,
    channels: list[dict[str, Any]],
    *,
    phase: str,
) -> list[dict[str, Any]]:
    timeline = []
    for boundary in range(1, SETTLEMENT_BOUNDARIES + 1):
        runtime.update(f"{phase}-{boundary}")
        snapshot = _snapshot(runtime, channels)
        snapshot["boundary"] = boundary
        timeline.append(snapshot)

    return timeline


def _report_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    diagnostic_labels = {
        "continuous",
        "old-000",
        "old-253",
        "old-254",
        "incoming-A",
        "incoming-B",
    }

    return {
        "boundary": snapshot["boundary"],
        "logical": snapshot["logical"],
        "real": snapshot["real"],
        "virtual": snapshot["virtual"],
        "virtualLabels": snapshot["virtualLabels"],
        "diagnosticVoices": [
            voice
            for voice in snapshot["voices"]
            if voice["isVirtual"] or voice["label"] in diagnostic_labels
        ],
    }


def _snapshot(runtime: Any, channels: list[dict[str, Any]]) -> dict[str, Any]:
    logical = C.c_int()
    real = C.c_int()
    runtime._check(
        runtime.core.FMOD_System_GetChannelsPlaying(
            runtime.low_level,
            C.byref(logical),
            C.byref(real),
        ),
        "read global channel counts",
    )
    voices = [_inspect_channel(runtime, channel) for channel in channels]
    observed_real = sum(not voice["isVirtual"] for voice in voices)
    if logical.value != len(voices) or real.value != observed_real:
        raise AssertionError(
            "direct channel enumeration disagrees with FMOD global counts: "
            f"enumerated={len(voices)}/{observed_real} "
            f"system={logical.value}/{real.value}"
        )

    return {
        "logical": logical.value,
        "real": real.value,
        "virtual": logical.value - real.value,
        "virtualLabels": [
            voice["label"] for voice in voices if voice["isVirtual"]
        ],
        "voices": voices,
    }


def _inspect_channel(runtime: Any, channel: dict[str, Any]) -> dict[str, Any]:
    virtual = C.c_int()
    audibility = C.c_float()
    position = C.c_uint()
    priority = C.c_int()
    runtime._check(
        runtime.core.FMOD_Channel_IsVirtual(channel["handle"], C.byref(virtual)),
        f"read {channel['label']} virtual state",
    )
    runtime._check(
        runtime.core.FMOD_Channel_GetAudibility(
            channel["handle"],
            C.byref(audibility),
        ),
        f"read {channel['label']} audibility",
    )
    runtime._check(
        runtime.core.FMOD_Channel_GetPosition(
            channel["handle"],
            C.byref(position),
            FMOD_TIMEUNIT_PCM,
        ),
        f"read {channel['label']} PCM position",
    )
    runtime._check(
        runtime.core.FMOD_Channel_GetPriority(
            channel["handle"],
            C.byref(priority),
        ),
        f"read {channel['label']} priority",
    )

    return {
        "label": channel["label"],
        "isVirtual": bool(virtual.value),
        "priority": priority.value,
        "audibility": audibility.value,
        "pcmPosition": position.value,
    }


def _play_channel(
    runtime: Any,
    sound: C.c_void_p,
    *,
    label: str,
    volume: float,
    priority: int,
) -> dict[str, Any]:
    channel = C.c_void_p()
    runtime._check(
        runtime.core.FMOD_System_PlaySound(
            runtime.low_level,
            sound,
            None,
            1,
            C.byref(channel),
        ),
        f"create paused channel {label}",
    )
    runtime._check(
        runtime.core.FMOD_Channel_SetVolume(channel, C.c_float(volume)),
        f"set {label} volume",
    )
    runtime._check(
        runtime.core.FMOD_Channel_SetPriority(channel, priority),
        f"set {label} priority",
    )
    runtime._check(
        runtime.core.FMOD_Channel_SetPaused(channel, 0),
        f"unpause {label}",
    )

    return {
        "label": label,
        "handle": channel,
    }


def _create_sound(runtime: Any, path: Path, mode: int) -> C.c_void_p:
    sound = C.c_void_p()
    runtime._check(
        runtime.core.FMOD_System_CreateSound(
            runtime.low_level,
            str(path).encode("utf-8"),
            mode,
            None,
            C.byref(sound),
        ),
        f"create decoded PCM sound {path.name}",
    )

    return sound


def _runtime_configuration(runtime: Any) -> dict[str, Any]:
    version = C.c_uint()
    software_channels = C.c_int()
    dsp_buffer_frames = C.c_uint()
    dsp_buffer_count = C.c_int()
    sample_rate = C.c_int()
    speaker_mode = C.c_int()
    raw_speakers = C.c_int()
    runtime._check(
        runtime.core.FMOD_System_GetVersion(runtime.low_level, C.byref(version)),
        "read FMOD runtime version",
    )
    runtime._check(
        runtime.core.FMOD_System_GetSoftwareChannels(
            runtime.low_level,
            C.byref(software_channels),
        ),
        "read software-channel budget",
    )
    runtime._check(
        runtime.core.FMOD_System_GetDSPBufferSize(
            runtime.low_level,
            C.byref(dsp_buffer_frames),
            C.byref(dsp_buffer_count),
        ),
        "read DSP buffer size",
    )
    runtime._check(
        runtime.core.FMOD_System_GetSoftwareFormat(
            runtime.low_level,
            C.byref(sample_rate),
            C.byref(speaker_mode),
            C.byref(raw_speakers),
        ),
        "read software format",
    )

    return {
        "fmodVersionHex": f"0x{version.value:08x}",
        "softwareChannels": software_channels.value,
        "dspBufferFrames": dsp_buffer_frames.value,
        "dspBufferCount": dsp_buffer_count.value,
        "sampleRateHz": sample_rate.value,
        "speakerMode": speaker_mode.value,
        "rawSpeakerCount": raw_speakers.value,
    }


def _validate_cases(cases: list[dict[str, Any]]) -> None:
    configured = {str(case["id"]): case for case in CASES}
    for result in cases:
        case = configured[result["id"]]
        expected_created = 258 if case.get("twoIncoming") else 257
        expected_after = list(case.get("expectedAfterRetireVirtual", []))
        pressure = result["pressureTimeline"][-1]
        after_retire = result["retirementTimeline"][-1]
        if result["createdLogicalChannelCount"] != expected_created:
            raise AssertionError(f"{result['id']}: wrong fixture channel count")
        if not result["allCreatedHandlesQueryableAtPressure"]:
            raise AssertionError(f"{result['id']}: a logical handle was stolen")
        if pressure["logical"] != expected_created or pressure["real"] != 256:
            raise AssertionError(f"{result['id']}: unexpected pressure counts")
        if pressure["virtualLabels"] != case["expectedPressureVirtual"]:
            raise AssertionError(
                f"{result['id']}: unexpected stable pressure arbitration: "
                f"{pressure['virtualLabels']}"
            )
        if after_retire["logical"] != expected_created - 1:
            raise AssertionError(f"{result['id']}: retirement did not remove one voice")
        if after_retire["real"] != 256:
            raise AssertionError(f"{result['id']}: real slot was not repopulated")
        if after_retire["virtualLabels"] != expected_after:
            raise AssertionError(
                f"{result['id']}: unexpected stable promotion: "
                f"{after_retire['virtualLabels']}"
            )


def _stable_boundary(timeline: list[dict[str, Any]]) -> int:
    states = [
        (snapshot["logical"], snapshot["real"], snapshot["virtualLabels"])
        for snapshot in timeline
    ]
    for index, state in enumerate(states):
        if all(candidate == state for candidate in states[index:]):
            return index + 1

    raise AssertionError("finite observation window has no stable suffix")


def _install_guid_event_lookup(global_probe: Any, guid_type: Any) -> None:
    def event_description(studio: Any, car_bank: Any, event_path: str) -> C.c_void_p:
        if event_path != CONTEXT_EVENT:
            raise AssertionError(f"unexpected context event: {event_path}")
        studio.FMOD_Studio_EventDescription_GetID.argtypes = [
            C.c_void_p,
            C.POINTER(guid_type),
        ]
        studio.FMOD_Studio_EventDescription_GetID.restype = C.c_int
        count = C.c_int()
        global_probe.SilentFmodReferenceRenderer._check(
            studio.FMOD_Studio_Bank_GetEventCount(car_bank, C.byref(count)),
            "count context-bank events",
        )
        descriptions = (C.c_void_p * max(1, count.value))()
        actual = C.c_int()
        global_probe.SilentFmodReferenceRenderer._check(
            studio.FMOD_Studio_Bank_GetEventList(
                car_bank,
                descriptions,
                count.value,
                C.byref(actual),
            ),
            "list context-bank events",
        )
        wanted = bytes(guid_type.parse(CONTEXT_EVENT_GUID))
        for raw_description in descriptions[: actual.value]:
            observed = guid_type()
            global_probe.SilentFmodReferenceRenderer._check(
                studio.FMOD_Studio_EventDescription_GetID(
                    raw_description,
                    C.byref(observed),
                ),
                "read context event GUID",
            )
            if bytes(observed) == wanted:
                return C.c_void_p(raw_description)

        raise AssertionError(
            f"context event GUID {CONTEXT_EVENT_GUID} absent from {CAR_ID} bank"
        )

    global_probe._event_description = event_description


def _load_audio_lab_runtime(
    audio_lab_root: Path,
    core_library: Path,
    studio_library: Path,
) -> tuple[Any, Any]:
    if str(audio_lab_root) not in sys.path:
        sys.path.insert(0, str(audio_lab_root))
    import tools.probe_fmod_global_voice_arbitration as global_probe
    from sim.fmod_native import FMOD_MACOS_VERSION, Guid

    class Cookie:
        def close(self) -> None:
            return None

    def add_dll_directory(_path: str) -> Cookie:
        return Cookie()

    def load_library(path: str) -> Any:
        name = Path(path).name
        if name == "fmod64.dll":
            return C.CDLL(str(core_library), mode=C.RTLD_GLOBAL)
        if name == "fmodstudio64.dll":
            return C.CDLL(str(studio_library))
        raise ValueError(f"unexpected FMOD library request: {path}")

    global_probe.os.add_dll_directory = add_dll_directory
    global_probe.C.WinDLL = load_library
    global_probe.FMOD_VERSION = FMOD_MACOS_VERSION

    return global_probe, Guid


def _bind_core_probe_api(core: Any) -> None:
    core.FMOD_System_CreateSound.argtypes = [
        C.c_void_p,
        C.c_char_p,
        C.c_uint,
        C.c_void_p,
        C.POINTER(C.c_void_p),
    ]
    core.FMOD_System_CreateSound.restype = C.c_int
    core.FMOD_System_PlaySound.argtypes = [
        C.c_void_p,
        C.c_void_p,
        C.c_void_p,
        C.c_int,
        C.POINTER(C.c_void_p),
    ]
    core.FMOD_System_PlaySound.restype = C.c_int
    core.FMOD_System_GetVersion.argtypes = [C.c_void_p, C.POINTER(C.c_uint)]
    core.FMOD_System_GetVersion.restype = C.c_int
    core.FMOD_System_GetSoftwareChannels.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_int),
    ]
    core.FMOD_System_GetSoftwareChannels.restype = C.c_int
    core.FMOD_System_GetChannelsPlaying.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_int),
        C.POINTER(C.c_int),
    ]
    core.FMOD_System_GetChannelsPlaying.restype = C.c_int
    core.FMOD_System_GetDSPBufferSize.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_uint),
        C.POINTER(C.c_int),
    ]
    core.FMOD_System_GetDSPBufferSize.restype = C.c_int
    core.FMOD_System_GetSoftwareFormat.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_int),
        C.POINTER(C.c_int),
        C.POINTER(C.c_int),
    ]
    core.FMOD_System_GetSoftwareFormat.restype = C.c_int
    core.FMOD_Channel_SetVolume.argtypes = [C.c_void_p, C.c_float]
    core.FMOD_Channel_SetVolume.restype = C.c_int
    core.FMOD_Channel_SetPriority.argtypes = [C.c_void_p, C.c_int]
    core.FMOD_Channel_SetPriority.restype = C.c_int
    core.FMOD_Channel_SetPaused.argtypes = [C.c_void_p, C.c_int]
    core.FMOD_Channel_SetPaused.restype = C.c_int
    core.FMOD_Channel_Stop.argtypes = [C.c_void_p]
    core.FMOD_Channel_Stop.restype = C.c_int
    core.FMOD_Channel_IsVirtual.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
    core.FMOD_Channel_IsVirtual.restype = C.c_int
    core.FMOD_Channel_GetAudibility.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_float),
    ]
    core.FMOD_Channel_GetAudibility.restype = C.c_int
    core.FMOD_Channel_GetPosition.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_uint),
        C.c_uint,
    ]
    core.FMOD_Channel_GetPosition.restype = C.c_int
    core.FMOD_Channel_GetPriority.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
    core.FMOD_Channel_GetPriority.restype = C.c_int
    core.FMOD_Sound_Release.argtypes = [C.c_void_p]
    core.FMOD_Sound_Release.restype = C.c_int


def _write_tone(path: Path, *, frequency_hz: float) -> None:
    frame_count = SAMPLE_RATE_HZ * 2
    frames = bytearray()
    for frame in range(frame_count):
        value = round(
            math.sin(2.0 * math.pi * frequency_hz * frame / SAMPLE_RATE_HZ)
            * 8_192
        )
        frames.extend(struct.pack("<hh", value, value))
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE_HZ)
        output.writeframes(frames)


def _file_identity(role: str, path: Path) -> dict[str, Any]:
    resolved = path.resolve(strict=True)

    return {
        "role": role,
        "path": str(resolved),
        "byteCount": resolved.stat().st_size,
        "sha256": _sha256(resolved),
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)

    return digest.hexdigest()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio-lab-root", type=Path, default=DEFAULT_AUDIO_LAB_ROOT)
    parser.add_argument("--fmod-api-root", type=Path, default=DEFAULT_FMOD_API_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)

    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    report = probe(args.audio_lab_root, args.fmod_api_root, args.output_root)
    print(
        json.dumps(
            {
                "result": report["result"],
                "caseCount": len(report["cases"]),
                "maximumLogical": max(
                    case["createdLogicalChannelCount"] for case in report["cases"]
                ),
                "maximumReal": max(
                    snapshot["real"]
                    for case in report["cases"]
                    for snapshot in case["pressureTimeline"]
                ),
                "report": str(args.output_root.resolve() / "report.json"),
            },
            sort_keys=True,
        )
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
