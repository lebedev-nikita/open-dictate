package com.opendictate.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R

@Composable internal fun ChatGPTSettings(viewModel: MainViewModel, hasKey: Boolean, active: Boolean) {
    val connection = viewModel.chatGPT.state.collectAsStateWithLifecycle().value
    ChatGPTSettingsPanel(connection, hasKey, active, viewModel::connectChatGPT, viewModel::disconnectChatGPT, viewModel::cancelChatGPT, viewModel::connectChatGPTAccount)

    if (connection.welcome) AlertDialog(onDismissRequest = viewModel.chatGPT::dismissWelcome,
        title = { Text(stringResource(R.string.chatgpt_welcome)) },
        text = { Text(stringResource(R.string.chatgpt_welcome_body)) },
        confirmButton = { TextButton(onClick = viewModel.chatGPT::dismissWelcome) { Text(stringResource(R.string.settings_done)) } })
}

@Composable internal fun ChatGPTSettingsPanel(connection: com.opendictate.app.chatgpt.ChatGPTSession.State, hasKey: Boolean, active: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onCancel: () -> Unit, onAccount: (String?) -> Unit = {}) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connection.connected) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chatgpt_using))
                    if (connection.email.isNotBlank()) Text(connection.email, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDisconnect, enabled = !active && !connection.busy) { Text(stringResource(R.string.chatgpt_disconnect)) }
            } else {
                OutlinedButton(onClick = onConnect, enabled = hasKey && !active && !connection.busy) { Text(stringResource(R.string.chatgpt_connect)) }
            }
            if (connection.busy) TextButton(onClick = onCancel) { Text(stringResource(R.string.postprocessing_cancel)) }
        }
        if (connection.accounts.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }, enabled = hasKey && !active && !connection.busy) { Text(stringResource(R.string.chatgpt_accounts)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    connection.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.label) }, onClick = { expanded = false; onAccount(account.id) }) }
                    DropdownMenuItem(text = { Text(stringResource(R.string.chatgpt_add_account)) }, onClick = { expanded = false; onAccount(null) })
                }
            }
        }
        Text(stringResource(when { !hasKey -> R.string.chatgpt_key_required; connection.connected -> R.string.chatgpt_connected_hint; else -> R.string.chatgpt_billing_hint }), style = MaterialTheme.typography.bodySmall)
        if (connection.connected && connection.failed) TextButton(onClick = onConnect, enabled = hasKey && !active && !connection.busy) { Text(stringResource(R.string.chatgpt_reconnect)) }
        if (connection.revocationUnconfirmed) Text(stringResource(R.string.chatgpt_revoke_failed), style = MaterialTheme.typography.bodySmall)
        if (connection.failed) Text(stringResource(R.string.chatgpt_request_failed), style = MaterialTheme.typography.bodySmall)
        if (connection.connected || connection.failed || connection.revocationUnconfirmed) TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
        }) { Text(stringResource(R.string.chatgpt_usage)) }
    }
}

