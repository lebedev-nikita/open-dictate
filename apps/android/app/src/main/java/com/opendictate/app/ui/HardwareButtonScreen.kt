@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R
import com.opendictate.app.model.DictationKeys
import com.opendictate.app.model.LogButtonSignature
import com.opendictate.app.service.LogButton

/** The one-line summary of the bound hardware button for the settings list. */
internal fun hardwareButtonSummary(resources: Resources, keyCode: Int, logButtonTag: String?): String = when {
    logButtonTag != null -> resources.getString(R.string.hardware_button_log_summary)
    else -> dictationKeySummary(resources, keyCode)
}

/**
 * Everything about the hardware button in one place: which button is bound, binding another, and
 * what its presses do. A button Android hides from apps appears here only after log access was
 * granted through adb; nothing else on the screen mentions the log.
 */
@Composable
internal fun HardwareButtonScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onBindKey: (Int) -> Unit,
    onLearned: (LogButtonSignature) -> Unit,
    onUnbind: () -> Unit,
    onMcpServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bindOpen by rememberSaveable { mutableStateOf(false) }
    val logStatus by LogButton.status.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val logBound = state.logButtonTag != null
    val keyBound = state.dictationKeyCode != DictationKeys.NONE
    val commands = state.mcpServers.any { it.activeTools.isNotEmpty() }

    Box(modifier.fillMaxSize().background(SettingsCanvas).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            ScreenHeader(stringResource(R.string.dictation_key_title), onBack)
            Column(Modifier.padding(horizontal = 20.dp)) {
                SettingsGroup {
                    InfoRow(
                        Icons.Outlined.SmartButton,
                        when {
                            logBound -> stringResource(R.string.hardware_button_bound)
                            keyBound -> dictationKeySummary(resources, state.dictationKeyCode)
                            else -> stringResource(R.string.dictation_key_none)
                        },
                        when {
                            logBound && logStatus == LogButton.Status.ACTIVE -> stringResource(R.string.hardware_button_log_active, state.logButtonTag.orEmpty())
                            logBound -> stringResource(R.string.log_button_needs_access)
                            keyBound -> stringResource(R.string.hardware_button_key_bound)
                            state.logButtonPermitted -> stringResource(R.string.hardware_button_none_any)
                            else -> stringResource(R.string.hardware_button_none)
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (logBound || keyBound) TextButton(onUnbind) { Text(stringResource(R.string.dictation_key_clear)) }
                        Button({ bindOpen = true }) {
                            Text(stringResource(if (logBound || keyBound) R.string.hardware_button_rebind else R.string.hardware_button_bind))
                        }
                    }
                }
                if (logBound || keyBound) {
                    SectionTitle(stringResource(R.string.hardware_button_actions))
                    SettingsGroup {
                        if (logBound) {
                            InfoRow(Icons.Outlined.TouchApp, stringResource(R.string.log_button_try_single), stringResource(R.string.hardware_button_single_effect))
                            SettingsDivider()
                            ActionRow(Icons.Outlined.Hub, stringResource(R.string.log_button_double),
                                stringResource(if (commands) R.string.hardware_button_double_effect else R.string.hardware_button_double_setup), onMcpServers)
                        } else {
                            InfoRow(Icons.Outlined.TouchApp, stringResource(R.string.hardware_button_hold), stringResource(R.string.button_bind_key_hint))
                        }
                    }
                }
            }
        }
    }
    if (bindOpen) ButtonBindSheet(state.accessibilityEnabled, state.logButtonPermitted, commands, onBindKey, onLearned) { bindOpen = false }
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.app_filter_back)) }
        Spacer(Modifier.width(8.dp))
        Text(title, color = SettingsText, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}

/** A settings row that only informs: same measures as an action row, without a chevron or a click. */
@Composable
internal fun InfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = SettingsMuted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
        }
    }
}
