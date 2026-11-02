package com.fileapex.platform

import java.awt.Rectangle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWindowOnScreenTest {
    private fun bounds(x: Int, y: Int, w: Int, h: Int) = DesktopWindowBounds(x, y, w, h)

    /** A 1024 by 768 display at 200% reports a 512 by 384 screen to Java. */
    private val scaledScreen = listOf(Rectangle(0, 0, 512, 384))

    @Test
    fun aWindowSavedOnAScaledDisplayIsOnScreenWhenItsUnscaledPositionIs() {
        assertTrue(DesktopWindowBoundsValidator.isOnScreen(bounds(700, 100, 600, 500), scaledScreen, 2f))
    }

    @Test
    fun aWindowBeyondTheScaledScreenIsStillOffScreen() {
        assertFalse(DesktopWindowBoundsValidator.isOnScreen(bounds(2000, 100, 600, 500), scaledScreen, 2f))
        assertFalse(DesktopWindowBoundsValidator.isOnScreen(bounds(100, 900, 600, 500), scaledScreen, 2f))
    }

    @Test
    fun withoutScalingTheCheckIsUnchanged() {
        val screen = listOf(Rectangle(0, 0, 1920, 1080))
        assertTrue(DesktopWindowBoundsValidator.isOnScreen(bounds(100, 100, 600, 500), screen, 1f))
        assertFalse(DesktopWindowBoundsValidator.isOnScreen(bounds(3000, 100, 600, 500), screen, 1f))
    }

    @Test
    fun aWindowTooSmallToUseIsNotKept() {
        assertFalse(DesktopWindowBoundsValidator.isOnScreen(bounds(10, 10, 100, 100), listOf(Rectangle(0, 0, 1920, 1080)), 1f))
    }

    @Test
    fun aWindowOnASecondScreenCountsWhenItsCornerIsOnThatScreen() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 1920, 1080))
        assertTrue(DesktopWindowBoundsValidator.isOnScreen(bounds(2200, 100, 600, 500), screens, 1f))
    }
}
