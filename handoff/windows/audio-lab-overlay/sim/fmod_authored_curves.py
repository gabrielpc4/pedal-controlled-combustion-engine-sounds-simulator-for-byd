"""Compile bounded FMOD 1.08 continuous-source automation into manifest curves.

The input is deliberately the filename-free bank graph and one row emitted by
``sim.fmod_graph_roles``.  Embedded sample names are neither inspected nor
copied into the result.  A target-only FMOD render is expected to bake the
instrument's static base volume, routing and DSP into the captured PCM; the
curves returned here are normalized ratios relative to that capture point.

FMOD 1.08 type-0 segments use exponential interpolation and type-1 segments use
a two-handle piecewise-power ease.  Those formulas, the shape scales, and the
legacy negative-infinity fader tail below -18.86 dB are calibrated against AC's
shipped FMOD 1.08.12 runtime.  They are explicitly reported as oracle-calibrated
approximations rather than undocumented FMOD source truth.  The final pack
compiler must still compare a target-only render with the oracle.
"""

from __future__ import annotations

from collections import defaultdict
import bisect
import copy
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Callable, Mapping, cast


BANK_GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
AUTHORED_CURVE_SCHEMA = "ac-fmod-authored-engine-curves-v1"
ONE_SHOT_CURVE_SCHEMA = "ac-fmod-authored-one-shot-curves-v1"
ENGINE_TRANSIENT_VERIFICATION_SCHEMA = (
    "ac-fmod-engine-transient-source-verification-v1"
)
ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION = (
    "fmod108-source-bound-dsp-clock-aligned-pcm16-v1"
)
LIMITER_PROGRAM_SCHEMA = "ac-fmod-authored-limiter-program-v1"
LIMITER_VERIFICATION_SCHEMA = "ac-fmod-limiter-source-verification-v1"
TURBO_TRANSIENT_SOURCE_SCHEMA = "ac-fmod-authored-turbo-transient-source-v1"
TURBO_TRANSIENT_VERIFICATION_SCHEMA = (
    "ac-fmod-turbo-transient-source-verification-v1"
)
TURBO_CONTROL_GAIN_ORACLE_VERSION = (
    "fmod108-source-bound-routed-gain-f32-adaptive-v1"
)
TURBO_PCM_CAPTURE_ORACLE_VERSION = (
    "fmod108-source-bound-pcm16-dsp-schedule-normalized-v1"
)
WINDOWED_CAPTURE_FALLBACK_SCHEMA = "ac-fmod-authored-windowed-capture-fallback-v1"
TARGET_ONLY_CAPTURE_RECIPE_SCHEMA = "ac-fmod-target-only-capture-recipe-v1"

_ALLOWED_POLICY = "allowCandidate"
_ENGINE_SUFFIXES = frozenset(("engine_ext", "engine_int"))
_TRANSMISSION_SUFFIX = "transmission"
_TURBO_SUFFIX = "turbo"
_TURBO_TRANSIENT_CLASSIFIER_ROLE = "TURBO_TRANSIENT_CANDIDATE"
_TURBO_RUNTIME_PARAMETERS = frozenset(("boost", "bov", "bov_decay"))
_PROPERTY_ONE_RAW_TO_SEMITONES = 24.0
_TURBO_TIMELINE_CAPTURE_LOOKAHEAD_FRAMES = 1024
_RPM_PARAMETER = "rpms"
_DRIVETRAIN_SPEED_PARAMETER = "drivetrain_speed"
_THROTTLE_PARAMETER = "throttle"
_DISTANCE_PARAMETER = "distance"
_EVENT_CONE_ANGLE_PARAMETER = "event cone angle"
_FIXED_AUTOMATIC_PARAMETERS = frozenset(
    (_DISTANCE_PARAMETER, _EVENT_CONE_ANGLE_PARAMETER)
)
DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS = {
    # SilentFmodReferenceRenderer's default emitter/listener positions are
    # (0,.5,0)/(0,.7,0), and both forward vectors are +Z.
    _DISTANCE_PARAMETER: 0.2,
    _EVENT_CONE_ANGLE_PARAMETER: 90.0,
}
_EXPECTED_AUTOMATIC_PARAMETER_TYPES = {
    _DISTANCE_PARAMETER: "FMOD_STUDIO_PARAMETER_AUTOMATIC_DISTANCE",
    _EVENT_CONE_ANGLE_PARAMETER: "FMOD_STUDIO_PARAMETER_AUTOMATIC_EVENT_CONE_ANGLE",
}
_SUPPORTED_PROPERTY_PARAMETERS = {
    0: frozenset(
        (
            _RPM_PARAMETER,
            _DRIVETRAIN_SPEED_PARAMETER,
            _THROTTLE_PARAMETER,
            _DISTANCE_PARAMETER,
            _EVENT_CONE_ANGLE_PARAMETER,
        )
    ),
    4: frozenset(
        (_RPM_PARAMETER, _DRIVETRAIN_SPEED_PARAMETER, _THROTTLE_PARAMETER)
    ),
}
_RADIANS_PER_SECOND_TO_RPM = 60.0 / (2.0 * math.pi)
_SHAPE_EXPONENT_SCALE = 6.9522
_SHAPE_LINEAR_EPSILON = 1.0e-7
_NEGATIVE_INFINITY_RAW = -42.0
_DEFAULT_INTERPOLATION_TOLERANCE = 2.0e-4
_MAX_ADAPTIVE_DEPTH = 14
_MAX_VALIDATION_REFINEMENTS = 14
_VALIDATION_PROBES_PER_SEGMENT = 32
_GATE_TRANSITION_DOMAIN_FRACTION = 1.0e-6
_DEFAULT_FALLBACK_PITCH_ERROR_CENTS = 5.0
_DEFAULT_FALLBACK_GAIN_ERROR_DB = 0.25
_DEFAULT_FALLBACK_MAX_WINDOWS = 64
_REFERENCE_RENDERER_VERSION = "FMOD Studio API 1.08.12"
_FMOD_BANK_TIMELINE_TICKS_PER_SECOND = 48000
_AC_LIMITER_EXECUTABLE_SHA256 = (
    "0df569c840f8303f7018f7891085e3a4c22cf93fb19327c6a0b85325cea23fd1"
)

# AC/FMOD 1.08 volume automation has a special taper to digital silence.  The
# bank serializes the Studio -infinity point as -42.  Above the first entry the
# value follows ordinary dB amplitude.  The entries below were measured from a
# target-only Tatuus source through WAVWRITER_NRT at fixed RPM and phase.  The
# table is intentionally linear-amplitude data so its quiet-tail interpolation
# error remains bounded in the domain that matters to the mixer.
_FMOD108_INFINITY_TAIL: tuple[tuple[float, float], ...] = (
    (-42.000000000, 0.000000000000),
    (-40.903253652, 0.000237369151),
    (-39.834686130, 0.000529441306),
    (-38.793573433, 0.001083968591),
    (-37.779210159, 0.001598481011),
    (-36.790909034, 0.002335279701),
    (-35.828000440, 0.003323185993),
    (-34.889831964, 0.004353294348),
    (-33.975767954, 0.005663291094),
    (-33.085189093, 0.007317820282),
    (-32.217491974, 0.009393305994),
    (-31.372088693, 0.011555500234),
    (-30.548406452, 0.013969025071),
    (-29.745887171, 0.016803926237),
    (-28.963987108, 0.020119030897),
    (-28.202176489, 0.023976694741),
    (-27.459939156, 0.028445131500),
    (-26.736772209, 0.033021764698),
    (-26.032185670, 0.037079380229),
    (-25.345702152, 0.041511165468),
    (-24.676856532, 0.046338244742),
    (-24.025195636, 0.051580549415),
    (-23.390277936, 0.057257760227),
    (-22.771673248, 0.063390557615),
    (-22.168962438, 0.069996097578),
    (-21.581737144, 0.077093775320),
    (-21.009599495, 0.084700337605),
    (-20.452161843, 0.092832892845),
    (-19.909046498, 0.101052707337),
    (-19.379885476, 0.107400519903),
    (-18.864320247, 0.113967574431),
)
_FMOD108_INFINITY_TAIL_X = tuple(item[0] for item in _FMOD108_INFINITY_TAIL)


class FmodAuthoredCurveError(ValueError):
    """A typed, fail-closed source-curve compilation error."""

    def __init__(self, code: str, detail: str):
        self.code = str(code)
        self.detail = str(detail)
        super().__init__(f"{self.code}: {self.detail}")


def _fail(code: str, detail: str) -> None:
    raise FmodAuthoredCurveError(code, detail)


