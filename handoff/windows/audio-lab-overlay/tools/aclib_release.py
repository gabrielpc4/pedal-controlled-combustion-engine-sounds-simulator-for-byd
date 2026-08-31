"""Filename-independent release capture plans for official AC sound banks.

This module is intentionally separate from the legacy event-level compiler.  A
release recipe names one parsed waveform-instrument GUID, carries the manifest
curves derived from that instrument's authored automation, and is rendered from
a temporary bank in which every other waveform reachable from the event is
disabled.  Embedded sample names are consulted only by the runtime identity
check during rendering; they never participate in classification or enter a
plan/manifest.
"""

from __future__ import annotations

from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any, Iterable

from sim.aclib import AUDIO_ROLES, LOOP_ROLES, V2_TRIGGERS_BY_ROLE
from sim.aclib_catalog import canonical_json_bytes, validate_catalog
from sim.flac_codec import inspect_pcm16_stereo_wav
from sim.fmod_authored_curves import (
    AUTHORED_CURVE_SCHEMA,
    LIMITER_PROGRAM_SCHEMA,
    ONE_SHOT_CURVE_SCHEMA,
    TURBO_TRANSIENT_SOURCE_SCHEMA,
    FmodAuthoredCurveError,
    certify_manifest_limiter_program,
    certify_manifest_turbo_transient_source,
    derive_manifest_limiter_program,
    derive_manifest_one_shot_curves,
    derive_manifest_source_curves,
    derive_manifest_turbo_transient_source,
    derive_windowed_capture_fallback,
)
from sim.fmod_graph_roles import (
    BANK_GRAPH_SCHEMA,
    CLASSIFICATION_SCHEMA,
    POLICY_ALLOW_CANDIDATE,
    POLICY_AMBIGUOUS,
    POLICY_EXCLUDE,
    POLICY_OUT_OF_SCOPE,
    ROLE_ENGINE_FALLING,
    ROLE_ENGINE_INDEPENDENT,
    ROLE_ENGINE_RELEASE_AUDIBLE,
    ROLE_ENGINE_TRANSIENT,
    ROLE_EXCLUDED_LOAD,
    ROLE_GEAR_GRIND,
    ROLE_GEAR_SHIFT,
    ROLE_LIMITER,
    ROLE_OVERRUN_TRANSIENT,
    ROLE_TRANSMISSION,
    ROLE_TURBO_CONTINUOUS,
    ROLE_TURBO_TRANSIENT,
    classify_bank_graph_sources,
)
from sim.fmod_continuous_source import (
    AUTO_PITCH_MODE,
    FORBIDDEN_PEDAL_VERIFICATION_SCHEMA,
    PROPERTY_ONE_INTERPOLATION,
    PROPERTY_ONE_PITCH_MODE,
    PROPERTY_ONE_VERIFICATION_SCHEMA,
    ROUTED_SILENCE_VERIFICATION_SCHEMA,
    certify_property_one_relative_rate,
    validate_property_one_pitch_curve,
)
from tools.audit_fmod_bank_graph import validate_bank_graph_report


RELEASE_CAPTURE_PLAN_SCHEMA_VERSION = 2
GRAPH_SUMMARY_SCHEMA = "ac-fmod-catalog-graph-audit-summary-v1"
DEFAULT_GRAPH_ROOT = Path(__file__).resolve().parents[1] / ".aclib-local" / "bank-graph-audit-v3"
DEFAULT_RELEASE_OUTPUT_ROOT = Path(r"D:\Users\sgabr\BYDMotorSoundData\aclib")
DEFAULT_RELEASE_PLAN = DEFAULT_RELEASE_OUTPUT_ROOT / "capture-plan-v2.json"
DEFAULT_LIMITER_ORACLE_PROOF = (
    Path(__file__).resolve().parents[1]
    / ".aclib-local"
    / "limiter-lifecycle-oracle-v1"
    / "proof.json"
)
DEFAULT_SHIFT_SILENCE_ORACLE_PROOF = (
    Path(r"D:\Users\sgabr\BYDMotorSoundData")
    / "shift-silence-oracle-v1"
    / "proof.json"
)
DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF = (
    Path(__file__).resolve().parents[1]
    / ".aclib-local"
    / "turbo-transient-oracle-v1"
    / "proof.json"
)
DEFAULT_PRIORITY_ORACLE_PROOF = (
    Path(r"D:\Users\sgabr\BYDMotorSoundData")
    / "priority-oracle-v1"
    / "proof.json"
)
DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF = (
    Path(r"D:\Users\sgabr\BYDMotorSoundData")
    / "oracles"
    / "continuous-source-oracle-v1"
    / "static-dispositions-proof.json"
)
DEFAULT_PROPERTY_ONE_ORACLE_PROOF = (
    Path(r"D:\Users\sgabr\BYDMotorSoundData")
    / "oracles"
    / "continuous-source-oracle-v1"
    / "property-one-proof.json"
)
CURVE_PROBE_DURATION_FRAMES = 48000
CURVE_PROBE_WARMUP_FRAMES = 24000
CURVE_PROBE_MIN_PREDICTED_AMPLITUDE = 0.25
CURVE_PROBE_MAX_RATIO_ERROR_DB = 3.0
TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE = 38.0

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
_TOKEN = re.compile(r"[a-z0-9]+")

_CONTINUOUS_ENGINE_ROLES = frozenset(
    (ROLE_ENGINE_FALLING, ROLE_ENGINE_INDEPENDENT, ROLE_ENGINE_RELEASE_AUDIBLE)
)
_AUTHORED_CONTINUOUS_ROLES = _CONTINUOUS_ENGINE_ROLES | {ROLE_TRANSMISSION}
_RETAINED_CLASSIFIER_ROLES = frozenset(
    (
        *_CONTINUOUS_ENGINE_ROLES,
        ROLE_ENGINE_TRANSIENT,
        ROLE_TRANSMISSION,
        ROLE_TURBO_CONTINUOUS,
        ROLE_TURBO_TRANSIENT,
        ROLE_LIMITER,
        ROLE_OVERRUN_TRANSIENT,
        ROLE_GEAR_SHIFT,
    )
)

# These values are bounded FMOD 1.08.12 callback/channel observations, not
# role-preference guesses.  Every unresolved one-shot category deliberately
# remains nullable in the capture plan and therefore cannot become a V2 pack.
_SOFTWARE_CHANNEL_PRIORITY_BY_MANIFEST_ROLE: dict[str, int] = {
    **{role: 64 for role in LOOP_ROLES},
    "ENGINE_TRANSIENT": 64,
    "ENGINE_START": 64,
    "LIMITER": 64,
    "SHIFT_UP": 128,
    "SHIFT_DOWN": 128,
    "BOV": 128,
    "TURBO_TRANSIENT": 128,
    "OVERRUN": 128,
}
_SOFTWARE_CHANNEL_PRIORITY_UNRESOLVED_ROLES = frozenset(
    {"POP", "BANG", "CRACK"}
)


class ReleaseCapturePlanError(ValueError):
    """A release plan could not be proven complete or internally consistent."""


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _event_suffix(path: object) -> str:
    text = str(path or "").strip().casefold().rstrip("/")
    return text.rsplit("/", 1)[-1] if text else ""


def _curve_value(points: list[list[float]], x: float) -> float:
    if not points:
        return 1.0
    if x <= float(points[0][0]):
        return float(points[0][1])
    for left, right in zip(points, points[1:]):
        if x <= float(right[0]):
            width = float(right[0]) - float(left[0])
            if width <= 0.0:
                return float(right[1])
            fraction = (x - float(left[0])) / width
            return float(left[1]) + (float(right[1]) - float(left[1])) * fraction
    return float(points[-1][1])


