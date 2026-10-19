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
    internal fun instruction(format: ValueFormat) = "{\"example\":${jsonQuote(format.example)},\"reference\":${jsonQuote(definition.getString("reference"))},\"rule\":${jsonQuote(definition.getString("rule"))},\"type\":${jsonQuote(id)}}"
}

data class ValueFormatting(val entries: Map<ValueKind, ValueFormat>) {
    val isValid: Boolean get() = entries.keys == ValueKind.entries.toSet() && entries.values.all { it.isValid }
    val hasEnabledTypes: Boolean get() = entries.values.any { it.enabled }
    fun updating(kind: ValueKind, format: ValueFormat) = copy(entries = entries + (kind to format))
    internal val instruction: String get() = PostProcessingRules.valuesInstructions + "\n\n" +
        ValueKind.entries.filter { entries.getValue(it).enabled }.joinToString(",", "[", "]") { it.instruction(entries.getValue(it)) }
    internal fun json(): String = entries.entries.sortedBy { it.key.id }.joinToString(",", "{", "}") { (kind, format) ->
        "${jsonQuote(kind.id)}:{\"enabled\":${format.enabled},\"example\":${jsonQuote(format.example)}}"
    }
    companion object {
        fun fromJson(json: JSONObject): ValueFormatting {
            require(json.length() == ValueKind.entries.size)
            return ValueFormatting(ValueKind.entries.associateWith { kind ->
                val value = json.getJSONObject(kind.id)
                require(value.get("enabled") is Boolean && value.get("example") is String)
                ValueFormat(value.getBoolean("enabled"), value.getString("example"))
            }).also { require(it.isValid) }
        }
        /** Called with the system region, never the in-app language override. Persist the result on first use. */
        fun systemDefaults(locale: Locale, twentyFourHour: Boolean? = null): ValueFormatting {
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
            return ValueFormatting(ValueKind.entries.zip(values).associate { (kind, example) -> kind to ValueFormat(example = example) })
                .also { require(it.isValid) }
        }
    }
}

internal object PostProcessingRules {
    private val root: JSONObject by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("postprocessing-rules.json")).bufferedReader().use { JSONObject(it.readText()) }
    }
    val punctuation: String get() = root.getString("punctuation")
    val valuesInstructions: String get() = root.getString("valuesInstructions")
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
