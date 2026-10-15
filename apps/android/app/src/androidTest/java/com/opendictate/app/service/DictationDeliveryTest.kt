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
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.DictationButtonPosition
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.model.AppFilter
import com.opendictate.app.model.AppFilterMode
import com.opendictate.app.test.DeliveryFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic provider results travel through the real Accessibility service and native editor. */
@RunWith(AndroidJUnit4::class)
class DictationDeliveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun menuBelowLargeButtonLeavesAVisibleGap() = withAccessibility { settings ->
        instrumentation.runOnMainSync {
            settings.dictationButtonAppearance = DictationButtonAppearance(96)
        }
        ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
            activity.onActivity { it.editor.setText("") }
            await { startButton() != null }
            // Settle the keyboard and button before opening the menu.
            SystemClock.sleep(600)
            assertTrue(startButton()!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            val button = Rect()
            val firstAction = Rect()
            await {
                val close = overlay(context.getString(R.string.overlay_close_menu))
                val menu = overlay(context.getString(R.string.overlay_last_dictation_description))
                close?.getBoundsInScreen(button)
                menu?.getBoundsInScreen(firstAction)
                close != null && menu != null && !button.isEmpty && !firstAction.isEmpty
            }
            if (InstrumentationRegistry.getArguments().getString("captureOverlay") == "true") {
                java.io.FileOutputStream(java.io.File(context.cacheDir, "menu-below-large-button.png")).use {
                    val screenshot = automation.takeScreenshot()
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    screenshot.recycle()
                }
            }
            assertTrue("Fixture must open the menu below the button", firstAction.centerY() > button.centerY())
            val requiredGap = (8 * context.resources.displayMetrics.density).toInt()
            assertTrue("Menu overlaps button: button=$button firstAction=$firstAction requiredGap=$requiredGap",
                firstAction.top - button.bottom >= requiredGap - 1)
        }
    }

    @Test fun customButtonMovesResizesOpensMenuAndCancelsWithMirroredSwipe() = withAccessibility { settings ->
        ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
            val density = context.resources.displayMetrics.density
            for (position in DictationButtonPosition.entries) {
                val appearance = DictationButtonAppearance(96, position)
                instrumentation.runOnMainSync { settings.dictationButtonAppearance = appearance }
                val description = context.getString(if (position == DictationButtonPosition.LEFT)
                    R.string.overlay_start_dictation_with_actions_left else R.string.overlay_start_dictation_with_actions)
                val bounds = Rect()
                await {
                    overlay(description)?.getBoundsInScreen(bounds)
                    val displayWidth = context.resources.displayMetrics.widthPixels
                    bounds.width() == appearance.diameterPx(density) && when (position) {
                        DictationButtonPosition.LEFT -> bounds.left == (14 * density).toInt()
                        DictationButtonPosition.CENTER -> kotlin.math.abs(bounds.centerX() - displayWidth / 2) <= 1
                        DictationButtonPosition.RIGHT -> bounds.right == displayWidth - (14 * density).toInt()
                    }
                }
                assertTrue(overlay(description)!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                await { overlay(context.getString(R.string.overlay_close_menu)) != null }
                assertTrue(overlay(context.getString(R.string.overlay_close_menu))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { overlay(description) != null }
            }
            instrumentation.runOnMainSync {
                settings.dictationButtonAppearance = DictationButtonAppearance(24, DictationButtonPosition.LEFT)
            }
            val description = context.getString(R.string.overlay_start_dictation_with_actions_left)
            val bounds = Rect()
            await {
                overlay(description)?.getBoundsInScreen(bounds)
                bounds.width() == settings.dictationButtonAppearance.touchDiameterPx(density)
            }
            // Resizing near the top margin also animates the vertical anchor.
            SystemClock.sleep(600)
            overlay(description)!!.getBoundsInScreen(bounds)
            // The outward swipe hides the idle button, and reopening the keyboard restores it.
            shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX() - (34 * density).toInt()} ${bounds.centerY()} 600")
            await { !hasOverlay() }
            activity.onActivity {
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(it.editor.windowToken, 0)
            }
            SystemClock.sleep(350)
            activity.onActivity {
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(it.editor, 0)
            }
            await { overlay(description) != null }
            assertTrue(overlay(description)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { DictationStateBus.state.value.phase == DictationPhase.LISTENING }
            val session = DictationStateBus.state.value
            overlay(context.getString(R.string.overlay_active_dictation_description_left))!!.getBoundsInScreen(bounds)
            shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX() + (80 * density).toInt()} ${bounds.centerY()} 600")
            await { DictationStateBus.state.value.phase == DictationPhase.IDLE }
            instrumentation.runOnMainSync { DictationStateBus.set(session.copy(phase = DictationPhase.COMPLETED, transcript = "late synthetic result")) }
            instrumentation.waitForIdleSync()
            activity.onActivity { assertEquals("before after", it.editor.text.toString()) }
        }
    }

    @Test fun accurateDeliverySpinnerAndCancellationWorkOnAndroid() = withAccessibility { _ ->
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
    }

    @Test fun appFilterChangesImmediatelyHideTheOverlayAndKeepTheOtherModeSelection() = withAccessibility { settings ->
        ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
            await { startButton() != null }
            settings.appFilter = AppFilter(excludedPackages = setOf(context.packageName))
            await { !hasOverlay() }
            settings.appFilter = settings.appFilter.copy(mode = AppFilterMode.ONLY_SELECTED)
            await { !hasOverlay() }
            settings.appFilter = settings.appFilter.withPackageSelected(context.packageName, true)
            await { startButton() != null }
            settings.appFilter = settings.appFilter.copy(mode = AppFilterMode.ALL_EXCEPT_SELECTED)
            await { !hasOverlay() }
            settings.appFilter = settings.appFilter.copy(mode = AppFilterMode.ONLY_SELECTED)
            await { startButton() != null }
            activity.onActivity {
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(it.editor.windowToken, 0)
            }
            await { !hasOverlay() }
            activity.onActivity {
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(it.editor, 0)
            }
            await { startButton() != null }
        }
    }

    @Test fun revokedAppsRejectLateLiveAndAccurateResults() = withAccessibility { settings ->
        for (model in TranscriptionModel.entries) {
            settings.appFilter = AppFilter(mode = AppFilterMode.ONLY_SELECTED, allowedPackages = setOf(context.packageName))
            instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
            ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
                val session = captureTargetWithoutNetwork()
                if (model == TranscriptionModel.LIVE) {
                    instrumentation.runOnMainSync {
                        DictationStateBus.set(session.copy(model = model, phase = DictationPhase.LISTENING, transcript = "partial "))
                    }
                    awaitEditor(activity, "before partial after")
                }
                settings.appFilter = settings.appFilter.withPackageSelected(context.packageName, false)
                await { !hasOverlay() }
                instrumentation.runOnMainSync {
                    DictationStateBus.set(session.copy(model = model, phase = DictationPhase.COMPLETED, transcript = "late result "))
                }
                instrumentation.waitForIdleSync()
                activity.onActivity {
                    assertEquals(if (model == TranscriptionModel.LIVE) "before partial after" else "before after", it.editor.text.toString())
                }
                instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
            }
        }
    }

    @Test fun liveCancellationRestoresTextAndIgnoresLateResults() = withAccessibility { settings ->
        settings.appFilter = AppFilter(mode = AppFilterMode.ONLY_SELECTED, allowedPackages = setOf(context.packageName))
        ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
            val session = captureTargetWithoutNetwork().copy(model = TranscriptionModel.LIVE)
            instrumentation.runOnMainSync {
                DictationStateBus.set(session.copy(phase = DictationPhase.PROCESSING, transcript = "partial "))
            }
            awaitEditor(activity, "before partial after")
            await { overlay(context.getString(R.string.overlay_processing_description)) != null }
            assertTrue(overlay(context.getString(R.string.overlay_processing_description))!!
                .performAction(R.id.accessibility_action_cancel_dictation))
            awaitEditor(activity, "before after")
            await { DictationStateBus.state.value.phase == DictationPhase.IDLE }
            instrumentation.runOnMainSync {
                DictationStateBus.set(session.copy(phase = DictationPhase.COMPLETED, transcript = "late result "))
            }
            instrumentation.waitForIdleSync()
            activity.onActivity { assertEquals("before after", it.editor.text.toString()) }
        }
    }

    @Test fun focusChangeToAnotherAppPreventsDelivery() = withAccessibility { settings ->
        settings.appFilter = AppFilter(mode = AppFilterMode.ONLY_SELECTED, allowedPackages = setOf(context.packageName))
        ActivityScenario.launch<DeliveryFixtureActivity>(Intent(context, DeliveryFixtureActivity::class.java)).use { activity ->
            val session = captureTargetWithoutNetwork()
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            await { automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.settings:id/search_action_bar")?.isNotEmpty() == true }
            assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("com.android.settings:id/search_action_bar")
                .first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true }
            val systemEditor = automation.rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            // Settings' focused search editor does not expose ACTION_CLICK on every Android image.
            val systemEditorBounds = Rect()
            systemEditor.getBoundsInScreen(systemEditorBounds)
            shell("input tap ${systemEditorBounds.centerX()} ${systemEditorBounds.centerY()}")
            await { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            await { !hasOverlay() }
            val original = systemEditor.text?.toString().orEmpty()
            instrumentation.runOnMainSync {
                DictationStateBus.set(session.copy(phase = DictationPhase.COMPLETED, transcript = "late result "))
            }
            instrumentation.waitForIdleSync()
            assertTrue(systemEditor.refresh())
            assertEquals(original, systemEditor.text?.toString().orEmpty())
            activity.onActivity { assertEquals("before after", it.editor.text.toString()) }
        }
    }

    /** Capture through the real overlay, then cancel the recorder before injecting provider states. */
    private fun captureTargetWithoutNetwork(): DictationState {
        await { startButton() != null }
        assertTrue(startButton()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { DictationStateBus.state.value.phase == DictationPhase.LISTENING }
        val session = DictationStateBus.state.value
        context.startService(Intent(context, DictationForegroundService::class.java)
            .setAction(DictationForegroundService.ACTION_CANCEL)
            .putExtra(DictationForegroundService.EXTRA_SESSION_ID, session.sessionId))
        await { DictationStateBus.state.value.phase == DictationPhase.IDLE }
        context.stopService(Intent(context, DictationForegroundService::class.java))
        instrumentation.waitForIdleSync()
        return session
    }

    private fun awaitEditor(activity: ActivityScenario<DeliveryFixtureActivity>, expected: String) = await {
        var matches = false
        activity.onActivity { matches = it.editor.text.toString() == expected }
        matches
    }

    private fun startButton() = overlay(context.getString(R.string.overlay_start_dictation_with_actions))

    private fun hasOverlay() = automation.windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
    }

    private fun withAccessibility(block: (SettingsStore) -> Unit) {
        instrumentation.runOnMainSync { DictationStateBus.set(DictationState()) }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
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
        val previousAppearance = settings.dictationButtonAppearance
        settings.dictationButtonAppearance = DictationButtonAppearance()
        val previousFilter = settings.appFilter
        settings.appFilter = AppFilter()
        settings.model = TranscriptionModel.ACCURATE
        // No network request is made: recording is stopped by teardown, not ACTION_STOP.
        SecureApiKeyStore(context).save("instrumentation-placeholder")
        try {
            block(settings)
        } finally {
            context.stopService(Intent(context, DictationForegroundService::class.java))
            SecureApiKeyStore(context).clear()
            settings.model = previousModel
            settings.appFilter = previousFilter
            settings.dictationButtonAppearance = previousAppearance
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
