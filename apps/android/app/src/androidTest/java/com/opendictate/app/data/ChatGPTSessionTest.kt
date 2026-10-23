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
    @Test fun failedStartupReadRecoversSavedAccountWithoutReauthorizationOrApiFallback() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val failOnce = java.util.concurrent.atomic.AtomicBoolean(true)
            val transient = credentialContext(context) { key, value ->
                if (key == "api_key_iv" && failOnce.compareAndSet(true, false)) "AAAAAAAAAAAAAAAA" else value
            }
            val refreshes = AtomicInteger()
            val http = planHttp(refreshes)
            val session = ChatGPTSession(transient, http)
            val captured = requireNotNull(session.activeAccount())
            assertTrue(session.state.value.failed)
            val api = OkHttpClient.Builder().addInterceptor { error("API fallback must not run") }.build()
            val result = com.opendictate.app.network.OpenAiTranscriptionClient(transient.resources, session, api)
                .postProcess("must-not-be-used", "Synthetic", com.opendictate.app.model.PostProcessingSettings(true),
                    emptySet(), chatGPTAccount = captured)
            assertFalse(result.failed)
            assertEquals("Synthetic.", result.text)
            assertEquals("oaiapp_test", session.activeAccount())
            assertFalse(session.state.value.failed)
            assertEquals(1, refreshes.get())
        } finally { cleanup() }
    }

    @Test fun cancelledDictationRetainsRotatedTokensWithoutSendingInference() = runBlocking {
        val (context, cleanup) = isolated()
        val release = java.util.concurrent.CountDownLatch(1)
        val rotated = CompletableDeferred<Unit>()
        val refreshes = AtomicInteger()
        val responses = AtomicInteger()
        try {
            seed(context)
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath.endsWith("/oauth/token")) {
                    assertEquals(1, refreshes.incrementAndGet())
                    rotated.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    renewedTokens
                } else {
                    assertEquals("Bearer synthetic-new", request.header("Authorization"))
                    responses.incrementAndGet()
                    completedText
                }
                response(request, body)
            }.build()
            val session = ChatGPTSession(context, http)
            val operation = launch(Dispatchers.Default) {
                session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
            }
            withTimeout(10_000) { rotated.await() }
            operation.cancel()
            release.countDown()
            withTimeout(10_000) { operation.join() }
            assertEquals(0, responses.get())
            val saved = JSONObject(SecureApiKeyStore(context, "_chatgpt").get(strict = true)!!)
                .getJSONObject("oaiapp_test")
            assertEquals("synthetic-rotated", saved.getString("refresh_token"))
            // A newly created broker must use the saved replacement, including after cancellation.
            ChatGPTSession(context, http).response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
            assertEquals(1, refreshes.get())
            assertEquals(1, responses.get())
        } finally { release.countDown(); cleanup() }
    }

    @Test fun brokerRecreationRestoresTheLatestRotatedTokens() = runBlocking {
        // Run seed and verify in separate instrumentation processes to check a real cold restart.
        val phase = InstrumentationRegistry.getArguments().getString("chatGPTPersistencePhase")
        require(phase in listOf(null, "seed", "verify"))
        val (context, cleanup) = if (phase == null) isolated() else isolated("chatgpt-restart-test-")
        try {
            val refreshes = AtomicInteger()
            val http = planHttp(refreshes)
            if (phase != "verify") {
                seed(context)
                ChatGPTSession(context, http).response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
                assertEquals(1, refreshes.get())
            }
            if (phase != "seed") {
                repeat(3) {
                    val session = ChatGPTSession(context, http)
                    assertEquals("oaiapp_test", session.activeAccount())
                    session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
                }
                assertEquals(if (phase == "verify") 0 else 1, refreshes.get())
            }
        } finally { if (phase != "seed") cleanup() }
    }

    @Test fun unreadableStoreIsRetriedWithoutOverwritingCredentialsOrOpeningSignIn() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
            val transient = credentialContext(context) { key, value ->
                if (key == "api_key_iv" && unavailable.get()) "AAAAAAAAAAAAAAAA" else value
            }
            val refreshes = AtomicInteger()
            val session = ChatGPTSession(transient, planHttp(refreshes))
            val captured = session.activeAccount()!!
            repeat(2) {
                try {
                    session.response(captured, textTransformationRequest("Synthetic", "Edit"))
                    fail("Unreadable credentials must fail closed")
                } catch (error: com.opendictate.app.chatgpt.ChatGPTException) {
                    assertEquals(com.opendictate.app.model.ErrorReason.CREDENTIAL_STORAGE, error.failureDetails.reason)
                }
            }
            session.connect({ true }) { error("An unreadable journal must not be replaced by a new registration") }
            assertEquals(0, refreshes.get())
            assertEquals("synthetic-refresh", JSONObject(SecureApiKeyStore(context, "_chatgpt").get(strict = true)!!)
                .getJSONObject("oaiapp_test").getString("refresh_token"))
            unavailable.set(false)
            session.response(captured, textTransformationRequest("Synthetic", "Edit"))
            assertEquals(1, refreshes.get())
            assertFalse(session.state.value.failed)
        } finally { cleanup() }
    }

    @Test fun failedRotatedTokenWriteIsRetriedDurablyBeforeInference() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val commits = AtomicInteger()
            val writing = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                    val prefs = context.getSharedPreferences(name, mode)
                    if (name != "opendictate_credentials_chatgpt") return prefs
                    return object : android.content.SharedPreferences by prefs {
                        override fun edit(): android.content.SharedPreferences.Editor {
                            val editor = prefs.edit()
                            return object : android.content.SharedPreferences.Editor by editor {
                                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                                    editor.putString(key, value)
                                    return this
                                }
                                override fun apply() { error("Rotating credentials require a durable commit") }
                                override fun commit() = if (commits.incrementAndGet() == 1) false else editor.commit()
                            }
                        }
                    }
                }
            }
            val refreshes = AtomicInteger()
            val session = ChatGPTSession(writing, planHttp(refreshes))
            try {
                session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
                fail("A failed durable save must stop inference")
            } catch (error: com.opendictate.app.chatgpt.ChatGPTException) {
                assertEquals(com.opendictate.app.model.ErrorReason.CREDENTIAL_STORAGE, error.failureDetails.reason)
            }
            session.response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
            assertEquals(1, refreshes.get())
            assertEquals(2, commits.get())
            ChatGPTSession(context, planHttp(refreshes)).response("oaiapp_test", textTransformationRequest("Synthetic", "Edit"))
            assertEquals(1, refreshes.get())
        } finally { cleanup() }
    }

    @Test fun signOutWhileStoreIsUnavailablePreventsCapturedRequestsFromRestoringIt() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
            val transient = credentialContext(context) { key, value ->
                if (key == "api_key_iv" && unavailable.get()) "AAAAAAAAAAAAAAAA" else value
            }
            val http = OkHttpClient.Builder().addInterceptor { error("Signed-out requests must not run") }.build()
            val session = ChatGPTSession(transient, http)
            val captured = session.activeAccount()!!
            session.disconnect()
            assertNull(session.activeAccount())
            assertTrue(session.state.value.revocationUnconfirmed)
            unavailable.set(false)
            try {
                session.response(captured, textTransformationRequest("Synthetic", "Edit"))
                fail("An old attempt must not restore the signed-out account")
            } catch (error: com.opendictate.app.chatgpt.ChatGPTException) {
                assertEquals(com.opendictate.app.model.ErrorReason.AUTHENTICATION, error.failureDetails.reason)
            }
            assertNull(ChatGPTSession(context, http).activeAccount())
        } finally { cleanup() }
    }

    private fun credentialContext(context: Context, read: (String?, String?) -> String?): Context =
        object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val prefs = context.getSharedPreferences(name, mode)
                if (name != "opendictate_credentials_chatgpt") return prefs
                return object : android.content.SharedPreferences by prefs {
                    override fun getString(key: String?, defValue: String?) = read(key, prefs.getString(key, defValue))
                }
            }
        }

    private val renewedTokens = """{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","expires_in":3600,"token_type":"Bearer"}"""
    private val completedText = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"transformed_text\\\":\\\"Synthetic.\\\"}\"}]}]}}\n\n"
    private fun response(request: Request, body: String, status: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    private fun planHttp(refreshes: AtomicInteger) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = if (request.url.encodedPath.endsWith("/oauth/token")) {
            refreshes.incrementAndGet()
            renewedTokens
        } else {
            assertEquals("Bearer synthetic-new", request.header("Authorization"))
            completedText
        }
        response(request, body)
    }.build()

    @Test fun postProcessingRetainsPlanHttpStatusWithoutApiFallback() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            seed(context)
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val refresh = chain.request().url.encodedPath.endsWith("/oauth/token")
                val body = if (refresh)
                    """{"access_token":"synthetic-new","refresh_token":"synthetic-rotated","scope":"chatgpt.tokens.use.direct","expires_in":3600,"token_type":"Bearer"}"""
                else """{"error":{"message":"synthetic private provider detail"}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (refresh) 200 else 429).message("Synthetic")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = OkHttpClient.Builder().addInterceptor { error("API fallback must not run") }.build()
            val result = com.opendictate.app.network.OpenAiTranscriptionClient(
                context.resources, ChatGPTSession(context, http), api,
            ).postProcess("synthetic-key", "synthetic source", com.opendictate.app.model.PostProcessingSettings(true),
                emptySet(), chatGPTAccount = "oaiapp_test")
            assertEquals("synthetic source", result.text)
            assertEquals(com.opendictate.app.model.FailureDetails(com.opendictate.app.model.ErrorReason.HTTP, 429), result.failure)
        } finally { cleanup() }
    }

    private fun isolated(prefix: String = "chatgpt-test-${java.util.UUID.randomUUID()}-"): Pair<Context, () -> Unit> {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
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
                    assertEquals("default", payload.getString("service_tier"))
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
                    assertEquals("default", body.getString("service_tier"))
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
