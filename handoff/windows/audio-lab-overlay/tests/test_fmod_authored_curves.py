from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path
import unittest

from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import (
    ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION,
    ENGINE_TRANSIENT_VERIFICATION_SCHEMA,
    FmodAuthoredCurveError,
    TURBO_CONTROL_GAIN_ORACLE_VERSION,
    TURBO_PCM_CAPTURE_ORACLE_VERSION,
    certify_manifest_limiter_program,
    certify_manifest_engine_transient_source,
    certify_manifest_turbo_transient_source,
    derive_manifest_limiter_program,
    derive_manifest_one_shot_curves,
    derive_manifest_source_curves,
    derive_manifest_turbo_transient_source,
    derive_windowed_capture_fallback,
    evaluate_authored_curve,
    evaluate_type0_curve,
    fmod108_volume_automation_linear,
)
from sim.fmod_graph_roles import classify_bank_graph_sources
from sim.fmod_sdk_audit import audit_shipped_fmod_authoring
from tools.audit_fmod_bank_graph import audit_bank_graph


TATUUS_FAMILY = (
    "668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab"
)


def _controller(
    guid: str,
    owner: str,
    parameter_guid: str,
    parameter: str,
    property_index: int,
    points: list[tuple[float, float, float, int]],
) -> dict:
    return {
        "guid": guid,
        "curveGuid": guid,
        "propertyOwnerGuid": owner,
        "inputKind": "parameter",
        "inputParameterGuid": parameter_guid,
        "inputParameterName": parameter,
        "propertyIndex": property_index,
        "points": [
            {"x": x, "y": y, "shape": shape, "type": point_type}
            for x, y, shape, point_type in points
        ],
    }


def _engine_fixture() -> tuple[dict, dict]:
    report = {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "bank": {"sha256": "a" * 64, "fileVersion": 0x50},
        "parameters": [
            {
                "guid": "rpm-param",
                "name": "rpms",
                "minimum": 0.0,
                "maximum": 8000.0,
                "defaultValue": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED",
            },
            {
                "guid": "throttle-param",
                "name": "throttle",
                "minimum": 0.0,
                "maximum": 1.0,
                "defaultValue": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED",
            },
        ],
        "instruments": [
            {
                "guid": "parent",
                "kind": "MultiInstrumentNode",
                "sample": None,
                "childInstruments": [{"guid": "source", "weight": 1.0}],
                "controllerGuids": ["throttle-parent"],
                "baseProperties": {
                    "volumeDb": -1.5,
                    "routableGuid": "route-parent",
                    "loopCount": -1,
                    "autoPitchReference": 0.0,
                },
            },
            {
                "guid": "source",
                "kind": "WaveformInstrumentNode",
                "sample": {"name": "must-never-be-emitted.wav"},
                "childInstruments": [],
                "controllerGuids": ["rpm-in", "rpm-out", "throttle-source"],
                "baseProperties": {
                    "volumeDb": -2.5,
                    "routableGuid": "route-source",
                    "loopCount": -1,
                    "autoPitchReference": 3000.0,
                },
            },
        ],
        "controllers": [
            _controller(
                "rpm-in",
                "source",
                "rpm-param",
                "rpms",
                4,
                [(1000.0, 0.0, -0.25, 0), (2000.0, 1.0, 0.0, 0)],
            ),
            _controller(
                "rpm-out",
                "source",
                "rpm-param",
                "rpms",
                4,
                [(4000.0, 1.0, 0.25, 0), (5000.0, 0.0, 0.0, 0)],
            ),
            _controller(
                "throttle-source",
                "route-source",
                "throttle-param",
                "throttle",
                0,
                [(0.0, 0.0, 0.2, 0), (1.0, -6.0, 0.0, 0)],
            ),
            _controller(
                "throttle-parent",
                "route-parent",
                "throttle-param",
                "throttle",
                0,
                [(0.0, -3.0, 0.0, 0), (1.0, -3.0, 0.0, 0)],
            ),
        ],
        "events": [
            {
                "path": "event:/cars/test/engine_ext",
                "mappingComplete": True,
                "reachableInstrumentGuids": ["parent", "source"],
                "parameterPlacements": [
                    {
                        "instrumentGuid": "source",
                        "parameterGuid": "rpm-param",
                        "parameterName": "rpms",
                        "start": 1000.0,
                        "end": 5000.0,
                        "includeEnd": True,
                    }
                ],
                "timelinePlacements": [],
            }
        ],
        "modulators": [],
        "effects": [],
    }
    classification = {
        "sourceGuid": "source",
        "eventPaths": ["event:/cars/test/engine_ext"],
        "eventSuffixes": ["engine_ext"],
        "lifetime": "continuous",
        "policy": "allowCandidate",
        "role": "ENGINE_FALLING_CANDIDATE",
        "candidateManifestRoles": ["COAST", "EXHAUST", "IDLE"],
    }
    return report, classification


def _limiter_fixture(mode: str) -> tuple[dict, dict]:
    if mode not in {"timeline", "decay-one-shot", "decay-loop"}:
        raise ValueError(mode)
    loop_count = -1 if mode == "decay-loop" else 0
    report = {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "bank": {"sha256": "b" * 64, "fileVersion": 0x50},
        "parameters": [
            {
                "guid": "decay-param",
                "name": "decay",
                "minimum": 0.0,
                "maximum": 1.0,
                "defaultValue": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED",
            }
        ],
        "instruments": [
            {
                "guid": "limiter-source",
                "kind": "WaveformInstrumentNode",
                "sample": {
                    "name": "must-never-be-emitted-limiter.wav",
                    "sampleCount": 48000,
                    "frequencyHz": 48000,
                    "channels": 2,
                    "encodedPayloadBytes": 192000,
                    "encodedPayloadSha256": "c" * 64,
                    "waveformResourceGuid": "resource",
                },
                "childInstruments": [],
                "controllerGuids": ["decay-volume"],
                "baseProperties": {
                    "volumeDb": -2.0,
                    "pitchSemitones": 0.0,
                    "routableGuid": "limiter-route",
                    "loopCount": loop_count,
                    "autoPitchReference": 1.0,
                },
            }
        ],
        "controllers": [
            _controller(
                "decay-volume",
                "limiter-route",
                "decay-param",
                "decay",
                0,
                [(0.0, 0.0, 0.0, 0), (0.15, -42.0, 0.0, 0)],
            )
        ],
        "events": [
            {
                "path": "event:/cars/test/limiter",
                "mappingComplete": True,
                "reachableInstrumentGuids": ["limiter-source"],
                "parameterPlacements": (
                    []
                    if mode == "timeline"
                    else [
                        {
                            "instrumentGuid": "limiter-source",
                            "parameterGuid": "decay-param",
                            "parameterName": "decay",
                            "start": 0.0,
                            "end": 0.15,
                            "includeEnd": True,
                        }
                    ]
                ),
                "timelinePlacements": (
                    [
                        {
                            "instrumentGuid": "limiter-source",
                            "startTime": 0,
                            "length": 24000,
                            "timeLocked": True,
                        }
                    ]
                    if mode == "timeline"
                    else []
                ),
            }
        ],
        "modulators": [
            {
                "guid": "limiter-adsr",
                "ownerGuid": "limiter-route",
                "type": "ADSR",
                "propertyIndex": 0,
            }
        ],
        "effects": [],
    }
    classification = {
        "sourceGuid": "limiter-source",
        "eventPaths": ["event:/cars/test/limiter"],
        "eventSuffixes": ["limiter"],
        "lifetime": "continuous" if loop_count < 0 else "oneShot",
        "policy": "allowCandidate",
        "role": "LIMITER",
        "candidateManifestRoles": ["LIMITER"],
    }
    return report, classification


def _canonical_hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()


def _fresh_engine_worker(operation: str, worker_pid: int) -> dict:
    worker_key = hashlib.sha256(f"{operation}:{worker_pid}".encode()).hexdigest()
    return {
        "requestSchema": "ac-fmod-engine-transient-worker-request-v1",
        "resultSchema": "ac-fmod-engine-transient-worker-result-v1",
        "operation": operation,
        "requestSha256": worker_key,
        "requestFileRelativePath": (
            f"work/workers/requests/{operation}-{worker_pid}.json"
        ),
        "requestFileSha256": "2" * 64,
        "resultFileRelativePath": (
            f"work/workers/results/{operation}-{worker_pid}.json"
        ),
        "resultFileSha256": "3" * 64,
        "payloadSha256": "4" * 64,
        "processBoundary": "ONE_NEW_PYTHON_PROCESS_FOR_THIS_OPERATION",
        "launcherProcessId": 100,
        "workerProcessId": worker_pid,
        "timeoutSeconds": 180,
    }


