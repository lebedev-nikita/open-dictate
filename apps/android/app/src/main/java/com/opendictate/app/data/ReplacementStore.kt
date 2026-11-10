package com.opendictate.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReplacementState(
    val document: ReplacementDocument = ReplacementDocument(),
    val enabled: Boolean = true,
    /** Independent of [enabled]: it changes characters, not words. */
    val plainTypography: Boolean = false,
    val storageError: Boolean = false,
)

enum class ReplacementError { Invalid, Pattern, Storage }

class ReplacementStore(context: Context, preferencesName: String = "word_replacements") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    var onChange: (() -> Unit)? = null

    fun engine(): WordReplacementEngine = state.value.let {
        WordReplacementEngine(if (it.enabled) it.document.rules else emptyList(), it.plainTypography)
    }
    /** Returns null when saved, otherwise the reason the rule was rejected. */
    fun save(id: String?, source: String, replacement: String, regex: Boolean = false, matchCase: Boolean = false): ReplacementError? {
        // Pattern whitespace is significant; an empty pattern replacement deletes the match.
        val source = java.text.Normalizer.normalize(if (regex) source else source.trim(), java.text.Normalizer.Form.NFC)
        val replacement = if (regex) replacement else replacement.trim()
        val current = state.value
        if (current.storageError) return ReplacementError.Storage
        // Phrases differing only in case can coexist when both match case.
        if (current.document.rules.any { it.id != id && it.regex == regex &&
                it.source.equals(source, ignoreCase = !regex && !(it.matchCase && matchCase)) }) return ReplacementError.Invalid
        val previous = current.document.rules.firstOrNull { it.id == id }
        val rule = WordReplacement(id ?: java.util.UUID.randomUUID().toString(), source, replacement, previous?.enabled ?: true, regex, matchCase)
        val next = ReplacementDocument(current.document.rules.filterNot { it.id == rule.id } + rule)
        return when {
            !next.isValid -> ReplacementError.Invalid
            !rule.isUsable -> ReplacementError.Pattern
            !persist(next) -> ReplacementError.Storage
            else -> null
        }
    }
    fun setEnabled(enabled: Boolean) {
        if (prefs.edit().putBoolean("enabled", enabled).commit()) {
            mutableState.value = state.value.copy(enabled = enabled)
            onChange?.invoke()
        }
        else mutableState.value = state.value.copy(storageError = true)
    }
    fun setPlainTypography(enabled: Boolean) {
        if (prefs.edit().putBoolean("plain_typography", enabled).commit()) {
            mutableState.value = state.value.copy(plainTypography = enabled)
            onChange?.invoke()
        }
        else mutableState.value = state.value.copy(storageError = true)
    }
    fun toggle(rule: WordReplacement, enabled: Boolean) {
        if (!state.value.storageError) persist(ReplacementDocument(state.value.document.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }))
    }
    fun delete(rule: WordReplacement) {
        if (!state.value.storageError) persist(ReplacementDocument(state.value.document.rules.filterNot { it.id == rule.id }))
    }
    private fun persist(document: ReplacementDocument): Boolean {
        val saved = prefs.edit().putString("document", document.toJson()).commit()
        mutableState.value = if (saved) state.value.copy(document = document) else state.value.copy(storageError = true)
        if (saved) onChange?.invoke()
        return saved
    }
    /** Keep the incoming JSON verbatim so platform-specific encoding never creates a new edit. */
    fun applySync(json: String?, enabled: Boolean?, plainTypography: Boolean? = null) {
        require(!state.value.storageError)
        val document = json?.let(ReplacementDocument::fromJson) ?: state.value.document
        val nextEnabled = enabled ?: state.value.enabled
        val nextPlainTypography = plainTypography ?: state.value.plainTypography
        if (!prefs.edit().apply {
                if (json != null) putString("document", json)
                putBoolean("enabled", nextEnabled)
                putBoolean("plain_typography", nextPlainTypography)
            }.commit()) {
            mutableState.value = state.value.copy(storageError = true)
            throw IllegalStateException("Could not save synchronized replacements")
        }
        mutableState.value = ReplacementState(document, nextEnabled, nextPlainTypography)
    }
    private fun load(): ReplacementState {
        val enabled = prefs.getBoolean("enabled", true)
        val plainTypography = prefs.getBoolean("plain_typography", false)
        val saved = prefs.getString("document", null) ?: return ReplacementState(enabled = enabled, plainTypography = plainTypography)
        return runCatching { ReplacementState(ReplacementDocument.fromJson(saved), enabled, plainTypography) }
            .getOrElse { ReplacementState(enabled = enabled, plainTypography = plainTypography, storageError = true) }
    }
}
