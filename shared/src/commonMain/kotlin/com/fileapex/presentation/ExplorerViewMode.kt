package com.fileapex.presentation

/**
 * File browser layout — persisted via [com.fileapex.data.settings.AppSettings].
 */
enum class ExplorerViewMode {
    List,
    Grid;

    fun toggled(): ExplorerViewMode = when (this) {
        List -> Grid
        Grid -> List
    }

    companion object {
        fun fromStorage(raw: String?): ExplorerViewMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: List
    }
}
