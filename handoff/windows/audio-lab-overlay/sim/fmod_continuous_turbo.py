"""Fail-closed static contracts for AC's continuous turbo source instruments.

The ordinary engine-curve compiler intentionally accepts only RPM/pedal
factorization.  AC turbo events instead use the native ``boost`` parameter and
can automate routed gain (properties 0 and 4), source rate (property 1), and
source scheduling.  This module inventories that topology without making an
audibility or exactness claim; a source-bound FMOD oracle must certify the
runtime projection before a release track can be emitted.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import PurePosixPath
from typing import Any


GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
STATIC_SCHEMA = "ac-fmod-static-continuous-turbo-source-v1"
VERIFICATION_SCHEMA = "ac-fmod-continuous-turbo-source-verification-v1"
CERTIFIED_SCHEMA = "ac-fmod-certified-continuous-turbo-source-v1"
CLASSIFIER_ROLE = "TURBO_CONTINUOUS_CANDIDATE"
RUNTIME_PITCH_MODE = "AUTHORED_CONTROL_RELATIVE_RATE"
RUNTIME_CONTROL = "BOOST"
RUNTIME_INTERPOLATION = "CLAMPED_LINEAR"
GAIN_CURVE_MAXIMUM = 38.0
PITCH_RATE_MAXIMUM = 16.0
GAIN_CURVE_MAXIMUM_ERROR = 2.0e-4
PITCH_ERROR_CENTS_MAXIMUM = 5.0
GAIN_ERROR_DB_MAXIMUM = 0.25


class FmodContinuousTurboError(ValueError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def _fail(code: str, detail: str) -> None:
    raise FmodContinuousTurboError(code, detail)


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _finite(value: object, label: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        _fail("invalidNumericEvidence", f"{label} is not numeric")
    if not math.isfinite(result):
        _fail("invalidNumericEvidence", f"{label} is not finite")
    return result


def _canonical_sha(value: object) -> str:
    try:
        payload = json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        _fail("nonCanonicalEvidence", str(exc))
    return hashlib.sha256(payload).hexdigest()


def _sha256(value: object, label: str) -> str:
    text = str(value or "").casefold()
    if len(text) != 64 or any(character not in "0123456789abcdef" for character in text):
        _fail("invalidHashEvidence", f"{label} is not lowercase SHA-256")
    return text


def _curve(
    value: object,
    *,
    label: str,
    domain: tuple[float, float],
    minimum_y: float,
    maximum_y: float,
    require_capture_unity: float | None = None,
) -> list[list[float]]:
    if not isinstance(value, list) or not 2 <= len(value) <= 512:
        _fail("invalidRuntimeCurve", f"{label} needs 2..512 points")
    result: list[list[float]] = []
    previous = -math.inf
    for index, raw in enumerate(value):
        if not isinstance(raw, list) or len(raw) != 2:
            _fail("invalidRuntimeCurve", f"{label}[{index}] is not [x,y]")
        x = _finite(raw[0], f"{label}[{index}].x")
        y = _finite(raw[1], f"{label}[{index}].y")
        if x <= previous or y < minimum_y or y > maximum_y:
            _fail("invalidRuntimeCurve", f"{label}[{index}] is outside its bounds")
        previous = x
        result.append([x, y])
    if result[0][0] != domain[0] or result[-1][0] != domain[1]:
        _fail("invalidRuntimeCurve", f"{label} does not span the declared domain")
    if require_capture_unity is not None:
        capture = require_capture_unity
        if capture <= result[0][0]:
            at_capture = result[0][1]
        elif capture >= result[-1][0]:
            at_capture = result[-1][1]
        else:
            at_capture = math.nan
            for left, right in zip(result, result[1:]):
                if left[0] <= capture <= right[0]:
                    amount = (capture - left[0]) / (right[0] - left[0])
                    at_capture = left[1] + ((right[1] - left[1]) * amount)
                    break
        if not math.isfinite(at_capture) or abs(at_capture - 1.0) > 2.0e-4:
            _fail("invalidRuntimeCurve", f"{label} is not capture-normalized")
    return result


def _objects_by_guid(graph: dict[str, Any], name: str) -> dict[str, dict[str, Any]]:
    raw = graph.get(name)
    if not isinstance(raw, list):
        _fail("invalidGraph", f"{name} is not an array")
    result: dict[str, dict[str, Any]] = {}
    for item in raw:
        if not isinstance(item, dict):
            _fail("invalidGraph", f"{name} contains a non-object")
        guid = _guid(item.get("guid"))
        if not guid or guid in result:
            _fail("invalidGraph", f"{name} GUID is empty or duplicated")
        result[guid] = item
    return result


def derive_continuous_turbo_source(
    graph_report: dict[str, Any], source_classification: dict[str, Any]
) -> dict[str, Any]:
    """Inventory one allowed infinite-loop turbo leaf without guessing curves."""

    if graph_report.get("schema") != GRAPH_SCHEMA:
        _fail("invalidGraph", f"expected {GRAPH_SCHEMA}")
    if (
        not isinstance(source_classification, dict)
        or source_classification.get("policy") != "allowCandidate"
        or source_classification.get("role") != CLASSIFIER_ROLE
        or source_classification.get("lifetime") != "continuous"
    ):
        _fail("invalidClassification", "source is not an allowed continuous turbo")
    source_guid = _guid(source_classification.get("sourceGuid"))
    instruments = _objects_by_guid(graph_report, "instruments")
    source = instruments.get(source_guid)
    if source is None or source.get("kind") != "WaveformInstrumentNode":
        _fail("invalidClassification", "classified source is not a waveform")
    base = source.get("baseProperties")
    if not isinstance(base, dict) or int(base.get("loopCount", 0)) >= 0:
        _fail("unsupportedLifetime", "continuous turbo source is not infinite-loop")
    event_paths = source_classification.get("eventPaths")
    if not isinstance(event_paths, list) or len(event_paths) != 1:
        _fail("invalidClassification", "continuous turbo source needs one event")
    event_path = str(event_paths[0])
    events = [
        item
        for item in graph_report.get("events", [])
        if isinstance(item, dict) and item.get("path") == event_path
    ]
    if (
        len(events) != 1
        or events[0].get("mappingComplete") is not True
        or not event_path.casefold().endswith("/turbo")
    ):
        _fail("invalidEvent", "continuous turbo event is missing or incomplete")
    event = events[0]
    if source_guid not in {_guid(value) for value in event["reachableInstrumentGuids"]}:
        _fail("invalidEvent", "source is not reachable from its turbo event")

    parameters = _objects_by_guid(graph_report, "parameters")
    boost_parameters = [
        parameters[_guid(guid)]
        for guid in event.get("parameterLayoutGuids", [])
        if _guid(guid) in parameters
        and str(parameters[_guid(guid)].get("name") or "").casefold() == "boost"
    ]
    if len(boost_parameters) != 1:
        _fail("invalidBoostControl", "turbo event needs exactly one boost parameter")
    boost = boost_parameters[0]
    boost_minimum = _finite(boost.get("minimum"), "boost minimum")
    boost_maximum = _finite(boost.get("maximum"), "boost maximum")
    if not 0.0 <= boost_minimum < boost_maximum <= 1.5:
        _fail("invalidBoostControl", "boost domain is outside the official 0..1.5 bound")

    timeline = [
        copy.deepcopy(item)
        for item in event.get("timelinePlacements", [])
        if _guid(item.get("instrumentGuid")) == source_guid
    ]
    regions = [
        copy.deepcopy(item)
        for item in event.get("parameterPlacements", [])
        if _guid(item.get("instrumentGuid")) == source_guid
    ]
    if (len(timeline), len(regions)) not in {(1, 0), (0, 1)}:
        _fail("unsupportedPlacement", "source needs one timeline or boost-region placement")
    if regions:
        region = regions[0]
        if (
            str(region.get("parameterName") or "").casefold() != "boost"
            or _finite(region.get("start"), "region start") < boost_minimum
            or _finite(region.get("end"), "region end") > boost_maximum + 1.0e-4
            or float(region["end"]) < float(region["start"])
            or not isinstance(region.get("includeEnd"), bool)
        ):
            _fail("unsupportedPlacement", "boost-region placement is invalid")
        program_mode = "BOOST_REGION_PERSISTENT_LOOP"
        placement = {
            "control": RUNTIME_CONTROL,
            "minimum": float(region["start"]),
            "maximum": min(boost_maximum, float(region["end"])),
            "includeMinimum": True,
            "includeMaximum": bool(region["includeEnd"]),
        }
    else:
        item = timeline[0]
        start = int(item.get("startTime", -1))
        length = int(item.get("length", -1))
        if start < 0 or length <= 0 or not isinstance(item.get("timeLocked"), bool):
            _fail("unsupportedPlacement", "timeline placement is invalid")
        program_mode = "TIMELINE_PERSISTENT_LOOP"
        placement = {
            "startTimeMs": start,
            "lengthMs": length,
            "timeLocked": item["timeLocked"],
        }

    graph_controllers = _objects_by_guid(graph_report, "controllers")
    evidence = source_classification.get("decisionEvidence")
    raw_controllers = (
        evidence.get("automationControllers") if isinstance(evidence, dict) else None
    )
    if not isinstance(raw_controllers, list) or not raw_controllers:
        _fail("missingController", "continuous turbo source has no controller evidence")
    dispositions: list[dict[str, Any]] = []
    seen: set[str] = set()
    property_counts: dict[int, int] = {}
    for raw in raw_controllers:
        if not isinstance(raw, dict):
            _fail("invalidController", "controller evidence is not an object")
        guid = _guid(raw.get("controllerGuid"))
        controller = graph_controllers.get(guid)
        if controller is None or guid in seen:
            _fail("invalidController", "controller is missing or duplicated")
        seen.add(guid)
        if _guid(controller.get("propertyOwnerGuid")) != _guid(
            raw.get("propertyOwnerGuid")
        ) or int(controller.get("propertyIndex", -1)) != int(
            raw.get("propertyIndex", -2)
        ):
            _fail("invalidController", "classifier/controller ownership changed")
        property_index = int(controller["propertyIndex"])
        input_kind = str(controller.get("inputKind") or "")
        parameter = str(controller.get("inputParameterName") or "").casefold()
        if input_kind == "parameter" and parameter == "boost" and property_index in {0, 4}:
            treatment = "SOURCE_BOUND_AGGREGATE_GAIN_CURVE"
        elif input_kind == "parameter" and parameter == "boost" and property_index == 1:
            treatment = "SOURCE_BOUND_CAPTURE_RELATIVE_RATE_CURVE"
        elif (
            input_kind == "timeline"
            and parameter == ""
            and property_index == 0
            and len(controller.get("points", [])) == 1
        ):
            treatment = "BAKED_INTO_TARGET_ONLY_LOOP_CAPTURE"
        else:
            _fail(
                "unsupportedController",
                f"controller {guid} maps {input_kind}/{parameter}/property{property_index}",
            )
        property_counts[property_index] = property_counts.get(property_index, 0) + 1
        dispositions.append(
            {
                "controllerGuid": guid,
                "inputKind": input_kind,
                "inputParameterName": parameter,
                "propertyIndex": property_index,
                "treatment": treatment,
                "points": copy.deepcopy(controller.get("points", [])),
            }
        )
    if property_counts.get(0, 0) < 1 or property_counts.get(1, 0) > 1:
        _fail("unsupportedController", "continuous turbo gain/pitch topology changed")

    sample = source.get("sample")
    base_properties = source.get("baseProperties")
    if not isinstance(sample, dict) or not isinstance(base_properties, dict):
        _fail("invalidGraph", "continuous turbo source geometry is absent")
    try:
        sample_evidence = {
            "soundBankIndex": int(sample["soundBankIndex"]),
            "subsoundIndex": int(sample["subsoundIndex"]),
            "sampleCount": int(sample["sampleCount"]),
            "frequencyHz": int(sample["frequencyHz"]),
            "channels": int(sample["channels"]),
            "encodedPayloadBytes": int(sample["encodedPayloadBytes"]),
            "encodedPayloadSha256": _sha256(
                sample["encodedPayloadSha256"], "embedded sample payload"
            ),
            "waveformResourceGuid": _guid(sample["waveformResourceGuid"]),
        }
    except (KeyError, TypeError, ValueError):
        _fail("invalidGraph", "continuous turbo sample evidence is incomplete")
    if (
        sample_evidence["soundBankIndex"] < 0
        or sample_evidence["subsoundIndex"] < 0
        or sample_evidence["sampleCount"] <= 0
        or sample_evidence["frequencyHz"] <= 0
        or sample_evidence["channels"] not in {1, 2}
        or sample_evidence["encodedPayloadBytes"] <= 0
        or not sample_evidence["waveformResourceGuid"]
    ):
        _fail("invalidGraph", "continuous turbo sample evidence is invalid")

    return {
        "schema": STATIC_SCHEMA,
        "sourceGuid": source_guid,
        "eventPath": event_path,
        "manifestRole": "TURBO",
        "programMode": program_mode,
        "placement": placement,
        "boostControl": {
            "control": RUNTIME_CONTROL,
            "eventParameterName": "boost",
            "eventParameterGuid": _guid(boost.get("guid")),
            "nativeDomain": [boost_minimum, boost_maximum],
            "runtimeSignal": "AC_TURBO_EVENT_AUTHORED_BOOST_PARAMETER",
            "clamp": "CLAMP_TO_NATIVE_EVENT_PARAMETER_DOMAIN",
            "seek": {
                "upPerSecond": _finite(boost.get("seekSpeed", 0.0), "boost seek up"),
                "downPerSecond": _finite(
                    boost.get("seekSpeedDown", 0.0), "boost seek down"
                ),
            },
        },
        "controllerDispositions": sorted(
            dispositions, key=lambda item: item["controllerGuid"]
        ),
        "sourceGeometry": {
            "sampleTechnicalEvidence": sample_evidence,
            "loopCount": int(base_properties["loopCount"]),
            "baseVolumeDb": _finite(base_properties["volumeDb"], "base volume"),
            "pitchSemitones": _finite(
                base_properties["pitchSemitones"], "base pitch"
            ),
            "autoPitchReference": _finite(
                base_properties["autoPitchReference"], "auto-pitch reference"
            ),
            "autoPitchDisabledForTurboSource": True,
        },
        "capture": None,
        "controlGainCurves": None,
        "pitchMode": RUNTIME_PITCH_MODE,
        "pitchControl": RUNTIME_CONTROL,
        "pitchCurve": None,
        "pitchCurveInterpolation": RUNTIME_INTERPOLATION,
        "fidelity": {
            "sampleNamesUsedForSemantics": False,
            "everyAuthoredControllerHasExplicitDisposition": True,
            "exactnessClaim": False,
            "requiredFinalGate": "sourceBoundContinuousTurboPcmCurveLifecycleControlMapping",
        },
    }


def certify_continuous_turbo_source(
    static_source: dict[str, Any], source_verification: dict[str, Any]
) -> dict[str, Any]:
    """Upgrade one continuous turbo source only after its source-bound gate."""

    if not isinstance(static_source, dict) or static_source.get("schema") != STATIC_SCHEMA:
        _fail("invalidStaticSource", f"expected {STATIC_SCHEMA}")
    if (
        not isinstance(source_verification, dict)
        or source_verification.get("schema") != VERIFICATION_SCHEMA
    ):
        _fail("invalidSourceVerification", f"expected {VERIFICATION_SCHEMA}")
    verification = copy.deepcopy(source_verification)
    embedded_hash = verification.pop("verificationPayloadSha256", None)
    if embedded_hash != _canonical_sha(verification):
        _fail("verificationHashMismatch", "source verification payload changed")
    if (
        _guid(verification.get("sourceGuid")) != static_source["sourceGuid"]
        or verification.get("eventPath") != static_source["eventPath"]
        or verification.get("programMode") != static_source["programMode"]
        or verification.get("derivedSourceSha256") != _canonical_sha(static_source)
    ):
        _fail("verificationIdentityMismatch", "source/event/mode derivation changed")
    renderer = verification.get("renderer")
    if not isinstance(renderer, dict) or renderer != {
        "runtime": "FMOD Studio API 1.08.12",
        "mode": "WAVWRITER_NRT",
        "sampleRateHz": 48000,
        "channels": 2,
        "sampleFormat": "SIGNED_PCM16_LE",
        "audioDeviceOpened": False,
        "freshProcessPerSource": True,
        "freshProcessPerPcmRender": True,
        "dspBufferFrames": 256,
    }:
        _fail("rendererContractMismatch", "continuous turbo renderer changed")
    priority = verification.get("softwareChannelPriority")
    if isinstance(priority, bool) or priority != 128:
        _fail("priorityMismatch", "continuous turbo priority is not source-bound 128")
    control = verification.get("boostControl")
    static_control = static_source["boostControl"]
    domain = tuple(float(value) for value in static_control["nativeDomain"])
    if not isinstance(control, dict) or (
        control.get("control") != RUNTIME_CONTROL
        or control.get("eventParameterName") != "boost"
        or _guid(control.get("eventParameterGuid"))
        != static_control["eventParameterGuid"]
        or control.get("declaredDomain") != static_control["nativeDomain"]
        or control.get("physicalReachableDomain") != [0.0, 1.0]
        or control.get("runtimeSignal")
        != "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST"
        or control.get("sourcePrimitive") != "latestNormalizedBoost"
        or control.get("secondNormalizationApplied") is not False
        or control.get("seek") != static_control["seek"]
        or static_control["seek"] != {"upPerSecond": 0.0, "downPerSecond": 0.0}
        or control.get("runtimeClamp") != "CLAMP_TO_DECLARED_DOMAIN"
        or control.get("declaredHeadroomAbovePhysicalReachabilityRetained")
        is not True
    ):
        _fail("boostControlMismatch", "AC event boost mapping changed")
    disposition = str(verification.get("disposition") or "")
    gain = verification.get("gainVerification")
    if not isinstance(gain, dict) or gain.get("accepted") is not True:
        _fail("gainVerificationFailed", "source-bound routed-gain proof is absent")
    transition_intervals = gain.get("transitionIntervals")
    if (
        not isinstance(transition_intervals, list)
        or _finite(gain.get("maximumSmoothAbsoluteLinearError"), "gain error")
        > GAIN_CURVE_MAXIMUM_ERROR
        or any(
            not isinstance(item, dict)
            or _finite(item.get("width"), "gain transition width") > 2.0**-20
            for item in transition_intervals
        )
    ):
        _fail("gainVerificationFailed", "gain linearization exceeds its bound")
    if disposition == "AUTHORED_TARGET_ROUTED_SILENT":
        absolute_curve = _curve(
            gain.get("absoluteRoutedGainCurve"),
            label="silent routed gain curve",
            domain=domain,
            minimum_y=0.0,
            maximum_y=GAIN_CURVE_MAXIMUM,
        )
        if any(point[1] != 0.0 for point in absolute_curve):
            _fail("silentDispositionMismatch", "silent source has nonzero routed gain")
        if verification.get("pitchVerification") is not None:
            _fail("silentDispositionMismatch", "silent source projects runtime pitch")
        silence = verification.get("silenceVerification")
        embedded = static_source["sourceGeometry"]["sampleTechnicalEvidence"]
        if not isinstance(silence, dict) or (
            silence.get("accepted") is not True
            or silence.get("targetScheduledAtEveryOraclePoint") is not True
            or silence.get("targetRouteExactZeroAtEveryOraclePoint") is not True
            or silence.get("targetPcmAllZeroInTwoIndependentRenders") is not True
            or silence.get("embeddedSourcePcmNonzero") is not True
            or silence.get("embeddedPcmPayloadSha256")
            != embedded["encodedPayloadSha256"]
        ):
            _fail("silentDispositionMismatch", "authored routed silence is unproven")
        output = copy.deepcopy(static_source)
        output.update(
            {
                "schema": CERTIFIED_SCHEMA,
                "disposition": disposition,
                "capture": None,
                "controlGainCurves": [],
                "pitchCurve": [],
                "continuousPolicy": None,
                "sourceVerification": copy.deepcopy(source_verification),
            }
        )
    elif disposition == "AUDIBLE_TARGET_PCM":
        capture = verification.get("capture")
        if not isinstance(capture, dict):
            _fail("pcmVerificationFailed", "audible capture is absent")
        capture_boost = _finite(capture.get("captureBoost"), "capture boost")
        if not domain[0] <= capture_boost <= domain[1]:
            _fail("pcmVerificationFailed", "capture boost is outside declared domain")
        relative_curve = _curve(
            gain.get("captureRelativeRoutedGainCurve"),
            label="capture-relative routed gain",
            domain=domain,
            minimum_y=0.0,
            maximum_y=GAIN_CURVE_MAXIMUM,
            require_capture_unity=capture_boost,
        )
        maximum_relative = max(point[1] for point in relative_curve)
        if abs(maximum_relative - _finite(gain.get("maximumCaptureRelativeGain"), "maximum relative gain")) > 1.0e-9:
            _fail("gainVerificationFailed", "maximum relative gain summary differs")
        if (
            gain.get("runtimeGainComposition")
            != "SOURCE_BOUND_ROUTED_GAIN_TIMES_SOURCE_BOUND_CUBIC_VARISPEED_RMS_COMPENSATION"
            or _finite(
                gain.get("effectiveCurveProductMaximumLinearizationError"),
                "effective gain product error",
            )
            > GAIN_CURVE_MAXIMUM_ERROR
        ):
            _fail("gainVerificationFailed", "effective runtime gain composition differs")
        compensation = gain.get("runtimeResamplerGainCompensation")
        compensation_curve = (
            compensation.get("linearCurve")
            if isinstance(compensation, dict)
            else None
        )
        if not isinstance(compensation, dict) or (
            compensation.get("kind")
            != "SOURCE_BOUND_CUBIC_TO_FMOD_LONG_WINDOW_RMS"
            or compensation.get("sign") != "MULTIPLY_RUNTIME_GAIN"
            or compensation.get("applicationCount") != 1
            or compensation.get("bakedIntoTargetOnlyPcm") is not False
            or compensation.get("foldedIntoEffectiveControlGainCurve") is not True
            or compensation_curve != gain.get("cubicVarispeedRmsCompensationCurve")
        ):
            _fail("gainVerificationFailed", "resampler compensation is not single-use")
        pitch = verification.get("pitchVerification")
        if not isinstance(pitch, dict) or pitch.get("accepted") is not True:
            _fail("pitchVerificationFailed", "source-bound rate proof is absent")
        pitch_curve = _curve(
            pitch.get("captureRelativePlaybackRateCurve"),
            label="capture-relative playback rate",
            domain=domain,
            minimum_y=1.0 / PITCH_RATE_MAXIMUM,
            maximum_y=PITCH_RATE_MAXIMUM,
            require_capture_unity=capture_boost,
        )
        if (
            pitch.get("control") != RUNTIME_CONTROL
            or pitch.get("interpolation") != RUNTIME_INTERPOLATION
            or pitch.get("runtimeTreatment")
            != "REPLACES_ORDINARY_RPM_ROOT_RATIO"
            or _finite(pitch.get("maximumLiveRateErrorCents"), "live rate error")
            > PITCH_ERROR_CENTS_MAXIMUM
        ):
            _fail("pitchVerificationFailed", "runtime rate contract differs")
        path = PurePosixPath(str(capture.get("finalWavRelativePath") or ""))
        try:
            frame_count = int(capture.get("frameCount"))
            loop_start = int(capture.get("loopStartFrame"))
            loop_end = int(capture.get("loopEndFrameExclusive"))
        except (TypeError, ValueError):
            _fail("pcmVerificationFailed", "capture frame/loop bounds are invalid")
        if (
            path.is_absolute()
            or not path.parts
            or ".." in path.parts
            or frame_count <= 0
            or not 0 <= loop_start < loop_end <= frame_count
            or _finite(capture.get("peakPcmDbfs"), "capture peak") <= -96.0
            or not all(
                _sha256(capture.get(name), f"capture {name}")
                for name in ("finalWavSha256", "pcmPayloadSha256")
            )
            or capture.get("scheduledSourceGuids") != [static_source["sourceGuid"]]
            or capture.get("independentFreshProcessRendersBitExact") is not True
        ):
            _fail("pcmVerificationFailed", "capture identity/audibility changed")
        spectral = verification.get("targetPcmVerification")
        if not isinstance(spectral, dict) or (
            spectral.get("accepted") is not True
            or _finite(spectral.get("maximumPitchErrorCents"), "PCM pitch error")
            > PITCH_ERROR_CENTS_MAXIMUM
            or _finite(spectral.get("maximumGainErrorDb"), "PCM gain error")
            > GAIN_ERROR_DB_MAXIMUM
        ):
            _fail("pcmVerificationFailed", "target/runtime PCM comparison failed")
        lifecycle = verification.get("lifecycleVerification")
        if not isinstance(lifecycle, dict) or (
            lifecycle.get("accepted") is not True
            or lifecycle.get("programMode") != static_source["programMode"]
            or lifecycle.get("runtimePolicy") is None
        ):
            _fail("lifecycleVerificationFailed", "continuous lifecycle is unproven")
        output = copy.deepcopy(static_source)
        output.update(
            {
                "schema": CERTIFIED_SCHEMA,
                "disposition": disposition,
                "capture": copy.deepcopy(capture),
                "controlGainCurves": [
                    {"control": RUNTIME_CONTROL, "curve": relative_curve}
                ],
                "pitchCurve": pitch_curve,
                "continuousPolicy": copy.deepcopy(lifecycle["runtimePolicy"]),
                "sourceVerification": copy.deepcopy(source_verification),
            }
        )
    else:
        _fail("invalidDisposition", "continuous turbo disposition is unsupported")
    output["fidelity"] = copy.deepcopy(output.get("fidelity") or {})
    output["fidelity"].update(
        {
            "exactnessClaim": True,
            "requiredFinalGate": None,
            "sourceVerificationPayloadSha256": embedded_hash,
        }
    )
    return output


__all__ = [
    "CERTIFIED_SCHEMA",
    "CLASSIFIER_ROLE",
    "FmodContinuousTurboError",
    "RUNTIME_CONTROL",
    "RUNTIME_INTERPOLATION",
    "RUNTIME_PITCH_MODE",
    "STATIC_SCHEMA",
    "VERIFICATION_SCHEMA",
    "certify_continuous_turbo_source",
    "derive_continuous_turbo_source",
]
