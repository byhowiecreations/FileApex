package com.fileapex.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.platform.AndroidNotificationChannels
import com.fileapex.platform.DirectShareShortcutCoordinator
import com.fileapex.platform.ShareServerPendingStart

actual fun systemLanguageTag(): String {
    val configLocale = android.content.res.Resources.getSystem().configuration.locales[0]
    return configLocale?.toLanguageTag() ?: JvmLocaleSupport.systemTag()
}

actual fun applyPlatformLocale(locale: AppLocale) {
    JvmLocaleSupport.apply(locale)
    val context = androidAppContextOrNull() ?: return
    val javaLocale = JvmLocaleSupport.javaLocale(locale)
    val resources = context.resources
    val config = Configuration(resources.configuration)
    config.setLocale(javaLocale)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(javaLocale))
    }
    @Suppress("DEPRECATION")
    resources.updateConfiguration(config, resources.displayMetrics)
}

internal actual fun onAppLocaleChanged() {
    val context = androidAppContextOrNull() ?: return
    AndroidNotificationChannels.refreshLocalized(context)
    ShareServerPendingStart.refreshLocalized(context)
    LocaleChromeRefresh.fire()
    if (FileApexServices.isDatabaseReady()) {
        DirectShareShortcutCoordinator.refreshFromPeerDiscovery()
        com.fileapex.update.AppUpdateCoordinator.republishPendingNotificationIfNeeded()
    }
}

actual fun formatLocalizedDateTime(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.dateTime(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedDate(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.date(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedTime(epochMs: Long, zoneId: String): String =
    JvmLocaleSupport.time(epochMs, zoneId, AppI18n.locale)

actual fun formatLocalizedNumber(value: Number): String =
    JvmLocaleSupport.number(value, AppI18n.locale)

fun Context.withAppLocale(locale: AppLocale): Context {
    val javaLocale = JvmLocaleSupport.javaLocale(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(javaLocale)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(javaLocale))
    }
    return createConfigurationContext(config)
}
