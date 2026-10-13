package com.opendictate.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReplacementSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun addEditDisableAndDeleteReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun label(id: Int) = context.getString(id)
        // The debug app is disposable; test data is cleared before this UI scenario.
        compose.runOnIdle {
            val store = (context.applicationContext as OpenDictateApplication).replacementStore
            store.state.value.document.rules.forEach(store::delete)
            store.setEnabled(true)
        }
        compose.onNodeWithText(context.getString(R.string.replacements_summary, 0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.replacements_title)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.replacements_source)).performTextInput("open dictate")
        compose.onNodeWithText(label(R.string.replacements_target)).performTextInput("OpenDictate")
        compose.onNode(hasText(label(R.string.replacements_add)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("OpenDictate") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_toggle_rule, "open dictate")).performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.replacements_summary, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.replacements_title)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_edit_rule, "open dictate")).performClick()
        compose.onNodeWithText(label(R.string.replacements_target)).performScrollTo().performTextReplacement("OpenDictate App")
        compose.onNodeWithText(label(R.string.action_save)).performScrollTo().performClick()
        compose.onNodeWithText("OpenDictate App", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_toggle_rule, "open dictate")).performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        // Keep evidence of the actual Compose surface before deleting the disposable rule.
        compose.onNode(hasText(label(R.string.replacements_title)) and hasAnyAncestor(isDialog())).performScrollTo()
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "replacement-phone.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_delete_rule, "open dictate")).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.replacements_empty)).performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.replacements_summary, 0)).performScrollTo().assertIsDisplayed()
    }
}
