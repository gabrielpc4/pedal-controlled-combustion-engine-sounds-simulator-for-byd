"use strict";

const $ = (id) => document.getElementById(id);
const dom = {
  favorite: $("favorite"), carName: $("carName"), stream: $("streamStatus"), rpm: $("rpm"), speed: $("speed"), gear: $("gear"),
  shiftPhase: $("shiftPhase"), shiftDot: $("shiftDot"), tachValue: $("tachValue"), needle: $("needle"), throttleRead: $("throttleRead"),
  boostRead: $("boostRead"), inputRead: $("inputRead"), ratioRead: $("ratioRead"), ignition: $("ignition"),
  throttleControl: $("throttleControl"), throttleOutput: $("throttleOutput"), brakeControl: $("brakeControl"),
  brakeOutput: $("brakeOutput"), inputRate: $("inputRate"), inputRateBadge: $("inputRateBadge"), scenario: $("scenario"), muted: $("muted"),
  isolated: $("isolated"), authentic: $("authentic"), audition: $("audition"), gateBadge: $("gateBadge"), audioDetail: $("audioDetail"),
  layers: $("layers"), rootPath: $("rootPath"), rejected: $("rejected"), accelerationRead: $("accelerationRead"),
  regenRead: $("regenRead"), energyRead: $("energyRead")
};

let config = null;
let state = null;
let desired = {};
let postRunning = false;
let renderRaf = 0;
let lastEventAt = 0;

class SynthesizedPowertrainAudio {
  constructor() {
    this.context = null;
    this.started = false;
    this.last = {};
  }

  async ensure() {
    if (!this.context) this.build();
    if (!this.context) return;
    if (this.context.state !== "running") await this.context.resume();
    this.started = this.context.state === "running";
    if (state?.audio) renderAudio(state.audio);
  }

  build() {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return;
    const context = new AudioContextClass({latencyHint:"interactive"});
    this.context = context;
    this.master = context.createGain();
    this.compressor = context.createDynamicsCompressor();
    this.compressor.threshold.value = -10;
    this.compressor.knee.value = 8;
    this.compressor.ratio.value = 5;
    this.compressor.attack.value = 0.004;
    this.compressor.release.value = 0.12;
    this.cameraFilter = context.createBiquadFilter();
    this.cameraFilter.type = "lowpass";
    this.cameraFilter.Q.value = 0.45;
    this.continuous = context.createGain();
    this.effects = context.createGain();
    this.continuous.connect(this.cameraFilter);
    this.effects.connect(this.cameraFilter);
    this.cameraFilter.connect(this.compressor);
    this.compressor.connect(this.master);
    this.master.connect(context.destination);

    this.engineVoices = [
      this.oscillator("sawtooth", 0.105),
      this.oscillator("square", 0.027),
      this.oscillator("triangle", 0.065),
      this.oscillator("sine", 0.024),
    ];
    this.turbo = this.oscillator("sine", 0.0);
    this.transmission = this.oscillator("triangle", 0.0);
    this.master.gain.value = 0;
  }

  oscillator(type, gainValue) {
    const oscillator = this.context.createOscillator();
    const gain = this.context.createGain();
    oscillator.type = type;
    gain.gain.value = gainValue;
    oscillator.connect(gain);
    gain.connect(this.continuous);
    oscillator.start();
    return {oscillator, gain};
  }

  burst(kind, strength=1) {
    if (!this.started) return;
    const context = this.context;
    const durations = {pop:0.16, crack:0.09, bov:0.32, limiter:0.07, startup:0.42};
    const duration = durations[kind] || 0.12;
    const frames = Math.ceil(context.sampleRate * duration);
    const buffer = context.createBuffer(1, frames, context.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i=0; i<frames; i++) {
      const envelope = Math.pow(1 - i / frames, kind === "bov" ? 1.4 : 3.2);
      data[i] = (Math.random() * 2 - 1) * envelope;
    }
    const source = context.createBufferSource();
    const filter = context.createBiquadFilter();
    const gain = context.createGain();
    filter.type = kind === "bov" ? "highpass" : "bandpass";
    filter.frequency.value = kind === "bov" ? 1600 : kind === "crack" ? 950 : 260;
    filter.Q.value = kind === "bov" ? 0.4 : 1.2;
    gain.gain.setValueAtTime(Math.min(0.32, 0.20 * strength), context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + duration);
    source.buffer = buffer;
    source.connect(filter);
    filter.connect(gain);
    gain.connect(this.effects);
    source.start();
    source.stop(context.currentTime + duration);
    if (kind === "pop" || kind === "startup") this.thump(kind === "startup" ? 72 : 92, duration, strength);
  }

