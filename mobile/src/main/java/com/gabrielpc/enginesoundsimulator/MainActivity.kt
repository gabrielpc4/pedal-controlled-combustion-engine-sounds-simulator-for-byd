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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.gabrielpc.enginesoundsimulator.drive.BackfireSettings
import com.gabrielpc.enginesoundsimulator.drive.UserVisibleMessage
import com.gabrielpc.enginesoundsimulator.drive.InputMode
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodBankResolver
import com.gabrielpc.enginesoundsimulator.audio.BackfirePreviewPlayer
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.ui.theme.EngineSoundsSimulatorTheme
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

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
private val ErrorBannerBody = Color(0xFF6E1018)

class MainActivity : ComponentActivity() {
    private val controller: DriveController
        get() = (application as EngineSoundsApplication).driveController

    private val choreographer by lazy(LazyThreadSafetyMode.NONE) { Choreographer.getInstance() }
    private var driveState by mutableStateOf<DriveSnapshot?>(null)
    private var uiMonitoringActive by mutableStateOf(false)
    private val backfirePreviewPlayer by lazy(LazyThreadSafetyMode.NONE) { BackfirePreviewPlayer(this) }

    private val refreshUi = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!uiMonitoringActive) {
                return
            }

            driveState = controller.snapshot()
            choreographer.postFrameCallback(this)
        }
    }

    @Suppress("RestrictedApi")
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
                        onSimulatedRegen = controller::setSimulatedRegen,
                        onToggleSimulatedPedalLatch = controller::setSimulatedPedalsLatched,
                        onTransmissionPositionChange = controller::setTransmissionPosition,
                        onSelectSimulatedPedals = controller::selectSimulatedPedals,
                        onSelectRealPedals = controller::selectRealPedals,
                        onToggleInputSource = controller::toggleInputSource,
                        onToggleAudioMute = controller::toggleAudioMute,
                        onResetAllPreferences = controller::resetAllPreferences,
                        onToggleManualShiftMode = controller::toggleManualShiftMode,
                        onManualUpshift = controller::requestManualUpshift,
                        onManualDownshift = controller::requestManualDownshift,
                        onHostGains = controller::setFmodHostGains,
                        onCategoryGains = controller::setFmodCategoryGains,
                        onToggleBackfireOnly = controller::setBackfireOnly,
                        onBackfireSettingsChange = controller::setBackfireSettings,
                        onPreviewBackfireSample = backfirePreviewPlayer::play,
                        onEventMute = controller::setFmodEventMute,
                        onEventSolo = controller::setFmodEventSolo,
                        onPreviousCar = controller::selectPreviousCar,
                        onNextCar = controller::selectNextCar,
                        onSelectCar = controller::selectCar,
                        onSoundPerspectiveChange = controller::setSoundPerspective,
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
        backfirePreviewPlayer.release()
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
    onSimulatedRegen: (Double) -> Unit,
    onToggleSimulatedPedalLatch: (Boolean) -> Unit,
    onTransmissionPositionChange: (TransmissionPosition) -> Unit,
    onSelectSimulatedPedals: () -> Unit,
    onSelectRealPedals: () -> Unit,
    onToggleInputSource: () -> Unit,
    onToggleAudioMute: () -> Boolean,
    onResetAllPreferences: () -> Unit,
    onToggleManualShiftMode: () -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    onHostGains: (Float, Float) -> Unit,
    onCategoryGains: (Float, Float, Float, Float) -> Unit,
    onToggleBackfireOnly: (Boolean) -> Unit,
    onBackfireSettingsChange: (BackfireSettings) -> Unit,
    onPreviewBackfireSample: (Int) -> Unit,
    onEventMute: (String, Boolean) -> Unit,
    onEventSolo: (String, Boolean) -> Unit,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
    onSelectCar: (String) -> Unit,
    onSoundPerspectiveChange: (com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective) -> Unit,
    onDismissUserMessage: () -> Unit,
) {
    var mainScreen by remember {
        mutableStateOf(
            if (RuntimeFeatureFlags.START_ON_MIXER) {
                DashboardMainScreen.MIXER
            } else {
                DashboardMainScreen.CLASSIC
            },
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
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
                        onToggleAudioMute = onToggleAudioMute,
                        onToggleManualShiftMode = onToggleManualShiftMode,
                        onOpenSettings = { mainScreen = DashboardMainScreen.SETTINGS },
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
                                    onSelectCar = onSelectCar,
                                    modifier = Modifier
                                        .weight(1.12f)
                                        .fillMaxHeight(),
                                )
                                Tachometer(
                                    drivetrain = state.drivetrain,
                                    transmissionPosition = state.transmissionPosition,
                                    maxRpm = state.drivetrain.tachometerMaximumRpm,
                                    redlineRpm = state.drivetrain.redlineRpm,
                                    upshiftRpm = state.drivetrain.automaticUpshiftRpm,
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
                                ClassicDriveControls(
                                    state = state,
                                    onThrottle = onThrottle,
                                    onBrake = onBrake,
                                    onSimulatedRegen = onSimulatedRegen,
                                    onToggleSimulatedPedalLatch = { onToggleSimulatedPedalLatch(!state.simulatedPedalsLatched) },
                                    onTransmissionPositionChange = onTransmissionPositionChange,
                                    onManualUpshift = onManualUpshift,
                                    onManualDownshift = onManualDownshift,
                                    modifier = Modifier.padding(start = 28.dp),
                                )
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
                            onSimulatedRegen = onSimulatedRegen,
                            onToggleSimulatedPedalLatch = { onToggleSimulatedPedalLatch(!state.simulatedPedalsLatched) },
                            onTransmissionPositionChange = onTransmissionPositionChange,
                            onSelectCar = onSelectCar,
                            soundPerspective = state.soundPerspective,
                            onSoundPerspectiveChange = onSoundPerspectiveChange,
                            onManualUpshift = onManualUpshift,
                            onManualDownshift = onManualDownshift,
                            onHostGains = onHostGains,
                            onCategoryGains = onCategoryGains,
                            onBackfireOnlyChange = onToggleBackfireOnly,
                            onEventMute = onEventMute,
                            onEventSolo = onEventSolo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        DashboardMainScreen.SETTINGS -> SettingsScreen(
                            onBack = { mainScreen = DashboardMainScreen.CLASSIC },
                            onResetAll = onResetAllPreferences,
                            backfireSettings = state.backfireSettings,
                            onBackfireSettingsChange = onBackfireSettingsChange,
                            onPreviewBackfireSample = onPreviewBackfireSample,
                        )
                    }
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
    onToggleAudioMute: () -> Boolean,
    onToggleManualShiftMode: () -> Unit,
    onOpenSettings: () -> Unit,
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
            StatusTag("BUILD ${AppBuildInfo.buildNumber}", CyanSoft)
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
        }

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
        MasterMuteHeaderControl(
            muted = state.audioMuted,
            onToggle = onToggleAudioMute,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Cyan)
        }
    }
}

