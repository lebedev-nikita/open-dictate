package com.opendictate.app.data

private val LEGACY_COMMA_SEPARATOR = Regex("[ \\t]*,[ \\t]*")
private val SELECTION_WHITESPACE = Regex("\\s+")

internal const val MAX_DICTIONARY_LENGTH = 300

internal sealed interface DictionaryAddition {
    data class Added(val terms: String) : DictionaryAddition
    data object Empty : DictionaryAddition
    data object Duplicate : DictionaryAddition
    data object Full : DictionaryAddition
}

internal fun normalizeDictionaryTerms(value: String): String =
    value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(LEGACY_COMMA_SEPARATOR, "\n")

internal fun transcriptionKeywords(value: String): List<String> =
    normalizeDictionaryTerms(value).lineSequence()
        .map { it.replace("<", "").replace(">", "").trim() }
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase() }
        .toList()

internal fun addDictionaryTerm(existing: String, selection: String): DictionaryAddition {
    val term = selection.trim().replace(SELECTION_WHITESPACE, " ")
    if (term.isEmpty()) return DictionaryAddition.Empty

    val terms = normalizeDictionaryTerms(existing).trim()
    if (terms.lineSequence().any { it.trim().equals(term, ignoreCase = true) }) {
        return DictionaryAddition.Duplicate
    }

    val updated = if (terms.isEmpty()) term else "$terms\n$term"
    if (updated.length > MAX_DICTIONARY_LENGTH) return DictionaryAddition.Full
    return DictionaryAddition.Added(updated)
}
