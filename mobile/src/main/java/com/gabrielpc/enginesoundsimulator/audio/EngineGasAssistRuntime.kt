package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.max

/** Immutable authored automatic-shift assist data used by the render-thread runtime. */
internal class EngineGasAssistSpec(
    val autoShifterGasCutoffMs: Double,
    val engineCutoffMs: Double,
    val autoBlipElectronic: Boolean,
    autoBlipTimesMs: DoubleArray,
    autoBlipPedals: DoubleArray,
    val autoBlipEndTimeMs: Double,
) {
    private val timesMs = autoBlipTimesMs.copyOf()
    private val pedals = autoBlipPedals.copyOf()

    init {
        require(autoShifterGasCutoffMs.isFinite() && autoShifterGasCutoffMs >= 0.0)
        require(engineCutoffMs.isFinite() && engineCutoffMs >= 0.0)
        require(timesMs.size == pedals.size)
        require(timesMs.isEmpty() || timesMs.size == 4)
        require(autoBlipEndTimeMs.isFinite() && autoBlipEndTimeMs >= 0.0)
        var index = 0
        while (index < timesMs.size) {
            require(timesMs[index].isFinite() && timesMs[index] >= 0.0)
            require(pedals[index].isFinite() && pedals[index] in 0.0..1.0)
            index += 1
        }
        if (timesMs.isEmpty()) {
            require(autoBlipEndTimeMs == 0.0)
        } else {
            require(timesMs[0] == 0.0 && pedals[0] == 0.0)
            require(timesMs.last() == autoBlipEndTimeMs && pedals.last() == 0.0)
        }
    }

    val hasAutoBlip: Boolean get() = timesMs.isNotEmpty() && autoBlipEndTimeMs > 0.0

    /**
     * AC's recovered evaluator scans points in insertion order for the first x >= elapsed. It
     * must not binary-search or sort: e.g. 20/130/60 intentionally leaves POINT_1 unreachable
     * because the separate program end is 60 ms.
     */
    fun autoBlipPedalAt(elapsedMs: Double): Double {
        if (!hasAutoBlip || elapsedMs < 0.0 || elapsedMs >= autoBlipEndTimeMs) return 0.0
        if (elapsedMs <= timesMs[0]) return pedals[0]
        var right = 1
        while (right < timesMs.size && elapsedMs > timesMs[right]) right += 1
        if (right >= timesMs.size) return pedals.last()
        val left = right - 1
        val span = timesMs[right] - timesMs[left]
        // Given the first-upper-bound scan, a selected point can equal its predecessor only when
        // elapsed is not above the predecessor; retain the right value defensively for malformed
        // direct test specs even though V2 manifest validation rejects no authored information.
        if (span == 0.0) return pedals[right]
        val fraction = (elapsedMs - timesMs[left]) / span
        return pedals[left] + (pedals[right] - pedals[left]) * fraction
    }

    fun newRuntime(
        throttleMap: AutomationCurve,
        limiterRpm: Double,
        limiterHz: Double,
    ): EngineGasAssistRuntime = EngineGasAssistRuntime(this, throttleMap, limiterRpm, limiterHz)

    companion object {
        val NONE = EngineGasAssistSpec(
            autoShifterGasCutoffMs = 0.0,
            engineCutoffMs = 0.0,
            autoBlipElectronic = false,
            autoBlipTimesMs = DoubleArray(0),
            autoBlipPedals = DoubleArray(0),
            autoBlipEndTimeMs = 0.0,
        )
    }
}

/**
 * Allocation-free render-thread state for AC's exact 3 ms assist/input ordering.
 *
 * The app's presentation gearbox is fully automatic and uses autoclutch. Consequently every
 * accepted automatic downshift represented by a new shift serial satisfies AC's exclusive
 * clutch gate and starts AutoBlip; a rejected/protected request has no serial and cannot blip.
 */
