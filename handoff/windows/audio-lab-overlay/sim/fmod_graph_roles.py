"""Deterministic source-role classification for FMOD bank graph audit v3.

The classifier intentionally never reads embedded sample names.  Source policy is
derived from event reachability, instrument/controller ownership, authored
parameter curves, and loop lifetime only.  Unknown or internally inconsistent
graphs are classified as ambiguous so callers can fail closed.
"""

from __future__ import annotations

from collections import Counter, defaultdict
import json
import math
from pathlib import Path
from typing import Any, Iterable


BANK_GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
CLASSIFICATION_SCHEMA = "ac-fmod-source-role-classification-v2"
CATALOG_CLASSIFICATION_SCHEMA = "ac-fmod-catalog-source-role-classification-v2"

POLICY_EXCLUDE = "exclude"
POLICY_ALLOW_CANDIDATE = "allowCandidate"
POLICY_OUT_OF_SCOPE = "outOfScope"
POLICY_AMBIGUOUS = "ambiguous"

ROLE_EXCLUDED_LOAD = "EXCLUDED_LOAD"
ROLE_ENGINE_FALLING = "ENGINE_FALLING_CANDIDATE"
ROLE_ENGINE_INDEPENDENT = "ENGINE_THROTTLE_INDEPENDENT_CANDIDATE"
ROLE_ENGINE_RELEASE_AUDIBLE = "ENGINE_RELEASE_AUDIBLE_CANDIDATE"
ROLE_ENGINE_TRANSIENT = "ENGINE_TRANSIENT_CANDIDATE"
ROLE_TRANSMISSION = "TRANSMISSION"
ROLE_TURBO_CONTINUOUS = "TURBO_CONTINUOUS_CANDIDATE"
ROLE_TURBO_TRANSIENT = "TURBO_TRANSIENT_CANDIDATE"
ROLE_LIMITER = "LIMITER"
ROLE_OVERRUN_TRANSIENT = "OVERRUN_TRANSIENT_CANDIDATE"
ROLE_GEAR_SHIFT = "GEAR_SHIFT_TRANSIENT_CANDIDATE"
ROLE_GEAR_GRIND = "GEAR_GRIND"
ROLE_OUT_OF_SCOPE = "OUT_OF_SCOPE_NON_CORE"
ROLE_AMBIGUOUS = "AMBIGUOUS"

ENGINE_EVENT_SUFFIXES = frozenset(("engine_ext", "engine_int"))
OUT_OF_SCOPE_EVENT_SUFFIXES = frozenset(
    (
        "bodywork",
        "door",
        "horn",
        "skid_ext",
        "skid_int",
        "tractioncontrol_ext",
        "tractioncontrol_int",
        "wheel",
        "wind",
    )
)
BACKFIRE_EVENT_SUFFIXES = frozenset(("backfire_ext", "backfire_int"))
GEAR_SHIFT_EVENT_SUFFIXES = frozenset(("gear_ext", "gear_int"))

_CURVE_EPSILON_DB = 0.01
_MATERIAL_RELEASE_SUPPRESSION_DB = 12.0
_RELEASE_AUDIBILITY_FLOOR_DB = -48.0
_THROTTLE_MINIMUM = 0.0
_THROTTLE_MAXIMUM = 1.0


class SourceRoleClassificationError(ValueError):
    """Raised when a document is not a valid graph-audit v3 input."""


def _require_list(document: dict[str, Any], key: str) -> list[Any]:
    value = document.get(key)
    if not isinstance(value, list):
        raise SourceRoleClassificationError(f"{key} must be an array")
    return value


def _guid(value: Any) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _event_suffix(path: Any) -> str:
    text = str(path or "").strip().casefold().rstrip("/")
    return text.rsplit("/", 1)[-1] if text else ""


def _property_index(controller: dict[str, Any]) -> int | None:
    try:
        return int(controller.get("propertyIndex"))
    except (TypeError, ValueError):
        return None


def curve_polarity(
    points: Iterable[dict[str, Any]], *, epsilon_db: float = _CURVE_EPSILON_DB
) -> str:
    """Return rising/falling/flat/mixed/insufficient from all authored points.

    Endpoint-only checks are intentionally avoided: a curve that reverses between
    endpoints is mixed and therefore cannot be used to prove LOAD exclusion.
    """

    ordered: list[tuple[float, float]] = []
    try:
        for point in points:
            ordered.append((float(point["x"]), float(point["y"])))
    except (KeyError, TypeError, ValueError):
        return "insufficient"
    if len(ordered) < 2:
        return "insufficient"
    ordered.sort(key=lambda item: item[0])
    values = [item[1] for item in ordered]
    deltas = [right - left for left, right in zip(values, values[1:])]
    total = values[-1] - values[0]
    if max(values) - min(values) <= epsilon_db:
        return "flat"
    if total > epsilon_db and all(delta >= -epsilon_db for delta in deltas):
        return "rising"
    if total < -epsilon_db and all(delta <= epsilon_db for delta in deltas):
        return "falling"
    return "mixed"


def _loop_lifetime(instrument: dict[str, Any]) -> str | None:
    properties = instrument.get("baseProperties")
    if not isinstance(properties, dict):
        return None
    try:
        loop_count = int(properties.get("loopCount"))
    except (TypeError, ValueError):
        return None
    if loop_count == -1:
        return "continuous"
    if loop_count == 0:
        return "oneShot"
    if loop_count > 0:
        return "finiteRepeat"
    return None


