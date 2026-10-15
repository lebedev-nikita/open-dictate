package com.opendictate.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.model.AppFilterMode
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AppFilterSettingsTest {
    @Test fun legacyExclusionsSurviveModeChangesAndStayOutsideSync() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "app-filter-test-${UUID.randomUUID()}"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "$prefix-$name"
                names += scoped
                return base.getSharedPreferences(scoped, mode)
            }
        }
        try {
            val prefs = context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("excluded_packages", setOf("mail")).commit()
            val settings = SettingsStore(context)
            val legacy = settings.appFilter
            assertEquals(AppFilterMode.ALL_EXCEPT_SELECTED, legacy.mode)
            assertFalse(legacy.allows("mail"))
            assertTrue(legacy.allows("notes"))
            assertTrue(legacy.allowedPackages.isEmpty())
            val beforeSync = settings.syncDocument()
            val observed = mutableListOf<com.opendictate.app.model.AppFilter>()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val observer = settings.observeAppFilter { observed += it }
            val allowed = legacy.copy(mode = AppFilterMode.ONLY_SELECTED).withPackageSelected("notes", true)
            instrumentation.runOnMainSync { settings.appFilter = allowed }
            assertEquals(allowed, SettingsStore(context).appFilter)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it == allowed })
            observer.close()
            val count = observed.size
            instrumentation.runOnMainSync { settings.appFilter = allowed.copy(mode = legacy.mode) }
            assertEquals(count, observed.size)
            assertEquals(setOf("mail"), settings.appFilter.selectedPackages)
            assertEquals(setOf("notes"), settings.appFilter.allowedPackages)
            assertEquals(beforeSync, settings.syncDocument())
            assertEquals(0L, prefs.getLong("sync_local_revision", 0))
            prefs.edit().putString("app_filter_mode", "unknown").commit()
            assertEquals(legacy.mode, settings.appFilter.mode)
        } finally {
            names.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
