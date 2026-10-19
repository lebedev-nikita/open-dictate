package com.opendictate.app.model

import org.json.JSONObject

object TranscriptionPrompt {
    const val MAXIMUM_BYTES = 16_384
    private val defaults: JSONObject by lazy {
        val stream = requireNotNull(TranscriptionPrompt::class.java.getResourceAsStream("/default-prompts.json"))
        stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    fun defaultText(@Suppress("UNUSED_PARAMETER") languages: Set<DictationLanguage>): String = defaults.getString("prompt")
    fun isValid(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_BYTES
}
