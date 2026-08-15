package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

data class EngineAudioFrame(
    val rpm: Double = 950.0,
    val throttle: Double = 0.0,
    val load: Double = 0.0,
    val redlineRpm: Double = 8_600.0,
    val cylinders: Int = 10,
    val shiftSerial: Long = 0L,
    val shifting: Boolean = false,
    val limiterActive: Boolean = false,
    val enabled: Boolean = true,
    val tuning: AudioTuning = AudioTuning(),
)

/**
 * Procedural four-stroke engine source used until a licensed RPM/load sample bank is selected.
 *
 * The firing fundamental is RPM / 60 * cylinders / 2. Load changes harmonic balance, intake noise,
 * and exhaust pulse saturation rather than merely changing volume. Shift, limiter, and overrun
 * envelopes prevent the result from behaving like a single pitch-shifted oscillator.
 */
class EngineSynthesizer(private val sampleRate: Int) {
    private var firingPhase = 0.0
    private var crankPhase = 0.0
    private var whinePhase = 0.0
    private var shiftThumpPhase = 0.0
    private var smoothedRpm = 950.0
    private var smoothedThrottle = 0.0
    private var smoothedLoad = 0.0
    private var previousTargetThrottle = 0.0
    private var lastShiftSerial = 0L
    private var shiftEnvelope = 0.0
    private var overrunEnvelope = 0.0
    private var intakeNoiseState = 0.0
    private var exhaustBodyState = 0.0
    private var masterGain = 0.0
    private var enabledGain = 0.0
    private var limiterGain = 1.0
    private var shiftGain = 1.0
    private var limiterSampleIndex = 0
    private var randomState = 0x4f1bbcdc

