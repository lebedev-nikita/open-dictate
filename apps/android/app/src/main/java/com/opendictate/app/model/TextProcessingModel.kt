package com.opendictate.app.model

enum class TextProcessingServiceTier(val apiValue: String) {
    STANDARD("default"), FAST("fast");

    companion object {
        fun fromValue(value: String?): TextProcessingServiceTier? = entries.firstOrNull { it.apiValue == value }
    }
}

object TextProcessingModel {
    const val ID = "gpt-6-luna"
    const val FAST_CHOICE = "gpt-6-luna:fast"
    val defaults = listOf(ID, FAST_CHOICE)
    fun title(choice: String): String = when (choice) {
        FAST_CHOICE -> "GPT-6 Luna Fast"
        ID -> "GPT-6 Luna"
        else -> choice
    }
    fun selection(model: String, tier: TextProcessingServiceTier): String =
        if (model == ID && tier == TextProcessingServiceTier.FAST) FAST_CHOICE else model
    fun model(choice: String): String = if (choice == FAST_CHOICE) ID else choice
    fun tier(choice: String): TextProcessingServiceTier =
        if (choice == FAST_CHOICE) TextProcessingServiceTier.FAST else TextProcessingServiceTier.STANDARD
    fun isValid(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
}
