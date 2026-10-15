package com.opendictate.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.R
import com.opendictate.app.data.TranscriptHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHistoryShowsGuidanceAndKeepsLoadErrorsVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = context.getString(R.string.history_load_error)
        compose.setContent {
            OpenDictateTheme {
                TranscriptHistoryScreen(
                    HistoryUiState(isLoading = false, errorMessage = error),
                    onBack = {}, onQueryChange = {}, onAiSearch = {}, onCopy = {}, onDelete = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.history_empty_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.history_search_label)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.history_ai_search)).assertDoesNotExist()
        capture("history-empty")
    }

    @Test fun savedHistoryKeepsSearchCopyAndConfirmedDeletionAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = TranscriptHistoryItem(42, "Synthetic layout sample for history controls.", 1_760_000_000_000)
        val state = mutableStateOf(HistoryUiState(entries = listOf(entry), isLoading = false))
        var copied: String? = null
        var deleted: Long? = null
        compose.setContent {
            OpenDictateTheme {
                TranscriptHistoryScreen(
                    state.value,
                    onBack = {},
                    onQueryChange = { state.value = state.value.withHistoryQuery(it) },
                    onAiSearch = { state.value = state.value.copy(errorMessage = context.getString(R.string.history_ai_search_error)) },
                    onCopy = { copied = it },
                    onDelete = { deleted = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.history_ai_search)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.history_search_label)).performTextInput("sample")
        compose.onNodeWithText(context.getString(R.string.history_ai_search)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.history_ai_search_error)).performScrollTo().assertIsDisplayed()
        capture("history-results")
        compose.onNodeWithText(entry.text).performScrollTo().performClick()
        assertEquals(entry.text, copied)
        compose.onNodeWithContentDescription(context.getString(R.string.history_delete_item)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(null, deleted)
        compose.onNodeWithContentDescription(context.getString(R.string.history_delete_item)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.history_delete_action)).performClick()
        assertEquals(entry.id, deleted)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureDesign") != "true") return
        compose.waitForIdle()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
