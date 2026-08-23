package com.fileapex.i18n

import kotlin.text.Charsets

internal actual fun readI18nXml(locale: AppLocale): String {
    val name = when (locale) {
        AppLocale.EN -> "/i18n/en.xml"
        AppLocale.ES -> "/i18n/es.xml"
        AppLocale.ZH_HANS -> "/i18n/zh-rCN.xml"
    }
    val stream = AppI18n::class.java.getResourceAsStream(name)
        ?: error("Missing $name on classpath")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
