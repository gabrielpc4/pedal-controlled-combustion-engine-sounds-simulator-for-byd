"""Local real-time server for the Assetto Corsa tachometer/audio laboratory."""

from __future__ import annotations

import argparse
import json
import mimetypes
import signal
import sys
import threading
import time
import webbrowser
from dataclasses import asdict, replace
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from sim.assetto import find_assetto_root
from sim.car_config import CarSpec, load_car_spec
from sim.catalog import discover_bank_library, discover_cars
from sim.drivetrain import AutomaticDrivetrain, load_drivetrain_spec
from sim.fmod_native import AudioStatus, NativeFmodAudio


PROJECT_ROOT = Path(__file__).resolve().parent
WEB_ROOT = PROJECT_ROOT / "web"
PHYSICS_STEP = 0.003
PHYSICS_HZ = 1.0 / PHYSICS_STEP
AUDIO_HZ = 100.0
DEFAULT_CAR_ID = "tatuusfa1"


def _audio_dict(status: AudioStatus) -> dict[str, Any]:
    return asdict(status)


class Simulation:
    """Own the deterministic physics clock, controls, and native audio state."""

    def __init__(
        self,
        assetto_root: Path,
        *,
        bank_root: Path | None = None,
        enable_audio: bool = True,
    ):
        self.assetto_root = assetto_root
        installed_cars = discover_cars(assetto_root)
        installed_by_id = {car.id: car for car in installed_cars if car.available}
        if not installed_by_id:
            raise FileNotFoundError("Assetto root contains no complete car data for reference physics")

        self._bank_library_root = bank_root.expanduser().resolve() if bank_root is not None else None
        self.cars = (
            discover_bank_library(self._bank_library_root)
            if self._bank_library_root is not None
            else installed_cars
        )
        self._cars_by_id = {car.id: car for car in self.cars if car.available}
        if not self._cars_by_id:
            raise FileNotFoundError("no selectable car banks were found")

        reference_car_id = DEFAULT_CAR_ID if DEFAULT_CAR_ID in installed_by_id else next(iter(installed_by_id))
        self._physics_car_id_by_id = {
            car_id: reference_car_id if self._bank_library_root is not None else car_id
            for car_id in self._cars_by_id
        }
        initial_car = DEFAULT_CAR_ID if DEFAULT_CAR_ID in self._cars_by_id else next(iter(self._cars_by_id))
        self._selected_car_id = initial_car
        physics_car_id = self._physics_car_id_by_id[initial_car]
        self.spec = load_car_spec(assetto_root, physics_car_id)
        self.drivetrain_spec = load_drivetrain_spec(assetto_root, physics_car_id)
        # AC's AutoShifter contains no low-speed N->1 launch rule. Race/grid
        # session state supplies first gear, so this standalone drive begins in
        # first as an on-track automatic session does.
        self.drivetrain = AutomaticDrivetrain(
            self.spec,
            self.drivetrain_spec,
            initial_gear=1,
        )
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._audio_initialized = threading.Event()
        self._thread: threading.Thread | None = None
        self._sequence = 0
        self._pending_car: str | None = None
        self._camera = "cockpit"
        self._muted = False
        self._engine_muted = False
        self._backfire_audition_requested = False
        self._volume = 1.0
        self._throttle_mode = "analog"
        self._throttle_command = 0.0
        self._brake_mode = "analog"
        self._brake_command = 0.0
        self._neutral_requested = False
        self._enable_audio = enable_audio
        self._audio_error = "Audio mixer is starting." if enable_audio else "Native audio disabled by --no-audio."
        self.audio: NativeFmodAudio | None = None
        self._frame = self.drivetrain.step(PHYSICS_STEP)

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="ac-physics-audio", daemon=True)
        self._thread.start()
        self._audio_initialized.wait(timeout=5.0)

    def _disable_audio(self, exc: Exception) -> None:
        self._audio_error = f"Audio stopped after {type(exc).__name__}: {exc}"
        audio, self.audio = self.audio, None
        if audio is not None:
            try:
                audio.close()
            except Exception:
                pass

    def _run(self) -> None:
        if self._enable_audio:
            try:
                audio = NativeFmodAudio(
                    self.assetto_root,
                    self.spec,
                    bank_path=Path(self._cars_by_id[self._selected_car_id].bank),
                    initial_camera=self._camera,
                )
                audio.configure(camera=self._camera, muted=self._muted, engine_muted=self._engine_muted, volume=self._volume)
                with self._lock:
                    self.audio = audio
                    self._audio_error = ""
            except Exception as exc:
                with self._lock:
                    self._audio_error = f"{type(exc).__name__}: {exc}"
        self._audio_initialized.set()

        physics_step = PHYSICS_STEP
        audio_step = 1.0 / AUDIO_HZ
        previous = time.perf_counter()
        accumulator = 0.0
        audio_accumulator = 0.0
        pending_backfire = False
        pending_limiter_pulse = False
        pending_shift_started = False
        pending_shift_rejected = False
        pending_traction_limit_pulse = False
        pending_gear_direction = 0
        pending_backfire_audition = False
        neutral_active = False

        try:
            while not self._stop.is_set():
                now = time.perf_counter()
                elapsed = min(0.05, max(0.0, now - previous))
                previous = now
                accumulator += elapsed
                audio_accumulator += elapsed

                with self._lock:
                    pending_car, self._pending_car = self._pending_car, None
                if pending_car is not None:
                    try:
                        new_entry = self._cars_by_id[pending_car]
                        physics_car_id = self._physics_car_id_by_id[pending_car]
                        new_spec = load_car_spec(self.assetto_root, physics_car_id)
                        new_drivetrain_spec = load_drivetrain_spec(self.assetto_root, physics_car_id)
                        new_drivetrain = AutomaticDrivetrain(new_spec, new_drivetrain_spec, initial_gear=1)
                        new_audio = None
                        switch_audio_error = ""
                        if self._enable_audio:
                            try:
                                new_audio = NativeFmodAudio(
                                    self.assetto_root,
                                    new_spec,
                                    bank_path=Path(new_entry.bank),
                                    initial_camera=self._camera,
                                )
                                new_audio.configure(camera=self._camera, muted=self._muted, engine_muted=self._engine_muted, volume=self._volume)
                            except Exception as audio_exc:
                                switch_audio_error = f"{type(audio_exc).__name__}: {audio_exc}"
                        old_audio = self.audio
                        with self._lock:
                            self.spec = new_spec
                            self.drivetrain_spec = new_drivetrain_spec
                            self.drivetrain = new_drivetrain
                            self._selected_car_id = pending_car
                            if self._neutral_requested:
                                new_drivetrain.set_auto_shift(False)
                                new_drivetrain.set_gear(0)
                            self._frame = new_drivetrain.step(PHYSICS_STEP)
                            self.audio = new_audio
                            self._audio_error = switch_audio_error
                            self._throttle_command = self._brake_command = 0.0
                            self._sequence += 1
                        if old_audio is not None:
                            old_audio.close()
                        accumulator = audio_accumulator = 0.0
                        pending_backfire = pending_limiter_pulse = pending_shift_started = False
                        pending_shift_rejected = pending_traction_limit_pulse = False
                        pending_backfire_audition = False
                        neutral_active = self._neutral_requested
                    except Exception as exc:
                        with self._lock:
                            self._audio_error = f"Car switch failed: {type(exc).__name__}: {exc}"

                with self._lock:
                    camera, muted, engine_muted, volume = self._camera, self._muted, self._engine_muted, self._volume
                    audition_requested, self._backfire_audition_requested = self._backfire_audition_requested, False
                pending_backfire_audition = pending_backfire_audition or audition_requested

                advanced = False
                while accumulator >= physics_step:
                    # Sample control state at every recovered 3 ms tick. This
                    # avoids replaying a stale pedal through a catch-up batch
                    # if an FMOD update briefly occupied the worker thread.
                    with self._lock:
                        throttle = self._throttle_command
                        throttle_mode = self._throttle_mode
                        brake = self._brake_command
                        brake_mode = self._brake_mode
                        neutral_requested = self._neutral_requested
                    if neutral_requested:
                        # Neutral is a persistent transmission mode, not an
                        # automatic launch state. Keep automatic shifting off
                        # so a free-rev cannot select first gear by itself.
                        self.drivetrain.set_auto_shift(False)
                        self.drivetrain.set_gear(0)
                        neutral_active = True
                    elif neutral_active:
                        self.drivetrain.set_gear(1)
                        self.drivetrain.set_auto_shift(True)
                        neutral_active = False
                    if throttle_mode == "keyboard":
                        self.drivetrain.set_keyboard_throttle(throttle >= 0.5)
                    else:
                        self.drivetrain.set_throttle(throttle)
                    if brake_mode == "keyboard":
                        self.drivetrain.set_keyboard_brake(brake >= 0.5)
                    else:
                        self.drivetrain.set_brake(brake)
                    frame = self.drivetrain.step(physics_step)
                    pending_backfire = pending_backfire or frame.backfire_triggered
                    pending_limiter_pulse = pending_limiter_pulse or frame.limiter_pulse
                    pending_shift_rejected = pending_shift_rejected or frame.shift_rejected
                    pending_traction_limit_pulse = (
                        pending_traction_limit_pulse or frame.traction_limit_pulse
                    )
                    if frame.shift_started:
                        pending_shift_started = True
                        pending_gear_direction = frame.gear_direction
                    accumulator -= physics_step
                    advanced = True
                    with self._lock:
                        self._frame = frame
                        self._sequence += 1

                if self.audio is not None and audio_accumulator >= audio_step:
                    dt = audio_accumulator
                    audio_accumulator = 0.0
                    try:
                        self.audio.configure(camera=camera, muted=muted, engine_muted=engine_muted, volume=volume)
                        audio_frame = replace(
                            self._frame,
                            backfire_triggered=pending_backfire,
                            limiter_pulse=pending_limiter_pulse,
                            shift_started=pending_shift_started,
                            shift_rejected=pending_shift_rejected,
                            gear_changed=pending_shift_started,
                            gear_direction=pending_gear_direction,
                            traction_limit_pulse=pending_traction_limit_pulse,
                        )
                        self.audio.update(audio_frame, dt, audition_backfire=pending_backfire_audition)
                        pending_backfire = False
                        pending_limiter_pulse = False
                        pending_shift_started = False
                        pending_shift_rejected = False
                        pending_traction_limit_pulse = False
                        pending_gear_direction = 0
                        pending_backfire_audition = False
                    except Exception as exc:
                        with self._lock:
                            self._disable_audio(exc)

                if not advanced:
                    self._stop.wait(min(0.0015, max(0.0002, physics_step - accumulator)))
        finally:
            # FMOD 1.08 event handles are thread-affine in this integration:
            # create, update and release them on this one dedicated thread.
            audio, self.audio = self.audio, None
            if audio is not None:
                audio.close()
            self._audio_initialized.set()

    def apply_control(self, payload: dict[str, Any]) -> None:
        allowed = {
            "throttle",
            "throttleMode",
            "brake",
            "brakeMode",
            "camera",
            "muted",
            "engineMuted",
            "auditionBackfire",
            "volume",
            "car",
            "neutral",
        }
        unknown = set(payload) - allowed
        if unknown:
            raise ValueError(f"unknown control(s): {', '.join(sorted(unknown))}")
        with self._lock:
            if "car" in payload:
                car_id = payload["car"]
                if not isinstance(car_id, str) or car_id not in self._cars_by_id:
                    raise ValueError("car must identify an available installed car")
                if car_id != self.spec.car_id:
                    self._pending_car = car_id
            if "throttleMode" in payload:
                mode = payload["throttleMode"]
                if mode not in ("analog", "keyboard"):
                    raise ValueError("throttleMode must be analog or keyboard")
                self._throttle_mode = mode
            if "throttle" in payload:
                value = payload["throttle"]
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    raise ValueError("throttle must be a number from 0 to 1")
                self._throttle_command = min(1.0, max(0.0, float(value)))
                if "throttleMode" not in payload:
                    self._throttle_mode = "analog"
            if "brakeMode" in payload:
                mode = payload["brakeMode"]
                if mode not in ("analog", "keyboard"):
                    raise ValueError("brakeMode must be analog or keyboard")
                self._brake_mode = mode
            if "brake" in payload:
                value = payload["brake"]
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    raise ValueError("brake must be a number from 0 to 1")
                self._brake_command = min(1.0, max(0.0, float(value)))
                if "brakeMode" not in payload:
                    self._brake_mode = "analog"
            if "neutral" in payload:
                if not isinstance(payload["neutral"], bool):
                    raise ValueError("neutral must be true or false")
                self._neutral_requested = payload["neutral"]
            if "camera" in payload:
                camera = payload["camera"]
                if camera not in NativeFmodAudio.cameras:
                    raise ValueError("camera must be cockpit, bonnet, or exhaust")
                self._camera = camera
            if "muted" in payload:
                if not isinstance(payload["muted"], bool):
                    raise ValueError("muted must be true or false")
                self._muted = payload["muted"]
            if "engineMuted" in payload:
                if not isinstance(payload["engineMuted"], bool):
                    raise ValueError("engineMuted must be true or false")
                self._engine_muted = payload["engineMuted"]
            if "auditionBackfire" in payload:
                if payload["auditionBackfire"] is not True:
                    raise ValueError("auditionBackfire must be true")
                self._backfire_audition_requested = True
            if "volume" in payload:
                value = payload["volume"]
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    raise ValueError("volume must be a number from 0 to 1")
                self._volume = min(1.0, max(0.0, float(value)))

    def audio_status(self) -> AudioStatus:
        with self._lock:
            if self.audio is not None:
                return self.audio.status()
            return AudioStatus(
                available=False,
                backend="silent physics mode",
                bank=self._cars_by_id[self._selected_car_id].bank,
                detail=self._audio_error or "Native audio unavailable.",
                camera=self._camera,
                muted=self._muted,
                engine_muted=self._engine_muted,
                volume=self._volume,
                events=(),
            )

    def state(self) -> dict[str, Any]:
        with self._lock:
            frame = self._frame
            return {
                "sequence": self._sequence,
                "carId": self._selected_car_id,
                "carName": self._cars_by_id[self._selected_car_id].name,
                "serverTime": time.time(),
                "rpm": round(frame.rpm, 4),
                "speedMps": round(frame.speed_mps, 6),
                "speedKph": round(frame.speed_kph, 4),
                "gear": frame.gear,
                "gearLabel": "R" if frame.gear < 0 else "N" if frame.gear == 0 else str(frame.gear),
                "requestedGear": frame.requested_gear,
                "automaticDownshiftRpm": round(self.drivetrain.automatic_downshift_rpm, 2),
                "lastUpshiftLandingRpm": round(self.drivetrain.last_upshift_landing_rpm, 2),
                "automatic": self.drivetrain.automatic_shifting,
                "neutral": self._neutral_requested,
                "shifting": frame.shifting,
                "shiftPhase": frame.shift_phase,
                "shiftStarted": frame.shift_started,
                "shiftRejected": frame.shift_rejected,
                "shiftCompleted": frame.shift_completed,
                "gearDirection": frame.gear_direction,
                "drivetrainSpeed": round(frame.drivetrain_speed, 6),
                "driverThrottle": round(frame.driver_throttle, 6),
                "throttle": round(frame.throttle, 6),
                "throttleMode": self._throttle_mode,
                "mappedThrottle": round(frame.mapped_throttle, 6),
                "effectiveThrottle": round(frame.effective_throttle, 6),
                "brake": round(frame.brake, 6),
                "brakeMode": self._brake_mode,
                "clutch": round(frame.clutch, 6),
                "boost": round(frame.boost, 6),
                "limiterActive": frame.limiter_active,
                "limiterPulse": frame.limiter_pulse,
                "backfireTriggered": frame.backfire_triggered,
                "bov": round(frame.bov, 6),
                "bovDecay": round(frame.bov_decay, 6),
                "tractionLimitActive": frame.traction_limit_active,
                "tractionLimitPulse": frame.traction_limit_pulse,
                "torque": round(frame.engine_torque, 4),
                "wheelTorque": round(frame.wheel_torque, 4),
                "accelerationMps2": round(frame.acceleration_mps2, 6),
                "autoGasCutActive": frame.auto_gas_cut_active,
                "engineCutActive": frame.engine_cut_active,
                "autoblipActive": frame.autoblip_active,
                "vehicleDynamicsExact": frame.vehicle_dynamics_exact,
                "camera": self._camera,
                "muted": self._muted,
                "engineMuted": self._engine_muted,
                "volume": self._volume,
                "audio": _audio_dict(self.audio_status()),
            }

    def config(self) -> dict[str, Any]:
        spec = self.spec
        audio_status = self.audio_status()
        return {
            "defaultCarId": DEFAULT_CAR_ID,
            "car": {
                "id": self._selected_car_id,
                "name": self._cars_by_id[self._selected_car_id].name,
            },
            "cars": [car.json() for car in self.cars],
            "engine": {
                "idleRpm": spec.idle_rpm,
                "limiterRpm": spec.limiter_rpm,
                "limiterHz": spec.limiter_hz,
                "tachometerMaximum": spec.tachometer_maximum,
                "engineInertia": spec.engine_inertia,
                "gearboxInertia": spec.gearbox_inertia,
                "shiftLights": list(spec.shift_lights),
                "shiftBlinkRpm": spec.shift_blink_rpm,
                "shiftBlinkHz": spec.shift_blink_hz,
            },
            "automatic": not self._neutral_requested,
            "neutral": self._neutral_requested,
            "transmission": {
                "automatic": not self._neutral_requested,
                "neutral": self._neutral_requested,
                "neutralAvailable": True,
                "initialGear": 1,
                "forwardRatios": list(self.drivetrain_spec.forward_ratios),
                "reverseRatio": self.drivetrain_spec.reverse_ratio,
                "finalDrive": self.drivetrain_spec.final_drive,
                "upshiftRpm": self.drivetrain_spec.auto_up_rpm,
                "downshiftRpm": round(self.drivetrain.automatic_downshift_rpm, 2),
                "downshiftMode": "calculated-gear-ratio-landing",
                "upshiftTimeMs": self.drivetrain_spec.gear_up_time_s * 1000.0,
                "downshiftTimeMs": self.drivetrain_spec.gear_down_time_s * 1000.0,
                "engineCutTimeMs": self.drivetrain_spec.auto_cutoff_time_s * 1000.0,
                "assistGasCutTimeMs": self.drivetrain_spec.auto_gas_cutoff_s * 1000.0,
                "clutchMaxTorque": self.drivetrain_spec.clutch_max_torque,
                "autoclutchMinimumRpm": self.drivetrain_spec.autoclutch_min_rpm,
                "autoclutchMaximumRpm": self.drivetrain_spec.autoclutch_max_rpm,
            },
            "physicsHz": PHYSICS_HZ,
            "audioHz": AUDIO_HZ,
            "audioThrottle": 1.0,
            "backfireAudioThrottle": 0.01,
            "keyboardThrottleRate": 4.0,
            "vehicleDynamicsExact": False,
            "turbo": (
                {
                    "maxBoost": spec.turbo.maximum_boost,
                    "wastegate": spec.turbo.wastegate,
                    "displayMaxBoost": spec.turbo.display_max_boost,
                    "referenceRpm": spec.turbo.reference_rpm,
                    "gamma": spec.turbo.gamma,
                }
                if spec.turbo is not None
                else None
            ),
            "audio": _audio_dict(audio_status),
            "availableSoundEvents": list(audio_status.events),
            "soundEvents": [
                "engine_int",
                "engine_ext",
                "gear_int",
                "gear_ext",
                "transmission",
                "turbo",
                "limiter",
                "backfire_int",
                "backfire_ext",
                "start",
                "transmission_ext",
                "tractioncontrol_int",
                "tractioncontrol_ext",
                "gear_grind",
            ],
            "audioScope": "powertrain",
            "microphones": [
                {
                    "id": "cockpit",
                    "name": "Cockpit",
                    "event": "engine_int",
                    "position": list(spec.driver_eyes),
                    "description": "Kunos's authored interior engine/exhaust layers and cabin processing.",
                },
                {
                    "id": "bonnet",
                    "name": "Bonnet",
                    "event": "engine_ext",
                    "position": list(spec.bonnet_camera),
                    "description": "The external event heard from the car's exact BONNET_CAMERA_POS.",
                },
                {
                    "id": "exhaust",
                    "name": "Exhaust",
                    "event": "engine_ext",
                    "position": [0.0, 0.31, "0.82 m behind rear engine anchor"],
                    "description": "The same external 3D event, with its authored rear cone, heard beside the tailpipe.",
                },
            ],
            "assettoRoot": str(self.assetto_root),
        }

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None


class RequestHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ACSoundLab/1.0"

    def __init__(self, *args: Any, **kwargs: Any):
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    @property
    def simulation(self) -> Simulation:
        return self.server.simulation  # type: ignore[attr-defined, no-any-return]

    def _json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        parsed = urlsplit(self.path)
        path = parsed.path
        if path == "/api/config":
            self._json(self.simulation.config())
            return
        if path == "/api/state":
            self._json(self.simulation.state())
            return
        if path == "/api/cars":
            self._json([car.json() for car in self.simulation.cars])
            return
        if path == "/api/car-image":
            requested = parse_qs(parsed.query).get("car", [self.simulation.spec.car_id])[0]
            entry = self.simulation._cars_by_id.get(requested)
            if entry is None:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            physics_car_id = self.simulation._physics_car_id_by_id[requested]
            skins = self.simulation.assetto_root / "content" / "cars" / physics_car_id / "skins"
            candidates = sorted(skins.glob("*/preview.jpg")) + sorted(skins.glob("*/preview.png"))
            if not candidates:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            image_path = candidates[0]
            body = image_path.read_bytes()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/stream":
            self._stream()
            return
        if path == "/":
            self.path = "/index.html"
        super().do_GET()

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
        if urlsplit(self.path).path != "/api/control":
            self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 16384:
                raise ValueError("request body must contain a small JSON object")
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            self.simulation.apply_control(payload)
        except (ValueError, json.JSONDecodeError) as exc:
            self._json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
            return
        self._json(self.simulation.state())

    def _stream(self) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            while True:
                data = json.dumps(self.simulation.state(), separators=(",", ":"))
                self.wfile.write(f"event: state\ndata: {data}\n\n".encode("utf-8"))
                self.wfile.flush()
                time.sleep(1.0 / 30.0)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def end_headers(self) -> None:
        if not self.path.startswith("/api/"):
            self.send_header("Cross-Origin-Opener-Policy", "same-origin")
            self.send_header("X-Frame-Options", "DENY")
        super().end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        if self.path != "/api/stream":
            print(f"[http] {self.address_string()} {format % args}")