def _finite_float(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _normalized_controller_points(
    controller: dict[str, Any],
) -> tuple[list[dict[str, Any]], list[tuple[float, float]], list[str]]:
    """Return auditable and interpolation forms of one automation curve."""

    raw_points = controller.get("points")
    if not isinstance(raw_points, list) or not raw_points:
        return [], [], ["throttleVolumeCurveHasNoUsablePoints"]
    auditable: list[dict[str, Any]] = []
    numeric: list[tuple[float, float]] = []
    problems: list[str] = []
    for point in raw_points:
        if not isinstance(point, dict):
            problems.append("throttleVolumeCurvePointInvalid")
            continue
        x = _finite_float(point.get("x"))
        y = _finite_float(point.get("y"))
        if x is None or y is None:
            problems.append("throttleVolumeCurvePointInvalid")
            continue
        if x < _THROTTLE_MINIMUM - 0.0001 or x > _THROTTLE_MAXIMUM + 0.0001:
            problems.append("throttleVolumeCurvePointOutsideNormalizedRange")
        auditable.append(
            {
                "x": x,
                "yDb": y,
                "type": point.get("type"),
                "shape": point.get("shape"),
            }
        )
        numeric.append((x, y))
    auditable.sort(key=lambda item: (item["x"], item["yDb"]))
    numeric.sort()
    deduplicated: list[tuple[float, float]] = []
    for x, y in numeric:
        if deduplicated and abs(x - deduplicated[-1][0]) <= 1e-7:
            if abs(y - deduplicated[-1][1]) > _CURVE_EPSILON_DB:
                problems.append("throttleVolumeCurveDuplicateXConflict")
            continue
        deduplicated.append((x, y))
    return auditable, deduplicated, sorted(set(problems))


def _linear_curve_value(points: list[tuple[float, float]], x: float) -> float:
    """Evaluate a clamped segment without pretending to reproduce curve shape."""

    if x <= points[0][0]:
        return points[0][1]
    if x >= points[-1][0]:
        return points[-1][1]
    for (left_x, left_y), (right_x, right_y) in zip(points, points[1:]):
        if x <= right_x:
            if right_x == left_x:
                return right_y
            fraction = (x - left_x) / (right_x - left_x)
            return left_y + ((right_y - left_y) * fraction)
    return points[-1][1]


def _throttle_volume_evidence(
    instrument_chain: list[tuple[str, dict[str, Any], int]],
    controllers: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], list[str]]:
    """Resolve effective source + ancestor throttle-volume automation.

    FMOD multi instruments own automation on their own routable object.  A child
    waveform therefore cannot be classified correctly by consulting only the
    child's controller list.  The effective dB curve is the sum of every volume
    automation on the unique source-to-root chain.
    """

    problems: list[str] = []
    missing_references: list[str] = []
    invalid_owner_references: list[str] = []
    unreferenced_owned: list[str] = []
    curve_records: list[dict[str, Any]] = []
    interpolation_curves: list[list[tuple[float, float]]] = []
    base_volume_db = 0.0

    for node_guid, node, depth in instrument_chain:
        properties = node.get("baseProperties")
        if not isinstance(properties, dict):
            properties = {}
        node_volume = _finite_float(properties.get("volumeDb"))
        if node_volume is not None:
            base_volume_db += node_volume
        routable_guid = _guid(properties.get("routableGuid"))
        allowed_owners = {node_guid}
        if routable_guid:
            allowed_owners.add(routable_guid)
        referenced = {_guid(item) for item in node.get("controllerGuids", [])}
        referenced.discard("")
        missing_references.extend(sorted(referenced - controllers.keys()))
        invalid_owner_references.extend(
            sorted(
                guid
                for guid in referenced & controllers.keys()
                if _guid(controllers[guid].get("propertyOwnerGuid"))
                not in allowed_owners
            )
        )
        owned_throttle_volume = {
            guid
            for guid, controller in controllers.items()
            if _guid(controller.get("propertyOwnerGuid")) in allowed_owners
            and str(controller.get("inputKind") or "").casefold() == "parameter"
            and str(controller.get("inputParameterName") or "").casefold()
            == "throttle"
            and _property_index(controller) == 0
        }
        unreferenced_owned.extend(sorted(owned_throttle_volume - referenced))
        for controller_guid in sorted(owned_throttle_volume & referenced):
            controller = controllers[controller_guid]
            auditable, numeric, curve_problems = _normalized_controller_points(
                controller
            )
            problems.extend(curve_problems)
            curve_records.append(
                {
                    "controllerGuid": controller_guid,
                    "propertyOwnerGuid": _guid(controller.get("propertyOwnerGuid")),
                    "scope": "source" if depth == 0 else "ancestor",
                    "scopeInstrumentGuid": node_guid,
                    "ancestorDepth": depth,
                    "pointTrend": curve_polarity(
                        ({"x": x, "y": y} for x, y in numeric)
                    ),
                    "points": auditable,
                }
            )
            if numeric:
                interpolation_curves.append(numeric)

    if missing_references:
        problems.append("missingReferencedController")
    if invalid_owner_references:
        problems.append("referencedControllerOwnerMismatch")
    if unreferenced_owned:
        problems.append("ownedThrottleVolumeControllerNotReferenced")

    effective_points: list[dict[str, float]] = []
    if interpolation_curves:
        sample_xs = sorted(
            {
                _THROTTLE_MINIMUM,
                _THROTTLE_MAXIMUM,
                *(
                    min(_THROTTLE_MAXIMUM, max(_THROTTLE_MINIMUM, x))
                    for curve in interpolation_curves
                    for x, _ in curve
                ),
            }
        )
        effective_points = [
            {
                "throttle": x,
                "gainDb": base_volume_db
                + sum(_linear_curve_value(curve, x) for curve in interpolation_curves),
            }
            for x in sample_xs
        ]
        trend = curve_polarity(
            ({"x": point["throttle"], "y": point["gainDb"]} for point in effective_points)
        )
        release_db = effective_points[0]["gainDb"]
        peak_point = max(
            effective_points, key=lambda point: (point["gainDb"], -point["throttle"])
        )
        peak_db = peak_point["gainDb"]
        release_suppression_db = max(0.0, peak_db - release_db)
        release_linear_ratio = 10.0 ** (-release_suppression_db / 20.0)
        peak_throttle = peak_point["throttle"]
    else:
        trend = "missing"
        release_db = None
        peak_db = None
        release_suppression_db = None
        release_linear_ratio = None
        peak_throttle = None

    evidence = {
        "trend": trend,
        "controllerGuids": [item["controllerGuid"] for item in curve_records],
        "controllerTrends": [item["pointTrend"] for item in curve_records],
        "controllers": curve_records,
        "aggregation": "sumDbAcrossSourceAndAncestors",
        "baseVolumeDb": base_volume_db,
        "effectiveCurvePoints": effective_points,
        "releaseDb": release_db,
        "peakDb": peak_db,
        "peakThrottle": peak_throttle,
        "releaseSuppressionDb": release_suppression_db,
        "releaseLinearRatioToPeak": release_linear_ratio,
        "materialSuppressionThresholdDb": _MATERIAL_RELEASE_SUPPRESSION_DB,
        "materiallySuppressedAtRelease": (
            release_suppression_db is not None
            and release_suppression_db >= _MATERIAL_RELEASE_SUPPRESSION_DB
        ),
        "ownershipConsistent": not problems,
        "missingReferencedControllerGuids": sorted(set(missing_references)),
        "ownerMismatchControllerGuids": sorted(set(invalid_owner_references)),
        "unreferencedOwnedControllerGuids": sorted(set(unreferenced_owned)),
    }
    return evidence, sorted(set(problems))


