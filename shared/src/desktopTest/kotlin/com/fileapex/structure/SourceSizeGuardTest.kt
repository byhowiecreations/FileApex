package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps files and functions from growing back into monoliths. A file over [FILE_LIMIT] lines or a function over
 * [FUNCTION_LIMIT] lines must be listed in `source-size-baseline.txt` and may not grow past its listed size.
 * Splitting one shrinks it for good: run with UPDATE_SIZE_BASELINE=1 to rewrite the list from the current code.
 */
class SourceSizeGuardTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin")
        .map { File(it) }.filter { it.exists() }
    private val baselineFile = File("source-size-baseline.txt")

    private val functionStart = Regex(
        """^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:private|internal|public|override|suspend|inline|protected|open|actual|expect|operator|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\("""
    )

    private fun clean(line: String): String =
        line.replace(Regex(""""\"\"\".*?\"\"\""""), "\"\"")
            .replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")
            .replace(Regex("""'(\\.|[^'\\])'"""), "''")
            .replace(Regex("""//.*"""), "")

    /** Function name to its longest block body, in lines, for functions whose body is a `{ }` block. */
    private fun functionSizes(lines: List<String>): Map<String, Int> {
        val cleaned = lines.map(::clean)
        val sizes = HashMap<String, Int>()
        var i = 0
        while (i < lines.size) {
            val match = functionStart.find(lines[i])
            if (match == null) { i++; continue }
            val name = match.groupValues[1]
            var paren = 0
            var line = i
            var col = cleaned[i].indexOf("fun")
            var bodyStart = -1
            var scanning = true
            while (scanning && line < cleaned.size) {
                val text = cleaned[line]
                while (col < text.length) {
                    val ch = text[col]
                    when {
                        ch == '(' -> paren++
                        ch == ')' -> paren--
                        paren == 0 && ch == '{' -> { bodyStart = line; scanning = false }
                        paren == 0 && ch == '=' && !text.startsWith("=>", col) -> scanning = false
                    }
                    if (!scanning) break
                    col++
                }
                if (scanning) {
                    val next = cleaned.getOrNull(line + 1)?.trim().orEmpty()
                    val continues = paren != 0 || next.startsWith("{") || next.startsWith("=") || next.startsWith(":")
                    if (!continues) scanning = false
                    line++
                    col = 0
                }
            }
            if (bodyStart >= 0) {
                var depth = 0
                var end = bodyStart
                var opened = false
                var j = bodyStart
                while (j < cleaned.size) {
                    for (ch in cleaned[j]) {
                        if (ch == '{') { depth++; opened = true }
                        if (ch == '}') depth--
                    }
                    if (opened && depth <= 0) { end = j; break }
                    j++
                }
                val size = end - i + 1
                if (size > (sizes[name] ?: 0)) sizes[name] = size
            }
            i++
        }
        return sizes
    }

    private fun measure(): Pair<Map<String, Int>, Map<String, Int>> {
        val files = sortedMapOf<String, Int>()
        val functions = sortedMapOf<String, Int>()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val path = file.path.replace(File.separatorChar, '/')
                val lines = file.readLines()
                if (lines.size > FILE_LIMIT) files[path] = lines.size
                functionSizes(lines).filterValues { it > FUNCTION_LIMIT }.forEach { (name, size) -> functions["$path#$name"] = size }
            }
        }
        return files to functions
    }

    private fun readBaseline(): Pair<Map<String, Int>, Map<String, Int>> {
        val files = HashMap<String, Int>()
        val functions = HashMap<String, Int>()
        if (baselineFile.exists()) baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (kind, key, size) = line.split(" ")
            (if (kind == "file") files else functions)[key] = size.toInt()
        }
        return files to functions
    }

    @Test
    fun filesAndFunctionsStayWithinTheirBaseline() {
        val (files, functions) = measure()
        if (System.getenv("UPDATE_SIZE_BASELINE") == "1") {
            baselineFile.writeText(
                "# Files over $FILE_LIMIT lines and functions over $FUNCTION_LIMIT lines, with the size they may not grow past.\n" +
                    "# Splitting one lowers it: run the tests with UPDATE_SIZE_BASELINE=1 to rewrite this file.\n" +
                    files.entries.joinToString("") { "file ${it.key} ${it.value}\n" } +
                    functions.entries.joinToString("") { "fun ${it.key} ${it.value}\n" }
            )
            return
        }
        val (baseFiles, baseFunctions) = readBaseline()
        val problems = mutableListOf<String>()
        files.forEach { (path, size) ->
            val allowed = baseFiles[path]
            if (allowed == null) problems += "$path has $size lines (limit $FILE_LIMIT); split it"
            else if (size > allowed) problems += "$path grew from $allowed to $size lines; split it instead"
        }
        functions.forEach { (key, size) ->
            val allowed = baseFunctions[key]
            if (allowed == null) problems += "$key is $size lines (limit $FUNCTION_LIMIT); split it"
            else if (size > allowed) problems += "$key grew from $allowed to $size lines; split it instead"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        const val FILE_LIMIT = 500
        const val FUNCTION_LIMIT = 200
    }
}
