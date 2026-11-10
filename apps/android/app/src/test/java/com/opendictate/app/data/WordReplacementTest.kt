package com.opendictate.app.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WordReplacementTest {
    @Test fun sharedPlatformCases() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/word-replacement-cases.json").isFile }
        val cases = JSONArray(File(root, "shared/word-replacement-cases.json").readText())
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val entries = fixture.getJSONArray("rules")
            val rules = (0 until entries.length()).map {
                val rule = entries.getJSONObject(it)
                WordReplacement(source = rule.getString("source"), replacement = rule.getString("replacement"),
                    enabled = rule.optBoolean("enabled", true), regex = rule.optBoolean("regex", false),
                    matchCase = rule.optBoolean("matchCase", false))
            }
            assertEquals(fixture.getString("name"), fixture.getString("expected"),
                WordReplacementEngine(rules).apply(fixture.getString("text"), fixture.optBoolean("final", true)))
        }
    }
    @Test fun sharedPlainTypographyCases() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/plain-typography-cases.json").isFile }
        val cases = JSONArray(File(root, "shared/plain-typography-cases.json").readText())
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            assertEquals(fixture.getString("name"), fixture.getString("expected"), PlainTypography.apply(fixture.getString("text")))
        }
    }
    @Test fun plainTypographyAppliesAfterRulesAndToLivePartials() {
        val engine = WordReplacementEngine(listOf(WordReplacement(source = "ёлка", replacement = "Ёлка — ель")), plainTypography = true)
        assertEquals("Ёлка - ель \"зелёная\"", engine.apply("ёлка «зелёная»"))
        assertEquals("Это -", engine.apply("Это —", final = false))
        assertEquals("Это - ёлка", engine.apply("Это — ёлка", final = false))
        assertEquals("ещё — «да»", WordReplacementEngine(emptyList()).apply("ещё — «да»"))
    }
    @Test fun sessionSnapshotAndCumulativeUpdates() {
        val rule = WordReplacement(source = "cat", replacement = "dog")
        val session = WordReplacementEngine(listOf(rule))
        assertEquals("dog ", session.apply("cat ", final = false))
        assertEquals("dog dog.", session.apply("cat cat.", final = false))
        assertEquals("bird", WordReplacementEngine(listOf(rule.copy(replacement = "bird"))).apply("cat"))
    }
    @Test fun documentRoundTripAndValidation() {
        val rule = WordReplacement(source = "тест", replacement = "Test")
        val document = ReplacementDocument(listOf(rule))
        assertEquals(document, ReplacementDocument.fromJson(document.toJson()))
        assertFalse(ReplacementDocument(listOf(rule, rule)).isValid)
        assertFalse(WordReplacement(source = " ", replacement = "Test").isValid)
        assertFalse(WordReplacement(source = "test", replacement = "").isValid)
        assertFalse(WordReplacement(source = "x".repeat(257), replacement = "Test").isValid)
        listOf("""{"schemaVersion":2,"rules":[]}""", """{"schemaVersion":3,"rules":[]}""", """{"schemaVersion":4,"rules":[]}""").forEach {
            assertThrows(Exception::class.java) { ReplacementDocument.fromJson(it) }
        }
    }
    @Test fun patternRulesUseVersionTwoAndLiteralRulesKeepVersionOneBytes() {
        val id = "00000000-0000-0000-0000-000000000001"
        val literal = ReplacementDocument(listOf(WordReplacement(id, "cat", "dog"))).toJson()
        assertEquals("""{"schemaVersion":1,"rules":[{"id":"$id","source":"cat","replacement":"dog","enabled":true}]}""", literal)
        val pattern = ReplacementDocument(listOf(WordReplacement(id, "c.t", "", regex = true)))
        assertEquals(2, pattern.schemaVersion)
        assertEquals(pattern, ReplacementDocument.fromJson(pattern.toJson()))
        assertThrows(Exception::class.java) { ReplacementDocument.fromJson(pattern.toJson().replace("\"schemaVersion\":2", "\"schemaVersion\":1")) }
        assertThrows(Exception::class.java) { ReplacementDocument.fromJson(pattern.toJson().replace("\"regex\":true", "\"regex\":\"true\"")) }
    }
    @Test fun caseSensitiveRulesUseVersionThree() {
        val id = "00000000-0000-0000-0000-000000000001"
        val document = ReplacementDocument(listOf(WordReplacement(id, "ИП", "предприниматель", matchCase = true)))
        assertEquals(3, document.schemaVersion)
        assertTrue(document.toJson().contains("\"matchCase\":true"))
        assertFalse(document.toJson().contains("regex"))
        assertEquals(document, ReplacementDocument.fromJson(document.toJson()))
        val pattern = ReplacementDocument(listOf(WordReplacement(id, "ё", "е", regex = true, matchCase = true)))
        assertEquals(3, pattern.schemaVersion)
        assertEquals(pattern, ReplacementDocument.fromJson(pattern.toJson()))
        assertThrows(Exception::class.java) { ReplacementDocument.fromJson(pattern.toJson().replace("\"schemaVersion\":3", "\"schemaVersion\":2")) }
        assertThrows(Exception::class.java) { ReplacementDocument.fromJson(pattern.toJson().replace("\"matchCase\":true", "\"matchCase\":1")) }
    }
    @Test fun patternUsability() {
        fun usable(source: String, replacement: String = "x") = WordReplacement(source = source, replacement = replacement, regex = true).isUsable
        assertTrue(usable("(\\d+) руб", "$1 ₽"))
        assertTrue(usable(" +", " "))
        assertTrue(usable("um", ""))
        assertFalse(usable("("))
        assertFalse(usable("a*"))
        assertFalse(usable("(a)", "$2"))
        assertFalse(usable("(a)", "\${2}"))
        assertTrue(usable("(a)", "$$2 \$x"))
        assertFalse(usable(""))
        assertFalse(WordReplacement(source = "cat", replacement = "").isUsable)
    }
    @Test fun malformedWireTypesAndFutureVersionsAreRejected() {
        val document = ReplacementDocument(listOf(WordReplacement(source = "cat", replacement = "dog"))).toJson()
        listOf(document.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
            document.replace("\"enabled\":true", "\"enabled\":\"true\""),
            document.replace("\"source\":\"cat\"", "\"source\":123")).forEach {
            assertThrows(Exception::class.java) { ReplacementDocument.fromJson(it) }
        }
    }
}
