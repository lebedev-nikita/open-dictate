package com.opendictate.app.ui

import android.content.res.Resources
import android.view.KeyEvent
import com.opendictate.app.R
import com.opendictate.app.model.ButtonAction
import com.opendictate.app.model.ButtonBindings
import com.opendictate.app.model.ButtonGesture
import com.opendictate.app.model.DictationKeys
import com.opendictate.app.model.PhoneButton

internal fun dictationKeyName(resources: Resources, keyCode: Int): String = when (keyCode) {
    DictationKeys.VOLUME_UP -> resources.getString(R.string.dictation_key_volume_up)
    DictationKeys.VOLUME_DOWN -> resources.getString(R.string.dictation_key_volume_down)
    DictationKeys.VOLUME_MUTE -> resources.getString(R.string.dictation_key_volume_mute)
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").let { raw ->
        // Vendor keys without an Android name are reported as bare numbers.
        if (raw.all(Char::isDigit)) resources.getString(R.string.dictation_key_code, keyCode)
        else raw.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
    }
}

/** A button found in the log has no name of its own; several are numbered in the order they were bound. */
internal fun phoneButtonName(resources: Resources, button: PhoneButton, bindings: ButtonBindings): String = when (button) {
    is PhoneButton.Key -> dictationKeyName(resources, button.keyCode)
    is PhoneButton.Log -> {
        val hidden = bindings.buttons.filterIsInstance<PhoneButton.Log>()
        val index = hidden.indexOf(button)
        if (hidden.size > 1 && index >= 0) resources.getString(R.string.phone_button_hidden_numbered, index + 1)
        else resources.getString(R.string.phone_button_hidden)
    }
}

internal fun ButtonGesture.labelRes(): Int = when (this) {
    ButtonGesture.PRESS -> R.string.button_gesture_press
    ButtonGesture.HOLD -> R.string.button_gesture_hold
    ButtonGesture.DOUBLE_PRESS -> R.string.button_gesture_double
}

/** The alternate dictation is named by what it does, the opposite of ordinary dictation's post-processing. */
internal fun ButtonAction.labelRes(postProcessingEnabled: Boolean): Int = when (this) {
    ButtonAction.DICTATION -> R.string.button_action_dictation
    ButtonAction.ALTERNATE_DICTATION ->
        if (postProcessingEnabled) R.string.button_action_dictation_raw else R.string.button_action_dictation_processed
    ButtonAction.VOICE_EDIT -> R.string.button_action_voice_edit
    ButtonAction.AGENT_COMMAND -> R.string.button_action_agent_command
}
