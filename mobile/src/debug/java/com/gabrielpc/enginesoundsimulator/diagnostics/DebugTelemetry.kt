package com.gabrielpc.enginesoundsimulator.diagnostics

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Debug-only, ADB-controlled capture for correlating the fixed drivetrain loop with FMOD.
 *
 * This deliberately owns bounded primitive rings instead of logging every three-millisecond
 * update. Capture is opt-in and remains completely dormant until the debug receiver receives a
 * START/RING command, so normal debug driving does not allocate, format Logcat lines, or write
 * storage from either realtime worker.
 */
internal object DebugTelemetry {
    const val ACTION = "com.gabrielpc.enginesoundsimulator.action.DEBUG_TELEMETRY"
    const val EXTRA_COMMAND = "command"

    private const val TAG = "DebugTelemetry"
    private const val FRAME_CAPACITY = 120_000
    private const val NATIVE_RECORD_CAPACITY = 50_000
    private const val MAX_EXPORTS = 12

    private val session = AtomicReference<CaptureSession?>(null)
    private val exportSerial = AtomicLong(0L)
    private val scenario = AtomicReference<DeterministicScenario?>(null)

    fun isCaptureActive(): Boolean = session.get() != null

    /** Native callbacks and source walks are only armed while a capture session exists. */
    fun nativeDiagnosticsEnabled(): Boolean = isCaptureActive()

    /**
     * Returns a safe synthetic input only while the explicit ADB scenario is active. The scenario
     * never touches user preferences: DriveController treats it as an ephemeral diagnostic layer.
     */
    fun scenarioOverride(timestampNanos: Long): DebugScenarioOverride? = scenario.get()?.overrideAt(timestampNanos)

    fun recordSimulation(
        timestampNanos: Long,
        simulationFrameId: Long,
        profileId: String,
        inputMode: String,
        perspectiveOrdinal: Int,
        rawSpeedKmh: Double,
        presentationSpeedKmh: Double,
        presentationAccelerationKmhPerSecond: Double,
        fmodDrivetrainSpeedKmh: Double,
        rpm: Double,
        gear: Int,
        clutch: Double,
        transmissionPosition: Int,
        throttle: Double,
        brake: Double,
        boost: Double,
        bov: Double,
        bovDecaySeconds: Double,
        isShifting: Boolean,
        shiftProgress: Double,
        shiftSerial: Long,
        shiftDirection: Int,
        limiterPulse: Boolean,
        backfireTriggered: Boolean,
        tractionLimitActive: Boolean,
        tractionLimitPulse: Boolean,
    ) {
        val active = session.get() ?: return
        active.updateContext(profileId, inputMode)
        active.simulation.append(
            timestampNanos = timestampNanos,
            simulationFrameId = simulationFrameId,
            perspectiveOrdinal = perspectiveOrdinal,
            rawSpeedKmh = rawSpeedKmh,
            presentationSpeedKmh = presentationSpeedKmh,
            presentationAccelerationKmhPerSecond = presentationAccelerationKmhPerSecond,
            fmodDrivetrainSpeedKmh = fmodDrivetrainSpeedKmh,
            rpm = rpm,
            gear = gear,
            clutch = clutch,
            transmissionPosition = transmissionPosition,
            throttle = throttle,
            brake = brake,
            boost = boost,
            bov = bov,
            bovDecaySeconds = bovDecaySeconds,
            isShifting = isShifting,
            shiftProgress = shiftProgress,
            shiftSerial = shiftSerial,
            shiftDirection = shiftDirection,
            limiterPulse = limiterPulse,
            backfireTriggered = backfireTriggered,
            tractionLimitActive = tractionLimitActive,
            tractionLimitPulse = tractionLimitPulse,
        )
    }

    fun recordAudioConsumption(
        timestampNanos: Long,
        controlTickId: Long,
        previousSimulationFrameId: Long,
        frame: EngineAudioFrame,
    ) {
        session.get()?.audio?.append(
            timestampNanos = timestampNanos,
            controlTickId = controlTickId,
            previousSimulationFrameId = previousSimulationFrameId,
            frame = frame,
        )
    }

