"""Silently realize a property-index-1 fallback through FMOD 1.08 target PCM."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import wave


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import derive_windowed_capture_fallback
from sim.fmod_bank_isolation import create_isolated_bank_copy
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_windowed_capture import realize_windowed_capture_fallback
from sim.loop_tools import crossfade_loop_seam, find_best_loop_bounds


SF15T_FAMILY = "4e384d921164da0e687dce51e8753ed41ea2c84f1925c6d2e60eb9195e090a74"
SF15T_SOURCE = "5169c3d1-950b-450b-884d-fbab12cc8cc9"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_wav(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError(f"noncanonical reference WAV {path}")
        frames = source.getnframes()
        return source.readframes(frames), frames


def _write_wav(path: Path, pcm: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(48000)
        target.writeframes(pcm)


def _repair_pack_loop(pcm: bytes) -> tuple[bytes, int, int, float]:
    frame_count = len(pcm) // 4
    guard = min(960, max(1, frame_count // 12))
    seam = find_best_loop_bounds(
        pcm,
        nominal_start=guard,
        nominal_end=frame_count - guard,
        search_frames=min(120, guard),
    )
    if seam.peak_dbfs > -36.0:
        pcm, seam = crossfade_loop_seam(
            pcm,
            seam.start_frame,
            seam.end_frame,
            crossfade_frames=min(960, (seam.end_frame - seam.start_frame) // 8),
        )
    if seam.peak_dbfs > -18.0:
        raise ValueError(f"unsafe realized loop seam {seam.peak_dbfs:.3f} dBFS")
    return pcm, seam.start_frame, seam.end_frame, seam.peak_dbfs


def realize(
    assetto_root: Path,
    graph_root: Path,
    classification_path: Path,
    family_id: str,
    source_guid: str,
    output_root: Path,
) -> dict:
    root = assetto_root.resolve(strict=True)
    graph = _load(graph_root / "families" / f"{family_id}.json")
    classification = _load(classification_path)
    row = next(
        item
        for item in classification["sourceDecisions"]
        if item.get("familyId") == family_id and item.get("sourceGuid") == source_guid
    )
    plan = derive_windowed_capture_fallback(graph, row)
    summary = _load(graph_root / "summary.json")
    family = next(item for item in summary["families"] if item["familyId"] == family_id)
    bank = root / str(family["bankPath"])
    before = _sha256(bank)
    if before != family_id:
        raise ValueError("installed bank identity differs from graph family")

    instruments = {item["guid"]: item for item in graph["instruments"]}
    source = instruments[source_guid]
    event = next(
        item
        for item in graph["events"]
        if item["path"] == plan["eventPath"]
    )
    reachable_waveforms = {
        guid
        for guid in event["reachableInstrumentGuids"]
        if instruments[guid]["kind"] == "WaveformInstrumentNode"
    }
    output_root.mkdir(parents=True, exist_ok=True)
    isolated_bank = output_root / "target-only.bank"
    isolation = create_isolated_bank_copy(
        bank,
        graph,
        reachable_waveforms - {source_guid},
        isolated_bank,
    )
    if source_guid in {patch.source_guid for patch in isolation.patches}:
        raise AssertionError("target source was patched by isolation")
    renderer = SilentFmodReferenceRenderer(root, dsp_buffer_frames=256)
    render_root = output_root / "renders"
    render_root.mkdir(parents=True, exist_ok=True)
    runtime_identity = str((source.get("sample") or {}).get("name") or "")
    callback_proofs: dict[str, dict] = {}

    def callback(recipe: dict) -> dict:
        recipe_sha256 = hashlib.sha256(canonical_json_bytes(recipe)).hexdigest()
        wav_path = render_root / f"{recipe_sha256}.wav"
        parameters = {
            str(name): float(value)
            for name, value in recipe["captureParameterValues"].items()
        }
        if not wav_path.is_file():
            renderer.render_event(
                isolation.output_path,
                plan["eventPath"],
                wav_path,
                parameters=parameters,
                warmup_frames=int(recipe["referenceRenderer"]["warmupFrames"]),
                duration_frames=int(recipe["referenceRenderer"]["analysisFrames"]),
            )
        # Render once more only for the first use of a recipe and require an
        # independent bit-exact result before it enters adaptive analysis.
        repeat_path = render_root / f"{recipe_sha256}-repeat.wav"
        if not repeat_path.is_file():
            reference = renderer.render_event(
                isolation.output_path,
                plan["eventPath"],
                repeat_path,
                parameters=parameters,
                warmup_frames=int(recipe["referenceRenderer"]["warmupFrames"]),
                duration_frames=int(recipe["referenceRenderer"]["analysisFrames"]),
            )
            if runtime_identity not in set(reference.scheduled_sound_names):
                raise AssertionError("target source did not schedule in isolated render")
        pcm, frames = _read_wav(wav_path)
        repeat_pcm, repeat_frames = _read_wav(repeat_path)
        if frames != repeat_frames or pcm != repeat_pcm:
            raise AssertionError("independent target-only property-1 renders differ")
        pack_pcm, loop_start, loop_end, seam_dbfs = _repair_pack_loop(pcm)
        callback_proofs[recipe_sha256] = {
            "captureRecipeSha256": recipe_sha256,
            "frameCount": frames,
            "pcmPayloadSha256": hashlib.sha256(pcm).hexdigest(),
            "independentRenderBitExact": True,
            "packPcmPayloadSha256": hashlib.sha256(pack_pcm).hexdigest(),
            "loopStartFrame": loop_start,
            "loopEndFrameExclusive": loop_end,
            "loopSeamPeakDbfs": seam_dbfs,
        }
        return {
            "pcm16le": pcm,
            "packPcm16le": pack_pcm,
            "frameCount": frames,
            "scheduledSourceGuids": [source_guid],
            "loopStartFrame": loop_start,
            "loopEndFrameExclusive": loop_end,
            "independentRenderBitExact": True,
        }

    realized = realize_windowed_capture_fallback(plan, callback)
    release_record = realized["releaseRecord"]
    media_root = output_root / "tracks"
    for track_id, pcm in realized["pcm16leByTrackId"].items():
        safe_name = hashlib.sha256(track_id.encode("utf-8")).hexdigest()[:20]
        wav = media_root / f"{safe_name}.wav"
        _write_wav(wav, pcm)
        track = next(item for item in release_record["tracks"] if item["trackId"] == track_id)
        track["compilerWavRelativePath"] = wav.relative_to(output_root).as_posix()
        track["compilerWavSha256"] = _sha256(wav)
    release_record.pop("realizationPayloadSha256", None)
    release_record["realizationPayloadSha256"] = hashlib.sha256(
        canonical_json_bytes(release_record)
    ).hexdigest()
    after = _sha256(bank)
    if after != before:
        raise AssertionError("installed bank was modified")
    return {
        "schema": "ac-fmod-windowed-capture-executable-proof-v1",
        "familyId": family_id,
        "sourceGuid": source_guid,
        "bankSha256Before": before,
        "bankSha256After": after,
        "installedBankUnchanged": before == after,
        "isolation": {
            "mutedWaveformCount": len(isolation.patches),
            "targetWasNotPatched": True,
            "isolatedBankSha256": isolation.output_sha256,
        },
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "signedPcm16LittleEndian",
            "audioDeviceOpened": False,
        },
        "callbackCaptures": [callback_proofs[key] for key in sorted(callback_proofs)],
        "releaseRecord": release_record,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument(
        "--graph-root",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3",
    )
    parser.add_argument(
        "--classification",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "source-role-classification-v2.json",
    )
    parser.add_argument("--family-id", default=SF15T_FAMILY)
    parser.add_argument("--source-guid", default=SF15T_SOURCE)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "windowed-capture-realizations" / SF15T_SOURCE,
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = realize(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.classification,
        args.family_id,
        args.source_guid,
        args.output_root,
    )
    report = args.report or args.output_root / "proof.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_bytes(canonical_json_bytes(result) + b"\n")
    print(
        f"tracks={len(result['releaseRecord']['tracks'])} "
        f"captures={len(result['callbackCaptures'])} evidence={report.resolve()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
