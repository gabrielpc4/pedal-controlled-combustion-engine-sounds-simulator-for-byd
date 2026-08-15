package com.gabrielpc.bydmotorsound

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.selection.SelectionContainer
import com.gabrielpc.bydmotorsound.telemetry.BydSpeedReader
import com.gabrielpc.bydmotorsound.telemetry.ReaderState
import com.gabrielpc.bydmotorsound.telemetry.SignalValue
import com.gabrielpc.bydmotorsound.telemetry.TelemetrySnapshot
import com.gabrielpc.bydmotorsound.ui.theme.BYDMotorSoundTheme
import java.util.Locale
import kotlin.math.roundToInt

private val DashboardBackground = Color(0xFF071018)
private val PanelBackground = Color(0xFF101D27)
private val PanelBorder = Color(0xFF243541)
private val PrimaryText = Color(0xFFF3F7FA)
private val SecondaryText = Color(0xFF9FB0BC)
private val AcceleratorColor = Color(0xFF2DDB89)
private val BrakeColor = Color(0xFFFF6269)
private val SpeedColor = Color(0xFF52C7FF)
private val WarningColor = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    private lateinit var reader: BydSpeedReader
    private val mainHandler = Handler(Looper.getMainLooper())
    private var telemetry by mutableStateOf(TelemetrySnapshot())
    private var nowNanos by mutableLongStateOf(0L)

    private val refreshUi = object : Runnable {
        override fun run() {
            telemetry = reader.snapshot()
            nowNanos = SystemClock.elapsedRealtimeNanos()
            mainHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BydSpeedReader(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            BYDMotorSoundTheme(darkTheme = true, dynamicColor = false) {
                TelemetryDashboard(
                    snapshot = telemetry,
                    nowNanos = nowNanos,
                    onRetry = reader::restart,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        reader.start()
        mainHandler.removeCallbacks(refreshUi)
        mainHandler.post(refreshUi)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(refreshUi)
        reader.stop()
        super.onStop()
    }
}

@Composable
private fun TelemetryDashboard(
    snapshot: TelemetrySnapshot,
    nowNanos: Long,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DashboardBackground,
        contentColor = PrimaryText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DashboardHeader(snapshot, onRetry)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 780.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            PedalCard("ACCELERATOR", snapshot.accelerator, AcceleratorColor, nowNanos)
                            PedalCard("BRAKE", snapshot.brake, BrakeColor, nowNanos)
                            SpeedCard(snapshot.speed, nowNanos)
                            TimingPanel(snapshot, nowNanos)
                        }
                        DiagnosticsPanel(
                            snapshot = snapshot,
                            modifier = Modifier.weight(0.85f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PedalCard("ACCELERATOR", snapshot.accelerator, AcceleratorColor, nowNanos)
                        PedalCard("BRAKE", snapshot.brake, BrakeColor, nowNanos)
                        SpeedCard(snapshot.speed, nowNanos)
                        TimingPanel(snapshot, nowNanos)
                        DiagnosticsPanel(snapshot)
                    }
                }
            }

            Text(
                text = "READ-ONLY DIAGNOSTIC • Test only while safely parked",
                color = SecondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun DashboardHeader(snapshot: TelemetrySnapshot, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BYD PEDAL PROBE",
                color = PrimaryText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Seal 2503 • direct DiLink capability test",
                color = SecondaryText,
                fontSize = 14.sp,
            )
        }
        StatusPill(snapshot.readerState, snapshot.lastError)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = PanelBorder,
                contentColor = PrimaryText,
            ),
        ) {
            Text("RETRY", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusPill(state: ReaderState, error: String?) {
    val color = when (state) {
        ReaderState.ACTIVE -> if (error == null) AcceleratorColor else WarningColor
        ReaderState.PROBING -> WarningColor
        ReaderState.UNAVAILABLE -> BrakeColor
        ReaderState.IDLE, ReaderState.STOPPED -> SecondaryText
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            text = state.name,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun PedalCard(
    label: String,
    signal: SignalValue,
    accent: Color,
    nowNanos: Long,
) {
    DiagnosticCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = SecondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = signal.value?.roundToInt()?.let { "$it%" } ?: "—",
                    color = if (signal.isValid) PrimaryText else SecondaryText,
                    fontSize = 42.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            SignalDetail(signal, nowNanos)
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { ((signal.value ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(50)),
            color = accent,
            trackColor = PanelBorder,
        )
        signal.issue?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = WarningColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SpeedCard(signal: SignalValue, nowNanos: Long) {
    DiagnosticCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VEHICLE SPEED",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = signal.value?.let { formatNumber(it, 1) } ?: "—",
                        color = if (signal.isValid) SpeedColor else SecondaryText,
                        fontSize = 38.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("km/h", color = SecondaryText, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            SignalDetail(signal, nowNanos)
        }
        signal.issue?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = WarningColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SignalDetail(signal: SignalValue, nowNanos: Long) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = "RAW ${signal.raw?.let { formatRawForUi(it) } ?: "—"}",
            color = SecondaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            text = "unchanged ${formatAge(nowNanos, signal.changedAtNanos)}",
            color = SecondaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TimingPanel(snapshot: TelemetrySnapshot, nowNanos: Long) {
    DiagnosticCard {
        Text(
            text = "TRANSPORT",
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Metric("MODE", snapshot.deliveryMode, Modifier.weight(1f))
            Metric("RATE", snapshot.cadence.rateHz?.let { "${formatNumber(it, 1)} Hz" } ?: "—", Modifier.weight(1f))
            Metric("READ AGE", formatAge(nowNanos, snapshot.lastReadAtNanos), Modifier.weight(1f))
            Metric(
                "CALL TIME",
                snapshot.lastReadDurationMs?.let { "${formatNumber(it, 2)} ms" } ?: "—",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "samples ${snapshot.cadence.sampleCount}  •  interval last ${formatMillis(snapshot.cadence.lastIntervalMs)}  " +
                "mean ${formatMillis(snapshot.cadence.meanIntervalMs)}  p95 ${formatMillis(snapshot.cadence.p95IntervalMs)}  " +
                "max ${formatMillis(snapshot.cadence.maxIntervalMs)}",
            color = SecondaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = SecondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            color = PrimaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiagnosticsPanel(snapshot: TelemetrySnapshot, modifier: Modifier = Modifier) {
    DiagnosticCard(modifier) {
        Text(
            text = "CAPABILITY DIAGNOSTICS",
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                text = buildString {
                    snapshot.diagnostics.forEach { append("• ").append(it).append('\n') }
                    snapshot.lastError?.let {
                        append('\n').append("LAST ERROR\n").append(it)
                    }
                }.trimEnd(),
                color = if (snapshot.lastError == null) PrimaryText else WarningColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun DiagnosticCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PanelBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

private fun formatAge(nowNanos: Long, thenNanos: Long?): String {
    if (thenNanos == null || nowNanos <= 0L) return "—"
    val milliseconds = ((nowNanos - thenNanos).coerceAtLeast(0L)) / 1_000_000.0
    return when {
        milliseconds < 1_000.0 -> "${milliseconds.roundToInt()} ms"
        else -> "${formatNumber(milliseconds / 1_000.0, 1)} s"
    }
}

private fun formatMillis(value: Double?): String = value?.let { "${formatNumber(it, 1)} ms" } ?: "—"

private fun formatRawForUi(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else formatNumber(value, 3)

private fun formatNumber(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
