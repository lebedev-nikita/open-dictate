package com.opendictate.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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
    Box(modifier.pullToRefresh(isRefreshing = state.busy, state = pullState,
        enabled = state.canSyncNow, onRefresh = onRefresh)) {
        content()
        if (state.enabled && !state.needsAuthorization && !state.needsSourceSelection) {
            PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = state.busy,
                modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
