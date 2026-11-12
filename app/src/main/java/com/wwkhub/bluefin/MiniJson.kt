package com.wwkhub.bluefin

/**
 * Small dependency-free JSON reader used by the character-pack importer.
 *
 * The importer has to run on the JVM in unit tests, so it must not depend on
 * Android's `org.json` stubs. Only standard JSON is accepted; comments and
 * trailing commas are rejected, matching `JSON.parse` in the pack format.
 */
internal sealed class JsonValue {
    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    fun asObject(): Obj? = this as? Obj
    fun asArray(): Arr? = this as? Arr
    fun asString(): String? = (this as? Str)?.value
    fun asDouble(): Double? = (this as? Num)?.value
    fun asBoolean(): Boolean? = (this as? Bool)?.value

    fun field(name: String): JsonValue? = asObject()?.fields?.get(name)

    fun intOrNull(): Int? {
        val d = asDouble() ?: return null
        if (!d.isFinite() || d % 1.0 != 0.0 || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
        return d.toInt()
    }

    fun stringMap(): Map<String, String>? {
        val obj = asObject() ?: return null
        val result = LinkedHashMap<String, String>()
        obj.fields.forEach { (key, value) ->
            val text = value.asString()?.trim().orEmpty()
            if (text.isNotEmpty()) result[key] = text
        }
        return result.ifEmpty { null }
    }
}

internal class JsonParseException(message: String) : Exception(message)

internal object MiniJson {
    fun parse(text: String): JsonValue {
        val clean = text.removePrefix("\uFEFF")
        val parser = Parser(clean)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.done()) throw JsonParseException("trailing content at ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        var position = 0
            private set

        fun done(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): JsonValue {
            skipWhitespace()
            if (done()) throw JsonParseException("unexpected end")
            return when (val c = text[position]) {
                '{' -> obj()
                '[' -> array()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') number() else {
                    throw JsonParseException("unexpected '$c' at $position")
                }
            }
        }

        private fun obj(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek('}')) {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) throw JsonParseException("object key expected at $position")
                val key = string()
                skipWhitespace()
                expect(':')
                fields[key] = value()
                skipWhitespace()
                when {
                    peek(',') -> {
                        position++
                        continue
                    }
                    peek('}') -> {
                        position++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonParseException("',' or '}' expected at $position")
                }
            }
        }

        private fun array(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek(']')) {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(value())
                skipWhitespace()
                when {
                    peek(',') -> {
                        position++
                        continue
                    }
                    peek(']') -> {
                        position++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("',' or ']' expected at $position")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (!done()) {
                val c = text[position++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (done()) throw JsonParseException("bad escape")
                        when (val esc = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonParseException("bad unicode escape")
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("bad unicode escape")
                                out.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonParseException("bad escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonParseException("control character in string")
                        out.append(c)
                    }
                }
            }
            throw JsonParseException("unterminated string")
        }

        private fun number(): JsonValue.Num {
            val start = position
            if (peek('-')) position++
            if (peek('0')) {
                position++
            } else {
                if (done() || text[position] !in '1'..'9') throw JsonParseException("bad number at $start")
                while (!done() && text[position] in '0'..'9') position++
            }
            if (!done() && text[position] == '.') {
                position++
                if (done() || text[position] !in '0'..'9') throw JsonParseException("bad fraction at $start")
                while (!done() && text[position] in '0'..'9') position++
            }
            if (!done() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!done() && (text[position] == '+' || text[position] == '-')) position++
                if (done() || text[position] !in '0'..'9') throw JsonParseException("bad exponent at $start")
                while (!done() && text[position] in '0'..'9') position++
            }
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("bad number at $start")
            return JsonValue.Num(value)
        }

        private fun literal(word: String, result: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) throw JsonParseException("bad literal at $position")
            position += word.length
            return result
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (done() || text[position] != c) throw JsonParseException("'$c' expected at $position")
            position++
        }

        private fun peek(c: Char): Boolean = !done() && text[position] == c
    }
}
