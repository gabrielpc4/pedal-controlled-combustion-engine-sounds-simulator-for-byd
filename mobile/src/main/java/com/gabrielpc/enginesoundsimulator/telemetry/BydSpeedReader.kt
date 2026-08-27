package com.gabrielpc.enginesoundsimulator.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val BYD_SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
private const val BYD_GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
internal const val BYD_SPEED_COMMON = "android.permission.BYDAUTO_SPEED_COMMON"
internal const val BYD_SPEED_GET = "android.permission.BYDAUTO_SPEED_GET"
internal const val BYD_GEARBOX_GET = "android.permission.BYDAUTO_GEARBOX_GET"

enum class ReaderState {
    IDLE,
    PROBING,
    ACTIVE,
    UNAVAILABLE,
    STOPPED,
}

data class SignalValue(
    val raw: Double? = null,
    val value: Double? = null,
    val issue: String? = null,
    val changedAtNanos: Long? = null,
) {
    val isValid: Boolean
        get() = value != null && issue == null
}

data class CadenceStats(
    val sampleCount: Long = 0,
    val rateHz: Double? = null,
    val lastIntervalMs: Double? = null,
    val meanIntervalMs: Double? = null,
    val p95IntervalMs: Double? = null,
    val maxIntervalMs: Double? = null,
)

data class TelemetrySnapshot(
    val readerState: ReaderState = ReaderState.IDLE,
    val deliveryMode: String = "NONE",
    val accelerator: SignalValue = SignalValue(),
    val brake: SignalValue = SignalValue(),
    val speed: SignalValue = SignalValue(),
    val gearboxAutoMode: SignalValue = SignalValue(),
    val gearboxCode: String? = null,
    val lastReadAtNanos: Long? = null,
    val lastReadDurationMs: Double? = null,
    val cadence: CadenceStats = CadenceStats(),
    val diagnostics: List<String> = emptyList(),
    val lastError: String? = null,
)

/**
 * Read-only access to BYD's vendor speed device.
 *
 * No BYD classes are bundled in this APK. The reader resolves the implementation supplied by the
 * car's boot class path, then invokes only the three documented getters. All reflection and polling
 * happen on one worker thread so a slow vehicle service can never block the UI or overlap calls.
 */
