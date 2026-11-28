package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceModelsTest {
    @Test fun `the phone's language and region win when the user chose it or nothing`() {
        assertEquals("ru-RU", OnDeviceModels.speechLocale(emptySet(), listOf("ru-RU", "en-US")))
        assertEquals("en-GB", OnDeviceModels.speechLocale(setOf(DictationLanguage.ENGLISH, DictationLanguage.RUSSIAN), listOf("en-GB")))
        assertEquals("ru-RU", OnDeviceModels.speechLocale(setOf(DictationLanguage.RUSSIAN), listOf("en-US", "ru-RU")))
    }

    @Test fun `a chosen language the phone lacks uses its usual region`() {
        assertEquals("de-DE", OnDeviceModels.speechLocale(setOf(DictationLanguage.GERMAN), listOf("ru-RU")))
        assertEquals("pt-PT", OnDeviceModels.speechLocale(setOf(DictationLanguage.PORTUGUESE), emptyList()))
        assertEquals("en-US", OnDeviceModels.speechLocale(emptySet(), listOf("fi-FI")))
    }

    @Test fun `Chinese uses the Mandarin locales the recognizer names`() {
        assertEquals("cmn-Hans-CN", OnDeviceModels.speechLocale(emptySet(), listOf("zh-CN")))
        assertEquals("cmn-Hant-TW", OnDeviceModels.speechLocale(emptySet(), listOf("zh-Hant-TW")))
        assertEquals("cmn-Hant-TW", OnDeviceModels.speechLocale(emptySet(), listOf("zh-HK")))
        assertEquals("cmn-Hans-CN", OnDeviceModels.speechLocale(setOf(DictationLanguage.CHINESE), listOf("en-US")))
    }

    @Test fun `only cloud paths need a key`() {
        assertFalse(OnDeviceModels.requiresApiKey(TranscriptionModel.ON_DEVICE, cloudText = false))
        assertTrue(OnDeviceModels.requiresApiKey(TranscriptionModel.ON_DEVICE, cloudText = true))
        assertTrue(OnDeviceModels.requiresApiKey(TranscriptionModel.LIVE, cloudText = false))
        assertTrue(OnDeviceModels.requiresApiKey(TranscriptionModel.ACCURATE, cloudText = false))
    }

    @Test fun `recognition is offered as Gemini Nano only when AICore offers text too`() {
        val withoutNano = OnDeviceAvailability(speech = OnDeviceStatus.AVAILABLE, text = OnDeviceStatus.UNAVAILABLE)
        assertFalse(withoutNano.speechOffered)
        assertFalse(withoutNano.textOffered)
        val downloadable = OnDeviceAvailability(speech = OnDeviceStatus.DOWNLOADABLE, text = OnDeviceStatus.DOWNLOADABLE)
        assertTrue(downloadable.speechOffered)
        assertTrue(downloadable.textOffered)
        assertFalse(OnDeviceAvailability(speech = OnDeviceStatus.UNAVAILABLE, text = OnDeviceStatus.AVAILABLE).speechOffered)
    }

    @Test fun `the on-device mode streams like Live and is never read from the synced choice`() {
        assertTrue(TranscriptionModel.ON_DEVICE.streamsPartials)
        assertTrue(TranscriptionModel.LIVE.streamsPartials)
        assertFalse(TranscriptionModel.ACCURATE.streamsPartials)
        assertEquals(TranscriptionModel.ACCURATE, TranscriptionModel.fromStored("ON_DEVICE"))
    }

    @Test fun `post-processing stays off for on-device dictation`() {
        val settings = PostProcessingSettings(enabled = true)
        assertFalse(settings.forMode(TranscriptionModel.ON_DEVICE).shouldProcess)
    }
}

class OnDeviceTranscriptTest {
    @Test fun `partials replace each other within a segment`() {
        val transcript = OnDeviceTranscript()
        assertEquals("Привет", transcript.partial("Привет"))
        assertEquals("Привет, это", transcript.partial("Привет, это"))
        assertEquals("Привет, это тест.", transcript.final("Привет, это тест."))
    }

    @Test fun `a final result closes a segment and later ones follow it`() {
        val transcript = OnDeviceTranscript()
        transcript.final("Первое предложение.")
        assertEquals("Первое предложение. Второе", transcript.partial("Второе"))
        assertEquals("Первое предложение. Второе предложение.", transcript.final("Второе предложение."))
        assertEquals("Первое предложение. Второе предложение.", transcript.text)
    }

    @Test fun `results that repeat earlier segments replace them`() {
        val transcript = OnDeviceTranscript()
        transcript.final("Hello.")
        assertEquals("Hello. How are you?", transcript.final("Hello. How are you?"))
    }

    @Test fun `scripts without spaces are joined directly`() {
        val transcript = OnDeviceTranscript()
        transcript.final("你好。")
        assertEquals("你好。今天", transcript.partial("今天"))
    }

    @Test fun `blank results change nothing`() {
        val transcript = OnDeviceTranscript()
        transcript.final("Text.")
        assertEquals("Text.", transcript.partial("  "))
        assertEquals("Text.", transcript.final(""))
    }
}
