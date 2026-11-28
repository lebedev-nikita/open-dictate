package com.opendictate.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.McpSecrets
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantDecision
import com.opendictate.app.model.AssistantFunction
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.FailedCall
import com.opendictate.app.network.McpClient
import com.opendictate.app.network.McpError
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.ui.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Carries out a spoken command: one request to the text model with the enabled tools of the user's
 * MCP servers, then one tool call. If the server refuses the call, the model sees that error once
 * and may correct it. The outcome is shown as a notification; the command and the tool's answer are
 * never logged.
 */
internal object AssistantCommands {
    private const val CHANNEL_ID = "assistant_commands"
    private const val NOTIFICATION_ID = 44
    private const val MAX_RESULT_LENGTH = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A command on its way. [decided] completes once the model has first decided, before any tool
     * runs: Android answers Gemini Nano only while an OpenDictate window is in front, so one stays
     * until then. [done] completes once the outcome is shown; callers only wait for it.
     */
    class Running(val decided: Job, val done: Job)

    fun run(context: Context, command: String): Running {
        val application = context.applicationContext
        val decided = CompletableDeferred<Unit>()
        val done = scope.launch {
            try {
                notify(application, Report(application.getString(R.string.assistant_running), command), ongoing = true)
                notify(application, execute(application, command) { decided.complete(Unit) }, ongoing = false)
            } finally {
                decided.complete(Unit)
            }
        }
        return Running(decided, done)
    }

    /** What the notification says; [link] is opened when the user taps it. */
    private class Report(val title: String, val text: String, val link: String? = null)

    private suspend fun execute(context: Context, command: String, onDecided: () -> Unit): Report {
        val settings = SettingsStore(context)
        val functions = AssistantCommand.functions(settings.mcpServers)
        val failed = context.getString(R.string.assistant_failed)
        if (functions.isEmpty()) return Report(failed, context.getString(R.string.assistant_no_tools))
        val apiKey = SecureApiKeyStore(context).get()?.takeIf(String::isNotBlank)
        val onDevice = settings.onDeviceTextProcessing
        if (apiKey == null && !onDevice) return Report(failed, DictationError.MissingApiKey.userMessage(context.resources))
        val chatGPT = (context as OpenDictateApplication).chatGPT
        val requests = TextRequests(OpenAiTranscriptionClient(chatGPT), apiKey, onDevice, settings.textProcessingModel,
            chatGPTAccount = chatGPT.activeAccount())
        fun action(function: AssistantFunction) = context.getString(R.string.assistant_action, function.server.name, function.tool.name)
        fun refused(call: FailedCall) = Report(context.getString(R.string.assistant_tool_error, action(call.function)),
            call.error.take(MAX_RESULT_LENGTH).ifBlank { context.getString(R.string.mcp_error_failed, call.function.server.name) })
        var failedCall: FailedCall? = null
        // The second round exists only to let the model correct a call the server refused.
        repeat(2) {
            val decision = requests.decide(command, functions, failedCall)
                .also { onDecided() }
                // Without a second answer, for example from Nano behind another app, the refusal is the outcome.
                .fold({ error -> return failedCall?.let(::refused) ?: Report(failed, error.userMessage(context.resources)) }, { it.value })
            val call = when (decision) {
                AssistantDecision.Nothing -> return Report(failed, context.getString(R.string.assistant_no_action))
                is AssistantDecision.Reply -> return Report(context.getString(R.string.assistant_reply), decision.text)
                is AssistantDecision.Call -> decision
            }
            val server = call.function.server
            val result = McpClient().callTool(server, McpSecrets(context).token(server), call.function.tool.name, call.argumentsJson).fold(
                { error ->
                    return Report(failed, when (error) {
                        McpError.SignInNeeded -> context.getString(R.string.mcp_error_sign_in, server.name)
                        is McpError.Unreachable -> context.getString(R.string.mcp_error_unreachable, server.name)
                        is McpError.Failed -> context.getString(R.string.mcp_error_failed, server.name)
                    })
                },
                { it },
            )
            val answer = AssistantCommand.answer(result.text)
            if (!result.isError) {
                return Report(action(call.function), answer.text?.take(MAX_RESULT_LENGTH)
                    ?: context.getString(if (answer.link != null) R.string.assistant_done_open else R.string.assistant_done), answer.link)
            }
            val refusal = FailedCall(call.function, call.argumentsJson, answer.text ?: result.text)
            if (failedCall != null) return refused(refusal)
            failedCall = refusal
        }
        return Report(failed, context.getString(R.string.assistant_no_action))
    }

    private fun notify(context: Context, report: Report, ongoing: Boolean) {
        val title = report.title
        val text = report.text
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            // Shown at once like an answer, but without a sound: the user is looking at the phone.
            NotificationChannel(CHANNEL_ID, context.getString(R.string.assistant_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        // A link in the tool's answer, such as the thread it started, is where a tap should lead.
        val target = report.link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: Intent(context, MainActivity::class.java)
        val open = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(ongoing)
                .setProgress(0, 0, ongoing)
                .setOnlyAlertOnce(ongoing)
                .setAutoCancel(!ongoing)
                .setContentIntent(open)
                .build())
        }
    }
}
