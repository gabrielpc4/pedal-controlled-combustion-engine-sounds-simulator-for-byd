from __future__ import annotations

import copy
import hashlib
import json
import unittest


from sim.fmod_continuous_source import (
    DIAGNOSTIC_SCHEMA,
    FmodContinuousSourceError,
    PROPERTY_ONE_INTERPOLATION,
    PROPERTY_ONE_PITCH_MODE,
    PROPERTY_ONE_VERIFICATION_SCHEMA,
    certify_authored_routed_silence,
    certify_forbidden_on_pedal_routing,
    certify_property_one_relative_rate,
    validate_property_one_pitch_curve,
)


def _hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()


def _diagnostic(*, silent: bool = False) -> dict:
    source = "11111111-2222-3333-4444-555555555555"
    observations = []
    for speed, speed_gain in ((4000.0, 0.5), (7000.0, 1.0)):
        for pedal, relative in ((0.0, 0.01), (0.5, 0.4), (1.0, 1.0)):
            routed = 0.0 if silent else speed_gain * relative
            observations.append(
                {
                    "kind": "CROSS_POINT",
                    "parameters": {"rpms": speed, "throttle": pedal},
                    "targetScheduled": True,
                    "scheduledSourceGuids": [source],
                    "activeTargetVoice": True,
                    "isVirtual": silent,
                    "audibility": 0.0 if silent else routed * 0.25,
                    "routedGain": routed,
                    "routedGainComponents": (
                        [
                            {
                                "groupIndexPath": [0],
                                "groupVolumes": [0.0, 1.0],
                                "channelVolume": 1.0,
                                "product": 0.0,
                            }
                        ]
                        if silent
                        else []
                    ),
                }
            )
    if silent:
        # Domain-spanning axis points are part of the all-domain gate.
        observations.extend(
            {
                "kind": "AXIS_POINT",
                "axis": axis,
                "parameters": {
                    "rpms": value if axis == "rpms" else 7000.0,
                    "throttle": value if axis == "throttle" else 0.95,
                },
                "targetScheduled": True,
                "scheduledSourceGuids": [source],
                "activeTargetVoice": True,
                "isVirtual": True,
                "audibility": 0.0,
                "routedGain": 0.0,
                "routedGainComponents": [
                    {
                        "groupIndexPath": [0],
                        "groupVolumes": [0.0, 1.0],
                        "channelVolume": 1.0,
                        "product": 0.0,
                    }
                ],
            }
            for axis, value in (("rpms", 0.0), ("rpms", 15000.0), ("throttle", 0.0), ("throttle", 1.0))
        )
    value = {
        "schema": DIAGNOSTIC_SCHEMA,
        "familyId": "a" * 64,
        "sourceGuid": source,
        "eventPath": "event:/cars/test/engine_int",
        "classifierRole": "ENGINE_THROTTLE_INDEPENDENT_CANDIDATE",
        "derivationKind": "DIRECT",
        "derivedSourceSha256": "b" * 64,
        "graphReportFileSha256": "c" * 64,
        "classificationFileSha256": "d" * 64,
        "probeImplementationFileSha256": "e" * 64,
        "runtimeIdentity": {
            "usedOnlyForCallbackChannelJoin": True,
            "sha256": "f" * 64,
            "sampleNameEmitted": False,
        },
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "audioDeviceOpened": False,
            "freshProcessScope": "ONE_SOURCE_PER_PROCESS",
            "workerProcessId": 42,
        },
        "isolation": {
            "sourceBankSha256": "a" * 64,
            "isolatedBankSha256": "9" * 64,
            "eventWasAlreadySingleWaveform": False,
            "mutedWaveformCount": 3,
            "disabledFullyMutedParentCount": 0,
            "changedBytesOnlyParserAttributedFields": True,
            "targetSourceWasNotPatched": True,
        },
        "embeddedSourcePcm": {
            "accepted": True,
            "encoding": "FSB5_PCM16_LE",
            "soundBankIndex": 0,
            "subsoundIndex": 1,
            "encodedPayloadSha256": "8" * 64,
            "sampleRateHz": 44100,
            "channels": 2,
            "frameCount": 1000,
            "allAuthoredSamplesZero": False,
            "peakPcmDbfs": -3.0,
            "rmsPcmDbfs": -12.0,
            "sampleNameUsed": False,
        },
        "captureParameterValues": {"rpms": 7000.0, "throttle": 0.0},
        "parameterDomains": {"rpms": [0.0, 15000.0], "throttle": [0.0, 1.0]},
        "axisValues": {"rpms": [0.0, 7000.0, 15000.0], "throttle": [0.0, 0.5, 1.0]},
        "observationCount": len(observations),
        "audibleObservationCount": 0 if silent else len(observations),
        "maximumObservedRoutedGain": 0.0 if silent else 1.0,
        "observations": observations,
        "diagnosticOnly": True,
        "releaseExactnessUpgraded": False,
    }
    value["diagnosticPayloadSha256"] = _hash(value)
    return value


