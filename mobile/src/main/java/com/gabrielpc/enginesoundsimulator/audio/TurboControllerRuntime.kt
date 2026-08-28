package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.pow

/** AC telemetry channels supported by official ctrl_turbo*.ini files. */
internal enum class TurboControllerInput {
    RPM, THROTTLE, GEAR,
}

internal enum class TurboControllerCombinator {
    ADD, MULTIPLY,
}

/** Identifies tracks whose authored level follows AC's normalized boost signal. */
internal enum class TurboAudioResponse {
    NONE, BOOST,
}

/**
 * Immutable, allocation-free lookup stage compiled from one CONTROLLER_n section.
 *
 * AC converts FILTER to a float32 rate and advances this controller only at the 3 ms physics
 * step. The recovered binary also has a 0.001 deadband around its zero-initialized current value.
 */
internal class TurboControllerStageSpec(
    val input: TurboControllerInput,
    val combinator: TurboControllerCombinator,
    inputPoints: DoubleArray,
    outputPoints: DoubleArray,
    filter: Double,
    val downLimit: Double,
    val upLimit: Double,
) {
    private val inputs = inputPoints.copyOf()
    private val outputs = outputPoints.copyOf()
    private val filterRatePerSecond =
        (1.0f - filter.toFloat()) * FILTER_RATE_SCALE * FILTER_REFERENCE_HZ

    val minimumCurveOutput: Double
    val maximumCurveOutput: Double

    init {
        require(inputs.isNotEmpty() && inputs.size == outputs.size) {
            "Turbo controller LUT must contain matching input/output points"
        }
        require(inputs[0].isFinite() && outputs[0].isFinite()) {
            "Turbo controller LUT points must be finite"
        }
        var index = 1
        while (index < inputs.size) {
            require(inputs[index].isFinite()) { "Turbo controller LUT input must be finite" }
            require(inputs[index] > inputs[index - 1]) { "Turbo controller LUT inputs must increase" }
            index += 1
        }
        require(filter.isFinite() && filter in 0.0..1.0) { "Turbo controller FILTER must be 0..1" }
        require(downLimit.isFinite() && upLimit.isFinite() && downLimit < upLimit) {
            "Turbo controller limits are invalid"
        }
        var minimum = outputs[0]
        var maximum = outputs[0]
        index = 1
        while (index < outputs.size) {
            val output = outputs[index]
            require(output.isFinite()) { "Turbo controller LUT output must be finite" }
            if (output < minimum) minimum = output
            if (output > maximum) maximum = output
            index += 1
        }
        minimumCurveOutput = minimum
        maximumCurveOutput = maximum
    }

    fun valueAt(value: Double): Double {
        val finiteValue = if (value.isFinite()) value else inputs[0]
        if (finiteValue <= inputs[0]) return outputs[0]
        val last = inputs.lastIndex
        if (finiteValue >= inputs[last]) return outputs[last]
        var right = 1
        while (right < inputs.size && finiteValue > inputs[right]) right += 1
        val left = right - 1
        val fraction = (finiteValue - inputs[left]) / (inputs[right] - inputs[left])
        return outputs[left] + (outputs[right] - outputs[left]) * fraction
    }

    fun filter(previous: Float, target: Double, elapsedSeconds: Double): Float {
        val targetFloat = target.toFloat()
        val difference = targetFloat - previous
        if (abs(difference) < FILTER_DEADBAND) return previous
        val alpha = (elapsedSeconds.toFloat().coerceAtLeast(0.0f) * filterRatePerSecond)
            .coerceIn(0.0f, 1.0f)
        return previous + alpha * difference
    }

    companion object {
        internal const val AC_PHYSICS_STEP_SECONDS = 0.003
        private const val FILTER_RATE_SCALE = 1.333333373f
        private const val FILTER_REFERENCE_HZ = 333.333343f
        private const val FILTER_DEADBAND = 0.001f
    }
}

internal class TurboControllerProgramSpec(
    val sourceFile: String,
    stages: Array<TurboControllerStageSpec>,
) {
    val stages: Array<TurboControllerStageSpec> = stages.copyOf()
    val maximumOutput: Double

    init {
        require(this.stages.isNotEmpty()) { "Turbo controller program cannot be empty" }
        require(this.stages[0].combinator == TurboControllerCombinator.ADD) {
            "Turbo controller program must establish its value with ADD"
        }
        var low = 0.0
        var high = 0.0
        var index = 0
        while (index < this.stages.size) {
            val stage = this.stages[index]
            when (stage.combinator) {
                TurboControllerCombinator.ADD -> {
                    low += stage.minimumCurveOutput
                    high += stage.maximumCurveOutput
                }
                TurboControllerCombinator.MULTIPLY -> {
                    val a = low * stage.minimumCurveOutput
                    val b = low * stage.maximumCurveOutput
                    val c = high * stage.minimumCurveOutput
                    val d = high * stage.maximumCurveOutput
                    low = minOf(a, b, c, d)
                    high = maxOf(a, b, c, d)
                }
            }
            low = low.coerceIn(stage.downLimit, stage.upLimit)
            high = high.coerceIn(stage.downLimit, stage.upLimit)
            index += 1
        }
        maximumOutput = high.coerceAtLeast(0.0)
    }
}

