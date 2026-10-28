package com.opendictate.app.network

import arrow.core.left
import arrow.core.right
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.FailureDetails
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class TypedRequestErrorsTest {
    @Test fun `HTTP rejection exposes only status for transcription models and voice editing`() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 503)) {
            withResponse(status, """{"error":{"message":"synthetic private provider text"}}""") { client, file ->
                val failure = DictationError.Http(status)
                val expected = failure.left()
                assertEquals(expected, client.transcribeFile("synthetic-key", "gpt-transcribe", file, emptySet(), "", 30))
                assertEquals(expected, client.listModels("synthetic-key"))
                assertEquals(expected, client.transformText("synthetic-key", "synthetic source", "synthetic instruction"))
                assertEquals(FailureDetails.http(status), failure.failureDetails)
                assertFalse(expected.toString().contains("private"))
            }
        }
    }

    @Test fun `transcription distinguishes malformed absent empty and successful text`() = runBlocking {
        val cases = listOf(
            "private malformed data" to DictationError.InvalidResponse.left(),
            "{}" to DictationError.InvalidResponse.left(),
            """{"text":null}""" to DictationError.InvalidResponse.left(),
            """{"text":"  "}""" to DictationError.EmptyResult.left(),
            """{"text":" synthetic text "}""" to "synthetic text".right(),
        )
        for ((body, expected) in cases) withResponse(200, body) { client, file ->
            assertEquals(expected, client.transcribeFile("synthetic-key", "gpt-transcribe", file, emptySet(), "", 30))
        }
    }

    @Test fun `empty transformation and invalid JSON are distinct typed failures`() = runBlocking {
        withResponse(200, """{"output":[]}""") { client, _ ->
            assertEquals(DictationError.EmptyTransformation.left(),
                client.transformText("synthetic-key", "synthetic source", "synthetic instruction"))
        }
        withResponse(200, "private malformed data") { client, _ ->
            assertEquals(DictationError.InvalidResponse.left(),
                client.transformText("synthetic-key", "synthetic source", "synthetic instruction"))
        }
    }

    @Test fun `transport and decoding errors are values while cancellation and defects propagate`() {
        for ((error, expected) in listOf(
            IOException("private") to DictationError.Network,
            InterruptedIOException("private") to DictationError.Timeout,
            EOFException("private") to DictationError.StreamIncomplete,
            JSONException("private") to DictationError.InvalidResponse,
        )) assertEquals(expected.left(), requestAttempt { throw error })

        val cancellation = CancellationException("synthetic cancellation")
        assertSame(cancellation, assertThrows(CancellationException::class.java) { requestAttempt { throw cancellation } })
        val defect = IllegalStateException("synthetic defect")
        assertSame(defect, assertThrows(IllegalStateException::class.java) { requestAttempt { throw defect } })
        val fatal = LinkageError("synthetic fatal failure")
        assertSame(fatal, assertThrows(LinkageError::class.java) { requestAttempt { throw fatal } })
    }

    private suspend fun withResponse(status: Int, body: String, block: suspend (OpenAiTranscriptionClient, File) -> Unit) {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val file = File.createTempFile("synthetic-typed-errors-", ".wav")
        try {
            block(OpenAiTranscriptionClient(httpClient = http), file)
        } finally {
            file.delete()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
