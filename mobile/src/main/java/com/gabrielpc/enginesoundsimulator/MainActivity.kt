package com.gabrielpc.enginesoundsimulator

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gabrielpc.enginesoundsimulator.drive.DriveController
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.ui.theme.EngineSoundsSimulatorTheme
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Night = Color(0xFF060606)
private val Navy = Color(0xFF071321)
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

class MainActivity : ComponentActivity() {
    private lateinit var controller: DriveController
    private val uiHandler = Handler(Looper.getMainLooper())
    private var driveState by mutableStateOf<DriveSnapshot?>(null)

    private val refreshUi = object : Runnable {
        override fun run() {
            driveState = controller.snapshot()
            // The simulation and audio control loop remain at 200 Hz. Thirty visual
            // updates per second are smooth on the head unit without competing with audio.
            uiHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = DriveController(applicationContext)
        driveState = controller.snapshot()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        setContent {
            EngineSoundsSimulatorTheme(darkTheme = true, dynamicColor = false) {
                driveState?.let { state ->
                    MotorSoundDashboard(
                        state = state,
                        onThrottle = controller::setManualThrottle,
                        onBrake = controller::setManualBrake,
                        onCycleInput = controller::cycleInputMode,
                        onTransmissionChange = controller::setTransmissionPosition,
                        onToggleSound = controller::toggleSound,
                        onCycleChannels = controller::cycleChannelMode,
                        onConfigChange = controller::setTuning,
                        onResetTuning = controller::resetTuning,
                        onRestartBydReader = controller::restartVehicleReader,
                        onRunSampleValidation = controller::runSampleAudioValidation,
                        onPreviousCar = controller::selectPreviousCar,
                        onNextCar = controller::selectNextCar,
                    )
                }
            }
        }
        maybeScheduleSampleValidation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeScheduleSampleValidation(intent)
    }

    private fun maybeScheduleSampleValidation(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_RUN_SAMPLE_VALIDATION, false)) {
            intent.removeExtra(EXTRA_RUN_SAMPLE_VALIDATION)
            uiHandler.postDelayed(controller::runSampleAudioValidation, 1_500L)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
        uiHandler.removeCallbacks(refreshUi)
        uiHandler.post(refreshUi)
    }

    override fun onStop() {
        releaseManualControls()
        uiHandler.removeCallbacks(refreshUi)
        controller.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseManualControls()
    }

    private fun releaseManualControls() {
        controller.setManualThrottle(0.0)
        controller.setManualBrake(0.0)
    }

}

