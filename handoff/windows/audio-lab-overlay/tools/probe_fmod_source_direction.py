"""Silently resolve pedal-direction semantics for ambiguous FMOD one-shots.

The probe never opens an audio device.  It creates an exact temporary copy of
the car bank for each target, disables every other waveform reachable from the
target event, and observes FMOD 1.08.12 ``SOUND_PLAYED`` callbacks while the
pedal parameter crosses the authored region in each direction.  Embedded
sample names are used only to verify runtime identity inside the target-only
copy; they are neither emitted nor used to assign a semantic role.
"""

from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_bank_isolation import create_isolated_bank_copy
from sim.fmod_native import (
    FMOD_VERSION,
    FmodError,
    _attributes,
    _distance_filter_description,
    _gain_description,
)
from sim.fmod_renderer import (
    EventCallback,
    FMOD_OUTPUTTYPE_WAVWRITER_NRT,
    FMOD_SPEAKERMODE_STEREO,
    FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED,
    FMOD_STUDIO_STOP_IMMEDIATE,
    SilentFmodReferenceRenderer,
)


SCHEMA = "ac-fmod-source-direction-oracle-v1"
BACKLOG_SCHEMA = "ac-fmod-source-role-manual-oracle-backlog-v1"
CATALOG_GRAPH_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
FAMILY_GRAPH_SCHEMA = "ac-fmod-bank-graph-audit-v3"
DEFAULT_GRAPH_ROOT = PROJECT_ROOT / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_BACKLOG = (
    PROJECT_ROOT / ".aclib-local" / "source-role-manual-oracle-backlog-v1.json"
)
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / ".aclib-local" / "fmod-source-direction-v1"


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_canonical(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    temporary.replace(path)


def _read_document(path: Path, schema: str) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema") != schema:
        raise ValueError(f"{path} is not a {schema} document")
    return document


def _direction(counts_up: list[int], counts_down: list[int]) -> str:
    if not counts_up or len(counts_up) != len(counts_down):
        return "inconsistent"
    up_present = [count > 0 for count in counts_up]
    down_present = [count > 0 for count in counts_down]
    if not all(value == up_present[0] for value in up_present):
        return "inconsistent"
    if not all(value == down_present[0] for value in down_present):
        return "inconsistent"
    if up_present[0] and not down_present[0]:
        return "increasingOnly"
    if down_present[0] and not up_present[0]:
        return "decreasingOnly"
    if up_present[0] and down_present[0]:
        return "both"
    return "neither"


def _disposition(direction: str) -> dict[str, Any]:
    if direction == "increasingOnly":
        return {
            "policy": "exclude",
            "role": "EXCLUDED_LOAD",
            "candidateManifestRoles": ["LOAD"],
            "reason": "silentRuntimeOneShotSchedulesOnIncreasingThrottleOnly",
        }
    if direction == "decreasingOnly":
        return {
            "policy": "allowCandidate",
            "role": "ENGINE_TRANSIENT_CANDIDATE",
            "candidateManifestRoles": ["ENGINE_TRANSIENT", "OVERRUN"],
            "reason": "silentRuntimeOneShotSchedulesOnDecreasingThrottleOnly",
        }
    return {
        "policy": "ambiguous",
        "role": "AMBIGUOUS",
        "candidateManifestRoles": [],
        "reason": f"silentRuntimeThrottleDirection:{direction}",
    }


def _rpm_probe(entry: dict[str, Any]) -> float:
    evidence = entry["nonFilenameEvidence"]
    controllers = evidence["automationControllers"]
    rpm_controllers = [
        controller
        for controller in controllers
        if str(controller.get("inputParameterName", "")).casefold() == "rpms"
    ]
    if len(rpm_controllers) != 1:
        raise ValueError(
            f"expected one RPM controller for {entry['sourceGuid']}, "
            f"got {len(rpm_controllers)}"
        )
    points = rpm_controllers[0].get("points") or []
    maximum = max(float(point["y"]) for point in points)
    peak_x = [float(point["x"]) for point in points if float(point["y"]) == maximum]
    rpm = sum(peak_x) / len(peak_x)
    placements = [
        placement
        for membership in evidence["eventMemberships"]
        for placement in membership["triggerTopology"]["placements"]
        if str(placement.get("parameterName", "")).casefold() == "rpms"
    ]
    if not any(
        float(placement["start"]) <= rpm <= float(placement["end"])
        for placement in placements
    ):
        raise ValueError(f"peak RPM {rpm} is outside the placement for {entry['sourceGuid']}")
    return rpm


def _event_description(
    studio: C.WinDLL, car_bank: C.c_void_p, event_path: str
) -> C.c_void_p:
    count = C.c_int()
    SilentFmodReferenceRenderer._check(
        studio.FMOD_Studio_Bank_GetEventCount(car_bank, C.byref(count)),
        "count direction-oracle events",
    )
    descriptions = (C.c_void_p * max(1, count.value))()
    actual = C.c_int()
    SilentFmodReferenceRenderer._check(
        studio.FMOD_Studio_Bank_GetEventList(
            car_bank, descriptions, count.value, C.byref(actual)
        ),
        "list direction-oracle events",
    )
    wanted = event_path.casefold().removeprefix("event:")
    for raw_description in descriptions[: actual.value]:
        needed = C.c_int()
        studio.FMOD_Studio_EventDescription_GetPath(
            raw_description, None, 0, C.byref(needed)
        )
        buffer = C.create_string_buffer(max(1, needed.value))
        if studio.FMOD_Studio_EventDescription_GetPath(
            raw_description, buffer, len(buffer), C.byref(needed)
        ):
            continue
        actual_path = buffer.value.decode("utf-8", "replace").casefold()
        if actual_path.removeprefix("event:") == wanted:
            return C.c_void_p(raw_description)
    raise FmodError(f"event {event_path!r} is absent from isolated bank")


def _run_sweep(
    assetto_root: Path,
    bank_path: Path,
    event_path: str,
    target_sample_name: str,
    rpm: float,
    *,
    direction: str,
    output_root: Path,
    step_count: int = 40,
    updates_per_step: int = 8,
    baseline_updates: int = 180,
) -> dict[str, Any]:
    if direction not in {"up", "down"}:
        raise ValueError(f"unsupported sweep direction: {direction}")
    renderer = SilentFmodReferenceRenderer(assetto_root)
    with tempfile.TemporaryDirectory(prefix="fmod-direction-", dir=output_root) as temp_text:
        writer_path = Path(temp_text) / "silent-oracle.wav"
        cookie = os.add_dll_directory(str(assetto_root))
        core = C.WinDLL(str(assetto_root / "fmod64.dll"))
        studio = C.WinDLL(str(assetto_root / "fmodstudio64.dll"))
        renderer._bind(core, studio)
        system = C.c_void_p()
        instance = C.c_void_p()
        callbacks: list[dict[str, Any]] = []
        stage = "setup"
        step_index = -1
        throttle_value = 0.0 if direction == "up" else 1.0

        @EventCallback
        def callback(callback_type: int, _event: int, parameters_pointer: int) -> int:
            if (
                callback_type == FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                and parameters_pointer
            ):
                name = C.create_string_buffer(1024)
                renderer._check(
                    core.FMOD_Sound_GetName(
                        C.c_void_p(parameters_pointer), name, len(name)
                    ),
                    "read direction-oracle sound identity",
                )
                decoded = name.value.decode("utf-8", "replace")
                callbacks.append(
                    {
                        "stage": stage,
                        "stepIndex": step_index,
                        "throttle": throttle_value,
                        "identityMatchesTarget": decoded == target_sample_name,
                        "soundIdentitySha256": hashlib.sha256(
                            decoded.encode("utf-8")
                        ).hexdigest(),
                    }
                )
            return 0

        renderer._check(
            studio.FMOD_Studio_System_Create(C.byref(system), FMOD_VERSION),
            "create direction-oracle Studio system",
        )
        try:
            low_level = C.c_void_p()
            renderer._check(
                studio.FMOD_Studio_System_GetLowLevelSystem(system, C.byref(low_level)),
                "get direction-oracle low-level system",
            )
            renderer._check(
                core.FMOD_System_SetOutput(low_level, FMOD_OUTPUTTYPE_WAVWRITER_NRT),
                "select direction-oracle non-realtime writer",
            )
            renderer._check(
                core.FMOD_System_SetSoftwareFormat(
                    low_level, 48000, FMOD_SPEAKERMODE_STEREO, 0
                ),
                "set direction-oracle stereo format",
            )
            renderer._check(
                core.FMOD_System_SetDSPBufferSize(
                    low_level, renderer.dsp_buffer_frames, 4
                ),
                "set direction-oracle DSP buffer",
            )
            writer_name = C.create_string_buffer(str(writer_path).encode("utf-8"))
            renderer._check(
                studio.FMOD_Studio_System_Initialize(
                    system, 64, 0, 0, C.cast(writer_name, C.c_void_p)
                ),
                "initialize direction-oracle writer",
            )
            distance_description, distance_keepalive = _distance_filter_description()
            renderer._check(
                studio.FMOD_Studio_System_RegisterPlugin(
                    system, C.byref(distance_description)
                ),
                "register direction-oracle distance filter",
            )
            gain_description, gain_keepalive = _gain_description()
            renderer._check(
                studio.FMOD_Studio_System_RegisterPlugin(
                    system, C.byref(gain_description)
                ),
                "register direction-oracle gain plugin",
            )
            loaded: list[C.c_void_p] = []
            for path in (
                assetto_root / "content" / "sfx" / "common.strings.bank",
                assetto_root / "content" / "sfx" / "common.bank",
                bank_path,
            ):
                bank = C.c_void_p()
                renderer._check(
                    studio.FMOD_Studio_System_LoadBankFile(
                        system, str(path).encode("utf-8"), 0, C.byref(bank)
                    ),
                    f"load {path.name}",
                )
                loaded.append(bank)
            description = _event_description(studio, loaded[-1], event_path)
            renderer._check(
                studio.FMOD_Studio_EventDescription_LoadSampleData(description),
                "load direction-oracle sample data",
            )
            renderer._check(
                studio.FMOD_Studio_System_FlushSampleLoading(system),
                "finish direction-oracle sample loading",
            )
            renderer._check(
                studio.FMOD_Studio_EventDescription_CreateInstance(
                    description, C.byref(instance)
                ),
                "create direction-oracle event instance",
            )
            emitter = _attributes((0.0, 0.5, 0.0))
            listener = _attributes((0.0, 0.7, 0.0))
            renderer._check(
                studio.FMOD_Studio_System_SetListenerAttributes(
                    system, 0, C.byref(listener)
                ),
                "place direction-oracle listener",
            )
            renderer._check(
                studio.FMOD_Studio_EventInstance_Set3DAttributes(
                    instance, C.byref(emitter)
                ),
                "place direction-oracle event",
            )
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetCallback(
                    instance, callback, FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED
                ),
                "attach direction-oracle callback",
            )
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, b"rpms", C.c_float(rpm)
                ),
                "set direction-oracle RPM",
            )
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetParameterValue(
                    instance, b"throttle", C.c_float(throttle_value)
                ),
                "set direction-oracle initial throttle",
            )
            renderer._check(
                studio.FMOD_Studio_EventInstance_SetTimelinePosition(instance, 0),
                "rewind direction-oracle event",
            )
            stage = "baseline"
            renderer._check(
                studio.FMOD_Studio_EventInstance_Start(instance),
                "start direction-oracle event",
            )
            renderer._check(
                studio.FMOD_Studio_System_FlushCommands(system),
                "flush direction-oracle start",
            )
            for _ in range(baseline_updates):
                renderer._check(
                    studio.FMOD_Studio_System_Update(system),
                    "render direction-oracle baseline",
                )
            baseline_callbacks = list(callbacks)
            callbacks.clear()
            values = [index / step_count for index in range(step_count + 1)]
            if direction == "down":
                values.reverse()
            stage = "sweep"
            for step_index, value in enumerate(values[1:]):
                throttle_value = value
                renderer._check(
                    studio.FMOD_Studio_EventInstance_SetParameterValue(
                        instance, b"throttle", C.c_float(throttle_value)
                    ),
                    "step direction-oracle throttle",
                )
                renderer._check(
                    studio.FMOD_Studio_System_FlushCommands(system),
                    "flush direction-oracle throttle step",
                )
                for _ in range(updates_per_step):
                    renderer._check(
                        studio.FMOD_Studio_System_Update(system),
                        "render direction-oracle throttle step",
                    )
            sweep_callbacks = list(callbacks)
            all_callbacks = baseline_callbacks + sweep_callbacks
            if any(not item["identityMatchesTarget"] for item in all_callbacks):
                raise AssertionError("target-only bank scheduled an unexpected sound identity")
            _ = (
                writer_name,
                callback,
                distance_keepalive,
                gain_keepalive,
            )
        finally:
            if instance:
                studio.FMOD_Studio_EventInstance_Stop(
                    instance, FMOD_STUDIO_STOP_IMMEDIATE
                )
                studio.FMOD_Studio_EventInstance_Release(instance)
            if system:
                studio.FMOD_Studio_System_UnloadAll(system)
                studio.FMOD_Studio_System_Release(system)
            cookie.close()
    return {
        "direction": direction,
        "fixedRpm": rpm,
        "startThrottle": 0.0 if direction == "up" else 1.0,
        "endThrottle": 1.0 if direction == "up" else 0.0,
        "stepCount": step_count,
        "updatesPerStep": updates_per_step,
        "baselineUpdates": baseline_updates,
        "baselineCallbackCount": len(baseline_callbacks),
        "baselineCallbacks": baseline_callbacks,
        "sweepCallbackCount": len(sweep_callbacks),
        "sweepCallbacks": sweep_callbacks,
    }


