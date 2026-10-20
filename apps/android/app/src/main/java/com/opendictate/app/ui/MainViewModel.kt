package com.opendictate.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.data.TranscriptHistoryItem
import com.opendictate.app.data.fuzzySearch
import com.opendictate.app.model.DictationLanguage
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.AppTheme
import com.opendictate.app.model.AppFilter
import com.opendictate.app.model.AppFilterMode
import com.opendictate.app.model.ModelCatalog
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.model.TranscriptionResponseTimeout
import com.opendictate.app.network.OpenAiTranscriptionClient
import com.opendictate.app.network.TranscriptSearchDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val dictationButtonAppearance: DictationButtonAppearance = DictationButtonAppearance(),
    val hasApiKey: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val microphoneGranted: Boolean = false,
    val model: TranscriptionModel = TranscriptionModel.ACCURATE,
    val liveModelId: String = ModelCatalog.DEFAULT.live.first(),
    val accurateModelId: String = ModelCatalog.DEFAULT.accurate.first(),
    val modelCatalog: ModelCatalog = ModelCatalog.DEFAULT,
    val textProcessingModel: String = com.opendictate.app.model.TextProcessingModel.ID,
    val textProcessingServiceTier: com.opendictate.app.model.TextProcessingServiceTier = com.opendictate.app.model.TextProcessingServiceTier.STANDARD,
    val modelsLoading: Boolean = false,
    val modelsError: Boolean = false,
    val newModelCount: Int = 0,
    val transformationButtonEnabled: Boolean = true,
    val languages: Set<DictationLanguage> = emptySet(),
    val prompt: String = "",
    val postProcessing: com.opendictate.app.model.PostProcessingSettings = com.opendictate.app.model.PostProcessingSettings(),
    val selectionDictionaryActionEnabled: Boolean = true,
    val transcriptionResponseTimeoutSeconds: Int? = TranscriptionResponseTimeout.DEFAULT_SECONDS,
    val appFilter: AppFilter = AppFilter(),
    val installedApps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
)

data class InstalledApp(val packageName: String, val label: String)

enum class HistorySearchMode {
    NONE,
    FUZZY,
    AI,
}

