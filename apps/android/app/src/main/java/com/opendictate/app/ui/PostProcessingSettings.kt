package com.opendictate.app.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.PostProcessingSettings
import com.opendictate.app.model.ValueFormat
import com.opendictate.app.model.ValueFormatting
import com.opendictate.app.model.ValueKind

@Composable
internal fun PostProcessingSettingsPanel(settings: PostProcessingSettings, onChange: (PostProcessingSettings) -> Unit) {
    var formatsOpen by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.postprocessing_title)
    val context = LocalContext.current
    fun defaults(): ValueFormatting {
        val hourPreference = android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24)
        val twentyFourHour = when (hourPreference) { "24" -> true; "12" -> false; else -> null }
        return ValueFormatting.systemDefaults(Resources.getSystem().configuration.locales[0], twentyFourHour)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PostProcessingToggle(title, settings.enabled, { onChange(settings.copy(enabled = it)) }, heading = true)
        if (settings.enabled) {
            PostProcessingToggle(stringResource(R.string.postprocessing_punctuation), settings.punctuation,
                { onChange(settings.copy(punctuation = it)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { formatsOpen = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.postprocessing_values), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
                val valuesTitle = stringResource(R.string.postprocessing_values)
                Switch(settings.formatValues, onCheckedChange = { onChange(settings.copy(formatValues = it,
                    formats = settings.formats ?: if (it) defaults() else null)) },
                    modifier = Modifier.semantics { contentDescription = valuesTitle })
            }
            TextButton(onClick = { onChange(PostProcessingSettings(enabled = settings.enabled)) },
                enabled = settings != PostProcessingSettings(enabled = settings.enabled), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.postprocessing_reset_settings))
            }
            if (formatsOpen) ValueFormattingSheet(settings.formats ?: defaults(), defaults(),
                onDismiss = { formatsOpen = false }, onSave = { chosen ->
                    onChange(settings.copy(formatValues = true, formats = chosen)); formatsOpen = false
                })
        }
    }
}

@Composable
private fun PostProcessingToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, heading: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

private val FormatsSaver = listSaver<ValueFormatting, String>(
    save = { formats -> ValueKind.entries.flatMap { listOf(formats.entries.getValue(it).enabled.toString(), formats.entries.getValue(it).example) } },
    restore = { values -> ValueFormatting(ValueKind.entries.mapIndexed { i, kind -> kind to ValueFormat(values[i * 2].toBoolean(), values[i * 2 + 1]) }.toMap()) })

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ValueFormattingSheet(initial: ValueFormatting, defaults: ValueFormatting, onDismiss: () -> Unit, onSave: (ValueFormatting) -> Unit) {
    var draft by rememberSaveable(stateSaver = FormatsSaver) { mutableStateOf(initial) }
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.postprocessing_values), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.value_formats_hint), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ValueKind.entries.forEach { kind ->
                    val value = draft.entries.getValue(kind)
                    val label = kind.title(russian)
                    var expanded by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = value.enabled, role = Role.Checkbox, onValueChange = { draft = draft.updating(kind, value.copy(enabled = it)) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Checkbox(value.enabled, onCheckedChange = null)
                        }
                        OutlinedTextField(value.example, onValueChange = { draft = draft.updating(kind, value.copy(example = it)) },
                            modifier = Modifier.fillMaxWidth().keepFullBoundsInView().semantics { contentDescription = label }, singleLine = true, enabled = value.enabled,
                            isError = !value.isValid,
                            supportingText = if (!value.isValid) {{ Text(stringResource(R.string.value_formats_invalid)) }} else null,
                            trailingIcon = {
                                Box {
                                    IconButton(onClick = { expanded = true }, enabled = value.enabled) {
                                        Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.value_formats_choose, label))
                                    }
                                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.value_formats_system, defaults.entries.getValue(kind).example)) }, onClick = {
                                            draft = draft.updating(kind, value.copy(example = defaults.entries.getValue(kind).example)); expanded = false
                                        })
                                        kind.examples.forEach { example -> DropdownMenuItem(text = { Text(example) }, onClick = {
                                            draft = draft.updating(kind, value.copy(example = example)); expanded = false
                                        }) }
                                    }
                                }
                            })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { draft = defaults }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.value_formats_reset)) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.postprocessing_cancel)) }
                Button(onClick = { onSave(draft) }, enabled = draft.isValid, modifier = Modifier.heightIn(min = 48.dp).testTag("valueFormatsSave")) { Text(stringResource(R.string.settings_done)) }
            }
        }
    }
}
