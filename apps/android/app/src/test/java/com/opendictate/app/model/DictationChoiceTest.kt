package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationChoiceTest {
    private val accurate = DictationChoice(TranscriptionModel.ACCURATE, "gpt-transcribe")
    private val live = DictationChoice(TranscriptionModel.LIVE, "gpt-live-transcribe")

    @Test fun `the model decides the mode and the phone's recognizer comes last where it works`() {
        assertEquals(listOf(accurate, live), DictationChoice.options(ModelCatalog.DEFAULT, onDeviceOffered = false, selected = accurate))
        assertEquals(listOf(accurate, live, DictationChoice.ON_DEVICE),
            DictationChoice.options(ModelCatalog.DEFAULT, onDeviceOffered = true, selected = accurate))
    }

    @Test fun `a chosen on-device model stays listed after the phone loses it`() {
        assertEquals(listOf(accurate, live, DictationChoice.ON_DEVICE),
            DictationChoice.options(ModelCatalog.DEFAULT, onDeviceOffered = false, selected = DictationChoice.ON_DEVICE))
    }

    @Test fun `an empty catalog still offers the bundled models`() {
        assertEquals(listOf(accurate, live), DictationChoice.options(ModelCatalog(emptyList(), emptyList()), false, accurate))
    }

    @Test fun `the selection follows the saved mode and its model`() {
        assertEquals(live, DictationChoice.selected(TranscriptionModel.LIVE, "gpt-live-transcribe", "gpt-transcribe"))
        assertEquals(accurate, DictationChoice.selected(TranscriptionModel.ACCURATE, "gpt-live-transcribe", "gpt-transcribe"))
        assertEquals(DictationChoice.ON_DEVICE, DictationChoice.selected(TranscriptionModel.ON_DEVICE, "x", "y"))
    }

    @Test fun `OpenAI models have readable names and the phone's recognizer is named by the screen`() {
        assertEquals("GPT Transcribe", accurate.title)
        assertEquals("GPT Live Transcribe", live.title)
        assertEquals("gpt-transcribe-2", DictationChoice(TranscriptionModel.ACCURATE, "gpt-transcribe-2").title)
        assertNull(DictationChoice.ON_DEVICE.title)
    }
}
