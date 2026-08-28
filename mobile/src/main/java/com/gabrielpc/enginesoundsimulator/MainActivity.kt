package com.gabrielpc.enginesoundsimulator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gabrielpc.enginesoundsimulator.drive.DriveRuntimeService
import com.gabrielpc.enginesoundsimulator.drive.DriveSnapshot
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogEntry
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogSnapshot
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
    private val uiHandler = Handler(Looper.getMainLooper())
    private var driveState by mutableStateOf<DriveSnapshot?>(null)
    private var carCatalog by mutableStateOf<CarCatalogSnapshot?>(null)
    private var catalogStatus by mutableStateOf<String?>(null)
    private var catalogStatusIsError by mutableStateOf(false)
    private var activityVisible by mutableStateOf(false)
    private var runtimeBinder: DriveRuntimeService.DriveRuntimeBinder? = null
    private var serviceBound = false
    private val uiLifecycleGate = DriveUiLifecycleGate()
    private var sampleValidationPending = false
    private var pendingDiagnosticExport: Uri? = null
    private var notificationPermissionRequestInFlight = false
    private val notificationPermissionPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionRequestInFlight = false
    }
    private val createDiagnosticExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { destination ->
        pendingDiagnosticExport = destination
        exportPendingDiagnostics()
    }
    private val importCarPacks = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach(::retainReadPermission)
        val connected = runtimeBinder
        if (connected == null) {
            recordCatalogEvent(
                DeferredCatalogEvent(
                    DeferredCatalogEventKind.FAILURE,
                    failureMessage = "Driving service is not connected",
                ),
            )
            return@registerForActivityResult
        }
        recordCatalogEvent(
            DeferredCatalogEvent(DeferredCatalogEventKind.PACK_IMPORT_STARTED, packCount = uris.size),
        )
        connected.importPacks(uris) { result ->
            val event = result.fold(
                onSuccess = {
                    DeferredCatalogEvent(DeferredCatalogEventKind.PACK_IMPORT_SUCCEEDED, packCount = uris.size)
                },
                onFailure = { failure ->
                    DeferredCatalogEvent(
                        DeferredCatalogEventKind.FAILURE,
                        failureMessage = failure.message ?: "Pack import failed",
                    )
                },
            )
            // A multi-pack batch may have installed earlier entries before a later one fails.
            // Always refresh from the runtime after completion instead of inferring mutation from
            // the aggregate Result.
            recordCatalogEvent(event, catalogChanged = true)
        }
    }
    private val importGeneratedCatalog = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        retainReadPermission(uri)
        val connected = runtimeBinder
        if (connected == null) {
            recordCatalogEvent(
                DeferredCatalogEvent(
                    DeferredCatalogEventKind.FAILURE,
                    failureMessage = "Driving service is not connected",
                ),
            )
            return@registerForActivityResult
        }
        recordCatalogEvent(DeferredCatalogEvent(DeferredCatalogEventKind.CATALOG_IMPORT_STARTED))
        connected.importGeneratedCatalog(uri) { result ->
            val event = result.fold(
                onSuccess = { DeferredCatalogEvent(DeferredCatalogEventKind.CATALOG_IMPORT_SUCCEEDED) },
                onFailure = { failure ->
                    DeferredCatalogEvent(
                        DeferredCatalogEventKind.FAILURE,
                        failureMessage = failure.message ?: "Catalog import failed",
                    )
                },
            )
            recordCatalogEvent(event, catalogChanged = true)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = service as? DriveRuntimeService.DriveRuntimeBinder ?: return
            runtimeBinder = connected
            uiLifecycleGate.onRuntimeConnected()
            if (uiLifecycleGate.visible) {
                connected.setUiVisible(true)
                driveState = connected.snapshot()
                if (driveState != null) {
                    refreshCatalogPresentationIfVisible(connected)
                    maybeRunPendingSampleValidation()
                    exportPendingDiagnostics()
                }
                startUiSampler()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtimeBinder = null
            uiLifecycleGate.onRuntimeDisconnected()
            uiHandler.removeCallbacks(refreshUi)
            if (uiLifecycleGate.visible) {
                driveState = null
                carCatalog = null
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            runtimeBinder = null
            uiLifecycleGate.onRuntimeDisconnected()
            uiHandler.removeCallbacks(refreshUi)
            if (uiLifecycleGate.visible) {
                driveState = null
                carCatalog = null
            }
            if (serviceBound) {
                runCatching { unbindService(this) }
                serviceBound = false
            }
            if (uiLifecycleGate.visible) bindToRuntime()
        }

        override fun onNullBinding(name: ComponentName?) {
            runtimeBinder = null
            uiLifecycleGate.onRuntimeDisconnected()
            uiHandler.removeCallbacks(refreshUi)
            if (uiLifecycleGate.visible) {
                driveState = null
                carCatalog = null
            }
            if (serviceBound) {
                runCatching { unbindService(this) }
                serviceBound = false
            }
        }
    }

    private val refreshUi = object : Runnable {
        override fun run() {
            val connected = runtimeBinder ?: return
            if (!uiLifecycleGate.shouldSample) return
            val next = connected.snapshot()
            if (next != null) {
                val becameReady = driveState == null
                driveState = next
                if (becameReady) {
                    refreshCatalogPresentationIfVisible(connected)
                    maybeRunPendingSampleValidation()
                    exportPendingDiagnostics()
                }
            } else if (driveState != null) {
                // Notification Stop can tear down a still-bound service. Do not leave its last
                // dashboard snapshot interactive after the ready controller is unpublished.
                driveState = null
                carCatalog = null
            }
            // Presentation stays below 60 Hz; the service's simulation loop remains independent.
            uiHandler.postDelayed(
                this,
                if (next == null) RUNTIME_READY_RETRY_MILLIS else UI_REFRESH_MILLIS,
            )
        }
    }

    private val runSampleValidation = object : Runnable {
        override fun run() {
            if (!uiLifecycleGate.visible || !sampleValidationPending) return
            val connected = runtimeBinder ?: return
            if (!connected.runSampleAudioValidation()) {
                if (connected.isInitializing()) {
                    uiHandler.postDelayed(this, RUNTIME_READY_RETRY_MILLIS)
                }
                return
            }
            sampleValidationPending = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        setContent {
            EngineSoundsSimulatorTheme(darkTheme = true, dynamicColor = false) {
                if (activityVisible) driveState?.let { state ->
                    MotorSoundDashboard(
                        state = state,
                        carCatalog = carCatalog,
                        catalogStatus = catalogStatus,
                        catalogStatusIsError = catalogStatusIsError,
                        onThrottle = { runtimeBinder?.setManualThrottle(it) },
                        onBrake = { runtimeBinder?.setManualBrake(it) },
                        onCycleInput = { runtimeBinder?.cycleInputMode() },
                        onTransmissionChange = { runtimeBinder?.setTransmissionPosition(it) },
                        onToggleSound = { runtimeBinder?.toggleSound() },
                        onConfigChange = { runtimeBinder?.setTuning(it) },
                        onResetTuning = { runtimeBinder?.resetTuning() },
                        onRestartBydReader = { runtimeBinder?.restartVehicleReader() },
                        onRunSampleValidation = { runtimeBinder?.runSampleAudioValidation() },
                        onPreviousCar = {
                            runtimeBinder?.let { connected ->
                                connected.selectPreviousCar()
                                connected.catalogSnapshot()?.let { carCatalog = it }
                                connected.snapshot()?.let { driveState = it }
                            }
                        },
                        onNextCar = {
                            runtimeBinder?.let { connected ->
                                connected.selectNextCar()
                                connected.catalogSnapshot()?.let { carCatalog = it }
                                connected.snapshot()?.let { driveState = it }
                            }
                        },
                        onSelectCar = { carId ->
                            runtimeBinder?.let { connected ->
                                catalogStatus = "Instalando pacote do carro…"
                                catalogStatusIsError = false
                                connected.selectCarOrAutoInstall(carId) { message, percent, result ->
                                    runOnUiThread {
                                        catalogStatus = if (percent != null && percent < 100) "$message $percent%" else message
                                        catalogStatusIsError = result?.isFailure == true
                                        connected.catalogSnapshot()?.let { carCatalog = it }
                                        connected.snapshot()?.let { driveState = it }
                                    }
                                }
                            }
                        },
                        onToggleFavorite = { carId ->
                            runtimeBinder?.let { connected ->
                                connected.toggleFavorite(carId)?.let { carCatalog = it }
                            }
                        },
                        onImportPacks = {
                            importCarPacks.launch(
                                arrayOf("application/zip", "application/octet-stream", "application/x-aclib"),
                            )
                        },
                        onImportCatalog = {
                            importGeneratedCatalog.launch(
                                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"),
                            )
                        },
                        onLayerMixMuted = { id, muted -> runtimeBinder?.setLayerMixMuted(id, muted) },
                        onLayerMixSolo = { id, solo -> runtimeBinder?.setLayerMixSolo(id, solo) },
                        onLayerMixVolume = { id, volume -> runtimeBinder?.setLayerMixVolume(id, volume) },
                        onSoundEffectChange = { id, enabled -> runtimeBinder?.setSoundEffectEnabled(id, enabled) },
                        onSoloSoundEffectsChange = { runtimeBinder?.setSoloSoundEffects(it) },
                        onAuditionPopsAndBangs = { runtimeBinder?.auditionPopsAndBangs() },
                        onDebugPanelVisible = { runtimeBinder?.setDebugPanelVisible(it) },
                        onCarMasterVolumeChange = { runtimeBinder?.setCarMasterVolume(it) },
                        onMarkCrackle = { runtimeBinder?.markCrackle() },
                        onExportDiagnostics = {
                            createDiagnosticExport.launch(
                                "byd-drive-diagnostics-${System.currentTimeMillis()}.jsonl",
                            )
                        },
                    )
                }
            }
        }
        maybeScheduleSampleValidation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // If the already-visible singleTop Activity reopens a stopped session there is no new
        // bind callback to publish visibility. Do it through the current binder, where onStop()
        // remains the authoritative later event; a delayed service Start intent must never set
        // visibility on behalf of an Activity that has since gone to the background.
        if (uiLifecycleGate.visible) runtimeBinder?.setUiVisible(true)
        DriveRuntimeService.startDrivingSession(this)
        maybeScheduleSampleValidation(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        maybeRequestNotificationPermission()
    }

    private fun maybeScheduleSampleValidation(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_RUN_SAMPLE_VALIDATION, false)) {
            intent.removeExtra(EXTRA_RUN_SAMPLE_VALIDATION)
            sampleValidationPending = true
            maybeRunPendingSampleValidation()
        }
    }

    override fun onStart() {
        super.onStart()
        // A disconnected/recreated service may still be constructing its controller. Never expose
        // the previous runtime's dashboard as interactive while the new binder is not ready.
        driveState = null
        carCatalog = null
        activityVisible = true
        uiLifecycleGate.onActivityStarted()
        DriveRuntimeService.startDrivingSession(this)
        bindToRuntime()
        runtimeBinder?.let { connected ->
            connected.setUiVisible(true)
            driveState = connected.snapshot()
            if (driveState != null) {
                refreshCatalogPresentationIfVisible(connected)
                maybeRunPendingSampleValidation()
                exportPendingDiagnostics()
            }
            startUiSampler()
        }
    }

    override fun onStop() {
        activityVisible = false
        uiLifecycleGate.onActivityStopped()
        releaseManualControls()
        uiHandler.removeCallbacks(refreshUi)
        uiHandler.removeCallbacks(runSampleValidation)
        runtimeBinder?.setUiVisible(false)
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        runtimeBinder = null
        uiLifecycleGate.onRuntimeDisconnected()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseManualControls()
    }

    private fun releaseManualControls() {
        runtimeBinder?.setManualThrottle(0.0)
        runtimeBinder?.setManualBrake(0.0)
    }

    private fun bindToRuntime() {
        if (serviceBound) return
        serviceBound = bindService(
            Intent(this, DriveRuntimeService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun startUiSampler() {
        uiHandler.removeCallbacks(refreshUi)
        uiHandler.post(refreshUi)
    }

    private fun maybeRunPendingSampleValidation() {
        if (!uiLifecycleGate.visible || !sampleValidationPending || runtimeBinder == null) return
        uiHandler.removeCallbacks(runSampleValidation)
        uiHandler.postDelayed(runSampleValidation, 1_500L)
    }

    private fun exportPendingDiagnostics() {
        val destination = pendingDiagnosticExport ?: return
        val connected = runtimeBinder ?: return
        if (!connected.isReady()) return
        pendingDiagnosticExport = null
        connected.exportDiagnostics(destination)
    }

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun recordCatalogEvent(event: DeferredCatalogEvent, catalogChanged: Boolean = false) {
        uiLifecycleGate.recordCatalogEvent(event, catalogChanged)
        refreshCatalogPresentationIfVisible(runtimeBinder)
    }

    /** The only path that materializes catalog/status presentation after async callbacks. */
    private fun refreshCatalogPresentationIfVisible(
        connected: DriveRuntimeService.DriveRuntimeBinder?,
    ) {
        if (connected == null || !uiLifecycleGate.shouldSample) return
        val currentCatalog = connected.catalogSnapshot() ?: return
        if (uiLifecycleGate.takeCatalogRefreshRequest()) {
            carCatalog = currentCatalog
        }
        uiLifecycleGate.takeCatalogEvent()?.let(::renderCatalogEvent)
    }

    private fun renderCatalogEvent(event: DeferredCatalogEvent) {
        check(uiLifecycleGate.shouldSample) { "Catalog presentation must only be rendered while visible" }
        when (event.kind) {
            DeferredCatalogEventKind.PACK_IMPORT_STARTED -> setCatalogStatus(
                "Importing ${event.packCount} car pack${if (event.packCount == 1) "" else "s"}…",
            )
            DeferredCatalogEventKind.PACK_IMPORT_SUCCEEDED -> setCatalogStatus(
                "Imported packs · ${carCatalog?.installedFamilyCount ?: 0} sound families installed",
            )
            DeferredCatalogEventKind.CATALOG_IMPORT_STARTED -> setCatalogStatus("Importing official car catalog…")
            DeferredCatalogEventKind.CATALOG_IMPORT_SUCCEEDED -> setCatalogStatus("Official metadata catalog imported")
            DeferredCatalogEventKind.FAILURE -> setCatalogStatus(
                event.failureMessage ?: "Catalog operation failed",
                isError = true,
            )
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val promptRecorded = notificationPermissionPreferences.getBoolean(
            NOTIFICATION_PERMISSION_PROMPT_RECORDED,
            false,
        )
        if (!NotificationPermissionRequestPolicy.shouldRequest(
                sdkInt = Build.VERSION.SDK_INT,
                activityVisible = uiLifecycleGate.visible,
                permissionGranted = permissionGranted,
                promptRecorded = promptRecorded,
                requestInFlight = notificationPermissionRequestInFlight,
            )
        ) return

        notificationPermissionRequestInFlight = true
        notificationPermissionPreferences.edit {
            putBoolean(NOTIFICATION_PERMISSION_PROMPT_RECORDED, true)
        }
        runCatching { requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure {
                notificationPermissionRequestInFlight = false
                notificationPermissionPreferences.edit {
                    remove(NOTIFICATION_PERMISSION_PROMPT_RECORDED)
                }
            }
    }

    private fun setCatalogStatus(message: String, isError: Boolean = false) {
        catalogStatus = message
        catalogStatusIsError = isError
    }

}

@Composable
private fun MotorSoundDashboard(
    state: DriveSnapshot,
    carCatalog: CarCatalogSnapshot?,
    catalogStatus: String?,
    catalogStatusIsError: Boolean,
    onThrottle: (Double) -> Unit,
    onBrake: (Double) -> Unit,
    onCycleInput: () -> Unit,
    onTransmissionChange: (TransmissionPosition) -> Unit,
    onToggleSound: () -> Unit,
    onConfigChange: (TuningConfig) -> Unit,
    onResetTuning: () -> Unit,
    onRestartBydReader: () -> Unit,
    onRunSampleValidation: () -> Unit,
    onPreviousCar: () -> Unit,
    onNextCar: () -> Unit,
    onSelectCar: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportPacks: () -> Unit,
    onImportCatalog: () -> Unit,
    onLayerMixMuted: (String, Boolean) -> Unit,
    onLayerMixSolo: (String, Boolean) -> Unit,
    onLayerMixVolume: (String, Double) -> Unit,
    onSoundEffectChange: (String, Boolean) -> Unit,
    onSoloSoundEffectsChange: (Boolean) -> Unit,
    onAuditionPopsAndBangs: () -> Unit,
    onDebugPanelVisible: (Boolean) -> Unit,
    onCarMasterVolumeChange: (Double) -> Unit,
    onMarkCrackle: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    var tuningOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var effectsOpen by remember { mutableStateOf(false) }
    var mainScreen by remember { mutableStateOf(DashboardMainScreen.CLASSIC) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(debugOpen) {
        onDebugPanelVisible(debugOpen)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (tuningOpen || debugOpen || effectsOpen) return@onPreviewKeyEvent false
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                        mainScreen = mainScreen,
                        onMainScreenChange = { mainScreen = it },
                        onCycleInput = onCycleInput,
                        onToggleSound = onToggleSound,
                        onOpenTuning = { tuningOpen = true },
                        onOpenDebug = { debugOpen = true },
                        onOpenEffects = { effectsOpen = true },
                    )

                    when (mainScreen) {
                        DashboardMainScreen.CLASSIC -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 34.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CarStage(
                                state = state,
                                selectedCatalogEntry = carCatalog?.find(state.selectedCarId),
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
                        DashboardMainScreen.MIXER -> MixerDashboardScreen(
                            state = state,
                            carCatalog = carCatalog,
                            catalogStatus = catalogStatus,
                            catalogStatusIsError = catalogStatusIsError,
                            onThrottle = onThrottle,
                            onBrake = onBrake,
                            onTransmissionChange = onTransmissionChange,
                            onSelectCar = onSelectCar,
                            onToggleFavorite = onToggleFavorite,
                            onImportPacks = onImportPacks,
                            onImportCatalog = onImportCatalog,
                            onCarMasterVolumeChange = onCarMasterVolumeChange,
                            onLayerMuted = onLayerMixMuted,
                            onLayerSolo = onLayerMixSolo,
                            onLayerVolume = onLayerMixVolume,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        DashboardMainScreen.GRID -> Spacer(Modifier.weight(1f))
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

                if (debugOpen) {
                    DebugPanel(
                        state = state,
                        onRestartBydReader = onRestartBydReader,
                        onRunSampleValidation = onRunSampleValidation,
                        onMarkCrackle = onMarkCrackle,
                        onExportDiagnostics = onExportDiagnostics,
                        onClose = { debugOpen = false },
                    )
                }

                if (effectsOpen) {
                    SoundEffectsPanel(
                        state = state,
                        onEffectChange = onSoundEffectChange,
                        onSoloChange = onSoloSoundEffectsChange,
                        onAuditionPopsAndBangs = onAuditionPopsAndBangs,
                        onClose = { effectsOpen = false },
                    )
                }
            }
        }

            if (mainScreen == DashboardMainScreen.GRID) {
                ResolutionProbeScreen(
                    selectedScreen = mainScreen,
                    onSelectScreen = { mainScreen = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val EXTRA_RUN_SAMPLE_VALIDATION = "run_sample_audio_validation"
private const val UI_REFRESH_MILLIS = 17L
private const val RUNTIME_READY_RETRY_MILLIS = 100L
private const val NOTIFICATION_PERMISSION_PREFERENCES = "notification_permission"
private const val NOTIFICATION_PERMISSION_PROMPT_RECORDED = "post_notifications_prompt_recorded"

@Composable
private fun DashboardHeader(
    state: DriveSnapshot,
    mainScreen: DashboardMainScreen,
    onMainScreenChange: (DashboardMainScreen) -> Unit,
    onCycleInput: () -> Unit,
    onToggleSound: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenEffects: () -> Unit,
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
            StatusTag(
                text = state.activeInput,
                color = if (state.activeInput.startsWith("BYD")) Green else Cyan,
                // Input labels have different lengths (for example BYD PEDALS versus
                // BYD UNAVAILABLE). Reserve their widest slot so the adjacent screen selector
                // does not move when the live source changes.
                modifier = Modifier.width(132.dp),
            )
            DashboardScreenSwitcher(
                selected = mainScreen,
                onSelect = onMainScreenChange,
            )
        }

        HeaderButton(
            primary = "DEBUG",
            secondary = "BYD / LOGS",
            accent = if (state.activeInput == "BYD UNAVAILABLE") Red else Cyan,
            onClick = onOpenDebug,
        )
        HeaderButton(
            primary = if (state.soundEffects.isEmpty()) "ENGINE" else "${state.soundEffects.count { it.enabled }}/${state.soundEffects.size} ON",
            secondary = "CAR EFFECTS",
            accent = if (state.soundEffects.any { it.enabled }) Green else Muted,
            onClick = onOpenEffects,
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
private fun StatusTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CarStage(
    state: DriveSnapshot,
    selectedCatalogEntry: CarCatalogEntry?,
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
                selectedCatalogEntry.catalogSummary(),
                color = CyanSoft,
                fontSize = 12.sp,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTag("${formatWhole(state.drivetrain.accelerationMps2 / 9.81)} G", Amber)
            }
        }

        CarPreviewImage(
            absolutePath = selectedCatalogEntry?.previewFile?.absolutePath,
            assetFallback = state.selectedCarPreviewAsset,
            contentDescription = state.selectedCarName,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.65f)
                .align(Alignment.Center)
                .offset(y = 18.dp),
        )

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
                lockedToVehicle = state.transmissionLockedToVehicle,
            )
        }

    }
}

private fun CarCatalogEntry?.catalogSummary(): String {
    if (this == null) return "OFFICIAL ASSETTO CORSA CAR · IMPORT ITS .ACLIB PACK TO ACTIVATE"
    val engineMetadata = engine
    val gearboxMetadata = gearbox
    if (engineMetadata == null && gearboxMetadata == null) {
        return "OFFICIAL ASSETTO CORSA CAR · IMPORT CATALOG OR .ACLIB FOR DETAILS"
    }
    val engineKind = when {
        engineMetadata?.hybrid == true -> "HYBRID"
        (engineMetadata?.turboCount ?: 0) > 1 -> "${engineMetadata?.turboCount} TURBOS"
        engineMetadata?.turboCount == 1 -> "TURBO"
        else -> "NATURALLY ASPIRATED"
    }
    return listOfNotNull(
        engineKind,
        gearboxMetadata?.forwardRatios?.size?.let { "$it-SPEED ${gearboxMetadata.traction}" },
        engineMetadata?.idleRpm?.roundToInt()?.let { "IDLE $it RPM" },
        engineMetadata?.redlineRpm?.roundToInt()?.let { "REDLINE $it RPM" },
    ).joinToString(" · ")
}

@Composable
internal fun TransmissionShifter(
    position: TransmissionPosition,
    onPositionChange: (TransmissionPosition) -> Unit,
    lockedToVehicle: Boolean = false,
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
            .border(2.dp, if (lockedToVehicle) Green.copy(alpha = 0.75f) else Color(0xFF60717D), RoundedCornerShape(16.dp))
            .padding(8.dp)
            .alpha(if (lockedToVehicle) 0.88f else 1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lockedToVehicle) {
            Text(
                text = "BYD",
                color = Green,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
            )
        }

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
internal fun PedalControl(
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
            if (drivetrain.isShifting) {
                Text(
                    text = if (drivetrain.shiftDirection.name == "UP") "SHIFT" else "DOWNSHIFT",
                    color = Green,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = gaugeSize * 0.08f),
                )
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
