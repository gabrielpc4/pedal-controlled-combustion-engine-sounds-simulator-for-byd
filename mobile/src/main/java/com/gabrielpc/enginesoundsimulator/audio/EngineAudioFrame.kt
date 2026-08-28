package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Read-only controls consumed by the sample-bank renderer. */
internal interface EngineAudioControlFrame {
    val rpm: Double
    /** Driven-wheel/shaft speed expressed as revolutions per minute for authored transmission audio. */
    val drivetrainRpm: Double
    /** Unsmoothed driver pedal used only by the AC physical turbo input chain. */
    val physicalPedal: Double
    val throttle: Double
    val enabled: Boolean
    val enabledEffectMask: Long
    val soloEffects: Boolean
    val shiftSerial: Long
    val shiftDirection: Int
    val isShifting: Boolean
    val gear: Int
    val limiterActive: Boolean
    val tuning: AudioTuning
    val layerMix: Map<String, LayerMixControl>
}

data class EngineAudioFrame(
    override val rpm: Double = 900.0,
    override val drivetrainRpm: Double = 0.0,
    override val throttle: Double = 0.0,
    override val physicalPedal: Double = throttle,
    override val enabled: Boolean = true,
    override val enabledEffectMask: Long = 0L,
    override val soloEffects: Boolean = false,
    override val shiftSerial: Long = 0L,
    override val shiftDirection: Int = 0,
    override val isShifting: Boolean = false,
    override val gear: Int = 1,
    override val limiterActive: Boolean = false,
    override val tuning: AudioTuning = AudioTuning(),
    override val layerMix: Map<String, LayerMixControl> = emptyMap(),
) : EngineAudioControlFrame

/** Audio-thread-owned mutable read buffer. */
internal class MutableEngineAudioFrame : EngineAudioControlFrame {
    @Volatile override var rpm: Double = 900.0
    @Volatile override var drivetrainRpm: Double = 0.0
    @Volatile override var physicalPedal: Double = 0.0
    @Volatile override var throttle: Double = 0.0
    @Volatile override var enabled: Boolean = true
    @Volatile override var enabledEffectMask: Long = 0L
    @Volatile override var soloEffects: Boolean = false
    @Volatile override var shiftSerial: Long = 0L
    @Volatile override var shiftDirection: Int = 0
    @Volatile override var isShifting: Boolean = false
    @Volatile override var gear: Int = 1
    @Volatile override var limiterActive: Boolean = false
    @Volatile override var tuning: AudioTuning = AudioTuning()
    @Volatile override var layerMix: Map<String, LayerMixControl> = emptyMap()
}

/**
 * Single-writer seqlock used between the 200 Hz core and audio renderer. It
 * publishes primitives/references without creating an EngineAudioFrame on
 * every core tick and lets the audio thread reuse one mutable read buffer.
 */
internal class RealtimeEngineAudioParameters {
    @Volatile private var revision = 0L
    private var rpm = 900.0
    private var drivetrainRpm = 0.0
    private var physicalPedal = 0.0
    private var throttle = 0.0
    private var enabled = true
    private var enabledEffectMask = 0L
    private var soloEffects = false
    private var shiftSerial = 0L
    private var shiftDirection = 0
    private var isShifting = false
    private var gear = 1
    private var limiterActive = false
    private var tuning = AudioTuning()
    private var layerMix: Map<String, LayerMixControl> = emptyMap()

    fun write(frame: EngineAudioControlFrame) = write(
        rpm = frame.rpm,
        drivetrainRpm = frame.drivetrainRpm,
        physicalPedal = frame.physicalPedal,
        throttle = frame.throttle,
        enabled = frame.enabled,
        enabledEffectMask = frame.enabledEffectMask,
        soloEffects = frame.soloEffects,
        shiftSerial = frame.shiftSerial,
        shiftDirection = frame.shiftDirection,
        isShifting = frame.isShifting,
        gear = frame.gear,
        limiterActive = frame.limiterActive,
        tuning = frame.tuning,
        layerMix = frame.layerMix,
    )

    fun write(
        rpm: Double,
        drivetrainRpm: Double,
        physicalPedal: Double,
        throttle: Double,
        enabled: Boolean,
        enabledEffectMask: Long,
        soloEffects: Boolean,
        shiftSerial: Long,
        shiftDirection: Int,
        isShifting: Boolean,
        gear: Int,
        limiterActive: Boolean,
        tuning: AudioTuning,
        layerMix: Map<String, LayerMixControl>,
    ) {
        val next = revision + 1L
        revision = next
        this.rpm = rpm
        this.drivetrainRpm = drivetrainRpm
        this.physicalPedal = physicalPedal
        this.throttle = throttle
        this.enabled = enabled
        this.enabledEffectMask = enabledEffectMask
        this.soloEffects = soloEffects
        this.shiftSerial = shiftSerial
        this.shiftDirection = shiftDirection
        this.isShifting = isShifting
        this.gear = gear
        this.limiterActive = limiterActive
        this.tuning = tuning
        this.layerMix = layerMix
        revision = next + 1L
    }

    fun readInto(destination: MutableEngineAudioFrame) {
        while (true) {
            val before = revision
            if (before and 1L != 0L) continue
            val rpm = this.rpm
            val drivetrainRpm = this.drivetrainRpm
            val physicalPedal = this.physicalPedal
            val throttle = this.throttle
            val enabled = this.enabled
            val enabledEffectMask = this.enabledEffectMask
            val soloEffects = this.soloEffects
            val shiftSerial = this.shiftSerial
            val shiftDirection = this.shiftDirection
            val isShifting = this.isShifting
            val gear = this.gear
            val limiterActive = this.limiterActive
            val tuning = this.tuning
            val layerMix = this.layerMix
            if (revision != before) continue
            destination.rpm = rpm
            destination.drivetrainRpm = drivetrainRpm
            destination.physicalPedal = physicalPedal
            destination.throttle = throttle
            destination.enabled = enabled
            destination.enabledEffectMask = enabledEffectMask
            destination.soloEffects = soloEffects
            destination.shiftSerial = shiftSerial
            destination.shiftDirection = shiftDirection
            destination.isShifting = isShifting
            destination.gear = gear
            destination.limiterActive = limiterActive
            destination.tuning = tuning
            destination.layerMix = layerMix
            return
        }
    }
}