class BydSpeedReader(
    context: Context,
    private val pollIntervalMs: Long = 20L,
) {
    private val appContext = context.applicationContext
    private val lifecycleLock = Any()
    private val generation = AtomicLong(0)

    @Volatile
    private var latestSnapshot = TelemetrySnapshot()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    private var accessors: Accessors? = null
    private var gearboxAccessors: GearboxAccessors? = null
    private var gearboxConstants = BydGearboxConstants.fallback()
    private val cadenceTracker = CadenceTracker()

    fun snapshot(): TelemetrySnapshot = latestSnapshot

    fun start() {
        val runId: Long
        val worker: ScheduledExecutorService
        synchronized(lifecycleLock) {
            if (executor != null) return

            runId = generation.incrementAndGet()
            cadenceTracker.reset()
            accessors = null
            gearboxAccessors = null
            latestSnapshot = TelemetrySnapshot(
                readerState = ReaderState.PROBING,
                deliveryMode = "PROBE",
                diagnostics = baseDiagnostics(),
            )

            worker = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "byd-speed-reader").apply { isDaemon = true }
            }
            executor = worker
        }

        worker.execute { initialize(runId, worker) }
    }

    fun stop() {
        val worker = synchronized(lifecycleLock) {
            generation.incrementAndGet()
            val current = executor
            executor = null
            accessors = null
            gearboxAccessors = null
            current
        }
        worker?.shutdownNow()
        latestSnapshot = latestSnapshot.copy(
            readerState = ReaderState.STOPPED,
            deliveryMode = "NONE",
        )
    }

    fun restart() {
        stop()
        start()
    }

    @SuppressLint("PrivateApi")
    private fun initialize(runId: Long, worker: ScheduledExecutorService) {
        val diagnostics = baseDiagnostics().toMutableList()
        try {
            val deviceType = Class.forName(BYD_SPEED_CLASS, false, appContext.classLoader)
            diagnostics += "BYD speed class: present"
            diagnostics += "Class loader: ${deviceType.classLoader?.javaClass?.simpleName ?: "boot/platform"}"

            val getInstance = deviceType.methods.firstOrNull { method ->
                method.name == "getInstance" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(appContext.javaClass)
            } ?: throw NoSuchMethodException("$BYD_SPEED_CLASS.getInstance(Context)")

            // This must be the first Context used for the process-local BYD singleton. Supplying
            // the ordinary application Context first can leave the singleton permanently holding
            // a context that rejects its client-side signature permission check.
            val deviceContext = BydReadOnlyPermissionContext(appContext)
            val device = getInstance.invoke(null, deviceContext)
                ?: throw IllegalStateException("BYD speed getInstance returned null")

            val runtimeType = device.javaClass
            val throttle = findGetter(runtimeType, "getAccelerateDeepness")
            val brake = findGetter(runtimeType, "getBrakeDeepness")
            val speed = findGetter(runtimeType, "getCurrentSpeed")
            if (throttle == null && brake == null && speed == null) {
                throw NoSuchMethodException("No documented speed getters exist on ${runtimeType.name}")
            }

            diagnostics += "Device instance: created"
            diagnostics += "Runtime class: ${runtimeType.name}"
            diagnostics += "Getter accelerator: ${if (throttle == null) "missing" else "present"}"
            diagnostics += "Getter brake: ${if (brake == null) "missing" else "present"}"
            diagnostics += "Getter speed: ${if (speed == null) "missing" else "present"}"
            diagnostics += "Read-only BYD speed compatibility context: active"
            diagnostics += describeListenerApi(runtimeType)
            diagnostics += "Delivery: polling every ${pollIntervalMs} ms (listener SDK not packaged)"
            diagnostics += initializeGearbox(deviceContext, diagnostics)

            if (!isCurrent(runId)) return
            accessors = Accessors(device, throttle, brake, speed)
            latestSnapshot = latestSnapshot.copy(
                readerState = ReaderState.ACTIVE,
                deliveryMode = "POLL ${pollIntervalMs} ms",
                diagnostics = diagnostics,
                lastError = null,
            )

            try {
                worker.scheduleWithFixedDelay(
                    { pollOnce(runId) },
                    0L,
                    pollIntervalMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                // The Activity stopped while the one-time capability probe was finishing.
            }
        } catch (throwable: Throwable) {
            if (!isCurrent(runId)) return
            val error = describeThrowable(throwable)
            diagnostics += "Probe failure: $error"
            DebugEventLog.recordThrowable("byd_telemetry_probe_failed", throwable)
            latestSnapshot = latestSnapshot.copy(
                readerState = ReaderState.UNAVAILABLE,
                deliveryMode = "NONE",
                diagnostics = diagnostics,
                lastError = error,
            )
            DebugEventLog.warning("byd_reader_unavailable", error)
        }
    }

    private fun pollOnce(runId: Long) {
        if (!isCurrent(runId)) return
        val currentAccessors = accessors ?: return
        val startedAt = SystemClock.elapsedRealtimeNanos()

        val throttleRead = invokeNumber(currentAccessors.device, currentAccessors.throttle)
        val brakeRead = invokeNumber(currentAccessors.device, currentAccessors.brake)
        val speedRead = invokeNumber(currentAccessors.device, currentAccessors.speed)
        val gearboxReads = readGearboxSignals()

        val completedAt = SystemClock.elapsedRealtimeNanos()
        val previous = latestSnapshot
        val throttleValidation = TelemetryValidation.pedal(throttleRead.raw, throttleRead.error)
        val brakeValidation = TelemetryValidation.pedal(brakeRead.raw, brakeRead.error)
        val speedValidation = TelemetryValidation.speed(speedRead.raw, speedRead.error)
        val gearboxValidation = TelemetryValidation.gearboxAutoMode(gearboxReads.autoModeRaw, gearboxReads.autoModeError)
        val errors = listOfNotNull(
            throttleRead.error?.let { "accelerator: $it" },
            brakeRead.error?.let { "brake: $it" },
            speedRead.error?.let { "speed: $it" },
            gearboxReads.autoModeError?.let { "gearbox: $it" },
        )

        if (!isCurrent(runId)) return
        val currentError = errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        if (currentError != previous.lastError) {
            if (currentError != null) {
                DebugEventLog.warning("byd_telemetry_read_failed", currentError)
            }
        }

        latestSnapshot = previous.copy(
            readerState = ReaderState.ACTIVE,
            accelerator = updateSignal(previous.accelerator, throttleRead.raw, throttleValidation, completedAt),
            brake = updateSignal(previous.brake, brakeRead.raw, brakeValidation, completedAt),
            speed = updateSignal(previous.speed, speedRead.raw, speedValidation, completedAt),
            gearboxAutoMode = updateSignal(
                previous.gearboxAutoMode,
                gearboxReads.autoModeRaw,
                gearboxValidation,
                completedAt,
            ),
            gearboxCode = gearboxReads.code ?: previous.gearboxCode,
            lastReadAtNanos = completedAt,
            lastReadDurationMs = nanosToMillis(completedAt - startedAt),
            cadence = cadenceTracker.record(completedAt),
            lastError = currentError,
        )
    }

    private fun baseDiagnostics(): List<String> = buildList {
        add("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        add("Build display: ${Build.DISPLAY}")
        add(
            "AAOS feature: " +
                if (appContext.packageManager.hasSystemFeature("android.hardware.type.automotive")) {
                    "present"
                } else {
                    "absent"
                },
        )
        add(permissionDiagnostic(BYD_SPEED_COMMON))
        add(permissionDiagnostic(BYD_SPEED_GET))
        add(permissionDiagnostic(BYD_GEARBOX_GET))
    }

    @SuppressLint("PrivateApi")
    private fun initializeGearbox(deviceContext: Context, diagnostics: MutableList<String>): String {
        return try {
            val deviceType = Class.forName(BYD_GEARBOX_CLASS, false, appContext.classLoader)
            diagnostics += "BYD gearbox class: present"
            val getInstance = deviceType.methods.firstOrNull { method ->
                method.name == "getInstance" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(deviceContext.javaClass)
            } ?: throw NoSuchMethodException("$BYD_GEARBOX_CLASS.getInstance(Context)")

            val device = getInstance.invoke(null, deviceContext)
                ?: throw IllegalStateException("BYD gearbox getInstance returned null")
            val runtimeType = device.javaClass
            gearboxConstants = BydGearboxConstants.discover(runtimeType)
            val autoMode = findGetter(runtimeType, "getGearboxAutoModeType")
            val code = findGetter(runtimeType, "getGearboxCode")
            gearboxAccessors = GearboxAccessors(device, autoMode, code)
            diagnostics += "Gearbox auto mode getter: ${if (autoMode == null) "missing" else "present"}"
            diagnostics += "Gearbox code getter: ${if (code == null) "missing" else "present"}"
            diagnostics += "Gearbox constants: P=${gearboxConstants.park} R=${gearboxConstants.reverse} N=${gearboxConstants.neutral} D=${gearboxConstants.drive}"
            "Gearbox reader: active"
        } catch (throwable: Throwable) {
            gearboxAccessors = null
            val message = "Gearbox probe: ${describeThrowable(throwable)}"
            diagnostics += message
            message
        }
    }

    private fun readGearboxSignals(): GearboxReads {
        val current = gearboxAccessors ?: return GearboxReads()
        val autoModeRead = invokeNumber(current.device, current.autoMode)
        val codeRead = invokeString(current.device, current.code)
        return GearboxReads(
            autoModeRaw = autoModeRead.raw,
            autoModeError = autoModeRead.error,
            code = codeRead.value,
            codeError = codeRead.error,
        )
    }

    private fun permissionDiagnostic(permission: String): String {
        val shortName = permission.substringAfterLast('.')
        return try {
            val info = appContext.packageManager.getPermissionInfo(permission, 0)
            val granted = appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            "$shortName: defined, ${protectionLabel(info)}, ${if (granted) "granted" else "denied"}"
        } catch (_: PackageManager.NameNotFoundException) {
            "$shortName: not defined by this firmware"
        } catch (throwable: Throwable) {
            "$shortName: inspection failed (${describeThrowable(throwable)})"
        }
    }

    private fun protectionLabel(info: PermissionInfo): String {
        val base = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        val baseName = when (base) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            else -> "protection=$base"
        }
        val flags = mutableListOf<String>()
        if (info.protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) flags += "privileged"
        if (info.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) flags += "development"
        return if (flags.isEmpty()) baseName else "$baseName+${flags.joinToString("+")}"
    }

    private fun describeListenerApi(deviceType: Class<*>): String {
        val registrations = deviceType.methods
            .filter { it.name == "registerListener" }
            .map { method ->
                method.parameterTypes.joinToString(prefix = "(", postfix = ")") { type ->
                    val kind = when {
                        type.isInterface -> "interface"
                        Modifier.isAbstract(type.modifiers) -> "abstract"
                        else -> "class"
                    }
                    "${type.name} [$kind]"
                }
            }
            .distinct()
        return if (registrations.isEmpty()) {
            "Listener API: not exposed on speed device"
        } else {
            "Listener API: ${registrations.joinToString()}"
        }
    }

    private fun isCurrent(runId: Long): Boolean = generation.get() == runId

    private data class Accessors(
        val device: Any,
        val throttle: Method?,
        val brake: Method?,
        val speed: Method?,
    )

    private data class GearboxAccessors(
        val device: Any,
        val autoMode: Method?,
        val code: Method?,
    )
}

