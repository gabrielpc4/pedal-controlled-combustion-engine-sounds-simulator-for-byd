from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from sim.assetto import find_assetto_root
from sim.fmod_graph_roles import (
    POLICY_ALLOW_CANDIDATE,
    POLICY_AMBIGUOUS,
    POLICY_EXCLUDE,
    ROLE_ENGINE_FALLING,
    ROLE_ENGINE_INDEPENDENT,
    ROLE_ENGINE_RELEASE_AUDIBLE,
    ROLE_EXCLUDED_LOAD,
    ROLE_TRANSMISSION,
    ROLE_TURBO_CONTINUOUS,
    ROLE_TURBO_TRANSIENT,
    classify_bank_graph_sources,
    classify_catalog_graph_directory,
    curve_polarity,
)
from sim.fmod_sdk_audit import FmodSdkAuditError, audit_shipped_fmod_authoring
from tools.audit_fmod_bank_graph import BankGraphAuditError, audit_bank_graph
from tools.classify_fmod_source_roles import manual_oracle_backlog_document


def _controller(
    guid: str,
    owner: str,
    values: list[float],
    *,
    parameter: str = "throttle",
    property_index: int = 0,
) -> dict:
    return {
        "guid": guid,
        "curveGuid": guid,
        "propertyOwnerGuid": owner,
        "inputKind": "parameter",
        "inputParameterName": parameter,
        "propertyIndex": property_index,
        "points": [
            {
                "x": (
                    float(index) / float(len(values) - 1)
                    if len(values) > 1
                    else 0.5
                ),
                "y": float(value),
            }
            for index, value in enumerate(values)
        ],
    }


def _waveform(
    guid: str,
    *,
    loop_count: int,
    controller_guid: str | None = None,
    sample_name: str = "misleading_load_coast_idle_name",
) -> dict:
    route = f"route-{guid}"
    return {
        "guid": guid,
        "kind": "WaveformInstrumentNode",
        "sample": {"name": sample_name},
        "baseProperties": {"loopCount": loop_count, "routableGuid": route},
        "controllerGuids": [controller_guid] if controller_guid else [],
    }


def _multi(
    guid: str,
    child_guid: str,
    *,
    controller_guid: str | None = None,
) -> dict:
    return {
        "guid": guid,
        "kind": "MultiInstrumentNode",
        "sample": None,
        "baseProperties": {"loopCount": -1, "routableGuid": f"route-{guid}"},
        "controllerGuids": [controller_guid] if controller_guid else [],
        "childInstruments": [{"guid": child_guid, "weight": 1.0}],
    }


def _report(instruments: list[dict], controllers: list[dict], events: list[dict]) -> dict:
    return {
        "schema": "ac-fmod-bank-graph-audit-v3",
        "bank": {"fileVersion": 0x50, "sha256": "a" * 64},
        "instruments": instruments,
        "controllers": controllers,
        "events": events,
    }


def _event(
    suffix: str,
    guids: list[str],
    *,
    complete: bool = True,
    parameter_placements: list[dict] | None = None,
) -> dict:
    return {
        "path": f"event:/cars/test/{suffix}",
        "reachableInstrumentGuids": guids,
        "mappingComplete": complete,
        "parameterPlacements": parameter_placements or [],
        "timelinePlacements": [],
    }


class CurvePolarityTests(unittest.TestCase):
    def test_uses_every_point_instead_of_endpoints_only(self) -> None:
        self.assertEqual(
            curve_polarity(
                [
                    {"x": 0.0, "y": 0.0},
                    {"x": 0.5, "y": 12.0},
                    {"x": 1.0, "y": 1.0},
                ]
            ),
            "mixed",
        )
        self.assertEqual(
            curve_polarity([{"x": 0.0, "y": -42.0}, {"x": 1.0, "y": 0.0}]),
            "rising",
        )
        self.assertEqual(
            curve_polarity([{"x": 0.0, "y": 0.0}, {"x": 1.0, "y": -42.0}]),
            "falling",
        )


