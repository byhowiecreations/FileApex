package com.fileapex.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuTest {

    @Test
    fun phoneMenuPlacesMovedRowsInTheRequestedSections() {
        val entries = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false
        )
        fun sectionOf(id: String) = entries.first { it.id == id }.section

        assertEquals(SettingsMenuSection.General, sectionOf(SettingsMenuIds.DEVICE_NAME))
        assertEquals(SettingsMenuSection.General, sectionOf(SettingsMenuIds.LANGUAGE))
        assertEquals(SettingsMenuSection.General, sectionOf(SettingsMenuIds.CHECK_FOR_UPDATES))
        assertEquals(SettingsMenuSection.General, sectionOf(SettingsMenuIds.REPORT_ISSUE))
        assertEquals(SettingsMenuSection.SystemPerformance, sectionOf(SettingsMenuIds.BACKGROUND_PERSISTENCE))
        assertEquals(SettingsMenuSection.SystemPerformance, sectionOf(SettingsMenuIds.AUTO_LAUNCH))
        assertEquals(SettingsMenuSection.SystemPerformance, sectionOf(SettingsMenuIds.TAILSCALE))
        assertEquals(SettingsMenuSection.Appearance, sectionOf(SettingsMenuIds.THEMES))
        assertEquals(SettingsMenuSection.Appearance, sectionOf(SettingsMenuIds.BULLETIN_BOARD))
        assertFalse(entries.any { it.id == SettingsMenuIds.BACKUP_SYNC })
        assertEquals(SettingsMenuSection.Security, sectionOf(SettingsMenuIds.LEAVE_CLUSTER))
        assertEquals(
            listOf(
                SettingsMenuSection.General,
                SettingsMenuSection.SystemPerformance,
                SettingsMenuSection.Appearance,
                SettingsMenuSection.Security
            ),
            entries.map { it.section }.distinct()
        )
    }

    @Test
    fun playAndDesktopFlagsHidePlatformRows() {
        val entries = settingsMenuEntries(
            playStoreBuild = true,
            desktopFileSelection = true,
            deviceNamePage = false,
            windowsFluent = true
        )
        val ids = entries.map { it.id }
        assertFalse(ids.contains(SettingsMenuIds.CHECK_FOR_UPDATES))
        assertFalse(ids.contains(SettingsMenuIds.AUTO_LAUNCH))
        assertFalse(ids.contains(SettingsMenuIds.DEVICE_NAME))
        assertTrue(ids.contains(SettingsMenuIds.DESKTOP_LAYOUT))
        assertTrue(ids.contains(SettingsMenuIds.WINDOWS_DESIGN))
        assertTrue(ids.contains(SettingsMenuIds.TAILSCALE))
    }

    @Test
    fun backupSyncLivesUnderGeneralWhenSupported() {
        val entries = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            backupSync = true
        )
        assertEquals(SettingsMenuSection.General, entries.first { it.id == SettingsMenuIds.BACKUP_SYNC }.section)
    }
}
