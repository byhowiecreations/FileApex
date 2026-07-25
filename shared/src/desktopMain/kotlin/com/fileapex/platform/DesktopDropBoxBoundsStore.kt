package com.fileapex.platform

import java.util.prefs.Preferences

/** SSOT for persisting the macOS drop-box panel frame across launches. */
object DesktopDropBoxBoundsStore {
    private const val PREFS_NODE = "com.fileapex.desktop.dropbox"
    private const val KEY_SAVED = "saved"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    const val DEFAULT_WIDTH_PX = 362
    const val DEFAULT_HEIGHT_PX = 281
    const val MIN_WIDTH_PX = 242
    const val MIN_HEIGHT_PX = 188
    private const val LEGACY_WIDTH_THRESHOLD_PX = 400

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
        val normalized = normalizeLegacyBounds(bounds)
        return if (isValidDropBoxBounds(normalized) && isDropBoxOnScreen(normalized)) {
            normalized
        } else {
            clear()
            null
        }
    }

    fun persistPixels(x: Int, y: Int, width: Int, height: Int) {
        val bounds = normalizeLegacyBounds(DesktopWindowBounds(x = x, y = y, width = width, height = height))
        if (!isValidDropBoxBounds(bounds) || !isDropBoxOnScreen(bounds)) {
            clear()
            return
        }
        prefs.putBoolean(KEY_SAVED, true)
        prefs.putInt(KEY_X, bounds.x)
        prefs.putInt(KEY_Y, bounds.y)
        prefs.putInt(KEY_WIDTH, bounds.width)
        prefs.putInt(KEY_HEIGHT, bounds.height)
        prefs.flush()
    }

    fun clear() {
        prefs.remove(KEY_SAVED)
        prefs.remove(KEY_X)
        prefs.remove(KEY_Y)
        prefs.remove(KEY_WIDTH)
        prefs.remove(KEY_HEIGHT)
    }

    private fun normalizeLegacyBounds(bounds: DesktopWindowBounds): DesktopWindowBounds {
        if (bounds.width <= LEGACY_WIDTH_THRESHOLD_PX && bounds.height <= LEGACY_WIDTH_THRESHOLD_PX) {
            return bounds
        }
        return bounds.copy(
            width = bounds.width.coerceAtMost(DEFAULT_WIDTH_PX),
            height = bounds.height.coerceAtMost(DEFAULT_HEIGHT_PX)
        )
    }

    private fun isValidDropBoxBounds(bounds: DesktopWindowBounds): Boolean =
        bounds.width >= MIN_WIDTH_PX && bounds.height >= MIN_HEIGHT_PX

    /** Native NSPanel frames use AppKit coordinates (origin from screen bottom-left). */
    private fun isDropBoxOnScreen(bounds: DesktopWindowBounds): Boolean {
        if (!isValidDropBoxBounds(bounds)) return false
        return bounds.x > Int.MIN_VALUE + 1 && bounds.y > Int.MIN_VALUE + 1
    }
}
