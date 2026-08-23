package com.fileapex.i18n

import com.fileapex.di.FileApexServices

fun applyStoredAppLanguage() {
    AppI18n.ensureLoaded()
    val stored = AppLocale.fromStorage(FileApexServices.settings.appLanguageTag.value)
    if (stored != null) {
        AppI18n.setLocale(stored)
    } else {
        AppI18n.setLocale(AppLocale.EN)
    }
}

fun persistAppLanguage(locale: AppLocale) {
    FileApexServices.settings.setAppLanguageTag(locale.tag)
    AppI18n.setLocale(locale)
}

fun needsLanguagePrompt(): Boolean {
    if (FileApexServices.settings.appLanguageTag.value.isNotBlank()) return false
    val detected = AppLocale.fromSystemTag(systemLanguageTag())
    return detected == AppLocale.ES || detected == AppLocale.ZH_HANS
}

fun detectedPromptLocale(): AppLocale {
    return AppLocale.fromSystemTag(systemLanguageTag()) ?: AppLocale.EN
}

fun defaultLanguageIfNoPrompt() {
    if (FileApexServices.settings.appLanguageTag.value.isNotBlank()) return
    persistAppLanguage(AppLocale.EN)
}
