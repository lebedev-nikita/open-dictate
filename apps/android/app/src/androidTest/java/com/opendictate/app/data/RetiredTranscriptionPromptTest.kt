package com.opendictate.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RetiredTranscriptionPromptTest {
    @Test fun legacyPromptsAreRemovedWithoutChangingPostProcessing() {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "retired-prompt-" + java.util.UUID.randomUUID()
        val context = object : ContextWrapper(actual) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + "-" + name, mode)
        }
        try {
            val prefs = context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_transcription_prompt", "Old instructions")
                .putBoolean("transcription_prompt_configured", true)
                .putString("transformation_model", "SOL").putString("transformation_model_id", "gpt-6-sol").commit()
            val store = SettingsStore(context)
            assertFalse(prefs.contains("custom_transcription_prompt"))
            assertFalse(prefs.contains("transcription_prompt_configured"))
            assertFalse(prefs.contains("transformation_model"))
            assertFalse(prefs.contains("transformation_model_id"))
            val original = store.postProcessing
            val remote = SettingsSyncDocument(mapOf(
                "transcriptionPrompt" to SettingsSyncEntry("Ignored", 500, "old"),
                "dictionary" to SettingsSyncEntry("Updated", 500, "old")))
            assertNull(store.mergeSyncDocument(remote, ReplacementStore(context)).entries["transcriptionPrompt"])
            assertEquals("Updated", store.prompt)
            assertEquals(original, store.postProcessing)
        } finally {
            actual.deleteSharedPreferences(prefix + "-opendictate_settings")
            actual.deleteSharedPreferences(prefix + "-word_replacements")
        }
    }
}
