package com.opendictate.app.data

import org.junit.Assert.*
import org.junit.Test

class SettingsSyncDocumentTest {
    @Test fun `retired prompt is dropped without a connection choice`() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "shared/settings-sync-prompts.json").isFile }
        val remote = SettingsSyncDocument.fromJson(java.io.File(root, "shared/settings-sync-prompts.json").readText())
        assertNull(remote.entries["transcriptionPrompt"])
        assertEquals("preserved", remote.entries["future.preference"]?.value)
        val legacy = document("transcriptionPrompt", "Ignored", 500, "old")
        val local = SettingsSyncDocument()
        assertFalse(local.needsConnectionChoice(legacy))
        assertTrue(local.hasSameSettings(legacy))
        SettingsSyncDocument.ConnectionSource.entries.forEach { source ->
            assertNull(local.resolvingConnection(legacy, source, "a", 1).entries["transcriptionPrompt"])
        }
        val edited = legacy.record(mapOf("dictionary" to "Updated", "transcriptionPrompt" to "Ignored"), "a", 1)
        assertNull(edited.entries["transcriptionPrompt"])
        assertEquals("Updated", edited.entries["dictionary"]?.value)
        assertFalse(legacy.toJson().contains("transcriptionPrompt"))
        assertEquals(1, org.json.JSONObject(remote.toJson()).getInt("schemaVersion"))
    }

    @Test fun `null is accepted only for the prompt in version two and never a missing value`() {
        listOf(
            """{"schemaVersion":1,"entries":{"transcriptionPrompt":{"value":null,"modifiedAt":1,"deviceId":"a"}}}""",
            """{"schemaVersion":2,"entries":{"mode":{"value":null,"modifiedAt":1,"deviceId":"a"}}}""",
            """{"schemaVersion":2,"entries":{"transcriptionPrompt":{"modifiedAt":1,"deviceId":"a"}}}"""
        ).forEach { assertThrows(Exception::class.java) { SettingsSyncDocument.fromJson(it) } }
    }

    @Test fun `punctuation is excluded from seeds legacy journals merges and exports`() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "shared/settings-sync-punctuation.json").isFile }
        val remote = SettingsSyncDocument.fromJson(java.io.File(root, "shared/settings-sync-punctuation.json").readText())
        assertNull(remote.entries["accuratePunctuationEnabled"])
        assertEquals("preserved", remote.entries["future.preference"]?.value)
        val seed = SettingsSyncDocument().record(mapOf("accuratePunctuationEnabled" to "true"), "mac", 0, seed = true)
        assertTrue(seed.entries.isEmpty())
        val legacy = SettingsSyncDocument(mapOf("accuratePunctuationEnabled" to SettingsSyncEntry("invalid", 1000, "mac")))
        assertFalse(legacy.toJson().contains("accuratePunctuationEnabled"))
        assertNull(legacy.promoteSeeds("mac", 1).entries["accuratePunctuationEnabled"])
        assertEquals(remote, legacy.merge(remote))
        assertEquals(remote, remote.merge(legacy))
        val edited = legacy.record(mapOf("dictionary" to "Local"), "mac", 50)
        assertNull(edited.entries["accuratePunctuationEnabled"])
        assertEquals(50L, edited.entries["dictionary"]?.modifiedAt)
    }

    @Test fun `shared replacement document imports IDs flags and Unicode and clearing defeats stale replicas`() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "shared/settings-sync-replacements.json").isFile }
        val remote = SettingsSyncDocument.fromJson(java.io.File(root, "shared/settings-sync-replacements.json").readText())
        val seed = SettingsSyncDocument().record(mapOf("wordReplacements" to ReplacementDocument().toJson(),
            "wordReplacementsEnabled" to "true"), "mac", 0, seed = true)
        val merged = seed.merge(remote)
        val rules = ReplacementDocument.fromJson(requireNotNull(merged.entries.getValue("wordReplacements").value)).rules
        assertEquals("00000000-0000-0000-0000-000000000001", rules.first().id)
        assertFalse(rules.last().enabled)
        assertEquals("OpenDictate 📝 cat", WordReplacementEngine(rules).apply("опен диктейт cat"))
        val cleared = merged.record(mapOf("wordReplacements" to ReplacementDocument().toJson(),
            "wordReplacementsEnabled" to "false"), "mac", 50).merge(remote)
        assertTrue(ReplacementDocument.fromJson(requireNotNull(cleared.entries.getValue("wordReplacements").value)).rules.isEmpty())
        assertEquals("false", cleared.entries["wordReplacementsEnabled"]?.value)
        assertEquals("preserved", cleared.entries["future.preference"]?.value)
    }

    @Test fun `replacement rules and master switch merge independently`() {
        val rules = document("wordReplacements", ReplacementDocument().toJson(), 100, "android")
        val enabled = document("wordReplacementsEnabled", "false", 120, "mac")
        assertEquals(rules.merge(enabled), enabled.merge(rules))
        assertEquals("false", rules.merge(enabled).entries["wordReplacementsEnabled"]?.value)
    }
    private fun document(key: String, value: String, time: Long, device: String) =
        SettingsSyncDocument(mapOf(key to SettingsSyncEntry(value, time, device)))

    @Test fun `separate preferences merge and concurrent conflicts converge regardless of order`() {
        val android = document("dictionary", "Android\nНикита", 100, "android")
            .merge(document("textModel", "gpt-6-sol", 120, "android"))
        val mac = document("dictionary", "Mac", 100, "mac")
            .merge(document("mode", "live", 130, "mac"))
        val merged = android.merge(mac)
        assertEquals(merged, mac.merge(android))
        assertEquals(merged, merged.merge(android).merge(mac))
        assertEquals("Mac", merged.entries["dictionary"]?.value)
        assertEquals("gpt-6-sol", merged.entries["textModel"]?.value)
        assertEquals("live", merged.entries["mode"]?.value)
    }

    @Test fun `clearing dictionary survives stale offline replicas`() {
        val old = document("dictionary", "OpenDictate", 1, "mac")
        val cleared = old.record(mapOf("dictionary" to ""), "android", 2)
        assertEquals("", cleared.merge(old).entries["dictionary"]?.value)
    }

    @Test fun `new device defaults yield to cloud and local changes use a monotonic clock`() {
        val seed = SettingsSyncDocument().record(mapOf("mode" to "accurate"), "android", 0, seed = true)
        val cloud = document("mode", "live", 1000, "mac")
        val merged = seed.merge(cloud)
        assertEquals("live", merged.entries["mode"]?.value)
        val changed = merged.record(mapOf("mode" to "accurate"), "android", 10)
        assertEquals(1001L, changed.entries["mode"]?.modifiedAt)
        assertEquals(changed, changed.record(mapOf("mode" to "accurate"), "android", 2000))
        assertTrue(seed.promoteSeeds("android", 100).entries["mode"]!!.modifiedAt > 0)
    }

    @Test fun `wire format round trips unicode preserves future sections and exports only allowlisted values`() {
        val fixture = """{"schemaVersion":1,"entries":{"dictionary":{"value":"Никита\nOpenDictate 📝","modifiedAt":1760000000000,"deviceId":"mac"},"future.color":{"value":"dark","modifiedAt":2,"deviceId":"mac"}}}"""
        val parsed = SettingsSyncDocument.fromJson(fixture)
        assertEquals(parsed, SettingsSyncDocument.fromJson(parsed.toJson()))
        val updated = parsed.record(mapOf("textModel" to "gpt-6-sol", "apiKey" to "private-test-key", "history" to "private-test-history"), "android", 1)
        assertEquals("dark", updated.entries["future.color"]?.value)
        assertFalse(updated.toJson().contains("private-test"))
    }

    @Test fun `future versions malformed entries and oversized data fail closed`() {
        listOf("{\"schemaVersion\":3,\"entries\":{}}", "{\"schemaVersion\":1.5,\"entries\":{}}", "{\"schemaVersion\":\"1\",\"entries\":{}}", "{\"schemaVersion\":1,\"entries\":{\"mode\":{\"value\":false,\"modifiedAt\":1,\"deviceId\":\"a\"}}}",
            "{\"schemaVersion\":1,\"entries\":{\"mode\":{\"value\":\"live\",\"modifiedAt\":-1,\"deviceId\":\"a\"}}}", "x".repeat(1_048_577)).forEach { value ->
            assertThrows(Exception::class.java) { SettingsSyncDocument.fromJson(value) }
        }
    }
    @Test fun connectionChoiceOnlyForDifferentKnownCloudValues() {
        val local = document("dictionary", "Local", 500, "android")
        assertFalse(local.needsConnectionChoice(SettingsSyncDocument()))
        assertFalse(local.needsConnectionChoice(document("future.setting", "unknown", 600, "mac")))
        assertFalse(local.needsConnectionChoice(document("dictionary", "Local", 1, "mac")))
        assertTrue(local.needsConnectionChoice(document("dictionary", "Cloud", 1, "mac")))
        assertTrue(local.needsConnectionChoice(document("dictionary", "", 1, "mac")))
        assertTrue(local.hasSameSettings(document("dictionary", "Local", 1, "mac")))
    }

    @Test fun explicitSourceWinsOverOfflineClocksAndStaleReplicas() {
        val local = document("dictionary", "Local", 500, "android").merge(document("mode", "live", 500, "android"))
        val cloud = document("dictionary", "", 100, "mac").merge(document("mode", "accurate", 900, "mac"))
            .merge(document("future.setting", "preserved", 1000, "future"))
        SettingsSyncDocument.ConnectionSource.entries.forEach { source ->
            val resolved = local.resolvingConnection(cloud, source, "new", 10)
            assertEquals(if (source == SettingsSyncDocument.ConnectionSource.CLOUD) "" else "Local", resolved.entries["dictionary"]?.value)
            assertEquals(if (source == SettingsSyncDocument.ConnectionSource.CLOUD) "accurate" else "live", resolved.entries["mode"]?.value)
            assertEquals(cloud.entries["future.setting"], resolved.entries["future.setting"])
            assertEquals(resolved, resolved.merge(local).merge(cloud))
        }
        val partial = local.resolvingConnection(document("dictionary", "Cloud", 1, "a"),
            SettingsSyncDocument.ConnectionSource.CLOUD, "new", 2)
        assertEquals("live", partial.entries["mode"]?.value)
    }

}
