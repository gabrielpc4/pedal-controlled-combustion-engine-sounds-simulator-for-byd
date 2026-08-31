#!/usr/bin/env python3
"""Render target-only FMOD NRT WAV loops declared by a profile recipe.

Every capture is produced from a temporary byte-verified bank derivative where
all waveform instruments except the requested source have trigger chance zero.
The original bank is opened read-only and its SHA-256 is checked before and
after the run.  Captures are canonical PCM16/48 kHz/stereo WAVs with an
exclusive-end loop recorded in a standard ``smpl`` chunk for Android's
``WavPcmDecoder``.
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
import struct
import sys
import tempfile
from typing import Any, Mapping, Sequence
import wave


SCRIPT_ROOT = Path(__file__).resolve().parent
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from generate_android_profile_recipe import (  # noqa: E402
    RECIPE_SCHEMA,
    canonical_json_bytes,
)


class NrtRecipeError(ValueError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _write_atomic(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(payload)
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _read_pcm(
    path: Path, *, require_nrt_format: bool = False
) -> tuple[bytes, int, int, int]:
    with wave.open(str(path), "rb") as source:
        actual = (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        )
        if require_nrt_format and actual != (48_000, 2, 2, "NONE"):
            raise NrtRecipeError(f"NRT WAV format is {actual}, expected PCM16/48k/stereo")
        if actual[1] not in {1, 2} or actual[2:] != (2, "NONE"):
            raise NrtRecipeError(
                f"WAV format is {actual}, expected mono/stereo PCM16"
            )
        frame_count = source.getnframes()
        return (
            source.readframes(frame_count),
            frame_count,
            source.getframerate(),
            source.getnchannels(),
        )


def _read_smpl_loop(path: Path) -> tuple[int, int] | None:
    payload = path.read_bytes()
    if len(payload) < 12 or payload[:4] != b"RIFF" or payload[8:12] != b"WAVE":
        raise NrtRecipeError(f"{path} is not a RIFF/WAVE file")
    offset = 12
    while offset + 8 <= len(payload):
        tag = payload[offset : offset + 4]
        size = struct.unpack_from("<I", payload, offset + 4)[0]
        body = offset + 8
        end = body + size
        if end > len(payload):
            raise NrtRecipeError(f"{path} has a truncated WAV chunk")
        if tag == b"smpl" and size >= 60:
            loop_count = struct.unpack_from("<I", payload, body + 28)[0]
            if loop_count:
                start = struct.unpack_from("<I", payload, body + 44)[0]
                end_inclusive = struct.unpack_from("<I", payload, body + 48)[0]
                return start, end_inclusive + 1
        offset = end + (size & 1)
    return None


def _stereo_pcm(pcm: bytes, channels: int) -> bytes:
    if channels == 2:
        return pcm
    if channels != 1:
        raise NrtRecipeError(f"cannot convert {channels} channels to stereo")
    source = array.array("h")
    source.frombytes(pcm)
    if sys.byteorder != "little":
        source.byteswap()
    stereo = array.array("h")
    for sample in source:
        stereo.extend((sample, sample))
    if sys.byteorder != "little":
        stereo.byteswap()
    return stereo.tobytes()


def _apply_linear_gain(
    pcm: bytes, gain: float, *, allow_clipping: bool = False
) -> bytes:
    if not math.isfinite(gain) or gain <= 0.0:
        raise NrtRecipeError("capture PCM post-gain must be positive and finite")
    if abs(gain - 1.0) <= 1.0e-12:
        return pcm
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    for index, sample in enumerate(samples):
        value = round(int(sample) * gain)
        if not allow_clipping and (value < -32768 or value > 32767):
            raise NrtRecipeError("capture PCM post-gain would clip")
        samples[index] = max(-32768, min(32767, value))
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def _smpl_chunk(
    loop_start: int, loop_end_exclusive: int, sample_rate: int
) -> bytes:
    if loop_end_exclusive <= loop_start:
        raise NrtRecipeError("loop interval is empty")
    # Nine DWORD sampler header followed by one six-DWORD forward loop.
    return struct.pack(
        "<15I",
        0,
        0,
        round(1_000_000_000 / sample_rate),
        60,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        loop_start,
        loop_end_exclusive - 1,
        0,
        0,
    )


def _canonical_wav(
    pcm: bytes,
    *,
    sample_rate: int,
    loop_bounds: tuple[int, int] | None,
) -> bytes:
    if len(pcm) % 4:
        raise NrtRecipeError("PCM is not whole stereo frames")
    frame_count = len(pcm) // 4
    if sample_rate <= 0:
        raise NrtRecipeError("sample rate must be positive")
    if loop_bounds is not None:
        loop_start, loop_end_exclusive = loop_bounds
        if not 0 <= loop_start < loop_end_exclusive <= frame_count:
            raise NrtRecipeError("loop interval is outside PCM")
    fmt = struct.pack(
        "<HHIIHH", 1, 2, sample_rate, sample_rate * 4, 4, 16
    )

    def chunk(tag: bytes, body: bytes) -> bytes:
        padding = b"\0" if len(body) & 1 else b""
        return tag + struct.pack("<I", len(body)) + body + padding

    body = b"WAVE" + chunk(b"fmt ", fmt) + chunk(b"data", pcm)
    if loop_bounds is not None:
        body += chunk(
            b"smpl", _smpl_chunk(loop_start, loop_end_exclusive, sample_rate)
        )
    return b"RIFF" + struct.pack("<I", len(body)) + body


def _pcm_metrics(pcm: bytes) -> dict[str, Any]:
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    square_sum = sum(int(value) * int(value) for value in samples)
    peak = max((abs(int(value)) for value in samples), default=0)
    return {
        "nonzeroSamples": sum(value != 0 for value in samples),
        "peakLinear": peak / 32768.0,
        "rmsLinear": math.sqrt(square_sum / max(1, len(samples))) / 32768.0,
        "pcmSha256": hashlib.sha256(pcm).hexdigest(),
    }


def _load_audio_lab(audio_lab_root: Path) -> tuple[Any, Any, Any, Any]:
    root = audio_lab_root.resolve()
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    from sim.fmod_bank_isolation import (
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
    )
    from sim.fmod_renderer import SilentFmodReferenceRenderer
    from sim.loop_tools import crossfade_loop_seam, find_best_loop_bounds

    return (
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
        SilentFmodReferenceRenderer,
        (find_best_loop_bounds, crossfade_loop_seam),
    )


def _repair_loop(
    pcm: bytes,
    frame_count: int,
    find_best_loop_bounds: Any,
    crossfade_loop_seam: Any,
    *,
    sample_rate: int,
    nominal_bounds: tuple[int, int] | None = None,
) -> tuple[bytes, int, int, float | None, bool]:
    guard = min(round(sample_rate * 0.020), max(1, frame_count // 12))
    if nominal_bounds is None:
        nominal_start = guard
        nominal_end = frame_count - guard
    else:
        nominal_start = max(0, min(frame_count - 3, nominal_bounds[0]))
        nominal_end = max(
            nominal_start + 3, min(frame_count, nominal_bounds[1])
        )
    seam = find_best_loop_bounds(
        pcm,
        nominal_start=nominal_start,
        nominal_end=nominal_end,
        search_frames=min(round(sample_rate * 0.015), guard),
    )
    repaired = False
    if seam.peak_dbfs > -36.0:
        pcm, seam = crossfade_loop_seam(
            pcm,
            seam.start_frame,
            seam.end_frame,
            crossfade_frames=min(
                round(sample_rate * 0.020),
                (seam.end_frame - seam.start_frame) // 8,
            ),
        )
        repaired = True
    if seam.peak_dbfs > -18.0:
        raise NrtRecipeError(
            f"loop repair left an unsafe seam at {seam.peak_dbfs:.2f} dBFS"
        )
    peak_dbfs = seam.peak_dbfs if math.isfinite(seam.peak_dbfs) else None
    return pcm, seam.start_frame, seam.end_frame, peak_dbfs, repaired


def realize_recipe(
    recipe: Mapping[str, Any],
    graph: Mapping[str, Any],
    bank_path: Path,
    guids_path: Path,
    audio_lab_root: Path,
    assetto_root: Path,
    output_directory: Path,
    *,
    selected_source_guids: set[str] | None = None,
    maximum_sources: int | None = None,
) -> dict[str, Any]:
    if recipe.get("schema") != RECIPE_SCHEMA:
        raise NrtRecipeError(f"recipe is not {RECIPE_SCHEMA}")
    bank = bank_path.resolve(strict=True)
    source_sha_before = _sha256(bank)
    if source_sha_before != recipe.get("bank", {}).get("sha256"):
        raise NrtRecipeError("recipe bank SHA-256 differs from source bank")
    if graph.get("bank", {}).get("sha256") != source_sha_before:
        raise NrtRecipeError("graph bank SHA-256 differs from source bank")
    if not guids_path.resolve(strict=True).is_file():
        raise FileNotFoundError(guids_path)
    output_directory = output_directory.resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    (
        create_isolated_bank_copy,
        fully_muted_multi_instrument_guids,
        SilentFmodReferenceRenderer,
        loop_tools,
    ) = _load_audio_lab(audio_lab_root)
    find_best_loop_bounds, crossfade_loop_seam = loop_tools

    instruments = {
        _guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    all_waveforms = {
        guid
        for guid, item in instruments.items()
        if item.get("kind") == "WaveformInstrumentNode"
    }
    all_sources = recipe.get("extraction", {}).get("sources", [])
    non_nrt_sources = [
        item
        for item in all_sources
        if not str(item.get("primaryCapture", {}).get("mode", "")).startswith(
            "targetOnlyFmodNrt"
        )
    ]
    if non_nrt_sources:
        raise NrtRecipeError(
            "recipe contains release-ineligible non-NRT sources: "
            + ", ".join(str(item.get("sourceGuid")) for item in non_nrt_sources)
        )
    capture_sources = [
        item
        for item in all_sources
        if (
            selected_source_guids is None
            or _guid(item.get("sourceGuid")) in selected_source_guids
        )
    ]
    if maximum_sources is not None:
        capture_sources = capture_sources[:maximum_sources]
    if not capture_sources:
        raise NrtRecipeError("no target-only NRT sources matched")

    captures: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="byd-fmod-nrt-") as temporary_text:
        temporary = Path(temporary_text)
        isolated_path = temporary / bank.name
        shutil.copyfile(guids_path, temporary / "GUIDs.txt")
        renderer = SilentFmodReferenceRenderer(assetto_root.resolve(strict=True))
        for source in capture_sources:
            source_guid = _guid(source.get("sourceGuid"))
            instrument = instruments.get(source_guid)
            if instrument is None or instrument.get("kind") != "WaveformInstrumentNode":
                raise NrtRecipeError(f"capture source {source_guid} is not a waveform")
            muted = all_waveforms - {source_guid}
            disabled_parents = fully_muted_multi_instrument_guids(dict(graph), muted)
            isolated = create_isolated_bank_copy(
                bank,
                dict(graph),
                muted,
                isolated_path,
                disabled_parent_guids=disabled_parents,
            )
            capture = source["primaryCapture"]
            raw_output = temporary / "capture.wav"
            raw_output.unlink(missing_ok=True)
            rendered = renderer.render_event(
                isolated.output_path,
                str(capture["event"]),
                raw_output,
                parameters={
                    str(key): float(value)
                    for key, value in capture["parameters"].items()
                },
                duration_frames=int(capture["durationFrames"]),
                warmup_frames=int(capture["warmupFrames"]),
            )
            expected_name = str((instrument.get("sample") or {}).get("name") or "")
            if not rendered.scheduled_sound_names or set(rendered.scheduled_sound_names) != {
                expected_name
            }:
                raise NrtRecipeError(
                    f"target-only identity failed for {source_guid}: "
                    f"{rendered.scheduled_sound_names}"
                )
            pcm, frame_count, sample_rate, channels = _read_pcm(
                raw_output, require_nrt_format=True
            )
            pcm = _stereo_pcm(pcm, channels)
            pcm = _apply_linear_gain(
                pcm, float(capture.get("capturePcmPostGainLinear", 1.0))
            )
            if _pcm_metrics(pcm)["nonzeroSamples"] < 32:
                raise NrtRecipeError(f"target-only capture {source_guid} is silent")
            if source.get("lifetime") == "continuous":
                pcm, loop_start, loop_end, seam_dbfs, crossfaded = _repair_loop(
                    pcm,
                    frame_count,
                    find_best_loop_bounds,
                    crossfade_loop_seam,
                    sample_rate=sample_rate,
                )
                loop_bounds = (loop_start, loop_end)
            else:
                loop_start = 0
                loop_end = frame_count
                seam_dbfs = None
                crossfaded = False
                loop_bounds = None
            metrics = _pcm_metrics(pcm)
            output = output_directory / str(source["assetName"])
            if output.exists():
                raise NrtRecipeError(f"refusing to overwrite {output}")
            _write_atomic(
                output,
                _canonical_wav(
                    pcm,
                    sample_rate=sample_rate,
                    loop_bounds=loop_bounds,
                ),
            )
            captures.append(
                {
                    "sourceGuid": source_guid,
                    "assetName": source["assetName"],
                    "eventPath": rendered.event_path,
                    "parameters": rendered.parameters,
                    "scheduledDiagnosticName": expected_name,
                    "isolatedBankSha256": isolated.output_sha256,
                    "mutedWaveformSources": len(isolated.patches),
                    "disabledMultiInstrumentParents": len(
                        isolated.disabled_parent_patches
                    ),
                    "changedBankBytes": len(isolated.differing_byte_offsets),
                    "frameCount": frame_count,
                    "loopStartFrame": loop_start,
                    "loopEndFrameExclusive": loop_end,
                    "loopSeamPeakDbfs": seam_dbfs,
                    "loopCrossfaded": crossfaded,
                    "hasLoopChunk": loop_bounds is not None,
                    "wavSha256": _sha256(output),
                    **metrics,
                }
            )
            raw_output.unlink(missing_ok=True)

    source_sha_after = _sha256(bank)
    if source_sha_after != source_sha_before:
        raise NrtRecipeError("source bank changed during target-only capture")
    declared_assets = {
        layer["assetName"]
        for program in recipe.get("programs", {}).values()
        for layer in program.get("layers", [])
    }
    for program in recipe.get("programs", {}).values():
        for effect in program.get("effects", []):
            declared_assets.add(effect["assetName"])
            declared_assets.update(effect.get("variantAssetNames", []))
    extraction_assets = {str(item["assetName"]) for item in all_sources}
    missing_extraction_declarations = sorted(declared_assets - extraction_assets)
    if missing_extraction_declarations:
        raise NrtRecipeError(
            "program references assets absent from extraction plan: "
            + ", ".join(missing_extraction_declarations)
        )
    full_run = selected_source_guids is None and maximum_sources is None
    missing_realized_assets = (
        sorted(
            asset
            for asset in extraction_assets
            if not (output_directory / asset).is_file()
        )
        if full_run
        else []
    )
    if missing_realized_assets:
        raise NrtRecipeError(
            "full realization omitted assets: " + ", ".join(missing_realized_assets)
        )
    return {
        "schema": "byd-fmod-target-only-nrt-realization-v1",
        "recipeSha256": recipe.get("recipeSha256"),
        "sourceBankSha256Before": source_sha_before,
        "sourceBankSha256After": source_sha_after,
        "sourceBankUnchanged": True,
        "allProgramAssetsDeclaredForExtraction": True,
        "allExtractionAssetsRealized": full_run and not missing_realized_assets,
        "fullRun": full_run,
        "captures": captures,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipe", type=Path, required=True)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--bank", type=Path, required=True)
    parser.add_argument("--guids", type=Path, required=True)
    parser.add_argument("--audio-lab-root", type=Path, required=True)
    parser.add_argument("--assetto-root", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    parser.add_argument("--source-guid", action="append", default=[])
    parser.add_argument("--maximum-sources", type=int)
    args = parser.parse_args(argv)
    try:
        recipe = json.loads(args.recipe.read_text(encoding="utf-8"))
        graph = json.loads(args.graph.read_text(encoding="utf-8"))
        result = realize_recipe(
            recipe,
            graph,
            args.bank,
            args.guids,
            args.audio_lab_root,
            args.assetto_root,
            args.output_directory,
            selected_source_guids=(
                {_guid(value) for value in args.source_guid}
                if args.source_guid
                else None
            ),
            maximum_sources=args.maximum_sources,
        )
        _write_atomic(args.report_output, canonical_json_bytes(result) + b"\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
