@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.opendictate.app.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R
import com.opendictate.app.model.ButtonAction
import com.opendictate.app.model.ButtonBindings
import com.opendictate.app.model.ButtonGesture
import com.opendictate.app.model.PhoneButton
import com.opendictate.app.service.LogButton

/** The one-line summary of the bound phone buttons for the settings list. */
internal fun hardwareButtonSummary(resources: Resources, bindings: ButtonBindings): String {
    val count = bindings.bindings.size
    return if (count == 0) resources.getString(R.string.phone_buttons_none)
    else resources.getQuantityString(R.plurals.phone_buttons_summary, count, count)
}

private fun ButtonAction.icon(): ImageVector = when (this) {
    ButtonAction.DICTATION -> Icons.Outlined.Mic
    ButtonAction.ALTERNATE_DICTATION -> Icons.Outlined.RecordVoiceOver
    ButtonAction.VOICE_EDIT -> Icons.Outlined.EditNote
    ButtonAction.AGENT_COMMAND -> Icons.Outlined.SmartToy
}

/**
 * Every action a phone button can run, each with the button and gesture that runs it. A button
 * Android hides from apps can be bound only after log access was granted through adb; nothing else
 * on the screen mentions the log until such a button is bound.
 */
@Composable
internal fun HardwareButtonScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onBind: (ButtonAction, PhoneButton, ButtonGesture) -> Unit,
    onUnbind: (ButtonAction) -> Unit,
    onMcpServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openAction by rememberSaveable { mutableStateOf<ButtonAction?>(null) }
    val logStatus by LogButton.status.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val bindings = state.buttonBindings
    val commands = state.mcpServers.any { it.activeTools.isNotEmpty() }
    val postProcessing = state.postProcessing.forMode(state.model).enabled
    val hiddenButtons = bindings.buttons.filterIsInstance<PhoneButton.Log>()

    Box(modifier.fillMaxSize().background(SettingsCanvas).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            ScreenHeader(stringResource(R.string.dictation_key_title), onBack)
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(if (state.logButtonPermitted) R.string.phone_buttons_intro_any else R.string.phone_buttons_intro),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    ButtonAction.entries.forEachIndexed { index, action ->
                        if (index > 0) SettingsDivider()
                        val binding = bindings.forAction(action)
                        ActionRow(action.icon(), stringResource(action.labelRes(postProcessing)), when {
                            action == ButtonAction.AGENT_COMMAND && !commands -> stringResource(R.string.agent_command_needs_server)
                            binding == null -> stringResource(R.string.dictation_key_none)
                            else -> stringResource(R.string.phone_button_binding, phoneButtonName(resources, binding.button, bindings),
                                stringResource(binding.gesture.labelRes()))
                        }) { openAction = action }
                    }
                }
                if (hiddenButtons.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.phone_buttons_hidden_title))
                    SettingsGroup {
                        hiddenButtons.forEachIndexed { index, button ->
                            if (index > 0) SettingsDivider()
                            InfoRow(Icons.Outlined.SmartButton, phoneButtonName(resources, button, bindings),
                                if (logStatus == LogButton.Status.ACTIVE) stringResource(R.string.hardware_button_log_active, button.signature.tag)
                                else stringResource(R.string.log_button_needs_access))
                        }
                    }
                }
            }
        }
    }
    openAction?.let { action ->
        ActionBindingSheet(
            action = action,
            title = stringResource(action.labelRes(postProcessing)),
            state = state,
            commandsAvailable = commands,
            onBind = { button, gesture -> onBind(action, button, gesture) },
            onUnbind = { onUnbind(action) },
            onMcpServers = {
                openAction = null
                onMcpServers()
            },
            onDismiss = { openAction = null },
        )
    }
}

