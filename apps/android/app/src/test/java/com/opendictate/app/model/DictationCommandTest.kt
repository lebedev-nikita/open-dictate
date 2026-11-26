package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationCommandTest {
    @Test
    fun parsesOnlyPublishedActions() {
        assertEquals(DictationCommand.START, DictationCommand.fromAction("com.opendictate.app.action.DICTATION_START"))
        assertEquals(DictationCommand.STOP, DictationCommand.fromAction("com.opendictate.app.action.DICTATION_STOP"))
        assertEquals(DictationCommand.TOGGLE, DictationCommand.fromAction("com.opendictate.app.action.DICTATION_TOGGLE"))
        assertEquals(DictationCommand.CANCEL, DictationCommand.fromAction("com.opendictate.app.action.DICTATION_CANCEL"))
        assertNull(DictationCommand.fromAction("com.opendictate.app.action.START_DICTATION"))
        assertNull(DictationCommand.fromAction(null))
    }

    @Test
    fun idleSessionOnlyStarts() {
        assertEquals(DictationCommandEffect.START, DictationCommand.START.effect(sessionActive = false))
        assertEquals(DictationCommandEffect.START, DictationCommand.TOGGLE.effect(sessionActive = false))
        assertEquals(DictationCommandEffect.NONE, DictationCommand.STOP.effect(sessionActive = false))
        assertEquals(DictationCommandEffect.NONE, DictationCommand.CANCEL.effect(sessionActive = false))
    }

    @Test
    fun activeSessionIsNeverRestarted() {
        assertEquals(DictationCommandEffect.NONE, DictationCommand.START.effect(sessionActive = true))
        assertEquals(DictationCommandEffect.STOP, DictationCommand.TOGGLE.effect(sessionActive = true))
        assertEquals(DictationCommandEffect.STOP, DictationCommand.STOP.effect(sessionActive = true))
        assertEquals(DictationCommandEffect.CANCEL, DictationCommand.CANCEL.effect(sessionActive = true))
    }
}
