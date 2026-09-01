#!/usr/bin/env python3
"""Exercise every local car bank with a fast parameter trajectory.

This is an authoring-time diagnostic, not an Android runtime dependency. It
uses Audio Lab's FMOD oracle to render short interior and exterior sweeps,
records signal measurements as JSONL, and reports silence, clipping, missing
events, or a bank that cannot survive rapid RPM/throttle changes.
"""

from __future__ import annotations

import argparse
import io
import importlib.util
import json
import math
import struct
import sys
import tempfile
import wave
import zipfile
from pathlib import Path
from types import ModuleType


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORIGINAL_ROOT = REPO_ROOT.parent / "original_cars"
DEFAULT_NEW_ROOT = REPO_ROOT.parent / "new_cars"
DEFAULT_LAB_ROOT = REPO_ROOT.parent / "assetto_corsa_audio_lab"
DEFAULT_ASSETTO_ROOT = DEFAULT_LAB_ROOT / "macos_bank_lab"
DEFAULT_BANK_ROOT = REPO_ROOT.parent / "assettocorsa_banks"
DEFAULT_REPORT = REPO_ROOT / "build" / "validation" / "car-audio.jsonl"
BLOCK_FRAMES = 2_560  # 53.3 ms, exactly ten 256-frame FMOD DSP blocks
TRAJECTORY_FRAMES = 72_000  # 1.5 s of rapidly changing controls
RMS_SILENCE_THRESHOLD = 1.0e-4
HARD_CLIP_THRESHOLD = 0.999
HARD_CLIP_RATIO_LIMIT = 0.01


