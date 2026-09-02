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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodBankResolver
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.FmodEventSection
import com.gabrielpc.enginesoundsimulator.audio.FmodSourceState
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
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    onHostGains: (Float, Float) -> Unit,
    onEventMute: (String, Boolean) -> Unit,
    onEventSolo: (String, Boolean) -> Unit,
    soundPerspective: EngineSoundPerspective,
    onSoundPerspectiveChange: (EngineSoundPerspective) -> Unit,
    modifier: Modifier = Modifier,
) {
    var knownSources by remember(soundPerspective, state.selectedCarId) { mutableStateOf(emptyMap<String, FmodSourceState>()) }
    var previousActive by remember(soundPerspective, state.selectedCarId) { mutableStateOf(emptySet<String>()) }
    var initialized by remember(soundPerspective, state.selectedCarId) { mutableStateOf(false) }
    var highlightedIds by remember(soundPerspective, state.selectedCarId) { mutableStateOf(emptySet<String>()) }
    val activeIds = state.fmodSources.filter { it.isActive }.mapTo(mutableSetOf(), FmodSourceState::id)
    val enteredIds = activeIds - previousActive

    LaunchedEffect(state.fmodSources) {
        knownSources = knownSources + state.fmodSources.associateBy(FmodSourceState::id)
        val shouldHighlight = initialized
        previousActive = activeIds
        initialized = true
        if (shouldHighlight && enteredIds.isNotEmpty()) {
            val highlightable = enteredIds.filter { id ->
                state.fmodSources.firstOrNull { it.id == id }?.let { it.eventName != "engine_int" && it.eventName != "engine_ext" } == true
            }.toSet()
            highlightedIds = highlightedIds + highlightable
        }
    }

    LaunchedEffect(highlightedIds) {
        if (highlightedIds.isNotEmpty()) {
            delay(1000)
            highlightedIds = emptySet()
        }
    }

    val sections = remember(knownSources, state.fmodSources) {
        knownSources.values
            .groupBy(FmodSourceState::section)
            .toSortedMap(compareBy(FmodEventSection::order))
            .mapValues { (_, sources) ->
                sources.sortedWith(
                    compareByDescending<FmodSourceState> { it.isActive }
                        .thenByDescending { it.audibility }
                        .thenBy(FmodSourceState::eventPath)
                        .thenBy(FmodSourceState::soundName),
                )
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MIXER_SCREEN_HORIZONTAL_PADDING.dp, vertical = 4.dp),
    ) {
        MixerHeaderRow(
            drivetrain = state.drivetrain,
            transmissionPosition = state.transmissionPosition,
            maxRpm = state.drivetrain.tachometerMaximumRpm,
            redlineRpm = state.drivetrain.redlineRpm,
            selectedCarId = state.selectedCarId,
            selectedCarName = state.selectedCarName,
            selectedCarPreviewAsset = state.selectedCarPreviewAsset,
            onSelectCar = onSelectCar,
        )
        Spacer(Modifier.height(6.dp))
        MixerPerspectiveSelector(
            perspective = soundPerspective,
            onPerspectiveSelected = onSoundPerspectiveChange,
        )
        var engineGain by remember { mutableStateOf(1.0f) }
        var effectsGain by remember { mutableStateOf(2.0f) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ENGINE ${String.format(Locale.US, "%.1fx", engineGain)}", color = CyanSoft, fontSize = 11.sp)
            Slider(engineGain, { engineGain = it; onHostGains(it, effectsGain) }, valueRange = 0f..3f, modifier = Modifier.weight(1f))
            Text("EFFECTS ${String.format(Locale.US, "%.1fx", effectsGain)}", color = CyanSoft, fontSize = 11.sp)
            Slider(effectsGain, { effectsGain = it; onHostGains(engineGain, it) }, valueRange = 0f..4f, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val columnCount = (maxWidth.value / 390f).toInt().coerceIn(1, 4)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = MIXER_PEDALS_OVERLAY_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { (section, sources) ->
                    item(key = "section-${section.name}") {
                        Text(
                            text = section.displayName,
                            color = CyanSoft,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp),
                        )
                    }
                    items(sources.chunked(columnCount), key = { row -> "${state.selectedCarId}-${soundPerspective.name}-" + row.joinToString { it.id } }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { source ->
                                FmodSourceMeter(
                                    source = source,
                                    highlight = source.id in highlightedIds,
                                    onMute = onEventMute,
                                    onSolo = onEventSolo,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columnCount - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            MixerDriveControls(
                state = state,
                onThrottle = onThrottle,
                onBrake = onBrake,
                onTransmissionChange = onTransmissionChange,
                onManualUpshift = onManualUpshift,
                onManualDownshift = onManualDownshift,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun MixerPerspectiveSelector(
    perspective: EngineSoundPerspective,
    onPerspectiveSelected: (EngineSoundPerspective) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LISTENING",
            color = Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.width(10.dp))
        EngineSoundPerspective.entries.forEach { option ->
            val active = option == perspective
            Text(
                text = option.displayName,
                color = if (active) Cyan else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) Cyan.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onPerspectiveSelected(option) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private val MIXER_PEDALS_OVERLAY_HEIGHT = 132.dp

@Composable
private fun MixerHeaderRow(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    selectedCarId: String,
    selectedCarName: String,
    selectedCarPreviewAsset: String,
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
            selectedCarPreviewAsset = selectedCarPreviewAsset,
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
    val shakeIntensity = redlineShakeIntensity(
        rpm = drivetrain.rpm,
        redlineRpm = redlineRpm,
        maxRpm = maxRpm,
        limiterActive = drivetrain.limiterActive,
    )
    val redlineShake = rememberRedlineShakeMotion(shakeIntensity)
    val rpm = drivetrain.rpm.toInt().coerceAtLeast(0)
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
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = rpm.toString(),
                        color = White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "RPM",
                        color = Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
                    )
                }
                Spacer(Modifier.width(22.dp))
                MixerTelemetryReadout(
                    label = "SPEED",
                    value = drivetrain.rawSpeedKmh.toInt().toString(),
                    unit = "km/h",
                )
                Spacer(Modifier.width(16.dp))
                MixerTelemetryReadout(
                    label = "PRED SPEED",
                    value = String.format(Locale.US, "%.2f", drivetrain.presentationSpeedKmh),
                    unit = "km/h",
                )
                Spacer(Modifier.width(16.dp))
                MixerTelemetryReadout(
                    label = "PRED ACCEL",
                    value = String.format(Locale.US, "%+.2f", drivetrain.presentationAccelerationKmhPerSecond),
                    unit = "km/h/s",
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
private fun MixerTelemetryReadout(
    label: String,
    value: String,
    unit: String? = null,
) {
    Column {
        Text(
            text = label,
            color = Muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.7.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Cyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            unit?.let {
                Text(
                    text = it,
                    color = CyanSoft,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
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
    onSelectCar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audioAssetResolver = remember(context) {
        FmodBankResolver(context.applicationContext)
    }
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
                verticalArrangement = Arrangement.Center,
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

            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FmodBankProfiles.all.forEach { profile ->
                val installed = audioAssetResolver.isInstalled(profile)
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
                            Column {
                                Text(
                                    profile.displayName,
                                    fontWeight = if (profile.id == selectedCarId) FontWeight.Black else FontWeight.Normal,
                                )
                                if (!installed) {
                                    Text("AUDIO NOT INSTALLED", color = Amber, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    },
                    enabled = installed,
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
private fun FmodSourceMeter(
    source: FmodSourceState,
    highlight: Boolean,
    onMute: (String, Boolean) -> Unit,
    onSolo: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var muted by remember(source.id) { mutableStateOf(false) }
    var soloed by remember(source.id) { mutableStateOf(false) }
    val level = source.audibility.toFloat().coerceIn(0f, 1f)
    val fillColor = outputMeterFillColor(level)
    val meterLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel.copy(alpha = 0.88f))
            .border(2.dp, if (highlight) Cyan else Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = source.soundName,
                color = White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        source.isVirtual -> "VIRTUAL"
                        source.isActive && source.audibility <= 0.002 ->
                            "SILENT • ${source.voiceCount} VOICE${if (source.voiceCount == 1) "" else "S"}"
                        source.isActive ->
                            "${source.voiceCount} VOICE${if (source.voiceCount == 1) "" else "S"}"
                        else -> "READY"
                    },
                    color = if (source.isActive) Cyan else Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = String.format(Locale.US, "ROUTE %.2fx", source.routeGain),
                    color = Muted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text("M", color = if (muted) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable { muted = !muted; onMute(source.eventName, muted) })
                Text("S", color = if (soloed) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable { soloed = !soloed; onSolo(source.eventName, soloed) })
            }
        }
        Text(
            text = source.eventName.uppercase().replace('_', ' '),
            color = CyanSoft,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                        "${source.audibilityPercent}%",
                        size.width - 8f,
                        size.height * 0.70f,
                        meterLabelPaint,
                    )
                }
            }
        }
    }
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
