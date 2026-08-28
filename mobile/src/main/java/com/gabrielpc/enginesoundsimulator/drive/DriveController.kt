package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioOutputState
import com.gabrielpc.enginesoundsimulator.audio.CarMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixRepository
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.audio.SampleLayerRole
import com.gabrielpc.enginesoundsimulator.audio.SoundEffectsRepository
import com.gabrielpc.enginesoundsimulator.audio.AuthoredCarMetadata
import com.gabrielpc.enginesoundsimulator.audio.authoredCarMetadata
import com.gabrielpc.enginesoundsimulator.audio.engineSampleProfileFor
import com.gabrielpc.enginesoundsimulator.audio.mixerDisplayName
import com.gabrielpc.enginesoundsimulator.audio.mixerTrackOrder
import com.gabrielpc.enginesoundsimulator.audio.toTurboControllerBank
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogEntry
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogRepository
import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogSnapshot
import com.gabrielpc.enginesoundsimulator.catalog.InstalledSoundFamily
import com.gabrielpc.enginesoundsimulator.catalog.SelectedOfficialCarRepository
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.RealtimeDrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.resolvedTransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.transmissionFollowsVehicle
import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
import com.gabrielpc.enginesoundsimulator.tuning.withSampleProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val displayName: String) {
    AUTO("AUTO"),
    SIMULATOR("SIM"),
    VEHICLE("BYD LIVE"),
}

data class DriveSnapshot(
    val coreSteps: Long,
    val drivetrain: DrivetrainState,
    val inputMode: InputMode,
    val activeInput: String,
    val throttle: Double,
    val brake: Double,
    val transmissionPosition: TransmissionPosition,
    val engineSoundEnabled: Boolean,
    val audio: AudioOutputState,
    val telemetry: TelemetrySnapshot,
    val tuning: TuningConfig,
    val selectedCarId: String,
    val selectedCarName: String,
    val selectedCarPreviewAsset: String,
    val selectedCarIndex: Int,
    val availableCarCount: Int,
    val soundEffects: List<SoundEffectOption>,
    val popsAndBangsAuditionAvailable: Boolean = false,
    val soloSoundEffects: Boolean,
    val sampleLayerCoverage: List<SampleLayerCoverage> = emptyList(),
    val layerMixTracks: List<LayerMixTrackState> = emptyList(),
    val carMasterVolume: Double = CarMasterVolumeRepository.DEFAULT,
    val transmissionLockedToVehicle: Boolean = false,
    /** Monotonic proof that a Compose/debug presentation snapshot was actually constructed. */
    val uiSnapshotBuildCount: Long = 0L,
)

data class SoundEffectOption(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
)

