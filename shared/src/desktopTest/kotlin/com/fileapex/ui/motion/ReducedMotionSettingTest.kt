package com.fileapex.ui.motion

import com.fileapex.data.settings.MotionSettings
import com.fileapex.data.settings.ReducedMotionSetting
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionSettingTest {
    @Test
    fun `System follows the operating system and On and Off override it`() {
        assertTrue(ReducedMotionPolicy.resolve(ReducedMotionSetting.System, systemReduced = true))
        assertFalse(ReducedMotionPolicy.resolve(ReducedMotionSetting.System, systemReduced = false))
        assertTrue(ReducedMotionPolicy.resolve(ReducedMotionSetting.On, systemReduced = false))
        assertFalse(ReducedMotionPolicy.resolve(ReducedMotionSetting.Off, systemReduced = true))
    }

    @Test
    fun `the setting defaults to System and an unknown stored value falls back to System`() {
        assertEquals(ReducedMotionSetting.System, ReducedMotionSetting.fromStorage(""))
        assertEquals(ReducedMotionSetting.System, ReducedMotionSetting.fromStorage("nonsense"))
        assertEquals(ReducedMotionSetting.On, ReducedMotionSetting.fromStorage("on"))
        assertEquals(ReducedMotionSetting.Off, ReducedMotionSetting.fromStorage("off"))
    }

    @Test
    fun `the choice is stored and read back`() {
        val store = MapKvStore()
        val settings = MotionSettings(store)
        assertEquals(ReducedMotionSetting.System, settings.reduceMotion.value)
        settings.setReduceMotion(ReducedMotionSetting.On)
        assertEquals(ReducedMotionSetting.On, settings.reduceMotion.value)
        assertEquals(ReducedMotionSetting.On, MotionSettings(store).reduceMotion.value)
    }

    @Test
    fun `gsettings output tells whether GNOME animations are on`() {
        assertEquals(true, LinuxAnimationsSetting.parse("true\n"))
        assertEquals(false, LinuxAnimationsSetting.parse("false\n"))
        assertEquals(false, LinuxAnimationsSetting.parse("  false  "))
        assertNull(LinuxAnimationsSetting.parse(""))
        assertNull(LinuxAnimationsSetting.parse("No such schema"))
    }

    @Test
    fun `the desktop window moves at once when motion is reduced`() {
        assertEquals(0L, FileApexMotion.windowMoveMillis(reduced = true))
        assertEquals(220L, FileApexMotion.windowMoveMillis(reduced = false))
    }
}
