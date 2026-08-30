package com.gabrielpc.enginesoundsimulator

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.audio.FmodAudioCapability
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodCapabilityDelivery
import com.gabrielpc.enginesoundsimulator.audio.FmodEventKind
import com.gabrielpc.enginesoundsimulator.audio.FmodEventMixSettings
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import java.util.Locale
import kotlin.math.roundToInt

enum class DashboardMainScreen(val title: String, val subtitle: String) {
    CLASSIC("CLASSIC", "CIRCULAR TACH"),
    MIXER("MIXER", "FMOD EVENTS"),
}

/** Presentation contract for the future native, rendered-PCM verification action. */
internal data class FmodAudioEventCheckResult(
    val kind: FmodEventKind,
    val eventPath: String,
    val instanceStarts: Int,
    val soundPlayedCallbacks: Int,
    val renderedFrames: Long,
    val peakDbfs: Double?,
    val rmsDbfs: Double?,
    val nonFiniteSamples: Long,
    val passed: Boolean,
    val detail: String = "",
)

internal sealed interface FmodAudioCheckUiState {
    data class Running(val profileName: String) : FmodAudioCheckUiState

    data class Complete(
        val profileName: String,
        val eventResults: List<FmodAudioEventCheckResult>,
        val excludedInstantiationCount: Int,
        val durationMilliseconds: Long,
    ) : FmodAudioCheckUiState {
        val passed: Boolean
            get() = eventResults.isNotEmpty() &&
                eventResults.all(FmodAudioEventCheckResult::passed) &&
                excludedInstantiationCount == 0
    }

    data class Failed(
        val stage: String,
        val detail: String,
        val partialResults: List<FmodAudioEventCheckResult> = emptyList(),
    ) : FmodAudioCheckUiState
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
    onCarMasterVolumeChange: (Double) -> Unit,
    onEventEnabled: (FmodEventKind, Boolean) -> Unit,
    onEventGainDb: (FmodEventKind, Double) -> Unit,
    onLoadOnlyEnabled: (Boolean) -> Unit,
    onCoastOnlyEnabled: (Boolean) -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    onSelectCar: (String) -> Unit,
    onRunFmodAudioCheck: (() -> Unit)? = null,
    fmodAudioCheckState: FmodAudioCheckUiState? = null,
    modifier: Modifier = Modifier,
) {
    val selectedProfile = remember(state.selectedCarId) {
        FmodCarProfiles.find(state.selectedCarId)
    }
    val supportedKinds = remember(selectedProfile.id, selectedProfile.events) {
        FmodEventKind.entries.filter(selectedProfile.events::containsKey)
    }
    val eventPaths = remember(selectedProfile.id, selectedProfile.events) {
        selectedProfile.events.mapValues { it.value.path }
    }
    val embeddedCapabilities = remember(selectedProfile.id, selectedProfile.capabilityRoutes) {
        selectedProfile.capabilityRoutes.entries
            .filter { it.value.delivery == FmodCapabilityDelivery.EMBEDDED_IN_ENGINE }
            .groupBy(
                keySelector = { it.value.eventKind },
                valueTransform = { it.key },
            )
    }
    val eventColumnSize = ((supportedKinds.size + MIXER_EVENT_COLUMN_COUNT - 1) /
        MIXER_EVENT_COLUMN_COUNT).coerceAtLeast(1)
    val eventColumns = List(MIXER_EVENT_COLUMN_COUNT) { column ->
        supportedKinds.drop(column * eventColumnSize).take(eventColumnSize)
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
            selectedCarName = state.selectedCarName,
            selectedCarPreviewAsset = state.selectedCarPreviewAsset,
            selectedCarId = state.selectedCarId,
            selectedCarIndex = state.selectedCarIndex,
            availableCarCount = state.availableCarCount,
            carAudioReady = state.carAudioReady,
            carMasterVolume = state.carMasterVolume,
            onCarMasterVolumeChange = onCarMasterVolumeChange,
            onSelectCar = onSelectCar,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EventMixColumn(
                kinds = eventColumns[0],
                eventPaths = eventPaths,
                embeddedCapabilities = embeddedCapabilities,
                settings = state.eventMixSettings,
                onEventEnabled = onEventEnabled,
                onEventGainDb = onEventGainDb,
                modifier = Modifier.weight(1f),
            )
            EventMixColumn(
                kinds = eventColumns[1],
                eventPaths = eventPaths,
                embeddedCapabilities = embeddedCapabilities,
                settings = state.eventMixSettings,
                onEventEnabled = onEventEnabled,
                onEventGainDb = onEventGainDb,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                EventMixColumn(
                    kinds = eventColumns[2],
                    eventPaths = eventPaths,
                    embeddedCapabilities = embeddedCapabilities,
                    settings = state.eventMixSettings,
                    onEventEnabled = onEventEnabled,
                    onEventGainDb = onEventGainDb,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = MIXER_PEDALS_OVERLAY_HEIGHT),
                    footer = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EngineThrottleOverrideControl(
                                label = "LOAD ONLY",
                                detail = "Matches the desktop lab: engine and transmission load lanes stay full; turbo, backfire detection, tach, and EV torque retain real inputs.",
                                accent = Cyan,
                                enabled = state.loadOnlyEnabled,
                                onEnabledChange = onLoadOnlyEnabled,
                            )
                            EngineThrottleOverrideControl(
                                label = "COAST ONLY",
                                detail = "Only engine_int hears zero throttle; separate turbo, backfire, and transmission events retain real inputs.",
                                accent = Amber,
                                enabled = state.coastOnlyEnabled,
                                onEnabledChange = onCoastOnlyEnabled,
                            )
                            if (onRunFmodAudioCheck != null || fmodAudioCheckState != null) {
                                FmodAudioCheckControl(
                                    state = fmodAudioCheckState,
                                    onRun = onRunFmodAudioCheck,
                                )
                            }
                        }
                    },
                )
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
}

