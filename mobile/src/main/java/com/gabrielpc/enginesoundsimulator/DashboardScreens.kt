package com.gabrielpc.enginesoundsimulator

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class DashboardMainScreen(val title: String, val subtitle: String) {
    CLASSIC("CLASSIC", "CIRCULAR TACH"),
    MIXER("MIXER", "HUD + LAYERS"),
    GRID("GRID", "DISPLAY BOUNDS"),
}

@Composable
internal fun DashboardScreenSwitcher(
    selected: DashboardMainScreen,
    onSelect: (DashboardMainScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DashboardMainScreen.entries.forEach { screen ->
            val active = screen == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) PanelBright else Panel)
                    .border(1.dp, if (active) Cyan else Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .clickable { onSelect(screen) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(screen.title, color = if (active) Cyan else White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text(screen.subtitle, color = Muted, fontSize = 8.sp, letterSpacing = 0.6.sp)
            }
        }
    }
}

@Composable
internal fun MixerDashboardScreen(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onSelectCar: (String) -> Unit,
    onLayerVolume: (String, Double) -> Unit,
    onLayerMuted: (String, Boolean) -> Unit,
    onLayerSolo: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        MixerHeaderRow(
            drivetrain = state.drivetrain,
            transmissionPosition = state.transmissionPosition,
            maxRpm = state.tuning.engine.maxRpm,
            redlineRpm = state.tuning.engine.redlineRpm,
            selectedCarId = state.selectedCarId,
            selectedCarName = state.selectedCarName,
            onSelectCar = onSelectCar,
        )
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 250.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.layerMixTracks, key = { it.id }) { track ->
                    LayerMixTrackControl(
                        track = track,
                        onVolume = { onLayerVolume(track.id, it) },
                        onMuted = { onLayerMuted(track.id, it) },
                        onSolo = { onLayerSolo(track.id, it) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PedalControl(
                        label = "BRAKE",
                        value = state.brake,
                        accent = Red,
                        width = 86.dp,
                        height = 140.dp,
                        onValue = onBrake,
                    )
                    PedalControl(
                        label = "THROTTLE",
                        value = state.throttle,
                        accent = Green,
                        width = 78.dp,
                        height = 178.dp,
                        onValue = onThrottle,
                    )
                    TransmissionShifter(
                        position = state.transmissionPosition,
                        onPositionChange = onTransmissionChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun MixerHeaderRow(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    selectedCarId: String,
    selectedCarName: String,
    onSelectCar: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, Line.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarTachometerHud(
            drivetrain = drivetrain,
            transmissionPosition = transmissionPosition,
            maxRpm = maxRpm,
            redlineRpm = redlineRpm,
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
        )
        CarDropdownSelector(
            selectedCarId = selectedCarId,
            selectedCarName = selectedCarName,
            onSelectCar = onSelectCar,
            modifier = Modifier.weight(0.42f).fillMaxHeight(),
        )
    }
}

@Composable
private fun BarTachometerHud(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    modifier: Modifier = Modifier,
) {
    val rpmFraction = (drivetrain.rpm / maxRpm.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
    val redlineFraction = (redlineRpm / maxRpm.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = drivetrain.rpm.roundToInt().toString(),
                color = if (drivetrain.limiterActive) Red else White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            Column {
                Text("RPM", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    text = if (transmissionPosition == TransmissionPosition.DRIVE) "G${drivetrain.gear}" else transmissionPosition.displayName,
                    color = Cyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Column {
                Text("KM/H", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    text = drivetrain.rawSpeedKmh.roundToInt().toString(),
                    color = Cyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF061018))
                .border(1.dp, Line, RoundedCornerShape(4.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val redlineX = width * redlineFraction
                drawRect(
                    color = Red.copy(alpha = 0.18f),
                    topLeft = Offset(redlineX, 0f),
                    size = androidx.compose.ui.geometry.Size(width - redlineX, height),
                )
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.35f), Amber, Red)),
                    size = androidx.compose.ui.geometry.Size(width * rpmFraction, height),
                )
                drawLine(
                    color = Red,
                    start = Offset(redlineX, 0f),
                    end = Offset(redlineX, height),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

@Composable
private fun CarDropdownSelector(
    selectedCarId: String,
    selectedCarName: String,
    onSelectCar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBright)
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("ENGINE PROFILE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                text = selectedCarName,
                color = White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Tap to change car", color = CyanSoft, fontSize = 10.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EngineSampleProfiles.all.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Text(
                            profile.displayName,
                            fontWeight = if (profile.id == selectedCarId) FontWeight.Black else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectCar(profile.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun LayerMixTrackControl(
    track: LayerMixTrackState,
    onVolume: (Double) -> Unit,
    onMuted: (Boolean) -> Unit,
    onSolo: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel.copy(alpha = 0.88f))
            .border(1.dp, Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = track.displayName,
            color = White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF061018))
                .border(1.dp, Line.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(track.userVolume.toFloat().coerceIn(0f, 1f))
                    .background(Color.White.copy(alpha = 0.06f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(track.outputLevel.toFloat().coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                if (track.isEffect) Amber.copy(alpha = 0.55f) else Cyan.copy(alpha = 0.55f),
                                if (track.isEffect) Amber else Cyan,
                            ),
                        ),
                    ),
            )
        }
        Slider(
            value = track.userVolume.toFloat(),
            onValueChange = { onVolume(it.toDouble()) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Cyan,
                activeTrackColor = Cyan.copy(alpha = 0.65f),
                inactiveTrackColor = Line,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Checkbox(
                    checked = track.muted,
                    onCheckedChange = onMuted,
                    colors = CheckboxDefaults.colors(checkedColor = Red, uncheckedColor = Muted),
                )
                Text("Mute", color = Muted, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Checkbox(
                    checked = track.solo,
                    onCheckedChange = onSolo,
                    colors = CheckboxDefaults.colors(checkedColor = Amber, uncheckedColor = Muted),
                )
                Text("Solo", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun ResolutionProbeScreen(modifier: Modifier = Modifier) {
    var gridStepPx by remember { mutableIntStateOf(100) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val canvasWidthPx = with(density) { maxWidth.roundToPx() }
        val canvasHeightPx = with(density) { maxHeight.roundToPx() }
        val canvasWidthDp = maxWidth
        val canvasHeightDp = maxHeight
        val verticalCells = ceil(canvasWidthPx / gridStepPx.toFloat()).toInt()
        val horizontalCells = ceil(canvasHeightPx / gridStepPx.toFloat()).toInt()
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("DISPLAY BOUNDS PROBE", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(
                        "${canvasWidthPx} × ${canvasHeightPx} px  •  ${canvasWidthDp.value.roundToInt()} × ${canvasHeightDp.value.roundToInt()} dp",
                        color = White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Grid cells (full screen): ${verticalCells} × ${horizontalCells} @ ${gridStepPx}px",
                        color = Muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Nominal head unit: 1920 × 990 — count visible grid cells to find the real drawable area.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200).forEach { step ->
                        val selected = gridStepPx == step
                        Text(
                            text = "${step}px",
                            color = if (selected) Cyan else Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) PanelBright else Panel)
                                .border(1.dp, if (selected) Cyan else Line, RoundedCornerShape(8.dp))
                                .clickable { gridStepPx = step }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF020608))
                    .border(2.dp, Red, RoundedCornerShape(12.dp)),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = gridStepPx.toFloat()
                    val verticalLines = ceil(size.width / step).toInt()
                    val horizontalLines = ceil(size.height / step).toInt()
                    for (index in 0..verticalLines) {
                        val x = index * step
                        drawLine(
                            color = if (index % 5 == 0) Cyan.copy(alpha = 0.55f) else Line.copy(alpha = 0.35f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = if (index % 5 == 0) 1.5f else 1f,
                        )
                    }
                    for (index in 0..horizontalLines) {
                        val y = index * step
                        drawLine(
                            color = if (index % 5 == 0) Cyan.copy(alpha = 0.55f) else Line.copy(alpha = 0.35f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (index % 5 == 0) 1.5f else 1f,
                        )
                    }
                    drawLine(Color.Red, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2f)
                    drawLine(Color.Red, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
                    drawRect(Color.Red.copy(alpha = 0.85f), topLeft = Offset.Zero, size = size, style = Stroke(4f))
                    drawIntoCanvas { canvas ->
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(136, 162, 178)
                            textSize = 28f
                        }
                        canvas.nativeCanvas.drawText("0,0", 8f, 28f, paint)
                        canvas.nativeCanvas.drawText(
                            "${size.width.toInt()},${size.height.toInt()}",
                            size.width - 180f,
                            size.height - 12f,
                            paint,
                        )
                    }
                }
            }
        }
    }
}

private val Night = Color(0xFF060606)
private val Panel = Color(0xFF0B1925)
private val PanelBright = Color(0xFF112837)
private val Line = Color(0xFF1A3C4A)
private val Cyan = Color(0xFF35E8F2)
private val CyanSoft = Color(0xFF5FBAC7)
private val Green = Color(0xFF38E58C)
private val Red = Color(0xFFFF394F)
private val Amber = Color(0xFFFFC456)
private val White = Color(0xFFF5FAFD)
private val Muted = Color(0xFF88A2B2)
