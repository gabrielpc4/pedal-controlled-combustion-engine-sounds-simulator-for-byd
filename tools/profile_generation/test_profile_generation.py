from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from causal_full_event_resource_proof import (
    apply_causal_runtime_resource_update,
    canonical_json_bytes,
    effect_node_key,
)
from generate_android_profile_recipe import (
    ProfileRecipeError,
    _apply_parameter_placement_lifecycle_evidence,
    _classify_finite_engine_program_role,
    _event_instance_ownership,
    _effect_perspective,
    _host_event_ownership_evidence,
    _placement_entry_contract,
    _playlist_selection_runtime_contract,
    _semantic_lifecycle_contract,
    index_graph,
    selected_engine_layers,
)
from generate_full_event_atlas_recipe import (
    ATLAS_PACK_REPORT_SCHEMA,
    ATLAS_PLAN_SCHEMA,
    ATLAS_REALIZATION_SCHEMA,
    ATLAS_RUNTIME_SCHEMA,
    ENGINE_MODE_PROGRAM_SCHEMA,
    _validate_exact_engine_event_role_variant,
    build_atlas_plan,
    build_runtime_index_template,
)
from pack_full_event_atlas import _engine_transition_shard_proof, pack_atlas
from batch_generate_full_event_atlases import (
    _compact_effect_node_shard_map,
    _promote_runtime_release_contract,
)
from playlist_selection import playlist_seed, select_playlist_member
from preflight_full_event_effect_selection import (
    PreflightError,
    _effect_source_tasks,
    _preflight_candidate_snapshot_scenario,
    _select_and_isolate,
)
from realize_nrt_recipe import NrtRecipeError, _canonical_wav, _sha256
from refine_full_event_atlas import (
    _apply_host_mix_contract,
    _bilinear_corners,
    _channel_snapshot_scenario,
    _effective_source_selection_capture,
    _finite_effect_multilinear_corners,
    _exact_zero_gain_curve_evidence,
    _loop_sample,
    _observed_effect_contributions,
    _render_isolated_effect_node,
    _source_selection_capture_contract,
    phase_invariant_metrics,
)


def _layer(identifier: str, role: str) -> dict:
    return {
        "id": identifier,
        "sourceGuid": identifier,
        "assetName": f"{identifier}.wav",
        "role": role,
        "startRpm": 800.0,
        "endRpm": 2_000.0,
        "autoPitchRootRpm": 1_200.0,
        "basePitchSemitones": 0.0,
        "baseGainDb": 0.0,
        "applyIdleGainBoost": False,
        "projectionEvidence": {
            "controllers": [
                {
                    "parameter": "rpms",
                    "points": [
                        {"x": 800.0, "rawValue": 0.0},
                        {"x": 2_000.0, "rawValue": 1.0},
                    ],
                },
                {
                    "parameter": "throttle",
                    "points": [
                        {"x": 0.25, "rawValue": 0.0},
                        {"x": 0.75, "rawValue": 1.0},
                    ],
                },
            ],
            "triggerPlacements": {
                "rpms": [{"start": 800.0, "end": 2_000.0}],
            },
            "normalization": {},
        },
        "runtimeParameters": {"rpms": 800.0, "throttle": 0.0},
        "hostParameterBindings": [
            {"parameter": "rpms", "source": "EngineSimulation.rpm"},
            {"parameter": "throttle", "source": "EngineSimulation.throttle"},
        ],
        "continuousParameterMembership": None,
        "schedulingGroup": {
            "composition": "simultaneousLayer",
            "groupId": f"layer:{identifier}",
            "members": [
                {
                    "sourceGuid": identifier,
                    "authoredOrder": 0,
                    "weight": 1.0,
                    "triggerChancePercent": None,
                }
            ],
            "complete": True,
            "incompleteReason": None,
        },
    }


def _finite_role_fixture(
    *,
    points: list[tuple[float, float]] | None,
    placement: tuple[float, float, bool] | None = None,
) -> tuple:
    controller_guids = ["throttle-controller"] if points is not None else []
    graph = {
        "instruments": [
            {
                "guid": "finite-source",
                "controllerGuids": controller_guids,
                "childInstruments": [],
                "baseProperties": {
                    "routableGuid": "finite-source-route",
                },
            }
        ],
        "controllers": (
            [
                {
                    "guid": "throttle-controller",
                    "inputKind": "parameter",
                    "inputParameterName": "throttle",
                    "inputParameterGuid": "throttle-parameter",
                    "propertyOwnerGuid": "finite-source-route",
                    "propertyIndex": 0,
                    "points": [
                        {"x": x, "y": y, "shape": 0.0, "type": 0}
                        for x, y in points
                    ],
                }
            ]
            if points is not None
            else []
        ),
        "parameters": [
            {
                "guid": "throttle-parameter",
                "name": "throttle",
                "minimum": 0.0,
                "maximum": 1.0,
                "defaultValue": 0.0,
                "type": "GAME_CONTROLLED",
            }
        ],
        "modulators": [],
    }
    indexed = index_graph(graph)
    event = {
        "path": "event:/cars/test/engine_int",
        "parameterLayoutGuids": ["throttle-parameter"],
    }
    placements = (
        [
            {
                "instrumentGuid": "finite-source",
                "parameterGuid": "throttle-parameter",
                "layoutGuid": "throttle-layout",
                "start": placement[0],
                "end": placement[1],
                "includeEnd": placement[2],
            }
        ]
        if placement is not None
        else []
    )

    return (
        indexed,
        event,
        [("finite-source", indexed.instruments["finite-source"])],
        [indexed.controllers[guid] for guid in controller_guids],
        placements,
    )


def _recipe() -> dict:
    load = _layer("load", "LOAD")
    coast = _layer("coast", "COAST")
    idle = _layer("idle", "IDLE")
    limiter = _layer("limiter", "LIMITER")
    base_layers = [idle, load, coast, limiter]
    cabin_layers = [
        {**layer, "sourceGuid": f"cabin-{layer['sourceGuid']}"}
        for layer in base_layers
    ]
    exterior_layers = [
        {**layer, "sourceGuid": f"exterior-{layer['sourceGuid']}"}
        for layer in base_layers
    ]
    bindings = [
        {
            "eventPath": event_path,
            "sourceGuid": layer["sourceGuid"],
            "assetName": f"{perspective}-{layer['id']}.wav",
            "captureMode": "targetOnlyFmodNrt",
            "runtimeMapping": {"kind": "engineLayer"},
        }
        for perspective, event_path, layers in (
            ("cabin", "event:/cars/test/engine_int", cabin_layers),
            ("exterior", "event:/cars/test/engine_ext", exterior_layers),
        )
        for layer in layers
    ]
    return {
        "recipeSha256": "1" * 64,
        "bank": {"sha256": "2" * 64},
        "programContract": {},
        "programs": {
            "cabin": {
                "eventPath": "event:/cars/test/engine_int",
                "layers": cabin_layers,
                "effects": [],
            },
            "exterior": {
                "eventPath": "event:/cars/test/engine_ext",
                "layers": exterior_layers,
                "effects": [],
            },
        },
        "sourceConservationAudit": {
            "exactGuidSetEquality": True,
            "coreReachableSourceGuids": [
                layer["sourceGuid"]
                for layer in cabin_layers + exterior_layers
            ],
            "coreEventBindings": bindings,
        },
        "extraction": {
            "sources": [
                {
                    "assetName": item["assetName"],
                    "sourceGuid": item["sourceGuid"],
                    "lifetime": "continuous",
                    "primaryCapture": {
                        "parameters": {},
                        "durationFrames": 96_000,
                    },
                    "diagnosticNameNotUsedForClassification": item["sourceGuid"],
                }
                for item in bindings
            ]
        },
    }


