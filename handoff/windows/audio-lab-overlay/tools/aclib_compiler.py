"""Silent local compiler for the official Assetto Corsa lossless catalog."""

from __future__ import annotations

import argparse
import array
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import wave
import zipfile
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib import (
    AUDIO_ROLES,
    LOOP_ROLES,
    TRIGGERS_BY_ROLE,
    build_aclib,
    derive_effect_capabilities,
    sha256_file,
    validate_aclib,
    validate_manifest,
    validate_release_manifest,
)
from sim.aclib_catalog import (
    build_official_catalog,
    canonical_json_bytes,
    validate_catalog,
)
from sim.assetto import find_assetto_root
from sim.car_config import load_car_spec
from sim.drivetrain import load_drivetrain_spec
from sim.flac_codec import (
    PinnedFlacCodec,
    apply_gain_pcm16_stereo_wav,
    bootstrap_pinned_flac,
    inspect_pcm16_stereo_wav,
)
from sim.fmod_probe import SilentFmodBankProbe
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_bank_isolation import (
    create_isolated_bank_copy,
    fully_muted_multi_instrument_guids,
)
from sim.fmod_graph_roles import classify_bank_graph_sources
from sim.fmod_sdk_audit import audit_shipped_fmod_authoring
from sim.huracan_regression import audit_huracan_loop_sources
from sim.loop_tools import crossfade_loop_seam, find_best_loop_bounds
from tools.aclib_release import (
    CURVE_PROBE_DURATION_FRAMES,
    CURVE_PROBE_WARMUP_FRAMES,
    DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF,
    DEFAULT_GRAPH_ROOT,
    DEFAULT_LIMITER_ORACLE_PROOF,
    DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
    DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    DEFAULT_PRIORITY_ORACLE_PROOF,
    DEFAULT_RELEASE_OUTPUT_ROOT,
    DEFAULT_RELEASE_PLAN,
    ENGINE_INT_EVENT_START_PROJECTION,
    build_hybrid_audio_control_audit,
    build_release_capture_plan,
    certify_silent_shift_source,
    certified_property_one_source_for_recipe,
    certified_turbo_source_for_recipe,
    certified_limiter_program_for_recipe,
    compile_all_omission_report,
    graph_report_for_family,
    load_shift_silence_source_verifications,
    load_property_one_source_verifications,
    load_turbo_transient_source_verifications,
    runtime_curve_probe_controls,
    validate_release_capture_plan,
    validate_runtime_curve_measurements,
    verify_recipe_against_graph,
)


DEFAULT_LOCAL_ROOT = PROJECT_ROOT / ".aclib-local"
DEFAULT_TOOL_ROOT = PROJECT_ROOT / ".aclib-tools"
DEFAULT_HURACAN_SOURCE_ROOT = Path(
    r"D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound\audio_samples"
    r"\fx_lamborghini_huracan_trofeo_evo2\converted"
)
MIN_AUDIBLE_PEAK_DBFS = -96.0
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
_TOKEN = re.compile(r"[a-z0-9]+")


def is_release_audible_peak_dbfs(value: object) -> bool:
    """Return true only for finite PCM strictly above the V2 silence floor."""

    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    peak = float(value)
    return math.isfinite(peak) and peak > MIN_AUDIBLE_PEAK_DBFS


def deterministic_source_selection_take_limit(
    row: dict[str, Any], graph: dict[str, Any]
) -> int:
    """Bound the pinned FMOD SmartRandom sequence by authored tree span."""

    instruments = {
        str(item.get("guid") or "").casefold(): item
        for item in graph.get("instruments", [])
        if isinstance(item, dict) and item.get("guid")
    }
    evidence = row.get("decisionEvidence")
    ancestry = (
        evidence.get("instrumentAncestry", [])
        if isinstance(evidence, dict)
        else []
    )
    span = 1
    for ancestor in ancestry[1:]:
        if not isinstance(ancestor, dict) or ancestor.get("kind") != "MultiInstrumentNode":
            continue
        guid = str(ancestor.get("instrumentGuid") or "").casefold()
        parent = instruments.get(guid)
        children = parent.get("childInstruments") if isinstance(parent, dict) else None
        if not isinstance(children, list) or not children:
            raise ValueError("one-shot selection ancestor has no authored children")
        span *= len(children)
        if span > 128:
            # 32*128 reaches the proven compiler cap.  More authored topology
            # is still exercised by the same exact 4096-take fail-closed gate.
            span = 128
            break
    return min(4096, max(64, 32 * span))


def _equal_power_rpm_window(roots: list[float], index: int) -> list[list[float]]:
    """Five-point sine/cosine crossfades against adjacent root captures."""

    center = roots[index]
    result: list[list[float]] = []
    if index > 0:
        left = roots[index - 1]
        for step in range(5):
            t = step / 4.0
            result.append(
                [left + (center - left) * t, math.sin(t * math.pi * 0.5)]
            )
    else:
        result.append([center, 1.0])
    if index + 1 < len(roots):
        right = roots[index + 1]
        for step in range(1, 5):
            t = step / 4.0
            result.append(
                [center + (right - center) * t, math.cos(t * math.pi * 0.5)]
            )
    return [[round(x, 6), round(y, 9)] for x, y in result]


def _curve_value(points: list[list[float]], x: float) -> float:
    if not points:
        return 1.0
    if x <= points[0][0]:
        return float(points[0][1])
    for left, right in zip(points, points[1:]):
        if x <= right[0]:
            span = right[0] - left[0]
            return float(right[1]) if span <= 0 else float(left[1]) + (
                float(right[1]) - float(left[1])
            ) * ((x - float(left[0])) / span)
    return float(points[-1][1])


def _default_mix_bound_dbfs(rendered: list[dict[str, Any]], redline: float) -> float:
    """Conservative peak-sum bound over the continuous default mix surface."""

    continuous = [item for item in rendered if item["recipe"]["looping"]]
    if not continuous:
        return -math.inf
    maximum = 0.0
    for rpm_step in range(129):
        rpm = redline * rpm_step / 128.0
        for pedal_step in range(17):
            pedal = pedal_step / 16.0
            bound = 0.0
            for item in continuous:
                recipe = item["recipe"]
                weight = _curve_value(recipe["rpmCurve"], rpm) * _curve_value(
                    recipe["gainCurve"], pedal
                )
                peak = 10.0 ** (item["integrity"].peak_dbfs / 20.0)
                bound += peak * weight
            maximum = max(maximum, bound)
    return -math.inf if maximum <= 0.0 else 20.0 * math.log10(maximum)


