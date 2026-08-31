"""Versioned deterministic evaluator for authored effect playlist groups."""

from __future__ import annotations

import hashlib
import math
from typing import Any, Mapping, Sequence


MASK64 = (1 << 64) - 1
XORSHIFT64STAR_MULTIPLIER = 2_685_821_657_736_338_717
ZERO_SEED_REPLACEMENT = 0x9E3779B97F4A7C15
UNIFORM_DIVISOR = 1 << 53


def playlist_seed(
    atlas_family_id: str,
    event_path: str,
    profile_audio_session_generation: int,
    group_id: str,
) -> int:
    """Derive the Android substitute stream for one profile-session group.

    Listener perspective is intentionally absent: shared transmission/turbo
    EventInstances and their authored scheduler state survive camera changes.
    Exact FMOD random sequence parity remains unclaimed; this seed defines the
    reproducible Android substitute only.
    """

    if (
        isinstance(profile_audio_session_generation, bool)
        or not isinstance(profile_audio_session_generation, int)
        or profile_audio_session_generation < 0
    ):
        raise ValueError("profile_audio_session_generation must be a non-negative integer")

    payload = (
        "byd-fmod-playlist-v3|"
        + atlas_family_id
        + "|"
        + event_path
        + "|"
        + str(profile_audio_session_generation)
        + "|"
        + group_id
    ).encode("utf-8")
    seed = int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")
    return ZERO_SEED_REPLACEMENT if seed == 0 else seed


def xorshift64star(state: int) -> tuple[int, int]:
    """Return post-transition state and its unsigned xorshift64* output."""

    value = int(state) & MASK64
    if value == 0:
        value = ZERO_SEED_REPLACEMENT
    value ^= value >> 12
    value &= MASK64
    value ^= (value << 25) & MASK64
    value &= MASK64
    value ^= value >> 27
    value &= MASK64
    return value, (value * XORSHIFT64STAR_MULTIPLIER) & MASK64


def uniform53(output: int) -> float:
    return ((int(output) & MASK64) >> 11) / UNIFORM_DIVISOR


def select_playlist_member(
    *,
    play_mode: str,
    members: Sequence[Mapping[str, Any]],
    group_trigger_chance_percent: float | None,
    state: int,
    sequential_cursor: int,
    last_selected_order: int | None,
) -> dict[str, Any]:
    """Choose an authored child with every state/draw consumption observable."""

    if play_mode not in {
        "PlaylistPlayMode_SmartRandom",
        "PlaylistPlayMode_PlaySequential",
    }:
        raise ValueError(f"unsupported playlist play mode: {play_mode}")
    group_chance = _chance(group_trigger_chance_percent, "group trigger chance")
    normalized = [_member(member) for member in members]
    if not normalized:
        raise ValueError("playlist has no members")
    state, group_output = xorshift64star(state)
    group_uniform = uniform53(group_output)
    if group_uniform * 100.0 >= group_chance:
        return {
            "state": state,
            "sequentialCursor": sequential_cursor,
            "lastSelectedOrder": last_selected_order,
            "selectedOrder": None,
            "accepted": False,
            "reason": "groupTriggerChanceRejected",
            "draws": [group_output],
        }

    if play_mode == "PlaylistPlayMode_PlaySequential":
        selected = normalized[sequential_cursor % len(normalized)]
        next_cursor = (sequential_cursor + 1) % len(normalized)
        selection_output: int | None = None
    else:
        candidates = normalized
        # FMOD SmartRandom's no-immediate-repeat behavior is retained only for
        # the documented case where it has at least three unconditional
        # children.  A child chance makes a retry semantically observable, so
        # excluding it would invent a different scheduler.
        if (
            last_selected_order is not None
            and len(normalized) >= 3
            and all(member["triggerChancePercent"] == 100.0 for member in normalized)
        ):
            candidates = [
                member
                for member in normalized
                if member["authoredOrder"] != last_selected_order
            ]
        state, selection_output = xorshift64star(state)
        selection_uniform = uniform53(selection_output)
        selected = _weighted_member(candidates, selection_uniform)
        next_cursor = sequential_cursor

    # A selected member always consumes a chance draw, including 0% and 100%,
    # which makes the next trigger independent of special-case branches.
    state, member_output = xorshift64star(state)
    member_uniform = uniform53(member_output)
    accepted = member_uniform * 100.0 < selected["triggerChancePercent"]
    draws = [group_output]
    if selection_output is not None:
        draws.append(selection_output)
    draws.append(member_output)
    return {
        "state": state,
        "sequentialCursor": next_cursor,
        "lastSelectedOrder": selected["authoredOrder"],
        "selectedOrder": selected["authoredOrder"],
        "accepted": accepted,
        "reason": None if accepted else "memberTriggerChanceRejected",
        "draws": draws,
    }


def _chance(value: object, label: str) -> float:
    chance = 100.0 if value is None else float(value)
    if not math.isfinite(chance) or not 0.0 <= chance <= 100.0:
        raise ValueError(f"{label} must be finite in [0,100]")
    return chance


def _member(raw: Mapping[str, Any]) -> dict[str, Any]:
    weight = float(raw.get("weight", 1.0) if raw.get("weight") is not None else 1.0)
    if not math.isfinite(weight) or weight <= 0.0:
        raise ValueError("playlist member weight must be finite and positive")
    order = int(raw["authoredOrder"])
    chance = _chance(raw.get("triggerChancePercent"), "member trigger chance")
    return {
        "authoredOrder": order,
        "weight": weight,
        "triggerChancePercent": chance,
    }


def _weighted_member(members: Sequence[Mapping[str, Any]], uniform: float) -> Mapping[str, Any]:
    total = sum(float(member["weight"]) for member in members)
    if not math.isfinite(total) or total <= 0.0:
        raise ValueError("playlist candidate weight total is invalid")
    point = uniform * total
    cumulative = 0.0
    for member in members:
        cumulative += float(member["weight"])
        if point < cumulative:
            return member
    # A 53-bit uniform is strictly below one, but preserve a deterministic
    # final-member fallback against a floating-point boundary round-up.
    return members[-1]
