package com.fileapex.data.settings

/**
 * Preferred placement of the navigation bar in the Simple theme on desktop hosts.
 */
enum class DesktopNavPosition {
    Left,
    Bottom,
    Right;

    fun next(): DesktopNavPosition = when (this) {
        Left -> Bottom
        Bottom -> Right
        Right -> Left
    }

    companion object {
        val DEFAULT: DesktopNavPosition = Left

        fun fromStorage(raw: String): DesktopNavPosition =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}
