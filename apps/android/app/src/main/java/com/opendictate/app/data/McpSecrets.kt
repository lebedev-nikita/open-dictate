package com.opendictate.app.data

import android.content.Context
import com.opendictate.app.model.McpAuth
import com.opendictate.app.model.McpServer

/** Tokens of MCP servers, each encrypted with its own Android Keystore key and never synced. */
class McpSecrets(private val context: Context) {
    private fun store(serverId: String) = SecureApiKeyStore(context, "_mcp_$serverId")

    fun token(server: McpServer): String? = if (server.auth == McpAuth.NONE) null else store(server.id).get()

    fun hasToken(server: McpServer): Boolean = server.auth == McpAuth.NONE || !store(server.id).get().isNullOrBlank()

    fun save(serverId: String, token: String) = store(serverId).save(token)

    fun clear(serverId: String) = store(serverId).clear()
}
