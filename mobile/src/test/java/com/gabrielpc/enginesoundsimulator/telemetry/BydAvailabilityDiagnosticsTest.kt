package com.gabrielpc.enginesoundsimulator.telemetry

import com.gabrielpc.enginesoundsimulator.drive.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BydAvailabilityDiagnosticsTest {
    @Test
    fun unavailableReaderExplainsBydLiveBlocker() {
        val report = buildBydAvailabilityReport(
            mode = InputMode.VEHICLE,
            telemetry = TelemetrySnapshot(
                readerState = ReaderState.UNAVAILABLE,
                lastError = "ClassNotFoundException: BYDAutoSpeedDevice",
                diagnostics = listOf("BYDAUTO_SPEED_GET: not defined by this firmware"),
            ),
        )

        assertTrue(report.bydLiveWouldShowUnavailable)
        assertTrue(report.blockers.any { it.contains("UNAVAILABLE") })
        assertTrue(report.hints.any { it.contains("falhou") || it.contains("firmware") })
    }

    @Test
    fun invalidAcceleratorBlocksEvenWhenReaderActive() {
        val report = buildBydAvailabilityReport(
            mode = InputMode.VEHICLE,
            telemetry = TelemetrySnapshot(
                readerState = ReaderState.ACTIVE,
                accelerator = SignalValue(raw = -10_005.0, value = null, issue = "permission denied"),
                brake = SignalValue(raw = 0.0, value = 0.0),
            ),
        )

        assertTrue(report.bydLiveWouldShowUnavailable)
        assertTrue(report.blockers.any { it.contains("Acelerador inválido") })
        assertTrue(report.hints.any { it.contains("permissão") })
    }

    @Test
    fun validPedalsAreAvailableInBydLive() {
        val report = buildBydAvailabilityReport(
            mode = InputMode.VEHICLE,
            telemetry = TelemetrySnapshot(
                readerState = ReaderState.ACTIVE,
                accelerator = SignalValue(raw = 12.0, value = 12.0),
                brake = SignalValue(raw = 0.0, value = 0.0),
            ),
        )

        assertTrue(report.vehiclePedalsAvailable)
        assertEquals(false, report.bydLiveWouldShowUnavailable)
    }
}