def _guid(value: Any) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _finite(value: Any, *, code: str, detail: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        _fail(code, detail)
    if not math.isfinite(result):
        _fail(code, detail)
    return result


def _event_suffix(path: Any) -> str:
    value = str(path or "").strip().casefold().rstrip("/")
    return value.rsplit("/", 1)[-1] if value else ""


def _objects_by_guid(
    report: dict[str, Any], key: str, *, required: bool = True
) -> dict[str, dict[str, Any]]:
    raw = report.get(key)
    if raw is None and not required:
        return {}
    if not isinstance(raw, list):
        _fail("invalidGraph", f"{key} must be an array")
    result: dict[str, dict[str, Any]] = {}
    for item in raw:
        if not isinstance(item, dict):
            _fail("invalidGraph", f"{key} contains a non-object")
        guid = _guid(item.get("guid"))
        if not guid:
            _fail("invalidGraph", f"{key} contains an object without a GUID")
        if guid in result:
            _fail("invalidGraph", f"{key} contains duplicate GUID {guid}")
        result[guid] = item
    return result


def _instrument_chain(
    source_guid: str, instruments: dict[str, dict[str, Any]]
) -> list[tuple[str, dict[str, Any], int]]:
    parents: dict[str, set[str]] = defaultdict(set)
    for parent_guid, parent in instruments.items():
        children = parent.get("childInstruments", [])
        if not isinstance(children, list):
            _fail("invalidGraph", f"instrument {parent_guid} children are invalid")
        for child in children:
            child_guid = _guid(child.get("guid")) if isinstance(child, dict) else _guid(child)
            if child_guid:
                parents[child_guid].add(parent_guid)

    chain: list[tuple[str, dict[str, Any], int]] = []
    visited: set[str] = set()
    current = source_guid
    while current:
        if current in visited:
            _fail("unsupportedOwnerTopology", "instrument ancestry contains a cycle")
        visited.add(current)
        node = instruments.get(current)
        if node is None:
            _fail("unsupportedOwnerTopology", f"missing ancestor {current}")
        chain.append((current, node, len(chain)))
        parent_guids = sorted(parents.get(current, set()))
        if len(parent_guids) > 1:
            _fail(
                "unsupportedOwnerTopology",
                f"instrument {current} has multiple ancestor paths",
            )
        current = parent_guids[0] if parent_guids else ""
    return chain


def _curve_points(
    controller: dict[str, Any],
) -> tuple[tuple[float, float, float, int], ...]:
    guid = _guid(controller.get("guid"))
    raw = controller.get("points")
    if not isinstance(raw, list) or not raw:
        _fail("unsupportedCurve", f"controller {guid} has no curve points")
    points: list[tuple[float, float, float, int]] = []
    previous_x: float | None = None
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            _fail("unsupportedCurve", f"controller {guid} point {index} is invalid")
        x = _finite(item.get("x"), code="unsupportedCurve", detail=f"controller {guid} has invalid x")
        y = _finite(item.get("y"), code="unsupportedCurve", detail=f"controller {guid} has invalid y")
        shape = _finite(
            item.get("shape", 0.0),
            code="unsupportedCurve",
            detail=f"controller {guid} has invalid shape",
        )
        try:
            point_type = int(item.get("type", 0))
        except (TypeError, ValueError):
            _fail("unsupportedCurvePointType", f"controller {guid} has an invalid point type")
        if point_type not in {0, 1}:
            _fail(
                "unsupportedCurvePointType",
                f"controller {guid} uses point type {point_type}",
            )
        if shape < -1.000001 or shape > 1.000001:
            _fail("unsupportedCurveShape", f"controller {guid} shape {shape} is outside -1..1")
        if previous_x is not None and x <= previous_x:
            _fail("unsupportedCurve", f"controller {guid} x values do not increase")
        previous_x = x
        points.append((x, y, shape, point_type))
    return tuple(points)


def evaluate_type0_curve(
    points: tuple[tuple[float, float, float], ...], x: float
) -> float:
    """Evaluate the parsed FMOD 1.08 type-0 exponential segment."""

    if not points:
        raise ValueError("curve points are empty")
    x = float(x)
    if x <= points[0][0]:
        return points[0][1]
    if x >= points[-1][0]:
        return points[-1][1]
    xs = [point[0] for point in points]
    right_index = bisect.bisect_left(xs, x)
    left_x, left_y, shape = points[right_index - 1]
    right_x, right_y, _ = points[right_index]
    t = (x - left_x) / (right_x - left_x)
    k = shape * _SHAPE_EXPONENT_SCALE
    fraction = t if abs(k) <= _SHAPE_LINEAR_EPSILON else math.expm1(k * t) / math.expm1(k)
    return left_y + ((right_y - left_y) * fraction)


def evaluate_authored_curve(
    points: tuple[tuple[float, float, float, int], ...], x: float
) -> float:
    """Evaluate parsed FMOD 1.08 type-0 and type-1 curve segments.

    Type 1 is FMOD Studio's two-handle ease-in/ease-out segment.  Silent
    target-only probes establish a piecewise power curve whose exponent is
    ``1 + 2*abs(shape)``.  Positive shapes ease in then out; negative shapes
    ease out then in.  See ``docs/FMOD_AUTHORED_CURVES.md`` for error bounds.
    """

    if not points:
        raise ValueError("curve points are empty")
    value = float(x)
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    xs = [point[0] for point in points]
    right_index = bisect.bisect_left(xs, value)
    left_x, left_y, shape, point_type = points[right_index - 1]
    right_x, right_y, _right_shape, _right_type = points[right_index]
    t = (value - left_x) / (right_x - left_x)
    if point_type == 0:
        fraction = evaluate_type0_curve(
            ((0.0, 0.0, shape), (1.0, 1.0, 0.0)), t
        )
    elif point_type == 1:
        exponent = 1.0 + (2.0 * abs(shape))
        if shape >= 0.0:
            fraction = (
                0.5 * ((2.0 * t) ** exponent)
                if t <= 0.5
                else 1.0 - (0.5 * ((2.0 * (1.0 - t)) ** exponent))
            )
        else:
            fraction = (
                0.5 * (1.0 - ((1.0 - (2.0 * t)) ** exponent))
                if t <= 0.5
                else 0.5 + (0.5 * (((2.0 * t) - 1.0) ** exponent))
            )
    else:  # pragma: no cover - _curve_points validates this boundary.
        _fail("unsupportedCurvePointType", f"curve uses point type {point_type}")
    return left_y + ((right_y - left_y) * fraction)


def fmod108_volume_automation_linear(raw_value: float) -> float:
    """Map a v0x50 volume-automation value to linear amplitude.

    Values at and below the serialized -infinity sentinel are exact silence.
    The quiet tail is a measured FMOD 1.08.12 lookup; normal values use the
    standard dB conversion.
    """

    value = float(raw_value)
    if not math.isfinite(value):
        raise ValueError("volume automation value must be finite")
    if value <= _NEGATIVE_INFINITY_RAW:
        return 0.0
    if value >= _FMOD108_INFINITY_TAIL[-1][0]:
        return 10.0 ** (value / 20.0)
    index = bisect.bisect_right(_FMOD108_INFINITY_TAIL_X, value)
    left_x, left_y = _FMOD108_INFINITY_TAIL[index - 1]
    right_x, right_y = _FMOD108_INFINITY_TAIL[index]
    fraction = (value - left_x) / (right_x - left_x)
    return left_y + ((right_y - left_y) * fraction)


def _controller_amplitude_from_points(
    guid: str,
    property_index: int,
    points: tuple[tuple[float, float, float, int], ...],
    x: float,
) -> float:
    value = evaluate_authored_curve(points, x)
    if property_index == 0:
        return fmod108_volume_automation_linear(value)
    if property_index == 4:
        if value < -1.0e-5 or value > 1.00001:
            _fail(
                "unsupportedFadeRange",
                f"controller {guid} fade value {value} is outside 0..1",
            )
        return min(1.0, max(0.0, value))
    _fail("unsupportedPropertyIndex", f"property index {property_index} is unsupported")


def _controller_amplitude(controller: dict[str, Any], x: float) -> float:
    return _controller_amplitude_from_points(
        _guid(controller.get("guid")),
        int(controller["propertyIndex"]),
        _curve_points(controller),
        x,
    )


def _event_for_source(
    report: dict[str, Any], source_guid: str, classification: dict[str, Any]
) -> dict[str, Any]:
    declared_paths = classification.get("eventPaths")
    if not isinstance(declared_paths, list):
        _fail("invalidClassification", "source row has no eventPaths array")
    declared = {str(item) for item in declared_paths}
    matches: list[dict[str, Any]] = []
    events = report.get("events")
    if not isinstance(events, list):
        _fail("invalidGraph", "events must be an array")
    for event in events:
        if not isinstance(event, dict):
            _fail("invalidGraph", "event must be an object")
        reachable = event.get("reachableInstrumentGuids")
        if not isinstance(reachable, list):
            _fail("invalidGraph", "event reachableInstrumentGuids must be an array")
        if source_guid in {_guid(item) for item in reachable}:
            matches.append(event)
    supported_matches = [
        event
        for event in matches
        if _event_suffix(event.get("path")) in _ENGINE_SUFFIXES | {_TRANSMISSION_SUFFIX}
    ]
    if len(supported_matches) != 1:
        _fail(
            "unsupportedEventTopology",
            f"source is reachable from {len(supported_matches)} supported continuous events",
        )
    event = supported_matches[0]
    if str(event.get("path")) not in declared or event.get("mappingComplete") is not True:
        _fail("invalidClassification", "classification/event mapping does not agree")
    return event


def _parameter_domains(
    report: dict[str, Any],
    event: dict[str, Any],
    chain_guids: set[str],
    controllers: list[dict[str, Any]],
    speed_parameter: str,
) -> tuple[dict[str, tuple[float, float]], dict[str, list[dict[str, Any]]]]:
    parameters = _objects_by_guid(report, "parameters")
    parameter_guids_by_name: dict[str, set[str]] = defaultdict(set)
    for controller in controllers:
        name = str(controller.get("inputParameterName") or "").casefold()
        parameter_guids_by_name[name].add(_guid(controller.get("inputParameterGuid")))

    placement_by_name: dict[str, list[dict[str, Any]]] = defaultdict(list)
    raw_placements = event.get("parameterPlacements", [])
    if not isinstance(raw_placements, list):
        _fail("invalidGraph", "event parameterPlacements must be an array")
    for placement in raw_placements:
        if not isinstance(placement, dict):
            _fail("invalidGraph", "parameter placement must be an object")
        if _guid(placement.get("instrumentGuid")) not in chain_guids:
            continue
        name = str(placement.get("parameterName") or "").casefold()
        if name not in {speed_parameter, _THROTTLE_PARAMETER}:
            _fail("unsupportedTriggerParameter", f"trigger placement uses {name or 'unnamed'}")
        start = _finite(
            placement.get("start"),
            code="unsupportedTriggerPlacement",
            detail=f"{name} placement start is invalid",
        )
        end = _finite(
            placement.get("end"),
            code="unsupportedTriggerPlacement",
            detail=f"{name} placement end is invalid",
        )
        if end < start:
            _fail("unsupportedTriggerPlacement", f"{name} placement is reversed")
        guid = _guid(placement.get("parameterGuid"))
        if guid:
            parameter_guids_by_name[name].add(guid)
        placement_by_name[name].append(
            {
                "start": start,
                "end": end,
                "includeEnd": placement.get("includeEnd") is True,
                "instrumentGuid": _guid(placement.get("instrumentGuid")),
                "parameterGuid": guid,
            }
        )

    raw_timeline = event.get("timelinePlacements", [])
    if not isinstance(raw_timeline, list):
        _fail("invalidGraph", "event timelinePlacements must be an array")
    if any(
        isinstance(item, dict) and _guid(item.get("instrumentGuid")) in chain_guids
        for item in raw_timeline
    ):
        _fail("unsupportedTimelinePlacement", "engine source is timeline-triggered")

    domains: dict[str, tuple[float, float]] = {}
    for name in (speed_parameter, _THROTTLE_PARAMETER):
        guids = parameter_guids_by_name.get(name, set())
        guids.discard("")
        if len(guids) > 1:
            _fail("unsupportedParameterTopology", f"source uses multiple {name} GUIDs")
        if guids:
            parameter = parameters.get(next(iter(guids)))
            if parameter is None:
                _fail("unsupportedParameterTopology", f"{name} parameter definition is absent")
            minimum = _finite(
                parameter.get("minimum"),
                code="unsupportedParameterTopology",
                detail=f"{name} minimum is invalid",
            )
            maximum = _finite(
                parameter.get("maximum"),
                code="unsupportedParameterTopology",
                detail=f"{name} maximum is invalid",
            )
            if maximum <= minimum:
                _fail("unsupportedParameterTopology", f"{name} domain is empty")
            domains[name] = (minimum, maximum)

    # An RPM controller is itself sufficient to establish a continuous speed
    # domain.  Four shipped Alfa 33 engine layers intentionally have only a
    # throttle trigger placement and drive RPM gain/pitch entirely through
    # controllers.  Requiring a redundant RPM placement would discard those
    # authored layers.
    if speed_parameter not in domains:
        _fail(
            "unsupportedTriggerPlacement",
            f"continuous source has no {speed_parameter} domain",
        )
    if _THROTTLE_PARAMETER not in domains:
        domains[_THROTTLE_PARAMETER] = (0.0, 1.0)
    throttle_domain = domains[_THROTTLE_PARAMETER]
    if abs(throttle_domain[0]) > 1.0e-5 or abs(throttle_domain[1] - 1.0) > 1.0e-5:
        _fail("unsupportedParameterDomain", f"throttle domain is {throttle_domain}")
    return domains, placement_by_name


def _gate_transition_width(
    domain: tuple[float, float], placement: dict[str, Any]
) -> float:
    span = domain[1] - domain[0]
    width = max(1.0e-8, span * _GATE_TRANSITION_DOMAIN_FRACTION)
    positive_distances = [
        distance
        for distance in (
            placement["start"] - domain[0],
            domain[1] - placement["end"],
            placement["end"] - placement["start"],
        )
        if distance > 0.0
    ]
    if positive_distances:
        width = min(width, min(positive_distances) * 0.25)
    return width


def _gate_amplitude(
    x: float,
    placements: list[dict[str, Any]],
    domain: tuple[float, float],
) -> float:
    """Approximate FMOD's hard trigger regions with bounded linear edges."""

    result = 1.0
    for placement in placements:
        start = placement["start"]
        end = placement["end"]
        width = _gate_transition_width(domain, placement)
        if start > domain[0]:
            if x <= start - width:
                return 0.0
            if x < start:
                result *= (x - (start - width)) / width
        elif x < start:
            return 0.0

        if end < domain[1]:
            if placement["includeEnd"]:
                if x >= end + width:
                    return 0.0
                if x > end:
                    result *= 1.0 - ((x - end) / width)
            else:
                if x >= end:
                    return 0.0
                if x > end - width:
                    result *= (end - x) / width
        elif x > end or (x == end and not placement["includeEnd"]):
            return 0.0
    return min(1.0, max(0.0, result))


def _gate_interval(
    domain: tuple[float, float], placements: list[dict[str, Any]]
) -> tuple[float, float]:
    start, end = domain
    for placement in placements:
        start = max(start, placement["start"])
        end = min(end, placement["end"])
    if end <= start:
        _fail("unsupportedTriggerPlacement", "effective trigger interval is empty")
    return start, end


def _curve_knots(
    domain: tuple[float, float],
    placements: list[dict[str, Any]],
    controllers: list[dict[str, Any]],
) -> list[float]:
    minimum, maximum = domain
    values = {minimum, maximum}
    for placement in placements:
        values.add(min(maximum, max(minimum, placement["start"])))
        values.add(min(maximum, max(minimum, placement["end"])))
        width = _gate_transition_width(domain, placement)
        if placement["start"] > minimum:
            values.add(max(minimum, placement["start"] - width))
        if placement["end"] < maximum:
            edge = (
                placement["end"] + width
                if placement["includeEnd"]
                else placement["end"] - width
            )
            values.add(min(maximum, max(minimum, edge)))
    for controller in controllers:
        for x, _y, _shape, _point_type in _curve_points(controller):
            if minimum <= x <= maximum:
                values.add(x)
    return sorted(values)


def _adaptive_sample(
    function: Callable[[float], float],
    knots: list[float],
    tolerance: float,
) -> tuple[list[list[float]], float]:
    values: dict[float, float] = {}

    def value(x: float) -> float:
        if x not in values:
            current = function(x)
            if not math.isfinite(current) or current < -1.0e-8:
                _fail("curveEvaluationFailed", f"non-finite/negative amplitude at {x}")
            values[x] = max(0.0, current)
        return values[x]

    output: list[tuple[float, float]] = []

    def visit(left: float, right: float, depth: int) -> None:
        left_y = value(left)
        right_y = value(right)
        probes = (0.25, 0.5, 0.75)
        errors: list[float] = []
        for fraction in probes:
            x = left + ((right - left) * fraction)
            actual = value(x)
            linear = left_y + ((right_y - left_y) * fraction)
            errors.append(abs(actual - linear))
        if max(errors, default=0.0) > tolerance and depth < _MAX_ADAPTIVE_DEPTH:
            middle = (left + right) * 0.5
            visit(left, middle, depth + 1)
            visit(middle, right, depth + 1)
            return
        output.append((left, left_y))

    for left, right in zip(knots, knots[1:]):
        if right > left:
            visit(left, right, 0)
    output.append((knots[-1], value(knots[-1])))
    deduplicated: list[tuple[float, float]] = []
    for point in sorted(output):
        if deduplicated and point[0] == deduplicated[-1][0]:
            deduplicated[-1] = point
        else:
            deduplicated.append(point)

    maximum_error = 0.0
    for _refinement in range(_MAX_VALIDATION_REFINEMENTS + 1):
        maximum_error = 0.0
        refine_at: list[float] = []
        for (left_x, left_y), (right_x, right_y) in zip(
            deduplicated, deduplicated[1:]
        ):
            segment_error = 0.0
            for index in range(1, _VALIDATION_PROBES_PER_SEGMENT + 1):
                fraction = index / (_VALIDATION_PROBES_PER_SEGMENT + 1.0)
                x = left_x + ((right_x - left_x) * fraction)
                linear = left_y + ((right_y - left_y) * fraction)
                segment_error = max(segment_error, abs(function(x) - linear))
            maximum_error = max(maximum_error, segment_error)
            if segment_error > tolerance:
                refine_at.append((left_x + right_x) * 0.5)
        if not refine_at:
            break
        deduplicated = sorted(
            deduplicated + [(x, value(x)) for x in refine_at]
        )
    else:  # pragma: no cover - defensive; catalog audit exercises the bound.
        _fail(
            "linearizationToleranceExceeded",
            f"dense validation error {maximum_error} exceeds {tolerance}",
        )
    return [[point[0], point[1]] for point in deduplicated], maximum_error


def _normalized_curve_function(
    function: Callable[[float], float], reference_amplitude: float
) -> Callable[[float], float]:
    """Return the exact manifest-domain gain function for one capture.

    The manifest stores gain relative to the selected capture operating point
    and saturates values above that point at unity.  Sampling the raw amplitude
    with ``tolerance * reference_amplitude`` is numerically brittle for very
    quiet explicit captures (notably the released-pedal idle projection), and
    clipping only the sampled vertices does not bound interpolation error at
    the unity crossing.  Sampling this normalized, clipped function directly
    makes the advertised tolerance apply to the values the runtime consumes.
    """

    if not math.isfinite(reference_amplitude) or reference_amplitude <= 0.0:
        _fail("invalidNormalizationAmplitude", "capture amplitude must be positive")

    def evaluate(value: float) -> float:
        amplitude = function(value)
        if not math.isfinite(amplitude):
            _fail("curveEvaluationFailed", f"non-finite amplitude at {value}")
        return min(1.0, max(0.0, amplitude / reference_amplitude))

    return evaluate


def _select_capture_value(
    function: Callable[[float], float],
    interval: tuple[float, float],
    preferred: float,
    explicit: float | None,
    extra_candidates: list[float] | None = None,
) -> tuple[float, float]:
    start, end = interval
    if explicit is not None:
        value = float(explicit)
        if not math.isfinite(value) or value < start or value > end:
            _fail("invalidCaptureControl", f"capture value {value} is outside {interval}")
        gain = function(value)
        if gain <= 1.0e-8:
            _fail("silentCaptureControl", f"capture value {value} has zero authored gain")
        return value, gain

    samples = 2048
    candidates = [start + ((end - start) * index / samples) for index in range(samples + 1)]
    candidates.extend(
        value
        for value in (extra_candidates or [])
        if start <= value <= end
    )
    if start <= preferred <= end:
        candidates.append(preferred)
    best_value = start
    best_gain = -1.0
    for value in candidates:
        gain = function(value)
        if gain > best_gain + 1.0e-10:
            best_value, best_gain = value, gain
        elif abs(gain - best_gain) <= 1.0e-10:
            if (abs(value - preferred), value) < (abs(best_value - preferred), best_value):
                best_value = value
    if best_gain <= 1.0e-8:
        _fail("silentSource", "authored source has no nonzero operating point")
    return best_value, best_gain


def _select_positive_capture_value(
    function: Callable[[float], float],
    interval: tuple[float, float],
    preferred: float,
    extra_candidates: list[float] | None = None,
) -> tuple[float, float]:
    """Choose an audible positive speed point, preferring AutoPitch reference.

    Capturing an AutoPitch source at zero (or a negative drivetrain speed)
    cannot define a usable positive varispeed root.  The authored AutoPitch
    reference is the least transformed capture whenever it is inside the
    placement and audible; otherwise choose the loudest sampled positive point.
    """

    start, end = interval
    if preferred > 0.0 and start <= preferred <= end:
        gain = function(preferred)
        if gain > 1.0e-8:
            return preferred, gain

    samples = 2048
    candidates = [
        start + ((end - start) * index / samples)
        for index in range(samples + 1)
    ]
    candidates.extend(
        value
        for value in (extra_candidates or [])
        if start <= value <= end
    )
    candidates = sorted({value for value in candidates if value > 0.0})
    best_value = 0.0
    best_gain = -1.0
    for value in candidates:
        gain = function(value)
        if gain > best_gain + 1.0e-10:
            best_value, best_gain = value, gain
        elif abs(gain - best_gain) <= 1.0e-10:
            if (abs(value - preferred), value) < (
                abs(best_value - preferred),
                best_value,
            ):
                best_value = value
    if best_gain <= 1.0e-8:
        _fail(
            "noPositiveAudibleCaptureRoot",
            "AutoPitch source has no positive audible capture operating point",
        )
    return best_value, best_gain


def derive_manifest_source_curves(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Return normalized manifest curves and a capture operating point.

    ``capture_controls`` may override the event's native speed parameter
    (``rpms`` or ``drivetrain_speed``) and/or ``throttle``.  Static base
    volume is marked as baked because the corresponding target-only FMOD render
    must be captured at the returned controls.  No sample name is read or emitted.
    """

    if graph_report.get("schema") != BANK_GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {BANK_GRAPH_SCHEMA}")
    if not isinstance(source_classification, dict):
        _fail("invalidClassification", "source classification must be an object")
    if source_classification.get("policy") != _ALLOWED_POLICY:
        _fail("sourceNotAllowed", "source classifier did not allow this source")
    if source_classification.get("lifetime") != "continuous":
        _fail("unsupportedLifetime", "authored engine curves require a continuous source")
    source_guid = _guid(source_classification.get("sourceGuid"))
    if not source_guid:
        _fail("invalidClassification", "source row has no GUID")
    tolerance = _finite(
        interpolation_tolerance,
        code="invalidTolerance",
        detail="interpolation tolerance must be finite",
    )
    if tolerance <= 0.0 or tolerance > 0.01:
        _fail("invalidTolerance", "interpolation tolerance must be in (0, 0.01]")
    overrides: dict[str, float] = {}
    for key, value in (capture_controls or {}).items():
        name = str(key).casefold()
        overrides[name] = _finite(
            value,
            code="invalidCaptureControl",
            detail=f"capture control {name or 'unnamed'} is invalid",
        )

    instruments = _objects_by_guid(graph_report, "instruments")
    parameters_by_guid = _objects_by_guid(graph_report, "parameters")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "classified GUID is not a waveform instrument")
    chain = _instrument_chain(source_guid, instruments)
    chain_guids = {item[0] for item in chain}
    owner_scope: dict[str, tuple[str, str, int]] = {}
    base_values: list[dict[str, Any]] = []
    for guid, node, depth in chain:
        properties = node.get("baseProperties")
        if not isinstance(properties, dict):
            _fail("unsupportedOwnerTopology", f"instrument {guid} has no base properties")
        routable = _guid(properties.get("routableGuid"))
        if not routable:
            _fail("unsupportedOwnerTopology", f"instrument {guid} has no routable GUID")
        for owner in (guid, routable):
            if owner in owner_scope and owner_scope[owner][0] != guid:
                _fail("unsupportedOwnerTopology", f"owner GUID {owner} is shared")
            owner_scope[owner] = (guid, routable, depth)
        base_values.append(
            {
                "instrumentGuid": guid,
                "ancestorDepth": depth,
                "rawValue": _finite(
                    properties.get("volumeDb", 0.0),
                    code="unsupportedBaseGain",
                    detail=f"instrument {guid} base volume is invalid",
                ),
            }
        )

    all_controllers = _objects_by_guid(graph_report, "controllers")
    referenced: set[str] = set()
    for guid, node, _depth in chain:
        raw_guids = node.get("controllerGuids")
        if not isinstance(raw_guids, list):
            _fail("invalidGraph", f"instrument {guid} controllerGuids is invalid")
        referenced.update(_guid(item) for item in raw_guids)
    owned = {
        guid
        for guid, controller in all_controllers.items()
        if _guid(controller.get("propertyOwnerGuid")) in owner_scope
    }
    if referenced - all_controllers.keys():
        _fail("missingController", f"missing controllers: {sorted(referenced - all_controllers.keys())}")
    if owned != referenced:
        _fail(
            "unsupportedControllerOwnership",
            f"owned/referenced controller mismatch: ownedOnly={sorted(owned-referenced)}, referencedOnly={sorted(referenced-owned)}",
        )

    controllers: list[dict[str, Any]] = []
    controller_evidence: list[dict[str, Any]] = []
    controller_points: dict[
        str, tuple[tuple[float, float, float, int], ...]
    ] = {}
    fixed_automatic_values: dict[str, float] = {}
    fixed_automatic_amplitude = 1.0
    for guid in sorted(referenced):
        controller = all_controllers[guid]
        owner = _guid(controller.get("propertyOwnerGuid"))
        if owner not in owner_scope:
            _fail("unsupportedControllerOwnership", f"controller {guid} owner does not belong to source")
        if str(controller.get("inputKind") or "").casefold() != "parameter":
            _fail("unsupportedControllerInput", f"controller {guid} is not parameter-driven")
        name = str(controller.get("inputParameterName") or "").casefold()
        try:
            property_index = int(controller.get("propertyIndex"))
        except (TypeError, ValueError):
            _fail("unsupportedPropertyIndex", f"controller {guid} property index is invalid")
        if property_index not in _SUPPORTED_PROPERTY_PARAMETERS:
            _fail("unsupportedPropertyIndex", f"controller {guid} uses property {property_index}")
        if name not in _SUPPORTED_PROPERTY_PARAMETERS[property_index]:
            _fail(
                "unsupportedControllerParameter",
                f"controller {guid} maps {name or 'unnamed'} to property {property_index}",
            )
        parameter_guid = _guid(controller.get("inputParameterGuid"))
        parameter = parameters_by_guid.get(parameter_guid)
        if parameter is None:
            _fail(
                "unsupportedParameterTopology",
                f"controller {guid} parameter definition is absent",
            )
        declared_name = str(parameter.get("name") or "").casefold()
        if declared_name != name:
            _fail(
                "unsupportedParameterTopology",
                f"controller {guid} parameter name/GUID disagree",
            )
        points = _curve_points(controller)
        controller_points[guid] = points
        if property_index == 4 and any(
            y < -1.0e-5 or y > 1.00001
            for _x, y, _shape, _point_type in points
        ):
            _fail("unsupportedFadeRange", f"controller {guid} fade points are outside 0..1")
        baked_fixed_automatic = name in _FIXED_AUTOMATIC_PARAMETERS
        if baked_fixed_automatic:
            expected_type = _EXPECTED_AUTOMATIC_PARAMETER_TYPES[name]
            if str(parameter.get("type") or "") != expected_type:
                _fail(
                    "unsupportedControllerParameter",
                    f"controller {guid} {name} is not {expected_type}",
                )
            value = overrides.get(
                name, DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS[name]
            )
            minimum = _finite(
                parameter.get("minimum"),
                code="unsupportedParameterTopology",
                detail=f"{name} minimum is invalid",
            )
            maximum = _finite(
                parameter.get("maximum"),
                code="unsupportedParameterTopology",
                detail=f"{name} maximum is invalid",
            )
            if value < minimum or value > maximum:
                _fail(
                    "invalidCaptureControl",
                    f"fixed automatic {name} value {value} is outside {minimum}..{maximum}",
                )
            fixed_automatic_values[name] = value
            fixed_automatic_amplitude *= _controller_amplitude_from_points(
                guid, property_index, points, value
            )
        controllers.append(controller)
        scope_guid, _routable, depth = owner_scope[owner]
        controller_evidence.append(
            {
                "controllerGuid": guid,
                "ownerInstrumentGuid": scope_guid,
                "ancestorDepth": depth,
                "parameter": name,
                "propertyIndex": property_index,
                "runtimeTreatment": (
                    "bakedFixedAutomaticAtCapture"
                    if baked_fixed_automatic
                    else "manifestCurve"
                ),
                "points": [
                    {
                        "x": x,
                        "rawValue": y,
                        "shape": shape,
                        "type": point_type,
                    }
                    for x, y, shape, point_type in points
                ],
            }
        )

    event = _event_for_source(graph_report, source_guid, source_classification)
    suffix = _event_suffix(event.get("path"))
    speed_parameter = (
        _DRIVETRAIN_SPEED_PARAMETER
        if suffix == _TRANSMISSION_SUFFIX
        else _RPM_PARAMETER
    )
    role = str(source_classification.get("role") or "")
    if suffix == _TRANSMISSION_SUFFIX and role != "TRANSMISSION":
        _fail("invalidClassification", "transmission event does not have TRANSMISSION role")
    if suffix in _ENGINE_SUFFIXES and role == "TRANSMISSION":
        _fail("invalidClassification", "engine event was classified as transmission")
    for controller in controllers:
        name = str(controller.get("inputParameterName") or "").casefold()
        if name not in {
            speed_parameter,
            _THROTTLE_PARAMETER,
            *_FIXED_AUTOMATIC_PARAMETERS,
        }:
            _fail(
                "unsupportedControllerParameter",
                f"{suffix} source has controller for {name or 'unnamed'}",
            )

    unknown_overrides = sorted(
        set(overrides)
        - {speed_parameter, _THROTTLE_PARAMETER, *_FIXED_AUTOMATIC_PARAMETERS}
    )
    if unknown_overrides:
        _fail("invalidCaptureControl", f"unknown controls: {', '.join(unknown_overrides)}")

    domains, placements = _parameter_domains(
        graph_report, event, chain_guids, controllers, speed_parameter
    )
    controllers_by_parameter: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for controller in controllers:
        controllers_by_parameter[str(controller["inputParameterName"]).casefold()].append(controller)

    def parameter_function(name: str) -> Callable[[float], float]:
        relevant = controllers_by_parameter.get(name, [])
        compiled = [
            (
                _guid(controller.get("guid")),
                int(controller["propertyIndex"]),
                controller_points[_guid(controller.get("guid"))],
            )
            for controller in relevant
        ]
        gates = placements.get(name, [])
        domain = domains[name]

        def evaluate(x: float) -> float:
            result = _gate_amplitude(x, gates, domain) if gates else 1.0
            for guid, property_index, points in compiled:
                result *= _controller_amplitude_from_points(
                    guid, property_index, points, x
                )
            return result

        return evaluate

    rpm_function = parameter_function(speed_parameter)
    throttle_function = parameter_function(_THROTTLE_PARAMETER)
    rpm_interval = _gate_interval(
        domains[speed_parameter], placements[speed_parameter]
    )
    throttle_interval = _gate_interval(
        domains[_THROTTLE_PARAMETER], placements.get(_THROTTLE_PARAMETER, [])
    ) if placements.get(_THROTTLE_PARAMETER) else domains[_THROTTLE_PARAMETER]

    properties = source["baseProperties"]
    auto_pitch = _finite(
        properties.get("autoPitchReference"),
        code="unsupportedAutoPitch",
        detail="source autopitch reference is invalid",
    )
    if auto_pitch <= 0.0:
        _fail("unsupportedAutoPitch", f"source autopitch reference is {auto_pitch}")

    rpm_knots = _curve_knots(
        domains[speed_parameter],
        placements[speed_parameter],
        controllers_by_parameter.get(speed_parameter, []),
    )
    throttle_knots = _curve_knots(
        domains[_THROTTLE_PARAMETER],
        placements.get(_THROTTLE_PARAMETER, []),
        controllers_by_parameter.get(_THROTTLE_PARAMETER, []),
    )

    capture_rpm, rpm_normalization_amplitude = _select_capture_value(
        rpm_function,
        rpm_interval,
        auto_pitch,
        overrides.get(speed_parameter),
        rpm_knots,
    )
    rpm_capture_amplitude = rpm_normalization_amplitude
    if capture_rpm <= 0.0:
        if overrides.get(speed_parameter) is not None:
            _fail(
                "invalidCaptureControl",
                "explicit AutoPitch capture speed must be positive",
            )
        capture_rpm, rpm_capture_amplitude = _select_positive_capture_value(
            rpm_function,
            rpm_interval,
            auto_pitch,
            rpm_knots,
        )
    capture_throttle, throttle_maximum = _select_capture_value(
        throttle_function,
        throttle_interval,
        throttle_interval[0],
        overrides.get(_THROTTLE_PARAMETER),
        throttle_knots,
    )
    if fixed_automatic_amplitude <= 1.0e-8:
        _fail(
            "silentFixedAutomaticCapture",
            "the configured listener/emitter geometry makes this source silent",
        )

    # Linearize in the normalized/clipped domain that is serialized.  This is
    # essential when an explicit capture is intentionally much quieter than
    # the source's peak (for example a shared rising source projected to IDLE
    # at throttle=0): raw-domain tolerances become unnecessarily tiny and do
    # not bound the post-clamp curve at the unity crossing.
    rpm_curve_native, rpm_error = _adaptive_sample(
        _normalized_curve_function(rpm_function, rpm_normalization_amplitude),
        rpm_knots,
        tolerance,
    )
    gain_curve, gain_error = _adaptive_sample(
        _normalized_curve_function(throttle_function, throttle_maximum),
        throttle_knots,
        tolerance,
    )
    speed_to_rpm = (
        _RADIANS_PER_SECOND_TO_RPM
        if speed_parameter == _DRIVETRAIN_SPEED_PARAMETER
        else 1.0
    )
    rpm_curve = [
        [round(point[0] * speed_to_rpm, 8), point[1]]
        for point in rpm_curve_native
    ]
    rpm_curve_native = [
        [round(float(x), 8), round(float(y), 10)]
        for x, y in rpm_curve_native
    ]
    gain_curve = [
        [round(float(x), 8), round(float(y), 10)]
        for x, y in gain_curve
    ]

    trigger_gate_edges: list[dict[str, Any]] = []
    for name, items in sorted(placements.items()):
        domain = domains[name]
        for item in items:
            width = _gate_transition_width(domain, item)
            if item["start"] > domain[0]:
                trigger_gate_edges.append(
                    {
                        "parameter": name,
                        "edge": "lower",
                        "authoredValue": item["start"],
                        "nativeTransitionWidth": width,
                        "rpmTransitionWidth": (
                            width * speed_to_rpm if name == speed_parameter else None
                        ),
                    }
                )

            if item["end"] < domain[1]:
                trigger_gate_edges.append(
                    {
                        "parameter": name,
                        "edge": "upper",
                        "authoredValue": item["end"],
                        "nativeTransitionWidth": width,
                        "rpmTransitionWidth": (
                            width * speed_to_rpm if name == speed_parameter else None
                        ),
                    }
                )

    curve_point_types = sorted(
        {
            point_type
            for points in controller_points.values()
            for _x, _y, _shape, point_type in points
        }
    )

    modulators = graph_report.get("modulators", [])
    if not isinstance(modulators, list):
        _fail("invalidGraph", "modulators must be an array")
    source_modulators = []
    for item in modulators:
        if not isinstance(item, dict):
            _fail("invalidGraph", "modulator must be an object")
        owner = _guid(item.get("ownerGuid"))
        if owner in owner_scope:
            source_modulators.append(
                {
                    "guid": _guid(item.get("guid")),
                    "ownerGuid": owner,
                    "type": item.get("type"),
                    "propertyIndex": item.get("propertyIndex"),
                }
            )

    effects = _objects_by_guid(graph_report, "effects", required=False)
    dsp_automation = []
    for guid, controller in sorted(all_controllers.items()):
        owner = _guid(controller.get("propertyOwnerGuid"))
        if owner not in effects:
            continue
        parameter = str(controller.get("inputParameterName") or "").casefold()
        if parameter in {
            _RPM_PARAMETER,
            _DRIVETRAIN_SPEED_PARAMETER,
            _THROTTLE_PARAMETER,
        }:
            dsp_automation.append(
                {
                    "controllerGuid": guid,
                    "effectGuid": owner,
                    "parameter": parameter,
                    "propertyIndex": controller.get("propertyIndex"),
                    "routingAttribution": "unavailableInBankGraphV3",
                }
            )

    output = {
        "schema": AUTHORED_CURVE_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": str(event.get("path")),
        "role": role,
        "nativeSpeedParameter": speed_parameter,
        "nativeSpeedToRpmScale": speed_to_rpm,
        "autoPitchReferenceRpm": round(auto_pitch * speed_to_rpm, 8),
        "captureRootRpm": round(capture_rpm * speed_to_rpm, 8),
        "captureThrottle": round(capture_throttle, 8),
        "captureParameterValues": {
            speed_parameter: round(capture_rpm, 8),
            _THROTTLE_PARAMETER: round(capture_throttle, 8),
        },
        "captureAutomaticParameterValues": {
            name: round(value, 8)
            for name, value in sorted(fixed_automatic_values.items())
        },
        "rpmCurve": rpm_curve,
        "gainCurve": gain_curve,
        "baseGain": {
            "bakedByTargetOnlyReferenceCapture": True,
            "sourceAndAncestorRawValues": base_values,
            "fixedAutomaticAmplitudeBakedAtCapture": fixed_automatic_amplitude,
            "applyAgainInManifestGainDb": False,
        },
        "triggerPlacements": {
            name: sorted(items, key=lambda item: (item["start"], item["end"], item["instrumentGuid"]))
            for name, items in sorted(placements.items())
        },
        "controllers": controller_evidence,
        "normalization": {
            "rpmAmplitudeAtCapture": rpm_capture_amplitude,
            "rpmNormalizationAmplitude": rpm_normalization_amplitude,
            "gainAmplitudeAtCapture": throttle_maximum,
            "fixedAutomaticAmplitudeAtCapture": fixed_automatic_amplitude,
            "capturePcmPostGainLinear": (
                rpm_normalization_amplitude / rpm_capture_amplitude
            ),
            "runtimeProductAtCapture": (
                rpm_capture_amplitude / rpm_normalization_amplitude
            ),
        },
        "fidelity": {
            "curvePointTypes": curve_point_types,
            "segmentEvaluators": {
                "0": "fmod108ExponentialOracleCalibrated",
                "1": "fmod108TwoHandlePiecewisePowerOracleCalibrated",
            },
            "shapeExponentScale": _SHAPE_EXPONENT_SCALE,
            "type0OracleMaxObservedNormalizedValueError": 2.0e-6,
            "type1ExponentRule": "1+2*abs(shape)",
            "type1OracleProbeCount": 84,
            "type1OracleMaxObservedNormalizedValueError": 8.0e-5,
            "negativeInfinityRawValue": _NEGATIVE_INFINITY_RAW,
            "negativeInfinityTail": "tatuusFmod10812WavwriterNrtLookup",
            "sampleNamesUsed": False,
            "linearSamplingTolerance": tolerance,
            "denseValidationProbesPerSegment": _VALIDATION_PROBES_PER_SEGMENT,
            "rpmDenseGridMaxObservedLinearError": rpm_error,
            "gainDenseGridMaxObservedLinearError": gain_error,
            "triggerGateApproximation": {
                "method": "linearEpsilonRamp",
                "domainFraction": _GATE_TRANSITION_DOMAIN_FRACTION,
                "exactOutsideTransitionWindows": True,
                "edges": trigger_gate_edges,
            },
            "exactnessClaim": False,
            "requiredFinalGate": "targetOnlyFmod108RuntimeOracleComparison",
        },
        "unsupported": {
            "sourceModulators": source_modulators,
            "bankWideUnattributedRpmThrottleDspAutomation": dsp_automation,
        },
    }
    return output


def _canonical_json_sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _property_one_points(
    controller: dict[str, Any],
) -> tuple[list[dict[str, Any]], list[float]]:
    """Validate pitch-controller evidence without interpreting raw units.

    Some Alfa 33 banks contain one byte-identical coincident point.  FMOD
    accepts it and it does not describe a discontinuity, so the fallback keeps
    it as evidence while deduplicating only the RPM probe seed.  Coincident
    points with different authored values remain ambiguous and fail closed.
    """

    guid = _guid(controller.get("guid"))
    raw = controller.get("points")
    if not isinstance(raw, list) or not raw:
        _fail("unsupportedPropertyOneCurve", f"controller {guid} has no points")
    evidence: list[dict[str, Any]] = []
    seeds: list[float] = []
    previous: tuple[float, float, float, int] | None = None
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            _fail(
                "unsupportedPropertyOneCurve",
                f"controller {guid} point {index} is invalid",
            )
        x = _finite(
            item.get("x"),
            code="unsupportedPropertyOneCurve",
            detail=f"controller {guid} has invalid x",
        )
        y = _finite(
            item.get("y"),
            code="unsupportedPropertyOneCurve",
            detail=f"controller {guid} has invalid raw value",
        )
        shape = _finite(
            item.get("shape", 0.0),
            code="unsupportedPropertyOneCurve",
            detail=f"controller {guid} has invalid shape",
        )
        try:
            point_type = int(item.get("type", 0))
        except (TypeError, ValueError):
            _fail(
                "unsupportedCurvePointType",
                f"controller {guid} has an invalid point type",
            )
        if point_type not in {0, 1}:
            _fail(
                "unsupportedCurvePointType",
                f"controller {guid} uses point type {point_type}",
            )
        if shape < -1.000001 or shape > 1.000001:
            _fail(
                "unsupportedCurveShape",
                f"controller {guid} shape {shape} is outside -1..1",
            )
        current = (x, y, shape, point_type)
        coincident = previous is not None and x == previous[0]
        if previous is not None and x < previous[0]:
            _fail(
                "unsupportedPropertyOneCurve",
                f"controller {guid} x values decrease",
            )
        if coincident and current != previous:
            _fail(
                "unsupportedPropertyOneDiscontinuity",
                f"controller {guid} has conflicting values at x={x}",
            )
        evidence.append(
            {
                "x": x,
                "rawValue": y,
                "shape": shape,
                "type": point_type,
                "coincidentIdenticalPoint": coincident,
            }
        )
        if not coincident:
            seeds.append(x)
        previous = current
    return evidence, seeds


def derive_manifest_one_shot_curves(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Compile bounded volume/gate semantics for an engine-event one-shot.

    One-shot PCM is captured at one audible authored operating point and uses
    that capture RPM as its runtime root.  FMOD 1.08 AutoPitch was observed to
    follow RPM continuously (it does not latch at the entry edge), so active
    PCM voices use current RPM/root RPM varispeed.  Parameter-region arming and
    re-entry remain independent of the normalized RPM/pedal volume curves.
    Timeline volume automation is dynamic PCM content and is recorded as baked
    evidence rather than flattened.
    """

    if graph_report.get("schema") != BANK_GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {BANK_GRAPH_SCHEMA}")
    if not isinstance(source_classification, dict):
        _fail("invalidClassification", "source classification must be an object")
    if source_classification.get("policy") != _ALLOWED_POLICY:
        _fail("sourceNotAllowed", "source classifier did not allow this source")
    if source_classification.get("lifetime") != "oneShot":
        _fail("unsupportedLifetime", "one-shot curves require a oneShot source")
    source_guid = _guid(source_classification.get("sourceGuid"))
    if not source_guid:
        _fail("invalidClassification", "source row has no GUID")

    instruments = _objects_by_guid(graph_report, "instruments")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "classified GUID is not a waveform instrument")
    try:
        loop_count = int((source.get("baseProperties") or {}).get("loopCount"))
    except (TypeError, ValueError):
        _fail("unsupportedLifetime", "one-shot source loop count is invalid")
    if loop_count < 0:
        _fail("unsupportedLifetime", "one-shot source is authored as an infinite loop")

    chain = _instrument_chain(source_guid, instruments)
    chain_guids = {guid for guid, _node, _depth in chain}
    owner_scope: set[str] = set()
    referenced: set[str] = set()
    for guid, node, _depth in chain:
        properties = node.get("baseProperties")
        if not isinstance(properties, dict):
            _fail("unsupportedOwnerTopology", f"instrument {guid} has no base properties")
        owner_scope.update((guid, _guid(properties.get("routableGuid"))))
        raw_guids = node.get("controllerGuids")
        if not isinstance(raw_guids, list):
            _fail("invalidGraph", f"instrument {guid} controllerGuids is invalid")
        referenced.update(_guid(item) for item in raw_guids)
    owner_scope.discard("")

    controllers = _objects_by_guid(graph_report, "controllers")
    if referenced - controllers.keys():
        _fail(
            "missingController",
            f"missing controllers: {sorted(referenced - controllers.keys())}",
        )
    timeline_controller_guids: set[str] = set()
    timeline_evidence: list[dict[str, Any]] = []
    for controller_guid in sorted(referenced):
        controller = controllers[controller_guid]
        input_kind = str(controller.get("inputKind") or "").casefold()
        if input_kind == "parameter":
            continue
        if input_kind != "timeline":
            _fail(
                "unsupportedControllerInput",
                f"controller {controller_guid} uses {input_kind or 'unnamed'} input",
            )
        if _guid(controller.get("propertyOwnerGuid")) not in owner_scope:
            _fail(
                "unsupportedControllerOwnership",
                f"timeline controller {controller_guid} owner does not belong to source",
            )
        try:
            property_index = int(controller.get("propertyIndex"))
        except (TypeError, ValueError):
            _fail(
                "unsupportedPropertyIndex",
                f"timeline controller {controller_guid} property is invalid",
            )
        if property_index != 0:
            _fail(
                "unsupportedPropertyIndex",
                f"timeline controller {controller_guid} uses property {property_index}",
            )
        raw_points = controller.get("points")
        if not isinstance(raw_points, list) or not raw_points:
            _fail(
                "unsupportedCurve",
                f"timeline controller {controller_guid} has no points",
            )
        points: list[dict[str, Any]] = []
        previous_word: int | None = None
        for index, point in enumerate(raw_points):
            if not isinstance(point, dict):
                _fail(
                    "unsupportedCurve",
                    f"timeline controller {controller_guid} point {index} is invalid",
                )
            try:
                position_word = int(point.get("xRawUInt32"))
                point_type = int(point.get("type", 0))
            except (TypeError, ValueError):
                _fail(
                    "unsupportedCurve",
                    f"timeline controller {controller_guid} point encoding is invalid",
                )
            if position_word < 0 or (
                previous_word is not None and position_word <= previous_word
            ):
                _fail(
                    "unsupportedCurve",
                    f"timeline controller {controller_guid} positions do not increase",
                )
            if point_type not in {0, 1}:
                _fail(
                    "unsupportedCurvePointType",
                    f"timeline controller {controller_guid} uses point type {point_type}",
                )
            raw_value = _finite(
                point.get("y"),
                code="unsupportedCurve",
                detail=f"timeline controller {controller_guid} raw value is invalid",
            )
            shape = _finite(
                point.get("shape", 0.0),
                code="unsupportedCurve",
                detail=f"timeline controller {controller_guid} shape is invalid",
            )
            if shape < -1.000001 or shape > 1.000001:
                _fail(
                    "unsupportedCurveShape",
                    f"timeline controller {controller_guid} shape {shape} is outside -1..1",
                )
            points.append(
                {
                    "rawTimelinePositionWord": position_word,
                    "rawValue": raw_value,
                    "shape": shape,
                    "type": point_type,
                }
            )
            previous_word = position_word
        timeline_controller_guids.add(controller_guid)
        timeline_evidence.append(
            {
                "controllerGuid": controller_guid,
                "propertyOwnerGuid": _guid(controller.get("propertyOwnerGuid")),
                "propertyIndex": 0,
                "runtimeTreatment": "bakedDynamicTimelineAutomationInTargetOnlyPcm",
                "timelinePositionUnitsClaimed": False,
                "points": points,
            }
        )

    event = _event_for_source(graph_report, source_guid, source_classification)
    suffix = _event_suffix(event.get("path"))
    if suffix not in _ENGINE_SUFFIXES:
        _fail("unsupportedEventTopology", "one-shot curve API accepts engine events only")
    parameters = _objects_by_guid(graph_report, "parameters")
    raw_placements = event.get("parameterPlacements")
    if not isinstance(raw_placements, list):
        _fail("invalidGraph", "event parameterPlacements must be an array")
    placements_by_parameter: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for placement in raw_placements:
        if not isinstance(placement, dict):
            _fail("invalidGraph", "parameter placement must be an object")
        if _guid(placement.get("instrumentGuid")) not in chain_guids:
            continue
        name = str(placement.get("parameterName") or "").casefold()
        if name not in {
            _RPM_PARAMETER,
            _THROTTLE_PARAMETER,
            *_FIXED_AUTOMATIC_PARAMETERS,
        }:
            _fail(
                "unsupportedTriggerParameter",
                f"one-shot trigger placement uses {name or 'unnamed'}",
            )
        parameter_guid = _guid(placement.get("parameterGuid"))
        parameter = parameters.get(parameter_guid)
        if parameter is None or str(parameter.get("name") or "").casefold() != name:
            _fail(
                "unsupportedParameterTopology",
                f"one-shot {name} placement parameter identity disagrees",
            )
        minimum = _finite(
            parameter.get("minimum"),
            code="unsupportedParameterTopology",
            detail=f"{name} minimum is invalid",
        )
        maximum = _finite(
            parameter.get("maximum"),
            code="unsupportedParameterTopology",
            detail=f"{name} maximum is invalid",
        )
        start = _finite(
            placement.get("start"),
            code="unsupportedTriggerPlacement",
            detail=f"{name} placement start is invalid",
        )
        end = _finite(
            placement.get("end"),
            code="unsupportedTriggerPlacement",
            detail=f"{name} placement end is invalid",
        )
        if maximum <= minimum or start < minimum or end > maximum or end < start:
            _fail(
                "unsupportedTriggerPlacement",
                f"{name} placement {start}..{end} is outside {minimum}..{maximum}",
            )
        seek_up = _finite(
            parameter.get("seekSpeed", 0.0),
            code="unsupportedParameterTopology",
            detail=f"{name} seekSpeed is invalid",
        )
        seek_down = _finite(
            parameter.get("seekSpeedDown", 0.0),
            code="unsupportedParameterTopology",
            detail=f"{name} seekSpeedDown is invalid",
        )
        placements_by_parameter[name].append(
            {
                "start": start,
                "end": end,
                "includeEnd": placement.get("includeEnd") is True,
                "parameterGuid": parameter_guid,
                "parameterDomain": [minimum, maximum],
                "seekSpeed": seek_up,
                "seekSpeedDown": seek_down,
                "instrumentGuid": _guid(placement.get("instrumentGuid")),
            }
        )
    if not placements_by_parameter:
        _fail("unsupportedTriggerPlacement", "one-shot source has no parameter region")

    placement_root_guids = {
        item["instrumentGuid"]
        for items in placements_by_parameter.values()
        for item in items
    }
    if len(placement_root_guids) != 1:
        _fail(
            "unsupportedOneShotProgramTopology",
            "one-shot source placements do not share exactly one program root",
        )
    program_placement_root_guid = next(iter(placement_root_guids))

    projected_report = copy.deepcopy(graph_report)
    projected_report["controllers"] = [
        item
        for item in projected_report.get("controllers", [])
        if _guid(item.get("guid")) not in timeline_controller_guids
    ]
    for instrument in projected_report.get("instruments", []):
        if _guid(instrument.get("guid")) in chain_guids:
            instrument["controllerGuids"] = [
                guid
                for guid in instrument.get("controllerGuids", [])
                if _guid(guid) not in timeline_controller_guids
            ]
    projected_event = next(
        item
        for item in projected_report["events"]
        if str(item.get("path")) == str(event.get("path"))
    )
    projected_event["parameterPlacements"] = [
        item
        for item in projected_event.get("parameterPlacements", [])
        if not (
            _guid(item.get("instrumentGuid")) in chain_guids
            and str(item.get("parameterName") or "").casefold()
            in _FIXED_AUTOMATIC_PARAMETERS
        )
    ]

    # A single R18 exterior transient is gated only by cone angle.  Its event
    # still exposes the ordinary RPM parameter, but the source has no RPM gain
    # controller or placement.  A full-domain projection placement lets the
    # shared gain compiler express the correct constant-one RPM volume curve;
    # it is not returned as authored trigger evidence.
    has_projected_speed_semantics = any(
        str(controller.get("inputParameterName") or "").casefold() == _RPM_PARAMETER
        and _guid(controller.get("guid")) not in timeline_controller_guids
        for controller in controllers.values()
        if _guid(controller.get("guid")) in referenced
    ) or bool(placements_by_parameter.get(_RPM_PARAMETER))
    synthetic_speed_placement = False
    if not has_projected_speed_semantics:
        event_parameter_layout_guids = {
            _guid(item) for item in projected_event.get("parameterLayoutGuids", [])
        }
        rpm_parameters = [
            item
            for item in projected_report.get("parameters", [])
            if str(item.get("name") or "").casefold() == _RPM_PARAMETER
            and (
                not event_parameter_layout_guids
                or _guid(item.get("guid")) in event_parameter_layout_guids
            )
        ]
        if len(rpm_parameters) != 1:
            _fail(
                "unsupportedParameterTopology",
                "one-shot event has no unique RPM parameter for capture",
            )
        rpm_parameter = rpm_parameters[0]
        projected_event["parameterPlacements"].append(
            {
                "instrumentGuid": source_guid,
                "parameterGuid": _guid(rpm_parameter.get("guid")),
                "parameterName": _RPM_PARAMETER,
                "start": float(rpm_parameter["minimum"]),
                "end": float(rpm_parameter["maximum"]),
                "includeEnd": True,
            }
        )
        synthetic_speed_placement = True

    projected_classification = copy.deepcopy(source_classification)
    projected_classification["lifetime"] = "continuous"
    projection = derive_manifest_source_curves(
        projected_report,
        projected_classification,
        capture_controls,
        interpolation_tolerance=interpolation_tolerance,
    )

    override_values = {
        str(key).casefold(): _finite(
            value,
            code="invalidCaptureControl",
            detail=f"capture control {key} is invalid",
        )
        for key, value in (capture_controls or {}).items()
    }
    runtime_regions: list[dict[str, Any]] = []
    fixed_regions: list[dict[str, Any]] = []
    for name, items in sorted(placements_by_parameter.items()):
        start = max(float(item["start"]) for item in items)
        end = min(float(item["end"]) for item in items)
        if end < start:
            _fail(
                "unsupportedTriggerPlacement",
                f"effective one-shot {name} region is empty",
            )
        include_end = all(
            item["includeEnd"] for item in items if float(item["end"]) == end
        )
        domain_minimum = float(items[0]["parameterDomain"][0])
        domain_maximum = float(items[0]["parameterDomain"][1])
        if any(
            item["parameterDomain"] != items[0]["parameterDomain"]
            or item["parameterGuid"] != items[0]["parameterGuid"]
            for item in items
        ):
            _fail(
                "unsupportedParameterTopology",
                f"one-shot {name} placements use inconsistent parameters",
            )
        region = {
            "parameter": name,
            "parameterGuid": items[0]["parameterGuid"],
            "minimum": start,
            "maximum": end,
            "includeMinimum": True,
            "includeMaximum": include_end,
            "parameterDomain": [domain_minimum, domain_maximum],
            "seekSpeed": items[0]["seekSpeed"],
            "seekSpeedDown": items[0]["seekSpeedDown"],
            "authoredPlacements": sorted(
                items,
                key=lambda item: (
                    item["start"],
                    item["end"],
                    item["instrumentGuid"],
                ),
            ),
        }
        if name in _FIXED_AUTOMATIC_PARAMETERS:
            expected_type = _EXPECTED_AUTOMATIC_PARAMETER_TYPES[name]
            parameter = parameters[items[0]["parameterGuid"]]
            if str(parameter.get("type") or "") != expected_type:
                _fail(
                    "unsupportedControllerParameter",
                    f"one-shot {name} is not {expected_type}",
                )
            fixed_value = override_values.get(
                name, DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS[name]
            )
            inside = fixed_value >= start and (
                fixed_value < end or (include_end and fixed_value == end)
            )
            if not inside:
                _fail(
                    "silentFixedAutomaticCapture",
                    f"fixed {name}={fixed_value} is outside one-shot region",
                )
            fixed_regions.append(
                {
                    **region,
                    "fixedCaptureValue": fixed_value,
                    "runtimeTreatment": "bakedAtFixedCompilerGeometry",
                }
            )
            continue
        entry_edges: list[dict[str, Any]] = []
        if start > domain_minimum:
            entry_edges.append(
                {
                    "edge": "lower",
                    "direction": "increasing",
                    "threshold": start,
                    "insideComparison": "value>=threshold",
                }
            )
        if end < domain_maximum:
            entry_edges.append(
                {
                    "edge": "upper",
                    "direction": "decreasing",
                    "threshold": end,
                    "insideComparison": (
                        "value<=threshold" if include_end else "value<threshold"
                    ),
                }
            )
        runtime_regions.append({**region, "entryEdges": entry_edges})

    control_names = {
        _RPM_PARAMETER: "ENGINE_RPM",
        _THROTTLE_PARAMETER: "ACCELERATOR",
    }
    parameter_gates = [
        {
            "control": control_names[region["parameter"]],
            "minimum": region["minimum"],
            "maximum": region["maximum"],
            "includeMinimum": region["includeMinimum"],
            "includeMaximum": region["includeMaximum"],
        }
        for region in runtime_regions
    ]
    program_entry_edges: list[dict[str, Any]] = []
    for region in runtime_regions:
        for edge in region["entryEdges"]:
            program_entry_edges.append(
                {
                    "control": control_names[region["parameter"]],
                    "boundary": (
                        "MINIMUM" if edge["edge"] == "lower" else "MAXIMUM"
                    ),
                    "direction": str(edge["direction"]).upper(),
                    "value": edge["threshold"],
                    "includeBoundary": (
                        region["includeMinimum"]
                        if edge["edge"] == "lower"
                        else region["includeMaximum"]
                    ),
                }
            )

    # AC's engine event is one long-lived FMOD event instance.  FMOD 1.08.12
    # arms a parameter-region one-shot only when that event starts inside its
    # region.  Once armed, every exit followed by re-entry schedules another
    # voice; exiting never cuts a voice already playing.  Starting outside
    # leaves the instrument disabled until the event itself is restarted.
    has_runtime_region = bool(runtime_regions)
    program_trigger = (
        "EVENT_START_ARMED_PARAMETER_REGION_REENTRY"
        if has_runtime_region
        else "EVENT_START"
    )
    program_trigger_template = {
        "trigger": program_trigger,
        "parameterRegions": (
            [
                {
                    "parameterGates": parameter_gates,
                    "entryEdges": program_entry_edges,
                    "triggerOnEventStartIfInside": True,
                }
            ]
            if has_runtime_region
            else []
        ),
        "armingMode": (
            "EVENT_START_INSIDE_REQUIRED"
            if has_runtime_region
            else "FIXED_COMPILER_GEOMETRY_AT_EVENT_START"
        ),
        "initiallyOutsideBehavior": (
            "DISABLED_UNTIL_EVENT_RESTART" if has_runtime_region else None
        ),
        "rearmMode": "AFTER_ANY_GATE_EXIT" if has_runtime_region else "NONE",
        "overlapMode": "ALLOW_OVERLAP",
        "exitBehavior": "LET_ACTIVE_VOICES_FINISH",
        "programGrouping": "ONE_PROGRAM_PER_FAMILY_EVENT_PLACEMENT_ROOT",
        "placementRootInstrumentGuid": program_placement_root_guid,
        "graphHierarchySelectionWeightsAndChanceAreAuthoritative": True,
    }

    automatic_values = dict(projection["captureAutomaticParameterValues"])
    for region in fixed_regions:
        automatic_values[region["parameter"]] = round(
            float(region["fixedCaptureValue"]), 8
        )

    nominal_parameters = dict(projection["captureParameterValues"])
    capture_operating_points: list[dict[str, Any]] = [
        {
            "id": "event-start-or-nominal-entry",
            "appliesToEntryEdges": [],
            "captureRpm": projection["captureRootRpm"],
            "parameters": nominal_parameters,
            "automaticParameterValues": automatic_values,
            "rootRpm": projection["captureRootRpm"],
            "treatment": "releaseCapture",
        }
    ]
    for region in runtime_regions:
        name = region["parameter"]
        for edge in region["entryEdges"]:
            parameters_at_edge = dict(nominal_parameters)
            threshold = float(edge["threshold"])
            if edge["edge"] == "upper" and not region["includeMaximum"]:
                threshold = math.nextafter(threshold, float(region["minimum"]))
            parameters_at_edge[name] = round(threshold, 8)
            capture_rpm = float(parameters_at_edge.get(_RPM_PARAMETER, projection["captureRootRpm"]))
            capture_operating_points.append(
                {
                    "id": f"entry-{name}-{edge['edge']}-{edge['direction']}",
                    "appliesToEntryEdges": [
                        {
                            "parameter": name,
                            "edge": edge["edge"],
                            "direction": edge["direction"],
                        }
                    ],
                    "captureRpm": round(capture_rpm, 8),
                    "parameters": dict(sorted(parameters_at_edge.items())),
                    "automaticParameterValues": automatic_values,
                    "rootRpm": projection["captureRootRpm"],
                    "treatment": "validationProbeOnlyNotACaptureVariant",
                }
            )

    pre_routing_base_db = sum(
        float(item["rawValue"])
        for item in projection["baseGain"]["sourceAndAncestorRawValues"]
    )
    fixed_geometry_only_disposition = (
        {
            "kind": "FIXED_GEOMETRY_EVENT_START",
            "authoredPreRoutingBaseDb": round(pre_routing_base_db, 8),
            "authoredSilentClaimed": False,
            "releaseTreatment": "targetOnlyCaptureThenOmitOnlyIfBoundedPcmAudibilityGatePasses",
            "requiredAudibilityEvidence": [
                "peakPcmDbfsAfterAuthoredRoutingAndDsp",
                "relativeDbToLoudestEligibleEngineEventSource",
            ],
        }
        if not has_runtime_region
        else None
    )

    # Preserve the graph's selection hierarchy as data.  The source-bound
    # renderer needs this to mute only non-target leaves while leaving every
    # SMART_RANDOM/playlist ancestor and its authored chance untouched.  No
    # sample identity or filename participates in this derivation.
    selection_path: list[dict[str, Any]] = []
    for index in range(1, len(chain)):
        child_guid = chain[index - 1][0]
        parent_guid, parent, _depth = chain[index]
        raw_children = parent.get("childInstruments")
        if not isinstance(raw_children, list):
            _fail("unsupportedSelectionTopology", "parent child list is invalid")
        children: list[dict[str, Any]] = []
        for raw_child in raw_children:
            if not isinstance(raw_child, dict):
                _fail("unsupportedSelectionTopology", "weighted child is invalid")
            child = _guid(raw_child.get("guid"))
            weight = _finite(
                raw_child.get("weight"),
                code="unsupportedSelectionTopology",
                detail=f"parent {parent_guid} child weight is invalid",
            )
            if not child or weight < 0.0:
                _fail("unsupportedSelectionTopology", "child GUID/weight is invalid")
            children.append({"instrumentGuid": child, "weight": weight})
        if sum(item["instrumentGuid"] == child_guid for item in children) != 1:
            _fail(
                "unsupportedSelectionTopology",
                f"parent {parent_guid} does not contain child {child_guid} exactly once",
            )
        playlist = parent.get("playlist")
        if playlist is None and len(children) == 1:
            playlist = {
                "playMode": "SINGLE_CHILD_DETERMINISTIC",
                "playModeValue": None,
                "selectionMode": "ONLY_CHILD",
                "selectionModeValue": None,
            }
        if not isinstance(playlist, dict):
            _fail(
                "unsupportedSelectionTopology",
                f"multi-instrument {parent_guid} has no playlist policy",
            )
        selection_path.append(
            {
                "parentInstrumentGuid": parent_guid,
                "selectedChildInstrumentGuid": child_guid,
                "playlist": {
                    "playMode": playlist.get("playMode"),
                    "playModeValue": playlist.get("playModeValue"),
                    "selectionMode": playlist.get("selectionMode"),
                    "selectionModeValue": playlist.get("selectionModeValue"),
                },
                "orderedChildren": children,
            }
        )

    sample = source.get("sample")
    if not isinstance(sample, dict):
        _fail("invalidGraph", "one-shot waveform source has no sample evidence")
    sample_technical_evidence = {
        key: sample.get(key)
        for key in (
            "channels",
            "frequencyHz",
            "sampleCount",
            "encodedPayloadBytes",
            "encodedPayloadSha256",
            "waveformResourceGuid",
            "soundBankIndex",
            "subsoundIndex",
        )
    }
    source_geometry_nodes = [
        {
            "instrumentGuid": guid,
            "instrumentKind": node.get("kind"),
            "ancestorDepth": depth,
            "autoPitchReference": (node.get("baseProperties") or {}).get(
                "autoPitchReference"
            ),
            "pitchSemitones": (node.get("baseProperties") or {}).get(
                "pitchSemitones"
            ),
            "loopCount": (node.get("baseProperties") or {}).get("loopCount"),
            "triggerChancePercent": (node.get("baseProperties") or {}).get(
                "triggerChancePercent"
            ),
        }
        for guid, node, depth in chain
    ]

    output = {
        "schema": ONE_SHOT_CURVE_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": projection["eventPath"],
        "role": projection["role"],
        "manifestRole": "ENGINE_TRANSIENT",
        "lifetime": "oneShot",
        "rootRpm": projection["captureRootRpm"],
        "captureRootRpm": projection["captureRootRpm"],
        "captureRpm": projection["captureRootRpm"],
        "captureThrottle": projection["captureThrottle"],
        "captureParameterValues": nominal_parameters,
        "captureAutomaticParameterValues": automatic_values,
        "captureOperatingPoints": capture_operating_points,
        "autoPitchReferenceRpm": projection["autoPitchReferenceRpm"],
        "rpmCurve": projection["rpmCurve"],
        "gainCurve": projection["gainCurve"],
        "baseGain": projection["baseGain"],
        "controllers": projection["controllers"],
        "timelineAutomation": timeline_evidence,
        "programPlacementRootInstrumentGuid": program_placement_root_guid,
        "selectionPath": selection_path,
        "sourceGeometry": {
            "nodes": source_geometry_nodes,
            "sampleTechnicalEvidence": sample_technical_evidence,
        },
        "programTriggerTemplate": program_trigger_template,
        "fixedGeometryOnlyDisposition": fixed_geometry_only_disposition,
        "triggerSemantics": {
            "kind": program_trigger,
            "evaluation": "sampledAfterAuthoredSeekAndBeforeAdditionalRuntimeFiltering",
            "allRuntimeRegionsMustBeInside": True,
            "runtimeRegions": runtime_regions,
            "fixedAutomaticRegions": fixed_regions,
            "arming": (
                "eventMustStartInsideAllRuntimeRegions"
                if has_runtime_region
                else "fixedCompilerGeometryEvaluatedAtEventStart"
            ),
            "initiallyOutside": (
                "disabledUntilEventRestart" if has_runtime_region else None
            ),
            "rearm": "afterAnyRuntimeRegionIsOutside" if has_runtime_region else "none",
            "triggerOnEventStartIfInside": True,
            "jumpAcrossRegionWithoutLandingInside": "doesNotTrigger",
            "leavingRegionCutsPlayingVoice": False,
            "reentryWhilePriorVoicePlays": "schedulesAdditionalOverlappingVoice",
            "overlapSemantics": "allowOverlap;PreserveGraphProgramHierarchyWeightsAndChance",
            "controlValues": {
                "rpms": "currentPresentationEngineRpmBeforeAnyAdditionalRuntimeFilter",
                "throttle": "currentNormalizedAcceleratorBeforeAnyAdditionalRuntimeFilter",
                "authoredSeekSpeeds": "applyBeforeRegionTest;allCurrentCatalogEntryRegionsAreZeroSeek",
            },
        },
        "voicePolicy": {
            "authoredInstrumentMaximumPolyphony": None,
            "authoredInstrumentStealPolicy": None,
            "graphV3ContainsThoseFields": False,
            "targetOracleMinimumProvenConcurrentVoices": 128,
            "targetOracleObservedAuthoredCapAtOrBelow128": False,
            "acGlobalLogicalVoiceCap": 2048,
            "acDefaultSoftwareRealVoiceBudget": 256,
            "runtimePolicy": "preserveLogicalVoicesAndFailClosedBeforeUnknownGlobalStealTie",
            "preallocation": {
                "scope": "perProgramAcrossAllSelectedLeavesAndCaptureVariants",
                "controlTickRateHz": 200,
                "minimumReentryTicks": 2,
                "minimumReentryFramesAt48000Hz": 480,
                "naturalLaneDemandFormula": "ceil(maxDecodedOneShotFrameCount/480)",
                "laneCountFormula": "min(2048,ceil(maxDecodedOneShotFrameCount/480))",
                "minimumLaneCount": 1,
                "requiredManifestInput": "maxDecodedOneShotFrameCount",
                "sessionInvariant": "oneLongLivedProgramInstance;profileRestartMustDrainOldProgram",
                "overflowAction": "ifNaturalLaneDemandExceeds2048FailClosedUntilGlobalStealTiePolicyIsOracleValidated",
                "softwareVirtualizationGate": "ifLogicalConcurrencyCanExceed256ValidateFmodPriorityAudibilityVirtualizationOrFailClosed",
                "observedEqualPriorityQueueModel": "olderPlayingVoicesRemainReal;newestEntriesVirtualize;promoteInEntryOrderAsRealSlotsOpen",
                "observedVirtualPhaseModel": "virtualVoicesRemainNearOnsetRatherThanAdvancingAtMediaRate;promotedVoicesThenAdvance",
            },
            "evidence": {
                "runtime": _REFERENCE_RENDERER_VERSION,
                "output": "WAVWRITER_NRT",
                "eventStartedInsideThenExitedAndReentered": True,
                "secondSoundPlayedCallbackBeforeFirstSoundStopped": True,
                "noSoundStoppedCallbackOnExitOrReentry": True,
                "rapidProbe64": "81Played17Stopped64ActiveAtSystemMaxChannels64",
                "rapidProbe128": "161Played33Stopped128ActiveAtSystemMaxChannels128",
                "capScalesWithSystemInitializeMaxChannels": True,
                "assettoInitializeMaxChannelsDisassembly": "edx=0x800AtStudioSystemInitializeCall",
                "assettoSoftwareChannelsSource": "system/cfg/audio_engine.ini:[SETTINGS]MAX_CHANNELS=256",
                "stockBudgetProbe": "301Logical256Real;45NewestNearOnsetVirtual;all301EventuallyStoppedNaturally",
                "stockBudgetPeakRealPcmPositionRange": [15679, 104533],
                "stockBudgetPeakVirtualPcmPositionRange": [0, 1137],
                "after100UpdatesLogicalRealVirtual": [287, 256, 31],
                "after100UpdatesVirtualPcmPositionRange": [697, 1473],
                "crossProgramPriorityAndAudibilityTiePolicyClaimed": False,
                "authoredOrUnboundedPolyphonyClaimed": False,
            },
        },
        "pitchTreatment": {
            "runtimeVarispeed": True,
            "rootRpm": projection["captureRootRpm"],
            "scale": "currentPresentationEngineRpm/rootRpm",
            "updatesContinuouslyWhileVoiceIsActive": True,
            "continuesAfterParameterGateExit": True,
            "fmodLivePitchLatchSemantics": "notLatched",
            "entryEdgeSpecificCaptureVariants": False,
            "captureOperatingPointEdgesAreValidationOnly": True,
            "oracleBound": {
                "runtime": _REFERENCE_RENDERER_VERSION,
                "dspBufferFrames": 256,
                "fixed3000TotalUpdates": 1244,
                "move3000To4500After101UpdatesTotalUpdates": 864,
                "move3000To5400After101UpdatesTotalUpdates": 737,
                "maximumDurationPredictionErrorUpdates": 1,
            },
            "zeroGainVirtualization": "failClosedIfAReleasedProgramCanReachExactZeroGainBeforePcmEnd",
            "timelineAutomation": "targetCompareVarispeededCaptureAgainstLiveFmodBeforeRelease",
        },
        "projection": {
            "syntheticFullDomainSpeedPlacementUsedOnlyForConstantGainProjection": synthetic_speed_placement,
            "timelineControllersRemovedOnlyFromStaticGainProjection": sorted(
                timeline_controller_guids
            ),
            "triggerPlacements": projection["triggerPlacements"],
            "normalization": projection["normalization"],
        },
        "fidelity": {
            **projection["fidelity"],
            "sampleNamesUsed": False,
            "exactnessClaim": False,
            "regionLifecycleOracleValidated": True,
            "liveAutoPitchMotionOracleValidated": True,
            "requiredFinalGate": "targetOnlyFmod108TimelineAndZeroGainVirtualizationComparisonPlusPerProgramFrameBound",
        },
        "unsupported": projection["unsupported"],
    }
    return output


def certify_manifest_engine_transient_source(
    one_shot_source: dict[str, Any], source_verification: dict[str, Any]
) -> dict[str, Any]:
    """Bind one ENGINE_TRANSIENT leaf to source-only FMOD 1.08 evidence.

    Static graph derivation cannot prove timeline output, live AutoPitch or
    FMOD's zero-gain phase freeze.  Certification therefore requires two
    independently repeated target renders cropped by the observed parent DSP
    clock, plus explicit pitch/phase/DSP dispositions.  Sample names are not
    semantic input and are not copied into the certified record.
    """

    if (
        not isinstance(one_shot_source, dict)
        or one_shot_source.get("schema") != ONE_SHOT_CURVE_SCHEMA
        or one_shot_source.get("manifestRole") != "ENGINE_TRANSIENT"
        or one_shot_source.get("lifetime") != "oneShot"
    ):
        _fail("invalidEngineTransientSource", f"expected {ONE_SHOT_CURVE_SCHEMA}")
    if (
        not isinstance(source_verification, dict)
        or source_verification.get("schema")
        != ENGINE_TRANSIENT_VERIFICATION_SCHEMA
    ):
        _fail(
            "invalidEngineTransientVerification",
            f"expected {ENGINE_TRANSIENT_VERIFICATION_SCHEMA}",
        )
    source = copy.deepcopy(one_shot_source)
    evidence = copy.deepcopy(source_verification)
    fidelity = source.get("fidelity")
    if not isinstance(fidelity, dict) or fidelity.get("exactnessClaim") is not False:
        _fail("invalidEngineTransientSource", "source is not awaiting verification")
    if (
        str(evidence.get("sourceGuid")) != str(source.get("sourceGuid"))
        or str(evidence.get("eventPath")) != str(source.get("eventPath"))
        or str(evidence.get("programPlacementRootInstrumentGuid"))
        != str(source.get("programPlacementRootInstrumentGuid"))
    ):
        _fail("engineTransientVerificationIdentityMismatch", "source identity differs")
    if evidence.get("derivedSourceSha256") != _canonical_json_sha256(source):
        _fail("engineTransientVerificationSourceMismatch", "derived-source hash differs")
    renderer = evidence.get("renderer")
    if not isinstance(renderer, dict) or renderer != {
        "runtime": _REFERENCE_RENDERER_VERSION,
        "sampleRateHz": 48000,
        "channels": 2,
        "sampleFormat": "signedPcm16LittleEndian",
        "audioDeviceOpened": False,
        "targetOnly": True,
    }:
        _fail("engineTransientVerificationRuntimeMismatch", "renderer contract differs")

    capture = evidence.get("capture")
    if not isinstance(capture, dict) or capture.get("accepted") is not True:
        _fail("engineTransientPcmVerificationFailed", "capture was not accepted")
    if (
        capture.get("oracleVersion")
        != ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION
        or capture.get("captureParameterValues")
        != source.get("captureParameterValues")
        or _guid(capture.get("scheduledSourceGuid")) != source.get("sourceGuid")
        or capture.get("independentRenderBitExact") is not True
        or capture.get("writerFrameIndexEqualsParentDspClock") is not True
        or capture.get("timelineAuthoredSilentPrefixPreserved") is not True
    ):
        _fail("engineTransientPcmVerificationFailed", "PCM oracle contract differs")

    def require_fresh_worker(
        raw: object, expected_operation: str
    ) -> dict[str, Any]:
        if not isinstance(raw, dict):
            _fail(
                "engineTransientProcessIsolationFailed",
                f"{expected_operation} fresh-worker proof is absent",
            )
        item = cast(dict[str, Any], raw)
        hashes = (
            "requestSha256",
            "requestFileSha256",
            "resultFileSha256",
            "payloadSha256",
        )
        paths = ("requestFileRelativePath", "resultFileRelativePath")
        try:
            launcher_pid = int(item.get("launcherProcessId"))
            worker_pid = int(item.get("workerProcessId"))
            timeout = int(item.get("timeoutSeconds"))
        except (TypeError, ValueError):
            _fail(
                "engineTransientProcessIsolationFailed",
                f"{expected_operation} worker identity is invalid",
            )
        if (
            item.get("requestSchema")
            != "ac-fmod-engine-transient-worker-request-v1"
            or item.get("resultSchema")
            != "ac-fmod-engine-transient-worker-result-v1"
            or item.get("operation") != expected_operation
            or item.get("processBoundary")
            != "ONE_NEW_PYTHON_PROCESS_FOR_THIS_OPERATION"
            or launcher_pid <= 0
            or worker_pid <= 0
            or launcher_pid == worker_pid
            or timeout != 180
            or any(
                len(str(item.get(name) or "")) != 64
                or any(
                    character not in "0123456789abcdef"
                    for character in str(item.get(name) or "").casefold()
                )
                for name in hashes
            )
            or any(
                not str(item.get(name) or "")
                or Path(str(item.get(name))).is_absolute()
                or ".." in Path(str(item.get(name))).parts
                for name in paths
            )
        ):
            _fail(
                "engineTransientProcessIsolationFailed",
                f"{expected_operation} fresh-worker contract differs",
            )
        return item

    capture_workers_raw = capture.get("independentFreshProcessRenders")
    if not isinstance(capture_workers_raw, list) or len(capture_workers_raw) != 2:
        _fail(
            "engineTransientProcessIsolationFailed",
            "two fresh PCM render workers are required",
        )
    capture_workers = [
        require_fresh_worker(item, "render") for item in capture_workers_raw
    ]
    if len({int(item["workerProcessId"]) for item in capture_workers}) != 2:
        _fail(
            "engineTransientProcessIsolationFailed",
            "independent PCM renders reused a worker process",
        )
    disposition = str(capture.get("audibilityDisposition") or "")
    all_zero = capture.get("allPcmSamplesZero") is True
    try:
        frame_count = int(capture.get("frameCount"))
        playback_start = int(capture.get("playbackStartFrame"))
        playback_end = int(capture.get("playbackEndFrameExclusive"))
        alignment_error = int(capture.get("dspClockAlignmentErrorBoundFrames"))
        termination_error = int(capture.get("terminationTimingErrorBoundFrames"))
        priority = int(evidence.get("voicePolicy", {}).get("softwareChannelPriority"))
    except (TypeError, ValueError):
        _fail("engineTransientPcmVerificationFailed", "PCM bounds/priority are invalid")
    pcm_hash = str(capture.get("pcmPayloadSha256") or "").casefold()
    wav_hash = str(capture.get("finalWavSha256") or "").casefold()
    relative_path = str(capture.get("finalWavRelativePath") or "")
    peak_dbfs = _finite(
        capture.get("peakPcmDbfs"),
        code="engineTransientPcmVerificationFailed",
        detail="capture peak is invalid",
    )
    if (
        disposition not in {"AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_SILENT"}
        or all_zero != (disposition == "AUTHORED_TARGET_SILENT")
        or frame_count <= 0
        or playback_start != 0
        or playback_end != frame_count
        or alignment_error != 0
        or termination_error < 0
        or termination_error > 256
        or priority < 0
        or priority > 256
        or len(pcm_hash) != 64
        or any(character not in "0123456789abcdef" for character in pcm_hash)
        or len(wav_hash) != 64
        or any(character not in "0123456789abcdef" for character in wav_hash)
        or not relative_path
        or Path(relative_path).is_absolute()
        or ".." in Path(relative_path).parts
        or (disposition == "AUDIBLE_TARGET_PCM" and peak_dbfs <= -120.0)
        or (disposition == "AUTHORED_TARGET_SILENT" and peak_dbfs > -120.0)
    ):
        _fail("engineTransientPcmVerificationFailed", "PCM identity/audibility is invalid")
    schedule = capture.get("dspClockAlignment")
    if not isinstance(schedule, dict):
        _fail("engineTransientPcmVerificationFailed", "DSP alignment proof is absent")
    try:
        starts = [int(value) for value in schedule["independentScheduleStartDspClockFrames"]]
        local_clocks = [int(value) for value in schedule["channelLocalDspClockAtScheduleObservation"]]
        parent_clocks = [
            int(value)
            for value in schedule[
                "immediateParentDspClockAtScheduleObservation"
            ]
        ]
        writer_clocks = [
            int(value)
            for value in schedule["writerMasterDspClockAtScheduleObservation"]
        ]
        crop_starts = [int(value) for value in schedule["writerCropStartFrames"]]
        dsp_block = int(schedule["dspBufferFrames"])
        compared = int(schedule["comparedFrameCount"])
    except (KeyError, TypeError, ValueError):
        _fail("engineTransientPcmVerificationFailed", "DSP alignment fields are invalid")
    if (
        len(starts) != 2
        or len(local_clocks) != 2
        or len(parent_clocks) != 2
        or len(writer_clocks) != 2
        or len(crop_starts) != 2
        or any(value < 0 or value % 256 for value in starts)
        or any(value < 0 for value in local_clocks)
        or any(parent < local for parent, local in zip(parent_clocks, local_clocks))
        or writer_clocks != starts
        or crop_starts != starts
        or dsp_block != 256
        or compared != frame_count
        or schedule.get("method")
        != "TARGET_SCHEDULE_FRAME_EQUALS_MASTER_WRITER_DSP_CLOCK_AT_DISCOVERY_UPDATE"
        or schedule.get("sampleNameUsedForSchedulingSemantics") is not False
    ):
        _fail("engineTransientPcmVerificationFailed", "DSP alignment exceeds zero frames")
    embedded = capture.get("embeddedSourcePcmEvidence")
    sample = source.get("sourceGeometry", {}).get("sampleTechnicalEvidence")
    if not isinstance(embedded, dict) or not isinstance(sample, dict):
        _fail("engineTransientPcmVerificationFailed", "embedded PCM proof is absent")
    for evidence_key, source_key in (
        ("frameCount", "sampleCount"),
        ("sampleRateHz", "frequencyHz"),
        ("channels", "channels"),
        ("soundBankIndex", "soundBankIndex"),
        ("subsoundIndex", "subsoundIndex"),
        ("encodedPayloadSha256", "encodedPayloadSha256"),
    ):
        if embedded.get(evidence_key) != sample.get(source_key):
            _fail("engineTransientPcmVerificationFailed", "embedded PCM differs from graph")
    if (
        embedded.get("accepted") is not True
        or embedded.get("encoding") != "FSB5_PCM16_LE"
        or embedded.get("sampleNameUsed") is not False
    ):
        _fail("engineTransientPcmVerificationFailed", "embedded PCM contract differs")

    pitch = evidence.get("pitchVerification")
    if not isinstance(pitch, dict) or pitch.get("accepted") is not True:
        _fail("engineTransientPitchVerificationFailed", "pitch proof is absent")
    capture_rpm = _finite(pitch.get("captureRpm"), code="engineTransientPitchVerificationFailed", detail="capture RPM is invalid")
    probe_rpm = _finite(pitch.get("probeRpm"), code="engineTransientPitchVerificationFailed", detail="probe RPM is invalid")
    capture_pitch = _finite(pitch.get("captureChannelPitch"), code="engineTransientPitchVerificationFailed", detail="capture pitch is invalid")
    probe_pitch = _finite(pitch.get("probeChannelPitch"), code="engineTransientPitchVerificationFailed", detail="probe pitch is invalid")
    pitch_error = _finite(pitch.get("maximumPlaybackRateRatioError"), code="engineTransientPitchVerificationFailed", detail="pitch error is invalid")
    pitch_mode = str(pitch.get("mode") or "")
    if (
        capture_rpm != float(source.get("captureRpm"))
        or probe_rpm == capture_rpm
        or capture_pitch <= 0.0
        or probe_pitch <= 0.0
        or pitch_error < 0.0
        or pitch_error > 1.0e-5
        or pitch_mode not in {"LIVE_RPM_RATIO", "STATIC_BAKED_PITCH"}
        or pitch.get("updatesWhileVoiceActive") is not True
        or pitch.get("sourceBoundChannelObserved") is not True
    ):
        _fail("engineTransientPitchVerificationFailed", "pitch proof exceeds its bound")
    runtime_varispeed = pitch_mode == "LIVE_RPM_RATIO"
    observed_ratio = probe_pitch / capture_pitch
    expected_ratio = probe_rpm / capture_rpm if runtime_varispeed else 1.0
    if abs(observed_ratio - expected_ratio) > (1.0e-5 if runtime_varispeed else 1.0e-6):
        _fail("engineTransientPitchVerificationFailed", "active pitch ratio differs")
    require_fresh_worker(pitch.get("freshProcessEvidence"), "pitch")

    zero_gain = evidence.get("zeroGainVirtualization")
    if not isinstance(zero_gain, dict):
        _fail("engineTransientZeroGainVerificationFailed", "phase proof is absent")
    zero_policy = str(zero_gain.get("phasePolicy") or "")
    runtime_regions = source.get("triggerSemantics", {}).get("runtimeRegions", [])

    def point_is_inside_active_regions(control: str, value: float) -> bool:
        for region in runtime_regions:
            if not isinstance(region, dict) or region.get("parameter") != control:
                continue
            minimum = float(region["minimum"])
            maximum = float(region["maximum"])
            if not (
                (value > minimum or (value == minimum and region["includeMinimum"]))
                and (
                    value < maximum
                    or (value == maximum and region["includeMaximum"])
                )
            ):
                return False
        return True

    graph_exact_zero_reachable = any(
        float(y) == 0.0 and point_is_inside_active_regions("rpms", float(x))
        for x, y in source.get("rpmCurve", [])
    ) or any(
        float(y) == 0.0 and point_is_inside_active_regions("throttle", float(x))
        for x, y in source.get("gainCurve", [])
    )
    if (
        zero_gain.get("accepted") is not True
        or zero_policy
        not in {
            "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
            "ADVANCE_LOGICAL_AND_DECODE_PHASE_AT_ACTIVE_PITCH_WHILE_EXACT_ZERO",
            "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE",
        }
        or graph_exact_zero_reachable
        != (
            zero_policy
            in {
                "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
                "ADVANCE_LOGICAL_AND_DECODE_PHASE_AT_ACTIVE_PITCH_WHILE_EXACT_ZERO",
            }
        )
        or zero_gain.get("reentryPolicy")
        not in {
            (
                "PRESERVE_PRIOR_UNTIL_SOURCE_BOUND_NATURAL_END_AND_SCHEDULE_NEW_ON_REENTRY;"
                "OVERLAP_IF_PRIOR_REMAINS_ALIVE"
            ),
            "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER",
        }
        or zero_gain.get("maximumPhaseObservationErrorFrames") != 0
        or not isinstance(zero_gain.get("newVoicePcmPositionsAtReentry"), list)
        or zero_gain.get("reentryOutcome")
        not in {
            "PRIOR_RETAINED_AND_OVERLAPPED_NEW_VOICE",
            "PRIOR_NATURALLY_ENDED_BEFORE_NEW_VOICE",
            "PRIOR_NATURALLY_ENDED_AND_REENTRY_DID_NOT_SCHEDULE_NEW_VOICE",
        }
        or (
            zero_gain.get("reentryOutcome")
            == "PRIOR_RETAINED_AND_OVERLAPPED_NEW_VOICE"
            and (
                zero_gain.get("priorVoicePresentAtReentry") is not True
                or int(zero_gain.get("reentryTargetVoiceCount", 0)) < 2
                or zero_gain.get("priorVoicePcmPositionAtReentry") is None
            )
        )
        or (
            zero_gain.get("reentryOutcome")
            == "PRIOR_NATURALLY_ENDED_BEFORE_NEW_VOICE"
            and (
                zero_gain.get("priorVoicePresentAtReentry") is not False
                or zero_gain.get("priorVoicePcmPositionAtReentry") is not None
            )
        )
        or (
            zero_gain.get("reentryPolicy")
            == "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER"
            and (
                zero_gain.get("reentryOutcome")
                != "PRIOR_NATURALLY_ENDED_AND_REENTRY_DID_NOT_SCHEDULE_NEW_VOICE"
                or int(zero_gain.get("reentryTargetVoiceCount", -1)) != 0
                or len(zero_gain.get("newVoicePcmPositionsAtReentry")) != 0
                or zero_gain.get("priorVoicePresentAtReentry") is not False
            )
        )
        or (
            zero_gain.get("reentryPolicy")
            != "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER"
            and (
                int(zero_gain.get("reentryTargetVoiceCount", 0)) < 1
                or len(zero_gain.get("newVoicePcmPositionsAtReentry")) < 1
            )
        )
    ):
        _fail("engineTransientZeroGainVerificationFailed", "phase-freeze proof differs")
    if graph_exact_zero_reachable:
        positions = zero_gain.get("zeroGainPcmPositionObservations")
        virtual_states = zero_gain.get("zeroGainVirtualStateObservations")
        audibilities = zero_gain.get("zeroGainAudibilityObservations")
        if (
            not isinstance(positions, list)
            or not isinstance(virtual_states, list)
            or not isinstance(audibilities, list)
            or len(positions) < 2
            or len(positions) != len(virtual_states)
            or len(positions) != len(audibilities)
            or len(audibilities) < 4
            or any(float(value) != 0.0 for value in audibilities[-4:])
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "exact-zero channel evidence is invalid",
            )
    runtime_zero = zero_gain.get("runtimeSemantic")
    expected_zero_kind = {
        "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE": "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
        "ADVANCE_LOGICAL_AND_DECODE_PHASE_AT_ACTIVE_PITCH_WHILE_EXACT_ZERO": "ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO",
        "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE": "NOT_APPLICABLE",
    }[zero_policy]
    if (
        not isinstance(runtime_zero, dict)
        or runtime_zero.get("kind") != expected_zero_kind
    ):
        _fail(
            "engineTransientZeroGainVerificationFailed",
            "runtime zero-gain semantic is absent",
        )
    required_runtime_zero_keys_by_kind = {
        "NOT_APPLICABLE": {
            "kind",
            "logicalVoiceDeadlineAdvancesAtWriterTime",
            "decodeCursorTreatment",
            "zeroTransition",
        },
        "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE": {
            "kind",
            "mixerZeroGateAction",
            "ordinaryNonzeroGainSmoothingUnaffected",
            "decodePhaseBeforeHold",
            "phaseHoldLatencyWriterFrames",
            "phaseAndDeadlineAdvanceWriterFramesBeforeHold",
            "phaseHoldLatencyFrameDomain",
            "holdDecodePhaseAfterLatency",
            "pauseNaturalEndDeadlineWhileHeld",
            "reaudibilizationBeforeDeadline",
            "writerDspBlockFrames",
            "zeroTransition",
            "channelGetPositionWhileVirtualIsRuntimeAuthoritative",
        },
        "ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO": {
            "kind",
            "mixerZeroGateAction",
            "ordinaryNonzeroGainSmoothingUnaffected",
            "decodePhaseWhileExactZero",
            "naturalEndDeadlineAdvancesWhileExactZero",
            "reaudibilizationBeforeDeadline",
            "writerDspBlockFrames",
            "zeroTransition",
            "channelGetPositionWhileVirtualIsRuntimeAuthoritative",
        },
    }
    required_runtime_zero_keys = required_runtime_zero_keys_by_kind[
        expected_zero_kind
    ]
    if set(runtime_zero) != required_runtime_zero_keys:
        _fail(
            "engineTransientZeroGainVerificationFailed",
            "runtime zero-gain semantic keys differ",
        )
    if expected_zero_kind == "NOT_APPLICABLE":
        no_zero_transition = runtime_zero.get("zeroTransition")
        if (
            runtime_zero.get("logicalVoiceDeadlineAdvancesAtWriterTime") is not True
            or runtime_zero.get("decodeCursorTreatment") != "NORMAL_ACTIVE_VOICE"
            or not isinstance(no_zero_transition, dict)
            or no_zero_transition
            != {
                "policy": "NOT_APPLICABLE",
                "reason": (
                    "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"
                ),
            }
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "no-exact-zero runtime semantic differs",
            )
    if expected_zero_kind != "NOT_APPLICABLE" and (
        runtime_zero.get("mixerZeroGateAction")
        != (
            "APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;"
            "DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING"
        )
        or runtime_zero.get("ordinaryNonzeroGainSmoothingUnaffected") is not True
        or int(runtime_zero.get("writerDspBlockFrames", 0)) != 256
        or runtime_zero.get("channelGetPositionWhileVirtualIsRuntimeAuthoritative")
        is not False
    ):
        _fail(
            "engineTransientZeroGainVerificationFailed",
            "runtime zero-gain lifecycle differs",
        )
    if expected_zero_kind != "NOT_APPLICABLE":
        pcm_comparison = zero_gain.get("postRestorePcmVerification")
        zero_output_gate = (
            pcm_comparison.get("zeroOutputGateVerification")
            if isinstance(pcm_comparison, dict)
            else None
        )
        if (
            not isinstance(pcm_comparison, dict)
            or pcm_comparison.get("accepted") is not True
            or pcm_comparison.get("comparison")
            != "POST_RESTORE_PHASE_AT_FROZEN_LOGICAL_AGE"
            or float(pcm_comparison.get("gainErrorDb", float("inf"))) > 0.02
            or (
                float(
                    pcm_comparison.get(
                        "normalizedCorrelation", float("-inf")
                    )
                )
                < 0.999
                and int(
                    pcm_comparison.get("maximumAbsoluteDifferenceLsb", 2)
                )
                > 1
            )
            or not math.isfinite(
                float(
                    pcm_comparison.get(
                        "differenceBelowReferenceDb", float("nan")
                    )
                )
            )
            or int(pcm_comparison.get("comparedFrameCount", 0)) <= 0
            or not isinstance(zero_output_gate, dict)
            or zero_output_gate.get("accepted") is not True
            or zero_output_gate.get("method")
            != "TARGET_ONLY_PCM16_DURING_EXACT_ZERO_CONTROL_HOLD"
            or zero_output_gate.get("frameDomain")
            != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "post-restore PCM phase comparison failed",
            )
        deadline_extension = int(
            pcm_comparison.get("logicalDeadlineExtensionFrames", -1)
        )
        hold_frames = int(pcm_comparison.get("zeroHoldFrames", -1))
        zero_transition = runtime_zero.get("zeroTransition")
        compact_transition_fit = (
            zero_output_gate.get("compactTransitionFit")
            if isinstance(zero_output_gate, dict)
            else None
        )
        holds_phase = expected_zero_kind == (
            "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE"
        )
        if (
            deadline_extension < 0
            or hold_frames <= 0
            or deadline_extension > hold_frames
            or deadline_extension % 256
            or not isinstance(zero_transition, dict)
            or not isinstance(compact_transition_fit, dict)
            or set(zero_transition)
            != {
                "policy",
                "frameDomain",
                "gainInterpolation",
                "gainAtTransitionStart",
                "gainAtExactZero",
                "retainPreZeroGainWriterFrames",
                "linearFadeWriterFrames",
                "exactZeroFromWriterFrame",
                "pitchDuringTransition",
                "phaseTreatment",
                "restoreCapturePcmPhaseOffsetFrames",
                "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames",
                "positiveGainReturnBeforePhaseHoldPolicy",
                "subsequentExactZeroCrossingPolicy",
                "residualMaximumAbsolutePcmLsb",
                "acceptanceBoundMaximumAbsolutePcmLsb",
            }
            or zero_transition.get("policy")
            not in {
                "IMMEDIATE_EXACT_ZERO",
                "RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO",
            }
            or zero_transition.get("frameDomain")
            != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
            or zero_transition.get("gainInterpolation")
            != "LINEAR_PER_WRITER_FRAME"
            or float(zero_transition.get("gainAtTransitionStart", -1.0)) != 1.0
            or float(zero_transition.get("gainAtExactZero", -1.0)) != 0.0
            or int(zero_transition.get("retainPreZeroGainWriterFrames", -1)) < 0
            or int(zero_transition.get("linearFadeWriterFrames", -1)) < 0
            or int(zero_transition.get("exactZeroFromWriterFrame", -1))
            != int(zero_transition.get("retainPreZeroGainWriterFrames", -1))
            + int(zero_transition.get("linearFadeWriterFrames", -1))
            or int(zero_transition.get("exactZeroFromWriterFrame", -1))
            > hold_frames
            or zero_transition.get("pitchDuringTransition")
            not in {"LIVE_CURRENT_RPM_PITCH", "AUTHORED_STATIC_BAKED_PITCH"}
            or zero_transition.get("pitchDuringTransition")
            != (
                "LIVE_CURRENT_RPM_PITCH"
                if runtime_varispeed
                else "AUTHORED_STATIC_BAKED_PITCH"
            )
            or zero_transition.get("phaseTreatment")
            not in {
                "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
                "APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET",
            }
            or zero_transition.get("positiveGainReturnBeforePhaseHoldPolicy")
            != (
                "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_"
                "WITHOUT_PHASE_OR_DEADLINE_HOLD"
            )
            or zero_transition.get("subsequentExactZeroCrossingPolicy")
            != (
                "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
                "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
            )
            or int(
                compact_transition_fit.get(
                    "pitchOnlyReferencePhaseOffsetWriterFrames", 1
                )
            )
            != 0
            or zero_transition.get("policy")
            != compact_transition_fit.get("policy")
            or int(zero_transition.get("retainPreZeroGainWriterFrames", -1))
            != int(
                compact_transition_fit.get(
                    "retainPreZeroGainWriterFrames", -2
                )
            )
            or int(zero_transition.get("linearFadeWriterFrames", -1))
            != int(compact_transition_fit.get("linearFadeWriterFrames", -2))
            or int(zero_transition.get("exactZeroFromWriterFrame", -1))
            != int(compact_transition_fit.get("exactZeroFromWriterFrame", -2))
            or zero_transition.get("pitchDuringTransition")
            != compact_transition_fit.get("pitchDuringPassThrough")
            or float(
                zero_transition.get(
                    "residualMaximumAbsolutePcmLsb", float("inf")
                )
            )
            > float(
                zero_transition.get(
                    "acceptanceBoundMaximumAbsolutePcmLsb", -1.0
                )
            )
            or (
                zero_transition.get("policy") == "IMMEDIATE_EXACT_ZERO"
                and int(zero_transition.get("exactZeroFromWriterFrame", -1)) != 0
            )
            or (
                zero_transition.get("policy")
                == "RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO"
                and int(zero_transition.get("linearFadeWriterFrames", 0)) <= 0
            )
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "zero-gain phase/deadline hold contract differs",
            )
        restore_phase_offset = _finite(
            zero_transition.get("restoreCapturePcmPhaseOffsetFrames"),
            code="engineTransientZeroGainVerificationFailed",
            detail="restore phase offset is invalid",
        )
        restore_phase_bound = _finite(
            zero_transition.get(
                "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames"
            ),
            code="engineTransientZeroGainVerificationFailed",
            detail="restore phase offset bound is invalid",
        )
        fractional_phase = pcm_comparison.get("fractionalPhaseOnlyAlignment")
        fractional_offset = 0.0
        if fractional_phase is not None:
            if (
                not isinstance(fractional_phase, dict)
                or fractional_phase.get("method")
                != "ONE_STEREO_FOURIER_FRACTIONAL_FRAME_PHASE_SHIFT_ONLY"
                or fractional_phase.get("phaseOffsetFrameDomain")
                != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
                or fractional_phase.get("gainOrSpectralCorrectionFitted") is not False
                or float(fractional_phase.get("maximumAbsolutePhaseSearchFrames", -1.0))
                != 4.0
                or float(fractional_phase.get("gainErrorDb", float("inf")))
                > 0.02
                or float(
                    fractional_phase.get("normalizedCorrelation", float("-inf"))
                )
                < 0.999
                or float(
                    fractional_phase.get(
                        "earlyLateEstimateDifferenceFrames", float("inf")
                    )
                )
                > 0.01
            ):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "fractional restore-phase proof exceeds its bound",
                )
            fractional_offset = _finite(
                fractional_phase.get("phaseOffsetReferenceFrames"),
                code="engineTransientZeroGainVerificationFailed",
                detail="fractional restore phase offset is invalid",
            )
        try:
            integer_phase_lag = int(
                pcm_comparison.get("bestBaselinePhaseLagFrames")
            )
        except (TypeError, ValueError):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "integer restore phase offset is invalid",
            )
        if (
            restore_phase_bound != 512.0
            or abs(restore_phase_offset) > restore_phase_bound
            or abs(
                restore_phase_offset
                - (float(integer_phase_lag) + fractional_offset)
            )
            > 1.0e-9
            or pcm_comparison.get("postRestorePhaseTreatment")
            != zero_transition.get("phaseTreatment")
            or float(
                pcm_comparison.get(
                    "restoreCapturePcmPhaseOffsetFrames", float("nan")
                )
            )
            != restore_phase_offset
            or float(
                pcm_comparison.get(
                    "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames",
                    float("nan"),
                )
            )
            != restore_phase_bound
            or (
                zero_transition.get("phaseTreatment")
                == "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET"
                and restore_phase_offset != 0.0
            )
            or (
                zero_transition.get("phaseTreatment")
                == "APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET"
                and restore_phase_offset == 0.0
            )
            or (
                not holds_phase
                and (
                    zero_transition.get("phaseTreatment")
                    != "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET"
                    or restore_phase_offset != 0.0
                )
            )
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "restore phase-offset runtime contract differs",
            )
        if holds_phase:
            latency_frames = int(
                runtime_zero.get("phaseHoldLatencyWriterFrames", -1)
            )
            deadline_inferred_latency = int(
                pcm_comparison.get(
                    "naturalDeadlineInferredPhaseHoldLatencyWriterFrames", -1
                )
            )
            deadline_onset_error = int(
                pcm_comparison.get(
                    "naturalDeadlineHoldOnsetErrorWriterFrames", -1
                )
            )
            deadline_onset_bound = int(
                pcm_comparison.get(
                    "naturalDeadlineHoldOnsetErrorBoundWriterFrames", -1
                )
            )
            if (
                deadline_inferred_latency != hold_frames - deadline_extension
                or deadline_onset_error
                != abs(deadline_inferred_latency - latency_frames)
                or deadline_onset_bound != 256
                or deadline_onset_error > deadline_onset_bound
                or int(
                    pcm_comparison.get("phaseAdvanceBeforeHoldFrames", -1)
                )
                != latency_frames
                or int(
                    runtime_zero.get(
                        "phaseAndDeadlineAdvanceWriterFramesBeforeHold", -1
                    )
                )
                != latency_frames
                or latency_frames <= 0
                or latency_frames % 256
                or int(zero_transition["exactZeroFromWriterFrame"])
                > latency_frames
                or runtime_zero.get("phaseHoldLatencyFrameDomain")
                != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
                or runtime_zero.get("decodePhaseBeforeHold")
                != "CURRENT_ACTIVE_VOICE_PITCH"
                or runtime_zero.get("holdDecodePhaseAfterLatency") is not True
                or runtime_zero.get("pauseNaturalEndDeadlineWhileHeld") is not True
                or runtime_zero.get("reaudibilizationBeforeDeadline")
                != "CONTINUE_FROM_HELD_LOGICAL_PHASE"
            ):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "zero-gain hold latency differs",
                )
        elif (
            runtime_zero.get("decodePhaseWhileExactZero")
            != "CURRENT_ACTIVE_VOICE_PITCH"
            or runtime_zero.get("naturalEndDeadlineAdvancesWhileExactZero")
            is not True
            or runtime_zero.get("reaudibilizationBeforeDeadline")
            != "CONTINUE_FROM_ADVANCED_LOGICAL_PHASE"
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "zero-gain advance policy differs",
            )
        brief = zero_gain.get("briefZeroTransitionVerification")
        expected_brief_conclusion = {
            "positiveGainReturnBeforePhaseHoldPolicy": (
                "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_"
                "WITHOUT_PHASE_OR_DEADLINE_HOLD"
            ),
            "subsequentExactZeroCrossingPolicy": (
                "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
                "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
            ),
        }
        sequences = brief.get("sequences") if isinstance(brief, dict) else None
        if (
            not isinstance(brief, dict)
            or brief.get("accepted") is not True
            or int(brief.get("controlUpdateQuantumWriterFrames", 0)) != 256
            or brief.get("frameDomain")
            != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
            or brief.get("runtimeConclusion") != expected_brief_conclusion
            or brief.get("sampleNameUsedForSemantics") is not False
            or not isinstance(sequences, list)
            or len(sequences) != 2
        ):
            _fail(
                "engineTransientZeroGainVerificationFailed",
                "brief zero/recovery proof is absent or differs",
            )
        expected_zero_updates = (1, 2)
        brief_worker_requests: set[str] = set()
        for sequence, zero_updates in zip(sequences, expected_zero_updates):
            if not isinstance(sequence, dict):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "brief zero/recovery sequence is invalid",
                )
            workers_raw = sequence.get("independentFreshProcessRenders")
            observations = sequence.get("observations")
            block_metrics = sequence.get("writerBlockMetricsFromFirstZero")
            pcm_payload_sha = str(sequence.get("pcmPayloadSha256") or "")
            if (
                sequence.get("accepted") is not True
                or sequence.get("independentRenderBitExact") is not True
                or int(sequence.get("writerDspBlockFrames", 0)) != 256
                or int(sequence.get("zeroUpdatesBeforeRecovery", 0))
                != zero_updates
                or int(sequence.get("recoveryUpdatesBeforeSecondZero", 0)) != 1
                or int(sequence.get("secondZeroObservationUpdates", 0)) < 8
                or int(
                    sequence.get("firstZeroToPositiveReturnWriterFrames", -1)
                )
                != zero_updates * 256
                or int(
                    sequence.get("positiveReturnToSecondZeroWriterFrames", -1)
                )
                != 256
                or len(pcm_payload_sha) != 64
                or any(
                    character not in "0123456789abcdef"
                    for character in pcm_payload_sha.casefold()
                )
                or not isinstance(workers_raw, list)
                or len(workers_raw) != 2
                or not isinstance(observations, list)
                or not isinstance(block_metrics, list)
            ):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "brief zero/recovery sequence contract differs",
                )
            workers = [
                require_fresh_worker(item, "briefZeroRecoveryRender")
                for item in workers_raw
            ]
            if len({int(item["workerProcessId"]) for item in workers}) != 2:
                _fail(
                    "engineTransientProcessIsolationFailed",
                    "brief zero/recovery repeats reused a worker process",
                )
            for item in workers:
                request_sha = str(item["requestSha256"])
                if request_sha in brief_worker_requests:
                    _fail(
                        "engineTransientProcessIsolationFailed",
                        "brief zero/recovery request was reused",
                    )
                brief_worker_requests.add(request_sha)

            def observations_with_prefix(prefix: str) -> list[dict[str, Any]]:
                return [
                    cast(dict[str, Any], item)
                    for item in observations
                    if isinstance(item, dict)
                    and str(item.get("label") or "").startswith(prefix)
                ]

            first_zero_observations = observations_with_prefix(
                "brief-zero-first-zero-"
            )
            recovery_observations = observations_with_prefix(
                "brief-zero-recovery-"
            )
            second_zero_observations = observations_with_prefix(
                "brief-zero-second-zero-"
            )
            if (
                len(first_zero_observations) != zero_updates
                or len(recovery_observations) != 1
                or len(second_zero_observations)
                != int(sequence["secondZeroObservationUpdates"])
                or any(
                    not isinstance(item.get("priorVoice"), dict)
                    for item in (
                        first_zero_observations
                        + recovery_observations
                        + second_zero_observations
                    )
                )
            ):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "brief zero/recovery channel observations differ",
                )
            first_positions = [
                int(cast(dict[str, Any], item["priorVoice"])["pcmPosition"])
                for item in first_zero_observations
            ]
            recovery_position = int(
                cast(dict[str, Any], recovery_observations[0]["priorVoice"])[
                    "pcmPosition"
                ]
            )
            second_positions = [
                int(cast(dict[str, Any], item["priorVoice"])["pcmPosition"])
                for item in second_zero_observations
            ]
            second_virtual_states = [
                bool(cast(dict[str, Any], item["priorVoice"])["isVirtual"])
                for item in second_zero_observations
            ]
            if recovery_position <= first_positions[-1]:
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "positive return did not cancel a pending phase hold",
                )
            if holds_phase:
                latency_blocks = int(
                    runtime_zero["phaseHoldLatencyWriterFrames"]
                ) // 256
                if (
                    latency_blocks <= 0
                    or len(second_positions) < latency_blocks + 2
                    or any(
                        right <= left
                        for left, right in zip(
                            [recovery_position] + second_positions[:latency_blocks - 1],
                            second_positions[:latency_blocks],
                        )
                    )
                    or any(second_virtual_states[:latency_blocks])
                    or not all(second_virtual_states[latency_blocks:])
                ):
                    _fail(
                        "engineTransientZeroGainVerificationFailed",
                        "second zero did not restart the source-bound hold countdown",
                    )
            else:
                if any(
                    right <= left
                    for left, right in zip(
                        [recovery_position] + second_positions[:-1],
                        second_positions,
                    )
                ):
                    _fail(
                        "engineTransientZeroGainVerificationFailed",
                        "advance-at-zero source stopped advancing after a second zero",
                    )
            metric_by_offset = {
                int(item.get("writerFrameOffsetFromFirstZero", -1)): item
                for item in block_metrics
                if isinstance(item, dict)
            }
            positive_offset = int(
                sequence["firstZeroToPositiveReturnWriterFrames"]
            )
            second_zero_offset = positive_offset + 256
            ordered_metric_offsets = sorted(metric_by_offset)
            terminal_metric_offsets = ordered_metric_offsets[-2:]
            if (
                positive_offset not in metric_by_offset
                or not any(
                    int(item.get("maximumAbsolutePcmLsb", 0)) > 0
                    for offset, item in metric_by_offset.items()
                    if offset >= positive_offset
                )
                or len(terminal_metric_offsets) != 2
                or any(
                    int(metric_by_offset[offset].get("maximumAbsolutePcmLsb", -1))
                    != 0
                    for offset in terminal_metric_offsets
                )
                or any(
                    len(str(item.get("pcmPayloadSha256") or "")) != 64
                    for item in metric_by_offset.values()
                )
            ):
                _fail(
                    "engineTransientZeroGainVerificationFailed",
                    "brief zero/recovery PCM does not prove cancel/restart",
                )
        baseline_worker = require_fresh_worker(
            pcm_comparison.get("baselineFreshProcess"), "zeroResumeRender"
        )
        gap_worker = require_fresh_worker(
            pcm_comparison.get("zeroGapFreshProcess"), "zeroResumeRender"
        )
        pitch_reference = pcm_comparison.get("pitchOnlyTransitionReference")
        if not isinstance(pitch_reference, dict):
            _fail(
                "engineTransientProcessIsolationFailed",
                "zero-transition pitch-only reference is absent",
            )
        pitch_reference_worker = require_fresh_worker(
            pitch_reference.get("freshProcess"), "zeroResumeRender"
        )
        if len(
            {
                int(baseline_worker["workerProcessId"]),
                int(gap_worker["workerProcessId"]),
                int(pitch_reference_worker["workerProcessId"]),
            }
        ) != 3:
            _fail(
                "engineTransientProcessIsolationFailed",
                "zero/resume PCM comparison reused a worker process",
            )
    require_fresh_worker(zero_gain.get("freshProcessEvidence"), "zeroGain")
    dynamic_dsp = evidence.get("dynamicDspVerification")
    unattributed = source.get("unsupported", {}).get("bankWideUnattributedRpmThrottleDspAutomation", [])
    if not isinstance(dynamic_dsp, dict) or dynamic_dsp.get("accepted") is not True:
        _fail("engineTransientDspVerificationFailed", "DSP disposition is absent")
    if int(dynamic_dsp.get("unattributedControllerCount", -1)) != len(unattributed):
        _fail("engineTransientDspVerificationFailed", "DSP controller count differs")
    treatment = str(dynamic_dsp.get("runtimeTreatment") or "")
    allowed_treatments = {
        "NO_UNATTRIBUTED_RUNTIME_DSP_AUTOMATION",
        "TARGET_ONLY_PROVEN_NOT_ROUTED_TO_SOURCE",
        "SOURCE_BOUND_WINDOWED_PCM_VARIANTS",
    }
    if treatment not in allowed_treatments or (
        bool(unattributed) == (treatment == "NO_UNATTRIBUTED_RUNTIME_DSP_AUTOMATION")
    ):
        _fail("engineTransientDspVerificationFailed", "DSP treatment differs from graph")
    if dynamic_dsp.get("targetOnlyComparisonRequired") is True:
        comparison_workers_raw = dynamic_dsp.get("independentFreshProcessRenders")
        if (
            not isinstance(comparison_workers_raw, list)
            or len(comparison_workers_raw) != 4
        ):
            _fail(
                "engineTransientProcessIsolationFailed",
                "four fresh repeatable DSP-comparison workers are required",
            )
        comparison_workers = [
            require_fresh_worker(item, "render")
            for item in comparison_workers_raw
        ]
        if len({int(item["workerProcessId"]) for item in comparison_workers}) != 4:
            _fail(
                "engineTransientProcessIsolationFailed",
                "DSP comparison reused a worker process",
            )
        phase = dynamic_dsp.get("phaseOnlyAlignment")
        bounds = dynamic_dsp.get("acceptanceBounds")
        if (
            not isinstance(phase, dict)
            or not isinstance(bounds, dict)
            or dynamic_dsp.get("independentTargetRepeatBitExact") is not True
            or dynamic_dsp.get("independentEquivalentRepeatBitExact") is not True
            or phase.get("method")
            != "ONE_STEREO_FOURIER_FRACTIONAL_FRAME_PHASE_SHIFT_ONLY"
            or phase.get("phaseOffsetFrameDomain")
            != "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
            or phase.get("gainOrSpectralCorrectionFitted") is not False
            or float(phase.get("maximumAbsolutePhaseSearchFrames", -1.0)) != 4.0
            or float(phase.get("gainErrorDb", float("inf")))
            > float(bounds.get("maximumGainErrorDb", -1.0))
            or float(phase.get("differenceBelowReferenceDb", float("-inf")))
            < float(bounds.get("minimumDifferenceBelowReferenceDb", float("inf")))
            or float(phase.get("normalizedCorrelation", float("-inf")))
            < float(bounds.get("minimumNormalizedCorrelation", float("inf")))
            or float(
                phase.get("earlyLateEstimateDifferenceFrames", float("inf"))
            )
            > float(
                bounds.get(
                    "maximumEarlyLatePhaseEstimateDifferenceFrames", -1.0
                )
            )
        ):
            _fail(
                "engineTransientDspVerificationFailed",
                "phase-only DSP-routing comparison exceeds its bounds",
            )

    evidence_hash = str(evidence.get("verificationPayloadSha256") or "")
    unhashed = copy.deepcopy(evidence)
    unhashed.pop("verificationPayloadSha256", None)
    if evidence_hash != _canonical_json_sha256(unhashed):
        _fail("engineTransientVerificationHashMismatch", "verification hash differs")
    source["rootRpm"] = source["captureRootRpm"] if runtime_varispeed else None
    source["pitchTreatment"] = {
        **source["pitchTreatment"],
        "runtimeVarispeed": runtime_varispeed,
        "rootRpm": source["captureRootRpm"] if runtime_varispeed else None,
        "scale": "currentPresentationEngineRpm/rootRpm" if runtime_varispeed else "1.0;authoredStaticPitchBakedInPcm",
        "updatesContinuouslyWhileVoiceIsActive": runtime_varispeed,
        "sourceBoundPitchVerification": copy.deepcopy(pitch),
        "zeroGainVirtualization": {
            "runtimeSemantic": copy.deepcopy(runtime_zero),
            "reentryPolicy": zero_gain["reentryPolicy"],
            "sourceVerificationPayloadSha256": evidence_hash,
        },
    }
    source["voicePolicy"] = {**source["voicePolicy"], "softwareChannelPriority": priority, "priorityRequiredFromSourceBoundOracle": False}
    source["unsupported"] = {
        **source["unsupported"],
        "bankWideUnattributedRpmThrottleDspAutomation": [],
        "sourceBoundDynamicDspDisposition": copy.deepcopy(dynamic_dsp),
    }
    source["fidelity"] = {
        **fidelity,
        "exactnessClaim": True,
        "exactWithinReportedOracleBounds": True,
        "requiredFinalGate": "satisfiedBySourceBoundEngineTransientVerification",
        "sourceVerificationPayloadSha256": evidence_hash,
    }
    source["verifiedTargetPcm"] = {
        "frameCount": frame_count,
        "pcmPayloadSha256": pcm_hash,
        "peakPcmDbfs": peak_dbfs,
        "playbackStartFrame": playback_start,
        "playbackEndFrameExclusive": playback_end,
        "terminationTimingErrorBoundFrames": termination_error,
        "dspClockAlignmentErrorBoundFrames": alignment_error,
        "audibilityDisposition": disposition,
        "allPcmSamplesZero": all_zero,
        "finalWavRelativePath": relative_path,
        "finalWavSha256": wav_hash,
        "timelineAuthoredSilentPrefixPreserved": True,
    }
    source["sourceVerification"] = evidence
    return source


