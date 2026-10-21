package com.opendictate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.R
import com.opendictate.app.model.PostProcessingSettings
import com.opendictate.app.model.ValueKind
import com.opendictate.app.model.ValueFormatting
import com.opendictate.app.model.ValueFormatRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PostProcessingLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun optionLabelsShareAnInsetAndTypography() {
        val settings = mutableStateOf(PostProcessingSettings(enabled = false))
        compose.setContent {
            OpenDictateTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(20.dp)) { PostProcessingSettingsPanel(settings.value, setOf("en", "ru"), { settings.value = it }) }
            } }
        }
        val defaultToggle = compose.onNodeWithText(label(R.string.postprocessing_default_enabled), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val punctuation = compose.onNodeWithText(label(R.string.postprocessing_punctuation), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val values = compose.onNodeWithText(label(R.string.postprocessing_values), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Default choice and rules must share the same leading edge", defaultToggle.left, punctuation.left, 1f)
        assertEquals("Option labels must share the same leading edge", punctuation.left, values.left, 1f)
        fun textStyle(id: Int): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label(id), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        val punctuationStyle = textStyle(R.string.postprocessing_punctuation)
        val valuesStyle = textStyle(R.string.postprocessing_values)
        val defaultStyle = textStyle(R.string.postprocessing_default_enabled)
        assertEquals(defaultStyle.fontSize, punctuationStyle.fontSize)
        assertEquals(defaultStyle.fontWeight, punctuationStyle.fontWeight)
        assertEquals(punctuationStyle.fontSize, valuesStyle.fontSize)
        assertEquals(punctuationStyle.fontWeight, valuesStyle.fontWeight)
        capture("postprocessing-default-off")
        val original = settings.value
        compose.onNodeWithContentDescription(label(R.string.postprocessing_default_enabled)).performClick()
        assertEquals(original.copy(enabled = true), settings.value)
        compose.onNodeWithText(label(R.string.postprocessing_punctuation)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.postprocessing_values)).assertIsDisplayed()
        capture("postprocessing-default-on")
    }

    @Test fun keyboardAndFieldChangesKeepThePanelAnchored() {
        val formats = ValueFormatting.systemDefaults(Locale.US)
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, setOf("en", "ru"), {}, {}) } }
        val viewport = compose.onNodeWithTag("valueFormattingViewport")
        val initialBounds = viewport.fetchSemanticsNode().boundsInWindow
        val initialTop = initialBounds.top
        compose.onNodeWithTag("addValueRule").performClick()
        compose.onNodeWithTag("valueRuleExample").performClick().performTextInput("0")
        compose.waitForIdle(); android.os.SystemClock.sleep(500)
        val keyboardBounds = viewport.fetchSemanticsNode().boundsInWindow
        assertTrue("Keyboard must be visible", keyboardBounds.bottom < initialBounds.bottom)
        assertEquals(initialTop, keyboardBounds.top, 1f)
        capture("keyboard-rule")
        androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle()
        assertEquals(initialTop, viewport.fetchSemanticsNode().boundsInWindow.top, 1f)
        compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick()
        compose.onNodeWithTag("addValueRule").performClick()
        compose.onNodeWithTag("valueRuleExample").performClick()
        compose.waitForIdle(); android.os.SystemClock.sleep(500)
        assertEquals(initialTop, viewport.fetchSemanticsNode().boundsInWindow.top, 1f)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }

    @Test fun formattingToggleAndEditorAreIndependentWhenDefaultIsOff() {
        val settings = mutableStateOf(PostProcessingSettings(enabled = false))
        compose.setContent {
            OpenDictateTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                    PostProcessingSettingsPanel(settings.value, setOf("en", "ru"), { settings.value = it })
                }
            } }
        }
        compose.onNodeWithContentDescription(label(R.string.postprocessing_values)).performClick()
        assertTrue(settings.value.formatValues)
        assertFalse(settings.value.enabled)
        assertNotNull(settings.value.formats)
        val formats = settings.value.formats
        compose.onNodeWithTag("valueFormatsSave").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.postprocessing_values)).performClick()
        assertFalse(settings.value.formatValues)
        assertEquals(formats, settings.value.formats)
        val beforeEditing = settings.value
        compose.onNodeWithText(label(R.string.postprocessing_value_rules)).performClick()
        compose.onNodeWithTag("valueFormatsSave").assertIsDisplayed()
        assertEquals(beforeEditing, settings.value)
        compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick()
        assertEquals(beforeEditing, settings.value)
    }

    @Test fun ruleLanguageMenuContainsOnlySelectedDictationLanguagesAndAllLanguages() {
        val formats = ValueFormatting.systemDefaults(Locale.US)
        val selected = mutableStateOf(setOf("ru", "en"))
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, selected.value, {}, {}) } }
        val russian = context.resources.configuration.locales[0].language == "ru"
        fun popup(code: String?) = compose.onNode(hasText(ValueFormatRule(ValueKind.INTEGER, code, "1").languageTitle(russian)) and hasAnyAncestor(isPopup()))
        compose.onNodeWithTag("addValueRule").performClick()
        compose.onNodeWithTag("valueRuleLanguage").performClick()
        popup(null).assertIsDisplayed(); popup("en").assertIsDisplayed(); popup("ru").assertIsDisplayed()
        popup("fr").assertDoesNotExist(); popup("de").assertDoesNotExist()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(3)
        capture("selected-language-menu")
        popup("en").performClick()
        compose.runOnIdle { selected.value = emptySet() }
        compose.onNodeWithTag("valueRuleSave").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.value_rule_choose_language)).assertIsDisplayed()
        compose.onNodeWithTag("valueRuleLanguage").performClick()
        popup(null).assertIsDisplayed(); popup("en").assertDoesNotExist(); popup("ru").assertDoesNotExist()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(1)
        popup(null).performClick()
        compose.onNodeWithTag("valueRuleSave").assertIsEnabled()
    }

    @Test fun removingADictationLanguageDoesNotDeleteItsSavedRuleOrPromoteItToAllLanguages() {
        val rule = ValueFormatRule(ValueKind.INTEGER, "fr", "1 234 567")
        val formats = ValueFormatting.systemDefaults(Locale.US).settingRule(rule)
        var saved: ValueFormatting? = null
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, setOf("en", "ru"), {}, { saved = it }) } }
        compose.onNodeWithText(rule.example).performClick()
        compose.onNodeWithText(label(R.string.value_rule_choose_language)).assertIsDisplayed()
        compose.onNodeWithTag("valueRuleSave").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.postprocessing_cancel)).performClick()
        compose.onNodeWithTag("valueFormatsSave").performClick()
        assertEquals(listOf(rule), saved!!.rules)
    }

    private fun manyRules(): ValueFormatting {
        var formats = ValueFormatting.systemDefaults(Locale.US)
        for (kind in ValueKind.entries) for (language in listOf(null, "en", "ru"))
            formats = formats.settingRule(ValueFormatRule(kind, language, formats.entries.getValue(kind).example))
        return formats
    }

    @Test fun allLanguagesPrecedesAlphabeticalLanguageRules() {
        val formats = manyRules()
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, setOf("en", "ru"), {}, {}) } }
        val russian = context.resources.configuration.locales[0].language == "ru"
        val all = compose.onAllNodesWithText(ValueFormatRule(ValueKind.INTEGER, null, "1").languageTitle(russian))[0].fetchSemanticsNode().boundsInRoot
        val english = compose.onAllNodesWithText(ValueFormatRule(ValueKind.INTEGER, "en", "1").languageTitle(russian))[0].fetchSemanticsNode().boundsInRoot
        val ru = compose.onAllNodesWithText(ValueFormatRule(ValueKind.INTEGER, "ru", "1").languageTitle(russian))[0].fetchSemanticsNode().boundsInRoot
        assertTrue(all.top < english.top); assertTrue(english.top < ru.top)
        capture("sorted-rules")
    }

    @Test fun fastFlingsToBothEndsKeepTheFormattingPanelStill() {
        val formats = manyRules()
        var dismissed = false
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, setOf("en", "ru"), { dismissed = true }, {}) } }
        val title = compose.onNodeWithText(label(R.string.postprocessing_values))
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        val list = compose.onNode(hasScrollAction())
        repeat(4) { index ->
            compose.mainClock.autoAdvance = false
            list.performTouchInput { if (index % 2 == 0) swipeUp(durationMillis = 80) else swipeDown(durationMillis = 80) }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals("The panel moved during a fling", initialTop, title.fetchSemanticsNode().boundsInWindow.top, 1f)
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertFalse(dismissed)
        }
    }

    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLayout") != "true") return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.filesDir, "layout-$state.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun scrollingAtTheStartDoesNotDragTheFormattingPanel() {
        val formats = manyRules()
        var dismissed = false
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, setOf("en", "ru"), { dismissed = true }, {}) } }
        val title = compose.onNodeWithText(label(R.string.postprocessing_values))
        val initialTop = title.fetchSemanticsNode().boundsInWindow.top
        val list = compose.onNode(hasScrollAction())
        compose.mainClock.autoAdvance = false
        list.performTouchInput {
            down(Offset(center.x, height * 0.25f))
            moveBy(Offset(0f, 140f), delayMillis = 300)
        }
        val draggedTop = title.fetchSemanticsNode().boundsInWindow.top
        list.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        assertFalse("Scrolling the values must not dismiss the editor", dismissed)
        assertEquals("Only the list should move, not the panel", initialTop, draggedTop, 1f)
    }
}
