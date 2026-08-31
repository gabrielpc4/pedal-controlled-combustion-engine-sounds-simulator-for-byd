from __future__ import annotations

import copy
import json
import math
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

from sim.aclib import (
    ManifestValidationError,
    _validate_limiter_event_program_policy,
    _validate_turbo_control_curve,
    validate_aclib,
    validate_manifest,
    validate_release_manifest,
)
from sim.aclib_catalog import canonical_json_bytes
from sim.fmod_graph_roles import classify_bank_graph_sources
from sim.fmod_bank_isolation import fully_muted_multi_instrument_guids
from sim.assetto import find_assetto_root
from sim.fmod_renderer import SilentFmodReferenceRenderer
from tools.aclib_compiler import (
    assert_repackage_family_subtree_unchanged,
    compile_family_child_command,
    _load_or_create_compile_all_status,
    _new_compile_all_status,
    deterministic_source_selection_take_limit,
    is_release_audible_peak_dbfs,
    repackage_aclib_for_capture_plan,
)
from tools.aclib_release import (
    CURVE_PROBE_MAX_RATIO_ERROR_DB,
    DEFAULT_RELEASE_PLAN,
    DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
    DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    ReleaseCapturePlanError,
    _off_capture_value,
    _release_family,
    _validate_limiter_event_policy,
    build_hybrid_audio_control_audit,
    load_shift_silence_source_verifications,
    load_property_one_source_verifications,
    load_turbo_transient_source_verifications,
    runtime_curve_probe_controls,
    validate_release_capture_plan,
    validate_runtime_curve_measurements,
    verify_recipe_against_graph,
)


TATUUS_FAMILY_ID = (
    "668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab"
)


def _curved_recipe() -> dict:
    return {
        "sourceCurveSha256": "a" * 64,
        "sourceGuid": "11111111-1111-1111-1111-111111111111",
        "parameters": {"rpms": 5000.0, "throttle": 1.0},
        "rootRpm": 5000.0,
        "rpmCurve": [[1000.0, 0.2], [5000.0, 1.0], [9000.0, 0.4]],
        "gainCurve": [[0.0, 0.5], [1.0, 1.0]],
    }


class RuntimeCurveGateTests(unittest.TestCase):
    def test_serialized_step_edges_are_never_runtime_probe_points(self) -> None:
        inclusive_rising = [
            [0.0, 0.0],
            [3999.985, 0.0],
            [4000.0, 1.0],
            [15000.0, 1.0],
        ]
        exclusive_rising = [
            [0.0, 0.0],
            [4000.0, 0.0],
            [4000.015, 1.0],
            [15000.0, 1.0],
        ]
        for curve, physical_minimum in (
            (inclusive_rising, 4000.0),
            (exclusive_rising, 4000.015),
        ):
            probe_x, probe_y = _off_capture_value(
                curve, 8175.0, minimum_amplitude=0.25
            )
            self.assertGreaterEqual(probe_x, physical_minimum)
            self.assertEqual(probe_y, 1.0)

        falling = [
            [0.0, 1.0],
            [11000.0, 1.0],
            [11000.015, 0.0],
            [15000.0, 0.0],
        ]
        falling_x, falling_y = _off_capture_value(
            falling, 8175.0, minimum_amplitude=0.25
        )
        self.assertLessEqual(falling_x, 11000.0)
        self.assertEqual(falling_y, 1.0)

    def test_wider_authored_ramp_remains_a_curve_probe_candidate(self) -> None:
        genuine_ramp = [
            [0.0, 0.0],
            [4000.0, 0.0],
            [4016.0, 1.0],
            [15000.0, 1.0],
        ]
        self.assertEqual(
            _off_capture_value(
                genuine_ramp, 8175.0, minimum_amplitude=0.25
            ),
            (4008.0, 0.5),
        )

    def test_deterministic_native_controls_and_exact_ratio_pass(self) -> None:
        recipe = _curved_recipe()
        probes = runtime_curve_probe_controls(recipe)
        self.assertEqual([item["axis"] for item in probes], ["speed", "accelerator"])
        self.assertEqual(probes[0]["parameters"]["throttle"], 1.0)
        self.assertEqual(probes[1]["parameters"]["rpms"], 5000.0)
        baseline = 0.25
        evidence = validate_runtime_curve_measurements(
            recipe,
            baseline,
            [
                {"axis": item["axis"], "rms": baseline * item["predictedRmsRatio"]}
                for item in probes
            ],
        )
        self.assertTrue(evidence["passed"])
        self.assertTrue(all(item["absoluteRatioErrorDb"] < 1.0e-9 for item in evidence["probes"]))

    def test_ratio_error_above_bound_fails_closed(self) -> None:
        recipe = _curved_recipe()
        probes = runtime_curve_probe_controls(recipe)
        excess = 10.0 ** ((CURVE_PROBE_MAX_RATIO_ERROR_DB + 0.1) / 20.0)
        with self.assertRaises(ReleaseCapturePlanError):
            validate_runtime_curve_measurements(
                recipe,
                0.25,
                [
                    {
                        "axis": item["axis"],
                        "rms": 0.25 * item["predictedRmsRatio"] * excess,
                    }
                    for item in probes
                ],
            )

    def test_transmission_probe_converts_manifest_rpm_to_native_radians(self) -> None:
        recipe = _curved_recipe()
        recipe["parameters"] = {"drivetrain_speed": 5000.0 * 2.0 * math.pi / 60.0, "throttle": 1.0}
        speed = runtime_curve_probe_controls(recipe)[0]
        self.assertAlmostEqual(
            speed["parameters"]["drivetrain_speed"],
            speed["manifestRpm"] * 2.0 * math.pi / 60.0,
            places=7,
        )

    def test_release_audibility_floor_is_strict_and_finite(self) -> None:
        self.assertTrue(is_release_audible_peak_dbfs(-95.999999))
        self.assertTrue(is_release_audible_peak_dbfs(-49.095))
        self.assertFalse(is_release_audible_peak_dbfs(-96.0))
        self.assertFalse(is_release_audible_peak_dbfs(-96.000001))
        self.assertFalse(is_release_audible_peak_dbfs(-math.inf))
        self.assertFalse(is_release_audible_peak_dbfs(math.nan))


