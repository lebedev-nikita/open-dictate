package com.opendictate.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID
import java.util.regex.MatchResult
import java.util.regex.Pattern

data class WordReplacement(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val replacement: String,
    val enabled: Boolean = true,
    /** Pattern rules match as written and may reference groups with `$1` or `${12}`. */
    val regex: Boolean = false,
) {
    /** Structural validity only. Patterns compile per platform, so synced rules never invalidate a document. */
    val isValid: Boolean get() = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
        source.length <= 256 && replacement.length <= 2048 &&
        if (regex) source.isNotEmpty() else source.isNotBlank() && replacement.isNotBlank()

    /** Checked when a rule is saved: the pattern compiles, cannot match empty text and references existing groups. */
    val isUsable: Boolean get() {
        if (!isValid) return false
        if (!regex) return true
        val pattern = WordReplacementEngine.compile(source) ?: return false
        val groups = pattern.matcher("").groupCount()
        return !pattern.matcher("").find() && WordReplacementEngine.groupReferences(replacement).all { it <= groups }
    }
}

data class ReplacementDocument(val rules: List<WordReplacement> = emptyList()) {
    /** Version 2 marks documents with pattern rules, so older clients reject rather than misread them. */
    val schemaVersion: Int get() = if (rules.any { it.regex }) 2 else 1
    val isValid: Boolean get() = rules.size <= 500 && rules.all { it.isValid } && rules.map { it.id }.toSet().size == rules.size
    fun toJson(): String = JSONObject().put("schemaVersion", schemaVersion).put("rules", JSONArray().apply {
        rules.forEach { rule -> put(JSONObject().put("id", rule.id).put("source", rule.source).put("replacement", rule.replacement)
            .put("enabled", rule.enabled).apply { if (rule.regex) put("regex", true) }) }
    }).toString()
    companion object {
        fun fromJson(json: String): ReplacementDocument {
            val value = JSONObject(json)
            val version = value.get("schemaVersion")
            require(version is Number && version.toDouble() in listOf(1.0, 2.0))
            val entries = value.getJSONArray("rules")
            require(entries.length() <= 500)
            return ReplacementDocument((0 until entries.length()).map { index ->
                val rule = entries.getJSONObject(index)
                require(listOf("id", "source", "replacement").all { rule.get(it) is String } && rule.get("enabled") is Boolean)
                require(!rule.has("regex") || rule.get("regex") is Boolean)
                WordReplacement(rule.getString("id"), rule.getString("source"), rule.getString("replacement"),
                    rule.getBoolean("enabled"), rule.optBoolean("regex", false))
            }).also { require(it.isValid && version.toDouble() == it.schemaVersion.toDouble()) }
        }
    }
}

/** Compiled once per session. Matches the original transcript once; replacements never cascade. */
class WordReplacementEngine(rules: List<WordReplacement>) {
    private class Matcher(val pattern: Pattern, val rules: List<WordReplacement>, val literal: Boolean)
    private val matchers: List<Matcher>

    init {
        val active = rules.filter { it.isValid && it.enabled }.sortedWith(
            compareByDescending<WordReplacement> { it.source.length }.thenBy { it.id },
        )
        val literals = active.filterNot { it.regex }
        val literal = literals.takeIf { it.isNotEmpty() }?.joinToString("|") {
            "(${Pattern.quote(Normalizer.normalize(it.source, Normalizer.Form.NFC))})"
        }?.let { Matcher(Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_])(?:$it)(?![\\p{L}\\p{M}\\p{N}_])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE), literals, true) }
        // A pattern this platform cannot compile is skipped instead of disabling other rules.
        matchers = listOfNotNull(literal) + active.filter { it.regex }.mapNotNull { rule -> compile(rule.source)?.let { Matcher(it, listOf(rule), false) } }
    }

    fun apply(text: String, final: Boolean = true): String {
        if (matchers.isEmpty()) return text
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Each rule set is enumerated once; empty matches never replace text.
        val candidates = matchers.map { matcher ->
            val match = matcher.pattern.matcher(normalized)
            buildList { while (match.find()) if (match.end() > match.start()) add(match.toMatchResult()) }
        }
        val next = IntArray(matchers.size)
        val result = StringBuilder()
        var cursor = 0
        while (true) {
            var bestIndex = -1
            var best: MatchResult? = null
            for (index in matchers.indices) {
                while (next[index] < candidates[index].size && candidates[index][next[index]].start() < cursor) next[index]++
                val match = candidates[index].getOrNull(next[index]) ?: continue
                // Earliest match wins, then the longer one, then literal rules and rule order.
                val current = best
                if (current != null && (match.start() > current.start() ||
                        (match.start() == current.start() && match.end() <= current.end()))) continue
                bestIndex = index; best = match
            }
            // A live trailing token may still grow (e.g. cat → catalog).
            if (best == null || (!final && best.end() == normalized.length)) break
            val matcher = matchers[bestIndex]
            result.append(normalized, cursor, best.start())
            if (matcher.literal) result.append(matcher.rules[matcher.rules.indices.first { best.group(it + 1) != null }].replacement)
            else expand(matcher.rules.single().replacement, best, result)
            cursor = best.end()
        }
        return result.append(normalized, cursor, normalized.length).toString()
    }

    private sealed interface Token {
        data class Text(val value: String) : Token
        data class Group(val number: Int) : Token
    }

    companion object {
        // Android's ICU regex is always Unicode-aware and rejects this flag; desktop Java needs it for \w and \b.
        private val unicodeClasses = runCatching { Pattern.compile("a", Pattern.UNICODE_CHARACTER_CLASS) }.isSuccess

        internal fun compile(pattern: String): Pattern? = runCatching {
            Pattern.compile(Normalizer.normalize(pattern, Normalizer.Form.NFC),
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or if (unicodeClasses) Pattern.UNICODE_CHARACTER_CLASS else 0)
        }.getOrNull()

        internal fun groupReferences(template: String): List<Int> = tokens(template).mapNotNull { (it as? Token.Group)?.number }

        /** `$n` and `${n}` insert a group, `$$` inserts `$`; any other `$` is literal. */
        private fun expand(template: String, match: MatchResult, result: StringBuilder) {
            tokens(template).forEach { token ->
                when (token) {
                    is Token.Text -> result.append(token.value)
                    is Token.Group -> if (token.number <= match.groupCount()) match.group(token.number)?.let(result::append)
                }
            }
        }

        private fun tokens(template: String): List<Token> {
            val tokens = mutableListOf<Token>()
            val text = StringBuilder()
            fun group(number: Int) { tokens += Token.Text(text.toString()); text.clear(); tokens += Token.Group(number) }
            var index = 0
            while (index < template.length) {
                val character = template[index]
                val next = template.getOrNull(index + 1)
                when {
                    character != '$' || next == null -> { text.append(character); index++ }
                    next == '$' -> { text.append('$'); index += 2 }
                    next in '0'..'9' -> { group(next - '0'); index += 2 }
                    next == '{' -> {
                        val close = template.indexOf('}', index + 2)
                        val digits = if (close > index + 2) template.substring(index + 2, close) else ""
                        if (digits.length in 1..3 && digits.all { it in '0'..'9' }) { group(digits.toInt()); index = close + 1 }
                        else { text.append(character); index++ }
                    }
                    else -> { text.append(character); index++ }
                }
            }
            tokens += Token.Text(text.toString())
            return tokens
        }
    }
}