private data class NumberRead(val raw: Double?, val error: String?)

private data class StringRead(val value: String?, val error: String?)

private data class GearboxReads(
    val autoModeRaw: Double? = null,
    val autoModeError: String? = null,
    val code: String? = null,
    val codeError: String? = null,
)

private fun invokeNumber(receiver: Any, method: Method?): NumberRead {
    if (method == null) return NumberRead(null, "method not available")
    return try {
    val result = method.invoke(receiver)
    if (result is Number) {
        NumberRead(result.toDouble(), null)
    } else {
        NumberRead(null, "${method.name} returned ${result?.javaClass?.name ?: "null"}")
    }
} catch (throwable: Throwable) {
    NumberRead(null, describeThrowable(throwable))
}
}

private fun invokeString(receiver: Any, method: Method?): StringRead {
    if (method == null) return StringRead(null, "method not available")
    return try {
        val result = method.invoke(receiver)
        when (result) {
            null -> StringRead(null, "no value")
            is String -> StringRead(result, null)
            is Number -> StringRead(result.toInt().toString(), null)
            else -> StringRead(result.toString(), null)
        }
    } catch (throwable: Throwable) {
        StringRead(null, describeThrowable(throwable))
    }
}

private fun findGetter(type: Class<*>, name: String): Method? =
    type.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }

private fun updateSignal(
    previous: SignalValue,
    raw: Double?,
    validation: Validation,
    nowNanos: Long,
): SignalValue {
    val changed = raw != previous.raw || validation.issue != previous.issue
    return SignalValue(
        raw = raw,
        value = validation.value,
        issue = validation.issue,
        changedAtNanos = if (changed) nowNanos else previous.changedAtNanos,
    )
}

internal data class Validation(val value: Double?, val issue: String?)

internal object TelemetryValidation {
    fun pedal(raw: Double?, invocationError: String? = null): Validation {
        if (invocationError != null) return Validation(null, invocationError)
        if (raw == null) return Validation(null, "no value")
        if (!raw.isFinite()) return Validation(null, "non-finite value")
        if (raw < 0.0 || raw > 100.0) {
            return Validation(null, sentinelOrRange(raw, "expected 0..100%"))
        }
        return Validation(raw, null)
    }

    fun speed(raw: Double?, invocationError: String? = null): Validation {
        if (invocationError != null) return Validation(null, invocationError)
        if (raw == null) return Validation(null, "no value")
        if (!raw.isFinite()) return Validation(null, "non-finite value")
        if (raw < 0.0 || raw > 282.0) {
            return Validation(null, sentinelOrRange(raw, "expected 0..282 km/h"))
        }
        return Validation(raw, null)
    }

