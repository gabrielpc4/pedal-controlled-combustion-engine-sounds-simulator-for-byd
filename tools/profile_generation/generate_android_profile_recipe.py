#!/usr/bin/env python3
"""Translate one FMOD bank graph into an auditable Android WAV-profile recipe.

The input is the parser's ``ac-fmod-bank-graph-audit-v3`` JSON plus the
``GUIDs.txt`` shipped beside the car bank.  Classification never relies on an
embedded sample name: event membership, parameter placements, loop lifetime,
and authored automation decide the role.  Sample names are retained only as
diagnostic evidence for a human listening pass.

This tool deliberately emits a recipe before it emits Kotlin.  The recipe is a
stable boundary between bank analysis/WAV extraction and the Android runtime,
and it records every construct that the current ``EngineSampleProfile`` model
cannot reproduce exactly.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import copy
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any, Callable, Iterable, Mapping, Sequence


GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
RECIPE_SCHEMA = "byd-fmod-android-profile-recipe-v1"
GENERATOR_VERSION = 1

ENGINE_EVENT_PERSPECTIVE = {
    "engine_int": "cabin",
    "engine_ext": "exterior",
}
NON_POWERTRAIN_EVENT_TOKENS = {
    "bodywork": "vehicle body impact/noise",
    "chassis": "vehicle body/chassis noise",
    "collision": "collision noise",
    "crash": "collision noise",
    "door": "vehicle body control",
    "gravel": "road-surface noise",
    "horn": "vehicle UI/accessory sound",
    "kerb": "road-surface noise",
    "skid": "tyre/road noise",
    "suspension": "road/body suspension noise",
    "tyre": "tyre/road noise",
    "tire": "tyre/road noise",
    "wheel": "wheel/road noise",
    "wind": "environmental wind noise",
    "wiper": "vehicle UI/accessory sound",
}
AUTOMATIC_PARAMETER_VALUES = {
    "distance": 0.2,
    "event cone angle": 90.0,
}
CURVE_EXPONENTIAL_SHAPE_SCALE = 6.9522
CURVE_SHAPE_EPSILON = 1.0e-7
THROTTLE_ROLE_EPSILON_DB = 0.25
CURVE_FLATTEN_TOLERANCE_DB = 0.05
MINIMUM_LINEAR_AMPLITUDE = 1.0e-6
PLAYLIST_SELECTION_SCHEMA = "byd-full-event-playlist-selection-v1"


class ProfileRecipeError(ValueError):
    """An input cannot be represented by a trustworthy recipe."""


@dataclass(frozen=True)
class IndexedGraph:
    graph: dict[str, Any]
    instruments: dict[str, dict[str, Any]]
    controllers: dict[str, dict[str, Any]]
    parameters: dict[str, dict[str, Any]]
    parents: dict[str, tuple[str, ...]]
    modulators_by_owner: dict[str, tuple[dict[str, Any], ...]]


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
        allow_nan=False,
    ).encode("ascii")


def _guid(value: Any) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _event_suffix(path: Any) -> str:
    value = str(path or "").strip().casefold().rstrip("/")
    return value.rsplit("/", 1)[-1] if value else ""


def _event_scope(suffix: str) -> tuple[str, str]:
    """Conservatively keep every event except explicit non-powertrain families."""

    normalized = suffix.casefold()
    for token, reason in NON_POWERTRAIN_EVENT_TOKENS.items():
        if token in normalized:
            return "excludedNonPowertrain", reason
    return "coreMechanical", "not an explicit road/environment/body/UI event"


_LAB_SESSION_EVENT_SUFFIXES = frozenset(
    {
        "engine_int",
        "engine_ext",
        "gear_int",
        "gear_ext",
        "transmission",
        "turbo",
        "limiter",
        "backfire_int",
        "backfire_ext",
        "start",
        "transmission_ext",
        "tractioncontrol_int",
        "tractioncontrol_ext",
        "gear_grind",
    }
)


def _host_event_ownership_evidence(suffix: str) -> dict[str, str]:
    """Classify an authored event against the actual Lab session call sites.

    Graph reachability says that a waveform belongs to an FMOD event; it does
    not say that the selected Assetto/Audio Lab host ever creates that event.
    Keep host-unreachable mechanical paths in conservation evidence, but do
    not emit/capture them as Android playback programs.  An unknown ownership
    proof is deliberately distinct from this static-only classification and
    remains release-blocking when introduced.
    """

    evidence = "assetto_corsa_audio_lab/sim/fmod_native.py:CORE_EVENT_NAMES"
    if suffix not in _LAB_SESSION_EVENT_SUFFIXES:
        return {
            "schema": "byd-fmod-host-event-ownership-v1",
            "status": "staticOnlyHostUnreachable",
            "evidence": evidence,
            "reason": "eventPathIsInBankButNoNativeFmodAudioSessionInstanceCallsite",
            "runtimeDelivery": "conservationOnlyNoNrtCaptureOrAndroidPlayback",
        }
    if suffix in {"engine_int", "engine_ext"}:
        return {
            "schema": "byd-fmod-host-event-ownership-v1",
            "status": "instantiated",
            "evidence": evidence,
            "instanceScope": "selectedPerspectiveEngineEventPath",
            "runtimeDelivery": "atlasRuntime",
        }
    return {
        "schema": "byd-fmod-host-event-ownership-v1",
        "status": "instantiated",
        "evidence": evidence,
        "instanceScope": "profileAudioSessionExactEventPath",
        "runtimeDelivery": "atlasRuntime",
    }


def _finite(value: Any, description: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ProfileRecipeError(f"{description} is not numeric") from exc
    if not math.isfinite(result):
        raise ProfileRecipeError(f"{description} is not finite")
    return result


def _slug(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", value.casefold()).strip("_")
    if not normalized:
        raise ProfileRecipeError("car id has no usable characters")
    return normalized


def parse_guid_paths(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, line in enumerate(text.splitlines(), 1):
        match = re.match(r"^\s*\{([^}]+)\}\s+(\S.*?)\s*$", line)
        if not match:
            continue
        guid = _guid(match.group(1))
        path = match.group(2)
        previous = result.get(guid)
        if previous is not None and previous != path:
            raise ProfileRecipeError(
                f"GUIDs.txt line {line_number} maps {guid} to two paths"
            )
        result[guid] = path
    return result


def graph_with_guid_paths(
    graph: Mapping[str, Any], guid_paths: Mapping[str, str]
) -> dict[str, Any]:
    if (
        graph.get("schema") != GRAPH_SCHEMA
        and graph.get("sourceSchema") != GRAPH_SCHEMA
    ):
        raise ProfileRecipeError(
            f"expected {GRAPH_SCHEMA}, got {graph.get('schema')!r}"
        )
    enriched = copy.deepcopy(dict(graph))
    if enriched.get("schema") != GRAPH_SCHEMA:
        enriched["workGraphSchema"] = enriched.get("schema")
        enriched["schema"] = GRAPH_SCHEMA
    events = enriched.get("events")
    if not isinstance(events, list):
        raise ProfileRecipeError("graph events must be an array")
    retained_events: list[dict[str, Any]] = []
    non_event_objects: list[dict[str, Any]] = []
    for event in events:
        if not isinstance(event, dict):
            raise ProfileRecipeError("graph event must be an object")
        guid = _guid(event.get("guid"))
        path = str(event.get("path") or guid_paths.get(guid) or "")
        if path.startswith("snapshot:/") or (
            not path and not event.get("reachableInstrumentGuids")
        ):
            if event.get("reachableInstrumentGuids"):
                raise ProfileRecipeError(
                    f"non-event object {guid or '<missing>'} reaches waveform sources"
                )
            non_event_objects.append({"guid": guid, "path": path})
            continue
        if not path.startswith("event:/"):
            raise ProfileRecipeError(f"event {guid or '<missing>'} has no GUID path")
        event["path"] = path
        retained_events.append(event)
    enriched["events"] = retained_events
    if non_event_objects:
        enriched["nonEventObjectsExcludedFromCarAudio"] = non_event_objects
    return enriched


def _objects_by_guid(graph: Mapping[str, Any], key: str) -> dict[str, dict[str, Any]]:
    raw = graph.get(key)
    if not isinstance(raw, list):
        raise ProfileRecipeError(f"graph {key} must be an array")
    result: dict[str, dict[str, Any]] = {}
    for item in raw:
        if not isinstance(item, dict):
            raise ProfileRecipeError(f"graph {key} contains a non-object")
        guid = _guid(item.get("guid"))
        if not guid:
            raise ProfileRecipeError(f"graph {key} contains an object without a GUID")
        if guid in result:
            raise ProfileRecipeError(f"graph {key} duplicates GUID {guid}")
        result[guid] = item
    return result


def index_graph(graph: dict[str, Any]) -> IndexedGraph:
    instruments = _objects_by_guid(graph, "instruments")
    controllers = _objects_by_guid(graph, "controllers")
    parameters = _objects_by_guid(graph, "parameters")
    parents_mutable: dict[str, set[str]] = defaultdict(set)
    for parent_guid, instrument in instruments.items():
        children = instrument.get("childInstruments", [])
        if not isinstance(children, list):
            raise ProfileRecipeError(f"instrument {parent_guid} children are invalid")
        if instrument.get("kind") in {"MultiInstrumentNode", "ScattererInstrumentNode"}:
            authored_orders: list[int] = []
            for child_index, child in enumerate(children):
                if not isinstance(child, Mapping):
                    raise ProfileRecipeError(
                        f"playlist instrument {parent_guid} child {child_index} is invalid"
                    )
                raw_order = child.get("authoredOrder")
                if (
                    isinstance(raw_order, bool)
                    or not isinstance(raw_order, int)
                    or raw_order < 0
                ):
                    raise ProfileRecipeError(
                        "playlist graph lacks explicit authoredOrder; regenerate the bank graph audit"
                    )
                authored_orders.append(raw_order)
            if sorted(authored_orders) != list(range(len(children))):
                raise ProfileRecipeError(
                    f"playlist instrument {parent_guid} authoredOrder values are not contiguous"
                )
        for child in children:
            child_guid = _guid(child.get("guid")) if isinstance(child, dict) else _guid(child)
            if child_guid:
                parents_mutable[child_guid].add(parent_guid)
    modulators_mutable: dict[str, list[dict[str, Any]]] = defaultdict(list)
    modulators = graph.get("modulators", [])
    if not isinstance(modulators, list):
        raise ProfileRecipeError("graph modulators must be an array")
    for modulator in modulators:
        if not isinstance(modulator, dict):
            raise ProfileRecipeError("graph modulator must be an object")
        modulators_mutable[_guid(modulator.get("ownerGuid"))].append(modulator)
    return IndexedGraph(
        graph=graph,
        instruments=instruments,
        controllers=controllers,
        parameters=parameters,
        parents={key: tuple(sorted(value)) for key, value in parents_mutable.items()},
        modulators_by_owner={
            key: tuple(sorted(value, key=lambda item: _guid(item.get("guid"))))
            for key, value in modulators_mutable.items()
        },
    )


def _instrument_chain(indexed: IndexedGraph, source_guid: str) -> list[tuple[str, dict[str, Any]]]:
    result: list[tuple[str, dict[str, Any]]] = []
    visited: set[str] = set()
    current = source_guid
    while current:
        if current in visited:
            raise ProfileRecipeError(f"instrument ancestry cycles at {current}")
        visited.add(current)
        instrument = indexed.instruments.get(current)
        if instrument is None:
            raise ProfileRecipeError(f"instrument ancestry is missing {current}")
        result.append((current, instrument))
        parents = indexed.parents.get(current, ())
        if len(parents) > 1:
            raise ProfileRecipeError(
                f"instrument {current} has multiple parent paths: {parents}"
            )
        current = parents[0] if parents else ""
    return result


def _chain_owner_guids(chain: Sequence[tuple[str, dict[str, Any]]]) -> set[str]:
    owners: set[str] = set()
    for guid, instrument in chain:
        owners.add(guid)
        properties = instrument.get("baseProperties")
        if isinstance(properties, dict):
            routable = _guid(properties.get("routableGuid"))
            if routable:
                owners.add(routable)
    return owners


def _chain_controllers(
    indexed: IndexedGraph, chain: Sequence[tuple[str, dict[str, Any]]]
) -> list[dict[str, Any]]:
    owners = _chain_owner_guids(chain)
    referenced: set[str] = set()
    for instrument_guid, instrument in chain:
        raw = instrument.get("controllerGuids", [])
        if not isinstance(raw, list):
            raise ProfileRecipeError(
                f"instrument {instrument_guid} controllerGuids is invalid"
            )
        referenced.update(_guid(item) for item in raw if _guid(item))
    missing = sorted(referenced - indexed.controllers.keys())
    if missing:
        raise ProfileRecipeError(f"instrument chain references missing controllers {missing}")
    result = [indexed.controllers[guid] for guid in sorted(referenced)]
    mismatched = [
        _guid(controller.get("guid"))
        for controller in result
        if _guid(controller.get("propertyOwnerGuid")) not in owners
    ]
    if mismatched:
        raise ProfileRecipeError(f"controller owners do not belong to chain: {mismatched}")
    return result


def _curve_points(controller: Mapping[str, Any]) -> list[dict[str, Any]]:
    controller_guid = _guid(controller.get("guid"))
    raw = controller.get("points")
    if not isinstance(raw, list) or not raw:
        raise ProfileRecipeError(f"controller {controller_guid} has no points")
    parsed: list[tuple[int, float, float, float, int]] = []
    for point_index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ProfileRecipeError(
                f"controller {controller_guid} point {point_index} is invalid"
            )
        x = _finite(item.get("x"), f"controller {controller_guid} x")
        output = _finite(item.get("y"), f"controller {controller_guid} y")
        shape = _finite(item.get("shape", 0.0), f"controller {controller_guid} shape")
        try:
            interpolation_type = int(item.get("type", 0))
        except (TypeError, ValueError) as exc:
            raise ProfileRecipeError(
                f"controller {controller_guid} interpolation type is invalid"
            ) from exc
        if interpolation_type not in (0, 1):
            raise ProfileRecipeError(
                f"controller {controller_guid} uses interpolation type {interpolation_type}"
            )
        parsed.append((point_index, x, output, shape, interpolation_type))
    # FMOD banks can repeat a knot to carry a distinct outgoing handle.  The
    # curve domain is parameter-keyed, not serialization-order keyed: sort by
    # x and retain the final record at a duplicate x, whose handle controls the
    # following non-zero segment.  These points are source-role evidence only;
    # release audio is the full-event NRT atlas and never this collapsed curve.
    result: list[dict[str, Any]] = []
    for _point_index, x, output, shape, interpolation_type in sorted(
        parsed, key=lambda item: (item[1], item[0])
    ):
        point = {
            "input": x,
            "output": output,
            "shape": shape,
            "interpolationType": interpolation_type,
        }
        if result and x == result[-1]["input"]:
            result[-1] = point
        else:
            result.append(point)
    return result


def _interpolation_fraction(fraction: float, shape: float, interpolation_type: int) -> float:
    if interpolation_type == 0:
        exponent = shape * CURVE_EXPONENTIAL_SHAPE_SCALE
        if abs(exponent) < CURVE_SHAPE_EPSILON:
            return fraction
        return math.expm1(exponent * fraction) / math.expm1(exponent)
    exponent = 1.0 + 2.0 * abs(shape)
    if shape >= 0.0:
        if fraction <= 0.5:
            return 0.5 * (2.0 * fraction) ** exponent
        return 1.0 - 0.5 * (2.0 * (1.0 - fraction)) ** exponent
    if fraction <= 0.5:
        return 0.5 * (1.0 - (1.0 - 2.0 * fraction) ** exponent)
    return 0.5 + 0.5 * (2.0 * fraction - 1.0) ** exponent


def evaluate_curve(points: Sequence[Mapping[str, Any]], value: float) -> float:
    if value <= float(points[0]["input"]):
        return float(points[0]["output"])
    if value >= float(points[-1]["input"]):
        return float(points[-1]["output"])
    for left, right in zip(points, points[1:]):
        left_x = float(left["input"])
        right_x = float(right["input"])
        if value <= right_x:
            fraction = (value - left_x) / (right_x - left_x)
            shaped = _interpolation_fraction(
                fraction,
                float(left.get("shape", 0.0)),
                int(left.get("interpolationType", 0)),
            )
            return float(left["output"]) + (
                float(right["output"]) - float(left["output"])
            ) * shaped
    return float(points[-1]["output"])


def _parameter_name(controller: Mapping[str, Any]) -> str:
    return str(controller.get("inputParameterName") or "").casefold()


def _property_index(controller: Mapping[str, Any]) -> int:
    try:
        return int(controller.get("propertyIndex"))
    except (TypeError, ValueError) as exc:
        raise ProfileRecipeError(
            f"controller {_guid(controller.get('guid'))} has an invalid property index"
        ) from exc


def _curve_document(controller: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "controllerGuid": _guid(controller.get("guid")),
        "points": _curve_points(controller),
    }


def _event_parameter_domain(
    indexed: IndexedGraph, event: Mapping[str, Any], parameter_name: str
) -> tuple[float, float]:
    candidates: list[tuple[float, float]] = []
    layouts = event.get("parameterLayoutGuids", [])
    if not isinstance(layouts, list):
        raise ProfileRecipeError(f"event {event.get('path')} parameter layouts are invalid")
    for raw_guid in layouts:
        parameter = indexed.parameters.get(_guid(raw_guid))
        if parameter is None:
            continue
        if str(parameter.get("name") or "").casefold() != parameter_name:
            continue
        candidates.append(
            (
                _finite(parameter.get("minimum"), f"{parameter_name} minimum"),
                _finite(parameter.get("maximum"), f"{parameter_name} maximum"),
            )
        )
    unique = sorted(set(candidates))
    if len(unique) != 1:
        raise ProfileRecipeError(
            f"event {event.get('path')} has {len(unique)} {parameter_name} domains"
        )
    minimum, maximum = unique[0]
    if maximum <= minimum:
        raise ProfileRecipeError(
            f"event {event.get('path')} {parameter_name} domain is empty"
        )
    return minimum, maximum


def _chain_placements(
    event: Mapping[str, Any], chain_guids: set[str], parameter_name: str
) -> list[dict[str, Any]]:
    raw = event.get("parameterPlacements", [])
    if not isinstance(raw, list):
        raise ProfileRecipeError(f"event {event.get('path')} placements are invalid")
    result: list[dict[str, Any]] = []
    for placement in raw:
        if not isinstance(placement, dict):
            raise ProfileRecipeError(f"event {event.get('path')} has an invalid placement")
        if _guid(placement.get("instrumentGuid")) not in chain_guids:
            continue
        if str(placement.get("parameterName") or "").casefold() != parameter_name:
            continue
        start = _finite(placement.get("start"), f"{parameter_name} placement start")
        end = _finite(placement.get("end"), f"{parameter_name} placement end")
        if end < start:
            raise ProfileRecipeError(f"{parameter_name} placement end precedes start")
        result.append(
            {
                "instrumentGuid": _guid(placement.get("instrumentGuid")),
                "parameterGuid": _guid(placement.get("parameterGuid")),
                "layoutGuid": _guid(placement.get("layoutGuid")),
                "start": start,
                "end": end,
                "includeEnd": placement.get("includeEnd") is True,
            }
        )
    return sorted(result, key=lambda item: (item["start"], item["end"], item["instrumentGuid"]))


def _placement_contains(value: float, placement: Mapping[str, Any]) -> bool:
    start = _finite(placement.get("start"), "placement start")
    end = _finite(placement.get("end"), "placement end")
    if end < start:
        raise ProfileRecipeError("parameter placement end precedes start")
    return value >= start and (value <= end if placement.get("includeEnd") is True else value < end)


def _placement_interval(
    domain: tuple[float, float], placements: Sequence[Mapping[str, Any]]
) -> tuple[float, float]:
    if not placements:
        return domain
    start = max(domain[0], *(float(item["start"]) for item in placements))
    end = min(domain[1], *(float(item["end"]) for item in placements))
    if end < start:
        # A waveform can be authored into multiple disjoint regions.  The
        # source-conservation graph keeps one identity, while the full-event
        # atlas preserves the actual gaps at render time.  This envelope is
        # evidence/classification metadata only, never a release runtime gate.
        start = max(domain[0], min(float(item["start"]) for item in placements))
        end = min(domain[1], max(float(item["end"]) for item in placements))
    return start, end


def _controller_db_at(controller: Mapping[str, Any], value: float) -> float:
    output = evaluate_curve(_curve_points(controller), value)
    property_index = _property_index(controller)
    if property_index == 0:
        return output
    if property_index == 4:
        return 20.0 * math.log10(max(MINIMUM_LINEAR_AMPLITUDE, output))
    raise ProfileRecipeError(
        f"controller {_guid(controller.get('guid'))} property {property_index} is not gain"
    )


def _flatten_gain_controllers(
    controllers: Sequence[Mapping[str, Any]],
    *,
    minimum: float,
    maximum: float,
    tolerance_db: float = CURVE_FLATTEN_TOLERANCE_DB,
) -> dict[str, Any] | None:
    if not controllers:
        return None
    knots = {minimum, maximum}
    for controller in controllers:
        for point in _curve_points(controller):
            knots.add(min(maximum, max(minimum, float(point["input"]))))
    ordered = sorted(knots)

    def exact(value: float) -> float:
        return sum(_controller_db_at(controller, value) for controller in controllers)

    points: list[tuple[float, float]] = []

    def sample(left: float, right: float, depth: int = 0) -> list[tuple[float, float]]:
        left_y = exact(left)
        right_y = exact(right)
        midpoint = (left + right) * 0.5
        midpoint_y = exact(midpoint)
        linear_midpoint = (left_y + right_y) * 0.5
        if depth < 16 and abs(midpoint_y - linear_midpoint) > tolerance_db:
            return sample(left, midpoint, depth + 1)[:-1] + sample(midpoint, right, depth + 1)
        return [(left, left_y), (right, right_y)]

    for left, right in zip(ordered, ordered[1:]):
        segment = sample(left, right)
        if points:
            segment = segment[1:]
        points.extend(segment)
    if len(ordered) == 1:
        points = [(ordered[0], exact(ordered[0]))]
    return {
        "derivedFromControllerGuids": sorted(
            _guid(controller.get("guid")) for controller in controllers
        ),
        "flattenedToleranceDb": tolerance_db,
        "points": [
            {
                "input": round(x, 8),
                "output": round(y, 8),
                "shape": 0.0,
                "interpolationType": 0,
            }
            for x, y in points
        ],
    }


def _linear_amplitude_curve(points: Sequence[Sequence[float]]) -> dict[str, Any]:
    return {
        "projection": "targetOnlyFmodNrtNormalizedLinearAmplitude",
        "points": [
            {
                "input": float(point[0]),
                "output": float(point[1]),
                "shape": 0.0,
                "interpolationType": 0,
            }
            for point in points
        ],
    }


def _linear_amplitude_to_db_curve(
    points: Sequence[Sequence[float]], *, tolerance: float = 2.0e-4
) -> dict[str, Any]:
    """Densify a linear-amplitude curve for Android's dB interpolation lane."""

    if not points:
        raise ProfileRecipeError("projected amplitude curve is empty")
    source = [(float(point[0]), float(point[1])) for point in points]
    if any(right[0] <= left[0] for left, right in zip(source, source[1:])):
        raise ProfileRecipeError("projected amplitude curve x values do not increase")

    def db(amplitude: float) -> float:
        return 20.0 * math.log10(max(MINIMUM_LINEAR_AMPLITUDE, amplitude))

    def render_segment(
        left: tuple[float, float], right: tuple[float, float], depth: int = 0
    ) -> list[tuple[float, float]]:
        midpoint_x = (left[0] + right[0]) * 0.5
        midpoint_amplitude = (left[1] + right[1]) * 0.5
        midpoint_db_linear = (db(left[1]) + db(right[1])) * 0.5
        reconstructed = 10.0 ** (midpoint_db_linear / 20.0)
        if depth < 18 and abs(reconstructed - midpoint_amplitude) > tolerance:
            midpoint = (midpoint_x, midpoint_amplitude)
            return (
                render_segment(left, midpoint, depth + 1)[:-1]
                + render_segment(midpoint, right, depth + 1)
            )
        return [(left[0], db(left[1])), (right[0], db(right[1]))]

    result: list[tuple[float, float]] = []
    for left, right in zip(source, source[1:]):
        segment = render_segment(left, right)
        if result:
            segment = segment[1:]
        result.extend(segment)
    if len(source) == 1:
        result = [(source[0][0], db(source[0][1]))]
    return {
        "projection": "targetOnlyFmodNrtNormalizedAmplitudeAsDb",
        "maximumAmplitudeInterpolationError": tolerance,
        "points": [
            {
                "input": round(x, 8),
                "output": round(y, 8),
                "shape": 0.0,
                "interpolationType": 0,
            }
            for x, y in result
        ],
    }


