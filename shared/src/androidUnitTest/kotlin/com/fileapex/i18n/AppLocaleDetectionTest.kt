package com.fileapex.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleDetectionTest {

    @Test
    fun mapsOsTagsToSupportedLandingLocales() {
        assertEquals(AppLocale.ES, AppLocale.fromSystemTag("es-US"))
        assertEquals(AppLocale.ES, AppLocale.fromSystemTag("es"))
        assertEquals(AppLocale.ZH_HANS, AppLocale.fromSystemTag("zh-CN"))
        assertEquals(AppLocale.ZH_HANS, AppLocale.fromSystemTag("zh-Hans-CN"))
        assertEquals(AppLocale.ZH_HANS, AppLocale.fromSystemTag("zh"))
        assertEquals(AppLocale.EN, AppLocale.fromSystemTag("en-US"))
        assertNull(AppLocale.fromSystemTag("fr-FR"))
        assertNull(AppLocale.fromSystemTag(""))
    }
}
