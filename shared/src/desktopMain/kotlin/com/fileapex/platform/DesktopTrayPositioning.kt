package com.fileapex.platform

import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Calculates on-screen positioning and focus debounce for the Windows tray flyout.
 */
object DesktopTrayPositioning {
    private const val SCREEN_MARGIN = 8

    /**
     * Resolves the usable work area (screen bounds minus taskbar insets) for the monitor containing the anchor.
     */
    fun resolveWorkArea(anchor: Point): Rectangle {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val defaultScreen = ge.defaultScreenDevice.defaultConfiguration
        var targetGc: GraphicsConfiguration = defaultScreen

        for (device in ge.screenDevices) {
            val gc = device.defaultConfiguration
            if (gc.bounds.contains(anchor)) {
                targetGc = gc
                break
            }
        }

        val bounds = targetGc.bounds
        val insets: Insets = try {
            Toolkit.getDefaultToolkit().getScreenInsets(targetGc)
        } catch (_: HeadlessException) {
            Insets(0, 0, 0, 0)
        }

        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom
        )
    }

    /**
     * Calculates the window bounds for a tray popover relative to the cursor anchor point.
     * Positions above the cursor if near the bottom (standard or overflow tray), or below if near the top.
     */
    fun calculateBounds(anchor: Point, size: Dimension, workArea: Rectangle): Rectangle {
        val width = size.width
        val height = size.height

        val minX = workArea.x + SCREEN_MARGIN
        val maxX = (workArea.x + workArea.width - width - SCREEN_MARGIN).coerceAtLeast(minX)
        val x = (anchor.x - width / 2).coerceIn(minX, maxX)

        val minY = workArea.y + SCREEN_MARGIN
        val maxY = (workArea.y + workArea.height - height - SCREEN_MARGIN).coerceAtLeast(minY)
        val inBottomHalf = anchor.y >= workArea.y + workArea.height / 2
        val targetY = if (inBottomHalf) anchor.y - height - SCREEN_MARGIN else anchor.y + SCREEN_MARGIN
        val y = targetY.coerceIn(minY, maxY)

        return Rectangle(x, y, width, height)
    }
}

/**
 * Guards against the tray focus-loss and click-bounce race conditions.
 */
object DesktopTrayFocusGuard {
    private const val BOUNCE_THRESHOLD_MS = 250L

    /**
     * Returns true if a tray click occurred within the bounce window of a focus-loss deactivation.
     */
    fun shouldIgnoreTrayClick(
        lastLostFocusEpochMs: Long,
        nowEpochMs: Long,
        thresholdMs: Long = BOUNCE_THRESHOLD_MS
    ): Boolean {
        if (lastLostFocusEpochMs <= 0L) return false
        val elapsed = nowEpochMs - lastLostFocusEpochMs
        return elapsed in 0 until thresholdMs
    }

    /**
     * Returns true if the popover should dismiss on lost focus.
     * Prevents self-destruction if the window never finished initial activation or is in the settle period.
     */
    fun shouldDismissOnLostFocus(hasGainedFocus: Boolean, isSettling: Boolean): Boolean {
        if (!hasGainedFocus) return false
        if (isSettling) return false
        return true
    }
}
