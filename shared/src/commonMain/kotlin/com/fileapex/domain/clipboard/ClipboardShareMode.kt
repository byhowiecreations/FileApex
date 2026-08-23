package com.fileapex.domain.clipboard

enum class ClipboardShareMode {
    UNSET,
    ALL,
    SPECIFIC;

    companion object {
        fun fromStorage(raw: String): ClipboardShareMode {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return UNSET
            return entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) } ?: UNSET
        }
    }
}
