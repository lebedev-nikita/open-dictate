package com.opendictate.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
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

@Composable
internal fun SettingsSyncStatus(state: DriveSyncState, onRefresh: () -> Unit) {
    if (!state.enabled) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(when {
            state.needsAuthorization -> R.string.drive_sync_sign_in_again
            state.needsSourceSelection -> R.string.drive_sync_source_message
            state.busy -> R.string.drive_sync_busy
            state.failed -> R.string.drive_sync_failed
            state.waiting -> R.string.drive_sync_waiting
            state.lastSyncedAt > 0 -> R.string.drive_sync_done
            else -> R.string.drive_sync_waiting
        }), modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.failed || state.needsAuthorization) MaterialTheme.colorScheme.error else SettingsMuted)
        TextButton(onClick = onRefresh,
            enabled = state.canSyncNow) {
            Text(stringResource(R.string.drive_sync_now))
        }
    }
}
