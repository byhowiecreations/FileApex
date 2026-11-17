package com.fileapex.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The release build renames classes (R8 turned `fileapex.shared.generated.resources.Res` into `o7`), so the packaged
 * catalog path must never be built from a class name. Unit tests do not run R8, so the source itself is guarded.
 */
class I18nResourcePathTest {
    @Test
    fun theCatalogPathIsNotDerivedFromAClassName() {
        val source = File("src/commonMain/kotlin/com/fileapex/i18n/I18nResourcePath.kt").readText()
        assertFalse("path must not use reflection on Res", source.contains("Res::class"))
        assertFalse("path must not use class names", source.contains(".java.name"))
    }

    @Test
    fun thePathIsTheLiteralPackagePathComposeGenerates() {
        assertEquals(
            "composeResources/fileapex.shared.generated.resources/files/i18n/en.xml",
            i18nResourcePath(AppLocale.EN)
        )
    }

    @Test
    fun thatLiteralMatchesThePackageOfTheGeneratedResClass() {
        assertNotNull(Class.forName("fileapex.shared.generated.resources.Res"))
    }
}
