package com.opendictate.app.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationKeyGestureTest {
    private val gesture = DictationKeyGesture()

    private fun down(repeated: Boolean = false, ready: Boolean = true, active: Boolean = false, hold: Boolean = false) =
        gesture.onKeyDown(repeated, ready, active, hold)

    @Test
    fun pressStartsAndReleaseStops() {
        assertEquals(DictationKeyAction.START, down())
        assertEquals(DictationKeyAction.CONSUME, down(repeated = true))
        assertEquals(DictationKeyAction.STOP, gesture.onKeyUp())
    }

    @Test
    fun keyKeepsItsBehaviorWithoutAReadyField() {
        assertEquals(DictationKeyAction.PASS, down(ready = false))
        assertEquals(DictationKeyAction.PASS, down(repeated = true, ready = false))
        assertEquals(DictationKeyAction.PASS, gesture.onKeyUp())
    }

    @Test
    fun shortVolumePressIsReplayed() {
        assertEquals(DictationKeyAction.WAIT_FOR_HOLD, down(hold = true))
        assertEquals(DictationKeyAction.REPLAY_SHORT_PRESS, gesture.onKeyUp())
        assertEquals(DictationKeyAction.PASS, gesture.onHoldTimeout())
    }

    @Test
    fun heldVolumeKeyDictatesUntilRelease() {
        assertEquals(DictationKeyAction.WAIT_FOR_HOLD, down(hold = true))
        assertEquals(DictationKeyAction.CONSUME, down(repeated = true, hold = true))
        assertEquals(DictationKeyAction.START, gesture.onHoldTimeout())
        assertEquals(DictationKeyAction.STOP, gesture.onKeyUp())
    }

    @Test
    fun keyStopsASessionStartedElsewhereAndSwallowsItsRelease() {
        assertEquals(DictationKeyAction.STOP, down(active = true, hold = true))
        assertEquals(DictationKeyAction.CONSUME, gesture.onKeyUp())
    }

    @Test
    fun releaseWithoutPressPassesThrough() {
        assertEquals(DictationKeyAction.PASS, gesture.onKeyUp())
        down()
        gesture.reset()
        assertEquals(DictationKeyAction.PASS, gesture.onKeyUp())
    }

    @Test
    fun keyCodesMatchAndroid() {
        assertEquals(KeyEvent.KEYCODE_BACK, DictationKeys.BACK)
        assertEquals(KeyEvent.KEYCODE_HOME, DictationKeys.HOME)
        assertEquals(KeyEvent.KEYCODE_POWER, DictationKeys.POWER)
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH, DictationKeys.APP_SWITCH)
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, DictationKeys.VOLUME_UP)
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, DictationKeys.VOLUME_DOWN)
        assertEquals(KeyEvent.KEYCODE_VOLUME_MUTE, DictationKeys.VOLUME_MUTE)
    }

    @Test
    fun navigationKeysCannotBeBound() {
        assertFalse(DictationKeys.isBindable(KeyEvent.KEYCODE_BACK))
        assertFalse(DictationKeys.isBindable(KeyEvent.KEYCODE_HOME))
        assertFalse(DictationKeys.isBindable(KeyEvent.KEYCODE_POWER))
        assertFalse(DictationKeys.isBindable(KeyEvent.KEYCODE_UNKNOWN))
        assertTrue(DictationKeys.isBindable(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(DictationKeys.isBindable(KeyEvent.KEYCODE_CAMERA))
        assertTrue(DictationKeys.holdsBeforeStart(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(DictationKeys.holdsBeforeStart(KeyEvent.KEYCODE_CAMERA))
    }
}
