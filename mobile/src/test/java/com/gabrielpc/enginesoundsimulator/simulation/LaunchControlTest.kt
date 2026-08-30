package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchControlTest {
    @Test
    fun fullThrottleAndBrakeArmsLaunchControl() {
        val next = LaunchControl.advancePhase(
            phase = LaunchControlPhase.INACTIVE,
            rawThrottle = 1.0,
            brake = 0.2,
            speedMps = 0.0,
            enabled = true,
        )

        assertEquals(LaunchControlPhase.ARMED, next)
    }

    @Test
    fun fullThrottleAndBrakeDoesNotArmWhenMoving() {
        val next = LaunchControl.advancePhase(
            phase = LaunchControlPhase.INACTIVE,
            rawThrottle = 1.0,
            brake = 0.2,
            speedMps = 2.0,
            enabled = true,
        )

        assertEquals(LaunchControlPhase.INACTIVE, next)
    }

    @Test
    fun releasingBrakeWhileArmedLaunchesSequence() {
        val launched = LaunchControl.advancePhase(
            phase = LaunchControlPhase.ARMED,
            rawThrottle = 1.0,
            brake = 0.0,
            speedMps = 0.0,
            enabled = true,
        )

        assertEquals(LaunchControlPhase.LAUNCHED, launched)
    }

    @Test
    fun pressingBrakeAfterLaunchRestoresNormalMode() {
        val restored = LaunchControl.advancePhase(
            phase = LaunchControlPhase.LAUNCHED,
            rawThrottle = 1.0,
            brake = 0.2,
            speedMps = 5.0,
            enabled = true,
        )

        assertEquals(LaunchControlPhase.INACTIVE, restored)
    }

    @Test
    fun releasingThrottleWhileArmedDisarmsLaunchControl() {
        val disarming = LaunchControl.advancePhase(
            phase = LaunchControlPhase.ARMED,
            rawThrottle = 0.0,
            brake = 0.2,
            speedMps = 0.0,
            enabled = true,
        )

        assertEquals(LaunchControlPhase.DISARMING, disarming)
    }

    @Test
    fun disarmTargetRpmMirrorsArmedRamp() {
        val idle = 850.0
        val peak = LaunchControl.HOLD_RPM + LaunchControl.ARMED_OVERSHOOT_RPM
        val midDisarm = LaunchControl.disarmTargetRpm(
            disarmElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS * 0.5,
            startRpm = peak,
            endRpm = idle,
        )
        val midArm = LaunchControl.armedTargetRpm(
            armedElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS * 0.5,
            jitterPhaseRadians = 0.0,
            startRpm = idle,
        )
        val atEnd = LaunchControl.disarmTargetRpm(
            disarmElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS,
            startRpm = peak,
            endRpm = idle,
        )

        assertEquals(idle, atEnd, 0.01)
        assertEquals(idle + peak, midArm + midDisarm, 1.0)
    }

    @Test
    fun armedTargetRpmRampsOvershootsThenJitters() {
        val idle = 850.0
        val midRamp = LaunchControl.armedTargetRpm(
            armedElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS * 0.5,
            jitterPhaseRadians = 0.0,
            startRpm = idle,
        )
        val atPeak = LaunchControl.armedTargetRpm(
            armedElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS,
            jitterPhaseRadians = 0.0,
            startRpm = idle,
        )
        val settled = LaunchControl.armedTargetRpm(
            armedElapsedSeconds = LaunchControl.ARMED_RAMP_SECONDS + LaunchControl.ARMED_SETTLE_SECONDS + 0.2,
            jitterPhaseRadians = 0.0,
            startRpm = idle,
        )

        assertTrue(midRamp in idle..LaunchControl.HOLD_RPM)
        assertTrue(atPeak > LaunchControl.HOLD_RPM)
        assertTrue(settled in (LaunchControl.HOLD_RPM - LaunchControl.JITTER_AMPLITUDE_RPM)..(LaunchControl.HOLD_RPM + LaunchControl.JITTER_AMPLITUDE_RPM))
    }

    @Test
    fun launchedTachCyclesBetweenRedlineAndBounceFloor() {
        val redline = 8_200.0
        val start = LaunchControl.HOLD_RPM
        val atRedline = LaunchControl.launchedTachTargetRpm(
            cycleElapsedSeconds = LaunchControl.LAUNCHED_TACH_REV_UP_SECONDS,
            redlineRpm = redline,
            launchStartRpm = start,
        )
        val afterBounce = LaunchControl.launchedTachTargetRpm(
            cycleElapsedSeconds = LaunchControl.LAUNCHED_TACH_REV_UP_SECONDS + LaunchControl.LAUNCHED_TACH_BOUNCE_SECONDS,
            redlineRpm = redline,
            launchStartRpm = start,
        )
        val secondCyclePeak = LaunchControl.launchedTachTargetRpm(
            cycleElapsedSeconds = LaunchControl.LAUNCHED_TACH_REV_UP_SECONDS +
                LaunchControl.LAUNCHED_TACH_BOUNCE_SECONDS +
                LaunchControl.LAUNCHED_TACH_REV_UP_SECONDS,
            redlineRpm = redline,
            launchStartRpm = start,
        )

        assertEquals(redline, atRedline, 0.01)
        assertEquals(redline - LaunchControl.LAUNCHED_TACH_BOUNCE_RPM, afterBounce, 0.01)
        assertEquals(redline, secondCyclePeak, 0.01)
    }

    @Test
    fun launchedTachAnimationRequiresFirstGearAndFullThrottle() {
        assertTrue(LaunchControl.shouldPlayLaunchTachAnimation(gearIndex = 0, throttle = 1.0))
        assertTrue(!LaunchControl.shouldPlayLaunchTachAnimation(gearIndex = 1, throttle = 1.0))
        assertTrue(!LaunchControl.shouldPlayLaunchTachAnimation(gearIndex = 0, throttle = 0.5))
    }
}
