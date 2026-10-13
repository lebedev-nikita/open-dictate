package com.opendictate.app.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import org.junit.Rule
import org.junit.Test

class DictationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bothModesOmitPunctuationCorrectionAfterUpgradingWithItEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val previousModel = settings.model
        val prefs = context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        val legacyKey = "accurate_punctuation_enabled"
        val previousEnabled = if (prefs.contains(legacyKey)) prefs.getBoolean(legacyKey, false) else null
        prefs.edit().putBoolean(legacyKey, true).commit()
        try {
            compose.activityRule.scenario.recreate()
            listOf(R.string.settings_mode_live, R.string.settings_mode_accurate).forEach { mode ->
                compose.onNodeWithText(context.getString(mode)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(R.string.settings_recognition_model)).assertIsDisplayed()
                listOf("Correct punctuation", "Исправлять пунктуацию").forEach { title ->
                    compose.onNodeWithText(title).assertDoesNotExist()
                }
                compose.onNodeWithText(context.getString(R.string.dictation_languages_title))
                    .performScrollTo().assertIsDisplayed()
            }
        } finally {
            settings.model = previousModel
            prefs.edit().apply {
                if (previousEnabled == null) remove(legacyKey) else putBoolean(legacyKey, previousEnabled)
            }.commit()
        }
    }
}
