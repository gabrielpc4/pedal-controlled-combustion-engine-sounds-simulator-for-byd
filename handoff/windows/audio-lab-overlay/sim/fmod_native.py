"""Native FMOD Studio playback bridge.

The module deliberately loads the user's installed Kunos bank in place.  It
does not extract, convert, copy, or redistribute game audio.  Assetto Corsa's
car banks refer to a custom ``FMOD Distance Filter`` DSP.  The simulator
registers a descriptor compatible with that DSP so the original bank can be
loaded; for these sub-metre microphone positions the filter's distance curve
is at unity, while FMOD's original spatialiser and angle automation remain in
the event graph.
"""

from __future__ import annotations

import ctypes as C
import os
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

from .car_config import CarSpec
from .engine import EngineFrame


FMOD_VERSION = 0x00010812
FMOD_MACOS_VERSION = 0x00011011
FMOD_STUDIO_STOP_ALLOWFADEOUT = 0
FMOD_API_ROOT_ENV = "FMOD_API_ROOT"
ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP = 2048
ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET = 256


class FmodError(RuntimeError):
    """Raised when the installed FMOD runtime rejects an operation."""


class Vector(C.Structure):
    _fields_ = [("x", C.c_float), ("y", C.c_float), ("z", C.c_float)]


class Attributes3D(C.Structure):
    _fields_ = [
        ("position", Vector),
        ("velocity", Vector),
        ("forward", Vector),
        ("up", Vector),
    ]


class Guid(C.Structure):
    _fields_ = [
        ("data1", C.c_uint32),
        ("data2", C.c_uint16),
        ("data3", C.c_uint16),
        ("data4", C.c_ubyte * 8),
    ]

    @classmethod
    def parse(cls, value: str) -> "Guid":
        parsed = uuid.UUID(value.strip("{}"))
        result = cls()
        result.data1, result.data2, result.data3 = parsed.time_low, parsed.time_mid, parsed.time_hi_version
        result.data4[:] = parsed.bytes[8:]
        return result


class FloatMappingPiecewise(C.Structure):
    _fields_ = [
        ("numpoints", C.c_int),
        ("pointparamvalues", C.POINTER(C.c_float)),
        ("pointpositions", C.POINTER(C.c_float)),
    ]


class FloatMappingUnion(C.Union):
    _fields_ = [("piecewise", FloatMappingPiecewise)]


class FloatMapping(C.Structure):
    _fields_ = [("type", C.c_int), ("mapping", FloatMappingUnion)]


class FloatDescription(C.Structure):
    _fields_ = [
        ("minimum", C.c_float),
        ("maximum", C.c_float),
        ("default_value", C.c_float),
        ("mapping", FloatMapping),
    ]


class DataDescription(C.Structure):
    _fields_ = [("datatype", C.c_int)]


class ParameterDescriptionUnion(C.Union):
    _fields_ = [
        ("float_description", FloatDescription),
        ("data_description", DataDescription),
        ("padding", C.c_byte * C.sizeof(FloatDescription)),
    ]


class ParameterDescription(C.Structure):
    _fields_ = [
        ("type", C.c_int),
        ("name", C.c_char * 16),
        ("label", C.c_char * 16),
        ("description", C.c_char_p),
        ("detail", ParameterDescriptionUnion),
    ]


class DspDescription(C.Structure):
    _fields_ = [
        ("plugin_sdk_version", C.c_uint),
        ("name", C.c_char * 32),
        ("version", C.c_uint),
        ("input_buffers", C.c_int),
        ("output_buffers", C.c_int),
        ("create", C.c_void_p),
        ("release", C.c_void_p),
        ("reset", C.c_void_p),
        ("read", C.c_void_p),
        ("process", C.c_void_p),
        ("set_position", C.c_void_p),
        ("parameter_count", C.c_int),
        ("parameters", C.POINTER(C.POINTER(ParameterDescription))),
        ("set_float", C.c_void_p),
        ("set_int", C.c_void_p),
        ("set_bool", C.c_void_p),
        ("set_data", C.c_void_p),
        ("get_float", C.c_void_p),
        ("get_int", C.c_void_p),
        ("get_bool", C.c_void_p),
        ("get_data", C.c_void_p),
        ("should_process", C.c_void_p),
        ("user_data", C.c_void_p),
        ("sys_register", C.c_void_p),
        ("sys_deregister", C.c_void_p),
        ("sys_mix", C.c_void_p),
    ]


_CALLBACK = C.WINFUNCTYPE if os.name == "nt" else C.CFUNCTYPE

DSP_SET_PARAMETER_DATA_CALLBACK = _CALLBACK(
    C.c_int, C.c_void_p, C.c_int, C.c_void_p, C.c_uint
)
DSP_SET_PARAMETER_FLOAT_CALLBACK = _CALLBACK(
    C.c_int, C.c_void_p, C.c_int, C.c_float
)
DSP_SET_PARAMETER_BOOL_CALLBACK = _CALLBACK(
    C.c_int, C.c_void_p, C.c_int, C.c_int
)
DSP_CREATE_CALLBACK = _CALLBACK(C.c_int, C.c_void_p)
DSP_RELEASE_CALLBACK = _CALLBACK(C.c_int, C.c_void_p)
DSP_READ_CALLBACK = _CALLBACK(
    C.c_int,
    C.c_void_p,
    C.POINTER(C.c_float),
    C.POINTER(C.c_float),
    C.c_uint,
    C.c_int,
    C.POINTER(C.c_int),
)


