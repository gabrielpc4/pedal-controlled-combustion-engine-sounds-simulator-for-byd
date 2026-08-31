from __future__ import annotations

import configparser
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.car_catalog import build_car_catalog_packs as catalog


def canonical_wav(frames: int = 64) -> bytes:
    pcm = b"\x00\x00\x00\x00" * frames
    return (
        b"RIFF"
        + struct.pack("<I", 36 + len(pcm))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 2, 48_000, 192_000, 4, 16)
        + b"data"
        + struct.pack("<I", len(pcm))
        + pcm
    )


@dataclass
class BackfireSpec:
    maximum_gas: float = 0.3
    minimum_rpm: float = 3_500.0
    maximum_rpm: float = 15_000.0
    trigger_gas: float = 0.8


@dataclass
class TurboSpec:
    lag_down: float = 0.99
    lag_up: float = 0.99
    maximum_boost: float = 1.0
    wastegate: float = 1.0
    reference_rpm: float = 3_000.0
    gamma: float = 1.0
    bov_threshold: float = 0.5


@dataclass
class EngineSpec:
    idle_rpm: float = 900.0
    limiter_rpm: float = 7_000.0
    tachometer_maximum: float = 7_500.0
    limiter_hz: float = 0.0
    turbos: tuple[TurboSpec, ...] = ()
    backfire: BackfireSpec = field(default_factory=BackfireSpec)


@dataclass
class VehicleSpec:
    front_wheel_radius_m: float = 0.33
    rear_wheel_radius_m: float = 0.35


@dataclass
class DrivetrainSpec:
    auto_up_rpm: int = 6_800
    forward_ratios: tuple[float, ...] = (3.0, 2.0, 1.0)
    gear_up_time_s: float = 0.08
    gear_down_time_s: float = 0.12
    final_drive: float = 3.5
    traction: str = "RWD"
    vehicle: VehicleSpec = field(default_factory=VehicleSpec)


