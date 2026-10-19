package com.opendictate.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class ValueFormatRulesTest {
    private fun fixture(name: String) = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "shared/$name").isFile }.resolve("shared/$name").readText()

    // CLDR releases differ in the spacing of native defaults (for example, 2:05 PM).
    // User-defined examples and every other part of the shared prompt stay exact.
    private fun normalizeAutomaticSpacing(prompt: String, formats: ValueFormatting): String =
        prompt.split("\n\n").joinToString("\n\n") { paragraph ->
            if (!paragraph.startsWith("[{\"example\":")) paragraph else {
                val styles = JSONArray(paragraph)
                for (index in 0 until styles.length()) {
                    val style = styles.getJSONObject(index)
                    val kind = style.getString("type")
                    val language = style.getString("language")
                    val custom = formats.rules.any { it.kind.id == kind && (it.language == null || it.language == language) }
                    if (language != "*" && !custom) {
                        style.put("example", style.getString("example").replace('\u00a0', ' ').replace('\u202f', ' '))
                    }
                }
                styles.toString()
            }
        }
    @Test fun sharedEncodingScopesPrecedenceAndDisplayOrder() {
        val root = JSONObject(fixture("value-format-rules.json")); val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i); val encoded = row.getString("encoded")
            val settings = PostProcessingSettings.fromJson(encoded)
            assertEquals(encoded, settings.toJson())
            val languages = row.getJSONArray("languages").let { a -> List(a.length()) { a.getString(it) } }
            val prompt = settings.prompt(languages)
            if (row.has("prompt")) assertEquals(row.getString("name"),
                normalizeAutomaticSpacing(row.getString("prompt"), settings.formats!!),
                normalizeAutomaticSpacing(prompt, settings.formats))
            if (row.getString("name") == "ru_en") {
                val order = root.getJSONArray("orderEnglish").let { a -> List(a.length()) { a.getString(it) } }
                assertEquals(order, settings.formats!!.sortedRules(false).map { it.id })
                assertTrue(prompt.contains("\"language\":\"ru\",\"reference\":\"2026-03-05\"")); assertFalse(prompt.contains("\"language\":\"de\""))
            }
            if (row.getString("name") == "global_beats_automatic") {
                assertFalse(prompt.contains("\"language\":\"en\",\"reference\":\"2026-03-05\""))
                assertTrue(prompt.contains("\"example\":\"2026-03-05\",\"language\":\"*\",\"reference\":\"2026-03-05\""))
            }
        }
        val invalid = root.getJSONArray("invalid")
        for (i in 0 until invalid.length()) assertTrue("invalid case $i", runCatching { PostProcessingSettings.fromJson(invalid.getString(i)) }.isFailure)
    }
    @Test fun editDeleteAndLegacyMigration() {
        var formats = ValueFormatting.systemDefaults(Locale.US)
        assertTrue(formats.rules.isEmpty())
        formats = formats.settingRule(ValueFormatRule(ValueKind.DATE, example = "2026-03-05"))
        formats = formats.settingRule(ValueFormatRule(ValueKind.DATE, "ru", "05.03.2026"), "date:*")
        assertEquals(listOf("date:ru"), formats.rules.map { it.id })
        formats = formats.removingRule("date:ru")
        assertTrue(formats.rules.isEmpty()); assertTrue(formats.isValid)
        assertTrue(PostProcessingSettings(true, formatValues = true, formats = formats).prompt(listOf("en")).contains("\"language\":\"en\""))
        assertEquals("5 марта 2026 года", ValueKind.DATE.source(true))
        assertEquals("March 5, 2026", ValueKind.DATE.source(false))
        val old = JSONObject(fixture("postprocessing-settings.json")).getJSONArray("cases").getJSONObject(2).getString("encoded")
        val decoded = PostProcessingSettings.fromJson(old)
        assertNull(decoded.formats!!.customRules); assertEquals(10, decoded.formats.rules.size); assertEquals(old, decoded.toJson())
    }
}
