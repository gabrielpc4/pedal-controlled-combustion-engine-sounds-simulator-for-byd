"""FH6 method-22 archive inspection and versioned local decode cache.

Method 22 is not a normal ZIP compression method.  FH6 wraps raw DEFLATE in
Playground Games' TransformIT white-box AES stream.  This module owns the
archive/cache contract and every integrity check.  The private TransformIT
tables must be recovered from the user's installed executable; they are never
downloaded or committed.
"""

from __future__ import annotations

import binascii
import hashlib
import json
import os
import struct
import subprocess
import wave
import zipfile
import zlib
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


CACHE_FORMAT_VERSION = 1
METHOD_TRANSFORMIT_DEFLATE = 22


class Method22Unavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class TransformITMaterial:
    """White-box tables and round key recovered from the installed build."""

    tables: tuple[tuple[int, ...], ...]
    indices: tuple[tuple[tuple[int, ...], ...], ...]
    key_words: tuple[int, ...]

    @classmethod
    def load(cls, directory: Path) -> "TransformITMaterial":
        table_path = directory / "aes_sbox_decrypt"
        index_path = directory / "aes_sbox_indices_dec"
        key_path = directory / "file_decryptionkey"
        missing = [path.name for path in (table_path, index_path, key_path) if not path.is_file()]
        if missing:
            raise Method22Unavailable("TransformIT material is incomplete: " + ", ".join(missing))
        table_data = table_path.read_bytes()
        if len(table_data) != 84 * 256 * 4:
            raise Method22Unavailable(f"aes_sbox_decrypt has unexpected size {len(table_data)}")
        flat_tables = struct.unpack(f"<{84 * 256}i", table_data)
        tables = tuple(tuple(flat_tables[index * 256 : (index + 1) * 256]) for index in range(84))

        rows: list[tuple[int, ...]] = []
        for raw in index_path.read_text(encoding="utf-8-sig").splitlines():
            line = raw.strip()
            if not line or line.startswith("//"):
                continue
            values = tuple(int(part.strip(), 0) for part in line.split(",") if part.strip())
            if len(values) != 4:
                raise Method22Unavailable("each TransformIT index row must contain four table indices")
            if any(value < 0 or value >= 84 for value in values):
                raise Method22Unavailable("TransformIT index references a table outside 0..83")
            rows.append(values)
        if len(rows) != 17 * 4:
            raise Method22Unavailable(f"expected 68 TransformIT index rows, found {len(rows)}")
        indices = tuple(tuple(rows[round_index * 4 : (round_index + 1) * 4]) for round_index in range(17))

        key_data = key_path.read_bytes()
        if len(key_data) != 72 * 4:
            raise Method22Unavailable(f"file_decryptionkey has unexpected size {len(key_data)}")
        return cls(tables, indices, struct.unpack("<72i", key_data))

    def _lookup(self, row: tuple[int, ...], sources: tuple[int, int, int, int], key: int) -> int:
        value = key
        for byte_index, shift in enumerate((24, 16, 8, 0)):
            value ^= self.tables[row[byte_index]][(sources[byte_index] >> shift) & 0xFF]
        return value & 0xFFFFFFFF

    def decrypt_block(self, block: bytes) -> bytes:
        if len(block) != 16:
            raise ValueError("TransformIT operates on 16-byte blocks")
        words = tuple(struct.unpack("<4I", block))
        # The first two rounds transform each word independently.
        for round_index in (0, 1):
            rows = self.indices[round_index]
            words = tuple(
                self._lookup(rows[index], (word, word, word, word), self.key_words[4 * (round_index + 1) + index])
                for index, word in enumerate(words)
            )
        # The middle fourteen rounds use the inverse ShiftRows arrangement
        # recovered from FH6's TransformIT reader.
        for round_index in range(2, 16):
            rows = self.indices[round_index]
            base = 4 * (round_index + 1)
            output: list[int] = []
            for output_index in range(4):
                shift = (output_index + 1) % 4
                row_index = shift
                sources = tuple(words[(shift + byte_index) % 4] for byte_index in range(4))
                output.append(self._lookup(rows[row_index], sources, self.key_words[base + output_index]))
            words = tuple(output)
        # The final round returns to independent words with a rotated row map.
        rows = self.indices[16]
        words = tuple(
            self._lookup(rows[(index + 3) % 4], (word, word, word, word), self.key_words[68 + index])
            for index, word in enumerate(words)
        )
        return struct.pack("<4I", *words)

    def decrypt_cbc(self, ciphertext: bytes, iv: bytes) -> tuple[bytes, bytes]:
        if len(iv) != 16 or len(ciphertext) % 16:
            raise ValueError("invalid TransformIT CBC block or IV length")
        output = bytearray()
        current_iv = iv
        for offset in range(0, len(ciphertext), 16):
            encrypted = ciphertext[offset : offset + 16]
            decoded = self.decrypt_block(encrypted)
            output.extend(left ^ right for left, right in zip(decoded, current_iv))
            current_iv = encrypted
        return bytes(output), current_iv

    def decrypt_stream(self, payload: bytes, chunk_size: int = 0x200) -> bytes:
        if len(payload) < 36:
            raise ValueError("method-22 payload is shorter than its TransformIT header")
        base_iv = payload[:16]
        last_chunk_padding = struct.unpack_from("<I", payload, 16)[0]
        if last_chunk_padding > chunk_size:
            raise ValueError("invalid TransformIT last-chunk padding")
        encrypted = memoryview(payload)[36:]
        stride = chunk_size + 16
        if len(encrypted) == 0 or len(encrypted) % stride:
            raise ValueError("method-22 chunk stream has an invalid size")
        chunks = len(encrypted) // stride
        result = bytearray()
        current_iv = base_iv
        for chunk_index in range(chunks):
            offset = chunk_index * stride
            ciphertext = bytes(encrypted[offset : offset + chunk_size])
            next_iv = bytes(encrypted[offset + chunk_size : offset + stride])
            clear, _ = self.decrypt_cbc(ciphertext, current_iv)
            take = chunk_size - last_chunk_padding if chunk_index == chunks - 1 else chunk_size
            result.extend(clear[:take])
            current_iv = next_iv
        return bytes(result)


