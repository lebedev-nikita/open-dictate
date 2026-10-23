package com.opendictate.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.opendictate.app.BuildConfig
import com.opendictate.app.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ErrorHistoryEntry(
    val id: Long,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val context: ErrorContext,
    val failure: FailureDetails,
    val durationMillis: Long?,
) {
    /** Fixed field names and enum values make this safe to paste into a public issue. */
    fun diagnosticText(): String = buildString {
        appendLine("OpenDictate Android $appVersion ($appVersionCode)")
        appendLine("Time: ${Instant.ofEpochMilli(createdAtEpochMillis)}")
        appendLine("Attempt: ${context.attemptId}")
        appendLine("Operation: ${context.operation}")
        appendLine("Mode: ${context.mode}")
        appendLine("Stage: ${context.stage}")
        appendLine("Route: ${context.route}")
        context.model?.let { appendLine("Model: ${it.id}") }
        appendLine("Reason: ${failure.reason}")
        failure.httpStatus?.let { appendLine("HTTP: $it") }
        durationMillis?.let { appendLine("Duration: $it ms") }
    }.trimEnd()
}

/** Separate, bounded app-private metadata. No text, audio, exception messages or provider payloads. */
class ErrorHistoryStore(context: Context, databaseName: String = "error-history.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE errors (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
            app_version TEXT NOT NULL, app_version_code INTEGER NOT NULL, attempt_id TEXT NOT NULL,
            stage TEXT NOT NULL, route TEXT NOT NULL, operation TEXT NOT NULL, mode TEXT NOT NULL,
            model TEXT, reason TEXT NOT NULL, http_status INTEGER, duration_ms INTEGER
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun add(context: ErrorContext, failure: FailureDetails, durationMillis: Long? = null,
            createdAtEpochMillis: Long = System.currentTimeMillis()) {
        // Attempt IDs are generated locally, never supplied by providers or text fields.
        require(UUID.fromString(context.attemptId).toString() == context.attemptId)
        require(durationMillis == null || durationMillis >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("errors", null, ContentValues().apply {
                put("created_at", createdAtEpochMillis)
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_version_code", BuildConfig.VERSION_CODE)
                put("attempt_id", context.attemptId)
                put("stage", context.stage.name); put("route", context.route.name)
                put("operation", context.operation.name); put("mode", context.mode.name)
                put("model", context.model?.name); put("reason", failure.reason.name)
                put("http_status", failure.httpStatus); put("duration_ms", durationMillis)
            })
            db.execSQL("DELETE FROM errors WHERE id NOT IN (SELECT id FROM errors ORDER BY id DESC LIMIT $MAX_ENTRIES)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        mutableRevision.update { it + 1 }
    }

    @Synchronized
    fun getAll(): List<ErrorHistoryEntry> = readableDatabase.query(
        "errors", null, null, null, null, null, "id DESC",
    ).use { cursor ->
        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        fun optionalLong(name: String): Long? = cursor.getColumnIndexOrThrow(name).let {
            if (cursor.isNull(it)) null else cursor.getLong(it)
        }
        buildList {
            while (cursor.moveToNext()) add(ErrorHistoryEntry(
                id = long("id"), createdAtEpochMillis = long("created_at"), appVersion = string("app_version"),
                appVersionCode = long("app_version_code").toInt(),
                context = ErrorContext(string("attempt_id"), ErrorStage.valueOf(string("stage")),
                    ErrorRoute.valueOf(string("route")), ErrorOperation.valueOf(string("operation")),
                    TranscriptionModel.valueOf(string("mode")), string("model")?.let(DiagnosticModel::valueOf)),
                failure = FailureDetails(ErrorReason.valueOf(string("reason")), optionalLong("http_status")?.toInt()),
                durationMillis = optionalLong("duration_ms"),
            ))
        }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("errors", null, null)
        mutableRevision.update { it + 1 }
    }

    companion object { const val MAX_ENTRIES = 100 }
}
