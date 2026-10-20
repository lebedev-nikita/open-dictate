package com.opendictate.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.model.TextProcessingServiceTier
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TranscriptHistoryStoreTest {
    @Test fun versionOneHistoryMigratesWithoutGuessingMetadata() = verifyMigration(1)

    @Test fun versionTwoHistoryKeepsTimingsWithoutGuessingModels() = verifyMigration(2)

    private fun verifyMigration(version: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-timings-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE transcript_history (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX history_created_at ON transcript_history(created_at DESC, id DESC)")
                db.execSQL("INSERT INTO transcript_history (text, created_at) VALUES ('Legacy synthetic text', 1)")
                if (version == 2) {
                    db.execSQL("ALTER TABLE transcript_history ADD COLUMN mode TEXT")
                    db.execSQL("ALTER TABLE transcript_history ADD COLUMN transcription_millis INTEGER")
                    db.execSQL("ALTER TABLE transcript_history ADD COLUMN postprocessing_millis INTEGER")
                    db.execSQL("UPDATE transcript_history SET mode = 'ACCURATE', transcription_millis = 1200, postprocessing_millis = 300")
                }
                db.version = version
            }
            val store = TranscriptHistoryStore(context, name)
            val old = store.getAll().single()
            assertEquals("Legacy synthetic text", old.text)
            if (version == 1) {
                assertNull(old.mode); assertNull(old.transcriptionMillis); assertNull(old.postProcessingMillis)
            } else {
                assertEquals(TranscriptionModel.ACCURATE, old.mode)
                assertEquals(1200L, old.transcriptionMillis); assertEquals(300L, old.postProcessingMillis)
            }
            assertNull(old.transcriptionModelId); assertNull(old.postProcessingModelId); assertNull(old.postProcessingServiceTier)
            store.add("Accurate synthetic text", 2, TranscriptionModel.ACCURATE, 1250, 450,
                "gpt-transcribe-synthetic-version", "gpt-6-luna", TextProcessingServiceTier.FAST)
            store.add("Live synthetic text", 3, TranscriptionModel.LIVE, 9000, 1000,
                "gpt-live-transcribe-synthetic-version", "gpt-6-luna", TextProcessingServiceTier.FAST)
            store.close()
            TranscriptHistoryStore(context, name).use { reopened ->
                val rows = reopened.getAll()
                assertEquals(3, rows.size)
                assertEquals(TranscriptionModel.LIVE, rows[0].mode)
                assertNull(rows[0].transcriptionMillis); assertNull(rows[0].postProcessingMillis)
                assertEquals("gpt-live-transcribe-synthetic-version", rows[0].transcriptionModelId)
                assertNull(rows[0].postProcessingModelId); assertNull(rows[0].postProcessingServiceTier)
                assertEquals(1250L, rows[1].transcriptionMillis)
                assertEquals(450L, rows[1].postProcessingMillis)
                assertEquals("gpt-transcribe-synthetic-version", rows[1].transcriptionModelId)
                assertEquals("gpt-6-luna", rows[1].postProcessingModelId)
                assertEquals(TextProcessingServiceTier.FAST, rows[1].postProcessingServiceTier)
                assertEquals(old, rows[2])
                assertEquals(rows[0].text, reopened.getLatestText())
                assertTrue(reopened.delete(old.id))
                assertEquals(2, reopened.getAll().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun newHistoryPreservesStandardAndDisabledPostProcessing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-models-${UUID.randomUUID()}.db"
        try {
            TranscriptHistoryStore(context, name).use { store ->
                store.add("Standard processing", 1, TranscriptionModel.ACCURATE, 1000, 200,
                    "gpt-transcribe", "gpt-6-luna", TextProcessingServiceTier.STANDARD)
                store.add("Processing disabled", 2, TranscriptionModel.ACCURATE, 1000,
                    transcriptionModelId = "gpt-transcribe")
            }
            TranscriptHistoryStore(context, name).use { store ->
                val rows = store.getAll()
                assertEquals("gpt-transcribe", rows[0].transcriptionModelId)
                assertNull(rows[0].postProcessingModelId); assertNull(rows[0].postProcessingServiceTier)
                assertEquals("gpt-6-luna", rows[1].postProcessingModelId)
                assertEquals(TextProcessingServiceTier.STANDARD, rows[1].postProcessingServiceTier)
            }
        } finally { context.deleteDatabase(name) }
    }
}
