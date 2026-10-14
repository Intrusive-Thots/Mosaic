package com.intrusivethots.mosaic.drive

internal class JsonValue(
    val text: String?,
    val fields: Map<String, JsonValue>?,
    val items: List<JsonValue>?
) {
    fun string(name: String): String? = fields?.get(name)?.text?.takeUnless { it == "null" }

    fun obj(name: String): JsonValue? = fields?.get(name)?.takeIf { it.fields != null }

    fun array(name: String): List<JsonValue> = fields?.get(name)?.items.orEmpty()

    fun long(name: String): Long = string(name)?.toLongOrNull() ?: 0L
}

internal fun parseJson(source: String): JsonValue = JsonParser(source).parseValue()

internal fun jsonEscape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

private class JsonParser(private val source: String) {
    private var index = 0

    fun parseValue(): JsonValue {
        skip()
        if (index >= source.length) return JsonValue(null, emptyMap(), null)
        return when (source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue(parseString(), null, null)
            else -> JsonValue(parseLiteral(), null, null)
        }
    }

    private fun parseObject(): JsonValue {
        index++
        val fields = linkedMapOf<String, JsonValue>()
        skip()
        if (peek('}')) {
            index++
            return JsonValue(null, fields, null)
        }
        while (index < source.length) {
            skip()
            val name = parseString()
            skip()
            if (peek(':')) index++
            fields[name] = parseValue()
            skip()
            if (peek('}')) {
                index++
                break
            }
            if (peek(',')) index++
        }
        return JsonValue(null, fields, null)
    }

    private fun parseArray(): JsonValue {
        index++
        val items = ArrayList<JsonValue>()
        skip()
        if (peek(']')) {
            index++
            return JsonValue(null, null, items)
        }
        while (index < source.length) {
            items += parseValue()
            skip()
            if (peek(']')) {
                index++
                break
            }
            if (peek(',')) index++
        }
        return JsonValue(null, null, items)
    }

    private fun parseString(): String {
        if (peek('"')) index++
        val out = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            if (char == '"') break
            if (char != '\\') {
                out.append(char)
                continue
            }
            if (index >= source.length) break
            out.append(escaped(source[index++]))
        }
        return out.toString()
    }

    private fun escaped(char: Char): Char {
        return when (char) {
            '"', '\\', '/' -> char
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicode()
            else -> char
        }
    }

    private fun unicode(): Char {
        val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
        index += hex.length
        return hex.toIntOrNull(16)?.toChar() ?: '?'
    }

    private fun parseLiteral(): String {
        val start = index
        while (index < source.length && source[index] !in ",}] \n\r\t") index++
        return source.substring(start, index)
    }

    private fun skip() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun peek(char: Char): Boolean = index < source.length && source[index] == char
}