def _fallback() -> dict:
    return {
        "schema": "ac-fmod-authored-windowed-capture-fallback-v1",
        "sourceGuid": "source",
        "eventPath": "event:/cars/test/engine_int",
        "role": "COAST",
        "captureRootRpm": 5000.0,
        "rpmCurve": [[0.0, 0.0], [5000.0, 1.0], [10000.0, 0.5]],
        "gainCurve": [[0.0, 1.0], [1.0, 1.0]],
        "fidelity": {"exactnessClaim": False, "requiredFinalGate": "window"},
    }


def _property_verification(fallback: dict) -> dict:
    value = {
        "schema": PROPERTY_ONE_VERIFICATION_SCHEMA,
        "familyId": "a" * 64,
        "sourceGuid": "source",
        "eventPath": fallback["eventPath"],
        "fallbackPlanSha256": _hash(fallback),
        "derivedSourceSha256": _hash(fallback),
        "pitchMode": PROPERTY_ONE_PITCH_MODE,
        "pitchCurve": [[0.0, 0.5], [5000.0, 1.0], [10000.0, 2.0]],
        "interpolation": PROPERTY_ONE_INTERPOLATION,
        "ordinaryAutoPitchRpmRatioReplaced": True,
        "rawValueToSemitonesScale": 24.0,
        "capture": {
            "scheduledSourceGuids": ["source"],
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "SIGNED_PCM16_LE",
            "frameCount": 144000,
            "oracleRenderFrameCount": 384000,
            "loopStartFrame": 960,
            "loopEndFrameExclusive": 143040,
            "peakPcmDbfs": -3.0,
            "pcmPayloadSha256": "1" * 64,
            "finalWavSha256": "2" * 64,
            "analysisPcmPayloadSha256": "3" * 64,
            "finalWavRelativePath": "property-one/source/capture.wav",
            "independentFreshProcessRendersBitExact": True,
            "independentFreshProcessRenders": [
                {
                    "scheduledSourceGuids": ["source"],
                    "frameCount": 384000,
                    "pcmPayloadSha256": "3" * 64,
                    "wavSha256": "4" * 64,
                    "worker": {
                        "evidenceSha256": "5" * 64,
                        "requestSha256": "6" * 64,
                        "renderIdentitySha256": "7" * 64,
                        "resultSha256": "8" * 64,
                        "outputSha256": "4" * 64,
                    },
                },
                {
                    "scheduledSourceGuids": ["source"],
                    "frameCount": 384000,
                    "pcmPayloadSha256": "3" * 64,
                    "wavSha256": "9" * 64,
                    "worker": {
                        "evidenceSha256": "a" * 64,
                        "requestSha256": "b" * 64,
                        "renderIdentitySha256": "7" * 64,
                        "resultSha256": "c" * 64,
                        "outputSha256": "9" * 64,
                    },
                },
            ],
        },
        "rateVerification": {
            "accepted": True,
            "observationCount": 12,
            "maximumPitchErrorCents": 0.4,
            "observations": [
                {"pitchErrorCents": 0.4} for _index in range(12)
            ],
        },
        "targetPcmSpectralVerification": {
            "accepted": True,
            "probeCount": 4,
            "maximumPitchErrorCents": 2.0,
            "maximumGainErrorDb": 0.1,
            "probes": [
                {"pitchErrorCents": 2.0, "gainErrorDb": 0.1}
                for _index in range(4)
            ],
        },
        "installedBankSha256Before": "a" * 64,
        "installedBankSha256After": "a" * 64,
        "installedBankUnchanged": True,
    }
    value["verificationPayloadSha256"] = _hash(value)
    return value