  thump(frequency, duration, strength) {
    const context = this.context;
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.type = "sine";
    oscillator.frequency.setValueAtTime(frequency, context.currentTime);
    oscillator.frequency.exponentialRampToValueAtTime(Math.max(35, frequency * .55), context.currentTime + duration);
    gain.gain.setValueAtTime(0.16 * strength, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + duration);
    oscillator.connect(gain); gain.connect(this.effects);
    oscillator.start(); oscillator.stop(context.currentTime + duration);
  }

  update(next) {
    if (!this.started || !this.context) { this.last = next; return; }
    const now = this.context.currentTime;
    const rpm = Math.max(0, Number(next.rpm || 0));
    const throttle = Math.max(0, Math.min(1, Number(next.throttlePct || 0) / 100));
    const boost = Math.max(0, Math.min(1, Number(next.boost || 0)));
    const speed = Math.max(0, Number(next.speedKph || 0));
    const continuousOn = Boolean(next.ignition && rpm > 80 && !next.isolated && !next.muted);
    const level = continuousOn ? 1 : 0;
    const firing = Math.max(28, rpm / 20); // inline-six four-stroke: three firing pulses per revolution
    const frequencies = [firing, firing * 2.01, firing * 0.5, firing * 3.03];
    const voiceLevels = [0.105, 0.027 + throttle * .018, 0.065, 0.014 + throttle * .022];
    this.engineVoices.forEach((voice,index) => {
      voice.oscillator.frequency.setTargetAtTime(frequencies[index], now, .018);
      voice.gain.gain.setTargetAtTime(voiceLevels[index] * level, now, .025);
    });
    this.turbo.oscillator.frequency.setTargetAtTime(520 + boost * 5200, now, .035);
    this.turbo.gain.gain.setTargetAtTime(level * (boost > .02 ? .022 : 0), now, .04);
    this.transmission.oscillator.frequency.setTargetAtTime(110 + speed * 13 + Math.max(0,next.gear || 0) * 38, now, .03);
    this.transmission.gain.gain.setTargetAtTime(level * (speed > 1 ? .032 : 0), now, .04);
    const camera = next.camera || "cockpit";
    const cutoff = camera === "cockpit" ? 3300 + throttle * 900 : camera === "bonnet" ? 12500 : 8800;
    this.cameraFilter.frequency.setTargetAtTime(cutoff, now, .035);
    this.master.gain.setTargetAtTime(next.muted ? 0 : camera === "exhaust" ? .52 : .42, now, .018);

    if (next.startupTriggered && !this.last.startupTriggered) this.burst("startup", 1);
    if ((next.backfireTriggered || next.burbleTriggered) && !(this.last.backfireTriggered || this.last.burbleTriggered)) this.burst("pop", 1);
    if (next.shiftStarted && !this.last.shiftStarted) this.burst("crack", .8);
    if (next.bovTriggered && !this.last.bovTriggered) this.burst("bov", .75);
    if (next.limiterActive && !this.last.limiterActive) this.burst("limiter", .7);
    this.effects.gain.setTargetAtTime(next.muted ? 0 : 1, now, .01);
    this.last = next;
  }
}

const synthesizedAudio = new SynthesizedPowertrainAudio();

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}

async function postControl(change) {
  Object.assign(desired, change);
  if (postRunning) return;
  postRunning = true;
  try {
    while (Object.keys(desired).length) {
      const payload = desired;
      desired = {};
      const response = await fetch("/api/control", {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(payload)});
      const json = await response.json();
      if (!response.ok) throw new Error(json.error || `HTTP ${response.status}`);
    }
  } catch (error) {
    dom.rejected.textContent = error.message;
  } finally {
    postRunning = false;
    if (Object.keys(desired).length) postControl({});
  }
}

