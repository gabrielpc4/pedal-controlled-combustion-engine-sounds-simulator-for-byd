package com.gabrielpc.bydmotorsound

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.bydmotorsound.drive.DriveSnapshot
import com.gabrielpc.bydmotorsound.tuning.AudioTuning
import com.gabrielpc.bydmotorsound.tuning.CurvePoint
import com.gabrielpc.bydmotorsound.tuning.EngineTuning
import com.gabrielpc.bydmotorsound.tuning.TuningConfig
import com.gabrielpc.bydmotorsound.tuning.interpolateCurve
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TuneBackground = Color(0xFA03080E)
private val TunePanel = Color(0xFF091721)
private val TunePanelBright = Color(0xFF102735)
private val TuneLine = Color(0xFF1A4250)
private val TuneCyan = Color(0xFF35E8F2)
private val TuneGreen = Color(0xFF38E58C)
private val TuneAmber = Color(0xFFFFC456)
private val TuneRed = Color(0xFFFF465C)
private val TuneWhite = Color(0xFFF5FAFD)
private val TuneMuted = Color(0xFF8CA7B5)

private enum class TuningTab(val title: String, val subtitle: String) {
    ENGINE("ENGINE", "CORE PARAMETERS"),
    CURVES("CURVES", "DRAG CONTROL POINTS"),
    TRANSMISSION("GEARING", "RATIOS & SHIFT LOGIC"),
    AUDIO("AUDIO", "LAYERS & HARMONICS"),
}

@Composable
internal fun TuningPanel(
    state: DriveSnapshot,
    onConfigChange: (TuningConfig) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val config = state.tuning

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF132C3A), TuneBackground, Color.Black),
                    center = Offset(1_260f, 470f),
                    radius = 1_250f,
                ),
            )
            .border(1.dp, TuneLine)
            .padding(horizontal = 30.dp, vertical = 22.dp),
    ) {
        TuningHeader(config, onReset, onClose)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningTab.entries.forEachIndexed { index, tab ->
                TabButton(tab, selected = tabIndex == index, onClick = { tabIndex = index })
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (TuningTab.entries[tabIndex]) {
                TuningTab.ENGINE -> EngineTab(state, config, onConfigChange)
                TuningTab.CURVES -> CurvesTab(state, config, onConfigChange)
                TuningTab.TRANSMISSION -> TransmissionTab(config, onConfigChange)
                TuningTab.AUDIO -> AudioTab(config, onConfigChange)
            }
        }
    }
}

@Composable
private fun TuningHeader(config: TuningConfig, onReset: () -> Unit, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(TuneGreen, CircleShape))
                Text("LIVE TUNING", color = TuneWhite, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("// APEX V10", color = TuneCyan, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 1.6.sp)
            }
            Text(
                "Changes apply immediately and are saved automatically  •  ${config.engine.maxRpm.roundToInt()} RPM TACH  •  ${config.engine.redlineRpm.roundToInt()} REDLINE  •  ${config.engine.maxTorqueNm.roundToInt()} Nm",
                color = TuneMuted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 22.dp, top = 4.dp),
            )
        }
        SmallAction("RESET", TuneAmber, onReset)
        Spacer(Modifier.width(10.dp))
        SmallAction("CLOSE", TuneCyan, onClose)
    }
}

@Composable
private fun TabButton(tab: TuningTab, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(230.dp).height(58.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) TuneCyan.copy(alpha = 0.16f) else TunePanel,
            contentColor = TuneWhite,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) TuneCyan else TuneLine),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(tab.title, color = if (selected) TuneCyan else TuneWhite, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text(tab.subtitle, color = TuneMuted, fontSize = 8.sp, letterSpacing = 0.7.sp)
        }
    }
}

