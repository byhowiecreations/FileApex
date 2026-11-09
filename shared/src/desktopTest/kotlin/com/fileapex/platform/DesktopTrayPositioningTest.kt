package com.fileapex.platform

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTrayPositioningTest {

    private val workArea = Rectangle(0, 0, 1920, 1040) // 1080p display with 40px taskbar at bottom
    private val popoverSize = Dimension(270, 320)

    @Test
    fun placesAboveCursorWhenCursorNearBottom() {
        val cursor = Point(1600, 1030) // Near bottom right in taskbar
        val bounds = DesktopTrayPositioning.calculateBounds(cursor, popoverSize, workArea)

        // y should be above cursor: 1030 - 320 - 8 = 702
        assertEquals(702, bounds.y)
        // x centered around 1600, within workArea
        assertEquals(1600 - 270 / 2, bounds.x)
        assertEquals(270, bounds.width)
        assertEquals(320, bounds.height)
    }

    @Test
    fun placesAboveCursorWhenInOverflowFlyoutAboveTaskbar() {
        val cursor = Point(1750, 950) // Inside overflow flyout ~90px above bottom taskbar
        val bounds = DesktopTrayPositioning.calculateBounds(cursor, popoverSize, workArea)

        // y should sit above cursor: 950 - 320 - 8 = 622
        assertEquals(622, bounds.y)
        assertEquals(1750 - 270 / 2, bounds.x)
    }

    @Test
    fun placesBelowCursorWhenCursorNearTop() {
        val topWorkArea = Rectangle(0, 40, 1920, 1040) // Top taskbar
        val cursor = Point(1000, 20)
        val bounds = DesktopTrayPositioning.calculateBounds(cursor, popoverSize, topWorkArea)

        // y should be below cursor inside topWorkArea
        assertEquals(40 + 8, bounds.y)
        assertEquals(1000 - 270 / 2, bounds.x)
    }

    @Test
    fun clampsHorizontallyWithinScreenMargins() {
        val leftCursor = Point(50, 1020)
        val leftBounds = DesktopTrayPositioning.calculateBounds(leftCursor, popoverSize, workArea)
        assertEquals(8, leftBounds.x) // clamped to margin

        val rightCursor = Point(1915, 1020)
        val rightBounds = DesktopTrayPositioning.calculateBounds(rightCursor, popoverSize, workArea)
        assertEquals(1920 - 270 - 8, rightBounds.x) // clamped to right edge margin
    }

    @Test
    fun focusGuardIgnoresBounceWithinThreshold() {
        val lastLost = 1000L
        assertTrue(DesktopTrayFocusGuard.shouldIgnoreTrayClick(lastLostFocusEpochMs = lastLost, nowEpochMs = 1100L))
        assertFalse(DesktopTrayFocusGuard.shouldIgnoreTrayClick(lastLostFocusEpochMs = lastLost, nowEpochMs = 1350L))
        assertFalse(DesktopTrayFocusGuard.shouldIgnoreTrayClick(lastLostFocusEpochMs = 0L, nowEpochMs = 1000L))
    }

    @Test
    fun focusDismissRequiresActivationAndSettleCompletion() {
        // Not settled yet: do not dismiss
        assertFalse(DesktopTrayFocusGuard.shouldDismissOnLostFocus(hasGainedFocus = true, isSettling = true))
        // Never gained focus: do not dismiss
        assertFalse(DesktopTrayFocusGuard.shouldDismissOnLostFocus(hasGainedFocus = false, isSettling = false))
        // Gained focus and settled: dismiss allowed
        assertTrue(DesktopTrayFocusGuard.shouldDismissOnLostFocus(hasGainedFocus = true, isSettling = false))
    }
}
