package com.opendictate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendictate.app.R

/** The switch for other apps' dictation and the apps the user has allowed so far. */
@Composable
internal fun ExternalDictationSettings(
    enabled: Boolean,
    apps: List<InstalledApp>,
    onEnabledChange: (Boolean) -> Unit,
    onRevoke: (String) -> Unit,
) {
    ToggleRow(Icons.Outlined.RecordVoiceOver, stringResource(R.string.external_dictation_title),
        stringResource(R.string.external_dictation_subtitle), enabled, onEnabledChange)
    AnimatedVisibility(enabled) {
        Column {
            if (apps.isEmpty()) {
                Text(stringResource(R.string.external_dictation_no_apps),
                    Modifier.padding(start = SettingsRowTextStart, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            }
            apps.forEach { app ->
                SettingsDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = SettingsRowTextStart, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onRevoke(app.packageName) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.external_dictation_revoke, app.label), tint = SettingsMuted)
                    }
                }
            }
        }
    }
}
