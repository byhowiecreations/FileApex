package com.fileapex.data.settings

import androidx.compose.runtime.staticCompositionLocalOf

enum class BulletinBoardStyle(val storageKey: String) {
    DEFAULT("default"),
    IOS_MODERN("ios_modern"),
    MATERIAL_YOU("material_you"),
    AERO_GLASS("aero_glass"),
    TORN_LEDGER("torn_ledger"),
    STICKY_NOTE("sticky_note");

    companion object {
        val DEFAULT_STYLE = DEFAULT

        fun fromStorage(key: String?): BulletinBoardStyle {
            if (key.isNullOrEmpty()) return DEFAULT_STYLE
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?: DEFAULT_STYLE
        }
    }
}

val LocalBulletinBoardStyle = staticCompositionLocalOf { BulletinBoardStyle.DEFAULT }
