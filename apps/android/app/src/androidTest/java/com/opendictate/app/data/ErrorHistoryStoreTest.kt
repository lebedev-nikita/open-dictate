package com.opendictate.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.BuildConfig
import com.opendictate.app.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ErrorHistoryStoreTest {
    @Test fun boundedMetadataSurvivesReopenAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "error-history-test-${UUID.randomUUID()}.db"
        val attempt = ErrorContext(UUID.randomUUID().toString(), ErrorStage.POST_PROCESSING, ErrorRoute.CHATGPT,
            ErrorOperation.RETRY, TranscriptionModel.ACCURATE, DiagnosticModel.TEXT)
        try {
            ErrorHistoryStore(context, name).use { store ->
                repeat(105) { index -> store.add(attempt, FailureDetails.http(429), index.toLong(), createdAtEpochMillis = index.toLong()) }
                assertEquals(100, store.getAll().size)
            }
            ErrorHistoryStore(context, name).use { store ->
                val entries = store.getAll()
                assertEquals(104L, entries.first().createdAtEpochMillis)
                assertEquals(5L, entries.last().createdAtEpochMillis)
                assertEquals(attempt, entries.first().context)
                assertEquals(FailureDetails(ErrorReason.HTTP, 429), entries.first().failure)
                val report = entries.first().diagnosticText()
                assertTrue(report.contains(BuildConfig.VERSION_NAME))
                assertTrue(report.contains("Route: CHATGPT"))
                assertTrue(report.contains("Duration: 104 ms"))
                assertTrue(report.contains("HTTP: 429"))
                store.clear()
                assertTrue(store.getAll().isEmpty())
            }
            ErrorHistoryStore(context, name).use { assertTrue(it.getAll().isEmpty()) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun arbitraryTextCannotEnterTheStoredMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "error-history-test-${UUID.randomUUID()}.db"
        try {
            ErrorHistoryStore(context, name).use { store ->
                val attempt = ErrorContext("synthetic-private-text", ErrorStage.TRANSCRIPTION, ErrorRoute.OPENAI_API,
                    ErrorOperation.DICTATION, TranscriptionModel.ACCURATE)
                assertThrows(IllegalArgumentException::class.java) { store.add(attempt, FailureDetails(ErrorReason.UNKNOWN)) }
                store.add(attempt.copy(attemptId = UUID.randomUUID().toString(), model = DiagnosticModel.fromId("synthetic-private-key")),
                    FailureDetails(ErrorReason.NETWORK))
                val entry = store.getAll().single()
                assertNull(entry.durationMillis)
                assertNull(entry.failure.httpStatus)
                assertEquals(DiagnosticModel.OTHER, entry.context.model)
                assertFalse(entry.diagnosticText().contains("synthetic-private"))
            }
        } finally { context.deleteDatabase(name) }
    }
}
