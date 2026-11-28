package com.opendictate.app.model

import java.util.Locale

enum class TranscriptionModel(val apiName: String) {
    LIVE("gpt-live-transcribe"),
    ACCURATE("gpt-transcribe"),
    /** Recognition on this phone. A local choice: it is never synced, since other devices may lack it. */
    ON_DEVICE(OnDeviceModels.ID);

    /** Text appears while the user speaks, so cancelling must restore the field. */
    val streamsPartials: Boolean get() = this != ACCURATE

    companion object {
        /** Reads the synced Live/Accurate choice, which never holds the local on-device mode. */
        fun fromStored(value: String?): TranscriptionModel =
            entries.firstOrNull { it.name == value && it != ON_DEVICE } ?: ACCURATE
    }
}

enum class DictationLanguage(val code: String) {
    RUSSIAN("ru"),
    ENGLISH("en"),
    UKRAINIAN("uk"),
    GERMAN("de"),
    FRENCH("fr"),
    SPANISH("es"),
    ITALIAN("it"),
    PORTUGUESE("pt"),
    POLISH("pl"),
    TURKISH("tr"),
    CHINESE("zh"),
    JAPANESE("ja"),
    KOREAN("ko"),
    ARABIC("ar"),
    HINDI("hi");

    companion object {
        fun fromStored(
            values: Set<String>?,
            legacyValue: String? = null,
            defaultValues: Set<DictationLanguage> = emptySet(),
        ): Set<DictationLanguage> {
            val storedNames = when {
                values != null -> values
                legacyValue != null -> setOf(legacyValue).filterNot { it == "AUTO" }.toSet()
                else -> return defaultValues.toCollection(linkedSetOf())
            }
            return entries.filterTo(linkedSetOf()) { it.name in storedNames }
        }

        fun fromLanguageTags(tags: List<String>): Set<DictationLanguage> {
            val languageCodes = tags
                .mapTo(mutableSetOf()) { tag ->
                    tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
                }
            return entries.filterTo(linkedSetOf()) { it.code in languageCodes }
        }
    }
}
