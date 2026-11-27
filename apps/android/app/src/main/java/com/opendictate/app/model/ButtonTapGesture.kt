package com.opendictate.app.model

enum class ButtonTapAction {
    /** Start dictation into the focused field. */
    START,
    STOP,
    /** Undo the start made by the first press and dictate a command for the assistant instead. */
    SECONDARY,
}

/**
 * Taps of a button that only reports presses, whatever detects them. One press toggles dictation
 * at once; a second press soon after a starting press switches to the secondary action, so the
 * single press never waits for a possible double press.
 */
class ButtonTapGesture(private val doublePressWindowMs: Long = DOUBLE_PRESS_WINDOW_MS) {
    private var startedAtMs: Long? = null

    fun onPress(timeMs: Long, sessionActive: Boolean, secondaryAvailable: Boolean): ButtonTapAction {
        val startedAt = startedAtMs
        startedAtMs = null
        if (secondaryAvailable && startedAt != null && timeMs - startedAt in 0..doublePressWindowMs) {
            return ButtonTapAction.SECONDARY
        }
        if (sessionActive) return ButtonTapAction.STOP
        startedAtMs = timeMs
        return ButtonTapAction.START
    }

    companion object {
        const val DOUBLE_PRESS_WINDOW_MS = 500L
    }
}
