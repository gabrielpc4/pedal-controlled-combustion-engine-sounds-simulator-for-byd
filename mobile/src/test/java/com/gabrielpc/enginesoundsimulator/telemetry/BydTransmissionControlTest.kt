package com.gabrielpc.enginesoundsimulator.telemetry

import com.gabrielpc.enginesoundsimulator.drive.InputMode
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BydTransmissionControlTest {
    @Test
    fun preferBydWithLivePedalsKeepsManualTransmission() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 10.0, value = 10.0),
            brake = SignalValue(raw = 0.0, value = 0.0),
            gearboxAutoMode = SignalValue(raw = 1.0, value = 1.0),
        )

        val result = resolveTransmissionControl(
            mode = InputMode.PREFER_BYD,
            telemetry = telemetry,
            manualPosition = TransmissionPosition.DRIVE,
        )

        assertFalse(result.lockedToVehicle)
        assertEquals(TransmissionPosition.DRIVE, result.position)
    }

    @Test
    fun simulatorModeKeepsManualTransmission() {
        val telemetry = TelemetrySnapshot(
            readerState = ReaderState.ACTIVE,
            accelerator = SignalValue(raw = 100.0, value = 100.0),
            brake = SignalValue(raw = 0.0, value = 0.0),
            gearboxAutoMode = SignalValue(raw = 4.0, value = 4.0),
        )

        val result = resolveTransmissionControl(
            mode = InputMode.SIMULATOR,
            telemetry = telemetry,
            manualPosition = TransmissionPosition.NEUTRAL,
        )

        assertFalse(result.lockedToVehicle)
        assertEquals(TransmissionPosition.NEUTRAL, result.position)
    }
}
