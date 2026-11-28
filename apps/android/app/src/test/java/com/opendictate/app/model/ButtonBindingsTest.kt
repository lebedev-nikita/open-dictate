package com.opendictate.app.model

import com.opendictate.app.model.ButtonAction.AGENT_COMMAND
import com.opendictate.app.model.ButtonAction.DICTATION
import com.opendictate.app.model.ButtonAction.VOICE_EDIT
import com.opendictate.app.model.ButtonGesture.DOUBLE_PRESS
import com.opendictate.app.model.ButtonGesture.HOLD
import com.opendictate.app.model.ButtonGesture.PRESS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ButtonBindingsTest {
    private val plusKey = PhoneButton.Log(LogButtonSignature("KEYLOG_OplusKeyEventUtil", "undefined keys", releaseLogged = true))
    private val pressOnly = PhoneButton.Log(LogButtonSignature("Hw", "press #", releaseLogged = false))
    private val volumeDown = PhoneButton.Key(DictationKeys.VOLUME_DOWN)
    private val camera = PhoneButton.Key(27)

    @Test
    fun buttonsOfferTheGesturesTheyCanTellApart() {
        assertEquals(ButtonGesture.entries.toSet(), plusKey.gestures)
        assertEquals(setOf(PRESS, DOUBLE_PRESS), pressOnly.gestures)
        assertEquals(setOf(HOLD), volumeDown.gestures)
        assertEquals(ButtonGesture.entries.toSet(), camera.gestures)
    }

    @Test
    fun eachActionRunsFromOneButtonAndGesture() {
        val bindings = ButtonBindings()
            .bind(DICTATION, plusKey, PRESS)
            .bind(AGENT_COMMAND, plusKey, HOLD)
            .bind(VOICE_EDIT, volumeDown, HOLD)
        assertEquals(DICTATION, bindings.actionFor(plusKey, PRESS))
        assertEquals(AGENT_COMMAND, bindings.actionFor(plusKey, HOLD))
        assertEquals(VOICE_EDIT, bindings.actionFor(volumeDown, HOLD))
        assertEquals(listOf(plusKey, volumeDown), bindings.buttons)
        // Moving an action replaces its binding.
        val moved = bindings.bind(DICTATION, camera, PRESS)
        assertNull(moved.actionFor(plusKey, PRESS))
        assertEquals(DICTATION, moved.actionFor(camera, PRESS))
        assertEquals(3, moved.bindings.size)
    }

    @Test
    fun takenOrMissingGesturesAreRefused() {
        val bindings = ButtonBindings().bind(DICTATION, plusKey, PRESS)
        assertSame(bindings, bindings.bind(AGENT_COMMAND, plusKey, PRESS))
        assertSame(bindings, bindings.bind(AGENT_COMMAND, pressOnly, HOLD))
        assertSame(bindings, bindings.bind(AGENT_COMMAND, volumeDown, PRESS))
        // An action can keep its own gesture.
        assertEquals(bindings, bindings.bind(DICTATION, plusKey, PRESS))
    }

    @Test
    fun freeGestureKeepsThePreferredOneWhenPossible() {
        val bindings = ButtonBindings().bind(DICTATION, plusKey, PRESS)
        assertEquals(HOLD, bindings.freeGesture(AGENT_COMMAND, plusKey, HOLD))
        assertEquals(HOLD, bindings.freeGesture(AGENT_COMMAND, plusKey, PRESS))
        assertEquals(HOLD, bindings.freeGesture(AGENT_COMMAND, volumeDown, PRESS))
        val full = bindings.bind(AGENT_COMMAND, plusKey, HOLD).bind(VOICE_EDIT, plusKey, DOUBLE_PRESS)
        assertNull(full.freeGesture(ButtonAction.ALTERNATE_DICTATION, plusKey, null))
        assertEquals(mapOf(PRESS to DICTATION, HOLD to AGENT_COMMAND), full.takenGestures(plusKey, VOICE_EDIT))
    }

    @Test
    fun jsonKeepsEveryBinding() {
        val bindings = ButtonBindings()
            .bind(DICTATION, plusKey, PRESS)
            .bind(AGENT_COMMAND, plusKey, HOLD)
            .bind(VOICE_EDIT, volumeDown, HOLD)
            .bind(ButtonAction.ALTERNATE_DICTATION, pressOnly, DOUBLE_PRESS)
        assertEquals(bindings, ButtonBindings.fromJson(bindings.toJson()))
    }

    @Test
    fun brokenStoredBindingsAreDropped() {
        assertEquals(ButtonBindings(), ButtonBindings.fromJson("not json"))
        assertEquals(ButtonBindings(), ButtonBindings.fromJson(null))
        val stored = """[{"action":"DICTATION","gesture":"PRESS","key":4},""" +
            """{"action":"VOICE_EDIT","gesture":"PRESS","key":25},""" +
            """{"action":"UNKNOWN","gesture":"PRESS","key":27},""" +
            """{"action":"AGENT_COMMAND","gesture":"HOLD","logTag":"a b","logMessage":"x","logRelease":true},""" +
            """{"action":"DICTATION","gesture":"HOLD","key":27}]"""
        // Back cannot be bound, volume cannot be pressed, and a tag with a space cannot filter logcat.
        assertEquals(ButtonBindings().bind(DICTATION, camera, HOLD), ButtonBindings.fromJson(stored))
    }

    @Test
    fun theSingleButtonBoundBeforeKeepsWhatItDid() {
        assertEquals(ButtonBindings().bind(DICTATION, plusKey, PRESS).bind(AGENT_COMMAND, plusKey, HOLD),
            ButtonBindings.fromLegacy(0, plusKey.signature))
        assertEquals(ButtonBindings().bind(DICTATION, pressOnly, PRESS).bind(AGENT_COMMAND, pressOnly, DOUBLE_PRESS),
            ButtonBindings.fromLegacy(0, pressOnly.signature))
        assertEquals(ButtonBindings().bind(DICTATION, volumeDown, HOLD), ButtonBindings.fromLegacy(DictationKeys.VOLUME_DOWN, null))
        assertEquals(ButtonBindings(), ButtonBindings.fromLegacy(0, null))
    }
}