def _finite(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ReleaseCapturePlanError(f"{label} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise ReleaseCapturePlanError(f"{label} must be a finite number")
    return result


def _validate_curve(value: object, label: str, *, pedal: bool) -> None:
    if not isinstance(value, list):
        raise ReleaseCapturePlanError(f"{label} must be an array")
    previous: float | None = None
    for index, point in enumerate(value):
        if not isinstance(point, list) or len(point) != 2:
            raise ReleaseCapturePlanError(f"{label}[{index}] must be [x,y]")
        x = _finite(point[0], f"{label}[{index}].x")
        y = _finite(point[1], f"{label}[{index}].y")
        if (pedal and not 0.0 <= x <= 1.0) or (not pedal and x < 0.0):
            raise ReleaseCapturePlanError(f"{label}[{index}].x is outside its domain")
        if not 0.0 <= y <= 1.0:
            raise ReleaseCapturePlanError(f"{label}[{index}].y is outside 0..1")
        if previous is not None and x <= previous:
            raise ReleaseCapturePlanError(f"{label} x values must increase")
        previous = x


def _load_graph_inventory(
    graph_root: Path, catalog: dict[str, Any]
) -> tuple[dict[str, Any], dict[str, Path]]:
    root = graph_root.resolve(strict=True)
    summary_path = root / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(summary, dict) or summary.get("schema") != GRAPH_SUMMARY_SCHEMA:
        raise ReleaseCapturePlanError("graph root has no supported complete summary")
    status = summary.get("status")
    if not isinstance(status, dict) or status.get("complete") is not True:
        raise ReleaseCapturePlanError("graph audit is not complete")
    if status.get("allFamiliesSuccessful") is not True:
        raise ReleaseCapturePlanError("graph audit contains failed families")
    source_offsets = summary.get("sourceIsolationOffsets")
    if (
        not isinstance(source_offsets, dict)
        or int(source_offsets.get("successfulFamilyCount", -1))
        != len(catalog["soundFamilies"])
        or int(source_offsets.get("offsetsValidatedAgainstExactSourceBytes", -1)) <= 0
    ):
        raise ReleaseCapturePlanError("graph audit has no complete source-offset proof")
    catalog_evidence = summary.get("catalog")
    if (
        not isinstance(catalog_evidence, dict)
        or catalog_evidence.get("catalogSha256") != catalog["catalogSha256"]
    ):
        raise ReleaseCapturePlanError("graph audit was made from a different catalog")

    expected_ids = {str(item["id"]) for item in catalog["soundFamilies"]}
    entries = summary.get("families")
    if not isinstance(entries, list):
        raise ReleaseCapturePlanError("graph summary families must be an array")
    by_family: dict[str, Path] = {}
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("status") != "success":
            raise ReleaseCapturePlanError("graph summary contains a non-success family")
        family_id = str(entry.get("familyId") or "")
        cache_path = entry.get("cachePath")
        if family_id in by_family or not isinstance(cache_path, str):
            raise ReleaseCapturePlanError("graph summary family entries are invalid")
        path = (root / Path(*cache_path.replace("\\", "/").split("/"))).resolve()
        if root != path and root not in path.parents:
            raise ReleaseCapturePlanError("graph cache path escapes its root")
        by_family[family_id] = path
    if set(by_family) != expected_ids:
        raise ReleaseCapturePlanError("graph summary does not cover every catalog family")
    return summary, by_family


def load_limiter_source_verifications(
    proof_path: Path = DEFAULT_LIMITER_ORACLE_PROOF,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    """Load the complete source-bound limiter oracle without trusting paths."""

    path = Path(proof_path).resolve(strict=True)
    proof = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(proof, dict)
        or proof.get("schema") != "ac-fmod-limiter-lifecycle-oracle-v1"
    ):
        raise ReleaseCapturePlanError("limiter oracle proof schema is unsupported")
    raw = proof.get("sourceVerifications")
    if not isinstance(raw, list) or len(raw) != 73:
        raise ReleaseCapturePlanError(
            "limiter oracle must contain all 73 source verifications"
        )
    by_guid: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ReleaseCapturePlanError(
                f"limiter source verification {index} is invalid"
            )
        guid = _guid(item.get("sourceGuid"))
        if not _GUID.fullmatch(guid) or guid in by_guid:
            raise ReleaseCapturePlanError(
                "limiter source verification GUID is invalid or duplicated"
            )
        by_guid[guid] = item
    counts = proof.get("sourceVerificationCounts")
    if not isinstance(counts, dict):
        raise ReleaseCapturePlanError("limiter source verification counts are absent")
    if (
        int(counts.get("verified", -1)) != 73
        or int(counts.get("audible", -1)) != 70
        or int(counts.get("authoredTargetSilent", -1)) != 3
        or counts.get("programModes")
        != {
            "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": 48,
            "PERSISTENT_DECAY_REGION_ONE_SHOT": 7,
            "PERSISTENT_DECAY_REGION_LOOP": 18,
        }
    ):
        raise ReleaseCapturePlanError("limiter source verification counts changed")
    return proof, by_guid


def load_shift_silence_source_verifications(
    proof_path: Path = DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    *,
    expected_catalog_sha256: str | None = None,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    """Load and independently validate the source-bound silent-shift proof."""

    path = Path(proof_path).resolve(strict=True)
    proof_root = path.parent
    proof = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(proof, dict)
        or set(proof)
        != {
            "schema",
            "catalogSha256",
            "capturePlanSha256",
            "sourceCount",
            "candidateDiscovery",
            "sourceVerifications",
        }
        or proof.get("schema") != "ac-fmod-shift-silence-oracle-v1"
        or proof.get("candidateDiscovery")
        != "STRICT_RELEASE_COMPILE_EXACT_ZERO_PCM"
        or not isinstance(proof.get("catalogSha256"), str)
        or not _SHA256.fullmatch(proof["catalogSha256"])
        or not isinstance(proof.get("capturePlanSha256"), str)
        or not _SHA256.fullmatch(proof["capturePlanSha256"])
    ):
        raise ReleaseCapturePlanError("shift-silence oracle proof is invalid")
    if (
        expected_catalog_sha256 is not None
        and proof["catalogSha256"] != expected_catalog_sha256
    ):
        raise ReleaseCapturePlanError(
            "shift-silence oracle was made from another catalog"
        )
    raw = proof["sourceVerifications"]
    if (
        not isinstance(raw, list)
        or proof.get("sourceCount") != 2
        or len(raw) != 2
    ):
        raise ReleaseCapturePlanError(
            "shift-silence oracle must contain both certified sources"
        )

    required_verification_fields = {
        "familyId",
        "representativeCarId",
        "sourceGuid",
        "role",
        "eventPath",
        "captureParameterValues",
        "sourceRuntimeIdentity",
        "graphReportSha256",
        "installedBankRelativePath",
        "installedBankSha256BeforeAndAfter",
        "graphBaseVolumeDb",
        "renderContract",
        "renders",
        "audibilityDisposition",
        "verificationPayloadSha256",
    }
    expected_render_contract = {
        "fmodVersionHex": "0x00010812",
        "output": "FMOD_OUTPUTTYPE_WAVWRITER_NRT",
        "opensPlaybackDevice": False,
        "sampleRate": 48000,
        "channels": 2,
        "bitsPerSample": 16,
        "dspBufferFrames": 256,
        "durationFrames": 96000,
        "warmupFrames": 0,
        "independentRenderCount": 2,
    }
    by_guid: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(raw):
        label = f"shift-silence sourceVerifications[{index}]"
        if not isinstance(item, dict) or set(item) != required_verification_fields:
            raise ReleaseCapturePlanError(f"{label} fields are invalid")
        guid = _guid(item.get("sourceGuid"))
        family_id = str(item.get("familyId") or "")
        role = item.get("role")
        if (
            not _GUID.fullmatch(guid)
            or guid in by_guid
            or not _SHA256.fullmatch(family_id)
            or role not in {"SHIFT_UP", "SHIFT_DOWN"}
            or item.get("audibilityDisposition") != "AUTHORED_TARGET_SILENT"
            or item.get("installedBankSha256BeforeAndAfter") != family_id
            or float(item.get("graphBaseVolumeDb", math.nan)) != -49.0
        ):
            raise ReleaseCapturePlanError(f"{label} identity/disposition is invalid")
        unsigned = dict(item)
        verification_sha = unsigned.pop("verificationPayloadSha256")
        if (
            not isinstance(verification_sha, str)
            or not _SHA256.fullmatch(verification_sha)
            or _sha256_bytes(canonical_json_bytes(unsigned)) != verification_sha
        ):
            raise ReleaseCapturePlanError(f"{label} payload hash is invalid")
        event_path = item.get("eventPath")
        runtime_identity = item.get("sourceRuntimeIdentity")
        state = 1.0 if role == "SHIFT_UP" else 0.0
        if (
            not isinstance(event_path, str)
            or _event_suffix(event_path) not in {"gear_int", "gear_ext"}
            or not isinstance(runtime_identity, str)
            or not runtime_identity
            or item.get("captureParameterValues") != {"state": state}
            or not isinstance(item.get("graphReportSha256"), str)
            or not _SHA256.fullmatch(item["graphReportSha256"])
        ):
            raise ReleaseCapturePlanError(f"{label} event/state evidence is invalid")
        bank_relative = item.get("installedBankRelativePath")
        if (
            not isinstance(bank_relative, str)
            or not bank_relative
            or Path(bank_relative).is_absolute()
            or ".." in Path(bank_relative.replace("\\", "/")).parts
        ):
            raise ReleaseCapturePlanError(f"{label} installed-bank path is invalid")
        contract = item.get("renderContract")
        if (
            not isinstance(contract, dict)
            or set(contract)
            != set(expected_render_contract) | {"fmodCoreSha256", "fmodStudioSha256"}
            or any(contract.get(key) != value for key, value in expected_render_contract.items())
            or any(
                not isinstance(contract.get(key), str)
                or not _SHA256.fullmatch(contract[key])
                for key in ("fmodCoreSha256", "fmodStudioSha256")
            )
        ):
            raise ReleaseCapturePlanError(f"{label} render contract is invalid")
        renders = item.get("renders")
        if not isinstance(renders, list) or len(renders) != 2:
            raise ReleaseCapturePlanError(f"{label} independent renders are absent")
        wav_hashes: set[str] = set()
        pcm_hashes: set[str] = set()
        for take, rendered in enumerate(renders, 1):
            render_label = f"{label}.renders[{take - 1}]"
            if (
                not isinstance(rendered, dict)
                or rendered.get("take") != take
                or rendered.get("exactAllZeroPcm") is not True
                or rendered.get("frameCount") != 96000
                or rendered.get("scheduledSoundNames") != [runtime_identity]
                or isinstance(rendered.get("mutedWaveformCount"), bool)
                or not isinstance(rendered.get("mutedWaveformCount"), int)
                or rendered["mutedWaveformCount"] < 0
                or isinstance(rendered.get("disabledParentCount"), bool)
                or not isinstance(rendered.get("disabledParentCount"), int)
                or rendered["disabledParentCount"] < 0
            ):
                raise ReleaseCapturePlanError(f"{render_label} evidence is invalid")
            for key in (
                "finalWavSha256",
                "pcmPayloadSha256",
                "isolatedBankSha256",
            ):
                if not isinstance(rendered.get(key), str) or not _SHA256.fullmatch(
                    rendered[key]
                ):
                    raise ReleaseCapturePlanError(f"{render_label}.{key} is invalid")
            relative = rendered.get("finalWavRelativePath")
            if not isinstance(relative, str) or not relative or "\\" in relative:
                raise ReleaseCapturePlanError(f"{render_label} WAV path is invalid")
            wav_path = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
            if proof_root != wav_path and proof_root not in wav_path.parents:
                raise ReleaseCapturePlanError(f"{render_label} WAV escapes proof root")
            if _sha256_file(wav_path) != rendered["finalWavSha256"]:
                raise ReleaseCapturePlanError(f"{render_label} WAV hash changed")
            integrity = inspect_pcm16_stereo_wav(wav_path)
            zero_pcm_sha = hashlib.sha256(
                b"\x00" * (integrity.frame_count * integrity.channels * 2)
            ).hexdigest()
            if (
                integrity.frame_count != 96000
                or math.isfinite(integrity.peak_dbfs)
                or integrity.sha256 != rendered["pcmPayloadSha256"]
                or integrity.sha256 != zero_pcm_sha
            ):
                raise ReleaseCapturePlanError(
                    f"{render_label} is not certified exact-zero PCM"
                )
            wav_hashes.add(rendered["finalWavSha256"])
            pcm_hashes.add(rendered["pcmPayloadSha256"])
        if len(wav_hashes) != 1 or len(pcm_hashes) != 1:
            raise ReleaseCapturePlanError(
                f"{label} independent exact-zero renders disagree"
            )
        by_guid[guid] = item
    return proof, by_guid


def load_turbo_transient_source_verifications(
    proof_path: Path = DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    """Load and independently bind all certified turbo-event source leaves."""

    path = Path(proof_path).resolve(strict=True)
    proof_root = path.parent
    proof = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(proof, dict)
        or set(proof)
        != {
            "schema",
            "result",
            "basis",
            "inputs",
            "counts",
            "catalogBounds",
            "sourceBanks",
            "sourceVerifications",
        }
        or proof.get("schema") != "ac-fmod-turbo-transient-oracle-v1"
        or proof.get("result") != "PASS_SOURCE_BOUND_COMPLETE"
    ):
        raise ReleaseCapturePlanError("turbo-transient oracle proof is invalid")
    expected_counts = {
        "sources": 171,
        "families": 59,
        "programModes": {
            "BOOST_RELEASE_REGION_ONE_SHOT": 143,
            "PARAMETER_SHEET_EVENT_START_ONE_SHOT": 3,
            "TIMELINE_PERIODIC_ONE_SHOT": 25,
        },
        "pcmDispositions": {
            "AUDIBLE_TARGET_PCM": 160,
            "AUTHORED_TARGET_SILENT": 11,
        },
        "softwareChannelPriorities": {"128": 171},
    }
    if proof.get("counts") != expected_counts:
        raise ReleaseCapturePlanError("turbo-transient oracle counts changed")
    basis = proof.get("basis")
    if not isinstance(basis, dict) or (
        basis.get("runtime") != "FMOD Studio API 1.08.12"
        or basis.get("output") != "WAVWRITER_NRT"
        or basis.get("audioDeviceOpened") is not False
        or basis.get("installedBanksModified") is not False
        or basis.get("sampleNamesUsedForSemantics") is not False
        or basis.get("sampleNamesUsedOnlyForRuntimeIdentityJoin") is not True
    ):
        raise ReleaseCapturePlanError("turbo-transient oracle basis changed")
    inputs = proof.get("inputs")
    if not isinstance(inputs, dict) or set(inputs) != {
        "classificationSha256",
        "priorityProofSha256",
    } or any(
        not isinstance(inputs.get(key), str) or not _SHA256.fullmatch(inputs[key])
        for key in inputs
    ):
        raise ReleaseCapturePlanError("turbo-transient oracle inputs are invalid")

    source_banks = proof.get("sourceBanks")
    if not isinstance(source_banks, list) or len(source_banks) != 59:
        raise ReleaseCapturePlanError("turbo-transient source-bank proof is incomplete")
    bank_ids: set[str] = set()
    for item in source_banks:
        if not isinstance(item, dict) or set(item) != {
            "familyId",
            "sha256Before",
            "sha256After",
            "unchanged",
        }:
            raise ReleaseCapturePlanError("turbo-transient source-bank row is invalid")
        family_id = str(item.get("familyId") or "")
        if (
            not _SHA256.fullmatch(family_id)
            or family_id in bank_ids
            or item.get("sha256Before") != family_id
            or item.get("sha256After") != family_id
            or item.get("unchanged") is not True
        ):
            raise ReleaseCapturePlanError("turbo-transient source bank was modified")
        bank_ids.add(family_id)

    raw = proof.get("sourceVerifications")
    if not isinstance(raw, list) or len(raw) != 171:
        raise ReleaseCapturePlanError(
            "turbo-transient oracle must contain all 171 source verifications"
        )
    by_guid: dict[str, dict[str, Any]] = {}
    families: set[str] = set()
    program_roots: set[tuple[str, str, str]] = set()
    mode_counts: Counter[str] = Counter()
    disposition_counts: Counter[str] = Counter()
    priority_counts: Counter[str] = Counter()
    observed_maximum_gain = 0.0
    observed_maximum_identity: tuple[str, str] | None = None
    observed_curves: dict[tuple[str, str], list[list[float]]] = {}
    for index, item in enumerate(raw):
        label = f"turbo sourceVerifications[{index}]"
        if not isinstance(item, dict):
            raise ReleaseCapturePlanError(f"{label} is invalid")
        guid = _guid(item.get("sourceGuid"))
        family_id = str(item.get("familyId") or "")
        event_path = str(item.get("eventPath") or "")
        program_root = _guid(item.get("programPlacementRootInstrumentGuid"))
        if (
            item.get("schema")
            != "ac-fmod-turbo-transient-source-verification-v1"
            or not _GUID.fullmatch(guid)
            or guid in by_guid
            or not _SHA256.fullmatch(family_id)
            or family_id not in bank_ids
            or not event_path
            or not _GUID.fullmatch(program_root)
        ):
            raise ReleaseCapturePlanError(f"{label} identity is invalid")
        unsigned = dict(item)
        verification_sha = unsigned.pop("verificationPayloadSha256", None)
        if (
            not isinstance(verification_sha, str)
            or not _SHA256.fullmatch(verification_sha)
            or _sha256_bytes(canonical_json_bytes(unsigned)) != verification_sha
        ):
            raise ReleaseCapturePlanError(f"{label} payload hash is invalid")
        capture = item.get("capture")
        if not isinstance(capture, dict):
            raise ReleaseCapturePlanError(f"{label} capture is invalid")
        disposition = str(capture.get("audibilityDisposition") or "")
        relative = capture.get("finalWavRelativePath")
        if (
            disposition not in {"AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_SILENT"}
            or not isinstance(relative, str)
            or not relative
            or "\\" in relative
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
            or not isinstance(capture.get("finalWavSha256"), str)
            or not _SHA256.fullmatch(capture["finalWavSha256"])
            or not isinstance(capture.get("pcmPayloadSha256"), str)
            or not _SHA256.fullmatch(capture["pcmPayloadSha256"])
        ):
            raise ReleaseCapturePlanError(f"{label} PCM identity is invalid")
        wav_path = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
        if proof_root != wav_path and proof_root not in wav_path.parents:
            raise ReleaseCapturePlanError(f"{label} WAV escapes proof root")
        if _sha256_file(wav_path) != capture["finalWavSha256"]:
            raise ReleaseCapturePlanError(f"{label} WAV hash changed")
        integrity = inspect_pcm16_stereo_wav(wav_path)
        silent = disposition == "AUTHORED_TARGET_SILENT"
        if (
            integrity.sha256 != capture["pcmPayloadSha256"]
            or integrity.frame_count != capture.get("frameCount")
            or (integrity.sample_rate, integrity.channels, integrity.bits_per_sample)
            != (48000, 2, 16)
            or silent != (not math.isfinite(integrity.peak_dbfs))
            or capture.get("allPcmSamplesZero") is not silent
        ):
            raise ReleaseCapturePlanError(f"{label} certified PCM changed")
        curve_verification = item.get("curveVerification")
        verified_curves = (
            curve_verification.get("verifiedControlGainCurves")
            if isinstance(curve_verification, dict)
            else None
        )
        if not isinstance(verified_curves, dict) or not verified_curves:
            raise ReleaseCapturePlanError(f"{label} has no source-bound gain curve")
        for control, record in verified_curves.items():
            curve = record.get("curve") if isinstance(record, dict) else None
            if not isinstance(curve, list) or not curve:
                raise ReleaseCapturePlanError(f"{label} {control} curve is invalid")
            observed_curves[(guid, str(control))] = curve
            for point in curve:
                if not isinstance(point, list) or len(point) != 2:
                    raise ReleaseCapturePlanError(f"{label} {control} point is invalid")
                value = _finite(point[1], f"{label} {control} gain")
                if value < 0.0 or value > TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE:
                    raise ReleaseCapturePlanError(
                        f"{label} {control} gain exceeds the certified V2 bound"
                    )
                if value > observed_maximum_gain:
                    observed_maximum_gain = value
                    observed_maximum_identity = (guid, str(control))
        voice = item.get("voicePolicy")
        priority = voice.get("softwareChannelPriority") if isinstance(voice, dict) else None
        if priority != 128:
            raise ReleaseCapturePlanError(f"{label} priority is not source-bound 128")
        by_guid[guid] = item
        families.add(family_id)
        program_roots.add((family_id, event_path, program_root))
        mode_counts[str(item.get("programMode"))] += 1
        disposition_counts[disposition] += 1
        priority_counts[str(priority)] += 1

    if (
        families != bank_ids
        or len(program_roots) != 105
        or dict(sorted(mode_counts.items())) != expected_counts["programModes"]
        or dict(sorted(disposition_counts.items()))
        != expected_counts["pcmDispositions"]
        or dict(sorted(priority_counts.items()))
        != expected_counts["softwareChannelPriorities"]
    ):
        raise ReleaseCapturePlanError("turbo-transient proof coverage changed")
    bound = proof.get("catalogBounds", {}).get(
        "maximumCaptureRelativeControlGain"
    )
    bound_control_value = (
        float(bound.get("controlValue", math.nan))
        if isinstance(bound, dict)
        else math.nan
    )
    bound_curve = (
        observed_curves.get(
            (str(bound.get("sourceGuid")), str(bound.get("control")))
        )
        if isinstance(bound, dict)
        else None
    )
    bound_vertex_value = next(
        (
            float(point[1])
            for point in (bound_curve or [])
            if float(point[0]) == bound_control_value
        ),
        math.nan,
    )
    if not isinstance(bound, dict) or (
        bound.get("basis")
        != "MAXIMUM_VERTEX_OF_ALL_SOURCE_BOUND_EMPIRICAL_CONTROL_GAIN_CURVES"
        or float(bound.get("value", math.nan)) != observed_maximum_gain
        or observed_maximum_identity is None
        or bound.get("sourceGuid") != observed_maximum_identity[0]
        or bound.get("control") != observed_maximum_identity[1]
        or bound_vertex_value != observed_maximum_gain
        or bound.get("sourceVerificationPayloadSha256")
        != by_guid[observed_maximum_identity[0]]["verificationPayloadSha256"]
    ):
        raise ReleaseCapturePlanError("turbo-transient maximum gain proof changed")
    return proof, by_guid


def _source_event_path(row: dict[str, Any]) -> str:
    raw = row.get("eventPaths")
    if not isinstance(raw, list) or not raw:
        raise ReleaseCapturePlanError(f"source {row.get('sourceGuid')} has no event path")
    paths = sorted(str(item) for item in raw)
    internal = [path for path in paths if _event_suffix(path).endswith("_int")]
    return internal[0] if internal else paths[0]


def _source_region_contains(
    row: dict[str, Any], event_suffix: str, parameter: str, value: float
) -> bool:
    memberships = (row.get("decisionEvidence") or {}).get("eventMemberships", [])
    membership = next(
        (
            item
            for item in memberships
            if isinstance(item, dict)
            and str(item.get("suffix") or "").casefold() == event_suffix.casefold()
        ),
        None,
    )
    if membership is None:
        return False
    placements = (membership.get("triggerTopology") or {}).get("placements", [])
    matching = [
        item
        for item in placements
        if isinstance(item, dict)
        and item.get("kind") == "parameter"
        and str(item.get("parameterName") or "").casefold() == parameter.casefold()
    ]
    if not matching:
        return False
    for item in matching:
        start = _finite(item.get("start"), f"{parameter} placement start")
        end = _finite(item.get("end"), f"{parameter} placement end")
        if value < start or value > end or (value == end and item.get("includeEnd") is not True):
            return False
    return True


def _placement_parameters(
    row: dict[str, Any], event_path: str, role: str
) -> dict[str, float]:
    memberships = (row.get("decisionEvidence") or {}).get("eventMemberships")
    if not isinstance(memberships, list):
        raise ReleaseCapturePlanError("classification membership evidence is absent")
    membership = next(
        (item for item in memberships if isinstance(item, dict) and item.get("path") == event_path),
        None,
    )
    if membership is None:
        raise ReleaseCapturePlanError("classification membership/event path disagree")
    placements = (membership.get("triggerTopology") or {}).get("placements", [])
    intervals: dict[str, list[tuple[float, float, bool]]] = defaultdict(list)
    for item in placements:
        if not isinstance(item, dict) or item.get("kind") != "parameter":
            continue
        name = str(item.get("parameterName") or "").casefold()
        if not name:
            continue
        start = _finite(item.get("start"), f"{name} placement start")
        end = _finite(item.get("end"), f"{name} placement end")
        if end < start:
            raise ReleaseCapturePlanError(f"{name} placement is reversed")
        intervals[name].append((start, end, item.get("includeEnd") is True))

    # A parameter controller also proves that the event exposes the parameter,
    # even when source scheduling does not use a placement for it.
    controlled = set()
    for controller in (row.get("decisionEvidence") or {}).get("automationControllers", []):
        if isinstance(controller, dict) and controller.get("inputKind") == "parameter":
            name = str(controller.get("inputParameterName") or "").casefold()
            if name:
                controlled.add(name)

    preferred = {
        "throttle": 0.0 if role == "OVERRUN" else 1.0,
        "state": 1.0 if role == "SHIFT_UP" else 0.0,
        "boost": 1.0,
        "bov": 1.0,
        "bov_decay": 0.0,
        "decay": 0.0,
    }
    result: dict[str, float] = {}
    for name, items in sorted(intervals.items()):
        start = max(item[0] for item in items)
        end = min(item[1] for item in items)
        include_end = all(item[2] for item in items if item[1] == end)
        if end < start:
            raise ReleaseCapturePlanError(f"{name} source trigger interval is empty")
        target = preferred.get(name, (start + end) * 0.5)
        target = min(end, max(start, target))
        if target == end and not include_end and end > start:
            target = math.nextafter(end, start)
        result[name] = round(target, 8)
    for name in sorted(controlled - result.keys()):
        if name in preferred:
            result[name] = preferred[name]

    if row.get("role") == ROLE_ENGINE_TRANSIENT:
        ancestry = (row.get("decisionEvidence") or {}).get("instrumentAncestry", [])
        source = ancestry[0] if ancestry else {}
        properties = source.get("baseProperties") if isinstance(source, dict) else {}
        auto_pitch = float((properties or {}).get("autoPitchReference") or 0.0)
        if "rpms" in controlled and "rpms" not in result and auto_pitch > 0.0:
            result["rpms"] = auto_pitch
        if "throttle" in controlled:
            result["throttle"] = 0.0
    return result


def _gear_roles(row: dict[str, Any], event_path: str) -> tuple[str, ...]:
    memberships = (row.get("decisionEvidence") or {}).get("eventMemberships", [])
    membership = next(
        (item for item in memberships if isinstance(item, dict) and item.get("path") == event_path),
        {},
    )
    placements = (membership.get("triggerTopology") or {}).get("placements", [])
    state_ranges = [
        (float(item["start"]), float(item["end"]), item.get("includeEnd") is True)
        for item in placements
        if isinstance(item, dict)
        and item.get("kind") == "parameter"
        and str(item.get("parameterName") or "").casefold() == "state"
    ]
    if not state_ranges:
        raise ReleaseCapturePlanError("gear source has no authored state placement")
    start = max(item[0] for item in state_ranges)
    end = min(item[1] for item in state_ranges)
    has_down = start <= 0.0 <= end
    has_up = start <= 1.0 <= end and (1.0 < end or all(item[2] for item in state_ranges))
    roles = tuple(role for role, present in (("SHIFT_DOWN", has_down), ("SHIFT_UP", has_up)) if present)
    if len(roles) != 1:
        raise ReleaseCapturePlanError(
            f"gear source state placement does not resolve one direction: [{start},{end}]"
        )
    return roles


def certify_silent_shift_source(
    graph: dict[str, Any],
    row: dict[str, Any],
    verification: dict[str, Any],
    *,
    graph_report_sha256: str,
    representative_car_id: str,
    installed_bank_relative_path: str,
) -> dict[str, Any]:
    """Bind a silent-shift proof row to the exact graph/classifier source."""

    source_guid = _guid(row.get("sourceGuid"))
    role = str(verification.get("role") or "")
    event_path = str(verification.get("eventPath") or "")
    state = 1.0 if role == "SHIFT_UP" else 0.0
    if (
        row.get("policy") != POLICY_ALLOW_CANDIDATE
        or row.get("role") != ROLE_GEAR_SHIFT
        or row.get("lifetime") != "oneShot"
        or source_guid != _guid(verification.get("sourceGuid"))
        or str((graph.get("bank") or {}).get("sha256"))
        != verification.get("familyId")
        or verification.get("graphReportSha256") != graph_report_sha256
        or verification.get("representativeCarId") != representative_car_id
        or verification.get("installedBankRelativePath")
        != installed_bank_relative_path
        or verification.get("captureParameterValues") != {"state": state}
        or _gear_roles(row, event_path) != (role,)
    ):
        raise ReleaseCapturePlanError(
            f"silent-shift proof no longer matches source {source_guid}"
        )
    instruments = {
        _guid(item.get("guid")): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    target = instruments.get(source_guid)
    sample = target.get("sample") if isinstance(target, dict) else None
    base = target.get("baseProperties") if isinstance(target, dict) else None
    event = next(
        (
            item
            for item in graph.get("events", [])
            if isinstance(item, dict) and item.get("path") == event_path
        ),
        None,
    )
    reachable = {
        _guid(guid)
        for guid in (event or {}).get("reachableInstrumentGuids", [])
    }
    if (
        not isinstance(target, dict)
        or target.get("kind") != "WaveformInstrumentNode"
        or not isinstance(base, dict)
        or float(base.get("volumeDb", math.nan)) != -49.0
        or not isinstance(sample, dict)
        or sample.get("name") != verification.get("sourceRuntimeIdentity")
        or event is None
        or event.get("mappingComplete") is not True
        or source_guid not in reachable
    ):
        raise ReleaseCapturePlanError(
            f"silent-shift graph evidence changed for source {source_guid}"
        )
    return {
        "sourceGuid": source_guid,
        "role": role,
        "disposition": "AUTHORED_TARGET_SILENT",
        "verificationPayloadSha256": verification["verificationPayloadSha256"],
    }


def load_continuous_disposition_source_verifications(
    proof_path: Path = DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    """Load the five source-bound dynamic route dispositions fail closed."""

    path = Path(proof_path).resolve(strict=True)
    proof_root = path.parent
    proof = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(proof, dict)
        or proof.get("schema")
        != "ac-fmod-continuous-static-disposition-oracle-v1"
        or proof.get("result") != "PASS_SOURCE_BOUND_COMPLETE"
    ):
        raise ReleaseCapturePlanError(
            "continuous-source disposition oracle proof is invalid"
        )
    payload_sha = proof.get("proofPayloadSha256")
    unsigned = dict(proof)
    unsigned.pop("proofPayloadSha256", None)
    if (
        not isinstance(payload_sha, str)
        or not _SHA256.fullmatch(payload_sha)
        or _sha256_bytes(canonical_json_bytes(unsigned)) != payload_sha
        or proof.get("counts")
        != {
            "sourceVerifications": 5,
            "forbiddenOnPedalRouting": 4,
            "authoredTargetRoutedSilent": 1,
        }
    ):
        raise ReleaseCapturePlanError(
            "continuous-source disposition oracle coverage changed"
        )
    artifacts = proof.get("diagnosticArtifacts")
    verifications = proof.get("sourceVerifications")
    if (
        not isinstance(artifacts, list)
        or len(artifacts) != 5
        or not isinstance(verifications, list)
        or len(verifications) != 5
    ):
        raise ReleaseCapturePlanError(
            "continuous-source disposition evidence is incomplete"
        )
    artifact_by_guid: dict[str, dict[str, Any]] = {}
    for item in artifacts:
        if not isinstance(item, dict):
            raise ReleaseCapturePlanError("continuous diagnostic artifact is invalid")
        guid = _guid(item.get("sourceGuid"))
        relative = item.get("relativePath")
        if (
            not _GUID.fullmatch(guid)
            or guid in artifact_by_guid
            or not isinstance(relative, str)
            or not relative
            or "\\" in relative
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
            or not isinstance(item.get("fileSha256"), str)
            or not _SHA256.fullmatch(item["fileSha256"])
            or not isinstance(item.get("payloadSha256"), str)
            or not _SHA256.fullmatch(item["payloadSha256"])
        ):
            raise ReleaseCapturePlanError("continuous diagnostic identity is invalid")
        diagnostic_path = (proof_root / Path(*relative.split("/"))).resolve(
            strict=True
        )
        if proof_root != diagnostic_path and proof_root not in diagnostic_path.parents:
            raise ReleaseCapturePlanError("continuous diagnostic escapes proof root")
        diagnostic = json.loads(diagnostic_path.read_text(encoding="utf-8"))
        if (
            _sha256_file(diagnostic_path) != item["fileSha256"]
            or diagnostic.get("diagnosticPayloadSha256") != item["payloadSha256"]
        ):
            raise ReleaseCapturePlanError("continuous diagnostic artifact changed")
        artifact_by_guid[guid] = item
    by_guid: dict[str, dict[str, Any]] = {}
    disposition_counts: Counter[str] = Counter()
    for item in verifications:
        if not isinstance(item, dict):
            raise ReleaseCapturePlanError("continuous disposition row is invalid")
        guid = _guid(item.get("sourceGuid"))
        disposition = item.get("disposition")
        expected_schema = (
            FORBIDDEN_PEDAL_VERIFICATION_SCHEMA
            if disposition == "FORBIDDEN_ON_PEDAL_ROUTING"
            else ROUTED_SILENCE_VERIFICATION_SCHEMA
            if disposition == "AUTHORED_TARGET_ROUTED_SILENT"
            else None
        )
        verification_sha = item.get("verificationPayloadSha256")
        unsigned_item = dict(item)
        unsigned_item.pop("verificationPayloadSha256", None)
        if (
            expected_schema is None
            or item.get("schema") != expected_schema
            or not _GUID.fullmatch(guid)
            or guid in by_guid
            or guid not in artifact_by_guid
            or item.get("diagnosticPayloadSha256")
            != artifact_by_guid[guid]["payloadSha256"]
            or not isinstance(verification_sha, str)
            or not _SHA256.fullmatch(verification_sha)
            or _sha256_bytes(canonical_json_bytes(unsigned_item))
            != verification_sha
            or item.get("sourceExcludedFromPlanMediaControlsAndRuntime")
            is not True
            or item.get("exactnessClaim") is not True
        ):
            raise ReleaseCapturePlanError(
                "continuous source disposition verification changed"
            )
        disposition_counts[str(disposition)] += 1
        by_guid[guid] = item
    if disposition_counts != {
        "FORBIDDEN_ON_PEDAL_ROUTING": 4,
        "AUTHORED_TARGET_ROUTED_SILENT": 1,
    }:
        raise ReleaseCapturePlanError("continuous disposition counts changed")
    return proof, by_guid


def load_property_one_source_verifications(
    proof_path: Path = DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    """Load all five compact property-index-1 PCM/rate certificates."""

    path = Path(proof_path).resolve(strict=True)
    proof_root = path.parent
    proof = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(proof, dict)
        or proof.get("schema") != "ac-fmod-property-one-relative-rate-oracle-v1"
        or proof.get("result") != "PASS_SOURCE_BOUND_COMPLETE"
        or proof.get("counts")
        != {
            "sourceVerifications": 5,
            "targetPcmCaptures": 5,
            "adaptiveWindowFallbackTracks": 0,
        }
    ):
        raise ReleaseCapturePlanError("property-one oracle proof is invalid")
    payload_sha = proof.get("proofPayloadSha256")
    unsigned = dict(proof)
    unsigned.pop("proofPayloadSha256", None)
    if (
        not isinstance(payload_sha, str)
        or not _SHA256.fullmatch(payload_sha)
        or _sha256_bytes(canonical_json_bytes(unsigned)) != payload_sha
    ):
        raise ReleaseCapturePlanError("property-one proof payload hash changed")
    artifacts = proof.get("sourceArtifacts")
    raw = proof.get("sourceVerifications")
    if (
        not isinstance(artifacts, list)
        or len(artifacts) != 5
        or not isinstance(raw, list)
        or len(raw) != 5
    ):
        raise ReleaseCapturePlanError("property-one source coverage is incomplete")
    artifact_by_guid = {
        _guid(item.get("sourceGuid")): item
        for item in artifacts
        if isinstance(item, dict)
    }
    if len(artifact_by_guid) != 5 or any(
        not _GUID.fullmatch(guid) for guid in artifact_by_guid
    ):
        raise ReleaseCapturePlanError("property-one artifact identities are invalid")
    by_guid: dict[str, dict[str, Any]] = {}
    for item in raw:
        if not isinstance(item, dict) or item.get("schema") != PROPERTY_ONE_VERIFICATION_SCHEMA:
            raise ReleaseCapturePlanError("property-one verification row is invalid")
        guid = _guid(item.get("sourceGuid"))
        artifact = artifact_by_guid.get(guid)
        if artifact is None or guid in by_guid:
            raise ReleaseCapturePlanError("property-one source is absent or duplicated")
        verification_sha = item.get("verificationPayloadSha256")
        unsigned_item = dict(item)
        unsigned_item.pop("verificationPayloadSha256", None)
        capture = item.get("capture")
        if (
            not isinstance(verification_sha, str)
            or not _SHA256.fullmatch(verification_sha)
            or _sha256_bytes(canonical_json_bytes(unsigned_item))
            != verification_sha
            or artifact.get("verificationPayloadSha256") != verification_sha
            or not isinstance(capture, dict)
            or artifact.get("finalWavRelativePath")
            != capture.get("finalWavRelativePath")
            or artifact.get("finalWavSha256") != capture.get("finalWavSha256")
            or artifact.get("pcmPayloadSha256") != capture.get("pcmPayloadSha256")
            or artifact.get("frameCount") != capture.get("frameCount")
        ):
            raise ReleaseCapturePlanError("property-one verification binding changed")
        relative = capture["finalWavRelativePath"]
        if (
            not isinstance(relative, str)
            or not relative
            or "\\" in relative
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
        ):
            raise ReleaseCapturePlanError("property-one PCM path is invalid")
        wav_path = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
        if proof_root != wav_path and proof_root not in wav_path.parents:
            raise ReleaseCapturePlanError("property-one PCM escapes proof root")
        integrity = inspect_pcm16_stereo_wav(wav_path)
        if (
            _sha256_file(wav_path) != capture["finalWavSha256"]
            or integrity.sha256 != capture["pcmPayloadSha256"]
            or integrity.frame_count != capture["frameCount"]
            or not math.isfinite(integrity.peak_dbfs)
            or integrity.peak_dbfs <= -96.0
        ):
            raise ReleaseCapturePlanError("property-one PCM artifact changed")
        by_guid[guid] = item
    return proof, by_guid


def _recipe(
    row: dict[str, Any],
    role: str,
    event_path: str,
    *,
    parameters: dict[str, float],
    root_rpm: float | None,
    rpm_curve: list[list[float]],
    gain_curve: list[list[float]],
    curve_sha256: str | None,
    source_projection: str = "NONE",
    capture_pcm_post_gain_linear: float = 1.0,
    pitch_mode: str = AUTO_PITCH_MODE,
    pitch_curve: list[list[float]] | None = None,
    pitch_curve_interpolation: str = "NONE",
) -> dict[str, Any]:
    source_guid = _guid(row.get("sourceGuid"))
    looping = role in LOOP_ROLES
    identifier = f"{role.casefold()}_{source_guid.replace('-', '')[:16]}"
    duration = (
        192000
        if role == "IDLE"
        else 144000
        if looping
        else 480000
        if role == "ENGINE_TRANSIENT"
        else 144000
        if role == "ENGINE_START"
        else 96000
    )
    warmup = 36000 if looping else 0
    software_priority = _SOFTWARE_CHANNEL_PRIORITY_BY_MANIFEST_ROLE.get(role)
    return {
        "id": identifier,
        "role": role,
        "event": _event_suffix(event_path),
        "eventPath": event_path,
        "sourceGuid": source_guid,
        "sourceClassificationRole": str(row["role"]),
        "sourceProjection": source_projection,
        "sourceCurveSha256": curve_sha256,
        "capturePcmPostGainLinear": float(capture_pcm_post_gain_linear),
        "parameters": dict(sorted(parameters.items())),
        "rootRpm": root_rpm,
        "looping": looping,
        "durationFrames": duration,
        "warmupFrames": warmup,
        "rpmCurve": rpm_curve,
        "gainCurve": gain_curve,
        "pitchMode": pitch_mode,
        "pitchCurve": [] if pitch_curve is None else pitch_curve,
        "pitchCurveInterpolation": pitch_curve_interpolation,
        "triggers": sorted(V2_TRIGGERS_BY_ROLE[role]),
        "variantIndex": 0,
        "softwareChannelPriority": software_priority,
        "softwareChannelPriorityBlocker": (
            None
            if software_priority is not None
            else "SOURCE_BOUND_FMOD_CHANNEL_PRIORITY_REQUIRED"
        ),
    }


def _derive_shared_source_idle_projection(
    graph: dict[str, Any], row: dict[str, Any]
) -> dict[str, Any]:
    """Retain only the authored pedal-zero use of a shared rising source."""

    projected_row = {
        **row,
        "policy": POLICY_ALLOW_CANDIDATE,
        "role": ROLE_ENGINE_FALLING,
    }
    curves = derive_manifest_source_curves(
        graph, projected_row, {"throttle": 0.0}
    )
    if curves.get("schema") != AUTHORED_CURVE_SCHEMA:
        raise ReleaseCapturePlanError(
            "idle projection curve oracle returned an unknown schema"
        )
    if _event_suffix(curves["eventPath"]) != "engine_int":
        raise ReleaseCapturePlanError(
            "idle projection must come from the cabin engine event"
        )
    parameters = {
        str(key): float(value)
        for key, value in curves["captureParameterValues"].items()
    }
    if parameters.get("throttle") != 0.0 or float(curves["captureThrottle"]) != 0.0:
        raise ReleaseCapturePlanError(
            "idle projection did not capture at released pedal"
        )
    return {
        "schema": "ac-shared-source-idle-projection-v1",
        "sourceGuid": str(row["sourceGuid"]),
        "eventPath": curves["eventPath"],
        "captureRootRpm": float(curves["captureRootRpm"]),
        "captureThrottle": 0.0,
        "captureParameterValues": dict(sorted(parameters.items())),
        "rpmCurve": curves["rpmCurve"],
        "gainCurve": [[0.0, 1.0], [1.0, 1.0]],
        "capturePcmPostGainLinear": float(
            (curves.get("normalization") or {}).get(
                "capturePcmPostGainLinear", 1.0
            )
        ),
        "runtimeAcceleratorControl": "FLAT_PEDAL_ZERO_CAPTURE",
        "risingAcceleratorControlRetained": False,
        "targetOnlyReferenceCaptureRequired": True,
    }


def _capture_pcm_post_gain_linear(curves: dict[str, Any]) -> float:
    value = float(
        (curves.get("normalization") or {}).get(
            "capturePcmPostGainLinear", 1.0
        )
    )
    if not math.isfinite(value) or value <= 0.0:
        raise ReleaseCapturePlanError("capture PCM post-gain is invalid")
    return value


def _derive_continuous_source_record(
    graph: dict[str, Any],
    row: dict[str, Any],
    property_one_source_verifications: dict[str, dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], str]:
    """Use the direct curve compiler, with its sole public pitch fallback."""

    try:
        curves = derive_manifest_source_curves(graph, row)
    except FmodAuthoredCurveError as exc:
        if exc.code != "unsupportedPropertyIndex":
            raise
        fallback = derive_windowed_capture_fallback(graph, row)
        if fallback.get("schema") != "ac-fmod-authored-windowed-capture-fallback-v1":
            raise ReleaseCapturePlanError(
                "windowed curve oracle returned an unknown schema"
            )
        verification = (property_one_source_verifications or {}).get(
            str(row["sourceGuid"])
        )
        if verification is None:
            return fallback, _ADAPTIVE_RPM_WINDOW_PROJECTION
        certified = certify_property_one_relative_rate(fallback, verification)
        if certified.get("schema") != "ac-fmod-certified-property-one-relative-rate-v1":
            raise ReleaseCapturePlanError(
                "property-one source certificate returned an unknown schema"
            )
        return certified, _CERTIFIED_PROPERTY_ONE_PROJECTION
    if curves.get("schema") != AUTHORED_CURVE_SCHEMA:
        raise ReleaseCapturePlanError("authored curve oracle returned an unknown schema")
    return curves, _NO_SOURCE_PROJECTION


_GATE_CONTROL = {
    "rpms": "ENGINE_RPM",
    "drivetrain_speed": "DRIVETRAIN_SPEED",
    "throttle": "ACCELERATOR",
    "state": "SHIFT_STATE",
    "boost": "BOOST",
    "bov": "BOV",
    "bov_decay": "BOV_DECAY",
    "decay": "DECAY",
}

_PROGRAM_TRIGGER_BY_TRACK_TRIGGER = {
    "bov": "BOV_LIFT",
    "turboEvent": "TURBO_EVENT",
    "limiterEvent": "LIMITER_EVENT",
    "shiftUp": "SHIFT_UP",
    "shiftDown": "SHIFT_DOWN",
    "overrunRelease": "THROTTLE_LIFT",
    "pop": "THROTTLE_LIFT",
    "bang": "THROTTLE_LIFT",
    "crack": "THROTTLE_LIFT",
    "engineEvent": "ENGINE_EVENT",
    "engineStart": "ENGINE_START",
}
_PROGRAM_TRIGGERS = frozenset(_PROGRAM_TRIGGER_BY_TRACK_TRIGGER.values())
_PROGRAM_PLAY_MODES = frozenset(("SEQUENTIAL", "SMART_RANDOM"))
_PROGRAM_SELECTION_MODES = frozenset(("NORMAL",))
_GATE_CONTROLS = frozenset(_GATE_CONTROL.values())
_NO_SOURCE_PROJECTION = "NONE"
_SHARED_SOURCE_IDLE_PROJECTION = "SHARED_SOURCE_IDLE"
_ADAPTIVE_RPM_WINDOW_PROJECTION = "ADAPTIVE_RPM_WINDOWS"
_CERTIFIED_PROPERTY_ONE_PROJECTION = "CERTIFIED_PROPERTY_ONE_RELATIVE_RATE"
_CERTIFIED_AUTHORED_SILENCE_PROJECTION = "CERTIFIED_AUTHORED_SILENCE"
_CERTIFIED_TURBO_TRANSIENT_PROJECTION = "CERTIFIED_TURBO_TRANSIENT"
_ENGINE_INT_EVENT_START_PROJECTION = "ENGINE_INT_EVENT_START"
ENGINE_INT_EVENT_START_PROJECTION = _ENGINE_INT_EVENT_START_PROJECTION
_PROJECTED_IDLE_CLASSIFICATION_ROLE = "SHARED_SOURCE_IDLE_PROJECTION"
ENGINE_START_DURATION_FRAMES = 144000


def _append_engine_start_recipe(
    recipes: list[dict[str, Any]],
    representative: dict[str, Any],
    rows: list[dict[str, Any]],
) -> None:
    """Optional event-start crank slice from engine_int; omitted when capture is inaudible."""

    idle_recipes = [
        item
        for item in recipes
        if item["role"] == "IDLE" and _event_suffix(item["eventPath"]) == "engine_int"
    ]
    if not idle_recipes:
        return
    idle_recipe = idle_recipes[0]
    row = next(
        (item for item in rows if str(item["sourceGuid"]) == idle_recipe["sourceGuid"]),
        None,
    )
    if row is None:
        return
    idle_rpm = float(representative["engine"]["idleRpm"])
    recipes.append(
        _recipe(
            row,
            "ENGINE_START",
            idle_recipe["eventPath"],
            parameters={"rpms": idle_rpm, "throttle": 0.0},
            root_rpm=idle_rpm,
            rpm_curve=[[0.0, 1.0], [1.0, 1.0]],
            gain_curve=[[0.0, 1.0], [1.0, 1.0]],
            curve_sha256=None,
            source_projection=_ENGINE_INT_EVENT_START_PROJECTION,
        )
    )


def _normalized_parameter_gates(
    row: dict[str, Any], event_path: str, manifest_role: str
) -> list[dict[str, Any]]:
    memberships = (row.get("decisionEvidence") or {}).get("eventMemberships", [])
    membership = next(
        (item for item in memberships if isinstance(item, dict) and item.get("path") == event_path),
        None,
    )
    if membership is None:
        raise ReleaseCapturePlanError("one-shot source membership/event disagree")
    raw = (membership.get("triggerTopology") or {}).get("placements", [])
    intervals: dict[str, list[tuple[float, float, bool]]] = defaultdict(list)
    for item in raw:
        if not isinstance(item, dict) or item.get("kind") != "parameter":
            continue
        name = str(item.get("parameterName") or "").casefold()
        control = _GATE_CONTROL.get(name)
        if control is None:
            raise ReleaseCapturePlanError(
                f"one-shot source uses unsupported runtime gate {name or 'unnamed'}"
            )
        start = _finite(item.get("start"), f"{name} gate start")
        end = _finite(item.get("end"), f"{name} gate end")
        if end < start:
            raise ReleaseCapturePlanError(f"{name} gate is reversed")
        intervals[control].append((start, end, item.get("includeEnd") is True))

    result: list[dict[str, Any]] = []
    for control, items in sorted(intervals.items()):
        if control == "SHIFT_STATE":
            if manifest_role == "SHIFT_UP":
                minimum, maximum = 0.0, 1.0
                include_maximum = True
            elif manifest_role == "SHIFT_DOWN":
                minimum, maximum = -1.0, 0.0
                include_maximum = False
            else:
                raise ReleaseCapturePlanError("SHIFT_STATE gate belongs to a non-shift role")
        else:
            minimum = max(item[0] for item in items)
            maximum = min(item[1] for item in items)
            include_maximum = all(item[2] for item in items if item[1] == maximum)
            if maximum < minimum:
                raise ReleaseCapturePlanError(f"{control} gate intersection is empty")
            if control == "DRIVETRAIN_SPEED":
                scale = 60.0 / (2.0 * math.pi)
                minimum *= scale
                maximum *= scale
            elif control == "ACCELERATOR":
                minimum = min(1.0, max(0.0, minimum))
                maximum = min(1.0, max(0.0, maximum))
        result.append(
            {
                "control": control,
                "minimum": round(minimum, 8),
                "maximum": round(maximum, 8),
                "includeMinimum": True,
                "includeMaximum": include_maximum,
            }
        )
    return result


def _instrument_chance(instrument: dict[str, Any]) -> float:
    properties = instrument.get("baseProperties")
    if not isinstance(properties, dict):
        raise ReleaseCapturePlanError("one-shot topology node has no base properties")
    percent = _finite(properties.get("triggerChancePercent"), "trigger chance")
    if not 0.0 <= percent <= 100.0:
        raise ReleaseCapturePlanError("trigger chance is outside 0..100 percent")
    return round(percent / 100.0, 8)


def _one_shot_programs(
    graph: dict[str, Any],
    rows: list[dict[str, Any]],
    recipes: list[dict[str, Any]],
    engine_transient_curves: dict[str, dict[str, Any]],
    limiter_programs: dict[str, dict[str, Any]],
    turbo_transient_sources: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    instruments = {
        _guid(item.get("guid")): item
        for item in graph["instruments"]
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    parents: dict[str, set[str]] = defaultdict(set)
    for parent_guid, instrument in instruments.items():
        children = instrument.get("childInstruments", [])
        if not isinstance(children, list):
            raise ReleaseCapturePlanError("one-shot topology children are invalid")
        for child in children:
            child_guid = _guid(child.get("guid")) if isinstance(child, dict) else _guid(child)
            if child_guid:
                parents[child_guid].add(parent_guid)
    rows_by_guid = {str(row["sourceGuid"]): row for row in rows}
    recipes_by_program: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for recipe in recipes:
        # Persistent limiter sources are event programs even when their
        # authored realization is a steady timeline-period or waveform loop.
        # Continuous engine/transmission beds are the only tracks outside the
        # program topology.
        if recipe["role"] in LOOP_ROLES:
            continue
        triggers = recipe["triggers"]
        if len(triggers) != 1:
            raise ReleaseCapturePlanError("one-shot recipe must have exactly one trigger")
        placement_root = ""
        if recipe["role"] == "ENGINE_TRANSIENT":
            authored = engine_transient_curves.get(recipe["sourceGuid"])
            if authored is None:
                raise ReleaseCapturePlanError(
                    "engine transient recipe has no authored program record"
                )
            placement_root = _guid(authored["programPlacementRootInstrumentGuid"])
            if not placement_root:
                raise ReleaseCapturePlanError(
                    "engine transient program placement root is absent"
                )
        elif recipe["role"] in {"BOV", "TURBO_TRANSIENT"}:
            authored = turbo_transient_sources.get(recipe["sourceGuid"])
            if authored is None:
                raise ReleaseCapturePlanError(
                    "turbo-event recipe has no authored program record"
                )
            placement_root = _guid(
                authored["programPlacementRootInstrumentGuid"]
            )
            if not placement_root:
                raise ReleaseCapturePlanError(
                    "turbo-event program placement root is absent"
                )
        recipes_by_program[(recipe["eventPath"], triggers[0], placement_root)].append(
            recipe
        )

    programs: list[dict[str, Any]] = []
    for (event_path, trigger, placement_root), program_recipes in sorted(
        recipes_by_program.items()
    ):
        leaf_guids = {recipe["sourceGuid"] for recipe in program_recipes}
        included = set(leaf_guids)
        for leaf in sorted(leaf_guids):
            current = leaf
            visited: set[str] = set()
            while parents.get(current) and current != placement_root:
                choices = sorted(parents[current])
                if len(choices) != 1:
                    raise ReleaseCapturePlanError("one-shot topology is not a tree")
                current = choices[0]
                if current in visited:
                    raise ReleaseCapturePlanError("one-shot topology contains a cycle")
                visited.add(current)
                included.add(current)
            if placement_root and current != placement_root:
                raise ReleaseCapturePlanError(
                    "one-shot leaf does not descend from its placement root"
                )

        def node_id(guid: str) -> str:
            kind = instruments[guid].get("kind")
            return ("group_" if kind == "MultiInstrumentNode" else "track_") + guid.replace("-", "")

        nodes: list[dict[str, Any]] = []
        for guid in sorted(included):
            instrument = instruments.get(guid)
            if instrument is None:
                raise ReleaseCapturePlanError("one-shot topology references a missing node")
            kind = instrument.get("kind")
            if kind == "MultiInstrumentNode":
                playlist = instrument.get("playlist")
                if not isinstance(playlist, dict):
                    raise ReleaseCapturePlanError("one-shot group has no playlist metadata")
                play_value = int(playlist.get("playModeValue", -1))
                selection_value = int(playlist.get("selectionModeValue", -1))
                play_mode = {0: "SEQUENTIAL", 2: "SMART_RANDOM"}.get(play_value)
                selection_mode = {1: "NORMAL"}.get(selection_value)
                if play_mode is None or selection_mode is None:
                    raise ReleaseCapturePlanError("one-shot playlist mode is unsupported")
                children = instrument.get("childInstruments", [])
                all_weight = sum(
                    max(0.0, float(child.get("weight", 0.0)))
                    for child in children
                    if isinstance(child, dict)
                )
                members = []
                retained_weight = 0.0
                for order, child in enumerate(children):
                    if not isinstance(child, dict):
                        raise ReleaseCapturePlanError("one-shot group child is invalid")
                    child_guid = _guid(child.get("guid"))
                    weight = _finite(child.get("weight"), "one-shot child weight")
                    if weight <= 0.0:
                        raise ReleaseCapturePlanError("one-shot child weight is not positive")
                    if child_guid not in included:
                        continue
                    retained_weight += weight
                    members.append(
                        {
                            "nodeId": node_id(child_guid),
                            "weight": weight,
                            # The runtime program has no silence node.  Preserve
                            # relative authored order after fail-closed pruning
                            # and use the required contiguous primitive index.
                            "order": len(members),
                        }
                    )
                if not members or all_weight <= 0.0:
                    raise ReleaseCapturePlanError("one-shot retained group is empty")
                chance = _instrument_chance(instrument) * (retained_weight / all_weight)
                nodes.append(
                    {
                        "id": node_id(guid),
                        "kind": "GROUP",
                        "triggerChance": round(chance, 8),
                        "playMode": play_mode,
                        "selectionMode": selection_mode,
                        "members": members,
                    }
                )
            elif kind == "WaveformInstrumentNode":
                recipe = next(
                    (item for item in program_recipes if item["sourceGuid"] == guid),
                    None,
                )
                if recipe is None:
                    raise ReleaseCapturePlanError("one-shot leaf has no retained track recipe")
                row = rows_by_guid.get(guid)
                if row is None:
                    raise ReleaseCapturePlanError("one-shot leaf has no classification row")
                authored = engine_transient_curves.get(guid)
                authored_limiter = limiter_programs.get(guid)
                authored_turbo = turbo_transient_sources.get(guid)
                parameter_gates = _normalized_parameter_gates(
                    row, event_path, recipe["role"]
                )
                if authored is not None:
                    templates = authored["programTriggerTemplate"]["parameterRegions"]
                    if len(templates) > 1:
                        raise ReleaseCapturePlanError(
                            "engine transient API emitted multiple region programs"
                        )
                    parameter_gates = (
                        templates[0]["parameterGates"] if templates else []
                    )
                elif authored_limiter is not None:
                    # The persistent limiter policy owns decay scheduling.
                    # Mirroring its placement here would make the generic
                    # one-shot scheduler retrigger the decoded track.
                    parameter_gates = []
                elif authored_turbo is not None:
                    # Turbo-event scheduling is represented once by the
                    # program policy.  A duplicate generic gate here would
                    # suppress event-start scheduling and change re-entry.
                    parameter_gates = []
                node: dict[str, Any] = {
                        "id": node_id(guid),
                        "kind": "TRACK",
                        "trackId": recipe["id"],
                        "triggerChance": _instrument_chance(instrument),
                        "parameterGates": parameter_gates,
                        "rpmCurve": recipe["rpmCurve"],
                        "gainCurve": recipe["gainCurve"],
                        "liveVarispeed": authored is not None,
                        "rootRpm": recipe["rootRpm"] if authored is not None else None,
                    }
                if authored_turbo is not None:
                    capture_controls: list[dict[str, Any]] = []
                    for name, value in sorted(
                        authored_turbo["captureParameterValues"].items()
                    ):
                        control = _GATE_CONTROL.get(str(name).casefold())
                        if control not in {"BOOST", "BOV", "BOV_DECAY"}:
                            raise ReleaseCapturePlanError(
                                f"turbo-event capture uses unsupported control {name!r}"
                            )
                        capture_controls.append(
                            {"control": control, "value": float(value)}
                        )
                    control_curves: list[dict[str, Any]] = []
                    for name, curve in sorted(
                        authored_turbo["controlGainCurves"].items()
                    ):
                        control = _GATE_CONTROL.get(str(name).casefold())
                        if control not in {"BOOST", "BOV", "BOV_DECAY"}:
                            raise ReleaseCapturePlanError(
                                f"turbo-event gain uses unsupported control {name!r}"
                            )
                        control_curves.append({"control": control, "curve": curve})
                    pitch_automations: list[dict[str, Any]] = []
                    for raw_automation in authored_turbo["pitchAutomation"]:
                        if not isinstance(raw_automation, dict):
                            raise ReleaseCapturePlanError(
                                "turbo-event pitch automation is invalid"
                            )
                        control = _GATE_CONTROL.get(
                            str(raw_automation.get("parameter") or "").casefold()
                        )
                        if control not in {"BOOST", "BOV", "BOV_DECAY"}:
                            raise ReleaseCapturePlanError(
                                "turbo-event pitch uses an unsupported control"
                            )
                        pitch_automations.append(
                            {
                                "control": control,
                                **{
                                    key: raw_automation[key]
                                    for key in (
                                        "propertyIndex",
                                        "rawValueToSemitonesScale",
                                        "captureSemitones",
                                        "playbackRateCurve",
                                        "runtimeTreatment",
                                        "updatesWhileVoiceActive",
                                        "continuesOutsideSchedulingRegion",
                                        "captureRate",
                                    )
                                },
                            }
                        )
                    verification_sha = (authored_turbo.get("fidelity") or {}).get(
                        "sourceVerificationPayloadSha256"
                    )
                    node.update(
                        {
                            "captureControlValues": capture_controls,
                            "controlGainCurves": control_curves,
                            "pitchAutomations": pitch_automations,
                            "sourceVerificationPayloadSha256": verification_sha,
                        }
                    )
                nodes.append(node)
            else:
                raise ReleaseCapturePlanError(
                    f"one-shot topology uses unsupported node kind {kind!r}"
                )
        root_guids = sorted(
            guid for guid in included if not (parents.get(guid, set()) & included)
        )
        if not root_guids:
            raise ReleaseCapturePlanError("one-shot program has no root nodes")
        id_suffix = (
            f"_{placement_root.replace('-', '')[:12]}" if placement_root else ""
        )
        program_id = re.sub(
            r"[^a-z0-9._-]+",
            "_", f"{trigger}_{_event_suffix(event_path)}{id_suffix}".casefold(),
        ).strip("_")
        program_trigger = _PROGRAM_TRIGGER_BY_TRACK_TRIGGER.get(trigger)
        if program_trigger is None:
            raise ReleaseCapturePlanError(
                f"one-shot track trigger {trigger!r} has no runtime program mapping"
            )
        leaf_priorities = {
            item.get("softwareChannelPriority") for item in program_recipes
        }
        if len(leaf_priorities) != 1:
            raise ReleaseCapturePlanError(
                "one-shot program leaves disagree on FMOD software-channel priority"
            )
        program: dict[str, Any] = {
            "id": program_id,
            "trigger": program_trigger,
            "softwareChannelPriority": next(iter(leaf_priorities)),
            "capturedFromEventStart": True,
            "rootNodeIds": [node_id(guid) for guid in root_guids],
            "nodes": sorted(nodes, key=lambda item: item["id"]),
            }
        if placement_root and program_trigger == "ENGINE_EVENT":
            records = [engine_transient_curves[item["sourceGuid"]] for item in program_recipes]
            templates = [item["programTriggerTemplate"] for item in records]
            template = templates[0]
            if any(item != template for item in templates[1:]):
                raise ReleaseCapturePlanError(
                    "engine transient siblings disagree on their program state machine"
                )
            program["policy"] = {
                "kind": "ENGINE_EVENT_REGION",
                "parameterRegions": template["parameterRegions"],
                "armingMode": template["armingMode"],
                "initiallyOutsideBehavior": template["initiallyOutsideBehavior"],
                "rearmMode": template["rearmMode"],
                "overlapMode": template["overlapMode"],
                "exitBehavior": template["exitBehavior"],
                "coreProgram": True,
                "auditionable": False,
                "maxDecodedOneShotFrameCount": None,
                "laneCount": None,
                "logicalVoiceLimit": 2048,
                "softwareRealVoiceBudget": 256,
            }
        elif placement_root and program_trigger == "TURBO_EVENT":
            records = [
                turbo_transient_sources[item["sourceGuid"]]
                for item in program_recipes
            ]
            fields = (
                "programMode",
                "programPlacementRootInstrumentGuid",
                "placementSignature",
                "programTriggerTemplate",
                "voicePolicy",
                "runtimeControlSemantics",
            )
            for field in fields:
                first = records[0][field]
                if any(item[field] != first for item in records[1:]):
                    raise ReleaseCapturePlanError(
                        f"turbo-event siblings disagree on {field}"
                    )
            resolved_roles = {item["resolvedManifestRole"] for item in records}
            if len(resolved_roles) != 1:
                raise ReleaseCapturePlanError(
                    "turbo-event siblings disagree on their manifest role"
                )
            resolved_role = next(iter(resolved_roles))
            program["policy"] = {
                "kind": "TURBO_EVENT_PROGRAM",
                **{field: records[0][field] for field in fields},
                "coreProgram": resolved_role == "TURBO_TRANSIENT",
                # The dedicated audition command is intentionally limited to
                # pops/bangs/cracks.  BOV remains a natural Turbo-effect
                # program and must never make that mislabeled command appear.
                "auditionable": False,
            }
        elif program_trigger == "LIMITER_EVENT":
            records = [limiter_programs[item["sourceGuid"]] for item in program_recipes]
            record = records[0]
            if any(item != record for item in records[1:]):
                raise ReleaseCapturePlanError(
                    "limiter siblings disagree on their persistent event contract"
                )
            program["policy"] = {
                "kind": "PERSISTENT_LIMITER_EVENT",
                "programMode": record["programMode"],
                "sourceLifetime": record["sourceLifetime"],
                "decayParameter": record["decayParameter"],
                "decayGainCurve": record["decayGainCurve"],
                "decayPlacement": record["decayPlacement"],
                "timelinePlacement": record["timelinePlacement"],
                "runtimeLifecycle": record["runtimeLifecycle"],
                "sourceScheduling": record["sourceScheduling"],
                "voicePolicy": record["voicePolicy"],
                "targetCaptureBakedModulators": record[
                    "targetCaptureBakedModulators"
                ],
                "sourceVerificationPayloadSha256": record["fidelity"][
                    "sourceVerificationPayloadSha256"
                ],
            }
        programs.append(program)
    return programs


def _release_family(
    family: dict[str, Any],
    representative: dict[str, Any],
    graph: dict[str, Any],
    graph_path: Path,
    limiter_source_verifications: dict[str, dict[str, Any]] | None = None,
    shift_silence_source_verifications: dict[str, dict[str, Any]] | None = None,
    turbo_transient_source_verifications: dict[str, dict[str, Any]] | None = None,
    continuous_disposition_source_verifications: dict[str, dict[str, Any]] | None = None,
    property_one_source_verifications: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    validate_bank_graph_report(graph)
    if str((graph.get("bank") or {}).get("sha256")) != family["id"]:
        raise ReleaseCapturePlanError("graph/source family SHA-256 mismatch")
    classified = classify_bank_graph_sources(graph)
    rows = classified["sources"]
    if len(rows) != int(classified["counts"]["sourceInstruments"]):
        raise ReleaseCapturePlanError("classifier did not emit every waveform source")

    # Cabin one-shots are authoritative when present.  External engine sources
    # remain retained as EXHAUST character, but duplicate external shift and
    # backfire events are not mixed into the cabin presentation.
    has_internal_gear = any(
        row["policy"] == POLICY_ALLOW_CANDIDATE
        and row["role"] == ROLE_GEAR_SHIFT
        and "gear_int" in row["eventSuffixes"]
        for row in rows
    )
    has_internal_backfire = any(
        row["policy"] == POLICY_ALLOW_CANDIDATE
        and row["role"] == ROLE_OVERRUN_TRANSIENT
        and "backfire_int" in row["eventSuffixes"]
        for row in rows
    )

    engine_candidates: list[
        tuple[dict[str, Any], dict[str, Any], str]
    ] = []
    engine_transient_curves: dict[str, dict[str, Any]] = {}
    limiter_programs: dict[str, dict[str, Any]] = {}
    turbo_transient_sources: dict[str, dict[str, Any]] = {}
    excluded_idle_rows: list[dict[str, Any]] = []
    other_rows: list[dict[str, Any]] = []
    source_omissions: list[dict[str, Any]] = []
    omission_counts: Counter[str] = Counter()
    for row in rows:
        source_guid = str(row["sourceGuid"])
        dynamic_disposition = (
            continuous_disposition_source_verifications or {}
        ).get(source_guid)
        if dynamic_disposition is not None:
            if (
                row["policy"] != POLICY_ALLOW_CANDIDATE
                or row.get("lifetime") != "continuous"
                or row["role"] not in _AUTHORED_CONTINUOUS_ROLES
                or dynamic_disposition.get("familyId") != family["id"]
                or dynamic_disposition.get("eventPath")
                not in row.get("eventPaths", [])
                or dynamic_disposition.get("staticClassifierRole") != row["role"]
            ):
                raise ReleaseCapturePlanError(
                    "dynamic continuous-source disposition disagrees with graph role"
                )
            derived = derive_manifest_source_curves(graph, row)
            if (
                _sha256_bytes(canonical_json_bytes(derived))
                != dynamic_disposition.get("derivedSourceSha256")
            ):
                raise ReleaseCapturePlanError(
                    "dynamic continuous-source disposition derivation changed"
                )
            disposition = str(dynamic_disposition["disposition"])
            source_omissions.append(
                {
                    "sourceGuid": source_guid,
                    "eventPath": dynamic_disposition["eventPath"],
                    "staticClassifierRole": row["role"],
                    "disposition": disposition,
                    "verificationPayloadSha256": dynamic_disposition[
                        "verificationPayloadSha256"
                    ],
                }
            )
            omission_counts[
                "forbiddenOnPedalRouting"
                if disposition == "FORBIDDEN_ON_PEDAL_ROUTING"
                else "certifiedAuthoredTargetRoutedSilent"
            ] += 1
            continue
        policy = row["policy"]
        if policy == POLICY_EXCLUDE:
            if (
                row["role"] == ROLE_EXCLUDED_LOAD
                and row.get("lifetime") == "continuous"
                and "engine_int" in row["eventSuffixes"]
            ):
                excluded_idle_rows.append(row)
            omission_counts["excludedByRolePolicy"] += 1
            continue
        if policy == POLICY_OUT_OF_SCOPE:
            omission_counts["nonCore"] += 1
            continue
        if policy == POLICY_AMBIGUOUS:
            omission_counts["ambiguousFailClosed"] += 1
            continue
        if policy != POLICY_ALLOW_CANDIDATE:
            raise ReleaseCapturePlanError(f"unknown source policy {policy!r}")
        if row["role"] == ROLE_GEAR_GRIND:
            omission_counts["nonNormalDrivingTransmissionTransient"] += 1
            continue
        if row["role"] not in _RETAINED_CLASSIFIER_ROLES:
            raise ReleaseCapturePlanError(f"unhandled allowed role {row['role']!r}")
        suffixes = set(row["eventSuffixes"])
        if row["role"] == ROLE_GEAR_SHIFT and has_internal_gear and "gear_int" not in suffixes:
            omission_counts["externalPerspectiveAlternate"] += 1
            continue
        if (
            row["role"] == ROLE_OVERRUN_TRANSIENT
            and has_internal_backfire
            and "backfire_int" not in suffixes
        ):
            omission_counts["externalPerspectiveAlternate"] += 1
            continue
        if row["role"] in _CONTINUOUS_ENGINE_ROLES:
            if row.get("lifetime") == "continuous":
                curves, projection = _derive_continuous_source_record(
                    graph, row, property_one_source_verifications
                )
                engine_candidates.append((row, curves, projection))
            elif row.get("lifetime") == "oneShot":
                if "engine_int" not in suffixes:
                    omission_counts["externalEngineTransientAuditOnly"] += 1
                    continue
                curves = derive_manifest_one_shot_curves(graph, row)
                if curves.get("schema") != ONE_SHOT_CURVE_SCHEMA:
                    raise ReleaseCapturePlanError(
                        "one-shot curve oracle returned an unknown schema"
                    )
                engine_transient_curves[str(row["sourceGuid"])] = curves
                other_rows.append({**row, "_authoredOneShotCurves": curves})
            else:
                raise ReleaseCapturePlanError(
                    "allowed engine source has an unsupported lifetime"
                )
        elif row["role"] == ROLE_ENGINE_TRANSIENT:
            if row.get("lifetime") != "oneShot":
                raise ReleaseCapturePlanError(
                    "engine transition source is not authored as a one-shot"
                )
            if "engine_int" not in suffixes:
                omission_counts["externalEngineTransientAuditOnly"] += 1
                continue
            curves = derive_manifest_one_shot_curves(graph, row)
            if curves.get("schema") != ONE_SHOT_CURVE_SCHEMA:
                raise ReleaseCapturePlanError(
                    "one-shot curve oracle returned an unknown schema"
                )
            engine_transient_curves[str(row["sourceGuid"])] = curves
            other_rows.append({**row, "_authoredOneShotCurves": curves})
        elif row["role"] == ROLE_TRANSMISSION:
            curves, projection = _derive_continuous_source_record(
                graph, row, property_one_source_verifications
            )
            other_rows.append(
                {**row, "_authoredCurves": curves, "_curveProjection": projection}
            )
        elif row["role"] == ROLE_LIMITER:
            if limiter_source_verifications is None:
                _proof, limiter_source_verifications = (
                    load_limiter_source_verifications()
                )
            unverified_limiter = derive_manifest_limiter_program(graph, row)
            verification = limiter_source_verifications.get(
                str(row["sourceGuid"])
            )
            if verification is None:
                raise ReleaseCapturePlanError(
                    f"limiter source {row['sourceGuid']} has no source-bound proof"
                )
            limiter = certify_manifest_limiter_program(
                unverified_limiter, verification
            )
            if limiter.get("schema") != LIMITER_PROGRAM_SCHEMA:
                raise ReleaseCapturePlanError(
                    "limiter oracle returned an unknown schema"
                )
            limiter_programs[str(row["sourceGuid"])] = limiter
            other_rows.append({**row, "_authoredLimiterProgram": limiter})
        elif row["role"] == ROLE_TURBO_TRANSIENT:
            if turbo_transient_source_verifications is None:
                _proof, turbo_transient_source_verifications = (
                    load_turbo_transient_source_verifications()
                )
            unverified_turbo = derive_manifest_turbo_transient_source(graph, row)
            verification = turbo_transient_source_verifications.get(
                str(row["sourceGuid"])
            )
            if verification is None:
                raise ReleaseCapturePlanError(
                    f"turbo source {row['sourceGuid']} has no source-bound proof"
                )
            if verification.get("familyId") != family["id"]:
                raise ReleaseCapturePlanError(
                    "turbo source-bound proof family identity changed"
                )
            authored_turbo = certify_manifest_turbo_transient_source(
                unverified_turbo, verification
            )
            if authored_turbo.get("schema") != TURBO_TRANSIENT_SOURCE_SCHEMA:
                raise ReleaseCapturePlanError(
                    "turbo-transient oracle returned an unknown schema"
                )
            if (
                authored_turbo.get("fidelity", {}).get("exactnessClaim") is not True
                or authored_turbo.get("fidelity", {}).get("requiredFinalGate")
                != "satisfiedBySourceBoundTurboVerification"
            ):
                raise ReleaseCapturePlanError(
                    "turbo-transient source did not pass its final gate"
                )
            turbo_transient_sources[str(row["sourceGuid"])] = authored_turbo
            other_rows.append(
                {**row, "_authoredTurboTransient": authored_turbo}
            )
        else:
            other_rows.append(row)

    idle_rpm = float(representative["engine"]["idleRpm"])
    idle_candidates: set[str] = set()
    for row, curves, _projection in engine_candidates:
        if _event_suffix(curves["eventPath"]) != "engine_int":
            continue
        audible = _curve_value(curves["rpmCurve"], idle_rpm) * _curve_value(
            curves["gainCurve"], 0.0
        )
        if audible > 1.0e-5:
            idle_candidates.add(row["sourceGuid"])
    idle_projection: tuple[dict[str, Any], dict[str, Any]] | None = None
    if not idle_candidates:
        projection_candidates: list[
            tuple[float, str, dict[str, Any], dict[str, Any]]
        ] = []
        for row in excluded_idle_rows:
            if not _source_region_contains(row, "engine_int", "rpms", idle_rpm):
                continue
            projection = _derive_shared_source_idle_projection(graph, row)
            rpm_gain = _curve_value(projection["rpmCurve"], idle_rpm)
            if rpm_gain <= 1.0e-5:
                continue
            release_db = _finite(
                (row.get("throttleVolume") or {}).get("releaseDb"),
                "idle projection released-pedal gain",
            )
            score_db = release_db + 20.0 * math.log10(rpm_gain)
            projection_candidates.append(
                (score_db, str(row["sourceGuid"]), row, projection)
            )
        if not projection_candidates:
            raise ReleaseCapturePlanError(
                f"{representative['id']} has no authored cabin engine source audible at idle"
            )
        _score, _guid_value, projection_row, projection = max(
            projection_candidates,
            key=lambda item: (item[0], item[1]),
        )
        idle_projection = (projection_row, projection)
        omission_counts["sharedSourceRisingControlRemoved"] += 1

    recipes: list[dict[str, Any]] = []
    if idle_projection is not None:
        row, projection = idle_projection
        projected_recipe_row = {
            **row,
            "role": _PROJECTED_IDLE_CLASSIFICATION_ROLE,
        }
        projection_sha = _sha256_bytes(canonical_json_bytes(projection))
        recipes.append(
            _recipe(
                projected_recipe_row,
                "IDLE",
                projection["eventPath"],
                parameters=projection["captureParameterValues"],
                root_rpm=float(projection["captureRootRpm"]),
                rpm_curve=projection["rpmCurve"],
                gain_curve=projection["gainCurve"],
                curve_sha256=projection_sha,
                source_projection=_SHARED_SOURCE_IDLE_PROJECTION,
                capture_pcm_post_gain_linear=projection[
                    "capturePcmPostGainLinear"
                ],
            )
        )
    for row, curves, curve_projection in engine_candidates:
        suffix = _event_suffix(curves["eventPath"])
        if suffix == "engine_ext":
            role = "EXHAUST"
        elif row["sourceGuid"] in idle_candidates:
            role = "IDLE"
        elif row["role"] == ROLE_ENGINE_INDEPENDENT:
            role = "TEXTURE"
        else:
            role = "COAST"
        curve_sha = _sha256_bytes(canonical_json_bytes(curves))
        recipe = _recipe(
            row,
            role,
            curves["eventPath"],
            parameters={
                str(key): float(value)
                for key, value in curves["captureParameterValues"].items()
            },
            root_rpm=float(curves["captureRootRpm"]),
            rpm_curve=curves["rpmCurve"],
            gain_curve=curves["gainCurve"],
            curve_sha256=curve_sha,
            source_projection=curve_projection,
            capture_pcm_post_gain_linear=_capture_pcm_post_gain_linear(curves),
            pitch_mode=(
                PROPERTY_ONE_PITCH_MODE
                if curve_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION
                else AUTO_PITCH_MODE
            ),
            pitch_curve=(
                curves["pitchCurve"]
                if curve_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION
                else []
            ),
            pitch_curve_interpolation=(
                PROPERTY_ONE_INTERPOLATION
                if curve_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION
                else "NONE"
            ),
        )
        if curve_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION:
            recipe["durationFrames"] = int(curves["capture"]["frameCount"])
            recipe["warmupFrames"] = 0
        recipes.append(recipe)

    for row in other_rows:
        event_path = _source_event_path(row)
        source_role = row["role"]
        authored_turbo = row.get("_authoredTurboTransient")
        if source_role == ROLE_ENGINE_TRANSIENT or row.get("_authoredOneShotCurves"):
            roles = ("ENGINE_TRANSIENT",)
        elif source_role == ROLE_TRANSMISSION:
            roles = ("TRANSMISSION",)
        elif source_role == ROLE_TURBO_CONTINUOUS:
            roles = ("TURBO",)
        elif source_role == ROLE_TURBO_TRANSIENT:
            if authored_turbo is None:
                raise ReleaseCapturePlanError(
                    "turbo transient has no authored source record"
                )
            roles = (str(authored_turbo["resolvedManifestRole"]),)
        elif source_role == ROLE_LIMITER:
            roles = ("LIMITER",)
        elif source_role == ROLE_OVERRUN_TRANSIENT:
            roles = ("OVERRUN",)
        elif source_role == ROLE_GEAR_SHIFT:
            roles = _gear_roles(row, event_path)
        else:  # pragma: no cover - guarded by the retained-role set above.
            raise ReleaseCapturePlanError(f"unhandled retained role {source_role}")
        for role in roles:
            authored_one_shot = row.get("_authoredOneShotCurves")
            authored_curves = row.get("_authoredCurves")
            authored_limiter = row.get("_authoredLimiterProgram")
            source_projection = row.get("_curveProjection", _NO_SOURCE_PROJECTION)
            if authored_turbo is not None:
                source_projection = _CERTIFIED_TURBO_TRANSIENT_PROJECTION
            if authored_one_shot is not None:
                curve_sha = _sha256_bytes(canonical_json_bytes(authored_one_shot))
                parameters = {
                    str(key): float(value)
                    for key, value in authored_one_shot[
                        "captureParameterValues"
                    ].items()
                }
                root_rpm = float(authored_one_shot["rootRpm"])
                rpm_curve = authored_one_shot["rpmCurve"]
                gain_curve = authored_one_shot["gainCurve"]
            elif authored_curves is not None:
                curve_sha = _sha256_bytes(canonical_json_bytes(authored_curves))
                parameters = {
                    str(key): float(value)
                    for key, value in authored_curves["captureParameterValues"].items()
                }
                root_rpm = float(authored_curves["captureRootRpm"])
                rpm_curve = authored_curves["rpmCurve"]
                gain_curve = authored_curves["gainCurve"]
            elif authored_limiter is not None:
                curve_sha = _sha256_bytes(canonical_json_bytes(authored_limiter))
                parameters = {
                    str(key): float(value)
                    for key, value in authored_limiter[
                        "captureParameterValues"
                    ].items()
                }
                root_rpm = None
                rpm_curve = []
                gain_curve = [[0.0, 1.0], [1.0, 1.0]]
            elif authored_turbo is not None:
                curve_sha = _sha256_bytes(canonical_json_bytes(authored_turbo))
                parameters = {
                    str(key): float(value)
                    for key, value in authored_turbo[
                        "captureParameterValues"
                    ].items()
                }
                root_rpm = None
                rpm_curve = []
                # Relative turbo gain and property-1 playback-rate curves are
                # leaf-program controls; the generic pedal curve stays unity.
                gain_curve = [[0.0, 1.0], [1.0, 1.0]]
            elif role == "TURBO":
                curve_sha = None
                parameters = _placement_parameters(row, event_path, role)
                root_rpm = None
                rpm_curve = []
                # Android applies the selected car's authored boost-controller
                # response separately; a pedal curve here would square it.
                gain_curve = [[0.0, 1.0], [1.0, 1.0]]
            else:
                curve_sha = None
                parameters = _placement_parameters(row, event_path, role)
                root_rpm = None
                rpm_curve = []
                gain_curve = [[0.0, 1.0], [1.0, 1.0]]
            recipe = _recipe(
                    row,
                    role,
                    event_path,
                    parameters=parameters,
                    root_rpm=root_rpm,
                    rpm_curve=rpm_curve,
                    gain_curve=gain_curve,
                    curve_sha256=curve_sha,
                    source_projection=source_projection,
                    capture_pcm_post_gain_linear=(
                        _capture_pcm_post_gain_linear(authored_one_shot)
                        if authored_one_shot is not None
                        else _capture_pcm_post_gain_linear(authored_curves)
                        if authored_curves is not None
                        else 1.0
                    ),
                )
            shift_verification = (
                (shift_silence_source_verifications or {}).get(
                    str(row["sourceGuid"])
                )
                if role in {"SHIFT_UP", "SHIFT_DOWN"}
                else None
            )
            if shift_verification is not None:
                certificate = certify_silent_shift_source(
                    graph,
                    row,
                    shift_verification,
                    graph_report_sha256=_sha256_file(graph_path),
                    representative_car_id=str(representative["id"]),
                    installed_bank_relative_path=str(
                        representative["provenance"]["bankPath"]
                    ),
                )
                if certificate["role"] != role:
                    raise ReleaseCapturePlanError(
                        "silent-shift certificate direction disagrees with recipe"
                    )
                recipe["sourceProjection"] = (
                    _CERTIFIED_AUTHORED_SILENCE_PROJECTION
                )
                recipe["sourceCurveSha256"] = certificate[
                    "verificationPayloadSha256"
                ]
            if authored_limiter is not None:
                verified_pcm = authored_limiter["verifiedTargetPcm"]
                recipe["durationFrames"] = int(verified_pcm["frameCount"])
                recipe["warmupFrames"] = 0
                recipe["looping"] = authored_limiter["programMode"] in {
                    "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT",
                    "PERSISTENT_DECAY_REGION_LOOP",
                }
            elif authored_turbo is not None:
                verified_pcm = authored_turbo["verifiedTargetPcm"]
                recipe["durationFrames"] = int(verified_pcm["frameCount"])
                recipe["warmupFrames"] = 0
                recipe["looping"] = (
                    verified_pcm.get("loopStartFrame") is not None
                    and verified_pcm.get("loopEndFrameExclusive") is not None
                )
            recipes.append(recipe)

    _append_engine_start_recipe(recipes, representative, rows)
    recipes.sort(key=lambda item: (item["role"], item["eventPath"], item["sourceGuid"]))
    identifiers = [item["id"] for item in recipes]
    if len(identifiers) != len(set(identifiers)):
        raise ReleaseCapturePlanError("release track identifiers collide")
    if len(recipes) > 256:
        raise ReleaseCapturePlanError("release family exceeds the runtime track limit")
    if not any(item["role"] == "IDLE" for item in recipes):
        raise ReleaseCapturePlanError("release family has no authored IDLE")

    one_shot_programs = _one_shot_programs(
        graph,
        rows,
        recipes,
        engine_transient_curves,
        limiter_programs,
        turbo_transient_sources,
    )

    policy_counts = Counter(row["policy"] for row in rows)
    return {
        "familyId": family["id"],
        "representativeCarId": representative["id"],
        "memberCarIds": family["memberIds"],
        "graphReportSha256": _sha256_file(graph_path),
        "classificationSha256": _sha256_bytes(canonical_json_bytes(classified)),
        "sourceCoverage": {
            "classified": len(rows),
            "policyAllowed": policy_counts[POLICY_ALLOW_CANDIDATE],
            "policyExcluded": policy_counts[POLICY_EXCLUDE],
            "policyNonCore": policy_counts[POLICY_OUT_OF_SCOPE],
            "policyAmbiguous": policy_counts[POLICY_AMBIGUOUS],
            "retainedRecipes": len(recipes),
            "omissions": dict(sorted(omission_counts.items())),
        },
        "sourceOmissions": sorted(
            source_omissions, key=lambda item: item["sourceGuid"]
        ),
        "oneShotPrograms": one_shot_programs,
        "recipes": recipes,
    }


def build_release_capture_plan(
    catalog: dict[str, Any],
    graph_root: Path = DEFAULT_GRAPH_ROOT,
    *,
    assetto_root: Path | None = None,
    empirical_cache_root: Path | None = None,
    limiter_proof_path: Path = DEFAULT_LIMITER_ORACLE_PROOF,
    shift_silence_proof_path: Path = DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    turbo_transient_proof_path: Path = DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    priority_proof_path: Path = DEFAULT_PRIORITY_ORACLE_PROOF,
    continuous_disposition_proof_path: Path = DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF,
    property_one_proof_path: Path = DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
) -> dict[str, Any]:
    """Build schema v2 from every complete v3 family graph, failing closed."""

    # The optional paths are reserved for the fail-closed empirical curve
    # fallback.  Static-authoring plans do not touch AC or create a cache.
    _ = assetto_root, empirical_cache_root
    validate_catalog(catalog, require_complete=True)
    summary, graph_paths = _load_graph_inventory(Path(graph_root), catalog)
    limiter_proof, limiter_source_verifications = (
        load_limiter_source_verifications(limiter_proof_path)
    )
    shift_silence_proof, shift_silence_source_verifications = (
        load_shift_silence_source_verifications(
            shift_silence_proof_path,
            expected_catalog_sha256=catalog["catalogSha256"],
        )
    )
    turbo_transient_proof, turbo_transient_source_verifications = (
        load_turbo_transient_source_verifications(turbo_transient_proof_path)
    )
    continuous_disposition_proof, continuous_disposition_source_verifications = (
        load_continuous_disposition_source_verifications(
            continuous_disposition_proof_path
        )
    )
    property_one_proof, property_one_source_verifications = (
        load_property_one_source_verifications(property_one_proof_path)
    )
    priority_proof_path = Path(priority_proof_path).resolve(strict=True)
    priority_proof = json.loads(priority_proof_path.read_text(encoding="utf-8"))
    if (
        not isinstance(priority_proof, dict)
        or priority_proof.get("schema")
        != "ac-fmod-source-priority-catalog-oracle-v1"
        or priority_proof.get("result") != "PASS_SOURCE_BOUND_COMPLETE"
        or priority_proof.get("sourceCount") != 907
        or priority_proof.get("rolePriorityCounts")
        != {
            "BANG": {},
            "BOV": {"128": 171},
            "CRACK": {},
            "OVERRUN": {"128": 736},
            "POP": {},
        }
        or not isinstance(
            priority_proof.get("globalArbitrationOracleSha256"), str
        )
        or not _SHA256.fullmatch(
            priority_proof["globalArbitrationOracleSha256"]
        )
    ):
        raise ReleaseCapturePlanError(
            "source-bound software-channel priority proof is incomplete"
        )
    if (
        turbo_transient_proof["inputs"]["priorityProofSha256"]
        != _sha256_file(priority_proof_path)
    ):
        raise ReleaseCapturePlanError(
            "turbo-transient proof was made from another priority proof"
        )
    cars = {str(car["id"]): car for car in catalog["cars"]}
    families = []
    for family in sorted(catalog["soundFamilies"], key=lambda item: str(item["id"])):
        path = graph_paths[str(family["id"])]
        graph = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(graph, dict) or graph.get("schema") != BANK_GRAPH_SCHEMA:
            raise ReleaseCapturePlanError(f"invalid graph report for {family['id']}")
        representative = cars[str(family["representativeCarId"])]
        families.append(
            _release_family(
                family,
                representative,
                graph,
                path,
                limiter_source_verifications,
                shift_silence_source_verifications,
                turbo_transient_source_verifications,
                continuous_disposition_source_verifications,
                property_one_source_verifications,
            )
        )

    planned_dynamic_omission_guids = {
        omission["sourceGuid"]
        for family in families
        for omission in family["sourceOmissions"]
    }
    if planned_dynamic_omission_guids != set(
        continuous_disposition_source_verifications
    ):
        raise ReleaseCapturePlanError(
            "continuous-source disposition proof does not match plan omissions"
        )
    planned_property_one_guids = {
        recipe["sourceGuid"]
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"] == _CERTIFIED_PROPERTY_ONE_PROJECTION
    }
    if planned_property_one_guids != set(property_one_source_verifications):
        raise ReleaseCapturePlanError(
            "property-one proof does not match the complete planned source set"
        )

    planned_silent_shift_guids = {
        recipe["sourceGuid"]
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"]
        == _CERTIFIED_AUTHORED_SILENCE_PROJECTION
    }
    if planned_silent_shift_guids != set(shift_silence_source_verifications):
        raise ReleaseCapturePlanError(
            "shift-silence proof does not match the complete planned source set"
        )

    planned_turbo_source_guids = {
        recipe["sourceGuid"]
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"]
        == _CERTIFIED_TURBO_TRANSIENT_PROJECTION
    }
    if planned_turbo_source_guids != set(turbo_transient_source_verifications):
        raise ReleaseCapturePlanError(
            "turbo-transient proof does not match the complete planned source set"
        )

    expected_priority_sources = {
        (
            family["familyId"],
            recipe["sourceGuid"],
            "BOV" if recipe["role"] == "TURBO_TRANSIENT" else recipe["role"],
        )
        for family in families
        for recipe in family["recipes"]
        if recipe["role"] in {"BOV", "TURBO_TRANSIENT", "OVERRUN"}
    }
    priority_observations = priority_proof.get("sourceObservations")
    if not isinstance(priority_observations, list) or {
        (
            item.get("familyId"),
            item.get("sourceGuid"),
            item.get("manifestRole"),
        )
        for item in priority_observations
        if isinstance(item, dict)
        and item.get("softwareChannelPriority") == 128
    } != expected_priority_sources:
        raise ReleaseCapturePlanError(
            "source-bound priority proof does not match every turbo/overrun recipe"
        )

    plan: dict[str, Any] = {
        "schemaVersion": RELEASE_CAPTURE_PLAN_SCHEMA_VERSION,
        "catalogSha256": catalog["catalogSha256"],
        "graphAudit": {
            "schema": BANK_GRAPH_SCHEMA,
            "summarySha256": _sha256_bytes(canonical_json_bytes(summary)),
            "familyCount": len(families),
            "sourceOffsetsVerified": True,
        },
        "limiterOracle": {
            "schema": limiter_proof["schema"],
            "proofSha256": _sha256_file(Path(limiter_proof_path).resolve()),
            "verifiedSourceCount": 73,
            "audibleTargetPcmCount": 70,
            "authoredTargetSilentCount": 3,
        },
        "shiftSilenceOracle": {
            "schema": shift_silence_proof["schema"],
            "proofSha256": _sha256_file(
                Path(shift_silence_proof_path).resolve()
            ),
            "verifiedSourceCount": len(shift_silence_source_verifications),
            "authoredTargetSilentCount": len(
                shift_silence_source_verifications
            ),
        },
        "turboTransientOracle": {
            "schema": turbo_transient_proof["schema"],
            "proofSha256": _sha256_file(
                Path(turbo_transient_proof_path).resolve()
            ),
            "verifiedSourceCount": 171,
            "verifiedFamilyCount": 59,
            "verifiedProgramRootCount": 105,
            "audibleTargetPcmCount": 160,
            "authoredTargetSilentCount": 11,
            "maximumCaptureRelativeControlGain": turbo_transient_proof[
                "catalogBounds"
            ]["maximumCaptureRelativeControlGain"]["value"],
            "manifestControlGainMaximumInclusive": (
                TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE
            ),
        },
        "continuousSourceOracle": {
            "schema": continuous_disposition_proof["schema"],
            "proofSha256": _sha256_file(
                Path(continuous_disposition_proof_path).resolve()
            ),
            "proofPayloadSha256": continuous_disposition_proof[
                "proofPayloadSha256"
            ],
            "forbiddenOnPedalRoutingSourceCount": 4,
            "authoredTargetRoutedSilentSourceCount": 1,
        },
        "propertyOneOracle": {
            "schema": property_one_proof["schema"],
            "proofSha256": _sha256_file(Path(property_one_proof_path).resolve()),
            "proofPayloadSha256": property_one_proof["proofPayloadSha256"],
            "verifiedSourceCount": 5,
            "runtimePitchMode": PROPERTY_ONE_PITCH_MODE,
            "interpolation": PROPERTY_ONE_INTERPOLATION,
            "adaptiveWindowFallbackTrackCount": 0,
        },
        "priorityOracle": {
            "schema": priority_proof["schema"],
            "proofSha256": _sha256_file(priority_proof_path),
            "globalArbitrationOracleSha256": priority_proof[
                "globalArbitrationOracleSha256"
            ],
            "verifiedSourceCount": priority_proof["sourceCount"],
            "acceptedMinimumInclusive": 0,
            "acceptedMaximumInclusive": 256,
            "resolvedRolePriorities": dict(
                sorted(_SOFTWARE_CHANNEL_PRIORITY_BY_MANIFEST_ROLE.items())
            ),
            "unresolvedRoles": sorted(
                _SOFTWARE_CHANNEL_PRIORITY_UNRESOLVED_ROLES
            ),
        },
        "audioFormat": {
            "codec": "FLAC",
            "sampleRate": 48000,
            "channels": 2,
            "bitsPerSample": 16,
            "compressionLevel": 8,
        },
        "curveSemantics": {
            "rpmCurveX": "engineRpm",
            "gainCurveX": "normalizedAccelerator",
            "curveY": "linearAmplitude",
            "interpolation": "clampedLinear",
        },
        "runtimeCurveGate": {
            "durationFrames": CURVE_PROBE_DURATION_FRAMES,
            "warmupFrames": CURVE_PROBE_WARMUP_FRAMES,
            "minimumPredictedAmplitude": CURVE_PROBE_MIN_PREDICTED_AMPLITUDE,
            "maximumRmsRatioErrorDb": CURVE_PROBE_MAX_RATIO_ERROR_DB,
            "probes": "deterministicOffCaptureSpeedAndAccelerator",
        },
        "fidelity": {
            "sourceAudio": "nativeFmodFinalMix",
            "layerIsolation": "sourceInstrument",
            "rpmGainCurve": "authoredSourceInstrument",
            "effectVariants": "authoredOneShotTopology",
            "notes": [
                "Every retained recipe is a single waveform-instrument GUID rendered through the native FMOD final mix.",
                "Engine RPM and accelerator curves are derived from source and ancestor automation without sample-name semantics.",
                "Every continuous engine and transmission curve must pass target-only off-capture one-second RMS ratio probes within 3.0 dB.",
                "Sample names are used only as a runtime callback identity join and are absent from plans and manifests.",
                "One-shot group topology, playlist order and weight, trigger chance, parameter gates, and event-start timing are preserved as primitive runtime programs.",
                "Ambiguous and non-normal-driving gear-grind transients are classified, omitted, and counted fail-closed.",
            ],
        },
        "families": families,
    }
    return validate_release_capture_plan(plan, catalog)


def _validate_engine_event_policy(raw_policy: object, label: str) -> None:
    if not isinstance(raw_policy, dict) or set(raw_policy) != {
        "kind",
        "parameterRegions",
        "armingMode",
        "initiallyOutsideBehavior",
        "rearmMode",
        "overlapMode",
        "exitBehavior",
        "coreProgram",
        "auditionable",
        "maxDecodedOneShotFrameCount",
        "laneCount",
        "logicalVoiceLimit",
        "softwareRealVoiceBudget",
    }:
        raise ReleaseCapturePlanError(f"{label} fields are invalid")
    if (
        raw_policy["kind"] != "ENGINE_EVENT_REGION"
        or raw_policy["armingMode"] != "EVENT_START_INSIDE_REQUIRED"
        or raw_policy["initiallyOutsideBehavior"]
        != "DISABLED_UNTIL_EVENT_RESTART"
        or raw_policy["rearmMode"] != "AFTER_ANY_GATE_EXIT"
        or raw_policy["overlapMode"] != "ALLOW_OVERLAP"
        or raw_policy["exitBehavior"] != "LET_ACTIVE_VOICES_FINISH"
        or raw_policy["coreProgram"] is not True
        or raw_policy["auditionable"] is not False
        or raw_policy["maxDecodedOneShotFrameCount"] is not None
        or raw_policy["laneCount"] is not None
        or raw_policy["logicalVoiceLimit"] != 2048
        or raw_policy["softwareRealVoiceBudget"] != 256
    ):
        raise ReleaseCapturePlanError(f"{label} execution contract is invalid")
    regions = raw_policy["parameterRegions"]
    if not isinstance(regions, list):
        raise ReleaseCapturePlanError(f"{label}.parameterRegions must be an array")
    for region_index, region in enumerate(regions):
        region_label = f"{label}.parameterRegions[{region_index}]"
        if not isinstance(region, dict) or set(region) != {
            "parameterGates",
            "entryEdges",
            "triggerOnEventStartIfInside",
        }:
            raise ReleaseCapturePlanError(f"{region_label} fields are invalid")
        if region["triggerOnEventStartIfInside"] is not True:
            raise ReleaseCapturePlanError(
                f"{region_label}.triggerOnEventStartIfInside must be true"
            )
        gates = region["parameterGates"]
        edges = region["entryEdges"]
        if not isinstance(gates, list) or not isinstance(edges, list):
            raise ReleaseCapturePlanError(f"{region_label} gates/edges are invalid")
        gate_controls: set[str] = set()
        for gate_index, gate in enumerate(gates):
            gate_label = f"{region_label}.parameterGates[{gate_index}]"
            if not isinstance(gate, dict) or set(gate) != {
                "control",
                "minimum",
                "maximum",
                "includeMinimum",
                "includeMaximum",
            }:
                raise ReleaseCapturePlanError(f"{gate_label} fields are invalid")
            control = gate["control"]
            minimum = _finite(gate["minimum"], f"{gate_label}.minimum")
            maximum = _finite(gate["maximum"], f"{gate_label}.maximum")
            if (
                control not in _GATE_CONTROLS
                or control in gate_controls
                or maximum < minimum
                or not isinstance(gate["includeMinimum"], bool)
                or not isinstance(gate["includeMaximum"], bool)
            ):
                raise ReleaseCapturePlanError(f"{gate_label} is invalid")
            gate_controls.add(str(control))
        for edge_index, edge in enumerate(edges):
            edge_label = f"{region_label}.entryEdges[{edge_index}]"
            if not isinstance(edge, dict) or set(edge) != {
                "control",
                "boundary",
                "direction",
                "value",
                "includeBoundary",
            }:
                raise ReleaseCapturePlanError(f"{edge_label} fields are invalid")
            if (
                edge["control"] not in gate_controls
                or edge["boundary"] not in {"MINIMUM", "MAXIMUM"}
                or edge["direction"] not in {"INCREASING", "DECREASING"}
                or not isinstance(edge["includeBoundary"], bool)
            ):
                raise ReleaseCapturePlanError(f"{edge_label} is invalid")
            _finite(edge["value"], f"{edge_label}.value")


def _validate_limiter_event_policy(raw_policy: object, label: str) -> str:
    """Validate the executable-backed persistent AC limiter contract."""

    expected = {
        "kind",
        "programMode",
        "sourceLifetime",
        "decayParameter",
        "decayGainCurve",
        "decayPlacement",
        "timelinePlacement",
        "runtimeLifecycle",
        "sourceScheduling",
        "voicePolicy",
        "targetCaptureBakedModulators",
        "sourceVerificationPayloadSha256",
    }
    if not isinstance(raw_policy, dict) or set(raw_policy) != expected:
        raise ReleaseCapturePlanError(f"{label} fields are invalid")
    mode = raw_policy["programMode"]
    lifetime_by_mode = {
        "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": "oneShot",
        "PERSISTENT_DECAY_REGION_ONE_SHOT": "oneShot",
        "PERSISTENT_DECAY_REGION_LOOP": "continuous",
    }
    if (
        raw_policy["kind"] != "PERSISTENT_LIMITER_EVENT"
        or mode not in lifetime_by_mode
        or raw_policy["sourceLifetime"] != lifetime_by_mode[mode]
    ):
        raise ReleaseCapturePlanError(f"{label} mode/lifetime is invalid")
    if (
        not isinstance(raw_policy["sourceVerificationPayloadSha256"], str)
        or not _SHA256.fullmatch(raw_policy["sourceVerificationPayloadSha256"])
    ):
        raise ReleaseCapturePlanError(
            f"{label}.sourceVerificationPayloadSha256 is invalid"
        )
    if raw_policy["decayParameter"] != {
        "control": "LIMITER_DECAY_SECONDS",
        "minimum": 0.0,
        "maximum": 1.0,
        "defaultValue": 0.0,
        "runtimeInput": "min(hostFloat32DecayTimerSeconds,1)",
    }:
        raise ReleaseCapturePlanError(f"{label}.decayParameter is invalid")
    _validate_curve(
        raw_policy["decayGainCurve"], f"{label}.decayGainCurve", pedal=True
    )
    if (
        not raw_policy["decayGainCurve"]
        or raw_policy["decayGainCurve"][0][0] != 0.0
        or raw_policy["decayGainCurve"][-1][0] != 1.0
    ):
        raise ReleaseCapturePlanError(f"{label}.decayGainCurve must span 0..1")

    placement = raw_policy["decayPlacement"]
    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        if placement is not None:
            raise ReleaseCapturePlanError(f"{label}.decayPlacement must be null")
    else:
        if not isinstance(placement, dict) or set(placement) != {
            "control",
            "minimum",
            "maximum",
            "includeMinimum",
            "includeMaximum",
        }:
            raise ReleaseCapturePlanError(f"{label}.decayPlacement is invalid")
        minimum = _finite(placement["minimum"], f"{label}.decayPlacement.minimum")
        maximum = _finite(placement["maximum"], f"{label}.decayPlacement.maximum")
        if (
            placement["control"] != "LIMITER_DECAY_SECONDS"
            or not 0.0 <= minimum < maximum <= 1.0
            or not isinstance(placement["includeMinimum"], bool)
            or not isinstance(placement["includeMaximum"], bool)
        ):
            raise ReleaseCapturePlanError(f"{label}.decayPlacement values are invalid")

    timeline = raw_policy["timelinePlacement"]
    if mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
        if not isinstance(timeline, dict) or set(timeline) != {
            "startTicks",
            "lengthTicks",
            "timeLocked",
            "tickRateHz",
            "startFrameAt48k",
            "periodFramesAt48k",
        }:
            raise ReleaseCapturePlanError(f"{label}.timelinePlacement is invalid")
        if (
            timeline["startTicks"] != 0
            or timeline["startFrameAt48k"] != 0
            or timeline["timeLocked"] is not True
            or timeline["tickRateHz"] != 48000
            or isinstance(timeline["lengthTicks"], bool)
            or not isinstance(timeline["lengthTicks"], int)
            or timeline["lengthTicks"] <= 0
            or timeline["periodFramesAt48k"] != timeline["lengthTicks"]
        ):
            raise ReleaseCapturePlanError(f"{label}.timelinePlacement values are invalid")
    elif timeline is not None:
        raise ReleaseCapturePlanError(f"{label}.timelinePlacement must be null")

    lifecycle = raw_policy["runtimeLifecycle"]
    if not isinstance(lifecycle, dict) or set(lifecycle) != {
        "owner",
        "initialHostDecayTimerSeconds",
        "updateOrder",
        "eventDesiredActiveWhen",
        "inactiveThreshold",
        "activeEventAction",
        "inactiveEventAction",
        "limiterPulseWhileEventActive",
        "reactivationAfterInactive",
        "executableEvidence",
    }:
        raise ReleaseCapturePlanError(f"{label}.runtimeLifecycle is invalid")
    if lifecycle != {
        "owner": "ONE_PERSISTENT_LIMITER_EVENT_INSTANCE",
        "initialHostDecayTimerSeconds": 10.0,
        "updateOrder": [
            "FLOAT32_TIMER_PLUS_DT",
            "RESET_TIMER_TO_ZERO_IF_LIMITER_PULSE",
            "WRITE_RAW_TIMER_TO_FMOD_DECAY_PARAMETER",
            "UPDATE_EVENT_OWNER_STATE",
        ],
        "eventDesiredActiveWhen": (
            "driveAudioActive && limiterEnabled && hostDecayTimerSeconds<=10"
        ),
        "inactiveThreshold": {
            "comparison": "STRICTLY_GREATER_THAN",
            "seconds": 10.0,
        },
        "activeEventAction": (
            "UNPAUSE_IF_PAUSED_ELSE_REWIND_TIMELINE_ZERO_AND_START_IF_STOPPED"
        ),
        "inactiveEventAction": "STOP_ALLOWFADEOUT",
        "limiterPulseWhileEventActive": (
            "RESET_DECAY_ONLY_PRESERVE_EVENT_TIMELINE_AND_ACTIVE_SOURCE_PHASE"
        ),
        "reactivationAfterInactive": (
            "SET_DECAY_ZERO_THEN_REWIND_TIMELINE_ZERO_THEN_START"
        ),
        "executableEvidence": {
            "timerInitialization": "acs.exe:0x140063038 immediate float32 10.0",
            "timerAndParameterUpdate": "acs.exe:0x140067134-0x14006718c",
            "tenSecondOwnerGate": "acs.exe:0x140067e28-0x140067ea4",
            "rewindThenStart": "acs.exe:0x1401fbf40-0x1401fbfb7",
            "allowFadeStop": "acs.exe:0x1401fc040-0x1401fc07f",
        },
    }:
        raise ReleaseCapturePlanError(
            f"{label}.runtimeLifecycle changed from executable evidence"
        )

    scheduling = raw_policy["sourceScheduling"]
    scheduling_by_mode = {
        "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT": {
            "timelinePeriodicOneShot": "EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER",
            "parameterRegionEntry": None,
            "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
            "placementExitBehavior": "TIMELINE_OWNS_SOURCE_LIFETIME",
            "overlapMode": "ONE_RENDERED_TIMELINE_LOOP_TRACK",
        },
        "PERSISTENT_DECAY_REGION_ONE_SHOT": {
            "timelinePeriodicOneShot": None,
            "parameterRegionEntry": (
                "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY"
            ),
            "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
            "placementExitBehavior": "LET_ACTIVE_ONE_SHOTS_FINISH",
            "overlapMode": "ALLOW_OVERLAPPING_ONE_SHOT_VOICES",
        },
        "PERSISTENT_DECAY_REGION_LOOP": {
            "timelinePeriodicOneShot": None,
            "parameterRegionEntry": (
                "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY"
            ),
            "sameInsideValueBehavior": "DO_NOT_RETRIGGER",
            "placementExitBehavior": (
                "STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY"
            ),
            "overlapMode": "ONE_ACTIVE_LOOP_VOICE",
        },
    }
    if scheduling != scheduling_by_mode[mode]:
        raise ReleaseCapturePlanError(f"{label}.sourceScheduling is invalid")

    voice = raw_policy["voicePolicy"]
    expected_voice = {
        "maximumSimultaneousProgramTracks": (
            None if mode == "PERSISTENT_DECAY_REGION_ONE_SHOT" else 1
        ),
        "oneShotLaneBoundAfterDecode": (
            "min(2048,ceil(decodedOneShotFrames/480))"
            if mode == "PERSISTENT_DECAY_REGION_ONE_SHOT"
            else None
        ),
        "acGlobalLogicalVoiceCap": 2048,
        "acDefaultSoftwareRealVoiceBudget": 256,
    }
    if voice != expected_voice:
        raise ReleaseCapturePlanError(f"{label}.voicePolicy is invalid")

    modulators = raw_policy["targetCaptureBakedModulators"]
    if not isinstance(modulators, list) or len(modulators) > 1:
        raise ReleaseCapturePlanError(
            f"{label}.targetCaptureBakedModulators is invalid"
        )
    for index, modulator in enumerate(modulators):
        item_label = f"{label}.targetCaptureBakedModulators[{index}]"
        if not isinstance(modulator, dict) or set(modulator) != {
            "guid",
            "ownerGuid",
            "type",
            "propertyIndex",
        }:
            raise ReleaseCapturePlanError(f"{item_label} fields are invalid")
        if (
            not isinstance(modulator["guid"], str)
            or not _GUID.fullmatch(modulator["guid"])
            or not isinstance(modulator["ownerGuid"], str)
            or not _GUID.fullmatch(modulator["ownerGuid"])
            or modulator["type"] != "ADSR"
            or modulator["propertyIndex"] != 0
        ):
            raise ReleaseCapturePlanError(f"{item_label} values are invalid")
    return str(mode)


def _validate_one_shot_programs(
    raw_programs: object,
    recipes: list[dict[str, Any]],
    label: str,
) -> None:
    if not isinstance(raw_programs, list):
        raise ReleaseCapturePlanError(f"{label} must be an array")
    program_recipes = {
        recipe["id"]: recipe for recipe in recipes if recipe["role"] not in LOOP_ROLES
    }
    seen_program_ids: set[str] = set()
    seen_track_ids: set[str] = set()
    for program_index, raw_program in enumerate(raw_programs):
        program_label = f"{label}[{program_index}]"
        base_fields = {
            "id",
            "trigger",
            "softwareChannelPriority",
            "capturedFromEventStart",
            "rootNodeIds",
            "nodes",
        }
        expected_fields = base_fields | (
            {"policy"}
            if raw_program.get("trigger")
            in {"ENGINE_EVENT", "LIMITER_EVENT", "TURBO_EVENT"}
            else set()
        ) if isinstance(raw_program, dict) else base_fields
        if not isinstance(raw_program, dict) or set(raw_program) != expected_fields:
            raise ReleaseCapturePlanError(f"{program_label} fields are invalid")
        program_id = raw_program["id"]
        if (
            not isinstance(program_id, str)
            or not _IDENTIFIER.fullmatch(program_id)
            or program_id in seen_program_ids
        ):
            raise ReleaseCapturePlanError(f"{program_label}.id is invalid or duplicated")
        seen_program_ids.add(program_id)
        trigger = raw_program["trigger"]
        if trigger not in _PROGRAM_TRIGGERS:
            raise ReleaseCapturePlanError(f"{program_label}.trigger is unsupported")
        program_priority = raw_program["softwareChannelPriority"]
        if program_priority is not None and (
            isinstance(program_priority, bool)
            or not isinstance(program_priority, int)
            or not 0 <= program_priority <= 256
        ):
            raise ReleaseCapturePlanError(
                f"{program_label}.softwareChannelPriority is invalid"
            )
        if trigger == "ENGINE_EVENT":
            _validate_engine_event_policy(raw_program["policy"], f"{program_label}.policy")
        limiter_mode = (
            _validate_limiter_event_policy(
                raw_program["policy"], f"{program_label}.policy"
            )
            if trigger == "LIMITER_EVENT"
            else None
        )
        turbo_policy = (
            raw_program["policy"]
            if trigger == "TURBO_EVENT"
            else None
        )
        if turbo_policy is not None:
            if not isinstance(turbo_policy, dict) or set(turbo_policy) != {
                "kind",
                "programMode",
                "programPlacementRootInstrumentGuid",
                "placementSignature",
                "programTriggerTemplate",
                "voicePolicy",
                "runtimeControlSemantics",
                "coreProgram",
                "auditionable",
            }:
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo-event fields are invalid"
                )
            mode = turbo_policy["programMode"]
            if (
                turbo_policy["kind"] != "TURBO_EVENT_PROGRAM"
                or mode
                not in {
                    "BOOST_RELEASE_REGION_ONE_SHOT",
                    "TIMELINE_PERIODIC_ONE_SHOT",
                    "PARAMETER_SHEET_EVENT_START_ONE_SHOT",
                }
                or not isinstance(
                    turbo_policy["programPlacementRootInstrumentGuid"], str
                )
                or not _GUID.fullmatch(
                    turbo_policy["programPlacementRootInstrumentGuid"]
                )
                or not isinstance(turbo_policy["placementSignature"], dict)
                or not isinstance(turbo_policy["programTriggerTemplate"], dict)
            ):
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo-event identity is invalid"
                )
            expected_core = mode != "BOOST_RELEASE_REGION_ONE_SHOT"
            if (
                turbo_policy["coreProgram"] is not expected_core
                or turbo_policy["auditionable"] is not False
            ):
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo-event exposure changed"
                )
            trigger_template = turbo_policy["programTriggerTemplate"]
            if mode == "BOOST_RELEASE_REGION_ONE_SHOT":
                if (
                    trigger_template.get("trigger")
                    != "EVENT_START_ARMED_PARAMETER_REGION_REENTRY"
                    or trigger_template.get("armingMode")
                    != "ARMED_WHEN_EVENT_STARTS_INSIDE_OR_OUTSIDE"
                    or trigger_template.get("initiallyOutsideBehavior")
                    != "SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY"
                    or trigger_template.get("rearmMode")
                    != "AFTER_ANY_GATE_EXIT"
                    or trigger_template.get("overlapMode") != "ALLOW_OVERLAP"
                    or trigger_template.get("exitBehavior")
                    != "LET_ACTIVE_VOICES_FINISH"
                ):
                    raise ReleaseCapturePlanError(
                        f"{program_label}.policy boost-release lifecycle changed"
                    )
            elif mode == "TIMELINE_PERIODIC_ONE_SHOT":
                if (
                    trigger_template.get("trigger") != "EVENT_TIMELINE_PERIODIC"
                    or trigger_template.get("overlapMode") != "ALLOW_OVERLAP"
                    or trigger_template.get("exitBehavior") != "NOT_APPLICABLE"
                ):
                    raise ReleaseCapturePlanError(
                        f"{program_label}.policy timeline lifecycle changed"
                    )
            elif (
                trigger_template.get("trigger") != "EVENT_START"
                or trigger_template.get("parameterRegionCoversEntireDomain") is not True
                or trigger_template.get("rearmMode") != "NONE_WITHOUT_EVENT_RESTART"
                or trigger_template.get("overlapMode") != "ONE_VOICE_PER_EVENT_START"
                or trigger_template.get("exitBehavior") != "LET_ACTIVE_VOICE_FINISH"
            ):
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy event-start lifecycle changed"
                )
            voice = turbo_policy["voicePolicy"]
            if not isinstance(voice, dict) or set(voice) != {
                "softwareChannelPriority",
                "priorityRequiredFromSourceBoundOracle",
                "acGlobalLogicalVoiceCap",
                "acDefaultSoftwareRealVoiceBudget",
                "overlapSharesGlobalBudget",
            }:
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo voice policy is invalid"
                )
            inner_priority = voice["softwareChannelPriority"]
            if (
                inner_priority != 128
                or voice["priorityRequiredFromSourceBoundOracle"] is not False
                or voice["acGlobalLogicalVoiceCap"] != 2048
                or voice["acDefaultSoftwareRealVoiceBudget"] != 256
                or voice["overlapSharesGlobalBudget"] is not True
            ):
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo voice contract changed"
                )
            if turbo_policy["runtimeControlSemantics"] != {
                "boost": "AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN",
                "bov": "AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED",
                "bov_decay": "AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED",
                "propertyZero": "DB_VOLUME",
                "propertyOne": "RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE",
                "propertyFour": "LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH",
                "autoPitchFromParameterPlacement": False,
            }:
                raise ReleaseCapturePlanError(
                    f"{program_label}.policy turbo runtime controls changed"
                )
        if raw_program["capturedFromEventStart"] is not True:
            raise ReleaseCapturePlanError(
                f"{program_label}.capturedFromEventStart must be true"
            )
        nodes = raw_program["nodes"]
        roots = raw_program["rootNodeIds"]
        if not isinstance(nodes, list) or not nodes:
            raise ReleaseCapturePlanError(f"{program_label}.nodes must be non-empty")
        if (
            not isinstance(roots, list)
            or not roots
            or any(not isinstance(item, str) for item in roots)
            or len(roots) != len(set(roots))
        ):
            raise ReleaseCapturePlanError(f"{program_label}.rootNodeIds is invalid")
        node_by_id: dict[str, dict[str, Any]] = {}
        child_parent: dict[str, str] = {}
        for node_index, raw_node in enumerate(nodes):
            node_label = f"{program_label}.nodes[{node_index}]"
            if not isinstance(raw_node, dict):
                raise ReleaseCapturePlanError(f"{node_label} must be an object")
            node_id = raw_node.get("id")
            if (
                not isinstance(node_id, str)
                or not _IDENTIFIER.fullmatch(node_id)
                or node_id in node_by_id
            ):
                raise ReleaseCapturePlanError(f"{node_label}.id is invalid or duplicated")
            kind = raw_node.get("kind")
            if kind == "GROUP":
                if set(raw_node) != {
                    "id",
                    "kind",
                    "triggerChance",
                    "playMode",
                    "selectionMode",
                    "members",
                }:
                    raise ReleaseCapturePlanError(f"{node_label} GROUP fields are invalid")
                if raw_node["playMode"] not in _PROGRAM_PLAY_MODES:
                    raise ReleaseCapturePlanError(f"{node_label}.playMode is unsupported")
                if raw_node["selectionMode"] not in _PROGRAM_SELECTION_MODES:
                    raise ReleaseCapturePlanError(
                        f"{node_label}.selectionMode is unsupported"
                    )
                members = raw_node["members"]
                if not isinstance(members, list) or not members:
                    raise ReleaseCapturePlanError(f"{node_label}.members must be non-empty")
                member_ids: set[str] = set()
                member_orders: set[int] = set()
                for member_index, raw_member in enumerate(members):
                    member_label = f"{node_label}.members[{member_index}]"
                    if not isinstance(raw_member, dict) or set(raw_member) != {
                        "nodeId",
                        "weight",
                        "order",
                    }:
                        raise ReleaseCapturePlanError(f"{member_label} fields are invalid")
                    child_id = raw_member["nodeId"]
                    order = raw_member["order"]
                    weight = _finite(raw_member["weight"], f"{member_label}.weight")
                    if (
                        not isinstance(child_id, str)
                        or child_id == node_id
                        or child_id in member_ids
                        or isinstance(order, bool)
                        or not isinstance(order, int)
                        or order < 0
                        or order in member_orders
                        or weight <= 0.0
                    ):
                        raise ReleaseCapturePlanError(f"{member_label} is invalid")
                    member_ids.add(child_id)
                    member_orders.add(order)
                    if child_id in child_parent:
                        raise ReleaseCapturePlanError(
                            f"{member_label}.nodeId has more than one parent"
                        )
                    child_parent[child_id] = node_id
            elif kind == "TRACK":
                track_fields = {
                    "id",
                    "kind",
                    "trackId",
                    "triggerChance",
                    "parameterGates",
                    "rpmCurve",
                    "gainCurve",
                    "liveVarispeed",
                    "rootRpm",
                }
                if trigger == "TURBO_EVENT":
                    track_fields |= {
                        "captureControlValues",
                        "controlGainCurves",
                        "pitchAutomations",
                        "sourceVerificationPayloadSha256",
                    }
                if set(raw_node) != track_fields:
                    raise ReleaseCapturePlanError(f"{node_label} TRACK fields are invalid")
                track_id = raw_node["trackId"]
                recipe = program_recipes.get(track_id)
                if recipe is None or track_id in seen_track_ids:
                    raise ReleaseCapturePlanError(
                        f"{node_label}.trackId is absent or duplicated"
                    )
                expected_trigger = _PROGRAM_TRIGGER_BY_TRACK_TRIGGER.get(
                    recipe["triggers"][0] if len(recipe["triggers"]) == 1 else ""
                )
                if expected_trigger != trigger:
                    raise ReleaseCapturePlanError(
                        f"{node_label}.trackId disagrees with the program trigger"
                    )
                if recipe["softwareChannelPriority"] != program_priority:
                    raise ReleaseCapturePlanError(
                        f"{node_label}.trackId channel priority disagrees with its program"
                    )
                seen_track_ids.add(track_id)
                if trigger == "TURBO_EVENT":
                    if recipe["role"] not in {"BOV", "TURBO_TRANSIENT"}:
                        raise ReleaseCapturePlanError(
                            f"{node_label} turbo program has an incompatible role"
                        )
                    capture_values = raw_node["captureControlValues"]
                    if not isinstance(capture_values, list) or not capture_values:
                        raise ReleaseCapturePlanError(
                            f"{node_label}.captureControlValues is invalid"
                        )
                    capture_controls: set[str] = set()
                    control_maximum = {
                        "BOOST": 1.5,
                        "BOV": 1.0,
                        "BOV_DECAY": 10.0,
                    }
                    for item in capture_values:
                        if not isinstance(item, dict) or set(item) != {"control", "value"}:
                            raise ReleaseCapturePlanError(
                                f"{node_label}.captureControlValues item is invalid"
                            )
                        control = item["control"]
                        value = _finite(item["value"], f"{node_label} capture control")
                        if (
                            control not in {"BOOST", "BOV", "BOV_DECAY"}
                            or control in capture_controls
                            or not 0.0 <= value <= control_maximum[control]
                        ):
                            raise ReleaseCapturePlanError(
                                f"{node_label}.captureControlValues item is invalid"
                            )
                        capture_controls.add(control)
                    expected_capture = [
                        {"control": _GATE_CONTROL[name], "value": value}
                        for name, value in sorted(recipe["parameters"].items())
                    ]
                    if capture_values != expected_capture:
                        raise ReleaseCapturePlanError(
                            f"{node_label}.captureControlValues changed"
                        )
                    gain_controls: set[str] = set()
                    gain_curves = raw_node["controlGainCurves"]
                    if not isinstance(gain_curves, list) or not gain_curves:
                        raise ReleaseCapturePlanError(
                            f"{node_label}.controlGainCurves is invalid"
                        )
                    for item in gain_curves:
                        if not isinstance(item, dict) or set(item) != {"control", "curve"}:
                            raise ReleaseCapturePlanError(
                                f"{node_label}.controlGainCurves item is invalid"
                            )
                        control = item["control"]
                        if (
                            control not in {"BOOST", "BOV", "BOV_DECAY"}
                            or control in gain_controls
                        ):
                            raise ReleaseCapturePlanError(
                                f"{node_label}.controlGainCurves control is invalid"
                            )
                        gain_controls.add(control)
                        curve = item["curve"]
                        if not isinstance(curve, list) or not curve:
                            raise ReleaseCapturePlanError(
                                f"{node_label}.{control} gain is invalid"
                            )
                        previous_x: float | None = None
                        for point in curve:
                            if not isinstance(point, list) or len(point) != 2:
                                raise ReleaseCapturePlanError(
                                    f"{node_label}.{control} gain point is invalid"
                                )
                            x = _finite(point[0], f"{node_label}.{control} gain x")
                            y = _finite(point[1], f"{node_label}.{control} gain y")
                            if (
                                not 0.0 <= x <= control_maximum[control]
                                or not 0.0
                                <= y
                                <= TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE
                                or (previous_x is not None and x <= previous_x)
                            ):
                                raise ReleaseCapturePlanError(
                                    f"{node_label}.{control} gain is invalid"
                                )
                            previous_x = x
                    pitch_controls: set[str] = set()
                    automations = raw_node["pitchAutomations"]
                    if not isinstance(automations, list) or len(automations) > 3:
                        raise ReleaseCapturePlanError(
                            f"{node_label}.pitchAutomations is invalid"
                        )
                    for automation in automations:
                        if not isinstance(automation, dict) or set(automation) != {
                            "control",
                            "propertyIndex",
                            "rawValueToSemitonesScale",
                            "captureSemitones",
                            "playbackRateCurve",
                            "runtimeTreatment",
                            "updatesWhileVoiceActive",
                            "continuesOutsideSchedulingRegion",
                            "captureRate",
                        }:
                            raise ReleaseCapturePlanError(
                                f"{node_label}.pitchAutomations item is invalid"
                            )
                        control = automation["control"]
                        if (
                            control not in {"BOOST", "BOV", "BOV_DECAY"}
                            or control in pitch_controls
                            or automation["propertyIndex"] != 1
                            or automation["rawValueToSemitonesScale"] != 24.0
                            or automation["runtimeTreatment"]
                            != "multiplyActiveVoiceRateContinuously"
                            or automation["updatesWhileVoiceActive"] is not True
                            or automation["continuesOutsideSchedulingRegion"] is not True
                            or automation["captureRate"] != 1.0
                            or not math.isfinite(float(automation["captureSemitones"]))
                        ):
                            raise ReleaseCapturePlanError(
                                f"{node_label}.pitchAutomations contract changed"
                            )
                        pitch_controls.add(control)
                        curve = automation["playbackRateCurve"]
                        if not isinstance(curve, list) or not curve:
                            raise ReleaseCapturePlanError(
                                f"{node_label}.playbackRateCurve is invalid"
                            )
                        previous_x: float | None = None
                        for point in curve:
                            if not isinstance(point, list) or len(point) != 2:
                                raise ReleaseCapturePlanError(
                                    f"{node_label}.playbackRateCurve point is invalid"
                                )
                            x = _finite(point[0], f"{node_label} pitch x")
                            rate = _finite(point[1], f"{node_label} pitch rate")
                            if (
                                not 0.0 <= x <= control_maximum[control]
                                or rate <= 0.0
                                or (previous_x is not None and x <= previous_x)
                            ):
                                raise ReleaseCapturePlanError(
                                    f"{node_label}.playbackRateCurve is invalid"
                                )
                            previous_x = x
                    verification_sha = raw_node[
                        "sourceVerificationPayloadSha256"
                    ]
                    if (
                        not isinstance(verification_sha, str)
                        or not _SHA256.fullmatch(verification_sha)
                    ):
                        raise ReleaseCapturePlanError(
                            f"{node_label}.sourceVerificationPayloadSha256 is invalid"
                        )
                gates = raw_node["parameterGates"]
                if not isinstance(gates, list):
                    raise ReleaseCapturePlanError(f"{node_label}.parameterGates is invalid")
                gate_controls: set[str] = set()
                for gate_index, raw_gate in enumerate(gates):
                    gate_label = f"{node_label}.parameterGates[{gate_index}]"
                    if not isinstance(raw_gate, dict) or set(raw_gate) != {
                        "control",
                        "minimum",
                        "maximum",
                        "includeMinimum",
                        "includeMaximum",
                    }:
                        raise ReleaseCapturePlanError(f"{gate_label} fields are invalid")
                    control = raw_gate["control"]
                    minimum = _finite(raw_gate["minimum"], f"{gate_label}.minimum")
                    maximum = _finite(raw_gate["maximum"], f"{gate_label}.maximum")
                    if (
                        control not in _GATE_CONTROLS
                        or control in gate_controls
                        or maximum < minimum
                        or not isinstance(raw_gate["includeMinimum"], bool)
                        or not isinstance(raw_gate["includeMaximum"], bool)
                    ):
                        raise ReleaseCapturePlanError(f"{gate_label} is invalid")
                    if control == "ACCELERATOR" and not (
                        0.0 <= minimum <= maximum <= 1.0
                    ):
                        raise ReleaseCapturePlanError(
                            f"{gate_label} accelerator bounds are invalid"
                        )
                    if control in {"ENGINE_RPM", "DRIVETRAIN_SPEED"} and minimum < 0.0:
                        raise ReleaseCapturePlanError(
                            f"{gate_label} RPM bounds are invalid"
                        )
                    if control == "SHIFT_STATE" and (minimum, maximum) not in {
                        (-1.0, 0.0),
                        (0.0, 1.0),
                    }:
                        raise ReleaseCapturePlanError(
                            f"{gate_label} shift-state bounds are invalid"
                        )
                    gate_controls.add(control)
                if raw_node["rpmCurve"] != recipe["rpmCurve"]:
                    raise ReleaseCapturePlanError(f"{node_label}.rpmCurve changed")
                if raw_node["gainCurve"] != recipe["gainCurve"]:
                    raise ReleaseCapturePlanError(f"{node_label}.gainCurve changed")
                if recipe["role"] == "ENGINE_TRANSIENT":
                    if (
                        trigger != "ENGINE_EVENT"
                        or raw_node["liveVarispeed"] is not True
                        or recipe["rootRpm"] is None
                        or raw_node["rootRpm"] != recipe["rootRpm"]
                    ):
                        raise ReleaseCapturePlanError(
                            f"{node_label} engine-transient varispeed contract changed"
                        )
                elif raw_node["liveVarispeed"] is not False or raw_node["rootRpm"] is not None:
                    raise ReleaseCapturePlanError(
                        f"{node_label} non-engine effect may not enable live varispeed"
                    )
                _validate_curve(raw_node["rpmCurve"], f"{node_label}.rpmCurve", pedal=False)
                _validate_curve(raw_node["gainCurve"], f"{node_label}.gainCurve", pedal=True)
            else:
                raise ReleaseCapturePlanError(f"{node_label}.kind is unsupported")
            chance = _finite(raw_node.get("triggerChance"), f"{node_label}.triggerChance")
            if not 0.0 <= chance <= 1.0:
                raise ReleaseCapturePlanError(f"{node_label}.triggerChance is invalid")
            node_by_id[node_id] = raw_node
        if limiter_mode is not None:
            limiter_tracks = [
                program_recipes[node["trackId"]]
                for node in nodes
                if node.get("kind") == "TRACK"
            ]
            if len(limiter_tracks) != 1 or limiter_tracks[0]["role"] != "LIMITER":
                raise ReleaseCapturePlanError(
                    f"{program_label} must contain exactly one LIMITER track"
                )
            limiter_recipe = limiter_tracks[0]
            expected_looping = limiter_mode in {
                "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT",
                "PERSISTENT_DECAY_REGION_LOOP",
            }
            if limiter_recipe["looping"] is not expected_looping:
                raise ReleaseCapturePlanError(
                    f"{program_label} limiter loop realization disagrees with policy"
                )
            if limiter_mode == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT":
                period = raw_program["policy"]["timelinePlacement"][
                    "periodFramesAt48k"
                ]
                if (
                    limiter_recipe["durationFrames"] != period
                    or limiter_recipe["warmupFrames"] != 0
                ):
                    raise ReleaseCapturePlanError(
                        f"{program_label} timeline capture period changed: "
                        f"duration={limiter_recipe['durationFrames']} "
                        f"warmup={limiter_recipe['warmupFrames']} period={period}"
                    )
        if turbo_policy is not None:
            turbo_tracks = [
                program_recipes[node["trackId"]]
                for node in nodes
                if node.get("kind") == "TRACK"
            ]
            roles = {item["role"] for item in turbo_tracks}
            expected_role = (
                "BOV"
                if turbo_policy["programMode"]
                == "BOOST_RELEASE_REGION_ONE_SHOT"
                else "TURBO_TRANSIENT"
            )
            if roles != {expected_role}:
                raise ReleaseCapturePlanError(
                    f"{program_label} turbo-event role/program mode changed"
                )
            expected_looping = (
                turbo_policy["programMode"] == "TIMELINE_PERIODIC_ONE_SHOT"
            )
            if any(
                recipe["sourceProjection"]
                != _CERTIFIED_TURBO_TRANSIENT_PROJECTION
                or recipe["looping"] is not expected_looping
                or recipe["warmupFrames"] != 0
                for recipe in turbo_tracks
            ):
                raise ReleaseCapturePlanError(
                    f"{program_label} turbo certified PCM lifetime changed"
                )
        if set(roots) != set(node_by_id) - set(child_parent):
            raise ReleaseCapturePlanError(
                f"{program_label}.rootNodeIds do not exactly name the roots"
            )
        if any(child not in node_by_id for child in child_parent):
            raise ReleaseCapturePlanError(f"{program_label} references a missing member node")
        reachable: set[str] = set()
        active: set[str] = set()

        def visit(node_id: str) -> None:
            if node_id in active:
                raise ReleaseCapturePlanError(f"{program_label} contains a cycle")
            if node_id in reachable:
                return
            active.add(node_id)
            node = node_by_id[node_id]
            if node["kind"] == "GROUP":
                for member in sorted(node["members"], key=lambda item: item["order"]):
                    visit(member["nodeId"])
            active.remove(node_id)
            reachable.add(node_id)

        for root in roots:
            visit(root)
        if reachable != set(node_by_id):
            raise ReleaseCapturePlanError(f"{program_label} contains unreachable nodes")
    if seen_track_ids != set(program_recipes):
        raise ReleaseCapturePlanError(
            f"{label} does not reference every non-loop recipe exactly once"
        )


