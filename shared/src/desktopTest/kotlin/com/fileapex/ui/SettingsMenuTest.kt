package com.fileapex.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuTest {

    @Test
    fun theStartAtSignInRowAppearsOnWindowsAndLinuxButNotOnTheMac() {
        fun hasRow(autoLaunchOnDesktop: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = true,
            deviceNamePage = false,
            windowsFluent = false,
            autoLaunchOnDesktop = autoLaunchOnDesktop
        ).any { it.id == SettingsMenuIds.AUTO_LAUNCH }
        assertTrue(hasRow(true))
        assertFalse(hasRow(false))
    }

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
        assertEquals(SettingsMenuSection.RemoteAccess, sectionOf(SettingsMenuIds.TAILSCALE))
        assertEquals(SettingsMenuSection.RemoteAccess, sectionOf(SettingsMenuIds.DRIVE_RELAY))
        assertEquals(SettingsMenuSection.Appearance, sectionOf(SettingsMenuIds.THEMES))
        assertEquals(SettingsMenuSection.Appearance, sectionOf(SettingsMenuIds.BULLETIN_BOARD))
        assertFalse(entries.any { it.id == SettingsMenuIds.BACKUP_SYNC })
        assertEquals(SettingsMenuSection.Security, sectionOf(SettingsMenuIds.LEAVE_CLUSTER))
        assertEquals(
            listOf(
                SettingsMenuSection.General,
                SettingsMenuSection.SystemPerformance,
                SettingsMenuSection.RemoteAccess,
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

    @Test
    fun googleAccountRowIsHiddenWithoutGoogleCloud() {
        fun ids(googleCloud: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            googleCloud = googleCloud
        ).map { it.id }

        assertTrue(ids(googleCloud = true).contains(SettingsMenuIds.GOOGLE_ACCOUNT))
        assertFalse(ids(googleCloud = false).contains(SettingsMenuIds.GOOGLE_ACCOUNT))
    }

    @Test
    fun tailscaleRowIsHiddenWhenUnavailable() {
        fun ids(tailscale: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            tailscale = tailscale
        ).map { it.id }

        assertTrue(ids(tailscale = true).contains(SettingsMenuIds.TAILSCALE))
        assertFalse(ids(tailscale = false).contains(SettingsMenuIds.TAILSCALE))
    }

    @Test
    fun remoteAccessHoldsTailscaleIrohAndDriveRelayAndIsEmptyWithoutThem() {
        fun ids(tailscale: Boolean, iroh: Boolean, googleCloud: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            googleCloud = googleCloud,
            tailscale = tailscale,
            iroh = iroh
        ).filter { it.section == SettingsMenuSection.RemoteAccess }.map { it.id }

        assertEquals(
            listOf(SettingsMenuIds.TAILSCALE, SettingsMenuIds.IROH, SettingsMenuIds.DRIVE_RELAY),
            ids(tailscale = true, iroh = true, googleCloud = true)
        )
        assertTrue(ids(tailscale = false, iroh = false, googleCloud = false).isEmpty())
    }
}
