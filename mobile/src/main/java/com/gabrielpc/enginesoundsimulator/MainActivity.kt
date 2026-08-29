package com.gabrielpc.enginesoundsimulator

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gabrielpc.enginesoundsimulator.drive.DriveController
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.drive.UserVisibleMessage
import com.gabrielpc.enginesoundsimulator.drive.InputMode
import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.CarMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
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
private val RealPedalsAccent = Color(0xFF43BD84)
private val Red = Color(0xFFFF394F)
private val Amber = Color(0xFFFFC456)
private val White = Color(0xFFF5FAFD)
private val Muted = Color(0xFF88A2B2)
private val StartStopRedHighlight = Color(0xFF9E1E28)
private val StartStopRedBody = Color(0xFF6E1018)
private val StartStopRedShadow = Color(0xFF3A070D)
private val StartStopGreenDark = Color(0xFF0E8F42)
private val StartStopGreenGlow = Color(0xFF34F07A)
private val StartStopGreenHot = Color(0xFF5CFF9A)
private val StartStopIndicatorOff = Color(0xFF2E080E)

class MainActivity : ComponentActivity() {
    private val controller: DriveController
        get() = (application as EngineSoundsApplication).driveController

    private val choreographer by lazy(LazyThreadSafetyMode.NONE) { Choreographer.getInstance() }
    private var driveState by mutableStateOf<DriveSnapshot?>(null)
    private var uiMonitoringActive by mutableStateOf(false)

