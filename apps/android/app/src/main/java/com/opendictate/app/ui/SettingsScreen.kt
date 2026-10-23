@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.opendictate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsAccessibility
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendictate.app.BuildConfig
import com.opendictate.app.R
import com.opendictate.app.model.AppFilterMode
import com.opendictate.app.data.MAX_DICTIONARY_LENGTH
import com.opendictate.app.data.normalizeDictionaryTerms
import com.opendictate.app.model.DictationLanguage
import com.opendictate.app.model.AppTheme
import com.opendictate.app.model.ModelCatalog
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.service.DictationPhase
import com.opendictate.app.service.DictationState

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    dictation: DictationState,
    scrollState: ScrollState,
    maximumDisplayRefreshRate: Float,
    onHistory: () -> Unit,
    onErrorHistory: () -> Unit = {},
    onApiKey: () -> Unit,
    onMicrophone: () -> Unit,
    onAccessibility: () -> Unit,
    onAppFilter: () -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    var replacementsOpen by rememberSaveable { mutableStateOf(false) }
    var dictionaryOpen by rememberSaveable { mutableStateOf(false) }
    var languagesOpen by rememberSaveable { mutableStateOf(false) }
    var timeoutOpen by rememberSaveable { mutableStateOf(false) }
    var gesturesOpen by rememberSaveable { mutableStateOf(false) }
    var buttonAppearanceOpen by rememberSaveable { mutableStateOf(false) }
    val replacements by viewModel.replacements.state.collectAsStateWithLifecycle()
    val syncState by viewModel.driveSync.state.collectAsStateWithLifecycle()
    val ready = state.hasApiKey && state.microphoneGranted && state.accessibilityEnabled
    val missingSteps = listOf(state.hasApiKey, state.microphoneGranted, state.accessibilityEnabled).count { !it }
    val colors = MaterialTheme.colorScheme

    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_mic_chatgpt_24), null, Modifier.size(24.dp), tint = SettingsText)
                Spacer(Modifier.width(10.dp))
                Text("OpenDictate", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                    role = Role.Button,
                    onClick = onHistory,
                    onLongClickLabel = stringResource(R.string.error_history_open),
                    onLongClick = onErrorHistory,
                ), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Article, stringResource(R.string.history_open), tint = SettingsMuted)
                }
                AppThemeSwitcher(state.theme, viewModel::selectTheme)
                AppLanguageSwitcher()
            }
            SettingsSyncRefresh(syncState, viewModel.driveSync::requestSync,
                Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState)
                        .preferredFrameRate(if (scrollState.isScrollInProgress) maximumDisplayRefreshRate else 0f)
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = { setupExpanded = !setupExpanded },
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Tune, null, tint = colors.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                val status = when (dictation.phase) {
                                    DictationPhase.CONNECTING -> R.string.settings_connecting
                                    DictationPhase.LISTENING -> R.string.settings_listening
                                    DictationPhase.PROCESSING -> R.string.settings_processing
                                    else -> if (ready) R.string.settings_ready else R.string.settings_setup
                                }
                                Text(stringResource(status), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when (dictation.phase) {
                                        DictationPhase.CONNECTING, DictationPhase.LISTENING -> stringResource(R.string.settings_active_hint)
                                        DictationPhase.PROCESSING -> stringResource(R.string.settings_processing_hint)
                                        else -> if (ready) stringResource(R.string.settings_ready_hint)
                                            else stringResource(R.string.settings_setup_steps, missingSteps)
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = SettingsMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(if (setupExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = SettingsMuted)
                        }
                    }
                    AnimatedVisibility(setupExpanded) {
                        Column(Modifier.padding(top = 8.dp)) {
                            SettingsGroup {
                                ActionRow(Icons.Outlined.Key, stringResource(R.string.api_key_title),
                                    stringResource(if (state.hasApiKey) R.string.api_key_saved else R.string.api_key_needed), onApiKey)
                                SettingsDivider()
                                ActionRow(Icons.Outlined.MicNone, stringResource(R.string.microphone_title),
                                    stringResource(if (state.microphoneGranted) R.string.microphone_granted else R.string.microphone_usage), onMicrophone)
                                SettingsDivider()
                                ActionRow(Icons.Outlined.SettingsAccessibility, stringResource(R.string.keyboard_button_title),
                                    stringResource(if (state.accessibilityEnabled) R.string.accessibility_enabled else R.string.accessibility_enable_hint), onAccessibility)
                            }
                        }
                    }

                    SectionTitle(stringResource(R.string.settings_dictation))
                    SettingsGroup {
                        ModeSelector(state.model, viewModel::selectModel)
                        Text(
                            stringResource(if (state.model == TranscriptionModel.LIVE) R.string.model_live_description else R.string.model_accurate_description),
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = SettingsMuted,
                        )
                        SettingsDivider()
                        val live = state.model == TranscriptionModel.LIVE
                        ModelPicker(
                            label = stringResource(R.string.settings_recognition_model),
                            selected = if (live) state.liveModelId else state.accurateModelId,
                            options = if (live) modelOptions(state.modelCatalog.live, ModelCatalog.DEFAULT.live)
                                else modelOptions(state.modelCatalog.accurate, ModelCatalog.DEFAULT.accurate),
                            onSelect = if (live) viewModel::selectLiveModel else viewModel::selectAccurateModel,
                        )
                        SettingsDivider()
                        ModelPicker(
                            label = stringResource(R.string.settings_text_model),
                            selected = com.opendictate.app.model.TextProcessingModel.selection(state.textProcessingModel, state.textProcessingServiceTier),
                            options = com.opendictate.app.model.TextProcessingModel.defaults,
                            onSelect = viewModel::selectTextProcessingModel,
                            displayName = com.opendictate.app.model.TextProcessingModel::title,
                        )
                        SettingsDivider()
                        ActionRow(Icons.Outlined.Language, stringResource(R.string.dictation_languages_title),
                            state.languages.summary(), { languagesOpen = true })
                        SettingsDivider()
                        ActionRow(Icons.Outlined.AutoFixHigh, stringResource(R.string.replacements_title),
                            stringResource(R.string.replacements_summary, replacements.document.rules.size), { replacementsOpen = true })
                        SettingsDivider()
                        ActionRow(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.settings_dictionary),
                            if (state.prompt.isBlank()) stringResource(R.string.settings_dictionary_empty)
                            else stringResource(R.string.settings_dictionary_count, state.prompt.lineSequence().count { it.isNotBlank() }),
                            { dictionaryOpen = true })
                    }

                    val postProcessingState = rememberSaveableStateHolder()
                    if (state.model == TranscriptionModel.ACCURATE) {
                        SectionTitle(stringResource(R.string.postprocessing_title))
                        postProcessingState.SaveableStateProvider("postprocessing") {
                            SettingsGroup { PostProcessingSettingsPanel(state.postProcessing, state.languages.map { it.code }.toSet(), viewModel::savePostProcessing) }
                        }
                    }

                    SectionTitle(stringResource(R.string.dictation_button_title))
                    SettingsGroup {
                        ActionRow(Icons.Outlined.Tune, stringResource(R.string.dictation_button_appearance),
                            stringResource(R.string.dictation_button_appearance_summary,
                                stringResource(R.string.dictation_button_pixels,
                                    state.dictationButtonAppearance.diameterPx(LocalDensity.current.density)),
                                stringResource(state.dictationButtonAppearance.position.labelResource())),
                            { buttonAppearanceOpen = true })
                        SettingsDivider()
                        ActionRow(Icons.Outlined.TouchApp, stringResource(R.string.gestures_title),
                            stringResource(R.string.gestures_summary), { gesturesOpen = true })
                        SettingsDivider()
                        ToggleRow(Icons.Outlined.AutoFixHigh, stringResource(R.string.transformation_model_title),
                            stringResource(R.string.settings_transformation_hint), state.transformationButtonEnabled,
                            viewModel::setTransformationButtonEnabled, enabled = !dictation.isActive)
                        SettingsDivider()
                        ToggleRow(Icons.Outlined.BookmarkAdd, stringResource(R.string.settings_selection_dictionary),
                            stringResource(R.string.dictionary_selection_action_subtitle), state.selectionDictionaryActionEnabled,
                            viewModel::setSelectionDictionaryActionEnabled)
                        SettingsDivider()
                        ActionRow(Icons.Outlined.VisibilityOff, stringResource(R.string.app_filter_title),
                            stringResource(
                                if (state.appFilter.mode == AppFilterMode.ONLY_SELECTED) R.string.app_filter_allowed_summary
                                else R.string.app_filter_excluded_summary,
                                state.appFilter.selectedPackages.size,
                            ), onAppFilter)
                    }

                    SectionTitle(stringResource(R.string.advanced_settings_title))
                    SettingsGroup {
                        ActionRow(Icons.Outlined.Timer, stringResource(R.string.transcription_timeout_title),
                            state.transcriptionResponseTimeoutSeconds?.let { stringResource(R.string.transcription_timeout_value, it) }
                                ?: stringResource(R.string.transcription_timeout_unlimited), { timeoutOpen = true })
                        SettingsDivider()
                        Surface(
                            onClick = { viewModel.refreshModelCatalog(force = true) },
                            enabled = state.hasApiKey && !state.modelsLoading,
                            color = SettingsSurface,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (state.modelsLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Refresh, null, Modifier.size(22.dp), tint = SettingsMuted)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_refresh_models), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(when {
                                        state.modelsLoading -> R.string.models_loading
                                        state.modelsError -> R.string.settings_models_retry
                                        !state.hasApiKey -> R.string.api_key_needed
                                        else -> R.string.settings_models_hint
                                    }), style = MaterialTheme.typography.bodySmall, color = if (state.modelsError) colors.error else SettingsMuted)
                                    if (state.newModelCount > 0 && !state.modelsLoading && !state.modelsError) {
                                        Text(stringResource(R.string.models_new, state.newModelCount), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    SectionTitle(stringResource(R.string.settings_integrations))
                    SettingsGroup {
                        GoogleDriveSyncSettings()
                        SettingsDivider()
                        ChatGPTSettings(viewModel, state.hasApiKey, dictation.isActive)
                    }

                    val testFieldLabel = stringResource(R.string.test_field_label)
                    SectionTitle(testFieldLabel)
                    var testText by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = testText, onValueChange = { testText = it },
                        modifier = Modifier.fillMaxWidth().keepFullBoundsInView().semantics { contentDescription = testFieldLabel },
                        placeholder = { Text(stringResource(R.string.test_field_placeholder)) },
                        minLines = 3, maxLines = 5, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = SettingsSurface, focusedContainerColor = SettingsSurface),
                    )
                    Row(Modifier.padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Lock, null, Modifier.padding(top = 2.dp).size(16.dp), tint = SettingsMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                    }
                    Text("OpenDictate · ${BuildConfig.VERSION_NAME}",
                        Modifier.fillMaxWidth().padding(bottom = 28.dp), style = MaterialTheme.typography.labelSmall, color = SettingsMuted)
                }
            }
        }
    }
    if (gesturesOpen) GesturesSheet(
        onDismiss = { gesturesOpen = false },
        mirrorGestures = state.dictationButtonAppearance.gestureDirection == -1,
    )
    if (buttonAppearanceOpen) DictationButtonAppearanceSheet(
        state.dictationButtonAppearance, viewModel::setDictationButtonAppearance,
    ) { buttonAppearanceOpen = false }
    if (replacementsOpen) ReplacementSheet(viewModel.replacements) { replacementsOpen = false }
    if (languagesOpen) LanguagesSheet(state.languages, viewModel::toggleLanguage, viewModel::useAutomaticLanguageDetection) { languagesOpen = false }
    if (dictionaryOpen) DictionarySheet(state.prompt, viewModel::savePrompt) { dictionaryOpen = false }
    if (timeoutOpen) TranscriptionTimeoutDialog(state.transcriptionResponseTimeoutSeconds,
        onDismiss = { timeoutOpen = false }, onSave = { viewModel.setTranscriptionResponseTimeout(it); timeoutOpen = false })
}

