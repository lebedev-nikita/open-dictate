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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PostProcessingLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun optionLabelsShareAnInsetAndTypography() {
        val settings = mutableStateOf(PostProcessingSettings(enabled = true))
        compose.setContent {
            OpenDictateTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(20.dp)) { PostProcessingSettingsPanel(settings.value, { settings.value = it }) }
            } }
        }
        val punctuation = compose.onNodeWithText(label(R.string.postprocessing_punctuation), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val values = compose.onNodeWithText(label(R.string.postprocessing_values), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Option labels must share the same leading edge", punctuation.left, values.left, 1f)
        fun textStyle(id: Int): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label(id), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        val punctuationStyle = textStyle(R.string.postprocessing_punctuation)
        val valuesStyle = textStyle(R.string.postprocessing_values)
        assertEquals(punctuationStyle.fontSize, valuesStyle.fontSize)
        assertEquals(punctuationStyle.fontWeight, valuesStyle.fontWeight)
    }

    @Test fun keyboardAndFieldChangesKeepThePanelAnchored() {
        val formats = ValueFormatting.systemDefaults(Locale.US)
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, {}, {}) } }
        val viewport = compose.onNodeWithTag("valueFormattingViewport")
        val initialBounds = viewport.fetchSemanticsNode().boundsInWindow
        val initialTop = initialBounds.top
        var keyboardBottom: Float? = null
        val russian = context.resources.configuration.locales[0].language == "ru"
        for (kind in listOf(ValueKind.INTEGER, ValueKind.DATE, ValueKind.FRACTION, ValueKind.DECIMAL)) {
            compose.onNode(hasSetTextAction() and hasContentDescription(kind.title(russian)))
                .performScrollTo().performClick().performTextInput("0")
            compose.waitForIdle()
            android.os.SystemClock.sleep(500)
            val bounds = viewport.fetchSemanticsNode().boundsInWindow
            assertTrue("Keyboard must be visible", bounds.bottom < initialBounds.bottom)
            keyboardBottom?.let { assertEquals("Keyboard geometry changed between fields", it, bounds.bottom, 1f) }
            keyboardBottom = bounds.bottom
            capture("keyboard-${kind.name}")
            assertEquals("The panel must stay anchored when focusing $kind", initialTop, viewport.fetchSemanticsNode().boundsInWindow.top, 1f)
            compose.onNode(hasSetTextAction() and hasContentDescription(kind.title(russian))).assertIsDisplayed()
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        assertEquals(initialTop, viewport.fetchSemanticsNode().boundsInWindow.top, 1f)
    }

    @Test fun fastFlingsToBothEndsKeepTheFormattingPanelStill() {
        val formats = ValueFormatting.systemDefaults(Locale.US)
        var dismissed = false
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, { dismissed = true }, {}) } }
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
        val formats = ValueFormatting.systemDefaults(Locale.US)
        var dismissed = false
        compose.setContent { OpenDictateTheme { ValueFormattingSheet(formats, formats, { dismissed = true }, {}) } }
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
