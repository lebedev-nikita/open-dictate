package com.opendictate.app.ui

import android.content.res.Resources
import android.view.KeyEvent
import com.opendictate.app.R
import com.opendictate.app.model.DictationKeys

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
