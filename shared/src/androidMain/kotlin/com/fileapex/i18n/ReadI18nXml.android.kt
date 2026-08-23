package com.fileapex.i18n

import com.fileapex.data.settings.androidAppContextOrNull
import kotlin.text.Charsets

internal actual fun readI18nXml(locale: AppLocale): String {
    val name = when (locale) {
        AppLocale.EN -> "i18n/en.xml"
        AppLocale.ES -> "i18n/es.xml"
        AppLocale.ZH_HANS -> "i18n/zh-rCN.xml"
    }
    val context = androidAppContextOrNull()
        ?: error("App context missing while loading $name")
    return context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
