package com.opendictate.app.data

import org.json.JSONObject

/** Shared with macOS. Unknown keys survive except preferences explicitly made device-local. */
data class SettingsSyncEntry(val value: String?, val modifiedAt: Long, val deviceId: String)

data class SettingsSyncDocument(val entries: Map<String, SettingsSyncEntry> = emptyMap()) {
    private fun syncEntries() = entries.filterKeys { it !in RETIRED_KEYS }.mapValues { (key, entry) ->
        if (key == "postprocessing" && entry.value != null) entry.copy(value = runCatching {
            com.opendictate.app.model.PostProcessingSettings.fromJson(entry.value).toJson()
        }.getOrDefault(entry.value)) else entry
    }

    fun merge(other: SettingsSyncDocument): SettingsSyncDocument {
        val result = syncEntries().toMutableMap()
        other.syncEntries().forEach { (key, incoming) ->
            val existing = result[key]
            if (existing == null || compareValuesBy(incoming, existing,
                    SettingsSyncEntry::modifiedAt, SettingsSyncEntry::deviceId, SettingsSyncEntry::value) > 0) {
                result[key] = incoming
            }
        }
        return SettingsSyncDocument(result)
    }

    fun record(values: Map<String, String?>, deviceId: String, now: Long, seed: Boolean = false): SettingsSyncDocument {
        val result = syncEntries().toMutableMap()
        val clock = maxOf(now, (result.values.maxOfOrNull { it.modifiedAt } ?: 0L) + 1)
        KEYS.forEach { key ->
            if (key !in values) return@forEach
            val value = values[key]
            if (key !in result || result[key]?.value != value) result[key] = SettingsSyncEntry(value, if (seed) 0 else clock, if (seed) "" else deviceId)
        }
        return SettingsSyncDocument(result)
    }

    enum class ConnectionSource { CLOUD, LOCAL }

    /** Compare visible settings, excluding clocks and unknown future entries. */
    fun hasSameSettings(other: SettingsSyncDocument): Boolean =
        KEYS.all { syncEntries()[it]?.value == other.syncEntries()[it]?.value }

    fun needsConnectionChoice(remote: SettingsSyncDocument): Boolean = KEYS.any { key ->
        remote.syncEntries()[key]?.let { it.value != syncEntries()[key]?.value } == true
    }

    /** An explicit choice wins over offline clocks without deleting unknown settings. */
    fun resolvingConnection(remote: SettingsSyncDocument, source: ConnectionSource,
                            deviceId: String, now: Long): SettingsSyncDocument {
        val preferred = if (source == ConnectionSource.CLOUD) remote else this
        val latest = maxOf(syncEntries().values.maxOfOrNull { it.modifiedAt } ?: 0L,
            remote.syncEntries().values.maxOfOrNull { it.modifiedAt } ?: 0L)
        val values = preferred.entries.mapValues { it.value.value }
        return merge(remote).record(values, deviceId, maxOf(now, latest + 1))
            .promoteSeeds(deviceId, now)
    }

    fun promoteSeeds(deviceId: String, now: Long): SettingsSyncDocument {
        val synced = syncEntries()
        val clock = maxOf(now, (synced.values.maxOfOrNull { it.modifiedAt } ?: 0L) + 1)
        return SettingsSyncDocument(synced.mapValues { (key, entry) ->
            if (key in KEYS && entry.modifiedAt == 0L) entry.copy(modifiedAt = clock, deviceId = deviceId) else entry
        })
    }

    fun toJson(): String = JSONObject().put("schemaVersion", if (syncEntries().values.any { it.value == null }) 2 else 1).put("entries", JSONObject().also { objectEntries ->
        syncEntries().forEach { (key, entry) -> objectEntries.put(key, JSONObject()
            .put("value", entry.value ?: JSONObject.NULL).put("modifiedAt", entry.modifiedAt).put("deviceId", entry.deviceId)) }
    }).toString()

    companion object {
        val KEYS = setOf("dictionary", "mode", "liveModel", "accurateModel", "wordReplacements", "wordReplacementsEnabled", "postprocessing", "textProcessingModel")
        // Decode retired fields for compatibility, then discard them from journals and replicas.
        private val RETIRED_KEYS = setOf("accuratePunctuationEnabled", "transcriptionPrompt", "textModel")
        const val MAXIMUM_BYTES = 1_048_576L
        fun fromJson(json: String): SettingsSyncDocument {
            require(json.toByteArray().size <= MAXIMUM_BYTES)
            val root = JSONObject(json)
            val version = root.get("schemaVersion")
            require(version is Number && version.toDouble() in listOf(1.0, 2.0))
            val entries = root.getJSONObject("entries")
            require(entries.length() <= 1024)
            return SettingsSyncDocument(entries.keys().asSequence().associateWith { key ->
                val entry = entries.getJSONObject(key)
                val rawValue = entry.get("value")
                val value = if (rawValue == JSONObject.NULL) null else rawValue
                val timestamp = entry.get("modifiedAt")
                val device = entry.get("deviceId")
                require((value is String && value.toByteArray().size <= 262_144 ||
                    value == null && key == "transcriptionPrompt" && version.toDouble() == 2.0) && device is String &&
                    device.toByteArray().size <= 128 && key.toByteArray().size <= 128 && timestamp is Number)
                val time = timestamp.toLong()
                require(timestamp.toDouble() == time.toDouble() && time >= 0 && time < Long.MAX_VALUE - 1)
                SettingsSyncEntry(value as String?, time, device)
            }.filterKeys { it !in RETIRED_KEYS }).let { SettingsSyncDocument(it.syncEntries()) }
        }
    }
}
