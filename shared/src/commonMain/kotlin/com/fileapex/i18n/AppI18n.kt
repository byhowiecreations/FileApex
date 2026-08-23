package com.fileapex.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf

object AppI18n {
    @Volatile
    private var localeValue: AppLocale = AppLocale.EN
    private val catalogs = mutableMapOf<AppLocale, StringCatalog>()
    private val localeState = mutableStateOf(AppLocale.EN)

    val locale: AppLocale
        get() = localeValue

    val localeFlowState get() = localeState

    fun ensureLoaded() {
        if (catalogs.isNotEmpty()) return
        AppLocale.entries.forEach { loc ->
            catalogs[loc] = parseStringXml(readI18nXml(loc))
        }
    }

    fun setLocale(next: AppLocale) {
        ensureLoaded()
        localeValue = next
        localeState.value = next
        applyPlatformLocale(next)
    }

    fun t(key: String, vararg args: Any): String {
        ensureLoaded()
        val raw = catalogs[locale]?.string(key)
            ?: catalogs[AppLocale.EN]?.string(key)
            ?: key
        return formatTemplate(raw, args)
    }

    fun plural(key: String, count: Int, vararg args: Any): String {
        ensureLoaded()
        val raw = catalogs[locale]?.plural(key, count, locale)
            ?: catalogs[AppLocale.EN]?.plural(key, count, AppLocale.EN)
            ?: t(key, *args)
        return formatTemplate(raw, if (args.isEmpty()) arrayOf(count) else args)
    }

    fun languageRowLabel(target: AppLocale): String {
        val translated = when (target) {
            AppLocale.EN -> t("language_english")
            AppLocale.ES -> t("language_spanish")
            AppLocale.ZH_HANS -> t("language_chinese_simplified")
        }
        return if (translated == target.nativeName) {
            target.nativeName
        } else {
            "$translated / ${target.nativeName}"
        }
    }
}

val LocalAppLocale = staticCompositionLocalOf { AppLocale.EN }

@Composable
fun ProvideAppLocale(locale: AppLocale, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLocale provides locale) {
        content()
    }
}

@Composable
fun stringRes(key: String, vararg args: Any): String {
    LocalAppLocale.current
    return AppI18n.t(key, *args)
}

@Composable
fun pluralRes(key: String, count: Int, vararg args: Any): String {
    LocalAppLocale.current
    return AppI18n.plural(key, count, *args)
}

internal expect fun readI18nXml(locale: AppLocale): String