    private val refreshUi = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!uiMonitoringActive) {
                return
            }

            driveState = controller.snapshot()
            choreographer.postFrameCallback(this)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && controller.handleShiftKey(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driveState = controller.snapshot()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        setContent {
            EngineSoundsSimulatorTheme(darkTheme = true, dynamicColor = false) {
                driveState?.let { state ->
                    MotorSoundDashboard(
                        state = state,
                        uiMonitoringActive = uiMonitoringActive,
                        onThrottle = controller::setSimulatedPedalThrottle,
                        onBrake = controller::setSimulatedPedalBrake,
                        onSelectSimulatedPedals = controller::selectSimulatedPedals,
                        onSelectRealPedals = controller::selectRealPedals,
                        onToggleInputSource = controller::toggleInputSource,
                        onTransmissionChange = controller::setTransmissionPosition,
                        onToggleSound = controller::toggleSound,
                        onTogglePopsAndBangs = controller::togglePopsAndBangs,
                        onPopsAndBangsGainChange = controller::setPopsAndBangsGain,
                        onToggleSharedShiftSounds = controller::toggleSharedShiftSounds,
                        onSharedShiftSoundsGainChange = controller::setSharedShiftSoundsGain,
                        onToggleManualShiftMode = controller::toggleManualShiftMode,
                        onManualUpshift = controller::requestManualUpshift,
                        onManualDownshift = controller::requestManualDownshift,
                        onToggleAppMute = controller::toggleAppMute,
                        onDecreaseMasterVolume = controller::decreaseAppMasterVolume,
                        onIncreaseMasterVolume = controller::increaseAppMasterVolume,
                        onConfigChange = controller::setTuning,
                        onResetTuning = controller::resetTuning,
                        onPreviousCar = controller::selectPreviousCar,
                        onNextCar = controller::selectNextCar,
                        onSelectCar = controller::selectCar,
                        onLayerMixMuted = controller::setLayerMixMuted,
                        onLayerMixSolo = controller::setLayerMixSolo,
                        onLayerMixVolume = controller::setLayerMixVolume,
                        onCarMasterVolumeChange = controller::setCarMasterVolume,
                        onDismissUserMessage = controller::dismissUserMessage,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(EngineRuntimeService.stopIntent(this))
        controller.setUiActive(true)
        uiMonitoringActive = true
        if (!controller.isRunning()) {
            controller.start()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        choreographer.removeFrameCallback(refreshUi)
        choreographer.postFrameCallback(refreshUi)
        driveState = controller.snapshot()
    }

    override fun onStop() {
        releaseManualControls()
        uiMonitoringActive = false
        controller.setUiActive(false)
        choreographer.removeFrameCallback(refreshUi)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startService(EngineRuntimeService.startIntent(this))
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            (application as EngineSoundsApplication).shutdownEngine()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseManualControls()
    }

    private fun releaseManualControls() {
        controller.setSimulatedPedalThrottle(0.0)
        controller.setSimulatedPedalBrake(0.0)
    }

}

@Composable
private fun MotorSoundDashboard(
    state: DriveSnapshot,
    uiMonitoringActive: Boolean,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onSelectSimulatedPedals: () -> Unit,
    onSelectRealPedals: () -> Unit,
    onToggleInputSource: () -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onToggleSound: () -> Unit,
    onTogglePopsAndBangs: () -> Unit,
    onPopsAndBangsGainChange: (Double) -> Unit,
    onToggleSharedShiftSounds: () -> Unit,
    onSharedShiftSoundsGainChange: (Double) -> Unit,
    onToggleManualShiftMode: () -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    onToggleAppMute: () -> Unit,
    onDecreaseMasterVolume: () -> Unit,
    onIncreaseMasterVolume: () -> Unit,
    onConfigChange: (TuningConfig) -> Unit,
    onResetTuning: () -> Unit,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
    onSelectCar: (String) -> Unit,
    onLayerMixMuted: (String, Boolean) -> Unit,
    onLayerMixSolo: (String, Boolean) -> Unit,
    onLayerMixVolume: (String, Double) -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    onDismissUserMessage: () -> Unit,
) {
    var tuningOpen by remember { mutableStateOf(false) }
    var mainScreen by remember { mutableStateOf(DashboardMainScreen.CLASSIC) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (tuningOpen) return@onPreviewKeyEvent false
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
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (pressed && state.manualShiftModeEnabled) {
                            onManualUpshift()
                        }
                        state.manualShiftModeEnabled
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    -> {
                        if (pressed && state.manualShiftModeEnabled) {
                            onManualDownshift()
                        }
                        state.manualShiftModeEnabled
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
                Column(modifier = Modifier.fillMaxSize()) {
                    DashboardHeader(
                        state = state,
                        uiMonitoringActive = uiMonitoringActive,
                        mainScreen = mainScreen,
                        onMainScreenChange = { screen ->
                            mainScreen = screen
                        },
                        onSelectSimulatedPedals = onSelectSimulatedPedals,
                        onSelectRealPedals = onSelectRealPedals,
                        onToggleInputSource = onToggleInputSource,
                        onToggleManualShiftMode = onToggleManualShiftMode,
                        onToggleAppMute = onToggleAppMute,
                        onDecreaseMasterVolume = onDecreaseMasterVolume,
                        onIncreaseMasterVolume = onIncreaseMasterVolume,
                        onOpenTuning = { tuningOpen = true },
                    )

                    state.userMessage?.let { message ->
                        DismissableUserMessageBanner(
                            message = message,
                            onDismiss = onDismissUserMessage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 34.dp, vertical = 8.dp),
                        )
                    }

                    when (mainScreen) {
                        DashboardMainScreen.CLASSIC -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 34.dp, vertical = 6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CarStage(
                                    state = state,
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
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                EngineStartStopButton(
                                    running = state.engineSoundEnabled,
                                    loading = state.engineStartLoading,
                                    onClick = onToggleSound,
                                )
                                DashboardEffectToggle(
                                    label = "Pops & Bangs",
                                    enabled = state.popsAndBangsEnabled,
                                    gain = state.popsAndBangsGain,
                                    onToggle = onTogglePopsAndBangs,
                                    onGainChange = onPopsAndBangsGainChange,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                                DashboardEffectToggle(
                                    label = "Shift Sounds",
                                    enabled = state.sharedShiftSoundsEnabled,
                                    gain = state.sharedShiftSoundsGain,
                                    onToggle = onToggleSharedShiftSounds,
                                    onGainChange = onSharedShiftSoundsGainChange,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    ClassicDriveControls(
                                        state = state,
                                        onThrottle = onThrottle,
                                        onBrake = onBrake,
                                        onTransmissionChange = onTransmissionChange,
                                        onManualUpshift = onManualUpshift,
                                        onManualDownshift = onManualDownshift,
                                        modifier = Modifier.offset(x = (-64).dp),
                                    )
                                }
                            }
                            DashboardMixerLauncherButton(
                                onClick = { mainScreen = DashboardMainScreen.MIXER },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 4.dp, bottom = 12.dp),
                            )
                        }
                        DashboardMainScreen.MIXER -> MixerDashboardScreen(
                            state = state,
                            onThrottle = onThrottle,
                            onBrake = onBrake,
                            onTransmissionChange = onTransmissionChange,
                            onSelectCar = onSelectCar,
                            onCarMasterVolumeChange = onCarMasterVolumeChange,
                            onLayerMuted = onLayerMixMuted,
                            onLayerSolo = onLayerMixSolo,
                            onLayerVolume = onLayerMixVolume,
                            onManualUpshift = onManualUpshift,
                            onManualDownshift = onManualDownshift,
                            coastLayerMixEnabled = state.coastLayerMixEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }

                if (tuningOpen) {
                    TuningPanel(
                        state = state,
                        onConfigChange = onConfigChange,
                        onReset = onResetTuning,
                        onClose = { tuningOpen = false },
                    )
                }

            }
        }
    }
}

@Composable
private fun DashboardHeader(
    state: DriveSnapshot,
    uiMonitoringActive: Boolean,
    mainScreen: DashboardMainScreen,
    onMainScreenChange: (DashboardMainScreen) -> Unit,
    onSelectSimulatedPedals: () -> Unit,
    onSelectRealPedals: () -> Unit,
    onToggleInputSource: () -> Unit,
    onToggleManualShiftMode: () -> Unit,
    onToggleAppMute: () -> Unit,
    onDecreaseMasterVolume: () -> Unit,
    onIncreaseMasterVolume: () -> Unit,
    onOpenTuning: () -> Unit,
) {
    var memoryLabels by remember {
        mutableStateOf(MemoryHeaderLabels(usageLabel = "— MB", availableLabel = "— MB left"))
    }
    var cpuLabel by remember { mutableStateOf("—% CPU") }
    val context = LocalContext.current

    LaunchedEffect(uiMonitoringActive, Unit) {
        if (!uiMonitoringActive) {
            return@LaunchedEffect
        }

        val startupBurstEndsAtMs = System.currentTimeMillis() + HEADER_MEMORY_STARTUP_BURST_MS
        while (uiMonitoringActive) {
            memoryLabels = AppMemoryUsage.readHeaderLabels(context)
            val refreshMs = if (System.currentTimeMillis() < startupBurstEndsAtMs) {
                HEADER_MEMORY_STARTUP_REFRESH_MS
            } else {
                HEADER_MEMORY_REFRESH_MS
            }
            delay(refreshMs)
        }
    }

    LaunchedEffect(uiMonitoringActive, Unit) {
        if (!uiMonitoringActive) {
            return@LaunchedEffect
        }

        AppCpuUsage.primeSample()
        while (uiMonitoringActive) {
            delay(HEADER_CPU_REFRESH_MS)
            cpuLabel = AppCpuUsage.sampleLabel()
        }
    }

    LaunchedEffect(uiMonitoringActive, state.selectedCarId, state.carAudioReady) {
        if (!uiMonitoringActive) {
            return@LaunchedEffect
        }

        if (!state.carAudioReady) {
            return@LaunchedEffect
        }

        withFrameNanos { }
        memoryLabels = AppMemoryUsage.readHeaderLabels(context)
    }

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
            if (mainScreen == DashboardMainScreen.MIXER) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to classic dashboard",
                    tint = Cyan,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            onMainScreenChange(DashboardMainScreen.CLASSIC)
                        },
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(if (state.engineSoundEnabled) Green else Red),
                )
            }
            Text(
                text = "ENGINE",
                color = White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.0.sp,
            )
            Text(
                text = "// SIMULATOR",
                color = Cyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.0.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusTag(memoryLabels.usageLabel, Muted)
                Text(
                    text = memoryLabels.availableLabel,
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = cpuLabel,
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    lineHeight = 12.sp,
                )
            }
            if (state.manualShiftModeEnabled) {
                StatusTag("MANUAL", CyanSoft)
            }
            if (state.legacyThrottleMixEnabled) {
                StatusTag("LEGACY MIX", Amber)
            }
        }

        HeaderIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "Open tuning",
            accent = White,
            onClick = onOpenTuning,
        )
        PedalsInputHeaderControl(
            state = state,
            onSelectSimulated = onSelectSimulatedPedals,
            onSelectReal = onSelectRealPedals,
            onToggle = onToggleInputSource,
        )
        ManualShiftHeaderControl(
            manualEnabled = state.manualShiftModeEnabled,
            onToggle = onToggleManualShiftMode,
        )
        MasterVolumeControls(
            volume = state.appMasterVolume,
            muted = state.appMuted,
            onDecrease = onDecreaseMasterVolume,
            onIncrease = onIncreaseMasterVolume,
            onToggleMute = onToggleAppMute,
        )
    }
}

