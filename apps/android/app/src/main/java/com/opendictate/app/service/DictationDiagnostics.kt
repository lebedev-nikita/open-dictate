package com.opendictate.app.service

import android.os.SystemClock
import com.opendictate.app.data.ErrorHistoryStore
import com.opendictate.app.model.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One attempt spans capture, requests and delivery, including an explicit retry. */
internal class DictationDiagnostics(
    private val store: ErrorHistoryStore,
    mode: TranscriptionModel,
    operation: ErrorOperation,
) {
    var context = ErrorContext(UUID.randomUUID().toString(), ErrorStage.RECORDING, ErrorRoute.DEVICE, operation, mode)
        private set
    private var started = SystemClock.elapsedRealtime()

    fun stage(stage: ErrorStage, route: ErrorRoute, modelId: String? = null) {
        context = context.copy(stage = stage, route = route, model = modelId?.let(DiagnosticModel::fromId))
        started = SystemClock.elapsedRealtime()
    }

    suspend fun record(failure: FailureDetails) {
        val snapshot = context
        val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
        // Persistence must neither block the UI nor disappear when this service finishes.
        withContext(NonCancellable + Dispatchers.IO) { runCatching { store.add(snapshot, failure, duration) } }
    }
}
