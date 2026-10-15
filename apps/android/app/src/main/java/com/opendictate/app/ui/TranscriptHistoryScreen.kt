package com.opendictate.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendictate.app.R
import com.opendictate.app.data.TranscriptHistoryItem
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            HistoryTopBar(entryCount = state.entries.size, onBack = onBack)
            Column(Modifier.padding(horizontal = 20.dp)) {
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
                                color = SettingsCanvas,
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
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
                state.errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = SettingsSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SettingsSecondary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

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
            )
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
                        containerColor = SettingsSecondary,
                        contentColor = SettingsCanvas,
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
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.history_back),
                tint = SettingsText,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = stringResource(R.string.history_title),
                color = SettingsText,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Text(
                text = stringResource(R.string.history_count, entryCount),
                color = SettingsMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.7.sp,
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
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = SettingsText, contentColor = SettingsCanvas),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
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
) {
    when {
        state.isLoading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SettingsText)
            }
        }
        state.entries.isEmpty() -> {
            HistoryEmptyState(
                title = stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_message),
                modifier = modifier,
            )
        }
        state.visibleEntries.isEmpty() -> {
            HistoryEmptyState(
                title = stringResource(R.string.history_no_results_title),
                message = stringResource(R.string.history_no_results_message),
                modifier = modifier,
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visibleEntries, key = TranscriptHistoryItem::id) { item ->
                    HistoryItem(
                        item = item,
                        onCopy = { onCopy(item.text) },
                        onDeleteRequest = { onDeleteRequest(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 36.dp),
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
        Text(title, color = SettingsText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(message, color = SettingsMuted, fontSize = 13.sp, lineHeight = 19.sp)
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
                    color = SettingsText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = item.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = copyAction, onClick = onCopy),
                    color = SettingsText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
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
