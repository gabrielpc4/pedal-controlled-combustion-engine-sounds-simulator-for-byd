"""Read the installed FH6 modular-car and granular-synth definitions."""

from __future__ import annotations

import json
import os
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_FH6_ROOT = Path(r"D:\Games\Forza Horizon 6")
REFERENCE_CAR_ID = "TOY_SupraRZ_98"
REFERENCE_CAR_NAME = "1998 Toyota Supra RZ MKIV"


def find_fh6_root(value: str | os.PathLike[str] | None = None) -> Path:
    candidates: list[Path] = []
    if value:
        candidates.append(Path(value))
    env = os.environ.get("FH6_ROOT")
    if env:
        candidates.append(Path(env))
    candidates.append(DEFAULT_FH6_ROOT)
    for candidate in candidates:
        root = candidate.expanduser().resolve()
        if (
            (root / "ForzaHorizon6.exe").is_file()
            or (root / "forzahorizon6.exe").is_file()
        ) and (root / "media" / "Audio" / "ModularCars").is_dir():
            return root
    tried = ", ".join(str(candidate) for candidate in candidates)
    raise FileNotFoundError(f"Forza Horizon 6 was not found. Tried: {tried}")


@dataclass(frozen=True)
class CurvePoint:
    key: float
    value: float


@dataclass(frozen=True)
class SynthDefinition:
    name: str
    archive: Path
    xml: Path
    control_parameter: str
    master_volume: float
    minimum_rpm: float
    maximum_rpm: float
    rpm_smoothing: float
    loop_rpm_rate: float
    granular_rpm_rate: float
    granule_crossfade_s: float
    limiter_blend_ms: float
    throttle_curve: tuple[CurvePoint, ...]

    @property
    def installed(self) -> bool:
        return self.archive.is_file() and self.xml.is_file()


@dataclass(frozen=True)
class DrivetrainDefinition:
    ratios: tuple[float, ...]
    final_drive: float
    driven_tyre_radius_m: float
    upshift_rpm: tuple[float, ...]
    shift_duration_s: float
    neutral_fraction: float
    reverse_ratio: float
    low_speed_selector_limit_kph: float
    exact_from_installed_database: bool
    source: str


@dataclass(frozen=True)
class FH6CarConfig:
    root: Path
    car_id: str
    display_name: str
    upgrade: str
    rpm_scalar: float
    engine_bank: str
    synths: dict[str, SynthDefinition]
    effects: dict[str, str]
    model: dict[str, str]
    startup_event: str
    drivetrain: DrivetrainDefinition

    @property
    def idle_rpm(self) -> float:
        engine = self.synths.get("Engine")
        return (engine.minimum_rpm if engine and engine.minimum_rpm > 0 else 900.0) * self.rpm_scalar

    @property
    def maximum_rpm(self) -> float:
        engine = self.synths.get("Engine")
        value = engine.maximum_rpm if engine else 7500.0
        return value * self.rpm_scalar

    def json(self) -> dict[str, object]:
        return {
            "id": self.car_id,
            "name": self.display_name,
            "upgrade": self.upgrade,
            "rpmScalar": self.rpm_scalar,
            "idleRpm": self.idle_rpm,
            "maximumRpm": self.maximum_rpm,
            "engineBank": self.engine_bank,
            "channels": {
                key: {
                    "name": synth.name,
                    "installed": synth.installed,
                    "controlParameter": synth.control_parameter,
                    "masterVolume": synth.master_volume,
                    "minimumRpm": synth.minimum_rpm,
                    "maximumRpm": synth.maximum_rpm,
                }
                for key, synth in self.synths.items()
            },
            "effects": self.effects,
            "model": self.model,
            "startupEvent": self.startup_event,
            "drivetrain": {
                "ratios": list(self.drivetrain.ratios),
                "finalDrive": self.drivetrain.final_drive,
                "tyreRadiusM": self.drivetrain.driven_tyre_radius_m,
                "upshiftRpm": list(self.drivetrain.upshift_rpm),
                "shiftDurationMs": self.drivetrain.shift_duration_s * 1000.0,
                "exactFromInstalledDatabase": self.drivetrain.exact_from_installed_database,
                "source": self.drivetrain.source,
            },
        }