@Composable
private fun MasterVolumeControls(
    volume: Double,
    muted: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onToggleMute: () -> Unit,
) {
    var activeFeedback by remember { mutableStateOf<VolumeStep?>(null) }
    var feedbackPercentLabel by remember { mutableStateOf("") }

    LaunchedEffect(activeFeedback) {
        if (activeFeedback == null) {
            return@LaunchedEffect
        }
        delay(1_500)
        activeFeedback = null
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VolumeStepButton(
            icon = Icons.AutoMirrored.Filled.VolumeDown,
            sign = "−",
            showPercent = activeFeedback == VolumeStep.DOWN,
            percentLabel = feedbackPercentLabel,
            contentDescription = "Decrease master volume",
            onClick = {
                onDecrease()
                val updatedVolume = (volume - MASTER_VOLUME_HEADER_STEP)
                    .coerceIn(AppMasterVolumeRepository.MIN, AppMasterVolumeRepository.MAX)
                feedbackPercentLabel = "${(updatedVolume * 100.0).roundToInt()}%"
                activeFeedback = VolumeStep.DOWN
            },
        )
        VolumeStepButton(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            sign = "+",
            showPercent = activeFeedback == VolumeStep.UP,
            percentLabel = feedbackPercentLabel,
            contentDescription = "Increase master volume",
            onClick = {
                onIncrease()
                val updatedVolume = (volume + MASTER_VOLUME_HEADER_STEP)
                    .coerceIn(AppMasterVolumeRepository.MIN, AppMasterVolumeRepository.MAX)
                feedbackPercentLabel = "${(updatedVolume * 100.0).roundToInt()}%"
                activeFeedback = VolumeStep.UP
            },
        )
        HeaderButton(
            primary = if (muted) {
                "UNMUTE"
            } else {
                "MUTE"
            },
            accent = if (muted) Green else Red,
            onClick = onToggleMute,
        )
    }
}

