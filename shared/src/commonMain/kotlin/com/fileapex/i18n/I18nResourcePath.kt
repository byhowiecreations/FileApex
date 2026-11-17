package com.fileapex.i18n

internal fun i18nFileName(locale: AppLocale): String = when (locale) {
    AppLocale.EN -> "en.xml"
    AppLocale.ES -> "es.xml"
    AppLocale.ZH_HANS -> "zh-rCN.xml"
}

/**
 * Where Compose packages `commonMain/composeResources/files/i18n` on both the desktop classpath and the Android assets.
 * The package is a literal on purpose: R8 renames the generated `Res` class, so it cannot be read from a class name.
 */
internal fun i18nResourcePath(locale: AppLocale): String =
    "composeResources/fileapex.shared.generated.resources/files/i18n/${i18nFileName(locale)}"
