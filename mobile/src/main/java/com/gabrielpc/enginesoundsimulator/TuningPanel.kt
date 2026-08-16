package com.gabrielpc.enginesoundsimulator

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.SampleLayerRole
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
import java.util.Locale
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
    ENGINE("SIMULATION", "TACH + SHIFT BEHAVIOR"),
    RESPONSE("RESPONSE", "PEDAL + RPM DYNAMICS"),
    AUDIO("AUDIO", "SAMPLE BANK"),
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
        TuningHeader(state.selectedCarName, onReset, onClose)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningTab.entries.forEachIndexed { index, tab ->
                TabButton(tab, selected = tabIndex == index, onClick = { tabIndex = index })
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (TuningTab.entries[tabIndex]) {
                TuningTab.ENGINE -> EngineTab(config, onConfigChange)
                TuningTab.RESPONSE -> ResponseTab(state, config, onConfigChange)
                TuningTab.AUDIO -> AudioTab(config, state.selectedCarId, onConfigChange)
            }
        }
    }
}

@Composable
private fun TuningHeader(
    selectedCarName: String,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(TuneGreen, CircleShape))
                Text("LIVE TUNING", color = TuneWhite, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text(
                    "// ${selectedCarName.uppercase(Locale.ROOT).take(36)}",
                    color = TuneCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                )
            }
            Text(
                "Changes apply immediately and are saved automatically • simulated sound and tach behavior only",
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
private fun EngineTab(config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val engine = config.engine
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard(
            "SIMULATED ENGINE",
            "Fake RPM, shift timing, and pedal-to-sound behavior",
            Modifier.weight(1f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ParameterSlider("TACHOMETER MAX", engine.maxRpm, 6_000.0..EngineSampleProfiles.maximumSupportedRpm, "%.0f RPM") {
                    onChange(config.copy(engine = engine.copy(
                        maxRpm = it,
                        redlineRpm = min(engine.redlineRpm, it - 100.0),
                        limiterRpm = min(engine.limiterRpm, it),
                    )))
                }
                ParameterSlider("REDLINE", engine.redlineRpm, 4_000.0..(engine.maxRpm - 100.0), "%.0f RPM") {
                    onChange(config.copy(engine = engine.copy(
                        redlineRpm = it,
                        limiterRpm = max(engine.limiterRpm, it),
                    )))
                }
                ParameterSlider("SOUND LIMITER", engine.limiterRpm, engine.redlineRpm..(engine.maxRpm - 100.0), "%.0f RPM") {
                    onChange(config.copy(engine = engine.copy(limiterRpm = it)))
                }
                ParameterSlider("IDLE RPM", engine.idleRpm, 600.0..2_000.0, "%.0f") {
                    onChange(config.copy(engine = engine.copy(idleRpm = it)))
                }
                ParameterSlider("MAX RPM FORCE", engine.driveRpmAccelerationPerSecond, 1_500.0..12_000.0, "%.0f RPM/s") {
                    onChange(config.copy(engine = engine.copy(driveRpmAccelerationPerSecond = it)))
                }
                ParameterSlider("FULL PEDAL SWEET SPOT", engine.fullThrottleSweetSpotRpm, (engine.idleRpm + 800.0)..(engine.redlineRpm - 350.0), "%.0f RPM") {
                    onChange(config.copy(engine = engine.copy(fullThrottleSweetSpotRpm = it)))
                }
                ParameterSlider("FULL PEDAL KICK", engine.fullThrottleKickRpmPerSecond, 6_000.0..60_000.0, "%.0f RPM/s") {
                    onChange(config.copy(engine = engine.copy(fullThrottleKickRpmPerSecond = it)))
                }
                ParameterSlider("VIRTUAL SHIFT RPM", engine.upshiftRpm, (engine.fullThrottleSweetSpotRpm.coerceAtMost(engine.redlineRpm - 500.0) + 200.0)..(engine.redlineRpm - 50.0), "%.0f RPM") {
                    onChange(config.copy(engine = engine.copy(upshiftRpm = it)))
                }
                ParameterSlider("LIFT-OFF RPM FORCE", engine.liftOffRpmDecelerationPerSecond, 300.0..12_000.0, "%.0f RPM/s") {
                    onChange(config.copy(engine = engine.copy(liftOffRpmDecelerationPerSecond = it)))
                }
                ParameterSlider("BRAKE RPM FORCE", engine.brakeRpmDecelerationPerSecond, 2_500.0..18_000.0, "%.0f RPM/s") {
                    onChange(config.copy(engine = engine.copy(brakeRpmDecelerationPerSecond = it)))
                }
            }
        }
    }
}

