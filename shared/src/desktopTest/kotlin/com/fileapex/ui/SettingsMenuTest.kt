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
    fun rowsSitInTheSectionsOfTheOwnersLayout() {
        val entries = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = true,
            deviceNamePage = true,
            windowsFluent = true,
            backupSync = true,
            iroh = true,
            groups = true,
            shareLink = true,
            autoLaunchOnDesktop = true,
            fullDiskAccess = true
        )
        fun idsIn(section: SettingsMenuSection) = entries.filter { it.section == section }.map { it.id }

        assertEquals(
            listOf(SettingsMenuIds.THEMES, SettingsMenuIds.DESKTOP_LAYOUT, SettingsMenuIds.WINDOWS_DESIGN),
            idsIn(SettingsMenuSection.Appearance)
        )
        assertEquals(
            listOf(
                SettingsMenuIds.BACKUP_SYNC, SettingsMenuIds.CLIPBOARD, SettingsMenuIds.BULLETIN_BOARD,
                SettingsMenuIds.GROUPS, SettingsMenuIds.NOTIFICATIONS
            ),
            idsIn(SettingsMenuSection.SyncNotifications)
        )
        assertEquals(
            listOf(
                SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.DEVICE_DETAILS, SettingsMenuIds.TAILSCALE,
                SettingsMenuIds.IROH, SettingsMenuIds.DRIVE_RELAY
            ),
            idsIn(SettingsMenuSection.DevicesConnectivity)
        )
        assertEquals(
            listOf(
                SettingsMenuIds.PIN_REQUIRED, SettingsMenuIds.SHARE_LINK, SettingsMenuIds.GOOGLE_ACCOUNT,
                SettingsMenuIds.FULL_DISK_ACCESS, SettingsMenuIds.LEAVE_CLUSTER
            ),
            idsIn(SettingsMenuSection.Security)
        )
        assertEquals(
            listOf(
                SettingsMenuIds.LANGUAGE, SettingsMenuIds.BACKGROUND_PERSISTENCE, SettingsMenuIds.AUTO_LAUNCH,
                SettingsMenuIds.CHECK_FOR_UPDATES
            ),
            idsIn(SettingsMenuSection.SystemApp)
        )
        assertEquals(listOf(SettingsMenuIds.REPORT_ISSUE), idsIn(SettingsMenuSection.Utility))
        assertEquals(
            listOf(
                SettingsMenuSection.Appearance,
                SettingsMenuSection.SyncNotifications,
                SettingsMenuSection.DevicesConnectivity,
                SettingsMenuSection.Security,
                SettingsMenuSection.SystemApp,
                SettingsMenuSection.Utility
            ),
            SettingsMenuSection.entries
        )
    }

    @Test
    fun everyRowIsInExactlyOneSection() {
        val ids = settingsMenuEntries(
            playStoreBuild = false, desktopFileSelection = true, deviceNamePage = true, windowsFluent = true,
            backupSync = true, iroh = true, groups = true, shareLink = true, autoLaunchOnDesktop = true, fullDiskAccess = true
        ).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theTitlesOfTheSectionsAreTheOwnersNames() {
        assertEquals("appearance", SettingsMenuSection.Appearance.titleKey())
        assertEquals("sync_notifications", SettingsMenuSection.SyncNotifications.titleKey())
        assertEquals("devices_connectivity", SettingsMenuSection.DevicesConnectivity.titleKey())
        assertEquals("security_privacy", SettingsMenuSection.Security.titleKey())
        assertEquals("system_app", SettingsMenuSection.SystemApp.titleKey())
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
    fun backupSyncLivesUnderSyncAndNotificationsWhenSupported() {
        val entries = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            backupSync = true
        )
        assertEquals(SettingsMenuSection.SyncNotifications, entries.first { it.id == SettingsMenuIds.BACKUP_SYNC }.section)
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
    fun connectivityHoldsTheTwoDeviceRowsAndTheThreeRemoteRows() {
        fun ids(tailscale: Boolean, iroh: Boolean, googleCloud: Boolean) = settingsMenuEntries(
            playStoreBuild = false,
            desktopFileSelection = false,
            deviceNamePage = true,
            windowsFluent = false,
            googleCloud = googleCloud,
            tailscale = tailscale,
            iroh = iroh
        ).filter { it.section == SettingsMenuSection.DevicesConnectivity }.map { it.id }

        assertEquals(
            listOf(
                SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.DEVICE_DETAILS, SettingsMenuIds.TAILSCALE,
                SettingsMenuIds.IROH, SettingsMenuIds.DRIVE_RELAY
            ),
            ids(tailscale = true, iroh = true, googleCloud = true)
        )
        assertEquals(
            listOf(SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.DEVICE_DETAILS),
            ids(tailscale = false, iroh = false, googleCloud = false)
        )
    }

    private val advancedIds = listOf(
        SettingsMenuIds.TAILSCALE, SettingsMenuIds.IROH, SettingsMenuIds.DRIVE_RELAY, SettingsMenuIds.PIN_REQUIRED,
        SettingsMenuIds.GOOGLE_ACCOUNT, SettingsMenuIds.LEAVE_CLUSTER
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
            SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.LANGUAGE, SettingsMenuIds.BACKUP_SYNC, SettingsMenuIds.CHECK_FOR_UPDATES, SettingsMenuIds.REPORT_ISSUE,
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
    fun onlyTheAllAdvancedSectionDisappearsWithTheSwitchOff() {
        val sections = menu(showAdvanced = false).map { it.section }.toSet()
        assertFalse(sections.contains(SettingsMenuSection.Security))
        assertTrue("Device Name and Device Details stay visible", sections.contains(SettingsMenuSection.DevicesConnectivity))
        val devices = menu(showAdvanced = false).filter { it.section == SettingsMenuSection.DevicesConnectivity }.map { it.id }
        assertEquals(listOf(SettingsMenuIds.DEVICE_NAME, SettingsMenuIds.DEVICE_DETAILS), devices)
    }

    @Test
    fun theAdvancedSetStaysTheSixRowsItWas() {
        assertEquals(advancedIds.toSet(), ADVANCED_IDS)
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
    fun theShareAsLinkPageIsInSecurityAndPrivacyBeforeLeaveCluster() {
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
