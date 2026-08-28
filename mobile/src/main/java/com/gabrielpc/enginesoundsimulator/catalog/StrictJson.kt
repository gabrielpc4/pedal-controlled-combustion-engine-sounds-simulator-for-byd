package com.gabrielpc.enginesoundsimulator.catalog

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal sealed class JsonValue {
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue()
    data class ArrayValue(val values: List<JsonValue>) : JsonValue()
    data class StringValue(val value: String) : JsonValue()
    data class NumberValue(val source: String) : JsonValue()
    data class BooleanValue(val value: Boolean) : JsonValue()
    data object NullValue : JsonValue()
}

internal class JsonValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Small strict JSON reader so pack validation does not add a general-purpose JSON runtime. */
internal object StrictJson {
    private const val MAX_DEPTH = 64
    private const val FORBIDDEN_LOAD_LENGTH = 4
    private const val HEX_DIGITS = "0123456789abcdef"

    fun parse(bytes: ByteArray): JsonValue {
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw JsonValidationException("JSON is not valid UTF-8", error)
        }
        return Parser(source).parse()
    }

    /** Matches the former `[a-z0-9]+`/case-insensitive LOAD check without MatchResult churn. */
    fun containsForbiddenLoadToken(bytes: ByteArray): Boolean {
        if (bytes.size < FORBIDDEN_LOAD_LENGTH) return false
        val lastStart = bytes.size - FORBIDDEN_LOAD_LENGTH
        for (index in 0..lastStart) {
            if (
                bytes[index].asciiLowercase() == 'l'.code &&
                bytes[index + 1].asciiLowercase() == 'o'.code &&
                bytes[index + 2].asciiLowercase() == 'a'.code &&
                bytes[index + 3].asciiLowercase() == 'd'.code &&
                (index == 0 || !bytes[index - 1].isAsciiLetterOrDigit()) &&
                (index + FORBIDDEN_LOAD_LENGTH == bytes.size ||
                    !bytes[index + FORBIDDEN_LOAD_LENGTH].isAsciiLetterOrDigit())
            ) {
                return true
            }
        }
        return false
    }

    /** Matches the compiler's UTF-8, sorted-key, compact JSON hashing representation. */
    fun canonicalBytes(value: JsonValue): ByteArray = buildString {
        appendCanonical(value)
    }.toByteArray(StandardCharsets.UTF_8)

    /** Hashes a root object without materializing either a copied map or a full canonical string. */
    fun canonicalSha256ExcludingObjectKey(
        values: Map<String, JsonValue>,
        excludedKey: String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = DigestUtf8Appendable(digest)
        writer.appendCanonicalObject(values, excludedKey)
        writer.finish()
        return digest.digest()
    }

    private fun Appendable.appendCanonical(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> appendCanonicalObject(value.values)
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendCanonical(child)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.source)
            is JsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            JsonValue.NullValue -> append("null")
        }
    }

    private fun Appendable.appendCanonicalObject(
        values: Map<String, JsonValue>,
        excludedKey: String? = null,
    ) {
        append('{')
        var first = true
        if (values.keys.keysAreAlreadySorted(excludedKey)) {
            for ((key, child) in values) {
                if (key == excludedKey) continue
                if (!first) append(',')
                first = false
                appendQuoted(key)
                append(':')
                appendCanonical(child)
            }
        } else {
            val sortedKeys = ArrayList<String>(values.size)
            for (key in values.keys) if (key != excludedKey) sortedKeys += key
            sortedKeys.sort()
            for (key in sortedKeys) {
                if (!first) append(',')
                first = false
                appendQuoted(key)
                append(':')
                appendCanonical(values.getValue(key))
            }
        }
        append('}')
    }

    private fun Set<String>.keysAreAlreadySorted(excludedKey: String?): Boolean {
        var previous: String? = null
        for (key in this) {
            if (key == excludedKey) continue
            val prior = previous
            if (prior != null && prior > key) return false
            previous = key
        }
        return true
    }

    private fun Appendable.appendQuoted(value: String) {
        append('"')
        var runStart = 0
        for (index in value.indices) {
            val character = value[index]
            val escaped = when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\u000c' -> "\\f"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> null
            }
            if (escaped != null || character.code < 0x20) {
                if (runStart < index) append(value, runStart, index)
                if (escaped != null) {
                    append(escaped)
                } else {
                    append("\\u00")
                    append(HEX_DIGITS[character.code ushr 4])
                    append(HEX_DIGITS[character.code and 0x0f])
                }
                runStart = index + 1
            }
        }
        if (runStart < value.length) append(value, runStart, value.length)
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (index != source.length) fail("Unexpected trailing content")
            return value
        }

        private fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH levels")
            if (index >= source.length) fail("Unexpected end of JSON")
            return when (source[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> readNumber()
                else -> fail("Unexpected character '${source[index]}'")
            }
        }

        private fun readObject(depth: Int): JsonValue.ObjectValue {
            index += 1
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(values)
            while (true) {
                if (index >= source.length || source[index] != '"') {
                    fail("Object key must be a string")
                }
                val key = readString()
                if (values.containsKey(key)) fail("Duplicate JSON key '$key'")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                values[key] = readValue(depth)
                skipWhitespace()
                if (consume('}')) break
                expect(',')
                skipWhitespace()
            }
            return JsonValue.ObjectValue(values)
        }

        private fun readArray(depth: Int): JsonValue.ArrayValue {
            index += 1
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
                skipWhitespace()
            }
            return JsonValue.ArrayValue(values)
        }

        private fun readString(): String {
            expect('"')
            val unescapedStart = index
            while (index < source.length) {
                val character = source[index]
                when {
                    character == '"' -> {
                        val value = source.substring(unescapedStart, index)
                        index += 1
                        return value
                    }
                    character == '\\' -> break
                    character.code < 0x20 -> fail("Unescaped control character in string")
                    else -> index += 1
                }
            }
            if (index >= source.length) fail("Unterminated JSON string")

            val value = StringBuilder(index - unescapedStart + 16)
            value.append(source, unescapedStart, index)
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> {
                        if (index >= source.length) fail("Unterminated string escape")
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> value.append(escaped)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000c')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> value.append(readUnicodeEscape())
                            else -> fail("Invalid string escape \\$escaped")
                        }
                    }
                    character.code < 0x20 -> fail("Unescaped control character in string")
                    else -> value.append(character)
                }
            }
            fail("Unterminated JSON string")
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > source.length) fail("Incomplete unicode escape")
            var code = 0
            repeat(4) {
                val digit = source[index++].hexDigitValue()
                if (digit < 0) fail("Invalid unicode escape")
                code = (code shl 4) or digit
            }
            return code.toChar()
        }

        private fun readNumber(): JsonValue.NumberValue {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) fail("Leading zero in number")
            } else {
                requireDigits()
            }
            if (consume('.')) requireDigits()
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index += 1
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
                requireDigits()
            }
            return JsonValue.NumberValue(source.substring(start, index))
        }

        private fun requireDigits() {
            val start = index
            while (index < source.length && source[index] in '0'..'9') index += 1
            if (start == index) fail("Expected a decimal digit")
        }

        private fun <T : JsonValue> readLiteral(literal: String, value: T): T {
            if (!source.regionMatches(index, literal, 0, literal.length)) {
                fail("Invalid JSON literal")
            }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length) {
                when (source[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun expect(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun consume(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index += 1
                return true
            }
            return false
        }

        private fun fail(message: String): Nothing =
            throw JsonValidationException("$message at character $index")
    }

    private fun Byte.asciiLowercase(): Int {
        val value = toInt() and 0xff
        return if (value in 'A'.code..'Z'.code) value + ('a'.code - 'A'.code) else value
    }

    private fun Byte.isAsciiLetterOrDigit(): Boolean {
        val value = toInt() and 0xff
        return value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code
    }

    private fun Char.hexDigitValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> -1
    }

    /** Buffered UTF-8 writer that avoids both a catalog-sized String and intermediate byte array. */
    private class DigestUtf8Appendable(
        private val digest: MessageDigest,
    ) : Appendable {
        private val buffer = ByteArray(8 * 1024)
        private var size = 0

        override fun append(value: Char): Appendable {
            appendCodeUnit(value, null)
            return this
        }

        override fun append(value: CharSequence?): Appendable {
            val text = value ?: "null"
            return append(text, 0, text.length)
        }

        override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
            val text = value ?: "null"
            var index = startIndex
            while (index < endIndex) {
                val character = text[index]
                val following = if (index + 1 < endIndex) text[index + 1] else null
                if (appendCodeUnit(character, following)) index += 1
                index += 1
            }
            return this
        }

        fun finish() {
            flush()
        }

        /** Returns true when a valid low surrogate was consumed with this code unit. */
        private fun appendCodeUnit(character: Char, following: Char?): Boolean {
            val code = character.code
            when {
                code < 0x80 -> appendByte(code)
                code < 0x800 -> {
                    appendByte(0xc0 or (code ushr 6))
                    appendByte(0x80 or (code and 0x3f))
                }
                character.isHighSurrogate() && following?.isLowSurrogate() == true -> {
                    val codePoint = Character.toCodePoint(character, following)
                    appendByte(0xf0 or (codePoint ushr 18))
                    appendByte(0x80 or ((codePoint ushr 12) and 0x3f))
                    appendByte(0x80 or ((codePoint ushr 6) and 0x3f))
                    appendByte(0x80 or (codePoint and 0x3f))
                    return true
                }
                character.isSurrogate() -> appendByte('?'.code)
                else -> {
                    appendByte(0xe0 or (code ushr 12))
                    appendByte(0x80 or ((code ushr 6) and 0x3f))
                    appendByte(0x80 or (code and 0x3f))
                }
            }
            return false
        }

        private fun appendByte(value: Int) {
            if (size == buffer.size) flush()
            buffer[size++] = value.toByte()
        }

        private fun flush() {
            if (size == 0) return
            digest.update(buffer, 0, size)
            size = 0
        }
    }
}

