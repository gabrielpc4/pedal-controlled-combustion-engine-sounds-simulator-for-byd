"""Fail-closed certificates for dynamic FMOD continuous-source evidence.

The bank parser remains the authoritative topology/source selector, but a
source-bound FMOD 1.08 oracle owns claims about live routed gain and property-1
playback rate.  This module is pure: it validates immutable evidence and never
opens a bank, invokes FMOD, or infers semantics from a sample filename.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import PurePosixPath
from typing import Any, Iterable


DIAGNOSTIC_SCHEMA = "ac-fmod-continuous-source-diagnostic-v1"
FORBIDDEN_PEDAL_VERIFICATION_SCHEMA = (
    "ac-fmod-forbidden-on-pedal-routing-source-verification-v1"
)
ROUTED_SILENCE_VERIFICATION_SCHEMA = (
    "ac-fmod-authored-routed-silence-source-verification-v1"
)
PROPERTY_ONE_VERIFICATION_SCHEMA = (
    "ac-fmod-property-one-relative-rate-source-verification-v1"
)
PROPERTY_ONE_PITCH_MODE = "AUTHORED_PROPERTY_ONE_RELATIVE_RATE"
AUTO_PITCH_MODE = "AUTO_PITCH_RPM_RATIO"
PROPERTY_ONE_INTERPOLATION = "CLAMPED_LINEAR"
PROPERTY_ONE_RAW_TO_SEMITONES = 24.0
FORBIDDEN_PEDAL_MINIMUM_SUPPRESSION_DB = 12.0
PROPERTY_ONE_MAXIMUM_PITCH_ERROR_CENTS = 5.0


class FmodContinuousSourceError(ValueError):
    """Typed failure raised when dynamic source evidence is incomplete."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def _fail(code: str, detail: str) -> None:
    raise FmodContinuousSourceError(code, detail)


