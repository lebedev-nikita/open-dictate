package com.opendictate.app.model

import kotlin.math.abs
import kotlin.math.max

/** What the indicator of a spoken command shows, from the press until the command has run. */
enum class CommandIndicatorStage {
    /** Recording, or about to record; a tap finishes the command. */
    LISTENING,
    TRANSCRIBING,
    /** The text model and the tool call; the indicator only reports progress. */
    EXECUTING;

    val acceptsStop: Boolean get() = this == LISTENING

    companion object {
        /** [recordingProcessing] is true once the command's own recording has stopped. */
        fun of(executing: Boolean, recordingProcessing: Boolean): CommandIndicatorStage = when {
            executing -> EXECUTING
            recordingProcessing -> TRANSCRIBING
            else -> LISTENING
        }
    }
}

/**
 * The indicator sits at the top of the screen, so it is flicked away upward or to either side, like
 * a notification banner. Downward drags are ignored: they belong to the status bar.
 */
object CommandIndicatorSwipe {
    const val THRESHOLD_DP = 56f

    /** The distance that counts toward the swipe, in pixels. */
    fun distancePx(dxPx: Float, dyPx: Float): Float = max(abs(dxPx), -dyPx).coerceAtLeast(0f)

    fun isArmed(dxPx: Float, dyPx: Float, density: Float): Boolean =
        distancePx(dxPx, dyPx) >= THRESHOLD_DP * density

    fun progress(dxPx: Float, dyPx: Float, density: Float): Float =
        (distancePx(dxPx, dyPx) / (THRESHOLD_DP * density)).coerceIn(0f, 1f)
}
