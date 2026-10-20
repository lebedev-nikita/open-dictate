package com.opendictate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HistoryTimingTest {
    @Test fun durationsUseLocalizedTenthsWithoutPretendingFastRequestsTookZero() {
        for ((milliseconds, expected) in listOf(0L to "<0.1", 99L to "<0.1", 100L to "0.1",
            1249L to "1.2", 1250L to "1.3", 62_350L to "62.4", Long.MAX_VALUE to "9223372036854775.8")) {
            assertEquals(expected, formatHistorySeconds(milliseconds, Locale.US))
            assertEquals(expected.replace('.', ','), formatHistorySeconds(milliseconds, Locale.forLanguageTag("ru")))
        }
    }
}