@Composable
private fun MotorSoundDashboard(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onCycleInput: () -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onToggleSound: () -> Unit,
    onCycleChannels: () -> Unit,
    onConfigChange: (TuningConfig) -> Unit,
    onResetTuning: () -> Unit,
    onRestartBydReader: () -> Unit,
    onRunSampleValidation: () -> Unit,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
) {
    var tuningOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (tuningOpen || debugOpen) return@onPreviewKeyEvent false
                val pressed = event.type == KeyEventType.KeyDown
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        onThrottle(if (pressed) 1.0 else 0.0)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_S,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_SPACE,
                    -> {
                        onBrake(if (pressed) 1.0 else 0.0)
                        true
                    }
                    else -> false
                }
            },
        color = Night,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            val heightForFullWidth = maxWidth * (990f / 1920f)
            val (dashboardWidth, dashboardHeight) = if (heightForFullWidth <= maxHeight) {
                maxWidth to heightForFullWidth
            } else {
                (maxHeight * (1920f / 990f)) to maxHeight
            }

            Box(
                modifier = Modifier
                    .width(dashboardWidth)
                    .height(dashboardHeight),
            ) {
                val density = LocalDensity.current
                val viewportWidthPx = with(density) { dashboardWidth.roundToPx() }
                val viewportHeightPx = with(density) { dashboardHeight.roundToPx() }

                Column(modifier = Modifier.fillMaxSize()) {
                    DashboardHeader(
                        state = state,
                        onCycleInput = onCycleInput,
                        onToggleSound = onToggleSound,
                        onCycleChannels = onCycleChannels,
                        onOpenTuning = { tuningOpen = true },
                        onOpenDebug = { debugOpen = true },
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 34.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CarStage(
                            state = state,
                            onThrottle = onThrottle,
                            onBrake = onBrake,
                            onTransmissionChange = onTransmissionChange,
                            onPreviousCar = onPreviousCar,
                            onNextCar = onNextCar,
                            modifier = Modifier
                                .weight(1.12f)
                                .fillMaxHeight(),
                        )
                        Tachometer(
                            drivetrain = state.drivetrain,
                            transmissionPosition = state.transmissionPosition,
                            maxRpm = state.tuning.engine.maxRpm,
                            redlineRpm = state.tuning.engine.redlineRpm,
                            upshiftRpm = state.tuning.engine.upshiftRpm,
                            modifier = Modifier
                                .weight(0.88f)
                                .fillMaxHeight()
                                .padding(start = 16.dp, bottom = 6.dp),
                        )
                    }

                    DashboardFooter(
                        state = state,
                        viewport = "${viewportWidthPx}x${viewportHeightPx}",
                    )
                }

                if (tuningOpen) {
                    TuningPanel(
                        state = state,
                        onConfigChange = onConfigChange,
                        onReset = onResetTuning,
                        onClose = { tuningOpen = false },
                    )
                }

                if (debugOpen) {
                    DebugPanel(
                        state = state,
                        onRestartBydReader = onRestartBydReader,
                        onRunSampleValidation = onRunSampleValidation,
                        onClose = { debugOpen = false },
                    )
                }
            }
        }
    }
}

private const val EXTRA_RUN_SAMPLE_VALIDATION = "run_sample_audio_validation"

@Composable
private fun DashboardHeader(
    state: DriveSnapshot,
    onCycleInput: () -> Unit,
    onToggleSound: () -> Unit,
    onCycleChannels: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color.Black.copy(alpha = 0.38f))
            .border(width = 1.dp, color = Line.copy(alpha = 0.55f))
            .padding(horizontal = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(if (state.engineSoundEnabled) Green else Red),
            )
            Text(
                text = "MOTOR",
                color = White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.0.sp,
            )
            Text(
                text = "// SAMPLE",
                color = Cyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.0.sp,
            )
            StatusTag(state.activeInput, if (state.activeInput.startsWith("BYD")) Green else Cyan)
        }

        HeaderButton(
            primary = "DEBUG",
            secondary = "BYD / LOGS",
            accent = if (state.activeInput == "BYD UNAVAILABLE") Red else Cyan,
            onClick = onOpenDebug,
        )
        HeaderButton(
            primary = "TUNE",
            secondary = "ENGINE PROFILE",
            accent = Amber,
            onClick = onOpenTuning,
        )
        HeaderButton(
            primary = state.inputMode.displayName,
            secondary = "INPUT",
            onClick = onCycleInput,
        )
        HeaderButton(
            primary = if (state.engineSoundEnabled) "ON" else "MUTED",
            secondary = "ENGINE AUDIO",
            accent = if (state.engineSoundEnabled) Green else Red,
            onClick = onToggleSound,
        )
        HeaderButton(
            primary = state.audio.requestedMode.displayName,
            secondary = "${state.audio.activeChannels.coerceAtLeast(0)} CH OUTPUT",
            accent = Cyan,
            onClick = onCycleChannels,
        )
    }
}

