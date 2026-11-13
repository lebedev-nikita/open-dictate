@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.opendictate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val monospace = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Monospace))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.replacements_title), style = MaterialTheme.typography.headlineSmall)
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
                Text(stringResource(R.string.replacements_add), style = MaterialTheme.typography.titleMedium)
            }
            item {
                ReplacementEditor(store, null, !state.storageError, monospace, onDone = {})
                if (state.storageError) Text(stringResource(R.string.replacements_storage_error), color = MaterialTheme.colorScheme.error)
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
            if (state.document.rules.isEmpty()) item {
                Text(stringResource(R.string.replacements_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.document.rules.sortedBy { it.replacement.lowercase() }, key = { it.id }) { rule ->
                // The rule is edited in its own row, so the sheet never scrolls away from it.
                // Extra space sets the open rule apart from its neighbours more than its fields from each other.
                if (editingID == rule.id) ReplacementEditor(store, rule, !state.storageError, monospace,
                    onDone = { editingID = null }, onCancel = { editingID = null }, Modifier.padding(vertical = 12.dp))
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val toggleLabel = stringResource(R.string.replacements_toggle_rule, rule.source)
                    Checkbox(rule.enabled, { store.toggle(rule, it) }, Modifier.semantics { contentDescription = toggleLabel })
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Row {
                            Text(rule.source, Modifier.weight(1f, fill = false).alignByBaseline(), style = if (rule.regex) monospace else LocalTextStyle.current,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // The tags sit further from the source than from each other, so they read as one group.
                            if (rule.regex) RuleBadge(".*", stringResource(R.string.replacements_regex),
                                Modifier.padding(start = 8.dp).alignByBaseline(), FontFamily.Monospace)
                            if (rule.matchCase) RuleBadge("Aa", stringResource(R.string.replacements_match_case),
                                Modifier.padding(start = if (rule.regex) 4.dp else 8.dp).alignByBaseline())
                        }
                        Text(rule.replacement)
                    }
                    IconButton(onClick = { editingID = rule.id }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.replacements_edit_rule, rule.source))
                    }
                    IconButton(onClick = { store.delete(rule); if (editingID == rule.id) editingID = null }) {
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

/** Fields for a new rule, or for an existing one in place of its row. */
@Composable
private fun ReplacementEditor(store: ReplacementStore, rule: WordReplacement?, enabled: Boolean, monospace: TextStyle,
                              onDone: () -> Unit, onCancel: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var source by rememberSaveable(rule?.id) { mutableStateOf(rule?.source.orEmpty()) }
    var replacement by rememberSaveable(rule?.id) { mutableStateOf(rule?.replacement.orEmpty()) }
    var regex by rememberSaveable(rule?.id) { mutableStateOf(rule?.regex ?: false) }
    var matchCase by rememberSaveable(rule?.id) { mutableStateOf(rule?.matchCase ?: false) }
    var error by rememberSaveable(rule?.id) { mutableStateOf<ReplacementError?>(null) }
    val focus = remember { FocusRequester() }
    if (rule != null) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(source, { source = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus).keepFullBoundsInView(),
            label = { Text(stringResource(if (regex) R.string.replacements_pattern else R.string.replacements_source)) }, maxLines = 3,
            textStyle = if (regex) monospace else LocalTextStyle.current,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = enabled)
        OutlinedTextField(replacement, { replacement = it; error = null }, Modifier.fillMaxWidth().keepFullBoundsInView(),
            label = { Text(stringResource(if (regex) R.string.replacements_target_pattern else R.string.replacements_target)) }, maxLines = 4,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = enabled)
        // Both options share a line where the labels fit.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LabeledCheckbox(stringResource(R.string.replacements_regex), regex, enabled) { regex = it; error = null }
            LabeledCheckbox(stringResource(R.string.replacements_match_case), matchCase, enabled) { matchCase = it; error = null }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                error = store.save(rule?.id, source, replacement, regex, matchCase)
                if (error == null) { source = ""; replacement = ""; regex = false; matchCase = false; onDone() }
            }, enabled = (if (regex) source.isNotEmpty() else source.isNotBlank() && replacement.isNotBlank()) && enabled) {
                Text(stringResource(if (rule == null) R.string.replacements_add else R.string.action_save))
            }
            if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
        when (error) {
            ReplacementError.Invalid -> R.string.replacements_invalid
            ReplacementError.Pattern -> R.string.replacements_invalid_pattern
            else -> null
        }?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun LabeledCheckbox(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

/** Marks a rule option with the glyph find-and-replace fields use for it, so the list needs no translated tag. */
@Composable
private fun RuleBadge(glyph: String, description: String, modifier: Modifier = Modifier, fontFamily: FontFamily? = null) {
    // A tint rather than an outline: the tag is a label, not a control.
    Text(glyph, modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall, fontFamily = fontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
