package com.opendictate.app.model

import org.json.JSONObject
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Locale
import java.util.TimeZone

/** Shared style examples are data, not editable model instructions. */
data class ValueFormat(val enabled: Boolean = true, val example: String) {
    val isValid: Boolean get() = example.length in 1..96 && example.codePoints().anyMatch { Character.getType(it) in listOf(Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt()) } && example.none(Char::isISOControl) && example.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == example
}

enum class ValueKind(val id: String) {
    INTEGER("integer"), DECIMAL("decimal"), DATE("date"), TIME("time"), DURATION("duration"),
    PERCENT("percent"), CURRENCY("currency"), MEASUREMENT("measurement"), RANGE("range"), FRACTION("fraction");
    private val definition: JSONObject get() = PostProcessingRules.types.getValue(id)
    fun title(russian: Boolean) = definition.getString(if (russian) "ru" else "en")
    val examples: List<String> get() = definition.getJSONArray("examples").let { a -> List(a.length()) { a.getString(it) } }
    fun source(russian: Boolean) = definition.getString(if (russian) "sourceRu" else "sourceEn")
    internal fun instruction(format: ValueFormat) = "{\"example\":${jsonQuote(format.example)},\"reference\":${jsonQuote(definition.getString("reference"))},\"rule\":${jsonQuote(definition.getString("rule"))},\"type\":${jsonQuote(id)}}"
}

data class ValueFormatRule(val kind: ValueKind, val language: String? = null, val example: String) {
    val id: String get() = kind.id + ":" + (language ?: "*")
    val isValid: Boolean get() = ValueFormat(example = example).isValid && (language == null || language in PostProcessingLanguages.supportedCodes)
    fun languageTitle(russian: Boolean): String = language?.let { Locale.forLanguageTag(it).getDisplayLanguage(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH).replaceFirstChar { character -> character.titlecase(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH) } }
        ?: if (russian) "Все языки" else "All languages"
}