private const val MIXER_EVENT_COLUMN_COUNT = 3
private val MIXER_PEDALS_OVERLAY_HEIGHT = 132.dp

@Composable
private fun EventMixColumn(
    kinds: List<FmodEventKind>,
    eventPaths: Map<FmodEventKind, String>,
    embeddedCapabilities: Map<FmodEventKind, List<FmodAudioCapability>>,
    settings: FmodEventMixSettings,
    onEventEnabled: (FmodEventKind, Boolean) -> Unit,
    onEventGainDb: (FmodEventKind, Double) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    footer: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(kinds, key = { it.name }) { kind ->
            FmodEventMixControl(
                kind = kind,
                authoredEventPath = eventPaths.getValue(kind),
                embeddedCapabilities = embeddedCapabilities[kind].orEmpty(),
                enabled = settings.control(kind).enabled,
                gainDb = settings.control(kind).gainDb,
                onEnabledChange = { onEventEnabled(kind, it) },
                onGainDbChange = { onEventGainDb(kind, it) },
            )
        }
        footer?.let { footerContent -> item(key = "footer") { footerContent() } }
    }
}

@Composable
private fun MixerHeaderRow(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    selectedCarName: String,
    selectedCarPreviewAsset: String,
    selectedCarId: String,
    selectedCarIndex: Int,
    availableCarCount: Int,
    carAudioReady: Boolean,
    carMasterVolume: Double,
    onCarMasterVolumeChange: (Double) -> Unit,
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
        FixedCarSummary(
            selectedCarName = selectedCarName,
            selectedCarPreviewAsset = selectedCarPreviewAsset,
            selectedCarId = selectedCarId,
            selectedCarIndex = selectedCarIndex,
            availableCarCount = availableCarCount,
            carAudioReady = carAudioReady,
            carMasterVolume = carMasterVolume,
            onCarMasterVolumeChange = onCarMasterVolumeChange,
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
private fun FixedCarSummary(
    selectedCarName: String,
    selectedCarPreviewAsset: String,
    selectedCarId: String,
    selectedCarIndex: Int,
    availableCarCount: Int,
    carAudioReady: Boolean,
    carMasterVolume: Double,
    onCarMasterVolumeChange: (Double) -> Unit,
    onSelectCar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var carMenuExpanded by remember { mutableStateOf(false) }
    val availableProfiles = FmodCarProfiles.all
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
                modifier = Modifier.fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "FMOD BANK  •  ${selectedCarIndex + 1}/${availableCarCount.coerceAtLeast(1)}  •  " +
                            if (carAudioReady) "READY" else "LOADING",
                        color = if (carAudioReady) Green else Amber,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.dp))
                                .clickable(enabled = availableProfiles.size > 1) {
                                    carMenuExpanded = true
                                }
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedCarName,
                                color = White,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (availableProfiles.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Choose FMOD car",
                                    tint = Cyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = carMenuExpanded,
                            onDismissRequest = { carMenuExpanded = false },
                        ) {
                            availableProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = profile.displayName,
                                            color = if (profile.id == selectedCarId) Cyan else White,
                                            fontWeight = if (profile.id == selectedCarId) {
                                                FontWeight.Black
                                            } else {
                                                FontWeight.Medium
                                            },
                                        )
                                    },
                                    onClick = {
                                        carMenuExpanded = false
                                        if (profile.id != selectedCarId) onSelectCar(profile.id)
                                    },
                                )
                            }
                        }
                    }
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
private fun FmodEventMixControl(
    kind: FmodEventKind,
    authoredEventPath: String,
    embeddedCapabilities: List<FmodAudioCapability>,
    enabled: Boolean,
    gainDb: Double,
    onEnabledChange: (Boolean) -> Unit,
    onGainDbChange: (Double) -> Unit,
) {
    val accent = if (enabled) Cyan else Muted
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
                text = kind.displayName(),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            EventEnableChip(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
        Text(
            text = "${kind.description()}  •  ${authoredEventPath.substringAfterLast('/')}",
            color = Muted,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (embeddedCapabilities.isNotEmpty()) {
            Text(
                text = "EMBEDDED HERE: " + embeddedCapabilities.joinToString(" • ") {
                    it.displayName()
                },
                color = Amber,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
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
                value = gainDb.toFloat().coerceIn(
                    FmodEventMixSettings.MIN_GAIN_DB.toFloat(),
                    FmodEventMixSettings.MAX_GAIN_DB.toFloat(),
                ),
                onValueChange = { onGainDbChange(it.toDouble()) },
                valueRange = FmodEventMixSettings.MIN_GAIN_DB.toFloat()..FmodEventMixSettings.MAX_GAIN_DB.toFloat(),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Line,
                    disabledThumbColor = Muted.copy(alpha = 0.45f),
                    disabledActiveTrackColor = Line,
                    disabledInactiveTrackColor = Line.copy(alpha = 0.55f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp),
            )
            Text(
                formatGainDb(gainDb),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.width(62.dp),
            )
        }
    }
}

@Composable
private fun EngineThrottleOverrideControl(
    label: String,
    detail: String,
    accent: Color,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) accent.copy(alpha = 0.12f) else Panel.copy(alpha = 0.88f))
            .border(1.dp, if (enabled) accent.copy(alpha = 0.8f) else Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable { onEnabledChange(!enabled) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = if (enabled) accent else White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                detail,
                color = Muted,
                fontSize = 9.sp,
                lineHeight = 11.sp,
            )
        }
        EventEnableChip(
            checked = enabled,
            accent = accent,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun FmodAudioCheckControl(
    state: FmodAudioCheckUiState?,
    onRun: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel.copy(alpha = 0.88f))
            .border(1.dp, Line.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Button(
            onClick = { onRun?.invoke() },
            enabled = onRun != null && state !is FmodAudioCheckUiState.Running,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan.copy(alpha = 0.18f),
                contentColor = Cyan,
                disabledContainerColor = Line.copy(alpha = 0.35f),
                disabledContentColor = Muted,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                text = if (state is FmodAudioCheckUiState.Running) {
                    "CHECKING RENDERED AUDIO…"
                } else {
                    "RUN FMOD AUDIO CHECK"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
            )
        }
        when (state) {
            null -> Unit
            is FmodAudioCheckUiState.Running -> Text(
                text = "${state.profileName}: rendering each allowlisted event off-screen.",
                color = Amber,
                fontSize = 9.sp,
                lineHeight = 11.sp,
            )
            is FmodAudioCheckUiState.Complete -> {
                Text(
                    text = if (state.passed) {
                        "PASS • ${state.eventResults.size} events • ${state.durationMilliseconds} ms"
                    } else {
                        "FAILED • ${state.eventResults.count { !it.passed }} event failures • " +
                            "${state.excludedInstantiationCount} excluded instances"
                    },
                    color = if (state.passed) Green else Red,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 11.sp,
                )
                FmodAudioEventCheckRows(state.eventResults)
            }
            is FmodAudioCheckUiState.Failed -> {
                Text(
                    text = "${state.stage}: ${state.detail}",
                    color = Red,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                )
                FmodAudioEventCheckRows(state.partialResults)
            }
        }
    }
}

