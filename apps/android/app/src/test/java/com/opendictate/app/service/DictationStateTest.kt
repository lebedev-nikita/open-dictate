package com.opendictate.app.service

import com.opendictate.app.model.TranscriptionModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationStateTest {
    @Test fun `processing stages keep session ownership and cancellation guards`() {
        for (stage in DictationProcessingStage.entries) {
            val state = DictationState(sessionId = 42L, phase = DictationPhase.PROCESSING,
                model = TranscriptionModel.ACCURATE, processingStage = stage)
            assertEquals(stage, state.activeProcessingStage)
            assertTrue(state.isActive)
            assertTrue(state.acceptsActiveUpdate(42L))
            assertTrue(state.acceptsCancellation(42L))
            assertFalse(state.acceptsActiveUpdate(41L))
            assertFalse(state.acceptsCancellation(41L))
        }
    }

    @Test fun `processing stage is hidden outside processing even after completion or cancellation`() {
        for (phase in DictationPhase.entries.filterNot { it.isProcessing }) {
            val state = DictationState(sessionId = 42L, phase = phase,
                model = TranscriptionModel.ACCURATE, processingStage = DictationProcessingStage.POST_PROCESSING)
            assertNull(state.activeProcessingStage)
        }
    }

    @Test fun `processing without an explicit stage preserves the operation and explicit stages override it`() {
        val state = DictationState(sessionId = 42L, phase = DictationPhase.PROCESSING,
            model = TranscriptionModel.ACCURATE)
        assertEquals(DictationProcessingStage.TRANSCRIPTION, state.activeProcessingStage)
        val editing = state.copy(operation = DictationOperation.TRANSFORMATION)
        assertEquals(DictationProcessingStage.TRANSFORMATION, editing.activeProcessingStage)
        assertEquals(DictationProcessingStage.TRANSCRIPTION,
            editing.copy(processingStage = DictationProcessingStage.TRANSCRIPTION).activeProcessingStage)
    }

    @Test
    fun `accurate cancellation does not restore text while recording or processing`() {
        for (phase in listOf(DictationPhase.LISTENING, DictationPhase.PROCESSING)) {
            val state = DictationState(
                sessionId = 42L,
                phase = phase,
                model = TranscriptionModel.ACCURATE,
            )

            assertFalse(state.shouldRestoreTextOnCancellation())
        }
    }

    @Test
    fun `processing remains active and accepts completion and cancellation`() {
        val state = DictationState(sessionId = 42L, phase = DictationPhase.PROCESSING,
            model = TranscriptionModel.ACCURATE)
        assertTrue(state.isActive)
        assertTrue(state.phase.isProcessing)
        assertTrue(state.acceptsActiveUpdate(42L))
        assertTrue(state.acceptsCancellation(42L))
        assertFalse(state.acceptsActiveUpdate(41L))
    }

    @Test
    fun `live cancellation restores the field after partial insertion`() {
        val state = DictationState(
            sessionId = 42L,
            phase = DictationPhase.LISTENING,
            model = TranscriptionModel.LIVE,
            transcript = "partial",
        )

        assertTrue(state.shouldRestoreTextOnCancellation())
    }

    @Test
    fun `transformation cancellation leaves the field alone`() {
        val state = DictationState(
            sessionId = 42L,
            phase = DictationPhase.PROCESSING,
            operation = DictationOperation.TRANSFORMATION,
            model = TranscriptionModel.LIVE,
        )

        assertFalse(state.shouldRestoreTextOnCancellation())
    }

    @Test
    fun `active session accepts its own updates`() {
        val state = DictationState(
            sessionId = 42L,
            phase = DictationPhase.LISTENING,
            model = TranscriptionModel.ACCURATE,
        )

        assertTrue(state.acceptsActiveUpdate(42L))
    }

    @Test
    fun `cancelled session rejects late partial updates`() {
        val state = DictationState(sessionId = 42L, phase = DictationPhase.IDLE)

        assertFalse(state.acceptsActiveUpdate(42L))
    }

    @Test
    fun `current session rejects updates from an older session`() {
        val state = DictationState(
            sessionId = 43L,
            phase = DictationPhase.LISTENING,
            model = TranscriptionModel.ACCURATE,
        )

        assertFalse(state.acceptsActiveUpdate(42L))
    }

    @Test
    fun `processing dictation and text transformation accept cancellation`() {
        val dictation = DictationState(
            sessionId = 42L,
            phase = DictationPhase.PROCESSING,
            operation = DictationOperation.DICTATION,
            model = TranscriptionModel.ACCURATE,
        )
        val transformation = DictationState(
            sessionId = 42L,
            phase = DictationPhase.PROCESSING,
            operation = DictationOperation.TRANSFORMATION,
            model = TranscriptionModel.ACCURATE,
        )

        assertTrue(dictation.acceptsCancellation(42L))
        assertTrue(transformation.acceptsCancellation(42L))
    }

    @Test
    fun `cancellation rejects missing stale and completed sessions`() {
        val active = DictationState(
            sessionId = 42L,
            phase = DictationPhase.LISTENING,
            model = TranscriptionModel.ACCURATE,
        )
        val completed = DictationState(sessionId = 42L, phase = DictationPhase.COMPLETED)

        assertFalse(active.acceptsCancellation(0L))
        assertFalse(active.acceptsCancellation(41L))
        assertFalse(completed.acceptsCancellation(42L))
    }
}