def load_builder() -> ModuleType:
    """Load the pack builder without making its authoring code a CLI dependency."""

    path = Path(__file__).with_name("build_wav_audio_packs.py")
    spec = importlib.util.spec_from_file_location("byd_wav_pack_builder", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def read_measurements(path: Path | io.BytesIO) -> dict[str, object]:
    source_path = str(path) if isinstance(path, Path) else path
    with wave.open(source_path, "rb") as source:
        channels = source.getnchannels()
        width = source.getsampwidth()
        rate = source.getframerate()
        frames = source.getnframes()
        payload = source.readframes(frames)
    if channels not in (1, 2) or width != 2 or rate not in (44_100, 48_000):
        raise RuntimeError(
            f"unexpected output format channels={channels} width={width} rate={rate}"
        )
    samples = struct.unpack(f"<{len(payload) // 2}h", payload)
    if not samples:
        raise RuntimeError("FMOD returned an empty WAV")
    peak = max(abs(sample) for sample in samples) / 32768.0
    square_sum = sum(sample * sample for sample in samples)
    rms = math.sqrt(square_sum / len(samples)) / 32768.0
    hard_clip_samples = sum(
        abs(sample) / 32768.0 >= HARD_CLIP_THRESHOLD for sample in samples
    )
    segment_rms: list[float] = []
    samples_per_segment = max(1, round(rate * (BLOCK_FRAMES / 48_000.0))) * channels
    for offset in range(0, len(samples), samples_per_segment):
        segment = samples[offset : offset + samples_per_segment]
        if not segment:
            continue
        segment_rms.append(
            math.sqrt(sum(sample * sample for sample in segment) / len(segment)) / 32768.0
        )
    return {
        "frames": frames,
        "rms": rms,
        "peak": peak,
        "hardClipRatio": hard_clip_samples / len(samples),
        "nonSilentSegments": sum(value > RMS_SILENCE_THRESHOLD for value in segment_rms),
        "segmentRms": [round(value, 7) for value in segment_rms],
    }


def trajectory(warmup_frames: int, idle_rpm: float, maximum_rpm: float) -> list[tuple[int, dict[str, float]]]:
    throttle_values = (0.0, 0.28, 0.92, 0.48, 1.0, 0.12, 0.76, 0.0)
    updates: list[tuple[int, dict[str, float]]] = []
    for index, throttle in enumerate(throttle_values):
        progress = index / max(1, len(throttle_values) - 1)
        rpm = idle_rpm + (maximum_rpm - idle_rpm) * progress
        updates.append(
            (
                warmup_frames + index * BLOCK_FRAMES,
                {"rpms": rpm, "throttle": throttle},
            )
        )
    return updates


def validate_event(oracle, builder: ModuleType, bank: Path, event: str, car, expected_silence: bool) -> dict[str, object]:
    warmup = 288_000 if car.pack_id in builder.LONG_STARTUP_WARMUP_PACKS else 48_128
    idle_rpm = 900.0
    maximum_rpm = builder.max_rpm(car.pack_id)
    result: dict[str, object] = {
        "car": car.pack_id,
        "displayName": car.display_name,
        "bank": bank.name,
        "event": event,
        "warmupFrames": warmup,
        "trajectoryFrames": TRAJECTORY_FRAMES,
    }
    try:
        with tempfile.TemporaryDirectory(prefix="byd-car-audio-") as temporary:
            rendered = Path(temporary) / "capture.wav"
            reference = oracle.render_event(
                bank,
                event,
                rendered,
                parameters={"rpms": idle_rpm, "throttle": 0.0},
                parameter_updates=trajectory(warmup, idle_rpm, maximum_rpm),
                duration_frames=TRAJECTORY_FRAMES,
                warmup_frames=warmup,
                bypass_cabin_eq=event == "engine_int",
            )
            measurements = read_measurements(rendered)
            result.update(measurements)
            result["eventPath"] = reference.event_path
            result["scheduledSounds"] = list(reference.scheduled_sound_names)
            if measurements["rms"] <= RMS_SILENCE_THRESHOLD:
                result["status"] = "expected_silence" if expected_silence else "silent"
            elif measurements["hardClipRatio"] > HARD_CLIP_RATIO_LIMIT:
                result["status"] = "warning_clipping"
            elif measurements["nonSilentSegments"] < 2:
                result["status"] = "short_or_unstable"
            else:
                result["status"] = "ok"
    except Exception as error:  # the report must retain failures for triage
        result["status"] = "error"
        result["error"] = f"{type(error).__name__}: {error}"
    if result["status"] == "error" and expected_silence:
        result["status"] = "expected_silence"
    return result


def validate_pack_archive(archive: Path, car, pack_id: str) -> dict[str, object]:
    result: dict[str, object] = {
        "car": car.pack_id,
        "displayName": car.display_name,
        "pack": pack_id,
        "archive": archive.name,
        "validationKind": "pack-wav",
    }
    try:
        silent: list[str] = []
        mono: list[str] = []
        engine_format_warnings: list[str] = []
        peaks: list[float] = []
        audio_files = 0
        with zipfile.ZipFile(archive) as source:
            manifest = json.loads(source.read("manifest.json"))
            for entry in manifest.get("files", []):
                path = str(entry.get("path", ""))
                if not path.startswith("audio/") or not path.endswith(".wav"):
                    continue
                audio_files += 1
                payload = source.read(path)
                with wave.open(io.BytesIO(payload), "rb") as wave_file:
                    channels = wave_file.getnchannels()
                measurements = read_measurements(io.BytesIO(payload))
                peaks.append(float(measurements["peak"]))
                if channels == 1:
                    mono.append(path)
                if path.rsplit("/", 1)[-1].startswith(("idle", "load", "coast")) and channels != 2:
                    engine_format_warnings.append(path)
                if float(measurements["rms"]) <= RMS_SILENCE_THRESHOLD:
                    silent.append(path)
        result.update(
            audioFiles=audio_files,
            silentAudioFiles=silent,
            monoAudioFiles=mono,
            engineFormatWarnings=engine_format_warnings,
            maxPeak=max(peaks, default=0.0),
        )
        result["status"] = "ok" if audio_files > 0 and not silent else "silent"
    except Exception as error:
        result["status"] = "error"
        result["error"] = f"{type(error).__name__}: {error}"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-root", type=Path, default=DEFAULT_ORIGINAL_ROOT)
    parser.add_argument("--new-root", type=Path, default=DEFAULT_NEW_ROOT)
    parser.add_argument("--assetto-root", type=Path, default=DEFAULT_ASSETTO_ROOT)
    parser.add_argument("--bank-root", type=Path, default=DEFAULT_BANK_ROOT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--only", action="append", default=[])
    arguments = parser.parse_args()

    builder = load_builder()
    if str(DEFAULT_LAB_ROOT) not in sys.path:
        sys.path.insert(0, str(DEFAULT_LAB_ROOT))
    from sim.fmod_renderer import SilentFmodReferenceRenderer

    selected = set(arguments.only)
    cars = [
        car
        for car in builder.discover_cars(arguments.original_root, arguments.new_root, arguments.bank_root)
        if not selected or car.pack_id in selected
    ]
    if not cars:
        raise SystemExit("No matching cars")

    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    oracle = SilentFmodReferenceRenderer(arguments.assetto_root)
    summary = {"ok": 0, "expected_silence": 0, "silent": 0, "short_or_unstable": 0, "warning_clipping": 0, "error": 0}
    validations = 0
    with arguments.report.open("w", encoding="utf-8") as report:
        for car in cars:
            pack_id = builder.EXACT_BANK_AUDIO_OWNER.get(car.pack_id, car.pack_id)
            if not car.generic or car.pack_id in builder.EXACT_BANK_AUDIO_OWNER:
                row = validate_pack_archive(REPO_ROOT / "audio_packs" / f"{pack_id}.bydpack", car, pack_id)
                report.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
                report.flush()
                summary[row["status"]] += 1
                validations += 1
                print(
                    f"{row['status']:>16} {car.pack_id:<48} {'pack':<10} "
                    f"files={row.get('audioFiles', 0)} peak={row.get('maxPeak', 0):.5f}"
                )
                continue
            bank = builder.active_bank(car)
            for event in ("engine_int", "engine_ext"):
                row = validate_event(
                    oracle,
                    builder,
                    bank,
                    event,
                    car,
                    event == "engine_ext" and car.pack_id in builder.EXTERIOR_FALLBACK_TO_INTERIOR_PACKS,
                )
                report.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
                report.flush()
                summary[row["status"]] += 1
                validations += 1
                print(
                    f"{row['status']:>16} {car.pack_id:<48} {event:<10} "
                    f"rms={row.get('rms', 0):.5f} peak={row.get('peak', 0):.5f}"
                )
    print(json.dumps({"cars": len(cars), "validations": validations, "summary": summary}, sort_keys=True))
    return 0 if summary["error"] == 0 and summary["silent"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