def _turbo_event_for_source(
    report: dict[str, Any], source_guid: str, classification: dict[str, Any]
) -> dict[str, Any]:
    declared_paths = classification.get("eventPaths")
    if not isinstance(declared_paths, list):
        _fail("invalidClassification", "turbo source row has no eventPaths array")
    declared = {str(item) for item in declared_paths}
    events = report.get("events")
    if not isinstance(events, list):
        _fail("invalidGraph", "events must be an array")
    matches: list[dict[str, Any]] = []
    for event in events:
        if not isinstance(event, dict):
            _fail("invalidGraph", "event must be an object")
        reachable = event.get("reachableInstrumentGuids")
        if not isinstance(reachable, list):
            _fail("invalidGraph", "event reachableInstrumentGuids must be an array")
        if source_guid in {_guid(item) for item in reachable}:
            matches.append(event)
    matches = [
        event for event in matches if _event_suffix(event.get("path")) == _TURBO_SUFFIX
    ]
    if len(matches) != 1:
        _fail(
            "unsupportedTurboEventTopology",
            f"source is reachable from {len(matches)} turbo events",
        )
    event = matches[0]
    if str(event.get("path")) not in declared or event.get("mappingComplete") is not True:
        _fail("invalidClassification", "classification/turbo event mapping disagrees")
    return event


