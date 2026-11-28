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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Carries out a spoken command: one request to the text model with the enabled tools of the user's
 * MCP servers, then one tool call. If the server refuses the call, the model sees that error once
 * and may correct it. The caller shows the outcome in its banner or the notification; the command
 * and the tool's answer are never logged.
 */
internal object AssistantCommands {
    private const val CHANNEL_ID = "assistant_commands"
    private const val NOTIFICATION_ID = 44
    private const val MAX_RESULT_LENGTH = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Carries out a command. The caller shows the outcome, in the banner or the notification; the
     * command runs on even when its caller is gone.
     */
    fun run(context: Context, command: String): Deferred<Report> {
        val application = context.applicationContext
        return scope.async { execute(application, command) }
    }

    /** A command's outcome; [link] is opened when the user taps it. */
    class Report(val title: String, val text: String, val link: String? = null)

    /** A command that is still being carried out, after its banner was put away. */
    fun showRunning(context: Context, command: String) =
        post(context, Report(context.getString(R.string.assistant_running), command), ongoing = true, silent = true)

    /** The outcome; [silent] when the user has just seen it in the banner. */
    fun showOutcome(context: Context, report: Report, silent: Boolean) =
        post(context, report, ongoing = false, silent = silent)

    /** Opens the outcome's link, as a tap on its notification does; false without one. */
    fun open(context: Context, report: Report): Boolean {
        val link = report.link ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** Reports the outcome of a command whose banner is gone, once the agent is done. */
    fun notifyWhenDone(context: Context, outcome: Deferred<Report>) {
        val application = context.applicationContext
        scope.launch { runCatching { outcome.await() }.onSuccess { showOutcome(application, it, silent = false) } }
    }

    private suspend fun execute(context: Context, command: String): Report {
        val settings = SettingsStore(context)
        val functions = AssistantCommand.functions(settings.mcpServers)
        val failed = context.getString(R.string.assistant_failed)
        if (functions.isEmpty()) return Report(failed, context.getString(R.string.assistant_no_tools))
        val apiKey = SecureApiKeyStore(context).get()?.takeIf(String::isNotBlank)
            ?: return Report(failed, DictationError.MissingApiKey.userMessage(context.resources))
        val chatGPT = (context as OpenDictateApplication).chatGPT
        val client = OpenAiTranscriptionClient(chatGPT)
        fun action(function: AssistantFunction) = context.getString(R.string.assistant_action, function.server.name, function.tool.name)
        fun refused(call: FailedCall) = Report(context.getString(R.string.assistant_tool_error, action(call.function)),
            call.error.take(MAX_RESULT_LENGTH).ifBlank { context.getString(R.string.mcp_error_failed, call.function.server.name) })
        var failedCall: FailedCall? = null
        // The second round exists only to let the model correct a call the server refused.
        repeat(2) {
            val decision = client.assistantDecision(apiKey, command, functions, settings.textProcessingModel, chatGPT.activeAccount(), failedCall)
                // Without a second answer the refusal is the outcome.
                .fold({ error -> return failedCall?.let(::refused) ?: Report(failed, error.userMessage(context.resources)) }, { it })
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

    /** An [ongoing] notification spins only until it has words: a collapsed one shows either, and words matter more. */
    private fun post(context: Context, report: Report, ongoing: Boolean, silent: Boolean) {
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
                .apply { if (text.isNotEmpty()) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(ongoing)
                .setProgress(0, 0, ongoing && text.isEmpty())
                // The user has seen what the banner showed; only an outcome that arrives later alerts.
                .setSilent(silent)
                .setAutoCancel(!ongoing)
                .setContentIntent(open)
                .build())
        }
    }
}
