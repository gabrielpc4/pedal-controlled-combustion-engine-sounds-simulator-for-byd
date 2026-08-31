"""Read FMOD bank metadata through AC's runtime without opening an audio device."""

from __future__ import annotations

import ctypes as C
import os
from pathlib import Path

from .fmod_native import (
    ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
    DspDescription,
    FMOD_VERSION,
    FmodError,
    Guid,
    _distance_filter_description,
    _gain_description,
)


FMOD_OUTPUTTYPE_NOSOUND_NRT = 4
FMOD_SPEAKERMODE_STEREO = 3


class SilentFmodBankProbe:
    """A reusable metadata-only FMOD Studio session.

    The low-level output is switched to ``NOSOUND_NRT`` before Studio is
    initialized.  Probing therefore neither opens the Windows playback device
    nor depends on wall-clock mixer updates.
    """

    def __init__(self, assetto_root: Path):
        if os.name != "nt":
            raise FmodError("the installed AC FMOD oracle requires Windows")
        self.root = assetto_root.resolve()
        self._system = C.c_void_p()
        self._low_level = C.c_void_p()
        self._strings = C.c_void_p()
        self._common = C.c_void_p()
        self._closed = False
        self._dll_cookie = os.add_dll_directory(str(self.root))
        self._core = C.WinDLL(str(self.root / "fmod64.dll"))
        self._studio = C.WinDLL(str(self.root / "fmodstudio64.dll"))
        self._bind()
        self._initialize()

    @staticmethod
    def _check(result: int, operation: str) -> None:
        if result:
            raise FmodError(f"{operation}: FMOD result {result}")

    def _bind(self) -> None:
        studio_signatures = {
            "FMOD_Studio_System_Create": ([C.POINTER(C.c_void_p), C.c_uint], C.c_int),
            "FMOD_Studio_System_GetLowLevelSystem": (
                [C.c_void_p, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_System_Initialize": (
                [C.c_void_p, C.c_int, C.c_uint, C.c_uint, C.c_void_p],
                C.c_int,
            ),
            "FMOD_Studio_System_RegisterPlugin": (
                [C.c_void_p, C.POINTER(DspDescription)],
                C.c_int,
            ),
            "FMOD_Studio_System_LoadBankFile": (
                [C.c_void_p, C.c_char_p, C.c_uint, C.POINTER(C.c_void_p)],
                C.c_int,
            ),
            "FMOD_Studio_Bank_GetEventCount": (
                [C.c_void_p, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_Bank_GetEventList": (
                [C.c_void_p, C.POINTER(C.c_void_p), C.c_int, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_EventDescription_GetPath": (
                [C.c_void_p, C.c_char_p, C.c_int, C.POINTER(C.c_int)],
                C.c_int,
            ),
            "FMOD_Studio_EventDescription_GetID": (
                [C.c_void_p, C.POINTER(Guid)],
                C.c_int,
            ),
            "FMOD_Studio_Bank_Unload": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_UnloadAll": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_Release": ([C.c_void_p], C.c_int),
        }
        for name, (args, result) in studio_signatures.items():
            function = getattr(self._studio, name)
            function.argtypes = args
            function.restype = result

        self._core.FMOD_System_SetOutput.argtypes = [C.c_void_p, C.c_int]
        self._core.FMOD_System_SetOutput.restype = C.c_int
        self._core.FMOD_System_SetSoftwareFormat.argtypes = [
            C.c_void_p,
            C.c_int,
            C.c_int,
            C.c_int,
        ]
        self._core.FMOD_System_SetSoftwareFormat.restype = C.c_int
        self._core.FMOD_System_SetSoftwareChannels.argtypes = [C.c_void_p, C.c_int]
        self._core.FMOD_System_SetSoftwareChannels.restype = C.c_int

    def _initialize(self) -> None:
        api = self._studio
        self._check(
            api.FMOD_Studio_System_Create(C.byref(self._system), FMOD_VERSION),
            "create silent Studio system",
        )
        try:
            self._check(
                api.FMOD_Studio_System_GetLowLevelSystem(
                    self._system, C.byref(self._low_level)
                ),
                "get low-level system",
            )
            self._check(
                self._core.FMOD_System_SetOutput(
                    self._low_level, FMOD_OUTPUTTYPE_NOSOUND_NRT
                ),
                "select no-sound non-realtime output",
            )
            self._check(
                self._core.FMOD_System_SetSoftwareFormat(
                    self._low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0
                ),
                "force 48 kHz stereo software format",
            )
            self._check(
                self._core.FMOD_System_SetSoftwareChannels(
                    self._low_level, ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET
                ),
                "set Assetto real software-channel budget",
            )
            self._check(
                api.FMOD_Studio_System_Initialize(
                    self._system, ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP, 0, 0, None
                ),
                "initialize silent Studio system",
            )
            self._dsp_description, self._dsp_keepalive = _distance_filter_description()
            self._check(
                api.FMOD_Studio_System_RegisterPlugin(
                    self._system, C.byref(self._dsp_description)
                ),
                "register AC distance filter descriptor",
            )
            self._gain_description, self._gain_keepalive = _gain_description()
            self._check(
                api.FMOD_Studio_System_RegisterPlugin(
                    self._system, C.byref(self._gain_description)
                ),
                "register M3 E30 Gr.A gain descriptor",
            )
            self._strings = self._load_bank(
                self.root / "content" / "sfx" / "common.strings.bank"
            )
            self._common = self._load_bank(self.root / "content" / "sfx" / "common.bank")
        except Exception:
            self.close()
            raise

    def _load_bank(self, path: Path) -> C.c_void_p:
        bank = C.c_void_p()
        self._check(
            self._studio.FMOD_Studio_System_LoadBankFile(
                self._system, str(path).encode("utf-8"), 0, C.byref(bank)
            ),
            f"load {path}",
        )
        return bank

    @staticmethod
    def _guid_text(guid: Guid) -> str:
        tail = bytes(guid.data4)
        return (
            f"{guid.data1:08x}-{guid.data2:04x}-{guid.data3:04x}-"
            f"{tail[0]:02x}{tail[1]:02x}-"
            + "".join(f"{value:02x}" for value in tail[2:])
        )

    def probe_events(self, bank_path: Path) -> tuple[tuple[str, str], ...]:
        """Return deterministic ``(event GUID, path)`` pairs without playback."""

        if self._closed:
            raise RuntimeError("silent FMOD probe is closed")
        bank_path = bank_path.resolve()
        guid_paths: dict[str, str] = {}
        guid_file = bank_path.parent / "GUIDs.txt"
        if guid_file.is_file():
            for line in guid_file.read_text(
                encoding="utf-8-sig", errors="replace"
            ).splitlines():
                parts = line.split(None, 1)
                if len(parts) != 2 or not parts[1].startswith("event:/"):
                    continue
                try:
                    guid_paths[self._guid_text(Guid.parse(parts[0]))] = parts[1]
                except ValueError:
                    continue
        bank = self._load_bank(bank_path)
        try:
            count = C.c_int()
            self._check(
                self._studio.FMOD_Studio_Bank_GetEventCount(bank, C.byref(count)),
                f"count events in {bank_path.name}",
            )
            descriptions = (C.c_void_p * max(1, count.value))()
            actual = C.c_int()
            self._check(
                self._studio.FMOD_Studio_Bank_GetEventList(
                    bank, descriptions, count.value, C.byref(actual)
                ),
                f"list events in {bank_path.name}",
            )
            result: list[tuple[str, str]] = []
            for description in descriptions[: actual.value]:
                guid = Guid()
                self._check(
                    self._studio.FMOD_Studio_EventDescription_GetID(
                        description, C.byref(guid)
                    ),
                    f"read event GUID in {bank_path.name}",
                )
                guid_text = self._guid_text(guid)
                needed = C.c_int()
                # 65 is FMOD_ERR_TRUNCATED and is the documented size-query result.
                status = self._studio.FMOD_Studio_EventDescription_GetPath(
                    description, None, 0, C.byref(needed)
                )
                if status not in (0, 65):
                    fallback = guid_paths.get(guid_text)
                    if fallback is not None:
                        result.append((guid_text, fallback))
                    continue
                buffer = C.create_string_buffer(max(1, needed.value))
                status = self._studio.FMOD_Studio_EventDescription_GetPath(
                    description, buffer, len(buffer), C.byref(needed)
                )
                if status == 0:
                    result.append((guid_text, buffer.value.decode("utf-8", "replace")))
                else:
                    fallback = guid_paths.get(guid_text)
                    if fallback is not None:
                        result.append((guid_text, fallback))
            return tuple(sorted(result))
        finally:
            self._studio.FMOD_Studio_Bank_Unload(bank)

    def probe(self, bank_path: Path) -> tuple[str, ...]:
        """Return sorted event paths, preserving the original probe API."""

        return tuple(sorted(path for _guid, path in self.probe_events(bank_path)))

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._system:
            try:
                self._studio.FMOD_Studio_System_UnloadAll(self._system)
            except Exception:
                pass
            try:
                self._studio.FMOD_Studio_System_Release(self._system)
            except Exception:
                pass
            self._system = C.c_void_p()
        try:
            self._dll_cookie.close()
        except Exception:
            pass

    def __enter__(self) -> "SilentFmodBankProbe":
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        self.close()