def _engine_transient_verification(source: dict) -> dict:
    sample = source["sourceGeometry"]["sampleTechnicalEvidence"]
    capture_workers = [
        _fresh_engine_worker("render", 101),
        _fresh_engine_worker("render", 102),
    ]
    value = {
        "schema": ENGINE_TRANSIENT_VERIFICATION_SCHEMA,
        "familyId": "fixture-family",
        "sourceGuid": source["sourceGuid"],
        "eventPath": source["eventPath"],
        "programPlacementRootInstrumentGuid": source[
            "programPlacementRootInstrumentGuid"
        ],
        "derivedSourceSha256": _canonical_hash(source),
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "signedPcm16LittleEndian",
            "audioDeviceOpened": False,
            "targetOnly": True,
        },
        "capture": {
            "accepted": True,
            "oracleVersion": ENGINE_TRANSIENT_PCM_CAPTURE_ORACLE_VERSION,
            "scheduledSourceGuid": source["sourceGuid"],
            "captureParameterValues": source["captureParameterValues"],
            "audibilityDisposition": "AUDIBLE_TARGET_PCM",
            "allPcmSamplesZero": False,
            "frameCount": 16,
            "pcmPayloadSha256": "5" * 64,
            "peakPcmDbfs": -6.0,
            "playbackStartFrame": 0,
            "playbackEndFrameExclusive": 16,
            "terminationTimingErrorBoundFrames": 256,
            "dspClockAlignmentErrorBoundFrames": 0,
            "independentRenderBitExact": True,
            "independentFreshProcessRenders": capture_workers,
            "writerFrameIndexEqualsParentDspClock": True,
            "timelineAuthoredSilentPrefixPreserved": True,
            "finalWavRelativePath": "captures/source.wav",
            "finalWavSha256": "6" * 64,
            "embeddedSourcePcmEvidence": {
                "accepted": True,
                "encoding": "FSB5_PCM16_LE",
                "frameCount": sample["sampleCount"],
                "sampleRateHz": sample["frequencyHz"],
                "channels": sample["channels"],
                "soundBankIndex": sample["soundBankIndex"],
                "subsoundIndex": sample["subsoundIndex"],
                "encodedPayloadSha256": sample["encodedPayloadSha256"],
                "sampleNameUsed": False,
            },
            "dspClockAlignment": {
                "method": (
                    "TARGET_SCHEDULE_FRAME_EQUALS_MASTER_WRITER_DSP_CLOCK_AT_DISCOVERY_UPDATE"
                ),
                "independentScheduleStartDspClockFrames": [256, 512],
                "channelLocalDspClockAtScheduleObservation": [0, 0],
                "immediateParentDspClockAtScheduleObservation": [256, 512],
                "writerMasterDspClockAtScheduleObservation": [256, 512],
                "writerCropStartFrames": [256, 512],
                "dspBufferFrames": 256,
                "comparedFrameCount": 16,
                "sampleNameUsedForSchedulingSemantics": False,
            },
        },
        "pitchVerification": {
            "accepted": True,
            "captureRpm": source["captureRpm"],
            "probeRpm": source["captureRpm"] * 1.25,
            "captureChannelPitch": 1.0,
            "probeChannelPitch": 1.25,
            "maximumPlaybackRateRatioError": 0.0,
            "mode": "LIVE_RPM_RATIO",
            "updatesWhileVoiceActive": True,
            "sourceBoundChannelObserved": True,
            "freshProcessEvidence": _fresh_engine_worker("pitch", 103),
        },
        "zeroGainVirtualization": {
            "accepted": True,
            "phasePolicy": (
                "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE"
            ),
            "reentryPolicy": (
                "PRESERVE_PRIOR_UNTIL_SOURCE_BOUND_NATURAL_END_AND_SCHEDULE_NEW_ON_REENTRY;"
                "OVERLAP_IF_PRIOR_REMAINS_ALIVE"
            ),
            "maximumPhaseObservationErrorFrames": 0,
            "reentryTargetVoiceCount": 2,
            "priorVoicePresentAtReentry": True,
            "priorVoicePcmPositionAtReentry": 300,
            "newVoicePcmPositionsAtReentry": [0],
            "reentryOutcome": "PRIOR_RETAINED_AND_OVERLAPPED_NEW_VOICE",
            "zeroGainPcmPositionObservations": [100, 200, 200, 200],
            "zeroGainVirtualStateObservations": [False, True, True, True],
            "zeroGainAudibilityObservations": [0.0, 0.0, 0.0, 0.0],
            "runtimeSemantic": {
                "kind": "EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
                "mixerZeroGateAction": (
                    "APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;"
                    "DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING"
                ),
                "ordinaryNonzeroGainSmoothingUnaffected": True,
                "decodePhaseBeforeHold": "CURRENT_ACTIVE_VOICE_PITCH",
                "phaseHoldLatencyWriterFrames": 512,
                "phaseAndDeadlineAdvanceWriterFramesBeforeHold": 512,
                "phaseHoldLatencyFrameDomain": (
                    "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
                ),
                "holdDecodePhaseAfterLatency": True,
                "pauseNaturalEndDeadlineWhileHeld": True,
                "reaudibilizationBeforeDeadline": (
                    "CONTINUE_FROM_HELD_LOGICAL_PHASE"
                ),
                "writerDspBlockFrames": 256,
                "zeroTransition": {
                    "policy": "IMMEDIATE_EXACT_ZERO",
                    "frameDomain": (
                        "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
                    ),
                    "gainInterpolation": "LINEAR_PER_WRITER_FRAME",
                    "gainAtTransitionStart": 1.0,
                    "gainAtExactZero": 0.0,
                    "retainPreZeroGainWriterFrames": 0,
                    "linearFadeWriterFrames": 0,
                    "exactZeroFromWriterFrame": 0,
                    "pitchDuringTransition": "LIVE_CURRENT_RPM_PITCH",
                    "phaseTreatment": "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
                    "restoreCapturePcmPhaseOffsetFrames": 0.0,
                    "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames": 512.0,
                    "positiveGainReturnBeforePhaseHoldPolicy": (
                        "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_"
                        "SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD"
                    ),
                    "subsequentExactZeroCrossingPolicy": (
                        "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
                        "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
                    ),
                    "residualMaximumAbsolutePcmLsb": 0.0,
                    "acceptanceBoundMaximumAbsolutePcmLsb": 1.0,
                },
                "channelGetPositionWhileVirtualIsRuntimeAuthoritative": False,
                "postRestorePcmVerification": {
                    "accepted": True,
                    "comparison": "POST_RESTORE_PHASE_AT_FROZEN_LOGICAL_AGE",
                    "gainErrorDb": 0.0,
                    "differenceBelowReferenceDb": 240.0,
                    "normalizedCorrelation": 1.0,
                    "maximumAbsoluteDifferenceLsb": 0,
                    "bestBaselinePhaseLagFrames": 0,
                    "postRestorePhaseTreatment": (
                        "RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET"
                    ),
                    "restoreCapturePcmPhaseOffsetFrames": 0.0,
                    "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames": 512.0,
                    "fractionalPhaseOnlyAlignment": None,
                    "comparedFrameCount": 1024,
                    "zeroHoldFrames": 1024,
                    "logicalDeadlineExtensionFrames": 512,
                    "phaseAdvanceBeforeHoldFrames": 512,
                    "naturalDeadlineInferredPhaseHoldLatencyWriterFrames": 512,
                    "naturalDeadlineHoldOnsetErrorWriterFrames": 0,
                    "naturalDeadlineHoldOnsetErrorBoundWriterFrames": 256,
                    "zeroOutputGateVerification": {
                        "accepted": True,
                        "method": (
                            "TARGET_ONLY_PCM16_DURING_EXACT_ZERO_CONTROL_HOLD"
                        ),
                        "frameDomain": (
                            "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
                        ),
                        "allPcmSamplesZeroFromFirstWriterFrame": True,
                        "maximumAbsolutePcmLsbPerWriterBlock": [0, 0, 0, 0],
                        "compactTransitionFit": {
                            "pitchOnlyReferencePhaseOffsetWriterFrames": 0,
                            "policy": "IMMEDIATE_EXACT_ZERO",
                            "retainPreZeroGainWriterFrames": 0,
                            "linearFadeWriterFrames": 0,
                            "exactZeroFromWriterFrame": 0,
                            "pitchDuringPassThrough": "LIVE_CURRENT_RPM_PITCH",
                        },
                    },
                    "baselineFreshProcess": _fresh_engine_worker(
                        "zeroResumeRender", 105
                    ),
                    "zeroGapFreshProcess": _fresh_engine_worker(
                        "zeroResumeRender", 106
                    ),
                },
            },
            "briefZeroTransitionVerification": {
                "accepted": True,
                "controlUpdateQuantumWriterFrames": 256,
                "frameDomain": "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
                "runtimeConclusion": {
                    "positiveGainReturnBeforePhaseHoldPolicy": (
                        "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_"
                        "SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD"
                    ),
                    "subsequentExactZeroCrossingPolicy": (
                        "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_"
                        "COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE"
                    ),
                },
                "sequences": [],
                "sampleNameUsedForSemantics": False,
            },
            "freshProcessEvidence": _fresh_engine_worker("zeroGain", 104),
        },
        "dynamicDspVerification": {
            "accepted": True,
            "unattributedControllerCount": 0,
            "runtimeTreatment": "NO_UNATTRIBUTED_RUNTIME_DSP_AUTOMATION",
            "targetOnlyComparisonRequired": False,
        },
        "voicePolicy": {"softwareChannelPriority": 128},
    }
    zero = value["zeroGainVirtualization"]
    post_restore = zero["runtimeSemantic"].pop("postRestorePcmVerification")
    post_restore["pitchOnlyTransitionReference"] = {
        "freshProcess": _fresh_engine_worker("zeroResumeRender", 107)
    }
    zero["postRestorePcmVerification"] = post_restore
    for sequence_index, zero_updates in enumerate((1, 2)):
        positive_offset = zero_updates * 256
        second_zero_offset = positive_offset + 256
        first_positions = [110 + index * 10 for index in range(zero_updates)]
        observations = [
            {
                "label": f"brief-zero-first-zero-{index}",
                "priorVoice": {"pcmPosition": position},
                "targetVoiceCount": 1,
                "writerFrameAfterUpdate": (index + 1) * 256,
            }
            for index, position in enumerate(first_positions)
        ]
        observations.append(
            {
                "label": "brief-zero-recovery-0",
                "priorVoice": {"pcmPosition": 200},
                "targetVoiceCount": 1,
                "writerFrameAfterUpdate": positive_offset + 256,
            }
        )
        observations.extend(
            {
                "label": f"brief-zero-second-zero-{index}",
                "priorVoice": {
                    "pcmPosition": 300 if index == 0 else 400,
                    "isVirtual": index >= 2,
                },
                "targetVoiceCount": 1,
                "writerFrameAfterUpdate": second_zero_offset + (index + 1) * 256,
            }
            for index in range(8)
        )
        zero["briefZeroTransitionVerification"]["sequences"].append(
            {
                "accepted": True,
                "independentRenderBitExact": True,
                "pcmPayloadSha256": ("8" if sequence_index == 0 else "9") * 64,
                "writerDspBlockFrames": 256,
                "zeroUpdatesBeforeRecovery": zero_updates,
                "recoveryUpdatesBeforeSecondZero": 1,
                "secondZeroObservationUpdates": 8,
                "firstZeroToPositiveReturnWriterFrames": positive_offset,
                "positiveReturnToSecondZeroWriterFrames": 256,
                "observations": observations,
                "writerBlockMetricsFromFirstZero": [
                    {
                        "writerFrameOffsetFromFirstZero": offset,
                        "maximumAbsolutePcmLsb": (
                            10 if offset == positive_offset else 0
                        ),
                        "pcmPayloadSha256": "a" * 64,
                    }
                    for offset in range(0, second_zero_offset + 2049, 256)
                ],
                "independentFreshProcessRenders": [
                    _fresh_engine_worker(
                        "briefZeroRecoveryRender", 108 + sequence_index * 2
                    ),
                    _fresh_engine_worker(
                        "briefZeroRecoveryRender", 109 + sequence_index * 2
                    ),
                ],
            }
        )
    value["verificationPayloadSha256"] = _canonical_hash(value)
    return value


def _limiter_verification(program: dict) -> dict:
    mode = program["programMode"]
    pcm = {
        "accepted": True,
        "frameCount": 16,
        "pcmPayloadSha256": "d" * 64,
        "loopStartFrame": 0,
        "loopEndFrameExclusive": 16,
        "allPcmSamplesZero": False,
        "audibilityDisposition": "AUDIBLE_TARGET_PCM",
    }
    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        pcm["adjacentPeriodComparison"] = {"bitExact": True, "snrDb": 300.0}
    elif mode == "PERSISTENT_DECAY_REGION_ONE_SHOT":
        pcm["independentRenderBitExact"] = True
    else:
        pcm["adjacentLoopComparison"] = {"snrDb": 40.0, "gainErrorDb": 0.001}
    evidence = {
        "schema": "ac-fmod-limiter-source-verification-v1",
        "familyId": "fixture",
        "sourceGuid": program["sourceGuid"],
        "eventPath": program["eventPath"],
        "programMode": mode,
        "derivedProgramSha256": _canonical_hash(program),
        "executable": {
            "sha256": "0df569c840f8303f7018f7891085e3a4c22cf93fb19327c6a0b85325cea23fd1"
        },
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "signedPcm16LittleEndian",
            "audioDeviceOpened": False,
        },
        "lifecycle": {
            "accepted": True,
            "contract": "EXACT_AC_OWNER_AND_FMOD_SOURCE_SCHEDULING",
        },
        "pcm": pcm,
    }
    evidence["verificationPayloadSha256"] = _canonical_hash(evidence)
    return evidence


