package com.opendictate.app.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TranscriptionPromptSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun openEditor() {
        compose.onNodeWithText(label(R.string.transcription_prompt_title)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.transcription_prompt_text)).assertExists()
    }
    private fun action(id: Int) { compose.onNodeWithText(label(id)).performScrollTo().performClick(); compose.waitForIdle() }

    @Test fun draftsRequireSaveSurviveRecreationAndResetCanBeCancelled() {
        val settings = SettingsStore(compose.activity)
        val original = settings.customTranscriptionPrompt
        try {
            settings.customTranscriptionPrompt = "Synthetic original"
            compose.activityRule.scenario.recreate()
            openEditor()
            compose.onNodeWithText(label(R.string.transcription_prompt_save)).assertIsNotEnabled()
            compose.onNodeWithText(label(R.string.transcription_prompt_text)).performTextReplacement("Synthetic draft 📝")
            assertEquals("Synthetic original", settings.customTranscriptionPrompt)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Synthetic draft 📝").assertExists()
            action(R.string.transcription_prompt_cancel)
            assertEquals("Synthetic original", settings.customTranscriptionPrompt)
            openEditor()
            compose.onNodeWithText(label(R.string.transcription_prompt_text)).performTextReplacement("Synthetic saved")
            action(R.string.transcription_prompt_save)
            assertEquals("Synthetic saved", settings.customTranscriptionPrompt)
            openEditor()
            action(R.string.transcription_prompt_reset)
            assertEquals("Synthetic saved", settings.customTranscriptionPrompt)
            action(R.string.transcription_prompt_cancel)
            assertEquals("Synthetic saved", settings.customTranscriptionPrompt)
            openEditor()
            action(R.string.transcription_prompt_reset)
            action(R.string.transcription_prompt_save)
            assertNull(settings.customTranscriptionPrompt)
            assertTrue(settings.syncDocument().entries.containsKey("transcriptionPrompt"))
            assertNull(settings.syncDocument().entries.getValue("transcriptionPrompt").value)
        } finally { settings.customTranscriptionPrompt = original }
    }

    @Test fun defaultEditorShowsBundledPromptWithoutCreatingAnOverride() {
        val settings = SettingsStore(compose.activity)
        val original = settings.customTranscriptionPrompt
        try {
            settings.customTranscriptionPrompt = null
            compose.activityRule.scenario.recreate()
            val before = settings.syncDocument()
            openEditor()
            compose.onNodeWithText(label(R.string.transcription_prompt_save)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(label(R.string.transcription_prompt_reset)).assertIsNotEnabled()
            assertEquals("", com.opendictate.app.model.TranscriptionPrompt.defaultText(settings.languages))
            capture()
            action(R.string.transcription_prompt_cancel)
            assertEquals(before, settings.syncDocument())
        } finally { settings.customTranscriptionPrompt = original }
    }

    private fun capture() {
        if (InstrumentationRegistry.getArguments().getString("capturePrompts") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, "prompt-android.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