def _write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = canonical_json_bytes(value) + b"\n"
    with tempfile.NamedTemporaryFile(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(data)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def _capture_plan_sha256(plan: dict[str, Any]) -> str:
    return __import__("hashlib").sha256(canonical_json_bytes(plan)).hexdigest()


def _capture_plan_family_subtree(
    plan: dict[str, Any], family_id: str
) -> dict[str, Any]:
    matches = [
        family
        for family in plan.get("families", [])
        if isinstance(family, dict) and family.get("familyId") == family_id
    ]
    if len(matches) != 1:
        raise ValueError(
            f"capture plan family {family_id} matched {len(matches)} subtrees"
        )
    return matches[0]


def assert_repackage_family_subtree_unchanged(
    old_plan: dict[str, Any], final_plan: dict[str, Any], family_id: str
) -> None:
    """Fail unless a rendered family's complete plan subtree is byte-stable."""

    if canonical_json_bytes(
        _capture_plan_family_subtree(old_plan, family_id)
    ) != canonical_json_bytes(_capture_plan_family_subtree(final_plan, family_id)):
        raise ValueError(
            f"family {family_id} changed between capture plans; PCM rerender required"
        )


def repackage_aclib_for_capture_plan(
    source_pack: Path,
    output_pack: Path,
    old_plan: dict[str, Any],
    final_plan: dict[str, Any],
    immutable_archive_root: Path,
    *,
    codec: PinnedFlacCodec | None = None,
) -> Path:
    """Rebind only global plan provenance when family inputs are identical.

    The old pack is copied once beneath a plan-hash directory before any new
    output is written.  Every media/preview byte is then copied verbatim into a
    deterministic new archive and both the old and new packs are fully
    revalidated.  This deliberately cannot authorize a changed family recipe,
    curve, program, member car, or other subtree value.
    """

    source = Path(source_pack).resolve(strict=True)
    destination = Path(output_pack).resolve()
    old_hash = _capture_plan_sha256(old_plan)
    final_hash = _capture_plan_sha256(final_plan)
    if old_hash == final_hash:
        raise ValueError("capture-plan convergence requires two distinct plans")
    if (
        old_plan.get("schemaVersion") != 2
        or final_plan.get("schemaVersion") != 2
        or old_plan.get("catalogSha256") != final_plan.get("catalogSha256")
    ):
        raise ValueError("capture plans are not compatible schema-v2 catalog plans")
    old_manifest = validate_release_manifest(validate_aclib(source, codec=codec))
    family_id = str(old_manifest["familyId"])
    if old_manifest["provenance"]["capturePlanSha256"] != old_hash:
        raise ValueError("source pack is not bound to the supplied old capture plan")
    if old_manifest["provenance"]["catalogSha256"] != old_plan["catalogSha256"]:
        raise ValueError("source pack catalog provenance changed")
    assert_repackage_family_subtree_unchanged(old_plan, final_plan, family_id)

    source_sha = sha256_file(source)
    archive = (
        Path(immutable_archive_root).resolve()
        / old_hash
        / f"{family_id}.aclib"
    )
    archive.parent.mkdir(parents=True, exist_ok=True)
    if archive.exists():
        if sha256_file(archive) != source_sha:
            raise ValueError("immutable old-plan pack archive has conflicting bytes")
    else:
        with tempfile.NamedTemporaryFile(
            prefix=f".{archive.name}.",
            suffix=".tmp",
            dir=archive.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
        try:
            shutil.copyfile(source, temporary_path)
            if sha256_file(temporary_path) != source_sha:
                raise ValueError("immutable old-plan pack copy changed")
            os.replace(temporary_path, archive)
        finally:
            temporary_path.unlink(missing_ok=True)
    if sha256_file(archive) != source_sha:
        raise ValueError("immutable old-plan pack archive failed verification")

    with zipfile.ZipFile(source, "r") as old_zip:
        old_members = {
            name: old_zip.read(name)
            for name in old_zip.namelist()
            if name != "manifest.json"
        }
    expected_manifest = json.loads(json.dumps(old_manifest))
    expected_manifest["provenance"]["capturePlanSha256"] = final_hash
    validate_release_manifest(expected_manifest)

    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{family_id}.repackage-", dir=destination.parent
    ) as temporary_text:
        temporary = Path(temporary_text)
        for name, payload in old_members.items():
            member_path = temporary / Path(*name.split("/"))
            member_path.parent.mkdir(parents=True, exist_ok=True)
            member_path.write_bytes(payload)
        build_aclib(expected_manifest, temporary, destination)

    new_manifest = validate_release_manifest(
        validate_aclib(destination, codec=codec)
    )
    if canonical_json_bytes(new_manifest) != canonical_json_bytes(expected_manifest):
        raise ValueError("repackaged manifest changed beyond capture-plan provenance")
    with zipfile.ZipFile(destination, "r") as new_zip:
        new_members = {
            name: new_zip.read(name)
            for name in new_zip.namelist()
            if name != "manifest.json"
        }
    if new_members != old_members:
        destination.unlink(missing_ok=True)
        raise ValueError("repackaged audio or preview payload changed")
    return destination


def _copy_catalog_previews(catalog: dict[str, Any], root: Path, directory: Path) -> int:
    copied = 0
    for car in catalog["cars"]:
        source_name = car.get("previewSource")
        target_name = car.get("previewPath")
        if not source_name or not target_name:
            continue
        source = (root / source_name).resolve()
        target = (directory / target_name).resolve()
        if root.resolve() not in source.parents or directory.resolve() not in target.parents:
            raise ValueError("preview path escapes its root")
        if sha256_file(source) != car["previewSha256"]:
            raise ValueError(f"preview changed during catalog build: {source_name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        copied += 1
    return copied


def _event_names(family: dict[str, Any]) -> set[str]:
    return {str(path).rsplit("/", 1)[-1].casefold() for path in family["events"]}


def _finite_number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{label} must be a finite number")
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{label} must be a finite number")
    return result


def _validate_plan_curve(value: object, label: str, *, pedal: bool) -> None:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be an array")
    previous: float | None = None
    for index, point in enumerate(value):
        if not isinstance(point, list) or len(point) != 2:
            raise ValueError(f"{label}[{index}] must be [x,y]")
        x = _finite_number(point[0], f"{label}[{index}].x")
        y = _finite_number(point[1], f"{label}[{index}].y")
        if not 0.0 <= y <= 1.0:
            raise ValueError(f"{label}[{index}].y must be linear amplitude 0..1")
        if (pedal and not 0.0 <= x <= 1.0) or (not pedal and x < 0.0):
            raise ValueError(f"{label}[{index}].x is outside its control domain")
        if previous is not None and x <= previous:
            raise ValueError(f"{label} x values must increase")
        previous = x


def validate_capture_plan(
    plan: object,
    catalog: dict[str, Any],
    *,
    require_renderable: bool = True,
) -> dict[str, Any]:
    """Strictly validate every compiler input before FMOD or filesystem work."""

    validate_catalog(catalog)
    if not isinstance(plan, dict):
        raise ValueError("capture plan must be an object")
    if plan.get("schemaVersion") == 2:
        return validate_release_capture_plan(
            plan, catalog, require_renderable=require_renderable
        )
    expected_top = {
        "schemaVersion",
        "catalogSha256",
        "audioFormat",
        "curveSemantics",
        "fidelity",
        "families",
    }
    if set(plan) != expected_top or plan["schemaVersion"] != 1:
        raise ValueError("capture plan top-level fields or schemaVersion are invalid")
    if plan["catalogSha256"] != catalog["catalogSha256"]:
        raise ValueError("capture plan was not made from this catalog")
    if plan["audioFormat"] != {
        "codec": "FLAC",
        "sampleRate": 48000,
        "channels": 2,
        "bitsPerSample": 16,
        "compressionLevel": 8,
    }:
        raise ValueError("capture plan audioFormat is invalid")
    if plan["curveSemantics"] != {
        "rpmCurveX": "engineRpm",
        "gainCurveX": "normalizedAccelerator",
        "curveY": "linearAmplitude",
        "interpolation": "clampedLinear",
    }:
        raise ValueError("capture plan curveSemantics are invalid")
    fidelity = plan["fidelity"]
    if not isinstance(fidelity, dict) or set(fidelity) != {
        "sourceAudio",
        "layerIsolation",
        "rpmGainCurve",
        "effectVariants",
        "notes",
    }:
        raise ValueError("capture plan fidelity fields are invalid")
    if (
        fidelity["sourceAudio"] != "nativeFmodFinalMix"
        or fidelity["layerIsolation"] != "eventLevel"
        or fidelity["rpmGainCurve"] != "compilerWindowApproximation"
        or fidelity["effectVariants"] != "nativeRandomSequence"
        or not isinstance(fidelity["notes"], list)
        or any(not isinstance(note, str) or not note for note in fidelity["notes"])
    ):
        raise ValueError("capture plan fidelity declaration is invalid")

    raw_families = plan["families"]
    if not isinstance(raw_families, list):
        raise ValueError("capture plan families must be an array")
    catalog_families = {family["id"]: family for family in catalog["soundFamilies"]}
    if {
        family.get("familyId") for family in raw_families if isinstance(family, dict)
    } != set(catalog_families) or len(raw_families) != len(catalog_families):
        raise ValueError("capture plan must define every catalog family exactly once")
    for family_index, family in enumerate(raw_families):
        if not isinstance(family, dict) or set(family) != {
            "familyId",
            "representativeCarId",
            "memberCarIds",
            "recipes",
        }:
            raise ValueError(f"families[{family_index}] fields are invalid")
        catalog_family = catalog_families[family["familyId"]]
        if (
            family["representativeCarId"] != catalog_family["representativeCarId"]
            or family["memberCarIds"] != catalog_family["memberIds"]
        ):
            raise ValueError(f"families[{family_index}] does not match its catalog family")
        recipes = family["recipes"]
        if not isinstance(recipes, list):
            raise ValueError(f"families[{family_index}].recipes must be an array")
        event_names = _event_names(catalog_family)
        ids: set[str] = set()
        for recipe_index, recipe in enumerate(recipes):
            label = f"families[{family_index}].recipes[{recipe_index}]"
            if not isinstance(recipe, dict) or set(recipe) != {
                "id",
                "role",
                "event",
                "parameters",
                "rootRpm",
                "looping",
                "durationFrames",
                "warmupFrames",
                "rpmCurve",
                "gainCurve",
                "triggers",
                "variantIndex",
            }:
                raise ValueError(f"{label} fields are invalid")
            track_id = recipe["id"]
            if not isinstance(track_id, str) or not _IDENTIFIER.fullmatch(track_id):
                raise ValueError(f"{label}.id is invalid")
            if track_id in ids:
                raise ValueError(f"{label}.id is duplicated")
            ids.add(track_id)
            role = recipe["role"]
            if role not in AUDIO_ROLES:
                raise ValueError(f"{label}.role is invalid")
            if recipe["looping"] != (role in LOOP_ROLES):
                raise ValueError(f"{label}.looping disagrees with role {role}")
            event = recipe["event"]
            if not isinstance(event, str) or event.casefold() not in event_names:
                raise ValueError(f"{label}.event is absent from the probed bank")
            parameters = recipe["parameters"]
            if not isinstance(parameters, dict) or any(
                not isinstance(key, str) or not key for key in parameters
            ):
                raise ValueError(f"{label}.parameters are invalid")
            for key, parameter in parameters.items():
                _finite_number(parameter, f"{label}.parameters.{key}")
            root_rpm = recipe["rootRpm"]
            if root_rpm is not None and _finite_number(root_rpm, f"{label}.rootRpm") <= 0:
                raise ValueError(f"{label}.rootRpm must be positive")
            if role in {"IDLE", "COAST", "TEXTURE", "INTAKE", "EXHAUST"} and root_rpm is None:
                raise ValueError(f"{label}.{role} requires rootRpm")
            for name, allow_zero in (("durationFrames", False), ("warmupFrames", True)):
                number = recipe[name]
                if isinstance(number, bool) or not isinstance(number, int) or (
                    number < 0 if allow_zero else number <= 0
                ):
                    raise ValueError(f"{label}.{name} is invalid")
            _validate_plan_curve(recipe["rpmCurve"], f"{label}.rpmCurve", pedal=False)
            _validate_plan_curve(recipe["gainCurve"], f"{label}.gainCurve", pedal=True)
            triggers = recipe["triggers"]
            if (
                not isinstance(triggers, list)
                or len(set(triggers)) != len(triggers)
                or set(triggers) != TRIGGERS_BY_ROLE[role]
            ):
                raise ValueError(f"{label}.triggers do not match role {role}")
            variant = recipe["variantIndex"]
            if isinstance(variant, bool) or not isinstance(variant, int) or variant < 0:
                raise ValueError(f"{label}.variantIndex is invalid")
        if require_renderable and "IDLE" not in {recipe["role"] for recipe in recipes}:
            raise ValueError(f"families[{family_index}] has no authored IDLE recipe")
    if "load" in _TOKEN.findall(canonical_json_bytes(plan).decode("utf-8").casefold()):
        raise ValueError("capture plan contains a forbidden audio role or reference")
    return plan


def build_capture_plan(catalog: dict[str, Any]) -> dict[str, Any]:
    validate_catalog(catalog)
    cars = {car["id"]: car for car in catalog["cars"]}
    families: list[dict[str, Any]] = []
    for family in catalog["soundFamilies"]:
        representative = cars[family["representativeCarId"]]
        events = _event_names(family)
        engine = representative["engine"]
        idle = float(engine["idleRpm"])
        redline = float(engine["redlineRpm"])
        recipes: list[dict[str, Any]] = []

        def add(
            track_id: str,
            role: str,
            event: str,
            *,
            parameters: dict[str, float],
            root_rpm: float | None,
            looping: bool,
            duration_seconds: float,
            warmup_seconds: float,
            trigger: str | None = None,
            rpm_curve: list[list[float]] | None = None,
            gain_curve: list[list[float]] | None = None,
            variant_index: int = 0,
        ) -> None:
            if event not in events:
                return
            recipes.append(
                {
                    "id": track_id,
                    "role": role,
                    "event": event,
                    "parameters": parameters,
                    "rootRpm": root_rpm,
                    "looping": looping,
                    "durationFrames": round(duration_seconds * 48000),
                    "warmupFrames": round(warmup_seconds * 48000),
                    "rpmCurve": (
                        rpm_curve
                        if rpm_curve is not None
                        else [[root_rpm, 1.0]]
                        if root_rpm is not None
                        else []
                    ),
                    "gainCurve": gain_curve or [[0.0, 1.0], [1.0, 1.0]],
                    "triggers": [trigger] if trigger else [],
                    "variantIndex": variant_index,
                }
            )

        interior = "engine_int" if "engine_int" in events else "engine_ext"
        coast_roots = sorted(
            {
                round(idle + (redline - idle) * fraction)
                for fraction in (0.15, 0.3, 0.45, 0.6, 0.75, 0.88, 0.97)
            }
        )
        all_engine_roots = [idle, *[float(item) for item in coast_roots]]
        add(
            "idle",
            "IDLE",
            interior,
            parameters={"rpms": idle, "throttle": 0.0},
            root_rpm=idle,
            looping=True,
            duration_seconds=4.0,
            warmup_seconds=0.75,
            rpm_curve=[
                [0.0, 0.0],
                [max(1.0, idle * 0.8), 0.0],
                *_equal_power_rpm_window(all_engine_roots, 0),
            ],
            gain_curve=[[0.0, 1.0], [1.0, 1.0]],
        )
        for index, rpm in enumerate(coast_roots):
            add(
                f"coast_{rpm}",
                "COAST",
                interior,
                parameters={"rpms": float(rpm), "throttle": 0.0},
                root_rpm=float(rpm),
                looping=True,
                duration_seconds=3.0,
                warmup_seconds=0.75,
                rpm_curve=_equal_power_rpm_window(all_engine_roots, index + 1),
                gain_curve=[
                    [0.0, 1.0],
                    [0.25, 0.923879533],
                    [0.5, 0.707106781],
                    [0.75, 0.382683432],
                    [1.0, 0.0],
                ],
            )
        character_rpm = round(idle + (redline - idle) * 0.58)
        add(
            f"texture_{character_rpm}",
            "TEXTURE",
            interior,
            parameters={"rpms": float(character_rpm), "throttle": 0.0},
            root_rpm=float(character_rpm),
            looping=True,
            duration_seconds=3.0,
            warmup_seconds=0.75,
            rpm_curve=[[idle, 0.0], [float(character_rpm), 1.0], [redline, 0.0]],
            gain_curve=[
                [0.0, 0.0],
                [0.25, 0.382683432],
                [0.5, 0.707106781],
                [0.75, 0.923879533],
                [1.0, 1.0],
            ],
        )
        add(
            f"exhaust_{character_rpm}",
            "EXHAUST",
            "engine_ext",
            parameters={"rpms": float(character_rpm), "throttle": 0.0},
            root_rpm=float(character_rpm),
            looping=True,
            duration_seconds=3.0,
            warmup_seconds=0.75,
            rpm_curve=[[idle, 0.0], [float(character_rpm), 1.0], [redline, 0.0]],
            gain_curve=[
                [0.0, 0.0],
                [0.25, 0.382683432],
                [0.5, 0.707106781],
                [0.75, 0.923879533],
                [1.0, 1.0],
            ],
        )
        add(
            "turbo",
            "TURBO",
            "turbo",
            parameters={"boost": 0.8, "bov": 0.0, "bov_decay": 10.0},
            root_rpm=None,
            looping=True,
            duration_seconds=3.0,
            warmup_seconds=0.5,
            gain_curve=[[0.0, 0.25], [1.0, 1.0]],
        )
        add(
            "spool",
            "SPOOL",
            "turbo",
            parameters={"boost": 0.5, "bov": 0.0, "bov_decay": 10.0},
            root_rpm=None,
            looping=True,
            duration_seconds=3.0,
            warmup_seconds=0.5,
            gain_curve=[[0.0, 0.15], [1.0, 1.0]],
        )
        if family["effects"]["bov"]:
            add(
                "bov",
                "BOV",
                "turbo",
                parameters={"boost": 0.8, "bov": 1.0, "bov_decay": 0.0},
                root_rpm=None,
                looping=False,
                duration_seconds=1.5,
                warmup_seconds=0.0,
                trigger="bov",
                gain_curve=[[0.0, 1.0], [1.0, 1.0]],
            )
        add(
            "transmission",
            "TRANSMISSION",
            "transmission",
            parameters={
                "drivetrain_speed": character_rpm * (2.0 * math.pi / 60.0),
                "throttle": 1.0,
            },
            root_rpm=None,
            looping=True,
            duration_seconds=3.0,
            warmup_seconds=0.5,
            gain_curve=[[0.0, 0.25], [1.0, 1.0]],
        )
        add(
            "limiter",
            "LIMITER",
            "limiter",
            parameters={"decay": 0.0},
            root_rpm=None,
            looping=False,
            duration_seconds=1.0,
            warmup_seconds=0.0,
            trigger="limiterPulse",
        )
        add(
            "shift_up",
            "SHIFT_UP",
            "gear_int" if "gear_int" in events else "gear_ext",
            parameters={"state": 1.0},
            root_rpm=None,
            looping=False,
            duration_seconds=1.25,
            warmup_seconds=0.0,
            trigger="shiftUp",
        )
        add(
            "shift_down",
            "SHIFT_DOWN",
            "gear_int" if "gear_int" in events else "gear_ext",
            parameters={"state": 0.0},
            root_rpm=None,
            looping=False,
            duration_seconds=1.25,
            warmup_seconds=0.0,
            trigger="shiftDown",
        )
        backfire = "backfire_int" if "backfire_int" in events else "backfire_ext"
        for variant_index in range(8):
            add(
                f"overrun_{variant_index + 1:02d}",
                "OVERRUN",
                backfire,
                parameters={"throttle": 0.0},
                root_rpm=None,
                looping=False,
                duration_seconds=1.75,
                warmup_seconds=0.0,
                trigger="overrunRelease",
                variant_index=variant_index,
            )
        families.append(
            {
                "familyId": family["id"],
                "representativeCarId": representative["id"],
                "memberCarIds": family["memberIds"],
                "recipes": recipes,
            }
        )
    plan: dict[str, Any] = {
        "schemaVersion": 1,
        "catalogSha256": catalog["catalogSha256"],
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
        "fidelity": {
            "sourceAudio": "nativeFmodFinalMix",
            "layerIsolation": "eventLevel",
            "rpmGainCurve": "compilerWindowApproximation",
            "effectVariants": "nativeRandomSequence",
            "notes": [
                "The FMOD runtime exposes final event output but not private source-instrument automation curves.",
                "Held-RPM captures retain authored event timbre and relative level at each root; runtime windows are compiler-defined.",
                "Event-level rendering cannot prove exclusion of attenuated authored source groups with a forbidden role.",
                "The shipped authoring XML proves this boundary for the Tatuus template but does not cover the other official banks.",
            ],
        },
        "families": families,
    }
    validate_capture_plan(
        plan,
        catalog,
        require_renderable=all(
            family["eventProbeStatus"] == "complete"
            for family in catalog["soundFamilies"]
        ),
    )
    return plan


def _resolve_family(items: list[dict[str, Any]], selector: str) -> dict[str, Any]:
    matches = [item for item in items if str(item["familyId"]).startswith(selector)]
    if len(matches) != 1:
        raise ValueError(f"family selector {selector!r} matched {len(matches)} entries")
    return matches[0]


def _write_pcm_wav(path: Path, pcm: bytes) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(48000)
        output.writeframes(pcm)


def _trim_trailing_digital_silence(path: Path, *, guard_frames: int = 4) -> int:
    """Drop renderer padding after a one-shot while preserving event-start timing."""

    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError("one-shot WAV is not canonical PCM16/48 kHz/stereo")
        frame_count = source.getnframes()
        pcm = source.readframes(frame_count)
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    last_nonzero_frame = -1
    for sample_index in range(len(samples) - 1, -1, -1):
        if samples[sample_index] != 0:
            last_nonzero_frame = sample_index // 2
            break
    if last_nonzero_frame < 0:
        return frame_count
    retained_frames = min(
        frame_count, last_nonzero_frame + 1 + max(0, int(guard_frames))
    )
    if retained_frames < frame_count:
        _write_pcm_wav(path, pcm[: retained_frames * 4])
    return retained_frames


def _apply_capture_pcm_post_gain(path: Path, linear_gain: float) -> None:
    """Bake curve-oracle capture normalization once before family calibration."""

    gain = float(linear_gain)
    if not math.isfinite(gain) or gain <= 0.0:
        raise ValueError("capture PCM post-gain must be finite and positive")
    if abs(gain - 1.0) <= 1.0e-15:
        return
    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError("capture-normalization WAV is not canonical PCM16 stereo")
        pcm = source.readframes(source.getnframes())
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    normalized = array.array("h")
    for value in samples:
        scaled = round(int(value) * gain)
        if scaled < -32768 or scaled > 32767:
            raise ValueError("capture PCM post-gain would clip before family calibration")
        normalized.append(scaled)
    if sys.byteorder != "little":
        normalized.byteswap()
    _write_pcm_wav(path, normalized.tobytes())


def _finalize_one_shot_programs(
    planned_programs: list[dict[str, Any]], tracks: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Bind engine-event lane demand to exact decoded one-shot frame counts."""

    programs = json.loads(json.dumps(planned_programs))
    tracks_by_id = {str(track["id"]): track for track in tracks}
    for program in programs:
        priority = program.get("softwareChannelPriority")
        if (
            isinstance(priority, bool)
            or not isinstance(priority, int)
            or not 0 <= priority <= 256
        ):
            raise ValueError(
                f"one-shot program {program.get('id')} has no certified FMOD priority"
            )
        program_track_references = [
            tracks_by_id.get(str(node["trackId"]))
            for node in program["nodes"]
            if node.get("kind") == "TRACK"
        ]
        if any(track is None for track in program_track_references):
            raise ValueError("one-shot program references an absent decoded track")
        program_tracks = [
            track for track in program_track_references if track is not None
        ]
        silent_sources = [
            node
            for node in program["nodes"]
            if node.get("kind") == "SILENT_SOURCE"
        ]
        if not program_tracks and not (
            program.get("trigger") == "TURBO_EVENT" and silent_sources
        ):
            raise ValueError("one-shot program has no decoded or certified-silent leaf")
        if any(
            track["softwareChannelPriority"] != priority
            for track in program_tracks
        ):
            raise ValueError(
                f"one-shot program {program.get('id')} priority disagrees with a leaf"
            )
        if program.get("trigger") != "ENGINE_EVENT":
            continue
        track_ids = [
            str(node["trackId"])
            for node in program["nodes"]
            if node.get("kind") == "TRACK"
        ]
        if not track_ids or any(track_id not in tracks_by_id for track_id in track_ids):
            raise ValueError("engine-event program references an absent decoded track")
        maximum_frames = max(
            int(tracks_by_id[track_id]["frameCount"]) for track_id in track_ids
        )
        natural_lane_demand = math.ceil(maximum_frames / 480)
        if natural_lane_demand > 2048:
            raise ValueError(
                f"engine-event program {program['id']} requires "
                f"{natural_lane_demand} logical lanes, above AC's proven 2048 cap"
            )
        program["policy"]["maxDecodedOneShotFrameCount"] = maximum_frames
        program["policy"]["laneCount"] = natural_lane_demand
    return programs


def _program_policy_by_track(
    programs: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for program in programs:
        policy = program.get("policy")
        if not isinstance(policy, dict):
            continue
        for node in program.get("nodes", []):
            if not isinstance(node, dict) or node.get("kind") != "TRACK":
                continue
            track_id = str(node.get("trackId") or "")
            if not track_id or track_id in result:
                raise ValueError("program policy track binding is invalid or duplicated")
            result[track_id] = policy
    return result


def _copy_certified_limiter_pcm(
    certified: dict[str, Any], proof_path: Path, destination: Path
) -> tuple[int, int, bool]:
    """Copy and independently hash-check one oracle-certified limiter WAV."""

    source_verification = certified.get("sourceVerification")
    verified = certified.get("verifiedTargetPcm")
    if not isinstance(source_verification, dict) or not isinstance(verified, dict):
        raise ValueError("certified limiter record has no PCM proof")
    pcm_proof = source_verification.get("pcm")
    if not isinstance(pcm_proof, dict):
        raise ValueError("limiter source verification has no PCM evidence")
    relative = pcm_proof.get("finalWavRelativePath")
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise ValueError("limiter source verification WAV path is invalid")
    proof_root = Path(proof_path).resolve(strict=True).parent
    source = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
    if proof_root != source and proof_root not in source.parents:
        raise ValueError("limiter source verification WAV escapes its proof root")
    if sha256_file(source) != pcm_proof.get("finalWavSha256"):
        raise ValueError("limiter certified WAV file hash changed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if sha256_file(destination) != pcm_proof.get("finalWavSha256"):
        raise ValueError("limiter certified WAV copy changed")
    integrity = inspect_pcm16_stereo_wav(destination)
    if (
        integrity.sha256 != verified.get("pcmPayloadSha256")
        or integrity.frame_count != verified.get("frameCount")
        or (integrity.sample_rate, integrity.channels, integrity.bits_per_sample)
        != (48000, 2, 16)
    ):
        raise ValueError("limiter certified PCM identity/frame contract changed")
    disposition = verified.get("audibilityDisposition")
    silent = disposition == "AUTHORED_TARGET_SILENT"
    if disposition not in {"AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_SILENT"}:
        raise ValueError("limiter certified PCM audibility disposition is invalid")
    if silent != (not math.isfinite(integrity.peak_dbfs)):
        raise ValueError("limiter certified silence disagrees with decoded PCM")
    start = int(verified.get("loopStartFrame", 0))
    end = int(verified.get("loopEndFrameExclusive", 0))
    if not (0 <= start <= end <= integrity.frame_count):
        raise ValueError("limiter certified loop bounds are outside PCM")
    return start, end, silent


def _copy_certified_shift_silence_pcm(
    verification: dict[str, Any], proof_path: Path, destination: Path
) -> None:
    """Copy and independently re-check one of two exact-zero shift renders."""

    renders = verification.get("renders")
    if not isinstance(renders, list) or len(renders) != 2:
        raise ValueError("certified shift-silence record has no two-take proof")
    selected = renders[0]
    relative = selected.get("finalWavRelativePath")
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise ValueError("certified shift-silence WAV path is invalid")
    proof_root = Path(proof_path).resolve(strict=True).parent
    source = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
    if proof_root != source and proof_root not in source.parents:
        raise ValueError("certified shift-silence WAV escapes its proof root")
    if sha256_file(source) != selected.get("finalWavSha256"):
        raise ValueError("certified shift-silence WAV file hash changed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if sha256_file(destination) != selected.get("finalWavSha256"):
        raise ValueError("certified shift-silence WAV copy changed")
    integrity = inspect_pcm16_stereo_wav(destination)
    if (
        integrity.sha256 != selected.get("pcmPayloadSha256")
        or integrity.frame_count != 96000
        or math.isfinite(integrity.peak_dbfs)
        or (integrity.sample_rate, integrity.channels, integrity.bits_per_sample)
        != (48000, 2, 16)
    ):
        raise ValueError("certified shift-silence PCM identity changed")


def _copy_certified_turbo_pcm(
    certified: dict[str, Any], proof_path: Path, destination: Path
) -> tuple[int | None, int | None, bool]:
    """Copy one source-bound turbo WAV and recheck its exact PCM contract."""

    verification = certified.get("sourceVerification")
    verified = certified.get("verifiedTargetPcm")
    capture = verification.get("capture") if isinstance(verification, dict) else None
    if not isinstance(capture, dict) or not isinstance(verified, dict):
        raise ValueError("certified turbo record has no PCM proof")
    relative = capture.get("finalWavRelativePath")
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise ValueError("turbo source verification WAV path is invalid")
    proof_root = Path(proof_path).resolve(strict=True).parent
    source = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
    if proof_root != source and proof_root not in source.parents:
        raise ValueError("turbo source verification WAV escapes its proof root")
    if sha256_file(source) != capture.get("finalWavSha256"):
        raise ValueError("turbo certified WAV file hash changed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if sha256_file(destination) != capture.get("finalWavSha256"):
        raise ValueError("turbo certified WAV copy changed")
    integrity = inspect_pcm16_stereo_wav(destination)
    if (
        integrity.sha256 != verified.get("pcmPayloadSha256")
        or integrity.frame_count != verified.get("frameCount")
        or (integrity.sample_rate, integrity.channels, integrity.bits_per_sample)
        != (48000, 2, 16)
    ):
        raise ValueError("turbo certified PCM identity/frame contract changed")
    disposition = verified.get("audibilityDisposition")
    silent = disposition == "AUTHORED_TARGET_SILENT"
    if disposition not in {"AUDIBLE_TARGET_PCM", "AUTHORED_TARGET_SILENT"}:
        raise ValueError("turbo certified PCM audibility disposition is invalid")
    if silent != (not math.isfinite(integrity.peak_dbfs)):
        raise ValueError("turbo certified silence disagrees with decoded PCM")
    start = verified.get("loopStartFrame")
    end = verified.get("loopEndFrameExclusive")
    if (start is None) != (end is None):
        raise ValueError("turbo certified loop bounds must both be null or set")
    if start is not None:
        if (
            isinstance(start, bool)
            or not isinstance(start, int)
            or isinstance(end, bool)
            or not isinstance(end, int)
            or not 0 <= start < end <= integrity.frame_count
        ):
            raise ValueError("turbo certified loop bounds are outside PCM")
    return start, end, silent


def _copy_certified_property_one_pcm(
    certified: dict[str, Any], proof_path: Path, destination: Path
) -> tuple[int, int]:
    """Copy one compact property-one capture and bind every PCM/loop field."""

    source_verification = certified.get("sourceVerification")
    capture = certified.get("capture")
    if (
        not isinstance(source_verification, dict)
        or not isinstance(capture, dict)
        or source_verification.get("capture") != capture
    ):
        raise ValueError("certified property-one record has no bound PCM proof")
    relative = capture.get("finalWavRelativePath")
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise ValueError("property-one source verification WAV path is invalid")
    proof_root = Path(proof_path).resolve(strict=True).parent
    source = (proof_root / Path(*relative.split("/"))).resolve(strict=True)
    if proof_root != source and proof_root not in source.parents:
        raise ValueError("property-one source verification WAV escapes its proof root")
    expected_wav_sha = capture.get("finalWavSha256")
    if sha256_file(source) != expected_wav_sha:
        raise ValueError("property-one certified WAV file hash changed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if sha256_file(destination) != expected_wav_sha:
        raise ValueError("property-one certified WAV copy changed")
    integrity = inspect_pcm16_stereo_wav(destination)
    if (
        integrity.sha256 != capture.get("pcmPayloadSha256")
        or integrity.frame_count != capture.get("frameCount")
        or (integrity.sample_rate, integrity.channels, integrity.bits_per_sample)
        != (48000, 2, 16)
        or not is_release_audible_peak_dbfs(integrity.peak_dbfs)
    ):
        raise ValueError("property-one certified PCM identity/frame contract changed")
    start = capture.get("loopStartFrame")
    end = capture.get("loopEndFrameExclusive")
    if (
        isinstance(start, bool)
        or not isinstance(start, int)
        or isinstance(end, bool)
        or not isinstance(end, int)
        or not 0 <= start < end <= integrity.frame_count
    ):
        raise ValueError("property-one certified loop bounds are outside PCM")
    return start, end


def _repair_loop(path: Path) -> tuple[int, int, float]:
    with wave.open(str(path), "rb") as source:
        pcm = source.readframes(source.getnframes())
        frame_count = source.getnframes()
    guard = min(960, max(1, frame_count // 12))
    best = find_best_loop_bounds(
        pcm,
        nominal_start=guard,
        nominal_end=frame_count - guard,
        search_frames=min(720, guard),
    )
    if best.peak_dbfs > -36.0:
        pcm, best = crossfade_loop_seam(
            pcm,
            best.start_frame,
            best.end_frame,
            crossfade_frames=min(960, (best.end_frame - best.start_frame) // 8),
        )
        _write_pcm_wav(path, pcm)
    if best.peak_dbfs > -18.0:
        raise ValueError(
            f"loop repair left an unsafe seam at {best.peak_dbfs:.2f} dBFS"
        )
    return best.start_frame, best.end_frame, best.peak_dbfs


def _wav_rms(path: Path) -> float:
    square_sum = 0
    sample_count = 0
    with wave.open(str(path), "rb") as source:
        if (
            source.getframerate(),
            source.getnchannels(),
            source.getsampwidth(),
            source.getcomptype(),
        ) != (48000, 2, 2, "NONE"):
            raise ValueError("curve-gate WAV is not canonical PCM16/48 kHz/stereo")
        while payload := source.readframes(16384):
            samples = array.array("h")
            samples.frombytes(payload)
            if sys.byteorder != "little":
                samples.byteswap()
            square_sum += sum(int(value) * int(value) for value in samples)
            sample_count += len(samples)
    if sample_count == 0 or square_sum == 0:
        return 0.0
    return math.sqrt(square_sum / sample_count) / 32768.0


def _assert_target_only_runtime_identity(
    scheduled_names: tuple[str, ...], expected_name: str, recipe_id: str
) -> None:
    if not scheduled_names or set(scheduled_names) != {expected_name}:
        raise ValueError(
            f"target-only runtime identity failed for {recipe_id}: "
            f"expected one source identity, observed {len(set(scheduled_names))}"
        )


def _render_curve_gate(
    renderer: SilentFmodReferenceRenderer,
    isolated_bank: Path,
    recipe: dict[str, Any],
    expected_sample_name: str,
    workspace: Path,
) -> dict[str, Any] | None:
    probes = runtime_curve_probe_controls(recipe)
    if not probes:
        return None
    baseline_path = workspace / f".{recipe['id']}.curve-baseline.wav"
    baseline = renderer.render_event(
        isolated_bank,
        recipe["event"],
        baseline_path,
        parameters=recipe["parameters"],
        duration_frames=CURVE_PROBE_DURATION_FRAMES,
        warmup_frames=CURVE_PROBE_WARMUP_FRAMES,
    )
    baseline_worker_evidence = renderer.last_fresh_process_evidence
    if baseline_worker_evidence is None:
        raise ValueError("release curve baseline lacks fresh-render worker evidence")
    _assert_target_only_runtime_identity(
        baseline.scheduled_sound_names, expected_sample_name, recipe["id"]
    )
    baseline_rms = _wav_rms(baseline_path)
    measurements: list[dict[str, Any]] = []
    probe_worker_evidence: list[dict[str, Any]] = []
    paths = [baseline_path]
    try:
        for index, probe in enumerate(probes):
            path = workspace / f".{recipe['id']}.curve-{index}.wav"
            paths.append(path)
            rendered = renderer.render_event(
                isolated_bank,
                recipe["event"],
                path,
                parameters=probe["parameters"],
                duration_frames=CURVE_PROBE_DURATION_FRAMES,
                warmup_frames=CURVE_PROBE_WARMUP_FRAMES,
            )
            worker_evidence = renderer.last_fresh_process_evidence
            if worker_evidence is None:
                raise ValueError("release curve probe lacks fresh-render worker evidence")
            probe_worker_evidence.append(worker_evidence)
            _assert_target_only_runtime_identity(
                rendered.scheduled_sound_names, expected_sample_name, recipe["id"]
            )
            measurements.append({"axis": probe["axis"], "rms": _wav_rms(path)})
        result = validate_runtime_curve_measurements(
            recipe, baseline_rms, measurements
        )
        result["freshRenderWorker"] = {
            "baseline": baseline_worker_evidence,
            "probes": probe_worker_evidence,
        }
        return result
    finally:
        for path in paths:
            path.unlink(missing_ok=True)


def _turbo_physics_metadata(root: Path, car: dict[str, Any]) -> dict[str, Any]:
    """Emit the per-car 3 ms physical turbo model that drives FMOD boost/BOV."""

    spec = load_car_spec(root, str(car["id"]))
    controller_files = {
        str(item["file"]).casefold(): str(item["file"])
        for item in car["engine"]["turboControllers"]
    }
    turbos: list[dict[str, Any]] = []
    for index, turbo in enumerate(spec.turbos):
        controller = controller_files.get(f"ctrl_turbo{index}.ini")
        turbos.append(
            {
                "maximumBoost": turbo.maximum_boost,
                "wastegate": turbo.wastegate,
                "referenceRpm": turbo.reference_rpm,
                "gamma": turbo.gamma,
                "lagUp": turbo.lag_up,
                "lagDown": turbo.lag_down,
                "controllerFile": controller,
            }
        )
    if len(controller_files) > len(turbos):
        raise ValueError(f"{car['id']} has a turbo controller without a matching turbo")
    return {
        "bovPressureThreshold": spec.turbos[0].bov_threshold if spec.turbos else 0.5,
        "turbos": turbos,
    }


def _throttle_map_metadata(root: Path, car: dict[str, Any]) -> dict[str, Any]:
    spec = load_car_spec(root, str(car["id"]))
    points = [[float(x), float(y)] for x, y in spec.throttle_curve]
    if (
        len(points) < 2
        or points[0][0] > 0.0
        or points[-1][0] < 1.0
        or any(
            not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0)
            for x, y in points
        )
        or any(right[0] <= left[0] for left, right in zip(points, points[1:]))
    ):
        raise ValueError(f"{car['id']} has an invalid normalized throttle map")
    return {
        "input": "NORMALIZED_PEDAL",
        "output": "NORMALIZED_ENGINE_GAS",
        "interpolation": "CLAMPED_LINEAR",
        "points": points,
    }


def _engine_gas_assist_metadata(root: Path, car: dict[str, Any]) -> dict[str, Any]:
    """Serialize AC's ordered assist-gas program without sorting its knots."""

    spec = load_drivetrain_spec(root, str(car["id"]))
    profile = [[float(x), float(y)] for x, y in spec.autoblip_profile_ms]
    if (
        len(profile) not in {0, 4}
        or (profile and profile[0] != [0.0, 0.0])
        or any(
            not math.isfinite(x)
            or not math.isfinite(y)
            or x < 0.0
            or not 0.0 <= y <= 1.0
            for x, y in profile
        )
        or (profile and profile[-1][1] != 0.0)
    ):
        raise ValueError(f"{car['id']} has an invalid authored AutoBlip program")
    gas_cutoff_ms = float(spec.auto_gas_cutoff_s) * 1000.0
    engine_cutoff_ms = float(spec.auto_cutoff_time_s) * 1000.0
    if gas_cutoff_ms < 0.0 or engine_cutoff_ms < 0.0:
        raise ValueError(f"{car['id']} has a negative authored assist cut")
    return {
        "autoShifterGasCutoffMs": gas_cutoff_ms,
        "engineCutoffMs": engine_cutoff_ms,
        "autoBlipElectronic": bool(spec.autoblip_electronic),
        "autoBlipEnableMode": "ELECTRONIC_OR_AUTOCLUTCH",
        "autoBlipClutchGateExclusive": 1.0 / math.pi,
        # AC appends these points in authored order.  Nineteen official cars
        # deliberately have POINT_2 < POINT_1; sorting changes game behavior.
        "autoBlipProfile": profile,
        "autoBlipEndTimeMs": profile[-1][0] if profile else 0.0,
        "autoBlipEvaluator": "AUTHORED_ORDER_FIRST_UPPER_BOUND_LINEAR",
        "autoBlipCombiner": "MAX_WITH_POST_ASSIST_PEDAL",
        "processingOrder": (
            "AUTOBLIP_THEN_AUTO_SHIFTER_CUT_THEN_ENGINE_CUTOFF_THEN_"
            "THROTTLE_MAP_THEN_LIMITER_CUT"
        ),
    }


def _one_shot_trigger_policies(
    root: Path,
    car: dict[str, Any],
    programs: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    """Build the car-specific state machines for family-level PCM programs."""

    spec = load_car_spec(root, str(car["id"]))
    policies: dict[str, dict[str, Any]] = {}
    for program in programs:
        trigger = str(program["trigger"])
        if trigger in {"ENGINE_EVENT", "TURBO_EVENT", "ENGINE_START"}:
            # The family-level policy embedded in this program is the exact
            # FMOD engine/turbo event state machine and has no per-car
            # threshold.  Turbo programs consume the per-car physical turbo
            # controls directly; emitting a second generic lift policy would
            # both duplicate and contradict their authored scheduling.
            # ENGINE_START is a direct one-shot with no per-car thresholds.
            continue
        policy: dict[str, Any] = {
            "kind": trigger,
            "minimumRpm": 0.0,
            "maximumRpm": None,
            "armPedal": None,
            "firePedal": None,
            "armBoost": None,
            "initialPeakPedal": None,
            "initialArmPedal": None,
            "initialFirePedal": None,
            "minimumArmMs": 0.0,
            "cooldownMs": 0.0,
            "periodHz": None,
        }
        if trigger == "THROTTLE_LIFT":
            policy.update(
                {
                    "kind": "AC_BACKFIRE",
                    "minimumRpm": spec.backfire.minimum_rpm,
                    "maximumRpm": spec.backfire.maximum_rpm,
                    # These are the exact peak-relative dynamic ratios from
                    # BackfireParams, not fixed pedal thresholds.
                    "armPedal": spec.backfire.trigger_gas,
                    "firePedal": spec.backfire.maximum_gas,
                    "initialPeakPedal": 0.6,
                    "initialArmPedal": 0.8,
                    "initialFirePedal": 0.25,
                    "minimumArmMs": 1000.0,
                }
            )
        elif trigger == "BOV_LIFT":
            # BOV is armed by the physical pressure signal emitted by the
            # per-car turbo model.  Pedal/boost approximations are forbidden.
            policy["kind"] = "BOV_LIFT"
        elif trigger == "LIMITER_EVENT":
            policy.update(
                {
                    "kind": "LIMITER_EVENT",
                    "minimumRpm": spec.limiter_rpm,
                    # limiterHz drives the presentation physics cut/pulse. It
                    # resets the persistent decay timer; it never retriggers
                    # decoded PCM directly.
                    "periodHz": None,
                }
            )
        elif trigger not in {"SHIFT_UP", "SHIFT_DOWN"}:
            raise ValueError(f"unsupported one-shot trigger policy {trigger!r}")
        policies[str(program["id"])] = policy
    return policies


def _compile_family(
    root: Path,
    catalog: dict[str, Any],
    plan: dict[str, Any],
    selector: str,
    output_root: Path,
    codec: PinnedFlacCodec,
    *,
    graph_root: Path | None = None,
    limiter_proof_path: Path = DEFAULT_LIMITER_ORACLE_PROOF,
    shift_silence_proof_path: Path = DEFAULT_SHIFT_SILENCE_ORACLE_PROOF,
    turbo_transient_proof_path: Path = DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF,
    priority_proof_path: Path = DEFAULT_PRIORITY_ORACLE_PROOF,
    continuous_disposition_proof_path: Path = DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF,
    property_one_proof_path: Path = DEFAULT_PROPERTY_ONE_ORACLE_PROOF,
) -> Path:
    planned = _resolve_family(plan["families"], selector)
    if plan["schemaVersion"] == 2:
        unresolved_priorities = sorted(
            {
                str(recipe["role"])
                for recipe in planned["recipes"]
                if recipe.get("softwareChannelPriority") is None
            }
        )
        if unresolved_priorities:
            raise ValueError(
                "software-channel priority awaits a source-bound FMOD oracle for roles: "
                + ", ".join(unresolved_priorities)
            )
        priority_proof = Path(priority_proof_path).resolve(strict=True)
        priority_oracle = plan.get("priorityOracle")
        if (
            not isinstance(priority_oracle, dict)
            or sha256_file(priority_proof) != priority_oracle.get("proofSha256")
        ):
            raise ValueError("software-channel priority oracle changed since planning")
        if any(
            recipe.get("sourceProjection") == "ADAPTIVE_RPM_WINDOWS"
            for recipe in planned["recipes"]
        ):
            raise ValueError(
                "adaptive RPM-window source awaits its target-only spectral/mixer gate"
            )
        limiter_programs = [
            program
            for program in planned["oneShotPrograms"]
            if program["trigger"] == "LIMITER_EVENT"
        ]
        if limiter_programs and any("policy" not in program for program in limiter_programs):
            raise ValueError(
                "limiter event awaits its authored persistent lifecycle policy"
            )
        proof_path = Path(limiter_proof_path).resolve(strict=True)
        limiter_oracle = plan.get("limiterOracle")
        if (
            not isinstance(limiter_oracle, dict)
            or sha256_file(proof_path) != limiter_oracle.get("proofSha256")
        ):
            raise ValueError("limiter oracle proof changed since plan generation")
        shift_proof_path = Path(shift_silence_proof_path).resolve(strict=True)
        shift_silence_oracle = plan.get("shiftSilenceOracle")
        if (
            not isinstance(shift_silence_oracle, dict)
            or sha256_file(shift_proof_path)
            != shift_silence_oracle.get("proofSha256")
        ):
            raise ValueError(
                "shift-silence oracle proof changed since plan generation"
            )
        _shift_proof, shift_silence_verifications = (
            load_shift_silence_source_verifications(
                shift_proof_path,
                expected_catalog_sha256=catalog["catalogSha256"],
            )
        )
        turbo_proof_path = Path(turbo_transient_proof_path).resolve(strict=True)
        turbo_oracle = plan.get("turboTransientOracle")
        if (
            not isinstance(turbo_oracle, dict)
            or sha256_file(turbo_proof_path)
            != turbo_oracle.get("proofSha256")
        ):
            raise ValueError(
                "turbo-transient oracle proof changed since plan generation"
            )
        _turbo_proof, turbo_transient_verifications = (
            load_turbo_transient_source_verifications(turbo_proof_path)
        )
        continuous_proof_path = Path(
            continuous_disposition_proof_path
        ).resolve(strict=True)
        continuous_oracle = plan.get("continuousSourceOracle")
        if (
            not isinstance(continuous_oracle, dict)
            or sha256_file(continuous_proof_path)
            != continuous_oracle.get("proofSha256")
        ):
            raise ValueError(
                "continuous-source disposition proof changed since plan generation"
            )
        property_proof_path = Path(property_one_proof_path).resolve(strict=True)
        property_oracle = plan.get("propertyOneOracle")
        if (
            not isinstance(property_oracle, dict)
            or sha256_file(property_proof_path)
            != property_oracle.get("proofSha256")
        ):
            raise ValueError("property-one oracle proof changed since plan generation")
        _property_proof, property_one_verifications = (
            load_property_one_source_verifications(property_proof_path)
        )
    else:
        shift_proof_path = Path(shift_silence_proof_path)
        shift_silence_verifications = {}
        turbo_proof_path = Path(turbo_transient_proof_path)
        turbo_transient_verifications = {}
        continuous_proof_path = Path(continuous_disposition_proof_path)
        property_proof_path = Path(property_one_proof_path)
        property_one_verifications = {}
    family = next(item for item in catalog["soundFamilies"] if item["id"] == planned["familyId"])
    cars_by_id = {car["id"]: car for car in catalog["cars"]}
    representative = cars_by_id[planned["representativeCarId"]]
    workspace = output_root / "families" / family["id"]
    audio_directory = workspace / "audio"
    audio_directory.mkdir(parents=True, exist_ok=True)
    # Each target and curve probe executes in its own hash-bound worker process
    # as a defensive isolation boundary.  A prior failure was traced to an
    # authored step-edge probe, not to proven byte-identical FMOD lifecycle
    # divergence, so this intentionally makes no stronger claim.
    renderer = SilentFmodReferenceRenderer(root, fresh_process_per_render=True)
    bank = root / representative["provenance"]["bankPath"]
    source_bank_sha_before = sha256_file(bank)
    release_graph: dict[str, Any] | None = None
    release_classification: dict[str, Any] | None = None
    if plan["schemaVersion"] == 2:
        if graph_root is None:
            raise ValueError("schema-v2 compilation requires a graph root")
        release_graph = graph_report_for_family(
            graph_root, family["id"], planned["graphReportSha256"]
        )
        release_classification = classify_bank_graph_sources(release_graph)
        if (
            __import__("hashlib").sha256(
                canonical_json_bytes(release_classification)
            ).hexdigest()
            != planned["classificationSha256"]
        ):
            raise ValueError("source classification changed since release-plan creation")
    rendered: list[dict[str, Any]] = []
    curve_gates: list[dict[str, Any]] = []
    certified_silent_sources: list[dict[str, Any]] = []
    silent_recipe_ids: set[str] = set()
    program_policy_by_track = _program_policy_by_track(
        planned.get("oneShotPrograms", [])
    )
    for recipe in planned["recipes"]:
        wav_path = workspace / f"{recipe['id']}.wav"
        render_bank = bank
        isolated_path: Path | None = None
        expected_sample_name: str | None = None
        certified_limiter: dict[str, Any] | None = None
        certified_limiter_bounds: tuple[int, int, bool] | None = None
        certified_silent_shift: dict[str, Any] | None = None
        certified_turbo: dict[str, Any] | None = None
        certified_turbo_bounds: tuple[int | None, int | None, bool] | None = None
        certified_property_one: dict[str, Any] | None = None
        certified_property_one_bounds: tuple[int, int] | None = None
        engine_int_event_start = (
            recipe.get("sourceProjection") == ENGINE_INT_EVENT_START_PROJECTION
        )
        if release_graph is not None:
            _row, target, reachable = verify_recipe_against_graph(
                recipe,
                release_graph,
                release_classification,
                limiter_proof_path=proof_path,
                shift_silence_proof_path=shift_proof_path,
                turbo_transient_proof_path=turbo_proof_path,
                turbo_transient_source_verifications=(
                    turbo_transient_verifications
                ),
                property_one_proof_path=property_proof_path,
                property_one_source_verifications=property_one_verifications,
            )
            if recipe["role"] == "LIMITER":
                certified_limiter = certified_limiter_program_for_recipe(
                    recipe, release_graph, _row, proof_path
                )
                limiter_policy = program_policy_by_track.get(recipe["id"])
                if (
                    limiter_policy is None
                    or limiter_policy.get("programMode")
                    != certified_limiter["programMode"]
                    or limiter_policy.get("sourceVerificationPayloadSha256")
                    != certified_limiter["fidelity"][
                        "sourceVerificationPayloadSha256"
                    ]
                ):
                    raise ValueError(
                        "limiter manifest policy disagrees with source-bound proof"
                    )
            if recipe.get("sourceProjection") == "CERTIFIED_AUTHORED_SILENCE":
                verification = shift_silence_verifications.get(
                    str(recipe["sourceGuid"])
                )
                if verification is None:
                    raise ValueError(
                        "silent shift recipe has no source-bound verification"
                    )
                certificate = certify_silent_shift_source(
                    release_graph,
                    _row,
                    verification,
                    graph_report_sha256=planned["graphReportSha256"],
                    representative_car_id=representative["id"],
                    installed_bank_relative_path=representative["provenance"][
                        "bankPath"
                    ],
                )
                if (
                    certificate["role"] != recipe["role"]
                    or certificate["verificationPayloadSha256"]
                    != recipe["sourceCurveSha256"]
                ):
                    raise ValueError(
                        "silent shift recipe disagrees with source-bound proof"
                    )
                certified_silent_shift = verification
            if recipe.get("sourceProjection") == "CERTIFIED_TURBO_TRANSIENT":
                certified_turbo = certified_turbo_source_for_recipe(
                    recipe,
                    release_graph,
                    _row,
                    turbo_proof_path,
                    turbo_transient_verifications,
                )
                verification_sha = certified_turbo["fidelity"][
                    "sourceVerificationPayloadSha256"
                ]
                program_node = next(
                    (
                        node
                        for program in planned["oneShotPrograms"]
                        for node in program["nodes"]
                        if node.get("kind") == "TRACK"
                        and node.get("trackId") == recipe["id"]
                    ),
                    None,
                )
                if (
                    program_node is None
                    or program_node.get("sourceVerificationPayloadSha256")
                    != verification_sha
                ):
                    raise ValueError(
                        "turbo manifest leaf disagrees with source-bound proof"
                    )
            if (
                recipe.get("sourceProjection")
                == "CERTIFIED_PROPERTY_ONE_RELATIVE_RATE"
            ):
                certified_property_one = certified_property_one_source_for_recipe(
                    recipe,
                    release_graph,
                    _row,
                    property_proof_path,
                    property_one_verifications,
                )
            sample = target.get("sample")
            if not isinstance(sample, dict) or not isinstance(sample.get("name"), str):
                raise ValueError(f"source {recipe['sourceGuid']} has no runtime identity")
            expected_sample_name = sample["name"]
            if (
                certified_silent_shift is None
                and certified_limiter is None
                and certified_turbo is None
                and certified_property_one is None
                and not engine_int_event_start
            ):
                isolated_path = workspace / "isolated" / f"{recipe['id']}.bank"
                muted = reachable - {recipe["sourceGuid"]}
                disabled_parent_guids = fully_muted_multi_instrument_guids(
                    release_graph, muted
                )
                single_shot_parent_guids: set[str] = set()
                if not recipe["looping"] and certified_limiter is None:
                    evidence = _row.get("decisionEvidence")
                    ancestry = (
                        evidence.get("instrumentAncestry", [])
                        if isinstance(evidence, dict)
                        else []
                    )
                    for ancestor in ancestry[1:]:
                        if (
                            isinstance(ancestor, dict)
                            and ancestor.get("kind") == "MultiInstrumentNode"
                            and int(
                                (ancestor.get("baseProperties") or {}).get(
                                    "loopCount", 0
                                )
                            )
                            < 0
                        ):
                            guid = str(
                                ancestor.get("instrumentGuid", "")
                            ).casefold()
                            if guid:
                                single_shot_parent_guids.add(guid)
                if muted or disabled_parent_guids or single_shot_parent_guids:
                    isolated = create_isolated_bank_copy(
                        bank,
                        release_graph,
                        muted,
                        isolated_path,
                        disabled_parent_guids=disabled_parent_guids,
                        single_shot_parent_guids=single_shot_parent_guids,
                    )
                    render_bank = isolated.output_path
                else:
                    isolated_path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(bank, isolated_path)
                    if (
                        isolated_path.stat().st_size != bank.stat().st_size
                        or sha256_file(isolated_path) != source_bank_sha_before
                    ):
                        raise ValueError(
                            "single-source verified bank copy changed bytes"
                        )
                    render_bank = isolated_path
        try:
            if certified_limiter is not None:
                certified_limiter_bounds = _copy_certified_limiter_pcm(
                    certified_limiter, proof_path, wav_path
                )
            elif certified_silent_shift is not None:
                _copy_certified_shift_silence_pcm(
                    certified_silent_shift, shift_proof_path, wav_path
                )
            elif certified_turbo is not None:
                certified_turbo_bounds = _copy_certified_turbo_pcm(
                    certified_turbo, turbo_proof_path, wav_path
                )
            elif certified_property_one is not None:
                certified_property_one_bounds = _copy_certified_property_one_pcm(
                    certified_property_one, property_proof_path, wav_path
                )
            elif engine_int_event_start:
                reference = renderer.render_event(
                    bank,
                    recipe["event"],
                    wav_path,
                    parameters=recipe["parameters"],
                    duration_frames=recipe["durationFrames"],
                    warmup_frames=0,
                    variant_index=recipe.get("variantIndex", 0),
                )
            else:
                reference = renderer.render_event(
                    render_bank,
                    recipe["event"],
                    wav_path,
                    parameters=recipe["parameters"],
                    duration_frames=recipe["durationFrames"],
                    warmup_frames=recipe["warmupFrames"],
                    variant_index=recipe.get("variantIndex", 0),
                )
            if (
                expected_sample_name is not None
                and not engine_int_event_start
                and certified_limiter is None
                and certified_silent_shift is None
                and certified_turbo is None
                and certified_property_one is None
            ):
                if (
                    not recipe["looping"]
                    and set(reference.scheduled_sound_names) != {expected_sample_name}
                ):
                    # A SmartRandom/playlist parent can schedule a sibling even
                    # when sibling waveform trigger chances are zero.  Render a
                    # deterministic bounded take sequence and crop the first
                    # take whose callback proves the target was the sole sound.
                    reference = renderer.render_event(
                        render_bank,
                        recipe["event"],
                        wav_path,
                        parameters=recipe["parameters"],
                        duration_frames=recipe["durationFrames"],
                        warmup_frames=recipe["warmupFrames"],
                        required_sound_name=expected_sample_name,
                        maximum_takes=deterministic_source_selection_take_limit(
                            _row, release_graph
                        ),
                    )
                _assert_target_only_runtime_identity(
                    reference.scheduled_sound_names,
                    expected_sample_name,
                    recipe["id"],
                )
                curve_gate = _render_curve_gate(
                    renderer,
                    render_bank,
                    recipe,
                    expected_sample_name,
                    workspace,
                )
                if curve_gate is not None:
                    curve_gates.append(curve_gate)
            if (
                certified_limiter is None
                and certified_silent_shift is None
                and certified_turbo is None
                and certified_property_one is None
            ):
                _apply_capture_pcm_post_gain(
                    wav_path, recipe.get("capturePcmPostGainLinear", 1.0)
                )
        finally:
            if isolated_path is not None:
                isolated_path.unlink(missing_ok=True)
        start: int | None = None
        end: int | None = None
        limiter_policy = program_policy_by_track.get(recipe["id"])
        if certified_limiter_bounds is not None:
            certified_start, certified_end, certified_silent = certified_limiter_bounds
            if recipe["looping"]:
                start, end = certified_start, certified_end
            elif certified_start != 0 or certified_end != int(recipe["durationFrames"]):
                raise ValueError("certified limiter one-shot bounds changed")
            if certified_silent:
                proof_hash = certified_limiter["fidelity"][
                    "sourceVerificationPayloadSha256"
                ]
                certified_silent_sources.append(
                    {
                        "sourceGuid": recipe["sourceGuid"],
                        "role": "LIMITER",
                        "disposition": "AUTHORED_TARGET_SILENT",
                        "verificationPayloadSha256": proof_hash,
                    }
                )
                silent_recipe_ids.add(recipe["id"])
        elif certified_silent_shift is not None:
            proof_hash = certified_silent_shift["verificationPayloadSha256"]
            certified_silent_sources.append(
                {
                    "sourceGuid": recipe["sourceGuid"],
                    "role": recipe["role"],
                    "disposition": "AUTHORED_TARGET_SILENT",
                    "verificationPayloadSha256": proof_hash,
                }
            )
            silent_recipe_ids.add(recipe["id"])
        elif certified_turbo_bounds is not None:
            certified_start, certified_end, certified_silent = (
                certified_turbo_bounds
            )
            if recipe["looping"]:
                if certified_start is None or certified_end is None:
                    raise ValueError("certified turbo loop bounds are absent")
                start, end = certified_start, certified_end
            elif certified_start is not None or certified_end is not None:
                raise ValueError("certified turbo one-shot unexpectedly loops")
            if certified_silent:
                proof_hash = certified_turbo["fidelity"][
                    "sourceVerificationPayloadSha256"
                ]
                certified_silent_sources.append(
                    {
                        "sourceGuid": recipe["sourceGuid"],
                        "role": recipe["role"],
                        "disposition": "AUTHORED_TARGET_SILENT",
                        "verificationPayloadSha256": proof_hash,
                    }
                )
                silent_recipe_ids.add(recipe["id"])
        elif certified_property_one_bounds is not None:
            start, end = certified_property_one_bounds
        elif recipe["looping"]:
            if (
                recipe["role"] == "LIMITER"
                and limiter_policy is not None
                and limiter_policy.get("programMode")
                == "PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT"
            ):
                # This capture is already one exact authored event period.
                # Loop repair would alter the FMOD timeline and is forbidden;
                # the source-bound adjacent-period verifier owns its seam gate.
                start, end = 0, int(recipe["durationFrames"])
            else:
                start, end, _seam = _repair_loop(wav_path)
        else:
            _trim_trailing_digital_silence(wav_path)
        pcm = inspect_pcm16_stereo_wav(wav_path)
        if recipe["id"] in silent_recipe_ids:
            if math.isfinite(pcm.peak_dbfs):
                raise ValueError("certified authored-target-silent source is audible")
            wav_path.unlink(missing_ok=True)
            continue
        if not is_release_audible_peak_dbfs(pcm.peak_dbfs):
            wav_path.unlink(missing_ok=True)
            if recipe["role"] == "ENGINE_START":
                silent_recipe_ids.add(recipe["id"])
                continue
            if plan["schemaVersion"] == 2:
                raise ValueError(
                    f"retained source {recipe['sourceGuid']} ({recipe['role']}) is not "
                    f"finite and strictly above the {MIN_AUDIBLE_PEAK_DBFS} dBFS "
                    "audible release floor"
                )
            if recipe["role"] == "IDLE":
                raise ValueError(
                    f"authored IDLE capture is below {MIN_AUDIBLE_PEAK_DBFS} dBFS"
                )
            continue
        rendered.append(
            {
                "recipe": recipe,
                "wavPath": wav_path,
                "loopStart": start,
                "loopEnd": end,
                "integrity": pcm,
            }
        )

    if sha256_file(bank) != source_bank_sha_before:
        raise ValueError("installed source bank changed during release compilation")
    if plan["schemaVersion"] == 2:
        expected_curve_gates = sum(
            bool(runtime_curve_probe_controls(recipe))
            for recipe in planned["recipes"]
        )
        if len(curve_gates) != expected_curve_gates:
            raise ValueError("not every direct continuous source passed its runtime curve gate")
        _write_json_atomic(
            workspace / "curve-gates.json",
            {
                "schema": "aclib-runtime-curve-gates-v1",
                "familyId": family["id"],
                "sourceBankSha256": source_bank_sha_before,
                "gates": curve_gates,
                "passed": True,
            },
        )

    if not rendered:
        raise ValueError("family produced no audible reference captures")
    maximum_family_rpm = max(
        float(cars_by_id[car_id]["engine"]["tachometerMaximumRpm"])
        for car_id in family["memberIds"]
    )
    while True:
        mix_bound_before = _default_mix_bound_dbfs(rendered, maximum_family_rpm)
        maximum_one_shot_peak = max(
            (
                item["integrity"].peak_dbfs
                for item in rendered
                if item["recipe"]["role"] not in LOOP_ROLES
            ),
            default=-math.inf,
        )
        family_attenuation_db = min(
            0.0, -3.1 - max(mix_bound_before, maximum_one_shot_peak)
        )
        retained = (
            rendered
            if plan["schemaVersion"] == 2
            else [
                item
                for item in rendered
                if item["recipe"]["role"] == "IDLE"
                or is_release_audible_peak_dbfs(
                    item["integrity"].peak_dbfs + family_attenuation_db
                )
            ]
        )
        if len(retained) == len(rendered):
            break
        removed = {item["wavPath"] for item in rendered} - {
            item["wavPath"] for item in retained
        }
        for path in removed:
            path.unlink(missing_ok=True)
        rendered = retained
    default_mix_peak_dbfs = mix_bound_before + family_attenuation_db
    tracks: list[dict[str, Any]] = []
    seen_semantic_pcm: dict[tuple[object, ...], int] = {}
    physical_media_by_pcm: dict[str, dict[str, Any]] = {}
    for item in rendered:
        recipe = item["recipe"]
        wav_path = item["wavPath"]
        flac_path = audio_directory / f"{recipe['id']}.flac"
        pcm = apply_gain_pcm16_stereo_wav(wav_path, family_attenuation_db)
        semantic_key = (
            pcm.sha256,
            recipe["role"],
            recipe["rootRpm"],
            item["loopStart"],
            item["loopEnd"],
            canonical_json_bytes(recipe["rpmCurve"]),
            canonical_json_bytes(recipe["gainCurve"]),
            recipe.get("pitchMode"),
            canonical_json_bytes(recipe.get("pitchCurve", [])),
            recipe.get("pitchCurveInterpolation"),
        )
        if plan["schemaVersion"] == 1 and semantic_key in seen_semantic_pcm:
            existing = tracks[seen_semantic_pcm[semantic_key]]
            existing["triggers"] = sorted(
                set(existing["triggers"]) | set(recipe["triggers"])
            )
            wav_path.unlink(missing_ok=True)
            flac_path.unlink(missing_ok=True)
            continue
        physical_media = physical_media_by_pcm.get(pcm.sha256)
        if physical_media is None:
            codec.encode_level8(wav_path, flac_path)
            physical_media = {
                "path": f"audio/{recipe['id']}.flac",
                "flacSha256": sha256_file(flac_path),
                "frameCount": pcm.frame_count,
                "sampleRate": pcm.sample_rate,
                "channels": pcm.channels,
                "bitsPerSample": pcm.bits_per_sample,
            }
            physical_media_by_pcm[pcm.sha256] = physical_media
        else:
            if (
                physical_media["frameCount"] != pcm.frame_count
                or physical_media["sampleRate"] != pcm.sample_rate
                or physical_media["channels"] != pcm.channels
                or physical_media["bitsPerSample"] != pcm.bits_per_sample
            ):
                raise ValueError("PCM hash collision changed physical media identity")
            # A previous interrupted attempt may have left a recipe-named FLAC
            # that is no longer referenced after content-addressed sharing.
            flac_path.unlink(missing_ok=True)
        tracks.append(
            {
                "id": recipe["id"],
                "role": recipe["role"],
                "path": physical_media["path"],
                "flacSha256": physical_media["flacSha256"],
                "pcmSha256": pcm.sha256,
                "frameCount": physical_media["frameCount"],
                "sampleRate": physical_media["sampleRate"],
                "channels": physical_media["channels"],
                "bitsPerSample": physical_media["bitsPerSample"],
                "rootRpm": recipe["rootRpm"],
                "loopStartFrame": item["loopStart"],
                "loopEndFrame": item["loopEnd"],
                "gainDb": 0.0,
                "peakDbfs": round(pcm.peak_dbfs, 6),
                "rpmCurve": recipe["rpmCurve"],
                "gainCurve": recipe["gainCurve"],
                "triggers": recipe["triggers"],
                **(
                    {
                        "softwareChannelPriority": recipe[
                            "softwareChannelPriority"
                        ],
                        "pitchMode": recipe["pitchMode"],
                        "pitchCurve": recipe["pitchCurve"],
                        "pitchCurveInterpolation": recipe[
                            "pitchCurveInterpolation"
                        ],
                    }
                    if plan["schemaVersion"] == 2
                    else {}
                ),
            }
        )
        seen_semantic_pcm[semantic_key] = len(tracks) - 1
        wav_path.unlink(missing_ok=True)

    assets: list[dict[str, Any]] = []
    for car_id in family["memberIds"]:
        car = cars_by_id[car_id]
        if not car.get("previewSource"):
            continue
        source = root / car["previewSource"]
        target = workspace / car["previewPath"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if sha256_file(target) != car["previewSha256"]:
            raise ValueError(f"preview integrity failed for {car_id}")
        assets.append(
            {
                "path": car["previewPath"],
                "sha256": car["previewSha256"],
                "mediaType": car["previewMediaType"],
            }
        )

    planned_programs = json.loads(
        json.dumps(planned.get("oneShotPrograms", []))
    )
    if plan["schemaVersion"] == 2 and silent_recipe_ids:
        retained_programs: list[dict[str, Any]] = []
        for program in planned_programs:
            track_ids = {
                str(node["trackId"])
                for node in program["nodes"]
                if node.get("kind") == "TRACK"
            }
            omitted = track_ids & silent_recipe_ids
            if omitted:
                trigger = program.get("trigger")
                if trigger == "TURBO_EVENT":
                    recipes_by_id = {
                        str(item["id"]): item for item in planned["recipes"]
                    }
                    transformed_nodes: list[dict[str, Any]] = []
                    for node in program["nodes"]:
                        if (
                            node.get("kind") != "TRACK"
                            or str(node.get("trackId")) not in omitted
                        ):
                            transformed_nodes.append(node)
                            continue
                        recipe = recipes_by_id[str(node["trackId"])]
                        verification_sha = node.get(
                            "sourceVerificationPayloadSha256"
                        )
                        if (
                            recipe.get("sourceProjection")
                            != "CERTIFIED_TURBO_TRANSIENT"
                            or recipe.get("role")
                            not in {"BOV", "TURBO_TRANSIENT"}
                            or not isinstance(verification_sha, str)
                            or not re.fullmatch(r"[0-9a-f]{64}", verification_sha)
                        ):
                            raise ValueError(
                                "silent turbo leaf lacks its exact source-bound certificate"
                            )
                        transformed_nodes.append(
                            {
                                "id": node["id"],
                                "kind": "SILENT_SOURCE",
                                "triggerChance": node["triggerChance"],
                                "sourceGuid": recipe["sourceGuid"],
                                "resolvedRole": recipe["role"],
                                "sourceVerificationPayloadSha256": (
                                    verification_sha
                                ),
                            }
                        )
                    program["nodes"] = transformed_nodes
                    retained_programs.append(program)
                    continue
                if trigger == "ENGINE_START" and omitted == track_ids:
                    continue
                if (
                    trigger not in {"LIMITER_EVENT", "SHIFT_UP", "SHIFT_DOWN"}
                    or omitted != track_ids
                    or len(track_ids) != 1
                ):
                    raise ValueError(
                        "certified silence may omit only a complete deterministic "
                        "one-track limiter or shift program"
                    )
                omitted_recipe = next(
                    item
                    for item in planned["recipes"]
                    if item["id"] in omitted
                )
                if trigger in {"SHIFT_UP", "SHIFT_DOWN"} and (
                    omitted_recipe.get("sourceProjection")
                    != "CERTIFIED_AUTHORED_SILENCE"
                    or omitted_recipe.get("role") != trigger
                ):
                    raise ValueError(
                        "silent shift program lacks its exact source-bound certificate"
                    )
                continue
            retained_programs.append(program)
        planned_programs = retained_programs
    one_shot_programs = (
        _finalize_one_shot_programs(planned_programs, tracks)
        if plan["schemaVersion"] == 2
        else []
    )
    car_manifests = []
    for car_id in family["memberIds"]:
        car = cars_by_id[car_id]
        engine = car["engine"]
        gearbox = car["gearbox"]
        if plan["schemaVersion"] == 2:
            engine = {
                **engine,
                "turboPhysics": _turbo_physics_metadata(root, car),
                "throttleMap": _throttle_map_metadata(root, car),
            }
            gearbox = {
                **gearbox,
                "engineGasAssist": _engine_gas_assist_metadata(root, car),
            }
        car_manifests.append(
            {
                "id": car["id"],
                "name": car["name"],
                "brand": car["brand"],
                "previewPath": car["previewPath"],
                "engine": engine,
                "gearbox": gearbox,
                **(
                    {
                        "oneShotTriggerPolicies": _one_shot_trigger_policies(
                            root, car, one_shot_programs
                        )
                    }
                    if plan["schemaVersion"] == 2
                    else {}
                ),
            }
        )
    quirks = sorted(
        {quirk for car_id in family["memberIds"] for quirk in cars_by_id[car_id]["quirks"]}
    )
    roles = [track["role"] for track in tracks]
    actual_effects = derive_effect_capabilities(roles)
    manifest = {
        "schemaVersion": 2 if plan["schemaVersion"] == 2 else 1,
        "familyId": family["id"],
        "displayName": representative["name"],
        "memberCarIds": family["memberIds"],
        "audioFormat": {
            "codec": "FLAC",
            "sampleRate": 48000,
            "channels": 2,
            "bitsPerSample": 16,
        },
        "cars": car_manifests,
        "effects": actual_effects,
        "quirks": quirks,
        "tracks": tracks,
        "assets": assets,
        "fidelity": plan["fidelity"],
        "provenance": {
            "source": "installedKunosAssettoCorsa1164",
            "sourceBankSha256": family["sourceBankSha256"],
            "catalogSha256": catalog["catalogSha256"],
            "capturePlanSha256": __import__("hashlib").sha256(
                canonical_json_bytes(plan)
            ).hexdigest(),
            "referenceRenderer": "FMODStudio10812WavWriterNrt",
            "familyAttenuationDb": round(family_attenuation_db, 6),
            "defaultMixPeakDbfs": round(default_mix_peak_dbfs, 6),
            "encoder": codec.provenance,
            **(
                {
                    "authoredDsp": (
                        [
                            {
                                "name": "FMOD Gain",
                                "version": 65536,
                                "parameters": {"gainDb": -0.5, "invert": False},
                                "treatment": "BAKED_INTO_TARGET_ONLY_CAPTURE",
                                "evidence": "FMOD108_SET_PARAMETER_CALLBACK",
                            }
                        ]
                        if any(
                            "requiresBmwM3E30GraAdditionalDsp"
                            in cars_by_id[car_id]["quirks"]
                            for car_id in family["memberIds"]
                        )
                        else []
                    ),
                    "certifiedSilentSources": sorted(
                        certified_silent_sources,
                        key=lambda item: (item["role"], item["sourceGuid"]),
                    ),
                    "softwareChannelPriorityOracleSha256": plan[
                        "priorityOracle"
                    ]["proofSha256"],
                }
                if plan["schemaVersion"] == 2
                else {}
            ),
        },
    }
    if plan["schemaVersion"] == 2:
        manifest["oneShotPrograms"] = one_shot_programs
    validate_manifest(manifest)
    _write_json_atomic(workspace / "manifest.json", manifest)
    pack = output_root / "packs" / f"{family['id']}.aclib"
    build_aclib(manifest, workspace, pack)
    validate_aclib(pack, codec=codec)
    return pack


def _command_catalog(args: argparse.Namespace) -> int:
    root = find_assetto_root(args.assetto_root)
    if args.skip_event_probe:
        catalog = build_official_catalog(
            root, event_probe=None, strict_complete=not args.allow_partial
        )
    else:
        with SilentFmodBankProbe(root) as probe:
            catalog = build_official_catalog(
                root, event_probe=probe.probe, strict_complete=not args.allow_partial
            )
    output = Path(args.output).resolve()
    _write_json_atomic(output, catalog)
    copied = _copy_catalog_previews(catalog, root, output.parent) if args.copy_previews else 0
    plan = build_capture_plan(catalog)
    plan_path = Path(args.capture_plan).resolve()
    _write_json_atomic(plan_path, plan)
    print(
        f"catalog={output} cars={len(catalog['cars'])} "
        f"families={len(catalog['soundFamilies'])} previews={copied}"
    )
    print(f"capturePlan={plan_path}")
    return 0


def _command_bootstrap(args: argparse.Namespace) -> int:
    executable = bootstrap_pinned_flac(Path(args.tool_cache))
    print(executable)
    return 0


def _command_compile_family(args: argparse.Namespace) -> int:
    root = find_assetto_root(args.assetto_root)
    catalog = _read_json(Path(args.catalog))
    validate_catalog(catalog, require_complete=not args.allow_partial)
    plan_path = Path(
        args.capture_plan
        or (DEFAULT_RELEASE_PLAN if args.release else DEFAULT_LOCAL_ROOT / "capture-plan-v1.json")
    )
    plan = _read_json(plan_path)
    validate_capture_plan(plan, catalog, require_renderable=True)
    if args.release:
        fidelity = plan["fidelity"]
        if (
            fidelity["layerIsolation"] != "sourceInstrument"
            or fidelity["rpmGainCurve"] != "authoredSourceInstrument"
        ):
            raise ValueError(
                "capture plan does not satisfy source-role/authored-curve release fidelity"
            )
    flac = bootstrap_pinned_flac(Path(args.tool_cache))
    pack = _compile_family(
        root,
        catalog,
        plan,
        args.family,
        Path(
            args.output_root
            or (DEFAULT_RELEASE_OUTPUT_ROOT if args.release else DEFAULT_LOCAL_ROOT)
        ).resolve(),
        PinnedFlacCodec(flac),
        graph_root=(
            Path(args.graph_root).resolve()
            if plan["schemaVersion"] == 2
            else None
        ),
        limiter_proof_path=Path(args.limiter_proof).resolve(),
        shift_silence_proof_path=Path(args.shift_silence_proof).resolve(),
        turbo_transient_proof_path=Path(args.turbo_transient_proof).resolve(),
        priority_proof_path=Path(args.priority_proof).resolve(),
        continuous_disposition_proof_path=Path(
            args.continuous_disposition_proof
        ).resolve(),
        property_one_proof_path=Path(args.property_one_proof).resolve(),
    )
    if args.release:
        validate_release_manifest(validate_aclib(pack, codec=PinnedFlacCodec(flac)))
    print(pack)
    return 0


def _command_release_plan(args: argparse.Namespace) -> int:
    catalog = _read_json(Path(args.catalog))
    validate_catalog(catalog, require_complete=True)
    plan = build_release_capture_plan(
        catalog,
        Path(args.graph_root),
        assetto_root=(find_assetto_root(args.assetto_root) if args.assetto_root else None),
        empirical_cache_root=Path(args.empirical_cache).resolve(),
        limiter_proof_path=Path(args.limiter_proof).resolve(),
        shift_silence_proof_path=Path(args.shift_silence_proof).resolve(),
        turbo_transient_proof_path=Path(args.turbo_transient_proof).resolve(),
        priority_proof_path=Path(args.priority_proof).resolve(),
        continuous_disposition_proof_path=Path(
            args.continuous_disposition_proof
        ).resolve(),
        property_one_proof_path=Path(args.property_one_proof).resolve(),
    )
    output = Path(args.output).resolve()
    _write_json_atomic(output, plan)
    omissions = compile_all_omission_report(plan)
    omission_path = Path(args.omission_report).resolve()
    _write_json_atomic(omission_path, omissions)
    hybrid_audit_path = Path(args.hybrid_audio_audit).resolve()
    hybrid_audit = build_hybrid_audio_control_audit(
        catalog, Path(args.graph_root)
    )
    _write_json_atomic(hybrid_audit_path, hybrid_audit)
    print(
        f"releasePlan={output} families={len(plan['families'])} "
        f"recipes={sum(len(item['recipes']) for item in plan['families'])}"
    )
    print(
        f"omissionReport={omission_path} counts="
        f"{json.dumps(omissions['counts'], sort_keys=True, separators=(',', ':'))}"
    )
    print(
        f"hybridAudioAudit={hybrid_audit_path} "
        f"families={hybrid_audit['familyCount']} "
        "hybridSpecificControls=0"
    )
    return 0


def _new_compile_all_status(
    plan: dict[str, Any],
    plan_sha256: str,
    selected_families: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    selected = selected_families or list(plan["families"])
    return {
        "schema": "aclib-compile-all-status-v1",
        "catalogSha256": plan["catalogSha256"],
        "capturePlanSha256": plan_sha256,
        "createdUnixMs": round(time.time() * 1000),
        "updatedUnixMs": round(time.time() * 1000),
        "families": {
            family["familyId"]: {
                "representativeCarId": family["representativeCarId"],
                "status": "pending",
                "attempts": 0,
                "packPath": None,
                "packSha256": None,
                "elapsedSeconds": None,
                "errorType": None,
                "error": None,
            }
            for family in selected
        },
        "summary": {
            "total": len(selected),
            "pending": len(selected),
            "running": 0,
            "succeeded": 0,
            "failed": 0,
        },
    }


def _refresh_compile_all_summary(status: dict[str, Any]) -> None:
    counts = {name: 0 for name in ("pending", "running", "succeeded", "failed")}
    for family in status["families"].values():
        state = str(family["status"])
        if state not in counts:
            raise ValueError(f"unknown compile-all state {state!r}")
        counts[state] += 1
    status["summary"] = {"total": len(status["families"]), **counts}
    status["updatedUnixMs"] = round(time.time() * 1000)


def _load_or_create_compile_all_status(
    status_path: Path,
    plan: dict[str, Any],
    plan_sha256: str,
    *,
    reset: bool,
    selected_families: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    selected = selected_families or list(plan["families"])
    if reset or not status_path.is_file():
        status = _new_compile_all_status(plan, plan_sha256, selected)
        _write_json_atomic(status_path, status)
        return status
    status = _read_json(status_path)
    if (
        status.get("schema") != "aclib-compile-all-status-v1"
        or status.get("catalogSha256") != plan["catalogSha256"]
        or status.get("capturePlanSha256") != plan_sha256
        or set(status.get("families", {}))
        != {family["familyId"] for family in selected}
    ):
        raise ValueError(
            "compile-all status belongs to another plan; pass --reset-status explicitly"
        )
    for family in status["families"].values():
        if family.get("status") == "running":
            family["status"] = "pending"
            family["errorType"] = "InterruptedPreviousRun"
            family["error"] = "Previous process ended before the atomic family result."
    _refresh_compile_all_summary(status)
    _write_json_atomic(status_path, status)
    return status


class FreshFamilyProcessError(RuntimeError):
    """Preserve a clean compile-family child's typed failure in the ledger."""

    def __init__(self, child_error_type: str, child_error: str):
        super().__init__(child_error)
        self.child_error_type = child_error_type
        self.child_error = child_error


def compile_family_child_command(
    args: argparse.Namespace,
    assetto_root: Path,
    family_id: str,
    plan_path: Path,
    output_root: Path,
) -> list[str]:
    """Return the exact fresh-process command used by a compile-all shard."""

    return [
        sys.executable,
        str(Path(__file__).resolve()),
        "compile-family",
        family_id,
        "--assetto-root",
        str(assetto_root.resolve()),
        "--catalog",
        str(Path(args.catalog).resolve()),
        "--capture-plan",
        str(plan_path.resolve()),
        "--output-root",
        str(output_root.resolve()),
        "--graph-root",
        str(Path(args.graph_root).resolve()),
        "--limiter-proof",
        str(Path(args.limiter_proof).resolve()),
        "--shift-silence-proof",
        str(Path(args.shift_silence_proof).resolve()),
        "--turbo-transient-proof",
        str(Path(args.turbo_transient_proof).resolve()),
        "--priority-proof",
        str(Path(args.priority_proof).resolve()),
        "--continuous-disposition-proof",
        str(Path(args.continuous_disposition_proof).resolve()),
        "--property-one-proof",
        str(Path(args.property_one_proof).resolve()),
        "--tool-cache",
        str(Path(args.tool_cache).resolve()),
        "--release",
    ]


def _compile_family_in_fresh_process(
    args: argparse.Namespace,
    assetto_root: Path,
    family_id: str,
    plan_path: Path,
    output_root: Path,
) -> Path:
    """Compile one family in a new interpreter and verify its atomic result."""

    expected_pack = (output_root / "packs" / f"{family_id}.aclib").resolve()
    command = compile_family_child_command(
        args, assetto_root, family_id, plan_path, output_root
    )
    environment = dict(os.environ)
    environment["PYTHONHASHSEED"] = "0"
    environment["PYTHONUNBUFFERED"] = "1"
    try:
        completed = subprocess.run(
            command,
            cwd=str(PROJECT_ROOT),
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=6 * 60 * 60,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise FreshFamilyProcessError(
            "FamilyProcessTimeout", "fresh family compiler exceeded six hours"
        ) from exc
    stdout_lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    stderr = completed.stderr.strip()
    if completed.returncode != 0:
        match = re.search(
            r"compiler failed: ([A-Za-z_][A-Za-z0-9_]*): ([\s\S]+)$", stderr
        )
        if match:
            raise FreshFamilyProcessError(match.group(1), match.group(2).strip())
        detail = stderr or "\n".join(stdout_lines) or "child exited without diagnostics"
        raise FreshFamilyProcessError(
            "FamilyProcessError", detail[-4000:]
        )
    if stderr or stdout_lines != [str(expected_pack)] or not expected_pack.is_file():
        raise FreshFamilyProcessError(
            "FamilyProcessProtocolError",
            "fresh family compiler returned an unexpected pack/result protocol",
        )
    validate_release_manifest(validate_aclib(expected_pack))
    return expected_pack


def _command_compile_all(args: argparse.Namespace) -> int:
    root = find_assetto_root(args.assetto_root)
    catalog = _read_json(Path(args.catalog))
    validate_catalog(catalog, require_complete=True)
    plan_path = Path(args.capture_plan).resolve()
    plan = _read_json(plan_path)
    validate_release_capture_plan(plan, catalog, require_renderable=True)
    plan_sha256 = __import__("hashlib").sha256(canonical_json_bytes(plan)).hexdigest()
    output_root = Path(args.output_root).resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    status_path = (
        Path(args.status).resolve()
        if args.status
        else output_root / "compile-all-status-v1.json"
    )
    omission_path = (
        Path(args.omission_report).resolve()
        if args.omission_report
        else output_root / "compile-all-omissions-v1.json"
    )
    _write_json_atomic(omission_path, compile_all_omission_report(plan))
    requested = args.family or []
    selected = (
        [_resolve_family(plan["families"], selector) for selector in requested]
        if requested
        else list(plan["families"])
    )
    if len({family["familyId"] for family in selected}) != len(selected):
        raise ValueError("compile-all family selectors contain duplicates")
    status = _load_or_create_compile_all_status(
        status_path,
        plan,
        plan_sha256,
        reset=args.reset_status,
        selected_families=selected,
    )
    failures = 0
    for index, family in enumerate(selected, 1):
        family_id = family["familyId"]
        entry = status["families"][family_id]
        if entry["status"] == "succeeded" and entry.get("packPath"):
            cached_pack = Path(entry["packPath"])
            if (
                cached_pack.is_file()
                and sha256_file(cached_pack) == entry.get("packSha256")
            ):
                validate_release_manifest(validate_aclib(cached_pack))
                print(f"[{index}/{len(selected)}] reuse {family_id}")
                continue
            entry["status"] = "pending"
            entry["errorType"] = "CachedPackInvalid"
            entry["error"] = "Successful status pack is absent or changed."
        entry.update(
            {
                "status": "running",
                "attempts": int(entry.get("attempts", 0)) + 1,
                "packPath": None,
                "packSha256": None,
                "elapsedSeconds": None,
                "errorType": None,
                "error": None,
            }
        )
        _refresh_compile_all_summary(status)
        _write_json_atomic(status_path, status)
        print(
            f"[{index}/{len(selected)}] compile {family_id} "
            f"car={family['representativeCarId']}"
        )
        started = time.perf_counter()
        try:
            pack = _compile_family_in_fresh_process(
                args,
                root,
                family_id,
                plan_path,
                output_root,
            )
            entry.update(
                {
                    "status": "succeeded",
                    "packPath": str(pack),
                    "packSha256": sha256_file(pack),
                    "elapsedSeconds": round(time.perf_counter() - started, 6),
                }
            )
        except Exception as exc:
            failures += 1
            error_type = getattr(exc, "child_error_type", type(exc).__name__)
            error_text = getattr(exc, "child_error", str(exc))
            entry.update(
                {
                    "status": "failed",
                    "elapsedSeconds": round(time.perf_counter() - started, 6),
                    "errorType": error_type,
                    "error": error_text,
                }
            )
        _refresh_compile_all_summary(status)
        _write_json_atomic(status_path, status)
        if entry["status"] == "failed":
            print(
                f"[{index}/{len(selected)}] failed {family_id}: "
                f"{entry['errorType']}: {entry['error']}",
                file=sys.stderr,
            )
            if args.fail_fast:
                break
        else:
            print(
                f"[{index}/{len(selected)}] succeeded {family_id} "
                f"seconds={entry['elapsedSeconds']}"
            )
    _refresh_compile_all_summary(status)
    _write_json_atomic(status_path, status)
    print(
        f"status={status_path} summary="
        f"{json.dumps(status['summary'], sort_keys=True, separators=(',', ':'))}"
    )
    return 1 if failures else 0


def _command_validate(args: argparse.Namespace) -> int:
    codec = None
    if args.decode:
        codec = PinnedFlacCodec(bootstrap_pinned_flac(Path(args.tool_cache)))
    manifest = validate_aclib(Path(args.pack), codec=codec)
    if args.release:
        validate_release_manifest(manifest)
    print(
        f"valid family={manifest['familyId']} cars={len(manifest['memberCarIds'])} "
        f"tracks={len(manifest['tracks'])}"
    )
    return 0


def _command_audit_huracan(args: argparse.Namespace) -> int:
    report = audit_huracan_loop_sources(Path(args.sample_root))
    output = Path(args.output).resolve()
    _write_json_atomic(output, report)
    print(f"report={output} tracks={len(report['tracks'])}")
    for track in report["tracks"]:
        loop = track["selectedExclusiveLoop"]
        print(
            f"{track['id']}: loop=[{loop['startFrame']},{loop['endFrame']}) "
            f"seam={loop['seamPeakDbfs']:.2f}dBFS "
            f"sourcePeak={track['sourcePeakDbfs']:.2f}dBFS "
            f"railSamples={track['sourceRailSampleCount']}"
        )
    return 0


def _command_audit_sdk(args: argparse.Namespace) -> int:
    root = find_assetto_root(args.assetto_root)
    report = audit_shipped_fmod_authoring(root)
    output = Path(args.output).resolve()
    _write_json_atomic(output, report)
    findings = report["findings"]
    print(
        f"report={output} events={findings['engineEvents']} "
        f"allowed={findings['allowedSourceInstruments']} "
        f"excluded={findings['excludedLoadSourceInstruments']}"
    )
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    bootstrap = subparsers.add_parser("bootstrap-tools", help="install verified FLAC locally")
    bootstrap.add_argument("--tool-cache", default=str(DEFAULT_TOOL_ROOT))
    bootstrap.set_defaults(function=_command_bootstrap)

    catalog = subparsers.add_parser("catalog", help="inventory the complete official catalog")
    catalog.add_argument("--assetto-root")
    catalog.add_argument("--output", default=str(DEFAULT_LOCAL_ROOT / "catalog-v1.json"))
    catalog.add_argument(
        "--capture-plan", default=str(DEFAULT_LOCAL_ROOT / "capture-plan-v1.json")
    )
    catalog.add_argument("--copy-previews", action="store_true")
    catalog.add_argument("--skip-event-probe", action="store_true")
    catalog.add_argument("--allow-partial", action="store_true")
    catalog.set_defaults(function=_command_catalog)

    release_plan = subparsers.add_parser(
        "release-plan",
        help="build a filename-independent source-instrument plan from complete v3 graphs",
    )
    release_plan.add_argument(
        "--catalog", default=str(DEFAULT_LOCAL_ROOT / "catalog-v1.json")
    )
    release_plan.add_argument("--assetto-root")
    release_plan.add_argument("--graph-root", default=str(DEFAULT_GRAPH_ROOT))
    release_plan.add_argument(
        "--limiter-proof", default=str(DEFAULT_LIMITER_ORACLE_PROOF)
    )
    release_plan.add_argument(
        "--shift-silence-proof",
        default=str(DEFAULT_SHIFT_SILENCE_ORACLE_PROOF),
    )
    release_plan.add_argument(
        "--turbo-transient-proof",
        default=str(DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF),
    )
    release_plan.add_argument(
        "--priority-proof", default=str(DEFAULT_PRIORITY_ORACLE_PROOF)
    )
    release_plan.add_argument(
        "--continuous-disposition-proof",
        default=str(DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF),
    )
    release_plan.add_argument(
        "--property-one-proof", default=str(DEFAULT_PROPERTY_ONE_ORACLE_PROOF)
    )
    release_plan.add_argument(
        "--empirical-cache",
        default=str(DEFAULT_RELEASE_OUTPUT_ROOT / "empirical-curves-v1"),
    )
    release_plan.add_argument("--output", default=str(DEFAULT_RELEASE_PLAN))
    release_plan.add_argument(
        "--omission-report",
        default=str(DEFAULT_RELEASE_OUTPUT_ROOT / "compile-all-omissions-v1.json"),
    )
    release_plan.add_argument(
        "--hybrid-audio-audit",
        default=str(DEFAULT_RELEASE_OUTPUT_ROOT / "hybrid-audio-control-audit-v1.json"),
    )
    release_plan.set_defaults(function=_command_release_plan)

    compile_family = subparsers.add_parser(
        "compile-family", help="render, repair, encode, package, and verify one family"
    )
    compile_family.add_argument("family", help="full family SHA-256 or unique prefix")
    compile_family.add_argument("--assetto-root")
    compile_family.add_argument("--catalog", default=str(DEFAULT_LOCAL_ROOT / "catalog-v1.json"))
    compile_family.add_argument("--capture-plan")
    compile_family.add_argument("--output-root")
    compile_family.add_argument("--graph-root", default=str(DEFAULT_GRAPH_ROOT))
    compile_family.add_argument(
        "--limiter-proof", default=str(DEFAULT_LIMITER_ORACLE_PROOF)
    )
    compile_family.add_argument(
        "--shift-silence-proof",
        default=str(DEFAULT_SHIFT_SILENCE_ORACLE_PROOF),
    )
    compile_family.add_argument(
        "--turbo-transient-proof",
        default=str(DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF),
    )
    compile_family.add_argument(
        "--priority-proof", default=str(DEFAULT_PRIORITY_ORACLE_PROOF)
    )
    compile_family.add_argument(
        "--continuous-disposition-proof",
        default=str(DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF),
    )
    compile_family.add_argument(
        "--property-one-proof", default=str(DEFAULT_PROPERTY_ONE_ORACLE_PROOF)
    )
    compile_family.add_argument("--tool-cache", default=str(DEFAULT_TOOL_ROOT))
    compile_family.add_argument("--allow-partial", action="store_true")
    compile_family.add_argument(
        "--release", action="store_true", help="fail unless exact source-role fidelity is available"
    )
    compile_family.set_defaults(function=_command_compile_family)

    compile_all = subparsers.add_parser(
        "compile-all",
        help="resume release rendering with atomic status after every sound family",
    )
    compile_all.add_argument("--assetto-root")
    compile_all.add_argument(
        "--catalog", default=str(DEFAULT_LOCAL_ROOT / "catalog-v1.json")
    )
    compile_all.add_argument("--capture-plan", default=str(DEFAULT_RELEASE_PLAN))
    compile_all.add_argument("--graph-root", default=str(DEFAULT_GRAPH_ROOT))
    compile_all.add_argument(
        "--limiter-proof", default=str(DEFAULT_LIMITER_ORACLE_PROOF)
    )
    compile_all.add_argument(
        "--shift-silence-proof",
        default=str(DEFAULT_SHIFT_SILENCE_ORACLE_PROOF),
    )
    compile_all.add_argument(
        "--turbo-transient-proof",
        default=str(DEFAULT_TURBO_TRANSIENT_ORACLE_PROOF),
    )
    compile_all.add_argument(
        "--priority-proof", default=str(DEFAULT_PRIORITY_ORACLE_PROOF)
    )
    compile_all.add_argument(
        "--continuous-disposition-proof",
        default=str(DEFAULT_CONTINUOUS_DISPOSITION_ORACLE_PROOF),
    )
    compile_all.add_argument(
        "--property-one-proof", default=str(DEFAULT_PROPERTY_ONE_ORACLE_PROOF)
    )
    compile_all.add_argument("--output-root", default=str(DEFAULT_RELEASE_OUTPUT_ROOT))
    compile_all.add_argument("--tool-cache", default=str(DEFAULT_TOOL_ROOT))
    compile_all.add_argument("--status")
    compile_all.add_argument("--omission-report")
    compile_all.add_argument(
        "--family",
        action="append",
        help="compile one unique family prefix (repeatable); default is every family",
    )
    compile_all.add_argument("--fail-fast", action="store_true")
    compile_all.add_argument(
        "--reset-status",
        action="store_true",
        help="start a new status ledger without deleting any existing packs",
    )
    compile_all.set_defaults(function=_command_compile_all)

    validate = subparsers.add_parser("validate-pack", help="validate an atomic .aclib pack")
    validate.add_argument("pack")
    validate.add_argument("--decode", action="store_true")
    validate.add_argument(
        "--release", action="store_true", help="also require exact source-role fidelity"
    )
    validate.add_argument("--tool-cache", default=str(DEFAULT_TOOL_ROOT))
    validate.set_defaults(function=_command_validate)

    huracan = subparsers.add_parser(
        "audit-huracan-loops",
        help="measure the mandatory c1/c3/limiter seams without modifying sources",
    )
    huracan.add_argument("--sample-root", default=str(DEFAULT_HURACAN_SOURCE_ROOT))
    huracan.add_argument(
        "--output", default=str(DEFAULT_LOCAL_ROOT / "huracan-loop-regression-v1.json")
    )
    huracan.set_defaults(function=_command_audit_huracan)

    sdk = subparsers.add_parser(
        "audit-sdk-authoring",
        help="inventory exact source roles and curves in AC's shipped FMOD SDK project",
    )
    sdk.add_argument("--assetto-root")
    sdk.add_argument(
        "--output", default=str(DEFAULT_LOCAL_ROOT / "fmod-sdk-authoring-audit-v1.json")
    )
    sdk.set_defaults(function=_command_audit_sdk)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return int(args.function(args))
    except Exception as exc:
        print(f"compiler failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