class ProgramContractTests(unittest.TestCase):
    def test_endpoint_modes_force_every_included_layer(self) -> None:
        recipe = _recipe()
        load = dict(selected_engine_layers(recipe, "cabin", "LOAD", 0.17))
        coast = dict(selected_engine_layers(recipe, "cabin", "COAST", 0.93))
        both = dict(selected_engine_layers(recipe, "cabin", "BOTH", 0.37))

        self.assertEqual(set(load), {"idle", "load", "limiter"})
        self.assertEqual(set(load.values()), {1.0})
        self.assertEqual(set(coast), {"idle", "coast"})
        self.assertEqual(set(coast.values()), {0.0})
        self.assertEqual(set(both), {"idle", "load", "coast", "limiter"})
        self.assertEqual(set(both.values()), {0.37})

    def test_finite_engine_role_uses_exact_binding_and_interior_curve_direction(self) -> None:
        indexed, event, chain, controllers, placements = _finite_role_fixture(
            points=[(0.0, 0.0), (0.5, -12.0), (1.0, -42.0)],
            placement=(0.2, 0.4, False),
        )
        binding_key = "binding:" + "a" * 64

        role, evidence = _classify_finite_engine_program_role(
            indexed=indexed,
            event=event,
            source_guid="finite-source",
            chain=chain,
            controllers=controllers,
            throttle_placements=placements,
            authored_binding_key=binding_key,
            scheduling_group_id="layer:finite-source",
        )

        self.assertEqual(role, "COAST")
        self.assertEqual(
            evidence["classificationKind"],
            "monotonicDecreasingAuthoredRouteGain",
        )
        self.assertEqual(evidence["bindingIdentity"]["authoredBindingKey"], binding_key)
        self.assertAlmostEqual(
            evidence["placementProbe"]["interiorProbeValue"], 0.3
        )
        self.assertTrue(
            any(abs(value - 0.3) < 1.0e-9 for value in evidence["probeValues"])
        )
        self.assertFalse(evidence["classificationUsesDiagnosticName"])

    def test_finite_engine_role_keeps_mixed_and_invariant_texture_unaffected(self) -> None:
        cases = (
            (
                [(0.0, -42.0), (0.5, 0.0), (1.0, -42.0)],
                (0.2, 0.8, False),
                "nonMonotonicOrConflictingAuthoredThrottleTexture",
            ),
            (
                [(0.0, -6.0), (0.5, -6.0), (1.0, -6.0)],
                (0.2, 0.4, False),
                "endpointInvariantOrInteriorOnlyAuthoredTexture",
            ),
        )
        for index, (points, placement, expected_kind) in enumerate(cases):
            with self.subTest(expected_kind=expected_kind):
                indexed, event, chain, controllers, placements = _finite_role_fixture(
                    points=points,
                    placement=placement,
                )
                role, evidence = _classify_finite_engine_program_role(
                    indexed=indexed,
                    event=event,
                    source_guid="finite-source",
                    chain=chain,
                    controllers=controllers,
                    throttle_placements=placements,
                    authored_binding_key="binding:" + str(index + 1) * 64,
                    scheduling_group_id="layer:finite-source",
                )

                self.assertEqual(role, "UNAFFECTED")
                self.assertEqual(evidence["classificationKind"], expected_kind)
                self.assertEqual(evidence["gainTrimDisposition"], "unaffected")

    def test_finite_engine_role_uses_exact_endpoint_placement_without_controller(self) -> None:
        cases = (
            ((0.0, 0.2, False), "COAST", "releaseOnlyAuthoredPlacement"),
            ((0.8, 1.0, True), "LOAD", "fullLoadOnlyAuthoredPlacement"),
        )
        for index, (placement, expected_role, expected_kind) in enumerate(cases):
            with self.subTest(role=expected_role):
                indexed, event, chain, controllers, placements = _finite_role_fixture(
                    points=None,
                    placement=placement,
                )
                role, evidence = _classify_finite_engine_program_role(
                    indexed=indexed,
                    event=event,
                    source_guid="finite-source",
                    chain=chain,
                    controllers=controllers,
                    throttle_placements=placements,
                    authored_binding_key="binding:" + str(index + 3) * 64,
                    scheduling_group_id="layer:finite-source",
                )

                self.assertEqual(role, expected_role)
                self.assertEqual(evidence["classificationKind"], expected_kind)

    def test_finite_engine_role_rejects_placement_from_another_parameter_guid(self) -> None:
        indexed, event, chain, controllers, placements = _finite_role_fixture(
            points=None,
            placement=(0.0, 0.2, False),
        )
        placements[0]["parameterGuid"] = "other-parameter"

        with self.assertRaisesRegex(
            ProfileRecipeError, "placements target other parameter GUIDs"
        ):
            _classify_finite_engine_program_role(
                indexed=indexed,
                event=event,
                source_guid="finite-source",
                chain=chain,
                controllers=controllers,
                throttle_placements=placements,
                authored_binding_key="binding:" + "f" * 64,
                scheduling_group_id="layer:finite-source",
            )

    def test_atlas_variant_requires_role_evidence_for_the_same_authored_binding(self) -> None:
        event_path = "event:/cars/test/engine_int"
        binding_key = "binding:" + "e" * 64
        variant = {
            "sourceGuid": "finite-source",
            "authoredBindingKey": binding_key,
            "engineProgramRole": "COAST",
            "schedulingGroup": {"groupId": "layer:finite-source"},
            "engineProgramRoleEvidence": {
                "schema": "byd-full-event-engine-program-role-v2",
                "status": "PASS_EXACT_AUTHORED_BINDING_ROUTE_CLASSIFICATION",
                "classificationUsesDiagnosticName": False,
                "role": "COAST",
                "probeValues": [0.0, 0.5, 1.0],
                "bindingIdentity": {
                    "eventPath": event_path,
                    "sourceGuid": "finite-source",
                    "authoredBindingKey": binding_key,
                    "schedulingGroupId": "layer:finite-source",
                },
            },
        }

        _validate_exact_engine_event_role_variant(event_path, variant)
        variant["engineProgramRoleEvidence"]["bindingIdentity"][
            "authoredBindingKey"
        ] = "binding:" + "0" * 64
        with self.assertRaisesRegex(ProfileRecipeError, "exact authored route"):
            _validate_exact_engine_event_role_variant(event_path, variant)

    def test_empty_continuous_role_partition_is_explicit_silence_not_an_invented_source(self) -> None:
        recipe = _recipe()
        removed_guids = {
            "cabin-coast",
            "exterior-coast",
        }
        for perspective in ("cabin", "exterior"):
            recipe["programs"][perspective]["layers"] = [
                layer
                for layer in recipe["programs"][perspective]["layers"]
                if layer["sourceGuid"] not in removed_guids
            ]
        recipe["sourceConservationAudit"]["coreReachableSourceGuids"] = [
            guid
            for guid in recipe["sourceConservationAudit"]["coreReachableSourceGuids"]
            if guid not in removed_guids
        ]
        recipe["sourceConservationAudit"]["coreEventBindings"] = [
            binding
            for binding in recipe["sourceConservationAudit"]["coreEventBindings"]
            if binding["sourceGuid"] not in removed_guids
        ]
        recipe["extraction"]["sources"] = [
            source
            for source in recipe["extraction"]["sources"]
            if source["sourceGuid"] not in removed_guids
        ]

        plan = build_atlas_plan(recipe)
        runtime = build_runtime_index_template(plan)

        for perspective in ("cabin", "exterior"):
            value = plan["perspectives"][perspective]
            contract = value["engineModePrograms"]
            coast = contract["sourcePartition"]["rolePartitionCertifications"][
                "COAST"
            ]
            self.assertEqual(contract["schema"], ENGINE_MODE_PROGRAM_SCHEMA)
            self.assertEqual(contract["sourcePartition"]["COAST"], [])
            self.assertEqual(coast["status"], "PASS_EXACT_GRAPH_EMPTY_ROLE_PARTITION")
            self.assertEqual(
                coast["emptyCaptureDisposition"],
                "modeStillRetainsUNAFFECTEDButMustContainNoOppositeRoleSource",
            )
            self.assertEqual(
                set(value["nodes"][0]["modeProgramTemporaryAssetNames"]),
                {"loadOnly", "coastOnly"},
            )
            runtime_coast = runtime["perspectives"][perspective][
                "engineModePrograms"
            ]["sourcePartition"]["rolePartitionCertifications"]["COAST"]
            self.assertEqual(runtime_coast, coast)


