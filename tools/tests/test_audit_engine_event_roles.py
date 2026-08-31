from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from tools.profile_generation.audit_engine_event_roles import (
    _finite_role_evidence_is_exact,
    _perspective_partitions,
    audit_generated_plan,
    diagnostic_name_collisions,
    exact_lifetime_collisions,
    finite_scheduling_groups,
)


def _record(
    *,
    event_path: str,
    source_guid: str,
    diagnostic_name: str,
    lifetime: str,
    role: str,
    group_id: str,
) -> dict:
    return {
        "eventPath": event_path,
        "sourceGuid": source_guid,
        "diagnosticName": diagnostic_name,
        "auditRouteIdentity": f"route:{event_path}:{source_guid}",
        "authoredBindingKey": f"binding:{source_guid}",
        "schedulingGroupId": group_id,
        "lifetime": lifetime,
        "rawRole": role,
        "normalizedRole": role,
    }


class EngineEventRoleAuditTests(unittest.TestCase):
    def test_finite_role_evidence_is_bound_to_exact_authored_identity(self) -> None:
        record = _record(
            event_path="event:/fixture/engine_int",
            source_guid="finite",
            diagnostic_name="never-used-for-role",
            lifetime="oneShot",
            role="COAST",
            group_id="layer:finite",
        )
        record["roleEvidence"] = {
            "schema": "byd-full-event-engine-program-role-v2",
            "status": "PASS_EXACT_AUTHORED_BINDING_ROUTE_CLASSIFICATION",
            "classificationUsesDiagnosticName": False,
            "role": "COAST",
            "probeValues": [0.0, 0.5, 1.0],
            "bindingIdentity": {
                "eventPath": record["eventPath"],
                "sourceGuid": record["sourceGuid"],
                "authoredBindingKey": record["authoredBindingKey"],
                "schedulingGroupId": record["schedulingGroupId"],
            },
        }

        self.assertTrue(_finite_role_evidence_is_exact(record))
        record["roleEvidence"]["bindingIdentity"]["authoredBindingKey"] = (
            "binding:other"
        )
        self.assertFalse(_finite_role_evidence_is_exact(record))

    def test_empty_continuous_role_is_certified_without_inventing_a_binding(self) -> None:
        recipe = {
            "programs": {
                "cabin": {"eventPath": "event:/fixture/engine_int"},
                "exterior": {"eventPath": "event:/fixture/engine_ext"},
            }
        }
        records = [
            _record(
                event_path="event:/fixture/engine_int",
                source_guid="load",
                diagnostic_name="diagnostic-only",
                lifetime="continuous",
                role="LOAD",
                group_id="layer:load",
            ),
            _record(
                event_path="event:/fixture/engine_ext",
                source_guid="load-ext",
                diagnostic_name="diagnostic-only",
                lifetime="continuous",
                role="LOAD",
                group_id="layer:load-ext",
            ),
        ]

        partitions = _perspective_partitions(recipe, records)

        for partition in partitions:
            self.assertEqual(partition["emptyContinuousRoles"], ["COAST"])
            self.assertEqual(
                partition["emptyRoleCertifications"],
                [
                    {
                        "role": "COAST",
                        "status": "PASS_EXACT_GRAPH_EMPTY_ROLE_PARTITION",
                        "classificationBasis": (
                            "exactContinuousSourceGuidPartitionFromAuthoredRouteGainResponse"
                        ),
                        "sourceReassignmentOrSyntheticFallback": "forbidden",
                        "requiredCapture": "bitExactDigitalSilenceAtEveryNode",
                    }
                ],
            )

    def test_duplicate_diagnostic_name_does_not_merge_distinct_lifetimes_or_routes(self) -> None:
        records = [
            _record(
                event_path="event:/fixture/engine_int",
                source_guid="continuous",
                diagnostic_name="same_name",
                lifetime="continuous",
                role="LOAD",
                group_id="layer:continuous",
            ),
            _record(
                event_path="event:/fixture/engine_int",
                source_guid="finite",
                diagnostic_name="same_name",
                lifetime="oneShot",
                role="COAST",
                group_id="layer:finite",
            ),
        ]

        (collision,) = diagnostic_name_collisions(records)

        self.assertTrue(collision["differentAuthoredRoutes"])
        self.assertTrue(collision["crossLifetime"])
        self.assertTrue(collision["crossRole"])
        self.assertEqual(exact_lifetime_collisions(records), [])

    def test_finite_group_with_two_executable_roles_is_reported(self) -> None:
        records = [
            _record(
                event_path="event:/fixture/engine_ext",
                source_guid="load",
                diagnostic_name="one",
                lifetime="oneShot",
                role="LOAD",
                group_id="multi:shared",
            ),
            _record(
                event_path="event:/fixture/engine_ext",
                source_guid="coast",
                diagnostic_name="two",
                lifetime="oneShot",
                role="COAST",
                group_id="multi:shared",
            ),
        ]

        (group,) = finite_scheduling_groups(records)

        self.assertTrue(group["mixedExecutableRoles"])
        self.assertEqual(group["explicitExecutableRoles"], ["COAST", "LOAD"])

    def test_pre_channel_mask_plan_is_stale_and_omits_finite_engine_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "atlas-plan.json"
            plan_path.write_text(
                json.dumps(
                    {
                        "schema": "byd-full-event-atlas-plan-v1",
                        "perspectives": {
                            "cabin": {"initialNodes": [{"rpm": 1_000.0}]},
                            "exterior": {"initialNodes": [{"rpm": 1_000.0}]},
                        },
                        "effects": [],
                    }
                ),
                encoding="utf-8",
            )

            result = audit_generated_plan(plan_path, ["finite-source"])

        self.assertEqual(result["status"], "STALE")
        self.assertCountEqual(
            result["staleReasons"],
            (
                "missingEngineModeProgramContract",
                "missingPerNodeIndependentModeProgramCaptures",
                "missingIndependentFreshEventProgramCaptureMethod",
                "finiteEngineEventBindingsNotSeparatedFromContinuousBed",
            ),
        )


if __name__ == "__main__":
    unittest.main()
