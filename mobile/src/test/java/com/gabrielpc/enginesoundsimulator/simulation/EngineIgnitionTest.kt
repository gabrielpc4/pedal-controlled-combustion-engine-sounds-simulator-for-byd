package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun startupAudioOpensQuicklyAfterCrank() {
        assertEquals(0.0, startupIgnitionAudioGain(0.20), 0.001)
        assertTrue(startupIgnitionAudioGain(ENGINE_START_AUDIO_OPEN_SECONDS + 0.01) > 0.10)
        assertEquals(1.0, startupIgnitionAudioGain(ENGINE_START_AUDIO_OPEN_SECONDS + ENGINE_START_AUDIO_FADE_SECONDS), 0.001)

        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(50) {
            simulation.update(DriverInput(), 0.005)
            assertTrue(simulation.ignitionAudioGain() < 0.02)
        }
        repeat(20) {
            simulation.update(DriverInput(), 0.005)
        }
        assertTrue(simulation.ignitionAudioGain() > 0.95)
        assertEquals(EngineIgnitionState.STARTING, simulation.ignition)
    }

    @Test
    fun shutdownAudioFadesOutSmoothly() {
        assertEquals(1.0, shutdownIgnitionAudioGain(0.0), 0.001)
        assertTrue(shutdownIgnitionAudioGain(0.20) > 0.65)
        assertEquals(0.0, shutdownIgnitionAudioGain(SHUTDOWN_AUDIO_FADE_SECONDS), 0.001)

        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(450) {
            simulation.update(DriverInput(), 0.005)
        }
        simulation.requestShutdown()
        simulation.update(DriverInput(), 0.005)
        assertTrue(simulation.ignitionAudioGain() > 0.95)
        repeat(150) {
            simulation.update(DriverInput(), 0.005)
        }
        assertFalse(simulation.isEngineAudioAudible())
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

    @Test
    fun startIgnitionDuringShutdownDoesNotWaitForZeroRpm() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(450) {
            simulation.update(DriverInput(), 0.005)
        }
        simulation.requestShutdown()
        repeat(20) {
            simulation.update(DriverInput(), 0.005)
        }
        assertEquals(EngineIgnitionState.STOPPING, simulation.ignition)
        assertTrue(simulation.state.rpm > 0.0)

        simulation.startIgnition()

        assertEquals(EngineIgnitionState.STARTING, simulation.ignition)
        assertEquals(0.0, simulation.state.rpm, 0.0)
    }
}