def _turbo_fixture(
    mode: str, *, pitch: bool = True, property_four: bool = False, silent: bool = False
) -> tuple[dict, dict]:
    if mode not in {"timeline", "release-region", "full-domain"}:
        raise ValueError(mode)
    controllers = [
        _controller(
            "boost-volume",
            "turbo-route",
            "boost-param",
            "boost",
            0,
            (
                [(0.0, -42.0, 0.0, 0)]
                if silent
                else [
                    (0.0, -42.0, -0.5, 0),
                    (0.25, 0.0, 0.0, 0),
                    (1.0, -6.0, 0.0, 0),
                ]
            ),
        )
    ]
    source_controller_guids = ["boost-volume"]
    if pitch:
        controllers.append(
            _controller(
                "boost-pitch",
                "turbo-source",
                "boost-param",
                "boost",
                1,
                [(0.0, -0.5, 0.0, 0), (1.0, 0.5, 0.0, 0)],
            )
        )
        source_controller_guids.append("boost-pitch")
    if property_four:
        controllers.append(
            _controller(
                "bov-decay-fade",
                "turbo-route",
                "bov-decay-param",
                "bov_decay",
                4,
                [(0.0, 1.0, 0.0, 0), (10.0, 0.0, 0.0, 0)],
            )
        )
        source_controller_guids.append("bov-decay-fade")
    report = {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "bank": {"sha256": "e" * 64, "fileVersion": 0x50},
        "parameters": [
            {
                "guid": "boost-param",
                "name": "boost",
                "minimum": 0.0,
                "maximum": 1.0,
                "defaultValue": 0.0,
                "seekSpeed": 0.0,
                "seekSpeedDown": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED",
            },
            {
                "guid": "bov-decay-param",
                "name": "bov_decay",
                "minimum": 0.0,
                "maximum": 10.0,
                "defaultValue": 0.0,
                "seekSpeed": 0.0,
                "seekSpeedDown": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED",
            },
        ],
        "instruments": [
            {
                "guid": "turbo-root",
                "kind": "MultiInstrumentNode",
                "sample": None,
                "childInstruments": [
                    {"guid": "turbo-source", "weight": 2.0},
                    {"guid": "other-source", "weight": 1.0},
                ],
                "controllerGuids": [],
                "playlist": {
                    "playMode": "PlaylistPlayMode_SmartRandom",
                    "playModeValue": 2,
                    "selectionMode": "PlaylistSelectionMode_SelectNormal",
                    "selectionModeValue": 1,
                },
                "baseProperties": {
                    "volumeDb": -1.0,
                    "pitchSemitones": 2.0,
                    "routableGuid": "turbo-root-route",
                    "loopCount": 0,
                    "initialSeekPercent": 0.0,
                    "initialSeekPosition": 0.0,
                    "triggerChancePercent": 100.0,
                },
            },
            {
                "guid": "turbo-source",
                "kind": "WaveformInstrumentNode",
                "sample": {
                    "name": "must-never-be-emitted-turbo.wav",
                    "sampleCount": 48000,
                    "frequencyHz": 48000,
                    "channels": 2,
                    "encodedPayloadBytes": 192000,
                    "encodedPayloadSha256": "f" * 64,
                    "waveformResourceGuid": "turbo-resource",
                    "soundBankIndex": 0,
                    "subsoundIndex": 4,
                },
                "childInstruments": [],
                "controllerGuids": source_controller_guids,
                "baseProperties": {
                    "volumeDb": -2.0,
                    "pitchSemitones": -1.0,
                    "routableGuid": "turbo-route",
                    "loopCount": 0,
                    "initialSeekPercent": 0.0,
                    "initialSeekPosition": 0.0,
                    "triggerChancePercent": 100.0,
                    "autoPitchReference": 1.0,
                },
            },
            {
                "guid": "other-source",
                "kind": "WaveformInstrumentNode",
                "sample": {"name": "unrelated.wav"},
                "childInstruments": [],
                "controllerGuids": [],
                "baseProperties": {
                    "volumeDb": 0.0,
                    "pitchSemitones": 0.0,
                    "routableGuid": "other-route",
                    "loopCount": 0,
                    "initialSeekPercent": 0.0,
                    "initialSeekPosition": 0.0,
                    "triggerChancePercent": 100.0,
                },
            },
        ],
        "controllers": controllers,
        "events": [
            {
                "path": "event:/cars/test/turbo",
                "mappingComplete": True,
                "reachableInstrumentGuids": [
                    "turbo-root",
                    "turbo-source",
                    "other-source",
                ],
                "parameterPlacements": (
                    []
                    if mode == "timeline"
                    else [
                        {
                            "instrumentGuid": "turbo-root",
                            "parameterGuid": "boost-param",
                            "parameterName": "boost",
                            "start": 0.0,
                            "end": 0.5 if mode == "release-region" else 1.00001,
                            "includeEnd": True,
                        }
                    ]
                ),
                "timelinePlacements": (
                    [
                        {
                            "instrumentGuid": "turbo-root",
                            "startTime": 0,
                            "length": 48000,
                            "timeLocked": True,
                        }
                    ]
                    if mode == "timeline"
                    else []
                ),
            }
        ],
        "modulators": [],
        "effects": [],
    }
    classification = {
        "sourceGuid": "turbo-source",
        "eventPaths": ["event:/cars/test/turbo"],
        "eventSuffixes": ["turbo"],
        "lifetime": "oneShot",
        "policy": "allowCandidate",
        "role": "TURBO_TRANSIENT_CANDIDATE",
        "candidateManifestRoles": ["BOV", "TURBO_TRANSIENT"],
    }
    return report, classification


def _turbo_verification(source: dict) -> dict:
    silent = source["staticAudibilityDisposition"] == "AUTHORED_CURVE_SILENT_ALL_DOMAIN"
    timeline = source["programMode"] == "TIMELINE_PERIODIC_ONE_SHOT"
    frame_count = (
        round(source["captureIsolation"]["predictedRenderedSourceCycleFrames"])
        if timeline
        else 48000
    )
    prediction_error = (
        abs(
            frame_count
            - source["captureIsolation"]["predictedRenderedSourceCycleFrames"]
        )
        if timeline
        else 0.0
    )
    verified_control_curves = {}
    for control, source_curve in sorted(source["controlGainCurves"].items()):
        capture_value = float(source["captureParameterValues"][control])

        def curve_value(value: float) -> float:
            if value <= source_curve[0][0]:
                return float(source_curve[0][1])
            if value >= source_curve[-1][0]:
                return float(source_curve[-1][1])
            for left, right in zip(source_curve, source_curve[1:]):
                if value <= right[0]:
                    amount = (value - left[0]) / (right[0] - left[0])
                    return left[1] + ((right[1] - left[1]) * amount)
            raise AssertionError("fixture curve interpolation fell through")

        verified_control_curves[control] = {
            "control": control,
            "domain": source["controlParameters"][control]["domain"],
            "captureValue": capture_value,
            "captureRoutedGain": 0.0 if silent else 0.5,
            "captureCurveValue": 0.0 if silent else curve_value(capture_value),
            "curve": copy.deepcopy(source_curve),
            "oracleProbeCount": max(2, len(source_curve)),
            "oracleRestartCount": 0,
            "absoluteLinearGainTolerance": 2.0e-4,
            "maximumObservedAbsoluteLinearGainErrorOutsideTransitions": 0.0,
            "transitionIntervals": [],
            "maximumTransitionDomainWidth": 0.0,
            "float32ControlInputs": True,
            "runtimeMeasurement": (
                "TARGET_CHANNEL_GROUP_ANCESTRY_TIMES_CHANNEL_FADER"
            ),
        }

    evidence = {
        "schema": "ac-fmod-turbo-transient-source-verification-v1",
        "sourceGuid": source["sourceGuid"],
        "eventPath": source["eventPath"],
        "programMode": source["programMode"],
        "programPlacementRootInstrumentGuid": source[
            "programPlacementRootInstrumentGuid"
        ],
        "derivedSourceSha256": _canonical_hash(source),
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "signedPcm16LittleEndian",
            "audioDeviceOpened": False,
            "targetOnly": True,
        },
        "capture": {
            "accepted": True,
            "oracleVersion": TURBO_PCM_CAPTURE_ORACLE_VERSION,
            "captureParameterValues": source["captureParameterValues"],
            "scheduledSourceGuid": source["sourceGuid"],
            "independentRenderBitExact": True,
            "independentRenderRawBitExact": True,
            "independentRenderComparisonMode": (
                "RAW_RENDER_PCM16"
                if timeline
                else (
                    "ALL_ZERO_TARGET_FULL_AUTHORED_DURATION"
                    if silent
                    else "DSP_BLOCK_SCHEDULING_NORMALIZED_PCM16"
                )
            ),
            "independentRenderStartOffsetsFrames": [0, 0],
            "independentRenderStartOffsetDifferenceFrames": 0,
            "independentRenderComparedFrameCount": frame_count,
            "dspSchedulingBlockFrames": 256,
            "removedPrefixSamplesAllZero": True,
            "retainedQuantizedSilentPrefixFrames": (
                [0, 0] if not silent else [frame_count, frame_count]
            ),
            "maximumAudibleSampleLoss": 0,
            "embeddedSourcePcmBoundaryEvidence": {
                "accepted": True,
                "encoding": "FSB5_PCM16_LE",
                "soundBankIndex": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["soundBankIndex"],
                "subsoundIndex": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["subsoundIndex"],
                "encodedPayloadSha256": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["encodedPayloadSha256"],
                "sampleRateHz": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["frequencyHz"],
                "channels": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["channels"],
                "frameCount": source["sourceGeometry"][
                    "sampleTechnicalEvidence"
                ]["sampleCount"],
                "authoredLeadingSilentFrames": 0,
                "authoredTrailingSilentFrames": 0,
                "allAuthoredSamplesZero": False,
                "sampleNameUsed": False,
            },
            "frameCount": frame_count,
            "playbackStartFrame": 0,
            "playbackEndFrameExclusive": frame_count,
            "terminationTimingErrorBoundFrames": 256,
            "writerPipelineLatencyFramesRemoved": 2048,
            "pcmPayloadSha256": "1" * 64,
            "finalWavRelativePath": "captures/turbo-source.wav",
            "finalWavSha256": "2" * 64,
            "allPcmSamplesZero": silent,
            "audibilityDisposition": (
                "AUTHORED_TARGET_SILENT" if silent else "AUDIBLE_TARGET_PCM"
            ),
            "peakPcmDbfs": -240.0 if silent else -3.0,
            "captureMode": (
                "TIME_LOCKED_SOURCE_CYCLE" if timeline else "ONE_SHOT_ROUTED_PCM"
            ),
            **(
                {
                    "loopStartFrame": 0,
                    "loopEndFrameExclusive": frame_count,
                    "programTimelinePeriodFrames": source["timelineGeometry"][
                        "repeatPeriodTicks"
                    ],
                    "sourceCycleBoundaryErrorBoundFrames": 1,
                    "sourcePlaybackMode": (
                        "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT"
                    ),
                    "sourceCycleIsolation": {
                        "accepted": True,
                        "boundarySelection": (
                            "MINIMUM_NORMALIZED_PCM_RECURRENCE_ERROR_AROUND_PARSED_SOURCE_GEOMETRY"
                        ),
                        "parsedPredictedBoundaryFrames": source[
                            "captureIsolation"
                        ]["predictedRenderedSourceCycleFrames"],
                        "selectedBoundaryFrame": frame_count,
                        "predictionErrorFrames": prediction_error,
                        "searchRadiusFrames": 64,
                        "referenceStartFrame": 0,
                        "analysisFrameCount": 512,
                        "normalizedMeanSquareRecurrenceError": 0.1,
                        "recurrenceCorrelation": 0.95,
                        "renderedLookAheadFrames": source[
                            "captureIsolation"
                        ]["minimumRenderedLookAheadFrames"],
                        "requiredMinimumLookAheadFrames": source[
                            "captureIsolation"
                        ]["minimumRenderedLookAheadFrames"],
                        "notTrimmedAtLastNonzeroSample": True,
                        "loopReplacementMaximumErrorLsb": 32,
                        "loopReplacementMaximumErrorDbfs": -60.0,
                        "independentRenderBitExact": True,
                    },
                }
                if timeline
                else {}
            ),
        },
        "curveVerification": {
            "accepted": True,
            "oracleVersion": TURBO_CONTROL_GAIN_ORACLE_VERSION,
            "maximumGainErrorDb": 0.05,
            "maximumPitchErrorCents": 0.5,
            "verifiedControlGainCurves": verified_control_curves,
        },
        "schedulingVerification": {
            "accepted": True,
            "programMode": source["programMode"],
            "activeVoiceExitBehaviorVerified": True,
            "overlapBehaviorVerified": True,
        },
        "voicePolicy": {
            "sourceBoundChannelObserved": True,
            "softwareChannelPriority": 128,
        },
    }
    evidence["verificationPayloadSha256"] = _canonical_hash(evidence)
    return evidence