function setupPedal(input, output, key) {
  const control = input.closest(".pedal-control");
  const plate = control.querySelector(".pedal-plate");
  let dragging = false;
  const setValue = (value, send=false) => {
    const bounded = Math.max(0, Math.min(100, Number(value) || 0));
    input.value = bounded.toFixed(1);
    output.value = `${bounded.toFixed(1)}%`;
    plate.style.setProperty("--pedal", bounded);
    if (send) {
      dom.scenario.value = "manual";
      postControl({mockMode:"manual", [key]:bounded});
    }
  };
  const fromPointer = (event) => {
    const rect = input.getBoundingClientRect();
    setValue((rect.bottom - event.clientY) / rect.height * 100, true);
  };
  input.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    dragging = true;
    input.setPointerCapture(event.pointerId);
    control.classList.add("active");
    fromPointer(event);
  });
  input.addEventListener("pointermove", (event) => {
    if (!dragging) return;
    event.preventDefault();
    fromPointer(event);
  });
  const release = (event) => {
    if (!dragging) return;
    dragging = false;
    control.classList.remove("active");
    if (input.hasPointerCapture(event.pointerId)) input.releasePointerCapture(event.pointerId);
    setValue(0, true);
  };
  input.addEventListener("pointerup", release);
  input.addEventListener("pointercancel", release);
  input.addEventListener("input", () => { if (!dragging) setValue(input.value, true); });
  input._setPedalValue = (value) => { if (!dragging) setValue(value, false); };
  setValue(0);
}

function render(next) {
  state = next;
  const rpm = Number(next.rpm || 0);
  const maximum = config?.referenceCar?.maximumRpm || 9500;
  const ratio = Math.max(0, Math.min(1, rpm / maximum));
  dom.rpm.textContent = Math.round(rpm).toLocaleString();
  dom.speed.textContent = Number(next.speedKph || 0).toFixed(2);
  dom.gear.textContent = next.gearLabel || next.selector || "P";
  dom.tachValue.style.strokeDasharray = `${ratio * 100} 100`;
  dom.needle.style.transform = `rotate(${ratio * 180}deg)`;
  dom.shiftPhase.textContent = next.shifting ? String(next.shiftPhase).toUpperCase() : (next.ignition ? "ROAD COUPLED" : "IGNITION OFF");
  dom.shiftDot.parentElement.classList.toggle("active", Boolean(next.shifting || next.limiterActive));
  dom.throttleRead.textContent = `${Number(next.throttlePct || 0).toFixed(1)}%`;
  dom.boostRead.textContent = `${Math.round(Number(next.boost || 0) * 100)}%`;
  dom.inputRead.textContent = next.inputDropout ? "DROPOUT" : next.inputStale ? "STALE" : `${Number(next.mock?.inputRateHz || 0)} HZ`;
  dom.ratioRead.textContent = next.ratioFidelityExact ? "RECOVERED" : "GATED";
  dom.ignition.classList.toggle("on", Boolean(next.ignition));
  dom.ignition.querySelector("strong").textContent = next.ignition ? "ON" : "OFF";
  document.querySelectorAll("[data-selector]").forEach((button) => button.classList.toggle("active", button.dataset.selector === next.selector));
  document.querySelectorAll("[data-camera]").forEach((button) => button.classList.toggle("active", button.dataset.camera === next.camera));
  dom.muted.checked = Boolean(next.muted);
  dom.isolated.checked = Boolean(next.isolated);
  dom.authentic.checked = Boolean(next.authenticLevels);
  const dynamics = next.mock?.dynamics || {};
  dom.accelerationRead.textContent = `${Number(dynamics.accelerationMps2 || 0).toFixed(2)} m/s²`;
  dom.regenRead.textContent = `${Number(dynamics.regenPowerKw || 0).toFixed(1)} kW`;
  dom.energyRead.textContent = `${(Number(dynamics.recoveredEnergyKwh || 0) * 1000).toFixed(3)} Wh`;
  const external = next.mock?.mode === "external";
  dom.throttleControl._setPedalValue?.(external ? next.throttlePct : next.mock?.throttlePct);
  dom.brakeControl._setPedalValue?.(external ? next.brakePct : next.mock?.brakePct);
  if (!external && dom.scenario.querySelector(`option[value="${next.mock?.mode}"]`)) dom.scenario.value = next.mock.mode;
  dom.rejected.textContent = next.controlRejected || "";
  lastEventAt = performance.now();
  dom.stream.classList.add("live");
  dom.stream.querySelector("span").textContent = "LIVE STREAM";
  synthesizedAudio.update(next);
}