class SourceSelectionCaptureTests(unittest.TestCase):
    def test_derives_nested_authored_span_and_negative_loop_patch(self) -> None:
        graph = {
            "instruments": [
                {
                    "guid": "leaf",
                    "kind": "WaveformInstrumentNode",
                    "childInstruments": [],
                },
                {
                    "guid": "inner",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [
                        {"guid": "leaf"},
                        {"guid": "inner-sibling"},
                    ],
                    "baseProperties": {"loopCount": -1},
                },
                {
                    "guid": "outer",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [
                        {"guid": "inner"},
                        {"guid": "outer-sibling-a"},
                        {"guid": "outer-sibling-b"},
                    ],
                    "baseProperties": {"loopCount": 0},
                },
            ]
        }

        contract = _source_selection_capture_contract(graph, "leaf")

        self.assertEqual(contract["instrumentChainLeafToRoot"], ["leaf", "inner", "outer"])
        self.assertEqual(contract["multiParentGuids"], ["inner", "outer"])
        self.assertEqual(contract["singleShotParentGuids"], ["inner"])
        self.assertEqual(contract["selectionSpan"], 6)
        self.assertEqual(contract["maximumTakes"], 192)

    def test_rejects_ambiguous_source_parent_chain(self) -> None:
        graph = {
            "instruments": [
                {
                    "guid": "leaf",
                    "kind": "WaveformInstrumentNode",
                    "childInstruments": [],
                },
                {
                    "guid": "one",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [{"guid": "leaf"}],
                    "baseProperties": {"loopCount": 0},
                },
                {
                    "guid": "two",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [{"guid": "leaf"}],
                    "baseProperties": {"loopCount": 0},
                },
            ]
        }

        with self.assertRaisesRegex(NrtRecipeError, "ambiguous parent bindings"):
            _source_selection_capture_contract(graph, "leaf")

    def test_accepts_only_exact_zero_gain_controller_points(self) -> None:
        graph = {
            "controllers": [
                {
                    "guid": "fade",
                    "propertyOwnerGuid": "parent",
                    "propertyIndex": 4,
                    "inputKind": "parameter",
                    "inputParameterName": "rpms",
                    "points": [{"x": 6_300.0, "y": 0.0}],
                },
                {
                    "guid": "nearby",
                    "propertyOwnerGuid": "parent",
                    "propertyIndex": 4,
                    "inputKind": "parameter",
                    "inputParameterName": "rpms",
                    "points": [{"x": 6_299.0, "y": 0.0}],
                },
                {
                    "guid": "volume-db",
                    "propertyOwnerGuid": "parent",
                    "propertyIndex": 0,
                    "inputKind": "parameter",
                    "inputParameterName": "rpms",
                    "points": [{"x": 6_300.0, "y": 0.0}],
                },
            ]
        }

        evidence = _exact_zero_gain_curve_evidence(
            graph,
            {"instrumentChainLeafToRoot": ["source", "parent"]},
            {"rpms": 6_300.0},
        )

        self.assertEqual(
            evidence,
            [
                {
                    "controllerGuid": "fade",
                    "propertyOwnerGuid": "parent",
                    "parameter": "rpms",
                    "exactPoint": {"x": 6_300.0, "y": 0.0},
                    "meaning": "normalizedLinearGainExactZero",
                }
            ],
        )

    def test_promotion_keeps_dynamic_initial_membership_semantics(self) -> None:
        binding_key = "binding:" + "a" * 64
        source = {
            "sourceGuid": "source",
            "authoredBindingKey": binding_key,
            "lifetime": "oneShot",
            "triggers": [],
            "semanticLifecycle": [],
            "warnings": ["runtimeTriggerWithheldUntilOriginalBankLifecycleProof"],
            "parameterPlacementEntry": {
                "stateScope": "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
                "membership": {"placements": {"rpms": [{"start": 1.0, "end": 2.0}]}},
            },
            "finiteLifecycleTopology": {
                "status": "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
                "topology": "parameterPlacementOnly",
            },
        }
        evidence = {
            ("event:/cars/test/engine_int", "source", binding_key): {
                "runtimeInitialMembership": True,
                "vectors": [{"pass": True}],
            }
        }

        _apply_parameter_placement_lifecycle_evidence(
            source,
            event_path="event:/cars/test/engine_int",
            evidence=evidence,
            evidence_sha256="b" * 64,
        )

        self.assertEqual(
            source["parameterPlacementEntry"]["initialState"],
            {
                "when": "exactEventInstanceCreated",
                "inside": "startOnceAtCurrentHostParameterValue",
                "outside": "remainSilentUntilOutsideToInsideEntry",
                "evidenceReportSha256": "b" * 64,
            },
        )
        self.assertEqual(source["triggers"], ["PARAMETER_PLACEMENT_ENTRY"])
        self.assertNotIn(
            "runtimeTriggerWithheldUntilOriginalBankLifecycleProof",
            source["warnings"],
        )

    def test_uses_one_cycle_only_for_complete_direct_play_sequential_group(self) -> None:
        base = {
            "maximumTakes": 64,
            "selectionSpan": 2,
            "multiParentGuids": ["parent"],
        }
        sequential = {
            "schedulingGroup": {
                "groupTriggerChancePercent": 100,
                "selection": {"playMode": "PlaylistPlayMode_PlaySequential"},
                "members": [
                    {"authoredOrder": 0, "triggerChancePercent": 100},
                    {"authoredOrder": 1, "triggerChancePercent": None},
                ],
            }
        }

        exact = _effective_source_selection_capture(base, sequential)

        self.assertEqual(exact["maximumTakes"], 2)
        self.assertEqual(exact["boundedSearchMaximumTakes"], 64)
        self.assertEqual(
            exact["effectiveTakeLimitReason"],
            "oneExactPlaySequentialAuthoredCycle",
        )

        smart_random = {
            "schedulingGroup": {
                **sequential["schedulingGroup"],
                "selection": {"playMode": "PlaylistPlayMode_SmartRandom"},
            }
        }
        fallback = _effective_source_selection_capture(base, smart_random)
        self.assertEqual(fallback["maximumTakes"], 64)
        self.assertEqual(
            fallback["effectiveTakeLimitReason"],
            "nonDirectOrNonSequentialUsesBoundedSearch",
        )

    def test_parameter_placement_entry_keeps_all_chain_gates_and_reentry_rules(self) -> None:
        placements = {
            "rpms": [
                {
                    "instrumentGuid": "rpm-parent",
                    "parameterGuid": "rpm-guid",
                    "layoutGuid": "rpm-layout",
                    "start": 800.0,
                    "end": 6_500.0,
                    "includeEnd": False,
                },
                {
                    "instrumentGuid": "rpm-child",
                    "parameterGuid": "rpm-guid",
                    "layoutGuid": "rpm-layout",
                    "start": 2_050.0,
                    "end": 6_550.0,
                    "includeEnd": True,
                },
            ],
            "throttle": [
                {
                    "instrumentGuid": "throttle-child",
                    "parameterGuid": "throttle-guid",
                    "layoutGuid": "throttle-layout",
                    "start": 0.6,
                    "end": 1.00001,
                    "includeEnd": True,
                }
            ],
        }
        contract = _placement_entry_contract(
            placements,
            [
                {"parameter": "rpms", "source": "EngineSimulation.rpm"},
                {"parameter": "throttle", "source": "EngineSimulation.throttle"},
            ],
            {"rpms": 3_000.0, "throttle": 0.7},
        )
        self.assertIsNotNone(contract)
        assert contract is not None
        self.assertEqual(
            contract["membership"]["parameterCombination"],
            "allParameterGroupsMustContainCurrentValue",
        )
        self.assertEqual(
            contract["membership"]["placementsWithinParameter"],
            "allInstrumentChainPlacementsMustContainCurrentValue",
        )
        self.assertEqual(
            contract["membership"]["placements"]["rpms"], placements["rpms"],
        )
        lifecycle = _semantic_lifecycle_contract(
            ["PARAMETER_PLACEMENT_ENTRY"], "oneShot", contract
        )
        self.assertEqual(
            lifecycle[0]["retrigger"],
            "everyOutsideToInsidePlacementEntrySubjectToSchedulingGroupPolyphony",
        )
        self.assertEqual(
            lifecycle[0]["parameterPlacementEntry"], contract,
        )
        self.assertEqual(
            contract["initialState"]["inside"],
            "BLOCKED_PENDING_ORIGINAL_BANK_LIFECYCLE_PROBE",
        )
        self.assertEqual(
            contract["stateScope"],
            "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
        )

        static_contract = _placement_entry_contract(
            {
                "valved": [
                    {
                    "instrumentGuid": "valve-source",
                    "parameterGuid": "valve-guid",
                    "layoutGuid": "valve-layout",
                        "start": 0.1,
                        "end": 0.9,
                        "includeEnd": True,
                    }
                ]
            },
            [],
            {"valved": 0.5},
        )
        self.assertEqual(
            static_contract["membership"]["parameterValues"],
            [
                {
                    "parameter": "valved",
                    "parameterGuid": "valve-guid",
                    "layoutGuid": "valve-layout",
                    "value": {"kind": "authoredDefault", "value": 0.5},
                }
            ],
        )

    def test_event_instance_state_never_uses_listener_perspective(self) -> None:
        playlist = _playlist_selection_runtime_contract()
        self.assertEqual(
            playlist["stateScope"],
            "selectionKindSpecificSeeSelectionStateOwnership",
        )
        self.assertNotIn("perspective", playlist["seedDerivation"]["formula"])
        self.assertIn(
            "profileAudioSessionGeneration", playlist["seedDerivation"]["formula"]
        )
        self.assertEqual(
            playlist["selectionStateOwnership"]["playSequential"]["scope"],
            "perExactEventPathEventInstanceActivationGenerationAndGroupId",
        )
        self.assertEqual(
            playlist["selectionStateOwnership"]["smartRandom"][
                "androidDeterministicSubstituteScope"
            ],
            "perProfileAudioSessionGenerationExactEventPathAndGroupId",
        )
        self.assertEqual(
            playlist["selectionStateOwnership"]["smartRandom"]["sequenceParity"],
            "notClaimed",
        )
        self.assertEqual(
            _event_instance_ownership("turbo")["survives"],
            "listenerCameraAndLoadCoastBothModeChanges",
        )
        self.assertEqual(
            _event_instance_ownership("engine_int")["resets"],
            "thatExactEngineEventPathStopRewindStartOrNewInstance",
        )
        self.assertEqual(
            _event_instance_ownership("gear_int")["activationGeneration"],
            "incrementsOnlyWhenThatPersistentExactEventPathIsStoppedThenStarted",
        )
        self.assertEqual(
            _event_instance_ownership("limiter")["activationGeneration"],
            "incrementsOnlyWhenThatPersistentExactEventPathIsStoppedThenStarted",
        )
        self.assertEqual(
            _event_instance_ownership("transmission")["activationGeneration"],
            "incrementsOnlyWhenThatPersistentExactEventPathIsStoppedThenStarted",
        )

    def test_newly_hosted_mechanical_events_are_runtime_delivered(self) -> None:
        for suffix in (
            "start",
            "transmission_ext",
            "tractioncontrol_int",
            "tractioncontrol_ext",
            "gear_grind",
        ):
            with self.subTest(suffix=suffix):
                ownership = _host_event_ownership_evidence(suffix)
                self.assertEqual(ownership["status"], "instantiated")
                self.assertEqual(ownership["runtimeDelivery"], "atlasRuntime")
                self.assertEqual(
                    _event_instance_ownership(suffix)["owner"],
                    "profileAudioSessionPersistentEventInstance",
                )

    def test_still_unhosted_mechanical_event_is_conservation_only(self) -> None:
        ownership = _host_event_ownership_evidence("mechanical_whine")
        self.assertEqual(ownership["status"], "staticOnlyHostUnreachable")
        self.assertEqual(
            ownership["runtimeDelivery"],
            "conservationOnlyNoNrtCaptureOrAndroidPlayback",
        )
        self.assertEqual(
            _host_event_ownership_evidence("transmission")["status"],
            "instantiated",
        )

    def test_external_transmission_replaces_shared_fallback_only_when_authored(self) -> None:
        self.assertIsNone(_effect_perspective("transmission"))
        self.assertEqual(
            _effect_perspective("transmission", has_transmission_ext=True),
            "cabin",
        )
        self.assertEqual(
            _effect_perspective("transmission_ext", has_transmission_ext=True),
            "exterior",
        )


