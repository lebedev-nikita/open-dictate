package com.opendictate.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class GesturesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsGuideCoversGesturesAndMenuAndSurvivesRecreation() {
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.gestures_title)).performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        val titleStarts = listOf(
            R.string.gestures_hold_title,
            R.string.gestures_left_title,
            R.string.gestures_right_title,
        ).map { title ->
            compose.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.left
        }
        titleStarts.drop(1).forEach { assertEquals(titleStarts.first(), it, 0.5f) }
        listOf(
            R.string.overlay_last_dictation,
            R.string.overlay_paste_last,
            R.string.overlay_edit_text,
            R.string.overlay_add_to_dictionary,
        ).forEach { title ->
            compose.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.gestures_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.gestures_title)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.gestures_back)).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.settings_keyboard)).assertIsDisplayed()
    }
}
