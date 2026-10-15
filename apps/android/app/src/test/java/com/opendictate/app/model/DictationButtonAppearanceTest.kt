package com.opendictate.app.model

import org.junit.Assert.*
import org.junit.Test

class DictationButtonAppearanceTest {
    @Test fun `defaults and unknown settings retain the original right button`() {
        assertEquals(DictationButtonAppearance(), DictationButtonAppearance.fromStored(52, "unknown"))
        assertEquals(24, DictationButtonAppearance.fromStored(-1, null).diameterDp)
        assertEquals(96, DictationButtonAppearance.fromStored(Int.MAX_VALUE, "LEFT").diameterDp)
        assertEquals(126, DictationButtonAppearance(24).touchDiameterPx(2.625f))
        assertEquals(63, DictationButtonAppearance(24).diameterPx(2.625f))
        assertEquals(137, DictationButtonAppearance().diameterPx(2.625f))
    }

    @Test fun `legacy center position moves right while preserving size and gestures`() {
        val appearance = DictationButtonAppearance.fromStored(72, "CENTER")

        assertEquals(DictationButtonAppearance(72, DictationButtonPosition.RIGHT), appearance)
        assertEquals(1, appearance.gestureDirection)
    }

    @Test fun `stored edge positions are preserved`() {
        for (position in DictationButtonPosition.entries) {
            assertEquals(position, DictationButtonAppearance.fromStored(52, position.name).position)
        }
    }

    @Test fun `left position mirrors gestures so cancellation remains reachable`() {
        assertEquals(-1, DictationButtonAppearance(position = DictationButtonPosition.LEFT).gestureDirection)
        assertEquals(1, DictationButtonAppearance().gestureDirection)
    }
}
