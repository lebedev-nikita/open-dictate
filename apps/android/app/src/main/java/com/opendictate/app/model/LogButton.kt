package com.opendictate.app.model

/**
 * One line of `logcat -v time`. [timeMs] is the time of day, which is enough to measure the gaps
 * between lines.
 */
data class LogLine(val timeMs: Long, val tag: String, val pid: Int, val message: String) {
    companion object {
        private val FORMAT = Regex("""^\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d{3}) [VDIWEFA]/(.*?)\(\s*(\d+)\): ?(.*)$""")

        fun parse(line: String): LogLine? {
            val (hours, minutes, seconds, millis, tag, pid, message) = FORMAT.matchEntire(line)?.destructured ?: return null
            val timeMs = ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + millis.toLong()
            // logcat pads short tags with spaces.
            return LogLine(timeMs, tag.trimEnd(), pid.toIntOrNull() ?: return null, message)
        }

        /** Milliseconds from [earlier] to [later] on the time-of-day clock, across midnight. */
        fun gapMs(earlier: Long, later: Long): Long = (later - earlier).mod(DAY_MS)

        private const val DAY_MS = 86_400_000L
    }
}

/**
 * The log line a hardware button leaves when Android does not deliver its key events to apps, such
 * as the Plus Key on OnePlus phones. Numbers in [message] are replaced by `#`, so counters and
 * timestamps do not matter. With [releaseLogged] the same line appears again when the button is
 * released, and nothing but their order tells the two apart.
 */
data class LogButtonSignature(val tag: String, val message: String, val releaseLogged: Boolean) {
    fun matches(line: LogLine): Boolean = line.tag == tag && normalize(line.message) == message

    companion object {
        private val DIGITS = Regex("""\d+""")

        fun normalize(message: String): String = message.replace(DIGITS, "#")

        /** Only such a tag can be passed to logcat as a filter, which keeps other lines out of the app. */
        fun isFilterableTag(tag: String): Boolean = tag.isNotEmpty() && tag.none { it == ':' || it == '*' || it.isWhitespace() }
    }
}

/** Turns the lines of a learned button into presses. */
class LogPressDetector(private val releaseLogged: Boolean) {
    private var lastLineMs: Long? = null
    private var awaitedReleaseOfMs: Long? = null

    /** Returns true when the line logged at [timeMs] begins a new press. */
    fun onLine(timeMs: Long): Boolean {
        val sameBurst = lastLineMs?.let { LogLine.gapMs(it, timeMs) <= BURST_GAP_MS } == true
        lastLineMs = timeMs
        if (sameBurst) return false
        val pressedAt = awaitedReleaseOfMs
        awaitedReleaseOfMs = null
        if (pressedAt != null && LogLine.gapMs(pressedAt, timeMs) <= RELEASE_TIMEOUT_MS) return false
        if (releaseLogged) awaitedReleaseOfMs = timeMs
        return true
    }

    companion object {
        /** One key event can be logged several times within the same instant. */
        const val BURST_GAP_MS = 25L

        /**
         * Nobody holds a button this long, so a later line is a new press. This also recovers the
         * order of presses and releases after a line was missed.
         */
        const val RELEASE_TIMEOUT_MS = 10_000L
    }
}

/**
 * Finds a button's log line while the user presses the button [presses] times, about once a second.
 * The line is recognized by that rhythm, a group of presses with quiet before and after it, and by
 * being new: recent lines, including those left by the taps that opened the screen, are known to be
 * unrelated. The user then presses other buttons, such as volume, and every line those leave as well
 * is dropped: the system can log one line for any hardware button. Lines are held in memory only
 * until a result is known.
 */
class LogButtonLearner(private val ownPid: Int, private val presses: Int = PRESSES) {
    sealed interface State {
        /** No line from another app arrived yet: the system has not granted log access. */
        data object AwaitingAccess : State
        data object Calibrating : State
        data object AwaitingPresses : State
        /** The button's lines are known; presses of other buttons now tell which line is its own. */
        data object AwaitingOtherButtons : State
        data class Learned(val signature: LogButtonSignature) : State
        data object Failed : State
        /** Every line the button leaves is also left by the other buttons. */
        data object Indistinct : State
    }