class SourceProgramIsolationTests(unittest.TestCase):
    def test_smart_random_take_limit_scales_with_authored_tree_span(self) -> None:
        graph = {
            "instruments": [
                {
                    "guid": "parent-a",
                    "childInstruments": [{"guid": str(index)} for index in range(6)],
                },
                {
                    "guid": "parent-b",
                    "childInstruments": [{"guid": str(index)} for index in range(3)],
                },
            ]
        }
        row = {
            "decisionEvidence": {
                "instrumentAncestry": [
                    {"instrumentGuid": "target", "kind": "WaveformInstrumentNode"},
                    {"instrumentGuid": "parent-a", "kind": "MultiInstrumentNode"},
                    {"instrumentGuid": "parent-b", "kind": "MultiInstrumentNode"},
                ]
            }
        }
        self.assertEqual(
            deterministic_source_selection_take_limit(row, graph), 32 * 18
        )
        row["decisionEvidence"]["instrumentAncestry"] = row[
            "decisionEvidence"
        ]["instrumentAncestry"][:1]
        self.assertEqual(deterministic_source_selection_take_limit(row, graph), 64)

    def test_only_outermost_fully_muted_multi_program_is_disabled(self) -> None:
        def node(
            guid: str, kind: str, children: list[str] | None = None
        ) -> dict:
            return {
                "guid": guid,
                "kind": kind,
                "childInstruments": [
                    {"guid": child, "weight": 1.0}
                    for child in (children or [])
                ],
                "baseProperties": {"triggerChancePercent": 100.0},
            }

        graph = {
            "instruments": [
                node("target", "WaveformInstrumentNode"),
                node("muted-a", "WaveformInstrumentNode"),
                node("muted-b", "WaveformInstrumentNode"),
                node("inner", "MultiInstrumentNode", ["muted-a", "muted-b"]),
                node("outer-muted", "MultiInstrumentNode", ["inner"]),
                node("mixed", "MultiInstrumentNode", ["target", "outer-muted"]),
            ]
        }
        self.assertEqual(
            fully_muted_multi_instrument_guids(
                graph, {"muted-a", "muted-b"}
            ),
            {"outer-muted"},
        )

    def test_cycle_in_instrument_children_fails_closed(self) -> None:
        graph = {
            "instruments": [
                {
                    "guid": "a",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [{"guid": "b", "weight": 1.0}],
                    "baseProperties": {"triggerChancePercent": 100.0},
                },
                {
                    "guid": "b",
                    "kind": "MultiInstrumentNode",
                    "childInstruments": [{"guid": "a", "weight": 1.0}],
                    "baseProperties": {"triggerChancePercent": 100.0},
                },
            ]
        }
        with self.assertRaisesRegex(ValueError, "child cycle"):
            fully_muted_multi_instrument_guids(graph, {"never"})


class TurboCaptureRelativeCurveTests(unittest.TestCase):
    def test_capture_relative_gain_may_exceed_unity_without_clamping(self) -> None:
        curve = [[0.0, 0.5], [0.45509338, 1.0], [1.0, 1.08536057]]
        _validate_turbo_control_curve(curve, "turbo", "BOOST", rate=False)
        exact_bound = [[0.0, 1.0], [1.0, 38.0]]
        _validate_turbo_control_curve(
            exact_bound, "turbo", "BOOST", rate=False
        )
        above_bound = copy.deepcopy(exact_bound)
        above_bound[-1][1] = math.nextafter(38.0, math.inf)
        with self.assertRaises(ManifestValidationError):
            _validate_turbo_control_curve(
                above_bound, "turbo", "BOOST", rate=False
            )
        changed = copy.deepcopy(curve)
        changed[-1][1] = -0.001
        with self.assertRaises(ManifestValidationError):
            _validate_turbo_control_curve(changed, "turbo", "BOOST", rate=False)