def _turbo_curve_knots(
    domain: tuple[float, float], controllers: list[dict[str, Any]]
) -> list[float]:
    values = {float(domain[0]), float(domain[1])}
    for controller in controllers:
        for x, _y, _shape, _point_type in _curve_points(controller):
            if domain[0] <= x <= domain[1]:
                values.add(float(x))
    return sorted(values)


def derive_manifest_turbo_transient_source(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Compile one filename-independent turbo-event one-shot leaf.

    The catalog's ``turbo`` event is not one homogeneous BOV trigger.  This
    function distinguishes repeating timeline one-shots, falling-boost region
    entries, and the three parameter-sheet leaves whose region spans the whole
    boost domain and therefore fire only when the persistent event starts.
    Property 0 is dB volume, property 4 is a linear parameter-sheet fade, and
    property 1 is pitch serialized in units of 1/24 semitone.  Only a paired
    source-bound target oracle may upgrade the returned exactness claim.
    """

    if graph_report.get("schema") != BANK_GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {BANK_GRAPH_SCHEMA}")
    if not isinstance(source_classification, dict):
        _fail("invalidClassification", "source classification must be an object")
    if source_classification.get("policy") != _ALLOWED_POLICY:
        _fail("sourceNotAllowed", "source classifier did not allow this source")
    if source_classification.get("role") != _TURBO_TRANSIENT_CLASSIFIER_ROLE:
        _fail(
            "invalidClassification",
            f"expected classifier role {_TURBO_TRANSIENT_CLASSIFIER_ROLE}",
        )
    if source_classification.get("lifetime") != "oneShot":
        _fail("unsupportedLifetime", "turbo transient must be a oneShot source")
    source_guid = _guid(source_classification.get("sourceGuid"))
    if not source_guid:
        _fail("invalidClassification", "source row has no GUID")
    tolerance = _finite(
        interpolation_tolerance,
        code="invalidTolerance",
        detail="interpolation tolerance must be finite",
    )
    if tolerance <= 0.0 or tolerance > 0.01:
        _fail("invalidTolerance", "interpolation tolerance must be in (0, 0.01]")
    overrides = {
        str(name).casefold(): _finite(
            value,
            code="invalidCaptureControl",
            detail=f"capture control {name} is invalid",
        )
        for name, value in (capture_controls or {}).items()
    }
    unknown_override_names = sorted(set(overrides) - _TURBO_RUNTIME_PARAMETERS)
    if unknown_override_names:
        _fail(
            "invalidCaptureControl",
            f"unknown turbo controls: {', '.join(unknown_override_names)}",
        )

    instruments = _objects_by_guid(graph_report, "instruments")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "classified GUID is not a waveform instrument")
    source_properties = source.get("baseProperties")
    if not isinstance(source_properties, dict):
        _fail("unsupportedOwnerTopology", "turbo source has no base properties")
    try:
        source_loop_count = int(source_properties.get("loopCount"))
    except (TypeError, ValueError):
        _fail("unsupportedLifetime", "turbo source loop count is invalid")
    if source_loop_count != 0:
        _fail(
            "unsupportedLifetime",
            f"turbo transient source loopCount is {source_loop_count}, expected 0",
        )

    chain = _instrument_chain(source_guid, instruments)
    chain_guids = {guid for guid, _node, _depth in chain}
    owner_scope: dict[str, tuple[str, int]] = {}
    base_gain_values: list[dict[str, Any]] = []
    base_pitch_values: list[dict[str, Any]] = []
    source_geometry_nodes: list[dict[str, Any]] = []
    referenced: set[str] = set()
    for guid, node, depth in chain:
        properties = node.get("baseProperties")
        if not isinstance(properties, dict):
            _fail("unsupportedOwnerTopology", f"instrument {guid} has no base properties")
        routable = _guid(properties.get("routableGuid"))
        if not routable:
            _fail("unsupportedOwnerTopology", f"instrument {guid} has no routable GUID")
        for owner in (guid, routable):
            previous = owner_scope.get(owner)
            if previous is not None and previous[0] != guid:
                _fail("unsupportedOwnerTopology", f"owner GUID {owner} is shared")
            owner_scope[owner] = (guid, depth)
        raw_controller_guids = node.get("controllerGuids")
        if not isinstance(raw_controller_guids, list):
            _fail("invalidGraph", f"instrument {guid} controllerGuids is invalid")
        referenced.update(_guid(item) for item in raw_controller_guids)
        gain_db = _finite(
            properties.get("volumeDb", 0.0),
            code="unsupportedBaseGain",
            detail=f"instrument {guid} base volume is invalid",
        )
        pitch_semitones = _finite(
            properties.get("pitchSemitones", 0.0),
            code="unsupportedBasePitch",
            detail=f"instrument {guid} base pitch is invalid",
        )
        chance = _finite(
            properties.get("triggerChancePercent", 100.0),
            code="unsupportedTriggerChance",
            detail=f"instrument {guid} trigger chance is invalid",
        )
        if chance < 0.0 or chance > 100.0:
            _fail("unsupportedTriggerChance", f"instrument {guid} chance is {chance}")
        base_gain_values.append(
            {"instrumentGuid": guid, "ancestorDepth": depth, "rawValueDb": gain_db}
        )
        base_pitch_values.append(
            {
                "instrumentGuid": guid,
                "ancestorDepth": depth,
                "rawValueSemitones": pitch_semitones,
            }
        )
        source_geometry_nodes.append(
            {
                "instrumentGuid": guid,
                "ancestorDepth": depth,
                "kind": str(node.get("kind") or ""),
                "loopCount": int(properties.get("loopCount", 0)),
                "initialSeekPercent": _finite(
                    properties.get("initialSeekPercent", 0.0),
                    code="unsupportedInitialSeek",
                    detail=f"instrument {guid} initial seek is invalid",
                ),
                "initialSeekPosition": _finite(
                    properties.get("initialSeekPosition", 0.0),
                    code="unsupportedInitialSeek",
                    detail=f"instrument {guid} initial seek position is invalid",
                ),
                "triggerChancePercent": chance,
            }
        )

    all_controllers = _objects_by_guid(graph_report, "controllers")
    if referenced - all_controllers.keys():
        _fail(
            "missingController",
            f"missing controllers: {sorted(referenced - all_controllers.keys())}",
        )
    owned = {
        guid
        for guid, controller in all_controllers.items()
        if _guid(controller.get("propertyOwnerGuid")) in owner_scope
    }
    if owned != referenced:
        _fail(
            "unsupportedControllerOwnership",
            "owned/referenced controller mismatch: "
            f"ownedOnly={sorted(owned-referenced)}, "
            f"referencedOnly={sorted(referenced-owned)}",
        )

    event = _turbo_event_for_source(graph_report, source_guid, source_classification)
    parameters = _objects_by_guid(graph_report, "parameters")
    timeline_placements = [
        item
        for item in event.get("timelinePlacements", [])
        if isinstance(item, dict)
        and _guid(item.get("instrumentGuid")) in chain_guids
    ]
    parameter_placements = [
        item
        for item in event.get("parameterPlacements", [])
        if isinstance(item, dict)
        and _guid(item.get("instrumentGuid")) in chain_guids
    ]
    if len(timeline_placements) + len(parameter_placements) != 1:
        _fail(
            "unsupportedTurboPlacementTopology",
            "turbo source chain must have exactly one timeline or parameter placement",
        )
    placement = (timeline_placements or parameter_placements)[0]
    placement_root = _guid(placement.get("instrumentGuid"))
    outermost_root = chain[-1][0]
    if placement_root != outermost_root:
        _fail(
            "unsupportedTurboProgramTopology",
            "turbo placement is not on the source chain's outermost program root",
        )

    parameter_guids_by_name: dict[str, set[str]] = defaultdict(set)
    controllers_by_parameter: dict[str, list[dict[str, Any]]] = defaultdict(list)
    gain_controllers_by_parameter: dict[str, list[dict[str, Any]]] = defaultdict(list)
    pitch_controllers_by_parameter: dict[str, list[dict[str, Any]]] = defaultdict(list)
    controller_points: dict[str, tuple[tuple[float, float, float, int], ...]] = {}
    controller_evidence: list[dict[str, Any]] = []
    for controller_guid in sorted(referenced):
        controller = all_controllers[controller_guid]
        owner = _guid(controller.get("propertyOwnerGuid"))
        if owner not in owner_scope:
            _fail(
                "unsupportedControllerOwnership",
                f"controller {controller_guid} owner does not belong to source",
            )
        if str(controller.get("inputKind") or "").casefold() != "parameter":
            _fail(
                "unsupportedControllerInput",
                f"turbo controller {controller_guid} is not parameter-driven",
            )
        parameter_name = str(controller.get("inputParameterName") or "").casefold()
        if parameter_name not in _TURBO_RUNTIME_PARAMETERS:
            _fail(
                "unsupportedControllerParameter",
                f"turbo controller uses {parameter_name or 'unnamed'}",
            )
        parameter_guid = _guid(controller.get("inputParameterGuid"))
        parameter = parameters.get(parameter_guid)
        if (
            parameter is None
            or str(parameter.get("name") or "").casefold() != parameter_name
        ):
            _fail(
                "unsupportedParameterTopology",
                f"controller {controller_guid} parameter identity disagrees",
            )
        parameter_guids_by_name[parameter_name].add(parameter_guid)
        try:
            property_index = int(controller.get("propertyIndex"))
        except (TypeError, ValueError):
            _fail(
                "unsupportedPropertyIndex",
                f"controller {controller_guid} property index is invalid",
            )
        if property_index not in {0, 1, 4}:
            _fail(
                "unsupportedPropertyIndex",
                f"turbo controller {controller_guid} uses property {property_index}",
            )
        if property_index == 1 and parameter_name != "boost":
            _fail(
                "unsupportedControllerParameter",
                "turbo property-1 pitch is supported only on boost",
            )
        points = _curve_points(controller)
        if property_index == 4 and any(
            raw_value < -1.0e-5 or raw_value > 1.00001
            for _x, raw_value, _shape, _point_type in points
        ):
            _fail(
                "unsupportedFadeRange",
                f"turbo controller {controller_guid} fade points are outside 0..1",
            )
        controller_points[controller_guid] = points
        controllers_by_parameter[parameter_name].append(controller)
        if property_index in {0, 4}:
            gain_controllers_by_parameter[parameter_name].append(controller)
            treatment = (
                "normalizedLinearGainCurve"
                if property_index == 4
                else "normalizedDbVolumeCurve"
            )
        else:
            pitch_controllers_by_parameter[parameter_name].append(controller)
            treatment = "rawValueTimes24SemitonesThenLiveVarispeed"
        scope_guid, depth = owner_scope[owner]
        controller_evidence.append(
            {
                "controllerGuid": controller_guid,
                "ownerInstrumentGuid": scope_guid,
                "ancestorDepth": depth,
                "parameter": parameter_name,
                "propertyIndex": property_index,
                "runtimeTreatment": treatment,
                "points": [
                    {
                        "x": x,
                        "rawValue": raw_value,
                        "shape": shape,
                        "type": point_type,
                    }
                    for x, raw_value, shape, point_type in points
                ],
            }
        )

    placement_parameter: dict[str, Any] | None = None
    if parameter_placements:
        parameter_name = str(placement.get("parameterName") or "").casefold()
        if parameter_name != "boost":
            _fail(
                "unsupportedTurboPlacementTopology",
                f"turbo parameter placement uses {parameter_name or 'unnamed'}",
            )
        parameter_guid = _guid(placement.get("parameterGuid"))
        parameter = parameters.get(parameter_guid)
        if parameter is None or str(parameter.get("name") or "").casefold() != "boost":
            _fail("unsupportedParameterTopology", "boost placement identity disagrees")
        parameter_guids_by_name["boost"].add(parameter_guid)
        domain_minimum = _finite(
            parameter.get("minimum"),
            code="unsupportedParameterTopology",
            detail="boost minimum is invalid",
        )
        domain_maximum = _finite(
            parameter.get("maximum"),
            code="unsupportedParameterTopology",
            detail="boost maximum is invalid",
        )
        start = _finite(
            placement.get("start"),
            code="unsupportedTriggerPlacement",
            detail="boost placement start is invalid",
        )
        authored_end = _finite(
            placement.get("end"),
            code="unsupportedTriggerPlacement",
            detail="boost placement end is invalid",
        )
        endpoint_slack = max(1.0e-5, abs(domain_maximum - domain_minimum) * 1.0e-5)
        if (
            domain_maximum <= domain_minimum
            or abs(start - domain_minimum) > endpoint_slack
            or authored_end < start
            or authored_end > domain_maximum + endpoint_slack
        ):
            _fail(
                "unsupportedTriggerPlacement",
                f"boost placement {start}..{authored_end} is incompatible with "
                f"domain {domain_minimum}..{domain_maximum}",
            )
        effective_end = min(authored_end, domain_maximum)
        full_domain = effective_end >= domain_maximum - endpoint_slack
        placement_parameter = {
            "parameter": "boost",
            "parameterGuid": parameter_guid,
            "parameterDomain": [domain_minimum, domain_maximum],
            "minimum": start,
            "maximum": effective_end,
            "authoredMaximum": authored_end,
            "includeMinimum": True,
            "includeMaximum": placement.get("includeEnd") is True,
            "seekSpeed": _finite(
                parameter.get("seekSpeed", 0.0),
                code="unsupportedParameterTopology",
                detail="boost seek speed is invalid",
            ),
            "seekSpeedDown": _finite(
                parameter.get("seekSpeedDown", 0.0),
                code="unsupportedParameterTopology",
                detail="boost seek-down speed is invalid",
            ),
            "coversEntireParameterDomain": full_domain,
        }
        if full_domain:
            program_mode = "PARAMETER_SHEET_EVENT_START_ONE_SHOT"
            resolved_role = "TURBO_TRANSIENT"
        else:
            program_mode = "BOOST_RELEASE_REGION_ONE_SHOT"
            resolved_role = "BOV"
    else:
        try:
            timeline_start = int(placement.get("startTime"))
            timeline_length = int(placement.get("length"))
        except (TypeError, ValueError):
            _fail("unsupportedTimelinePlacement", "turbo timeline placement is invalid")
        if timeline_start < 0 or timeline_length <= 0 or placement.get("timeLocked") is not True:
            _fail(
                "unsupportedTimelinePlacement",
                "turbo timeline placement must be positive and time-locked",
            )
        program_mode = "TIMELINE_PERIODIC_ONE_SHOT"
        resolved_role = "TURBO_TRANSIENT"

    domains: dict[str, tuple[float, float]] = {}
    parameter_identity: dict[str, dict[str, Any]] = {}
    for parameter_name, guids in sorted(parameter_guids_by_name.items()):
        guids.discard("")
        if len(guids) != 1:
            _fail(
                "unsupportedParameterTopology",
                f"turbo source uses {len(guids)} {parameter_name} parameter GUIDs",
            )
        parameter_guid = next(iter(guids))
        parameter = parameters[parameter_guid]
        minimum = _finite(
            parameter.get("minimum"),
            code="unsupportedParameterTopology",
            detail=f"{parameter_name} minimum is invalid",
        )
        maximum = _finite(
            parameter.get("maximum"),
            code="unsupportedParameterTopology",
            detail=f"{parameter_name} maximum is invalid",
        )
        if maximum <= minimum:
            _fail("unsupportedParameterTopology", f"{parameter_name} domain is empty")
        domains[parameter_name] = (minimum, maximum)
        parameter_identity[parameter_name] = {
            "parameterGuid": parameter_guid,
            "domain": [minimum, maximum],
            "defaultValue": _finite(
                parameter.get("defaultValue", minimum),
                code="unsupportedParameterTopology",
                detail=f"{parameter_name} default is invalid",
            ),
            "seekSpeed": _finite(
                parameter.get("seekSpeed", 0.0),
                code="unsupportedParameterTopology",
                detail=f"{parameter_name} seek speed is invalid",
            ),
            "seekSpeedDown": _finite(
                parameter.get("seekSpeedDown", 0.0),
                code="unsupportedParameterTopology",
                detail=f"{parameter_name} seek-down speed is invalid",
            ),
        }
    if "boost" not in domains:
        _fail("unsupportedParameterTopology", "turbo source has no boost domain")
    unknown_override_names = sorted(set(overrides) - set(domains))
    if unknown_override_names:
        _fail(
            "invalidCaptureControl",
            f"capture controls are not used by source: {', '.join(unknown_override_names)}",
        )

    def gain_function(parameter_name: str) -> Callable[[float], float]:
        compiled = [
            (
                _guid(controller.get("guid")),
                int(controller.get("propertyIndex")),
                controller_points[_guid(controller.get("guid"))],
            )
            for controller in gain_controllers_by_parameter.get(parameter_name, [])
        ]

        def evaluate(value: float) -> float:
            amplitude = 1.0
            for guid, property_index, points in compiled:
                amplitude *= _controller_amplitude_from_points(
                    guid, property_index, points, value
                )
            return amplitude

        return evaluate

    def pitch_semitone_function(parameter_name: str) -> Callable[[float], float]:
        compiled = [
            controller_points[_guid(controller.get("guid"))]
            for controller in pitch_controllers_by_parameter.get(parameter_name, [])
        ]

        def evaluate(value: float) -> float:
            return sum(
                evaluate_authored_curve(points, value)
                * _PROPERTY_ONE_RAW_TO_SEMITONES
                for points in compiled
            )

        return evaluate

    capture_values: dict[str, float] = {}
    capture_axis_amplitudes: dict[str, float] = {}
    control_gain_curves: dict[str, list[list[float]]] = {}
    gain_errors: dict[str, float] = {}
    statically_silent_axes: list[str] = []
    timeline_capture_constraint: dict[str, Any] | None = None
    timeline_unpitched_output_frames: float | None = None
    authored_base_pitch_semitones = sum(
        float(item["rawValueSemitones"]) for item in base_pitch_values
    )
    property_one_overrides_base_pitch = any(
        pitch_controllers_by_parameter.values()
    )
    # FMOD 1.08 property automation writes the instrument pitch property; it
    # does not add to the authored static property.  Four shipped timeline
    # turbo leaves retain a +2.44 static value alongside property-1 automation.
    # Their source PCM position advances at the controller value alone.
    timeline_base_pitch_semitones = (
        0.0
        if property_one_overrides_base_pitch
        else authored_base_pitch_semitones
    )
    if program_mode == "TIMELINE_PERIODIC_ONE_SHOT":
        sample_for_duration = source.get("sample")
        if not isinstance(sample_for_duration, dict):
            _fail("invalidGraph", "timeline turbo source has no sample evidence")
        try:
            native_sample_count = int(sample_for_duration.get("sampleCount"))
            native_frequency_hz = int(sample_for_duration.get("frequencyHz"))
        except (TypeError, ValueError):
            _fail("invalidGraph", "timeline turbo sample duration is invalid")
        if native_sample_count <= 0 or native_frequency_hz <= 0:
            _fail("invalidGraph", "timeline turbo sample duration is non-positive")
        if any(
            abs(float(item["initialSeekPercent"])) > 1.0e-9
            or abs(float(item["initialSeekPosition"])) > 1.0e-9
            for item in source_geometry_nodes
        ):
            _fail(
                "unsupportedInitialSeek",
                "timeline turbo capture isolation requires zero authored initial seek",
            )
        timeline_unpitched_output_frames = (
            float(native_sample_count)
            * float(_FMOD_BANK_TIMELINE_TICKS_PER_SECOND)
            / float(native_frequency_hz)
        )
    for parameter_name, domain in sorted(domains.items()):
        function = gain_function(parameter_name)
        capture_interval = domain
        if parameter_name == "boost" and placement_parameter is not None:
            capture_interval = (
                float(placement_parameter["minimum"]),
                float(placement_parameter["maximum"]),
            )
        controller_knots = _turbo_curve_knots(
            domain, controllers_by_parameter.get(parameter_name, [])
        )
        explicit = overrides.get(parameter_name)
        if explicit is not None and (
            explicit < capture_interval[0] or explicit > capture_interval[1]
        ):
            _fail(
                "invalidCaptureControl",
                f"capture {parameter_name}={explicit} is outside {capture_interval}",
            )
        try:
            if (
                parameter_name == "boost"
                and timeline_unpitched_output_frames is not None
                and pitch_controllers_by_parameter.get(parameter_name)
            ):
                pitch_function = pitch_semitone_function(parameter_name)

                def absolute_playback_rate(value: float) -> float:
                    return 2.0 ** (
                        (
                            timeline_base_pitch_semitones
                            + pitch_function(value)
                        )
                        / 12.0
                    )

                if explicit is not None:
                    feasible_values = [float(explicit)]
                else:
                    # FMOD's shaped point curves are continuous between the
                    # parsed authored knots.  A deterministic 1/65536-domain
                    # search gives a strict upper bound of one grid interval
                    # on the chosen operating point; authored knots are added
                    # verbatim so a gain maximum is never skipped merely due
                    # to sampling phase.
                    minimum, maximum = capture_interval
                    span = maximum - minimum
                    feasible_values = [
                        minimum + span * index / 65536.0
                        for index in range(65537)
                    ]
                    feasible_values.extend(
                        value
                        for value in controller_knots
                        if minimum <= value <= maximum
                    )
                feasible_values = sorted(
                    {
                        float(value)
                        for value in feasible_values
                        if function(float(value)) > 0.0
                    }
                )
                if not feasible_values:
                    _fail(
                        "silentSource",
                        "timeline turbo has no audible pitch-reference operating point",
                    )
                maximum_capture_cycle_frames = max(
                    1,
                    timeline_length
                    - _TURBO_TIMELINE_CAPTURE_LOOKAHEAD_FRAMES,
                )
                safe_values = [
                    value
                    for value in feasible_values
                    if timeline_unpitched_output_frames
                    / absolute_playback_rate(value)
                    <= maximum_capture_cycle_frames
                ]
                if not safe_values:
                    _fail(
                        "unsupportedTimelineCaptureIsolation",
                        "timeline turbo has no audible pitch point with the required source-cycle look-ahead",
                    )
                preferred = parameter_identity[parameter_name]["defaultValue"]
                capture_value = min(
                    safe_values,
                    key=lambda value: (
                        abs(math.log2(absolute_playback_rate(value))),
                        -function(value),
                        abs(value - preferred),
                        value,
                    ),
                )
                amplitude = function(capture_value)
                if amplitude <= 0.0:
                    _fail(
                        "silentSource",
                        "timeline-safe capture interval is authored silent",
                    )
                selected_rate = absolute_playback_rate(capture_value)
                predicted_frames = timeline_unpitched_output_frames / selected_rate
                timeline_capture_constraint = {
                    "strategy": "TARGET_ROUTED_SOURCE_CYCLE_AT_NEAREST_UNITY_PITCH",
                    "selectedPlaybackRate": round(selected_rate, 10),
                    "predictedRenderedSourceCycleFrames": round(
                        predicted_frames, 8
                    ),
                    "programTimelinePeriodFrames": timeline_length,
                    "sourcePlaybackMode": (
                        "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT"
                    ),
                    "minimumRenderedLookAheadFrames": (
                        _TURBO_TIMELINE_CAPTURE_LOOKAHEAD_FRAMES
                    ),
                    "captureSearchMaximumBoostError": round(
                        (capture_interval[1] - capture_interval[0]) / 65536.0,
                        12,
                    ),
                }
            else:
                capture_value, amplitude = _select_capture_value(
                    function,
                    capture_interval,
                    parameter_identity[parameter_name]["defaultValue"],
                    explicit,
                    controller_knots,
                )
        except FmodAuthoredCurveError as error:
            if error.code != "silentSource":
                raise
            # One shipped Supra full-domain leaf has a source-bound property-0
            # curve fixed at FMOD's -infinity sentinel.  Retain it as an
            # explicit fail-closed silent candidate; only target PCM evidence
            # may certify that it is intentionally silent.
            preferred = (
                explicit
                if explicit is not None
                else parameter_identity[parameter_name]["defaultValue"]
            )
            capture_value = min(
                capture_interval[1], max(capture_interval[0], preferred)
            )
            amplitude = 0.0
            statically_silent_axes.append(parameter_name)
        capture_values[parameter_name] = capture_value
        capture_axis_amplitudes[parameter_name] = amplitude
        if amplitude > 0.0:
            # Timeline capture is selected for a bounded, fully observable
            # source cycle (and live property-1 pitch), not necessarily at the
            # property-0 gain maximum.  Ratios above the capture gain are
            # authored and must remain above unity; clipping here loses level
            # motion even though the target PCM and safety normalization are
            # handled later by the pack compiler.
            def relative_gain(value: float) -> float:
                evaluated = function(value)
                if not math.isfinite(evaluated):
                    _fail(
                        "curveEvaluationFailed",
                        f"non-finite turbo amplitude at {value}",
                    )
                return max(0.0, evaluated / amplitude)

            sampled, error = _adaptive_sample(
                relative_gain,
                controller_knots,
                tolerance,
            )
        else:
            sampled = [[domain[0], 0.0], [domain[1], 0.0]]
            error = 0.0
        control_gain_curves[parameter_name] = [
            [round(float(x), 8), round(float(y), 10)] for x, y in sampled
        ]
        gain_errors[parameter_name] = error

    if (
        program_mode == "TIMELINE_PERIODIC_ONE_SHOT"
        and timeline_capture_constraint is None
    ):
        if timeline_unpitched_output_frames is None:
            raise AssertionError("timeline sample duration was not initialized")
        selected_rate = 2.0 ** (timeline_base_pitch_semitones / 12.0)
        predicted_frames = timeline_unpitched_output_frames / selected_rate
        if (
            timeline_length - predicted_frames
            < _TURBO_TIMELINE_CAPTURE_LOOKAHEAD_FRAMES
        ):
            _fail(
                "unsupportedTimelineCaptureIsolation",
                "static-pitch timeline turbo lacks the required source-cycle look-ahead",
            )
        timeline_capture_constraint = {
            "strategy": "TARGET_ROUTED_SOURCE_CYCLE_AT_AUTHORED_STATIC_PITCH",
            "selectedPlaybackRate": round(selected_rate, 10),
            "predictedRenderedSourceCycleFrames": round(
                predicted_frames, 8
            ),
            "programTimelinePeriodFrames": timeline_length,
            "sourcePlaybackMode": (
                "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT"
            ),
            "minimumRenderedLookAheadFrames": (
                _TURBO_TIMELINE_CAPTURE_LOOKAHEAD_FRAMES
            ),
            "captureSearchMaximumBoostError": 0.0,
        }

    pitch_automation: list[dict[str, Any]] = []
    pitch_errors: dict[str, float] = {}
    for parameter_name, pitch_controllers in sorted(
        pitch_controllers_by_parameter.items()
    ):
        domain = domains[parameter_name]
        function = pitch_semitone_function(parameter_name)
        capture_semitones = function(capture_values[parameter_name])

        def playback_rate(value: float) -> float:
            return 2.0 ** ((function(value) - capture_semitones) / 12.0)

        knots = _turbo_curve_knots(domain, pitch_controllers)
        sampled, error = _adaptive_sample(playback_rate, knots, tolerance)
        pitch_errors[parameter_name] = error
        pitch_automation.append(
            {
                "parameter": parameter_name,
                "propertyIndex": 1,
                "rawValueToSemitonesScale": _PROPERTY_ONE_RAW_TO_SEMITONES,
                "captureSemitones": round(capture_semitones, 8),
                "playbackRateCurve": [
                    [round(float(x), 8), round(float(y), 10)] for x, y in sampled
                ],
                "runtimeTreatment": "multiplyActiveVoiceRateContinuously",
                "updatesWhileVoiceActive": True,
                "continuesOutsideSchedulingRegion": True,
                "captureRate": 1.0,
            }
        )

    selection_path: list[dict[str, Any]] = []
    for index in range(1, len(chain)):
        child_guid = chain[index - 1][0]
        parent_guid, parent, _depth = chain[index]
        raw_children = parent.get("childInstruments")
        if not isinstance(raw_children, list):
            _fail("unsupportedSelectionTopology", "parent child list is invalid")
        children: list[dict[str, Any]] = []
        for raw_child in raw_children:
            if not isinstance(raw_child, dict):
                _fail("unsupportedSelectionTopology", "weighted child is invalid")
            child = _guid(raw_child.get("guid"))
            weight = _finite(
                raw_child.get("weight"),
                code="unsupportedSelectionTopology",
                detail=f"parent {parent_guid} child weight is invalid",
            )
            if not child or weight < 0.0:
                _fail("unsupportedSelectionTopology", "child GUID/weight is invalid")
            children.append({"instrumentGuid": child, "weight": weight})
        if sum(item["instrumentGuid"] == child_guid for item in children) != 1:
            _fail(
                "unsupportedSelectionTopology",
                f"parent {parent_guid} does not contain child {child_guid} exactly once",
            )
        playlist = parent.get("playlist")
        if not isinstance(playlist, dict):
            _fail(
                "unsupportedSelectionTopology",
                f"multi-instrument {parent_guid} has no playlist policy",
            )
        selection_path.append(
            {
                "parentInstrumentGuid": parent_guid,
                "selectedChildInstrumentGuid": child_guid,
                "playlist": {
                    "playMode": playlist.get("playMode"),
                    "playModeValue": playlist.get("playModeValue"),
                    "selectionMode": playlist.get("selectionMode"),
                    "selectionModeValue": playlist.get("selectionModeValue"),
                },
                "orderedChildren": children,
            }
        )

    sample = source.get("sample")
    if not isinstance(sample, dict):
        _fail("invalidGraph", "turbo waveform source has no sample evidence")
    sample_technical_evidence = {
        key: sample.get(key)
        for key in (
            "channels",
            "frequencyHz",
            "sampleCount",
            "encodedPayloadBytes",
            "encodedPayloadSha256",
            "waveformResourceGuid",
            "soundBankIndex",
            "subsoundIndex",
        )
    }

    total_capture_amplitude = math.prod(capture_axis_amplitudes.values())
    static_audibility_disposition = (
        "AUTHORED_CURVE_SILENT_ALL_DOMAIN"
        if statically_silent_axes
        else "AUDIBLE_CAPTURE_OPERATING_POINT"
    )
    capture_values = {
        name: round(float(value), 8) for name, value in sorted(capture_values.items())
    }
    if program_mode == "TIMELINE_PERIODIC_ONE_SHOT":
        timeline_geometry = {
            "startTick": timeline_start,
            "lengthTicks": timeline_length,
            "ticksPerSecond": _FMOD_BANK_TIMELINE_TICKS_PER_SECOND,
            "timeLocked": True,
            "repeatMode": "PERSISTENT_EVENT_TIMELINE_LOOP",
            "repeatPeriodTicks": timeline_length,
        }
        parameter_region = None
        trigger_template = {
            "trigger": "EVENT_TIMELINE_PERIODIC",
            "startTick": timeline_start,
            "periodTicks": timeline_length,
            "ticksPerSecond": _FMOD_BANK_TIMELINE_TICKS_PER_SECOND,
            "overlapMode": "ALLOW_OVERLAP",
            "exitBehavior": "NOT_APPLICABLE",
        }
        placement_signature = {
            "kind": "timeline",
            "instrumentGuid": placement_root,
            "startTick": timeline_start,
            "lengthTicks": timeline_length,
            "timeLocked": True,
        }
    else:
        timeline_geometry = None
        parameter_region = placement_parameter
        if program_mode == "BOOST_RELEASE_REGION_ONE_SHOT":
            trigger_template = {
                "trigger": "EVENT_START_ARMED_PARAMETER_REGION_REENTRY",
                "parameter": "boost",
                "minimum": placement_parameter["minimum"],
                "maximum": placement_parameter["maximum"],
                "includeMinimum": True,
                "includeMaximum": placement_parameter["includeMaximum"],
                "entryEdges": [
                    {
                        "boundary": "MAXIMUM",
                        "direction": "DECREASING",
                        "value": placement_parameter["maximum"],
                        "includeBoundary": placement_parameter["includeMaximum"],
                    }
                ],
                "armingMode": "ARMED_WHEN_EVENT_STARTS_INSIDE_OR_OUTSIDE",
                "initiallyOutsideBehavior": "SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY",
                "rearmMode": "AFTER_ANY_GATE_EXIT",
                "overlapMode": "ALLOW_OVERLAP",
                "exitBehavior": "LET_ACTIVE_VOICES_FINISH",
            }
        else:
            trigger_template = {
                "trigger": "EVENT_START",
                "parameter": "boost",
                "parameterRegionCoversEntireDomain": True,
                "rearmMode": "NONE_WITHOUT_EVENT_RESTART",
                "overlapMode": "ONE_VOICE_PER_EVENT_START",
                "exitBehavior": "LET_ACTIVE_VOICE_FINISH",
            }
        placement_signature = {
            "kind": "parameter",
            "instrumentGuid": placement_root,
            "parameter": "boost",
            "parameterGuid": placement_parameter["parameterGuid"],
            "minimum": placement_parameter["minimum"],
            "maximum": placement_parameter["maximum"],
            "authoredMaximum": placement_parameter["authoredMaximum"],
            "includeMaximum": placement_parameter["includeMaximum"],
        }

    modulators = graph_report.get("modulators", [])
    if not isinstance(modulators, list):
        _fail("invalidGraph", "modulators must be an array")
    source_modulators = [
        {
            "guid": _guid(item.get("guid")),
            "ownerGuid": _guid(item.get("ownerGuid")),
            "type": item.get("type"),
            "propertyIndex": item.get("propertyIndex"),
        }
        for item in modulators
        if isinstance(item, dict) and _guid(item.get("ownerGuid")) in owner_scope
    ]
    effects = _objects_by_guid(graph_report, "effects", required=False)
    unattributed_dsp_automation = [
        {
            "controllerGuid": guid,
            "effectGuid": _guid(controller.get("propertyOwnerGuid")),
            "parameter": str(controller.get("inputParameterName") or "").casefold(),
            "propertyIndex": controller.get("propertyIndex"),
            "routingAttribution": "unavailableInBankGraphV3",
        }
        for guid, controller in sorted(all_controllers.items())
        if _guid(controller.get("propertyOwnerGuid")) in effects
        and str(controller.get("inputParameterName") or "").casefold()
        in _TURBO_RUNTIME_PARAMETERS
    ]

    output = {
        "schema": TURBO_TRANSIENT_SOURCE_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": str(event.get("path")),
        "classifierRole": _TURBO_TRANSIENT_CLASSIFIER_ROLE,
        "resolvedManifestRole": resolved_role,
        "programMode": program_mode,
        "lifetime": "oneShot",
        "rootRpm": None,
        "captureRootRpm": None,
        "programPlacementRootInstrumentGuid": placement_root,
        "placementSignature": placement_signature,
        "selectionPath": selection_path,
        "captureParameterValues": capture_values,
        "captureBoost": capture_values["boost"],
        "staticAudibilityDisposition": static_audibility_disposition,
        "controlParameters": parameter_identity,
        "controlGainCurves": control_gain_curves,
        "boostGainCurve": control_gain_curves["boost"],
        "pitchAutomation": pitch_automation,
        "controllers": controller_evidence,
        "baseGain": {
            "bakedByTargetOnlyReferenceCapture": True,
            "sourceAndAncestorRawValues": base_gain_values,
            "applyAgainInManifestGainDb": False,
        },
        "basePitch": {
            "bakedByTargetOnlyReferenceCapture": not property_one_overrides_base_pitch,
            "sourceAndAncestorRawValues": base_pitch_values,
            "applyAgainAtRuntime": False,
            "propertyOneAutomationOverridesBaseProperty": (
                property_one_overrides_base_pitch
            ),
            "effectiveAuthoredBaseSemitonesAtCapture": round(
                timeline_base_pitch_semitones
                if program_mode == "TIMELINE_PERIODIC_ONE_SHOT"
                else (
                    0.0
                    if property_one_overrides_base_pitch
                    else authored_base_pitch_semitones
                ),
                8,
            ),
        },
        "sourceGeometry": {
            "nodes": source_geometry_nodes,
            "sampleTechnicalEvidence": sample_technical_evidence,
            "sourceLoopCount": source_loop_count,
        },
        "parameterRegion": parameter_region,
        "timelineGeometry": timeline_geometry,
        "captureIsolation": timeline_capture_constraint,
        "programTriggerTemplate": trigger_template,
        "normalization": {
            "captureAxisAmplitudes": {
                name: value for name, value in sorted(capture_axis_amplitudes.items())
            },
            "combinedCaptureAmplitude": total_capture_amplitude,
            "staticallySilentControlAxes": sorted(statically_silent_axes),
            "capturePcmContainsAuthoredBaseGainAndRouting": True,
            "runtimeControlCurvesAreRelativeToCapture": True,
        },
        "voicePolicy": {
            "softwareChannelPriority": None,
            "priorityRequiredFromSourceBoundOracle": True,
            "acGlobalLogicalVoiceCap": 2048,
            "acDefaultSoftwareRealVoiceBudget": 256,
            "overlapSharesGlobalBudget": True,
        },
        "runtimeControlSemantics": {
            "boost": "AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN",
            "bov": "AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED",
            "bov_decay": "AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED",
            "propertyZero": "DB_VOLUME",
            "propertyOne": "RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE",
            "propertyFour": "LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH",
            "autoPitchFromParameterPlacement": False,
        },
        "fidelity": {
            "sampleNamesUsed": False,
            "curvePointTypes": sorted(
                {
                    point_type
                    for points in controller_points.values()
                    for _x, _y, _shape, point_type in points
                }
            ),
            "linearSamplingTolerance": tolerance,
            "denseValidationProbesPerSegment": _VALIDATION_PROBES_PER_SEGMENT,
            "controlGainDenseGridMaxObservedLinearError": gain_errors,
            "pitchRateDenseGridMaxObservedLinearError": pitch_errors,
            "propertyOneScaleEvidence": (
                "TatuusFMODStudio108AuthoringXMLPitchValuesEqualBankRawTimes24"
            ),
            "parameterSheetAutoPitchOracle": (
                "AudiSourceDurationInvariantAcrossHeldBoostValues"
            ),
            "exactnessClaim": False,
            "requiredFinalGate": "sourceBoundTurboLifecycleGainPitchPcmAndPriorityOracle",
        },
        "unsupported": {
            "sourceModulators": source_modulators,
            "bankWideUnattributedTurboDspAutomation": unattributed_dsp_automation,
        },
    }
    return output


def certify_manifest_turbo_transient_source(
    turbo_source: dict[str, Any], source_verification: dict[str, Any]
) -> dict[str, Any]:
    """Bind one derived turbo leaf to target-only PCM and runtime evidence."""

    if (
        not isinstance(turbo_source, dict)
        or turbo_source.get("schema") != TURBO_TRANSIENT_SOURCE_SCHEMA
    ):
        _fail("invalidTurboTransientSource", f"expected {TURBO_TRANSIENT_SOURCE_SCHEMA}")
    if (
        not isinstance(source_verification, dict)
        or source_verification.get("schema") != TURBO_TRANSIENT_VERIFICATION_SCHEMA
    ):
        _fail(
            "invalidTurboTransientVerification",
            f"expected {TURBO_TRANSIENT_VERIFICATION_SCHEMA}",
        )
    source = copy.deepcopy(turbo_source)
    evidence = copy.deepcopy(source_verification)
    fidelity = source.get("fidelity")
    if not isinstance(fidelity, dict) or fidelity.get("exactnessClaim") is not False:
        _fail(
            "invalidTurboTransientSource",
            "only an unverified exactnessClaim=false source can be certified",
        )
    unsupported = source.get("unsupported")
    if not isinstance(unsupported, dict) or any(
        unsupported.get(name)
        for name in (
            "sourceModulators",
            "bankWideUnattributedTurboDspAutomation",
        )
    ):
        _fail(
            "unsupportedTurboRuntimeSemantics",
            "source has a modulator or unattributed turbo DSP automation",
        )
    expected_identity = (
        str(source.get("sourceGuid")),
        str(source.get("eventPath")),
        str(source.get("programMode")),
        str(source.get("programPlacementRootInstrumentGuid")),
    )
    evidence_identity = (
        str(evidence.get("sourceGuid")),
        str(evidence.get("eventPath")),
        str(evidence.get("programMode")),
        str(evidence.get("programPlacementRootInstrumentGuid")),
    )
    if evidence_identity != expected_identity:
        _fail(
            "turboVerificationIdentityMismatch",
            "source/event/mode/program-root identity differs",
        )
    source_hash = _canonical_json_sha256(source)
    if evidence.get("derivedSourceSha256") != source_hash:
        _fail(
            "turboVerificationSourceMismatch",
            "canonical derived-source hash differs",
        )
    renderer = evidence.get("renderer")
    if not isinstance(renderer, dict) or (
        renderer.get("runtime") != _REFERENCE_RENDERER_VERSION
        or renderer.get("sampleRateHz") != 48000
        or renderer.get("channels") != 2
        or renderer.get("sampleFormat") != "signedPcm16LittleEndian"
        or renderer.get("audioDeviceOpened") is not False
        or renderer.get("targetOnly") is not True
    ):
        _fail("turboVerificationRuntimeMismatch", "reference-renderer contract differs")
    capture = evidence.get("capture")
    if not isinstance(capture, dict) or capture.get("accepted") is not True:
        _fail("turboPcmVerificationFailed", "target-only capture was not accepted")
    if capture.get("oracleVersion") != TURBO_PCM_CAPTURE_ORACLE_VERSION:
        _fail(
            "turboPcmVerificationFailed",
            "source-bound DSP-schedule-normalized PCM oracle version is absent or unsupported",
        )
    if capture.get("captureParameterValues") != source.get("captureParameterValues"):
        _fail("turboPcmVerificationFailed", "capture controls differ from derivation")
    static_silent = (
        source.get("staticAudibilityDisposition")
        == "AUTHORED_CURVE_SILENT_ALL_DOMAIN"
    )
    pcm_disposition = str(capture.get("audibilityDisposition") or "")
    all_pcm_samples_zero = capture.get("allPcmSamplesZero") is True
    if (
        _guid(capture.get("scheduledSourceGuid")) != source.get("sourceGuid")
        or capture.get("independentRenderBitExact") is not True
        or pcm_disposition not in {"AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_SILENT"}
        or all_pcm_samples_zero
        != (pcm_disposition == "AUTHORED_TARGET_SILENT")
        or (static_silent and pcm_disposition != "AUTHORED_TARGET_SILENT")
    ):
        _fail(
            "turboPcmVerificationFailed",
            "capture is not source-bound, repeatable target PCM with the expected audibility",
        )
    try:
        frame_count = int(capture.get("frameCount"))
    except (TypeError, ValueError):
        _fail("turboPcmVerificationFailed", "capture frame count is invalid")
    pcm_sha256 = str(capture.get("pcmPayloadSha256") or "").casefold()
    peak_dbfs = _finite(
        capture.get("peakPcmDbfs"),
        code="turboPcmVerificationFailed",
        detail="capture peak is invalid",
    )
    final_wav_relative_path = str(capture.get("finalWavRelativePath") or "")
    final_wav_sha256 = str(capture.get("finalWavSha256") or "").casefold()
    try:
        playback_start = int(capture.get("playbackStartFrame"))
        playback_end = int(capture.get("playbackEndFrameExclusive"))
        termination_error_frames = int(
            capture.get("terminationTimingErrorBoundFrames")
        )
        writer_latency_frames = int(
            capture.get("writerPipelineLatencyFramesRemoved")
        )
    except (TypeError, ValueError):
        _fail("turboPcmVerificationFailed", "capture playback bounds are invalid")
    if (
        frame_count <= 0
        or len(pcm_sha256) != 64
        or any(character not in "0123456789abcdef" for character in pcm_sha256)
        or not final_wav_relative_path
        or Path(final_wav_relative_path).is_absolute()
        or ".." in Path(final_wav_relative_path).parts
        or len(final_wav_sha256) != 64
        or any(character not in "0123456789abcdef" for character in final_wav_sha256)
        or playback_start != 0
        or playback_end != frame_count
        or termination_error_frames < 0
        or termination_error_frames > 256
        or writer_latency_frames != 2048
        or (pcm_disposition == "AUDIBLE_TARGET_PCM" and peak_dbfs <= -120.0)
        or (pcm_disposition == "AUTHORED_TARGET_SILENT" and peak_dbfs > -120.0)
    ):
        _fail("turboPcmVerificationFailed", "capture PCM identity/audibility is invalid")
    embedded_boundary = capture.get("embeddedSourcePcmBoundaryEvidence")
    sample_evidence = source.get("sourceGeometry", {}).get(
        "sampleTechnicalEvidence"
    )
    if not isinstance(embedded_boundary, dict) or not isinstance(
        sample_evidence, dict
    ):
        _fail(
            "turboPcmVerificationFailed",
            "embedded source PCM boundary proof is absent",
        )
    try:
        embedded_bank_index = int(embedded_boundary.get("soundBankIndex"))
        embedded_subsound_index = int(embedded_boundary.get("subsoundIndex"))
        embedded_frame_count = int(embedded_boundary.get("frameCount"))
        embedded_channels = int(embedded_boundary.get("channels"))
        embedded_rate = int(embedded_boundary.get("sampleRateHz"))
        authored_leading_silence = int(
            embedded_boundary.get("authoredLeadingSilentFrames")
        )
        authored_trailing_silence = int(
            embedded_boundary.get("authoredTrailingSilentFrames")
        )
    except (TypeError, ValueError):
        _fail(
            "turboPcmVerificationFailed",
            "embedded source PCM boundary fields are invalid",
        )
    if (
        embedded_boundary.get("accepted") is not True
        or embedded_boundary.get("encoding") != "FSB5_PCM16_LE"
        or embedded_boundary.get("sampleNameUsed") is not False
        or embedded_bank_index != int(sample_evidence.get("soundBankIndex"))
        or embedded_subsound_index != int(sample_evidence.get("subsoundIndex"))
        or embedded_frame_count != int(sample_evidence.get("sampleCount"))
        or embedded_channels != int(sample_evidence.get("channels"))
        or embedded_rate != int(sample_evidence.get("frequencyHz"))
        or embedded_boundary.get("encodedPayloadSha256")
        != sample_evidence.get("encodedPayloadSha256")
        or authored_leading_silence < 0
        or authored_leading_silence > 1
        or authored_trailing_silence < 0
        or authored_trailing_silence > embedded_frame_count
    ):
        _fail(
            "turboPcmVerificationFailed",
            "embedded source PCM boundary proof differs from graph evidence",
        )
    raw_bit_exact = capture.get("independentRenderRawBitExact")
    comparison_mode = str(capture.get("independentRenderComparisonMode") or "")
    raw_offsets = capture.get("independentRenderStartOffsetsFrames")
    raw_retained_prefix = capture.get("retainedQuantizedSilentPrefixFrames")
    try:
        offsets = [int(value) for value in raw_offsets]
        retained_prefix = [int(value) for value in raw_retained_prefix]
        offset_difference = int(
            capture.get("independentRenderStartOffsetDifferenceFrames")
        )
        compared_frames = int(capture.get("independentRenderComparedFrameCount"))
        scheduling_block = int(capture.get("dspSchedulingBlockFrames"))
        audible_loss = int(capture.get("maximumAudibleSampleLoss"))
    except (TypeError, ValueError):
        _fail(
            "turboPcmVerificationFailed",
            "independent-render scheduling-normalization fields are invalid",
        )
    if (
        raw_bit_exact not in (True, False)
        or not isinstance(raw_offsets, list)
        or len(offsets) != 2
        or not isinstance(raw_retained_prefix, list)
        or len(retained_prefix) != 2
        or any(value < 0 or value % 256 for value in offsets)
        or offset_difference != abs(offsets[0] - offsets[1])
        or compared_frames < frame_count
        or scheduling_block != 256
        or capture.get("removedPrefixSamplesAllZero") is not True
        or audible_loss != 0
        or any(value < 0 for value in retained_prefix)
    ):
        _fail(
            "turboPcmVerificationFailed",
            "independent-render scheduling normalization exceeds its bounds",
        )
    capture_mode = str(capture.get("captureMode") or "")
    verified_loop_start: int | None = None
    verified_loop_end: int | None = None
    if source.get("programMode") == "TIMELINE_PERIODIC_ONE_SHOT":
        try:
            verified_loop_start = int(capture.get("loopStartFrame"))
            verified_loop_end = int(capture.get("loopEndFrameExclusive"))
            captured_period = int(capture.get("programTimelinePeriodFrames"))
            cycle_boundary_error = int(
                capture.get("sourceCycleBoundaryErrorBoundFrames")
            )
        except (TypeError, ValueError):
            _fail("turboPcmVerificationFailed", "timeline cycle bounds are invalid")
        if (
            capture_mode != "TIME_LOCKED_SOURCE_CYCLE"
            or comparison_mode != "RAW_RENDER_PCM16"
            or raw_bit_exact is not True
            or offsets != [0, 0]
            or verified_loop_start != 0
            or verified_loop_end != frame_count
            or captured_period
            != int(source["timelineGeometry"]["repeatPeriodTicks"])
            or cycle_boundary_error < 0
            or cycle_boundary_error > 1
            or capture.get("sourcePlaybackMode")
            != "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT"
        ):
            _fail(
                "turboPcmVerificationFailed",
                "timeline capture is not one bounded time-locked source cycle",
            )
        isolation = capture.get("sourceCycleIsolation")
        if not isinstance(isolation, dict) or isolation.get("accepted") is not True:
            _fail(
                "turboPcmVerificationFailed",
                "timeline source-cycle isolation proof is absent",
            )
        try:
            selected_boundary = int(isolation.get("selectedBoundaryFrame"))
            lookahead_frames = int(isolation.get("renderedLookAheadFrames"))
            required_lookahead = int(
                isolation.get("requiredMinimumLookAheadFrames")
            )
            search_radius = int(isolation.get("searchRadiusFrames"))
            loop_error_lsb = int(
                isolation.get("loopReplacementMaximumErrorLsb")
            )
        except (TypeError, ValueError):
            _fail(
                "turboPcmVerificationFailed",
                "timeline source-cycle isolation integers are invalid",
            )
        prediction_error = _finite(
            isolation.get("predictionErrorFrames"),
            code="turboPcmVerificationFailed",
            detail="timeline source-cycle prediction error is invalid",
        )
        recurrence_error = _finite(
            isolation.get("normalizedMeanSquareRecurrenceError"),
            code="turboPcmVerificationFailed",
            detail="timeline source-cycle recurrence error is invalid",
        )
        recurrence_correlation = _finite(
            isolation.get("recurrenceCorrelation"),
            code="turboPcmVerificationFailed",
            detail="timeline source-cycle recurrence correlation is invalid",
        )
        expected_lookahead = int(
            source["captureIsolation"]["minimumRenderedLookAheadFrames"]
        )
        if (
            isolation.get("boundarySelection")
            != "MINIMUM_NORMALIZED_PCM_RECURRENCE_ERROR_AROUND_PARSED_SOURCE_GEOMETRY"
            or selected_boundary != frame_count
            or prediction_error < 0.0
            or prediction_error > 1.0
            or recurrence_error < 0.0
            or recurrence_error > 0.5
            or recurrence_correlation < 0.75
            or recurrence_correlation > 1.000001
            or search_radius != 64
            or required_lookahead != expected_lookahead
            or lookahead_frames < required_lookahead
            or isolation.get("notTrimmedAtLastNonzeroSample") is not True
            or isolation.get("independentRenderBitExact") is not True
            or loop_error_lsb < 0
            or loop_error_lsb > 512
        ):
            _fail(
                "turboPcmVerificationFailed",
                "timeline source-cycle isolation exceeds its oracle bounds",
            )
    else:
        expected_comparison_mode = (
            "ALL_ZERO_TARGET_FULL_AUTHORED_DURATION"
            if pcm_disposition == "AUTHORED_TARGET_SILENT"
            else "DSP_BLOCK_SCHEDULING_NORMALIZED_PCM16"
        )
        if (
            capture_mode != "ONE_SHOT_ROUTED_PCM"
            or comparison_mode != expected_comparison_mode
            or compared_frames != frame_count
            or (
                pcm_disposition == "AUTHORED_TARGET_SILENT"
                and (offsets != [0, 0] or raw_bit_exact is not True)
            )
            or (
                pcm_disposition == "AUDIBLE_TARGET_PCM"
                and any(value >= 256 for value in retained_prefix)
            )
        ):
            _fail(
                "turboPcmVerificationFailed",
                "parameter-triggered turbo capture scheduling proof is invalid",
            )
    curve = evidence.get("curveVerification")
    if not isinstance(curve, dict) or curve.get("accepted") is not True:
        _fail("turboCurveVerificationFailed", "gain/pitch curve proof was not accepted")
    if curve.get("oracleVersion") != TURBO_CONTROL_GAIN_ORACLE_VERSION:
        _fail(
            "turboCurveVerificationFailed",
            "source-bound routed-gain oracle version is absent or unsupported",
        )
    verified_control_curves = curve.get("verifiedControlGainCurves")
    expected_control_curves = source.get("controlGainCurves")
    if (
        not isinstance(verified_control_curves, dict)
        or not isinstance(expected_control_curves, dict)
        or set(verified_control_curves) != set(expected_control_curves)
    ):
        _fail(
            "turboCurveVerificationFailed",
            "verified control-gain axes differ from the derived source",
        )

    replacement_control_curves: dict[str, list[list[float]]] = {}
    for parameter_name in sorted(expected_control_curves):
        verification = verified_control_curves.get(parameter_name)
        if not isinstance(verification, dict) or set(verification) != {
            "control",
            "domain",
            "captureValue",
            "captureRoutedGain",
            "captureCurveValue",
            "curve",
            "oracleProbeCount",
            "oracleRestartCount",
            "absoluteLinearGainTolerance",
            "maximumObservedAbsoluteLinearGainErrorOutsideTransitions",
            "transitionIntervals",
            "maximumTransitionDomainWidth",
            "float32ControlInputs",
            "runtimeMeasurement",
        }:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain verification fields are invalid",
            )
        if verification.get("control") != parameter_name:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain control identity differs",
            )
        parameter_identity = source.get("controlParameters", {}).get(parameter_name)
        raw_domain = verification.get("domain")
        if (
            not isinstance(parameter_identity, dict)
            or not isinstance(raw_domain, list)
            or len(raw_domain) != 2
        ):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain domain is invalid",
            )
        domain = tuple(
            _finite(
                value,
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} routed-gain domain is invalid",
            )
            for value in raw_domain
        )
        authored_domain = tuple(float(value) for value in parameter_identity["domain"])
        if domain != authored_domain or domain[1] <= domain[0]:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain domain differs from the authored parameter",
            )
        capture_value = _finite(
            verification.get("captureValue"),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} capture value is invalid",
        )
        if capture_value != float(source["captureParameterValues"][parameter_name]):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain capture value differs",
            )
        capture_routed_gain = _finite(
            verification.get("captureRoutedGain"),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} routed gain at capture is invalid",
        )
        capture_curve_value = _finite(
            verification.get("captureCurveValue"),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} normalized capture gain is invalid",
        )
        tolerance = _finite(
            verification.get("absoluteLinearGainTolerance"),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} routed-gain tolerance is invalid",
        )
        observed_error = _finite(
            verification.get(
                "maximumObservedAbsoluteLinearGainErrorOutsideTransitions"
            ),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} routed-gain error is invalid",
        )
        try:
            probe_count = int(verification.get("oracleProbeCount"))
            restart_count = int(verification.get("oracleRestartCount"))
        except (TypeError, ValueError):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain probe counts are invalid",
            )
        if (
            verification.get("float32ControlInputs") is not True
            or verification.get("runtimeMeasurement")
            != "TARGET_CHANNEL_GROUP_ANCESTRY_TIMES_CHANNEL_FADER"
            or tolerance <= 0.0
            or tolerance > 2.0e-4
            or observed_error < 0.0
            or observed_error > tolerance * 1.0001
            or probe_count < 2
            or restart_count < 0
            or capture_routed_gain < 0.0
        ):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain oracle bounds are invalid",
            )

        raw_verified_curve = verification.get("curve")
        if not isinstance(raw_verified_curve, list) or len(raw_verified_curve) < 2:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain curve is invalid",
            )
        verified_curve: list[list[float]] = []
        previous_x: float | None = None
        for raw_point in raw_verified_curve:
            if not isinstance(raw_point, list) or len(raw_point) != 2:
                _fail(
                    "turboCurveVerificationFailed",
                    f"{parameter_name} routed-gain point is invalid",
                )
            point_x = _finite(
                raw_point[0],
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} routed-gain x is invalid",
            )
            point_y = _finite(
                raw_point[1],
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} routed-gain y is invalid",
            )
            if (
                point_x < domain[0]
                or point_x > domain[1]
                or point_y < 0.0
                or (previous_x is not None and point_x <= previous_x)
            ):
                _fail(
                    "turboCurveVerificationFailed",
                    f"{parameter_name} routed-gain curve is unordered/outside its domain",
                )
            verified_curve.append([point_x, point_y])
            previous_x = point_x
        if verified_curve[0][0] != domain[0] or verified_curve[-1][0] != domain[1]:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain curve does not span its domain",
            )

        def curve_value(value: float) -> float:
            if value <= verified_curve[0][0]:
                return verified_curve[0][1]
            if value >= verified_curve[-1][0]:
                return verified_curve[-1][1]
            right_index = bisect.bisect_left(
                [point[0] for point in verified_curve], value
            )
            left_x, left_y = verified_curve[right_index - 1]
            right_x, right_y = verified_curve[right_index]
            amount = (value - left_x) / (right_x - left_x)
            return left_y + ((right_y - left_y) * amount)

        evaluated_capture = curve_value(capture_value)
        statically_silent_axis = parameter_name in set(
            source.get("normalization", {}).get("staticallySilentControlAxes", [])
        )
        source_bound_silent_axis = (
            pcm_disposition == "AUTHORED_TARGET_SILENT"
            and capture_routed_gain == 0.0
        )
        if statically_silent_axis or source_bound_silent_axis:
            if (
                capture_routed_gain != 0.0
                or capture_curve_value != 0.0
                or any(point[1] != 0.0 for point in verified_curve)
            ):
                _fail(
                    "turboCurveVerificationFailed",
                    f"{parameter_name} statically-silent source gained signal in the oracle",
                )
        elif (
            capture_routed_gain <= 0.0
            or abs(capture_curve_value - 1.0) > tolerance
            or abs(evaluated_capture - 1.0) > tolerance
        ):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} routed-gain curve is not normalized at capture",
            )

        raw_transitions = verification.get("transitionIntervals")
        maximum_transition_width = _finite(
            verification.get("maximumTransitionDomainWidth"),
            code="turboCurveVerificationFailed",
            detail=f"{parameter_name} transition-width bound is invalid",
        )
        if not isinstance(raw_transitions, list) or maximum_transition_width < 0.0:
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} transition intervals are invalid",
            )
        measured_maximum_transition_width = 0.0
        previous_transition_end: float | None = None
        for transition in raw_transitions:
            if not isinstance(transition, dict) or set(transition) != {
                "minimum",
                "maximum",
                "width",
                "leftRelativeGain",
                "rightRelativeGain",
                "maximumObservedAbsoluteLinearGainError",
            }:
                _fail(
                    "turboCurveVerificationFailed",
                    f"{parameter_name} transition interval fields are invalid",
                )
            transition_minimum = _finite(
                transition.get("minimum"),
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} transition minimum is invalid",
            )
            transition_maximum = _finite(
                transition.get("maximum"),
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} transition maximum is invalid",
            )
            transition_width = _finite(
                transition.get("width"),
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} transition width is invalid",
            )
            transition_error = _finite(
                transition.get("maximumObservedAbsoluteLinearGainError"),
                code="turboCurveVerificationFailed",
                detail=f"{parameter_name} transition error is invalid",
            )
            for gain_key in ("leftRelativeGain", "rightRelativeGain"):
                if _finite(
                    transition.get(gain_key),
                    code="turboCurveVerificationFailed",
                    detail=f"{parameter_name} transition gain is invalid",
                ) < 0.0:
                    _fail(
                        "turboCurveVerificationFailed",
                        f"{parameter_name} transition gain is negative",
                    )
            if (
                transition_minimum < domain[0]
                or transition_maximum > domain[1]
                or transition_maximum <= transition_minimum
                or transition_width != transition_maximum - transition_minimum
                or transition_error < tolerance
                or (
                    previous_transition_end is not None
                    and transition_minimum < previous_transition_end
                )
            ):
                _fail(
                    "turboCurveVerificationFailed",
                    f"{parameter_name} transition interval is invalid",
                )
            previous_transition_end = transition_maximum
            measured_maximum_transition_width = max(
                measured_maximum_transition_width, transition_width
            )
        domain_span = domain[1] - domain[0]
        allowed_transition_width = max(
            domain_span / float(1 << 20),
            math.ulp(max(abs(domain[0]), abs(domain[1]), 1.0)) * 16.0,
        )
        if (
            abs(
                measured_maximum_transition_width - maximum_transition_width
            )
            > math.ulp(max(maximum_transition_width, 1.0)) * 8.0
            or maximum_transition_width > allowed_transition_width
        ):
            _fail(
                "turboCurveVerificationFailed",
                f"{parameter_name} near-vertical transition exceeds its domain bound",
            )
        replacement_control_curves[parameter_name] = verified_curve

    gain_error_db = _finite(
        curve.get("maximumGainErrorDb"),
        code="turboCurveVerificationFailed",
        detail="gain error is invalid",
    )
    pitch_error_cents = _finite(
        curve.get("maximumPitchErrorCents"),
        code="turboCurveVerificationFailed",
        detail="pitch error is invalid",
    )
    if gain_error_db > 0.25 or pitch_error_cents > 1.0:
        _fail(
            "turboCurveVerificationFailed",
            "gain exceeds 0.25 dB or pitch exceeds 1 cent",
        )
    scheduling = evidence.get("schedulingVerification")
    if (
        not isinstance(scheduling, dict)
        or scheduling.get("accepted") is not True
        or scheduling.get("programMode") != source.get("programMode")
        or scheduling.get("activeVoiceExitBehaviorVerified") is not True
        or scheduling.get("overlapBehaviorVerified") is not True
    ):
        _fail(
            "turboSchedulingVerificationFailed",
            "source scheduling/overlap lifecycle was not accepted",
        )
    voice = evidence.get("voicePolicy")
    if not isinstance(voice, dict) or voice.get("sourceBoundChannelObserved") is not True:
        _fail("turboVoicePolicyVerificationFailed", "source channel was not observed")
    try:
        priority = int(voice.get("softwareChannelPriority"))
    except (TypeError, ValueError):
        _fail("turboVoicePolicyVerificationFailed", "channel priority is invalid")
    if priority < 0 or priority > 256:
        _fail("turboVoicePolicyVerificationFailed", f"priority {priority} is outside 0..256")
    evidence_hash = str(evidence.get("verificationPayloadSha256") or "")
    unhashed = copy.deepcopy(evidence)
    unhashed.pop("verificationPayloadSha256", None)
    if evidence_hash != _canonical_json_sha256(unhashed):
        _fail("turboVerificationHashMismatch", "verification payload hash differs")

    source["voicePolicy"] = {
        **source["voicePolicy"],
        "softwareChannelPriority": priority,
        "priorityRequiredFromSourceBoundOracle": False,
    }
    source["controlGainCurves"] = replacement_control_curves
    source["boostGainCurve"] = replacement_control_curves["boost"]
    source["normalization"] = {
        **source["normalization"],
        "runtimeControlCurvesReplacedBySourceBoundRoutedGainOracle": True,
        "sourceBoundRoutedGainOracleVersion": TURBO_CONTROL_GAIN_ORACLE_VERSION,
    }
    source["fidelity"] = {
        **fidelity,
        "exactnessClaim": True,
        "exactWithinReportedOracleBounds": True,
        "requiredFinalGate": "satisfiedBySourceBoundTurboVerification",
        "sourceVerificationPayloadSha256": evidence_hash,
    }
    source["verifiedTargetPcm"] = {
        "frameCount": frame_count,
        "pcmPayloadSha256": pcm_sha256,
        "peakPcmDbfs": peak_dbfs,
        "playbackStartFrame": playback_start,
        "playbackEndFrameExclusive": playback_end,
        "terminationTimingErrorBoundFrames": termination_error_frames,
        "writerPipelineLatencyFramesRemoved": writer_latency_frames,
        "audibilityDisposition": pcm_disposition,
        "allPcmSamplesZero": all_pcm_samples_zero,
        "finalWavRelativePath": final_wav_relative_path,
        "finalWavSha256": final_wav_sha256,
        "captureMode": capture_mode,
        "loopStartFrame": verified_loop_start,
        "loopEndFrameExclusive": verified_loop_end,
        "sourceCycleIsolation": (
            copy.deepcopy(capture.get("sourceCycleIsolation"))
            if verified_loop_start is not None
            else None
        ),
    }
    source["sourceVerification"] = evidence
    return source


def _limiter_event_for_source(
    report: dict[str, Any], source_guid: str, classification: dict[str, Any]
) -> dict[str, Any]:
    declared_paths = classification.get("eventPaths")
    if not isinstance(declared_paths, list):
        _fail("invalidClassification", "limiter source row has no eventPaths array")
    declared = {str(item) for item in declared_paths}
    events = report.get("events")
    if not isinstance(events, list):
        _fail("invalidGraph", "events must be an array")
    matches = []
    for event in events:
        if not isinstance(event, dict):
            _fail("invalidGraph", "event must be an object")
        reachable = event.get("reachableInstrumentGuids")
        if not isinstance(reachable, list):
            _fail("invalidGraph", "event reachableInstrumentGuids must be an array")
        if (
            source_guid in {_guid(item) for item in reachable}
            and _event_suffix(event.get("path")) == "limiter"
        ):
            matches.append(event)
    if len(matches) != 1:
        _fail(
            "unsupportedLimiterEventTopology",
            f"source is reachable from {len(matches)} limiter events",
        )
    event = matches[0]
    if str(event.get("path")) not in declared or event.get("mappingComplete") is not True:
        _fail("invalidClassification", "limiter classification/event mapping disagrees")
    return event


def derive_manifest_limiter_program(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Compile one official AC limiter source and its persistent host lifecycle.

    AC owns one limiter EventInstance.  Its float32 decay timer starts at 10,
    increments by audio-update ``dt``, resets to zero on a limiter pulse, and
    is written to the authored ``decay`` parameter.  The owner keeps the event
    active while the timer is at most 10 seconds, preserves phase on repeated
    cuts, and uses AudioEvent::start (timeline rewind followed by start) after
    inactivity.  This API compiles the three shipped source topologies without
    reducing them to periodic host-generated limiter pulses.
    """

    if graph_report.get("schema") != BANK_GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {BANK_GRAPH_SCHEMA}")
    if not isinstance(source_classification, dict):
        _fail("invalidClassification", "source classification must be an object")
    if source_classification.get("policy") != _ALLOWED_POLICY:
        _fail("sourceNotAllowed", "source classifier did not allow this limiter")
    if source_classification.get("role") != "LIMITER":
        _fail("invalidClassification", "source row is not classified LIMITER")
    source_guid = _guid(source_classification.get("sourceGuid"))
    if not source_guid:
        _fail("invalidClassification", "limiter source row has no GUID")
    tolerance = _finite(
        interpolation_tolerance,
        code="invalidTolerance",
        detail="interpolation tolerance must be finite",
    )
    if tolerance <= 0.0 or tolerance > 0.01:
        _fail("invalidTolerance", "interpolation tolerance must be in (0, 0.01]")

    instruments = _objects_by_guid(graph_report, "instruments")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "limiter GUID is not a waveform instrument")
    chain = _instrument_chain(source_guid, instruments)
    if len(chain) != 1:
        _fail(
            "unsupportedLimiterOwnerTopology",
            "limiter waveform has an ancestor selection/program hierarchy",
        )
    properties = source.get("baseProperties")
    if not isinstance(properties, dict):
        _fail("invalidGraph", "limiter source baseProperties is invalid")
    try:
        loop_count = int(properties.get("loopCount"))
    except (TypeError, ValueError):
        _fail("unsupportedLifetime", "limiter loop count is invalid")
    expected_lifetime = "continuous" if loop_count < 0 else "oneShot"
    if source_classification.get("lifetime") != expected_lifetime:
        _fail("invalidClassification", "limiter lifetime and loop count disagree")

    event = _limiter_event_for_source(
        graph_report, source_guid, source_classification
    )
    raw_parameter_placements = event.get("parameterPlacements", [])
    raw_timeline_placements = event.get("timelinePlacements", [])
    if not isinstance(raw_parameter_placements, list) or not isinstance(
        raw_timeline_placements, list
    ):
        _fail("invalidGraph", "limiter placements must be arrays")
    parameter_placements = [
        item
        for item in raw_parameter_placements
        if isinstance(item, dict)
        and _guid(item.get("instrumentGuid")) == source_guid
    ]
    timeline_placements = [
        item
        for item in raw_timeline_placements
        if isinstance(item, dict)
        and _guid(item.get("instrumentGuid")) == source_guid
    ]
    if len(parameter_placements) == 0 and len(timeline_placements) == 1 and loop_count >= 0:
        program_mode = "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT"
    elif len(parameter_placements) == 1 and len(timeline_placements) == 0 and loop_count >= 0:
        program_mode = "PERSISTENT_DECAY_REGION_ONE_SHOT"
    elif len(parameter_placements) == 1 and len(timeline_placements) == 0 and loop_count < 0:
        program_mode = "PERSISTENT_DECAY_REGION_LOOP"
    else:
        _fail(
            "unsupportedLimiterTopology",
            "limiter must have exactly one timeline or decay placement",
        )

    parameters = _objects_by_guid(graph_report, "parameters")
    referenced_guids = source.get("controllerGuids")
    if not isinstance(referenced_guids, list) or not referenced_guids:
        _fail("missingController", "limiter source has no decay controller")
    controllers = _objects_by_guid(graph_report, "controllers")
    referenced = [_guid(item) for item in referenced_guids]
    if len(set(referenced)) != len(referenced) or any(item not in controllers for item in referenced):
        _fail("missingController", "limiter controller references are invalid")
    routable_guid = _guid(properties.get("routableGuid"))
    decay_parameter_guid = ""
    decay_controllers: list[dict[str, Any]] = []
    controller_evidence: list[dict[str, Any]] = []
    for controller_guid in referenced:
        controller = controllers[controller_guid]
        if str(controller.get("inputKind") or "").casefold() != "parameter":
            _fail(
                "unsupportedLimiterControllerInput",
                f"controller {controller_guid} is not parameter-driven",
            )
        if str(controller.get("inputParameterName") or "").casefold() != "decay":
            _fail(
                "unsupportedLimiterControllerParameter",
                f"controller {controller_guid} is not driven by decay",
            )
        try:
            property_index = int(controller.get("propertyIndex"))
        except (TypeError, ValueError):
            _fail("unsupportedPropertyIndex", "limiter controller property is invalid")
        if property_index != 0:
            _fail(
                "unsupportedPropertyIndex",
                f"limiter controller {controller_guid} uses property {property_index}",
            )
        owner = _guid(controller.get("propertyOwnerGuid"))
        if owner not in {source_guid, routable_guid}:
            _fail(
                "unsupportedControllerOwnership",
                f"limiter controller {controller_guid} owner is not the source route",
            )
        parameter_guid = _guid(controller.get("inputParameterGuid"))
        if decay_parameter_guid and parameter_guid != decay_parameter_guid:
            _fail("unsupportedParameterTopology", "limiter uses multiple decay parameters")
        decay_parameter_guid = parameter_guid
        points = _curve_points(controller)
        decay_controllers.append(controller)
        controller_evidence.append(
            {
                "controllerGuid": controller_guid,
                "ownerGuid": owner,
                "propertyIndex": 0,
                "parameter": "decay",
                "points": [
                    {
                        "x": x,
                        "rawValue": y,
                        "shape": shape,
                        "type": point_type,
                    }
                    for x, y, shape, point_type in points
                ],
            }
        )
    decay_parameter = parameters.get(decay_parameter_guid)
    if decay_parameter is None:
        _fail("unsupportedParameterTopology", "decay parameter definition is absent")
    decay_minimum = _finite(
        decay_parameter.get("minimum"),
        code="unsupportedParameterTopology",
        detail="decay minimum is invalid",
    )
    decay_maximum = _finite(
        decay_parameter.get("maximum"),
        code="unsupportedParameterTopology",
        detail="decay maximum is invalid",
    )
    decay_default = _finite(
        decay_parameter.get("defaultValue"),
        code="unsupportedParameterTopology",
        detail="decay default is invalid",
    )
    if (
        abs(decay_minimum) > 1.0e-8
        or abs(decay_maximum - 1.0) > 1.0e-8
        or abs(decay_default) > 1.0e-8
        or str(decay_parameter.get("type") or "")
        != "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED"
    ):
        _fail("unsupportedParameterTopology", "limiter decay domain/default/type changed")

    if parameter_placements:
        placement = parameter_placements[0]
        if (
            str(placement.get("parameterName") or "").casefold() != "decay"
            or _guid(placement.get("parameterGuid")) != decay_parameter_guid
        ):
            _fail("unsupportedLimiterTopology", "limiter placement is not decay")
        placement_start = _finite(
            placement.get("start"),
            code="unsupportedTriggerPlacement",
            detail="limiter decay placement start is invalid",
        )
        placement_end = _finite(
            placement.get("end"),
            code="unsupportedTriggerPlacement",
            detail="limiter decay placement end is invalid",
        )
        if (
            abs(placement_start) > 1.0e-8
            or placement_end <= placement_start
            or placement_end > decay_maximum
        ):
            _fail("unsupportedTriggerPlacement", "limiter decay placement changed")
        decay_placement = {
            "control": "LIMITER_DECAY_SECONDS",
            "minimum": placement_start,
            "maximum": placement_end,
            "includeMinimum": True,
            "includeMaximum": placement.get("includeEnd") is True,
        }
    else:
        decay_placement = None

    override_values = {
        str(key).casefold(): _finite(
            value,
            code="invalidCaptureControl",
            detail=f"capture control {key} is invalid",
        )
        for key, value in (capture_controls or {}).items()
    }
    unknown_overrides = set(override_values) - (
        {"decay"} | _FIXED_AUTOMATIC_PARAMETERS
    )
    if unknown_overrides:
        _fail(
            "unsupportedCaptureControl",
            f"unsupported limiter capture controls: {sorted(unknown_overrides)}",
        )
    if abs(override_values.get("decay", 0.0)) > 1.0e-8:
        _fail("invalidCaptureControl", "limiter reference capture requires decay=0")
    automatic_values = {
        name: round(
            override_values.get(name, DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS[name]), 8
        )
        for name in sorted(_FIXED_AUTOMATIC_PARAMETERS)
    }

    def decay_function(value: float) -> float:
        result = 1.0
        for controller in decay_controllers:
            result *= _controller_amplitude(controller, value)
        return result

    capture_amplitude = decay_function(0.0)
    if capture_amplitude <= 1.0e-8:
        _fail("silentSource", "limiter decay=0 capture is silent")
    knots = {decay_minimum, decay_maximum}
    for controller in decay_controllers:
        knots.update(point[0] for point in _curve_points(controller))
    decay_curve, decay_error = _adaptive_sample(
        _normalized_curve_function(decay_function, capture_amplitude),
        sorted(knots),
        tolerance,
    )
    decay_curve = [
        [round(float(x), 8), round(float(y), 10)] for x, y in decay_curve
    ]

    timeline_placement: dict[str, Any] | None = None
    timeline_capture: dict[str, Any] | None = None
    if timeline_placements:
        placement = timeline_placements[0]
        try:
            start_ticks = int(placement.get("startTime"))
            length_ticks = int(placement.get("length"))
        except (TypeError, ValueError):
            _fail("unsupportedTimelinePlacement", "limiter timeline bounds are invalid")
        if start_ticks < 0 or length_ticks <= 0 or placement.get("timeLocked") is not True:
            _fail("unsupportedTimelinePlacement", "limiter timeline placement changed")
        sample = source.get("sample")
        if not isinstance(sample, dict):
            _fail("invalidGraph", "limiter waveform technical evidence is absent")
        sample_count = _finite(
            sample.get("sampleCount"),
            code="invalidGraph",
            detail="limiter sample count is invalid",
        )
        frequency_hz = _finite(
            sample.get("frequencyHz"),
            code="invalidGraph",
            detail="limiter sample frequency is invalid",
        )
        pitch_semitones = _finite(
            properties.get("pitchSemitones", 0.0),
            code="invalidGraph",
            detail="limiter static pitch is invalid",
        )
        if sample_count <= 0.0 or frequency_hz <= 0.0:
            _fail("invalidGraph", "limiter waveform technical evidence is empty")
        estimated_rendered_frames = math.ceil(
            sample_count
            * _FMOD_BANK_TIMELINE_TICKS_PER_SECOND
            / frequency_hz
            / (2.0 ** (pitch_semitones / 12.0))
        )
        warmup_periods = math.ceil(estimated_rendered_frames / length_ticks) + 1
        timeline_placement = {
            "startTicks": start_ticks,
            "lengthTicks": length_ticks,
            "timeLocked": True,
            "tickRateHz": _FMOD_BANK_TIMELINE_TICKS_PER_SECOND,
            "startFrameAt48k": start_ticks,
            "periodFramesAt48k": length_ticks,
        }
        timeline_capture = {
            "mode": "TARGET_ONLY_EVENT_STEADY_PERIOD_RENDER",
            "setDecay": 0.0,
            "rewindTimelineToTicks": 0,
            "startEvent": True,
            "warmupPeriods": warmup_periods,
            "captureFrames": length_ticks,
            "loopBoundsExclusiveEndFrames": [0, length_ticks],
            "requiredVerification": (
                "targetOnlyAdjacentPeriodPcmAndLoopSeamComparison"
            ),
        }

    modulators = graph_report.get("modulators", [])
    if not isinstance(modulators, list):
        _fail("invalidGraph", "modulators must be an array")
    source_modulators = [
        {
            "guid": _guid(item.get("guid")),
            "ownerGuid": _guid(item.get("ownerGuid")),
            "type": item.get("type"),
            "propertyIndex": item.get("propertyIndex"),
        }
        for item in modulators
        if isinstance(item, dict)
        and _guid(item.get("ownerGuid")) in {source_guid, routable_guid}
    ]
    # Seventy-two of the seventy-three shipped limiter waveforms have one
    # routable-owned volume ADSR.  The v3 graph proves its ownership/property
    # but intentionally does not pretend that the bank reader has decoded the
    # ADSR time constants.  A target-isolated reference render therefore bakes
    # the envelope into a one-shot (or into the timeline-period render).  Loop
    # sources additionally require target-measured entry/exit envelopes.  Any
    # other modulator remains fail-closed.
    target_baked_modulators: list[dict[str, Any]] = []
    unsupported_modulators: list[dict[str, Any]] = []
    for modulator in source_modulators:
        try:
            property_index = int(modulator.get("propertyIndex"))
        except (TypeError, ValueError):
            property_index = -1
        if (
            str(modulator.get("type") or "").upper() == "ADSR"
            and _guid(modulator.get("ownerGuid")) == routable_guid
            and property_index == 0
        ):
            target_baked_modulators.append(modulator)
        else:
            unsupported_modulators.append(modulator)
    if unsupported_modulators:
        _fail(
            "unsupportedLimiterModulator",
            "limiter source has an authored modulator not represented by this program",
        )

    pitch_semitones = _finite(
        properties.get("pitchSemitones", 0.0),
        code="invalidGraph",
        detail="limiter static pitch is invalid",
    )
    volume_db = _finite(
        properties.get("volumeDb", 0.0),
        code="invalidGraph",
        detail="limiter base volume is invalid",
    )
    return {
        "schema": LIMITER_PROGRAM_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": str(event.get("path")),
        "manifestRole": "LIMITER",
        "programMode": program_mode,
        "sourceLifetime": expected_lifetime,
        "captureParameterValues": {"decay": 0.0},
        "captureAutomaticParameterValues": automatic_values,
        "baseGain": {
            "sourceVolumeDb": volume_db,
            "bakedByTargetOnlyReferenceCapture": True,
            "applyAgainInManifestGainDb": False,
        },
        "targetCaptureBakedModulators": target_baked_modulators,
        "pitchTreatment": {
            "staticSemitones": pitch_semitones,
            "bakedByTargetOnlyReferenceCapture": True,
            "runtimeVarispeed": False,
        },
        "decayParameter": {
            "control": "LIMITER_DECAY_SECONDS",
            "minimum": decay_minimum,
            "maximum": decay_maximum,
            "defaultValue": decay_default,
            "runtimeInput": "min(hostFloat32DecayTimerSeconds,1)",
        },
        "decayGainCurve": decay_curve,
        "decayPlacement": decay_placement,
        "timelinePlacement": timeline_placement,
        "timelineCapture": timeline_capture,
        "controllers": controller_evidence,
        "runtimeLifecycle": {
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
        },
        "sourceScheduling": {
            "timelinePeriodicOneShot": (
                "EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER"
                if program_mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT"
                else None
            ),
            "parameterRegionEntry": (
                "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY"
                if decay_placement is not None
                else None
            ),
            "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
            "placementExitBehavior": (
                "LET_ACTIVE_ONE_SHOTS_FINISH"
                if program_mode == "PERSISTENT_DECAY_REGION_ONE_SHOT"
                else (
                    "STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY"
                    if program_mode == "PERSISTENT_DECAY_REGION_LOOP"
                    else "TIMELINE_OWNS_SOURCE_LIFETIME"
                )
            ),
            "overlapMode": (
                "ALLOW_OVERLAPPING_ONE_SHOT_VOICES"
                if program_mode == "PERSISTENT_DECAY_REGION_ONE_SHOT"
                else "ONE_RENDERED_TIMELINE_LOOP_TRACK"
                if program_mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT"
                else "ONE_ACTIVE_LOOP_VOICE"
            ),
        },
        "voicePolicy": {
            "maximumSimultaneousProgramTracks": (
                1 if program_mode != "PERSISTENT_DECAY_REGION_ONE_SHOT" else None
            ),
            "oneShotLaneBoundAfterDecode": (
                "min(2048,ceil(decodedOneShotFrames/480))"
                if program_mode == "PERSISTENT_DECAY_REGION_ONE_SHOT"
                else None
            ),
            "acGlobalLogicalVoiceCap": 2048,
            "acDefaultSoftwareRealVoiceBudget": 256,
        },
        "fidelity": {
            "sampleNamesUsed": False,
            "linearSamplingTolerance": tolerance,
            "denseValidationProbesPerSegment": _VALIDATION_PROBES_PER_SEGMENT,
            "decayDenseGridMaxObservedLinearError": decay_error,
            "timelineTickRateBasis": (
                "bankTicksAndFmod108TimelinePositionOracle"
                if timeline_placement is not None
                else None
            ),
            "lifecycleOracle": "ac-fmod-limiter-lifecycle-oracle-v1",
            "exactnessClaim": False,
            "requiredFinalGate": (
                "targetOnlyFmod108LifecycleAndPcmComparisonForThisFamily"
            ),
        },
        "unsupported": {
            "sourceModulators": unsupported_modulators,
            "decayDrivenDspAutomation": [],
        },
    }


