package com.fileapex.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class I18nCatalogParityTest {

    @Test
    fun englishSpanishAndChineseShareKeysPlaceholdersAndHaveNoDuplicates() {
        val catalogs = listOf("en", "es", "zh-rCN").associateWith { load(it) }
        val en = catalogs.getValue("en")
        catalogs.forEach { (locale, catalog) ->
            assertTrue("$locale has no duplicate string names", catalog.duplicateNames.isEmpty())
            assertEquals(
                "locale $locale keys must match en.xml",
                en.names,
                catalog.names
            )
        }
        en.values.keys.forEach { name ->
            val enPh = placeholders(en.values.getValue(name))
            catalogs.forEach { (locale, catalog) ->
                assertEquals(
                    "placeholders for $name in $locale",
                    enPh,
                    placeholders(catalog.values.getValue(name))
                )
            }
        }
    }

    private fun load(locale: String): ParsedCatalog {
        val path = "i18n/$locale.xml"
        val loader = javaClass.classLoader
        assertNotNull("class loader", loader)
        val xml = requireNotNull(loader).getResourceAsStream(path)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing test catalog $path")
        val names = mutableListOf<String>()
        val values = linkedMapOf<String, String>()
        STRING_RE.findAll(xml).forEach { match ->
            val name = match.groupValues[1]
            names += name
            values[name] = match.groupValues[2]
        }
        PLURAL_RE.findAll(xml).forEach { match ->
            names += match.groupValues[1]
        }
        return ParsedCatalog(
            names = names.toSet(),
            values = values,
            duplicateNames = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        )
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER_RE.findAll(value).map { it.value }.toList()

    private data class ParsedCatalog(
        val names: Set<String>,
        val values: Map<String, String>,
        val duplicateNames: Set<String>
    )

    private companion object {
        val STRING_RE = Regex("""<string\s+name="([^"]+)">([\s\S]*?)</string>""")
        val PLURAL_RE = Regex("""<plurals\s+name="([^"]+)">""")
        val PLACEHOLDER_RE = Regex("""%\d+\$[sd]|%[sd]""")
    }
}
