#!/usr/bin/env python3
"""Create the installable WAV packs for every local Assetto Corsa car.

Run this under x86_64 Python on Apple Silicon because the local FMOD API is
x86_64. The script never writes banks or source cars; WAVs and `.bydpack`
archives are generated below the ignored build/audio_packs directories.
"""

from __future__ import annotations

import argparse
import audioop
import hashlib
import json
import shutil
import struct
import sys
import tempfile
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORIGINAL_ROOT = REPO_ROOT.parent / "original_cars"
DEFAULT_NEW_ROOT = REPO_ROOT.parent / "new_cars"
DEFAULT_AUDIO_SAMPLES = REPO_ROOT / "audio_samples"
DEFAULT_OUTPUT = REPO_ROOT / "audio_packs"
DEFAULT_WORK = REPO_ROOT / "build" / "wav-pack-authoring"
DEFAULT_LAB_ROOT = REPO_ROOT.parent / "assetto_corsa_audio_lab"
DEFAULT_ASSETTO_ROOT = DEFAULT_LAB_ROOT / "macos_bank_lab"
DEFAULT_BANK_FALLBACK_ROOT = REPO_ROOT.parent / "assettocorsa_banks"
PACK_SCHEMA = "byd-wav-audio-pack-v1"
MANIFEST_NAME = "manifest.json"
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
GENERIC_ROOTS = (0.28, 0.52, 0.78)
EFFECT_REPORT_DIRECTORY = "_effect_capture_reports"
EFFECT_AUDIBLE_RMS = 0.0005
EFFECT_TARGET_RMS = {
    "transmission": 0.060,
    "shift_up": 0.120,
    "shift_down": 0.120,
    "turbo_loop": 0.080,
    "turbo_dump": 0.120,
    "limiter": 0.100,
    "overrun": 0.120,
}
# These aliases are based on byte-for-byte equal local source banks. They are
# deliberately not model-family guesses: one pack is authored, then both UI
# profiles resolve the same installed payload.
EXACT_BANK_AUDIO_OWNER = {
    "lexus-lfa-concept-gt500": "lamborghini_aventador_sv_cabin",
    "nissan-370z-widebody": "nissan-350z",
}
# A mod can retain an original car event graph while its own FMOD output route
# is silent in the headless renderer.  These are source-specific fallbacks,
# never a model-name shortcut.  The Porsche fallback is the original DLC bank
# for the exact GT3 RS model after the mod's own int/ext events were verified
# to schedule sounds but emit all-zero PCM.
CAPTURE_BANK_FALLBACKS = {
    "porsche-911-gt3-rs-hellspec": "ks_porsche_911_gt3_rs.bank",
}
# These C6-derived banks take about five seconds to activate their interior
# graph.  Capturing after the normal one-second warmup would put a near-silent
# start at the loop boundary.
LONG_STARTUP_WARMUP_PACKS = {
    "chevrolet-corvette-c6-z06-stanced",
    "chevrolet-corvette-c7-stingray-hellspec",
}
# The renderer proves their engine_ext routes emit all-zero PCM at every
# tested microphone position.  The dashboard deliberately uses the audible
# interior program for its exterior toggle rather than offering a silent mode.
EXTERIOR_FALLBACK_TO_INTERIOR_PACKS = {
    "aston-martin-dbrs9-gt3",
    "chevrolet-corvette-c6-z06-stanced",
    "chevrolet-corvette-c7-stingray-hellspec",
}
# A pair of authored coast cells are silent even after their startup graph has
# settled. Reusing the nearest audible cell keeps the program continuous; the
# Android layer's normal root-RPM pitch scaling supplies the target frequency.
SILENT_LAYER_FALLBACKS = {
    "chevrolet-corvette-c6-z06-stanced": {
        "coast_2.wav": "coast_1.wav",
        "coast_3.wav": "coast_1.wav",
    },
    "chevrolet-corvette-c7-stingray-hellspec": {"coast_2.wav": "coast_3.wav"},
}


@dataclass(frozen=True)
class SourceCar:
    source_directory: Path | None
    pack_id: str
    display_name: str
    generic: bool
    bank_path: Path | None = None


@dataclass(frozen=True)
class BankCarDefinition:
    pack_id: str
    bank_name: str
    display_name: str


@dataclass(frozen=True)
class GenericEffectCapture:
    """One optional FMOD event that the WAV renderer can reproduce.

    The engine event itself is rendered as the continuous idle/load/coast
    program. These captures preserve the authored events outside that program:
    drivetrain texture, shift impacts, turbo, limiter, and backfire. Candidate
    events are only fallbacks within the same bank, never another car's audio.
    """

    id: str
    asset_name: str
    event_candidates: tuple[str, ...]
    parameters: dict[str, float]
    duration_frames: int
    warmup_frames: int
    looping: bool = False