@Composable
private fun EngineTab(state: DriveSnapshot, config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val engine = config.engine
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("ENGINE SPEED", "RPM envelope and automatic strategy", Modifier.weight(0.78f)) {
            ParameterSlider("TACHOMETER MAX", engine.maxRpm, 6_000.0..12_000.0, "%.0f RPM") {
                onChange(config.copy(engine = engine.copy(
                    maxRpm = it,
                    redlineRpm = min(engine.redlineRpm, it - 300.0),
                    limiterRpm = min(engine.limiterRpm, it - 100.0),
                )))
            }
            ParameterSlider("REDLINE", engine.redlineRpm, 4_000.0..(engine.maxRpm - 300.0), "%.0f RPM") {
                onChange(config.copy(engine = engine.copy(
                    redlineRpm = it,
                    limiterRpm = max(engine.limiterRpm, it),
                    upshiftRpm = min(engine.upshiftRpm, it - 100.0),
                )))
            }
            ParameterSlider("FUEL CUTOFF", engine.limiterRpm, engine.redlineRpm..(engine.maxRpm - 100.0), "%.0f RPM") {
                onChange(config.copy(engine = engine.copy(limiterRpm = it)))
            }
            ParameterSlider("IDLE RPM", engine.idleRpm, 600.0..2_000.0, "%.0f") {
                onChange(config.copy(engine = engine.copy(idleRpm = it)))
            }
            ParameterSlider("UPSHIFT RPM", engine.upshiftRpm, (engine.idleRpm + 1_000.0)..(engine.redlineRpm - 100.0), "%.0f") {
                onChange(config.copy(engine = engine.copy(upshiftRpm = it)))
            }
            ParameterSlider("DOWNSHIFT RPM", engine.downshiftRpm, 1_000.0..min(4_500.0, engine.upshiftRpm - 500.0), "%.0f") {
                onChange(config.copy(engine = engine.copy(downshiftRpm = it)))
            }
        }
        PanelCard("PHYSICAL MODEL", "Torque, inertia, mass and rolling geometry", Modifier.weight(0.88f)) {
            ParameterSlider("PEAK TORQUE", engine.maxTorqueNm, 150.0..1_200.0, "%.0f Nm") {
                onChange(config.copy(engine = engine.copy(maxTorqueNm = it)))
            }
            ParameterSlider("ENGINE INERTIA", engine.engineInertiaKgM2, 0.15..1.50, "%.2f kg·m²") {
                onChange(config.copy(engine = engine.copy(engineInertiaKgM2 = it)))
            }
            ParameterSlider("VEHICLE MASS", engine.vehicleMassKg, 700.0..3_500.0, "%.0f kg") {
                onChange(config.copy(engine = engine.copy(vehicleMassKg = it)))
            }
            ParameterSlider("WHEEL RADIUS", engine.wheelRadiusMeters, 0.22..0.50, "%.3f m") {
                onChange(config.copy(engine = engine.copy(wheelRadiusMeters = it)))
            }
        }
        PanelCard("TORQUE + POWER", "Computed from the editable torque curve", Modifier.weight(1.34f)) {
            TorquePowerGraph(engine, state.drivetrain.rpm, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CurvesTab(state: DriveSnapshot, config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val engine = config.engine
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("TORQUE CURVE", "Drag any point • horizontal = RPM • vertical = torque", Modifier.weight(1.18f)) {
            EditableCurveGraph(
                points = engine.torqueCurve,
                xLabel = { "${(it * engine.limiterRpm / 1_000.0).format(1)}k" },
                yLabel = { "${(it * engine.maxTorqueNm).roundToInt()} Nm" },
                currentX = (state.drivetrain.rpm / engine.limiterRpm).coerceIn(0.0, 1.0),
                accent = TuneCyan,
                lockEndpointX = true,
                onPointsChange = { onChange(config.copy(engine = engine.copy(torqueCurve = it))) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PanelCard("THROTTLE RESPONSE", "Drag the three middle points • pedal vs requested torque", Modifier.weight(1f)) {
            EditableCurveGraph(
                points = engine.throttleCurve,
                xLabel = { "${(it * 100).roundToInt()}%" },
                yLabel = { "${(it * 100).roundToInt()}%" },
                currentX = state.throttle,
                accent = TuneGreen,
                lockEndpointX = true,
                lockEndpointY = true,
                onPointsChange = { onChange(config.copy(engine = engine.copy(throttleCurve = it))) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PanelCard("PEDAL DYNAMICS", "Filter timing shapes transient response", Modifier.weight(0.72f)) {
            ParameterSlider("THROTTLE ATTACK", engine.throttleAttackMs, 15.0..500.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(throttleAttackMs = it)))
            }
            ParameterSlider("THROTTLE RELEASE", engine.throttleReleaseMs, 20.0..800.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(throttleReleaseMs = it)))
            }
            ParameterSlider("BRAKE ATTACK", engine.brakeResponseMs, 15.0..500.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(brakeResponseMs = it)))
            }
            Spacer(Modifier.height(12.dp))
            ResponsePreview(engine, Modifier.fillMaxWidth().height(190.dp))
        }
    }
}

@Composable
private fun TransmissionTab(config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val engine = config.engine
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("SHIFT CONTROL", "Timing, dwell and final drive", Modifier.weight(0.76f)) {
            ParameterSlider("FINAL DRIVE", engine.finalDrive, 2.0..6.0, "%.2f") {
                onChange(config.copy(engine = engine.copy(finalDrive = it)))
            }
            ParameterSlider("UPSHIFT TIME", engine.upshiftDurationMs, 100.0..900.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(upshiftDurationMs = it)))
            }
            ParameterSlider("DOWNSHIFT TIME", engine.downshiftDurationMs, 120.0..1_000.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(downshiftDurationMs = it)))
            }
            ParameterSlider("GEAR DWELL", engine.shiftDwellMs, 100.0..1_500.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(shiftDwellMs = it)))
            }
        }
        PanelCard("GEAR RATIOS", "Each ratio is live • lower gears remain ordered", Modifier.weight(0.96f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                engine.gearRatios.forEachIndexed { index, ratio ->
                    val maximum = if (index == 0) 5.0 else engine.gearRatios[index - 1] - 0.05
                    val minimum = if (index == engine.gearRatios.lastIndex) 0.45 else engine.gearRatios[index + 1] + 0.05
                    ParameterSlider("GEAR ${index + 1}", ratio, minimum..maximum, "%.2f") { value ->
                        val ratios = engine.gearRatios.toMutableList()
                        ratios[index] = value
                        onChange(config.copy(engine = engine.copy(gearRatios = ratios)))
                    }
                }
            }
        }
        PanelCard("RPM AFTER EACH SHIFT", "Calculated at ${engine.upshiftRpm.roundToInt()} RPM", Modifier.weight(1.25f)) {
            GearDropGraph(engine, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AudioTab(config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val audio = config.audio
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("ENGINE MIX", "Balance the synthesized source layers", Modifier.weight(0.82f)) {
            AudioSlider("MASTER", audio.masterGain, 0.0..1.2) { onChange(config.copy(audio = audio.copy(masterGain = it))) }
            AudioSlider("EXHAUST", audio.exhaustLevel, 0.0..1.5) { onChange(config.copy(audio = audio.copy(exhaustLevel = it))) }
            AudioSlider("INTAKE", audio.intakeLevel, 0.0..1.5) { onChange(config.copy(audio = audio.copy(intakeLevel = it))) }
            AudioSlider("MECHANICAL", audio.mechanicalLevel, 0.0..1.5) { onChange(config.copy(audio = audio.copy(mechanicalLevel = it))) }
            AudioSlider("OVERRUN", audio.overrunLevel, 0.0..1.5) { onChange(config.copy(audio = audio.copy(overrunLevel = it))) }
            AudioSlider("SHIFT IMPACT", audio.shiftLevel, 0.0..1.5) { onChange(config.copy(audio = audio.copy(shiftLevel = it))) }
        }
        PanelCard("HARMONIC CHARACTER", "Shape the firing-order spectrum", Modifier.weight(0.78f)) {
            AudioSlider("2ND HARMONIC", audio.harmonic2, 0.0..1.5) { onChange(config.copy(audio = audio.copy(harmonic2 = it))) }
            AudioSlider("3RD HARMONIC", audio.harmonic3, 0.0..1.5) { onChange(config.copy(audio = audio.copy(harmonic3 = it))) }
            AudioSlider("4TH HARMONIC", audio.harmonic4, 0.0..1.5) { onChange(config.copy(audio = audio.copy(harmonic4 = it))) }
            AudioSlider("5TH HARMONIC", audio.harmonic5, 0.0..1.5) { onChange(config.copy(audio = audio.copy(harmonic5 = it))) }
        }
        PanelCard("SPECTRAL PROFILE", "Relative contribution of each editable harmonic", Modifier.weight(1.10f)) {
            AudioSpectrumGraph(audio, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PanelCard(title: String, subtitle: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(TunePanel.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
            .border(1.dp, TuneLine, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text(title, color = TuneWhite, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Text(subtitle, color = TuneMuted, fontSize = 9.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    format: String,
    onValueChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TuneMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, format, value), color = TuneCyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = TuneCyan,
                activeTrackColor = TuneCyan,
                inactiveTrackColor = TuneLine,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun AudioSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    ParameterSlider(label, value, range, "%.2f×", onChange)
}

@Composable
private fun SmallAction(label: String, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TunePanelBright),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun EditableCurveGraph(
    points: List<CurvePoint>,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
    currentX: Double,
    accent: Color,
    lockEndpointX: Boolean,
    modifier: Modifier = Modifier,
    lockEndpointY: Boolean = false,
    onPointsChange: (List<CurvePoint>) -> Unit,
) {
    var activePoint by remember { mutableIntStateOf(-1) }
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChange by rememberUpdatedState(onPointsChange)
    val graphPaddingLeft = 58f
    val graphPaddingRight = 28f
    val graphPaddingTop = 32f
    val graphPaddingBottom = 54f

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { position ->
                    val dragPoints = currentPoints
                    val graphWidth = size.width - graphPaddingLeft - graphPaddingRight
                    val graphHeight = size.height - graphPaddingTop - graphPaddingBottom
                    activePoint = dragPoints.indices.minByOrNull { index ->
                        val point = dragPoints[index]
                        val px = graphPaddingLeft + point.x.toFloat() * graphWidth
                        val py = graphPaddingTop + (1f - point.y.toFloat() / 1.15f) * graphHeight
                        val dx = position.x - px
                        val dy = position.y - py
                        dx * dx + dy * dy
                    } ?: -1
                },
                onDragEnd = { activePoint = -1 },
                onDragCancel = { activePoint = -1 },
            ) { change, _ ->
                val index = activePoint
                val dragPoints = currentPoints
                if (index !in dragPoints.indices) return@detectDragGestures
                change.consume()
                val graphWidth = (size.width - graphPaddingLeft - graphPaddingRight).coerceAtLeast(1f)
                val graphHeight = (size.height - graphPaddingTop - graphPaddingBottom).coerceAtLeast(1f)
                var x = ((change.position.x - graphPaddingLeft) / graphWidth).coerceIn(0f, 1f).toDouble()
                var y = ((1f - (change.position.y - graphPaddingTop) / graphHeight) * 1.15f).coerceIn(0f, 1.15f).toDouble()
                val firstOrLast = index == 0 || index == dragPoints.lastIndex
                if (lockEndpointX && firstOrLast) x = dragPoints[index].x
                if (lockEndpointY && firstOrLast) y = dragPoints[index].y
                if (index > 0) x = max(x, dragPoints[index - 1].x + 0.025)
                if (index < dragPoints.lastIndex) x = min(x, dragPoints[index + 1].x - 0.025)
                val changed = dragPoints.toMutableList()
                changed[index] = CurvePoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.15))
                currentOnPointsChange(changed)
            }
        },
    ) {
        val left = graphPaddingLeft
        val right = size.width - graphPaddingRight
        val top = graphPaddingTop
        val bottom = size.height - graphPaddingBottom
        val width = right - left
        val height = bottom - top

        repeat(6) { index ->
            val fraction = index / 5f
            val x = left + width * fraction
            val y = bottom - height * fraction
            drawLine(TuneLine.copy(alpha = 0.70f), Offset(x, top), Offset(x, bottom), 1f)
            drawLine(TuneLine.copy(alpha = 0.70f), Offset(left, y), Offset(right, y), 1f)
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = left + point.x.toFloat() * width
            val y = bottom - (point.y.toFloat() / 1.15f) * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(4f, cap = StrokeCap.Round))

        val liveY = interpolateCurve(points, currentX).coerceIn(0.0, 1.15)
        val livePoint = Offset(left + currentX.toFloat() * width, bottom - (liveY.toFloat() / 1.15f) * height)
        drawLine(accent.copy(alpha = 0.28f), Offset(livePoint.x, top), Offset(livePoint.x, bottom), 2f)
        drawCircle(accent.copy(alpha = 0.22f), 15f, livePoint)
        drawCircle(TuneWhite, 5f, livePoint)

        points.forEachIndexed { index, point ->
            val center = Offset(left + point.x.toFloat() * width, bottom - (point.y.toFloat() / 1.15f) * height)
            drawCircle(if (index == activePoint) TuneWhite else TunePanelBright, if (index == activePoint) 12f else 10f, center)
            drawCircle(accent, if (index == activePoint) 12f else 10f, center, style = Stroke(4f))
        }

        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(140, 167, 181)
                textSize = 22f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            repeat(5) { index ->
                val fraction = index / 4.0
                canvas.nativeCanvas.drawText(xLabel(fraction), left + width * fraction.toFloat(), bottom + 35f, paint)
            }
            paint.textAlign = Paint.Align.RIGHT
            repeat(4) { index ->
                val fraction = index / 3.0 * 1.15
                canvas.nativeCanvas.drawText(yLabel(fraction), left - 10f, bottom - height * (fraction / 1.15).toFloat() + 7f, paint)
            }
        }
    }
}

@Composable
private fun TorquePowerGraph(engine: EngineTuning, currentRpm: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 54f
        val right = size.width - 24f
        val top = 30f
        val bottom = size.height - 50f
        val width = right - left
        val height = bottom - top
        repeat(6) { index ->
            val f = index / 5f
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(left + width * f, top), Offset(left + width * f, bottom), 1f)
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(left, bottom - height * f), Offset(right, bottom - height * f), 1f)
        }
        val torquePath = Path()
        val powerPath = Path()
        var maxPowerKw = 1.0
        repeat(101) { index ->
            val x = index / 100.0
            val torque = interpolateCurve(engine.torqueCurve, x) * engine.maxTorqueNm
            val rpm = x * engine.limiterRpm
            maxPowerKw = max(maxPowerKw, torque * rpm / 9_549.0)
        }
        repeat(101) { index ->
            val x = index / 100.0
            val torque = interpolateCurve(engine.torqueCurve, x) * engine.maxTorqueNm
            val rpm = x * engine.limiterRpm
            val power = torque * rpm / 9_549.0
            val px = left + width * x.toFloat()
            val torqueY = bottom - height * (torque / (engine.maxTorqueNm * 1.15)).toFloat()
            val powerY = bottom - height * (power / (maxPowerKw * 1.10)).toFloat()
            if (index == 0) {
                torquePath.moveTo(px, torqueY)
                powerPath.moveTo(px, powerY)
            } else {
                torquePath.lineTo(px, torqueY)
                powerPath.lineTo(px, powerY)
            }
        }
        drawPath(torquePath, TuneCyan, style = Stroke(4f, cap = StrokeCap.Round))
        drawPath(powerPath, TuneAmber, style = Stroke(4f, cap = StrokeCap.Round))
        val liveX = (currentRpm / engine.limiterRpm).coerceIn(0.0, 1.0).toFloat()
        drawLine(TuneWhite.copy(alpha = 0.45f), Offset(left + width * liveX, top), Offset(left + width * liveX, bottom), 2f)
        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(53, 232, 242)
            canvas.nativeCanvas.drawText("TORQUE  ${engine.maxTorqueNm.roundToInt()} Nm", left, bottom + 34f, paint)
            paint.color = android.graphics.Color.rgb(255, 196, 86)
            canvas.nativeCanvas.drawText("POWER  ${maxPowerKw.roundToInt()} kW", left + width * 0.50f, bottom + 34f, paint)
        }
    }
}

