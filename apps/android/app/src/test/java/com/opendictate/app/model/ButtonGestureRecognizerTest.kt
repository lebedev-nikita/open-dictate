package com.opendictate.app.model

import com.opendictate.app.model.ButtonGesture.DOUBLE_PRESS
import com.opendictate.app.model.ButtonGesture.HOLD
import com.opendictate.app.model.ButtonGesture.PRESS
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonGestureRecognizerTest {
    private val recognizer = ButtonGestureRecognizer(doublePressWindowMs = 500)

    private fun down(atMs: Long, vararg gestures: ButtonGesture, session: Boolean = false, startable: Boolean = true,
        keepsShortPress: Boolean = false) =
        recognizer.onDown(atMs, gestures.toSet(), keepsShortPress, session, startable)

    @Test
    fun pressAloneStartsAtOnceAndTheNextPressStops() {
        assertEquals(ButtonSignal.Start(PRESS), down(1_000, PRESS))
        assertEquals(ButtonSignal.Consume, recognizer.onUp(0))
        assertEquals(ButtonSignal.Stop, down(1_300, PRESS, session = true))
    }

    @Test
    fun holdAloneStartsAsTheButtonGoesDownAndEndsOnRelease() {
        assertEquals(ButtonSignal.Start(HOLD), down(1_000, HOLD))
        assertEquals(ButtonSignal.Release, recognizer.onUp(0))
    }

    @Test
    fun pressBesideHoldStartsOnRelease() {
        assertEquals(ButtonSignal.WaitForHold, down(1_000, PRESS, HOLD))
        assertEquals(ButtonSignal.Start(PRESS), recognizer.onUp(0))
        // The hold timer of that press may still fire; it changes nothing.
        assertEquals(ButtonSignal.Consume, recognizer.onHoldTimeout())
    }

    @Test
    fun holdBesidePressRunsUntilRelease() {
        assertEquals(ButtonSignal.WaitForHold, down(1_000, PRESS, HOLD))
        assertEquals(ButtonSignal.Start(HOLD), recognizer.onHoldTimeout())
        assertEquals(ButtonSignal.Release, recognizer.onUp(0))
    }

    @Test
    fun secondPressSoonAfterAStartingPressReplacesIt() {
        assertEquals(ButtonSignal.Start(PRESS), down(1_000, PRESS, DOUBLE_PRESS))
        recognizer.onUp(0)
        // The first press may already have started a session.
        assertEquals(ButtonSignal.Start(DOUBLE_PRESS, replacesPress = true), down(1_344, PRESS, DOUBLE_PRESS, session = true))
        recognizer.onUp(0)
        // The session it started ends with one press, like any other.
        assertEquals(ButtonSignal.Stop, down(1_700, PRESS, DOUBLE_PRESS, session = true))
    }

    @Test
    fun secondPressAfterTheWindowStops() {
        down(1_000, PRESS, DOUBLE_PRESS)
        recognizer.onUp(0)
        assertEquals(ButtonSignal.Stop, down(1_501, PRESS, DOUBLE_PRESS, session = true))
    }

    @Test
    fun doublePressAloneWaitsForTheSecondPress() {
        assertEquals(ButtonSignal.Consume, down(1_000, DOUBLE_PRESS))
        recognizer.onUp(0)
        assertEquals(ButtonSignal.Start(DOUBLE_PRESS), down(1_300, DOUBLE_PRESS))
    }

    @Test
    fun allThreeGesturesOnOneButton() {
        assertEquals(ButtonSignal.WaitForHold, down(1_000, PRESS, HOLD, DOUBLE_PRESS))
        assertEquals(ButtonSignal.Start(PRESS), recognizer.onUp(1_100))
        assertEquals(ButtonSignal.Start(DOUBLE_PRESS, replacesPress = true), down(1_350, PRESS, HOLD, DOUBLE_PRESS, session = true))
        recognizer.onUp(0)
        assertEquals(ButtonSignal.Stop, down(5_000, PRESS, HOLD, DOUBLE_PRESS, session = true))
        recognizer.onUp(0)
        assertEquals(ButtonSignal.WaitForHold, down(9_000, PRESS, HOLD, DOUBLE_PRESS))
        assertEquals(ButtonSignal.Start(HOLD), recognizer.onHoldTimeout())
        assertEquals(ButtonSignal.Release, recognizer.onUp(0))
    }

    @Test
    fun slowFirstTapBesideAHoldStillLeavesTheWholeWindow() {
        down(1_000, PRESS, HOLD, DOUBLE_PRESS)
        // The tap took 300 ms; the second press comes 400 ms after it ended.
        assertEquals(ButtonSignal.Start(PRESS), recognizer.onUp(1_300))
        assertEquals(ButtonSignal.Start(DOUBLE_PRESS, replacesPress = true), down(1_700, PRESS, HOLD, DOUBLE_PRESS, session = true))
    }

    @Test
    fun holdBesideADoublePressStartsTheDoubleOnTheSecondPress() {
        assertEquals(ButtonSignal.WaitForHold, down(1_000, HOLD, DOUBLE_PRESS))
        assertEquals(ButtonSignal.Consume, recognizer.onUp(1_100))
        assertEquals(ButtonSignal.Start(DOUBLE_PRESS), down(1_400, HOLD, DOUBLE_PRESS))
    }

    @Test
    fun pressSoonAfterAStoppingPressStartsAgain() {
        assertEquals(ButtonSignal.Stop, down(1_000, PRESS, DOUBLE_PRESS, session = true))
        recognizer.onUp(0)
        assertEquals(ButtonSignal.Start(PRESS), down(1_300, PRESS, DOUBLE_PRESS))
    }

    @Test
    fun shortVolumePressIsReplayedAndAHoldDictates() {
        assertEquals(ButtonSignal.WaitForHold, down(1_000, HOLD, keepsShortPress = true))
        assertEquals(ButtonSignal.ReplayShortPress, recognizer.onUp(0))
        assertEquals(ButtonSignal.WaitForHold, down(2_000, HOLD, keepsShortPress = true))
        assertEquals(ButtonSignal.Start(HOLD), recognizer.onHoldTimeout())
        assertEquals(ButtonSignal.Release, recognizer.onUp(0))
    }

    @Test
    fun keyKeepsItsBehaviorWhenNothingCanStart() {
        assertEquals(ButtonSignal.Pass, down(1_000, HOLD, startable = false))
        assertEquals(ButtonSignal.Pass, recognizer.onRepeat())
        assertEquals(ButtonSignal.Pass, recognizer.onUp(0))
        assertEquals(ButtonSignal.Pass, down(2_000))
    }

    @Test
    fun keyStopsASessionStartedElsewhereAndSwallowsItsRelease() {
        assertEquals(ButtonSignal.Stop, down(1_000, HOLD, session = true, startable = false))
        assertEquals(ButtonSignal.Consume, recognizer.onRepeat())
        assertEquals(ButtonSignal.Consume, recognizer.onUp(0))
    }

    @Test
    fun releaseWithoutPressPassesThrough() {
        assertEquals(ButtonSignal.Pass, recognizer.onUp(0))
    }

    @Test
    fun pressAfterAMissedReleaseEndsTheHold() {
        down(1_000, PRESS, HOLD)
        recognizer.onHoldTimeout()
        // The held command keeps a session open, but the press must end it rather than stop.
        assertEquals(ButtonSignal.Release, down(20_000, PRESS, HOLD, session = true))
    }
}
