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

    private class Learning(calibrationLines: List<String> = listOf("System")) {
        val learner = LogButtonLearner(ownPid = 10)

        init {
            calibrationLines.forEach { line(0, it, "event") }
        }

        fun line(atMs: Long, tag: String, message: String = "event", pid: Int = 5034) =
            learner.onLine(LogLine(atMs, tag, pid, message), nowMs = atMs)

        fun press(atMs: Long, tag: String = "Keys", message: String = "undefined key", holdMs: Long? = 110) {
            line(atMs, tag, message)
            line(atMs, tag, message)
            holdMs?.let { line(atMs + it, tag, message) }
        }

        /** The state once the last line, logged at [lastLineMs], is surely the end of its group. */
        fun afterPresses(lastLineMs: Long, named: Boolean = false): LogButtonLearner.State {
            val settledAt = lastLineMs + LogButtonLearner.MAX_PRESS_GAP_MS
            learner.state(settledAt)
            return learner.state(settledAt + if (named) LogButtonLearner.DECISION_DELAY_MS else LogButtonLearner.UNNAMED_DECISION_DELAY_MS)
        }

        /** Two other buttons pressed at [firstMs] and a second later; [linesOf] logs what each leaves. */
        fun afterOtherButtons(firstMs: Long, linesOf: (Long) -> Unit = {}): LogButtonLearner.State {
            for (at in listOf(firstMs, firstMs + 1_000)) {
                learner.onOtherButton(at)
                linesOf(at)
            }
            assertEquals(LogButtonLearner.State.AwaitingOtherButtons, learner.state(firstMs + 1_500))
            return learner.state(firstMs + 1_000 + LogButtonLearner.OTHER_SETTLE_MS)
        }

        fun learnedTag(lastLineMs: Long): String? {
            assertEquals(LogButtonLearner.State.AwaitingOtherButtons, afterPresses(lastLineMs))
            return (afterOtherButtons(lastLineMs + 20_000) as? LogButtonLearner.State.Learned)?.signature?.tag
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
        assertEquals(LogButtonLearner.State.AwaitingOtherButtons, afterPresses(54_231, named = true))
        // One other button is not enough to tell.
        learner.onOtherButton(60_000)
        assertEquals(LogButtonLearner.State.AwaitingOtherButtons, learner.state(70_000))
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), afterOtherButtons(71_000))
    }

    @Test
    fun showsPressesAsTheyAreHeard() = with(Learning()) {
        assertEquals(0, learner.heardPresses(2_000))
        press(3_000)
        assertEquals(1, learner.heardPresses(3_200))
        press(4_200)
        assertEquals(2, learner.heardPresses(4_400))
        press(5_300)
        assertEquals(3, learner.heardPresses(5_500))
        // The count stays while the group is checked and once it is accepted.
        assertEquals(3, learner.heardPresses(5_410 + LogButtonLearner.MAX_PRESS_GAP_MS))
        afterPresses(5_410, named = true)
        assertEquals(3, learner.heardPresses(20_000))
    }

    @Test
    fun forgetsPressesThatDidNotMakeThree() = with(Learning()) {
        press(3_000)
        press(4_200)
        assertEquals(2, learner.heardPresses(4_400))
        // The pause was too long: the next press starts over.
        assertEquals(0, learner.heardPresses(4_310 + LogButtonLearner.MAX_PRESS_GAP_MS))
        press(9_000)
        assertEquals(1, learner.heardPresses(9_200))
        // A fourth press in a row is not three presses either.
        listOf(10_000L, 11_000, 12_000).forEach { press(it) }
        assertEquals(0, learner.heardPresses(12_200))
    }

    @Test
    fun lineAlreadyInTheLogShowsFromItsSecondPress() = with(Learning(listOf("WindowKeys"))) {
        // Seen on a OnePlus 15: a line naming a key is logged whenever a window opens.
        line(3_000, "WindowKeys")
        assertEquals(0, learner.heardPresses(3_200))
        line(4_000, "WindowKeys")
        assertEquals(2, learner.heardPresses(4_200))
    }

    @Test
    fun learnsFromQuickPresses() = with(Learning()) {
        listOf(57_367L, 57_479, 57_577, 57_681, 57_763, 57_900).forEach { line(it, "Keys", "undefined key") }
        afterPresses(57_900)
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), afterOtherButtons(70_000))
    }

    @Test
    fun learnsAButtonThatLogsOnlyItsPress() = with(Learning()) {
        listOf(3_000L, 4_000, 5_000).forEach { press(it, "Hw", "press 17", holdMs = null) }
        afterPresses(5_000)
        assertEquals(learned("Hw", "press #", releaseLogged = false), afterOtherButtons(20_000))
    }

    @Test
    fun lineLeftByAnyHardwareButtonIsNotTheButton() = with(Learning()) {
        // Seen on a OnePlus 15: one line is logged for every hardware key, volume included, and
        // comes before the line that only the Plus Key leaves.
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "system_server", "start saupwkProcessKey")
            line(press + 1, "KEYLOG_OplusKeyEventUtil", "undefined keys")
            line(press + 120, "system_server", "start saupwkProcessKey")
            line(press + 121, "KEYLOG_OplusKeyEventUtil", "undefined keys")
        }
        assertEquals(LogButtonLearner.State.AwaitingOtherButtons, afterPresses(5_121, named = true))
        val state = afterOtherButtons(20_000) { volumePress ->
            line(volumePress, "system_server", "start saupwkProcessKey")
            line(volumePress + 110, "system_server", "start saupwkProcessKey")
        }
        assertEquals(learned("KEYLOG_OplusKeyEventUtil", "undefined keys", releaseLogged = true), state)
    }

    @Test
    fun buttonWithoutALineOfItsOwnIsIndistinct() = with(Learning()) {
        listOf(3_000L, 4_000, 5_000).forEach { press(it, "AnyKey", "key event") }
        afterPresses(5_110, named = true)
        assertEquals(LogButtonLearner.State.Indistinct, afterOtherButtons(20_000) { line(it, "AnyKey", "key event") })
    }

    @Test
    fun prefersALineWithoutNumbers() = with(Learning()) {
        // A line that carries the phone's state stops matching when that state changes.
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "KeyPolicy", "key down, screenOn = true, flags=0x8")
            line(press + 1, "KeyUtil", "undefined key")
        }
        afterPresses(5_001, named = true)
        assertEquals(learned("KeyUtil", "undefined key", releaseLogged = false), afterOtherButtons(20_000))
    }

    @Test
    fun earlierStrayPressesDoNotSpoilTheLearning() = with(Learning()) {
        // The user tried the button once before following the instructions.
        press(3_000)
        listOf(8_000L, 9_200, 10_300).forEach { press(it) }
        afterPresses(10_410)
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), afterOtherButtons(30_000))
    }

    @Test
    fun linesSeenBeforeThePressesAreNotTheButton() = with(Learning(listOf("SurfaceFlinger"))) {
        // Seen on a OnePlus 15: display updates kept the rhythm of three presses by themselves.
        listOf(3_000L, 3_600, 4_300).forEach { line(it, "SurfaceFlinger") }
        assertEquals(LogButtonLearner.State.AwaitingPresses, afterPresses(4_300))
    }

    @Test
    fun buttonTriedJustBeforeLearningIsStillFound() = with(Learning(listOf("InputKeys"))) {
        listOf(3_000L, 4_000, 5_000).forEach { line(it, "InputKeys") }
        assertEquals("InputKeys", learnedTag(5_000))
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
        assertEquals(LogButtonLearner.State.AwaitingOtherButtons, afterPresses(5_710, named = true))
        assertEquals(learned("Keys", "undefined key", releaseLogged = true), afterOtherButtons(20_000))
    }

    @Test
    fun aNewLineWinsOverOneSeenBeforeWhenNeitherNamesAKey() = with(Learning(listOf("Old"))) {
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "Old")
            line(press + 2, "Fresh")
        }
        assertEquals("Fresh", learnedTag(5_002))
    }

    @Test
    fun prefersALineThatNamesAKey() = with(Learning()) {
        for (press in listOf(3_000L, 4_000, 5_000)) {
            line(press, "Power", "user activity")
            line(press + 1, "InputKeys", "event")
        }
        assertEquals("InputKeys", learnedTag(5_001))
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
        assertEquals("Keys", learnedTag(5_150))
    }

    @Test
    fun unrelatedLinesOutOfRhythmAreNotAButton() = with(Learning()) {
        // Seen on a OnePlus 15 while nobody pressed anything.
        listOf(15_630L, 25_507, 28_298).forEach { line(it, "JobInfo", "flag for job 4 is ignored") }
        // Too many in a row, too few, and one that cannot be used as a log filter.
        listOf(30_000L, 30_600, 31_200, 31_800).forEach { line(it, "Timer", "tick") }
        listOf(30_000L, 31_000).forEach { line(it, "Rare", "twice") }
        listOf(30_000L, 31_000, 32_000).forEach { line(it, "bad tag", "press") }
        assertEquals(LogButtonLearner.State.AwaitingPresses, afterPresses(32_000))
        assertEquals(LogButtonLearner.State.Failed, learner.state(1_500 + LogButtonLearner.PRESS_TIMEOUT_MS + 1))
    }
}
