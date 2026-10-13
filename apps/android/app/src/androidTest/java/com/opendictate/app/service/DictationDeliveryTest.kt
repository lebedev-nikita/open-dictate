package com.opendictate.app.service

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.test.DeliveryFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic provider results travel through the real Accessibility service and native editor. */
@RunWith(AndroidJUnit4::class)
class DictationDeliveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun accurateDeliverySpinnerAndCancellationWorkOnAndroid() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val previousServices = shell("settings get secure enabled_accessibility_services").trim()
        val previousAccessibility = shell("settings get secure accessibility_enabled").trim()
        val previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        check(!SecureApiKeyStore(context).hasKey()) { "Run delivery tests in a fresh debug installation" }
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        val existingServices = previousServices.takeUnless { it == "null" || it.isBlank() }?.plus(":").orEmpty()
        shell("settings put secure enabled_accessibility_services ${existingServices}${context.packageName}/com.opendictate.app.service.OpenDictateAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        val settings = SettingsStore(context)
        val previousModel = settings.model
        settings.model = TranscriptionModel.ACCURATE
        // No network request is made: recording is stopped by teardown, not ACTION_STOP.
        SecureApiKeyStore(context).save("instrumentation-placeholder")
        try {
            for (cancel in listOf(false, true)) {
                instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
                ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
                    await { overlay(context.getString(R.string.overlay_start_dictation_with_actions)) != null }
                    assertTrue(overlay(context.getString(R.string.overlay_start_dictation_with_actions))!!
                        .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { DictationStateBus.state.value.phase == DictationPhase.LISTENING }
                    val session = DictationStateBus.state.value
                    instrumentation.runOnMainSync { DictationStateBus.set(session.copy(phase = DictationPhase.PROCESSING)) }
                    await { overlay(context.getString(R.string.overlay_processing_description)) != null }
                    // Two actual overlay frames must differ while the transcription result is pending.
                    val bounds = Rect()
                    overlay(context.getString(R.string.overlay_processing_description))!!.getBoundsInScreen(bounds)
                    val first = spinnerFrame(bounds)
                    SystemClock.sleep(150)
                    val second = spinnerFrame(bounds)
                    assertFalse("Spinner must continue animating", first.sameAs(second))
                    first.recycle(); second.recycle()
                    assertTrue(DictationStateBus.state.value.isActive)
                    if (cancel) {
                        assertTrue(overlay(context.getString(R.string.overlay_processing_description))!!
                            .performAction(R.id.accessibility_action_cancel_dictation))
                        await { DictationStateBus.state.value.phase == DictationPhase.IDLE }
                    }
                    val result = "По идее, у вас ещё реклама должна блокироваться, по крайней мере её часть."
                    instrumentation.runOnMainSync {
                        DictationStateBus.set(session.copy(phase = DictationPhase.COMPLETED, transcript = result))
                    }
                    if (cancel) {
                        instrumentation.waitForIdleSync()
                        activity.onActivity { assertEquals("before after", it.editor.text.toString()) }
                    } else await {
                        var inserted = false
                        activity.onActivity { inserted = it.editor.text.toString() == "before ${result}after" }
                        inserted
                    }
                    if (!cancel) activity.onActivity { assertEquals(7 + result.length, it.editor.selectionStart) }
                    context.stopService(Intent(context, DictationForegroundService::class.java))
                    await { !DictationStateBus.state.value.isActive }
                }
            }
        } finally {
            context.stopService(Intent(context, DictationForegroundService::class.java))
            SecureApiKeyStore(context).clear()
            settings.model = previousModel
            restoreSetting("enabled_accessibility_services", previousServices)
            restoreSetting("accessibility_enabled", previousAccessibility)
            restoreSetting("show_ime_with_hard_keyboard", previousKeyboard)
            instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
        }
    }

    private fun spinnerFrame(bounds: Rect): Bitmap {
        val screenshot = automation.takeScreenshot()
        val frame = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
        screenshot.recycle()
        return frame
    }

    private fun overlay(description: String): AccessibilityNodeInfo? = automation.windows
        .flatMap { window -> window.root?.let { listOf(it) }.orEmpty() }
        .firstNotNullOfOrNull { find(it, description) }

    private fun find(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, description) }?.let { return it }
        }
        return null
    }

    private fun restoreSetting(name: String, value: String) {
        if (value == "null" || value.isBlank()) shell("settings delete secure $name")
        else shell("settings put secure $name '$value'")
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for dictation delivery; windows=${automation.windows.map { it.type }}" }
            SystemClock.sleep(50)
        }
    }
}
