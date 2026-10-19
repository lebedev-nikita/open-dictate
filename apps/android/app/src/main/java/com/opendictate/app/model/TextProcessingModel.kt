package com.opendictate.app.model

object TextProcessingModel {
    const val ID = "gpt-6-luna"
    val defaults = listOf(ID)
    fun title(id: String): String = if (id == ID) "GPT-6 Luna" else id
    fun isValid(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
}
