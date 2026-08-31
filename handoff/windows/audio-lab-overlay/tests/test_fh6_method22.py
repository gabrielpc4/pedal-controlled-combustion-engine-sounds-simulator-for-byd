from __future__ import annotations

import tempfile
import unittest
import wave
import zipfile
import struct
from pathlib import Path

from fh6.method22 import DecodeCache, METHOD_TRANSFORMIT_DEFLATE, Method22Decoder, TransformITMaterial, inspect_archive


class Method22Tests(unittest.TestCase):
    def test_transformit_material_and_chunk_reader_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "aes_sbox_decrypt").write_bytes(bytes(84 * 256 * 4))
            (directory / "aes_sbox_indices_dec").write_text("\n".join(["0,0,0,0"] * 68), encoding="utf-8")
            (directory / "file_decryptionkey").write_bytes(bytes(72 * 4))
            material = TransformITMaterial.load(directory)
            self.assertEqual(material.decrypt_block(bytes(16)), bytes(16))
            payload = bytes(16) + struct.pack("<I", 0) + bytes(16) + bytes(0x200) + bytes(16)
            self.assertEqual(material.decrypt_stream(payload), bytes(0x200))

    def test_installed_supra_archive_is_identified_as_method_22(self) -> None:
        archive = Path(r"D:\Games\Forza Horizon 6\media\Audio\EngineSynth\G_I6TC_Asian_Street_6_Eng.zip")
        if not archive.is_file():
            self.skipTest("installed FH6 Supra archive not found")
        entries = inspect_archive(archive)
        self.assertGreater(len(entries), 20)
        self.assertTrue(all(entry.method == METHOD_TRANSFORMIT_DEFLATE for entry in entries))
        self.assertTrue(any("idle" in entry.name.lower() for entry in entries))
        self.assertTrue(all(entry.payload_offset > entry.local_header_offset for entry in entries))

    def test_standard_archive_cache_validates_crc_hash_and_wav_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wav_path = root / "tone.wav"
            with wave.open(str(wav_path), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(48000)
                wav.writeframes(b"\0\0" * 48)
            archive = root / "fixture.zip"
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                output.write(wav_path, "loops/tone.wav")
            cache = DecodeCache(root / "cache")
            result = Method22Decoder(cache).decode(archive)
            self.assertTrue(result.valid, result.errors)
            self.assertEqual(result.decoded_entries, 1)
            decoded = cache.archive_directory(archive) / "loops" / "tone.wav"
            decoded.write_bytes(decoded.read_bytes()[:-2] + b"xx")
            invalid = cache.validate(archive)
            self.assertFalse(invalid.valid)
            self.assertTrue(any("CRC mismatch" in error for error in invalid.errors))


if __name__ == "__main__":
    unittest.main()
