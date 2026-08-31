package com.gabrielpc.enginesoundsimulator.audio

/**
 * Realtime rendering boundary shared by the original per-sample program and externally installed
 * full-event atlases. Implementations must not allocate or open files from [render].
 */
internal interface EngineProgramRenderer : AutoCloseable {
    val meterTrackIds: List<String>

    /** Optional non-audible setup; unlike [render], this must never advance event lifecycle state. */
    fun prepare() = Unit

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double)

    fun writeLayerOutputLevels(target: EngineAudioFrame, destination: DoubleArray)

    override fun close()
}