internal class TurboControllerBankSpec(
    val turboCount: Int,
    programs: Array<TurboControllerProgramSpec>,
) {
    val programs: Array<TurboControllerProgramSpec> = programs.copyOf()

    /** A partial set cannot be normalized to AC's total boost without missing turbo physics. */
    val hasCompleteAudioCoverage: Boolean = turboCount > 0 && this.programs.size == turboCount

    init {
        require(turboCount > 0) { "Turbo controller bank requires at least one turbo" }
        require(this.programs.isNotEmpty()) { "Turbo controller bank requires at least one program" }
        require(this.programs.size <= turboCount) {
            "Turbo controller files exceed the authored turbo count"
        }
        require(this.programs.map { it.sourceFile }.toSet().size == this.programs.size) {
            "Turbo controller source files must be unique"
        }
    }

    fun newRuntime(): TurboControllerBankRuntime = TurboControllerBankRuntime(this)

    fun programIndex(sourceFile: String): Int = programs.indexOfFirst { it.sourceFile == sourceFile }
}

/** Mutable render-thread state; update performs no allocation, locking, or file access. */
internal class TurboControllerBankRuntime internal constructor(
    private val spec: TurboControllerBankSpec,
) {
    private val programs = Array(spec.programs.size) { index ->
        TurboControllerProgramRuntime(spec.programs[index])
    }
    private val normalization = spec.programs.sumOf { it.maximumOutput }
    private val absoluteOutputs = DoubleArray(programs.size)

    val outputCount: Int get() = absoluteOutputs.size

    var latestNormalizedOutput: Double = 1.0
        private set

    fun update(rpm: Double, throttle: Double, gear: Int, elapsedSeconds: Double): Double {
        updateInto(rpm, throttle, gear, elapsedSeconds, absoluteOutputs)
        var total = 0.0
        var index = 0
        while (index < programs.size) {
            total += absoluteOutputs[index]
            index += 1
        }
        val normalized = if (normalization > MIN_NORMALIZATION) {
            (total / normalization).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        latestNormalizedOutput = normalized
        // Do not invent the absent static-turbo pressure needed to normalize a partial bank.
        return if (spec.hasCompleteAudioCoverage) normalized else 1.0
    }

    /**
     * Evaluates every ctrl_turbo program into caller-owned storage. Values are absolute dynamic
     * wastegate pressures in bar, not a normalized audio gain. The method allocates nothing.
     */
    fun updateInto(
        rpm: Double,
        throttle: Double,
        gear: Int,
        elapsedSeconds: Double,
        destination: DoubleArray,
    ) {
        require(destination.size >= programs.size) { "Turbo controller output buffer is too small" }
        var index = 0
        while (index < programs.size) {
            val output = programs[index].update(rpm, throttle, gear, elapsedSeconds)
            absoluteOutputs[index] = output
            destination[index] = output
            index += 1
        }
    }

    fun latestAbsoluteOutput(index: Int): Double = absoluteOutputs[index]

    private class TurboControllerProgramRuntime(
        private val spec: TurboControllerProgramSpec,
    ) {
        private val filteredValues = FloatArray(spec.stages.size)
        fun update(rpm: Double, throttle: Double, gear: Int, elapsedSeconds: Double): Double {
            var value = 0.0
            var index = 0
            while (index < spec.stages.size) {
                val stage = spec.stages[index]
                val input = when (stage.input) {
                    TurboControllerInput.RPM -> rpm
                    TurboControllerInput.THROTTLE -> throttle
                    TurboControllerInput.GEAR -> gear.toDouble()
                }
                val raw = stage.valueAt(input)
                val filtered = stage.filter(filteredValues[index], raw, elapsedSeconds)
                filteredValues[index] = filtered
                value = when (stage.combinator) {
                    TurboControllerCombinator.ADD -> value + filtered.toDouble()
                    TurboControllerCombinator.MULTIPLY -> value * filtered.toDouble()
                }.coerceIn(stage.downLimit, stage.upLimit)
                index += 1
            }
            return value
        }
    }

    companion object {
        private const val MIN_NORMALIZATION = 1e-9
    }
}

internal class TurboPhysicsUnitSpec(
    val maximumBoost: Double,
    val wastegate: Double,
    val referenceRpm: Double,
    val gamma: Double,
    val lagUp: Double,
    val lagDown: Double,
    /** Index in [TurboControllerBankSpec.programs], or -1 for the static authored wastegate. */
    val controllerProgramIndex: Int,
) {
    init {
        require(maximumBoost.isFinite() && maximumBoost > 0.0)
        require(wastegate.isFinite() && wastegate >= 0.0)
        require(referenceRpm.isFinite() && referenceRpm > 0.0)
        require(gamma.isFinite() && gamma > 0.0)
        require(lagUp.isFinite() && lagUp >= 0.0)
        require(lagDown.isFinite() && lagDown >= 0.0)
        require(controllerProgramIndex >= -1)
    }
}

internal class TurboPhysicsSpec(
    val bovPressureThreshold: Double,
    units: Array<TurboPhysicsUnitSpec>,
    val controllerBank: TurboControllerBankSpec?,
) {
    val units: Array<TurboPhysicsUnitSpec> = units.copyOf()

    init {
        require(bovPressureThreshold.isFinite() && bovPressureThreshold >= 0.0)
        require(this.units.isNotEmpty()) { "Turbo physics requires at least one authored turbo" }
        val controllerCount = controllerBank?.programs?.size ?: 0
        val referenced = BooleanArray(controllerCount)
        var index = 0
        while (index < this.units.size) {
            val controllerIndex = this.units[index].controllerProgramIndex
            require(controllerIndex < controllerCount) { "Turbo physics controller index is invalid" }
            if (controllerIndex >= 0) {
                require(!referenced[controllerIndex]) { "Turbo controller must map to exactly one turbo" }
                referenced[controllerIndex] = true
            }
            index += 1
        }
        require(referenced.all { it }) { "Every turbo controller must map to exactly one turbo" }
    }

    fun newRuntime(): TurboPhysicsRuntime = TurboPhysicsRuntime(this)
}

/** Exact 3 ms AC turbo spool/pressure state, owned exclusively by the render thread. */
internal class TurboPhysicsRuntime internal constructor(
    private val spec: TurboPhysicsSpec,
) {
    private val spool = DoubleArray(spec.units.size)
    private val controllerRuntime = spec.controllerBank?.newRuntime()
    private val controllerOutputs = DoubleArray(controllerRuntime?.outputCount ?: 0)
    private val maximumBoostSum = spec.units.sumOf { it.maximumBoost }
    private var stepAccumulatorSeconds = 0.0
    private var bovWasActive = false
    private var limiterCutStepsRemaining = 0

    var latestTotalBoost: Double = 0.0
        private set
    var latestNormalizedBoost: Double = 0.0
        private set
    var latestBovValue: Double = 0.0
        private set
    var latestBovDecaySeconds: Double = MAX_BOV_DECAY_SECONDS
        private set
    var bovRisingEdge: Boolean = false
        private set
    var bovRisingEdgeBoost: Double = 0.0
        private set

    var latestEffectiveThrottle: Double = 0.0
        private set

    /** Advances only in exact AC physics ticks and allocates no temporary objects. */
    fun update(
        rpm: Double,
        effectiveThrottle: Double,
        gear: Int,
        elapsedSeconds: Double,
    ): Double = update(
        rpm = rpm,
        postAssistPedal = effectiveThrottle,
        mappedEngineGas = effectiveThrottle,
        limiterReload = false,
        limiterHz = 20.0,
        gear = gear,
        elapsedSeconds = elapsedSeconds,
    )

    /**
     * Full AC input chain. [postAssistPedal] remains distinct from throttle.lut output so the
     * pending ctrl_turbo GAS oracle can be corrected in one place without changing call sites.
     */
    fun update(
        rpm: Double,
        postAssistPedal: Double,
        mappedEngineGas: Double,
        limiterReload: Boolean,
        limiterHz: Double,
        gear: Int,
        elapsedSeconds: Double,
    ): Double {
        beginBlock()
        stepAccumulatorSeconds += elapsedSeconds.coerceAtLeast(0.0)
        val safeRpm = if (rpm.isFinite()) rpm.coerceAtLeast(0.0) else 0.0
        val assistedGas = if (postAssistPedal.isFinite()) postAssistPedal.coerceIn(0.0, 1.0) else 0.0
        val mappedGas = if (mappedEngineGas.isFinite()) mappedEngineGas.coerceIn(0.0, 1.0) else 0.0
        val limiterPeriodSteps = if (limiterHz.isFinite() && limiterHz > 0.0) {
            (1_000.0 / limiterHz).toInt() / 3
        } else {
            0
        }
        while (stepAccumulatorSeconds + STEP_EPSILON_SECONDS >= AC_PHYSICS_STEP_SECONDS) {
            stepAccumulatorSeconds -= AC_PHYSICS_STEP_SECONDS
            if (limiterReload) limiterCutStepsRemaining = limiterPeriodSteps
            val limiterCut = limiterCutStepsRemaining > 0
            if (limiterCut) limiterCutStepsRemaining -= 1
            val gas = if (limiterCut) 0.0 else mappedGas
            stepAtPhysicsTick(safeRpm, assistedGas, gas, gear)
        }
        if (stepAccumulatorSeconds < 0.0) stepAccumulatorSeconds = 0.0
        return latestNormalizedBoost
    }

    /** Resets block-edge latches without disturbing the persistent 3 ms turbo state. */
    internal fun beginBlock() {
        bovRisingEdge = false
        bovRisingEdgeBoost = 0.0
    }

    /**
     * Advances exactly one AC physics tick. This is split from [update] so the authored gas-assist
     * runtime can interleave timer, throttle-map and limiter changes with turbo state rather than
     * applying only the final gas value to every tick in an audio block.
     */
    internal fun stepAtPhysicsTick(
        rpm: Double,
        postAssistPedal: Double,
        effectiveThrottle: Double,
        gear: Int,
    ) {
        val safeRpm = if (rpm.isFinite()) rpm.coerceAtLeast(0.0) else 0.0
        val assistedGas = if (postAssistPedal.isFinite()) {
            postAssistPedal.coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val gas = if (effectiveThrottle.isFinite()) effectiveThrottle.coerceIn(0.0, 1.0) else 0.0
        latestEffectiveThrottle = gas
        controllerRuntime?.updateInto(
            safeRpm,
            controllerThrottleInput(assistedGas, gas),
            gear,
            AC_PHYSICS_STEP_SECONDS,
            controllerOutputs,
        )
        var totalPressure = 0.0
        var turboIndex = 0
        while (turboIndex < spec.units.size) {
            val unit = spec.units[turboIndex]
            val target = (gas * safeRpm / unit.referenceRpm)
                .coerceIn(0.0, 1.0)
                .pow(unit.gamma)
            val lag = if (target > spool[turboIndex]) unit.lagUp else unit.lagDown
            spool[turboIndex] +=
                (AC_PHYSICS_STEP_SECONDS * lag).coerceIn(0.0, 1.0) *
                (target - spool[turboIndex])
            val uncappedPressure = unit.maximumBoost * spool[turboIndex]
            val controllerIndex = unit.controllerProgramIndex
            totalPressure += when {
                // A controller output is an absolute live wastegate replacement. Zero is a real
                // closed-gate output here, not the static-config sentinel below.
                controllerIndex >= 0 -> minOf(uncappedPressure, controllerOutputs[controllerIndex])
                // AC's static WASTEGATE <= 0 sentinel disables the cap. All official cars author
                // a positive value, but retaining the sentinel keeps imported physics exact.
                unit.wastegate > 0.0 -> minOf(uncappedPressure, unit.wastegate)
                else -> uncappedPressure
            }
            turboIndex += 1
        }
        latestTotalBoost = totalPressure
        latestNormalizedBoost = (totalPressure / maximumBoostSum).coerceIn(0.0, 1.0)
        val bovActive = totalPressure * (1.0 - gas) > spec.bovPressureThreshold
        if (bovActive && !bovWasActive) {
            bovRisingEdge = true
            bovRisingEdgeBoost = latestNormalizedBoost
        }
        latestBovValue = if (bovActive) 1.0 else 0.0
        latestBovDecaySeconds = if (bovActive) {
            0.0
        } else {
            (latestBovDecaySeconds + AC_PHYSICS_STEP_SECONDS).coerceAtMost(MAX_BOV_DECAY_SECONDS)
        }
        bovWasActive = bovActive
    }

    /** ctrl_turbo INPUT=GAS reads post-assist Car.controls.gas, before map/limiter engine cuts. */
    private fun controllerThrottleInput(postAssistPedal: Double, @Suppress("UNUSED_PARAMETER") effectiveGas: Double): Double =
        postAssistPedal

    companion object {
        internal const val AC_PHYSICS_STEP_SECONDS = 0.003
        internal const val MAX_BOV_DECAY_SECONDS = 10.0
        private const val STEP_EPSILON_SECONDS = 1e-12
    }
}