@Composable
private fun MasterMuteHeaderControl(
    muted: Boolean,
    onToggle: () -> Boolean,
) {
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (muted) Red.copy(alpha = 0.18f) else Panel)
            .border(1.dp, if (muted) Red.copy(alpha = 0.65f) else Line, RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeDown else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (muted) "Unmute and reset audio engine" else "Mute audio",
            tint = if (muted) Red else Cyan,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = if (muted) "UNMUTE" else "MUTE",
            color = if (muted) Red else White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.7.sp,
        )
    }
}
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

/**
 * Keeps simulated pedal percentages after a pointer release. This is intentionally a runtime
 * control: REAL PEDALS remain telemetry-authoritative, and disabling it immediately clears both
 * virtual pedals so an old test value cannot silently remain applied.
 */
@Composable
private fun SimulatedPedalLatchToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    scale: Float,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(scale.scaledDp(5)),
        modifier = Modifier.padding(bottom = scale.scaledDp(6)),
    ) {
        Text(
            text = "HOLD PEDALS",
            color = if (enabled) Cyan else Muted,
            fontSize = (11f * scale).sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (0.8f * scale).sp,
        )
        Box(
            modifier = Modifier
                .width(scale.scaledDp(62))
                .height(scale.scaledDp(28))
                .clip(RoundedCornerShape(50))
                .background(if (enabled) Cyan else Line)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = scale.scaledDp(4))
                    .offset(x = if (enabled) scale.scaledDp(30) else 0.dp)
                    .size(scale.scaledDp(20))
                    .clip(CircleShape)
                    .background(White),
            )
        }
    }
}

