package com.fileapex.data.settings

/**
 * Desktop visual style preference (Windows). Other platforms ignore this and stay on [Standard].
 */
enum class DesktopUiStyle {
    /** Cross-platform Material look (matches Android). */
    Standard,
    /** Windows 11 Fluent-inspired styling (Mica-capable chrome, rounded surfaces, Segoe). */
    WindowsFluent;

    val label: String
        get() = when (this) {
            Standard -> "Standard"
            WindowsFluent -> "Windows 11 Modern"
        }

    companion object {
        val DEFAULT: DesktopUiStyle = Standard

        fun fromStorage(raw: String?): DesktopUiStyle =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