def _instrument_parent_map(
    instruments: dict[str, dict[str, Any]],
) -> dict[str, set[str]]:
    parents: dict[str, set[str]] = defaultdict(set)
    for parent_guid, instrument in instruments.items():
        children = instrument.get("childInstruments", [])
        if not isinstance(children, list):
            continue
        for child in children:
            child_guid = (
                _guid(child.get("guid"))
                if isinstance(child, dict)
                else _guid(child)
            )
            if child_guid:
                parents[child_guid].add(parent_guid)
    return parents


def _instrument_chain(
    source_guid: str,
    instruments: dict[str, dict[str, Any]],
    parents: dict[str, set[str]],
) -> tuple[list[tuple[str, dict[str, Any], int]], list[str]]:
    """Return one deterministic source-to-root chain or fail-closed problems."""

    result: list[tuple[str, dict[str, Any], int]] = []
    problems: list[str] = []
    current = source_guid
    visited: set[str] = set()
    depth = 0
    while current:
        if current in visited:
            problems.append("instrumentAncestorCycle")
            break
        visited.add(current)
        instrument = instruments.get(current)
        if instrument is None:
            problems.append("instrumentAncestorMissing")
            break
        result.append((current, instrument, depth))
        parent_guids = sorted(parents.get(current, set()))
        if len(parent_guids) > 1:
            problems.append("multipleInstrumentAncestorPaths")
            break
        current = parent_guids[0] if parent_guids else ""
        depth += 1
    return result, sorted(set(problems))


def _source_placement_evidence(
    event: dict[str, Any],
    source_guid: str,
    ancestor_guids: set[str],
) -> tuple[dict[str, Any], list[str]]:
    target_guids = {source_guid, *ancestor_guids}
    placements: list[dict[str, Any]] = []
    problems: list[str] = []
    parameter_placements = event.get("parameterPlacements", [])
    if parameter_placements is None:
        parameter_placements = []
    if not isinstance(parameter_placements, list):
        problems.append("parameterPlacementsInvalid")
        parameter_placements = []
    for placement in parameter_placements:
        if not isinstance(placement, dict):
            continue
        instrument_guid = _guid(placement.get("instrumentGuid"))
        if instrument_guid not in target_guids:
            continue
        start = _finite_float(placement.get("start"))
        end = _finite_float(placement.get("end"))
        if start is None or end is None or end < start:
            problems.append("parameterPlacementRangeInvalid")
            continue
        parameter_name = str(placement.get("parameterName") or "").casefold()
        include_end = placement.get("includeEnd") is True
        includes_release = None
        if parameter_name == "throttle":
            includes_release = start <= _THROTTLE_MINIMUM and (
                end > _THROTTLE_MINIMUM
                or (end == _THROTTLE_MINIMUM and include_end)
            )
        placements.append(
            {
                "kind": "parameter",
                "instrumentGuid": instrument_guid,
                "scope": "source" if instrument_guid == source_guid else "ancestor",
                "parameterName": parameter_name,
                "start": start,
                "end": end,
                "includeEnd": include_end,
                "includesReleasedThrottle": includes_release,
            }
        )

    timeline_placements = event.get("timelinePlacements", [])
    if timeline_placements is None:
        timeline_placements = []
    if not isinstance(timeline_placements, list):
        problems.append("timelinePlacementsInvalid")
        timeline_placements = []
    for placement in timeline_placements:
        if not isinstance(placement, dict):
            continue
        instrument_guid = _guid(placement.get("instrumentGuid"))
        if instrument_guid not in target_guids:
            continue
        placements.append(
            {
                "kind": "timeline",
                "instrumentGuid": instrument_guid,
                "scope": "source" if instrument_guid == source_guid else "ancestor",
                "startTime": placement.get("startTime"),
                "length": placement.get("length"),
                "timeLocked": placement.get("timeLocked") is True,
            }
        )

    placements.sort(
        key=lambda item: (
            item["kind"],
            str(item.get("parameterName") or ""),
            float(item.get("start") or 0.0),
            item["instrumentGuid"],
        )
    )
    throttle_placements = [
        item
        for item in placements
        if item["kind"] == "parameter" and item.get("parameterName") == "throttle"
    ]
    throttle_gate = (
        "notAuthored"
        if not throttle_placements
        else "excludesRelease"
        if any(item["includesReleasedThrottle"] is False for item in throttle_placements)
        else "includesRelease"
    )
    trigger_kinds = sorted(
        {
            "timeline"
            if item["kind"] == "timeline"
            else str(item.get("parameterName") or "unnamedParameter")
            for item in placements
        }
    )
    return (
        {
            "placements": placements,
            "triggerKinds": trigger_kinds,
            "throttleGate": throttle_gate,
            "hasRecognizedTriggerTopology": bool(placements),
        },
        sorted(set(problems)),
    )


