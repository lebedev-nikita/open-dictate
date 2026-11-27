package com.opendictate.app.network

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.opendictate.app.model.McpServer
import com.opendictate.app.model.McpTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** Why a request to an MCP server did not succeed. Details never contain tokens or arguments. */
sealed interface McpError {
    /** The server refused the stored credentials, or there are none yet. */
    data object SignInNeeded : McpError
    data class Unreachable(val detail: String?) : McpError
    data class Failed(val detail: String?) : McpError
}

data class McpCallResult(val text: String, val isError: Boolean)

/**
 * Trust for one server's connections. With [McpServer.trustDeviceCertificates] the certificate
 * authorities the user installed on the device are accepted next to the system ones, as the browser
 * accepts them; every other connection of the app keeps the system list.
 */
internal object McpTrust {
    private val deviceTrust: Pair<SSLSocketFactory, X509TrustManager> by lazy {
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(KeyStore.getInstance("AndroidCAStore").apply { load(null) }) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory to manager
    }

    fun socketFactory(server: McpServer): SSLSocketFactory? = if (server.trustDeviceCertificates) deviceTrust.first else null

    fun httpClient(server: McpServer): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply { if (server.trustDeviceCertificates) sslSocketFactory(deviceTrust.first, deviceTrust.second) }
        .build()
}

/** Lists and calls the tools of an MCP server over Streamable HTTP, one short session per request. */
internal class McpClient {
    suspend fun listTools(server: McpServer, token: String?): Either<McpError, List<McpTool>> = session(server, token) { client ->
        buildList {
            var cursor: String? = null
            // A server pages its list; a few pages are more tools than a voice command can use.
            repeat(MAX_PAGES) {
                val page = client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
                page.tools.forEach { tool ->
                    add(McpTool(tool.name, tool.description ?: tool.title.orEmpty(), McpJson.encodeToString(ToolSchema.serializer(), tool.inputSchema)))
                }
                cursor = page.nextCursor ?: return@buildList
            }
        }
    }

    suspend fun callTool(server: McpServer, token: String?, tool: String, argumentsJson: String): Either<McpError, McpCallResult> =
        session(server, token) { client ->
            val arguments = McpJson.parseToJsonElement(argumentsJson).jsonObject
            val result = client.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = arguments)))
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                .ifBlank { result.structuredContent?.toString().orEmpty() }
            McpCallResult(text.trim(), result.isError == true)
        }

    private suspend fun <T> session(server: McpServer, token: String?, block: suspend (Client) -> T): Either<McpError, T> =
        withContext(Dispatchers.IO) {
            val http = HttpClient(OkHttp) {
                engine { preconfigured = McpTrust.httpClient(server) }
                install(SSE)
            }
            val client = Client(Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))
            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    client.connect(StreamableHttpClientTransport(client = http, url = server.url) {
                        token?.let { header("Authorization", "Bearer $it") }
                    })
                    block(client).right()
                }
            } catch (error: TimeoutCancellationException) {
                McpError.Unreachable(null).left()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                classify(error).left()
            } finally {
                runCatching { client.close() }
                http.close()
            }
        }

    private fun classify(error: Throwable): McpError {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val status = causes.filterIsInstance<StreamableHttpError>().firstOrNull()?.code
        return when {
            status == 401 || status == 403 -> McpError.SignInNeeded
            status != null && status > 0 -> McpError.Failed("HTTP $status")
            causes.any { it is IOException } -> McpError.Unreachable(causes.first { it is IOException }::class.simpleName)
            else -> McpError.Failed(error::class.simpleName)
        }
    }

    private companion object {
        const val CLIENT_NAME = "OpenDictate"
        const val CLIENT_VERSION = "1"
        const val REQUEST_TIMEOUT_MS = 45_000L
        const val MAX_PAGES = 10
    }
}
