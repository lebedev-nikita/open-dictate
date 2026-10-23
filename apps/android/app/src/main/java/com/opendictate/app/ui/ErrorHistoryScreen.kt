package com.opendictate.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.data.ErrorHistoryEntry
import com.opendictate.app.data.ErrorHistoryStore
import com.opendictate.app.model.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

data class ErrorHistoryUiState(
    val entries: List<ErrorHistoryEntry> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

@Composable
internal fun ErrorHistoryScreen(state: ErrorHistoryUiState, onBack: () -> Unit, onClear: () -> Unit,
                                onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.error_history_copied)
    Box(modifier.fillMaxSize().background(SettingsCanvas), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.history_back), tint = SettingsText)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.error_history_title), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, color = SettingsText)
                    Text(stringResource(R.string.error_history_count, state.entries.size, ErrorHistoryStore.MAX_ENTRIES),
                        style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                }
                IconButton(onClick = { confirmClear = true }, enabled = state.entries.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.error_history_clear))
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.error_history_privacy), style = MaterialTheme.typography.bodySmall,
                        color = SettingsMuted, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (state.failed) item {
                    Text(stringResource(R.string.error_history_storage_error), color = MaterialTheme.colorScheme.error)
                }
                if (state.isLoading) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else if (state.entries.isEmpty() && !state.failed) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.error_history_empty), style = MaterialTheme.typography.titleMedium, color = SettingsText)
                        Text(stringResource(R.string.error_history_empty_hint), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
                    }
                }
                items(state.entries, key = { it.id }) { entry ->
                    ErrorHistoryRow(entry) {
                        onCopy(entry.diagnosticText())
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) scope.launch { snackbar.showSnackbar(copied) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.error_history_clear_title)) },
        text = { Text(stringResource(R.string.error_history_clear_hint)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.error_history_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ErrorHistoryRow(entry: ErrorHistoryEntry, onCopy: () -> Unit) {
    val stage = when (entry.context.stage) {
        ErrorStage.RECORDING -> R.string.error_stage_recording
        ErrorStage.TRANSCRIPTION -> R.string.error_stage_transcription
        ErrorStage.POST_PROCESSING -> R.string.error_stage_postprocessing
        ErrorStage.TRANSFORMATION -> R.string.error_stage_transformation
        ErrorStage.DELIVERY -> R.string.error_stage_delivery
    }
    val route = when (entry.context.route) {
        ErrorRoute.DEVICE -> stringResource(R.string.error_route_device)
        ErrorRoute.OPENAI_API -> "OpenAI API"
        ErrorRoute.CHATGPT -> "ChatGPT"
    }
    val reason = when (entry.failure.reason) {
        ErrorReason.NETWORK -> R.string.error_reason_network
        ErrorReason.TIMEOUT -> R.string.error_reason_timeout
        ErrorReason.HTTP -> R.string.error_reason_http
        ErrorReason.AUTHENTICATION -> R.string.error_reason_authentication
        ErrorReason.CREDENTIAL_STORAGE -> R.string.error_reason_credential_storage
        ErrorReason.PROVIDER -> R.string.error_reason_provider
        ErrorReason.STREAM_INCOMPLETE -> R.string.error_reason_stream_incomplete
        ErrorReason.INVALID_RESPONSE -> R.string.error_reason_invalid_response
        ErrorReason.EMPTY_RESULT -> R.string.error_reason_empty_result
        ErrorReason.WORDS_CHANGED -> R.string.error_reason_words_changed
        ErrorReason.NUMBERS_CHANGED -> R.string.error_reason_numbers_changed
        ErrorReason.SPACING_CHANGED -> R.string.error_reason_spacing_changed
        ErrorReason.VALUE_CHANGED -> R.string.error_reason_value_changed
        ErrorReason.MICROPHONE_PERMISSION -> R.string.error_reason_microphone_permission
        ErrorReason.MISSING_API_KEY -> R.string.error_reason_missing_api_key
        ErrorReason.MISSING_RECORDING -> R.string.error_reason_missing_recording
        ErrorReason.DELIVERY_REJECTED -> R.string.error_reason_delivery_rejected
        ErrorReason.UNKNOWN -> R.string.error_reason_unknown
    }
    Surface(shape = RoundedCornerShape(16.dp), color = SettingsSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(stage), style = MaterialTheme.typography.titleMedium, color = SettingsText)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(entry.createdAtEpochMillis)),
                style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            Text(stringResource(reason), style = MaterialTheme.typography.bodyLarge, color = SettingsText)
            val metadata = buildList {
                add(route)
                entry.context.model?.let {
                    add(if (it == DiagnosticModel.OTHER) stringResource(R.string.error_model_other) else it.id)
                }
                entry.failure.httpStatus?.let { add("HTTP $it") }
                entry.durationMillis?.let { add(stringResource(R.string.error_history_duration, it)) }
            }
            Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.error_history_copy))
            }
        }
    }
}