def probe_direction_oracle(
    assetto_root: Path,
    graph_root: Path,
    backlog_path: Path,
    output_root: Path,
    *,
    repetitions: int = 2,
) -> dict[str, Any]:
    if repetitions < 2 or repetitions > 5:
        raise ValueError("repetitions must be between 2 and 5")
    root = assetto_root.resolve(strict=True)
    graph_root = graph_root.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    summary = _read_document(graph_root / "summary.json", CATALOG_GRAPH_SCHEMA)
    backlog = _read_document(backlog_path.resolve(strict=True), BACKLOG_SCHEMA)
    entries = backlog.get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("manual-oracle backlog has no entries")
    families = {str(item["familyId"]): item for item in summary["families"]}
    by_family: dict[str, list[dict[str, Any]]] = {}
    for entry in entries:
        if entry.get("requiredOracle", {}).get("kind") != "silentRuntimeDirectionSweep":
            raise ValueError(f"unsupported backlog oracle for {entry.get('sourceGuid')}")
        by_family.setdefault(str(entry["familyId"]), []).append(entry)

    source_bank_hashes: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    for family_id, family_entries in sorted(by_family.items()):
        family = families.get(family_id)
        if family is None:
            raise ValueError(f"family {family_id} is absent from graph summary")
        graph_path = graph_root / str(family["cachePath"])
        graph = _read_document(graph_path, FAMILY_GRAPH_SCHEMA)
        bank_path = root / str(family["bankPath"])
        before = _sha256(bank_path)
        if before != family_id or before != str(graph["bank"]["sha256"]):
            raise ValueError(f"source-bank identity mismatch for {family_id}")
        instruments = {_guid(item["guid"]): item for item in graph["instruments"]}
        events = {str(item["path"]).casefold(): item for item in graph["events"]}
        for entry in sorted(family_entries, key=lambda item: item["sourceGuid"]):
            source_guid = _guid(entry["sourceGuid"])
            source = instruments.get(source_guid)
            if source is None or source.get("kind") != "WaveformInstrumentNode":
                raise ValueError(f"target waveform {source_guid} is absent")
            event_paths = entry.get("eventPaths") or []
            if len(event_paths) != 1:
                raise ValueError(f"target {source_guid} does not have exactly one event")
            event_path = str(event_paths[0])
            event = events.get(event_path.casefold())
            if event is None:
                raise ValueError(f"event {event_path} is absent from graph")
            reachable_waveforms = {
                _guid(guid)
                for guid in event["reachableInstrumentGuids"]
                if instruments.get(_guid(guid), {}).get("kind")
                == "WaveformInstrumentNode"
            }
            if source_guid not in reachable_waveforms:
                raise ValueError(f"target {source_guid} is not reachable from {event_path}")
            solo_path = output_root / f"{source_guid}-solo.bank"
            isolated = create_isolated_bank_copy(
                bank_path,
                graph,
                reachable_waveforms - {source_guid},
                solo_path,
            )
            sample = source.get("sample") or {}
            sample_name = str(sample.get("name") or "")
            if not sample_name:
                raise ValueError(f"target {source_guid} has no runtime identity")
            rpm = _rpm_probe(entry)
            runs: list[dict[str, Any]] = []
            for repetition in range(repetitions):
                for direction in ("up", "down"):
                    run = _run_sweep(
                        root,
                        isolated.output_path,
                        event_path,
                        sample_name,
                        rpm,
                        direction=direction,
                        output_root=output_root,
                    )
                    run["repetition"] = repetition
                    runs.append(run)
            up_counts = [
                int(run["sweepCallbackCount"])
                for run in runs
                if run["direction"] == "up"
            ]
            down_counts = [
                int(run["sweepCallbackCount"])
                for run in runs
                if run["direction"] == "down"
            ]
            observed_direction = _direction(up_counts, down_counts)
            technical = source.get("sample") or {}
            results.append(
                {
                    "familyId": family_id,
                    "representativeCarId": family.get("representativeCarId"),
                    "sourceGuid": source_guid,
                    "eventPath": event_path,
                    "lifetime": "oneShot",
                    "fixedRpm": rpm,
                    "targetIdentity": {
                        "waveformResourceGuid": technical.get(
                            "waveformResourceGuid"
                        ),
                        "soundBankIndex": technical.get("soundBankIndex"),
                        "subsoundIndex": technical.get("subsoundIndex"),
                        "encodedPayloadSha256": technical.get("encodedPayloadSha256"),
                        "runtimeIdentitySha256": hashlib.sha256(
                            sample_name.encode("utf-8")
                        ).hexdigest(),
                    },
                    "isolation": {
                        "soloBankSha256": isolated.output_sha256,
                        "mutedReachableWaveformCount": len(isolated.patches),
                        "targetWasNotPatched": source_guid
                        not in {patch.source_guid for patch in isolated.patches},
                    },
                    "runs": runs,
                    "upSweepCallbackCounts": up_counts,
                    "downSweepCallbackCounts": down_counts,
                    "observedDirection": observed_direction,
                    "disposition": _disposition(observed_direction),
                }
            )
        after = _sha256(bank_path)
        source_bank_hashes.append(
            {
                "familyId": family_id,
                "path": str(bank_path),
                "sha256Before": before,
                "sha256After": after,
                "unchanged": before == after,
            }
        )
        if after != before:
            raise AssertionError(f"installed bank changed during oracle: {bank_path}")
    unresolved = [
        item for item in results if item["disposition"]["policy"] == "ambiguous"
    ]
    return {
        "schema": SCHEMA,
        "inputSchema": BACKLOG_SCHEMA,
        "basis": {
            "usesSampleNamesForSemantics": False,
            "usesSampleNamesForRuntimeIdentityJoin": True,
            "audioDeviceOpened": False,
            "outputMode": "WAVWRITER_NRT",
            "sourceIsolation": "allOtherEventReachableWaveformsTriggerChanceZero",
            "unknownPolicy": "failClosed",
        },
        "counts": {
            "families": len(by_family),
            "sources": len(results),
            "repetitionsPerDirection": repetitions,
            "resolved": len(results) - len(unresolved),
            "unresolved": len(unresolved),
        },
        "sourceBanks": source_bank_hashes,
        "results": sorted(results, key=lambda item: (item["familyId"], item["sourceGuid"])),
        "result": "PASS" if not unresolved else "INCONCLUSIVE_FAIL_CLOSED",
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--graph-root", type=Path, default=DEFAULT_GRAPH_ROOT)
    parser.add_argument("--backlog", type=Path, default=DEFAULT_BACKLOG)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--repetitions", type=int, default=2)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    output_root = args.output_root.resolve()
    report = probe_direction_oracle(
        find_assetto_root(args.assetto_root),
        args.graph_root,
        args.backlog,
        output_root,
        repetitions=args.repetitions,
    )
    report_path = (args.report or output_root / "proof.json").resolve()
    _write_canonical(report_path, report)
    print(
        f"{report['result']}: {report['counts']['resolved']}/"
        f"{report['counts']['sources']} direction oracles resolved; "
        f"evidence={report_path}"
    )
    return 0 if report["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
