package com.opendictate.app.model

/**
 * Broadcast commands from automation apps. Commands cannot name a sender, so they only control the
 * single dictation session and never return text; results go to the focused field.
 */
enum class DictationCommand(val action: String) {
    START("com.opendictate.app.action.DICTATION_START"),
    STOP("com.opendictate.app.action.DICTATION_STOP"),
    TOGGLE("com.opendictate.app.action.DICTATION_TOGGLE"),
    CANCEL("com.opendictate.app.action.DICTATION_CANCEL"),
    ;

    fun effect(sessionActive: Boolean): DictationCommandEffect = when (this) {
        START -> if (sessionActive) DictationCommandEffect.NONE else DictationCommandEffect.START
        STOP -> if (sessionActive) DictationCommandEffect.STOP else DictationCommandEffect.NONE
        TOGGLE -> if (sessionActive) DictationCommandEffect.STOP else DictationCommandEffect.START
        CANCEL -> if (sessionActive) DictationCommandEffect.CANCEL else DictationCommandEffect.NONE
    }

    companion object {
        fun fromAction(action: String?): DictationCommand? = entries.firstOrNull { it.action == action }
    }
}

enum class DictationCommandEffect { START, STOP, CANCEL, NONE }
