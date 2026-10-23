package com.opendictate.app.service

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.model.ErrorReason
import com.opendictate.app.model.ErrorRoute
import com.opendictate.app.model.ErrorStage
import com.opendictate.app.model.ErrorOperation
import com.opendictate.app.model.TranscriptionModel
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ErrorHistoryPipelineTest {
    @Test fun failedStartIsSavedByTheRealServiceWithoutAudioOrNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!SecureApiKeyStore(context).hasKey()) { "Run in a fresh debug installation" }
        val store = (context.applicationContext as OpenDictateApplication).errorHistoryStore
        val lastId = store.getAll().firstOrNull()?.id ?: 0
        ActivityScenario.launch(MainActivity::class.java).use {
            context.startService(Intent(context, DictationForegroundService::class.java)
                .setAction(DictationForegroundService.ACTION_START))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (store.getAll().none { it.id > lastId } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            val entry = store.getAll().first { it.id > lastId }
            assertEquals(ErrorReason.MISSING_API_KEY, entry.failure.reason)
            assertEquals(ErrorStage.RECORDING, entry.context.stage)
            assertEquals(ErrorRoute.DEVICE, entry.context.route)
            assertEquals(entry.context.attemptId, UUID.fromString(entry.context.attemptId).toString())
            assertNull(entry.context.model)
            assertNull(entry.failure.httpStatus)
            assertTrue(entry.diagnosticText().contains("Reason: MISSING_API_KEY"))
            context.startService(Intent(context, DictationForegroundService::class.java)
                .setAction(DictationForegroundService.ACTION_RETRY_LAST))
            val retryDeadline = SystemClock.elapsedRealtime() + 5_000
            while (store.getAll().none { it.id > entry.id } && SystemClock.elapsedRealtime() < retryDeadline) SystemClock.sleep(20)
            val retry = store.getAll().first { it.id > entry.id }
            assertEquals(ErrorOperation.RETRY, retry.context.operation)
            assertEquals(TranscriptionModel.ACCURATE, retry.context.mode)
        }
    }
}
