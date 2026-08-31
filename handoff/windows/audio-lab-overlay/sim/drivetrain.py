"""Automatic-shift and straight-line drivetrain model for Assetto Corsa cars.

The assist state machine in this module is a direct transcription of the
shipped Assetto Corsa 1.16.4 ``AutoShifter``, ``GearChanger``, ``Autoclutch``
and 2WD gear-request path.  The longitudinal tyre/contact-patch calculation is
deliberately smaller than AC's full four-wheel solver; its boundary is called
out on :class:`DrivetrainFrame` and in the class docstring below.
"""

from __future__ import annotations

import configparser
import math
import re
import struct
from dataclasses import dataclass, field
from pathlib import Path

from .acd import load_car_data, text_file
from .ac_ini import parse_ac_ini
from .car_config import CarSpec, interpolate_curve


RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * math.pi)
RADIAN_SECOND_PER_RPM = 1.0 / RPM_PER_RADIAN_SECOND
_AUTOBLIP_CLUTCH_GATE = 1.0 / math.pi
_MAXIMUM_FINAL_DRIVE_RATIO = 20.0


def _f32(value: float) -> float:
    """Round through IEEE-754 binary32, as AC does for controls and dt."""

    return struct.unpack("<f", struct.pack("<f", float(value)))[0]


def _milliseconds_as_ac_seconds(value: float) -> float:
    # Drivetrain::loadINI multiplies two floats, then widens the result to
    # double.  Preserving that detail makes the strict shift timer reproducible.
    return _f32(_f32(value) * _f32(0.001))


def _parser(source: str) -> configparser.ConfigParser:
    return parse_ac_ini(source)


def _lut(source: str) -> tuple[tuple[float, float], ...]:
    def ac_float(value: str) -> float:
        # The shipped data contains a couple of malformed values such as
        # ``0.0.91``. AC's C parser consumes the valid numeric prefix (0.0).
        match = re.match(r"^[\t ]*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)", value)
        if not match:
            raise ValueError(f"invalid LUT number {value!r}")
        return float(match.group(1))

    points: list[tuple[float, float]] = []
    for raw_line in source.splitlines():
        line = raw_line.split(";", 1)[0].split("#", 1)[0].strip()
        if not line or "|" not in line:
            continue
        x, y = line.split("|", 1)
        points.append((ac_float(x), ac_float(y)))
    return tuple(sorted(points))


def _authored_final_drive_options(source: str, table_name: str) -> tuple[tuple[str, float], ...]:
    options: list[tuple[str, float]] = []
    for line_number, raw_line in enumerate(source.splitlines(), start=1):
        line = raw_line.split(";", 1)[0].split("#", 1)[0].strip()
        if not line:
            continue
        if "|" not in line:
            raise ValueError(f"{table_name}:{line_number} is not a label|ratio row")
        label, raw_ratio = (part.strip() for part in line.split("|", 1))
        if not label:
            raise ValueError(f"{table_name}:{line_number} has no option label")
        ratio = float(raw_ratio)
        if not math.isfinite(ratio) or not 0.0 < ratio <= _MAXIMUM_FINAL_DRIVE_RATIO:
            raise ValueError(
                f"{table_name}:{line_number} final-drive ratio {ratio!r} is outside "
                f"(0, {_MAXIMUM_FINAL_DRIVE_RATIO}]"
            )
        options.append((label, ratio))
    if not options:
        raise ValueError(f"{table_name} has no authored final-drive options")

    return tuple(options)


def _final_drive_ratio(files: dict[str, bytes], drivetrain: configparser.ConfigParser) -> float:
    raw_direct = drivetrain.get("GEARS", "FINAL")
    try:
        direct = float(raw_direct)
    except ValueError:
        direct = math.nan
    if math.isfinite(direct) and 0.0 < direct <= _MAXIMUM_FINAL_DRIVE_RATIO:
        return direct
    if "setup.ini" not in files:
        raise ValueError(
            f"drivetrain FINAL={raw_direct!r} is invalid/outlying and setup.ini is missing"
        )
    setup = _parser(text_file(files, "setup.ini"))
    if not setup.has_section("FINAL_GEAR_RATIO"):
        raise ValueError(
            f"drivetrain FINAL={raw_direct!r} is invalid/outlying and setup.ini has no [FINAL_GEAR_RATIO]"
        )
    ratio_table_name = setup.get("FINAL_GEAR_RATIO", "RATIOS").strip()
    if (
        not ratio_table_name
        or Path(ratio_table_name).name != ratio_table_name
        or "/" in ratio_table_name
        or "\\" in ratio_table_name
        or ratio_table_name in {".", ".."}
    ):
        raise ValueError(f"setup.ini references unsafe final-drive table {ratio_table_name!r}")
    if ratio_table_name not in files:
        raise ValueError(f"setup.ini references missing final-drive table {ratio_table_name!r}")
    options = _authored_final_drive_options(text_file(files, ratio_table_name), ratio_table_name)

    return options[0][1]


