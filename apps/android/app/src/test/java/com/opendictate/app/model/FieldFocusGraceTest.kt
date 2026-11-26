package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldFocusGraceTest {
    @Test
    fun focusedFieldHoldsSession() {
        val grace = FieldFocusGrace(graceMs = 1_000)
        assertEquals(FieldFocus.Held, grace.check(targetFocused = true, nowMs = 0))
        assertEquals(FieldFocus.Held, grace.check(targetFocused = true, nowMs = 5_000))
    }

    @Test
    fun briefFocusLossDuringTaskSwitchIsTolerated() {
        val grace = FieldFocusGrace(graceMs = 1_000)
        assertEquals(FieldFocus.Waiting(1_000), grace.check(targetFocused = false, nowMs = 10_000))
        assertEquals(FieldFocus.Waiting(600), grace.check(targetFocused = false, nowMs = 10_400))
        assertEquals(FieldFocus.Held, grace.check(targetFocused = true, nowMs = 10_500))
        // The grace period starts again after focus returns.
        assertEquals(FieldFocus.Waiting(1_000), grace.check(targetFocused = false, nowMs = 20_000))
    }

    @Test
    fun lastingFocusLossEndsSession() {
        val grace = FieldFocusGrace(graceMs = 1_000)
        grace.check(targetFocused = false, nowMs = 0)
        assertEquals(FieldFocus.Lost, grace.check(targetFocused = false, nowMs = 1_000))
        assertEquals(FieldFocus.Lost, grace.check(targetFocused = false, nowMs = 3_000))
    }

    @Test
    fun resetForgetsEarlierLoss() {
        val grace = FieldFocusGrace(graceMs = 1_000)
        grace.check(targetFocused = false, nowMs = 0)
        grace.reset()
        assertEquals(FieldFocus.Waiting(1_000), grace.check(targetFocused = false, nowMs = 2_000))
    }
}
