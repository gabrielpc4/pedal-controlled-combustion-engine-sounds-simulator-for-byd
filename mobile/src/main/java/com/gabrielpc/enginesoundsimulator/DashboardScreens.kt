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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfile
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodBankResolver
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.FmodEventSection
import com.gabrielpc.enginesoundsimulator.audio.FmodSourceState
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import java.util.Locale

enum class DashboardMainScreen(val title: String, val subtitle: String) {
    CLASSIC("CLASSIC", "CIRCULAR TACH"),
    MIXER("MIXER", "HUD + LAYERS"),
    SETTINGS("SETTINGS", "PREFERENCES"),
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
    onToggleSimulatedPedalLatch: () -> Unit,
    onSelectCar: (String) -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    onHostGains: (Float, Float) -> Unit,
    onCategoryGains: (Float, Float, Float, Float) -> Unit,
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
    // FMOD swaps sound names inside one authored event as RPM changes. Keep
    // M/S on that event identity so a control never disappears with a source.
    var mutedEvents by remember(soundPerspective, state.selectedCarId) { mutableStateOf(emptyMap<String, Boolean>()) }
    var soloedEvents by remember(soundPerspective, state.selectedCarId) { mutableStateOf(emptyMap<String, Boolean>()) }
    val activeIds = state.fmodSources.filter { it.isActive }.mapTo(mutableSetOf(), FmodSourceState::id)
    val enteredIds = activeIds - previousActive

    LaunchedEffect(state.fmodSources) {
        val currentSources = state.fmodSources
            .filter(FmodSourceState::isActive)
            .associateBy(FmodSourceState::id)
        val inactiveKnownSources = knownSources.mapValues { (_, source) ->
            source.copy(
                audibility = 0.0,
                voiceCount = 0,
                isVirtual = false,
                isActive = false,
            )
        }
        // Once FMOD has exposed a source, keep its diagnostic card so its
        // disappearance is visible as SILENT instead of looking like a reset.
        knownSources = inactiveKnownSources + currentSources
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

    val sections = remember(knownSources) {
        // Dormant sources that FMOD has never exposed are omitted. Sources that
        // were previously live remain as SILENT diagnostics instead of READY.
        knownSources.values
            .groupBy(FmodSourceState::section)
            .toSortedMap(compareBy<FmodEventSection> {
                // Keep the many engine-region cards out of the way of the
                // shorter effect sections by presenting ENGINE last.
                if (it == FmodEventSection.ENGINE) Int.MAX_VALUE else it.order
            })
            .mapValues { (_, sources) ->
                sources.sortedWith(
                    // Keep each source in a deterministic slot. Activity and audibility are
                    // diagnostic values only; sorting by them made a newly audible voice appear
                    // to replace another card even though both FMOD voices were still alive.
                    compareBy(FmodSourceState::id),
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
        CategoryGainControls(
            transmissionGain = state.transmissionGain,
            gearShiftGain = state.gearShiftGain,
            turboGain = state.turboGain,
            backfireGain = state.backfireGain,
            onChange = onCategoryGains,
        )
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
                                    muted = mutedEvents[source.eventName] == true,
                                    soloed = soloedEvents[source.eventName] == true,
                                    onMute = { muted ->
                                        mutedEvents = mutedEvents + (source.eventName to muted)
                                        onEventMute(source.eventName, muted)
                                    },
                                    onSolo = { solo ->
                                        soloedEvents = soloedEvents + (source.eventName to solo)
                                        onEventSolo(source.eventName, solo)
                                    },
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
                onToggleSimulatedPedalLatch = onToggleSimulatedPedalLatch,
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

@Composable
private fun CategoryGainControls(
    transmissionGain: Float,
    gearShiftGain: Float,
    turboGain: Float,
    backfireGain: Float,
    onChange: (Float, Float, Float, Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GainControl("TRANSMISSION", transmissionGain) { onChange(it, gearShiftGain, turboGain, backfireGain) }
        GainControl("GEAR SHIFT", gearShiftGain) { onChange(transmissionGain, it, turboGain, backfireGain) }
        GainControl("TURBO", turboGain) { onChange(transmissionGain, gearShiftGain, it, backfireGain) }
        GainControl("BACKFIRE", backfireGain) { onChange(transmissionGain, gearShiftGain, turboGain, it) }
    }
}

@Composable
private fun GainControl(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.width(300.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = CyanSoft, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(String.format(Locale.US, "%.0f%%", value * 100f), color = White, fontSize = 10.sp)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..2f)
    }
}

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onResetAll: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SETTINGS", color = White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("BACK", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.clickable(onClick = onBack).padding(12.dp))
        }
        Text(
            "Mixer gains are saved independently for each car. Reset All clears saved car, perspective, shift mode, and audio gain preferences.",
            color = Muted,
            fontSize = 15.sp,
        )
        Button(
            onClick = onResetAll,
            colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.85f)),
        ) {
            Text("RESET ALL", color = White, fontWeight = FontWeight.Black)
        }
    }
}

