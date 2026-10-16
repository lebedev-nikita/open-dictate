package com.opendictate.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.opendictate.app.data.DriveSyncState

@Composable
internal fun SettingsSyncRefresh(
    state: DriveSyncState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    // Only the user's pull owns this indicator; automatic checks stay silent.
    var refreshRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy, state.waiting) {
        if (!state.busy && !state.waiting) refreshRequested = false
    }
    val isRefreshing = refreshRequested && state.busy
    Box(modifier.pullToRefresh(isRefreshing = isRefreshing, state = pullState,
        enabled = state.canSyncNow, onRefresh = {
            refreshRequested = true
            onRefresh()
        })) {
        content()
        if (state.enabled && !state.needsAuthorization && !state.needsSourceSelection) {
            PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
