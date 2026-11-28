package com.opendictate.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import org.junit.Rule
import org.junit.Test

class TextModelSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test fun everyTextTaskNamesLunaAndNeedsAKey() {
        val keys = SecureApiKeyStore(compose.activity)
        val key = keys.get()
        val needsKey = compose.activity.getString(R.string.text_model_needs_key)
        try {
            keys.save("instrumentation-placeholder")
            compose.runOnIdle { viewModel.refreshPermissions() }
            compose.onAllNodesWithText("GPT-6 Luna").assertCountEquals(3)
            compose.onNodeWithText(compose.activity.getString(R.string.text_model_commands)).performScrollTo().assertIsDisplayed()
            // GPT-6 Luna is the only text model, so the rows open no menu.
            compose.onAllNodesWithText("GPT-6 Luna").onFirst().assertHasNoClickAction()
            listOf("GPT-6 Luna Fast", "GPT-6.1 Terra", "GPT-6.2 Sol").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            compose.onAllNodesWithText(needsKey).assertCountEquals(0)

            keys.clear()
            compose.runOnIdle { viewModel.refreshPermissions() }
            compose.onAllNodesWithText("GPT-6 Luna").assertCountEquals(0)
            compose.onAllNodesWithText(needsKey).assertCountEquals(3)
            compose.onAllNodesWithText(needsKey).onFirst().assertHasClickAction()
        } finally {
            if (key.isNullOrBlank()) keys.clear() else keys.save(key)
        }
    }
}
