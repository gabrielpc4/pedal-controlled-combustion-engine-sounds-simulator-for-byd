package com.gabrielpc.enginesoundsimulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.diagnostics.PersistentDiagnosticLog
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.SignalValue
import com.gabrielpc.enginesoundsimulator.telemetry.buildBydAvailabilityReport
import com.gabrielpc.enginesoundsimulator.telemetry.formatTelemetryNumber
import kotlin.math.roundToInt

private val DbgBackground = Color(0xFA03080E)
private val DbgPanel = Color(0xFF091721)
private val DbgLine = Color(0xFF1A4250)
private val DbgCyan = Color(0xFF35E8F2)
private val DbgGreen = Color(0xFF38E58C)
private val DbgAmber = Color(0xFFFFC456)
private val DbgRed = Color(0xFFFF465C)
private val DbgWhite = Color(0xFFF5FAFD)
private val DbgMuted = Color(0xFF8CA7B5)

@Composable
internal fun DebugPanel(
    state: DriveSnapshot,
    onRestartBydReader: () -> Unit,
    onRunSampleValidation: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val report = remember(state.inputMode, state.telemetry) {
        buildBydAvailabilityReport(state.inputMode, state.telemetry)
    }
    val logText = PersistentDiagnosticLog.readRecentLogText(context)
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF132C3A), DbgBackground, Color.Black),
                ),
            )
            .border(1.dp, DbgLine)
            .padding(horizontal = 30.dp, vertical = 22.dp),
    ) {
        DebugHeader(
            onRestartBydReader = onRestartBydReader,
            onRunSampleValidation = onRunSampleValidation,
            onClose = onClose,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DebugSection(
                title = "BYD LIVE STATUS",
                accent = if (report.bydLiveWouldShowUnavailable) DbgRed else DbgGreen,
            ) {
                Text(report.summary, color = DbgWhite, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(8.dp))
                DebugLine("Input mode", state.inputMode.displayName)
                DebugLine("Active input label", state.activeInput)
                DebugLine("Reader state", state.telemetry.readerState.name)
                DebugLine("Delivery", state.telemetry.deliveryMode)
                if (report.blockers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Blockers", color = DbgRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    report.blockers.forEach { line ->
                        Text("• $line", color = DbgRed.copy(alpha = 0.92f), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                if (report.hints.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Likely causes / next steps", color = DbgAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    report.hints.forEach { line ->
                        Text("• $line", color = DbgAmber.copy(alpha = 0.95f), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }

            DebugSection(title = "LIVE SIGNALS", accent = DbgCyan) {
                SignalRow("Accelerator", state.telemetry.accelerator)
                SignalRow("Brake", state.telemetry.brake)
                SignalRow("Speed", state.telemetry.speed)
                DebugLine("Last poll duration", state.telemetry.lastReadDurationMs?.let { "${formatTelemetryNumber(it)} ms" } ?: "—")
                DebugLine("Poll rate", state.telemetry.cadence.rateHz?.let { "${formatTelemetryNumber(it)} Hz" } ?: "—")
                DebugLine("Samples", state.telemetry.cadence.sampleCount.toString())
                state.telemetry.lastError?.let { error ->
                    DebugLine("Last poll error", error)
                }
            }

            DebugSection(title = "PROBE DIAGNOSTICS", accent = DbgCyan) {
                if (state.telemetry.diagnostics.isEmpty()) {
                    Text("(no probe diagnostics yet)", color = DbgMuted, fontSize = 12.sp)
                } else {
                    state.telemetry.diagnostics.forEach { line ->
                        Text(line, color = DbgMuted, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            DebugSection(
                title = "ENGINE SAMPLE AUDIO",
                accent = if (state.audio.sampleStatus == "ACTIVE") DbgGreen else DbgAmber,
            ) {
                DebugLine("Sample status", state.audio.sampleStatus)
                DebugLine("Sample profile", state.audio.sampleProfile)
                DebugLine("Loaded loops", state.audio.sampleLoadedLoops.toString())
                DebugLine("Decoded memory", "${(state.audio.sampleDecodedBytes / (1024 * 1024.0)).roundToInt()} MiB")
                DebugLine("Target sample RPM", state.audio.sampleTargetRpm.toString())
                DebugLine("Rendered sample RPM", state.audio.sampleRenderRpm.toString())
                DebugLine("Rendered throttle", "${(state.audio.sampleThrottle * 100.0).roundToInt()}%")
                DebugLine("Rendered frames", state.audio.sampleFramesRendered.toString())
                DebugLine("Loop wraps", state.audio.sampleLoopWraps.toString())
                DebugLine("Output peak", "${(state.audio.samplePeak * 100.0).roundToInt()}%")
                DebugLine("Over-range before limiter", state.audio.sampleOverRangeSamples.toString())
                DebugLine("Startup underruns", state.audio.startupUnderruns.toString())
                DebugLine("New underruns", state.audio.steadyStateUnderruns.toString())
                Text(
                    "Active layers: ${state.audio.sampleActiveLayers}",
                    color = DbgWhite,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp),
                )
                state.audio.sampleError?.let { error ->
                    Text("Required-bank error: $error", color = DbgRed, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }

            DebugSection(title = "PERSISTED EVENT LOG", accent = DbgCyan) {
                Text(
                    text = "Path: ${PersistentDiagnosticLog.activeLogPath(context)}",
                    color = DbgMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = logText,
                    color = DbgWhite,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun DebugHeader(
    onRestartBydReader: () -> Unit,
    onRunSampleValidation: () -> Unit,
    onClose: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("DIAGNOSTICS", color = DbgWhite, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text(
                    text = " ${AppBuildInfo.diagnosticTitleSuffix}",
                    color = DbgCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                )
            }
            Text(
                "BYD telemetry probe, live signal validity, and persisted event log · built ${AppBuildInfo.builtAtUtc}",
                color = DbgMuted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        DebugAction("RUN AUDIO TEST", DbgGreen, onRunSampleValidation)
        Spacer(Modifier.width(10.dp))
        DebugAction("RETRY BYD", DbgAmber, onRestartBydReader)
        Spacer(Modifier.width(10.dp))
        DebugAction("CLOSE", DbgCyan, onClose)
    }
}

@Composable
private fun DebugSection(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DbgPanel, RoundedCornerShape(14.dp))
            .border(1.dp, DbgLine, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = DbgMuted, fontSize = 12.sp, modifier = Modifier.width(156.dp))
        Text(value, color = DbgWhite, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SignalRow(label: String, signal: SignalValue) {
    val status = if (signal.isValid) {
        "OK"
    } else {
        "INVALID"
    }
    val statusColor = if (signal.isValid) {
        DbgGreen
    } else {
        DbgRed
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, color = DbgMuted, fontSize = 12.sp, modifier = Modifier.width(156.dp))
            Text(status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        val raw = signal.raw?.let { formatTelemetryNumber(it) } ?: "—"
        val value = signal.value?.let { formatTelemetryNumber(it) } ?: "—"
        val issue = signal.issue ?: "none"
        Text(
            "raw=$raw  value=$value  issue=$issue",
            color = DbgMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 156.dp, top = 2.dp),
        )
    }
}

@Composable
private fun DebugAction(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DbgPanel, contentColor = accent),
        modifier = Modifier.height(44.dp),
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
    }
}
