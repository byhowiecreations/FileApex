package com.fileapex.presentation

/**
 * File browser layout — persisted via [com.fileapex.data.settings.AppSettings].
 */
enum class ExplorerViewMode {
    List,
    Grid,
    Split;

    /** Devices home stays list or grid. */
    fun toggled(): ExplorerViewMode = when (this) {
        List -> Grid
        Grid -> List
        Split -> List
    }

    /** Local Files cycles list, grid, then two navigators. */
    fun cycled(): ExplorerViewMode = when (this) {
        List -> Grid
        Grid -> Split
        Split -> List
    }

    companion object {
        fun fromStorage(raw: String?): ExplorerViewMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: List
    }
}