def certify_manifest_limiter_program(
    limiter_program: dict[str, Any],
    source_verification: dict[str, Any],
) -> dict[str, Any]:
    """Upgrade one limiter program only after its source-bound oracle gate.

    The ordinary derivation intentionally returns ``exactnessClaim=false``.
    This function accepts only the versioned evidence emitted by the silent
    all-source limiter verifier, binds it to the canonical hash of the exact
    derived program, and independently enforces the mode-specific PCM and
    lifecycle bounds.  Callers cannot turn a boolean into release permission.
    """

    if not isinstance(limiter_program, dict) or limiter_program.get("schema") != LIMITER_PROGRAM_SCHEMA:
        _fail("invalidLimiterProgram", f"expected {LIMITER_PROGRAM_SCHEMA}")
    if not isinstance(source_verification, dict) or source_verification.get("schema") != LIMITER_VERIFICATION_SCHEMA:
        _fail("invalidLimiterVerification", f"expected {LIMITER_VERIFICATION_SCHEMA}")
    program = copy.deepcopy(limiter_program)
    evidence = copy.deepcopy(source_verification)
    fidelity = program.get("fidelity")
    if not isinstance(fidelity, dict) or fidelity.get("exactnessClaim") is not False:
        _fail(
            "invalidLimiterProgram",
            "only an unverified exactnessClaim=false limiter program can be certified",
        )
    program_sha256 = _canonical_json_sha256(program)
    expected_identity = (
        str(program.get("sourceGuid")),
        str(program.get("eventPath")),
        str(program.get("programMode")),
    )
    evidence_identity = (
        str(evidence.get("sourceGuid")),
        str(evidence.get("eventPath")),
        str(evidence.get("programMode")),
    )
    if evidence_identity != expected_identity:
        _fail("limiterVerificationIdentityMismatch", "source/event/mode identity differs")
    if evidence.get("derivedProgramSha256") != program_sha256:
        _fail("limiterVerificationProgramMismatch", "canonical derived-program hash differs")
    executable = evidence.get("executable")
    if not isinstance(executable, dict) or executable.get("sha256") != _AC_LIMITER_EXECUTABLE_SHA256:
        _fail("limiterVerificationRuntimeMismatch", "acs.exe identity differs")
    renderer = evidence.get("renderer")
    if not isinstance(renderer, dict) or (
        renderer.get("runtime") != _REFERENCE_RENDERER_VERSION
        or renderer.get("sampleRateHz") != 48000
        or renderer.get("channels") != 2
        or renderer.get("sampleFormat") != "signedPcm16LittleEndian"
        or renderer.get("audioDeviceOpened") is not False
    ):
        _fail("limiterVerificationRuntimeMismatch", "reference-renderer contract differs")
    lifecycle = evidence.get("lifecycle")
    if not isinstance(lifecycle, dict) or lifecycle.get("accepted") is not True:
        _fail("limiterLifecycleVerificationFailed", "source lifecycle was not accepted")
    if lifecycle.get("contract") != "EXACT_AC_OWNER_AND_FMOD_SOURCE_SCHEDULING":
        _fail("limiterLifecycleVerificationFailed", "source lifecycle contract differs")
    pcm = evidence.get("pcm")
    if not isinstance(pcm, dict) or pcm.get("accepted") is not True:
        _fail("limiterPcmVerificationFailed", "source PCM was not accepted")
    try:
        frame_count = int(pcm.get("frameCount"))
        loop_start = int(pcm.get("loopStartFrame"))
        loop_end = int(pcm.get("loopEndFrameExclusive"))
    except (TypeError, ValueError):
        _fail("limiterPcmVerificationFailed", "PCM frame/loop fields are invalid")
    pcm_sha256 = str(pcm.get("pcmPayloadSha256") or "").casefold()
    if (
        frame_count <= 0
        or loop_start < 0
        or loop_end <= loop_start
        or loop_end > frame_count
        or len(pcm_sha256) != 64
        or any(character not in "0123456789abcdef" for character in pcm_sha256)
    ):
        _fail("limiterPcmVerificationFailed", "PCM identity/bounds are invalid")
    disposition = str(pcm.get("audibilityDisposition") or "")
    mode = str(program.get("programMode"))
    if disposition == "AUTHORED_TARGET_SILENT":
        if pcm.get("allPcmSamplesZero") is not True:
            _fail("limiterPcmVerificationFailed", "silent disposition has nonzero PCM")
    elif disposition != "AUDIBLE_TARGET_PCM":
        _fail("limiterPcmVerificationFailed", "unknown PCM audibility disposition")
    elif mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        comparison = pcm.get("adjacentPeriodComparison")
        if not isinstance(comparison, dict):
            _fail("limiterPcmVerificationFailed", "timeline period comparison is absent")
        snr_db = _finite(
            comparison.get("snrDb"),
            code="limiterPcmVerificationFailed",
            detail="timeline period SNR is invalid",
        )
        if comparison.get("bitExact") is not True and snr_db < 50.0:
            _fail("limiterPcmVerificationFailed", "timeline period SNR is below 50 dB")
    elif mode == "PERSISTENT_DECAY_REGION_ONE_SHOT":
        if pcm.get("independentRenderBitExact") is not True:
            _fail("limiterPcmVerificationFailed", "one-shot repeat render is not bit exact")
    elif mode == "PERSISTENT_DECAY_REGION_LOOP":
        comparison = pcm.get("adjacentLoopComparison")
        if not isinstance(comparison, dict):
            _fail("limiterPcmVerificationFailed", "loop comparison is absent")
        snr_db = _finite(
            comparison.get("snrDb"),
            code="limiterPcmVerificationFailed",
            detail="loop SNR is invalid",
        )
        gain_error_db = _finite(
            comparison.get("gainErrorDb"),
            code="limiterPcmVerificationFailed",
            detail="loop gain error is invalid",
        )
        if snr_db < 35.0 or gain_error_db > 0.01:
            _fail("limiterPcmVerificationFailed", "loop PCM exceeds oracle bounds")
    else:
        _fail("invalidLimiterProgram", "unknown limiter program mode")

    evidence_hash = str(evidence.get("verificationPayloadSha256") or "")
    unhashed = copy.deepcopy(evidence)
    unhashed.pop("verificationPayloadSha256", None)
    if evidence_hash != _canonical_json_sha256(unhashed):
        _fail("limiterVerificationHashMismatch", "verification payload hash differs")

    program["fidelity"] = {
        **fidelity,
        "exactnessClaim": True,
        "exactWithinReportedOracleBounds": True,
        "requiredFinalGate": "satisfiedBySourceBoundLimiterVerification",
        "sourceVerificationPayloadSha256": evidence_hash,
    }
    program["verifiedTargetPcm"] = {
        "frameCount": frame_count,
        "pcmPayloadSha256": pcm_sha256,
        "loopStartFrame": loop_start,
        "loopEndFrameExclusive": loop_end,
        "audibilityDisposition": disposition,
    }
    program["sourceVerification"] = evidence
    return program


