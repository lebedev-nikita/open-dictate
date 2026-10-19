package com.opendictate.app.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.TextProcessingModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextModelSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sharedTextModelStaysBesideRecognitionAndSurvivesRecreation() {
        val store = SettingsStore(compose.activity)
        val originalTier = store.textProcessingServiceTier
        val original = store.textProcessingModel
        val mode = store.model
        val label = compose.activity.getString(R.string.settings_text_model)
        try {
            compose.runOnIdle { ViewModelProvider(compose.activity)[MainViewModel::class.java].selectTextProcessingModel(TextProcessingModel.ID) }
            for (modeLabel in listOf(R.string.settings_mode_live, R.string.settings_mode_accurate)) {
                compose.onNodeWithText(compose.activity.getString(modeLabel)).performScrollTo().performClick()
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("GPT-6 Luna").assertIsDisplayed()
            }
            capture("closed")
            compose.onNodeWithText(label).performClick()
            compose.onAllNodesWithText("GPT-6 Luna").assertCountEquals(2)
            capture("open")
            compose.onNodeWithText("GPT-6 Luna Fast").assertIsDisplayed().performClick()
            assertEquals(TextProcessingModel.ID, store.textProcessingModel)
            assertEquals(com.opendictate.app.model.TextProcessingServiceTier.FAST, store.textProcessingServiceTier)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("GPT-6 Luna Fast").assertIsDisplayed()
            capture("fast")
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText("GPT-6 Luna").performClick()
            assertEquals(com.opendictate.app.model.TextProcessingServiceTier.STANDARD, store.textProcessingServiceTier)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("GPT-6 Luna").assertIsDisplayed()
        } finally {
            store.textProcessingModel = original
            store.textProcessingServiceTier = originalTier
            store.model = mode
        }
    }

    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDesign") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(300) // Let the popup surface reach the screenshot compositor.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "text-model-$state.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
