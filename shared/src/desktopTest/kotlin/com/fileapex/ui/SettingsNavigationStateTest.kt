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
