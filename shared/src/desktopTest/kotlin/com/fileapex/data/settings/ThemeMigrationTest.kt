package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMigrationTest {

    private class MapStore : SettingsKvStore {
        val map = mutableMapOf<String, Any>()
        override fun contains(key: String) = map.containsKey(key)
        override fun getBoolean(key: String, default: Boolean) = (map[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String) = (map[key] as? String) ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = (map[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun clear() { map.clear() }
    }

    @Test
    fun freshInstallStartsOnSimpleAndStillGetsTheHint() {
        val settings = BaseAppSettings(MapStore(), existingInstall = false)
        assertEquals(AppTheme.SIMPLE, settings.appTheme.value)
        assertFalse(settings.otherThemesHintShown.value)
    }

    @Test
    fun upgradedInstallWithNoStoredThemeIsOnCleanFromTheFirstRead() {
        val settings = BaseAppSettings(MapStore(), existingInstall = true)
        assertEquals(AppTheme.CLEAN, settings.appTheme.value)
        assertTrue(settings.otherThemesHintShown.value)
    }

    @Test
    fun explicitStoredThemeIsNeverChanged() {
        val store = MapStore()
        store.putString(BaseAppSettings.KEY_APP_THEME, AppTheme.FREESTYLE.name)
        val settings = BaseAppSettings(store, existingInstall = true)
        assertEquals(AppTheme.FREESTYLE, settings.appTheme.value)
        assertTrue(settings.otherThemesHintShown.value)
    }

    @Test
    fun decisionIsMadeOnlyOnce() {
        val store = MapStore()
        BaseAppSettings(store, existingInstall = false)
        val second = BaseAppSettings(store, existingInstall = true)
        assertEquals(AppTheme.SIMPLE, second.appTheme.value)
    }

    @Test
    fun wipedInstallWithLeftoverDatabaseStaysOnSimple() {
        val store = MapStore()
        val settings = BaseAppSettings(store, existingInstall = true)
        settings.resetToDefaults()
        settings.markThemeDefaultDecided()
        val afterRestart = BaseAppSettings(store, existingInstall = true)
        assertEquals(AppTheme.SIMPLE, afterRestart.appTheme.value)
    }

    @Test
    fun switchingThemesLeavesPerThemeStateAlone() {
        val settings = BaseAppSettings(MapStore())
        settings.setAppTheme(AppTheme.KINETIC_SPHERE)
        settings.setThemeIconStyle(AppTheme.KINETIC_SPHERE, ThemeIconStyle.FREESTYLE)
        settings.setKineticStyle(KineticStyle.JADED_STEEL)
        settings.setAppTheme(AppTheme.SIMPLE)
        settings.setAppTheme(AppTheme.KINETIC_SPHERE)
        assertEquals(ThemeIconStyle.FREESTYLE, settings.themeIconStyleFor(AppTheme.KINETIC_SPHERE))
        assertEquals(KineticStyle.JADED_STEEL, settings.kineticStyle.value)
    }
}
