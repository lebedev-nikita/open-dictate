package com.opendictate.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.opendictate.app.model.TranscriptionModel

data class TranscriptHistoryItem(
    val id: Long,
    val text: String,
    val createdAtEpochMillis: Long,
    val mode: TranscriptionModel? = null,
    val transcriptionMillis: Long? = null,
    val postProcessingMillis: Long? = null,
)

class TranscriptHistoryStore(context: Context, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TEXT TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_MODE TEXT,
                $COLUMN_TRANSCRIPTION_MILLIS INTEGER,
                $COLUMN_POSTPROCESSING_MILLIS INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX history_created_at ON $TABLE_HISTORY($COLUMN_CREATED_AT DESC, $COLUMN_ID DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_MODE TEXT")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_TRANSCRIPTION_MILLIS INTEGER")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_POSTPROCESSING_MILLIS INTEGER")
        }
    }

    fun add(text: String, createdAtEpochMillis: Long = System.currentTimeMillis(),
            mode: TranscriptionModel? = null, transcriptionMillis: Long? = null,
            postProcessingMillis: Long? = null): Long {
        require(transcriptionMillis == null || transcriptionMillis >= 0)
        require(postProcessingMillis == null || postProcessingMillis >= 0)
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Transcript must not be blank" }
        return writableDatabase.insertOrThrow(
            TABLE_HISTORY,
            null,
            ContentValues().apply {
                put(COLUMN_TEXT, normalized)
                put(COLUMN_CREATED_AT, createdAtEpochMillis)
                put(COLUMN_MODE, mode?.name)
                put(COLUMN_TRANSCRIPTION_MILLIS, transcriptionMillis.takeUnless { mode == TranscriptionModel.LIVE })
                put(COLUMN_POSTPROCESSING_MILLIS, postProcessingMillis.takeUnless { mode == TranscriptionModel.LIVE })
            },
        )
    }

    fun getAll(): List<TranscriptHistoryItem> = readableDatabase.query(
        TABLE_HISTORY,
        arrayOf(COLUMN_ID, COLUMN_TEXT, COLUMN_CREATED_AT, COLUMN_MODE, COLUMN_TRANSCRIPTION_MILLIS, COLUMN_POSTPROCESSING_MILLIS),
        null,
        null,
        null,
        null,
        "$COLUMN_CREATED_AT DESC, $COLUMN_ID DESC",
    ).use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
        val textIndex = cursor.getColumnIndexOrThrow(COLUMN_TEXT)
        val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
        val modeIndex = cursor.getColumnIndexOrThrow(COLUMN_MODE)
        val transcriptionIndex = cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPTION_MILLIS)
        val postProcessingIndex = cursor.getColumnIndexOrThrow(COLUMN_POSTPROCESSING_MILLIS)
        fun duration(index: Int) = if (cursor.isNull(index)) null else cursor.getLong(index).takeIf { it >= 0 }
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TranscriptHistoryItem(
                        id = cursor.getLong(idIndex),
                        text = cursor.getString(textIndex),
                        createdAtEpochMillis = cursor.getLong(createdAtIndex),
                        mode = TranscriptionModel.entries.firstOrNull { it.name == cursor.getString(modeIndex) },
                        transcriptionMillis = duration(transcriptionIndex),
                        postProcessingMillis = duration(postProcessingIndex),
                    ),
                )
            }
        }
    }

    fun getLatestText(): String? = readableDatabase.query(
        TABLE_HISTORY,
        arrayOf(COLUMN_TEXT),
        null,
        null,
        null,
        null,
        "$COLUMN_CREATED_AT DESC, $COLUMN_ID DESC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    fun delete(id: Long): Boolean = writableDatabase.delete(
        TABLE_HISTORY,
        "$COLUMN_ID = ?",
        arrayOf(id.toString()),
    ) > 0

    private companion object {
        const val DATABASE_NAME = "transcript-history.db"
        const val DATABASE_VERSION = 2
        const val TABLE_HISTORY = "transcript_history"
        const val COLUMN_ID = "id"
        const val COLUMN_TEXT = "text"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_MODE = "mode"
        const val COLUMN_TRANSCRIPTION_MILLIS = "transcription_millis"
        const val COLUMN_POSTPROCESSING_MILLIS = "postprocessing_millis"
    }
}
