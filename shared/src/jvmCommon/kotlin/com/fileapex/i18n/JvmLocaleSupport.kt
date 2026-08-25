package com.fileapex.i18n

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal object JvmLocaleSupport {
    fun javaLocale(locale: AppLocale): Locale = when (locale) {
        AppLocale.EN -> Locale.ENGLISH
        AppLocale.ES -> Locale.forLanguageTag("es")
        AppLocale.ZH_HANS -> Locale.SIMPLIFIED_CHINESE
    }

        // Captured before [apply] overwrites Locale.getDefault — landing/onboarding must
    // still see the OS language after the user has not picked an app language.
    private val hostLanguageTag: String = Locale.getDefault().toLanguageTag()

    fun apply(locale: AppLocale) {
        Locale.setDefault(javaLocale(locale))
    }

    fun systemTag(): String = hostLanguageTag

    fun dateTime(epochMs: Long, zoneId: String, locale: AppLocale): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(javaLocale(locale))
            .format(zoned)
    }

    fun date(epochMs: Long, zoneId: String, locale: AppLocale): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(javaLocale(locale))
            .format(zoned)
    }

    fun time(epochMs: Long, zoneId: String, locale: AppLocale): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(javaLocale(locale))
            .format(zoned)
    }

    fun number(value: Number, locale: AppLocale): String =
        NumberFormat.getNumberInstance(javaLocale(locale)).format(value)
}
