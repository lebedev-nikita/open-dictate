package com.opendictate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.core.content.edit
import com.opendictate.app.model.AppFilter
import com.opendictate.app.model.AppFilterMode
import com.opendictate.app.model.DictationButtonAppearance
import com.opendictate.app.model.AppTheme
import com.opendictate.app.model.DictationLanguage
import com.opendictate.app.model.ModelCatalog
import com.opendictate.app.model.PostProcessingSettings
import com.opendictate.app.model.TranscriptionModel
import com.opendictate.app.model.TranscriptionResponseTimeout
import com.opendictate.app.model.preferredModelId
import java.io.Closeable
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val replacementPrefs = context.getSharedPreferences("word_replacements", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("postprocessingOptions")) prefs.getString("postprocessing", null)?.let { legacy ->
            runCatching { PostProcessingSettings.fromLegacyJson(legacy).toJson() }.getOrNull()?.let { encoded ->
                prefs.edit { putString("postprocessingOptions", encoded) }
            }
        }
        prefs.edit { remove("postprocessing") }
        prefs.edit { remove("custom_transcription_prompt"); remove("transcription_prompt_configured"); remove("transformation_model"); remove("transformation_model_id") }
    }

    var theme: AppTheme
        get() = AppTheme.fromStored(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    var dictationButtonAppearance: DictationButtonAppearance
        get() = DictationButtonAppearance.fromStored(
            prefs.getInt(KEY_BUTTON_DIAMETER, DictationButtonAppearance.DEFAULT_DIAMETER_DP),
            prefs.getString(KEY_BUTTON_POSITION, null),
        )
        set(value) = prefs.edit {
            putInt(KEY_BUTTON_DIAMETER, value.diameterDp)
            putString(KEY_BUTTON_POSITION, value.position.name)
        }

    fun observeDictationButtonAppearance(onChange: (DictationButtonAppearance) -> Unit): Closeable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_BUTTON_DIAMETER || key == KEY_BUTTON_POSITION) {
                onChange(dictationButtonAppearance)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var model: TranscriptionModel
        get() = TranscriptionModel.fromStored(prefs.getString(KEY_MODEL, null))
        set(value) = saveSynced { putString(KEY_MODEL, value.name) }

    var liveModelId: String
        get() = preferredModelId(
            prefs.getString(KEY_LIVE_MODEL_ID, null).orEmpty(),
            modelCatalog.live,
            ModelCatalog.DEFAULT.live,
        )
        set(value) = saveSynced { putString(KEY_LIVE_MODEL_ID, value) }

    var accurateModelId: String
        get() = preferredModelId(
            prefs.getString(KEY_ACCURATE_MODEL_ID, null).orEmpty(),
            modelCatalog.accurate,
            ModelCatalog.DEFAULT.accurate,
        )
        set(value) = saveSynced { putString(KEY_ACCURATE_MODEL_ID, value) }

    var textProcessingModel: String
        get() = prefs.getString("text_processing_model", null)?.takeIf(com.opendictate.app.model.TextProcessingModel::isValid)
            ?.let(com.opendictate.app.model.TextProcessingModel::model)
            ?: com.opendictate.app.model.TextProcessingModel.ID
        set(value) {
            require(com.opendictate.app.model.TextProcessingModel.isValid(value))
            saveSynced { putString("text_processing_model", com.opendictate.app.model.TextProcessingModel.model(value)) }
        }

    // Retain the sync key and history tiers for compatibility, but never enable Fast.
    val textProcessingServiceTier: com.opendictate.app.model.TextProcessingServiceTier
        get() = com.opendictate.app.model.TextProcessingServiceTier.STANDARD

    var modelCatalog: ModelCatalog
        get() = ModelCatalog.fromCachedJson(prefs.getString(KEY_MODEL_CATALOG_V2, null))
        set(value) = prefs.edit {
            putString(KEY_MODEL_CATALOG_V2, value.toCachedJson())
            remove(KEY_MODEL_CATALOG)
            putLong(KEY_MODEL_CATALOG_UPDATED, System.currentTimeMillis())
        }

    val modelCatalogUpdatedAt: Long
        get() = if (prefs.contains(KEY_MODEL_CATALOG_V2)) {
            prefs.getLong(KEY_MODEL_CATALOG_UPDATED, 0L)
        } else {
            0L
        }

    fun clearModelCatalog() = prefs.edit {
        remove(KEY_MODEL_CATALOG)
        remove(KEY_MODEL_CATALOG_V2)
        remove(KEY_MODEL_CATALOG_UPDATED)
    }

    var languages: Set<DictationLanguage>
        get() = DictationLanguage.fromStored(
            values = prefs.getStringSet(KEY_LANGUAGES, null),
            legacyValue = prefs.getString(KEY_LANGUAGE, null),
            defaultValues = systemDictationLanguages(),
        )
        set(value) = prefs.edit {
            putStringSet(KEY_LANGUAGES, value.mapTo(mutableSetOf()) { it.name })
            remove(KEY_LANGUAGE)
        }

    var prompt: String
        get() = normalizeDictionaryTerms(prefs.getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty())
        set(value) = saveSynced { putString(KEY_PROMPT, normalizeDictionaryTerms(value).trim()) }

    var postProcessing: PostProcessingSettings
        get() = prefs.getString("postprocessingOptions", null)?.let { runCatching { PostProcessingSettings.fromJson(it) }.getOrNull() }
            ?: PostProcessingSettings()
        set(value) = saveSynced { putString("postprocessingOptions", value.toJson()) }

    var transformationButtonEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSFORMATION_BUTTON_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_TRANSFORMATION_BUTTON_ENABLED, value) }

    var appFilter: AppFilter
        get() = AppFilter(
            mode = AppFilterMode.fromStored(prefs.getString(KEY_APP_FILTER_MODE, null)),
            excludedPackages = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null).orEmpty().toSet(),
            allowedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, null).orEmpty().toSet(),
        )
        set(value) = prefs.edit {
            putString(KEY_APP_FILTER_MODE, value.mode.name)
            putStringSet(KEY_EXCLUDED_PACKAGES, value.excludedPackages.toSet())
            putStringSet(KEY_ALLOWED_PACKAGES, value.allowedPackages.toSet())
        }

    fun observeAppFilter(onChange: (AppFilter) -> Unit): Closeable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in APP_FILTER_KEYS) onChange(appFilter)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var transcriptionResponseTimeoutSeconds: Int?
        get() = TranscriptionResponseTimeout.fromStored(
            prefs.getInt(KEY_TRANSCRIPTION_RESPONSE_TIMEOUT, TranscriptionResponseTimeout.DEFAULT_SECONDS),
        )
        set(value) {
            require(value == null || TranscriptionResponseTimeout.isValid(value))
            prefs.edit { putInt(KEY_TRANSCRIPTION_RESPONSE_TIMEOUT, value ?: 0) }
        }

    var driveSyncEnabled: Boolean
        get() = prefs.getBoolean("drive_sync_enabled", false)
        set(value) = prefs.edit { putBoolean("drive_sync_enabled", value) }

    var driveConnectionPending: Boolean
        get() = prefs.getBoolean("drive_connection_pending", false)
        set(value) = prefs.edit { putBoolean("drive_connection_pending", value) }

    val syncDeviceId: String
        get() = synchronized(SYNC_LOCK) {
            prefs.getString("sync_device_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit { putString("sync_device_id", it) }
            }
        }

    private fun syncValues(): Map<String, String?> {
        val replacements = replacementPrefs.getString("document", null) ?: "{\"schemaVersion\":1,\"rules\":[]}"
        ReplacementDocument.fromJson(replacements)
        return mutableMapOf<String, String?>(
        "dictionary" to prefs.getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty(), "mode" to model.name.lowercase(),
        "liveModel" to prefs.getString(KEY_LIVE_MODEL_ID, ModelCatalog.DEFAULT.live.first()).orEmpty(),
        "accurateModel" to prefs.getString(KEY_ACCURATE_MODEL_ID, ModelCatalog.DEFAULT.accurate.first()).orEmpty(),
        "postprocessingRules" to postProcessing.rulesJson(),
        "textProcessingModel" to textProcessingModel,
        "textProcessingServiceTier" to textProcessingServiceTier.apiValue,
        "wordReplacements" to replacements,
        "wordReplacementsEnabled" to replacementPrefs.getBoolean("enabled", true).toString(),
        ).also { values ->
            require(values.values.all { it == null || it.toByteArray().size <= 262_144 })
        }
    }

    fun syncDocument(): SettingsSyncDocument = synchronized(SYNC_LOCK) {
        val stored = prefs.getString("sync_document", null)
        val document = stored?.let(SettingsSyncDocument::fromJson) ?: SettingsSyncDocument()
        document.record(syncValues().filterKeys { it !in document.entries }, syncDeviceId, 0, seed = true).also {
            prefs.edit { putString("sync_document", it.toJson()) }
        }
    }

    private fun saveSynced(update: android.content.SharedPreferences.Editor.() -> Unit) = synchronized(SYNC_LOCK) {
        // A damaged sync journal must not prevent ordinary local settings edits.
        val previous = runCatching {
            prefs.getString("sync_document", null)?.let(SettingsSyncDocument::fromJson) ?: syncDocument()
        }.getOrNull()
        prefs.edit { update() }
        if (previous != null) {
            runCatching {
                val document = previous.record(syncValues(), syncDeviceId, System.currentTimeMillis())
                SettingsSyncDocument.fromJson(document.toJson())
                if (document != previous) prefs.edit {
                    putString("sync_document", document.toJson())
                    // Remote imports never increment this counter, so they cannot cause upload loops.
                    putLong("sync_local_revision", prefs.getLong("sync_local_revision", 0) + 1)
                }
            }
        }
    }

    /** Called after all downloads succeed. Includes edits made while the network was suspended. */
    fun recordReplacementChange() { saveSynced {} }

    fun mergeSyncDocument(remote: SettingsSyncDocument, replacements: ReplacementStore,
                          source: SettingsSyncDocument.ConnectionSource? = null): SettingsSyncDocument = synchronized(SYNC_LOCK) {
        val local = syncDocument()
        val merged = if (source == null) local.merge(remote).promoteSeeds(syncDeviceId, System.currentTimeMillis())
            else local.resolvingConnection(remote, source, syncDeviceId, System.currentTimeMillis())
        validateSyncDocument(merged)
        val values = merged.entries
        val replacementJSON = values["wordReplacements"]?.value
        val enabled = values["wordReplacementsEnabled"]?.value
        if (replacementJSON != null || enabled != null) replacements.applySync(replacementJSON, enabled?.toBooleanStrict())
        prefs.edit {
            values["dictionary"]?.let { putString(KEY_PROMPT, it.value) }
            values["mode"]?.let { putString(KEY_MODEL, requireNotNull(it.value).uppercase()) }
            values["liveModel"]?.let { putString(KEY_LIVE_MODEL_ID, it.value) }
            values["accurateModel"]?.let { putString(KEY_ACCURATE_MODEL_ID, it.value) }
            values["textProcessingModel"]?.let { putString("text_processing_model", it.value) }
            values["postprocessingRules"]?.let { putString("postprocessingOptions", postProcessing.applyingRules(requireNotNull(it.value)).toJson()) }
            putString("sync_document", merged.toJson())
        }
        merged
    }

    fun validateSyncDocument(merged: SettingsSyncDocument) {
        SettingsSyncDocument.fromJson(merged.toJson())
        val values = merged.entries
        values["postprocessingRules"]?.value?.let { PostProcessingSettings().applyingRules(it) }
        require(values["mode"]?.value in listOf(null, "live", "accurate"))
        listOf("liveModel", "accurateModel", "textProcessingModel").forEach { key ->
            values[key]?.value?.let { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) }
        }
        values["textProcessingServiceTier"]?.value?.let {
            require(com.opendictate.app.model.TextProcessingServiceTier.fromValue(it) != null)
        }
        val replacementJSON = values["wordReplacements"]?.value
        replacementJSON?.let(ReplacementDocument::fromJson)
        val enabled = values["wordReplacementsEnabled"]?.value
        require(enabled in listOf(null, "true", "false"))
    }

    companion object {
        private val SYNC_LOCK = Any()
        private const val FILE_NAME = "opendictate_settings"
        private const val KEY_BUTTON_DIAMETER = "dictation_button_diameter_dp"
        private const val KEY_BUTTON_POSITION = "dictation_button_position"
        private const val KEY_THEME = "theme"
        private const val KEY_MODEL = "model"
        private const val KEY_LIVE_MODEL_ID = "live_model_id"
        private const val KEY_ACCURATE_MODEL_ID = "accurate_model_id"
        private const val KEY_MODEL_CATALOG = "model_catalog"
        private const val KEY_MODEL_CATALOG_V2 = "model_catalog_v2"
        private const val KEY_MODEL_CATALOG_UPDATED = "model_catalog_updated"
        private const val KEY_LANGUAGES = "languages"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_TRANSFORMATION_BUTTON_ENABLED = "transformation_button_enabled"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_APP_FILTER_MODE = "app_filter_mode"
        private val APP_FILTER_KEYS = setOf(KEY_APP_FILTER_MODE, KEY_EXCLUDED_PACKAGES, KEY_ALLOWED_PACKAGES)
        private const val KEY_TRANSCRIPTION_RESPONSE_TIMEOUT = "transcription_response_timeout_seconds"
        private const val DEFAULT_PROMPT = "OpenDictate\nDictate"
    }
}

private fun systemDictationLanguages(): Set<DictationLanguage> {
    val locales = Resources.getSystem().configuration.locales
    return DictationLanguage.fromLanguageTags(
        List(locales.size()) { index -> locales[index].toLanguageTag() },
    )
}
