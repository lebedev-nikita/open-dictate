@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.TranscriptionPrompt

@Composable
internal fun TranscriptionPromptSheet(
    customPrompt: String?,
    defaultPrompt: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep a draft independent of incoming cloud settings until Save is explicitly chosen.
    val original by rememberSaveable { mutableStateOf(customPrompt) }
    val defaultText = rememberSaveable { defaultPrompt }
    var text by rememberSaveable { mutableStateOf(customPrompt ?: defaultText) }
    var usesDefault by rememberSaveable { mutableStateOf(customPrompt == null) }
    val valid = TranscriptionPrompt.isValid(text)
    val changed = if (usesDefault) original != null else original != text
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SettingsSurface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.transcription_prompt_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.transcription_prompt_description), style = MaterialTheme.typography.bodyMedium,
                color = SettingsMuted)
            OutlinedTextField(value = text, onValueChange = {
                text = it
                if (it != defaultText) usesDefault = false
            }, label = { Text(stringResource(R.string.transcription_prompt_text)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 360.dp),
                isError = !valid,
                supportingText = if (!valid) {{ Text(stringResource(R.string.transcription_prompt_limit)) }} else null)
            Text(stringResource(R.string.transcription_prompt_hint), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            TextButton(onClick = { usesDefault = true; text = defaultText }, enabled = !usesDefault || text != defaultText) {
                Text(stringResource(R.string.transcription_prompt_reset))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.transcription_prompt_cancel)) }
                Button(onClick = { onSave(if (usesDefault) null else text); onDismiss() }, enabled = changed && valid) {
                    Text(stringResource(R.string.transcription_prompt_save))
                }
            }
        }
    }
}
