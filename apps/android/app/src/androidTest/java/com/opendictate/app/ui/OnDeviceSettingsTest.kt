package com.opendictate.app.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.OnDeviceAvailability
import com.opendictate.app.model.OnDeviceStatus
import com.opendictate.app.model.TextProcessingModel
import com.opendictate.app.model.TranscriptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Emulators have no AICore, so these tests give the screen a phone's Gemini Nano status. */
class OnDeviceSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun show(availability: OnDeviceAvailability?) = compose.runOnIdle { viewModel.showOnDeviceAvailability(availability) }

    @Test fun onDeviceChoicesAppearOnlyWithGeminiNanoAndStayLocal() {
        val store = SettingsStore(compose.activity)
        val mode = store.model
        val onDeviceText = store.onDeviceTextProcessing
        val textModel = store.textProcessingModel
        try {
            compose.runOnIdle {
                viewModel.selectModel(TranscriptionModel.ACCURATE)
                viewModel.selectTextProcessingModel(TextProcessingModel.ID)
            }
            show(OnDeviceAvailability(OnDeviceStatus.UNAVAILABLE, OnDeviceStatus.UNAVAILABLE))
            compose.onNodeWithText(text(R.string.settings_mode_accurate)).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText(text(R.string.settings_mode_on_device)).assertCountEquals(0)
            compose.onNodeWithText(text(R.string.settings_text_model)).performScrollTo().performClick()
            compose.onAllNodesWithText("Gemini Nano").assertCountEquals(0)
            androidx.test.espresso.Espresso.pressBack()

            show(OnDeviceAvailability(OnDeviceStatus.AVAILABLE, OnDeviceStatus.AVAILABLE))
            compose.onNodeWithText(text(R.string.settings_mode_on_device)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_mode_on_device)).assertIsSelected()
            compose.onNodeWithText(text(R.string.model_on_device_description)).assertIsDisplayed()
            compose.onNodeWithText("Gemini Nano").assertIsDisplayed()
            assertEquals(TranscriptionModel.ON_DEVICE, store.model)
            // Without a key, on-device dictation no longer counts the key as a missing setup step.
            if (!com.opendictate.app.data.SecureApiKeyStore(compose.activity).hasKey()) {
                compose.onNodeWithText(text(R.string.api_key_optional_on_device)).assertExists()
            }
            assertEquals("accurate", store.syncDocument().entries["mode"]?.value)
            capture("mode")

            compose.onNodeWithText(text(R.string.settings_text_model)).performScrollTo().performClick()
            capture("text-menu")
            compose.onAllNodesWithText("Gemini Nano")[1].performClick()
            assertTrue(store.onDeviceTextProcessing)
            assertEquals(TextProcessingModel.ID, store.textProcessingModel)
            assertEquals(TextProcessingModel.ID, store.syncDocument().entries["textProcessingModel"]?.value)
            compose.onNodeWithText(text(R.string.text_model_on_device_hint, "GPT-6 Luna")).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Gemini Nano").assertCountEquals(2)
            capture("text")

            show(OnDeviceAvailability(OnDeviceStatus.DOWNLOADING, OnDeviceStatus.DOWNLOADING))
            compose.onNodeWithText(text(R.string.model_on_device_downloading)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.text_model_on_device_downloading)).assertIsDisplayed()
            capture("downloading")

            // A phone that lost Gemini Nano keeps the chosen mode visible so the user can leave it.
            show(OnDeviceAvailability(OnDeviceStatus.UNAVAILABLE, OnDeviceStatus.UNAVAILABLE))
            compose.onNodeWithText(text(R.string.model_on_device_unavailable)).performScrollTo().assertIsDisplayed()
            capture("unavailable")
            compose.onNodeWithText(text(R.string.settings_mode_live)).performClick()
            assertEquals(TranscriptionModel.LIVE, store.model)
            compose.onAllNodesWithText(text(R.string.settings_mode_on_device)).assertCountEquals(0)

            compose.onNodeWithText(text(R.string.settings_text_model)).performScrollTo().performClick()
            // The row shows Gemini Nano, so the menu holds the only GPT-6 Luna.
            compose.onAllNodesWithText("GPT-6 Luna").onLast().performClick()
            assertFalse(store.onDeviceTextProcessing)
        } finally {
            store.model = mode
            store.onDeviceTextProcessing = onDeviceText
            store.textProcessingModel = textModel
        }
    }

    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDesign") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(300) // Let the popup surface reach the screenshot compositor.
        val language = compose.activity.resources.configuration.locales[0].language
        val screenshot = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "on-device-$language-$state.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
