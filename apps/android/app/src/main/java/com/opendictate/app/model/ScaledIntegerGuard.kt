package com.opendictate.app.model

import org.json.JSONObject
import java.util.Locale

/** A conservative value check, not a general number formatter. Unsupported phrases are not guessed. */
internal object ScaledIntegerGuard {
    private val vocabulary by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("scaled-integers.json")).bufferedReader()
            .use { JSONObject(it.readText()) }
    }
    private val small by lazy { vocabulary.getJSONObject("small").let { j -> j.keys().asSequence().associateWith { j.getLong(it) } } }
    private val scales by lazy { vocabulary.getJSONObject("scales").let { j -> j.keys().asSequence().associateWith { j.getLong(it) } } }
    private val tokens = Regex("[\\p{L}]+|[\\p{Nd}]+")
    private val gap = Regex("[\\s\\p{Z}-]+")
    private val fractionContext = setOf("point", "comma", "half", "полтора", "полторы", "половиной", "целая", "целое", "целых", "десятых", "сотых")
    private val ordinalContext = Regex("(?:first|second|third|.*(?:th|st|nd|rd)|(?:перв|втор|трет|четв[её]рт|пят|шест|седьм|восьм|девят|десят|одиннадцат|двенадцат|тринадцат|четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат|сороков|пятидесят|шестидесят|семидесят|восьмидесят|девяност|сот|тысячн|миллионн|миллиардн).*)")
    private val durationContext = Regex("(?:hours?|minutes?|seconds?|days?|weeks?|час.*|минут.*|секунд.*|дней|дня|день|недел.*)")
    // Standard and Indian grouping; decimal tails are excluded from this integer-magnitude check.
    private val minusWord = Regex("(?:minus|negative|минус)\\s*$", RegexOption.IGNORE_CASE)
    private val numeric = Regex("(?<![\\p{N}])[-−+]?[ \\p{Zs}]*(?:\\p{Nd}{1,2}(?:,\\p{Nd}{2})+,\\p{Nd}{3}|\\p{Nd}{1,3}(?:[ \\p{Zs},.'’٬_]\\p{Nd}{3})+|\\p{Nd}+)(?![\\p{N}])")

    fun preserves(source: String, candidate: String): Boolean {
        val required = spoken(source)
        if (required.isEmpty()) return true
        val available = (spoken(candidate) + numeric.findAll(candidate).mapNotNull { match ->
            val digits = match.value.filter { Character.isDigit(it) }.map { Character.digit(it, 10) }.joinToString("")
            digits.toLongOrNull()?.let { if (match.value.trimStart().startsWith('-') || match.value.trimStart().startsWith('−') ||
                minusWord.containsMatchIn(candidate.substring(0, match.range.first))) -it else it }
        }.toList()).toMutableList()
        return required.all { available.remove(it) }
    }

    private fun known(word: String) = word in small || word in scales || word == "hundred" || word == "and" || word.toLongOrNull() != null

    private fun spoken(text: String): List<Long> {
        val normalized = text.lowercase(Locale.ROOT)
        val matches = tokens.findAll(normalized).toList()
        val values = mutableListOf<Long>()
        var i = 0
        while (i < matches.size) {
            val start = i
            if (!known(matches[i].value)) { i++; continue }
            while (i + 1 < matches.size && known(matches[i + 1].value) &&
                gap.matches(normalized.substring(matches[i].range.last + 1, matches[i + 1].range.first))) i++
            val end = i++
            val before = matches.getOrNull(start - 1)
            val after = matches.getOrNull(end + 1)
            // Do not extract an integer fragment from a fractional amount or a convertible duration.
            val nearby = matches.subList(maxOf(0, start - 2), minOf(matches.size, end + 3)).map { it.value }
            if (nearby.any { it in fractionContext } || after?.value?.matches(durationContext) == true || after?.value?.matches(ordinalContext) == true) continue
            if (before != null && before.value.first().isDigit() &&
                normalized.substring(before.range.last + 1, matches[start].range.first).matches(Regex("[.,]"))) continue
            if (after != null && after.value.first().isDigit() &&
                normalized.substring(matches[end].range.last + 1, after.range.first).matches(Regex("[.,]"))) continue
            val value = parse(matches.subList(start, end + 1).map { it.value }) ?: continue
            val prefix = normalized.substring(before?.range?.last?.plus(1) ?: 0, matches[start].range.first).trim()
            val negative = before?.value in setOf("minus", "negative", "минус") || prefix == "-" || prefix == "−"
            values += if (negative) -value else value
        }
        return values
    }

    private fun parse(words: List<String>): Long? {
        if (words.none { it in scales }) return null
        var total = 0L
        var previousScale = Long.MAX_VALUE
        var chunk = mutableListOf<String>()
        for (word in words.filter { it != "and" }) {
            val scale = scales[word]
            if (scale == null) chunk += word else {
                val group = group(chunk) ?: return null
                if (group == 0L || scale >= previousScale) return null
                total += group * scale
                previousScale = scale
                chunk = mutableListOf()
            }
        }
        val tail = if (chunk.isEmpty()) 0 else group(chunk) ?: return null
        if (tail >= previousScale) return null
        return total + tail
    }

    private fun group(words: List<String>): Long? {
        if (words.isEmpty()) return null
        if (words.size == 1) words[0].toLongOrNull()?.let { return it.takeIf { n -> n in 0..999 } }
        var value = 0L
        var maximum = 999L
        for ((index, word) in words.withIndex()) {
            if (word == "hundred") {
                if (index != 1 || value !in 1..9) return null
                value *= 100; maximum = 99
            } else {
                val n = small[word] ?: return null
                if (n > maximum) return null
                value += n
                maximum = when { n >= 100 -> 99; n >= 20 && n % 10 == 0L -> 9; else -> -1 }
            }
        }
        return value
    }
}
