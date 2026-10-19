package com.opendictate.app.network

import com.opendictate.app.model.DictationLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class OpenAiTranscriptionClientTest {
    @Test
    fun `file transcription uses configured overall timeout or none`() {
        val base = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        assertEquals(30_000, base.forTranscriptionResponse(30).callTimeoutMillis)
        assertEquals(0, base.forTranscriptionResponse(30).readTimeoutMillis)
        assertEquals(0, base.forTranscriptionResponse(null).callTimeoutMillis)
        assertEquals(0, base.forTranscriptionResponse(null).readTimeoutMillis)
    }

    @Test
    fun `live final response times out after configured interval`() = runTest {
        var timedOut = false
        try {
            awaitTranscriptionResponse(30) { awaitCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }

        assertTrue(timedOut)
    }

    @Test
    fun `live final response can wait without an overall limit`() = runTest {
        val response = CompletableDeferred<String>()
        val waiting = async { awaitTranscriptionResponse(null) { response.await() } }

        delay(600_000)
        assertFalse(waiting.isCompleted)
        response.complete("recognized")
        assertEquals("recognized", waiting.await())
    }

    @Test
    fun `realtime transcription URL selects transcription intent without a model`() {
        val query = URI(realtimeTranscriptionUrl()).rawQuery
            .split('&')
            .associate { part ->
                val (name, value) = part.split('=', limit = 2)
                name to value
            }

        assertEquals("transcription", query["intent"])
        assertFalse(query.containsKey("model"))
    }

    @Test
    fun `session update configures live transcription without unsupported delay`() {
        val event = realtimeTranscriptionSessionUpdate(
            modelId = "gpt-live-transcribe",
            languages = setOf(DictationLanguage.ENGLISH, DictationLanguage.RUSSIAN),
            dictionary = "OpenDictate\r\n WebSocket \n<OpenDictate>\n<>",
        )
        val session = event.getJSONObject("session")
        val input = session.getJSONObject("audio").getJSONObject("input")
        val transcription = input.getJSONObject("transcription")

        assertEquals("session.update", event.getString("type"))
        assertEquals("transcription", session.getString("type"))
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        assertEquals("gpt-live-transcribe", transcription.getString("model"))
        assertFalse(transcription.has("prompt"))
        assertEquals(listOf("OpenDictate", "WebSocket"), transcription.getJSONArray("keywords").let { keywords ->
            List(keywords.length()) { index -> keywords.getString(index) }
        })
        assertEquals(listOf("en", "ru"), transcription.getJSONArray("languages").let { languages ->
            List(languages.length()) { index -> languages.getString(index) }
        })
        assertFalse(transcription.has("delay"))
        assertTrue(input.isNull("turn_detection"))
    }

    @Test
    fun `Russian transcription context does not override selected Ukrainian`() {
        val event = realtimeTranscriptionSessionUpdate(
            modelId = "gpt-live-transcribe",
            languages = setOf(DictationLanguage.RUSSIAN, DictationLanguage.UKRAINIAN),
            dictionary = "OpenDictate",
        )
        val transcription = event
            .getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")

        assertFalse(transcription.has("prompt"))
        assertEquals("OpenDictate", transcription.getJSONArray("keywords").getString(0))
    }

    @Test
    fun `transcription omits prompt for every language and automatic detection`() {
        val languageSelections = listOf(emptySet<DictationLanguage>()) + DictationLanguage.entries.map { setOf(it) } +
            listOf(DictationLanguage.entries.toSet())

        languageSelections.forEach { languages ->
            val transcription = realtimeTranscriptionSessionUpdate("gpt-live-transcribe", languages, "")
                .getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
            assertFalse(transcription.has("prompt"))
            val file = java.io.File.createTempFile("transcription-test", ".wav")
            try {
                val buffer = okio.Buffer()
                transcriptionMultipartBody("gpt-transcribe", file, languages, "").writeTo(buffer)
                assertFalse(buffer.readUtf8().contains("name=\"prompt\""))
            } finally { file.delete() }
        }
    }

    @Test
    fun `live transcription omits default prompt without a dictionary or language hint`() {
        val input = realtimeTranscriptionSessionUpdate(
            modelId = "gpt-live-transcribe",
            languages = emptySet(),
            dictionary = " \r\n<>\n ",
        ).getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        val transcription = input.getJSONObject("transcription")

        assertFalse(transcription.has("prompt"))
        assertFalse(transcription.has("keywords"))
        assertFalse(transcription.has("languages"))
        assertFalse(transcription.has("delay"))
        assertTrue(input.isNull("turn_detection"))
    }

    @Test
    fun `accurate transcription sends normalized dictionary as repeated keyword fields`() {
        val file = java.io.File.createTempFile("transcription-test-", ".wav")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val languages = setOf(DictationLanguage.RUSSIAN, DictationLanguage.ENGLISH)
            val body = transcriptionMultipartBody("gpt-transcribe", file, languages,
                " OpenDictate, Jetpack Compose\r\n<opendictate>\n<>")
            val fields = body.parts.filter { it.headers?.get("Content-Disposition")?.contains("filename=") != true }
                .map { part ->
                    val buffer = okio.Buffer()
                    part.body.writeTo(buffer)
                    part.headers?.get("Content-Disposition") to buffer.readUtf8()
                }
            fun values(name: String) = fields.filter { it.first == "form-data; name=\"$name\"" }.map { it.second }

            assertEquals(listOf("gpt-transcribe"), values("model"))
            assertEquals(listOf("ru", "en"), values("languages[]"))
            assertEquals(listOf("OpenDictate", "Jetpack Compose"), values("keywords[]"))
            assertTrue(values("prompt").isEmpty())
            val audio = body.parts.single { it.headers?.get("Content-Disposition")?.contains("filename=") == true }
            assertEquals("audio/wav", audio.body.contentType().toString())
            val buffer = okio.Buffer()
            audio.body.writeTo(buffer)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), buffer.readByteArray())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `accurate automatic language and empty dictionary omit array hints`() {
        val body = transcriptionMultipartBody("gpt-transcribe", java.io.File("unused.wav"), emptySet(), " \r\n<> ")
        val headers = body.parts.map { it.headers?.get("Content-Disposition").orEmpty() }
        assertFalse(headers.any { it.contains("keywords") || it.contains("languages") })
    }

    @Test
    fun `text transformation request always uses Luna without storage`() {
        val request = textTransformationRequest(

            sourceText = "Черновик",
            instruction = "Сделай вежливее",
        )

        assertEquals("gpt-6-luna", request.getString("model"))
        assertFalse(request.getBoolean("store"))
        assertEquals("low", request.getJSONObject("reasoning").getString("effort"))
        val format = request.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        val schema = format.getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(
            listOf("transformed_text", "message"),
            schema.getJSONArray("required").let { required ->
                List(required.length()) { index -> required.getString(index) }
            },
        )
        val input = JSONObject(request.getString("input"))
        assertEquals("Сделай вежливее", input.getString("instruction"))
        assertEquals("Черновик", input.getString("source_text"))
    }

    @Test
    fun `response text extraction skips reasoning and joins text parts`() {
        val response = JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(JSONObject().put("type", "reasoning"))
                    .put(
                        JSONObject()
                            .put("type", "message")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "refusal").put("refusal", "no"))
                                    .put(JSONObject().put("type", "output_text").put("text", "Готовый "))
                                    .put(JSONObject().put("type", "output_text").put("text", "текст")),
                            ),
                    ),
            )

        assertEquals("Готовый текст", extractResponseText(response))
    }

    @Test
    fun `transformation result normally has no user message`() {
        val response = transformationResponse(
            transformedText = "Готовый текст",
            message = JSONObject.NULL,
        )

        assertEquals(
            TextTransformationResult(text = "Готовый текст", message = null),
            extractTransformationResult(response),
        )
    }

    @Test
    fun `transformation result exposes an attached user message`() {
        val response = transformationResponse(
            transformedText = "Исходный текст",
            message = "Не удалось определить адресата.",
        )

        assertEquals(
            TextTransformationResult(
                text = "Исходный текст",
                message = "Не удалось определить адресата.",
            ),
            extractTransformationResult(response),
        )
    }

    @Test
    fun `history search request uses structured output without storage`() {
        val request = transcriptHistorySearchRequest(

            query = "project planning",
            documents = listOf(
                TranscriptSearchDocument(7, "Discuss the roadmap"),
                TranscriptSearchDocument(9, "Buy coffee"),
            ),
        )

        assertEquals("gpt-6-luna", request.getString("model"))
        assertFalse(request.getBoolean("store"))
        val format = request.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        val input = JSONObject(request.getString("input"))
        assertEquals("project planning", input.getString("semantic_query"))
        assertEquals(7, input.getJSONArray("documents").getJSONObject(0).getLong("id"))
    }

    @Test
    fun `history search matches are extracted and deduplicated`() {
        val response = JSONObject().put(
            "output",
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", JSONObject().put("match_ids", JSONArray(listOf(9, 7, 9))).toString()),
                        ),
                    ),
            ),
        )

        assertEquals(listOf(9L, 7L), extractTranscriptHistoryMatches(response))
    }

    @Test
    fun `history search batches bound document count`() {
        val documents = (1L..51L).map { TranscriptSearchDocument(it, "text") }

        assertEquals(listOf(50, 1), historySearchBatches(documents).map(List<*>::size))
    }

    private fun transformationResponse(
        transformedText: String,
        message: Any,
    ): JSONObject = JSONObject().put(
        "output",
        JSONArray().put(
            JSONObject()
                .put("type", "message")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "output_text")
                            .put(
                                "text",
                                JSONObject()
                                    .put("transformed_text", transformedText)
                                    .put("message", message)
                                    .toString(),
                            ),
                    ),
                ),
        ),
    )
}
