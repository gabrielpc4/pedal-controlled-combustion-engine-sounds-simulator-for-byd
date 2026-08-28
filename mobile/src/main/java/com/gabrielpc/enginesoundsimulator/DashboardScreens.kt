package com.gabrielpc.enginesoundsimulator

import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogEntry
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogSnapshot
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import androidx.compose.ui.window.Dialog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
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
    carCatalog: CarCatalogSnapshot?,
    catalogStatus: String?,
    catalogStatusIsError: Boolean,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onSelectCar: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportPacks: () -> Unit,
    onImportCatalog: () -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    onLayerMuted: (String, Boolean) -> Unit,
    onLayerSolo: (String, Boolean) -> Unit,
    onLayerVolume: (String, Double) -> Unit,
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
            selectedCarPreviewAsset = state.selectedCarPreviewAsset,
            carCatalog = carCatalog,
            catalogStatus = catalogStatus,
            catalogStatusIsError = catalogStatusIsError,
            carMasterVolume = state.carMasterVolume,
            onSelectCar = onSelectCar,
            onToggleFavorite = onToggleFavorite,
            onImportPacks = onImportPacks,
            onImportCatalog = onImportCatalog,
            onCarMasterVolumeChange = onCarMasterVolumeChange,
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
                        onMuted = { onLayerMuted(track.id, it) },
                        onSolo = { onLayerSolo(track.id, it) },
                        onVolume = { onLayerVolume(track.id, it) },
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
                        lockedToVehicle = state.transmissionLockedToVehicle,
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
    selectedCarPreviewAsset: String,
    carCatalog: CarCatalogSnapshot?,
    catalogStatus: String?,
    catalogStatusIsError: Boolean,
    carMasterVolume: Double,
    onSelectCar: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportPacks: () -> Unit,
    onImportCatalog: () -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
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
            carCatalog = carCatalog,
            catalogStatus = catalogStatus,
            catalogStatusIsError = catalogStatusIsError,
            carMasterVolume = carMasterVolume,
            onSelectCar = onSelectCar,
            onToggleFavorite = onToggleFavorite,
            onImportPacks = onImportPacks,
            onImportCatalog = onImportCatalog,
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
    val rpmJitter = rememberLimiterGaugeRpmJitter(drivetrain.limiterActive, amplitudeFraction = 0.011f)
    val displayedRpmFraction = (rpmFraction + rpmJitter).coerceIn(0f, 1f)
    val redlineFraction = (redlineRpm / maxRpm.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
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
                drawRect(
                    color = Red.copy(alpha = 0.18f),
                    topLeft = Offset(redlineX, 0f),
                    size = androidx.compose.ui.geometry.Size(width - redlineX, height),
                )
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.35f), Amber, Red)),
                    size = androidx.compose.ui.geometry.Size(width * displayedRpmFraction, height),
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
    selectedCarPreviewAsset: String,
    carCatalog: CarCatalogSnapshot?,
    catalogStatus: String?,
    catalogStatusIsError: Boolean,
    carMasterVolume: Double,
    onSelectCar: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportPacks: () -> Unit,
    onImportCatalog: () -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedEntry = carCatalog?.find(selectedCarId)
    Box(
        modifier = modifier.padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = 112.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBright)
                .border(1.dp, Line, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarPreviewImage(
                absolutePath = selectedEntry?.previewFile?.absolutePath,
                assetFallback = selectedCarPreviewAsset,
                maximumDimensionPx = 512,
                contentDescription = selectedCarName,
                modifier = Modifier
                    .width(144.dp)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { expanded = true },
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("SIMULATED CAR", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            text = selectedCarName,
                            color = White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val selectorStatus = catalogStatus ?: selectedEntry?.let {
                            if (it.installed) "PACK INSTALLED" else "PACK NOT INSTALLED"
                        }
                        Text(
                            text = selectorStatus.orEmpty(),
                            color = when {
                                catalogStatusIsError -> Red
                                catalogStatus != null || selectedEntry?.installed == true -> Green
                                else -> Amber
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(16.dp),
                        )
                    }
                    Text(
                        text = carFavoriteMarker(selectedEntry?.favorite),
                        color = if (selectedEntry?.favorite == true) Amber else Muted,
                        fontSize = 24.sp,
                        modifier = Modifier
                            .width(34.dp)
                            .clickable(enabled = selectedEntry != null) {
                                selectedEntry?.let { onToggleFavorite(it.id) }
                            },
                        textAlign = TextAlign.Center,
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
        if (expanded) {
            CarCatalogDialog(
                catalog = carCatalog,
                selectedCarId = selectedCarId,
                catalogStatus = catalogStatus,
                catalogStatusIsError = catalogStatusIsError,
                onDismiss = { expanded = false },
                onSelectCar = {
                    expanded = false
                    onSelectCar(it)
                },
                onToggleFavorite = onToggleFavorite,
                onImportPacks = {
                    expanded = false
                    onImportPacks()
                },
                onImportCatalog = {
                    expanded = false
                    onImportCatalog()
                },
            )
        }
    }
}

@Composable
private fun CarCatalogDialog(
    catalog: CarCatalogSnapshot?,
    selectedCarId: String,
    catalogStatus: String?,
    catalogStatusIsError: Boolean,
    onDismiss: () -> Unit,
    onSelectCar: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportPacks: () -> Unit,
    onImportCatalog: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val entries = remember(catalog?.entries, normalizedQuery) {
        filterCarCatalogEntries(catalog?.entries.orEmpty(), normalizedQuery)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(Panel)
                .border(1.dp, Line, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ASSETTO CORSA CARS", color = White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (catalog == null) "Connecting to the driving catalog…" else
                            "${catalog.entries.count { it.installed }} / ${catalog.entries.size} cars installed · ${catalog.installedFamilyCount} sound families",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelBright),
                ) {
                    Text("CLOSE", color = Cyan, fontWeight = FontWeight.Black)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name, brand, or car ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onImportPacks,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.18f)),
                ) {
                    Text("IMPORT .ACLIB", color = Cyan, fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = onImportCatalog,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelBright),
                ) {
                    Text("CATALOG JSON", color = White, fontWeight = FontWeight.Bold)
                }
            }

            if (catalogStatus != null) {
                Text(
                    text = catalogStatus,
                    color = if (catalogStatusIsError) Red else Green,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (entries.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (catalog == null) "CATALOG UNAVAILABLE" else "NO CARS MATCH YOUR SEARCH",
                        color = Muted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        CarCatalogRow(
                            entry = entry,
                            selected = entry.id == selectedCarId,
                            onSelect = { onSelectCar(entry.id) },
                            onToggleFavorite = { onToggleFavorite(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarCatalogRow(
    entry: CarCatalogEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Cyan.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.22f))
            .border(1.dp, if (selected) Cyan else Line.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CarPreviewImage(
            absolutePath = entry.previewFile?.absolutePath,
            assetFallback = null,
            maximumDimensionPx = 256,
            contentDescription = entry.displayName,
            modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                color = if (entry.installed) White else Muted,
                fontSize = 14.sp,
                fontWeight = if (selected || entry.favorite) FontWeight.Black else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.brand} · ${entry.id}",
                color = Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = carInstallationLabel(entry.installed),
            color = if (entry.installed) Green else Amber,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(76.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = carFavoriteMarker(entry.favorite),
            color = if (entry.favorite) Amber else Muted,
            fontSize = 25.sp,
            modifier = Modifier.width(38.dp).clickable(onClick = onToggleFavorite),
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (selected) "✓" else "",
            color = Cyan,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(22.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Pure catalog presentation policy, kept testable without constructing a Compose hierarchy. */
internal fun filterCarCatalogEntries(
    entries: List<CarCatalogEntry>,
    query: String,
): List<CarCatalogEntry> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isEmpty()) return entries
    return entries.filter { entry ->
        entry.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.brand.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.id.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
}

internal fun carInstallationLabel(installed: Boolean): String =
    if (installed) "INSTALLED" else "IMPORT PACK"

internal fun carFavoriteMarker(favorite: Boolean?): String = when (favorite) {
    true -> "★"
    false -> "☆"
    null -> ""
}

@Composable
internal fun CarPreviewImage(
    absolutePath: String?,
    assetFallback: String?,
    contentDescription: String,
    maximumDimensionPx: Int = 1_280,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, absolutePath, assetFallback, maximumDimensionPx) {
        value = withContext(Dispatchers.IO) {
            try {
                val privateFile = sequenceOf(absolutePath, assetFallback)
                    .filterNotNull()
                    .map(::File)
                    .firstOrNull { it.isAbsolute && it.isFile }
                val asset = if (privateFile == null) {
                    assetFallback
                        ?.takeIf(String::isNotBlank)
                        ?.takeUnless { File(it).isAbsolute }
                        ?: throw IllegalStateException("No preview image is installed")
                } else {
                    null
                }
                val openInput = {
                    privateFile?.inputStream() ?: context.assets.open(requireNotNull(asset))
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openInput().use { BitmapFactory.decodeStream(it, null, bounds) }
                var sampleSize = 1
                val largestDimension = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                while (largestDimension / sampleSize > maximumDimensionPx.coerceAtLeast(64)) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                openInput().use {
                    val bitmap = requireNotNull(BitmapFactory.decodeStream(it, null, options))
                    bitmap.asImageBitmap()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        val loadedPreview = preview
        if (loadedPreview != null) {
            Image(
                bitmap = loadedPreview,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "NO PREVIEW",
                color = Muted.copy(alpha = 0.72f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LayerMixTrackControl(
    track: LayerMixTrackState,
    onMuted: (Boolean) -> Unit,
    onSolo: (Boolean) -> Unit,
    onVolume: (Double) -> Unit,
) {
    val level = track.outputLevel.toFloat().coerceIn(0f, 1f)
    val fillColor = outputMeterFillColor(level)
    val showTrimSlider = track.showVolumeSlider
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
            Text(
                text = "${(level * 100f).roundToInt()}%",
                color = fillColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF061018))
                    .border(1.dp, Line.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            ) {
                if (level > 0.002f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        fillColor.copy(alpha = 0.45f),
                                        fillColor,
                                        fillColor.copy(red = minOf(fillColor.red + 0.08f, 1f)),
                                    ),
                                ),
                            ),
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

@Composable
internal fun ResolutionProbeScreen(
    selectedScreen: DashboardMainScreen,
    onSelectScreen: (DashboardMainScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    var gridStepPx by remember { mutableIntStateOf(100) }
    var probeWidthPx by remember { mutableFloatStateOf(-1f) }
    var probeHeightPx by remember { mutableFloatStateOf(-1f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.roundToPx() }
        val screenHeightPx = with(density) { maxHeight.roundToPx() }
        val probeReady = probeWidthPx >= 0f

        val probeWidthDp = with(density) { max(probeWidthPx, 1f).toDp() }
        val probeHeightDp = with(density) { max(probeHeightPx, 1f).toDp() }
        val contentWidthPx = max(screenWidthPx, if (probeReady) probeWidthPx.roundToInt() else screenWidthPx)
        val contentHeightPx = max(screenHeightPx, if (probeReady) probeHeightPx.roundToInt() else screenHeightPx)
        val contentWidthDp = with(density) { contentWidthPx.toDp() }
        val contentHeightDp = with(density) { contentHeightPx.toDp() }

        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020608)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState),
            ) {
                Box(
                    modifier = Modifier
                        .horizontalScroll(horizontalScrollState)
                        .size(contentWidthDp, contentHeightDp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = gridStepPx.toFloat()
                        val verticalLines = ceil(size.width / step).toInt()
                        val horizontalLines = ceil(size.height / step).toInt()

                        for (index in 0..verticalLines) {
                            val x = index * step
                            drawLine(
                                color = if (index % 5 == 0) Cyan.copy(alpha = 0.45f) else Line.copy(alpha = 0.28f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = if (index % 5 == 0) 1.5f else 1f,
                            )
                        }

                        for (index in 0..horizontalLines) {
                            val y = index * step
                            drawLine(
                                color = if (index % 5 == 0) Cyan.copy(alpha = 0.45f) else Line.copy(alpha = 0.28f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = if (index % 5 == 0) 1.5f else 1f,
                            )
                        }
                    }

                    if (probeReady) {
                        Box(
                            modifier = Modifier
                                .size(probeWidthDp, probeHeightDp)
                                .border(width = 4.dp, color = Red),
                        ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawLine(Color.Red, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2f)
                            drawLine(Color.Red, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
                            drawRect(Color.Red.copy(alpha = 0.12f))
                            drawIntoCanvas { canvas ->
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.rgb(136, 162, 178)
                                    textSize = 28f
                                }
                                canvas.nativeCanvas.drawText("0,0", 8f, 28f, paint)
                                canvas.nativeCanvas.drawText(
                                    "${probeWidthPx.roundToInt()},${probeHeightPx.roundToInt()}",
                                    size.width - 220f,
                                    size.height - 12f,
                                    paint,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 24.dp, y = 24.dp)
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Red.copy(alpha = 0.92f))
                                .border(2.dp, White, RoundedCornerShape(10.dp))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        probeWidthPx = max(50f, probeWidthPx + dragAmount.x)
                                        probeHeightPx = max(50f, probeHeightPx + dragAmount.y)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↘", color = White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 20.dp)
                                .width(36.dp)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        probeWidthPx = max(50f, probeWidthPx + dragAmount.x)
                                    }
                                },
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 20.dp)
                                .height(36.dp)
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        probeHeightPx = max(50f, probeHeightPx + dragAmount.y)
                                    }
                                },
                        )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .onGloballyPositioned { coordinates ->
                        if (probeWidthPx < 0f) {
                            val marginPx = with(density) { 12.dp.toPx() }
                            probeWidthPx = coordinates.size.width + marginPx * 2f
                            probeHeightPx = coordinates.size.height + marginPx * 2f
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DashboardScreenSwitcher(
                    selected = selectedScreen,
                    onSelect = onSelectScreen,
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "PROBE SIZE",
                        color = Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        if (probeReady) {
                            "${probeWidthPx.roundToInt()} × ${probeHeightPx.roundToInt()} px"
                        } else {
                            "— × — px"
                        },
                        color = White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        if (probeReady) {
                            "${probeWidthDp.value.roundToInt()} × ${probeHeightDp.value.roundToInt()} dp"
                        } else {
                            "— × — dp"
                        },
                        color = White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Screen: ${screenWidthPx} × ${screenHeightPx} px — drag ↘ corner (can exceed screen; scroll to see overflow)",
                        color = Muted,
                        fontSize = 10.sp,
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
