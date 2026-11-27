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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import com.opendictate.app.model.ButtonTapAction
import com.opendictate.app.model.ButtonTapGesture
import com.opendictate.app.model.DictationKeys
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

private enum class StepStatus { PENDING, ACTIVE, DONE }

/**
 * Binds a button in three visible steps: press it three times, press both volume buttons so the app
 * can tell it from them, then try it. The accessibility service reports the volume presses and
 * keeps them from changing the volume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogButtonLearnSheet(
    accessibilityEnabled: Boolean,
    linkSet: Boolean,
    onLearned: (LogButtonSignature) -> Unit,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LogButtonLearner.State>(LogButtonLearner.State.AwaitingAccess) }
    var heard by remember { mutableIntStateOf(0) }
    var otherKeys by remember { mutableStateOf(emptySet<Int>()) }
    var lastPress by remember { mutableStateOf<ButtonTapAction?>(null) }
    var pressCount by remember { mutableIntStateOf(0) }
    val currentOnLearned by rememberUpdatedState(onLearned)
    // A button bound earlier must not start dictation while it is pressed here.
    DisposableEffect(Unit) {
        LogButton.learning = true
        onDispose { LogButton.learning = false }
    }
    LaunchedEffect(attempt) {
        heard = 0
        otherKeys = emptySet()
        lastPress = null
        val learner = LogButtonLearner(Process.myPid())
        val reader = LogReader.everything(RECENT_LOG_LINES) { line ->
            synchronized(learner) { learner.onLine(line, SystemClock.elapsedRealtime()) }
        }
        val otherButtons = launch {
            DictationKeyCapture.captured.collect { keyCode ->
                // Each button counts once, so the same one pressed twice does not pass for two.
                if (keyCode !in otherKeys) {
                    otherKeys = otherKeys + keyCode
                    synchronized(learner) { learner.onOtherButton(SystemClock.elapsedRealtime()) }
                }
            }
        }
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                state = synchronized(learner) { learner.state(now) }
                heard = synchronized(learner) { learner.heardPresses(now) }
                if (state == LogButtonLearner.State.AwaitingOtherButtons) DictationKeyCapture.begin() else DictationKeyCapture.end()
                val learned = state as? LogButtonLearner.State.Learned
                if (learned != null) {
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
    // The bound button is tried with the gesture that will run it, so the names match what happens.
    LaunchedEffect(attempt, linkSet) {
        val gesture = ButtonTapGesture()
        LogButton.learningPresses.collect { timeMs ->
            lastPress = gesture.onPress(timeMs, sessionActive = false, secondaryAvailable = linkSet)
            pressCount++
        }
    }

    val learned = state is LogButtonLearner.State.Learned
    val failed = state == LogButtonLearner.State.Failed || state == LogButtonLearner.State.Indistinct
    val othersReached = state == LogButtonLearner.State.AwaitingOtherButtons || state == LogButtonLearner.State.Indistinct || learned
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SettingsSurface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.log_button_learn_title), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel), tint = SettingsMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            Step(1, if (othersReached) StepStatus.DONE else StepStatus.ACTIVE,
                stringResource(if (othersReached) R.string.log_button_step_press_done else R.string.log_button_step_press)) {
                when (state) {
                    LogButtonLearner.State.AwaitingAccess -> StepNote(stringResource(R.string.log_button_learn_access), busy = true)
                    LogButtonLearner.State.Calibrating -> StepNote(stringResource(R.string.log_button_learn_wait), busy = true)
                    LogButtonLearner.State.Failed -> StepNote(stringResource(R.string.log_button_learn_failed), error = true)
                    else -> PressDots(heard)
                }
            }
            SettingsDivider(startInset = 42.dp)
            Step(2, when {
                learned -> StepStatus.DONE
                othersReached -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }, stringResource(if (learned) R.string.log_button_step_others_done else R.string.log_button_step_others)) {
                when {
                    state == LogButtonLearner.State.Indistinct -> StepNote(stringResource(R.string.log_button_learn_indistinct), error = true)
                    !accessibilityEnabled -> StepNote(stringResource(R.string.accessibility_enable_hint), error = true)
                    else -> {
                        StepNote(stringResource(R.string.log_button_step_others_hint))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VolumeChip(stringResource(R.string.log_button_volume_up), DictationKeys.VOLUME_UP in otherKeys)
                            VolumeChip(stringResource(R.string.log_button_volume_down), DictationKeys.VOLUME_DOWN in otherKeys)
                        }
                    }
                }
            }
            SettingsDivider(startInset = 42.dp)
            Step(3, if (learned) StepStatus.ACTIVE else StepStatus.PENDING,
                stringResource(if (learned) R.string.log_button_step_try_active else R.string.log_button_step_try)) {
                TryPad(lastPress, pressCount, linkSet)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                val restart: () -> Unit = {
                    state = LogButtonLearner.State.AwaitingAccess
                    attempt++
                }
                when {
                    learned -> {
                        TextButton(restart) { Text(stringResource(R.string.log_button_rebind)) }
                        Button(onDismiss) { Text(stringResource(R.string.settings_done)) }
                    }
                    failed -> {
                        TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Button(restart) { Text(stringResource(R.string.log_button_retry)) }
                    }
                    else -> TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

/** One numbered step; only the active one shows its content. */
@Composable
private fun Step(number: Int, status: StepStatus, title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(when (status) {
                    StepStatus.ACTIVE -> colors.primary
                    StepStatus.DONE -> colors.primaryContainer
                    StepStatus.PENDING -> Color.Transparent
                })
                .then(if (status == StepStatus.PENDING) Modifier.border(1.5.dp, colors.outlineVariant, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (status == StepStatus.DONE) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onPrimaryContainer)
            else Text(number.toString(), style = MaterialTheme.typography.labelLarge,
                color = if (status == StepStatus.ACTIVE) colors.onPrimary else SettingsMuted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).semantics { if (status == StepStatus.ACTIVE) liveRegion = LiveRegionMode.Polite }) {
            Text(title, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyLarge,
                color = if (status == StepStatus.PENDING) SettingsMuted else SettingsText)
            if (status == StepStatus.ACTIVE) {
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun StepNote(text: String, busy: Boolean = false, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else SettingsMuted)
    }
}

/** One dot per press; the next one expected is outlined in the primary color. */
@Composable
private fun PressDots(heard: Int) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.log_button_step_press_heard, heard, LogButtonLearner.PRESSES)
    StepNote(stringResource(R.string.log_button_step_press_hint))
    Spacer(Modifier.height(12.dp))
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(LogButtonLearner.PRESSES) { index ->
            val filled = index < heard
            val fill by animateColorAsState(if (filled) colors.primary else Color.Transparent, label = "press")
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(fill)
                    .border(2.dp, if (filled || index == heard) colors.primary else colors.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (filled) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = colors.onPrimary)
            }
        }
    }
    if (heard > 0) {
        Spacer(Modifier.height(8.dp))
        StepNote(if (heard >= LogButtonLearner.PRESSES) stringResource(R.string.log_button_step_press_checking) else description,
            busy = heard >= LogButtonLearner.PRESSES)
    }
}

