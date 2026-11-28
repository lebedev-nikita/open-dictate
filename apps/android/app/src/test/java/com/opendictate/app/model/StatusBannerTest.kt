package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBannerTest {
    @Test
    fun commandFollowsItsRecordingTheAgentAndItsOutcome() {
        assertEquals(StatusBannerStage.LISTENING, StatusBanner.command(null).stage)
        assertEquals(StatusBannerStage.TRANSCRIBING, StatusBanner.command("Remind me", recorded = true).stage)
        assertEquals(StatusBannerStage.PROCESSING, StatusBanner.command("Remind me", recorded = true, executing = true).stage)
        assertEquals(StatusBannerStage.RESULT, StatusBanner.result("Tasks · add", "Added").stage)
        assertTrue(StatusBanner.command("Remind me").acceptsStop)
        assertFalse(StatusBanner.result("Tasks · add", "Added").acceptsStop)
    }

    @Test
    fun aSwipeCancelsARecordingAndKeepsOrClosesWhatTheAgentDid() {
        val up = StatusBannerSwipeDirection.UP
        val aside = StatusBannerSwipeDirection.SIDEWAYS
        for (banner in listOf(StatusBanner.command("Remind me"), StatusBanner.command("Remind me", recorded = true))) {
            assertEquals(StatusBannerSwipeAction.CANCEL, banner.onSwipe(up))
            assertEquals(StatusBannerSwipeAction.CANCEL, banner.onSwipe(aside))
        }
        // The agent's work cannot be undone, so its banner only moves to the notification.
        val executing = StatusBanner.command("Remind me", recorded = true, executing = true)
        assertEquals(StatusBannerSwipeAction.TO_NOTIFICATION, executing.onSwipe(up))
        assertEquals(StatusBannerSwipeAction.TO_NOTIFICATION, executing.onSwipe(aside))
        val outcome = StatusBanner.result("Tasks · add", "Added")
        assertEquals(StatusBannerSwipeAction.TO_NOTIFICATION, outcome.onSwipe(up))
        assertEquals(StatusBannerSwipeAction.CLOSE, outcome.onSwipe(aside))
        val dictation = StatusBanner.field(voiceEdit = false, processing = true, textStage = true)
        assertEquals(StatusBannerSwipeAction.CANCEL, dictation.onSwipe(up))
        assertEquals(StatusBannerSwipeAction.CANCEL, dictation.onSwipe(aside))
    }

    @Test
    fun anOutcomeReadsFromItsStartWhileACommandShowsItsLatestWords() {
        val long = "word ".repeat(60).trim()
        assertEquals(long, StatusBanner.result("Done", long).text)
        assertTrue(StatusBanner.command(long).text!!.startsWith("…"))
        assertEquals("Done", StatusBanner.result("Done", "Added").resultTitle)
    }

    @Test
    fun fieldStageFollowsTranscriptionThenTheTextModel() {
        assertEquals(StatusBanner(StatusBannerKind.DICTATION, StatusBannerStage.LISTENING),
            StatusBanner.field(voiceEdit = false, processing = false, textStage = false))
        assertEquals(StatusBanner(StatusBannerKind.DICTATION, StatusBannerStage.TRANSCRIBING),
            StatusBanner.field(voiceEdit = false, processing = true, textStage = false))
        assertEquals(StatusBanner(StatusBannerKind.VOICE_EDIT, StatusBannerStage.PROCESSING),
            StatusBanner.field(voiceEdit = true, processing = true, textStage = true))
    }

    @Test
    fun onlyListeningAcceptsAStop() {
        assertTrue(StatusBanner.field(voiceEdit = false, processing = false, textStage = false).acceptsStop)
        assertFalse(StatusBanner.field(voiceEdit = false, processing = true, textStage = false).acceptsStop)
        assertFalse(StatusBanner.field(voiceEdit = true, processing = true, textStage = true).acceptsStop)
    }

    @Test
    fun commandShowsItsWordsWhileDictationNeverDoes() {
        assertEquals("Remind me at five", StatusBanner.command("  Remind me at five ").text)
        assertNull(StatusBanner.command(" ").text)
        assertNull(StatusBanner.field(voiceEdit = false, processing = false, textStage = false).text)
    }

    @Test
    fun longCommandKeepsItsLatestWordsFromAWordStart() {
        val text = "one two three four five six"
        assertEquals("…five six", StatusBanner.tail(text, maxChars = 10))
        assertEquals(text, StatusBanner.tail(text, maxChars = text.length))
        // A single long word is cut where it must be.
        assertEquals("…cdef", StatusBanner.tail("abcdef", maxChars = 4))
        assertTrue(StatusBanner.command("word ".repeat(100)).text!!.length <= StatusBanner.MAX_TEXT_CHARS + 1)
    }

    @Test
    fun swipeArmsUpwardOrToEitherSide() {
        val density = 2f
        val threshold = StatusBannerSwipe.THRESHOLD_DP * density
        assertTrue(StatusBannerSwipe.isArmed(threshold, 0f, density))
        assertTrue(StatusBannerSwipe.isArmed(-threshold, 0f, density))
        assertTrue(StatusBannerSwipe.isArmed(0f, -threshold, density))
        assertFalse(StatusBannerSwipe.isArmed(threshold - 1f, 0f, density))
        assertEquals(StatusBannerSwipeDirection.SIDEWAYS, StatusBannerSwipe.direction(-30f, -10f))
        assertEquals(StatusBannerSwipeDirection.UP, StatusBannerSwipe.direction(10f, -30f))
    }

    @Test
    fun downwardDragNeverArms() {
        val density = 2f
        assertFalse(StatusBannerSwipe.isArmed(0f, 500f, density))
        assertEquals(0f, StatusBannerSwipe.progress(0f, 500f, density))
    }
}
