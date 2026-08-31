"""Parse the car parameters that drive the standalone simulation."""

from __future__ import annotations

import configparser
from dataclasses import dataclass, field
from pathlib import Path

from .acd import load_car_data, text_file
from .ac_ini import parse_ac_ini


@dataclass(frozen=True)
class TurboSpec:
    lag_down: float
    lag_up: float
    maximum_boost: float
    wastegate: float
    display_max_boost: float
    reference_rpm: float
    gamma: float
    bov_threshold: float


@dataclass(frozen=True)
class BackfireSpec:
    maximum_gas: float = 0.3
    minimum_rpm: float = 3500.0
    maximum_rpm: float = 15000.0
    trigger_gas: float = 0.8


@dataclass(frozen=True)
class CarSpec:
    car_id: str
    display_name: str
    engine_inertia: float
    gearbox_inertia: float
    idle_rpm: float
    limiter_rpm: float
    limiter_hz: float
    coast_reference_rpm: float
    coast_reference_torque: float
    coast_non_linearity: float
    torque_curve: tuple[tuple[float, float], ...]
    throttle_curve: tuple[tuple[float, float], ...]
    turbo: TurboSpec | None
    turbos: tuple[TurboSpec, ...]
    backfire: BackfireSpec
    driver_eyes: tuple[float, float, float]
    bonnet_camera: tuple[float, float, float]
    engine_position: str
    wheelbase: float
    cg_location: float
    front_wheel_radius: float
    rear_wheel_radius: float
    tachometer_maximum: float
    shift_lights: tuple[float, ...] = field(default_factory=tuple)
    shift_blink_rpm: float = 0.0
    shift_blink_hz: float = 0.0


def _parser(source: str) -> configparser.ConfigParser:
    # Kunos INIs predate Python's configparser conventions.  They commonly
    # use ``VALUE=1; comment`` (without whitespace) and a few hybrid-era files
    # put VERSION keys before their first section.  AC accepts both forms.
    return parse_ac_ini(source)


def _vector(text: str) -> tuple[float, float, float]:
    values = tuple(float(item.strip()) for item in text.split(","))
    if len(values) != 3:
        raise ValueError(f"expected a 3D vector, got {text!r}")
    return values  # type: ignore[return-value]


def _lut(source: str, *, x_scale: float = 1.0, y_scale: float = 1.0) -> tuple[tuple[float, float], ...]:
    points: list[tuple[float, float]] = []
    for raw_line in source.splitlines():
        line = raw_line.split(";", 1)[0].split("#", 1)[0].strip()
        if not line or "|" not in line:
            continue
        x_text, y_text = line.split("|", 1)
        points.append((float(x_text) * x_scale, float(y_text) * y_scale))
    if not points:
        raise ValueError("LUT contains no points")
    return tuple(sorted(points))


def _optional_float(parser: configparser.ConfigParser, section: str, name: str, default: float) -> float:
    try:
        return parser.getfloat(section, name)
    except (configparser.Error, ValueError):
        return default