def validate_release_capture_plan(
    plan: object,
    catalog: dict[str, Any],
    *,
    require_renderable: bool = True,
) -> dict[str, Any]:
    """Strict schema-v2 validation independent of graph-file availability."""

    validate_catalog(catalog)
    if not isinstance(plan, dict):
        raise ReleaseCapturePlanError("capture plan must be an object")
    expected_top = {
        "schemaVersion",
        "catalogSha256",
        "graphAudit",
        "limiterOracle",
        "shiftSilenceOracle",
        "turboTransientOracle",
        "continuousSourceOracle",
        "propertyOneOracle",
        "priorityOracle",
        "audioFormat",
        "curveSemantics",
        "runtimeCurveGate",
        "fidelity",
        "families",
    }
    if set(plan) != expected_top or plan.get("schemaVersion") != 2:
        raise ReleaseCapturePlanError("release plan top-level fields are invalid")
    if plan["catalogSha256"] != catalog["catalogSha256"]:
        raise ReleaseCapturePlanError("release plan was made from another catalog")
    graph_audit = plan["graphAudit"]
    if (
        not isinstance(graph_audit, dict)
        or set(graph_audit)
        != {"schema", "summarySha256", "familyCount", "sourceOffsetsVerified"}
        or graph_audit["schema"] != BANK_GRAPH_SCHEMA
        or not isinstance(graph_audit["summarySha256"], str)
        or not _SHA256.fullmatch(graph_audit["summarySha256"])
        or graph_audit["sourceOffsetsVerified"] is not True
    ):
        raise ReleaseCapturePlanError("release graph-audit provenance is invalid")
    if plan["audioFormat"] != {
        "codec": "FLAC",
        "sampleRate": 48000,
        "channels": 2,
        "bitsPerSample": 16,
        "compressionLevel": 8,
    }:
        raise ReleaseCapturePlanError("release audio format is invalid")
    limiter_oracle = plan["limiterOracle"]
    if (
        not isinstance(limiter_oracle, dict)
        or set(limiter_oracle)
        != {
            "schema",
            "proofSha256",
            "verifiedSourceCount",
            "audibleTargetPcmCount",
            "authoredTargetSilentCount",
        }
        or limiter_oracle["schema"] != "ac-fmod-limiter-lifecycle-oracle-v1"
        or not isinstance(limiter_oracle["proofSha256"], str)
        or not _SHA256.fullmatch(limiter_oracle["proofSha256"])
        or limiter_oracle["verifiedSourceCount"] != 73
        or limiter_oracle["audibleTargetPcmCount"] != 70
        or limiter_oracle["authoredTargetSilentCount"] != 3
    ):
        raise ReleaseCapturePlanError("release limiter oracle provenance is invalid")
    shift_silence_oracle = plan["shiftSilenceOracle"]
    if (
        not isinstance(shift_silence_oracle, dict)
        or set(shift_silence_oracle)
        != {
            "schema",
            "proofSha256",
            "verifiedSourceCount",
            "authoredTargetSilentCount",
        }
        or shift_silence_oracle["schema"]
        != "ac-fmod-shift-silence-oracle-v1"
        or not isinstance(shift_silence_oracle["proofSha256"], str)
        or not _SHA256.fullmatch(shift_silence_oracle["proofSha256"])
        or shift_silence_oracle["verifiedSourceCount"] != 2
        or shift_silence_oracle["authoredTargetSilentCount"] != 2
    ):
        raise ReleaseCapturePlanError(
            "release shift-silence oracle provenance is invalid"
        )
    turbo_oracle = plan["turboTransientOracle"]
    if (
        not isinstance(turbo_oracle, dict)
        or set(turbo_oracle)
        != {
            "schema",
            "proofSha256",
            "verifiedSourceCount",
            "verifiedFamilyCount",
            "verifiedProgramRootCount",
            "audibleTargetPcmCount",
            "authoredTargetSilentCount",
            "maximumCaptureRelativeControlGain",
            "manifestControlGainMaximumInclusive",
        }
        or turbo_oracle["schema"]
        != "ac-fmod-turbo-transient-oracle-v1"
        or not isinstance(turbo_oracle["proofSha256"], str)
        or not _SHA256.fullmatch(turbo_oracle["proofSha256"])
        or turbo_oracle["verifiedSourceCount"] != 171
        or turbo_oracle["verifiedFamilyCount"] != 59
        or turbo_oracle["verifiedProgramRootCount"] != 105
        or turbo_oracle["audibleTargetPcmCount"] != 160
        or turbo_oracle["authoredTargetSilentCount"] != 11
        or turbo_oracle["maximumCaptureRelativeControlGain"]
        != 37.50169480863333
        or turbo_oracle["manifestControlGainMaximumInclusive"]
        != TURBO_CONTROL_GAIN_MAXIMUM_INCLUSIVE
    ):
        raise ReleaseCapturePlanError(
            "release turbo-transient oracle provenance is invalid"
        )
    continuous_oracle = plan["continuousSourceOracle"]
    if (
        not isinstance(continuous_oracle, dict)
        or set(continuous_oracle)
        != {
            "schema",
            "proofSha256",
            "proofPayloadSha256",
            "forbiddenOnPedalRoutingSourceCount",
            "authoredTargetRoutedSilentSourceCount",
        }
        or continuous_oracle["schema"]
        != "ac-fmod-continuous-static-disposition-oracle-v1"
        or any(
            not isinstance(continuous_oracle.get(key), str)
            or not _SHA256.fullmatch(continuous_oracle[key])
            for key in ("proofSha256", "proofPayloadSha256")
        )
        or continuous_oracle["forbiddenOnPedalRoutingSourceCount"] != 4
        or continuous_oracle["authoredTargetRoutedSilentSourceCount"] != 1
    ):
        raise ReleaseCapturePlanError(
            "release continuous-source oracle provenance is invalid"
        )
    property_one_oracle = plan["propertyOneOracle"]
    if (
        not isinstance(property_one_oracle, dict)
        or set(property_one_oracle)
        != {
            "schema",
            "proofSha256",
            "proofPayloadSha256",
            "verifiedSourceCount",
            "runtimePitchMode",
            "interpolation",
            "adaptiveWindowFallbackTrackCount",
        }
        or property_one_oracle["schema"]
        != "ac-fmod-property-one-relative-rate-oracle-v1"
        or any(
            not isinstance(property_one_oracle.get(key), str)
            or not _SHA256.fullmatch(property_one_oracle[key])
            for key in ("proofSha256", "proofPayloadSha256")
        )
        or property_one_oracle["verifiedSourceCount"] != 5
        or property_one_oracle["runtimePitchMode"] != PROPERTY_ONE_PITCH_MODE
        or property_one_oracle["interpolation"] != PROPERTY_ONE_INTERPOLATION
        or property_one_oracle["adaptiveWindowFallbackTrackCount"] != 0
    ):
        raise ReleaseCapturePlanError(
            "release property-one oracle provenance is invalid"
        )
    priority_oracle = plan["priorityOracle"]
    if (
        not isinstance(priority_oracle, dict)
        or set(priority_oracle)
        != {
            "schema",
            "proofSha256",
            "globalArbitrationOracleSha256",
            "verifiedSourceCount",
            "acceptedMinimumInclusive",
            "acceptedMaximumInclusive",
            "resolvedRolePriorities",
            "unresolvedRoles",
        }
        or priority_oracle["schema"]
        != "ac-fmod-source-priority-catalog-oracle-v1"
        or not isinstance(priority_oracle["proofSha256"], str)
        or not _SHA256.fullmatch(priority_oracle["proofSha256"])
        or not isinstance(
            priority_oracle["globalArbitrationOracleSha256"], str
        )
        or not _SHA256.fullmatch(
            priority_oracle["globalArbitrationOracleSha256"]
        )
        or priority_oracle["verifiedSourceCount"] != 907
        or priority_oracle["acceptedMinimumInclusive"] != 0
        or priority_oracle["acceptedMaximumInclusive"] != 256
        or priority_oracle["resolvedRolePriorities"]
        != dict(sorted(_SOFTWARE_CHANNEL_PRIORITY_BY_MANIFEST_ROLE.items()))
        or priority_oracle["unresolvedRoles"]
        != sorted(_SOFTWARE_CHANNEL_PRIORITY_UNRESOLVED_ROLES)
    ):
        raise ReleaseCapturePlanError("release priority oracle provenance is invalid")
    if plan["curveSemantics"] != {
        "rpmCurveX": "engineRpm",
        "gainCurveX": "normalizedAccelerator",
        "curveY": "linearAmplitude",
        "interpolation": "clampedLinear",
    }:
        raise ReleaseCapturePlanError("release curve semantics are invalid")
    if plan["runtimeCurveGate"] != {
        "durationFrames": CURVE_PROBE_DURATION_FRAMES,
        "warmupFrames": CURVE_PROBE_WARMUP_FRAMES,
        "minimumPredictedAmplitude": CURVE_PROBE_MIN_PREDICTED_AMPLITUDE,
        "maximumRmsRatioErrorDb": CURVE_PROBE_MAX_RATIO_ERROR_DB,
        "probes": "deterministicOffCaptureSpeedAndAccelerator",
    }:
        raise ReleaseCapturePlanError("release runtime curve gate is invalid")
    fidelity = plan["fidelity"]
    if (
        not isinstance(fidelity, dict)
        or set(fidelity)
        != {"sourceAudio", "layerIsolation", "rpmGainCurve", "effectVariants", "notes"}
        or fidelity["sourceAudio"] != "nativeFmodFinalMix"
        or fidelity["layerIsolation"] != "sourceInstrument"
        or fidelity["rpmGainCurve"] != "authoredSourceInstrument"
        or fidelity["effectVariants"] != "authoredOneShotTopology"
        or not isinstance(fidelity["notes"], list)
        or any(not isinstance(item, str) or not item for item in fidelity["notes"])
    ):
        raise ReleaseCapturePlanError("release fidelity declaration is invalid")

    families = plan["families"]
    catalog_families = {str(item["id"]): item for item in catalog["soundFamilies"]}
    if (
        not isinstance(families, list)
        or len(families) != len(catalog_families)
        or {item.get("familyId") for item in families if isinstance(item, dict)}
        != set(catalog_families)
        or graph_audit["familyCount"] != len(catalog_families)
    ):
        raise ReleaseCapturePlanError("release plan does not cover every family")
    for family_index, family in enumerate(families):
        label = f"families[{family_index}]"
        if not isinstance(family, dict) or set(family) != {
            "familyId",
            "representativeCarId",
            "memberCarIds",
            "graphReportSha256",
            "classificationSha256",
            "sourceCoverage",
            "sourceOmissions",
            "oneShotPrograms",
            "recipes",
        }:
            raise ReleaseCapturePlanError(f"{label} fields are invalid")
        catalog_family = catalog_families[str(family["familyId"])]
        if (
            family["representativeCarId"] != catalog_family["representativeCarId"]
            or family["memberCarIds"] != catalog_family["memberIds"]
        ):
            raise ReleaseCapturePlanError(f"{label} disagrees with the catalog")
        for hash_name in ("graphReportSha256", "classificationSha256"):
            if not isinstance(family[hash_name], str) or not _SHA256.fullmatch(family[hash_name]):
                raise ReleaseCapturePlanError(f"{label}.{hash_name} is invalid")
        coverage = family["sourceCoverage"]
        if not isinstance(coverage, dict) or set(coverage) != {
            "classified",
            "policyAllowed",
            "policyExcluded",
            "policyNonCore",
            "policyAmbiguous",
            "retainedRecipes",
            "omissions",
        }:
            raise ReleaseCapturePlanError(f"{label}.sourceCoverage is invalid")
        for key in set(coverage) - {"omissions"}:
            if isinstance(coverage[key], bool) or not isinstance(coverage[key], int) or coverage[key] < 0:
                raise ReleaseCapturePlanError(f"{label}.sourceCoverage.{key} is invalid")
        omissions = coverage["omissions"]
        if not isinstance(omissions, dict) or any(
            not isinstance(key, str)
            or not key
            or isinstance(value, bool)
            or not isinstance(value, int)
            or value < 0
            for key, value in omissions.items()
        ):
            raise ReleaseCapturePlanError(f"{label}.sourceCoverage.omissions is invalid")
        if coverage["classified"] != (
            coverage["policyAllowed"]
            + coverage["policyExcluded"]
            + coverage["policyNonCore"]
            + coverage["policyAmbiguous"]
        ):
            raise ReleaseCapturePlanError(f"{label} classifier coverage does not balance")

        source_omissions = family["sourceOmissions"]
        if not isinstance(source_omissions, list):
            raise ReleaseCapturePlanError(f"{label}.sourceOmissions is invalid")
        omission_guids: set[str] = set()
        for omission_index, omission in enumerate(source_omissions):
            omission_label = f"{label}.sourceOmissions[{omission_index}]"
            if (
                not isinstance(omission, dict)
                or set(omission)
                != {
                    "sourceGuid",
                    "eventPath",
                    "staticClassifierRole",
                    "disposition",
                    "verificationPayloadSha256",
                }
                or not isinstance(omission.get("sourceGuid"), str)
                or not _GUID.fullmatch(omission["sourceGuid"])
                or omission["sourceGuid"] in omission_guids
                or not isinstance(omission.get("eventPath"), str)
                or omission["disposition"]
                not in {
                    "FORBIDDEN_ON_PEDAL_ROUTING",
                    "AUTHORED_TARGET_ROUTED_SILENT",
                }
                or not isinstance(omission.get("verificationPayloadSha256"), str)
                or not _SHA256.fullmatch(omission["verificationPayloadSha256"])
            ):
                raise ReleaseCapturePlanError(f"{omission_label} is invalid")
            omission_guids.add(omission["sourceGuid"])

        recipes = family["recipes"]
        if not isinstance(recipes, list) or coverage["retainedRecipes"] != len(recipes):
            raise ReleaseCapturePlanError(f"{label}.recipes is invalid")
        identifiers: set[str] = set()
        event_names = {str(path).rsplit("/", 1)[-1].casefold() for path in catalog_family["events"]}
        for recipe_index, recipe in enumerate(recipes):
            recipe_label = f"{label}.recipes[{recipe_index}]"
            expected = {
                "id",
                "role",
                "event",
                "eventPath",
                "sourceGuid",
                "sourceClassificationRole",
                "sourceProjection",
                "sourceCurveSha256",
                "capturePcmPostGainLinear",
                "parameters",
                "rootRpm",
                "looping",
                "durationFrames",
                "warmupFrames",
                "rpmCurve",
                "gainCurve",
                "pitchMode",
                "pitchCurve",
                "pitchCurveInterpolation",
                "triggers",
                "variantIndex",
                "softwareChannelPriority",
                "softwareChannelPriorityBlocker",
            }
            if not isinstance(recipe, dict) or set(recipe) != expected:
                raise ReleaseCapturePlanError(f"{recipe_label} fields are invalid")
            identifier = recipe["id"]
            if not isinstance(identifier, str) or not _IDENTIFIER.fullmatch(identifier) or identifier in identifiers:
                raise ReleaseCapturePlanError(f"{recipe_label}.id is invalid or duplicated")
            identifiers.add(identifier)
            role = recipe["role"]
            source_projection = recipe["sourceProjection"]
            variable_program_loop = source_projection in {
                _CERTIFIED_TURBO_TRANSIENT_PROJECTION,
            }
            if role not in AUDIO_ROLES or (
                role != "LIMITER"
                and not variable_program_loop
                and recipe["looping"] != (role in LOOP_ROLES)
            ):
                raise ReleaseCapturePlanError(f"{recipe_label}.role/lifetime is invalid")
            if source_projection not in {
                _NO_SOURCE_PROJECTION,
                _SHARED_SOURCE_IDLE_PROJECTION,
                _ADAPTIVE_RPM_WINDOW_PROJECTION,
                _CERTIFIED_PROPERTY_ONE_PROJECTION,
                _CERTIFIED_AUTHORED_SILENCE_PROJECTION,
                _CERTIFIED_TURBO_TRANSIENT_PROJECTION,
                _ENGINE_INT_EVENT_START_PROJECTION,
            }:
                raise ReleaseCapturePlanError(
                    f"{recipe_label}.sourceProjection is invalid"
                )
            if source_projection == _SHARED_SOURCE_IDLE_PROJECTION:
                if (
                    role != "IDLE"
                    or recipe["sourceClassificationRole"]
                    != _PROJECTED_IDLE_CLASSIFICATION_ROLE
                    or recipe["parameters"].get("throttle") != 0.0
                    or recipe["gainCurve"] != [[0.0, 1.0], [1.0, 1.0]]
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} exposes forbidden projected-pedal behavior"
                    )
            elif source_projection == _CERTIFIED_AUTHORED_SILENCE_PROJECTION:
                if (
                    role not in {"SHIFT_UP", "SHIFT_DOWN"}
                    or recipe["sourceClassificationRole"] != ROLE_GEAR_SHIFT
                    or recipe["looping"] is not False
                    or recipe["parameters"]
                    != {"state": 1.0 if role == "SHIFT_UP" else 0.0}
                    or recipe["rootRpm"] is not None
                    or recipe["rpmCurve"] != []
                    or recipe["gainCurve"] != [[0.0, 1.0], [1.0, 1.0]]
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} certified silence contract is invalid"
                    )
            elif source_projection == _CERTIFIED_TURBO_TRANSIENT_PROJECTION:
                if (
                    role not in {"BOV", "TURBO_TRANSIENT"}
                    or recipe["sourceClassificationRole"]
                    != ROLE_TURBO_TRANSIENT
                    or not isinstance(recipe["sourceCurveSha256"], str)
                    or not _SHA256.fullmatch(recipe["sourceCurveSha256"])
                    or recipe["rootRpm"] is not None
                    or recipe["rpmCurve"] != []
                    or recipe["gainCurve"] != [[0.0, 1.0], [1.0, 1.0]]
                    or recipe["warmupFrames"] != 0
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} certified turbo contract is invalid"
                    )
            elif source_projection == _ENGINE_INT_EVENT_START_PROJECTION:
                if (
                    role != "ENGINE_START"
                    or recipe["looping"] is not False
                    or recipe["parameters"].get("throttle") != 0.0
                    or recipe["rootRpm"] is None
                    or recipe["warmupFrames"] != 0
                    or recipe["durationFrames"] != ENGINE_START_DURATION_FRAMES
                    or recipe["triggers"] != ["engineStart"]
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} engine-start event capture contract is invalid"
                    )
            elif source_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION:
                if (
                    role not in LOOP_ROLES
                    or recipe["sourceClassificationRole"]
                    not in _AUTHORED_CONTINUOUS_ROLES
                    or recipe["warmupFrames"] != 0
                    or recipe["rootRpm"] is None
                    or recipe["pitchMode"] != PROPERTY_ONE_PITCH_MODE
                    or recipe["pitchCurveInterpolation"]
                    != PROPERTY_ONE_INTERPOLATION
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} property-one contract is invalid"
                    )
            elif recipe["sourceClassificationRole"] not in _RETAINED_CLASSIFIER_ROLES:
                raise ReleaseCapturePlanError(
                    f"{recipe_label}.sourceClassificationRole is invalid"
                )
            if not isinstance(recipe["sourceGuid"], str) or not _GUID.fullmatch(recipe["sourceGuid"]):
                raise ReleaseCapturePlanError(f"{recipe_label}.sourceGuid is invalid")
            if _finite(
                recipe["capturePcmPostGainLinear"],
                f"{recipe_label}.capturePcmPostGainLinear",
            ) <= 0.0:
                raise ReleaseCapturePlanError(
                    f"{recipe_label}.capturePcmPostGainLinear is invalid"
                )
            event = recipe["event"]
            event_path = recipe["eventPath"]
            if (
                not isinstance(event, str)
                or event.casefold() not in event_names
                or not isinstance(event_path, str)
                or _event_suffix(event_path) != event.casefold()
            ):
                raise ReleaseCapturePlanError(f"{recipe_label}.event is invalid")
            parameters = recipe["parameters"]
            if not isinstance(parameters, dict) or any(
                not isinstance(key, str) or not key or not math.isfinite(float(value))
                for key, value in parameters.items()
            ):
                raise ReleaseCapturePlanError(f"{recipe_label}.parameters are invalid")
            root = recipe["rootRpm"]
            if root is not None and _finite(root, f"{recipe_label}.rootRpm") <= 0.0:
                raise ReleaseCapturePlanError(f"{recipe_label}.rootRpm is invalid")
            if role in {
                "IDLE",
                "COAST",
                "TEXTURE",
                "INTAKE",
                "EXHAUST",
                "ENGINE_TRANSIENT",
            } and root is None:
                raise ReleaseCapturePlanError(f"{recipe_label}.{role} requires rootRpm")
            for name, allow_zero in (("durationFrames", False), ("warmupFrames", True)):
                value = recipe[name]
                if isinstance(value, bool) or not isinstance(value, int) or (value < 0 if allow_zero else value <= 0):
                    raise ReleaseCapturePlanError(f"{recipe_label}.{name} is invalid")
            _validate_curve(recipe["rpmCurve"], f"{recipe_label}.rpmCurve", pedal=False)
            _validate_curve(recipe["gainCurve"], f"{recipe_label}.gainCurve", pedal=True)
            if source_projection == _CERTIFIED_PROPERTY_ONE_PROJECTION:
                try:
                    validate_property_one_pitch_curve(
                        recipe["pitchCurve"],
                        capture_rpm=float(recipe["rootRpm"]),
                        rpm_domain=(
                            float(recipe["rpmCurve"][0][0]),
                            float(recipe["rpmCurve"][-1][0]),
                        ),
                    )
                except ValueError as exc:
                    raise ReleaseCapturePlanError(
                        f"{recipe_label}.pitchCurve is invalid: {exc}"
                    ) from exc
            elif (
                recipe["pitchMode"] != AUTO_PITCH_MODE
                or recipe["pitchCurve"] != []
                or recipe["pitchCurveInterpolation"] != "NONE"
            ):
                raise ReleaseCapturePlanError(
                    f"{recipe_label} ordinary pitch contract is invalid"
                )
            if set(recipe["triggers"]) != V2_TRIGGERS_BY_ROLE[role] or len(recipe["triggers"]) != len(set(recipe["triggers"])):
                raise ReleaseCapturePlanError(f"{recipe_label}.triggers are invalid")
            curve_hash = recipe["sourceCurveSha256"]
            if (
                (
                    recipe["sourceClassificationRole"] in _AUTHORED_CONTINUOUS_ROLES
                    or source_projection == _SHARED_SOURCE_IDLE_PROJECTION
                    or role == "ENGINE_TRANSIENT"
                    or role == "LIMITER"
                    or role in {"BOV", "TURBO_TRANSIENT"}
                    or source_projection == _CERTIFIED_AUTHORED_SILENCE_PROJECTION
                )
                and source_projection != _ENGINE_INT_EVENT_START_PROJECTION
            ):
                if not isinstance(curve_hash, str) or not _SHA256.fullmatch(curve_hash):
                    raise ReleaseCapturePlanError(f"{recipe_label}.sourceCurveSha256 is invalid")
            elif curve_hash is not None:
                raise ReleaseCapturePlanError(f"{recipe_label}.sourceCurveSha256 must be null")
            if recipe["variantIndex"] != 0:
                raise ReleaseCapturePlanError(f"{recipe_label}.variantIndex must be zero")
            priority = recipe["softwareChannelPriority"]
            blocker = recipe["softwareChannelPriorityBlocker"]
            expected_priority = _SOFTWARE_CHANNEL_PRIORITY_BY_MANIFEST_ROLE.get(role)
            if expected_priority is None:
                if (
                    priority is not None
                    or blocker != "SOURCE_BOUND_FMOD_CHANNEL_PRIORITY_REQUIRED"
                    or role not in _SOFTWARE_CHANNEL_PRIORITY_UNRESOLVED_ROLES
                ):
                    raise ReleaseCapturePlanError(
                        f"{recipe_label} unresolved channel priority is invalid"
                    )
            elif (
                priority != expected_priority
                or blocker is not None
                or isinstance(priority, bool)
                or not isinstance(priority, int)
                or not 0 <= priority <= 256
            ):
                raise ReleaseCapturePlanError(
                    f"{recipe_label}.softwareChannelPriority is invalid"
                )
        if require_renderable and not any(recipe["role"] == "IDLE" for recipe in recipes):
            raise ReleaseCapturePlanError(f"{label} has no authored IDLE")
        _validate_one_shot_programs(
            family["oneShotPrograms"], recipes, f"{label}.oneShotPrograms"
        )

    dynamic_omissions = [
        omission
        for family in families
        for omission in family["sourceOmissions"]
    ]
    if (
        len(dynamic_omissions) != 5
        or len({item["sourceGuid"] for item in dynamic_omissions}) != 5
        or Counter(item["disposition"] for item in dynamic_omissions)
        != {
            "FORBIDDEN_ON_PEDAL_ROUTING": 4,
            "AUTHORED_TARGET_ROUTED_SILENT": 1,
        }
        or {
            item["sourceGuid"]
            for item in dynamic_omissions
        }
        & {
            recipe["sourceGuid"]
            for family in families
            for recipe in family["recipes"]
        }
    ):
        raise ReleaseCapturePlanError(
            "release continuous-source dispositions must remain exactly 5/5 omissions"
        )

    property_one_recipes = [
        recipe
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"] == _CERTIFIED_PROPERTY_ONE_PROJECTION
    ]
    if (
        len(property_one_recipes) != 5
        or len({recipe["sourceGuid"] for recipe in property_one_recipes}) != 5
        or any(
            recipe["pitchMode"] != PROPERTY_ONE_PITCH_MODE
            or recipe["pitchCurveInterpolation"] != PROPERTY_ONE_INTERPOLATION
            for recipe in property_one_recipes
        )
        or any(
            recipe["sourceProjection"] == _ADAPTIVE_RPM_WINDOW_PROJECTION
            for family in families
            for recipe in family["recipes"]
        )
    ):
        raise ReleaseCapturePlanError(
            "release property-one source coverage must remain exactly 5/5 compact captures"
        )

    planned_silent_shifts = [
        recipe
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"]
        == _CERTIFIED_AUTHORED_SILENCE_PROJECTION
    ]
    if (
        len(planned_silent_shifts) != 2
        or len({recipe["sourceGuid"] for recipe in planned_silent_shifts}) != 2
        or {recipe["role"] for recipe in planned_silent_shifts}
        - {"SHIFT_UP", "SHIFT_DOWN"}
    ):
        raise ReleaseCapturePlanError(
            "release silent-shift source coverage must remain exactly 2/2"
        )

    certified_turbo_recipes = [
        recipe
        for family in families
        for recipe in family["recipes"]
        if recipe["sourceProjection"]
        == _CERTIFIED_TURBO_TRANSIENT_PROJECTION
    ]
    if (
        len(certified_turbo_recipes) != 171
        or len({recipe["sourceGuid"] for recipe in certified_turbo_recipes})
        != 171
        or {recipe["role"] for recipe in certified_turbo_recipes}
        != {"BOV", "TURBO_TRANSIENT"}
        or any(
            not isinstance(recipe["sourceCurveSha256"], str)
            or not _SHA256.fullmatch(recipe["sourceCurveSha256"])
            for recipe in certified_turbo_recipes
        )
    ):
        raise ReleaseCapturePlanError(
            "release turbo-transient source coverage must remain exactly 171/171"
        )

    turbo_recipes = [
        (family["familyId"], recipe["sourceGuid"])
        for family in families
        for recipe in family["recipes"]
        if recipe["role"] == "TURBO"
    ]
    spool_recipes = [
        recipe
        for family in families
        for recipe in family["recipes"]
        if recipe["role"] == "SPOOL"
    ]
    if (
        len(turbo_recipes) != 66
        or len(turbo_recipes) != len(set(turbo_recipes))
        or spool_recipes
    ):
        raise ReleaseCapturePlanError(
            "continuous turbo/spool source coverage must remain exactly 66/66"
        )

    plan_text = canonical_json_bytes(plan).decode("utf-8")
    if re.search(r'"role"\s*:\s*"LOAD"', plan_text):
        raise ReleaseCapturePlanError("release plan contains a forbidden role/reference token")
    return plan