    private class Candidate(val tag: String, val message: String, val seenBefore: Boolean) {
        /** Start times of the latest bursts of lines; a burst is one press or one release. */
        val bursts = ArrayList<Long>()
        var lastLineMs: Long? = null
        var lastArrivalMs = 0L
    }

    private var accessAtMs: Long? = null
    private var matched: Candidate? = null
    private var matchedAtMs = 0L
    /** Lines that kept the rhythm of the presses, from the moment other buttons are asked for. */
    private var inRhythm: List<Pair<Candidate, LogButtonSignature>>? = null
    private var othersSinceMs = 0L
    private var otherPresses = 0
    private var lastOtherPressMs = 0L
    private var result: State? = null
    private val seenBefore = HashSet<Pair<String, String>>()
    private val candidates = LinkedHashMap<Pair<String, String>, Candidate>()

    /** [nowMs] is a monotonic clock; the line's own time measures the gaps between presses. */
    fun onLine(line: LogLine, nowMs: Long) {
        // Without log access the app still sees its own lines.
        if (line.pid == ownPid) return
        val accessAt = accessAtMs ?: nowMs.also { accessAtMs = it }
        val key = line.tag to LogButtonSignature.normalize(line.message)
        if (nowMs - accessAt < CALIBRATION_MS) {
            seenBefore += key
            return
        }
        if (!LogButtonSignature.isFilterableTag(line.tag)) return
        val candidate = candidates[key] ?: run {
            if (candidates.size >= MAX_CANDIDATES) return
            Candidate(key.first, key.second, key in seenBefore).also { candidates[key] = it }
        }
        val sameBurst = candidate.lastLineMs?.let { LogLine.gapMs(it, line.timeMs) <= LogPressDetector.BURST_GAP_MS } == true
        candidate.lastLineMs = line.timeMs
        candidate.lastArrivalMs = nowMs
        if (sameBurst) return
        candidate.bursts += line.timeMs
        if (candidate.bursts.size > MAX_BURSTS) candidate.bursts.removeAt(0)
    }

    /**
     * A best guess for showing progress while the button's line is still unknown: the presses in
     * the group that a line naming a key is going through. A line that was already in the log shows
     * only from its second press, because its first one may be anything.
     */
    fun heardPresses(nowMs: Long): Int {
        if (inRhythm != null) return presses
        return candidates.values.maxOfOrNull { candidate ->
            val heard = if (rank(candidate) >= NAMED_RANK) pressesOf(candidate, nowMs) else 0
            if (candidate.seenBefore && heard < 2) 0 else heard
        } ?: 0
    }

    private fun pressesOf(candidate: Candidate, nowMs: Long): Int {
        if (nowMs - candidate.lastArrivalMs >= MAX_PRESS_GAP_MS) return if (signatureOf(candidate, nowMs) != null) presses else 0
        // A burst soon after another is the release of the same press.
        val heard = 1 + group(candidate).zipWithNext().count { (earlier, later) -> LogLine.gapMs(earlier, later) >= MIN_PRESS_GAP_MS }
        return if (heard <= presses) heard else 0
    }

    /** A press of a button the app can see as a key, such as volume, after the presses were found. */
    fun onOtherButton(nowMs: Long) {
        if (inRhythm == null || result != null) return
        otherPresses++
        lastOtherPressMs = nowMs
    }

