package com.opendictate.app.network

import arrow.core.Either
import com.opendictate.app.model.DictationError
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionRequestLifecycleTest {
    @Test fun `cancel during response body releases next dictation with 120 second timeout`() =
        cancellationReleasesNextRequest(120)

    @Test fun `cancel during response body releases next dictation without timeout`() =
        cancellationReleasesNextRequest(null)

    private fun cancellationReleasesNextRequest(timeoutSeconds: Int?) = runBlocking {
        cancellationScenario(timeoutSeconds)
    }

    private suspend fun cancellationScenario(timeoutSeconds: Int?) = supervisorScope {
        StalledResponse(sendHeaders = true).use { server ->
            val bodyStarted = CountDownLatch(1)
            val callCancelled = CountDownLatch(1)
            val callFailed = CountDownLatch(1)
            val calls = AtomicInteger()
            val http = OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
                    override fun canceled(call: Call) { callCancelled.countDown() }
                    override fun callFailed(call: Call, ioe: IOException) { callFailed.countDown() }
                })
                .addInterceptor { chain ->
                    if (calls.incrementAndGet() == 1) {
                        chain.proceed(chain.request().newBuilder().url(server.url).build())
                    } else {
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("Synthetic")
                            .body("""{"text":"synthetic result"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }.build()
            val file = File.createTempFile("synthetic-dictation-", ".wav")
            val client = OpenAiTranscriptionClient(httpClient = http)
            val previous = async {
                client.transcribeFile("synthetic-key", "gpt-transcribe", file, emptySet(), "", timeoutSeconds)
            }
            try {
                withContext(Dispatchers.IO) {
                    assertTrue("Response body must be reading before cancellation", bodyStarted.await(3, TimeUnit.SECONDS))
                }
                previous.cancel()
                // DictationForegroundService waits for the cancelled session before starting another.
                val next = async {
                    previous.join()
                    client.transcribeFile("synthetic-key", "gpt-transcribe", file, emptySet(), "", timeoutSeconds)
                }
                assertEquals(Either.Right("synthetic result"), withTimeout(1_000) { next.await() })
                val cancellation = runCatching { previous.await() }.exceptionOrNull()
                assertTrue("Cancellation must not become a network error", cancellation is CancellationException)
                withContext(Dispatchers.IO) {
                    assertTrue("Cancelled request must cancel its HTTP call", callCancelled.await(1, TimeUnit.SECONDS))
                    assertTrue("Cancelled body read must release the transport", callFailed.await(1, TimeUnit.SECONDS))
                }
            } finally {
                server.release.countDown()
                previous.cancel()
                previous.join()
                file.delete()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun `configured timeout remains TIMEOUT before headers and during response body`() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            StalledResponse(sendHeaders).use { server ->
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url).build())
                }.build()
                val file = File.createTempFile("synthetic-timeout-", ".wav")
                try {
                    val result = OpenAiTranscriptionClient(httpClient = http).transcribeFile(
                        "synthetic-key", "gpt-transcribe", file, emptySet(), "", 1,
                    )
                    assertEquals(Either.Left(DictationError.Timeout), result)
                } finally {
                    server.release.countDown()
                    file.delete()
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    private class StalledResponse(sendHeaders: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/transcription"
        val release = CountDownLatch(1)
        private val worker = thread(isDaemon = true, name = "synthetic-transcription-server") {
            server.accept().use { socket ->
                if (sendHeaders) {
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nContent-Type: application/json\r\n\r\n{"
                            .toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
                // Bound even a failing test; no real credentials, audio or OpenAI requests.
                release.await(5, TimeUnit.SECONDS)
            }
        }

        override fun close() {
            release.countDown()
            server.close()
            worker.join(6_000)
        }
    }
}
