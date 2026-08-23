package com.fileapex.i18n

actual fun systemLanguageTag(): String = JvmLocaleSupport.systemTag()

actual fun applyPlatformLocale(locale: AppLocale) {
    JvmLocaleSupport.apply(locale)
}

actual fun formatLocalizedDateTime(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.dateTime(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedDate(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.date(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedTime(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.time(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedNumber(value: Number): String =
    JvmLocaleSupport.number(value, AppI18n.locale)
