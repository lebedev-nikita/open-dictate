package com.opendictate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFilterScreenTest {
    @Test
    fun filtersByDisplayNameAsUserTypes() {
        val apps = listOf(
            InstalledApp("com.example.notes", "Notes"),
            InstalledApp("com.example.mail", "Mail"),
        )

        assertEquals(listOf(apps[0]), filterAndPrioritizeApps(apps, "  NOt  ", emptySet()))
        assertEquals(apps, filterAndPrioritizeApps(apps, "", emptySet()))
        assertEquals(emptyList<InstalledApp>(), filterAndPrioritizeApps(apps, "example", emptySet()))
    }

    @Test
    fun selectedAppsStayAtTopWhileFiltering() {
        val apps = listOf(
            InstalledApp("calendar", "Calendar"),
            InstalledApp("camera", "Camera"),
            InstalledApp("maps", "Maps"),
        )

        assertEquals(
            listOf(apps[2], apps[0], apps[1]),
            filterAndPrioritizeApps(apps, "", setOf("maps")),
        )
        assertEquals(
            listOf(apps[1], apps[0]),
            filterAndPrioritizeApps(apps, "ca", setOf("camera")),
        )
    }
}
