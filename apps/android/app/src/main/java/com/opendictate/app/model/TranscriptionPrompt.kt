package com.opendictate.app.model

import org.json.JSONObject

object TranscriptionPrompt {
    const val MAXIMUM_BYTES = 16_384
    private val defaults: JSONObject by lazy {
        val stream = requireNotNull(TranscriptionPrompt::class.java.getResourceAsStream("/default-prompts.json"))
        stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    fun defaultText(languages: Set<DictationLanguage>): String = buildList {
        add(defaults.getString("base"))
        if (DictationLanguage.RUSSIAN in languages && DictationLanguage.UKRAINIAN !in languages) {
            add(defaults.getString("russianOrthography"))
        }
        if (DictationLanguage.RUSSIAN in languages) add(defaults.getString("russianPunctuation"))
    }.joinToString("\n")
    fun isValid(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_BYTES
}
