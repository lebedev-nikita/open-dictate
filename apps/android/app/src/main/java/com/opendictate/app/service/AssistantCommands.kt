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
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.McpServer
import com.opendictate.app.network.AssistantAgent
import com.opendictate.app.network.AssistantRun
import com.opendictate.app.network.McpClient
import com.opendictate.app.network.McpError
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.network.ToolCallOutcome
import com.opendictate.app.ui.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Carries out a spoken command with [AssistantAgent]: the text model sees the enabled tools of the
 * user's MCP servers, calls several at once or one after another, reads their answers and closes
 * with a sentence. The caller shows the outcome in its banner or the notification; the command,
 * the tools' arguments and answers are never logged.
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
        val account = chatGPT.activeAccount()
        val secrets = McpSecrets(context)
        val mcp = McpClient()
        val run = AssistantAgent(
            functions = functions,
            modelId = settings.textProcessingModel,
            send = { body -> client.assistantResponse(apiKey, body, account) },
            callTool = { function, arguments -> mcp.callTool(function.server, secrets.token(function.server), function.tool.name, arguments) },
        ).run(command)
        return report(context, run)
    }

    /**
     * The outcome as the user sees it: the actions that worked, the model's closing sentence, and the
     * last link a tool answered with. Without a working action, the first failure explains itself.
     */
    private fun report(context: Context, run: AssistantRun): Report {
        fun actions(calls: List<ToolCallOutcome>) = calls.map {
            context.getString(R.string.assistant_action, it.function.server.name, it.function.tool.name)
        }.distinct().joinToString(", ")
        val reply = run.reply.getOrNull()?.takeIf(String::isNotBlank)?.take(MAX_RESULT_LENGTH)
        val failed = context.getString(R.string.assistant_failed)
        if (run.calls.isEmpty()) {
            if (reply != null) return Report(context.getString(R.string.assistant_reply), reply)
            return Report(failed, run.reply.fold({ it.userMessage(context.resources) }, { context.getString(R.string.assistant_no_action) }))
        }
        val done = run.calls.filterNot { it.isError }
        if (done.isEmpty()) {
            val first = run.calls.first()
            val server = first.function.server
            val text = first.error?.let { mcpMessage(context, it, server) } ?: reply
                ?: first.answer.text?.take(MAX_RESULT_LENGTH) ?: context.getString(R.string.mcp_error_failed, server.name)
            return Report(context.getString(R.string.assistant_tool_error, actions(run.calls)), text)
        }
        val link = done.mapNotNull { it.answer.link }.lastOrNull()
        val text = reply ?: done.singleOrNull()?.answer?.text?.take(MAX_RESULT_LENGTH)
            ?: context.getString(if (link != null) R.string.assistant_done_open else R.string.assistant_done)
        return Report(actions(done), text, link)
    }

    private fun mcpMessage(context: Context, error: McpError, server: McpServer) = when (error) {
        McpError.SignInNeeded -> context.getString(R.string.mcp_error_sign_in, server.name)
        is McpError.Unreachable -> context.getString(R.string.mcp_error_unreachable, server.name)
        is McpError.Failed -> context.getString(R.string.mcp_error_failed, server.name)
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