internal fun JsonValue.asObject(label: String): Map<String, JsonValue> =
    (this as? JsonValue.ObjectValue)?.values ?: throw JsonValidationException("$label must be an object")

internal fun JsonValue.asArray(label: String): List<JsonValue> =
    (this as? JsonValue.ArrayValue)?.values ?: throw JsonValidationException("$label must be an array")

internal fun JsonValue.asString(label: String): String =
    (this as? JsonValue.StringValue)?.value ?: throw JsonValidationException("$label must be a string")

internal fun JsonValue.asBoolean(label: String): Boolean =
    (this as? JsonValue.BooleanValue)?.value ?: throw JsonValidationException("$label must be a boolean")

internal fun JsonValue.asDouble(label: String): Double {
    val number = (this as? JsonValue.NumberValue)?.source?.toDoubleOrNull()
        ?: throw JsonValidationException("$label must be a number")
    if (!number.isFinite()) throw JsonValidationException("$label must be finite")
    return number
}

internal fun JsonValue.asLong(label: String): Long {
    val source = (this as? JsonValue.NumberValue)?.source
        ?: throw JsonValidationException("$label must be an integer")
    if (source.any { it == '.' || it == 'e' || it == 'E' }) {
        throw JsonValidationException("$label must be an integer")
    }
    return source.toLongOrNull() ?: throw JsonValidationException("$label is outside the integer range")
}

internal fun JsonValue.asNullableString(label: String): String? = when (this) {
    JsonValue.NullValue -> null
    is JsonValue.StringValue -> value
    else -> throw JsonValidationException("$label must be a string or null")
}

internal fun Map<String, JsonValue>.requireExactKeys(label: String, expected: Set<String>) {
    if (size == expected.size) {
        var exact = true
        for (key in keys) {
            if (key !in expected) {
                exact = false
                break
            }
        }
        if (exact) return
    }
    val missing = expected.filterNotTo(linkedSetOf(), ::containsKey)
    val unknown = keys.filterNotTo(linkedSetOf(), expected::contains)
    if (missing.isNotEmpty() || unknown.isNotEmpty()) {
        throw JsonValidationException("$label fields mismatch: missing=$missing unknown=$unknown")
    }
}

internal fun Map<String, JsonValue>.getRequired(key: String): JsonValue =
    get(key) ?: throw JsonValidationException("Missing required field '$key'")
