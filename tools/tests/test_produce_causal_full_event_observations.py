from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest

from tools.profile_generation.causal_full_event_resource_proof import (
    DSP_BUFFER_FRAMES,
    canonical_json_bytes,
)
from tools.profile_generation.produce_causal_full_event_observations import (
    CAMERA_STOP_MODE,
    CausalObservationProducerError,
    NATIVE_TRACE_SCHEMA,
    _group_scenario_spec,
    _engine_source_probe_spec,
    _self_hashed,
    _session_scenario_spec,
    _validate_native_trace,
    produce_causal_full_event_observations,
)
from tools.tests.test_causal_full_event_resource_proof import (
    PERSPECTIVES,
    PROGRAM_MODES,
    REPOSITORY_ROOT,
    _attach_source_binding_oracles,
    _camera_tail_scenario,
    _plan,
    _scenario,
)


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_sha256(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _camera_evidence(scenario: dict, spec: dict) -> dict:
    switch_host_frame = spec["cameraSwitch"]["hostFrame"]
    switch_dsp_frame = (
        (switch_host_frame + DSP_BUFFER_FRAMES - 1)
        // DSP_BUFFER_FRAMES
        * DSP_BUFFER_FRAMES
    )
    old_perspective = spec["cameraSwitch"]["fromPerspective"]
    new_perspective = spec["cameraSwitch"]["toPerspective"]
    ordinal_by_perspective = {"cabin": 0, "exterior": 1}
    old_ordinal = ordinal_by_perspective[old_perspective]
    new_ordinal = ordinal_by_perspective[new_perspective]
    old_frames: list[int] = []
    overlap_frames: list[int] = []
    drain_frame = None
    for snapshot in scenario["snapshots"]:
        frame = snapshot["afterDspBlockStartFrame"]
        if frame < switch_dsp_frame:
            continue
        active = {
            voice.get("activationPerspective")
            for voice in snapshot["voices"]
            if voice["kind"] == "engineContinuous"
        }
        if old_perspective in active:
            old_frames.append(frame)
            if new_perspective in active:
                overlap_frames.append(frame)
        elif old_frames and drain_frame is None:
            drain_frame = frame

    return {
        "oldEngineStopMode": CAMERA_STOP_MODE,
        "oldEngineStopHostFrame": switch_host_frame,
        "oldEngineStopDspFrame": switch_dsp_frame,
        "oldEngineTailObservedAfterStop": bool(old_frames),
        "oldEngineTailDspFrames": old_frames,
        "newAndOldEngineOverlapObserved": bool(overlap_frames),
        "newAndOldEngineOverlapDspFrames": overlap_frames,
        "oldEngineTailDrainedNaturally": drain_frame is not None,
        "oldEngineTailDrainDspFrame": drain_frame,
        "persistentEffectInstanceTokensStable": True,
        "oldEngineEventOrdinal": old_ordinal,
        "newEngineEventOrdinal": new_ordinal,
        "oldEngineEventInstanceToken": 100 + old_ordinal,
        "newEngineEventInstanceToken": 100 + new_ordinal,
        "switchStartedEventOrdinals": [new_ordinal],
        "switchStoppedEventOrdinals": [old_ordinal],
        "oldEngineStopAppliedToExactInstance": True,
        "newEngineStartAppliedToExactInstance": True,
    }


def _native_trace(plan: dict, request: dict) -> dict:
    group_entries = {
        _group_scenario_spec(entry)["id"]: entry
        for entry in request["hostControlReachability"]["groups"]
    }
    session_entries = {
        _session_scenario_spec(entry)["id"]: entry
        for entry in request["hostControlReachability"]["sessionContexts"]
    }
    scenarios = []
    for spec in request["scenarios"]:
        if spec["trajectoryKind"] == "camera-switch-tail":
            scenario = _camera_tail_scenario(
                plan,
                spec["perspective"],
                spec["programMode"],
            )
            scenario["cameraSwitchEvidence"] = _camera_evidence(scenario, spec)
        elif spec["id"] in group_entries:
            scenario = _scenario(plan, group_entries[spec["id"]])
        else:
            scenario = _scenario(plan, session_entries[spec["id"]])
        scenario.update(
            {
                "id": spec["id"],
                "perspective": spec["perspective"],
                "programMode": spec["programMode"],
                "trajectoryKind": spec["trajectoryKind"],
                "initialHostPhaseFrames": spec["initialHostPhaseFrames"],
                "scenarioRequestSha256": spec["scenarioRequestSha256"],
                "originalBankEvidence": {
                    "bankPath": request["bankPath"],
                    "bankSha256": request["bankSha256"],
                    "graphSha256": request["graphSha256"],
                    "realizationSha256": request["realizationSha256"],
                    "eventInstances": "originalBankEventDescriptions",
                    "channelEnumeration": (
                        "FMOD_System_GetChannelsPlaying+perChannelVirtualState"
                    ),
                    "sourceBinding": (
                        "callbackSoundIdentity+eventInstanceChannelGroup+"
                        "sourceSoloAuthoredBindingOracle"
                    ),
                    "diagnosticNamesUsedForClassification": False,
                    "appliedHostPathSha256": _canonical_sha256(spec["hostPath"]),
                    "appliedFiniteTriggerRecordsSha256": _canonical_sha256(
                        spec["finiteTriggerRecords"]
                    ),
                },
            }
        )
        scenarios.append(scenario)
    source_binding_oracles = _attach_source_binding_oracles(scenarios)
    trace = {
        "schema": NATIVE_TRACE_SCHEMA,
        "status": "PASS",
        "requestSha256": request["requestSha256"],
        "atlasFamilyId": request["atlasFamilyId"],
        "planSha256": request["planSha256"],
        "bankPath": request["bankPath"],
        "bankSha256": request["bankSha256"],
        "graphSha256": request["graphSha256"],
        "realizationSha256": request["realizationSha256"],
        "sourceBankKind": "originalAssettoCarBank",
        "evidenceKind": "nativeOriginalBankFmodNrtSession",
        "syntheticEvidence": False,
        "nativeRuntime": {
            "architecture": "x86_64",
            "outputMode": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
            "sampleRateHz": 48_000,
            "dspBufferFrames": 256,
            "studioLogicalChannelCap": 2_048,
            "softwareRealChannelBudget": 256,
            "coreLibrarySha256": "a" * 64,
            "studioLibrarySha256": "b" * 64,
        },
        "sourceBindingOraclesBySha256": source_binding_oracles,
        "scenarios": scenarios,
    }

    return _self_hashed(trace, "traceSha256")


class CausalObservationProducerTest(unittest.TestCase):
    def test_engine_source_probe_sweeps_every_mode_node_at_settled_dsp_boundaries(self) -> None:
        plan = _plan()
        for perspective in PERSPECTIVES:
            plan["perspectives"][perspective]["nodes"] = [
                {"parameters": {"rpms": rpm, "throttle": throttle}}
                for rpm in (1_000.0, 2_000.0)
                for throttle in (0.0, 0.5, 1.0)
            ]
        load = _engine_source_probe_spec(plan, "cabin", "LOAD")
        both = _engine_source_probe_spec(plan, "cabin", "BOTH")

        self.assertIsNotNone(load)
        self.assertIsNotNone(both)
        assert load is not None and both is not None
        self.assertEqual(load["trajectoryKind"], "source-audibility-probe")
        self.assertEqual(len(load["engineProbeNodes"]), 2)
        self.assertEqual(len(both["engineProbeNodes"]), 6)
        self.assertTrue(
            all(
                node["parameters"]["throttle"] == 1.0
                for node in load["engineProbeNodes"]
            )
        )
        self.assertEqual(
            [state["hostFrame"] for state in load["hostPath"]],
            [0, 512],
        )

    def test_native_original_bank_trace_is_proven_before_materialization(self) -> None:
        bank_bytes = b"fixture-original-bank"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bank = root / "fixture.bank"
            graph = root / "fixture-graph.json"
            realization = root / "fixture-realization.json"
            bank.write_bytes(bank_bytes)
            graph.write_text("{}\n", encoding="utf-8")
            realization.write_text("{}\n", encoding="utf-8")
            plan = _plan()
            plan["bankSha256"] = _sha256_bytes(bank_bytes)

            observations = produce_causal_full_event_observations(
                plan,
                bank_path=bank,
                graph_path=graph,
                realization_path=realization,
                implementation_source_root=REPOSITORY_ROOT,
                native_observer=lambda request: _native_trace(plan, dict(request)),
            )

        self.assertEqual(
            observations["producer"]["schema"],
            "byd-causal-observation-producer-v1",
        )
        self.assertIs(observations["producer"]["syntheticEvidenceAccepted"], False)
        self.assertEqual(
            {
                (scenario["perspective"], scenario["programMode"])
                for scenario in observations["scenarios"]
                if scenario["trajectoryKind"] == "camera-switch-tail"
            },
            {
                (perspective, mode)
                for perspective in PERSPECTIVES
                for mode in PROGRAM_MODES
            },
        )

    def test_trace_marked_synthetic_cannot_cross_native_boundary(self) -> None:
        request = {
            "requestSha256": "a" * 64,
            "atlasFamilyId": "fixture",
            "planSha256": "b" * 64,
            "bankPath": "/fixture.bank",
            "bankSha256": "c" * 64,
            "graphSha256": "d" * 64,
            "realizationSha256": "e" * 64,
            "scenarios": [],
        }
        trace = {
            "schema": NATIVE_TRACE_SCHEMA,
            "status": "PASS",
            "requestSha256": request["requestSha256"],
            "atlasFamilyId": request["atlasFamilyId"],
            "planSha256": request["planSha256"],
            "bankPath": request["bankPath"],
            "bankSha256": request["bankSha256"],
            "graphSha256": request["graphSha256"],
            "realizationSha256": request["realizationSha256"],
            "sourceBankKind": "originalAssettoCarBank",
            "evidenceKind": "nativeOriginalBankFmodNrtSession",
            "syntheticEvidence": True,
            "nativeRuntime": {},
            "scenarios": [],
        }
        trace = _self_hashed(trace, "traceSha256")

        with self.assertRaisesRegex(
            CausalObservationProducerError,
            "not a PASS for the exact original bank request",
        ):
            _validate_native_trace(trace, request)

    def test_tampered_scenario_request_hash_is_rejected(self) -> None:
        request = {
            "requestSha256": "a" * 64,
            "atlasFamilyId": "fixture",
            "planSha256": "b" * 64,
            "bankPath": "/fixture.bank",
            "bankSha256": "c" * 64,
            "graphSha256": "d" * 64,
            "realizationSha256": "e" * 64,
            "scenarios": [
                {
                    "id": "fixture",
                    "perspective": "cabin",
                    "programMode": "LOAD",
                    "trajectoryKind": "host-control-peak",
                    "initialHostPhaseFrames": 0,
                    "scenarioRequestSha256": "f" * 64,
                    "hostPath": [],
                    "finiteTriggerRecords": [],
                }
            ],
        }
        observed = {
            "id": "fixture",
            "perspective": "cabin",
            "programMode": "LOAD",
            "trajectoryKind": "host-control-peak",
            "initialHostPhaseFrames": 0,
            "scenarioRequestSha256": "0" * 64,
        }
        native_trace = {
            "schema": NATIVE_TRACE_SCHEMA,
            "status": "PASS",
            "requestSha256": request["requestSha256"],
            "atlasFamilyId": request["atlasFamilyId"],
            "planSha256": request["planSha256"],
            "bankPath": request["bankPath"],
            "bankSha256": request["bankSha256"],
            "graphSha256": request["graphSha256"],
            "realizationSha256": request["realizationSha256"],
            "sourceBankKind": "originalAssettoCarBank",
            "evidenceKind": "nativeOriginalBankFmodNrtSession",
            "syntheticEvidence": False,
            "nativeRuntime": {
                "architecture": "x86_64",
                "outputMode": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
                "sampleRateHz": 48_000,
                "dspBufferFrames": 256,
                "studioLogicalChannelCap": 2_048,
                "softwareRealChannelBudget": 256,
                "coreLibrarySha256": "a" * 64,
                "studioLibrarySha256": "b" * 64,
            },
            "sourceBindingOraclesBySha256": {},
            "scenarios": [observed],
        }
        oracle_evidence = {
            "schema": "byd-original-bank-source-solo-binding-oracle-v1",
            "originalBankSha256": "c" * 64,
            "graphSha256": "d" * 64,
            "eventPath": "event:/fixture/engine_cabin",
            "sourceGuid": "00000000-0000-0000-0000-000000000001",
            "authoredBindingKey": None,
            "activationPerspective": "cabin",
            "callbackSoundIdentity": "fixture",
            "parameters": {},
            "mutedReachableWaveformGuids": [],
            "disabledFullyMutedParentGuids": [],
            "derivativeBankSha256": "c" * 64,
            "derivativeDifferingByteOffsets": [],
            "scheduledSoundIdentities": ["fixture"],
            "originalBankUnchangedAfterProbe": True,
        }
        native_trace["sourceBindingOraclesBySha256"][
            _canonical_sha256(oracle_evidence)
        ] = oracle_evidence
        native_trace = _self_hashed(native_trace, "traceSha256")

        with self.assertRaisesRegex(CausalObservationProducerError, "identity differs"):
            _validate_native_trace(native_trace, request)


if __name__ == "__main__":
    unittest.main()
