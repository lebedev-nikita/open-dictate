package com.opendictate.app.model

enum class ButtonTapAction {
    /** Start dictation into the focused field. */
    START,
    STOP,
    /** Undo the start made by the first press and dictate for the link instead. */
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

/** A link template that receives dictated text, such as `t3code://new?prompt={text}`. */
object TextLink {
    const val PLACEHOLDER = "{text}"
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private const val UNRESERVED = "-_.~"

    fun isValid(template: String): Boolean = PLACEHOLDER in template && SCHEME.containsMatchIn(template)

    fun build(template: String, text: String): String = template.replace(PLACEHOLDER, encode(text))

    /** Percent-encodes everything but unreserved characters; a space becomes `%20`, never `+`. */
    private fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) append(char)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