@Composable
private fun FmodAudioEventCheckRows(results: List<FmodAudioEventCheckResult>) {
    results.forEach { result ->
        val pcm = if (result.peakDbfs != null && result.rmsDbfs != null) {
            String.format(Locale.US, "peak %.1f / rms %.1f dBFS", result.peakDbfs, result.rmsDbfs)
        } else {
            "no PCM level"
        }
        Text(
            text = buildString {
                append(if (result.passed) "PASS " else "FAIL ")
                append(result.eventPath.substringAfterLast('/'))
                append(" • starts ").append(result.instanceStarts)
                append(" • sounds ").append(result.soundPlayedCallbacks)
                append(" • frames ").append(result.renderedFrames)
                append(" • ").append(pcm)
                if (result.nonFiniteSamples > 0) {
                    append(" • non-finite ").append(result.nonFiniteSamples)
                }
                if (result.detail.isNotBlank()) append(" • ").append(result.detail)
            },
            color = if (result.passed) Green else Red,
            fontSize = 8.sp,
            lineHeight = 10.sp,
        )
    }
}

@Composable
private fun EventEnableChip(
    checked: Boolean,
    accent: Color = Cyan,
    onCheckedChange: (Boolean) -> Unit,
) {
    Text(
        text = if (checked) "ON" else "OFF",
        color = if (checked) accent else Muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .width(38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) accent.copy(alpha = 0.18f) else Color(0xFF061018))
            .border(1.dp, if (checked) accent.copy(alpha = 0.85f) else Line.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 3.dp),
        textAlign = TextAlign.Center,
    )
}

