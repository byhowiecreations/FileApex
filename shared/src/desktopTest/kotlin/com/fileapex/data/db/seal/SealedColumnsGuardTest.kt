package com.fileapex.data.db.seal

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sealed column can be written and read through the driver, but SQL cannot compare or order it. */
class SealedColumnsGuardTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "shared/src").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "project root not found" }
    }

    private val queries: List<String> by lazy {
        val out = mutableListOf<String>()
        File(root(), "shared/src/commonMain/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            Regex("""@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)""").findAll(text).forEach { m ->
                out += Regex(""""((?:[^"\\]|\\.)*)"""").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] }
            }
        }
        out
    }

    @Test fun noQueryFiltersOrOrdersBySealedColumns() {
        assertTrue("found no queries, the scan is broken", queries.size > 50)
        for (sql in queries) {
            val table = Regex("""(?i)\b(?:FROM|UPDATE|INTO)\s+`?(\w+)""").find(sql)?.groupValues?.get(1) ?: continue
            val sealed = SealedColumns.STAGE_ONE[table] ?: continue
            val clauses = Regex("""(?is)\b(WHERE|ORDER BY|GROUP BY|HAVING|ON)\b(.*)""").find(sql)?.groupValues?.get(2).orEmpty()
            for (column in sealed) {
                assertTrue("query compares or orders by sealed $table.$column: $sql", !Regex("""\b$column\b""").containsMatchIn(clauses))
            }
        }
    }

    @Test fun everySealedColumnIsATextColumnOfTheLatestSchema() {
        val dir = File(root(), "shared/schemas/com.fileapex.data.db.FileApexDatabase")
        val latest = dir.listFiles { f -> f.extension == "json" }!!.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        val entities = Json.parseToJsonElement(latest.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val fields = entities.associate { e ->
            val o = e.jsonObject
            o["tableName"]!!.jsonPrimitive.content to o["fields"]!!.jsonArray.associate { f -> f.jsonObject["columnName"]!!.jsonPrimitive.content to f.jsonObject["affinity"]!!.jsonPrimitive.content }
        }
        for ((table, columns) in SealedColumns.STAGE_ONE) for (column in columns) {
            assertEquals("$table.$column", "TEXT", fields[table]?.get(column))
        }
    }

    @Test fun theLatestSchemaIsTheOneThatSeals() {
        val dir = File(root(), "shared/schemas/com.fileapex.data.db.FileApexDatabase")
        assertTrue(File(dir, "23.json").isFile)
    }
}