@Composable
private fun ResponseTab(state: DriveSnapshot, config: TuningConfig, onChange: (TuningConfig) -> Unit) {
    val engine = config.engine
    val currentRpm = ((state.drivetrain.rpm - engine.idleRpm) / (engine.redlineRpm - engine.idleRpm))
        .coerceIn(0.0, 1.0)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("SIM PEDAL RESPONSE", "Drag points • pedal vs simulated drive request", Modifier.weight(1.05f)) {
            EditableCurveGraph(
                points = engine.throttleCurve,
                xLabel = { "${(it * 100).roundToInt()}" },
                yLabel = { "${(it * 100).roundToInt()}" },
                xMarkerLabel = { "${(it * 100).roundToInt()}%" },
                yMarkerLabel = { "${(it * 100).roundToInt()}%" },
                xAxisTitle = "PEDAL INPUT (%)",
                yAxisTitle = "SIM DRIVE REQUEST (%)",
                currentX = state.throttle,
                accent = TuneGreen,
                lockEndpointX = true,
                lockEndpointY = true,
                onPointsChange = { onChange(config.copy(engine = engine.copy(throttleCurve = it))) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PanelCard("TACH FORCE CURVE", "Drag points • independent from road speed", Modifier.weight(1.05f)) {
            EditableCurveGraph(
                points = engine.rpmProgressionCurve,
                xLabel = { "${(engine.idleRpm + it * (engine.redlineRpm - engine.idleRpm)).roundToInt()}" },
                yLabel = { "${(it * 100).roundToInt()}" },
                xMarkerLabel = { "${(engine.idleRpm + it * (engine.redlineRpm - engine.idleRpm)).roundToInt()} RPM" },
                yMarkerLabel = { "${(it * 100).roundToInt()}%" },
                xAxisTitle = "FAKE ENGINE SPEED (RPM)",
                yAxisTitle = "POSITIVE FORCE (%)",
                currentX = currentRpm,
                accent = TuneCyan,
                lockEndpointX = true,
                onPointsChange = { onChange(config.copy(engine = engine.copy(rpmProgressionCurve = it))) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PanelCard("PEDAL DYNAMICS", "Editable input, lift and brake timing", Modifier.weight(0.82f)) {
            ParameterSlider("THROTTLE ATTACK", engine.throttleAttackMs, 15.0..500.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(throttleAttackMs = it)))
            }
            ParameterSlider("THROTTLE RELEASE", engine.throttleReleaseMs, 20.0..800.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(throttleReleaseMs = it)))
            }
            ParameterSlider("BRAKE ATTACK", engine.brakeResponseMs, 15.0..500.0, "%.0f ms") {
                onChange(config.copy(engine = engine.copy(brakeResponseMs = it)))
            }
            ParameterSlider("SIM LIFT-OFF DECEL", engine.simulatorCoastRegenMps2, 0.0..4.00, "%.0f m/s²") {
                onChange(config.copy(engine = engine.copy(simulatorCoastRegenMps2 = it)))
            }
            Spacer(Modifier.height(12.dp))
            ResponsePreview(engine, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun AudioTab(config: TuningConfig, selectedCarId: String, onChange: (TuningConfig) -> Unit) {
    val audio = config.audio
    val profile = EngineSampleProfiles.find(selectedCarId)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard("SAMPLE OUTPUT", "The recovered bank logic is the only engine source", Modifier.weight(0.72f)) {
            AudioSlider("MASTER", audio.masterGain, 0.0..1.2) { onChange(config.copy(audio = audio.copy(masterGain = it))) }
            Spacer(Modifier.height(20.dp))
            Text("PROFILE", color = TuneMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(profile.displayName, color = TuneWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("NATIVE RPM DOMAIN", color = TuneMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("0–${profile.maximumRpm.roundToInt()} RPM · DIRECT 1:1", color = TuneCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("${profile.layers.size} continuous layers · bank-authored RPM and throttle automation", color = TuneWhite, fontSize = 12.sp, lineHeight = 18.sp)
        }
        PanelCard("RPM LAYER COVERAGE", "Recovered FMOD regions on the bank's native parameter axis", Modifier.weight(1.85f)) {
            SampleBankCoverageGraph(profile.id, config.engine.redlineRpm, Modifier.fillMaxSize())
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
    ParameterSlider(label, value * 100.0, range.start * 100.0..range.endInclusive * 100.0, "%.0f%%") {
        onChange(it / 100.0)
    }
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
    xMarkerLabel: (Double) -> String,
    yMarkerLabel: (Double) -> String,
    xAxisTitle: String,
    yAxisTitle: String,
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
    // Leave enough room for values such as "405.3 kgfm" without drawing into
    // the neighboring panel or beyond the display edge.
    val graphPaddingLeft = 124f
    val graphPaddingRight = 28f
    val graphPaddingTop = 40f
    val graphPaddingBottom = 72f

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

        val xTicks = axisTicksFromValues(
            values = points.map { it.x },
            positionOf = { it.toFloat() },
            labelOf = xLabel,
            minSpacing = 0.07f,
        )
        val yTicks = axisTicksFromValues(
            values = listOf(0.0) + points.map { it.y },
            positionOf = { (it / 1.15).toFloat() },
            labelOf = yLabel,
            minSpacing = 0.08f,
        )
        xTicks.forEach { tick ->
            val x = left + width * tick.position
            drawLine(TuneLine.copy(alpha = 0.70f), Offset(x, top), Offset(x, bottom), 1f)
        }
        yTicks.forEach { tick ->
            val y = bottom - height * tick.position
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
                textSize = 19f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            paint.textAlign = Paint.Align.LEFT
            canvas.nativeCanvas.drawText(yAxisTitle, left, 21f, paint)
            paint.textAlign = Paint.Align.CENTER
            xTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left + width * tick.position,
                    bottom + 30f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.RIGHT
            yTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left - 10f,
                    bottom - height * tick.position + 7f,
                    paint,
                )
            }
            paint.textSize = 12f
            val accentArgb = accent.toArgb()
            points.forEach { point ->
                val px = left + point.x.toFloat() * width
                val py = bottom - (point.y.toFloat() / 1.15f) * height
                paint.textAlign = Paint.Align.CENTER
                paint.color = accentArgb
                canvas.nativeCanvas.drawText(yMarkerLabel(point.y), px, py - 14f, paint)
                paint.color = GRAPH_AXIS_LABEL_COLOR
                canvas.nativeCanvas.drawText(
                    xMarkerLabel(point.x),
                    px,
                    markerLabelYBelow(py, bottom),
                    paint,
                )
            }
            paint.textSize = 19f
            paint.textAlign = Paint.Align.CENTER
            canvas.nativeCanvas.drawText(xAxisTitle, left + width / 2f, bottom + 58f, paint)
        }
    }
}

@Composable
private fun TorquePowerGraph(engine: EngineTuning, currentSpeedKmh: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 88f
        val right = size.width - 80f
        val top = 48f
        val bottom = size.height - 82f
        val width = right - left
        val height = bottom - top
        val peakWheelTorque = engine.frontPeakWheelTorqueNm + engine.rearPeakWheelTorqueNm
        val torqueScale = peakWheelTorque * 1.15
        val peakWheelKw = peakWheelPowerKw(engine)
        val powerScale = engine.peakPowerKw * 1.10
        val displayPowerKw = { wheelKw: Double ->
            engine.wheelPowerDisplayKw(wheelKw, peakWheelKw)
        }
        val landmarks = torquePowerLandmarks(engine)
        val xTicks = axisTicksFromValues(
            values = landmarks.map { it.normalizedSpeed },
            positionOf = { it.toFloat() },
            labelOf = { normalized -> "${(normalized * engine.topSpeedKmh).roundToInt()}" },
            minSpacing = 0.045f,
            mergeTolerance = 3.0 / engine.topSpeedKmh,
        )
        val torqueYTicks = axisTicksFromValues(
            values = landmarks.map { it.displayedTorqueNm },
            positionOf = { (it / torqueScale).toFloat().coerceIn(0f, 1f) },
            labelOf = { torqueNm ->
                engine.wheelTorqueDisplayKgfm(torqueNm).roundToInt().toString()
            },
            minSpacing = 0.07f,
            mergeTolerance = peakWheelTorque * 0.025,
        )
        val powerYTicks = axisTicksFromValues(
            values = landmarks.map { it.powerKw },
            positionOf = { (displayPowerKw(it) / powerScale).toFloat().coerceIn(0f, 1f) },
            labelOf = { powerKw ->
                kilowattsToHorsepower(displayPowerKw(powerKw)).roundToInt().toString()
            },
            minSpacing = 0.07f,
            mergeTolerance = engine.peakPowerKw * 0.025,
        )
        xTicks.forEach { tick ->
            val x = left + width * tick.position
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(x, top), Offset(x, bottom), 1f)
        }
        (torqueYTicks + powerYTicks)
            .map { it.position }
            .distinct()
            .sorted()
            .forEach { position ->
                val y = bottom - height * position
                drawLine(TuneLine.copy(alpha = 0.65f), Offset(left, y), Offset(right, y), 1f)
            }
        val torquePath = Path()
        val powerPath = Path()
        repeat(101) { index ->
            val x = index / 100.0
            val sample = sampleTorquePower(engine, x)
            val px = left + width * x.toFloat()
            val torqueY = bottom - height * (sample.displayedTorqueNm / torqueScale).toFloat()
            val powerY = bottom - height * (displayPowerKw(sample.powerKw) / powerScale).toFloat()
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
        landmarks.forEach { landmark ->
            val px = left + width * landmark.normalizedSpeed.toFloat()
            val torqueY = bottom - height * (landmark.displayedTorqueNm / torqueScale).toFloat()
            val powerY = bottom - height * (displayPowerKw(landmark.powerKw) / powerScale).toFloat()
            drawLine(
                TuneWhite.copy(alpha = 0.18f),
                Offset(px, top),
                Offset(px, bottom),
                1f,
            )
            drawCircle(TuneCyan.copy(alpha = 0.9f), 5f, Offset(px, torqueY))
            drawCircle(TuneAmber.copy(alpha = 0.9f), 5f, Offset(px, powerY))
        }
        val liveX = (currentSpeedKmh / engine.topSpeedKmh).coerceIn(0.0, 1.0).toFloat()
        drawLine(TuneWhite.copy(alpha = 0.45f), Offset(left + width * liveX, top), Offset(left + width * liveX, bottom), 2f)
        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textSize = 16f
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(53, 232, 242)
            canvas.nativeCanvas.drawText("≈ MOTOR TORQUE (kgfm)", left, 20f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = android.graphics.Color.rgb(255, 196, 86)
            canvas.nativeCanvas.drawText("≈ MOTOR POWER (HP)", right, 20f, paint)
            torqueYTicks.forEach { tick ->
                paint.textAlign = Paint.Align.RIGHT
                paint.color = android.graphics.Color.rgb(53, 232, 242)
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left - 10f,
                    bottom - height * tick.position + 6f,
                    paint,
                )
            }
            powerYTicks.forEach { tick ->
                paint.textAlign = Paint.Align.LEFT
                paint.color = android.graphics.Color.rgb(255, 196, 86)
                canvas.nativeCanvas.drawText(
                    tick.label,
                    right + 10f,
                    bottom - height * tick.position + 6f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f
            val axisLabelColor = GRAPH_AXIS_LABEL_COLOR
            xTicks.forEach { tick ->
                paint.color = axisLabelColor
                paint.textSize = 16f
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left + width * tick.position,
                    bottom + 27f,
                    paint,
                )
            }
            paint.textSize = 12f
            landmarks.forEach { landmark ->
                val px = left + width * landmark.normalizedSpeed.toFloat()
                val torqueY = bottom - height * (landmark.displayedTorqueNm / torqueScale).toFloat()
                val powerY = bottom - height * (displayPowerKw(landmark.powerKw) / powerScale).toFloat()
                val torqueLabel = engine.wheelTorqueDisplayKgfm(landmark.displayedTorqueNm).roundToInt().toString()
                val powerLabel = kilowattsToHorsepower(displayPowerKw(landmark.powerKw)).roundToInt().toString()
                val speedKmh = (landmark.normalizedSpeed * engine.topSpeedKmh).roundToInt()
                val markerBottomY = max(torqueY, powerY)

                paint.textAlign = Paint.Align.CENTER
                paint.color = android.graphics.Color.rgb(53, 232, 242)
                canvas.nativeCanvas.drawText(torqueLabel, px, torqueY - 10f, paint)
                paint.color = android.graphics.Color.rgb(255, 196, 86)
                canvas.nativeCanvas.drawText(powerLabel, px, powerY - 10f, paint)
                paint.color = axisLabelColor
                canvas.nativeCanvas.drawText(
                    "$speedKmh km/h",
                    px,
                    markerLabelYBelow(markerBottomY, bottom),
                    paint,
                )
            }
            paint.textSize = 16f
            paint.color = axisLabelColor
            canvas.nativeCanvas.drawText("ROAD SPEED (km/h)", left + width / 2f, bottom + 55f, paint)
        }
    }
}

private fun totalWheelTorque(engine: EngineTuning, normalizedSpeed: Double): Double =
    interpolateCurve(engine.frontWheelTorqueCurve, normalizedSpeed) * engine.frontPeakWheelTorqueNm +
        interpolateCurve(engine.rearWheelTorqueCurve, normalizedSpeed) * engine.rearPeakWheelTorqueNm

private data class TorquePowerSample(
    val normalizedSpeed: Double,
    val displayedTorqueNm: Double,
    val powerKw: Double,
)

private fun sampleTorquePower(engine: EngineTuning, normalizedSpeed: Double): TorquePowerSample {
    val rawTorque = totalWheelTorque(engine, normalizedSpeed)
    val wheelOmega = (normalizedSpeed * engine.topSpeedKmh / 3.6) / engine.wheelRadiusMeters
    val powerLimitedTorque = if (wheelOmega < 1.0) {
        rawTorque
    } else {
        engine.peakPowerKw * 1_000.0 * engine.drivetrainEfficiency / wheelOmega
    }
    val displayedTorque = min(rawTorque, powerLimitedTorque)
    val power = displayedTorque * wheelOmega / 1_000.0
    return TorquePowerSample(
        normalizedSpeed = normalizedSpeed,
        displayedTorqueNm = displayedTorque,
        powerKw = power,
    )
}

private fun buildTorquePowerSeries(engine: EngineTuning, steps: Int = 400): List<TorquePowerSample> {
    return (0..steps).map { index ->
        sampleTorquePower(engine, index / steps.toDouble())
    }
}

private fun torquePowerLandmarks(engine: EngineTuning): List<TorquePowerSample> {
    val steps = 400
    val series = buildTorquePowerSeries(engine, steps)
    val torqueValues = series.map { it.displayedTorqueNm }
    val powerValues = series.map { it.powerKw }
    val torqueRange = (torqueValues.maxOrNull() ?: 1.0) - (torqueValues.minOrNull() ?: 0.0)
    val powerRange = (powerValues.maxOrNull() ?: 1.0) - (powerValues.minOrNull() ?: 0.0)

    val indices = linkedSetOf(0, series.lastIndex)
    indices.addAll(significantInflectionIndices(torqueValues, torqueRange * 0.0015))
    indices.addAll(significantInflectionIndices(powerValues, powerRange * 0.0015))
    indices.addAll(extremaIndices(powerValues))

    var powerLimitOnsetIndex: Int? = null
    series.forEachIndexed { index, sample ->
        if (powerLimitOnsetIndex != null) {
            return@forEachIndexed
        }
        val rawTorque = totalWheelTorque(engine, sample.normalizedSpeed)
        if (sample.displayedTorqueNm + 1.0 < rawTorque) {
            powerLimitOnsetIndex = index
        }
    }
    if (powerLimitOnsetIndex != null) {
        indices.add(powerLimitOnsetIndex!!)
    }

    val curveBreakpoints = engine.frontWheelTorqueCurve.map { it.x } +
        engine.rearWheelTorqueCurve.map { it.x }
    curveBreakpoints.forEach { normalized ->
        val index = (normalized * steps).roundToInt().coerceIn(0, steps)
        indices.add(index)
    }

    return indices
        .sorted()
        .map { series[it] }
        .distinctBy { (it.normalizedSpeed * 1_000).roundToInt() }
}

private fun significantInflectionIndices(values: List<Double>, minCurvatureChange: Double): List<Int> {
    if (values.size < 5) {
        return emptyList()
    }
    val indices = mutableListOf<Int>()
    for (index in 2 until values.size - 2) {
        val curvatureBefore = values[index] - 2.0 * values[index - 1] + values[index - 2]
        val curvatureAfter = values[index + 1] - 2.0 * values[index] + values[index - 1]
        if (curvatureBefore * curvatureAfter < 0.0 &&
            abs(curvatureAfter - curvatureBefore) > minCurvatureChange
        ) {
            indices.add(index)
        }
    }
    return indices
}

private fun extremaIndices(values: List<Double>): List<Int> {
    if (values.size < 3) {
        return emptyList()
    }
    val indices = mutableListOf<Int>()
    for (index in 1 until values.lastIndex) {
        val localMaximum = values[index - 1] < values[index] && values[index] >= values[index + 1]
        val localMinimum = values[index - 1] > values[index] && values[index] <= values[index + 1]
        if (localMaximum || localMinimum) {
            indices.add(index)
        }
    }
    return indices
}

@Composable
private fun TorqueDistributionGraph(engine: EngineTuning, currentSpeedKmh: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 68f
        val right = size.width - 24f
        val top = 48f
        val bottom = size.height - 82f
        val width = right - left
        val height = bottom - top
        val distributionLandmarks = torqueDistributionLandmarks(engine)
        val xTicks = axisTicksFromValues(
            values = distributionLandmarks.map { it.normalizedSpeed },
            positionOf = { it.toFloat() },
            labelOf = { normalized -> "${(normalized * engine.topSpeedKmh).roundToInt()}" },
            minSpacing = 0.08f,
            mergeTolerance = 0.02,
        )
        val yTicks = axisTicksFromValues(
            values = distributionLandmarks.map { it.rearShare },
            positionOf = { it.toFloat() },
            labelOf = { share -> "${(share * 100).roundToInt()}%" },
            minSpacing = 0.10f,
            mergeTolerance = 0.03,
        )
        xTicks.forEach { tick ->
            val x = left + width * tick.position
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(x, top), Offset(x, bottom), 1f)
        }
        yTicks.forEach { tick ->
            val y = bottom - height * tick.position
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(left, y), Offset(right, y), 1f)
        }
        val frontPath = Path()
        val rearPath = Path()
        repeat(101) { index ->
            val x = index / 100.0
            val front = interpolateCurve(engine.frontWheelTorqueCurve, x) * engine.frontPeakWheelTorqueNm
            val rear = interpolateCurve(engine.rearWheelTorqueCurve, x) * engine.rearPeakWheelTorqueNm
            val rearShare = if (front + rear > 0.0) rear / (front + rear) else 0.0
            val px = left + width * x.toFloat()
            val frontY = bottom - height * (1.0 - rearShare).toFloat()
            val rearY = bottom - height * rearShare.toFloat()
            if (index == 0) {
                frontPath.moveTo(px, frontY)
                rearPath.moveTo(px, rearY)
            } else {
                frontPath.lineTo(px, frontY)
                rearPath.lineTo(px, rearY)
            }
        }
        drawPath(frontPath, TuneAmber, style = Stroke(4f, cap = StrokeCap.Round))
        drawPath(rearPath, TuneRed, style = Stroke(4f, cap = StrokeCap.Round))
        distributionLandmarks.forEach { landmark ->
            val px = left + width * landmark.normalizedSpeed.toFloat()
            val rearShare = landmark.rearShare
            val frontShare = 1.0 - rearShare
            val frontY = bottom - height * frontShare.toFloat()
            val rearY = bottom - height * rearShare.toFloat()
            drawCircle(TuneAmber.copy(alpha = 0.9f), 5f, Offset(px, frontY))
            drawCircle(TuneRed.copy(alpha = 0.9f), 5f, Offset(px, rearY))
        }
        val liveX = (currentSpeedKmh / engine.topSpeedKmh).coerceIn(0.0, 1.0)
        drawLine(
            TuneWhite.copy(alpha = 0.45f),
            Offset(left + width * liveX.toFloat(), top),
            Offset(left + width * liveX.toFloat(), bottom),
            2f,
        )
        val liveFront = interpolateCurve(engine.frontWheelTorqueCurve, liveX) * engine.frontPeakWheelTorqueNm
        val liveRear = interpolateCurve(engine.rearWheelTorqueCurve, liveX) * engine.rearPeakWheelTorqueNm
        val liveRearShare = liveRear / (liveFront + liveRear).coerceAtLeast(1.0)
        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textSize = 16f
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(140, 167, 181)
            canvas.nativeCanvas.drawText("TORQUE SHARE (%)", left, 20f, paint)
            paint.textAlign = Paint.Align.RIGHT
            yTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left - 10f,
                    bottom - height * tick.position + 6f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.CENTER
            xTicks.forEach { tick ->
                paint.color = GRAPH_AXIS_LABEL_COLOR
                paint.textSize = 16f
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left + width * tick.position,
                    bottom + 27f,
                    paint,
                )
            }
            paint.textSize = 12f
            distributionLandmarks.forEach { landmark ->
                val px = left + width * landmark.normalizedSpeed.toFloat()
                val rearShare = landmark.rearShare
                val frontShare = 1.0 - rearShare
                val frontY = bottom - height * frontShare.toFloat()
                val rearY = bottom - height * rearShare.toFloat()
                val speedKmh = (landmark.normalizedSpeed * engine.topSpeedKmh).roundToInt()
                val markerBottomY = max(frontY, rearY)

                paint.textAlign = Paint.Align.CENTER
                paint.color = android.graphics.Color.rgb(255, 196, 86)
                canvas.nativeCanvas.drawText("${(frontShare * 100).roundToInt()}%", px, frontY - 10f, paint)
                paint.color = android.graphics.Color.rgb(255, 70, 92)
                canvas.nativeCanvas.drawText("${(rearShare * 100).roundToInt()}%", px, rearY - 10f, paint)
                paint.color = GRAPH_AXIS_LABEL_COLOR
                canvas.nativeCanvas.drawText(
                    "$speedKmh km/h",
                    px,
                    markerLabelYBelow(markerBottomY, bottom),
                    paint,
                )
            }
            paint.textSize = 16f
            paint.color = GRAPH_AXIS_LABEL_COLOR
            canvas.nativeCanvas.drawText("ROAD SPEED (km/h)", left + width / 2f, bottom + 55f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(255, 196, 86)
            canvas.nativeCanvas.drawText("FRONT  ${((1.0 - liveRearShare) * 100).roundToInt()}%", left, top + 20f, paint)
            paint.color = android.graphics.Color.rgb(255, 70, 92)
            canvas.nativeCanvas.drawText("REAR  ${(liveRearShare * 100).roundToInt()}%", left + width * 0.62f, top + 20f, paint)
        }
    }
}

@Composable
private fun GearDropGraph(engine: EngineTuning, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 72f
        val right = size.width - 25f
        val top = 48f
        val bottom = size.height - 86f
        val width = right - left
        val height = bottom - top
        val count = engine.gearRatios.lastIndex.coerceAtLeast(1)
        val xTicks = (0 until count).map { index ->
            AxisTick(((index + 0.5f) / count), "${index + 1}")
        }
        val landingRpms = (0 until engine.gearRatios.lastIndex).map { index ->
            engine.idleRpm +
                (engine.upshiftRpm - engine.idleRpm) * engine.gearRatios[index + 1] / engine.gearRatios[index]
        }
        val yTicks = axisTicksFromValues(
            values = listOf(engine.idleRpm, engine.upshiftRpm) + landingRpms,
            positionOf = { (it / engine.maxRpm).toFloat().coerceIn(0f, 1f) },
            labelOf = { rpm -> rpm.roundToInt().toString() },
            minSpacing = 0.09f,
            mergeTolerance = engine.maxRpm * 0.04,
        )
        yTicks.forEach { tick ->
            val y = bottom - height * tick.position
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(left, y), Offset(right, y), 1f)
        }
        xTicks.forEach { tick ->
            val x = left + width * tick.position
            drawLine(TuneLine.copy(alpha = 0.65f), Offset(x, top), Offset(x, bottom), 1f)
        }
        for (index in 0 until engine.gearRatios.lastIndex) {
            val postShift = engine.idleRpm +
                (engine.upshiftRpm - engine.idleRpm) * engine.gearRatios[index + 1] / engine.gearRatios[index]
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
                paint.color = android.graphics.Color.rgb(53, 232, 242)
                canvas.nativeCanvas.drawText(postShift.roundToInt().toString(), x, y - 12f, paint)
                paint.color = GRAPH_AXIS_LABEL_COLOR
                canvas.nativeCanvas.drawText(
                    "${index + 1}→${index + 2}",
                    x,
                    markerLabelYBelow(y, bottom),
                    paint,
                )
            }
        }
        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textSize = 16f
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(140, 167, 181)
            canvas.nativeCanvas.drawText("LANDING / DOWNSHIFT RPM", left, 20f, paint)
            paint.textAlign = Paint.Align.RIGHT
            yTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left - 10f,
                    bottom - height * tick.position + 6f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.CENTER
            paint.color = GRAPH_AXIS_LABEL_COLOR
            xTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left + width * tick.position,
                    bottom + 27f,
                    paint,
                )
            }
            canvas.nativeCanvas.drawText("SHIFT EVENT", left + width / 2f, bottom + 62f, paint)
        }
    }
}

