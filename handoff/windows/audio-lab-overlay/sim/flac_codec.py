"""Pinned FLAC 1.5.0 encode/decode and bit-exact PCM verification."""

from __future__ import annotations

import array
import hashlib
import math
import os
import shutil
import subprocess
import tempfile
import urllib.request
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path


PINNED_FLAC_VERSION = "1.5.0"
PINNED_FLAC_ARCHIVE_URL = (
    "https://github.com/xiph/flac/releases/download/1.5.0/flac-1.5.0-win.zip"
)
PINNED_FLAC_ARCHIVE_SHA256 = (
    "53f1500f0d6e7c61379d7fee50d4a9f7f504c650009506d9ba015530d76c0dde"
)


class FlacToolError(RuntimeError):
    pass


@dataclass(frozen=True)
class PcmIntegrity:
    sample_rate: int
    channels: int
    bits_per_sample: int
    frame_count: int
    sha256: str
    peak_dbfs: float


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def bootstrap_pinned_flac(cache_root: Path) -> Path:
    """Download the official archive, verify it, and return ``flac.exe``.

    The tool cache is generated locally and must remain outside source
    control.  An existing verified installation is reused.
    """

    cache = cache_root.resolve()
    destination = cache / f"flac-{PINNED_FLAC_VERSION}"
    existing = _find_executable(destination, "flac.exe")
    if existing is not None:
        _verify_version(existing)
        return existing

    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="flac-bootstrap-", dir=cache) as temp_text:
        temp = Path(temp_text)
        archive = temp / "flac.zip"
        with urllib.request.urlopen(PINNED_FLAC_ARCHIVE_URL, timeout=60) as response:
            with archive.open("wb") as output:
                shutil.copyfileobj(response, output)
        actual_hash = _hash_file(archive)
        if actual_hash != PINNED_FLAC_ARCHIVE_SHA256:
            raise FlacToolError(
                f"FLAC archive SHA-256 mismatch: {actual_hash} != "
                f"{PINNED_FLAC_ARCHIVE_SHA256}"
            )
        extracted = temp / "extracted"
        extracted.mkdir()
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                target = (extracted / info.filename).resolve()
                if target != extracted and extracted not in target.parents:
                    raise FlacToolError(f"unsafe FLAC archive member {info.filename!r}")
            source.extractall(extracted)
        source_exe = _find_executable(extracted, "flac.exe")
        if source_exe is None:
            raise FlacToolError("official FLAC archive contains no flac.exe")
        # Preserve the matching metaflac executable, DLLs and licenses next to
        # the pinned binary.  Copying the whole archive also retains notices.
        staged = temp / "staged"
        shutil.copytree(extracted, staged)
        try:
            os.replace(staged, destination)
        except OSError:
            if not destination.exists():
                raise

    executable = _find_executable(destination, "flac.exe")
    if executable is None:
        raise FlacToolError("pinned FLAC installation is incomplete")
    _verify_version(executable)
    return executable


def _find_executable(directory: Path, name: str) -> Path | None:
    if not directory.is_dir():
        return None
    matches = sorted(
        directory.rglob(name),
        key=lambda path: ("win64" not in path.as_posix().casefold(), len(path.parts)),
    )
    return matches[0] if matches else None