@dataclass(frozen=True)
class ArchiveEntry:
    name: str
    method: int
    flags: int
    crc32: int
    compressed_size: int
    size: int
    local_header_offset: int
    payload_offset: int

    @property
    def method22(self) -> bool:
        return self.method == METHOD_TRANSFORMIT_DEFLATE


@dataclass(frozen=True)
class WavMetadata:
    channels: int
    sample_width: int
    sample_rate: int
    frame_count: int


@dataclass(frozen=True)
class CacheValidation:
    valid: bool
    decoded_entries: int
    expected_entries: int
    errors: tuple[str, ...]


def _safe_relative(name: str) -> Path:
    pure = PurePosixPath(name)
    if pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"unsafe archive entry: {name}")
    return Path(*pure.parts)


def _payload_offset(stream, info: zipfile.ZipInfo) -> int:
    stream.seek(info.header_offset)
    header = stream.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError(f"invalid local ZIP header for {info.filename}")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def inspect_archive(path: Path) -> tuple[ArchiveEntry, ...]:
    entries: list[ArchiveEntry] = []
    with path.open("rb") as raw, zipfile.ZipFile(raw) as archive:
        for info in archive.infolist():
            entries.append(
                ArchiveEntry(
                    name=info.filename,
                    method=info.compress_type,
                    flags=info.flag_bits,
                    crc32=info.CRC,
                    compressed_size=info.compress_size,
                    size=info.file_size,
                    local_header_offset=info.header_offset,
                    payload_offset=_payload_offset(raw, info),
                )
            )
    return tuple(entries)