@Composable
private fun SampleBankCoverageGraph(profileId: String, redlineRpm: Double, modifier: Modifier = Modifier) {
    val profile = EngineSampleProfiles.find(profileId)
    Canvas(modifier) {
        val left = 90f
        val right = size.width - 26f
        val top = 48f
        val bottom = size.height - 72f
        val width = right - left
        val height = bottom - top
        val roles = SampleLayerRole.entries
        val laneHeight = height / roles.size

        for (rpm in 0..profile.maximumRpm.toInt() step 1_000) {
            val x = left + width * (rpm / profile.maximumRpm).toFloat()
            drawLine(TuneLine.copy(alpha = 0.75f), Offset(x, top), Offset(x, bottom), 1f)
        }
        roles.forEachIndexed { lane, role ->
            val laneTop = top + lane * laneHeight
            val color = when (role) {
                SampleLayerRole.IDLE -> TuneWhite
                SampleLayerRole.LOAD -> TuneGreen
                SampleLayerRole.COAST -> TuneCyan
                SampleLayerRole.TEXTURE -> TuneAmber
                SampleLayerRole.LIMITER -> TuneRed
            }
            profile.layers.filter { it.role == role }.forEach { layer ->
                val x = left + width * (layer.startRpm / profile.maximumRpm).toFloat()
                val endX = left + width * (layer.endRpm / profile.maximumRpm).toFloat()
                val y = laneTop + laneHeight * 0.20f
                val barHeight = laneHeight * 0.60f
            drawRoundRect(
                    color = color.copy(alpha = 0.50f),
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size((endX - x).coerceAtLeast(2f), barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f),
                )
                drawLine(color, Offset(x, y), Offset(x, y + barHeight), 2f)
            }
            drawLine(TuneLine, Offset(left, laneTop + laneHeight), Offset(right, laneTop + laneHeight), 1f)
        }

        val currentRedline = redlineRpm.coerceIn(
            profile.minimumRpm,
            profile.maximumRpm,
        )
        val redlineX = left + width * (currentRedline / profile.maximumRpm).toFloat()
        drawLine(TuneRed, Offset(redlineX, top), Offset(redlineX, bottom), 3f)

        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textSize = 15f
            paint.color = GRAPH_AXIS_LABEL_COLOR
            paint.textAlign = Paint.Align.RIGHT
            roles.forEachIndexed { lane, role ->
                canvas.nativeCanvas.drawText(
                    role.name,
                    left - 12f,
                    top + lane * laneHeight + laneHeight * 0.55f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.CENTER
            for (rpm in 0..profile.maximumRpm.toInt() step 1_000) {
                val x = left + width * (rpm / profile.maximumRpm).toFloat()
                canvas.nativeCanvas.drawText(rpm.toString(), x, bottom + 28f, paint)
            }
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(255, 70, 92)
            canvas.nativeCanvas.drawText("REDLINE ${currentRedline.roundToInt()}", redlineX - 122f, top - 15f, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.color = GRAPH_AXIS_LABEL_COLOR
            canvas.nativeCanvas.drawText("NATIVE BANK RPM — DIRECT 1:1", left + width / 2f, bottom + 58f, paint)
        }
    }
}

@Composable
private fun ResponsePreview(engine: EngineTuning, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 62f
        val right = size.width - 18f
        val top = 48f
        val bottom = size.height - 68f
        val width = right - left
        val height = bottom - top
        val responseTimesMs = listOf(0.0, engine.throttleAttackMs, engine.throttleReleaseMs, engine.brakeResponseMs)
        val xTicks = axisTicksFromValues(
            values = responseTimesMs,
            positionOf = { (it / 1_000.0).toFloat().coerceIn(0f, 1f) },
            labelOf = { timeMs -> "${timeMs.roundToInt()}" },
            minSpacing = 0.08f,
            mergeTolerance = 35.0,
        )
        val yTicks = axisTicksFromValues(
            values = listOf(0.0, 0.632, 1.0),
            positionOf = { it.toFloat() },
            labelOf = { response -> "${(response * 100).roundToInt()}%" },
            minSpacing = 0.12f,
            mergeTolerance = 0.04,
        )
        xTicks.forEach { tick ->
            val x = left + width * tick.position
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(x, top), Offset(x, bottom), 1f)
        }
        yTicks.forEach { tick ->
            val y = bottom - height * tick.position
            drawLine(TuneLine.copy(alpha = 0.7f), Offset(left, y), Offset(right, y), 1f)
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
        val responseCurves = listOf(
            Triple(engine.throttleAttackMs, android.graphics.Color.rgb(54, 227, 145), "ATTACK"),
            Triple(engine.throttleReleaseMs, android.graphics.Color.rgb(53, 232, 242), "RELEASE"),
            Triple(engine.brakeResponseMs, android.graphics.Color.rgb(255, 70, 92), "BRAKE"),
        )
        responseTimesMs.forEach { timeMs ->
            val normalizedTime = (timeMs / 1_000.0).toFloat().coerceIn(0f, 1f)
            val px = left + width * normalizedTime
            var markerBottomY = bottom
            val labelOffsets = listOf(-14f, 0f, 14f)
            responseCurves.forEachIndexed { curveIndex, (tauMs, color, _) ->
                val response = 1.0 - kotlin.math.exp(-(timeMs / 1_000.0) / (tauMs / 1_000.0))
                val py = bottom - height * response.toFloat().coerceIn(0f, 1f)
                markerBottomY = max(markerBottomY, py)
                drawCircle(Color(color).copy(alpha = 0.9f), 4f, Offset(px, py))
                drawIntoCanvas { canvas ->
                    val paint = graphPaint()
                    paint.textSize = 12f
                    paint.textAlign = Paint.Align.CENTER
                    paint.color = color
                    canvas.nativeCanvas.drawText(
                        "${(response * 100).roundToInt()}%",
                        px + labelOffsets[curveIndex],
                        py - 10f,
                        paint,
                    )
                }
            }
            drawIntoCanvas { canvas ->
                val paint = graphPaint()
                paint.textSize = 12f
                paint.textAlign = Paint.Align.CENTER
                paint.color = GRAPH_AXIS_LABEL_COLOR
                canvas.nativeCanvas.drawText(
                    "${timeMs.roundToInt()} ms",
                    px,
                    markerLabelYBelow(markerBottomY, bottom),
                    paint,
                )
            }
        }
        drawIntoCanvas { canvas ->
            val paint = graphPaint()
            paint.textSize = 14f
            paint.textAlign = Paint.Align.LEFT
            paint.color = android.graphics.Color.rgb(140, 167, 181)
            canvas.nativeCanvas.drawText("RESPONSE (%)", left, 16f, paint)
            val legend = listOf(
                "ATTACK" to android.graphics.Color.rgb(54, 227, 145),
                "RELEASE" to android.graphics.Color.rgb(53, 232, 242),
                "BRAKE" to android.graphics.Color.rgb(255, 70, 92),
            )
            legend.forEachIndexed { index, (label, color) ->
                paint.color = color
                canvas.nativeCanvas.drawText(label, left + index * (width / 3f), 36f, paint)
            }
            paint.textAlign = Paint.Align.RIGHT
            paint.color = android.graphics.Color.rgb(140, 167, 181)
            yTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left - 10f,
                    bottom - height * tick.position + 5f,
                    paint,
                )
            }
            paint.textAlign = Paint.Align.CENTER
            paint.color = GRAPH_AXIS_LABEL_COLOR
            xTicks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    tick.label,
                    left + width * tick.position,
                    bottom + 24f,
                    paint,
                )
            }
            canvas.nativeCanvas.drawText("TIME AFTER PEDAL CHANGE (ms)", left + width / 2f, bottom + 48f, paint)
        }
    }
}

