"""Silently bound FMOD 1.08 one-shot re-entry/polyphony behavior.

This executable probe consumes an already target-isolated bank.  The target
sound name is used only as a callback identity assertion, never to infer its
role.  WAVWRITER_NRT is the only output, so no audio device is opened.
"""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import sys


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
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


SCHEMA = "ac-fmod-one-shot-polyphony-oracle-v1"
FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED = 0x00004000
FMOD_TIMEUNIT_PCM = 0x00000002


def probe(
    assetto_root: Path,
    isolated_bank: Path,
    event_path: str,
    target_sound_name: str,
    *,
    rpm_inside: float,
    rpm_outside: float,
    throttle: float,
    reentries: int,
    max_channels: int,
    output_wav: Path,
    software_channels: int | None = None,
    snapshot_delay_updates: int = 0,
    post_reentry_tail_updates: int = 0,
    pitch_motion_rpm: float | None = None,
    pitch_motion_after_updates: int = 100,
    maximum_tail_updates: int = 4000,
) -> dict:
    if pitch_motion_rpm is None and (reentries < 2 or reentries > 1000):
        raise ValueError("reentries must be in 2..1000")
    if max_channels < 1:
        raise ValueError("max_channels must be positive")
    if software_channels is not None and software_channels < 1:
        raise ValueError("software_channels must be positive")
    root = assetto_root.resolve(strict=True)
    bank_path = isolated_bank.resolve(strict=True)
    output_wav.parent.mkdir(parents=True, exist_ok=True)
    renderer = SilentFmodReferenceRenderer(root)
    cookie = os.add_dll_directory(str(root))
    core = C.WinDLL(str(root / "fmod64.dll"))
    studio = C.WinDLL(str(root / "fmodstudio64.dll"))
    renderer._bind(core, studio)
    core.FMOD_System_GetChannelsPlaying.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_int),
        C.POINTER(C.c_int),
    ]
    core.FMOD_System_GetChannelsPlaying.restype = C.c_int
    core.FMOD_System_SetSoftwareChannels.argtypes = [C.c_void_p, C.c_int]
    core.FMOD_System_SetSoftwareChannels.restype = C.c_int
    core.FMOD_ChannelGroup_GetNumChannels.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
    core.FMOD_ChannelGroup_GetNumChannels.restype = C.c_int
    core.FMOD_ChannelGroup_GetChannel.argtypes = [
        C.c_void_p,
        C.c_int,
        C.POINTER(C.c_void_p),
    ]
    core.FMOD_ChannelGroup_GetChannel.restype = C.c_int
    core.FMOD_ChannelGroup_GetNumGroups.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
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
    studio.FMOD_Studio_EventInstance_GetChannelGroup.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_void_p),
    ]
    studio.FMOD_Studio_EventInstance_GetChannelGroup.restype = C.c_int

    system = C.c_void_p()
    instance = C.c_void_p()
    callbacks: list[dict] = []
    active_target_voices = 0
    maximum_callback_active = 0
    stage = "setup"
    cycle = -1

    @EventCallback
    def callback(callback_type: int, _event: int, parameters_pointer: int) -> int:
        nonlocal active_target_voices, maximum_callback_active
        if callback_type not in {
            FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
            FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
        } or not parameters_pointer:
            return 0
        name = C.create_string_buffer(1024)
        renderer._check(
            core.FMOD_Sound_GetName(C.c_void_p(parameters_pointer), name, len(name)),
            "read polyphony-oracle sound identity",
        )
        decoded = name.value.decode("utf-8", "replace")
        if decoded != target_sound_name:
            raise AssertionError("target-isolated bank scheduled another sound")
        kind = (
            "played"
            if callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
            else "stopped"
        )
        active_target_voices += 1 if kind == "played" else -1
        maximum_callback_active = max(maximum_callback_active, active_target_voices)
        callbacks.append(
            {
                "kind": kind,
                "stage": stage,
                "cycle": cycle,
                "activeTargetVoicesAfterCallback": active_target_voices,
                "identitySha256": hashlib.sha256(decoded.encode("utf-8")).hexdigest(),
            }
        )
        return 0

    renderer._check(
        studio.FMOD_Studio_System_Create(C.byref(system), FMOD_VERSION),
        "create polyphony-oracle Studio system",
    )
    channel_records: list[dict] = []
    channel_voice_snapshots: list[dict] = []
    callbacks_after_reentries: list[dict] | None = None
    try:
        low_level = C.c_void_p()
        renderer._check(
            studio.FMOD_Studio_System_GetLowLevelSystem(system, C.byref(low_level)),
            "get polyphony-oracle low-level system",
        )
        renderer._check(
            core.FMOD_System_SetOutput(low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT),
            "select non-realtime writer",
        )
        renderer._check(
            core.FMOD_System_SetSoftwareFormat(low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0),
            "set stereo format",
        )
        renderer._check(
            core.FMOD_System_SetDSPBufferSize(low_level, renderer.dsp_buffer_frames, 4),
            "set DSP buffer",
        )
        if software_channels is not None:
            renderer._check(
                core.FMOD_System_SetSoftwareChannels(low_level, software_channels),
                "set software channel budget",
            )
        writer_name = C.create_string_buffer(str(output_wav).encode("utf-8"))
        renderer._check(
            studio.FMOD_Studio_System_Initialize(
                system, max_channels, 0, 0, C.cast(writer_name, C.c_void_p)
            ),
            "initialize polyphony-oracle writer",
        )
        distance, distance_keepalive = _distance_filter_description()
        gain, gain_keepalive = _gain_description()
        renderer._check(
            studio.FMOD_Studio_System_RegisterPlugin(system, C.byref(distance)),
            "register distance filter",
        )
        renderer._check(
            studio.FMOD_Studio_System_RegisterPlugin(system, C.byref(gain)),
            "register gain plugin",
        )
        loaded: list[C.c_void_p] = []
        for path in (
            root / "content" / "sfx" / "common.strings.bank",
            root / "content" / "sfx" / "common.bank",
            bank_path,
        ):
            bank = C.c_void_p()
            renderer._check(
                studio.FMOD_Studio_System_LoadBankFile(
                    system, str(path).encode("utf-8"), 0, C.byref(bank)
                ),
                f"load {path.name}",
            )
            loaded.append(bank)
        description = _event_description(studio, loaded[-1], event_path)
        renderer._check(
            studio.FMOD_Studio_EventDescription_LoadSampleData(description),
            "load sample data",
        )
        renderer._check(
            studio.FMOD_Studio_System_FlushSampleLoading(system),
            "flush sample loading",
        )
        renderer._check(
            studio.FMOD_Studio_EventDescription_CreateInstance(description, C.byref(instance)),
            "create event instance",
        )
        listener = _attributes((0.0, 0.7, 0.0))
        emitter = _attributes((0.0, 0.5, 0.0))
        renderer._check(
            studio.FMOD_Studio_System_SetListenerAttributes(system, 0, C.byref(listener)),
            "place listener",
        )
        renderer._check(
            studio.FMOD_Studio_EventInstance_Set3DAttributes(instance, C.byref(emitter)),
            "place emitter",
        )
        renderer._check(
            studio.FMOD_Studio_EventInstance_SetCallback(
                instance,
                callback,
                FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                | FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED,
            ),
            "attach lifecycle callback",
        )
        for name, value in ((b"rpms", rpm_inside), (b"throttle", throttle)):
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, name, C.c_float(value)
                ),
                f"set {name!r}",
            )
        stage = "event-start-inside"
        renderer._check(
            studio.FMOD_Studio_EventInstance_Start(instance), "start event inside"
        )
        renderer._check(
            studio.FMOD_Studio_System_FlushCommands(system), "flush event start"
        )
        renderer._check(studio.FMOD_Studio_System_Update(system), "render event start")

        if pitch_motion_rpm is not None:
            stage = "pre-motion"
            for cycle in range(pitch_motion_after_updates):
                renderer._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render before in-region RPM motion",
                )
            stage = "in-region-rpm-motion"
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, b"rpms", C.c_float(pitch_motion_rpm)
                ),
                "set in-region motion RPM",
            )
            renderer._check(
                studio.FMOD_Studio_System_FlushCommands(system),
                "flush in-region motion RPM",
            )
            tail_updates = 0
            while active_target_voices and tail_updates < maximum_tail_updates:
                renderer._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render after in-region RPM motion",
                )
                tail_updates += 1
            if active_target_voices:
                raise AssertionError("target voice did not finish inside tail bound")
        else:
            tail_updates = None
            for cycle in range(reentries):
                for stage_name, value in (("outside", rpm_outside), ("reenter", rpm_inside)):
                    stage = stage_name
                    renderer._check(
                        studio.FMOD_Studio_EventInstance_SetParameterValue(
                            instance, b"rpms", C.c_float(value)
                        ),
                        f"set RPM {stage_name}",
                    )
                    renderer._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        f"flush RPM {stage_name}",
                    )
                    renderer._check(
                        studio.FMOD_Studio_System_Update(system),
                        f"render RPM {stage_name}",
                    )
                    total = C.c_int()
                    real = C.c_int()
                    renderer._check(
                        core.FMOD_System_GetChannelsPlaying(
                            low_level, C.byref(total), C.byref(real)
                        ),
                        "read channel counts",
                    )
                    channel_records.append(
                        {
                            "cycle": cycle,
                            "stage": stage_name,
                            "logicalChannelsPlaying": total.value,
                            "realChannelsPlaying": real.value,
                            "callbackActiveTargetVoices": active_target_voices,
                        }
                    )
            callbacks_after_reentries = list(callbacks)
            stage = "pre-snapshot-tail"
            for _ in range(snapshot_delay_updates):
                renderer._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render before channel snapshot",
                )
            event_group = C.c_void_p()
            renderer._check(
                studio.FMOD_Studio_EventInstance_GetChannelGroup(
                    instance, C.byref(event_group)
                ),
                "get event channel group",
            )
            pending_groups: list[tuple[C.c_void_p, int]] = [(event_group, 0)]
            visited_groups: set[int] = set()
            while pending_groups:
                current_group, group_depth = pending_groups.pop()
                group_address = int(current_group.value or 0)
                if not group_address or group_address in visited_groups:
                    continue
                visited_groups.add(group_address)
                direct_count = C.c_int()
                renderer._check(
                    core.FMOD_ChannelGroup_GetNumChannels(
                        current_group, C.byref(direct_count)
                    ),
                    "count event channels",
                )
                for channel_index in range(direct_count.value):
                    channel = C.c_void_p()
                    renderer._check(
                        core.FMOD_ChannelGroup_GetChannel(
                            current_group, channel_index, C.byref(channel)
                        ),
                        "get event channel",
                    )
                    is_virtual = C.c_int()
                    position = C.c_uint()
                    renderer._check(
                        core.FMOD_Channel_IsVirtual(channel, C.byref(is_virtual)),
                        "read virtual state",
                    )
                    renderer._check(
                        core.FMOD_Channel_GetPosition(
                            channel, C.byref(position), FMOD_TIMEUNIT_PCM
                        ),
                        "read channel PCM position",
                    )
                    channel_voice_snapshots.append(
                        {
                            "groupDepth": group_depth,
                            "channelIndex": channel_index,
                            "isVirtual": bool(is_virtual.value),
                            "pcmPosition": position.value,
                        }
                    )
                child_count = C.c_int()
                renderer._check(
                    core.FMOD_ChannelGroup_GetNumGroups(
                        current_group, C.byref(child_count)
                    ),
                    "count child channel groups",
                )
                for group_index in range(child_count.value):
                    child_group = C.c_void_p()
                    renderer._check(
                        core.FMOD_ChannelGroup_GetGroup(
                            current_group, group_index, C.byref(child_group)
                        ),
                        "get child channel group",
                    )
                    pending_groups.append((child_group, group_depth + 1))
            stage = "post-reentry-tail"
            for tail_index in range(post_reentry_tail_updates):
                renderer._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render post-reentry tail",
                )
                if tail_index % 20 == 0 or tail_index + 1 == post_reentry_tail_updates:
                    total = C.c_int()
                    real = C.c_int()
                    renderer._check(
                        core.FMOD_System_GetChannelsPlaying(
                            low_level, C.byref(total), C.byref(real)
                        ),
                        "read post-reentry channel counts",
                    )
                    channel_records.append(
                        {
                            "tailUpdate": tail_index + 1,
                            "stage": stage,
                            "logicalChannelsPlaying": total.value,
                            "realChannelsPlaying": real.value,
                            "callbackActiveTargetVoices": active_target_voices,
                        }
                    )
        before_stop = list(callbacks)
        active_before_stop = active_target_voices
        stage = "explicit-event-stop"
        renderer._check(
            studio.FMOD_Studio_EventInstance_Stop(instance, FMOD_STUDIO_STOP_IMMEDIATE),
            "stop event",
        )
        renderer._check(studio.FMOD_Studio_System_FlushCommands(system), "flush stop")
        renderer._check(studio.FMOD_Studio_System_Update(system), "render stop")
        _ = (writer_name, callback, distance_keepalive, gain_keepalive)
    finally:
        if instance:
            studio.FMOD_Studio_EventInstance_Release(instance)
        if system:
            studio.FMOD_Studio_System_UnloadAll(system)
            studio.FMOD_Studio_System_Release(system)
        cookie.close()

    played_before_stop = sum(item["kind"] == "played" for item in before_stop)
    stopped_before_stop = sum(item["kind"] == "stopped" for item in before_stop)
    stopped_before_post_reentry_tail = sum(
        item["kind"] == "stopped" for item in (callbacks_after_reentries or ())
    )
    maximum_logical_channels = max(
        (item["logicalChannelsPlaying"] for item in channel_records), default=0
    )
    maximum_real_channels = max(
        (item["realChannelsPlaying"] for item in channel_records), default=0
    )
    return {
        "schema": SCHEMA,
        "basis": {
            "runtime": "FMOD Studio API 1.08.12",
            "output": "WAVWRITER_NRT",
            "audioDeviceOpened": False,
            "targetIsolated": True,
            "sampleNameUsedOnlyForRuntimeIdentity": True,
            "sampleNameUsedForSemantics": False,
            "dspBufferFrames": renderer.dsp_buffer_frames,
            "sampleRateHz": 48000,
            "systemInitializeMaxChannels": max_channels,
            "softwareChannels": software_channels,
        },
        "eventPath": event_path,
        "sequence": {
            "eventStartsInside": True,
            "reentries": reentries,
            "rpmInside": rpm_inside,
            "rpmOutside": rpm_outside,
            "updatesPerOutsideOrInsideState": 1,
            "pitchMotionRpm": pitch_motion_rpm,
            "pitchMotionAfterUpdates": (
                pitch_motion_after_updates if pitch_motion_rpm is not None else None
            ),
            "tailUpdatesUntilStopped": tail_updates,
            "postReentryTailUpdates": post_reentry_tail_updates,
            "snapshotDelayUpdates": snapshot_delay_updates,
        },
        "beforeExplicitStop": {
            "soundPlayedCallbacks": played_before_stop,
            "soundStoppedCallbacks": stopped_before_stop,
            "soundStoppedCallbacksBeforePostReentryTail": stopped_before_post_reentry_tail,
            "activeTargetVoicesByCallbacks": active_before_stop,
            "maximumActiveTargetVoicesByCallbacks": maximum_callback_active,
            "maximumLogicalChannelsPlaying": maximum_logical_channels,
            "maximumRealChannelsPlaying": maximum_real_channels,
        },
        "interpretation": {
            "authoredCapObservedBelowSystemLimit": False,
            "logicalCapObservedAtSystemInitializeMaxChannels": (
                maximum_logical_channels == max_channels
                and stopped_before_post_reentry_tail > 0
            ),
            "systemLimitStopObserved": stopped_before_post_reentry_tail > 0,
            "atLeastThisManyOverlappingTargetVoicesProven": maximum_logical_channels,
            "allLogicalVoicesFinishedBeforeExplicitStop": active_before_stop == 0,
            "globalOrUnboundedMaximumClaimed": False,
        },
        "channelRecords": channel_records,
        "peakChannelVoiceSnapshot": channel_voice_snapshots,
        "callbacks": callbacks,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--isolated-bank", required=True, type=Path)
    parser.add_argument("--event", required=True)
    parser.add_argument("--target-sound-name", required=True)
    parser.add_argument("--rpm-inside", required=True, type=float)
    parser.add_argument("--rpm-outside", required=True, type=float)
    parser.add_argument("--throttle", type=float, default=0.0)
    parser.add_argument("--reentries", type=int, default=80)
    parser.add_argument("--max-channels", type=int, default=64)
    parser.add_argument("--software-channels", type=int)
    parser.add_argument("--snapshot-delay-updates", type=int, default=0)
    parser.add_argument("--post-reentry-tail-updates", type=int, default=0)
    parser.add_argument("--pitch-motion-rpm", type=float)
    parser.add_argument("--pitch-motion-after-updates", type=int, default=100)
    parser.add_argument("--maximum-tail-updates", type=int, default=4000)
    parser.add_argument("--output-wav", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    result = probe(
        find_assetto_root(args.assetto_root),
        args.isolated_bank,
        args.event,
        args.target_sound_name,
        rpm_inside=args.rpm_inside,
        rpm_outside=args.rpm_outside,
        throttle=args.throttle,
        reentries=args.reentries,
        max_channels=args.max_channels,
        output_wav=args.output_wav,
        software_channels=args.software_channels,
        snapshot_delay_updates=args.snapshot_delay_updates,
        post_reentry_tail_updates=args.post_reentry_tail_updates,
        pitch_motion_rpm=args.pitch_motion_rpm,
        pitch_motion_after_updates=args.pitch_motion_after_updates,
        maximum_tail_updates=args.maximum_tail_updates,
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_bytes(canonical_json_bytes(result) + b"\n")
    print(json.dumps(result["beforeExplicitStop"], sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