@Composable
private fun HeaderButton(
    primary: String,
    secondary: String,
    accent: Color = Cyan,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = White),
        modifier = Modifier.height(52.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(primary, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(secondary, color = Muted, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CarStage(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 28.dp, top = 26.dp),
        ) {
            Text(
                state.selectedCarName.uppercase(),
                color = White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
            )
            Text(
                "RPM / LOAD LOOPSET  •  ${state.audio.sampleLoadedLoops} ENGINE LAYERS  •  " +
                    "${state.tuning.engine.maxRpm.roundToInt()} RPM BANK  •  " +
                    "${state.selectedCarIndex + 1}/${state.availableCarCount}",
                color = CyanSoft,
                fontSize = 12.sp,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTag("${formatWhole(state.drivetrain.speedKmh)} KM/H", Cyan)
                StatusTag("${formatWhole(state.drivetrain.accelerationMps2 / 9.81)} G", Amber)
            }
        }

        val context = LocalContext.current
        val preview = remember(state.selectedCarPreviewAsset) {
            runCatching {
                context.assets.open(state.selectedCarPreviewAsset).use { input ->
                    requireNotNull(BitmapFactory.decodeStream(input)).asImageBitmap()
                }
            }.getOrNull()
        }
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = state.selectedCarName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.65f)
                    .align(Alignment.Center)
                    .offset(y = 18.dp),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.apex_v10_car),
                contentDescription = state.selectedCarName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.62f).align(Alignment.Center),
            )
        }

        CarSelectorArrow("‹", "Previous car", onPreviousCar, Modifier.align(Alignment.CenterStart))
        CarSelectorArrow("›", "Next car", onNextCar, Modifier.align(Alignment.CenterEnd))

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PedalControl(
                label = "BRAKE",
                value = state.brake,
                accent = Red,
                width = 92.dp,
                height = 154.dp,
                onValue = onBrake,
            )
            PedalControl(
                label = "THROTTLE",
                value = state.throttle,
                accent = Green,
                width = 84.dp,
                height = 202.dp,
                onValue = onThrottle,
            )
            TransmissionShifter(
                position = state.transmissionPosition,
                onPositionChange = onTransmissionChange,
            )
        }

        Text(
            text = "TOUCH / DRAG PEDALS   •   P N D SHIFTER   •   W or ↑ THROTTLE   •   S, ↓ or SPACE BRAKE",
            color = Muted,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TransmissionShifter(
    position: TransmissionPosition,
    onPositionChange: (TransmissionPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(58.dp)
            .height(202.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5B6670), Color(0xFF232D35), Color(0xFF11181E)),
                ),
            )
            .border(2.dp, Color(0xFF60717D), RoundedCornerShape(16.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TransmissionPosition.entries.forEach { option ->
            val selected = option == position
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            Amber.copy(alpha = 0.22f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Amber else Color(0xFF4A5A66),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onPositionChange(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.displayName,
                    color = if (selected) Amber else Muted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.0.sp,
                )
            }
        }
    }
}

@Composable
private fun CarSelectorArrow(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(58.dp).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF111111).copy(alpha = 0.92f),
            contentColor = White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(
            text = label,
            color = White,
            fontSize = 42.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PedalControl(
    label: String,
    value: Double,
    accent: Color,
    width: Dp,
    height: Dp,
    onValue: (Double) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5B6670), Color(0xFF232D35), Color(0xFF11181E)),
                ),
            )
            .border(2.dp, if (value > 0.01) accent else Color(0xFF60717D), RoundedCornerShape(16.dp))
            .pointerInput(onValue) {
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun updateAt(y: Float) {
                            onValue((1.0 - y / size.height.toDouble()).coerceIn(0.0, 1.0))
                        }
                        updateAt(down.position.y)
                        var pointer = down
                        do {
                            val event = awaitPointerEvent()
                            pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            updateAt(pointer.position.y)
                            pointer.consume()
                        } while (pointer.pressed)
                    } finally {
                        // Pointer coroutines are cancelled when the window loses focus or
                        // this node leaves composition. Never leave a simulated pedal held.
                        onValue(0.0)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(value.toFloat().coerceIn(0f, 1f))
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.45f)))),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }
            Text(
                text = "${(value * 100).roundToInt()}%",
                color = if (value > 0.01) accent else White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
            )
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }
    }
}