class ContinuousSourceCertificateTests(unittest.TestCase):
    def test_live_pedal_direction_overrides_misleading_static_role(self) -> None:
        diagnostic = _diagnostic()
        result = certify_forbidden_on_pedal_routing(
            diagnostic,
            source_guid=diagnostic["sourceGuid"],
            derived_source_sha256=diagnostic["derivedSourceSha256"],
            retained_allowed_recipe_ids=("idle", "coast"),
            retained_idle_recipe_ids=("idle",),
        )
        self.assertEqual(result["disposition"], "FORBIDDEN_ON_PEDAL_ROUTING")
        self.assertTrue(result["sourceExcludedFromPlanMediaControlsAndRuntime"])
        self.assertEqual(len(result["speedSliceProofs"]), 2)
        self.assertGreater(result["speedSliceProofs"][0]["highToReleaseGainRatio"], 10.0)

    def test_pedal_direction_and_embedded_pcm_gates_fail_closed(self) -> None:
        wrong = _diagnostic()
        for item in wrong["observations"]:
            if item["kind"] == "CROSS_POINT":
                item["routedGain"] = 1.0 - float(item["parameters"]["throttle"]) * 0.5
        wrong["diagnosticPayloadSha256"] = _hash(
            {key: value for key, value in wrong.items() if key != "diagnosticPayloadSha256"}
        )
        with self.assertRaises(FmodContinuousSourceError):
            certify_forbidden_on_pedal_routing(
                wrong,
                source_guid=wrong["sourceGuid"],
                derived_source_sha256=wrong["derivedSourceSha256"],
                retained_allowed_recipe_ids=("idle",),
                retained_idle_recipe_ids=("idle",),
            )
        embedded_silent = _diagnostic()
        embedded_silent["embeddedSourcePcm"]["allAuthoredSamplesZero"] = True
        embedded_silent["diagnosticPayloadSha256"] = _hash(
            {
                key: value
                for key, value in embedded_silent.items()
                if key != "diagnosticPayloadSha256"
            }
        )
        with self.assertRaises(FmodContinuousSourceError):
            certify_forbidden_on_pedal_routing(
                embedded_silent,
                source_guid=embedded_silent["sourceGuid"],
                derived_source_sha256=embedded_silent["derivedSourceSha256"],
                retained_allowed_recipe_ids=("idle",),
                retained_idle_recipe_ids=("idle",),
            )

    def test_routed_silence_requires_every_observation_exact_zero(self) -> None:
        diagnostic = _diagnostic(silent=True)
        result = certify_authored_routed_silence(
            diagnostic,
            source_guid=diagnostic["sourceGuid"],
            derived_source_sha256=diagnostic["derivedSourceSha256"],
            retained_allowed_recipe_ids=("idle", "transmission_other"),
            retained_idle_recipe_ids=("idle",),
        )
        self.assertEqual(result["disposition"], "AUTHORED_TARGET_ROUTED_SILENT")
        broken = copy.deepcopy(diagnostic)
        broken["observations"][0]["routedGain"] = 1.0e-9
        broken["diagnosticPayloadSha256"] = _hash(
            {key: value for key, value in broken.items() if key != "diagnosticPayloadSha256"}
        )
        with self.assertRaises(FmodContinuousSourceError):
            certify_authored_routed_silence(
                broken,
                source_guid=broken["sourceGuid"],
                derived_source_sha256=broken["derivedSourceSha256"],
                retained_allowed_recipe_ids=("idle",),
                retained_idle_recipe_ids=("idle",),
            )


class PropertyOneCertificateTests(unittest.TestCase):
    def test_certification_emits_single_capture_relative_rate(self) -> None:
        fallback = _fallback()
        verification = _property_verification(fallback)
        result = certify_property_one_relative_rate(fallback, verification)
        self.assertEqual(result["pitchMode"], PROPERTY_ONE_PITCH_MODE)
        self.assertEqual(result["pitchCurveInterpolation"], PROPERTY_ONE_INTERPOLATION)
        self.assertTrue(result["fidelity"]["exactnessClaim"])
        self.assertEqual(result["fidelity"]["sourceVerificationPayloadSha256"], verification["verificationPayloadSha256"])

    def test_pitch_curve_bounds_normalization_and_hash_are_strict(self) -> None:
        validate_property_one_pitch_curve(
            [[0.0, 0.5], [5000.0, 1.0], [10000.0, 2.0]],
            capture_rpm=5000.0,
            rpm_domain=(0.0, 10000.0),
        )
        for points in (
            [[1.0, 0.5], [5000.0, 1.0], [10000.0, 2.0]],
            [[0.0, 0.5], [5000.0, 1.1], [10000.0, 2.0]],
            [[0.0, 0.5], [5000.0, 1.0], [10000.0, 16.000001]],
            [[0.0, 0.5], [0.0, 1.0], [10000.0, 2.0]],
        ):
            with self.assertRaises(FmodContinuousSourceError):
                validate_property_one_pitch_curve(
                    points, capture_rpm=5000.0, rpm_domain=(0.0, 10000.0)
                )
        fallback = _fallback()
        verification = _property_verification(fallback)
        verification["targetPcmSpectralVerification"]["maximumGainErrorDb"] = 0.250001
        verification["verificationPayloadSha256"] = _hash(
            {
                key: value
                for key, value in verification.items()
                if key != "verificationPayloadSha256"
            }
        )
        with self.assertRaises(FmodContinuousSourceError):
            certify_property_one_relative_rate(fallback, verification)


if __name__ == "__main__":
    unittest.main()
