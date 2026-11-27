package com.opendictate.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.model.McpAuth
import com.opendictate.app.model.McpServer
import com.opendictate.app.model.McpTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pass `mcpLocale` (en or ru) and `mcpScreenshot` (a file name prefix) as instrumentation arguments. */
class McpServersScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val arguments = InstrumentationRegistry.getArguments()
    private val shot: String? = arguments.getString("mcpScreenshot")

    @Test fun tryingACommandKeepsRunAboveTheKeyboardAndTheNoteExampleFitsOneLine() {
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(arguments.getString("mcpLocale", "en"))) }
        compose.activityRule.scenario.recreate()
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val server = McpServer(SERVER_ID, "T3 Code", "https://macbook.t3.home.arpa/mcp", McpAuth.BROWSER, trustDeviceCertificates = true)
            .withTools(listOf(McpTool("t3_thread_launch", "Start a thread", "{}")))
        compose.runOnUiThread { model.saveMcpServer(server, null) }
        try {
            compose.onNodeWithText(label(R.string.mcp_servers_title)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performClick()
            compose.waitUntil(10_000) { imeBottom() > 0 }
            compose.onNode(hasSetTextAction()).performTextInput("create a new thread with the text ping")
            // The keyboard slides in and the list follows it.
            SystemClock.sleep(1_000)
            compose.waitForIdle()
            val run = compose.onNodeWithText(label(R.string.mcp_try_run)).fetchSemanticsNode().boundsInWindow
            val visibleBottom = compose.activity.window.decorView.height - imeBottom()
            assertTrue("Run ends at ${run.bottom}, the keyboard starts at $visibleBottom", run.bottom <= visibleBottom)
            compose.onNodeWithText(label(R.string.mcp_try_run)).assertIsEnabled()
            shot?.let { capture("$it-try-keyboard") }

            Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_server_edit, server.name)).performScrollTo().performClick()
            val example = compose.onNodeWithText(label(R.string.mcp_server_hint_example), useUnmergedTree = true)
            example.performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            example.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("The note's example must not wrap", 1, layouts.single().lineCount)
            compose.waitForIdle()
            SystemClock.sleep(500)
            shot?.let { capture("$it-server-sheet") }
        } finally {
            compose.runOnUiThread { model.deleteMcpServer(SERVER_ID) }
        }
    }

    private fun imeBottom(): Int {
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    }

    /** The whole screen, so the keyboard and the sheet's own window are in the picture. */
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SERVER_ID = "mcp-screen-test"
    }
}
