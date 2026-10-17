package com.opendictate.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TranscriptionPromptStorageTest {
    @Test fun untouchedDefaultsAreNotExportedButCustomAndResetPersistAndMerge() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "prompt-${UUID.randomUUID()}"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += "$prefix-$name"
                return base.getSharedPreferences("$prefix-$name", mode)
            }
        }
        try {
            val settings = SettingsStore(context)
            assertFalse(settings.syncDocument().entries.containsKey("transcriptionPrompt"))
            settings.prompt = "Dictionary"
            assertFalse(settings.syncDocument().entries.containsKey("transcriptionPrompt"))
            settings.customTranscriptionPrompt = "Точные слова 📝\nExactly."
            val stale = settings.syncDocument()
            val snapshot = settings.customTranscriptionPrompt
            settings.customTranscriptionPrompt = null
            assertEquals("Точные слова 📝\nExactly.", snapshot)
            val reset = SettingsStore(context).syncDocument()
            assertTrue(reset.entries.containsKey("transcriptionPrompt"))
            assertNull(reset.entries.getValue("transcriptionPrompt").value)
            settings.mergeSyncDocument(stale, ReplacementStore(context))
            assertNull(settings.customTranscriptionPrompt)
            settings.customTranscriptionPrompt = ""
            assertEquals("", SettingsStore(context).customTranscriptionPrompt)
            assertThrows(IllegalArgumentException::class.java) { settings.customTranscriptionPrompt = "я".repeat(16384) }
            assertEquals("", settings.customTranscriptionPrompt)
        } finally { names.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    }
}
