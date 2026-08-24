package com.fileapex.i18n

import com.fileapex.data.settings.androidAppContextOrNull
import kotlin.text.Charsets

internal actual fun readI18nXml(locale: AppLocale): String {
    val name = when (locale) {
        AppLocale.EN -> "i18n/en.xml"
        AppLocale.ES -> "i18n/es.xml"
        AppLocale.ZH_HANS -> "i18n/zh-rCN.xml"
    }
    androidAppContextOrNull()?.let { context ->
        runCatching {
            return context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    val loader = Thread.currentThread().contextClassLoader ?: AppI18n::class.java.classLoader
    val stream = loader?.getResourceAsStream(name)
        ?: loader?.getResourceAsStream("files/$name")
        ?: error("Missing i18n catalog $name")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
