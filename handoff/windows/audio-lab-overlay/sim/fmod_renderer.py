"""Headless 48 kHz stereo reference rendering through AC's FMOD runtime."""

from __future__ import annotations

import ctypes as C
import hashlib
import json
import math
import os
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from .fmod_native import (
    ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
    Attributes3D,
    DspDescription,
    FMOD_API_ROOT_ENV,
    FMOD_MACOS_VERSION,
    FMOD_VERSION,
    FmodError,
    Guid,
    _attributes,
    _distance_filter_description,
    _gain_description,
)


FMOD_OUTPUTTYPE_WAVWRITER_NRT = 5
FMOD_SPEAKERMODE_STEREO = 3
FMOD_STUDIO_STOP_IMMEDIATE = 1
FMOD_STUDIO_STOP_ALLOWFADEOUT = 0
FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED = 0x00002000
FMOD_TIMEUNIT_PCM = 0x00000002
FRESH_RENDER_REQUEST_SCHEMA = "ac-fmod-fresh-render-request-v1"
FRESH_RENDER_RESULT_SCHEMA = "ac-fmod-fresh-render-result-v1"
FRESH_RENDER_EVIDENCE_SCHEMA = "ac-fmod-fresh-render-evidence-v1"
ENGINE_PROGRAM_MASK_ROLES = frozenset(
    {"LOAD", "COAST", "UNAFFECTED", "EXCLUDED"}
)
MAXIMUM_MASK_PARAMETER_SETTLE_BLOCKS = 4_096
MASK_PARAMETER_SETTLE_EPSILON = 1e-4
MASK_ROUTE_STABLE_BLOCKS = 8
MASK_ROUTE_ABSOLUTE_STABILITY_EPSILON = 1e-7
MASK_ROUTE_RELATIVE_STABILITY_EPSILON = 1e-4


EventCallback = getattr(C, "WINFUNCTYPE", C.CFUNCTYPE)(
    C.c_int, C.c_uint, C.c_void_p, C.c_void_p
)


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
        allow_nan=False,
    ).encode("ascii")


