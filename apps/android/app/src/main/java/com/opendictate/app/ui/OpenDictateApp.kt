@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.os.LocaleListCompat
import com.opendictate.app.R
import com.opendictate.app.model.AppLanguage
import com.opendictate.app.model.TranscriptionResponseTimeout
import com.opendictate.app.service.DictationStateBus

@Composable
fun OpenDictateApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val historyState by viewModel.historyState.collectAsState()
    val dictation by DictationStateBus.state.collectAsState()
    val context = LocalContext.current
    val clipboardTranscriptLabel = stringResource(R.string.clipboard_transcript_label)
    val lifecycleOwner = LocalLifecycleOwner.current
    var showKeyDialog by remember { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAppExclusions by rememberSaveable { mutableStateOf(false) }
    var showGestures by rememberSaveable { mutableStateOf(false) }
    val settingsScrollState = rememberScrollState()
    val maximumDisplayRefreshRate = LocalView.current.display
        ?.supportedModes
        ?.maxOfOrNull { it.refreshRate }
        ?: 0f
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissions() }

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }
    LaunchedEffect(showHistory) {
        if (showHistory) viewModel.refreshHistory()
    }
    LaunchedEffect(showAppExclusions) {
        if (showAppExclusions) viewModel.loadInstalledApps()
    }
    BackHandler(enabled = showHistory || showAppExclusions || showGestures) {
        when {
            showGestures -> showGestures = false
            showAppExclusions -> showAppExclusions = false
            else -> showHistory = false
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                viewModel.refreshModelCatalog()
                viewModel.refreshPrompt()
                viewModel.refreshSelectionDictionaryAction()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OpenDictateTheme(theme = state.theme) {
        Scaffold(
            containerColor = SettingsCanvas,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            if (showGestures) {
                GesturesScreen(onBack = { showGestures = false }, modifier = Modifier.padding(padding))
            } else if (showAppExclusions) {
                AppExclusionsScreen(
                    apps = state.installedApps,
                    excludedPackages = state.excludedPackages,
                    loading = state.appsLoading,
                    onBack = { showAppExclusions = false },
                    onExcludedChange = viewModel::setAppExcluded,
                    modifier = Modifier.padding(padding),
                )
            } else if (showHistory) {
                TranscriptHistoryScreen(
                    state = historyState,
                    onBack = { showHistory = false },
                    onQueryChange = viewModel::updateHistoryQuery,
                    onAiSearch = viewModel::runAiHistorySearch,
                    onCopy = { transcript ->
                        val clip = ClipData.newPlainText(
                            clipboardTranscriptLabel,
                            transcript,
                        )
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(clip)
                    },
                    onDelete = viewModel::deleteHistoryItem,
                    modifier = Modifier.padding(padding),
                )
            } else {
                SettingsScreen(
                    state = state,
                    dictation = dictation,
                    scrollState = settingsScrollState,
                    maximumDisplayRefreshRate = maximumDisplayRefreshRate,
                    onHistory = { showHistory = true },
                    onApiKey = { showKeyDialog = true },
                    onMicrophone = {
                        val permissions = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    },
                    onAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onAppExclusions = { showAppExclusions = true },
                    onGestures = { showGestures = true },
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                )
            }
        }
        if (showKeyDialog) {
            ApiKeyDialog(
                hasKey = state.hasApiKey,
                loadKey = viewModel::getApiKey,
                onDismiss = { showKeyDialog = false },
                onSave = {
                    viewModel.saveApiKey(it)
                    showKeyDialog = false
                },
                onDelete = {
                    viewModel.clearApiKey()
                    showKeyDialog = false
                },
            )
        }
    }
}

@Composable
internal fun AppLanguageSwitcher() {
    var expanded by remember { mutableStateOf(false) }
    val selected = AppLanguage.fromLanguageTags(
        AppCompatDelegate.getApplicationLocales().toLanguageTags(),
    )
    Box {
        TextButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = SettingsInset,
                contentColor = SettingsText,
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = stringResource(R.string.language_switcher_description),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                selected.shortLabel(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.title()) },
                    onClick = {
                        expanded = false
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(language.languageTag),
                        )
                    },
                    leadingIcon = {
                        if (language == selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = SettingsText,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun TranscriptionTimeoutDialog(
    currentSeconds: Int?,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    var limitEnabled by rememberSaveable { mutableStateOf(currentSeconds != null) }
    var secondsText by rememberSaveable {
        mutableStateOf((currentSeconds ?: TranscriptionResponseTimeout.DEFAULT_SECONDS).toString())
    }
    val seconds = secondsText.toIntOrNull()?.takeIf(TranscriptionResponseTimeout::isValid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcription_timeout_title)) },
        text = {
            Column {
                Text(stringResource(R.string.transcription_timeout_explanation))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.transcription_timeout_limit),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = limitEnabled, onCheckedChange = { limitEnabled = it })
                }
                if (limitEnabled) {
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.transcription_timeout_seconds)) },
                        supportingText = {
                            Text(stringResource(R.string.transcription_timeout_range))
                        },
                        isError = seconds == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (limitEnabled) seconds else null) },
                enabled = !limitEnabled || seconds != null,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Keeps cursor relocation from moving the parent settings list after the field is focused. */
internal fun Modifier.keepFullBoundsInView(): Modifier =
    this then FullBoundsBringIntoViewElement

private object FullBoundsBringIntoViewElement : ModifierNodeElement<FullBoundsBringIntoViewNode>() {
    override fun create() = FullBoundsBringIntoViewNode()

    override fun update(node: FullBoundsBringIntoViewNode) = Unit

    override fun equals(other: Any?) = other === this

    override fun hashCode() = javaClass.hashCode()
}

private class FullBoundsBringIntoViewNode :
    Modifier.Node(),
    BringIntoViewModifierNode,
    LayoutAwareModifierNode {
    private var size = IntSize.Zero

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) {
        bringIntoView {
            if (size == IntSize.Zero) {
                boundsProvider()
            } else {
                Rect(
                    left = 0f,
                    top = 0f,
                    right = size.width.toFloat(),
                    bottom = size.height.toFloat(),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(
    hasKey: Boolean,
    loadKey: () -> String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var value by remember { mutableStateOf(if (hasKey) loadKey() else "") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (hasKey) R.string.api_key_dialog_replace else R.string.api_key_dialog_connect,
                ),
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.api_key_dialog_description))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim().take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.api_key_title)) },
                    placeholder = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clip = ClipData.newPlainText("OpenAI API key", value).apply {
                                    description.extras = PersistableBundle().apply {
                                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    }
                                }
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(clip)
                            },
                            enabled = value.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.api_key_copy),
                            )
                        }
                    },
                    singleLine = true,
                )
                if (hasKey) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.api_key_dialog_delete), color = SettingsSecondary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(value) },
                enabled = value.length >= 20,
                colors = ButtonDefaults.buttonColors(containerColor = SettingsText, contentColor = SettingsCanvas),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = SettingsSurface,
        titleContentColor = SettingsText,
        textContentColor = SettingsMuted,
    )
}

@Composable
private fun AppLanguage.shortLabel(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system_short)
    AppLanguage.RUSSIAN -> "RU"
    AppLanguage.ENGLISH -> "EN"
}

@Composable
private fun AppLanguage.title(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
}
