package com.opendictate.app.ui

import android.content.res.Resources
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
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
import com.opendictate.app.model.ValueFormatRule
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Add
import java.util.Locale
import org.json.JSONObject
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
        val locales = Resources.getSystem().configuration.locales
        return ValueFormatting.systemDefaults(locales[0], twentyFourHour, preferredLocales = List(locales.size()) { locales[it] })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PostProcessingToggle(title, settings.enabled, { onChange(settings.copy(enabled = it)) }, heading = true)
        if (settings.enabled) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PostProcessingToggle(stringResource(R.string.postprocessing_punctuation), settings.punctuation,
                { onChange(settings.copy(punctuation = it)) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button) { formatsOpen = true }
                    .padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.postprocessing_values), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
                val valuesTitle = stringResource(R.string.postprocessing_values)
                Switch(settings.formatValues, onCheckedChange = { onChange(settings.copy(formatValues = it,
                    formats = settings.formats ?: if (it) defaults() else null)) },
                    modifier = Modifier.semantics { contentDescription = valuesTitle })
            }
            TextButton(onClick = { onChange(PostProcessingSettings(enabled = settings.enabled)) },
                enabled = settings != PostProcessingSettings(enabled = settings.enabled),
                contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
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
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

private val FormatsSaver = Saver<ValueFormatting, String>(save = { it.json() }, restore = { ValueFormatting.fromJson(JSONObject(it)) })

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ValueFormattingSheet(initial: ValueFormatting, defaults: ValueFormatting, onDismiss: () -> Unit, onSave: (ValueFormatting) -> Unit) {
    var draft by rememberSaveable(stateSaver = FormatsSaver) { mutableStateOf(initial) }
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var kindId by rememberSaveable { mutableStateOf(ValueKind.INTEGER.id) }
    var language by rememberSaveable { mutableStateOf("*") }
    var example by rememberSaveable { mutableStateOf("") }
    fun edit(rule: ValueFormatRule?) {
        editing = rule?.id ?: "new"
        kindId = (rule?.kind ?: ValueKind.INTEGER).id; language = rule?.language ?: "*"
        example = rule?.example ?: draft.entries.getValue(ValueKind.INTEGER).example
    }
    val languages = remember(russian) { ValueFormatting.supportedLanguages.sortedWith(compareBy(java.text.Collator.getInstance(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH)) { Locale.forLanguageTag(it).getDisplayLanguage(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH) }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false, dragHandle = null, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(Modifier.fillMaxSize().testTag("valueFormattingViewport").padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (editing == null) R.string.postprocessing_values else R.string.value_rule_title), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing == null) {
                        var typesOpen by remember { mutableStateOf(false) }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                OutlinedButton(onClick = { typesOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.value_rule_types)); Icon(Icons.Outlined.ArrowDropDown, null)
                                }
                                DropdownMenu(typesOpen, onDismissRequest = { typesOpen = false }) {
                                    ValueKind.entries.forEach { kind ->
                                        val value = draft.entries.getValue(kind)
                                        DropdownMenuItem(text = { Text(kind.title(russian)) }, trailingIcon = { Checkbox(value.enabled, onCheckedChange = null) }, onClick = { draft = draft.updating(kind, value.copy(enabled = !value.enabled)) })
                                    }
                                }
                            }
                            FilledTonalButton(onClick = { edit(null) }, enabled = draft.rules.size < 64, modifier = Modifier.heightIn(min = 48.dp).testTag("addValueRule")) {
                                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.value_rule_add))
                            }
                        }
                        if (draft.rules.isEmpty()) Text(stringResource(R.string.value_rules_automatic), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        draft.sortedRules(russian).groupBy { it.kind }.forEach { (kind, rules) ->
                            Text(kind.title(russian), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            rules.forEach { rule ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button) { edit(rule) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(rule.languageTitle(russian), style = MaterialTheme.typography.bodyMedium)
                                        Text(rule.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { draft = draft.removingRule(rule.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.value_rule_delete, kind.title(russian), rule.languageTitle(russian))) }
                                }
                            }
                            HorizontalDivider()
                        }
                    } else {
                        val kind = ValueKind.entries.first { it.id == kindId }
                        RuleChoice(stringResource(R.string.value_rule_type), kind.title(russian)) { close ->
                            ValueKind.entries.forEach { chosen -> DropdownMenuItem(text = { Text(chosen.title(russian)) }, onClick = {
                                if (kindId != chosen.id) { kindId = chosen.id; example = draft.entries.getValue(chosen).example }; close()
                            }) }
                        }
                        RuleChoice(stringResource(R.string.value_rule_language), ValueFormatRule(kind, language.takeUnless { it == "*" }, example).languageTitle(russian)) { close ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.value_rule_all_languages)) }, onClick = { language = "*"; close() })
                            languages.forEach { code -> DropdownMenuItem(text = { Text(ValueFormatRule(kind, code, example).languageTitle(russian)) }, onClick = { language = code; close() }) }
                        }
                        Text(stringResource(R.string.value_rule_source), style = MaterialTheme.typography.labelLarge)
                        Text(kind.source(russian), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("valueRuleSource"))
                        var presetsOpen by remember { mutableStateOf(false) }
                        OutlinedTextField(example, onValueChange = { example = it }, label = { Text(stringResource(R.string.value_rule_result)) },
                            modifier = Modifier.fillMaxWidth().keepFullBoundsInView().testTag("valueRuleExample"), singleLine = true, isError = !ValueFormat(example = example).isValid,
                            supportingText = if (!ValueFormat(example = example).isValid) {{ Text(stringResource(R.string.value_formats_invalid)) }} else null,
                            trailingIcon = { Box {
                                IconButton(onClick = { presetsOpen = true }) { Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.value_formats_choose, kind.title(russian))) }
                                DropdownMenu(presetsOpen, onDismissRequest = { presetsOpen = false }) {
                                    kind.examples.forEach { preset -> DropdownMenuItem(text = { Text(preset) }, onClick = { example = preset; presetsOpen = false }) }
                                }
                            } })
                        val rule = ValueFormatRule(kind, language.takeUnless { it == "*" }, example)
                        if (rule.isValid && !draft.settingRule(rule, editing).isValid) Text(stringResource(R.string.value_rule_too_large), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (draft.rules.any { it.id == rule.id && it.id != editing }) Text(stringResource(R.string.value_rule_duplicate), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (editing == null) {
                        TextButton(onClick = { draft = defaults }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.value_formats_reset)) }
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.postprocessing_cancel)) }
                        Button(onClick = { onSave(draft) }, enabled = draft.isValid, modifier = Modifier.heightIn(min = 48.dp).testTag("valueFormatsSave")) { Text(stringResource(R.string.settings_done)) }
                    } else {
                        val rule = ValueFormatRule(ValueKind.entries.first { it.id == kindId }, language.takeUnless { it == "*" }, example)
                        val next = draft.settingRule(rule, editing)
                        TextButton(onClick = { editing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.postprocessing_cancel)) }
                        Button(onClick = { draft = next; editing = null }, enabled = rule.isValid && next.isValid && draft.rules.none { it.id == rule.id && it.id != editing },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("valueRuleSave")) { Text(stringResource(R.string.value_rule_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleChoice(label: String, value: String, content: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(value, Modifier.weight(1f)); Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) { content { expanded = false } }
        }
    }
}
