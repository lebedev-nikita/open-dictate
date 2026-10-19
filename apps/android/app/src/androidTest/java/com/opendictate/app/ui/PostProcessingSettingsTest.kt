package com.opendictate.app.ui

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.*
import com.opendictate.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PostProcessingSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun action(id: Int) { compose.onNodeWithText(label(id)).performScrollTo().performClick(); compose.waitForIdle() }
    private fun dateField() = compose.onNode(hasSetTextAction() and hasContentDescription(ValueKind.DATE.title(compose.activity.resources.configuration.locales[0].language == "ru")))
    private fun closeKeyboard() { androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle() }

    @Test fun menuDraftSaveCancelResetAndRestartWithoutPromptEditor() {
        val store = SettingsStore(compose.activity)
        val original = store.postProcessing; val originalMode = store.model
        val prefs = compose.activity.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        val journal = prefs.getString("sync_document", null)
        prefs.edit().remove("sync_document").commit()
        try {
            compose.runOnIdle { androidx.lifecycle.ViewModelProvider(compose.activity)[MainViewModel::class.java].savePostProcessing(PostProcessingSettings()) }
            action(R.string.settings_mode_live)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).assertDoesNotExist()
            action(R.string.settings_mode_accurate)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().performClick()
            assertTrue(store.postProcessing.enabled); assertTrue(store.postProcessing.punctuation)
            compose.onNodeWithText("Post-processing prompt").assertDoesNotExist()
            compose.onNodeWithText("Промпт постобработки").assertDoesNotExist()
            compose.onAllNodes(hasContentDescription(label(R.string.postprocessing_title)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
            action(R.string.postprocessing_values)
            dateField().performScrollTo().performTextReplacement("")
            compose.onNodeWithTag("valueFormatsSave").assertIsNotEnabled()
            dateField().performTextReplacement("2026-03-05")
            assertNull(store.postProcessing.formats)
            closeKeyboard()
            compose.activityRule.scenario.recreate()
            dateField().assertTextContains("2026-03-05")
            capture("formats")
            val fractionLabel = ValueKind.FRACTION.title(compose.activity.resources.configuration.locales[0].language == "ru")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.value_formats_choose, fractionLabel)).performScrollTo().performClick()
            compose.onNodeWithText("¾").performClick()
            capture("formats-bottom")
            compose.onNodeWithTag("valueFormatsSave").performClick(); compose.waitForIdle()
            assertTrue(store.postProcessing.formatValues)
            val hourSetting = android.provider.Settings.System.getString(compose.activity.contentResolver, android.provider.Settings.System.TIME_12_24)
            val defaultHour = when (hourSetting) { "24" -> true; "12" -> false; else -> null }
            assertEquals(ValueFormatting.systemDefaults(Resources.getSystem().configuration.locales[0], defaultHour).entries.getValue(ValueKind.TIME),
                store.postProcessing.formats!!.entries.getValue(ValueKind.TIME))
            assertEquals("¾", store.postProcessing.formats!!.entries.getValue(ValueKind.FRACTION).example)
            assertEquals("2026-03-05", store.postProcessing.formats!!.entries.getValue(ValueKind.DATE).example)
            action(R.string.postprocessing_values)
            dateField().performScrollTo().performTextReplacement("03/05/2026")
            closeKeyboard(); compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick(); compose.waitForIdle()
            assertEquals("2026-03-05", store.postProcessing.formats!!.entries.getValue(ValueKind.DATE).example)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_values)).performScrollTo().performClick()
            assertFalse(store.postProcessing.formatValues)
            assertNotNull(store.postProcessing.formats)
            action(R.string.postprocessing_values)
            compose.onNodeWithText(label(R.string.value_formats_reset)).performClick(); compose.waitForIdle()
            dateField().assertTextContains(ValueFormatting.systemDefaults(Resources.getSystem().configuration.locales[0]).entries.getValue(ValueKind.DATE).example)
            compose.onNodeWithTag("valueFormatsSave").performClick(); compose.waitForIdle()
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo()
            compose.onNodeWithText(label(R.string.postprocessing_reset_settings)).performScrollTo()
            capture("expanded")
            action(R.string.postprocessing_reset_settings)
            assertEquals(PostProcessingSettings(enabled = true), store.postProcessing)
            action(R.string.settings_mode_live)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).assertDoesNotExist()
            action(R.string.settings_mode_accurate)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().performClick()
            assertFalse(store.postProcessing.enabled)
            compose.onNodeWithText(label(R.string.postprocessing_values)).assertDoesNotExist()
            capture("collapsed")
        } finally {
            store.postProcessing = original; store.model = originalMode
            prefs.edit().putString("sync_document", journal).commit()
        }
    }
    @Test fun cloudOptionsValidateAtomicallyAndContainNoCompiledInstructions() {
        val prefix = "postprocessing-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val store = SettingsStore(context)
        try {
            val remoteSettings = PostProcessingSettings(true, formatValues = true, formats = ValueFormatting.systemDefaults(Locale.forLanguageTag("ru-RU")))
            val remote = SettingsSyncDocument(mapOf("postprocessingOptions" to SettingsSyncEntry(remoteSettings.toJson(), 42, "remote")))
            val replacements = ReplacementStore(context)
            store.mergeSyncDocument(remote, replacements)
            assertEquals(remoteSettings, store.postProcessing)
            val dictionary = store.prompt
            val invalid = SettingsSyncDocument(mapOf(
                "postprocessingOptions" to SettingsSyncEntry("{\"schemaVersion\":1,\"enabled\":true,\"punctuation\":true,\"valuesEnabled\":true,\"formats\":{}}", 43, "remote"),
                "dictionary" to SettingsSyncEntry("Must not apply", 43, "remote")))
            assertTrue(runCatching { store.mergeSyncDocument(invalid, replacements) }.isFailure)
            assertEquals(remoteSettings, store.postProcessing); assertEquals(dictionary, store.prompt)
            val encoded = store.syncDocument().entries.getValue("postprocessingOptions").value!!
            assertFalse(encoded.contains("prompt")); assertFalse(encoded.contains("Correct punctuation"))
        } finally {
            compose.activity.deleteSharedPreferences("$prefix-opendictate_settings")
            compose.activity.deleteSharedPreferences("$prefix-word_replacements")
        }
    }
    @Test fun legacyPromptIsReplacedOnceAndCannotReturnFromOldLocalStorage() {
        val prefix = "postprocessing-migrate-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val prefs = context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("postprocessing", "{\"enabled\":true,\"prompt\":\"Legacy custom instruction\"}").commit()
            val store = SettingsStore(context)
            assertEquals(PostProcessingSettings(enabled = true), store.postProcessing)
            assertFalse(prefs.contains("postprocessing"))
            store.postProcessing = PostProcessingSettings(enabled = false, punctuation = false)
            prefs.edit().putString("postprocessing", "{\"enabled\":true,\"prompt\":\"Old again\"}").commit()
            assertEquals(PostProcessingSettings(enabled = false, punctuation = false), SettingsStore(context).postProcessing)
            assertFalse(prefs.contains("postprocessing"))
        } finally { compose.activity.deleteSharedPreferences("$prefix-opendictate_settings") }
    }
    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePostprocessing") != "true") return
        compose.waitForIdle(); android.os.SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, "postprocessing-android-$state.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
