package com.gabrielpc.enginesoundsimulator.drive

import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.telemetry.ReaderState
import com.gabrielpc.enginesoundsimulator.telemetry.SignalValue
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveControllerInputTest {
    @Test
    fun realPedalsFallsBackToSimulatedPedalsWhenVehiclePedalsAreUnavailable() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 65.0, value = null, issue = "permission denied"),
            brake = SignalValue(raw = 0.0, value = 0.0),
        )

        val result = resolveDriveInput(
            mode = InputMode.RealPedals,
            telemetry = telemetry,
            simulatedPedalThrottle = 1.0,
            simulatedPedalBrake = 0.75,
        )

        assertEquals(1.0, result.throttle, 0.0)
        assertEquals(0.75, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals(InputMode.SimulatedPedals.displayName, result.label)
        assertEquals(true, result.usesSimulatedPedals)
    }

    @Test
    fun realPedalsUsesSimulatedPedalsOnlyWhenVehiclePedalsAreUnavailable() {
        val result = resolveDriveInput(
            mode = InputMode.RealPedals,
            telemetry = TelemetrySnapshot(readerState = ReaderState.UNAVAILABLE),
            simulatedPedalThrottle = 1.4,
            simulatedPedalBrake = -0.2,
        )

        assertEquals(1.0, result.throttle, 0.0)
        assertEquals(0.0, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals(InputMode.SimulatedPedals.displayName, result.label)
        assertEquals(true, result.usesSimulatedPedals)
    }

    @Test
    fun validVehiclePedalsWinWhenRealPedalsAndInvalidSpeedIsNotForwarded() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 47.0, value = 47.0),
            brake = SignalValue(raw = 12.0, value = 12.0),
            speed = SignalValue(raw = 65_535.0, value = null, issue = "no data"),
        )

        val result = resolveDriveInput(
            mode = InputMode.RealPedals,
            telemetry = telemetry,
            simulatedPedalThrottle = 0.9,
            simulatedPedalBrake = 0.8,
        )

        assertEquals(0.47, result.throttle, 0.0)
        assertEquals(0.12, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals(InputMode.RealPedals.displayName, result.label)
        assertEquals(false, result.usesSimulatedPedals)
    }

    @Test
    fun vehicleThrottleAtNinetyNinePercentCountsAsFullThrottle() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 99.0, value = 99.0),
            brake = SignalValue(raw = 0.0, value = 0.0),
        )

        val result = resolveDriveInput(
            mode = InputMode.RealPedals,
            telemetry = telemetry,
            simulatedPedalThrottle = 0.0,
            simulatedPedalBrake = 0.0,
        )

        assertEquals(1.0, result.throttle, 0.0)
        assertEquals(1.0, normalizeVehicleThrottlePercent(100.0), 0.0)
        assertEquals(0.98, normalizeVehicleThrottlePercent(98.0), 0.0)
    }

    @Test
    fun simulatedPedalsModeIgnoresOtherwiseValidVehiclePedals() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 100.0, value = 100.0),
            brake = SignalValue(raw = 80.0, value = 80.0),
            speed = SignalValue(raw = 140.0, value = 140.0),
        )

        val result = resolveDriveInput(
            mode = InputMode.SimulatedPedals,
            telemetry = telemetry,
            simulatedPedalThrottle = 0.3,
            simulatedPedalBrake = 0.1,
        )

        assertEquals(0.3, result.throttle, 0.0)
        assertEquals(0.1, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals(InputMode.SimulatedPedals.displayName, result.label)
        assertEquals(true, result.usesSimulatedPedals)
    }

    @Test
    fun inputUiFadesRealPedalsWhenVehicleIsUnavailable() {
        val ui = resolveInputSourceUi(
            selectedMode = InputMode.SimulatedPedals,
            vehicleAvailable = false,
        )

        assertEquals(InputMode.SimulatedPedals.primaryLabel, ui.primaryLabel)
        assertEquals(InputMode.SimulatedPedals.secondaryLabel, ui.secondaryLabel)
        assertFalse(ui.isRealPedals)
        assertTrue(ui.faded)
    }

    @Test
    fun inputUiShowsSimulatedPedalsWhenRealPedalsPreferredButVehicleIsUnavailable() {
        val ui = resolveInputSourceUi(
            selectedMode = InputMode.RealPedals,
            vehicleAvailable = false,
        )

        assertEquals(InputMode.SimulatedPedals.primaryLabel, ui.primaryLabel)
        assertEquals(InputMode.SimulatedPedals.secondaryLabel, ui.secondaryLabel)
        assertFalse(ui.isRealPedals)
        assertTrue(ui.faded)
    }

    @Test
    fun inputUiShowsRealPedalsWhenVehicleIsAvailable() {
        val ui = resolveInputSourceUi(
            selectedMode = InputMode.RealPedals,
            vehicleAvailable = true,
        )

        assertEquals(InputMode.RealPedals.primaryLabel, ui.primaryLabel)
        assertTrue(ui.isRealPedals)
        assertFalse(ui.faded)
    }

    @Test
    fun interruptionResumeVolumeCapsHighSavedVolume() {
        val volume = resolveInterruptionResumeVolume(
            savedVolume = 1.0,
            resumeCap = 0.25,
        )

        assertEquals(0.25, volume, 0.0)
    }

    @Test
    fun interruptionResumeVolumeKeepsLowSavedVolume() {
        val volume = resolveInterruptionResumeVolume(
            savedVolume = 0.15,
            resumeCap = 0.25,
        )

        assertEquals(0.15, volume, 0.0)
    }

    @Test
    fun interruptionResumeVolumeUsesDefaultWhenSavedVolumeMissing() {
        val volume = resolveInterruptionResumeVolume(
            savedVolume = null,
            resumeCap = 0.25,
        )

        assertEquals(
            minOf(AppMasterVolumeRepository.DEFAULT, 0.25),
            volume,
            0.0,
        )
    }
}
