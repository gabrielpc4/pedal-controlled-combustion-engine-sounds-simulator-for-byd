package com.gabrielpc.enginesoundsimulator.audio

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PcmLoopData(
    val channelSamples: Array<FloatArray>,
    val sampleRate: Int,
    val loopStartFrame: Int = 0,
    val loopEndFrameExclusive: Int = channelSamples.first().size,
) {
    val sourceChannels: Int get() = channelSamples.size
    val frameCount: Int get() = channelSamples.first().size
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
        var pcmBytes: ByteArray? = null
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
                "data" -> pcmBytes = stream.readExactly(chunkSize)
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
        val bytes = requireNotNull(pcmBytes) { "WAV has no data chunk" }
        require(bytes.size % (channels * 2) == 0) { "Misaligned WAV PCM data" }

        val frameCount = bytes.size / (channels * 2)
        val decodedChannels = Array(channels) { FloatArray(frameCount) }
        var byteIndex = 0
        for (frame in 0 until frameCount) {
            repeat(channels) { channel ->
                val low = bytes[byteIndex++].toInt() and 0xff
                val high = bytes[byteIndex++].toInt()
                decodedChannels[channel][frame] = ((high shl 8) or low).toShort() / 32768.0f
            }
        }
        require(frameCount >= 32) { "WAV is too short to loop" }
        val loopStart = smplLoopStart?.toInt()?.coerceIn(0, frameCount - 1) ?: 0
        val loopEndExclusive = smplLoopEndInclusive
            ?.plus(1L)
            ?.toInt()
            ?.coerceIn(loopStart + 1, frameCount)
            ?: frameCount
        require(loopEndExclusive - loopStart >= 4) { "WAV loop is too short" }
        PcmLoopData(decodedChannels, sampleRate, loopStart, loopEndExclusive)
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