private enum class VolumeStep {
    DOWN,
    UP,
}

private const val MASTER_VOLUME_HEADER_STEP = 0.10
private const val HEADER_MEMORY_STARTUP_BURST_MS = 10_000L
private const val HEADER_MEMORY_STARTUP_REFRESH_MS = 250L
private const val HEADER_MEMORY_REFRESH_MS = 15_000L
private const val HEADER_CPU_REFRESH_MS = 1_000L

@Composable
private fun ManualShiftHeaderControl(
    manualEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "SHIFT:",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        PedalsInputToggle(
            realSelected = manualEnabled,
            realPedalsActive = manualEnabled,
            enabled = true,
            onToggle = onToggle,
        )
        Text(
            text = "MANUAL",
            color = if (manualEnabled) {
                CyanSoft
            } else {
                Muted
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            modifier = Modifier.clickable {
                if (!manualEnabled) {
                    onToggle()
                }
            },
        )
    }
}

@Composable
private fun PedalsInputHeaderControl(
    state: DriveSnapshot,
    onSelectSimulated: () -> Unit,
    onSelectReal: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "${InputMode.SimulatedPedals.secondaryLabel}:",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Text(
            text = InputMode.SimulatedPedals.primaryLabel,
            color = Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            modifier = Modifier.clickable(onClick = onSelectSimulated),
        )
        PedalsInputToggle(
            realSelected = state.inputSourceIsRealPedals,
            realPedalsActive = state.inputSourceIsRealPedals,
            enabled = !state.inputSourceFaded,
            onToggle = onToggle,
        )
        Text(
            text = InputMode.RealPedals.primaryLabel,
            color = RealPedalsAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .alpha(if (state.inputSourceFaded) {
                    0.42f
                } else {
                    1f
                })
                .clickable(
                    enabled = !state.inputSourceFaded,
                    onClick = onSelectReal,
                ),
        )
    }
}

