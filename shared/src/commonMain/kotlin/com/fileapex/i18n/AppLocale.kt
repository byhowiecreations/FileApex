package com.fileapex.i18n

enum class AppLocale(
    val tag: String,
    val language: String,
    val region: String?,
    val englishName: String,
    val nativeName: String
) {
    EN("en", "en", null, "English", "English"),
    ES("es", "es", null, "Spanish", "Español"),
    ZH_HANS("zh-Hans", "zh", "CN", "Simplified Chinese", "简体中文");

    companion object {
        fun fromStorage(raw: String): AppLocale? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.tag.equals(cleaned, ignoreCase = true) ||
                    it.language.equals(cleaned, ignoreCase = true)
            }
        }

        fun fromSystemTag(tag: String): AppLocale? {
            val lower = tag.trim().lowercase().replace('_', '-')
            if (lower.startsWith("es")) return ES
            if (lower.startsWith("zh-hans") ||
                lower.startsWith("zh-cn") ||
                lower.startsWith("zh-sg") ||
                lower == "zh"
            ) {
                return ZH_HANS
            }
            if (lower.startsWith("en")) return EN
            return null
        }
    }
}

expect fun systemLanguageTag(): String
expect fun applyPlatformLocale(locale: AppLocale)
expect fun formatLocalizedDateTime(epochMs: Long, zoneId: String): String
expect fun formatLocalizedDate(epochMs: Long, zoneId: String): String
expect fun formatLocalizedTime(epochMs: Long, zoneId: String): String
expect fun formatLocalizedNumber(value: Number): String
internal expect fun onAppLocaleChanged()
