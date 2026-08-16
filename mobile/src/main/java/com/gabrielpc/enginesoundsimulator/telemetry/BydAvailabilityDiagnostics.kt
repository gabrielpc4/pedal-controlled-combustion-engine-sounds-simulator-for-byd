package com.gabrielpc.enginesoundsimulator.telemetry

import com.gabrielpc.enginesoundsimulator.drive.InputMode
import kotlin.math.roundToInt

data class BydAvailabilityReport(
    val vehiclePedalsAvailable: Boolean,
    val bydLiveWouldShowUnavailable: Boolean,
    val summary: String,
    val blockers: List<String>,
    val hints: List<String>,
)

fun TelemetrySnapshot.vehiclePedalsAvailable(): Boolean =
    readerState == ReaderState.ACTIVE && accelerator.isValid && brake.isValid

fun buildBydAvailabilityReport(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
): BydAvailabilityReport {
    val available = telemetry.vehiclePedalsAvailable()
    val blockers = mutableListOf<String>()
    val hints = mutableListOf<String>()

    if (telemetry.readerState != ReaderState.ACTIVE) {
        blockers += "Reader state is ${telemetry.readerState.name}, expected ACTIVE"
        when (telemetry.readerState) {
            ReaderState.IDLE -> hints += "Reader has not started yet. Open the app and wait a second."
            ReaderState.PROBING -> hints += "Reader is still probing BYD classes. Wait a moment."
            ReaderState.ACTIVE -> Unit
            ReaderState.UNAVAILABLE -> {
                telemetry.lastError?.let { blockers += "Probe error: $it" }
                hints += "BYD speed API probe failed. See probe diagnostics below."
            }
            ReaderState.STOPPED -> hints += "Reader was stopped (app left foreground?). Return to the dashboard."
        }
    }

    if (!telemetry.accelerator.isValid) {
        blockers += formatSignalBlocker("Accelerator", telemetry.accelerator)
    }
    if (!telemetry.brake.isValid) {
        blockers += formatSignalBlocker("Brake", telemetry.brake)
    }

    telemetry.lastError?.takeIf { telemetry.readerState == ReaderState.ACTIVE }?.let {
        blockers += "Latest poll error: $it"
    }

    addPermissionHints(telemetry, hints)
    addSignalHints(telemetry, hints)

    val bydLiveBlocked = mode == InputMode.VEHICLE && !available
    val summary = when {
        available && mode == InputMode.VEHICLE -> "BYD Live is receiving valid pedal data."
        available -> "BYD pedals are valid, but mode ${mode.displayName} may still use simulator input."
        bydLiveBlocked -> "BYD Live is blocked: ${blockers.firstOrNull() ?: "unknown reason"}"
        else -> "BYD pedals are not available."
    }

    return BydAvailabilityReport(
        vehiclePedalsAvailable = available,
        bydLiveWouldShowUnavailable = bydLiveBlocked,
        summary = summary,
        blockers = blockers,
        hints = hints.distinct(),
    )
}

private fun formatSignalBlocker(label: String, signal: SignalValue): String {
    val raw = signal.raw?.let { "raw=${formatTelemetryNumber(it)}" } ?: "raw=—"
    val issue = signal.issue ?: "no value"
    return "$label invalid ($raw, $issue)"
}

private fun addPermissionHints(telemetry: TelemetrySnapshot, hints: MutableList<String>) {
    telemetry.diagnostics.forEach { line ->
        when {
            line.contains("BYDAUTO_SPEED_GET") && line.contains("denied") -> {
                hints += "BYDAUTO_SPEED_GET is denied. DiLink often grants this only to system-signed apps."
            }
            line.contains("BYDAUTO_SPEED_GET") && line.contains("not defined") -> {
                hints += "BYDAUTO_SPEED_GET is missing on this firmware. Wrong device or unsupported build."
            }
            line.contains("BYDAUTO_SPEED_COMMON") && line.contains("denied") -> {
                hints += "BYDAUTO_SPEED_COMMON is denied."
            }
            line.contains("Probe failure") && line.contains("ClassNotFoundException") -> {
                hints += "BYD speed class not on classpath. Emulator/phone will always fail — use the Seal head unit."
            }
            line.contains("Probe failure") && line.contains("SecurityException") -> {
                hints += "SecurityException during probe. Signature or permission gate blocked vendor API access."
            }
        }
    }
}

private fun addSignalHints(telemetry: TelemetrySnapshot, hints: MutableList<String>) {
    listOf(
        telemetry.accelerator.issue to "accelerator",
        telemetry.brake.issue to "brake",
        telemetry.speed.issue to "speed",
    ).forEach { (issue, name) ->
        when (issue) {
            "permission denied" -> hints += "$name getter returned BYD permission denied sentinel."
            "SDK not available" -> hints += "$name getter says SDK not available on this build."
            "feature unbound" -> hints += "$name getter says feature unbound — service not ready yet."
            "no data" -> if (name == "speed") {
                hints += "Speed returned no-data sentinel. Pedals may still work; virtual speed will be used."
            }
        }
    }
}

fun formatTelemetryNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format("%.2f", value)
    }