def load_car_spec(assetto_root: Path, car_id: str = "tatuusfa1") -> CarSpec:
    car_directory = assetto_root / "content" / "cars" / car_id
    files = load_car_data(car_directory)
    engine = _parser(text_file(files, "engine.ini"))
    drivetrain = _parser(text_file(files, "drivetrain.ini"))
    car = _parser(text_file(files, "car.ini"))
    suspensions = _parser(text_file(files, "suspensions.ini"))
    tyres = _parser(text_file(files, "tyres.ini"))
    sounds = _parser(text_file(files, "sounds.ini")) if "sounds.ini" in files else configparser.ConfigParser()
    instruments = (
        _parser(text_file(files, "digital_instruments.ini"))
        if "digital_instruments.ini" in files
        else configparser.ConfigParser()
    )

    power_file = engine.get("HEADER", "POWER_CURVE", fallback="power.lut").strip()
    torque_curve = _lut(text_file(files, power_file))
    throttle_curve = (
        _lut(text_file(files, "throttle.lut"), x_scale=0.01, y_scale=0.01)
        if "throttle.lut" in files
        else ((0.0, 0.0), (1.0, 1.0))
    )

    turbo_sections = sorted(
        (section for section in engine.sections() if section.upper().startswith("TURBO_")),
        key=lambda section: int(section.rsplit("_", 1)[1]),
    )
    turbo = None
    turbo_specs: list[TurboSpec] = []
    if turbo_sections:
        primary = turbo_sections[0]
        total_maximum = sum(engine.getfloat(section, "MAX_BOOST", fallback=0.0) for section in turbo_sections)
        total_wastegate = sum(engine.getfloat(section, "WASTEGATE", fallback=0.0) for section in turbo_sections)
        for section in turbo_sections:
            turbo_specs.append(TurboSpec(
                lag_down=engine.getfloat(section, "LAG_DN", fallback=0.99),
                lag_up=engine.getfloat(section, "LAG_UP", fallback=0.99),
                maximum_boost=engine.getfloat(section, "MAX_BOOST", fallback=0.0),
                wastegate=engine.getfloat(section, "WASTEGATE", fallback=0.0),
                display_max_boost=engine.getfloat(section, "DISPLAY_MAX_BOOST", fallback=engine.getfloat(section, "MAX_BOOST", fallback=0.0)),
                reference_rpm=engine.getfloat(section, "REFERENCE_RPM", fallback=1.0),
                gamma=engine.getfloat(section, "GAMMA", fallback=1.0),
                bov_threshold=engine.getfloat("BOV", "PRESSURE_THRESHOLD", fallback=0.5),
            ))
        turbo = TurboSpec(
            lag_down=engine.getfloat(primary, "LAG_DN", fallback=0.99),
            lag_up=engine.getfloat(primary, "LAG_UP", fallback=0.99),
            maximum_boost=total_maximum,
            wastegate=total_wastegate,
            display_max_boost=sum(
                engine.getfloat(section, "DISPLAY_MAX_BOOST", fallback=engine.getfloat(section, "MAX_BOOST", fallback=0.0))
                for section in turbo_sections
            ),
            reference_rpm=engine.getfloat(primary, "REFERENCE_RPM", fallback=1.0),
            gamma=engine.getfloat(primary, "GAMMA", fallback=1.0),
            bov_threshold=engine.getfloat("BOV", "PRESSURE_THRESHOLD", fallback=0.5),
        )

    backfire = BackfireSpec(
        # BackfireParams in the final executable hard-caps loaded MAXGAS at
        # 0.3, so the Tatuus's legacy 0.4 value becomes 0.3 at runtime.
        maximum_gas=min(0.3, _optional_float(sounds, "BACKFIRE", "MAXGAS", 0.4)),
        minimum_rpm=_optional_float(sounds, "BACKFIRE", "MINRPM", 3500.0),
        maximum_rpm=_optional_float(sounds, "BACKFIRE", "MAXRPM", 15000.0),
        trigger_gas=_optional_float(sounds, "BACKFIRE", "TRIGGERGAS", 0.8),
    )

    shift_lights: list[float] = []
    shift_blink_rpm = 0.0
    shift_blink_hz = 0.0
    for section in instruments.sections():
        if not section.upper().startswith("LED_"):
            continue
        shift_lights.append(instruments.getfloat(section, "RPM_SWITCH", fallback=0.0))
        shift_blink_rpm = max(
            shift_blink_rpm,
            instruments.getfloat(section, "BLINK_SWITCH", fallback=0.0),
        )
        shift_blink_hz = max(
            shift_blink_hz,
            instruments.getfloat(section, "BLINK_HZ", fallback=0.0),
        )

    tachometer_maximum = max(
        engine.getfloat("ENGINE_DATA", "LIMITER", fallback=7000.0) + 500.0,
        instruments.getfloat("ITEM_3", "RPM_MAX", fallback=0.0),
    )
    return CarSpec(
        car_id=car_id,
        display_name=car.get("INFO", "SCREEN_NAME", fallback=car_id),
        engine_inertia=engine.getfloat("ENGINE_DATA", "INERTIA"),
        gearbox_inertia=drivetrain.getfloat("GEARBOX", "INERTIA", fallback=0.0),
        idle_rpm=engine.getfloat("ENGINE_DATA", "MINIMUM"),
        limiter_rpm=engine.getfloat("ENGINE_DATA", "LIMITER"),
        limiter_hz=engine.getfloat("ENGINE_DATA", "LIMITER_HZ", fallback=0.0),
        coast_reference_rpm=engine.getfloat("COAST_REF", "RPM", fallback=7000.0),
        coast_reference_torque=engine.getfloat("COAST_REF", "TORQUE", fallback=0.0),
        coast_non_linearity=engine.getfloat("COAST_REF", "NON_LINEARITY", fallback=0.0),
        torque_curve=torque_curve,
        throttle_curve=throttle_curve,
        turbo=turbo,
        turbos=tuple(turbo_specs),
        backfire=backfire,
        driver_eyes=_vector(car.get("GRAPHICS", "DRIVEREYES", fallback="0,0.7,0")),
        bonnet_camera=_vector(car.get("GRAPHICS", "BONNET_CAMERA_POS", fallback="0,0.7,1")),
        # Missing/unknown POSITION maps to the car CoG in AC, not rear.
        engine_position=sounds.get("ENGINE", "POSITION", fallback="").strip().lower(),
        wheelbase=suspensions.getfloat("BASIC", "WHEELBASE", fallback=2.5),
        cg_location=suspensions.getfloat("BASIC", "CG_LOCATION", fallback=0.5),
        front_wheel_radius=tyres.getfloat("FRONT", "RADIUS", fallback=0.3),
        rear_wheel_radius=tyres.getfloat("REAR", "RADIUS", fallback=0.3),
        tachometer_maximum=tachometer_maximum,
        shift_lights=tuple(sorted(light for light in shift_lights if light > 0)),
        shift_blink_rpm=shift_blink_rpm,
        shift_blink_hz=shift_blink_hz,
    )


def interpolate_curve(points: tuple[tuple[float, float], ...], x: float) -> float:
    """Linear interpolation with endpoint clamping, matching AC LUT behavior."""

    if x <= points[0][0]:
        return points[0][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1:
            span = x1 - x0
            return y1 if span <= 0 else y0 + (y1 - y0) * ((x - x0) / span)
    return points[-1][1]
