"use strict";

(() => {
  const FAVORITES_KEY = "assetto-audio-lab.favorite-cars.v1";
  const DEFAULT_CONFIG = Object.freeze({
    carId: "tatuusfa1",
    carName: "Tatuus FA01",
    tachMax: 7000,
    idleRpm: 1250,
    limiterRpm: 6500,
    limiterHz: 30,
    shiftLights: [5800, 5900, 6000, 6100, 6200],
    shiftBlinkRpm: 6300,
    shiftBlinkHz: 5,
    maxBoost: 1.38,
    bankName: "tatuusfa1.bank",
    fmodVersion: "FMOD 1.08.12"
  });

  const MODE_INFO = Object.freeze({
    cockpit: {
      index: "01 / 03",
      event: "engine_int",
      label: "COCKPIT",
      x: 228,
      y: 112,
      cx: 210,
      cy: 108,
      description: "Plays the native interior engine event, with the cabin character authored into the bank."
    },
    bonnet: {
      index: "02 / 03",
      event: "engine_ext",
      label: "BONNET",
      x: 228,
      y: 45,
      cx: 210,
      cy: 41,
      description: "Plays the native external engine event with the listener placed at the car's bonnet camera position."
    },
    exhaust: {
      index: "03 / 03",
      event: "engine_ext",
      label: "EXHAUST",
      x: 228,
      y: 204,
      cx: 210,
      cy: 200,
      description: "Uses the same spatialized external event, but moves the listener behind the exhaust so FMOD's cone and distance response change naturally."
    }
  });

  const dom = {};
  let favoriteCars = new Set();
  try {
    const storedFavorites = JSON.parse(localStorage.getItem(FAVORITES_KEY) || "[]");
    if (Array.isArray(storedFavorites)) favoriteCars = new Set(storedFavorites.map(String));
  } catch (_) { /* Keep session-only favorites if storage is unavailable. */ }
  const controlSources = new Set();
  const brakeSources = new Set();
  let config = { ...DEFAULT_CONFIG };
  let target = {
    rpm: DEFAULT_CONFIG.idleRpm,
    driverThrottle: 0,
    throttle: 0,
    boost: 0,
    speedKph: 0,
    gear: 0,
    gearLabel: "N",
    shiftPhase: "ready",
    shifting: false,
    clutch: 1,
    drivetrainSpeed: 0,
    brake: 0,
    throttleMode: "analog",
    brakeMode: "analog",
    automatic: true,
    neutral: false,
    camera: "cockpit",
    muted: false,
    engineMuted: false,
    volume: 0.85,
    limiterActive: false,
    limiterPulse: false,
    audioReady: false,
    audioBackend: "Starting…",
    bankName: DEFAULT_CONFIG.bankName,
    bankPath: DEFAULT_CONFIG.bankName,
    audioDetail: "",
    error: ""
  };
  let display = {
    rpm: target.rpm,
    throttle: 0,
    boost: 0,
    speedKph: 0,
    clutch: 1,
    drivetrainSpeed: 0,
    brake: 0
  };
  let analogThrottle = 0;
  let analogBrake = 0;
  let eventSource = null;
  let pollingTimer = null;
  let streamRetryTimer = null;
  let streamWatchdog = null;
  let lastTelemetryAt = 0;
  let lastFrameAt = performance.now();
  let connectedOnce = false;
  let activeTransport = "connecting";
  let queuedControl = {};
  let controlFlushTimer = null;
  let controlRequestActive = false;
  let throttlePostValue = -1;
  let throttlePostMode = "";
  let brakePostValue = -1;
  let brakePostMode = "";
  let inputVolumeActive = false;
  let inputThrottleActive = false;
  let inputBrakeActive = false;

  function byId(id) {
    return document.getElementById(id);
  }

  function cacheDom() {
    [
      "carName", "carSelect", "favoriteButton", "favoriteIcon", "carPreview", "carPreviewImage", "carPreviewName", "connectionPill", "connectionText", "errorBanner", "errorTitle", "errorMessage",
      "retryButton", "engineState", "engineStateText", "shiftArray", "tachMeter", "tachNeedle",
      "tachTicks", "tachLabels", "redlineArc", "dialCarName", "rpmValue", "gearValue", "speedValue",
      "shiftPhase", "transmissionMode", "throttleValue", "throttleBar", "boostValue", "boostBar",
      "clutchValue", "clutchBar", "drivetrainValue", "brakeValue", "brakeBar", "limiterValue", "limiterPulse", "modeIndex",
      "listenerRing", "listenerDot", "listenerLabel", "modeSelector", "modeExplainer", "pedalReadout",
      "throttlePad", "throttleSlider", "throttleOutput", "brakePad", "brakeSlider", "brakeOutput", "neutralButton", "neutralText", "muteButton", "muteText", "engineMuteButton", "engineMuteText", "backfireAuditionButton", "volumeSlider",
      "volumeOutput", "audioBackend", "audioBank", "audioEvent", "footerStatus"
    ].forEach((id) => { dom[id] = byId(id); });
    dom.modeButtons = [...document.querySelectorAll("[data-camera]")];
    dom.footerStatusWrap = dom.footerStatus.closest(".footer-status");
    dom.gearDisplay = dom.gearValue.closest(".gear-display");
  }

  function asNumber(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function firstDefined(...values) {
    return values.find((value) => value !== undefined && value !== null);
  }

  function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }

  function normalizeUnit(value, fallback = 0) {
    const number = asNumber(value, fallback);
    return clamp(number > 1.0001 ? number / 100 : number, 0, 1);
  }

  function bankBaseName(value) {
    const path = String(value || "");
    return path.split(/[\\/]/).filter(Boolean).pop() || DEFAULT_CONFIG.bankName;
  }

  function normalizeGearLabel(label, gear) {
    if (label !== undefined && label !== null && String(label).trim()) {
      return String(label).trim().toUpperCase();
    }
    const number = asNumber(gear, 0);
    if (number < 0) return "R";
    if (number === 0) return "N";
    return String(clamp(Math.round(number), 1, 6));
  }

  function formatShiftPhase(phase, automatic = true) {
    const value = String(phase || "").trim();
    if (!value) return automatic ? "AUTO DRIVE" : "IN GEAR";
    return value
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .replace(/[_-]+/g, " ")
      .replace(/\s+/g, " ")
      .toUpperCase();
  }

  function isActiveShiftPhase(phase, shifting = false) {
    return Boolean(shifting)
      || /shift|neutral[_ -]?(up|down)|disengag|re.?engag|torque cut|clutch in|clutch out/i.test(String(phase || ""));
  }

  function shaftRpm(angularVelocity) {
    return asNumber(angularVelocity, 0) * 60 / (2 * Math.PI);
  }

  function normalizeConfig(raw = {}) {
    const car = raw.car || raw.vehicle || {};
    const engine = raw.engine || {};
    const tach = raw.tachometer || raw.instruments || {};
    const turbo = raw.turbo || engine.turbo || {};
    const audio = raw.audio || {};
    const lights = firstDefined(
      raw.shift_lights,
      raw.shiftLights,
      tach.shift_lights,
      tach.shiftLights,
      engine.shift_lights,
      engine.shiftLights,
      DEFAULT_CONFIG.shiftLights
    );

    const normalizedLights = Array.isArray(lights)
      ? lights.map((value) => asNumber(value, 0)).filter((value) => value > 0).sort((a, b) => a - b)
      : DEFAULT_CONFIG.shiftLights;

    return {
      carId: String(firstDefined(raw.car_id, raw.carId, car.id, DEFAULT_CONFIG.carId)),
      carName: String(firstDefined(raw.car_name, raw.carName, raw.screen_name, car.name, car.screen_name, DEFAULT_CONFIG.carName)),
      tachMax: asNumber(firstDefined(raw.tach_max, raw.tachMax, tach.maximum, tach.max_rpm, engine.tachometer_maximum, engine.tachometerMaximum), DEFAULT_CONFIG.tachMax),
      idleRpm: asNumber(firstDefined(raw.idle_rpm, raw.idleRpm, engine.idle_rpm, engine.idleRpm, engine.minimum), DEFAULT_CONFIG.idleRpm),
      limiterRpm: asNumber(firstDefined(raw.limiter_rpm, raw.limiterRpm, engine.limiter_rpm, engine.limiterRpm, engine.limiter), DEFAULT_CONFIG.limiterRpm),
      limiterHz: asNumber(firstDefined(raw.limiter_hz, raw.limiterHz, engine.limiter_hz, engine.limiterHz), DEFAULT_CONFIG.limiterHz),
      shiftLights: normalizedLights.length ? normalizedLights : DEFAULT_CONFIG.shiftLights,
      shiftBlinkRpm: asNumber(firstDefined(raw.shift_blink_rpm, raw.shiftBlinkRpm, tach.shift_blink_rpm, engine.shift_blink_rpm, engine.shiftBlinkRpm), DEFAULT_CONFIG.shiftBlinkRpm),
      shiftBlinkHz: asNumber(firstDefined(raw.shift_blink_hz, raw.shiftBlinkHz, tach.shift_blink_hz, engine.shift_blink_hz, engine.shiftBlinkHz), DEFAULT_CONFIG.shiftBlinkHz),
      maxBoost: asNumber(firstDefined(raw.display_max_boost, raw.displayMaxBoost, turbo.display_max_boost, turbo.displayMaxBoost, turbo.wastegate, turbo.max_boost, turbo.maxBoost), DEFAULT_CONFIG.maxBoost),
      bankName: bankBaseName(firstDefined(raw.bank_name, raw.bankName, audio.bank, audio.bank_name, DEFAULT_CONFIG.bankName)),
      bankPath: String(firstDefined(audio.bank, raw.bank_path, raw.bankPath, DEFAULT_CONFIG.bankName)),
      fmodVersion: String(firstDefined(raw.fmod_version, raw.fmodVersion, audio.fmod_version, DEFAULT_CONFIG.fmodVersion)),
      audioAvailable: Boolean(firstDefined(audio.available, raw.audio_available, raw.audioAvailable, false)),
      audioBackend: String(firstDefined(audio.backend, raw.audio_backend, raw.audioBackend, "Starting…")),
      audioDetail: String(firstDefined(audio.detail, raw.audio_detail, raw.audioDetail, "")),
      initialCamera: String(firstDefined(audio.camera, raw.camera, "cockpit")),
      initialMuted: Boolean(firstDefined(audio.muted, raw.muted, false)),
      initialEngineMuted: Boolean(firstDefined(audio.engine_muted, audio.engineMuted, raw.engineMuted, false)),
      initialVolume: normalizeUnit(firstDefined(audio.volume, raw.volume), 0.85),
      automatic: Boolean(firstDefined(raw.automatic, raw.transmission?.automatic, true)),
      neutral: Boolean(firstDefined(raw.neutral, raw.transmission?.neutral, false)),
      cars: Array.isArray(raw.cars) ? raw.cars : [],
      availableSoundEvents: Array.isArray(raw.availableSoundEvents)
        ? raw.availableSoundEvents.map(String)
        : (Array.isArray(audio.events) ? audio.events.map(String) : [])
    };
  }

  function normalizeState(raw = {}) {
    const engine = raw.engine || raw.physics || {};
    const audio = raw.audio || {};
    const control = raw.control || raw.controls || {};
    const turbo = raw.turbo || engine.turbo || {};
    const transmission = raw.transmission || raw.drivetrain || {};
    const rawCamera = String(firstDefined(raw.camera, raw.mode, raw.listener, control.camera, target.camera)).toLowerCase();
    const camera = rawCamera === "hood" || rawCamera === "external" ? "bonnet" : rawCamera;
    const errorValue = firstDefined(raw.error, audio.error, "");
    const gear = asNumber(firstDefined(raw.gear, transmission.gear), target.gear);

    return {
      rpm: Math.max(0, asNumber(firstDefined(raw.rpm, engine.rpm, engine.engine_rpm), target.rpm)),
      driverThrottle: normalizeUnit(firstDefined(raw.driverThrottle, raw.driver_throttle, control.driverThrottle, control.throttle, raw.throttle, engine.throttle), target.driverThrottle),
      throttle: normalizeUnit(firstDefined(raw.throttle, engine.throttle, control.throttle), target.throttle),
      throttleMode: String(firstDefined(raw.throttleMode, raw.throttle_mode, control.throttleMode, target.throttleMode)),
      boost: Math.max(0, asNumber(firstDefined(raw.boost, engine.boost, turbo.boost), target.boost)),
      speedKph: Math.max(0, asNumber(firstDefined(raw.speedKph, raw.speed_kph, raw.speedKmh, raw.speed_kmh, transmission.speedKph, transmission.speed_kph, transmission.speedKmh, transmission.speed_kmh), target.speedKph)),
      gear,
      gearLabel: normalizeGearLabel(firstDefined(raw.gearLabel, raw.gear_label, transmission.gearLabel, transmission.gear_label), gear),
      shiftPhase: String(firstDefined(raw.shiftPhase, raw.shift_phase, transmission.shiftPhase, transmission.shift_phase, target.shiftPhase)),
      shifting: Boolean(firstDefined(raw.shifting, transmission.shifting, target.shifting)),
      clutch: normalizeUnit(firstDefined(raw.clutch, transmission.clutch), target.clutch),
      drivetrainSpeed: Math.max(0, asNumber(firstDefined(raw.drivetrainSpeed, raw.drivetrain_speed, transmission.drivetrainSpeed, transmission.drivetrain_speed), target.drivetrainSpeed)),
      brake: normalizeUnit(firstDefined(raw.brake, control.brake), target.brake),
      brakeMode: String(firstDefined(raw.brakeMode, raw.brake_mode, control.brakeMode, target.brakeMode)),
      automatic: Boolean(firstDefined(raw.automatic, transmission.automatic, target.automatic)),
      neutral: Boolean(firstDefined(raw.neutral, transmission.neutral, target.neutral)),
      camera: MODE_INFO[camera] ? camera : target.camera,
      muted: Boolean(firstDefined(raw.muted, raw.mute, audio.muted, control.muted, target.muted)),
      engineMuted: Boolean(firstDefined(raw.engineMuted, raw.engine_muted, audio.engineMuted, audio.engine_muted, target.engineMuted)),
      volume: normalizeUnit(firstDefined(raw.volume, audio.volume, control.volume), target.volume),
      limiterActive: Boolean(firstDefined(raw.limiter_active, raw.limiterActive, engine.limiter_active, engine.limiter, false)),
      limiterPulse: Boolean(firstDefined(raw.limiter_pulse, raw.limiterPulse, engine.limiter_pulse, false)),
      audioReady: Boolean(firstDefined(raw.audio_ready, raw.audioReady, audio.available, audio.ready, audio.loaded, target.audioReady)),
      audioBackend: String(firstDefined(raw.audio_backend, raw.audioBackend, audio.backend, audio.status, target.audioBackend)),
      bankName: bankBaseName(firstDefined(raw.bank_name, raw.bankName, audio.bank, audio.bank_name, target.bankName)),
      bankPath: String(firstDefined(audio.bank, raw.bank_path, raw.bankPath, target.bankPath, target.bankName)),
      audioDetail: String(firstDefined(audio.detail, raw.audio_detail, raw.audioDetail, target.audioDetail, "")),
      error: errorValue ? String(errorValue) : ""
    };
  }

  function polarPoint(cx, cy, radius, angleDegrees) {
    const radians = angleDegrees * Math.PI / 180;
    return {
      x: cx + radius * Math.sin(radians),
      y: cy - radius * Math.cos(radians)
    };
  }

  function rpmAngle(rpm) {
    return -130 + clamp(rpm / config.tachMax, 0, 1) * 260;
  }

  function arcPath(startRpm, endRpm, radius = 216) {
    const start = polarPoint(300, 278, radius, rpmAngle(startRpm));
    const end = polarPoint(300, 278, radius, rpmAngle(endRpm));
    const angleSpan = rpmAngle(endRpm) - rpmAngle(startRpm);
    return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${radius} ${radius} 0 ${angleSpan > 180 ? 1 : 0} 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
  }

  function buildTachometer() {
    const svgNamespace = "http://www.w3.org/2000/svg";
    dom.tachTicks.replaceChildren();
    dom.tachLabels.replaceChildren();
    const tickStep = 100;

    for (let rpm = 0; rpm <= config.tachMax; rpm += tickStep) {
      const major = rpm % 1000 === 0;
      const medium = !major && rpm % 500 === 0;
      const angle = rpmAngle(rpm);
      const outer = polarPoint(300, 278, 213, angle);
      const inner = polarPoint(300, 278, major ? 187 : medium ? 195 : 202, angle);
      const line = document.createElementNS(svgNamespace, "line");
      line.setAttribute("x1", inner.x.toFixed(2));
      line.setAttribute("y1", inner.y.toFixed(2));
      line.setAttribute("x2", outer.x.toFixed(2));
      line.setAttribute("y2", outer.y.toFixed(2));
      line.setAttribute("class", `tach-tick${major ? " is-major" : medium ? " is-medium" : ""}${rpm >= config.limiterRpm ? " is-redline" : ""}`);
      dom.tachTicks.append(line);

      if (major) {
        const labelPoint = polarPoint(300, 278, 161, angle);
        const label = document.createElementNS(svgNamespace, "text");
        label.setAttribute("x", labelPoint.x.toFixed(2));
        label.setAttribute("y", (labelPoint.y + 10).toFixed(2));
        label.setAttribute("text-anchor", "middle");
        label.setAttribute("class", `tach-label${rpm >= config.limiterRpm ? " is-redline" : ""}`);
        label.textContent = String(Math.round(rpm / 1000));
        dom.tachLabels.append(label);
      }
    }

    dom.redlineArc.setAttribute("d", arcPath(config.limiterRpm, config.tachMax));
    dom.tachMeter.setAttribute("aria-valuemax", String(config.tachMax));
    buildShiftLights();
  }

  function buildShiftLights() {
    const fragment = document.createDocumentFragment();
    config.shiftLights.forEach((threshold, index) => {
      const light = document.createElement("span");
      light.className = "shift-light";
      light.dataset.threshold = String(threshold);
      light.title = `Shift light ${index + 1}: ${Math.round(threshold).toLocaleString()} RPM`;
      fragment.append(light);
    });
    dom.shiftArray.replaceChildren(fragment);
    dom.shiftLights = [...dom.shiftArray.children];
  }

  function applyConfig(rawConfig) {
    config = normalizeConfig(rawConfig);
    dom.carName.textContent = config.carName;
    dom.carPreviewName.textContent = config.carName;
    dom.carPreviewImage.alt = `${config.carName} showroom preview`;
    dom.carPreview.classList.remove("is-missing");
    dom.carPreviewImage.src = `/api/car-image?car=${encodeURIComponent(config.carId)}`;
    renderCarOptions();
    updateFavoriteUi();
    dom.dialCarName.textContent = config.carName.toUpperCase();
    dom.audioBank.textContent = config.bankName;
    dom.audioBank.title = config.bankPath;
    dom.limiterValue.textContent = `LIMITER ARMED ${Math.round(config.limiterRpm).toLocaleString()}`;
    target.audioReady = config.audioAvailable;
    target.audioBackend = config.audioBackend;
    target.audioDetail = config.audioDetail;
    target.bankName = config.bankName;
    target.bankPath = config.bankPath;
    target.camera = MODE_INFO[config.initialCamera] ? config.initialCamera : target.camera;
    target.muted = config.initialMuted;
    target.engineMuted = config.initialEngineMuted;
    target.volume = config.initialVolume;
    target.automatic = config.automatic;
    target.neutral = config.neutral;
    dom.volumeSlider.value = String(Math.round(target.volume * 100));
    dom.volumeOutput.value = `${Math.round(target.volume * 100)}%`;
    setCameraUi(target.camera);
    setEngineMuteUi(target.engineMuted);
    setNeutralUi(target.neutral);
    const hasBackfire = config.availableSoundEvents.some((name) => name === "backfire_int" || name === "backfire_ext");
    dom.backfireAuditionButton.hidden = !hasBackfire;
    setMuteUi(target.muted);
    updateAudioDiagnostics();
    buildTachometer();
  }

  function renderCarOptions() {
    if (!config.cars.length) return;
    const cars = [...config.cars].sort((left, right) => {
      const favoriteOrder = Number(favoriteCars.has(right.id)) - Number(favoriteCars.has(left.id));
      return favoriteOrder || String(left.name).localeCompare(String(right.name));
    });
    dom.carSelect.replaceChildren(...cars.map((car) => {
      const option = document.createElement("option");
      const favorite = favoriteCars.has(car.id);
      option.value = car.id;
      option.textContent = `${favorite ? "★ " : ""}${car.brand ? `${car.brand} · ` : ""}${car.name}${car.available ? "" : " (not installed)"}`;
      option.disabled = !car.available;
      option.title = [favorite ? "Favorite" : "", car.error || (Array.isArray(car.quirks) ? car.quirks.join(", ") : "")].filter(Boolean).join(" · ");
      return option;
    }));
    dom.carSelect.value = config.carId;
  }

  function updateFavoriteUi() {
    const favorite = favoriteCars.has(config.carId);
    dom.favoriteButton.classList.toggle("is-favorite", favorite);
    dom.favoriteButton.setAttribute("aria-pressed", String(favorite));
    const action = favorite ? "Remove selected car from favorites" : "Add selected car to favorites";
    dom.favoriteButton.setAttribute("aria-label", action);
    dom.favoriteButton.title = action;
    dom.favoriteIcon.textContent = favorite ? "★" : "☆";
  }

  function toggleFavorite() {
    if (favoriteCars.has(config.carId)) favoriteCars.delete(config.carId);
    else favoriteCars.add(config.carId);
    try { localStorage.setItem(FAVORITES_KEY, JSON.stringify([...favoriteCars])); } catch (_) { /* Session state still works. */ }
    renderCarOptions();
    updateFavoriteUi();
  }

  function updateTarget(rawState) {
    const next = normalizeState(rawState);
    target = { ...target, ...next };
    lastTelemetryAt = performance.now();
    connectedOnce = true;

    if (!inputVolumeActive) {
      dom.volumeSlider.value = String(Math.round(target.volume * 100));
      dom.volumeOutput.value = `${Math.round(target.volume * 100)}%`;
    }

    setCameraUi(target.camera);
    setMuteUi(target.muted);
    setEngineMuteUi(target.engineMuted);
    setNeutralUi(target.neutral);
    updateAudioDiagnostics();

    if (target.error) {
      showError("Audio backend reported an error", target.error);
    } else if (!target.audioReady && target.audioDetail) {
      showError("Native audio is unavailable", target.audioDetail);
    } else if (activeTransport !== "offline") {
      hideError();
    }
  }

  function renderFrame(now) {
    lastFrameAt = now;
    // Render the newest 30 Hz telemetry sample directly. An exponential
    // visual filter would add a second, non-AC tachometer response on top of
    // the fixed-step physics and smear limiter/gear-change transients.
    display.rpm = target.rpm;
    display.throttle = target.driverThrottle;
    display.boost = target.boost;
    display.speedKph = target.speedKph;
    display.clutch = target.clutch;
    display.drivetrainSpeed = target.drivetrainSpeed;
    display.brake = target.brake;

    const rpm = Math.max(0, display.rpm);
    const throttlePercent = clamp(display.throttle * 100, 0, 100);
    const clutchPercent = clamp(display.clutch * 100, 0, 100);
    const brakePercent = clamp(display.brake * 100, 0, 100);
    const boost = Math.max(0, display.boost);
    const shiftActive = isActiveShiftPhase(target.shiftPhase, target.shifting);
    const angle = rpmAngle(rpm);
    dom.tachNeedle.style.transform = `rotate(${angle.toFixed(3)}deg)`;
    dom.rpmValue.textContent = Math.round(rpm).toLocaleString();
    dom.gearValue.textContent = target.gearLabel;
    dom.speedValue.textContent = Math.round(display.speedKph).toLocaleString();
    dom.shiftPhase.textContent = formatShiftPhase(target.shiftPhase, target.automatic);
    dom.shiftPhase.classList.toggle("is-shifting", shiftActive);
    dom.gearDisplay.classList.toggle("is-shifting", shiftActive);
    dom.transmissionMode.textContent = target.neutral ? "N" : target.automatic ? "AUTO" : "MAN";
    dom.throttleValue.textContent = String(Math.round(throttlePercent));
    dom.throttleBar.style.width = `${throttlePercent.toFixed(1)}%`;
    dom.boostValue.textContent = boost.toFixed(2);
    dom.boostBar.style.width = `${clamp(boost / Math.max(config.maxBoost, 0.01) * 100, 0, 100).toFixed(1)}%`;
    dom.clutchValue.textContent = String(Math.round(clutchPercent));
    dom.clutchBar.style.width = `${clutchPercent.toFixed(1)}%`;
    dom.drivetrainValue.textContent = `SHAFT ${Math.round(shaftRpm(display.drivetrainSpeed)).toLocaleString()} RPM`;
    dom.brakeValue.textContent = String(Math.round(brakePercent));
    dom.brakeBar.style.width = `${brakePercent.toFixed(1)}%`;
    dom.pedalReadout.textContent = `T ${Math.round(throttlePercent)} · B ${Math.round(brakePercent)}`;

    dom.tachMeter.setAttribute("aria-valuenow", String(Math.round(rpm)));
    dom.tachMeter.setAttribute("aria-valuetext", `${Math.round(rpm)} revolutions per minute, gear ${target.gearLabel}, ${Math.round(display.speedKph)} kilometers per hour`);

    renderShiftLights(now, rpm);
    renderEngineState(rpm);
    requestAnimationFrame(renderFrame);
  }

  function renderShiftLights(now, rpm) {
    if (!dom.shiftLights) return;
    const blinkPeriod = 1000 / Math.max(config.shiftBlinkHz, 0.1);
    const blinkVisible = rpm < config.shiftBlinkRpm || (now % blinkPeriod) < blinkPeriod / 2;
    dom.shiftLights.forEach((light, index) => {
      const threshold = config.shiftLights[index];
      light.classList.toggle("is-lit", rpm >= threshold && blinkVisible);
    });
  }

  function renderEngineState(rpm) {
    const limiter = target.limiterActive || target.limiterPulse || rpm >= config.limiterRpm;
    const running = rpm > config.idleRpm * 0.7;
    const shifting = isActiveShiftPhase(target.shiftPhase, target.shifting);
    const state = limiter
      ? "Limiter"
      : shifting
        ? "Shifting"
        : target.brake > 0.03
          ? "Braking"
          : target.throttle > 0.02
            ? "Accelerating"
            : target.speedKph > 0.5
              ? "Coasting"
              : running
                ? "Ready"
                : "Stopped";
    dom.engineStateText.textContent = state;
    dom.engineState.classList.toggle("is-limiter", limiter);
    dom.limiterPulse.classList.toggle("is-active", target.limiterPulse || (limiter && (performance.now() % (1000 / Math.max(config.limiterHz, 1))) < 10));
    dom.limiterValue.textContent = limiter ? "LIMITER FUEL CUT" : `LIMITER ARMED ${Math.round(config.limiterRpm).toLocaleString()}`;
  }

  function setCameraUi(camera) {
    const info = MODE_INFO[camera] || MODE_INFO.cockpit;
    dom.modeIndex.textContent = info.index;
    dom.modeExplainer.textContent = info.description;
    dom.audioEvent.textContent = info.event;
    dom.listenerRing.setAttribute("cx", String(info.cx));
    dom.listenerRing.setAttribute("cy", String(info.cy));
    dom.listenerDot.setAttribute("cx", String(info.cx));
    dom.listenerDot.setAttribute("cy", String(info.cy));
    dom.listenerLabel.setAttribute("x", String(info.x));
    dom.listenerLabel.setAttribute("y", String(info.y));
    dom.listenerLabel.textContent = info.label;
    dom.modeButtons.forEach((button) => {
      const selected = button.dataset.camera === camera;
      button.classList.toggle("is-selected", selected);
      button.setAttribute("aria-checked", String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
  }

  function setMuteUi(muted) {
    dom.muteButton.classList.toggle("is-muted", muted);
    dom.muteButton.setAttribute("aria-pressed", String(muted));
    dom.muteText.textContent = muted ? "Unmute" : "Mute";
  }

  function setEngineMuteUi(muted) {
    dom.engineMuteButton.classList.toggle("is-isolated", muted);
    dom.engineMuteButton.setAttribute("aria-pressed", String(muted));
    dom.engineMuteText.textContent = muted ? "Isolated" : "Playing";
  }

  function setNeutralUi(neutral) {
    dom.neutralButton.classList.toggle("is-neutral", neutral);
    dom.neutralButton.setAttribute("aria-pressed", String(neutral));
    dom.neutralButton.title = neutral ? "Return to automatic drive" : "Disengage the drivetrain for neutral revving";
    dom.neutralText.textContent = neutral ? "Neutral" : "Drive";
  }

  function updateAudioDiagnostics() {
    dom.audioBackend.textContent = target.audioBackend || (target.audioReady ? config.fmodVersion : "Unavailable");
    dom.audioBank.textContent = target.bankName || config.bankName;
    dom.audioBackend.title = target.audioDetail || dom.audioBackend.textContent;
    dom.audioBank.title = target.bankPath || config.bankPath || dom.audioBank.textContent;
  }

  function setConnection(mode, detail = "") {
    activeTransport = mode;
    dom.connectionPill.className = `connection-pill is-${mode}`;
    dom.footerStatusWrap.className = mode === "connecting" ? "footer-status" : `footer-status is-${mode}`;
    const labels = {
      connecting: "Connecting",
      live: "Live stream",
      polling: "Live polling",
      offline: "Offline"
    };
    dom.connectionText.textContent = labels[mode] || mode;
    dom.footerStatus.textContent = detail || {
      connecting: "Connecting to simulator",
      live: "Telemetry stream active",
      polling: "Telemetry active (polling fallback)",
      offline: "Simulator unavailable — retrying"
    }[mode];
  }

  function showError(title, message) {
    dom.errorTitle.textContent = title;
    dom.errorMessage.textContent = message;
    dom.errorBanner.hidden = false;
  }

  function hideError() {
    dom.errorBanner.hidden = true;
  }

  async function fetchJson(url, options = {}) {
    const response = await fetch(url, {
      cache: "no-store",
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {})
      }
    });
    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      throw new Error(`${response.status} ${response.statusText}${detail ? ` — ${detail.slice(0, 160)}` : ""}`);
    }
    if (response.status === 204) return null;
    const contentType = response.headers.get("content-type") || "";
    return contentType.includes("json") ? response.json() : null;
  }

  async function loadConfig() {
    try {
      const raw = await fetchJson("/api/config");
      if (raw) applyConfig(raw);
    } catch (error) {
      applyConfig(DEFAULT_CONFIG);
      showError("Using built-in instrument calibration", `/api/config did not respond: ${error.message}`);
    }
  }

  function handleStreamPayload(event) {
    try {
      const payload = JSON.parse(event.data);
      if (payload.type === "config" && payload.config) {
        applyConfig(payload.config);
        return;
      }
      updateTarget(payload.state || payload);
      setConnection("live");
    } catch (error) {
      showError("Malformed telemetry message", error.message);
    }
  }

  function stopPolling() {
    if (pollingTimer) {
      clearTimeout(pollingTimer);
      pollingTimer = null;
    }
  }

  function closeStream() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    if (streamWatchdog) {
      clearInterval(streamWatchdog);
      streamWatchdog = null;
    }
  }

  function openStream() {
    clearTimeout(streamRetryTimer);
    closeStream();
    stopPolling();
    setConnection("connecting");

    if (!("EventSource" in window)) {
      startPolling("Server-sent events are not supported by this browser.");
      return;
    }

    lastTelemetryAt = performance.now();
    eventSource = new EventSource("/api/stream");
    eventSource.addEventListener("open", () => {
      setConnection("live");
      hideError();
    });
    eventSource.addEventListener("message", handleStreamPayload);
    eventSource.addEventListener("state", handleStreamPayload);
    eventSource.addEventListener("config", (event) => {
      try { applyConfig(JSON.parse(event.data)); } catch (error) { showError("Invalid configuration event", error.message); }
    });
    eventSource.addEventListener("error", () => {
      if (performance.now() - lastTelemetryAt > 1800) {
        startPolling("The telemetry stream closed; state polling is active while it reconnects.");
      }
    });

    streamWatchdog = setInterval(() => {
      if (performance.now() - lastTelemetryAt > 4000) {
        startPolling("No stream data arrived for four seconds; using polling fallback.");
      }
    }, 1000);
  }

  function startPolling(reason = "") {
    closeStream();
    if (pollingTimer) return;
    setConnection("polling");
    if (reason && !connectedOnce) showError("Live stream unavailable", reason);

    const poll = async () => {
      try {
        const raw = await fetchJson("/api/state");
        if (raw) updateTarget(raw.state || raw);
        setConnection("polling");
        if (!target.error && (target.audioReady || !target.audioDetail)) hideError();
      } catch (error) {
        setConnection("offline");
        showError("Simulator connection interrupted", `${error.message}. Retrying automatically.`);
      } finally {
        pollingTimer = setTimeout(poll, activeTransport === "offline" ? 1200 : 250);
      }
    };

    poll();
    streamRetryTimer = setTimeout(() => {
      stopPolling();
      openStream();
    }, 10000);
  }

  function queueControl(partial, immediate = false) {
    queuedControl = { ...queuedControl, ...partial };
    clearTimeout(controlFlushTimer);
    if (immediate) {
      flushControls();
    } else {
      controlFlushTimer = setTimeout(flushControls, 35);
    }
  }

  async function flushControls() {
    clearTimeout(controlFlushTimer);
    controlFlushTimer = null;
    if (controlRequestActive || !Object.keys(queuedControl).length) return;
    controlRequestActive = true;
    try {
      // Only one POST may be in flight. New input is merged into queuedControl
      // and sent next, so an older response can never restore a released pedal
      // or a superseded camera/mute state.
      while (Object.keys(queuedControl).length) {
        const payload = queuedControl;
        queuedControl = {};
        try {
          const response = await fetchJson("/api/control", {
            method: "POST",
            body: JSON.stringify(payload)
          });
          if (response) updateTarget(response.state || response);
        } catch (error) {
          showError("Control command was not accepted", error.message);
        }
      }
    } finally {
      controlRequestActive = false;
      if (Object.keys(queuedControl).length) void flushControls();
    }
  }

  function currentThrottleCommand(mode) {
    return mode === "keyboard" ? (controlSources.size ? 1 : 0) : analogThrottle;
  }

  function publishThrottle(force = false, mode = controlSources.size ? "keyboard" : "analog") {
    const throttle = currentThrottleCommand(mode);
    if (!force && Math.abs(throttle - throttlePostValue) < 0.001 && mode === throttlePostMode) return;
    throttlePostValue = throttle;
    throttlePostMode = mode;
    target.driverThrottle = throttle;
    if (mode === "analog") target.throttle = throttle;
    queueControl({ throttle, throttleMode: mode });
    dom.throttlePad.classList.toggle("is-active", controlSources.size > 0);
    dom.throttlePad.setAttribute("aria-pressed", String(controlSources.size > 0));
  }

  function pressThrottle(source) {
    controlSources.add(source);
    publishThrottle(true, "keyboard");
  }

  function releaseThrottle(source) {
    if (!controlSources.delete(source)) return;
    const mode = controlSources.size || analogThrottle === 0 ? "keyboard" : "analog";
    publishThrottle(true, mode);
  }

  function currentBrakeCommand(mode) {
    return mode === "keyboard" ? (brakeSources.size ? 1 : 0) : analogBrake;
  }

  function publishBrake(force = false, mode = brakeSources.size ? "keyboard" : "analog") {
    const brake = currentBrakeCommand(mode);
    if (!force && Math.abs(brake - brakePostValue) < 0.001 && mode === brakePostMode) return;
    brakePostValue = brake;
    brakePostMode = mode;
    if (mode === "analog") target.brake = brake;
    queueControl({ brake, brakeMode: mode });
    dom.brakePad.classList.toggle("is-active", brakeSources.size > 0);
    dom.brakePad.setAttribute("aria-pressed", String(brakeSources.size > 0));
  }

  function pressBrake(source) {
    brakeSources.add(source);
    publishBrake(true, "keyboard");
  }

  function releaseBrake(source) {
    if (!brakeSources.delete(source)) return;
    const mode = brakeSources.size || analogBrake === 0 ? "keyboard" : "analog";
    publishBrake(true, mode);
  }

  function selectCamera(camera, focus = false) {
    if (!MODE_INFO[camera]) return;
    target.camera = camera;
    setCameraUi(camera);
    queueControl({ camera }, true);
    if (focus) document.querySelector(`[data-camera="${camera}"]`)?.focus();
  }

  function toggleMute() {
    target.muted = !target.muted;
    setMuteUi(target.muted);
    queueControl({ muted: target.muted }, true);
  }

  function toggleEngineMute() {
    target.engineMuted = !target.engineMuted;
    setEngineMuteUi(target.engineMuted);
    queueControl({ engineMuted: target.engineMuted }, true);
  }

  function toggleNeutral() {
    target.neutral = !target.neutral;
    setNeutralUi(target.neutral);
    queueControl({ neutral: target.neutral }, true);
  }

  function auditionBackfire() {
    target.engineMuted = true;
    setEngineMuteUi(true);
    queueControl({ engineMuted: true, auditionBackfire: true }, true);
  }

  function isTextControl(element) {
    return element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement || element?.isContentEditable;
  }

  function attachControls() {
    dom.favoriteButton.addEventListener("click", toggleFavorite);
    dom.carPreviewImage.addEventListener("error", () => dom.carPreview.classList.add("is-missing"));
    dom.carPreviewImage.addEventListener("load", () => dom.carPreview.classList.remove("is-missing"));
    dom.carSelect.addEventListener("change", () => {
      const requested = dom.carSelect.value;
      queueControl({ car: requested, throttle: 0, brake: 0 }, true);
      const waitForCar = async () => {
        for (let attempt = 0; attempt < 40; attempt += 1) {
          await new Promise((resolve) => setTimeout(resolve, 100));
          try {
            const state = await fetchJson("/api/state");
            if (state.carId === requested) { await loadConfig(); return; }
          } catch (_) { /* stream reconnect UI owns connectivity feedback */ }
        }
      };
      void waitForCar();
    });
    dom.throttlePad.addEventListener("pointerdown", (event) => {
      if (event.button !== 0 && event.pointerType === "mouse") return;
      dom.throttlePad.setPointerCapture?.(event.pointerId);
      pressThrottle(`pointer-${event.pointerId}`);
    });
    ["pointerup", "pointercancel", "lostpointercapture"].forEach((eventName) => {
      dom.throttlePad.addEventListener(eventName, (event) => releaseThrottle(`pointer-${event.pointerId}`));
    });
    dom.throttlePad.addEventListener("click", (event) => event.preventDefault());
    dom.throttlePad.addEventListener("keydown", (event) => {
      if (event.code !== "Enter" || event.repeat) return;
      event.preventDefault();
      pressThrottle("button-Enter");
    });
    dom.throttlePad.addEventListener("keyup", (event) => {
      if (event.code === "Enter") releaseThrottle("button-Enter");
    });

    dom.throttleSlider.addEventListener("pointerdown", () => { inputThrottleActive = true; });
    dom.throttleSlider.addEventListener("pointerup", () => { inputThrottleActive = false; });
    dom.throttleSlider.addEventListener("change", () => { inputThrottleActive = false; });
    dom.throttleSlider.addEventListener("input", () => {
      analogThrottle = asNumber(dom.throttleSlider.value, 0) / 100;
      dom.throttleOutput.value = `${Math.round(analogThrottle * 100)}%`;
      if (!controlSources.size) publishThrottle(false, "analog");
    });

    dom.brakePad.addEventListener("pointerdown", (event) => {
      if (event.button !== 0 && event.pointerType === "mouse") return;
      dom.brakePad.setPointerCapture?.(event.pointerId);
      pressBrake(`pointer-${event.pointerId}`);
    });
    ["pointerup", "pointercancel", "lostpointercapture"].forEach((eventName) => {
      dom.brakePad.addEventListener(eventName, (event) => releaseBrake(`pointer-${event.pointerId}`));
    });
    dom.brakePad.addEventListener("click", (event) => event.preventDefault());
    dom.brakePad.addEventListener("keydown", (event) => {
      if (!["Space", "Enter"].includes(event.code) || event.repeat) return;
      event.preventDefault();
      pressBrake(`button-${event.code}`);
    });
    dom.brakePad.addEventListener("keyup", (event) => {
      if (["Space", "Enter"].includes(event.code)) releaseBrake(`button-${event.code}`);
    });

    dom.brakeSlider.addEventListener("pointerdown", () => { inputBrakeActive = true; });
    dom.brakeSlider.addEventListener("pointerup", () => { inputBrakeActive = false; });
    dom.brakeSlider.addEventListener("change", () => { inputBrakeActive = false; });
    dom.brakeSlider.addEventListener("input", () => {
      analogBrake = asNumber(dom.brakeSlider.value, 0) / 100;
      dom.brakeOutput.value = `${Math.round(analogBrake * 100)}%`;
      if (!brakeSources.size) publishBrake(false, "analog");
    });

    dom.modeButtons.forEach((button, index) => {
      button.addEventListener("click", () => selectCamera(button.dataset.camera));
      button.addEventListener("keydown", (event) => {
        if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
        event.preventDefault();
        const direction = event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 : -1;
        const next = dom.modeButtons[(index + direction + dom.modeButtons.length) % dom.modeButtons.length];
        selectCamera(next.dataset.camera, true);
      });
    });

    dom.muteButton.addEventListener("click", toggleMute);
    dom.engineMuteButton.addEventListener("click", toggleEngineMute);
    dom.neutralButton.addEventListener("click", toggleNeutral);
    dom.backfireAuditionButton.addEventListener("click", auditionBackfire);
    dom.volumeSlider.addEventListener("pointerdown", () => { inputVolumeActive = true; });
    dom.volumeSlider.addEventListener("pointerup", () => { inputVolumeActive = false; });
    dom.volumeSlider.addEventListener("change", () => { inputVolumeActive = false; });
    dom.volumeSlider.addEventListener("input", () => {
      const volume = asNumber(dom.volumeSlider.value, 85) / 100;
      target.volume = volume;
      dom.volumeOutput.value = `${Math.round(volume * 100)}%`;
      queueControl({ volume });
    });

    window.addEventListener("keydown", (event) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (isTextControl(event.target)) return;
      if (event.code === "Space" && event.target instanceof HTMLButtonElement && event.target !== dom.throttlePad) return;
      if (event.code === "ArrowDown" && event.target instanceof HTMLButtonElement && event.target !== dom.brakePad) return;
      if (event.code === "Space" || event.code === "KeyW") {
        event.preventDefault();
        if (!event.repeat) pressThrottle(`key-${event.code}`);
      } else if (event.code === "KeyS" || event.code === "ArrowDown") {
        event.preventDefault();
        if (!event.repeat) pressBrake(`key-${event.code}`);
      } else if (event.code === "Digit1") {
        selectCamera("cockpit", true);
      } else if (event.code === "Digit2") {
        selectCamera("bonnet", true);
      } else if (event.code === "Digit3") {
        selectCamera("exhaust", true);
      } else if (event.code === "KeyM" && !event.repeat) {
        toggleMute();
      } else if (event.code === "KeyN" && !event.repeat) {
        toggleNeutral();
      }
    });

    window.addEventListener("keyup", (event) => {
      if (event.code === "Space" && event.target instanceof HTMLButtonElement && event.target !== dom.throttlePad) return;
      if (event.code === "ArrowDown" && event.target instanceof HTMLButtonElement && event.target !== dom.brakePad) return;
      if (event.code === "Space" || event.code === "KeyW") {
        event.preventDefault();
        releaseThrottle(`key-${event.code}`);
      } else if (event.code === "KeyS" || event.code === "ArrowDown") {
        event.preventDefault();
        releaseBrake(`key-${event.code}`);
      }
    });
    window.addEventListener("blur", () => {
      const hadDigitalThrottle = controlSources.size > 0;
      const hadDigitalBrake = brakeSources.size > 0;
      controlSources.clear();
      brakeSources.clear();
      if (hadDigitalThrottle) publishThrottle(true, analogThrottle === 0 ? "keyboard" : "analog");
      if (hadDigitalBrake) publishBrake(true, analogBrake === 0 ? "keyboard" : "analog");
    });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) {
        const hadDigitalThrottle = controlSources.size > 0;
        const hadDigitalBrake = brakeSources.size > 0;
        controlSources.clear();
        brakeSources.clear();
        if (hadDigitalThrottle) publishThrottle(true, analogThrottle === 0 ? "keyboard" : "analog");
        if (hadDigitalBrake) publishBrake(true, analogBrake === 0 ? "keyboard" : "analog");
      }
    });

    dom.retryButton.addEventListener("click", openStream);
  }

  async function initialize() {
    cacheDom();
    applyConfig(DEFAULT_CONFIG);
    attachControls();
    setCameraUi(target.camera);
    setMuteUi(false);
    setEngineMuteUi(false);
    setConnection("connecting");
    await loadConfig();
    openStream();
    requestAnimationFrame(renderFrame);
  }

  window.addEventListener("DOMContentLoaded", initialize, { once: true });
})();
