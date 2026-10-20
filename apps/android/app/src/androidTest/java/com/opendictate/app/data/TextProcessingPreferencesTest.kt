package com.opendictate.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.TextProcessingModel
import com.opendictate.app.model.TextProcessingServiceTier
import org.junit.Assert.*
import org.junit.Test

class TextProcessingPreferencesTest {
    @Test fun legacyAndSyncedFastPreferencesUseStandard() {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "text-tier-" + java.util.UUID.randomUUID()
        val names = mutableSetOf<String>()
        fun isolated(suffix: String) = object : ContextWrapper(actual) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$prefix-$suffix-$name".also { names += it }, mode)
        }
        try {
            val context = isolated("local")
            val otherContext = isolated("remote")
            context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE).edit()
                .putString("text_processing_model", TextProcessingModel.ID)
                .putString("text_processing_service_tier", "fast").commit()
            val store = SettingsStore(context)
            val other = SettingsStore(otherContext)
            assertEquals(TextProcessingServiceTier.STANDARD, store.textProcessingServiceTier)
            assertEquals(TextProcessingServiceTier.STANDARD, other.textProcessingServiceTier)
            assertEquals(TextProcessingServiceTier.STANDARD, SettingsStore(context).textProcessingServiceTier)
            other.mergeSyncDocument(store.syncDocument(), ReplacementStore(otherContext))
            assertEquals(TextProcessingServiceTier.STANDARD, other.textProcessingServiceTier)
            val legacy = SettingsSyncDocument(mapOf("textProcessingServiceTier" to SettingsSyncEntry("fast", 8_000_000_000_000, "legacy")))
            other.mergeSyncDocument(legacy, ReplacementStore(otherContext))
            assertEquals(TextProcessingServiceTier.STANDARD, SettingsStore(otherContext).textProcessingServiceTier)
            assertEquals(TextProcessingServiceTier.STANDARD, other.textProcessingServiceTier)
            assertEquals(TextProcessingModel.ID, other.textProcessingModel)
            val invalid = SettingsSyncDocument(mapOf("textProcessingServiceTier" to SettingsSyncEntry("invalid", 9_000_000_000_000, "remote"),
                "dictionary" to SettingsSyncEntry("Must not apply", 9_000_000_000_000, "remote")))
            val dictionary = store.prompt
            assertThrows(IllegalArgumentException::class.java) { store.mergeSyncDocument(invalid, ReplacementStore(context)) }
            assertEquals(dictionary, store.prompt)
            assertEquals(TextProcessingServiceTier.STANDARD, store.textProcessingServiceTier)
        } finally {
            names.forEach { actual.deleteSharedPreferences(it) }
        }
    }
}