def _read_strict_json(path: Path, *, maximum_bytes: int = 8 * 1024 * 1024) -> dict[str, Any]:
    payload = path.read_bytes()
    if len(payload) > maximum_bytes:
        raise FmodError(f"fresh FMOD JSON exceeds {maximum_bytes} bytes")

    def reject_duplicates(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise FmodError(f"fresh FMOD JSON duplicates key {key!r}")
            result[key] = value
        return result

    try:
        value = json.loads(payload.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FmodError(f"fresh FMOD JSON is invalid: {exc}") from exc
    if not isinstance(value, dict):
        raise FmodError("fresh FMOD JSON root must be an object")
    return value


def _write_canonical_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(_canonical_json_bytes(value) + b"\n")
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


@dataclass(frozen=True)
class ReferenceRender:
    output_path: Path
    event_path: str
    parameters: dict[str, float]
    start_parameters: dict[str, float] | None
    sample_rate: int
    channels: int
    bits_per_sample: int
    frame_count: int
    scheduled_sound_names: tuple[str, ...]
    scheduled_sound_names_by_take: tuple[tuple[str, ...], ...]


@dataclass(frozen=True)
class ReferenceMixRender:
    """Evidence from one shared-Studio-System multi-event NRT render."""

    output_path: Path
    event_paths: tuple[str, ...]
    sample_rate: int
    channels: int
    bits_per_sample: int
    frame_count: int
    scheduled_sound_names: tuple[str, ...]
    scheduled_sound_names_by_instance: tuple[tuple[str, ...], ...]
    channel_snapshots: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ChannelMaskReferenceRender:
    """Phase-related event captures made from one live EventInstance."""

    output_paths: dict[str, Path]
    event_path: str
    scheduled_sound_names: tuple[str, ...]
    channel_sound_names: tuple[str, ...]


@dataclass
class _ChannelMaskCapturePlan:
    sound_role_candidates: Mapping[str, tuple[str, ...]]
    masks: tuple[tuple[str, Mapping[str, float]], ...]
    warmup_frames: int
    capture_frames: int
    padded_capture_frames: int

    @property
    def segment_frames(self) -> int:
        return self.warmup_frames + self.padded_capture_frames


@dataclass(frozen=True)
class _MaskChannel:
    sound_name: str
    role: str
    base_volume: float
    binding_route: tuple[str, tuple[int, ...]]


@dataclass(frozen=True)
class _MaskChannelEvidence:
    channel_id: int
    sound_name: str
    base_volume: float
    audibility: float
    channel_groups: tuple[dict[str, Any], ...]


class SilentFmodReferenceRenderer:
    """Render one event to a file without ever opening a playback device."""

    def __init__(
        self,
        assetto_root: Path,
        *,
        dsp_buffer_frames: int = 256,
        fresh_process_per_render: bool = False,
    ):
        if os.name != "nt" and sys.platform != "darwin":
            raise FmodError(f"the FMOD oracle is not configured for {sys.platform}")
        if fresh_process_per_render and os.name != "nt":
            raise FmodError(
                "fresh-process reference rendering currently requires the native "
                "Assetto Corsa Windows runtime"
            )
        self.root = assetto_root.resolve()
        self.dsp_buffer_frames = int(dsp_buffer_frames)
        if self.dsp_buffer_frames <= 0:
            raise ValueError("dsp_buffer_frames must be positive")
        self.fresh_process_per_render = bool(fresh_process_per_render)
        self.last_channel_mask_sound_names: tuple[str, ...] = ()
        self.last_channel_mask_channels: tuple[dict[str, Any], ...] = ()
        self.last_gain_dsp_parameter_observations: dict[
            str, tuple[tuple[int, float | bool], ...]
        ] = {"float": (), "bool": ()}
        self.last_fresh_process_evidence: dict[str, Any] | None = None

    @staticmethod
    def _check(result: int, operation: str) -> None:
        if result:
            raise FmodError(f"{operation}: FMOD result {result}")

    def render_event(
        self,
        bank_path: Path,
        event_name: str,
        output_path: Path,
        *,
        parameters: dict[str, float] | None = None,
        start_parameters: dict[str, float] | None = None,
        parameter_updates: Sequence[tuple[int, Mapping[str, float]]] | None = None,
        duration_frames: int = 192000,
        warmup_frames: int = 24000,
        emitter_position: tuple[float, float, float] = (0.0, 0.5, 0.0),
        listener_position: tuple[float, float, float] = (0.0, 0.7, 0.0),
        variant_index: int = 0,
        required_sound_name: str | None = None,
        allow_missing_required_sound_name: bool = False,
        maximum_takes: int = 64,
        event_id_lookup_bank_path: Path | None = None,
        take_lifecycle: str = "newEventInstancePerTake-v1",
    ) -> ReferenceRender:
        if (
            duration_frames <= 0
            or warmup_frames < 0
            or variant_index < 0
            or maximum_takes <= 0
        ):
            raise ValueError("duration_frames must be positive and warmup_frames non-negative")
        if required_sound_name is not None and (not required_sound_name or variant_index != 0):
            raise ValueError("required_sound_name needs a non-empty name and variant_index zero")
        if allow_missing_required_sound_name and required_sound_name is None:
            raise ValueError(
                "allow_missing_required_sound_name requires required_sound_name"
            )
        if take_lifecycle not in {
            "newEventInstancePerTake-v1",
            "singleEventInstancePlacementReentry-v1",
            "singleEventInstanceInitialInsideThenPlacementReentry-v1",
            "singleEventInstanceStopRewindStart-v1",
        }:
            raise ValueError("take_lifecycle is not a supported deterministic selection policy")
        event_guid_lookup_bank = (
            Path(event_id_lookup_bank_path).resolve()
            if event_id_lookup_bank_path is not None
            else bank_path.resolve()
        )
        if not event_guid_lookup_bank.is_file():
            raise ValueError("event_id_lookup_bank_path must name an existing source bank")
        parameter_values = {
            str(key): float(value) for key, value in (parameters or {}).items()
        }
        initial_parameter_values: dict[str, float] | None = None
        if start_parameters is not None:
            initial_parameter_values = dict(parameter_values)
            initial_parameter_values.update(
                {str(key): float(value) for key, value in start_parameters.items()}
            )
            if initial_parameter_values == parameter_values:
                initial_parameter_values = None
        for label, values in (
            ("parameters", parameter_values),
            ("start_parameters", initial_parameter_values or {}),
        ):
            if any(not key or not math.isfinite(value) for key, value in values.items()):
                raise ValueError(f"{label} must contain named finite values")
        scheduled_updates: list[tuple[int, dict[str, float]]] = []
        for frame, values in parameter_updates or ():
            if (
                not isinstance(frame, int)
                or frame < 0
                or frame >= duration_frames + warmup_frames
                or frame % self.dsp_buffer_frames
            ):
                raise ValueError("parameter update frames must be in-range DSP boundaries")
            normalized = {str(key): float(value) for key, value in values.items()}
            if not normalized or any(not key or not math.isfinite(value) for key, value in normalized.items()):
                raise ValueError("parameter updates must contain named finite values")
            scheduled_updates.append((frame, normalized))
        if scheduled_updates != sorted(scheduled_updates, key=lambda item: item[0]) or len({frame for frame, _values in scheduled_updates}) != len(scheduled_updates):
            raise ValueError("parameter updates must be strictly ordered by distinct DSP frames")
        if take_lifecycle in {
            "singleEventInstancePlacementReentry-v1",
            "singleEventInstanceInitialInsideThenPlacementReentry-v1",
        }:
            if initial_parameter_values is None:
                raise ValueError(
                    "same-event placement lifecycle requires explicit start_parameters"
                )
            if scheduled_updates:
                raise ValueError(
                    "same-event placement reentry does not support per-take parameter trajectories"
                )
        if take_lifecycle == "singleEventInstanceStopRewindStart-v1" and scheduled_updates:
            raise ValueError(
                "same-event stop/rewind/start probe does not support per-take parameter trajectories"
            )
        if self.fresh_process_per_render:
            if event_guid_lookup_bank != bank_path.resolve():
                raise FmodError(
                    "fresh-process renderer does not support a separate event GUID lookup bank"
                )
            if scheduled_updates:
                raise FmodError("fresh-process renderer does not support parameter trajectories")
            if take_lifecycle != "newEventInstancePerTake-v1":
                raise FmodError("fresh-process renderer does not support same-instance placement reentry takes")
            return self._render_event_in_fresh_process(
                bank_path.resolve(),
                event_name,
                output_path.resolve(),
                parameters=parameter_values,
                start_parameters=initial_parameter_values,
                parameter_updates=scheduled_updates,
                duration_frames=duration_frames,
                warmup_frames=warmup_frames,
                emitter_position=emitter_position,
                listener_position=listener_position,
                variant_index=variant_index,
                required_sound_name=required_sound_name,
                maximum_takes=maximum_takes,
            )
        output = output_path.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="fmod-reference-", dir=output.parent) as temp_text:
            raw_writer_path = Path(temp_text) / "oracle.wav"
            take_count = maximum_takes if required_sound_name is not None else variant_index + 1
            (
                event_path,
                take_stride,
                scheduled_sound_names,
                scheduled_sound_names_by_take,
            ) = self._render_writer(
                bank_path.resolve(),
                event_name,
                raw_writer_path,
                parameter_values,
                initial_parameter_values,
                scheduled_updates,
                duration_frames + warmup_frames,
                emitter_position,
                listener_position,
                take_count,
                event_guid_lookup_bank,
                take_lifecycle,
            )
            selected_take = variant_index
            selected_names = scheduled_sound_names
            if required_sound_name is not None:
                selected_take = next(
                    (
                        index
                        for index, names in enumerate(scheduled_sound_names_by_take)
                        if names and set(names) == {required_sound_name}
                    ),
                    -1,
                )
                if selected_take < 0:
                    if not allow_missing_required_sound_name:
                        raise FmodError(
                            f"source identity {required_sound_name!r} was not selected in "
                            f"{maximum_takes} deterministic event takes"
                        )
                    # Boundary/outside probes need the complete take trace to
                    # prove a target was never scheduled.  Keep a deterministic
                    # crop solely so the regular ReferenceRender invariant holds;
                    # callers must inspect scheduled_sound_names_by_take rather
                    # than treating this first take as a selected target.
                    selected_take = 0
                    selected_names = scheduled_sound_names_by_take[selected_take]
                else:
                    selected_names = scheduled_sound_names_by_take[selected_take]
            self._crop_canonical(
                raw_writer_path,
                output,
                start_frame=(
                    selected_take * take_stride
                    + (
                        self.dsp_buffer_frames
                        if initial_parameter_values
                        and take_lifecycle
                        != "singleEventInstanceInitialInsideThenPlacementReentry-v1"
                        else 0
                    )
                    + warmup_frames
                ),
                frame_count=duration_frames,
            )
        return ReferenceRender(
            output,
            event_path,
            parameter_values,
            initial_parameter_values,
            48000,
            2,
            16,
            duration_frames,
            selected_names,
            scheduled_sound_names_by_take,
        )

    def render_event_channel_masks(
        self,
        bank_path: Path,
        event_name: str,
        output_directory: Path,
        *,
        parameters: Mapping[str, float],
        sound_roles: Mapping[str, str | Sequence[str]],
        masks: Sequence[tuple[str, Mapping[str, float]]],
        duration_frames: int = 48_000,
        warmup_frames: int = 36_000,
        emitter_position: tuple[float, float, float] = (0.0, 0.5, 0.0),
        listener_position: tuple[float, float, float] = (0.0, 0.7, 0.0),
        event_id_lookup_bank_path: Path | None = None,
    ) -> ChannelMaskReferenceRender:
        """Capture role masks from one running FMOD event and channel set.

        Each segment restores the same channel PCM positions and retains the
        same EventInstance, random choices, modulators, and decoder objects.
        This is the only role-isolation path that may claim phase relation;
        separately started events are intentionally rejected by downstream
        sample-wise conservation checks.
        """

        if self.fresh_process_per_render:
            raise FmodError("channel-mask capture needs the regular unmodified event renderer")
        if duration_frames <= 0 or warmup_frames < 0:
            raise ValueError("channel-mask capture frame counts are invalid")
        normalized_role_candidates: dict[str, tuple[str, ...]] = {}
        for raw_name, raw_roles in sound_roles.items():
            name = str(raw_name)
            roles = (
                (str(raw_roles).upper(),)
                if isinstance(raw_roles, str)
                else tuple(str(role).upper() for role in raw_roles)
            )
            if not name or not roles or any(not role for role in roles):
                raise ValueError("channel-mask sound identities and roles must be non-empty")
            normalized_role_candidates[name] = roles
        if (
            len(normalized_role_candidates) != len(sound_roles)
            or not normalized_role_candidates
        ):
            raise ValueError("channel-mask sound roles must be uniquely named and non-empty")
        declared_roles = {
            role for roles in normalized_role_candidates.values() for role in roles
        }
        if declared_roles - ENGINE_PROGRAM_MASK_ROLES:
            raise ValueError("channel-mask sound roles contain an unsupported role")
        normalized_masks: list[tuple[str, dict[str, float]]] = []
        for label, raw_gains in masks:
            normalized_label = str(label).strip()
            gains = {str(role).upper(): float(value) for role, value in raw_gains.items()}
            if (
                not normalized_label
                or any(existing_label == normalized_label for existing_label, _ in normalized_masks)
                or set(gains) != ENGINE_PROGRAM_MASK_ROLES
                or any(not math.isfinite(value) or value < 0.0 for value in gains.values())
            ):
                raise ValueError("channel-mask labels/role gains are incomplete or invalid")
            normalized_masks.append((normalized_label, gains))
        if not normalized_masks:
            raise ValueError("channel-mask capture needs at least one mask")
        padded_warmup = (
            math.ceil(warmup_frames / self.dsp_buffer_frames) * self.dsp_buffer_frames
        )
        padded_capture = (
            math.ceil(duration_frames / self.dsp_buffer_frames) * self.dsp_buffer_frames
        )
        plan = _ChannelMaskCapturePlan(
            sound_role_candidates=normalized_role_candidates,
            masks=tuple(normalized_masks),
            warmup_frames=padded_warmup,
            capture_frames=duration_frames,
            padded_capture_frames=padded_capture,
        )
        output_root = output_directory.resolve()
        output_root.mkdir(parents=True, exist_ok=True)
        event_guid_lookup_bank = (
            Path(event_id_lookup_bank_path).resolve()
            if event_id_lookup_bank_path is not None
            else bank_path.resolve()
        )
        with tempfile.TemporaryDirectory(
            prefix="fmod-channel-masks-",
            dir=output_root,
        ) as temp_text:
            raw_writer_path = Path(temp_text) / "masks.wav"
            (
                event_path,
                take_stride,
                scheduled_sound_names,
                _scheduled_by_take,
            ) = self._render_writer(
                bank_path.resolve(),
                event_name,
                raw_writer_path,
                {str(name): float(value) for name, value in parameters.items()},
                None,
                (),
                len(plan.masks) * plan.segment_frames,
                emitter_position,
                listener_position,
                1,
                event_guid_lookup_bank,
                "newEventInstancePerTake-v1",
                channel_capture_plan=plan,
            )
            capture_frames = len(plan.masks) * plan.segment_frames
            prelude_frames = take_stride - capture_frames
            if (
                prelude_frames < self.dsp_buffer_frames
                or prelude_frames % self.dsp_buffer_frames != 0
            ):
                raise FmodError("channel-mask capture returned an invalid calibration prelude")
            output_paths: dict[str, Path] = {}
            for index, (label, _gains) in enumerate(plan.masks):
                destination = output_root / f"{label}.wav"
                self._crop_canonical(
                    raw_writer_path,
                    destination,
                    start_frame=(
                        prelude_frames
                        + index * plan.segment_frames
                        + plan.warmup_frames
                    ),
                    frame_count=plan.capture_frames,
                )
                output_paths[label] = destination
        return ChannelMaskReferenceRender(
            output_paths=output_paths,
            event_path=event_path,
            scheduled_sound_names=scheduled_sound_names,
            channel_sound_names=self.last_channel_mask_sound_names,
        )

    def render_event_mix(
        self,
        bank_path: Path,
        output_path: Path,
        *,
        events: Sequence[Mapping[str, Any]],
        duration_frames: int,
        warmup_frames: int = 0,
        emitter_position: tuple[float, float, float] = (0.0, 0.5, 0.0),
        listener_position: tuple[float, float, float] = (0.0, 0.7, 0.0),
    ) -> ReferenceMixRender:
        """Render scheduled event instances through one FMOD Studio System.

        Each item declares the event name, its absolute DSP-block-aligned
        start frame, initial parameters, optional absolute DSP-block parameter
        updates, and the host-side linear event gain.
        The gain is applied with ``EventInstance_SetVolume`` before start, so
        engine/effect summing is evaluated on the same FMOD buses before the
        atlas host's common causal master limiter.  This deliberately has no
        fresh-process mode: a multi-instance render is already self-contained
        in one short-lived Studio System and fresh-process workers only accept
        one single-event request schema.
        """

        if duration_frames <= 0 or warmup_frames < 0:
            raise ValueError("duration_frames must be positive and warmup_frames non-negative")
        if self.fresh_process_per_render:
            raise FmodError("fresh-process renderer does not support shared event mixes")
        total_frames = duration_frames + warmup_frames
        normalized_events: list[dict[str, Any]] = []
        for ordinal, raw in enumerate(events):
            if not isinstance(raw, Mapping):
                raise ValueError("mix events must be mappings")
            name = str(raw.get("eventName") or "").strip()
            start = raw.get("startFrame")
            gain = raw.get("hostGainLinear")
            values = raw.get("parameters", {})
            raw_updates = raw.get("parameterUpdates", ())
            raw_actions = raw.get("lifecycleActions")
            stop_frame = raw.get("stopFrame")
            stop_mode = raw.get("stopMode", "FMOD_STUDIO_STOP_IMMEDIATE")
            event_bank = raw.get("bankPath", bank_path)
            guid_lookup_bank = raw.get("eventIdLookupBankPath", event_bank)
            if (
                not name
                or isinstance(start, bool)
                or not isinstance(start, int)
                or start < 0
                or start >= total_frames
                or start % self.dsp_buffer_frames
                or isinstance(gain, bool)
                or not isinstance(gain, (int, float))
                or not math.isfinite(float(gain))
                or float(gain) < 0.0
                or not isinstance(values, Mapping)
                or not isinstance(raw_updates, Sequence)
                or isinstance(raw_updates, (str, bytes))
                or not isinstance(event_bank, (str, Path))
                or not isinstance(guid_lookup_bank, (str, Path))
                or (
                    stop_frame is not None
                    and (
                        isinstance(stop_frame, bool)
                        or not isinstance(stop_frame, int)
                        or stop_frame <= start
                        or stop_frame >= total_frames
                        or stop_frame % self.dsp_buffer_frames
                    )
                )
                or stop_mode not in {
                    "FMOD_STUDIO_STOP_IMMEDIATE",
                    "FMOD_STUDIO_STOP_ALLOWFADEOUT",
                }
            ):
                raise ValueError("mix event fields are invalid")
            if raw_actions is None:
                lifecycle_actions = [{"frame": start, "action": "start"}]
                if stop_frame is not None:
                    lifecycle_actions.append(
                        {
                            "frame": stop_frame,
                            "action": (
                                "stopAllowFadeout"
                                if stop_mode == "FMOD_STUDIO_STOP_ALLOWFADEOUT"
                                else "stopImmediate"
                            ),
                        }
                    )
            else:
                if (
                    not isinstance(raw_actions, Sequence)
                    or isinstance(raw_actions, (str, bytes))
                ):
                    raise ValueError("mix lifecycle actions are invalid")
                lifecycle_actions = []
                for raw_action in raw_actions:
                    if not isinstance(raw_action, Mapping):
                        raise ValueError("mix lifecycle action is not an object")
                    action_frame = raw_action.get("frame")
                    action = raw_action.get("action")
                    if (
                        isinstance(action_frame, bool)
                        or not isinstance(action_frame, int)
                        or action_frame < 0
                        or action_frame >= total_frames
                        or action_frame % self.dsp_buffer_frames
                        or action not in {
                            "start",
                            "stopImmediate",
                            "stopAllowFadeout",
                        }
                    ):
                        raise ValueError("mix lifecycle action fields are invalid")
                    lifecycle_actions.append(
                        {"frame": action_frame, "action": str(action)}
                    )
                if (
                    not lifecycle_actions
                    or lifecycle_actions
                    != sorted(lifecycle_actions, key=lambda item: item["frame"])
                    or lifecycle_actions[0]["action"] != "start"
                    or lifecycle_actions[0]["frame"] != start
                    or any(
                        before["frame"] == after["frame"]
                        for before, after in zip(
                            lifecycle_actions,
                            lifecycle_actions[1:],
                        )
                    )
                ):
                    raise ValueError(
                        "mix lifecycle actions must be ordered, distinct, and begin at startFrame"
                    )
            parameters = {str(key): float(value) for key, value in values.items()}
            if any(not key or not math.isfinite(value) for key, value in parameters.items()):
                raise ValueError("mix event parameters must be named finite values")
            parameter_updates: list[tuple[int, dict[str, float]]] = []
            for update in raw_updates:
                if (
                    not isinstance(update, Sequence)
                    or isinstance(update, (str, bytes))
                    or len(update) != 2
                    or isinstance(update[0], bool)
                    or not isinstance(update[0], int)
                    or update[0] <= start
                    or update[0] >= total_frames
                    or update[0] % self.dsp_buffer_frames
                    or not isinstance(update[1], Mapping)
                ):
                    raise ValueError("mix parameter updates are invalid")
                update_values = {
                    str(key): float(value) for key, value in update[1].items()
                }
                if (
                    not update_values
                    or any(
                        not key or not math.isfinite(value)
                        for key, value in update_values.items()
                    )
                ):
                    raise ValueError("mix parameter updates must be named finite values")
                parameter_updates.append((int(update[0]), update_values))
            if (
                parameter_updates
                != sorted(parameter_updates, key=lambda item: item[0])
                or len({frame for frame, _values in parameter_updates})
                != len(parameter_updates)
            ):
                raise ValueError(
                    "mix parameter updates must be strictly ordered by distinct DSP frames"
                )
            normalized_events.append(
                {
                    "ordinal": ordinal,
                    "eventName": name,
                    "startFrame": start,
                    "hostGainLinear": float(gain),
                    "parameters": parameters,
                    "parameterUpdates": parameter_updates,
                    "bankPath": str(Path(event_bank).resolve()),
                    "eventIdLookupBankPath": str(Path(guid_lookup_bank).resolve()),
                    "stopFrame": stop_frame,
                    "stopMode": stop_mode,
                    "lifecycleActions": lifecycle_actions,
                }
            )
        if not normalized_events:
            raise ValueError("shared event mix needs at least one event")
        output = output_path.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="fmod-mix-reference-", dir=output.parent) as temp_text:
            raw_writer_path = Path(temp_text) / "oracle-mix.wav"
            event_paths, scheduled, scheduled_by_instance, channel_snapshots = self._render_mix_writer(
                bank_path.resolve(),
                raw_writer_path,
                normalized_events,
                total_frames,
                emitter_position,
                listener_position,
            )
            self._crop_canonical(
                raw_writer_path,
                output,
                start_frame=warmup_frames,
                frame_count=duration_frames,
            )
        return ReferenceMixRender(
            output,
            event_paths,
            48_000,
            2,
            16,
            duration_frames,
            scheduled,
            scheduled_by_instance,
            channel_snapshots,
        )

    def _render_event_in_fresh_process(
        self,
        bank_path: Path,
        event_name: str,
        output_path: Path,
        *,
        parameters: dict[str, float],
        start_parameters: dict[str, float] | None,
        parameter_updates: Sequence[tuple[int, Mapping[str, float]]],
        duration_frames: int,
        warmup_frames: int,
        emitter_position: tuple[float, float, float],
        listener_position: tuple[float, float, float],
        variant_index: int,
        required_sound_name: str | None,
        maximum_takes: int,
    ) -> ReferenceRender:
        """Render exactly one request in a new process and verify its boundary.

        Every release capture crosses this hash-bound worker boundary so a
        prior request cannot influence its Studio System.  This is defensive
        isolation: no byte-identical fresh-vs-reused request divergence is
        currently claimed by the release evidence.
        """

        if not bank_path.is_file():
            raise FileNotFoundError(bank_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        runtime_dlls = {
            name: _sha256_file(self.root / name)
            for name in ("fmod64.dll", "fmodstudio64.dll")
        }
        request: dict[str, Any] = {
            "schema": FRESH_RENDER_REQUEST_SCHEMA,
            "assettoRoot": str(self.root),
            "runtimeDllSha256": runtime_dlls,
            "bankPath": str(bank_path),
            "bankSha256": _sha256_file(bank_path),
            "eventName": str(event_name),
            "outputPath": str(output_path),
            "parameters": dict(sorted(parameters.items())),
            "startParameters": (
                None if start_parameters is None else dict(sorted(start_parameters.items()))
            ),
            "parameterUpdates": [
                {"frame": frame, "parameters": dict(sorted(values.items()))}
                for frame, values in parameter_updates
            ],
            "durationFrames": int(duration_frames),
            "warmupFrames": int(warmup_frames),
            "emitterPosition": [float(value) for value in emitter_position],
            "listenerPosition": [float(value) for value in listener_position],
            "variantIndex": int(variant_index),
            "requiredSoundName": required_sound_name,
            "maximumTakes": int(maximum_takes),
            "dspBufferFrames": self.dsp_buffer_frames,
        }
        render_identity = {
            key: value for key, value in request.items() if key != "outputPath"
        }
        request["renderIdentitySha256"] = _sha256_bytes(
            _canonical_json_bytes(render_identity)
        )
        request_sha = _sha256_bytes(_canonical_json_bytes(request))
        envelope = {"request": request, "requestSha256": request_sha}
        take_count = maximum_takes if required_sound_name is not None else variant_index + 1
        rendered_seconds = (
            (duration_frames + warmup_frames) * take_count / 48000.0
        )
        timeout_seconds = min(7200.0, max(120.0, 60.0 + rendered_seconds * 0.25))
        with tempfile.TemporaryDirectory(
            prefix="fmod-fresh-request-", dir=output_path.parent
        ) as temporary_text:
            temporary = Path(temporary_text)
            request_path = temporary / "request.json"
            result_path = temporary / "result.json"
            request_path.write_bytes(_canonical_json_bytes(envelope) + b"\n")
            output_path.unlink(missing_ok=True)
            creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            try:
                completed = subprocess.run(
                    [
                        sys.executable,
                        "-m",
                        "sim.fmod_renderer",
                        "--fresh-render-worker",
                        str(request_path),
                        str(result_path),
                    ],
                    cwd=str(Path(__file__).resolve().parents[1]),
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=timeout_seconds,
                    creationflags=creation_flags,
                    check=False,
                )
            except subprocess.TimeoutExpired as exc:
                raise FmodError(
                    f"fresh FMOD render timed out after {timeout_seconds:.1f}s"
                ) from exc
            if completed.returncode != 0:
                detail = (completed.stderr or completed.stdout).strip()
                raise FmodError(
                    "fresh FMOD render worker failed"
                    + (f": {detail[-2000:]}" if detail else " without diagnostics")
                )
            if completed.stdout.strip() or completed.stderr.strip():
                raise FmodError("fresh FMOD render worker emitted unexpected console output")
            result_envelope = _read_strict_json(result_path)
            if set(result_envelope) != {"result", "resultSha256"}:
                raise FmodError("fresh FMOD render result envelope fields changed")
            result = result_envelope["result"]
            if not isinstance(result, dict) or (
                result_envelope["resultSha256"]
                != _sha256_bytes(_canonical_json_bytes(result))
            ):
                raise FmodError("fresh FMOD render result hash mismatch")
            expected_fields = {
                "schema",
                "requestSha256",
                "renderIdentitySha256",
                "outputSha256",
                "eventPath",
                "parameters",
                "startParameters",
                "sampleRate",
                "channels",
                "bitsPerSample",
                "frameCount",
                "scheduledSoundNames",
                "scheduledSoundNamesByTake",
                "gainDspParameterObservations",
            }
            if set(result) != expected_fields or (
                result["schema"] != FRESH_RENDER_RESULT_SCHEMA
                or result["requestSha256"] != request_sha
                or result["renderIdentitySha256"]
                != request["renderIdentitySha256"]
                or result["parameters"] != request["parameters"]
                or result["startParameters"] != request["startParameters"]
                or result["sampleRate"] != 48000
                or result["channels"] != 2
                or result["bitsPerSample"] != 16
                or result["frameCount"] != duration_frames
                or not output_path.is_file()
                or result["outputSha256"] != _sha256_file(output_path)
            ):
                raise FmodError("fresh FMOD render result disagrees with its request")
            names = result["scheduledSoundNames"]
            names_by_take = result["scheduledSoundNamesByTake"]
            if (
                not isinstance(result["eventPath"], str)
                or not isinstance(names, list)
                or any(not isinstance(name, str) for name in names)
                or not isinstance(names_by_take, list)
                or any(
                    not isinstance(items, list)
                    or any(not isinstance(name, str) for name in items)
                    for items in names_by_take
                )
                or len(names_by_take) != take_count
            ):
                raise FmodError("fresh FMOD render callback evidence is invalid")
            observations = result["gainDspParameterObservations"]
            if not isinstance(observations, dict) or set(observations) != {"float", "bool"}:
                raise FmodError("fresh FMOD Gain observations are invalid")
            parsed_observations: dict[str, tuple[tuple[int, float | bool], ...]] = {}
            for kind in ("float", "bool"):
                values = observations[kind]
                if not isinstance(values, list):
                    raise FmodError("fresh FMOD Gain observation list is invalid")
                parsed: list[tuple[int, float | bool]] = []
                for item in values:
                    if (
                        not isinstance(item, list)
                        or len(item) != 2
                        or isinstance(item[0], bool)
                        or not isinstance(item[0], int)
                    ):
                        raise FmodError("fresh FMOD Gain observation is invalid")
                    value = item[1]
                    if kind == "bool":
                        if not isinstance(value, bool):
                            raise FmodError("fresh FMOD Gain bool observation is invalid")
                    elif isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                        raise FmodError("fresh FMOD Gain float observation is invalid")
                    parsed.append((item[0], value if isinstance(value, bool) else float(value)))
                parsed_observations[kind] = tuple(parsed)
            self.last_gain_dsp_parameter_observations = parsed_observations
            evidence_payload = {
                "schema": FRESH_RENDER_EVIDENCE_SCHEMA,
                "requestEnvelope": envelope,
                "resultEnvelope": result_envelope,
                "timeoutSeconds": timeout_seconds,
                "workerReturnCode": completed.returncode,
            }
            evidence_envelope = {
                "evidence": evidence_payload,
                "evidenceSha256": _sha256_bytes(
                    _canonical_json_bytes(evidence_payload)
                ),
            }
            evidence_path = output_path.with_name(
                f".{output_path.name}.fresh-render-evidence.json"
            )
            _write_canonical_json_atomic(evidence_path, evidence_envelope)
            self.last_fresh_process_evidence = {
                "path": str(evidence_path),
                "evidenceSha256": evidence_envelope["evidenceSha256"],
                "requestSha256": request_sha,
                "renderIdentitySha256": request["renderIdentitySha256"],
                "resultSha256": result_envelope["resultSha256"],
                "outputSha256": result["outputSha256"],
            }
            return ReferenceRender(
                output_path,
                result["eventPath"],
                dict(result["parameters"]),
                None if result["startParameters"] is None else dict(result["startParameters"]),
                48000,
                2,
                16,
                duration_frames,
                tuple(names),
                tuple(tuple(items) for items in names_by_take),
            )

    @staticmethod
    def _macos_api_root() -> Path:
        configured = os.environ.get(FMOD_API_ROOT_ENV)
        if configured:
            return Path(configured).expanduser().resolve()
        return Path.home() / "Downloads" / "FMOD Programmers API" / "api"

    def _load_runtime(self) -> tuple[object, object, int, object | None]:
        if os.name == "nt":
            cookie = os.add_dll_directory(str(self.root))
            return (
                C.WinDLL(str(self.root / "fmod64.dll")),
                C.WinDLL(str(self.root / "fmodstudio64.dll")),
                FMOD_VERSION,
                cookie,
            )
        if sys.platform == "darwin":
            api_root = self._macos_api_root()
            core_path = api_root / "lowlevel" / "lib" / "libfmod.dylib"
            studio_path = api_root / "studio" / "lib" / "libfmodstudio.dylib"
            for required in (core_path, studio_path):
                if not required.is_file():
                    raise FileNotFoundError(required)
            core = C.CDLL(str(core_path), mode=C.RTLD_GLOBAL)
            return core, C.CDLL(str(studio_path)), FMOD_MACOS_VERSION, None
        raise FmodError(f"the FMOD oracle is not configured for {sys.platform}")

    def _bind(self, core: object, studio: object) -> None:
        signatures = {
            "FMOD_Studio_System_Create": ([C.POINTER(C.c_void_p), C.c_uint], C.c_int),
            "FMOD_Studio_System_GetLowLevelSystem": (
                [C.c_void_p, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_System_Initialize": (
                [C.c_void_p, C.c_int, C.c_uint, C.c_uint, C.c_void_p],
                C.c_int,
            ),
            "FMOD_Studio_System_RegisterPlugin": (
                [C.c_void_p, C.POINTER(DspDescription)],
                C.c_int,
            ),
            "FMOD_Studio_System_LoadBankFile": (
                [C.c_void_p, C.c_char_p, C.c_uint, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_System_GetEventByID": (
                [C.c_void_p, C.POINTER(Guid), C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_Bank_GetEventCount": (
                [C.c_void_p, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_Bank_GetEventList": (
                [C.c_void_p, C.POINTER(C.c_void_p), C.c_int, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_EventDescription_GetPath": (
                [C.c_void_p, C.c_char_p, C.c_int, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_EventDescription_CreateInstance": (
                [C.c_void_p, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_EventDescription_LoadSampleData": ([C.c_void_p], C.c_int),
            "FMOD_Studio_EventInstance_SetParameterValue": (
                [C.c_void_p, C.c_char_p, C.c_float],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_GetParameterValue": (
                [
                    C.c_void_p,
                    C.c_char_p,
                    C.POINTER(C.c_float),
                    C.POINTER(C.c_float),
                ],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_SetVolume": ([C.c_void_p, C.c_float], C.c_int),
            "FMOD_Studio_EventInstance_SetPaused": ([C.c_void_p, C.c_int], C.c_int),
            "FMOD_Studio_EventInstance_Set3DAttributes": (
                [C.c_void_p, C.POINTER(Attributes3D)],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_SetTimelinePosition": (
                [C.c_void_p, C.c_int],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_SetCallback": (
                [C.c_void_p, EventCallback, C.c_uint],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_Start": ([C.c_void_p], C.c_int),
            "FMOD_Studio_EventInstance_GetChannelGroup": (
                [C.c_void_p, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_EventInstance_Stop": ([C.c_void_p, C.c_int], C.c_int),
            "FMOD_Studio_EventInstance_Release": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_SetListenerAttributes": (
                [C.c_void_p, C.c_int, C.POINTER(Attributes3D)],
                C.c_int,
            ),
            "FMOD_Studio_System_Update": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_FlushCommands": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_FlushSampleLoading": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_UnloadAll": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_Release": ([C.c_void_p], C.c_int),
        }
        for name, (args, result) in signatures.items():
            function = getattr(studio, name)
            function.argtypes = args
            function.restype = result
        core.FMOD_System_SetOutput.argtypes = [C.c_void_p, C.c_int]
        core.FMOD_System_SetOutput.restype = C.c_int
        core.FMOD_System_SetSoftwareFormat.argtypes = [C.c_void_p, C.c_int, C.c_int, C.c_int]
        core.FMOD_System_SetSoftwareFormat.restype = C.c_int
        core.FMOD_System_SetDSPBufferSize.argtypes = [C.c_void_p, C.c_uint, C.c_int]
        core.FMOD_System_SetDSPBufferSize.restype = C.c_int
        core.FMOD_System_SetSoftwareChannels.argtypes = [C.c_void_p, C.c_int]
        core.FMOD_System_SetSoftwareChannels.restype = C.c_int
        core.FMOD_System_GetChannelsPlaying.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
            C.POINTER(C.c_int),
        ]
        core.FMOD_System_GetChannelsPlaying.restype = C.c_int
        core.FMOD_System_GetChannel.argtypes = [
            C.c_void_p,
            C.c_int,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_System_GetChannel.restype = C.c_int
        core.FMOD_Channel_GetCurrentSound.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_Channel_GetCurrentSound.restype = C.c_int
        core.FMOD_Channel_IsVirtual.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_int),
        ]
        core.FMOD_Channel_IsVirtual.restype = C.c_int
        core.FMOD_Channel_SetPosition.argtypes = [
            C.c_void_p,
            C.c_uint,
            C.c_uint,
        ]
        core.FMOD_Channel_SetPosition.restype = C.c_int
        core.FMOD_Channel_GetVolume.argtypes = [C.c_void_p, C.POINTER(C.c_float)]
        core.FMOD_Channel_GetVolume.restype = C.c_int
        core.FMOD_Channel_SetVolume.argtypes = [C.c_void_p, C.c_float]
        core.FMOD_Channel_SetVolume.restype = C.c_int
        core.FMOD_Channel_GetAudibility.argtypes = [C.c_void_p, C.POINTER(C.c_float)]
        core.FMOD_Channel_GetAudibility.restype = C.c_int
        core.FMOD_Channel_GetChannelGroup.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_Channel_GetChannelGroup.restype = C.c_int
        core.FMOD_ChannelGroup_GetName.argtypes = [C.c_void_p, C.c_char_p, C.c_int]
        core.FMOD_ChannelGroup_GetName.restype = C.c_int
        core.FMOD_ChannelGroup_GetVolume.argtypes = [C.c_void_p, C.POINTER(C.c_float)]
        core.FMOD_ChannelGroup_GetVolume.restype = C.c_int
        core.FMOD_ChannelGroup_GetParentGroup.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        core.FMOD_ChannelGroup_GetParentGroup.restype = C.c_int
        core.FMOD_Sound_GetName.argtypes = [C.c_void_p, C.c_char_p, C.c_int]
        core.FMOD_Sound_GetName.restype = C.c_int

    def _render_mix_writer(
        self,
        bank_path: Path,
        writer_path: Path,
        events: Sequence[Mapping[str, Any]],
        requested_frames: int,
        emitter_position: tuple[float, float, float],
        listener_position: tuple[float, float, float],
    ) -> tuple[
        tuple[str, ...],
        tuple[str, ...],
        tuple[tuple[str, ...], ...],
        tuple[dict[str, Any], ...],
    ]:
        """Write a block-scheduled shared-system event mix to a WAV writer."""

        core, studio, runtime_version, cookie = self._load_runtime()
        self._bind(core, studio)
        system = C.c_void_p()
        loaded: list[C.c_void_p] = []
        live_instances: list[C.c_void_p] = []
        event_paths: list[str] = []
        scheduled_sound_names: list[str] = []
        scheduled_by_instance: list[list[str]] = [[] for _ in events]
        instance_ordinals: dict[int, int] = {}
        channel_snapshots: list[dict[str, Any]] = []
        callback_frame = -1
        callbacks_by_frame: dict[int, list[dict[str, Any]]] = {}

        @EventCallback
        def sound_played_callback(
            callback_type: int, event_instance_pointer: int, parameters_pointer: int
        ) -> int:
            if (
                callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                and parameters_pointer
            ):
                name = C.create_string_buffer(1024)
                if core.FMOD_Sound_GetName(
                    C.c_void_p(parameters_pointer), name, len(name)
                ) == 0:
                    decoded = name.value.decode("utf-8", "replace")
                    scheduled_sound_names.append(decoded)
                    ordinal = instance_ordinals.get(int(event_instance_pointer or 0))
                    if ordinal is not None:
                        scheduled_by_instance[ordinal].append(decoded)
                        callbacks_by_frame.setdefault(callback_frame, []).append(
                            {
                                "eventOrdinal": ordinal,
                                "soundName": decoded,
                            }
                        )
            return 0

        self._check(
            studio.FMOD_Studio_System_Create(C.byref(system), runtime_version),
            "create mix writer Studio system",
        )
        try:
            low_level = C.c_void_p()
            self._check(
                studio.FMOD_Studio_System_GetLowLevelSystem(system, C.byref(low_level)),
                "get mix writer low-level system",
            )
            self._check(
                core.FMOD_System_SetOutput(low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT),
                "select mix non-realtime WAV writer",
            )
            self._check(
                core.FMOD_System_SetSoftwareFormat(
                    low_level, 48_000, FMOD_SPEAKERMODE_STEREO, 0
                ),
                "force mix reference software format",
            )
            self._check(
                core.FMOD_System_SetDSPBufferSize(low_level, self.dsp_buffer_frames, 4),
                "set mix reference DSP buffer",
            )
            self._check(
                core.FMOD_System_SetSoftwareChannels(
                    low_level, ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
                ),
                "set Assetto real software-channel budget",
            )
            writer_name = C.create_string_buffer(str(writer_path).encode("utf-8"))
            self._check(
                studio.FMOD_Studio_System_Initialize(
                    system,
                    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                    0,
                    0,
                    C.cast(writer_name, C.c_void_p),
                ),
                "initialize mix non-realtime WAV writer",
            )
            dsp_description, dsp_keepalive = _distance_filter_description()
            self._check(
                studio.FMOD_Studio_System_RegisterPlugin(system, C.byref(dsp_description)),
                "register mix AC distance filter descriptor",
            )
            gain_description, gain_keepalive = _gain_description()
            self._check(
                studio.FMOD_Studio_System_RegisterPlugin(system, C.byref(gain_description)),
                "register mix M3 E30 Gr.A gain descriptor",
            )
            for path in (
                self.root / "content" / "sfx" / "common.strings.bank",
                self.root / "content" / "sfx" / "common.bank",
            ):
                bank = C.c_void_p()
                self._check(
                    studio.FMOD_Studio_System_LoadBankFile(
                        system, str(path).encode("utf-8"), 0, C.byref(bank)
                    ),
                    f"load mix {path.name}",
                )
                loaded.append(bank)
            # Only load banks selected by an event instance.  Loading the
            # original car bank alongside an isolated derivative with the same
            # FMOD event GUID is rejected by Studio even when no instance uses
            # the original description.
            requested_banks = [str(event["bankPath"]) for event in events]
            descriptions_by_bank_suffix: dict[tuple[str, str], tuple[C.c_void_p, str]] = {}
            for bank_name in dict.fromkeys(requested_banks):
                path = Path(bank_name).resolve()
                if not path.is_file():
                    raise FileNotFoundError(path)
                car_bank = C.c_void_p()
                self._check(
                    studio.FMOD_Studio_System_LoadBankFile(
                        system, str(path).encode("utf-8"), 0, C.byref(car_bank)
                    ),
                    f"load mix {path.name}",
                )
                loaded.append(car_bank)
                count = C.c_int()
                self._check(
                    studio.FMOD_Studio_Bank_GetEventCount(car_bank, C.byref(count)),
                    "count mix reference events",
                )
                descriptions = (C.c_void_p * max(1, count.value))()
                actual = C.c_int()
                self._check(
                    studio.FMOD_Studio_Bank_GetEventList(
                        car_bank, descriptions, count.value, C.byref(actual)
                    ),
                    "list mix reference events",
                )
                for description in descriptions[: actual.value]:
                    needed = C.c_int()
                    if studio.FMOD_Studio_EventDescription_GetPath(
                        description, None, 0, C.byref(needed)
                    ):
                        continue
                    buffer = C.create_string_buffer(max(1, needed.value))
                    if studio.FMOD_Studio_EventDescription_GetPath(
                        description, buffer, len(buffer), C.byref(needed)
                    ):
                        continue
                    event_path = buffer.value.decode("utf-8", "replace")
                    suffix = event_path.rsplit("/", 1)[-1].casefold()
                    key = (str(path), suffix)
                    if key in descriptions_by_bank_suffix:
                        raise FmodError(f"mix event suffix {suffix!r} is ambiguous")
                    descriptions_by_bank_suffix[key] = (C.c_void_p(description), event_path)
            resolved: list[tuple[Mapping[str, Any], C.c_void_p, str]] = []
            for event in events:
                wanted = str(event["eventName"]).casefold().removeprefix("event:")
                suffix = wanted.rsplit("/", 1)[-1]
                selected = descriptions_by_bank_suffix.get(
                    (str(event["bankPath"]), suffix)
                )
                # Private AC banks commonly omit string-table event paths.
                # The normal one-event renderer resolves these with the
                # adjacent GUIDs.txt file; shared-system renders must use the
                # same fallback or their dynamic/combined oracle path silently
                # excludes exactly those banks.  A derivative isolated bank
                # carries the original FMOD event GUID, so callers may supply
                # the source bank solely for GUID lookup.
                if selected is None:
                    guid_file = Path(event["eventIdLookupBankPath"]).parent / "GUIDs.txt"
                    if guid_file.is_file():
                        candidates: list[tuple[Guid, str]] = []
                        for line in guid_file.read_text(
                            encoding="utf-8-sig", errors="replace"
                        ).splitlines():
                            parts = line.split(None, 1)
                            if len(parts) != 2 or not parts[1].startswith("event:/"):
                                continue
                            if parts[1].casefold() == str(event["eventName"]).casefold():
                                candidates.append((Guid.parse(parts[0]), parts[1]))
                        if len(candidates) > 1:
                            raise FmodError(
                                f"mix event {event['eventName']!r} is ambiguous in {guid_file.name}"
                            )
                        if candidates:
                            guid, event_path = candidates[0]
                            description = C.c_void_p()
                            self._check(
                                studio.FMOD_Studio_System_GetEventByID(
                                    system, C.byref(guid), C.byref(description)
                                ),
                                f"resolve mix {event_path} by GUID",
                            )
                            selected = (description, event_path)
                if selected is None:
                    raise FmodError(
                        f"mix event {event['eventName']!r} is absent from {bank_path.name}"
                    )
                description, path = selected
                self._check(
                    studio.FMOD_Studio_EventDescription_LoadSampleData(description),
                    f"load sample data for mix {path}",
                )
                resolved.append((event, description, path))
                event_paths.append(path)
            self._check(
                studio.FMOD_Studio_System_FlushSampleLoading(system),
                "finish mix reference sample loading",
            )
            emitter = _attributes(emitter_position)
            listener = _attributes(listener_position)
            self._check(
                studio.FMOD_Studio_System_SetListenerAttributes(
                    system, 0, C.byref(listener)
                ),
                "place mix reference listener",
            )
            instances_by_ordinal: dict[int, C.c_void_p] = {}
            paths_by_ordinal: dict[int, str] = {}
            for event, description, path in resolved:
                ordinal = int(event["ordinal"])
                instance = C.c_void_p()
                self._check(
                    studio.FMOD_Studio_EventDescription_CreateInstance(
                        description, C.byref(instance)
                    ),
                    f"create mix {path} instance {ordinal}",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Set3DAttributes(
                        instance, C.byref(emitter)
                    ),
                    "place mix reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_SetVolume(
                        instance, float(event["hostGainLinear"])
                    ),
                    "set mix event host gain",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_SetCallback(
                        instance,
                        sound_played_callback,
                        FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                    ),
                    "observe scheduled mix sounds",
                )
                for name, value in sorted(event["parameters"].items()):
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetParameterValue(
                            instance, name.encode("ascii"), value
                        ),
                        f"set mix parameter {name}",
                    )
                instance_ordinals[int(instance.value or 0)] = ordinal
                instances_by_ordinal[ordinal] = instance
                paths_by_ordinal[ordinal] = path
                live_instances.append(instance)
            starts: dict[int, list[tuple[Mapping[str, Any], C.c_void_p, str]]] = {}
            updates: dict[int, list[tuple[Mapping[str, Any], C.c_void_p, str]]] = {}
            stops: dict[int, list[tuple[Mapping[str, Any], C.c_void_p, str]]] = {}
            for item in resolved:
                for action in item[0]["lifecycleActions"]:
                    if action["action"] == "start":
                        starts.setdefault(int(action["frame"]), []).append(item)
                    else:
                        stops.setdefault(int(action["frame"]), []).append(
                            (
                                {
                                    **item[0],
                                    "stopMode": (
                                        "FMOD_STUDIO_STOP_ALLOWFADEOUT"
                                        if action["action"] == "stopAllowFadeout"
                                        else "FMOD_STUDIO_STOP_IMMEDIATE"
                                    ),
                                },
                                item[1],
                                item[2],
                            )
                        )
                for update_frame, _values in item[0]["parameterUpdates"]:
                    updates.setdefault(int(update_frame), []).append(item)
            update_count = math.ceil(requested_frames / self.dsp_buffer_frames)
            for update_index in range(update_count):
                frame = update_index * self.dsp_buffer_frames
                callback_frame = frame
                updated_ordinals: list[int] = []
                for event, _description, _path in updates.get(frame, []):
                    instance = instances_by_ordinal.get(int(event["ordinal"]))
                    if instance is None:
                        raise FmodError(
                            f"mix parameter update precedes live event instance {event['ordinal']}"
                        )
                    values = next(
                        values
                        for update_frame, values in event["parameterUpdates"]
                        if update_frame == frame
                    )
                    for name, value in sorted(values.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"set scheduled mix parameter {name}",
                        )
                    updated_ordinals.append(int(event["ordinal"]))
                for event, description, path in starts.get(frame, []):
                    instance = instances_by_ordinal[int(event["ordinal"])]
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetTimelinePosition(instance, 0),
                        "rewind mix event",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Start(instance),
                        "start mix event",
                    )
                stopped_ordinals: list[int] = []
                for event, _description, path in stops.get(frame, []):
                    instance = instances_by_ordinal.get(int(event["ordinal"]))
                    if instance is None:
                        raise FmodError(
                            f"mix stop precedes live event instance {event['ordinal']}"
                        )
                    stop_mode = (
                        FMOD_STUDIO_STOP_ALLOWFADEOUT
                        if event["stopMode"] == "FMOD_STUDIO_STOP_ALLOWFADEOUT"
                        else FMOD_STUDIO_STOP_IMMEDIATE
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Stop(instance, stop_mode),
                        f"stop scheduled mix {path} instance {event['ordinal']}",
                    )
                    stopped_ordinals.append(int(event["ordinal"]))
                if starts.get(frame) or updates.get(frame) or stops.get(frame):
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush mix start, stop, or parameter commands",
                    )
                self._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render mix reference buffer",
                )
                logical_channels = C.c_int()
                real_channels = C.c_int()
                self._check(
                    core.FMOD_System_GetChannelsPlaying(
                        low_level,
                        C.byref(logical_channels),
                        C.byref(real_channels),
                    ),
                    "snapshot mix channels playing",
                )
                channel_snapshots.append(
                    {
                        "afterDspBlockStartFrame": frame,
                        "logicalChannels": int(logical_channels.value),
                        "realChannels": int(real_channels.value),
                        # The causal session exporter needs an empirical token
                        # for the exact Studio EventInstance behind each raw
                        # Core channel.  These are deliberately process-local
                        # pointer identities: they are never compared across
                        # renders, only across DSP snapshots in this one
                        # short-lived original-bank session.
                        "eventInstanceTokensByOrdinal": {
                            str(ordinal): int(instance.value or 0)
                            for ordinal, instance in sorted(
                                instances_by_ordinal.items()
                            )
                        },
                        "activeChannels": self._read_mix_active_channels(
                            core,
                            studio,
                            low_level,
                            live_instances,
                            instance_ordinals,
                        ),
                        "soundCallbacks": callbacks_by_frame.pop(frame, []),
                        "startedEventOrdinals": [
                            int(event["ordinal"]) for event, _description, _path in starts.get(frame, [])
                        ],
                        "stoppedEventOrdinals": stopped_ordinals,
                        "updatedEventOrdinals": updated_ordinals,
                    }
                )
            for instance in live_instances:
                self._check(
                    studio.FMOD_Studio_EventInstance_Stop(
                        instance, FMOD_STUDIO_STOP_IMMEDIATE
                    ),
                    "stop mix reference instance",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Release(instance),
                    "release mix reference instance",
                )
            live_instances.clear()
            _ = (dsp_keepalive, gain_keepalive, writer_name, sound_played_callback)
            raw_gain_observations = gain_keepalive[-1]
            self.last_gain_dsp_parameter_observations = {
                kind: tuple(values) for kind, values in raw_gain_observations.items()
            }
        finally:
            for instance in live_instances:
                studio.FMOD_Studio_EventInstance_Stop(instance, FMOD_STUDIO_STOP_IMMEDIATE)
                studio.FMOD_Studio_EventInstance_Release(instance)
            if system:
                studio.FMOD_Studio_System_UnloadAll(system)
                studio.FMOD_Studio_System_Release(system)
            if cookie is not None:
                cookie.close()
        return (
            tuple(event_paths),
            tuple(scheduled_sound_names),
            tuple(tuple(names) for names in scheduled_by_instance),
            tuple(channel_snapshots),
        )

    def _read_mix_active_channels(
        self,
        core: C.CDLL,
        studio: C.CDLL,
        low_level_system: C.c_void_p,
        instances: Sequence[C.c_void_p],
        instance_ordinals: Mapping[int, int],
    ) -> list[dict[str, Any]]:
        """Enumerate every raw Core voice and bind it to its Studio instance.

        Sound callbacks alone cannot distinguish two EventInstances playing the
        same source.  The descendant ChannelGroup chain is the exact runtime
        ownership relation, so causal traces record both identities and fail
        when FMOD exposes a voice outside every requested original-bank event.
        """

        event_group_ordinals: dict[int, int] = {}
        for instance in instances:
            group = C.c_void_p()
            if studio.FMOD_Studio_EventInstance_GetChannelGroup(
                instance,
                C.byref(group),
            ) or not group:
                continue
            ordinal = instance_ordinals.get(int(instance.value or 0))
            if ordinal is None:
                raise FmodError("mix EventInstance has no exact ordinal identity")
            pointer = int(group.value or 0)
            previous = event_group_ordinals.setdefault(pointer, ordinal)
            if previous != ordinal:
                raise FmodError("two mix EventInstances share one channel-group identity")

        channels: list[dict[str, Any]] = []
        for channel_id in range(ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP):
            channel = C.c_void_p()
            if core.FMOD_System_GetChannel(
                low_level_system,
                channel_id,
                C.byref(channel),
            ):
                continue
            sound = C.c_void_p()
            if core.FMOD_Channel_GetCurrentSound(channel, C.byref(sound)) or not sound:
                continue
            name_buffer = C.create_string_buffer(1024)
            self._check(
                core.FMOD_Sound_GetName(sound, name_buffer, len(name_buffer)),
                f"name causal mix sound on channel {channel_id}",
            )
            groups = self._channel_group_evidence(core, channel)
            owners = {
                event_group_ordinals[int(group["pointer"])]
                for group in groups
                if int(group["pointer"]) in event_group_ordinals
            }
            if len(owners) != 1:
                raise FmodError(
                    "causal mix channel has ambiguous/missing EventInstance ownership: "
                    f"channel={channel_id}, sound={name_buffer.value!r}, owners={sorted(owners)}"
                )
            virtual = C.c_int()
            self._check(
                core.FMOD_Channel_IsVirtual(channel, C.byref(virtual)),
                f"read causal mix virtual state on channel {channel_id}",
            )
            audibility = C.c_float()
            self._check(
                core.FMOD_Channel_GetAudibility(channel, C.byref(audibility)),
                f"read causal mix audibility on channel {channel_id}",
            )
            authored_route_gain = 1.0
            for group in groups:
                if group["name"] in {"Master Bus", "Input Bus", "FMOD master"}:
                    break
                volume = group["volume"]
                if volume is None:
                    raise FmodError(
                        f"causal mix route gain is unreadable on channel {channel_id}"
                    )
                authored_route_gain *= float(volume)
            channels.append(
                {
                    "channelId": channel_id,
                    "channelPointer": int(channel.value or 0),
                    "eventOrdinal": next(iter(owners)),
                    "soundName": name_buffer.value.decode("utf-8", "replace"),
                    "isVirtual": bool(virtual.value),
                    "audibility": float(audibility.value),
                    "authoredRouteGain": authored_route_gain,
                    "channelGroups": groups,
                }
            )

        return channels

    def _render_writer(
        self,
        bank_path: Path,
        event_name: str,
        writer_path: Path,
        parameters: dict[str, float],
        start_parameters: dict[str, float] | None,
        parameter_updates: Sequence[tuple[int, Mapping[str, float]]],
        requested_frames: int,
        emitter_position: tuple[float, float, float],
        listener_position: tuple[float, float, float],
        take_count: int,
        event_id_lookup_bank_path: Path,
        take_lifecycle: str,
        channel_capture_plan: _ChannelMaskCapturePlan | None = None,
    ) -> tuple[str, int, tuple[str, ...], tuple[tuple[str, ...], ...]]:
        core, studio, runtime_version, cookie = self._load_runtime()
        self._bind(core, studio)
        system = C.c_void_p()
        instance = C.c_void_p()
        loaded: list[C.c_void_p] = []
        selected_path = ""
        scheduled_sound_names: list[str] = []
        scheduled_sound_names_by_take: list[list[str]] = [
            [] for _index in range(take_count)
        ]
        active_take = 0
        if channel_capture_plan is not None:
            if (
                take_count != 1
                or start_parameters is not None
                or parameter_updates
                or take_lifecycle != "newEventInstancePerTake-v1"
                or requested_frames
                != len(channel_capture_plan.masks) * channel_capture_plan.segment_frames
            ):
                raise ValueError("channel-mask capture plan does not match the writer request")

        @EventCallback
        def sound_played_callback(
            callback_type: int, _event: int, parameters_pointer: int
        ) -> int:
            if (
                callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                and parameters_pointer
            ):
                name = C.create_string_buffer(1024)
                if core.FMOD_Sound_GetName(
                    C.c_void_p(parameters_pointer), name, len(name)
                ) == 0:
                    scheduled_sound_names.append(name.value.decode("utf-8", "replace"))
                    scheduled_sound_names_by_take[active_take].append(
                        name.value.decode("utf-8", "replace")
                    )
            return 0
        self._check(
            studio.FMOD_Studio_System_Create(C.byref(system), runtime_version),
            "create writer Studio system",
        )
        try:
            low_level = C.c_void_p()
            self._check(
                studio.FMOD_Studio_System_GetLowLevelSystem(system, C.byref(low_level)),
                "get writer low-level system",
            )
            self._check(
                core.FMOD_System_SetOutput(low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT),
                "select non-realtime WAV writer",
            )
            self._check(
                core.FMOD_System_SetSoftwareFormat(
                    low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0
                ),
                "force reference software format",
            )
            self._check(
                core.FMOD_System_SetDSPBufferSize(low_level, self.dsp_buffer_frames, 4),
                "set reference DSP buffer",
            )
            self._check(
                core.FMOD_System_SetSoftwareChannels(
                    low_level, ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
                ),
                "set Assetto real software-channel budget",
            )
            writer_name = C.create_string_buffer(str(writer_path).encode("utf-8"))
            self._check(
                studio.FMOD_Studio_System_Initialize(
                    system,
                    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                    0,
                    0,
                    C.cast(writer_name, C.c_void_p),
                ),
                "initialize non-realtime WAV writer",
            )
            dsp_description, dsp_keepalive = _distance_filter_description()
            self._check(
                studio.FMOD_Studio_System_RegisterPlugin(
                    system, C.byref(dsp_description)
                ),
                "register AC distance filter descriptor",
            )
            gain_description, gain_keepalive = _gain_description()
            self._check(
                studio.FMOD_Studio_System_RegisterPlugin(
                    system, C.byref(gain_description)
                ),
                "register M3 E30 Gr.A gain descriptor",
            )
            for path in (
                self.root / "content" / "sfx" / "common.strings.bank",
                self.root / "content" / "sfx" / "common.bank",
                bank_path,
            ):
                bank = C.c_void_p()
                self._check(
                    studio.FMOD_Studio_System_LoadBankFile(
                        system, str(path).encode("utf-8"), 0, C.byref(bank)
                    ),
                    f"load {path.name}",
                )
                loaded.append(bank)
            car_bank = loaded[-1]
            count = C.c_int()
            self._check(
                studio.FMOD_Studio_Bank_GetEventCount(car_bank, C.byref(count)),
                "count reference events",
            )
            descriptions = (C.c_void_p * max(1, count.value))()
            actual = C.c_int()
            self._check(
                studio.FMOD_Studio_Bank_GetEventList(
                    car_bank, descriptions, count.value, C.byref(actual)
                ),
                "list reference events",
            )
            selected: C.c_void_p | None = None
            wanted = event_name.casefold().removeprefix("event:")
            wanted = wanted.rsplit("/", 1)[-1]
            for description in descriptions[: actual.value]:
                needed = C.c_int()
                studio.FMOD_Studio_EventDescription_GetPath(
                    description, None, 0, C.byref(needed)
                )
                buffer = C.create_string_buffer(max(1, needed.value))
                if studio.FMOD_Studio_EventDescription_GetPath(
                    description, buffer, len(buffer), C.byref(needed)
                ):
                    continue
                path = buffer.value.decode("utf-8", "replace")
                if path.rsplit("/", 1)[-1].casefold() == wanted:
                    selected = C.c_void_p(description)
                    selected_path = path
                    break
            # Private AC cars often omit a strings bank but ship FMOD Studio's
            # GUIDs.txt beside the bank. Resolve the same event description by
            # GUID only when the normal runtime path lookup found nothing.
            if selected is None:
                guid_file = event_id_lookup_bank_path.parent / "GUIDs.txt"
                if guid_file.is_file():
                    candidates: list[tuple[Guid, str]] = []
                    for line in guid_file.read_text(
                        encoding="utf-8-sig", errors="replace"
                    ).splitlines():
                        parts = line.split(None, 1)
                        if len(parts) != 2 or not parts[1].startswith("event:/"):
                            continue
                        path = parts[1]
                        if path.casefold() == event_name.casefold():
                            candidates.append((Guid.parse(parts[0]), path))
                    if len(candidates) > 1:
                        raise FmodError(
                            f"event name {event_name!r} is ambiguous in {guid_file.name}"
                        )
                    if candidates:
                        guid, selected_path = candidates[0]
                        description = C.c_void_p()
                        self._check(
                            studio.FMOD_Studio_System_GetEventByID(
                                system, C.byref(guid), C.byref(description)
                            ),
                            f"resolve {selected_path} by GUID",
                        )
                        selected = description
            if selected is None:
                raise FmodError(f"event {event_name!r} is absent from {bank_path.name}")
            self._check(
                studio.FMOD_Studio_EventDescription_LoadSampleData(selected),
                f"load sample data for {selected_path}",
            )
            self._check(
                studio.FMOD_Studio_System_FlushSampleLoading(system),
                "finish reference sample loading",
            )
            emitter = _attributes(emitter_position)
            listener = _attributes(listener_position)
            self._check(
                studio.FMOD_Studio_System_SetListenerAttributes(
                    system, 0, C.byref(listener)
                ),
                "place reference listener",
            )
            update_count = math.ceil(requested_frames / self.dsp_buffer_frames)
            updates_by_frame = dict(parameter_updates)
            start_update_count = (
                1
                if start_parameters is not None
                and take_lifecycle
                != "singleEventInstanceInitialInsideThenPlacementReentry-v1"
                else 0
            )
            take_stride = (
                update_count + start_update_count
            ) * self.dsp_buffer_frames
            if take_lifecycle == "singleEventInstanceStopRewindStart-v1":
                # Each stop is committed by one System_Update before the next
                # rewind/start take, so later WAV crops must skip that block.
                take_stride += self.dsp_buffer_frames
            channel_capture_prelude_frames = 0
            if take_lifecycle == "singleEventInstancePlacementReentry-v1":
                # PlaySequential state is scoped to an EventInstance.  Keep one
                # instance alive and make every take an authored
                # outside-to-inside placement entry; Stop/Start would reset or
                # otherwise perturb that scheduler state.
                self._check(
                    studio.FMOD_Studio_EventDescription_CreateInstance(
                        selected, C.byref(instance)
                    ),
                    f"create {selected_path} placement-reentry instance",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Set3DAttributes(
                        instance, C.byref(emitter)
                    ),
                    "place placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_SetCallback(
                        instance,
                        sound_played_callback,
                        FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                    ),
                    "observe placement-reentry reference sounds",
                )
                for name, value in sorted(start_parameters.items()):
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetParameterValue(
                            instance, name.encode("ascii"), value
                        ),
                        f"set placement-reentry outside parameter {name}",
                    )
                self._check(
                    studio.FMOD_Studio_EventInstance_Start(instance),
                    "start placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_System_FlushCommands(system),
                    "flush placement-reentry outside command",
                )
                self._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render placement-reentry outside buffer",
                )
                for take in range(take_count):
                    active_take = take
                    for name, value in sorted(parameters.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"set placement-reentry capture parameter {name}",
                        )
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush placement-reentry capture parameters",
                    )
                    for _update_index in range(update_count):
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render placement-reentry reference buffer",
                        )
                    for name, value in sorted(start_parameters.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"restore placement-reentry outside parameter {name}",
                        )
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush placement-reentry outside parameters",
                    )
                    self._check(
                        studio.FMOD_Studio_System_Update(system),
                        "render placement-reentry exit buffer",
                    )
                self._check(
                    studio.FMOD_Studio_EventInstance_Stop(
                        instance, FMOD_STUDIO_STOP_IMMEDIATE
                    ),
                    "stop placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Release(instance),
                    "release placement-reentry reference event",
                )
                instance = C.c_void_p()
            elif take_lifecycle == "singleEventInstanceInitialInsideThenPlacementReentry-v1":
                # This keeps one EventInstance alive long enough to observe a
                # real initial-inside activation *and* every later
                # outside-to-inside scheduler selection.  ``start_parameters``
                # is the initial/inside state; ``parameters`` is the known
                # outside state restored between takes.  The first callback is
                # candidate-set evidence only for playlists; later exact target
                # takes prove each individual authored binding without
                # pretending a fixed FMOD random seed selects every sibling at
                # initial Start.
                take_stride += self.dsp_buffer_frames
                self._check(
                    studio.FMOD_Studio_EventDescription_CreateInstance(
                        selected, C.byref(instance)
                    ),
                    f"create {selected_path} initial-inside placement-reentry instance",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Set3DAttributes(
                        instance, C.byref(emitter)
                    ),
                    "place initial-inside placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_SetCallback(
                        instance,
                        sound_played_callback,
                        FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                    ),
                    "observe initial-inside placement-reentry reference sounds",
                )
                for name, value in sorted(start_parameters.items()):
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetParameterValue(
                            instance, name.encode("ascii"), value
                        ),
                        f"set initial-inside parameter {name}",
                    )
                self._check(
                    studio.FMOD_Studio_EventInstance_Start(instance),
                    "start initial-inside placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_System_FlushCommands(system),
                    "flush initial-inside placement-reentry start",
                )
                for take in range(take_count):
                    active_take = take
                    for _update_index in range(update_count):
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render initial-inside placement-reentry reference buffer",
                        )
                    for name, value in sorted(parameters.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"set initial-inside placement-reentry outside parameter {name}",
                        )
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush initial-inside placement-reentry outside parameters",
                    )
                    self._check(
                        studio.FMOD_Studio_System_Update(system),
                        "render initial-inside placement-reentry exit buffer",
                    )
                    if take + 1 < take_count:
                        # FMOD may deliver SOUND_PLAYED while flushing the
                        # next inside parameter write.  Attribute that callback
                        # to the next output segment, never to the preceding
                        # outside/exit segment.
                        active_take = take + 1
                        for name, value in sorted(start_parameters.items()):
                            self._check(
                                studio.FMOD_Studio_EventInstance_SetParameterValue(
                                    instance, name.encode("ascii"), value
                                ),
                                f"restore initial-inside placement-reentry parameter {name}",
                            )
                        self._check(
                            studio.FMOD_Studio_System_FlushCommands(system),
                            "flush initial-inside placement-reentry parameters",
                        )
                self._check(
                    studio.FMOD_Studio_EventInstance_Stop(
                        instance, FMOD_STUDIO_STOP_IMMEDIATE
                    ),
                    "stop initial-inside placement-reentry reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Release(instance),
                    "release initial-inside placement-reentry reference event",
                )
                instance = C.c_void_p()
            elif take_lifecycle == "singleEventInstanceStopRewindStart-v1":
                # NativeFmodAudio's event restart path rewinds then starts the
                # same EventInstance.  This probe deliberately differs from
                # both a fresh instance and a placement reentry so callers can
                # observe cursor/RNG and initial-placement latch scope.
                self._check(
                    studio.FMOD_Studio_EventDescription_CreateInstance(
                        selected, C.byref(instance)
                    ),
                    f"create {selected_path} stop-rewind-start instance",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_Set3DAttributes(
                        instance, C.byref(emitter)
                    ),
                    "place stop-rewind-start reference event",
                )
                self._check(
                    studio.FMOD_Studio_EventInstance_SetCallback(
                        instance,
                        sound_played_callback,
                        FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                    ),
                    "observe stop-rewind-start reference sounds",
                )
                for take in range(take_count):
                    active_take = take
                    initial_values = start_parameters or parameters
                    for name, value in sorted(initial_values.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"set stop-rewind-start parameter {name}",
                        )
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetTimelinePosition(instance, 0),
                        "rewind stop-rewind-start reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Start(instance),
                        "start stop-rewind-start reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush stop-rewind-start command",
                    )
                    if start_parameters is not None:
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render stop-rewind-start staged buffer",
                        )
                        for name, value in sorted(parameters.items()):
                            self._check(
                                studio.FMOD_Studio_EventInstance_SetParameterValue(
                                    instance, name.encode("ascii"), value
                                ),
                                f"set stop-rewind-start capture parameter {name}",
                            )
                        self._check(
                            studio.FMOD_Studio_System_FlushCommands(system),
                            "flush stop-rewind-start capture parameters",
                        )
                    for _update_index in range(update_count):
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render stop-rewind-start reference buffer",
                        )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Stop(
                            instance, FMOD_STUDIO_STOP_IMMEDIATE
                        ),
                        "stop stop-rewind-start reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_System_Update(system),
                        "commit stop-rewind-start reference stop",
                    )
                self._check(
                    studio.FMOD_Studio_EventInstance_Release(instance),
                    "release stop-rewind-start reference event",
                )
                instance = C.c_void_p()
            else:
                for take in range(take_count):
                    active_take = take
                    self._check(
                        studio.FMOD_Studio_EventDescription_CreateInstance(
                            selected, C.byref(instance)
                        ),
                        f"create {selected_path} take {take}",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Set3DAttributes(
                            instance, C.byref(emitter)
                        ),
                        "place reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetCallback(
                            instance,
                            sound_played_callback,
                            FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
                        ),
                        "observe scheduled reference sounds",
                    )
                    initial_values = start_parameters or parameters
                    for name, value in sorted(initial_values.items()):
                        self._check(
                            studio.FMOD_Studio_EventInstance_SetParameterValue(
                                instance, name.encode("ascii"), value
                            ),
                            f"set reference parameter {name}",
                        )
                    self._check(
                        studio.FMOD_Studio_EventInstance_SetTimelinePosition(instance, 0),
                        "rewind reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Start(instance),
                        "start reference event",
                    )
                    self._check(
                        studio.FMOD_Studio_System_FlushCommands(system),
                        "flush reference start command",
                    )
                    mask_channels: dict[int, _MaskChannel] | None = None
                    if channel_capture_plan is not None:
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "materialize channel-mask reference voices",
                        )
                        channel_capture_prelude_frames += self.dsp_buffer_frames
                        mask_channels, calibration_update_blocks = self._snapshot_mask_channels(
                            core,
                            low_level,
                            studio,
                            system,
                            instance,
                            channel_capture_plan.sound_role_candidates,
                            parameters,
                        )
                        channel_capture_prelude_frames += (
                            calibration_update_blocks * self.dsp_buffer_frames
                        )
                        self.last_channel_mask_sound_names = tuple(
                            sorted({channel.sound_name for channel in mask_channels.values()})
                        )
                    if start_parameters is not None:
                        # Let FMOD evaluate event-start-only parameter sheets at a
                        # graph-proven outside point before entering the target's
                        # authored operating region.  This isolates a continuous
                        # bed from unrelated finite event-start transients without
                        # muting or otherwise changing the retained instrument.
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render staged event-start buffer",
                        )
                        for name, value in sorted(parameters.items()):
                            self._check(
                                studio.FMOD_Studio_EventInstance_SetParameterValue(
                                    instance, name.encode("ascii"), value
                                ),
                                f"set staged capture parameter {name}",
                            )
                        self._check(
                            studio.FMOD_Studio_System_FlushCommands(system),
                            "flush staged capture parameters",
                        )
                    for update_index in range(update_count):
                        if channel_capture_plan is not None:
                            segment_blocks = (
                                channel_capture_plan.segment_frames
                                // self.dsp_buffer_frames
                            )
                            if update_index % segment_blocks == 0:
                                mask_index = update_index // segment_blocks
                                _label, role_gains = channel_capture_plan.masks[mask_index]
                                self._apply_channel_mask(
                                    core,
                                    low_level,
                                    mask_channels or {},
                                    channel_capture_plan.sound_role_candidates,
                                    role_gains,
                                    reset_positions=True,
                                )
                            else:
                                _label, role_gains = channel_capture_plan.masks[0]
                                self._apply_channel_mask(
                                    core,
                                    low_level,
                                    mask_channels or {},
                                    channel_capture_plan.sound_role_candidates,
                                    role_gains,
                                    reset_positions=False,
                                )
                        scheduled = updates_by_frame.get(
                            update_index * self.dsp_buffer_frames
                        )
                        if scheduled is not None:
                            for name, value in sorted(scheduled.items()):
                                self._check(
                                    studio.FMOD_Studio_EventInstance_SetParameterValue(
                                        instance, name.encode("ascii"), value
                                    ),
                                    f"set scheduled reference parameter {name}",
                                )
                            self._check(
                                studio.FMOD_Studio_System_FlushCommands(system),
                                "flush scheduled reference parameters",
                            )
                        self._check(
                            studio.FMOD_Studio_System_Update(system),
                            "render reference buffer",
                        )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Stop(
                            instance, FMOD_STUDIO_STOP_IMMEDIATE
                        ),
                        "stop reference take",
                    )
                    self._check(
                        studio.FMOD_Studio_EventInstance_Release(instance),
                        "release reference take",
                    )
                    instance = C.c_void_p()
            # Keep the descriptor/callback and writer-name buffer alive until
            # the final update has completed.
            _ = (
                dsp_keepalive,
                gain_keepalive,
                writer_name,
                sound_played_callback,
            )
            raw_gain_observations = gain_keepalive[-1]
            self.last_gain_dsp_parameter_observations = {
                kind: tuple(values)
                for kind, values in raw_gain_observations.items()
            }
            if any(index != 0 for index, _value in raw_gain_observations["float"]) or any(
                index != 1 for index, _value in raw_gain_observations["bool"]
            ):
                raise FmodError(
                    "FMOD Gain compatibility DSP received an unknown parameter index"
                )
        finally:
            if instance:
                studio.FMOD_Studio_EventInstance_Stop(instance, FMOD_STUDIO_STOP_IMMEDIATE)
                studio.FMOD_Studio_EventInstance_Release(instance)
            if system:
                studio.FMOD_Studio_System_UnloadAll(system)
                studio.FMOD_Studio_System_Release(system)
            if cookie is not None:
                cookie.close()
        if channel_capture_plan is not None:
            take_stride += channel_capture_prelude_frames
        return (
            selected_path,
            take_stride,
            tuple(scheduled_sound_names),
            tuple(tuple(items) for items in scheduled_sound_names_by_take),
        )

    def _snapshot_mask_channels(
        self,
        core: C.CDLL,
        low_level_system: C.c_void_p,
        studio: C.CDLL,
        studio_system: C.c_void_p,
        instance: C.c_void_p,
        sound_role_candidates: Mapping[str, tuple[str, ...]],
        target_parameters: Mapping[str, float],
    ) -> tuple[dict[int, _MaskChannel], int]:
        initial = self._read_active_mask_channels(
            core,
            low_level_system,
            sound_role_candidates,
        )
        roles_by_channel: dict[int, str] = {}
        probes_by_channel: dict[int, dict[str, float]] = {}
        calibration_update_blocks = 0
        engine_program_channels: list[_MaskChannelEvidence] = []
        for channel in initial.values():
            declared_roles = set(sound_role_candidates[channel.sound_name])
            if "EXCLUDED" in declared_roles:
                if declared_roles != {"EXCLUDED"}:
                    raise FmodError(
                        f"sound {channel.sound_name!r} is shared by finite and continuous "
                        "engine bindings; exact binding identity is required"
                    )
                roles_by_channel[channel.channel_id] = "EXCLUDED"
            else:
                engine_program_channels.append(channel)

        if engine_program_channels:
            resolved, probes, calibration_update_blocks = (
                self._classify_engine_program_mask_channels(
                    core,
                    studio,
                    studio_system,
                    instance,
                    engine_program_channels,
                    target_parameters,
                )
            )
            roles_by_channel.update(resolved)
            probes_by_channel.update(probes)

        roles_by_binding_route: dict[tuple[str, tuple[int, ...]], str] = {}
        for channel_id, role in roles_by_channel.items():
            channel = initial[channel_id]
            key = self._mask_binding_route_key(channel)
            existing_role = roles_by_binding_route.get(key)
            if existing_role is not None and existing_role != role:
                raise FmodError(
                    f"channel-mask binding route for {channel.sound_name!r} has conflicting roles"
                )
            roles_by_binding_route[key] = role

        final = self._read_active_mask_channels(
            core,
            low_level_system,
            sound_role_candidates,
        )
        replacement_routes = {
            self._mask_binding_route_key(channel)
            for channel_id, channel in final.items()
            if channel_id not in initial
            and self._mask_binding_route_key(channel) in roles_by_binding_route
        }
        channels: dict[int, _MaskChannel] = {}
        evidence: list[dict[str, Any]] = []
        for channel_id, channel in final.items():
            binding_route = self._mask_binding_route_key(channel)
            if channel_id in initial and binding_route in replacement_routes:
                continue
            role = roles_by_channel.get(channel_id)
            if role is None:
                role = roles_by_binding_route.get(binding_route)
                if role is None:
                    candidates = set(sound_role_candidates[channel.sound_name])
                    if len(candidates) != 1:
                        raise FmodError(
                            "channel-mask identity calibration created an unclassified "
                            f"{channel.sound_name!r} voice on channel {channel_id}"
                        )
                    role = next(iter(candidates))
            channels[channel_id] = _MaskChannel(
                sound_name=channel.sound_name,
                role=role,
                base_volume=channel.base_volume,
                binding_route=binding_route,
            )
            item = {
                "channelId": channel_id,
                "soundName": channel.sound_name,
                "role": role,
                "baseVolume": channel.base_volume,
                "audibility": channel.audibility,
                "channelGroupPath": tuple(
                    group["name"] for group in channel.channel_groups
                ),
                "channelGroups": channel.channel_groups,
                "declaredRoles": sound_role_candidates[channel.sound_name],
            }
            if channel_id in probes_by_channel:
                item["roleIdentityProbe"] = probes_by_channel[channel_id]
            evidence.append(item)

        missing_core_channels = [
            channel_id
            for channel_id, role in roles_by_channel.items()
            if role != "EXCLUDED"
            and channel_id not in channels
            and self._mask_binding_route_key(initial[channel_id])
            not in {
                self._mask_binding_route_key(channel)
                for channel in final.values()
                if channel.channel_id in channels
            }
        ]
        if missing_core_channels:
            raise FmodError(
                "channel-mask identity calibration changed the continuous channel set: "
                + ", ".join(str(value) for value in sorted(missing_core_channels))
            )
        if not channels:
            raise FmodError("channel-mask capture found no classified active channels")
        self.last_channel_mask_channels = tuple(evidence)
        return channels, calibration_update_blocks

    def _read_active_mask_channels(
        self,
        core: C.CDLL,
        low_level_system: C.c_void_p,
        sound_role_candidates: Mapping[str, tuple[str, ...]],
    ) -> dict[int, _MaskChannelEvidence]:
        channels: dict[int, _MaskChannelEvidence] = {}
        unknown_names: set[str] = set()
        for channel_id in range(ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP):
            channel = C.c_void_p()
            if core.FMOD_System_GetChannel(
                low_level_system,
                channel_id,
                C.byref(channel),
            ):
                continue
            sound = C.c_void_p()
            if core.FMOD_Channel_GetCurrentSound(channel, C.byref(sound)) or not sound:
                continue
            name_buffer = C.create_string_buffer(1024)
            self._check(
                core.FMOD_Sound_GetName(sound, name_buffer, len(name_buffer)),
                f"name channel-mask sound on channel {channel_id}",
            )
            name = name_buffer.value.decode("utf-8", "replace")
            if name not in sound_role_candidates:
                unknown_names.add(name)
                continue
            volume = C.c_float()
            self._check(
                core.FMOD_Channel_GetVolume(channel, C.byref(volume)),
                f"read channel-mask base volume on channel {channel_id}",
            )
            audibility = C.c_float()
            self._check(
                core.FMOD_Channel_GetAudibility(channel, C.byref(audibility)),
                f"read channel-mask audibility on channel {channel_id}",
            )
            group_evidence = self._channel_group_evidence(core, channel)
            channels[channel_id] = _MaskChannelEvidence(
                channel_id=channel_id,
                sound_name=name,
                base_volume=float(volume.value),
                audibility=float(audibility.value),
                channel_groups=group_evidence,
            )
        if unknown_names:
            raise FmodError(
                "channel-mask role map omitted active sounds: "
                + ", ".join(sorted(unknown_names))
            )
        return channels

    def _classify_engine_program_mask_channels(
        self,
        core: C.CDLL,
        studio: C.CDLL,
        studio_system: C.c_void_p,
        instance: C.c_void_p,
        channels: Sequence[_MaskChannelEvidence],
        target_parameters: Mapping[str, float],
    ) -> tuple[dict[int, str], dict[int, dict[str, float]], int]:
        if "throttle" not in target_parameters:
            raise FmodError("engine-program channel masks need a throttle identity probe")

        route_group_pointers = {
            channel.channel_id: self._authored_mask_route_group_pointers(channel)
            for channel in channels
        }
        endpoint_gains: dict[float, dict[int, float]] = {}
        calibration_update_blocks = 0
        for throttle in (0.0, 1.0):
            calibration_update_blocks += self._set_mask_probe_parameter(
                studio,
                studio_system,
                instance,
                "throttle",
                throttle,
            )
            settled, update_blocks = self._settle_mask_route_gains(
                core,
                studio,
                studio_system,
                route_group_pointers,
            )
            calibration_update_blocks += update_blocks
            endpoint_gains[throttle] = settled
        calibration_update_blocks += self._set_mask_probe_parameter(
            studio,
            studio_system,
            instance,
            "throttle",
            float(target_parameters["throttle"]),
        )
        _target_gains, update_blocks = self._settle_mask_route_gains(
            core,
            studio,
            studio_system,
            route_group_pointers,
        )
        calibration_update_blocks += update_blocks

        resolved: dict[int, str] = {}
        probes: dict[int, dict[str, float]] = {}
        for channel in channels:
            low = endpoint_gains[0.0][channel.channel_id]
            high = endpoint_gains[1.0][channel.channel_id]
            peak = max(low, high)
            normalized_delta = 0.0 if peak <= 1e-8 else (high - low) / peak
            role = (
                "LOAD"
                if normalized_delta >= 0.05
                else "COAST"
                if normalized_delta <= -0.05
                else "UNAFFECTED"
            )
            resolved[channel.channel_id] = role
            probes[channel.channel_id] = {
                "throttle0RouteGain": low,
                "throttle1RouteGain": high,
                "routeGainDelta": high - low,
                "normalizedRouteGainDelta": normalized_delta,
            }
        return resolved, probes, calibration_update_blocks

    def _settle_mask_route_gains(
        self,
        core: C.CDLL,
        studio: C.CDLL,
        studio_system: C.c_void_p,
        route_group_pointers: Mapping[int, Sequence[int]],
    ) -> tuple[dict[int, float], int]:
        previous: dict[int, float] | None = None
        stable_blocks = 0
        for update_count in range(1, MAXIMUM_MASK_PARAMETER_SETTLE_BLOCKS + 1):
            self._check(
                studio.FMOD_Studio_System_Update(studio_system),
                "settle channel-mask authored route gains",
            )
            current = {
                channel_id: self._mask_route_gain(core, pointers)
                for channel_id, pointers in route_group_pointers.items()
            }
            if previous is not None and all(
                abs(current[channel_id] - previous[channel_id])
                <= max(
                    MASK_ROUTE_ABSOLUTE_STABILITY_EPSILON,
                    abs(current[channel_id]) * MASK_ROUTE_RELATIVE_STABILITY_EPSILON,
                )
                for channel_id in current
            ):
                stable_blocks += 1
            else:
                stable_blocks = 0
            if stable_blocks >= MASK_ROUTE_STABLE_BLOCKS:
                return current, update_count
            previous = current
        raise FmodError(
            "channel-mask authored route gains did not settle within "
            f"{MAXIMUM_MASK_PARAMETER_SETTLE_BLOCKS} DSP blocks"
        )

    def _set_mask_probe_parameter(
        self,
        studio: C.CDLL,
        studio_system: C.c_void_p,
        instance: C.c_void_p,
        name: str,
        value: float,
    ) -> int:
        self._check(
            studio.FMOD_Studio_EventInstance_SetParameterValue(
                instance,
                name.encode("ascii"),
                value,
            ),
            f"set channel-mask identity probe {name}",
        )
        self._check(
            studio.FMOD_Studio_System_FlushCommands(studio_system),
            "flush channel-mask identity probe",
        )
        for update_count in range(1, MAXIMUM_MASK_PARAMETER_SETTLE_BLOCKS + 1):
            self._check(
                studio.FMOD_Studio_System_Update(studio_system),
                "evaluate channel-mask identity probe",
            )
            current = C.c_float()
            final = C.c_float()
            self._check(
                studio.FMOD_Studio_EventInstance_GetParameterValue(
                    instance,
                    name.encode("ascii"),
                    C.byref(current),
                    C.byref(final),
                ),
                f"read channel-mask identity probe {name}",
            )
            if (
                abs(float(final.value) - value) <= MASK_PARAMETER_SETTLE_EPSILON
                and abs(float(current.value) - value) <= MASK_PARAMETER_SETTLE_EPSILON
            ):
                return update_count
        raise FmodError(
            f"channel-mask identity probe {name} did not settle at {value} "
            f"within {MAXIMUM_MASK_PARAMETER_SETTLE_BLOCKS} DSP blocks"
        )

    @staticmethod
    def _authored_mask_route_group_pointers(
        channel: _MaskChannelEvidence,
    ) -> tuple[int, ...]:
        pointers: list[int] = []
        for group in channel.channel_groups:
            if group["name"] in {"Master Bus", "Input Bus", "FMOD master"}:
                break
            pointers.append(int(group["pointer"]))
        if not pointers:
            raise FmodError(
                f"engine-program sound {channel.sound_name!r} has no authored routing groups"
            )
        return tuple(pointers)

    @staticmethod
    def _mask_binding_route_key(
        channel: _MaskChannelEvidence,
    ) -> tuple[str, tuple[int, ...]]:
        pointers = tuple(
            int(group["pointer"])
            for group in channel.channel_groups
            if group["name"] not in {
                "PlaylistInstrument",
                "Master Bus",
                "Input Bus",
                "FMOD master",
            }
        )
        if not pointers:
            raise FmodError(
                f"engine-program sound {channel.sound_name!r} has no binding route"
            )
        return channel.sound_name, pointers

    def _mask_route_gain(
        self,
        core: C.CDLL,
        group_pointers: Sequence[int],
    ) -> float:
        gain = 1.0
        for pointer in group_pointers:
            volume = C.c_float()
            self._check(
                core.FMOD_ChannelGroup_GetVolume(
                    C.c_void_p(pointer),
                    C.byref(volume),
                ),
                "read channel-mask identity route gain",
            )
            gain *= float(volume.value)
        return gain

    def _channel_group_evidence(
        self,
        core: C.CDLL,
        channel: C.c_void_p,
    ) -> tuple[dict[str, Any], ...]:
        group = C.c_void_p()
        if core.FMOD_Channel_GetChannelGroup(channel, C.byref(group)) or not group:
            return ()
        groups: list[dict[str, Any]] = []
        seen: set[int] = set()
        while group and int(group.value or 0) not in seen:
            pointer = int(group.value or 0)
            seen.add(pointer)
            name_buffer = C.create_string_buffer(512)
            name = ""
            if not core.FMOD_ChannelGroup_GetName(group, name_buffer, len(name_buffer)):
                name = name_buffer.value.decode("utf-8", "replace")
            volume = C.c_float()
            volume_result = core.FMOD_ChannelGroup_GetVolume(group, C.byref(volume))
            groups.append(
                {
                    "pointer": pointer,
                    "name": name,
                    "volume": None if volume_result else float(volume.value),
                }
            )
            parent = C.c_void_p()
            if core.FMOD_ChannelGroup_GetParentGroup(group, C.byref(parent)) or not parent:
                break
            group = parent
        return tuple(groups)

    def _apply_channel_mask(
        self,
        core: C.CDLL,
        low_level_system: C.c_void_p,
        channels: Mapping[int, _MaskChannel],
        sound_role_candidates: Mapping[str, tuple[str, ...]],
        role_gains: Mapping[str, float],
        *,
        reset_positions: bool,
    ) -> None:
        if not channels:
            raise FmodError("channel-mask capture lost its active-channel snapshot")
        templates_by_route = {
            snapshot.binding_route: snapshot for snapshot in channels.values()
        }
        active = self._read_active_mask_channels(
            core,
            low_level_system,
            sound_role_candidates,
        )
        active_routes: set[tuple[str, tuple[int, ...]]] = set()
        for channel_id, evidence in active.items():
            binding_route = self._mask_binding_route_key(evidence)
            active_routes.add(binding_route)
            snapshot = templates_by_route.get(binding_route)
            if snapshot is None:
                declared_roles = set(sound_role_candidates[evidence.sound_name])
                if declared_roles != {"EXCLUDED"}:
                    raise FmodError(
                        "channel-mask capture created an unclassified continuous route "
                        f"for {evidence.sound_name!r} on channel {channel_id}"
                    )
                snapshot = _MaskChannel(
                    sound_name=evidence.sound_name,
                    role="EXCLUDED",
                    base_volume=evidence.base_volume,
                    binding_route=binding_route,
                )
            role = snapshot.role
            # A PlaySequential replacement may reuse the authored binding route
            # with a new Core channel id.  Its current volume can still contain
            # the previous capture mask, so it is not a trustworthy unmasked
            # baseline.  The route snapshot was taken before the first mask and
            # is the phase-family's authoritative authored gain.
            base_volume = snapshot.base_volume
            channel = C.c_void_p()
            self._check(
                core.FMOD_System_GetChannel(
                    low_level_system,
                    channel_id,
                    C.byref(channel),
                ),
                f"reacquire active channel-mask channel {channel_id}",
            )
            self._check(
                core.FMOD_Channel_SetVolume(
                    channel,
                    C.c_float(base_volume * role_gains[role]),
                ),
                f"apply {role} channel mask on channel {channel_id}",
            )
            if reset_positions:
                self._check(
                    core.FMOD_Channel_SetPosition(
                        channel,
                        0,
                        FMOD_TIMEUNIT_PCM,
                    ),
                    f"reset channel-mask phase on channel {channel_id}",
                )
        missing_audible_routes = sorted(
            str(route)
            for route, snapshot in templates_by_route.items()
            if route not in active_routes
            and snapshot.role != "EXCLUDED"
            and role_gains[snapshot.role] != 0.0
        )
        if missing_audible_routes:
            raise FmodError(
                "channel-mask capture lost an audible continuous binding route: "
                + ", ".join(missing_audible_routes)
            )

    @staticmethod
    def _crop_canonical(
        source_path: Path,
        output_path: Path,
        *,
        start_frame: int,
        frame_count: int,
    ) -> None:
        with wave.open(str(source_path), "rb") as source:
            if (
                source.getframerate(),
                source.getnchannels(),
                source.getsampwidth(),
                source.getcomptype(),
            ) != (48000, 2, 2, "NONE"):
                raise FmodError("FMOD writer did not produce PCM16/48 kHz/stereo")
            if source.getnframes() < start_frame + frame_count:
                raise FmodError(
                    f"FMOD writer returned {source.getnframes()} frames, "
                    f"need {start_frame + frame_count}"
                )
            source.setpos(start_frame)
            data = source.readframes(frame_count)
        with wave.open(str(output_path), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(48000)
            output.writeframes(data)


def _fresh_render_worker(request_path: Path, result_path: Path) -> None:
    """Execute one hash-bound render request in this otherwise-clean process."""

    request_envelope = _read_strict_json(request_path, maximum_bytes=1024 * 1024)
    if set(request_envelope) != {"request", "requestSha256"}:
        raise FmodError("fresh FMOD request envelope fields changed")
    request = request_envelope["request"]
    request_sha = request_envelope["requestSha256"]
    if (
        not isinstance(request, dict)
        or not isinstance(request_sha, str)
        or len(request_sha) != 64
        or request_sha != _sha256_bytes(_canonical_json_bytes(request))
    ):
        raise FmodError("fresh FMOD request hash mismatch")
    expected_fields = {
        "schema",
        "assettoRoot",
        "runtimeDllSha256",
        "bankPath",
        "bankSha256",
        "eventName",
        "outputPath",
        "renderIdentitySha256",
        "parameters",
        "startParameters",
        "durationFrames",
        "warmupFrames",
        "emitterPosition",
        "listenerPosition",
        "variantIndex",
        "requiredSoundName",
        "maximumTakes",
        "dspBufferFrames",
    }
    if set(request) != expected_fields or request["schema"] != FRESH_RENDER_REQUEST_SCHEMA:
        raise FmodError("fresh FMOD request fields changed")
    render_identity = {
        key: value
        for key, value in request.items()
        if key not in {"outputPath", "renderIdentitySha256"}
    }
    if request["renderIdentitySha256"] != _sha256_bytes(
        _canonical_json_bytes(render_identity)
    ):
        raise FmodError("fresh FMOD semantic render identity hash mismatch")
    assetto_root = Path(request["assettoRoot"])
    bank_path = Path(request["bankPath"])
    output_path = Path(request["outputPath"])
    if (
        not assetto_root.is_absolute()
        or not bank_path.is_absolute()
        or not output_path.is_absolute()
        or not assetto_root.is_dir()
        or not bank_path.is_file()
        or output_path in {request_path.resolve(), result_path.resolve()}
    ):
        raise FmodError("fresh FMOD request paths are invalid")
    assetto_root = assetto_root.resolve()
    bank_path = bank_path.resolve()
    output_path = output_path.resolve()
    if request["bankSha256"] != _sha256_file(bank_path):
        raise FmodError("fresh FMOD request bank changed before worker render")
    runtime_hashes = request["runtimeDllSha256"]
    if not isinstance(runtime_hashes, dict) or set(runtime_hashes) != {
        "fmod64.dll",
        "fmodstudio64.dll",
    }:
        raise FmodError("fresh FMOD runtime identity fields changed")
    for name, expected_sha in runtime_hashes.items():
        dll_path = assetto_root / name
        if (
            not isinstance(expected_sha, str)
            or len(expected_sha) != 64
            or not dll_path.is_file()
            or _sha256_file(dll_path) != expected_sha
        ):
            raise FmodError(f"fresh FMOD runtime identity changed for {name}")
    event_name = request["eventName"]
    parameters = request["parameters"]
    start_parameters = request["startParameters"]
    if (
        not isinstance(event_name, str)
        or not event_name
        or not isinstance(parameters, dict)
        or (start_parameters is not None and not isinstance(start_parameters, dict))
    ):
        raise FmodError("fresh FMOD request controls are invalid")
    for label, controls in (
        ("parameters", parameters),
        ("startParameters", start_parameters or {}),
    ):
        if any(
            not isinstance(key, str)
            or not key
            or isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(float(value))
            for key, value in controls.items()
        ):
            raise FmodError(f"fresh FMOD request {label} are invalid")
    integer_fields = {
        "durationFrames": (1, 2**31 - 1),
        "warmupFrames": (0, 2**31 - 1),
        "variantIndex": (0, 2**31 - 1),
        "maximumTakes": (1, 4096),
        "dspBufferFrames": (1, 8192),
    }
    for name, (minimum, maximum) in integer_fields.items():
        value = request[name]
        if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
            raise FmodError(f"fresh FMOD request {name} is invalid")
    positions: dict[str, tuple[float, float, float]] = {}
    for name in ("emitterPosition", "listenerPosition"):
        values = request[name]
        if (
            not isinstance(values, list)
            or len(values) != 3
            or any(
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(float(value))
                for value in values
            )
        ):
            raise FmodError(f"fresh FMOD request {name} is invalid")
        positions[name] = tuple(float(value) for value in values)  # type: ignore[assignment]
    required_name = request["requiredSoundName"]
    if required_name is not None and (not isinstance(required_name, str) or not required_name):
        raise FmodError("fresh FMOD required sound identity is invalid")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{output_path.name}.",
        suffix=".fresh-render.tmp.wav",
        dir=output_path.parent,
        delete=False,
    ) as temporary_output:
        staged_output = Path(temporary_output.name)
    staged_output.unlink(missing_ok=True)
    try:
        renderer = SilentFmodReferenceRenderer(
            assetto_root,
            dsp_buffer_frames=request["dspBufferFrames"],
            fresh_process_per_render=False,
        )
        rendered = renderer.render_event(
            bank_path,
            event_name,
            staged_output,
            parameters={str(key): float(value) for key, value in parameters.items()},
            start_parameters=(
                None
                if start_parameters is None
                else {str(key): float(value) for key, value in start_parameters.items()}
            ),
            duration_frames=request["durationFrames"],
            warmup_frames=request["warmupFrames"],
            emitter_position=positions["emitterPosition"],
            listener_position=positions["listenerPosition"],
            variant_index=request["variantIndex"],
            required_sound_name=required_name,
            maximum_takes=request["maximumTakes"],
        )
        output_sha = _sha256_file(staged_output)
        result: dict[str, Any] = {
            "schema": FRESH_RENDER_RESULT_SCHEMA,
            "requestSha256": request_sha,
            "renderIdentitySha256": request["renderIdentitySha256"],
            "outputSha256": output_sha,
            "eventPath": rendered.event_path,
            "parameters": rendered.parameters,
            "startParameters": rendered.start_parameters,
            "sampleRate": rendered.sample_rate,
            "channels": rendered.channels,
            "bitsPerSample": rendered.bits_per_sample,
            "frameCount": rendered.frame_count,
            "scheduledSoundNames": list(rendered.scheduled_sound_names),
            "scheduledSoundNamesByTake": [
                list(items) for items in rendered.scheduled_sound_names_by_take
            ],
            "gainDspParameterObservations": {
                kind: [list(item) for item in values]
                for kind, values in renderer.last_gain_dsp_parameter_observations.items()
            },
        }
        result_envelope = {
            "result": result,
            "resultSha256": _sha256_bytes(_canonical_json_bytes(result)),
        }
        with tempfile.NamedTemporaryFile(
            prefix=f".{result_path.name}.",
            suffix=".tmp",
            dir=result_path.parent,
            delete=False,
        ) as temporary_result:
            staged_result = Path(temporary_result.name)
            temporary_result.write(_canonical_json_bytes(result_envelope) + b"\n")
            temporary_result.flush()
            os.fsync(temporary_result.fileno())
        os.replace(staged_output, output_path)
        os.replace(staged_result, result_path)
    finally:
        staged_output.unlink(missing_ok=True)
        if "staged_result" in locals():
            staged_result.unlink(missing_ok=True)


def _worker_main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[0] != "--fresh-render-worker":
        print("fmod_renderer is an internal library/worker", file=sys.stderr)
        return 2
    try:
        _fresh_render_worker(Path(argv[1]).resolve(), Path(argv[2]).resolve())
    except Exception as exc:
        print(f"fresh render failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(_worker_main(sys.argv[1:]))