def _properties(root: ET.Element) -> dict[str, str]:
    result: dict[str, str] = {}
    for prop in root.findall(".//Property"):
        name = prop.get("Name")
        value = prop.get("Value")
        if name and value is not None:
            result[name] = value
    templates = [node.get("Source", "") for node in root.findall(".//PropertyTemplate")]
    if templates:
        result["Templates"] = ", ".join(value for value in templates if value)
    return result


def _curve(parent: ET.Element, input_type: str) -> tuple[CurvePoint, ...]:
    for node in parent.findall(".//VolumeCurve"):
        if node.get("InputType") == input_type:
            return tuple(
                CurvePoint(float(point.get("Key", "0")), float(point.get("Value", "0")))
                for point in node.findall("Point")
            )
    return ()


def _load_synth(audio_root: Path, name: str) -> SynthDefinition:
    xml_path = audio_root / "EngineSynth" / f"{name}.xml"
    archive_path = audio_root / "EngineSynth" / f"{name}.zip"
    if not xml_path.is_file():
        return SynthDefinition(name, archive_path, xml_path, "", 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05, 0.0, ())
    root = ET.parse(xml_path).getroot()
    channel = root.find("Channel")
    if channel is None:
        raise ValueError(f"missing Channel element in {xml_path}")
    return SynthDefinition(
        name=name,
        archive=archive_path,
        xml=xml_path,
        control_parameter=channel.get("ControlParameter", "EngineRPM"),
        master_volume=float(channel.get("MasterVolume", "1")),
        minimum_rpm=float(channel.get("MinRPM", "0")),
        maximum_rpm=float(channel.get("MaxRPM", "0")),
        rpm_smoothing=float(channel.get("RPMSmoothing", "0")),
        loop_rpm_rate=float(channel.get("LoopRPMRate", "0")),
        granular_rpm_rate=float(channel.get("GranularRPMRate", "0")),
        granule_crossfade_s=float(channel.get("GranuleXFade", "0.05")),
        limiter_blend_ms=float(channel.get("LimiterBlendDuration", "0")),
        throttle_curve=_curve(root, "Throttle"),
    )


def _startup_from_config(audio_root: Path, templates: str) -> str:
    config_path = audio_root / "ModularCarConfig.xml"
    if not config_path.is_file():
        return "AV_STARTUP_ClassicSportsCar_01"
    # The file has changed shape between Forza builds.  Matching the template
    # and startup token by proximity preserves unknown nodes without hardcoding
    # one schema.
    text = config_path.read_text(encoding="utf-8-sig")
    for template in (part.strip() for part in templates.split(",")):
        if not template:
            continue
        index = text.find(template)
        if index < 0:
            continue
        window = text[index : index + 3000]
        marker = "AV_STARTUP_"
        start = window.find(marker)
        if start >= 0:
            end = start
            while end < len(window) and (window[end].isalnum() or window[end] in "_-"):
                end += 1
            return window[start:end]
    return "AV_STARTUP_ClassicSportsCar_01"


def _resolve_synth_name(channel_name: str, authored_name: str) -> str | None:
    if channel_name == "Turbo":
        return f"Turbo_Turbine_{authored_name}_Tbo"
    if channel_name == "Transmission":
        # This profile selects a lane inside GS_ModularCar rather than a
        # method-22 EngineSynth archive.
        return None
    return authored_name


