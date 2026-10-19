package com.opendictate.app.ui

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.data.SettingsSyncDocument
import com.opendictate.app.data.SettingsSyncEntry
import com.opendictate.app.data.ReplacementStore
import com.opendictate.app.model.PostProcessingSettings
import com.opendictate.app.model.PostProcessingPreset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PostProcessingSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun action(id: Int) { compose.onNodeWithText(label(id)).performScrollTo().performClick(); compose.waitForIdle() }

    @Test fun toggleDraftPresetsSaveResetAndRestart() {
        val store = SettingsStore(compose.activity)
        val original = store.postProcessing
        val preferences = compose.activity.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        val journal = preferences.getString("sync_document", null)
        preferences.edit().remove("sync_document").commit()
        try {
            store.postProcessing = PostProcessingSettings()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Transcription prompt").assertDoesNotExist()
            compose.onNodeWithText("Промпт транскрипции").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.postprocessing_prompt)).assertDoesNotExist()
            val header = hasContentDescription(label(R.string.postprocessing_title))
            compose.onAllNodes(header and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
            compose.onNodeWithText(label(R.string.postprocessing_title)).performScrollTo().performClick()
            assertTrue(store.postProcessing.enabled)
            compose.onNodeWithText("Post-processing model").assertDoesNotExist()
            compose.onNodeWithText("Модель постобработки").assertDoesNotExist()
            compose.onNodeWithText("Editing model").assertDoesNotExist()
            compose.onNodeWithText("Модель для правок").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.postprocessing_punctuation)).performScrollTo().assertIsNotEnabled()
            action(R.string.postprocessing_numbers)
            assertNull(store.postProcessing.prompt)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(PostProcessingPreset.NUMBERS.text, substring = true).assertExists()
            action(R.string.postprocessing_save)
            assertTrue(store.postProcessing.effectivePrompt.contains(PostProcessingPreset.NUMBERS.text))
            action(R.string.postprocessing_reset)
            action(R.string.postprocessing_save)
            assertNull(store.postProcessing.prompt)
            capture("expanded-bottom")
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo()
            capture("expanded")
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().performClick()
            assertFalse(store.postProcessing.enabled)
            compose.onNodeWithText(label(R.string.postprocessing_prompt)).assertDoesNotExist()
            assertNull(store.postProcessing.prompt)
            capture("collapsed")
        } finally {
            store.postProcessing = original
            preferences.edit().putString("sync_document", journal).commit()
        }
    }

    @Test fun cloudSettingsAreValidatedBeforeApplicationAndDefaultTextIsNotSynced() {
        val prefix = "postprocessing-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val store = SettingsStore(context)
        try {
            val remoteSettings = PostProcessingSettings(true, "Custom prompt 📝")
            val remote = SettingsSyncDocument(mapOf("postprocessing" to SettingsSyncEntry(remoteSettings.toJson(), 42, "remote")))
            val replacements = ReplacementStore(context)
            store.mergeSyncDocument(remote, replacements)
            assertEquals(remoteSettings, store.postProcessing)
            val invalid = SettingsSyncDocument(mapOf("postprocessing" to SettingsSyncEntry("{\"enabled\":true,\"prompt\":123}", 43, "remote")))
            assertTrue(runCatching { store.mergeSyncDocument(invalid, replacements) }.isFailure)
            assertEquals(remoteSettings, store.postProcessing)
            store.postProcessing = PostProcessingSettings(true)
            assertFalse(store.syncDocument().entries.getValue("postprocessing").value!!.contains(PostProcessingPreset.PUNCTUATION.text))
        } finally {
            compose.activity.deleteSharedPreferences("$prefix-opendictate_settings")
            compose.activity.deleteSharedPreferences("$prefix-word_replacements")
        }
    }

    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePostprocessing") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, "postprocessing-android-$state.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
