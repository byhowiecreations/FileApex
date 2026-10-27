package com.fileapex.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDeviceNameActionsTest {
    @Test
    fun draftKeepsLettersDigitsSpacesAndHyphens() {
        assertEquals("Magic v5 - 2", SettingsDeviceNameActions.filterDraft("Magic v5 - 2"))
    }

    @Test
    fun draftDropsEverythingElse() {
        assertEquals("Cliffs Mac", SettingsDeviceNameActions.filterDraft("Cliff's Mac!?<>/\\_"))
    }

    @Test
    fun accentedLettersAreKept() {
        assertEquals("Café", SettingsDeviceNameActions.filterDraft("Café"))
    }
}