private fun graphPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 21f
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
}

private val GRAPH_AXIS_LABEL_COLOR = 0xFF8CA7B5.toInt()

private fun markerLabelYBelow(markerBottomY: Float, plotBottom: Float): Float {
    val preferred = markerBottomY + 16f
    if (preferred <= plotBottom - 8f) {
        return preferred
    }
    return plotBottom + 14f
}

private data class AxisTick(val position: Float, val label: String)

private fun axisTicksFromValues(
    values: List<Double>,
    positionOf: (Double) -> Float,
    labelOf: (Double) -> String,
    minSpacing: Float,
    mergeTolerance: Double = 0.01,
): List<AxisTick> {
    val distinctValues = mergeCloseValues(values, mergeTolerance)
    val ticks = distinctValues.map { value ->
        AxisTick(positionOf(value), labelOf(value))
    }
    return filterAxisTicks(ticks, minSpacing)
}

private fun mergeCloseValues(values: List<Double>, tolerance: Double): List<Double> {
    if (values.isEmpty()) {
        return emptyList()
    }
    val sorted = values.sorted()
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { value ->
        if (abs(value - merged.last()) > tolerance) {
            merged.add(value)
        }
    }
    return merged
}

private fun filterAxisTicks(ticks: List<AxisTick>, minSpacing: Float): List<AxisTick> {
    if (ticks.isEmpty()) {
        return emptyList()
    }
    val sorted = ticks.sortedBy { it.position }
    val kept = mutableListOf(sorted.first())
    sorted.drop(1).forEach { tick ->
        if (tick.position - kept.last().position >= minSpacing) {
            kept.add(tick)
        }
    }
    return kept
}

