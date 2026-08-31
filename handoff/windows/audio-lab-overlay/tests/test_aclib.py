from __future__ import annotations

import array
import copy
import hashlib
import json
import math
import os
import tempfile
import unittest
import wave
import zipfile
from pathlib import Path

from sim.aclib import (
    ManifestValidationError,
    _validate_track,
    build_aclib,
    default_mix_peak_bound_dbfs,
    validate_aclib,
    validate_manifest,
    validate_release_manifest,
)
from sim.aclib_catalog import build_official_catalog, validate_catalog
from sim.assetto import find_assetto_root
from sim.flac_codec import PinnedFlacCodec, inspect_pcm16_stereo_wav
from sim.fmod_probe import SilentFmodBankProbe
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_sdk_audit import audit_shipped_fmod_authoring
from sim.huracan_regression import audit_huracan_loop_sources
from sim.loop_tools import crossfade_loop_seam, measure_loop_seam
from sim.official_cars import (
    EXPECTED_OFFICIAL_DIRECTORIES,
    EXPECTED_SOUND_FAMILIES,
    EXPECTED_USABLE_CARS,
    OFFICIAL_KUNOS_CAR_IDS,
    UNUSABLE_OFFICIAL_CAR_IDS,
)
from tools.aclib_compiler import build_capture_plan, validate_capture_plan


HASH_A = "a" * 64
HASH_B = "b" * 64


def manifest_fixture(encoded: bytes) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "familyId": HASH_A,
        "displayName": "Test car",
        "memberCarIds": ["test_car"],
        "audioFormat": {
            "codec": "FLAC",
            "sampleRate": 48000,
            "channels": 2,
            "bitsPerSample": 16,
        },
        "cars": [
            {
                "id": "test_car",
                "name": "Test car",
                "brand": "Test",
                "previewPath": None,
                "engine": {
                    "idleRpm": 900.0,
                    "redlineRpm": 7000.0,
                    "limiterRpm": 7000.0,
                    "limiterHz": 20.0,
                    "tachometerMaximumRpm": 7500.0,
                    "turboCount": 0,
                    "hybrid": False,
                    "hybridConfig": None,
                    "turboControllers": [],
                },
                "gearbox": {
                    "traction": "RWD",
                    "forwardRatios": [3.0, 2.0],
                    "reverseRatio": -3.0,
                    "finalDrive": 4.0,
                    "upshiftRpm": 6500,
                    "downshiftLandingRpmByGear": {"2": 4333.333333},
                    "upshiftTimeMs": 100.0,
                    "downshiftTimeMs": 150.0,
                    "alternateGearSets": [],
                },
            }
        ],
        "effects": {
            "idle": True,
            "coast": False,
            "texture": False,
            "intake": False,
            "exhaust": False,
            "turbo": False,
            "spool": False,
            "bov": False,
            "transmission": False,
            "limiter": False,
            "shift": False,
            "overrun": False,
            "popsBangsCracks": False,
        },
        "quirks": [],
        "tracks": [
            {
                "id": "idle",
                "role": "IDLE",
                "path": "audio/idle.flac",
                "flacSha256": hashlib.sha256(encoded).hexdigest(),
                "pcmSha256": HASH_B,
                "frameCount": 100,
                "sampleRate": 48000,
                "channels": 2,
                "bitsPerSample": 16,
                "rootRpm": 900.0,
                "loopStartFrame": 2,
                "loopEndFrame": 98,
                "gainDb": 0.0,
                "peakDbfs": -6.0,
                "rpmCurve": [[900.0, 1.0]],
                "gainCurve": [[0.0, 1.0]],
                "triggers": [],
            }
        ],
        "assets": [],
        "fidelity": {
            "sourceAudio": "nativeFmodFinalMix",
            "layerIsolation": "eventLevel",
            "rpmGainCurve": "compilerWindowApproximation",
            "effectVariants": "nativeRandomSequence",
            "notes": ["Test fidelity boundary."],
        },
        "provenance": {
            "source": "test",
            "sourceBankSha256": HASH_A,
            "catalogSha256": HASH_B,
            "capturePlanSha256": HASH_B,
            "referenceRenderer": "test",
            "familyAttenuationDb": 0.0,
            "defaultMixPeakDbfs": -6.0,
            "encoder": {
                "name": "libFLAC",
                "version": "1.5.0",
                "executableSha256": HASH_B,
            },
        },
    }


