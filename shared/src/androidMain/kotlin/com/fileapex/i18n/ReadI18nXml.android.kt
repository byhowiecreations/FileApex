package com.fileapex.i18n

import com.fileapex.data.settings.androidAppContextOrNull
import kotlin.text.Charsets

internal actual fun readI18nXml(locale: AppLocale): String {
    val packaged = i18nResourcePath(locale)
    androidAppContextOrNull()?.let { context ->
        runCatching {
            return context.assets.open(packaged).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    val loader = Thread.currentThread().contextClassLoader ?: AppI18n::class.java.classLoader
    val stream = loader?.getResourceAsStream(packaged)
        ?: loader?.getResourceAsStream("i18n/${i18nFileName(locale)}")
        ?: error("Missing i18n catalog $packaged")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