@Composable
private fun GearDropGraph(engine: EngineTuning, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 58f
        val right = size.width - 25f
        val top = 38f
        val bottom = size.height - 58f
        val width = right - left
        val height = bottom - top
        val count = engine.gearRatios.lastIndex.coerceAtLeast(1)
        repeat(5) { index ->
            val f = index / 4f
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(left, bottom - height * f), Offset(right, bottom - height * f), 1f)
        }
        for (index in 0 until engine.gearRatios.lastIndex) {
            val postShift = engine.upshiftRpm * engine.gearRatios[index + 1] / engine.gearRatios[index]
            val x = left + width * ((index + 0.5f) / count)
            val barWidth = width / count * 0.52f
            val y = bottom - height * (postShift / engine.maxRpm).toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(TuneCyan, TuneGreen.copy(alpha = 0.5f))),
                topLeft = Offset(x - barWidth / 2, y),
                size = androidx.compose.ui.geometry.Size(barWidth, bottom - y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
            )
            drawIntoCanvas { canvas ->
                val paint = graphPaint()
                paint.textAlign = Paint.Align.CENTER
                paint.color = android.graphics.Color.WHITE
                canvas.nativeCanvas.drawText("${index + 1}→${index + 2}", x, bottom + 34f, paint)
                paint.color = android.graphics.Color.rgb(53, 232, 242)
                canvas.nativeCanvas.drawText(postShift.roundToInt().toString(), x, y - 12f, paint)
            }
        }
        val downshiftY = bottom - height * (engine.downshiftRpm / engine.maxRpm).toFloat()
        drawLine(TuneAmber, Offset(left, downshiftY), Offset(right, downshiftY), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}

