package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal sealed interface FmodNativeCallResult {
    data object Success : FmodNativeCallResult
    data class Failure(val detail: String) : FmodNativeCallResult
}

internal data class FmodNativeOpenResult(
    val bridge: FmodNativeBridge? = null,
    val error: String? = null,
) {
    val succeeded: Boolean get() = bridge != null
}

data class FmodRenderedAudioEventResult(
    val kind: FmodEventKind,
    val eventPath: String,
    val instanceStarts: Int,
    val soundPlayedCallbacks: Int,
    val renderedFrames: Long,
    val peakDbfs: Double,
    val rmsDbfs: Double,
    val nonFiniteSamples: Long,
    val passed: Boolean,
    val detail: String,
    val soundNames: List<String>,
)

data class FmodRenderedAudioValidationResult(
    val profileId: String,
    val passed: Boolean,
    val eventResults: List<FmodRenderedAudioEventResult>,
    val excludedInstantiationCount: Int,
    val durationMilliseconds: Long,
    val outputMode: String,
    val error: String? = null,
)

/**
 * Owns one FMOD Studio system and the selected profile's audited core event graphs.
 *
 * The caller owns the 400 Hz control thread. A complete state is written into one direct
 * [ByteBuffer] and delivered with a single JNI call; the native side never retains the buffer.
 */
