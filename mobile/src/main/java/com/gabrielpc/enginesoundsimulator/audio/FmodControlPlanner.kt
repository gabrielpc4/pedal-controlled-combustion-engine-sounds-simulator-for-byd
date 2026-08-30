package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

/**
 * Allocation-free values copied into the JNI control buffer on the 400 Hz FMOD worker.
 * Event serials are owned here so a source serial reset cannot replay an old native event.
 */
class FmodControlState internal constructor() {
    var flags: Int = 0
        internal set
    var rpm: Float = FmodCarProfiles.default.idleRpm.toFloat()
        internal set
    var engineThrottle: Float = 0f
        internal set
    var boost: Float = 0f
        internal set
    var bov: Float = 0f
        internal set
    var bovDecaySeconds: Float = MAX_DECAY_SECONDS.toFloat()
        internal set
    var limiterDecaySeconds: Float = MAX_DECAY_SECONDS.toFloat()
        internal set
    var masterGain: Float = 1f
        internal set
    var engineGain: Float = 1f
        internal set
    var turboGain: Float = 1f
        internal set
    var limiterGain: Float = 1f
        internal set
    var shiftGain: Float = 1f
        internal set
    var backfireGain: Float = 1f
        internal set
    var drivetrainSpeed: Float = 0f
        internal set
    var transmissionThrottle: Float = 0f
        internal set
    var transmissionGain: Float = 1f
        internal set
    var shiftDirection: Int = 0
        internal set
    var shiftSerial: Long = 0L
        internal set
    var limiterSerial: Long = 0L
        internal set
    var bovSerial: Long = 0L
        internal set
    var backfireSerial: Long = 0L
        internal set

    val audioEnabled: Boolean get() = flags and FLAG_AUDIO_ENABLED != 0

    fun eventEnabled(kind: FmodEventKind): Boolean = flags and eventFlag(kind) != 0

    companion object {
        const val FLAG_AUDIO_ENABLED = 1 shl 0
        const val FLAG_ENGINE_ENABLED = 1 shl 1
        const val FLAG_TURBO_ENABLED = 1 shl 2
        const val FLAG_LIMITER_ENABLED = 1 shl 3
        const val FLAG_SHIFTS_ENABLED = 1 shl 4
        const val FLAG_BACKFIRE_ENABLED = 1 shl 5
        const val FLAG_TRANSMISSION_ENABLED = 1 shl 6
        internal const val MAX_DECAY_SECONDS = 10.0

        fun eventFlag(kind: FmodEventKind): Int = when (kind) {
            FmodEventKind.ENGINE -> FLAG_ENGINE_ENABLED
            FmodEventKind.TURBO -> FLAG_TURBO_ENABLED
            FmodEventKind.LIMITER -> FLAG_LIMITER_ENABLED
            FmodEventKind.SHIFTS -> FLAG_SHIFTS_ENABLED
            FmodEventKind.BACKFIRE -> FLAG_BACKFIRE_ENABLED
            FmodEventKind.TRANSMISSION -> FLAG_TRANSMISSION_ENABLED
        }
    }
}

