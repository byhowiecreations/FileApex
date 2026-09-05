package com.fileapex.data.settings

/**
 * The 3 distinct layout modes for the Freestyle theme.
 */
enum class FreestyleLayoutMode(val storageKey: String) {
    CARDS_HORIZONTAL("cards_horizontal"),
    CARDS_VERTICAL("cards_vertical"),
    TILES("tiles");

    fun next(): FreestyleLayoutMode = when (this) {
        CARDS_HORIZONTAL -> CARDS_VERTICAL
        CARDS_VERTICAL -> TILES
        TILES -> CARDS_HORIZONTAL
    }

    val isTile: Boolean get() = this == TILES
    val isCard: Boolean get() = this != TILES

    companion object {
        val DEFAULT = CARDS_HORIZONTAL

        fun fromStorage(raw: String?): FreestyleLayoutMode {
            if (raw.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull {
                it.storageKey.equals(raw.trim(), ignoreCase = true) ||
                it.name.equals(raw.trim(), ignoreCase = true)
            } ?: DEFAULT
        }
    }
}
