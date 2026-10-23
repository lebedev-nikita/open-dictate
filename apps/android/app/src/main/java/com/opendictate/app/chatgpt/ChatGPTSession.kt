package com.opendictate.app.chatgpt

import android.content.Context
import com.opendictate.app.model.FailureDetails
import com.opendictate.app.model.ErrorReason
import com.opendictate.app.data.SecureApiKeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One application-owned credential broker serializes rotating refresh tokens. No Drive export. */
class ChatGPTSession(context: Context, httpClient: OkHttpClient? = null) {
    data class Account(val id: String, val label: String)
    data class State(val connected: Boolean = false, val email: String = "", val busy: Boolean = false,
                     val failed: Boolean = false, val welcome: Boolean = false, val revocationUnconfirmed: Boolean = false, val accounts: List<Account> = emptyList())
    private val store = SecureApiKeyStore(context, "_chatgpt")
    private val preferences = context.getSharedPreferences("chatgpt_host", Context.MODE_PRIVATE)
    // An unreadable store is not an empty journal. Retry it before using or changing credentials.
    private var loadedRecords = runCatching { JSONObject(store.get(strict = true) ?: "{}") }.getOrNull()
    private val records: JSONObject get() = loadedRecords ?: throw storageFailure()
    private var unsaved = false
    private var recoveredAccount: String? = null
    @Volatile private var selected: String? = if (preferences.getBoolean("use_plan", false)) loadedRecords?.optString("active")?.takeIf { it.isNotBlank() } ?: UNAVAILABLE_ACCOUNT else null
    private val mutableState = MutableStateFlow(status())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val http = httpClient ?: OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build()
    private var socket: ServerSocket? = null
    private fun status(): State {
        val saved = loadedRecords
        val accounts = saved?.keys()?.asSequence()?.mapNotNull { id ->
            saved.optJSONObject(id)?.takeIf { it.optString("sub").isNotBlank() }?.let {
                Account(id, it.optString("email").ifBlank { "ChatGPT" } + " · " + id.takeLast(6))
            }
        }?.sortedBy { it.label }?.toList().orEmpty()
        val id = activeAccount()
        val record = id?.let { saved?.optJSONObject(it) }
        val usable = record != null && record.optString("access_token").isNotBlank() &&
            record.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")
        return State(connected = id != null, email = record?.optString("email").orEmpty(), failed = id != null && !usable, accounts = accounts)
    }
    fun activeAccount(): String? = selected
    /** Called under mutex. Failed writes keep the latest rotating token in memory for a durable retry. */
    private suspend fun save() {
        unsaved = true
        try { withContext(Dispatchers.IO) { store.save(records.toString(), durable = true) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw storageFailure() }
        unsaved = false
        mutableState.value = status()
    }
    private suspend fun loadRecords() {
        if (loadedRecords == null) {
            val saved = try { withContext(Dispatchers.IO) { JSONObject(store.get(strict = true) ?: "{}") } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw storageFailure() }
            loadedRecords = saved
            if (selected == UNAVAILABLE_ACCOUNT) {
                recoveredAccount = saved.optString("active").takeIf { it.isNotBlank() }
                selected = recoveredAccount ?: UNAVAILABLE_ACCOUNT
            }
            mutableState.value = status()
        }
        if (unsaved) save()
    }
    private fun storageFailure() = ChatGPTException(FailureDetails(ErrorReason.CREDENTIAL_STORAGE))
    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun dismissWelcome() { preferences.edit().putBoolean("welcomed", true).apply(); mutableState.value = mutableState.value.copy(welcome = false) }
    fun cancel() { runCatching { socket?.close() }; socket = null }

    suspend fun connect(hasTranscriptionKey: () -> Boolean, account: String? = "__last__", openBrowser: (String) -> Unit) {
        if (!hasTranscriptionKey() || mutableState.value.busy) return
        mutableState.value = status().copy(busy = true)
        try {
            mutex.withLock { loadRecords() }
            val host = preferences.getString("host", null) ?: ("urn:uuid:" + UUID.randomUUID()).also {
                preferences.edit().putString("host", it).commit()
            }
            // Keep the last registration after sign-out; reauthorization stays bound to its verified subject.
            val previousID = (if (account == "__last__") records.optString("last") else account)?.takeIf { records.has(it) }
            val previous = previousID?.let { records.getJSONObject(it) }
            val state = random(); val nonce = random(); val verifier = random()
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); socket = listener
            val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
            val deadline = CoroutineScope(currentCoroutineContext()).launch { delay(600_000); runCatching { listener.close() } }
            val result = try {
                openBrowser(ChatGPTProtocol.authorization(host, redirect, state, nonce, challenge, previousID, previous?.optString("id_token")?.takeIf { it.isNotBlank() }, previous != null && !previous.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")))
                withContext(Dispatchers.IO) {
                    listener.soTimeout = 600_000
                    var callback: Pair<String, String>? = null
                    repeat(16) {
                        if (callback != null) return@repeat
                        listener.accept().use { incoming ->
                            incoming.soTimeout = 2000
                            val header = StringBuilder()
                            while (header.length < 8192 && !header.endsWith("\r\n\r\n")) {
                                val byte = incoming.getInputStream().read(); if (byte < 0) break; header.append(byte.toChar())
                            }
                            val first = header.toString().substringBefore("\r\n").split(' ')
                            if (first.size != 3 || first[0] != "GET") return@use
                            val target = java.net.URI("http://127.0.0.1${first[1]}")
                            val states = target.rawQuery.orEmpty().split('&').mapNotNull {
                                val pair = it.split('=', limit = 2)
                                if (pair.size == 2 && pair[0] == "state") java.net.URLDecoder.decode(pair[1], "UTF-8") else null
                            }
                            if (target.path != "/auth/callback" || states != listOf(state)) return@use
                            val accepted = runCatching { ChatGPTProtocol.callback(first[1], state, previousID) }
                            val message = "Return to OpenDictate. You may close this tab."
                            incoming.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${message.toByteArray(Charsets.UTF_8).size}\r\n\r\n" + message).toByteArray(Charsets.UTF_8))
                            callback = accepted.getOrThrow()
                        }
                    }
                    callback ?: throw ChatGPTException()
                }
            } finally { deadline.cancel(); listener.close(); socket = null }
            currentCoroutineContext().ensureActive()
            if (!hasTranscriptionKey()) throw ChatGPTException()
            val (code, clientID) = result
            // Retain issued registration even if the exchange needs a fresh authorization code.
            mutex.withLock {
                if (!records.has(clientID)) records.put(clientID, JSONObject())
                records.put("last", clientID); save()
            }
            val tokens = request(ChatGPTProtocol.TOKEN, fields = mapOf("client_id" to clientID, "code" to code,
                "code_verifier" to verifier, "redirect_uri" to redirect, "grant_type" to "authorization_code", "resource" to ChatGPTProtocol.RESOURCE))
            val jwks = request("https://auth.openai.com/.well-known/jwks.json")
            val identity = ChatGPTProtocol.identity(tokens.getString("id_token"), jwks, clientID, nonce, System.currentTimeMillis() / 1000)
            if (previous?.optString("sub").orEmpty().isNotEmpty()) require(identity.getString("sub") == previous!!.getString("sub"))
            if (!tokens.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) {
                tokens.put("sub", identity.getString("sub")).put("email", identity.optString("email"))
                mutex.withLock { records.put(clientID, tokens); save() }
                throw ChatGPTException()
            }
            require(tokens.optString("token_type").equals("Bearer", true) && tokens.getLong("expires_in") in 1..86400)
            require(tokens.getString("access_token").isNotBlank() && tokens.getString("refresh_token").isNotBlank())
            val models = request("${ChatGPTProtocol.RESOURCE}/models", bearer = tokens.getString("access_token")).getJSONArray("models")
            require((0 until models.length()).any { models.getJSONObject(it).let { m -> m.optString("slug") == "gpt-6-luna" && m.optString("visibility") == "list" } })
            currentCoroutineContext().ensureActive()
            if (!hasTranscriptionKey()) throw ChatGPTException()
            mutex.withLock {
                tokens.put("sub", identity.getString("sub")).put("email", identity.optString("email"))
                    .put("expires_at", System.currentTimeMillis() / 1000 + tokens.getLong("expires_in"))
                records.put(clientID, tokens); records.put("active", clientID); save()
                preferences.edit().putBoolean("use_plan", true).commit(); selected = clientID
                mutableState.value = status().copy(welcome = !preferences.getBoolean("welcomed", false))
            }
        } catch (error: CancellationException) { mutableState.value = status(); throw error }
        catch (_: Exception) { mutableState.value = status().copy(failed = true) }
        finally { cancel() }
    }
    suspend fun disconnect() {
        mutableState.value = status().copy(busy = true)
        var failed = false
        mutex.withLock {
            // Signing out locally remains possible when the store is temporarily locked.
            // Never replace an unreadable journal with an empty one.
            try { loadRecords() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            val id = activeAccount()?.takeIf { loadedRecords?.has(it) == true }
            if (id != null) {
                val record = records.getJSONObject(id)
                try { request("https://auth.openai.com/api/accounts/oauth/revoke", fields = mapOf("token" to record.getString("refresh_token"), "token_type_hint" to "refresh_token", "client_id" to id), empty = true) }
                catch (_: Exception) { failed = true }
                listOf("access_token", "refresh_token", "id_token").forEach(record::remove)
                records.remove("active"); save()
            }
            preferences.edit().putBoolean("use_plan", false).commit(); selected = null
        }
        mutableState.value = status().copy(revocationUnconfirmed = failed)
    }
    /** Refresh only the session captured by the caller. Never substitute an API key. */
    private suspend fun token(id: String): String = mutex.withLock {
        loadRecords()
        val account = if (id == UNAVAILABLE_ACCOUNT) recoveredAccount else id
        val record = account?.let { records.optJSONObject(it) } ?: throw ChatGPTException(FailureDetails(ErrorReason.AUTHENTICATION))
        if (record.optString("access_token").isBlank() || !record.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) {
            throw ChatGPTException(FailureDetails(ErrorReason.AUTHENTICATION))
        }
        if (record.optLong("expires_at") <= System.currentTimeMillis() / 1000 + 60) {
            currentCoroutineContext().ensureActive()
            // The server may already have consumed a rotating token when dictation is cancelled.
            // Finish the bounded refresh and its durable write, but never continue inference afterwards.
            withContext(NonCancellable + Dispatchers.IO) {
                val renewed = try { request(ChatGPTProtocol.TOKEN, fields = mapOf("grant_type" to "refresh_token", "client_id" to requireNotNull(account),
                    "refresh_token" to record.getString("refresh_token"), "resource" to ChatGPTProtocol.RESOURCE)) }
                catch (_: ChatGPTReauthentication) {
                    listOf("access_token", "refresh_token", "id_token").forEach(record::remove); save()
                    throw ChatGPTException(FailureDetails(ErrorReason.AUTHENTICATION))
                }
                require(renewed.optString("access_token").isNotBlank() && renewed.optString("refresh_token").isNotBlank())
                require(renewed.optString("scope").split(' ').contains("chatgpt.tokens.use.direct"))
                require(renewed.optString("token_type").equals("Bearer", true) && renewed.getLong("expires_in") in 1..86400)
                record.put("access_token", renewed.getString("access_token")).put("refresh_token", renewed.getString("refresh_token"))
                    .put("scope", renewed.getString("scope")).put("expires_at", System.currentTimeMillis() / 1000 + renewed.getLong("expires_in"))
                save()
            }
        }
        currentCoroutineContext().ensureActive()
        record.getString("access_token")
    }
    suspend fun response(id: String, body: JSONObject): JSONObject {
        try {
            require(body.optString("model") == "gpt-6-luna")
            val response = request("${ChatGPTProtocol.RESOURCE}/responses", bearer = token(id), body = ChatGPTProtocol.planRequest(body))
            mutableState.value = mutableState.value.copy(failed = false)
            return response
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.value = status().copy(failed = true); throw ChatGPTException(FailureDetails.from(error)) }
    }
    private suspend fun request(url: String, bearer: String? = null, fields: Map<String, String>? = null,
                                body: JSONObject? = null, empty: Boolean = false): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", "OpenDictate-Android")
        if (bearer != null) request.header("Authorization", "Bearer $bearer")
        if (fields != null) request.post(ChatGPTProtocol.form(fields).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(ChatGPTException(FailureDetails.from(e))) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) {
                            if (url == ChatGPTProtocol.TOKEN) {
                                val source = it.body.source(); source.request(65537)
                                if (source.buffer.size <= 65536) {
                                    val error = runCatching { JSONObject(source.readUtf8()).optString("error") }.getOrNull()
                                    if (error in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) throw ChatGPTReauthentication()
                                }
                            }
                            throw ChatGPTException(FailureDetails.http(it.code))
                        }
                        if (body != null) {
                            val source = it.body.source(); val data = StringBuilder(); var size = 0; val stream = ChatGPTResponseAccumulator()
                            while (!source.exhausted()) {
                                val line = source.readUtf8LineStrict(1_048_576); size += line.length; require(size <= 2_097_152)
                                if (line.isEmpty()) {
                                    if (data.isNotEmpty()) { stream.consume(data.toString()); data.setLength(0) }
                                } else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                            }
                            stream.result()
                        } else if (empty) JSONObject() else {
                            val source = it.body.source(); source.request(2_097_153)
                            require(source.buffer.size <= 2_097_152)
                            val bytes = source.readByteArray()
                            require(bytes.size <= 2_097_152); JSONObject(String(bytes, Charsets.UTF_8))
                        }
                    }
                }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(if (it is ChatGPTReauthentication) it else ChatGPTException(if (it is IllegalArgumentException) FailureDetails(ErrorReason.INVALID_RESPONSE) else FailureDetails.from(it))) })
            }
        })
    }

    private companion object { const val UNAVAILABLE_ACCOUNT = "unavailable" }
}

private class ChatGPTReauthentication : Exception()
