package com.opendictate.app.data

import java.text.Normalizer

/**
 * Replaces typographic characters that rarely appear in typed messages with their keyboard equivalents.
 * Letters are never changed; `ё` can be replaced with a case-sensitive pattern rule.
 */
object PlainTypography {
    private val dash = Regex("[ \\t\\u00A0]*[\\u2013\\u2014]+[ \\t\\u00A0]*")
    private val characters = mapOf(
        '«' to "\"", '»' to "\"", '“' to "\"", '”' to "\"", '„' to "\"",
        '‘' to "'", '’' to "'", '…' to "...",
    )

    fun apply(text: String): String {
        val value = Normalizer.normalize(text, Normalizer.Form.NFC)
        fun isDigit(char: Char?) = char != null && char in '0'..'9'
        fun isEdge(char: Char?) = char == null || char == '\n' || char == '\r'
        val dashed = dash.replace(value) { match ->
            val before = value.getOrNull(match.range.first - 1)
            val after = value.getOrNull(match.range.last + 1)
            // Unspaced number ranges stay compact; other dashes become a spaced hyphen.
            val spaced = match.value.any { it == ' ' || it == '\t' || it == ' ' }
            if (!spaced && isDigit(before) && isDigit(after)) "-"
            else (if (isEdge(before)) "" else " ") + "-" + (if (isEdge(after)) "" else " ")
        }
        return buildString { dashed.forEach { append(characters[it] ?: it) } }
    }
}