def _classify_engine_role(
    throttle_curve: Mapping[str, Any] | None,
    throttle_placements: Sequence[Mapping[str, Any]],
    rpm_interval: tuple[float, float],
) -> tuple[str, list[str]]:
    warnings: list[str] = []
    if throttle_placements:
        includes_release = all(float(item["start"]) <= 0.0 <= float(item["end"]) for item in throttle_placements)
        includes_full = all(float(item["start"]) <= 1.0 <= float(item["end"]) for item in throttle_placements)
        if includes_full and not includes_release:
            return "LOAD", warnings
        if includes_release and not includes_full:
            return "COAST", warnings
        if not includes_release and not includes_full:
            warnings.append("throttlePlacementExcludesBothProgramEndpoints")
    if throttle_curve is None:
        if rpm_interval[0] <= 1.0 and rpm_interval[1] <= 3_000.0:
            return "IDLE", warnings
        return "TEXTURE", warnings
    points = throttle_curve["points"]
    probes = [index / 40.0 for index in range(41)]
    values = [evaluate_curve(points, value) for value in probes]
    release = values[0]
    full = values[-1]
    if full - release > THROTTLE_ROLE_EPSILON_DB:
        return "LOAD", warnings
    if release - full > THROTTLE_ROLE_EPSILON_DB:
        return "COAST", warnings
    span = max(values) - min(values)
    if span <= THROTTLE_ROLE_EPSILON_DB:
        if rpm_interval[0] <= 1.0 and rpm_interval[1] <= 3_000.0:
            return "IDLE", warnings
        return "TEXTURE", warnings
    peak_index = max(range(len(values)), key=values.__getitem__)
    warnings.append("mixedThrottleCurveRoleNeedsNrtOracle")
    return ("LOAD" if probes[peak_index] >= 0.5 else "COAST"), warnings


def _finite_route_direction(values: Sequence[float], *, epsilon: float = 1.0e-6) -> str:
    directions = {
        1 if right - left > epsilon else -1
        for left, right in zip(values, values[1:])
        if abs(right - left) > epsilon
    }
    if directions == {1}:
        return "increasing"
    if directions == {-1}:
        return "decreasing"
    if not directions:
        return "invariant"

    return "mixed"


