"""Small diagnostic for the FMOD Studio runtime bundled with Assetto Corsa.

This never copies or modifies game files. It loads one car bank in-place and
prints the events that the runtime itself exposes.
"""

from __future__ import annotations

import ctypes as C
import os
import sys
from pathlib import Path


FMOD_VERSION = 0x00010812


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


DSP_SET_PARAMETER_DATA_CALLBACK = C.WINFUNCTYPE(
    C.c_int, C.c_void_p, C.c_int, C.c_void_p, C.c_uint
)


class Guid(C.Structure):
    _fields_ = [
        ("data1", C.c_uint32),
        ("data2", C.c_uint16),
        ("data3", C.c_uint16),
        ("data4", C.c_ubyte * 8),
    ]

    def __str__(self) -> str:
        tail = bytes(self.data4)
        return (
            f"{{{self.data1:08x}-{self.data2:04x}-{self.data3:04x}-"
            f"{tail[0]:02x}{tail[1]:02x}-{''.join(f'{byte:02x}' for byte in tail[2:])}}}"
        )


def distance_filter_description(plugin_sdk_version: int):
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
    description.plugin_sdk_version = plugin_sdk_version
    description.name = b"FMOD Distance Filter"
    description.version = 0x00010000
    description.input_buffers = 1
    description.output_buffers = 1
    description.parameter_count = 3
    description.parameters = parameters

    @DSP_SET_PARAMETER_DATA_CALLBACK
    def accept_3d_attributes(
        _state: int, _index: int, _data: int, _length: int
    ) -> int:
        return 0

    description.set_data = C.cast(accept_3d_attributes, C.c_void_p)
    # Keep every pointed-to object alive for the lifetime of the descriptor.
    return description, (
        max_distance,
        frequency,
        attributes,
        parameters,
        accept_3d_attributes,
    )


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: probe_fmod.py <assetto-root> <bank-file>")
        return 2

    root = Path(sys.argv[1]).resolve()
    bank_path = Path(sys.argv[2]).resolve()
    os.add_dll_directory(str(root))

    core = C.WinDLL(str(root / "fmod64.dll"))
    studio = C.WinDLL(str(root / "fmodstudio64.dll"))

    def check(result: int, operation: str) -> None:
        if result:
            raise RuntimeError(f"{operation}: FMOD result {result}")

    system = C.c_void_p()
    studio.FMOD_Studio_System_Create.argtypes = [C.POINTER(C.c_void_p), C.c_uint]
    studio.FMOD_Studio_System_Create.restype = C.c_int
    check(studio.FMOD_Studio_System_Create(C.byref(system), FMOD_VERSION), "create")

    studio.FMOD_Studio_System_Initialize.argtypes = [C.c_void_p, C.c_int, C.c_uint, C.c_uint, C.c_void_p]
    studio.FMOD_Studio_System_Initialize.restype = C.c_int
    check(studio.FMOD_Studio_System_Initialize(system, 256, 0, 0, None), "initialize")

    plugin_sdk_version = int(os.environ.get("FMOD_PLUGIN_SDK", "105"))
    dsp_description, keepalive = distance_filter_description(plugin_sdk_version)
    studio.FMOD_Studio_System_RegisterPlugin.argtypes = [C.c_void_p, C.POINTER(DspDescription)]
    studio.FMOD_Studio_System_RegisterPlugin.restype = C.c_int
    register_result = studio.FMOD_Studio_System_RegisterPlugin(
        system, C.byref(dsp_description)
    )
    print(
        f"distance-filter register sdk={plugin_sdk_version}: result={register_result}; "
        f"dsp-size={C.sizeof(DspDescription)} param-size={C.sizeof(ParameterDescription)}"
    )
    check(register_result, "register distance filter")

    common_bank = C.c_void_p()
    bank = C.c_void_p()
    studio.FMOD_Studio_System_LoadBankFile.argtypes = [C.c_void_p, C.c_char_p, C.c_uint, C.POINTER(C.c_void_p)]
    studio.FMOD_Studio_System_LoadBankFile.restype = C.c_int
    check(
        studio.FMOD_Studio_System_LoadBankFile(
            system,
            str(root / "content" / "sfx" / "common.bank").encode("utf-8"),
            0,
            C.byref(common_bank),
        ),
        "load common bank",
    )
    check(
        studio.FMOD_Studio_System_LoadBankFile(
            system, str(bank_path).encode("utf-8"), 0, C.byref(bank)
        ),
        "load bank",
    )

    studio.FMOD_Studio_Bank_GetEventCount.argtypes = [C.c_void_p, C.POINTER(C.c_int)]
    studio.FMOD_Studio_Bank_GetEventCount.restype = C.c_int
    count = C.c_int()
    check(studio.FMOD_Studio_Bank_GetEventCount(bank, C.byref(count)), "event count")
    print(f"events={count.value}")

    descriptions = (C.c_void_p * max(1, count.value))()
    written = C.c_int()
    studio.FMOD_Studio_Bank_GetEventList.argtypes = [
        C.c_void_p,
        C.POINTER(C.c_void_p),
        C.c_int,
        C.POINTER(C.c_int),
    ]
    studio.FMOD_Studio_Bank_GetEventList.restype = C.c_int
    check(
        studio.FMOD_Studio_Bank_GetEventList(
            bank, descriptions, count.value, C.byref(written)
        ),
        "event list",
    )

    studio.FMOD_Studio_EventDescription_GetPath.argtypes = [
        C.c_void_p,
        C.c_char_p,
        C.c_int,
        C.POINTER(C.c_int),
    ]
    studio.FMOD_Studio_EventDescription_GetPath.restype = C.c_int
    studio.FMOD_Studio_EventDescription_GetID.argtypes = [C.c_void_p, C.POINTER(Guid)]
    studio.FMOD_Studio_EventDescription_GetID.restype = C.c_int
    selected = None
    play_guid = os.environ.get("FMOD_PLAY_GUID", "").lower()
    for i in range(written.value):
        guid = Guid()
        check(studio.FMOD_Studio_EventDescription_GetID(descriptions[i], C.byref(guid)), f"id {i}")
        guid_text = str(guid)
        if play_guid and guid_text.lower() == play_guid:
            selected = descriptions[i]
        needed = C.c_int()
        result = studio.FMOD_Studio_EventDescription_GetPath(
            descriptions[i], None, 0, C.byref(needed)
        )
        path = "<path unavailable>"
        if result in (0, 65):  # FMOD_ERR_TRUNCATED is expected for size query.
            buf = C.create_string_buffer(max(1, needed.value))
            check(
                studio.FMOD_Studio_EventDescription_GetPath(
                    descriptions[i], buf, len(buf), C.byref(needed)
                ),
                f"path {i}",
            )
            path = buf.value.decode("utf-8", "replace")
        print(f"{i:02}: {guid_text} {path}")

    if play_guid:
        if selected is None:
            raise RuntimeError(f"event {play_guid} not found")
        instance = C.c_void_p()
        studio.FMOD_Studio_EventDescription_CreateInstance.argtypes = [
            C.c_void_p,
            C.POINTER(C.c_void_p),
        ]
        studio.FMOD_Studio_EventDescription_CreateInstance.restype = C.c_int
        check(
            studio.FMOD_Studio_EventDescription_CreateInstance(
                selected, C.byref(instance)
            ),
            "create instance",
        )
        studio.FMOD_Studio_EventInstance_SetParameterValue.argtypes = [
            C.c_void_p,
            C.c_char_p,
            C.c_float,
        ]
        studio.FMOD_Studio_EventInstance_SetParameterValue.restype = C.c_int
        for parameter_name, value in ((b"rpms", 3500.0), (b"throttle", 0.65)):
            result = studio.FMOD_Studio_EventInstance_SetParameterValue(
                instance, parameter_name, value
            )
            print(f"set {parameter_name.decode()}={value}: result={result}")
        studio.FMOD_Studio_EventInstance_Start.argtypes = [C.c_void_p]
        studio.FMOD_Studio_EventInstance_Start.restype = C.c_int
        check(studio.FMOD_Studio_EventInstance_Start(instance), "start instance")
        studio.FMOD_Studio_System_Update.argtypes = [C.c_void_p]
        studio.FMOD_Studio_System_Update.restype = C.c_int
        import time

        print("playing for 2 seconds...")
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            check(
                studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, b"rpms", 3500.0
                ),
                "live rpms update",
            )
            check(studio.FMOD_Studio_System_Update(system), "update")
            time.sleep(0.01)
        studio.FMOD_Studio_EventInstance_Stop.argtypes = [C.c_void_p, C.c_int]
        studio.FMOD_Studio_EventInstance_Stop(instance, 0)
        studio.FMOD_Studio_EventInstance_Release.argtypes = [C.c_void_p]
        studio.FMOD_Studio_EventInstance_Release(instance)

    studio.FMOD_Studio_System_UnloadAll.argtypes = [C.c_void_p]
    studio.FMOD_Studio_System_Release.argtypes = [C.c_void_p]
    studio.FMOD_Studio_System_UnloadAll(system)
    studio.FMOD_Studio_System_Release(system)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