class PhysicsTests(unittest.TestCase):
    def test_invalid_primary_idle_uses_authored_throttle_lua_value(self) -> None:
        engine_ini = configparser.ConfigParser()
        engine_ini.read_string("[THROTTLE_LUA]\nIDLE_RPM=700\n")

        physics, evidence = catalog.derive_physics(
            car_id="test_car",
            engine_spec=EngineSpec(idle_rpm=-9_000.0),
            drivetrain_spec=DrivetrainSpec(),
            engine_ini=engine_ini,
            instruments_ini=None,
        )

        self.assertEqual(physics["idleRpm"], 700.0)
        self.assertTrue(evidence["idleRpm"]["fallbackUsed"])
        self.assertEqual(evidence["idleRpm"]["rejectedPrimary"], -9_000.0)
        self.assertEqual(physics["soundFinalDriveRatio"], 3.5)
        self.assertEqual(physics["soundDrivenWheelRadiusMeters"], 0.35)

    def test_redline_uses_valid_authored_indicator_and_preserves_upshift(self) -> None:
        engine_ini = configparser.ConfigParser()
        instruments = configparser.ConfigParser()
        instruments.read_string(
            "[LED_0]\nRPM_SWITCH=6200\nBLINK_SWITCH=6600\n"
            "[LED_1]\nRPM_SWITCH=6500\nBLINK_SWITCH=18800\n"
        )

        physics, evidence = catalog.derive_physics(
            car_id="test_car",
            engine_spec=EngineSpec(),
            drivetrain_spec=DrivetrainSpec(auto_up_rpm=6_800),
            engine_ini=engine_ini,
            instruments_ini=instruments,
        )

        self.assertEqual(physics["redlineRpm"], 6_800.0)
        indicators = evidence["redlineRpm"]["validShiftIndicators"]
        self.assertNotIn(18_800.0, [item["rpm"] for item in indicators])

    def test_non_descending_gear_ratios_fail_closed(self) -> None:
        physics = {
            "minimumRpm": 0.0,
            "maximumRpm": 7_500.0,
            "idleRpm": 900.0,
            "redlineRpm": 7_000.0,
            "limiterRpm": 7_000.0,
            "upshiftRpm": 6_800.0,
            "gearRatios": [3.0, 2.0, 2.0],
            "soundFinalDriveRatio": 3.5,
            "soundDrivenWheelRadiusMeters": 0.35,
            "drivetrainSpeedControl": {
                "parameterName": "drivetrain_speed",
                "unit": "drivenWheelRadiansPerSecond",
                "formula": "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
                "signed": True,
            },
            "turbos": [],
            "turboBoostNormalization": {
                "kind": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
                "divisor": 0.0,
                "minimum": 0.0,
                "maximum": 1.0,
            },
            "backfire": {
                "maximumGas": 0.3,
                "minimumRpm": 3_500.0,
                "maximumRpm": 15_000.0,
                "triggerGas": 0.8,
                "minimumIntentThrottle": 0.4,
                "minimumIntentSeconds": 1.0,
            },
            "limiterFrequencyHz": 0.0,
            "upshiftDurationSeconds": 0.08,
            "downshiftDurationSeconds": 0.12,
        }

        with self.assertRaisesRegex(catalog.CatalogBuildError, "strictly descending"):
            catalog.validate_physics(physics, "test_car")

    def test_profile_ids_are_casefolded_and_collision_safe_inputs_match(self) -> None:
        self.assertEqual(catalog.profile_id("sa_gtr_Godzilla"), "ac_sa_gtr_godzilla")
        self.assertEqual(catalog.profile_id("f302_eclipse_gsx-r"), "ac_f302_eclipse_gsx_r")

    def test_awd_sound_radius_matches_audio_lab_mean_without_fallback(self) -> None:
        physics, evidence = catalog.derive_physics(
            car_id="test_awd",
            engine_spec=EngineSpec(),
            drivetrain_spec=DrivetrainSpec(
                traction="AWD2",
                vehicle=VehicleSpec(front_wheel_radius_m=0.34, rear_wheel_radius_m=0.36),
            ),
            engine_ini=configparser.ConfigParser(),
            instruments_ini=None,
        )

        self.assertAlmostEqual(physics["soundDrivenWheelRadiusMeters"], 0.35)
        radius_evidence = evidence["soundDrivenWheelRadiusMeters"]
        self.assertFalse(radius_evidence["fallbackUsed"])
        self.assertEqual(radius_evidence["traction"], "AWD2")

    def test_invalid_final_drive_uses_first_authored_setup_ratio_with_provenance(self) -> None:
        setup_ini = configparser.ConfigParser()
        setup_ini.read_string("[FINAL_GEAR_RATIO]\nRATIOS=final.rto\n")

        physics, evidence = catalog.derive_physics(
            car_id="nohesi_370z_widebody",
            engine_spec=EngineSpec(),
            drivetrain_spec=DrivetrainSpec(final_drive=5.2),
            engine_ini=configparser.ConfigParser(),
            instruments_ini=None,
            raw_final_drive="9999",
            setup_ini=setup_ini,
            final_drive_ratio_tables={"final.rto": "Stock|5.2\nShort|5.4\nShorter|5.6\n"},
        )

        self.assertEqual(physics["soundFinalDriveRatio"], 5.2)
        final_drive_evidence = evidence["soundFinalDriveRatio"]
        self.assertTrue(final_drive_evidence["fallbackUsed"])
        self.assertEqual(final_drive_evidence["rejectedDrivetrainFinal"], 9999.0)
        self.assertEqual(final_drive_evidence["selectedLabel"], "Stock")
        self.assertEqual(
            [option["ratio"] for option in final_drive_evidence["authoredOptions"]],
            [5.2, 5.4, 5.6],
        )

    def test_invalid_final_drive_without_authored_setup_fails_closed(self) -> None:
        with self.assertRaisesRegex(catalog.CatalogBuildError, "setup.ini has no"):
            catalog.resolve_sound_final_drive(
                car_id="broken_car",
                drivetrain_final_drive=9999,
            )

    def test_turbo_backfire_limiter_and_drivetrain_controls_preserve_authored_physics(self) -> None:
        engine_ini = configparser.ConfigParser()
        engine_ini.read_string(
            "[ENGINE_DATA]\nLIMITER_HZ=23\n"
            "[TURBO_0]\nLAG_UP=0.987\nLAG_DN=0.9993\nMAX_BOOST=1.0\n"
            "WASTEGATE=0.5\nREFERENCE_RPM=3500\nGAMMA=2.0\n"
            "[TURBO_1]\nLAG_UP=0.987\nLAG_DN=0.9993\nMAX_BOOST=1.0\n"
            "WASTEGATE=0.5\nREFERENCE_RPM=3500\nGAMMA=2.0\n"
            "[BOV]\nPRESSURE_THRESHOLD=0.45\n"
        )
        sounds_ini = configparser.ConfigParser()
        sounds_ini.read_string(
            "[BACKFIRE]\nMAXGAS=0.8\nMINRPM=4200\nMAXRPM=8000\nTRIGGERGAS=0.7\n"
        )
        turbo = TurboSpec(
            lag_down=0.9993,
            lag_up=0.987,
            maximum_boost=1.0,
            wastegate=0.5,
            reference_rpm=3_500.0,
            gamma=2.0,
            bov_threshold=0.45,
        )

        physics, evidence = catalog.derive_physics(
            car_id="turbo_car",
            engine_spec=EngineSpec(
                limiter_hz=23.0,
                turbos=(turbo, turbo),
                backfire=BackfireSpec(
                    maximum_gas=0.3,
                    minimum_rpm=4_200.0,
                    maximum_rpm=8_000.0,
                    trigger_gas=0.7,
                ),
            ),
            drivetrain_spec=DrivetrainSpec(),
            engine_ini=engine_ini,
            instruments_ini=None,
            sounds_ini=sounds_ini,
        )

        self.assertEqual(len(physics["turbos"]), 2)
        self.assertEqual(physics["turboBoostNormalization"]["divisor"], 2.0)
        self.assertEqual(physics["backfire"]["maximumGas"], 0.3)
        self.assertEqual(physics["backfire"]["minimumIntentThrottle"], 0.4)
        self.assertEqual(physics["limiterFrequencyHz"], 23.0)
        self.assertEqual(
            physics["drivetrainSpeedControl"]["formula"],
            "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
        )
        self.assertEqual(
            evidence["backfire"]["maximumGas"]["assettoExecutableHardCap"],
            0.3,
        )


