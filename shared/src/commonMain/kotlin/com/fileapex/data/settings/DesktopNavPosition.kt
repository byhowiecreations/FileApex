package com.fileapex.data.settings

/**
 * Preferred placement of the navigation bar on desktop hosts for themes with a navigation bar.
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

object DesktopNavPositionPolicy {
    /**
     * Themes that render a dockable navigation bar (rail or bottom bar) on desktop.
     * Canvas-based Freestyle and dock-based Carousel manage navigation directly without a rail or bar.
     */
    fun supportsTheme(theme: AppTheme): Boolean = when (theme) {
        AppTheme.SIMPLE,
        AppTheme.KINETIC_SPHERE,
        AppTheme.CLEAN,
        AppTheme.FLUX_GLASS -> true
        AppTheme.FREESTYLE,
        AppTheme.CAROUSEL -> false
    }

    fun showHeaderToggle(theme: AppTheme, isDesktop: Boolean): Boolean =
        isDesktop && supportsTheme(theme)

    fun resolve(
        theme: AppTheme,
        isDesktop: Boolean,
        windowIsWide: Boolean,
        desktopPosition: DesktopNavPosition
    ): DesktopNavPosition = when {
        !supportsTheme(theme) -> DesktopNavPosition.Bottom
        isDesktop -> desktopPosition
        windowIsWide -> DesktopNavPosition.Left
        else -> DesktopNavPosition.Bottom
    }
}
