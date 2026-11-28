package com.opendictate.app.model

import kotlin.math.abs
import kotlin.math.max

/** The session a status banner reports. */
enum class StatusBannerKind {
    /** A spoken command for the AI agent. */
    COMMAND,
    DICTATION,
    VOICE_EDIT,
}

enum class StatusBannerStage {
    /** Recording, or about to record; a tap finishes it. */
    LISTENING,
    TRANSCRIBING,
    /** Post-processing, voice editing, or the agent carrying out a command. */
    PROCESSING,
    /** The outcome of a command. */
    RESULT,
}

/** Which way a banner was flicked. */
enum class StatusBannerSwipeDirection { UP, SIDEWAYS }

/** What a flick does to the banner's session. */
enum class StatusBannerSwipeAction {
    CANCEL,
    /** The banner goes away and the command continues, or ends, in the agent's notification. */
    TO_NOTIFICATION,
    /** The outcome goes away for good. */
    CLOSE,
}

/**
 * The banner at the top of the screen. It shows a spoken command from its recording to its outcome,
 * and dictation into a field while the button above the keyboard is off.
 */
data class StatusBanner(
    val kind: StatusBannerKind,
    val stage: StatusBannerStage,
    private val words: String? = null,
    /** The outcome's heading, shown only at [StatusBannerStage.RESULT]. */
    val resultTitle: String? = null,
) {
    val acceptsStop: Boolean get() = stage == StatusBannerStage.LISTENING

    /**
     * A command's words, ending with the latest ones, or its outcome from the start. Dictation
     * never shows its text here: it appears in the field itself.
     */
    val text: String? get() = words?.trim()?.takeIf(String::isNotEmpty)?.let { if (stage == StatusBannerStage.RESULT) it else tail(it) }

    /** A command can no longer be cancelled once the agent works on it; its outcome is kept or closed. */
    fun onSwipe(direction: StatusBannerSwipeDirection): StatusBannerSwipeAction = when {
        kind != StatusBannerKind.COMMAND -> StatusBannerSwipeAction.CANCEL
        stage == StatusBannerStage.RESULT && direction == StatusBannerSwipeDirection.SIDEWAYS -> StatusBannerSwipeAction.CLOSE
        stage == StatusBannerStage.RESULT || stage == StatusBannerStage.PROCESSING -> StatusBannerSwipeAction.TO_NOTIFICATION
        else -> StatusBannerSwipeAction.CANCEL
    }

    companion object {
        /** Enough for about three lines of a phone-wide banner. */
        const val MAX_TEXT_CHARS = 120

        /** How long an untouched outcome stays before it moves to the agent's notification. */
        const val RESULT_VISIBLE_MS = 8_000L

        /** [recorded] is true once the recording has stopped; [executing] once the agent works on it. */
        fun command(words: String?, recorded: Boolean = false, executing: Boolean = false): StatusBanner = StatusBanner(
            StatusBannerKind.COMMAND,
            when {
                executing -> StatusBannerStage.PROCESSING
                recorded -> StatusBannerStage.TRANSCRIBING
                else -> StatusBannerStage.LISTENING
            },
            words,
        )

        fun result(title: String, text: String): StatusBanner =
            StatusBanner(StatusBannerKind.COMMAND, StatusBannerStage.RESULT, text, title)

        /** [textStage] is true once transcription is done and the text model works on the result. */
        fun field(voiceEdit: Boolean, processing: Boolean, textStage: Boolean): StatusBanner = StatusBanner(
            if (voiceEdit) StatusBannerKind.VOICE_EDIT else StatusBannerKind.DICTATION,
            when {
                !processing -> StatusBannerStage.LISTENING
                textStage -> StatusBannerStage.PROCESSING
                else -> StatusBannerStage.TRANSCRIBING
            },
        )

        /** Keeps the end of a long command, starting at a word, so the latest words stay visible. */
        fun tail(text: String, maxChars: Int = MAX_TEXT_CHARS): String {
            if (text.length <= maxChars) return text
            val cut = text.length - maxChars
            val wordStart = text.indexOf(' ', cut).takeIf { it in cut until text.length - 1 }?.plus(1) ?: cut
            return "…" + text.substring(wordStart).trimStart()
        }
    }
}

/**
 * The banner sits at the top of the screen, so it is flicked away upward or to either side, like
 * a notification. Downward drags are ignored: they belong to the status bar.
 */
object StatusBannerSwipe {
    const val THRESHOLD_DP = 56f

    /** The distance that counts toward the swipe, in pixels. */
    fun distancePx(dxPx: Float, dyPx: Float): Float = max(abs(dxPx), -dyPx).coerceAtLeast(0f)

    fun isArmed(dxPx: Float, dyPx: Float, density: Float): Boolean =
        distancePx(dxPx, dyPx) >= THRESHOLD_DP * density

    fun progress(dxPx: Float, dyPx: Float, density: Float): Float =
        (distancePx(dxPx, dyPx) / (THRESHOLD_DP * density)).coerceIn(0f, 1f)

    /** A sideways swipe follows the finger sideways; otherwise the banner moves up. */
    fun isSideways(dxPx: Float, dyPx: Float): Boolean = abs(dxPx) >= -dyPx

    fun direction(dxPx: Float, dyPx: Float): StatusBannerSwipeDirection =
        if (isSideways(dxPx, dyPx)) StatusBannerSwipeDirection.SIDEWAYS else StatusBannerSwipeDirection.UP
}
