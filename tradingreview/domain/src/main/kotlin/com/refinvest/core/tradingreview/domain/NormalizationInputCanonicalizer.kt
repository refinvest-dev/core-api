package com.refinvest.core.tradingreview.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** RFC 8785 V1 subset: this contract contains strings, arrays and objects only. */
object NormalizationInputCanonicalizer {
    fun canonicalize(identity: NormalizationInputIdentity): ByteArray =
        buildString { appendValue(identity.toWireValue()) }.toByteArray(StandardCharsets.UTF_8)

    fun hash(identity: NormalizationInputIdentity): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(canonicalize(identity)))

    private fun StringBuilder.appendValue(value: Any) {
        when (value) {
            is String -> appendQuoted(value)
            is Map<*, *> -> {
                append('{')
                value.entries.sortedWith { left, right ->
                    (left.key as String).compareTo(right.key as String) // UTF-16 code units (JCS)
                }.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendValue(entry.value ?: error("Null identity field"))
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item ?: error("Null identity item"))
                }
                append(']')
            }
            else -> error("Unsupported identity value")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> when {
                    char < ' ' -> append("\\u%04x".format(java.util.Locale.ROOT, char.code))
                    Character.isHighSurrogate(char) -> {
                        require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                            "Lone surrogate in identity"
                        }
                        append(char).append(value[++index])
                    }
                    Character.isLowSurrogate(char) -> throw IllegalArgumentException("Lone surrogate in identity")
                    else -> append(char)
                }
            }
            index++
        }
        append('"')
    }
}
