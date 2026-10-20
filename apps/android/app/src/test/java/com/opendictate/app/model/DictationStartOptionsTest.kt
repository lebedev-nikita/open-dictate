package com.opendictate.app.model

import org.junit.Assert.*
import org.junit.Test

class DictationStartOptionsTest {
    @Test fun `primary button keeps saved mode and processing policy`() {
        for (mode in TranscriptionModel.entries) for (enabled in listOf(false, true)) {
            val saved = PostProcessingSettings(enabled = enabled)
            val result = DictationStartOptions.resolve(mode, saved)
            assertEquals(mode, result.model)
            assertEquals(saved.forMode(mode), result.postProcessing)
        }
    }

    @Test fun `alternate choice enables Accurate processing once without changing saved settings`() {
        val saved = PostProcessingSettings(enabled = false)
        for (mode in TranscriptionModel.entries) {
            val result = DictationStartOptions.resolve(mode, saved, true)
            assertEquals(TranscriptionModel.ACCURATE, result.model)
            assertTrue(result.postProcessing.shouldProcess)
            assertFalse(saved.enabled)
            assertEquals(saved.forMode(mode), DictationStartOptions.resolve(mode, saved).postProcessing)
        }
    }

    @Test fun `alternate choice disables only this session and preserves configured rules`() {
        val saved = PostProcessingSettings(enabled = true, formatValues = true,
            formats = ValueFormatting.systemDefaults(java.util.Locale.US))
        val result = DictationStartOptions.resolve(TranscriptionModel.ACCURATE, saved, false)
        assertFalse(result.postProcessing.shouldProcess)
        assertEquals(saved.formats, result.postProcessing.formats)
        assertTrue(DictationStartOptions.resolve(TranscriptionModel.ACCURATE, saved).postProcessing.shouldProcess)
    }

    @Test fun `forced processing falls back to punctuation when all rule switches are off`() {
        val saved = PostProcessingSettings(enabled = false, punctuation = false)
        assertTrue(DictationStartOptions.resolve(TranscriptionModel.ACCURATE, saved, true).postProcessing.shouldProcess)
        assertFalse(saved.punctuation)
    }
}
