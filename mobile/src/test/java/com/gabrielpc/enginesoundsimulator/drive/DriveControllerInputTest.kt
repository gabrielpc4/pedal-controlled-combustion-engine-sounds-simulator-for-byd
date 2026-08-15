package com.gabrielpc.enginesoundsimulator.drive

import com.gabrielpc.enginesoundsimulator.telemetry.ReaderState
import com.gabrielpc.enginesoundsimulator.telemetry.SignalValue
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveControllerInputTest {
    @Test
    fun explicitVehicleModeFailsSafeToZeroWhenPedalsAreUnavailable() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 65.0, value = null, issue = "permission denied"),
            brake = SignalValue(raw = 0.0, value = 0.0),
        )

        val result = resolveDriveInput(
            mode = InputMode.VEHICLE,
            telemetry = telemetry,
            simulatorThrottle = 1.0,
            simulatorBrake = 0.75,
        )

        assertEquals(0.0, result.throttle, 0.0)
        assertEquals(0.0, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals("BYD UNAVAILABLE", result.label)
    }

    @Test
    fun autoModeUsesSimulatorOnlyWhenVehiclePedalsAreUnavailable() {
        val result = resolveDriveInput(
            mode = InputMode.AUTO,
            telemetry = TelemetrySnapshot(readerState = ReaderState.UNAVAILABLE),
            simulatorThrottle = 1.4,
            simulatorBrake = -0.2,
        )

        assertEquals(1.0, result.throttle, 0.0)
        assertEquals(0.0, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals("SIM PEDALS", result.label)
    }

    @Test
    fun validVehiclePedalsWinInAutoModeAndInvalidSpeedIsNotForwarded() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 47.0, value = 47.0),
            brake = SignalValue(raw = 12.0, value = 12.0),
            speed = SignalValue(raw = 65_535.0, value = null, issue = "no data"),
        )

        val result = resolveDriveInput(
            mode = InputMode.AUTO,
            telemetry = telemetry,
            simulatorThrottle = 0.9,
            simulatorBrake = 0.8,
        )

        assertEquals(0.47, result.throttle, 0.0)
        assertEquals(0.12, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals("BYD PEDALS", result.label)
    }

    @Test
    fun simulatorModeIgnoresOtherwiseValidVehiclePedals() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 100.0, value = 100.0),
            brake = SignalValue(raw = 80.0, value = 80.0),
            speed = SignalValue(raw = 140.0, value = 140.0),
        )

        val result = resolveDriveInput(
            mode = InputMode.SIMULATOR,
            telemetry = telemetry,
            simulatorThrottle = 0.3,
            simulatorBrake = 0.1,
        )

        assertEquals(0.3, result.throttle, 0.0)
        assertEquals(0.1, result.brake, 0.0)
        assertNull(result.externalSpeedKmh)
        assertEquals("SIM PEDALS", result.label)
    }
}