private data class DistributionLandmark(
    val normalizedSpeed: Double,
    val rearShare: Double,
)

private fun rearShareAt(engine: EngineTuning, normalizedSpeed: Double): Double {
    val front = interpolateCurve(engine.frontWheelTorqueCurve, normalizedSpeed) * engine.frontPeakWheelTorqueNm
    val rear = interpolateCurve(engine.rearWheelTorqueCurve, normalizedSpeed) * engine.rearPeakWheelTorqueNm
    val total = front + rear
    if (total <= 0.0) {
        return 0.0
    }
    return rear / total
}

private fun torqueDistributionLandmarks(engine: EngineTuning): List<DistributionLandmark> {
    val normalizedSpeeds = linkedSetOf(0.0, 1.0)
    normalizedSpeeds.addAll(engine.frontWheelTorqueCurve.map { it.x })
    normalizedSpeeds.addAll(engine.rearWheelTorqueCurve.map { it.x })

    var crossover: Double? = null
    var previousShare = rearShareAt(engine, 0.0)
    repeat(101) { index ->
        if (crossover != null) {
            return@repeat
        }
        val normalized = index / 100.0
        val share = rearShareAt(engine, normalized)
        if ((previousShare - 0.5) * (share - 0.5) < 0.0) {
            crossover = normalized
        }
        previousShare = share
    }
    if (crossover != null) {
        normalizedSpeeds.add(crossover!!)
    }

    return normalizedSpeeds
        .sorted()
        .map { normalized -> DistributionLandmark(normalized, rearShareAt(engine, normalized)) }
}

private fun EngineTuning.wheelTorqueDisplayKgfm(wheelNewtonMeters: Double): Double =
    wheelNewtonMetersToMotorEquivalentKgfm(wheelNewtonMeters, motorReductionRatio)

private fun EngineTuning.wheelTorqueFromDisplayKgfm(displayKgfm: Double): Double =
    motorEquivalentKgfmToWheelNewtonMeters(displayKgfm, motorReductionRatio)

private fun EngineTuning.wheelPowerDisplayKw(wheelKilowatts: Double, peakWheelKw: Double): Double =
    wheelKilowattsToMotorEquivalentDisplayKw(wheelKilowatts, peakPowerKw, peakWheelKw)
