package com.opendictate.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.chatgpt.ChatGPTSession
import com.opendictate.app.network.textTransformationRequest
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatGPTSessionTest {
    private fun isolated(): Pair<Context, () -> Unit> {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "chatgpt-test-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(actual) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        return context to { listOf("opendictate_credentials_chatgpt", "chatgpt_host").forEach { actual.deleteSharedPreferences(prefix + it) }; Unit }
    }
    private fun seed(context: Context) {
        val record = JSONObject().put("access_token", "synthetic-expired").put("refresh_token", "synthetic-refresh").put("expires_at", 1).put("scope", "chatgpt.tokens.use.direct")
        SecureApiKeyStore(context, "_chatgpt").save(JSONObject().put("active", "oaiapp_test").put("oaiapp_test", record).toString())
        context.getSharedPreferences("chatgpt_host", Context.MODE_PRIVATE).edit().putBoolean("use_plan", true).commit()
    }
    @Test fun rotatingRefreshIsSerializedAndOnlyOAuthBearerReachesTextEndpoint() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val refreshes = AtomicInteger(); val responses = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath.endsWith("/oauth/token")) {
                    refreshes.incrementAndGet()
                    """{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","expires_in":3600,"token_type":"Bearer"}"""
                } else {
                    assertEquals("/v1/responses", request.url.encodedPath)
                    assertEquals("Bearer synthetic-new", request.header("Authorization"))
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val payload = JSONObject(buffer.readUtf8())
                    assertTrue(payload.getBoolean("stream")); assertFalse(payload.getBoolean("store")); assertTrue(payload.get("input") is org.json.JSONArray)
                    responses.incrementAndGet()
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val session = ChatGPTSession(context, http)
            coroutineScope { List(2) { async(Dispatchers.Default) { session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit")) } }.awaitAll() }
            assertEquals(1, refreshes.get()); assertEquals(2, responses.get())
            val saved = JSONObject(SecureApiKeyStore(context, "_chatgpt").get(strict = true)!!).getJSONObject("oaiapp_test")
            assertEquals("synthetic-rotated", saved.getString("refresh_token"))
        } finally { cleanup() }
    }
    @Test fun terminalRefreshErrorClearsTokensButNeverSelectsApiBilling() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context); val calls = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Rejected")
                    .body("{\"error\":\"invalid_grant\"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val session = ChatGPTSession(context, http)
            repeat(2) { try { session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit")); fail() } catch (_: com.opendictate.app.chatgpt.ChatGPTException) {} }
            assertEquals(1, calls.get())
            assertEquals("oaiapp_test", session.activeAccount())
            val saved = JSONObject(SecureApiKeyStore(context, "_chatgpt").get(strict = true)!!).getJSONObject("oaiapp_test")
            assertFalse(saved.has("refresh_token")); assertFalse(saved.has("access_token"))
        } finally { cleanup() }
    }
    @Test fun completedItemFeedsPostProcessingWhenTerminalOutputIsEmpty() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val item = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text")
                    .put("text", "{\"transformed_text\":\"123\"}")))
            val done = JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", item)
            val terminal = "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"
            val stream = "data: $done\n\ndata: $terminal\n\n"
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath.endsWith("/oauth/token"))
                    """{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","expires_in":3600,"token_type":"Bearer"}"""
                else {
                    assertEquals("Bearer synthetic-new", request.header("Authorization"))
                    val buffer = okio.Buffer(); requireNotNull(request.body).writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    val input = JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content"))
                    assertTrue(input.getString("instruction").contains("[ru] Russian."))
                    assertFalse(input.getString("instruction").contains("[en] English."))
                    stream
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val session = ChatGPTSession(context, http)
            val settings = com.opendictate.app.model.PostProcessingSettings(enabled = true, formatValues = true,
                formats = com.opendictate.app.model.ValueFormatting.systemDefaults(java.util.Locale.US))
            val result = com.opendictate.app.network.OpenAiTranscriptionClient(chatGPT = session)
                .postProcess("must-not-be-used", "сто двадцать три", settings, languages = setOf(com.opendictate.app.model.DictationLanguage.RUSSIAN), chatGPTAccount = "oaiapp_test")
            assertFalse(result.failed); assertEquals("123", result.text)
        } finally { cleanup() }
    }

}