// The enlarged mixer pedals and adjacent tach need a protected bottom area so cards never slide
// underneath the controls while the diagnostics list is scrolled.
private val MIXER_PEDALS_OVERLAY_HEIGHT = 500.dp

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
    val gear = if (transmissionPosition == TransmissionPosition.DRIVE) drivetrain.gear.toString() else transmissionPosition.displayName
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(drivetrain.rpm.toInt().toString(), color = White, fontSize = 42.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text(" RPM", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                MixerTelemetryReadout("SPEED", drivetrain.realOrDocumentedRawSpeedKmh.toInt().toString(), "km/h")
                Spacer(Modifier.width(12.dp))
                MixerTelemetryReadout("PRED SPEED", String.format(Locale.US, "%.2f", drivetrain.presentationSpeedKmh), "km/h")
            }
            Text(gear, color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
        Box(modifier = Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF061018)).border(1.dp, Line, RoundedCornerShape(4.dp))) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.35f), Amber, Red)), size = androidx.compose.ui.geometry.Size(size.width * rpmFraction, size.height))
                drawRect(color = Red.copy(alpha = 0.18f), topLeft = Offset(size.width * redlineFraction, 0f), size = androidx.compose.ui.geometry.Size(size.width * (1f - redlineFraction), size.height))
                drawLine(Red, Offset(size.width * redlineFraction, 0f), Offset(size.width * redlineFraction, size.height), 2f)
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
                profile = FmodBankProfiles.find(selectedCarId),
                audioAssetResolver = audioAssetResolver,
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
        if (expanded) {
            val installedProfiles = FmodBankProfiles.all.filter(audioAssetResolver::isInstalled)
            // Use the full available window width; the platform's default dialog width would
            // collapse the adaptive grid to one narrow column on larger displays.
            Dialog(
                onDismissRequest = { expanded = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.84f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelBright)
                        .border(1.dp, Line, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "SELECT CAR",
                            color = CyanSoft,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            text = "${installedProfiles.size} INSTALLED",
                            color = Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                        )
                        LazyVerticalGrid(
                            // Card count adapts to the actual dialog width instead of assuming
                            // three columns, so every available horizontal pixel is useful.
                            columns = GridCells.Adaptive(minSize = 260.dp),
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(installedProfiles, key = { it.id }) { profile ->
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (profile.id == selectedCarId) Cyan.copy(alpha = 0.18f) else Panel)
                                        .border(
                                            1.dp,
                                            if (profile.id == selectedCarId) Cyan else Line,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            expanded = false
                                            onSelectCar(profile.id)
                                        }
                                        .padding(8.dp),
                                ) {
                                    CarPreviewThumbnail(
                                        profile = profile,
                                        audioAssetResolver = audioAssetResolver,
                                        contentDescription = profile.displayName,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(112.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    Text(
                                        text = profile.displayName,
                                        color = White,
                                        fontSize = 11.sp,
                                        lineHeight = 13.sp,
                                        fontWeight = if (profile.id == selectedCarId) FontWeight.Black else FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Spacious main-screen picker; thumbnails are capped by their decoded native dimensions. */
@Composable
internal fun CarGridSelectionDialog(
    selectedCarId: String,
    onSelectCar: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resolver = remember(context) { FmodBankResolver(context.applicationContext) }
    val installedProfiles = FmodBankProfiles.all.filter(resolver::isInstalled)
    // Disable the platform's narrow default dialog width so the picker can span the display.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelBright)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(18.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text("SELECT CAR", color = CyanSoft, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(
                    text = "${installedProfiles.size} INSTALLED",
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                LazyVerticalGrid(
                    // Choose as many cards as fit at runtime; this remains usable on both the
                    // 1920x1080 emulator and narrower vehicle displays.
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(installedProfiles, key = { it.id }) { profile ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (profile.id == selectedCarId) Cyan.copy(alpha = 0.18f) else Panel)
                                .border(1.dp, if (profile.id == selectedCarId) Cyan else Line, RoundedCornerShape(10.dp))
                                .clickable {
                                    onDismiss()
                                    onSelectCar(profile.id)
                                }
                                .padding(10.dp),
                        ) {
                            CarPreviewThumbnail(
                                profile = profile,
                                audioAssetResolver = resolver,
                                contentDescription = profile.displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(128.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Text(
                                text = profile.displayName,
                                color = White,
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                fontWeight = if (profile.id == selectedCarId) FontWeight.Black else FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarPreviewThumbnail(
    profile: FmodBankProfile,
    audioAssetResolver: FmodBankResolver,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val installedPreviewPath = audioAssetResolver.previewFile(profile)?.path
    val preview = remember(profile.id, installedPreviewPath) {
        runCatching {
            audioAssetResolver.openCarPreviewInput(profile)?.use { input ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                LoadedCarPreview(
                    image = bitmap.asImageBitmap(),
                    aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat(),
                )
            }
        }.getOrNull()
    }

    val aspectRatio = preview?.aspectRatio ?: (16f / 9f)
    val density = LocalDensity.current

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
                // Do not enlarge a small native preview just to fill a larger picker card.
                modifier = Modifier
                    .fillMaxSize()
                    .sizeIn(
                        maxWidth = with(density) { preview.image.width.toDp() },
                        maxHeight = with(density) { preview.image.height.toDp() },
                    ),
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
    muted: Boolean,
    soloed: Boolean,
    onMute: (Boolean) -> Unit,
    onSolo: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        else -> "SILENT"
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
                    modifier = Modifier.clickable { onMute(!muted) })
                Text("S", color = if (soloed) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable { onSolo(!soloed) })
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