@dataclass(frozen=True)
class AeroSurfaceSpec:
    index: int
    chord: float
    span: float
    angle_degrees: float
    lift_gain: float
    drag_gain: float
    lift_curve: tuple[tuple[float, float], ...]
    drag_curve: tuple[tuple[float, float], ...]
    controller_speed_curve: tuple[tuple[float, float], ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class VehicleSpec:
    mass_kg: float
    front_weight_fraction: float
    front_wheel_radius_m: float
    rear_wheel_radius_m: float
    front_wheel_inertia: float
    rear_wheel_inertia: float
    front_grip_coefficient: float
    rear_grip_coefficient: float
    front_rolling_resistance_0: float
    rear_rolling_resistance_0: float
    front_rolling_resistance_1: float
    rear_rolling_resistance_1: float
    brake_max_torque: float
    brake_front_share: float
    aero_surfaces: tuple[AeroSurfaceSpec, ...]
    air_density_kg_m3: float = 1.225


@dataclass(frozen=True)
class DrivetrainSpec:
    traction: str
    reverse_ratio: float
    forward_ratios: tuple[float, ...]
    final_drive: float
    differential_power: float
    differential_coast: float
    differential_preload: float
    gear_up_time_s: float
    gear_down_time_s: float
    auto_cutoff_time_s: float
    supports_shifter: bool
    valid_shift_rpm_window: float
    controls_window_gain: float
    gearbox_inertia: float
    clutch_max_torque: float
    autoclutch_upshift_profile: tuple[tuple[float, float], ...]
    autoclutch_downshift_profile: tuple[tuple[float, float], ...]
    autoclutch_use_on_changes: bool
    autoclutch_min_rpm: float
    autoclutch_max_rpm: float
    autoclutch_speed: float
    autoclutch_forced: bool
    autoblip_electronic: bool
    autoblip_profile_ms: tuple[tuple[float, float], ...]
    auto_up_rpm: int
    auto_down_rpm: int
    auto_slip_threshold: float
    auto_gas_cutoff_s: float
    downshift_protection: bool
    downshift_overrev_rpm: int
    downshift_lock_neutral: bool
    vehicle: VehicleSpec

    @property
    def maximum_gear(self) -> int:
        return len(self.forward_ratios)

    def ratio_for_gear(self, gear: int) -> float:
        if gear == -1:
            return self.reverse_ratio
        if gear == 0:
            return 0.0
        if 1 <= gear <= self.maximum_gear:
            return self.forward_ratios[gear - 1]
        raise ValueError(f"gear {gear} is outside -1..{self.maximum_gear}")


@dataclass(frozen=True)
class DrivetrainFrame:
    rpm: float
    speed_mps: float
    speed_kmh: float
    speed_kph: float
    gear: int
    requested_gear: int
    drivetrain_speed: float
    driver_throttle: float
    throttle: float
    effective_throttle: float
    mapped_throttle: float
    brake: float
    clutch: float
    boost: float
    engine_torque: float
    clutch_torque: float
    wheel_torque: float
    longitudinal_force: float
    brake_force: float
    rolling_resistance_force: float
    aero_drag_force: float
    aero_downforce: float
    acceleration_mps2: float
    driving_tyre_slip: float
    limiter_active: bool
    limiter_pulse: bool
    backfire_triggered: bool
    bov: float
    bov_decay: float
    shifting: bool
    shift_phase: str
    gear_direction: int
    shift_started: bool
    shift_rejected: bool
    gear_changed: bool
    shift_completed: bool
    gear_engaged: bool
    traction_limit_active: bool
    traction_limit_pulse: bool
    auto_gas_cut_active: bool
    engine_cut_active: bool
    autoblip_active: bool
    vehicle_dynamics_exact: bool = False


def _profile(
    parser: configparser.ConfigParser,
    section_name: str,
) -> tuple[tuple[float, float], ...]:
    if not section_name or section_name.upper() == "NONE" or not parser.has_section(section_name):
        return ()
    p0 = _milliseconds_as_ac_seconds(parser.getfloat(section_name, "POINT_0"))
    p1 = _milliseconds_as_ac_seconds(parser.getfloat(section_name, "POINT_1"))
    p2 = _milliseconds_as_ac_seconds(parser.getfloat(section_name, "POINT_2"))
    return ((0.0, 1.0), (p0, 0.0), (p1, 0.0), (p2, 1.0))


def load_drivetrain_spec(
    assetto_root: Path,
    car_id: str = "tatuusfa1",
) -> DrivetrainSpec:
    """Load the gearbox, assist, wheel, brake and aero inputs from ``data.acd``."""

    car_directory = assetto_root / "content" / "cars" / car_id
    files = load_car_data(car_directory)
    drivetrain = _parser(text_file(files, "drivetrain.ini"))
    car = _parser(text_file(files, "car.ini"))
    tyres = _parser(text_file(files, "tyres.ini"))
    brakes = _parser(text_file(files, "brakes.ini"))
    aero = _parser(text_file(files, "aero.ini")) if "aero.ini" in files else configparser.ConfigParser()
    brakes_section = "DATA" if brakes.has_section("DATA") else "HEADER"

    count = drivetrain.getint("GEARS", "COUNT")
    forward_ratios = tuple(drivetrain.getfloat("GEARS", f"GEAR_{index}") for index in range(1, count + 1))
    final_drive = _final_drive_ratio(files, drivetrain)

    auto_section = "AUTO_SHIFTER"
    auto_source = drivetrain
    if not drivetrain.has_section(auto_section):
        auto_source = _parser(text_file(files, "ai.ini"))
        auto_section = "GEARS"

    up_profile_name = drivetrain.get("AUTOCLUTCH", "UPSHIFT_PROFILE", fallback="NONE").strip()
    down_profile_name = drivetrain.get("AUTOCLUTCH", "DOWNSHIFT_PROFILE", fallback="NONE").strip()

    autoblip_level = drivetrain.getfloat("AUTOBLIP", "LEVEL", fallback=1.0)
    autoblip_profile_ms: tuple[tuple[float, float], ...] = ()
    if drivetrain.has_section("AUTOBLIP"):
        autoblip_profile_ms = (
            (0.0, 0.0),
            (drivetrain.getfloat("AUTOBLIP", "POINT_0"), autoblip_level),
            (drivetrain.getfloat("AUTOBLIP", "POINT_1"), autoblip_level),
            (drivetrain.getfloat("AUTOBLIP", "POINT_2"), 0.0),
        )

    controllers: dict[int, tuple[tuple[float, float], ...]] = {}
    for section in aero.sections():
        if not section.upper().startswith("DYNAMIC_CONTROLLER_"):
            continue
        wing = aero.getint(section, "WING", fallback=-1)
        lut_name = aero.get(section, "LUT", fallback="").strip()
        if wing >= 0 and lut_name in files:
            controllers[wing] = _lut(text_file(files, lut_name))

    surfaces: list[AeroSurfaceSpec] = []
    for section in aero.sections():
        if not section.upper().startswith("WING_"):
            continue
        try:
            index = int(section.split("_", 1)[1])
        except ValueError:
            continue
        lift_name = aero.get(section, "LUT_AOA_CL", fallback="").strip()
        drag_name = aero.get(section, "LUT_AOA_CD", fallback="").strip()
        if lift_name not in files or drag_name not in files:
            continue
        surfaces.append(
            AeroSurfaceSpec(
                index=index,
                chord=aero.getfloat(section, "CHORD", fallback=1.0),
                span=aero.getfloat(section, "SPAN", fallback=1.0),
                angle_degrees=aero.getfloat(section, "ANGLE", fallback=0.0),
                lift_gain=aero.getfloat(section, "CL_GAIN", fallback=1.0),
                drag_gain=aero.getfloat(section, "CD_GAIN", fallback=1.0),
                lift_curve=_lut(text_file(files, lift_name)),
                drag_curve=_lut(text_file(files, drag_name)),
                controller_speed_curve=controllers.get(index, ()),
            )
        )

    vehicle = VehicleSpec(
        mass_kg=car.getfloat("BASIC", "TOTALMASS"),
        front_weight_fraction=_parser(text_file(files, "suspensions.ini")).getfloat(
            "BASIC", "CG_LOCATION", fallback=0.5
        ),
        front_wheel_radius_m=tyres.getfloat("FRONT", "RADIUS"),
        rear_wheel_radius_m=tyres.getfloat("REAR", "RADIUS"),
        front_wheel_inertia=tyres.getfloat("FRONT", "ANGULAR_INERTIA"),
        rear_wheel_inertia=tyres.getfloat("REAR", "ANGULAR_INERTIA"),
        front_grip_coefficient=tyres.getfloat(
            "FRONT", "DX_REF", fallback=tyres.getfloat("FRONT", "DX0", fallback=1.0)
        ),
        rear_grip_coefficient=tyres.getfloat(
            "REAR", "DX_REF", fallback=tyres.getfloat("REAR", "DX0", fallback=1.0)
        ),
        front_rolling_resistance_0=tyres.getfloat("FRONT", "ROLLING_RESISTANCE_0", fallback=0.0),
        rear_rolling_resistance_0=tyres.getfloat("REAR", "ROLLING_RESISTANCE_0", fallback=0.0),
        front_rolling_resistance_1=tyres.getfloat("FRONT", "ROLLING_RESISTANCE_1", fallback=0.0),
        rear_rolling_resistance_1=tyres.getfloat("REAR", "ROLLING_RESISTANCE_1", fallback=0.0),
        # A few older mods accidentally commented out their [DATA] marker.
        # AC still consumes the top-level values, represented here as HEADER.
        brake_max_torque=brakes.getfloat(brakes_section, "MAX_TORQUE"),
        brake_front_share=brakes.getfloat(brakes_section, "FRONT_SHARE"),
        aero_surfaces=tuple(sorted(surfaces, key=lambda item: item.index)),
    )

    return DrivetrainSpec(
        traction=drivetrain.get("TRACTION", "TYPE", fallback="RWD").upper(),
        reverse_ratio=drivetrain.getfloat("GEARS", "GEAR_R"),
        forward_ratios=forward_ratios,
        final_drive=final_drive,
        differential_power=drivetrain.getfloat("DIFFERENTIAL", "POWER", fallback=0.0),
        differential_coast=drivetrain.getfloat("DIFFERENTIAL", "COAST", fallback=0.0),
        differential_preload=drivetrain.getfloat("DIFFERENTIAL", "PRELOAD", fallback=0.0),
        gear_up_time_s=_milliseconds_as_ac_seconds(drivetrain.getfloat("GEARBOX", "CHANGE_UP_TIME")),
        gear_down_time_s=_milliseconds_as_ac_seconds(drivetrain.getfloat("GEARBOX", "CHANGE_DN_TIME")),
        auto_cutoff_time_s=drivetrain.getfloat("GEARBOX", "AUTO_CUTOFF_TIME", fallback=0.0) * 0.001,
        supports_shifter=drivetrain.getboolean("GEARBOX", "SUPPORTS_SHIFTER", fallback=False),
        valid_shift_rpm_window=drivetrain.getfloat("GEARBOX", "VALID_SHIFT_RPM_WINDOW", fallback=0.0),
        controls_window_gain=drivetrain.getfloat("GEARBOX", "CONTROLS_WINDOW_GAIN", fallback=0.0),
        gearbox_inertia=drivetrain.getfloat("GEARBOX", "INERTIA", fallback=0.02),
        clutch_max_torque=drivetrain.getfloat("CLUTCH", "MAX_TORQUE"),
        autoclutch_upshift_profile=_profile(drivetrain, up_profile_name),
        autoclutch_downshift_profile=_profile(drivetrain, down_profile_name),
        autoclutch_use_on_changes=drivetrain.getboolean("AUTOCLUTCH", "USE_ON_CHANGES", fallback=True),
        autoclutch_min_rpm=drivetrain.getfloat("AUTOCLUTCH", "MIN_RPM", fallback=1500.0),
        autoclutch_max_rpm=drivetrain.getfloat("AUTOCLUTCH", "MAX_RPM", fallback=2500.0),
        autoclutch_speed=drivetrain.getfloat("AUTOCLUTCH", "CLUTCH_SPEED", fallback=1.0),
        autoclutch_forced=drivetrain.getboolean("AUTOCLUTCH", "FORCED_ON", fallback=False),
        autoblip_electronic=drivetrain.getboolean("AUTOBLIP", "ELECTRONIC", fallback=False),
        autoblip_profile_ms=autoblip_profile_ms,
        auto_up_rpm=int(auto_source.getfloat(auto_section, "UP", fallback=4000.0)),
        auto_down_rpm=int(auto_source.getfloat(auto_section, "DOWN", fallback=0.0)),
        auto_slip_threshold=auto_source.getfloat(auto_section, "SLIP_THRESHOLD", fallback=0.8),
        auto_gas_cutoff_s=auto_source.getfloat(auto_section, "GAS_CUTOFF_TIME", fallback=0.5),
        downshift_protection=drivetrain.getboolean("DOWNSHIFT_PROTECTION", "ACTIVE", fallback=False),
        downshift_overrev_rpm=drivetrain.getint("DOWNSHIFT_PROTECTION", "OVERREV", fallback=0),
        downshift_lock_neutral=drivetrain.getboolean("DOWNSHIFT_PROTECTION", "LOCK_N", fallback=False),
        vehicle=vehicle,
    )


class AutomaticDrivetrain:
    """AC-exact automatic-assist timing around a bounded longitudinal model.

    Exact: control ordering, auto-shift predicates, request acceptance, the
    immediate neutral intermediate, strict timer comparison, gas/engine cuts,
    autoclutch profiles and autoblip profile.  Approximate: contact-patch slip,
    clutch constraint, dynamic tyre radius/load sensitivity and aero height
    maps.  Those require AC's complete suspension/tyre rigid-body solver.
    """

    def __init__(
        self,
        engine_spec: CarSpec,
        drivetrain_spec: DrivetrainSpec,
        *,
        initial_gear: int = 1,
    ):
        self.engine_spec = engine_spec
        self.spec = drivetrain_spec
        self.rpm = engine_spec.idle_rpm
        self.speed_mps = 0.0
        self.gear = 0
        self.requested_gear = 0
        self.elapsed = 0.0
        self._session_elapsed_ms = 0.0
        self._target_throttle = 0.0
        self._keyboard_throttle = False
        self._keyboard_throttle_pressed = False
        self._keyboard_gas = 0.0
        self._target_brake = 0.0
        self._keyboard_brake = False
        self._keyboard_brake_pressed = False
        self._manual_clutch = 1.0
        self.autoclutch_enabled = True
        self.automatic_shifting = True
        self.driving_tyre_slip = 0.0
        self._traction_limit_active = False
        self._clutch_signal = 0.0
        self._clutch_sequence: tuple[tuple[float, float], ...] = ()
        self._clutch_sequence_elapsed = 0.0
        self._autoblip_start_ms: float | None = None
        self._auto_gas_cutoff = 0.0
        self._engine_cutoff = 0.0
        self._shift_direction = 0
        self._shift_target = 0
        self._shift_elapsed = 0.0
        self._shift_duration = 0.0
        self._manual_shift_request = 0
        self._previous_wheel_speed = 0.0
        # The next gear's synchronized RPM is implicit in the installed gear
        # ratios. Precompute it at the car's authored automatic upshift RPM so
        # every gear has a useful threshold before it has been driven.
        self._upshift_landing_rpm: dict[int, float] = {
            gear: self.spec.auto_up_rpm
            * abs(self.spec.ratio_for_gear(gear))
            / max(1e-9, abs(self.spec.ratio_for_gear(gear - 1)))
            for gear in range(2, self.spec.maximum_gear + 1)
        }
        self.last_upshift_landing_rpm = 0.0
        self._turbo_q = 0.0
        self._turbo_qs = [0.0 for _ in engine_spec.turbos]
        self.boost = 0.0
        self.bov = 0.0
        self.bov_decay = 10.0
        self._backfire_peak_gas = 0.6
        self._backfire_arm_level = engine_spec.backfire.trigger_gas
        self._backfire_fire_below = 0.25
        self._backfire_armed = False
        self._backfire_timer = 0.0
        self._limiter_counter = 0
        self.set_gear(initial_gear)

    @property
    def shifting(self) -> bool:
        return self._shift_direction != 0

    @property
    def automatic_downshift_rpm(self) -> float:
        """Current gear's ratio-calculated landing-point threshold."""

        learned = self._upshift_landing_rpm.get(self.gear)
        if learned is not None:
            return max(self.engine_spec.idle_rpm, learned)
        fallback = float(self.spec.auto_down_rpm)
        return fallback * 0.65 if self.gear == 2 else fallback

    def set_throttle(self, value: float) -> None:
        self._keyboard_throttle = False
        self._target_throttle = min(1.0, max(0.0, float(value)))

    def set_keyboard_throttle(self, pressed: bool) -> None:
        if not self._keyboard_throttle:
            self._keyboard_gas = self._target_throttle
        self._keyboard_throttle = True
        self._keyboard_throttle_pressed = bool(pressed)

    def set_brake(self, value: float) -> None:
        self._keyboard_brake = False
        self._target_brake = min(1.0, max(0.0, float(value)))

    def set_keyboard_brake(self, pressed: bool) -> None:
        self._keyboard_brake = True
        self._keyboard_brake_pressed = bool(pressed)

    def set_clutch(self, value: float) -> None:
        self._manual_clutch = min(1.0, max(0.0, float(value)))

    def set_autoclutch(self, enabled: bool) -> None:
        self.autoclutch_enabled = bool(enabled)

    def set_auto_shift(self, enabled: bool) -> None:
        self.automatic_shifting = bool(enabled)

    def set_driving_tyre_slip(self, value: float) -> None:
        self.driving_tyre_slip = float(value)

    def set_speed(self, value_mps: float) -> None:
        """Set longitudinal speed without creating a synthetic acceleration spike."""

        self.speed_mps = max(0.0, float(value_mps))
        self._previous_wheel_speed = (
            self.speed_mps / self._driven_radius()
        )

    def _driven_radius(self) -> float:
        front = max(1e-6, self.spec.vehicle.front_wheel_radius_m)
        rear = max(1e-6, self.spec.vehicle.rear_wheel_radius_m)
        traction = self.spec.traction.upper()
        if traction == "FWD":
            return front
        if traction.startswith("AWD"):
            return 0.5 * (front + rear)
        return rear

    def request_shift(self, direction: int) -> None:
        if direction not in (-1, 1):
            raise ValueError("shift direction must be -1 or +1")
        self._manual_shift_request = direction

    def set_gear(self, gear: int) -> None:
        self.spec.ratio_for_gear(gear)
        self.gear = int(gear)
        self.requested_gear = self.gear
        self._shift_direction = 0
        self._shift_target = self.gear
        self._shift_elapsed = 0.0
        self._shift_duration = 0.0

    def _resolve_throttle(self, dt: float) -> float:
        if not self._keyboard_throttle:
            return self._target_throttle
        if self._keyboard_throttle_pressed:
            self._keyboard_gas = min(1.0, _f32(self._keyboard_gas + 4.0 * dt))
        else:
            # KeyboardCarControl clears gas and intGas directly on key-up.
            self._keyboard_gas = 0.0
        return self._keyboard_gas

    def _aero(self) -> tuple[float, float]:
        speed_kmh = self.speed_mps * 3.6
        q = 0.5 * self.spec.vehicle.air_density_kg_m3 * self.speed_mps * self.speed_mps
        drag_area = 0.0
        lift_area = 0.0
        for surface in self.spec.vehicle.aero_surfaces:
            angle = surface.angle_degrees
            if surface.controller_speed_curve:
                angle += interpolate_curve(surface.controller_speed_curve, speed_kmh)
            area = surface.chord * surface.span
            drag_area += area * surface.drag_gain * interpolate_curve(surface.drag_curve, angle)
            lift_area += area * surface.lift_gain * interpolate_curve(surface.lift_curve, angle)
        # AC additionally applies ride-height, yaw, damage and controller
        # filtering.  Their omission is the principal aero approximation here.
        return max(0.0, q * drag_area), max(0.0, q * lift_area)

    def _optimal_keyboard_brake(self, downforce: float) -> float:
        """Static straight-line equivalent of RaceEngineer::getOptimalBrake."""

        vehicle = self.spec.vehicle
        gravity_load = vehicle.mass_kg * 9.81
        front_load = gravity_load * vehicle.front_weight_fraction + downforce * vehicle.front_weight_fraction
        rear_load = gravity_load - gravity_load * vehicle.front_weight_fraction + downforce * (1.0 - vehicle.front_weight_fraction)
        front_available_per_wheel = 0.5 * front_load * vehicle.front_grip_coefficient
        rear_available_per_wheel = 0.5 * rear_load * vehicle.rear_grip_coefficient
        front_requested = vehicle.brake_max_torque * vehicle.brake_front_share / vehicle.front_wheel_radius_m
        rear_requested = vehicle.brake_max_torque * (1.0 - vehicle.brake_front_share) / vehicle.rear_wheel_radius_m
        front = front_available_per_wheel / max(1e-9, front_requested)
        rear = rear_available_per_wheel / max(1e-9, rear_requested)
        return min(1.0, max(0.0, min(front, rear)))

    def _autoclutch_step(self, dt: float, gas: float) -> float:
        if self._clutch_sequence:
            value = interpolate_curve(self._clutch_sequence, self._clutch_sequence_elapsed)
            self._clutch_signal = value
            self._clutch_sequence_elapsed = _f32(self._clutch_sequence_elapsed + dt)
            if self._clutch_sequence_elapsed > self._clutch_sequence[-1][0]:
                self._clutch_sequence = ()
            return min(1.0, max(0.0, value))

        if not (self.autoclutch_enabled or self.spec.autoclutch_forced):
            return self._manual_clutch

        minimum = self.spec.autoclutch_min_rpm
        maximum = self.spec.autoclutch_max_rpm
        if self.gear in (-1, 1):
            if self.rpm < minimum:
                target = 0.0
            elif self.rpm > maximum:
                target = 1.0
            else:
                target = (self.rpm - minimum) / max(1.0, maximum - minimum)
        elif self.gear == 0:
            target = 1.0 if self.speed_mps * 3.6 >= 5.0 or gas > 0.2 else 0.0
        else:
            target = 1.0 if self.rpm >= minimum else 0.0

        maximum_step = self.spec.autoclutch_speed * dt
        difference = target - self._clutch_signal
        if abs(difference) < maximum_step:
            self._clutch_signal = target
        elif difference > 0.0:
            self._clutch_signal += maximum_step
        else:
            self._clutch_signal -= maximum_step
        return min(1.0, max(0.0, self._clutch_signal))

    def _auto_shift_decision(self, gas: float, clutch: float) -> int:
        # PhysicsEngine::hasSessionStarted(300.0) uses the millisecond clock.
        if (
            not self.automatic_shifting
            or self._session_elapsed_ms <= 300.0
            or self.gear == -1
            or self.shifting
        ):
            return 0
        slipping = self.driving_tyre_slip > self.spec.auto_slip_threshold and self.speed_mps > 5.0
        request = 0
        if (clutch > 0.99 or self.gear == 0) and not slipping:
            if (
                self.rpm > self.spec.auto_up_rpm
                and self.gear < self.spec.maximum_gear
                and gas > 0.2
                and self._auto_gas_cutoff <= 0.0
            ):
                request = 1
                self._auto_gas_cutoff = _f32(self.spec.auto_gas_cutoff_s)
            else:
                down_rpm = self.automatic_downshift_rpm
                learned_release = self.gear in self._upshift_landing_rpm and gas <= 0.2
                if (
                    self.rpm < down_rpm
                    and (self.gear not in self._upshift_landing_rpm or learned_release)
                    and self.gear > 1
                    and clutch > 0.85
                    and (learned_release or self._auto_gas_cutoff <= 0.0)
                ):
                    request = -1
        if (
            request == 0
            and self.speed_mps < 2.0
            and gas < 0.1
            and self._auto_gas_cutoff <= 0.0
            and self.gear > 1
        ):
            request = -1
        return request

    def _downshift_allowed(self, target: int, dt: float) -> bool:
        if not self.spec.downshift_protection:
            return True
        if target == 0 and self.spec.downshift_lock_neutral and self.speed_mps * 3.6 > 2.0:
            return False
        if target <= 0:
            return True
        wheel_speed = self.speed_mps / self._driven_radius()
        wheel_acceleration = max(0.0, (wheel_speed - self._previous_wheel_speed) / max(dt, 1e-9))
        projected = wheel_speed + self.spec.gear_down_time_s * wheel_acceleration
        projected_rpm = projected * abs(self.spec.ratio_for_gear(target) * self.spec.final_drive) * RPM_PER_RADIAN_SECOND
        return projected_rpm <= self.engine_spec.limiter_rpm + self.spec.downshift_overrev_rpm

    def _accept_shift(self, direction: int, clutch: float, dt: float) -> bool:
        if direction == 0 or self.shifting:
            return False
        target = self.gear + direction
        if target < -1 or target > self.spec.maximum_gear:
            return False
        if direction < 0 and not self._downshift_allowed(target, dt):
            return False

        self._shift_direction = direction
        self._shift_target = target
        self.requested_gear = target
        self._shift_elapsed = 0.0
        self._shift_duration = self.spec.gear_up_time_s if direction > 0 else self.spec.gear_down_time_s
        # Drivetrain::gearUp/gearDown sets internal currentGear=1 (neutral)
        # immediately after dispatching OnGearRequest.
        self.gear = 0
        if direction > 0 and self.spec.auto_cutoff_time_s != 0.0:
            self._engine_cutoff = self.spec.auto_cutoff_time_s
        if direction > 0:
            profile = self.spec.autoclutch_upshift_profile
        else:
            profile = self.spec.autoclutch_downshift_profile
        if self.spec.autoclutch_use_on_changes and clutch > 0.01 and profile:
            self._clutch_sequence = profile
            self._clutch_sequence_elapsed = 0.0
        if direction < 0 and clutch > _AUTOBLIP_CLUTCH_GATE:
            self._autoblip_start_ms = self._session_elapsed_ms
        return True

    def _coast_torque(self, rpm: float) -> float:
        if rpm <= self.engine_spec.idle_rpm:
            return 0.0
        reference = self.engine_spec.coast_reference_rpm
        minimum = self.engine_spec.idle_rpm
        non_linearity = self.engine_spec.coast_non_linearity
        denominator = (1.0 - non_linearity) * reference - minimum
        c1 = -self.engine_spec.coast_reference_torque / denominator if denominator else 0.0
        nonlinear_rpm = non_linearity * reference
        c2 = self.engine_spec.coast_reference_torque / (nonlinear_rpm * nonlinear_rpm) if nonlinear_rpm else 0.0
        delta = rpm - minimum
        return c1 * delta - c2 * delta * delta * (1.0 if rpm >= 0.0 else -1.0)

    def _engine_torque(
        self,
        dt: float,
        controls_gas: float,
        engine_gas: float,
    ) -> tuple[float, float, float, bool, bool]:
        # FMOD/backfire observes Car.controls.gas after AutoBlip/AutoShifter,
        # while Drivetrain can separately zero the SACEngineInput for its cut.
        if controls_gas > self._backfire_peak_gas and controls_gas != 0.0:
            self._backfire_peak_gas = controls_gas
            self._backfire_arm_level = self.engine_spec.backfire.trigger_gas * controls_gas
            self._backfire_fire_below = self.engine_spec.backfire.maximum_gas * controls_gas
        if controls_gas > self._backfire_arm_level:
            self._backfire_armed = True
        backfire = (
            self._backfire_armed
            and 0.0 < controls_gas < self._backfire_fire_below
            and self.rpm > self.engine_spec.backfire.minimum_rpm
            and self.rpm <= self.engine_spec.backfire.maximum_rpm
            and self._backfire_timer > 1.0
        )
        if backfire:
            self._backfire_armed = False
        elif self._backfire_armed:
            self._backfire_timer = min(10.0, self._backfire_timer + dt)

        mapped = interpolate_curve(self.engine_spec.throttle_curve, engine_gas)
        if self.engine_spec.limiter_hz > 0.0:
            limiter_steps = int(int(1000.0 / self.engine_spec.limiter_hz) / 3)
        else:
            limiter_steps = 50
        if self.engine_spec.limiter_rpm > 0.0 and self.rpm > self.engine_spec.limiter_rpm:
            self._limiter_counter = max(1, limiter_steps)
        limiter_active = self._limiter_counter > 0
        if limiter_active:
            self._limiter_counter -= 1
        effective = 0.0 if limiter_active else mapped

        if self.engine_spec.turbos:
            boosts: list[float] = []
            for index, turbo in enumerate(self.engine_spec.turbos):
                turbo_input = min(1.0, max(0.0, effective * self.rpm / max(1.0, turbo.reference_rpm)))
                target = turbo_input**turbo.gamma
                q = self._turbo_qs[index]
                lag = turbo.lag_up if target > q else turbo.lag_down
                q += min(1.0, max(0.0, dt * lag)) * (target - q)
                if turbo.wastegate > 0.0 and turbo.maximum_boost * q > turbo.wastegate:
                    q = turbo.wastegate / max(0.001, turbo.maximum_boost)
                self._turbo_qs[index] = q
                boosts.append(turbo.maximum_boost * q)
            self._turbo_q = self._turbo_qs[0]
            self.boost = sum(boosts)
            self.bov = (
                1.0
                if self.boost * (1.0 - effective) > self.engine_spec.turbos[0].bov_threshold
                else 0.0
            )
            self.bov_decay = (
                0.0 if self.bov > 0.0 else min(10.0, self.bov_decay + dt)
            )

        power = interpolate_curve(self.engine_spec.torque_curve, self.rpm) * (1.0 + self.boost)
        coast = self._coast_torque(self.rpm)
        torque = coast + effective * (power - coast)
        if self.rpm < self.engine_spec.idle_rpm:
            torque = max(torque, 15.0)
        return torque, mapped, effective, limiter_active, backfire

    def step(self, dt: float) -> DrivetrainFrame:
        dt = _f32(min(0.02, max(0.0001, float(dt))))
        self.elapsed += dt
        self._session_elapsed_ms += float(dt) * 1000.0
        shift_started = False
        shift_rejected = False
        shift_completed = False
        event_direction = self._shift_direction if self.shifting else 0

        raw_gas = self._resolve_throttle(dt)
        drag_force, downforce = self._aero()
        if self._keyboard_brake:
            brake = self._optimal_keyboard_brake(downforce) if self._keyboard_brake_pressed else 0.0
        else:
            brake = self._target_brake

        # Car::step calls Autoclutch before stepComponents.  Within components,
        # AutoBlip -> AutoShifter -> GearChanger -> Drivetrain is the exact order.
        clutch = self._autoclutch_step(dt, raw_gas)
        gas = raw_gas
        autoblip_active = False
        if self._autoblip_start_ms is not None and (self.spec.autoblip_electronic or self.autoclutch_enabled):
            autoblip_elapsed = self._session_elapsed_ms - self._autoblip_start_ms
            if self.spec.autoblip_profile_ms and 0.0 <= autoblip_elapsed < self.spec.autoblip_profile_ms[-1][0]:
                gas = max(gas, interpolate_curve(self.spec.autoblip_profile_ms, autoblip_elapsed))
                autoblip_active = True

        automatic_request = self._auto_shift_decision(gas, clutch)
        auto_gas_cut_active = self._auto_gas_cutoff > 0.0
        if auto_gas_cut_active:
            self._auto_gas_cutoff = _f32(self._auto_gas_cutoff - dt)
            gas = 0.0

        requested_direction = self._manual_shift_request or automatic_request
        self._manual_shift_request = 0
        if self._accept_shift(requested_direction, clutch, dt):
            shift_started = True
            event_direction = requested_direction
        elif requested_direction:
            # A grind belongs to an actual rejected drivetrain request.  It is
            # deliberately not inferred from gear/RPM/filename state, and an
            # accepted request never emits this pulse.
            shift_rejected = True

        # Drivetrain::step2WD checks strict duration < elapsed before adding dt.
        if self.shifting:
            if self._shift_duration < self._shift_elapsed:
                self.gear = self._shift_target
                self.requested_gear = self.gear
                event_direction = self._shift_direction
                self._shift_direction = 0
                shift_completed = True
            else:
                self._shift_elapsed += float(dt)

        engine_cut_active = self._engine_cutoff > 0.0
        engine_gas = 0.0 if engine_cut_active else gas
        if engine_cut_active:
            self._engine_cutoff -= float(dt)
        (
            engine_torque,
            mapped_throttle,
            effective_throttle,
            limiter_active,
            backfire_triggered,
        ) = self._engine_torque(dt, gas, engine_gas)

        vehicle = self.spec.vehicle
        rear_radius = max(1e-6, vehicle.rear_wheel_radius_m)
        front_radius = max(1e-6, vehicle.front_wheel_radius_m)
        traction = self.spec.traction.upper()
        if traction == "FWD":
            driven_radius = front_radius
            driven_normal_fraction = vehicle.front_weight_fraction
            driven_grip = vehicle.front_grip_coefficient
        elif traction.startswith("AWD"):
            driven_radius = 0.5 * (front_radius + rear_radius)
            driven_normal_fraction = 1.0
            driven_grip = (
                vehicle.front_weight_fraction * vehicle.front_grip_coefficient
                + (1.0 - vehicle.front_weight_fraction) * vehicle.rear_grip_coefficient
            )
        else:
            driven_radius = rear_radius
            driven_normal_fraction = 1.0 - vehicle.front_weight_fraction
            driven_grip = vehicle.rear_grip_coefficient
        wheel_speed = self.speed_mps / driven_radius
        drivetrain_speed = wheel_speed
        rolling_force = 2.0 * (
            vehicle.front_rolling_resistance_0
            + vehicle.rear_rolling_resistance_0
            + (vehicle.front_rolling_resistance_1 + vehicle.rear_rolling_resistance_1)
            * self.speed_mps
            * self.speed_mps
        )
        front_brake = 2.0 * vehicle.brake_max_torque * vehicle.brake_front_share / vehicle.front_wheel_radius_m
        rear_brake = 2.0 * vehicle.brake_max_torque * (1.0 - vehicle.brake_front_share) / rear_radius
        brake_force = brake * (front_brake + rear_brake)
        total_normal = vehicle.mass_kg * 9.81 + downforce
        brake_grip = total_normal * (
            vehicle.front_weight_fraction * vehicle.front_grip_coefficient
            + (1.0 - vehicle.front_weight_fraction) * vehicle.rear_grip_coefficient
        )
        brake_force = min(brake_force, brake_grip)

        effective_mass = vehicle.mass_kg + 2.0 * vehicle.front_wheel_inertia / vehicle.front_wheel_radius_m**2 + 2.0 * vehicle.rear_wheel_inertia / rear_radius**2
        resisting_force = drag_force + rolling_force + brake_force
        clutch_torque = 0.0
        wheel_torque = 0.0
        drive_force = 0.0
        traction_torque_limited = False
        ratio = abs(self.spec.ratio_for_gear(self.gear) * self.spec.final_drive)
        engine_omega = self.rpm * RADIAN_SECOND_PER_RPM
        if ratio > 0.0 and clutch > 0.0:
            engine_inertia = self.engine_spec.engine_inertia + self.spec.gearbox_inertia
            slip = engine_omega - ratio * wheel_speed
            denominator = 1.0 / engine_inertia + ratio * ratio / (effective_mass * driven_radius * driven_radius)
            required = (
                slip / dt
                + engine_torque / engine_inertia
                + resisting_force * ratio / (effective_mass * driven_radius)
            ) / denominator
            capacity = self.spec.clutch_max_torque * clutch**1.5
            driven_normal = total_normal * driven_normal_fraction
            grip_force = driven_grip * driven_normal
            grip_capacity = grip_force * driven_radius / ratio
            traction_torque_limited = (
                effective_throttle > 0.0
                and required > grip_capacity + 1.0e-6
                and grip_capacity < capacity - 1.0e-6
            )
            clutch_torque = min(capacity, grip_capacity, max(-capacity, required))
            drive_force = clutch_torque * ratio / driven_radius
            wheel_torque = clutch_torque * ratio
            engine_omega += (engine_torque - clutch_torque) / engine_inertia * dt
        else:
            engine_omega += engine_torque / max(0.001, self.engine_spec.engine_inertia) * dt

        net_force = drive_force - resisting_force
        old_speed = self.speed_mps
        self.speed_mps = max(0.0, self.speed_mps + net_force / effective_mass * dt)
        if old_speed <= 0.0 and drive_force <= resisting_force:
            self.speed_mps = 0.0
        acceleration = (self.speed_mps - old_speed) / dt
        self.rpm = max(0.0, engine_omega * RPM_PER_RADIAN_SECOND)
        self._previous_wheel_speed = wheel_speed
        traction_limit_active = effective_throttle > 0.0 and (
            traction_torque_limited
            or self.driving_tyre_slip > self.spec.auto_slip_threshold
        )
        traction_limit_pulse = (
            traction_limit_active and not self._traction_limit_active
        )
        self._traction_limit_active = traction_limit_active
        if shift_completed and event_direction > 0 and self.gear > 1:
            # Keep actual landing RPM as telemetry, while automatic decisions
            # use the stable ratio-calculated value initialized above.
            self.last_upshift_landing_rpm = self.rpm

        phase = "engaged"
        if self.shifting:
            phase = "neutral_up" if self._shift_direction > 0 else "neutral_down"
        elif self.gear == 0:
            phase = "neutral"

        return DrivetrainFrame(
            rpm=self.rpm,
            speed_mps=self.speed_mps,
            speed_kmh=self.speed_mps * 3.6,
            speed_kph=self.speed_mps * 3.6,
            gear=self.gear,
            requested_gear=self.requested_gear,
            drivetrain_speed=self.speed_mps / driven_radius,
            driver_throttle=raw_gas,
            throttle=gas,
            effective_throttle=effective_throttle,
            mapped_throttle=mapped_throttle,
            brake=brake,
            clutch=clutch,
            boost=self.boost,
            engine_torque=engine_torque,
            clutch_torque=clutch_torque,
            wheel_torque=wheel_torque,
            longitudinal_force=net_force,
            brake_force=brake_force,
            rolling_resistance_force=rolling_force,
            aero_drag_force=drag_force,
            aero_downforce=downforce,
            acceleration_mps2=acceleration,
            driving_tyre_slip=self.driving_tyre_slip,
            limiter_active=limiter_active,
            limiter_pulse=limiter_active,
            backfire_triggered=backfire_triggered,
            bov=self.bov,
            bov_decay=self.bov_decay,
            shifting=self.shifting,
            shift_phase=phase,
            gear_direction=event_direction if (shift_started or shift_completed or self.shifting) else 0,
            shift_started=shift_started,
            shift_rejected=shift_rejected,
            gear_changed=shift_started,
            shift_completed=shift_completed,
            gear_engaged=shift_completed,
            traction_limit_active=traction_limit_active,
            traction_limit_pulse=traction_limit_pulse,
            auto_gas_cut_active=auto_gas_cut_active,
            engine_cut_active=engine_cut_active,
            autoblip_active=autoblip_active,
        )
