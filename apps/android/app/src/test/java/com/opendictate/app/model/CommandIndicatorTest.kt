package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandIndicatorTest {
    @Test
    fun stageFollowsTheCommandFromRecordingToExecution() {
        assertEquals(CommandIndicatorStage.LISTENING, CommandIndicatorStage.of(executing = false, recordingProcessing = false))
        assertEquals(CommandIndicatorStage.TRANSCRIBING, CommandIndicatorStage.of(executing = false, recordingProcessing = true))
        assertEquals(CommandIndicatorStage.EXECUTING, CommandIndicatorStage.of(executing = true, recordingProcessing = false))
    }

    @Test
    fun onlyListeningAcceptsAStop() {
        assertTrue(CommandIndicatorStage.LISTENING.acceptsStop)
        assertFalse(CommandIndicatorStage.TRANSCRIBING.acceptsStop)
        assertFalse(CommandIndicatorStage.EXECUTING.acceptsStop)
    }

    @Test
    fun swipeArmsUpwardOrToEitherSide() {
        val density = 2f
        val threshold = CommandIndicatorSwipe.THRESHOLD_DP * density
        assertTrue(CommandIndicatorSwipe.isArmed(threshold, 0f, density))
        assertTrue(CommandIndicatorSwipe.isArmed(-threshold, 0f, density))
        assertTrue(CommandIndicatorSwipe.isArmed(0f, -threshold, density))
        assertFalse(CommandIndicatorSwipe.isArmed(threshold - 1f, 0f, density))
    }

    @Test
    fun downwardDragNeverArms() {
        val density = 2f
        assertFalse(CommandIndicatorSwipe.isArmed(0f, 500f, density))
        assertEquals(0f, CommandIndicatorSwipe.progress(0f, 500f, density))
    }
}
