@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.McpAuth
import com.opendictate.app.model.McpServer
import com.opendictate.app.network.McpError
import java.util.UUID

/**
 * The MCP servers that receive spoken commands. Each server shows whether it is signed in and which
 * of its tools the model may call; every enabled tool is sent to the model with each command.
 */
@Composable
internal fun McpServersScreen(
    servers: List<McpServer>,
    signedIn: Set<String>,
    busy: Set<String>,
    errors: Map<String, McpError>,
    onBack: () -> Unit,
    onSave: (McpServer, String?) -> Unit,
    onDelete: (String) -> Unit,
    onSignIn: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onToolEnabled: (String, String, Boolean) -> Unit,
    onTryCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(SettingsCanvas).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            ScreenHeader(stringResource(R.string.mcp_servers_title), onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.mcp_servers_intro), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
                servers.forEach { server ->
                    ServerCard(server, server.id in signedIn, server.id in busy, errors[server.id],
                        onEdit = { editing = server.id }, onSignIn = { onSignIn(server.id) }, onRefresh = { onRefresh(server.id) },
                        onToolEnabled = { tool, enabled -> onToolEnabled(server.id, tool, enabled) })
                }
                Button({ adding = true }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.mcp_server_add)) }
                if (servers.any { it.activeTools.isNotEmpty() }) TryCommand(onTryCommand)
            }
        }
    }
    val edited = servers.firstOrNull { it.id == editing }
    if (adding || edited != null) {
        val close = { adding = false; editing = null }
        McpServerSheet(edited, hasToken = edited != null && edited.id in signedIn && edited.auth == McpAuth.TOKEN,
            onSave = { server, token -> onSave(server, token); close() },
            onDelete = edited?.let { { onDelete(it.id); close() } }, onDismiss = close)
    }
}

@Composable
private fun ServerCard(
    server: McpServer,
    signedIn: Boolean,
    busy: Boolean,
    error: McpError?,
    onEdit: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onToolEnabled: (String, Boolean) -> Unit,
) {
    var toolsOpen by rememberSaveable(server.id) { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.bodyLarge)
                Text(server.url, style = MaterialTheme.typography.bodySmall, color = SettingsMuted, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.mcp_server_edit, server.name), tint = SettingsMuted) }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = when {
                busy -> stringResource(R.string.mcp_server_busy)
                error == McpError.SignInNeeded || !signedIn ->
                    stringResource(if (server.auth == McpAuth.TOKEN) R.string.mcp_server_needs_token else R.string.mcp_server_needs_sign_in)
                error is McpError.Unreachable -> stringResource(R.string.mcp_server_unreachable)
                error is McpError.Failed -> stringResource(R.string.mcp_server_failed)
                server.tools.isEmpty() -> stringResource(R.string.mcp_server_no_tools)
                else -> stringResource(R.string.mcp_server_tools, server.enabledTools.size, server.tools.size)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (!busy && error != null && error != McpError.SignInNeeded) MaterialTheme.colorScheme.error else SettingsMuted)
            when {
                busy -> Unit
                server.auth == McpAuth.BROWSER && (!signedIn || error == McpError.SignInNeeded) ->
                    TextButton(onSignIn) { Text(stringResource(R.string.mcp_server_sign_in)) }
                signedIn -> TextButton(onRefresh) { Text(stringResource(R.string.mcp_server_refresh)) }
            }
        }
        if (server.tools.isNotEmpty()) {
            SettingsDivider(startInset = 16.dp)
            Row(Modifier.fillMaxWidth().toggleable(toolsOpen, role = Role.Button) { toolsOpen = it }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_server_choose_tools), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(if (toolsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = SettingsMuted)
            }
            AnimatedVisibility(toolsOpen) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    server.tools.forEach { tool ->
                        val enabled = tool.name in server.enabledTools
                        Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Checkbox) { onToolEnabled(tool.name, it) }
                            .heightIn(min = 52.dp).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(enabled, onCheckedChange = null, Modifier.padding(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall,
                                    color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Runs a typed command exactly as a spoken one, to try the servers without the button. */
@Composable
private fun TryCommand(onRun: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    Column {
        SectionTitle(stringResource(R.string.mcp_try_title))
        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text(stringResource(R.string.mcp_try_label)) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mcp_try_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                    Spacer(Modifier.width(12.dp))
                    Button({ onRun(command.trim()); command = "" }, enabled = command.isNotBlank()) { Text(stringResource(R.string.mcp_try_run)) }
                }
            }
        }
    }
}

