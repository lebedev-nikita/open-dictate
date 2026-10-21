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
        val original = store.postProcessing; val originalMode = store.model; val originalLanguages = store.languages
        val prefs = compose.activity.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        val journal = prefs.getString("sync_document", null)
        prefs.edit().remove("sync_document").commit()
        try {
            compose.runOnIdle {
                val viewModel = androidx.lifecycle.ViewModelProvider(compose.activity)[MainViewModel::class.java]
                viewModel.savePostProcessing(PostProcessingSettings())
                for (language in DictationLanguage.entries) {
                    if ((language in store.languages) != (language == DictationLanguage.ENGLISH || language == DictationLanguage.RUSSIAN)) viewModel.toggleLanguage(language)
                }
            }
            action(R.string.settings_mode_live)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).assertDoesNotExist()
            action(R.string.settings_mode_accurate)
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().assertIsOff()
            compose.onNodeWithText(label(R.string.postprocessing_punctuation)).assertExists()
            compose.onNodeWithText(label(R.string.postprocessing_values)).assertExists()
            assertFalse(store.postProcessing.enabled); assertTrue(store.postProcessing.punctuation)
            compose.onNodeWithText("Post-processing prompt").assertDoesNotExist()
            action(R.string.postprocessing_values)
            compose.onNodeWithTag("addValueRule").performClick()
            val russian = compose.activity.resources.configuration.locales[0].language == "ru"
            compose.onNodeWithText(ValueKind.INTEGER.title(russian)).performClick()
            compose.onNodeWithText(ValueKind.DATE.title(russian)).performClick()
            compose.onNodeWithTag("valueRuleSource").assertTextEquals(ValueKind.DATE.source(russian))
            compose.onNodeWithText(label(R.string.value_rule_all_languages)).performClick()
            compose.onNode(hasText(ValueFormatRule(ValueKind.DATE, "en", "1").languageTitle(russian)) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
            compose.onNodeWithTag("valueRuleExample").performTextReplacement("")
            compose.onNodeWithTag("valueRuleSave").assertIsNotEnabled()
            compose.onNodeWithTag("valueRuleExample").performTextReplacement("2026-03-05")
            assertNull(store.postProcessing.formats)
            closeKeyboard()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("valueRuleExample").assertTextContains("2026-03-05")
            capture("rule-editor")
            compose.onNodeWithTag("valueRuleSave").performClick()
            capture("rules")
            compose.onNodeWithTag("valueFormatsSave").performClick(); compose.waitForIdle()
            assertTrue(store.postProcessing.formatValues)
            assertEquals(listOf(ValueFormatRule(ValueKind.DATE, "en", "2026-03-05")), store.postProcessing.formats!!.rules)
            action(R.string.postprocessing_values)
            compose.onNodeWithText("2026-03-05").performClick()
            compose.onNodeWithTag("valueRuleExample").performTextReplacement("03/05/2026")
            closeKeyboard(); compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick()
            compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick(); compose.waitForIdle()
            assertEquals("2026-03-05", store.postProcessing.formats!!.rules.single().example)
            action(R.string.postprocessing_values)
            compose.onNodeWithText(label(R.string.value_formats_reset)).performClick()
            compose.onNodeWithText("2026-03-05").assertDoesNotExist()
            compose.onNodeWithTag("valueFormatsSave").performClick(); compose.waitForIdle()
            assertTrue(store.postProcessing.formats!!.rules.isEmpty())
            action(R.string.postprocessing_reset_settings)
            assertEquals(PostProcessingSettings(enabled = false), store.postProcessing)
            val rules = store.postProcessing
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().performClick()
            assertEquals(rules.copy(enabled = true), store.postProcessing)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performScrollTo().assertIsOn()
            compose.onNodeWithContentDescription(label(R.string.postprocessing_title)).performClick()
            assertEquals(rules, store.postProcessing)
            compose.onNodeWithText(label(R.string.postprocessing_values)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.postprocessing_punctuation)).performScrollTo().performClick()
            assertEquals(rules.copy(punctuation = false), store.postProcessing)
        } finally {
            store.postProcessing = original; store.model = originalMode; store.languages = originalLanguages
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
            val remote = SettingsSyncDocument(mapOf("postprocessingRules" to SettingsSyncEntry(remoteSettings.rulesJson(), 42, "remote")))
            val replacements = ReplacementStore(context)
            store.mergeSyncDocument(remote, replacements)
            assertEquals(remoteSettings.formats, store.postProcessing.formats); assertFalse(store.postProcessing.enabled)
            val dictionary = store.prompt
            val invalid = SettingsSyncDocument(mapOf(
                "postprocessingRules" to SettingsSyncEntry("{\"schemaVersion\":1,\"enabled\":true,\"punctuation\":true,\"valuesEnabled\":true,\"formats\":{}}", 43, "remote"),
                "dictionary" to SettingsSyncEntry("Must not apply", 43, "remote")))
            assertTrue(runCatching { store.mergeSyncDocument(invalid, replacements) }.isFailure)
            assertEquals(remoteSettings.formats, store.postProcessing.formats); assertFalse(store.postProcessing.enabled); assertEquals(dictionary, store.prompt)
            val encoded = store.syncDocument().entries.getValue("postprocessingRules").value!!
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
