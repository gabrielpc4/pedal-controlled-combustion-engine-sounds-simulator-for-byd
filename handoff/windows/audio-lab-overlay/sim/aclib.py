"""Versioned, strict, atomic Assetto Corsa lossless-library packs."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from .aclib_catalog import canonical_json_bytes
from .flac_codec import PinnedFlacCodec


MANIFEST_SCHEMA_VERSION = 2
SUPPORTED_MANIFEST_SCHEMA_VERSIONS = frozenset((1, 2))
MANIFEST_NAME = "manifest.json"
AUDIO_ROLES = frozenset(
    {
        "IDLE",
        "COAST",
        "TEXTURE",
        "INTAKE",
        "EXHAUST",
        "TURBO",
        "SPOOL",
        "BOV",
        "TURBO_TRANSIENT",
        "TRANSMISSION",
        "LIMITER",
        "SHIFT_UP",
        "SHIFT_DOWN",
        "OVERRUN",
        "POP",
        "BANG",
        "CRACK",
        "ENGINE_TRANSIENT",
        "ENGINE_START",
    }
)
LOOP_ROLES = frozenset(
    {
        "IDLE",
        "COAST",
        "TEXTURE",
        "INTAKE",
        "EXHAUST",
        "TURBO",
        "SPOOL",
        "TRANSMISSION",
    }
)
TRIGGERS_BY_ROLE = {
    "IDLE": frozenset(),
    "COAST": frozenset(),
    "TEXTURE": frozenset(),
    "INTAKE": frozenset(),
    "EXHAUST": frozenset(),
    "TURBO": frozenset(),
    "SPOOL": frozenset(),
    "TRANSMISSION": frozenset(),
    "BOV": frozenset({"bov"}),
    "TURBO_TRANSIENT": frozenset({"turboEvent"}),
    "LIMITER": frozenset({"limiterPulse"}),
    "SHIFT_UP": frozenset({"shiftUp"}),
    "SHIFT_DOWN": frozenset({"shiftDown"}),
    "OVERRUN": frozenset({"overrunRelease"}),
    "POP": frozenset({"pop"}),
    "BANG": frozenset({"bang"}),
    "CRACK": frozenset({"crack"}),
    "ENGINE_TRANSIENT": frozenset({"engineEvent"}),
    "ENGINE_START": frozenset({"engineStart"}),
}
V2_TRIGGERS_BY_ROLE = {
    **TRIGGERS_BY_ROLE,
    "LIMITER": frozenset({"limiterEvent"}),
    "BOV": frozenset({"turboEvent"}),
}
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
_TOKEN = re.compile(r"[a-z0-9]+")
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_TRACKS = 256
MAX_CERTIFIED_SILENT_SOURCES = 512
MAX_CARS_PER_FAMILY = 64
MAX_ASSETS = 64
MAX_AUDIO_MEMBER_BYTES = 128 * 1024 * 1024
MAX_PREVIEW_MEMBER_BYTES = 16 * 1024 * 1024
MAX_DECODED_PCM_BYTES = 192 * 1024 * 1024
MAX_ONE_SHOT_PROGRAMS = 128
MAX_ONE_SHOT_NODES_PER_PROGRAM = 512
MAX_ONE_SHOT_GROUP_MEMBERS = 256
MAX_ONE_SHOT_TREE_DEPTH = 32
AUTO_PITCH_MODE = "AUTO_PITCH_RPM_RATIO"
PROPERTY_ONE_PITCH_MODE = "AUTHORED_PROPERTY_ONE_RELATIVE_RATE"
PROPERTY_ONE_INTERPOLATION = "CLAMPED_LINEAR"
MAX_PITCH_CURVE_POINTS = 512
MAX_PROPERTY_ONE_PLAYBACK_RATE = 16.0
PROPERTY_ONE_ROOT_RATE_TOLERANCE = 2.0e-4


def derive_effect_capabilities(track_roles: object) -> dict[str, bool]:
    """Derive public controls from audible retained semantic tracks.

    This deliberately accepts roles, not program metadata: a certified-silent
    topology leaf must not make a control appear.  OVERRUN participates in the
    pops/bangs/cracks audition capability because audition drives that exact
    authored throttle-lift topology rather than substituting a sample.
    """

    if not isinstance(track_roles, (list, tuple, set, frozenset)) or any(
        not isinstance(role, str) or role not in AUDIO_ROLES
        for role in track_roles
    ):
        raise ManifestValidationError("effect capabilities require valid track roles")
    roles = set(track_roles)
    return {
        "idle": "IDLE" in roles,
        "coast": "COAST" in roles,
        "texture": "TEXTURE" in roles,
        "intake": "INTAKE" in roles,
        "exhaust": "EXHAUST" in roles,
        "turbo": "TURBO" in roles or "TURBO_TRANSIENT" in roles,
        "spool": "SPOOL" in roles or "TURBO" in roles or "TURBO_TRANSIENT" in roles,
        "bov": "BOV" in roles,
        "transmission": "TRANSMISSION" in roles,
        "limiter": "LIMITER" in roles,
        "shift": "SHIFT_UP" in roles or "SHIFT_DOWN" in roles,
        "overrun": "OVERRUN" in roles,
        "popsBangsCracks": bool(
            {"OVERRUN", "POP", "BANG", "CRACK"} & roles
        ),
        "engineStart": "ENGINE_START" in roles,
    }

ONE_SHOT_TRIGGER_BY_ROLE = {
    "LIMITER": "LIMITER_EVENT",
    "SHIFT_UP": "SHIFT_UP",
    "SHIFT_DOWN": "SHIFT_DOWN",
    "BOV": "BOV_LIFT",
    "TURBO_TRANSIENT": "TURBO_EVENT",
    "OVERRUN": "THROTTLE_LIFT",
    "POP": "THROTTLE_LIFT",
    "BANG": "THROTTLE_LIFT",
    "CRACK": "THROTTLE_LIFT",
    "ENGINE_TRANSIENT": "ENGINE_EVENT",
    "ENGINE_START": "ENGINE_START",
}
# V2 turbo-bank programs own both authored BOV-region scheduling and the
# non-BOV timeline/event-start turbo transients.  Legacy V1 BOV packs retain
# their BOV_LIFT mapping through TRIGGERS_BY_ROLE.
ONE_SHOT_TRIGGER_BY_ROLE["BOV"] = "TURBO_EVENT"
ONE_SHOT_TRIGGERS = frozenset(ONE_SHOT_TRIGGER_BY_ROLE.values())
ONE_SHOT_POLICY_KIND_BY_TRIGGER = {
    "THROTTLE_LIFT": "AC_BACKFIRE",
    "BOV_LIFT": "BOV_LIFT",
    "LIMITER_EVENT": "LIMITER_EVENT",
    "SHIFT_UP": "SHIFT_UP",
    "SHIFT_DOWN": "SHIFT_DOWN",
}
ONE_SHOT_POLICY_KINDS = frozenset(ONE_SHOT_POLICY_KIND_BY_TRIGGER.values())
ONE_SHOT_PLAY_MODES = frozenset(("NORMAL", "SMART_RANDOM", "SEQUENTIAL"))
ONE_SHOT_SELECTION_MODES = frozenset(("NORMAL", "SELECT_ALL"))
ONE_SHOT_GATE_CONTROLS = frozenset(
    (
        "ENGINE_RPM",
        "ACCELERATOR",
        "SHIFT_STATE",
        "BOOST",
        "BOV",
        "BOV_DECAY",
        "DRIVETRAIN_SPEED",
        "DECAY",
    )
)


class ManifestValidationError(ValueError):
    pass


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _require_dict(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ManifestValidationError(f"{label} must be an object")
    return value


def _require_list(value: object, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ManifestValidationError(f"{label} must be an array")
    return value


def _require_number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ManifestValidationError(f"{label} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise ManifestValidationError(f"{label} must be a finite number")
    return result


def _archive_path(value: object, label: str, *, prefix: str | None = None) -> str:
    if not isinstance(value, str) or not value:
        raise ManifestValidationError(f"{label} must be a non-empty path")
    if len(value.encode("utf-8")) > 240:
        raise ManifestValidationError(f"{label} is too long")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "\\" in value or value != path.as_posix():
        raise ManifestValidationError(f"{label} is not a normalized relative path")
    if prefix is not None and (not path.parts or path.parts[0] != prefix):
        raise ManifestValidationError(f"{label} must be under {prefix}/")
    return value


def _reject_forbidden_token(value: object) -> None:
    text = canonical_json_bytes(value).decode("utf-8").casefold()
    if "load" in _TOKEN.findall(text):
        raise ManifestValidationError("manifest contains a forbidden audio role or reference")


def _curve_value(points: list[list[float]], x: float) -> float:
    if not points:
        return 1.0
    if x <= float(points[0][0]):
        return float(points[0][1])
    for left, right in zip(points, points[1:]):
        if x <= float(right[0]):
            width = float(right[0]) - float(left[0])
            if width <= 0.0:
                return float(right[1])
            fraction = (x - float(left[0])) / width
            return float(left[1]) + (float(right[1]) - float(left[1])) * fraction
    return float(points[-1][1])


def default_mix_peak_bound_dbfs(tracks: list[dict[str, Any]], maximum_rpm: float) -> float:
    """Conservative continuous-track peak-sum bound on the runtime control grid."""

    continuous = [track for track in tracks if track.get("role") in LOOP_ROLES]
    if not continuous:
        return -math.inf
    maximum = 0.0
    for rpm_step in range(129):
        rpm = maximum_rpm * rpm_step / 128.0
        for pedal_step in range(17):
            pedal = pedal_step / 16.0
            bound = 0.0
            for track in continuous:
                weight = _curve_value(track["rpmCurve"], rpm) * _curve_value(
                    track["gainCurve"], pedal
                )
                peak = 10.0 ** (
                    (float(track["peakDbfs"]) + float(track["gainDb"])) / 20.0
                )
                bound += peak * weight
            maximum = max(maximum, bound)
    return -math.inf if maximum <= 0.0 else 20.0 * math.log10(maximum)


def _validate_track(track: object, index: int, schema_version: int) -> str:
    item = _require_dict(track, f"tracks[{index}]")
    required = {
        "id",
        "role",
        "path",
        "flacSha256",
        "pcmSha256",
        "frameCount",
        "sampleRate",
        "channels",
        "bitsPerSample",
        "rootRpm",
        "loopStartFrame",
        "loopEndFrame",
        "gainDb",
        "peakDbfs",
        "rpmCurve",
        "gainCurve",
        "triggers",
    }
    if schema_version >= 2:
        required.update(
            {
                "softwareChannelPriority",
                "pitchMode",
                "pitchCurve",
                "pitchCurveInterpolation",
            }
        )
    missing = sorted(required - set(item))
    unknown = sorted(set(item) - required)
    if missing or unknown:
        raise ManifestValidationError(
            f"tracks[{index}] fields mismatch: missing={missing} unknown={unknown}"
        )
    track_id = item["id"]
    if not isinstance(track_id, str) or not _IDENTIFIER.fullmatch(track_id):
        raise ManifestValidationError(f"tracks[{index}].id is invalid")
    role = item["role"]
    if role not in AUDIO_ROLES:
        raise ManifestValidationError(f"tracks[{index}].role is unsupported")
    if schema_version >= 2:
        priority = item["softwareChannelPriority"]
        if (
            isinstance(priority, bool)
            or not isinstance(priority, int)
            or not 0 <= priority <= 256
        ):
            raise ManifestValidationError(
                f"tracks[{index}].softwareChannelPriority must be in 0..256"
            )
    path = _archive_path(item["path"], f"tracks[{index}].path", prefix="audio")
    if not path.casefold().endswith(".flac"):
        raise ManifestValidationError(f"tracks[{index}].path must end in .flac")
    for name in ("flacSha256", "pcmSha256"):
        if not isinstance(item[name], str) or not _SHA256.fullmatch(item[name]):
            raise ManifestValidationError(f"tracks[{index}].{name} is not SHA-256")
    frame_count = item["frameCount"]
    if isinstance(frame_count, bool) or not isinstance(frame_count, int) or frame_count <= 0:
        raise ManifestValidationError(f"tracks[{index}].frameCount must be positive")
    if (item["sampleRate"], item["channels"], item["bitsPerSample"]) != (48000, 2, 16):
        raise ManifestValidationError(f"tracks[{index}] must be PCM16/48 kHz/stereo")
    root_rpm = item["rootRpm"]
    if root_rpm is not None and _require_number(root_rpm, f"tracks[{index}].rootRpm") <= 0:
        raise ManifestValidationError(f"tracks[{index}].rootRpm must be positive")
    start, end = item["loopStartFrame"], item["loopEndFrame"]
    if (start is None) != (end is None):
        raise ManifestValidationError(f"tracks[{index}] loop bounds must both be set or null")
    if role in LOOP_ROLES and start is None:
        raise ManifestValidationError(f"tracks[{index}] {role} requires explicit loop bounds")
    if start is not None:
        if any(isinstance(value, bool) or not isinstance(value, int) for value in (start, end)):
            raise ManifestValidationError(f"tracks[{index}] loop bounds must be integers")
        if not (0 <= start < end <= frame_count):
            raise ManifestValidationError(f"tracks[{index}] loop bounds are outside PCM")
    if role in {
        "IDLE",
        "COAST",
        "TEXTURE",
        "INTAKE",
        "EXHAUST",
        "ENGINE_TRANSIENT",
    } and root_rpm is None:
        raise ManifestValidationError(f"tracks[{index}] {role} requires rootRpm")
    gain_db = _require_number(item["gainDb"], f"tracks[{index}].gainDb")
    peak_dbfs = _require_number(item["peakDbfs"], f"tracks[{index}].peakDbfs")
    if gain_db > 0.0:
        raise ManifestValidationError(f"tracks[{index}].gainDb may not amplify")
    if peak_dbfs > -3.0:
        raise ManifestValidationError(f"tracks[{index}] exceeds the -3 dBFS profile ceiling")
    if peak_dbfs <= -96.0:
        raise ManifestValidationError(f"tracks[{index}] is effectively silent")
    for curve_name in ("rpmCurve", "gainCurve"):
        curve = _require_list(item[curve_name], f"tracks[{index}].{curve_name}")
        previous_x: float | None = None
        for point_index, point in enumerate(curve):
            if not isinstance(point, list) or len(point) != 2:
                raise ManifestValidationError(
                    f"tracks[{index}].{curve_name}[{point_index}] must be [x,y]"
                )
            x = _require_number(point[0], "curve x")
            y = _require_number(point[1], "curve y")
            if not 0.0 <= y <= 1.0:
                raise ManifestValidationError(
                    f"tracks[{index}].{curve_name} y must be linear amplitude 0..1"
                )
            if curve_name == "gainCurve" and not 0.0 <= x <= 1.0:
                raise ManifestValidationError(
                    f"tracks[{index}].gainCurve x must be normalized pedal 0..1"
                )
            if curve_name == "rpmCurve" and x < 0.0:
                raise ManifestValidationError(
                    f"tracks[{index}].rpmCurve x must be non-negative RPM"
                )
            if previous_x is not None and x <= previous_x:
                raise ManifestValidationError(
                    f"tracks[{index}].{curve_name} x values must increase"
                )
            previous_x = x
    if schema_version >= 2:
        pitch_mode = item["pitchMode"]
        pitch_curve = _require_list(
            item["pitchCurve"], f"tracks[{index}].pitchCurve"
        )
        pitch_interpolation = item["pitchCurveInterpolation"]
        if pitch_mode == AUTO_PITCH_MODE:
            if pitch_curve or pitch_interpolation != "NONE":
                raise ManifestValidationError(
                    f"tracks[{index}] ordinary AutoPitch requires an empty pitchCurve/NONE"
                )
        elif pitch_mode == PROPERTY_ONE_PITCH_MODE:
            if (
                pitch_interpolation != PROPERTY_ONE_INTERPOLATION
                or role not in LOOP_ROLES
                or root_rpm is None
                or not 2 <= len(pitch_curve) <= MAX_PITCH_CURVE_POINTS
                or len(item["rpmCurve"]) < 2
            ):
                raise ManifestValidationError(
                    f"tracks[{index}] property-one pitch contract is invalid"
                )
            parsed_pitch: list[tuple[float, float]] = []
            previous_pitch_x: float | None = None
            for point_index, point in enumerate(pitch_curve):
                if not isinstance(point, list) or len(point) != 2:
                    raise ManifestValidationError(
                        f"tracks[{index}].pitchCurve[{point_index}] must be [rpm,rate]"
                    )
                x = _require_number(
                    point[0], f"tracks[{index}].pitchCurve[{point_index}].rpm"
                )
                rate = _require_number(
                    point[1], f"tracks[{index}].pitchCurve[{point_index}].rate"
                )
                if (
                    x < 0.0
                    or not 0.0 < rate <= MAX_PROPERTY_ONE_PLAYBACK_RATE
                    or (previous_pitch_x is not None and x <= previous_pitch_x)
                ):
                    raise ManifestValidationError(
                        f"tracks[{index}].pitchCurve[{point_index}] is outside its domain"
                    )
                parsed_pitch.append((x, rate))
                previous_pitch_x = x
            rpm_curve = item["rpmCurve"]
            if (
                parsed_pitch[0][0] != float(rpm_curve[0][0])
                or parsed_pitch[-1][0] != float(rpm_curve[-1][0])
            ):
                raise ManifestValidationError(
                    f"tracks[{index}].pitchCurve domain must exactly match rpmCurve"
                )
            root = float(root_rpm)
            if not parsed_pitch[0][0] <= root <= parsed_pitch[-1][0]:
                raise ManifestValidationError(
                    f"tracks[{index}].rootRpm is outside pitchCurve"
                )
            root_rate = parsed_pitch[-1][1]
            for left, right in zip(parsed_pitch, parsed_pitch[1:]):
                if left[0] <= root <= right[0]:
                    fraction = (root - left[0]) / (right[0] - left[0])
                    root_rate = left[1] + (right[1] - left[1]) * fraction
                    break
            if abs(root_rate - 1.0) > PROPERTY_ONE_ROOT_RATE_TOLERANCE:
                raise ManifestValidationError(
                    f"tracks[{index}].pitchCurve must be capture-normalized at rootRpm"
                )
        else:
            raise ManifestValidationError(
                f"tracks[{index}].pitchMode is unsupported"
            )
    triggers = _require_list(item["triggers"], f"tracks[{index}].triggers")
    if any(not isinstance(trigger, str) or not trigger for trigger in triggers):
        raise ManifestValidationError(f"tracks[{index}].triggers must contain strings")
    role_triggers = (
        V2_TRIGGERS_BY_ROLE if schema_version >= 2 else TRIGGERS_BY_ROLE
    )[role]
    if len(set(triggers)) != len(triggers) or set(triggers) != role_triggers:
        raise ManifestValidationError(
            f"tracks[{index}].triggers do not match role {role}"
        )
    return path


def _validate_program_curve(value: object, label: str, *, normalized_input: bool) -> None:
    curve = _require_list(value, label)
    previous_x: float | None = None
    for index, point in enumerate(curve):
        if not isinstance(point, list) or len(point) != 2:
            raise ManifestValidationError(f"{label}[{index}] must be [x,y]")
        x = _require_number(point[0], f"{label}[{index}].x")
        y = _require_number(point[1], f"{label}[{index}].y")
        if not 0.0 <= y <= 1.0:
            raise ManifestValidationError(f"{label}[{index}].y must be in 0..1")
        if normalized_input and not 0.0 <= x <= 1.0:
            raise ManifestValidationError(f"{label}[{index}].x must be in 0..1")
        if not normalized_input and x < 0.0:
            raise ManifestValidationError(f"{label}[{index}].x must be non-negative")
        if previous_x is not None and x <= previous_x:
            raise ManifestValidationError(f"{label} x values must increase")
        previous_x = x


def _validate_engine_event_program_policy(
    raw_policy: object, label: str
) -> tuple[int, int]:
    policy = _require_dict(raw_policy, label)
    if set(policy) != {
        "kind",
        "parameterRegions",
        "armingMode",
        "initiallyOutsideBehavior",
        "rearmMode",
        "overlapMode",
        "exitBehavior",
        "coreProgram",
        "auditionable",
        "maxDecodedOneShotFrameCount",
        "laneCount",
        "logicalVoiceLimit",
        "softwareRealVoiceBudget",
    }:
        raise ManifestValidationError(f"{label} fields are invalid")
    if (
        policy["kind"] != "ENGINE_EVENT_REGION"
        or policy["armingMode"] != "EVENT_START_INSIDE_REQUIRED"
        or policy["initiallyOutsideBehavior"] != "DISABLED_UNTIL_EVENT_RESTART"
        or policy["rearmMode"] != "AFTER_ANY_GATE_EXIT"
        or policy["overlapMode"] != "ALLOW_OVERLAP"
        or policy["exitBehavior"] != "LET_ACTIVE_VOICES_FINISH"
        or policy["coreProgram"] is not True
        or policy["auditionable"] is not False
        or policy["logicalVoiceLimit"] != 2048
        or policy["softwareRealVoiceBudget"] != 256
    ):
        raise ManifestValidationError(f"{label} execution contract is invalid")
    maximum_frames = policy["maxDecodedOneShotFrameCount"]
    lane_count = policy["laneCount"]
    if (
        isinstance(maximum_frames, bool)
        or not isinstance(maximum_frames, int)
        or maximum_frames <= 0
        or isinstance(lane_count, bool)
        or not isinstance(lane_count, int)
        or lane_count <= 0
    ):
        raise ManifestValidationError(f"{label} frame/lane bounds are invalid")
    natural_lane_demand = math.ceil(maximum_frames / 480)
    if natural_lane_demand > 2048 or lane_count != natural_lane_demand:
        raise ManifestValidationError(f"{label} natural polyphony demand is unsupported")
    regions = _require_list(policy["parameterRegions"], f"{label}.parameterRegions")
    for region_index, raw_region in enumerate(regions):
        region_label = f"{label}.parameterRegions[{region_index}]"
        region = _require_dict(raw_region, region_label)
        if set(region) != {
            "parameterGates",
            "entryEdges",
            "triggerOnEventStartIfInside",
        }:
            raise ManifestValidationError(f"{region_label} fields are invalid")
        if region["triggerOnEventStartIfInside"] is not True:
            raise ManifestValidationError(
                f"{region_label}.triggerOnEventStartIfInside must be true"
            )
        gates = _require_list(region["parameterGates"], f"{region_label}.parameterGates")
        controls: set[str] = set()
        for gate_index, raw_gate in enumerate(gates):
            gate_label = f"{region_label}.parameterGates[{gate_index}]"
            gate = _require_dict(raw_gate, gate_label)
            if set(gate) != {
                "control",
                "minimum",
                "maximum",
                "includeMinimum",
                "includeMaximum",
            }:
                raise ManifestValidationError(f"{gate_label} fields are invalid")
            control = gate["control"]
            minimum = _require_number(gate["minimum"], f"{gate_label}.minimum")
            maximum = _require_number(gate["maximum"], f"{gate_label}.maximum")
            if (
                control not in ONE_SHOT_GATE_CONTROLS
                or control in controls
                or maximum < minimum
                or not isinstance(gate["includeMinimum"], bool)
                or not isinstance(gate["includeMaximum"], bool)
            ):
                raise ManifestValidationError(f"{gate_label} is invalid")
            controls.add(str(control))
        edges = _require_list(region["entryEdges"], f"{region_label}.entryEdges")
        for edge_index, raw_edge in enumerate(edges):
            edge_label = f"{region_label}.entryEdges[{edge_index}]"
            edge = _require_dict(raw_edge, edge_label)
            if set(edge) != {
                "control",
                "boundary",
                "direction",
                "value",
                "includeBoundary",
            }:
                raise ManifestValidationError(f"{edge_label} fields are invalid")
            if (
                edge["control"] not in controls
                or edge["boundary"] not in {"MINIMUM", "MAXIMUM"}
                or edge["direction"] not in {"INCREASING", "DECREASING"}
                or not isinstance(edge["includeBoundary"], bool)
            ):
                raise ManifestValidationError(f"{edge_label} is invalid")
            _require_number(edge["value"], f"{edge_label}.value")
    return maximum_frames, lane_count


_TURBO_CONTROL_MAXIMUM = {
    "BOOST": 1.5,
    "BOV": 1.0,
    "BOV_DECAY": 10.0,
}
TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE = 38.0


def _validate_turbo_control_curve(
    value: object, label: str, control: str, *, rate: bool
) -> None:
    curve = _require_list(value, label)
    if not curve:
        raise ManifestValidationError(f"{label} must be non-empty")
    previous_x: float | None = None
    for index, point in enumerate(curve):
        if not isinstance(point, list) or len(point) != 2:
            raise ManifestValidationError(f"{label}[{index}] must be [x,y]")
        x = _require_number(point[0], f"{label}[{index}].x")
        y = _require_number(point[1], f"{label}[{index}].y")
        if (
            not 0.0 <= x <= _TURBO_CONTROL_MAXIMUM[control]
            or (y <= 0.0 if rate else y < 0.0)
            or (not rate and y > TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE)
            or (previous_x is not None and x <= previous_x)
        ):
            raise ManifestValidationError(f"{label} is outside its authored domain")
        previous_x = x


def _validate_turbo_event_program_policy(raw_policy: object, label: str) -> str:
    policy = _require_dict(raw_policy, label)
    if set(policy) != {
        "kind",
        "programMode",
        "programPlacementRootInstrumentGuid",
        "placementSignature",
        "programTriggerTemplate",
        "voicePolicy",
        "runtimeControlSemantics",
        "coreProgram",
        "auditionable",
    }:
        raise ManifestValidationError(f"{label} fields are invalid")
    mode = policy["programMode"]
    if (
        policy["kind"] != "TURBO_EVENT_PROGRAM"
        or mode
        not in {
            "BOOST_RELEASE_REGION_ONE_SHOT",
            "TIMELINE_PERIODIC_ONE_SHOT",
            "PARAMETER_SHEET_EVENT_START_ONE_SHOT",
        }
        or not isinstance(policy["programPlacementRootInstrumentGuid"], str)
        or not _GUID.fullmatch(policy["programPlacementRootInstrumentGuid"])
        or policy["auditionable"] is not False
        or policy["coreProgram"]
        is not (mode != "BOOST_RELEASE_REGION_ONE_SHOT")
    ):
        raise ManifestValidationError(f"{label} identity/exposure is invalid")
    placement = _require_dict(policy["placementSignature"], f"{label}.placementSignature")
    template = _require_dict(
        policy["programTriggerTemplate"], f"{label}.programTriggerTemplate"
    )
    if mode == "TIMELINE_PERIODIC_ONE_SHOT":
        if set(placement) != {
            "kind",
            "instrumentGuid",
            "startTick",
            "lengthTicks",
            "timeLocked",
        } or set(template) != {
            "trigger",
            "startTick",
            "periodTicks",
            "ticksPerSecond",
            "overlapMode",
            "exitBehavior",
        }:
            raise ManifestValidationError(f"{label} timeline fields are invalid")
        if (
            placement["kind"] != "timeline"
            or placement["instrumentGuid"]
            != policy["programPlacementRootInstrumentGuid"]
            or placement["timeLocked"] is not True
            or isinstance(placement["startTick"], bool)
            or not isinstance(placement["startTick"], int)
            or placement["startTick"] < 0
            or isinstance(placement["lengthTicks"], bool)
            or not isinstance(placement["lengthTicks"], int)
            or placement["lengthTicks"] <= 0
            or template
            != {
                "trigger": "EVENT_TIMELINE_PERIODIC",
                "startTick": placement["startTick"],
                "periodTicks": placement["lengthTicks"],
                "ticksPerSecond": 48000,
                "overlapMode": "ALLOW_OVERLAP",
                "exitBehavior": "NOT_APPLICABLE",
            }
        ):
            raise ManifestValidationError(f"{label} timeline lifecycle changed")
    else:
        if set(placement) != {
            "kind",
            "instrumentGuid",
            "parameter",
            "parameterGuid",
            "minimum",
            "maximum",
            "authoredMaximum",
            "includeMaximum",
        }:
            raise ManifestValidationError(f"{label} parameter placement is invalid")
        minimum = _require_number(placement["minimum"], f"{label}.minimum")
        maximum = _require_number(placement["maximum"], f"{label}.maximum")
        authored_maximum = _require_number(
            placement["authoredMaximum"], f"{label}.authoredMaximum"
        )
        if (
            placement["kind"] != "parameter"
            or placement["instrumentGuid"]
            != policy["programPlacementRootInstrumentGuid"]
            or placement["parameter"] != "boost"
            or not isinstance(placement["parameterGuid"], str)
            or not _GUID.fullmatch(placement["parameterGuid"])
            or not 0.0 <= minimum < maximum <= 1.5
            or authored_maximum < maximum
            or not isinstance(placement["includeMaximum"], bool)
        ):
            raise ManifestValidationError(f"{label} parameter placement changed")
        if mode == "BOOST_RELEASE_REGION_ONE_SHOT":
            if set(template) != {
                "trigger",
                "parameter",
                "minimum",
                "maximum",
                "includeMinimum",
                "includeMaximum",
                "entryEdges",
                "armingMode",
                "initiallyOutsideBehavior",
                "rearmMode",
                "overlapMode",
                "exitBehavior",
            }:
                raise ManifestValidationError(f"{label} boost-release fields are invalid")
            edges = _require_list(template["entryEdges"], f"{label}.entryEdges")
            expected_edge = {
                "boundary": "MAXIMUM",
                "direction": "DECREASING",
                "value": maximum,
                "includeBoundary": placement["includeMaximum"],
            }
            if (
                template["trigger"]
                != "EVENT_START_ARMED_PARAMETER_REGION_REENTRY"
                or template["parameter"] != "boost"
                or float(template["minimum"]) != minimum
                or float(template["maximum"]) != maximum
                or template["includeMinimum"] is not True
                or template["includeMaximum"]
                is not placement["includeMaximum"]
                or edges != [expected_edge]
                or template["armingMode"]
                != "ARMED_WHEN_EVENT_STARTS_INSIDE_OR_OUTSIDE"
                or template["initiallyOutsideBehavior"]
                != "SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY"
                or template["rearmMode"] != "AFTER_ANY_GATE_EXIT"
                or template["overlapMode"] != "ALLOW_OVERLAP"
                or template["exitBehavior"] != "LET_ACTIVE_VOICES_FINISH"
            ):
                raise ManifestValidationError(f"{label} boost-release lifecycle changed")
        elif template != {
            "trigger": "EVENT_START",
            "parameter": "boost",
            "parameterRegionCoversEntireDomain": True,
            "rearmMode": "NONE_WITHOUT_EVENT_RESTART",
            "overlapMode": "ONE_VOICE_PER_EVENT_START",
            "exitBehavior": "LET_ACTIVE_VOICE_FINISH",
        }:
            raise ManifestValidationError(f"{label} event-start lifecycle changed")
    voice = _require_dict(policy["voicePolicy"], f"{label}.voicePolicy")
    if voice != {
        "softwareChannelPriority": 128,
        "priorityRequiredFromSourceBoundOracle": False,
        "acGlobalLogicalVoiceCap": 2048,
        "acDefaultSoftwareRealVoiceBudget": 256,
        "overlapSharesGlobalBudget": True,
    }:
        raise ManifestValidationError(f"{label}.voicePolicy is not certified")
    if policy["runtimeControlSemantics"] != {
        "boost": "AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN",
        "bov": "AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED",
        "bov_decay": "AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED",
        "propertyZero": "DB_VOLUME",
        "propertyOne": "RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE",
        "propertyFour": "LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH",
        "autoPitchFromParameterPlacement": False,
    }:
        raise ManifestValidationError(f"{label}.runtimeControlSemantics changed")
    return str(mode)


_LIMITER_LIFECYCLE_V2 = {
    "owner": "ONE_PERSISTENT_LIMITER_EVENT_INSTANCE",
    "initialHostDecayTimerSeconds": 10.0,
    "updateOrder": [
        "FLOAT32_TIMER_PLUS_DT",
        "RESET_TIMER_TO_ZERO_IF_LIMITER_PULSE",
        "WRITE_RAW_TIMER_TO_FMOD_DECAY_PARAMETER",
        "UPDATE_EVENT_OWNER_STATE",
    ],
    "eventDesiredActiveWhen": (
        "driveAudioActive && limiterEnabled && hostDecayTimerSeconds<=10"
    ),
    "inactiveThreshold": {
        "comparison": "STRICTLY_GREATER_THAN",
        "seconds": 10.0,
    },
    "activeEventAction": (
        "UNPAUSE_IF_PAUSED_ELSE_REWIND_TIMELINE_ZERO_AND_START_IF_STOPPED"
    ),
    "inactiveEventAction": "STOP_ALLOWFADEOUT",
    "limiterPulseWhileEventActive": (
        "RESET_DECAY_ONLY_PRESERVE_EVENT_TIMELINE_AND_ACTIVE_SOURCE_PHASE"
    ),
    "reactivationAfterInactive": (
        "SET_DECAY_ZERO_THEN_REWIND_TIMELINE_ZERO_THEN_START"
    ),
    "executableEvidence": {
        "timerInitialization": "acs.exe:0x140063038 immediate float32 10.0",
        "timerAndParameterUpdate": "acs.exe:0x140067134-0x14006718c",
        "tenSecondOwnerGate": "acs.exe:0x140067e28-0x140067ea4",
        "rewindThenStart": "acs.exe:0x1401fbf40-0x1401fbfb7",
        "allowFadeStop": "acs.exe:0x1401fc040-0x1401fc07f",
    },
}

_LIMITER_SCHEDULING_V2 = {
    "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": {
        "timelinePeriodicOneShot": "EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER",
        "parameterRegionEntry": None,
        "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
        "placementExitBehavior": "TIMELINE_OWNS_SOURCE_LIFETIME",
        "overlapMode": "ONE_RENDERED_TIMELINE_LOOP_TRACK",
    },
    "PERSISTENT_DECAY_REGION_ONE_SHOT": {
        "timelinePeriodicOneShot": None,
        "parameterRegionEntry": (
            "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY"
        ),
        "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
        "placementExitBehavior": "LET_ACTIVE_ONE_SHOTS_FINISH",
        "overlapMode": "ALLOW_OVERLAPPING_ONE_SHOT_VOICES",
    },
    "PERSISTENT_DECAY_REGION_LOOP": {
        "timelinePeriodicOneShot": None,
        "parameterRegionEntry": (
            "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY"
        ),
        "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
        "placementExitBehavior": (
            "STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY"
        ),
        "overlapMode": "ONE_ACTIVE_LOOP_VOICE",
    },
}


def _validate_limiter_event_program_policy(raw_policy: object, label: str) -> str:
    policy = _require_dict(raw_policy, label)
    if set(policy) != {
        "kind",
        "programMode",
        "sourceLifetime",
        "decayParameter",
        "decayGainCurve",
        "decayPlacement",
        "timelinePlacement",
        "runtimeLifecycle",
        "sourceScheduling",
        "voicePolicy",
        "targetCaptureBakedModulators",
        "sourceVerificationPayloadSha256",
    }:
        raise ManifestValidationError(f"{label} fields are invalid")
    mode = policy["programMode"]
    lifetime = {
        "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": "oneShot",
        "PERSISTENT_DECAY_REGION_ONE_SHOT": "oneShot",
        "PERSISTENT_DECAY_REGION_LOOP": "continuous",
    }.get(mode)
    if (
        policy["kind"] != "PERSISTENT_LIMITER_EVENT"
        or lifetime is None
        or policy["sourceLifetime"] != lifetime
    ):
        raise ManifestValidationError(f"{label} mode/lifetime is invalid")
    if (
        not isinstance(policy["sourceVerificationPayloadSha256"], str)
        or not _SHA256.fullmatch(policy["sourceVerificationPayloadSha256"])
    ):
        raise ManifestValidationError(
            f"{label}.sourceVerificationPayloadSha256 is invalid"
        )
    if policy["decayParameter"] != {
        "control": "LIMITER_DECAY_SECONDS",
        "minimum": 0.0,
        "maximum": 1.0,
        "defaultValue": 0.0,
        "runtimeInput": "min(hostFloat32DecayTimerSeconds,1)",
    }:
        raise ManifestValidationError(f"{label}.decayParameter is invalid")
    _validate_program_curve(
        policy["decayGainCurve"], f"{label}.decayGainCurve", normalized_input=True
    )
    decay_curve = policy["decayGainCurve"]
    if not decay_curve or decay_curve[0][0] != 0.0 or decay_curve[-1][0] != 1.0:
        raise ManifestValidationError(f"{label}.decayGainCurve must span 0..1")
    placement = policy["decayPlacement"]
    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        if placement is not None:
            raise ManifestValidationError(f"{label}.decayPlacement must be null")
    else:
        placement = _require_dict(placement, f"{label}.decayPlacement")
        if set(placement) != {
            "control", "minimum", "maximum", "includeMinimum", "includeMaximum"
        }:
            raise ManifestValidationError(f"{label}.decayPlacement fields are invalid")
        minimum = _require_number(placement["minimum"], f"{label}.decayPlacement.minimum")
        maximum = _require_number(placement["maximum"], f"{label}.decayPlacement.maximum")
        if (
            placement["control"] != "LIMITER_DECAY_SECONDS"
            or not 0.0 <= minimum < maximum <= 1.0
            or not isinstance(placement["includeMinimum"], bool)
            or not isinstance(placement["includeMaximum"], bool)
        ):
            raise ManifestValidationError(f"{label}.decayPlacement values are invalid")
    timeline = policy["timelinePlacement"]
    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        timeline = _require_dict(timeline, f"{label}.timelinePlacement")
        if set(timeline) != {
            "startTicks", "lengthTicks", "timeLocked", "tickRateHz",
            "startFrameAt48k", "periodFramesAt48k",
        } or (
            timeline["startTicks"] != 0
            or timeline["startFrameAt48k"] != 0
            or timeline["timeLocked"] is not True
            or timeline["tickRateHz"] != 48000
            or isinstance(timeline["lengthTicks"], bool)
            or not isinstance(timeline["lengthTicks"], int)
            or timeline["lengthTicks"] <= 0
            or timeline["periodFramesAt48k"] != timeline["lengthTicks"]
        ):
            raise ManifestValidationError(f"{label}.timelinePlacement is invalid")
    elif timeline is not None:
        raise ManifestValidationError(f"{label}.timelinePlacement must be null")
    if policy["runtimeLifecycle"] != _LIMITER_LIFECYCLE_V2:
        raise ManifestValidationError(f"{label}.runtimeLifecycle changed")
    if policy["sourceScheduling"] != _LIMITER_SCHEDULING_V2[mode]:
        raise ManifestValidationError(f"{label}.sourceScheduling changed")
    expected_voice = {
        "maximumSimultaneousProgramTracks": (
            None if mode == "PERSISTENT_DECAY_REGION_ONE_SHOT" else 1
        ),
        "oneShotLaneBoundAfterDecode": (
            "min(2048,ceil(decodedOneShotFrames/480))"
            if mode == "PERSISTENT_DECAY_REGION_ONE_SHOT"
            else None
        ),
        "acGlobalLogicalVoiceCap": 2048,
        "acDefaultSoftwareRealVoiceBudget": 256,
    }
    if policy["voicePolicy"] != expected_voice:
        raise ManifestValidationError(f"{label}.voicePolicy changed")
    modulators = _require_list(
        policy["targetCaptureBakedModulators"],
        f"{label}.targetCaptureBakedModulators",
    )
    if len(modulators) > 1:
        raise ManifestValidationError(f"{label} has too many baked modulators")
    for index, raw in enumerate(modulators):
        modulator = _require_dict(raw, f"{label}.targetCaptureBakedModulators[{index}]")
        if set(modulator) != {"guid", "ownerGuid", "type", "propertyIndex"} or (
            not isinstance(modulator["guid"], str)
            or not _GUID.fullmatch(modulator["guid"])
            or not isinstance(modulator["ownerGuid"], str)
            or not _GUID.fullmatch(modulator["ownerGuid"])
            or modulator["type"] != "ADSR"
            or modulator["propertyIndex"] != 0
        ):
            raise ManifestValidationError(f"{label} baked modulator is invalid")
    return str(mode)


def _validate_one_shot_programs(
    raw_programs: object, tracks: list[dict[str, Any]]
) -> dict[str, str]:
    programs = _require_list(raw_programs, "oneShotPrograms")
    if len(programs) > MAX_ONE_SHOT_PROGRAMS:
        raise ManifestValidationError("oneShotPrograms exceeds the bounded program count")
    tracks_by_id = {str(track["id"]): track for track in tracks}
    one_shot_track_ids = {
        str(track["id"]) for track in tracks if str(track["role"]) not in LOOP_ROLES
    }
    represented_track_ids: set[str] = set()
    turbo_verification_hashes: set[str] = set()
    turbo_silent_source_guids: set[str] = set()
    program_triggers: dict[str, str] = {}
    for program_index, raw_program in enumerate(programs):
        label = f"oneShotPrograms[{program_index}]"
        program = _require_dict(raw_program, label)
        base_fields = {
            "id",
            "trigger",
            "softwareChannelPriority",
            "capturedFromEventStart",
            "rootNodeIds",
            "nodes",
        }
        expected_fields = base_fields | (
            {"policy"}
            if program.get("trigger")
            in {"ENGINE_EVENT", "LIMITER_EVENT", "TURBO_EVENT"}
            else set()
        )
        if set(program) != expected_fields:
            raise ManifestValidationError(f"{label} fields are invalid")
        program_id = program["id"]
        if (
            not isinstance(program_id, str)
            or not _IDENTIFIER.fullmatch(program_id)
            or program_id in program_triggers
        ):
            raise ManifestValidationError(f"{label}.id is invalid or duplicated")
        trigger = program["trigger"]
        if trigger not in ONE_SHOT_TRIGGERS:
            raise ManifestValidationError(f"{label}.trigger is unsupported")
        program_priority = program["softwareChannelPriority"]
        if (
            isinstance(program_priority, bool)
            or not isinstance(program_priority, int)
            or not 0 <= program_priority <= 256
        ):
            raise ManifestValidationError(
                f"{label}.softwareChannelPriority must be in 0..256"
            )
        engine_event_bounds = (
            _validate_engine_event_program_policy(program["policy"], f"{label}.policy")
            if trigger == "ENGINE_EVENT"
            else None
        )
        limiter_mode = (
            _validate_limiter_event_program_policy(
                program["policy"], f"{label}.policy"
            )
            if trigger == "LIMITER_EVENT"
            else None
        )
        turbo_mode = (
            _validate_turbo_event_program_policy(
                program["policy"], f"{label}.policy"
            )
            if trigger == "TURBO_EVENT"
            else None
        )
        if program["capturedFromEventStart"] is not True:
            raise ManifestValidationError(f"{label} must retain event-start PCM timing")
        roots = _require_list(program["rootNodeIds"], f"{label}.rootNodeIds")
        if (
            not roots
            or any(not isinstance(root, str) or not _IDENTIFIER.fullmatch(root) for root in roots)
            or len(roots) != len(set(roots))
        ):
            raise ManifestValidationError(f"{label}.rootNodeIds must be valid and unique")
        nodes = _require_list(program["nodes"], f"{label}.nodes")
        if not nodes or len(nodes) > MAX_ONE_SHOT_NODES_PER_PROGRAM:
            raise ManifestValidationError(f"{label}.nodes is empty or exceeds its bound")
        node_by_id: dict[str, dict[str, Any]] = {}
        parent_counts: dict[str, int] = {}
        program_track_ids: set[str] = set()
        for node_index, raw_node in enumerate(nodes):
            node_label = f"{label}.nodes[{node_index}]"
            node = _require_dict(raw_node, node_label)
            node_id = node.get("id")
            if (
                not isinstance(node_id, str)
                or not _IDENTIFIER.fullmatch(node_id)
                or node_id in node_by_id
            ):
                raise ManifestValidationError(f"{node_label}.id is invalid or duplicated")
            chance = _require_number(node.get("triggerChance"), f"{node_label}.triggerChance")
            if not 0.0 <= chance <= 1.0:
                raise ManifestValidationError(f"{node_label}.triggerChance must be in 0..1")
            kind = node.get("kind")
            if kind == "GROUP":
                if set(node) != {
                    "id",
                    "kind",
                    "triggerChance",
                    "playMode",
                    "selectionMode",
                    "members",
                }:
                    raise ManifestValidationError(f"{node_label} GROUP fields are invalid")
                if node["playMode"] not in ONE_SHOT_PLAY_MODES:
                    raise ManifestValidationError(f"{node_label}.playMode is unsupported")
                if node["selectionMode"] not in ONE_SHOT_SELECTION_MODES:
                    raise ManifestValidationError(f"{node_label}.selectionMode is unsupported")
                members = _require_list(node["members"], f"{node_label}.members")
                if not members or len(members) > MAX_ONE_SHOT_GROUP_MEMBERS:
                    raise ManifestValidationError(f"{node_label}.members count is invalid")
                member_ids: set[str] = set()
                orders: list[int] = []
                for member_index, raw_member in enumerate(members):
                    member_label = f"{node_label}.members[{member_index}]"
                    member = _require_dict(raw_member, member_label)
                    if set(member) != {"nodeId", "weight", "order"}:
                        raise ManifestValidationError(f"{member_label} fields are invalid")
                    child_id = member["nodeId"]
                    order = member["order"]
                    if (
                        not isinstance(child_id, str)
                        or not _IDENTIFIER.fullmatch(child_id)
                        or child_id in member_ids
                        or isinstance(order, bool)
                        or not isinstance(order, int)
                        or order < 0
                    ):
                        raise ManifestValidationError(f"{member_label} is invalid")
                    if _require_number(member["weight"], f"{member_label}.weight") <= 0.0:
                        raise ManifestValidationError(f"{member_label}.weight must be positive")
                    member_ids.add(child_id)
                    orders.append(order)
                    parent_counts[child_id] = parent_counts.get(child_id, 0) + 1
                if sorted(orders) != list(range(len(members))):
                    raise ManifestValidationError(
                        f"{node_label}.members order must be unique and contiguous"
                    )
            elif kind == "SILENT_SOURCE":
                if set(node) != {
                    "id",
                    "kind",
                    "triggerChance",
                    "sourceGuid",
                    "resolvedRole",
                    "sourceVerificationPayloadSha256",
                }:
                    raise ManifestValidationError(
                        f"{node_label} SILENT_SOURCE fields are invalid"
                    )
                source_guid = node["sourceGuid"]
                resolved_role = node["resolvedRole"]
                verification_sha = node["sourceVerificationPayloadSha256"]
                if (
                    trigger != "TURBO_EVENT"
                    or not isinstance(source_guid, str)
                    or not _GUID.fullmatch(source_guid)
                    or source_guid in turbo_silent_source_guids
                    or resolved_role not in {"BOV", "TURBO_TRANSIENT"}
                    or not isinstance(verification_sha, str)
                    or not _SHA256.fullmatch(verification_sha)
                    or verification_sha in turbo_verification_hashes
                ):
                    raise ManifestValidationError(f"{node_label} SILENT_SOURCE is invalid")
                turbo_silent_source_guids.add(source_guid)
                turbo_verification_hashes.add(verification_sha)
            elif kind == "TRACK":
                track_fields = {
                    "id",
                    "kind",
                    "trackId",
                    "triggerChance",
                    "parameterGates",
                    "rpmCurve",
                    "gainCurve",
                    "liveVarispeed",
                    "rootRpm",
                }
                if trigger == "TURBO_EVENT":
                    track_fields |= {
                        "captureControlValues",
                        "controlGainCurves",
                        "pitchAutomations",
                        "sourceVerificationPayloadSha256",
                    }
                if set(node) != track_fields:
                    raise ManifestValidationError(f"{node_label} TRACK fields are invalid")
                track_id = node["trackId"]
                if not isinstance(track_id, str) or not _IDENTIFIER.fullmatch(track_id):
                    raise ManifestValidationError(f"{node_label}.trackId is invalid")
                track = tracks_by_id.get(track_id)
                if track is None or track_id in represented_track_ids:
                    raise ManifestValidationError(
                        f"{node_label}.trackId is missing or represented more than once"
                    )
                if track["softwareChannelPriority"] != program_priority:
                    raise ManifestValidationError(
                        f"{node_label}.trackId channel priority disagrees with its program"
                    )
                role = str(track["role"])
                if role in LOOP_ROLES or ONE_SHOT_TRIGGER_BY_ROLE.get(role) != trigger:
                    raise ManifestValidationError(f"{node_label}.trackId trigger is incompatible")
                gates = _require_list(node["parameterGates"], f"{node_label}.parameterGates")
                gate_controls: set[str] = set()
                for gate_index, raw_gate in enumerate(gates):
                    gate_label = f"{node_label}.parameterGates[{gate_index}]"
                    gate = _require_dict(raw_gate, gate_label)
                    if set(gate) != {
                        "control",
                        "minimum",
                        "maximum",
                        "includeMinimum",
                        "includeMaximum",
                    }:
                        raise ManifestValidationError(f"{gate_label} fields are invalid")
                    control = gate["control"]
                    minimum = _require_number(gate["minimum"], f"{gate_label}.minimum")
                    maximum = _require_number(gate["maximum"], f"{gate_label}.maximum")
                    if (
                        control not in ONE_SHOT_GATE_CONTROLS
                        or control in gate_controls
                        or minimum >= maximum
                        or not isinstance(gate["includeMinimum"], bool)
                        or not isinstance(gate["includeMaximum"], bool)
                    ):
                        raise ManifestValidationError(f"{gate_label} is invalid")
                    if control == "ACCELERATOR" and not (
                        0.0 <= minimum < maximum <= 1.0
                    ):
                        raise ManifestValidationError(f"{gate_label} pedal bounds are invalid")
                    if control in {"ENGINE_RPM", "DRIVETRAIN_SPEED"} and minimum < 0.0:
                        raise ManifestValidationError(f"{gate_label} RPM bounds are invalid")
                    if control == "SHIFT_STATE" and (minimum, maximum) not in {
                        (-1.0, 0.0),
                        (0.0, 1.0),
                    }:
                        raise ManifestValidationError(f"{gate_label} shift bounds are invalid")
                    gate_controls.add(str(control))
                _validate_program_curve(
                    node["rpmCurve"], f"{node_label}.rpmCurve", normalized_input=False
                )
                _validate_program_curve(
                    node["gainCurve"], f"{node_label}.gainCurve", normalized_input=True
                )
                if node["rpmCurve"] != track["rpmCurve"] or node["gainCurve"] != track["gainCurve"]:
                    raise ManifestValidationError(f"{node_label} curves must match its track")
                if role == "ENGINE_TRANSIENT":
                    if (
                        trigger != "ENGINE_EVENT"
                        or node["liveVarispeed"] is not True
                        or node["rootRpm"] != track["rootRpm"]
                        or track["rootRpm"] is None
                    ):
                        raise ManifestValidationError(
                            f"{node_label} engine-transient varispeed contract changed"
                        )
                elif node["liveVarispeed"] is not False or node["rootRpm"] is not None:
                    raise ManifestValidationError(
                        f"{node_label} non-engine effect may not enable live varispeed"
                    )
                if trigger == "TURBO_EVENT":
                    if role not in {"BOV", "TURBO_TRANSIENT"}:
                        raise ManifestValidationError(
                            f"{node_label} turbo-event track role is invalid"
                        )
                    capture_values = _require_list(
                        node["captureControlValues"],
                        f"{node_label}.captureControlValues",
                    )
                    if not capture_values:
                        raise ManifestValidationError(
                            f"{node_label}.captureControlValues is empty"
                        )
                    capture_controls: set[str] = set()
                    for value_index, raw_value in enumerate(capture_values):
                        value_label = (
                            f"{node_label}.captureControlValues[{value_index}]"
                        )
                        value = _require_dict(raw_value, value_label)
                        if set(value) != {"control", "value"}:
                            raise ManifestValidationError(
                                f"{value_label} fields are invalid"
                            )
                        control = value["control"]
                        control_value = _require_number(
                            value["value"], f"{value_label}.value"
                        )
                        if (
                            control not in _TURBO_CONTROL_MAXIMUM
                            or control in capture_controls
                            or not 0.0
                            <= control_value
                            <= _TURBO_CONTROL_MAXIMUM[control]
                        ):
                            raise ManifestValidationError(f"{value_label} is invalid")
                        capture_controls.add(str(control))
                    gain_curves = _require_list(
                        node["controlGainCurves"],
                        f"{node_label}.controlGainCurves",
                    )
                    if not gain_curves or len(gain_curves) > 3:
                        raise ManifestValidationError(
                            f"{node_label}.controlGainCurves count is invalid"
                        )
                    gain_controls: set[str] = set()
                    for curve_index, raw_control_curve in enumerate(gain_curves):
                        curve_label = (
                            f"{node_label}.controlGainCurves[{curve_index}]"
                        )
                        control_curve = _require_dict(raw_control_curve, curve_label)
                        if set(control_curve) != {"control", "curve"}:
                            raise ManifestValidationError(
                                f"{curve_label} fields are invalid"
                            )
                        control = control_curve["control"]
                        if (
                            control not in _TURBO_CONTROL_MAXIMUM
                            or control in gain_controls
                        ):
                            raise ManifestValidationError(
                                f"{curve_label}.control is invalid"
                            )
                        gain_controls.add(str(control))
                        _validate_turbo_control_curve(
                            control_curve["curve"],
                            f"{curve_label}.curve",
                            str(control),
                            rate=False,
                        )
                    automations = _require_list(
                        node["pitchAutomations"],
                        f"{node_label}.pitchAutomations",
                    )
                    if len(automations) > 3:
                        raise ManifestValidationError(
                            f"{node_label}.pitchAutomations count is invalid"
                        )
                    pitch_controls: set[str] = set()
                    for automation_index, raw_automation in enumerate(automations):
                        automation_label = (
                            f"{node_label}.pitchAutomations[{automation_index}]"
                        )
                        automation = _require_dict(raw_automation, automation_label)
                        if set(automation) != {
                            "control",
                            "propertyIndex",
                            "rawValueToSemitonesScale",
                            "captureSemitones",
                            "playbackRateCurve",
                            "runtimeTreatment",
                            "updatesWhileVoiceActive",
                            "continuesOutsideSchedulingRegion",
                            "captureRate",
                        }:
                            raise ManifestValidationError(
                                f"{automation_label} fields are invalid"
                            )
                        control = automation["control"]
                        _require_number(
                            automation["captureSemitones"],
                            f"{automation_label}.captureSemitones",
                        )
                        if (
                            control not in _TURBO_CONTROL_MAXIMUM
                            or control in pitch_controls
                            or automation["propertyIndex"] != 1
                            or automation["rawValueToSemitonesScale"] != 24.0
                            or automation["runtimeTreatment"]
                            != "multiplyActiveVoiceRateContinuously"
                            or automation["updatesWhileVoiceActive"] is not True
                            or automation["continuesOutsideSchedulingRegion"]
                            is not True
                            or automation["captureRate"] != 1.0
                        ):
                            raise ManifestValidationError(
                                f"{automation_label} contract changed"
                            )
                        pitch_controls.add(str(control))
                        _validate_turbo_control_curve(
                            automation["playbackRateCurve"],
                            f"{automation_label}.playbackRateCurve",
                            str(control),
                            rate=True,
                        )
                    verification_sha = node[
                        "sourceVerificationPayloadSha256"
                    ]
                    if (
                        not isinstance(verification_sha, str)
                        or not _SHA256.fullmatch(verification_sha)
                        or verification_sha in turbo_verification_hashes
                    ):
                        raise ManifestValidationError(
                            f"{node_label}.sourceVerificationPayloadSha256 is invalid"
                        )
                    turbo_verification_hashes.add(verification_sha)
                represented_track_ids.add(track_id)
                program_track_ids.add(track_id)
            else:
                raise ManifestValidationError(f"{node_label}.kind is unsupported")
            node_by_id[node_id] = node
        if not set(node_by_id).issuperset(roots):
            raise ManifestValidationError(f"{label} references a missing root")
        for node_id in node_by_id:
            expected_parents = 0 if node_id in roots else 1
            if parent_counts.get(node_id, 0) != expected_parents:
                raise ManifestValidationError(f"{label} is not a rooted tree at {node_id}")
        if any(child_id not in node_by_id for child_id in parent_counts):
            raise ManifestValidationError(f"{label} references a missing child node")
        visiting: set[str] = set()
        visited: set[str] = set()

        def visit(node_id: str, depth: int) -> None:
            if depth > MAX_ONE_SHOT_TREE_DEPTH:
                raise ManifestValidationError(f"{label} exceeds the tree-depth bound")
            if node_id in visiting:
                raise ManifestValidationError(f"{label} contains a cycle")
            if node_id in visited:
                return
            visiting.add(node_id)
            node = node_by_id[node_id]
            if node["kind"] == "GROUP":
                for member in sorted(node["members"], key=lambda item: item["order"]):
                    visit(member["nodeId"], depth + 1)
            visiting.remove(node_id)
            visited.add(node_id)

        for root in roots:
            visit(root, 0)
        if visited != set(node_by_id):
            raise ManifestValidationError(f"{label} contains unreachable nodes")
        if engine_event_bounds is not None:
            maximum_frames, _lane_count = engine_event_bounds
            actual_maximum = max(
                int(tracks_by_id[track_id]["frameCount"])
                for track_id in program_track_ids
            )
            if maximum_frames != actual_maximum:
                raise ManifestValidationError(
                    f"{label}.policy maxDecodedOneShotFrameCount disagrees with tracks"
                )
        if limiter_mode is not None:
            limiter_tracks = [tracks_by_id[track_id] for track_id in program_track_ids]
            if len(limiter_tracks) != 1 or limiter_tracks[0]["role"] != "LIMITER":
                raise ManifestValidationError(
                    f"{label} must contain exactly one LIMITER track"
                )
            track = limiter_tracks[0]
            has_loop = track["loopStartFrame"] is not None
            expected_loop = limiter_mode in {
                "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT",
                "PERSISTENT_DECAY_REGION_LOOP",
            }
            if has_loop is not expected_loop:
                raise ManifestValidationError(
                    f"{label} limiter loop realization disagrees with policy"
                )
            if limiter_mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
                period = program["policy"]["timelinePlacement"][
                    "periodFramesAt48k"
                ]
                if (
                    track["frameCount"] != period
                    or track["loopStartFrame"] != 0
                    or track["loopEndFrame"] != period
                ):
                    raise ManifestValidationError(
                        f"{label} timeline limiter track must be its exact authored period"
                    )
        if turbo_mode is not None:
            turbo_roles = {
                str(tracks_by_id[track_id]["role"])
                for track_id in program_track_ids
            }
            silent_roles = {
                str(node["resolvedRole"])
                for node in nodes
                if node.get("kind") == "SILENT_SOURCE"
            }
            expected_role = (
                "BOV"
                if turbo_mode == "BOOST_RELEASE_REGION_ONE_SHOT"
                else "TURBO_TRANSIENT"
            )
            if (turbo_roles | silent_roles) != {expected_role}:
                raise ManifestValidationError(
                    f"{label} turbo-event role/program mode changed"
                )
        program_triggers[program_id] = str(trigger)
    if represented_track_ids != one_shot_track_ids:
        raise ManifestValidationError(
            "oneShotPrograms must represent every one-shot track exactly once"
        )
    return program_triggers


def _validate_one_shot_policies(
    raw_policies: object, label: str
) -> dict[str, str]:
    policies = _require_dict(raw_policies, label)
    if len(policies) > MAX_ONE_SHOT_PROGRAMS:
        raise ManifestValidationError(f"{label} exceeds the bounded policy count")
    result: dict[str, str] = {}
    for program_id, raw_policy in policies.items():
        policy_label = f"{label}.{program_id}"
        if not isinstance(program_id, str) or not _IDENTIFIER.fullmatch(program_id):
            raise ManifestValidationError(f"{label} contains an invalid program id")
        policy = _require_dict(raw_policy, policy_label)
        if set(policy) != {
            "kind",
            "minimumRpm",
            "maximumRpm",
            "armPedal",
            "firePedal",
            "armBoost",
            "initialPeakPedal",
            "initialArmPedal",
            "initialFirePedal",
            "minimumArmMs",
            "cooldownMs",
            "periodHz",
        }:
            raise ManifestValidationError(f"{policy_label} fields are invalid")
        kind = policy["kind"]
        if kind not in ONE_SHOT_POLICY_KINDS:
            raise ManifestValidationError(f"{policy_label}.kind is unsupported")
        minimum_rpm = _require_number(policy["minimumRpm"], f"{policy_label}.minimumRpm")
        minimum_arm_ms = _require_number(
            policy["minimumArmMs"], f"{policy_label}.minimumArmMs"
        )
        cooldown_ms = _require_number(policy["cooldownMs"], f"{policy_label}.cooldownMs")
        if minimum_rpm < 0.0 or minimum_arm_ms < 0.0 or cooldown_ms < 0.0:
            raise ManifestValidationError(f"{policy_label} contains negative timing/RPM")

        def nullable_number(name: str) -> float | None:
            value = policy[name]
            return None if value is None else _require_number(value, f"{policy_label}.{name}")

        maximum_rpm = nullable_number("maximumRpm")
        arm_pedal = nullable_number("armPedal")
        fire_pedal = nullable_number("firePedal")
        arm_boost = nullable_number("armBoost")
        initial_peak_pedal = nullable_number("initialPeakPedal")
        initial_arm_pedal = nullable_number("initialArmPedal")
        initial_fire_pedal = nullable_number("initialFirePedal")
        period_hz = nullable_number("periodHz")
        if maximum_rpm is not None and maximum_rpm <= minimum_rpm:
            raise ManifestValidationError(
                f"{policy_label}.maximumRpm must exceed minimumRpm"
            )
        if kind == "AC_BACKFIRE":
            valid = (
                arm_pedal is not None
                and fire_pedal is not None
                and 0.0 <= arm_pedal <= 1.0
                and 0.0 <= fire_pedal <= 0.3
                and arm_boost is None
                and maximum_rpm is not None
                and initial_peak_pedal is not None
                and 0.0 <= initial_peak_pedal <= 1.0
                and initial_arm_pedal is not None
                and 0.0 <= initial_arm_pedal <= 1.0
                and initial_fire_pedal is not None
                and 0.0 <= initial_fire_pedal < initial_arm_pedal <= 1.0
                and period_hz is None
            )
        elif kind == "BOV_LIFT":
            valid = (
                arm_pedal is None
                and fire_pedal is None
                and arm_boost is None
                and initial_peak_pedal is None
                and initial_arm_pedal is None
                and initial_fire_pedal is None
                and minimum_arm_ms == 0.0
                and period_hz is None
            )
        elif kind == "LIMITER_EVENT":
            valid = (
                arm_pedal is None
                and fire_pedal is None
                and arm_boost is None
                and initial_peak_pedal is None
                and initial_arm_pedal is None
                and initial_fire_pedal is None
                and period_hz is None
            )
        else:
            valid = (
                arm_pedal is None
                and fire_pedal is None
                and arm_boost is None
                and initial_peak_pedal is None
                and initial_arm_pedal is None
                and initial_fire_pedal is None
                and period_hz is None
            )
        if not valid:
            raise ManifestValidationError(f"{policy_label} values do not match {kind}")
        result[program_id] = str(kind)
    return result


def validate_manifest(manifest: object) -> dict[str, Any]:
    value = _require_dict(manifest, "manifest")
    schema_version = value.get("schemaVersion")
    if schema_version not in SUPPORTED_MANIFEST_SCHEMA_VERSIONS:
        raise ManifestValidationError("unsupported manifest schemaVersion")
    required = {
        "schemaVersion",
        "familyId",
        "displayName",
        "memberCarIds",
        "audioFormat",
        "cars",
        "effects",
        "quirks",
        "tracks",
        "assets",
        "fidelity",
        "provenance",
    }
    if schema_version == 2:
        required.add("oneShotPrograms")
    missing = sorted(required - set(value))
    unknown = sorted(set(value) - required)
    if missing or unknown:
        raise ManifestValidationError(
            f"manifest fields mismatch: missing={missing} unknown={unknown}"
        )
    family_id = value["familyId"]
    if not isinstance(family_id, str) or not _SHA256.fullmatch(family_id):
        raise ManifestValidationError("familyId must be the full source-bank SHA-256")
    if not isinstance(value["displayName"], str) or not value["displayName"].strip():
        raise ManifestValidationError("displayName must be non-empty")
    members = _require_list(value["memberCarIds"], "memberCarIds")
    if not members or any(not isinstance(item, str) or not _IDENTIFIER.fullmatch(item) for item in members):
        raise ManifestValidationError("memberCarIds must contain valid identifiers")
    if len(set(members)) != len(members):
        raise ManifestValidationError("memberCarIds must be unique")
    audio_format = _require_dict(value["audioFormat"], "audioFormat")
    if audio_format != {
        "codec": "FLAC",
        "sampleRate": 48000,
        "channels": 2,
        "bitsPerSample": 16,
    }:
        raise ManifestValidationError("audioFormat must be FLAC PCM16/48 kHz/stereo")
    cars = _require_list(value["cars"], "cars")
    if len(cars) > MAX_CARS_PER_FAMILY:
        raise ManifestValidationError("family has too many cars")
    if (
        len(cars) != len(members)
        or {car.get("id") for car in cars if isinstance(car, dict)} != set(members)
    ):
        raise ManifestValidationError("cars must define every memberCarId exactly once")
    for index, raw_car in enumerate(cars):
        car = _require_dict(raw_car, f"cars[{index}]")
        expected_car_fields = {"id", "name", "brand", "previewPath", "engine", "gearbox"}
        if schema_version == 2:
            expected_car_fields.add("oneShotTriggerPolicies")
        if set(car) != expected_car_fields:
            raise ManifestValidationError(f"cars[{index}] fields are invalid")
        if not isinstance(car["name"], str) or not isinstance(car["brand"], str):
            raise ManifestValidationError(f"cars[{index}] name/brand must be strings")
        if car["previewPath"] is not None:
            _archive_path(car["previewPath"], f"cars[{index}].previewPath", prefix="previews")
        engine = _require_dict(car["engine"], f"cars[{index}].engine")
        expected_engine = {
            "idleRpm",
            "redlineRpm",
            "limiterRpm",
            "limiterHz",
            "tachometerMaximumRpm",
            "turboCount",
            "hybrid",
            "hybridConfig",
            "turboControllers",
        }
        if schema_version == 2:
            expected_engine.add("turboPhysics")
            expected_engine.add("throttleMap")
        if set(engine) != expected_engine:
            raise ManifestValidationError(f"cars[{index}].engine fields are invalid")
        for key in ("idleRpm", "redlineRpm", "limiterRpm", "limiterHz", "tachometerMaximumRpm"):
            if _require_number(engine[key], f"cars[{index}].engine.{key}") < 0:
                raise ManifestValidationError(f"cars[{index}].engine.{key} is negative")
        if isinstance(engine["turboCount"], bool) or not isinstance(engine["turboCount"], int) or engine["turboCount"] < 0:
            raise ManifestValidationError(f"cars[{index}].engine.turboCount is invalid")
        if not isinstance(engine["hybrid"], bool):
            raise ManifestValidationError(f"cars[{index}].engine.hybrid must be boolean")
        turbo_controllers = _require_list(engine["turboControllers"], "turboControllers")
        for controller_index, raw_controller_file in enumerate(turbo_controllers):
            controller_file = _require_dict(raw_controller_file, "turbo controller file")
            if set(controller_file) != {"file", "sha256", "controllers"}:
                raise ManifestValidationError("turbo controller file fields are invalid")
            if not isinstance(controller_file["file"], str) or not _SHA256.fullmatch(str(controller_file["sha256"])):
                raise ManifestValidationError("turbo controller file provenance is invalid")
            for raw_controller in _require_list(controller_file["controllers"], "controllers"):
                controller = _require_dict(raw_controller, "turbo controller")
                if set(controller) != {"section", "input", "combinator", "lut", "filter", "upLimit", "downLimit"}:
                    raise ManifestValidationError("turbo controller fields are invalid")
                if not all(isinstance(controller[key], str) for key in ("section", "input", "combinator")):
                    raise ManifestValidationError("turbo controller identifiers must be strings")
                for point in _require_list(controller["lut"], "turbo controller LUT"):
                    if not isinstance(point, list) or len(point) != 2:
                        raise ManifestValidationError("turbo controller LUT points must be [x,y]")
                    _require_number(point[0], "turbo controller LUT x")
                    _require_number(point[1], "turbo controller LUT y")
                for key in ("filter", "upLimit", "downLimit"):
                    _require_number(controller[key], f"turbo controller {key}")
        if schema_version == 2:
            throttle_map = _require_dict(
                engine["throttleMap"], f"cars[{index}].engine.throttleMap"
            )
            if throttle_map.get("input") != "NORMALIZED_PEDAL" or throttle_map.get(
                "output"
            ) != "NORMALIZED_ENGINE_GAS" or throttle_map.get(
                "interpolation"
            ) != "CLAMPED_LINEAR" or set(throttle_map) != {
                "input",
                "output",
                "interpolation",
                "points",
            }:
                raise ManifestValidationError("throttleMap fields are invalid")
            throttle_points = _require_list(
                throttle_map["points"], f"cars[{index}].engine.throttleMap.points"
            )
            if len(throttle_points) < 2:
                raise ManifestValidationError("throttleMap needs at least two points")
            previous_x: float | None = None
            for point_index, raw_point in enumerate(throttle_points):
                point_label = (
                    f"cars[{index}].engine.throttleMap.points[{point_index}]"
                )
                if not isinstance(raw_point, list) or len(raw_point) != 2:
                    raise ManifestValidationError(f"{point_label} must be [x,y]")
                x = _require_number(raw_point[0], f"{point_label}.x")
                y = _require_number(raw_point[1], f"{point_label}.y")
                if (
                    not 0.0 <= x <= 1.0
                    or not 0.0 <= y <= 1.0
                    or (previous_x is not None and x <= previous_x)
                ):
                    raise ManifestValidationError(f"{point_label} is invalid")
                previous_x = x
            if float(throttle_points[0][0]) != 0.0 or float(
                throttle_points[-1][0]
            ) != 1.0:
                raise ManifestValidationError("throttleMap must cover normalized 0..1")
            turbo_physics = _require_dict(
                engine["turboPhysics"], f"cars[{index}].engine.turboPhysics"
            )
            if set(turbo_physics) != {"bovPressureThreshold", "turbos"}:
                raise ManifestValidationError("turboPhysics fields are invalid")
            if _require_number(
                turbo_physics["bovPressureThreshold"],
                f"cars[{index}].engine.turboPhysics.bovPressureThreshold",
            ) < 0.0:
                raise ManifestValidationError("turboPhysics BOV threshold is negative")
            physics_turbos = _require_list(
                turbo_physics["turbos"], f"cars[{index}].engine.turboPhysics.turbos"
            )
            if len(physics_turbos) != engine["turboCount"]:
                raise ManifestValidationError("turboPhysics must define every turbo")
            mapped_controller_files: list[str] = []
            for turbo_index, raw_turbo in enumerate(physics_turbos):
                turbo_label = f"cars[{index}].engine.turboPhysics.turbos[{turbo_index}]"
                turbo = _require_dict(raw_turbo, turbo_label)
                if set(turbo) != {
                    "maximumBoost",
                    "wastegate",
                    "referenceRpm",
                    "gamma",
                    "lagUp",
                    "lagDown",
                    "controllerFile",
                }:
                    raise ManifestValidationError(f"{turbo_label} fields are invalid")
                maximum_boost = _require_number(
                    turbo["maximumBoost"], f"{turbo_label}.maximumBoost"
                )
                wastegate = _require_number(turbo["wastegate"], f"{turbo_label}.wastegate")
                reference_rpm = _require_number(
                    turbo["referenceRpm"], f"{turbo_label}.referenceRpm"
                )
                gamma = _require_number(turbo["gamma"], f"{turbo_label}.gamma")
                lag_up = _require_number(turbo["lagUp"], f"{turbo_label}.lagUp")
                lag_down = _require_number(turbo["lagDown"], f"{turbo_label}.lagDown")
                if (
                    maximum_boost <= 0.0
                    or wastegate < 0.0
                    or reference_rpm <= 0.0
                    or gamma <= 0.0
                    or lag_up < 0.0
                    or lag_down < 0.0
                ):
                    raise ManifestValidationError(f"{turbo_label} physics values are invalid")
                controller_file = turbo["controllerFile"]
                if controller_file is not None:
                    if not isinstance(controller_file, str) or not controller_file:
                        raise ManifestValidationError(
                            f"{turbo_label}.controllerFile is invalid"
                        )
                    mapped_controller_files.append(controller_file)
            declared_controller_files = [str(item["file"]) for item in turbo_controllers]
            if (
                len(mapped_controller_files) != len(set(mapped_controller_files))
                or set(mapped_controller_files) != set(declared_controller_files)
            ):
                raise ManifestValidationError(
                    "turboPhysics controllerFile mapping must be one-to-one and complete"
                )
        hybrid_config = engine["hybridConfig"]
        if engine["hybrid"] != (hybrid_config is not None):
            raise ManifestValidationError("engine.hybrid and hybridConfig disagree")
        if hybrid_config is not None:
            hybrid = _require_dict(hybrid_config, "hybridConfig")
            expected_hybrid = {
                "file",
                "sha256",
                "maximumEnergyKjPerLap",
                "dischargeTimeMs",
                "hasButtonOverride",
                "defaultController",
                "heatTorquePercent",
                "hasFrontMotors",
                "frontDischargeTimeMs",
                "controllerFiles",
            }
            if set(hybrid) != expected_hybrid:
                raise ManifestValidationError("hybridConfig fields are invalid")
            if not isinstance(hybrid["file"], str) or not _SHA256.fullmatch(str(hybrid["sha256"])):
                raise ManifestValidationError("hybridConfig provenance is invalid")
            for key in ("maximumEnergyKjPerLap", "dischargeTimeMs", "defaultController", "heatTorquePercent", "frontDischargeTimeMs"):
                _require_number(hybrid[key], f"hybridConfig.{key}")
            if not isinstance(hybrid["hasButtonOverride"], bool) or not isinstance(hybrid["hasFrontMotors"], bool):
                raise ManifestValidationError("hybridConfig flags must be boolean")
            for raw_file in _require_list(hybrid["controllerFiles"], "hybrid controllerFiles"):
                controller_file = _require_dict(raw_file, "hybrid controller file")
                if set(controller_file) != {"file", "sha256"} or not isinstance(controller_file["file"], str) or not _SHA256.fullmatch(str(controller_file["sha256"])):
                    raise ManifestValidationError("hybrid controller file is invalid")
        gearbox = _require_dict(car["gearbox"], f"cars[{index}].gearbox")
        expected_gearbox = {
            "traction",
            "forwardRatios",
            "reverseRatio",
            "finalDrive",
            "upshiftRpm",
            "downshiftLandingRpmByGear",
            "upshiftTimeMs",
            "downshiftTimeMs",
            "alternateGearSets",
        }
        if schema_version == 2:
            expected_gearbox.add("engineGasAssist")
        if set(gearbox) != expected_gearbox:
            raise ManifestValidationError(f"cars[{index}].gearbox fields are invalid")
        ratios = _require_list(gearbox["forwardRatios"], "forwardRatios")
        if not ratios or any(_require_number(item, "gear ratio") == 0 for item in ratios):
            raise ManifestValidationError("forwardRatios must be non-zero numbers")
        for key in ("reverseRatio", "finalDrive", "upshiftRpm", "upshiftTimeMs", "downshiftTimeMs"):
            _require_number(gearbox[key], f"cars[{index}].gearbox.{key}")
        landings = _require_dict(gearbox["downshiftLandingRpmByGear"], "downshiftLandingRpmByGear")
        for gear, rpm in landings.items():
            if not str(gear).isdigit() or _require_number(rpm, "landing RPM") <= 0:
                raise ManifestValidationError("downshiftLandingRpmByGear is invalid")
        for raw_set in _require_list(gearbox["alternateGearSets"], "alternateGearSets"):
            gear_set = _require_dict(raw_set, "alternate gear set")
            if set(gear_set) != {"file", "sha256", "options"}:
                raise ManifestValidationError("alternate gear set fields are invalid")
            if not isinstance(gear_set["file"], str) or not _SHA256.fullmatch(str(gear_set["sha256"])):
                raise ManifestValidationError("alternate gear set provenance is invalid")
            for raw_option in _require_list(gear_set["options"], "alternate gear options"):
                option = _require_dict(raw_option, "alternate gear option")
                if set(option) != {"label", "ratio"} or not isinstance(option["label"], str):
                    raise ManifestValidationError("alternate gear option is invalid")
                _require_number(option["ratio"], "alternate gear ratio")
        if schema_version == 2:
            assist = _require_dict(
                gearbox["engineGasAssist"],
                f"cars[{index}].gearbox.engineGasAssist",
            )
            if set(assist) != {
                "autoShifterGasCutoffMs",
                "engineCutoffMs",
                "autoBlipElectronic",
                "autoBlipEnableMode",
                "autoBlipClutchGateExclusive",
                "autoBlipProfile",
                "autoBlipEndTimeMs",
                "autoBlipEvaluator",
                "autoBlipCombiner",
                "processingOrder",
            }:
                raise ManifestValidationError("engineGasAssist fields are invalid")
            if (
                assist["autoBlipEnableMode"] != "ELECTRONIC_OR_AUTOCLUTCH"
                or assist["autoBlipEvaluator"]
                != "AUTHORED_ORDER_FIRST_UPPER_BOUND_LINEAR"
                or assist["autoBlipCombiner"] != "MAX_WITH_POST_ASSIST_PEDAL"
                or assist["processingOrder"]
                != "AUTOBLIP_THEN_AUTO_SHIFTER_CUT_THEN_ENGINE_CUTOFF_THEN_THROTTLE_MAP_THEN_LIMITER_CUT"
            ):
                raise ManifestValidationError("engineGasAssist execution contract is invalid")
            if not isinstance(assist["autoBlipElectronic"], bool):
                raise ManifestValidationError("engineGasAssist electronic flag is invalid")
            for key in (
                "autoShifterGasCutoffMs",
                "engineCutoffMs",
                "autoBlipClutchGateExclusive",
                "autoBlipEndTimeMs",
            ):
                if _require_number(assist[key], f"engineGasAssist.{key}") < 0.0:
                    raise ManifestValidationError(
                        "engineGasAssist values must be nonnegative"
                    )
            if not math.isclose(
                float(assist["autoBlipClutchGateExclusive"]),
                1.0 / math.pi,
                rel_tol=0.0,
                abs_tol=1.0e-12,
            ):
                raise ManifestValidationError("engineGasAssist clutch gate changed")
            profile = _require_list(
                assist["autoBlipProfile"], "engineGasAssist.autoBlipProfile"
            )
            if len(profile) not in {0, 4}:
                raise ManifestValidationError(
                    "engineGasAssist requires zero or four ordered points"
                )
            parsed_profile: list[tuple[float, float]] = []
            for point_index, raw_point in enumerate(profile):
                if not isinstance(raw_point, list) or len(raw_point) != 2:
                    raise ManifestValidationError(
                        f"engineGasAssist.autoBlipProfile[{point_index}] is invalid"
                    )
                point_x = _require_number(raw_point[0], "AutoBlip point time")
                point_y = _require_number(raw_point[1], "AutoBlip point pedal")
                if point_x < 0.0 or not 0.0 <= point_y <= 1.0:
                    raise ManifestValidationError(
                        "engineGasAssist AutoBlip point is invalid"
                    )
                parsed_profile.append((point_x, point_y))
            # Do not sort or require increasing x. Nineteen official profiles
            # depend on AC's insertion-order first-upper-bound evaluator.
            if parsed_profile and (
                parsed_profile[0] != (0.0, 0.0)
                or parsed_profile[-1][1] != 0.0
                or parsed_profile[-1][0] != float(assist["autoBlipEndTimeMs"])
            ):
                raise ManifestValidationError(
                    "engineGasAssist ordered profile/end disagree"
                )
            if not parsed_profile and float(assist["autoBlipEndTimeMs"]) != 0.0:
                raise ManifestValidationError(
                    "engineGasAssist empty profile requires zero end time"
                )
        if schema_version == 2:
            _validate_one_shot_policies(
                car["oneShotTriggerPolicies"], f"cars[{index}].oneShotTriggerPolicies"
            )
    effects = _require_dict(value["effects"], "effects")
    expected_effects = {
        "idle",
        "coast",
        "texture",
        "intake",
        "exhaust",
        "turbo",
        "spool",
        "bov",
        "transmission",
        "limiter",
        "shift",
        "overrun",
        "popsBangsCracks",
        "engineStart",
    }
    if set(effects) != expected_effects or any(not isinstance(item, bool) for item in effects.values()):
        raise ManifestValidationError("effects must contain the complete boolean capability map")
    quirks = _require_list(value["quirks"], "quirks")
    if any(not isinstance(item, str) or not item for item in quirks):
        raise ManifestValidationError("quirks must contain strings")
    tracks = _require_list(value["tracks"], "tracks")
    if not tracks:
        raise ManifestValidationError("manifest must contain audio tracks")
    if len(tracks) > MAX_TRACKS:
        raise ManifestValidationError("manifest has too many tracks")
    paths = [
        _validate_track(track, index, schema_version)
        for index, track in enumerate(tracks)
    ]
    # Several official banks legitimately reuse one authored recording for
    # distinct semantic programs (for example, separate up/down shift
    # triggers).  Keep both tracks while storing/decoding one immutable media
    # payload.  A shared path is accepted only when every physical-media field
    # is identical; role, curves, loop bounds and triggers remain track-local.
    media_by_path: dict[str, tuple[object, ...]] = {}
    for path, track in zip(paths, tracks):
        identity = (
            track["flacSha256"],
            track["pcmSha256"],
            track["frameCount"],
            track["sampleRate"],
            track["channels"],
            track["bitsPerSample"],
        )
        previous = media_by_path.setdefault(path, identity)
        if previous != identity:
            raise ManifestValidationError(
                "tracks sharing a path must declare identical physical media"
            )
    track_ids = [track["id"] for track in tracks]
    if len(set(track_ids)) != len(track_ids):
        raise ManifestValidationError("track ids must be unique")
    program_triggers = (
        _validate_one_shot_programs(value["oneShotPrograms"], tracks)
        if schema_version == 2
        else {}
    )
    if schema_version == 2:
        for index, car in enumerate(cars):
            policies = _validate_one_shot_policies(
                car["oneShotTriggerPolicies"], f"cars[{index}].oneShotTriggerPolicies"
            )
            expected_policies = {
                program_id: ONE_SHOT_POLICY_KIND_BY_TRIGGER[trigger]
                for program_id, trigger in program_triggers.items()
                if trigger in ONE_SHOT_POLICY_KIND_BY_TRIGGER
            }
            if policies != expected_policies:
                raise ManifestValidationError(
                    f"cars[{index}] must define one matching policy per one-shot program"
                )
    roles = [track["role"] for track in tracks]
    if "IDLE" not in roles:
        raise ManifestValidationError("every family requires authored audible IDLE")
    derived_effects = derive_effect_capabilities(roles)
    if effects != derived_effects:
        raise ManifestValidationError("effects must exactly describe retained track roles")
    if sum(track["frameCount"] * 4 for track in tracks) > MAX_DECODED_PCM_BYTES:
        raise ManifestValidationError("family exceeds the decoded PCM hard limit")
    assets = _require_list(value["assets"], "assets")
    if len(assets) > MAX_ASSETS:
        raise ManifestValidationError("manifest has too many preview assets")
    asset_paths: list[str] = []
    for index, raw in enumerate(assets):
        asset = _require_dict(raw, f"assets[{index}]")
        if set(asset) != {"path", "sha256", "mediaType"}:
            raise ManifestValidationError(f"assets[{index}] fields are invalid")
        path = _archive_path(asset["path"], f"assets[{index}].path", prefix="previews")
        if path in paths:
            raise ManifestValidationError("asset and track paths collide")
        if not isinstance(asset["sha256"], str) or not _SHA256.fullmatch(asset["sha256"]):
            raise ManifestValidationError(f"assets[{index}].sha256 is invalid")
        if asset["mediaType"] not in ("image/jpeg", "image/png", "image/webp"):
            raise ManifestValidationError(f"assets[{index}].mediaType is unsupported")
        asset_paths.append(path)
    if len(set(asset_paths)) != len(asset_paths):
        raise ManifestValidationError("asset paths must be unique")
    declared_previews = {car["previewPath"] for car in cars if car["previewPath"] is not None}
    if declared_previews != set(asset_paths):
        raise ManifestValidationError("car previewPath values must exactly match assets")
    fidelity = _require_dict(value["fidelity"], "fidelity")
    if set(fidelity) != {"sourceAudio", "layerIsolation", "rpmGainCurve", "effectVariants", "notes"}:
        raise ManifestValidationError("fidelity fields are invalid")
    if fidelity["sourceAudio"] != "nativeFmodFinalMix":
        raise ManifestValidationError("fidelity.sourceAudio is unsupported")
    if fidelity["layerIsolation"] not in ("eventLevel", "sourceInstrument"):
        raise ManifestValidationError("fidelity.layerIsolation is unsupported")
    if fidelity["rpmGainCurve"] not in ("compilerWindowApproximation", "authoredSourceInstrument"):
        raise ManifestValidationError("fidelity.rpmGainCurve is unsupported")
    supported_effect_variants = (
        ("authoredOneShotTopology",)
        if schema_version == 2
        else ("nativeRandomSequence", "singleEventTake")
    )
    if fidelity["effectVariants"] not in supported_effect_variants:
        raise ManifestValidationError("fidelity.effectVariants is unsupported")
    notes = _require_list(fidelity["notes"], "fidelity.notes")
    if any(not isinstance(item, str) or not item for item in notes):
        raise ManifestValidationError("fidelity.notes must contain strings")
    provenance = _require_dict(value["provenance"], "provenance")
    expected_provenance = {
        "source",
        "sourceBankSha256",
        "catalogSha256",
        "capturePlanSha256",
        "referenceRenderer",
        "familyAttenuationDb",
        "defaultMixPeakDbfs",
        "encoder",
    }
    if schema_version == 2:
        expected_provenance.add("authoredDsp")
        expected_provenance.add("certifiedSilentSources")
        expected_provenance.add("softwareChannelPriorityOracleSha256")
    if set(provenance) != expected_provenance:
        raise ManifestValidationError("provenance fields are invalid")
    if provenance.get("sourceBankSha256") != family_id:
        raise ManifestValidationError("provenance sourceBankSha256 must match familyId")
    for key in ("catalogSha256", "capturePlanSha256"):
        if not isinstance(provenance[key], str) or not _SHA256.fullmatch(provenance[key]):
            raise ManifestValidationError(f"provenance.{key} is invalid")
    if not all(isinstance(provenance[key], str) and provenance[key] for key in ("source", "referenceRenderer")):
        raise ManifestValidationError("provenance source/renderer must be strings")
    if _require_number(provenance["familyAttenuationDb"], "provenance.familyAttenuationDb") > 0:
        raise ManifestValidationError("provenance.familyAttenuationDb may not amplify")
    if _require_number(provenance["defaultMixPeakDbfs"], "provenance.defaultMixPeakDbfs") > -3.0:
        raise ManifestValidationError("default continuous mix exceeds the -3 dBFS ceiling")
    maximum_rpm = max(float(car["engine"]["tachometerMaximumRpm"]) for car in cars)
    calculated_mix_peak = default_mix_peak_bound_dbfs(tracks, maximum_rpm)
    if calculated_mix_peak > float(provenance["defaultMixPeakDbfs"]) + 0.01:
        raise ManifestValidationError(
            "provenance.defaultMixPeakDbfs understates the validated peak-sum bound"
        )
    encoder = _require_dict(provenance["encoder"], "provenance.encoder")
    if set(encoder) != {"name", "version", "executableSha256"}:
        raise ManifestValidationError("provenance.encoder fields are invalid")
    if not isinstance(encoder["name"], str) or not isinstance(encoder["version"], str):
        raise ManifestValidationError("provenance.encoder name/version must be strings")
    if not isinstance(encoder["executableSha256"], str) or not _SHA256.fullmatch(encoder["executableSha256"]):
        raise ManifestValidationError("provenance.encoder executableSha256 is invalid")
    if schema_version == 2:
        priority_oracle_sha = provenance[
            "softwareChannelPriorityOracleSha256"
        ]
        if (
            not isinstance(priority_oracle_sha, str)
            or not _SHA256.fullmatch(priority_oracle_sha)
        ):
            raise ManifestValidationError(
                "provenance.softwareChannelPriorityOracleSha256 is invalid"
            )
        authored_dsp = _require_list(
            provenance["authoredDsp"], "provenance.authoredDsp"
        )
        if len(authored_dsp) > 1:
            raise ManifestValidationError("provenance.authoredDsp has unknown DSPs")
        for index, raw_dsp in enumerate(authored_dsp):
            label = f"provenance.authoredDsp[{index}]"
            dsp = _require_dict(raw_dsp, label)
            if set(dsp) != {
                "name",
                "version",
                "parameters",
                "treatment",
                "evidence",
            }:
                raise ManifestValidationError(f"{label} fields are invalid")
            parameters = _require_dict(dsp["parameters"], f"{label}.parameters")
            if (
                dsp["name"] != "FMOD Gain"
                or dsp["version"] != 65536
                or set(parameters) != {"gainDb", "invert"}
                or _require_number(parameters["gainDb"], f"{label}.parameters.gainDb")
                != -0.5
                or parameters["invert"] is not False
                or dsp["treatment"] != "BAKED_INTO_TARGET_ONLY_CAPTURE"
                or dsp["evidence"] != "FMOD108_SET_PARAMETER_CALLBACK"
            ):
                raise ManifestValidationError(f"{label} contract is unsupported")
        if bool(authored_dsp) != (
            "requiresBmwM3E30GraAdditionalDsp" in quirks
        ):
            raise ManifestValidationError(
                "provenance.authoredDsp must exactly match the BMW Gr.A quirk"
            )
        silent_sources = _require_list(
            provenance["certifiedSilentSources"],
            "provenance.certifiedSilentSources",
        )
        if len(silent_sources) > MAX_CERTIFIED_SILENT_SOURCES:
            raise ManifestValidationError(
                "provenance.certifiedSilentSources has an unsupported count"
            )
        silent_hashes: set[str] = set()
        silent_identities: set[tuple[str, str]] = set()
        silent_source_guids: set[str] = set()
        provenance_turbo_silent_nodes: set[tuple[str, str, str]] = set()
        for index, raw_source in enumerate(silent_sources):
            label = f"provenance.certifiedSilentSources[{index}]"
            source = _require_dict(raw_source, label)
            identity = (str(source.get("sourceGuid")), str(source.get("role")))
            if set(source) != {
                "sourceGuid",
                "role",
                "disposition",
                "verificationPayloadSha256",
            } or (
                not isinstance(source["sourceGuid"], str)
                or not _GUID.fullmatch(source["sourceGuid"])
                or source["role"]
                not in {
                    "LIMITER",
                    "BOV",
                    "TURBO_TRANSIENT",
                    "SHIFT_UP",
                    "SHIFT_DOWN",
                }
                or source["disposition"] != "AUTHORED_TARGET_SILENT"
                or not isinstance(source["verificationPayloadSha256"], str)
                or not _SHA256.fullmatch(source["verificationPayloadSha256"])
                or source["verificationPayloadSha256"] in silent_hashes
                or identity in silent_identities
                or source["sourceGuid"] in silent_source_guids
            ):
                raise ManifestValidationError(f"{label} is invalid")
            silent_hashes.add(source["verificationPayloadSha256"])
            silent_identities.add(identity)
            silent_source_guids.add(source["sourceGuid"])
            if source["role"] in {"BOV", "TURBO_TRANSIENT"}:
                provenance_turbo_silent_nodes.add(
                    (
                        source["sourceGuid"],
                        source["role"],
                        source["verificationPayloadSha256"],
                    )
                )
            elif source["role"] in {"SHIFT_UP", "SHIFT_DOWN"}:
                compact_guid = source["sourceGuid"].replace("-", "")
                silent_track_id = (
                    f"{source['role'].casefold()}_{compact_guid[:16]}"
                )
                silent_node_id = f"track_{compact_guid}"
                if any(track["id"] == silent_track_id for track in tracks) or any(
                    node.get("id") == silent_node_id
                    for program in value["oneShotPrograms"]
                    for node in program["nodes"]
                ):
                    raise ManifestValidationError(
                        f"{label} is also present as an audible shift source"
                    )
        audible_verified_hashes = {
            program["policy"]["sourceVerificationPayloadSha256"]
            for program in value["oneShotPrograms"]
            if program["trigger"] == "LIMITER_EVENT"
        }
        audible_verified_hashes.update(
            node["sourceVerificationPayloadSha256"]
            for program in value["oneShotPrograms"]
            if program["trigger"] == "TURBO_EVENT"
            for node in program["nodes"]
            if node["kind"] == "TRACK"
        )
        manifest_turbo_silent_nodes = {
            (
                node["sourceGuid"],
                node["resolvedRole"],
                node["sourceVerificationPayloadSha256"],
            )
            for program in value["oneShotPrograms"]
            if program["trigger"] == "TURBO_EVENT"
            for node in program["nodes"]
            if node["kind"] == "SILENT_SOURCE"
        }
        if silent_hashes & audible_verified_hashes:
            raise ManifestValidationError(
                "one source verification cannot be both audible and authored-silent"
            )
        if manifest_turbo_silent_nodes != provenance_turbo_silent_nodes:
            raise ManifestValidationError(
                "turbo SILENT_SOURCE nodes and certified-silent provenance disagree"
            )
    _reject_forbidden_token(value)
    return value


def parse_manifest(data: bytes) -> dict[str, Any]:
    if len(data) > MAX_MANIFEST_BYTES:
        raise ManifestValidationError("manifest.json exceeds the size limit")
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestValidationError(f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ManifestValidationError(f"invalid manifest JSON: {exc}") from exc
    return validate_manifest(value)


def validate_release_manifest(manifest: object) -> dict[str, Any]:
    """Apply fidelity gates required before a pack may be called release-ready."""

    value = validate_manifest(manifest)
    if value["schemaVersion"] != 2:
        raise ManifestValidationError("release fidelity requires schemaVersion 2")
    fidelity = value["fidelity"]
    if fidelity["layerIsolation"] != "sourceInstrument":
        raise ManifestValidationError(
            "release fidelity requires source-instrument role isolation"
        )
    if fidelity["rpmGainCurve"] != "authoredSourceInstrument":
        raise ManifestValidationError(
            "release fidelity requires authored source-instrument RPM/gain curves"
        )
    if fidelity["effectVariants"] != "authoredOneShotTopology":
        raise ManifestValidationError(
            "release fidelity requires authored one-shot topology"
        )
    return value


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o100644 << 16
    info.create_system = 3
    return info


def build_aclib(manifest: dict[str, Any], source_directory: Path, output_path: Path) -> Path:
    value = validate_manifest(manifest)
    source = source_directory.resolve()
    members = sorted(
        {track["path"] for track in value["tracks"]}
        | {asset["path"] for asset in value["assets"]}
    )
    expected_hashes = {
        track["path"]: track["flacSha256"] for track in value["tracks"]
    }
    expected_hashes.update({asset["path"]: asset["sha256"] for asset in value["assets"]})
    for name in members:
        path = (source / PurePosixPath(name)).resolve()
        if source not in path.parents:
            raise ManifestValidationError(f"pack source escapes root: {name}")
        if not path.is_file():
            raise FileNotFoundError(path)
        if sha256_file(path) != expected_hashes[name]:
            raise ManifestValidationError(f"content hash mismatch for {name}")

    output = output_path.resolve()
    if output.suffix.casefold() != ".aclib":
        raise ManifestValidationError("pack output must use the .aclib extension")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest_bytes = canonical_json_bytes(value) + b"\n"
    with tempfile.NamedTemporaryFile(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent, delete=False
    ) as temporary:
        temp_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temp_path, "w", allowZip64=True) as archive:
            archive.writestr(_zip_info(MANIFEST_NAME), manifest_bytes)
            for name in members:
                path = source / PurePosixPath(name)
                archive.writestr(_zip_info(name), path.read_bytes())
        os.replace(temp_path, output)
    finally:
        temp_path.unlink(missing_ok=True)
    validate_aclib(output)
    return output


def validate_aclib(path: Path, *, codec: PinnedFlacCodec | None = None) -> dict[str, Any]:
    with zipfile.ZipFile(path, "r") as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ManifestValidationError("pack has duplicate ZIP members")
        if MANIFEST_NAME not in names:
            raise ManifestValidationError("pack has no manifest.json")
        for name in names:
            _archive_path(name, f"ZIP member {name!r}")
            info = archive.getinfo(name)
            if info.is_dir():
                raise ManifestValidationError("directory ZIP members are not allowed")
            if name == MANIFEST_NAME and info.file_size > MAX_MANIFEST_BYTES:
                raise ManifestValidationError("manifest.json exceeds the size limit")
            if name.startswith("audio/") and info.file_size > MAX_AUDIO_MEMBER_BYTES:
                raise ManifestValidationError(f"audio member is too large: {name}")
            if name.startswith("previews/") and info.file_size > MAX_PREVIEW_MEMBER_BYTES:
                raise ManifestValidationError(f"preview member is too large: {name}")
        manifest = parse_manifest(archive.read(MANIFEST_NAME))
        expected = {MANIFEST_NAME}
        expected.update(track["path"] for track in manifest["tracks"])
        expected.update(asset["path"] for asset in manifest["assets"])
        if set(names) != expected:
            raise ManifestValidationError(
                f"pack members mismatch: missing={sorted(expected-set(names))} "
                f"unexpected={sorted(set(names)-expected)}"
            )
        for track in manifest["tracks"]:
            encoded = archive.read(track["path"])
            if not encoded.startswith(b"fLaC"):
                raise ManifestValidationError(f"invalid FLAC signature for {track['path']}")
            if sha256_bytes(encoded) != track["flacSha256"]:
                raise ManifestValidationError(f"FLAC hash mismatch for {track['path']}")
        decoded_bytes = sum(
            track["frameCount"] * 4
            for path, track in {
                track["path"]: track for track in manifest["tracks"]
            }.items()
        )
        if decoded_bytes > MAX_DECODED_PCM_BYTES:
            raise ManifestValidationError("family exceeds the decoded PCM hard limit")
        for asset in manifest["assets"]:
            if sha256_bytes(archive.read(asset["path"])) != asset["sha256"]:
                raise ManifestValidationError(f"asset hash mismatch for {asset['path']}")
        if codec is not None:
            # Keep potentially large verification members on the pack's
            # volume.  Release packs live on D:, so validation never consumes
            # the constrained system TEMP/C: drive.
            with tempfile.TemporaryDirectory(
                prefix="aclib-verify-", dir=Path(path).resolve().parent
            ) as temp_text:
                temp = Path(temp_text)
                verified_paths: set[str] = set()
                for index, track in enumerate(manifest["tracks"]):
                    if track["path"] in verified_paths:
                        continue
                    verified_paths.add(track["path"])
                    extracted = temp / f"{index}.flac"
                    extracted.write_bytes(archive.read(track["path"]))
                    integrity = codec.decode_integrity(extracted)
                    if (
                        integrity.sha256 != track["pcmSha256"]
                        or integrity.frame_count != track["frameCount"]
                    ):
                        raise ManifestValidationError(
                            f"decoded PCM integrity mismatch for {track['path']}"
                        )
    return manifest
