package com.opendictate.app.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.R
import com.opendictate.app.chatgpt.ChatGPTSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatGPTSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun transcriptionKeyGatesSignInAndExplanationStaysVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hasKey = mutableStateOf(false); val active = mutableStateOf(false)
        val state = mutableStateOf(ChatGPTSession.State())
        var connects = 0; var disconnects = 0; var cancels = 0
        compose.setContent {
            OpenDictateTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text(context.getString(R.string.settings_text_model), style = MaterialTheme.typography.titleLarge)
                        Text("GPT-6 Luna", Modifier.padding(vertical = 12.dp))
                        ChatGPTSettingsPanel(state.value, hasKey.value, active.value, { connects++ }, { disconnects++ }, { cancels++ })
                    }
                }
            }
        }
        val connection = compose.onNodeWithText("ChatGPT")
        connection.assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.chatgpt_key_required)).assertIsDisplayed()
        capture("locked")
        compose.runOnIdle { hasKey.value = true }
        connection.assertIsEnabled().performClick()
        assertEquals(1, connects)
        capture("ready")
        compose.runOnIdle { active.value = true }
        connection.assertIsNotEnabled()
        compose.runOnIdle { active.value = false; state.value = ChatGPTSession.State(busy = true) }
        connection.assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.postprocessing_cancel)).performClick()
        assertEquals(1, cancels)
        capture("busy")
        compose.runOnIdle { active.value = false; state.value = ChatGPTSession.State(connected = true, email = "synthetic@example.com", failed = true) }
        compose.onNodeWithText(context.getString(R.string.chatgpt_using)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chatgpt_usage)).assertIsDisplayed()
        connection.assertIsOn().assertIsEnabled().performClick()
        assertEquals(1, disconnects)
        compose.runOnIdle { hasKey.value = false }
        connection.assertIsEnabled()
        capture("connected-error")
    }
    private fun capture(state: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDesign") != "true") return
        compose.waitForIdle(); android.os.SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "chatgpt-$state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
