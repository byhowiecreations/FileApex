package com.fileapex.platform

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.abs

/** A window frame in AWT screen units, which are the dp values Compose reports on desktop. */
data class WindowFrame(val x: Float, val y: Float, val width: Float, val height: Float)

/** Screen edges taken by the dock, the menu bar or the taskbar. */
data class FrameInsets(val top: Float, val left: Float, val bottom: Float, val right: Float)

object WindowEasing {
    private val curve = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun at(fraction: Float): Float = curve.transform(fraction)
}

object DesktopWindowExpansion {
    private const val SIZE_TOLERANCE = 2f

    /** The screen area a window can use once the dock, menu bar or taskbar are taken out. */
    fun usable(screen: WindowFrame, insets: FrameInsets): WindowFrame = WindowFrame(
        screen.x + insets.left,
        screen.y + insets.top,
        screen.width - insets.left - insets.right,
        screen.height - insets.top - insets.bottom
    )

    /** The wider frame for the file manager inside [usable], or null when the window is already at least [targetWidth] wide. */
    fun expand(current: WindowFrame, usable: WindowFrame, targetWidth: Float): WindowFrame? {
        if (current.width >= targetWidth) return null
        val width = minOf(targetWidth, usable.width)
        val height = minOf(current.height, usable.height)
        val x = current.x.coerceIn(usable.x, usable.x + usable.width - width)
        val y = current.y.coerceIn(usable.y, usable.y + usable.height - height)
        return WindowFrame(x, y, width, height)
    }

    /** The frame to go back to, or null when the user resized the window while it was expanded. */
    fun restore(saved: WindowFrame, expanded: WindowFrame, now: WindowFrame): WindowFrame? =
        if (abs(now.width - expanded.width) <= SIZE_TOLERANCE && abs(now.height - expanded.height) <= SIZE_TOLERANCE) saved else null
}
