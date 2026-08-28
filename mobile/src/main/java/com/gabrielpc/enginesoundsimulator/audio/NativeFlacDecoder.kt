package com.gabrielpc.enginesoundsimulator.audio

import android.app.ActivityManager
import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** One process-wide load point shared by the decoder and mixer entry paths. */
internal object NativeAudioLibrary {
    init {
        System.loadLibrary("engine_audio_native")
    }

    fun ensureLoaded() = Unit
}

/** Native libFLAC decoder used for private, locally imported `.aclib` sound packs. */
internal object NativeFlacDecoder {
    init {
        NativeAudioLibrary.ensureLoaded()
    }

    fun decode(file: File, hardBudgetBytes: Long): NativePcm16Clip =
        NativeDecodeCancellation().use { cancellation ->
            decode(file, hardBudgetBytes, cancellation)
        }

    fun decode(
        file: File,
        hardBudgetBytes: Long,
        cancellation: NativeDecodeCancellation,
    ): NativePcm16Clip {
        require(file.isFile) { "FLAC file does not exist: ${file.name}" }
        require(hardBudgetBytes > 0L) { "Decoded-audio budget must be positive" }
        val handle = nativeDecode(file.absolutePath, hardBudgetBytes, cancellation.nativeHandle())
        check(handle != 0L) { "Native FLAC decoder returned an empty handle" }
        return try {
            NativePcm16Clip(
                nativeHandle = handle,
                sampleRate = nativeSampleRate(handle),
                channelCount = nativeChannels(handle),
                frameCount = nativeFrames(handle),
            )
        } catch (throwable: Throwable) {
            nativeRelease(handle)
            throw throwable
        }
    }

    internal fun channelBuffer(handle: Long, channel: Int): ByteBuffer =
        nativeChannelBuffer(handle, channel).order(ByteOrder.nativeOrder())

    internal fun release(handle: Long) = nativeRelease(handle)

    internal fun createCancellation(): Long = nativeCreateCancellation()
    internal fun cancel(handle: Long) = nativeCancel(handle)
    internal fun releaseCancellation(handle: Long) = nativeReleaseCancellation(handle)

    internal fun testClip(interleavedStereo: ShortArray): NativePcm16Clip {
        require(interleavedStereo.size >= 8 && interleavedStereo.size % 2 == 0)
        val handle = nativeCreateTestClip(interleavedStereo)
        return NativePcm16Clip(handle, 48_000, 2, (interleavedStereo.size / 2).toLong())
    }

    private external fun nativeDecode(path: String, maxDecodedBytes: Long, cancellationHandle: Long): Long
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeChannels(handle: Long): Int
    private external fun nativeFrames(handle: Long): Long
    private external fun nativeChannelBuffer(handle: Long, channel: Int): ByteBuffer
    private external fun nativeRelease(handle: Long)
    private external fun nativeCreateCancellation(): Long
    private external fun nativeCancel(handle: Long)
    private external fun nativeReleaseCancellation(handle: Long)
    private external fun nativeCreateTestClip(interleavedStereo: ShortArray): Long
}

/** Native cancellation flag checked between FLAC decoder blocks. */
internal class NativeDecodeCancellation : Closeable {
    private val handle = AtomicLong(NativeFlacDecoder.createCancellation())
    private val cancelled = AtomicBoolean(false)

    init {
        check(handle.get() != 0L) { "Unable to allocate FLAC cancellation token" }
    }

    fun cancel() {
        cancelled.set(true)
        val activeHandle = handle.get()
        if (activeHandle != 0L) NativeFlacDecoder.cancel(activeHandle)
    }

    internal fun nativeHandle(): Long {
        val activeHandle = handle.get()
        check(activeHandle != 0L) { "FLAC cancellation token is closed" }
        return activeHandle
    }

    fun isCancelled(): Boolean = cancelled.get()

    override fun close() {
        val activeHandle = handle.getAndSet(0L)
        if (activeHandle != 0L) {
            NativeFlacDecoder.cancel(activeHandle)
            NativeFlacDecoder.releaseCancellation(activeHandle)
        }
    }
}

/** Immutable native planar PCM16. Closing invalidates every channel view. */
internal class NativePcm16Clip internal constructor(
    nativeHandle: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
) : Closeable {
    private val handle = AtomicLong(nativeHandle)

    internal fun activeHandle(): Long = handle.get().also { check(it != 0L) { "PCM clip is closed" } }

    init {
        require(sampleRate == SAMPLE_RATE_HZ) { "Expected 48 kHz PCM, got $sampleRate Hz" }
        require(channelCount in 1..2) { "Expected mono or stereo PCM, got $channelCount channels" }
        require(frameCount > 0L) { "Decoded FLAC is empty" }
        require(frameCount <= Int.MAX_VALUE.toLong()) { "Decoded FLAC is too long for indexed mixing" }
    }

    val decodedBytes: Long
        get() = frameCount * channelCount * Short.SIZE_BYTES

    fun channel(index: Int): ShortBuffer {
        require(index in 0 until channelCount) { "Invalid PCM channel $index" }
        val activeHandle = handle.get()
        check(activeHandle != 0L) { "PCM clip is closed" }
        return NativeFlacDecoder.channelBuffer(activeHandle, index)
            .asShortBuffer()
            .asReadOnlyBuffer()
    }

    override fun close() {
        val activeHandle = handle.getAndSet(0L)
        if (activeHandle != 0L) NativeFlacDecoder.release(activeHandle)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
    }
}

internal data class DecodedAudioBudget(
    val softBytes: Long,
    val hardBytes: Long,
) {
    init {
        require(softBytes > 0L) { "Decoded-audio soft budget must be positive" }
        require(hardBytes >= softBytes) { "Decoded-audio hard budget must cover the soft budget" }
    }

    companion object {
        private const val MIB = 1024L * 1024L

        fun forDevice(context: Context): DecodedAudioBudget {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryClassBytes = activityManager.memoryClass.toLong() * MIB
            return DecodedAudioBudget(
                softBytes = min(64L * MIB, memoryClassBytes / 8L),
                hardBytes = min(192L * MIB, memoryClassBytes / 4L),
            )
        }
    }
}
