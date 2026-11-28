package com.opendictate.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ExternalDictationOutcome
import com.opendictate.app.model.OnDeviceModels
import com.opendictate.app.service.DictationForegroundService
import com.opendictate.app.service.DictationPhase
import com.opendictate.app.service.DictationStateBus
import com.opendictate.app.service.ExternalDictationBus
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records one dictation whose text goes somewhere other than the focused field. The visible panel
 * lets Android 14+ start the microphone service.
 */
abstract class DictationPanelActivity : ComponentActivity() {
    protected val settings by lazy { SettingsStore(this) }
    private var requestId: String? = null
    private var startWhenResumed = false
    private var finished = false
    /** The outcome is delivered and the panel waits for work that needs it in front. */
    private var holding by mutableStateOf(false)

    /**
     * Hands the outcome to whoever asked for the dictation; called once, before the panel closes.
     * The panel stays open, showing progress, until the returned job finishes.
     */
    protected abstract fun deliver(outcome: ExternalDictationOutcome): Job?

    /** On-device dictation records without a key; subclasses add what their outcome needs. */
    protected open val needsApiKey: Boolean
        get() = OnDeviceModels.requiresApiKey(settings.model, cloudText = false)

    /** A session that is being cancelled for this one needs a moment to end. */
    protected open val waitsForRunningSession = false

    /** Returns true when a request survived recreation and its result is awaited again. */
    protected fun restoreRequest(savedInstanceState: Bundle?): Boolean {
        requestId = savedInstanceState?.getString(KEY_REQUEST_ID)
        requestId?.let(::awaitResult)
        return requestId != null
    }

    protected fun startWhenResumed() {
        startWhenResumed = true
    }

    protected fun showPanel(content: @Composable (processing: Boolean) -> Unit) {
        onBackPressedDispatcher.addCallback(this) { cancel() }
        setContent {
            OpenDictateTheme(theme = settings.theme) {
                val state by DictationStateBus.state.collectAsStateWithLifecycle()
                content(holding || state.external && state.phase == DictationPhase.PROCESSING)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (startWhenResumed) {
            startWhenResumed = false
            start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_REQUEST_ID, requestId)
    }

    override fun onDestroy() {
        // Leaving the panel for good, for example from Recents, discards the recording.
        if (isFinishing && !finished) cancel()
        super.onDestroy()
    }

    protected fun start() {
        if (finished || requestId != null) return
        if (needsApiKey && !SecureApiKeyStore(this).hasKey()) return finishWith(ExternalDictationOutcome.Failed(DictationError.MissingApiKey))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return finishWith(ExternalDictationOutcome.Failed(DictationError.MicrophonePermission))
        }
        if (!DictationStateBus.state.value.isActive) return request()
        if (!waitsForRunningSession) return finishWith(ExternalDictationOutcome.Busy)
        lifecycleScope.launch {
            withTimeoutOrNull(RUNNING_SESSION_WAIT_MS) { DictationStateBus.state.first { !it.isActive } }
            if (finished || requestId != null) return@launch
            if (DictationStateBus.state.value.isActive) finishWith(ExternalDictationOutcome.Busy) else request()
        }
    }

    private fun request() {
        val id = UUID.randomUUID().toString()
        requestId = id
        ExternalDictationBus.await(id)
        awaitResult(id)
        try {
            ContextCompat.startForegroundService(this, serviceIntent(DictationForegroundService.ACTION_START_EXTERNAL, id))
        } catch (_: IllegalStateException) {
            finishWith(ExternalDictationOutcome.Failed(DictationError.Unexpected))
        }
    }

    private fun awaitResult(id: String) {
        lifecycleScope.launch {
            val delivery = ExternalDictationBus.delivery.filterNotNull().first { it.requestId == id }
            finishWith(delivery.outcome)
        }
    }

    /** A recording was requested; a stop from now on reaches it, even before the service starts. */
    protected val isRecording: Boolean
        get() = requestId != null

    protected fun stopRecording() {
        requestId?.let { runCatching { startService(serviceIntent(DictationForegroundService.ACTION_STOP_EXTERNAL, it)) } }
    }

    protected fun cancel() {
        // Leaving a holding panel lets its work go on without it, behind other apps.
        if (finished) return if (holding) finish() else Unit
        requestId?.let { runCatching { startService(serviceIntent(DictationForegroundService.ACTION_CANCEL_EXTERNAL, it)) } }
        finishWith(ExternalDictationOutcome.Cancelled)
    }

    private fun serviceIntent(action: String, id: String) =
        Intent(this, DictationForegroundService::class.java)
            .setAction(action)
            .putExtra(DictationForegroundService.EXTRA_REQUEST_ID, id)

    protected fun finishWith(outcome: ExternalDictationOutcome) {
        if (finished) return
        finished = true
        val pending = deliver(outcome)
        val message = when (outcome) {
            is ExternalDictationOutcome.Failed -> outcome.error.userMessage(resources)
            ExternalDictationOutcome.Busy -> getString(R.string.external_dictation_busy)
            else -> null
        }
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        requestId?.let(ExternalDictationBus::release)
        if (pending == null || pending.isCompleted) return finish()
        holding = true
        lifecycleScope.launch {
            withTimeoutOrNull(HOLD_TIMEOUT_MS) { pending.join() }
            finish()
        }
    }

    /** Closes without recording and without an outcome. */
    protected fun abandon() {
        finished = true
        finish()
    }

    private companion object {
        const val KEY_REQUEST_ID = "request_id"
        const val RUNNING_SESSION_WAIT_MS = 1_000L
        const val HOLD_TIMEOUT_MS = 30_000L
    }
}
