package com.openmanga.engine

/**
 * Small dependency-free JSON reader/writer so the engine runs unchanged on the JVM and on Android.
 * Parsed values: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (p.pos != text.length) p.fail("Unexpected trailing data")
        return v
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int) {
        val next = if (indent < 0) -1 else indent + 1
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v % 1.0 == 0.0 && !v.isInfinite()) v.toLong().toString() else v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, next)
                    quote(sb, k.toString())
                    sb.append(if (indent < 0) ":" else ": ")
                    write(sb, value, next)
                }
                newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in list) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, next)
                    write(sb, item, next)
                }
                newline(sb, indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at position $pos")

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (pos >= s.length) fail("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("Expected string key")
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("Expected ':'")
                pos++
                out[key] = readValue()
                skipWs()
                if (pos >= s.length) fail("Unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (pos >= s.length) fail("Unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            pos++ // opening quote
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("Unknown escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("Bad number '$text'")
        }
    }
}

/** Minimal JSON path: `a.b[0].c`, `a.b[*]`, optional leading `$.`. Returns every match. */
object JsonPath {
    fun select(root: Any?, path: String): List<Any?> {
        var current: List<Any?> = listOf(root)
        for (token in tokenize(path)) {
            val next = ArrayList<Any?>()
            for (node in current) {
                when (token) {
                    "*" -> if (node is List<*>) next.addAll(node) else if (node is Map<*, *>) next.addAll(node.values)
                    else -> {
                        val idx = token.toIntOrNull()
                        if (idx != null && node is List<*>) {
                            val i = if (idx < 0) node.size + idx else idx
                            if (i in node.indices) next.add(node[i])
                        } else if (node is Map<*, *> && node.containsKey(token)) {
                            next.add(node[token])
                        }
                    }
                }
            }
            current = next
            if (current.isEmpty()) break
        }
        return current
    }

    private fun tokenize(path: String): List<String> {
        val p = path.trim().removePrefix("$").removePrefix(".")
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < p.length) {
            when (val c = p[i]) {
                '.' -> { if (cur.isNotEmpty()) out.add(cur.toString()); cur.clear() }
                '[' -> {
                    if (cur.isNotEmpty()) out.add(cur.toString()); cur.clear()
                    val end = p.indexOf(']', i)
                    if (end < 0) { out.add(p.substring(i + 1)); i = p.length; continue }
                    out.add(p.substring(i + 1, end).trim('\'', '"'))
                    i = end
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    fun asText(node: Any?): String? = when (node) {
        null -> null
        is String -> node
        is Double -> if (node % 1.0 == 0.0) node.toLong().toString() else node.toString()
        is Number, is Boolean -> node.toString()
        else -> Json.stringify(node)
    }
}
