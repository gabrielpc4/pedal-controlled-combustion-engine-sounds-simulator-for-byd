package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineAudioFrameTest {
    @Test
    fun realtimePublicationKeepsEngineAndDrivenShaftSpeedsIndependent() {
        val parameters = RealtimeEngineAudioParameters()
        parameters.write(
            EngineAudioFrame(
                rpm = 6_200.0,
                drivetrainRpm = 875.0,
                throttle = 0.4,
            ),
        )
        val destination = MutableEngineAudioFrame()

        parameters.readInto(destination)

        assertEquals(6_200.0, destination.rpm, 0.0)
        assertEquals(875.0, destination.drivetrainRpm, 0.0)
        assertEquals(0.4, destination.throttle, 0.0)
    }
}