def _distance_filter_description() -> tuple[DspDescription, tuple[object, ...]]:
    """Reproduce the descriptor serialized into the official AC bank."""

    max_distance = ParameterDescription()
    max_distance.type = 0
    max_distance.name = b"Max Dist"
    max_distance.label = b"m"
    max_distance.description = b"Distance at which the filter reaches its target frequency."
    max_distance.detail.float_description.minimum = 0.0
    max_distance.detail.float_description.maximum = 10000.0
    max_distance.detail.float_description.default_value = 100.0

    frequency = ParameterDescription()
    frequency.type = 0
    frequency.name = b"Frequency"
    frequency.label = b"Hz"
    frequency.description = b"Low-pass target frequency at maximum distance."
    frequency.detail.float_description.minimum = 10.0
    frequency.detail.float_description.maximum = 22000.0
    frequency.detail.float_description.default_value = 1000.0

    attributes = ParameterDescription()
    attributes.type = 3
    attributes.name = b"3D Attributes"
    attributes.description = b"Source and listener transforms supplied by FMOD."
    attributes.detail.data_description.datatype = -2

    parameters = (C.POINTER(ParameterDescription) * 3)(
        C.pointer(max_distance), C.pointer(frequency), C.pointer(attributes)
    )
    description = DspDescription()
    # The plugin API number used by Assetto Corsa's FMOD Studio 1.08.12.
    description.plugin_sdk_version = 105
    description.name = b"FMOD Distance Filter"
    description.version = 0x00010000
    description.input_buffers = 1
    description.output_buffers = 1
    description.parameter_count = 3
    description.parameters = parameters

    # The exterior event supplies its live source/listener transform through
    # this data parameter. FMOD leaves the event in STARTING until the plug-in
    # accepts it. At our three sub-metre listener positions the serialized
    # long-range filter is at its open near-field endpoint, so the compatible
    # shim accepts the transform while FMOD passes audio through unchanged.
    @DSP_SET_PARAMETER_DATA_CALLBACK
    def accept_3d_attributes(
        _state: int, _index: int, _data: int, _length: int
    ) -> int:
        return 0

    description.set_data = C.cast(accept_3d_attributes, C.c_void_p)
    return description, (
        max_distance,
        frequency,
        attributes,
        parameters,
        accept_3d_attributes,
    )


def _gain_description() -> tuple[DspDescription, tuple[object, ...]]:
    """Compatibility descriptor required by the official M3 E30 Gr.A bank.

    That one bank serializes the stock Studio ``FMOD Gain`` effect as a plug-in
    dependency even though AC's 1.08 runtime does not register it itself.  The
    bank authors -0.5 dB and non-inverted output, so this descriptor implements
    the serialized two-parameter ABI and applies that gain during offline
    reference capture instead of silently bypassing the dependency.
    """

    gain = ParameterDescription()
    gain.type = 0
    gain.name = b"Gain"
    gain.label = b"dB"
    gain.description = b"Linear output gain in decibels."
    gain.detail.float_description.minimum = -80.0
    gain.detail.float_description.maximum = 10.0
    gain.detail.float_description.default_value = 0.0

    invert = ParameterDescription()
    invert.type = 2
    invert.name = b"Invert"
    invert.description = b"Invert the output polarity."

    parameters = (C.POINTER(ParameterDescription) * 2)(
        C.pointer(gain), C.pointer(invert)
    )
    description = DspDescription()
    description.plugin_sdk_version = 105
    description.name = b"FMOD Gain"
    description.version = 0x00010000
    description.input_buffers = 1
    description.output_buffers = 1
    description.parameter_count = 2
    description.parameters = parameters
    observations: dict[str, list[tuple[int, float | bool]]] = {
        "float": [],
        "bool": [],
    }
    states: dict[int, tuple[float, bool]] = {}

    @DSP_CREATE_CALLBACK
    def create(state: int) -> int:
        states[int(state or 0)] = (0.0, False)
        return 0

    @DSP_RELEASE_CALLBACK
    def release(state: int) -> int:
        states.pop(int(state or 0), None)
        return 0

    @DSP_SET_PARAMETER_FLOAT_CALLBACK
    def observe_float(_state: int, index: int, value: float) -> int:
        observations["float"].append((int(index), float(value)))
        key = int(_state or 0)
        _old_gain, old_invert = states.get(key, (0.0, False))
        states[key] = (float(value), old_invert)
        return 0

    @DSP_SET_PARAMETER_BOOL_CALLBACK
    def observe_bool(_state: int, index: int, value: int) -> int:
        observations["bool"].append((int(index), bool(value)))
        key = int(_state or 0)
        old_gain, _old_invert = states.get(key, (0.0, False))
        states[key] = (old_gain, bool(value))
        return 0

    @DSP_READ_CALLBACK
    def process(
        state: int,
        input_buffer: C.POINTER(C.c_float),
        output_buffer: C.POINTER(C.c_float),
        length: int,
        input_channels: int,
        output_channels: C.POINTER(C.c_int),
    ) -> int:
        channels = max(0, int(input_channels))
        if output_channels:
            output_channels[0] = channels
        gain_db, invert_output = states.get(int(state or 0), (0.0, False))
        scale = (10.0 ** (gain_db / 20.0)) * (-1.0 if invert_output else 1.0)
        count = int(length) * channels
        if abs(scale - 1.0) <= 1.0e-12:
            C.memmove(output_buffer, input_buffer, count * C.sizeof(C.c_float))
        else:
            for sample_index in range(count):
                output_buffer[sample_index] = input_buffer[sample_index] * scale
        return 0

    description.create = C.cast(create, C.c_void_p)
    description.release = C.cast(release, C.c_void_p)
    description.read = C.cast(process, C.c_void_p)
    description.set_float = C.cast(observe_float, C.c_void_p)
    description.set_bool = C.cast(observe_bool, C.c_void_p)
    return description, (
        gain,
        invert,
        parameters,
        create,
        release,
        process,
        observe_float,
        observe_bool,
        states,
        observations,
    )


