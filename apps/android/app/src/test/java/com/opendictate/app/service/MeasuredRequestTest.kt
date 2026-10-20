package com.opendictate.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeasuredRequestTest {
    @Test fun timerIncludesSuspendedUploadAndResponseButNotEarlierRecordingOrLaterWork() = runTest {
        delay(10_000)
        val timed = measureRequest({ testScheduler.currentTime }) {
            delay(125) // Upload.
            delay(750) // Server and full response.
            "transcript"
        }
        delay(5_000)
        assertEquals("transcript", timed.value)
        assertEquals(875L, timed.milliseconds)
    }

    @Test fun cancellationCannotProduceMeasuredSuccess() = runTest {
        try {
            measureRequest({ testScheduler.currentTime }) { throw CancellationException() }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
    }
}
