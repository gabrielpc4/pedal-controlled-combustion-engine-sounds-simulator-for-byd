"""Build bit-exact private Huracan c1/c3/limiter FLAC seam regressions.

This is deliberately separate from the official Kunos/DLC catalog.  It reads
the user's private Trofeo EVO2 FMOD bank, creates byte-proven target-only bank
copies outside the Assetto Corsa installation, renders through AC's FMOD
1.08.12 runtime in non-realtime WAV-writer mode, repairs exclusive-end loop
seams, applies the release peak ceiling, and proves a FLAC 1.5.0 round trip.
Neither the source bank nor the legacy 44.1 kHz extractions are modified.
"""

from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sys
import tempfile
import wave
from dataclasses import asdict, dataclass
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.flac_codec import (
    PinnedFlacCodec,
    bootstrap_pinned_flac,
    calibrate_pcm16_stereo_wav,
    inspect_pcm16_stereo_wav,
)
from sim.fmod_bank_isolation import (
    create_isolated_bank_copy,
    fully_muted_multi_instrument_guids,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.loop_tools import (
    crossfade_loop_seam,
    find_best_loop_bounds,
    measure_loop_seam,
)


SOURCE_BANK_SHA256 = (
    "74f5053dfcae0529027b37da993ece36d2ff3d26102af8370bfe6589d8f2479c"
)
GRAPH_REPORT_SHA256 = (
    "00ac2f468b72d2cf08b9256e7c3c148ba21b261cadbca46cc8c324ca05cc444f"
)
EVENT_PATH = "event:/cars/fx_lamborghini_huracan_trofeo_evo2/engine_int"
SAFE_SEAM_DBFS = -36.0
PEAK_CEILING_DBFS = -3.1


@dataclass(frozen=True)
class Target:
    track_id: str
    source_guid: str
    runtime_name: str
    encoded_payload_sha256: str
    source_frames: int
    source_rate_hz: int
    start_rpm: float
    capture_rpm: float
    throttle: float
    warmup_frames: int
    capture_frames: int


TARGETS = (
    Target(
        "c1",
        "ac272141-a129-481d-9cc0-800fc20369b4",
        "Hur_C1",
        "af5ffbd673c46583c67ef639fcf5826d27c725fbd289fcf61a907fef608403d3",
        117591,
        44100,
        20000.0,
        7448.0,
        0.0,
        4096,
        96000,
    ),
    Target(
        "c3",
        "94d79f0d-0774-4c17-9fa6-d03642b1082a",
        "Hur_C3",
        "e03b831a402c18f3ea7654a3c5c52ac0096591d8951a7b7375e6c5552f8c973c",
        148402,
        44100,
        20000.0,
        4821.6,
        0.0,
        4096,
        120000,
    ),
    Target(
        "limiter",
        "49803d53-4f33-424f-afae-eccd80c1733a",
        "Hur_LIM",
        "fbee08ee9decffa0190892036d02c8901025495ccda5dc42b70356e7a164ec05",
        26474,
        44100,
        0.0,
        8133.1494,
        0.9,
        4096,
        16000,
    ),
)


class HuracanPrivateRegressionError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise HuracanPrivateRegressionError(f"JSON root is not an object: {path}")
    return value


def _read_pcm(path: Path) -> bytes:
    with wave.open(str(path), "rb") as source:
        metadata = (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        )
        if metadata != (48000, 2, 2, "NONE"):
            raise HuracanPrivateRegressionError(
                f"reference render is not canonical PCM16/48 kHz/stereo: {metadata}"
            )
        return source.readframes(source.getnframes())


def _write_pcm(path: Path, pcm: bytes) -> None:
    if len(pcm) % 4:
        raise HuracanPrivateRegressionError("PCM is not integral stereo frames")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(48000)
        target.writeframes(pcm)


def _rail_sample_count(pcm: bytes) -> int:
    values = array.array("h")
    values.frombytes(pcm)
    if sys.byteorder != "little":
        values.byteswap()
    return sum(value in (-32768, 32767) for value in values)


def _repair_loop(pcm: bytes) -> tuple[bytes, dict[str, Any]]:
    frames = len(pcm) // 4
    guard = min(960, max(2, frames // 12))
    original = measure_loop_seam(pcm, guard, frames - guard)
    selected = find_best_loop_bounds(
        pcm,
        nominal_start=guard,
        nominal_end=frames - guard,
        search_frames=min(720, guard),
    )
    before_repair = selected
    crossfade_frames = 0
    if selected.peak_dbfs > SAFE_SEAM_DBFS:
        crossfade_frames = min(960, (selected.end_frame - selected.start_frame) // 8)
        pcm, selected = crossfade_loop_seam(
            pcm,
            selected.start_frame,
            selected.end_frame,
            crossfade_frames=crossfade_frames,
        )
    if selected.peak_dbfs > SAFE_SEAM_DBFS:
        raise HuracanPrivateRegressionError(
            f"loop repair did not reach {SAFE_SEAM_DBFS} dBFS: "
            f"{selected.peak_dbfs:.6f} dBFS"
        )
    return pcm, {
        "nominal": {
            "startFrame": original.start_frame,
            "endFrameExclusive": original.end_frame,
            "seamPeakDbfs": original.peak_dbfs,
        },
        "bestBeforeRepair": {
            "startFrame": before_repair.start_frame,
            "endFrameExclusive": before_repair.end_frame,
            "sampleDelta": before_repair.sample_delta,
            "derivativeDelta": before_repair.derivative_delta,
            "seamPeakDbfs": before_repair.peak_dbfs,
        },
        "selectedExclusiveEnd": {
            "startFrame": selected.start_frame,
            "endFrameExclusive": selected.end_frame,
            "sampleDelta": selected.sample_delta,
            "derivativeDelta": selected.derivative_delta,
            "seamPeakDbfs": selected.peak_dbfs,
        },
        "crossfadeFrames": crossfade_frames,
    }


def _validate_graph(
    graph_path: Path, bank: Path
) -> tuple[dict[str, Any], dict[str, dict[str, Any]], set[str]]:
    if _sha256(graph_path) != GRAPH_REPORT_SHA256:
        raise HuracanPrivateRegressionError("private graph report identity changed")
    graph = _load_json(graph_path)
    bank_record = graph.get("bank")
    if not isinstance(bank_record, dict) or bank_record.get("sha256") != _sha256(bank):
        raise HuracanPrivateRegressionError("graph report does not match private bank")
    instruments = graph.get("instruments")
    if not isinstance(instruments, list):
        raise HuracanPrivateRegressionError("graph report has no instruments")
    by_guid = {
        str(item.get("guid")): item
        for item in instruments
        if isinstance(item, dict) and item.get("guid")
    }
    events = graph.get("events")
    if not isinstance(events, list):
        raise HuracanPrivateRegressionError("graph report has no events")
    matches = [item for item in events if item.get("path") == EVENT_PATH]
    if len(matches) != 1:
        raise HuracanPrivateRegressionError("engine_int event identity is ambiguous")
    reachable = set(matches[0].get("reachableInstrumentGuids") or ())
    for target in TARGETS:
        source = by_guid.get(target.source_guid)
        sample = source.get("sample") if isinstance(source, dict) else None
        if (
            not isinstance(source, dict)
            or source.get("kind") != "WaveformInstrumentNode"
            or not isinstance(sample, dict)
            or sample.get("name") != target.runtime_name
            or sample.get("encodedPayloadSha256") != target.encoded_payload_sha256
            or int(sample.get("sampleCount", -1)) != target.source_frames
            or int(sample.get("frequencyHz", -1)) != target.source_rate_hz
            or target.source_guid not in reachable
        ):
            raise HuracanPrivateRegressionError(
                f"graph identity changed for mandatory target {target.track_id}"
            )
    return graph, by_guid, reachable


def _positive_waveforms(
    by_guid: dict[str, dict[str, Any]], reachable: set[str]
) -> set[str]:
    result: set[str] = set()
    for guid in reachable:
        item = by_guid.get(guid)
        if not isinstance(item, dict) or item.get("kind") != "WaveformInstrumentNode":
            continue
        properties = item.get("baseProperties")
        if isinstance(properties, dict) and float(
            properties.get("triggerChancePercent", 0.0)
        ) > 0.0:
            result.add(guid)
    return result


def _integrity_dict(value: Any) -> dict[str, Any]:
    result = asdict(value)
    if not math.isfinite(result["peak_dbfs"]):
        result["peak_dbfs"] = None
    return {
        "sampleRateHz": result["sample_rate"],
        "channels": result["channels"],
        "bitsPerSample": result["bits_per_sample"],
        "frameCount": result["frame_count"],
        "pcmPayloadSha256": result["sha256"],
        "peakDbfs": result["peak_dbfs"],
    }


def build(
    *,
    assetto_root: Path,
    bank_path: Path,
    graph_path: Path,
    output_root: Path,
    tool_cache: Path,
) -> dict[str, Any]:
    bank = bank_path.resolve(strict=True)
    graph_file = graph_path.resolve(strict=True)
    output = output_root.resolve()
    if _sha256(bank) != SOURCE_BANK_SHA256:
        raise HuracanPrivateRegressionError("private Huracan bank identity changed")
    guid_file = bank.parent / "GUIDs.txt"
    if not guid_file.is_file() or EVENT_PATH not in guid_file.read_text(
        encoding="utf-8-sig", errors="replace"
    ):
        raise HuracanPrivateRegressionError("adjacent GUIDs.txt lacks engine_int")
    graph, by_guid, reachable = _validate_graph(graph_file, bank)
    positive_waveforms = _positive_waveforms(by_guid, reachable)
    expected_targets = {target.source_guid for target in TARGETS}
    if not expected_targets.issubset(positive_waveforms):
        raise HuracanPrivateRegressionError("a mandatory target is not schedulable")

    output.mkdir(parents=True, exist_ok=True)
    flac_root = output / "flac"
    flac_root.mkdir(parents=True, exist_ok=True)
    codec = PinnedFlacCodec(bootstrap_pinned_flac(tool_cache))
    renderer = SilentFmodReferenceRenderer(assetto_root, dsp_buffer_frames=256)
    bank_sha_before = _sha256(bank)
    records: list[dict[str, Any]] = []

    with tempfile.TemporaryDirectory(prefix="huracan-regression-", dir=output) as temp_text:
        temporary = Path(temp_text)
        for target in TARGETS:
            target_root = temporary / target.track_id
            target_root.mkdir()
            isolated_path = target_root / bank.name
            muted_waveforms = positive_waveforms - {target.source_guid}
            disabled_parents = fully_muted_multi_instrument_guids(
                graph, muted_waveforms
            )
            isolation = create_isolated_bank_copy(
                bank,
                graph,
                muted_waveforms,
                isolated_path,
                disabled_parent_guids=disabled_parents,
            )
            shutil.copyfile(guid_file, target_root / "GUIDs.txt")
            parameters = {"rpms": target.capture_rpm, "throttle": target.throttle}
            start_parameters = {
                "rpms": target.start_rpm,
                "throttle": target.throttle,
            }
            first_wav = target_root / "first.wav"
            repeat_wav = target_root / "repeat.wav"
            first = renderer.render_event(
                isolated_path,
                EVENT_PATH,
                first_wav,
                parameters=parameters,
                start_parameters=start_parameters,
                warmup_frames=target.warmup_frames,
                duration_frames=target.capture_frames,
            )
            repeat = renderer.render_event(
                isolated_path,
                EVENT_PATH,
                repeat_wav,
                parameters=parameters,
                start_parameters=start_parameters,
                warmup_frames=target.warmup_frames,
                duration_frames=target.capture_frames,
            )
            expected_runtime_set = {target.runtime_name}
            if (
                set(first.scheduled_sound_names) != expected_runtime_set
                or set(repeat.scheduled_sound_names) != expected_runtime_set
            ):
                raise HuracanPrivateRegressionError(
                    f"target-only {target.track_id} scheduled identities "
                    f"{sorted(set(first.scheduled_sound_names))} and "
                    f"{sorted(set(repeat.scheduled_sound_names))}"
                )
            first_pcm = _read_pcm(first_wav)
            repeat_pcm = _read_pcm(repeat_wav)
            if first_pcm != repeat_pcm:
                raise HuracanPrivateRegressionError(
                    f"independent {target.track_id} renders are not bit-exact"
                )
            repaired_pcm, loop = _repair_loop(first_pcm)
            canonical_wav = target_root / f"{target.track_id}.wav"
            _write_pcm(canonical_wav, repaired_pcm)
            gain_db, integrity = calibrate_pcm16_stereo_wav(
                canonical_wav, ceiling_dbfs=PEAK_CEILING_DBFS
            )
            calibrated_pcm = _read_pcm(canonical_wav)
            selected = loop["selectedExclusiveEnd"]
            calibrated_seam = measure_loop_seam(
                calibrated_pcm,
                int(selected["startFrame"]),
                int(selected["endFrameExclusive"]),
            )
            if calibrated_seam.peak_dbfs > SAFE_SEAM_DBFS:
                raise HuracanPrivateRegressionError(
                    f"calibration made {target.track_id} seam unsafe"
                )
            if integrity.peak_dbfs > PEAK_CEILING_DBFS + 0.001:
                raise HuracanPrivateRegressionError(
                    f"{target.track_id} exceeds release peak ceiling"
                )
            flac_path = flac_root / f"{target.track_id}.flac"
            encoded_integrity = codec.encode_level8(canonical_wav, flac_path)
            decoded_integrity = codec.decode_integrity(flac_path)
            if encoded_integrity != decoded_integrity or encoded_integrity != integrity:
                raise HuracanPrivateRegressionError(
                    f"{target.track_id} FLAC round trip is not bit-exact"
                )
            records.append(
                {
                    "id": target.track_id,
                    "source": {
                        "instrumentGuid": target.source_guid,
                        "runtimeName": target.runtime_name,
                        "encodedPayloadSha256": target.encoded_payload_sha256,
                        "sampleRateHz": target.source_rate_hz,
                        "frameCount": target.source_frames,
                    },
                    "capture": {
                        "eventPath": first.event_path,
                        "startParameters": start_parameters,
                        "parameters": parameters,
                        "stagedEventStartFrames": 256,
                        "warmupFrames": target.warmup_frames,
                        "frameCount": target.capture_frames,
                        "scheduledSoundNames": list(first.scheduled_sound_names),
                        "independentRenderBitExact": True,
                        "unrepairedPcmPayloadSha256": hashlib.sha256(first_pcm).hexdigest(),
                    },
                    "isolation": {
                        "sourceBankSha256": isolation.source_sha256,
                        "isolatedBankSha256": isolation.output_sha256,
                        "mutedWaveformCount": len(isolation.patches),
                        "disabledOutermostParentCount": len(
                            isolation.disabled_parent_patches
                        ),
                        "disabledOutermostParentGuids": [
                            patch.source_guid
                            for patch in isolation.disabled_parent_patches
                        ],
                        "targetWasNotPatched": target.source_guid
                        not in {patch.source_guid for patch in isolation.patches},
                        "differingByteOffsets": list(isolation.differing_byte_offsets),
                    },
                    "loop": {
                        **loop,
                        "selectedExclusiveEnd": {
                            **selected,
                            "sampleDeltaAfterCalibration": calibrated_seam.sample_delta,
                            "derivativeDeltaAfterCalibration": calibrated_seam.derivative_delta,
                            "seamPeakDbfsAfterCalibration": calibrated_seam.peak_dbfs,
                        },
                    },
                    "calibration": {
                        "appliedGainDb": gain_db,
                        "peakCeilingDbfs": PEAK_CEILING_DBFS,
                        "railSampleCount": _rail_sample_count(calibrated_pcm),
                    },
                    "flac": {
                        "relativePath": flac_path.relative_to(output).as_posix(),
                        "fileSha256": _sha256(flac_path),
                        "storageBytes": flac_path.stat().st_size,
                        "level": 8,
                        "decoded": _integrity_dict(decoded_integrity),
                    },
                }
            )

    bank_sha_after = _sha256(bank)
    if bank_sha_after != bank_sha_before:
        raise HuracanPrivateRegressionError("private source bank changed during build")
    report: dict[str, Any] = {
        "schema": "huracan-trofeo-evo2-private-flac-loop-regression-v2",
        "catalogDisposition": "PRIVATE_REGRESSION_ONLY_NOT_OFFICIAL_CATALOG",
        "sourceBank": {
            "path": str(bank),
            "sha256Before": bank_sha_before,
            "sha256After": bank_sha_after,
            "unchanged": True,
        },
        "graphReport": {"path": str(graph_file), "sha256": _sha256(graph_file)},
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "mode": "WAVWRITER_NRT",
            "sampleRateHz": 48000,
            "channels": 2,
            "bitsPerSample": 16,
            "audioDeviceOpened": False,
            "dspBufferFrames": 256,
        },
        "flacCodec": codec.provenance,
        "safeLoopSeamDbfs": SAFE_SEAM_DBFS,
        "profilePeakCeilingDbfs": PEAK_CEILING_DBFS,
        "loadRolePresent": False,
        "tracks": records,
    }
    report_bytes = canonical_json_bytes(report) + b"\n"
    report_path = output / "report.json"
    report_path.write_bytes(report_bytes)
    return {
        **report,
        "report": {"path": str(report_path), "sha256": _sha256(report_path)},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument(
        "--bank",
        type=Path,
        default=Path(
            r"D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound\audio_samples"
            r"\fx_lamborghini_huracan_trofeo_evo2\sfx"
            r"\fx_lamborghini_huracan_trofeo_evo2.bank"
        ),
    )
    parser.add_argument(
        "--graph",
        type=Path,
        default=PROJECT_ROOT
        / ".aclib-local"
        / "huracan-trofeo-evo2-bank-graph-v1.json",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=PROJECT_ROOT
        / ".aclib-local"
        / "huracan-trofeo-evo2-private-flac-regression-v2",
    )
    parser.add_argument(
        "--tool-cache",
        type=Path,
        default=PROJECT_ROOT / ".aclib-local" / "tools",
    )
    args = parser.parse_args()
    result = build(
        assetto_root=find_assetto_root(args.assetto_root),
        bank_path=args.bank,
        graph_path=args.graph,
        output_root=args.output_root,
        tool_cache=args.tool_cache,
    )
    print(
        f"tracks={len(result['tracks'])} load=False sourceUnchanged=True "
        f"report={result['report']['path']} sha256={result['report']['sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