class GeneratedCatalogTests(unittest.TestCase):
    def test_checked_in_catalog_and_all_previews_match_the_passing_preparation_report(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        source = catalog._load_source_catalog(
            repository / "docs/generated/new-cars-android-catalog-source.json"
        )
        report = json.loads(
            (repository / "docs/generated/new-cars-android-catalog-preparation-report.json")
            .read_text(encoding="utf-8")
        )
        previews = repository / "mobile/src/main/assets/car_previews"
        preview_files = sorted(path for path in previews.iterdir() if path.is_file())

        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["activeCarCount"], 36)
        self.assertEqual(report["bankFamilyCount"], 32)
        self.assertEqual(report["deduplicatedCarCount"], 4)
        self.assertEqual(len(source["cars"]), 36)
        self.assertEqual(len(source["families"]), 32)
        turbo_cars = [car for car in source["cars"] if car["physics"]["turbos"]]
        naturally_aspirated_cars = [car for car in source["cars"] if not car["physics"]["turbos"]]
        self.assertEqual(len(turbo_cars), 17)
        self.assertEqual(len(naturally_aspirated_cars), 19)
        self.assertEqual(sum(len(car["physics"]["turbos"]) for car in turbo_cars), 26)
        self.assertTrue(
            all(car["physics"]["turboBoostNormalization"]["divisor"] == 0.0 for car in naturally_aspirated_cars)
        )
        self.assertEqual(len(preview_files), 36)
        self.assertEqual(sum(path.stat().st_size for path in preview_files), report["totalPreviewBytes"])
        self.assertLessEqual(report["largestPreviewBytes"], catalog.MAXIMUM_PREVIEW_BYTES)
        self.assertLessEqual(report["totalPreviewBytes"], catalog.MAXIMUM_ALL_PREVIEW_BYTES)
        report_previews = {Path(item["output"]).name: item for item in report["previewEvidence"]}
        self.assertEqual({path.name for path in preview_files}, set(report_previews))
        for path in preview_files:
            evidence = report_previews[path.name]
            self.assertEqual(path.stat().st_size, evidence["outputBytes"])
            self.assertEqual(catalog.sha256_file(path), evidence["outputSha256"])
            self.assertLessEqual(evidence["width"], 960)
            self.assertLessEqual(evidence["height"], 540)

        nohesi = next(car for car in source["cars"] if car["sourceCarId"] == "nohesi_370z_widebody")
        nohesi_evidence = next(
            item for item in report["physicsEvidence"] if item["carId"] == "nohesi_370z_widebody"
        )["fields"]["soundFinalDriveRatio"]
        self.assertEqual(nohesi["physics"]["soundFinalDriveRatio"], 5.2)
        self.assertEqual(nohesi["physics"]["soundDrivenWheelRadiusMeters"], 0.33)
        self.assertEqual(nohesi_evidence["selectedLabel"], "Stock")
        self.assertEqual(nohesi_evidence["rejectedDrivetrainFinal"], 9999.0)


class PackTests(unittest.TestCase):
    def test_pack_is_deterministic_and_manifest_matches_strict_android_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            assets = root / "assets"
            assets.mkdir()
            shard = assets / "engine_cabin_atlas_000.wav"
            shard.write_bytes(canonical_wav())
            metadata = catalog.read_canonical_atlas_wav(shard)
            runtime = {"shards": [{"name": shard.name, "sha256": metadata.sha256, "bytes": metadata.size_bytes}]}

            requirement_one, report_one = catalog.build_family_pack(
                runtime_id="atlas_0123456789abcdef",
                assets_directory=assets,
                runtime=runtime,
                wav_metadata={shard.name: metadata},
                output_path=root / "one.bydpack",
                pack_version=3,
            )
            requirement_two, report_two = catalog.build_family_pack(
                runtime_id="atlas_0123456789abcdef",
                assets_directory=assets,
                runtime=runtime,
                wav_metadata={shard.name: metadata},
                output_path=root / "two.bydpack",
                pack_version=3,
            )

            self.assertEqual(report_one["packSha256"], report_two["packSha256"])
            self.assertEqual(requirement_one, requirement_two)
            with zipfile.ZipFile(root / "one.bydpack") as archive:
                self.assertEqual(
                    archive.namelist(),
                    ["manifest.json", "sample_engine/atlas_0123456789abcdef/engine_cabin_atlas_000.wav"],
                )
                manifest_bytes = archive.read("manifest.json")
                manifest = json.loads(manifest_bytes)
                self.assertEqual(set(manifest), {"schemaVersion", "packId", "packVersion", "files"})
                self.assertEqual(manifest["schemaVersion"], 1)
                self.assertEqual(manifest["files"][0]["frameCount"], 64)
                self.assertEqual(hashlib.sha256(manifest_bytes).hexdigest(), requirement_one["manifestSha256"])

    def test_noncanonical_wav_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bad.wav"
            path.write_bytes(canonical_wav() + b"junk")

            with self.assertRaises(catalog.CatalogBuildError):
                catalog.read_canonical_atlas_wav(path)