@Composable
private fun PedalsInputToggle(
    realSelected: Boolean,
    realPedalsActive: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val thumbProgress by animateFloatAsState(
        targetValue = if (realSelected) {
            1f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 180),
        label = "pedalsInputToggle",
    )
    val trackWidth = 46.dp
    val trackHeight = 24.dp
    val thumbSize = 18.dp
    val trackInset = 3.dp
    val trackColor = if (realPedalsActive) {
        RealPedalsAccent
    } else {
        Line
    }

    BoxWithConstraints(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .alpha(if (enabled) {
                1f
            } else {
                0.42f
            })
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .clickable(
                enabled = enabled,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = maxWidth - thumbSize - trackInset * 2
        Box(
            modifier = Modifier
                .padding(start = trackInset)
                .offset(x = travel * thumbProgress)
                .size(thumbSize)
                .clip(CircleShape)
                .background(White),
        )
    }
}

@Composable
private fun VolumeStepButton(
    icon: ImageVector,
    sign: String,
    showPercent: Boolean,
    percentLabel: String,
    contentDescription: String,
    accent: Color = Cyan,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = White),
        modifier = Modifier
            .height(52.dp)
            .width(50.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.alpha(if (showPercent) {
                    0f
                } else {
                    1f
                }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = sign,
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Text(
                text = percentLabel,
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(if (showPercent) {
                        1f
                    } else {
                        0f
                    })
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    accent: Color = Cyan,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = accent),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HeaderButton(
    primary: String,
    secondary: String? = null,
    accent: Color = Cyan,
    contentAlpha: Float = 1f,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = White),
        modifier = Modifier
            .height(52.dp)
            .alpha(contentAlpha),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(primary, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (secondary != null) {
                Text(secondary, color = Muted, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
            }
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

private const val CLASSIC_DRIVE_CONTROL_SCALE = 0.7f
private const val MIXER_DRIVE_CONTROL_SCALE = 0.60f

private fun Float.scaledDp(base: Int): Dp = (base * this).dp

@Composable
private fun ClassicDriveControls(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CLASSIC_DRIVE_CONTROL_SCALE.scaledDp(18)),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (state.manualShiftModeEnabled && !state.inputSourceIsRealPedals) {
            ManualShiftButtons(
                onUpshift = onManualUpshift,
                onDownshift = onManualDownshift,
                scale = CLASSIC_DRIVE_CONTROL_SCALE,
            )
        }
        PedalControl(
            label = "BRAKE",
            value = state.brake,
            accent = Red,
            width = CLASSIC_DRIVE_CONTROL_SCALE.scaledDp(92),
            height = CLASSIC_DRIVE_CONTROL_SCALE.scaledDp(154),
            contentScale = CLASSIC_DRIVE_CONTROL_SCALE,
            onValue = onBrake,
        )
        PedalControl(
            label = "THROTTLE",
            value = state.throttle,
            accent = Green,
            width = CLASSIC_DRIVE_CONTROL_SCALE.scaledDp(84),
            height = CLASSIC_DRIVE_CONTROL_SCALE.scaledDp(202),
            contentScale = CLASSIC_DRIVE_CONTROL_SCALE,
            onValue = onThrottle,
        )
        TransmissionShifter(
            position = state.transmissionPosition,
            onPositionChange = onTransmissionChange,
            lockedToVehicle = state.transmissionLockedToVehicle,
            scale = CLASSIC_DRIVE_CONTROL_SCALE,
        )
    }
}

@Composable
private fun ManualShiftButtons(
    onUpshift: () -> Unit,
    onDownshift: () -> Unit,
    scale: Float,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(scale.scaledDp(8)),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = scale.scaledDp(6)),
    ) {
        ManualShiftButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "Upshift",
            accent = Green,
            size = scale.scaledDp(56),
            contentScale = scale,
            onClick = onUpshift,
        )
        ManualShiftButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Downshift",
            accent = Red,
            size = scale.scaledDp(56),
            contentScale = scale,
            onClick = onDownshift,
        )
    }
}

