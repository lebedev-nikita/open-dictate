package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R

@Composable internal fun ChatGPTSettings(viewModel: MainViewModel, hasKey: Boolean, active: Boolean) {
    val connection = viewModel.chatGPT.state.collectAsStateWithLifecycle().value
    ChatGPTSettingsPanel(connection, hasKey, active, viewModel::connectChatGPT, viewModel::disconnectChatGPT, viewModel::cancelChatGPT)

    if (connection.welcome) AlertDialog(onDismissRequest = viewModel.chatGPT::dismissWelcome,
        title = { Text(stringResource(R.string.chatgpt_welcome)) },
        text = { Text(stringResource(R.string.chatgpt_welcome_body)) },
        confirmButton = { TextButton(onClick = viewModel.chatGPT::dismissWelcome) { Text(stringResource(R.string.settings_done)) } })
}

@Composable internal fun ChatGPTSettingsPanel(connection: com.opendictate.app.chatgpt.ChatGPTSession.State, hasKey: Boolean, active: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onCancel: () -> Unit) {
    val checked = connection.connected || connection.busy
    val enabled = !active && !connection.busy && (hasKey || connection.connected)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = { if (it) onConnect() else onDisconnect() })
            .heightIn(min = 80.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_chatgpt_24), null, Modifier.size(24.dp), tint = SettingsText)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ChatGPT", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.chatgpt_text_hint), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (!hasKey || connection.busy || connection.failed || connection.revocationUnconfirmed) Column(Modifier.padding(start = 54.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connection.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.chatgpt_waiting), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.postprocessing_cancel)) }
            }
            if (!hasKey) Text(stringResource(R.string.chatgpt_key_required), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            if (connection.connected && connection.failed) TextButton(onClick = onConnect, enabled = hasKey && !active && !connection.busy) { Text(stringResource(R.string.chatgpt_reconnect)) }
            if (connection.revocationUnconfirmed) Text(stringResource(R.string.chatgpt_revoke_failed), style = MaterialTheme.typography.bodySmall)
            if (connection.failed) Text(stringResource(R.string.chatgpt_request_failed), style = MaterialTheme.typography.bodySmall)
        }
    }
}