def _instrument_evidence(
    node_guid: str, node: dict[str, Any], depth: int
) -> dict[str, Any]:
    properties = node.get("baseProperties")
    if not isinstance(properties, dict):
        properties = {}
    evidence: dict[str, Any] = {
        "instrumentGuid": node_guid,
        "kind": node.get("kind"),
        "depth": depth,
        "baseProperties": {
            key: properties.get(key)
            for key in (
                "autoPitchAtMinimum",
                "autoPitchReference",
                "initialSeekPercent",
                "initialSeekPosition",
                "loopCount",
                "pitchSemitones",
                "routableGuid",
                "timelineGuid",
                "triggerChancePercent",
                "volumeDb",
            )
        },
    }
    waveform = node.get("sample")
    if depth == 0 and isinstance(waveform, dict):
        evidence["waveformTechnicalEvidence"] = {
            key: waveform.get(key)
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
    return evidence


def _automation_evidence(
    instrument_chain: list[tuple[str, dict[str, Any], int]],
    controllers: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for node_guid, node, depth in instrument_chain:
        for controller_guid in sorted(
            {_guid(item) for item in node.get("controllerGuids", []) if _guid(item)}
        ):
            controller = controllers.get(controller_guid)
            if controller is None:
                continue
            points = controller.get("points")
            if not isinstance(points, list):
                points = []
            evidence.append(
                {
                    "controllerGuid": controller_guid,
                    "scope": "source" if depth == 0 else "ancestor",
                    "scopeInstrumentGuid": node_guid,
                    "ancestorDepth": depth,
                    "propertyOwnerGuid": _guid(controller.get("propertyOwnerGuid")),
                    "propertyIndex": _property_index(controller),
                    "inputKind": controller.get("inputKind"),
                    "inputParameterGuid": _guid(
                        controller.get("inputParameterGuid")
                    ),
                    "inputParameterName": str(
                        controller.get("inputParameterName") or ""
                    ).casefold(),
                    "points": [
                        {
                            "x": point.get("x"),
                            "xRawUInt32": point.get("xRawUInt32"),
                            "y": point.get("y"),
                            "type": point.get("type"),
                            "shape": point.get("shape"),
                        }
                        for point in points
                        if isinstance(point, dict)
                    ],
                }
            )
    return sorted(
        evidence,
        key=lambda item: (
            item["ancestorDepth"],
            item["scopeInstrumentGuid"],
            item["controllerGuid"],
        ),
    )


def _ambiguous_membership(reason: str) -> dict[str, Any]:
    return {
        "semanticClass": ROLE_AMBIGUOUS,
        "role": ROLE_AMBIGUOUS,
        "policy": POLICY_AMBIGUOUS,
        "candidateManifestRoles": [],
        "eventClass": "ambiguous",
        "ruleId": reason,
        "reason": reason,
    }


def _role_membership(
    *,
    semantic_class: str,
    role: str,
    policy: str,
    candidates: list[str],
    event_class: str,
    reason: str,
) -> dict[str, Any]:
    return {
        "semanticClass": semantic_class,
        "role": role,
        "policy": policy,
        "candidateManifestRoles": candidates,
        "eventClass": event_class,
        "ruleId": reason,
        "reason": reason,
    }


def _engine_membership_role(
    lifetime: str | None,
    throttle: dict[str, Any],
    throttle_problems: list[str],
    placement: dict[str, Any],
    placement_problems: list[str],
) -> dict[str, Any]:
    if lifetime not in {"continuous", "oneShot"}:
        return _ambiguous_membership(
            f"unsupportedEngineLoopLifetime:{lifetime or 'missing'}"
        )
    if throttle_problems:
        return _ambiguous_membership(throttle_problems[0])
    if placement_problems:
        return _ambiguous_membership(placement_problems[0])

    trend = throttle["trend"]
    throttle_gate = placement["throttleGate"]
    materially_suppressed = throttle["materiallySuppressedAtRelease"] is True
    event_class = "continuousEngine" if lifetime == "continuous" else "oneShotEngine"

    if lifetime == "continuous":
        if throttle_gate == "excludesRelease":
            return _role_membership(
                semantic_class=ROLE_EXCLUDED_LOAD,
                role=ROLE_EXCLUDED_LOAD,
                policy=POLICY_EXCLUDE,
                candidates=["LOAD"],
                event_class=event_class,
                reason="continuousEngineThrottlePlacementExcludesReleasedThrottle",
            )
        if trend == "rising":
            return _role_membership(
                semantic_class=ROLE_EXCLUDED_LOAD,
                role=ROLE_EXCLUDED_LOAD,
                policy=POLICY_EXCLUDE,
                candidates=["LOAD"],
                event_class=event_class,
                reason="continuousEngineThrottleVolumeMonotonicRising",
            )
        if trend == "falling":
            return _role_membership(
                semantic_class=ROLE_ENGINE_FALLING,
                role=ROLE_ENGINE_FALLING,
                policy=POLICY_ALLOW_CANDIDATE,
                candidates=["COAST", "EXHAUST", "IDLE"],
                event_class=event_class,
                reason="continuousEngineThrottleVolumeMonotonicFalling",
            )
        if trend in {"missing", "flat"}:
            return _role_membership(
                semantic_class=ROLE_ENGINE_INDEPENDENT,
                role=ROLE_ENGINE_INDEPENDENT,
                policy=POLICY_ALLOW_CANDIDATE,
                candidates=["IDLE", "TEXTURE", "INTAKE", "EXHAUST"],
                event_class=event_class,
                reason=(
                    "continuousEngineHasNoThrottleVolumeController"
                    if trend == "missing"
                    else "continuousEngineThrottleVolumeConstant"
                ),
            )
        if trend == "mixed" and materially_suppressed:
            return _role_membership(
                semantic_class=ROLE_EXCLUDED_LOAD,
                role=ROLE_EXCLUDED_LOAD,
                policy=POLICY_EXCLUDE,
                candidates=["LOAD"],
                event_class=event_class,
                reason="continuousEngineMixedCurveMateriallySuppressedAtRelease",
            )
        if trend == "mixed":
            return _role_membership(
                semantic_class=ROLE_ENGINE_RELEASE_AUDIBLE,
                role=ROLE_ENGINE_RELEASE_AUDIBLE,
                policy=POLICY_ALLOW_CANDIDATE,
                candidates=["COAST", "EXHAUST", "IDLE", "INTAKE", "TEXTURE"],
                event_class=event_class,
                reason="continuousEngineMixedCurveRemainsAudibleAtRelease",
            )
        return _ambiguous_membership(f"continuousEngineThrottleVolumeTrend:{trend}")

    # A one-shot is not treated as a continuous loop merely because its volume
    # rises.  Exclusion requires an authored trigger placement as corroborating
    # topology; otherwise the source could be an allowed transient.
    if trend in {"missing", "flat"}:
        if throttle_gate == "excludesRelease":
            return _ambiguous_membership(
                "oneShotPedalTriggerWithoutDiscriminatingThrottleCurve"
            )
        return _role_membership(
            semantic_class=ROLE_ENGINE_INDEPENDENT,
            role=ROLE_ENGINE_INDEPENDENT,
            policy=POLICY_ALLOW_CANDIDATE,
            candidates=["ENGINE_TRANSIENT", "OVERRUN"],
            event_class=event_class,
            reason=(
                "oneShotEngineHasNoThrottleVolumeController"
                if trend == "missing"
                else "oneShotEngineThrottleVolumeConstant"
            ),
        )
    if not placement["hasRecognizedTriggerTopology"]:
        return _ambiguous_membership("oneShotEngineTriggerTopologyMissing")
    if trend == "rising":
        return _role_membership(
            semantic_class=ROLE_EXCLUDED_LOAD,
            role=ROLE_EXCLUDED_LOAD,
            policy=POLICY_EXCLUDE,
            candidates=["LOAD"],
            event_class=event_class,
            reason="oneShotEngineTriggerTopologyAndMonotonicRisingThrottleVolume",
        )
    if trend == "falling":
        return _role_membership(
            semantic_class=ROLE_ENGINE_FALLING,
            role=ROLE_ENGINE_FALLING,
            policy=POLICY_ALLOW_CANDIDATE,
            candidates=["ENGINE_TRANSIENT", "OVERRUN"],
            event_class=event_class,
            reason="oneShotEngineTriggerTopologyAndMonotonicFallingThrottleVolume",
        )
    if trend == "mixed" and materially_suppressed:
        return _role_membership(
            semantic_class=ROLE_EXCLUDED_LOAD,
            role=ROLE_EXCLUDED_LOAD,
            policy=POLICY_EXCLUDE,
            candidates=["LOAD"],
            event_class=event_class,
            reason="oneShotEngineTriggerTopologyAndMaterialReleaseSuppression",
        )
    if trend == "mixed" and throttle_gate == "excludesRelease":
        return _ambiguous_membership(
            "oneShotMixedCurvePedalGateDirectionSemanticsUnknown"
        )
    if trend == "mixed":
        return _role_membership(
            semantic_class=ROLE_ENGINE_TRANSIENT,
            role=ROLE_ENGINE_TRANSIENT,
            policy=POLICY_ALLOW_CANDIDATE,
            candidates=["ENGINE_TRANSIENT", "OVERRUN"],
            event_class=event_class,
            reason="oneShotMixedCurveRemainsAudibleAtRelease",
        )
    return _ambiguous_membership(f"oneShotEngineThrottleVolumeTrend:{trend}")


def _membership_role(
    suffix: str,
    lifetime: str | None,
    throttle: dict[str, Any],
    throttle_problems: list[str],
    placement: dict[str, Any],
    placement_problems: list[str],
) -> dict[str, Any]:
    if suffix in ENGINE_EVENT_SUFFIXES:
        return _engine_membership_role(
            lifetime,
            throttle,
            throttle_problems,
            placement,
            placement_problems,
        )

    if suffix in OUT_OF_SCOPE_EVENT_SUFFIXES:
        return {
            "semanticClass": ROLE_OUT_OF_SCOPE,
            "role": ROLE_OUT_OF_SCOPE,
            "policy": POLICY_OUT_OF_SCOPE,
            "candidateManifestRoles": [],
            "eventClass": "outOfScope",
            "reason": "knownNonCoreEventTopology",
        }

    if lifetime not in {"continuous", "oneShot"}:
        return _ambiguous_membership(f"unsupportedLoopLifetime:{lifetime or 'missing'}")

    if suffix == "transmission":
        if lifetime != "continuous":
            return _ambiguous_membership("transmissionSourceIsNotInfiniteLoop")
        return {
            "semanticClass": ROLE_TRANSMISSION,
            "role": ROLE_TRANSMISSION,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["TRANSMISSION"],
            "eventClass": "continuousCore",
            "reason": "transmissionEventTopology",
        }

    if suffix == "turbo":
        if lifetime == "continuous":
            return {
                "semanticClass": ROLE_TURBO_CONTINUOUS,
                "role": ROLE_TURBO_CONTINUOUS,
                "policy": POLICY_ALLOW_CANDIDATE,
                "candidateManifestRoles": ["TURBO", "SPOOL"],
                "eventClass": "continuousCore",
                "reason": "turboEventInfiniteLoop",
            }
        return {
            "semanticClass": ROLE_TURBO_TRANSIENT,
            "role": ROLE_TURBO_TRANSIENT,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["BOV", "TURBO_TRANSIENT"],
            "eventClass": "oneShotCore",
            "reason": "turboEventOneShot",
        }

    if suffix == "limiter":
        return {
            "semanticClass": f"{ROLE_LIMITER}:{lifetime}",
            "role": ROLE_LIMITER,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["LIMITER"],
            "eventClass": "continuousCore" if lifetime == "continuous" else "oneShotCore",
            "reason": f"limiterEvent:{lifetime}",
        }

    if suffix in BACKFIRE_EVENT_SUFFIXES:
        if lifetime != "oneShot":
            return _ambiguous_membership("backfireSourceIsNotOneShot")
        return {
            "semanticClass": ROLE_OVERRUN_TRANSIENT,
            "role": ROLE_OVERRUN_TRANSIENT,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["OVERRUN", "POPS", "BANGS", "CRACKS"],
            "eventClass": "oneShotCore",
            "reason": "backfireEventOneShot",
        }

    if suffix in GEAR_SHIFT_EVENT_SUFFIXES:
        if lifetime != "oneShot":
            return _ambiguous_membership("gearShiftSourceIsNotOneShot")
        return {
            "semanticClass": ROLE_GEAR_SHIFT,
            "role": ROLE_GEAR_SHIFT,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["SHIFT_UP", "SHIFT_DOWN"],
            "eventClass": "oneShotCore",
            "reason": "gearEventOneShot",
        }

    if suffix == "gear_grind":
        if lifetime != "oneShot":
            return _ambiguous_membership("gearGrindSourceIsNotOneShot")
        return {
            "semanticClass": ROLE_GEAR_GRIND,
            "role": ROLE_GEAR_GRIND,
            "policy": POLICY_ALLOW_CANDIDATE,
            "candidateManifestRoles": ["GEAR_GRIND"],
            "eventClass": "oneShotCore",
            "reason": "gearGrindEventOneShot",
        }

    return _ambiguous_membership(f"unknownEventSuffix:{suffix or 'missing'}")


def _manual_oracle_backlog_entry(row: dict[str, Any]) -> dict[str, Any]:
    reasons = list(row.get("reasons", []))
    if "oneShotMixedCurvePedalGateDirectionSemanticsUnknown" in reasons:
        required_oracle = {
            "kind": "silentRuntimeDirectionSweep",
            "question": (
                "Does this one-shot schedule on increasing throttle, decreasing "
                "throttle, or both when RPM is inside its authored placement?"
            ),
            "observations": [
                "sourceIdentityWithoutFilenameSemantics",
                "triggerDirection",
                "triggerThrottle",
                "triggerRpm",
            ],
        }
    elif any("Controller" in reason or "Curve" in reason for reason in reasons):
        required_oracle = {
            "kind": "authoringGraphOrParserRepair",
            "question": "Recover a complete, ownership-consistent throttle automation graph.",
            "observations": ["controllerOwnership", "allCurvePoints", "sourceAncestry"],
        }
    elif "eventMappingIncomplete" in reasons:
        required_oracle = {
            "kind": "completeStaticEventMapping",
            "question": "Resolve every instrument reachable from this event.",
            "observations": ["eventReachability", "sourceIdentityWithoutFilenameSemantics"],
        }
    else:
        required_oracle = {
            "kind": "authoringTopologyOrSilentRuntimeTrace",
            "question": "Resolve the source's trigger and pedal-state semantics.",
            "observations": ["triggerTopology", "triggerDirection", "throttleState"],
        }
    return {
        "sourceGuid": row["sourceGuid"],
        "eventPaths": row["eventPaths"],
        "eventSuffixes": row["eventSuffixes"],
        "lifetime": row["lifetime"],
        "reasons": reasons,
        "nonFilenameEvidence": {
            "throttleVolume": row["throttleVolume"],
            "instrumentAncestry": row["decisionEvidence"]["instrumentAncestry"],
            "automationControllers": row["decisionEvidence"][
                "automationControllers"
            ],
            "eventMemberships": row["decisionEvidence"]["eventMemberships"],
            "chainProblems": row["decisionEvidence"]["chainProblems"],
        },
        "requiredOracle": required_oracle,
    }


def _role_policy_lifetime_rows(
    rows: Iterable[dict[str, Any]],
) -> list[dict[str, Any]]:
    counts: Counter[tuple[str, str, str]] = Counter(
        (
            str(row["role"]),
            str(row["policy"]),
            str(row["lifetime"] or "missing"),
        )
        for row in rows
    )
    return [
        {"role": role, "policy": policy, "lifetime": lifetime, "count": count}
        for (role, policy, lifetime), count in sorted(counts.items())
    ]


def _is_allowed_released_throttle_continuous_engine(
    row: dict[str, Any],
) -> bool:
    if (
        row.get("lifetime") != "continuous"
        or row.get("policy") != POLICY_ALLOW_CANDIDATE
        or row.get("role")
        not in {
            ROLE_ENGINE_FALLING,
            ROLE_ENGINE_INDEPENDENT,
            ROLE_ENGINE_RELEASE_AUDIBLE,
        }
    ):
        return False
    memberships = row.get("decisionEvidence", {}).get("eventMemberships", [])
    if not any(
        membership.get("suffix") in ENGINE_EVENT_SUFFIXES
        and membership.get("mappingComplete") is True
        and membership.get("triggerTopology", {}).get("throttleGate")
        != "excludesRelease"
        for membership in memberships
    ):
        return False
    throttle = row.get("throttleVolume", {})
    release_db = throttle.get("releaseDb")
    if release_db is None:
        release_db = throttle.get("baseVolumeDb")
    release_db = _finite_float(release_db)
    return release_db is not None and release_db > _RELEASE_AUDIBILITY_FLOOR_DB


def classify_bank_graph_sources(report: dict[str, Any]) -> dict[str, Any]:
    """Classify every waveform source in one validated v3 graph report."""

    if report.get("schema") != BANK_GRAPH_SCHEMA:
        raise SourceRoleClassificationError(
            f"expected {BANK_GRAPH_SCHEMA}, got {report.get('schema')!r}"
        )
    instruments_raw = _require_list(report, "instruments")
    controllers_raw = _require_list(report, "controllers")
    events_raw = _require_list(report, "events")

    controllers: dict[str, dict[str, Any]] = {}
    for controller in controllers_raw:
        if not isinstance(controller, dict) or not _guid(controller.get("guid")):
            raise SourceRoleClassificationError("controller is missing a GUID")
        guid = _guid(controller["guid"])
        if guid in controllers:
            raise SourceRoleClassificationError(f"duplicate controller GUID: {guid}")
        controllers[guid] = controller

    instruments: dict[str, dict[str, Any]] = {}
    sources: dict[str, dict[str, Any]] = {}
    structural_instruments = 0
    for instrument in instruments_raw:
        if not isinstance(instrument, dict) or not _guid(instrument.get("guid")):
            raise SourceRoleClassificationError("instrument is missing a GUID")
        guid = _guid(instrument["guid"])
        if guid in instruments:
            raise SourceRoleClassificationError(f"duplicate instrument GUID: {guid}")
        instruments[guid] = instrument
        if instrument.get("kind") != "WaveformInstrumentNode":
            structural_instruments += 1
            continue
        sources[guid] = instrument

    parents = _instrument_parent_map(instruments)

    memberships: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events_raw:
        if not isinstance(event, dict):
            raise SourceRoleClassificationError("event must be an object")
        path = str(event.get("path") or "")
        suffix = _event_suffix(path)
        complete = event.get("mappingComplete") is True
        reachable = event.get("reachableInstrumentGuids")
        if not isinstance(reachable, list):
            raise SourceRoleClassificationError(f"event {path!r} has no reachable graph")
        for reachable_guid in reachable:
            guid = _guid(reachable_guid)
            if guid in sources:
                memberships[guid].append(
                    {
                        "path": path,
                        "suffix": suffix,
                        "mappingComplete": complete,
                        "event": event,
                    }
                )

    rows: list[dict[str, Any]] = []
    for guid, instrument in sorted(sources.items()):
        lifetime = _loop_lifetime(instrument)
        instrument_chain, chain_problems = _instrument_chain(
            guid, instruments, parents
        )
        throttle, throttle_problems = _throttle_volume_evidence(
            instrument_chain, controllers
        )
        event_memberships = sorted(
            memberships.get(guid, []), key=lambda item: (item["path"], item["suffix"])
        )
        reasons: list[str] = []
        decisions: list[dict[str, Any]] = []
        membership_evidence: list[dict[str, Any]] = []
        if chain_problems:
            decisions.append(_ambiguous_membership(chain_problems[0]))
        if not event_memberships:
            decisions.append(_ambiguous_membership("sourceUnreachableFromAnyEvent"))
        else:
            for membership in event_memberships:
                placement, placement_problems = _source_placement_evidence(
                    membership["event"],
                    guid,
                    {item[0] for item in instrument_chain[1:]},
                )
                membership_evidence.append(
                    {
                        "path": membership["path"],
                        "suffix": membership["suffix"],
                        "mappingComplete": membership["mappingComplete"],
                        "triggerTopology": placement,
                        "problems": placement_problems,
                    }
                )
                if not membership["mappingComplete"]:
                    decisions.append(_ambiguous_membership("eventMappingIncomplete"))
                else:
                    decisions.append(
                        _membership_role(
                            membership["suffix"],
                            lifetime,
                            throttle,
                            throttle_problems,
                            placement,
                            placement_problems,
                        )
                    )

        for decision in decisions:
            decision.setdefault("ruleId", decision["reason"])

        semantic_classes = {item["semanticClass"] for item in decisions}
        if len(semantic_classes) != 1:
            role = ROLE_AMBIGUOUS
            policy = POLICY_AMBIGUOUS
            event_class = "ambiguous"
            candidate_roles: list[str] = []
            reasons.append("conflictingEventTopologies")
        else:
            decision = decisions[0]
            role = decision["role"]
            policy = decision["policy"]
            event_class = decision["eventClass"]
            candidate_roles = sorted(
                {
                    candidate
                    for item in decisions
                    for candidate in item["candidateManifestRoles"]
                }
            )
        reasons.extend(item["reason"] for item in decisions)
        if policy == POLICY_AMBIGUOUS:
            reasons.extend(throttle_problems)
            reasons.extend(chain_problems)
        rows.append(
            {
                "sourceGuid": guid,
                "eventPaths": sorted({item["path"] for item in event_memberships}),
                "eventSuffixes": sorted({item["suffix"] for item in event_memberships}),
                "lifetime": lifetime,
                "policy": policy,
                "role": role,
                "eventClass": event_class,
                "candidateManifestRoles": candidate_roles,
                "throttleVolume": throttle,
                "reasons": sorted(set(reasons)),
                "decisionEvidence": {
                    "ruleIds": sorted({item["ruleId"] for item in decisions}),
                    "instrumentAncestry": [
                        _instrument_evidence(node_guid, node, depth)
                        for node_guid, node, depth in instrument_chain
                    ],
                    "automationControllers": _automation_evidence(
                        instrument_chain, controllers
                    ),
                    "eventMemberships": membership_evidence,
                    "chainProblems": chain_problems,
                },
            }
        )

    policy_counts = Counter(row["policy"] for row in rows)
    role_counts = Counter(row["role"] for row in rows)
    event_class_counts = Counter(row["eventClass"] for row in rows)
    lifetime_counts = Counter(row["lifetime"] or "missing" for row in rows)
    engine_rows = [
        row
        for row in rows
        if set(row["eventSuffixes"]) & ENGINE_EVENT_SUFFIXES
    ]
    exact_role_candidates = sum(
        1 for row in rows if len(row["candidateManifestRoles"]) > 1
    )
    decision_rule_counts: Counter[str] = Counter(
        rule_id
        for row in rows
        for rule_id in row["decisionEvidence"]["ruleIds"]
    )
    ancestor_controlled_rows = [
        row
        for row in rows
        if any(
            controller["scope"] == "ancestor"
            for controller in row["throttleVolume"]["controllers"]
        )
    ]
    allowed_release_continuous_engine_rows = [
        row
        for row in rows
        if _is_allowed_released_throttle_continuous_engine(row)
    ]
    return {
        "schema": CLASSIFICATION_SCHEMA,
        "inputSchema": BANK_GRAPH_SCHEMA,
        "bank": {
            "sha256": str((report.get("bank") or {}).get("sha256") or ""),
            "fileVersion": (report.get("bank") or {}).get("fileVersion"),
        },
        "basis": {
            "usesSampleNames": False,
            "curveRule": (
                "source-plus-ancestor throttle-volume curves are summed in dB; "
                "monotonic direction is authoritative and mixed curves use "
                "released-throttle audibility"
            ),
            "materialReleaseSuppressionDb": _MATERIAL_RELEASE_SUPPRESSION_DB,
            "releasedThrottleAudibilityFloorDb": _RELEASE_AUDIBILITY_FLOOR_DB,
            "unknownPolicy": "failClosed",
            "scope": "source selection only; not an exact-rendering or release-readiness claim",
        },
        "counts": {
            "sourceInstruments": len(rows),
            "structuralInstruments": structural_instruments,
            "engineSourceInstruments": len(engine_rows),
            "ambiguousSourceInstruments": policy_counts[POLICY_AMBIGUOUS],
            "exactRoleCandidateSourceInstruments": exact_role_candidates,
            "ancestorThrottleControlledSourceInstruments": len(
                ancestor_controlled_rows
            ),
            "ancestorThrottleControlledEngineSourceInstruments": sum(
                row in engine_rows for row in ancestor_controlled_rows
            ),
            "allowedReleasedThrottleContinuousEngineSourceInstruments": len(
                allowed_release_continuous_engine_rows
            ),
        },
        "policyCounts": dict(sorted(policy_counts.items())),
        "roleCounts": dict(sorted(role_counts.items())),
        "eventClassCounts": dict(sorted(event_class_counts.items())),
        "lifetimeCounts": dict(sorted(lifetime_counts.items())),
        "decisionRuleCounts": dict(sorted(decision_rule_counts.items())),
        "rolePolicyLifetimeCounts": _role_policy_lifetime_rows(rows),
        "loadExclusionComplete": all(
            row["policy"] != POLICY_AMBIGUOUS for row in engine_rows
        ),
        "ambiguousSourceGuids": [
            row["sourceGuid"] for row in rows if row["policy"] == POLICY_AMBIGUOUS
        ],
        "manualOracleBacklog": [
            _manual_oracle_backlog_entry(row)
            for row in rows
            if row["policy"] == POLICY_AMBIGUOUS
        ],
        "sources": rows,
    }


def _catalog_metadata(root: Path) -> dict[str, dict[str, Any]]:
    summary_path = root / "summary.json"
    if not summary_path.is_file():
        return {}
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if summary.get("schema") != "ac-fmod-catalog-graph-audit-summary-v1":
        return {}
    return {
        str(item.get("familyId")): item
        for item in summary.get("families", [])
        if isinstance(item, dict) and item.get("familyId")
    }


def classify_catalog_graph_directory(root: Path) -> dict[str, Any]:
    """Classify cached v3 reports with an auditable decision for every source."""

    root = root.resolve()
    family_root = root / "families" if (root / "families").is_dir() else root
    report_paths = sorted(family_root.glob("*.json"))
    if not report_paths:
        raise SourceRoleClassificationError(f"no family graph reports found under {root}")
    metadata = _catalog_metadata(root)
    aggregate_policies: Counter[str] = Counter()
    aggregate_roles: Counter[str] = Counter()
    aggregate_event_classes: Counter[str] = Counter()
    aggregate_lifetimes: Counter[str] = Counter()
    aggregate_decision_rules: Counter[str] = Counter()
    ambiguous_reasons: Counter[str] = Counter()
    family_rows: list[dict[str, Any]] = []
    ambiguous_sources: list[dict[str, Any]] = []
    manual_oracle_backlog: list[dict[str, Any]] = []
    source_decisions: list[dict[str, Any]] = []
    exact_role_candidates = 0
    source_count = 0
    structural_count = 0
    engine_count = 0
    ancestor_controlled_count = 0
    ancestor_controlled_engine_count = 0
    allowed_release_continuous_engine_count = 0

    for report_path in report_paths:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        classified = classify_bank_graph_sources(report)
        family_id = str(classified["bank"]["sha256"] or report_path.stem)
        family_meta = metadata.get(family_id, {})
        aggregate_policies.update(classified["policyCounts"])
        aggregate_roles.update(classified["roleCounts"])
        aggregate_event_classes.update(classified["eventClassCounts"])
        aggregate_lifetimes.update(classified["lifetimeCounts"])
        aggregate_decision_rules.update(classified["decisionRuleCounts"])
        source_count += int(classified["counts"]["sourceInstruments"])
        structural_count += int(classified["counts"]["structuralInstruments"])
        engine_count += int(classified["counts"]["engineSourceInstruments"])
        ancestor_controlled_count += int(
            classified["counts"]["ancestorThrottleControlledSourceInstruments"]
        )
        ancestor_controlled_engine_count += int(
            classified["counts"]["ancestorThrottleControlledEngineSourceInstruments"]
        )
        family_allowed_release_continuous_engine_count = int(
            classified["counts"][
                "allowedReleasedThrottleContinuousEngineSourceInstruments"
            ]
        )
        allowed_release_continuous_engine_count += (
            family_allowed_release_continuous_engine_count
        )
        exact_role_candidates += int(
            classified["counts"]["exactRoleCandidateSourceInstruments"]
        )
        ambiguous_rows = [
            row for row in classified["sources"] if row["policy"] == POLICY_AMBIGUOUS
        ]
        for row in classified["sources"]:
            source_decisions.append({"familyId": family_id, **row})
        for row in ambiguous_rows:
            ambiguous_reasons.update(row["reasons"])
            ambiguous_sources.append(
                {
                    "familyId": family_id,
                    "sourceGuid": row["sourceGuid"],
                    "eventPaths": row["eventPaths"],
                    "reasons": row["reasons"],
                }
            )
        for backlog_entry in classified["manualOracleBacklog"]:
            manual_oracle_backlog.append(
                {
                    "familyId": family_id,
                    "representativeCarId": family_meta.get("representativeCarId"),
                    "memberIds": family_meta.get("memberIds", []),
                    **backlog_entry,
                }
            )
        family_rows.append(
            {
                "familyId": family_id,
                "representativeCarId": family_meta.get("representativeCarId"),
                "memberIds": family_meta.get("memberIds", []),
                "sourceInstruments": classified["counts"]["sourceInstruments"],
                "engineSourceInstruments": classified["counts"]["engineSourceInstruments"],
                "ambiguousSourceInstruments": len(ambiguous_rows),
                "exactRoleCandidateSourceInstruments": classified["counts"][
                    "exactRoleCandidateSourceInstruments"
                ],
                "loadExclusionComplete": classified["loadExclusionComplete"],
                "allowedReleasedThrottleContinuousEngineSourceInstruments": (
                    family_allowed_release_continuous_engine_count
                ),
                "hasAllowedReleasedThrottleContinuousEngineSource": (
                    family_allowed_release_continuous_engine_count > 0
                ),
                "policyCounts": classified["policyCounts"],
                "roleCounts": classified["roleCounts"],
            }
        )

    return {
        "schema": CATALOG_CLASSIFICATION_SCHEMA,
        "inputSchema": BANK_GRAPH_SCHEMA,
        "basis": {
            "usesSampleNames": False,
            "controllerScope": "sourcePlusUniqueInstrumentAncestors",
            "materialReleaseSuppressionDb": _MATERIAL_RELEASE_SUPPRESSION_DB,
            "releasedThrottleAudibilityFloorDb": _RELEASE_AUDIBILITY_FLOOR_DB,
            "unknownPolicy": "failClosed",
            "scope": "source selection only; not an exact-rendering or release-readiness claim",
        },
        "counts": {
            "families": len(family_rows),
            "sourceInstruments": source_count,
            "structuralInstruments": structural_count,
            "engineSourceInstruments": engine_count,
            "sourceDecisionRecords": len(source_decisions),
            "ancestorThrottleControlledSourceInstruments": ancestor_controlled_count,
            "ancestorThrottleControlledEngineSourceInstruments": (
                ancestor_controlled_engine_count
            ),
            "allowedReleasedThrottleContinuousEngineSourceInstruments": (
                allowed_release_continuous_engine_count
            ),
            "ambiguousSourceInstruments": aggregate_policies[POLICY_AMBIGUOUS],
            "familiesWithAmbiguousSources": sum(
                int(item["ambiguousSourceInstruments"] > 0) for item in family_rows
            ),
            "familiesWithIncompleteLoadExclusion": sum(
                int(not item["loadExclusionComplete"]) for item in family_rows
            ),
            "familiesWithoutAllowedReleasedThrottleContinuousEngineSource": sum(
                int(
                    not item[
                        "hasAllowedReleasedThrottleContinuousEngineSource"
                    ]
                )
                for item in family_rows
            ),
            "exactRoleCandidateSourceInstruments": exact_role_candidates,
        },
        "policyCounts": dict(sorted(aggregate_policies.items())),
        "roleCounts": dict(sorted(aggregate_roles.items())),
        "eventClassCounts": dict(sorted(aggregate_event_classes.items())),
        "lifetimeCounts": dict(sorted(aggregate_lifetimes.items())),
        "decisionRuleCounts": dict(sorted(aggregate_decision_rules.items())),
        "rolePolicyLifetimeCounts": _role_policy_lifetime_rows(source_decisions),
        "ambiguousReasonCounts": dict(sorted(ambiguous_reasons.items())),
        "ambiguousSources": sorted(
            ambiguous_sources, key=lambda item: (item["familyId"], item["sourceGuid"])
        ),
        "manualOracleBacklog": sorted(
            manual_oracle_backlog,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
        "sourceDecisions": sorted(
            source_decisions,
            key=lambda item: (item["familyId"], item["sourceGuid"]),
        ),
        "familiesWithoutAllowedReleasedThrottleContinuousEngineSource": sorted(
            item["familyId"]
            for item in family_rows
            if not item["hasAllowedReleasedThrottleContinuousEngineSource"]
        ),
        "families": sorted(family_rows, key=lambda item: item["familyId"]),
    }
