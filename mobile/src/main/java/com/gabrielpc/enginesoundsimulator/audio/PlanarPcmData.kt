package com.gabrielpc.enginesoundsimulator.audio

/** Read-only planar PCM view consumed by the renderer and native-mixer parity path. */
internal interface PlanarPcmData {
    val sampleRate: Int
    val loopStartFrame: Int
    val loopEndFrameExclusive: Int
    val sourceChannels: Int
    val frameCount: Int
    fun normalizedSample(channel: Int, frame: Int): Double
}