    /**
     * Native records are drained only at the snapshot cadence. The String allocation happens
     * after FMOD has left its callback/update path and is therefore absent from the 3 ms path.
     */
    fun recordNativeRecords(timestampNanos: Long, rows: Array<String>) {
        val active = session.get() ?: return
        rows.forEach { active.native.append(timestampNanos, it) }
    }

    fun recordBankEventCatalog(timestampNanos: Long, rows: Array<String>) {
        val active = session.get() ?: return
        rows.forEach { active.bankEventCatalog.append(timestampNanos, it) }
    }

    /**
     * Associates the capture with the exact installed car bank rather than merely its profile.
     * The digest is calculated only when capture starts, never on the control loop's normal path.
     */
    fun recordBankContext(profileId: String, bankSha256: String) {
        session.get()?.updateBankContext(profileId, bankSha256)
    }

    fun handleCommand(context: Context, requestedCommand: String?, extras: Bundle?): String {
        val command = requestedCommand?.trim()?.uppercase() ?: "STATUS"
        val result = when (command) {
            "START", "RING" -> {
                session.set(CaptureSession(FRAME_CAPACITY, NATIVE_RECORD_CAPACITY))
                "capture started"
            }

            "CLEAR" -> {
                session.get()?.clear()
                "capture cleared"
            }

            "SCENARIO" -> {
                val profileId = extras?.getString(EXTRA_PROFILE_ID)?.trim().orEmpty()
                if (profileId.isEmpty()) {
                    "scenario requires --es profile <installed-profile-id>"
                } else {
                    if (session.get() == null) session.set(CaptureSession(FRAME_CAPACITY, NATIVE_RECORD_CAPACITY))
                    scenario.set(
                        DeterministicScenario(
                            id = exportSerial.incrementAndGet(),
                            profileId = profileId,
                            startedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                        ),
                    )
                    "scenario started for $profileId"
                }
            }

            "BACKFIRE" -> {
                val profileId = extras?.getString(EXTRA_PROFILE_ID)?.trim().orEmpty()
                if (profileId.isEmpty()) {
                    "backfire scenario requires --es profile <installed-profile-id>"
                } else {
                    if (session.get() == null) session.set(CaptureSession(FRAME_CAPACITY, NATIVE_RECORD_CAPACITY))
                    scenario.set(
                        DeterministicScenario(
                            id = exportSerial.incrementAndGet(),
                            profileId = profileId,
                            startedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                            kind = ScenarioKind.BACKFIRE,
                        ),
                    )
                    "backfire scenario started for $profileId"
                }
            }

            "CANCEL_SCENARIO" -> {
                scenario.set(null)
                "scenario cancelled"
            }

            "DUMP" -> export(context, session.get(), "snapshot")

            "STOP", "OFF" -> {
                scenario.set(null)
                val completed = session.getAndSet(null)
                export(context, completed, "stopped")
            }

            "STATUS" -> session.get()?.status() ?: "capture off"
            else -> "unknown command '$command'; use START, CLEAR, SCENARIO, BACKFIRE, CANCEL_SCENARIO, DUMP, STOP, or STATUS"
        }
        writeStatus(context, result)
        return result
    }

