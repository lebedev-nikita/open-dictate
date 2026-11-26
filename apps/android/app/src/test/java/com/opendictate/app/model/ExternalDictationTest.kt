package com.opendictate.app.model

import android.app.Activity
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalDictationTest {
    @Test
    fun resultCodesMatchAndroidConstants() = with(ExternalDictationContract) {
        assertEquals(Activity.RESULT_OK, RESULT_OK)
        assertEquals(Activity.RESULT_CANCELED, RESULT_CANCELED)
        assertEquals(RecognizerIntent.RESULT_NO_MATCH, RESULT_NO_MATCH)
        assertEquals(RecognizerIntent.RESULT_CLIENT_ERROR, RESULT_CLIENT_ERROR)
        assertEquals(RecognizerIntent.RESULT_SERVER_ERROR, RESULT_SERVER_ERROR)
        assertEquals(RecognizerIntent.RESULT_NETWORK_ERROR, RESULT_NETWORK_ERROR)
        assertEquals(RecognizerIntent.RESULT_AUDIO_ERROR, RESULT_AUDIO_ERROR)
    }

    @Test
    fun transcriptIsReturnedWithoutError() {
        val result = ExternalDictationOutcome.Transcribed("Привет").toResult()
        assertEquals(ExternalDictationResult(ExternalDictationContract.RESULT_OK, "Привет", null), result)
    }

    @Test
    fun outcomesMapToStableErrors() {
        val expected = mapOf(
            ExternalDictationOutcome.Cancelled to (ExternalDictationContract.RESULT_CANCELED to "cancelled"),
            ExternalDictationOutcome.NotAllowed to (ExternalDictationContract.RESULT_CLIENT_ERROR to "not_allowed"),
            ExternalDictationOutcome.Busy to (ExternalDictationContract.RESULT_CLIENT_ERROR to "busy"),
            failed(DictationError.MissingApiKey) to (ExternalDictationContract.RESULT_CLIENT_ERROR to "missing_api_key"),
            failed(DictationError.MicrophonePermission) to (ExternalDictationContract.RESULT_AUDIO_ERROR to "microphone_permission"),
            failed(DictationError.Network) to (ExternalDictationContract.RESULT_NETWORK_ERROR to "network"),
            failed(DictationError.Timeout) to (ExternalDictationContract.RESULT_NETWORK_ERROR to "timeout"),
            failed(DictationError.EmptyResult) to (ExternalDictationContract.RESULT_NO_MATCH to "empty"),
            failed(DictationError.Http(401)) to (ExternalDictationContract.RESULT_SERVER_ERROR to "server"),
            failed(DictationError.Provider()) to (ExternalDictationContract.RESULT_SERVER_ERROR to "server"),
            failed(DictationError.InvalidResponse) to (ExternalDictationContract.RESULT_SERVER_ERROR to "server"),
            failed(DictationError.Unexpected) to (ExternalDictationContract.RESULT_CLIENT_ERROR to "unexpected"),
        )
        expected.forEach { (outcome, codeAndError) ->
            val result = outcome.toResult()
            assertEquals(outcome.toString(), codeAndError, result.resultCode to result.error)
            assertNull(result.text)
        }
    }

    @Test
    fun callerMustBeIdentifiedAndApproved() {
        val allowed = setOf("com.llamalab.automate")
        assertEquals(ExternalCallerAccess.REJECTED, externalCallerAccess(null, allowed))
        assertEquals(ExternalCallerAccess.REJECTED, externalCallerAccess("", allowed))
        assertEquals(ExternalCallerAccess.ALLOWED, externalCallerAccess("com.llamalab.automate", allowed))
        assertEquals(ExternalCallerAccess.ASK, externalCallerAccess("com.example.other", allowed))
    }

    private fun failed(error: DictationError) = ExternalDictationOutcome.Failed(error)
}
