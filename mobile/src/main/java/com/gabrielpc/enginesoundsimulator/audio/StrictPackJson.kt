package com.gabrielpc.enginesoundsimulator.audio

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface PackJsonValue {
    data class ObjectValue(val values: Map<String, PackJsonValue>) : PackJsonValue
    data class ArrayValue(val values: List<PackJsonValue>) : PackJsonValue
    data class StringValue(val value: String) : PackJsonValue
    data class IntegerValue(val value: Long) : PackJsonValue
    data class BooleanValue(val value: Boolean) : PackJsonValue
    data object NullValue : PackJsonValue
}

internal class PackJsonException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** A deliberately small JSON reader that rejects duplicate keys and non-integer numbers. */
internal object StrictPackJson {
    fun parse(bytes: ByteArray): PackJsonValue {
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw PackJsonException("Manifest is not valid UTF-8", error)
        }

        return Parser(source).parse()
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): PackJsonValue {
            skipWhitespace()
            val value = readValue(depth = 0)
            skipWhitespace()
            if (index != source.length) fail("Unexpected trailing content")

            return value
        }

        private fun readValue(depth: Int): PackJsonValue {
            if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH levels")
            if (index >= source.length) fail("Unexpected end of JSON")

            return when (source[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> PackJsonValue.StringValue(readString())
                't' -> readLiteral("true", PackJsonValue.BooleanValue(true))
                'f' -> readLiteral("false", PackJsonValue.BooleanValue(false))
                'n' -> readLiteral("null", PackJsonValue.NullValue)
                '-', in '0'..'9' -> PackJsonValue.IntegerValue(readInteger())
                else -> fail("Unexpected character '${source[index]}'")
            }
        }

        private fun readObject(depth: Int): PackJsonValue.ObjectValue {
            index += 1
            skipWhitespace()
            val values = linkedMapOf<String, PackJsonValue>()
            if (consume('}')) return PackJsonValue.ObjectValue(values)

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

            return PackJsonValue.ObjectValue(values)
        }

        private fun readArray(depth: Int): PackJsonValue.ArrayValue {
            index += 1
            skipWhitespace()
            val values = mutableListOf<PackJsonValue>()
            if (consume(']')) return PackJsonValue.ArrayValue(values)

            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
                skipWhitespace()
            }

            return PackJsonValue.ArrayValue(values)
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> result.append(readEscapedCharacter())
                    character.code < 0x20 -> fail("Unescaped control character in string")
                    character.isSurrogate() -> fail("Manifest strings cannot contain unescaped surrogates")
                    else -> result.append(character)
                }
            }
            fail("Unterminated JSON string")
        }

        private fun readEscapedCharacter(): Char {
            if (index >= source.length) fail("Unterminated string escape")

            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> fail("Invalid string escape \\$escaped")
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > source.length) fail("Incomplete unicode escape")
            var code = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(16) ?: fail("Invalid unicode escape")
                code = (code shl 4) or digit
            }
            val value = code.toChar()
            if (value.isSurrogate()) fail("Manifest strings cannot contain surrogate code units")

            return value
        }

        private fun readInteger(): Long {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) fail("Leading zero in number")
            } else {
                val digitStart = index
                while (index < source.length && source[index] in '0'..'9') index += 1
                if (digitStart == index) fail("Expected a decimal digit")
            }
            if (index < source.length && source[index] in charArrayOf('.', 'e', 'E')) {
                fail("Manifest numbers must be integers")
            }

            return source.substring(start, index).toLongOrNull() ?: fail("Integer is outside the supported range")
        }

        private fun <T : PackJsonValue> readLiteral(literal: String, value: T): T {
            if (!source.regionMatches(index, literal, 0, literal.length)) fail("Invalid JSON literal")
            index += literal.length

            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\t', '\n', '\r')) {
                index += 1
            }
        }

        private fun expect(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index += 1

            return true
        }

        private fun fail(message: String): Nothing = throw PackJsonException("$message at character $index")
    }

    private const val MAX_DEPTH = 12
}

internal fun PackJsonValue.objectValues(label: String): Map<String, PackJsonValue> =
    (this as? PackJsonValue.ObjectValue)?.values ?: throw PackJsonException("$label must be an object")

internal fun PackJsonValue.arrayValues(label: String): List<PackJsonValue> =
    (this as? PackJsonValue.ArrayValue)?.values ?: throw PackJsonException("$label must be an array")

internal fun PackJsonValue.stringValue(label: String): String =
    (this as? PackJsonValue.StringValue)?.value ?: throw PackJsonException("$label must be a string")

internal fun PackJsonValue.integerValue(label: String): Long =
    (this as? PackJsonValue.IntegerValue)?.value ?: throw PackJsonException("$label must be an integer")