TATUUS_EVENT_GUIDS = {
    "engine_int": "{b0b4d0ad-e7f6-43d2-bb85-ce578a596b96}",
    "engine_ext": "{5fdbe5d7-17e3-4465-8eb3-8907d258c70a}",
    "gear_int": "{1d030c53-1d77-44e8-8586-b6fe5a5dc5dd}",
    "gear_ext": "{79068e60-2e68-4071-9b16-109ebe9b3393}",
    "transmission": "{089793a6-e7a3-4262-806d-4ff0281406f8}",
    "turbo": "{c76e2923-cf98-425c-9c4e-22a20ac0891f}",
    "limiter": "{08efac47-cc16-4a10-b73e-fed5a2bf9320}",
    "backfire_int": "{b3bfdbea-1234-4187-a7ae-7d4cdd7733d9}",
    "backfire_ext": "{431fb779-148d-4b16-aaab-52eeb2c65640}",
}

CORE_EVENT_NAMES = frozenset(
    (
        *TATUUS_EVENT_GUIDS,
        "start",
        "transmission_ext",
        "tractioncontrol_int",
        "tractioncontrol_ext",
        "gear_grind",
    )
)
ENGINE_OUTPUT_SCALE = 0.5
EFFECT_OUTPUT_SCALE = 1.0
ENGINE_EVENTS = frozenset(("engine_int", "engine_ext"))
ENGINE_ISOLATION_EVENTS = frozenset(
    (*ENGINE_EVENTS, "transmission", "transmission_ext")
)
# The lab intentionally auditions the engine and transmission graphs at their
# authored full-load endpoint. The pedal still drives vehicle dynamics and RPM.
FULL_LOAD_AUDIO_THROTTLE = 1.0
# Kunos backfire automation is inverse to the engine load lanes: its loud end
# is the valid low-positive trigger region, while throttle=1 is heavily
# attenuated. One percent is effectively the authored 0 dB endpoint and still
# represents a naturally valid post-lift sample.
MAX_BACKFIRE_AUDIO_THROTTLE = 0.01


def event_output_gain(
    name: str,
    master: float,
    *,
    muted: bool = False,
    engine_muted: bool = False,
) -> float:
    """Apply the lab mix after the bank's authored event automation.

    Effects retain unity host gain. Only the two continuous engine events are
    attenuated; the isolation control continues to silence engine and
    transmission together without suppressing shifts, turbo or backfires.
    """
    if muted or (engine_muted and name in ENGINE_ISOLATION_EVENTS):
        return 0.0
    scale = ENGINE_OUTPUT_SCALE if name in ENGINE_EVENTS else EFFECT_OUTPUT_SCALE
    return max(0.0, min(1.0, master)) * scale


@dataclass(frozen=True)
class AudioStatus:
    available: bool
    backend: str
    bank: str
    detail: str
    camera: str
    muted: bool
    engine_muted: bool
    volume: float
    events: tuple[str, ...] = ()


def _attributes(position: tuple[float, float, float], *, forward_z: float = -1.0) -> Attributes3D:
    # AudioEvent::set3DAttributes in AC 1.16.4 copies the car transform's up
    # axis unchanged but negates all three components of its Z axis before
    # writing FMOD_3D_ATTRIBUTES.forward (acs.exe 0x1401FB3F1-0x1401FB3FF).
    # Keeping +Z here reverses authored event cones and puts a bonnet listener
    # behind their low-pass lobe, which was especially obvious on the Supra.
    return Attributes3D(
        Vector(*position),
        Vector(0.0, 0.0, 0.0),
        Vector(0.0, 0.0, forward_z),
        Vector(0.0, 1.0, 0.0),
    )


