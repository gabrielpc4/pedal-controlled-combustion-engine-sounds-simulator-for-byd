"""Read Assetto Corsa ``data.acd`` archives without modifying game files.

The key derivation and container layout are based on the MIT-licensed decoder
from albertowd/live-telemetry, itself crediting Luigi Auriemma for the format.
Only the small, read-only subset needed by this simulator is implemented here.
"""

from __future__ import annotations

import struct
from collections.abc import Iterator
from pathlib import Path


def _key_for_car(car_name: str) -> bytes:
    i = 0
    key1 = 0
    while i < len(car_name):
        key1 += ord(car_name[i])
        i += 1
    key1 &= 0xFF

    i = 0
    key2 = 0
    while i < len(car_name) - 1:
        key2 *= ord(car_name[i])
        i += 1
        key2 -= ord(car_name[i])
        i += 1
    key2 &= 0xFF

    i = 1
    key3 = 0
    while i < len(car_name) - 3:
        key3 *= ord(car_name[i])
        i += 1
        key3 = int(key3 / (ord(car_name[i]) + 0x1B))
        i -= 2
        key3 += -0x1B - ord(car_name[i])
        i += 4
    key3 &= 0xFF

    i = 1
    key4 = 0x1683
    while i < len(car_name):
        key4 -= ord(car_name[i])
        i += 1
    key4 &= 0xFF

    i = 1
    key5 = 0x42
    while i < len(car_name) - 4:
        temporary = (ord(car_name[i]) + 0x0F) * key5
        i -= 1
        key5 = (ord(car_name[i]) + 0x0F) * temporary + 0x16
        i += 5
    key5 &= 0xFF

    i = 0
    key6 = 0x65
    while i < len(car_name) - 2:
        key6 -= ord(car_name[i])
        i += 2
    key6 &= 0xFF

    i = 0
    key7 = 0xAB
    while i < len(car_name) - 2:
        key7 %= ord(car_name[i])
        i += 2
    key7 &= 0xFF

    i = 0
    key8 = 0xAB
    while i < len(car_name) - 1:
        key8 = int(key8 / ord(car_name[i])) + ord(car_name[i + 1])
        i += 1
    key8 &= 0xFF

    text = f"{key1}-{key2}-{key3}-{key4}-{key5}-{key6}-{key7}-{key8}"
    return text.encode("ascii")


def iter_acd_files(archive_path: Path, car_name: str) -> Iterator[tuple[str, bytes]]:
    """Yield decrypted ``(name, payload)`` entries from an ACD archive."""

    content = archive_path.read_bytes()
    key = _key_for_car(car_name)
    offset = 0
    if len(content) >= 8 and struct.unpack_from("<i", content, 0)[0] < 0:
        offset = 8

    while offset < len(content):
        if offset + 4 > len(content):
            raise ValueError(f"truncated ACD name length at byte {offset}")
        name_size = struct.unpack_from("<I", content, offset)[0]
        offset += 4
        if offset + name_size + 4 > len(content):
            raise ValueError(f"truncated ACD name at byte {offset}")
        name = content[offset : offset + name_size].decode("utf-8")
        offset += name_size
        file_size = struct.unpack_from("<I", content, offset)[0]
        offset += 4
        packed_size = file_size * 4
        if offset + packed_size > len(content):
            raise ValueError(f"truncated ACD payload for {name!r}")
        packed = content[offset : offset + packed_size : 4]
        offset += packed_size
        decoded = bytes((value - key[index % len(key)]) & 0xFF for index, value in enumerate(packed))
        yield name, decoded


def load_car_data(car_directory: Path) -> dict[str, bytes]:
    """Load a car's loose ``data`` folder or its packed ``data.acd`` archive."""

    loose = car_directory / "data"
    if loose.is_dir():
        return {path.name: path.read_bytes() for path in loose.iterdir() if path.is_file()}
    archive = car_directory / "data.acd"
    if not archive.is_file():
        raise FileNotFoundError(f"no data folder or data.acd under {car_directory}")
    # Assetto derives the ACD key from its case-insensitive car identifier.
    # Mods copied from Windows can retain mixed-case folder names on macOS,
    # but their packed data was still encrypted with the normalized id.
    return dict(iter_acd_files(archive, car_directory.name.casefold()))


def text_file(files: dict[str, bytes], name: str) -> str:
    """Decode one AC text file while tolerating legacy Windows encodings."""

    raw = files[name]
    for encoding in ("utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1", "replace")