def graph_report_for_family(
    graph_root: Path, family_id: str, expected_sha256: str
) -> dict[str, Any]:
    """Load and hash-check one plan-bound family graph at compile time."""

    path = Path(graph_root).resolve() / "families" / f"{family_id}.json"
    if not path.is_file() or _sha256_file(path) != expected_sha256:
        raise ReleaseCapturePlanError("family graph is absent or changed since plan creation")
    graph = json.loads(path.read_text(encoding="utf-8"))
    validate_bank_graph_report(graph)
    if str((graph.get("bank") or {}).get("sha256")) != family_id:
        raise ReleaseCapturePlanError("family graph/source SHA-256 mismatch")
    return graph


def certified_limiter_program_for_recipe(
    recipe: dict[str, Any],
    graph: dict[str, Any],
    row: dict[str, Any],
    proof_path: Path = DEFAULT_LIMITER_ORACLE_PROOF,
) -> dict[str, Any]:
    """Re-certify one limiter against the exact proof selected for compilation."""

    proof, verifications = load_limiter_source_verifications(proof_path)
    _ = proof
    verification = verifications.get(str(recipe["sourceGuid"]))
    if verification is None:
        raise ReleaseCapturePlanError("limiter recipe has no source-bound verification")
    certified = certify_manifest_limiter_program(
        derive_manifest_limiter_program(graph, row), verification
    )
    fidelity = certified.get("fidelity")
    if (
        not isinstance(fidelity, dict)
        or fidelity.get("exactnessClaim") is not True
        or fidelity.get("requiredFinalGate")
        != "satisfiedBySourceBoundLimiterVerification"
    ):
        raise ReleaseCapturePlanError("limiter source is not release-certified")
    return certified