data class SampleLayerCoverage(
    val id: String,
    val role: String,
    val startRpm: Double,
    val endRpm: Double,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(
    context: Context,
    initialSoundEnabled: Boolean = true,
) : DriveRuntimePrimitiveState {
    private val applicationContext = context.applicationContext
    private val tuningRepository = TuningRepository(applicationContext)
    private val catalogRepository = CarCatalogRepository(applicationContext)
    private val selectedCarRepository = SelectedOfficialCarRepository(applicationContext)
    private val soundEffectsRepository = SoundEffectsRepository(applicationContext)
    private val layerMixRepository = LayerMixRepository(applicationContext)
    private val carMasterVolumeRepository = CarMasterVolumeRepository(applicationContext)
    private val catalogState = AtomicReference(catalogRepository.snapshot())
    private val initialCatalogEntry = resolveInitialCatalogEntry(catalogState.get(), selectedCarRepository)
    private val initialInstalledFamily = catalogRepository.installedFamilyForCar(initialCatalogEntry.id)
    private val selectedCatalogEntry = AtomicReference(initialCatalogEntry)
    private val selectedSampleProfile = AtomicReference(
        profileFor(initialCatalogEntry, initialInstalledFamily),
    )
    private val layerMixControls = AtomicReference(layerMixRepository.load(selectedSampleProfile.get()))
    private val enabledEffectMask = AtomicLong(soundEffectsRepository.loadEnabledMask(selectedSampleProfile.get()))
    private val soloEffects = AtomicBoolean(soundEffectsRepository.loadSoloEffects(selectedSampleProfile.get()))
    private val tuningConfig = AtomicReference(tuningRepository.load().withSampleProfile(selectedSampleProfile.get()))
    private val carMasterVolume = AtomicReference(carMasterVolumeRepository.load(initialCatalogEntry.id))
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile(selectedSampleProfile.get())
    private val simulation = EngineSimulation(profile)
    private val runtimeDrivetrain = RealtimeDrivetrainState().also(simulation::publishSnapshot)
    private val vehicleReader = BydSpeedReader(applicationContext)
    private val audioEngine = EngineAudioEngine(applicationContext)
    private val lifecycleLock = Any()
    /** Serializes multi-field car/profile publication against asynchronous catalog imports. */
    private val carSelectionLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val manualInput = AtomicReference(ManualInput())
    private val selectedInputMode = AtomicReference(InputMode.AUTO)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val soundEnabled = AtomicBoolean(initialSoundEnabled)
    /**
     * True only after this controller has deliberately created its audio runtime. A persisted
     * startup mute leaves this false, which prevents profile decode as well as AudioTrack/focus
     * ownership. Once audio has started, ordinary runtime mute remains phase-preserving.
     */
    private val audioRuntimeStarted = AtomicBoolean(false)
    private val debugPanelVisible = AtomicBoolean(false)
    private val validationThread = AtomicReference<Thread?>(null)
    private val uiSnapshotBuildCounter = AtomicLong(0L)

    @Volatile
    private var loopThread: Thread? = null

    // The 200 Hz loop publishes only its small runtime values. Compose-facing lists,
    // labels, meters and copies are assembled exclusively when a visible client asks.
    @Volatile private var latestInputMode = InputMode.AUTO
    @Volatile private var latestActiveInput = "SIM FALLBACK"
    @Volatile private var latestThrottle = 0.0
    @Volatile private var latestBrake = 0.0
    @Volatile private var latestTransmissionPosition = TransmissionPosition.DRIVE
    @Volatile private var latestTransmissionLockedToVehicle = false
    @Volatile private var coreSteps = 0L
    private var cachedAudioSource = appliedTuning.audio
    private var cachedCarMasterVolume = carMasterVolume.get()
    private var cachedEffectiveAudioTuning = calculateEffectiveAudioTuning(cachedAudioSource, cachedCarMasterVolume)

    fun setDebugPanelVisible(visible: Boolean) {
        debugPanelVisible.set(visible)
    }

    fun snapshot(): DriveSnapshot {
        val snapshotBuild = uiSnapshotBuildCounter.incrementAndGet()
        val liveAudio = audioEngine.state()
        val catalog = catalogState.get()
        val catalogEntry = selectedCatalogEntry.get()
        val selectedCar = selectedSampleProfile.get()
        val outputLevels = liveAudio.layerOutputMeters.associate { it.id to it.outputLevel }
        return DriveSnapshot(
            coreSteps = coreSteps,
            drivetrain = runtimeDrivetrain.snapshot(),
            inputMode = latestInputMode,
            activeInput = latestActiveInput,
            throttle = latestThrottle,
            brake = latestBrake,
            transmissionPosition = latestTransmissionPosition,
            engineSoundEnabled = soundEnabled.get(),
            audio = liveAudio,
            telemetry = vehicleReader.snapshot(),
            tuning = tuningConfig.get(),
            selectedCarId = catalogEntry.id,
            selectedCarName = catalogEntry.displayName,
            selectedCarPreviewAsset = catalogEntry.previewFile?.absolutePath.orEmpty(),
            selectedCarIndex = catalog.entries.indexOfFirst { it.id == catalogEntry.id }.coerceAtLeast(0),
            availableCarCount = catalog.entries.size,
            soundEffects = soundEffectOptions(selectedCar, enabledEffectMask.get()),
            popsAndBangsAuditionAvailable = selectedCar.effects.any { it.auditionable },
            soloSoundEffects = soloEffects.get(),
            sampleLayerCoverage = selectedCar.layers.map { layer ->
                SampleLayerCoverage(
                    id = layer.id,
                    role = layer.role.name,
                    startRpm = layer.startRpm,
                    endRpm = layer.endRpm,
                )
            },
            layerMixTracks = buildLayerMixTracks(
                selectedCar,
                layerMixControls.get(),
                outputLevels,
            ),
            carMasterVolume = carMasterVolume.get(),
            transmissionLockedToVehicle = latestTransmissionLockedToVehicle,
            uiSnapshotBuildCount = snapshotBuild,
        )
    }

    /** Background-service reads deliberately avoid renderer diagnostics and presentation lists. */
    override fun selectedCarDisplayName(): String = selectedCatalogEntry.get().displayName

    override fun shutdownFadeTimeConstantMillis(): Double = tuningConfig.get().audio.enabledFadeMs

    override fun uiSnapshotBuildCount(): Long = uiSnapshotBuildCounter.get()

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && loopThread?.isAlive == true) return
            val previous = loopThread
            if (previous?.isAlive == true) {
                previous.interrupt()
                if (!joinLoop(previous)) return
            }

            // Start each visible/controller session with a fresh source line and heartbeat.
            val runId = generation.incrementAndGet()
            running.set(true)
            val thread = Thread({ runLoop(runId) }, "drivetrain-simulation").apply { isDaemon = true }
            loopThread = thread
            try {
                vehicleReader.start()
                if (DriveAudioResourcePolicy.shouldStartOnControllerStart(soundEnabled.get())) {
                    startAudioRuntimeLocked()
                }
                thread.start()
            } catch (throwable: Throwable) {
                running.set(false)
                generation.incrementAndGet()
                loopThread = null
                vehicleReader.stop()
                audioEngine.stop()
                DebugEventLog.recordThrowable("drive_controller_start_failed", throwable)
                throw throwable
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            generation.incrementAndGet()
            val thread = loopThread
            thread?.interrupt()
            if (thread == null || joinLoop(thread)) loopThread = null
            validationThread.getAndSet(null)?.let { validation ->
                validation.interrupt()
                joinLoop(validation)
            }
            vehicleReader.stop()
            audioEngine.stop()
            audioRuntimeStarted.set(false)
            manualInput.set(ManualInput())
        }
    }

    /** Final service teardown; unlike [stop], this also terminates decoder ownership. */
    fun close() {
        stop()
        audioEngine.close()
    }

    fun setManualThrottle(value: Double) {
        manualInput.updateAndGet { it.copy(throttle = value.coerceIn(0.0, 1.0)) }
    }

    fun setManualBrake(value: Double) {
        manualInput.updateAndGet { it.copy(brake = value.coerceIn(0.0, 1.0)) }
    }

    fun setInputMode(mode: InputMode) {
        selectedInputMode.set(mode)
    }

    fun setTuning(config: TuningConfig) {
        val clean = config.sanitized()
        tuningConfig.set(clean)
        tuningRepository.save(clean)
    }

    fun resetTuning() {
        val clean = tuningRepository.reset().withSampleProfile(selectedSampleProfile.get())
        tuningConfig.set(clean)
        tuningRepository.save(clean)
    }

    fun selectPreviousCar() = selectAdjacentCar(-1)

    fun selectNextCar() = selectAdjacentCar(1)

    fun selectCar(carId: String): Boolean {
        return synchronized(carSelectionLock) {
            val selected = catalogState.get().find(carId) ?: return@synchronized false
            val family = catalogRepository.installedFamilyForCar(carId) ?: return@synchronized false
            applySelectedCar(selected, family)
            true
        }
    }

    internal fun catalogSnapshot(): CarCatalogSnapshot = catalogState.get()

    internal fun toggleFavorite(carId: String): CarCatalogSnapshot {
        val updated = catalogRepository.toggleFavorite(carId)
        publishCatalog(updated)
        return updated
    }

    /** Blocking import entry point; the foreground service invokes it only on its I/O worker. */
    internal fun importPack(uri: android.net.Uri): CarCatalogSnapshot {
        val updated = catalogRepository.importPack(uri)
        publishCatalog(updated, refreshSelectedProfile = true)
        return updated
    }

    /** Blocking batch import; every pack remains atomic and catalog publication happens once. */
    internal fun importPacks(uris: List<android.net.Uri>): CarCatalogSnapshot {
        val updated = catalogRepository.importPacks(uris)
        publishCatalog(updated, refreshSelectedProfile = true)
        return updated
    }

    internal fun autoInstallCar(carId: String, progress: (String, Int?) -> Unit = { _, _ -> }): CarCatalogSnapshot {
        val updated = catalogRepository.autoInstallForCar(carId, progress)
        catalogState.set(updated)
        return updated
    }

    /** Blocking catalog entry point; the foreground service invokes it only on its I/O worker. */
    internal fun importGeneratedCatalog(uri: android.net.Uri): CarCatalogSnapshot {
        val updated = applicationContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected catalog could not be opened" }
            catalogRepository.importGeneratedCatalog(input)
        }
        publishCatalog(updated, refreshSelectedProfile = true)
        return updated
    }

    fun auditionPopsAndBangs() = audioEngine.auditionPopsAndBangs()

    fun setLayerMixVolume(trackId: String, volume: Double) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setVolume(profile, trackId, volume))
    }

    fun setLayerMixMuted(trackId: String, muted: Boolean) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setMuted(profile, trackId, muted))
    }

    fun setLayerMixSolo(trackId: String, solo: Boolean) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setSolo(profile, trackId, solo))
    }

    fun setCarMasterVolume(volume: Double) {
        val profileId = selectedSampleProfile.get().id
        carMasterVolume.set(carMasterVolumeRepository.save(profileId, volume))
    }

    fun resetAllCarMasterVolumes() {
        carMasterVolumeRepository.resetAll()
        carMasterVolume.set(carMasterVolumeRepository.load(selectedSampleProfile.get().id))
    }

    fun setSoundEffectEnabled(controlId: String, enabled: Boolean) {
        val selected = selectedSampleProfile.get()
        val updatedMask = soundEffectsRepository.setEnabled(selected, controlId, enabled)
        enabledEffectMask.set(updatedMask)
    }

    fun setSoloSoundEffects(enabled: Boolean) {
        val selected = selectedSampleProfile.get()
        soundEffectsRepository.setSoloEffects(selected, enabled)
        soloEffects.set(enabled)
    }

    private fun selectAdjacentCar(offset: Int) {
        synchronized(carSelectionLock) {
            val installed = catalogState.get().entries.filter(CarCatalogEntry::installed)
            if (installed.isEmpty()) return@synchronized
            val previousId = selectedCatalogEntry.get().id
            val previousIndex = installed.indexOfFirst { it.id == previousId }
            val selected = installed[(previousIndex.coerceAtLeast(0) + offset).mod(installed.size)]
            if (selected.id == previousId) return@synchronized
            val family = catalogRepository.installedFamilyForCar(selected.id) ?: return@synchronized
            applySelectedCar(selected, family)
        }
    }

    private fun applySelectedCar(selected: CarCatalogEntry, family: InstalledSoundFamily?) {
        val previousCarId = selectedCatalogEntry.get().id
        val sampleProfile = profileFor(selected, family)
        selectedCatalogEntry.set(selected)
        selectedSampleProfile.set(sampleProfile)
        enabledEffectMask.set(soundEffectsRepository.loadEnabledMask(sampleProfile))
        soloEffects.set(soundEffectsRepository.loadSoloEffects(sampleProfile))
        layerMixControls.set(layerMixRepository.load(sampleProfile))
        carMasterVolume.set(carMasterVolumeRepository.load(selected.id))
        selectedCarRepository.save(selected.id)
        val tuning = tuningConfig.get().withSampleProfile(sampleProfile)
        tuningConfig.set(tuning)
        tuningRepository.save(tuning)
        if (previousCarId != selected.id) {
            if (sampleProfile.hasEngineStart) {
                simulation.beginEngineStart()
                audioEngine.scheduleEngineStart()
            } else {
                simulation.cancelEngineStart()
            }
        }
        if (DriveAudioResourcePolicy.shouldPrepareSelectedProfile(audioRuntimeStarted.get())) {
            selectAudioProfile(sampleProfile, family)
        }
    }

    private fun selectAudioProfile(profile: EngineSampleProfile, family: InstalledSoundFamily?) {
        if (family == null) {
            audioEngine.selectUninstalledProfile(profile)
        } else {
            audioEngine.setInstalledFamily(family, profile.id)
        }
    }

    private fun publishCatalog(updated: CarCatalogSnapshot, refreshSelectedProfile: Boolean = false) {
        synchronized(carSelectionLock) {
            catalogState.set(updated)
            val currentId = selectedCatalogEntry.get().id
            val refreshed = updated.find(currentId) ?: return@synchronized
            selectedCatalogEntry.set(refreshed)
            if (refreshSelectedProfile) {
                applySelectedCar(refreshed, catalogRepository.installedFamilyForCar(currentId))
            }
        }
    }

    fun cycleInputMode() {
        val modes = InputMode.entries
        val current = selectedInputMode.get()
        setInputMode(modes[(current.ordinal + 1) % modes.size])
    }

    fun setTransmissionPosition(position: TransmissionPosition) {
        val telemetry = vehicleReader.snapshot()
        if (telemetry.transmissionFollowsVehicle(selectedInputMode.get())) {
            return
        }
        transmissionPosition.set(position)
    }

    fun restartVehicleReader() {
        vehicleReader.restart()
    }

    fun toggleSound() {
        setSoundEnabled(!soundEnabled.get())
    }

    fun setSoundEnabled(enabled: Boolean) {
        synchronized(lifecycleLock) {
            soundEnabled.set(enabled)
            if (running.get()) {
                // Muting is an allocation-free renderer fade. Keep AudioTrack and
                // the decoded native family alive so unmute resumes the same loop
                // phases instead of falling back to the silent bootstrap profile.
                if (enabled) startAudioRuntimeLocked()
            }
        }
    }

    /** Must be called with [lifecycleLock]. Loads the current car exactly once before first play. */
    private fun startAudioRuntimeLocked() {
        if (!audioRuntimeStarted.get()) {
            selectAudioProfile(selectedSampleProfile.get(), catalogRepository.installedFamilyForCar(
                selectedCatalogEntry.get().id,
            ))
        }
        audioEngine.start()
        audioRuntimeStarted.set(true)
    }

    /**
     * Starts the renderer's normal enabled-gain fade without tearing down AudioTrack. The
     * foreground service calls this shortly before its final stop so task dismissal and the
     * notification Stop action cannot create an abrupt full-scale edge.
     */
    fun beginShutdownFade() {
        soundEnabled.set(false)
    }

    /** Runs a deterministic pedal program for on-device sample-renderer and telemetry validation. */
    fun runSampleAudioValidation(forceSoundEnabled: Boolean = true) {
        synchronized(lifecycleLock) {
            if (validationThread.get()?.isAlive == true) {
                DebugEventLog.warning("sample_validation_already_running")
                return
            }
            selectedInputMode.set(InputMode.SIMULATOR)
            transmissionPosition.set(TransmissionPosition.DRIVE)
            manualInput.set(ManualInput())
            if (forceSoundEnabled && !soundEnabled.getAndSet(true) && running.get()) {
                audioEngine.start()
            }

            val validation = Thread(
                {
                    var completed = false
                    try {
                        var previousThrottle = 0.0
                        VALIDATION_STAGES.forEach { stage ->
                            val stageStarted = SystemClock.elapsedRealtime()
                            while (SystemClock.elapsedRealtime() - stageStarted < stage.durationMs) {
                                val elapsed = SystemClock.elapsedRealtime() - stageStarted
                                val ramp = (elapsed / VALIDATION_RAMP_MS.toDouble()).coerceIn(0.0, 1.0)
                                val throttle = previousThrottle + (stage.throttle - previousThrottle) * ramp
                                manualInput.set(ManualInput(throttle = throttle, brake = 0.0))
                                Thread.sleep(VALIDATION_UPDATE_MS)
                            }
                            previousThrottle = stage.throttle
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        manualInput.set(ManualInput())
                        validationThread.compareAndSet(Thread.currentThread(), null)
                    }
                },
                "sample-audio-validation",
            ).apply { isDaemon = true }
            validationThread.set(validation)
            validation.start()
        }
    }

    private fun runLoop(runId: Long) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            var previousNanos = SystemClock.elapsedRealtimeNanos()
            var accumulatorSeconds = 0.0

            while (isCurrent(runId)) {
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val elapsedSeconds = ((nowNanos - previousNanos) / 1_000_000_000.0).coerceIn(0.0, 0.050)
                previousNanos = nowNanos
                accumulatorSeconds += elapsedSeconds

                while (accumulatorSeconds >= FIXED_STEP_SECONDS && isCurrent(runId)) {
                    step(FIXED_STEP_SECONDS)
                    accumulatorSeconds -= FIXED_STEP_SECONDS
                }

                val remaining = FIXED_STEP_NANOS - (SystemClock.elapsedRealtimeNanos() - nowNanos)
                if (remaining > 0L) LockSupport.parkNanos(remaining)
            }
        } catch (throwable: Throwable) {
            DebugEventLog.recordThrowable("drive_loop_failed", throwable, "generation=$runId")
            throw throwable
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun joinLoop(thread: Thread): Boolean {
        if (thread === Thread.currentThread()) return false
        try {
            thread.join(LOOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    private fun step(dt: Double) {
        val tuning = tuningConfig.get()
        if (tuning !== appliedTuning) {
            profile = tuning.toEngineProfile(selectedSampleProfile.get())
            simulation.updateProfile(profile)
            appliedTuning = tuning
        }
        val telemetry = vehicleReader.snapshot()
        val mode = selectedInputMode.get()
        val manual = manualInput.get()
        val vehicleAvailable = telemetry.vehiclePedalsAvailable()
        val useVehiclePedals = vehicleAvailable && mode != InputMode.SIMULATOR
        val resolvedThrottle: Double
        val resolvedBrake: Double
        val externalSpeedKmh: Double?
        val inputLabel: String
        val simulatorInput: Boolean
        if (useVehiclePedals) {
            resolvedThrottle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0)
            resolvedBrake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0)
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid }
            inputLabel = "BYD PEDALS"
            simulatorInput = false
        } else if (mode == InputMode.VEHICLE) {
            resolvedThrottle = 0.0
            resolvedBrake = 0.0
            externalSpeedKmh = null
            inputLabel = "BYD UNAVAILABLE"
            simulatorInput = false
        } else {
            resolvedThrottle = manual.throttle.coerceIn(0.0, 1.0)
            resolvedBrake = manual.brake.coerceIn(0.0, 1.0)
            externalSpeedKmh = null
            inputLabel = "SIM PEDALS"
            simulatorInput = true
        }

        val transmissionLocked = telemetry.transmissionFollowsVehicle(mode)
        val resolvedTransmission = if (transmissionLocked) {
            telemetry.resolvedTransmissionPosition() ?: transmissionPosition.get()
        } else {
            transmissionPosition.get()
        }
        if (transmissionLocked) {
            transmissionPosition.set(resolvedTransmission)
        }

        simulation.updateRealtime(
            throttle = resolvedThrottle,
            brake = resolvedBrake,
            externalSpeedKmh = externalSpeedKmh,
            // AUTO falls back to the same SIM pedals when BYD input is unavailable.
            simulateCoastRegen = simulatorInput,
            transmissionPosition = resolvedTransmission,
            deltaSeconds = dt,
            destination = runtimeDrivetrain,
        )
        val enabled = soundEnabled.get()
        audioEngine.updateCore(
            rpm = runtimeDrivetrain.rpm,
            drivetrainRpm = (runtimeDrivetrain.speedKmh.coerceAtLeast(0.0) / 3.6) /
                (2.0 * Math.PI * profile.wheelRadiusMeters) * 60.0,
            physicalPedal = resolvedThrottle,
            // Seal EV pedal filtering belongs to motion only. AC/FM0D receives the driver's
            // unsmoothed pedal, then applies its own authored gearbox-assist state.
            throttle = resolvedThrottle,
            enabled = enabled,
            enabledEffectMask = enabledEffectMask.get(),
            soloEffects = soloEffects.get(),
            shiftSerial = runtimeDrivetrain.shiftSerial,
            shiftDirection = when (runtimeDrivetrain.shiftDirection) {
                ShiftDirection.UP -> 1
                ShiftDirection.DOWN -> -1
                ShiftDirection.NONE -> 0
            },
            isShifting = runtimeDrivetrain.isShifting,
            gear = runtimeDrivetrain.gear,
            limiterActive = runtimeDrivetrain.limiterActive,
            tuning = effectiveAudioTuning(tuning),
            layerMix = layerMixControls.get(),
        )
        latestInputMode = mode
        latestActiveInput = inputLabel
        latestThrottle = resolvedThrottle
        latestBrake = resolvedBrake
        latestTransmissionPosition = resolvedTransmission
        latestTransmissionLockedToVehicle = transmissionLocked
        coreSteps += 1L
    }

    private fun effectiveAudioTuning(tuning: TuningConfig): com.gabrielpc.enginesoundsimulator.tuning.AudioTuning {
        val volume = carMasterVolume.get()
        if (tuning.audio !== cachedAudioSource || volume != cachedCarMasterVolume) {
            cachedAudioSource = tuning.audio
            cachedCarMasterVolume = volume
            cachedEffectiveAudioTuning = calculateEffectiveAudioTuning(tuning.audio, volume)
        }
        return cachedEffectiveAudioTuning
    }

    private fun calculateEffectiveAudioTuning(
        audio: com.gabrielpc.enginesoundsimulator.tuning.AudioTuning,
        volume: Double,
    ) = audio.copy(
        masterGain = (volume * audio.masterGain / CarMasterVolumeRepository.DEFAULT)
            .coerceIn(CarMasterVolumeRepository.MIN, CarMasterVolumeRepository.MAX),
    )

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val VALIDATION_RAMP_MS = 500L
        const val VALIDATION_UPDATE_MS = 50L
        val VALIDATION_STAGES = listOf(
            ValidationStage(throttle = 0.25, durationMs = 2_500L),
            ValidationStage(throttle = 0.55, durationMs = 3_000L),
            ValidationStage(throttle = 1.00, durationMs = 9_000L),
            ValidationStage(throttle = 0.00, durationMs = 5_000L),
        )
    }

    private data class ValidationStage(val throttle: Double, val durationMs: Long)
}

