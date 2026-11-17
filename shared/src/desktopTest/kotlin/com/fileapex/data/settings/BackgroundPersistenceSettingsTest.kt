package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPersistenceSettingsTest {
    private val kv = MapKvStore()

    @Test
    fun backgroundPersistenceAndRebootLaunchStartOnAndDesktopAutostartStartsOff() {
        val s = BaseAppSettings(kv)
        assertTrue(s.enableServiceWatchdog.value)
        assertTrue(s.autoLaunchOnReboot.value)
        assertFalse(s.desktopAutostart.value)
    }

    @Test
    fun theRebootLaunchChoiceIsItsOwnAndNoLongerFollowsTheWatchdog() {
        BaseAppSettings(kv).setEnableServiceWatchdog(false)
        assertTrue(BaseAppSettings(kv).autoLaunchOnReboot.value)
        BaseAppSettings(kv).setAutoLaunchOnReboot(false)
        assertFalse(BaseAppSettings(kv).autoLaunchOnReboot.value)
        assertFalse(BaseAppSettings(kv).enableServiceWatchdog.value)
    }
}
