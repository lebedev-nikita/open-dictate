package com.opendictate.app.service

import com.opendictate.app.model.ExternalDictationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalDictationExchangeTest {
    @Test
    fun deliversOnlyTheAwaitedRequest() {
        val exchange = ExternalDictationExchange()
        exchange.await("a")
        exchange.deliver("b", ExternalDictationOutcome.Transcribed("other"))
        assertNull(exchange.delivery.value)

        exchange.deliver("a", ExternalDictationOutcome.Transcribed("text"))
        assertEquals(ExternalDictationDelivery("a", ExternalDictationOutcome.Transcribed("text")), exchange.delivery.value)
    }

    @Test
    fun releaseDropsTheTranscriptAndIgnoresLateResults() {
        val exchange = ExternalDictationExchange()
        exchange.await("a")
        exchange.deliver("a", ExternalDictationOutcome.Transcribed("text"))
        exchange.release("a")
        assertNull(exchange.delivery.value)
        assertNull(exchange.awaitedRequestId)

        exchange.deliver("a", ExternalDictationOutcome.Transcribed("late"))
        assertNull(exchange.delivery.value)
    }

    @Test
    fun newRequestClearsAnEarlierUnclaimedResult() {
        val exchange = ExternalDictationExchange()
        exchange.await("a")
        exchange.deliver("a", ExternalDictationOutcome.Cancelled)
        exchange.await("b")
        assertNull(exchange.delivery.value)
        assertEquals("b", exchange.awaitedRequestId)
    }

    @Test
    fun releasingAnOlderRequestKeepsTheCurrentOne() {
        val exchange = ExternalDictationExchange()
        exchange.await("a")
        exchange.await("b")
        exchange.release("a")
        assertEquals("b", exchange.awaitedRequestId)
    }
}
