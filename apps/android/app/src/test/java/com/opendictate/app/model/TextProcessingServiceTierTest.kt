package com.opendictate.app.model

import com.opendictate.app.data.SettingsSyncDocument
import com.opendictate.app.network.postProcessingRequest
import com.opendictate.app.network.textTransformationRequest
import com.opendictate.app.network.transcriptHistorySearchRequest
import org.junit.Assert.*
import org.junit.Test

class TextProcessingServiceTierTest {
    @Test fun `both choices use the real model ID with the selected service tier`() {
        assertEquals(listOf("GPT-6 Luna", "GPT-6 Luna Fast"), TextProcessingModel.defaults.map(TextProcessingModel::title))
        TextProcessingModel.defaults.forEach { choice ->
            val model = TextProcessingModel.model(choice)
            val tier = TextProcessingModel.tier(choice)
            assertEquals("gpt-6-luna", model)
            assertEquals(choice, TextProcessingModel.selection(model, tier))
            listOf(textTransformationRequest("Draft", "Shorten", model, tier),
                postProcessingRequest("Draft", PostProcessingSettings(enabled = true), model, serviceTier = tier)).forEach {
                assertEquals("gpt-6-luna", it.getString("model"))
                assertEquals(tier.apiValue, it.getString("service_tier"))
                assertFalse(it.getBoolean("store"))
            }
        }
    }

    @Test fun `text operations default to fast and preserve postprocessing reasoning and history search`() {
        assertEquals("fast", textTransformationRequest("Draft", "Shorten").getString("service_tier"))
        val post = postProcessingRequest("Draft", PostProcessingSettings(enabled = true))
        assertEquals("fast", post.getString("service_tier"))
        assertEquals("none", post.getJSONObject("reasoning").getString("effort"))
        assertFalse(transcriptHistorySearchRequest("Draft", emptyList()).has("service_tier"))
    }

    @Test fun `tier sync survives an older replica without changing the model`() {
        val old = SettingsSyncDocument().record(mapOf("textProcessingModel" to "gpt-6-luna"), "old", 100)
        val local = old.record(mapOf("textProcessingServiceTier" to "default"), "mac", 200)
        val remote = old.record(mapOf("dictionary" to "Updated"), "android", 300)
        val merged = local.merge(remote)
        assertEquals(merged, remote.merge(local))
        val decoded = SettingsSyncDocument.fromJson(merged.toJson())
        assertEquals("gpt-6-luna", decoded.entries["textProcessingModel"]?.value)
        assertEquals("default", decoded.entries["textProcessingServiceTier"]?.value)
        assertEquals("Updated", decoded.entries["dictionary"]?.value)
        assertTrue(old.needsConnectionChoice(local))
    }
}