internal class FmodNativeBridge private constructor(
    @Volatile private var nativeHandle: Long,
    private val javaFmodClass: Class<*>,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun loadBanks(profileId: String): FmodNativeCallResult = callNative("load the FMOD banks") { handle ->
        FmodNativeBindings.loadBanks(handle, profileId)
    }

    @Synchronized
    fun loadBanks(): FmodNativeCallResult = loadBanks(FmodCarProfiles.default.id)

    /** Hot-path call. [controlBuffer] must follow [ControlBufferLayout]. */
    @Synchronized
    fun update(controlBuffer: ByteBuffer): FmodNativeCallResult {
        if (!controlBuffer.isDirect) {
            return FmodNativeCallResult.Failure("FMOD control state must use a direct ByteBuffer.")
        }
        if (controlBuffer.capacity() < ControlBufferLayout.BUFFER_SIZE_BYTES) {
            return FmodNativeCallResult.Failure(
                "FMOD control buffer is ${controlBuffer.capacity()} bytes; " +
                    "${ControlBufferLayout.BUFFER_SIZE_BYTES} bytes are required.",
            )
        }
        return callNative("update FMOD") { handle ->
            FmodNativeBindings.update(handle, controlBuffer)
        }
    }

    /** Serializes [state] allocation-free and performs exactly one JNI update call. */
    @Synchronized
    fun update(state: FmodControlState, controlBuffer: ByteBuffer): FmodNativeCallResult {
        ControlBufferLayout.write(controlBuffer, state)
        return update(controlBuffer)
    }

    @Synchronized
    fun suspendMixer(): FmodNativeCallResult = callNative("suspend the FMOD mixer") { handle ->
        FmodNativeBindings.suspendMixer(handle)
    }

    @Synchronized
    fun resumeMixer(): FmodNativeCallResult = callNative("resume the FMOD mixer") { handle ->
        FmodNativeBindings.resumeMixer(handle)
    }

    @Synchronized
    fun diagnostics(): String = if (closed || nativeHandle == 0L) {
        "FMOD bridge is closed."
    } else {
        FmodNativeBindings.diagnostics(nativeHandle)
    }

    /** Runs a second deterministic NOSOUND_NRT system; call this off the UI/control hot path. */
    @Synchronized
    fun validateRenderedAudio(): FmodRenderedAudioValidationResult {
        if (closed || nativeHandle == 0L) {
            return FmodRenderedAudioValidationResult(
                profileId = "",
                passed = false,
                eventResults = emptyList(),
                excludedInstantiationCount = 0,
                durationMilliseconds = 0,
                outputMode = "",
                error = "FMOD bridge is closed.",
            )
        }
        return parseRenderedAudioValidation(FmodNativeBindings.validateRenderedAudio(nativeHandle))
    }

    /** Intentionally unsynchronized so lifecycle stop can interrupt an in-flight native check. */
    fun cancelRenderedAudioValidation() {
        val handle = nativeHandle
        if (handle != 0L) FmodNativeBindings.cancelRenderedAudioValidation(handle)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val handle = nativeHandle
        nativeHandle = 0L
        try {
            if (handle != 0L) FmodNativeBindings.release(handle)
        } finally {
            runCatching { javaFmodClass.getMethod("close").invoke(null) }
            processOpen.set(false)
        }
    }

    private inline fun callNative(
        operation: String,
        block: (Long) -> Boolean,
    ): FmodNativeCallResult {
        if (closed || nativeHandle == 0L) {
            return FmodNativeCallResult.Failure("Cannot $operation because the FMOD bridge is closed.")
        }
        return if (block(nativeHandle)) {
            FmodNativeCallResult.Success
        } else {
            FmodNativeCallResult.Failure(FmodNativeBindings.lastError(nativeHandle))
        }
    }

    internal object ControlBufferLayout {
        const val SCHEMA_VERSION = 2
        const val BUFFER_SIZE_BYTES = 112

        const val SCHEMA_OFFSET = 0
        const val ENABLED_MASK_OFFSET = 4
        const val RPM_OFFSET = 8
        const val ENGINE_THROTTLE_OFFSET = 12
        const val BOOST_OFFSET = 16
        const val BOV_OFFSET = 20
        const val BOV_DECAY_OFFSET = 24
        const val LIMITER_DECAY_OFFSET = 28
        const val MASTER_GAIN_OFFSET = 32
        const val ENGINE_GAIN_OFFSET = 36
        const val TURBO_GAIN_OFFSET = 40
        const val LIMITER_GAIN_OFFSET = 44
        const val SHIFT_GAIN_OFFSET = 48
        const val BACKFIRE_GAIN_OFFSET = 52
        const val SHIFT_DIRECTION_OFFSET = 56
        const val RESERVED_OFFSET = 60
        const val SHIFT_SERIAL_OFFSET = 64
        const val LIMITER_SERIAL_OFFSET = 72
        const val BOV_SERIAL_OFFSET = 80
        const val BACKFIRE_SERIAL_OFFSET = 88
        const val DRIVETRAIN_SPEED_OFFSET = 96
        const val TRANSMISSION_THROTTLE_OFFSET = 100
        const val TRANSMISSION_GAIN_OFFSET = 104
        const val RESERVED_V2_OFFSET = 108

        const val AUDIO_ENABLED = 1 shl 0
        const val ENGINE_ENABLED = 1 shl 1
        const val TURBO_ENABLED = 1 shl 2
        const val LIMITER_ENABLED = 1 shl 3
        const val SHIFT_ENABLED = 1 shl 4
        const val BACKFIRE_ENABLED = 1 shl 5
        const val TRANSMISSION_ENABLED = 1 shl 6
        const val ALL_EVENTS_ENABLED =
            AUDIO_ENABLED or ENGINE_ENABLED or TURBO_ENABLED or LIMITER_ENABLED or
                SHIFT_ENABLED or BACKFIRE_ENABLED or TRANSMISSION_ENABLED

        fun allocate(): ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also(::reset)

        fun reset(buffer: ByteBuffer) {
            require(buffer.isDirect) { "FMOD control buffer must be direct." }
            require(buffer.capacity() >= BUFFER_SIZE_BYTES) {
                "FMOD control buffer must be at least $BUFFER_SIZE_BYTES bytes."
            }
            buffer.order(ByteOrder.nativeOrder())
            for (offset in 0 until BUFFER_SIZE_BYTES step Long.SIZE_BYTES) {
                buffer.putLong(offset, 0L)
            }
            buffer.putInt(SCHEMA_OFFSET, SCHEMA_VERSION)
        }

        fun write(buffer: ByteBuffer, state: FmodControlState) {
            require(buffer.isDirect) { "FMOD control buffer must be direct." }
            require(buffer.capacity() >= BUFFER_SIZE_BYTES) {
                "FMOD control buffer must be at least $BUFFER_SIZE_BYTES bytes."
            }
            buffer.order(ByteOrder.nativeOrder())
            buffer.putInt(SCHEMA_OFFSET, SCHEMA_VERSION)
            buffer.putInt(ENABLED_MASK_OFFSET, state.flags)
            buffer.putFloat(RPM_OFFSET, state.rpm)
            buffer.putFloat(ENGINE_THROTTLE_OFFSET, state.engineThrottle)
            buffer.putFloat(BOOST_OFFSET, state.boost)
            buffer.putFloat(BOV_OFFSET, state.bov)
            buffer.putFloat(BOV_DECAY_OFFSET, state.bovDecaySeconds)
            buffer.putFloat(LIMITER_DECAY_OFFSET, state.limiterDecaySeconds)
            buffer.putFloat(MASTER_GAIN_OFFSET, state.masterGain)
            buffer.putFloat(ENGINE_GAIN_OFFSET, state.engineGain)
            buffer.putFloat(TURBO_GAIN_OFFSET, state.turboGain)
            buffer.putFloat(LIMITER_GAIN_OFFSET, state.limiterGain)
            buffer.putFloat(SHIFT_GAIN_OFFSET, state.shiftGain)
            buffer.putFloat(BACKFIRE_GAIN_OFFSET, state.backfireGain)
            buffer.putInt(SHIFT_DIRECTION_OFFSET, state.shiftDirection)
            buffer.putInt(RESERVED_OFFSET, 0)
            buffer.putLong(SHIFT_SERIAL_OFFSET, state.shiftSerial)
            buffer.putLong(LIMITER_SERIAL_OFFSET, state.limiterSerial)
            buffer.putLong(BOV_SERIAL_OFFSET, state.bovSerial)
            buffer.putLong(BACKFIRE_SERIAL_OFFSET, state.backfireSerial)
            buffer.putFloat(DRIVETRAIN_SPEED_OFFSET, state.drivetrainSpeed)
            buffer.putFloat(TRANSMISSION_THROTTLE_OFFSET, state.transmissionThrottle)
            buffer.putFloat(TRANSMISSION_GAIN_OFFSET, state.transmissionGain)
            buffer.putFloat(RESERVED_V2_OFFSET, 0f)
        }
    }

    companion object {
        private val processOpen = AtomicBoolean(false)
        private val librariesLoaded = AtomicBoolean(false)
        private val libraryLoadLock = Any()

        fun open(context: Context): FmodNativeOpenResult {
            if (!processOpen.compareAndSet(false, true)) {
                return FmodNativeOpenResult(error = "Only one FMOD runtime may be open at a time.")
            }

            var javaFmodClass: Class<*>? = null
            try {
                ensureLibrariesLoaded()
                javaFmodClass = Class.forName("org.fmod.FMOD")
                javaFmodClass.getMethod("init", Context::class.java)
                    .invoke(null, context.applicationContext)

                val handle = FmodNativeBindings.create()
                if (handle == 0L) {
                    val detail = FmodNativeBindings.lastError(0L)
                    runCatching { javaFmodClass.getMethod("close").invoke(null) }
                    processOpen.set(false)
                    return FmodNativeOpenResult(error = detail)
                }
                return FmodNativeOpenResult(FmodNativeBridge(handle, javaFmodClass))
            } catch (throwable: Throwable) {
                runCatching { javaFmodClass?.getMethod("close")?.invoke(null) }
                processOpen.set(false)
                return FmodNativeOpenResult(error = formatOpenFailure(throwable))
            }
        }

        fun allocateControlBuffer(): ByteBuffer = ControlBufferLayout.allocate()

        private fun ensureLibrariesLoaded() {
            if (librariesLoaded.get()) return
            synchronized(libraryLoadLock) {
                if (librariesLoaded.get()) return
                System.loadLibrary("fmod")
                System.loadLibrary("fmodstudio")
                System.loadLibrary("byd_fmod_bridge")
                librariesLoaded.set(true)
            }
        }

        private fun formatOpenFailure(throwable: Throwable): String {
            val cause = if (throwable is InvocationTargetException) {
                throwable.targetException ?: throwable
            } else {
                throwable
            }
            return when (cause) {
                is ClassNotFoundException ->
                    "FMOD Android Java wrapper is missing. Check fmod.sdk.dir and rebuild the APK."
                is UnsatisfiedLinkError ->
                    "FMOD native libraries could not be loaded: ${cause.message ?: "unknown linker error"}"
                else -> cause.message ?: cause::class.java.simpleName
            }
        }

        private fun parseRenderedAudioValidation(raw: String): FmodRenderedAudioValidationResult =
            runCatching {
                val root = JSONObject(raw)
                val checks = root.optJSONArray("checks")
                val results = buildList {
                    if (checks != null) {
                        for (index in 0 until checks.length()) {
                            val check = checks.getJSONObject(index)
                            val names = check.optString("soundNames")
                                .split('|')
                                .filter(String::isNotBlank)
                            add(
                                FmodRenderedAudioEventResult(
                                    kind = FmodEventKind.valueOf(check.getString("kind")),
                                    eventPath = check.getString("eventPath"),
                                    instanceStarts = check.optInt("instanceStarts"),
                                    soundPlayedCallbacks = check.optInt("soundPlayedCallbacks"),
                                    renderedFrames = check.optLong("renderedFrames"),
                                    peakDbfs = check.optDouble("peakDbfs", Double.NEGATIVE_INFINITY),
                                    rmsDbfs = check.optDouble("rmsDbfs", Double.NEGATIVE_INFINITY),
                                    nonFiniteSamples = check.optLong("nonFiniteSamples"),
                                    passed = check.optBoolean("passed"),
                                    detail = check.optString("detail"),
                                    soundNames = names,
                                ),
                            )
                        }
                    }
                }
                FmodRenderedAudioValidationResult(
                    profileId = root.optString("profileId"),
                    passed = root.optBoolean("passed"),
                    eventResults = results,
                    excludedInstantiationCount = root.optInt("excludedInstantiationCount"),
                    durationMilliseconds = root.optLong("durationMilliseconds"),
                    outputMode = root.optString("output"),
                    error = root.optString("error").takeIf(String::isNotBlank),
                )
            }.getOrElse { throwable ->
                FmodRenderedAudioValidationResult(
                    profileId = "",
                    passed = false,
                    eventResults = emptyList(),
                    excludedInstantiationCount = 0,
                    durationMilliseconds = 0,
                    outputMode = "",
                    error = "Could not parse native FMOD validation: ${throwable.message}; raw=$raw",
                )
            }
    }
}

private object FmodNativeBindings {
    external fun create(): Long
    external fun loadBanks(handle: Long, profileId: String): Boolean
    external fun update(handle: Long, controlBuffer: ByteBuffer): Boolean
    external fun validateRenderedAudio(handle: Long): String
    external fun cancelRenderedAudioValidation(handle: Long)
    external fun suspendMixer(handle: Long): Boolean
    external fun resumeMixer(handle: Long): Boolean
    external fun diagnostics(handle: Long): String
    external fun lastError(handle: Long): String
    external fun release(handle: Long)
}
