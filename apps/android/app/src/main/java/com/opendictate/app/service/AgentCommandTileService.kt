package com.opendictate.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile for the AI agent command. A tap starts a spoken command through the
 * accessibility service, like a phone button; a second tap finishes it. The tile only forwards taps:
 * the command, its banner and its microphone belong to [CommandSession].
 */
class AgentCommandTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        // A command records as an external session, so its state follows the bus.
        scope.launch { DictationStateBus.state.collect { updateTile() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val service = OpenDictateAccessibilityService.running
        when {
            service == null -> openApp(R.string.accessibility_enable_hint)
            !SettingsStore(this).assistantCommandsAvailable -> openApp(R.string.agent_command_needs_server)
            else -> service.toggleAgentCommandFromTile()
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = OpenDictateAccessibilityService.running?.agentCommandListening == true
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    /** Explains what is missing and opens the app, where it is set up. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Android 13 lets the app offer the tile instead of sending the user to the panel's editor. */
        val canRequestAdd: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun requestAdd(context: Context) {
            context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(context, AgentCommandTileService::class.java),
                context.getString(R.string.button_action_agent_command),
                Icon.createWithResource(context, R.drawable.ic_agent_command_24),
                context.mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    Toast.makeText(context, R.string.quick_tile_already_added, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
