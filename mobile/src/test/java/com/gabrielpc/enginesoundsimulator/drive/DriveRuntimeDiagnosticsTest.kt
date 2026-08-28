package com.gabrielpc.enginesoundsimulator.drive

import com.gabrielpc.enginesoundsimulator.audio.AudioOutputState
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRuntimeDiagnosticsTest {
    @Test
    fun `export is jsonl and escapes event text`() {
        val lines = DriveRuntimeDiagnostics.buildJsonLines(
            snapshot = testSnapshot(),
            recentLog = "quote \" and slash \\\nsecond event",
            exportedAtMs = 123L,
        ).toList()

        assertEquals(4, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines[1].contains("\"rpm\":null"))
        assertTrue(lines[1].contains("\"target_buffer_ms\":50"))
        assertTrue(lines[1].contains("\"core_steps\":321"))
        assertTrue(lines[1].contains("\"audio_frames\":654"))
        assertTrue(lines[1].contains("\"ui_snapshot_builds\":17"))
        assertTrue(lines[1].contains("\"alternate_gear_variants\":\"1st.rto{short=4.2}\""))
        assertTrue(lines[1].contains("\"selected_family\":\"family-123\""))
        assertTrue(lines[1].contains("\"selected_forward_gears\":7"))
        val concise = DriveRuntimeDiagnostics.conciseSummary(testSnapshot())
        assertTrue(concise.contains("core_steps=321 audio_frames=654"))
        assertTrue(concise.contains("pack_family=family-123 pack_car=test_car"))
        assertTrue(concise.contains("forward_gears=7 preview_present=false"))
        assertTrue(concise.contains("audio_errors=none"))
        assertTrue(lines[2].contains("\\\""))
        assertTrue(lines[2].contains("\\\\"))
    }

    private fun testSnapshot() = DriveSnapshot(
        coreSteps = 321L,
        drivetrain = DrivetrainState(
            rpm = Double.NaN,
            gear = 3,
            speedKmh = 84.5,
            smoothedThrottle = 0.4,
            smoothedBrake = 0.0,
            engineLoad = 0.4,
            isShifting = false,
            shiftDirection = ShiftDirection.NONE,
            shiftProgress = 0.0,
            shiftSerial = 7L,
            limiterActive = false,
            accelerationMps2 = 0.0,
            rawSpeedKmh = 84.0,
        ),
        inputMode = InputMode.VEHICLE,
        activeInput = "BYD PEDALS",
        throttle = 0.4,
        brake = 0.0,
        transmissionPosition = TransmissionPosition.DRIVE,
        engineSoundEnabled = true,
        audio = AudioOutputState(
            running = true,
            sampleStatus = "ACTIVE",
            sampleRate = 48_000,
            bufferFrames = 2_400,
            targetBufferMilliseconds = 50,
            queuedFrames = 2_048,
            sampleFramesRendered = 654L,
            alternateGearVariants = "1st.rto{short=4.2}",
            packLoadFamily = "family-123",
            packLoadCar = "test_car",
        ),
        telemetry = TelemetrySnapshot(),
        tuning = TuningConfig(),
        selectedCarId = "test_car",
        selectedCarName = "Test Car",
        selectedCarPreviewAsset = "test.png",
        selectedCarIndex = 0,
        availableCarCount = 1,
        soundEffects = emptyList(),
        soloSoundEffects = false,
        uiSnapshotBuildCount = 17L,
    )
}