@Composable
private fun Tachometer(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    upshiftRpm: Double,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val gaugeSize = if (maxWidth < maxHeight) maxWidth else maxHeight
        val gaugeMaxRpm = ceil(maxRpm.coerceAtLeast(1_000.0) / 1_000.0) * 1_000.0
        val majorIntervals = (gaugeMaxRpm / 1_000.0).roundToInt().coerceAtLeast(1)
        Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension * 0.455f
                val stroke = radius * 0.012f
                val startAngle = 135f
                val sweepAngle = 270f
                val rpmFraction = (drivetrain.rpm / gaugeMaxRpm).toFloat().coerceIn(0f, 1f)

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF0B2535), Color(0xFF06111A), Color.Black),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
                drawCircle(Cyan.copy(alpha = 0.16f), radius = radius * 1.015f, center = center, style = Stroke(radius * 0.035f))
                drawArc(
                    color = Color(0xFF123F4E),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(stroke * 1.35f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(Cyan, Cyan, Green, Amber, Red, Red), center),
                    startAngle = startAngle,
                    sweepAngle = sweepAngle * rpmFraction,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(stroke * 1.8f, cap = StrokeCap.Round),
                )

                // Perfect full-throttle shift band and red zone.
                drawArc(
                    color = Green,
                    startAngle = startAngle + sweepAngle * ((upshiftRpm - 250.0) / gaugeMaxRpm).toFloat().coerceIn(0f, 1f),
                    sweepAngle = sweepAngle * (350.0 / gaugeMaxRpm).toFloat(),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 0.96f, center.y - radius * 0.96f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.92f, radius * 1.92f),
                    style = Stroke(radius * 0.022f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Red,
                    startAngle = startAngle + sweepAngle * (redlineRpm / gaugeMaxRpm).toFloat().coerceIn(0f, 1f),
                    sweepAngle = sweepAngle * ((maxRpm - redlineRpm) / gaugeMaxRpm).toFloat().coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 0.96f, center.y - radius * 0.96f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.92f, radius * 1.92f),
                    style = Stroke(radius * 0.026f, cap = StrokeCap.Round),
                )

                val tickCount = majorIntervals * 5
                for (tick in 0..tickCount) {
                    val fraction = tick / tickCount.toFloat()
                    val angle = startAngle + sweepAngle * fraction
                    val major = tick % 5 == 0
                    val outer = polar(center, radius * 0.91f, angle)
                    val inner = polar(center, radius * if (major) 0.80f else 0.85f, angle)
                    val inRed = fraction * gaugeMaxRpm >= redlineRpm
                    drawLine(
                        color = if (inRed) Red else if (major) Cyan else CyanSoft.copy(alpha = 0.60f),
                        start = inner,
                        end = outer,
                        strokeWidth = if (major) radius * 0.012f else radius * 0.005f,
                        cap = StrokeCap.Round,
                    )
                }

                drawIntoCanvas { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textAlign = Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC)
                        textSize = radius * 0.105f
                    }
                    for (number in 0..majorIntervals) {
                        val point = polar(center, radius * 0.69f, startAngle + sweepAngle * (number / majorIntervals.toFloat()))
                        paint.color = if (number * 1_000.0 >= redlineRpm) android.graphics.Color.rgb(255, 57, 79) else android.graphics.Color.WHITE
                        canvas.nativeCanvas.drawText(number.toString(), point.x, point.y + paint.textSize * 0.34f, paint)
                    }
                }

                val needleAngle = startAngle + sweepAngle * rpmFraction
                val needleTip = polar(center, radius * 0.77f, needleAngle)
                val angleRadians = Math.toRadians(needleAngle.toDouble())
                val perpendicular = angleRadians + PI / 2.0
                val baseHalfWidth = radius * 0.027f
                val baseA = androidx.compose.ui.geometry.Offset(
                    center.x + (cos(perpendicular) * baseHalfWidth).toFloat(),
                    center.y + (sin(perpendicular) * baseHalfWidth).toFloat(),
                )
                val baseB = androidx.compose.ui.geometry.Offset(
                    center.x - (cos(perpendicular) * baseHalfWidth).toFloat(),
                    center.y - (sin(perpendicular) * baseHalfWidth).toFloat(),
                )
                drawPath(
                    Path().apply {
                        moveTo(baseA.x, baseA.y)
                        lineTo(needleTip.x, needleTip.y)
                        lineTo(baseB.x, baseB.y)
                        close()
                    },
                    brush = Brush.linearGradient(listOf(Amber, Red), start = center, end = needleTip),
                )
                drawCircle(Color(0xFF07141F), radius * 0.14f, center)
                drawCircle(Cyan, radius * 0.14f, center, style = Stroke(radius * 0.008f))
                if (drivetrain.isShifting) {
                    drawCircle(Green.copy(alpha = 0.55f), radius * 0.985f, center, style = Stroke(radius * 0.018f))
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(y = (-2).dp),
            ) {
                Text(
                    text = if (transmissionPosition == TransmissionPosition.DRIVE) {
                        drivetrain.gear.toString()
                    } else {
                        transmissionPosition.displayName
                    },
                    color = Cyan,
                    fontSize = 48.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    if (transmissionPosition == TransmissionPosition.DRIVE) {
                        "GEAR"
                    } else {
                        "RANGE"
                    },
                    color = CyanSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gaugeSize * 0.12f),
            ) {
                Text(
                    text = formatRpm(drivetrain.rpm),
                    color = if (drivetrain.limiterActive) Red else Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 46.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                )
                Text("RPM", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            if (drivetrain.isShifting) {
                Text(
                    text = if (drivetrain.shiftDirection.name == "UP") "PERFECT SHIFT" else "REV MATCH",
                    color = Green,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = gaugeSize * 0.08f),
                )
            }
        }
    }
}