def _canonical_hash(value: object) -> str:
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        _fail("nonCanonicalEvidence", str(error))
    return hashlib.sha256(encoded).hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _is_sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _finite(value: object, label: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        _fail("invalidNumericEvidence", f"{label} is not numeric")
    if not math.isfinite(result):
        _fail("invalidNumericEvidence", f"{label} is not finite")
    return result


def _validate_diagnostic(
    diagnostic: dict[str, Any],
    *,
    source_guid: str,
    derived_source_sha256: str,
) -> None:
    if not isinstance(diagnostic, dict) or diagnostic.get("schema") != DIAGNOSTIC_SCHEMA:
        _fail("invalidDiagnostic", f"expected {DIAGNOSTIC_SCHEMA}")
    embedded_hash = diagnostic.get("diagnosticPayloadSha256")
    payload = copy.deepcopy(diagnostic)
    payload.pop("diagnosticPayloadSha256", None)
    if (
        not isinstance(embedded_hash, str)
        or embedded_hash != _canonical_hash(payload)
    ):
        _fail("diagnosticHashMismatch", "continuous diagnostic hash differs")
    if _guid(diagnostic.get("sourceGuid")) != _guid(source_guid):
        _fail("sourceIdentityMismatch", "diagnostic source GUID differs")
    if diagnostic.get("derivedSourceSha256") != derived_source_sha256:
        _fail("derivedSourceMismatch", "static source derivation changed")
    renderer = diagnostic.get("renderer")
    if (
        not isinstance(renderer, dict)
        or renderer.get("runtime") != "FMOD Studio API 1.08.12"
        or renderer.get("mode") != "WAVWRITER_NRT"
        or renderer.get("sampleRateHz") != 48000
        or renderer.get("channels") != 2
        or renderer.get("audioDeviceOpened") is not False
        or renderer.get("freshProcessScope") != "ONE_SOURCE_PER_PROCESS"
    ):
        _fail("rendererContractMismatch", "diagnostic renderer contract changed")
    isolation = diagnostic.get("isolation")
    if (
        not isinstance(isolation, dict)
        or isolation.get("targetSourceWasNotPatched") is not True
        or isolation.get("changedBytesOnlyParserAttributedFields") is not True
        or isolation.get("sourceBankSha256") != diagnostic.get("familyId")
    ):
        _fail("isolationMismatch", "target-only isolation is unproven")
    embedded = diagnostic.get("embeddedSourcePcm")
    if (
        not isinstance(embedded, dict)
        or embedded.get("accepted") is not True
        or embedded.get("encoding") != "FSB5_PCM16_LE"
        or embedded.get("allAuthoredSamplesZero") is not False
        or embedded.get("sampleNameUsed") is not False
        or _finite(embedded.get("peakPcmDbfs"), "embedded peak") <= -96.0
    ):
        _fail("embeddedPcmMismatch", "embedded target PCM is absent/silent/unbound")
    identity = diagnostic.get("runtimeIdentity")
    if (
        not isinstance(identity, dict)
        or identity.get("usedOnlyForCallbackChannelJoin") is not True
        or identity.get("sampleNameEmitted") is not False
    ):
        _fail("runtimeIdentityMisuse", "runtime name escaped callback-only scope")


def _observations(diagnostic: dict[str, Any]) -> list[dict[str, Any]]:
    raw = diagnostic.get("observations")
    if not isinstance(raw, list) or not raw or not all(isinstance(item, dict) for item in raw):
        _fail("invalidObservations", "continuous observations are absent")
    if diagnostic.get("observationCount") != len(raw):
        _fail("invalidObservations", "continuous observation count differs")
    return raw


def certify_forbidden_on_pedal_routing(
    diagnostic: dict[str, Any],
    *,
    source_guid: str,
    derived_source_sha256: str,
    retained_allowed_recipe_ids: Iterable[str],
    retained_idle_recipe_ids: Iterable[str],
) -> dict[str, Any]:
    """Certify that a misleading static role is dynamically on-pedal-only.

    At least two distinct audible speed slices must each contain release,
    intermediate-pedal, and high-pedal observations.  The routed gain must
    increase twice and release must be suppressed by at least 12 dB relative
    to the high-pedal value.  The resulting source is excluded; it is never
    relabeled or serialized as a runtime role.
    """

    _validate_diagnostic(
        diagnostic,
        source_guid=source_guid,
        derived_source_sha256=derived_source_sha256,
    )
    retained = sorted({str(value) for value in retained_allowed_recipe_ids if str(value)})
    retained_idle = sorted({str(value) for value in retained_idle_recipe_ids if str(value)})
    if not retained or not retained_idle or not set(retained_idle).issubset(retained):
        _fail("familyContentNotRetained", "allowed family/IDLE content is absent")
    cross = [item for item in _observations(diagnostic) if item.get("kind") == "CROSS_POINT"]
    speed_name = "drivetrain_speed" if "drivetrain_speed" in diagnostic["parameterDomains"] else "rpms"
    by_speed: dict[float, list[dict[str, Any]]] = {}
    for item in cross:
        parameters = item.get("parameters")
        if not isinstance(parameters, dict) or speed_name not in parameters or "throttle" not in parameters:
            _fail("invalidPedalObservation", "cross point lacks speed/pedal")
        by_speed.setdefault(_finite(parameters[speed_name], "speed"), []).append(item)
    speed_proofs: list[dict[str, Any]] = []
    for speed, items in sorted(by_speed.items()):
        points = sorted(
            (
                _finite(item["parameters"]["throttle"], "pedal"),
                _finite(item.get("routedGain"), "routed gain"),
            )
            for item in items
            if item.get("targetScheduled") is True
            and item.get("activeTargetVoice") is True
        )
        unique: dict[float, float] = {}
        for pedal, gain in points:
            unique[pedal] = gain
        points = sorted(unique.items())
        if len(points) < 3 or points[0][0] != 0.0:
            continue
        release_pedal, release_gain = points[0]
        high_pedal, high_gain = points[-1]
        interior = [item for item in points[1:-1] if 0.0 < item[0] < high_pedal]
        if not interior or high_gain <= 0.0:
            continue
        middle_pedal, middle_gain = max(interior, key=lambda item: item[0])
        if not (0.0 <= release_gain < middle_gain < high_gain):
            continue
        suppression_db = (
            math.inf
            if release_gain == 0.0
            else 20.0 * math.log10(high_gain / release_gain)
        )
        if suppression_db < FORBIDDEN_PEDAL_MINIMUM_SUPPRESSION_DB:
            continue
        speed_proofs.append(
            {
                "speedControl": speed_name,
                "speedValue": speed,
                "release": {"pedal": release_pedal, "routedGain": release_gain},
                "intermediate": {"pedal": middle_pedal, "routedGain": middle_gain},
                "high": {"pedal": high_pedal, "routedGain": high_gain},
                "highToReleaseGainRatio": (
                    None if release_gain == 0.0 else high_gain / release_gain
                ),
                "releaseSuppressionDb": (
                    "POSITIVE_INFINITY" if math.isinf(suppression_db) else suppression_db
                ),
                "strictlyIncreasingAcrossTwoPedalIntervals": True,
            }
        )
    if len({item["speedValue"] for item in speed_proofs}) < 2:
        _fail(
            "insufficientPedalDirectionProof",
            "fewer than two audible speed slices prove on-pedal routing",
        )
    finite_suppressions = [
        float(item["releaseSuppressionDb"])
        for item in speed_proofs
        if item["releaseSuppressionDb"] != "POSITIVE_INFINITY"
    ]
    if finite_suppressions and max(finite_suppressions) - min(finite_suppressions) > 0.02:
        _fail("nonSeparablePedalDirection", "pedal suppression changes across speed")
    certificate = {
        "schema": FORBIDDEN_PEDAL_VERIFICATION_SCHEMA,
        "familyId": diagnostic["familyId"],
        "sourceGuid": _guid(source_guid),
        "eventPath": diagnostic["eventPath"],
        "staticClassifierRole": diagnostic["classifierRole"],
        "disposition": "FORBIDDEN_ON_PEDAL_ROUTING",
        "derivedSourceSha256": derived_source_sha256,
        "diagnosticPayloadSha256": diagnostic["diagnosticPayloadSha256"],
        "embeddedSourcePcm": copy.deepcopy(diagnostic["embeddedSourcePcm"]),
        "speedSliceProofs": speed_proofs,
        "minimumReleaseSuppressionDb": FORBIDDEN_PEDAL_MINIMUM_SUPPRESSION_DB,
        "pedalDirection": "STRICTLY_INCREASING_TO_HIGH_PEDAL",
        "staticSemanticLabelCannotOverrideLiveFader": True,
        "sourceExcludedFromPlanMediaControlsAndRuntime": True,
        "sourceIsNotRelabeledAsAnyRuntimeRole": True,
        "retainedAllowedFamilyContent": {
            "recipeIds": retained,
            "idleRecipeIds": retained_idle,
        },
        "exactnessClaim": True,
    }
    certificate["verificationPayloadSha256"] = _canonical_hash(certificate)
    return certificate


def certify_authored_routed_silence(
    diagnostic: dict[str, Any],
    *,
    source_guid: str,
    derived_source_sha256: str,
    retained_allowed_recipe_ids: Iterable[str],
    retained_idle_recipe_ids: Iterable[str],
) -> dict[str, Any]:
    """Certify an embedded-audible source whose target route is exactly zero."""

    _validate_diagnostic(
        diagnostic,
        source_guid=source_guid,
        derived_source_sha256=derived_source_sha256,
    )
    retained = sorted({str(value) for value in retained_allowed_recipe_ids if str(value)})
    retained_idle = sorted({str(value) for value in retained_idle_recipe_ids if str(value)})
    if not retained or not retained_idle or not set(retained_idle).issubset(retained):
        _fail("familyContentNotRetained", "allowed family/IDLE content is absent")
    observations = _observations(diagnostic)
    if diagnostic.get("maximumObservedRoutedGain") != 0.0:
        _fail("routedSourceNotSilent", "diagnostic reports a nonzero routed gain")
    zero_group_paths: set[tuple[int, ...]] = set()
    for index, item in enumerate(observations):
        if (
            item.get("targetScheduled") is not True
            or item.get("activeTargetVoice") is not True
            or item.get("isVirtual") is not True
            or _finite(item.get("routedGain"), f"observation {index} routed gain") != 0.0
            or _finite(item.get("audibility"), f"observation {index} audibility") != 0.0
        ):
            _fail("routedSourceNotSilent", f"observation {index} is not exact zero")
        components = item.get("routedGainComponents")
        if not isinstance(components, list) or len(components) != 1:
            _fail("silentRouteNotStatic", f"observation {index} has ambiguous route")
        component = components[0]
        group_volumes = component.get("groupVolumes")
        path = component.get("groupIndexPath")
        if (
            not isinstance(group_volumes, list)
            or not group_volumes
            or _finite(group_volumes[0], f"observation {index} root group") != 0.0
            or _finite(component.get("channelVolume"), f"observation {index} channel") <= 0.0
            or _finite(component.get("product"), f"observation {index} product") != 0.0
            or not isinstance(path, list)
            or not all(isinstance(value, int) and value >= 0 for value in path)
        ):
            _fail(
                "silentRouteNotStatic",
                f"observation {index} lacks the exact-zero root-group route",
            )
        zero_group_paths.add(tuple(path))
    if len(zero_group_paths) != 1:
        _fail("silentRouteNotStatic", "target route path changes across controls")
    axis_values = diagnostic.get("axisValues")
    domains = diagnostic.get("parameterDomains")
    if not isinstance(axis_values, dict) or not isinstance(domains, dict):
        _fail("incompleteSilentSurface", "control domains/axis values are absent")
    for name, domain in domains.items():
        if name not in axis_values or not isinstance(domain, list) or len(domain) != 2:
            _fail("incompleteSilentSurface", f"axis {name} is absent")
        values = [_finite(value, f"axis {name}") for value in axis_values[name]]
        if min(values) != _finite(domain[0], f"domain {name} min") or max(values) != _finite(domain[1], f"domain {name} max"):
            _fail("incompleteSilentSurface", f"axis {name} does not span its domain")
    certificate = {
        "schema": ROUTED_SILENCE_VERIFICATION_SCHEMA,
        "familyId": diagnostic["familyId"],
        "sourceGuid": _guid(source_guid),
        "eventPath": diagnostic["eventPath"],
        "staticClassifierRole": diagnostic["classifierRole"],
        "disposition": "AUTHORED_TARGET_ROUTED_SILENT",
        "derivedSourceSha256": derived_source_sha256,
        "diagnosticPayloadSha256": diagnostic["diagnosticPayloadSha256"],
        "embeddedSourcePcm": copy.deepcopy(diagnostic["embeddedSourcePcm"]),
        "controlDomains": copy.deepcopy(domains),
        "axisValues": copy.deepcopy(axis_values),
        "exactZeroObservationCount": len(observations),
        "targetScheduledAndActiveAtEveryObservation": True,
        "targetRouteAndAudibilityExactZeroAtEveryObservation": True,
        "staticRootGroupVolumeExactZeroAtEveryObservation": True,
        "targetGroupIndexPath": list(next(iter(zero_group_paths))),
        "sourceExcludedFromPlanMediaControlsAndRuntime": True,
        "retainedAllowedFamilyContent": {
            "recipeIds": retained,
            "idleRecipeIds": retained_idle,
        },
        "exactnessClaim": True,
    }
    certificate["verificationPayloadSha256"] = _canonical_hash(certificate)
    return certificate


def validate_property_one_pitch_curve(
    points: object,
    *,
    capture_rpm: float,
    rpm_domain: tuple[float, float],
) -> list[list[float]]:
    if not isinstance(points, list) or len(points) < 2 or len(points) > 512:
        _fail("invalidPitchCurve", "property-one pitch curve size is invalid")
    result: list[list[float]] = []
    previous = -math.inf
    for index, point in enumerate(points):
        if not isinstance(point, list) or len(point) != 2:
            _fail("invalidPitchCurve", f"pitch curve point {index} is invalid")
        rpm = _finite(point[0], f"pitch curve rpm {index}")
        rate = _finite(point[1], f"pitch curve rate {index}")
        if rpm <= previous or rate <= 0.0 or rate > 16.0:
            _fail("invalidPitchCurve", f"pitch curve point {index} is out of range")
        previous = rpm
        result.append([rpm, rate])
    if result[0][0] != rpm_domain[0] or result[-1][0] != rpm_domain[1]:
        _fail("invalidPitchCurve", "pitch curve does not span RPM domain")
    capture_rate = _linear_curve(result, capture_rpm)
    if abs(capture_rate - 1.0) > 2.0e-4:
        _fail("invalidPitchCurve", "pitch curve is not capture-normalized")
    return result


def _linear_curve(points: list[list[float]], value: float) -> float:
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for left, right in zip(points, points[1:]):
        if left[0] <= value <= right[0]:
            fraction = (value - left[0]) / (right[0] - left[0])
            return left[1] + fraction * (right[1] - left[1])
    _fail("invalidPitchCurve", "pitch curve lookup failed")


def certify_property_one_relative_rate(
    fallback_plan: dict[str, Any], source_verification: dict[str, Any]
) -> dict[str, Any]:
    """Upgrade one property-1 source only after PCM/rate/spectral evidence."""

    if fallback_plan.get("schema") != "ac-fmod-authored-windowed-capture-fallback-v1":
        _fail("invalidFallbackPlan", "property-one fallback schema differs")
    if source_verification.get("schema") != PROPERTY_ONE_VERIFICATION_SCHEMA:
        _fail("invalidPropertyOneProof", "property-one verification schema differs")
    embedded_hash = source_verification.get("verificationPayloadSha256")
    payload = copy.deepcopy(source_verification)
    payload.pop("verificationPayloadSha256", None)
    if not isinstance(embedded_hash, str) or embedded_hash != _canonical_hash(payload):
        _fail("propertyOneProofHashMismatch", "property-one proof hash differs")
    source_guid = _guid(fallback_plan.get("sourceGuid"))
    if (
        _guid(source_verification.get("sourceGuid")) != source_guid
        or source_verification.get("familyId") is None
        or source_verification.get("eventPath") != fallback_plan.get("eventPath")
        or source_verification.get("fallbackPlanSha256") != _canonical_hash(fallback_plan)
        or source_verification.get("derivedSourceSha256")
        != _canonical_hash(fallback_plan)
    ):
        _fail("propertyOneIdentityMismatch", "property-one source identity differs")
    if (
        source_verification.get("pitchMode") != PROPERTY_ONE_PITCH_MODE
        or source_verification.get("interpolation") != PROPERTY_ONE_INTERPOLATION
        or source_verification.get("ordinaryAutoPitchRpmRatioReplaced") is not True
        or source_verification.get("rawValueToSemitonesScale") != PROPERTY_ONE_RAW_TO_SEMITONES
    ):
        _fail("propertyOneRuntimeContractMismatch", "property-one runtime contract differs")
    rpm_curve = fallback_plan.get("rpmCurve")
    if not isinstance(rpm_curve, list) or len(rpm_curve) < 2:
        _fail("invalidFallbackPlan", "fallback RPM curve is absent")
    rpm_domain = (float(rpm_curve[0][0]), float(rpm_curve[-1][0]))
    capture_rpm = float(fallback_plan["captureRootRpm"])
    pitch_curve = validate_property_one_pitch_curve(
        source_verification.get("pitchCurve"),
        capture_rpm=capture_rpm,
        rpm_domain=rpm_domain,
    )
    rate = source_verification.get("rateVerification")
    spectral = source_verification.get("targetPcmSpectralVerification")
    capture = source_verification.get("capture")
    capture_path = (
        PurePosixPath(str(capture.get("finalWavRelativePath", "")))
        if isinstance(capture, dict)
        else PurePosixPath()
    )
    independent = capture.get("independentFreshProcessRenders") if isinstance(capture, dict) else None
    rate_observations = rate.get("observations") if isinstance(rate, dict) else None
    spectral_probes = spectral.get("probes") if isinstance(spectral, dict) else None
    if (
        not isinstance(rate, dict)
        or rate.get("accepted") is not True
        or _finite(rate.get("maximumPitchErrorCents"), "rate pitch error")
        > PROPERTY_ONE_MAXIMUM_PITCH_ERROR_CENTS
        or int(rate.get("observationCount", 0)) < 8
        or not isinstance(rate_observations, list)
        or len(rate_observations) != int(rate.get("observationCount", 0))
        or not isinstance(spectral, dict)
        or spectral.get("accepted") is not True
        or _finite(spectral.get("maximumPitchErrorCents"), "spectral pitch error")
        > PROPERTY_ONE_MAXIMUM_PITCH_ERROR_CENTS
        or _finite(spectral.get("maximumGainErrorDb"), "spectral gain error") > 0.25
        or int(spectral.get("probeCount", 0)) < 3
        or not isinstance(spectral_probes, list)
        or len(spectral_probes) != int(spectral.get("probeCount", 0))
        or not isinstance(capture, dict)
        or capture.get("independentFreshProcessRendersBitExact") is not True
        or not isinstance(independent, list)
        or len(independent) != 2
        or capture.get("scheduledSourceGuids") != [source_guid]
        or capture.get("sampleRateHz") != 48000
        or capture.get("channels") != 2
        or capture.get("sampleFormat") != "SIGNED_PCM16_LE"
        or _finite(capture.get("peakPcmDbfs"), "capture peak") <= -96.0
        or not _is_sha256(capture.get("pcmPayloadSha256"))
        or not _is_sha256(capture.get("finalWavSha256"))
        or not _is_sha256(capture.get("analysisPcmPayloadSha256"))
        or capture_path.is_absolute()
        or not capture_path.parts
        or ".." in capture_path.parts
        or int(capture.get("frameCount", 0)) <= 0
        or int(capture.get("loopStartFrame", -1)) < 0
        or int(capture.get("loopEndFrameExclusive", 0))
        > int(capture.get("frameCount", 0))
        or int(capture.get("loopStartFrame", -1))
        >= int(capture.get("loopEndFrameExclusive", 0))
    ):
        _fail("propertyOneFinalGateFailed", "PCM/rate/spectral property-one gate failed")
    for index, item in enumerate(independent):
        worker = item.get("worker") if isinstance(item, dict) else None
        if (
            not isinstance(item, dict)
            or item.get("scheduledSourceGuids") != [source_guid]
            or item.get("frameCount") != capture.get("oracleRenderFrameCount")
            or item.get("pcmPayloadSha256")
            != capture.get("analysisPcmPayloadSha256")
            or not _is_sha256(item.get("wavSha256"))
            or not isinstance(worker, dict)
            or not _is_sha256(worker.get("evidenceSha256"))
            or not _is_sha256(worker.get("requestSha256"))
            or not _is_sha256(worker.get("renderIdentitySha256"))
            or not _is_sha256(worker.get("resultSha256"))
            or worker.get("outputSha256") != item.get("wavSha256")
        ):
            _fail(
                "propertyOneFinalGateFailed",
                f"fresh-process capture evidence {index} is invalid",
            )
    if independent[0]["worker"]["requestSha256"] == independent[1]["worker"]["requestSha256"]:
        _fail("propertyOneFinalGateFailed", "independent capture requests are not distinct")
    if any(
        not isinstance(item, dict)
        or _finite(item.get("pitchErrorCents"), "rate observation pitch error")
        > PROPERTY_ONE_MAXIMUM_PITCH_ERROR_CENTS
        for item in rate_observations
    ):
        _fail("propertyOneFinalGateFailed", "a live rate observation exceeds tolerance")
    if any(
        not isinstance(item, dict)
        or _finite(item.get("pitchErrorCents"), "spectral probe pitch error")
        > PROPERTY_ONE_MAXIMUM_PITCH_ERROR_CENTS
        or _finite(item.get("gainErrorDb"), "spectral probe gain error") > 0.25
        for item in spectral_probes
    ):
        _fail("propertyOneFinalGateFailed", "a target PCM probe exceeds tolerance")
    if (
        source_verification.get("installedBankUnchanged") is not True
        or source_verification.get("installedBankSha256Before")
        != source_verification.get("installedBankSha256After")
        or source_verification.get("installedBankSha256Before")
        != source_verification.get("familyId")
    ):
        _fail("propertyOneFinalGateFailed", "installed bank identity is unbound")
    output = copy.deepcopy(fallback_plan)
    output["schema"] = "ac-fmod-certified-property-one-relative-rate-v1"
    output["kind"] = "singleTargetCaptureWithAuthoredRelativeRate"
    output["pitchMode"] = PROPERTY_ONE_PITCH_MODE
    output["pitchCurve"] = pitch_curve
    output["pitchCurveInterpolation"] = PROPERTY_ONE_INTERPOLATION
    output["capture"] = copy.deepcopy(capture)
    output["fidelity"] = copy.deepcopy(output.get("fidelity") or {})
    output["fidelity"].update(
        {
            "exactnessClaim": True,
            "requiredFinalGate": None,
            "sourceVerificationPayloadSha256": embedded_hash,
            "maximumRatePitchErrorCents": rate["maximumPitchErrorCents"],
            "maximumTargetPcmPitchErrorCents": spectral["maximumPitchErrorCents"],
            "maximumTargetPcmGainErrorDb": spectral["maximumGainErrorDb"],
        }
    )
    output["sourceVerification"] = copy.deepcopy(source_verification)
    return output


__all__ = [
    "AUTO_PITCH_MODE",
    "DIAGNOSTIC_SCHEMA",
    "FORBIDDEN_PEDAL_VERIFICATION_SCHEMA",
    "FmodContinuousSourceError",
    "PROPERTY_ONE_INTERPOLATION",
    "PROPERTY_ONE_PITCH_MODE",
    "PROPERTY_ONE_RAW_TO_SEMITONES",
    "PROPERTY_ONE_VERIFICATION_SCHEMA",
    "ROUTED_SILENCE_VERIFICATION_SCHEMA",
    "certify_authored_routed_silence",
    "certify_forbidden_on_pedal_routing",
    "certify_property_one_relative_rate",
    "validate_property_one_pitch_curve",
]
