package com.opendictate.app.model

import com.opendictate.app.chatgpt.ChatGPTResponseAccumulator
import com.opendictate.app.network.OpenAiException
import com.opendictate.app.network.OpenAiTranscriptionClient
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ErrorDiagnosticsTest {
    @Test fun punctuationRejectionsExplainWhyWithoutChangingDeliveryRules() = runTest {
        val settings = PostProcessingSettings(enabled = true)
        for ((source, candidate, reason) in listOf(
            Triple("да,конечно", "Да, конечно.", ErrorReason.SPACING_CHANGED),
            Triple("он все еще ждет", "Он всё ещё ждёт.", ErrorReason.WORDS_CHANGED),
            Triple("open foo.bar", "Open foobar.", ErrorReason.WORDS_CHANGED),
            Triple("цена 1.5 рубля", "Цена 15 рубля.", ErrorReason.NUMBERS_CHANGED),
        )) {
            val result = PostProcessing.apply(source, settings) { candidate }
            assertEquals(source, result.text)
            assertEquals(FailureDetails(reason), result.failure)
        }
        assertFalse(PostProcessing.apply("да конечно", settings) { "Да, конечно." }.failed)
    }

    @Test fun requestFailuresReachPostProcessingWithoutProviderText() = runTest {
        val cases = listOf(
            429 to "sensitive quota description",
            401 to "sensitive token description",
            200 to "malformed synthetic response",
            200 to """{"status":"incomplete","output":[]}""",
        )
        for ((index, row) in cases.withIndex()) {
            val (status, body) = row
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val result = OpenAiTranscriptionClient(httpClient = http).postProcess(
                "synthetic-key", "synthetic source", PostProcessingSettings(enabled = true), emptySet(),
            )
            val expected = when (index) {
                0 -> FailureDetails(ErrorReason.HTTP, 429)
                1 -> FailureDetails(ErrorReason.AUTHENTICATION, 401)
                2 -> FailureDetails(ErrorReason.INVALID_RESPONSE)
                else -> FailureDetails(ErrorReason.STREAM_INCOMPLETE)
            }
            assertEquals(expected, result.failure)
            assertEquals("synthetic source", result.text)
            assertFalse(result.failure.toString().contains("sensitive"))
        }
    }

    @Test fun wrappedNetworkAndTimeoutErrorsKeepTheirSafeCategory() {
        assertEquals(ErrorReason.NETWORK, FailureDetails.from(OpenAiException("private", IOException("private"))).reason)
        assertEquals(ErrorReason.TIMEOUT, FailureDetails.from(OpenAiException("private", InterruptedIOException("private"))).reason)
        assertEquals(FailureDetails(ErrorReason.HTTP, 503), FailureDetails.from(
            OpenAiException("private", failureDetails = FailureDetails.http(503))))
        assertEquals(DiagnosticModel.OTHER, DiagnosticModel.fromId("sk-synthetic-private-value"))
    }

    @Test fun incompleteAndFailedStreamsRemainDistinct() {
        val stream = ChatGPTResponseAccumulator()
        val missing = assertThrows(Exception::class.java) { stream.result() }
        assertEquals(ErrorReason.STREAM_INCOMPLETE, FailureDetails.from(missing).reason)
        for ((type, reason) in listOf("response.incomplete" to ErrorReason.STREAM_INCOMPLETE, "response.failed" to ErrorReason.PROVIDER)) {
            val error = assertThrows(Exception::class.java) {
                ChatGPTResponseAccumulator().consume("""{"type":"$type","error":{"message":"synthetic private data"}}""")
            }
            assertEquals(reason, FailureDetails.from(error).reason)
        }
    }

}
