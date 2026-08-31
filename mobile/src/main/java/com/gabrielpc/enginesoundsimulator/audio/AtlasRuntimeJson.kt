package com.gabrielpc.enginesoundsimulator.audio

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface AtlasJsonValue {
    data class ObjectValue(val values: Map<String, AtlasJsonValue>) : AtlasJsonValue
    data class ArrayValue(val values: List<AtlasJsonValue>) : AtlasJsonValue
    data class StringValue(val value: String) : AtlasJsonValue
    data class NumberValue(val value: Double) : AtlasJsonValue
    data class BooleanValue(val value: Boolean) : AtlasJsonValue
    data object NullValue : AtlasJsonValue
}

internal class AtlasJsonException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Strict finite-number JSON parser for generated runtime/catalog files. */
internal object AtlasRuntimeJson {
    fun parse(bytes: ByteArray): AtlasJsonValue {
        require(bytes.isNotEmpty()) { "Runtime JSON is empty" }
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw AtlasJsonException("Runtime JSON is not valid UTF-8", error)
        }

        return Parser(source).parse()
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): AtlasJsonValue {
            skipWhitespace()
            val value = readValue(depth = 0)
            skipWhitespace()
            if (index != source.length) fail("Unexpected trailing content")

            return value
        }

        private fun readValue(depth: Int): AtlasJsonValue {
            if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH levels")
            if (index >= source.length) fail("Unexpected end of JSON")

            return when (source[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> AtlasJsonValue.StringValue(readString())
                't' -> readLiteral("true", AtlasJsonValue.BooleanValue(true))
                'f' -> readLiteral("false", AtlasJsonValue.BooleanValue(false))
                'n' -> readLiteral("null", AtlasJsonValue.NullValue)
                '-', in '0'..'9' -> AtlasJsonValue.NumberValue(readNumber())
                else -> fail("Unexpected character '${source[index]}'")
            }
        }

        private fun readObject(depth: Int): AtlasJsonValue.ObjectValue {
            index += 1
            skipWhitespace()
            val values = linkedMapOf<String, AtlasJsonValue>()
            if (consume('}')) return AtlasJsonValue.ObjectValue(values)

            while (true) {
                if (index >= source.length || source[index] != '"') fail("Object key must be a string")
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

            return AtlasJsonValue.ObjectValue(values)
        }

        private fun readArray(depth: Int): AtlasJsonValue.ArrayValue {
            index += 1
            skipWhitespace()
            val values = mutableListOf<AtlasJsonValue>()
            if (consume(']')) return AtlasJsonValue.ArrayValue(values)

            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
                skipWhitespace()
            }

            return AtlasJsonValue.ArrayValue(values)
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> appendEscapedCharacter(result)
                    character.code < 0x20 -> fail("Unescaped control character in string")
                    character.isHighSurrogate() -> {
                        if (index >= source.length || !source[index].isLowSurrogate()) {
                            fail("Strings cannot contain an unpaired high surrogate")
                        }
                        result.append(character)
                        result.append(source[index++])
                    }
                    character.isLowSurrogate() -> fail("Strings cannot contain an unpaired low surrogate")
                    else -> result.append(character)
                }
            }
            fail("Unterminated JSON string")
        }

        private fun appendEscapedCharacter(result: StringBuilder) {
            if (index >= source.length) fail("Unterminated string escape")

            when (val escaped = source[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> appendUnicodeEscape(result)
                else -> fail("Invalid string escape \\$escaped")
            }
        }

        private fun appendUnicodeEscape(result: StringBuilder) {
            val first = readUnicodeCodeUnit()
            when {
                first.isHighSurrogate() -> {
                    if (index + 2 > source.length || source[index] != '\\' || source[index + 1] != 'u') {
                        fail("Escaped high surrogate must be followed by an escaped low surrogate")
                    }
                    index += 2
                    val second = readUnicodeCodeUnit()
                    if (!second.isLowSurrogate()) {
                        fail("Escaped high surrogate must be followed by an escaped low surrogate")
                    }
                    result.append(first)
                    result.append(second)
                }
                first.isLowSurrogate() -> fail("Strings cannot contain an unpaired escaped low surrogate")
                else -> result.append(first)
            }
        }

        private fun readUnicodeCodeUnit(): Char {
            if (index + 4 > source.length) fail("Incomplete unicode escape")
            var code = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(16) ?: fail("Invalid unicode escape")
                code = (code shl 4) or digit
            }

            return code.toChar()
        }

        private fun readNumber(): Double {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) fail("Leading zero in number")
            } else {
                val digits = index
                while (index < source.length && source[index].isDigit()) index += 1
                if (digits == index) fail("Expected a decimal digit")
            }
            if (consume('.')) {
                val digits = index
                while (index < source.length && source[index].isDigit()) index += 1
                if (digits == index) fail("Expected digits after decimal point")
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index += 1
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
                val digits = index
                while (index < source.length && source[index].isDigit()) index += 1
                if (digits == index) fail("Expected exponent digits")
            }
            val value = source.substring(start, index).toDoubleOrNull() ?: fail("Invalid number")
            if (!value.isFinite()) fail("Numbers must be finite")

            return value
        }

        private fun <T : AtlasJsonValue> readLiteral(literal: String, value: T): T {
            if (!source.regionMatches(index, literal, 0, literal.length)) fail("Invalid JSON literal")
            index += literal.length

            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\t', '\n', '\r')) index += 1
        }

        private fun expect(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index += 1

            return true
        }

        private fun fail(message: String): Nothing = throw AtlasJsonException("$message at character $index")
    }

    private const val MAX_DEPTH = 32
}

internal fun AtlasJsonValue.objectValues(label: String): Map<String, AtlasJsonValue> =
    (this as? AtlasJsonValue.ObjectValue)?.values ?: throw AtlasJsonException("$label must be an object")

internal fun AtlasJsonValue.arrayValues(label: String): List<AtlasJsonValue> =
    (this as? AtlasJsonValue.ArrayValue)?.values ?: throw AtlasJsonException("$label must be an array")

internal fun AtlasJsonValue.stringValue(label: String): String =
    (this as? AtlasJsonValue.StringValue)?.value ?: throw AtlasJsonException("$label must be a string")

internal fun AtlasJsonValue.numberValue(label: String): Double =
    (this as? AtlasJsonValue.NumberValue)?.value ?: throw AtlasJsonException("$label must be a number")

internal fun AtlasJsonValue.intValue(label: String): Int {
    val number = numberValue(label)
    require(number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        "$label must be an integer"
    }

    return number.toInt()
}

internal fun AtlasJsonValue.longValue(label: String): Long {
    val number = numberValue(label)
    require(number % 1.0 == 0.0 && number in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
        "$label must be an integer"
    }

    return number.toLong()
}

internal fun AtlasJsonValue.booleanValue(label: String): Boolean =
    (this as? AtlasJsonValue.BooleanValue)?.value ?: throw AtlasJsonException("$label must be a boolean")

internal fun AtlasJsonValue.nullableLongValue(label: String): Long? = when (this) {
    AtlasJsonValue.NullValue -> null
    else -> longValue(label)
}
