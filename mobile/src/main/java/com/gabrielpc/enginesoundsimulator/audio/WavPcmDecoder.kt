package com.gabrielpc.enginesoundsimulator.audio

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PcmLoopData(
    val interleavedSamples: ShortArray,
    val sourceChannels: Int,
    val sampleRate: Int,
    val loopStartFrame: Int = 0,
    val loopEndFrameExclusive: Int = interleavedSamples.size / sourceChannels,
) {
    init {
        require(sourceChannels in 1..2)
        require(interleavedSamples.size % sourceChannels == 0)
    }

    val frameCount: Int get() = interleavedSamples.size / sourceChannels
    val decodedBytes: Long get() = interleavedSamples.size.toLong() * Short.SIZE_BYTES

    fun sampleAt(channel: Int, frame: Int): Float =
        interleavedSamples[frame * sourceChannels + channel] / 32768.0f
}

internal object WavPcmDecoder {
    fun decode(input: InputStream): PcmLoopData = input.buffered().use { stream ->
        require(stream.readAscii(4) == "RIFF") { "Not a RIFF file" }
        stream.readUInt32Le()
        require(stream.readAscii(4) == "WAVE") { "Not a WAVE file" }

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcmSamples: ShortArray? = null
        var smplLoopStart: Long? = null
        var smplLoopEndInclusive: Long? = null

        while (true) {
            val chunkId = stream.readAsciiOrNull(4) ?: break
            val chunkSize = stream.readUInt32Le().toInt()
            require(chunkSize >= 0) { "Invalid WAV chunk size" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Truncated WAV format chunk" }
                    format = stream.readUInt16Le()
                    channels = stream.readUInt16Le()
                    sampleRate = stream.readUInt32Le().toInt()
                    stream.skipFully(6)
                    bitsPerSample = stream.readUInt16Le()
                    stream.skipFully(chunkSize - 16)
                }
                "data" -> {
                    require(format == 1) { "WAV data appeared before a PCM format chunk" }
                    require(channels in 1..2) { "Only mono/stereo WAV is supported (channels=$channels)" }
                    require(bitsPerSample == 16) { "Only PCM16 WAV is supported (bits=$bitsPerSample)" }
                    require(chunkSize % (channels * 2) == 0) { "Misaligned WAV PCM data" }
                    val samples = ShortArray(chunkSize / Short.SIZE_BYTES)
                    for (index in samples.indices) {
                        samples[index] = stream.readUInt16Le().toShort()
                    }
                    pcmSamples = samples
                }
                "smpl" -> {
                    val bytes = stream.readExactly(chunkSize)
                    if (bytes.size >= 60) {
                        val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val loopCount = values.getInt(28).toLong() and 0xffff_ffffL
                        if (loopCount > 0L) {
                            smplLoopStart = values.getInt(44).toLong() and 0xffff_ffffL
                            smplLoopEndInclusive = values.getInt(48).toLong() and 0xffff_ffffL
                        }
                    }
                }
                else -> stream.skipFully(chunkSize)
            }
            if ((chunkSize and 1) != 0) stream.skipFully(1)
        }

        require(format == 1) { "Only uncompressed PCM WAV is supported (format=$format)" }
        require(channels in 1..2) { "Only mono/stereo WAV is supported (channels=$channels)" }
        require(sampleRate > 0) { "Invalid WAV sample rate" }
        require(bitsPerSample == 16) { "Only PCM16 WAV is supported (bits=$bitsPerSample)" }
        val samples = requireNotNull(pcmSamples) { "WAV has no data chunk" }
        val frameCount = samples.size / channels
        require(frameCount >= 32) { "WAV is too short to loop" }
        val loopStart = smplLoopStart?.toInt()?.coerceIn(0, frameCount - 1) ?: 0
        val loopEndExclusive = smplLoopEndInclusive
            ?.plus(1L)
            ?.toInt()
            ?.coerceIn(loopStart + 1, frameCount)
            ?: frameCount
        require(loopEndExclusive - loopStart >= 4) { "WAV loop is too short" }
        PcmLoopData(samples, channels, sampleRate, loopStart, loopEndExclusive)
    }
}

private fun InputStream.readAscii(count: Int): String = String(readExactly(count), Charsets.US_ASCII)

private fun InputStream.readAsciiOrNull(count: Int): String? {
    val first = read()
    if (first < 0) return null
    val bytes = ByteArray(count)
    bytes[0] = first.toByte()
    var offset = 1
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw EOFException("Unexpected end of WAV")
        offset += read
    }
    return String(bytes, Charsets.US_ASCII)
}

private fun InputStream.readUInt16Le(): Int {
    val low = read()
    val high = read()
    if (low < 0 || high < 0) throw EOFException("Unexpected end of WAV")
    return low or (high shl 8)
}

private fun InputStream.readUInt32Le(): Long {
    var value = 0L
    repeat(4) { shift ->
        val byte = read()
        if (byte < 0) throw EOFException("Unexpected end of WAV")
        value = value or (byte.toLong() shl (shift * 8))
    }
    return value
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val result = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(result, offset, count - offset)
        if (read < 0) throw EOFException("Unexpected end of WAV")
        offset += read
    }
    return result
}

private fun InputStream.skipFully(count: Int) {
    var remaining = count.toLong()
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else if (read() >= 0) {
            remaining -= 1
        } else {
            throw EOFException("Unexpected end of WAV")
        }
    }
}
