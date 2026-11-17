package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDefaultDecisionTest {
    private val kv = MapKvStore()
    private val styles = ThemeStyleStore(kv)

    @Test
    fun aNewInstallStoresNoThemeAndKeepsTheHintForLater() {
        styles.decideDefaultTheme(existingInstall = false)
        assertFalse(kv.contains(BaseAppSettings.KEY_APP_THEME))
        assertFalse(kv.getBoolean(BaseAppSettings.KEY_OTHER_THEMES_HINT_SHOWN, false))
        assertTrue(styles.defaultThemeDecided())
    }

    @Test
    fun anExistingInstallWithoutAThemeKeepsTheDefaultAndSkipsTheHint() {
        styles.decideDefaultTheme(existingInstall = true)
        assertEquals(AppTheme.DEFAULT.name, kv.getString(BaseAppSettings.KEY_APP_THEME, ""))
        assertTrue(kv.getBoolean(BaseAppSettings.KEY_OTHER_THEMES_HINT_SHOWN, false))
    }

    @Test
    fun aStoredThemeIsNeverChangedAndSkipsTheHint() {
        kv.putString(BaseAppSettings.KEY_APP_THEME, AppTheme.FREESTYLE.name)
        styles.decideDefaultTheme(existingInstall = false)
        assertEquals(AppTheme.FREESTYLE.name, kv.getString(BaseAppSettings.KEY_APP_THEME, ""))
        assertTrue(kv.getBoolean(BaseAppSettings.KEY_OTHER_THEMES_HINT_SHOWN, false))
    }

    @Test
    fun theDecisionIsMadeOnce() {
        styles.decideDefaultTheme(existingInstall = false)
        styles.decideDefaultTheme(existingInstall = true)
        assertFalse("the second call must not run", kv.contains(BaseAppSettings.KEY_APP_THEME))
    }

    @Test
    fun markingTheDecisionMadeStopsAnyLaterDecision() {
        styles.markDefaultDecided()
        styles.decideDefaultTheme(existingInstall = true)
        assertFalse(kv.contains(BaseAppSettings.KEY_APP_THEME))
    }
}