def certified_turbo_source_for_recipe(
    recipe: dict[str, Any],
    graph: dict[str, Any],
    row: dict[str, Any],
    proof_path: Path = DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    source_verifications: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Re-certify one planned turbo leaf against its immutable proof row."""

    if source_verifications is None:
        _proof, source_verifications = (
            load_turbo_transient_source_verifications(proof_path)
        )
    verification = source_verifications.get(str(recipe["sourceGuid"]))
    if verification is None:
        raise ReleaseCapturePlanError(
            "turbo recipe has no source-bound verification"
        )
    certified = certify_manifest_turbo_transient_source(
        derive_manifest_turbo_transient_source(graph, row), verification
    )
    fidelity = certified.get("fidelity")
    if (
        not isinstance(fidelity, dict)
        or fidelity.get("exactnessClaim") is not True
        or fidelity.get("requiredFinalGate")
        != "satisfiedBySourceBoundTurboVerification"
    ):
        raise ReleaseCapturePlanError("turbo source is not release-certified")
    return certified


def certified_property_one_source_for_recipe(
    recipe: dict[str, Any],
    graph: dict[str, Any],
    row: dict[str, Any],
    proof_path: Path = DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
    source_verifications: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Re-certify one compact property-one track against immutable evidence."""

    if source_verifications is None:
        _proof, source_verifications = load_property_one_source_verifications(
            proof_path
        )
    verification = source_verifications.get(str(recipe["sourceGuid"]))
    if verification is None:
        raise ReleaseCapturePlanError(
            "property-one recipe has no source-bound verification"
        )
    try:
        derive_manifest_source_curves(graph, row)
    except FmodAuthoredCurveError as exc:
        if exc.code != "unsupportedPropertyIndex":
            raise
    else:
        raise ReleaseCapturePlanError(
            "property-one source no longer requires its certified runtime mode"
        )
    fallback = derive_windowed_capture_fallback(graph, row)
    certified = certify_property_one_relative_rate(fallback, verification)
    fidelity = certified.get("fidelity")
    if (
        certified.get("schema")
        != "ac-fmod-certified-property-one-relative-rate-v1"
        or not isinstance(fidelity, dict)
        or fidelity.get("exactnessClaim") is not True
        or fidelity.get("requiredFinalGate") is not None
    ):
        raise ReleaseCapturePlanError(
            "property-one source is not release-certified"
        )
    return certified


def verify_recipe_against_graph(
    recipe: dict[str, Any],
    graph: dict[str, Any],
    classification: dict[str, Any] | None = None,
    limiter_proof_path: Path = DEFAULT_LIMITER_ORACLE_PROOF,
    shift_silence_proof_path: Path = DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    turbo_transient_proof_path: Path = DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    turbo_transient_source_verifications: dict[str, dict[str, Any]] | None = None,
    property_one_proof_path: Path = DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
    property_one_source_verifications: dict[str, dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], dict[str, Any], set[str]]:
    """Recompute classifier/curve evidence and return target + event source set."""

    classified = classification or classify_bank_graph_sources(graph)
    rows = {row["sourceGuid"]: row for row in classified["sources"]}
    row = rows.get(recipe["sourceGuid"])
    projection = recipe.get("sourceProjection", _NO_SOURCE_PROJECTION)
    if projection == _SHARED_SOURCE_IDLE_PROJECTION:
        if (
            row is None
            or row["policy"] != POLICY_EXCLUDE
            or row["role"] != ROLE_EXCLUDED_LOAD
            or row.get("lifetime") != "continuous"
        ):
            raise ReleaseCapturePlanError("planned idle projection classification changed")
        derived_projection = _derive_shared_source_idle_projection(graph, row)
        if (
            _sha256_bytes(canonical_json_bytes(derived_projection))
            != recipe["sourceCurveSha256"]
            or derived_projection["eventPath"] != recipe["eventPath"]
            or derived_projection["captureParameterValues"]
            != {
                str(key): float(value)
                for key, value in recipe["parameters"].items()
            }
            or derived_projection["rpmCurve"] != recipe["rpmCurve"]
            or derived_projection["gainCurve"] != recipe["gainCurve"]
            or derived_projection["capturePcmPostGainLinear"]
            != recipe["capturePcmPostGainLinear"]
        ):
            raise ReleaseCapturePlanError("planned idle projection changed")
    else:
        if row is None or row["policy"] != POLICY_ALLOW_CANDIDATE:
            raise ReleaseCapturePlanError("planned source is no longer classifier-allowed")
        if row["role"] != recipe["sourceClassificationRole"]:
            raise ReleaseCapturePlanError("planned source classification changed")
    if projection == _CERTIFIED_AUTHORED_SILENCE_PROJECTION:
        _proof, verifications = load_shift_silence_source_verifications(
            shift_silence_proof_path
        )
        verification = verifications.get(str(recipe["sourceGuid"]))
        if verification is None:
            raise ReleaseCapturePlanError(
                "planned silent shift has no source-bound verification"
            )
        certificate = certify_silent_shift_source(
            graph,
            row,
            verification,
            graph_report_sha256=verification["graphReportSha256"],
            representative_car_id=verification["representativeCarId"],
            installed_bank_relative_path=verification["installedBankRelativePath"],
        )
        if (
            certificate["role"] != recipe["role"]
            or certificate["verificationPayloadSha256"]
            != recipe["sourceCurveSha256"]
            or verification["eventPath"] != recipe["eventPath"]
            or verification["captureParameterValues"] != recipe["parameters"]
        ):
            raise ReleaseCapturePlanError(
                "planned silent-shift recipe disagrees with its proof"
            )
    if (
        recipe["sourceCurveSha256"] is not None
        and projection
        not in {
            _SHARED_SOURCE_IDLE_PROJECTION,
            _CERTIFIED_AUTHORED_SILENCE_PROJECTION,
        }
    ):
        if recipe["role"] == "ENGINE_TRANSIENT":
            curves = derive_manifest_one_shot_curves(graph, row)
            if curves.get("schema") != ONE_SHOT_CURVE_SCHEMA:
                raise ReleaseCapturePlanError(
                    "engine transient curve oracle returned an unknown schema"
                )
        elif recipe["role"] == "LIMITER":
            curves = certified_limiter_program_for_recipe(
                recipe, graph, row, limiter_proof_path
            )
            if curves.get("schema") != LIMITER_PROGRAM_SCHEMA:
                raise ReleaseCapturePlanError(
                    "limiter oracle returned an unknown schema"
                )
        elif recipe["role"] in {"BOV", "TURBO_TRANSIENT"}:
            curves = certified_turbo_source_for_recipe(
                recipe,
                graph,
                row,
                turbo_transient_proof_path,
                turbo_transient_source_verifications,
            )
            if curves.get("schema") != TURBO_TRANSIENT_SOURCE_SCHEMA:
                raise ReleaseCapturePlanError(
                    "turbo-transient oracle returned an unknown schema"
                )
        elif projection == _CERTIFIED_PROPERTY_ONE_PROJECTION:
            curves = certified_property_one_source_for_recipe(
                recipe,
                graph,
                row,
                property_one_proof_path,
                property_one_source_verifications,
            )
        elif projection == _ADAPTIVE_RPM_WINDOW_PROJECTION:
            curves = derive_windowed_capture_fallback(graph, row)
            if curves.get("schema") != "ac-fmod-authored-windowed-capture-fallback-v1":
                raise ReleaseCapturePlanError(
                    "windowed curve oracle returned an unknown schema"
                )
        elif projection == _NO_SOURCE_PROJECTION:
            curves = derive_manifest_source_curves(graph, row)
        elif projection == _ENGINE_INT_EVENT_START_PROJECTION:
            if recipe["role"] != "ENGINE_START":
                raise ReleaseCapturePlanError(
                    "engine-int event-start projection belongs only to ENGINE_START"
                )
        else:  # pragma: no cover - strict plan validation guards this branch.
            raise ReleaseCapturePlanError("unsupported source projection")
        if (
            projection != _ENGINE_INT_EVENT_START_PROJECTION
            and _sha256_bytes(canonical_json_bytes(curves)) != recipe["sourceCurveSha256"]
        ):
            raise ReleaseCapturePlanError("authored source curves changed since plan creation")
        capture_values = {
            str(key): float(value)
            for key, value in curves["captureParameterValues"].items()
        }
        if recipe["role"] == "LIMITER":
            if (
                curves["eventPath"] != recipe["eventPath"]
                or capture_values
                != {
                    str(key): float(value)
                    for key, value in recipe["parameters"].items()
                }
                or recipe["rootRpm"] is not None
                or recipe["rpmCurve"] != []
                or recipe["gainCurve"] != [[0.0, 1.0], [1.0, 1.0]]
                or float(recipe["capturePcmPostGainLinear"]) != 1.0
                or int(curves["verifiedTargetPcm"]["frameCount"])
                != int(recipe["durationFrames"])
                or int(recipe["warmupFrames"]) != 0
            ):
                raise ReleaseCapturePlanError(
                    "planned limiter recipe disagrees with limiter oracle"
                )
        elif recipe["role"] in {"BOV", "TURBO_TRANSIENT"}:
            if (
                curves["resolvedManifestRole"] != recipe["role"]
                or curves["eventPath"] != recipe["eventPath"]
                or capture_values
                != {
                    str(key): float(value)
                    for key, value in recipe["parameters"].items()
                }
                or recipe["rootRpm"] is not None
                or recipe["rpmCurve"] != []
                or recipe["gainCurve"] != [[0.0, 1.0], [1.0, 1.0]]
                or float(recipe["capturePcmPostGainLinear"]) != 1.0
                or int(curves["verifiedTargetPcm"]["frameCount"])
                != int(recipe["durationFrames"])
                or int(recipe["warmupFrames"]) != 0
                or recipe["looping"]
                is not (
                    curves["verifiedTargetPcm"].get("loopStartFrame")
                    is not None
                )
            ):
                raise ReleaseCapturePlanError(
                    "planned turbo-event recipe disagrees with its source oracle"
                )
        elif projection == _CERTIFIED_PROPERTY_ONE_PROJECTION:
            if (
                curves["eventPath"] != recipe["eventPath"]
                or capture_values
                != {
                    str(key): float(value)
                    for key, value in recipe["parameters"].items()
                }
                or curves["rpmCurve"] != recipe["rpmCurve"]
                or curves["gainCurve"] != recipe["gainCurve"]
                or float(curves["captureRootRpm"]) != float(recipe["rootRpm"])
                or curves["pitchMode"] != recipe["pitchMode"]
                or curves["pitchCurve"] != recipe["pitchCurve"]
                or curves["pitchCurveInterpolation"]
                != recipe["pitchCurveInterpolation"]
                or int(curves["capture"]["frameCount"])
                != int(recipe["durationFrames"])
                or int(recipe["warmupFrames"]) != 0
            ):
                raise ReleaseCapturePlanError(
                    "planned property-one recipe disagrees with its source oracle"
                )
        elif (
            curves["eventPath"] != recipe["eventPath"]
            or capture_values != {
                str(key): float(value) for key, value in recipe["parameters"].items()
            }
            or curves["rpmCurve"] != recipe["rpmCurve"]
            or curves["gainCurve"] != recipe["gainCurve"]
            or float(curves.get("rootRpm", curves["captureRootRpm"]))
            != float(recipe["rootRpm"])
            or _capture_pcm_post_gain_linear(curves)
            != float(recipe["capturePcmPostGainLinear"])
        ):
            raise ReleaseCapturePlanError("planned engine recipe disagrees with curve oracle")
    instruments = {
        _guid(item.get("guid")): item
        for item in graph["instruments"]
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    target = instruments.get(recipe["sourceGuid"])
    if target is None or target.get("kind") != "WaveformInstrumentNode":
        raise ReleaseCapturePlanError("planned target is not a waveform instrument")
    event = next(
        (
            item
            for item in graph["events"]
            if isinstance(item, dict) and item.get("path") == recipe["eventPath"]
        ),
        None,
    )
    if event is None or event.get("mappingComplete") is not True:
        raise ReleaseCapturePlanError("planned event mapping is absent or incomplete")
    reachable = {
        _guid(guid)
        for guid in event.get("reachableInstrumentGuids", [])
        if instruments.get(_guid(guid), {}).get("kind") == "WaveformInstrumentNode"
    }
    if recipe["sourceGuid"] not in reachable:
        raise ReleaseCapturePlanError("planned source is not reachable from its event")
    return row, target, reachable


def _off_capture_value(
    points: list[list[float]], capture: float, *, minimum_amplitude: float
) -> tuple[float, float]:
    if len(points) < 2:
        raise ReleaseCapturePlanError("authored curve has no off-capture domain")
    domain = max(1.0e-9, float(points[-1][0]) - float(points[0][0]))
    candidates: list[tuple[float, float]] = []
    for left, right in zip(points, points[1:]):
        left_y = float(left[1])
        right_y = float(right[1])
        # The authored-curve oracle serializes a hard placement step as a
        # <=1e-6-domain zero<->positive epsilon ramp.  Its midpoint is only a
        # linearization artifact: on the zero side FMOD correctly does not
        # schedule the source.  Never select that artificial interval as a
        # runtime identity/level probe; the exact placement bounds remain
        # independently validated by the graph/curve oracle.
        if (
            float(right[0]) - float(left[0]) <= domain * 1.000001e-6
            and ((left_y == 0.0) != (right_y == 0.0))
        ):
            continue
        x = (float(left[0]) + float(right[0])) * 0.5
        y = (left_y + right_y) * 0.5
        candidates.append((x, y))
    eligible = [
        item
        for item in candidates
        if item[1] >= minimum_amplitude
        and abs(item[0] - capture) > domain * 1.0e-6
    ]
    if not eligible:
        raise ReleaseCapturePlanError(
            "authored curve has no audible deterministic off-capture probe"
        )
    # Prefer a materially different gain; flat curves fall back to the point
    # farthest from capture.  Tuple ordering makes the choice deterministic.
    return max(
        eligible,
        key=lambda item: (
            abs(math.log(max(item[1], 1.0e-12))),
            abs(item[0] - capture) / domain,
            -item[0],
        ),
    )


def runtime_curve_probe_controls(recipe: dict[str, Any]) -> list[dict[str, Any]]:
    """Return deterministic speed and accelerator probes for a curved source."""

    if recipe.get("sourceCurveSha256") is None:
        return []
    if recipe.get("sourceProjection") == _ENGINE_INT_EVENT_START_PROJECTION:
        return []
    if (
        recipe.get("sourceProjection")
        == _CERTIFIED_AUTHORED_SILENCE_PROJECTION
    ):
        return []
    if (
        recipe.get("sourceProjection")
        == _CERTIFIED_TURBO_TRANSIENT_PROJECTION
    ):
        # The source-bound turbo oracle already measures live routed gain and
        # pitch motion across every authored control axis.  A held RPM/pedal
        # probe is neither applicable nor an additional fidelity claim.
        return []
    if recipe.get("role") in {"ENGINE_TRANSIENT", "LIMITER"}:
        # Live AutoPitch/timeline fidelity has a separate motion oracle.  A
        # held one-second RPM/pedal RMS ratio is not a valid gate for finite
        # engine transients or the persistent limiter decay state machine.
        return []
    if recipe.get("sourceProjection") == _ADAPTIVE_RPM_WINDOW_PROJECTION:
        # The fallback record supplies its own pitch/RMS window realization
        # and adaptive split contract; it must not be reduced to two probes.
        return []
    if recipe.get("sourceProjection") == _CERTIFIED_PROPERTY_ONE_PROJECTION:
        # The source-bound proof already validates the complete live FMOD
        # property-one rate domain and target PCM pitch/gain parity.  Applying
        # the ordinary RPM/root probe here would test the wrong pitch mode.
        return []
    parameters = {str(key): float(value) for key, value in recipe["parameters"].items()}
    speed_name = "drivetrain_speed" if "drivetrain_speed" in parameters else "rpms"
    if speed_name not in parameters or "throttle" not in parameters:
        raise ReleaseCapturePlanError("curved recipe lacks native capture controls")
    capture_rpm = float(recipe["rootRpm"])
    capture_pedal = parameters["throttle"]
    rpm_curve = recipe["rpmCurve"]
    gain_curve = recipe["gainCurve"]
    base_rpm = _curve_value(rpm_curve, capture_rpm)
    base_pedal = _curve_value(gain_curve, capture_pedal)
    if base_rpm <= 0.0 or base_pedal <= 0.0:
        raise ReleaseCapturePlanError("capture control has zero manifest curve gain")

    probe_rpm, probe_rpm_gain = _off_capture_value(
        rpm_curve,
        capture_rpm,
        minimum_amplitude=CURVE_PROBE_MIN_PREDICTED_AMPLITUDE,
    )
    speed_parameters = dict(parameters)
    speed_parameters[speed_name] = (
        probe_rpm * (2.0 * math.pi / 60.0)
        if speed_name == "drivetrain_speed"
        else probe_rpm
    )
    result = [
        {
            "axis": "speed",
            "manifestRpm": round(probe_rpm, 8),
            "accelerator": round(capture_pedal, 8),
            "parameters": dict(sorted(speed_parameters.items())),
            "predictedRmsRatio": probe_rpm_gain / base_rpm,
        }
    ]
    if recipe.get("sourceProjection") == _SHARED_SOURCE_IDLE_PROJECTION:
        if capture_pedal != 0.0 or gain_curve != [[0.0, 1.0], [1.0, 1.0]]:
            raise ReleaseCapturePlanError(
                "idle projection exposes a non-flat accelerator control"
            )
        return result
    probe_pedal, probe_pedal_gain = _off_capture_value(
        gain_curve,
        capture_pedal,
        minimum_amplitude=CURVE_PROBE_MIN_PREDICTED_AMPLITUDE,
    )
    pedal_parameters = dict(parameters)
    pedal_parameters["throttle"] = probe_pedal
    result.append(
        {
            "axis": "accelerator",
            "manifestRpm": round(capture_rpm, 8),
            "accelerator": round(probe_pedal, 8),
            "parameters": dict(sorted(pedal_parameters.items())),
            "predictedRmsRatio": probe_pedal_gain / base_pedal,
        }
    )
    return result


def validate_runtime_curve_measurements(
    recipe: dict[str, Any], baseline_rms: float, measurements: Iterable[dict[str, Any]]
) -> dict[str, Any]:
    """Validate long-window target-only RMS ratios against manifest curves."""

    baseline = float(baseline_rms)
    if not math.isfinite(baseline) or baseline <= 0.0:
        raise ReleaseCapturePlanError("curve-gate baseline RMS is silent/invalid")
    expected_controls = runtime_curve_probe_controls(recipe)
    actual = list(measurements)
    if len(actual) != len(expected_controls):
        raise ReleaseCapturePlanError("curve-gate measurement count is invalid")
    evidence: list[dict[str, Any]] = []
    for expected, measured in zip(expected_controls, actual):
        if measured.get("axis") != expected["axis"]:
            raise ReleaseCapturePlanError("curve-gate measurement order changed")
        rms = float(measured.get("rms", 0.0))
        if not math.isfinite(rms) or rms <= 0.0:
            raise ReleaseCapturePlanError(
                f"curve-gate {expected['axis']} probe rendered silence"
            )
        observed = rms / baseline
        predicted = float(expected["predictedRmsRatio"])
        error_db = abs(20.0 * math.log10(observed / predicted))
        if error_db > CURVE_PROBE_MAX_RATIO_ERROR_DB:
            raise ReleaseCapturePlanError(
                f"curve-gate {expected['axis']} RMS ratio error {error_db:.3f} dB "
                f"exceeds {CURVE_PROBE_MAX_RATIO_ERROR_DB:.3f} dB"
            )
        evidence.append(
            {
                **expected,
                "measuredRms": rms,
                "observedRmsRatio": observed,
                "absoluteRatioErrorDb": error_db,
                "passed": True,
            }
        )
    return {
        "sourceGuid": recipe["sourceGuid"],
        "baselineRms": baseline,
        "durationFrames": CURVE_PROBE_DURATION_FRAMES,
        "warmupFrames": CURVE_PROBE_WARMUP_FRAMES,
        "maximumRmsRatioErrorDb": CURVE_PROBE_MAX_RATIO_ERROR_DB,
        "probes": evidence,
        "passed": True,
    }


def compile_all_omission_report(plan: dict[str, Any]) -> dict[str, Any]:
    """Aggregate every deliberate schema-v2 omission for status/final audit."""

    aggregate: Counter[str] = Counter()
    classified = retained = 0
    certified_source_omission_count = 0
    families: list[dict[str, Any]] = []
    for family in plan["families"]:
        coverage = family["sourceCoverage"]
        classified += int(coverage["classified"])
        retained += int(coverage["retainedRecipes"])
        aggregate.update(coverage["omissions"])
        source_omissions = family.get("sourceOmissions", [])
        certified_source_omission_count += len(source_omissions)
        if coverage["omissions"] or source_omissions:
            families.append(
                {
                    "familyId": family["familyId"],
                    "representativeCarId": family["representativeCarId"],
                    "omissions": coverage["omissions"],
                    "certifiedSourceOmissions": source_omissions,
                }
            )
    return {
        "schema": "aclib-release-omissions-v1",
        "classifiedSourceCount": classified,
        "retainedRecipeCount": retained,
        "certifiedSourceOmissionCount": certified_source_omission_count,
        "counts": dict(sorted(aggregate.items())),
        "families": families,
    }


def build_hybrid_audio_control_audit(
    catalog: dict[str, Any], graph_root: Path = DEFAULT_GRAPH_ROOT
) -> dict[str, Any]:
    """Prove hybrid metadata does not hide an unmodeled FMOD runtime signal."""

    validate_catalog(catalog, require_complete=True)
    _summary, graph_paths = _load_graph_inventory(Path(graph_root), catalog)
    hybrid_cars = sorted(
        (car for car in catalog["cars"] if car["engine"]["hybrid"]),
        key=lambda car: str(car["id"]),
    )
    forbidden = {
        "ers",
        "kers",
        "hybrid",
        "electric",
        "energy",
        "deployment",
        "recovery",
        "battery",
    }
    families: list[dict[str, Any]] = []
    for car in hybrid_cars:
        family_id = str(car["familyId"])
        graph_path = graph_paths[family_id]
        graph = json.loads(graph_path.read_text(encoding="utf-8"))
        validate_bank_graph_report(graph)
        parameters = sorted(
            {
                str(item.get("name") or "").casefold()
                for item in graph["parameters"]
                if isinstance(item, dict)
                and item.get("type") == "FMOD_STUDIO_PARAMETER_GAME_CONTROLLED"
                and str(item.get("name") or "")
            }
        )
        event_suffixes = sorted(
            {_event_suffix(item.get("path")) for item in graph["events"]}
        )
        matching = sorted(
            {
                token
                for value in (*parameters, *event_suffixes)
                for token in _TOKEN.findall(value)
                if token in forbidden
            }
        )
        if matching:
            raise ReleaseCapturePlanError(
                f"hybrid family {family_id} exposes unmodeled FMOD controls {matching}"
            )
        families.append(
            {
                "carId": car["id"],
                "familyId": family_id,
                "graphReportSha256": _sha256_file(graph_path),
                "gameControlledParameters": parameters,
                "eventSuffixes": event_suffixes,
                "hybridSpecificMatches": [],
            }
        )
    if len(hybrid_cars) != 12 or len({item["familyId"] for item in families}) != 12:
        raise ReleaseCapturePlanError("official hybrid family inventory changed")
    return {
        "schema": "aclib-hybrid-audio-control-audit-v1",
        "catalogSha256": catalog["catalogSha256"],
        "familyCount": 12,
        "hybridSpecificGameParameterCount": 0,
        "hybridSpecificEventSuffixCount": 0,
        "conclusion": (
            "Hybrid physics metadata does not require an additional FMOD audio "
            "runtime signal; retained core events use ordinary authored controls."
        ),
        "families": families,
    }


__all__ = [
    "CURVE_PROBE_DURATION_FRAMES",
    "CURVE_PROBE_MAX_RATIO_ERROR_DB",
    "CURVE_PROBE_MIN_PREDICTED_AMPLITUDE",
    "CURVE_PROBE_WARMUP_FRAMES",
    "DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF",
    "DEFAULT_GRAPH_ROOT",
    "DEFAULT_LIMITER_ORACLE_PROOF",
    "DEFAULT_PROPERTY_ONE_ORACLE_PROOF",
    "DEFAULT_PRIORITY_ORACLE_PROOF",
    "DEFAULT_RELEASE_OUTPUT_ROOT",
    "DEFAULT_RELEASE_PLAN",
    "DEFAULT_SHIFT_SILENCE_ORACLE_PROOF",
    "DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF",
    "ENGINE_INT_EVENT_START_PROJECTION",
    "ENGINE_START_DURATION_FRAMES",
    "RELEASE_CAPTURE_PLAN_SCHEMA_VERSION",
    "ReleaseCapturePlanError",
    "build_release_capture_plan",
    "build_hybrid_audio_control_audit",
    "certify_silent_shift_source",
    "certified_limiter_program_for_recipe",
    "certified_property_one_source_for_recipe",
    "certified_turbo_source_for_recipe",
    "compile_all_omission_report",
    "graph_report_for_family",
    "load_shift_silence_source_verifications",
    "load_property_one_source_verifications",
    "load_turbo_transient_source_verifications",
    "runtime_curve_probe_controls",
    "validate_release_capture_plan",
    "validate_runtime_curve_measurements",
    "verify_recipe_against_graph",
]
