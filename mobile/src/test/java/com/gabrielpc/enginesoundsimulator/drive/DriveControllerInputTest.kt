package com.gabrielpc.enginesoundsimulator.drive

import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import com.gabrielpc.enginesoundsimulator.telemetry.ReaderState
import com.gabrielpc.enginesoundsimulator.telemetry.SignalValue
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.withFmodProfile
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
        assertEquals(false, result.usesSimulatedPedals)
    }

    @Test
    fun realPedalsPreserveFractionalVehicleSpeed() {
        val result = resolveDriveInput(
            mode = InputMode.RealPedals,
            telemetry = TelemetrySnapshot(
                readerState = ReaderState.ACTIVE,
                accelerator = SignalValue(raw = 25.0, value = 25.0),
                brake = SignalValue(raw = 0.0, value = 0.0),
                speed = SignalValue(raw = 12.375, value = 12.375),
            ),
            simulatedPedalThrottle = 0.0,
            simulatedPedalBrake = 0.0,
        )

        assertEquals(12.375, result.externalSpeedKmh!!, 0.0)
        assertFalse(result.usesSimulatedPedals)
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

    @Test
    fun drivetrainSpeedPreservesFractionalRoadSpeedAndSign() {
        val expected = (190.0 / 3.6) / 0.3425
        assertEquals(expected, drivetrainAngularSpeedRadPerSecond(190.0, 0.3425), 0.000001)
        assertEquals(-expected, drivetrainAngularSpeedRadPerSecond(-190.0, 0.3425), 0.000001)
        assertEquals(
            (12.375 / 3.6) / 0.3425,
            drivetrainAngularSpeedRadPerSecond(12.375, 0.3425),
            0.000001,
        )
        assertEquals(0.0, drivetrainAngularSpeedRadPerSecond(190.0, 0.0), 0.0)
    }

    @Test
    fun carSelectionBuildsOneCoherentRuntimePublication() {
        val initial = DriveRuntimeConfig(
            selectedCar = FmodCarProfiles.skylineR34,
            tuning = TuningConfig.DEFAULT.withFmodProfile(FmodCarProfiles.skylineR34),
            carMasterVolume = 0.8,
        )

        val selected = initial.selecting(FmodCarProfiles.huracanTrofeoEvo2, volume = 0.63)

        assertEquals(FmodCarProfiles.HURACAN_TROFEO_EVO2_ID, selected.selectedCar.id)
        assertEquals(selected.selectedCar.idleRpm, selected.tuning.engine.idleRpm, 0.0)
        assertEquals(selected.selectedCar.redlineRpm, selected.tuning.engine.redlineRpm, 0.0)
        assertEquals(selected.selectedCar.limiterRpm, selected.tuning.engine.limiterRpm, 0.0)
        assertEquals(selected.selectedCar.gearCount, selected.tuning.engine.gearRatios.size)
        assertEquals(0.63, selected.carMasterVolume, 0.0)

        // The old immutable publication remains internally consistent for a step already in flight.
        assertEquals(FmodCarProfiles.SKYLINE_R34_ID, initial.selectedCar.id)
        assertEquals(initial.selectedCar.redlineRpm, initial.tuning.engine.redlineRpm, 0.0)
        assertEquals(0.8, initial.carMasterVolume, 0.0)
    }
}