class ManifestTests(unittest.TestCase):
    def test_strict_manifest_and_atomic_pack(self) -> None:
        encoded = b"fLaC\x00\x00\x00\x22test"
        manifest = manifest_fixture(encoded)
        validate_manifest(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "audio").mkdir()
            (root / "audio" / "idle.flac").write_bytes(encoded)
            pack = build_aclib(manifest, root, root / "test.aclib")
            self.assertEqual(validate_aclib(pack)["familyId"], HASH_A)

    def test_forbidden_role_token_is_rejected_everywhere(self) -> None:
        manifest = manifest_fixture(b"fLaCtest")
        manifest["tracks"][0]["triggers"] = ["load"]  # type: ignore[index]
        with self.assertRaises(ManifestValidationError):
            validate_manifest(manifest)

    def test_unknown_fields_and_nonexclusive_bounds_are_rejected(self) -> None:
        manifest = manifest_fixture(b"fLaCtest")
        manifest["tracks"][0]["unexpected"] = True  # type: ignore[index]
        with self.assertRaises(ManifestValidationError):
            validate_manifest(manifest)

    def test_distinct_semantic_tracks_may_share_one_verified_media_member(self) -> None:
        encoded = b"fLaC\x00\x00\x00\x22shared"
        manifest = manifest_fixture(encoded)
        shared = copy.deepcopy(manifest["tracks"][0])  # type: ignore[index]
        shared.update(
            {
                "id": "shift_down",
                "role": "SHIFT_DOWN",
                "rootRpm": None,
                "loopStartFrame": 0,
                "loopEndFrame": 100,
                "rpmCurve": [],
                "triggers": ["shiftDown"],
            }
        )
        manifest["tracks"].append(shared)  # type: ignore[union-attr]
        manifest["effects"]["shift"] = True  # type: ignore[index]
        manifest["provenance"]["defaultMixPeakDbfs"] = default_mix_peak_bound_dbfs(  # type: ignore[index]
            manifest["tracks"], 7500.0  # type: ignore[arg-type]
        )
        validate_manifest(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "audio").mkdir()
            (root / "audio" / "idle.flac").write_bytes(encoded)
            pack = build_aclib(manifest, root, root / "shared.aclib")
            with zipfile.ZipFile(pack) as archive:
                self.assertEqual(archive.namelist().count("audio/idle.flac"), 1)

        mismatched = copy.deepcopy(manifest)
        mismatched["tracks"][1]["flacSha256"] = HASH_A  # type: ignore[index]
        with self.assertRaisesRegex(
            ManifestValidationError, "identical physical media"
        ):
            validate_manifest(mismatched)
        manifest = manifest_fixture(b"fLaCtest")
        manifest["tracks"][0]["loopEndFrame"] = 101  # type: ignore[index]
        with self.assertRaises(ManifestValidationError):
            validate_manifest(manifest)

    def test_overrun_is_an_authored_pops_bangs_cracks_capability(self) -> None:
        manifest = manifest_fixture(b"fLaCtest")
        overrun = copy.deepcopy(manifest["tracks"][0])  # type: ignore[index]
        overrun.update(
            {
                "id": "overrun",
                "role": "OVERRUN",
                "triggers": ["overrunRelease"],
            }
        )
        manifest["tracks"].append(overrun)  # type: ignore[union-attr]
        manifest["effects"]["overrun"] = True  # type: ignore[index]
        manifest["effects"]["popsBangsCracks"] = True  # type: ignore[index]
        validate_manifest(manifest)

        omitted_capability = copy.deepcopy(manifest)
        omitted_capability["effects"]["popsBangsCracks"] = False  # type: ignore[index]
        with self.assertRaisesRegex(
            ManifestValidationError, "effects must exactly describe"
        ):
            validate_manifest(omitted_capability)

    def test_v2_pitch_contract_is_required_and_capture_normalized(self) -> None:
        track = copy.deepcopy(manifest_fixture(b"fLaCtest")["tracks"][0])
        track.update(
            {
                "softwareChannelPriority": 64,
                "pitchMode": "AUTHORED_PROPERTY_ONE_RELATIVE_RATE",
                "pitchCurve": [[0.0, 0.75], [900.0, 1.0], [2000.0, 1.4]],
                "pitchCurveInterpolation": "CLAMPED_LINEAR",
                "rpmCurve": [[0.0, 0.0], [900.0, 1.0], [2000.0, 0.0]],
            }
        )
        _validate_track(track, 0, 2)

        for key in ("pitchMode", "pitchCurve", "pitchCurveInterpolation"):
            missing = copy.deepcopy(track)
            del missing[key]
            with self.assertRaises(ManifestValidationError):
                _validate_track(missing, 0, 2)

        wrong_root = copy.deepcopy(track)
        wrong_root["pitchCurve"][1][1] = 1.01
        with self.assertRaisesRegex(ManifestValidationError, "capture-normalized"):
            _validate_track(wrong_root, 0, 2)

        over_rate = copy.deepcopy(track)
        over_rate["pitchCurve"][-1][1] = math.nextafter(16.0, math.inf)
        with self.assertRaises(ManifestValidationError):
            _validate_track(over_rate, 0, 2)

        wrong_domain = copy.deepcopy(track)
        wrong_domain["pitchCurve"][-1][0] = 1999.0
        with self.assertRaisesRegex(ManifestValidationError, "exactly match"):
            _validate_track(wrong_domain, 0, 2)

        ordinary = copy.deepcopy(track)
        ordinary.update(
            {
                "pitchMode": "AUTO_PITCH_RPM_RATIO",
                "pitchCurve": [],
                "pitchCurveInterpolation": "NONE",
            }
        )
        _validate_track(ordinary, 0, 2)
        ordinary["pitchCurve"] = [[0.0, 1.0], [2000.0, 1.0]]
        with self.assertRaisesRegex(ManifestValidationError, "empty pitchCurve"):
            _validate_track(ordinary, 0, 2)


