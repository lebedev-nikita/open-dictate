@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R
import com.opendictate.app.data.ReplacementError
import com.opendictate.app.data.ReplacementStore
import com.opendictate.app.data.WordReplacement

@Composable
internal fun ReplacementSheet(store: ReplacementStore, onDismiss: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    var editingID by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<ReplacementError?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun reset() { editingID = null; source = ""; replacement = ""; regex = false; error = null }
    val monospace = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Monospace))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.replacements_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.replacements_description), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.replacements_enable), Modifier.weight(1f))
                    val enableLabel = stringResource(R.string.replacements_enable)
                    Switch(state.enabled, store::setEnabled, Modifier.semantics { contentDescription = enableLabel })
                }
                HorizontalDivider()
            }
            item {
                Text(stringResource(if (editingID == null) R.string.replacements_add else R.string.replacements_edit), style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(source, { source = it; error = null }, Modifier.fillMaxWidth().keepFullBoundsInView(),
                    label = { Text(stringResource(if (regex) R.string.replacements_pattern else R.string.replacements_source)) }, maxLines = 3,
                    textStyle = if (regex) monospace else LocalTextStyle.current,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !state.storageError)
            }
            item {
                OutlinedTextField(replacement, { replacement = it; error = null }, Modifier.fillMaxWidth().keepFullBoundsInView(),
                    label = { Text(stringResource(if (regex) R.string.replacements_target_pattern else R.string.replacements_target)) }, maxLines = 4,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !state.storageError)
            }
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(regex, enabled = !state.storageError, role = Role.Checkbox) {
                    regex = it; error = null
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(regex, null, enabled = !state.storageError)
                    Text(stringResource(R.string.replacements_regex), Modifier.weight(1f).padding(start = 12.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        error = store.save(editingID, source, replacement, regex)
                        if (error == null) reset()
                    }, enabled = (if (regex) source.isNotEmpty() else source.isNotBlank() && replacement.isNotBlank()) && !state.storageError) {
                        Text(stringResource(if (editingID == null) R.string.replacements_add else R.string.action_save))
                    }
                    if (editingID != null) TextButton(onClick = { reset() }) { Text(stringResource(android.R.string.cancel)) }
                }
                when (error) {
                    ReplacementError.Invalid -> R.string.replacements_invalid
                    ReplacementError.Pattern -> R.string.replacements_invalid_pattern
                    else -> null
                }?.let { Text(stringResource(it), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (state.storageError) Text(stringResource(R.string.replacements_storage_error), color = MaterialTheme.colorScheme.error)
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
            if (state.document.rules.isEmpty()) item {
                Text(stringResource(R.string.replacements_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.document.rules.sortedBy { it.replacement.lowercase() }, key = { it.id }) { rule ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val toggleLabel = stringResource(R.string.replacements_toggle_rule, rule.source)
                    Checkbox(rule.enabled, { store.toggle(rule, it) }, Modifier.semantics { contentDescription = toggleLabel })
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        if (rule.regex) Text(rule.displaySource, style = monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(rule.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rule.replacement)
                    }
                    IconButton(onClick = { editingID = rule.id; source = rule.source; replacement = rule.replacement; regex = rule.regex; error = null; scope.launch { listState.animateScrollToItem(2) } }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.replacements_edit_rule, rule.source))
                    }
                    IconButton(onClick = { store.delete(rule); if (editingID == rule.id) reset() }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.replacements_delete_rule, rule.source))
                    }
                }
            }
            item {
                Text(stringResource(R.string.replacements_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_done))
                }
            }
        }
    }
}

/** Pattern rules read as `/pattern/`, distinct from phrases without a translated badge. */
private val WordReplacement.displaySource get() = "/$source/"
