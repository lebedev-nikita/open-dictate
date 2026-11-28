package com.opendictate.app.model

/** What to do with one event of a bound button. */
sealed interface ButtonSignal {
    /**
     * Start the action bound to [gesture]. With [replacesPress] the press just before started the
     * action bound to [ButtonGesture.PRESS], which this one replaces.
     */
    data class Start(val gesture: ButtonGesture, val replacesPress: Boolean = false) : ButtonSignal

    /** Finish the running session, whatever started it. */
    data object Stop : ButtonSignal

    /** The button came up after a hold: finish the action bound to [ButtonGesture.HOLD]. */
    data object Release : ButtonSignal

    /** Call [ButtonGestureRecognizer.onHoldTimeout] after [ButtonGestureRecognizer.HOLD_THRESHOLD_MS]. */
    data object WaitForHold : ButtonSignal

    /** A short press of a key with its own function: perform that function instead. */
    data object ReplayShortPress : ButtonSignal

    /** Keep the event from the system and do nothing else. */
    data object Consume : ButtonSignal

    /** Let a key event reach the system unchanged. */
    data object Pass : ButtonSignal
}

/**
 * Turns the presses and releases of one button into the gestures bound on it. Only bound gestures
 * are waited for: a button with a press alone starts at once, a hold alone starts as the button goes
 * down, and a press beside a hold starts when the button comes up. A press beside a double press
 * still starts at once, and a second press soon after replaces it.
 */
class ButtonGestureRecognizer(private val doublePressWindowMs: Long = DOUBLE_PRESS_WINDOW_MS) {
    private enum class State { IDLE, PASSING, WAITING_FOR_HOLD, HOLDING, CONSUMING }

    private var state = State.IDLE
    private var gestures = emptySet<ButtonGesture>()
    private var keepsShortPress = false
    /**
     * When the last press that can begin a double press ended: its release when it waited to tell a
     * hold apart, which a slow tap must not shorten, or else the moment it went down.
     */
    private var tapAtMs: Long? = null

    /**
     * [gestures] are those bound on the button whose actions are available now. A key passes to the
     * system when it is not [startable], unless it has a session to finish.
     */
    fun onDown(timeMs: Long, gestures: Set<ButtonGesture>, keepsShortPress: Boolean, sessionActive: Boolean,
        startable: Boolean = true): ButtonSignal {
        // A press while held means the release was missed; the hold ends with it.
        if (state == State.HOLDING) {
            state = State.CONSUMING
            return ButtonSignal.Release
        }
        this.gestures = gestures
        this.keepsShortPress = keepsShortPress
        val tapAt = tapAtMs
        tapAtMs = null
        if (ButtonGesture.DOUBLE_PRESS in gestures && tapAt != null && timeMs - tapAt in 0..doublePressWindowMs) {
            state = State.CONSUMING
            return ButtonSignal.Start(ButtonGesture.DOUBLE_PRESS, replacesPress = ButtonGesture.PRESS in gestures)
        }
        if (sessionActive) {
            state = State.CONSUMING
            return ButtonSignal.Stop
        }
        if (gestures.isEmpty() || !startable) {
            state = State.PASSING
            return ButtonSignal.Pass
        }
        val tapGestures = gestures - ButtonGesture.HOLD
        if (ButtonGesture.HOLD in gestures) {
            if (tapGestures.isEmpty() && !keepsShortPress) {
                state = State.HOLDING
                return ButtonSignal.Start(ButtonGesture.HOLD)
            }
            state = State.WAITING_FOR_HOLD
            return ButtonSignal.WaitForHold
        }
        state = State.CONSUMING
        if (ButtonGesture.DOUBLE_PRESS in gestures) tapAtMs = timeMs
        return if (ButtonGesture.PRESS in gestures) ButtonSignal.Start(ButtonGesture.PRESS) else ButtonSignal.Consume
    }

    /** A repeat of a key held down: whether to keep it from the system. */
    fun onRepeat(): ButtonSignal = if (state == State.IDLE || state == State.PASSING) ButtonSignal.Pass else ButtonSignal.Consume

    fun onHoldTimeout(): ButtonSignal {
        if (state != State.WAITING_FOR_HOLD) return ButtonSignal.Consume
        state = State.HOLDING
        return ButtonSignal.Start(ButtonGesture.HOLD)
    }

    fun onUp(timeMs: Long): ButtonSignal {
        val previous = state
        state = State.IDLE
        return when (previous) {
            State.IDLE, State.PASSING -> ButtonSignal.Pass
            State.CONSUMING -> ButtonSignal.Consume
            State.HOLDING -> ButtonSignal.Release
            State.WAITING_FOR_HOLD -> {
                if (ButtonGesture.DOUBLE_PRESS in gestures) tapAtMs = timeMs
                when {
                    ButtonGesture.PRESS in gestures -> ButtonSignal.Start(ButtonGesture.PRESS)
                    keepsShortPress -> ButtonSignal.ReplayShortPress
                    else -> ButtonSignal.Consume
                }
            }
        }
    }

    /** Forgets a press whose release will never arrive, for example after the bindings changed. */
    fun reset() {
        state = State.IDLE
        tapAtMs = null
    }

    companion object {
        const val HOLD_THRESHOLD_MS = 400L
        const val DOUBLE_PRESS_WINDOW_MS = 500L
    }
}
