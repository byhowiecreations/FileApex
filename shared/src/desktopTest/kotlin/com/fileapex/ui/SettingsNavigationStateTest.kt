package com.fileapex.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationStateTest {
    @Test
    fun theOpenPageStartsAtTheRoot() {
        assertEquals(SettingsPage.Root, SettingsNavigationState().page.value)
    }

    @Test
    fun theOpenPageSurvivesAHostSwitchBecauseTheHolderIsTheSameObject() {
        val holder = SettingsNavigationState()
        holder.page.value = SettingsPage.BulletinBoardStyles

        // The wide layout and the compact route both read the same holder from the activity's view model.
        val asSeenByTheOtherHost = holder.page.value

        assertEquals(SettingsPage.BulletinBoardStyles, asSeenByTheOtherHost)
    }
}

class SettingsPageTreeTest {
    @Test
    fun everyPageReachesTheRootAndOnlyTheRootLeavesSettings() {
        SettingsPage.entries.forEach { start ->
            var page = start
            var steps = 0
            while (page.parent != null) {
                page = page.parent!!
                steps++
                org.junit.Assert.assertTrue("${start.name} loops", steps <= SettingsPage.entries.size)
            }
            assertEquals("${start.name} ends at the root", SettingsPage.Root, page)
        }
        assertEquals(listOf(SettingsPage.Root), SettingsPage.entries.filter { it.parent == null })
    }

    @Test
    fun subPagesReturnToTheirOwnMenu() {
        assertEquals(SettingsPage.Notifications, SettingsPage.BroadcastNotifications.parent)
        assertEquals(SettingsPage.Notifications, SettingsPage.FileTransferNotifications.parent)
        assertEquals(SettingsPage.BulletinBoard, SettingsPage.BulletinBoardStyles.parent)
        assertEquals(SettingsPage.BulletinBoard, SettingsPage.RemoteFileDeletion.parent)
        assertEquals(SettingsPage.Clipboard, SettingsPage.ClipboardDiagnostics.parent)
        assertEquals(SettingsPage.Clipboard, SettingsPage.ClipboardShareTargets.parent)
        assertEquals(SettingsPage.Clipboard, SettingsPage.ClipboardAccessibility.parent)
    }
}