class TypeZeroCurveTests(unittest.TestCase):
    def test_three_oracle_calibration_segments(self) -> None:
        # Normalized FMOD 1.08.12 outputs measured from target-only Tatuus
        # sources.  This test protects the reverse-engineered exponential shape
        # scale without claiming it is an official FMOD formula.
        cases = (
            (-0.18415698, 0.5, 0.6547866957300882),
            (-0.68982375, 0.5263157894736841, 0.9275355669998985),
            (0.19940375, 0.5526315789473684, 0.3837929636),
        )
        for shape, x, expected in cases:
            actual = evaluate_type0_curve(((0.0, 0.0, shape), (1.0, 1.0, 0.0)), x)
            self.assertAlmostEqual(actual, expected, delta=3.0e-6)

    def test_legacy_negative_infinity_tail_is_silent_and_monotonic(self) -> None:
        self.assertEqual(fmod108_volume_automation_linear(-42.0), 0.0)
        self.assertEqual(fmod108_volume_automation_linear(-80.0), 0.0)
        self.assertAlmostEqual(
            fmod108_volume_automation_linear(-6.0), 10.0 ** (-6.0 / 20.0)
        )
        values = [
            fmod108_volume_automation_linear(value)
            for value in (-42.0, -40.0, -30.0, -20.0, -10.0, 0.0)
        ]
        self.assertEqual(values, sorted(values))


class TypeOneCurveTests(unittest.TestCase):
    def test_two_handle_piecewise_power_oracle_cases(self) -> None:
        cases = (
            # shape, normalized x, oracle-normalized curve value
            (0.5, 0.25, 0.124998272),
            (-1.0, 0.40, 0.496009397),
            (1.0, 0.40, 0.256003222),
            (0.40367743, 0.90, 0.972727055),
        )
        for shape, x, expected in cases:
            actual = evaluate_authored_curve(
                ((0.0, 0.0, shape, 1), (1.0, 1.0, 0.0, 0)), x
            )
            self.assertAlmostEqual(actual, expected, delta=8.0e-5)


class SourceBoundRoutedGainOracleTests(unittest.TestCase):
    def test_binary32_step_is_bounded_as_a_near_vertical_transition(self) -> None:
        from tools.probe_fmod_turbo_transients import (
            _adaptive_empirical_curve,
        )

        def oracle(value: float) -> float:
            return 1.0 if value < 0.5 else 0.25

        curve, transitions, smooth_error = _adaptive_empirical_curve(
            oracle, [0.0, 1.0]
        )

        self.assertEqual(curve[0][0], 0.0)
        self.assertEqual(curve[-1][0], 1.0)
        self.assertEqual(len(transitions), 1)
        self.assertLessEqual(transitions[0]["width"], 1.0 / (1 << 20))
        self.assertGreater(
            transitions[0]["maximumObservedAbsoluteLinearGainError"],
            2.0e-4,
        )
        self.assertLessEqual(smooth_error, 2.0e-4)

    def test_one_dsp_block_writer_offset_normalizes_to_bit_exact_pcm(self) -> None:
        import numpy as np

        from tools.probe_fmod_turbo_transients import (
            _normalize_independent_one_shot_renders,
        )

        authored = np.array(
            [[0, 0], [0, 0], [17, -17], [31, -31], [0, 0], [9, -9]],
            dtype=np.int16,
        )
        first = np.zeros((1024, 2), dtype=np.int16)
        second = np.zeros((1024, 2), dtype=np.int16)
        first[512 : 512 + len(authored)] = authored
        second[768 : 768 + len(authored)] = authored

        normalized, proof = _normalize_independent_one_shot_renders(
            first,
            second,
            predicted_playback_frames=len(authored),
        )

        self.assertTrue(np.array_equal(normalized, authored))
        self.assertTrue(proof["independentRenderBitExact"])
        self.assertFalse(proof["independentRenderRawBitExact"])
        self.assertEqual(
            proof["independentRenderStartOffsetsFrames"], [512, 768]
        )
        self.assertEqual(proof["maximumAudibleSampleLoss"], 0)


