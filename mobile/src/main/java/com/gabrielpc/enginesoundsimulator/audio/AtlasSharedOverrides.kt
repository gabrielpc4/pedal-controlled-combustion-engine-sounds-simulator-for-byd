package com.gabrielpc.enginesoundsimulator.audio

/** Small bundled override bank kept separate from externally-installed car packs. */
internal class AtlasSharedOverrides(source: AudioAssetSource) {
    private val pops = SharedVoice(
        SharedPopsAndBangs.assetNames.map { source.open(SharedPopsAndBangs.assetPath(it)).use(WavPcmDecoder::decode) }
            .toTypedArray(),
    )
    private val shiftUp = SharedVoice(
        arrayOf(source.open(SharedHuracanShiftSounds.assetPath(SharedHuracanShiftSounds.shiftUpSpec.assetName)).use(WavPcmDecoder::decode)),
    )
    private val shiftDown = SharedVoice(
        arrayOf(source.open(SharedHuracanShiftSounds.assetPath(SharedHuracanShiftSounds.shiftDownSpec.assetName)).use(WavPcmDecoder::decode)),
    )

    fun update(target: EngineAudioFrame, controls: AtlasEffectControlModel) {
        if (target.popsAndBangsEnabled && target.throttleLiftEffectsEnabled &&
            controls.isTriggered(AtlasRuntimeTrigger.THROTTLE_LIFT) && controls.rpm >= SharedPopsAndBangs.effectSpec.minimumRpm
        ) pops.start()
        if (target.sharedShiftSoundsEnabled && controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP)) shiftUp.start()
        if (target.sharedShiftSoundsEnabled && controls.isTriggered(AtlasRuntimeTrigger.SHIFT_DOWN)) shiftDown.start()
    }

    fun mixFrame(target: EngineAudioFrame, destination: DoubleArray, anySolo: Boolean) {
        if (target.popsAndBangsEnabled && target.throttleLiftEffectsEnabled) {
            pops.mix(destination, target.popsAndBangsGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN) *
                trackGain(target, SharedPopsAndBangs.EFFECT_ID, anySolo))
        }
        if (target.sharedShiftSoundsEnabled) {
            val gain = target.sharedShiftSoundsGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
            shiftUp.mix(destination, gain * trackGain(target, SharedHuracanShiftSounds.SHIFT_UP_ID, anySolo))
            shiftDown.mix(destination, gain * trackGain(target, SharedHuracanShiftSounds.SHIFT_DOWN_ID, anySolo))
        }
    }

    private fun trackGain(target: EngineAudioFrame, id: String, anySolo: Boolean): Double {
        val control = target.layerMix[id] ?: LayerMixControl.DEFAULT
        return when {
            control.muted || (anySolo && !control.solo) -> 0.0
            else -> control.volume.coerceIn(0.0, LayerMixControl.MAX_GAIN_MULTIPLIER)
        }
    }

    fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) {
        destination[offset] = if (target.popsAndBangsEnabled && pops.isActive) {
            target.popsAndBangsGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        } else 0.0
        destination[offset + 1] = if (target.sharedShiftSoundsEnabled && shiftUp.isActive) {
            target.sharedShiftSoundsGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        } else 0.0
        destination[offset + 2] = if (target.sharedShiftSoundsEnabled && shiftDown.isActive) {
            target.sharedShiftSoundsGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        } else 0.0
    }

    private class SharedVoice(private val samples: Array<PcmLoopData>) {
        private var sampleIndex = 0
        private var lastSampleIndex = -1
        private var randomState = 0x9e3779b97f4a7c15UL.toLong()
        private var phase = 0
        private var active = false
        val isActive: Boolean get() = active

        fun start() {
            if (active) return
            active = true
            phase = 0
            sampleIndex = nextSampleIndex()
        }

        fun mix(destination: DoubleArray, gain: Double) {
            if (!active) return
            val sample = samples[sampleIndex]
            if (phase >= sample.frameCount) {
                active = false
                return
            }
            destination[0] += sample.sampleAt(0, phase) * gain
            destination[1] += sample.sampleAt(if (sample.sourceChannels == 1) 0 else 1, phase) * gain
            phase += 1
        }

        private fun nextSampleIndex(): Int {
            if (samples.size == 1) return 0
            do {
                randomState = randomState xor (randomState ushr 12)
                randomState = randomState xor (randomState shl 25)
                randomState = randomState xor (randomState ushr 27)
                sampleIndex = ((randomState * 2_685_821_657_736_338_717L).toULong() % samples.size.toUInt().toULong()).toInt()
            } while (sampleIndex == lastSampleIndex)
            lastSampleIndex = sampleIndex
            return sampleIndex
        }
    }
}
