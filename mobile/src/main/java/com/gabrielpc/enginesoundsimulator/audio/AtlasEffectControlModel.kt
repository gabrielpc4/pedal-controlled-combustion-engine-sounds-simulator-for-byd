package com.gabrielpc.enginesoundsimulator.audio

/** Allocation-free Audio Lab control reconstruction shared by every atlas effect event. */
internal class AtlasEffectControlModel(
    private val physics: AtlasCarAudioPhysics,
    private val drivenWheelRadiusMeters: Double,
) {
    private val turbo = AtlasTurboDynamics(physics)
    private var initialized = false
    private var previousShiftSerial = 0L
    private var previousShiftRejectedSerial = 0L
    private var previousLimiterActive = false
    private var previousTractionLimitActive = false
    private var previousEngineStarting = false
    private var limiterHoldSeconds = 0.0
    private var limiterDecaySeconds = MAXIMUM_DECAY_SECONDS
    private var tractionDecaySeconds = MAXIMUM_DECAY_SECONDS
    private var triggerMask = 0L
    private var backfirePeakGas = INITIAL_BACKFIRE_PEAK_GAS
    private var backfireArmLevel = physics.backfire.triggerGas
    private var backfireFireBelow = INITIAL_BACKFIRE_FIRE_BELOW
    private var backfireArmed = false
    private var backfireArmedSeconds = 0.0
    private var intentSeconds = 0.0
    private var intentQualified = false
    private var pendingBackfireDelaySeconds = 0.0

    var rpm: Double = 0.0
        private set
    var liveThrottle: Double = 0.0
        private set
    var programThrottle: Double = 0.0
        private set
    var drivetrainSpeedRadiansPerSecond: Double = 0.0
        private set
    var shiftState: Double = 0.0
        private set
    val boost: Double get() = turbo.boost
    val bov: Double get() = turbo.bov
    val bovDecay: Double get() = turbo.bovDecay
    val limiterDecay: Double get() = limiterDecaySeconds
    val tractionDecay: Double get() = tractionDecaySeconds
    val hasTurbo: Boolean get() = physics.turbos.isNotEmpty()

    fun update(
        frame: EngineAudioFrame,
        dt: Double,
        effectiveProgramThrottle: Double = frame.throttle,
        selectedEngineEventActivationStarted: Boolean = false,
    ) {
        val seconds = dt.coerceIn(0.0, MAXIMUM_UPDATE_SECONDS)
        triggerMask = 0L
        rpm = frame.rpm.coerceAtLeast(0.0)
        liveThrottle = frame.throttle.coerceIn(0.0, 1.0)
        programThrottle = effectiveProgramThrottle.coerceIn(0.0, 1.0)
        drivetrainSpeedRadiansPerSecond =
            frame.presentationSpeedMetersPerSecond / drivenWheelRadiusMeters
        shiftState = if (frame.shiftDirection > 0) 1.0 else 0.0
        val firstUpdate = !initialized

        turbo.update(
            dt = seconds,
            rpm = rpm,
            effectiveThrottle = liveThrottle,
            attackMultiplier = frame.turboSpoolAttackMultiplier,
        )
        if (turbo.consumeDumpPulse()) fire(AtlasRuntimeTrigger.TURBO_DUMP)
        if (hasTurbo) fire(AtlasRuntimeTrigger.TURBO_LOOP)

        if (!initialized) {
            initialized = true
            previousShiftSerial = frame.shiftSerial
            previousShiftRejectedSerial = frame.shiftRejectedSerial
            previousLimiterActive = frame.limiterActive
            previousTractionLimitActive = frame.tractionLimitActive
            previousEngineStarting = frame.engineStarting
            if (frame.engineStarting) fire(AtlasRuntimeTrigger.ENGINE_START)
        } else {
            if (frame.shiftSerial > previousShiftSerial) {
                if (!frame.engineStarting) {
                    fire(if (frame.shiftDirection < 0) AtlasRuntimeTrigger.SHIFT_DOWN else AtlasRuntimeTrigger.SHIFT_UP)
                }
                previousShiftSerial = frame.shiftSerial
            }
            if (frame.shiftRejectedSerial > previousShiftRejectedSerial) {
                fire(AtlasRuntimeTrigger.SHIFT_REJECTED)
                previousShiftRejectedSerial = frame.shiftRejectedSerial
            }
            if (frame.engineStarting && !previousEngineStarting) fire(AtlasRuntimeTrigger.ENGINE_START)
            previousEngineStarting = frame.engineStarting
        }
        if (selectedEngineEventActivationStarted || firstUpdate) {
            fire(AtlasRuntimeTrigger.ENGINE_EVENT_START)
        }

        updateLimiter(frame.limiterActive, seconds)
        updateTractionLimit(frame.tractionLimitActive, seconds)
        updateBackfire(frame, seconds)
    }

    fun isTriggered(trigger: AtlasRuntimeTrigger): Boolean =
        triggerMask and (1L shl trigger.ordinal) != 0L

    fun isContinuousActive(trigger: AtlasRuntimeTrigger): Boolean = when (trigger) {
        AtlasRuntimeTrigger.TRANSMISSION_LOOP -> true
        AtlasRuntimeTrigger.TURBO_LOOP -> hasTurbo
        AtlasRuntimeTrigger.LIMITER_LOOP -> limiterHoldSeconds > 0.0
        AtlasRuntimeTrigger.TRACTION_LIMIT -> tractionDecaySeconds == 0.0
        else -> false
    }

    fun parameter(name: String, trigger: AtlasRuntimeTrigger): Double = when (name) {
        "rpms" -> rpm
        "drivetrain_speed" -> drivetrainSpeedRadiansPerSecond
        "throttle" -> if (trigger == AtlasRuntimeTrigger.THROTTLE_LIFT) BACKFIRE_AUDIO_THROTTLE else programThrottle
        "state" -> shiftState
        "boost" -> boost
        "bov" -> bov
        "bov_decay" -> bovDecay
        "decay" -> when (trigger) {
            AtlasRuntimeTrigger.TRACTION_LIMIT,
            AtlasRuntimeTrigger.TRACTION_PULSE,
            -> tractionDecay
            else -> limiterDecay
        }
        else -> throw IllegalArgumentException("Unsupported atlas runtime parameter $name")
    }

    private fun updateLimiter(inputActive: Boolean, dt: Double) {
        if (inputActive) {
            limiterHoldSeconds = maxOf(limiterHoldSeconds, 1.0 / physics.limiterFrequencyHz)
            limiterDecaySeconds = 0.0
            fire(AtlasRuntimeTrigger.LIMITER_LOOP)
            if (!previousLimiterActive) fire(AtlasRuntimeTrigger.LIMITER_PULSE)
        } else {
            limiterHoldSeconds = (limiterHoldSeconds - dt).coerceAtLeast(0.0)
            limiterDecaySeconds = (limiterDecaySeconds + dt).coerceAtMost(MAXIMUM_DECAY_SECONDS)
            if (limiterHoldSeconds > 0.0) fire(AtlasRuntimeTrigger.LIMITER_LOOP)
        }
        previousLimiterActive = inputActive
    }

    private fun updateTractionLimit(active: Boolean, dt: Double) {
        if (active) {
            tractionDecaySeconds = 0.0
            fire(AtlasRuntimeTrigger.TRACTION_LIMIT)
            if (!previousTractionLimitActive) fire(AtlasRuntimeTrigger.TRACTION_PULSE)
        } else {
            tractionDecaySeconds = (tractionDecaySeconds + dt).coerceAtMost(MAXIMUM_DECAY_SECONDS)
        }
        previousTractionLimitActive = active
    }

    private fun updateBackfire(frame: EngineAudioFrame, dt: Double) {
        if (!frame.throttleLiftEffectsEnabled) {
            resetBackfireIntent()
            return
        }
        if (liveThrottle > backfirePeakGas && liveThrottle != 0.0) {
            backfirePeakGas = liveThrottle
            backfireArmLevel = physics.backfire.triggerGas * liveThrottle
            backfireFireBelow = physics.backfire.maximumGas * liveThrottle
        }
        if (liveThrottle >= physics.backfire.minimumIntentThrottle) {
            intentSeconds = (intentSeconds + dt).coerceAtMost(MAXIMUM_DECAY_SECONDS)
            intentQualified = intentQualified || intentSeconds >= physics.backfire.minimumIntentSeconds
        }
        if (liveThrottle > backfireArmLevel) backfireArmed = true
        if (backfireArmed) backfireArmedSeconds = (backfireArmedSeconds + dt).coerceAtMost(MAXIMUM_DECAY_SECONDS)

        val detectorThrottle = if (liveThrottle <= GLOBAL_LIFT_FIRE_THROTTLE) {
            BACKFIRE_AUDIO_THROTTLE
        } else {
            liveThrottle
        }
        val donorGate = backfireArmed &&
            intentQualified &&
            detectorThrottle in Double.MIN_VALUE..backfireFireBelow &&
            rpm > physics.backfire.minimumRpm &&
            rpm <= physics.backfire.maximumRpm &&
            backfireArmedSeconds > physics.backfire.minimumIntentSeconds
        if (donorGate && pendingBackfireDelaySeconds <= 0.0) {
            pendingBackfireDelaySeconds = BACKFIRE_DELAY_SECONDS
            backfireArmed = false
            intentQualified = false
            intentSeconds = 0.0
        }
        if (pendingBackfireDelaySeconds > 0.0) {
            pendingBackfireDelaySeconds -= dt
            if (pendingBackfireDelaySeconds <= 0.0) {
                pendingBackfireDelaySeconds = 0.0
                fire(AtlasRuntimeTrigger.THROTTLE_LIFT)
            }
        }
        if (liveThrottle <= GLOBAL_LIFT_FIRE_THROTTLE && pendingBackfireDelaySeconds <= 0.0) {
            backfireArmed = false
            backfireArmedSeconds = 0.0
            intentSeconds = 0.0
            intentQualified = false
        }
    }

    private fun resetBackfireIntent() {
        backfireArmed = false
        backfireArmedSeconds = 0.0
        intentSeconds = 0.0
        intentQualified = false
        pendingBackfireDelaySeconds = 0.0
    }

    private fun fire(trigger: AtlasRuntimeTrigger) {
        triggerMask = triggerMask or (1L shl trigger.ordinal)
    }

    private companion object {
        const val MAXIMUM_UPDATE_SECONDS = 0.080
        const val MAXIMUM_DECAY_SECONDS = 10.0
        const val INITIAL_BACKFIRE_PEAK_GAS = 0.6
        const val INITIAL_BACKFIRE_FIRE_BELOW = 0.25
        const val GLOBAL_LIFT_FIRE_THROTTLE = 0.08
        const val BACKFIRE_AUDIO_THROTTLE = 0.01
        const val BACKFIRE_DELAY_SECONDS = 0.18
    }
}
