package com.fileapex.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings exist in three places the runtime reads from (compose resources, desktop resources, Android assets).
 * `commonMain/composeResources` is the one to edit; the other two must be byte-for-byte copies of it, and every
 * language must carry the same keys.
 */
class I18nCopiesTest {
    private val languages = listOf("en", "es", "zh-rCN")
    private val master = File("src/commonMain/composeResources/files/i18n")
    private val copies = listOf(File("src/desktopMain/resources/i18n"), File("src/androidMain/assets/i18n"))

    @Test
    fun theCopiesMatchTheMaster() {
        val drifted = languages.flatMap { language ->
            copies.filter { File(it, "$language.xml").readText() != File(master, "$language.xml").readText() }
                .map { "${it.path}/$language.xml differs from ${master.path}/$language.xml; copy the master over it" }
        }
        assertTrue(drifted.joinToString("\n"), drifted.isEmpty())
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
