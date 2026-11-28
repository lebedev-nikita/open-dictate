package com.opendictate.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ExternalDictationOutcome
import com.opendictate.app.model.StatusBanner
import com.opendictate.app.model.StatusBannerSwipeAction
import com.opendictate.app.model.StatusBannerSwipeDirection
import com.opendictate.app.overlay.StatusBannerWindow
import com.opendictate.app.ui.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Spoken commands for the AI agent, started by a phone button. One banner at the top of the screen
 * shows each command from its recording to its outcome: the words as they are recognized, then the
 * transcription, the agent at work and what it did. Android starts a background microphone only
 * behind a visible window, so the banner comes first. The recording runs through
 * [DictationForegroundService] as an external request in the saved mode; [AssistantCommands] then
 * carries the text out. Swiped up, or left alone for a while, the command moves to the agent's
 * notification; its outcome can also be closed for good.
 */
internal class CommandSession(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private var current: Run? = null

    /** From the start until the recording ends; the button then finishes the command. */
    val isListening: Boolean
        get() = current?.isListening == true

    /** Starts a command; one started by a [held] button finishes when the button comes up. */
    fun start(held: Boolean) {
        current?.let { if (it.recordingPhase) return else it.toNotification() }
        if (!settings.assistantCommandsAvailable) return
        // On-device dictation records without a key, but the OpenAI text model decides the command.
        if (!SecureApiKeyStore(context).hasKey()) return toast(DictationError.MissingApiKey.userMessage(context.resources))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return toast(DictationError.MicrophonePermission.userMessage(context.resources))
        }
        val run = Run(held)
        // Android starts a background microphone only behind a visible window, so the banner comes first.
        if (!run.showBanner()) return toast(context.getString(R.string.command_microphone_unavailable))
        current = run
        run.job = scope.launch {
            try {
                run.execute()
            } finally {
                run.finish()
            }
        }
    }

    fun stop() {
        current?.stop()
    }

    /** The button of a held command came up. */
    fun release() {
        current?.takeIf { it.held }?.stop()
    }

    /** Cancels the recording; a command the agent already works on moves to its notification. */
    fun cancel() {
        current?.cancel()
    }

    /** Removes the banner at once; a command the agent already works on reports in its notification. */
    fun close() {
        current?.close()
    }

    private inner class Run(val held: Boolean) {
        var job: Job? = null
        private var requestId: String? = null
        private var banner: StatusBannerWindow? = null
        /** Until the recording has delivered its text, or ended without one. */
        var recordingPhase = true
            private set
        private var recorded = false
        private var spoken: String? = null
        private var command: String? = null
        private var running: Deferred<AssistantCommands.Report>? = null
        private var report: AssistantCommands.Report? = null
        /** The agent's notification shows this command now, not the banner. */
        private var inNotification = false
        /** Completes when the outcome no longer needs the banner. */
        private val outcomeShown = CompletableDeferred<Unit>()

        val isListening: Boolean get() = job?.isActive == true && recordingPhase && !recorded

        suspend fun execute() {
            val follow = scope.launch { followRecording() }
            val text = try { record() } finally { follow.cancel() }
            recordingPhase = false
            if (text == null) return
            command = text
            banner?.content = StatusBanner.command(text, recorded = true, executing = true)
            val agent = AssistantCommands.run(context, text)
            running = agent
            val outcome = agent.await()
            report = outcome
            val window = banner
            if (inNotification || window == null) return AssistantCommands.showOutcome(context, outcome, silent = false)
            window.content = StatusBanner.result(outcome.title, outcome.text)
            if (withTimeoutOrNull(StatusBanner.RESULT_VISIBLE_MS) { outcomeShown.await() } == null) toNotification()
        }

        private suspend fun followRecording() {
            DictationStateBus.state.collect { state ->
                // Only one external session runs at a time, and this command awaits it.
                if (requestId == null || !state.external) return@collect
                if (state.isActive && state.transcript.isNotBlank()) spoken = state.transcript
                if (state.phase.isProcessing || state.phase == DictationPhase.COMPLETED) recorded = true
                banner?.content = StatusBanner.command(spoken, recorded)
            }
        }

        /** The command's text, or null when the recording ended without one. */
        private suspend fun record(): String? {
            // A dictation that a double press replaced is being cancelled and needs a moment to end.
            if (DictationStateBus.state.value.isActive) {
                withTimeoutOrNull(RUNNING_SESSION_WAIT_MS) { DictationStateBus.state.first { !it.isActive } }
                if (DictationStateBus.state.value.isActive) return null.also { toast(context.getString(R.string.external_dictation_busy)) }
            }
            val id = UUID.randomUUID().toString()
            requestId = id
            ExternalDictationBus.await(id)
            try {
                ContextCompat.startForegroundService(context, serviceIntent(DictationForegroundService.ACTION_START_EXTERNAL, id)
                    .putExtra(DictationForegroundService.EXTRA_AGENT_COMMAND, true))
            } catch (_: IllegalStateException) {
                return null.also { toast(context.getString(R.string.command_microphone_unavailable)) }
            }
            val outcome = ExternalDictationBus.delivery.filterNotNull().first { it.requestId == id }.outcome
            ExternalDictationBus.release(id)
            requestId = null
            when (outcome) {
                is ExternalDictationOutcome.Transcribed -> return outcome.text
                is ExternalDictationOutcome.Failed -> toast(outcome.error.userMessage(context.resources))
                ExternalDictationOutcome.Busy -> toast(context.getString(R.string.external_dictation_busy))
                ExternalDictationOutcome.Cancelled, ExternalDictationOutcome.NotAllowed -> Unit
            }
            return null
        }

        /** A stop before recording began leaves nothing to send; after the recording it changes nothing. */
        fun stop() {
            if (!recordingPhase) return
            val id = requestId ?: return cancel()
            runCatching { context.startService(serviceIntent(DictationForegroundService.ACTION_STOP_EXTERNAL, id)) }
        }

        fun cancel() {
            if (!recordingPhase) return toNotification()
            requestId?.let { runCatching { context.startService(serviceIntent(DictationForegroundService.ACTION_CANCEL_EXTERNAL, it)) } }
            job?.cancel()
        }

        private fun onSwipe(direction: StatusBannerSwipeDirection) {
            val content = banner?.content ?: return
            when (content.onSwipe(direction)) {
                StatusBannerSwipeAction.CANCEL -> cancel()
                StatusBannerSwipeAction.TO_NOTIFICATION -> toNotification()
                StatusBannerSwipeAction.CLOSE -> hideOutcome()
            }
        }

        /** A tap on the outcome opens its link, as its notification would. */
        private fun open() {
            val outcome = report ?: return
            if (AssistantCommands.open(context, outcome)) hideOutcome()
        }

        /** Moves the command, or its outcome, from the banner to the agent's notification. */
        fun toNotification() {
            if (recordingPhase || inNotification) return
            inNotification = true
            removeBanner()
            val outcome = report
            if (outcome != null) {
                AssistantCommands.showOutcome(context, outcome, silent = true)
                outcomeShown.complete(Unit)
            } else {
                command?.let { AssistantCommands.showRunning(context, it) }
            }
        }

        private fun hideOutcome() {
            removeBanner()
            outcomeShown.complete(Unit)
        }

        private fun removeBanner() {
            banner?.dismiss()
            banner = null
        }

        fun finish() {
            requestId?.let(ExternalDictationBus::release)
            requestId = null
            recordingPhase = false
            // A command the agent still works on reports in its notification once its banner is gone.
            val pending = running?.takeIf { report == null }
            if (pending != null) {
                if (!inNotification) command?.let { AssistantCommands.showRunning(context, it) }
                AssistantCommands.notifyWhenDone(context, pending)
            }
            if (current === this) current = null
            removeBanner()
        }

        fun close() {
            val window = banner
            banner = null
            window?.removeNow()
            if (recordingPhase) cancel() else job?.cancel()
        }

        fun showBanner(): Boolean {
            val window = StatusBannerWindow(context, windowManager, onStop = ::stop, onSwipe = ::onSwipe, onOpen = ::open)
            if (!window.show(StatusBanner.command(null))) return false
            banner = window
            return true
        }
    }

    private fun serviceIntent(action: String, id: String) =
        Intent(context, DictationForegroundService::class.java)
            .setAction(action)
            .putExtra(DictationForegroundService.EXTRA_REQUEST_ID, id)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val RUNNING_SESSION_WAIT_MS = 1_000L
    }
}
