package com.opendictate.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.opendictate.app.MainActivity
import org.junit.Rule
import org.junit.Test

class ChatGPTEntryPointTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualSettingsScreenIncludesChatGPTConnection() {
        compose.onNodeWithText("ChatGPT").performScrollTo().assertIsDisplayed()
    }
}