    fun state(nowMs: Long): State {
        result?.let { return it }
        val accessAt = accessAtMs ?: return State.AwaitingAccess
        val pressingFor = nowMs - accessAt - CALIBRATION_MS
        if (pressingFor < 0) return State.Calibrating
        inRhythm?.let { found ->
            if (otherPresses < OTHER_PRESSES || nowMs - lastOtherPressMs < OTHER_SETTLE_MS) return State.AwaitingOtherButtons
            val own = found.filter { (candidate, _) -> candidate.lastArrivalMs <= othersSinceMs }
                // A line without numbers is the same in every state of the phone.
                .maxWithOrNull(compareBy({ (candidate, _) -> rank(candidate) }, { (candidate, _) -> '#' !in candidate.message }))
            return (own?.let { State.Learned(it.second) } ?: State.Indistinct).also { result = it }
        }
        val matching = candidates.values
            .filter { rank(it) > 0 }
            .mapNotNull { candidate -> signatureOf(candidate, nowMs)?.let { candidate to it } }
        val best = matching.maxByOrNull { (candidate, _) -> rank(candidate) }?.first
        if (best !== matched) {
            matched = best
            matchedAtMs = nowMs
        }
        if (best == null) return if (pressingFor > PRESS_TIMEOUT_MS) State.Failed.also { result = it } else State.AwaitingPresses
        // Other lines can keep the rhythm before the button's own line has finished: a side effect
        // of each press, or the motion sensor as the phone is picked up. A line that names a key is
        // taken soon; any other waits for a better one to finish its presses.
        val delay = if (rank(best) >= NAMED_RANK) DECISION_DELAY_MS else UNNAMED_DECISION_DELAY_MS
        if (nowMs - matchedAtMs < delay) return State.AwaitingPresses
        inRhythm = matching
        othersSinceMs = nowMs
        return State.AwaitingOtherButtons
    }

    /**
     * The latest group of bursts no more than [MAX_PRESS_GAP_MS] apart, once it has ended, must be
     * the requested presses: one burst per press, or two when the release is logged as well.
     */
    private fun signatureOf(candidate: Candidate, nowMs: Long): LogButtonSignature? {
        if (nowMs - candidate.lastArrivalMs < MAX_PRESS_GAP_MS) return null
        val group = group(candidate)
        val releaseLogged = when (group.size) {
            presses * 2 -> true
            // A press and its quick release must not pass for two presses of a button that logs once.
            presses -> if (group.zipWithNext().all { (earlier, later) -> LogLine.gapMs(earlier, later) >= MIN_PRESS_GAP_MS }) false else return null
            else -> return null
        }
        return LogButtonSignature(candidate.tag, candidate.message, releaseLogged)
    }

    private fun group(candidate: Candidate): List<Long> {
        val bursts = candidate.bursts
        var first = bursts.lastIndex
        while (first > 0 && LogLine.gapMs(bursts[first - 1], bursts[first]) <= MAX_PRESS_GAP_MS) first--
        return if (bursts.isEmpty()) bursts else bursts.subList(first, bursts.size)
    }

    /**
     * A busy log holds lines that keep the rhythm by chance, such as display updates, and several
     * lines can follow each press. A line that was there before the presses is taken only when it
     * names a key: the user may have tried the button just before. Zero means never.
     */
    private fun rank(candidate: Candidate): Int {
        val namesButton = BUTTON_WORDS.any { candidate.tag.contains(it, ignoreCase = true) || candidate.message.contains(it, ignoreCase = true) }
        return (if (namesButton) NAMED_RANK else 0) + (if (candidate.seenBefore) 0 else 1)
    }

    companion object {
        const val PRESSES = 3
        const val CALIBRATION_MS = 1_500L
        const val DECISION_DELAY_MS = 500L
        const val UNNAMED_DECISION_DELAY_MS = 4_500L
        private const val NAMED_RANK = 2
        const val MIN_PRESS_GAP_MS = 300L
        const val MAX_PRESS_GAP_MS = 2_000L
        const val PRESS_TIMEOUT_MS = 60_000L
        const val OTHER_PRESSES = 2
        const val OTHER_SETTLE_MS = 1_000L
        private const val MAX_CANDIDATES = 2_000
        private const val MAX_BURSTS = 12
        private val BUTTON_WORDS = listOf("key", "button")
    }
}
