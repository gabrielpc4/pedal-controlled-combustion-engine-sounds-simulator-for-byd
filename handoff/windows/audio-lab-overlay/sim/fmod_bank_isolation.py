"""Verified, copy-only waveform-instrument isolation for AC FMOD banks.

The semantic decision about which GUIDs to suppress is deliberately external to
this module.  This module accepts exact waveform-instrument GUIDs from the graph
classifier, verifies their parser-reported binary offsets against the source
bytes, copies the bank, and changes only each selected instrument's trigger
chance to IEEE-754 ``0.0f``.  Installed Assetto Corsa files are never opened for
writing.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import shutil
import struct
import tempfile
from typing import Any, Iterable


ZERO_FLOAT32_LE = b"\x00\x00\x00\x00"


class FmodBankIsolationError(ValueError):
    """Raised when a patch cannot be proven safe and attributable."""


@dataclass(frozen=True)
class InstrumentPatch:
    source_guid: str
    trigger_chance_offset: int
    original_raw_uint32: int
    original_percent: float


@dataclass(frozen=True)
class SingleShotParentPatch:
    """Capture-only patch that makes one playlist selection finite and certain.

    AC banks encode ``LoopCount`` eight bytes after the parser-attributed
    instrument volume field.  For a target-only one-shot capture we change a
    selected Multi Instrument ancestor from an authored repeating playlist to
    one selection and make its program-level trigger chance certain.  The
    authored loop/chance topology remains in the manifest; this derivative is
    used only to obtain the selected child's PCM without a later playlist
    iteration contaminating its tail.
    """

    source_guid: str
    loop_count_offset: int
    original_loop_count: int
    trigger_chance_offset: int
    original_trigger_chance_percent: float


@dataclass(frozen=True)
class DisabledParentPatch:
    """Capture-only suppression of a program whose every leaf is muted."""

    source_guid: str
    trigger_chance_offset: int
    original_raw_uint32: int
    original_percent: float


@dataclass(frozen=True)
class IsolatedBank:
    source_path: Path
    output_path: Path
    source_sha256: str
    output_sha256: str
    source_size: int
    patches: tuple[InstrumentPatch, ...]
    disabled_parent_patches: tuple[DisabledParentPatch, ...]
    single_shot_parent_patches: tuple[SingleShotParentPatch, ...]
    differing_byte_offsets: tuple[int, ...]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _guid(value: object) -> str:
    return str(value or "").strip().strip("{}").casefold()


def fully_muted_multi_instrument_guids(
    graph_report: dict[str, Any], muted_source_guids: Iterable[str]
) -> set[str]:
    """Select outermost Multi Instruments whose every waveform is muted.

    This is a pure graph proof.  The copy-only patcher below independently
    validates the returned GUIDs and their exact source-bank bytes before it
    changes any trigger chance.
    """

    muted = {_guid(value) for value in muted_source_guids}
    muted.discard("")
    instruments = {
        _guid(item.get("guid")): item
        for item in graph_report.get("instruments", [])
        if isinstance(item, dict) and _guid(item.get("guid"))
    }

    def child_guids(item: dict[str, Any]) -> tuple[str, ...]:
        result: list[str] = []
        for child in item.get("childInstruments", []):
            guid = child.get("guid") if isinstance(child, dict) else child
            normalized = _guid(guid)
            if normalized:
                result.append(normalized)
        return tuple(result)

    parents: dict[str, set[str]] = {}
    for guid, item in instruments.items():
        for child in child_guids(item):
            parents.setdefault(child, set()).add(guid)

    memo: dict[str, frozenset[str]] = {}

    def waveform_descendants(
        guid: str, visiting: frozenset[str]
    ) -> frozenset[str]:
        if guid in memo:
            return memo[guid]
        if guid in visiting:
            raise FmodBankIsolationError(
                "instrument graph contains a child cycle"
            )
        item = instruments.get(guid)
        if item is None:
            raise FmodBankIsolationError(
                f"instrument graph references absent child {guid}"
            )
        if item.get("kind") == "WaveformInstrumentNode":
            result = frozenset((guid,))
        else:
            result = frozenset().union(
                *(
                    waveform_descendants(child, visiting | {guid})
                    for child in child_guids(item)
                )
            )
        memo[guid] = result
        return result

    candidates = {
        guid
        for guid, item in instruments.items()
        if item.get("kind") == "MultiInstrumentNode"
        and (descendants := waveform_descendants(guid, frozenset()))
        and descendants <= muted
        and float((item.get("baseProperties") or {}).get(
            "triggerChancePercent", 0.0
        ))
        > 0.0
    }

    def has_candidate_ancestor(guid: str) -> bool:
        pending = list(parents.get(guid, ()))
        seen: set[str] = set()
        while pending:
            parent = pending.pop()
            if parent in seen:
                continue
            seen.add(parent)
            if parent in candidates:
                return True
            pending.extend(parents.get(parent, ()))
        return False

    return {guid for guid in candidates if not has_candidate_ancestor(guid)}


def plan_instrument_isolation(
    bank_path: Path,
    graph_report: dict[str, Any],
    muted_source_guids: Iterable[str],
) -> tuple[InstrumentPatch, ...]:
    """Validate and return a deterministic trigger-chance patch plan."""

    source = bank_path.resolve(strict=True)
    source_sha = _sha256(source)
    bank = graph_report.get("bank")
    if not isinstance(bank, dict) or bank.get("sha256") != source_sha:
        raise FmodBankIsolationError(
            "graph report SHA-256 does not match the source bank"
        )
    requested = {_guid(value) for value in muted_source_guids}
    requested.discard("")
    if not requested:
        raise FmodBankIsolationError("at least one source GUID must be selected")

    instruments = graph_report.get("instruments")
    if not isinstance(instruments, list):
        raise FmodBankIsolationError("graph report has no instrument array")
    by_guid: dict[str, dict[str, Any]] = {}
    for item in instruments:
        if isinstance(item, dict) and _guid(item.get("guid")):
            by_guid[_guid(item["guid"])] = item
    missing = sorted(requested - by_guid.keys())
    if missing:
        raise FmodBankIsolationError(
            "selected source GUIDs are absent from the graph: " + ", ".join(missing)
        )

    source_size = source.stat().st_size
    patches: list[InstrumentPatch] = []
    with source.open("rb") as raw:
        for guid in sorted(requested):
            instrument = by_guid[guid]
            if instrument.get("kind") != "WaveformInstrumentNode":
                raise FmodBankIsolationError(
                    f"selected GUID is not a waveform instrument: {guid}"
                )
            properties = instrument.get("baseProperties")
            if not isinstance(properties, dict):
                raise FmodBankIsolationError(f"instrument has no base body: {guid}")
            try:
                offset = int(properties["triggerChancePercentFileOffset"])
                raw_uint32 = int(properties["triggerChancePercentRawUInt32"])
                percent = float(properties["triggerChancePercent"])
            except (KeyError, TypeError, ValueError) as exc:
                raise FmodBankIsolationError(
                    f"instrument lacks a parser-attributed trigger-chance offset: {guid}"
                ) from exc
            if offset < 0 or offset + 4 > source_size:
                raise FmodBankIsolationError(
                    f"trigger-chance offset is outside the source bank: {guid}@{offset}"
                )
            raw.seek(offset)
            actual = raw.read(4)
            expected = struct.pack("<I", raw_uint32)
            if actual != expected or actual != struct.pack("<f", percent):
                raise FmodBankIsolationError(
                    f"parser/source trigger-chance bytes disagree: {guid}@{offset}"
                )
            if percent <= 0.0:
                raise FmodBankIsolationError(
                    f"selected instrument is already unschedulable: {guid}"
                )
            patches.append(InstrumentPatch(guid, offset, raw_uint32, percent))

    offsets = [patch.trigger_chance_offset for patch in patches]
    if len(offsets) != len(set(offsets)):
        raise FmodBankIsolationError("selected instruments share a patch offset")
    return tuple(patches)


def plan_single_shot_parent_isolation(
    bank_path: Path,
    graph_report: dict[str, Any],
    parent_instrument_guids: Iterable[str],
) -> tuple[SingleShotParentPatch, ...]:
    """Return verified capture-only loop/chance patches for playlist parents."""

    source = bank_path.resolve(strict=True)
    source_sha = _sha256(source)
    bank = graph_report.get("bank")
    if not isinstance(bank, dict) or bank.get("sha256") != source_sha:
        raise FmodBankIsolationError(
            "graph report SHA-256 does not match the source bank"
        )
    # Every official bank in the release catalog uses the pinned AC 1.08
    # layout (file version 80).  Fail closed rather than infer offsets for a
    # different serialization.
    if int(bank.get("fileVersion", -1)) != 80:
        raise FmodBankIsolationError(
            "single-shot parent isolation requires FMOD bank file version 80"
        )
    requested = {_guid(value) for value in parent_instrument_guids}
    requested.discard("")
    if not requested:
        return ()

    instruments = graph_report.get("instruments")
    if not isinstance(instruments, list):
        raise FmodBankIsolationError("graph report has no instrument array")
    by_guid = {
        _guid(item.get("guid")): item
        for item in instruments
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    missing = sorted(requested - by_guid.keys())
    if missing:
        raise FmodBankIsolationError(
            "selected playlist parent GUIDs are absent from the graph: "
            + ", ".join(missing)
        )

    source_size = source.stat().st_size
    patches: list[SingleShotParentPatch] = []
    with source.open("rb") as raw:
        for guid in sorted(requested):
            instrument = by_guid[guid]
            if instrument.get("kind") != "MultiInstrumentNode":
                raise FmodBankIsolationError(
                    f"single-shot parent is not a Multi Instrument: {guid}"
                )
            properties = instrument.get("baseProperties")
            if not isinstance(properties, dict):
                raise FmodBankIsolationError(
                    f"single-shot parent has no base body: {guid}"
                )
            try:
                volume_offset = int(properties["volumeFileOffset"])
                loop_count = int(properties["loopCount"])
                chance_offset = int(properties["triggerChancePercentFileOffset"])
                chance_raw = int(properties["triggerChancePercentRawUInt32"])
                chance = float(properties["triggerChancePercent"])
            except (KeyError, TypeError, ValueError) as exc:
                raise FmodBankIsolationError(
                    f"single-shot parent lacks parser-attributed property offsets: {guid}"
                ) from exc
            loop_offset = volume_offset + 8
            if (
                loop_offset < 0
                or loop_offset + 4 > source_size
                or chance_offset < 0
                or chance_offset + 4 > source_size
            ):
                raise FmodBankIsolationError(
                    f"single-shot parent patch is outside the source bank: {guid}"
                )
            raw.seek(loop_offset)
            if raw.read(4) != struct.pack("<i", loop_count):
                raise FmodBankIsolationError(
                    f"parser/source loop-count bytes disagree: {guid}@{loop_offset}"
                )
            raw.seek(chance_offset)
            actual_chance = raw.read(4)
            if (
                actual_chance != struct.pack("<I", chance_raw)
                or actual_chance != struct.pack("<f", chance)
            ):
                raise FmodBankIsolationError(
                    f"parser/source trigger-chance bytes disagree: {guid}@{chance_offset}"
                )
            if loop_count >= 0:
                raise FmodBankIsolationError(
                    f"single-shot playlist parent is not authored to repeat: {guid}"
                )
            if not 0.0 < chance <= 100.0:
                raise FmodBankIsolationError(
                    f"single-shot playlist parent has invalid trigger chance: {guid}"
                )
            patches.append(
                SingleShotParentPatch(
                    guid, loop_offset, loop_count, chance_offset, chance
                )
            )

    offsets = [
        offset
        for patch in patches
        for offset in (patch.loop_count_offset, patch.trigger_chance_offset)
    ]
    if len(offsets) != len(set(offsets)):
        raise FmodBankIsolationError("single-shot parent patches share an offset")
    return tuple(patches)


def plan_disabled_parent_isolation(
    bank_path: Path,
    graph_report: dict[str, Any],
    parent_instrument_guids: Iterable[str],
) -> tuple[DisabledParentPatch, ...]:
    """Verify Multi Instrument trigger chances that may be set to zero.

    The caller must prove that every waveform descendant of each selected
    parent is already part of the muted-source set.  This function owns the
    independent byte/graph validation and refuses any other instrument kind.
    """

    source = bank_path.resolve(strict=True)
    source_sha = _sha256(source)
    bank = graph_report.get("bank")
    if not isinstance(bank, dict) or bank.get("sha256") != source_sha:
        raise FmodBankIsolationError(
            "graph report SHA-256 does not match the source bank"
        )
    requested = {_guid(value) for value in parent_instrument_guids}
    requested.discard("")
    if not requested:
        return ()
    instruments = graph_report.get("instruments")
    if not isinstance(instruments, list):
        raise FmodBankIsolationError("graph report has no instrument array")
    by_guid = {
        _guid(item.get("guid")): item
        for item in instruments
        if isinstance(item, dict) and _guid(item.get("guid"))
    }
    missing = sorted(requested - by_guid.keys())
    if missing:
        raise FmodBankIsolationError(
            "selected disabled parent GUIDs are absent from the graph: "
            + ", ".join(missing)
        )
    source_size = source.stat().st_size
    patches: list[DisabledParentPatch] = []
    with source.open("rb") as raw:
        for guid in sorted(requested):
            instrument = by_guid[guid]
            if instrument.get("kind") != "MultiInstrumentNode":
                raise FmodBankIsolationError(
                    f"disabled parent is not a Multi Instrument: {guid}"
                )
            properties = instrument.get("baseProperties")
            if not isinstance(properties, dict):
                raise FmodBankIsolationError(
                    f"disabled parent has no base body: {guid}"
                )
            try:
                offset = int(properties["triggerChancePercentFileOffset"])
                raw_uint32 = int(properties["triggerChancePercentRawUInt32"])
                percent = float(properties["triggerChancePercent"])
            except (KeyError, TypeError, ValueError) as exc:
                raise FmodBankIsolationError(
                    f"disabled parent lacks a parser-attributed trigger chance: {guid}"
                ) from exc
            if offset < 0 or offset + 4 > source_size:
                raise FmodBankIsolationError(
                    f"disabled parent patch is outside the source bank: {guid}"
                )
            raw.seek(offset)
            actual = raw.read(4)
            if (
                actual != struct.pack("<I", raw_uint32)
                or actual != struct.pack("<f", percent)
            ):
                raise FmodBankIsolationError(
                    f"parser/source trigger-chance bytes disagree: {guid}@{offset}"
                )
            if percent <= 0.0:
                raise FmodBankIsolationError(
                    f"disabled parent is already unschedulable: {guid}"
                )
            patches.append(DisabledParentPatch(guid, offset, raw_uint32, percent))
    offsets = [patch.trigger_chance_offset for patch in patches]
    if len(offsets) != len(set(offsets)):
        raise FmodBankIsolationError("disabled parents share a patch offset")
    return tuple(patches)


def _differing_offsets(source_path: Path, output_path: Path) -> tuple[int, ...]:
    differing: list[int] = []
    absolute = 0
    with source_path.open("rb") as source, output_path.open("rb") as output:
        while True:
            left = source.read(1024 * 1024)
            right = output.read(1024 * 1024)
            if len(left) != len(right):
                raise FmodBankIsolationError("isolated bank size changed")
            if not left:
                break
            differing.extend(
                absolute + index
                for index, (before, after) in enumerate(zip(left, right))
                if before != after
            )
            absolute += len(left)
    return tuple(differing)


def create_isolated_bank_copy(
    bank_path: Path,
    graph_report: dict[str, Any],
    muted_source_guids: Iterable[str],
    output_path: Path,
    *,
    disabled_parent_guids: Iterable[str] = (),
    single_shot_parent_guids: Iterable[str] = (),
) -> IsolatedBank:
    """Create an atomic temporary-bank derivative and prove its byte delta."""

    source = bank_path.resolve(strict=True)
    output = output_path.resolve()
    if output == source:
        raise FmodBankIsolationError("isolated output must not be the source bank")
    if output.exists() and os.path.samefile(source, output):
        raise FmodBankIsolationError("isolated output aliases the source bank")
    content_root = next(
        (parent for parent in source.parents if parent.name.casefold() == "content"),
        None,
    )
    if content_root is not None and (output == content_root or content_root in output.parents):
        raise FmodBankIsolationError(
            "isolated output must be outside the Assetto Corsa content tree"
        )
    muted = tuple(muted_source_guids)
    disabled_guids = tuple(disabled_parent_guids)
    parent_guids = tuple(single_shot_parent_guids)
    if not muted and not disabled_guids and not parent_guids:
        raise FmodBankIsolationError("at least one isolation patch must be selected")
    patches = (
        plan_instrument_isolation(source, graph_report, muted) if muted else ()
    )
    disabled_patches = plan_disabled_parent_isolation(
        source, graph_report, disabled_guids
    )
    parent_patches = plan_single_shot_parent_isolation(
        source, graph_report, parent_guids
    )
    disabled_offsets = {
        patch.trigger_chance_offset for patch in disabled_patches
    }
    single_shot_offsets = {
        patch.trigger_chance_offset for patch in parent_patches
    }
    if disabled_offsets & single_shot_offsets:
        raise FmodBankIsolationError(
            "one parent cannot be both disabled and selected for capture"
        )
    source_sha_before = _sha256(source)
    source_size = source.stat().st_size
    output.parent.mkdir(parents=True, exist_ok=True)

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
        shutil.copyfile(source, temporary_path)
        with temporary_path.open("r+b", buffering=0) as target:
            for patch in patches:
                target.seek(patch.trigger_chance_offset)
                target.write(ZERO_FLOAT32_LE)
            for patch in disabled_patches:
                target.seek(patch.trigger_chance_offset)
                target.write(ZERO_FLOAT32_LE)
            for patch in parent_patches:
                target.seek(patch.loop_count_offset)
                target.write(struct.pack("<i", 0))
                target.seek(patch.trigger_chance_offset)
                target.write(struct.pack("<f", 100.0))
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    if source.stat().st_size != source_size or _sha256(source) != source_sha_before:
        raise FmodBankIsolationError("source bank changed during copy isolation")
    if output.stat().st_size != source_size:
        raise FmodBankIsolationError("isolated bank size differs from source")

    differing = _differing_offsets(source, output)
    allowed_bytes = {
        patch.trigger_chance_offset + byte_index
        for patch in patches
        for byte_index in range(4)
    }
    allowed_bytes.update(
        patch.trigger_chance_offset + byte_index
        for patch in disabled_patches
        for byte_index in range(4)
    )
    allowed_bytes.update(
        offset + byte_index
        for patch in parent_patches
        for offset in (patch.loop_count_offset, patch.trigger_chance_offset)
        for byte_index in range(4)
    )
    if not differing or not set(differing).issubset(allowed_bytes):
        raise FmodBankIsolationError(
            "isolated bank contains a byte change outside selected trigger chances"
        )
    with output.open("rb") as isolated:
        for patch in patches:
            isolated.seek(patch.trigger_chance_offset)
            if isolated.read(4) != ZERO_FLOAT32_LE:
                raise FmodBankIsolationError(
                    f"isolated trigger chance is not zero: {patch.source_guid}"
                )
        for patch in disabled_patches:
            isolated.seek(patch.trigger_chance_offset)
            if isolated.read(4) != ZERO_FLOAT32_LE:
                raise FmodBankIsolationError(
                    f"isolated parent trigger chance is not zero: {patch.source_guid}"
                )
        for patch in parent_patches:
            isolated.seek(patch.loop_count_offset)
            if isolated.read(4) != struct.pack("<i", 0):
                raise FmodBankIsolationError(
                    f"isolated playlist parent still repeats: {patch.source_guid}"
                )
            isolated.seek(patch.trigger_chance_offset)
            if isolated.read(4) != struct.pack("<f", 100.0):
                raise FmodBankIsolationError(
                    f"isolated playlist parent trigger is not certain: {patch.source_guid}"
                )

    return IsolatedBank(
        source,
        output,
        source_sha_before,
        _sha256(output),
        source_size,
        patches,
        disabled_patches,
        parent_patches,
        differing,
    )