data class ValueFormatting(val entries: Map<ValueKind, ValueFormat>, val customRules: List<ValueFormatRule>? = null, val languageLocales: Map<String, String>? = null) {
    val rules: List<ValueFormatRule> get() = customRules ?: ValueKind.entries.map { ValueFormatRule(it, example = entries.getValue(it).example) }
    fun sortedRules(russian: Boolean): List<ValueFormatRule> {
        val collator = java.text.Collator.getInstance(if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH)
        return rules.sortedWith(compareBy<ValueFormatRule> { it.kind.ordinal }.thenBy { it.language != null }
            .thenComparator { a, b -> collator.compare(a.languageTitle(russian), b.languageTitle(russian)) }.thenBy { it.id })
    }
    fun settingRule(rule: ValueFormatRule, replacing: String? = null) = copy(customRules = rules.filter { it.id != rule.id && it.id != replacing } + rule)
    fun removingRule(id: String) = copy(customRules = rules.filter { it.id != id })
    val isValid: Boolean get() = entries.keys == ValueKind.entries.toSet() && entries.values.all { it.isValid } &&
        (customRules == null || customRules.size <= 64 && customRules.all { it.isValid } && customRules.map { it.id }.toSet().size == customRules.size) &&
        (languageLocales == null || customRules != null && languageLocales.keys.all { it in PostProcessingLanguages.supportedCodes } && languageLocales.values.all { it.length <= 35 && it.matches(Regex("^[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*$")) }) && json().toByteArray(Charsets.UTF_8).size <= PostProcessingSettings.MAXIMUM_PROMPT_BYTES - 256
    val hasEnabledTypes: Boolean get() = entries.values.any { it.enabled }
    fun updating(kind: ValueKind, format: ValueFormat) = copy(entries = entries + (kind to format))
    internal val instruction: String get() = instruction(emptyList())
    internal fun instruction(languages: Collection<String>): String {
        val active = ValueKind.entries.filter { entries.getValue(it).enabled }
        if (customRules == null) return PostProcessingRules.valuesInstructions + "\n\n" + active.joinToString(",", "[", "]") { it.instruction(entries.getValue(it)) }
        val globals = rules.filter { it.language == null }.associate { it.kind to it.example }
        val scopes = (if (languages.isEmpty()) rules.mapNotNull { it.language } else languages).toSet().intersect(PostProcessingLanguages.supportedCodes).sorted()
        val defaults = scopes.associateWith { systemDefaults(Locale.forLanguageTag((languageLocales?.get(it) ?: it).replace('_', '-')), automatic = false) }
        fun style(kind: ValueKind, language: String, example: String): String = kind.instruction(ValueFormat(example = example))
            .replaceFirst(",\"reference\":", ",\"language\":${jsonQuote(language)},\"reference\":")
            .replaceFirst(",\"type\":", ",\"source_value\":${jsonQuote(kind.source(false))},\"type\":")
        val styles = active.flatMap { kind ->
            listOf(style(kind, "*", globals[kind] ?: entries.getValue(kind).example)) + scopes.mapNotNull { language ->
                val specific = rules.firstOrNull { it.kind == kind && it.language == language }
                val example = specific?.example ?: if (languages.isNotEmpty() && kind !in globals) defaults.getValue(language).entries.getValue(kind).example else null
                example?.let { style(kind, language, it) }
            }
        }
        return PostProcessingRules.valuesInstructions + "\n\n" + PostProcessingRules.styleSelection + "\n\n" + styles.joinToString(",", "[", "]")
    }
    fun json(): String {
        val fields = entries.entries.associate { (kind, format) -> kind.id to "{\"enabled\":${format.enabled},\"example\":${jsonQuote(format.example)}}" }.toMutableMap()
        customRules?.let { rules -> fields["customRules"] = rules.sortedBy { it.id }.joinToString(",", "[", "]") { "{\"example\":${jsonQuote(it.example)},\"language\":${it.language?.let(::jsonQuote) ?: "null"},\"type\":${jsonQuote(it.kind.id)}}" } }
        languageLocales?.let { locales -> fields["languageLocales"] = locales.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${jsonQuote(it.key)}:${jsonQuote(it.value)}" } }
        return fields.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${jsonQuote(it.key)}:${it.value}" }
    }
    companion object {
        val supportedLanguages: List<String> get() = PostProcessingLanguages.supportedCodes.sorted()
        fun fromJson(json: JSONObject): ValueFormatting {
            require(json.keys().asSequence().toSet() == ValueKind.entries.map { it.id }.toSet() + listOf("customRules", "languageLocales").filter(json::has))
            val rules = if (json.has("customRules")) json.getJSONArray("customRules").let { array ->
                require(array.length() <= 64)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index); require(item.get("example") is String && item.has("language") && (item.isNull("language") || item.get("language") is String))
                    ValueFormatRule(ValueKind.entries.first { it.id == item.getString("type") }, if (item.isNull("language")) null else item.getString("language"), item.getString("example"))
                }
            } else null
            val locales = if (json.has("languageLocales")) json.getJSONObject("languageLocales").let { obj -> obj.keys().asSequence().associateWith { require(obj.get(it) is String); obj.getString(it) } } else null
            return ValueFormatting(ValueKind.entries.associateWith { kind ->
                val value = json.getJSONObject(kind.id)
                require(value.get("enabled") is Boolean && value.get("example") is String)
                ValueFormat(value.getBoolean("enabled"), value.getString("example"))
            }, rules, locales).also { require(it.isValid) }
        }
        /** Called with the system region, never the in-app language override. Persist the result on first use. */
        fun systemDefaults(locale: Locale, twentyFourHour: Boolean? = null, automatic: Boolean = true, preferredLocales: List<Locale> = listOf(locale)): ValueFormatting {
            fun number(value: Double, fraction: Int = 0) = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = fraction; maximumFractionDigits = fraction
            }.format(value)
            val date = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                clear(); set(2026, Calendar.MARCH, 5, 14, 5)
            }.time
            val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT, locale).also {
                if (it is SimpleDateFormat) it.applyPattern(it.toPattern().replace(Regex("y+"), "yyyy"))
                it.calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
            }
            val timeFormat = (twentyFourHour?.let { SimpleDateFormat(if (it) "HH:mm" else "h:mm a", locale) }
                ?: DateFormat.getTimeInstance(DateFormat.SHORT, locale)).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val ru = locale.language == "ru"
            val values = listOf(number(1234567.0), number(12345.67, 2), dateFormat.format(date), timeFormat.format(date),
                if (ru) "2 ч 15 мин" else "2 h 15 min",
                NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(0.125),
                NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("USD"); minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(1234.5),
                number(1.5, 1) + if (ru) " кг" else " kg", if (ru) "5–10 км" else "5–10 km", "3/4")
            val locales = if (automatic) supportedLanguages.associateWith { language ->
                val preferred = preferredLocales.firstOrNull { it.language == language && it.country.isNotEmpty() }
                (preferred ?: if (locale.language == language) locale else if (language == "en") Locale.forLanguageTag("en-" + locale.country.ifEmpty { "US" }) else Locale.forLanguageTag(language)).stripExtensions().toLanguageTag()
            } else null
            return ValueFormatting(ValueKind.entries.zip(values).associate { (kind, example) -> kind to ValueFormat(example = example) }, if (automatic) emptyList() else null, locales)
                .also { require(it.isValid) }
        }
    }
}

internal object PostProcessingRules {
    private val root: JSONObject by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("postprocessing-rules.json")).bufferedReader().use { JSONObject(it.readText()) }
    }
    val punctuation: String get() = root.getString("punctuation")
    val punctuationWithValues: String get() = root.getString("punctuationWithValues")
    val valuesInstructions: String get() = root.getString("valuesInstructions")
    val styleSelection: String get() = root.getString("styleSelection")
    val types: Map<String, JSONObject> by lazy {
        root.getJSONArray("types").let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { type -> type.getString("id") to type } } }
    }
}

internal fun jsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { character -> append(when (character) {
        '"' -> "\\\""; '\\' -> "\\\\"; '\b' -> "\\b"; '\u000c' -> "\\f"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
        else -> if (character.code < 32) "\\u" + character.code.toString(16).padStart(4, '0') else character.toString()
    }) }
    append('"')
}