def generic_effect_captures(exterior: bool) -> tuple[GenericEffectCapture, ...]:
    """Return the standard auxiliary event recipes for one listening route."""
    prefix = "ext_" if exterior else ""
    gear_events = ("gear_ext", "gear_int") if exterior else ("gear_int",)
    transmission_events = ("transmission_ext", "transmission") if exterior else ("transmission",)
    backfire_events = ("backfire_ext", "backfire_int") if exterior else ("backfire_int",)
    return (
        GenericEffectCapture(
            "transmission", f"{prefix}fx_transmission.wav", transmission_events,
            {"drivetrain_speed": 220.0},
            duration_frames=96_000, warmup_frames=24_000, looping=True,
        ),
        GenericEffectCapture(
            "shift_up", f"{prefix}fx_shift_up.wav", gear_events,
            {"state": 1.0},
            duration_frames=72_000, warmup_frames=0,
        ),
        GenericEffectCapture(
            "shift_down", f"{prefix}fx_shift_down.wav", gear_events,
            {"state": 0.0},
            duration_frames=72_000, warmup_frames=0,
        ),
        GenericEffectCapture(
            "turbo_loop", f"{prefix}fx_turbo_loop.wav", ("turbo",),
            {"boost": 0.72, "bov": 0.0, "bov_decay": 1.0},
            duration_frames=96_000, warmup_frames=24_000, looping=True,
        ),
        GenericEffectCapture(
            "turbo_dump", f"{prefix}fx_turbo_dump.wav", ("turbo",),
            {"boost": 0.72, "bov": 1.0, "bov_decay": 0.0},
            duration_frames=72_000, warmup_frames=0,
        ),
        GenericEffectCapture(
            "limiter", f"{prefix}fx_limiter.wav", ("limiter",),
            {"decay": 0.0},
            duration_frames=96_000, warmup_frames=24_000, looping=True,
        ),
        GenericEffectCapture(
            "overrun", f"{prefix}fx_overrun.wav", backfire_events,
            {"throttle": 0.01},
            duration_frames=72_000, warmup_frames=0,
        ),
    )