@Composable
private fun VolumeChip(label: String, pressed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (pressed) colors.primaryContainer else Color.Transparent,
        border = if (pressed) null else BorderStroke(1.5.dp, colors.outline),
        modifier = Modifier.semantics { selected = pressed },
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pressed) {
                Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (pressed) colors.onPrimaryContainer else SettingsMuted)
        }
    }
}

/** Names each press of the bound button the way the app will act on it. Other buttons do nothing. */
@Composable
private fun TryPad(lastPress: ButtonTapAction?, pressCount: Int, linkSet: Boolean) {
    val colors = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressCount) {
        if (pressCount > 0) {
            scale.snapTo(1.14f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val double = lastPress == ButtonTapAction.SECONDARY
    StepNote(stringResource(R.string.log_button_try_hint))
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).scale(scale.value).clip(CircleShape).background(SettingsInset), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartButton, null, Modifier.size(28.dp), tint = SettingsText)
        }
        Spacer(Modifier.width(16.dp))
        Surface(shape = CircleShape, color = if (lastPress == null) SettingsInset else colors.primary) {
            Text(
                stringResource(when {
                    lastPress == null -> R.string.log_button_try_idle
                    double -> R.string.log_button_link_title
                    else -> R.string.log_button_try_single
                }),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (lastPress == null) SettingsMuted else colors.onPrimary,
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    GestureEffect(stringResource(R.string.log_button_try_single), stringResource(R.string.log_button_single_effect),
        current = lastPress != null && !double)
    GestureEffect(stringResource(R.string.log_button_link_title),
        stringResource(if (linkSet) R.string.log_button_double_effect else R.string.log_button_double_unused), current = double)
}

@Composable
private fun GestureEffect(gesture: String, effect: String, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        Text(effect, style = MaterialTheme.typography.bodySmall, color = if (current) SettingsText else SettingsMuted)
    }
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
