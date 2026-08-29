package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineIgnitionTest {
    @Test
    fun startCurveBlipsToFiveThousandRpm() {
        val idle = 1_040.0

        assertEquals(0.0, engineStartRpmAt(0.0, idle), 0.0)
        assertTrue(engineStartRpmAt(0.90, idle) > idle * 2.5)
        assertEquals(ENGINE_START_PEAK_RPM, engineStartRpmAt(0.95, idle), 1.0)
        assertEquals(idle, engineStartRpmAt(2.1, idle), 0.0)
    }

    @Test
    fun shutdownEndsAtZeroWithoutHold() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(450) {
            simulation.update(DriverInput(), 0.005)
        }
        simulation.requestShutdown()
        var sawZero = false
        repeat(800) {
            val state = simulation.update(DriverInput(), 0.005)
            if (state.rpm <= 0.0) {
                sawZero = true
            }
        }
        assertTrue(sawZero)
        assertEquals(EngineIgnitionState.OFF, simulation.ignition)
    }

    @Test
    fun shutdownAudioCutsBeforeRpmReachesZero() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(450) {
            simulation.update(DriverInput(), 0.005)
        }
        simulation.requestShutdown()
        var audioStoppedWhileStillStopping = false
        repeat(800) {
            simulation.update(DriverInput(), 0.005)
            if (!simulation.isEngineAudioAudible() &&
                simulation.ignition == EngineIgnitionState.STOPPING &&
                simulation.state.rpm > 0.0
            ) {
                audioStoppedWhileStillStopping = true
            }
        }
        assertTrue(audioStoppedWhileStillStopping)
    }
}
