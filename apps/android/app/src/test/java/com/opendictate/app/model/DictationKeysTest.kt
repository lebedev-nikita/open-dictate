package com.opendictate.app.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationKeysTest {
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