@Composable
private fun ManualShiftButton(
    icon: ImageVector,
    contentDescription: String,
    accent: Color,
    size: Dp,
    contentScale: Float,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape((16f * contentScale).dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5B6670), Color(0xFF232D35), Color(0xFF11181E)),
                ),
            )
            .border(
                (2f * contentScale).dp,
                if (active) {
                    accent
                } else {
                    Color(0xFF60717D)
                },
                RoundedCornerShape((16f * contentScale).dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.45f)),
                        ),
                    ),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) {
                accent
            } else {
                Muted
            },
            modifier = Modifier.size((28f * contentScale).dp),
        )
    }
}

@Composable
internal fun MixerDriveControls(
    state: DriveSnapshot,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MIXER_DRIVE_CONTROL_SCALE.scaledDp(12)),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (state.manualShiftModeEnabled && !state.inputSourceIsRealPedals) {
            ManualShiftButtons(
                onUpshift = onManualUpshift,
                onDownshift = onManualDownshift,
                scale = MIXER_DRIVE_CONTROL_SCALE,
            )
        }
        PedalControl(
            label = "BRAKE",
            value = state.brake,
            accent = Red,
            width = MIXER_DRIVE_CONTROL_SCALE.scaledDp(92),
            height = MIXER_DRIVE_CONTROL_SCALE.scaledDp(154),
            contentScale = MIXER_DRIVE_CONTROL_SCALE,
            onValue = onBrake,
        )
        PedalControl(
            label = "THROTTLE",
            value = state.throttle,
            accent = Green,
            width = MIXER_DRIVE_CONTROL_SCALE.scaledDp(84),
            height = MIXER_DRIVE_CONTROL_SCALE.scaledDp(202),
            contentScale = MIXER_DRIVE_CONTROL_SCALE,
            onValue = onThrottle,
        )
        TransmissionShifter(
            position = state.transmissionPosition,
            onPositionChange = onTransmissionChange,
            lockedToVehicle = state.transmissionLockedToVehicle,
            scale = MIXER_DRIVE_CONTROL_SCALE,
        )
    }
}

@Composable
private fun CarStage(
    state: DriveSnapshot,
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
                text = state.selectedCarName.uppercase(),
                color = White,
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = EngineSampleProfiles.specificationsFor(state.selectedCarId).summary(),
                color = CyanSoft,
                fontSize = 12.sp,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
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
    }
}

