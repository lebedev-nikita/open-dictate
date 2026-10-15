package com.opendictate.app.ui

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DictationButtonSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun legacyCenterPositionSelectsRightAfterRecreation() {
        val context = compose.activity
        val settings = SettingsStore(context)
        val original = settings.dictationButtonAppearance
        val prefs = context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("dictation_button_position", "CENTER")
                .putInt("dictation_button_diameter_dp", 72).commit()
            reloadSeededPreferences()

            assertEquals(DictationButtonAppearance(72, DictationButtonPosition.RIGHT), settings.dictationButtonAppearance)
            openAppearance()
            assertPosition(R.string.dictation_button_right)
            assertPreview(DictationButtonAppearance(72, DictationButtonPosition.RIGHT))
            listOf("Center", "По центру").forEach { label ->
                compose.onNodeWithText(label).assertDoesNotExist()
            }
        } finally {
            settings.dictationButtonAppearance = original
        }
    }

    @Test fun sheetSavesImmediatelyRestoresAfterRecreationAndUpdatesSummaryOnClose() {
        val context = compose.activity
        val settings = SettingsStore(context)
        val original = settings.dictationButtonAppearance
        try {
            settings.dictationButtonAppearance = DictationButtonAppearance()
            reloadSeededPreferences()
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_size)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.dictation_button_appearance)).performScrollTo()
            capture("button-appearance-settings")
            openAppearance()
            capture("button-appearance-sheet")
            setSize(96f)
            choosePosition(R.string.dictation_button_left)
            assertEquals(DictationButtonAppearance(96, DictationButtonPosition.LEFT), settings.dictationButtonAppearance)
            assertPreview(settings.dictationButtonAppearance)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.dictation_button_appearance_title)).assertIsDisplayed()
            assertPosition(R.string.dictation_button_left)
            assertPreview(settings.dictationButtonAppearance)
            compose.onNodeWithText(context.getString(R.string.settings_done)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_size)).assertDoesNotExist()
            assertSummary(settings.dictationButtonAppearance)

            openAppearance()
            choosePosition(R.string.dictation_button_right)
            setSize(24f)
            assertPreview(settings.dictationButtonAppearance)
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_appearance_close)).performClick()
            assertSummary(DictationButtonAppearance(24, DictationButtonPosition.RIGHT))
            // System Back dismisses without undoing autosaved changes.
            openAppearance()
            choosePosition(R.string.dictation_button_left)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
            assertSummary(DictationButtonAppearance(24, DictationButtonPosition.LEFT))
            compose.onNodeWithText(context.getString(R.string.gestures_title)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.gestures_cancel_right_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.gestures_dismiss_left_title)).assertIsDisplayed()
        } finally {
            settings.dictationButtonAppearance = original
        }
    }

    @Test fun resetRestoresSizeAndPositionAndPersistsAfterReopening() {
        val context = compose.activity
        val settings = SettingsStore(context)
        val original = settings.dictationButtonAppearance
        try {
            settings.dictationButtonAppearance = DictationButtonAppearance(96, DictationButtonPosition.LEFT)
            reloadSeededPreferences()
            openAppearance()
            compose.onNodeWithText(context.getString(R.string.dictation_button_reset))
                .performScrollTo().assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(DictationButtonAppearance(), settings.dictationButtonAppearance)
            assertPreview(DictationButtonAppearance())
            assertPosition(R.string.dictation_button_right)
            compose.onNodeWithText(context.getString(R.string.dictation_button_reset)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.settings_done)).performScrollTo().performClick()
            openAppearance()
            assertPreview(DictationButtonAppearance())
            assertPosition(R.string.dictation_button_right)
            compose.onNodeWithText(context.getString(R.string.dictation_button_reset)).performScrollTo().assertIsNotEnabled()
        } finally {
            settings.dictationButtonAppearance = original
        }
    }

    private fun reloadSeededPreferences() {
        // recreate() preserves ViewModels; a seeded preference needs a fresh settings owner.
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        compose.activityRule.scenario.recreate()
    }

    private fun openAppearance() {
        compose.onNodeWithText(compose.activity.getString(R.string.dictation_button_appearance))
            .performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dictation_button_appearance_title)).assertIsDisplayed()
    }

    private fun setSize(size: Float) {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dictation_button_size))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(size) }
        compose.waitForIdle()
    }

    private fun choosePosition(label: Int) {
        compose.onNodeWithText(compose.activity.getString(label)).performScrollTo().performClick()
        compose.waitForIdle()
        assertPosition(label)
    }

    private fun assertPosition(label: Int) {
        compose.onNodeWithText(compose.activity.getString(label)).performScrollTo().assertIsSelected()
    }

    private fun assertPreview(appearance: DictationButtonAppearance) {
        val pixels = compose.activity.getString(R.string.dictation_button_pixels,
            appearance.diameterPx(compose.activity.resources.displayMetrics.density))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dictation_button_preview))
            .performScrollTo().assertIsDisplayed().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, pixels))
        compose.onNodeWithText(pixels).assertIsDisplayed()
    }

    private fun assertSummary(appearance: DictationButtonAppearance) {
        val context = compose.activity
        val summary = context.getString(R.string.dictation_button_appearance_summary,
            context.getString(R.string.dictation_button_pixels,
                appearance.diameterPx(context.resources.displayMetrics.density)),
            context.getString(appearance.position.labelResource()))
        compose.onNodeWithText(summary).performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureButtonAppearance") != "true") return
        compose.waitForIdle()
        // Allow the native sheet entrance to finish before recording visual evidence.
        android.os.SystemClock.sleep(500)
        val screenshot = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.FileOutputStream(java.io.File(compose.activity.cacheDir, "$name.png")).use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