    private fun export(context: Context, captured: CaptureSession?, reason: String): String {
        if (captured == null) return "capture off; nothing to export"
        return runCatching {
            val root = File(
                context.getExternalFilesDir("fmod-diagnostics") ?: File(context.filesDir, "fmod-diagnostics"),
                "capture-${captured.startedElapsedNanos}-${exportSerial.incrementAndGet()}",
            )
            root.mkdirs()
            captured.exportTo(root, reason)
            trimOldExports(root.parentFile)
            "exported ${root.absolutePath}"
        }.getOrElse { error ->
            Log.e(TAG, "Unable to export debug telemetry", error)
            "export failed: ${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun trimOldExports(root: File?) {
        val directories = root?.listFiles()
            ?.filter(File::isDirectory)
            ?.sortedByDescending(File::lastModified)
            ?: return
        directories.drop(MAX_EXPORTS).forEach(::deleteRecursively)
    }

    private fun deleteRecursively(target: File) {
        target.listFiles()?.forEach(::deleteRecursively)
        target.delete()
    }

    private fun writeStatus(context: Context, value: String) {
        val directory = context.getExternalFilesDir("fmod-diagnostics") ?: File(context.filesDir, "fmod-diagnostics")
        directory.mkdirs()
        File(directory, "status.txt").writeText("${SystemClock.elapsedRealtimeNanos()} $value\n")
    }

    private class CaptureSession(frameCapacity: Int, nativeRecordCapacity: Int) {
        val startedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val simulation = SimulationRing(frameCapacity)
        val audio = AudioRing(frameCapacity)
        val native = TextRing(nativeRecordCapacity)
        val bankEventCatalog = TextRing(2_048)

        @Volatile private var profileId = "unknown"
        @Volatile private var inputMode = "unknown"
        @Volatile private var bankSha256 = "unknown"

        fun updateContext(nextProfileId: String, nextInputMode: String) {
            profileId = nextProfileId
            inputMode = nextInputMode
        }

        fun updateBankContext(nextProfileId: String, nextBankSha256: String) {
            profileId = nextProfileId
            bankSha256 = nextBankSha256
        }

        fun clear() {
            simulation.clear()
            audio.clear()
            native.clear()
            bankEventCatalog.clear()
        }

        fun status(): String = "capture active: ${simulation.count()} simulation, ${audio.count()} audio, ${native.count()} native, ${bankEventCatalog.count()} catalog records"

        fun exportTo(directory: File, reason: String) {
            File(directory, "metadata.json").bufferedWriter().use { writer ->
                writer.appendLine("{")
                writer.appendLine("  \"format\": \"byd-fmod-debug-trace-v1\",")
                writer.appendLine("  \"reason\": \"${json(reason)}\",")
                writer.appendLine("  \"startedElapsedNanos\": $startedElapsedNanos,")
                writer.appendLine("  \"profileId\": \"${json(profileId)}\",")
                writer.appendLine("  \"bankSha256\": \"${json(bankSha256)}\",")
                writer.appendLine("  \"inputMode\": \"${json(inputMode)}\",")
                writer.appendLine("  \"simulationSchema\": \"byd-fmod-simulation-frame-v2\",")
                writer.appendLine("  \"audioSchema\": \"byd-fmod-audio-consumption-v2\",")
                writer.appendLine("  \"nativeSchema\": \"byd-fmod-native-lifecycle-v1\",")
                writer.appendLine("  \"bankEventCatalogSchema\": \"byd-fmod-bank-event-catalog-v1\",")
                writer.appendLine("  \"simulationRecords\": ${simulation.count()},")
                writer.appendLine("  \"audioRecords\": ${audio.count()},")
                writer.appendLine("  \"nativeRecords\": ${native.count()},")
                writer.appendLine("  \"bankEventCatalogRecords\": ${bankEventCatalog.count()}")
                writer.appendLine("}")
            }
            simulation.export(File(directory, "simulation.csv"))
            audio.export(File(directory, "audio.csv"))
            native.exportDelimited(
                file = File(directory, "native.csv"),
                header = NATIVE_RECORD_HEADER,
                fieldCount = NATIVE_RECORD_FIELD_COUNT,
            )
            bankEventCatalog.exportDelimited(
                file = File(directory, "bank_event_catalog.csv"),
                header = BANK_EVENT_CATALOG_HEADER,
                fieldCount = BANK_EVENT_CATALOG_FIELD_COUNT,
            )
        }
    }

    private class SimulationRing(private val capacity: Int) {
        private val sequence = LongArray(capacity) { -1L }
        private val timestampNanos = LongArray(capacity)
        private val frameId = LongArray(capacity)
        private val perspectiveOrdinal = IntArray(capacity)
        private val rawSpeedKmh = FloatArray(capacity)
        private val presentationSpeedKmh = FloatArray(capacity)
        private val presentationAccelerationKmhPerSecond = FloatArray(capacity)
        private val fmodDrivetrainSpeedKmh = FloatArray(capacity)
        private val rpm = FloatArray(capacity)
        private val gear = IntArray(capacity)
        private val clutch = FloatArray(capacity)
        private val transmissionPosition = IntArray(capacity)
        private val throttle = FloatArray(capacity)
        private val brake = FloatArray(capacity)
        private val boost = FloatArray(capacity)
        private val bov = FloatArray(capacity)
        private val bovDecaySeconds = FloatArray(capacity)
        private val shiftProgress = FloatArray(capacity)
        private val shiftSerial = LongArray(capacity)
        private val shiftDirection = IntArray(capacity)
        private val flags = IntArray(capacity)

        @Volatile private var publishedCount = 0L

        fun append(
            timestampNanos: Long,
            simulationFrameId: Long,
            perspectiveOrdinal: Int,
            rawSpeedKmh: Double,
            presentationSpeedKmh: Double,
            presentationAccelerationKmhPerSecond: Double,
            fmodDrivetrainSpeedKmh: Double,
            rpm: Double,
            gear: Int,
            clutch: Double,
            transmissionPosition: Int,
            throttle: Double,
            brake: Double,
            boost: Double,
            bov: Double,
            bovDecaySeconds: Double,
            isShifting: Boolean,
            shiftProgress: Double,
            shiftSerial: Long,
            shiftDirection: Int,
            limiterPulse: Boolean,
            backfireTriggered: Boolean,
            tractionLimitActive: Boolean,
            tractionLimitPulse: Boolean,
        ) {
            val next = publishedCount
            val index = (next % capacity).toInt()
            this.timestampNanos[index] = timestampNanos
            frameId[index] = simulationFrameId
            this.perspectiveOrdinal[index] = perspectiveOrdinal
            this.rawSpeedKmh[index] = rawSpeedKmh.toFloat()
            this.presentationSpeedKmh[index] = presentationSpeedKmh.toFloat()
            this.presentationAccelerationKmhPerSecond[index] = presentationAccelerationKmhPerSecond.toFloat()
            this.fmodDrivetrainSpeedKmh[index] = fmodDrivetrainSpeedKmh.toFloat()
            this.rpm[index] = rpm.toFloat()
            this.gear[index] = gear
            this.clutch[index] = clutch.toFloat()
            this.transmissionPosition[index] = transmissionPosition
            this.throttle[index] = throttle.toFloat()
            this.brake[index] = brake.toFloat()
            this.boost[index] = boost.toFloat()
            this.bov[index] = bov.toFloat()
            this.bovDecaySeconds[index] = bovDecaySeconds.toFloat()
            this.shiftProgress[index] = shiftProgress.toFloat()
            this.shiftSerial[index] = shiftSerial
            this.shiftDirection[index] = shiftDirection
            flags[index] = bitFlags(
                isShifting = isShifting,
                limiterPulse = limiterPulse,
                backfireTriggered = backfireTriggered,
                tractionLimitActive = tractionLimitActive,
                tractionLimitPulse = tractionLimitPulse,
            )
            sequence[index] = next
            publishedCount = next + 1
        }

        fun clear() {
            publishedCount = 0L
            sequence.fill(-1L)
        }

        fun count(): Long = minOf(publishedCount, capacity.toLong())

        fun export(file: File) {
            file.bufferedWriter().use { writer ->
                writer.appendLine("sequence,timestampNanos,simulationFrameId,perspectiveOrdinal,rawSpeedKmh,presentationSpeedKmh,presentationAccelerationKmhPerSecond,fmodDrivetrainSpeedKmh,rpm,gear,clutch,transmissionPosition,throttle,brake,boost,bov,bovDecaySeconds,isShifting,shiftProgress,shiftSerial,shiftDirection,limiterPulse,backfireTriggered,tractionLimitActive,tractionLimitPulse")
                val end = publishedCount
                val start = max(0L, end - capacity)
                for (recordSequence in start until end) {
                    val index = (recordSequence % capacity).toInt()
                    if (sequence[index] != recordSequence) continue
                    val frameFlags = flags[index]
                    writer.append(recordSequence.toString()).append(',')
                    writer.append(timestampNanos[index].toString()).append(',')
                    writer.append(frameId[index].toString()).append(',')
                    writer.append(perspectiveOrdinal[index].toString()).append(',')
                    writer.append(rawSpeedKmh[index].toString()).append(',')
                    writer.append(presentationSpeedKmh[index].toString()).append(',')
                    writer.append(presentationAccelerationKmhPerSecond[index].toString()).append(',')
                    writer.append(fmodDrivetrainSpeedKmh[index].toString()).append(',')
                    writer.append(rpm[index].toString()).append(',')
                    writer.append(gear[index].toString()).append(',')
                    writer.append(clutch[index].toString()).append(',')
                    writer.append(transmissionPosition[index].toString()).append(',')
                    writer.append(throttle[index].toString()).append(',')
                    writer.append(brake[index].toString()).append(',')
                    writer.append(boost[index].toString()).append(',')
                    writer.append(bov[index].toString()).append(',')
                    writer.append(bovDecaySeconds[index].toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_SHIFTING).toString()).append(',')
                    writer.append(shiftProgress[index].toString()).append(',')
                    writer.append(shiftSerial[index].toString()).append(',')
                    writer.append(shiftDirection[index].toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_LIMITER).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_BACKFIRE).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_TRACTION_ACTIVE).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_TRACTION_PULSE).toString()).appendLine()
                }
            }
        }
    }

    private class AudioRing(private val capacity: Int) {
        private val sequence = LongArray(capacity) { -1L }
        private val timestampNanos = LongArray(capacity)
        private val controlTickId = LongArray(capacity)
        private val simulationFrameId = LongArray(capacity)
        private val perspectiveOrdinal = IntArray(capacity)
        private val skippedSimulationFrames = LongArray(capacity)
        private val rpm = FloatArray(capacity)
        private val rawSpeedKmh = FloatArray(capacity)
        private val presentationSpeedKmh = FloatArray(capacity)
        private val presentationAccelerationKmhPerSecond = FloatArray(capacity)
        private val fmodDrivetrainSpeedRadiansPerSecond = FloatArray(capacity)
        private val throttle = FloatArray(capacity)
        private val brake = FloatArray(capacity)
        private val clutch = FloatArray(capacity)
        private val boost = FloatArray(capacity)
        private val bov = FloatArray(capacity)
        private val bovDecaySeconds = FloatArray(capacity)
        private val gear = IntArray(capacity)
        private val transmissionPosition = IntArray(capacity)
        private val shiftProgress = FloatArray(capacity)
        private val shiftSerial = LongArray(capacity)
        private val shiftDirection = IntArray(capacity)
        private val flags = IntArray(capacity)

        @Volatile private var publishedCount = 0L

        fun append(
            timestampNanos: Long,
            controlTickId: Long,
            previousSimulationFrameId: Long,
            frame: EngineAudioFrame,
        ) {
            val next = publishedCount
            val index = (next % capacity).toInt()
            val currentSimulationFrameId = frame.simulationFrameId
            this.timestampNanos[index] = timestampNanos
            this.controlTickId[index] = controlTickId
            simulationFrameId[index] = currentSimulationFrameId
            perspectiveOrdinal[index] = frame.perspective.ordinal
            skippedSimulationFrames[index] = when {
                currentSimulationFrameId <= 0L || previousSimulationFrameId <= 0L -> 0L
                currentSimulationFrameId > previousSimulationFrameId -> currentSimulationFrameId - previousSimulationFrameId - 1L
                else -> 0L
            }
            rpm[index] = frame.rpm.toFloat()
            rawSpeedKmh[index] = frame.rawSpeedKmh.toFloat()
            presentationSpeedKmh[index] = frame.presentationSpeedKmh.toFloat()
            presentationAccelerationKmhPerSecond[index] = frame.presentationAccelerationKmhPerSecond.toFloat()
            fmodDrivetrainSpeedRadiansPerSecond[index] = frame.drivetrainSpeedRadiansPerSecond.toFloat()
            throttle[index] = frame.throttle.toFloat()
            brake[index] = frame.brake.toFloat()
            clutch[index] = frame.clutch.toFloat()
            boost[index] = frame.boost.toFloat()
            bov[index] = frame.bov.toFloat()
            bovDecaySeconds[index] = frame.bovDecaySeconds.toFloat()
            gear[index] = frame.gear
            transmissionPosition[index] = frame.transmissionPosition
            shiftProgress[index] = frame.shiftProgress.toFloat()
            shiftSerial[index] = frame.shiftSerial
            shiftDirection[index] = frame.shiftDirection
            flags[index] = bitFlags(
                isShifting = frame.isShifting,
                limiterPulse = frame.limiterPulse,
                backfireTriggered = frame.backfireTriggered,
                tractionLimitActive = frame.tractionLimitActive,
                tractionLimitPulse = frame.tractionLimitPulse,
            ) or if (currentSimulationFrameId > 0L && currentSimulationFrameId == previousSimulationFrameId) FLAG_REPEATED_SIMULATION_FRAME else 0
            sequence[index] = next
            publishedCount = next + 1
        }

        fun clear() {
            publishedCount = 0L
            sequence.fill(-1L)
        }

        fun count(): Long = minOf(publishedCount, capacity.toLong())

        fun export(file: File) {
            file.bufferedWriter().use { writer ->
                writer.appendLine("sequence,timestampNanos,controlTickId,simulationFrameId,perspectiveOrdinal,repeatedSimulationFrame,skippedSimulationFrames,rpm,rawSpeedKmh,presentationSpeedKmh,presentationAccelerationKmhPerSecond,fmodDrivetrainSpeedRadiansPerSecond,throttle,brake,clutch,boost,bov,bovDecaySeconds,gear,transmissionPosition,isShifting,shiftProgress,shiftSerial,shiftDirection,limiterPulse,backfireTriggered,tractionLimitActive,tractionLimitPulse")
                val end = publishedCount
                val start = max(0L, end - capacity)
                for (recordSequence in start until end) {
                    val index = (recordSequence % capacity).toInt()
                    if (sequence[index] != recordSequence) continue
                    val frameFlags = flags[index]
                    writer.append(recordSequence.toString()).append(',')
                    writer.append(timestampNanos[index].toString()).append(',')
                    writer.append(controlTickId[index].toString()).append(',')
                    writer.append(simulationFrameId[index].toString()).append(',')
                    writer.append(perspectiveOrdinal[index].toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_REPEATED_SIMULATION_FRAME).toString()).append(',')
                    writer.append(skippedSimulationFrames[index].toString()).append(',')
                    writer.append(rpm[index].toString()).append(',')
                    writer.append(rawSpeedKmh[index].toString()).append(',')
                    writer.append(presentationSpeedKmh[index].toString()).append(',')
                    writer.append(presentationAccelerationKmhPerSecond[index].toString()).append(',')
                    writer.append(fmodDrivetrainSpeedRadiansPerSecond[index].toString()).append(',')
                    writer.append(throttle[index].toString()).append(',')
                    writer.append(brake[index].toString()).append(',')
                    writer.append(clutch[index].toString()).append(',')
                    writer.append(boost[index].toString()).append(',')
                    writer.append(bov[index].toString()).append(',')
                    writer.append(bovDecaySeconds[index].toString()).append(',')
                    writer.append(gear[index].toString()).append(',')
                    writer.append(transmissionPosition[index].toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_SHIFTING).toString()).append(',')
                    writer.append(shiftProgress[index].toString()).append(',')
                    writer.append(shiftSerial[index].toString()).append(',')
                    writer.append(shiftDirection[index].toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_LIMITER).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_BACKFIRE).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_TRACTION_ACTIVE).toString()).append(',')
                    writer.append(flag(frameFlags, FLAG_TRACTION_PULSE).toString()).appendLine()
                }
            }
        }
    }

    private class TextRing(private val capacity: Int) {
        private val sequence = LongArray(capacity) { -1L }
        private val timestampNanos = LongArray(capacity)
        private val rows = arrayOfNulls<String>(capacity)

        @Volatile private var publishedCount = 0L

        fun append(timestampNanos: Long, row: String) {
            val next = publishedCount
            val index = (next % capacity).toInt()
            this.timestampNanos[index] = timestampNanos
            rows[index] = row
            sequence[index] = next
            publishedCount = next + 1
        }

        fun clear() {
            publishedCount = 0L
            sequence.fill(-1L)
            rows.fill(null)
        }

        fun count(): Long = minOf(publishedCount, capacity.toLong())

        fun exportDelimited(file: File, header: String, fieldCount: Int) {
            file.bufferedWriter().use { writer ->
                writer.appendLine(header)
                val end = publishedCount
                val start = max(0L, end - capacity)
                for (recordSequence in start until end) {
                    val index = (recordSequence % capacity).toInt()
                    if (sequence[index] != recordSequence) continue
                    writer.append(recordSequence.toString()).append(',')
                    writer.append(timestampNanos[index].toString()).append(',')
                    val fields = rows[index].orEmpty().split(FIELD_SEPARATOR)
                    for (fieldIndex in 0 until fieldCount) {
                        if (fieldIndex > 0) writer.append(',')
                        csv(writer, fields.getOrElse(fieldIndex) { "" })
                    }
                    writer.appendLine()
                }
            }
        }
    }

    /**
     * Fixed phase order lets an ADB loop select every installed original profile without UI taps.
     * The phases intentionally cover the branch conditions that can start/stop authored events;
     * they are instrumentation inputs, not a replacement for normal vehicle controls.
     */
    private data class DeterministicScenario(
        val id: Long,
        val profileId: String,
        val startedElapsedNanos: Long,
        val kind: ScenarioKind = ScenarioKind.GENERAL,
    ) {
        fun overrideAt(nowNanos: Long): DebugScenarioOverride {
            val seconds = ((nowNanos - startedElapsedNanos).coerceAtLeast(0L) / 1_000_000_000.0)
            if (kind == ScenarioKind.BACKFIRE) return backfireOverride(seconds)
            val phase = when {
                seconds < 2.0 -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false)
                seconds < 6.0 -> Phase(throttle = 0.35, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false)
                seconds < 18.0 -> Phase(throttle = 1.0, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false)
                seconds < 21.0 -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false)
                seconds < 27.0 -> Phase(throttle = 0.0, brake = 0.85, transmissionPositionOrdinal = 2, exterior = false)
                seconds < 30.0 -> Phase(throttle = 0.80, brake = 0.0, transmissionPositionOrdinal = 1, exterior = false)
                seconds < 33.0 -> Phase(throttle = 0.65, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
                seconds < 36.0 -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
                seconds < 42.0 -> Phase(throttle = 0.75, brake = 0.0, transmissionPositionOrdinal = 2, exterior = true)
                seconds < 46.0 -> Phase(throttle = 0.0, brake = 0.65, transmissionPositionOrdinal = 2, exterior = true)
                seconds < 49.0 -> Phase(throttle = 0.45, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false, manual = true)
                seconds < 52.0 -> Phase(throttle = 0.0, brake = 0.65, transmissionPositionOrdinal = 2, exterior = false, manual = true)
                else -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 2, exterior = false)
            }
            val actionWindow = when {
                seconds in 48.0..51.0 -> 1
                seconds in 51.0..52.0 -> -1
                else -> 0
            }
            return DebugScenarioOverride(
                scenarioId = id,
                profileId = profileId,
                perspectiveOrdinal = if (phase.exterior) 1 else 0,
                inputModeOrdinal = 1,
                // TransmissionPosition order is P, N, D. The P/N phases deliberately exercise
                // both free-rev branches without mutating a user's gear-selector preference.
                transmissionPositionOrdinal = phase.transmissionPositionOrdinal,
                throttle = phase.throttle,
                brake = phase.brake,
                manualModeEnabled = phase.manual,
                manualShiftSerial = if (actionWindow != 0) (id * 10L) + if (actionWindow > 0) 1L else 2L else 0L,
                manualShiftDirection = actionWindow,
            )
        }

        private fun backfireOverride(seconds: Double): DebugScenarioOverride {
            // Repeated neutral cycles deliberately arm the bank's backfire conditions at full
            // load, then leave the throttle closed long enough to measure trigger/rearm timing.
            // This exists only in the debug ADB harness and never changes normal driving.
            val cycleSeconds = seconds % 7.5
            val phase = when {
                cycleSeconds < 3.0 -> Phase(throttle = 1.0, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
                cycleSeconds < 5.0 -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
                cycleSeconds < 6.0 -> Phase(throttle = 0.10, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
                else -> Phase(throttle = 0.0, brake = 0.0, transmissionPositionOrdinal = 0, exterior = false)
            }
            return DebugScenarioOverride(
                scenarioId = id,
                profileId = profileId,
                perspectiveOrdinal = 0,
                inputModeOrdinal = 1,
                transmissionPositionOrdinal = phase.transmissionPositionOrdinal,
                throttle = phase.throttle,
                brake = phase.brake,
                manualModeEnabled = false,
            )
        }

        private data class Phase(
            val throttle: Double,
            val brake: Double,
            val transmissionPositionOrdinal: Int,
            val exterior: Boolean,
            val manual: Boolean = false,
        )
    }

    private enum class ScenarioKind { GENERAL, BACKFIRE }

    private fun bitFlags(
        isShifting: Boolean,
        limiterPulse: Boolean,
        backfireTriggered: Boolean,
        tractionLimitActive: Boolean,
        tractionLimitPulse: Boolean,
    ): Int =
        (if (isShifting) FLAG_SHIFTING else 0) or
            (if (limiterPulse) FLAG_LIMITER else 0) or
            (if (backfireTriggered) FLAG_BACKFIRE else 0) or
            (if (tractionLimitActive) FLAG_TRACTION_ACTIVE else 0) or
            (if (tractionLimitPulse) FLAG_TRACTION_PULSE else 0)

    private fun flag(flags: Int, mask: Int): Int = if (flags and mask != 0) 1 else 0

    private fun csv(writer: BufferedWriter, value: String) {
        writer.append('"')
        value.forEach { character ->
            if (character == '"') writer.append('"')
            writer.append(character)
        }
        writer.append('"')
    }

    private fun json(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private const val FLAG_SHIFTING = 1 shl 0
    private const val FLAG_LIMITER = 1 shl 1
    private const val FLAG_BACKFIRE = 1 shl 2
    private const val FLAG_TRACTION_ACTIVE = 1 shl 3
    private const val FLAG_TRACTION_PULSE = 1 shl 4
    private const val FLAG_REPEATED_SIMULATION_FRAME = 1 shl 5
    private const val EXTRA_PROFILE_ID = "profile"
    private const val FIELD_SEPARATOR = '\u001f'
    private const val NATIVE_RECORD_FIELD_COUNT = 29
    private const val BANK_EVENT_CATALOG_FIELD_COUNT = 5
    private const val NATIVE_RECORD_HEADER = "captureSequence,drainTimestampNanos,kind,nativeTimestampSeconds,simulationFrameId,voiceSerial,sampleDurationSeconds,fmodResult,gear,voiceCount,virtualVoiceCount,callbackVoiceCount,audibility,routeGain,rpm,drivetrainSpeed,throttle,boostNormalized,boostAbsolute,bov,bovDecay,shiftProgress,shiftSerial,stateFlags,isShifting,sampleLengthMs,sampleChannels,sampleRateHz,eventName,eventPath,rawSoundName"
    private const val BANK_EVENT_CATALOG_HEADER = "captureSequence,drainTimestampNanos,kind,eventPath,eventGuid,eventSuffix,appClassification"
}