# These are official Assetto Corsa banks that are close enough to a supplied
# new-car model to be useful as additional selectable sound profiles. The
# relationship is explicit and reviewable; it is never inferred from a name at
# runtime. Models without a defensible counterpart remain out of the app.
BANK_CAR_DEFINITIONS = (
    BankCarDefinition("assetto-audi-r8-lms-2016", "ks_audi_r8_lms_2016.bank", "Audi R8 LMS 2016"),
    BankCarDefinition("assetto-audi-r8-plus", "ks_audi_r8_plus.bank", "Audi R8 Plus"),
    BankCarDefinition("assetto-audi-tt-cup", "ks_audi_tt_cup.bank", "Audi TT Cup"),
    BankCarDefinition("assetto-bmw-m4", "ks_bmw_m4.bank", "BMW M4"),
    BankCarDefinition("assetto-corvette-c7-stingray", "ks_corvette_c7_stingray.bank", "Chevrolet Corvette C7 Stingray"),
    BankCarDefinition("assetto-ferrari-458", "ferrari_458.bank", "Ferrari 458 Italia"),
    BankCarDefinition("assetto-ferrari-458-gt2", "ferrari_458_GT2.bank", "Ferrari 458 GT2"),
    BankCarDefinition("assetto-ferrari-488-gtb", "ks_ferrari_488_gtb.bank", "Ferrari 488 GTB"),
    BankCarDefinition("assetto-ferrari-488-gt3", "ks_ferrari_488_gt3.bank", "Ferrari 488 GT3"),
    BankCarDefinition("assetto-ferrari-fxx-k", "ks_ferrari_fxx_k.bank", "Ferrari FXX K"),
    BankCarDefinition("assetto-ferrari-laferrari", "ferrari_LaFerrari.bank", "Ferrari LaFerrari"),
    BankCarDefinition("assetto-lamborghini-aventador-sv", "ks_lamborghini_aventador_sv.bank", "Lamborghini Aventador SV"),
    BankCarDefinition("assetto-lamborghini-gallardo-sl", "ks_lamborghini_gallardo_sl.bank", "Lamborghini Gallardo Superleggera"),
    BankCarDefinition("assetto-lamborghini-huracan-performante", "ks_lamborghini_huracan_performante.bank", "Lamborghini Huracán Performante"),
    BankCarDefinition("assetto-lamborghini-huracan-st", "ks_lamborghini_huracan_st.bank", "Lamborghini Huracán ST"),
    BankCarDefinition("assetto-mercedes-amg-gt3", "ks_mercedes_amg_gt3.bank", "Mercedes-AMG GT3"),
    BankCarDefinition("assetto-nissan-370z", "ks_nissan_370z.bank", "Nissan 370Z"),
    BankCarDefinition("assetto-nissan-gtr", "ks_nissan_gtr.bank", "Nissan GT-R"),
    BankCarDefinition("assetto-porsche-911-gt3-rs", "ks_porsche_911_gt3_rs.bank", "Porsche 911 GT3 RS"),
    BankCarDefinition("assetto-porsche-991-turbo-s", "ks_porsche_991_turbo_s.bank", "Porsche 911 Turbo S (991)"),
    BankCarDefinition("assetto-toyota-supra-mkiv", "ks_toyota_supra_mkiv.bank", "Toyota Supra Mk IV"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        for block in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def display_name(directory: Path) -> str:
    info = directory / "info.txt"
    if info.is_file():
        for line in info.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("Name:"):
                return line.removeprefix("Name:").strip()
    return directory.name.replace("-", " ").replace("_", " ").title()


def normalized_id(directory: Path) -> str:
    return directory.name.casefold().replace("_", "-")


def discover_cars(original_root: Path, new_root: Path, bank_root: Path | None = None) -> list[SourceCar]:
    cars: list[SourceCar] = []
    original_special = {
        "lamborghini-huracan-trofeo-evo2": "lamborghini_huracan_trofeo_evo2_cabin",
        "lamborghini-aventador-sv": "lamborghini_aventador_sv_cabin",
        "nissan-skyline-gt-r34-v-spec": "nissan_skyline_r34_cabin",
    }
    for root in (original_root, new_root):
        for directory in sorted(path for path in root.iterdir() if path.is_dir() and path.name != "_original_compressed_files"):
            normalized = normalized_id(directory)
            cars.append(
                SourceCar(
                    source_directory=directory,
                    pack_id=original_special.get(normalized, normalized),
                    display_name=display_name(directory),
                    generic=normalized not in original_special,
                )
            )
    if bank_root is not None:
        for definition in BANK_CAR_DEFINITIONS:
            bank_path = bank_root / definition.bank_name
            if bank_path.is_file():
                cars.append(
                    SourceCar(
                        source_directory=None,
                        pack_id=definition.pack_id,
                        display_name=definition.display_name,
                        generic=True,
                        bank_path=bank_path,
                    )
                )
    return cars


def active_bank(car: SourceCar) -> Path:
    if car.bank_path is not None:
        return car.bank_path
    if car.source_directory is None:
        raise RuntimeError(f"No source directory or bank was declared for {car.pack_id}")
    fallback_name = CAPTURE_BANK_FALLBACKS.get(car.pack_id)
    if fallback_name is not None:
        fallback = DEFAULT_BANK_FALLBACK_ROOT / fallback_name
        if not fallback.is_file():
            raise RuntimeError(f"Missing documented fallback bank for {car.pack_id}: {fallback}")
        return fallback
    sfx = car.source_directory / "sfx"
    banks = sorted(sfx.glob("*.bank"))
    if len(banks) == 1:
        return banks[0]
    expected = car.source_directory.name.casefold()
    exact = [bank for bank in banks if bank.stem.casefold() == expected]
    if len(exact) == 1:
        return exact[0]
    raise RuntimeError(f"Could not choose an active bank for {car.source_directory.name}: {banks}")


def max_rpm(pack_id: str) -> float:
    values = {
        "assetto-audi-r8-lms-2016": 9000.0,
        "assetto-audi-r8-plus": 8500.0,
        "assetto-audi-tt-cup": 7500.0,
        "assetto-bmw-m4": 8500.0,
        "assetto-corvette-c7-stingray": 7000.0,
        "assetto-ferrari-458": 9000.0,
        "assetto-ferrari-458-gt2": 9000.0,
        "assetto-ferrari-488-gtb": 8500.0,
        "assetto-ferrari-488-gt3": 8500.0,
        "assetto-ferrari-fxx-k": 9000.0,
        "assetto-ferrari-laferrari": 9250.0,
        "assetto-lamborghini-aventador-sv": 9200.0,
        "assetto-lamborghini-gallardo-sl": 8500.0,
        "assetto-lamborghini-huracan-performante": 8500.0,
        "assetto-lamborghini-huracan-st": 8500.0,
        "assetto-mercedes-amg-gt3": 8500.0,
        "assetto-nissan-370z": 7500.0,
        "assetto-nissan-gtr": 7500.0,
        "assetto-porsche-911-gt3-rs": 9500.0,
        "assetto-porsche-991-turbo-s": 7500.0,
        "assetto-toyota-supra-mkiv": 7500.0,
        "ferrari-f1-2000": 16000.0,
        "ferrari-laferrari-trio": 9500.0,
        "ferrari-sf90-xx-stradale-2024": 9500.0,
        "lexus-lfa": 10000.0,
        "lexus-lfa-concept-gt500": 10000.0,
        "lexus-lfa-no-hesi-spec": 10000.0,
        "lexus-lfa-nurburgring-edition": 10000.0,
        "mercedes-amg-project-one-hypercar": 11000.0,
        "porsche-911-gt3-rs-hellspec": 9500.0,
        "porsche-carrera-gt-rs": 9000.0,
    }
    return values.get(pack_id, 8500.0)


def add_smpl_loop(source: Path, destination: Path) -> None:
    """Preserve the capture and add a click-safe exclusive-end loop region."""
    lab_root = DEFAULT_LAB_ROOT
    if str(lab_root) not in sys.path:
        sys.path.insert(0, str(lab_root))
    from sim.loop_tools import crossfade_loop_seam, find_best_loop_bounds

    with wave.open(str(source), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (2, 2, 48000, "NONE"):
            raise RuntimeError(f"FMOD output is not PCM16/48k/stereo: {source}")
        pcm = wav.readframes(wav.getnframes())
    frame_count = len(pcm) // 4
    guard = min(2400, max(480, frame_count // 10))
    seam = find_best_loop_bounds(
        pcm,
        nominal_start=guard,
        nominal_end=frame_count - guard,
        search_frames=min(480, guard // 2),
    )
    pcm, seam = crossfade_loop_seam(pcm, seam.start_frame, seam.end_frame, crossfade_frames=960)
    fmt = struct.pack("<HHIIHH", 1, 2, 48000, 48000 * 4, 4, 16)
    smpl = struct.pack("<9I", 0, 0, 0, 60, 0, 0, 0, 1, 0) + struct.pack(
        "<6I", 0, 0, seam.start_frame, seam.end_frame - 1, 0, 0
    )
    chunks = ((b"fmt ", fmt), (b"smpl", smpl), (b"data", pcm))
    body = b"".join(identifier + struct.pack("<I", len(payload)) + payload + (b"\0" if len(payload) % 2 else b"") for identifier, payload in chunks)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(b"RIFF" + struct.pack("<I", len(body) + 4) + b"WAVE" + body)


def wav_metrics(path: Path) -> tuple[float, float]:
    """Return peak and RMS normalized to the PCM16 full-scale range."""
    with wave.open(str(path), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (2, 2, 48000, "NONE"):
            raise RuntimeError(f"FMOD output is not PCM16/48k/stereo: {path}")
        pcm = wav.readframes(wav.getnframes())
    return audioop.max(pcm, 2) / 32768.0, audioop.rms(pcm, 2) / 32768.0


def trim_one_shot(source: Path, destination: Path) -> None:
    """Keep useful attack/tail while avoiding decoded silence for one-shot voices."""
    with wave.open(str(source), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (2, 2, 48000, "NONE"):
            raise RuntimeError(f"FMOD output is not PCM16/48k/stereo: {source}")
        pcm = wav.readframes(wav.getnframes())
        frame_count = wav.getnframes()

    peak = audioop.max(pcm, 2)
    threshold = max(64, int(peak * 0.025))
    first_frame: int | None = None
    last_frame: int | None = None
    for sample_index, (value,) in enumerate(struct.iter_unpack("<h", pcm)):
        if abs(value) >= threshold:
            frame = sample_index // 2
            first_frame = frame if first_frame is None else first_frame
            last_frame = frame
    if first_frame is None or last_frame is None:
        raise RuntimeError(f"Cannot trim a silent FMOD one-shot: {source}")

    # Preserve 10 ms before the transient and 100 ms after its real tail.
    start = max(0, first_frame - 480)
    end = min(frame_count, last_frame + 4_800)
    trimmed = pcm[start * 4:end * 4]
    fmt = struct.pack("<HHIIHH", 1, 2, 48000, 48000 * 4, 4, 16)
    body = b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(trimmed)) + trimmed
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(b"RIFF" + struct.pack("<I", len(body) + 4) + b"WAVE" + body)


def normalize_effect_wav(path: Path, target_rms: float) -> tuple[float, float]:
    """Normalize auxiliary layers without touching the WAV loop metadata."""
    payload = bytearray(path.read_bytes())
    if payload[:4] != b"RIFF" or payload[8:12] != b"WAVE":
        raise RuntimeError(f"Effect capture is not RIFF/WAV: {path}")
    offset = 12
    while offset + 8 <= len(payload):
        chunk_id = bytes(payload[offset:offset + 4])
        chunk_size = struct.unpack_from("<I", payload, offset + 4)[0]
        data_start = offset + 8
        data_end = data_start + chunk_size
        if data_end > len(payload):
            raise RuntimeError(f"Malformed WAV chunk in {path}")
        if chunk_id == b"data":
            pcm = bytes(payload[data_start:data_end])
            peak = audioop.max(pcm, 2) / 32768.0
            rms = audioop.rms(pcm, 2) / 32768.0
            if peak <= 0.0 or rms <= 0.0:
                raise RuntimeError(f"Cannot normalize silent effect capture: {path}")
            scale = min(target_rms / rms, 0.90 / peak)
            payload[data_start:data_end] = audioop.mul(pcm, 2, scale)
            path.write_bytes(payload)
            return wav_metrics(path)
        offset = data_end + (chunk_size % 2)
    raise RuntimeError(f"Effect capture has no data chunk: {path}")


def capture_generic_effects(
    car: SourceCar,
    assetto_root: Path,
    work_root: Path,
    *,
    interior_only: bool = False,
) -> dict[str, object]:
    """Render every audible standard side-event and persist auditable evidence.

    A missing FMOD event or an all-but-silent render is reported as unavailable,
    not converted into an empty placeholder. Android then only declares assets
    that the source bank actually supplied.
    """
    if str(DEFAULT_LAB_ROOT) not in sys.path:
        sys.path.insert(0, str(DEFAULT_LAB_ROOT))
    from sim.fmod_renderer import SilentFmodReferenceRenderer

    audio_directory = work_root / car.pack_id / "audio"
    audio_directory.mkdir(parents=True, exist_ok=True)
    bank = active_bank(car)
    renderer = SilentFmodReferenceRenderer(assetto_root)
    exterior_available = not interior_only and car.pack_id not in EXTERIOR_FALLBACK_TO_INTERIOR_PACKS
    perspectives = (("cabin", False),) + (("exterior", True),) if exterior_available else (("cabin", False),)
    results: dict[str, object] = {"packId": car.pack_id, "bank": str(bank), "perspectives": {}}

    for perspective, exterior in perspectives:
        captures: dict[str, object] = {}
        for recipe in generic_effect_captures(exterior):
            destination = audio_directory / recipe.asset_name
            destination.unlink(missing_ok=True)
            errors: list[str] = []
            for event in recipe.event_candidates:
                rendered = audio_directory / f".{recipe.asset_name}.{event}.render.wav"
                try:
                    reference = renderer.render_event(
                        bank,
                        event,
                        rendered,
                        parameters=recipe.parameters,
                        duration_frames=recipe.duration_frames,
                        warmup_frames=recipe.warmup_frames,
                        bypass_cabin_eq=False,
                    )
                    peak, rms = wav_metrics(rendered)
                    if rms < EFFECT_AUDIBLE_RMS:
                        errors.append(f"{event}: RMS {rms:.6f} below audible threshold")
                        continue
                    if recipe.looping:
                        add_smpl_loop(rendered, destination)
                    else:
                        trim_one_shot(rendered, destination)
                    output_peak, output_rms = normalize_effect_wav(destination, EFFECT_TARGET_RMS[recipe.id])
                    captures[recipe.id] = {
                        "asset": recipe.asset_name,
                        "event": event,
                        "peak": round(peak, 7),
                        "rms": round(rms, 7),
                        "normalizedPeak": round(output_peak, 7),
                        "normalizedRms": round(output_rms, 7),
                        "scheduledSounds": list(reference.scheduled_sound_names),
                        "fallback": event != recipe.event_candidates[0],
                    }
                    break
                except Exception as error:
                    errors.append(f"{event}: {error}")
                finally:
                    rendered.unlink(missing_ok=True)
            else:
                captures[recipe.id] = {"unavailable": errors}
        results["perspectives"][perspective] = captures

    report_directory = work_root / EFFECT_REPORT_DIRECTORY
    report_directory.mkdir(parents=True, exist_ok=True)
    (report_directory / f"{car.pack_id}.json").write_text(
        json.dumps(results, indent=2, sort_keys=True), encoding="utf-8"
    )
    return results


def render_generic_car(
    car: SourceCar,
    assetto_root: Path,
    work_root: Path,
    *,
    interior_only: bool = False,
) -> Path:
    if str(DEFAULT_LAB_ROOT) not in sys.path:
        sys.path.insert(0, str(DEFAULT_LAB_ROOT))
    from sim.fmod_renderer import SilentFmodReferenceRenderer

    destination = work_root / car.pack_id / "audio"
    destination.mkdir(parents=True, exist_ok=True)
    bank = active_bank(car)
    renderer = SilentFmodReferenceRenderer(assetto_root)
    rpm_values = (900.0,) + tuple(max_rpm(car.pack_id) * fraction for fraction in GENERIC_ROOTS)
    exterior_available = car.pack_id not in EXTERIOR_FALLBACK_TO_INTERIOR_PACKS
    programs = (("", "engine_int", 1.0),) if interior_only or not exterior_available else (
        ("", "engine_int", 1.0),
        ("ext_", "engine_ext", 1.0),
    )
    if not exterior_available:
        for stale_exterior in destination.glob("ext_*.wav"):
            stale_exterior.unlink()
    warmup_frames = 288000 if car.pack_id in LONG_STARTUP_WARMUP_PACKS else 48000
    for prefix, event, throttle in programs:
        for name, rpm in zip(("idle", "load_1", "load_2", "load_3"), rpm_values):
            render_rpm = (
                rpm_values[1]
                if car.pack_id == "bmw-m8-gtlm" and prefix == "ext_" and name == "idle"
                else rpm
            )
            rendered = destination / f".{prefix}{name}.render.wav"
            renderer.render_event(
                bank,
                event,
                rendered,
                parameters={"rpms": render_rpm, "throttle": throttle},
                duration_frames=192000,
                warmup_frames=warmup_frames,
                # Keep engine_int's stereo source/routing, while removing the
                # low-pass/EQ cabin treatment that made the earlier generic
                # interiors sound muffled.  `engine_ext` is never substituted.
                bypass_cabin_eq=event == "engine_int",
            )
            add_smpl_loop(rendered, destination / f"{prefix}{name}.wav")
            rendered.unlink(missing_ok=True)
        for index, rpm in enumerate(rpm_values[1:], start=1):
            rendered = destination / f".{prefix}coast_{index}.render.wav"
            renderer.render_event(
                bank,
                event,
                rendered,
                parameters={"rpms": rpm, "throttle": 0.0},
                duration_frames=192000,
                warmup_frames=48000,
                bypass_cabin_eq=event == "engine_int",
            )
            add_smpl_loop(rendered, destination / f"{prefix}coast_{index}.wav")
            rendered.unlink(missing_ok=True)
    apply_known_silent_layer_fallbacks(car, destination)
    if not interior_only:
        capture_generic_effects(car, assetto_root, work_root)
    return destination.parent


def apply_known_silent_layer_fallbacks(car: SourceCar, audio_directory: Path) -> None:
    for target_name, source_name in SILENT_LAYER_FALLBACKS.get(car.pack_id, {}).items():
        source = audio_directory / source_name
        if not source.is_file():
            raise RuntimeError(f"Missing audible fallback source for {car.pack_id}: {source_name}")
        shutil.copy2(source, audio_directory / target_name)


def copy_existing_profile(car: SourceCar, samples_root: Path, work_root: Path) -> Path:
    mapping = {
        "lamborghini_huracan_trofeo_evo2_cabin": (
            samples_root / "fx_lamborghini_huracan_trofeo_evo2" / "converted",
            samples_root / "fx_lamborghini_huracan_trofeo_evo2" / "converted_exterior",
        ),
        "lamborghini_aventador_sv_cabin": (
            samples_root / "tr_lamborghini_aventador_sv" / "converted",
            samples_root / "tr_lamborghini_aventador_sv" / "converted_exterior",
        ),
        "nissan_skyline_r34_cabin": (
            samples_root / "fx_nissan_skyline_r34" / "converted",
        ),
    }
    destination = work_root / car.pack_id / "audio"
    destination.mkdir(parents=True, exist_ok=True)
    for source in mapping[car.pack_id]:
        for wav in source.glob("*.wav"):
            shutil.copy2(wav, destination / wav.name)
    return destination.parent


def copy_shared_effects(samples_root: Path, pack_root: Path) -> None:
    pops = REPO_ROOT / "reference" / "alfa_romeo_4c_exhaust_effects" / "01_backfire_internal"
    shifts = samples_root / "fx_lamborghini_huracan_trofeo_evo2" / "converted"
    pops_destination = pack_root / "shared" / "pops_and_bangs"
    shifts_destination = pack_root / "shared" / "huracan_shift_sounds"
    pops_destination.mkdir(parents=True, exist_ok=True)
    shifts_destination.mkdir(parents=True, exist_ok=True)
    for wav in pops.glob("backfire_*.wav"):
        shutil.copy2(wav, pops_destination / wav.name)
    for name in ("fx_shift_up.wav", "fx_shift_down.wav"):
        shutil.copy2(shifts / name, shifts_destination / name)


def write_pack(car: SourceCar, pack_root: Path, output: Path, samples_root: Path) -> Path:
    copy_shared_effects(samples_root, pack_root)
    entries = []
    for file in sorted(
        path for path in pack_root.rglob("*") if path.is_file() and path.name != MANIFEST_NAME
    ):
        relative = file.relative_to(pack_root).as_posix()
        entries.append({"path": relative, "bytes": file.stat().st_size, "sha256": sha256(file)})
    manifest = {"schema": PACK_SCHEMA, "id": car.pack_id, "version": 1, "files": entries}
    (pack_root / MANIFEST_NAME).write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")), encoding="utf-8")
    archive = output / f"{car.pack_id}.bydpack"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zip_file:
        for path in [pack_root / MANIFEST_NAME, *(pack_root / entry["path"] for entry in entries)]:
            info = zipfile.ZipInfo(path.relative_to(pack_root).as_posix(), ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            zip_file.writestr(info, path.read_bytes())
    return archive


def reusable_pack(car: SourceCar, output: Path) -> Path | None:
    """Return an already verified archive so an interrupted bulk pass resumes safely."""
    archive = output / f"{car.pack_id}.bydpack"
    if not archive.is_file():
        return None
    try:
        with zipfile.ZipFile(archive) as zip_file:
            manifest = json.loads(zip_file.read(MANIFEST_NAME))
            if manifest.get("schema") != PACK_SCHEMA or manifest.get("id") != car.pack_id:
                return None
            for entry in manifest.get("files", []):
                payload = zip_file.read(entry["path"])
                if len(payload) != entry["bytes"] or hashlib.sha256(payload).hexdigest() != entry["sha256"]:
                    return None
    except (KeyError, OSError, ValueError, zipfile.BadZipFile):
        return None
    return archive


def installed_pack_index(output: Path, cars: list[SourceCar]) -> list[dict[str, object]]:
    """Rebuild the installer index from every present archive, including partial runs."""
    names = {car.pack_id: car.display_name for car in cars}
    installed: list[dict[str, object]] = []
    for archive in sorted(output.glob("*.bydpack")):
        try:
            with zipfile.ZipFile(archive) as zip_file:
                manifest = json.loads(zip_file.read(MANIFEST_NAME))
            pack_id = manifest.get("id")
            if not isinstance(pack_id, str) or pack_id not in names:
                continue
            installed.append({
                "id": pack_id,
                "name": names[pack_id],
                "asset": archive.name,
                "bytes": archive.stat().st_size,
            })
        except (KeyError, OSError, ValueError, zipfile.BadZipFile):
            continue
    return installed


def print_generic_effect_availability(work_root: Path) -> None:
    """Print the checked-in Kotlin availability map from audited capture reports."""
    effect_names = (
        "transmission",
        "shift_up",
        "shift_down",
        "turbo_loop",
        "turbo_dump",
        "limiter",
        "overrun",
    )
    reports = sorted((work_root / EFFECT_REPORT_DIRECTORY).glob("*.json"))
    if not reports:
        raise RuntimeError("No effect reports exist; run --effects-only before emitting the Kotlin map")
    print("internal val genericCarEffectAvailability = mapOf(")
    for report_path in reports:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        pack_id = report.get("packId")
        perspectives = report.get("perspectives")
        if not isinstance(pack_id, str) or not isinstance(perspectives, dict):
            raise RuntimeError(f"Invalid effect report: {report_path}")

        def effect_set(perspective: str) -> str:
            captured = perspectives.get(perspective, {})
            if not isinstance(captured, dict):
                raise RuntimeError(f"Invalid {perspective} report for {pack_id}")
            values = [
                f"GenericCarEffect.{effect.upper()}"
                for effect in effect_names
                if isinstance(captured.get(effect), dict) and "asset" in captured[effect]
            ]
            return "emptySet()" if not values else f"setOf({', '.join(values)})"

        print(
            f'    "{pack_id}" to GenericCarEffectAvailability('
            f"{effect_set('cabin')}, {effect_set('exterior')}),"
        )
    print(")")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-root", type=Path, default=DEFAULT_ORIGINAL_ROOT)
    parser.add_argument("--new-root", type=Path, default=DEFAULT_NEW_ROOT)
    parser.add_argument("--audio-samples", type=Path, default=DEFAULT_AUDIO_SAMPLES)
    parser.add_argument("--assetto-root", type=Path, default=DEFAULT_ASSETTO_ROOT)
    parser.add_argument("--bank-root", type=Path, default=DEFAULT_BANK_FALLBACK_ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--work", type=Path, default=DEFAULT_WORK)
    parser.add_argument("--only", action="append", default=[])
    parser.add_argument("--force", action="store_true", help="Recreate valid packs instead of resuming them")
    parser.add_argument(
        "--interior-only",
        action="store_true",
        help="Regenerate only generic engine_int WAVs with the cabin filter/EQ bypassed",
    )
    parser.add_argument(
        "--repack",
        action="store_true",
        help="Recreate archives from existing authoring WAVs without another FMOD render",
    )
    parser.add_argument(
        "--repair-known-silent-layers",
        action="store_true",
        help="Apply documented no-FMOD layer substitutions to existing authoring WAVs",
    )
    parser.add_argument(
        "--effects-only",
        action="store_true",
        help="Capture only generic side events, then repack without rerendering engine layers",
    )
    parser.add_argument(
        "--print-effect-availability",
        action="store_true",
        help="Print the Kotlin map reconstructed from captured effect reports",
    )
    arguments = parser.parse_args()
    if arguments.print_effect_availability:
        if arguments.only or arguments.force or arguments.interior_only or arguments.repack or arguments.repair_known_silent_layers or arguments.effects_only:
            parser.error("--print-effect-availability only reads the completed capture reports")
        print_generic_effect_availability(arguments.work)
        return 0
    if arguments.interior_only and (arguments.repack or arguments.repair_known_silent_layers or arguments.effects_only):
        parser.error("--interior-only renders WAVs and cannot be combined with archive-only modes")
    if sum(bool(value) for value in (arguments.repack, arguments.repair_known_silent_layers, arguments.effects_only)) > 1:
        parser.error("Choose only one archive-only mode")
    selected = set(arguments.only)
    cars = [
        car
        for car in discover_cars(arguments.original_root, arguments.new_root, arguments.bank_root)
        if (
            car.pack_id not in EXACT_BANK_AUDIO_OWNER
            and (not selected or car.pack_id in selected)
            and (not arguments.interior_only or car.generic)
            and (not arguments.effects_only or car.generic)
        )
    ]
    if not cars:
        raise SystemExit("No matching cars")
    arguments.output.mkdir(parents=True, exist_ok=True)
    arguments.work.mkdir(parents=True, exist_ok=True)
    report = []
    for car in cars:
        try:
            archive = (
                None
                if arguments.force or arguments.repack or arguments.interior_only or arguments.repair_known_silent_layers or arguments.effects_only
                else reusable_pack(car, arguments.output)
            )
            if archive is None:
                pack_root = arguments.work / car.pack_id if arguments.repack or arguments.repair_known_silent_layers or arguments.effects_only else (
                    render_generic_car(
                        car,
                        arguments.assetto_root,
                        arguments.work,
                        interior_only=arguments.interior_only,
                    )
                    if car.generic
                    else copy_existing_profile(car, arguments.audio_samples, arguments.work)
                )
                if not pack_root.is_dir():
                    raise RuntimeError(f"No existing authoring WAVs for {car.pack_id}")
                if arguments.repair_known_silent_layers:
                    apply_known_silent_layer_fallbacks(car, pack_root / "audio")
                if arguments.effects_only:
                    capture_generic_effects(car, arguments.assetto_root, arguments.work)
                archive = write_pack(car, pack_root, arguments.output, arguments.audio_samples)
                print(f"built {car.pack_id}")
            else:
                print(f"reused {car.pack_id}")
            report.append({"id": car.pack_id, "name": car.display_name, "asset": archive.name, "bytes": archive.stat().st_size})
        except Exception as error:
            print(f"FAILED {car.pack_id}: {error}", file=sys.stderr)
            report.append({"id": car.pack_id, "name": car.display_name, "error": str(error)})
    all_cars = discover_cars(arguments.original_root, arguments.new_root, arguments.bank_root)
    selected_installed = [row for row in report if "asset" in row]
    installed = installed_pack_index(arguments.output, all_cars)
    (arguments.output / "index.json").write_text(json.dumps({"schema": "byd-audio-pack-index-v1", "packs": installed}, indent=2), encoding="utf-8")
    (arguments.work / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return 0 if len(selected_installed) == len(cars) else 1


if __name__ == "__main__":
    raise SystemExit(main())
