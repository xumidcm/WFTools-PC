package com.ailife.wftools.agent.provider

/** Minimal dependency-free JSON tree used by the protocol layer on plain JVM tests and Android. */
sealed interface AgentJsonValue {
    data class Obj(val fields: Map<String, AgentJsonValue>) : AgentJsonValue {
        fun value(name: String): AgentJsonValue? = fields[name]
        fun string(name: String): String? = (fields[name] as? Str)?.value
        fun obj(name: String): Obj? = fields[name] as? Obj
        fun array(name: String): Arr? = fields[name] as? Arr
        fun bool(name: String): Boolean? = (fields[name] as? Bool)?.value
        fun long(name: String): Long? = when (val value = fields[name]) {
            is Num -> value.value.toLong()
            is Str -> value.value.toLongOrNull()
            else -> null
        }
    }

    data class Arr(val items: List<AgentJsonValue>) : AgentJsonValue
    data class Str(val value: String) : AgentJsonValue
    data class Num(val value: Double) : AgentJsonValue
    data class Bool(val value: Boolean) : AgentJsonValue
    data object Null : AgentJsonValue
}

object AgentJsonParser {
    fun parse(input: String): AgentJsonValue? = runCatching { Parser(input).parse() }.getOrNull()

    private class Parser(private val input: String) {
        private var index = 0

        fun parse(): AgentJsonValue {
            val value = value()
            whitespace()
            check(index == input.length) { "trailing JSON" }
            return value
        }

        private fun value(): AgentJsonValue {
            whitespace()
            check(index < input.length) { "missing JSON value" }
            return when (input[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> AgentJsonValue.Str(string())
                't' -> literal("true", AgentJsonValue.Bool(true))
                'f' -> literal("false", AgentJsonValue.Bool(false))
                'n' -> literal("null", AgentJsonValue.Null)
                '-', in '0'..'9' -> number()
                else -> error("invalid JSON value")
            }
        }

        private fun objectValue(): AgentJsonValue.Obj {
            index++
            whitespace()
            val fields = linkedMapOf<String, AgentJsonValue>()
            if (take('}')) return AgentJsonValue.Obj(fields)
            while (true) {
                whitespace()
                check(peek() == '"') { "object key must be string" }
                val key = string()
                whitespace()
                check(take(':')) { "missing object colon" }
                fields[key] = value()
                whitespace()
                if (take('}')) return AgentJsonValue.Obj(fields)
                check(take(',')) { "missing object comma" }
            }
        }

        private fun arrayValue(): AgentJsonValue.Arr {
            index++
            whitespace()
            val items = mutableListOf<AgentJsonValue>()
            if (take(']')) return AgentJsonValue.Arr(items)
            while (true) {
                items += value()
                whitespace()
                if (take(']')) return AgentJsonValue.Arr(items)
                check(take(',')) { "missing array comma" }
            }
        }

        private fun string(): String {
            check(take('"')) { "missing string quote" }
            val output = StringBuilder()
            while (index < input.length) {
                when (val character = input[index++]) {
                    '"' -> return output.toString()
                    '\\' -> {
                        check(index < input.length) { "missing escape" }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000c')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                check(index + 4 <= input.length) { "invalid unicode escape" }
                                output.append(input.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("invalid string escape")
                        }
                    }
                    else -> {
                        check(character >= ' ') { "control character in string" }
                        output.append(character)
                    }
                }
            }
            error("unterminated string")
        }

        private fun number(): AgentJsonValue.Num {
            val start = index
            if (take('-')) Unit
            while (peek()?.isDigit() == true) index++
            if (take('.')) while (peek()?.isDigit() == true) index++
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                while (peek()?.isDigit() == true) index++
            }
            return AgentJsonValue.Num(input.substring(start, index).toDouble())
        }

        private fun literal(expected: String, value: AgentJsonValue): AgentJsonValue {
            check(input.regionMatches(index, expected, 0, expected.length)) { "invalid literal" }
            index += expected.length
            return value
        }

        private fun whitespace() { while (peek()?.isWhitespace() == true) index++ }
        private fun peek(): Char? = input.getOrNull(index)
        private fun take(character: Char): Boolean = if (peek() == character) { index++; true } else false
    }
}

object AgentJsonWriter {
    fun objectValue(fields: Map<String, Any?>): String = encodeObject(fields)

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is AgentJsonValue -> encodeTree(value)
        is Map<*, *> -> encodeObject(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::encode)
        else -> quote(value.toString())
    }

    private fun encodeObject(fields: Map<String, Any?>): String = fields.entries
        .filter { it.value != null }
        .joinToString(prefix = "{", postfix = "}") { "${quote(it.key)}:${encode(it.value)}" }

    private fun encodeTree(value: AgentJsonValue): String = when (value) {
        is AgentJsonValue.Obj -> encodeObject(value.fields)
        is AgentJsonValue.Arr -> value.items.joinToString(prefix = "[", postfix = "]", transform = ::encodeTree)
        is AgentJsonValue.Str -> quote(value.value)
        is AgentJsonValue.Num -> value.value.toString()
        is AgentJsonValue.Bool -> value.value.toString()
        AgentJsonValue.Null -> "null"
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}