def _finite_throttle_placement_probe(
    placements: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Prove the exact conjunctive throttle region for one authored binding."""

    if not placements:
        return {
            "kind": "ungatedAcrossThrottleDomain",
            "includesRelease": True,
            "includesFullLoad": True,
            "activeInterval": [0.0, 1.0],
            "interiorProbeValue": 0.5,
        }
    lower = max(0.0, *(float(item["start"]) for item in placements))
    upper = min(1.0, *(float(item["end"]) for item in placements))
    if upper < lower:
        raise ProfileRecipeError(
            "finite engine binding has no throttle value inside every authored placement"
        )
    interior = lower if lower == upper else (lower + upper) * 0.5
    if not all(_placement_contains(interior, item) for item in placements):
        raise ProfileRecipeError(
            "finite engine binding throttle placement intersection is empty"
        )

    return {
        "kind": "conjunctiveInstrumentChainPlacement",
        "includesRelease": all(_placement_contains(0.0, item) for item in placements),
        "includesFullLoad": all(_placement_contains(1.0, item) for item in placements),
        "activeInterval": [lower, upper],
        "interiorProbeValue": interior,
    }


def _classify_finite_engine_program_role(
    *,
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    source_guid: str,
    chain: Sequence[tuple[str, dict[str, Any]]],
    controllers: Sequence[Mapping[str, Any]],
    throttle_placements: Sequence[Mapping[str, Any]],
    authored_binding_key: str,
    scheduling_group_id: str,
) -> tuple[str, dict[str, Any]]:
    """Classify a finite engine binding from its exact authored route.

    Diagnostic sample labels are deliberately unavailable to this function.
    The role is owned by the exact event/source/binding identity and follows
    the monotonic response of every throttle gain controller on that route.
    Placements are conjunctive activation gates; they classify an otherwise
    invariant route only when they select exactly one program endpoint.
    """

    event_path = str(event.get("path") or "")
    if not event_path.startswith("event:/"):
        raise ProfileRecipeError("finite engine role classifier has no exact event path")
    if not re.fullmatch(r"binding:[0-9a-f]{64}", authored_binding_key):
        raise ProfileRecipeError("finite engine role classifier has no authored binding key")
    if not chain or chain[0][0] != source_guid:
        raise ProfileRecipeError("finite engine role classifier source route is inconsistent")

    layout_guids = event.get("parameterLayoutGuids", [])
    if not isinstance(layout_guids, list):
        raise ProfileRecipeError(f"event {event_path} parameter layouts are invalid")
    throttle_parameter_guids = sorted(
        {
            parameter_guid
            for raw_guid in layout_guids
            if (parameter_guid := _guid(raw_guid))
            and parameter_guid in indexed.parameters
            and str(indexed.parameters[parameter_guid].get("name") or "").casefold()
            == "throttle"
        }
    )
    if len(throttle_parameter_guids) != 1:
        raise ProfileRecipeError(
            f"event {event_path} has no unique GUID-owned throttle parameter"
        )
    throttle_parameter_guid = throttle_parameter_guids[0]
    misplaced = sorted(
        {
            _guid(item.get("parameterGuid"))
            for item in throttle_placements
            if _guid(item.get("parameterGuid")) != throttle_parameter_guid
        }
    )
    if misplaced:
        raise ProfileRecipeError(
            f"finite engine throttle placements target other parameter GUIDs: {misplaced}"
        )

    throttle_controllers = [
        controller
        for controller in controllers
        if _parameter_name(controller) == "throttle"
        and _property_index(controller) in {0, 4}
    ]
    non_parameter_controllers = sorted(
        _guid(controller.get("guid"))
        for controller in throttle_controllers
        if str(controller.get("inputKind") or "").casefold() != "parameter"
    )
    if non_parameter_controllers:
        raise ProfileRecipeError(
            "finite engine throttle role controllers are not GUID-owned parameters: "
            f"{non_parameter_controllers}"
        )
    wrong_controller_parameters = sorted(
        {
            _guid(controller.get("inputParameterGuid"))
            for controller in throttle_controllers
            if _guid(controller.get("inputParameterGuid"))
            != throttle_parameter_guid
        }
    )
    if wrong_controller_parameters:
        raise ProfileRecipeError(
            "finite engine throttle controllers target other parameter GUIDs: "
            f"{wrong_controller_parameters}"
        )

    placement_probe = _finite_throttle_placement_probe(throttle_placements)
    authored_values = {0.0, 1.0, float(placement_probe["interiorProbeValue"])}
    for placement in throttle_placements:
        authored_values.update(
            {
                min(1.0, max(0.0, float(placement["start"]))),
                min(1.0, max(0.0, float(placement["end"]))),
            }
        )
    for controller in throttle_controllers:
        authored_values.update(
            min(1.0, max(0.0, float(point["input"])))
            for point in _curve_points(controller)
        )
    authored_order = sorted(authored_values)
    probe_values = sorted(
        set(authored_order)
        | {
            (left + right) * 0.5
            for left, right in zip(authored_order, authored_order[1:])
            if right > left
        }
    )

    controller_evidence: list[dict[str, Any]] = []
    controller_directions: set[str] = set()
    for controller in throttle_controllers:
        values = [_controller_db_at(controller, value) for value in probe_values]
        direction = _finite_route_direction(values)
        controller_directions.add(direction)
        controller_evidence.append(
            {
                "controllerGuid": _guid(controller.get("guid")),
                "inputKind": str(controller.get("inputKind") or ""),
                "inputParameterGuid": _guid(controller.get("inputParameterGuid")),
                "propertyOwnerGuid": _guid(controller.get("propertyOwnerGuid")),
                "propertyIndex": _property_index(controller),
                "points": _curve_points(controller),
                "directionAcrossEndpointsKnotsAndInteriors": direction,
                "gainDbAtProbeValues": values,
            }
        )

    route_values = [
        sum(_controller_db_at(controller, value) for controller in throttle_controllers)
        for value in probe_values
    ]
    route_direction = _finite_route_direction(route_values)
    directional = controller_directions & {"increasing", "decreasing"}
    if "mixed" in controller_directions or len(directional) > 1:
        role = "UNAFFECTED"
        classification_kind = "nonMonotonicOrConflictingAuthoredThrottleTexture"
    elif route_direction == "increasing" and directional == {"increasing"}:
        role = "LOAD"
        classification_kind = "monotonicIncreasingAuthoredRouteGain"
    elif route_direction == "decreasing" and directional == {"decreasing"}:
        role = "COAST"
        classification_kind = "monotonicDecreasingAuthoredRouteGain"
    elif (
        placement_probe["includesFullLoad"]
        and not placement_probe["includesRelease"]
    ):
        role = "LOAD"
        classification_kind = "fullLoadOnlyAuthoredPlacement"
    elif (
        placement_probe["includesRelease"]
        and not placement_probe["includesFullLoad"]
    ):
        role = "COAST"
        classification_kind = "releaseOnlyAuthoredPlacement"
    else:
        role = "UNAFFECTED"
        classification_kind = "endpointInvariantOrInteriorOnlyAuthoredTexture"

    chain_owner_guids = _chain_owner_guids(chain)
    return role, {
        "schema": "byd-full-event-engine-program-role-v2",
        "status": "PASS_EXACT_AUTHORED_BINDING_ROUTE_CLASSIFICATION",
        "role": role,
        "classificationKind": classification_kind,
        "classificationUsesDiagnosticName": False,
        "bindingIdentity": {
            "eventPath": event_path,
            "sourceGuid": source_guid,
            "authoredBindingKey": authored_binding_key,
            "schedulingGroupId": scheduling_group_id,
            "instrumentChainSourceToRoot": [guid for guid, _item in chain],
            "routableGuidsSourceToRoot": [
                _guid((instrument.get("baseProperties") or {}).get("routableGuid"))
                for _guid_value, instrument in chain
            ],
            "routeOwnerGuids": sorted(chain_owner_guids),
        },
        "throttleParameterGuid": throttle_parameter_guid,
        "throttlePlacements": copy.deepcopy(list(throttle_placements)),
        "placementProbe": placement_probe,
        "probeValues": probe_values,
        "controllers": controller_evidence,
        "combinedRouteGain": {
            "directionAcrossEndpointsKnotsAndInteriors": route_direction,
            "gainDbAtProbeValues": route_values,
        },
        "gainTrimDisposition": (
            "loadStem"
            if role == "LOAD"
            else "coastStem"
            if role == "COAST"
            else "unaffected"
        ),
        "pcmOracleBoundary": (
            "roleIdentityIsProvenFromTheExactAuthoredRoute; phase-synchronous "
            "same-live-event channel masking still proves captured PCM conservation"
        ),
    }


def _static_properties(
    chain: Sequence[tuple[str, dict[str, Any]]],
    controllers: Sequence[Mapping[str, Any]],
) -> tuple[float, float, list[str]]:
    gain_db = 0.0
    pitch_semitones = 0.0
    warnings: list[str] = []
    for guid, instrument in chain:
        properties = instrument.get("baseProperties")
        if not isinstance(properties, dict):
            raise ProfileRecipeError(f"instrument {guid} has no baseProperties")
        gain_db += _finite(properties.get("volumeDb", 0.0), f"instrument {guid} volume")
        pitch_semitones += _finite(
            properties.get("pitchSemitones", 0.0), f"instrument {guid} pitch"
        )
    for controller in controllers:
        name = _parameter_name(controller)
        if name not in AUTOMATIC_PARAMETER_VALUES:
            continue
        property_index = _property_index(controller)
        if property_index == 0:
            gain_db += evaluate_curve(
                _curve_points(controller), AUTOMATIC_PARAMETER_VALUES[name]
            )
        else:
            warnings.append(
                f"automaticParameterPropertyUnsupported:{name}:{property_index}"
            )
    return gain_db, pitch_semitones, warnings


def _sample_evidence(source: Mapping[str, Any]) -> dict[str, Any]:
    sample = source.get("sample")
    if not isinstance(sample, dict):
        raise ProfileRecipeError(f"source {_guid(source.get('guid'))} has no sample")
    return {
        "diagnosticNameNotUsedForClassification": str(sample.get("name") or ""),
        "subsoundIndexZeroBased": int(sample.get("subsoundIndex")),
        "vgmstreamStreamIndexOneBased": int(sample.get("subsoundIndex")) + 1,
        "frequencyHz": int(sample.get("frequencyHz")),
        "channels": int(sample.get("channels")),
        "sampleCount": int(sample.get("sampleCount")),
        "encodedPayloadBytes": int(sample.get("encodedPayloadBytes")),
        "encodedPayloadSha256": str(sample.get("encodedPayloadSha256") or ""),
        "waveformResourceGuid": _guid(sample.get("waveformResourceGuid")),
    }


def _asset_name(source: Mapping[str, Any], *, unique_context: str = "") -> str:
    sample = _sample_evidence(source)
    payload_hash = sample["encodedPayloadSha256"]
    if not re.fullmatch(r"[0-9a-f]{64}", payload_hash):
        raise ProfileRecipeError("source encoded payload SHA-256 is invalid")
    suffix = f"_{_slug(unique_context)}" if unique_context else ""
    return f"source_{payload_hash[:16]}{suffix}.wav"


def _source_warnings(
    indexed: IndexedGraph,
    chain: Sequence[tuple[str, dict[str, Any]]],
    controllers: Sequence[Mapping[str, Any]],
    *,
    continuous_engine: bool,
) -> list[str]:
    warnings: list[str] = []
    owners = _chain_owner_guids(chain)
    for owner in sorted(owners):
        for modulator in indexed.modulators_by_owner.get(owner, ()):
            warnings.append(
                "modulatorUnsupported:"
                f"{modulator.get('type')}:{modulator.get('propertyIndex')}"
            )
    for controller in controllers:
        name = _parameter_name(controller)
        property_index = _property_index(controller)
        if name in AUTOMATIC_PARAMETER_VALUES:
            continue
        if continuous_engine and name in {"rpms", "throttle"} and property_index in {0, 4}:
            continue
        warnings.append(f"controllerUnsupported:{name or 'unnamed'}:{property_index}")
    return sorted(set(warnings))


def _continuous_engine_layer(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    source_guid: str,
    curve_projector: Callable[..., dict[str, Any]] | None,
) -> dict[str, Any]:
    source = indexed.instruments[source_guid]
    properties = source.get("baseProperties")
    if not isinstance(properties, dict):
        raise ProfileRecipeError(f"source {source_guid} has no baseProperties")
    if int(properties.get("loopCount")) != -1:
        raise ProfileRecipeError(f"source {source_guid} is not an infinite loop")
    chain = _instrument_chain(indexed, source_guid)
    chain_guids = {guid for guid, _instrument in chain}
    controllers = _chain_controllers(indexed, chain)
    suffix = _event_suffix(event.get("path"))
    runtime_parameters = _effect_runtime_parameters(indexed, event, chain_guids)
    authored_parameter_defaults = _effect_authored_parameter_defaults(indexed, event)
    host_parameter_bindings = _host_parameter_bindings(suffix, runtime_parameters)
    placement_parameter_names = sorted(
        {
            str(placement.get("parameterName") or "").casefold()
            for placement in event.get("parameterPlacements", [])
            if isinstance(placement, Mapping)
            and _guid(placement.get("instrumentGuid")) in chain_guids
            and str(placement.get("parameterName") or "").strip()
        }
    )
    parameter_placements = {
        parameter: _chain_placements(event, chain_guids, parameter)
        for parameter in placement_parameter_names
    }
    parameter_domains = {
        parameter: list(_event_parameter_domain(indexed, event, parameter))
        for parameter in sorted(parameter_placements)
    }
    continuous_membership = _continuous_parameter_membership_contract(
        parameter_placements,
        host_parameter_bindings,
        runtime_parameters,
        authored_parameter_defaults,
    )
    scheduling_group = _effect_scheduler_contract(
        indexed, event, source_guid, chain
    )
    rpm_domain = _event_parameter_domain(indexed, event, "rpms")
    rpm_placements = _chain_placements(event, chain_guids, "rpms")
    rpm_interval = _placement_interval(rpm_domain, rpm_placements)
    throttle_placements = _chain_placements(event, chain_guids, "throttle")
    throttle_controllers = [
        controller
        for controller in controllers
        if _parameter_name(controller) == "throttle"
        and _property_index(controller) in {0, 4}
    ]
    throttle_curve = _flatten_gain_controllers(
        throttle_controllers, minimum=0.0, maximum=1.0
    )
    role, role_warnings = _classify_engine_role(
        throttle_curve, throttle_placements, rpm_interval
    )
    gain_db, pitch_semitones, static_warnings = _static_properties(chain, controllers)
    rpm_amplitude_curves = [
        _curve_document(controller)
        for controller in controllers
        if _parameter_name(controller) == "rpms" and _property_index(controller) == 4
    ]
    rpm_gain_db_curves = [
        _curve_document(controller)
        for controller in controllers
        if _parameter_name(controller) == "rpms" and _property_index(controller) == 0
    ]
    root_rpm = _finite(
        properties.get("autoPitchReference"), f"source {source_guid} AutoPitch root"
    )
    warnings = role_warnings + static_warnings + _source_warnings(
        indexed, chain, controllers, continuous_engine=True
    )
    if scheduling_group["complete"] is not True:
        warnings.append(str(scheduling_group["incompleteReason"]))
    if root_rpm <= 0.0:
        warnings.append("autoPitchRootIsNotPositive")
    if int(properties.get("autoPitchAtMinimum", 0)) != 0:
        warnings.append("autoPitchAtMinimumUnsupported")
    if root_rpm > 0.0 and (
        rpm_interval[0] / root_rpm < 0.10 or rpm_interval[1] / root_rpm > 4.0
    ):
        warnings.append("androidPlaybackRatioClampMayDifferFromFmod")
    perspective = ENGINE_EVENT_PERSPECTIVE[_event_suffix(event.get("path"))]
    layer_id = (
        f"{perspective}_{role.casefold()}_"
        f"s{_sample_evidence(source)['subsoundIndexZeroBased']:03d}_{source_guid[:8]}"
    )
    layer = {
        "id": layer_id,
        "assetName": _asset_name(source),
        "sourceGuid": source_guid,
        "role": role,
        "startRpm": rpm_interval[0],
        "endRpm": rpm_interval[1],
        "autoPitchRootRpm": root_rpm,
        "basePitchSemitones": pitch_semitones,
        "baseGainDb": gain_db,
        "rawFallbackGainBakeDb": gain_db,
        "applyIdleGainBoost": False,
        "throttleGainDb": throttle_curve,
        "throttleAmplitudeCurveExact": None,
        "rpmAmplitudeCurves": rpm_amplitude_curves,
        "rpmGainDbCurves": rpm_gain_db_curves,
        "sourceEvidence": _sample_evidence(source),
        "rpmPlacements": rpm_placements,
        "throttlePlacements": throttle_placements,
        "runtimeParameters": runtime_parameters,
        "authoredParameterDefaults": authored_parameter_defaults,
        "hostParameterBindings": host_parameter_bindings,
        "parameterPlacements": parameter_placements,
        "parameterDomains": parameter_domains,
        "continuousParameterMembership": continuous_membership,
        "schedulingGroup": scheduling_group,
        "warnings": sorted(set(warnings)),
    }
    def evidence_capture(reason: str) -> dict[str, Any]:
        rpm_gain_controllers = [
            controller
            for controller in controllers
            if _parameter_name(controller) == "rpms"
            and _property_index(controller) in {0, 4}
        ]
        candidates = {
            rpm_interval[0],
            rpm_interval[1],
            (rpm_interval[0] + rpm_interval[1]) * 0.5,
            min(rpm_interval[1], max(rpm_interval[0], root_rpm)),
        }
        for controller in rpm_gain_controllers:
            candidates.update(
                min(rpm_interval[1], max(rpm_interval[0], float(point["input"])))
                for point in _curve_points(controller)
            )
        capture_rpm = max(
            sorted(candidates),
            key=lambda value: sum(
                _controller_db_at(controller, value)
                for controller in rpm_gain_controllers
            ),
        )
        parameters: dict[str, float] = {"rpms": capture_rpm}
        if role == "LOAD":
            parameters["throttle"] = 1.0
        elif role == "COAST":
            parameters["throttle"] = 0.0
        return {
            "mode": "targetOnlyFmodNrtEvidence",
            "event": _event_suffix(event.get("path")),
            "parameters": parameters,
            "durationFrames": 96_000,
            "warmupFrames": 24_000,
            "capturePcmPostGainLinear": 1.0,
            "isolateEveryOtherWaveformInstrument": True,
            "lifetime": "continuous",
            "releaseRuntimeAsset": False,
            "reason": reason,
        }

    if curve_projector is None:
        layer["assetName"] = (
            f"nrt_evidence_{perspective}_{role.casefold()}_{source_guid[:12]}.wav"
        )
        layer["capture"] = evidence_capture(
            "authored curve projector was not configured"
        )
        layer["warnings"] = sorted(
            {*layer["warnings"], "exactRuntimeProjectionUnavailable"}
        )
        return layer

    classification = {
        "sourceGuid": source_guid,
        "eventPaths": [str(event.get("path"))],
        "policy": "allowCandidate",
        "lifetime": "continuous",
        "role": role,
    }
    capture_controls: dict[str, float] = {}
    if role == "LOAD":
        capture_controls["throttle"] = 1.0
    elif role == "COAST":
        capture_controls["throttle"] = 0.0
    try:
        projection = curve_projector(
            indexed.graph,
            classification,
            capture_controls or None,
        )
    except ValueError as exc:
        layer["assetName"] = (
            f"nrt_evidence_{perspective}_{role.casefold()}_{source_guid[:12]}.wav"
        )
        layer["capture"] = evidence_capture(
            f"curve projector rejected source: {exc}"
        )
        layer["warnings"] = sorted(
            {*layer["warnings"], "exactRuntimeProjectionUnavailable"}
        )
        return layer
    layer.update(
        {
            "assetName": f"nrt_{perspective}_{role.casefold()}_{source_guid[:12]}.wav",
            "autoPitchRootRpm": float(projection["captureRootRpm"]),
            "basePitchSemitones": 0.0,
            "baseGainDb": 0.0,
            "throttleGainDb": _linear_amplitude_to_db_curve(
                projection["gainCurve"]
            ),
            "throttleAmplitudeCurveExact": _linear_amplitude_curve(
                projection["gainCurve"]
            ),
            "rpmAmplitudeCurves": [
                _linear_amplitude_curve(projection["rpmCurve"])
            ],
            "rpmGainDbCurves": [],
            "capture": {
                "mode": "targetOnlyFmodNrt",
                "event": _event_suffix(event.get("path")),
                "parameters": projection["captureParameterValues"],
                "automaticParameters": projection[
                    "captureAutomaticParameterValues"
                ],
                "captureRootRpm": projection["captureRootRpm"],
                "sourceAutoPitchReferenceRpm": projection[
                    "autoPitchReferenceRpm"
                ],
                "durationFrames": 192_000,
                "warmupFrames": 36_000,
                "capturePcmPostGainLinear": projection["normalization"][
                    "capturePcmPostGainLinear"
                ],
                "isolateEveryOtherWaveformInstrument": True,
                "loopRepair": {
                    "method": "boundedSeamSearchThenCrossfade",
                    "smplChunkRequired": True,
                },
                "fidelity": projection["fidelity"],
            },
            "projectionEvidence": {
                "schema": projection["schema"],
                "controllers": projection["controllers"],
                "triggerPlacements": projection["triggerPlacements"],
                "baseGain": projection["baseGain"],
                "normalization": projection["normalization"],
            },
        }
    )
    return layer


def _effect_perspective(
    suffix: str,
    *,
    has_transmission_ext: bool = False,
) -> str | None:
    if suffix == "transmission" and has_transmission_ext:
        return "cabin"
    if suffix.endswith("_int"):
        return "cabin"
    if suffix.endswith("_ext"):
        return "exterior"
    return None


def _event_instance_ownership(suffix: str) -> dict[str, str]:
    """Describe the actual FMOD instance that owns finite scheduler state.

    A perspective is an output/listener concern, not a generic event-instance
    key.  NativeFmodAudio holds one instance per event path for the active car
    session. Camera selection does not change an event's exact-path identity;
    INT/EXT event pairs retain independent instances, while one-shot tails and
    scheduler state remain owned by their authored event path.
    """

    if suffix in {"engine_int", "engine_ext"}:
        return {
            "schema": "byd-fmod-event-instance-ownership-v1",
            "key": "exactEventPath",
            "owner": "selectedPerspectiveEngineEventInstance",
            "created": "selectedEngineEventPathStartForActiveProfileAudioSession",
            "survives": "loadCoastBothModeChangeOnly",
            "resets": "thatExactEngineEventPathStopRewindStartOrNewInstance",
            "activationGeneration": "incrementsForEveryStopRewindStartOfThatExactEnginePath",
        }
    if suffix in {
        "gear_int",
        "gear_ext",
        "backfire_int",
        "backfire_ext",
        "limiter",
        "transmission",
        "transmission_ext",
        "tractioncontrol_int",
        "tractioncontrol_ext",
        "gear_grind",
        "start",
        "turbo",
    }:
        return {
            "schema": "byd-fmod-event-instance-ownership-v1",
            "key": "exactEventPath",
            "owner": "profileAudioSessionPersistentEventInstance",
            "created": "exactEventPathStartForActiveProfileAudioSession",
            "survives": "listenerCameraAndLoadCoastBothModeChanges",
            "resets": "profileAudioSessionStopThenNewInstance",
            "activationGeneration": "incrementsOnlyWhenThatPersistentExactEventPathIsStoppedThenStarted",
        }
    return {
        "schema": "byd-fmod-event-instance-ownership-v1",
        "key": "exactEventPath",
        "owner": "BLOCKED_UNKNOWN_HOST_EVENT_OWNER",
        "created": "BLOCKED_UNKNOWN_HOST_EVENT_OWNER",
        "survives": "BLOCKED_UNKNOWN_HOST_EVENT_OWNER",
        "resets": "BLOCKED_UNKNOWN_HOST_EVENT_OWNER",
        "activationGeneration": "BLOCKED_UNKNOWN_HOST_EVENT_OWNER",
    }


def _effect_capture_parameters(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    chain_guids: set[str],
    controllers: Sequence[Mapping[str, Any]],
    parameter_axes: Mapping[str, Sequence[float]],
    host_parameter_bindings: Sequence[Mapping[str, Any]],
) -> dict[str, float]:
    """Choose an authored-grid point that reaches an audible effect source.

    An isolated finite source can have no parameter-sheet placement on an
    input that controls its gain.  Using that parameter's graph default (often
    zero throttle/RPM) schedules the source but captures all-zero PCM.  Select
    only from the exact placement/controller grid retained in the atlas, and
    maximize the chain's authored gain at that point.  This is a deterministic
    reference point for source-solo lifecycle evidence; runtime interpolation
    still evaluates every emitted grid corner.
    """

    constant_host_parameters: dict[str, float] = {}
    for binding in host_parameter_bindings:
        parameter = str(binding.get("parameter") or "").casefold()
        if not parameter or "constant" not in binding:
            continue
        value = _finite(binding["constant"], f"{parameter} host constant")
        previous = constant_host_parameters.get(parameter)
        if previous is not None and abs(previous - value) > 1.0e-8:
            raise ProfileRecipeError(
                f"event {event.get('path')} has conflicting {parameter} host constants"
            )
        constant_host_parameters[parameter] = value

    result: dict[str, float] = {}
    layouts = event.get("parameterLayoutGuids", [])
    if not isinstance(layouts, list):
        raise ProfileRecipeError(f"event {event.get('path')} parameter layouts are invalid")
    for raw_guid in layouts:
        parameter = indexed.parameters.get(_guid(raw_guid))
        if parameter is None:
            continue
        parameter_type = str(parameter.get("type") or "")
        if "AUTOMATIC" in parameter_type:
            continue
        name = str(parameter.get("name") or "").casefold()
        if not name:
            continue
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        placements = _chain_placements(event, chain_guids, name)
        raw_candidates = parameter_axes.get(name)
        if not isinstance(raw_candidates, Sequence) or isinstance(
            raw_candidates, (str, bytes)
        ):
            raise ProfileRecipeError(
                f"event {event.get('path')} {name} has no executable capture axis"
            )
        candidates = sorted(
            {
                min(maximum, max(minimum, _finite(value, f"{name} capture axis")))
                for value in raw_candidates
                if not placements
                or all(_placement_contains(float(value), placement) for placement in placements)
            }
        )
        if not candidates:
            raise ProfileRecipeError(
                f"event {event.get('path')} {name} has no capture point inside every placement"
            )
        fixed_host_value = constant_host_parameters.get(name)
        if fixed_host_value is not None:
            if fixed_host_value not in candidates:
                raise ProfileRecipeError(
                    f"event {event.get('path')} host constant {name}={fixed_host_value} "
                    "cannot reach this source's authored capture region"
                )
            value = fixed_host_value
        else:
            gain_controllers = [
            controller
            for controller in controllers
            if _parameter_name(controller) == name
            and _property_index(controller) in {0, 4}
            ]
            if gain_controllers:
                value = max(
                    candidates,
                    key=lambda candidate: (
                        sum(
                            _controller_db_at(controller, candidate)
                            for controller in gain_controllers
                        ),
                        candidate,
                    ),
                )
            else:
                default = _finite(parameter.get("defaultValue"), f"{name} default")
                value = min(candidates, key=lambda candidate: (abs(candidate - default), candidate))
        previous = result.get(name)
        if previous is not None and abs(previous - value) > 1.0e-8:
            raise ProfileRecipeError(
                f"event {event.get('path')} has conflicting {name} capture values"
            )
        result[name] = value
    return result


def _effect_runtime_parameters(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    chain_guids: set[str],
) -> dict[str, float]:
    """Return exact graph defaults for parameters Android does not own.

    Capture points intentionally sit inside source placements so an isolated
    PCM node can be rendered. They are not event-instance defaults. Treating
    a midpoint as a runtime value can wake an authored-dormant source (for
    example a valved engine transient whose real default is zero), so runtime
    defaults always come straight from the parameter declaration.
    """

    result: dict[str, float] = {}
    layouts = event.get("parameterLayoutGuids", [])
    if not isinstance(layouts, list):
        raise ProfileRecipeError(f"event {event.get('path')} parameter layouts are invalid")
    layout_by_guid: dict[str, Mapping[str, Any]] = {}
    for raw_guid in layouts:
        parameter = indexed.parameters.get(_guid(raw_guid))
        if parameter is None:
            continue
        layout_by_guid[_guid(raw_guid)] = parameter
        if "AUTOMATIC" in str(parameter.get("type") or ""):
            continue
        name = str(parameter.get("name") or "").casefold()
        if not name:
            continue
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        default = _finite(parameter.get("defaultValue"), f"{name} default")
        if default < minimum or default > maximum:
            raise ProfileRecipeError(
                f"event {event.get('path')} {name} default lies outside its domain"
            )
        previous = result.get(name)
        if previous is not None and abs(previous - default) > 1.0e-8:
            raise ProfileRecipeError(
                f"event {event.get('path')} has conflicting {name} runtime defaults"
            )
        result[name] = default
    # The event may contain same-named layouts. A source's placement owns the
    # exact parameter GUID it uses; never choose a default by display name.
    # Ambiguous same-name source placements cannot be reproduced by Android's
    # name-based host binding and intentionally block recipe generation.
    source_parameter_guids: dict[str, set[str]] = defaultdict(set)
    for placement in event.get("parameterPlacements", []):
        if not isinstance(placement, Mapping):
            raise ProfileRecipeError(f"event {event.get('path')} has an invalid placement")
        if _guid(placement.get("instrumentGuid")) not in chain_guids:
            continue
        name = str(placement.get("parameterName") or "").casefold()
        parameter_guid = _guid(placement.get("parameterGuid"))
        parameter = indexed.parameters.get(parameter_guid)
        if not name or parameter is None:
            raise ProfileRecipeError(
                f"event {event.get('path')} placement has no resolvable parameter GUID"
            )
        if str(parameter.get("name") or "").casefold() != name:
            raise ProfileRecipeError(
                f"event {event.get('path')} placement parameter GUID/name disagree"
            )
        if parameter_guid not in layout_by_guid:
            raise ProfileRecipeError(
                f"event {event.get('path')} placement parameter is not in its layout"
            )
        source_parameter_guids[name].add(parameter_guid)
    for name, guids in sorted(source_parameter_guids.items()):
        if len(guids) != 1:
            raise ProfileRecipeError(
                f"event {event.get('path')} source has ambiguous same-name {name} placements"
            )
        parameter = indexed.parameters[next(iter(guids))]
        if "AUTOMATIC" in str(parameter.get("type") or ""):
            # FMOD owns these values at render time. They are retained as
            # continuous-placement dependencies rather than silently reduced
            # to the graph default in the Android parameter map.
            continue
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        default = _finite(parameter.get("defaultValue"), f"{name} default")
        if default < minimum or default > maximum:
            raise ProfileRecipeError(
                f"event {event.get('path')} {name} placement default lies outside its domain"
            )
        result[name] = default
    return result


def _effect_authored_parameter_defaults(
    indexed: IndexedGraph, event: Mapping[str, Any]
) -> list[dict[str, Any]]:
    """Retain every event parameter's exact GUID-bound graph default.

    Android only writes an explicit, closed host-binding set.  Automatic and
    otherwise authored parameters therefore stay at the value FMOD gives a new
    event instance.  Keep that value keyed by GUID (not display name) so a
    duplicate parameter name cannot silently wake a gated source.
    """

    layouts = event.get("parameterLayoutGuids", [])
    if not isinstance(layouts, list):
        raise ProfileRecipeError(f"event {event.get('path')} parameter layouts are invalid")
    result: list[dict[str, Any]] = []
    for raw_guid in layouts:
        parameter_guid = _guid(raw_guid)
        parameter = indexed.parameters.get(parameter_guid)
        if parameter is None:
            raise ProfileRecipeError(
                f"event {event.get('path')} references an unavailable parameter GUID"
            )
        name = str(parameter.get("name") or "").casefold()
        if not name:
            raise ProfileRecipeError(
                f"event {event.get('path')} parameter {parameter_guid} has no name"
            )
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        default = _finite(parameter.get("defaultValue"), f"{name} default")
        if default < minimum or default > maximum:
            raise ProfileRecipeError(
                f"event {event.get('path')} {name} default lies outside its domain"
            )
        result.append(
            {
                "parameter": name,
                "parameterGuid": parameter_guid,
                "defaultValue": default,
                "type": str(parameter.get("type") or ""),
            }
        )
    if len({item["parameterGuid"] for item in result}) != len(result):
        raise ProfileRecipeError(f"event {event.get('path')} repeats a parameter GUID")
    return sorted(result, key=lambda item: item["parameterGuid"])


def _effect_capture_parameter_axes(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    chain_guids: set[str],
    controllers: Sequence[Mapping[str, Any]],
) -> dict[str, list[float]]:
    """Keep every authored placement boundary for a full-event effect atlas.

    A midpoint-only capture is not a runtime contract: continuous drivetrain,
    turbo, limiter, and traction events need values at every source gate edge.
    Values without a source placement are deliberately left at their authored
    default; expanding them to an invented min/max sweep would not be source
    conservation.
    """

    result: dict[str, list[float]] = {}
    layouts = event.get("parameterLayoutGuids", [])
    if not isinstance(layouts, list):
        raise ProfileRecipeError(f"event {event.get('path')} parameter layouts are invalid")
    layout_guids = {_guid(value) for value in layouts}
    controller_knots: dict[str, set[float]] = defaultdict(set)
    controller_parameter_guids: dict[str, set[str]] = defaultdict(set)
    for controller in controllers:
        if str(controller.get("inputKind") or "").casefold() != "parameter":
            continue
        parameter_guid = _guid(controller.get("inputParameterGuid"))
        if not parameter_guid:
            continue
        parameter = indexed.parameters.get(parameter_guid)
        if parameter is None or parameter_guid not in layout_guids:
            raise ProfileRecipeError(
                f"effect controller {_guid(controller.get('guid'))} has no event-owned parameter"
            )
        if "AUTOMATIC" in str(parameter.get("type") or ""):
            # The target renderer leaves automatic FMOD values at their
            # authored event-instance defaults. They are not Android host
            # axes; preserving the default rather than inventing a spatial or
            # timeline control keeps the source capture in the same state.
            continue
        name = _parameter_name(controller)
        if not name or str(parameter.get("name") or "").casefold() != name:
            raise ProfileRecipeError(
                f"effect controller {_guid(controller.get('guid'))} parameter identity differs"
            )
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        for point in _curve_points(controller):
            x = float(point["input"])
            if minimum <= x <= maximum:
                controller_knots[name].add(x)
        controller_parameter_guids[name].add(parameter_guid)
    ambiguous = sorted(
        name for name, guids in controller_parameter_guids.items() if len(guids) != 1
    )
    if ambiguous:
        raise ProfileRecipeError(
            "effect controller axes have ambiguous same-name parameter GUIDs: "
            + ", ".join(ambiguous)
        )
    for raw_guid in layouts:
        parameter = indexed.parameters.get(_guid(raw_guid))
        if parameter is None or "AUTOMATIC" in str(parameter.get("type") or ""):
            continue
        name = str(parameter.get("name") or "").casefold()
        if not name:
            continue
        minimum = _finite(parameter.get("minimum"), f"{name} minimum")
        maximum = _finite(parameter.get("maximum"), f"{name} maximum")
        placements = _chain_placements(event, chain_guids, name)
        values: set[float]
        if placements:
            values = {
                min(maximum, max(minimum, value))
                for placement in placements
                for value in (float(placement["start"]), float(placement["end"]))
            }
        else:
            values = {
                min(
                    maximum,
                    max(minimum, _finite(parameter.get("defaultValue"), f"{name} default")),
                )
            }
        values.update(controller_knots.get(name, set()))
        result[name] = sorted(values)
    return result


def _effect_capture_recipe(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    source: Mapping[str, Any],
    chain_guids: set[str],
    lifetime: str,
    controllers: Sequence[Mapping[str, Any]],
    host_parameter_bindings: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    evidence = _sample_evidence(source)
    source_seconds = evidence["sampleCount"] / max(1, evidence["frequencyHz"])
    if lifetime == "continuous":
        duration_frames = 192_000
        warmup_frames = 36_000
    else:
        duration_frames = round(
            min(10.0, max(1.0, source_seconds + 0.5)) * 48_000
        )
        warmup_frames = 0
    parameter_axes = _effect_capture_parameter_axes(
        indexed, event, chain_guids, controllers
    )
    # A host-owned constant is not an interpolation dimension.  For example,
    # Audio Lab intentionally writes backfire throttle as 0.01; retaining the
    # graph default or arbitrary controller knots would emit nodes Android can
    # never select and might capture silence.  Collapse that dimension before
    # choosing the source-solo capture point so both capture and runtime bind
    # the exact same literal value.
    for binding in host_parameter_bindings:
        parameter = str(binding.get("parameter") or "").casefold()
        if not parameter or "constant" not in binding:
            continue
        if parameter not in parameter_axes:
            raise ProfileRecipeError(
                f"event {event.get('path')} host constant targets unknown parameter {parameter}"
            )
        parameter_axes[parameter] = [
            _finite(binding["constant"], f"{parameter} host constant")
        ]
    parameters = _effect_capture_parameters(
        indexed,
        event,
        chain_guids,
        controllers,
        parameter_axes,
        host_parameter_bindings,
    )
    for parameter, value in parameters.items():
        parameter_axes.setdefault(parameter, []).append(value)
        parameter_axes[parameter] = sorted(set(parameter_axes[parameter]))
    return {
        "mode": "targetOnlyFmodNrtEffect",
        "event": _event_suffix(event.get("path")),
        "parameters": parameters,
        "parameterAxes": parameter_axes,
        "durationFrames": duration_frames,
        "warmupFrames": warmup_frames,
        "capturePcmPostGainLinear": 1.0,
        "isolateEveryOtherWaveformInstrument": True,
        "lifetime": lifetime,
        "loopRepair": (
            {
                "method": "boundedSeamSearchThenCrossfade",
                "smplChunkRequired": True,
            }
            if lifetime == "continuous"
            else None
        ),
    }


def _placement_entry_contract(
    placements: Mapping[str, Sequence[Mapping[str, Any]]],
    host_parameter_bindings: Sequence[Mapping[str, Any]],
    authored_parameters: Mapping[str, Any],
) -> dict[str, Any] | None:
    """Describe FMOD parameter-sheet entry with no inferred endpoint rules.

    Parameter-sheet instruments are not event-start one-shots. FMOD starts a
    finite child when its *combined* chain placement becomes true, and starts
    it again after the placement becomes false and later true.  Keep every
    placement rather than reducing a source to one broad interval: ancestor
    placements are conjunctive gates, and ``includeEnd`` is authored data.
    """

    normalized = {
        str(parameter): [dict(item) for item in entries]
        for parameter, entries in sorted(placements.items())
        if entries
    }
    if not normalized:
        return None
    binding_by_parameter = {
        str(binding.get("parameter")): dict(binding)
        for binding in host_parameter_bindings
        if isinstance(binding, Mapping) and binding.get("parameter")
    }
    missing = sorted(set(normalized) - set(authored_parameters))
    if missing:
        raise ProfileRecipeError(
            "parameter-placement finite source has no retained authored value for "
            f"{', '.join(missing)}"
        )
    parameter_values = []
    for parameter in sorted(normalized):
        binding = binding_by_parameter.get(parameter)
        parameter_guids = {
            str(item.get("parameterGuid") or "")
            for item in normalized[parameter]
        }
        layout_guids = {
            str(item.get("layoutGuid") or "")
            for item in normalized[parameter]
        }
        if len(parameter_guids) != 1 or "" in parameter_guids:
            raise ProfileRecipeError(
                f"parameter-placement {parameter} has ambiguous/missing parameter GUID"
            )
        if len(layout_guids) != 1 or "" in layout_guids:
            raise ProfileRecipeError(
                f"parameter-placement {parameter} has ambiguous/missing layout GUID"
            )
        parameter_values.append(
            {
                "parameter": parameter,
                "parameterGuid": next(iter(parameter_guids)),
                "layoutGuid": next(iter(layout_guids)),
                "value": (
                    {"kind": "hostBinding", "binding": binding}
                    if binding is not None
                    else {
                        "kind": "authoredDefault",
                        "value": _finite(
                            authored_parameters[parameter],
                            f"{parameter} authored capture parameter",
                        ),
                    }
                ),
            }
        )
    return {
        "schema": "byd-fmod-parameter-placement-entry-v1",
        "membership": {
            "parameterCombination": "allParameterGroupsMustContainCurrentValue",
            "placementsWithinParameter": "allInstrumentChainPlacementsMustContainCurrentValue",
            "startBoundary": "inclusive",
            "endBoundary": "includeEndFromAuthoredParameterPlacement",
            "placements": normalized,
            "parameterValues": parameter_values,
        },
        "initialState": {
            "when": "exactEventInstanceCreated",
            # Initial-inside varies across real persistent FMOD graphs (for
            # example, a 488 transmission sheet fires at start while its
            # placement-only turbo sheet does not).  Do not turn the common
            # AND-membership topology into a false universal start rule.
            "inside": "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
            "outside": "remainSilentUntilOriginalBankProvenOutsideToInsideEntry",
        },
        "transition": {
            "sampleBoundary": "eachDspBlockAfterHostParameterUpdateForHostBoundParameters",
            "trigger": "combinedMembershipOutsideToInside",
            "directions": ["increasing", "decreasing", "discontinuousJump"],
            "exit": "combinedMembershipInsideToOutsideArmsNextEntry",
        },
        # FMOD owns placement latches on the concrete EventInstance.  The
        # event path already distinguishes engine_int from engine_ext, while
        # shared transmission/turbo instances survive a camera/listener
        # change.  Perspective must therefore never participate in this key.
        "stateScope": "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
    }


def _continuous_parameter_membership_contract(
    placements: Mapping[str, Sequence[Mapping[str, Any]]],
    host_parameter_bindings: Sequence[Mapping[str, Any]],
    authored_parameters: Mapping[str, Any],
    authored_parameter_defaults: Sequence[Mapping[str, Any]],
) -> dict[str, Any] | None:
    """Keep exact continuous-source channel membership separate from entry.

    A continuous waveform does not emit a finite placement-entry trigger, but
    its nested parameter-sheet placements still determine whether FMOD has an
    active source channel at a DSP block.  Reuse the GUID-bound membership
    normalisation from finite sources and explicitly change only the lifetime
    behavior; no midpoint/default approximation is allowed here.
    """

    automatic_by_guid = {
        str(item.get("parameterGuid") or ""): item
        for item in authored_parameter_defaults
        if isinstance(item, Mapping)
        and "AUTOMATIC" in str(item.get("type") or "")
    }
    regular_placements: dict[str, Sequence[Mapping[str, Any]]] = {}
    automatic_dependencies: list[dict[str, Any]] = []
    for parameter, spans in sorted(placements.items()):
        guids = {
            str(span.get("parameterGuid") or "")
            for span in spans
            if isinstance(span, Mapping)
        }
        if len(guids) != 1 or "" in guids:
            raise ProfileRecipeError(
                f"continuous placement {parameter} has ambiguous/missing parameter GUID"
            )
        parameter_guid = next(iter(guids))
        automatic = automatic_by_guid.get(parameter_guid)
        if automatic is None:
            regular_placements[parameter] = spans
            continue
        automatic_dependencies.append(
            {
                "parameter": parameter,
                "parameterGuid": parameter_guid,
                "type": automatic["type"],
                "placements": [dict(span) for span in spans],
                "resolution": "rendererAutomaticValueAtEachDspBlock; notGraphDefaultOrHostApproximation",
            }
        )
    entry = _placement_entry_contract(
        regular_placements, host_parameter_bindings, authored_parameters
    )
    if entry is None and not automatic_dependencies:
        return None
    membership = (
        copy.deepcopy(entry["membership"])
        if entry is not None
        else {
            "parameterCombination": "allParameterGroupsMustContainCurrentValue",
            "placementsWithinParameter": "allInstrumentChainPlacementsMustContainCurrentValue",
            "startBoundary": "inclusive",
            "endBoundary": "includeEndFromAuthoredParameterPlacement",
            "placements": {},
            "parameterValues": [],
        }
    )
    return {
        "schema": "byd-fmod-continuous-placement-membership-v1",
        "membership": membership,
        "automaticPlacementDependencies": automatic_dependencies,
        "evaluation": "everyDspBlockAfterHostParameterUpdate",
        "activation": "insideCombinedMembershipConsumesOneSourceLogicalChannel",
        "deactivation": "outsideCombinedMembershipRemovesThatSourceLogicalChannel",
        "parameterValueFallback": "authoredDefaultBoundByPlacementParameterGuid",
    }


def _effect_trigger_for_source(
    event: Mapping[str, Any],
    chain_guids: set[str],
    suffix: str,
    lifetime: str,
    placement_entry: Mapping[str, Any] | None,
    *,
    has_timeline_placement: bool,
) -> tuple[list[str], list[str]]:
    warnings: list[str] = []
    persistent_event = suffix in {
        "engine_int",
        "engine_ext",
        "transmission",
        "transmission_ext",
        "turbo",
        "limiter",
    }
    if persistent_event and lifetime != "continuous":
        # A finite source with a graph-owned parameter placement must be
        # driven from the placement state machine.  Whether an initially
        # inside event instance fires is an authored/topological behavior and
        # is carried by parameterPlacementEntry.initialState; it is not a
        # suffix-derived turbo/transmission pulse.
        if placement_entry is not None:
            return ["PARAMETER_PLACEMENT_ENTRY"], warnings
        if has_timeline_placement:
            # A timeline placement is not evidence of a host event-start
            # trigger.  Persistent engine, transmission, turbo, and limiter
            # events can have periodic/time-locked scheduling whose phase and
            # overlap behaviour survives ordinary host control updates.  The
            # original-bank lifecycle probe must classify the concrete source
            # before a runtime trigger is emitted.
            return [], [
                "persistentFiniteTimelineLifecycleUnprovenOriginalBankPeriodicProbeRequired"
            ]
        return [], [
            "persistentFiniteLifecycleUnprovenNoTimelineOrHostControllablePlacement"
        ]
    if "backfire" in suffix or "overrun" in suffix:
        return ["THROTTLE_LIFT"], warnings
    if "gear" in suffix and "grind" not in suffix:
        state_placements = _chain_placements(event, chain_guids, "state")
        if not state_placements:
            return [], ["gearStatePlacementMissing"]
        interval = _placement_interval((0.0, 1.0), state_placements)
        triggers: list[str] = []
        if interval[0] <= 0.0 <= interval[1]:
            triggers.append("SHIFT_DOWN")
        if interval[0] <= 1.0 <= interval[1]:
            triggers.append("SHIFT_UP")
        if not triggers:
            warnings.append("gearStatePlacementContainsNeitherEndpoint")
        return triggers, warnings
    if "transmission" in suffix or "drivetrain" in suffix:
        return (["TRANSMISSION_LOOP"] if lifetime == "continuous" else ["TRANSMISSION_PULSE"]), warnings
    if "turbo" in suffix or "supercharger" in suffix:
        return (["TURBO_LOOP"] if lifetime == "continuous" else ["TURBO_DUMP"]), warnings
    if "bov" in suffix or "dump" in suffix:
        return (["TURBO_DUMP"] if lifetime != "continuous" else []), warnings
    if "limiter" in suffix:
        return (
            ["LIMITER_LOOP"] if lifetime == "continuous" else ["LIMITER_PULSE"],
            warnings,
        )
    if "traction" in suffix:
        return (
            ["TRACTION_LIMIT"] if lifetime == "continuous" else ["TRACTION_PULSE"],
            warnings,
        )
    if "start" in suffix:
        return ["ENGINE_START"], warnings
    if "grind" in suffix:
        return ["SHIFT_REJECTED"], warnings
    return [], [f"unsupportedEffectEvent:{suffix}"]


def _finite_lifecycle_topology(
    *,
    suffix: str,
    lifetime: str,
    placement_entry: Mapping[str, Any] | None,
    scheduler: Mapping[str, Any],
) -> dict[str, Any] | None:
    """Record graph topology without promoting it to a host lifecycle claim.

    FMOD graph topology is sufficient to select the *kind* of original-bank
    probe, but not to infer initial-inside, release/falling, timeline period,
    or an Android host signal.  Each finite persistent source stays blocked
    until a callback/PCM lifecycle record for its exact source identity is
    attached by the preflight oracle.
    """

    if lifetime == "continuous":
        return None
    persistent_suffixes = {
        "engine_int",
        "engine_ext",
        "transmission",
        "transmission_ext",
        "turbo",
        "limiter",
    }
    if suffix not in persistent_suffixes:
        return {
            "schema": "byd-fmod-finite-lifecycle-topology-v1",
            "status": "hostSemanticTrigger",
            "topology": "externalSemanticTrigger",
        }
    has_timeline = bool(scheduler.get("timelinePlacements"))
    has_placement = placement_entry is not None
    if has_timeline and has_placement:
        topology = "timelineAndParameterPlacement"
        required_probe = "eventStartInsideOutsideAndPlacementReentryWithTimelinePhase"
    elif has_timeline:
        topology = "timelinePlacementOnly"
        required_probe = "timelinePeriodAndStopStartPhaseWithOriginalBankCallbacksAndPcm"
    elif has_placement:
        topology = "parameterPlacementOnly"
        required_probe = "eventStartInsideOutsideAndParameterEntryDirectionWithOriginalBankCallbacksAndPcm"
    else:
        topology = "noTimelineOrHostControllablePlacement"
        required_probe = "identifyExactHostLifecycleOrClassifyHostUnreachable"
    return {
        "schema": "byd-fmod-finite-lifecycle-topology-v1",
        "status": "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
        "topology": topology,
        "requiredProbe": required_probe,
        "timelinePlacements": copy.deepcopy(scheduler.get("timelinePlacements", [])),
    }


def _finite_lifecycle_evidence_index(
    document: Mapping[str, Any] | None,
    *,
    bank_sha256: str,
) -> tuple[dict[tuple[str, str, str], Mapping[str, Any]], str | None]:
    """Validate a source-solo lifecycle preflight before recipe promotion.

    The preflight report is deliberately external build evidence, not an APK
    runtime table.  It may promote only the exact event/source/authored-binding
    row that it source-solo observed against this bank; a matching diagnostic
    name, a different bank, or a partial report is never sufficient.
    """

    if document is None:
        return {}, None
    if (
        document.get("schema")
        != "byd-full-event-parameter-placement-lifecycle-preflight-v1"
        or document.get("status") != "PASS"
        or document.get("sourceBankSha256") != bank_sha256
        or document.get("sourceCoverageExact") is not True
    ):
        raise ProfileRecipeError(
            "finite lifecycle evidence is not a passing exact-bank source-solo preflight"
        )
    sources = document.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ProfileRecipeError("finite lifecycle evidence has no source records")
    index: dict[tuple[str, str, str], Mapping[str, Any]] = {}
    for source in sources:
        if not isinstance(source, Mapping) or source.get("pass") is not True:
            raise ProfileRecipeError("finite lifecycle evidence contains a failed source")
        event_path = str(source.get("eventPath") or "")
        source_guid = _guid(source.get("sourceGuid"))
        binding_key = str(source.get("authoredBindingKey") or "")
        if (
            not event_path
            or not source_guid
            or not re.fullmatch(r"binding:[0-9a-f]{64}", binding_key)
            or not isinstance(source.get("vectors"), list)
            or not source["vectors"]
            or any(
                not isinstance(vector, Mapping) or vector.get("pass") is not True
                for vector in source["vectors"]
            )
        ):
            raise ProfileRecipeError("finite lifecycle evidence source is incomplete")
        key = (event_path, source_guid, binding_key)
        if key in index:
            raise ProfileRecipeError("finite lifecycle evidence duplicates an authored binding")
        index[key] = source
    return index, hashlib.sha256(canonical_json_bytes(document)).hexdigest()


def _apply_parameter_placement_lifecycle_evidence(
    source: dict[str, Any],
    *,
    event_path: str,
    evidence: Mapping[tuple[str, str, str], Mapping[str, Any]],
    evidence_sha256: str | None,
) -> None:
    """Promote only a source-proven parameter-placement finite lifecycle.

    Timeline-plus-placement sources remain blocked here: their source-solo
    entry vectors do not establish timeline phase/period/restart semantics.
    A dedicated timeline oracle must add that evidence instead of letting a
    generic placement callback erase the distinction.
    """

    topology = source.get("finiteLifecycleTopology")
    if (
        not isinstance(topology, Mapping)
        or topology.get("status") != "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE"
    ):
        return
    key = (
        event_path,
        _guid(source.get("sourceGuid")),
        str(source.get("authoredBindingKey") or ""),
    )
    proof = evidence.get(key)
    if proof is None:
        return
    if topology.get("topology") != "parameterPlacementOnly":
        source.setdefault("warnings", []).append(
            "sourceSoloPlacementEvidenceInsufficientForTimelinePhaseLifecycle"
        )
        return
    entry = source.get("parameterPlacementEntry")
    if not isinstance(entry, Mapping):
        raise ProfileRecipeError("placement lifecycle evidence has no source contract")
    initial_membership = proof.get("runtimeInitialMembership")
    if not isinstance(initial_membership, bool):
        raise ProfileRecipeError("placement lifecycle evidence lacks initial membership")
    promoted_entry = copy.deepcopy(dict(entry))
    promoted_entry["initialState"] = {
        "when": "exactEventInstanceCreated",
        # Runtime membership is evaluated at the exact owning EventInstance
        # activation.  The source-solo report proves the FMOD topology and
        # vectors, but it must not freeze a capture-time random branch or a
        # static initial boolean into Android's live state machine.
        "inside": "startOnceAtCurrentHostParameterValue",
        "outside": "remainSilentUntilOutsideToInsideEntry",
        "evidenceReportSha256": evidence_sha256,
    }
    promoted_topology = copy.deepcopy(dict(topology))
    promoted_topology.update(
        {
            "status": "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE",
            "evidenceReportSha256": evidence_sha256,
            "evidenceSourceKey": {
                "eventPath": key[0],
                "sourceGuid": key[1],
                "authoredBindingKey": key[2],
            },
        }
    )
    source["parameterPlacementEntry"] = promoted_entry
    source["finiteLifecycleTopology"] = promoted_topology
    source["triggers"] = ["PARAMETER_PLACEMENT_ENTRY"]
    source["semanticLifecycle"] = _semantic_lifecycle_contract(
        source["triggers"], str(source["lifetime"]), promoted_entry
    )
    source["warnings"] = sorted(
        warning
        for warning in source.get("warnings", [])
        if warning != "runtimeTriggerWithheldUntilOriginalBankLifecycleProof"
    )


def _authored_binding_key(
    *,
    event_path: str,
    source_guid: str,
    chain: Sequence[tuple[str, Mapping[str, Any]]],
    placements: Mapping[str, Sequence[Mapping[str, Any]]],
    scheduler: Mapping[str, Any],
) -> str:
    """Return one lossless scheduling identity, independent of PCM corners.

    ``sourceGuid`` identifies a waveform instrument, not necessarily one
    authored scheduling occurrence.  Bind all graph facts that determine
    scheduling/placement into a compact opaque key so every node can point
    back to exactly one raw FMOD binding.  A multi-parent source is already
    rejected by ``_instrument_chain``; keeping that invariant explicit here
    prevents a later dedupe from silently collapsing two occurrences.
    """

    payload = {
        "schema": "byd-fmod-authored-scheduling-binding-v1",
        "eventPath": event_path,
        "sourceGuid": source_guid,
        "instrumentChainSourceToRoot": [guid for guid, _item in chain],
        "parameterPlacements": {
            parameter: [dict(item) for item in values]
            for parameter, values in sorted(placements.items())
        },
        "schedulingGroupId": scheduler.get("groupId"),
        "schedulingComposition": scheduler.get("composition"),
        "timelinePlacements": scheduler.get("timelinePlacements", []),
    }
    return "binding:" + hashlib.sha256(canonical_json_bytes(payload)).hexdigest()


def _semantic_lifecycle_contract(
    triggers: Sequence[str],
    lifetime: str,
    placement_entry: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Make host signal and retrigger semantics executable, never suffix-led."""

    contracts: dict[str, dict[str, Any]] = {
        "TRANSMISSION_LOOP": {
            "signal": "EngineSimulation.transmissionActive",
            "start": "falseToTrue",
            "update": "whileTrueUseCurrentAuthoredParameterBindings",
            "stop": "trueToFalse",
            "retrigger": "noneWhileActive",
        },
        "TRANSMISSION_PULSE": {
            "signal": "EngineSimulation.transmissionPulseSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "parameterSample": "atSequenceEdgeUseCurrentAuthoredParameterBindings",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "ENGINE_EVENT_START": {
            "signal": "EngineSimulation.engineEventInstanceGeneration",
            "start": "onceForEveryNewSelectedPerspectiveEngineEventInstance",
            "parameterSample": "atInstanceCreationUseCurrentAuthoredParameterBindings",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "onlyAfterEngineEventInstanceGenerationChanges",
        },
        "EVENT_INSTANCE_START": {
            "signal": "EngineSimulation.eventInstanceGenerationByEventPath",
            "start": "onceForEveryNewExactEventPathEventInstanceActivationGeneration",
            "parameterSample": "atExactEventInstanceCreationUseCurrentAuthoredParameterBindings",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "onlyAfterExactEventInstanceGenerationChanges",
        },
        "PARAMETER_PLACEMENT_ENTRY": {
            "signal": "EngineSimulation.eventInstanceHostParameters",
            "start": "onlyAsSpecifiedByOriginalBankProvenParameterPlacementEntryInitialStateOrOutsideToInsideTransition",
            "parameterSample": "currentHostParameterBindingsAtExactDspBlock",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyOutsideToInsidePlacementEntrySubjectToSchedulingGroupPolyphony",
        },
        "ENGINE_START": {
            "signal": "EngineSimulation.ignitionCycleGeneration",
            "start": "onceForEveryStrictlyIncreasingIgnitionCycleGeneration",
            "parameterSample": "atIgnitionEdgeUseCurrentAuthoredParameterBindings",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "onlyAfterEngineStoppedThenNextIgnitionCycleGeneration",
        },
        "SHIFT_UP": {
            "signal": "EngineSimulation.successfulUpShiftSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "SHIFT_DOWN": {
            "signal": "EngineSimulation.successfulDownShiftSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "SHIFT_REJECTED": {
            "signal": "EngineSimulation.rejectedShiftSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "THROTTLE_LIFT": {
            "signal": "EngineSimulation.throttleLiftSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "TURBO_LOOP": {
            "signal": "EngineSimulation.turboActive",
            "start": "falseToTrue",
            "update": "whileTrueUseCurrentAuthoredParameterBindings",
            "stop": "trueToFalse",
            "retrigger": "noneWhileActive",
        },
        "TURBO_DUMP": {
            "signal": "EngineSimulation.turboDumpSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "LIMITER_LOOP": {
            "signal": "EngineSimulation.limiterActive",
            "start": "falseToTrue",
            "update": "whileTrueUseCurrentAuthoredParameterBindings",
            "stop": "trueToFalse",
            "retrigger": "noneWhileActive",
        },
        "LIMITER_PULSE": {
            "signal": "EngineSimulation.limiterPulseSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
        "TRACTION_LIMIT": {
            "signal": "EngineSimulation.tractionLimitActive",
            "start": "falseToTrue",
            "update": "whileTrueUseCurrentAuthoredParameterBindings",
            "stop": "trueToFalse",
            "retrigger": "noneWhileActive",
        },
        "TRACTION_PULSE": {
            "signal": "EngineSimulation.tractionLimitPulseSequence",
            "start": "onceForEveryStrictlyIncreasingSequenceValue",
            "stop": "capturedOneShotOrFiniteRepeatEnd",
            "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
        },
    }
    result = []
    for trigger in triggers:
        contract = contracts.get(trigger)
        if contract is None:
            raise ProfileRecipeError(
                f"no exact semantic lifecycle contract for trigger {trigger}"
            )
        item = {"trigger": trigger, "lifetime": lifetime, **contract}
        if trigger == "PARAMETER_PLACEMENT_ENTRY":
            if placement_entry is None:
                raise ProfileRecipeError(
                    "parameter-placement lifecycle has no placement entry contract"
                )
            item["parameterPlacementEntry"] = dict(placement_entry)
        result.append(item)
    return result


def _host_parameter_bindings(
    suffix: str, authored_defaults: Mapping[str, Any]
) -> list[dict[str, Any]]:
    """List only parameters the host is allowed to overwrite at runtime.

    Every other FMOD parameter remains at the source-specific authored/default
    value in ``runtimeMapping.parameters``.  In particular this prevents an
    Android renderer from guessing that ``valved`` or ``transmission_load`` is
    one merely because it participates in an event layout.
    """

    candidates: dict[str, dict[str, Any]]
    if suffix in {"engine_int", "engine_ext"}:
        candidates = {
            "rpms": {"source": "EngineSimulation.rpm"},
            "throttle": {"source": "EngineSimulation.throttle"},
        }
    elif "transmission" in suffix or "drivetrain" in suffix:
        candidates = {
            "drivetrain_speed": {"source": "EngineSimulation.drivetrainSpeed"},
            "throttle": {"source": "EngineSimulation.throttle"},
        }
    elif "gear" in suffix:
        candidates = {"state": {"source": "EngineSimulation.gearState"}}
    elif "backfire" in suffix or "overrun" in suffix:
        candidates = {"throttle": {"constant": 0.01}}
    elif "turbo" in suffix or "supercharger" in suffix or "bov" in suffix:
        candidates = {
            "boost": {"source": "EngineSimulation.turboBoost"},
            "bov": {"source": "EngineSimulation.turboBov"},
            "bov_decay": {"source": "EngineSimulation.turboBovDecay"},
        }
    elif "limiter" in suffix:
        candidates = {"decay": {"source": "EngineSimulation.limiterDecay"}}
    elif "traction" in suffix:
        candidates = {"decay": {"source": "EngineSimulation.tractionDecay"}}
    else:
        candidates = {}
    return [
        {"parameter": parameter, **binding}
        for parameter, binding in candidates.items()
        if parameter in authored_defaults
    ]


def _finite_scheduler_chance(value: object) -> bool:
    """Accept FMOD's omitted chance as its documented 100 percent default."""

    if value is None:
        return True
    try:
        chance = float(value)
    except (TypeError, ValueError):
        return False
    return math.isfinite(chance) and 0.0 <= chance <= 100.0


def _finite_scheduler_weight(value: object) -> bool:
    """Accept an omitted child weight as FMOD's documented unit weight."""

    if value is None:
        return True
    try:
        weight = float(value)
    except (TypeError, ValueError):
        return False
    return math.isfinite(weight) and weight > 0.0


def _playlist_selection_runtime_contract() -> dict[str, Any]:
    """One literal Android/Python contract for FMOD playlist scheduling.

    This object deliberately describes the arithmetic rather than a friendly
    approximation.  It is copied onto every playlist group so an index reader
    never has to infer a PRNG, chance ordering, or SmartRandom history rule.
    ``atlasFamilyId`` is the runtime-index id emitted by the atlas finalizer.
    """

    return {
        "schema": PLAYLIST_SELECTION_SCHEMA,
        # Selection state is intentionally selection-kind specific.  The
        # original-bank probes prove that PlaySequential resets at a new
        # EventInstance activation.  They also prove only that SmartRandom
        # advances across a stop/rewind/start; the scope could still be the
        # event description or group rather than the Studio session.  Android
        # uses an explicit deterministic substitute scoped to its persistent
        # host event owner.  It reproduces branches/chances/weights/lifecycle,
        # while intentionally making no claim of identical random sequences.
        "stateScope": "selectionKindSpecificSeeSelectionStateOwnership",
        "selectionStateOwnership": {
            "playSequential": {
                "scope": "perExactEventPathEventInstanceActivationGenerationAndGroupId",
                "originalBankEvidence": "stopRewindStartSameEventInstanceResetsAuthoredCursor",
            },
            "smartRandom": {
                "fmodObservedScope": "advancesAcrossStopRewindStart; exactScopeNotSeparatedBetweenStudioSessionEventDescriptionAndGroup",
                "androidDeterministicSubstituteScope": "perProfileAudioSessionGenerationExactEventPathAndGroupId",
                "originalBankEvidence": "freshEventInstancesAndStopRewindStartAdvanceObservedStream",
                "sequenceParity": "notClaimed",
                "androidPolicy": "deterministicSubstituteRequiresIndependentMemberCoverageWeightChanceDistributionAndLifecycleOracle",
            },
        },
        "seedDerivation": {
            "encoding": "utf8",
            "formula": "sha256('byd-fmod-playlist-v3|'+atlasFamilyId+'|'+eventPath+'|'+profileAudioSessionGeneration+'|'+groupId)",
            "atlasFamilyId": "runtimeIndex.id",
            "appliesTo": "androidDeterministicSmartRandomSubstituteOnly; notFMODSequenceParity",
            "take": "first8BytesBigEndianUnsigned",
            "zeroSeedReplacementUnsigned": "0x9e3779b97f4a7c15",
        },
        "rng": {
            "algorithm": "xorshift64star-v1",
            "unsignedArithmetic": "uint64Modulo2To64",
            "stateTransition": [
                "x = x xor (x unsignedShiftRight 12)",
                "x = x xor ((x shiftLeft 25) modulo2To64)",
                "x = x xor (x unsignedShiftRight 27)",
            ],
            "output": "postTransitionStateTimes2685821657736338717Modulo2To64",
            "uniform": "(outputUnsigned unsignedShiftRight 11) / 9007199254740992.0",
        },
        "groupTriggerChance": {
            "source": "multiInstrument.baseProperties.triggerChancePercent",
            "defaultPercentWhenNull": 100.0,
            "drawConsumption": "oneRngOutputBeforePlaylistSelectionForEverySemanticTrigger",
            "acceptance": "uniformTimes100 < triggerChancePercent",
            "onRejected": "silentNoCursorOrHistoryUpdate",
        },
        "selection": {
            "playSequential": {
                "cursorScope": "perExactEventPathEventInstanceActivationGenerationAndGroupId",
                "initialCursor": 0,
                "order": "ascendingAuthoredOrderWrap",
                "weights": "ignored",
                "cursorAdvance": "afterGroupAcceptanceBeforeMemberChanceEvenWhenMemberChanceRejects",
            },
            "smartRandom": {
                "stateScope": "AndroidDeterministicSubstitutePerProfileAudioSessionGenerationExactEventPathAndGroupId; FMODSequenceParityNotClaimed",
                "drawConsumption": "oneRngOutputAfterGroupAcceptance",
                "weight": "positiveAuthoredWeightDefaultOne",
                "weightedBoundary": "uniformTimesTotalWeight < cumulativeWeight; finalMemberFallbackOnlyForFloatingPointRoundup",
                "noImmediateRepeat": "excludeLastSelectedOnlyWhenMemberCountAtLeast3AndEveryMemberTriggerChancePercentIs100",
                "historyUpdate": "afterMemberSelectionBeforeMemberChance",
            },
        },
        "memberTriggerChance": {
            "source": "waveformInstrument.baseProperties.triggerChancePercent",
            "defaultPercentWhenNull": 100.0,
            "drawConsumption": "oneRngOutputAfterMemberSelectionIncludingZeroAnd100Percent",
            "acceptance": "uniformTimes100 < triggerChancePercent",
            "onRejected": "silentButSelectionCursorAndHistoryRemainAdvanced",
        },
        "invalidAuthoredValue": "blockReleaseNonFiniteChanceOrChanceOutside0To100OrNonFiniteNonPositiveWeight",
    }


def _simultaneous_layer_selection_runtime_contract() -> dict[str, Any]:
    """The intentionally smaller deterministic contract for one direct layer."""

    playlist_contract = _playlist_selection_runtime_contract()
    return {
        "schema": PLAYLIST_SELECTION_SCHEMA,
        "schedulerKind": "simultaneousLayer",
        "stateScope": playlist_contract["stateScope"],
        "seedDerivation": playlist_contract["seedDerivation"],
        "rng": playlist_contract["rng"],
        "selection": {
            "kind": "always",
            "drawConsumption": "oneRngOutputForThisLayerChanceOnEverySemanticTrigger",
            "history": "none",
        },
        "triggerChance": {
            "source": "waveformInstrument.baseProperties.triggerChancePercent",
            "defaultPercentWhenNull": 100.0,
            "acceptance": "uniformTimes100 < triggerChancePercent",
            "onRejected": "silent",
        },
        "invalidAuthoredValue": playlist_contract["invalidAuthoredValue"],
    }


def _effect_scheduler_contract(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    source_guid: str,
    chain: Sequence[tuple[str, Mapping[str, Any]]],
) -> dict[str, Any]:
    """Preserve FMOD's layer-vs-playlist topology without name-based guesses.

    Sibling waveform instruments are not always random alternatives: separate
    event/timeline roots are simultaneous layers.  Only a closest
    ``MultiInstrumentNode`` turns its direct children into an authored
    playlist group.  Nested playlists remain explicit but release-blocked
    until the Android evaluator supports their full traversal semantics.
    """

    waveform = indexed.instruments[source_guid]
    waveform_properties = waveform.get("baseProperties")
    if not isinstance(waveform_properties, Mapping):
        raise ProfileRecipeError(f"source {source_guid} has no baseProperties")
    multi_ancestors = [
        (guid, instrument)
        for guid, instrument in chain[1:]
        if instrument.get("kind") == "MultiInstrumentNode"
    ]
    timeline_placements = [
        {
            "instrumentGuid": _guid(placement.get("instrumentGuid")),
            "startTime": placement.get("startTime"),
            "length": placement.get("length"),
            "timeLocked": placement.get("timeLocked"),
        }
        for placement in event.get("timelinePlacements", [])
        if isinstance(placement, Mapping)
        and _guid(placement.get("instrumentGuid")) in {guid for guid, _ in chain}
    ]
    if not multi_ancestors:
        trigger_chance = waveform_properties.get("triggerChancePercent")
        return {
            "composition": "simultaneousLayer",
            "groupId": f"layer:{source_guid}",
            "selection": {
                "kind": "always",
                "triggerChance": {
                    "source": "waveformInstrument.baseProperties.triggerChancePercent",
                    "percent": trigger_chance,
                    "defaultPercentWhenNull": 100.0,
                    "activation": "independentPerSemanticTrigger",
                    "acceptance": "uniformTimes100 < triggerChancePercent",
                },
            },
            "selectionRuntimeContract": _simultaneous_layer_selection_runtime_contract(),
            "members": [
                {
                    "sourceGuid": source_guid,
                    "authoredOrder": 0,
                    "weight": 1.0,
                    "triggerChancePercent": trigger_chance,
                }
            ],
            "timelinePlacements": timeline_placements,
            "complete": _finite_scheduler_chance(trigger_chance),
            "incompleteReason": (
                None
                if _finite_scheduler_chance(trigger_chance)
                else "invalidSimultaneousLayerTriggerChance"
            ),
        }

    group_guid, group = multi_ancestors[0]
    children = group.get("childInstruments")
    playlist = group.get("playlist")
    direct_children = (
        isinstance(children, list)
        and all(isinstance(child, Mapping) for child in children)
        and all(
            _guid(child.get("guid")) in indexed.instruments
            and indexed.instruments[_guid(child.get("guid"))].get("kind")
            == "WaveformInstrumentNode"
            for child in children
        )
    )
    members: list[dict[str, Any]] = []
    if direct_children:
        authored_children: list[tuple[int, Mapping[str, Any]]] = []
        for child in children:
            raw_order = child.get("authoredOrder")
            if (
                isinstance(raw_order, bool)
                or not isinstance(raw_order, int)
                or raw_order < 0
            ):
                raise ProfileRecipeError(
                    f"playlist group {group_guid} child has no non-negative authoredOrder"
                )
            authored_children.append((raw_order, child))
        authored_children.sort(key=lambda item: item[0])
        if [order for order, _child in authored_children] != list(range(len(children))):
            raise ProfileRecipeError(
                f"playlist group {group_guid} authoredOrder values are not contiguous"
            )
        for order, child in authored_children:
            child_guid = _guid(child.get("guid"))
            child_properties = indexed.instruments[child_guid].get(
                "baseProperties"
            )
            members.append(
                {
                    "sourceGuid": child_guid,
                    "authoredOrder": order,
                    "weight": child.get("weight"),
                    "triggerChancePercent": (
                        child_properties.get("triggerChancePercent")
                        if isinstance(child_properties, Mapping)
                        else None
                    ),
                }
            )
    supported_playlist = (
        isinstance(playlist, Mapping)
        and playlist.get("playMode")
        in {
            "PlaylistPlayMode_SmartRandom",
            "PlaylistPlayMode_PlaySequential",
        }
        and playlist.get("selectionMode") == "PlaylistSelectionMode_SelectNormal"
    )
    group_properties = group.get("baseProperties")
    group_trigger_chance = (
        group_properties.get("triggerChancePercent")
        if isinstance(group_properties, Mapping)
        else None
    )
    authored_values_valid = _finite_scheduler_chance(group_trigger_chance) and all(
        _finite_scheduler_weight(member.get("weight"))
        and _finite_scheduler_chance(member.get("triggerChancePercent"))
        for member in members
    )
    complete = (
        direct_children
        and isinstance(playlist, Mapping)
        and len(multi_ancestors) == 1
        and supported_playlist
        and authored_values_valid
        and any(member["sourceGuid"] == source_guid for member in members)
    )
    return {
        "composition": "playlistAlternative",
        "groupId": f"multi:{group_guid}",
        "selection": {
            "kind": "fmodMultiInstrumentPlaylist",
            "playMode": playlist.get("playMode") if isinstance(playlist, Mapping) else None,
            "playModeValue": playlist.get("playModeValue") if isinstance(playlist, Mapping) else None,
            "selectionMode": playlist.get("selectionMode") if isinstance(playlist, Mapping) else None,
            "selectionModeValue": playlist.get("selectionModeValue") if isinstance(playlist, Mapping) else None,
        },
        "groupTriggerChancePercent": group_trigger_chance,
        "selectionRuntimeContract": {
            **_playlist_selection_runtime_contract(),
            "schedulerKind": "playlistAlternative",
        },
        "members": members,
        "timelinePlacements": timeline_placements,
        "complete": complete,
        "incompleteReason": (
            None
            if complete
            else (
                "nestedMultiInstrumentPlaylistUnsupported"
                if len(multi_ancestors) > 1
                else (
                    "invalidMultiInstrumentPlaylistAuthoredChanceOrWeight"
                    if not authored_values_valid
                    else "unsupportedOrIncompleteMultiInstrumentPlaylist"
                )
            )
        ),
    }


def _effect_source_recipe(
    indexed: IndexedGraph,
    event: Mapping[str, Any],
    source_guid: str,
    *,
    has_transmission_ext: bool = False,
) -> dict[str, Any]:
    source = indexed.instruments[source_guid]
    properties = source.get("baseProperties")
    if not isinstance(properties, dict):
        raise ProfileRecipeError(f"source {source_guid} has no baseProperties")
    loop_count = int(properties.get("loopCount"))
    lifetime = "continuous" if loop_count == -1 else "oneShot" if loop_count == 0 else "finiteRepeat"
    chain = _instrument_chain(indexed, source_guid)
    chain_guids = {guid for guid, _instrument in chain}
    controllers = _chain_controllers(indexed, chain)
    gain_db, pitch_semitones, warnings = _static_properties(chain, controllers)
    warnings.extend(
        _source_warnings(indexed, chain, controllers, continuous_engine=False)
    )
    suffix = _event_suffix(event.get("path"))
    runtime_parameters = _effect_runtime_parameters(indexed, event, chain_guids)
    authored_parameter_defaults = _effect_authored_parameter_defaults(indexed, event)
    host_parameter_bindings = _host_parameter_bindings(
        suffix, runtime_parameters
    )
    capture = _effect_capture_recipe(
        indexed,
        event,
        source,
        chain_guids,
        lifetime,
        controllers,
        host_parameter_bindings,
    )
    placement_parameter_names = sorted(
        {
            str(placement.get("parameterName") or "").casefold()
            for placement in event.get("parameterPlacements", [])
            if isinstance(placement, Mapping)
            and _guid(placement.get("instrumentGuid")) in chain_guids
            and str(placement.get("parameterName") or "").strip()
        }
    )
    placements = {
        parameter: _chain_placements(event, chain_guids, parameter)
        for parameter in placement_parameter_names
    }
    parameter_domains = {
        parameter: list(_event_parameter_domain(indexed, event, parameter))
        for parameter in sorted(placements)
    }
    scheduler = _effect_scheduler_contract(indexed, event, source_guid, chain)
    authored_binding_key = _authored_binding_key(
        event_path=str(event["path"]),
        source_guid=source_guid,
        chain=chain,
        placements=placements,
        scheduler=scheduler,
    )
    engine_program_role: str | None = None
    engine_program_role_evidence: dict[str, Any] | None = None
    if suffix in {"engine_int", "engine_ext"}:
        engine_program_role, engine_program_role_evidence = (
            _classify_finite_engine_program_role(
                indexed=indexed,
                event=event,
                source_guid=source_guid,
                chain=chain,
                controllers=controllers,
                throttle_placements=list(placements.get("throttle", [])),
                authored_binding_key=authored_binding_key,
                scheduling_group_id=str(scheduler.get("groupId") or ""),
            )
        )
    placement_entry = (
        _placement_entry_contract(
            placements, host_parameter_bindings, runtime_parameters
        )
        if lifetime != "continuous"
        else None
    )
    lifecycle_topology = _finite_lifecycle_topology(
        suffix=suffix,
        lifetime=lifetime,
        placement_entry=placement_entry,
        scheduler=scheduler,
    )
    triggers, trigger_warnings = _effect_trigger_for_source(
        event,
        chain_guids,
        suffix,
        lifetime,
        placement_entry,
        has_timeline_placement=bool(scheduler.get("timelinePlacements")),
    )
    warnings.extend(trigger_warnings)
    if (
        isinstance(lifecycle_topology, Mapping)
        and lifecycle_topology.get("status")
        == "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE"
    ):
        # Preserve the exact graph topology and placement data for the
        # original-bank oracle, but do not hand Android an apparently
        # executable trigger merely because a source lies on a parameter sheet
        # or timeline.  For example, a persistent timeline can be periodic,
        # a start-time conditional, or have a phase/re-entry rule that the
        # host signal cannot represent yet.
        triggers = []
        warnings.append("runtimeTriggerWithheldUntilOriginalBankLifecycleProof")
    if scheduler["complete"] is not True:
        warnings.append(str(scheduler["incompleteReason"]))
    semantic_lifecycle = _semantic_lifecycle_contract(
        triggers, lifetime, placement_entry
    )
    # A finite source hosted by an engine event is replayed alongside that
    # event's loop bed and therefore retains the engine instance gain.  Every
    # other event is a separately started effect instance at unity host gain.
    host_gain_class = (
        "engineEvent"
        if suffix in {"engine_int", "engine_ext"}
        else "effectEvent"
    )
    return {
        "sourceGuid": source_guid,
        "authoredBindingKey": authored_binding_key,
        "assetName": (
            f"nrt_effect_{_slug(suffix)}_{source_guid[:12]}.wav"
        ),
        "eventSuffix": suffix,
        "perspective": _effect_perspective(
            suffix,
            has_transmission_ext=has_transmission_ext,
        ),
        "eventInstanceOwnership": _event_instance_ownership(suffix),
        "lifetime": lifetime,
        "finiteLifecycleTopology": lifecycle_topology,
        "triggers": triggers,
        "semanticLifecycle": semantic_lifecycle,
        "hostGainClass": host_gain_class,
        "engineProgramRole": engine_program_role,
        "engineProgramRoleEvidence": engine_program_role_evidence,
        "hostParameterBindings": host_parameter_bindings,
        "runtimeParameters": runtime_parameters,
        "authoredParameterDefaults": authored_parameter_defaults,
        "baseGainDbToBakeIntoWav": gain_db,
        "capture": capture,
        "basePitchSemitones": pitch_semitones,
        "autoPitchRoot": _finite(
            properties.get("autoPitchReference", 1.0), f"source {source_guid} AutoPitch"
        ),
        "placements": placements,
        "parameterDomains": parameter_domains,
        "parameterPlacementEntry": placement_entry,
        "controllers": [
            {
                "controllerGuid": _guid(controller.get("guid")),
                "parameter": _parameter_name(controller),
                "propertyIndex": _property_index(controller),
                "points": _curve_points(controller),
            }
            for controller in controllers
        ],
        "schedulingGroup": scheduler,
        "sourceEvidence": _sample_evidence(source),
        "warnings": sorted(set(warnings)),
    }


def _group_android_effects(
    perspective: str, sources: Sequence[Mapping[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    grouped: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    unsupported: list[dict[str, Any]] = []
    for source in sources:
        triggers = source.get("triggers")
        if not isinstance(triggers, list) or not triggers:
            unsupported.append(
                {
                    "sourceGuid": source.get("sourceGuid"),
                    "reason": "noCurrentAndroidTriggerMapping",
                    "eventSuffix": source.get("eventSuffix"),
                    "warnings": source.get("warnings", []),
                }
            )
            continue
        for trigger in triggers:
            grouped[str(trigger)].append(source)
    effects: list[dict[str, Any]] = []
    control_by_trigger = {
        "SHIFT_UP": "gearChanges",
        "SHIFT_DOWN": "gearChanges",
        "THROTTLE_LIFT": "exhaustOverrun",
        "TRANSMISSION_LOOP": "transmission",
        "TRANSMISSION_PULSE": "transmission",
        "TURBO_LOOP": "turbo",
        "TURBO_DUMP": "turbo",
        "LIMITER_LOOP": "limiter",
        "LIMITER_PULSE": "limiter",
        "TRACTION_LIMIT": "tractionLimit",
        "TRACTION_PULSE": "tractionLimit",
        "ENGINE_START": "engineStart",
        "SHIFT_REJECTED": "gearChanges",
        "ENGINE_EVENT_START": "engineEventLifecycle",
        "PARAMETER_PLACEMENT_ENTRY": "engineEventParameters",
    }
    for trigger, variants in sorted(grouped.items()):
        continuous = trigger in {"TRANSMISSION_LOOP", "TURBO_LOOP"}
        if continuous and len(variants) > 1:
            unsupported.append(
                {
                    "sourceGuids": [item["sourceGuid"] for item in variants],
                    "reason": "multipleAuthoredContinuousEffectLayersNeedPerSourceCurves",
                    "trigger": trigger,
                }
            )
        first = variants[0]
        effects.append(
            {
                "id": f"{perspective}_{trigger.casefold()}",
                "control": control_by_trigger[trigger],
                "assetName": first["assetName"],
                "variantAssetNames": [item["assetName"] for item in variants[1:]],
                "trigger": trigger,
                "baseGainDb": 0.0,
                "minimumRpm": 0.0,
                "sourceGuids": [item["sourceGuid"] for item in variants],
                "requiresWavGainBake": True,
                "warnings": sorted(
                    {
                        warning
                        for item in variants
                        for warning in item.get("warnings", [])
                    }
                ),
            }
        )
    return effects, unsupported


def _event_sources(
    indexed: IndexedGraph, event: Mapping[str, Any]
) -> list[str]:
    if event.get("mappingComplete") is not True:
        raise ProfileRecipeError(f"event {event.get('path')} mapping is incomplete")
    reachable = event.get("reachableInstrumentGuids")
    if not isinstance(reachable, list):
        raise ProfileRecipeError(f"event {event.get('path')} reachable graph is invalid")
    return sorted(
        guid
        for guid in (_guid(item) for item in reachable)
        if guid in indexed.instruments
        and indexed.instruments[guid].get("kind") == "WaveformInstrumentNode"
    )


def _add_extraction_source(
    result: dict[str, dict[str, Any]],
    source: Mapping[str, Any],
    asset_name: str,
    *,
    gain_bake_db: float,
    capture: Mapping[str, Any] | None = None,
) -> None:
    evidence = _sample_evidence(source)
    properties = source.get("baseProperties")
    if not isinstance(properties, dict):
        raise ProfileRecipeError(f"source {_guid(source.get('guid'))} has no properties")
    loop_count = int(properties.get("loopCount"))
    key = asset_name
    candidate = {
        "assetName": asset_name,
        "sourceGuid": _guid(source.get("guid")),
        **evidence,
        "lifetime": str(
            (capture or {}).get(
                "lifetime",
                "continuous" if loop_count == -1 else "oneShot",
            )
        ),
        "gainBakeDb": gain_bake_db,
        "primaryCapture": dict(capture or {"mode": "rawVgmstreamFallback"}),
        "fallback": {
            "mode": "rawVgmstream",
            "releaseEligible": False,
            "warning": (
                "identity/debugging only; does not include authored bus/DSP/static "
                "event processing"
            ),
        },
        "command": [
            "vgmstream-cli",
            "-i",
            "-L",
            "-s",
            str(evidence["vgmstreamStreamIndexOneBased"]),
            "-o",
            asset_name,
            "<bank-path>",
        ],
    }
    previous = result.get(key)
    if previous is not None and previous != candidate:
        raise ProfileRecipeError(f"extraction asset collision for {asset_name}")
    result[key] = candidate


def build_recipe(
    graph: Mapping[str, Any],
    guid_paths: Mapping[str, str],
    *,
    car_id: str,
    curve_projector: Callable[..., dict[str, Any]] | None = None,
    finite_lifecycle_evidence: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    enriched = graph_with_guid_paths(graph, guid_paths)
    indexed = index_graph(enriched)
    _slug(car_id)
    bank = enriched.get("bank")
    if not isinstance(bank, Mapping) or not isinstance(bank.get("sha256"), str):
        raise ProfileRecipeError("graph has no source-bank SHA-256")
    lifecycle_evidence, lifecycle_evidence_sha256 = _finite_lifecycle_evidence_index(
        finite_lifecycle_evidence,
        bank_sha256=str(bank["sha256"]),
    )
    programs: dict[str, dict[str, Any]] = {
        "cabin": {
            "layers": [],
            "effects": [],
            "eventPath": None,
            "hostParameterBindings": [
                {"parameter": "rpms", "source": "EngineSimulation.rpm"},
                {"parameter": "throttle", "source": "EngineSimulation.throttle"},
            ],
        },
        "exterior": {
            "layers": [],
            "effects": [],
            "eventPath": None,
            "hostParameterBindings": [
                {"parameter": "rpms", "source": "EngineSimulation.rpm"},
                {"parameter": "throttle", "source": "EngineSimulation.throttle"},
            ],
        },
    }
    effect_sources_by_perspective: dict[str, list[dict[str, Any]]] = defaultdict(list)
    engine_transients: list[dict[str, Any]] = []
    extraction_sources: dict[str, dict[str, Any]] = {}
    unsupported: list[dict[str, Any]] = []
    core_bindings: list[dict[str, Any]] = []
    excluded_events: list[dict[str, Any]] = []

    events = enriched["events"]
    has_transmission_ext = any(
        _event_suffix(event.get("path")) == "transmission_ext"
        and bool(_event_sources(indexed, event))
        for event in events
    )
    for event in sorted(events, key=lambda item: str(item.get("path"))):
        suffix = _event_suffix(event.get("path"))
        sources = _event_sources(indexed, event)
        scope, scope_reason = _event_scope(suffix)
        if scope == "excludedNonPowertrain":
            excluded_events.append(
                {
                    "eventPath": event["path"],
                    "reason": scope_reason,
                    "reachableSourceGuids": sources,
                }
            )
            continue
        host_event_ownership = _host_event_ownership_evidence(suffix)
        if host_event_ownership["status"] == "staticOnlyHostUnreachable":
            # Retain each exact bank identity in the conservation ledger.  It
            # is intentionally absent from NRT capture/runtime programs
            # because NativeFmodAudio never creates this event path.
            for source_guid in sources:
                source = indexed.instruments[source_guid]
                core_bindings.append(
                    {
                        "eventPath": event["path"],
                        "sourceGuid": source_guid,
                        "assetName": "",
                        "captureMode": "staticOnlyHostUnreachable",
                        "sourceEvidence": _sample_evidence(source),
                        "hostEventOwnership": host_event_ownership,
                        "runtimeMapping": None,
                    }
                )
            continue
        if suffix in ENGINE_EVENT_PERSPECTIVE:
            perspective = ENGINE_EVENT_PERSPECTIVE[suffix]
            programs[perspective]["eventPath"] = event["path"]
            for source_guid in sources:
                source = indexed.instruments[source_guid]
                properties = source.get("baseProperties") or {}
                loop_count = int(properties.get("loopCount"))
                if loop_count != -1:
                    transient = _effect_source_recipe(
                        indexed,
                        event,
                        source_guid,
                        has_transmission_ext=has_transmission_ext,
                    )
                    _apply_parameter_placement_lifecycle_evidence(
                        transient,
                        event_path=str(event["path"]),
                        evidence=lifecycle_evidence,
                        evidence_sha256=lifecycle_evidence_sha256,
                    )
                    transient["reason"] = (
                        "non-looping engine-event source preserves its authored "
                        "parameter-placement entry/re-entry lifecycle, or explicit "
                        "event-start lifecycle only when no placement exists"
                    )
                    engine_transients.append(transient)
                    _add_extraction_source(
                        extraction_sources,
                        source,
                        transient["assetName"],
                        gain_bake_db=float(
                            transient["baseGainDbToBakeIntoWav"]
                        ),
                        capture=transient["capture"],
                    )
                    core_bindings.append(
                        {
                            "eventPath": event["path"],
                            "sourceGuid": source_guid,
                            "assetName": transient["assetName"],
                            "captureMode": transient["capture"]["mode"],
                            "runtimeMapping": {
                                "kind": "engineEventTransient",
                                "perspective": perspective,
                                "perspectives": [perspective],
                                "eventInstanceOwnership": transient[
                                    "eventInstanceOwnership"
                                ],
                                "triggers": transient["triggers"],
                                "lifetime": transient["lifetime"],
                                "semanticLifecycle": transient["semanticLifecycle"],
                                "finiteLifecycleTopology": transient[
                                    "finiteLifecycleTopology"
                                ],
                                "parameterPlacements": transient["placements"],
                                "parameterDomains": transient["parameterDomains"],
                                "parameterPlacementEntry": transient[
                                    "parameterPlacementEntry"
                                ],
                                "hostGainClass": transient["hostGainClass"],
                                "engineProgramRole": transient[
                                    "engineProgramRole"
                                ],
                                "engineProgramRoleEvidence": transient[
                                    "engineProgramRoleEvidence"
                                ],
                                "hostParameterBindings": transient["hostParameterBindings"],
                                "parameters": transient["runtimeParameters"],
                                "authoredParameterDefaults": transient[
                                    "authoredParameterDefaults"
                                ],
                                "captureParameters": transient["capture"]["parameters"],
                                "parameterAxes": transient["capture"]["parameterAxes"],
                                "authoredBindingKey": transient["authoredBindingKey"],
                                "variantSourceGuid": source_guid,
                                "schedulingGroup": transient["schedulingGroup"],
                                "hostEventOwnership": host_event_ownership,
                            },
                        }
                    )
                    continue
                layer = _continuous_engine_layer(
                    indexed, event, source_guid, curve_projector
                )
                programs[perspective]["layers"].append(layer)
                _add_extraction_source(
                    extraction_sources,
                    source,
                    layer["assetName"],
                    gain_bake_db=float(layer["rawFallbackGainBakeDb"]),
                    capture=layer["capture"],
                )
                core_bindings.append(
                    {
                        "eventPath": event["path"],
                        "sourceGuid": source_guid,
                        "assetName": layer["assetName"],
                        "captureMode": layer["capture"]["mode"],
                            "runtimeMapping": {
                            "kind": "engineLayer",
                            "perspective": perspective,
                            "id": layer["id"],
                            "hostParameterBindings": layer["hostParameterBindings"],
                            "parameters": layer["runtimeParameters"],
                            "authoredParameterDefaults": layer[
                                "authoredParameterDefaults"
                            ],
                            "parameterPlacements": layer["parameterPlacements"],
                            "parameterDomains": layer["parameterDomains"],
                            "continuousParameterMembership": layer[
                                "continuousParameterMembership"
                            ],
                                "schedulingGroup": layer["schedulingGroup"],
                                "hostEventOwnership": host_event_ownership,
                        },
                    }
                )
        else:
            for source_guid in sources:
                effect_source = _effect_source_recipe(
                    indexed,
                    event,
                    source_guid,
                    has_transmission_ext=has_transmission_ext,
                )
                _apply_parameter_placement_lifecycle_evidence(
                    effect_source,
                    event_path=str(event["path"]),
                    evidence=lifecycle_evidence,
                    evidence_sha256=lifecycle_evidence_sha256,
                )
                perspective = effect_source["perspective"]
                if perspective is None:
                    # Shared effects are copied into both programs. The Android
                    # renderer loads only the selected perspective, so this
                    # does not duplicate voices at runtime.
                    for target in ("cabin", "exterior"):
                        effect_sources_by_perspective[target].append(effect_source)
                else:
                    effect_sources_by_perspective[perspective].append(effect_source)
                _add_extraction_source(
                    extraction_sources,
                    indexed.instruments[source_guid],
                    effect_source["assetName"],
                    gain_bake_db=float(
                        effect_source["baseGainDbToBakeIntoWav"]
                    ),
                    capture=effect_source["capture"],
                )
                core_bindings.append(
                    {
                        "eventPath": event["path"],
                        "sourceGuid": source_guid,
                        "assetName": effect_source["assetName"],
                        "captureMode": effect_source["capture"]["mode"],
                        "hostEventOwnership": host_event_ownership,
                        "unmappedRuntimeEvidence": {
                            "eventInstanceOwnership": effect_source[
                                "eventInstanceOwnership"
                            ],
                            "finiteLifecycleTopology": effect_source[
                                "finiteLifecycleTopology"
                            ],
                            "warnings": effect_source["warnings"],
                        },
                        "runtimeMapping": (
                            {
                            "kind": (
                                "engineEventTransient"
                                if effect_source["eventSuffix"] in {"engine_int", "engine_ext"}
                                else "effect"
                            ),
                            "perspectives": (
                                    [effect_source["perspective"]]
                                    if effect_source["perspective"] is not None
                                    else ["cabin", "exterior"]
                                ),
                                "eventInstanceOwnership": effect_source[
                                    "eventInstanceOwnership"
                                ],
                                "triggers": effect_source["triggers"],
                                "lifetime": effect_source["lifetime"],
                                "semanticLifecycle": effect_source["semanticLifecycle"],
                                "finiteLifecycleTopology": effect_source[
                                    "finiteLifecycleTopology"
                                ],
                                "parameterPlacements": effect_source["placements"],
                                "parameterDomains": effect_source["parameterDomains"],
                                "parameterPlacementEntry": effect_source[
                                    "parameterPlacementEntry"
                                ],
                                "hostGainClass": effect_source["hostGainClass"],
                                "engineProgramRole": effect_source[
                                    "engineProgramRole"
                                ],
                                "engineProgramRoleEvidence": effect_source[
                                    "engineProgramRoleEvidence"
                                ],
                                "hostParameterBindings": effect_source["hostParameterBindings"],
                                "parameters": effect_source["runtimeParameters"],
                                "authoredParameterDefaults": effect_source[
                                    "authoredParameterDefaults"
                                ],
                                "captureParameters": effect_source["capture"]["parameters"],
                                "parameterAxes": effect_source["capture"]["parameterAxes"],
                                "authoredBindingKey": effect_source["authoredBindingKey"],
                                "variantSourceGuid": source_guid,
                                "schedulingGroup": effect_source["schedulingGroup"],
                                "hostEventOwnership": host_event_ownership,
                            }
                            if effect_source["triggers"]
                            else None
                        ),
                    }
                )

    for perspective in ("cabin", "exterior"):
        programs[perspective]["layers"].sort(
            key=lambda layer: (
                layer["startRpm"],
                layer["role"],
                layer["sourceGuid"],
            )
        )
        effects, effect_unsupported = _group_android_effects(
            perspective, effect_sources_by_perspective[perspective]
        )
        programs[perspective]["effects"] = effects
        unsupported.extend(
            {"perspective": perspective, **item} for item in effect_unsupported
        )
        if programs[perspective]["eventPath"] is None:
            unsupported.append(
                {
                    "perspective": perspective,
                    "reason": "standardEngineEventMissing",
                }
            )

    bank = enriched.get("bank")
    if not isinstance(bank, dict):
        raise ProfileRecipeError("graph bank metadata is invalid")
    all_layer_warnings = [
        {
            "perspective": perspective,
            "sourceGuid": layer["sourceGuid"],
            "warnings": layer["warnings"],
        }
        for perspective, program in programs.items()
        for layer in program["layers"]
        if layer["warnings"]
    ]
    oracle_requirements: list[dict[str, Any]] = []
    if enriched.get("effects"):
        oracle_requirements.append(
            {
                "reason": "fullEventNrtSweepValidationRequiredForBankDspAndSumming",
                "validation": "compare reconstructed sweeps with the NRT event oracle",
            }
        )
    bank_sha256 = str(bank.get("sha256") or "")
    if not re.fullmatch(r"[0-9a-f]{64}", bank_sha256):
        raise ProfileRecipeError("graph bank SHA-256 is invalid")
    extraction_source_list = sorted(
        extraction_sources.values(),
        key=lambda item: (
            item["vgmstreamStreamIndexOneBased"], item["assetName"]
        ),
    )
    asset_set_document = {
        "schema": "byd-fmod-audio-asset-set-v1",
        "bankSha256": bank_sha256,
        "captureGeneratorVersion": GENERATOR_VERSION,
        "sources": [
            {
                "assetName": item["assetName"],
                "sourceGuid": item["sourceGuid"],
                "encodedPayloadSha256": item["encodedPayloadSha256"],
                "gainBakeDb": item["gainBakeDb"],
                "primaryCapture": item["primaryCapture"],
            }
            for item in extraction_source_list
        ],
    }
    asset_set_sha256 = hashlib.sha256(
        canonical_json_bytes(asset_set_document)
    ).hexdigest()
    asset_directory = f"fmod_{asset_set_sha256[:24]}"
    core_source_guids = sorted({item["sourceGuid"] for item in core_bindings})
    static_only_bindings = [
        item
        for item in core_bindings
        if isinstance(item.get("hostEventOwnership"), Mapping)
        and item["hostEventOwnership"].get("status")
        == "staticOnlyHostUnreachable"
    ]
    static_only_source_guids = sorted(
        {item["sourceGuid"] for item in static_only_bindings}
    )
    emitted_nrt_source_guids = sorted(
        {
            item["sourceGuid"]
            for item in extraction_source_list
            if str(item["primaryCapture"].get("mode", "")).startswith(
                "targetOnlyFmodNrt"
            )
        }
    )
    # Static-only paths still have one graph/evidence binding per GUID, but
    # intentionally have no NRT capture because the reference host never
    # creates their event instance.  This is conservation, not an omission.
    conservation_passes = core_source_guids == sorted(
        set(emitted_nrt_source_guids) | set(static_only_source_guids)
    )
    if not conservation_passes:
        unsupported.append(
            {
                "reason": "coreSourceConservationFailed",
                "missingNrtSourceGuids": sorted(
                    set(core_source_guids)
                    - set(emitted_nrt_source_guids)
                    - set(static_only_source_guids)
                ),
                "unexpectedNrtSourceGuids": sorted(
                    set(emitted_nrt_source_guids) - set(core_source_guids)
                ),
            }
        )
    unmapped_bindings = [
        item
        for item in core_bindings
        if item["runtimeMapping"] is None and item not in static_only_bindings
    ]
    if any(
        layer.get("throttleAmplitudeCurveExact") is not None
        for program in programs.values()
        for layer in program["layers"]
    ):
        unsupported.append(
            {
                "reason": "exactThrottleLinearAmplitudeCurveNeedsRuntimeLane",
                "detail": (
                    "generated dB points are diagnostic only; release must consume "
                    "throttleAmplitudeCurveExact without approximation"
                ),
            }
        )
    for warning in all_layer_warnings:
        if "androidPlaybackRatioClampMayDifferFromFmod" in warning["warnings"]:
            unsupported.append(
                {
                    "reason": "androidPlaybackRatioClampMayDifferFromFmod",
                    "perspective": warning["perspective"],
                    "sourceGuid": warning["sourceGuid"],
                }
            )
    recipe: dict[str, Any] = {
        "schema": RECIPE_SCHEMA,
        "generatorVersion": GENERATOR_VERSION,
        "carId": car_id,
        "bank": {
            "fileName": bank.get("fileName"),
            "sha256": bank_sha256,
            "fileVersion": bank.get("fileVersion"),
        },
        "assetFamily": {
            "id": asset_directory,
            "assetDirectory": asset_directory,
            "deduplicationKeySha256": asset_set_sha256,
            "bankSha256": bank_sha256,
            "shareAcrossProfilesOnlyWhenDeduplicationKeyMatches": True,
        },
        "programContract": {
            "LOAD": {
                "includedRoles": ["IDLE", "LOAD", "TEXTURE", "LIMITER"],
                "excludedRoles": ["COAST"],
                "allIncludedLayerThrottle": 1.0,
                "directionIndependent": True,
            },
            "COAST": {
                "includedRoles": ["IDLE", "COAST", "TEXTURE"],
                "excludedRoles": ["LOAD", "LIMITER"],
                "allIncludedLayerThrottle": 0.0,
                "directionIndependent": True,
            },
            "BOTH": {
                "includedRoles": ["IDLE", "LOAD", "COAST", "TEXTURE", "LIMITER"],
                "throttle": "livePedal",
                "directionIndependent": True,
            },
        },
        "programs": programs,
        "engineEventTransients": sorted(
            engine_transients, key=lambda item: item["sourceGuid"]
        ),
        "sourceConservationAudit": {
            "coreEventBindings": sorted(
                core_bindings,
                key=lambda item: (
                    item["eventPath"], item["sourceGuid"], item["assetName"]
                ),
            ),
            "coreReachableSourceGuids": core_source_guids,
            "emittedNrtSourceGuids": emitted_nrt_source_guids,
            "staticOnlyHostUnreachableSourceGuids": static_only_source_guids,
            "exactGuidSetEquality": conservation_passes,
            "unmappedCoreBindings": sorted(
                unmapped_bindings,
                key=lambda item: (item["eventPath"], item["sourceGuid"]),
            ),
            "staticOnlyHostUnreachableBindings": sorted(
                static_only_bindings,
                key=lambda item: (item["eventPath"], item["sourceGuid"]),
            ),
            "explicitEventExclusions": sorted(
                excluded_events, key=lambda item: item["eventPath"]
            ),
        },
        "extraction": {
            "subsoundIndexConvention": (
                "graph is zero-based; vgmstream -s is one-based"
            ),
            "assetSet": asset_set_document,
            "sources": extraction_source_list,
        },
        "warnings": all_layer_warnings,
        "runtimeExtensionsRequired": sorted(
            unsupported,
            key=lambda item: canonical_json_bytes(item),
        ),
        "oracleValidationsRequired": oracle_requirements,
        "releaseGate": {
            "status": (
                "PASS"
                if conservation_passes
                and not unmapped_bindings
                and not unsupported
                and not oracle_requirements
                else "BLOCKED"
            ),
            "requiresExactCoreSourceConservation": True,
            "requiresEveryHostInstantiatedCoreBindingMapped": True,
            "requiresEveryHostInstantiatedCoreAssetRenderedThroughFmodNrt": True,
            "allowsHostUnreachableStaticOnlyConservationEvidence": True,
            "rawVgmstreamFallbackReleaseEligible": False,
            "requiresFullEventNrtOracleValidation": True,
        },
        "classificationBasis": {
            "usesSampleNames": False,
            "usesEventGuidPaths": True,
            "usesLoopLifetime": True,
            "usesParameterPlacements": True,
            "usesSourceAndAncestorAutomation": True,
            "sampleNamesRetainedOnlyForDiagnostics": True,
        },
        "captureStrategy": {
            "primary": "targetOnlyFmodNrt",
            "reason": (
                "bakes the source's static event/bus/DSP/automatic-parameter sound "
                "while leaving RPM and throttle automation reconstructable"
            ),
            "fallback": "rawVgmstreamIdentityOnly",
            "fallbackReleaseEligible": False,
            "requiredOracle": "fullEventNrtSweepComparison",
        },
    }
    recipe["recipeSha256"] = hashlib.sha256(canonical_json_bytes(recipe)).hexdigest()
    return recipe


def selected_engine_layers(
    recipe: Mapping[str, Any], perspective: str, mode: str, live_pedal: float
) -> list[tuple[str, float]]:
    """Return layer ids/effective throttle for contract tests and audits."""

    mode = mode.upper()
    if mode not in {"LOAD", "COAST", "BOTH"}:
        raise ValueError(f"unknown engine program {mode}")
    layers = recipe["programs"][perspective]["layers"]
    included_roles = {
        "LOAD": {"IDLE", "LOAD", "TEXTURE", "LIMITER"},
        "COAST": {"IDLE", "COAST", "TEXTURE"},
        "BOTH": {"IDLE", "LOAD", "COAST", "TEXTURE", "LIMITER"},
    }[mode]
    result: list[tuple[str, float]] = []
    for layer in layers:
        role = layer["role"]
        if role not in included_roles:
            continue
        if mode == "LOAD":
            throttle = 1.0
        elif mode == "COAST":
            throttle = 0.0
        else:
            throttle = min(1.0, max(0.0, float(live_pedal)))
        result.append((layer["id"], throttle))
    return result


def build_runtime_program_record(
    recipe: Mapping[str, Any], *, allow_blocked_draft: bool = False
) -> dict[str, Any]:
    blocked = recipe.get("releaseGate", {}).get("status") != "PASS"
    if blocked and not allow_blocked_draft:
        raise ProfileRecipeError(
            "refusing runtime program for a recipe whose release gate is BLOCKED"
        )

    def runtime_layer(layer: Mapping[str, Any]) -> dict[str, Any]:
        result = {
            key: layer[key]
            for key in (
                "id",
                "assetName",
                "role",
                "startRpm",
                "endRpm",
                "autoPitchRootRpm",
                "basePitchSemitones",
                "baseGainDb",
                "applyIdleGainBoost",
            )
        }
        projection = layer.get("projectionEvidence")
        if isinstance(projection, dict):
            result["authoredAutomation"] = {
                "controllers": projection["controllers"],
                "triggerPlacements": projection["triggerPlacements"],
                "normalization": projection["normalization"],
            }
        else:
            result["authoredAutomation"] = None
        return result

    def runtime_effect(effect: Mapping[str, Any]) -> dict[str, Any]:
        return {
            key: effect[key]
            for key in (
                "id",
                "control",
                "assetName",
                "variantAssetNames",
                "trigger",
                "baseGainDb",
                "minimumRpm",
            )
        }

    return {
        "schema": "byd-engine-audio-program-v2",
        "id": recipe["assetFamily"]["id"],
        "assetDirectory": recipe["assetFamily"]["assetDirectory"],
        "draftBlocked": blocked,
        "modeContract": recipe["programContract"],
        "perspectives": {
            perspective: {
                "layers": [runtime_layer(layer) for layer in program["layers"]],
                "effects": [runtime_effect(effect) for effect in program["effects"]],
            }
            for perspective, program in recipe["programs"].items()
        },
    }


def build_catalog_profile_input(
    recipe: Mapping[str, Any], profile_metadata: Mapping[str, Any] | None = None
) -> dict[str, Any]:
    metadata = dict(profile_metadata or {})
    required = ("displayName", "preview", "physics")
    return {
        "schema": "byd-car-catalog-input-v1",
        "carId": recipe["carId"],
        "audioProgramFamilyId": recipe["assetFamily"]["id"],
        "profile": metadata,
        "missingProfileFields": [key for key in required if key not in metadata],
        "packRequirement": {
            "id": recipe["assetFamily"]["id"],
            "assetDirectory": recipe["assetFamily"]["assetDirectory"],
            "assetSetSha256": recipe["assetFamily"]["deduplicationKeySha256"],
            "bankSha256": recipe["assetFamily"]["bankSha256"],
        },
    }


def _parse_vgmstream_metadata(output: str) -> dict[str, Any]:
    patterns = {
        "frequencyHz": r"^sample rate:\s+(\d+) Hz$",
        "channels": r"^channels:\s+(\d+)$",
        "sampleCount": r"^stream total samples:\s+(\d+)",
        "streamCount": r"^stream count:\s+(\d+)$",
        "streamIndexOneBased": r"^stream index:\s+(\d+)$",
        "diagnosticName": r"^stream name:\s*(.*)$",
    }
    result: dict[str, Any] = {}
    for line in output.splitlines():
        for key, pattern in patterns.items():
            match = re.match(pattern, line.strip())
            if not match:
                continue
            result[key] = (
                match.group(1)
                if key == "diagnosticName"
                else int(match.group(1))
            )
    missing = sorted(set(patterns) - result.keys())
    if missing:
        raise ProfileRecipeError(f"vgmstream metadata is missing {missing}")
    return result


def validate_vgmstream_sources(
    recipe: Mapping[str, Any], bank_path: Path, executable: str = "vgmstream-cli"
) -> dict[str, Any]:
    bank_path = bank_path.resolve()
    expected_bank_sha = str(recipe["bank"].get("sha256") or "")
    actual_bank_sha = hashlib.sha256(bank_path.read_bytes()).hexdigest()
    if expected_bank_sha != actual_bank_sha:
        raise ProfileRecipeError("bank SHA-256 differs from graph report")
    checked: list[dict[str, Any]] = []
    seen_indexes: set[int] = set()
    for source in recipe["extraction"]["sources"]:
        stream_index = int(source["vgmstreamStreamIndexOneBased"])
        if stream_index in seen_indexes:
            continue
        seen_indexes.add(stream_index)
        completed = subprocess.run(
            [executable, "-m", "-s", str(stream_index), str(bank_path)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if completed.returncode:
            raise ProfileRecipeError(
                f"vgmstream stream {stream_index} failed: {completed.stderr.strip()}"
            )
        metadata = _parse_vgmstream_metadata(completed.stdout + completed.stderr)
        for graph_key, metadata_key in (
            ("frequencyHz", "frequencyHz"),
            ("channels", "channels"),
            ("sampleCount", "sampleCount"),
            ("vgmstreamStreamIndexOneBased", "streamIndexOneBased"),
        ):
            if int(source[graph_key]) != int(metadata[metadata_key]):
                raise ProfileRecipeError(
                    f"vgmstream stream {stream_index} {metadata_key} differs from graph"
                )
        checked.append(metadata)
    return {
        "bankSha256": actual_bank_sha,
        "checkedUniqueStreams": len(checked),
        "streams": checked,
    }


def extract_recipe_wavs(
    recipe: Mapping[str, Any], bank_path: Path, output_directory: Path,
    executable: str = "vgmstream-cli",
) -> None:
    output_directory.mkdir(parents=True, exist_ok=True)
    for source in recipe["extraction"]["sources"]:
        output = output_directory / source["assetName"]
        if output.exists():
            raise ProfileRecipeError(f"refusing to overwrite {output}")
        completed = subprocess.run(
            [
                executable,
                "-i",
                "-L",
                "-s",
                str(source["vgmstreamStreamIndexOneBased"]),
                "-o",
                str(output),
                str(bank_path),
            ],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if completed.returncode:
            output.unlink(missing_ok=True)
            raise ProfileRecipeError(
                f"failed to extract {source['assetName']}: {completed.stderr.strip()}"
            )
        # Per-variant static gain baking is intentionally left to the pack
        # compiler, which can do one normalized PCM pass for the target format.


def load_curve_projector(audio_lab_root: Path) -> Callable[..., dict[str, Any]]:
    root = audio_lab_root.resolve()
    if not (root / "sim" / "fmod_authored_curves.py").is_file():
        raise ProfileRecipeError(f"audio lab curve compiler is absent from {root}")
    root_text = str(root)
    if root_text not in sys.path:
        sys.path.insert(0, root_text)
    from sim.fmod_authored_curves import derive_manifest_source_curves

    return derive_manifest_source_curves


def _write_atomic(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(payload)
        temporary.flush()
    try:
        temporary_path.replace(path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--guids", type=Path, required=True)
    parser.add_argument("--car-id", required=True)
    parser.add_argument(
        "--audio-lab-root",
        type=Path,
        help="enable target-only NRT curve/capture recipes through this Audio Lab",
    )
    parser.add_argument("--recipe-output", type=Path, required=True)
    parser.add_argument(
        "--finite-lifecycle-evidence",
        type=Path,
        help=(
            "passing source-solo finite parameter-placement lifecycle preflight; "
            "promotes only exact parameterPlacementOnly bindings"
        ),
    )
    parser.add_argument("--runtime-program-output", type=Path)
    parser.add_argument("--catalog-input-output", type=Path)
    parser.add_argument("--profile-metadata", type=Path)
    parser.add_argument(
        "--allow-blocked-runtime-draft",
        action="store_true",
        help="emit inspection-only runtime JSON when fidelity/runtime gates block release",
    )
    parser.add_argument("--bank", type=Path)
    parser.add_argument("--vgmstream-validation-output", type=Path)
    parser.add_argument("--extract-wavs", type=Path)
    parser.add_argument("--vgmstream", default="vgmstream-cli")
    args = parser.parse_args(argv)
    try:
        graph = json.loads(args.graph.read_text(encoding="utf-8"))
        guid_paths = parse_guid_paths(
            args.guids.read_text(encoding="utf-8-sig", errors="strict")
        )
        projector = (
            load_curve_projector(args.audio_lab_root)
            if args.audio_lab_root is not None
            else None
        )
        recipe = build_recipe(
            graph,
            guid_paths,
            car_id=args.car_id,
            curve_projector=projector,
            finite_lifecycle_evidence=(
                json.loads(args.finite_lifecycle_evidence.read_text(encoding="utf-8"))
                if args.finite_lifecycle_evidence is not None
                else None
            ),
        )
        _write_atomic(args.recipe_output, canonical_json_bytes(recipe) + b"\n")
        if args.runtime_program_output is not None:
            _write_atomic(
                args.runtime_program_output,
                canonical_json_bytes(
                    build_runtime_program_record(
                        recipe,
                        allow_blocked_draft=args.allow_blocked_runtime_draft,
                    )
                )
                + b"\n",
            )
        if args.catalog_input_output is not None:
            profile_metadata = (
                json.loads(args.profile_metadata.read_text(encoding="utf-8"))
                if args.profile_metadata is not None
                else None
            )
            _write_atomic(
                args.catalog_input_output,
                canonical_json_bytes(
                    build_catalog_profile_input(recipe, profile_metadata)
                )
                + b"\n",
            )
        if args.vgmstream_validation_output is not None:
            if args.bank is None:
                parser.error("--vgmstream-validation-output requires --bank")
            validation = validate_vgmstream_sources(
                recipe, args.bank, args.vgmstream
            )
            _write_atomic(
                args.vgmstream_validation_output,
                canonical_json_bytes(validation) + b"\n",
            )
        if args.extract_wavs is not None:
            if args.bank is None:
                parser.error("--extract-wavs requires --bank")
            extract_recipe_wavs(
                recipe, args.bank.resolve(), args.extract_wavs.resolve(), args.vgmstream
            )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
