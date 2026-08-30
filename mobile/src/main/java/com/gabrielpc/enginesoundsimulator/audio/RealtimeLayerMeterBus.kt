package com.gabrielpc.enginesoundsimulator.audio

/** Single-writer meter bridge: the audio thread never allocates or waits for the UI. */
internal class RealtimeLayerMeterBus(private val trackIds: List<String>) {
    private val levels = DoubleArray(trackIds.size)

    @Volatile
    private var sequence = 0

    fun publish(renderer: SampleEngineRenderer, target: EngineAudioFrame) {
        sequence += 1
        renderer.writeLayerOutputLevels(target, levels)
        sequence += 1
    }

    fun snapshot(): List<LayerOutputMeter> {
        if (trackIds.isEmpty()) return emptyList()
        val copied = DoubleArray(levels.size)
        while (true) {
            val before = sequence
            if (before and 1 != 0) continue
            levels.copyInto(copied)
            val after = sequence
            if (before == after && after and 1 == 0) break
        }
        return List(trackIds.size) { index ->
            LayerOutputMeter(trackIds[index], copied[index])
        }
    }
}
