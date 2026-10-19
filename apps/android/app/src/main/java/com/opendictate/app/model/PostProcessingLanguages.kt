package com.opendictate.app.model

import org.json.JSONObject

/** Frozen shared examples avoid platform spellout fallbacks and locale-dependent requests. */
internal object PostProcessingLanguages {
    private val root by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("postprocessing-languages.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
    }
    private val definitions by lazy {
        root.getJSONArray("languages").let { rows -> (0 until rows.length()).associate { i ->
            rows.getJSONObject(i).let { it.getString("language") to it }
        } }
    }
    val supportedCodes: Set<String> get() = definitions.keys

    private fun render(examples: org.json.JSONArray): String = (0 until examples.length()).joinToString("; ") { i ->
        examples.getJSONObject(i).let { "${jsonQuote(it.getString("spoken"))} = ${it.getString("value")}" }
    }

    fun instruction(languages: Collection<String>): String {
        val blocks = languages.toSortedSet().mapNotNull { code ->
            definitions[code]?.let { definition ->
                "[$code] ${definition.getString("name")}. Scales: " + render(definition.getJSONArray("scales")) +
                    ". Examples: " + render(definition.getJSONArray("examples"))
            }
        }
        return if (blocks.isEmpty()) "" else root.getString("heading") + "\n" + blocks.joinToString("\n")
    }
}
