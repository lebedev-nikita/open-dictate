package com.opendictate.app.model

import org.json.JSONArray
import org.json.JSONObject

/** Recognition choices only; text choices are defined by TextProcessingModel. */
data class ModelCatalog(
    val live: List<String>,
    val accurate: List<String>,
) {
    fun toCachedJson(): String = JSONObject()
        .put("live", JSONArray(live))
        .put("accurate", JSONArray(accurate))
        .toString()

    companion object {
        val DEFAULT = ModelCatalog(
            live = listOf("gpt-live-transcribe"),
            accurate = listOf("gpt-transcribe"),
        )

        fun fromModelsResponse(body: String): ModelCatalog {
            val data = JSONObject(body).getJSONArray("data")
            val models = (0 until data.length()).map { data.getJSONObject(it) }
            val ids = models.map { it.getString("id") }.toSet()
            return ModelCatalog(
                live = listOfNotNull(DEFAULT.live.first().takeIf(ids::contains)),
                accurate = listOfNotNull(DEFAULT.accurate.first().takeIf(ids::contains)),
            )
        }

        fun fromCachedJson(value: String?): ModelCatalog {
            if (value.isNullOrBlank()) return ModelCatalog(emptyList(), emptyList())
            return runCatching {
                val cached = JSONObject(value)
                ModelCatalog(
                    live = cached.getJSONArray("live").strings().filter { it == DEFAULT.live.first() },
                    accurate = cached.getJSONArray("accurate").strings().filter { it == DEFAULT.accurate.first() },
                )
            }.getOrElse { ModelCatalog(emptyList(), emptyList()) }
        }
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

fun preferredModelId(selected: String, available: List<String>, defaults: List<String>): String {
    val options = available.ifEmpty { defaults }
    if (selected in options) return selected
    val role = selected.substringAfterLast('-', missingDelimiterValue = "")
    return options.firstOrNull { it.endsWith("-$role") } ?: options.first()
}
