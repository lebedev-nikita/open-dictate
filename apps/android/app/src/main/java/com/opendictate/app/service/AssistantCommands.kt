package com.opendictate.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.McpSecrets
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantDecision
import com.opendictate.app.model.DictationError
import com.opendictate.app.network.McpClient
import com.opendictate.app.network.McpError
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.ui.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Carries out a spoken command: one request to the text model with the enabled tools of the user's
 * MCP servers, then at most one tool call. The outcome is shown as a notification; the command and
 * the tool's answer are never logged.
 */
internal object AssistantCommands {
    private const val CHANNEL_ID = "assistant_commands"
    private const val NOTIFICATION_ID = 44
    private const val MAX_RESULT_LENGTH = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun run(context: Context, command: String) {
        val application = context.applicationContext
        scope.launch {
            notify(application, application.getString(R.string.assistant_running), command, ongoing = true)
            val (title, text) = execute(application, command)
            notify(application, title, text, ongoing = false)
        }
    }

    /** Returns the title and text of the notification that reports the outcome. */
    private suspend fun execute(context: Context, command: String): Pair<String, String> {
        val settings = SettingsStore(context)
        val functions = AssistantCommand.functions(settings.mcpServers)
        val failed = context.getString(R.string.assistant_failed)
        if (functions.isEmpty()) return failed to context.getString(R.string.assistant_no_tools)
        val apiKey = SecureApiKeyStore(context).get()
            ?: return failed to DictationError.MissingApiKey.userMessage(context.resources)
        val chatGPT = (context as OpenDictateApplication).chatGPT
        val decision = OpenAiTranscriptionClient(chatGPT)
            .assistantDecision(apiKey, command, functions, settings.textProcessingModel, chatGPT.activeAccount())
            .fold({ return failed to it.userMessage(context.resources) }, { it })
        return when (decision) {
            AssistantDecision.Nothing -> failed to context.getString(R.string.assistant_no_action)
            is AssistantDecision.Reply -> context.getString(R.string.assistant_reply) to decision.text
            is AssistantDecision.Call -> {
                val server = decision.function.server
                val action = context.getString(R.string.assistant_action, server.name, decision.function.tool.name)
                McpClient().callTool(server, McpSecrets(context).token(server), decision.function.tool.name, decision.argumentsJson).fold(
                    { error ->
                        failed to when (error) {
                            McpError.SignInNeeded -> context.getString(R.string.mcp_error_sign_in, server.name)
                            is McpError.Unreachable -> context.getString(R.string.mcp_error_unreachable, server.name)
                            is McpError.Failed -> context.getString(R.string.mcp_error_failed, server.name)
                        }
                    },
                    { result ->
                        val text = result.text.take(MAX_RESULT_LENGTH).ifBlank { context.getString(R.string.assistant_done) }
                        (if (result.isError) context.getString(R.string.assistant_tool_error, action) else action) to text
                    },
                )
            }
        }
    }

    private fun notify(context: Context, title: String, text: String, ongoing: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            // Shown at once like an answer, but without a sound: the user is looking at the phone.
            NotificationChannel(CHANNEL_ID, context.getString(R.string.assistant_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
