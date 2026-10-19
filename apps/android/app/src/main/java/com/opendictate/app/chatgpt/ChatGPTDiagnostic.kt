package com.opendictate.app.chatgpt

import org.json.JSONObject

/** Temporary diagnostic: only fixed labels and booleans may leave request memory. */
internal fun chatGPTDiagnostic(error: JSONObject?): String {
    val codes = setOf("invalid_request_error", "unsupported_value", "unsupported_parameter", "invalid_value",
        "bad_request", "rate_limit_exceeded", "insufficient_quota", "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_usage_unavailable", "permission_denied", "invalid_api_key", "model_not_found", "server_error")
    val parameters = setOf("service_tier", "reasoning", "reasoning.effort", "text", "text.format", "model", "input", "store", "stream")
    fun label(key: String, allowed: Set<String>) = error?.optString(key)?.takeIf { it in allowed } ?: "other"
    val message = error?.optString("message").orEmpty().lowercase(java.util.Locale.ROOT)
    val unsupported = listOf("not supported", "unsupported", "not allowed", "not available", "unrecognized", "unknown").any(message::contains)
    return "type=${label("type", codes)} code=${label("code", codes)} param=${label("param", parameters)} " +
        "mentionsTier=${message.contains("service_tier") || message.contains("service") && message.contains("tier")} " +
        "mentionsFast=${message.contains("fast")} mentionsPriority=${message.contains("priority")} unsupported=$unsupported"
}
