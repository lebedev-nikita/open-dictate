package com.opendictate.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the button that opened a command panel did since. The button can come up, or a double press
 * can replace the command, before the panel exists, so the latest signal is kept here for the panel
 * with the same [Signal.panel] number rather than sent to it.
 */
internal object CommandButtonHold {
    enum class Kind { HELD, RELEASED, CANCELLED }

    data class Signal(val panel: Long, val kind: Kind)

    private var nextPanel = 0L
    private val mutableSignal = MutableStateFlow<Signal?>(null)
    val signal: StateFlow<Signal?> = mutableSignal.asStateFlow()

    /** Numbers a new panel; [held] when it records until the button comes up. */
    fun open(held: Boolean): Long {
        val panel = ++nextPanel
        mutableSignal.value = Signal(panel, if (held) Kind.HELD else Kind.RELEASED)
        return panel
    }

    fun release() {
        val current = mutableSignal.value ?: return
        if (current.kind == Kind.HELD) mutableSignal.value = current.copy(kind = Kind.RELEASED)
    }

    /** Cancels the latest panel: a double press replaced the command its first press opened. */
    fun cancel() {
        val current = mutableSignal.value ?: return
        mutableSignal.value = current.copy(kind = Kind.CANCELLED)
    }
}