def _load_drivetrain(root: Path, cache_root: Path | None) -> DrivetrainDefinition:
    """Load recovered local values, with an explicitly gated engineering seed.

    The installed car database is itself inside a method-22 archive.  The
    decoder writes the recovered values to this small JSON document.  Until it
    exists, the runnable lab uses an isolated seed and advertises that the
    ratio fidelity gate is *not* passed; it is never described as recovered.
    """

    locations: Iterable[Path] = (
        (cache_root or (root / ".fh6-powertrain-cache")) / "vehicles" / f"{REFERENCE_CAR_ID}.json",
        Path(__file__).resolve().parent / "reference" / f"{REFERENCE_CAR_ID}.json",
    )
    for path in locations:
        if not path.is_file():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("source") != "installed-fh6-database":
            continue
        return DrivetrainDefinition(
            ratios=tuple(float(v) for v in data["ratios"]),
            final_drive=float(data["finalDrive"]),
            driven_tyre_radius_m=float(data["drivenTyreRadiusM"]),
            upshift_rpm=tuple(float(v) for v in data["upshiftRpm"]),
            shift_duration_s=float(data.get("shiftDurationMs", 170.0)) / 1000.0,
            neutral_fraction=float(data.get("neutralFraction", 0.38)),
            reverse_ratio=float(data["reverseRatio"]),
            low_speed_selector_limit_kph=float(data.get("selectorLimitKph", 5.0)),
            exact_from_installed_database=True,
            source=str(path),
        )

    # Runnable engineering seed only.  It is deliberately kept here rather
    # than masquerading as a decoded game-database file.
    return DrivetrainDefinition(
        ratios=(3.827, 2.360, 1.685, 1.312, 1.000, 0.793),
        final_drive=3.133,
        driven_tyre_radius_m=0.326,
        upshift_rpm=(6900.0, 6900.0, 6900.0, 6900.0, 6900.0),
        shift_duration_s=0.170,
        neutral_fraction=0.38,
        reverse_ratio=3.280,
        low_speed_selector_limit_kph=5.0,
        exact_from_installed_database=False,
        source="engineering seed; waiting for installed method-22 vehicle database decode",
    )


def load_reference_config(
    root: Path,
    *,
    upgrade: str = "Stock",
    cache_root: Path | None = None,
) -> FH6CarConfig:
    if upgrade not in {"Stock", "Street", "Sport", "Race"}:
        raise ValueError("upgrade must be Stock, Street, Sport, or Race")
    audio_root = root / "media" / "Audio"
    modular = audio_root / "ModularCars"
    engine_xml = modular / f"{REFERENCE_CAR_ID}-Engine.xml"
    model_xml = modular / f"{REFERENCE_CAR_ID}-Model.xml"
    if not engine_xml.is_file() or not model_xml.is_file():
        raise FileNotFoundError(f"installed Supra modular-car definitions were not found below {modular}")
    engine_root = ET.parse(engine_xml).getroot()
    model_root = ET.parse(model_xml).getroot()
    granular = engine_root.find("GranularEngine")
    if granular is None:
        raise ValueError(f"missing GranularEngine in {engine_xml}")
    parameter = granular.find("Parameter[@Name='RPMScalar']")
    rpm_scalar = float(parameter.get(upgrade, "1")) if parameter is not None else 1.0
    synths: dict[str, SynthDefinition] = {}
    for channel in granular.findall("Channel"):
        name = channel.get("Name", "")
        authored_name = channel.get(upgrade) or channel.get("Profile")
        synth_name = _resolve_synth_name(name, authored_name) if authored_name else None
        if name and synth_name and name in {"Engine", "Exhaust", "Intake", "Turbo"}:
            synths[name] = _load_synth(audio_root, synth_name)
    effects = _properties(engine_root)
    transmission = granular.find("Channel[@Name='Transmission']")
    if transmission is not None and transmission.get("Profile"):
        effects["Transmission"] = transmission.get("Profile", "")
    model = _properties(model_root)
    return FH6CarConfig(
        root=root,
        car_id=REFERENCE_CAR_ID,
        display_name=REFERENCE_CAR_NAME,
        upgrade=upgrade,
        rpm_scalar=rpm_scalar,
        engine_bank=effects.get("EngineBank", "GS_ModularCar"),
        synths=synths,
        effects=effects,
        model=model,
        startup_event=_startup_from_config(audio_root, model.get("Templates", "")),
        drivetrain=_load_drivetrain(root, cache_root),
    )