def archive_sha256(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wav_metadata(path: Path) -> WavMetadata:
    with wave.open(str(path), "rb") as wav:
        return WavMetadata(
            channels=wav.getnchannels(),
            sample_width=wav.getsampwidth(),
            sample_rate=wav.getframerate(),
            frame_count=wav.getnframes(),
        )


class DecodeCache:
    def __init__(self, root: Path):
        self.root = root

    def archive_directory(self, archive: Path) -> Path:
        token = archive_sha256(archive)[:16]
        return self.root / f"v{CACHE_FORMAT_VERSION}" / f"{archive.stem}-{token}"

    def validate(self, archive: Path) -> CacheValidation:
        entries = tuple(entry for entry in inspect_archive(archive) if not entry.name.endswith("/"))
        directory = self.archive_directory(archive)
        manifest_path = directory / "manifest.json"
        errors: list[str] = []
        decoded = 0
        if not manifest_path.is_file():
            return CacheValidation(False, 0, len(entries), ("cache manifest is missing",))
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            return CacheValidation(False, 0, len(entries), (f"invalid cache manifest: {exc}",))
        if manifest.get("version") != CACHE_FORMAT_VERSION:
            errors.append("cache format version differs")
        if manifest.get("archiveSha256") != archive_sha256(archive):
            errors.append("source archive hash differs")
        recorded = manifest.get("entries", {})
        for entry in entries:
            record = recorded.get(entry.name)
            if not isinstance(record, dict):
                errors.append(f"missing manifest entry: {entry.name}")
                continue
            try:
                output = directory / _safe_relative(entry.name)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            if not output.is_file():
                errors.append(f"decoded file missing: {entry.name}")
                continue
            data = output.read_bytes()
            if len(data) != entry.size:
                errors.append(f"size mismatch: {entry.name}")
                continue
            if (binascii.crc32(data) & 0xFFFFFFFF) != entry.crc32:
                errors.append(f"CRC mismatch: {entry.name}")
                continue
            digest = hashlib.sha256(data).hexdigest()
            if record.get("sha256") != digest:
                errors.append(f"deterministic hash mismatch: {entry.name}")
                continue
            if output.suffix.lower() == ".wav":
                try:
                    actual_wav = asdict(wav_metadata(output))
                except (wave.Error, EOFError) as exc:
                    errors.append(f"invalid WAV {entry.name}: {exc}")
                    continue
                if record.get("wav") != actual_wav:
                    errors.append(f"WAV metadata mismatch: {entry.name}")
                    continue
            decoded += 1
        return CacheValidation(not errors and decoded == len(entries), decoded, len(entries), tuple(errors))

    def write_manifest(self, archive: Path) -> Path:
        directory = self.archive_directory(archive)
        entries: dict[str, object] = {}
        for entry in inspect_archive(archive):
            if entry.name.endswith("/"):
                continue
            output = directory / _safe_relative(entry.name)
            if not output.is_file():
                raise FileNotFoundError(f"decoder did not produce {output}")
            data = output.read_bytes()
            if len(data) != entry.size or (binascii.crc32(data) & 0xFFFFFFFF) != entry.crc32:
                raise ValueError(f"decoded integrity check failed for {entry.name}")
            record: dict[str, object] = {
                "size": len(data),
                "crc32": f"{entry.crc32:08x}",
                "sha256": hashlib.sha256(data).hexdigest(),
            }
            if output.suffix.lower() == ".wav":
                record["wav"] = asdict(wav_metadata(output))
            entries[entry.name] = record
        manifest = {
            "version": CACHE_FORMAT_VERSION,
            "source": str(archive),
            "archiveSha256": archive_sha256(archive),
            "entries": entries,
        }
        path = directory / "manifest.json"
        path.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
        return path


class Method22Decoder:
    """Runs the local TransformIT decoder and seals its output into the cache.

    `FH6_METHOD22_DECODER` may point at the locally-built native helper.  The
    helper contract is intentionally narrow: ``decoder archive output keys``.
    Keys and decoded assets stay outside the repository.
    """

    def __init__(self, cache: DecodeCache, helper: Path | None = None, key_directory: Path | None = None):
        configured = os.environ.get("FH6_METHOD22_DECODER")
        self.cache = cache
        self.helper = helper or (Path(configured) if configured else None)
        self.key_directory = key_directory

    @property
    def available(self) -> bool:
        return bool(
            self.key_directory
            and self.key_directory.is_dir()
            and all((self.key_directory / name).is_file() for name in ("aes_sbox_decrypt", "aes_sbox_indices_dec", "file_decryptionkey"))
        ) or bool(self.helper and self.helper.is_file() and self.key_directory and self.key_directory.is_dir())

    def decode(self, archive: Path) -> CacheValidation:
        methods = {entry.method for entry in inspect_archive(archive)}
        if METHOD_TRANSFORMIT_DEFLATE not in methods:
            self._decode_standard(archive)
            self.cache.write_manifest(archive)
            return self.cache.validate(archive)
        if not self.available:
            raise Method22Unavailable(
                "method-22 TransformIT tables have not been recovered from this installed FH6 executable; "
                "set FH6_METHOD22_DECODER and provide a local key/table directory"
            )
        if self.key_directory and all((self.key_directory / name).is_file() for name in ("aes_sbox_decrypt", "aes_sbox_indices_dec", "file_decryptionkey")):
            self._decode_transformit(archive, TransformITMaterial.load(self.key_directory))
        else:
            destination = self.cache.archive_directory(archive)
            destination.mkdir(parents=True, exist_ok=True)
            completed = subprocess.run(
                [str(self.helper), str(archive), str(destination), str(self.key_directory)],
                check=False,
                capture_output=True,
                text=True,
                timeout=300,
            )
            if completed.returncode:
                detail = completed.stderr.strip() or completed.stdout.strip() or f"exit {completed.returncode}"
                raise Method22Unavailable(f"local method-22 decoder failed: {detail}")
        self.cache.write_manifest(archive)
        result = self.cache.validate(archive)
        if not result.valid:
            raise ValueError("decoded cache failed validation: " + "; ".join(result.errors))
        return result

    def _decode_transformit(self, archive: Path, material: TransformITMaterial) -> None:
        destination = self.cache.archive_directory(archive)
        destination.mkdir(parents=True, exist_ok=True)
        entries = inspect_archive(archive)
        with archive.open("rb") as source:
            for entry in entries:
                if entry.name.endswith("/"):
                    continue
                source.seek(entry.payload_offset)
                payload = source.read(entry.compressed_size)
                if len(payload) != entry.compressed_size:
                    raise ValueError(f"truncated method-22 entry: {entry.name}")
                if entry.method == METHOD_TRANSFORMIT_DEFLATE:
                    raw_deflate = material.decrypt_stream(payload)
                    decoded = zlib.decompress(raw_deflate, -15)
                elif entry.method == zipfile.ZIP_STORED:
                    decoded = payload
                elif entry.method == zipfile.ZIP_DEFLATED:
                    decoded = zlib.decompress(payload, -15)
                else:
                    raise ValueError(f"unsupported mixed ZIP method {entry.method} in {entry.name}")
                if len(decoded) != entry.size or (binascii.crc32(decoded) & 0xFFFFFFFF) != entry.crc32:
                    raise ValueError(f"decoded size/CRC mismatch for {entry.name}")
                output = destination / _safe_relative(entry.name)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(decoded)

    def _decode_standard(self, archive: Path) -> None:
        destination = self.cache.archive_directory(archive)
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                if info.is_dir():
                    continue
                output = destination / _safe_relative(info.filename)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(source.read(info))


def inspect_many(paths: Iterable[Path]) -> dict[str, tuple[ArchiveEntry, ...]]:
    return {str(path): inspect_archive(path) for path in paths}