class AtlasPlanTests(unittest.TestCase):
    def test_playlist_graph_requires_explicit_contiguous_authored_order(self) -> None:
        graph = {
            "instruments": [
                {
                    "guid": "playlist",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [
                        {"guid": "first", "weight": 1.0},
                        {"guid": "second", "weight": 1.0},
                    ],
                },
                {"guid": "first", "kind": "WaveformInstrumentNode"},
                {"guid": "second", "kind": "WaveformInstrumentNode"},
            ],
            "controllers": [],
            "parameters": [],
            "modulators": [],
        }
        with self.assertRaisesRegex(ProfileRecipeError, "lacks explicit authoredOrder"):
            index_graph(graph)

        graph["instruments"][0]["childInstruments"] = [
            {"guid": "second", "weight": 1.0, "authoredOrder": 1},
            {"guid": "first", "weight": 1.0, "authoredOrder": 0},
        ]
        self.assertEqual(
            index_graph(graph).instruments["playlist"]["childInstruments"][0][
                "authoredOrder"
            ],
            1,
        )

    def test_preflight_snapshot_records_candidate_guid_set_without_claiming_exact_callback_identity(self) -> None:
        plan = {
            "perspectives": {
                "exterior": {
                    "eventPath": "event:/cars/test/engine_ext",
                    "requiredSourceCoverage": [
                        {"diagnosticName": "same_loop", "sourceGuid": "source-a"},
                        {"diagnosticName": "same_loop", "sourceGuid": "source-b"},
                    ],
                }
            },
            "effects": [],
        }
        result = _preflight_candidate_snapshot_scenario(
            plan,
            perspective="exterior",
            identifier="fixture",
            kind="preflightEngineHold",
            snapshots=[
                {"afterDspBlockStartFrame": 0, "logicalChannels": 2, "realChannels": 2}
            ],
            scheduled_by_instance=[["same_loop"]],
            event_paths=["event:/cars/test/engine_ext"],
        )
        self.assertTrue(result["pass"])
        self.assertEqual(result["callbackIdentityResolution"], "candidateSetOnly")
        self.assertFalse(result["exactCallbackGuidAttributionPass"])
        self.assertEqual(
            result["callbackCandidateOccurrences"],
            [
                {
                    "eventInstanceIndex": 0,
                    "eventPath": "event:/cars/test/engine_ext",
                    "diagnosticName": "same_loop",
                    "candidateSourceGuids": ["source-a", "source-b"],
                    "candidateSourcesByCategory": {
                        "engineContinuous": ["source-a", "source-b"]
                    },
                    "candidateGuidMultiplicity": 2,
                    "observedCallbackMultiplicity": 1,
                    "maximumCompatibleCallbackMultiplicity": 2,
                }
            ],
        )
        self.assertNotIn("rawSourceGuidCallbackBindings", result)

    def test_preflight_uses_binding_capture_parameters_not_an_outside_placement_node(self) -> None:
        graph = {
            "events": [
                {
                    "path": "event:/cars/test/gear",
                    "reachableInstrumentGuids": ["target"],
                }
            ],
            "instruments": [
                {
                    "guid": "target",
                    "kind": "WaveformInstrumentNode",
                    "sample": {"name": "gear_up"},
                }
            ],
        }
        binding = {
            "bindingId": "source:target",
            "sourceGuid": "target",
            "runtimeMapping": {
                "captureParameters": {"state": 1.0},
                "parameterDomains": {"state": [0.0, 2.0]},
                "parameterPlacementEntry": {
                    "membership": {
                        "placements": {
                            "state": [
                                {
                                    "instrumentGuid": "playlist",
                                    "layoutGuid": "state-layout",
                                    "parameterGuid": "state-guid",
                                    "start": 0.5,
                                    "end": 1.0,
                                    "includeEnd": True,
                                }
                            ]
                        }
                    }
                },
                "schedulingGroup": {
                    "selection": {"playMode": "PlaylistPlayMode_PlaySequential"}
                },
            },
        }
        node = {
            "requiredSourceGuid": "target",
            "requiredDiagnosticName": "gear_up",
            # This anchor is outside the source's authored state [0.5, 1].
            "parameters": {"state": 0.0},
            "sourceBindings": [binding],
        }
        tasks = _effect_source_tasks(
            {
                "familyId": "family",
                "planSha256": "a" * 64,
                "bankSha256": "b" * 64,
                "graph": graph,
                "plan": {
                    "effects": [
                        {
                            "eventPath": "event:/cars/test/gear",
                            "perspectives": ["exterior"],
                            "nodes": [node],
                        }
                    ]
                },
            }
        )
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]["parameters"], {"state": 1.0})
        self.assertEqual(tasks[0]["startParameters"], {"state": 0.0})
        self.assertEqual(
            tasks[0]["takeLifecycle"], "singleEventInstancePlacementReentry-v1"
        )

    def test_preflight_blocks_sequential_source_without_a_proven_outside_placement_state(self) -> None:
        graph = {
            "events": [{"path": "event:/cars/test/gear", "reachableInstrumentGuids": ["target"]}],
            "instruments": [
                {
                    "guid": "target",
                    "kind": "WaveformInstrumentNode",
                    "sample": {"name": "gear_up"},
                }
            ],
        }
        binding = {
            "bindingId": "source:target",
            "sourceGuid": "target",
            "runtimeMapping": {
                "captureParameters": {"state": 1.0},
                "parameterDomains": {"state": [0.5, 1.0]},
                "parameterPlacementEntry": {
                    "membership": {
                        "placements": {
                            "state": [
                                {
                                    "instrumentGuid": "playlist",
                                    "layoutGuid": "state-layout",
                                    "parameterGuid": "state-guid",
                                    "start": 0.5,
                                    "end": 1.0,
                                    "includeEnd": True,
                                }
                            ]
                        }
                    }
                },
                "schedulingGroup": {
                    "selection": {"playMode": "PlaylistPlayMode_PlaySequential"}
                },
            },
        }
        family = {
            "familyId": "family",
            "planSha256": "a" * 64,
            "bankSha256": "b" * 64,
            "graph": graph,
            "plan": {
                "effects": [
                    {
                        "eventPath": "event:/cars/test/gear",
                        "perspectives": ["exterior"],
                        "nodes": [
                            {
                                "requiredSourceGuid": "target",
                                "requiredDiagnosticName": "gear_up",
                                "sourceBindings": [binding],
                            }
                        ],
                    }
                ]
            },
        }
        with self.assertRaisesRegex(PreflightError, "no host-controllable parameter-domain endpoint"):
            _effect_source_tasks(family)

    def test_preflight_uses_original_bank_only_for_a_single_reachable_waveform(self) -> None:
        graph = {
            "events": [
                {
                    "path": "event:/cars/test/tractioncontrol",
                    "reachableInstrumentGuids": ["target"],
                }
            ],
            "instruments": [
                {"guid": "target", "kind": "WaveformInstrumentNode"}
            ],
        }
        calls: list[Path] = []
        renderer = SimpleNamespace(
            render_event=lambda bank, *_args, **_kwargs: (
                calls.append(bank)
                or SimpleNamespace(
                    scheduled_sound_names=("target_sound",),
                    scheduled_sound_names_by_take=(("target_sound",),),
                )
            )
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bank = root / "test.bank"
            bank.write_bytes(b"bank")
            result = _select_and_isolate(
                renderer=renderer,
                family={"graph": graph, "bankPath": bank},
                task={
                    "eventPath": "event:/cars/test/tractioncontrol",
                    "sourceGuid": "target",
                    "diagnosticName": "target_sound",
                    "parameters": {},
                    "takeLifecycle": "newEventInstancePerTake-v1",
                },
                create_isolated_bank_copy=lambda *_args, **_kwargs: self.fail("must not patch a single-source event"),
                fully_muted_multi_instrument_guids=lambda *_args, **_kwargs: self.fail("must not select parents for no patch"),
                temporary_root=root,
            )
        self.assertEqual(calls, [bank.resolve()])
        self.assertEqual(result["sourceIsolationMethod"], "singleReachableWaveformNoIsolationRequired-v1")
        self.assertEqual(result["mutedWaveformSourceCount"], 0)

    def test_preflight_requires_derivative_when_a_reachable_sibling_exists(self) -> None:
        graph = {
            "events": [
                {
                    "path": "event:/cars/test/gear",
                    "reachableInstrumentGuids": ["target", "sibling"],
                }
            ],
            "instruments": [
                {"guid": "target", "kind": "WaveformInstrumentNode"},
                {"guid": "sibling", "kind": "WaveformInstrumentNode"},
            ],
        }
        calls: list[Path] = []
        renderer = SimpleNamespace(
            render_event=lambda bank, *_args, **_kwargs: (
                calls.append(bank)
                or SimpleNamespace(
                    scheduled_sound_names=("target_sound",),
                    scheduled_sound_names_by_take=(("target_sound",),),
                )
            )
        )
        muted_seen: list[set[str]] = []
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bank = root / "test.bank"
            bank.write_bytes(b"bank")
            isolated = root / "isolated.bank"
            isolated.write_bytes(b"isolated")
            result = _select_and_isolate(
                renderer=renderer,
                family={"graph": graph, "bankPath": bank},
                task={
                    "eventPath": "event:/cars/test/gear",
                    "sourceGuid": "target",
                    "diagnosticName": "target_sound",
                    "parameters": {},
                    "takeLifecycle": "newEventInstancePerTake-v1",
                },
                create_isolated_bank_copy=lambda _bank, _graph, muted, *_args, **_kwargs: (
                    muted_seen.append(set(muted))
                    or SimpleNamespace(output_path=isolated)
                ),
                fully_muted_multi_instrument_guids=lambda *_args, **_kwargs: set(),
                temporary_root=root,
            )
        self.assertEqual(muted_seen, [{"sibling"}])
        self.assertEqual(calls, [isolated])
        self.assertEqual(result["sourceIsolationMethod"], "sourceSoloEventRoutingAndBusDsp-v1")

    def test_source_isolation_preserves_bounded_playlist_take_search(self) -> None:
        graph = {
            "events": [
                {
                    "path": "event:/cars/test/backfire",
                    "reachableInstrumentGuids": ["target", "sibling"],
                }
            ],
            "instruments": [
                {"guid": "target", "kind": "WaveformInstrumentNode"},
                {"guid": "sibling", "kind": "WaveformInstrumentNode"},
            ],
        }
        node = {
            "requiredSourceGuid": "target",
            "requiredDiagnosticName": "selected_after_playlist_search",
        }
        observed_node: dict[str, object] = {}

        def render_node(*_args: object, **_kwargs: object) -> dict[str, object]:
            observed_node.update(_args[3])
            return {"scheduledDiagnosticNames": ["selected_after_playlist_search"]}

        with tempfile.TemporaryDirectory() as temporary, patch(
            "refine_full_event_atlas._render_node", side_effect=render_node
        ):
            result = _render_isolated_effect_node(
                renderer=object(),
                bank=Path("/bank"),
                event="event:/cars/test/backfire",
                node=node,
                output=Path(temporary) / "selected.wav",
                graph=graph,
                create_isolated_bank_copy=lambda *_args, **_kwargs: SimpleNamespace(
                    output_path=Path("/isolated.bank")
                ),
                fully_muted_multi_instrument_guids=lambda *_args, **_kwargs: set(),
                loop_tools=(object(), object()),
            )
        self.assertNotIn("maximumRequiredSourceTakes", observed_node)
        self.assertTrue(result["sourceIsolatedFullEventContribution"])

    def test_runtime_release_promotion_rejects_any_surviving_blocked_contract(self) -> None:
        oracle = {
            "status": "PASS",
            "combinedEngineEffectMixOracle": {
                "globalFmodChannelArbitrationOracle": {
                    "schema": "byd-full-event-fmod-channel-arbitration-oracle-v2",
                    "status": "PASS",
                    "familyPerspectiveScenarios": [
                        {
                            "perspective": "cabin",
                            "pass": True,
                            "maximumLogicalChannels": 7,
                            "maximumRealChannels": 7,
                        },
                        {
                            "perspective": "exterior",
                            "pass": True,
                            "maximumLogicalChannels": 9,
                            "maximumRealChannels": 9,
                        },
                    ],
                }
            },
        }
        family_id = "fixture-family"
        plan_sha256 = "b" * 64
        runtime = {
            "id": family_id,
            "planSha256": plan_sha256,
            "draftBlocked": True,
            "interpolationContract": {},
            "effects": {"channelArbitration": {"status": "BLOCKED_PENDING"}},
            "resourceBounds": {
                "schema": "byd-full-event-atlas-runtime-resource-bounds-v3",
                "scope": "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
                "perPerspective": {
                    perspective: {"engine": {}, "effects": {}, "total": {}}
                    for perspective in ("cabin", "exterior")
                },
                "session": {
                    "proofStatus": "BLOCKED_PENDING_SESSION_MAPPING_INSTANCE_PROOF"
                },
            },
        }
        causal_update = {
            "schema": "byd-full-event-causal-runtime-resource-update-v1",
            "status": "PASS",
            "atlasFamilyId": family_id,
            "planSha256": plan_sha256,
            "causalResourceProofSha256": "c" * 64,
            "packedShardProofSha256": "d" * 64,
            "causalVerifierImplementationSha256": hashlib.sha256(
                Path(__file__).with_name("causal_full_event_resource_proof.py").read_bytes()
            ).hexdigest(),
            "sessionStateGraphProducerImplementationSha256": hashlib.sha256(
                Path(__file__).with_name(
                    "export_full_event_session_state_graph.py"
                ).read_bytes()
            ).hexdigest(),
            "resourceBoundsSchema": "byd-full-event-atlas-runtime-resource-bounds-v3",
            "resourceBoundsScope": "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
            "channelArbitration": {},
            "sessionCommon": {
                "finiteRingPoolBytes": 196_608,
                "maximumCausalFiniteLogicalRingVoices": 2,
                "maximumCausalFmodLogicalChannels": 9,
                "maximumCausalFmodRealChannels": 9,
                "finiteRingPoolStatus": "PASS",
                "peakProofStatus": "PASS",
            },
            "session": {
                "mappingInstanceIdentity": "activationPerspectivePlusShardName",
                "retainedEffectPerspectives": ["cabin", "exterior"],
                "perSelectedEnginePerspective": {
                    perspective: {
                        "engineMaximumMappedShardInstancesDuringCellTransition": 1,
                        "retainedCabinEffectsMaximumMappedShardInstances": 2,
                        "retainedExteriorEffectsMaximumMappedShardInstances": 2,
                        "maximumMappedShardInstancesDuringTransitionSafeUpperBound": 5,
                    }
                    for perspective in ("cabin", "exterior")
                },
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound": 5,
                "proofStatus": "PASS",
            },
            "perSelectedPerspective": {
                perspective: {
                    "engine": {"peakProofStatus": "PASS"},
                    "effects": {
                        "finiteRingPoolBytes": 196_608,
                        "finiteRingPoolStatus": "PASS",
                        "peakProofStatus": "PASS",
                    },
                    "total": {"peakProofStatus": "PASS"},
                }
                for perspective in ("cabin", "exterior")
            },
        }
        causal_update["proofSha256"] = hashlib.sha256(
            canonical_json_bytes(causal_update)
        ).hexdigest()
        apply_causal_runtime_resource_update(runtime, causal_update)

        runtime["resourceBounds"]["session"]["proofStatus"] = "BLOCKED_TAMPERED"
        with self.assertRaisesRegex(ValueError, "exact verified causal resource update"):
            _promote_runtime_release_contract(
                runtime,
                oracle,
                "a" * 64,
                causal_update,
            )
        apply_causal_runtime_resource_update(runtime, causal_update)
        runtime["unresolvedExecutableGate"] = {"status": "BLOCKED_PENDING"}
        with self.assertRaisesRegex(ValueError, "blocked executable contracts"):
            _promote_runtime_release_contract(
                runtime,
                oracle,
                "a" * 64,
                causal_update,
            )
        runtime.pop("unresolvedExecutableGate")
        _promote_runtime_release_contract(
            runtime,
            oracle,
            "a" * 64,
            causal_update,
        )
        self.assertFalse(runtime["draftBlocked"])
        self.assertEqual(runtime["effects"]["channelArbitration"]["status"], "PASS")
        self.assertEqual(runtime["oracleReportSha256"], "a" * 64)

    def test_compact_effect_shard_map_uses_authored_binding_not_source_guid(self) -> None:
        source_guid = "00000000-0000-0000-0000-000000000001"
        first_binding = "binding:" + "1" * 64
        second_binding = "binding:" + "2" * 64
        event_path = "event:/fixture/turbo"
        parameters = {"boost": 0.5}
        runtime = {
            "effects": {
                "runtimeContract": {
                    "schema": "byd-full-event-effect-runtime-v5"
                },
                "variantBindings": [
                    {
                        "id": "v0",
                        "sourceGuid": source_guid,
                        "authoredBindingKey": first_binding,
                    },
                    {
                        "id": "v1",
                        "sourceGuid": source_guid,
                        "authoredBindingKey": second_binding,
                    },
                ],
                "events": [
                    {
                        "eventPath": event_path,
                        "nodes": [
                            ["v0", parameters, "effects-0.wav", 0, 512, None, None],
                            ["v1", parameters, "effects-1.wav", 512, 1_024, None, None],
                        ],
                    }
                ],
            }
        }
        mapped = _compact_effect_node_shard_map(runtime)
        self.assertEqual(
            mapped,
            {
                effect_node_key(
                    event_path,
                    source_guid,
                    first_binding,
                    parameters,
                ): "effects-0.wav",
                effect_node_key(
                    event_path,
                    source_guid,
                    second_binding,
                    parameters,
                ): "effects-1.wav",
            },
        )

    def test_channel_snapshot_gate_requires_unique_callback_guid_and_budget(self) -> None:
        plan = {
            "perspectives": {
                "cabin": {
                    "requiredSourceCoverage": [
                        {"diagnosticName": "engine_loop", "sourceGuid": "engine-guid"}
                    ]
                }
            },
            "effects": [
                {
                    "perspectives": ["cabin"],
                    "runtimeLifecycleParameterVariantContract": {
                        "variants": [
                            {
                                "diagnosticName": "turbo_dump",
                                "sourceGuid": "turbo-guid",
                                "runtimeMapping": {"perspectives": ["cabin"]},
                            }
                        ]
                    },
                }
            ],
        }
        passing = _channel_snapshot_scenario(
            plan,
            perspective="cabin",
            identifier="fixture",
            kind="dynamicEngineTrajectory",
            snapshots=[
                {"afterDspBlockStartFrame": 0, "logicalChannels": 2, "realChannels": 2}
            ],
            scheduled_by_instance=[["engine_loop", "turbo_dump"]],
        )
        self.assertTrue(passing["pass"])
        self.assertEqual(
            passing["rawSourceGuidCallbackBindings"],
            [
                {"diagnosticName": "engine_loop", "sourceGuid": "engine-guid"},
                {"diagnosticName": "turbo_dump", "sourceGuid": "turbo-guid"},
            ],
        )
        unresolved = _channel_snapshot_scenario(
            plan,
            perspective="cabin",
            identifier="unresolved",
            kind="dynamicEngineTrajectory",
            snapshots=[
                {"afterDspBlockStartFrame": 0, "logicalChannels": 1, "realChannels": 1}
            ],
            scheduled_by_instance=[["unknown"]],
        )
        self.assertFalse(unresolved["pass"])
        self.assertEqual(unresolved["unresolvedCallbackNames"], ["unknown"])
        over_budget = _channel_snapshot_scenario(
            plan,
            perspective="cabin",
            identifier="over-budget",
            kind="dynamicEngineTrajectory",
            snapshots=[
                {"afterDspBlockStartFrame": 0, "logicalChannels": 257, "realChannels": 257}
            ],
            scheduled_by_instance=[["engine_loop"]],
        )
        self.assertFalse(over_budget["pass"])

    def test_combined_oracle_refuses_callback_source_ambiguity(self) -> None:
        event = {
            "nodes": [
                {
                    "requiredDiagnosticName": "same-sound",
                    "requiredSourceGuid": "source-a",
                    "parameters": {"state": 0.0},
                    "temporaryAssetName": "a.wav",
                },
                {
                    "requiredDiagnosticName": "same-sound",
                    "requiredSourceGuid": "source-b",
                    "parameters": {"state": 0.0},
                    "temporaryAssetName": "b.wav",
                },
            ]
        }

        with self.assertRaises(NrtRecipeError):
            _observed_effect_contributions(event, {"state": 0.0}, ["same-sound"])

    def test_engine_event_transient_shares_the_engine_host_gain(self) -> None:
        engine = [0.2, -0.2, 0.1, -0.1]
        transient = [0.4, -0.4, 0.2, -0.2]

        engine_event = _apply_host_mix_contract(
            engine, [transient], ["engineEvent"]
        )
        effect_event = _apply_host_mix_contract(
            engine, [transient], ["effectEvent"]
        )

        for actual, expected in zip(engine_event, [0.3, -0.3, 0.15, -0.15]):
            self.assertAlmostEqual(actual, expected)
        for actual, expected in zip(effect_event, [0.5, -0.5, 0.25, -0.25]):
            self.assertAlmostEqual(actual, expected)

    def test_selected_engine_program_is_not_additively_reconstructed(self) -> None:
        full = [1.0, -1.0, 0.5, -0.5]
        transient = [0.4, -0.4, 0.2, -0.2]
        mixed = _apply_host_mix_contract(
            full,
            [transient],
            ["engineEvent"],
            load_program_gain=0.5,
            coast_program_gain=0.25,
            engine_event_effect_roles=["LOAD"],
        )
        for actual, expected in zip(mixed, [0.6, -0.6, 0.3, -0.3]):
            self.assertAlmostEqual(actual, expected)

    def test_plan_starts_at_endpoints_and_runtime_maps_at_most_four_nodes(self) -> None:
        plan = build_atlas_plan(_recipe())
        runtime = build_runtime_index_template(plan)

        self.assertEqual(plan["schema"], ATLAS_PLAN_SCHEMA)
        self.assertEqual(plan["perspectives"]["cabin"]["initialAxes"]["throttle"], [0.0, 1.0])
        self.assertEqual(
            plan["perspectives"]["cabin"]["mandatoryOracleProbes"]["authoredThrottleKnots"],
            [0.0, 0.25, 0.75, 1.0],
        )
        self.assertEqual(
            runtime["hotCellPolicy"]["maximumMappedLoopNodesPerPerspective"], 4
        )
        engine_gain = runtime["hostMixContract"]["engineProgramGainContract"]
        self.assertEqual(
            engine_gain["schema"], "byd-full-event-engine-program-gain-v4"
        )
        self.assertEqual(
            engine_gain["normalUnityRepresentation"]["formula"],
            "directFULLProgram",
        )
        self.assertEqual(
            engine_gain["sampleCombination"],
            "neverAddOrSubtractIndependentPrograms",
        )
        self.assertTrue(runtime["hotCellPolicy"]["wholeAtlasHeapDecodeForbidden"])
        engine_channels = plan["perspectives"]["cabin"]["logicalChannelMultiplicity"]
        self.assertEqual(
            engine_channels["maximumLogicalSourceChannelsAtAtlasNode"], 4
        )
        self.assertEqual(
            engine_channels["cells"][0]["logicalSourceChannelMultiplicity"], 4
        )
        self.assertEqual(
            runtime["resourceBounds"]["perPerspective"]["cabin"]["engine"][
                "maximumFmodLogicalSourceChannelsAtAtlasNode"
            ],
            4,
        )
        self.assertTrue(
            runtime["resourceBounds"]["perPerspective"]["cabin"]["engine"][
                "androidPremixedBedIsNotFmodChannelAccounting"
            ]
        )
        resource_bounds = runtime["resourceBounds"]
        self.assertEqual(
            resource_bounds["schema"],
            "byd-full-event-atlas-runtime-resource-bounds-v3",
        )
        self.assertEqual(
            resource_bounds["scope"],
            "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
        )
        self.assertEqual(
            resource_bounds["perPerspective"]["cabin"]["effects"]["resourceModel"],
            "profileSessionRetainedEffectsResourceBounds-v3",
        )
        self.assertNotIn(
            "maximumUniqueMappedShardsDuringTransitionSafeUpperBound",
            resource_bounds["perPerspective"]["cabin"]["total"],
        )
        self.assertEqual(
            resource_bounds["session"],
            {
                "mappingInstanceIdentity": "activationPerspectivePlusShardName",
                "retainedEffectPerspectives": ["cabin", "exterior"],
                "perSelectedEnginePerspective": {
                    perspective: {
                        "engineMaximumMappedShardInstancesDuringCellTransition": None,
                        "retainedCabinEffectsMaximumMappedShardInstances": None,
                        "retainedExteriorEffectsMaximumMappedShardInstances": None,
                        "maximumMappedShardInstancesDuringTransitionSafeUpperBound": None,
                    }
                    for perspective in ("cabin", "exterior")
                },
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound": None,
                "proofStatus": "BLOCKED_PENDING_SESSION_MAPPING_INSTANCE_PROOF",
            },
        )
        contract = runtime["interpolationContract"]
        self.assertEqual(
            contract["algorithm"],
            "independentFmodMasterProgramsRootRpmBilinear-v1",
        )
        self.assertEqual(contract["activation"]["gainFormula"], "rawBilinearWeight")
        self.assertTrue(contract["activation"]["prepareOnlyAtZeroWeight"])

    def test_mixed_turbo_effect_keeps_source_identity_and_executable_contract(self) -> None:
        recipe = _recipe()
        scheduler = lambda source_guid: {
            "composition": "simultaneousLayer",
            "groupId": f"layer:{source_guid}",
            "selection": {"kind": "always"},
            "members": [
                {
                    "sourceGuid": source_guid,
                    "authoredOrder": 0,
                    "weight": 1.0,
                    "triggerChancePercent": 100.0,
                }
            ],
            "timelinePlacements": [],
            "complete": True,
        }
        loop_mapping = {
            "kind": "effect",
            "authoredBindingKey": "binding:" + "1" * 64,
            "hostGainClass": "effectEvent",
            "perspectives": ["cabin"],
            "triggers": ["TURBO_LOOP"],
            "lifetime": "continuous",
            "semanticLifecycle": [
                {
                    "trigger": "TURBO_LOOP",
                    "retrigger": "noneWhileActive",
                }
            ],
            "parameters": {"boost": 0.0},
            "parameterAxes": {"boost": [0.0, 1.0]},
            "variantSourceGuid": "turbo-loop",
            "schedulingGroup": scheduler("turbo-loop"),
        }
        dump_mapping = {
            "kind": "effect",
            "authoredBindingKey": "binding:" + "2" * 64,
            "hostGainClass": "effectEvent",
            "perspectives": ["exterior"],
            "triggers": ["TURBO_DUMP"],
            "lifetime": "oneShot",
            "semanticLifecycle": [
                {
                    "trigger": "TURBO_DUMP",
                    "retrigger": "everyNewSequenceValueSubjectToSchedulingGroupPolyphony",
                }
            ],
            "parameters": {"boost": 0.0},
            "parameterAxes": {"boost": [0.0, 1.0]},
            "variantSourceGuid": "turbo-dump",
            "schedulingGroup": scheduler("turbo-dump"),
        }
        recipe["sourceConservationAudit"]["coreEventBindings"].extend(
            [
                {
                    "eventPath": "event:/cars/test/turbo",
                    "sourceGuid": "turbo-loop",
                    "assetName": "turbo-loop.wav",
                    "captureMode": "targetOnlyFmodNrtEffect",
                    "runtimeMapping": loop_mapping,
                },
                {
                    "eventPath": "event:/cars/test/turbo",
                    "sourceGuid": "turbo-dump",
                    "assetName": "turbo-dump.wav",
                    "captureMode": "targetOnlyFmodNrtEffect",
                    "runtimeMapping": dump_mapping,
                },
            ]
        )
        recipe["extraction"]["sources"].extend(
            [
                {
                    "assetName": "turbo-loop.wav",
                    "sourceGuid": "turbo-loop",
                    "lifetime": "continuous",
                    "diagnosticNameNotUsedForClassification": "turbo-loop",
                    "primaryCapture": {
                        "parameters": {"boost": 0.0},
                        "parameterAxes": {"boost": [0.0, 1.0]},
                        "durationFrames": 96_000,
                        "warmupFrames": 36_000,
                    },
                },
                {
                    "assetName": "turbo-dump.wav",
                    "sourceGuid": "turbo-dump",
                    "lifetime": "oneShot",
                    "diagnosticNameNotUsedForClassification": "turbo-dump",
                    "primaryCapture": {
                        "parameters": {"boost": 0.0},
                        "parameterAxes": {"boost": [0.0, 1.0]},
                        "durationFrames": 48_000,
                        "warmupFrames": 0,
                    },
                },
            ]
        )

        runtime = build_runtime_index_template(build_atlas_plan(recipe))
        turbo = next(
            event for event in runtime["effects"]["events"] if event["eventSuffix"] == "turbo"
        )

        self.assertEqual(turbo["perspectives"], ["cabin", "exterior"])
        self.assertEqual(
            turbo["effectInterpolationContract"]["continuous"]["algorithm"],
            "perSourceAxisAlignedMultilinear-v1",
        )
        self.assertEqual(
            turbo["effectInterpolationContract"]["oneShot"]["algorithm"],
            "perSourceAxisAlignedMultilinearFiniteRing-v2",
        )
        self.assertEqual(turbo["maximumSimultaneousVoices"], 2)
        self.assertEqual(turbo["maximumMappedNodes"], 2)
        cabin_resources = turbo["perspectiveResources"]["cabin"]
        self.assertEqual(
            cabin_resources["continuous"]["maximumMmapPlaybackCornerVoices"], 2
        )
        self.assertEqual(
            cabin_resources["finite"]["combinedOneDspUpdateUpperBound"]["logicalRingVoices"], 0
        )
        self.assertEqual(
            cabin_resources["finite"]["maximumSourceCornerContributorsPerUpdate"], 0
        )
        exterior_resources = turbo["perspectiveResources"]["exterior"]
        self.assertEqual(
            exterior_resources["continuous"]["maximumMmapPlaybackCornerVoices"], 0
        )
        self.assertEqual(
            exterior_resources["finite"]["combinedOneDspUpdateUpperBound"]["logicalRingVoices"], 1
        )
        self.assertEqual(
            exterior_resources["finite"]["maximumSourceCornerContributorsPerUpdate"], 2
        )
        self.assertEqual({node["requiredSourceGuid"] for node in turbo["nodes"]}, {"turbo-loop", "turbo-dump"})
        for node in turbo["nodes"]:
            self.assertEqual(len(node["sourceBindings"]), 1)
            self.assertEqual(
                node["requiredSourceGuid"], node["sourceBindings"][0]["sourceGuid"]
            )
            self.assertEqual(node["hostGainClass"], "effectEvent")

    def test_finite_effect_runtime_corners_are_exact_nd_lower_upper_weights(self) -> None:
        variant = {
            "parameters": {"rpms": 800.0, "throttle": 0.0, "valved": 0.0},
            "parameterAxes": {
                "rpms": [800.0, 1_200.0],
                "throttle": [0.0, 1.0],
                "valved": [0.0],
            },
        }

        corners = _finite_effect_multilinear_corners(
            variant, {"rpms": 900.0, "throttle": 0.25, "valved": 0.0}
        )

        self.assertEqual(len(corners), 4)
        self.assertAlmostEqual(sum(weight for _parameters, weight in corners), 1.0)
        by_parameters = {
            (parameters["rpms"], parameters["throttle"], parameters["valved"]): weight
            for parameters, weight in corners
        }
        self.assertAlmostEqual(by_parameters[(800.0, 0.0, 0.0)], 0.5625)
        self.assertAlmostEqual(by_parameters[(1_200.0, 0.0, 0.0)], 0.1875)
        self.assertAlmostEqual(by_parameters[(800.0, 1.0, 0.0)], 0.1875)
        self.assertAlmostEqual(by_parameters[(1_200.0, 1.0, 0.0)], 0.0625)

    def test_phase_invariant_oracle_accepts_phase_shift_and_rejects_pitch_or_timbre(self) -> None:
        def tone(frequency: float, *, phase: float = 0.0, square: bool = False) -> list[float]:
            result: list[float] = []
            for frame in range(12_000):
                value = math.sin(2.0 * math.pi * frequency * frame / 48_000.0 + phase)
                if square:
                    value = 1.0 if value >= 0.0 else -1.0
                result.extend((value * 0.5, value * 0.5))
            return result

        reference = tone(440.0)
        phase_shifted = phase_invariant_metrics(reference, tone(440.0, phase=1.7))
        wrong_pitch = phase_invariant_metrics(reference, tone(660.0))
        wrong_timbre = phase_invariant_metrics(reference, tone(440.0, square=True))

        self.assertFalse(phase_shifted["rawPcmNrmseUsed"])
        self.assertLess(phase_shifted["maximumBandEnergyErrorDb"], 0.25)
        self.assertLess(abs(phase_shifted["pitchErrorCents"]), 3.0)
        self.assertGreater(abs(wrong_pitch["pitchErrorCents"]), 3.0)
        self.assertGreater(wrong_timbre["maximumBandEnergyErrorDb"], 0.25)

    def test_cubic_interpolation_fixture_matches_android_formula(self) -> None:
        fixture = json.loads(
            (
                Path(__file__).parent
                / "fixtures"
                / "atlas-cubic-interpolation-v1.json"
            ).read_text(encoding="utf-8")
        )
        samples = [
            value / 32767.0
            for frame in fixture["pcm16StereoFrames"]
            for value in frame
        ]
        for vector in fixture["vectors"]:
            actual = _loop_sample(
                samples,
                fixture["loopStartFrame"],
                fixture["loopEndFrameExclusive"],
                vector["phaseOffsetFrames"],
            )
            for measured, expected in zip(actual, vector["expectedStereo"]):
                self.assertAlmostEqual(measured, expected, places=12)

    def test_playlist_selection_golden_is_deterministic_and_sequential_ignores_weights(self) -> None:
        fixture = json.loads(
            (
                Path(__file__).parent
                / "fixtures"
                / "atlas-playlist-selection-v3.json"
            ).read_text(encoding="utf-8")
        )
        seed = playlist_seed(
            fixture["atlasFamilyId"],
            fixture["eventPath"],
            fixture["profileAudioSessionGeneration"],
            fixture["groupId"],
        )
        self.assertEqual(seed, int(fixture["expectedSeedUnsigned"]))

        for play_mode, expected_runs in (
            ("PlaylistPlayMode_SmartRandom", fixture["smartRandom"]),
            ("PlaylistPlayMode_PlaySequential", fixture["playSequential"]),
        ):
            state = seed
            cursor = 0
            last_selected_order = None
            for expected in expected_runs:
                result = select_playlist_member(
                    play_mode=play_mode,
                    members=fixture["members"],
                    group_trigger_chance_percent=None,
                    state=state,
                    sequential_cursor=cursor,
                    last_selected_order=last_selected_order,
                )
                self.assertEqual(result["selectedOrder"], expected["selectedOrder"])
                self.assertEqual(result["accepted"], expected["accepted"])
                self.assertEqual(
                    result["draws"],
                    [int(value) for value in expected["drawsUnsignedDecimal"]],
                )
                if "sequentialCursor" in expected:
                    self.assertEqual(result["sequentialCursor"], expected["sequentialCursor"])
                state = result["state"]
                cursor = result["sequentialCursor"]
                last_selected_order = result["lastSelectedOrder"]

    def test_playlist_member_chance_failure_keeps_selected_history_and_cursor(self) -> None:
        result = select_playlist_member(
            play_mode="PlaylistPlayMode_PlaySequential",
            members=[
                {"authoredOrder": 0, "weight": 1000.0, "triggerChancePercent": 0.0},
                {"authoredOrder": 1, "weight": 0.1, "triggerChancePercent": 100.0},
            ],
            group_trigger_chance_percent=None,
            state=1,
            sequential_cursor=0,
            last_selected_order=None,
        )

        self.assertFalse(result["accepted"])
        self.assertEqual(result["selectedOrder"], 0)
        self.assertEqual(result["lastSelectedOrder"], 0)
        self.assertEqual(result["sequentialCursor"], 1)
        self.assertEqual(result["reason"], "memberTriggerChanceRejected")

    def test_exact_axis_cell_keeps_zero_weight_neighbours_for_phase_preparation(self) -> None:
        corners = _bilinear_corners(
            [500.0, 1_000.0, 2_000.0], [0.0, 1.0], 1_000.0, 0.0
        )
        weights = {(rpm, throttle): weight for rpm, throttle, weight in corners}

        self.assertEqual(weights[(1_000.0, 0.0)], 1.0)
        self.assertEqual(weights[(2_000.0, 0.0)], 0.0)


class AtlasPackTests(unittest.TestCase):
    def test_engine_transition_shard_proof_discards_only_zero_weight_leaving_row(self) -> None:
        perspective = {
            "rpmAxis": [1_000.0, 2_000.0, 3_000.0],
            "throttleAxis": [0.0, 1.0],
            "nodes": [
                {
                    "rpm": rpm,
                    "throttle": throttle,
                    "shardName": f"rpm_{int(rpm)}.wav",
                    "modePrograms": {
                        "loadOnly": {"shardName": f"rpm_{int(rpm)}.wav"},
                        "coastOnly": {"shardName": f"rpm_{int(rpm)}.wav"},
                    },
                }
                for rpm in (1_000.0, 2_000.0, 3_000.0)
                for throttle in (0.0, 1.0)
            ],
        }

        proof = _engine_transition_shard_proof(perspective)

        self.assertEqual(proof["maximumUniqueMappedShards"], 2)
        worst = proof["worstCase"]
        self.assertEqual(worst["uniqueShardCount"], 2)
        self.assertEqual(
            set(worst["retainedShardNames"]),
            {"rpm_1000.wav", "rpm_2000.wav"},
        )

    def test_packer_shards_on_rpm_boundaries_and_indexes_absolute_loops(self) -> None:
        plan = build_atlas_plan(_recipe())
        with tempfile.TemporaryDirectory() as temporary_text:
            root = Path(temporary_text)
            nodes = root / "nodes"
            output = root / "packed"
            nodes.mkdir()
            capture_reports = []
            for perspective in plan["perspectives"].values():
                for index, node in enumerate(perspective["nodes"]):
                    path = nodes / node["temporaryAssetName"]
                    pcm = (b"\x01\x00\x02\x00" * (40 + index))
                    path.write_bytes(
                        _canonical_wav(
                            pcm,
                            sample_rate=48_000,
                            loop_bounds=(4, len(pcm) // 4 - 4),
                        )
                    )
                    capture_reports.append(
                        {
                            "temporaryAssetName": path.name,
                            "wavSha256": _sha256(path),
                            "modePrograms": {},
                        }
                    )
                    for mode, program_name in node["modeProgramTemporaryAssetNames"].items():
                        program_path = nodes / program_name
                        program_path.write_bytes(path.read_bytes())
                        capture_reports[-1]["modePrograms"][mode] = {
                            "temporaryAssetName": program_path.name,
                            "wavSha256": _sha256(program_path),
                        }
            realization = {
                "schema": ATLAS_REALIZATION_SCHEMA,
                "planSha256": plan["planSha256"],
                "atlasFamilyId": plan["id"],
                "sourceBankSha256Before": plan["bankSha256"],
                "sourceBankSha256After": plan["bankSha256"],
                "sourceBankUnchanged": True,
                "fullRun": True,
                "captureCount": len(capture_reports),
                "captures": capture_reports,
            }
            legacy_realization = copy.deepcopy(realization)
            legacy_realization["schema"] = "byd-full-event-atlas-realization-v1"
            with self.assertRaisesRegex(NrtRecipeError, "realization report is not"):
                pack_atlas(
                    plan,
                    legacy_realization,
                    nodes,
                    output,
                    maximum_shard_bytes=1_200,
                    delete_nodes=False,
                )
            missing_mode_program = copy.deepcopy(realization)
            del missing_mode_program["captures"][0]["modePrograms"]["coastOnly"]
            with self.assertRaisesRegex(NrtRecipeError, "lacks exact mode programs"):
                pack_atlas(
                    plan,
                    missing_mode_program,
                    nodes,
                    output,
                    maximum_shard_bytes=1_200,
                    delete_nodes=False,
                )
            mismatched_geometry = copy.deepcopy(realization)
            load_program = mismatched_geometry["captures"][0]["modePrograms"]["loadOnly"]
            load_program_path = nodes / load_program["temporaryAssetName"]
            original_load_program = load_program_path.read_bytes()
            load_program_path.write_bytes(
                _canonical_wav(
                    b"\x01\x00\x02\x00" * 48,
                    sample_rate=48_000,
                    loop_bounds=(4, 44),
                )
            )
            load_program["wavSha256"] = _sha256(load_program_path)
            with self.assertRaisesRegex(NrtRecipeError, "program geometry differs"):
                pack_atlas(
                    plan,
                    mismatched_geometry,
                    nodes,
                    output,
                    maximum_shard_bytes=1_200,
                    delete_nodes=False,
                )
            load_program_path.write_bytes(original_load_program)
            runtime, report = pack_atlas(
                plan,
                realization,
                nodes,
                output,
                maximum_shard_bytes=1_200,
                delete_nodes=False,
            )

            self.assertEqual(runtime["schema"], ATLAS_RUNTIME_SCHEMA)
            self.assertEqual(report["schema"], ATLAS_PACK_REPORT_SCHEMA)
            self.assertEqual(report["assetCount"], report["nodeCount"] * 3)
            self.assertTrue(runtime["shards"])
            self.assertTrue(
                all(set(shard) == {"name", "sha256", "bytes"} for shard in runtime["shards"])
            )
            self.assertEqual(
                len({shard["name"] for shard in runtime["shards"]}),
                len(runtime["shards"]),
            )
            self.assertLessEqual(report["largestShardBytes"], 1_244)
            self.assertGreater(len(report["shards"]), 2)
            runtime_payload = json.dumps(
                runtime,
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=True,
                allow_nan=False,
            ).encode("ascii") + b"\n"
            self.assertEqual(report["runtimeIndex"]["bytes"], len(runtime_payload))
            self.assertEqual(
                report["runtimeIndex"]["canonicalJsonNewlineSha256"],
                hashlib.sha256(runtime_payload).hexdigest(),
            )
            for perspective in runtime["perspectives"].values():
                self.assertEqual(set(perspective), {"rpmAxis", "throttleAxis", "nodes"})
                for node in perspective["nodes"]:
                    self.assertNotIn("temporaryAssetName", node)
                    self.assertLess(node["startFrame"], node["loopStartFrame"])
                    self.assertLess(node["loopStartFrame"], node["loopEndFrameExclusive"])
                    self.assertLessEqual(
                        node["loopEndFrameExclusive"], node["endFrameExclusive"]
                    )
                    self.assertEqual(set(node["modePrograms"]), {"loadOnly", "coastOnly"})
                    self.assertEqual(
                        {node["shardName"]},
                        {geometry["shardName"] for geometry in node["modePrograms"].values()},
                    )


if __name__ == "__main__":
    unittest.main()