class SimulatorServer(ThreadingHTTPServer):
    daemon_threads = True
    # On Windows SO_REUSEADDR can let two independent simulator processes bind
    # the same port and load-balance requests between different physics states.
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], simulation: Simulation):
        self.simulation = simulation
        super().__init__(address, RequestHandler)

    def handle_error(self, request: object, client_address: object) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, (BrokenPipeError, ConnectionResetError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)  # type: ignore[arg-type]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assetto-root", help="Assetto Corsa game directory")
    parser.add_argument("--bank-root", help="directory of standalone Assetto Corsa .bank files")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-audio", action="store_true", help="run physics/UI without opening an audio device")
    parser.add_argument("--no-browser", action="store_true", help="do not open the local UI automatically")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = find_assetto_root(args.assetto_root)
        simulation = Simulation(
            root,
            bank_root=Path(args.bank_root) if args.bank_root else None,
            enable_audio=not args.no_audio,
        )
    except Exception as exc:
        print(f"startup failed: {exc}", file=sys.stderr)
        return 1

    server = SimulatorServer((args.host, args.port), simulation)
    url = f"http://{args.host}:{server.server_address[1]}/"
    stop_once = threading.Event()

    def request_stop(_signum: int | None = None, _frame: object | None = None) -> None:
        if stop_once.is_set():
            return
        stop_once.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, request_stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, request_stop)

    simulation.start()
    status = simulation.audio_status()
    print(f"Assetto Corsa: {root}")
    print(f"Car: {simulation.state()['carName']}")
    print(f"Audio: {status.backend} ({'ready' if status.available else status.detail})")
    print(f"Open: {url}")
    if not args.no_browser:
        threading.Timer(0.5, webbrowser.open, args=(url,)).start()

    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        simulation.close()
        print("Simulator stopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
