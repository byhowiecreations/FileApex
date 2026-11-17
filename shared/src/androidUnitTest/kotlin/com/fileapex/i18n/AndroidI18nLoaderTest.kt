package com.fileapex.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidI18nLoaderTest {
    @Test
    fun theLoaderReadsTheMasterCatalogsWithoutAnyCopy() {
        mapOf(AppLocale.EN to "en", AppLocale.ES to "es", AppLocale.ZH_HANS to "zh-rCN").forEach { (locale, file) ->
            assertEquals(
                "readI18nXml $file",
                File("src/commonMain/composeResources/files/i18n/$file.xml").readText(),
                readI18nXml(locale)
            )
        }
    }

    @Test
    fun theAndroidPathPointsInsideThePackagedComposeResources() {
        assertEquals(
            "composeResources/fileapex.shared.generated.resources/files/i18n/zh-rCN.xml",
            i18nResourcePath(AppLocale.ZH_HANS)
        )
    }
}
