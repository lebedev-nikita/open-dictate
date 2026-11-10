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
        // The sheet is lazy: scroll its list so rows below the editor are composed before matching them.
        fun sheet(matcher: SemanticsMatcher) = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        fun addRule() {
            // An open keyboard brings the focused field back into view and can scroll the button away.
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            sheet(hasText(label(R.string.replacements_add)) and hasClickAction())
            compose.onNode(hasText(label(R.string.replacements_add)) and hasClickAction()).performClick()
        }
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
        addRule()
        sheet(hasContentDescription(context.getString(R.string.replacements_toggle_rule, "open dictate")))
        compose.onNode(hasText("OpenDictate") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_toggle_rule, "open dictate")).performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.replacements_summary, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.replacements_title)).performClick()
        sheet(hasContentDescription(context.getString(R.string.replacements_edit_rule, "open dictate")))
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_edit_rule, "open dictate")).performClick()
        compose.onNodeWithText(label(R.string.replacements_target)).performScrollTo().performTextReplacement("OpenDictate App")
        sheet(hasText(label(R.string.action_save)) and hasClickAction())
        compose.onNodeWithText(label(R.string.action_save)).performClick()
        sheet(hasText("OpenDictate App"))
        compose.onNodeWithText("OpenDictate App", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_toggle_rule, "open dictate")).performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        // Keep evidence of the actual Compose surface before deleting the disposable rule.
        compose.onNode(hasText(label(R.string.replacements_title)) and hasAnyAncestor(isDialog())).performScrollTo()
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "replacement-phone.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        sheet(hasContentDescription(context.getString(R.string.replacements_delete_rule, "open dictate")))
        compose.onNodeWithContentDescription(context.getString(R.string.replacements_delete_rule, "open dictate")).performClick()
        sheet(hasText(label(R.string.replacements_empty)))
        compose.onNodeWithText(label(R.string.replacements_empty)).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.replacements_summary, 0)).performScrollTo().assertIsDisplayed()
    }

    @Test fun patternRuleRejectsInvalidPatternAndListsItsOptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun label(id: Int) = context.getString(id)
        fun sheet(matcher: SemanticsMatcher) = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        fun addRule() {
            // An open keyboard brings the focused field back into view and can scroll the button away.
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            sheet(hasText(label(R.string.replacements_add)) and hasClickAction())
            compose.onNode(hasText(label(R.string.replacements_add)) and hasClickAction()).performClick()
        }
        val store = (context.applicationContext as OpenDictateApplication).replacementStore
        compose.runOnIdle { store.state.value.document.rules.forEach(store::delete); store.setEnabled(true) }
        compose.onNodeWithText(label(R.string.replacements_title)).performScrollTo().performClick()
        sheet(hasText(label(R.string.replacements_regex)))
        compose.onNodeWithText(label(R.string.replacements_regex)).performClick()
        compose.onNodeWithText(label(R.string.replacements_pattern)).performTextInput("(a)")
        compose.onNodeWithText(label(R.string.replacements_target_pattern)).performTextInput("$2")
        addRule()
        sheet(hasText(label(R.string.replacements_invalid_pattern)))
        compose.onNodeWithText(label(R.string.replacements_invalid_pattern)).assertIsDisplayed()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        capture(context, "replacement-pattern-error")
        compose.onNodeWithText("$2").performTextReplacement("$1 ₽")
        compose.onNodeWithText("(a)").performTextReplacement("(\\d+) ?руб\\w*")
        addRule()
        compose.runOnIdle { org.junit.Assert.assertEquals("250 ₽", store.engine().apply("250 рублей")) }
        compose.onNodeWithText(label(R.string.replacements_regex)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.replacements_match_case)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.replacements_pattern)).performTextInput("ё")
        compose.onNodeWithText(label(R.string.replacements_target_pattern)).performTextInput("е")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        capture(context, "replacement-pattern-editor")
        addRule()
        compose.runOnIdle { org.junit.Assert.assertEquals("Ёж все", store.engine().apply("Ёж всё")) }
        sheet(hasText("(\\d+) ?руб\\w*"))
        compose.onNodeWithText("(\\d+) ?руб\\w*", useUnmergedTree = true).assertIsDisplayed()
        // Both rules are patterns; only the second matches case.
        sheet(hasContentDescription(label(R.string.replacements_match_case)))
        compose.onAllNodesWithContentDescription(label(R.string.replacements_regex), useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(label(R.string.replacements_match_case), useUnmergedTree = true).assertCountEquals(1)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        capture(context, "replacement-pattern-list")
        compose.runOnIdle { store.state.value.document.rules.forEach(store::delete) }
    }
    private fun capture(context: android.content.Context, name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val locale = context.resources.configuration.locales[0].language
        File(context.getExternalFilesDir(null), "$name-$locale.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