@Composable
internal fun AppThemeSwitcher(theme: AppTheme, onSelect: (AppTheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val themeLabel = stringResource(theme.titleResource())
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { stateDescription = themeLabel }) {
            Icon(theme.icon(), stringResource(R.string.theme_switcher_description), tint = SettingsMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppTheme.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.titleResource())) },
                    onClick = { expanded = false; onSelect(option) },
                    leadingIcon = { Icon(option.icon(), null) },
                    trailingIcon = { if (option == theme) Icon(Icons.Outlined.Check, null) },
                    modifier = Modifier.semantics { selected = option == theme },
                )
            }
        }
    }
}

private fun AppTheme.titleResource(): Int = when (this) {
    AppTheme.SYSTEM -> R.string.theme_system
    AppTheme.LIGHT -> R.string.theme_light
    AppTheme.DARK -> R.string.theme_dark
}

private fun AppTheme.icon(): ImageVector = when (this) {
    AppTheme.SYSTEM -> Icons.Outlined.Contrast
    AppTheme.LIGHT -> Icons.Outlined.LightMode
    AppTheme.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(start = 4.dp, top = 28.dp, bottom = 10.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = SettingsMuted, fontWeight = FontWeight.Medium)
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = SettingsSurface, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
private fun RowCopy(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = SettingsSurface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = SettingsMuted)
            Spacer(Modifier.width(14.dp))
            RowCopy(title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = SettingsMuted)
        }
    }
}