class ReleaseFamilyTests(unittest.TestCase):
    def _write_fixture(self, root: Path, *, draft_blocked: bool = False) -> tuple[str, str, list[str]]:
        runtime_id = "atlas_0123456789abcdef"
        bank_sha = "a" * 64
        plan_sha = "b" * 64
        car_ids = ["car_one", "car_two"]
        family = root / "families" / runtime_id
        assets = family / "assets"
        assets.mkdir(parents=True)
        shard = assets / "engine_cabin_atlas_000.wav"
        shard.write_bytes(canonical_wav(frames=192))
        metadata = catalog.read_canonical_atlas_wav(shard)
        observed_voice_budget = {
            "schema": "byd-full-event-fmod-voice-budget-input-v1",
            "status": "PASS_WITH_BOUNDED_CLAIMS",
            "reportSha256": "f" * 64,
        }
        premix_admission_parity = {
            "requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget": True,
            "realBudget": 256,
            "scenarioDemand": "continuousRawSourcesPlusEveryCausallyLiveFiniteTailSource",
            "onExceeded": "BLOCK_RELEASE_REQUIRE_SOURCE_STEMS_FOR_PER_SOURCE_PRIORITY_AUDIBILITY_AND_VIRTUALIZATION",
            "scalarOnlyProofIsSufficient": False,
        }
        oracle = {
            "schema": catalog.ORACLE_SCHEMA,
            "atlasFamilyId": runtime_id,
            "initialPlanSha256": "c" * 64,
            "finalPlanSha256": plan_sha,
            "status": "PASS",
            "convergedIterations": 2,
            "probeCount": 1,
            "thresholds": {},
            "maximumObserved": {},
            "allProbesPass": True,
            "probes": [
                {
                    "perspective": "cabin",
                    "rpm": 3_000.0,
                    "throttle": 1.0,
                    "oracleWavSha256": "d" * 64,
                    "reconstructionSha256": "e" * 64,
                    "metrics": {},
                    "pass": True,
                }
            ],
            "sourceBankSha256": bank_sha,
            "finiteEffectInterpolationOracle": {
                "schema": "byd-full-event-finite-interpolation-oracle-v1",
                "runtimeAlgorithm": "perSourceAxisAlignedMultilinearFiniteRing-v2",
                "allPass": True,
                "probeCount": 0,
                "probes": [],
                "sourceResiduals": [],
            },
            "combinedEngineEffectMixOracle": {
                "schema": catalog.COMBINED_ENGINE_EFFECT_MIX_ORACLE_SCHEMA,
                "required": True,
                "status": "PASS",
                "allScenariosPass": True,
                "scenarioCount": 1,
                "requiredSchedulingGroupIds": ["layer:source-shift"],
                "scenarios": [
                    {
                        "id": "shift_group_lifecycle",
                        "pass": True,
                        "schedulingGroupIds": ["layer:source-shift"],
                    }
                ],
                "lifecycleOracle": {"allPass": True},
                "parameterPlacementLifecycleOracle": {
                    "schema": "byd-full-event-parameter-placement-lifecycle-oracle-v1",
                    "allPass": True,
                    "sourceCount": 0,
                    "sources": [],
                },
                "playlistSelectionOracle": {"allPass": True},
                "globalFmodChannelArbitrationOracle": {
                    "schema": "byd-full-event-fmod-channel-arbitration-oracle-v2",
                    "required": True,
                    "status": "PASS",
                    "assettoStudioLogicalChannelCap": 2048,
                    "assettoSoftwareRealChannelBudget": 256,
                    "rendererInitializationOrder": [
                        "FMOD_Studio_System_Create",
                        "FMOD_Studio_System_GetLowLevelSystem",
                        "FMOD_System_SetSoftwareChannels(256)",
                        "FMOD_Studio_System_Initialize(2048)",
                    ],
                    "requiredScenarios": [
                        "assettoDefaultCombinedEngineAndEffects",
                        "assettoAggressiveCombinedRpmThrottleTurboShift",
                        "assettoTwoHundredFiftySeventhRealVoiceContention",
                    ],
                    "requiredEvidence": [
                        "perDspBlockLogicalAndRealChannelSnapshots",
                        "callbackTracePerScheduledEventInstance",
                        "observed257thRealVoiceArbitrationOutcome",
                        "runtimeArbitrationPolicyMatchesObservedOutcome",
                    ],
                    "premixAdmissionParity": premix_admission_parity,
                    "observedVoiceBudgetOracle": observed_voice_budget,
                    "policy": {"algorithm": "fixture"},
                    "scenarios": [{}, {}, {}],
                },
            },
        }
        catalog._write_json(family / "oracle-status.json", oracle)
        oracle_sha = catalog.sha256_file(family / "oracle-status.json")
        runtime = {
            "schema": catalog.RUNTIME_SCHEMA,
            "id": runtime_id,
            "draftBlocked": draft_blocked,
            "planSha256": plan_sha,
            "oracleReportSha256": oracle_sha,
            "hostMixContract": catalog.HOST_MIX_CONTRACT,
            "modeRows": {
                "LOAD": {"throttle": 1.0, "livePedalIgnored": True},
                "COAST": {"throttle": 0.0, "livePedalIgnored": True},
                "BOTH": {"throttle": "livePedal"},
            },
            "perspectives": {
                "cabin": {
                    "rpmAxis": [1_000.0, 7_000.0],
                    "throttleAxis": [0.0, 1.0],
                    "nodes": [{"rpm": 1_000.0, "throttle": 0.0, "shardName": shard.name, "startFrame": 0, "endFrameExclusive": 64, "loopStartFrame": 4, "loopEndFrameExclusive": 60, "phaseOffsetFrames": 0.0, "modePrograms": {"loadOnly": {"shardName": shard.name, "startFrame": 64, "endFrameExclusive": 128, "loopStartFrame": 68, "loopEndFrameExclusive": 124}, "coastOnly": {"shardName": shard.name, "startFrame": 128, "endFrameExclusive": 192, "loopStartFrame": 132, "loopEndFrameExclusive": 188}}}],
                },
                "exterior": {
                    "rpmAxis": [1_000.0, 7_000.0],
                    "throttleAxis": [0.0, 1.0],
                    "nodes": [{"rpm": 1_000.0, "throttle": 0.0, "shardName": shard.name, "startFrame": 0, "endFrameExclusive": 64, "loopStartFrame": 4, "loopEndFrameExclusive": 60, "phaseOffsetFrames": 0.0, "modePrograms": {"loadOnly": {"shardName": shard.name, "startFrame": 64, "endFrameExclusive": 128, "loopStartFrame": 68, "loopEndFrameExclusive": 124}, "coastOnly": {"shardName": shard.name, "startFrame": 128, "endFrameExclusive": 192, "loopStartFrame": 132, "loopEndFrameExclusive": 188}}}],
                },
            },
            "hotCellPolicy": {},
            "effects": {
                "runtimeContract": {
                    "schema": catalog.COMPACT_EFFECT_RUNTIME_SCHEMA,
                    "variantBindingIdentity": "familyLocalVnRefPlusExactAuthoredBindingKeyAndSourceGuid",
                    "schedulingGroupIdentity": "familyLocalGnRefPlusExactAuthoredGroupId",
                    "runtimeMappingProfileIdentity": "familyLocalMnRefPlusCanonicalExecutableMapping",
                    "nodeBinding": "nodes[][0] is variantBindingRef resolving to authoredBindingKey",
                    "nodeEncoding": {
                        "schema": catalog.COMPACT_EFFECT_NODE_ENCODING_SCHEMA,
                        "fields": list(catalog.COMPACT_EFFECT_NODE_FIELDS),
                        "sourceIdentity": "nodes[][0] resolves to variantBindings[].authoredBindingKeyAndSourceGuid",
                        "finiteDurationFrames": "nodes[][4]-nodes[][3]",
                    },
                    "execution": {
                        "schema": "byd-full-event-effect-execution-contract-v1",
                        "continuous": {
                            "algorithm": "perSourceAxisAlignedMultilinear-v1",
                            "nodeIdentity": "requiredAuthoredBindingKeyPlusCanonicalParameters",
                        },
                        "oneShot": {
                            "algorithm": "perSourceAxisAlignedMultilinearFiniteRing-v2",
                            "cornerGainFormula": "rawNDimensionalMultilinearWeight",
                            "nodeIdentity": "requiredAuthoredBindingKeyPlusCanonicalParameters",
                            "logicalVoice": {
                                "pcm16Premix": "forbidden",
                                "sourceCornerRegions": "audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; noAudioCallbackMmapAllocationLockOrPcm16PremixStorage",
                            },
                        },
                    },
                    "selectionRuntimeContractTable": "selectionRuntimeContracts[].id",
                },
                "variantBindings": [
                    {
                        "id": "v0",
                        "sourceGuid": "source-shift",
                        "authoredBindingKey": "binding:" + "1" * 64,
                        "runtimeMappingRef": "m0",
                        "schedulingGroupRef": "g0",
                    }
                ],
                "runtimeMappingProfiles": [
                    {
                        "id": "m0",
                        "runtimeMapping": {
                            "hostGainClass": "effectEvent",
                        },
                    }
                ],
                "schedulingGroups": [
                    {
                        "id": "g0",
                        "groupId": "layer:source-shift",
                        "complete": True,
                        "selectionRuntimeContractRef": "s0",
                        "maximumSourceCornerContributorsPerLogicalRing": 1,
                        "maximumFmodSourceChannelsPerLogicalRing": 1,
                        "maximumCaptureFramesPerLogicalRing": 64,
                        "streamingRingBufferFrames": 12_288,
                    }
                ],
                "selectionRuntimeContracts": [
                    {
                        "id": "s0",
                        "contract": {
                            "schema": "byd-full-event-playlist-selection-v1",
                        },
                    }
                ],
                "events": [
                    {
                        "eventPath": "event:/cars/test/gear_int",
                        "eventSuffix": "gear_int",
                        "perspectives": ["cabin"],
                        "runtimeTriggers": ["TRANSMISSION_PULSE"],
                        "runtimeMappingBlocked": False,
                        "variantBindingRefs": ["v0"],
                        "schedulingGroupRefs": ["g0"],
                        "nodes": [
                            ["v0", {}, shard.name, 0, 64, None, None]
                        ],
                    }
                ],
            },
            "shards": [{"name": shard.name, "sha256": metadata.sha256, "bytes": metadata.size_bytes}],
        }
        plan = {
            "schema": catalog.PLAN_SCHEMA,
            "id": runtime_id,
            "planSha256": plan_sha,
            "bankSha256": bank_sha,
            "hostMixContract": catalog.HOST_MIX_CONTRACT,
            "sourceConservationEvidence": {"exactGuidSetEquality": True},
            "perspectives": {
                "cabin": {"nodes": [{"temporaryAssetName": "engine-cabin.wav", "modeProgramTemporaryAssetNames": {"loadOnly": "engine-cabin-load-only.wav", "coastOnly": "engine-cabin-coast-only.wav"}}]},
                "exterior": {"nodes": [{"temporaryAssetName": "engine-exterior.wav", "modeProgramTemporaryAssetNames": {"loadOnly": "engine-exterior-load-only.wav", "coastOnly": "engine-exterior-coast-only.wav"}}]},
            },
            "effects": [
                {
                    "eventPath": "event:/cars/test/gear_int",
                    "nodes": [{"temporaryAssetName": "effect.wav"}],
                    "runtimeLifecycleParameterVariantContract": {
                        "variants": [
                            {
                                "sourceGuid": "source-shift",
                                "authoredBindingKey": "binding:" + "1" * 64,
                                "runtimeMapping": {"hostGainClass": "effectEvent"},
                                "schedulingGroup": {
                                    "groupId": "layer:source-shift",
                                    "complete": True,
                                    "selectionRuntimeContract": {
                                        "schema": "byd-full-event-playlist-selection-v1",
                                    },
                                },
                            }
                        ],
                    },
                    "perspectiveResources": {
                        "cabin": {
                            "finite": {
                                "groups": [
                                    {
                                        "groupId": "layer:source-shift",
                                        "maximumSourceCornerContributorsPerLogicalRing": 1,
                                        "maximumFmodSourceChannelsPerLogicalRing": 1,
                                        "maximumCaptureFramesPerLogicalRing": 64,
                                        "streamingRingBufferFrames": 12_288,
                                    }
                                ]
                            }
                        }
                    },
                }
            ],
            "refinementGate": {
                "globalFmodChannelArbitrationOracle": {
                    "required": True,
                    "schema": "byd-full-event-fmod-channel-arbitration-oracle-v2",
                    "assettoStudioLogicalChannelCap": 2048,
                    "assettoSoftwareRealChannelBudget": 256,
                    "rendererInitializationOrder": [
                        "FMOD_Studio_System_Create",
                        "FMOD_Studio_System_GetLowLevelSystem",
                        "FMOD_System_SetSoftwareChannels(256)",
                        "FMOD_Studio_System_Initialize(2048)",
                    ],
                    "requiredScenarios": [
                        "assettoDefaultCombinedEngineAndEffects",
                        "assettoAggressiveCombinedRpmThrottleTurboShift",
                        "assettoTwoHundredFiftySeventhRealVoiceContention",
                    ],
                    "requiredEvidence": [
                        "perDspBlockLogicalAndRealChannelSnapshots",
                        "callbackTracePerScheduledEventInstance",
                        "observed257thRealVoiceArbitrationOutcome",
                        "runtimeArbitrationPolicyMatchesObservedOutcome",
                    ],
                    "premixAdmissionParity": premix_admission_parity,
                    "observedVoiceBudgetOracle": observed_voice_budget,
                }
            },
            "releaseGate": {"status": "PASS", "oracleReportSha256": oracle_sha, "convergedIterations": 2},
        }
        realization = {
            "schema": catalog.REALIZATION_SCHEMA,
            "planSha256": plan_sha,
            "atlasFamilyId": runtime_id,
            "sourceBankSha256Before": bank_sha,
            "sourceBankSha256After": bank_sha,
            "sourceBankUnchanged": True,
            "fullRun": True,
            "captureCount": 3,
            "captures": [
                {"temporaryAssetName": "effect.wav", "wavSha256": "1" * 64},
                {"temporaryAssetName": "engine-cabin.wav", "wavSha256": "2" * 64, "modePrograms": {"loadOnly": {"temporaryAssetName": "engine-cabin-load-only.wav", "wavSha256": "4" * 64}, "coastOnly": {"temporaryAssetName": "engine-cabin-coast-only.wav", "wavSha256": "5" * 64}}},
                {"temporaryAssetName": "engine-exterior.wav", "wavSha256": "3" * 64, "modePrograms": {"loadOnly": {"temporaryAssetName": "engine-exterior-load-only.wav", "wavSha256": "6" * 64}, "coastOnly": {"temporaryAssetName": "engine-exterior-coast-only.wav", "wavSha256": "7" * 64}}},
            ],
        }
        pack_report = {
            "schema": catalog.ATLAS_PACK_REPORT_SCHEMA,
            "planSha256": plan_sha,
            "atlasFamilyId": runtime_id,
            "nodeCount": 3,
            "assetCount": 7,
            "sourceAssetHashes": {
                "effect.wav": "1" * 64,
                "engine-cabin.wav": "2" * 64,
                "engine-cabin-load-only.wav": "4" * 64,
                "engine-cabin-coast-only.wav": "5" * 64,
                "engine-exterior.wav": "3" * 64,
                "engine-exterior-load-only.wav": "6" * 64,
                "engine-exterior-coast-only.wav": "7" * 64,
            },
            "shards": [{"shardName": shard.name, "sha256": metadata.sha256, "bytes": metadata.size_bytes}],
        }
        catalog._write_json(family / "runtime-index.json", runtime)
        runtime_payload = catalog.canonical_json_bytes(runtime) + b"\n"
        pack_report["runtimeIndex"] = {
            "schema": catalog.COMPACT_EFFECT_RUNTIME_SCHEMA,
            "bytes": len(runtime_payload),
            "canonicalJsonNewlineSha256": hashlib.sha256(runtime_payload).hexdigest(),
        }
        for name, value in (
            ("plan.json", plan),
            ("realization-report.json", realization),
            ("pack-report.json", pack_report),
        ):
            catalog._write_json(family / name, value)
        for car_id in car_ids:
            recipe = {
                "bank": {"sha256": bank_sha},
                "sourceConservationAudit": {"exactGuidSetEquality": True, "unmappedCoreBindings": []},
            }
            catalog._write_json(root / "cars" / car_id / "source-conservation-report.json", recipe)

        return runtime_id, bank_sha, car_ids

    def _refresh_runtime_attestation(self, root: Path, runtime_id: str) -> None:
        family = root / "families" / runtime_id
        runtime = json.loads((family / "runtime-index.json").read_text(encoding="utf-8"))
        payload = catalog.canonical_json_bytes(runtime) + b"\n"
        report_path = family / "pack-report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["runtimeIndex"] = {
            "schema": catalog.COMPACT_EFFECT_RUNTIME_SCHEMA,
            "bytes": len(payload),
            "canonicalJsonNewlineSha256": hashlib.sha256(payload).hexdigest(),
        }
        catalog._write_json(report_path, report)

    def test_release_family_validates_every_evidence_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root)

            runtime, assets, result = catalog.validate_release_family(
                atlas_root=root,
                runtime_id=runtime_id,
                bank_sha=bank_sha,
                member_car_ids=car_ids,
            )

            self.assertFalse(runtime["draftBlocked"])
            self.assertTrue(assets.is_dir())
            self.assertEqual(result["evidence"]["oracleProbeCount"], 1)

    def test_release_family_rejects_missing_mode_program_and_legacy_realization(self) -> None:
        mutations = (
            (
                "missing mode program",
                lambda realization: realization["captures"][1]["modePrograms"].pop("coastOnly"),
                "lacks independent mode programs",
            ),
            (
                "legacy schema",
                lambda realization: realization.__setitem__(
                    "schema",
                    "byd-full-event-atlas-realization-v1",
                ),
                "realization schema differs",
            ),
        )
        for label, mutate, expected_error in mutations:
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runtime_id, bank_sha, car_ids = self._write_fixture(root)
                realization_path = root / "families" / runtime_id / "realization-report.json"
                realization = json.loads(realization_path.read_text(encoding="utf-8"))
                mutate(realization)
                catalog._write_json(realization_path, realization)

                with self.assertRaisesRegex(catalog.CatalogBuildError, expected_error):
                    catalog.validate_release_family(
                        atlas_root=root,
                        runtime_id=runtime_id,
                        bank_sha=bank_sha,
                        member_car_ids=car_ids,
                    )

    def test_release_family_rejects_duplicate_shard_json_name_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root)
            runtime_path = root / "families" / runtime_id / "runtime-index.json"
            payload = runtime_path.read_text(encoding="utf-8")
            shard_name = '"name":"engine_cabin_atlas_000.wav"'
            self.assertIn(shard_name, payload)
            runtime_path.write_text(
                payload.replace(shard_name, f"{shard_name},{shard_name}", 1),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(catalog.CatalogBuildError, "duplicate JSON key 'name'"):
                catalog.validate_release_family(
                    atlas_root=root,
                    runtime_id=runtime_id,
                    bank_sha=bank_sha,
                    member_car_ids=car_ids,
                )

    def test_draft_runtime_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root, draft_blocked=True)

            with self.assertRaisesRegex(catalog.CatalogBuildError, "draft-blocked"):
                catalog.validate_release_family(
                    atlas_root=root,
                    runtime_id=runtime_id,
                    bank_sha=bank_sha,
                    member_car_ids=car_ids,
                )

    def test_legacy_lookahead_host_mix_contract_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root)
            runtime_path = root / "families" / runtime_id / "runtime-index.json"
            runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
            runtime["hostMixContract"]["postSumMaster"]["algorithm"] = "stereoLinkedLookaheadPeakLimiter-v1"
            runtime["hostMixContract"]["postSumMaster"]["lookaheadFrames"] = 240
            catalog._write_json(runtime_path, runtime)

            with self.assertRaisesRegex(catalog.CatalogBuildError, "host/master mix contract differs"):
                catalog.validate_release_family(
                    atlas_root=root,
                    runtime_id=runtime_id,
                    bank_sha=bank_sha,
                    member_car_ids=car_ids,
                )

    def test_unattested_runtime_start_trigger_and_binding_mutations_fail_closed(self) -> None:
        mutations = (
            (
                "start frame",
                lambda runtime: runtime["perspectives"]["cabin"]["nodes"][0].__setitem__("startFrame", 1),
            ),
            (
                "trigger",
                lambda runtime: runtime["effects"]["runtimeMappingProfiles"][0]["runtimeMapping"].__setitem__(
                    "semanticLifecycle", [{"trigger": "TRANSMISSION_PULSE"}]
                ),
            ),
            (
                "variant binding",
                lambda runtime: runtime["effects"]["events"][0]["nodes"][0].__setitem__(
                    0, "v999"
                ),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runtime_id, bank_sha, car_ids = self._write_fixture(root)
                runtime_path = root / "families" / runtime_id / "runtime-index.json"
                runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
                mutate(runtime)
                catalog._write_json(runtime_path, runtime)

                with self.assertRaisesRegex(catalog.CatalogBuildError, "does not attest the exact final runtime index"):
                    catalog.validate_release_family(
                        atlas_root=root,
                        runtime_id=runtime_id,
                        bank_sha=bank_sha,
                        member_car_ids=car_ids,
                    )

    def test_attested_noninteger_or_out_of_range_node_frames_fail_closed(self) -> None:
        mutations = (
            ("fractional", 1.5, "frame range is not integer JSON"),
            ("boolean", True, "frame range is not integer JSON"),
            ("outside", 64, "lies outside its WAV"),
        )
        for label, start_frame, expected_error in mutations:
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runtime_id, bank_sha, car_ids = self._write_fixture(root)
                runtime_path = root / "families" / runtime_id / "runtime-index.json"
                runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
                runtime["perspectives"]["cabin"]["nodes"][0]["startFrame"] = start_frame
                catalog._write_json(runtime_path, runtime)
                self._refresh_runtime_attestation(root, runtime_id)

                with self.assertRaisesRegex(catalog.CatalogBuildError, expected_error):
                    catalog.validate_release_family(
                        atlas_root=root,
                        runtime_id=runtime_id,
                        bank_sha=bank_sha,
                        member_car_ids=car_ids,
                    )

    def test_combined_mix_oracle_requires_passing_complete_scheduling_group_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root)
            oracle_path = root / "families" / runtime_id / "oracle-status.json"
            oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
            oracle["combinedEngineEffectMixOracle"]["scenarios"][0]["schedulingGroupIds"] = ["wrong_group"]
            catalog._write_json(oracle_path, oracle)

            with self.assertRaisesRegex(catalog.CatalogBuildError, "scenario coverage differs"):
                catalog.validate_release_family(
                    atlas_root=root,
                    runtime_id=runtime_id,
                    bank_sha=bank_sha,
                    member_car_ids=car_ids,
                )

    def test_combined_mix_oracle_requires_pass_nonempty_scenarios_and_exact_declared_groups(self) -> None:
        cases = (
            (
                "all scenarios pass",
                lambda report: report.__setitem__("allScenariosPass", False),
                "did not pass",
            ),
            (
                "nonempty scenarios",
                lambda report: report.update({"scenarioCount": 0, "scenarios": []}),
                "has no scenarios",
            ),
            (
                "declared required groups",
                lambda report: report.__setitem__("requiredSchedulingGroupIds", ["wrong_group"]),
                "required scheduling groups differ",
            ),
            (
                "lifecycle oracle",
                lambda report: report["lifecycleOracle"].__setitem__("allPass", False),
                "lifecycle oracle did not pass",
            ),
            (
                "playlist oracle",
                lambda report: report["playlistSelectionOracle"].__setitem__("allPass", False),
                "playlist selection oracle did not pass",
            ),
        )
        for label, mutate, expected_error in cases:
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runtime_id, bank_sha, car_ids = self._write_fixture(root)
                oracle_path = root / "families" / runtime_id / "oracle-status.json"
                oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
                mutate(oracle["combinedEngineEffectMixOracle"])
                catalog._write_json(oracle_path, oracle)

                with self.assertRaisesRegex(catalog.CatalogBuildError, expected_error):
                    catalog.validate_release_family(
                        atlas_root=root,
                        runtime_id=runtime_id,
                        bank_sha=bank_sha,
                        member_car_ids=car_ids,
                    )

    def test_combined_mix_oracle_rejects_missing_or_incompatible_schema_fields(self) -> None:
        cases = (
            (
                "missing field",
                lambda report: report.pop("allScenariosPass"),
                "missing required fields",
            ),
            (
                "old schema",
                lambda report: report.__setitem__("schema", "byd-combined-engine-effect-mix-oracle-v0"),
                "old or unknown schema",
            ),
        )
        for label, mutate, expected_error in cases:
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runtime_id, bank_sha, car_ids = self._write_fixture(root)
                oracle_path = root / "families" / runtime_id / "oracle-status.json"
                oracle = json.loads(oracle_path.read_text(encoding="utf-8"))
                mutate(oracle["combinedEngineEffectMixOracle"])
                catalog._write_json(oracle_path, oracle)

                with self.assertRaisesRegex(catalog.CatalogBuildError, expected_error):
                    catalog.validate_release_family(
                        atlas_root=root,
                        runtime_id=runtime_id,
                        bank_sha=bank_sha,
                        member_car_ids=car_ids,
                    )

    def test_fixture_release_assembly_emits_one_deduplicated_pack_and_two_catalog_cars(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime_id, bank_sha, car_ids = self._write_fixture(root)
            physics = {
                "minimumRpm": 0.0,
                "maximumRpm": 7_500.0,
                "idleRpm": 900.0,
                "redlineRpm": 7_000.0,
                "limiterRpm": 7_000.0,
                "upshiftRpm": 6_800.0,
                "gearRatios": [3.0, 2.0, 1.0],
                "soundFinalDriveRatio": 3.5,
                "soundDrivenWheelRadiusMeters": 0.35,
                "drivetrainSpeedControl": {
                    "parameterName": "drivetrain_speed",
                    "unit": "drivenWheelRadiansPerSecond",
                    "formula": "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters",
                    "signed": True,
                },
                "turbos": [],
                "turboBoostNormalization": {
                    "kind": "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST",
                    "divisor": 0.0,
                    "minimum": 0.0,
                    "maximum": 1.0,
                },
                "backfire": {
                    "maximumGas": 0.3,
                    "minimumRpm": 3_500.0,
                    "maximumRpm": 15_000.0,
                    "triggerGas": 0.8,
                    "minimumIntentThrottle": 0.4,
                    "minimumIntentSeconds": 1.0,
                },
                "limiterFrequencyHz": 0.0,
                "upshiftDurationSeconds": 0.08,
                "downshiftDurationSeconds": 0.12,
            }
            source = {
                "schema": catalog.SOURCE_SCHEMA,
                "catalogVersion": catalog.CATALOG_VERSION,
                "inventorySha256": "f" * 64,
                "auditSha256": "0" * 64,
                "cars": [
                    {
                        "id": f"ac_{car_id}",
                        "sourceCarId": car_id,
                        "displayName": car_id.replace("_", " ").title(),
                        "bankFamilyId": "shared",
                        "bankSha256": bank_sha,
                        "previewAssetName": f"car_previews/{car_id}.jpg",
                        "physics": physics,
                        "specifications": {"assettoCorsaCarId": car_id},
                    }
                    for car_id in car_ids
                ],
                "families": [
                    {
                        "bankFamilyId": "shared",
                        "bankSha256": bank_sha,
                        "representativeCarId": car_ids[0],
                        "memberCarIds": car_ids,
                        "requiredRetainedSourceGuidCount": 1,
                    }
                ],
            }
            catalog._write_json(root / "source.json", source)
            batch = {
                "schema": catalog.BATCH_SCHEMA,
                "carCount": 2,
                "familyCount": 1,
                "cars": [
                    {"carId": car_id, "audioProgramFamilyId": runtime_id}
                    for car_id in car_ids
                ],
            }
            catalog._write_json(root / "catalog.json", batch)

            with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(catalog, "EXPECTED_BANK_FAMILIES", 1):
                runtime_catalog, report = catalog.assemble_release(
                    source_catalog_path=root / "source.json",
                    atlas_root=root,
                    pack_output_directory=root / "release-packs",
                    runtime_index_output_directory=root / "apk-assets" / "families",
                    pack_version=1,
                )

            self.assertEqual(runtime_catalog["schema"], catalog.RELEASE_CATALOG_SCHEMA)
            self.assertEqual(runtime_catalog["catalogVersion"], catalog.RELEASE_CATALOG_VERSION)
            self.assertEqual(len(runtime_catalog["cars"]), 2)
            self.assertEqual(len(runtime_catalog["families"]), 1)
            self.assertEqual(
                {car["audioProgramFamilyId"] for car in runtime_catalog["cars"]},
                {runtime_id},
            )
            self.assertEqual(report["packCount"], 1)
            self.assertTrue((root / "release-packs" / f"{runtime_id}-v1.bydpack").is_file())
            descriptor = runtime_catalog["families"][0]
            self.assertEqual(descriptor["runtimeAssetName"], f"families/{runtime_id}.json")
            self.assertNotIn("runtimeIndex", descriptor)
            self.assertEqual(
                descriptor["eagerCapabilities"],
                {
                    "perspectives": ["cabin", "exterior"],
                    "effectControls": {
                        "cabin": {
                            "hasTurboEvent": False,
                            "runtimeTriggers": ["TRANSMISSION_PULSE"],
                        },
                        "exterior": {
                            "hasTurboEvent": False,
                            "runtimeTriggers": [],
                        },
                    },
                },
            )
            runtime_asset = root / "apk-assets" / "families" / f"{runtime_id}.json"
            self.assertTrue(runtime_asset.is_file())
            payload = runtime_asset.read_bytes()
            self.assertEqual(len(payload), descriptor["runtimeBytes"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), descriptor["runtimeSha256"])

    def test_batch_family_map_requires_duplicate_cars_to_share_one_program(self) -> None:
        source = {
            "cars": [
                {"sourceCarId": "car_one"},
                {"sourceCarId": "car_two"},
            ],
            "families": [
                {"bankFamilyId": "shared", "memberCarIds": ["car_one", "car_two"]},
            ],
        }
        batch = {
            "schema": catalog.BATCH_SCHEMA,
            "carCount": 2,
            "familyCount": 1,
            "cars": [
                {"carId": "car_one", "audioProgramFamilyId": "atlas_one"},
                {"carId": "car_two", "audioProgramFamilyId": "atlas_two"},
            ],
        }

        with patch.object(catalog, "EXPECTED_ACTIVE_CARS", 2), patch.object(catalog, "EXPECTED_BANK_FAMILIES", 1):
            with self.assertRaisesRegex(catalog.CatalogBuildError, "rendered more than once"):
                catalog._batch_family_map(batch, source)


if __name__ == "__main__":
    unittest.main()
