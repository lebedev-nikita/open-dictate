package com.opendictate.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test fun sliderAndResetUpdatePreviewPixelsAndPersistPositionAcrossRecreation() {
        val context = compose.activity
        val settings = SettingsStore(context)
        val original = settings.dictationButtonAppearance
        try {
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_size))
                .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(96f) }
            compose.onNodeWithText(context.getString(R.string.dictation_button_left)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(DictationButtonAppearance(96, DictationButtonPosition.LEFT), settings.dictationButtonAppearance)
            val pixels = context.getString(R.string.dictation_button_pixels,
                settings.dictationButtonAppearance.diameterPx(context.resources.displayMetrics.density))
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_preview))
                .performScrollTo().assertIsDisplayed().assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, pixels))
            compose.onNodeWithText(pixels).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_reset_size))
                .performScrollTo().assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(DictationButtonAppearance(position = DictationButtonPosition.LEFT), settings.dictationButtonAppearance)
            val defaultPixels = context.getString(R.string.dictation_button_pixels,
                settings.dictationButtonAppearance.diameterPx(context.resources.displayMetrics.density))
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_preview))
                .performScrollTo().assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, defaultPixels))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_reset_size))
                .performScrollTo().assertIsNotEnabled()
            assertEquals(DictationButtonAppearance.DEFAULT_DIAMETER_DP, settings.dictationButtonAppearance.diameterDp)
            compose.onNodeWithText(context.getString(R.string.dictation_button_left)).performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_size))
                .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(24f) }
            compose.waitForIdle()
            assertEquals(24, settings.dictationButtonAppearance.diameterDp)
            val minimumPixels = context.getString(R.string.dictation_button_pixels,
                settings.dictationButtonAppearance.diameterPx(context.resources.displayMetrics.density))
            compose.onNodeWithContentDescription(context.getString(R.string.dictation_button_preview))
                .performScrollTo().assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, minimumPixels))
            compose.onNodeWithText(context.getString(R.string.gestures_title)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.gestures_cancel_right_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.gestures_dismiss_left_title)).assertIsDisplayed()
        } finally {
            settings.dictationButtonAppearance = original
        }
    }
}
