package com.fileapex.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AppI18n {
    @Volatile
    private var localeValue: AppLocale = AppLocale.EN
    private val catalogs = ConcurrentHashMap<AppLocale, StringCatalog>()
    // A flow, not a Compose state: a global mutableStateOf created the first time this object is touched belongs to
    // whatever snapshot touched it, and a composition that reads it later can fail with "Reading a state that was
    // created after the snapshot was taken" (seen at startup on the Mac).
    private val localeState = MutableStateFlow(AppLocale.EN)

    val locale: AppLocale
        get() = localeValue

    val localeFlow: StateFlow<AppLocale> get() = localeState.asStateFlow()

    /** Parses the active locale (and the English fallback); other locales load on first use. */
    fun ensureLoaded() {
        catalog(localeValue)
        catalog(AppLocale.EN)
    }

    private fun catalog(locale: AppLocale): StringCatalog =
        catalogs[locale] ?: synchronized(catalogs) {
            catalogs.getOrPut(locale) { parseStringXml(readI18nXml(locale)) }
        }

    fun setLocale(next: AppLocale) {
        catalog(next)
        localeValue = next
        localeState.value = next
        applyPlatformLocale(next)
        onAppLocaleChanged()
    }

    /** Catalog snapshot for native tray / share-extension chrome that cannot call [t] directly. */
    fun runtimeOverlayJson(): String {
        val catalog = catalog(locale)
        val stringsObj = buildJsonObject {
            catalog.snapshotStrings().forEach { (key, value) -> put(key, value) }
        }
        val pluralsObj = buildJsonObject {
            catalog.snapshotPlurals().forEach { (key, forms) ->
                put(
                    key,
                    buildJsonObject {
                        forms.zero?.let { put("zero", it) }
                        forms.one?.let { put("one", it) }
                        forms.two?.let { put("two", it) }
                        forms.few?.let { put("few", it) }
                        forms.many?.let { put("many", it) }
                        put("other", forms.other)
                    }
                )
            }
        }
        return JsonObject(
            mapOf(
                "strings" to stringsObj,
                "plurals" to pluralsObj
            )
        ).toString()
    }

    /** Localized UI copy. Dialogs, toasts, banners, and labels must use this (or [stringRes]) — never hardcoded English. */
    fun t(key: String, vararg args: Any): String {
        val current = locale
        val raw = catalog(current).string(key)
            ?: (if (current != AppLocale.EN) catalog(AppLocale.EN).string(key) else null)
            ?: key
        return formatTemplate(raw, args)
    }

    fun plural(key: String, count: Int, vararg args: Any): String {
        val current = locale
        val raw = catalog(current).plural(key, count, current)
            ?: (if (current != AppLocale.EN) catalog(AppLocale.EN).plural(key, count, AppLocale.EN) else null)
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

internal expect fun readI18nXml(locale: AppLocale): String
