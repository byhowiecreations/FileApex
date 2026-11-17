package com.fileapex.i18n

import kotlin.text.Charsets

internal actual fun readI18nXml(locale: AppLocale): String {
    val name = "/" + i18nResourcePath(locale)
    val stream = AppI18n::class.java.getResourceAsStream(name)
        ?: error("Missing $name on classpath")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
