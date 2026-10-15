package com.opendictate.app.ui

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun settingsSheetShowsOnlyGesturesAndSurvivesRecreation() {
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.gestures_title)).performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        val titleStarts = listOf(
            R.string.gestures_menu_action,
            R.string.gestures_cancel_action,
            R.string.gestures_hide_action,
        ).map { title ->
            val node = compose.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode()
            node.boundsInRoot.left
        }
        titleStarts.drop(1).forEach { assertEquals(titleStarts.first(), it, 0.5f) }
        listOf(R.string.gestures_hold_hint, R.string.gestures_cancel_left_hint,
            R.string.gestures_hide_right_hint, R.string.gestures_keyboard_recovery).forEach { hint ->
            compose.onNodeWithText(context.getString(hint)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.settings_done)).assertDoesNotExist()
        listOf(
            R.string.overlay_last_dictation,
            R.string.overlay_paste_last,
            R.string.overlay_edit_text,
            R.string.overlay_add_to_dictionary,
        ).forEach { title ->
            compose.onNodeWithText(context.getString(title)).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.gestures_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.gestures_title)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.gestures_close)).assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.dictation_button_title)).assertIsDisplayed()
    }
}
