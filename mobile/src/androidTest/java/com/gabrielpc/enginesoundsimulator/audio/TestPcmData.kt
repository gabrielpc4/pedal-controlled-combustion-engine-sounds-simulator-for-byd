package com.gabrielpc.enginesoundsimulator.audio

/** Float reference PCM used only for device-side native-mixer parity tests. */
internal data class PcmLoopData(
    val channelSamples: Array<FloatArray>,
    override val sampleRate: Int,
    override val loopStartFrame: Int = 0,
    override val loopEndFrameExclusive: Int = channelSamples.first().size,
) : PlanarPcmData {
    override val sourceChannels: Int get() = channelSamples.size
    override val frameCount: Int get() = channelSamples.first().size
    override fun normalizedSample(channel: Int, frame: Int): Double = channelSamples[channel][frame].toDouble()
}