@Composable
private fun SimulatedRegenControl(
    value: Double,
    onValue: (Double) -> Unit,
    scale: Float,
) {
    Column(
        modifier = Modifier.width(scale.scaledDp(118)).padding(bottom = scale.scaledDp(4)),
        verticalArrangement = Arrangement.spacedBy(scale.scaledDp(2)),
    ) {
        Text(
            text = "REGEN ${"%.0f".format(Locale.US, value * 100.0)}%",
            color = CyanSoft,
            fontSize = (10f * scale).sp,
            fontWeight = FontWeight.Black,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toDouble()) },
            valueRange = 0f..1f,
            modifier = Modifier.height(scale.scaledDp(30)),
        )
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
    onSimulatedRegen: (Double) -> Unit,
    onToggleSimulatedPedalLatch: () -> Unit,
    onTransmissionPositionChange: (TransmissionPosition) -> Unit,
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
        if (!state.inputSourceIsRealPedals) {
            SimulatedPedalLatchToggle(
                enabled = state.simulatedPedalsLatched,
                onToggle = onToggleSimulatedPedalLatch,
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
        if (!state.inputSourceIsRealPedals) {
            SimulatedRegenControl(state.simulatedRegen, onSimulatedRegen, CLASSIC_DRIVE_CONTROL_SCALE)
        }
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
            lockedToVehicle = state.transmissionLockedToVehicle,
            scale = CLASSIC_DRIVE_CONTROL_SCALE,
            onPositionSelected = if (state.inputSourceIsRealPedals) null else onTransmissionPositionChange,
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
    onSimulatedRegen: (Double) -> Unit,
    onToggleSimulatedPedalLatch: () -> Unit,
    onTransmissionPositionChange: (TransmissionPosition) -> Unit,
    onManualUpshift: () -> Unit,
    onManualDownshift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MIXER_DRIVE_CONTROL_SCALE.scaledDp(16)),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Keep the mixer diagnostics paired with the same live tachometer shown on the classic
        // dashboard, so the pedal test has an immediate RPM reference without leaving the mixer.
        Tachometer(
            drivetrain = state.drivetrain,
            transmissionPosition = state.transmissionPosition,
            maxRpm = state.drivetrain.tachometerMaximumRpm,
            redlineRpm = state.drivetrain.redlineRpm,
            upshiftRpm = state.drivetrain.automaticUpshiftRpm,
            modifier = Modifier.size(MIXER_DRIVE_CONTROL_SCALE.scaledDp(808)),
        )
        if (state.manualShiftModeEnabled && !state.inputSourceIsRealPedals) {
            ManualShiftButtons(
                onUpshift = onManualUpshift,
                onDownshift = onManualDownshift,
                scale = MIXER_DRIVE_CONTROL_SCALE,
            )
        }
        if (!state.inputSourceIsRealPedals) {
            SimulatedPedalLatchToggle(
                enabled = state.simulatedPedalsLatched,
                onToggle = onToggleSimulatedPedalLatch,
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
        if (!state.inputSourceIsRealPedals) {
            SimulatedRegenControl(state.simulatedRegen, onSimulatedRegen, MIXER_DRIVE_CONTROL_SCALE)
        }
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
            lockedToVehicle = state.transmissionLockedToVehicle,
            scale = MIXER_DRIVE_CONTROL_SCALE,
            onPositionSelected = if (state.inputSourceIsRealPedals) null else onTransmissionPositionChange,
        )
    }
}

@Composable
private fun CarStage(
    state: DriveSnapshot,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
    onSelectCar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var carPickerExpanded by remember { mutableStateOf(false) }
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
                text = "ORIGINAL ASSETTO CORSA DATA",
                color = CyanSoft,
                fontSize = 12.sp,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        val context = LocalContext.current
        val audioResolver = remember(context) { FmodBankResolver(context.applicationContext) }
        val selectedProfile = remember(state.selectedCarId) { FmodBankProfiles.find(state.selectedCarId) }
        val installedPreviewPath = audioResolver.previewFile(selectedProfile)?.path
        val preview = remember(state.selectedCarId, installedPreviewPath) {
            runCatching {
                audioResolver.openCarPreviewInput(selectedProfile)?.use { input ->
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
                    .offset(y = (-46).dp)
                    .clickable { carPickerExpanded = true },
            )
        } else {
            Image(
                painter = painterResource(R.drawable.apex_v10_car),
                contentDescription = state.selectedCarName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .fillMaxHeight(0.62f)
                    .align(Alignment.Center)
                    .offset(y = (-46).dp)
                    .clickable { carPickerExpanded = true },
            )
        }

        if (carPickerExpanded) {
            CarGridSelectionDialog(
                selectedCarId = state.selectedCarId,
                onSelectCar = onSelectCar,
                onDismiss = { carPickerExpanded = false },
            )
        }

        CarSelectorArrow("‹", "Previous car", onPreviousCar, Modifier.align(Alignment.CenterStart))
        CarSelectorArrow("›", "Next car", onNextCar, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
internal fun TransmissionShifter(
    position: TransmissionPosition,
    lockedToVehicle: Boolean = false,
    onPositionSelected: ((TransmissionPosition) -> Unit)? = null,
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
                    .clickable(
                        enabled = onPositionSelected != null,
                        onClick = { onPositionSelected?.invoke(option) },
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
            .background(ErrorBannerBody.copy(alpha = 0.94f))
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
                    text = formatWhole(drivetrain.realOrDocumentedRawSpeedKmh),
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