@Composable
private fun DashboardFooter(state: DriveSnapshot, viewport: String) {
    val audio = state.audio
    val audioStatus = when {
        !state.engineSoundEnabled -> "OFF"
        audio.running -> audio.activeLayout
        audio.error != null -> audio.error
        else -> "NEGOTIATING"
    }
    val audioStatusColor = when {
        !state.engineSoundEnabled -> Muted
        audio.running -> Green
        audio.error != null -> Red
        else -> Amber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Line.copy(alpha = 0.45f))
            .padding(horizontal = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FooterMetric("AUDIO", audioStatus, audioStatusColor)
        FooterMetric("SAMPLE BANK", audio.sampleStatus, if (audio.sampleStatus == "ACTIVE") Green else Amber)
        FooterMetric("ROUTE", audio.routedDevice, CyanSoft, Modifier.weight(1f))
        FooterMetric("FORMAT", if (audio.sampleRate > 0) "${audio.sampleRate / 1000} kHz • ${audio.bufferFrames}f • ${audio.steadyStateUnderruns} new underruns" else "NEGOTIATING", CyanSoft)
        FooterMetric("SESSION", if (audio.sessionId > 0) audio.sessionId.toString() else "—", CyanSoft)
        FooterMetric("VIEWPORT", "$viewport px", Muted)
    }
}

@Composable
private fun FooterMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        Text(value, color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun polar(
    center: androidx.compose.ui.geometry.Offset,
    radius: Float,
    angleDegrees: Float,
): androidx.compose.ui.geometry.Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return androidx.compose.ui.geometry.Offset(
        center.x + (cos(radians) * radius).toFloat(),
        center.y + (sin(radians) * radius).toFloat(),
    )
}

private fun formatRpm(rpm: Double): String = ((rpm / 10.0).roundToInt() * 10).toString()

private fun formatWhole(value: Double): String = value.roundToInt().toString()