class SourceRoleClassifierTests(unittest.TestCase):
    def test_engine_polarity_is_scoped_by_event_topology_not_filename(self) -> None:
        load = _waveform(
            "load-guid",
            loop_count=-1,
            controller_guid="load-curve",
            sample_name="definitely_coast_off_idle",
        )
        coast = _waveform(
            "coast-guid",
            loop_count=-1,
            controller_guid="coast-curve",
            sample_name="definitely_load_on",
        )
        transmission = _waveform(
            "transmission-guid",
            loop_count=-1,
            controller_guid="transmission-curve",
            sample_name="engine_load_on",
        )
        controllers = [
            _controller("load-curve", "route-load-guid", [-42.0, 0.0]),
            _controller("coast-curve", "route-coast-guid", [0.0, -42.0]),
            _controller("transmission-curve", "route-transmission-guid", [-42.0, 0.0]),
        ]
        result = classify_bank_graph_sources(
            _report(
                [load, coast, transmission],
                controllers,
                [
                    _event("engine_ext", ["load-guid", "coast-guid"]),
                    _event("transmission", ["transmission-guid"]),
                ],
            )
        )
        rows = {row["sourceGuid"]: row for row in result["sources"]}
        self.assertEqual(rows["load-guid"]["role"], ROLE_EXCLUDED_LOAD)
        self.assertEqual(rows["load-guid"]["policy"], POLICY_EXCLUDE)
        self.assertEqual(rows["coast-guid"]["role"], ROLE_ENGINE_FALLING)
        self.assertEqual(rows["coast-guid"]["policy"], POLICY_ALLOW_CANDIDATE)
        self.assertEqual(rows["transmission-guid"]["role"], ROLE_TRANSMISSION)
        self.assertNotIn("definitely_", json.dumps(result))

    def test_materially_suppressed_mixed_curve_is_load_and_bad_ownership_fails_closed(self) -> None:
        mixed = _waveform("mixed", loop_count=-1, controller_guid="mixed-curve")
        wrong_owner = _waveform("wrong-owner", loop_count=-1, controller_guid="wrong-curve")
        result = classify_bank_graph_sources(
            _report(
                [mixed, wrong_owner],
                [
                    _controller("mixed-curve", "route-mixed", [-42.0, 0.0, -6.0]),
                    _controller("wrong-curve", "somebody-elses-route", [-42.0, 0.0]),
                ],
                [_event("engine_int", ["mixed", "wrong-owner"])],
            )
        )
        rows = {row["sourceGuid"]: row for row in result["sources"]}
        self.assertEqual(rows["mixed"]["policy"], POLICY_EXCLUDE)
        self.assertIn(
            "continuousEngineMixedCurveMateriallySuppressedAtRelease",
            rows["mixed"]["reasons"],
        )
        self.assertEqual(rows["wrong-owner"]["policy"], POLICY_AMBIGUOUS)
        self.assertIn("referencedControllerOwnerMismatch", rows["wrong-owner"]["reasons"])

    def test_turbo_lifetime_selects_continuous_or_transient_candidate(self) -> None:
        continuous = _waveform("turbo-loop", loop_count=-1)
        transient = _waveform("turbo-shot", loop_count=0)
        result = classify_bank_graph_sources(
            _report(
                [continuous, transient],
                [],
                [_event("turbo", ["turbo-loop", "turbo-shot"])],
            )
        )
        rows = {row["sourceGuid"]: row for row in result["sources"]}
        self.assertEqual(rows["turbo-loop"]["role"], ROLE_TURBO_CONTINUOUS)
        self.assertEqual(rows["turbo-loop"]["eventClass"], "continuousCore")
        self.assertEqual(rows["turbo-shot"]["role"], ROLE_TURBO_TRANSIENT)
        self.assertEqual(rows["turbo-shot"]["eventClass"], "oneShotCore")

    def test_engine_one_shot_still_obeys_authored_throttle_polarity(self) -> None:
        source = _waveform("engine-shot", loop_count=0, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-engine-shot", [-42.0, 0.0])],
                [
                    _event(
                        "engine_ext",
                        ["engine-shot"],
                        parameter_placements=[
                            {
                                "instrumentGuid": "engine-shot",
                                "parameterName": "rpms",
                                "start": 1000.0,
                                "end": 8000.0,
                                "includeEnd": True,
                            }
                        ],
                    )
                ],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["role"], ROLE_EXCLUDED_LOAD)
        self.assertEqual(row["policy"], POLICY_EXCLUDE)
        self.assertEqual(row["eventClass"], "oneShotEngine")

    def test_parent_multi_controller_and_placement_are_source_evidence(self) -> None:
        child = _waveform("child", loop_count=0)
        parent = _multi("parent", "child", controller_guid="parent-curve")
        result = classify_bank_graph_sources(
            _report(
                [child, parent],
                [_controller("parent-curve", "route-parent", [-42.0, 0.0])],
                [
                    _event(
                        "engine_int",
                        ["child"],
                        parameter_placements=[
                            {
                                "instrumentGuid": "parent",
                                "parameterName": "rpms",
                                "start": 800.0,
                                "end": 7000.0,
                                "includeEnd": True,
                            }
                        ],
                    )
                ],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["role"], ROLE_EXCLUDED_LOAD)
        self.assertEqual(row["throttleVolume"]["controllers"][0]["scope"], "ancestor")
        trigger = row["decisionEvidence"]["eventMemberships"][0]["triggerTopology"]
        self.assertEqual(trigger["placements"][0]["scope"], "ancestor")

    def test_continuous_throttle_gate_excludes_release_even_with_falling_curve(self) -> None:
        source = _waveform("gated", loop_count=-1, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-gated", [0.0, -42.0])],
                [
                    _event(
                        "engine_ext",
                        ["gated"],
                        parameter_placements=[
                            {
                                "instrumentGuid": "gated",
                                "parameterName": "throttle",
                                "start": 0.2,
                                "end": 1.0,
                                "includeEnd": True,
                            }
                        ],
                    )
                ],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["role"], ROLE_EXCLUDED_LOAD)
        self.assertIn(
            "continuousEngineThrottlePlacementExcludesReleasedThrottle",
            row["reasons"],
        )

    def test_mild_nonmonotonic_continuous_curve_remains_release_audible(self) -> None:
        source = _waveform("texture", loop_count=-1, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-texture", [-4.0, -10.0, 0.0])],
                [_event("engine_ext", ["texture"])],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["role"], ROLE_ENGINE_RELEASE_AUDIBLE)
        self.assertEqual(row["policy"], POLICY_ALLOW_CANDIDATE)
        self.assertLess(row["throttleVolume"]["releaseSuppressionDb"], 12.0)

    def test_release_audible_aggregate_omits_authored_silence_floor(self) -> None:
        silent = _waveform("silent", loop_count=-1, controller_guid="silent-curve")
        silent["baseProperties"]["volumeDb"] = -80.0
        audible = _waveform("audible", loop_count=-1, controller_guid="audible-curve")
        audible["baseProperties"]["volumeDb"] = -28.0
        result = classify_bank_graph_sources(
            _report(
                [silent, audible],
                [
                    _controller("silent-curve", "route-silent", [0.0, -42.0]),
                    _controller("audible-curve", "route-audible", [0.0, -42.0]),
                ],
                [_event("engine_ext", ["silent", "audible"])],
            )
        )
        self.assertEqual(
            result["counts"][
                "allowedReleasedThrottleContinuousEngineSourceInstruments"
            ],
            1,
        )

    def test_one_shot_rising_curve_without_trigger_topology_fails_closed(self) -> None:
        source = _waveform("unknown-shot", loop_count=0, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-unknown-shot", [-42.0, 0.0])],
                [_event("engine_ext", ["unknown-shot"])],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["policy"], POLICY_AMBIGUOUS)
        self.assertIn("oneShotEngineTriggerTopologyMissing", row["reasons"])
        self.assertEqual(
            result["manualOracleBacklog"][0]["requiredOracle"]["kind"],
            "authoringTopologyOrSilentRuntimeTrace",
        )

    def test_one_shot_mixed_curve_with_pedal_gate_requires_direction_oracle(self) -> None:
        source = _waveform("direction-shot", loop_count=0, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-direction-shot", [-3.0, 0.0, -6.0])],
                [
                    _event(
                        "engine_int",
                        ["direction-shot"],
                        parameter_placements=[
                            {
                                "instrumentGuid": "direction-shot",
                                "parameterName": "throttle",
                                "start": 0.33,
                                "end": 1.0,
                                "includeEnd": True,
                            }
                        ],
                    )
                ],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["policy"], POLICY_AMBIGUOUS)
        self.assertIn(
            "oneShotMixedCurvePedalGateDirectionSemanticsUnknown", row["reasons"]
        )
        backlog = result["manualOracleBacklog"][0]
        self.assertEqual(backlog["requiredOracle"]["kind"], "silentRuntimeDirectionSweep")
        self.assertNotIn("misleading_load_coast_idle_name", json.dumps(backlog))
        self.assertNotIn('"name":', json.dumps(backlog))

    def test_single_point_throttle_curve_is_constant_not_underspecified(self) -> None:
        source = _waveform("constant", loop_count=-1, controller_guid="curve")
        result = classify_bank_graph_sources(
            _report(
                [source],
                [_controller("curve", "route-constant", [-7.0])],
                [_event("engine_int", ["constant"])],
            )
        )
        row = result["sources"][0]
        self.assertEqual(row["role"], ROLE_ENGINE_INDEPENDENT)
        self.assertEqual(row["throttleVolume"]["trend"], "flat")

    def test_engine_without_throttle_volume_controller_is_allowed_but_unresolved(self) -> None:
        idle_or_texture = _waveform("idle-or-texture", loop_count=-1)
        shift_or_overrun = _waveform("shift-or-overrun", loop_count=0)
        result = classify_bank_graph_sources(
            _report(
                [idle_or_texture, shift_or_overrun],
                [],
                [_event("engine_int", ["idle-or-texture", "shift-or-overrun"])],
            )
        )
        rows = {row["sourceGuid"]: row for row in result["sources"]}
        self.assertEqual(rows["idle-or-texture"]["role"], ROLE_ENGINE_INDEPENDENT)
        self.assertEqual(rows["idle-or-texture"]["policy"], POLICY_ALLOW_CANDIDATE)
        self.assertIn("IDLE", rows["idle-or-texture"]["candidateManifestRoles"])
        self.assertEqual(rows["shift-or-overrun"]["role"], ROLE_ENGINE_INDEPENDENT)
        self.assertIn("ENGINE_TRANSIENT", rows["shift-or-overrun"]["candidateManifestRoles"])

    def test_incomplete_or_unknown_event_mapping_fails_closed(self) -> None:
        incomplete = _waveform("incomplete", loop_count=0)
        unknown = _waveform("unknown", loop_count=0)
        result = classify_bank_graph_sources(
            _report(
                [incomplete, unknown],
                [],
                [
                    _event("backfire_ext", ["incomplete"], complete=False),
                    _event("new_unmapped_role", ["unknown"]),
                ],
            )
        )
        rows = {row["sourceGuid"]: row for row in result["sources"]}
        self.assertEqual(rows["incomplete"]["policy"], POLICY_AMBIGUOUS)
        self.assertIn("eventMappingIncomplete", rows["incomplete"]["reasons"])
        self.assertEqual(rows["unknown"]["policy"], POLICY_AMBIGUOUS)
        self.assertIn("unknownEventSuffix:new_unmapped_role", rows["unknown"]["reasons"])

    def test_catalog_summary_preserves_family_ambiguity(self) -> None:
        source = _waveform("source", loop_count=0)
        report = _report([source], [], [_event("unknown", ["source"])])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            families = root / "families"
            families.mkdir()
            (families / f"{'a' * 64}.json").write_text(
                json.dumps(report), encoding="utf-8"
            )
            result = classify_catalog_graph_directory(root)
        self.assertEqual(result["counts"]["families"], 1)
        self.assertEqual(result["counts"]["ambiguousSourceInstruments"], 1)
        self.assertEqual(result["counts"]["familiesWithAmbiguousSources"], 1)


class FullCatalogRoleClassifierTests(unittest.TestCase):
    def test_full_catalog_stats_backlog_and_output_are_deterministic(self) -> None:
        root = (
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3"
        )
        if len(list((root / "families").glob("*.json"))) != 153:
            raise unittest.SkipTest("complete 153-family graph cache is absent")

        first = classify_catalog_graph_directory(root)
        second = classify_catalog_graph_directory(root)
        first_bytes = json.dumps(
            first, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        second_bytes = json.dumps(
            second, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        self.assertEqual(
            hashlib.sha256(first_bytes).digest(),
            hashlib.sha256(second_bytes).digest(),
        )
        self.assertEqual(
            first["counts"],
            {
                "families": 153,
                "sourceInstruments": 9450,
                "structuralInstruments": 415,
                "engineSourceInstruments": 4839,
                "sourceDecisionRecords": 9450,
                "ancestorThrottleControlledSourceInstruments": 1626,
                "ancestorThrottleControlledEngineSourceInstruments": 300,
                "allowedReleasedThrottleContinuousEngineSourceInstruments": 1719,
                "ambiguousSourceInstruments": 2,
                "familiesWithAmbiguousSources": 1,
                "familiesWithIncompleteLoadExclusion": 1,
                "familiesWithoutAllowedReleasedThrottleContinuousEngineSource": 0,
                "exactRoleCandidateSourceInstruments": 4153,
            },
        )
        self.assertEqual(
            first["policyCounts"],
            {
                "allowCandidate": 4430,
                "ambiguous": 2,
                "exclude": 2969,
                "outOfScope": 2049,
            },
        )
        self.assertEqual(
            first["rolePolicyLifetimeCounts"],
            [
                {"role": "AMBIGUOUS", "policy": "ambiguous", "lifetime": "oneShot", "count": 2},
                {"role": "ENGINE_FALLING_CANDIDATE", "policy": "allowCandidate", "lifetime": "continuous", "count": 1505},
                {"role": "ENGINE_FALLING_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 136},
                {"role": "ENGINE_RELEASE_AUDIBLE_CANDIDATE", "policy": "allowCandidate", "lifetime": "continuous", "count": 103},
                {"role": "ENGINE_THROTTLE_INDEPENDENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "continuous", "count": 112},
                {"role": "ENGINE_THROTTLE_INDEPENDENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 1},
                {"role": "ENGINE_TRANSIENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 11},
                {"role": "EXCLUDED_LOAD", "policy": "exclude", "lifetime": "continuous", "count": 2652},
                {"role": "EXCLUDED_LOAD", "policy": "exclude", "lifetime": "oneShot", "count": 317},
                {"role": "GEAR_GRIND", "policy": "allowCandidate", "lifetime": "oneShot", "count": 98},
                {"role": "GEAR_SHIFT_TRANSIENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 685},
                {"role": "LIMITER", "policy": "allowCandidate", "lifetime": "continuous", "count": 18},
                {"role": "LIMITER", "policy": "allowCandidate", "lifetime": "oneShot", "count": 55},
                {"role": "OUT_OF_SCOPE_NON_CORE", "policy": "outOfScope", "lifetime": "continuous", "count": 1354},
                {"role": "OUT_OF_SCOPE_NON_CORE", "policy": "outOfScope", "lifetime": "oneShot", "count": 695},
                {"role": "OVERRUN_TRANSIENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 1363},
                {"role": "TRANSMISSION", "policy": "allowCandidate", "lifetime": "continuous", "count": 106},
                {"role": "TURBO_CONTINUOUS_CANDIDATE", "policy": "allowCandidate", "lifetime": "continuous", "count": 66},
                {"role": "TURBO_TRANSIENT_CANDIDATE", "policy": "allowCandidate", "lifetime": "oneShot", "count": 171},
            ],
        )
        self.assertEqual(
            first[
                "familiesWithoutAllowedReleasedThrottleContinuousEngineSource"
            ],
            [],
        )
        self.assertEqual(
            {
                (item["familyId"], item["sourceGuid"])
                for item in first["manualOracleBacklog"]
            },
            {
                (
                    "fc3e9f0b32def23d65122ea9bf24e6f0741d9e5b719a8c304b2820a4a9017391",
                    "072761f3-f125-4e61-99c8-c9b00439e6ed",
                ),
                (
                    "fc3e9f0b32def23d65122ea9bf24e6f0741d9e5b719a8c304b2820a4a9017391",
                    "9e5a0153-37a0-42fa-9c94-ae6bf7e6e12e",
                ),
            },
        )
        backlog_document = manual_oracle_backlog_document(first)
        self.assertEqual(
            backlog_document["counts"], {"entries": 2, "families": 1}
        )
        self.assertFalse(backlog_document["basis"]["usesSampleNames"])
        self.assertEqual(
            [
                (row["familyId"], row["sourceGuid"])
                for row in first["sourceDecisions"]
            ],
            sorted(
                (row["familyId"], row["sourceGuid"])
                for row in first["sourceDecisions"]
            ),
        )
        self.assertNotIn('"name":', json.dumps(first["sourceDecisions"]))


class ShippedTatuusRoleOracleTests(unittest.TestCase):
    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest()

    def test_binary_graph_matches_all_shipped_authoring_instrument_identities(self) -> None:
        try:
            assetto_root = find_assetto_root()
            authoring = audit_shipped_fmod_authoring(assetto_root)
        except (FileNotFoundError, FmodSdkAuditError) as exc:
            raise unittest.SkipTest(str(exc)) from exc
        bank = assetto_root / "content" / "cars" / "tatuusfa1" / "sfx" / "tatuusfa1.bank"
        family_id = self._sha256(bank)
        cached = (
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3"
            / "families"
            / f"{family_id}.json"
        )
        try:
            graph = (
                json.loads(cached.read_text(encoding="utf-8"))
                if cached.is_file()
                else audit_bank_graph(bank, assetto_root=assetto_root)
            )
        except (OSError, BankGraphAuditError) as exc:
            raise unittest.SkipTest(str(exc)) from exc

        expected_excluded = {
            instrument["id"].strip("{}").casefold()
            for event in authoring["events"]
            for group in event["groups"]
            if group["manifestRole"] == "EXCLUDED_LOAD"
            for instrument in group["instruments"]
        }
        expected_allowed = {
            instrument["id"].strip("{}").casefold()
            for event in authoring["events"]
            for group in event["groups"]
            if group["manifestRole"] in {"COAST", "EXHAUST"}
            for instrument in group["instruments"]
        }
        classified = classify_bank_graph_sources(graph)
        actual_excluded = {
            row["sourceGuid"]
            for row in classified["sources"]
            if row["role"] == ROLE_EXCLUDED_LOAD
        }
        actual_allowed = {
            row["sourceGuid"]
            for row in classified["sources"]
            if row["role"] == ROLE_ENGINE_FALLING
        }
        self.assertEqual(len(expected_excluded), 14)
        self.assertEqual(len(expected_allowed), 12)
        self.assertEqual(actual_excluded, expected_excluded)
        self.assertEqual(actual_allowed, expected_allowed)
        self.assertTrue(classified["loadExclusionComplete"])


if __name__ == "__main__":
    unittest.main()
