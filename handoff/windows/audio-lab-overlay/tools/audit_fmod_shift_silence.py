"""Create source-bound proof for authored all-zero AC shift instruments."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import tempfile
import wave


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in os.sys.path:
    os.sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.flac_codec import inspect_pcm16_stereo_wav
from sim.fmod_bank_isolation import (
    create_isolated_bank_copy,
    fully_muted_multi_instrument_guids,
)
from sim.fmod_graph_roles import (
    POLICY_ALLOW_CANDIDATE,
    ROLE_GEAR_SHIFT,
    classify_bank_graph_sources,
)
from sim.fmod_native import FMOD_VERSION
from sim.fmod_renderer import SilentFmodReferenceRenderer
from tools.aclib_release import ReleaseCapturePlanError, validate_release_capture_plan
from tools.audit_fmod_bank_graph import validate_bank_graph_report


SCHEMA = "ac-fmod-shift-silence-oracle-v1"
DEFAULT_OUTPUT_ROOT = Path(
    r"D:\Users\sgabr\BYDMotorSoundData\shift-silence-oracle-v1"
)
EXPECTED_SOURCES = (
    {
        "familyId": "0a26446f58dfce705b88e028fbb40c52fe444bc82acacb2b5ccf065a68cefd67",
        "sourceGuid": "f504195a-1ecc-4705-a6f4-189e419863f5",
        "role": "SHIFT_UP",
        "state": 1.0,
    },
    {
        "familyId": "4544b0286cae2b93a745c99e86665f34e614a902d15b76a243b6da7b5f74049a",
        "sourceGuid": "60031308-f9c2-4f43-8949-a7e2528e3d63",
        "role": "SHIFT_UP",
        "state": 1.0,
    },
)
BOOTSTRAP_CAPTURE_PLAN_SHA256 = (
    "e558a0d27f57e372073da4d281c46414fa76e8092229371df2c697c758bc4f7e"
)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(canonical_json_bytes(value) + b"\n")
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _pcm_is_exact_zero(path: Path) -> bool:
    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError("shift oracle render is not PCM16/48k/stereo")
        return not any(source.readframes(source.getnframes()))


def build_shift_silence_proof(
    assetto_root: Path,
    catalog: dict,
    plan: dict,
    graph_root: Path,
    output_root: Path,
) -> dict:
    plan_sha256 = hashlib.sha256(canonical_json_bytes(plan)).hexdigest()
    try:
        validate_release_capture_plan(plan, catalog, require_renderable=True)
    except ReleaseCapturePlanError:
        # The source-bound certificate necessarily bootstraps the schema field
        # that will carry it.  Only the immutable, previously fully validated
        # static plan is accepted for that one circular provenance boundary.
        if (
            plan_sha256 != BOOTSTRAP_CAPTURE_PLAN_SHA256
            or plan.get("schemaVersion") != 2
            or plan.get("catalogSha256") != catalog.get("catalogSha256")
            or "shiftSilenceOracle" in plan
        ):
            raise
    root = assetto_root.resolve(strict=True)
    graph_directory = graph_root.resolve(strict=True) / "families"
    output = output_root.resolve()
    captures = output / "captures"
    scratch = output / "scratch"
    captures.mkdir(parents=True, exist_ok=True)
    scratch.mkdir(parents=True, exist_ok=True)
    cars = {car["id"]: car for car in catalog["cars"]}
    catalog_families = {
        family["id"]: family for family in catalog["soundFamilies"]
    }
    plan_families = {
        family["familyId"]: family for family in plan["families"]
    }
    renderer = SilentFmodReferenceRenderer(root)
    verifications: list[dict] = []
    for expected in EXPECTED_SOURCES:
        family_id = expected["familyId"]
        source_guid = expected["sourceGuid"]
        family = catalog_families[family_id]
        planned = plan_families[family_id]
        representative = cars[planned["representativeCarId"]]
        recipes = [
            recipe
            for recipe in planned["recipes"]
            if recipe["sourceGuid"] == source_guid
        ]
        if len(recipes) != 1:
            raise ValueError("silent shift source did not resolve one plan recipe")
        recipe = recipes[0]
        if (
            recipe["role"] != expected["role"]
            or recipe["parameters"] != {"state": expected["state"]}
            or recipe["looping"] is not False
        ):
            raise ValueError("silent shift recipe role/state changed")
        graph_path = graph_directory / f"{family_id}.json"
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
        validate_bank_graph_report(graph)
        if graph["bank"]["sha256"] != family_id:
            raise ValueError("silent shift graph/source family changed")
        classification = classify_bank_graph_sources(graph)
        rows = [
            row
            for row in classification["sources"]
            if row["sourceGuid"] == source_guid
        ]
        if (
            len(rows) != 1
            or rows[0]["policy"] != POLICY_ALLOW_CANDIDATE
            or rows[0]["role"] != ROLE_GEAR_SHIFT
            or rows[0]["lifetime"] != "oneShot"
        ):
            raise ValueError("silent shift source classification changed")
        instruments = {
            item["guid"]: item for item in graph["instruments"]
        }
        target = instruments[source_guid]
        base = target["baseProperties"]
        if float(base["volumeDb"]) != -49.0:
            raise ValueError("silent shift source no longer has -49 dB base volume")
        sample = target.get("sample")
        if not isinstance(sample, dict) or not isinstance(sample.get("name"), str):
            raise ValueError("silent shift source has no runtime identity")
        event = next(
            item for item in graph["events"] if item["path"] == recipe["eventPath"]
        )
        reachable = {
            guid
            for guid in event["reachableInstrumentGuids"]
            if instruments[guid]["kind"] == "WaveformInstrumentNode"
        }
        if source_guid not in reachable:
            raise ValueError("silent shift source is not event-reachable")
        muted = reachable - {source_guid}
        disabled_parents = fully_muted_multi_instrument_guids(graph, muted)
        bank = root / representative["provenance"]["bankPath"]
        installed_before = _sha256_file(bank)
        if installed_before != family_id:
            raise ValueError("installed silent shift bank changed")
        rendered_evidence: list[dict] = []
        for take in (1, 2):
            isolated = scratch / f"{source_guid}-take{take}.bank"
            isolated_record = create_isolated_bank_copy(
                bank,
                graph,
                muted,
                isolated,
                disabled_parent_guids=disabled_parents,
            )
            wav = captures / f"{source_guid}-take{take}.wav"
            rendered = renderer.render_event(
                isolated,
                recipe["event"],
                wav,
                parameters={"state": expected["state"]},
                duration_frames=96000,
                warmup_frames=0,
            )
            integrity = inspect_pcm16_stereo_wav(wav)
            if (
                rendered.scheduled_sound_names != (sample["name"],)
                or math.isfinite(integrity.peak_dbfs)
                or not _pcm_is_exact_zero(wav)
                or integrity.frame_count != 96000
            ):
                raise ValueError("silent shift target render is not exact all-zero PCM")
            rendered_evidence.append(
                {
                    "take": take,
                    "scheduledSoundNames": list(rendered.scheduled_sound_names),
                    "finalWavRelativePath": f"captures/{wav.name}",
                    "finalWavSha256": _sha256_file(wav),
                    "pcmPayloadSha256": integrity.sha256,
                    "frameCount": integrity.frame_count,
                    "isolatedBankSha256": isolated_record.output_sha256,
                    "mutedWaveformCount": len(isolated_record.patches),
                    "disabledParentCount": len(
                        isolated_record.disabled_parent_patches
                    ),
                    "exactAllZeroPcm": True,
                }
            )
            isolated.unlink(missing_ok=True)
        if _sha256_file(bank) != installed_before:
            raise ValueError("installed shift bank changed during oracle renders")
        verification = {
            "familyId": family_id,
            "representativeCarId": representative["id"],
            "sourceGuid": source_guid,
            "role": expected["role"],
            "eventPath": recipe["eventPath"],
            "captureParameterValues": {"state": expected["state"]},
            "sourceRuntimeIdentity": sample["name"],
            "graphReportSha256": _sha256_file(graph_path),
            "installedBankRelativePath": representative["provenance"]["bankPath"],
            "installedBankSha256BeforeAndAfter": installed_before,
            "graphBaseVolumeDb": -49.0,
            "renderContract": {
                "fmodVersionHex": f"0x{FMOD_VERSION:08x}",
                "fmodCoreSha256": _sha256_file(root / "fmod64.dll"),
                "fmodStudioSha256": _sha256_file(root / "fmodstudio64.dll"),
                "output": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
                "opensPlaybackDevice": False,
                "sampleRate": 48000,
                "channels": 2,
                "bitsPerSample": 16,
                "dspBufferFrames": 256,
                "durationFrames": 96000,
                "warmupFrames": 0,
                "independentRenderCount": 2,
            },
            "renders": rendered_evidence,
            "audibilityDisposition": "AUTHORED_TARGET_SILENT",
        }
        verification["verificationPayloadSha256"] = hashlib.sha256(
            canonical_json_bytes(verification)
        ).hexdigest()
        verifications.append(verification)
    if len(verifications) != len(EXPECTED_SOURCES):
        raise AssertionError("shift silence proof source count changed")
    proof = {
        "schema": SCHEMA,
        "catalogSha256": catalog["catalogSha256"],
        "capturePlanSha256": plan_sha256,
        "sourceCount": len(verifications),
        "candidateDiscovery": "STRICT_RELEASE_COMPILE_EXACT_ZERO_PCM",
        "sourceVerifications": sorted(
            verifications, key=lambda item: (item["familyId"], item["sourceGuid"])
        ),
    }
    shutil.rmtree(scratch, ignore_errors=True)
    return proof


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root")
    parser.add_argument(
        "--catalog", default=str(PROJECT_ROOT / ".aclib-local" / "catalog-v1.json")
    )
    parser.add_argument(
        "--capture-plan",
        default=str(
            Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
            / "capture-plan-v2-static-c993755d.json"
        ),
    )
    parser.add_argument(
        "--graph-root",
        default=str(PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"),
    )
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    args = parser.parse_args()
    catalog = json.loads(Path(args.catalog).read_text(encoding="utf-8"))
    plan = json.loads(Path(args.capture_plan).read_text(encoding="utf-8"))
    output_root = Path(args.output_root)
    proof = build_shift_silence_proof(
        find_assetto_root(args.assetto_root),
        catalog,
        plan,
        Path(args.graph_root),
        output_root,
    )
    proof_path = output_root / "proof.json"
    _write_json_atomic(proof_path, proof)
    print(
        f"proof={proof_path} sources={proof['sourceCount']} "
        f"sha256={_sha256_file(proof_path)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
