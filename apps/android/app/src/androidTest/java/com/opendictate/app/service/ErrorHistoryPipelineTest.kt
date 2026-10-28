package com.opendictate.app.service

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.model.DictationError
import com.opendictate.app.test.DeliveryFixtureActivity
import java.io.File
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
            assertEquals(DictationError.MissingApiKey, DictationStateBus.state.value.failure)
            assertEquals(context.getString(R.string.error_add_api_key), DictationStateBus.state.value.message)
            context.startService(Intent(context, DictationForegroundService::class.java)
                .setAction(DictationForegroundService.ACTION_RETRY_LAST))
            val retryDeadline = SystemClock.elapsedRealtime() + 5_000
            while (store.getAll().none { it.id > entry.id } && SystemClock.elapsedRealtime() < retryDeadline) SystemClock.sleep(20)
            val retry = store.getAll().first { it.id > entry.id }
            assertEquals(ErrorOperation.RETRY, retry.context.operation)
            assertEquals(TranscriptionModel.ACCURATE, retry.context.mode)
        }
    }

    @Test fun missingRetryRecordingTravelsThroughTheTypedSessionAndSafeDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keys = SecureApiKeyStore(context)
        check(!keys.hasKey()) { "Run in a fresh debug installation" }
        // Any recording in this credential-free test installation is synthetic fixture audio.
        File(context.filesDir, "last-dictation.wav").delete()
        val store = (context.applicationContext as OpenDictateApplication).errorHistoryStore
        val lastId = store.getAll().firstOrNull()?.id ?: 0
        keys.save("instrumentation-placeholder")
        try {
            ActivityScenario.launch(DeliveryFixtureActivity::class.java).use {
                context.startService(Intent(context, DictationForegroundService::class.java)
                    .setAction(DictationForegroundService.ACTION_RETRY_LAST))
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (DictationStateBus.state.value.failure != DictationError.MissingRecording &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                val state = DictationStateBus.state.value
                assertEquals(DictationPhase.ERROR, state.phase)
                assertEquals(DictationError.MissingRecording, state.failure)
                assertEquals(context.getString(R.string.error_no_last_dictation), state.message)
                assertTrue(state.transcript.isEmpty())
                val entry = store.getAll().first { it.id > lastId }
                assertEquals(ErrorReason.MISSING_RECORDING, entry.failure.reason)
                assertEquals(ErrorOperation.RETRY, entry.context.operation)
                assertFalse(entry.diagnosticText().contains("instrumentation-placeholder"))
            }
        } finally {
            context.stopService(Intent(context, DictationForegroundService::class.java))
            keys.clear()
            instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
        }
    }
}
