package com.opendictate.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.AppFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppFilterScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachModeRemembersItsSelectionThroughSearchAndRecreation() {
        val context = compose.activity
        val settings = SettingsStore(context)
        val previous = settings.appFilter
        val packageName = "com.android.settings"
        val label = context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
        settings.appFilter = AppFilter(excludedPackages = setOf(packageName))
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.app_filter_title)).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.app_filter_mode_exclude)).assertIsSelected()
            scrollToText(label).assertIsOn()
            scrollToText(context.getString(R.string.app_filter_mode_allow)).performClick()
            compose.onNodeWithText(context.getString(R.string.app_filter_allow_empty)).assertIsDisplayed()
            scrollToText(label).assertIsOff().performClick()
            scrollToText(label).assertIsOn()
            compose.onNodeWithText(context.getString(R.string.app_filter_allow_empty)).assertDoesNotExist()
            scrollToText(context.getString(R.string.app_filter_mode_exclude)).performClick()
            scrollToText(label).assertIsOn().performClick()
            scrollToText(label).assertIsOff()
            scrollToText(context.getString(R.string.app_filter_mode_allow)).performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.app_filter_mode_allow)).assertIsSelected()
            scrollToText(label).assertIsOn()
            scrollToText(context.getString(R.string.app_filter_search))
                .performTextInput("zz-no-matching-app")
            scrollToText(context.getString(R.string.app_filter_no_matches)).assertIsDisplayed()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(context.getString(R.string.app_filter_clear_search)))
            compose.onNodeWithContentDescription(context.getString(R.string.app_filter_clear_search)).performClick()
            scrollToText(label).assertIsOn()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(context.getString(R.string.app_filter_back)))
            compose.onNodeWithContentDescription(context.getString(R.string.app_filter_back)).performClick()
            compose.onNodeWithText(context.getString(R.string.app_filter_allowed_summary, 1)).assertIsDisplayed()
            assertEquals(setOf(packageName), settings.appFilter.allowedPackages)
            assertEquals(emptySet<String>(), settings.appFilter.excludedPackages)
        } finally {
            settings.appFilter = previous
        }
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
}
