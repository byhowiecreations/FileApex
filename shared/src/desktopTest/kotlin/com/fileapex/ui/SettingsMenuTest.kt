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
        assertTrue("Notifications is where Critical Notifications lives, so it must stay on Play", ids.contains(SettingsMenuIds.NOTIFICATIONS))
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

    private val advancedIds = listOf(
        SettingsMenuIds.TAILSCALE, SettingsMenuIds.IROH, SettingsMenuIds.DRIVE_RELAY, SettingsMenuIds.PIN_REQUIRED,
        SettingsMenuIds.GOOGLE_ACCOUNT, SettingsMenuIds.BACKUP_SYNC, SettingsMenuIds.LEAVE_CLUSTER
    )

    private fun menu(showAdvanced: Boolean, inUse: Set<String> = emptySet()) = settingsMenuEntries(
        playStoreBuild = false, desktopFileSelection = false, deviceNamePage = true, windowsFluent = false,
        backupSync = true, iroh = true, showAdvanced = showAdvanced, inUseAdvancedIds = inUse
    )

    @Test
    fun advancedRowsAreHiddenUntilTheSwitchIsOn() {
        val ids = menu(showAdvanced = false).map { it.id }
        advancedIds.forEach { assertFalse("$it should be advanced", ids.contains(it)) }
        listOf(
            SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.LANGUAGE, SettingsMenuIds.CHECK_FOR_UPDATES, SettingsMenuIds.REPORT_ISSUE,
            SettingsMenuIds.BACKGROUND_PERSISTENCE, SettingsMenuIds.THEMES, SettingsMenuIds.BULLETIN_BOARD,
            SettingsMenuIds.NOTIFICATIONS, SettingsMenuIds.CLIPBOARD, SettingsMenuIds.DEVICE_DETAILS
        ).forEach { assertTrue("$it should stay basic", ids.contains(it)) }
        val advancedOn = menu(showAdvanced = true).map { it.id }
        advancedIds.forEach { assertTrue(advancedOn.contains(it)) }
    }

    @Test
    fun anAdvancedRowThatIsInUseStaysVisibleWithTheSwitchOff() {
        val ids = menu(showAdvanced = false, inUse = setOf(SettingsMenuIds.TAILSCALE, SettingsMenuIds.PIN_REQUIRED)).map { it.id }
        assertTrue(ids.contains(SettingsMenuIds.TAILSCALE))
        assertTrue(ids.contains(SettingsMenuIds.PIN_REQUIRED))
        assertFalse(ids.contains(SettingsMenuIds.IROH))
    }

    @Test
    fun sectionsThatAreAllAdvancedDisappearWithTheSwitchOff() {
        val sections = menu(showAdvanced = false).map { it.section }.toSet()
        assertFalse(sections.contains(SettingsMenuSection.RemoteAccess))
        assertFalse(sections.contains(SettingsMenuSection.Security))
    }

    @Test
    fun theClusterWipeIsNeverTreatedAsInUse() {
        assertFalse(menu(showAdvanced = false).any { it.id == SettingsMenuIds.LEAVE_CLUSTER })
    }

    private fun shareLinkMenu(shareLink: Boolean, showAdvanced: Boolean = true) = settingsMenuEntries(
        playStoreBuild = false,
        desktopFileSelection = false,
        deviceNamePage = true,
        windowsFluent = false,
        shareLink = shareLink,
        showAdvanced = showAdvanced
    )

    @Test
    fun theShareAsLinkPageIsInSecurityAndAccessControlBeforeLeaveCluster() {
        val ids = shareLinkMenu(true).filter { it.section == SettingsMenuSection.Security }.map { it.id }
        assertTrue(ids.contains(SettingsMenuIds.SHARE_LINK))
        assertTrue(ids.indexOf(SettingsMenuIds.SHARE_LINK) < ids.indexOf(SettingsMenuIds.LEAVE_CLUSTER))
    }

    @Test
    fun theShareAsLinkPageIsHiddenWhereLinksCannotBeMadeAndNeverAnAdvancedRow() {
        assertFalse(shareLinkMenu(false).any { it.id == SettingsMenuIds.SHARE_LINK })
        assertTrue(shareLinkMenu(true, showAdvanced = false).any { it.id == SettingsMenuIds.SHARE_LINK })
        assertFalse(SettingsMenuIds.SHARE_LINK in ADVANCED_IDS)
    }

    @Test
    fun fullDiskAccessIsASecurityRowOnlyWhereTheSystemHasIt() {
        fun entries(on: Boolean) = settingsMenuEntries(
            playStoreBuild = false, desktopFileSelection = true, deviceNamePage = false, windowsFluent = false, fullDiskAccess = on
        ).filter { it.id == SettingsMenuIds.FULL_DISK_ACCESS }
        assertTrue(entries(false).isEmpty())
        assertEquals(listOf(SettingsMenuSection.Security), entries(true).map { it.section })
        assertFalse(SettingsMenuIds.FULL_DISK_ACCESS in ADVANCED_IDS)
        assertEquals(SettingsPage.FullDiskAccess, settingsPageFor(SettingsMenuIds.FULL_DISK_ACCESS))
    }

    private val everyRow = settingsMenuEntries(
        playStoreBuild = false, desktopFileSelection = true, deviceNamePage = true, windowsFluent = true, backupSync = true,
        googleCloud = true, tailscale = true, iroh = true, groups = true, shareLink = true, autoLaunchOnDesktop = true, fullDiskAccess = true
    )

    @Test
    fun everyMenuRowOpensAPageExceptReportAnIssue() {
        everyRow.map { it.id }.filter { it != SettingsMenuIds.REPORT_ISSUE }.forEach {
            assertTrue("$it has no page", settingsPageFor(it) != null)
        }
        assertEquals(null, settingsPageFor(SettingsMenuIds.REPORT_ISSUE))
        assertEquals(null, settingsPageFor("no such row"))
    }

    @Test
    fun rowsOpenThePagesTheyAlwaysOpened() {
        assertEquals(SettingsPage.DeviceName, settingsPageFor(SettingsMenuIds.DEVICE_NAME))
        assertEquals(SettingsPage.AutoLaunchOnReboot, settingsPageFor(SettingsMenuIds.AUTO_LAUNCH))
        assertEquals(SettingsPage.GoogleAccount, settingsPageFor(SettingsMenuIds.GOOGLE_ACCOUNT))
        assertEquals(SettingsPage.LeaveClusterWipe, settingsPageFor(SettingsMenuIds.LEAVE_CLUSTER))
        assertEquals(SettingsPage.Groups, settingsPageFor(SettingsMenuIds.GROUPS))
        assertEquals(SettingsPage.ShareLink, settingsPageFor(SettingsMenuIds.SHARE_LINK))
    }

    @Test
    fun navigationPositionRowRespectsThemeSupportOnDesktop() {
        fun hasNavPosition(navPosition: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = true,
            deviceNamePage = false,
            windowsFluent = false,
            navPosition = navPosition
        ).any { it.id == SettingsMenuIds.DESKTOP_LAYOUT }

        assertTrue(hasNavPosition(true))
        assertFalse(hasNavPosition(false))
    }

    @Test
    fun noTwoRowsShareAPageExceptDriveRelayWhichOpensTheGoogleAccountPage() {
        val pages = everyRow.mapNotNull { e -> settingsPageFor(e.id)?.let { e.id to it } }
        val shared = pages.groupBy({ it.second }, { it.first }).filter { it.value.size > 1 }
        assertEquals(mapOf(SettingsPage.GoogleAccount to setOf(SettingsMenuIds.GOOGLE_ACCOUNT, SettingsMenuIds.DRIVE_RELAY)), shared.mapValues { it.value.toSet() })
    }
}
