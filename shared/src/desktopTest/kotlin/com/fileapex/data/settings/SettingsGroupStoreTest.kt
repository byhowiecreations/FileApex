package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGroupStoreTest {
    private val kv = MapKvStore()

    @Test
    fun everySectionStartsExpanded() {
        assertTrue(SettingsGroupStore(kv).collapsed.value.isEmpty())
    }

    @Test
    fun collapsingOneSectionLeavesTheOthersAlone() {
        val groups = SettingsGroupStore(kv)
        groups.setExpanded("security", false)
        assertEquals(setOf("security"), groups.collapsed.value)
        groups.setExpanded("appearance", false)
        assertEquals(setOf("security", "appearance"), groups.collapsed.value)
        groups.setExpanded("security", true)
        assertEquals(setOf("appearance"), groups.collapsed.value)
    }

    @Test
    fun theChoiceSurvivesARestart() {
        SettingsGroupStore(kv).setExpanded("system_app", false)
        assertEquals(setOf("system_app"), SettingsGroupStore(kv).collapsed.value)
    }

    @Test
    fun theOldFiveFlagKeysAreNotRead() {
        kv.putBoolean("settings_group_general_expanded", false)
        kv.putBoolean("settings_group_security_account_expanded", false)
        assertTrue(SettingsGroupStore(kv).collapsed.value.isEmpty())
    }

    @Test
    fun resetExpandsEverythingAndForgetsTheChoices() {
        val groups = SettingsGroupStore(kv)
        groups.setExpanded("security", false)
        groups.reset()
        assertTrue(groups.collapsed.value.isEmpty())
        assertTrue(SettingsGroupStore(kv).collapsed.value.isEmpty())
    }

    @Test
    fun theAppSettingsResetAlsoExpandsTheSections() {
        val settings = BaseAppSettings(kv)
        settings.settingsGroups.setExpanded("security", false)
        settings.resetToDefaults()
        assertTrue(settings.settingsGroups.collapsed.value.isEmpty())
    }
}
