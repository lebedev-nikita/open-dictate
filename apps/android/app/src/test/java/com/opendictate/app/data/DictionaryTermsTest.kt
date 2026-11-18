package com.opendictate.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DictionaryTermsTest {
    @Test
    fun `keyword hints clean legacy terms and remove case-insensitive duplicates`() {
        val dictionary = " <OpenDictate>, Jetpack Compose\r\nOPENDICTATE\r\n <> \n"
        assertEquals(listOf("OpenDictate", "Jetpack Compose"), transcriptionKeywords(dictionary))
    }

    @Test
    fun `keyword hints preserve multilingual phrases and punctuation`() {
        assertEquals(listOf("Никита Лебедев", "GPT-6", "C++", "AC-42"),
            transcriptionKeywords("Никита Лебедев\nGPT-6\nC++\nAC-42"))
        assertEquals(emptyList<String>(), transcriptionKeywords(" \r\n<>\n "))
    }

    @Test
    fun `keeps one term per line`() {
        assertEquals(
            "Kotlin\nOpenDictate\nJetpack Compose",
            normalizeDictionaryTerms("Kotlin\nOpenDictate\nJetpack Compose"),
        )
    }

    @Test
    fun `converts legacy comma-separated terms to lines`() {
        assertEquals(
            "Kotlin\nOpenDictate\nJetpack Compose",
            normalizeDictionaryTerms("Kotlin, OpenDictate ,\tJetpack Compose"),
        )
    }

    @Test
    fun `normalizes carriage-return line endings`() {
        assertEquals(
            "Kotlin\nOpenDictate\nCompose",
            normalizeDictionaryTerms("Kotlin\r\nOpenDictate\rCompose"),
        )
    }

    @Test
    fun `sorts terms alphabetically ignoring case`() {
        assertEquals(
            "api\nJetpack Compose\nKotlin\nOpenDictate",
            sortDictionaryTerms("OpenDictate\nKotlin\napi\nJetpack Compose", Locale.ENGLISH),
        )
        assertEquals(
            "ёлка\nЖук\nЛебедев\nНикита",
            sortDictionaryTerms("Никита\nЖук\nЛебедев\nёлка", Locale.forLanguageTag("ru")),
        )
    }

    @Test
    fun `sorting drops blank lines and case-insensitive repeats`() {
        assertEquals(
            "Compose\nKotlin",
            sortDictionaryTerms(" Kotlin \r\n\nkotlin\nCompose, KOTLIN\n", Locale.ENGLISH),
        )
        assertEquals("", sortDictionaryTerms(" \n\n", Locale.ENGLISH))
    }

    @Test
    fun `adds trimmed selected phrase in alphabetical order`() {
        assertEquals(
            DictionaryAddition.Added("Jetpack Compose\nOpenDictate"),
            addDictionaryTerm("OpenDictate", "  Jetpack\n Compose  ", Locale.ENGLISH),
        )
    }

    @Test
    fun `does not add a case-insensitive duplicate`() {
        assertEquals(
            DictionaryAddition.Duplicate,
            addDictionaryTerm("OpenDictate\nJetpack Compose", " jetpack compose "),
        )
    }

    @Test
    fun `does not add duplicate after selection whitespace is normalized`() {
        assertEquals(
            DictionaryAddition.Duplicate,
            addDictionaryTerm("OpenDictate\nJetpack Compose", "  jetpack\n  compose  "),
        )
    }

    @Test
    fun `ignores empty selection`() {
        assertEquals(DictionaryAddition.Empty, addDictionaryTerm("OpenDictate", " \n "))
    }

    @Test
    fun `rejects additions that exceed the editor limit`() {
        assertEquals(
            DictionaryAddition.Full,
            addDictionaryTerm("a".repeat(MAX_DICTIONARY_LENGTH - 1), "word"),
        )
    }

    @Test
    fun `accepts a term that exactly fills the editor limit`() {
        assertEquals(
            DictionaryAddition.Added("a".repeat(MAX_DICTIONARY_LENGTH)),
            addDictionaryTerm("", "a".repeat(MAX_DICTIONARY_LENGTH)),
        )
    }
}
