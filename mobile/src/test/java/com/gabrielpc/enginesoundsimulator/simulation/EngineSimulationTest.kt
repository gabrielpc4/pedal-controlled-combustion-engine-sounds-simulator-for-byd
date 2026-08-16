package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSimulationTest {
    @Test
    fun sampleProfileStartsAtItsOwnIdleInFirstVirtualGear() {
        val simulation = EngineSimulation()
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 0.0)
        assertEquals(1, simulation.state.gear)
        assertFalse(simulation.state.isShifting)
        assertEquals(0L, simulation.state.shiftSerial)
    }

    @Test
    fun fullPedalRapidlyReachesTheSweetSpot() {
        val simulation = EngineSimulation()
        val reached = simulation.runFor(0.25, throttle = 1.0, sim = true)
        assertTrue(reached.rpm >= simulation.profile.fullThrottleSweetSpotRpm - 120.0)
    }

    @Test
    fun tachClimbsMoreGentlyAfterTheSweetSpot() {
        val simulation = EngineSimulation()
        val early = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        var state = early
        repeat((0.35 / STEP).toInt()) {
            state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        }
        assertTrue(state.rpm >= simulation.profile.fullThrottleSweetSpotRpm - 120.0)
        assertTrue(state.rpmPositiveForcePerSecond < early.rpmPositiveForcePerSecond * 0.30)
    }

    @Test
    fun virtualGearsContinueUpshiftingWithoutAFixedTopGear() {
        val simulation = EngineSimulation()
        var state = simulation.state
        repeat((18.0 / STEP).toInt()) {
            state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        }
        assertTrue("full throttle should pass the old seven-gear ceiling: $state", state.gear > 7)
        assertTrue("every virtual upshift needs an event serial", state.shiftSerial >= 7L)
    }

    @Test
    fun liftOffDownshiftsThroughVirtualGearsAtTheLandingRpm() {
        val simulation = EngineSimulation()
        var state = simulation.state
        repeat((5.0 / STEP).toInt()) {
            state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        }
        val gearBeforeLift = state.gear
        repeat((1.5 / STEP).toInt()) {
            state = simulation.update(DriverInput(simulateCoastRegen = true), STEP)
        }

        assertTrue(gearBeforeLift > 1)
        assertTrue("lift-off should step back down through virtual gears: $state", state.gear < gearBeforeLift)
        assertTrue(state.shiftSerial > 0L)
    }

    @Test
    fun simulatorSpeedFollowsTheFakeTachOnLiftOff() {
        val simulation = EngineSimulation()
        val launched = simulation.runFor(1.0, throttle = 0.35, sim = true)
        val lifted = simulation.runFor(0.80, sim = true)
        val rpmSpan = simulation.profile.redlineRpm - simulation.profile.idleRpm
        val expectedSpeed = simulation.profile.topSpeedKmh *
            ((lifted.rpm - simulation.profile.idleRpm) / rpmSpan).coerceIn(0.0, 1.0)
        assertEquals(expectedSpeed, lifted.speedKmh, 0.01)
        assertTrue(lifted.rpm < launched.rpm - 600.0)
        assertTrue(lifted.speedKmh < launched.speedKmh - 15.0)
    }

    @Test
    fun brakeDropsDirectTachFasterThanLiftOff() {
        val coast = EngineSimulation()
        val brake = EngineSimulation()
        coast.runFor(1.0, throttle = 0.75, sim = true)
        brake.runFor(1.0, throttle = 0.75, sim = true)
        val coastState = coast.runFor(0.25, sim = true)
        val brakeState = brake.runFor(0.25, brake = 1.0, sim = true)
        assertTrue(brakeState.rpm < coastState.rpm - 400.0)
        assertTrue(brakeState.speedKmh < coastState.speedKmh - 8.0)
    }

    @Test
    fun externalRoadSpeedNeverMovesTheFakeTach() {
        val simulation = EngineSimulation()
        simulation.update(DriverInput(externalSpeedKmh = 180.0), STEP)
        simulation.runForExternal(1.0, speedKmh = 20.0)
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 0.01)
        assertEquals(1, simulation.state.gear)
    }

    @Test
    fun directTachProgressionIsIndependentFromExternalRoadSpeed() {
        val slow = EngineSimulation()
        val fast = EngineSimulation()
        val slowState = slow.runForExternal(0.8, speedKmh = 0.0, throttle = 0.55)
        val fastState = fast.runForExternal(0.8, speedKmh = 180.0, throttle = 0.55)
        assertEquals(slowState.rpm, fastState.rpm, 0.01)
        assertEquals(slowState.rpmPositiveForcePerSecond, fastState.rpmPositiveForcePerSecond, 0.01)
    }

    @Test
    fun limiterStillUsesHysteresisWithoutAForcedShift() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE.copy(
            redlineRpm = 3_000.0,
            limiterRpm = 3_200.0,
            fullThrottleSweetSpotRpm = 2_300.0,
        )
        val simulation = EngineSimulation(profile)
        var state = simulation.state
        repeat((3.0 / STEP).toInt()) {
            state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        }
        assertTrue(state.limiterActive)
        assertEquals(0L, state.shiftSerial)
        assertFalse(state.isShifting)
    }

    @Test
    fun parkKeepsSimulatorSpeedAtZero() {
        val simulation = EngineSimulation()
        val state = simulation.runFor(0.8, throttle = 1.0, sim = true, position = TransmissionPosition.PARK)
        assertEquals(0.0, state.speedKmh, 0.001)
    }

    private fun EngineSimulation.runFor(
        seconds: Double,
        throttle: Double = 0.0,
        brake: Double = 0.0,
        sim: Boolean = false,
        position: TransmissionPosition = TransmissionPosition.DRIVE,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(
                DriverInput(throttle, brake, simulateCoastRegen = sim, transmissionPosition = position),
                STEP,
            )
        }
        return result
    }

    private fun EngineSimulation.runForExternal(
        seconds: Double,
        speedKmh: Double,
        throttle: Double = 0.0,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle = throttle, externalSpeedKmh = speedKmh), STEP)
        }
        return result
    }

    private companion object { const val STEP = 1.0 / 200.0 }
}