function renderConfig(next) {
  config = next;
  const car = next.referenceCar;
  dom.carName.textContent = car.name;
  dom.rootPath.textContent = next.fh6Root;
  const audio = state?.audio;
  if (audio) renderAudio(audio);
}

function renderAudio(audio) {
  const fallbackActive = synthesizedAudio.started && audio.synthesized_fallback_available;
  dom.gateBadge.textContent = audio.available ? "PASSED" : fallbackActive ? "SYNTH ACTIVE" : audio.method22_locked ? "CLICK TO ENABLE SYNTH" : "NATIVE LINK PENDING";
  dom.gateBadge.classList.toggle("ready", Boolean(audio.available || fallbackActive));
  dom.gateBadge.classList.toggle("locked", !audio.available && !fallbackActive);
  dom.audioDetail.textContent = fallbackActive ? `${audio.detail} Browser synthesis is active; it is not original FH6 audio.` : audio.detail;
  dom.layers.innerHTML = audio.layers.map((layer) => `<div class="layer ${layer.decoded ? "decoded" : ""}" title="${escapeHtml(layer.source)}"><strong><i></i>${escapeHtml(layer.id.replaceAll("_", " "))}</strong><small>${escapeHtml(layer.authored_name || "not authored")}</small></div>`).join("");
}

dom.favorite.addEventListener("click", () => {
  const value = localStorage.getItem("fh6.favorite.TOY_SupraRZ_98") !== "1";
  localStorage.setItem("fh6.favorite.TOY_SupraRZ_98", value ? "1" : "0");
  dom.favorite.classList.toggle("on", value);
  dom.favorite.textContent = value ? "★" : "☆";
});
const favorite = localStorage.getItem("fh6.favorite.TOY_SupraRZ_98") === "1";
dom.favorite.classList.toggle("on", favorite); dom.favorite.textContent = favorite ? "★" : "☆";

dom.ignition.addEventListener("click", () => postControl({ignition: !Boolean(state?.ignition)}));
document.querySelectorAll("[data-selector]").forEach((button) => button.addEventListener("click", () => postControl({selector:button.dataset.selector})));
document.querySelectorAll("[data-camera]").forEach((button) => button.addEventListener("click", () => postControl({camera:button.dataset.camera})));
setupPedal(dom.throttleControl, dom.throttleOutput, "throttlePct");
setupPedal(dom.brakeControl, dom.brakeOutput, "brakePct");
dom.inputRate.addEventListener("change", () => { dom.inputRateBadge.textContent=`${dom.inputRate.value} HZ`; postControl({inputRateHz:Number(dom.inputRate.value)}); });
dom.scenario.addEventListener("change", () => postControl({mockMode:dom.scenario.value}));
dom.muted.addEventListener("change", () => postControl({muted:dom.muted.checked}));
dom.isolated.addEventListener("change", () => postControl({isolated:dom.isolated.checked}));
dom.authentic.addEventListener("change", () => postControl({authenticLevels:dom.authentic.checked}));
dom.audition.addEventListener("click", () => postControl({auditionPops:true}));
document.addEventListener("pointerdown", () => synthesizedAudio.ensure(), {capture:true});
document.addEventListener("click", () => synthesizedAudio.ensure(), {capture:true});
document.addEventListener("keydown", () => synthesizedAudio.ensure(), {capture:true});

async function initialize() {
  try {
    const [configResponse, stateResponse] = await Promise.all([fetch("/api/config"), fetch("/api/state")]);
    renderConfig(await configResponse.json());
    const initial = await stateResponse.json();
    render(initial); renderAudio(initial.audio);
    const stream = new EventSource("/api/stream");
    stream.addEventListener("state", (event) => { const next=JSON.parse(event.data); render(next); renderAudio(next.audio); });
    stream.onerror = () => { dom.stream.classList.remove("live"); dom.stream.querySelector("span").textContent="RECONNECTING"; };
  } catch (error) {
    dom.stream.querySelector("span").textContent = "OFFLINE";
    dom.audioDetail.textContent = error.message;
  }
  const watchdog = () => {
    if (performance.now() - lastEventAt > 1200) dom.stream.classList.remove("live");
    renderRaf = requestAnimationFrame(watchdog);
  };
  renderRaf = requestAnimationFrame(watchdog);
}

window.addEventListener("beforeunload", () => cancelAnimationFrame(renderRaf));
initialize();
