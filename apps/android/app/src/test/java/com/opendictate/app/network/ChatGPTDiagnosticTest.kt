package com.opendictate.app.network

import com.opendictate.app.chatgpt.chatGPTDiagnostic
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGPTDiagnosticTest {
    @Test fun diagnosticsNeverEchoProviderFieldsOrMessages() {
        val secret = "sensitive-provider-value"
        val result = chatGPTDiagnostic(JSONObject().put("type", secret).put("code", secret)
            .put("param", secret).put("message", secret))
        assertFalse(result.contains(secret))
        assertEquals("type=other code=other param=other mentionsTier=false mentionsFast=false mentionsPriority=false unsupported=false invalid=false literalError=false mentionsDefault=false mentionsUsage=false detailEnvelope=false errorEnvelope=false", result)
    }
    @Test fun detailEnvelopeCanIdentifyTierValidationWithoutEchoingItsText() {
        val result = chatGPTDiagnostic(JSONObject().put("detail", "Invalid service_tier fast; use default or priority; sensitive-provider-value"))
        assertTrue(result.contains("detailEnvelope=true")); assertTrue(result.contains("mentionsTier=true"))
        assertTrue(result.contains("mentionsPriority=true")); assertTrue(result.contains("invalid=true"))
        assertFalse(result.contains("sensitive-provider-value"))
    }
    @Test fun unsupportedTierIsClassifiedWithoutReturningRawMessage() {
        val message = "Unsupported service_tier fast; sensitive-provider-value"
        val result = chatGPTDiagnostic(JSONObject().put("type", "invalid_request_error").put("param", "service_tier").put("message", message))
        assertTrue(result.contains("type=invalid_request_error")); assertTrue(result.contains("param=service_tier"))
        assertTrue(result.contains("mentionsFast=true")); assertTrue(result.contains("unsupported=true"))
        assertFalse(result.contains(message)); assertFalse(result.contains("sensitive-provider-value"))
    }
}
