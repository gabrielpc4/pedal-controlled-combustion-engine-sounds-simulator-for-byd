#!/usr/bin/env python3
"""Repair an old FMOD Android ELF layout after copying it into build staging.

FMOD Studio 1.10 was built with a linker that left a few local linker-marker
symbols after the global portion of `.dynsym`. Modern Android NDK linkers reject
that otherwise loadable layout when our JNI bridge links against the SDK. The
markers are not public API, so promote only those out-of-order local entries in
the generated copy. The downloaded SDK is never modified.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path


PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_HASH = 4
DT_SYMTAB = 6
DT_SYMENT = 11
SHT_DYNSYM = 11
STB_LOCAL = 0
STB_GLOBAL = 1


def fail(message: str) -> None:
    raise SystemExit(f"FMOD ELF repair: {message}")


def unpack_from(format_string: str, payload: bytearray, offset: int) -> tuple[int, ...]:
    return struct.unpack_from(format_string, payload, offset)


def virtual_to_file_offset(load_segments: list[tuple[int, int, int]], address: int) -> int:
    for virtual_start, file_start, file_size in load_segments:
        if virtual_start <= address < virtual_start + file_size:
            return file_start + address - virtual_start
    fail(f"no load segment contains virtual address 0x{address:x}")


def repair(path: Path) -> int:
    payload = bytearray(path.read_bytes())
    if payload[:4] != b"\x7fELF" or payload[5] != 1:
        fail(f"{path} is not a little-endian ELF file")

    elf_class = payload[4]
    if elf_class == 2:
        header_format = "<16sHHIQQQIHHHHHH"
        program_header_format = "<IIQQQQQQ"
        section_header_format = "<IIQQQQIIQQ"
        dynamic_format = "<QQ"
        symbol_info_offset = 4
    elif elf_class == 1:
        header_format = "<16sHHIIIIIHHHHHH"
        program_header_format = "<IIIIIIII"
        section_header_format = "<IIIIIIIIII"
        dynamic_format = "<II"
        symbol_info_offset = 12
    else:
        fail(f"{path} has unsupported ELF class {elf_class}")

    header = unpack_from(header_format, payload, 0)
    program_offset = header[5]
    program_entry_size = header[9]
    program_count = header[10]
    section_offset = header[6]
    section_entry_size = header[11]
    section_count = header[12]
    program_size = struct.calcsize(program_header_format)
    if program_entry_size < program_size:
        fail(f"{path} has invalid program-header size")

    load_segments: list[tuple[int, int, int]] = []
    dynamic_segment: tuple[int, int] | None = None
    for index in range(program_count):
        entry_offset = program_offset + index * program_entry_size
        entry = unpack_from(program_header_format, payload, entry_offset)
        if elf_class == 2:
            segment_type, _, file_offset, virtual_address, _, file_size, _, _ = entry
        else:
            segment_type, file_offset, virtual_address, _, file_size, _, _, _ = entry
        if segment_type == PT_LOAD:
            load_segments.append((virtual_address, file_offset, file_size))
        elif segment_type == PT_DYNAMIC:
            dynamic_segment = (file_offset, file_size)

    if dynamic_segment is None:
        fail(f"{path} has no dynamic segment")

    dynamic_values: dict[int, int] = {}
    dynamic_offset, dynamic_size = dynamic_segment
    dynamic_entry_size = struct.calcsize(dynamic_format)
    for offset in range(dynamic_offset, dynamic_offset + dynamic_size, dynamic_entry_size):
        tag, value = unpack_from(dynamic_format, payload, offset)
        if tag == DT_NULL:
            break
        dynamic_values[tag] = value

    symbol_address = dynamic_values.get(DT_SYMTAB)
    hash_address = dynamic_values.get(DT_HASH)
    symbol_entry_size = dynamic_values.get(DT_SYMENT)
    if symbol_address is None or hash_address is None or symbol_entry_size is None:
        fail(f"{path} has no complete dynamic symbol table")

    hash_offset = virtual_to_file_offset(load_segments, hash_address)
    _, symbol_count = unpack_from("<II", payload, hash_offset)
    symbol_offset = virtual_to_file_offset(load_segments, symbol_address)
    section_size = struct.calcsize(section_header_format)
    if section_entry_size < section_size:
        fail(f"{path} has invalid section-header size")

    first_global_index: int | None = None
    for index in range(section_count):
        entry = unpack_from(
            section_header_format,
            payload,
            section_offset + index * section_entry_size,
        )
        section_type = entry[1]
        section_address = entry[3]
        section_info = entry[7]
        if section_type == SHT_DYNSYM and section_address == symbol_address:
            first_global_index = section_info
            break
    if first_global_index is None:
        fail(f"{path} has no dynamic-symbol section metadata")

    repaired = 0
    for index in range(symbol_count):
        info_offset = symbol_offset + index * symbol_entry_size + symbol_info_offset
        symbol_info = payload[info_offset]
        binding = symbol_info >> 4
        if binding == STB_LOCAL and index >= first_global_index:
            payload[info_offset] = (STB_GLOBAL << 4) | (symbol_info & 0x0F)
            repaired += 1

    if repaired:
        path.write_bytes(payload)
    return repaired


if __name__ == "__main__":
    if len(sys.argv) < 2:
        fail("pass one or more staged FMOD .so files")
    count = sum(repair(Path(argument)) for argument in sys.argv[1:])
    print(f"repaired {count} FMOD dynamic-symbol entries")