class CompileAllResumeTests(unittest.TestCase):
    def test_compile_all_family_command_is_a_fresh_release_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text)
            args = SimpleNamespace(
                catalog=str(temporary / "catalog.json"),
                graph_root=str(temporary / "graphs"),
                limiter_proof=str(temporary / "limiter.json"),
                shift_silence_proof=str(temporary / "shift.json"),
                turbo_transient_proof=str(temporary / "turbo.json"),
                priority_proof=str(temporary / "priority.json"),
                continuous_disposition_proof=str(
                    temporary / "continuous.json"
                ),
                property_one_proof=str(temporary / "property-one.json"),
                tool_cache=str(temporary / "tools"),
            )
            command = compile_family_child_command(
                args,
                temporary / "assetto",
                "f" * 64,
                temporary / "plan.json",
                temporary / "output",
            )
        self.assertEqual(command[2:4], ["compile-family", "f" * 64])
        self.assertEqual(command.count("--release"), 1)
        self.assertIn("--assetto-root", command)
        self.assertIn("--capture-plan", command)
        self.assertIn("--output-root", command)
        self.assertTrue(all(Path(value).is_absolute() for value in command[5::2]))

    def test_family_subtree_change_forbids_plan_only_repackage(self) -> None:
        old = {
            "schemaVersion": 2,
            "catalogSha256": "c" * 64,
            "families": [
                {
                    "familyId": "f" * 64,
                    "recipes": [{"id": "idle", "rootRpm": 900.0}],
                }
            ],
        }
        final = copy.deepcopy(old)
        final["newGlobalProof"] = {"sha256": "d" * 64}
        assert_repackage_family_subtree_unchanged(old, final, "f" * 64)
        final["families"][0]["recipes"][0]["rootRpm"] = 901.0
        with self.assertRaisesRegex(ValueError, "PCM rerender required"):
            assert_repackage_family_subtree_unchanged(old, final, "f" * 64)

    def test_interrupted_family_becomes_pending_without_deleting_success(self) -> None:
        plan = {
            "catalogSha256": "b" * 64,
            "families": [
                {"familyId": "1" * 64, "representativeCarId": "one"},
                {"familyId": "2" * 64, "representativeCarId": "two"},
            ],
        }
        plan_sha = "c" * 64
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "status.json"
            status = _new_compile_all_status(plan, plan_sha)
            status["families"]["1" * 64]["status"] = "succeeded"
            status["families"]["2" * 64]["status"] = "running"
            path.write_bytes(canonical_json_bytes(status) + b"\n")
            resumed = _load_or_create_compile_all_status(
                path, plan, plan_sha, reset=False
            )
            self.assertEqual(resumed["families"]["1" * 64]["status"], "succeeded")
            self.assertEqual(resumed["families"]["2" * 64]["status"], "pending")
            self.assertEqual(resumed["summary"]["succeeded"], 1)
            self.assertEqual(resumed["summary"]["pending"], 1)