class NativeFmodAudio:
    """Drive an authored FMOD engine graph through its matching local runtime."""

    cameras = ("cockpit", "bonnet", "exhaust")

    def __init__(
        self,
        assetto_root: Path,
        spec: CarSpec,
        *,
        bank_path: Path | None = None,
        initial_camera: str = "cockpit",
    ):
        self.assetto_root = assetto_root.resolve()
        self.spec = spec
        self.common_bank_path = self.assetto_root / "content" / "sfx" / "common.bank"
        self.strings_bank_path = self.assetto_root / "content" / "sfx" / "common.strings.bank"
        sfx_directory = self.assetto_root / "content" / "cars" / spec.car_id / "sfx"
        preferred_bank = sfx_directory / f"{spec.car_id}.bank"
        banks = sorted(sfx_directory.glob("*.bank")) if sfx_directory.is_dir() else []
        self.bank_path = (
            bank_path.expanduser().resolve()
            if bank_path is not None
            else (preferred_bank if preferred_bank.is_file() else (banks[0] if banks else preferred_bank))
        )
        for required in (*self._runtime_paths(), self.common_bank_path, self.strings_bank_path, self.bank_path):
            if not required.is_file():
                raise FileNotFoundError(required)

        if initial_camera not in self.cameras:
            raise ValueError(f"unknown camera {initial_camera!r}")
        self.camera = initial_camera
        self.muted = False
        self.engine_muted = False
        self.volume = 1.0
        self._limiter_decay = 10.0
        self._limiter_running = False
        self._traction_decay = 10.0
        self._bov_decay = 10.0
        self._closed = False
        self._system = C.c_void_p()
        self._low_level = C.c_void_p()
        self._common_bank = C.c_void_p()
        self._strings_bank = C.c_void_p()
        self._bank = C.c_void_p()
        self._events: dict[str, C.c_void_p] = {}
        self._instances: dict[str, C.c_void_p] = {}
        self._dll_cookie = None
        self._core, self._studio, self._runtime_version, self._runtime_label = self._load_runtime()
        self._bind()
        self._initialize()

    @staticmethod
    def _macos_api_root() -> Path:
        configured = os.environ.get(FMOD_API_ROOT_ENV)
        if configured:
            return Path(configured).expanduser().resolve()
        return Path.home() / "Downloads" / "FMOD Programmers API" / "api"

    def _runtime_paths(self) -> tuple[Path, Path]:
        if os.name == "nt":
            return self.assetto_root / "fmod64.dll", self.assetto_root / "fmodstudio64.dll"
        if sys.platform == "darwin":
            api_root = self._macos_api_root()
            return api_root / "lowlevel" / "lib" / "libfmod.dylib", api_root / "studio" / "lib" / "libfmodstudio.dylib"
        raise FmodError(f"FMOD playback is not configured for {sys.platform}")

    def _load_runtime(self) -> tuple[object, object, int, str]:
        core_path, studio_path = self._runtime_paths()
        if os.name == "nt":
            self._dll_cookie = os.add_dll_directory(str(self.assetto_root))
            return (
                C.WinDLL(str(core_path)),
                C.WinDLL(str(studio_path)),
                FMOD_VERSION,
                "FMOD Studio 1.08.12 / native Kunos runtime",
            )
        if sys.platform == "darwin":
            core = C.CDLL(str(core_path), mode=C.RTLD_GLOBAL)
            return (
                core,
                C.CDLL(str(studio_path)),
                FMOD_MACOS_VERSION,
                "FMOD Studio 1.10.11 / macOS API runtime",
            )
        raise FmodError(f"FMOD playback is not configured for {sys.platform}")

    def _bind(self) -> None:
        api = self._studio
        signatures = {
            "FMOD_Studio_System_Create": ([C.POINTER(C.c_void_p), C.c_uint], C.c_int),
            "FMOD_Studio_System_GetLowLevelSystem": ([C.c_void_p, C.POINTER(C.c_void_p)], C.c_int),
            "FMOD_Studio_System_Initialize": ([C.c_void_p, C.c_int, C.c_uint, C.c_uint, C.c_void_p], C.c_int),
            "FMOD_Studio_System_RegisterPlugin": ([C.c_void_p, C.POINTER(DspDescription)], C.c_int),
            "FMOD_Studio_System_LoadBankFile": ([C.c_void_p, C.c_char_p, C.c_uint, C.POINTER(C.c_void_p)], C.c_int),
            "FMOD_Studio_System_GetEventByID": ([C.c_void_p, C.POINTER(Guid), C.POINTER(C.c_void_p)], C.c_int),
            "FMOD_Studio_Bank_GetEventCount": ([C.c_void_p, C.POINTER(C.c_int)], C.c_int),
            "FMOD_Studio_Bank_GetEventList": ([C.c_void_p, C.POINTER(C.c_void_p), C.c_int, C.POINTER(C.c_int)], C.c_int),
            "FMOD_Studio_EventDescription_GetPath": ([C.c_void_p, C.c_char_p, C.c_int, C.POINTER(C.c_int)], C.c_int),
            "FMOD_Studio_System_SetListenerAttributes": ([C.c_void_p, C.c_int, C.POINTER(Attributes3D)], C.c_int),
            "FMOD_Studio_System_Update": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_UnloadAll": ([C.c_void_p], C.c_int),
            "FMOD_Studio_System_Release": ([C.c_void_p], C.c_int),
            "FMOD_Studio_EventDescription_CreateInstance": ([C.c_void_p, C.POINTER(C.c_void_p)], C.c_int),
            "FMOD_Studio_EventInstance_SetParameterValue": ([C.c_void_p, C.c_char_p, C.c_float], C.c_int),
            "FMOD_Studio_EventInstance_Set3DAttributes": ([C.c_void_p, C.POINTER(Attributes3D)], C.c_int),
            "FMOD_Studio_EventInstance_SetVolume": ([C.c_void_p, C.c_float], C.c_int),
            "FMOD_Studio_EventInstance_SetTimelinePosition": ([C.c_void_p, C.c_int], C.c_int),
            "FMOD_Studio_EventInstance_GetPlaybackState": ([C.c_void_p, C.POINTER(C.c_int)], C.c_int),
            "FMOD_Studio_EventInstance_Start": ([C.c_void_p], C.c_int),
            "FMOD_Studio_EventInstance_Stop": ([C.c_void_p, C.c_int], C.c_int),
            "FMOD_Studio_EventInstance_Release": ([C.c_void_p], C.c_int),
        }
        for name, (argtypes, restype) in signatures.items():
            function = getattr(api, name)
            function.argtypes = argtypes
            function.restype = restype
        self._core.FMOD_System_SetSoftwareChannels.argtypes = [C.c_void_p, C.c_int]
        self._core.FMOD_System_SetSoftwareChannels.restype = C.c_int

    @staticmethod
    def _check(result: int, operation: str) -> None:
        if result:
            raise FmodError(f"{operation}: FMOD result {result}")

    def _initialize(self) -> None:
        api = self._studio
        self._check(api.FMOD_Studio_System_Create(C.byref(self._system), self._runtime_version), "create Studio system")
        try:
            self._check(
                api.FMOD_Studio_System_GetLowLevelSystem(
                    self._system, C.byref(self._low_level)
                ),
                "get Studio low-level system",
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
                "initialize Studio system",
            )
            self._dsp_description, self._dsp_keepalive = _distance_filter_description()
            self._check(
                api.FMOD_Studio_System_RegisterPlugin(self._system, C.byref(self._dsp_description)),
                "register AC distance filter descriptor",
            )
            self._gain_description, self._gain_keepalive = _gain_description()
            self._check(
                api.FMOD_Studio_System_RegisterPlugin(
                    self._system, C.byref(self._gain_description)
                ),
                "register M3 E30 Gr.A gain descriptor",
            )
            self._check(
                api.FMOD_Studio_System_LoadBankFile(
                    self._system,
                    str(self.strings_bank_path).encode("utf-8"),
                    0,
                    C.byref(self._strings_bank),
                ),
                "load strings bank",
            )
            self._check(
                api.FMOD_Studio_System_LoadBankFile(
                    self._system,
                    str(self.common_bank_path).encode("utf-8"),
                    0,
                    C.byref(self._common_bank),
                ),
                "load common bank",
            )
            self._check(
                api.FMOD_Studio_System_LoadBankFile(
                    self._system, str(self.bank_path).encode("utf-8"), 0, C.byref(self._bank)
                ),
                f"load {self.spec.car_id} bank",
            )
            count = C.c_int()
            self._check(api.FMOD_Studio_Bank_GetEventCount(self._bank, C.byref(count)), "count bank events")
            descriptions = (C.c_void_p * count.value)()
            actual = C.c_int()
            self._check(api.FMOD_Studio_Bank_GetEventList(self._bank, descriptions, count.value, C.byref(actual)), "list bank events")
            for description in descriptions[: actual.value]:
                needed = C.c_int()
                api.FMOD_Studio_EventDescription_GetPath(description, None, 0, C.byref(needed))
                buffer = C.create_string_buffer(max(needed.value, 256))
                result = api.FMOD_Studio_EventDescription_GetPath(description, buffer, len(buffer), C.byref(needed))
                if result:
                    continue
                name = buffer.value.decode("utf-8", errors="replace").rsplit("/", 1)[-1].casefold()
                if name in CORE_EVENT_NAMES:
                    self._events[name] = C.c_void_p(description)
            # Third-party banks often ship without a strings bank but include
            # FMOD Studio's GUIDs.txt. It is authoritative for the same paths.
            guid_file = self.bank_path.parent / "GUIDs.txt"
            if guid_file.is_file():
                for line in guid_file.read_text(encoding="utf-8-sig", errors="replace").splitlines():
                    parts = line.split(None, 1)
                    if len(parts) != 2 or not parts[1].startswith("event:/"):
                        continue
                    name = parts[1].rsplit("/", 1)[-1].casefold()
                    if name not in CORE_EVENT_NAMES or name in self._events:
                        continue
                    description = C.c_void_p()
                    guid = Guid.parse(parts[0])
                    if not api.FMOD_Studio_System_GetEventByID(self._system, C.byref(guid), C.byref(description)):
                        self._events[name] = description
            if not {"engine_int", "engine_ext"}.issubset(self._events):
                raise FmodError("bank has no standard engine_int/engine_ext event pair")

            for name in self._events:
                self._instances[name] = self._create_instance(name)

            emitter = self._emitter_attributes()
            for name in (
                "engine_int",
                "engine_ext",
                "gear_int",
                "gear_ext",
                "gear_grind",
                "transmission",
                "transmission_ext",
                "turbo",
                "limiter",
                "start",
                "tractioncontrol_int",
                "tractioncontrol_ext",
            ):
                if name not in self._instances:
                    continue
                self._check(
                    api.FMOD_Studio_EventInstance_Set3DAttributes(self._instances[name], C.byref(emitter)),
                    f"place {name}",
                )
            backfire_emitter = self._backfire_attributes()
            for name in ("backfire_int", "backfire_ext"):
                if name not in self._instances:
                    continue
                self._check(
                    api.FMOD_Studio_EventInstance_Set3DAttributes(
                        self._instances[name], C.byref(backfire_emitter)
                    ),
                    f"place {name}",
                )
            self._set_listener()
            for name in ("engine_int", "engine_ext"):
                self._set_parameter(self._instances[name], "rpms", self.spec.idle_rpm)
                self._set_parameter(
                    self._instances[name], "throttle", FULL_LOAD_AUDIO_THROTTLE
                )
            for name in ("backfire_int", "backfire_ext"):
                if name not in self._instances:
                    continue
                self._set_parameter(self._instances[name], "throttle", 0.0)
            for name in ("gear_int", "gear_ext"):
                if name not in self._instances:
                    continue
                self._set_parameter(self._instances[name], "state", 1.0)
            for name in ("transmission", "transmission_ext"):
                if name not in self._instances:
                    continue
                self._set_parameter(self._instances[name], "drivetrain_speed", 0.0)
                self._set_parameter(
                    self._instances[name],
                    "throttle",
                    FULL_LOAD_AUDIO_THROTTLE,
                )
            for name in ("tractioncontrol_int", "tractioncontrol_ext"):
                if name in self._instances:
                    self._set_parameter(
                        self._instances[name], "decay", self._traction_decay
                    )
            if "limiter" in self._instances:
                self._set_parameter(self._instances["limiter"], "decay", self._limiter_decay)
            initial_transmission = self._perspective_event(
                "transmission", "transmission_ext"
            )
            for name in ("engine_int", initial_transmission, "turbo", "limiter"):
                if name is None:
                    continue
                if name in self._instances and (name != "turbo" or self.spec.turbo is not None):
                    self._start_event(name)
            if "start" in self._instances:
                # One NativeFmodAudio object is one profile audio session.  The
                # authored ignition event starts exactly once here and is never
                # replayed by camera, pedal, RPM, or transmission changes.
                self._start_event("start")
            self._limiter_running = "limiter" in self._instances
            self._apply_gains()
            self._check(api.FMOD_Studio_System_Update(self._system), "initial audio update")
        except Exception:
            self.close()
            raise

    def _create_instance(self, name: str) -> C.c_void_p:
        instance = C.c_void_p()
        self._check(
            self._studio.FMOD_Studio_EventDescription_CreateInstance(self._events[name], C.byref(instance)),
            f"create {name}",
        )
        return instance

    def _set_parameter(self, instance: C.c_void_p, name: str, value: float) -> None:
        self._check(
            self._studio.FMOD_Studio_EventInstance_SetParameterValue(instance, name.encode("ascii"), float(value)),
            f"set {name}",
        )

    def _start_event(self, name: str) -> None:
        """Match AudioEvent::start: rewind the authored timeline, then start."""

        instance = self._instances[name]
        self._check(
            self._studio.FMOD_Studio_EventInstance_SetTimelinePosition(instance, 0),
            f"rewind {name}",
        )
        self._check(
            self._studio.FMOD_Studio_EventInstance_Start(instance),
            f"start {name}",
        )

    def _event_is_playing(self, name: str) -> bool:
        state = C.c_int()
        self._check(
            self._studio.FMOD_Studio_EventInstance_GetPlaybackState(
                self._instances[name], C.byref(state)
            ),
            f"query {name}",
        )
        # AudioEvent::isPlaying accepts PLAYING, SUSTAINING and STARTING, but
        # deliberately regards STOPPING as no longer playing.
        return state.value in (0, 1, 3)

    def _emitter_attributes(self) -> Attributes3D:
        # AC's POSITION=rear convention anchors the event 0.5 m ahead of the
        # rear axle.  Front weight fraction * wheelbase is CG-to-rear-axle.
        if self.spec.engine_position == "rear":
            z = -(self.spec.wheelbase * self.spec.cg_location) + 0.5
            y = self.spec.rear_wheel_radius
        elif self.spec.engine_position == "front":
            z = self.spec.wheelbase * (1.0 - self.spec.cg_location)
            y = self.spec.front_wheel_radius
        else:
            z = 0.0
            y = 0.5 * (self.spec.front_wheel_radius + self.spec.rear_wheel_radius)
        return _attributes((0.0, y, z))

    def _backfire_attributes(self) -> Attributes3D:
        rear_axle_z = -(self.spec.wheelbase * self.spec.cg_location)
        # AC positions backfires half a metre behind the rear-wheel midpoint.
        return _attributes((0.0, self.spec.rear_wheel_radius, rear_axle_z - 0.5))

    def _listener_attributes(self) -> Attributes3D:
        if self.camera == "cockpit":
            return _attributes(self.spec.driver_eyes)
        if self.camera == "bonnet":
            return _attributes(self.spec.bonnet_camera)
        rear_axle_z = -(self.spec.wheelbase * self.spec.cg_location)
        # The third lab state is a tailpipe listener, so it follows the rear
        # axle rather than the engine anchor (critical for front-engine cars).
        return _attributes((0.0, self.spec.rear_wheel_radius + 0.025, rear_axle_z - 0.82))

    def _set_listener(self) -> None:
        attributes = self._listener_attributes()
        self._check(
            self._studio.FMOD_Studio_System_SetListenerAttributes(
                self._system, 0, C.byref(attributes)
            ),
            "place listener",
        )

    def _apply_gains(self) -> None:
        # Keep every effect at unity host gain relative to the master while
        # attenuating only engine_int/engine_ext to half. The bank still owns
        # its internal mixer, automation and event-to-event authored balance.
        for name in self._instances:
            event_gain = event_output_gain(
                name,
                self.volume,
                muted=self.muted,
                engine_muted=self.engine_muted,
            )
            self._check(
                self._studio.FMOD_Studio_EventInstance_SetVolume(
                    self._instances[name], event_gain
                ),
                f"set {name} gain",
            )

    def _perspective_event(
        self,
        cabin_name: str,
        exterior_name: str,
        *,
        camera: str | None = None,
    ) -> str | None:
        """Resolve an authored INT/EXT pair without fabricating an event.

        A car that supplies the exterior path gets it outside and the cabin
        path inside.  The standard unsuffixed transmission remains the exact
        fallback for cars whose bank does not author ``transmission_ext``.
        """

        selected_camera = self.camera if camera is None else camera
        preferred, fallback = (
            (cabin_name, exterior_name)
            if selected_camera == "cockpit"
            else (exterior_name, cabin_name)
        )
        if preferred in self._instances:
            return preferred
        if fallback in self._instances:
            return fallback
        return None

    def configure(self, *, camera: str | None = None, muted: bool | None = None, engine_muted: bool | None = None, volume: float | None = None) -> None:
        if camera is not None:
            if camera not in self.cameras:
                raise ValueError(f"unknown camera {camera!r}")
            if camera != self.camera:
                previous_camera = self.camera
                was_external = self.camera != "cockpit"
                will_be_external = camera != "cockpit"
                self.camera = camera
                self._set_listener()
                if was_external != will_be_external:
                    excluded = "engine_int" if will_be_external else "engine_ext"
                    selected = "engine_ext" if will_be_external else "engine_int"
                    self._check(
                        self._studio.FMOD_Studio_EventInstance_Stop(
                            self._instances[excluded], FMOD_STUDIO_STOP_ALLOWFADEOUT
                        ),
                        f"fade out {excluded}",
                    )
                    self._start_event(selected)
                    old_transmission = self._perspective_event(
                        "transmission",
                        "transmission_ext",
                        camera=previous_camera,
                    )
                    new_transmission = self._perspective_event(
                        "transmission", "transmission_ext"
                    )
                    if old_transmission != new_transmission:
                        if old_transmission is not None:
                            self._check(
                                self._studio.FMOD_Studio_EventInstance_Stop(
                                    self._instances[old_transmission],
                                    FMOD_STUDIO_STOP_ALLOWFADEOUT,
                                ),
                                f"fade out {old_transmission}",
                            )
                        if new_transmission is not None:
                            self._start_event(new_transmission)

                    old_traction = self._perspective_event(
                        "tractioncontrol_int",
                        "tractioncontrol_ext",
                        camera=previous_camera,
                    )
                    new_traction = self._perspective_event(
                        "tractioncontrol_int", "tractioncontrol_ext"
                    )
                    if old_traction != new_traction and old_traction is not None:
                        old_traction_playing = self._event_is_playing(old_traction)
                        if old_traction_playing:
                            self._check(
                                self._studio.FMOD_Studio_EventInstance_Stop(
                                    self._instances[old_traction],
                                    FMOD_STUDIO_STOP_ALLOWFADEOUT,
                                ),
                                f"fade out {old_traction}",
                            )
                            if new_traction is not None:
                                self._start_event(new_traction)
        if muted is not None:
            self.muted = bool(muted)
        if engine_muted is not None:
            self.engine_muted = bool(engine_muted)
        if volume is not None:
            self.volume = min(1.0, max(0.0, float(volume)))

    @staticmethod
    def _gear_shift_state(frame: EngineFrame) -> float | None:
        """Return the bank's state switch for one accepted shift request.

        ``shift_started`` (or its backwards-compatible ``gear_changed``
        alias) is a one-audio-update pulse. ``gear_state`` is the exact FMOD
        switch, 0=down and 1=up. ``gear_direction`` may instead be ``down`` /
        ``up`` (or a signed -1 / +1). The eventual neutral-to-target ratio
        engagement must not emit a second pulse.
        """

        pulse = bool(
            getattr(frame, "shift_started", getattr(frame, "gear_changed", False))
        )
        if not pulse:
            return None

        state = getattr(frame, "gear_state", None)
        if state is not None:
            try:
                return 1.0 if float(state) >= 0.5 else 0.0
            except (TypeError, ValueError):
                pass

        # Optional exact AC enum payload: UpShift=0, DownShift=1. This is kept
        # separate because signed application-level direction uses +1 for up.
        request_direction = getattr(frame, "gear_request_direction", None)
        if request_direction is not None:
            enum_name = getattr(request_direction, "name", "")
            if enum_name:
                request_direction = enum_name
            if isinstance(request_direction, str):
                lowered = request_direction.casefold().replace("_", "")
                if "down" in lowered:
                    return 0.0
                if "up" in lowered:
                    return 1.0
            try:
                return 1.0 if int(request_direction) == 0 else 0.0
            except (TypeError, ValueError):
                pass

        direction = getattr(frame, "gear_direction", None)
        direction_name = getattr(direction, "name", direction)
        if isinstance(direction_name, str):
            lowered = direction_name.casefold().replace("_", "")
            if "down" in lowered:
                return 0.0
            if "up" in lowered:
                return 1.0
        try:
            signed_direction = float(direction_name)
        except (TypeError, ValueError):
            return None
        if signed_direction < 0.0:
            return 0.0
        if signed_direction > 0.0:
            return 1.0
        return None

    def update(self, frame: EngineFrame, dt: float, *, audition_backfire: bool = False) -> None:
        if self._closed:
            return
        # AC feeds both instances even while one is stopped. Camera changes
        # start the selected graph and stop the other with the event's authored
        # ALLOWFADEOUT behavior; bonnet-to-exhaust only moves the listener.
        for name in ("engine_int", "engine_ext"):
            instance = self._instances[name]
            self._set_parameter(instance, "rpms", max(1.0, frame.rpm))
            self._set_parameter(instance, "throttle", FULL_LOAD_AUDIO_THROTTLE)
        for name in ("backfire_int", "backfire_ext"):
            if name in self._instances:
                # Backfire events use inverse throttle automation. Keep them
                # at their loudest valid low-gas endpoint for both natural and
                # explicit triggers; this does not alter the trigger detector.
                self._set_parameter(
                    self._instances[name],
                    "throttle",
                    MAX_BACKFIRE_AUDIO_THROTTLE,
                )

        # CarAudioFMOD passes the drivetrain element's angular velocity
        # straight through. It is rad/s, signed, and is neither wheel speed nor
        # km/h. Older EngineFrame producers omit it and remain silent here.
        for name in ("transmission", "transmission_ext"):
            transmission = self._instances.get(name)
            if transmission is None:
                continue
            self._set_parameter(
                transmission,
                "drivetrain_speed",
                float(getattr(frame, "drivetrain_speed", 0.0)),
            )
            self._set_parameter(
                transmission, "throttle", FULL_LOAD_AUDIO_THROTTLE
            )

        shift_state = self._gear_shift_state(frame)
        if shift_state is not None:
            # This callback occurs when the shift request is accepted (the
            # drivetrain's OnGearRequest), not when the target ratio engages.
            selected_gear = "gear_int" if self.camera == "cockpit" else "gear_ext"
            if selected_gear in self._instances and not self._event_is_playing(selected_gear):
                self._check(
                    self._studio.FMOD_Studio_EventInstance_Stop(
                        self._instances[selected_gear], FMOD_STUDIO_STOP_ALLOWFADEOUT
                    ),
                    f"fade out {selected_gear}",
                )
                self._set_parameter(self._instances[selected_gear], "state", shift_state)
                self._start_event(selected_gear)

        if bool(getattr(frame, "shift_rejected", False)):
            gear_grind = self._instances.get("gear_grind")
            if gear_grind is not None and not self._event_is_playing("gear_grind"):
                self._start_event("gear_grind")

        traction_active = bool(getattr(frame, "traction_limit_active", False))
        traction_pulse = bool(getattr(frame, "traction_limit_pulse", False))
        if traction_active or traction_pulse:
            self._traction_decay = 0.0
        else:
            self._traction_decay = min(10.0, self._traction_decay + dt)
        for name in ("tractioncontrol_int", "tractioncontrol_ext"):
            if name in self._instances:
                self._set_parameter(
                    self._instances[name], "decay", self._traction_decay
                )
        selected_traction = self._perspective_event(
            "tractioncontrol_int", "tractioncontrol_ext"
        )
        if (
            (traction_active or traction_pulse)
            and selected_traction is not None
            and not self._event_is_playing(selected_traction)
        ):
            self._start_event(selected_traction)

        turbo = self._instances.get("turbo")
        if turbo is not None and self.spec.turbo is not None:
            maximum_boost = self.spec.turbo.maximum_boost
            self._set_parameter(turbo, "boost", frame.boost / max(0.001, maximum_boost))
            self._set_parameter(turbo, "bov", frame.bov)
            self._bov_decay = 0.0 if frame.bov > 0.0 else self._bov_decay + dt
            self._set_parameter(turbo, "bov_decay", self._bov_decay)

        # AC keeps one limiter event alive: decay is reset on a limiter pulse
        # and otherwise contains seconds since the most recent pulse. The
        # Tatuus bank's authored curve reaches -80 dB at 0.136874 seconds.
        self._limiter_decay += dt
        if frame.limiter_pulse:
            self._limiter_decay = 0.0
        limiter = self._instances.get("limiter")
        if limiter is not None:
            self._set_parameter(limiter, "decay", self._limiter_decay)
        if limiter is not None and self._limiter_decay <= 10.0 and not self._limiter_running:
            self._start_event("limiter")
            self._limiter_running = True
        elif limiter is not None and self._limiter_decay > 10.0 and self._limiter_running:
            self._check(
                self._studio.FMOD_Studio_EventInstance_Stop(
                    self._instances["limiter"], FMOD_STUDIO_STOP_ALLOWFADEOUT
                ),
                "fade out limiter",
            )
            self._limiter_running = False

        backfire_names = tuple(name for name in ("backfire_int", "backfire_ext") if name in self._instances)
        if (frame.backfire_triggered or audition_backfire) and backfire_names and not any(self._event_is_playing(name) for name in backfire_names):
            # AC owns one wrapper/instance for each event and retriggers the
            # selected bank timeline. VOLUME_IN/OUT/SCALE_OUT are legacy keys
            # ignored by the final executable; the bank owns the mix level.
            preferred = "backfire_int" if self.camera == "cockpit" else "backfire_ext"
            selected = preferred if preferred in self._instances else backfire_names[0]
            self._start_event(selected)

        self._apply_gains()
        self._check(self._studio.FMOD_Studio_System_Update(self._system), "audio update")

    def status(self) -> AudioStatus:
        return AudioStatus(
            available=not self._closed,
            backend=self._runtime_label,
            bank=str(self.bank_path),
            detail=(
                "Original core powertrain graphs available for this car are active: "
                f"{', '.join(sorted(self._instances))}. Engine and transmission "
                "audio is locked at full load; backfire is locked at its "
                "loudest valid low-throttle endpoint. Effects use unity host "
                "gain and the continuous engine events use 50% host gain."
            ),
            camera=self.camera,
            muted=self.muted,
            engine_muted=self.engine_muted,
            volume=self.volume,
            events=tuple(sorted(self._instances)),
        )

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        api = getattr(self, "_studio", None)
        if api is not None:
            for instance in list(getattr(self, "_instances", {}).values()):
                if instance:
                    api.FMOD_Studio_EventInstance_Stop(instance, FMOD_STUDIO_STOP_ALLOWFADEOUT)
                    api.FMOD_Studio_EventInstance_Release(instance)
            self._instances.clear()
            if self._system:
                api.FMOD_Studio_System_UnloadAll(self._system)
                api.FMOD_Studio_System_Release(self._system)
                self._system = C.c_void_p()
        cookie = getattr(self, "_dll_cookie", None)
        if cookie is not None:
            cookie.close()

    def __enter__(self) -> "NativeFmodAudio":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()