internal class EngineGasAssistRuntime internal constructor(
    private val spec: EngineGasAssistSpec,
    private val throttleMap: AutomationCurve,
    private val limiterRpm: Double,
    limiterHz: Double,
) {
    private val limiterPeriodSteps = if (limiterHz.isFinite() && limiterHz > 0.0) {
        (1_000.0 / limiterHz).toInt() / 3
    } else {
        0
    }
    private var stepAccumulatorSeconds = 0.0
    private var hasShiftSerial = false
    private var lastShiftSerial = 0L
    private var pendingShiftDirection = 0
    private var autoShifterCutRemainingMs = 0.0
    private var engineCutRemainingMs = 0.0
    private var autoBlipElapsedMs = INACTIVE_AUTOBLIP_MS
    private var limiterCutStepsRemaining = 0

    var latestControlsGas: Double = 0.0
        private set
    var latestEngineGas: Double = 0.0
        private set
    var latestMappedEngineGas: Double = 0.0
        private set
    var latestEffectiveEngineGas: Double = 0.0
        private set
    var latestAutoBlipPedal: Double = 0.0
        private set
    var autoShifterCutActive: Boolean = false
        private set
    var engineCutActive: Boolean = false
        private set
    var autoBlipActive: Boolean = false
        private set
    var limiterCutActive: Boolean = false
        private set

    /**
     * Advances assists and turbo physics in one interleaved 3 ms loop. Shift events are retained
     * until a complete physics tick exists, so a short/zero render block cannot lose a cut/blip.
     */
    fun update(
        rawPedal: Double,
        rpm: Double,
        gear: Int,
        shiftSerial: Long,
        shiftDirection: Int,
        elapsedSeconds: Double,
        turboPhysics: TurboPhysicsRuntime?,
    ) {
        if (!hasShiftSerial) {
            lastShiftSerial = shiftSerial
            hasShiftSerial = true
        } else if (shiftSerial != lastShiftSerial) {
            lastShiftSerial = shiftSerial
            pendingShiftDirection = shiftDirection.coerceIn(-1, 1)
        }

        turboPhysics?.beginBlock()
        stepAccumulatorSeconds += elapsedSeconds.coerceAtLeast(0.0)
        val safePedal = if (rawPedal.isFinite()) rawPedal.coerceIn(0.0, 1.0) else 0.0
        val safeRpm = if (rpm.isFinite()) rpm.coerceAtLeast(0.0) else 0.0
        while (stepAccumulatorSeconds + STEP_EPSILON_SECONDS >= AC_PHYSICS_STEP_SECONDS) {
            stepAccumulatorSeconds -= AC_PHYSICS_STEP_SECONDS
            applyPendingShift()

            // AutoBlip runs because the fully automatic presentation path always has autoclutch.
            val blip = spec.autoBlipPedalAt(autoBlipElapsedMs)
            latestAutoBlipPedal = blip
            autoBlipActive = blip > 0.0 ||
                (autoBlipElapsedMs >= 0.0 && autoBlipElapsedMs < spec.autoBlipEndTimeMs)
            val postAutoBlipPedal = max(safePedal, blip)

            autoShifterCutActive = autoShifterCutRemainingMs > 0.0
            latestControlsGas = if (autoShifterCutActive) 0.0 else postAutoBlipPedal
            if (autoShifterCutActive) autoShifterCutRemainingMs -= AC_PHYSICS_STEP_MS

            engineCutActive = engineCutRemainingMs > 0.0
            latestEngineGas = if (engineCutActive) 0.0 else latestControlsGas
            if (engineCutActive) engineCutRemainingMs -= AC_PHYSICS_STEP_MS

            latestMappedEngineGas = throttleMap.valueAt(latestEngineGas).coerceIn(0.0, 1.0)
            if (limiterRpm > 0.0 && safeRpm > limiterRpm) {
                limiterCutStepsRemaining = limiterPeriodSteps
            }
            limiterCutActive = limiterCutStepsRemaining > 0
            if (limiterCutActive) limiterCutStepsRemaining -= 1
            latestEffectiveEngineGas = if (limiterCutActive) 0.0 else latestMappedEngineGas

            turboPhysics?.stepAtPhysicsTick(
                rpm = safeRpm,
                postAssistPedal = latestControlsGas,
                effectiveThrottle = latestEffectiveEngineGas,
                gear = gear,
            )
            if (autoBlipElapsedMs != INACTIVE_AUTOBLIP_MS) {
                autoBlipElapsedMs += AC_PHYSICS_STEP_MS
                if (autoBlipElapsedMs >= spec.autoBlipEndTimeMs) {
                    autoBlipElapsedMs = INACTIVE_AUTOBLIP_MS
                }
            }
        }
        if (stepAccumulatorSeconds < 0.0) stepAccumulatorSeconds = 0.0
    }

    private fun applyPendingShift() {
        when {
            pendingShiftDirection > 0 -> {
                // AutoShifter cuts Car.controls.gas and accepted GearChanger dispatch separately
                // starts Drivetrain's engine-input cutoff on this same physics tick.
                autoShifterCutRemainingMs = spec.autoShifterGasCutoffMs
                engineCutRemainingMs = spec.engineCutoffMs
            }
            pendingShiftDirection < 0 && spec.hasAutoBlip -> {
                autoBlipElapsedMs = 0.0
            }
        }
        pendingShiftDirection = 0
    }

    companion object {
        internal const val AC_PHYSICS_STEP_SECONDS = 0.003
        private const val AC_PHYSICS_STEP_MS = 3.0
        private const val INACTIVE_AUTOBLIP_MS = -1.0
        private const val STEP_EPSILON_SECONDS = 1e-12
    }
}