data class HistoryUiState(
    val entries: List<TranscriptHistoryItem> = emptyList(),
    val query: String = "",
    val searchMode: HistorySearchMode = HistorySearchMode.NONE,
    val aiMatchIds: List<Long> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    val visibleEntries: List<TranscriptHistoryItem>
        get() = when (searchMode) {
            HistorySearchMode.NONE -> entries
            HistorySearchMode.FUZZY -> fuzzySearch(entries, query)
            HistorySearchMode.AI -> {
                val entriesById = entries.associateBy(TranscriptHistoryItem::id)
                aiMatchIds.mapNotNull(entriesById::get)
            }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val selectionDictionaryComponent = ComponentName(
        application,
        AddToDictionaryActivity::class.java,
    )
    private val apiKeyStore = SecureApiKeyStore(application)
    val replacements = (application as OpenDictateApplication).replacementStore
    val chatGPT = (application as OpenDictateApplication).chatGPT
    private var chatGPTJob: Job? = null
    val driveSync = (application as OpenDictateApplication).driveSync
    private val historyStore = (application as OpenDictateApplication).transcriptHistoryStore
    private val apiClient = OpenAiTranscriptionClient(application.resources)
    private val mutableState = MutableStateFlow(loadState())
    val state = mutableState.asStateFlow()
    private val mutableHistoryState = MutableStateFlow(HistoryUiState())
    val historyState = mutableHistoryState.asStateFlow()
    private var historyLoadJob: Job? = null
    private var historySearchJob: Job? = null
    private var modelLoadJob: Job? = null
    private var appsLoadJob: Job? = null

    init {
        refreshModelCatalog()
        viewModelScope.launch {
            (application as OpenDictateApplication).driveSync.state.collect {
                mutableState.update { current -> current.copy(model = settings.model, prompt = settings.prompt,
                    postProcessing = settings.postProcessing, textProcessingModel = settings.textProcessingModel, textProcessingServiceTier = settings.textProcessingServiceTier,
                    liveModelId = settings.liveModelId, accurateModelId = settings.accurateModelId) }
            }
        }
    }
    fun refreshPermissions() {
        mutableState.update {
            it.copy(
                hasApiKey = apiKeyStore.hasKey(),
                accessibilityEnabled = isAccessibilityEnabled(getApplication()),
                microphoneGranted = hasMicrophonePermission(getApplication()),
            )
        }
    }

    fun saveApiKey(key: String) {
        modelLoadJob?.cancel()
        modelLoadJob = null
        apiKeyStore.save(key)
        settings.clearModelCatalog()
        mutableState.update {
            it.copy(
                modelCatalog = ModelCatalog.DEFAULT,
                liveModelId = settings.liveModelId,
                accurateModelId = settings.accurateModelId,
                newModelCount = 0,
            )
        }
        refreshPermissions()
        refreshModelCatalog(force = true)
    }

    fun connectChatGPT() { connectChatGPTAccount("__last__") }
    fun connectChatGPTAccount(account: String?) {
        if (!apiKeyStore.hasKey() || chatGPT.state.value.busy) return
        chatGPTJob = viewModelScope.launch {
            chatGPT.connect(apiKeyStore::hasKey, account) { url ->
                getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    fun cancelChatGPT() { chatGPT.cancel(); chatGPTJob?.cancel() }
    fun disconnectChatGPT() { chatGPTJob = viewModelScope.launch { chatGPT.disconnect() } }
    override fun onCleared() { cancelChatGPT(); super.onCleared() }

    fun getApiKey(): String = apiKeyStore.get().orEmpty()

    fun clearApiKey() {
        cancelChatGPT()
        apiKeyStore.clear()
        modelLoadJob?.cancel()
        modelLoadJob = null
        settings.clearModelCatalog()
        mutableState.update {
            it.copy(
                modelCatalog = ModelCatalog.DEFAULT,
                liveModelId = settings.liveModelId,
                accurateModelId = settings.accurateModelId,
                modelsLoading = false,
                newModelCount = 0,
            )
        }
        refreshPermissions()
    }

    fun selectModel(model: TranscriptionModel) {
        settings.model = model
        mutableState.update { it.copy(model = model) }
    }

    fun setDictationButtonAppearance(appearance: DictationButtonAppearance) {
        settings.dictationButtonAppearance = appearance
        mutableState.update { it.copy(dictationButtonAppearance = appearance) }
    }

    fun selectTheme(theme: AppTheme) {
        settings.theme = theme
        mutableState.update { it.copy(theme = theme) }
    }

    fun selectLiveModel(id: String) {
        if (id !in modelOptions(mutableState.value.modelCatalog.live, ModelCatalog.DEFAULT.live)) return
        settings.liveModelId = id
        mutableState.update { it.copy(liveModelId = id) }
    }

    fun selectAccurateModel(id: String) {
        if (id !in modelOptions(mutableState.value.modelCatalog.accurate, ModelCatalog.DEFAULT.accurate)) return
        settings.accurateModelId = id
        mutableState.update { it.copy(accurateModelId = id) }
    }

    fun selectTextProcessingModel(id: String) {
        if (!com.opendictate.app.model.TextProcessingModel.isValid(id)) return
        val model = com.opendictate.app.model.TextProcessingModel.model(id)
        val tier = com.opendictate.app.model.TextProcessingModel.tier(id)
        settings.textProcessingModel = model
        mutableState.update { it.copy(textProcessingModel = model, textProcessingServiceTier = tier) }
    }

    fun savePostProcessing(value: com.opendictate.app.model.PostProcessingSettings) {
        if (!value.isValid) return
        settings.postProcessing = value
        mutableState.update { it.copy(postProcessing = value) }
    }

    fun refreshModelCatalog(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - settings.modelCatalogUpdatedAt < MODEL_REFRESH_MS) return
        if (modelLoadJob?.isActive == true) return
        val key = apiKeyStore.get() ?: return
        modelLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(modelsLoading = true, modelsError = false) }
            try {
                val catalog = apiClient.listModels(key)
                val previous = mutableState.value.modelCatalog
                val hadCatalog = settings.modelCatalogUpdatedAt > 0L
                settings.modelCatalog = catalog
                val added = (catalog.live + catalog.accurate).toSet() -
                    (previous.live + previous.accurate).toSet()
                mutableState.update {
                    it.copy(
                        modelCatalog = catalog,
                        liveModelId = settings.liveModelId,
                        accurateModelId = settings.accurateModelId,
                        modelsLoading = false,
                        newModelCount = if (hadCatalog) added.size else 0,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(modelsLoading = false, modelsError = true) }
            }
        }
    }

    fun setTransformationButtonEnabled(enabled: Boolean) {
        settings.transformationButtonEnabled = enabled
        mutableState.update { it.copy(transformationButtonEnabled = enabled) }
    }

    fun loadInstalledApps() {
        if (appsLoadJob?.isActive == true) return
        appsLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(appsLoading = true, appFilter = settings.appFilter) }
            val apps = withContext(Dispatchers.IO) {
                val packageManager = getApplication<Application>().packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { info ->
                        InstalledApp(info.activityInfo.packageName, info.loadLabel(packageManager).toString())
                    }
                    .filterNot { it.packageName == getApplication<Application>().packageName }
                    .distinctBy(InstalledApp::packageName)
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label)
                        .thenBy(InstalledApp::packageName))
            }
            mutableState.update { it.copy(installedApps = apps, appsLoading = false) }
        }
    }

    fun setAppFilterMode(mode: AppFilterMode) {
        val filter = settings.appFilter.copy(mode = mode)
        settings.appFilter = filter
        mutableState.update { it.copy(appFilter = filter) }
    }

    fun setAppSelected(packageName: String, selected: Boolean) {
        val filter = settings.appFilter.withPackageSelected(packageName, selected)
        settings.appFilter = filter
        mutableState.update { it.copy(appFilter = filter) }
    }

    fun toggleLanguage(language: DictationLanguage) {
        val languages = mutableState.value.languages.toMutableSet().apply {
            if (!add(language)) remove(language)
        }
        settings.languages = languages
        mutableState.update { it.copy(languages = languages) }
    }

    fun useAutomaticLanguageDetection() {
        settings.languages = emptySet()
        mutableState.update { it.copy(languages = emptySet()) }
    }

    fun savePrompt(prompt: String) {
        settings.prompt = prompt
        mutableState.update { it.copy(prompt = settings.prompt) }
    }

    fun refreshPrompt() {
        mutableState.update { it.copy(prompt = settings.prompt) }
    }

    fun refreshSelectionDictionaryAction() {
        mutableState.update {
            it.copy(selectionDictionaryActionEnabled = isSelectionDictionaryActionEnabled())
        }
    }

    fun setSelectionDictionaryActionEnabled(enabled: Boolean) {
        getApplication<Application>().packageManager.setComponentEnabledSetting(
            selectionDictionaryComponent,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        refreshSelectionDictionaryAction()
    }

    private fun isSelectionDictionaryActionEnabled(): Boolean =
        when (getApplication<Application>().packageManager.getComponentEnabledSetting(
            selectionDictionaryComponent,
        )) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }

    fun setTranscriptionResponseTimeout(seconds: Int?) {
        settings.transcriptionResponseTimeoutSeconds = seconds
        mutableState.update { it.copy(transcriptionResponseTimeoutSeconds = seconds) }
    }

    fun refreshHistory() {
        historyLoadJob?.cancel()
        historyLoadJob = viewModelScope.launch {
            mutableHistoryState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val entries = withContext(Dispatchers.IO) { historyStore.getAll() }
                mutableHistoryState.update { it.copy(entries = entries, isLoading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableHistoryState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = getApplication<Application>().getString(
                            R.string.history_load_error,
                        ),
                    )
                }
            }
        }
    }

    fun updateHistoryQuery(query: String) {
        historySearchJob?.cancel()
        mutableHistoryState.update { it.withHistoryQuery(query) }
    }

    fun runAiHistorySearch() {
        val state = mutableHistoryState.value
        val query = state.query.trim()
        if (query.isEmpty() || state.entries.isEmpty()) return
        val apiKey = apiKeyStore.get()
        if (apiKey.isNullOrBlank()) {
            mutableHistoryState.update {
                it.copy(errorMessage = getApplication<Application>().getString(R.string.error_add_api_key))
            }
            return
        }

        historySearchJob?.cancel()
        historySearchJob = viewModelScope.launch {
            mutableHistoryState.update {
                it.copy(
                    searchMode = HistorySearchMode.AI,
                    aiMatchIds = emptyList(),
                    isLoading = true,
                    errorMessage = null,
                )
            }
            try {
                val matches = apiClient.searchTranscriptHistory(
                    apiKey = apiKey,
                    query = query,
                    documents = state.entries.map {
                        TranscriptSearchDocument(id = it.id, text = it.text)
                    },
                )
                mutableHistoryState.update { current ->
                    if (current.query.trim() == query) {
                        current.copy(aiMatchIds = matches, isLoading = false)
                    } else {
                        current.copy(isLoading = false)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableHistoryState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = getApplication<Application>().getString(R.string.history_ai_search_error),
                    )
                }
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        val beforeDelete = mutableHistoryState.value
        mutableHistoryState.update { state ->
            state.copy(
                entries = state.entries.filterNot { it.id == id },
                aiMatchIds = state.aiMatchIds.filterNot { it == id },
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { historyStore.delete(id) }
                if (!deleted) throw IllegalStateException("History item was not found")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableHistoryState.value = beforeDelete.copy(
                    errorMessage = getApplication<Application>().getString(
                        R.string.history_delete_error,
                    ),
                )
            }
        }
    }

    private fun loadState() = MainUiState(
        theme = settings.theme,
        dictationButtonAppearance = settings.dictationButtonAppearance,
        hasApiKey = apiKeyStore.hasKey(),
        accessibilityEnabled = isAccessibilityEnabled(getApplication()),
        microphoneGranted = hasMicrophonePermission(getApplication()),
        model = settings.model,
        liveModelId = settings.liveModelId,
        accurateModelId = settings.accurateModelId,
        modelCatalog = settings.modelCatalog,
        transformationButtonEnabled = settings.transformationButtonEnabled,
        languages = settings.languages,
        prompt = settings.prompt,
        postProcessing = settings.postProcessing,
        textProcessingModel = settings.textProcessingModel, textProcessingServiceTier = settings.textProcessingServiceTier,
        selectionDictionaryActionEnabled = isSelectionDictionaryActionEnabled(),
        transcriptionResponseTimeoutSeconds = settings.transcriptionResponseTimeoutSeconds,
        appFilter = settings.appFilter,
    )

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun hasMicrophonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

}

private const val MAX_HISTORY_QUERY_CHARS = 300
private const val MODEL_REFRESH_MS = 24 * 60 * 60 * 1000L

internal fun modelOptions(discovered: List<String>, defaults: List<String>): List<String> =
    discovered.ifEmpty { defaults }

internal fun HistoryUiState.withHistoryQuery(query: String): HistoryUiState {
    val updatedQuery = query.take(MAX_HISTORY_QUERY_CHARS)
    return copy(
        query = updatedQuery,
        searchMode = if (updatedQuery.isBlank()) {
            HistorySearchMode.NONE
        } else {
            HistorySearchMode.FUZZY
        },
        aiMatchIds = emptyList(),
        isLoading = false,
        errorMessage = null,
    )
}