private fun resolveInitialCatalogEntry(
    snapshot: CarCatalogSnapshot,
    repository: SelectedOfficialCarRepository,
): CarCatalogEntry {
    val selectedId = repository.load(snapshot)
    return requireNotNull(snapshot.find(selectedId)) { "Selected official car is absent from the immutable catalog" }
}

private fun profileFor(entry: CarCatalogEntry, family: InstalledSoundFamily?): EngineSampleProfile {
    if (family != null) {
        return family.manifest.engineSampleProfileFor(entry.id, entry.previewFile?.absolutePath)
    }

    val engine = entry.engine
    val gearbox = entry.gearbox
    val idleRpm = engine?.idleRpm ?: 900.0
    val redlineRpm = engine?.redlineRpm ?: 7_500.0
    val limiterRpm = engine?.limiterRpm ?: redlineRpm
    val maximumRpm = engine?.tachometerMaximumRpm
        ?.coerceAtLeast(limiterRpm)
        ?: (limiterRpm + 500.0).coerceAtLeast(8_000.0)
    val ratios = gearbox?.forwardRatios?.takeIf { it.isNotEmpty() }
        ?: listOf(3.20, 2.10, 1.52, 1.18, 0.96, 0.80)
    val turboControllerBank = engine?.toTurboControllerBank()
    val authoredMetadata = if (engine != null && gearbox != null) {
        authoredCarMetadata(entry.id, engine, gearbox, turboControllerBank)
    } else {
        AuthoredCarMetadata.EMPTY
    }
    return EngineSampleProfile(
        id = entry.id,
        displayName = entry.displayName,
        assetDirectory = "",
        previewAssetName = entry.previewFile?.absolutePath.orEmpty(),
        outputSampleRate = 48_000,
        minimumRpm = 0.0,
        maximumRpm = maximumRpm,
        idleRpm = idleRpm,
        redlineRpm = redlineRpm,
        limiterRpm = limiterRpm,
        upshiftRpm = gearbox?.upshiftRpm ?: redlineRpm,
        gearRatios = ratios,
        upshiftDurationSeconds = (gearbox?.upshiftTimeMs ?: 150.0) / 1_000.0,
        downshiftDurationSeconds = (gearbox?.downshiftTimeMs ?: 180.0) / 1_000.0,
        layers = emptyList(),
        effects = emptyList(),
        limiterHz = engine?.limiterHz ?: 20.0,
        turboControllerBank = turboControllerBank,
        authoredCarMetadata = authoredMetadata,
    )
}

