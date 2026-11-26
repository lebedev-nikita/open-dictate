package com.opendictate.app.model

/** What the accessibility service does with a hardware key event. */
enum class DictationKeyAction {
    /** Let the key reach the app and the system unchanged. */
    PASS,
    CONSUME,
    START,
    STOP,
    /** Consume now and call [DictationKeyGesture.onHoldTimeout] after the hold threshold. */
    WAIT_FOR_HOLD,
    /** A short press of a key with its own function: perform that function instead. */
    REPLAY_SHORT_PRESS,
}

/**
 * Hold-to-talk for one bound hardware key. Press starts dictation and release stops it, but only
 * when a text field is ready; otherwise the key keeps its usual behavior. Keys with their own
 * function, such as volume, start only after a hold so a short press still does its usual job.
 */
class DictationKeyGesture {
    private enum class State { IDLE, PASSING, WAITING_FOR_HOLD, HOLDING, STOPPING }

    private var state = State.IDLE

    fun onKeyDown(repeated: Boolean, fieldReady: Boolean, fieldSessionActive: Boolean, holdBeforeStart: Boolean): DictationKeyAction {
        if (repeated) return if (state == State.PASSING || state == State.IDLE) DictationKeyAction.PASS else DictationKeyAction.CONSUME
        return when {
            // A session started from the screen or a command ends with the key, like a second tap.
            fieldSessionActive -> { state = State.STOPPING; DictationKeyAction.STOP }
            !fieldReady -> { state = State.PASSING; DictationKeyAction.PASS }
            holdBeforeStart -> { state = State.WAITING_FOR_HOLD; DictationKeyAction.WAIT_FOR_HOLD }
            else -> { state = State.HOLDING; DictationKeyAction.START }
        }
    }

    fun onHoldTimeout(): DictationKeyAction {
        if (state != State.WAITING_FOR_HOLD) return DictationKeyAction.PASS
        state = State.HOLDING
        return DictationKeyAction.START
    }

    fun onKeyUp(): DictationKeyAction {
        val previous = state
        state = State.IDLE
        return when (previous) {
            State.IDLE, State.PASSING -> DictationKeyAction.PASS
            State.WAITING_FOR_HOLD -> DictationKeyAction.REPLAY_SHORT_PRESS
            State.HOLDING -> DictationKeyAction.STOP
            State.STOPPING -> DictationKeyAction.CONSUME
        }
    }

    /** Forgets a press whose release will never arrive, for example after the binding changed. */
    fun reset() {
        state = State.IDLE
    }

    companion object {
        const val HOLD_THRESHOLD_MS = 400L
    }
}

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
