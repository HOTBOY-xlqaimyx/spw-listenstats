/*
 * SPW 听歌统计插件
 * 本插件对 SPW Workshop API（Apache-2.0, Moriafly）仅有编译期依赖。
 */

package com.keyqiang.spw.listenstats

/**
 * 极简 JSON 值模型。
 *
 * 本插件刻意保持**零第三方运行时依赖**（除 kotlin-stdlib），因此自带一个
 * 最小可用的 JSON 编解码器，只覆盖本插件自己写出的数据格式。
 */
internal sealed interface Jv {
    data object Null : Jv
    data class B(val value: Boolean) : Jv
    data class N(val raw: String) : Jv
    data class S(val value: String) : Jv
    data class A(val items: MutableList<Jv> = mutableListOf()) : Jv
    data class O(val fields: LinkedHashMap<String, Jv> = LinkedHashMap()) : Jv
}

internal fun jStr(value: String?): Jv = if (value == null) Jv.Null else Jv.S(value)

internal fun jNum(value: Long): Jv = Jv.N(value.toString())

internal fun jNum(value: Int): Jv = Jv.N(value.toString())

internal fun jBool(value: Boolean): Jv = Jv.B(value)

internal fun jObj(vararg pairs: Pair<String, Jv>): Jv.O {
    val o = Jv.O()
    for ((k, v) in pairs) o.fields[k] = v
    return o
}

internal fun jArr(items: List<Jv>): Jv.A = Jv.A(items.toMutableList())

internal operator fun Jv.get(key: String): Jv? = (this as? Jv.O)?.fields?.get(key)

internal fun Jv.asObjectOrNull(): Jv.O? = this as? Jv.O

internal fun Jv?.asArrayOrEmpty(): List<Jv> = (this as? Jv.A)?.items ?: emptyList()

// 取值器一律挂在可空接收者上，读取外部文件时更省事
internal fun Jv?.asString(default: String = ""): String = (this as? Jv.S)?.value ?: default

internal fun Jv?.asLong(default: Long = 0L): Long = when (this) {
    is Jv.N -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: default
    is Jv.S -> value.toLongOrNull() ?: default
    else -> default
}

internal fun Jv?.asBool(default: Boolean = false): Boolean = (this as? Jv.B)?.value ?: default

internal fun Jv.encode(): String {
    val sb = StringBuilder(512)
    writeTo(sb)
    return sb.toString()
}

private fun Jv.writeTo(sb: StringBuilder) {
    when (this) {
        is Jv.Null -> sb.append("null")
        is Jv.B -> sb.append(if (value) "true" else "false")
        is Jv.N -> sb.append(raw)
        is Jv.S -> writeJsonString(sb, value)
        is Jv.A -> {
            sb.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) sb.append(',')
                item.writeTo(sb)
            }
            sb.append(']')
        }
        is Jv.O -> {
            sb.append('{')
            var first = true
            for ((key, value) in fields) {
                if (!first) sb.append(',')
                first = false
                writeJsonString(sb, key)
                sb.append(':')
                value.writeTo(sb)
            }
            sb.append('}')
        }
    }
}

internal fun writeJsonString(sb: StringBuilder, value: String) {
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}

/**
 * 递归下降 JSON 解析器：只支持标准 JSON，不做宽容扩展。
 */
internal object JsonParser {

    fun parse(text: String): Jv {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.value()
        cursor.skipWhitespace()
        if (!cursor.eof()) {
            throw IllegalArgumentException("JSON 解析失败：偏移 ${cursor.pos} 之后仍有内容")
        }
        return value
    }

    private class Cursor(private val text: String) {
        var pos = 0

        fun eof(): Boolean = pos >= text.length

        fun peek(): Char {
            if (eof()) throw IllegalArgumentException("JSON 意外结束（偏移 $pos）")
            return text[pos]
        }

        fun next(): Char {
            if (eof()) throw IllegalArgumentException("JSON 意外结束（偏移 $pos）")
            return text[pos++]
        }

        fun skipWhitespace() {
            while (!eof() && text[pos].isWhitespace()) pos++
        }

        fun expect(expected: Char) {
            val actual = next()
            if (actual != expected) {
                throw IllegalArgumentException("JSON 期望 '$expected'，实际 '$actual'（偏移 ${pos - 1}）")
            }
        }

        fun value(): Jv {
            skipWhitespace()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Jv.S(string())
                't' -> {
                    literal("true")
                    Jv.B(true)
                }
                'f' -> {
                    literal("false")
                    Jv.B(false)
                }
                'n' -> {
                    literal("null")
                    Jv.Null
                }
                else -> number()
            }
        }

        fun obj(): Jv.O {
            expect('{')
            skipWhitespace()
            val result = Jv.O()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result.fields[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> break
                    else -> throw IllegalArgumentException("JSON 对象分隔符错误（偏移 ${pos - 1}）")
                }
            }
            return result
        }

        fun arr(): Jv.A {
            expect('[')
            skipWhitespace()
            val result = Jv.A()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.items.add(value())
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> break
                    else -> throw IllegalArgumentException("JSON 数组分隔符错误（偏移 ${pos - 1}）")
                }
            }
            return result
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val escape = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw IllegalArgumentException("JSON \\u 转义不完整（偏移 $pos）")
                                }
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("JSON 非法转义 \\$escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Jv.N {
            val start = pos
            if (peek() == '-') pos++
            while (!eof()) {
                val c = text[pos]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            if (pos == start) throw IllegalArgumentException("JSON 非法数值（偏移 $start）")
            return Jv.N(text.substring(start, pos))
        }

        fun literal(literal: String) {
            if (!text.startsWith(literal, pos)) {
                throw IllegalArgumentException("JSON 非法字面量（偏移 $pos）")
            }
            pos += literal.length
        }
    }
}
