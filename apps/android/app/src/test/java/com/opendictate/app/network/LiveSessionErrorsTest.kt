package com.opendictate.app.network

import arrow.core.left
import arrow.core.right
import com.opendictate.app.model.DictationError
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class LiveSessionErrorsTest {
    private val socket = object : WebSocket {
        override fun request() = Request.Builder().url("https://example.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    @Test fun `provider rejection before configuration completes both waiters without provider text`() = runTest {
        for (type in listOf("error", "conversation.item.input_audio_transcription.failed")) {
            val session = session()
            session.onMessage(socket, """{"type":"$type","error":{"message":"synthetic private data"}}""")
            assertEquals(DictationError.Provider().left(), session.ready.await())
            assertEquals(DictationError.Provider().left(), session.completed.await())
            assertFalse(session.completed.await().toString().contains("private"))
        }
    }

    @Test fun `failure after ready remains a typed final failure`() = runTest {
        val session = session()
        session.onMessage(socket, """{"type":"session.updated"}""")
        assertEquals(Unit.right(), session.ready.await())
        session.onFailure(socket, IOException("synthetic private data"), null)
        assertEquals(DictationError.Network.left(), session.completed.await())
    }

    @Test fun `closed stream and malformed events finish as distinct failures`() = runTest {
        val closed = session()
        closed.onClosed(socket, 1006, "synthetic private reason")
        assertEquals(DictationError.StreamIncomplete.left(), closed.completed.await())
        val malformed = session()
        malformed.onMessage(socket, "synthetic private invalid JSON")
        assertEquals(DictationError.InvalidResponse.left(), malformed.ready.await())
        assertEquals(DictationError.InvalidResponse.left(), malformed.completed.await())
    }

    @Test fun `successful live completion keeps cumulative partials and survives socket close`() = runTest {
        val partials = mutableListOf<String>()
        val session = session(partials::add)
        session.onMessage(socket, """{"type":"conversation.item.input_audio_transcription.delta","delta":"synthetic "}""")
        session.onMessage(socket, """{"type":"conversation.item.input_audio_transcription.delta","delta":"text"}""")
        session.onMessage(socket, """{"type":"conversation.item.input_audio_transcription.completed"}""")
        session.onClosed(socket, 1000, "done")
        assertEquals(listOf("synthetic ", "synthetic text", "synthetic text"), partials)
        assertEquals("synthetic text".right(), session.completed.await())
    }

    private fun session(onPartial: (String) -> Unit = {}): OpenAiTranscriptionClient.LiveSession =
        OpenAiTranscriptionClient().LiveSession("synthetic-key", "gpt-live-transcribe", emptySet(), "", onPartial)
}
