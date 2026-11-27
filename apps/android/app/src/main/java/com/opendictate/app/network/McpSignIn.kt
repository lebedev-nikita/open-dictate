package com.opendictate.app.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.opendictate.app.R
import com.opendictate.app.model.McpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.RegistrationRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.ConnectionBuilder
import okhttp3.Request
import org.json.JSONObject

/**
 * Signs in to an MCP server the way its specification asks of native apps: the server's own page
 * opens in the browser, and its answer returns to a port this app listens on, on the phone itself.
 * AppAuth carries the protocol: PKCE, the state check, client registration and the token exchange.
 */
internal class McpSignIn(private val context: Context) {
    /** Returns the access token. The page waits for the user, so this can take minutes. */
    suspend fun signIn(server: McpServer): Either<McpError, String> = withContext(Dispatchers.IO) {
        val listener = try {
            ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        } catch (error: IOException) {
            return@withContext McpError.Failed(error::class.simpleName).left()
        }
        val connections = ConnectionBuilder { uri ->
            (URL(uri.toString()).openConnection() as HttpURLConnection).also { connection ->
                McpTrust.socketFactory(server)?.let { (connection as? HttpsURLConnection)?.sslSocketFactory = it }
            }
        }
        val auth = AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder(connections).build())
        try {
            either {
                val discovered = discover(server).bind()
                val redirect = Uri.parse("http://$LOOPBACK:${listener.localPort}/callback")
                val registration = RegistrationRequest.Builder(discovered.configuration, listOf(redirect))
                    .setTokenEndpointAuthenticationMethod("none")
                    .setAdditionalParameters(mapOf("client_name" to context.getString(R.string.app_name)))
                    .build()
                val clientId = awaitAuth { callback -> auth.performRegistrationRequest(registration) { response, error -> callback(response?.clientId, error) } }.bind()
                val request = AuthorizationRequest.Builder(discovered.configuration, clientId, ResponseTypeValues.CODE, redirect)
                    .setScope(discovered.scope)
                    // The token is asked for this server alone (RFC 8707), as MCP requires.
                    .setAdditionalParameters(mapOf("resource" to server.url))
                    .build()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, request.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    raise(McpError.Failed("no browser"))
                }
                val callback = awaitRedirect(listener).bind()
                ensureNotNull(callback.getQueryParameter("code")) { McpError.SignInNeeded }
                // AppAuth rejects an answer whose state does not match the request.
                val response = try {
                    AuthorizationResponse.Builder(request).fromUri(callback).build()
                } catch (_: IllegalArgumentException) {
                    raise(McpError.Failed("state"))
                }
                if (response.state != request.state) raise(McpError.Failed("state"))
                val exchange = response.createTokenExchangeRequest(mapOf("resource" to server.url))
                awaitAuth { result -> auth.performTokenRequest(exchange, NoClientAuthentication.INSTANCE) { tokens, error -> result(tokens?.accessToken, error) } }.bind()
            }
        } finally {
            runCatching { listener.close() }
            auth.dispose()
        }
    }

    private class Discovered(val configuration: AuthorizationServiceConfiguration, val scope: String?)

    /** Finds the server's sign-in endpoints: RFC 9728 names the authorization server, RFC 8414 describes it. */
    private fun discover(server: McpServer): Either<McpError, Discovered> = either {
        val url = Uri.parse(server.url)
        val origin = "${url.scheme}://${url.encodedAuthority}"
        val resource = getJson(server, "$origin/.well-known/oauth-protected-resource${url.encodedPath.orEmpty()}")
            ?: getJson(server, "$origin/.well-known/oauth-protected-resource")
        val issuer = resource?.optJSONArray("authorization_servers")?.optString(0)?.ifBlank { null } ?: origin
        val metadata = ensureNotNull(
            getJson(server, "${issuer.trimEnd('/')}/.well-known/oauth-authorization-server")
                ?: getJson(server, "${issuer.trimEnd('/')}/.well-known/openid-configuration"),
        ) { McpError.Unreachable(null) }
        val authorize = metadata.optString("authorization_endpoint").ifBlank { raise(McpError.Failed("authorization_endpoint")) }
        val token = metadata.optString("token_endpoint").ifBlank { raise(McpError.Failed("token_endpoint")) }
        // Without registration the app would need a client id the user cannot supply.
        val register = metadata.optString("registration_endpoint").ifBlank { raise(McpError.Failed("registration_endpoint")) }
        val scopes = resource?.optJSONArray("scopes_supported") ?: metadata.optJSONArray("scopes_supported")
        Discovered(
            AuthorizationServiceConfiguration(Uri.parse(authorize), Uri.parse(token), Uri.parse(register)),
            scopes?.let { list -> (0 until list.length()).joinToString(" ") { list.optString(it) } }?.ifBlank { null },
        )
    }

    private fun getJson(server: McpServer, address: String): JSONObject? = try {
        McpTrust.httpClient(server).newCall(Request.Builder().url(address).header("Accept", "application/json").build()).execute().use { response ->
            if (response.isSuccessful) runCatching { JSONObject(response.body.string()) }.getOrNull() else null
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Waits for the browser to come back; closing the listener is the only way to end a blocked wait. */
    private suspend fun awaitRedirect(listener: ServerSocket): Either<McpError, Uri> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { listener.close() } }
        thread(name = "mcp-sign-in", isDaemon = true) {
            val answer = acceptRedirect(listener)
            if (continuation.isActive) continuation.resume(answer)
        }
    }

    /** Answers the browser with a short page and returns the address it used. */
    private fun acceptRedirect(listener: ServerSocket): Either<McpError, Uri> = try {
        listener.soTimeout = REDIRECT_TIMEOUT_MS
        listener.accept().use { socket ->
            socket.soTimeout = 5_000
            val path = socket.getInputStream().bufferedReader().readLine()?.split(" ")?.getOrNull(1)
            val page = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                "<body style=\"font-family:sans-serif;padding:2em\">${context.getString(R.string.mcp_sign_in_page)}</body>"
            val bytes = page.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
            if (path == null) McpError.Failed("redirect").left() else Uri.parse("http://$LOOPBACK:${listener.localPort}$path").right()
        }
    } catch (_: SocketTimeoutException) {
        McpError.SignInNeeded.left()
    } catch (error: IOException) {
        McpError.Failed(error::class.simpleName).left()
    }

    private suspend fun awaitAuth(start: ((String?, AuthorizationException?) -> Unit) -> Unit): Either<McpError, String> =
        suspendCancellableCoroutine { continuation ->
            try {
                start { value, error ->
                    if (!continuation.isActive) return@start
                    continuation.resume(when {
                        value != null -> value.right()
                        // An authorization code lives for a minute; a late exchange is refused.
                        error?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> McpError.SignInNeeded.left()
                        error?.cause is IOException -> McpError.Unreachable(null).left()
                        else -> McpError.Failed(error?.error ?: error?.errorDescription).left()
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resume(McpError.Failed(error::class.simpleName).left())
            }
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val REDIRECT_TIMEOUT_MS = 10 * 60 * 1000
    }
}
