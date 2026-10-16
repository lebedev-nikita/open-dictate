package com.opendictate.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.R
import com.opendictate.app.data.DriveSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsSyncRefreshTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(DriveSyncState(enabled = true))
    private var requests = 0
    private lateinit var scroll: ScrollState
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun showSettings(deferRefresh: Boolean = false) {
        compose.setContent {
            OpenDictateTheme {
                scroll = rememberScrollState()
                val refresh = { requests++; state = state.copy(busy = !deferRefresh, waiting = deferRefresh) }
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsSyncRefresh(state, refresh, Modifier.fillMaxSize().testTag("refresh")) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp)) {
                            Text(context.getString(R.string.settings_dictation))
                            Spacer(Modifier.height(3000.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun pullStartsSyncAndBusyBlocksRepeatedPullUntilRetry() {
        showSettings()
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNode(progress).assertIsDisplayed()
        capture("busy")
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, requests)
            state = state.copy(busy = false, failed = true)
        }
        capture("failed")
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(2, requests)
            state = state.copy(busy = false, failed = false, lastSyncedAt = 42)
        }
        capture("synced")
        compose.onNode(progress).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun startupAndAutomaticSyncDoNotShowRefreshProgress() {
        state = DriveSyncState(enabled = true, busy = true)
        showSettings()
        compose.onNode(progress).assertDoesNotExist()
        capture("startup")
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(0, requests)
            state = state.copy(busy = false)
        }
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun queuedManualRefreshKeepsItsIndicatorUntilSyncCompletes() {
        showSettings(deferRefresh = true)
        compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, requests)
            assertTrue(state.waiting)
        }
        compose.runOnIdle { state = state.copy(busy = true, waiting = false) }
        compose.onNode(progress).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(busy = false) }
        compose.onNode(progress).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun disconnectedAuthorizationAndSourceSelectionDoNotConsumeRefresh() {
        state = DriveSyncState()
        showSettings()
        for (blocked in listOf(DriveSyncState(), DriveSyncState(enabled = true, needsAuthorization = true),
            DriveSyncState(enabled = true, needsSourceSelection = true))) {
            compose.runOnIdle { state = blocked }
            compose.onNodeWithTag("refresh").performTouchInput { swipeDown() }
            compose.runOnIdle { assertEquals(0, requests) }
        }
    }

    @Test fun downwardScrollBelowTopDoesNotStartSync() {
        showSettings()
        compose.onNodeWithTag("refresh").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scroll.value > 0) }
        compose.onNodeWithTag("refresh").performTouchInput {
            swipe(start = center, end = center.copy(y = center.y + 100), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(0, requests) }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureSyncViews") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "sync-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
