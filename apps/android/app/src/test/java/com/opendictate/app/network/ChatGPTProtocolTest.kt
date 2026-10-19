package com.opendictate.app.network

import com.opendictate.app.chatgpt.ChatGPTProtocol
import com.opendictate.app.chatgpt.ChatGPTException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGPTProtocolTest {
    @Test fun callbackBindsStateClientAndPathAndRejectsAmbiguity() {
        assertEquals("code" to "oaiapp_test", ChatGPTProtocol.callback("/auth/callback?state=s&code=code&client_id=oaiapp_test", "s", null))
        listOf("/callback?state=s&code=c&client_id=oaiapp_test", "/auth/callback?state=wrong&code=c&client_id=oaiapp_test",
            "/auth/callback?state=s&state=s&code=c&client_id=oaiapp_test", "/auth/callback?state=s&code=c&client_id=oaiapp_other",
            "/auth/callback?state=s&error=access_denied").forEach { target ->
            assertThrows(Exception::class.java) { ChatGPTProtocol.callback(target, "s", "oaiapp_test") }
        }
        val initial = ChatGPTProtocol.authorization("urn:uuid:test", "http://127.0.0.1:123/auth/callback", "s", "n", "pkce")
        assertTrue(initial.contains("client_id=dynamic_agent_client")); assertTrue(initial.contains("agent_name_hint=OpenDictate"))
        assertFalse(ChatGPTProtocol.authorization("host", "redirect", "s", "n", "pkce", "oaiapp_test").contains("agent_name_hint"))
    }
    @Test fun signedIdentityValidatesNonceAudienceIssuerExpiryAndSignature() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val jwks = JSONObject().put("keys", org.json.JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA")
            .put("n", b64(public.modulus.toByteArray())).put("e", b64(public.publicExponent.toByteArray()))))
        fun signed(claims: JSONObject): String {
            val input = b64("{\"alg\":\"RS256\",\"kid\":\"test\"}".toByteArray()) + "." + b64(claims.toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(input.toByteArray()); sign() }
            return input + "." + b64(signature)
        }
        val claims = JSONObject().put("iss", ChatGPTProtocol.ISSUER).put("sub", "synthetic").put("aud", "oaiapp_test").put("nonce", "nonce").put("exp", 2000)
        val jwt = signed(claims)
        assertEquals("synthetic", ChatGPTProtocol.identity(jwt, jwks, "oaiapp_test", "nonce", 1000).getString("sub"))
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(jwt, jwks, "other", "nonce", 1000) }
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(jwt, jwks, "oaiapp_test", "other", 1000) }
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(jwt, jwks, "oaiapp_test", "nonce", 2000) }
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(jwt.dropLast(8) + "AAAAAAAA", jwks, "oaiapp_test", "nonce", 1000) }
        // OpenAI can encode the single client audience as an array without azp.
        claims.put("aud", org.json.JSONArray().put("oaiapp_test"))
        assertEquals("synthetic", ChatGPTProtocol.identity(signed(claims), jwks, "oaiapp_test", "nonce", 1000).getString("sub"))
        claims.put("aud", org.json.JSONArray().put("oaiapp_other"))
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(signed(claims), jwks, "oaiapp_test", "nonce", 1000) }
        claims.put("aud", org.json.JSONArray().put("oaiapp_test").put("oaiapp_other"))
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(signed(claims), jwks, "oaiapp_test", "nonce", 1000) }
        claims.put("azp", "oaiapp_test")
        assertEquals("synthetic", ChatGPTProtocol.identity(signed(claims), jwks, "oaiapp_test", "nonce", 1000).getString("sub"))
        claims.put("aud", "oaiapp_test").put("azp", "oaiapp_other")
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(signed(claims), jwks, "oaiapp_test", "nonce", 1000) }
        claims.remove("azp")
        assertThrows(Exception::class.java) { ChatGPTProtocol.identity(signed(claims.put("iss", "https://example.com")), jwks, "oaiapp_test", "nonce", 1000) }
    }
    @Test fun planBodyRequiresStreamAndTerminalCompletionRatherThanDeltas() {
        val body = textTransformationRequest("Synthetic", "Edit")
        val plan = ChatGPTProtocol.planRequest(body)
        assertTrue(plan.getBoolean("stream")); assertFalse(plan.getBoolean("store"))
        assertEquals(body.getString("input"), plan.getJSONArray("input").getJSONObject(0).getString("content"))
        assertFalse(body.has("stream"))
        assertNull(ChatGPTProtocol.completedEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}"))
        assertThrows(ChatGPTException::class.java) { ChatGPTProtocol.completedEvent("{\"type\":\"response.failed\"}") }
        assertThrows(ChatGPTException::class.java) { ChatGPTProtocol.completedEvent("{\"type\":\"response.incomplete\"}") }
        assertEquals("completed", ChatGPTProtocol.completedEvent("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}")!!.getString("status"))
    }
}
