"""Prove temporary-bank waveform isolation against AC's FMOD 1.08.12 oracle.

This is deliberately a Tatuus SDK identity-oracle regression.  The shipped
authoring XML supplies semantic LOAD versus COAST/EXHAUST GUID sets; embedded
sample names are used only to join a parsed waveform GUID to FMOD's runtime
``SOUND_PLAYED`` callback.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import time
import wave
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.aclib_catalog import canonical_json_bytes
from sim.assetto import find_assetto_root
from sim.fmod_bank_isolation import create_isolated_bank_copy
from sim.fmod_graph_roles import (
    ROLE_ENGINE_FALLING,
    ROLE_EXCLUDED_LOAD,
    classify_bank_graph_sources,
)
from sim.fmod_renderer import SilentFmodReferenceRenderer
from sim.fmod_sdk_audit import audit_shipped_fmod_authoring
from tools.audit_fmod_bank_graph import audit_bank_graph, build_static_auditor


SCHEMA = "ac-fmod-source-isolation-proof-v1"
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / ".aclib-local" / "fmod-source-isolation-v1"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def _write_canonical(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(canonical_json_bytes(value) + b"\n")
    temporary.replace(path)


def _pcm_metrics(path: Path) -> dict[str, Any]:
    with wave.open(str(path), "rb") as source:
        if (source.getframerate(), source.getnchannels(), source.getsampwidth()) != (
            48000,
            2,
            2,
        ):
            raise AssertionError(f"non-canonical oracle WAV: {path}")
        frames = source.getnframes()
        payload = source.readframes(frames)
    samples = struct.unpack(f"<{len(payload) // 2}h", payload)
    square_sum = sum(value * value for value in samples)
    peak = max((abs(value) for value in samples), default=0)
    rms = math.sqrt(square_sum / max(1, len(samples))) / 32768.0
    return {
        "pcmSha256": hashlib.sha256(payload).hexdigest(),
        "frameCount": frames,
        "nonzeroSamples": sum(value != 0 for value in samples),
        "peakLinear": peak / 32768.0,
        "rmsLinear": rms,
    }


def _source_sets(authoring: dict[str, Any]) -> tuple[set[str], set[str]]:
    excluded = {
        _guid(instrument["id"])
        for event in authoring["events"]
        for group in event["groups"]
        if group["manifestRole"] == "EXCLUDED_LOAD"
        for instrument in group["instruments"]
    }
    allowed = {
        _guid(instrument["id"])
        for event in authoring["events"]
        for group in event["groups"]
        if group["manifestRole"] in {"COAST", "EXHAUST"}
        for instrument in group["instruments"]
    }
    return excluded, allowed


def _event_and_midpoint(
    graph: dict[str, Any], source_guid: str
) -> tuple[str, float]:
    matches: list[tuple[str, float]] = []
    for event in graph["events"]:
        for placement in event["parameterPlacements"]:
            if (
                _guid(placement.get("instrumentGuid")) == source_guid
                and str(placement.get("parameterName", "")).casefold() == "rpms"
            ):
                start = float(placement["start"])
                end = float(placement["end"])
                matches.append((event["path"].rsplit("/", 1)[-1], (start + end) / 2.0))
    if len(matches) != 1:
        raise AssertionError(
            f"expected one RPM placement for {source_guid}, got {matches}"
        )
    return matches[0]


def _render_probe(
    renderer: SilentFmodReferenceRenderer,
    bank: Path,
    event_name: str,
    rpm: float,
    output: Path,
    *,
    throttle: float = 0.5,
    frames: int = 4096,
) -> dict[str, Any]:
    rendered = renderer.render_event(
        bank,
        event_name,
        output,
        parameters={"rpms": rpm, "throttle": throttle},
        duration_frames=frames,
        warmup_frames=0,
    )
    return {
        "event": rendered.event_path,
        "rpm": rpm,
        "throttle": throttle,
        "scheduledSoundNames": list(rendered.scheduled_sound_names),
        "scheduledSoundCounts": dict(sorted(Counter(rendered.scheduled_sound_names).items())),
        "pcm": _pcm_metrics(output),
    }


def prove_tatuus_source_isolation(
    assetto_root: Path, output_root: Path
) -> dict[str, Any]:
    started = time.perf_counter()
    root = assetto_root.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    bank = root / "content" / "cars" / "tatuusfa1" / "sfx" / "tatuusfa1.bank"
    source_sha_before = _sha256(bank)

    build_started = time.perf_counter()
    build_static_auditor()
    graph = audit_bank_graph(bank, assetto_root=root, build=False)
    graph_seconds = time.perf_counter() - build_started
    classified = classify_bank_graph_sources(graph)
    authoring = audit_shipped_fmod_authoring(root)
    expected_excluded, expected_allowed = _source_sets(authoring)
    actual_excluded = {
        row["sourceGuid"]
        for row in classified["sources"]
        if row["role"] == ROLE_EXCLUDED_LOAD
    }
    actual_allowed = {
        row["sourceGuid"]
        for row in classified["sources"]
        if row["role"] == ROLE_ENGINE_FALLING
    }
    if actual_excluded != expected_excluded or len(expected_excluded) != 14:
        raise AssertionError("binary/classifier excluded GUIDs disagree with authoring")
    if actual_allowed != expected_allowed or len(expected_allowed) != 12:
        raise AssertionError("binary/classifier allowed GUIDs disagree with authoring")
    engine_guids = expected_excluded | expected_allowed
    instruments = {item["guid"]: item for item in graph["instruments"]}

    patch_started = time.perf_counter()
    excluded_path = output_root / "tatuusfa1-excluded-load-muted.bank"
    all_muted_path = output_root / "tatuusfa1-all-engine-muted.bank"
    excluded_bank = create_isolated_bank_copy(
        bank, graph, expected_excluded, excluded_path
    )
    all_muted_bank = create_isolated_bank_copy(
        bank, graph, engine_guids, all_muted_path
    )
    patch_seconds = time.perf_counter() - patch_started

    # Reparse both derivatives and load them through the runtime path oracle.
    excluded_graph = audit_bank_graph(excluded_path, assetto_root=root, build=False)
    all_muted_graph = audit_bank_graph(all_muted_path, assetto_root=root, build=False)
    excluded_after = {item["guid"]: item for item in excluded_graph["instruments"]}
    all_muted_after = {item["guid"]: item for item in all_muted_graph["instruments"]}
    for guid in engine_guids:
        excluded_chance = excluded_after[guid]["baseProperties"]["triggerChancePercent"]
        all_muted_chance = all_muted_after[guid]["baseProperties"]["triggerChancePercent"]
        if all_muted_chance != 0.0:
            raise AssertionError(f"all-muted derivative left {guid} schedulable")
        if guid in expected_excluded and excluded_chance != 0.0:
            raise AssertionError(f"excluded-only derivative left LOAD {guid} schedulable")
        if guid in expected_allowed and excluded_chance <= 0.0:
            raise AssertionError(f"excluded-only derivative changed allowed {guid}")

    renderer = SilentFmodReferenceRenderer(root)
    scratch_wav = output_root / "probe-scratch.wav"
    solo_bank_path = output_root / "tatuusfa1-solo-source.bank"
    runtime_started = time.perf_counter()
    source_evidence: list[dict[str, Any]] = []
    for guid in sorted(engine_guids):
        instrument = instruments[guid]
        sample_name = instrument["sample"]["name"]
        event_name, rpm = _event_and_midpoint(graph, guid)
        solo = create_isolated_bank_copy(
            bank, graph, engine_guids - {guid}, solo_bank_path
        )
        solo_probe = _render_probe(
            renderer, solo.output_path, event_name, rpm, scratch_wav
        )
        if solo_probe["scheduledSoundCounts"].get(sample_name, 0) != 1:
            raise AssertionError(
                f"solo runtime identity mismatch for {guid}: "
                f"{solo_probe['scheduledSoundNames']}"
            )
        if solo_probe["pcm"]["nonzeroSamples"] <= 0:
            raise AssertionError(f"solo source rendered silence: {guid}")

        policy_bank = excluded_path
        policy_probe = _render_probe(renderer, policy_bank, event_name, rpm, scratch_wav)
        policy_count = policy_probe["scheduledSoundCounts"].get(sample_name, 0)
        unmodified_probe: dict[str, Any] | None = None
        if guid in expected_allowed:
            if policy_count <= 0 or policy_probe["pcm"]["nonzeroSamples"] <= 0:
                raise AssertionError(f"allowed source is absent after LOAD isolation: {guid}")
        else:
            unmodified_probe = _render_probe(
                renderer, bank, event_name, rpm, scratch_wav
            )
            unmodified_count = unmodified_probe["scheduledSoundCounts"].get(
                sample_name, 0
            )
            if unmodified_count <= policy_count:
                raise AssertionError(
                    f"excluded-only derivative did not remove runtime identity: {guid}"
                )
            # One Tatuus LOAD GUID and one allowed GUID intentionally reuse the
            # same embedded 500ss_idle waveform.  In that case the callback
            # count falls from two to one rather than to zero.  The solo proof
            # above distinguishes the instrument identities.
            if policy_count != 0:
                same_name_allowed = {
                    candidate
                    for candidate in expected_allowed
                    if instruments[candidate]["sample"]["name"] == sample_name
                }
                if not same_name_allowed:
                    raise AssertionError(f"excluded source still scheduled: {guid}")
        source_evidence.append(
            {
                "sourceGuid": guid,
                "authoringPolicy": (
                    "EXCLUDED_LOAD" if guid in expected_excluded else "ALLOWED"
                ),
                "runtimeIdentitySampleName": sample_name,
                "event": event_name,
                "rpmProbe": rpm,
                "solo": solo_probe,
                "unmodifiedProbe": unmodified_probe,
                "policyDerivative": "excludedLoadMuted",
                "policyProbe": policy_probe,
            }
        )

    # A three-way output comparison at one overlapping external-engine point.
    representative: dict[str, Any] = {}
    for label, candidate in (
        ("unmodified", bank),
        ("excludedLoadMuted", excluded_path),
        ("allEngineMuted", all_muted_path),
    ):
        wav_path = output_root / f"representative-{label}.wav"
        representative[label] = _render_probe(
            renderer,
            candidate,
            "engine_ext",
            3600.0,
            wav_path,
            # Low throttle makes the retained falling/coast layers audible;
            # Tatuus also leaves one authored LOAD group above digital silence
            # here, so the unmodified and excluded-only PCM remain distinct.
            throttle=0.10,
            frames=24000,
        )
        representative[label]["wav"] = str(wav_path)
    if representative["allEngineMuted"]["scheduledSoundNames"]:
        raise AssertionError("all-muted representative scheduled a sound")
    if representative["allEngineMuted"]["pcm"]["nonzeroSamples"] != 0:
        raise AssertionError("all-muted representative output is not exact digital silence")
    if representative["excludedLoadMuted"]["pcm"]["nonzeroSamples"] <= 0:
        raise AssertionError("excluded-only representative lost allowed engine audio")
    if (
        representative["unmodified"]["pcm"]["pcmSha256"]
        == representative["excludedLoadMuted"]["pcm"]["pcmSha256"]
    ):
        raise AssertionError("LOAD isolation did not change representative PCM")
    runtime_seconds = time.perf_counter() - runtime_started

    scratch_wav.unlink(missing_ok=True)
    solo_bank_path.unlink(missing_ok=True)
    source_sha_after = _sha256(bank)
    if source_sha_after != source_sha_before:
        raise AssertionError("installed source bank changed")

    return {
        "schema": SCHEMA,
        "source": {
            "path": str(bank),
            "sha256Before": source_sha_before,
            "sha256After": source_sha_after,
            "unchanged": source_sha_before == source_sha_after,
        },
        "identityOracle": {
            "authoringVersion": authoring["authoringVersion"],
            "excludedLoadGuids": sorted(expected_excluded),
            "allowedCoastExhaustGuids": sorted(expected_allowed),
            "classifierExactSetMatch": True,
            "sampleNamesUsedForSemantics": False,
            "sampleNamesUsedForRuntimeIdentityJoin": True,
        },
        "derivatives": {
            "excludedLoadMuted": {
                "path": str(excluded_path),
                "sha256": excluded_bank.output_sha256,
                "patchedGuids": [patch.source_guid for patch in excluded_bank.patches],
                "differingByteCount": len(excluded_bank.differing_byte_offsets),
                "runtimeBankAccepted": True,
                "runtimeEventMappings": excluded_graph["silentRuntimeOracle"][
                    "eventGuidPathMappings"
                ],
            },
            "allEngineMuted": {
                "path": str(all_muted_path),
                "sha256": all_muted_bank.output_sha256,
                "patchedGuids": [patch.source_guid for patch in all_muted_bank.patches],
                "differingByteCount": len(all_muted_bank.differing_byte_offsets),
                "runtimeBankAccepted": True,
                "runtimeEventMappings": all_muted_graph["silentRuntimeOracle"][
                    "eventGuidPathMappings"
                ],
            },
        },
        "sourceEvidence": source_evidence,
        "representativeComparison": representative,
        "timingsSeconds": {
            "buildAuditAndClassify": graph_seconds,
            "createDerivatives": patch_seconds,
            "runtimeProof": runtime_seconds,
            "total": time.perf_counter() - started,
        },
        "result": "PASS",
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", type=Path)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument(
        "--report",
        type=Path,
        help="canonical proof JSON (default: <output-root>/proof.json)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    root = find_assetto_root(args.assetto_root)
    output_root = args.output_root.resolve()
    report = prove_tatuus_source_isolation(root, output_root)
    report_path = (args.report or output_root / "proof.json").resolve()
    _write_canonical(report_path, report)
    timings = report["timingsSeconds"]
    print(
        "PASS: Tatuus temporary-bank source isolation; "
        f"14 LOAD muted, 12 allowed retained, {timings['total']:.2f}s; "
        f"evidence={report_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
