#!/usr/bin/env python3
"""Capture release causal-resource evidence from one original Assetto bank.

This program is the native side of BYDMotorSound's causal observation
producer.  It deliberately accepts only the hashed v1 capture request and it
has no synthetic/fixture output mode.  Every reported voice comes from Core
channel enumeration in ``SilentFmodReferenceRenderer.render_event_mix`` and is
joined to an authored waveform occurrence through an exact event owner plus a
source-solo identity oracle.  Ambiguous joins, unsupported finite lifecycle
topologies, missing tails, and unverifiable camera fades abort the capture.
"""

from __future__ import annotations

import argparse
from bisect import bisect_right
from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
from typing import Any, Mapping, Sequence


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.fmod_bank_isolation import (  # noqa: E402
    create_isolated_bank_copy,
    fully_muted_multi_instrument_guids,
)
from sim.fmod_native import (  # noqa: E402
    ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
    ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
    FMOD_API_ROOT_ENV,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer  # noqa: E402


REQUEST_SCHEMA = "byd-original-bank-causal-capture-request-v1"
TRACE_SCHEMA = "byd-original-bank-causal-session-trace-v1"
VIRTUALIZATION_SCHEMA = "byd-fmod-virtual-voice-inaudibility-v1"
DSP_BUFFER_FRAMES = 256
SAMPLE_RATE_HZ = 48_000
HOST_TICK_FRAMES = 240
VIRTUAL_EPSILON = 1.0e-7
CAMERA_STOP_MODE = "FMOD_STUDIO_STOP_ALLOWFADEOUT"
SUPPORTED_FINITE_TOPOLOGY_STATUS = (
    "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE"
)
SUPPORTED_FINITE_TOPOLOGY = "parameterPlacementOnly"
SOURCE_SOLO_PROBE_FRAMES = 4_096
SOURCE_SOLO_MAXIMUM_TAKES = 64
CAMERA_CAPTURE_FRAME_CANDIDATES = (48_000, 192_000, 480_000)


class CaptureError(ValueError):
    """The request cannot be represented by attributable native evidence."""


def _canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)

    return digest.hexdigest()


def _object(value: object, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise CaptureError(f"{label} must be an object")

    return value


def _array(value: object, label: str) -> Sequence[Any]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        raise CaptureError(f"{label} must be an array")

    return value


def _string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise CaptureError(f"{label} must be a non-empty string")

    return value


def _guid(value: object, label: str) -> str:
    normalized = _string(value, label).strip().strip("{}").casefold()
    if len(normalized) != 36:
        raise CaptureError(f"{label} is not a canonical GUID")

    return normalized


def _finite_float(value: object, label: str) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
    ):
        raise CaptureError(f"{label} must be finite")

    return float(value)


def _nonnegative_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise CaptureError(f"{label} must be a non-negative integer")

    return value


def _self_hash(value: Mapping[str, Any], field: str) -> dict[str, Any]:
    if field in value:
        raise CaptureError(f"cannot overwrite {field}")
    result = dict(value)
    result[field] = hashlib.sha256(_canonical_json_bytes(result)).hexdigest()

    return result


def _validate_self_hash(value: Mapping[str, Any], field: str, label: str) -> None:
    expected = value.get(field)
    if not isinstance(expected, str) or len(expected) != 64:
        raise CaptureError(f"{label} has no {field}")
    body = dict(value)
    body.pop(field, None)
    actual = hashlib.sha256(_canonical_json_bytes(body)).hexdigest()
    if actual != expected:
        raise CaptureError(f"{label} {field} differs")


def _ceil_dsp(frame: int) -> int:
    return (frame + DSP_BUFFER_FRAMES - 1) // DSP_BUFFER_FRAMES * DSP_BUFFER_FRAMES


def _scheduling_group_key(event_path: str, group_id: str) -> str:
    identity = {"eventPath": event_path, "groupId": group_id}
    return "scheduling-group:" + hashlib.sha256(
        _canonical_json_bytes(identity)
    ).hexdigest()


def _effect_node_key(
    event_path: str,
    source_guid: str,
    authored_binding_key: str,
    parameters: Mapping[str, Any],
) -> str:
    identity = {
        "eventPath": event_path,
        "sourceGuid": source_guid,
        "authoredBindingKey": authored_binding_key,
        "parameters": dict(parameters),
    }
    return "effect-node:" + hashlib.sha256(
        _canonical_json_bytes(identity)
    ).hexdigest()


def _engine_node_key(
    perspective: str,
    event_path: str,
    parameters: Mapping[str, Any],
) -> str:
    identity = {
        "perspective": perspective,
        "eventPath": event_path,
        "parameters": dict(parameters),
    }

    return "engine-node:" + hashlib.sha256(
        _canonical_json_bytes(identity)
    ).hexdigest()


@dataclass(frozen=True)
class EffectNode:
    key: str
    parameters: Mapping[str, float]
    duration_frames: int


@dataclass(frozen=True)
class SourceIdentity:
    event_path: str
    source_guid: str
    diagnostic_name: str
    kind: str
    lifetime: str
    authored_binding_key: str | None
    activation_perspective: str | None
    perspectives: frozenset[str]
    group_key: str | None
    group_id: str | None
    group_composition: str | None
    parameter_axes: Mapping[str, tuple[float, ...]]
    parameters: Mapping[str, float]
    nodes: tuple[EffectNode, ...]


@dataclass(frozen=True)
class EventSchedule:
    ordinal: int
    event_path: str
    engine_perspective: str | None
    persistent_effect: bool
    parameters_by_delivery_frame: Mapping[int, Mapping[str, float]]
    lifecycle_actions: tuple[tuple[int, str], ...]


@dataclass(frozen=True)
class BoundChannel:
    raw: Mapping[str, Any]
    identity: SourceIdentity
    voice_token: str
    source_binding_oracle_sha256: str
    ring_id: str | None


@dataclass(frozen=True)
class RingDefinition:
    instance_id: str
    event_path: str
    group_key: str
    group_id: str
    activation_perspective: str
    trigger_host_frame: int
    start_frame: int
    end_frame_exclusive: int
    contributor_node_keys: tuple[str, ...]
    channel_generation_keys: frozenset[tuple[int, int, int, str]]