@Composable
internal fun TransmissionShifter(
    position: TransmissionPosition,
    onPositionChange: (TransmissionPosition) -> Unit,
    lockedToVehicle: Boolean = false,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width((58f * scale).dp)
            .height((202f * scale).dp)
            .clip(RoundedCornerShape((16f * scale).dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5B6670), Color(0xFF232D35), Color(0xFF11181E)),
                ),
            )
            .border((2f * scale).dp, if (lockedToVehicle) Green.copy(alpha = 0.75f) else Color(0xFF60717D), RoundedCornerShape((16f * scale).dp))
            .padding((8f * scale).dp)
            .alpha(if (lockedToVehicle) 0.88f else 1f),
        verticalArrangement = Arrangement.spacedBy((6f * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lockedToVehicle) {
            Text(
                text = "BYD",
                color = Green,
                fontSize = (8f * scale).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
            )
        }

        TransmissionPosition.entries.forEach { option ->
            val selected = option == position
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((52f * scale).dp)
                    .clip(RoundedCornerShape((10f * scale).dp))
                    .background(
                        if (selected) {
                            Cyan.copy(alpha = 0.22f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .border(
                        width = if (selected) (2f * scale).dp else (1f * scale).dp,
                        color = if (selected) Cyan else Color(0xFF4A5A66),
                        shape = RoundedCornerShape((10f * scale).dp),
                    )
                    .then(
                        if (lockedToVehicle) {
                            Modifier
                        } else {
                            Modifier.clickable { onPositionChange(option) }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.displayName,
                    color = if (selected) Cyan else Muted,
                    fontSize = (22f * scale).sp,
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
internal fun PedalControl(
    label: String,
    value: Double,
    accent: Color,
    width: Dp,
    height: Dp,
    onValue: (Double) -> Unit,
    contentScale: Float = 1f,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape((16f * contentScale).dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5B6670), Color(0xFF232D35), Color(0xFF11181E)),
                ),
            )
            .border((2f * contentScale).dp, if (value > 0.01) accent else Color(0xFF60717D), RoundedCornerShape((16f * contentScale).dp))
            .pointerInput(onValue) {
                awaitEachGesture {
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
                .padding(horizontal = (12f * contentScale).dp, vertical = (14f * contentScale).dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((6f * contentScale).dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }
            Text(
                text = "${(value * 100).roundToInt()}%",
                color = if (value > 0.01) accent else White,
                fontSize = (15f * contentScale).sp,
                fontWeight = FontWeight.Black,
            )
            Text(label, color = Muted, fontSize = (9f * contentScale).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }
    }
}

@Composable
private fun DismissableUserMessageBanner(
    message: UserVisibleMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(StartStopRedBody.copy(alpha = 0.94f))
            .border(1.dp, Red.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message.title,
                color = White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = message.detail,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss message",
                tint = White,
            )
        }
    }
}

@Composable
private fun DashboardEffectToggle(
    label: String,
    enabled: Boolean,
    gain: Double,
    onToggle: () -> Unit,
    onGainChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = if (enabled) {
        Cyan.copy(alpha = 0.92f)
    } else {
        Line
    }
    val thumbProgress by animateFloatAsState(
        targetValue = if (enabled) {
            1f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 180),
        label = "${label}Toggle",
    )
    val trackWidth = 46.dp
    val trackHeight = 24.dp
    val thumbSize = 18.dp
    val trackInset = 3.dp
    val accentColor = if (enabled) {
        Cyan
    } else {
        Muted
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp,
            modifier = Modifier.width(72.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .width(trackWidth)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.CenterStart,
        ) {
            val travel = maxWidth - thumbSize - trackInset * 2
            Box(
                modifier = Modifier
                    .padding(start = trackInset)
                    .offset(x = travel * thumbProgress)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(White),
            )
        }
        Slider(
            value = gain.toFloat(),
            onValueChange = { onGainChange(it.toDouble()) },
            valueRange = EngineAudioFrame.MIN_EFFECT_GAIN.toFloat()..EngineAudioFrame.MAX_EFFECT_GAIN.toFloat(),
            modifier = Modifier
                .width(72.dp)
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Line,
            ),
        )
        Text(
            text = String.format("%.1f×", gain),
            color = accentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun EngineStartStopButton(
    running: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonSize = 92.dp
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressDepth by animateFloatAsState(
        targetValue = if (pressed && !loading) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "startStopPressDepth",
    )
    val shadowLift = ((1f - pressDepth) * 3f).dp
    val faceOffsetY = (pressDepth * 2.5f).dp
    val faceScale = 1f - (pressDepth * 0.035f)
    val topHighlight = StartStopRedHighlight.copy(alpha = 0.72f + (0.28f * (1f - pressDepth)))
    val centerShineAlpha = 0.10f + (0.10f * (1f - pressDepth))

    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = shadowLift)
                .clip(CircleShape)
                .background(StartStopRedShadow.copy(alpha = 0.55f + (0.45f * (1f - pressDepth)))),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = faceOffsetY)
                .scale(faceScale)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (pressDepth > 0.01f) {
                            listOf(
                                StartStopRedShadow,
                                StartStopRedBody,
                                StartStopRedHighlight.copy(alpha = 0.85f),
                            )
                        } else {
                            listOf(
                                topHighlight,
                                StartStopRedBody,
                                StartStopRedShadow,
                            )
                        },
                    ),
                )
                .border(
                    width = (1.5f + pressDepth).dp,
                    color = StartStopRedShadow.copy(alpha = 0.85f + (0.15f * pressDepth)),
                    shape = CircleShape,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.42f)),
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = Cyan,
                    strokeWidth = 3.dp,
                )
            }
            if (!loading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = centerShineAlpha),
                                    Color.White.copy(alpha = centerShineAlpha * 0.35f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                ) {
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (running) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        StartStopGreenHot,
                                        StartStopGreenGlow,
                                        StartStopGreenDark,
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        StartStopIndicatorOff,
                                        StartStopRedShadow,
                                    ),
                                )
                            },
                        )
                        .border(
                            width = 0.5.dp,
                            color = if (running) {
                                StartStopGreenGlow.copy(alpha = 0.95f)
                            } else {
                                StartStopRedShadow
                            },
                            shape = RoundedCornerShape(50),
                        ),
                )
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = "START",
                    color = White.copy(alpha = 0.96f - (0.08f * pressDepth)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(1.5.dp)
                        .background(White.copy(alpha = 0.55f - (0.15f * pressDepth))),
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "STOP",
                    color = White.copy(alpha = 0.88f - (0.10f * pressDepth)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp,
                )
                }
            }
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
    TachometerGauge(
        drivetrain = drivetrain,
        transmissionPosition = transmissionPosition,
        maxRpm = maxRpm,
        redlineRpm = redlineRpm,
        upshiftRpm = upshiftRpm,
        modifier = modifier,
    )
}

