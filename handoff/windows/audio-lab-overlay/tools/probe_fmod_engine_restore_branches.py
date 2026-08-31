"""Bound finite FMOD 1.08 zero-hold restore outcomes for one engine source.

This is a diagnostic/certification oracle, not a release-role classifier.  It
repeats byte-identical target-only requests in fresh processes, preserves one
PCM artifact per distinct outcome, and records the channel/callback state that
could predict a branch.  No sample filename participates in semantics or is
emitted in the proof.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
from pathlib import Path
import shutil
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from sim.assetto import find_assetto_root
from sim.fmod_authored_curves import derive_manifest_one_shot_curves
from tools.probe_fmod_engine_transients import (
    ALLOWED_ROLES,
    CLASSIFIER_SCHEMA,
    DEFAULT_CLASSIFICATION,
    DEFAULT_GRAPH_ROOT,
    DEFAULT_PLAN,
    GRAPH_SUMMARY_SCHEMA,
    _canonical_sha,
    _fresh_worker,
    _guid,
    _load_json,
    _pitch_probe,
    _release_source_guids,
    _sha256,
    _write_canonical,
)
from tools.probe_fmod_turbo_transients import _runtime_bank, _target


SCHEMA = "ac-fmod-engine-restore-branch-oracle-v1"


def _channel_signature(observation: dict[str, Any]) -> dict[str, Any]:
    voice = observation.get("priorVoice")
    if not isinstance(voice, dict):
        return {
            "writerFrameAfterSchedule": int(observation["writerFrameAfterSchedule"]),
            "voicePresent": False,
            "targetVoiceCount": int(observation["targetVoiceCount"]),
        }
    return {
        "writerFrameAfterSchedule": int(observation["writerFrameAfterSchedule"]),
        "voicePresent": True,
        "targetVoiceCount": int(observation["targetVoiceCount"]),
        "isVirtual": bool(voice["isVirtual"]),
        "pcmPosition": int(voice["pcmPosition"]),
        "pitch": float(voice["pitch"]),
        "audibility": float(voice["audibility"]),
        "localDspClockRelativeToWriter": int(voice["localDspClock"])
        - int(voice["writerMasterDspClock"]),
        "parentDspClockRelativeToWriter": int(voice["parentDspClock"])
        - int(voice["writerMasterDspClock"]),
    }


def _observation_signature(payload: dict[str, Any]) -> dict[str, Any]:
    holds = [
        _channel_signature(item)
        for item in payload.get("zeroHoldChannelObservations", [])
    ]
    restores = [
        _channel_signature(item)
        for item in payload.get("restoreChannelObservations", [])
    ]
    callbacks = []
    schedule = int(payload["scheduleStartDspClockFrames"])
    for item in payload.get("targetCallbacks", []):
        callbacks.append(
            {
                "kind": str(item["kind"]),
                "writerDspClockRelativeToSchedule": int(
                    item.get("writerMasterDspClock", schedule)
                )
                - schedule,
                "localDspClockRelativeToWriter": int(
                    item.get("localDspClock", 0)
                )
                - int(item.get("writerMasterDspClock", 0)),
                "parentDspClockRelativeToWriter": int(
                    item.get("parentDspClock", 0)
                )
                - int(item.get("writerMasterDspClock", 0)),
            }
        )
    return {
        "scheduleStartDspClockModulo256": schedule % 256,
        "renderedFramesThroughStoppedCallback": int(
            payload["renderedFramesThroughStoppedCallback"]
        ),
        "hold": holds,
        "restore": restores,
        "callbacks": callbacks,
    }


def probe(
    source_guid: str,
    output_root: Path,
    configurations: list[tuple[int, int, int]],
) -> dict[str, Any]:
    assetto_root = find_assetto_root().resolve(strict=True)
    graph_root = DEFAULT_GRAPH_ROOT.resolve(strict=True)
    classification_path = DEFAULT_CLASSIFICATION.resolve(strict=True)
    plan_path = DEFAULT_PLAN.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    summary = _load_json(graph_root / "summary.json", GRAPH_SUMMARY_SCHEMA)
    classification = _load_json(classification_path, CLASSIFIER_SCHEMA)
    plan = _load_json(plan_path)
    wanted = _guid(source_guid)
    release_guids = _release_source_guids(plan)
    row = next(
        (
            item
            for item in classification["sourceDecisions"]
            if _guid(item.get("sourceGuid")) == wanted
            and wanted in release_guids
            and item.get("policy") == "allowCandidate"
            and item.get("lifetime") == "oneShot"
            and item.get("role") in ALLOWED_ROLES
        ),
        None,
    )
    if row is None:
        raise ValueError("source is not a release-selected ENGINE_TRANSIENT leaf")
    families = {str(item["familyId"]): item for item in summary["families"]}
    target = _target(graph_root, families, row)
    target["_graphRoot"] = str(graph_root)
    target["_classificationPath"] = str(classification_path)
    derived = derive_manifest_one_shot_curves(target["graph"], row)
    derived_sha = _canonical_sha(derived)
    runtime_bank, isolation = _runtime_bank(
        assetto_root, target, derived, output_root
    )
    pitch = _pitch_probe(
        assetto_root, runtime_bank, target, derived, output_root
    )
    branches_dir = output_root / "branches"
    branches_dir.mkdir(parents=True, exist_ok=True)
    configuration_records = []
    all_branch_hashes: set[str] = set()
    for preroll, hold, repeats in configurations:
        if preroll < 1 or hold < 2 or repeats < 2:
            raise ValueError("alignment values must be positive and repeats >=2")
        label = f"preroll-{preroll}-hold-{hold}"
        work = output_root / "work" / "branch-renders" / label
        work.mkdir(parents=True, exist_ok=True)
        outcomes = []
        baselines = []
        for repeat in range(2):
            writer = work / f"baseline-{repeat}.wav"
            payload = _fresh_worker(
                "zeroResumeRender",
                assetto_root,
                runtime_bank,
                target,
                derived,
                output_root,
                extras={
                    "renderId": f"branch-{label}-baseline-{repeat}",
                    "writerPath": str(writer.resolve()),
                    "applyZero": False,
                    "pitchOnlyReference": False,
                    "liveRpmPitch": pitch["mode"] == "LIVE_RPM_RATIO",
                    "prerollUpdates": preroll,
                    "zeroHoldUpdates": hold,
                },
            )
            cropped = Path(str(payload.pop("croppedWavPath"))).resolve(strict=True)
            cropped_sha = str(payload.pop("croppedWavSha256"))
            if _sha256(cropped) != cropped_sha:
                raise AssertionError("baseline cropped hash differs")
            cropped.unlink(missing_ok=True)
            writer.unlink(missing_ok=True)
            baselines.append(
                {
                    "pcmWavSha256": cropped_sha,
                    "observation": _observation_signature(payload),
                    "freshProcessEvidence": payload.pop("freshProcessEvidence"),
                }
            )
        if len({item["pcmWavSha256"] for item in baselines}) != 1:
            raise AssertionError(f"baseline is not bit-exact for {label}")
        for repeat in range(repeats):
            writer = work / f"gap-{repeat}.wav"
            payload = _fresh_worker(
                "zeroResumeRender",
                assetto_root,
                runtime_bank,
                target,
                derived,
                output_root,
                extras={
                    "renderId": f"branch-{label}-gap-{repeat}",
                    "writerPath": str(writer.resolve()),
                    "applyZero": True,
                    "pitchOnlyReference": False,
                    "liveRpmPitch": pitch["mode"] == "LIVE_RPM_RATIO",
                    "prerollUpdates": preroll,
                    "zeroHoldUpdates": hold,
                },
            )
            cropped = Path(str(payload.pop("croppedWavPath"))).resolve(strict=True)
            cropped_sha = str(payload.pop("croppedWavSha256"))
            if _sha256(cropped) != cropped_sha:
                raise AssertionError("gap cropped hash differs")
            branch_path = branches_dir / f"{cropped_sha}.wav"
            if not branch_path.is_file():
                shutil.copy2(cropped, branch_path)
            if _sha256(branch_path) != cropped_sha:
                raise AssertionError("preserved branch hash differs")
            cropped.unlink(missing_ok=True)
            writer.unlink(missing_ok=True)
            all_branch_hashes.add(cropped_sha)
            outcomes.append(
                {
                    "repeatIndex": repeat,
                    "pcmWavSha256": cropped_sha,
                    "observation": _observation_signature(payload),
                    "freshProcessEvidence": payload.pop("freshProcessEvidence"),
                }
            )
            _write_canonical(
                output_root / "partial.json",
                {
                    "schema": SCHEMA,
                    "complete": False,
                    "sourceGuid": wanted,
                    "currentConfiguration": label,
                    "completedOutcomesInCurrentConfiguration": len(outcomes),
                    "distinctPcmOutcomeSha256": sorted(all_branch_hashes),
                },
            )
        counts = Counter(item["pcmWavSha256"] for item in outcomes)
        signatures_by_branch: dict[str, set[str]] = defaultdict(set)
        for item in outcomes:
            signatures_by_branch[item["pcmWavSha256"]].add(
                _canonical_sha(item["observation"])
            )
        configuration_records.append(
            {
                "prerollUpdates": preroll,
                "zeroHoldUpdates": hold,
                "freshGapProcessCount": repeats,
                "baseline": baselines,
                "branchCounts": dict(sorted(counts.items())),
                "observationSignatureSha256ByBranch": {
                    key: sorted(value)
                    for key, value in sorted(signatures_by_branch.items())
                },
                "outcomes": outcomes,
            }
        )
    result = {
        "schema": SCHEMA,
        "complete": True,
        "sourceGuid": wanted,
        "familyId": str(row["familyId"]),
        "eventPath": str(derived["eventPath"]),
        "derivedSourceSha256": derived_sha,
        "sourceBankSha256": _sha256(
            assetto_root / str(families[str(row["familyId"])]["bankPath"])
        ),
        "isolatedRuntimeBankSha256": _sha256(runtime_bank),
        "isolation": isolation,
        "renderer": {
            "runtime": "FMOD Studio API 1.08.12",
            "sampleRateHz": 48000,
            "channels": 2,
            "sampleFormat": "signedPcm16LittleEndian",
            "audioDeviceOpened": False,
            "targetOnly": True,
        },
        "pitchMode": pitch["mode"],
        "configurations": configuration_records,
        "distinctPcmOutcomeSha256": sorted(all_branch_hashes),
        "sampleNameUsedForSemantics": False,
        "probeExecutableSha256": _sha256(Path(__file__).resolve()),
        "engineProbeExecutableSha256": _sha256(
            PROJECT_ROOT / "tools" / "probe_fmod_engine_transients.py"
        ),
    }
    result["proofPayloadSha256"] = _canonical_sha(result)
    _write_canonical(output_root / "proof.json", result)
    return result


def _parse_configuration(value: str) -> tuple[int, int, int]:
    parts = value.split(",")
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("expected PREROLL,HOLD,REPEATS")
    try:
        return tuple(int(item) for item in parts)  # type: ignore[return-value]
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-guid", required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument(
        "--configuration",
        action="append",
        type=_parse_configuration,
        default=[],
        metavar="PREROLL,HOLD,REPEATS",
    )
    args = parser.parse_args()
    configurations = args.configuration or [(4, 20, 64)]
    result = probe(args.source_guid, args.output_root, configurations)
    print(
        {
            "result": "PASS_BRANCH_INVENTORY",
            "proof": str((args.output_root / "proof.json").resolve()),
            "proofSha256": _sha256((args.output_root / "proof.json").resolve()),
            "distinctPcmOutcomes": len(result["distinctPcmOutcomeSha256"]),
        }
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
