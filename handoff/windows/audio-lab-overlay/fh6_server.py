"""Standalone local server for the Forza Horizon 6 virtual powertrain."""

from __future__ import annotations

import argparse
import json
import signal
import sys
import threading
import time
import webbrowser
from dataclasses import asdict
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from fh6.audio import FH6AudioEngine
from fh6.config import find_fh6_root, load_reference_config
from fh6.input import PowertrainControl, PredictiveResampler, Selector, VehicleSample
from fh6.powertrain import FH6Powertrain, PowertrainFrame
from fh6.vehicle_dynamics import BYDSealAWDModel


PROJECT_ROOT = Path(__file__).resolve().parent
WEB_ROOT = PROJECT_ROOT / "fh6_web"
CONTROL_HZ = 1000.0
CONTROL_STEP = 1.0 / CONTROL_HZ


class FH6Simulation:
    def __init__(self, root: Path, *, cache_root: Path | None = None):
        self.root = root
        self.cache_root = cache_root or (PROJECT_ROOT / ".fh6-cache")
        self.config_data = load_reference_config(root, cache_root=self.cache_root)
        self.resampler = PredictiveResampler()
        self.powertrain = FH6Powertrain(self.config_data)
        self.audio = FH6AudioEngine(self.config_data, self.cache_root / "audio")
        self.vehicle_dynamics = BYDSealAWDModel()
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._sequence = 0
        self._input_sequence = 0
        self._mock_mode = "manual"
        self._mock_speed = 0.0
        self._mock_throttle = 0.0
        self._mock_brake = 0.0
        self._mock_rate_hz = 20.0
        self._mock_next_sample = 0.0
        self._scenario_elapsed = 0.0
        self._pending_control = PowertrainControl(False, Selector.PARK)
        self._control_rejected = ""
        self._frame = self.powertrain.step(CONTROL_STEP, self.resampler.sample(time.monotonic_ns(), CONTROL_STEP))
        self._parameters: dict[str, object] = {}
        self._audition_requested = False

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="fh6-powertrain", daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3.0)
            self._thread = None

    def submit(self, sample: VehicleSample) -> bool:
        accepted = self.resampler.submit(sample)
        if accepted:
            with self._lock:
                self._input_sequence += 1
        return accepted

    def _scenario(self, dt: float) -> None:
        with self._lock:
            mode = self._mock_mode
            self._scenario_elapsed += dt
            if mode == "launch":
                self._mock_throttle, self._mock_brake = 100.0, 0.0
            elif mode == "coast":
                self._mock_throttle, self._mock_brake = 0.0, 0.0
            elif mode == "brake":
                self._mock_throttle, self._mock_brake = 0.0, 72.0
            elif mode == "cycle":
                phase = self._scenario_elapsed % 18.0
                if phase < 9.0:
                    self._mock_throttle, self._mock_brake = 86.0, 0.0
                elif phase < 14.0:
                    self._mock_throttle, self._mock_brake = 0.0, 0.0
                else:
                    self._mock_throttle, self._mock_brake = 0.0, 80.0
            if mode != "external":
                dynamics = self.vehicle_dynamics.step(
                    dt,
                    self._mock_throttle,
                    self._mock_brake,
                    ignition=self.powertrain.control.ignition,
                    selector=self.powertrain.control.selector,
                )
                self._mock_speed = dynamics.speed_kph

    def _emit_mock(self, now: float) -> None:
        with self._lock:
            if self._mock_mode in {"dropout", "external"} or now < self._mock_next_sample:
                return
            interval = 1.0 / self._mock_rate_hz
            self._mock_next_sample = now + interval
            sample = VehicleSample(
                timestamp_ns=time.monotonic_ns(),
                speed_kph=self._mock_speed,
                throttle_pct=self._mock_throttle,
                brake_pct=self._mock_brake,
            )
        self.submit(sample)

    def _run(self) -> None:
        previous = time.perf_counter()
        accumulator = 0.0
        while not self._stop.is_set():
            now = time.perf_counter()
            elapsed = min(0.05, max(0.0, now - previous))
            previous = now
            accumulator += elapsed
            self._scenario(elapsed)
            self._emit_mock(now)
            advanced = False
            while accumulator >= CONTROL_STEP:
                current_ns = time.monotonic_ns()
                vehicle = self.resampler.sample(current_ns, CONTROL_STEP)
                with self._lock:
                    requested = self._pending_control
                    if requested != self.powertrain.control:
                        accepted = self.powertrain.set_control(requested, vehicle.speed_kph)
                        self._control_rejected = "" if accepted else (
                            f"Selector {requested.selector.value} rejected above "
                            f"{self.config_data.drivetrain.low_speed_selector_limit_kph:g} km/h"
                        )
                        if not accepted:
                            self._pending_control = self.powertrain.control
                    audition = self._audition_requested
                    self._audition_requested = False
                if audition:
                    self.audio.audition_pops()
                frame = self.powertrain.step(CONTROL_STEP, vehicle)
                parameters = self.audio.update(frame)
                with self._lock:
                    self._frame = frame
                    self._parameters = parameters
                    self._sequence += 1
                accumulator -= CONTROL_STEP
                advanced = True
            if not advanced:
                self._stop.wait(0.0005)

    @staticmethod
    def _number(payload: dict[str, Any], key: str, minimum: float, maximum: float) -> float:
        value = payload[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{key} must be a number")
        value = float(value)
        if not minimum <= value <= maximum:
            raise ValueError(f"{key} must be from {minimum:g} to {maximum:g}")
        return value

    def apply_control(self, payload: dict[str, Any]) -> None:
        allowed = {
            "ignition", "selector", "camera", "muted", "isolated", "authenticLevels",
            "auditionPops", "mockMode", "speedKph", "throttlePct", "brakePct", "inputRateHz",
        }
        unknown = set(payload) - allowed
        if unknown:
            raise ValueError(f"unknown control(s): {', '.join(sorted(unknown))}")
        with self._lock:
            ignition = self._pending_control.ignition
            selector = self._pending_control.selector
            if "ignition" in payload:
                if not isinstance(payload["ignition"], bool):
                    raise ValueError("ignition must be true or false")
                ignition = payload["ignition"]
            if "selector" in payload:
                try:
                    selector = Selector(str(payload["selector"]))
                except ValueError as exc:
                    raise ValueError("selector must be P, R, N, or D") from exc
            self._pending_control = PowertrainControl(ignition, selector)
            if "camera" in payload or "muted" in payload or "isolated" in payload or "authenticLevels" in payload:
                self.audio.configure(
                    camera=payload.get("camera"),
                    muted=payload.get("muted"),
                    isolated=payload.get("isolated"),
                    authentic_levels=payload.get("authenticLevels"),
                )
            if payload.get("auditionPops") is True:
                self._audition_requested = True
            elif "auditionPops" in payload:
                raise ValueError("auditionPops is a one-shot and must be true")
            if "mockMode" in payload:
                mode = payload["mockMode"]
                if mode not in {"manual", "launch", "coast", "brake", "cycle", "dropout"}:
                    raise ValueError("invalid mockMode")
                self._mock_mode = mode
                self._scenario_elapsed = 0.0
            if "speedKph" in payload:
                self._mock_speed = self._number(payload, "speedKph", 0.0, 500.0)
                self.vehicle_dynamics.set_speed(self._mock_speed)
            if "throttlePct" in payload:
                self._mock_throttle = self._number(payload, "throttlePct", 0.0, 100.0)
            if "brakePct" in payload:
                self._mock_brake = self._number(payload, "brakePct", 0.0, 100.0)
            if "inputRateHz" in payload:
                rate = self._number(payload, "inputRateHz", 1.0, 120.0)
                self._mock_rate_hz = rate
                self._mock_next_sample = 0.0

    def apply_sample(self, payload: dict[str, Any]) -> bool:
        allowed = {"timestampNs", "speedKph", "throttlePct", "brakePct"}
        unknown = set(payload) - allowed
        if unknown:
            raise ValueError(f"unknown sample field(s): {', '.join(sorted(unknown))}")
        for required in ("speedKph", "throttlePct", "brakePct"):
            if required not in payload:
                raise ValueError(f"missing {required}")
        timestamp = payload.get("timestampNs", time.monotonic_ns())
        if isinstance(timestamp, bool) or not isinstance(timestamp, int):
            raise ValueError("timestampNs must be an integer")
        sample = VehicleSample(
            timestamp_ns=timestamp,
            speed_kph=self._number(payload, "speedKph", -500.0, 500.0),
            throttle_pct=self._number(payload, "throttlePct", 0.0, 100.0),
            brake_pct=self._number(payload, "brakePct", 0.0, 100.0),
        )
        with self._lock:
            # The first real-adapter packet takes ownership of the shared
            # contract until the UI explicitly selects a mock scenario again.
            self._mock_mode = "external"
        return self.submit(sample)

    def state(self) -> dict[str, Any]:
        with self._lock:
            frame = self._frame
            result = {
                "sequence": self._sequence,
                "inputSequence": self._input_sequence,
                "serverTime": time.time(),
                **{
                    "rpm": round(frame.rpm, 4),
                    "roadCoupledRpm": round(frame.road_coupled_rpm, 4),
                    "speedKph": round(frame.speed_kph, 5),
                    "accelerationMps2": round(frame.acceleration_mps2, 5),
                    "throttlePct": round(frame.throttle * 100.0, 4),
                    "brakePct": round(frame.brake * 100.0, 4),
                    "boost": round(frame.boost, 5),
                    "selector": frame.selector,
                    "ignition": frame.ignition,
                    "gear": frame.gear,
                    "gearLabel": frame.gear_label,
                    "shifting": frame.shifting,
                    "shiftPhase": frame.shift_phase,
                    "shiftProgress": round(frame.shift_progress, 5),
                    "shiftStarted": frame.shift_started,
                    "shiftCompleted": frame.shift_completed,
                    "shiftDirection": frame.shift_direction,
                    "startupTriggered": frame.startup_triggered,
                    "bovTriggered": frame.bov_triggered,
                    "burbleTriggered": frame.burble_triggered,
                    "backfireTriggered": frame.backfire_triggered,
                    "antiLagActive": frame.anti_lag_active,
                    "limiterActive": frame.limiter_active,
                    "inputStale": frame.input_stale,
                    "inputDropout": frame.input_dropout,
                    "ratioFidelityExact": frame.ratio_fidelity_exact,
                },
                "mock": {
                    "mode": self._mock_mode,
                    "speedKph": round(self._mock_speed, 4),
                    "throttlePct": round(self._mock_throttle, 4),
                    "brakePct": round(self._mock_brake, 4),
                    "inputRateHz": self._mock_rate_hz,
                    "dynamics": {
                        "model": "BYD Seal AWD chart-calibrated",
                        "accelerationMps2": round(self.vehicle_dynamics.frame.acceleration_mps2, 5),
                        "driveForceN": round(self.vehicle_dynamics.frame.drive_force_n, 3),
                        "regenForceN": round(self.vehicle_dynamics.frame.regen_force_n, 3),
                        "frictionBrakeForceN": round(self.vehicle_dynamics.frame.friction_brake_force_n, 3),
                        "resistanceForceN": round(self.vehicle_dynamics.frame.resistance_force_n, 3),
                        "regenPowerKw": round(self.vehicle_dynamics.frame.regen_power_kw, 4),
                        "recoveredEnergyKwh": round(self.vehicle_dynamics.frame.recovered_energy_kwh, 7),
                    },
                },
                "controlRejected": self._control_rejected,
                "camera": self.audio.camera,
                "muted": self.audio.muted,
                "isolated": self.audio.isolated,
                "authenticLevels": self.audio.authentic_levels,
                "audio": self.audio.status().json(),
                "audioParameters": dict(self._parameters),
            }
            return result

    def config(self) -> dict[str, Any]:
        return {
            "app": "Forza Horizon 6 Virtual Powertrain",
            "referenceCar": self.config_data.json(),
            "controlHz": CONTROL_HZ,
            "adapterContract": {
                "VehicleSample": {"timestampNs": "integer", "speedKph": "float", "throttlePct": "0..100", "brakePct": "0..100"},
                "PowertrainControl": {"ignition": "boolean", "selector": ["P", "R", "N", "D"]},
            },
            "cameras": list(self.audio.cameras),
            "mockRatesHz": [5, 10, 20, 60],
            "mockVehicle": {
                "name": "BYD Seal AWD",
                "calibration": "user-supplied 408 kW / 3.97 s / 190 km/h trace",
                **self.vehicle_dynamics.spec.json(),
            },
            "audioScope": "core-powertrain-only",
            "excludedSounds": ["tyres", "brakes", "chassis", "damage"],
            "fidelityGatePassed": self.audio.status().available and self.config_data.drivetrain.exact_from_installed_database,
            "fh6Root": str(self.root),
            "cacheRoot": str(self.cache_root),
        }


class RequestHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FH6Powertrain/0.1"

    def __init__(self, *args: Any, **kwargs: Any):
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    @property
    def simulation(self) -> FH6Simulation:
        return self.server.simulation  # type: ignore[attr-defined,no-any-return]

    def _json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        if path == "/api/config":
            self._json(self.simulation.config())
            return
        if path == "/api/state":
            self._json(self.simulation.state())
            return
        if path == "/api/stream":
            self._stream()
            return
        if path == "/":
            self.path = "/index.html"
        super().do_GET()

    def do_POST(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        if path not in {"/api/control", "/api/sample"}:
            self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 16384:
                raise ValueError("request body must contain a small JSON object")
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            if path == "/api/control":
                self.simulation.apply_control(payload)
                accepted = True
            else:
                accepted = self.simulation.apply_sample(payload)
        except (ValueError, json.JSONDecodeError) as exc:
            self._json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
            return
        self._json({"accepted": accepted, "state": self.simulation.state()})

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

    def log_message(self, format: str, *args: Any) -> None:
        if not self.path.startswith("/api/stream"):
            print(f"[fh6-http] {self.address_string()} {format % args}")


class FH6Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], simulation: FH6Simulation):
        self.simulation = simulation
        super().__init__(address, RequestHandler)

    def handle_error(self, request: object, client_address: object) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, (BrokenPipeError, ConnectionResetError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)  # type: ignore[arg-type]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fh6-root", help="Forza Horizon 6 game directory")
    parser.add_argument("--cache-root", help="versioned local decode cache (outside game files)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--no-browser", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = find_fh6_root(args.fh6_root)
        simulation = FH6Simulation(root, cache_root=Path(args.cache_root).resolve() if args.cache_root else None)
    except Exception as exc:
        print(f"FH6 startup failed: {exc}", file=sys.stderr)
        return 1
    server = FH6Server((args.host, args.port), simulation)
    url = f"http://{args.host}:{server.server_address[1]}/"
    stop_once = threading.Event()

    def stop(_signum: int | None = None, _frame: object | None = None) -> None:
        if not stop_once.is_set():
            stop_once.set()
            threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop)
    simulation.start()
    print(f"Forza Horizon 6: {root}")
    print(f"Reference: {simulation.config_data.display_name} / {simulation.config_data.upgrade}")
    print(f"Audio fidelity gate: {simulation.audio.status().detail}")
    print(f"Open: {url}")
    if not args.no_browser:
        threading.Timer(0.5, webbrowser.open, args=(url,)).start()
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        simulation.close()
        print("FH6 virtual powertrain stopped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
