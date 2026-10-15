@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R
import com.opendictate.app.data.ReplacementStore

@Composable
internal fun ReplacementSheet(store: ReplacementStore, onDismiss: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    var editingID by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun reset() { editingID = null; source = ""; replacement = ""; invalid = false }
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
                OutlinedTextField(source, { source = it; invalid = false }, Modifier.fillMaxWidth().keepFullBoundsInView(),
                    label = { Text(stringResource(R.string.replacements_source)) }, maxLines = 3,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !state.storageError)
            }
            item {
                OutlinedTextField(replacement, { replacement = it; invalid = false }, Modifier.fillMaxWidth().keepFullBoundsInView(),
                    label = { Text(stringResource(R.string.replacements_target)) }, maxLines = 4,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !state.storageError)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (store.save(editingID, source, replacement)) reset() else invalid = true
                    }, enabled = source.isNotBlank() && replacement.isNotBlank() && !state.storageError) {
                        Text(stringResource(if (editingID == null) R.string.replacements_add else R.string.action_save))
                    }
                    if (editingID != null) TextButton(onClick = { reset() }) { Text(stringResource(android.R.string.cancel)) }
                }
                if (invalid) Text(stringResource(R.string.replacements_invalid), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
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
                        Text(rule.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rule.replacement)
                    }
                    IconButton(onClick = { editingID = rule.id; source = rule.source; replacement = rule.replacement; invalid = false; scope.launch { listState.animateScrollToItem(2) } }) {
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
