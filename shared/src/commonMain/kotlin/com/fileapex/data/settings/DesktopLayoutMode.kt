package com.fileapex.data.settings

/**
 * Desktop window layout preference — overrides width-based adaptive breakpoints.
 */
enum class DesktopLayoutMode {
    Compact,
    Expanded;

    val label: String
        get() = when (this) {
            Compact -> "Compact"
            Expanded -> "Expanded"
        }

    companion object {
        val DEFAULT: DesktopLayoutMode = Compact

        fun fromStorage(raw: String): DesktopLayoutMode {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
