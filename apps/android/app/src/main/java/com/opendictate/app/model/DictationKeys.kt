package com.opendictate.app.model

/** Android key codes that matter for binding; values match android.view.KeyEvent. */
object DictationKeys {
    const val NONE = 0
    const val BACK = 4
    const val HOME = 3
    const val POWER = 26
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val VOLUME_MUTE = 164
    const val APP_SWITCH = 187

    /**
     * Navigation and power keys must keep working, so they cannot start dictation. Android does not
     * deliver keys without a key code to accessibility services, so those cannot be bound either.
     */
    fun isBindable(keyCode: Int): Boolean =
        keyCode > NONE && keyCode !in setOf(BACK, HOME, POWER, APP_SWITCH)

    /** Volume keys keep their short press; other keys start dictation as soon as they go down. */
    fun holdsBeforeStart(keyCode: Int): Boolean = keyCode in setOf(VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE)
}
