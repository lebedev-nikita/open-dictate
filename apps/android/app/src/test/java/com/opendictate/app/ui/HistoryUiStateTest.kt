package com.opendictate.app.ui

import com.opendictate.app.data.TranscriptHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryUiStateTest {
    private val roadmap = TranscriptHistoryItem(42, "Discuss the roadmap", 2)
    private val coffee = TranscriptHistoryItem(43, "Buy coffee", 1)
    private val entries = listOf(roadmap, coffee)

    @Test
    fun `typing a query immediately finds local fuzzy matches`() {
        val state = HistoryUiState(entries = entries, isLoading = false)
            .withHistoryQuery("roadmp")

        assertEquals("roadmp", state.query)
        assertEquals(listOf(roadmap), state.visibleEntries)
        assertEquals(emptyList<TranscriptHistoryItem>(), state.withHistoryQuery("unrelated").visibleEntries)
    }

    @Test
    fun `editing the query recomputes results and clearing restores history`() {
        val state = HistoryUiState(entries = entries, query = "roadmap", isLoading = false)
            .withHistoryQuery("coffee")

        assertEquals(listOf(coffee), state.visibleEntries)
        assertEquals(entries, state.withHistoryQuery("   ").visibleEntries)
        assertEquals(entries, state.withHistoryQuery("").visibleEntries)
    }

    @Test
    fun `typing while history loads preserves the pending load`() {
        val state = HistoryUiState(errorMessage = "Old error").withHistoryQuery("roadmap")

        assertTrue(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf(roadmap), state.copy(entries = entries, isLoading = false).visibleEntries)
    }

    @Test
    fun `search query length remains bounded`() {
        val state = HistoryUiState().withHistoryQuery("a".repeat(301))

        assertEquals("a".repeat(300), state.query)
    }
}
