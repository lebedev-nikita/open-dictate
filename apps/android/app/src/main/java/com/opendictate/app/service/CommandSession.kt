package com.opendictate.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.CommandIndicatorStage
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ExternalDictationOutcome
import com.opendictate.app.model.OnDeviceModels
import com.opendictate.app.overlay.CommandIndicatorView
import com.opendictate.app.ui.CommandFrontActivity
import com.opendictate.app.ui.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Spoken commands for the AI agent, started by a phone button: each shows the indicator at the top
 * of the screen, records through [DictationForegroundService] as an external request, then hands the
 * text to [AssistantCommands]. The indicator stays until the command has run; its outcome is a
 * notification.
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
        current?.let { if (it.executing.value) it.cancel() else if (it.isActive) return }
        if (!settings.assistantCommandsAvailable) return
        // On-device dictation records without a key; the command still needs Gemini Nano or OpenAI for text.
        val onDeviceText = settings.onDeviceTextProcessing
        val needsKey = OnDeviceModels.requiresApiKey(settings.model, cloudText = false) || !onDeviceText
        if (needsKey && !SecureApiKeyStore(context).hasKey()) return toast(DictationError.MissingApiKey.userMessage(context.resources))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return toast(DictationError.MicrophonePermission.userMessage(context.resources))
        }
        val run = Run(held, onDeviceText)
        // Android starts a background microphone only behind a visible window, so the indicator comes first.
        if (!run.showIndicator()) return toast(context.getString(R.string.command_microphone_unavailable))
        current = run
        run.holdFront()
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

    /** Cancels the recording; once the command runs, only the indicator goes away. */
    fun cancel() {
        current?.cancel()
    }

    /** Removes the indicator at once, without a recording or a command left behind. */
    fun close() {
        current?.close()
    }

    /** [onDeviceText] keeps an invisible OpenDictate window in front until Gemini Nano has decided. */
    private inner class Run(val held: Boolean, private val onDeviceText: Boolean) {
        var job: Job? = null
        val executing = MutableStateFlow(false)
        private var requestId: String? = null
        private var indicator: CommandIndicatorView? = null

        val isActive: Boolean get() = job?.isActive == true
        val isListening: Boolean get() = isActive && indicator?.stage?.acceptsStop == true

        suspend fun execute() {
            val render = scope.launch {
                combine(DictationStateBus.state, executing) { state, executing ->
                    CommandIndicatorStage.of(executing, recordingProcessing = requestId != null && state.external && state.phase.isProcessing)
                }.collect { indicator?.stage = it }
            }
            try {
                record()
            } finally {
                render.cancel()
            }
        }

        private suspend fun record() {
            // A dictation that a double press replaced is being cancelled and needs a moment to end.
            if (DictationStateBus.state.value.isActive) {
                withTimeoutOrNull(RUNNING_SESSION_WAIT_MS) { DictationStateBus.state.first { !it.isActive } }
                if (DictationStateBus.state.value.isActive) return toast(context.getString(R.string.external_dictation_busy))
            }
            val id = UUID.randomUUID().toString()
            requestId = id
            ExternalDictationBus.await(id)
            try {
                ContextCompat.startForegroundService(context, serviceIntent(DictationForegroundService.ACTION_START_EXTERNAL, id))
            } catch (_: IllegalStateException) {
                return toast(context.getString(R.string.command_microphone_unavailable))
            }
            val outcome = ExternalDictationBus.delivery.filterNotNull().first { it.requestId == id }.outcome
            ExternalDictationBus.release(id)
            requestId = null
            when (outcome) {
                is ExternalDictationOutcome.Transcribed -> {
                    executing.value = true
                    val running = AssistantCommands.run(context, outcome.text)
                    withTimeoutOrNull(FRONT_TIMEOUT_MS) { running.decided.join() }
                    releaseFront()
                    running.done.join()
                }
                is ExternalDictationOutcome.Failed -> toast(outcome.error.userMessage(context.resources))
                ExternalDictationOutcome.Busy -> toast(context.getString(R.string.external_dictation_busy))
                ExternalDictationOutcome.Cancelled, ExternalDictationOutcome.NotAllowed -> Unit
            }
        }

        /** A stop before recording began leaves nothing to send. */
        fun stop() {
            val id = requestId ?: return cancel()
            runCatching { context.startService(serviceIntent(DictationForegroundService.ACTION_STOP_EXTERNAL, id)) }
        }

        fun cancel() {
            requestId?.let { runCatching { context.startService(serviceIntent(DictationForegroundService.ACTION_CANCEL_EXTERNAL, it)) } }
            job?.cancel()
        }

        fun holdFront() {
            if (onDeviceText) runCatching { CommandFrontActivity.open(context, this) }.onFailure { Log.w(TAG, "Could not open command window", it) }
        }

        private fun releaseFront() {
            if (onDeviceText) CommandFrontActivity.close(this)
        }

        fun finish() {
            releaseFront()
            requestId?.let(ExternalDictationBus::release)
            requestId = null
            executing.value = false
            if (current === this) current = null
            val view = indicator ?: return
            indicator = null
            view.disappear { runCatching { windowManager.removeView(view) } }
        }

        fun close() {
            releaseFront()
            val view = indicator
            indicator = null
            cancel()
            view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        }

        fun showIndicator(): Boolean {
            val view = CommandIndicatorView(context, settings.dictationButtonAppearance.diameterDp, ::stop, ::cancel)
            val size = view.windowSizePx
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topInsetPx() + (TOP_MARGIN_DP * context.resources.displayMetrics.density).toInt()
            }
            return runCatching { windowManager.addView(view, params) }
                .onFailure { Log.w(TAG, "Could not add command indicator", it) }
                .map {
                    indicator = view
                    view.appear()
                }.isSuccess
        }
    }

    private fun serviceIntent(action: String, id: String) =
        Intent(context, DictationForegroundService::class.java)
            .setAction(action)
            .putExtra(DictationForegroundService.EXTRA_REQUEST_ID, id)

    private fun topInsetPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return (FALLBACK_STATUS_BAR_DP * context.resources.displayMetrics.density).toInt()
        val insets = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        return insets.top
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "OpenDictateCommand"
        const val RUNNING_SESSION_WAIT_MS = 1_000L
        const val FRONT_TIMEOUT_MS = 30_000L
        // The ripples already leave a margin, so the button sits close below the status bar.
        const val TOP_MARGIN_DP = 2
        const val FALLBACK_STATUS_BAR_DP = 24
    }
}
