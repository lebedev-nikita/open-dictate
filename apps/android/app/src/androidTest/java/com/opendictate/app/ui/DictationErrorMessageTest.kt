package com.opendictate.app.ui

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ErrorReason
import com.opendictate.app.model.FailureDetails
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DictationErrorMessageTest {
    @Test fun everyFailureHasBundledEnglishAndRussianCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failures = listOf(
            DictationError.Http(401), DictationError.Http(429), DictationError.Network, DictationError.Timeout,
            DictationError.InvalidResponse, DictationError.EmptyResult, DictationError.StreamIncomplete,
            DictationError.Provider(), DictationError.ChatGPT(FailureDetails(ErrorReason.CREDENTIAL_STORAGE)),
            DictationError.MicrophonePermission, DictationError.MissingApiKey, DictationError.MissingRecording,
            DictationError.NothingToTransform, DictationError.EmptyTransformation,
            DictationError.Rejected(ErrorReason.WORDS_CHANGED), DictationError.Unexpected,
        )
        val messages = listOf("en", "ru").map { language ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(configuration).resources
            failures.map { it.userMessage(resources) }
        }
        failures.indices.forEach { index ->
            assertTrue(messages[0][index].isNotBlank())
            assertTrue(messages[1][index].isNotBlank())
            assertNotEquals("Missing Russian translation for ${failures[index]}", messages[0][index], messages[1][index])
            assertFalse(messages[0][index].contains("%1"))
            assertFalse(messages[1][index].contains("%1"))
        }
    }
}