    fun gearboxAutoMode(raw: Double?, invocationError: String? = null): Validation {
        if (invocationError != null) return Validation(null, invocationError)
        if (raw == null) return Validation(null, "no value")
        if (!raw.isFinite()) return Validation(null, "non-finite value")
        if (raw < 0.0 || raw > 32.0) {
            return Validation(null, sentinelOrRange(raw, "expected gearbox auto mode"))
        }
        return Validation(raw, null)
    }

    private fun sentinelOrRange(raw: Double, rangeMessage: String): String {
        val integral = raw.toLong()
        val sentinel = when (integral) {
            -2_147_482_624L -> "SDK not available"
            -10_011L -> "feature unbound"
            -10_013L -> "statistic not computed"
            -10_006L -> "uninitialized"
            -10_005L -> "permission denied"
            -10_001L -> "framework booting"
            65_535L -> "no data"
            Int.MIN_VALUE.toLong() -> "device not registered"
            else -> null
        }
        return sentinel?.let { "$it (raw ${formatRaw(raw)})" }
            ?: "$rangeMessage (raw ${formatRaw(raw)})"
    }
}

internal class CadenceTracker(private val capacity: Int = 128) {
    private val intervals = LongArray(capacity)
    private var intervalCount = 0
    private var nextIndex = 0
    private var previousAtNanos: Long? = null
    private var sampleCount = 0L

    init {
        require(capacity > 0)
    }

    fun reset() {
        intervalCount = 0
        nextIndex = 0
        previousAtNanos = null
        sampleCount = 0L
    }

    fun record(nowNanos: Long): CadenceStats {
        sampleCount += 1
        previousAtNanos?.let { previous ->
            val interval = nowNanos - previous
            if (interval >= 0L) {
                intervals[nextIndex] = interval
                nextIndex = (nextIndex + 1) % capacity
                if (intervalCount < capacity) intervalCount += 1
            }
        }
        previousAtNanos = nowNanos

        if (intervalCount == 0) return CadenceStats(sampleCount = sampleCount)
        val values = LongArray(intervalCount) { intervals[it] }
        val sorted = values.sortedArray()
        val meanNanos = values.average()
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return CadenceStats(
            sampleCount = sampleCount,
            rateHz = if (meanNanos > 0.0) 1_000_000_000.0 / meanNanos else null,
            lastIntervalMs = nanosToMillis(values[(nextIndex - 1 + capacity) % capacity]),
            meanIntervalMs = nanosToMillis(meanNanos),
            p95IntervalMs = nanosToMillis(sorted[p95Index]),
            maxIntervalMs = nanosToMillis(sorted.last()),
        )
    }
}

internal fun describeThrowable(throwable: Throwable): String {
    var cause = throwable
    while (cause is InvocationTargetException && cause.targetException != null) {
        cause = cause.targetException
    }
    val message = cause.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(240)
        ?.takeIf { it.isNotBlank() }
    return if (message == null) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $message"
}

private fun formatRaw(raw: Double): String = raw.roundToInt().toString()

private fun nanosToMillis(value: Long): Double = value / 1_000_000.0

private fun nanosToMillis(value: Double): Double = value / 1_000_000.0
