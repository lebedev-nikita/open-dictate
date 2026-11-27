package com.opendictate.app.ui

import android.os.Process
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendictate.app.R
import com.opendictate.app.model.LogButtonLearner
import com.opendictate.app.model.LogButtonSignature
import com.opendictate.app.model.TextLink
import com.opendictate.app.service.DictationKeyCapture
import com.opendictate.app.service.LogButton
import com.opendictate.app.service.LogReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shown only after READ_LOGS was granted through adb; everyone else never sees these rows. */
@Composable
internal fun LogButtonSettings(
    enabled: Boolean,
    learnedTag: String?,
    link: String,
    onEnabledChange: (Boolean) -> Unit,
    onLearn: () -> Unit,
    onEditLink: () -> Unit,
) {
    val status by LogButton.status.collectAsStateWithLifecycle()
    ToggleRow(Icons.Outlined.SmartButton, stringResource(R.string.log_button_title),
        stringResource(when {
            !enabled -> R.string.log_button_subtitle
            learnedTag == null -> R.string.log_button_not_learned
            status == LogButton.Status.ACTIVE -> R.string.log_button_active
            else -> R.string.log_button_needs_access
        }), enabled, onEnabledChange)
    AnimatedVisibility(enabled) {
        Column {
            SettingsDivider()
            ActionRow(Icons.Outlined.TouchApp, stringResource(R.string.log_button_learn_title),
                learnedTag?.let { stringResource(R.string.log_button_learned_summary, it) }
                    ?: stringResource(R.string.log_button_learn_summary), onLearn)
            SettingsDivider()
            ActionRow(Icons.Outlined.Link, stringResource(R.string.log_button_link_title),
                link.ifEmpty { stringResource(R.string.log_button_link_none) }, onEditLink)
        }
    }
}

/**
 * Finds the button's log line while the user presses the button three times, then the volume
 * buttons, which the accessibility service reports and keeps from changing the volume.
 */
@Composable
internal fun LogButtonLearnDialog(accessibilityEnabled: Boolean, onLearned: (LogButtonSignature) -> Unit, onDismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LogButtonLearner.State>(LogButtonLearner.State.AwaitingAccess) }
    val currentOnLearned by rememberUpdatedState(onLearned)
    val checkedPresses by LogButton.learningPresses.collectAsStateWithLifecycle()
    // A button learned earlier must not start dictation while it is pressed here.
    DisposableEffect(Unit) {
        LogButton.learning = true
        onDispose { LogButton.learning = false }
    }
    LaunchedEffect(attempt) {
        val learner = LogButtonLearner(Process.myPid())
        val reader = LogReader.everything(RECENT_LOG_LINES) { line ->
            synchronized(learner) { learner.onLine(line, SystemClock.elapsedRealtime()) }
        }
        val otherButtons = launch {
            DictationKeyCapture.captured.collect { synchronized(learner) { learner.onOtherButton(SystemClock.elapsedRealtime()) } }
        }
        try {
            while (true) {
                state = synchronized(learner) { learner.state(SystemClock.elapsedRealtime()) }
                if (state == LogButtonLearner.State.AwaitingOtherButtons) DictationKeyCapture.begin() else DictationKeyCapture.end()
                val learned = state as? LogButtonLearner.State.Learned
                if (learned != null) {
                    LogButton.learning = true
                    currentOnLearned(learned.signature)
                    // Log access lasts while a reader is open; the watcher's reader takes over first.
                    delay(READER_HANDOVER_MS)
                    break
                }
                if (state == LogButtonLearner.State.Failed || state == LogButtonLearner.State.Indistinct) break
                delay(POLL_MS)
            }
        } finally {
            DictationKeyCapture.end()
            otherButtons.cancel()
            reader.close()
        }
    }
    val waiting = state !is LogButtonLearner.State.Learned && state != LogButtonLearner.State.Failed &&
        state != LogButtonLearner.State.Indistinct
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_button_learn_title)) },
        text = {
            Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                if (waiting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                }
                Text(when (state) {
                    LogButtonLearner.State.AwaitingAccess -> stringResource(R.string.log_button_learn_access)
                    LogButtonLearner.State.Calibrating -> stringResource(R.string.log_button_learn_wait)
                    LogButtonLearner.State.AwaitingPresses -> stringResource(R.string.log_button_learn_press)
                    LogButtonLearner.State.AwaitingOtherButtons -> stringResource(
                        if (accessibilityEnabled) R.string.log_button_learn_others else R.string.accessibility_enable_hint)
                    // The count lets the user see that it follows the button and nothing else.
                    is LogButtonLearner.State.Learned -> stringResource(R.string.log_button_learn_done, checkedPresses)
                    LogButtonLearner.State.Failed -> stringResource(R.string.log_button_learn_failed)
                    LogButtonLearner.State.Indistinct -> stringResource(R.string.log_button_learn_indistinct)
                })
            }
        },
        confirmButton = {
            if (state is LogButtonLearner.State.Learned) TextButton(onDismiss) { Text(stringResource(R.string.settings_done)) }
            else TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = if (waiting) null else {
            {
                TextButton({
                    state = LogButtonLearner.State.AwaitingAccess
                    attempt++
                }) { Text(stringResource(R.string.log_button_retry)) }
            }
        },
    )
}

/** Sets the link that receives the text dictated after a double press. */
@Composable
internal fun LogButtonLinkDialog(link: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(link) }
    val trimmed = text.trim()
    val invalid = trimmed.isNotEmpty() && !TextLink.isValid(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_button_link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.log_button_link_hint))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(LINK_EXAMPLE) }, singleLine = true, isError = invalid,
                    supportingText = if (invalid) { { Text(stringResource(R.string.log_button_link_error, LINK_EXAMPLE)) } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton({ onSave(trimmed); onDismiss() }, enabled = !invalid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val LINK_EXAMPLE = "t3code://new?prompt=${TextLink.PLACEHOLDER}"
private const val RECENT_LOG_LINES = 5_000
private const val POLL_MS = 100L
private const val READER_HANDOVER_MS = 1_000L
