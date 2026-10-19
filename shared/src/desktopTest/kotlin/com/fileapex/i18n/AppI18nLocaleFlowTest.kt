package com.fileapex.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppI18nLocaleFlowTest {
    @Test
    fun theLocaleIsAPlainFlowThatFollowsSetLocale() {
        val before = AppI18n.locale
        try {
            AppI18n.setLocale(AppLocale.ES)
            assertEquals(AppLocale.ES, AppI18n.localeFlow.value)
            assertEquals(AppLocale.ES, AppI18n.locale)
        } finally {
            AppI18n.setLocale(before)
        }
    }
}