class NativeCausalCapturer:
    def __init__(self, request: Mapping[str, Any], working_directory: Path):
        self.request = request
        self.working_directory = working_directory
        self.plan = _object(request.get("atlasPlan"), "atlas plan")
        self.bank_path = Path(_string(request.get("bankPath"), "bank path")).resolve(
            strict=True
        )
        self.graph_path = Path(
            _string(request.get("graphPath"), "graph path")
        ).resolve(strict=True)
        self.realization_path = Path(
            _string(request.get("realizationPath"), "realization path")
        ).resolve(strict=True)
        self.graph = self._load_json(self.graph_path, "bank graph")
        self.realization = self._load_json(
            self.realization_path, "atlas realization"
        )
        self.assetto_root = PROJECT_ROOT / "macos_bank_lab"
        for common_name in ("common.strings.bank", "common.bank"):
            common = self.assetto_root / "content" / "sfx" / common_name
            if not common.is_file():
                raise CaptureError(f"Audio Lab native common bank is absent: {common}")
        self.renderer = SilentFmodReferenceRenderer(
            self.assetto_root,
            dsp_buffer_frames=DSP_BUFFER_FRAMES,
        )
        self.identities_by_event_and_name: dict[
            tuple[str, str], tuple[SourceIdentity, ...]
        ] = {}
        self.identities_by_binding: dict[str, SourceIdentity] = {}
        self.groups: dict[str, tuple[str, str, str]] = {}
        self.event_parameter_bindings: dict[
            str, dict[str, tuple[str, float | str]]
        ] = {}
        self.event_parameter_defaults: dict[str, dict[str, float]] = {}
        self.engine_event_by_perspective: dict[str, str] = {}
        self.source_solo_oracle_cache: dict[
            tuple[str, str, str, str, str, str], str
        ] = {}
        self.realization_oracles: dict[tuple[str, str], str] = {}
        self.source_binding_oracles_by_sha256: dict[
            str, Mapping[str, Any]
        ] = {}
        self.used_source_binding_oracle_shas: set[str] = set()
        self.graph_event_waveforms: dict[str, frozenset[str]] = {}
        self.graph_instruments: dict[str, Mapping[str, Any]] = {}
        self._validate_request()
        self._build_source_contract()

    @staticmethod
    def _load_json(path: Path, label: str) -> Mapping[str, Any]:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise CaptureError(f"{label} is unreadable: {path}") from exc

        return _object(value, label)

    def _validate_request(self) -> None:
        if self.request.get("schema") != REQUEST_SCHEMA:
            raise CaptureError("capture request schema differs")
        _validate_self_hash(self.request, "requestSha256", "capture request")
        if platform.machine().casefold() != "x86_64":
            raise CaptureError(
                "causal native capture must run under the x86_64 process"
            )
        for path, key in (
            (self.bank_path, "bankSha256"),
            (self.graph_path, "graphSha256"),
            (self.realization_path, "realizationSha256"),
        ):
            if _sha256(path) != self.request.get(key):
                raise CaptureError(f"capture request {key} differs from {path}")
        if (
            self.plan.get("id") != self.request.get("atlasFamilyId")
            or self.plan.get("planSha256") != self.request.get("planSha256")
            or self.plan.get("bankSha256") != self.request.get("bankSha256")
        ):
            raise CaptureError("capture request plan identity differs")
        boundary = _object(self.request.get("runtimeBoundary"), "runtime boundary")
        exact_boundary = {
            "sampleRateHz": SAMPLE_RATE_HZ,
            "dspBufferFrames": DSP_BUFFER_FRAMES,
            "hostControlHz": 200,
            "hostTickFrames": HOST_TICK_FRAMES,
            "assettoStudioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
            "assettoSoftwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
        }
        if dict(boundary) != exact_boundary:
            raise CaptureError("capture request runtime boundary differs")
        source_policy = _object(
            self.request.get("sourceIdentityPolicy"), "source identity policy"
        )
        if (
            source_policy.get("diagnosticNameMaySelectIdentity") is not False
            or source_policy.get("ambiguousRuntimeSourceIdentity") != "failClosed"
            or source_policy.get("requiredIdentity")
            != "eventPath+sourceGuid+authoredBindingKey"
        ):
            raise CaptureError("capture request weakens exact source identity")
        instance_policy = _object(
            self.request.get("eventInstancePolicy"), "event-instance policy"
        )
        if (
            instance_policy.get("originalBankOnly") is not True
            or instance_policy.get("cameraOldEngineStopMode") != CAMERA_STOP_MODE
            or instance_policy.get("persistentEffectsSurviveCameraAndProgramModeChanges")
            is not True
            or instance_policy.get("freshFiniteInstanceSubstitution") != "forbidden"
        ):
            raise CaptureError("capture request event-instance policy differs")
        virtual_policy = _object(
            self.request.get("virtualVoicePolicy"), "virtual voice policy"
        )
        if (
            virtual_policy.get("schema") != VIRTUALIZATION_SCHEMA
            or virtual_policy.get("audibilityEpsilon") != VIRTUAL_EPSILON
            or virtual_policy.get("authoredRouteGainEpsilon") != VIRTUAL_EPSILON
            or virtual_policy.get(
                "virtualBindingMustBeRealAudibleElsewhereOrCertifiedSilent"
            )
            is not True
        ):
            raise CaptureError("capture request virtual-voice policy differs")
        graph_bank = _object(self.graph.get("bank"), "bank graph identity")
        if graph_bank.get("sha256") != self.request.get("bankSha256"):
            raise CaptureError("bank graph is not for the requested original bank")
        if (
            self.realization.get("atlasFamilyId") != self.request.get("atlasFamilyId")
            or self.realization.get("planSha256") != self.request.get("planSha256")
            or self.realization.get("sourceBankSha256Before")
            != self.request.get("bankSha256")
            or self.realization.get("sourceBankSha256After")
            != self.request.get("bankSha256")
            or self.realization.get("sourceBankUnchanged") is not True
            or self.realization.get("fullRun") is not True
        ):
            raise CaptureError(
                "atlas realization is not a full unchanged-original-bank run"
            )
        scenarios = _array(self.request.get("scenarios"), "capture scenarios")
        if not scenarios:
            raise CaptureError("capture request has no scenarios")
        scenario_ids: set[str] = set()
        for raw in scenarios:
            scenario = _object(raw, "capture scenario")
            _validate_self_hash(
                scenario, "scenarioRequestSha256", f"scenario {scenario.get('id')}"
            )
            identifier = _string(scenario.get("id"), "scenario id")
            if identifier in scenario_ids:
                raise CaptureError(f"capture scenario id repeats: {identifier}")
            scenario_ids.add(identifier)

    def _build_source_contract(self) -> None:
        raw_instruments = _array(self.graph.get("instruments"), "graph instruments")
        for raw in raw_instruments:
            item = _object(raw, "graph instrument")
            guid = _guid(item.get("guid"), "graph instrument GUID")
            if guid in self.graph_instruments:
                raise CaptureError(f"graph instrument GUID repeats: {guid}")
            self.graph_instruments[guid] = item
        for raw in _array(self.graph.get("events"), "graph events"):
            event = _object(raw, "graph event")
            path = _string(event.get("path"), "graph event path")
            reachable = frozenset(
                _guid(value, f"{path} reachable instrument")
                for value in _array(
                    event.get("reachableInstrumentGuids"),
                    f"{path} reachable instruments",
                )
            )
            self.graph_event_waveforms[path] = frozenset(
                guid
                for guid in reachable
                if self.graph_instruments.get(guid, {}).get("kind")
                == "WaveformInstrumentNode"
            )

        catalog = _object(self.request.get("exactSourceCatalog"), "source catalog")
        raw_engines = _object(catalog.get("engines"), "engine source catalog")
        plan_perspectives = _object(self.plan.get("perspectives"), "plan perspectives")
        identities: list[SourceIdentity] = []
        for perspective in ("cabin", "exterior"):
            program = _object(
                plan_perspectives.get(perspective), f"{perspective} engine plan"
            )
            catalog_program = _object(
                raw_engines.get(perspective), f"{perspective} engine catalog"
            )
            event_path = _string(program.get("eventPath"), "engine event path")
            if catalog_program.get("eventPath") != event_path:
                raise CaptureError(f"{perspective} engine catalog event differs")
            self.engine_event_by_perspective[perspective] = event_path
            self._merge_bindings(
                event_path,
                _array(
                    program.get("hostParameterBindings", []),
                    f"{perspective} engine host bindings",
                ),
            )
            self._seed_engine_defaults(event_path, program)
            for raw_source in _array(
                catalog_program.get("sources"), f"{perspective} engine sources"
            ):
                source = _object(raw_source, "engine source")
                source_guid = _guid(source.get("sourceGuid"), "engine source GUID")
                diagnostic_name = _string(
                    source.get("diagnosticName"), "engine source diagnostic identity"
                )
                self._validate_graph_waveform(
                    event_path, source_guid, diagnostic_name
                )
                identities.append(
                    SourceIdentity(
                        event_path=event_path,
                        source_guid=source_guid,
                        diagnostic_name=diagnostic_name,
                        kind="engineContinuous",
                        lifetime="continuous",
                        authored_binding_key=None,
                        activation_perspective=perspective,
                        perspectives=frozenset((perspective,)),
                        group_key=None,
                        group_id=None,
                        group_composition=None,
                        parameter_axes={},
                        parameters={},
                        nodes=(),
                    )
                )

        effect_catalog = [
            _object(item, "effect catalog source")
            for item in _array(catalog.get("effects"), "effect source catalog")
        ]
        catalog_by_binding: dict[str, Mapping[str, Any]] = {}
        for item in effect_catalog:
            binding = _string(
                item.get("authoredBindingKey"), "effect authored binding key"
            )
            if binding in catalog_by_binding:
                raise CaptureError(f"effect catalog binding repeats: {binding}")
            catalog_by_binding[binding] = item

        plan_effects = [
            _object(item, "plan effect")
            for item in _array(self.plan.get("effects"), "plan effects")
        ]
        seen_bindings: set[str] = set()
        for event in plan_effects:
            event_path = _string(event.get("eventPath"), "effect event path")
            contract = _object(
                event.get("runtimeLifecycleParameterVariantContract"),
                f"{event_path} variant contract",
            )
            variants = [
                _object(item, f"{event_path} variant")
                for item in _array(contract.get("variants"), f"{event_path} variants")
            ]
            nodes = [
                _object(item, f"{event_path} node")
                for item in _array(event.get("nodes"), f"{event_path} nodes")
            ]
            for variant in variants:
                binding = _string(
                    variant.get("authoredBindingKey"),
                    f"{event_path} authored binding key",
                )
                catalog_item = catalog_by_binding.get(binding)
                if catalog_item is None:
                    raise CaptureError(
                        f"{event_path} binding {binding} is absent from source catalog"
                    )
                if catalog_item.get("eventPath") != event_path:
                    raise CaptureError(f"{binding} catalog event path differs")
                source_guid = _guid(
                    variant.get("sourceGuid"), f"{binding} source GUID"
                )
                if _guid(catalog_item.get("sourceGuid"), f"{binding} catalog GUID") != source_guid:
                    raise CaptureError(f"{binding} catalog source GUID differs")
                diagnostic_name = _string(
                    catalog_item.get("diagnosticName"),
                    f"{binding} diagnostic identity",
                )
                self._validate_graph_waveform(
                    event_path, source_guid, diagnostic_name
                )
                lifetime = _string(variant.get("lifetime"), f"{binding} lifetime")
                mapping = _object(
                    variant.get("runtimeMapping"), f"{binding} runtime mapping"
                )
                if lifetime != "continuous":
                    topology = _object(
                        mapping.get("finiteLifecycleTopology"),
                        f"{binding} finite lifecycle topology",
                    )
                    if (
                        topology.get("status") != SUPPORTED_FINITE_TOPOLOGY_STATUS
                        or topology.get("topology") != SUPPORTED_FINITE_TOPOLOGY
                        or topology.get("triggers") != ["PARAMETER_PLACEMENT_ENTRY"]
                    ):
                        raise CaptureError(
                            f"{binding} finite lifecycle is not an exact source-solo "
                            "parameter-placement topology"
                        )
                ownership = _object(
                    mapping.get("eventInstanceOwnership"),
                    f"{binding} event-instance ownership",
                )
                is_engine_event = event_path in self.engine_event_by_perspective.values()
                expected_owner = (
                    "selectedPerspectiveEngineEventInstance"
                    if is_engine_event
                    else "profileAudioSessionPersistentEventInstance"
                )
                if ownership.get("owner") != expected_owner:
                    raise CaptureError(
                        f"{binding} ownership {ownership.get('owner')!r} cannot be "
                        f"captured as {expected_owner}"
                    )
                perspectives = frozenset(
                    _string(value, f"{binding} perspective")
                    for value in _array(
                        mapping.get("perspectives"), f"{binding} perspectives"
                    )
                )
                if not perspectives or not perspectives <= {"cabin", "exterior"}:
                    raise CaptureError(f"{binding} perspective scope differs")
                scheduler = _object(
                    variant.get("schedulingGroup", mapping.get("schedulingGroup")),
                    f"{binding} scheduling group",
                )
                group_id = _string(scheduler.get("groupId"), f"{binding} group id")
                group_key = _scheduling_group_key(event_path, group_id)
                composition = _string(
                    scheduler.get("composition"), f"{binding} group composition"
                )
                prior_group = self.groups.setdefault(
                    group_key, (event_path, group_id, composition)
                )
                if prior_group != (event_path, group_id, composition):
                    raise CaptureError(f"scheduling group key collides: {group_key}")
                axes = self._parameter_axes(variant, mapping)
                parameters = self._variant_parameters(variant, mapping)
                binding_nodes: list[EffectNode] = []
                for node in nodes:
                    if node.get("requiredAuthoredBindingKey") != binding:
                        continue
                    if _guid(node.get("requiredSourceGuid"), "node source GUID") != source_guid:
                        raise CaptureError(f"{binding} node source GUID differs")
                    node_parameters = {
                        str(key): _finite_float(value, f"{binding} node parameter")
                        for key, value in _object(
                            node.get("parameters"), f"{binding} node parameters"
                        ).items()
                    }
                    node_key = _effect_node_key(
                        event_path, source_guid, binding, node_parameters
                    )
                    catalog_nodes = [
                        _object(item, f"{binding} catalog node")
                        for item in _array(
                            catalog_item.get("nodes"), f"{binding} catalog nodes"
                        )
                    ]
                    exact_catalog_nodes = [
                        item
                        for item in catalog_nodes
                        if item.get("nodeKey") == node_key
                    ]
                    if len(exact_catalog_nodes) != 1:
                        raise CaptureError(
                            f"{binding} exact node is absent/duplicated in source catalog"
                        )
                    duration = _nonnegative_int(
                        node.get("durationFrames"), f"{binding} node duration"
                    )
                    if duration <= 0 or exact_catalog_nodes[0].get("durationFrames") != duration:
                        raise CaptureError(f"{binding} node duration differs")
                    binding_nodes.append(
                        EffectNode(node_key, node_parameters, duration)
                    )
                if not binding_nodes:
                    raise CaptureError(f"{binding} has no exact atlas nodes")
                identity = SourceIdentity(
                    event_path=event_path,
                    source_guid=source_guid,
                    diagnostic_name=diagnostic_name,
                    kind=(
                        "effectContinuous"
                        if lifetime == "continuous"
                        else "effectFinite"
                    ),
                    lifetime=lifetime,
                    authored_binding_key=binding,
                    activation_perspective=None,
                    perspectives=perspectives,
                    group_key=group_key,
                    group_id=group_id,
                    group_composition=composition,
                    parameter_axes=axes,
                    parameters=parameters,
                    nodes=tuple(sorted(binding_nodes, key=lambda item: item.key)),
                )
                identities.append(identity)
                self.identities_by_binding[binding] = identity
                seen_bindings.add(binding)
                self._merge_bindings(
                    event_path,
                    _array(
                        mapping.get("hostParameterBindings", []),
                        f"{binding} host parameter bindings",
                    ),
                )
                self._merge_defaults(event_path, mapping, parameters)
                self._register_realization_oracle(event, identity)
        if seen_bindings != set(catalog_by_binding):
            raise CaptureError("effect source catalog has bindings absent from plan")

        grouped: dict[tuple[str, str], list[SourceIdentity]] = defaultdict(list)
        for identity in identities:
            grouped[(identity.event_path, identity.diagnostic_name)].append(identity)
        self.identities_by_event_and_name = {
            key: tuple(
                sorted(
                    values,
                    key=lambda item: (
                        item.source_guid,
                        item.authored_binding_key or "",
                        item.activation_perspective or "",
                    ),
                )
            )
            for key, values in grouped.items()
        }

    def _validate_graph_waveform(
        self, event_path: str, source_guid: str, diagnostic_name: str
    ) -> None:
        if source_guid not in self.graph_event_waveforms.get(event_path, frozenset()):
            raise CaptureError(
                f"{event_path} graph does not reach source {source_guid}"
            )
        instrument = self.graph_instruments.get(source_guid)
        sample = _object(
            instrument.get("sample") if instrument else None,
            f"{source_guid} graph sample",
        )
        if sample.get("name") != diagnostic_name:
            raise CaptureError(
                f"{event_path} source {source_guid} graph sample identity differs"
            )

    def _merge_bindings(
        self, event_path: str, raw_bindings: Sequence[Any]
    ) -> None:
        target = self.event_parameter_bindings.setdefault(event_path, {})
        for raw in raw_bindings:
            binding = _object(raw, f"{event_path} host binding")
            parameter = _string(
                binding.get("parameter"), f"{event_path} host parameter"
            )
            if "source" in binding:
                value: tuple[str, float | str] = (
                    "source",
                    _string(binding.get("source"), f"{parameter} host source"),
                )
            elif "constant" in binding:
                value = (
                    "constant",
                    _finite_float(binding.get("constant"), f"{parameter} constant"),
                )
            else:
                raise CaptureError(f"{event_path} host binding has no value")
            previous = target.setdefault(parameter, value)
            if previous != value:
                raise CaptureError(
                    f"{event_path} parameter {parameter} has conflicting host bindings"
                )

    def _seed_engine_defaults(
        self, event_path: str, program: Mapping[str, Any]
    ) -> None:
        defaults = self.event_parameter_defaults.setdefault(event_path, {})
        nodes = program.get("nodes", program.get("initialNodes", []))
        if isinstance(nodes, Sequence) and not isinstance(nodes, (str, bytes)):
            for raw in nodes:
                if not isinstance(raw, Mapping):
                    continue
                parameters = raw.get("parameters")
                if not isinstance(parameters, Mapping):
                    continue
                for key, value in parameters.items():
                    try:
                        normalized = _finite_float(value, "engine default")
                    except CaptureError:
                        continue
                    defaults.setdefault(str(key), normalized)
                if defaults:
                    break
        defaults.setdefault("rpms", 3_000.0)
        defaults.setdefault("throttle", 0.5)

    @staticmethod
    def _parameter_axes(
        variant: Mapping[str, Any], mapping: Mapping[str, Any]
    ) -> Mapping[str, tuple[float, ...]]:
        raw_axes = variant.get("parameterAxes", mapping.get("parameterAxes", {}))
        axes: dict[str, tuple[float, ...]] = {}
        for name, raw_values in _object(raw_axes, "variant parameter axes").items():
            values = tuple(
                sorted(
                    {
                        _finite_float(value, f"{name} parameter-axis value")
                        for value in _array(raw_values, f"{name} parameter axis")
                    }
                )
            )
            if not values:
                raise CaptureError(f"{name} parameter axis is empty")
            axes[str(name)] = values

        return axes

    @staticmethod
    def _variant_parameters(
        variant: Mapping[str, Any], mapping: Mapping[str, Any]
    ) -> Mapping[str, float]:
        raw = variant.get("parameters", mapping.get("captureParameters", {}))
        return {
            str(key): _finite_float(value, f"{key} variant parameter")
            for key, value in _object(raw, "variant parameters").items()
        }

    def _merge_defaults(
        self,
        event_path: str,
        mapping: Mapping[str, Any],
        parameters: Mapping[str, float],
    ) -> None:
        defaults = self.event_parameter_defaults.setdefault(event_path, {})
        raw_authored = mapping.get("authoredParameterDefaults", [])
        if isinstance(raw_authored, Sequence) and not isinstance(
            raw_authored, (str, bytes)
        ):
            for raw in raw_authored:
                if not isinstance(raw, Mapping) or "parameter" not in raw:
                    continue
                # Automatic distance/cone controls are owned by FMOD's 3-D
                # listener state and cannot be driven through
                # EventInstance_SetParameterValue.  Only game-controlled
                # defaults belong in the host trajectory.
                parameter_type = raw.get("type")
                if (
                    parameter_type is not None
                    and parameter_type
                    != "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED"
                ):
                    continue
                parameter = str(raw["parameter"])
                value = _finite_float(
                    raw.get("defaultValue"), f"{event_path} authored default"
                )
                previous = defaults.setdefault(parameter, value)
                if previous != value:
                    raise CaptureError(
                        f"{event_path} authored default {parameter} conflicts"
                    )
        for parameter, value in parameters.items():
            defaults.setdefault(parameter, value)

    def _register_realization_oracle(
        self, event: Mapping[str, Any], identity: SourceIdentity
    ) -> None:
        captures = [
            _object(item, "realization capture")
            for item in _array(
                self.realization.get("captures"), "realization captures"
            )
        ]
        plan_nodes = [
            _object(item, f"{identity.event_path} plan node")
            for item in _array(event.get("nodes"), f"{identity.event_path} nodes")
            if isinstance(item, Mapping)
            and item.get("requiredAuthoredBindingKey")
            == identity.authored_binding_key
        ]
        asset_names = {
            str(item.get("temporaryAssetName"))
            for item in plan_nodes
            if item.get("temporaryAssetName")
        }
        exact: list[Mapping[str, Any]] = []
        for capture in captures:
            if (
                capture.get("temporaryAssetName") in asset_names
                and capture.get("eventPath") == identity.event_path
                and capture.get("requiredDiagnosticName")
                == identity.diagnostic_name
                and set(capture.get("scheduledDiagnosticNames", []))
                == {identity.diagnostic_name}
                and capture.get("sourceIsolatedFullEventContribution") is True
                and capture.get("sourceIsolationMethod")
                == "sourceSoloEventRoutingAndBusDsp-v1"
            ):
                exact.append(capture)
        if exact:
            evidence = {
                "schema": "byd-realization-source-binding-oracle-v1",
                "eventPath": identity.event_path,
                "sourceGuid": identity.source_guid,
                "authoredBindingKey": identity.authored_binding_key,
                "diagnosticName": identity.diagnostic_name,
                "realizationSha256": self.request["realizationSha256"],
                "captures": sorted(
                    (
                        {
                            "temporaryAssetName": item["temporaryAssetName"],
                            "taskSpecSha256": item.get("taskSpecSha256"),
                            "wavSha256": item.get("wavSha256"),
                        }
                        for item in exact
                    ),
                    key=lambda item: item["temporaryAssetName"],
                ),
            }
            self.realization_oracles[
                (identity.authored_binding_key or "", identity.source_guid)
            ] = self._register_source_binding_oracle(evidence)

    def _register_source_binding_oracle(
        self, evidence: Mapping[str, Any]
    ) -> str:
        exact = dict(evidence)
        oracle_sha = hashlib.sha256(
            _canonical_json_bytes(exact)
        ).hexdigest()
        prior = self.source_binding_oracles_by_sha256.setdefault(
            oracle_sha, exact
        )
        if prior != exact:
            raise CaptureError("source-binding oracle SHA-256 collides")

        return oracle_sha

    def _event_parameters(
        self,
        event_path: str,
        host_values: Mapping[str, Any],
        mode: str,
        *,
        engine_event: bool,
    ) -> dict[str, float]:
        values = dict(self.event_parameter_defaults.get(event_path, {}))
        for parameter, (kind, raw_value) in self.event_parameter_bindings.get(
            event_path, {}
        ).items():
            if kind == "constant":
                values[parameter] = float(raw_value)
            else:
                source = str(raw_value)
                if source in host_values:
                    values[parameter] = _finite_float(
                        host_values[source], f"{event_path} {source} host value"
                    )
        if "EngineSimulation.rpm" in host_values:
            values["rpms"] = _finite_float(
                host_values["EngineSimulation.rpm"], "engine RPM host value"
            )
        if engine_event:
            if mode == "LOAD":
                values["throttle"] = 1.0
            elif mode == "COAST":
                values["throttle"] = 0.0
            elif mode == "BOTH":
                values["throttle"] = _finite_float(
                    host_values.get("EngineSimulation.throttle", 0.5),
                    "BOTH engine throttle",
                )
            else:
                raise CaptureError(f"unknown program mode {mode}")

        return dict(sorted(values.items()))

    def _validate_engine_probe_scenario(
        self,
        scenario: Mapping[str, Any],
        host_path: Sequence[Mapping[str, Any]],
    ) -> None:
        perspective = _string(
            scenario.get("perspective"), "engine probe perspective"
        )
        mode = _string(scenario.get("programMode"), "engine probe program mode")
        if perspective not in {"cabin", "exterior"} or mode not in {
            "LOAD",
            "COAST",
            "BOTH",
        }:
            raise CaptureError("engine probe context is invalid")
        if (
            scenario.get("initialHostPhaseFrames") != 0
            or scenario.get("requiredTailDrain") is not True
            or list(
                _array(
                    scenario.get("finiteTriggerRecords"),
                    "engine probe finite trigger records",
                )
            )
        ):
            raise CaptureError(
                "engine probe must start at frame zero and contain no finite triggers"
            )

        plan_perspectives = _object(
            self.plan.get("perspectives"), "plan engine perspectives"
        )
        engine_plan = _object(
            plan_perspectives.get(perspective),
            f"{perspective} engine plan",
        )
        event_path = self.engine_event_by_perspective[perspective]
        unique_parameters: dict[bytes, dict[str, float]] = {}
        for raw_node in _array(
            engine_plan.get("nodes"), f"{perspective} engine atlas nodes"
        ):
            node = _object(raw_node, "engine atlas node")
            raw_parameters = _object(
                node.get("parameters"), "engine atlas node parameters"
            )
            parameters: dict[str, float] = {}
            for raw_name, raw_value in raw_parameters.items():
                name = _string(raw_name, "engine atlas parameter name")
                parameters[name] = _finite_float(
                    raw_value, f"engine atlas parameter {name}"
                )
            if "throttle" in parameters and mode in {"LOAD", "COAST"}:
                parameters["throttle"] = 1.0 if mode == "LOAD" else 0.0
            parameters = dict(sorted(parameters.items()))
            unique_parameters.setdefault(
                _canonical_json_bytes(parameters), parameters
            )
        ordered_parameters = [
            unique_parameters[key] for key in sorted(unique_parameters)
        ]
        if not ordered_parameters:
            raise CaptureError("engine probe has no authored atlas nodes")

        expected_nodes: list[dict[str, Any]] = []
        expected_host_path: list[dict[str, Any]] = []
        for index, parameters in enumerate(ordered_parameters):
            host_frame = index * DSP_BUFFER_FRAMES * 2
            node_key = _engine_node_key(perspective, event_path, parameters)
            expected_nodes.append(
                {
                    "nodeKey": node_key,
                    "hostFrame": host_frame,
                    "parameters": parameters,
                }
            )
            expected_host_path.append(
                {
                    "hostFrame": host_frame,
                    "selectedPerspective": perspective,
                    "programMode": mode,
                    "hostValues": {},
                    "engineParameters": parameters,
                    "engineNodeKey": node_key,
                    "emissions": [],
                }
            )
        observed_nodes = [
            dict(_object(item, "engine probe node"))
            for item in _array(
                scenario.get("engineProbeNodes"), "engine probe nodes"
            )
        ]
        if observed_nodes != expected_nodes:
            raise CaptureError(
                "engine probe nodes differ from the exact program-mode atlas sweep"
            )
        if [dict(state) for state in host_path] != expected_host_path:
            raise CaptureError(
                "engine probe host path differs from its exact two-block node sweep"
            )

    @staticmethod
    def _direct_engine_parameters(
        state: Mapping[str, Any],
    ) -> dict[str, float]:
        raw_parameters = _object(
            state.get("engineParameters"), "engine probe parameters"
        )
        parameters: dict[str, float] = {}
        for raw_name, raw_value in raw_parameters.items():
            name = _string(raw_name, "engine probe parameter name")
            parameters[name] = _finite_float(
                raw_value, f"engine probe parameter {name}"
            )

        return dict(sorted(parameters.items()))

    def _engine_parameters_for_state(
        self,
        event_path: str,
        perspective: str,
        state: Mapping[str, Any],
    ) -> dict[str, float]:
        raw_by_perspective = state.get(
            "engineParametersByActivationPerspective"
        )
        if raw_by_perspective is not None:
            by_perspective = _object(
                raw_by_perspective,
                "engine parameters by activation perspective",
            )
            if set(by_perspective) != {"cabin", "exterior"}:
                raise CaptureError(
                    "camera engine parameter perspective matrix differs"
                )
            direct_state = {
                "engineParameters": _object(
                    by_perspective.get(perspective),
                    f"{perspective} direct engine parameters",
                )
            }
            parameters = self._direct_engine_parameters(direct_state)
            mode = str(state["programMode"])
            if "throttle" in parameters and mode in {"LOAD", "COAST"}:
                expected = 1.0 if mode == "LOAD" else 0.0
                if parameters["throttle"] != expected:
                    raise CaptureError(
                        f"{perspective} direct engine throttle differs from {mode}"
                    )

            return parameters

        return self._event_parameters(
            event_path,
            _object(state.get("hostValues", {}), "host values"),
            str(state["programMode"]),
            engine_event=True,
        )

    def _scenario_schedule(
        self, scenario: Mapping[str, Any], duration_frames: int
    ) -> tuple[list[dict[str, Any]], dict[int, EventSchedule], dict[int, Mapping[str, Any]]]:
        host_path = [
            _object(item, "scenario host state")
            for item in _array(scenario.get("hostPath"), "scenario host path")
        ]
        if not host_path:
            raise CaptureError(f"scenario {scenario.get('id')} host path is empty")
        initial_host_phase = _nonnegative_int(
            scenario.get("initialHostPhaseFrames"),
            "scenario initial host phase",
        )
        if initial_host_phase >= DSP_BUFFER_FRAMES:
            raise CaptureError("scenario initial host phase is outside one DSP block")
        if host_path[0].get("hostFrame") != initial_host_phase:
            raise CaptureError("scenario host path does not begin at its exact phase")
        delivery_states: dict[int, Mapping[str, Any]] = {}
        trajectory_kind = _string(
            scenario.get("trajectoryKind"), "scenario trajectory kind"
        )
        is_engine_probe = trajectory_kind == "source-audibility-probe"
        if is_engine_probe:
            self._validate_engine_probe_scenario(scenario, host_path)
        previous_host_frame: int | None = None
        for state in host_path:
            host_frame = _nonnegative_int(state.get("hostFrame"), "host frame")
            expected_delta = (
                DSP_BUFFER_FRAMES * 2 if is_engine_probe else HOST_TICK_FRAMES
            )
            if (
                previous_host_frame is not None
                and host_frame - previous_host_frame != expected_delta
            ):
                cadence = "two DSP blocks" if is_engine_probe else "200 Hz"
                raise CaptureError(
                    f"scenario host path is not contiguous at {cadence}"
                )
            previous_host_frame = host_frame
            delivery = _ceil_dsp(host_frame)
            if delivery in delivery_states:
                raise CaptureError(
                    "two host ticks collapse onto one DSP boundary; exact order is unavailable"
                )
            if delivery >= duration_frames:
                raise CaptureError("scenario duration ends before its host path")
            perspective = state.get("selectedPerspective")
            mode = state.get("programMode")
            if perspective not in {"cabin", "exterior"} or mode not in {
                "LOAD",
                "COAST",
                "BOTH",
            }:
                raise CaptureError("scenario host context is invalid")
            host_values = _object(state.get("hostValues", {}), "host values")
            for name, value in host_values.items():
                _finite_float(value, f"host value {name}")
            delivery_states[delivery] = state

        if is_engine_probe:
            perspective = str(scenario["perspective"])
            event_path = self.engine_event_by_perspective[perspective]
            parameters_by_frame = {
                frame: self._direct_engine_parameters(state)
                for frame, state in delivery_states.items()
            }
            first_start = min(delivery_states)
            lifecycle = ((first_start, "start"),)
            event_spec = {
                "eventName": event_path,
                "startFrame": first_start,
                "hostGainLinear": 1.0,
                "parameters": parameters_by_frame[first_start],
                "parameterUpdates": [
                    [frame, values]
                    for frame, values in parameters_by_frame.items()
                    if frame > first_start
                ],
                "lifecycleActions": [
                    {"frame": frame, "action": action}
                    for frame, action in lifecycle
                ],
            }
            schedule = EventSchedule(
                0,
                event_path,
                perspective,
                False,
                parameters_by_frame,
                lifecycle,
            )

            return [event_spec], {0: schedule}, delivery_states

        selected_perspectives = {
            str(state["selectedPerspective"]) for state in delivery_states.values()
        }
        event_specs: list[dict[str, Any]] = []
        schedules: dict[int, EventSchedule] = {}
        ordinal = 0
        for perspective in ("cabin", "exterior"):
            if perspective not in selected_perspectives:
                continue
            event_path = self.engine_event_by_perspective[perspective]
            selected_frames = [
                frame
                for frame, state in delivery_states.items()
                if state["selectedPerspective"] == perspective
            ]
            first_start = selected_frames[0]
            lifecycle: list[dict[str, Any]] = []
            prior_selected: str | None = None
            for frame, state in delivery_states.items():
                selected = str(state["selectedPerspective"])
                if selected != prior_selected:
                    if selected == perspective:
                        lifecycle.append({"frame": frame, "action": "start"})
                    elif prior_selected == perspective:
                        lifecycle.append(
                            {"frame": frame, "action": "stopAllowFadeout"}
                        )
                prior_selected = selected
            if not lifecycle or lifecycle[0] != {"frame": first_start, "action": "start"}:
                raise CaptureError(f"{event_path} lifecycle does not begin with start")
            parameters_by_frame = {
                frame: self._engine_parameters_for_state(
                    event_path,
                    perspective,
                    state,
                )
                for frame, state in delivery_states.items()
            }
            initial_parameters = parameters_by_frame[first_start]
            updates = [
                [frame, values]
                for frame, values in parameters_by_frame.items()
                if frame > first_start
            ]
            event_specs.append(
                {
                    "eventName": event_path,
                    "startFrame": first_start,
                    "hostGainLinear": 1.0,
                    "parameters": initial_parameters,
                    "parameterUpdates": updates,
                    "lifecycleActions": lifecycle,
                }
            )
            schedules[ordinal] = EventSchedule(
                ordinal,
                event_path,
                perspective,
                False,
                parameters_by_frame,
                tuple(
                    (int(action["frame"]), str(action["action"]))
                    for action in lifecycle
                ),
            )
            ordinal += 1

        engine_paths = set(self.engine_event_by_perspective.values())
        persistent_paths = sorted(
            {
                identity.event_path
                for identities in self.identities_by_event_and_name.values()
                for identity in identities
                if identity.event_path not in engine_paths
            }
        )
        session_start = min(delivery_states)
        for event_path in persistent_paths:
            parameters_by_frame = {
                frame: self._event_parameters(
                    event_path,
                    _object(state.get("hostValues", {}), "host values"),
                    str(state["programMode"]),
                    engine_event=False,
                )
                for frame, state in delivery_states.items()
            }
            event_specs.append(
                {
                    "eventName": event_path,
                    "startFrame": session_start,
                    "hostGainLinear": 1.0,
                    "parameters": parameters_by_frame[session_start],
                    "parameterUpdates": [
                        [frame, values]
                        for frame, values in parameters_by_frame.items()
                        if frame > session_start
                    ],
                    "lifecycleActions": [
                        {"frame": session_start, "action": "start"}
                    ],
                }
            )
            schedules[ordinal] = EventSchedule(
                ordinal,
                event_path,
                None,
                True,
                parameters_by_frame,
                ((session_start, "start"),),
            )
            ordinal += 1
        if not event_specs:
            raise CaptureError("scenario schedules no original-bank event")

        return event_specs, schedules, delivery_states

    def _maximum_scenario_duration(self, scenario: Mapping[str, Any]) -> int:
        host_path = [
            _object(item, "scenario host state")
            for item in _array(scenario.get("hostPath"), "scenario host path")
        ]
        final_host_delivery = max(
            _ceil_dsp(_nonnegative_int(item.get("hostFrame"), "host frame"))
            for item in host_path
        )
        if scenario.get("trajectoryKind") == "source-audibility-probe":
            return final_host_delivery + DSP_BUFFER_FRAMES * 2
        maximum_end = final_host_delivery
        for raw in _array(
            scenario.get("finiteTriggerRecords"), "finite trigger records"
        ):
            trigger = _object(raw, "finite trigger record")
            group_key = _string(trigger.get("groupKey"), "finite group key")
            group_id = self.groups.get(group_key)
            if group_id is None:
                raise CaptureError(f"finite trigger references unknown {group_key}")
            maximum_capture = max(
                node.duration_frames
                for identity in self.identities_by_binding.values()
                if identity.group_key == group_key
                for node in identity.nodes
            )
            start = _ceil_dsp(
                _nonnegative_int(trigger.get("hostFrame"), "finite trigger frame")
            )
            maximum_end = max(maximum_end, start + maximum_capture)

        return _ceil_dsp(maximum_end) + DSP_BUFFER_FRAMES

    def capture(self) -> Mapping[str, Any]:
        original_bank_sha = _sha256(self.bank_path)
        scenarios: list[Mapping[str, Any]] = []
        for index, raw in enumerate(
            _array(self.request.get("scenarios"), "capture scenarios"), start=1
        ):
            scenario = _object(raw, "capture scenario")
            print(
                f"causal original-bank capture {index}/"
                f"{len(self.request['scenarios'])}: {scenario['id']}",
                flush=True,
            )
            scenarios.append(self._capture_scenario(scenario))
            if _sha256(self.bank_path) != original_bank_sha:
                raise CaptureError("original bank changed during native capture")
        if _sha256(self.bank_path) != self.request.get("bankSha256"):
            raise CaptureError("original bank changed during native capture")
        api_root = Path(
            os.environ.get(FMOD_API_ROOT_ENV, "")
        ).expanduser().resolve(strict=True)
        core_library = api_root / "lowlevel" / "lib" / "libfmod.dylib"
        studio_library = api_root / "studio" / "lib" / "libfmodstudio.dylib"
        for path in (core_library, studio_library):
            if not path.is_file():
                raise CaptureError(f"FMOD native library is absent: {path}")
        if not self.used_source_binding_oracle_shas:
            raise CaptureError("native capture emitted no source-binding evidence")
        missing_oracles = (
            self.used_source_binding_oracle_shas
            - set(self.source_binding_oracles_by_sha256)
        )
        if missing_oracles:
            raise CaptureError(
                "emitted voices reference absent source-binding oracles"
            )
        for oracle_sha in self.used_source_binding_oracle_shas:
            if hashlib.sha256(
                _canonical_json_bytes(
                    self.source_binding_oracles_by_sha256[oracle_sha]
                )
            ).hexdigest() != oracle_sha:
                raise CaptureError("source-binding oracle registry hash differs")
        trace = {
            "schema": TRACE_SCHEMA,
            "status": "PASS",
            "evidenceKind": "nativeOriginalBankFmodNrtSession",
            "syntheticEvidence": False,
            "requestSha256": self.request["requestSha256"],
            "atlasFamilyId": self.request["atlasFamilyId"],
            "planSha256": self.request["planSha256"],
            "bankPath": self.request["bankPath"],
            "bankSha256": self.request["bankSha256"],
            "graphSha256": self.request["graphSha256"],
            "realizationSha256": self.request["realizationSha256"],
            "sourceBankKind": "originalAssettoCarBank",
            "nativeRuntime": {
                "architecture": "x86_64",
                "outputMode": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
                "sampleRateHz": SAMPLE_RATE_HZ,
                "dspBufferFrames": DSP_BUFFER_FRAMES,
                "studioLogicalChannelCap": ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP,
                "softwareRealChannelBudget": ASSETTO_SOFTWARE_REAL_CHANNEL_BUDGET,
                "coreLibrarySha256": _sha256(core_library),
                "studioLibrarySha256": _sha256(studio_library),
            },
            "sourceBindingOraclesBySha256": {
                oracle_sha: self.source_binding_oracles_by_sha256[oracle_sha]
                for oracle_sha in sorted(
                    self.used_source_binding_oracle_shas
                )
            },
            "scenarios": scenarios,
        }

        return _self_hash(trace, "traceSha256")

    def _capture_scenario(self, scenario: Mapping[str, Any]) -> Mapping[str, Any]:
        kind = _string(scenario.get("trajectoryKind"), "trajectory kind")
        minimum_duration = self._maximum_scenario_duration(scenario)
        duration_candidates = (
            [
                max(minimum_duration, candidate)
                for candidate in CAMERA_CAPTURE_FRAME_CANDIDATES
            ]
            if kind == "camera-switch-tail"
            else [minimum_duration]
        )
        last_camera_failure: str | None = None
        for duration in dict.fromkeys(duration_candidates):
            event_specs, schedules, delivery_states = self._scenario_schedule(
                scenario, duration
            )
            with tempfile.NamedTemporaryFile(
                prefix="causal-original-bank-",
                suffix=".wav",
                dir=self.working_directory,
                delete=False,
            ) as temporary:
                output_path = Path(temporary.name)
            output_path.unlink(missing_ok=True)
            try:
                rendered = self.renderer.render_event_mix(
                    self.bank_path,
                    output_path,
                    events=event_specs,
                    duration_frames=duration,
                    warmup_frames=0,
                )
            finally:
                output_path.unlink(missing_ok=True)
            expected_event_paths = [
                str(spec["eventName"]) for spec in event_specs
            ]
            if list(rendered.event_paths) != expected_event_paths:
                raise CaptureError(
                    "FMOD resolved event paths differ from the exact requested paths"
                )
            try:
                result = self._translate_render(
                    scenario,
                    rendered.channel_snapshots,
                    schedules,
                    delivery_states,
                )
                if kind == "camera-switch-tail":
                    result["cameraSwitchEvidence"] = self._camera_evidence(
                        scenario, result["snapshots"], rendered.channel_snapshots, schedules
                    )

                return result
            except CaptureError as exc:
                if kind != "camera-switch-tail" or "camera fade tail" not in str(exc):
                    raise
                last_camera_failure = str(exc)
        raise CaptureError(
            last_camera_failure or "camera fade tail could not be captured"
        )

    def _translate_render(
        self,
        scenario: Mapping[str, Any],
        raw_snapshots: Sequence[Mapping[str, Any]],
        schedules: Mapping[int, EventSchedule],
        delivery_states: Mapping[int, Mapping[str, Any]],
    ) -> dict[str, Any]:
        if not raw_snapshots:
            raise CaptureError("native render returned no channel snapshots")
        frames = [
            _nonnegative_int(item.get("afterDspBlockStartFrame"), "DSP frame")
            for item in raw_snapshots
        ]
        if frames != list(range(0, frames[-1] + DSP_BUFFER_FRAMES, DSP_BUFFER_FRAMES)):
            raise CaptureError("native render snapshots are not contiguous DSP blocks")

        event_instance_tokens: dict[int, set[int]] = {
            ordinal: set() for ordinal in schedules
        }
        for raw_snapshot in raw_snapshots:
            frame = _nonnegative_int(
                raw_snapshot.get("afterDspBlockStartFrame"),
                "native lifecycle frame",
            )
            raw_tokens = _object(
                raw_snapshot.get("eventInstanceTokensByOrdinal"),
                "native EventInstance tokens",
            )
            if set(raw_tokens) != {str(ordinal) for ordinal in schedules}:
                raise CaptureError(
                    "native prepared EventInstance token coverage differs"
                )
            for ordinal in schedules:
                token = _nonnegative_int(
                    raw_tokens[str(ordinal)], "native EventInstance token"
                )
                if token == 0:
                    raise CaptureError("native EventInstance token is null")
                event_instance_tokens[ordinal].add(token)
            expected_started = sorted(
                ordinal
                for ordinal, schedule in schedules.items()
                if (frame, "start") in schedule.lifecycle_actions
            )
            expected_stopped = sorted(
                ordinal
                for ordinal, schedule in schedules.items()
                if any(
                    action_frame == frame
                    and action in {"stopAllowFadeout", "stopImmediate"}
                    for action_frame, action in schedule.lifecycle_actions
                )
            )
            observed_started = [
                _nonnegative_int(value, "native started EventInstance ordinal")
                for value in _array(
                    raw_snapshot.get("startedEventOrdinals"),
                    "native started EventInstance ordinals",
                )
            ]
            observed_stopped = [
                _nonnegative_int(value, "native stopped EventInstance ordinal")
                for value in _array(
                    raw_snapshot.get("stoppedEventOrdinals"),
                    "native stopped EventInstance ordinals",
                )
            ]
            if (
                observed_started != expected_started
                or observed_stopped != expected_stopped
            ):
                raise CaptureError(
                    "native EventInstance start/stop records differ from the exact schedule"
                )
        if (
            any(len(tokens) != 1 for tokens in event_instance_tokens.values())
            or len(
                {
                    next(iter(tokens))
                    for tokens in event_instance_tokens.values()
                }
            )
            != len(event_instance_tokens)
        ):
            raise CaptureError(
                "native prepared EventInstance identities are unstable or aliased"
            )
        prepared_event_instance_tokens = {
            str(ordinal): next(iter(tokens))
            for ordinal, tokens in sorted(event_instance_tokens.items())
        }

        bound_by_frame: dict[int, list[BoundChannel]] = {}
        generation_by_live_key: dict[tuple[int, int, str], int] = {}
        binding_by_live_key: dict[
            tuple[int, int, str], tuple[SourceIdentity, str]
        ] = {}
        previous_live_keys: set[tuple[int, int, str]] = set()
        generation_counter = 0
        for raw_snapshot in raw_snapshots:
            frame = int(raw_snapshot["afterDspBlockStartFrame"])
            channels = [
                _object(item, "active native channel")
                for item in _array(
                    raw_snapshot.get("activeChannels"), "active native channels"
                )
            ]
            logical = _nonnegative_int(
                raw_snapshot.get("logicalChannels"), "FMOD logical channels"
            )
            real = _nonnegative_int(
                raw_snapshot.get("realChannels"), "FMOD real channels"
            )
            if logical != len(channels):
                raise CaptureError(
                    "FMOD logical demand differs from complete Core channel enumeration"
                )
            if real != sum(not bool(item.get("isVirtual")) for item in channels):
                raise CaptureError(
                    "FMOD real demand differs from per-channel virtual state"
                )
            current_live_keys: set[tuple[int, int, str]] = set()
            bound: list[BoundChannel] = []
            for channel in channels:
                ordinal = _nonnegative_int(
                    channel.get("eventOrdinal"), "channel event ordinal"
                )
                schedule = schedules.get(ordinal)
                if schedule is None:
                    raise CaptureError("native channel has no exact EventInstance owner")
                sound_name = _string(channel.get("soundName"), "channel sound identity")
                parameters = self._parameters_at_frame(schedule, frame)
                pointer = _nonnegative_int(
                    channel.get("channelPointer"), "channel pointer"
                )
                live_key = (ordinal, pointer, sound_name)
                current_live_keys.add(live_key)
                if live_key not in previous_live_keys:
                    generation_counter += 1
                    generation_by_live_key[live_key] = generation_counter
                    binding_by_live_key[live_key] = self._resolve_source_identity(
                        schedule.event_path, sound_name, parameters
                    )
                identity, oracle_sha = binding_by_live_key[live_key]
                if scenario.get("trajectoryKind") == "source-audibility-probe" and (
                    identity.kind != "engineContinuous"
                    or identity.activation_perspective != scenario.get("perspective")
                ):
                    raise CaptureError(
                        "engine source-audibility probe exposed a non-target voice"
                    )
                generation = generation_by_live_key[live_key]
                token = (
                    f"native:{scenario['id']}:{ordinal}:{pointer}:"
                    f"{generation}:{identity.source_guid}"
                )
                bound.append(
                    BoundChannel(channel, identity, token, oracle_sha, None)
                )
            previous_live_keys = current_live_keys
            binding_by_live_key = {
                key: value
                for key, value in binding_by_live_key.items()
                if key in current_live_keys
            }
            bound_by_frame[frame] = bound

        rings, ring_by_generation = self._materialize_rings(
            scenario, bound_by_frame, schedules
        )
        snapshots: list[dict[str, Any]] = []
        for raw_snapshot in raw_snapshots:
            frame = int(raw_snapshot["afterDspBlockStartFrame"])
            state = self._state_at_frame(delivery_states, frame)
            selected = str(state["selectedPerspective"])
            mode = str(state["programMode"])
            active_rings = [
                ring
                for ring in rings
                if ring.start_frame <= frame < ring.end_frame_exclusive
            ]
            voices: list[dict[str, Any]] = []
            active_continuous_keys: set[str] = set()
            retained_continuous: dict[str, set[str]] = {
                "cabin": set(),
                "exterior": set(),
            }
            engine_sources: dict[str, list[str]] = {"cabin": [], "exterior": []}
            for channel in bound_by_frame[frame]:
                generation_key = self._channel_generation_key(channel)
                ring_id = ring_by_generation.get(generation_key)
                identity = channel.identity
                if identity.kind == "effectFinite":
                    if ring_id is None:
                        raise CaptureError(
                            "original-bank finite voice has no exact host-triggered logical ring"
                        )
                    if ring_id not in {ring.instance_id for ring in active_rings}:
                        raise CaptureError(
                            "original-bank finite voice outlives its exact captured ring"
                        )
                elif ring_id is not None:
                    raise CaptureError("non-finite voice was assigned to a logical ring")
                if identity.kind == "engineContinuous":
                    assert identity.activation_perspective is not None
                    engine_sources[identity.activation_perspective].append(
                        identity.source_guid
                    )
                elif identity.kind == "effectContinuous":
                    parameters = self._parameters_at_frame(
                        schedules[int(channel.raw["eventOrdinal"])], frame
                    )
                    node_keys = self._active_node_keys(identity, parameters)
                    if selected in identity.perspectives:
                        active_continuous_keys.update(node_keys)
                    for perspective in identity.perspectives:
                        retained_continuous[perspective].update(node_keys)
                voice = {
                    "voiceToken": channel.voice_token,
                    "kind": identity.kind,
                    "sourceGuid": identity.source_guid,
                    "eventPath": identity.event_path,
                    "isVirtual": bool(channel.raw.get("isVirtual")),
                    "audibility": _finite_float(
                        channel.raw.get("audibility"), "channel audibility"
                    ),
                    "authoredRouteGain": _finite_float(
                        channel.raw.get("authoredRouteGain"),
                        "channel authored route gain",
                    ),
                    "sourceBindingOracleSha256": (
                        channel.source_binding_oracle_sha256
                    ),
                }
                self.used_source_binding_oracle_shas.add(
                    channel.source_binding_oracle_sha256
                )
                if identity.kind == "engineContinuous":
                    voice["activationPerspective"] = identity.activation_perspective
                else:
                    voice["authoredBindingKey"] = identity.authored_binding_key
                if identity.kind == "effectFinite":
                    voice["logicalRingInstanceId"] = ring_id
                if voice["isVirtual"]:
                    identity_field = (
                        {
                            "activationPerspective": identity.activation_perspective
                        }
                        if identity.kind == "engineContinuous"
                        else {"authoredBindingKey": identity.authored_binding_key}
                    )
                    voice["virtualizationProof"] = {
                        "schema": VIRTUALIZATION_SCHEMA,
                        "eventPath": identity.event_path,
                        "sourceGuid": identity.source_guid,
                        **identity_field,
                        "isVirtualReportedByFmod": True,
                        "measuredAudibility": voice["audibility"],
                        "measuredAuthoredRouteGain": voice["authoredRouteGain"],
                        "audibilityEpsilon": VIRTUAL_EPSILON,
                        "authoredRouteGainEpsilon": VIRTUAL_EPSILON,
                    }
                    if (
                        voice["audibility"] > VIRTUAL_EPSILON
                        or voice["authoredRouteGain"] > VIRTUAL_EPSILON
                    ):
                        raise CaptureError(
                            "FMOD virtual voice is audibly expected at its exact node"
                        )
                voices.append(voice)
            snapshots.append(
                {
                    "afterDspBlockStartFrame": frame,
                    "selectedPerspective": selected,
                    "programMode": mode,
                    "engineProgramThrottle": self._engine_program_throttle(
                        state, mode
                    ),
                    "voices": voices,
                    "systemLogicalChannels": int(raw_snapshot["logicalChannels"]),
                    "systemRealChannels": int(raw_snapshot["realChannels"]),
                    "engineActiveSourceGuidsByActivationPerspective": {
                        key: sorted(values)
                        for key, values in engine_sources.items()
                    },
                    "continuousEffectNodeKeys": sorted(active_continuous_keys),
                    "retainedContinuousEffectNodeKeysByActivationPerspective": {
                        key: sorted(values)
                        for key, values in retained_continuous.items()
                    },
                    "finiteLogicalRings": [
                        {
                            "instanceId": ring.instance_id,
                            "eventPath": ring.event_path,
                            "groupKey": ring.group_key,
                            "groupId": ring.group_id,
                            "activationPerspective": ring.activation_perspective,
                            "triggerHostFrame": ring.trigger_host_frame,
                            "startFrame": ring.start_frame,
                            "endFrameExclusive": ring.end_frame_exclusive,
                            "contributorNodeKeys": list(
                                ring.contributor_node_keys
                            ),
                        }
                        for ring in active_rings
                    ],
                }
            )
            if scenario.get("trajectoryKind") == "source-audibility-probe":
                snapshots[-1]["engineNodeKey"] = _string(
                    state.get("engineNodeKey"), "engine probe node key"
                )
                snapshots[-1]["engineParameters"] = (
                    self._direct_engine_parameters(state)
                )
        if rings and max(ring.end_frame_exclusive for ring in rings) > frames[-1]:
            raise CaptureError("native capture ends before its final finite tail")
        if any(
            channel.identity.kind == "effectFinite"
            for channel in bound_by_frame[frames[-1]]
        ):
            raise CaptureError("native capture final snapshot still owns a finite voice")
        if snapshots[-1]["finiteLogicalRings"]:
            raise CaptureError("native capture final snapshot still owns a finite ring")
        provenance = {
            "bankPath": self.request["bankPath"],
            "bankSha256": self.request["bankSha256"],
            "graphSha256": self.request["graphSha256"],
            "realizationSha256": self.request["realizationSha256"],
            "eventInstances": "originalBankEventDescriptions",
            "channelEnumeration": (
                "FMOD_System_GetChannelsPlaying+perChannelVirtualState"
            ),
            "sourceBinding": (
                "callbackSoundIdentity+eventInstanceChannelGroup+"
                "sourceSoloAuthoredBindingOracle"
            ),
            "diagnosticNamesUsedForClassification": False,
            "appliedHostPathSha256": hashlib.sha256(
                _canonical_json_bytes(scenario["hostPath"])
            ).hexdigest(),
            "appliedFiniteTriggerRecordsSha256": hashlib.sha256(
                _canonical_json_bytes(scenario["finiteTriggerRecords"])
            ).hexdigest(),
        }
        return {
            "id": scenario["id"],
            "perspective": scenario["perspective"],
            "programMode": scenario["programMode"],
            "trajectoryKind": scenario["trajectoryKind"],
            "initialHostPhaseFrames": scenario["initialHostPhaseFrames"],
            "scenarioRequestSha256": scenario["scenarioRequestSha256"],
            "tailDrained": True,
            "preparedEventInstanceTokensByOrdinal": (
                prepared_event_instance_tokens
            ),
            "snapshots": snapshots,
            "originalBankEvidence": provenance,
        }

    @staticmethod
    def _state_at_frame(
        delivery_states: Mapping[int, Mapping[str, Any]], frame: int
    ) -> Mapping[str, Any]:
        delivered = [state for delivery, state in delivery_states.items() if delivery <= frame]
        if delivered:
            return delivered[-1]

        return next(iter(delivery_states.values()))

    @staticmethod
    def _parameters_at_frame(
        schedule: EventSchedule, frame: int
    ) -> Mapping[str, float]:
        delivered = [
            values
            for delivery, values in schedule.parameters_by_delivery_frame.items()
            if delivery <= frame
        ]
        if delivered:
            return delivered[-1]

        return next(iter(schedule.parameters_by_delivery_frame.values()))

    @staticmethod
    def _engine_program_throttle(state: Mapping[str, Any], mode: str) -> float:
        if mode == "LOAD":
            return 1.0
        if mode == "COAST":
            return 0.0
        engine_parameters = state.get("engineParameters")
        if isinstance(engine_parameters, Mapping) and "throttle" in engine_parameters:
            value = engine_parameters["throttle"]
            return max(
                0.0,
                min(1.0, _finite_float(value, "BOTH engine probe throttle")),
            )
        host_values = state.get("hostValues", {})
        if isinstance(host_values, Mapping):
            value = host_values.get("EngineSimulation.throttle", 0.5)
            return max(0.0, min(1.0, _finite_float(value, "BOTH throttle")))

        return 0.5

    def _resolve_source_identity(
        self,
        event_path: str,
        sound_name: str,
        parameters: Mapping[str, float],
    ) -> tuple[SourceIdentity, str]:
        candidates = self.identities_by_event_and_name.get((event_path, sound_name), ())
        if not candidates:
            raise CaptureError(
                f"runtime callback {sound_name!r} in {event_path} has no exact catalog identity"
            )
        # The callback string is only a join key into candidates.  It cannot
        # choose the identity: each candidate needs an independent source-solo
        # proof, and exactly one authored occurrence must remain possible.
        proven: list[tuple[SourceIdentity, str]] = []
        requires_node_specific_disambiguation = len(candidates) > 1
        for candidate in candidates:
            if (
                not requires_node_specific_disambiguation
                and candidate.authored_binding_key is not None
            ):
                existing = self.realization_oracles.get(
                    (candidate.authored_binding_key, candidate.source_guid)
                )
                if existing is not None:
                    proven.append((candidate, existing))
                    continue
            try:
                oracle_sha = self._probe_source_solo(candidate, parameters)
            except CaptureError:
                continue
            proven.append((candidate, oracle_sha))
        unique = {
            (
                candidate.source_guid,
                candidate.authored_binding_key,
                candidate.activation_perspective,
            ): (candidate, oracle_sha)
            for candidate, oracle_sha in proven
        }
        if len(unique) != 1:
            raise CaptureError(
                f"runtime callback {sound_name!r} in {event_path} has "
                f"{len(unique)} source-solo-authored identities"
            )

        resolved, oracle_sha = next(iter(unique.values()))
        if (
            len(oracle_sha) != 64
            or any(character not in "0123456789abcdef" for character in oracle_sha)
        ):
            raise CaptureError("source-binding oracle SHA-256 is invalid")

        return resolved, oracle_sha

    def _probe_source_solo(
        self, identity: SourceIdentity, parameters: Mapping[str, float]
    ) -> str:
        cache_key = (
            identity.event_path,
            identity.source_guid,
            identity.diagnostic_name,
            identity.authored_binding_key or "",
            identity.activation_perspective or "",
            hashlib.sha256(
                _canonical_json_bytes(dict(sorted(parameters.items())))
            ).hexdigest(),
        )
        cached = self.source_solo_oracle_cache.get(cache_key)
        if cached is not None:
            return cached
        reachable = self.graph_event_waveforms.get(identity.event_path, frozenset())
        if identity.source_guid not in reachable:
            raise CaptureError("source-solo target is absent from exact event graph")
        muted = set(reachable) - {identity.source_guid}
        with tempfile.TemporaryDirectory(
            prefix="causal-source-solo-", dir=self.working_directory
        ) as temporary_text:
            temporary = Path(temporary_text)
            if muted:
                disabled = fully_muted_multi_instrument_guids(
                    dict(self.graph), muted
                )
                isolated = create_isolated_bank_copy(
                    self.bank_path,
                    dict(self.graph),
                    muted,
                    temporary / self.bank_path.name,
                    disabled_parent_guids=disabled,
                )
                probe_bank = isolated.output_path
                derivative_sha = isolated.output_sha256
                differing_offsets = list(isolated.differing_byte_offsets)
            else:
                disabled = set()
                probe_bank = self.bank_path
                derivative_sha = self.request["bankSha256"]
                differing_offsets = []
            output = temporary / "source-solo.wav"
            try:
                rendered = self.renderer.render_event(
                    probe_bank,
                    identity.event_path,
                    output,
                    parameters=dict(parameters),
                    duration_frames=SOURCE_SOLO_PROBE_FRAMES,
                    warmup_frames=0,
                    required_sound_name=identity.diagnostic_name,
                    maximum_takes=SOURCE_SOLO_MAXIMUM_TAKES,
                    event_id_lookup_bank_path=self.bank_path,
                )
            except Exception as exc:
                raise CaptureError(
                    f"source-solo oracle could not activate {identity.source_guid}"
                ) from exc
            scheduled = list(rendered.scheduled_sound_names)
            if identity.diagnostic_name not in scheduled or set(scheduled) != {
                identity.diagnostic_name
            }:
                raise CaptureError(
                    f"source-solo oracle for {identity.source_guid} is contaminated"
                )
            evidence = {
                "schema": "byd-original-bank-source-solo-binding-oracle-v1",
                "originalBankSha256": self.request["bankSha256"],
                "graphSha256": self.request["graphSha256"],
                "eventPath": identity.event_path,
                "sourceGuid": identity.source_guid,
                "authoredBindingKey": identity.authored_binding_key,
                "activationPerspective": identity.activation_perspective,
                "callbackSoundIdentity": identity.diagnostic_name,
                "parameters": dict(parameters),
                "mutedReachableWaveformGuids": sorted(muted),
                "disabledFullyMutedParentGuids": sorted(disabled),
                "derivativeBankSha256": derivative_sha,
                "derivativeDifferingByteOffsets": differing_offsets,
                "scheduledSoundIdentities": scheduled,
                "originalBankUnchangedAfterProbe": (
                    _sha256(self.bank_path) == self.request["bankSha256"]
                ),
            }
            if evidence["originalBankUnchangedAfterProbe"] is not True:
                raise CaptureError("source-solo probe changed the original bank")
            oracle_sha = self._register_source_binding_oracle(evidence)
            self.source_solo_oracle_cache[cache_key] = oracle_sha

            return oracle_sha

    def _active_node_keys(
        self, identity: SourceIdentity, parameters: Mapping[str, float]
    ) -> tuple[str, ...]:
        if not identity.nodes:
            raise CaptureError("effect source identity has no atlas nodes")
        bounds: dict[str, tuple[float, float, float]] = {}
        for parameter, axis in identity.parameter_axes.items():
            raw = parameters.get(parameter, identity.parameters.get(parameter))
            if raw is None:
                raise CaptureError(
                    f"{identity.authored_binding_key} has no runtime value for {parameter}"
                )
            value = max(axis[0], min(axis[-1], float(raw)))
            lower_index = max(0, bisect_right(axis, value) - 1)
            upper_index = min(lower_index + 1, len(axis) - 1)
            lower = axis[lower_index]
            upper = axis[upper_index]
            fraction = 0.0 if upper == lower else (value - lower) / (upper - lower)
            bounds[parameter] = (lower, upper, fraction)
        active: list[str] = []
        for node in identity.nodes:
            gain = 1.0
            for parameter, (lower, upper, fraction) in bounds.items():
                coordinate = node.parameters.get(parameter)
                if coordinate == lower:
                    gain *= 1.0 - fraction
                elif coordinate == upper:
                    gain *= fraction
                else:
                    gain = 0.0
                    break
            if gain > 0.0:
                active.append(node.key)
        if not active:
            raise CaptureError(
                f"{identity.authored_binding_key} runtime source has no exact positive atlas corner"
            )

        return tuple(sorted(active))

    @staticmethod
    def _channel_generation_key(
        channel: BoundChannel,
    ) -> tuple[int, int, int, str]:
        parts = channel.voice_token.split(":")
        # native:<scenario-with-colons?>:<ordinal>:<pointer>:<generation>:<guid>
        # Scenario ids can contain colons, so the stable native values are
        # taken directly from the raw channel and the generation is the field
        # immediately before the GUID.
        generation = int(parts[-2])
        return (
            int(channel.raw["eventOrdinal"]),
            int(channel.raw["channelPointer"]),
            generation,
            channel.identity.source_guid,
        )

    def _materialize_rings(
        self,
        scenario: Mapping[str, Any],
        bound_by_frame: Mapping[int, list[BoundChannel]],
        schedules: Mapping[int, EventSchedule],
    ) -> tuple[list[RingDefinition], dict[tuple[int, int, int, str], str]]:
        triggers_by_frame_and_group: dict[
            tuple[int, str], list[Mapping[str, Any]]
        ] = defaultdict(list)
        for raw in _array(
            scenario.get("finiteTriggerRecords"), "finite trigger records"
        ):
            trigger = _object(raw, "finite trigger record")
            host_frame = _nonnegative_int(trigger.get("hostFrame"), "trigger frame")
            group_key = _string(trigger.get("groupKey"), "trigger group key")
            activation = _string(
                trigger.get("activationPerspective"), "trigger perspective"
            )
            if activation not in {"cabin", "exterior"}:
                raise CaptureError("trigger activation perspective differs")
            triggers_by_frame_and_group[(_ceil_dsp(host_frame), group_key)].append(
                trigger
            )

        first_frame_by_generation: dict[tuple[int, int, int, str], int] = {}
        channel_by_generation: dict[tuple[int, int, int, str], BoundChannel] = {}
        for frame, channels in bound_by_frame.items():
            for channel in channels:
                if channel.identity.kind != "effectFinite":
                    continue
                key = self._channel_generation_key(channel)
                first_frame_by_generation.setdefault(key, frame)
                channel_by_generation[key] = channel
        unassigned = set(first_frame_by_generation)
        rings: list[RingDefinition] = []
        ring_by_generation: dict[tuple[int, int, int, str], str] = {}
        for (start_frame, group_key), triggers in sorted(
            triggers_by_frame_and_group.items()
        ):
            if len(triggers) != 1:
                raise CaptureError(
                    "multiple same-group semantic triggers share one DSP boundary; "
                    "raw FMOD channel generations cannot be attributed exactly"
                )
            candidates = sorted(
                (
                    key
                    for key in unassigned
                    if first_frame_by_generation[key] == start_frame
                    and channel_by_generation[key].identity.group_key == group_key
                ),
                key=lambda key: (key[0], key[1], key[2], key[3]),
            )
            if not candidates:
                raise CaptureError(
                    f"host trigger {group_key} produced no attributable original-bank voice"
                )
            identities = [channel_by_generation[key].identity for key in candidates]
            composition = {identity.group_composition for identity in identities}
            if len(composition) != 1:
                raise CaptureError(f"{group_key} runtime composition is ambiguous")
            if next(iter(composition)) == "playlistAlternative" and len(candidates) != 1:
                raise CaptureError(
                    f"playlist group {group_key} emitted multiple source voices"
                )
            trigger = triggers[0]
            activation = str(trigger["activationPerspective"])
            contributor_keys: set[str] = set()
            maximum_duration = 0
            for key in candidates:
                channel = channel_by_generation[key]
                identity = channel.identity
                if activation not in identity.perspectives:
                    raise CaptureError(
                        f"{group_key} selected a source outside {activation} scope"
                    )
                parameters = self._parameters_at_frame(
                    schedules[int(channel.raw["eventOrdinal"])], start_frame
                )
                active_node_keys = self._active_node_keys(identity, parameters)
                contributor_keys.update(active_node_keys)
                by_key = {node.key: node for node in identity.nodes}
                maximum_duration = max(
                    maximum_duration,
                    *(by_key[node_key].duration_frames for node_key in active_node_keys),
                )
            ring_id = f"{scenario['id']}-ring-{len(rings)}"
            event_path, group_id, _group_composition = self.groups[group_key]
            ring = RingDefinition(
                instance_id=ring_id,
                event_path=event_path,
                group_key=group_key,
                group_id=group_id,
                activation_perspective=activation,
                trigger_host_frame=int(trigger["hostFrame"]),
                start_frame=start_frame,
                end_frame_exclusive=start_frame + maximum_duration,
                contributor_node_keys=tuple(sorted(contributor_keys)),
                channel_generation_keys=frozenset(candidates),
            )
            rings.append(ring)
            for key in candidates:
                ring_by_generation[key] = ring_id
            unassigned.difference_update(candidates)
        if unassigned:
            raise CaptureError(
                "original-bank finite voices started without exact host trigger records"
            )
        if len(rings) != sum(len(values) for values in triggers_by_frame_and_group.values()):
            raise CaptureError("finite logical ring count differs from trigger records")

        return rings, ring_by_generation

    def _camera_evidence(
        self,
        scenario: Mapping[str, Any],
        snapshots: Sequence[Mapping[str, Any]],
        raw_snapshots: Sequence[Mapping[str, Any]],
        schedules: Mapping[int, EventSchedule],
    ) -> Mapping[str, Any]:
        camera = _object(scenario.get("cameraSwitch"), "camera switch")
        if camera.get("oldEngineStopMode") != CAMERA_STOP_MODE:
            raise CaptureError("camera stop mode differs")
        host_frame = _nonnegative_int(camera.get("hostFrame"), "camera host frame")
        dsp_frame = _ceil_dsp(host_frame)
        old_perspective = _string(
            camera.get("fromPerspective"), "old camera perspective"
        )
        new_perspective = _string(
            camera.get("toPerspective"), "new camera perspective"
        )
        old_engine_ordinals = [
            ordinal
            for ordinal, schedule in schedules.items()
            if schedule.engine_perspective == old_perspective
        ]
        new_engine_ordinals = [
            ordinal
            for ordinal, schedule in schedules.items()
            if schedule.engine_perspective == new_perspective
        ]
        if len(old_engine_ordinals) != 1 or len(new_engine_ordinals) != 1:
            raise CaptureError(
                "camera switch does not have one exact old and new engine EventInstance"
            )
        old_ordinal = old_engine_ordinals[0]
        new_ordinal = new_engine_ordinals[0]
        if (
            (dsp_frame, "stopAllowFadeout")
            not in schedules[old_ordinal].lifecycle_actions
            or (dsp_frame, "start")
            not in schedules[new_ordinal].lifecycle_actions
        ):
            raise CaptureError(
                "camera engine lifecycle is not exact STOP_ALLOWFADEOUT/start"
            )
        raw_switch_snapshots = [
            raw
            for raw in raw_snapshots
            if raw.get("afterDspBlockStartFrame") == dsp_frame
        ]
        if len(raw_switch_snapshots) != 1:
            raise CaptureError("camera switch has no unique native DSP snapshot")
        raw_switch = raw_switch_snapshots[0]
        started_ordinals = [
            _nonnegative_int(value, "started EventInstance ordinal")
            for value in _array(
                raw_switch.get("startedEventOrdinals"),
                "started EventInstance ordinals",
            )
        ]
        stopped_ordinals = [
            _nonnegative_int(value, "stopped EventInstance ordinal")
            for value in _array(
                raw_switch.get("stoppedEventOrdinals"),
                "stopped EventInstance ordinals",
            )
        ]
        if started_ordinals != [new_ordinal] or stopped_ordinals != [old_ordinal]:
            raise CaptureError(
                "native camera DSP did not start/stop the exact new/old engine ordinals"
            )
        old_tail_frames: list[int] = []
        overlap_frames: list[int] = []
        first_drain: int | None = None
        tail_has_drained = False
        for snapshot in snapshots:
            frame = int(snapshot["afterDspBlockStartFrame"])
            if frame < dsp_frame:
                continue
            active = {
                str(voice.get("activationPerspective"))
                for voice in snapshot["voices"]
                if voice.get("kind") == "engineContinuous"
            }
            if old_perspective in active:
                if tail_has_drained:
                    raise CaptureError(
                        "camera fade tail reappeared after its first drain boundary"
                    )
                old_tail_frames.append(frame)
                if new_perspective in active:
                    overlap_frames.append(frame)
            elif old_tail_frames and first_drain is None:
                first_drain = frame
                tail_has_drained = True
        persistent_ordinals = {
            ordinal
            for ordinal, schedule in schedules.items()
            if schedule.persistent_effect
        }
        tokens_by_ordinal: dict[int, set[int]] = {
            ordinal: set()
            for ordinal in persistent_ordinals | {old_ordinal, new_ordinal}
        }
        for raw in raw_snapshots:
            tokens = _object(
                raw.get("eventInstanceTokensByOrdinal"),
                "native EventInstance tokens",
            )
            for ordinal in tokens_by_ordinal:
                token = _nonnegative_int(
                    tokens.get(str(ordinal)), "prepared EventInstance token"
                )
                if token == 0:
                    raise CaptureError("prepared EventInstance token is null")
                tokens_by_ordinal[ordinal].add(token)
        persistent_stable = all(
            len(tokens_by_ordinal[ordinal]) == 1
            for ordinal in persistent_ordinals
        )
        for raw in raw_snapshots:
            started = set(
                _nonnegative_int(value, "native started ordinal")
                for value in _array(
                    raw.get("startedEventOrdinals"),
                    "native started ordinals",
                )
            )
            stopped = set(
                _nonnegative_int(value, "native stopped ordinal")
                for value in _array(
                    raw.get("stoppedEventOrdinals"),
                    "native stopped ordinals",
                )
            )
            if stopped & persistent_ordinals:
                raise CaptureError(
                    "camera switch stopped a persistent effect EventInstance"
                )
            frame = int(raw["afterDspBlockStartFrame"])
            for ordinal in persistent_ordinals:
                expected_start = min(
                    schedules[ordinal].parameters_by_delivery_frame
                )
                if (ordinal in started) != (frame == expected_start):
                    raise CaptureError(
                        "persistent effect EventInstance start lifecycle differs"
                    )
        old_tokens = tokens_by_ordinal[old_ordinal]
        new_tokens = tokens_by_ordinal[new_ordinal]
        engine_tokens_stable = len(old_tokens) == 1 and len(new_tokens) == 1
        old_token = next(iter(old_tokens)) if engine_tokens_stable else 0
        new_token = next(iter(new_tokens)) if engine_tokens_stable else 0
        if (
            not old_tail_frames
            or not overlap_frames
            or old_tail_frames[0] != dsp_frame
            or overlap_frames[0] != dsp_frame
            or any(
                after - before != DSP_BUFFER_FRAMES
                for before, after in zip(
                    old_tail_frames, old_tail_frames[1:]
                )
            )
            or first_drain != old_tail_frames[-1] + DSP_BUFFER_FRAMES
            or first_drain is None
            or not persistent_stable
            or not engine_tokens_stable
            or old_token == new_token
        ):
            raise CaptureError(
                "camera fade tail lacks old/new overlap, natural drain, or stable "
                "persistent effect EventInstances"
            )

        return {
            "oldEngineStopMode": CAMERA_STOP_MODE,
            "oldEngineStopHostFrame": host_frame,
            "oldEngineStopDspFrame": dsp_frame,
            "oldEngineEventOrdinal": old_ordinal,
            "newEngineEventOrdinal": new_ordinal,
            "oldEngineEventInstanceToken": old_token,
            "newEngineEventInstanceToken": new_token,
            "switchStartedEventOrdinals": started_ordinals,
            "switchStoppedEventOrdinals": stopped_ordinals,
            "oldEngineStopAppliedToExactInstance": True,
            "newEngineStartAppliedToExactInstance": True,
            "oldEngineTailObservedAfterStop": True,
            "oldEngineTailDspFrames": old_tail_frames,
            "newAndOldEngineOverlapObserved": True,
            "newAndOldEngineOverlapDspFrames": overlap_frames,
            "oldEngineTailDrainedNaturally": True,
            "oldEngineTailDrainDspFrame": first_drain,
            "persistentEffectInstanceTokensStable": True,
        }


def _write_json_atomic(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(_canonical_json_bytes(value) + b"\n")
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        request = NativeCausalCapturer._load_json(
            args.request.resolve(strict=True), "capture request"
        )
        output = args.output.resolve()
        capturer = NativeCausalCapturer(request, output.parent)
        trace = capturer.capture()
        _write_json_atomic(output, trace)
    except (CaptureError, OSError, RuntimeError) as exc:
        parser.error(str(exc))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
