package com.opendictate.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opendictate.app.R
import com.opendictate.app.data.TranscriptHistoryItem
import com.opendictate.app.model.TranscriptionModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun TranscriptHistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAiSearch: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<TranscriptHistoryItem?>(null) }
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.history_copied)

    Box(
        modifier = modifier.fillMaxSize().background(SettingsCanvas),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HistoryTopBar(entryCount = state.entries.size, onBack = onBack)
            HistoryList(
                state = state,
                onCopy = { text ->
                    onCopy(text)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    }
                },
                onDeleteRequest = { pendingDelete = it },
                modifier = Modifier.weight(1f),
            ) {
                Column {
                    if (state.entries.isNotEmpty()) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.history_search_label)) },
                            placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            },
                            trailingIcon = if (state.query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { onQueryChange("") }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.history_clear_search),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { focusManager.clearFocus() },
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        SearchModeButton(
                            text = stringResource(R.string.history_ai_search),
                            selected = state.searchMode == HistorySearchMode.AI,
                            enabled = state.query.isNotBlank() &&
                                state.entries.isNotEmpty() &&
                                !state.isLoading,
                            icon = {
                                if (state.isLoading && state.searchMode == HistorySearchMode.AI) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = LocalContentColor.current,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            onClick = {
                                focusManager.clearFocus()
                                onAiSearch()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = SettingsMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = stringResource(R.string.history_ai_privacy),
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    state.errorMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp),
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(item.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.history_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = SettingsSurface,
            titleContentColor = SettingsText,
            textContentColor = SettingsMuted,
        )
    }
}

@Composable
private fun HistoryTopBar(entryCount: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.history_back),
                tint = SettingsText,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = stringResource(R.string.history_title),
                color = SettingsText,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.history_count, entryCount),
                color = SettingsMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SearchModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = SettingsText, contentColor = SettingsCanvas),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsText),
            content = { content() },
        )
    }
}

@Composable
private fun HistoryList(
    state: HistoryUiState,
    onCopy: (String) -> Unit,
    onDeleteRequest: (TranscriptHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { header() }
        when {
            state.isLoading -> item {
                Box(Modifier.fillParentMaxHeight(0.75f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SettingsText)
                }
            }
            state.entries.isEmpty() -> item {
                HistoryEmptyState(
                    title = stringResource(R.string.history_empty_title),
                    message = stringResource(R.string.history_empty_message),
                    modifier = Modifier.fillParentMaxHeight(0.75f),
                )
            }
            state.visibleEntries.isEmpty() -> item {
                HistoryEmptyState(
                    title = stringResource(R.string.history_no_results_title),
                    message = stringResource(R.string.history_no_results_message),
                    modifier = Modifier.fillParentMaxHeight(0.75f),
                )
            }
            else -> items(state.visibleEntries, key = TranscriptHistoryItem::id) { item ->
                HistoryItem(item, onCopy = { onCopy(item.text) }, onDeleteRequest = { onDeleteRequest(item) })
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(58.dp).background(SettingsInset, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Article,
                contentDescription = null,
                tint = SettingsText,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = SettingsText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, color = SettingsMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HistoryItem(
    item: TranscriptHistoryItem,
    onCopy: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val copyAction = stringResource(R.string.history_copy_item)
    val formattedDate = remember(item.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(item.createdAtEpochMillis))
    }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = SettingsSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formattedDate,
                    color = SettingsMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                HistoryTimings(item)
                Spacer(Modifier.height(7.dp))
                Text(
                    text = item.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = copyAction, onClick = onCopy),
                    color = SettingsText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.history_delete_item),
                    tint = SettingsSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTimings(item: TranscriptHistoryItem) {
    val locale = LocalConfiguration.current.locales[0]
    val labels = if (item.mode == TranscriptionModel.LIVE) {
        listOf("Live" to stringResource(R.string.history_live_description))
    } else buildList {
        item.transcriptionMillis?.takeIf { it >= 0 }?.let {
            val seconds = formatHistorySeconds(it, locale)
            add(stringResource(R.string.history_transcription_time, seconds) to
                stringResource(R.string.history_transcription_time_description, seconds))
        }
        item.postProcessingMillis?.takeIf { it >= 0 }?.let {
            val seconds = formatHistorySeconds(it, locale)
            add(stringResource(R.string.history_postprocessing_time, seconds) to
                stringResource(R.string.history_postprocessing_time_description, seconds))
        }
    }
    if (labels.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEach { (label, description) ->
                Text(label, color = SettingsMuted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { contentDescription = description })
            }
        }
    }
}
