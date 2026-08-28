package com.gabrielpc.enginesoundsimulator.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.gabrielpc.enginesoundsimulator.BuildConfig
import com.gabrielpc.enginesoundsimulator.MainActivity
import com.gabrielpc.enginesoundsimulator.R
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogSnapshot
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/**
 * Process-local owner of the driving runtime.
 *
 * Unbinding the dashboard only stops presentation work. Telemetry, simulation, gearbox and audio
 * continue until the task is dismissed or the user presses Stop in the ongoing notification.
 */
class DriveRuntimeService : Service() {
    private lateinit var sessionStore: DriveRuntimeSessionStore
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimeInitializationExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "drive-runtime-initializer").apply { isDaemon = true }
    }
    private val runtimeTeardownExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "drive-runtime-teardown").apply { isDaemon = true }
    }
    private val exportExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "drive-diagnostic-export").apply { isDaemon = true }
    }
    private val catalogExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "drive-catalog-import").apply { isDaemon = true }
    }
    private val stopping = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val initializationInFlight = AtomicBoolean(false)
    private val teardownInFlight = AtomicBoolean(false)
    private val initializationGeneration = AtomicLong(0L)
    private val controller = AtomicReference<DriveController?>(null)
    private val stopRestartCoordinator = DriveRuntimeStopRestartCoordinator()
    private val binder = DriveRuntimeBinder()
    private val pendingDebugCommands = ArrayDeque<PendingDebugCommand>()

    @Volatile
    private var uiVisible = false

    @Volatile
    private var foregroundStarted = false

    private var lastNotificationState: NotificationState? = null

    private val finishStop = Runnable {
        if (!stopRestartCoordinator.beginTeardown()) return@Runnable
        // A started service can remain alive while an Activity is still bound.
        // Close runtime ownership here rather than waiting for onDestroy(), so
        // notification Stop always releases telemetry, decoder, native PCM,
        // AudioTrack and audio focus after the requested fade.
        closeControllerRuntimeAsync {
            if (destroyed.get()) return@closeControllerRuntimeAsync
            // startDrivingSession() persists its marker before Android delivers ACTION_START. The
            // marker closes the tiny main-queue race where teardown finishes between those steps.
            val persistedRestart = DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())
            when (stopRestartCoordinator.completeTeardown(persistedRestart)) {
                TeardownDisposition.START_NEW_RUNTIME -> {
                    // The old controller is fully closed at this point. Only now may an explicit
                    // reopen create a fresh controller; the old loop/audio phases are never reused.
                    stopping.set(false)
                    ensureForeground()
                    ensureControllerInitialization()
                }

                TeardownDisposition.STOP_SERVICE -> {
                    removeForegroundNotification()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = DriveRuntimeSessionStore(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // startForegroundService() requires promotion before onStartCommand may finish. The
        // explicit-start marker is written by [startDrivingSession] before Android creates us.
        if (DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())) {
            val provisionalState = NotificationState(
                selectedCarName = getString(R.string.app_name),
                soundEnabled = sessionStore.read().soundEnabled,
            )
            startForeground(NOTIFICATION_ID, buildNotification(provisionalState))
            foregroundStarted = true
            ensureControllerInitialization()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var debugCommand: Intent? = null
        var explicitStartDisposition: ExplicitStartDisposition? = null
        when (intent?.action) {
            ACTION_STOP -> {
                requestUserStop("notification")
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_SOUND -> {
                if (DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())) {
                    ensureForeground()
                    setSoundEnabled(!sessionStore.read().soundEnabled)
                    if (!stopping.get()) ensureRuntimeStarted()
                } else {
                    // A notification action queued before Stop must not bypass the requested
                    // fade by destroying the service immediately. The stop coordinator owns the
                    // final teardown once [stopping] is true.
                    if (!stopRestartCoordinator.isStopping()) stopSelf(startId)
                }
                return if (DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }

            ACTION_START -> {
                // startDrivingSession() commits the explicit-start marker before asking Android
                // to deliver this intent. Do not write it again here: a Start queued before a
                // newer task/notification Stop may arrive afterward, and that stale delivery must
                // not clear the stopped-by-user marker or queue a resurrection.
                if (DriveRuntimeSessionPolicy.acceptExplicitStartDelivery(sessionStore.read())) {
                    explicitStartDisposition = stopRestartCoordinator.requestExplicitStart()
                }
            }
            ACTION_DEBUG_COMMAND -> {
                if (!BuildConfig.DEBUG) {
                    Log.w(DEBUG_LOG_TAG, "Ignoring debug command in a non-debug build")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (intent.getStringExtra(EXTRA_DEBUG_COMMAND)
                        ?.trim()
                        ?.equals(DEBUG_COMMAND_STOP, ignoreCase = true) == true
                ) {
                    handleDebugCommand(intent)
                    return START_NOT_STICKY
                }
                // forwardDebugCommand() persists authorization before dispatch, just like the
                // dashboard path. Ignore a pre-Stop command delivered after the Stop marker.
                if (DriveRuntimeSessionPolicy.acceptExplicitStartDelivery(sessionStore.read())) {
                    explicitStartDisposition = stopRestartCoordinator.requestExplicitStart()
                    debugCommand = intent
                }
            }
            null -> Unit // Android is attempting a START_STICKY process restoration.
            else -> Unit
        }

        if (!DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())) {
            // Preserve an in-progress user fade. With no stop in progress this is an ordinary
            // stale sticky/unknown delivery and can be stopped immediately.
            if (!stopRestartCoordinator.isStopping()) stopSelf(startId)
            return START_NOT_STICKY
        }

        if (explicitStartDisposition == ExplicitStartDisposition.START_NOW) {
            stopping.set(false)
        }
        ensureForeground()
        if (stopping.get()) {
            // Preserve the command for the fresh controller, but never let it revive the one that
            // is already fading or closing.
            debugCommand?.let(::handleDebugCommand)
            return START_STICKY
        }
        ensureRuntimeStarted()
        debugCommand?.let(::handleDebugCommand)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        setUiVisible(false)
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestUserStop("task_removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed.set(true)
        initializationGeneration.incrementAndGet()
        mainHandler.removeCallbacks(finishStop)
        uiVisible = false
        failPendingDebugCommands("runtime_destroyed")
        closeControllerRuntimeAsync()
        removeForegroundNotification()
        // A constructor already in progress is allowed to finish so it can close its own native
        // resources after observing [destroyed]. The executor thread is daemon-backed.
        runtimeInitializationExecutor.shutdown()
        // Drain any close submitted above without interrupting AudioTrack/native cleanup.
        runtimeTeardownExecutor.shutdown()
        exportExecutor.shutdownNow()
        catalogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureRuntimeStarted() {
        if (readyController() != null || destroyed.get() || stopping.get()) return
        ensureControllerInitialization()
    }

    /**
     * DriveController construction opens repositories, validates installed packs and creates the
     * native/audio ownership graph. None of that work belongs on the service main thread.
     * Publication happens only after [DriveController.start] succeeds and the same requested
     * session generation is still current.
     */
    private fun ensureControllerInitialization() {
        if (destroyed.get() || stopping.get() || teardownInFlight.get() || readyController() != null) return
        if (!DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())) return
        if (!initializationInFlight.compareAndSet(false, true)) return

        val generation = initializationGeneration.get()
        try {
            runtimeInitializationExecutor.execute {
                var candidate: DriveController? = null
                val result = runCatching {
                    val initialSessionState = sessionStore.read()
                    candidate = DriveController(
                        applicationContext,
                        initialSoundEnabled = initialSessionState.soundEnabled,
                    )
                    val built = requireNotNull(candidate)
                    if (!initializationMayContinue(generation)) {
                        built.close()
                        candidate = null
                        return@runCatching null
                    }

                    val soundEnabled = sessionStore.read().soundEnabled
                    built.setSoundEnabled(soundEnabled)
                    if (!initializationMayContinue(generation)) {
                        built.close()
                        candidate = null
                        return@runCatching null
                    }

                    built.start()
                    if (!initializationMayContinue(generation)) {
                        built.close()
                        candidate = null
                        return@runCatching null
                    }
                    candidate = null
                    built
                }
                result.exceptionOrNull()?.let { failure ->
                    candidate?.let { late -> runCatching(late::close) }
                    DebugEventLog.recordThrowable("drive_runtime_initialization_failed", failure)
                }
                mainHandler.post {
                    completeControllerInitialization(
                        generation = generation,
                        initialized = result.getOrNull(),
                        failure = result.exceptionOrNull(),
                    )
                }
            }
        } catch (rejected: RejectedExecutionException) {
            initializationInFlight.set(false)
            if (!destroyed.get()) {
                DebugEventLog.recordThrowable("drive_runtime_initialization_rejected", rejected)
            }
        }
    }

    private fun initializationMayContinue(generation: Long): Boolean =
        DriveRuntimeInitializationPolicy.shouldPublish(
            completedGeneration = generation,
            currentGeneration = initializationGeneration.get(),
            sessionState = sessionStore.read(),
            stopping = stopping.get(),
            destroyed = destroyed.get(),
        )

    private fun completeControllerInitialization(
        generation: Long,
        initialized: DriveController?,
        failure: Throwable?,
    ) {
        initializationInFlight.set(false)
        if (initialized == null) {
            if (failure != null && initializationMayContinue(generation)) {
                failPendingDebugCommands("runtime_initialization_failed")
            } else if (!destroyed.get() && !stopping.get() &&
                DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())
            ) {
                ensureControllerInitialization()
            }
            return
        }

        if (!initializationMayContinue(generation) || controller.get() != null) {
            closeLateController(initialized)
            if (!destroyed.get() && !stopping.get() &&
                DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())
            ) {
                ensureControllerInitialization()
            }
            return
        }

        // Sound may have been toggled while construction/startup was in flight.
        initialized.setSoundEnabled(sessionStore.read().soundEnabled)
        if (!uiVisible) {
            initialized.setManualThrottle(0.0)
            initialized.setManualBrake(0.0)
            initialized.setDebugPanelVisible(false)
        }
        if (!initializationMayContinue(generation) || !controller.compareAndSet(null, initialized)) {
            closeLateController(initialized)
            if (!destroyed.get() && !stopping.get() && controller.get() == null &&
                DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())
            ) {
                ensureControllerInitialization()
            }
            return
        }
        refreshNotificationIfChanged()
        DebugEventLog.warning(
            "drive_runtime_started",
            "sticky=true sound_enabled=${sessionStore.read().soundEnabled}",
        )
        drainPendingDebugCommands()
    }

    private fun closeLateController(lateController: DriveController) {
        try {
            runtimeInitializationExecutor.execute {
                runCatching(lateController::close).exceptionOrNull()?.let { failure ->
                    DebugEventLog.recordThrowable("late_drive_runtime_close_failed", failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            Thread(
                {
                    runCatching(lateController::close).exceptionOrNull()?.let { failure ->
                        DebugEventLog.recordThrowable("late_drive_runtime_close_failed", failure)
                    }
                },
                "late-drive-runtime-close",
            ).apply { isDaemon = true }.start()
        }
    }

    /** Detaches ownership immediately and performs every potentially blocking join off-main. */
    private fun closeControllerRuntimeAsync(onClosed: (() -> Unit)? = null) {
        val active = controller.getAndSet(null)
        if (active == null) {
            onClosed?.invoke()
            return
        }
        check(teardownInFlight.compareAndSet(false, true)) {
            "A second published drive runtime cannot be torn down concurrently"
        }
        val closeTask = Runnable {
            runCatching {
                active.setManualThrottle(0.0)
                active.setManualBrake(0.0)
                active.setDebugPanelVisible(false)
                active.close()
            }.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("drive_runtime_close_failed", failure)
            }
            DebugEventLog.warning("drive_runtime_stopped")
            DebugEventLog.warning("drive_runtime_resources_closed")
            mainHandler.post {
                teardownInFlight.set(false)
                onClosed?.invoke()
                if (onClosed == null && !destroyed.get() && !stopping.get() &&
                    DriveRuntimeSessionPolicy.shouldRun(sessionStore.read())
                ) {
                    ensureControllerInitialization()
                }
            }
        }
        try {
            runtimeTeardownExecutor.execute(closeTask)
        } catch (_: RejectedExecutionException) {
            Thread(closeTask, "late-drive-runtime-teardown").apply { isDaemon = true }.start()
        }
    }

    private fun requestUserStop(source: String) {
        sessionStore.recordUserStop()
        val newlyStopping = stopRestartCoordinator.requestStop()
        // Remain unavailable through FADING, TEARING_DOWN and the final stopped interval. Only an
        // explicit start, or teardown completion with a queued start, may clear this gate.
        stopping.set(true)
        if (!newlyStopping) {
            // A second Stop cancels any explicit restart queued during the first teardown.
            failPendingDebugCommands("runtime_stopped")
            // Once teardown has already reached STOPPED, a late binder/debug Stop may be the
            // only command attached to a still-bound service. Ensure it cannot leave that empty
            // service started after the original stopSelf() boundary.
            if (!stopRestartCoordinator.isStopping()) {
                removeForegroundNotification()
                stopSelf()
            }
            return
        }
        initializationGeneration.incrementAndGet()
        failPendingDebugCommands("runtime_stopped")
        uiVisible = false
        controller.get()?.let { active ->
            active.setManualThrottle(0.0)
            active.setManualBrake(0.0)
            active.setDebugPanelVisible(false)
            active.beginShutdownFade()
        }
        DebugEventLog.warning("drive_runtime_stop_requested", "source=$source")
        mainHandler.removeCallbacks(finishStop)
        mainHandler.postDelayed(finishStop, shutdownFadeMillis())
    }

    private fun shutdownFadeMillis(): Long {
        return DriveRuntimeBackgroundReadPolicy.shutdownFadeMillis(
            runtime = controller.get(),
            minimumMillis = MINIMUM_STOP_FADE_MILLIS,
            maximumMillis = MAXIMUM_STOP_FADE_MILLIS,
            timeConstants = STOP_FADE_TIME_CONSTANTS,
        )
    }

    private fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) {
            controller.get()?.let { active ->
                active.setManualThrottle(0.0)
                active.setManualBrake(0.0)
                active.setDebugPanelVisible(false)
            }
        }
    }

    private fun setSoundEnabled(enabled: Boolean) {
        sessionStore.recordSoundEnabled(enabled)
        readyController()?.setSoundEnabled(enabled)
        refreshNotificationIfChanged()
    }

    private fun ensureForeground() {
        val state = currentNotificationState()
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification(state))
            foregroundStarted = true
        } else if (state != lastNotificationState) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun refreshNotificationIfChanged() {
        if (!foregroundStarted) return
        val state = currentNotificationState()
        if (state == lastNotificationState) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun currentNotificationState(): NotificationState {
        val selectedCarName = DriveRuntimeBackgroundReadPolicy.notificationCarName(
            runtime = controller.get(),
            fallback = lastNotificationState?.selectedCarName ?: getString(R.string.app_name),
        )
        return NotificationState(
            selectedCarName = selectedCarName,
            soundEnabled = sessionStore.read().soundEnabled,
        )
    }

    private fun readyController(): DriveController? {
        if (destroyed.get() || stopping.get()) return null
        return controller.get()
    }

    private fun buildNotification(state: NotificationState): Notification {
        lastNotificationState = state
        val openDashboard = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_DASHBOARD,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleSound = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_SOUND,
            Intent(this, DriveRuntimeService::class.java).setAction(ACTION_TOGGLE_SOUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, DriveRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_drive_notification)
            .setContentTitle(state.selectedCarName)
            .setContentText(
                getString(
                    if (state.soundEnabled) R.string.drive_notification_running
                    else R.string.drive_notification_muted,
                ),
            )
            .setContentIntent(openDashboard)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_drive_notification),
                    getString(
                        if (state.soundEnabled) R.string.drive_notification_mute
                        else R.string.drive_notification_unmute,
                    ),
                    toggleSound,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_drive_notification),
                    getString(R.string.drive_notification_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.drive_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.drive_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun removeForegroundNotification() {
        if (!foregroundStarted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        lastNotificationState = null
    }

    private fun exportDiagnostics(destination: Uri, onComplete: (Boolean) -> Unit) {
        val active = readyController()
        if (active == null) {
            onComplete(false)
            return
        }
        exportExecutor.execute {
            val result = runCatching {
                DriveRuntimeDiagnostics.write(contentResolver, destination, active.snapshot())
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("diagnostic_export_failed", failure)
            }
            mainHandler.post { onComplete(result.isSuccess) }
        }
    }

    private fun importPacks(
        sources: List<Uri>,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (sources.isEmpty()) {
            onComplete(Result.success(Unit))
            return
        }
        val active = readyController()
        if (active == null) {
            onComplete(Result.failure(IllegalStateException("Driving runtime is still initializing")))
            return
        }
        catalogExecutor.execute {
            val result = runCatching {
                active.importPacks(sources)
                Unit
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("sound_pack_import_failed", failure)
            }
            mainHandler.post {
                if (result.isSuccess) refreshNotificationIfChanged()
                onComplete(result)
            }
        }
    }

    private fun selectCarOrAutoInstall(carId: String, onProgress: (String, Int?, Result<Unit>?) -> Unit) {
        val active = readyController() ?: run {
            onProgress("Driving runtime ainda está iniciando…", null, Result.failure(IllegalStateException("runtime")))
            return
        }
        if (active.catalogSnapshot()?.entries?.firstOrNull { it.id == carId }?.installed == true) {
            active.selectCar(carId)
            onProgress("Carro selecionado", 100, Result.success(Unit))
            refreshNotificationIfChanged()
            return
        }
        catalogExecutor.execute {
            onProgress("Procurando pacote local…", 5, null)
            val result = runCatching {
                active.autoInstallCar(carId) { message, percent -> onProgress(message, percent, null) }
                check(active.selectCar(carId)) { "Não foi possível ativar o carro" }
            }
            mainHandler.post {
                onProgress(if (result.isSuccess) "Carro instalado e selecionado" else (result.exceptionOrNull()?.message ?: "Falha na instalação"), if (result.isSuccess) 100 else null, result.map { Unit })
                if (result.isSuccess) refreshNotificationIfChanged()
            }
        }
    }

    private fun importGeneratedCatalog(
        source: Uri,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val active = readyController()
        if (active == null) {
            onComplete(Result.failure(IllegalStateException("Driving runtime is still initializing")))
            return
        }
        catalogExecutor.execute {
            val result = runCatching {
                active.importGeneratedCatalog(source)
                Unit
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("sound_catalog_import_failed", failure)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    /**
     * Executes commands forwarded by the debug-source-set receiver. The production manifest has
     * no receiver, and this second gate keeps the command surface inert in release builds even if
     * an in-process caller constructs the private service intent directly.
     */
    private fun handleDebugCommand(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val command = intent.getStringExtra(EXTRA_DEBUG_COMMAND)
            ?.trim()
            ?.lowercase(java.util.Locale.US)
            .orEmpty()
        if (command.isEmpty()) {
            logDebugResult("missing", "result=error message=missing_command")
            return
        }
        if (command == DEBUG_COMMAND_STOP) {
            requestUserStop("adb_debug")
            logDebugResult(command, "result=ok")
            return
        }
        val active = readyController()
        if (active == null) {
            enqueuePendingDebugCommand(intent, command)
            return
        }
        if (command == DEBUG_COMMAND_IMPORT_PACKS) {
            handleDebugBulkImport(command, intent, active)
            return
        }
        if (command == DEBUG_COMMAND_IMPORT_CATALOG || command == DEBUG_COMMAND_IMPORT_PACK) {
            handleDebugImport(command, intent, active)
            return
        }
        if (command == DEBUG_COMMAND_EXPORT_DIAGNOSTICS) {
            handleDebugDiagnosticExport(command, active)
            return
        }
        if (command == DEBUG_COMMAND_STABILIZE_MEMORY) {
            handleDebugMemoryStabilization(command)
            return
        }

        val result = runCatching {
            when (command) {
                DEBUG_COMMAND_SET_PEDALS -> {
                    active.setInputMode(InputMode.SIMULATOR)
                    active.setManualThrottle(intent.requiredNumberExtra(EXTRA_DEBUG_THROTTLE))
                    active.setManualBrake(intent.requiredNumberExtra(EXTRA_DEBUG_BRAKE))
                }

                DEBUG_COMMAND_SET_THROTTLE -> {
                    active.setInputMode(InputMode.SIMULATOR)
                    active.setManualThrottle(intent.requiredNumberExtra(EXTRA_DEBUG_VALUE))
                }

                DEBUG_COMMAND_SET_BRAKE -> {
                    active.setInputMode(InputMode.SIMULATOR)
                    active.setManualBrake(intent.requiredNumberExtra(EXTRA_DEBUG_VALUE))
                }

                DEBUG_COMMAND_RESET_PEDALS -> {
                    active.setInputMode(InputMode.SIMULATOR)
                    active.setManualThrottle(0.0)
                    active.setManualBrake(0.0)
                }

                DEBUG_COMMAND_SET_INPUT_MODE -> {
                    val value = intent.requiredStringExtra(EXTRA_DEBUG_VALUE)
                    active.setInputMode(enumValue<InputMode>(value, "input mode"))
                }

                DEBUG_COMMAND_SET_TRANSMISSION -> {
                    val value = intent.requiredStringExtra(EXTRA_DEBUG_VALUE)
                    active.setInputMode(InputMode.SIMULATOR)
                    active.setTransmissionPosition(
                        enumValue<TransmissionPosition>(value, "transmission position"),
                    )
                }

                DEBUG_COMMAND_SET_SOUND_ENABLED -> {
                    setSoundEnabled(intent.requiredBooleanExtra(EXTRA_DEBUG_VALUE))
                }

                DEBUG_COMMAND_SELECT_CAR -> {
                    val carId = intent.requiredStringExtra(EXTRA_DEBUG_CAR_ID)
                    require(active.selectCar(carId)) {
                        "Car '$carId' is unknown or its sound family is not installed"
                    }
                    refreshNotificationIfChanged()
                }

                DEBUG_COMMAND_TOGGLE_FAVORITE -> {
                    val carId = intent.requiredStringExtra(EXTRA_DEBUG_CAR_ID)
                    require(active.catalogSnapshot().find(carId) != null) { "Unknown official car '$carId'" }
                    active.toggleFavorite(carId)
                }

                DEBUG_COMMAND_AUDITION_POPS_BANGS -> active.auditionPopsAndBangs()
                DEBUG_COMMAND_MARK_CRACKLE -> DriveRuntimeDiagnostics.markCrackle(active.snapshot())
                DEBUG_COMMAND_RUN_VALIDATION -> {
                    val soundEnabled = intent.booleanExtra(EXTRA_DEBUG_SOUND_ENABLED) ?: true
                    setSoundEnabled(soundEnabled)
                    active.runSampleAudioValidation(forceSoundEnabled = soundEnabled)
                    mainHandler.postDelayed(
                        { logDebugSnapshot("validation_checkpoint") },
                        DEBUG_VALIDATION_LOG_DELAY_MILLIS,
                    )
                }

                DEBUG_COMMAND_LOG_SNAPSHOT -> Unit
                else -> error("Unknown debug command '$command'")
            }
        }

        result.fold(
            onSuccess = {
                logDebugResult(command, "result=ok")
                logDebugSnapshot(command)
            },
            onFailure = { failure ->
                logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
                DebugEventLog.recordThrowable("adb_debug_command_failed", failure, "command=$command")
            },
        )
    }

    /**
     * Imports only files staged inside this debug APK's external-files directory. The exported
     * receiver still requires the signature-level shell DUMP permission, and release builds do not
     * contain it. Keeping the work on the existing catalog executor also exercises the production
     * import and atomic-validation path without blocking the broadcast/main thread.
     */
    private fun handleDebugImport(
        command: String,
        intent: Intent,
        active: DriveController,
    ) {
        val stagedFile = runCatching { requireDebugStagedFile(intent) }.getOrElse { failure ->
            logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
            return
        }
        logDebugResult(command, "result=started file=${stagedFile.name}")
        catalogExecutor.execute {
            val result = runCatching {
                val uri = Uri.fromFile(stagedFile)
                if (command == DEBUG_COMMAND_IMPORT_CATALOG) {
                    active.importGeneratedCatalog(uri)
                } else {
                    active.importPack(uri)
                }
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("adb_debug_import_failed", failure, "command=$command")
            }
            mainHandler.post {
                result.fold(
                    onSuccess = {
                        refreshNotificationIfChanged()
                        logDebugResult(command, "result=ok file=${stagedFile.name}")
                        logDebugSnapshot(command)
                    },
                    onFailure = { failure ->
                        logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
                    },
                )
            }
        }
    }

    /** Debug-only ADB staging adapter into the same one-refresh production batch import path. */
    private fun handleDebugBulkImport(
        command: String,
        intent: Intent,
        active: DriveController,
    ) {
        val staged = runCatching { requireDebugStagedPackDirectory(intent) }.getOrElse { failure ->
            logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
            return
        }
        val directory = staged.directory
        val packFiles = staged.packFiles
        logDebugResult(command, "result=started count=${packFiles.size}")
        catalogExecutor.execute {
            val result = runCatching {
                active.importPacks(packFiles.map(Uri::fromFile))
                Unit
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable("adb_debug_bulk_import_failed", failure, "command=$command")
            }
            // The bridge owns this exact canonical staging directory. Imported packs have already
            // been atomically copied into the family store, so retain no second private copy.
            if (!DebugPackStagingPolicy.close(staged)) {
                DebugEventLog.warning(
                    "adb_debug_bulk_import_cleanup_incomplete",
                    "directory=${directory.name} count=${packFiles.size}",
                )
            }
            mainHandler.post {
                result.fold(
                    onSuccess = {
                        refreshNotificationIfChanged()
                        logDebugResult(command, "result=ok count=${packFiles.size}")
                        logDebugSnapshot(command)
                    },
                    onFailure = { failure ->
                        logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
                    },
                )
            }
        }
    }

    /** Writes the same bounded JSONL as the SAF UI flow, but only into debug app-private storage. */
    private fun handleDebugDiagnosticExport(command: String, active: DriveController) {
        val destination = File(File(filesDir, DEBUG_EXPORT_DIRECTORY), DEBUG_EXPORT_FILE_NAME)
        logDebugResult(command, "result=started file=${destination.name}")
        exportExecutor.execute {
            val result = runCatching {
                DriveRuntimeDiagnostics.write(destination, active.snapshot())
            }
            result.exceptionOrNull()?.let { failure ->
                DebugEventLog.recordThrowable(
                    "adb_debug_diagnostic_export_failed",
                    failure,
                    "command=$command",
                )
            }
            mainHandler.post {
                result.fold(
                    onSuccess = {
                        logDebugResult(
                            command,
                            "result=ok file=${destination.name} bytes=${destination.length()}",
                        )
                    },
                    onFailure = { failure ->
                        logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
                    },
                )
            }
        }
    }

    /** Test-only GC boundary used between completed profile swaps; never touches the audio thread. */
    private fun handleDebugMemoryStabilization(command: String) {
        logDebugResult(command, "result=started")
        exportExecutor.execute {
            val result = runCatching {
                repeat(2) {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    Thread.sleep(DEBUG_MEMORY_STABILIZATION_DELAY_MILLIS)
                }
            }
            mainHandler.post {
                result.fold(
                    onSuccess = { logDebugResult(command, "result=ok") },
                    onFailure = { failure ->
                        logDebugResult(command, "result=error message=${failure.message.orEmpty()}")
                    },
                )
            }
        }
    }

    private fun enqueuePendingDebugCommand(intent: Intent, command: String) {
        val pending = PendingDebugCommand(
            intent = Intent(intent),
            command = command,
            generation = initializationGeneration.get(),
        )
        val dropped = synchronized(pendingDebugCommands) {
            val overflow = if (pendingDebugCommands.size >= MAX_PENDING_DEBUG_COMMANDS) {
                pendingDebugCommands.removeFirst()
            } else {
                null
            }
            pendingDebugCommands.addLast(pending)
            overflow
        }
        dropped?.let {
            logDebugResult(it.command, "result=error message=runtime_initialization_queue_full")
        }
        // This is deliberately not result=ok: the ADB harness continues waiting until the
        // command has actually executed against the fully started controller.
        Log.i(DEBUG_LOG_TAG, "command=$command state=queued readiness=INITIALIZING")
    }

    private fun drainPendingDebugCommands() {
        while (readyController() != null) {
            val pending = synchronized(pendingDebugCommands) {
                if (pendingDebugCommands.isEmpty()) null else pendingDebugCommands.removeFirst()
            } ?: return
            if (pending.generation != initializationGeneration.get()) {
                logDebugResult(pending.command, "result=error message=stale_runtime_generation")
                continue
            }
            handleDebugCommand(pending.intent)
        }
    }

    private fun failPendingDebugCommands(message: String) {
        val pending = synchronized(pendingDebugCommands) {
            pendingDebugCommands.toList().also { pendingDebugCommands.clear() }
        }
        pending.forEach { command ->
            logDebugResult(command.command, "result=error message=$message")
        }
    }

    private fun requireDebugStagedFile(intent: Intent): File {
        val supplied = intent.requiredStringExtra(EXTRA_DEBUG_FILE_PATH)
        val candidate = File(supplied).canonicalFile
        val roots = buildList {
            add(File(filesDir, DEBUG_IMPORT_DIRECTORY).canonicalFile)
            getExternalFilesDir(null)?.let { add(File(it, DEBUG_IMPORT_DIRECTORY).canonicalFile) }
        }
        val isInsideStagingRoot = roots.any { root ->
            val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
            candidate.path.startsWith(rootPrefix, ignoreCase = true)
        }
        require(isInsideStagingRoot && candidate.isFile) {
            "Debug import must be a readable file under the app's $DEBUG_IMPORT_DIRECTORY directory"
        }
        return candidate
    }

    private fun requireDebugStagedPackDirectory(intent: Intent): DebugPackStagingBatch {
        val supplied = intent.requiredStringExtra(EXTRA_DEBUG_DIRECTORY_PATH)
        val roots = buildList {
            add(File(filesDir, DEBUG_IMPORT_DIRECTORY).canonicalFile)
            getExternalFilesDir(null)?.let { add(File(it, DEBUG_IMPORT_DIRECTORY).canonicalFile) }
        }
        return DebugPackStagingPolicy.requireBatch(
            suppliedDirectory = supplied,
            allowedRoots = roots,
            maximumPacks = MAX_DEBUG_BATCH_PACKS,
        )
    }

    private fun logDebugSnapshot(reason: String) {
        if (!BuildConfig.DEBUG) return
        val active = readyController()
        if (active == null) {
            Log.i(
                DEBUG_LOG_TAG,
                "snapshot reason=$reason ui_visible=$uiVisible runtime_started=false readiness=INITIALIZING",
            )
            return
        }
        Log.i(
            DEBUG_LOG_TAG,
            "snapshot reason=$reason ui_visible=$uiVisible runtime_started=true " +
                DriveRuntimeDiagnostics.conciseSummary(active.snapshot()),
        )
    }

    private fun logDebugResult(command: String, result: String) {
        if (!BuildConfig.DEBUG) return
        val message = "command=$command $result"
        Log.i(DEBUG_LOG_TAG, message)
        DebugEventLog.warning("adb_debug_command", message)
    }

    inner class DriveRuntimeBinder internal constructor() : Binder() {
        /** Null means foreground service ownership exists but its runtime is still initializing. */
        fun snapshot(): DriveSnapshot? = readyController()?.snapshot()

        fun isReady(): Boolean = readyController() != null

        fun isInitializing(): Boolean =
            initializationInFlight.get() && !stopping.get() && !destroyed.get()

        fun setUiVisible(visible: Boolean) = this@DriveRuntimeService.setUiVisible(visible)

        fun isUiVisible(): Boolean = uiVisible

        // Positive manual inputs are intentionally dropped before readiness. Replaying them after
        // a slow startup could leave the simulated accelerator stuck without a matching key-up.
        fun setManualThrottle(value: Double) {
            readyController()?.setManualThrottle(value)
        }

        fun setManualBrake(value: Double) {
            readyController()?.setManualBrake(value)
        }

        fun cycleInputMode() {
            readyController()?.cycleInputMode()
        }

        fun setTransmissionPosition(position: TransmissionPosition) {
            readyController()?.setTransmissionPosition(position)
        }

        fun toggleSound() = setSoundEnabled(!sessionStore.read().soundEnabled)

        fun setTuning(config: TuningConfig) {
            readyController()?.setTuning(config)
        }

        fun resetTuning() {
            readyController()?.resetTuning()
        }

        fun restartVehicleReader() {
            readyController()?.restartVehicleReader()
        }

        fun runSampleAudioValidation(): Boolean {
            val active = readyController() ?: return false
            active.runSampleAudioValidation()
            return true
        }

        fun selectPreviousCar() {
            val active = readyController() ?: return
            active.selectPreviousCar()
            refreshNotificationIfChanged()
        }

        fun selectNextCar() {
            val active = readyController() ?: return
            active.selectNextCar()
            refreshNotificationIfChanged()
        }

        fun selectCar(profileId: String) {
            if (readyController()?.selectCar(profileId) == true) refreshNotificationIfChanged()
        }

        internal fun selectCarOrAutoInstall(profileId: String, onProgress: (String, Int?, Result<Unit>?) -> Unit) =
            this@DriveRuntimeService.selectCarOrAutoInstall(profileId, onProgress)

        internal fun catalogSnapshot(): CarCatalogSnapshot? = readyController()?.catalogSnapshot()

        internal fun toggleFavorite(carId: String): CarCatalogSnapshot? =
            readyController()?.toggleFavorite(carId)

        internal fun importPacks(
            sources: List<Uri>,
            onComplete: (Result<Unit>) -> Unit,
        ) = this@DriveRuntimeService.importPacks(sources, onComplete)

        internal fun importGeneratedCatalog(
            source: Uri,
            onComplete: (Result<Unit>) -> Unit,
        ) = this@DriveRuntimeService.importGeneratedCatalog(source, onComplete)

        fun auditionPopsAndBangs() {
            readyController()?.auditionPopsAndBangs()
        }

        fun setLayerMixMuted(trackId: String, muted: Boolean) {
            readyController()?.setLayerMixMuted(trackId, muted)
        }

        fun setLayerMixSolo(trackId: String, solo: Boolean) {
            readyController()?.setLayerMixSolo(trackId, solo)
        }

        fun setLayerMixVolume(trackId: String, volume: Double) {
            readyController()?.setLayerMixVolume(trackId, volume)
        }

        fun setSoundEffectEnabled(controlId: String, enabled: Boolean) {
            readyController()?.setSoundEffectEnabled(controlId, enabled)
        }

        fun setSoloSoundEffects(enabled: Boolean) {
            readyController()?.setSoloSoundEffects(enabled)
        }

        fun setDebugPanelVisible(visible: Boolean) {
            readyController()?.setDebugPanelVisible(visible)
        }

        fun setCarMasterVolume(volume: Double) {
            readyController()?.setCarMasterVolume(volume)
        }

        fun markCrackle() {
            readyController()?.snapshot()?.let(DriveRuntimeDiagnostics::markCrackle)
        }

        fun exportDiagnostics(destination: Uri, onComplete: (Boolean) -> Unit = {}) =
            this@DriveRuntimeService.exportDiagnostics(destination, onComplete)

        fun stopDrivingSession() = requestUserStop("binder")
    }

    private data class NotificationState(
        val selectedCarName: String,
        val soundEnabled: Boolean,
    )

    private data class PendingDebugCommand(
        val intent: Intent,
        val command: String,
        val generation: Long,
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "drive_runtime"
        private const val NOTIFICATION_ID = 10_041
        private const val MINIMUM_STOP_FADE_MILLIS = 200L
        private const val MAXIMUM_STOP_FADE_MILLIS = 2_500L
        private const val STOP_FADE_TIME_CONSTANTS = 5.0
        private const val REQUEST_OPEN_DASHBOARD = 1
        private const val REQUEST_TOGGLE_SOUND = 2
        private const val REQUEST_STOP = 3
        private const val ACTION_START =
            "com.gabrielpc.enginesoundsimulator.action.START_DRIVE_RUNTIME"
        private const val ACTION_TOGGLE_SOUND =
            "com.gabrielpc.enginesoundsimulator.action.TOGGLE_DRIVE_SOUND"
        private const val ACTION_STOP =
            "com.gabrielpc.enginesoundsimulator.action.STOP_DRIVE_RUNTIME"
        internal const val DEBUG_CONTROL_ACTION =
            "com.gabrielpc.enginesoundsimulator.debug.CONTROL"
        private const val ACTION_DEBUG_COMMAND =
            "com.gabrielpc.enginesoundsimulator.action.DEBUG_DRIVE_COMMAND"
        internal const val EXTRA_DEBUG_COMMAND = "command"
        private const val EXTRA_DEBUG_VALUE = "value"
        private const val EXTRA_DEBUG_THROTTLE = "throttle"
        private const val EXTRA_DEBUG_BRAKE = "brake"
        private const val EXTRA_DEBUG_CAR_ID = "car_id"
        private const val EXTRA_DEBUG_FILE_PATH = "file_path"
        private const val EXTRA_DEBUG_DIRECTORY_PATH = "directory_path"
        private const val EXTRA_DEBUG_SOUND_ENABLED = "sound_enabled"
        private const val DEBUG_IMPORT_DIRECTORY = "adb-import"
        private const val DEBUG_EXPORT_DIRECTORY = "adb-export"
        private const val DEBUG_EXPORT_FILE_NAME = "drive-diagnostics.jsonl"
        private const val DEBUG_COMMAND_SET_PEDALS = "set_pedals"
        private const val DEBUG_COMMAND_SET_THROTTLE = "set_throttle"
        private const val DEBUG_COMMAND_SET_BRAKE = "set_brake"
        private const val DEBUG_COMMAND_RESET_PEDALS = "reset_pedals"
        private const val DEBUG_COMMAND_SET_INPUT_MODE = "set_input_mode"
        private const val DEBUG_COMMAND_SET_TRANSMISSION = "set_transmission"
        private const val DEBUG_COMMAND_SET_SOUND_ENABLED = "set_sound_enabled"
        private const val DEBUG_COMMAND_SELECT_CAR = "select_car"
        private const val DEBUG_COMMAND_TOGGLE_FAVORITE = "toggle_favorite"
        private const val DEBUG_COMMAND_IMPORT_CATALOG = "import_catalog"
        private const val DEBUG_COMMAND_IMPORT_PACK = "import_pack"
        private const val DEBUG_COMMAND_IMPORT_PACKS = "import_packs"
        private const val DEBUG_COMMAND_AUDITION_POPS_BANGS = "audition_pops_bangs"
        private const val DEBUG_COMMAND_MARK_CRACKLE = "mark_crackle"
        private const val DEBUG_COMMAND_EXPORT_DIAGNOSTICS = "export_diagnostics"
        private const val DEBUG_COMMAND_STABILIZE_MEMORY = "stabilize_memory"
        private const val DEBUG_COMMAND_STOP = "stop_runtime"
        private const val DEBUG_COMMAND_RUN_VALIDATION = "run_validation"
        private const val DEBUG_COMMAND_LOG_SNAPSHOT = "log_snapshot"
        private const val DEBUG_VALIDATION_LOG_DELAY_MILLIS = 20_500L
        private const val DEBUG_MEMORY_STABILIZATION_DELAY_MILLIS = 100L
        private const val DEBUG_LOG_TAG = "BYDDriveDebug"
        private const val MAX_PENDING_DEBUG_COMMANDS = 32
        private const val MAX_DEBUG_BATCH_PACKS = 153

        /** Explicit dashboard entry clears a previous task/user stop and starts a new session. */
        fun startDrivingSession(context: Context) {
            DriveRuntimeSessionStore(context).recordExplicitStart()
            ContextCompat.startForegroundService(
                context,
                Intent(context, DriveRuntimeService::class.java).setAction(ACTION_START),
            )
        }

        /** Called only by the receiver compiled into the debug source set. */
        internal fun forwardDebugCommand(context: Context, source: Intent) {
            if (!BuildConfig.DEBUG) return
            val isStop = source.getStringExtra(EXTRA_DEBUG_COMMAND)
                ?.trim()
                ?.equals(DEBUG_COMMAND_STOP, ignoreCase = true) == true
            if (!isStop) DriveRuntimeSessionStore(context).recordExplicitStart()
            val commandIntent = Intent(context, DriveRuntimeService::class.java)
                .setAction(ACTION_DEBUG_COMMAND)
            source.extras?.let(commandIntent::putExtras)
            if (isStop) {
                // The harness issues Stop only against an already-running foreground service.
                // Do not turn a stop request into a new requested/sticky session.
                context.startService(commandIntent)
            } else {
                ContextCompat.startForegroundService(context, commandIntent)
            }
        }
    }
}

/** Android-free acceptance rule for publishing a controller built on the initializer thread. */
internal object DriveRuntimeInitializationPolicy {
    fun shouldPublish(
        completedGeneration: Long,
        currentGeneration: Long,
        sessionState: DriveRuntimeSessionState,
        stopping: Boolean,
        destroyed: Boolean,
    ): Boolean =
        completedGeneration == currentGeneration &&
            !stopping &&
            !destroyed &&
            DriveRuntimeSessionPolicy.shouldRun(sessionState)
}

private fun Intent.requiredStringExtra(name: String): String =
    getStringExtra(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing --es $name <value>")

@Suppress("DEPRECATION")
private fun Intent.requiredNumberExtra(name: String): Double =
    (extras?.get(name) as? Number)?.toDouble()
        ?: getStringExtra(name)?.toDoubleOrNull()
        ?: error("Missing --ef $name <number>")

private fun Intent.requiredBooleanExtra(name: String): Boolean =
    booleanExtra(name) ?: error("Missing --ez $name <true|false>")

@Suppress("DEPRECATION")
private fun Intent.booleanExtra(name: String): Boolean? = when (val value = extras?.get(name)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> when (value.trim().lowercase(java.util.Locale.US)) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
    else -> null
}

private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: error("Unknown $label '$value'; expected ${enumValues<T>().joinToString { it.name }}")
