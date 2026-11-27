package com.opendictate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogButtonTest {
    private val keyLine = "11-27 12:45:04.085 I/KEYLOG_OplusKeyEventUtil( 5034): should not notify undefined keys in restrict listen mode"

    @Test
    fun parsesTimeTagPidAndMessage() {
        val line = LogLine.parse(keyLine)!!
        assertEquals(((12L * 60 + 45) * 60 + 4) * 1000 + 85, line.timeMs)
        assertEquals("KEYLOG_OplusKeyEventUtil", line.tag)
        assertEquals(5034, line.pid)
        assertEquals("should not notify undefined keys in restrict listen mode", line.message)
    }

    @Test
    fun parsesPaddedTagsAndRejectsOtherText() {
        assertEquals("Battery", LogLine.parse("11-27 12:20:48.989 W/Battery ( 7089): ThermalControllHandler: level=[2]")!!.tag)
        assertNull(LogLine.parse("--------- beginning of main"))
    }

    @Test
    fun gapsAreMeasuredAcrossMidnight() {
        assertEquals(300, LogLine.gapMs(86_399_900, 200))
    }

    @Test
    fun signatureIgnoresNumbersInTheMessage() {
        val signature = LogButtonSignature("Keys", LogButtonSignature.normalize("key 735 down at 1200"), releaseLogged = false)
        assertTrue(signature.matches(LogLine(0, "Keys", 1, "key 735 down at 98431")))
        assertFalse(signature.matches(LogLine(0, "Keys", 1, "key 735 up at 98431")))
        assertFalse(signature.matches(LogLine(0, "Other", 1, "key 735 down at 98431")))
    }

    @Test
    fun onlyPlainTagsCanFilterTheLog() {
        assertTrue(LogButtonSignature.isFilterableTag("KEYLOG_OplusKeyEventUtil"))
        assertFalse(LogButtonSignature.isFilterableTag("a:b"))
        assertFalse(LogButtonSignature.isFilterableTag("two words"))
        assertFalse(LogButtonSignature.isFilterableTag(""))
    }

    @Test
    fun repeatedLinesOfOneEventAreOnePress() {
        val detector = LogPressDetector(releaseLogged = false)
        assertTrue(detector.onLine(1_000))
        assertFalse(detector.onLine(1_000))
        assertTrue(detector.onLine(1_344))
    }

    @Test
    fun releaseLineDoesNotCountAsAPress() {
        // Timings of a real double press of the OnePlus Plus Key: down, up, down, up.
        val detector = LogPressDetector(releaseLogged = true)
        assertEquals(listOf(true, false, false, false, true, false, false, false),
            listOf(4_085L, 4_085, 4_169, 4_169, 4_429, 4_429, 4_543, 4_543).map(detector::onLine))
    }

    @Test
    fun releaseAfterALongHoldIsStillARelease() {
        // A three-second hold of the Plus Key logs the same line when the button comes up.
        val detector = LogPressDetector(releaseLogged = true)
        assertEquals(listOf(true, false, true, false), listOf(9_077L, 12_158, 13_188, 16_176).map(detector::onLine))
    }

    @Test
    fun orderOfPressAndReleaseRecoversAfterAMissedLine() {
        val detector = LogPressDetector(releaseLogged = true)
        assertTrue(detector.onLine(1_000))
        // The release was missed; much later the next line can only be a press.
        assertTrue(detector.onLine(1_000 + LogPressDetector.RELEASE_TIMEOUT_MS + 1))
        assertFalse(detector.onLine(1_200 + LogPressDetector.RELEASE_TIMEOUT_MS))
    }

    private class Learning {
        val learner = LogButtonLearner(ownPid = 10)

        init {
            line(0, "System", "start")
        }

        fun line(atMs: Long, tag: String, message: String, pid: Int = 5034) =
            learner.onLine(LogLine(atMs, tag, pid, message), nowMs = atMs)

        fun press(atMs: Long, tag: String = "Keys", message: String = "undefined key", holdMs: Long? = 110) {
            line(atMs, tag, message)
            line(atMs, tag, message)
            holdMs?.let { line(atMs + it, tag, message) }
        }

        /** The state once the last line, logged at [lastLineMs], is surely the end of its group. */
        fun decided(lastLineMs: Long): LogButtonLearner.State {
            val settledAt = lastLineMs + LogButtonLearner.MAX_PRESS_GAP_MS
            learner.state(settledAt)
            return learner.state(settledAt + LogButtonLearner.UNNAMED_DECISION_DELAY_MS)
        }
    }

    private fun learned(tag: String, message: String, releaseLogged: Boolean) =
        LogButtonLearner.State.Learned(LogButtonSignature(tag, message, releaseLogged))

    @Test
    fun waitsForAccessThenCalibrates() {
        val learner = LogButtonLearner(ownPid = 10)
        learner.onLine(LogLine(0, "OpenDictate", 10, "own line"), nowMs = 0)
        assertEquals(LogButtonLearner.State.AwaitingAccess, learner.state(500))
        learner.onLine(LogLine(1_000, "System", 5034, "other line"), nowMs = 1_000)
        assertEquals(LogButtonLearner.State.Calibrating, learner.state(2_000))
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(2_500))
    }

    @Test
    fun learnsTheLineLeftByThreePresses() = with(Learning()) {
        // Real timings of the OnePlus Plus Key pressed about once a second.
        press(51_486, holdMs = 117)
        press(52_774, holdMs = 108)
        // Two presses in, a pause cannot make a button of them.
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(52_882 + 1_100))
        press(54_067, holdMs = 164)
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(54_231 + LogButtonLearner.MAX_PRESS_GAP_MS))
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), decided(54_231))
    }

    @Test
    fun learnsFromQuickPresses() = with(Learning()) {
        listOf(57_367L, 57_479, 57_577, 57_681, 57_763, 57_900).forEach { line(it, "Keys", "undefined key") }
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), decided(57_900))
    }

    @Test
    fun learnsAButtonThatLogsOnlyItsPress() = with(Learning()) {
        listOf(3_000L, 4_000, 5_000).forEach { press(it, "Hw", "press 17", holdMs = null) }
        assertEquals(learned("Hw", "press #", releaseLogged = false), decided(5_000))
    }

    @Test
    fun earlierStrayPressesDoNotSpoilTheLearning() = with(Learning()) {
        // The user tried the button once before following the instructions.
        press(3_000)
        listOf(8_000L, 9_200, 10_300).forEach { press(it) }
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), decided(10_410))
    }

    @Test
    fun linesSeenBeforeThePressesAreNotTheButton() {
        val learner = LogButtonLearner(ownPid = 10)
        fun line(atMs: Long, tag: String) = learner.onLine(LogLine(atMs, tag, 5034, "event"), nowMs = atMs)
        // Seen on a OnePlus 15: display updates kept the rhythm of three presses by themselves.
        line(0, "SurfaceFlinger")
        listOf(3_000L, 3_600, 4_300).forEach { line(it, "SurfaceFlinger") }
        learner.state(4_300 + LogButtonLearner.MAX_PRESS_GAP_MS)
        assertEquals(LogButtonLearner.State.AwaitingPresses,
            learner.state(4_300 + LogButtonLearner.MAX_PRESS_GAP_MS + LogButtonLearner.UNNAMED_DECISION_DELAY_MS))
    }

    @Test
    fun buttonTriedJustBeforeLearningIsStillFound() {
        val learner = LogButtonLearner(ownPid = 10)
        fun line(atMs: Long, tag: String) = learner.onLine(LogLine(atMs, tag, 5034, "event"), nowMs = atMs)
        line(0, "InputKeys")
        listOf(3_000L, 4_000, 5_000).forEach { line(it, "InputKeys") }
        learner.state(5_000 + LogButtonLearner.MAX_PRESS_GAP_MS)
        val state = learner.state(5_000 + LogButtonLearner.MAX_PRESS_GAP_MS + LogButtonLearner.DECISION_DELAY_MS)
        assertEquals("InputKeys", (state as LogButtonLearner.State.Learned).signature.tag)
    }

    @Test
    fun lineNamingAKeyWinsOverMotionThatFinishedEarlier() = with(Learning()) {
        // Seen on a OnePlus 15: picking the phone up logged three orientation events in rhythm
        // before the third press of the button.
        listOf(2_000L, 2_700, 3_400).forEach { line(it, "sensors-hal", "device_orient_event") }
        press(3_000)
        press(4_300)
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(3_400 + LogButtonLearner.MAX_PRESS_GAP_MS))
        press(5_600)
        assertEquals(LogButtonLearner.State.AwaitingPresses,
            learner.state(3_400 + LogButtonLearner.MAX_PRESS_GAP_MS + LogButtonLearner.DECISION_DELAY_MS))
        val settledAt = 5_710 + LogButtonLearner.MAX_PRESS_GAP_MS
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(settledAt))
        assertEquals(learned("Keys", "undefined key", releaseLogged = true),
            learner.state(settledAt + LogButtonLearner.DECISION_DELAY_MS))
    }

    @Test
    fun aNewLineWinsOverOneSeenBeforeWhenNeitherNamesAKey() {
        val learner = LogButtonLearner(ownPid = 10)
        fun line(atMs: Long, tag: String) = learner.onLine(LogLine(atMs, tag, 5034, "event"), nowMs = atMs)
        line(0, "Old")
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "Old")
            line(press + 2, "Fresh")
        }
        learner.state(5_002 + LogButtonLearner.MAX_PRESS_GAP_MS)
        val state = learner.state(5_002 + LogButtonLearner.MAX_PRESS_GAP_MS + LogButtonLearner.UNNAMED_DECISION_DELAY_MS)
        assertEquals("Fresh", (state as LogButtonLearner.State.Learned).signature.tag)
    }

    @Test
    fun prefersALineThatNamesAKey() = with(Learning()) {
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "Power", "user activity")
            line(press + 1, "InputKeys", "event")
        }
        assertEquals("InputKeys", (decided(5_001) as LogButtonLearner.State.Learned).signature.tag)
    }

    @Test
    fun waitsForTheButtonLineWhenASideEffectSettlesFirst() = with(Learning()) {
        // Seen on a OnePlus 15: a job is scheduled on each press, and its line settles before the
        // release line of the last press does.
        for (press in listOf(3_000L, 4_000, 5_000)) {
            press(press, holdMs = 150)
            line(press + 2, "JobInfo", "flag for job 4 is ignored")
        }
        assertEquals(LogButtonLearner.State.AwaitingPresses, learner.state(5_002 + LogButtonLearner.MAX_PRESS_GAP_MS))
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), decided(5_150))
    }

    @Test
    fun unrelatedLinesOutOfRhythmAreNotAButton() = with(Learning()) {
        // Seen on a OnePlus 15 while nobody pressed anything.
        listOf(15_630L, 25_507, 28_298).forEach { line(it, "JobInfo", "flag for job 4 is ignored") }
        // Too many in a row, too few, and one that cannot be used as a log filter.
        listOf(30_000L, 30_600, 31_200, 31_800).forEach { line(it, "Timer", "tick") }
        listOf(30_000L, 31_000).forEach { line(it, "Rare", "twice") }
        listOf(30_000L, 31_000, 32_000).forEach { line(it, "bad tag", "press") }
        assertEquals(LogButtonLearner.State.AwaitingPresses, decided(32_000))
        assertEquals(LogButtonLearner.State.Failed, learner.state(1_500 + LogButtonLearner.PRESS_TIMEOUT_MS + 1))
    }
}