@Composable
internal fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 80.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = SettingsMuted)
        Spacer(Modifier.width(14.dp))
        RowCopy(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ModeSelector(selected: TranscriptionModel, onSelect: (TranscriptionModel) -> Unit) {
    val models = listOf(TranscriptionModel.ACCURATE, TranscriptionModel.LIVE)
    SettingsChoiceRow(
        options = listOf(stringResource(R.string.settings_mode_accurate), stringResource(R.string.settings_mode_live)),
        selectedIndex = models.indexOf(selected),
        onSelect = { onSelect(models[it]) },
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun ModelPicker(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, enabled: Boolean = true, displayName: (String) -> String = { it }) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, enabled = enabled, color = SettingsSurface) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                    Text(displayName(selected), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(20.dp), tint = SettingsMuted)
            }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(text = { Text(displayName(id), fontFamily = FontFamily.Monospace) },
                    onClick = { onSelect(id); expanded = false },
                    leadingIcon = { if (id == selected) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) })
            }
        }
    }
}

@Composable
private fun LanguagesSheet(languages: Set<DictationLanguage>, onToggle: (DictationLanguage) -> Unit, onAutomatic: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.dictation_languages_title), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.dictation_languages_subtitle), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = SettingsMuted)
            LanguageChoice(stringResource(R.string.dictation_languages_automatic), languages.isEmpty(), onAutomatic)
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DictationLanguage.entries.forEach { language ->
                LanguageChoice(language.title(), language in languages) { onToggle(language) }
            }
        }
    }
}