def derive_windowed_capture_fallback(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    pitch_error_cents: float = _DEFAULT_FALLBACK_PITCH_ERROR_CENTS,
    gain_error_db: float = _DEFAULT_FALLBACK_GAIN_ERROR_DB,
    max_windows: int = _DEFAULT_FALLBACK_MAX_WINDOWS,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Plan a target-only window capture for property-index-1 pitch curves.

    The ordinary manifest schema has no pitch-automation lane.  This API is a
    deliberately narrower escape hatch: it succeeds only when the direct
    compiler's unsupported semantic is source-owned property-index 1 driven by
    the event's native speed axis.  Known gain/fade/trigger automation is
    compiled normally after removing only that pitch controller from a deep
    copy of the graph.  No sample filename is inspected or emitted.

    Returned seed recipes are *oracle probes*, not release permission.  The
    caller must adaptively split RPM intervals and satisfy the returned pitch,
    gain, target-isolation, loop, and lossless-round-trip gates before emitting
    a final manifest window.
    """

    pitch_limit = _finite(
        pitch_error_cents,
        code="invalidFallbackTolerance",
        detail="pitch error tolerance must be finite",
    )
    gain_limit = _finite(
        gain_error_db,
        code="invalidFallbackTolerance",
        detail="gain error tolerance must be finite",
    )
    try:
        window_limit = int(max_windows)
    except (TypeError, ValueError):
        _fail("invalidFallbackTolerance", "maximum windows must be an integer")
    if pitch_limit <= 0.0 or pitch_limit > 50.0:
        _fail("invalidFallbackTolerance", "pitch tolerance must be in (0, 50]")
    if gain_limit <= 0.0 or gain_limit > 3.0:
        _fail("invalidFallbackTolerance", "gain tolerance must be in (0, 3]")
    if window_limit < 1 or window_limit > 256:
        _fail("invalidFallbackTolerance", "maximum windows must be in 1..256")

    direct_error: FmodAuthoredCurveError | None = None
    try:
        derive_manifest_source_curves(
            graph_report,
            source_classification,
            capture_controls,
            interpolation_tolerance=interpolation_tolerance,
        )
    except FmodAuthoredCurveError as exc:
        direct_error = exc
    if direct_error is None:
        _fail(
            "windowedFallbackNotRequired",
            "source compiles directly and must not use a windowed fallback",
        )
    if direct_error.code != "unsupportedPropertyIndex":
        raise direct_error

    if graph_report.get("schema") != BANK_GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {BANK_GRAPH_SCHEMA}")
    source_guid = _guid(source_classification.get("sourceGuid"))
    instruments = _objects_by_guid(graph_report, "instruments")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "classified GUID is not a waveform instrument")
    chain = _instrument_chain(source_guid, instruments)
    chain_guids = {guid for guid, _node, _depth in chain}
    referenced = {
        _guid(controller_guid)
        for _guid_value, node, _depth in chain
        for controller_guid in (
            node.get("controllerGuids")
            if isinstance(node.get("controllerGuids"), list)
            else _fail(
                "invalidGraph",
                f"instrument {_guid_value} controllerGuids is invalid",
            )
        )
    }
    controllers = _objects_by_guid(graph_report, "controllers")
    if referenced - controllers.keys():
        _fail(
            "missingController",
            f"missing controllers: {sorted(referenced - controllers.keys())}",
        )

    event = _event_for_source(graph_report, source_guid, source_classification)
    suffix = _event_suffix(event.get("path"))
    speed_parameter = (
        _DRIVETRAIN_SPEED_PARAMETER
        if suffix == _TRANSMISSION_SUFFIX
        else _RPM_PARAMETER
    )
    speed_to_rpm = (
        _RADIANS_PER_SECOND_TO_RPM
        if speed_parameter == _DRIVETRAIN_SPEED_PARAMETER
        else 1.0
    )
    parameters = _objects_by_guid(graph_report, "parameters")
    property_one_guids: set[str] = set()
    property_one_evidence: list[dict[str, Any]] = []
    seed_native_values: set[float] = set()
    raw_pitch_values: list[float] = []
    speed_parameter_guid = ""
    for controller_guid in sorted(referenced):
        controller = controllers[controller_guid]
        try:
            property_index = int(controller.get("propertyIndex"))
        except (TypeError, ValueError):
            _fail(
                "unsupportedPropertyIndex",
                f"controller {controller_guid} property index is invalid",
            )
        if property_index != 1:
            continue
        property_one_guids.add(controller_guid)
        if _guid(controller.get("propertyOwnerGuid")) != source_guid:
            _fail(
                "unsupportedPropertyOneOwner",
                f"controller {controller_guid} is not owned by the waveform source",
            )
        if str(controller.get("inputKind") or "").casefold() != "parameter":
            _fail(
                "unsupportedControllerInput",
                f"controller {controller_guid} is not parameter-driven",
            )
        parameter_name = str(
            controller.get("inputParameterName") or ""
        ).casefold()
        if parameter_name != speed_parameter:
            _fail(
                "unsupportedPropertyOneParameter",
                f"controller {controller_guid} maps {parameter_name or 'unnamed'} instead of {speed_parameter}",
            )
        parameter_guid = _guid(controller.get("inputParameterGuid"))
        parameter = parameters.get(parameter_guid)
        if parameter is None:
            _fail(
                "unsupportedParameterTopology",
                f"controller {controller_guid} parameter definition is absent",
            )
        if str(parameter.get("name") or "").casefold() != speed_parameter:
            _fail(
                "unsupportedParameterTopology",
                f"controller {controller_guid} parameter name/GUID disagree",
            )
        if str(parameter.get("type") or "") != "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED":
            _fail(
                "unsupportedPropertyOneParameter",
                f"controller {controller_guid} speed input is not game-controlled",
            )
        if speed_parameter_guid and speed_parameter_guid != parameter_guid:
            _fail(
                "unsupportedParameterTopology",
                f"property-index-1 controllers use multiple {speed_parameter} GUIDs",
            )
        speed_parameter_guid = parameter_guid
        minimum = _finite(
            parameter.get("minimum"),
            code="unsupportedParameterTopology",
            detail=f"{speed_parameter} minimum is invalid",
        )
        maximum = _finite(
            parameter.get("maximum"),
            code="unsupportedParameterTopology",
            detail=f"{speed_parameter} maximum is invalid",
        )
        if maximum <= minimum:
            _fail("unsupportedParameterTopology", f"{speed_parameter} domain is empty")
        points, controller_seeds = _property_one_points(controller)
        if any(value < minimum or value > maximum for value in controller_seeds):
            _fail(
                "unsupportedPropertyOneCurve",
                f"controller {controller_guid} has points outside {minimum}..{maximum}",
            )
        seed_native_values.update(controller_seeds)
        raw_pitch_values.extend(point["rawValue"] for point in points)
        property_one_evidence.append(
            {
                "controllerGuid": controller_guid,
                "ownerInstrumentGuid": source_guid,
                "parameter": speed_parameter,
                "parameterGuid": parameter_guid,
                "propertyIndex": 1,
                "rawUnits": "undocumentedFmod108PropertyIndex1",
                "points": points,
            }
        )
    if not property_one_guids:
        raise direct_error

    projected_report = copy.deepcopy(graph_report)
    projected_report["controllers"] = [
        item
        for item in projected_report.get("controllers", [])
        if _guid(item.get("guid")) not in property_one_guids
    ]
    for item in projected_report.get("instruments", []):
        if _guid(item.get("guid")) not in chain_guids:
            continue
        item["controllerGuids"] = [
            guid
            for guid in item.get("controllerGuids", [])
            if _guid(guid) not in property_one_guids
        ]
    projection = derive_manifest_source_curves(
        projected_report,
        source_classification,
        capture_controls,
        interpolation_tolerance=interpolation_tolerance,
    )

    # Gain/fade knots and trigger bounds are deterministic probe boundaries.
    # Dense linearization points are intentionally not copied into the seed
    # set; adaptive oracle splitting owns the additional probes.
    for controller in controllers.values():
        if _guid(controller.get("guid")) not in referenced:
            continue
        if int(controller.get("propertyIndex", -1)) not in {0, 4}:
            continue
        if str(controller.get("inputParameterName") or "").casefold() != speed_parameter:
            continue
        for point in controller.get("points", []):
            if isinstance(point, dict):
                seed_native_values.add(
                    _finite(
                        point.get("x"),
                        code="unsupportedCurve",
                        detail="gain-controller seed x is invalid",
                    )
                )
    for placement in projection["triggerPlacements"].get(speed_parameter, []):
        seed_native_values.update((float(placement["start"]), float(placement["end"])))
    seed_native_values.add(
        float(projection["captureRootRpm"]) / speed_to_rpm
    )
    seed_native_values = {
        value for value in seed_native_values if math.isfinite(value) and value > 0.0
    }
    ordered_native_seeds = sorted(seed_native_values)
    if len(ordered_native_seeds) < 2:
        _fail(
            "insufficientFallbackSeeds",
            "property-index-1 fallback requires at least two positive speed probes",
        )
    bank = graph_report.get("bank")
    if not isinstance(bank, dict):
        _fail("invalidGraph", "bank identity is absent")
    bank_sha256 = str(bank.get("sha256") or "").casefold()
    if len(bank_sha256) != 64 or any(character not in "0123456789abcdef" for character in bank_sha256):
        _fail("invalidGraph", "bank SHA-256 identity is invalid")
    try:
        bank_file_version = int(bank.get("fileVersion"))
    except (TypeError, ValueError):
        _fail("invalidGraph", "bank file version is invalid")

    override_values = {
        str(key).casefold(): float(value)
        for key, value in (capture_controls or {}).items()
    }
    renderer_geometry = {
        name: round(
            float(
                projection["captureAutomaticParameterValues"].get(
                    name,
                    override_values.get(name, DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS[name]),
                )
            ),
            8,
        )
        for name in sorted(_FIXED_AUTOMATIC_PARAMETERS)
    }
    seed_recipes: list[dict[str, Any]] = []
    for index, native_value in enumerate(ordered_native_seeds):
        root_rpm = native_value * speed_to_rpm
        recipe_payload = {
            "schema": TARGET_ONLY_CAPTURE_RECIPE_SCHEMA,
            "bankGraphIdentity": {
                "schema": BANK_GRAPH_SCHEMA,
                "bankSha256": bank_sha256,
                "bankFileVersion": bank_file_version,
            },
            "sourceGuid": source_guid,
            "eventPath": projection["eventPath"],
            "purpose": "adaptiveOracleProbeOrFinalWindowReference",
            "targetOnlyScheduling": {
                "mode": "waveformSourceGuid",
                "scheduledSourceGuids": [source_guid],
                "rejectAnyAdditionalScheduledSource": True,
            },
            "captureParameterValues": {
                speed_parameter: round(native_value, 8),
                _THROTTLE_PARAMETER: projection["captureThrottle"],
            },
            "rendererGeometryAutomaticParameterValues": renderer_geometry,
            "captureRootRpm": round(root_rpm, 8),
            "referenceRenderer": {
                "runtime": _REFERENCE_RENDERER_VERSION,
                "mode": "WAVWRITER_NRT",
                "opensAudioOutputDevice": False,
                "warmupFrames": 96000,
                "analysisFrames": 384000,
            },
            "pcmOutput": {
                "sampleRateHz": 48000,
                "channelCount": 2,
                "channelLayout": "stereo",
                "sampleFormat": "signedPcm16LittleEndian",
                "interleaving": "interleaved",
            },
        }
        seed_recipes.append(
            {
                "recipeId": f"{source_guid}:rpm-window-seed:{index:03d}",
                "recipePayload": recipe_payload,
                "captureRecipeSha256": _canonical_json_sha256(recipe_payload),
            }
        )

    seed_rpm_values = [round(value * speed_to_rpm, 8) for value in ordered_native_seeds]
    positive_curve_indices = [
        index
        for index, (_rpm, amplitude) in enumerate(projection["rpmCurve"])
        if float(amplitude) > 0.0
    ]
    if not positive_curve_indices:
        _fail("silentSource", "projected authored RPM gain is identically zero")
    first_positive = positive_curve_indices[0]
    last_positive = positive_curve_indices[-1]
    coverage_start_index = max(0, first_positive - 1)
    coverage_end_index = min(len(projection["rpmCurve"]) - 1, last_positive + 1)
    coverage_start_rpm = float(projection["rpmCurve"][coverage_start_index][0])
    coverage_end_rpm = float(projection["rpmCurve"][coverage_end_index][0])
    partition_boundaries = sorted(
        {
            round(value, 8)
            for value in (
                coverage_start_rpm,
                coverage_end_rpm,
                *seed_rpm_values,
            )
            if coverage_start_rpm <= value <= coverage_end_rpm
        }
    )
    if len(partition_boundaries) < 2:
        _fail(
            "insufficientFallbackSeeds",
            "property-index-1 fallback has no nonempty audible RPM partition",
        )
    if len(partition_boundaries) - 1 > window_limit:
        _fail(
            "fallbackSeedLimitExceeded",
            f"{len(partition_boundaries) - 1} initial windows exceed limit {window_limit}",
        )
    output = {
        "schema": WINDOWED_CAPTURE_FALLBACK_SCHEMA,
        "kind": "targetOnlyAdaptiveRpmWindows",
        "reason": "propertyIndex1PitchAutomationHasNoManifestPitchCurve",
        "sourceGuid": source_guid,
        "eventPath": projection["eventPath"],
        "role": projection["role"],
        "nativeSpeedParameter": speed_parameter,
        "nativeSpeedToRpmScale": speed_to_rpm,
        "autoPitchReferenceRpm": projection["autoPitchReferenceRpm"],
        "captureRootRpm": projection["captureRootRpm"],
        "captureThrottle": projection["captureThrottle"],
        "captureParameterValues": projection["captureParameterValues"],
        "captureAutomaticParameterValues": projection[
            "captureAutomaticParameterValues"
        ],
        "rpmCurve": projection["rpmCurve"],
        "gainCurve": projection["gainCurve"],
        "manifestGainProjection": {
            "excludesOnlyPropertyIndex1PitchAutomation": True,
            "baseGain": projection["baseGain"],
            "triggerPlacements": projection["triggerPlacements"],
            "controllers": projection["controllers"],
            "normalization": projection["normalization"],
            "fidelity": projection["fidelity"],
            "unsupported": projection["unsupported"],
        },
        "directFailure": {
            "code": direct_error.code,
            "detail": direct_error.detail,
        },
        "propertyOneControllers": property_one_evidence,
        "authoredRawPitchSpan": max(raw_pitch_values) - min(raw_pitch_values),
        "seedRpmValues": seed_rpm_values,
        "seedCaptureRecipes": seed_recipes,
        "initialPartitionBoundariesRpm": partition_boundaries,
        "adaptiveOracleGate": {
            "measurement": "targetOnlyLongWindowRelativeLogSpectralPitchAndRms",
            "initialIntervalsRpm": [
                [left, right]
                for left, right in zip(
                    partition_boundaries, partition_boundaries[1:]
                )
            ],
            "probePositionsPerSupport": [
                "supportStart",
                "eachAuthoredKnot",
                "quarter",
                "midpoint",
                "threeQuarter",
                "supportEnd",
            ],
            "windowReferenceSelection": "maximumProjectedRpmGainOverExpandedSupport; tie=nearestCellMidpointThenLowestPositiveRpm",
            "runtimePitchPrediction": "actualNativeCubicVarispeedRenderOfReferencePcmAtProbeRpm",
            "runtimeGainPrediction": "actualNativeMixerRenderUsingFinalPerWindowRpmCurve",
            "splitEachWindowUntilPitchErrorCentsAtMost": pitch_limit,
            "splitEachWindowUntilGainErrorDbAtMost": gain_limit,
            "maximumFinalWindows": window_limit,
            "splitRule": "splitAtInteriorProbeWithLargestNormalizedBoundViolation; tie=lowestRpm; otherwiseArithmeticMidpoint",
            "newRecipeRule": "cloneNearestSeedRecipePayloadReplaceNativeSpeedAndCaptureRootThenRehash",
            "onUnstablePitchEstimate": "failClosedDoNotReleaseSource",
            "onWindowLimitExceeded": "failClosedDoNotReleaseSource",
        },
        "manifestWindowRealization": {
            "partition": "contiguousAudibleCellsFromFinalAdaptiveBoundaries",
            "silentCellRule": "omitOnlyWhenProjectedRpmGainMaximumIsExactlyZero",
            "crossfade": {
                "kind": "twoTrackEqualPower",
                "interiorBoundaryHalfWidthRpm": "0.25*min(leftCellWidthRpm,rightCellWidthRpm)",
                "leftEnvelope": "cos(pi*t/2)",
                "rightEnvelope": "sin(pi*t/2)",
                "tDomain": "0AtBoundaryMinusHalfWidthTo1AtBoundaryPlusHalfWidth",
                "maximumSimultaneousTracks": 2,
                "noHardSwitchAtAudibleInteriorBoundary": True,
            },
            "expandedTrackSupport": "cellPlusAdjacentHalfCrossfadeBands",
            "referenceRootRpm": "positiveRpmMaximizingProjectedRpmGainAcrossExpandedTrackSupport; tie=nearestCellMidpointThenLowestRpm",
            "perTrackRpmCurveFunction": "equalPowerEnvelope(rpm)*projectedRpmGain(rpm)/projectedRpmGain(referenceRootRpm)",
            "perTrackGainCurve": "copyTopLevelGainCurveUnchanged",
            "normalizationInvariant": "referenceRootIsSupportGainMaximumSoEveryPerTrackRpmCurveValueIsIn0To1",
            "linearization": {
                "method": "deterministicAdaptivePiecewiseLinear",
                "absoluteAmplitudeTolerance": interpolation_tolerance,
                "denseValidationProbesPerSegment": _VALIDATION_PROBES_PER_SEGMENT,
            },
            "overlapAcceptance": {
                "render": "actualNativeMixerOfBothWindowTracksAgainstTargetOnlyFmodAtQuarterMidAndThreeQuarterCrossfade",
                "pitchErrorCentsAtMost": pitch_limit,
                "gainErrorDbAtMost": gain_limit,
                "failureAction": "splitAdjacentCellOrFailClosedAtWindowLimit",
            },
        },
        "pitchMeasurementContract": {
            "input": "384000PostWarmupPcmFramesPerProbeAt48000HzStereo",
            "channelSelection": "dcRemoveEachChannelThenUseHigherRmsChannel",
            "spectrum": {
                "window": "hann32768Frames",
                "hopFrames": 8192,
                "aggregation": "medianPowerPerBinAcrossWindows",
                "frequencyRangeHz": [40.0, 12000.0],
            },
            "estimator": "normalizedCrossCorrelationOfOneCentLogFrequencyMedianSpectraRelativeToWindowReference",
            "search": "plusOrMinus240CentsAroundOrdinaryRpmRatioPredictionWithParabolicPeakRefinement",
            "confidence": {
                "minimumActiveSpectralBins": 24,
                "minimumNormalizedCorrelation": 0.6,
                "minimumBestToSecondPeakSeparation": 0.05,
                "maximumEightWayBootstrapMadCents": 1.5,
            },
            "rms": "sqrt(mean((leftDcRemoved^2+rightDcRemoved^2)/2))OverAllAnalysisFrames",
            "stationarity": "32BlockRmsMadAtMost0.5DbOtherwiseExtendOnceThenFailClosed",
            "pitchError": "abs(1200*log2(measuredTargetPitchScale/measuredRuntimePitchScale))",
            "singleFundamentalRequired": False,
            "failureAction": "failClosedDoNotReleaseSource",
        },
        "captureVerificationContract": {
            "canonicalRecipeJson": {
                "encoding": "utf-8",
                "ensureAscii": False,
                "sortKeys": True,
                "separators": [",", ":"],
                "allowNan": False,
                "hash": "sha256",
                "hashInput": "recipePayloadOnly",
                "requiredField": "captureRecipeSha256",
            },
            "targetIsolation": {
                "requiredScheduledSourceGuids": [source_guid],
                "rejectAnyAdditionalScheduledSource": True,
                "sampleNamesMayBeUsedForIdentity": False,
            },
            "pcmIdentity": {
                "requiredFormat": "interleavedSignedPcm16LittleEndian48000HzStereo",
                "hash": "sha256",
                "hashInput": "rawInterleavedPcmPayloadWithoutContainerHeader",
                "requiredFields": [
                    "frameCount",
                    "pcmPayloadSha256",
                    "loopStartFrame",
                    "loopEndFrameExclusive",
                ],
                "loopBoundsRule": "0<=start<endExclusive<=frameCount",
            },
            "losslessStorage": {
                "codec": "FLAC",
                "compressionLevel": 8,
                "decodeMustMatchPcmPayloadSha256": True,
                "decodeMustMatchFrameCount": True,
                "loopEndSemantics": "exclusive",
                "decodeMismatchAction": "failClosedDoNotReleaseWindow",
            },
            "oracleAcceptance": {
                "requiredAt": ["everyFinalWindowEndpoint", "everyFinalWindowMidpoint"],
                "pitchErrorCentsAtMost": pitch_limit,
                "gainErrorDbAtMost": gain_limit,
                "targetOnlyRuntimeVersion": _REFERENCE_RENDERER_VERSION,
                "acceptanceFailureAction": "splitOrFailClosed",
            },
        },
        "fidelity": {
            "sampleNamesUsed": False,
            "exactnessClaim": False,
            "unsupportedPropertyIndexInterpretedStatically": False,
            "requiredFinalGate": "targetOnlyFmod108AdaptiveWindowOracleAndBitExactFlacRoundTrip",
        },
    }
    return output


def derive_manifest_engine_curves(
    graph_report: dict[str, Any],
    source_classification: dict[str, Any],
    capture_controls: Mapping[str, float] | None = None,
    *,
    interpolation_tolerance: float = _DEFAULT_INTERPOLATION_TOLERANCE,
) -> dict[str, Any]:
    """Backward-compatible name for :func:`derive_manifest_source_curves`."""

    return derive_manifest_source_curves(
        graph_report,
        source_classification,
        capture_controls,
        interpolation_tolerance=interpolation_tolerance,
    )


def realize_windowed_capture_fallback(
    fallback_plan: dict[str, Any],
    render_callback: Callable[[dict[str, Any]], Mapping[str, Any]],
) -> dict[str, Any]:
    """Lazy compatibility export for the PCM-oracle realization component."""

    from .fmod_windowed_capture import realize_windowed_capture_fallback as realize

    return realize(fallback_plan, render_callback)


__all__ = [
    "AUTHORED_CURVE_SCHEMA",
    "DEFAULT_CAPTURE_AUTOMATIC_PARAMETERS",
    "ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION",
    "ENGINE_TRANSIENT_VERIFICATION_SCHEMA",
    "FmodAuthoredCurveError",
    "LIMITER_PROGRAM_SCHEMA",
    "LIMITER_VERIFICATION_SCHEMA",
    "ONE_SHOT_CURVE_SCHEMA",
    "TARGET_ONLY_CAPTURE_RECIPE_SCHEMA",
    "TURBO_CONTROL_GAIN_ORACLE_VERSION",
    "TURBO_PCM_CAPTURE_ORACLE_VERSION",
    "TURBO_TRANSIENT_SOURCE_SCHEMA",
    "TURBO_TRANSIENT_VERIFICATION_SCHEMA",
    "WINDOWED_CAPTURE_FALLBACK_SCHEMA",
    "certify_manifest_limiter_program",
    "certify_manifest_engine_transient_source",
    "certify_manifest_turbo_transient_source",
    "derive_manifest_engine_curves",
    "derive_manifest_limiter_program",
    "derive_manifest_one_shot_curves",
    "derive_manifest_source_curves",
    "derive_manifest_turbo_transient_source",
    "derive_windowed_capture_fallback",
    "evaluate_authored_curve",
    "evaluate_type0_curve",
    "fmod108_volume_automation_linear",
    "realize_windowed_capture_fallback",
]
