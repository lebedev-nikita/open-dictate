package com.opendictate.app.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.OnDeviceStatus
import com.opendictate.app.model.TranscriptionModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Emulators lack the phone's recognizer, so these tests give the screen its status. The key and
 * the recognizer are independent: each of the four combinations has its own setup and mode choices.
 */
class OnDeviceSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private val keys get() = SecureApiKeyStore(compose.activity)
    private fun text(id: Int) = compose.activity.getString(id)

    private fun show(hasKey: Boolean, speech: OnDeviceStatus?) {
        if (hasKey) keys.save("instrumentation-placeholder") else keys.clear()
        compose.runOnIdle {
            viewModel.refreshPermissions()
            viewModel.showOnDeviceSpeech(speech)
        }
    }

    @Test fun keyAndRecognizerCombineIndependently() = withSavedSettings { store ->
        compose.runOnIdle { viewModel.selectModel(TranscriptionModel.ACCURATE) }

        show(hasKey = true, speech = OnDeviceStatus.AVAILABLE)
        compose.onNodeWithText(text(R.string.api_key_saved)).assertExists()
        compose.onNodeWithText(text(R.string.settings_mode_on_device)).performScrollTo().assertIsDisplayed()
        capture("key-model")

        show(hasKey = true, speech = OnDeviceStatus.UNAVAILABLE)
        compose.onNodeWithText(text(R.string.api_key_saved)).assertExists()
        compose.onAllNodesWithText(text(R.string.settings_mode_on_device)).assertCountEquals(0)
        capture("key-no-model")

        show(hasKey = false, speech = OnDeviceStatus.UNAVAILABLE)
        compose.onAllNodesWithText(text(R.string.api_key_needed))[0].assertExists()
        compose.onAllNodesWithText(text(R.string.settings_mode_on_device)).assertCountEquals(0)
        capture("no-key-no-model")

        // Without a key the recognizer is the way to dictate, and the key step says so.
        show(hasKey = false, speech = OnDeviceStatus.AVAILABLE)
        compose.onNodeWithText(text(R.string.api_key_needed_or_on_device)).assertExists()
        capture("no-key-model-accurate")
        compose.onNodeWithText(text(R.string.settings_mode_on_device)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_mode_on_device)).assertIsSelected()
        compose.onNodeWithText(text(R.string.model_on_device_description)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.api_key_optional_on_device)).assertExists()
        assertEquals(TranscriptionModel.ON_DEVICE, store.model)
        // The mode stays on this phone; other devices keep the synced Live/Accurate choice.
        assertEquals("accurate", store.syncDocument().entries["mode"]?.value)
        capture("no-key-model")
    }

    @Test fun onDeviceModeExplainsDownloadsAndStaysVisibleWhenLost() = withSavedSettings { store ->
        show(hasKey = false, speech = OnDeviceStatus.AVAILABLE)
        compose.onNodeWithText(text(R.string.settings_mode_on_device)).performScrollTo().performClick()

        show(hasKey = false, speech = OnDeviceStatus.DOWNLOADING)
        compose.onNodeWithText(text(R.string.model_on_device_downloading)).performScrollTo().assertIsDisplayed()

        // A phone that lost the recognizer keeps the chosen mode visible so the user can leave it.
        show(hasKey = false, speech = OnDeviceStatus.UNAVAILABLE)
        compose.onNodeWithText(text(R.string.model_on_device_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_mode_live)).performClick()
        assertEquals(TranscriptionModel.LIVE, store.model)
        compose.onAllNodesWithText(text(R.string.settings_mode_on_device)).assertCountEquals(0)
    }

    private fun withSavedSettings(block: (SettingsStore) -> Unit) {
        val store = SettingsStore(compose.activity)
        val mode = store.model
        val key = keys.get()
        try {
            block(store)
        } finally {
            store.model = mode
            if (key.isNullOrBlank()) keys.clear() else keys.save(key)
        }
    }

    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDesign") != "true") return
        compose.onNodeWithText(text(R.string.settings_dictation)).performScrollTo()
        compose.waitForIdle()
        android.os.SystemClock.sleep(300) // Let the frame reach the screenshot compositor.
        val language = compose.activity.resources.configuration.locales[0].language
        val screenshot = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "on-device-$language-$state.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
