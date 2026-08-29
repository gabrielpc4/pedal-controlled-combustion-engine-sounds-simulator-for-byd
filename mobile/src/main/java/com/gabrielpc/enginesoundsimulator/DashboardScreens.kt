package com.gabrielpc.enginesoundsimulator

import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import java.util.Locale
import kotlin.math.roundToInt

enum class DashboardMainScreen(val title: String, val subtitle: String) {
    CLASSIC("CLASSIC", "CIRCULAR TACH"),
    MIXER("MIXER", "HUD + LAYERS"),
}

@Composable
internal fun DashboardMixerLauncherButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Tune,
        contentDescription = "Mixer",
        tint = Cyan,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(10.dp)
            .size(44.dp),
    )
}

internal const val MIXER_SCREEN_HORIZONTAL_PADDING = 20

@Composable
internal fun MixerDashboardScreen(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onSelectCar: (String) -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    onLayerMuted: (String, Boolean) -> Unit,
    onLayerSolo: (String, Boolean) -> Unit,
    onLayerVolume: (String, Double) -> Unit,
    coastLayerMixEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val groupedTracks = remember(state.layerMixTracks) {
        groupMixerTracks(state.layerMixTracks)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MIXER_SCREEN_HORIZONTAL_PADDING.dp, vertical = 4.dp),
    ) {
        MixerHeaderRow(
            drivetrain = state.drivetrain,
            transmissionPosition = state.transmissionPosition,
            maxRpm = state.tuning.engine.maxRpm,
            redlineRpm = state.tuning.engine.redlineRpm,
            selectedCarId = state.selectedCarId,
            selectedCarName = state.selectedCarName,
            selectedCarPreviewAsset = state.selectedCarPreviewAsset,
            carMasterVolume = state.carMasterVolume,
            onSelectCar = onSelectCar,
            onCarMasterVolumeChange = onCarMasterVolumeChange,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MixerTrackColumn(
                tracks = groupedTracks.coast,
                coastLayerMixEnabled = coastLayerMixEnabled,
                onLayerMuted = onLayerMuted,
                onLayerSolo = onLayerSolo,
                onLayerVolume = onLayerVolume,
                modifier = Modifier.weight(1f),
            )
            MixerTrackColumn(
                tracks = groupedTracks.middle,
                coastLayerMixEnabled = coastLayerMixEnabled,
                onLayerMuted = onLayerMuted,
                onLayerSolo = onLayerSolo,
                onLayerVolume = onLayerVolume,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                MixerTrackColumn(
                    tracks = groupedTracks.texture,
                    coastLayerMixEnabled = coastLayerMixEnabled,
                    onLayerMuted = onLayerMuted,
                    onLayerSolo = onLayerSolo,
                    onLayerVolume = onLayerVolume,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = MIXER_PEDALS_OVERLAY_HEIGHT),
                )
                MixerDriveControls(
                    state = state,
                    onThrottle = onThrottle,
                    onBrake = onBrake,
                    onTransmissionChange = onTransmissionChange,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

private val MIXER_PEDALS_OVERLAY_HEIGHT = 132.dp

private data class GroupedMixerTracks(
    val coast: List<LayerMixTrackState>,
    val middle: List<LayerMixTrackState>,
    val texture: List<LayerMixTrackState>,
)

private fun groupMixerTracks(tracks: List<LayerMixTrackState>): GroupedMixerTracks {
    val idle = tracks.filter { it.sortGroup == 0 }
    val coast = tracks.filter { it.sortGroup == 1 }
    val texture = tracks.filter { it.sortGroup == 3 }
    val middle = tracks.filter { track ->
        track.sortGroup != 0 && track.sortGroup != 1 && track.sortGroup != 3
    }
    return GroupedMixerTracks(
        coast = idle + coast,
        middle = middle,
        texture = texture,
    )
}

@Composable
private fun MixerTrackColumn(
    tracks: List<LayerMixTrackState>,
    coastLayerMixEnabled: Boolean,
    onLayerMuted: (String, Boolean) -> Unit,
    onLayerSolo: (String, Boolean) -> Unit,
    onLayerVolume: (String, Double) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            LayerMixTrackControl(
                track = track,
                coastLayerMixEnabled = coastLayerMixEnabled,
                onMuted = { onLayerMuted(track.id, it) },
                onSolo = { onLayerSolo(track.id, it) },
                onVolume = { onLayerVolume(track.id, it) },
            )
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
    selectedCarPreviewAsset: String,
    carMasterVolume: Double,
    onSelectCar: (String) -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
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
            selectedCarPreviewAsset = selectedCarPreviewAsset,
            carMasterVolume = carMasterVolume,
            onSelectCar = onSelectCar,
            onCarMasterVolumeChange = onCarMasterVolumeChange,
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
    val shakeIntensity = redlineShakeIntensity(
        rpm = drivetrain.rpm,
        redlineRpm = redlineRpm,
        maxRpm = maxRpm,
        limiterActive = drivetrain.limiterActive,
    )
    val redlineShake = rememberRedlineShakeMotion(shakeIntensity)
    val speedKmh = drivetrain.rawSpeedKmh.roundToInt().coerceAtLeast(0)
    val gearLabel = if (transmissionPosition == TransmissionPosition.DRIVE) {
        drivetrain.gear.toString()
    } else {
        transmissionPosition.displayName
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = speedKmh.toString(),
                    color = White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Km/h",
                    color = Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(end = 10.dp),
            ) {
                Text(
                    "GEAR",
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = gearLabel,
                    color = Cyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
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
                val shakeOffset = redlineShake.interiorTranslation

                clipRect(0f, 0f, width, height) {
                    translate(shakeOffset.x, shakeOffset.y) {
                        drawRect(
                            color = Red.copy(alpha = 0.18f),
                            topLeft = Offset(redlineX, 0f),
                            size = androidx.compose.ui.geometry.Size(width - redlineX, height),
                        )
                        drawRect(
                            brush = Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.35f), Amber, Red)),
                            size = androidx.compose.ui.geometry.Size(width * rpmFraction, height),
                        )
                    }
                }

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
    selectedCarPreviewAsset: String,
    carMasterVolume: Double,
    onSelectCar: (String) -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBright)
                .border(1.dp, Line, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarPreviewThumbnail(
                previewAsset = selectedCarPreviewAsset,
                contentDescription = selectedCarName,
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable { expanded = true },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true },
                ) {
                    Text("SIMULATED CAR", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        text = selectedCarName,
                        color = White,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Volume:",
                        color = Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.4.sp,
                    )
                    Slider(
                        value = carMasterVolume.toFloat(),
                        onValueChange = { onCarMasterVolumeChange(it.toDouble()) },
                        valueRange = 0f..1.2f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = Line.copy(alpha = 0.85f),
                        ),
                    )
                    Text(
                        text = "${(carMasterVolume * 100.0).roundToInt()}%",
                        color = Amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EngineSampleProfiles.all.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CarPreviewThumbnail(
                                previewAsset = profile.previewAssetName,
                                contentDescription = profile.displayName,
                                modifier = Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Text(
                                profile.displayName,
                                fontWeight = if (profile.id == selectedCarId) FontWeight.Black else FontWeight.Normal,
                            )
                        }
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
private fun CarPreviewThumbnail(
    previewAsset: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview = remember(previewAsset) {
        runCatching {
            context.assets.open(previewAsset).use { input ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                LoadedCarPreview(
                    image = bitmap.asImageBitmap(),
                    aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat(),
                )
            }
        }.getOrNull()
    }

    val aspectRatio = preview?.aspectRatio ?: (16f / 9f)

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.apex_v10_car),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class LoadedCarPreview(
    val image: ImageBitmap,
    val aspectRatio: Float,
)

@Composable
private fun LayerMixTrackControl(
    track: LayerMixTrackState,
    coastLayerMixEnabled: Boolean,
    onMuted: (Boolean) -> Unit,
    onSolo: (Boolean) -> Unit,
    onVolume: (Double) -> Unit,
) {
    val level = track.outputLevel.toFloat().coerceIn(0f, 1f)
    val fillColor = outputMeterFillColor(level)
    val showTrimSlider = coastLayerMixEnabled && track.showVolumeSlider
    val meterLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel.copy(alpha = 0.88f))
            .border(1.dp, Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = track.displayName,
                color = White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF061018))
                    .border(1.dp, Line.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            ) {
                if (level > 0.002f) {
                    drawRect(
                        color = fillColor,
                        size = androidx.compose.ui.geometry.Size(
                            width = size.width * level,
                            height = size.height,
                        ),
                        alpha = 0.88f,
                    )
                }
                meterLabelPaint.textSize = size.height * 0.55f
                meterLabelPaint.color = fillColor.toArgb()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        "${(level * 100f).roundToInt()}%",
                        size.width - 8f,
                        size.height * 0.70f,
                        meterLabelPaint,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MixToggleChip(
                    label = "M",
                    checked = track.muted,
                    accent = Red,
                    onCheckedChange = onMuted,
                )
                MixToggleChip(
                    label = "S",
                    checked = track.solo,
                    accent = Amber,
                    onCheckedChange = onSolo,
                )
            }
        }
        if (showTrimSlider) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "GAIN",
                    color = Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.width(34.dp),
                )
                Slider(
                    value = track.userVolume.toFloat().coerceIn(
                        LayerMixControl.MIN_GAIN_MULTIPLIER.toFloat(),
                        LayerMixControl.MAX_GAIN_MULTIPLIER.toFloat(),
                    ),
                    onValueChange = { onVolume(it.toDouble()) },
                    valueRange = LayerMixControl.MIN_GAIN_MULTIPLIER.toFloat()..LayerMixControl.MAX_GAIN_MULTIPLIER.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan,
                        activeTrackColor = Cyan,
                        inactiveTrackColor = Line,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                )
                Text(
                    String.format(Locale.US, "%.1fx", track.userVolume),
                    color = Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

@Composable
private fun MixToggleChip(
    label: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Text(
        text = label,
        color = if (checked) accent else Muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .width(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) accent.copy(alpha = 0.18f) else Color(0xFF061018))
            .border(1.dp, if (checked) accent.copy(alpha = 0.85f) else Line.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 3.dp),
        textAlign = TextAlign.Center,
    )
}

/** Green → cyan → amber → red as the live output meter fills. */
private fun outputMeterFillColor(level: Float): Color {
    return when {
        level <= 0.01f -> Muted.copy(alpha = 0.35f)
        level < 0.30f -> blendColors(Green.copy(alpha = 0.65f), Cyan, level / 0.30f)
        level < 0.60f -> blendColors(Cyan, Amber, (level - 0.30f) / 0.30f)
        level < 0.85f -> blendColors(Amber, Color(0xFFFF7040), (level - 0.60f) / 0.25f)
        else -> blendColors(Color(0xFFFF7040), Red, (level - 0.85f) / 0.15f)
    }
}

private fun blendColors(start: Color, end: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t,
    )
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