/** Chooses the button and the gesture that run one action. Each choice is saved at once. */
@Composable
private fun ActionBindingSheet(
    action: ButtonAction,
    title: String,
    state: MainUiState,
    commandsAvailable: Boolean,
    onBind: (PhoneButton, ButtonGesture) -> Unit,
    onUnbind: () -> Unit,
    onMcpServers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val bindings = state.buttonBindings
    val binding = bindings.forAction(action)
    // A button whose gestures are all taken stays chosen here without being saved.
    var unsavedButton by remember { mutableStateOf<PhoneButton?>(null) }
    var bindOpen by remember { mutableStateOf(false) }
    val button = unsavedButton ?: binding?.button
    val postProcessing = state.postProcessing.forMode(state.model).enabled
    val actionNames = ButtonAction.entries.associateWith { stringResource(it.labelRes(postProcessing)) }
    val choose: (PhoneButton) -> Unit = { chosen ->
        val gesture = bindings.freeGesture(action, chosen, binding?.gesture)
        if (gesture == null) {
            unsavedButton = chosen
        } else {
            unsavedButton = null
            onBind(chosen, gesture)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SettingsSurface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel), tint = SettingsMuted)
                }
            }
            if (action == ButtonAction.AGENT_COMMAND && !commandsAvailable) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.agent_command_needs_server_hint), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
                TextButton(onMcpServers, Modifier.padding(start = 0.dp)) { Text(stringResource(R.string.mcp_servers_title)) }
            }
            SheetLabel(stringResource(R.string.phone_button_label))
            Column(Modifier.selectableGroup()) {
                (bindings.buttons + listOfNotNull(button)).distinct().forEach { known ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(known == button, role = Role.RadioButton) { choose(known) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(known == button, onClick = null, Modifier.padding(end = 12.dp))
                        Text(phoneButtonName(resources, known, bindings), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(false, role = Role.Button) { bindOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.padding(end = 12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.phone_button_bind_new), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (button != null) {
                val taken = bindings.takenGestures(button, action)
                val selected = binding?.gesture?.takeIf { binding.button == button }
                SheetLabel(stringResource(R.string.button_gesture_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonGesture.entries.filter { it in button.gestures }.forEach { gesture ->
                        val isSelected = gesture == selected
                        FilterChip(
                            selected = isSelected,
                            onClick = { onBind(button, gesture); unsavedButton = null },
                            label = { Text(stringResource(gesture.labelRes()), maxLines = 1) },
                            enabled = gesture !in taken,
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                val notes = buildList {
                    when (selected) {
                        ButtonGesture.HOLD -> add(stringResource(R.string.button_gesture_hold_hint))
                        ButtonGesture.PRESS, ButtonGesture.DOUBLE_PRESS -> add(stringResource(R.string.button_gesture_toggle_hint))
                        null -> if (taken.keys.containsAll(button.gestures)) add(stringResource(R.string.button_gesture_all_taken))
                    }
                    taken.forEach { (gesture, other) ->
                        add(stringResource(R.string.button_gesture_taken, stringResource(gesture.labelRes()), actionNames.getValue(other)))
                    }
                    if (button.keepsShortPress) add(stringResource(R.string.button_gesture_volume_hold))
                    else if (ButtonGesture.HOLD !in button.gestures) add(stringResource(R.string.button_gesture_no_release))
                }
                notes.forEach { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (binding != null) {
                    TextButton({
                        unsavedButton = null
                        onUnbind()
                    }) { Text(stringResource(R.string.dictation_key_clear)) }
                }
                Button(onDismiss) { Text(stringResource(R.string.settings_done)) }
            }
        }
    }
    if (bindOpen) {
        ButtonBindSheet(
            accessibilityEnabled = state.accessibilityEnabled,
            logPermitted = state.logButtonPermitted,
            bindings = bindings,
            actionNames = actionNames,
            onBound = choose,
        ) { bindOpen = false }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = SettingsMuted)
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

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.app_filter_back)) }
        Spacer(Modifier.width(8.dp))
        Text(title, color = SettingsText, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}
