package com.opendictate.app.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.opendictate.app.R
import com.opendictate.app.model.DictationCommand
import com.opendictate.app.model.DictationCommandEffect
import com.opendictate.app.ui.ExternalDictationActivity

/**
 * Receives start, stop, toggle and cancel from automation apps such as Automate or Key Mapper.
 * Senders cannot be identified, so a started session only writes into the focused field.
 */
class DictationCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = DictationCommand.fromAction(intent.action) ?: return
        val state = DictationStateBus.state.value
        // A RECOGNIZE_SPEECH request may still be starting when the caller already sends stop.
        val pendingExternal = ExternalDictationBus.awaitedRequestId?.takeUnless { state.isActive && !state.external }
        when (command.effect(state.isActive || pendingExternal != null)) {
            DictationCommandEffect.START -> {
                val service = OpenDictateAccessibilityService.running
                if (service == null) {
                    Toast.makeText(context, R.string.accessibility_enable_hint, Toast.LENGTH_SHORT).show()
                } else {
                    service.startFromCommand()
                }
            }
            DictationCommandEffect.STOP -> send(context,
                if (pendingExternal != null) DictationForegroundService.ACTION_STOP_EXTERNAL
                else DictationForegroundService.ACTION_STOP, pendingExternal)
            DictationCommandEffect.CANCEL -> when {
                pendingExternal != null -> send(context, DictationForegroundService.ACTION_CANCEL_EXTERNAL, pendingExternal)
                // The accessibility service restores live text that was already inserted.
                OpenDictateAccessibilityService.running?.cancelFromCommand() == true -> Unit
                else -> send(context, DictationForegroundService.ACTION_CANCEL, null, state.sessionId)
            }
            DictationCommandEffect.NONE -> Unit
        }
    }

    private fun send(context: Context, action: String, requestId: String?, sessionId: Long? = null) {
        // An active session keeps the foreground service running, so the app may start services.
        runCatching {
            context.startService(
                Intent(context, DictationForegroundService::class.java).setAction(action).apply {
                    requestId?.let { putExtra(DictationForegroundService.EXTRA_REQUEST_ID, it) }
                    sessionId?.let { putExtra(DictationForegroundService.EXTRA_SESSION_ID, it) }
                },
            )
        }
    }
}

/** The exported entry points exist only while the user has enabled control from other apps. */
object ExternalDictationComponents {
    private fun components(context: Context) = listOf(
        ComponentName(context, ExternalDictationActivity::class.java),
        ComponentName(context, DictationCommandReceiver::class.java),
    )

    fun isEnabled(context: Context): Boolean = components(context).all {
        context.packageManager.getComponentEnabledSetting(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        components(context).forEach {
            context.packageManager.setComponentEnabledSetting(
                it,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
