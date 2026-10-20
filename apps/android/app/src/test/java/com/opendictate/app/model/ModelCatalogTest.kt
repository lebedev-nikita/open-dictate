package com.opendictate.app.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun recognitionCatalogIgnoresTextCandidatesAndLegacyCache() {
        val source = """{"data":[{"id":"gpt-6-sol"},{"id":"gpt-live-transcribe"},{"id":"gpt-transcribe"}]}"""
        val catalog = ModelCatalog.fromModelsResponse(source)
        assertEquals(ModelCatalog.DEFAULT, catalog)
        assertFalse(JSONObject(catalog.toCachedJson()).has("text"))
        val legacy = """{"live":["gpt-live-transcribe"],"accurate":["gpt-transcribe"],"text":["gpt-6-sol"]}"""
        assertEquals(catalog, ModelCatalog.fromCachedJson(legacy))
    }
    @Test fun emptyOrDamagedCacheKeepsRecognitionFallbacksAvailable() {
        assertTrue(ModelCatalog.fromCachedJson("broken").live.isEmpty())
        assertEquals("gpt-live-transcribe", preferredModelId("", emptyList(), ModelCatalog.DEFAULT.live))
    }
}