@Composable
private fun AudioSpectrumGraph(audio: AudioTuning, modifier: Modifier = Modifier) {
    val values = listOf(1.0, audio.harmonic2, audio.harmonic3, audio.harmonic4, audio.harmonic5)
    Canvas(modifier) {
        val left = 50f
        val right = size.width - 30f
        val top = 38f
        val bottom = size.height - 62f
        val width = right - left
        val height = bottom - top
        repeat(4) { index ->
            val f = index / 3f
            drawLine(TuneLine, Offset(left, bottom - height * f), Offset(right, bottom - height * f), 1f)
        }
        values.forEachIndexed { index, value ->
            val slot = width / values.size
            val x = left + slot * (index + 0.5f)
            val barWidth = slot * 0.48f
            val y = bottom - height * (value / 1.5).toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(TuneAmber, TuneRed.copy(alpha = 0.55f))),
                topLeft = Offset(x - barWidth / 2, y),
                size = androidx.compose.ui.geometry.Size(barWidth, bottom - y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f),
            )
            drawIntoCanvas { canvas ->
                val paint = graphPaint()
                paint.textAlign = Paint.Align.CENTER
                paint.color = android.graphics.Color.WHITE
                canvas.nativeCanvas.drawText(if (index == 0) "FUND" else "H${index + 1}", x, bottom + 36f, paint)
                paint.color = android.graphics.Color.rgb(255, 196, 86)
                canvas.nativeCanvas.drawText(value.format(2), x, y - 12f, paint)
            }
        }
    }
}

@Composable
private fun ResponsePreview(engine: EngineTuning, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 32f
        val right = size.width - 18f
        val top = 18f
        val bottom = size.height - 28f
        val width = right - left
        val height = bottom - top
        repeat(5) { index ->
            val f = index / 4f
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(left + width * f, top), Offset(left + width * f, bottom), 1f)
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(left, bottom - height * f), Offset(right, bottom - height * f), 1f)
        }
        fun responsePath(timeMs: Double): Path {
            val path = Path()
            repeat(101) { index ->
                val timeSeconds = index / 100.0
                val response = 1.0 - kotlin.math.exp(-timeSeconds / (timeMs / 1_000.0))
                val x = left + width * index / 100f
                val y = bottom - height * response.toFloat().coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        drawPath(responsePath(engine.throttleAttackMs), TuneGreen, style = Stroke(3f))
        drawPath(responsePath(engine.throttleReleaseMs), TuneCyan, style = Stroke(3f))
        drawPath(responsePath(engine.brakeResponseMs), TuneRed, style = Stroke(3f))
    }
}

private fun graphPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 21f
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
}

private fun Double.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