def _verify_version(executable: Path) -> None:
    completed = subprocess.run(
        [str(executable), "--version"],
        check=False,
        capture_output=True,
        text=True,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    text = (completed.stdout + completed.stderr).strip()
    if completed.returncode or PINNED_FLAC_VERSION not in text:
        raise FlacToolError(
            f"expected FLAC {PINNED_FLAC_VERSION}, got {text or 'no version output'}"
        )


def inspect_pcm16_stereo_wav(path: Path) -> PcmIntegrity:
    digest = hashlib.sha256()
    peak = 0
    with wave.open(str(path), "rb") as source:
        channels = source.getnchannels()
        sample_width = source.getsampwidth()
        sample_rate = source.getframerate()
        frames = source.getnframes()
        compression = source.getcomptype()
        if (sample_rate, channels, sample_width, compression) != (48000, 2, 2, "NONE"):
            raise FlacToolError(
                "canonical input must be uncompressed 16-bit, 48 kHz stereo PCM"
            )
        while data := source.readframes(16384):
            digest.update(data)
            samples = array.array("h")
            samples.frombytes(data)
            if os.sys.byteorder != "little":
                samples.byteswap()
            if samples:
                peak = max(peak, max(abs(int(value)) for value in samples))
    peak_dbfs = -math.inf if peak == 0 else 20.0 * math.log10(peak / 32768.0)
    return PcmIntegrity(sample_rate, channels, 16, frames, digest.hexdigest(), peak_dbfs)


def calibrate_pcm16_stereo_wav(
    path: Path, *, ceiling_dbfs: float = -3.1
) -> tuple[float, PcmIntegrity]:
    """Attenuate a canonical WAV in place and return applied gain + integrity."""

    if not math.isfinite(ceiling_dbfs) or ceiling_dbfs > -3.0:
        raise FlacToolError("profile ceiling must be finite and no higher than -3 dBFS")
    before = inspect_pcm16_stereo_wav(path)
    if before.peak_dbfs <= ceiling_dbfs:
        return 0.0, before
    gain_db = ceiling_dbfs - before.peak_dbfs
    return gain_db, apply_gain_pcm16_stereo_wav(path, gain_db)


def apply_gain_pcm16_stereo_wav(path: Path, gain_db: float) -> PcmIntegrity:
    """Apply one non-amplifying gain to canonical PCM and rewrite atomically."""

    if not math.isfinite(gain_db) or gain_db > 0.0:
        raise FlacToolError("PCM calibration gain must be finite and non-positive")
    before = inspect_pcm16_stereo_wav(path)
    if gain_db == 0.0:
        return before
    scale = 10.0 ** (gain_db / 20.0)
    with wave.open(str(path), "rb") as source:
        data = source.readframes(source.getnframes())
    samples = array.array("h")
    samples.frombytes(data)
    if os.sys.byteorder != "little":
        samples.byteswap()
    for index, value in enumerate(samples):
        samples[index] = max(-32768, min(32767, round(int(value) * scale)))
    if os.sys.byteorder != "little":
        samples.byteswap()
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".wav", dir=path.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with wave.open(str(temporary_path), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(48000)
            output.writeframes(samples.tobytes())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)
    return inspect_pcm16_stereo_wav(path)


class PinnedFlacCodec:
    def __init__(self, flac_executable: Path):
        self.flac = flac_executable.resolve()
        _verify_version(self.flac)
        self.metaflac = self.flac.with_name("metaflac.exe")
        if not self.metaflac.is_file():
            discovered = _find_executable(self.flac.parents[2], "metaflac.exe")
            if discovered is None:
                raise FlacToolError("matching pinned metaflac.exe was not found")
            self.metaflac = discovered

    @property
    def provenance(self) -> dict[str, str]:
        return {
            "name": "libFLAC",
            "version": PINNED_FLAC_VERSION,
            "executableSha256": _hash_file(self.flac),
        }

    def encode_level8(self, wav_path: Path, flac_path: Path) -> PcmIntegrity:
        integrity = inspect_pcm16_stereo_wav(wav_path)
        flac_path.parent.mkdir(parents=True, exist_ok=True)
        command = [
            str(self.flac),
            "--silent",
            "--force",
            "--verify",
            "--best",
            "--no-padding",
            "--no-preserve-modtime",
            f"--output-name={flac_path}",
            str(wav_path),
        ]
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if completed.returncode:
            raise FlacToolError(
                f"FLAC encode failed for {wav_path.name}: "
                f"{(completed.stderr or completed.stdout).strip()}"
            )
        decoded = self.decode_integrity(flac_path)
        if decoded != integrity:
            raise FlacToolError(
                f"FLAC round trip changed PCM for {wav_path.name}: "
                f"before={integrity} after={decoded}"
            )
        return integrity

    def _metadata(self, flac_path: Path) -> tuple[int, int, int, int]:
        completed = subprocess.run(
            [
                str(self.metaflac),
                "--show-sample-rate",
                "--show-channels",
                "--show-bps",
                "--show-total-samples",
                str(flac_path),
            ],
            check=False,
            capture_output=True,
            text=True,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if completed.returncode:
            raise FlacToolError(
                f"metaflac failed for {flac_path.name}: {completed.stderr.strip()}"
            )
        values = [int(line.strip()) for line in completed.stdout.splitlines() if line.strip()]
        if len(values) != 4:
            raise FlacToolError(f"unexpected metaflac output: {completed.stdout!r}")
        return values[0], values[1], values[2], values[3]

    def decode_integrity(self, flac_path: Path) -> PcmIntegrity:
        sample_rate, channels, bits, frame_count = self._metadata(flac_path)
        if (sample_rate, channels, bits) != (48000, 2, 16):
            raise FlacToolError(
                f"{flac_path.name} is {sample_rate} Hz/{channels} ch/{bits} bit, "
                "expected 48000 Hz/2 ch/16 bit"
            )
        command = [
            str(self.flac),
            "--decode",
            "--stdout",
            "--silent",
            "--force-raw-format",
            "--endian=little",
            "--sign=signed",
            str(flac_path),
        ]
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        assert process.stdout is not None
        digest = hashlib.sha256()
        peak = 0
        decoded_bytes = 0
        while chunk := process.stdout.read(1024 * 1024):
            decoded_bytes += len(chunk)
            digest.update(chunk)
            samples = array.array("h")
            samples.frombytes(chunk)
            if os.sys.byteorder != "little":
                samples.byteswap()
            if samples:
                peak = max(peak, max(abs(int(value)) for value in samples))
        process.stdout.close()
        stderr = process.stderr.read() if process.stderr is not None else b""
        if process.stderr is not None:
            process.stderr.close()
        return_code = process.wait()
        if return_code:
            raise FlacToolError(
                f"FLAC decode failed for {flac_path.name}: "
                f"{stderr.decode('utf-8', 'replace').strip()}"
            )
        expected_bytes = frame_count * channels * (bits // 8)
        if decoded_bytes != expected_bytes:
            raise FlacToolError(
                f"decoded byte count {decoded_bytes} != metadata count {expected_bytes}"
            )
        peak_dbfs = -math.inf if peak == 0 else 20.0 * math.log10(peak / 32768.0)
        return PcmIntegrity(
            sample_rate,
            channels,
            bits,
            frame_count,
            digest.hexdigest(),
            peak_dbfs,
        )
