package com.opendictate.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * An invisible OpenDictate window in front while Gemini Nano decides a spoken command: Android
 * answers Nano only then. The command indicator shows progress; this window shows nothing and
 * closes once the command no longer needs it, or on Back.
 */
class CommandFrontActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            holder.first { it == null }
            finish()
        }
    }

    companion object {
        /** The command that holds the window; a later command takes it over. */
        private val holder = MutableStateFlow<Any?>(null)

        fun open(context: Context, command: Any) {
            holder.value = command
            context.startActivity(Intent(context, CommandFrontActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }

        fun close(command: Any) {
            holder.compareAndSet(command, null)
        }
    }
}
