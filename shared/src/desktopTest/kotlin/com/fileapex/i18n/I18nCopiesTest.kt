package com.fileapex.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings live in one place, `commonMain/composeResources/files/i18n`. Compose packages that folder into the
 * desktop classpath and the Android assets, and `readI18nXml` reads it from there, so no second copy may exist.
 */
class I18nCopiesTest {
    private val languages = listOf("en", "es", "zh-rCN")
    private val master = File("src/commonMain/composeResources/files/i18n")
    private val forbiddenCopies = listOf(
        File("src/desktopMain/resources/i18n"),
        File("src/androidMain/assets/i18n"),
        File("src/androidUnitTest/resources/i18n")
    )

    @Test
    fun noSecondCopyOfTheStringFilesExists() {
        val found = forbiddenCopies.filter { it.exists() }.map { it.path }
        assertTrue("These folders duplicate the master string files: $found", found.isEmpty())
    }

    @Test
    fun theRuntimeReadsTheMasterFromItsPackagedLocation() {
        languages.forEach { language ->
            val locale = AppLocale.entries.first { i18nFileName(it) == "$language.xml" }
            val packaged = AppI18n::class.java.getResourceAsStream("/" + i18nResourcePath(locale))
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            assertEquals("packaged $language.xml", File(master, "$language.xml").readText(), packaged)
            assertEquals("readI18nXml $language", File(master, "$language.xml").readText(), readI18nXml(locale))
        }
    }

    @Test
    fun everyLanguageHasTheSameKeys() {
        val keyPattern = Regex("""<string\s+name="([^"]+)"""")
        val keys = languages.associateWith { language -> keyPattern.findAll(File(master, "$language.xml").readText()).map { it.groupValues[1] }.toSet() }
        val english = keys.getValue("en")
        val problems = languages.filter { it != "en" }.flatMap { language ->
            val missing = english - keys.getValue(language)
            val extra = keys.getValue(language) - english
            listOfNotNull(
                missing.takeIf { it.isNotEmpty() }?.let { "$language is missing ${it.size} keys, e.g. ${it.sorted().take(5)}" },
                extra.takeIf { it.isNotEmpty() }?.let { "$language has ${it.size} keys English lacks, e.g. ${it.sorted().take(5)}" }
            )
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }
}
