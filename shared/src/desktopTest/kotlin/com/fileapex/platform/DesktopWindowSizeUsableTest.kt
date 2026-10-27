package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWindowSizeUsableTest {
    @Test
    fun aWideWindowOnARetinaScreenIsUsable() {
        // 1024 x 576 points saved as 2048 x 1152 on a 1512 x 982 point screen.
        assertTrue(DesktopWindowBoundsValidator.sizeUsable(2048, 1152, 1512, 982, 2f))
        assertTrue(DesktopWindowBoundsValidator.sizeUsable(2900, 1900, 1512, 982, 2f))
    }

    @Test
    fun aWindowLargerThanTheScreenIsNot() {
        assertFalse(DesktopWindowBoundsValidator.sizeUsable(3200, 1152, 1512, 982, 2f))
        assertFalse(DesktopWindowBoundsValidator.sizeUsable(2048, 2100, 1512, 982, 2f))
    }

    @Test
    fun tinyWindowsAreNot() {
        assertFalse(DesktopWindowBoundsValidator.sizeUsable(300, 900, 1512, 982, 2f))
        assertFalse(DesktopWindowBoundsValidator.sizeUsable(900, 300, 1512, 982, 2f))
    }

    @Test
    fun anUnscaledScreenStillWorks() {
        assertTrue(DesktopWindowBoundsValidator.sizeUsable(1280, 720, 1920, 1080, 1f))
        assertFalse(DesktopWindowBoundsValidator.sizeUsable(2000, 720, 1920, 1080, 1f))
    }
}
