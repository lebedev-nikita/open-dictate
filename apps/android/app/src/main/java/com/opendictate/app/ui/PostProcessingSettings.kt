package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.PostProcessingPreset
import com.opendictate.app.model.PostProcessingSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PostProcessingSettingsPanel(settings: PostProcessingSettings, onChange: (PostProcessingSettings) -> Unit) {
    val title = stringResource(R.string.postprocessing_title)
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var prompt by rememberSaveable { mutableStateOf(settings.effectivePrompt) }
    var originalModel by rememberSaveable { mutableStateOf(settings.model) }
    var originalPrompt by rememberSaveable { mutableStateOf(settings.effectivePrompt) }
    val changed = model != originalModel || prompt != originalPrompt
    val draft = settings.copy(model = model, prompt = prompt.takeUnless { it == PostProcessingPreset.PUNCTUATION.text })
    fun load(value: PostProcessingSettings) {
        model = value.model; prompt = value.effectivePrompt
        originalModel = value.model; originalPrompt = value.effectivePrompt
    }
    LaunchedEffect(settings) { if (!changed) load(settings) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = settings.enabled, role = Role.Switch, onValueChange = { onChange(settings.copy(enabled = it)) })
            .semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(settings.enabled, onCheckedChange = null)
        }
        if (settings.enabled) {
            Text(stringResource(R.string.postprocessing_description), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
            OutlinedTextField(value = model, onValueChange = { model = it },
                label = { Text(stringResource(R.string.postprocessing_model)) },
                modifier = Modifier.fillMaxWidth().keepFullBoundsInView(), singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = !PostProcessingSettings(model = model).isValid,
                supportingText = if (!PostProcessingSettings(model = model).isValid) {{ Text(stringResource(R.string.postprocessing_invalid_model)) }} else null)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.postprocessing_prompt)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp).keepFullBoundsInView(),
                isError = prompt.toByteArray(Charsets.UTF_8).size > PostProcessingSettings.MAXIMUM_PROMPT_BYTES,
                supportingText = if (prompt.toByteArray(Charsets.UTF_8).size > PostProcessingSettings.MAXIMUM_PROMPT_BYTES)
                    {{ Text(stringResource(R.string.transcription_prompt_limit)) }} else null)
            Text(stringResource(R.string.postprocessing_add_instruction), style = MaterialTheme.typography.labelMedium, color = SettingsMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { prompt = PostProcessingPreset.PUNCTUATION.appendTo(prompt) },
                    enabled = !prompt.contains(PostProcessingPreset.PUNCTUATION.text), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.postprocessing_punctuation))
                }
                OutlinedButton(onClick = { prompt = PostProcessingPreset.NUMBERS.appendTo(prompt) },
                    enabled = !prompt.contains(PostProcessingPreset.NUMBERS.text), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.postprocessing_numbers))
                }
            }
            Text(stringResource(R.string.postprocessing_hint), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { prompt = PostProcessingPreset.PUNCTUATION.text },
                    enabled = prompt != PostProcessingPreset.PUNCTUATION.text, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.postprocessing_reset))
                }
                if (changed) TextButton(onClick = { load(settings) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.transcription_prompt_cancel))
                }
                Button(onClick = { onChange(draft); load(draft) }, enabled = changed && draft.isValid,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.transcription_prompt_save)) }
            }
        }
    }
}
