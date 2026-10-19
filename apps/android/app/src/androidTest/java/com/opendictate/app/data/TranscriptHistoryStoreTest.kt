package com.opendictate.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.TranscriptionModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TranscriptHistoryStoreTest {
    @Test fun versionOneHistoryMigratesWithoutGuessingDurationsAndNewMetadataSurvivesRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-timings-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE transcript_history (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX history_created_at ON transcript_history(created_at DESC, id DESC)")
                db.execSQL("INSERT INTO transcript_history (text, created_at) VALUES ('Legacy synthetic text', 1)")
                db.version = 1
            }
            val store = TranscriptHistoryStore(context, name)
            val old = store.getAll().single()
            assertEquals("Legacy synthetic text", old.text)
            assertNull(old.mode); assertNull(old.transcriptionMillis); assertNull(old.postProcessingMillis)
            store.add("Accurate synthetic text", 2, TranscriptionModel.ACCURATE, 1250, 450)
            store.add("Live synthetic text", 3, TranscriptionModel.LIVE, 9000, 1000)
            store.close()
            TranscriptHistoryStore(context, name).use { reopened ->
                val rows = reopened.getAll()
                assertEquals(3, rows.size)
                assertEquals(TranscriptionModel.LIVE, rows[0].mode)
                assertNull(rows[0].transcriptionMillis); assertNull(rows[0].postProcessingMillis)
                assertEquals(1250L, rows[1].transcriptionMillis)
                assertEquals(450L, rows[1].postProcessingMillis)
                assertEquals(old, rows[2])
                assertEquals(rows[0].text, reopened.getLatestText())
                assertTrue(reopened.delete(old.id))
                assertEquals(2, reopened.getAll().size)
            }
        } finally { context.deleteDatabase(name) }
    }
}