class ManifestCurveDerivationTests(unittest.TestCase):
    def test_base_gain_multiple_controllers_and_fades_are_preserved(self) -> None:
        report, classification = _engine_fixture()
        result = derive_manifest_source_curves(report, classification)
        self.assertEqual(result["sourceGuid"], "source")
        self.assertEqual(result["eventPath"], "event:/cars/test/engine_ext")
        self.assertEqual(result["captureRootRpm"], 3000.0)
        self.assertEqual(result["captureThrottle"], 0.0)
        self.assertEqual(result["captureParameterValues"], {"rpms": 3000.0, "throttle": 0.0})
        self.assertEqual(
            [item["rawValue"] for item in result["baseGain"]["sourceAndAncestorRawValues"]],
            [-2.5, -1.5],
        )
        self.assertTrue(result["baseGain"]["bakedByTargetOnlyReferenceCapture"])
        self.assertFalse(result["baseGain"]["applyAgainInManifestGainDb"])
        self.assertEqual(len(result["controllers"]), 4)
        self.assertAlmostEqual(max(point[1] for point in result["rpmCurve"]), 1.0)
        self.assertAlmostEqual(max(point[1] for point in result["gainCurve"]), 1.0)
        self.assertEqual(result["rpmCurve"][0][1], 0.0)
        self.assertEqual(result["rpmCurve"][-1][1], 0.0)
        self.assertLess(result["gainCurve"][-1][1], 0.51)
        serialized = json.dumps(result, sort_keys=True)
        self.assertNotIn("must-never-be-emitted", serialized)
        self.assertFalse(result["fidelity"]["sampleNamesUsed"])

    def test_explicit_capture_controls_are_stable(self) -> None:
        report, classification = _engine_fixture()
        result = derive_manifest_source_curves(
            report,
            classification,
            {"rpms": 2500.0, "throttle": 0.25},
        )
        self.assertEqual(result["captureRootRpm"], 2500.0)
        self.assertEqual(result["captureThrottle"], 0.25)

    def test_quiet_explicit_capture_linearizes_in_normalized_clipped_domain(self) -> None:
        report, classification = _engine_fixture()
        throttle = next(
            item for item in report["controllers"] if item["guid"] == "throttle-source"
        )
        throttle["points"] = [
            {"x": 0.0, "y": -37.5, "shape": 0.5, "type": 0},
            {"x": 0.7, "y": 0.0, "shape": 0.0, "type": 0},
        ]

        result = derive_manifest_source_curves(
            report, classification, {"throttle": 0.0}
        )

        self.assertEqual(result["captureThrottle"], 0.0)
        self.assertLess(result["normalization"]["gainAmplitudeAtCapture"], 0.01)
        self.assertTrue(all(value == 1.0 for _x, value in result["gainCurve"]))
        self.assertLessEqual(
            result["fidelity"]["gainDenseGridMaxObservedLinearError"],
            result["fidelity"]["linearSamplingTolerance"],
        )

    def test_zero_rpm_gain_peak_uses_positive_autopitch_capture_and_post_gain(self) -> None:
        report, classification = _engine_fixture()
        report["events"][0]["parameterPlacements"][0].update(
            {"start": 0.0, "end": 2600.0}
        )
        source = report["instruments"][1]
        source["baseProperties"]["autoPitchReference"] = 1998.0
        source["controllerGuids"] = ["rpm-volume", "throttle-source"]
        report["controllers"] = [
            item
            for item in report["controllers"]
            if item["guid"] in {"throttle-source", "throttle-parent"}
        ] + [
            _controller(
                "rpm-volume",
                "route-source",
                "rpm-param",
                "rpms",
                0,
                [(0.0, 0.0, 0.0, 0), (2600.0, -3.0, 0.0, 0)],
            )
        ]

        result = derive_manifest_source_curves(report, classification)

        self.assertEqual(result["captureRootRpm"], 1998.0)
        self.assertEqual(result["captureParameterValues"]["rpms"], 1998.0)
        self.assertEqual(max(value for _rpm, value in result["rpmCurve"]), 1.0)
        self.assertGreater(result["normalization"]["capturePcmPostGainLinear"], 1.0)
        self.assertLess(result["normalization"]["runtimeProductAtCapture"], 1.0)
        self.assertAlmostEqual(
            result["normalization"]["capturePcmPostGainLinear"]
            * result["normalization"]["runtimeProductAtCapture"],
            1.0,
        )

    def test_transmission_native_radians_per_second_become_shaft_rpm(self) -> None:
        report, classification = _engine_fixture()
        report["events"][0]["path"] = "event:/cars/test/transmission"
        report["parameters"][0].update(
            {
                "guid": "shaft-param",
                "name": "drivetrain_speed",
                "maximum": 350.0,
            }
        )
        report["events"][0]["parameterPlacements"][0].update(
            {
                "parameterGuid": "shaft-param",
                "parameterName": "drivetrain_speed",
                "start": 0.0,
                "end": 350.0,
            }
        )
        source = report["instruments"][1]
        source["baseProperties"]["autoPitchReference"] = 70.0
        source["controllerGuids"] = ["shaft-volume", "throttle-source"]
        report["controllers"] = [
            item
            for item in report["controllers"]
            if item["guid"] in {"throttle-source", "throttle-parent"}
        ] + [
            _controller(
                "shaft-volume",
                "source",
                "shaft-param",
                "drivetrain_speed",
                0,
                [(0.0, -42.0, -0.5, 0), (70.0, 0.0, 0.0, 0), (350.0, -6.0, 0.0, 0)],
            )
        ]
        classification.update(
            {
                "eventPaths": ["event:/cars/test/transmission"],
                "eventSuffixes": ["transmission"],
                "role": "TRANSMISSION",
                "candidateManifestRoles": ["TRANSMISSION"],
            }
        )
        result = derive_manifest_source_curves(report, classification)
        scale = 60.0 / (2.0 * math.pi)
        self.assertAlmostEqual(result["nativeSpeedToRpmScale"], scale)
        self.assertAlmostEqual(result["autoPitchReferenceRpm"], 70.0 * scale)
        self.assertAlmostEqual(result["captureRootRpm"], 70.0 * scale)
        self.assertEqual(result["captureParameterValues"]["drivetrain_speed"], 70.0)
        self.assertAlmostEqual(result["rpmCurve"][-1][0], 350.0 * scale)

    def test_fixed_automatic_geometry_is_baked_not_added_to_runtime_curves(self) -> None:
        report, classification = _engine_fixture()
        report["parameters"].append(
            {
                "guid": "cone-param",
                "name": "Event Cone Angle",
                "minimum": 0.0,
                "maximum": 180.0,
                "defaultValue": 0.0,
                "type": "FMOD_STUDIO_PARAMETER_AUTOMATIC_EVENT_CONE_ANGLE",
            }
        )
        report["instruments"][1]["controllerGuids"].append("cone")
        report["controllers"].append(
            _controller(
                "cone",
                "route-source",
                "cone-param",
                "Event Cone Angle",
                0,
                [(30.0, 0.0, 0.5, 1), (120.0, -6.0, 0.0, 0)],
            )
        )
        result = derive_manifest_source_curves(
            report, classification, {"event cone angle": 60.0}
        )
        self.assertEqual(
            result["captureAutomaticParameterValues"], {"event cone angle": 60.0}
        )
        cone = next(item for item in result["controllers"] if item["controllerGuid"] == "cone")
        self.assertEqual(cone["runtimeTreatment"], "bakedFixedAutomaticAtCapture")
        self.assertEqual(result["fidelity"]["curvePointTypes"], [0, 1])
        self.assertNotIn("event cone angle", result["captureParameterValues"])

    def test_hard_trigger_edges_have_bounded_epsilon_ramps(self) -> None:
        report, classification = _engine_fixture()
        report["instruments"][1]["controllerGuids"] = ["throttle-source"]
        report["controllers"] = [
            item
            for item in report["controllers"]
            if item["guid"] in {"throttle-source", "throttle-parent"}
        ]
        result = derive_manifest_source_curves(report, classification)
        edges = result["fidelity"]["triggerGateApproximation"]["edges"]
        rpm_edges = [item for item in edges if item["parameter"] == "rpms"]
        self.assertEqual([item["edge"] for item in rpm_edges], ["lower", "upper"])
        self.assertTrue(all(item["nativeTransitionWidth"] <= 0.008000001 for item in rpm_edges))
        points = {round(x, 6): gain for x, gain in result["rpmCurve"]}
        self.assertEqual(points[round(1000.0 - 0.008, 6)], 0.0)
        self.assertEqual(points[1000.0], 1.0)
        self.assertEqual(points[5000.0], 1.0)
        self.assertEqual(points[round(5000.0 + 0.008, 6)], 0.0)

    def test_unsupported_semantics_fail_closed_with_code(self) -> None:
        mutations = (
            ("unsupportedPropertyIndex", lambda report: report["controllers"][0].update(propertyIndex=9)),
            ("unsupportedCurvePointType", lambda report: report["controllers"][0]["points"][0].update(type=2)),
            ("unsupportedControllerOwnership", lambda report: report["controllers"][0].update(propertyOwnerGuid="foreign")),
            ("unsupportedControllerParameter", lambda report: report["controllers"][0].update(inputParameterName="boost")),
        )
        for expected, mutate in mutations:
            report, classification = _engine_fixture()
            mutate(report)
            with self.assertRaises(FmodAuthoredCurveError) as raised:
                derive_manifest_source_curves(report, classification)
            self.assertEqual(raised.exception.code, expected)

    def test_property_one_has_hashed_target_only_window_fallback(self) -> None:
        report, classification = _engine_fixture()
        report["instruments"][1]["controllerGuids"].append("rpm-pitch")
        report["controllers"].append(
            _controller(
                "rpm-pitch",
                "source",
                "rpm-param",
                "rpms",
                1,
                [
                    (1000.0, -0.5, 0.0, 0),
                    (3000.0, 0.0, 0.0, 0),
                    (5000.0, 0.25, 0.0, 0),
                ],
            )
        )
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_manifest_source_curves(report, classification)
        self.assertEqual(raised.exception.code, "unsupportedPropertyIndex")

        result = derive_windowed_capture_fallback(report, classification)
        self.assertEqual(result["kind"], "targetOnlyAdaptiveRpmWindows")
        self.assertEqual(result["sourceGuid"], "source")
        self.assertEqual(result["rpmCurve"][0][1], 0.0)
        self.assertAlmostEqual(max(point[1] for point in result["rpmCurve"]), 1.0)
        self.assertIn(3000.0, result["seedRpmValues"])
        self.assertEqual(
            result["captureVerificationContract"]["losslessStorage"]["compressionLevel"],
            8,
        )
        recipe = result["seedCaptureRecipes"][0]
        expected_hash = hashlib.sha256(
            json.dumps(
                recipe["recipePayload"],
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        ).hexdigest()
        self.assertEqual(recipe["captureRecipeSha256"], expected_hash)
        self.assertEqual(
            recipe["recipePayload"]["targetOnlyScheduling"][
                "scheduledSourceGuids"
            ],
            ["source"],
        )
        serialized = json.dumps(result, sort_keys=True)
        self.assertNotIn("must-never-be-emitted", serialized)

    def test_controller_only_rpm_domain_is_valid(self) -> None:
        report, classification = _engine_fixture()
        report["events"][0]["parameterPlacements"] = []
        report["instruments"][1]["controllerGuids"].append("rpm-pitch")
        report["controllers"].append(
            _controller(
                "rpm-pitch",
                "source",
                "rpm-param",
                "rpms",
                1,
                [(1000.0, -0.5, 0.0, 0), (5000.0, 0.25, 0.0, 0)],
            )
        )
        result = derive_windowed_capture_fallback(report, classification)
        self.assertEqual(
            result["manifestGainProjection"]["triggerPlacements"], {"rpms": []}
        )
        self.assertEqual(result["seedRpmValues"][0], 1000.0)

    def test_window_fallback_is_fail_closed_outside_exact_scope(self) -> None:
        report, classification = _engine_fixture()
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_windowed_capture_fallback(report, classification)
        self.assertEqual(raised.exception.code, "windowedFallbackNotRequired")

        report["instruments"][1]["controllerGuids"].append("rpm-pitch")
        report["controllers"].append(
            _controller(
                "rpm-pitch",
                "route-source",
                "rpm-param",
                "rpms",
                1,
                [(1000.0, -0.5, 0.0, 0), (5000.0, 0.25, 0.0, 0)],
            )
        )
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_windowed_capture_fallback(report, classification)
        self.assertEqual(raised.exception.code, "unsupportedPropertyOneOwner")


class CatalogPropertyOneFallbackTests(unittest.TestCase):
    def test_all_five_residual_release_sources_have_bounded_plans(self) -> None:
        root = (
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3"
            / "families"
        )
        if not root.is_dir():
            raise unittest.SkipTest("catalog bank-graph cache is absent")
        expected = {
            "5169c3d1-950b-450b-884d-fbab12cc8cc9",
            "631c5f70-22bb-4a33-93e5-2c7fe87f39d9",
            "c15dec11-78a4-4fc7-97a8-6550949646f1",
            "f2526e5a-9b8b-4359-ad98-ce6d379d3264",
            "f37460b3-8cec-473d-8070-962449d0f764",
        }
        compiled: dict[str, dict] = {}
        for family_id in (
            "4e384d921164da0e687dce51e8753ed41ea2c84f1925c6d2e60eb9195e090a74",
            "bb236cb7a759de852680d5cb76a6c549cc518b0fb92649633bc3f647c0fd93be",
        ):
            report = json.loads((root / f"{family_id}.json").read_text(encoding="utf-8"))
            for row in classify_bank_graph_sources(report)["sources"]:
                if row.get("sourceGuid") in expected:
                    compiled[row["sourceGuid"]] = derive_windowed_capture_fallback(
                        report, row
                    )
        self.assertEqual(set(compiled), expected)
        for result in compiled.values():
            self.assertLessEqual(
                len(result["adaptiveOracleGate"]["initialIntervalsRpm"]), 64
            )
            self.assertEqual(
                len(result["seedRpmValues"]), len(result["seedCaptureRecipes"])
            )
            self.assertFalse(result["fidelity"]["exactnessClaim"])


class TurboTransientDerivationTests(unittest.TestCase):
    def test_timeline_source_selects_audible_capture_and_live_property_one_pitch(self) -> None:
        report, classification = _turbo_fixture("timeline")
        result = derive_manifest_turbo_transient_source(report, classification)

        self.assertEqual(result["programMode"], "TIMELINE_PERIODIC_ONE_SHOT")
        self.assertEqual(result["resolvedManifestRole"], "TURBO_TRANSIENT")
        self.assertGreater(result["captureBoost"], 0.45)
        self.assertEqual(result["timelineGeometry"]["repeatPeriodTicks"], 48000)
        self.assertEqual(
            result["programTriggerTemplate"]["trigger"], "EVENT_TIMELINE_PERIODIC"
        )
        self.assertEqual(result["pitchAutomation"][0]["rawValueToSemitonesScale"], 24.0)
        isolation = result["captureIsolation"]
        self.assertEqual(
            isolation["strategy"],
            "TARGET_ROUTED_SOURCE_CYCLE_AT_NEAREST_UNITY_PITCH",
        )
        self.assertEqual(
            isolation["sourcePlaybackMode"],
            "TIME_LOCKED_REPEAT_SOURCE_CYCLE_UNTIL_TIMELINE_EXIT",
        )
        self.assertGreaterEqual(isolation["selectedPlaybackRate"], 1.0)
        self.assertLessEqual(
            isolation["predictedRenderedSourceCycleFrames"],
            isolation["programTimelinePeriodFrames"]
            - isolation["minimumRenderedLookAheadFrames"],
        )
        self.assertFalse(
            result["runtimeControlSemantics"]["autoPitchFromParameterPlacement"]
        )
        self.assertNotIn("must-never-be-emitted", json.dumps(result, sort_keys=True))

    def test_falling_boost_region_is_bov_and_preserves_playlist(self) -> None:
        report, classification = _turbo_fixture("release-region", pitch=False)
        result = derive_manifest_turbo_transient_source(report, classification)

        self.assertEqual(result["programMode"], "BOOST_RELEASE_REGION_ONE_SHOT")
        self.assertEqual(result["resolvedManifestRole"], "BOV")
        trigger = result["programTriggerTemplate"]
        self.assertEqual(trigger["entryEdges"][0]["direction"], "DECREASING")
        self.assertEqual(
            trigger["initiallyOutsideBehavior"],
            "SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY",
        )
        self.assertEqual(trigger["entryEdges"][0]["value"], 0.5)
        self.assertEqual(trigger["rearmMode"], "AFTER_ANY_GATE_EXIT")
        self.assertEqual(trigger["exitBehavior"], "LET_ACTIVE_VOICES_FINISH")
        self.assertEqual(len(result["selectionPath"]), 1)
        self.assertEqual(
            result["selectionPath"][0]["playlist"]["playMode"],
            "PlaylistPlayMode_SmartRandom",
        )
        self.assertEqual(
            [item["weight"] for item in result["selectionPath"][0]["orderedChildren"]],
            [2.0, 1.0],
        )

    def test_full_domain_parameter_sheet_leaf_is_not_mislabeled_bov(self) -> None:
        report, classification = _turbo_fixture("full-domain", pitch=False)
        result = derive_manifest_turbo_transient_source(report, classification)

        self.assertEqual(
            result["programMode"], "PARAMETER_SHEET_EVENT_START_ONE_SHOT"
        )
        self.assertEqual(result["resolvedManifestRole"], "TURBO_TRANSIENT")
        self.assertTrue(result["parameterRegion"]["coversEntireParameterDomain"])
        self.assertEqual(result["programTriggerTemplate"]["trigger"], "EVENT_START")
        self.assertEqual(
            result["programTriggerTemplate"]["rearmMode"],
            "NONE_WITHOUT_EVENT_RESTART",
        )

    def test_property_four_is_a_gain_axis_not_pitch(self) -> None:
        report, classification = _turbo_fixture(
            "release-region", pitch=False, property_four=True
        )
        result = derive_manifest_turbo_transient_source(report, classification)

        self.assertEqual(result["captureParameterValues"]["bov_decay"], 0.0)
        self.assertIn("bov_decay", result["controlGainCurves"])
        fade = next(item for item in result["controllers"] if item["propertyIndex"] == 4)
        self.assertEqual(fade["runtimeTreatment"], "normalizedLinearGainCurve")
        self.assertEqual(
            result["runtimeControlSemantics"]["propertyFour"],
            "LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH",
        )

    def test_static_silent_leaf_is_retained_for_source_bound_silence_proof(self) -> None:
        report, classification = _turbo_fixture(
            "full-domain", pitch=True, silent=True
        )
        result = derive_manifest_turbo_transient_source(report, classification)

        self.assertEqual(
            result["staticAudibilityDisposition"],
            "AUTHORED_CURVE_SILENT_ALL_DOMAIN",
        )
        self.assertEqual(result["boostGainCurve"], [[0.0, 0.0], [1.0, 0.0]])
        certified = certify_manifest_turbo_transient_source(
            result, _turbo_verification(result)
        )
        self.assertTrue(certified["fidelity"]["exactnessClaim"])
        self.assertEqual(
            certified["sourceVerification"]["capture"]["audibilityDisposition"],
            "AUTHORED_TARGET_SILENT",
        )

    def test_source_bound_hash_and_priority_are_required_for_certification(self) -> None:
        report, classification = _turbo_fixture("timeline")
        source = derive_manifest_turbo_transient_source(report, classification)
        certified = certify_manifest_turbo_transient_source(
            source, _turbo_verification(source)
        )
        self.assertTrue(certified["fidelity"]["exactnessClaim"])
        self.assertEqual(certified["voicePolicy"]["softwareChannelPriority"], 128)

        evidence = _turbo_verification(source)
        evidence["voicePolicy"]["softwareChannelPriority"] = 300
        evidence["verificationPayloadSha256"] = _canonical_hash(
            {key: value for key, value in evidence.items() if key != "verificationPayloadSha256"}
        )
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            certify_manifest_turbo_transient_source(source, evidence)
        self.assertEqual(raised.exception.code, "turboVoicePolicyVerificationFailed")

    def test_unsupported_property_index_fails_closed(self) -> None:
        report, classification = _turbo_fixture("timeline")
        report["controllers"][0]["propertyIndex"] = 3
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_manifest_turbo_transient_source(report, classification)
        self.assertEqual(raised.exception.code, "unsupportedPropertyIndex")


class CatalogTurboTransientTests(unittest.TestCase):
    def test_all_official_turbo_transient_leaves_normalize_to_105_program_roots(self) -> None:
        project = Path(__file__).resolve().parents[1]
        graph_root = project / ".aclib-local" / "bank-graph-audit-v3" / "families"
        classification_path = project / ".aclib-local" / "source-role-classification-v2.json"
        if not graph_root.is_dir() or not classification_path.is_file():
            raise unittest.SkipTest("catalog turbo-transient evidence is absent")
        classification = json.loads(classification_path.read_text(encoding="utf-8"))
        rows = [
            row
            for row in classification["sourceDecisions"]
            if row.get("policy") == "allowCandidate"
            and row.get("role") == "TURBO_TRANSIENT_CANDIDATE"
        ]
        outputs = []
        graphs: dict[str, dict] = {}
        for row in rows:
            family_id = row["familyId"]
            if family_id not in graphs:
                graphs[family_id] = json.loads(
                    (graph_root / f"{family_id}.json").read_text(encoding="utf-8")
                )
            output = derive_manifest_turbo_transient_source(graphs[family_id], row)
            outputs.append((row, output))
            source = next(
                item
                for item in graphs[family_id]["instruments"]
                if item["guid"] == row["sourceGuid"]
            )
            sample_name = str((source.get("sample") or {}).get("name") or "")
            technical = output["sourceGeometry"]["sampleTechnicalEvidence"]
            self.assertNotIn("name", technical)
            self.assertNotIn("sampleName", technical)
            self.assertNotIn("filename", technical)
            # Short authored sample names such as ``turbo`` legitimately occur
            # inside schema and event-path strings.  The release record proves
            # name independence structurally: only immutable resource identity
            # and technical media facts may cross this boundary.
            self.assertEqual(
                set(technical),
                {
                    "channels",
                    "encodedPayloadBytes",
                    "encodedPayloadSha256",
                    "frequencyHz",
                    "sampleCount",
                    "soundBankIndex",
                    "subsoundIndex",
                    "waveformResourceGuid",
                },
            )
            self.assertTrue(sample_name or technical["waveformResourceGuid"])
            self.assertFalse(output["fidelity"]["sampleNamesUsed"])

        programs = {
            (
                row["familyId"],
                output["eventPath"],
                output["programPlacementRootInstrumentGuid"],
            )
            for row, output in outputs
        }
        self.assertEqual(len(rows), 171)
        self.assertEqual(len(graphs), 59)
        self.assertEqual(len(programs), 105)
        self.assertEqual(
            {
                mode: sum(output["programMode"] == mode for _row, output in outputs)
                for mode in {output["programMode"] for _row, output in outputs}
            },
            {
                "BOOST_RELEASE_REGION_ONE_SHOT": 143,
                "TIMELINE_PERIODIC_ONE_SHOT": 25,
                "PARAMETER_SHEET_EVENT_START_ONE_SHOT": 3,
            },
        )
        self.assertEqual(sum(bool(output["pitchAutomation"]) for _row, output in outputs), 29)
        self.assertEqual(
            sum(
                any(item["propertyIndex"] == 4 for item in output["controllers"])
                for _row, output in outputs
            ),
            2,
        )
        silent = [
            (row["familyId"], row["sourceGuid"])
            for row, output in outputs
            if output["staticAudibilityDisposition"]
            == "AUTHORED_CURVE_SILENT_ALL_DOMAIN"
        ]
        self.assertEqual(
            silent,
            [
                (
                    "760535669d40b0fd279e1b20d964ac34c6dfcd51329f73005b312994d94297f0",
                    "937d46eb-b0d8-47d2-afc4-e9bb405f2496",
                )
            ],
        )
        tatuus = next(
            output
            for row, output in outputs
            if row["sourceGuid"] == "dbc12fab-78b7-4f68-a7d1-a30a600f6d2e"
        )
        self.assertGreater(tatuus["captureBoost"], 0.45)
        self.assertLessEqual(
            tatuus["captureIsolation"]["predictedRenderedSourceCycleFrames"],
            tatuus["captureIsolation"]["programTimelinePeriodFrames"]
            - tatuus["captureIsolation"]["minimumRenderedLookAheadFrames"],
        )


class LimiterProgramDerivationTests(unittest.TestCase):
    def test_timeline_program_is_compiled_as_one_persistent_period_loop(self) -> None:
        report, classification = _limiter_fixture("timeline")

        result = derive_manifest_limiter_program(report, classification)

        self.assertEqual(
            result["programMode"], "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT"
        )
        self.assertEqual(result["manifestRole"], "LIMITER")
        self.assertEqual(result["timelinePlacement"]["tickRateHz"], 48000)
        self.assertEqual(result["timelineCapture"]["warmupPeriods"], 3)
        self.assertEqual(result["timelineCapture"]["captureFrames"], 24000)
        self.assertEqual(
            result["timelineCapture"]["loopBoundsExclusiveEndFrames"], [0, 24000]
        )
        self.assertEqual(result["runtimeLifecycle"]["initialHostDecayTimerSeconds"], 10.0)
        self.assertEqual(
            result["runtimeLifecycle"]["inactiveThreshold"]["comparison"],
            "STRICTLY_GREATER_THAN",
        )
        self.assertEqual(
            result["sourceScheduling"]["timelinePeriodicOneShot"],
            "EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER",
        )
        self.assertEqual(len(result["targetCaptureBakedModulators"]), 1)
        self.assertNotIn("must-never-be-emitted", json.dumps(result, sort_keys=True))

    def test_decay_region_one_shot_reentry_overlaps_without_exit_cut(self) -> None:
        report, classification = _limiter_fixture("decay-one-shot")

        result = derive_manifest_limiter_program(report, classification)

        self.assertEqual(result["programMode"], "PERSISTENT_DECAY_REGION_ONE_SHOT")
        self.assertEqual(result["decayPlacement"]["minimum"], 0.0)
        self.assertEqual(result["decayPlacement"]["maximum"], 0.15)
        self.assertTrue(result["decayPlacement"]["includeMaximum"])
        self.assertEqual(
            result["sourceScheduling"]["placementExitBehavior"],
            "LET_ACTIVE_ONE_SHOTS_FINISH",
        )
        self.assertEqual(
            result["sourceScheduling"]["overlapMode"],
            "ALLOW_OVERLAPPING_ONE_SHOT_VOICES",
        )
        self.assertIsNone(result["voicePolicy"]["maximumSimultaneousProgramTracks"])

    def test_decay_region_loop_preserves_phase_on_cut_and_restarts_after_exit(self) -> None:
        report, classification = _limiter_fixture("decay-loop")

        result = derive_manifest_limiter_program(report, classification)

        self.assertEqual(result["programMode"], "PERSISTENT_DECAY_REGION_LOOP")
        self.assertEqual(result["sourceLifetime"], "continuous")
        self.assertEqual(
            result["runtimeLifecycle"]["limiterPulseWhileEventActive"],
            "RESET_DECAY_ONLY_PRESERVE_EVENT_TIMELINE_AND_ACTIVE_SOURCE_PHASE",
        )
        self.assertEqual(
            result["sourceScheduling"]["placementExitBehavior"],
            "STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY",
        )

    def test_unrepresented_modulator_fails_closed(self) -> None:
        report, classification = _limiter_fixture("decay-loop")
        report["modulators"][0]["propertyIndex"] = 1

        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_manifest_limiter_program(report, classification)

        self.assertEqual(raised.exception.code, "unsupportedLimiterModulator")

    def test_source_bound_oracle_evidence_is_the_only_exactness_upgrade(self) -> None:
        report, classification = _limiter_fixture("timeline")
        program = derive_manifest_limiter_program(report, classification)
        self.assertFalse(program["fidelity"]["exactnessClaim"])

        certified = certify_manifest_limiter_program(
            program, _limiter_verification(program)
        )

        self.assertTrue(certified["fidelity"]["exactnessClaim"])
        self.assertTrue(certified["fidelity"]["exactWithinReportedOracleBounds"])
        self.assertEqual(certified["verifiedTargetPcm"]["frameCount"], 16)
        self.assertFalse(program["fidelity"]["exactnessClaim"])

    def test_tampered_limiter_verification_fails_closed(self) -> None:
        report, classification = _limiter_fixture("decay-loop")
        program = derive_manifest_limiter_program(report, classification)
        evidence = _limiter_verification(program)
        evidence["pcm"]["adjacentLoopComparison"]["snrDb"] = 20.0
        evidence["verificationPayloadSha256"] = _canonical_hash(
            {key: value for key, value in evidence.items() if key != "verificationPayloadSha256"}
        )

        with self.assertRaises(FmodAuthoredCurveError) as raised:
            certify_manifest_limiter_program(program, evidence)

        self.assertEqual(raised.exception.code, "limiterPcmVerificationFailed")


class CatalogLimiterProgramTests(unittest.TestCase):
    def test_all_official_limiter_sources_compile_to_three_proven_topologies(self) -> None:
        root = Path(__file__).resolve().parents[1]
        classification_path = root / ".aclib-local" / "source-role-classification-v2.json"
        graph_root = root / ".aclib-local" / "bank-graph-audit-v3" / "families"
        if not classification_path.is_file() or not graph_root.is_dir():
            self.skipTest("local official-car graph/classification audit is unavailable")
        classification = json.loads(classification_path.read_text(encoding="utf-8"))
        rows = [
            row
            for row in classification["sourceDecisions"]
            if row["policy"] == "allowCandidate" and row["role"] == "LIMITER"
        ]
        counts: dict[str, int] = {}
        compiled: dict[str, dict] = {}
        for row in rows:
            graph = json.loads(
                (graph_root / f"{row['familyId']}.json").read_text(encoding="utf-8")
            )
            result = derive_manifest_limiter_program(graph, row)
            compiled[row["sourceGuid"]] = result
            mode = result["programMode"]
            counts[mode] = counts.get(mode, 0) + 1
            self.assertFalse(result["fidelity"]["sampleNamesUsed"])

        self.assertEqual(len(rows), 73)
        self.assertEqual(
            counts,
            {
                "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": 48,
                "PERSISTENT_DECAY_REGION_ONE_SHOT": 7,
                "PERSISTENT_DECAY_REGION_LOOP": 18,
            },
        )
        proof_path = (
            root / ".aclib-local" / "limiter-lifecycle-oracle-v1" / "proof.json"
        )
        if proof_path.is_file():
            proof = json.loads(proof_path.read_text(encoding="utf-8"))
            self.assertEqual(proof["schema"], "ac-fmod-limiter-lifecycle-oracle-v1")
            self.assertEqual(
                proof["acExecutableContract"]["inactivityThresholdConstant"][
                    "float32"
                ],
                10.0,
            )
            self.assertEqual(
                proof["observedRuntimeContract"]["repeatedCutWhileActive"],
                "RESET_DECAY_ONLY_WITHOUT_EVENT_OR_SOURCE_RESTART",
            )
            verifications = {
                item["sourceGuid"]: item for item in proof["sourceVerifications"]
            }
            self.assertEqual(set(verifications), set(compiled))
            self.assertEqual(proof["sourceVerificationCounts"]["verified"], 73)
            self.assertEqual(
                sum(
                    certify_manifest_limiter_program(
                        compiled[source_guid], verifications[source_guid]
                    )["fidelity"]["exactnessClaim"]
                    for source_guid in compiled
                ),
                73,
            )


class OneShotCurveDerivationTests(unittest.TestCase):
    def _certifiable_source(self) -> dict:
        report, classification = _engine_fixture()
        classification["lifetime"] = "oneShot"
        source = report["instruments"][1]
        source["baseProperties"]["loopCount"] = 0
        source["sample"] = {
            "name": "must-never-be-emitted.wav",
            "channels": 2,
            "frequencyHz": 44100,
            "sampleCount": 16,
            "encodedPayloadBytes": 64,
            "encodedPayloadSha256": "a" * 64,
            "waveformResourceGuid": "fixture-waveform-resource",
            "soundBankIndex": 0,
            "subsoundIndex": 1,
        }
        return derive_manifest_one_shot_curves(report, classification)

    def test_source_bound_engine_transient_certification_upgrades_exactness(self) -> None:
        source = self._certifiable_source()
        self.assertFalse(source["fidelity"]["exactnessClaim"])

        certified = certify_manifest_engine_transient_source(
            source, _engine_transient_verification(source)
        )

        self.assertTrue(certified["fidelity"]["exactnessClaim"])
        self.assertEqual(certified["rootRpm"], 3000.0)
        self.assertTrue(certified["pitchTreatment"]["runtimeVarispeed"])
        self.assertEqual(certified["voicePolicy"]["softwareChannelPriority"], 128)
        self.assertEqual(certified["verifiedTargetPcm"]["frameCount"], 16)
        self.assertFalse(source["fidelity"]["exactnessClaim"])

    def test_static_source_bound_pitch_removes_runtime_root_rpm(self) -> None:
        source = self._certifiable_source()
        evidence = _engine_transient_verification(source)
        evidence["pitchVerification"].update(
            {
                "mode": "STATIC_BAKED_PITCH",
                "probeChannelPitch": 1.0,
            }
        )
        zero = evidence["zeroGainVirtualization"]
        zero["runtimeSemantic"]["zeroTransition"]["pitchDuringTransition"] = (
            "AUTHORED_STATIC_BAKED_PITCH"
        )
        zero["postRestorePcmVerification"]["zeroOutputGateVerification"][
            "compactTransitionFit"
        ]["pitchDuringPassThrough"] = "AUTHORED_STATIC_BAKED_PITCH"
        evidence["verificationPayloadSha256"] = _canonical_hash(
            {
                key: value
                for key, value in evidence.items()
                if key != "verificationPayloadSha256"
            }
        )

        certified = certify_manifest_engine_transient_source(source, evidence)

        self.assertIsNone(certified["rootRpm"])
        self.assertFalse(certified["pitchTreatment"]["runtimeVarispeed"])
        self.assertEqual(
            certified["pitchTreatment"]["scale"],
            "1.0;authoredStaticPitchBakedInPcm",
        )

    def test_reused_engine_transient_worker_process_fails_closed(self) -> None:
        source = self._certifiable_source()
        evidence = _engine_transient_verification(source)
        evidence["capture"]["independentFreshProcessRenders"][1][
            "workerProcessId"
        ] = evidence["capture"]["independentFreshProcessRenders"][0][
            "workerProcessId"
        ]
        evidence["verificationPayloadSha256"] = _canonical_hash(
            {
                key: value
                for key, value in evidence.items()
                if key != "verificationPayloadSha256"
            }
        )

        with self.assertRaises(FmodAuthoredCurveError) as raised:
            certify_manifest_engine_transient_source(source, evidence)

        self.assertEqual(
            raised.exception.code, "engineTransientProcessIsolationFailed"
        )

    def test_region_program_live_pitch_and_overlap_contract(self) -> None:
        report, classification = _engine_fixture()
        classification["lifetime"] = "oneShot"
        report["instruments"][1]["baseProperties"]["loopCount"] = 0
        result = derive_manifest_one_shot_curves(report, classification)

        self.assertEqual(result["manifestRole"], "ENGINE_TRANSIENT")
        self.assertEqual(result["programPlacementRootInstrumentGuid"], "source")
        self.assertEqual(result["rootRpm"], 3000.0)
        self.assertEqual(result["captureRootRpm"], 3000.0)
        trigger = result["programTriggerTemplate"]
        self.assertEqual(
            trigger["trigger"], "EVENT_START_ARMED_PARAMETER_REGION_REENTRY"
        )
        self.assertEqual(trigger["armingMode"], "EVENT_START_INSIDE_REQUIRED")
        self.assertEqual(trigger["rearmMode"], "AFTER_ANY_GATE_EXIT")
        self.assertEqual(trigger["overlapMode"], "ALLOW_OVERLAP")
        self.assertEqual(trigger["exitBehavior"], "LET_ACTIVE_VOICES_FINISH")
        self.assertEqual(
            trigger["parameterRegions"][0]["parameterGates"],
            [
                {
                    "control": "ENGINE_RPM",
                    "minimum": 1000.0,
                    "maximum": 5000.0,
                    "includeMinimum": True,
                    "includeMaximum": True,
                }
            ],
        )
        self.assertTrue(result["pitchTreatment"]["runtimeVarispeed"])
        self.assertEqual(
            result["pitchTreatment"]["scale"],
            "currentPresentationEngineRpm/rootRpm",
        )
        self.assertFalse(
            result["pitchTreatment"]["entryEdgeSpecificCaptureVariants"]
        )
        self.assertEqual(result["voicePolicy"]["acGlobalLogicalVoiceCap"], 2048)
        self.assertEqual(
            result["voicePolicy"]["acDefaultSoftwareRealVoiceBudget"], 256
        )
        serialized = json.dumps(result, sort_keys=True)
        self.assertNotIn("must-never-be-emitted", serialized)

    def test_timeline_volume_is_baked_and_not_flattened(self) -> None:
        report, classification = _engine_fixture()
        classification["lifetime"] = "oneShot"
        source = report["instruments"][1]
        source["baseProperties"]["loopCount"] = 0
        source["controllerGuids"].append("timeline-volume")
        report["controllers"].append(
            {
                "guid": "timeline-volume",
                "curveGuid": "timeline-volume",
                "propertyOwnerGuid": "route-source",
                "inputKind": "timeline",
                "inputParameterGuid": None,
                "inputParameterName": None,
                "propertyIndex": 0,
                "points": [
                    {
                        "xRawUInt32": 100,
                        "y": -42.0,
                        "shape": 0.0,
                        "type": 0,
                    },
                    {
                        "xRawUInt32": 200,
                        "y": 0.0,
                        "shape": 0.0,
                        "type": 0,
                    },
                ],
            }
        )
        result = derive_manifest_one_shot_curves(report, classification)
        self.assertEqual(len(result["timelineAutomation"]), 1)
        self.assertEqual(
            result["timelineAutomation"][0]["runtimeTreatment"],
            "bakedDynamicTimelineAutomationInTargetOnlyPcm",
        )
        self.assertNotIn(
            "timeline-volume",
            {item["controllerGuid"] for item in result["controllers"]},
        )

    def test_multiple_placement_roots_fail_closed(self) -> None:
        report, classification = _engine_fixture()
        classification["lifetime"] = "oneShot"
        report["instruments"][1]["baseProperties"]["loopCount"] = 0
        report["events"][0]["parameterPlacements"].append(
            {
                "instrumentGuid": "parent",
                "parameterGuid": "throttle-param",
                "parameterName": "throttle",
                "start": 0.0,
                "end": 0.5,
                "includeEnd": False,
            }
        )
        with self.assertRaises(FmodAuthoredCurveError) as raised:
            derive_manifest_one_shot_curves(report, classification)
        self.assertEqual(raised.exception.code, "unsupportedOneShotProgramTopology")


class CatalogOneShotCurveTests(unittest.TestCase):
    def test_every_allowed_engine_one_shot_has_one_exact_program_root(self) -> None:
        project = Path(__file__).resolve().parents[1]
        graph_root = project / ".aclib-local" / "bank-graph-audit-v3" / "families"
        classification_path = project / ".aclib-local" / "source-role-classification-v2.json"
        if not graph_root.is_dir() or not classification_path.is_file():
            raise unittest.SkipTest("catalog one-shot evidence is absent")
        classification = json.loads(classification_path.read_text(encoding="utf-8"))
        rows = [
            row
            for row in classification["sourceDecisions"]
            if row.get("policy") == "allowCandidate"
            and row.get("lifetime") == "oneShot"
            and set(row.get("eventSuffixes") or ()) & {"engine_int", "engine_ext"}
        ]
        by_family: dict[str, list[dict]] = {}
        for row in rows:
            by_family.setdefault(row["familyId"], []).append(row)

        programs: set[tuple[str, str, str]] = set()
        fixed_geometry_only: list[dict] = []
        for family_id, family_rows in by_family.items():
            graph = json.loads(
                (graph_root / f"{family_id}.json").read_text(encoding="utf-8")
            )
            for row in family_rows:
                result = derive_manifest_one_shot_curves(graph, row)
                programs.add(
                    (
                        family_id,
                        result["eventPath"],
                        result["programPlacementRootInstrumentGuid"],
                    )
                )
                if not result["triggerSemantics"]["runtimeRegions"]:
                    fixed_geometry_only.append(result)
                serialized = json.dumps(result, sort_keys=True)
                source = next(
                    item
                    for item in graph["instruments"]
                    if item.get("guid") == row["sourceGuid"]
                )
                sample_name = str((source.get("sample") or {}).get("name") or "")
                if sample_name:
                    self.assertNotIn(sample_name, serialized)

        self.assertEqual(len(rows), 148)
        self.assertEqual(len(by_family), 35)
        self.assertEqual(len(programs), 105)
        self.assertEqual(len(fixed_geometry_only), 1)
        self.assertEqual(
            fixed_geometry_only[0]["programTriggerTemplate"]["trigger"],
            "EVENT_START",
        )
        self.assertIsNotNone(fixed_geometry_only[0]["fixedGeometryOnlyDisposition"])


class TatuusAuthoredCurveIdentityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.assetto_root = find_assetto_root(None)
        except FileNotFoundError as error:
            raise unittest.SkipTest(str(error))
        cached = (
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3"
            / "families"
            / f"{TATUUS_FAMILY}.json"
        )
        if cached.is_file():
            cls.graph = json.loads(cached.read_text(encoding="utf-8"))
        else:
            bank = (
                cls.assetto_root
                / "content"
                / "cars"
                / "tatuusfa1"
                / "sfx"
                / "tatuusfa1.bank"
            )
            cls.graph = audit_bank_graph(bank, assetto_root=cls.assetto_root)
        cls.classification = classify_bank_graph_sources(cls.graph)
        cls.authoring = audit_shipped_fmod_authoring(cls.assetto_root)

    def test_all_twelve_allowed_guids_compile_and_match_sdk_points(self) -> None:
        authored: dict[str, tuple[dict, dict, dict]] = {}
        for event in self.authoring["events"]:
            for group in event["groups"]:
                if group["manifestRole"] not in {"COAST", "EXHAUST"}:
                    continue
                for instrument in group["instruments"]:
                    guid = instrument["id"].strip("{}").casefold()
                    authored[guid] = (event, group, instrument)
        rows = {
            row["sourceGuid"]: row
            for row in self.classification["sources"]
            if row["policy"] == "allowCandidate"
            and row["lifetime"] == "continuous"
            and set(row["eventSuffixes"]) & {"engine_ext", "engine_int"}
        }
        self.assertEqual(set(rows), set(authored))
        self.assertEqual(len(rows), 12)

        graph_instruments = {item["guid"]: item for item in self.graph["instruments"]}
        graph_controllers = {item["guid"]: item for item in self.graph["controllers"]}
        for guid in sorted(rows):
            event, group, sdk = authored[guid]
            result = derive_manifest_source_curves(self.graph, rows[guid])
            graph_instrument = graph_instruments[guid]
            self.assertAlmostEqual(
                graph_instrument["baseProperties"]["autoPitchReference"], sdk["rootRpm"]
            )
            self.assertAlmostEqual(
                graph_instrument["baseProperties"]["volumeDb"], sdk["baseGainDb"]
            )
            placement = next(
                item
                for item in result["triggerPlacements"]["rpms"]
                if item["instrumentGuid"] == guid
            )
            self.assertAlmostEqual(placement["start"], sdk["regionStartRpm"])
            self.assertAlmostEqual(placement["end"], sdk["regionEndRpm"])
            self.assertAlmostEqual(max(point[1] for point in result["rpmCurve"]), 1.0)
            self.assertAlmostEqual(max(point[1] for point in result["gainCurve"]), 1.0)

            evidence = {item["controllerGuid"]: item for item in result["controllers"]}
            for controller_guid in graph_instrument["controllerGuids"]:
                self.assertIn(controller_guid, evidence)
                self.assertEqual(
                    evidence[controller_guid]["propertyIndex"],
                    graph_controllers[controller_guid]["propertyIndex"],
                )

            # The SDK exposes -infinity as -80 while v0x50 serializes that
            # endpoint as -42.  Other x/y/shape values are identity evidence.
            property_zero = [
                item for item in result["controllers"] if item["propertyIndex"] == 0
            ]
            for parameter, sdk_points in group["gainAutomationDb"].items():
                binary = next(item for item in property_zero if item["parameter"] == parameter)
                expected = [
                    (
                        point["x"],
                        -42.0 if point["y"] <= -79.999 else point["y"],
                        point.get("curveShape", 0.0),
                    )
                    for point in sdk_points
                ]
                actual = [
                    (point["x"], point["rawValue"], point["shape"])
                    for point in binary["points"]
                ]
                self.assertEqual(len(actual), len(expected))
                for left, right in zip(actual, expected):
                    for actual_value, expected_value in zip(left, right):
                        self.assertAlmostEqual(actual_value, expected_value, places=5)

            property_four = [
                item for item in result["controllers"] if item["propertyIndex"] == 4
            ]
            for sdk_points in sdk.get("rpmFades", {}).values():
                binary = next(
                    item
                    for item in property_four
                    if len(item["points"]) == len(sdk_points)
                    and all(
                        abs(left["x"] - right["x"]) <= 1.0e-4
                        for left, right in zip(item["points"], sdk_points)
                    )
                )
                expected = [
                    (point["x"], point["y"], point.get("curveShape", 0.0))
                    for point in sdk_points
                ]
                actual = [
                    (point["x"], point["rawValue"], point["shape"])
                    for point in binary["points"]
                ]
                for left, right in zip(actual, expected):
                    for actual_value, expected_value in zip(left, right):
                        self.assertAlmostEqual(actual_value, expected_value, places=5)

            emitted = json.dumps(result, sort_keys=True)
            self.assertNotIn(sdk["audio"]["path"], emitted)

    def test_tatuus_transmission_uses_equivalent_shaft_rpm(self) -> None:
        row = next(
            item for item in self.classification["sources"] if item["role"] == "TRANSMISSION"
        )
        result = derive_manifest_source_curves(self.graph, row)
        scale = 60.0 / (2.0 * math.pi)
        self.assertEqual(result["nativeSpeedParameter"], "drivetrain_speed")
        self.assertAlmostEqual(result["autoPitchReferenceRpm"], 70.0 * scale, places=6)
        self.assertAlmostEqual(result["captureParameterValues"]["drivetrain_speed"], 100.0)
        self.assertAlmostEqual(result["captureRootRpm"], 100.0 * scale, places=6)


if __name__ == "__main__":
    unittest.main()