@Composable
private fun TachometerGauge(
    drivetrain: DrivetrainState,
    transmissionPosition: TransmissionPosition,
    maxRpm: Double,
    redlineRpm: Double,
    upshiftRpm: Double,
    modifier: Modifier = Modifier,
) {
    val shakeIntensity = redlineShakeIntensity(
        rpm = drivetrain.rpm,
        redlineRpm = redlineRpm,
        maxRpm = maxRpm,
        limiterActive = drivetrain.limiterActive,
    )
    val redlineShake = rememberRedlineShakeMotion(shakeIntensity)

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
                    style = Stroke(stroke * 1.8f, cap = StrokeCap.Butt),
                )

                val zoneBandStroke = radius * 0.024f
                val zoneBandRadius = radius * 0.96f
                val zoneBandTopLeft = androidx.compose.ui.geometry.Offset(
                    center.x - zoneBandRadius,
                    center.y - zoneBandRadius,
                )
                val zoneBandSize = androidx.compose.ui.geometry.Size(zoneBandRadius * 2f, zoneBandRadius * 2f)
                val zoneBandStyle = Stroke(zoneBandStroke, cap = StrokeCap.Butt)

                drawArc(
                    color = Red,
                    startAngle = startAngle + sweepAngle * (redlineRpm / gaugeMaxRpm).toFloat().coerceIn(0f, 1f),
                    sweepAngle = sweepAngle * ((maxRpm - redlineRpm) / gaugeMaxRpm).toFloat().coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = zoneBandTopLeft,
                    size = zoneBandSize,
                    style = zoneBandStyle,
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

                val nominalNeedleAngle = startAngle + sweepAngle * rpmFraction
                val tipAngle = nominalNeedleAngle + redlineShake.needleTipAngleJitterDegrees
                val needleTip = polar(center, radius * 0.77f, tipAngle)
                val baseAngleRadians = Math.toRadians(nominalNeedleAngle.toDouble())
                val perpendicular = baseAngleRadians + PI / 2.0
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
                    text = if (transmissionPosition == TransmissionPosition.DRIVE) drivetrain.gear.toString() else transmissionPosition.displayName,
                    color = Cyan,
                    fontSize = 48.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gaugeSize * 0.12f),
            ) {
                Text(
                    text = formatWhole(drivetrain.rawSpeedKmh),
                    color = if (drivetrain.limiterActive) Red else Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 46.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                )
                Text("KM/H", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
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

private fun formatWhole(value: Double): String = value.roundToInt().toString()
