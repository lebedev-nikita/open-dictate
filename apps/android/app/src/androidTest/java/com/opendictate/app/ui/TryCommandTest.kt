package com.opendictate.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TryCommandTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theKeyboardsSendKeyRunsTheCommandAndClearsTheField() {
        val ran = mutableListOf<String>()
        compose.setContent { OpenDictateTheme { TryCommand { ran += it } } }
        val field = compose.onNode(hasSetTextAction())
        field.performImeAction()
        assertEquals("An empty field runs nothing", emptyList<String>(), ran)
        field.performTextInput("  turn on the kitchen light ")
        field.performImeAction()
        assertEquals(listOf("turn on the kitchen light"), ran)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