private fun soundEffectOptions(
    profile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile,
    mask: Long,
): List<SoundEffectOption> = profile.effectControls.map { control ->
    SoundEffectOption(
        id = control.id,
        displayName = control.displayName,
        description = control.description,
        enabled = mask and control.bit != 0L,
    )
}

internal fun buildLayerMixTracks(
    profile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile,
    controls: Map<String, LayerMixControl>,
    outputLevels: Map<String, Double>,
): List<LayerMixTrackState> {
    val layerById = profile.layers.associateBy { it.id }
    val effectById = profile.effects.associateBy { it.id }
    return profile.mixerTrackOrder().mapNotNull { (trackId, sortGroup) ->
        val control = controls[trackId] ?: LayerMixControl.DEFAULT
        val layer = layerById[trackId]
        val effect = effectById[trackId]
        when {
            layer != null -> LayerMixTrackState(
                id = trackId,
                displayName = layer.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels[trackId] ?: 0.0,
                isEffect = false,
                showVolumeSlider = true,
            )
            effect != null -> LayerMixTrackState(
                id = trackId,
                displayName = effect.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels[trackId] ?: 0.0,
                isEffect = true,
                showVolumeSlider = true,
            )
            else -> null
        }
    }
}

private fun TuningConfig.toEngineProfile(sampleProfile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile): EngineProfile {
    val engine = engine.sanitized()
    return EngineProfile(
        name = sampleProfile.displayName,
        idleRpm = engine.idleRpm,
        redlineRpm = engine.redlineRpm,
        limiterRpm = engine.limiterRpm,
        upshiftRpm = engine.upshiftRpm,
        maxTorqueNm = engine.maxTorqueNm,
        peakPowerKw = engine.peakPowerKw,
        motorMaxRpm = engine.motorMaxRpm,
        motorReductionRatio = engine.motorReductionRatio,
        drivetrainEfficiency = engine.drivetrainEfficiency,
        frontPeakWheelTorqueNm = engine.frontPeakWheelTorqueNm,
        rearPeakWheelTorqueNm = engine.rearPeakWheelTorqueNm,
        tractionLimitMps2 = engine.tractionLimitMps2,
        vehicleMassKg = engine.vehicleMassKg,
        rotationalMassFactor = engine.rotationalMassFactor,
        wheelRadiusMeters = engine.wheelRadiusMeters,
        dragAreaM2 = engine.dragAreaM2,
        rollingResistanceCoefficient = engine.rollingResistanceCoefficient,
        topSpeedKmh = engine.topSpeedKmh,
        syntheticRpmResponseSeconds = engine.syntheticRpmResponseMs / 1_000.0,
        externalSpeedSmoothingSeconds = engine.externalSpeedSmoothingMs / 1_000.0,
        gearRatios = engine.gearRatios.toDoubleArray(),
        frontWheelTorqueCurve = engine.frontWheelTorqueCurve,
        rearWheelTorqueCurve = engine.rearWheelTorqueCurve,
        throttleCurve = engine.throttleCurve,
        throttleAttackSeconds = engine.throttleAttackMs / 1_000.0,
        throttleReleaseSeconds = engine.throttleReleaseMs / 1_000.0,
        upshiftDurationSeconds = engine.upshiftDurationMs / 1_000.0,
        downshiftDurationSeconds = engine.downshiftDurationMs / 1_000.0,
        shiftDwellSeconds = engine.shiftDwellMs / 1_000.0,
    )
}

internal data class ResolvedDriveInput(
    val throttle: Double,
    val brake: Double,
    val externalSpeedKmh: Double?,
    val label: String,
    val isSimulator: Boolean,
)

/** Pure input arbitration kept separate so unavailable vehicle data can be fail-safe tested. */
internal fun resolveDriveInput(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
    simulatorThrottle: Double,
    simulatorBrake: Double,
): ResolvedDriveInput {
    val vehicleAvailable = telemetry.vehiclePedalsAvailable()

    if (vehicleAvailable && mode != InputMode.SIMULATOR) {
        return ResolvedDriveInput(
            throttle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid },
            label = "BYD PEDALS",
            isSimulator = false,
        )
    }

    if (mode == InputMode.VEHICLE) {
        return ResolvedDriveInput(
            throttle = 0.0,
            brake = 0.0,
            externalSpeedKmh = null,
            label = "BYD UNAVAILABLE",
            isSimulator = false,
        )
    }

    return ResolvedDriveInput(
        throttle = simulatorThrottle.coerceIn(0.0, 1.0),
        brake = simulatorBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        label = "SIM PEDALS",
        isSimulator = true,
    )
}
