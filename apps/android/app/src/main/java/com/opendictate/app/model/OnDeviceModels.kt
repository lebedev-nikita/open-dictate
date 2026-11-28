package com.opendictate.app.model

import java.util.Locale

enum class OnDeviceStatus { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** The phone can recognize speech once anything missing is downloaded; unknown until ML Kit answers. */
val OnDeviceStatus?.offered: Boolean get() = this != null && this != OnDeviceStatus.UNAVAILABLE

/** Pure rules for speech recognition on the phone, which needs no key and works behind other apps. */
object OnDeviceModels {
    /** Stored in history and diagnostics; kept from the release that introduced the mode. */
    const val ID = "gemini-nano"

    /**
     * On-device dictation needs no key. [cloudText] marks sessions that need the OpenAI text model
     * or the Accurate path, such as voice editing.
     */
    fun requiresApiKey(model: TranscriptionModel, cloudText: Boolean): Boolean =
        model != TranscriptionModel.ON_DEVICE || cloudText

    /**
     * Recognition takes one locale. The phone's language wins when the user chose it or chose none;
     * otherwise the first chosen language. The phone's region is kept for its own language.
     */
    fun speechLocale(languages: Set<DictationLanguage>, systemTags: List<String>): String {
        val system = systemTags.map(Locale::forLanguageTag)
        val systemLanguages = system.mapNotNull { locale -> DictationLanguage.entries.firstOrNull { it.code == locale.language } }
        val language = systemLanguages.firstOrNull { languages.isEmpty() || it in languages }
            ?: languages.firstOrNull()
            ?: DictationLanguage.ENGLISH
        val match = system.firstOrNull { it.language == language.code }
        if (language == DictationLanguage.CHINESE) {
            val traditional = match != null && (match.script == "Hant" || match.country in setOf("TW", "HK", "MO"))
            return if (traditional) "cmn-Hant-TW" else "cmn-Hans-CN"
        }
        val region = match?.country?.takeIf(String::isNotEmpty) ?: DEFAULT_REGIONS.getValue(language)
        return "${language.code}-$region"
    }

    private val DEFAULT_REGIONS = mapOf(
        DictationLanguage.RUSSIAN to "RU", DictationLanguage.ENGLISH to "US", DictationLanguage.UKRAINIAN to "UA",
        DictationLanguage.GERMAN to "DE", DictationLanguage.FRENCH to "FR", DictationLanguage.SPANISH to "ES",
        DictationLanguage.ITALIAN to "IT", DictationLanguage.PORTUGUESE to "PT", DictationLanguage.POLISH to "PL",
        DictationLanguage.TURKISH to "TR", DictationLanguage.CHINESE to "CN", DictationLanguage.JAPANESE to "JP",
        DictationLanguage.KOREAN to "KR", DictationLanguage.ARABIC to "SA", DictationLanguage.HINDI to "IN",
    )
}

/**
 * Joins what the recognizer reports into the cumulative text the field shows. A final result
 * closes a speech segment and the next partial starts the following one. Results that already
 * repeat the earlier segments replace them instead of being appended twice.
 */
class OnDeviceTranscript {
    private var closed = ""
    private var partial = ""

    val text: String get() = combine(closed, partial)

    fun partial(value: String): String {
        partial = value.trim()
        return text
    }

    fun final(value: String): String {
        closed = combine(closed, value.trim())
        partial = ""
        return text
    }

    private fun combine(earlier: String, next: String): String = when {
        next.isEmpty() -> earlier
        earlier.isEmpty() || next.startsWith(earlier) -> next
        else -> earlier + (if (needsSpace(earlier.last(), next.first())) " " else "") + next
    }

    private fun needsSpace(before: Char, after: Char): Boolean =
        !before.isWhitespace() && !after.isWhitespace() && !before.isUnspaced() && !after.isUnspaced()

    private fun Char.isUnspaced(): Boolean = Character.UnicodeScript.of(code) in UNSPACED_SCRIPTS

    private companion object {
        val UNSPACED_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI)
    }
}
