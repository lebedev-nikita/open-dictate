package com.opendictate.app.model

import org.junit.Assert.*
import org.junit.Test

class AppFilterTest {
    @Test fun defaultAndLegacyModesKeepExclusions() {
        for (stored in listOf(null, "", "unknown", AppFilterMode.ALL_EXCEPT_SELECTED.name)) {
            val filter = AppFilter(
                mode = AppFilterMode.fromStored(stored),
                excludedPackages = setOf("hidden"),
                allowedPackages = setOf("hidden"),
            )
            assertFalse(filter.allows("hidden"))
            assertTrue(filter.allows("notes"))
            assertTrue(filter.allows("com.android.settings"))
        }
    }

    @Test fun allowModeRejectsEverythingExceptExactSelectedPackages() {
        val filter = AppFilter(
            mode = AppFilterMode.fromStored("ONLY_SELECTED"),
            allowedPackages = setOf("notes"),
            excludedPackages = setOf("notes"),
        )
        assertTrue(filter.allows(StringBuilder("notes")))
        for (name in listOf("mail", "notes.beta", "Notes", "com.android.settings", "com.android.systemui")) {
            assertFalse(filter.allows(name))
        }
        assertFalse(filter.copy(allowedPackages = emptySet()).allows("notes"))
    }

    @Test fun missingPackageIsNeverEligible() {
        AppFilterMode.entries.forEach { mode ->
            val filter = AppFilter(mode = mode, allowedPackages = setOf("", " "))
            assertFalse(filter.allows(null))
            assertFalse(filter.allows(""))
            assertFalse(filter.allows(" "))
        }
    }

    @Test fun switchingModesAndEditingSelectionPreservesTheOtherList() {
        val excluded = AppFilter().withPackageSelected("mail", true)
        val allowed = excluded.copy(mode = AppFilterMode.ONLY_SELECTED)
            .withPackageSelected("notes", true)
        assertEquals(setOf("mail"), allowed.excludedPackages)
        assertEquals(setOf("notes"), allowed.selectedPackages)
        assertTrue(allowed.allows("notes"))
        assertFalse(allowed.allows("mail"))
        val switchedBack = allowed.copy(mode = AppFilterMode.ALL_EXCEPT_SELECTED)
        assertEquals(excluded.selectedPackages, switchedBack.selectedPackages)
        assertTrue(switchedBack.allows("notes"))
        assertFalse(switchedBack.allows("mail"))
        assertEquals(emptySet<String>(), switchedBack.withPackageSelected("mail", false).excludedPackages)
        assertEquals(setOf("notes"), switchedBack.withPackageSelected("mail", false).allowedPackages)
        assertEquals(emptySet<String>(), allowed.withPackageSelected("notes", false).allowedPackages)
    }
}