/** Converts raw EV pedal/tach state into Assetto's FMOD parameter and trigger domains. */
class FmodControlPlanner(
    private val profile: FmodCarProfile = FmodCarProfiles.default,
) {
    private val output = FmodControlState()
    private var turboCharge = 0.0
    private var sourceShiftInitialized = false
    private var lastSourceShiftSerial = 0L
    private var limiterWasActive = false
    private var limiterPulseElapsedSeconds = 0.0
    private var limiterDecaySeconds = FmodControlState.MAX_DECAY_SECONDS
    private var bovWasActive = false
    private var bovDecaySeconds = FmodControlState.MAX_DECAY_SECONDS
    private var backfireArmed = false
    private var backfireCooldownSeconds = profile.backfire?.debounceSeconds ?: 0.0
    private var cachedMixSettings: FmodEventMixSettings? = null
    private var cachedEnabledEventFlags = 0

    /** Returns the same mutable state object on every call. Consume it before the next update. */
    fun update(frame: EngineAudioFrame, deltaSeconds: Double): FmodControlState {
        val dt = deltaSeconds.coerceIn(MIN_UPDATE_SECONDS, MAX_UPDATE_SECONDS)
        val throttle = frame.throttle.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val rpm = frame.rpm.takeIf(Double::isFinite)?.coerceIn(0.0, profile.maximumRpm) ?: 0.0

        updateMix(frame)
        output.rpm = rpm.toFloat()
        // Coast Only affects engine_int alone. Load Only also holds an authored transmission event
        // at full load to match the desktop lab. Turbo, backfire detection, EV torque, and tach
        // behavior continue to observe the real pedal. Coast wins if stale preferences contain both.
        output.engineThrottle = when {
            frame.coastOnlyEnabled -> 0f
            frame.loadOnlyEnabled -> 1f
            else -> throttle.toFloat()
        }
        output.masterGain = frame.masterGain.takeIf(Double::isFinite)?.coerceAtLeast(0.0)?.toFloat() ?: 0f
        output.transmissionThrottle = if (frame.loadOnlyEnabled && !frame.coastOnlyEnabled) {
            1f
        } else {
            throttle.toFloat()
        }
        val transmissionMaximum = profile.transmissionSpeedMaximumRadPerSecond
        output.drivetrainSpeed = if (
            transmissionMaximum != null && profile.supports(FmodEventKind.TRANSMISSION)
        ) {
            frame.drivetrainSpeed
                .takeIf(Double::isFinite)
                ?.coerceIn(-transmissionMaximum, transmissionMaximum)
                ?.toFloat()
                ?: 0f
        } else {
            0f
        }

        updateTurbo(rpm = rpm, throttle = throttle, enabled = frame.enabled, dt = dt)
        updateShift(frame)
        updateLimiter(active = frame.enabled && frame.limiterActive, dt = dt)
        updateBackfire(rpm = rpm, throttle = throttle, enabled = frame.enabled, dt = dt)
        return output
    }

    private fun updateMix(frame: EngineAudioFrame) {
        val settings = frame.eventMixSettings
        if (settings !== cachedMixSettings) {
            cachedMixSettings = settings
            cachedEnabledEventFlags = 0

            val engine = settings.control(FmodEventKind.ENGINE)
            if (engine.enabled && profile.supports(FmodEventKind.ENGINE)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_ENGINE_ENABLED
            }
            output.engineGain = dbToLinear(engine.gainDb).toFloat()

            val turbo = settings.control(FmodEventKind.TURBO)
            if (turbo.enabled && profile.supports(FmodEventKind.TURBO)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_TURBO_ENABLED
            }
            output.turboGain = dbToLinear(turbo.gainDb).toFloat()

            val limiter = settings.control(FmodEventKind.LIMITER)
            if (limiter.enabled && profile.supports(FmodEventKind.LIMITER)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_LIMITER_ENABLED
            }
            output.limiterGain = dbToLinear(limiter.gainDb).toFloat()

            val shifts = settings.control(FmodEventKind.SHIFTS)
            if (shifts.enabled && profile.supports(FmodEventKind.SHIFTS)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_SHIFTS_ENABLED
            }
            output.shiftGain = dbToLinear(shifts.gainDb).toFloat()

            val backfire = settings.control(FmodEventKind.BACKFIRE)
            if (backfire.enabled && profile.supports(FmodEventKind.BACKFIRE)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_BACKFIRE_ENABLED
            }
            output.backfireGain = dbToLinear(backfire.gainDb).toFloat()

            val transmission = settings.control(FmodEventKind.TRANSMISSION)
            if (transmission.enabled && profile.supports(FmodEventKind.TRANSMISSION)) {
                cachedEnabledEventFlags = cachedEnabledEventFlags or FmodControlState.FLAG_TRANSMISSION_ENABLED
            }
            output.transmissionGain = dbToLinear(transmission.gainDb).toFloat()
        }

        val audioEnabledFlag = if (frame.enabled) {
            FmodControlState.FLAG_AUDIO_ENABLED
        } else {
            0
        }
        output.flags = cachedEnabledEventFlags or audioEnabledFlag
    }

    private fun updateTurbo(rpm: Double, throttle: Double, enabled: Boolean, dt: Double) {
        val turbo = profile.turbo
        if (turbo == null || !profile.supports(FmodEventKind.TURBO)) {
            turboCharge = 0.0
            bovWasActive = false
            bovDecaySeconds = FmodControlState.MAX_DECAY_SECONDS
            output.boost = 0f
            output.bov = 0f
            output.bovDecaySeconds = FmodControlState.MAX_DECAY_SECONDS.toFloat()
            return
        }
        val turboInput = if (enabled) {
            (throttle * rpm / turbo.referenceRpm).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val target = turboInput.pow(turbo.gamma)
        val lag = if (target > turboCharge) turbo.lagUp else turbo.lagDown
        turboCharge += (dt * lag).coerceIn(0.0, 1.0) * (target - turboCharge)
        turboCharge = turboCharge.coerceIn(0.0, turbo.normalizedBoostCap)
        output.boost = turboCharge.toFloat()

        val bovActive = turbo.bovAudible && enabled &&
            turboCharge * turbo.maximumBoost * (1.0 - throttle) > turbo.bovThreshold
        if (bovActive) {
            bovDecaySeconds = if (turbo.bovDecayAudible) 0.0 else FmodControlState.MAX_DECAY_SECONDS
            if (!bovWasActive) output.bovSerial += 1L
        } else {
            bovDecaySeconds = if (turbo.bovDecayAudible) {
                (bovDecaySeconds + dt).coerceAtMost(FmodControlState.MAX_DECAY_SECONDS)
            } else {
                FmodControlState.MAX_DECAY_SECONDS
            }
        }
        bovWasActive = bovActive
        output.bov = if (bovActive) 1f else 0f
        output.bovDecaySeconds = bovDecaySeconds.toFloat()
    }

    private fun updateShift(frame: EngineAudioFrame) {
        output.shiftDirection = 0
        val sourceSerial = frame.shiftSerial.coerceAtLeast(0L)
        if (!sourceShiftInitialized) {
            sourceShiftInitialized = true
            lastSourceShiftSerial = sourceSerial
            return
        }
        if (sourceSerial <= lastSourceShiftSerial) {
            // A lower value is a source reset, not a new shift edge.
            if (sourceSerial < lastSourceShiftSerial) lastSourceShiftSerial = sourceSerial
            return
        }

        val serialDelta = sourceSerial - lastSourceShiftSerial
        lastSourceShiftSerial = sourceSerial
        val direction = frame.shiftDirection.coerceIn(-1, 1)
        if (
            profile.supports(FmodEventKind.SHIFTS) &&
            frame.enabled && serialDelta > 0L && direction != 0
        ) {
            output.shiftDirection = direction
            output.shiftSerial += serialDelta
        }
    }

    private fun updateLimiter(active: Boolean, dt: Double) {
        if (!profile.supports(FmodEventKind.LIMITER) || profile.limiterHz <= 0.0) {
            limiterWasActive = false
            limiterPulseElapsedSeconds = 0.0
            limiterDecaySeconds = FmodControlState.MAX_DECAY_SECONDS
            output.limiterDecaySeconds = FmodControlState.MAX_DECAY_SECONDS.toFloat()
            return
        }
        val pulseSeconds = 1.0 / profile.limiterHz
        if (!active) {
            limiterWasActive = false
            limiterPulseElapsedSeconds = 0.0
            limiterDecaySeconds =
                (limiterDecaySeconds + dt).coerceAtMost(FmodControlState.MAX_DECAY_SECONDS)
            output.limiterDecaySeconds = limiterDecaySeconds.toFloat()
            return
        }

        if (!limiterWasActive) {
            limiterWasActive = true
            limiterPulseElapsedSeconds = 0.0
            limiterDecaySeconds = 0.0
            output.limiterSerial += 1L
        } else {
            limiterPulseElapsedSeconds += dt
            if (limiterPulseElapsedSeconds >= pulseSeconds) {
                val pulses = (limiterPulseElapsedSeconds / pulseSeconds).toLong()
                output.limiterSerial += pulses
                limiterPulseElapsedSeconds -= pulses * pulseSeconds
                limiterDecaySeconds = limiterPulseElapsedSeconds
            } else {
                limiterDecaySeconds += dt
            }
        }
        output.limiterDecaySeconds = limiterDecaySeconds.toFloat()
    }

    private fun updateBackfire(rpm: Double, throttle: Double, enabled: Boolean, dt: Double) {
        val behavior = profile.backfire
        if (behavior == null || !profile.supports(FmodEventKind.BACKFIRE)) {
            backfireArmed = false
            return
        }
        backfireCooldownSeconds =
            (backfireCooldownSeconds + dt).coerceAtMost(FmodControlState.MAX_DECAY_SECONDS)
        if (!enabled) {
            backfireArmed = false
            return
        }
        if (throttle > behavior.armThrottle) {
            backfireArmed = true
            return
        }
        val released = if (behavior.exactZeroRelease) {
            throttle == 0.0
        } else {
            throttle < behavior.fireThrottle
        }
        if (backfireArmed && released) {
            if (
                rpm > behavior.minimumRpm &&
                rpm <= behavior.maximumRpm &&
                backfireCooldownSeconds >= behavior.debounceSeconds
            ) {
                output.backfireSerial += 1L
                backfireCooldownSeconds = 0.0
            }
            // Consume the release edge even if RPM/cooldown rejected it; a new high-pedal edge must re-arm.
            backfireArmed = false
        }
    }

    companion object {
        const val CONTROL_HZ = 400.0
        const val TURBO_REFERENCE_RPM = 3_400.0
        const val TURBO_GAMMA = 2.0
        const val TURBO_LAG_UP = 0.9988
        const val TURBO_LAG_DOWN = 0.995
        const val TURBO_NORMALIZED_CAP = 1.0 / 3.0
        const val TURBO_TOTAL_MAXIMUM_BOOST = 2.4
        const val TURBO_BOV_THRESHOLD = 0.5
        const val LIMITER_HZ = 50.0
        const val LIMITER_PULSE_SECONDS = 1.0 / LIMITER_HZ
        const val BACKFIRE_MINIMUM_RPM = 4_750.0
        const val BACKFIRE_ARM_THROTTLE = 0.8
        const val BACKFIRE_FIRE_THROTTLE = 0.3
        const val BACKFIRE_DEBOUNCE_SECONDS = 1.0
        private const val MIN_UPDATE_SECONDS = 1.0 / 1_000.0
        private const val MAX_UPDATE_SECONDS = 0.100
    }
}

internal fun dbToLinear(gainDb: Double): Double =
    10.0.pow(gainDb.coerceIn(FmodEventMixSettings.MIN_GAIN_DB, FmodEventMixSettings.MAX_GAIN_DB) / 20.0)