private fun FmodEventKind.displayName(): String = when (this) {
    FmodEventKind.ENGINE -> "ENGINE"
    FmodEventKind.TRANSMISSION -> "TRANSMISSION"
    FmodEventKind.TURBO -> "TURBO"
    FmodEventKind.LIMITER -> "LIMITER"
    FmodEventKind.SHIFTS -> "SHIFT SOUNDS"
    FmodEventKind.BACKFIRE -> "BACKFIRE"
}

private fun FmodEventKind.description(): String = when (this) {
    FmodEventKind.ENGINE -> "Cockpit engine and exhaust bank event"
    FmodEventKind.TRANSMISSION -> "Authored drivetrain whine and load response"
    FmodEventKind.TURBO -> "Boost spool and pressure response"
    FmodEventKind.LIMITER -> "Authored redline pulse"
    FmodEventKind.SHIFTS -> "Accepted upshift and downshift events"
    FmodEventKind.BACKFIRE -> "High-RPM throttle-lift event"
}

private fun FmodAudioCapability.displayName(): String = when (this) {
    FmodAudioCapability.ENGINE -> "ENGINE"
    FmodAudioCapability.TURBO -> "TURBO"
    FmodAudioCapability.LIMITER -> "LIMITER"
    FmodAudioCapability.SHIFTS -> "SHIFT"
    FmodAudioCapability.BACKFIRE -> "BACKFIRE"
    FmodAudioCapability.TRANSMISSION -> "TRANSMISSION"
    FmodAudioCapability.ENGINE_START -> "START"
    FmodAudioCapability.ENGINE_SHUTDOWN -> "SHUTDOWN"
}

private fun formatGainDb(gainDb: Double): String {
    return String.format(Locale.US, "%+.1f dB", gainDb.coerceIn(
        FmodEventMixSettings.MIN_GAIN_DB,
        FmodEventMixSettings.MAX_GAIN_DB,
    ))
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