/** Adds or edits a server. A saved token is never shown again; leaving its field empty keeps it. */
@Composable
private fun McpServerSheet(
    server: McpServer?,
    hasToken: Boolean,
    onSave: (McpServer, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(server?.url.orEmpty()) }
    var auth by rememberSaveable { mutableStateOf(server?.auth ?: McpAuth.BROWSER) }
    var token by remember { mutableStateOf("") }
    var trust by rememberSaveable { mutableStateOf(server?.trustDeviceCertificates ?: false) }
    var hint by rememberSaveable { mutableStateOf(server?.hint.orEmpty()) }
    val urlInvalid = url.isNotBlank() && !McpServer.isValidUrl(url)
    val complete = name.isNotBlank() && McpServer.isValidUrl(url) && (auth != McpAuth.TOKEN || token.isNotBlank() || hasToken)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SettingsSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (server == null) R.string.mcp_server_add else R.string.mcp_server_sheet_edit),
                    Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel), tint = SettingsMuted) }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_server_name)) })
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, isError = urlInvalid,
                label = { Text(stringResource(R.string.mcp_server_url)) }, placeholder = { Text("https://…/mcp") },
                supportingText = if (urlInvalid) { { Text(stringResource(R.string.mcp_server_url_error)) } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.mcp_server_auth), style = MaterialTheme.typography.labelLarge, color = SettingsMuted)
                Spacer(Modifier.height(4.dp))
                AuthChoice(McpAuth.BROWSER, auth, R.string.mcp_auth_browser, R.string.mcp_auth_browser_hint) { auth = it }
                AuthChoice(McpAuth.TOKEN, auth, R.string.mcp_auth_token, R.string.mcp_auth_token_hint) { auth = it }
                AuthChoice(McpAuth.NONE, auth, R.string.mcp_auth_none, null) { auth = it }
            }
            if (auth == McpAuth.TOKEN) {
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.mcp_server_token)) },
                    placeholder = if (hasToken) { { Text(stringResource(R.string.mcp_server_token_saved)) } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            }
            Surface(shape = MaterialTheme.shapes.medium, color = SettingsInset) {
                Row(Modifier.fillMaxWidth().toggleable(trust, role = Role.Switch) { trust = it }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.mcp_server_trust), style = MaterialTheme.typography.bodyLarge, color = SettingsText)
                        Text(stringResource(R.string.mcp_server_trust_hint), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(trust, onCheckedChange = null)
                }
            }
            OutlinedTextField(hint, { hint = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                label = { Text(stringResource(R.string.mcp_server_hint)) },
                supportingText = { Text(stringResource(R.string.mcp_server_hint_example)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onDelete) { Text(stringResource(R.string.mcp_server_delete), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button({
                    val saved = (server ?: McpServer(UUID.randomUUID().toString(), "", "")).copy(
                        name = name.trim(), url = url.trim(), auth = auth, trustDeviceCertificates = trust, hint = hint.trim())
                    onSave(saved, token.trim().takeIf { auth == McpAuth.TOKEN && it.isNotEmpty() })
                }, enabled = complete) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun AuthChoice(value: McpAuth, selected: McpAuth, title: Int, hint: Int?, onSelect: (McpAuth) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(value == selected, role = Role.RadioButton) { onSelect(value) }.heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(value == selected, onClick = null, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
        }
    }
}
