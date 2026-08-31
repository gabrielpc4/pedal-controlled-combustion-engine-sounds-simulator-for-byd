"""FH6 powertrain audio inventory, fidelity state, and control routing."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path

from .config import FH6CarConfig
from .method22 import DecodeCache
from .powertrain import PowertrainFrame


CORE_EFFECT_BANK_PREFIXES = {
    "startup": "AV_STARTUP_",
    "backfire": "Backfire_",
    "limiter": "Limiter_",
    "burbles": "Burbles_",
    "anti_lag": "AntiLag_",
    "turbo_bov": "TurboBOV_",
    "gear_crack": "GearCrack_",
    "gears_internal": "Gears_",
    "gears_external": "Gears_",
}


@dataclass(frozen=True)
class AudioLayer:
    id: str
    authored_name: str
    source: str
    installed: bool
    decoded: bool
    enabled: bool


@dataclass(frozen=True)
class FH6AudioStatus:
    available: bool
    backend: str
    detail: str
    runtime: str
    method22_locked: bool
    synthesized_fallback_available: bool
    authentic_levels: bool
    muted: bool
    isolated: bool
    camera: str
    layers: tuple[AudioLayer, ...]

    def json(self) -> dict[str, object]:
        result = asdict(self)
        result["layers"] = [asdict(layer) for layer in self.layers]
        return result


class FH6AudioEngine:
    """Owns audio policy while the native renderer is fidelity-gated.

    The class deliberately emits no substitute engine sound.  It exposes a
    stable parameter/event frame to the native C++ renderer and becomes ready
    only after every core granular archive validates in the local cache.
    """

    cameras = ("cockpit", "bonnet", "exhaust")

    def __init__(self, config: FH6CarConfig, cache_root: Path):
        self.config = config
        self.cache = DecodeCache(cache_root)
        self.camera = "cockpit"
        self.muted = False
        self.isolated = False
        self.authentic_levels = False
        self._audition_pops = False
        self._last_parameters: dict[str, float | int | bool | str] = {}
        self._layers = self._inventory()

    def _find_asset_bank(self, token: str) -> Path | None:
        root = self.config.root / "media" / "Audio"
        aliases = {
            "ModernSportsCarStick": "ModernSportStickInternal",
            "ClassicSportsCarStick": "ModernSportStickExternal",
        }
        token = aliases.get(token, token)
        matches = sorted(root.rglob(f"*{token}*.assets.bank"))
        return matches[0] if matches else None

    def _inventory(self) -> tuple[AudioLayer, ...]:
        result: list[AudioLayer] = []
        for name, synth in self.config.synths.items():
            validation = self.cache.validate(synth.archive) if synth.archive.is_file() else None
            result.append(
                AudioLayer(name.lower(), synth.name, str(synth.archive), synth.installed, bool(validation and validation.valid), True)
            )
        authored = {
            "transmission": self.config.effects.get("Transmission", ""),
            "startup": self.config.startup_event,
            "backfire": self.config.effects.get("Backfire", ""),
            "limiter": self.config.effects.get("Limiter", ""),
            "burbles": self.config.effects.get("Burbles", ""),
            "anti_lag": self.config.effects.get("AntiLag", ""),
            "turbo_bov": self.config.effects.get("TurboBOV", ""),
            "gear_crack": self.config.effects.get("GearCrack", ""),
            "gears_internal": self.config.model.get("GearsInternal", ""),
            "gears_external": self.config.model.get("GearsExternal", ""),
        }
        for layer_id, token in authored.items():
            if layer_id == "transmission":
                candidate = self.config.root / "media" / "Audio" / "FMODBanks" / "GS_ModularCar.bank"
                asset = candidate if candidate.is_file() else None
            else:
                asset = self._find_asset_bank(token) if token else None
            result.append(AudioLayer(layer_id, token, str(asset or ""), asset is not None, asset is not None, True))
        return tuple(result)

    def configure(
        self,
        *,
        camera: str | None = None,
        muted: bool | None = None,
        isolated: bool | None = None,
        authentic_levels: bool | None = None,
    ) -> None:
        if camera is not None:
            if camera not in self.cameras:
                raise ValueError("camera must be cockpit, bonnet, or exhaust")
            self.camera = camera
        if muted is not None:
            self.muted = bool(muted)
        if isolated is not None:
            self.isolated = bool(isolated)
        if authentic_levels is not None:
            self.authentic_levels = bool(authentic_levels)

    def audition_pops(self) -> None:
        self._audition_pops = True

    @staticmethod
    def _curve_value(points, value: float) -> float:
        if not points:
            return value
        if value <= points[0].key:
            return points[0].value
        for left, right in zip(points, points[1:]):
            if value <= right.key:
                span = max(1e-9, right.key - left.key)
                position = (value - left.key) / span
                return left.value + (right.value - left.value) * position
        return points[-1].value

    def update(self, frame: PowertrainFrame) -> dict[str, float | int | bool | str]:
        # Default policy keeps all authored powertrain layers at their loudest
        # valid level. Throttle still drives sound character and trigger logic.
        authored_levels = {
            name: self._curve_value(synth.throttle_curve, frame.throttle)
            for name, synth in self.config.synths.items()
        }
        continuous_enabled = not self.muted and not self.isolated
        effects_enabled = not self.muted
        engine_level = authored_levels.get("Engine", frame.throttle) if self.authentic_levels else 1.0
        exhaust_level = authored_levels.get("Exhaust", frame.throttle) if self.authentic_levels else 1.0
        intake_level = authored_levels.get("Intake", frame.throttle) if self.authentic_levels else 1.0
        turbo_level = frame.boost if self.authentic_levels else 1.0
        transmission_level = frame.throttle if self.authentic_levels else 1.0
        if not continuous_enabled:
            engine_level = exhaust_level = intake_level = turbo_level = transmission_level = 0.0
        pop_trigger = frame.backfire_triggered or frame.burble_triggered or self._audition_pops
        self._audition_pops = False
        self._last_parameters = {
            "EngineRPM": frame.rpm,
            "NormEngineRPM": frame.rpm / max(1.0, self.config.maximum_rpm),
            "Throttle": frame.throttle,
            "LoadLevel": engine_level,
            "MasterLevel": 0.0 if self.muted else 1.0,
            "EngineLevel": engine_level,
            "ExhaustLevel": exhaust_level,
            "IntakeLevel": intake_level,
            "TurboLevel": turbo_level,
            "TransmissionLevel": transmission_level,
            "ShiftLevel": 1.0 if effects_enabled else 0.0,
            "EffectsLevel": 1.0 if effects_enabled else 0.0,
            "Boost": frame.boost,
            "PGG_RPMRateOfChange": frame.acceleration_mps2,
            "Gear": frame.gear,
            "LimiterActive": frame.limiter_active,
            "Backfire": pop_trigger,
            "AntiLagBurble": frame.anti_lag_active,
            "GearCrack": frame.shift_started,
            "Startup": frame.startup_triggered,
            "Camera": self.camera,
            "CockpitFiltering": float(self.config.model.get("CockpitFiltering", "0")) if self.camera == "cockpit" else 0.0,
            "MasterMuted": self.muted,
            "EngineTransmissionIsolated": self.isolated,
        }
        return dict(self._last_parameters)

    def status(self) -> FH6AudioStatus:
        granular = [layer for layer in self._layers if layer.id in {"engine", "exhaust", "intake", "turbo"}]
        decoded = all(layer.decoded for layer in granular) and bool(granular)
        detail = (
            "Validated granular cache is ready; native FMOD renderer is not yet linked."
            if decoded
            else (
                "Original granular recordings are installed but method-22 TransformIT decoding is not yet unlocked. "
                "An explicitly labelled realtime browser synthesis fallback is available after the first click."
            )
        )
        return FH6AudioStatus(
            available=False,
            backend="FH6 native renderer fidelity gate",
            detail=detail,
            runtime="FMOD Studio 2.x is statically linked in ForzaHorizon6.exe; exact build probe pending native bridge",
            method22_locked=not decoded,
            synthesized_fallback_available=True,
            authentic_levels=self.authentic_levels,
            muted=self.muted,
            isolated=self.isolated,
            camera=self.camera,
            layers=self._layers,
        )