class InstalledFreshRendererIsolationTests(unittest.TestCase):
    FAMILY_ID = (
        "31dc3cc11ed9d819d0b10ffa44f0b9ae2930e9a1777db3ecf76c445b573d236f"
    )
    RECIPE_ID = "texture_9f260f53c08446df"

    def test_recorded_identity_zero_is_outside_placement_not_lifecycle_state(self) -> None:
        ledger = Path(
            r"D:\Users\sgabr\BYDMotorSoundData\aclib"
            r"\compile-retry-selection-c-75c-v2.json"
        )
        isolated_bank = Path(
            r"D:\Users\sgabr\BYDMotorSoundData\direct-source-diagnostic-v1"
            rf"\{self.RECIPE_ID}.bank"
        )
        if not ledger.is_file() or not isolated_bank.is_file():
            self.skipTest("recorded FMOD lifecycle-contamination evidence is unavailable")
        old = json.loads(ledger.read_text(encoding="utf-8"))["families"][self.FAMILY_ID]
        self.assertEqual(old["status"], "failed")
        self.assertIn("expected one source identity, observed 0", old["error"])
        renderer = SilentFmodReferenceRenderer(
            find_assetto_root(), fresh_process_per_render=True
        )
        outside = isolated_bank.with_name(f"{self.RECIPE_ID}-unittest-outside.wav")
        outside_render = renderer.render_event(
            isolated_bank,
            "engine_int",
            outside,
            parameters={"rpms": 3999.9925, "throttle": 0.0},
            duration_frames=48000,
            warmup_frames=24000,
        )
        self.assertEqual(outside_render.scheduled_sound_names, ())

        inside = isolated_bank.with_name(f"{self.RECIPE_ID}-unittest-inside.wav")
        inside_render = renderer.render_event(
            isolated_bank,
            "engine_int",
            inside,
            parameters={"rpms": 9500.0, "throttle": 0.0},
            duration_frames=48000,
            warmup_frames=24000,
        )
        self.assertEqual(inside_render.scheduled_sound_names, ("elisesc_compressor",))
        self.assertGreater(inside.stat().st_size, 44)
        first_inside_evidence = dict(renderer.last_fresh_process_evidence or {})
        inside_duplicate = isolated_bank.with_name(
            f"{self.RECIPE_ID}-unittest-inside-duplicate.wav"
        )
        duplicate_render = renderer.render_event(
            isolated_bank,
            "engine_int",
            inside_duplicate,
            parameters={"rpms": 9500.0, "throttle": 0.0},
            duration_frames=48000,
            warmup_frames=24000,
        )
        duplicate_evidence = renderer.last_fresh_process_evidence or {}
        self.assertEqual(
            duplicate_render.scheduled_sound_names, ("elisesc_compressor",)
        )
        self.assertEqual(
            first_inside_evidence["renderIdentitySha256"],
            duplicate_evidence["renderIdentitySha256"],
        )
        self.assertEqual(
            first_inside_evidence["outputSha256"],
            duplicate_evidence["outputSha256"],
        )
        self.assertNotEqual(
            first_inside_evidence["requestSha256"],
            duplicate_evidence["requestSha256"],
        )
        evidence_path = inside.with_name(
            f".{inside.name}.fresh-render-evidence.json"
        )
        evidence_envelope = json.loads(evidence_path.read_text(encoding="utf-8"))
        evidence = evidence_envelope["evidence"]
        self.assertEqual(
            evidence_envelope["evidenceSha256"],
            __import__("hashlib").sha256(canonical_json_bytes(evidence)).hexdigest(),
        )
        request_envelope = evidence["requestEnvelope"]
        self.assertEqual(
            request_envelope["requestSha256"],
            __import__("hashlib").sha256(
                canonical_json_bytes(request_envelope["request"])
            ).hexdigest(),
        )
        result_envelope = evidence["resultEnvelope"]
        self.assertEqual(
            result_envelope["resultSha256"],
            __import__("hashlib").sha256(
                canonical_json_bytes(result_envelope["result"])
            ).hexdigest(),
        )


class InstalledPlanRepackageTests(unittest.TestCase):
    def test_plan_only_repackage_preserves_all_payload_bytes(self) -> None:
        release_root = Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
        old_plan_path = release_root / "capture-plan-v2-static-c993755d.json"
        source = (
            release_root
            / "packs"
            / "0a35cfe4af101affc46bb2a2be29e500a8ccbd5504261328c03c00f827af2753.aclib"
        )
        if not old_plan_path.is_file() or not source.is_file():
            self.skipTest("a frozen-plan release pack is unavailable")
        try:
            validate_release_manifest(validate_aclib(source))
        except ManifestValidationError as exc:
            self.skipTest(f"legacy V2 pack predates the strict pitch contract: {exc}")
        old_plan = json.loads(old_plan_path.read_text(encoding="utf-8"))
        final_plan = copy.deepcopy(old_plan)
        final_plan["planConvergenceRegression"] = {
            "proof": "global-only-field"
        }
        with tempfile.TemporaryDirectory(
            prefix="aclib-repackage-test-", dir=release_root
        ) as temporary_text:
            temporary = Path(temporary_text)
            output = temporary / "packs" / source.name
            repackaged = repackage_aclib_for_capture_plan(
                source,
                output,
                old_plan,
                final_plan,
                temporary / "immutable",
            )
            manifest = validate_release_manifest(validate_aclib(repackaged))
            self.assertEqual(
                manifest["provenance"]["capturePlanSha256"],
                __import__("hashlib").sha256(
                    canonical_json_bytes(final_plan)
                ).hexdigest(),
            )
            archived = (
                temporary
                / "immutable"
                / __import__("hashlib").sha256(
                    canonical_json_bytes(old_plan)
                ).hexdigest()
                / source.name
            )
            self.assertEqual(archived.read_bytes(), source.read_bytes())
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(
                repackaged
            ) as after:
                before_payload = {
                    name: before.read(name)
                    for name in before.namelist()
                    if name != "manifest.json"
                }
                after_payload = {
                    name: after.read(name)
                    for name in after.namelist()
                    if name != "manifest.json"
                }
            self.assertEqual(after_payload, before_payload)


class InstalledSilentShiftManifestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pack = Path(
            r"D:\Users\sgabr\BYDMotorSoundData\aclib-shift-silence-smoke\packs"
        ) / (
            "0a26446f58dfce705b88e028fbb40c52fe444bc82acacb2b5ccf065a68cefd67"
            ".aclib"
        )
        if not cls.pack.is_file():
            raise unittest.SkipTest("certified silent-shift smoke pack is absent")
        with zipfile.ZipFile(cls.pack) as archive:
            cls.manifest = json.loads(archive.read("manifest.json"))
        try:
            validate_release_manifest(validate_manifest(cls.manifest))
        except ManifestValidationError as exc:
            raise unittest.SkipTest(
                f"silent-shift smoke pack predates strict V2 pitch fields: {exc}"
            ) from exc

    def test_certificate_is_exact_and_audible_shift_direction_remains(self) -> None:
        silent = self.manifest["provenance"]["certifiedSilentSources"]
        self.assertEqual(
            silent,
            [
                {
                    "disposition": "AUTHORED_TARGET_SILENT",
                    "role": "SHIFT_UP",
                    "sourceGuid": "f504195a-1ecc-4705-a6f4-189e419863f5",
                    "verificationPayloadSha256": (
                        "9c25aef2a2b0daebaddc4bd0865ae425ef5ab517d4d0daa8029b95a9da1d0527"
                    ),
                }
            ],
        )
        self.assertTrue(self.manifest["effects"]["shift"])
        self.assertEqual(
            {track["role"] for track in self.manifest["tracks"] if track["role"].startswith("SHIFT")},
            {"SHIFT_DOWN"},
        )

    def test_wrong_role_duplicate_and_cap_are_rejected(self) -> None:
        wrong = copy.deepcopy(self.manifest)
        wrong["provenance"]["certifiedSilentSources"][0]["role"] = "IDLE"
        with self.assertRaises(ManifestValidationError):
            validate_manifest(wrong)

        duplicate = copy.deepcopy(self.manifest)
        duplicate["provenance"]["certifiedSilentSources"].append(
            {
                **duplicate["provenance"]["certifiedSilentSources"][0],
                "role": "SHIFT_DOWN",
            }
        )
        with self.assertRaises(ManifestValidationError):
            validate_manifest(duplicate)

        over_cap = copy.deepcopy(self.manifest)
        over_cap["provenance"]["certifiedSilentSources"] = [
            {
                "sourceGuid": f"00000000-0000-0000-0000-{index:012x}",
                "role": "SHIFT_UP",
                "disposition": "AUTHORED_TARGET_SILENT",
                "verificationPayloadSha256": __import__("hashlib").sha256(
                    str(index).encode("ascii")
                ).hexdigest(),
            }
            for index in range(513)
        ]
        with self.assertRaises(ManifestValidationError):
            validate_manifest(over_cap)

    def test_audible_node_and_capability_contradictions_are_rejected(self) -> None:
        audible = copy.deepcopy(self.manifest)
        silent_guid = audible["provenance"]["certifiedSilentSources"][0][
            "sourceGuid"
        ].replace("-", "")
        program = next(
            item for item in audible["oneShotPrograms"] if item["trigger"] == "SHIFT_DOWN"
        )
        track_node = next(node for node in program["nodes"] if node["kind"] == "TRACK")
        old_id = track_node["id"]
        track_node["id"] = f"track_{silent_guid}"
        program["rootNodeIds"] = [
            track_node["id"] if node_id == old_id else node_id
            for node_id in program["rootNodeIds"]
        ]
        with self.assertRaises(ManifestValidationError):
            validate_manifest(audible)

        capability = copy.deepcopy(self.manifest)
        capability["effects"]["shift"] = False
        with self.assertRaises(ManifestValidationError):
            validate_manifest(capability)

    def test_fake_oracle_payload_hash_is_rejected(self) -> None:
        proof_root = Path(DEFAULT_SHIFT_SILENCE_ORACLE_PROOF).parent
        with tempfile.TemporaryDirectory() as temporary_text:
            temporary = Path(temporary_text) / "oracle"
            shutil.copytree(proof_root, temporary)
            proof_path = temporary / "proof.json"
            proof = json.loads(proof_path.read_text(encoding="utf-8"))
            proof["sourceVerifications"][0]["verificationPayloadSha256"] = "0" * 64
            proof_path.write_bytes(canonical_json_bytes(proof) + b"\n")
            with self.assertRaises(ReleaseCapturePlanError):
                load_shift_silence_source_verifications(proof_path)


class InstalledReleasePlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        root = Path(__file__).resolve().parents[1]
        catalog_path = root / ".aclib-local" / "catalog-v1.json"
        graph_path = (
            root
            / ".aclib-local"
            / "bank-graph-audit-v3"
            / "families"
            / f"{TATUUS_FAMILY_ID}.json"
        )
        if not catalog_path.is_file() or not graph_path.is_file():
            raise unittest.SkipTest("complete local Tatuus catalog graph is absent")
        cls.catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        cls.graph = json.loads(graph_path.read_text(encoding="utf-8"))
        cls.graph_path = graph_path

    def test_tatuus_classifies_every_source_and_retains_exact_curved_sources(self) -> None:
        family = next(
            item for item in self.catalog["soundFamilies"] if item["id"] == TATUUS_FAMILY_ID
        )
        car = next(item for item in self.catalog["cars"] if item["id"] == "tatuusfa1")
        release = _release_family(family, car, self.graph, self.graph_path)
        classified = classify_bank_graph_sources(self.graph)
        self.assertEqual(
            release["sourceCoverage"]["classified"],
            classified["counts"]["sourceInstruments"],
        )
        self.assertEqual(
            release["sourceCoverage"]["omissions"][
                "nonNormalDrivingTransmissionTransient"
            ],
            1,
        )
        self.assertTrue(any(item["role"] == "IDLE" for item in release["recipes"]))
        self.assertTrue(any(item["role"] == "TRANSMISSION" for item in release["recipes"]))
        limiter_programs = [
            item
            for item in release["oneShotPrograms"]
            if item["trigger"] == "LIMITER_EVENT"
        ]
        self.assertEqual(len(limiter_programs), 1)
        limiter = limiter_programs[0]
        self.assertEqual(limiter["policy"]["kind"], "PERSISTENT_LIMITER_EVENT")
        self.assertNotIn("periodHz", limiter["policy"])
        _validate_limiter_event_policy(limiter["policy"], "limiter")
        self.assertEqual(
            _validate_limiter_event_program_policy(limiter["policy"], "limiter"),
            limiter["policy"]["programMode"],
        )
        changed = copy.deepcopy(limiter["policy"])
        changed["runtimeLifecycle"]["inactiveThreshold"]["seconds"] = 9.0
        with self.assertRaises(ReleaseCapturePlanError):
            _validate_limiter_event_policy(changed, "limiter")
        with self.assertRaises(ManifestValidationError):
            _validate_limiter_event_program_policy(changed, "limiter")
        curved = [item for item in release["recipes"] if item["sourceCurveSha256"]]
        self.assertGreater(len(curved), 0)
        classification = classify_bank_graph_sources(self.graph)
        for recipe in curved:
            row, target, reachable = verify_recipe_against_graph(
                recipe, self.graph, classification
            )
            self.assertEqual(row["sourceGuid"], recipe["sourceGuid"])
            self.assertEqual(target["kind"], "WaveformInstrumentNode")
            self.assertIn(recipe["sourceGuid"], reachable)
        serialized = canonical_json_bytes(release).decode("utf-8").casefold()
        self.assertNotIn("\"load\"", serialized)

    def test_complete_local_release_plan_retains_special_fidelity_cases(self) -> None:
        plan_path = DEFAULT_RELEASE_PLAN
        property_one_plan = (
            Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
            / "capture-plan-v2-property-working.json"
        )
        certified_turbo_plan = (
            Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
            / "capture-plan-v2-turbo-certified-working.json"
        )
        shift_silence_plan = (
            Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
            / "capture-plan-v2-shift-silence-working.json"
        )
        static_turbo_plan = (
            Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
            / "capture-plan-v2-static-c993755d.json"
        )
        if property_one_plan.is_file():
            plan_path = property_one_plan
        elif certified_turbo_plan.is_file():
            plan_path = certified_turbo_plan
        elif shift_silence_plan.is_file():
            plan_path = shift_silence_plan
        elif static_turbo_plan.is_file():
            plan_path = static_turbo_plan
        if not plan_path.is_file():
            plan_path = (
                Path(__file__).resolve().parents[1]
                / ".aclib-local"
                / "capture-plan-v2-working.json"
            )
        if not plan_path.is_file():
            self.skipTest("complete local V2 release plan is absent")
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        validate_release_capture_plan(plan, self.catalog)
        recipes = [
            recipe for family in plan["families"] for recipe in family["recipes"]
        ]
        programs = [
            program
            for family in plan["families"]
            for program in family["oneShotPrograms"]
        ]
        transients = [item for item in recipes if item["role"] == "ENGINE_TRANSIENT"]
        engine_programs = [item for item in programs if item["trigger"] == "ENGINE_EVENT"]
        limiter_programs = [
            item for item in programs if item["trigger"] == "LIMITER_EVENT"
        ]
        turbo_programs = [
            item for item in programs if item["trigger"] == "TURBO_EVENT"
        ]
        self.assertEqual(len(plan["families"]), 153)
        self.assertEqual(
            plan["limiterOracle"]["verifiedSourceCount"], 73
        )
        self.assertEqual(
            plan["limiterOracle"]["authoredTargetSilentCount"], 3
        )
        self.assertEqual(plan["shiftSilenceOracle"]["verifiedSourceCount"], 2)
        self.assertEqual(
            plan["shiftSilenceOracle"]["authoredTargetSilentCount"], 2
        )
        self.assertEqual(plan["turboTransientOracle"]["verifiedSourceCount"], 171)
        self.assertEqual(plan["turboTransientOracle"]["verifiedFamilyCount"], 59)
        self.assertEqual(
            plan["turboTransientOracle"]["verifiedProgramRootCount"], 105
        )
        self.assertEqual(
            plan["turboTransientOracle"]["authoredTargetSilentCount"], 11
        )
        self.assertEqual(
            plan["turboTransientOracle"][
                "manifestControlGainMaximumInclusive"
            ],
            38.0,
        )
        self.assertEqual(len(recipes), 3272)
        self.assertEqual(
            sum(item["sourceProjection"] == "SHARED_SOURCE_IDLE" for item in recipes),
            11,
        )
        self.assertEqual(
            sum(item["sourceProjection"] == "ADAPTIVE_RPM_WINDOWS" for item in recipes),
            0,
        )
        compact_property = [
            item
            for item in recipes
            if item["sourceProjection"]
            == "CERTIFIED_PROPERTY_ONE_RELATIVE_RATE"
        ]
        self.assertEqual(len(compact_property), 5)
        self.assertTrue(
            all(
                item["pitchMode"]
                == "AUTHORED_PROPERTY_ONE_RELATIVE_RATE"
                and item["pitchCurveInterpolation"] == "CLAMPED_LINEAR"
                and 2 <= len(item["pitchCurve"]) <= 512
                for item in compact_property
            )
        )
        self.assertTrue(
            all(
                item["pitchMode"] == "AUTO_PITCH_RPM_RATIO"
                and item["pitchCurve"] == []
                and item["pitchCurveInterpolation"] == "NONE"
                for item in recipes
                if item not in compact_property
            )
        )
        property_proof, property_verifications = (
            load_property_one_source_verifications(
                DEFAULT_PROPERTY_ONE_ORACLE_PROOF
            )
        )
        self.assertEqual(set(property_verifications), {
            item["sourceGuid"] for item in compact_property
        })
        self.assertEqual(
            plan["propertyOneOracle"]["proofSha256"],
            __import__("hashlib").sha256(
                Path(DEFAULT_PROPERTY_ONE_ORACLE_PROOF).read_bytes()
            ).hexdigest(),
        )
        self.assertEqual(
            plan["propertyOneOracle"]["proofPayloadSha256"],
            property_proof["proofPayloadSha256"],
        )
        source_omissions = [
            omission
            for family in plan["families"]
            for omission in family["sourceOmissions"]
        ]
        self.assertEqual(len(source_omissions), 5)
        self.assertEqual(
            sum(
                omission["disposition"] == "FORBIDDEN_ON_PEDAL_ROUTING"
                for omission in source_omissions
            ),
            4,
        )
        self.assertEqual(
            sum(
                omission["disposition"]
                == "AUTHORED_TARGET_ROUTED_SILENT"
                for omission in source_omissions
            ),
            1,
        )
        self.assertEqual(
            sum(
                item["sourceProjection"] == "CERTIFIED_TURBO_TRANSIENT"
                for item in recipes
            ),
            171,
        )
        silent_shifts = [
            item
            for item in recipes
            if item["sourceProjection"] == "CERTIFIED_AUTHORED_SILENCE"
        ]
        self.assertEqual(len(silent_shifts), 2)
        self.assertEqual({item["role"] for item in silent_shifts}, {"SHIFT_UP"})
        proof, verifications = load_shift_silence_source_verifications(
            DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
            expected_catalog_sha256=self.catalog["catalogSha256"],
        )
        self.assertEqual(
            plan["shiftSilenceOracle"]["proofSha256"],
            __import__("hashlib").sha256(
                Path(DEFAULT_SHIFT_SILENCE_ORACLE_PROOF).read_bytes()
            ).hexdigest(),
        )
        self.assertEqual(
            {item["sourceGuid"] for item in silent_shifts}, set(verifications)
        )
        self.assertEqual(proof["sourceCount"], 2)
        turbo_proof, turbo_verifications = (
            load_turbo_transient_source_verifications(
                DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF
            )
        )
        self.assertEqual(len(turbo_verifications), 171)
        self.assertEqual(
            plan["turboTransientOracle"]["proofSha256"],
            __import__("hashlib").sha256(
                Path(DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF).read_bytes()
            ).hexdigest(),
        )
        self.assertEqual(
            turbo_proof["counts"]["pcmDispositions"],
            {
                "AUDIBLE_TARGET_PCM": 160,
                "AUTHORED_TARGET_SILENT": 11,
            },
        )
        self.assertEqual(sum(item["role"] == "TURBO" for item in recipes), 66)
        self.assertEqual(sum(item["role"] == "BOV" for item in recipes), 143)
        self.assertEqual(
            sum(item["role"] == "TURBO_TRANSIENT" for item in recipes), 28
        )
        self.assertEqual(len(transients), 60)
        self.assertEqual(len(engine_programs), 50)
        self.assertEqual(len(limiter_programs), 73)
        self.assertEqual(len(turbo_programs), 105)
        self.assertEqual(plan["priorityOracle"]["verifiedSourceCount"], 907)
        self.assertEqual(
            plan["priorityOracle"]["resolvedRolePriorities"]["OVERRUN"], 128
        )
        self.assertEqual(
            plan["priorityOracle"]["resolvedRolePriorities"]["BOV"], 128
        )
        self.assertEqual(
            plan["priorityOracle"]["resolvedRolePriorities"]["TURBO_TRANSIENT"],
            128,
        )
        self.assertFalse(
            any(item["softwareChannelPriority"] is None for item in recipes)
        )
        recipes_by_id = {item["id"]: item for item in recipes}
        for program in programs:
            leaf_priorities = {
                recipes_by_id[node["trackId"]]["softwareChannelPriority"]
                for node in program["nodes"]
                if node["kind"] == "TRACK"
            }
            self.assertEqual(
                leaf_priorities, {program["softwareChannelPriority"]}
            )
        self.assertEqual(
            {
                mode: sum(
                    item["policy"]["programMode"] == mode
                    for item in limiter_programs
                )
                for mode in {
                    "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT",
                    "PERSISTENT_DECAY_REGION_ONE_SHOT",
                    "PERSISTENT_DECAY_REGION_LOOP",
                }
            },
            {
                "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": 48,
                "PERSISTENT_DECAY_REGION_ONE_SHOT": 7,
                "PERSISTENT_DECAY_REGION_LOOP": 18,
            },
        )
        self.assertFalse(any(item["trigger"] == "LIMITER" for item in programs))
        self.assertEqual(
            {
                mode: sum(
                    program["policy"]["programMode"] == mode
                    for program in turbo_programs
                )
                for mode in {
                    "BOOST_RELEASE_REGION_ONE_SHOT",
                    "TIMELINE_PERIODIC_ONE_SHOT",
                    "PARAMETER_SHEET_EVENT_START_ONE_SHOT",
                }
            },
            {
                "BOOST_RELEASE_REGION_ONE_SHOT": 77,
                "TIMELINE_PERIODIC_ONE_SHOT": 25,
                "PARAMETER_SHEET_EVENT_START_ONE_SHOT": 3,
            },
        )
        self.assertEqual(
            sorted(
                sum(node["kind"] == "TRACK" for node in program["nodes"])
                for program in turbo_programs
                if any(node["kind"] == "GROUP" for node in program["nodes"])
            ),
            [3, 17, 17, 17, 17],
        )
        for program in turbo_programs:
            self.assertEqual(program["softwareChannelPriority"], 128)
            self.assertFalse(program["policy"]["auditionable"])
            self.assertEqual(
                program["policy"]["coreProgram"],
                program["policy"]["programMode"]
                != "BOOST_RELEASE_REGION_ONE_SHOT",
            )
            for node in program["nodes"]:
                if node["kind"] != "TRACK":
                    continue
                self.assertRegex(
                    node["sourceVerificationPayloadSha256"], r"^[0-9a-f]{64}$"
                )
                self.assertTrue(node["captureControlValues"])
                self.assertTrue(node["controlGainCurves"])
                self.assertTrue(
                    all(
                        0.0 <= point[1] <= 38.0
                        for control_curve in node["controlGainCurves"]
                        for point in control_curve["curve"]
                    )
                )
                self.assertFalse(node["liveVarispeed"])
                self.assertIsNone(node["rootRpm"])
        self.assertTrue(
            all(
                len(item["policy"]["sourceVerificationPayloadSha256"]) == 64
                for item in limiter_programs
            )
        )
        self.assertEqual(
            len(
                {
                    family["familyId"]
                    for family in plan["families"]
                    if any(
                        program["trigger"] == "ENGINE_EVENT"
                        for program in family["oneShotPrograms"]
                    )
                }
            ),
            24,
        )
        for program in engine_programs:
            self.assertTrue(program["policy"]["coreProgram"])
            self.assertFalse(program["policy"]["auditionable"])
            for node in program["nodes"]:
                if node["kind"] == "TRACK":
                    self.assertTrue(node["liveVarispeed"])
                    self.assertGreater(node["rootRpm"], 0.0)
                    for gate in node["parameterGates"]:
                        self.assertIn("includeMinimum", gate)
        hybrid_audit = build_hybrid_audio_control_audit(
            self.catalog,
            Path(__file__).resolve().parents[1]
            / ".aclib-local"
            / "bank-graph-audit-v3",
        )
        self.assertEqual(hybrid_audit["familyCount"], 12)
        self.assertEqual(hybrid_audit["hybridSpecificGameParameterCount"], 0)
        self.assertEqual(hybrid_audit["hybridSpecificEventSuffixCount"], 0)


if __name__ == "__main__":
    unittest.main()