    fun reset() {
        firingPhase = 0.0
        crankPhase = 0.0
        whinePhase = 0.0
        shiftThumpPhase = 0.0
        smoothedRpm = 950.0
        smoothedThrottle = 0.0
        smoothedLoad = 0.0
        previousTargetThrottle = 0.0
        lastShiftSerial = 0L
        shiftEnvelope = 0.0
        overrunEnvelope = 0.0
        intakeNoiseState = 0.0
        exhaustBodyState = 0.0
        masterGain = 0.0
        enabledGain = 0.0
        limiterGain = 1.0
        shiftGain = 1.0
        limiterSampleIndex = 0
        randomState = 0x4f1bbcdc
    }

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double = 0.72) {
        if (target.shiftSerial != lastShiftSerial) {
            lastShiftSerial = target.shiftSerial
            shiftEnvelope = 1.0
            shiftThumpPhase = 0.0
        }
        if (previousTargetThrottle - target.throttle > 0.20 && target.rpm > 3_500.0) {
            overrunEnvelope = ((previousTargetThrottle - target.throttle) * 0.85).coerceAtMost(1.0)
        }
        previousTargetThrottle = target.throttle

        val rpmSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.016))
        val controlSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.010))
        val shiftDecay = exp(-1.0 / (sampleRate * 0.115))
        val overrunDecay = exp(-1.0 / (sampleRate * 0.33))
        val masterGainSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.006))
        val enabledGainSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.008))
        val limiterGainSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.0015))
        val shiftGainSmoothing = 1.0 - exp(-1.0 / (sampleRate * 0.004))
        val audioTuning = target.tuning.sanitized()
        val targetMasterGain = (gain * audioTuning.masterGain / 0.72).coerceIn(0.0, 1.5)
        val targetEnabledGain = if (target.enabled) 1.0 else 0.0
        val targetShiftGain = if (target.shifting) 0.64 else 1.0

        for (index in output.indices) {
            smoothedRpm += (target.rpm.coerceAtLeast(300.0) - smoothedRpm) * rpmSmoothing
            smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * controlSmoothing
            smoothedLoad += (target.load.coerceIn(0.0, 1.0) - smoothedLoad) * controlSmoothing

            val crankHz = smoothedRpm / 60.0
            val firingHz = crankHz * (target.cylinders.coerceAtLeast(2) / 2.0)
            firingPhase = wrapPhase(firingPhase + TWO_PI * firingHz / sampleRate)
            crankPhase = wrapPhase(crankPhase + TWO_PI * crankHz / sampleRate)
            whinePhase = wrapPhase(whinePhase + TWO_PI * crankHz * 3.72 / sampleRate)
            shiftThumpPhase = wrapPhase(shiftThumpPhase + TWO_PI * 54.0 / sampleRate)

            val normalizedRpm = (smoothedRpm / target.redlineRpm).coerceIn(0.0, 1.08)
            val pulse =
                sin(firingPhase) +
                    sin(firingPhase * 2.0) * (0.36 + smoothedLoad * 0.13) * audioTuning.harmonic2 +
                    sin(firingPhase * 3.0) * (0.20 + normalizedRpm * 0.10) * audioTuning.harmonic3 +
                    sin(firingPhase * 4.0) * (0.10 + smoothedThrottle * 0.11) * audioTuning.harmonic4 +
                    sin(firingPhase * 5.0) * normalizedRpm * 0.07 * audioTuning.harmonic5
            val saturatedPulse = softClip(pulse * (1.12 + smoothedLoad * 1.48))

            val exhaustCutoff = 0.055 + normalizedRpm * 0.16 + smoothedLoad * 0.09
            exhaustBodyState += (saturatedPulse - exhaustBodyState) * exhaustCutoff.coerceIn(0.03, 0.32)

            val whiteNoise = nextNoise()
            intakeNoiseState += (whiteNoise - intakeNoiseState) * (0.03 + normalizedRpm * 0.13)
            val intakeNoise = whiteNoise - intakeNoiseState
            val mechanical = sin(crankPhase * 2.0) * 0.12 + sin(whinePhase) * (0.04 + normalizedRpm * 0.09)

            val loadBody = exhaustBodyState * (0.32 + smoothedLoad * 0.61) * audioTuning.exhaustLevel
            val intake = intakeNoise * (0.025 + smoothedThrottle * 0.18) *
                (0.45 + normalizedRpm) * audioTuning.intakeLevel
            val idleCombustion = saturatedPulse * (1.0 - normalizedRpm).coerceIn(0.0, 1.0) * 0.12
            val shiftThump = sin(shiftThumpPhase) * shiftEnvelope * 0.24 * audioTuning.shiftLevel
            val overrunCrackle = if (overrunEnvelope > 0.001 && whiteNoise > 0.72) {
                whiteNoise * overrunEnvelope * 0.24 * audioTuning.overrunLevel
            } else {
                0.0
            }
            val targetLimiterGain = if (target.limiterActive && ((limiterSampleIndex / 64) and 1) == 0) {
                0.22
            } else {
                1.0
            }
            limiterSampleIndex = (limiterSampleIndex + 1) and 127

            masterGain += (targetMasterGain - masterGain) * masterGainSmoothing
            enabledGain += (targetEnabledGain - enabledGain) * enabledGainSmoothing
            limiterGain += (targetLimiterGain - limiterGain) * limiterGainSmoothing
            shiftGain += (targetShiftGain - shiftGain) * shiftGainSmoothing
            val mixed = (
                loadBody + intake + mechanical * audioTuning.mechanicalLevel +
                    idleCombustion + shiftThump + overrunCrackle
                ) *
                limiterGain * shiftGain
            val finalSample = softClip(mixed * masterGain * enabledGain) * 0.88
            output[index] = (finalSample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

            shiftEnvelope *= shiftDecay
            overrunEnvelope *= overrunDecay
        }
    }

    private fun nextNoise(): Double {
        var value = randomState
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        randomState = value
        return value.toDouble() / Int.MAX_VALUE.toDouble()
    }

    private fun softClip(value: Double): Double = value / (1.0 + abs(value))

    private fun wrapPhase(value: Double): Double = if (value >= TWO_PI) value - TWO_PI else value

    private companion object {
        const val TWO_PI = PI * 2.0
    }
}