@Composable
private fun LanguageChoice(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() })
        .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Checkbox(checked, onCheckedChange = null)
    }
}

@Composable
private fun DictionarySheet(prompt: String, onPrompt: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(prompt) }
    LaunchedEffect(prompt) {
        if (normalizeDictionaryTerms(value).trim() != prompt) value = prompt
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.settings_dictionary), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.dictionary_supporting), Modifier.padding(top = 8.dp, bottom = 20.dp), color = SettingsMuted)
            OutlinedTextField(value, onValueChange = {
                value = normalizeDictionaryTerms(it).take(MAX_DICTIONARY_LENGTH)
                onPrompt(value)
            }, modifier = Modifier.fillMaxWidth().keepFullBoundsInView(),
                label = { Text(stringResource(R.string.dictionary_label)) },
                placeholder = { Text(stringResource(R.string.dictionary_placeholder)) }, minLines = 5, maxLines = 10,
                shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            Text(stringResource(R.string.settings_saved_automatically), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_done))
            }
        }
    }
}

@Composable
private fun Set<DictationLanguage>.summary(): String = when (size) {
    0 -> stringResource(R.string.dictation_languages_automatic)
    1 -> first().title()
    else -> stringResource(R.string.dictation_languages_summary_count, size)
}

private fun DictationLanguage.title(): String = when (this) {
    DictationLanguage.RUSSIAN -> "Русский"
    DictationLanguage.ENGLISH -> "English"
    DictationLanguage.UKRAINIAN -> "Українська"
    DictationLanguage.GERMAN -> "Deutsch"
    DictationLanguage.FRENCH -> "Français"
    DictationLanguage.SPANISH -> "Español"
    DictationLanguage.ITALIAN -> "Italiano"
    DictationLanguage.PORTUGUESE -> "Português"
    DictationLanguage.POLISH -> "Polski"
    DictationLanguage.TURKISH -> "Türkçe"
    DictationLanguage.CHINESE -> "中文"
    DictationLanguage.JAPANESE -> "日本語"
    DictationLanguage.KOREAN -> "한국어"
    DictationLanguage.ARABIC -> "العربية"
    DictationLanguage.HINDI -> "हिन्दी"
}
