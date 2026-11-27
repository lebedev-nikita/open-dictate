package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonTapGestureTest {
    private val gesture = ButtonTapGesture(doublePressWindowMs = 500)

    @Test
    fun singlePressesToggleAtOnce() {
        assertEquals(ButtonTapAction.START, gesture.onPress(1_000, sessionActive = false, secondaryAvailable = true))
        assertEquals(ButtonTapAction.STOP, gesture.onPress(4_000, sessionActive = true, secondaryAvailable = true))
        assertEquals(ButtonTapAction.START, gesture.onPress(9_000, sessionActive = false, secondaryAvailable = true))
    }

    @Test
    fun quickSecondPressSwitchesToTheSecondaryAction() {
        assertEquals(ButtonTapAction.START, gesture.onPress(1_000, sessionActive = false, secondaryAvailable = true))
        // The first press may already have started a session, or found no field to start in.
        assertEquals(ButtonTapAction.SECONDARY, gesture.onPress(1_344, sessionActive = true, secondaryAvailable = true))
        // The secondary session then ends with one press, like any other.
        assertEquals(ButtonTapAction.STOP, gesture.onPress(1_700, sessionActive = true, secondaryAvailable = true))
    }

    @Test
    fun secondPressAfterTheWindowStops() {
        gesture.onPress(1_000, sessionActive = false, secondaryAvailable = true)
        assertEquals(ButtonTapAction.STOP, gesture.onPress(1_501, sessionActive = true, secondaryAvailable = true))
    }

    @Test
    fun withoutASecondaryActionEveryPressToggles() {
        assertEquals(ButtonTapAction.START, gesture.onPress(1_000, sessionActive = false, secondaryAvailable = false))
        assertEquals(ButtonTapAction.STOP, gesture.onPress(1_300, sessionActive = true, secondaryAvailable = false))
    }

    @Test
    fun pressSoonAfterAStoppingPressStartsAgain() {
        gesture.onPress(1_000, sessionActive = true, secondaryAvailable = true)
        assertEquals(ButtonTapAction.START, gesture.onPress(1_300, sessionActive = false, secondaryAvailable = true))
    }

    @Test
    fun linkNeedsASchemeAndThePlaceholder() {
        assertTrue(TextLink.isValid("t3code://new?prompt={text}"))
        assertFalse(TextLink.isValid("t3code://new?prompt="))
        assertFalse(TextLink.isValid("new?prompt={text}"))
        assertFalse(TextLink.isValid(""))
    }

    @Test
    fun linkTextIsPercentEncoded() {
        assertEquals("t3code://new?prompt=Fix%20a%2Bb%20%26%20c%3F%0A%D0%94%D0%B0~",
            TextLink.build("t3code://new?prompt={text}", "Fix a+b & c?\nДа~"))
    }
}
