package com.opendictate.app.ui

import android.content.res.Resources
import android.view.KeyEvent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.opendictate.app.R
import com.opendictate.app.model.DictationKeys
import com.opendictate.app.service.DictationKeyCapture

/** Learns the next hardware key press through the accessibility service. */
@Composable
internal fun DictationKeyDialog(
    keyCode: Int,
    accessibilityEnabled: Boolean,
    onBind: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (accessibilityEnabled) {
        DisposableEffect(Unit) {
            DictationKeyCapture.begin()
            onDispose { DictationKeyCapture.end() }
        }
        LaunchedEffect(Unit) {
            DictationKeyCapture.captured.collect { onBind(it); onDismiss() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictation_key_title)) },
        text = {
            Text(stringResource(if (accessibilityEnabled) R.string.dictation_key_capture_hint else R.string.accessibility_enable_hint))
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        dismissButton = if (keyCode != DictationKeys.NONE) {
            { TextButton({ onClear(); onDismiss() }) { Text(stringResource(R.string.dictation_key_clear)) } }
        } else null,
    )
}

internal fun dictationKeySummary(resources: Resources, keyCode: Int): String {
    if (keyCode == DictationKeys.NONE) return resources.getString(R.string.dictation_key_none)
    val name = when (keyCode) {
        DictationKeys.VOLUME_UP -> resources.getString(R.string.dictation_key_volume_up)
        DictationKeys.VOLUME_DOWN -> resources.getString(R.string.dictation_key_volume_down)
        DictationKeys.VOLUME_MUTE -> resources.getString(R.string.dictation_key_volume_mute)
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").let { raw ->
            // Vendor keys without an Android name are reported as bare numbers.
            if (raw.all(Char::isDigit)) resources.getString(R.string.dictation_key_code, keyCode)
            else raw.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
        }
    }
    return if (DictationKeys.holdsBeforeStart(keyCode)) resources.getString(R.string.dictation_key_hold_summary, name) else name
}
