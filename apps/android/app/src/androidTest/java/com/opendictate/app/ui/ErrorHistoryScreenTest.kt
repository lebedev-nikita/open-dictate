package com.opendictate.app.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.model.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ErrorHistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val store get() = (compose.activity.application as OpenDictateApplication).errorHistoryStore

    @Test fun longPressOpensSeparatePersistentScreenAndCanCopyAndClear() {
        val arguments = InstrumentationRegistry.getArguments()
        val locale = arguments.getString("errorHistoryLocale", "en")
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[MainViewModel::class.java].selectTheme(if (locale == "ru") AppTheme.DARK else AppTheme.LIGHT)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
        }
        compose.activityRule.scenario.recreate()
        store.clear()
        val attempt = ErrorContext(UUID.randomUUID().toString(), ErrorStage.POST_PROCESSING, ErrorRoute.CHATGPT,
            ErrorOperation.DICTATION, TranscriptionModel.ACCURATE, DiagnosticModel.TEXT)
        store.add(attempt, FailureDetails(ErrorReason.SPACING_CHANGED), 1920)
        store.add(attempt, FailureDetails.http(429), 2870)

        compose.onNodeWithContentDescription(label(R.string.history_open)).performClick()
        compose.onNodeWithText(label(R.string.history_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.history_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.history_open)).performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.error_history_title)).assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.error_reason_http)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.error_reason_http)).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(label(R.string.error_history_title)).assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.error_history_copy)).fetchSemanticsNodes().isNotEmpty() }
        val shot = arguments.getString("errorHistoryScreenshot")
        if (shot != null) capture(shot)
        compose.onAllNodesWithText(label(R.string.error_history_copy)).onFirst().performClick()
        compose.runOnIdle {
            val report = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("HTTP: 429"))
            assertTrue(report.contains("Route: CHATGPT"))
        }
        compose.onNodeWithContentDescription(label(R.string.error_history_clear)).performClick()
        compose.onNodeWithText(label(R.string.error_history_clear)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.error_history_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.error_history_empty)).assertIsDisplayed()
        assertTrue(store.getAll().isEmpty())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.error_history_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.error_history_empty)).assertIsDisplayed()
        if (shot != null) capture("$shot-empty")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(label(R.string.history_open)).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText(label(R.string.error_history_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.history_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.history_open)).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
