package com.opendictate.app.ui

import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.model.ButtonAction
import com.opendictate.app.model.ButtonBindings
import com.opendictate.app.model.ButtonGesture
import com.opendictate.app.model.ButtonGestureRecognizer
import com.opendictate.app.model.ButtonSignal
import com.opendictate.app.model.LogButtonLearner
import com.opendictate.app.model.PhoneButton
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
import com.opendictate.app.model.LogButtonEdge
import kotlinx.coroutines.Job
import com.opendictate.app.model.DictationKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class StepStatus { PENDING, ACTIVE, DONE }

/**
 * Binds one hardware button. A button Android reports as a key is bound by its first press. With
 * adb-granted log access, a button Android hides is found in three visible steps: press it three
 * times, press both volume buttons so the app can tell it from them, then try it. The accessibility
 * service reports the keys and keeps the volume from changing meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ButtonBindSheet(
    accessibilityEnabled: Boolean,
    logPermitted: Boolean,
    bindings: ButtonBindings,
    actionNames: Map<ButtonAction, String>,
    onBound: (PhoneButton) -> Unit,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LogButtonLearner.State>(LogButtonLearner.State.AwaitingAccess) }
    var heard by remember { mutableIntStateOf(0) }
    var otherKeys by remember { mutableStateOf(emptySet<Int>()) }
    var boundKey by remember { mutableStateOf<Int?>(null) }
    var lastGesture by remember { mutableStateOf<ButtonGesture?>(null) }
    var pressCount by remember { mutableIntStateOf(0) }
    val currentOnBound by rememberUpdatedState(onBound)
    // A button bound earlier must not start dictation while it is pressed here.
    DisposableEffect(Unit) {
        LogButton.learning = true
        onDispose { LogButton.learning = false }
    }
    LaunchedEffect(attempt) {
        heard = 0
        otherKeys = emptySet()
        boundKey = null
        lastGesture = null
        val learner = if (logPermitted) LogButtonLearner(Process.myPid()) else null
        val reader = learner?.let {
            LogReader.everything(RECENT_LOG_LINES) { line -> synchronized(it) { it.onLine(line, SystemClock.elapsedRealtime()) } }
        }
        val keys = launch {
            DictationKeyCapture.captured.collect { keyCode ->
                when {
                    // Before the hidden button's presses are found, a key Android reports is the button itself.
                    learner == null || state != LogButtonLearner.State.AwaitingOtherButtons -> if (boundKey == null) {
                        boundKey = keyCode
                        currentOnBound(PhoneButton.Key(keyCode))
                    }
                    // Each button counts once, so the same one pressed twice does not pass for two.
                    keyCode !in otherKeys -> {
                        otherKeys = otherKeys + keyCode
                        synchronized(learner) { learner.onOtherButton(SystemClock.elapsedRealtime()) }
                    }
                }
            }
        }
        try {
            DictationKeyCapture.begin()
            while (boundKey == null) {
                if (learner != null) {
                    val now = SystemClock.elapsedRealtime()
                    state = synchronized(learner) { learner.state(now) }
                    heard = synchronized(learner) { learner.heardPresses(now) }
                    val learned = state as? LogButtonLearner.State.Learned
                    if (learned != null) {
                        currentOnBound(PhoneButton.Log(learned.signature))
                        // Log access lasts while a reader is open; the watcher's reader takes over first.
                        DictationKeyCapture.end()
                        delay(READER_HANDOVER_MS)
                        break
                    }
                    if (state == LogButtonLearner.State.Failed || state == LogButtonLearner.State.Indistinct) break
                }
                delay(POLL_MS)
            }
        } finally {
            DictationKeyCapture.end()
            keys.cancel()
            reader?.close()
        }
    }
    val learnedButton = (state as? LogButtonLearner.State.Learned)?.signature?.let(PhoneButton::Log)
    // Every gesture the button offers is told apart, so the user sees which one each press makes.
    LaunchedEffect(attempt, learnedButton) {
        val button = learnedButton ?: return@LaunchedEffect
        val recognizer = ButtonGestureRecognizer()
        var holdTimeout: Job? = null
        fun show(signal: ButtonSignal) {
            if (signal !is ButtonSignal.Start) return
            lastGesture = signal.gesture
            pressCount++
        }
        LogButton.learningPresses.collect { event ->
            if (event.signature != button.signature) return@collect
            holdTimeout?.cancel()
            val signal = when (event.edge) {
                LogButtonEdge.PRESS -> recognizer.onDown(event.timeMs, button.gestures, keepsShortPress = false, sessionActive = false)
                LogButtonEdge.RELEASE -> recognizer.onUp(event.timeMs)
            }
            if (signal == ButtonSignal.WaitForHold) {
                holdTimeout = launch {
                    delay(ButtonGestureRecognizer.HOLD_THRESHOLD_MS)
                    show(recognizer.onHoldTimeout())
                }
            } else {
                show(signal)
            }
        }
    }

    val key = boundKey
    val learned = state is LogButtonLearner.State.Learned
    val failed = state == LogButtonLearner.State.Failed || state == LogButtonLearner.State.Indistinct
    val othersReached = state == LogButtonLearner.State.AwaitingOtherButtons || state == LogButtonLearner.State.Indistinct || learned
    val restart: () -> Unit = {
        state = LogButtonLearner.State.AwaitingAccess
        attempt++
    }
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
            when {
                key != null -> BoundKey(dictationKeyName(LocalContext.current.resources, key))
                !logPermitted -> Column(Modifier.padding(vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.button_bind_press), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    if (accessibilityEnabled) StepNote(stringResource(R.string.button_bind_press_hint), busy = true)
                    else StepNote(stringResource(R.string.accessibility_enable_hint), error = true)
                }
                else -> {
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
                        learnedButton?.let { button ->
                            TryPad(lastGesture, pressCount, button.gestures.associateWith { gesture ->
                                bindings.actionFor(button, gesture)?.let(actionNames::getValue)
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                when {
                    key != null || learned -> {
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

/** The outcome for a button Android reports as a key: it is bound by its first press. */
@Composable
private fun BoundKey(name: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.button_bind_key_done, name), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            StepNote(stringResource(R.string.button_bind_key_hint))
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

/** Names each gesture of the bound button and the action it runs. Other buttons do nothing. */
@Composable
private fun TryPad(lastGesture: ButtonGesture?, pressCount: Int, effects: Map<ButtonGesture, String?>) {
    val colors = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressCount) {
        if (pressCount > 0) {
            scale.snapTo(1.14f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    StepNote(stringResource(R.string.log_button_try_hint))
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).scale(scale.value).clip(CircleShape).background(SettingsInset), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartButton, null, Modifier.size(28.dp), tint = SettingsText)
        }
        Spacer(Modifier.width(16.dp))
        Surface(shape = CircleShape, color = if (lastGesture == null) SettingsInset else colors.primary) {
            Text(
                stringResource(lastGesture?.labelRes() ?: R.string.log_button_try_idle),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (lastGesture == null) SettingsMuted else colors.onPrimary,
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    ButtonGesture.entries.filter { it in effects }.forEach { gesture ->
        GestureEffect(stringResource(gesture.labelRes()), effects[gesture] ?: stringResource(R.string.log_button_double_unused),
            current = gesture == lastGesture)
    }
}

@Composable
private fun GestureEffect(gesture: String, effect: String, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        Text(effect, style = MaterialTheme.typography.bodySmall, color = if (current) SettingsText else SettingsMuted)
    }
}

private const val RECENT_LOG_LINES = 5_000
private const val POLL_MS = 100L
private const val READER_HANDOVER_MS = 1_000L
