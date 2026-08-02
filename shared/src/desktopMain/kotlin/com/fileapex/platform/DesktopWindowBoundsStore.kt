package com.fileapex.platform

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.prefs.Preferences
import kotlin.math.roundToInt

/**
 * Last known desktop window bounds in physical pixels (AWT / screen space).
 */
data class DesktopWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    fun toDpSize(): DpSize = DpSize(
        width = DesktopUiMetrics.pixelsToDp(width),
        height = DesktopUiMetrics.pixelsToDp(height)
    )

    fun toWindowPosition(): WindowPosition = WindowPosition(
        x = DesktopUiMetrics.pixelsToDp(x),
        y = DesktopUiMetrics.pixelsToDp(y)
    )
}

/** SSOT for persisting FileApex desktop window size and location across launches. */
object DesktopWindowBoundsStore {
    private const val PREFS_NODE = "com.fileapex.desktop.window"
    private const val KEY_SAVED = "saved"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    private val prefs: Preferences =
        Preferences.userRoot().node(PREFS_NODE)

    fun loadValidated(): DesktopWindowBounds? {
        if (!prefs.getBoolean(KEY_SAVED, false)) return null
        val bounds = DesktopWindowBounds(
            x = prefs.getInt(KEY_X, Int.MIN_VALUE),
            y = prefs.getInt(KEY_Y, Int.MIN_VALUE),
            width = prefs.getInt(KEY_WIDTH, 0),
            height = prefs.getInt(KEY_HEIGHT, 0)
        )
        return if (DesktopWindowBoundsValidator.isOnScreen(bounds)) {
            bounds
        } else {
            clear()
            null
        }
    }

    fun hasValidSaved(): Boolean = loadValidated() != null

    fun persist(size: DpSize, position: WindowPosition) {
        if (position.x == Dp.Unspecified || position.y == Dp.Unspecified) return
        val widthPx = DesktopUiMetrics.dpToPixels(size.width)
        val heightPx = DesktopUiMetrics.dpToPixels(size.height)
        val xPx = DesktopUiMetrics.dpToPixels(position.x)
        val yPx = DesktopUiMetrics.dpToPixels(position.y)
        persistPixels(xPx, yPx, widthPx, heightPx)
    }

    private fun persistPixels(x: Int, y: Int, width: Int, height: Int) {
        val bounds = DesktopWindowBounds(x = x, y = y, width = width, height = height)
        if (!DesktopWindowBoundsValidator.isOnScreen(bounds)) {
            clear()
            return
        }
        prefs.putBoolean(KEY_SAVED, true)
        prefs.putInt(KEY_X, x)
        prefs.putInt(KEY_Y, y)
        prefs.putInt(KEY_WIDTH, width)
        prefs.putInt(KEY_HEIGHT, height)
    }

    fun clear() {
        prefs.remove(KEY_SAVED)
        prefs.remove(KEY_X)
        prefs.remove(KEY_Y)
        prefs.remove(KEY_WIDTH)
        prefs.remove(KEY_HEIGHT)
    }
}

object DesktopUiMetrics {
    fun scaleFactor(): Float =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()

    fun pixelsToDp(pixels: Int): Dp = (pixels / scaleFactor()).dp

    fun dpToPixels(dp: Dp): Int = (dp.value * scaleFactor()).roundToInt()
}

object DesktopWindowBoundsValidator {
    private const val MIN_WIDTH_PX = 320
    private const val MIN_HEIGHT_PX = 400
    private const val MIN_VISIBLE_WIDTH_PX = 120
    private const val MIN_VISIBLE_HEIGHT_PX = 48

    fun isOnScreen(bounds: DesktopWindowBounds): Boolean {
        if (bounds.width < MIN_WIDTH_PX || bounds.height < MIN_HEIGHT_PX) return false
        val windowRect = Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val topLeftOnScreen = screens.any { device ->
            device.defaultConfiguration.bounds.contains(bounds.x, bounds.y)
        }
        if (!topLeftOnScreen) return false
        return screens.any { device ->
            val visible = windowRect.intersection(device.defaultConfiguration.bounds)
            visible.width >= MIN_VISIBLE_WIDTH_PX && visible.height >= MIN_VISIBLE_HEIGHT_PX
        }
    }
}

object DesktopScreenGeometry {
    /** Primary display top-left in global screen coordinates (AWT pixels). */
    fun primaryTopLeft(): DesktopWindowBounds {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        return DesktopWindowBounds(
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height
        )
    }

    fun primaryTopLeftPosition(): WindowPosition {
        val origin = primaryTopLeft()
        return WindowPosition(
            x = DesktopUiMetrics.pixelsToDp(origin.x),
            y = DesktopUiMetrics.pixelsToDp(origin.y)
        )
    }
}
