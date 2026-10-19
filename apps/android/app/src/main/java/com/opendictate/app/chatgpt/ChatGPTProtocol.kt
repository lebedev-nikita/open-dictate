package com.opendictate.app.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64

/** Public SIWC route only. Credentials and payloads must never enter diagnostics. */
object ChatGPTProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    fun form(values: Map<String, String>) = values.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    fun authorization(host: String, redirect: String, state: String, nonce: String, challenge: String,
                      client: String? = null, idToken: String? = null, consent: Boolean = false): String {
        val fields = linkedMapOf("client_id" to (client ?: "dynamic_agent_client"), "ext_agent_host_id" to host,
            "redirect_uri" to redirect, "response_type" to "code", "scope" to SCOPE, "resource" to RESOURCE,
            "state" to state, "nonce" to nonce, "code_challenge_method" to "S256", "code_challenge" to challenge)
        if (client == null) fields["agent_name_hint"] = "OpenDictate"
        if (idToken != null) fields["id_token_hint"] = idToken
        if (consent) fields["prompt"] = "consent"
        return "$ISSUER/api/accounts/authorize?" + form(fields)
    }
    fun callback(target: String, state: String, client: String?): Pair<String, String> {
        val url = URI("http://127.0.0.1$target")
        require(url.path == "/auth/callback")
        val pairs = url.rawQuery.orEmpty().split('&').map {
            val p = it.split('=', limit = 2); require(p.size == 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val fields = pairs.toMap()
        require(fields["state"] == state && fields["error"] == null)
        val issued = fields["client_id"] ?: client
        require(issued != null && issued.startsWith("oaiapp_") && (client == null || client == issued))
        return requireNotNull(fields["code"]).also { require(it.isNotBlank()) } to issued
    }
    fun identity(jwt: String, jwks: JSONObject, client: String, nonce: String, now: Long): JSONObject {
        val parts = jwt.split('.'); require(parts.size == 3 && jwt.length < 32768)
        val header = JSONObject(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256")
        val keys = jwks.getJSONArray("keys")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }
            .single { it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" }
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("n"))),
            BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("e")))))
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
            verify(Base64.getUrlDecoder().decode(parts[2]))
        })
        val claims = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        val aud = claims.get("aud")
        val authorizedParty = if (claims.has("azp")) claims.getString("azp") else null
        require(authorizedParty == null || authorizedParty == client)
        // OIDC permits a single audience as either a string or a one-element array.
        // Keep authorized-party validation for tokens with multiple audiences.
        require(aud == client || aud is JSONArray && (0 until aud.length()).any { aud.getString(it) == client } &&
            (aud.length() == 1 || authorizedParty == client))
        require(claims.getString("iss") == ISSUER && claims.getString("nonce") == nonce && claims.getLong("exp") > now)
        require(claims.getString("sub").isNotBlank())
        return claims
    }
    fun planRequest(body: JSONObject): JSONObject = JSONObject(body.toString()).apply {
        put("store", false); put("stream", true)
        // The ChatGPT plan route validates Fast using its protocol name, priority.
        if (optString("service_tier") == "fast") put("service_tier", "priority")
        val input = get("input")
        if (input is String) put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
    }
    /** Terminal response is authoritative; deltas alone can never produce a successful result. */
    fun completedEvent(data: String): JSONObject? {
        if (data == "[DONE]") return null
        val event = JSONObject(data)
        return when (event.optString("type")) {
            "response.completed" -> event.getJSONObject("response").also { require(it.optString("status") == "completed") }
            "response.failed", "response.incomplete", "error" -> throw ChatGPTException()
            else -> null
        }
    }
}
class ChatGPTException : Exception("ChatGPT unavailable. Check your connection and plan usage. No API fallback was used.")

/** Some plan streams omit output snapshots from response.completed; retain finished items until then. */
class ChatGPTResponseAccumulator {
    private val items = sortedMapOf<Int, JSONObject>()
    private var completed: JSONObject? = null
    private var failed = false

    fun consume(data: String) {
        try {
            check(!failed)
            if (data == "[DONE]") return
            val event = JSONObject(data)
            when (event.optString("type")) {
                "response.output_item.done" -> {
                    require(event.get("output_index") is Number)
                    val index = event.getInt("output_index")
                    require(index in 0..1023 && event.getDouble("output_index") == index.toDouble())
                    items[index] = event.getJSONObject("item")
                }
                "response.completed" -> {
                    val response = requireNotNull(ChatGPTProtocol.completedEvent(data))
                    val output = if (response.isNull("output")) null else response.getJSONArray("output")
                    if ((output == null || output.length() == 0) && items.isNotEmpty()) {
                        response.put("output", JSONArray(items.values.toList()))
                    }
                    completed = response
                }
                "response.failed", "response.incomplete", "error" -> throw ChatGPTException()
            }
        } catch (error: Exception) {
            failed = true; completed = null
            throw error
        }
    }

    fun result(): JSONObject {
        check(!failed)
        return completed ?: throw ChatGPTException()
    }
}
