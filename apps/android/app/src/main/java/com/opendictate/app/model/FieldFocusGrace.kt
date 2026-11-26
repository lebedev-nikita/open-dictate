package com.opendictate.app.model

/**
 * Decides when a dictation started by an activity command loses its field. Launching any activity
 * switches tasks, and Android closes the keyboard; the field keeps input focus. Such a session ends
 * only after its field has been out of focus for [graceMs], which covers the switch itself.
 */
class FieldFocusGrace(private val graceMs: Long = GRACE_MS) {
    private var lostAtMs: Long? = null

    fun check(targetFocused: Boolean, nowMs: Long): FieldFocus {
        if (targetFocused) {
            lostAtMs = null
            return FieldFocus.Held
        }
        val lostAt = lostAtMs ?: nowMs.also { lostAtMs = it }
        val remaining = lostAt + graceMs - nowMs
        return if (remaining > 0) FieldFocus.Waiting(remaining) else FieldFocus.Lost
    }

    fun reset() {
        lostAtMs = null
    }

    companion object {
        const val GRACE_MS = 1_000L
    }
}

sealed interface FieldFocus {
    data object Held : FieldFocus
    data class Waiting(val remainingMs: Long) : FieldFocus
    data object Lost : FieldFocus
}