class LoopRepairTests(unittest.TestCase):
    def test_crossfade_replaces_a_discontinuous_wrap(self) -> None:
        samples = array.array("h")
        for frame in range(6000):
            phase = 2.0 * math.pi * frame / 173.0
            offset = 9000 if frame >= 5000 else 0
            value = max(-32768, min(32767, round(math.sin(phase) * 12000 + offset)))
            samples.extend((value, value))
        if os.sys.byteorder != "little":
            samples.byteswap()
        pcm = samples.tobytes()
        before = measure_loop_seam(pcm, 100, 5500)
        repaired, after = crossfade_loop_seam(pcm, 100, 5500, crossfade_frames=480)
        self.assertEqual(len(repaired), len(pcm))
        self.assertEqual(after.start_frame, 580)
        self.assertLess(after.sample_delta, before.sample_delta)


class InstalledCompilerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.root = find_assetto_root()
        except FileNotFoundError as exc:
            raise unittest.SkipTest(str(exc)) from exc

    def test_canonical_first_party_set_is_exact(self) -> None:
        self.assertEqual(len(OFFICIAL_KUNOS_CAR_IDS), EXPECTED_OFFICIAL_DIRECTORIES)
        self.assertEqual(len(set(OFFICIAL_KUNOS_CAR_IDS)), EXPECTED_OFFICIAL_DIRECTORIES)
        self.assertEqual(
            len(set(OFFICIAL_KUNOS_CAR_IDS) - UNUSABLE_OFFICIAL_CAR_IDS),
            EXPECTED_USABLE_CARS,
        )

    def test_installed_catalog_has_178_cars_and_153_exact_banks(self) -> None:
        catalog = build_official_catalog(self.root, strict_complete=True)
        validate_catalog(catalog, require_complete=True)
        self.assertEqual(catalog["counts"]["usableCars"], EXPECTED_USABLE_CARS)
        self.assertEqual(catalog["counts"]["soundFamilies"], EXPECTED_SOUND_FAMILIES)
        gra = next(car for car in catalog["cars"] if car["id"] == "bmw_m3_e30_gra")
        self.assertIn("requiresBmwM3E30GraAdditionalDsp", gra["quirks"])
        tatuus = next(car for car in catalog["cars"] if car["id"] == "tatuusfa1")
        self.assertAlmostEqual(
            tatuus["gearbox"]["downshiftLandingRpmByGear"]["2"],
            6300.0 * 1.94 / 2.36,
            places=5,
        )

    def test_probe_uses_no_sound_and_supports_extra_gain_dsp(self) -> None:
        with SilentFmodBankProbe(self.root) as probe:
            for car_id in ("tatuusfa1", "bmw_m3_e30_gra"):
                bank = next((self.root / "content" / "cars" / car_id / "sfx").glob("*.bank"))
                events = probe.probe(bank)
                self.assertTrue(any(path.endswith("/engine_int") for path in events))

    def test_bmw_gra_authored_gain_dsp_is_observed_and_baked(self) -> None:
        bank = next(
            (self.root / "content" / "cars" / "bmw_m3_e30_gra" / "sfx").glob(
                "*.bank"
            )
        )
        renderer = SilentFmodReferenceRenderer(self.root)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "gra.wav"
            rendered = renderer.render_event(
                bank,
                "engine_int",
                output,
                parameters={"rpms": 4000.0, "throttle": 0.0},
                duration_frames=4096,
                warmup_frames=0,
            )
            self.assertEqual(rendered.frame_count, 4096)
            self.assertGreater(inspect_pcm16_stereo_wav(output).peak_dbfs, -96.0)
        observed = renderer.last_gain_dsp_parameter_observations
        self.assertIn((0, -0.5), observed["float"])
        self.assertIn((1, False), observed["bool"])

    def test_capture_plan_is_strict_and_tatuus_recipes_are_honest(self) -> None:
        with SilentFmodBankProbe(self.root) as probe:
            catalog = build_official_catalog(
                self.root, event_probe=probe.probe, strict_complete=True
            )
        plan = build_capture_plan(catalog)
        validate_capture_plan(plan, catalog)
        family = next(
            item for item in plan["families"] if item["representativeCarId"] == "tatuusfa1"
        )
        recipes = family["recipes"]
        self.assertEqual(sum(recipe["role"] == "IDLE" for recipe in recipes), 1)
        self.assertEqual(sum(recipe["role"] == "COAST" for recipe in recipes), 7)
        self.assertFalse(any(recipe["role"] in {"POP", "BANG", "CRACK"} for recipe in recipes))
        overrun = [recipe for recipe in recipes if recipe["role"] == "OVERRUN"]
        self.assertEqual(len(overrun), 8)
        self.assertEqual({recipe["triggers"][0] for recipe in overrun}, {"overrunRelease"})
        self.assertTrue(
            any(
                len({point[1] for point in recipe["gainCurve"]}) > 1
                for recipe in recipes
                if recipe["role"] != "IDLE"
            )
        )
        changed = copy.deepcopy(plan)
        changed["families"][0]["recipes"][0]["unexpected"] = True
        with self.assertRaises(ValueError):
            validate_capture_plan(changed, catalog)

    def test_shipped_sdk_proves_source_role_and_curve_fidelity_boundary(self) -> None:
        report = audit_shipped_fmod_authoring(self.root)
        findings = report["findings"]
        self.assertEqual(findings["engineEvents"], 2)
        self.assertGreater(findings["allowedSourceInstruments"], 0)
        self.assertGreater(findings["excludedLoadSourceInstruments"], 0)
        self.assertFalse(findings["eventLevelCaptureGuaranteesRoleExclusion"])
        self.assertTrue(findings["curveShapeMetadataPresent"])
        roles = {
            group["manifestRole"]
            for event in report["events"]
            for group in event["groups"]
        }
        self.assertTrue({"COAST", "EXHAUST", "EXCLUDED_LOAD"} <= roles)

    def test_reference_writer_outputs_audible_pcm_without_playback(self) -> None:
        bank = self.root / "content" / "cars" / "tatuusfa1" / "sfx" / "tatuusfa1.bank"
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "idle.wav"
            SilentFmodReferenceRenderer(self.root).render_event(
                bank,
                "engine_int",
                output,
                parameters={"rpms": 1250.0, "throttle": 0.0},
                duration_frames=12000,
                warmup_frames=4800,
            )
            integrity = inspect_pcm16_stereo_wav(output)
            self.assertEqual(integrity.frame_count, 12000)
            self.assertGreater(integrity.peak_dbfs, -96.0)

    def test_pinned_flac_round_trip_when_bootstrapped(self) -> None:
        candidates = list((Path(__file__).resolve().parents[1] / ".aclib-tools").rglob("flac.exe"))
        if not candidates:
            self.skipTest("pinned FLAC tool cache is not bootstrapped")
        codec = PinnedFlacCodec(candidates[0])
        with tempfile.TemporaryDirectory() as temporary:
            wav_path = Path(temporary) / "input.wav"
            flac_path = Path(temporary) / "output.flac"
            samples = array.array("h", [1000, -1000] * 4800)
            if os.sys.byteorder != "little":
                samples.byteswap()
            with wave.open(str(wav_path), "wb") as output:
                output.setnchannels(2)
                output.setsampwidth(2)
                output.setframerate(48000)
                output.writeframes(samples.tobytes())
            before = codec.encode_level8(wav_path, flac_path)
            self.assertEqual(before, codec.decode_integrity(flac_path))

    def test_huracan_c1_c3_limiter_loop_regressions_when_present(self) -> None:
        source = Path(
            r"D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound\audio_samples"
            r"\fx_lamborghini_huracan_trofeo_evo2\converted"
        )
        if not source.is_dir():
            self.skipTest("local Huracán regression sources are absent")
        report = audit_huracan_loop_sources(source)
        self.assertEqual([track["id"] for track in report["tracks"]], ["c1", "c3", "limiter"])
        for track in report["tracks"]:
            self.assertLessEqual(track["selectedExclusiveLoop"]["seamPeakDbfs"], -36.0)
            self.assertLessEqual(
                track["sourcePeakDbfs"] + track["requiredAttenuationDb"], -3.099
            )
            self.assertTrue(track["requiresCanonical48KhzRender"])
        c1 = report["tracks"][0]
        self.assertGreater(c1["sourceRailSampleCount"], 0)

    def test_generated_tatuus_pack_release_regression_when_present(self) -> None:
        family_id = "668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab"
        pack = Path(__file__).resolve().parents[1] / ".aclib-local" / "packs" / f"{family_id}.aclib"
        if not pack.is_file():
            self.skipTest("generated Tatuus regression pack is absent")
        manifest = validate_aclib(pack)
        with self.assertRaises(ManifestValidationError):
            validate_release_manifest(manifest)
        tracks = manifest["tracks"]
        roles = {track["role"] for track in tracks}
        self.assertEqual(len({track["pcmSha256"] for track in tracks}), len(tracks))
        self.assertFalse({"TURBO", "SPOOL", "BOV", "TRANSMISSION", "POP", "BANG", "CRACK"} & roles)
        self.assertTrue(manifest["effects"]["popsBangsCracks"])
        self.assertFalse(manifest["effects"]["intake"])
        self.assertTrue(manifest["effects"]["overrun"])
        self.assertTrue(all(track["peakDbfs"] >= -48.0 for track in tracks))
        maximum_rpm = max(
            car["engine"]["tachometerMaximumRpm"] for car in manifest["cars"]
        )
        self.assertLessEqual(default_mix_peak_bound_dbfs(tracks, maximum_rpm), -3.09)
        overrun = [track for track in tracks if track["role"] == "OVERRUN"]
        self.assertGreater(len(overrun), 1)
        self.assertEqual(len({track["pcmSha256"] for track in overrun}), len(overrun))


if __name__ == "__main__":
    unittest.main()
