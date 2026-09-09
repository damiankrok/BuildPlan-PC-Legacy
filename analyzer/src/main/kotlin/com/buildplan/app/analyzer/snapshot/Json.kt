package com.buildplan.app.analyzer.snapshot

/**
 * A small JSON model with a deterministic writer and a strict parser.
 *
 * Written rather than depended on: the snapshot needs stable key order and
 * stable number formatting so that two runs over the same source produce
 * byte-identical files, and `org.json` is a stub on the JVM unit-test
 * classpath while a serialization framework would be a plugin plus an
 * annotation processor for a dozen record types. Objects keep insertion
 * order; numbers are written with the shortest round-trip form; no NaN or
 * infinity can be written at all.
 */
sealed interface JsonValue {
    data class Obj(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun num(key: String): Double? = (fields[key] as? Num)?.value
        fun int(key: String): Int? = num(key)?.toInt()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
    }

    data class Arr(val items: List<JsonValue>) : JsonValue {
        val objects: List<Obj> get() = items.filterIsInstance<Obj>()
    }

    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue {
        init {
            require(value.isFinite()) { "JSON cannot carry $value" }
        }
    }

    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** Builder sugar: `json { "a" to 1; "b" to listOf(...) }`. */
class JsonObjectBuilder {
    val obj = JsonValue.Obj()

    infix fun String.to(value: JsonValue?) {
        obj.fields[this] = value ?: JsonValue.Null
    }

    infix fun String.to(value: String?) {
        obj.fields[this] = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
    }

    infix fun String.to(value: Double?) {
        obj.fields[this] = value?.let { JsonValue.Num(it) } ?: JsonValue.Null
    }

    infix fun String.to(value: Int?) {
        obj.fields[this] = value?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
    }

    infix fun String.to(value: Long?) {
        obj.fields[this] = value?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
    }

    infix fun String.to(value: Boolean?) {
        obj.fields[this] = value?.let { JsonValue.Bool(it) } ?: JsonValue.Null
    }

    infix fun String.to(value: Enum<*>?) {
        obj.fields[this] = value?.let { JsonValue.Str(it.name) } ?: JsonValue.Null
    }

    infix fun String.toArray(values: List<JsonValue>) {
        obj.fields[this] = JsonValue.Arr(values)
    }

    infix fun String.toStrings(values: List<String>) {
        obj.fields[this] = JsonValue.Arr(values.map { JsonValue.Str(it) })
    }

    infix fun String.toNumbers(values: List<Double>) {
        obj.fields[this] = JsonValue.Arr(values.map { JsonValue.Num(it) })
    }
}

fun json(build: JsonObjectBuilder.() -> Unit): JsonValue.Obj = JsonObjectBuilder().apply(build).obj

object Json {

    fun write(value: JsonValue, pretty: Boolean = true): String {
        val out = StringBuilder()
        writeValue(value, out, if (pretty) 0 else -1)
        if (pretty) out.append('\n')
        return out.toString()
    }

    private fun writeValue(value: JsonValue, out: StringBuilder, indent: Int) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> out.append(formatNumber(value.value))
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    newline(out, indent + 1)
                    writeValue(item, out, if (indent < 0) -1 else indent + 1)
                }
                newline(out, indent)
                out.append(']')
            }

            is JsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                value.fields.forEach { (key, item) ->
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent + 1)
                    writeString(key, out)
                    out.append(if (indent < 0) ":" else ": ")
                    writeValue(item, out, if (indent < 0) -1 else indent + 1)
                }
                newline(out, indent)
                out.append('}')
            }
        }
    }

    private fun newline(out: StringBuilder, indent: Int) {
        if (indent < 0) return
        out.append('\n')
        repeat(indent) { out.append("  ") }
    }

    /** Integers print as integers; other values with the shortest form that round-trips. */
    fun formatNumber(value: Double): String {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
        val text = value.toString()
        return if ('E' in text || 'e' in text) java.math.BigDecimal(value).stripTrailingZeros().toPlainString() else text
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            val value = parseValue()
            skipWhitespace()
            require(i == s.length) { "Trailing characters at $i" }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            val obj = JsonValue.Obj()
            i++
            skipWhitespace()
            if (s[i] == '}') {
                i++
                return obj
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(s[i] == ':') { "Expected ':' at $i" }
                i++
                obj.fields[key] = parseValue()
                skipWhitespace()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return obj
                    }
                    else -> error("Expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            val items = mutableListOf<JsonValue>()
            i++
            skipWhitespace()
            if (s[i] == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> error("Expected ',' or ']' at $i")
                }
            }
        }

        private fun parseString(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val out = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'u' -> {
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("Bad escape \\$e at $i")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return JsonValue.Num(s.substring(start, i).toDouble())
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
