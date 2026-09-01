package com.gabrielpc.enginesoundsimulator.telemetry

import android.annotation.SuppressLint
import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
) {
    val isValid: Boolean
        get() = value != null && issue == null
}

data class TelemetrySnapshot(
    val readerState: ReaderState = ReaderState.IDLE,
    val accelerator: SignalValue = SignalValue(),
    val brake: SignalValue = SignalValue(),
    val speed: SignalValue = SignalValue(),
    val gearboxAutoMode: SignalValue = SignalValue(),
    val gearboxCode: String? = null,
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

    fun snapshot(): TelemetrySnapshot = latestSnapshot

    fun start() {
        val runId: Long
        val worker: ScheduledExecutorService
        synchronized(lifecycleLock) {
            if (executor != null) return

            runId = generation.incrementAndGet()
            accessors = null
            gearboxAccessors = null
            latestSnapshot = TelemetrySnapshot(
                readerState = ReaderState.PROBING,
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
        )
    }

    @SuppressLint("PrivateApi")
    private fun initialize(runId: Long, worker: ScheduledExecutorService) {
        try {
            val deviceType = Class.forName(BYD_SPEED_CLASS, false, appContext.classLoader)

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

            initializeGearbox(deviceContext)

            if (!isCurrent(runId)) return
            accessors = Accessors(device, throttle, brake, speed)
            latestSnapshot = latestSnapshot.copy(
                readerState = ReaderState.ACTIVE,
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
            latestSnapshot = latestSnapshot.copy(
                readerState = ReaderState.UNAVAILABLE,
            )
        }
    }

    private fun pollOnce(runId: Long) {
        if (!isCurrent(runId)) return
        val currentAccessors = accessors ?: return
        val throttleRead = invokeNumber(currentAccessors.device, currentAccessors.throttle)
        val brakeRead = invokeNumber(currentAccessors.device, currentAccessors.brake)
        val speedRead = invokeNumber(currentAccessors.device, currentAccessors.speed)
        val gearboxReads = readGearboxSignals()

        val previous = latestSnapshot
        val throttleValidation = TelemetryValidation.pedal(throttleRead.raw, throttleRead.error)
        val brakeValidation = TelemetryValidation.pedal(brakeRead.raw, brakeRead.error)
        val speedValidation = TelemetryValidation.speed(speedRead.raw, speedRead.error)
        val gearboxValidation = TelemetryValidation.gearboxAutoMode(gearboxReads.autoModeRaw, gearboxReads.autoModeError)
        if (!isCurrent(runId)) return
        latestSnapshot = previous.copy(
            readerState = ReaderState.ACTIVE,
            accelerator = updateSignal(throttleRead.raw, throttleValidation),
            brake = updateSignal(brakeRead.raw, brakeValidation),
            speed = updateSignal(speedRead.raw, speedValidation),
            gearboxAutoMode = updateSignal(
                gearboxReads.autoModeRaw,
                gearboxValidation,
            ),
            gearboxCode = gearboxReads.code ?: previous.gearboxCode,
        )
    }

    @SuppressLint("PrivateApi")
    private fun initializeGearbox(deviceContext: Context) {
        try {
            val deviceType = Class.forName(BYD_GEARBOX_CLASS, false, appContext.classLoader)
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
        } catch (_: Throwable) {
            gearboxAccessors = null
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
        )
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
    raw: Double?,
    validation: Validation,
): SignalValue {
    return SignalValue(
        raw = raw,
        value = validation.value,
        issue = validation.issue,
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

private fun formatRaw(raw: Double): String = raw.toInt().toString()
