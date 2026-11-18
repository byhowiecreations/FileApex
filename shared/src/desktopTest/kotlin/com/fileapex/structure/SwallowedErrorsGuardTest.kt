package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGENTS.md error handling: a failure is never discarded anywhere in the shared module. Where this scan finds a
 * discarded failure it names the file and line.
 *
 * What counts as discarded:
 * - raw `runCatching` (it also swallows cancellation; use `cancellableCatching` or a typed catch);
 * - `cancellableCatching` / `transferCatching` without a step name whose failure is dropped (no handler, `getOrNull`,
 *   `getOrDefault`, `isSuccess`, `isFailure`) or handled by a lambda that neither logs nor throws; a step name
 *   (`cancellableCatching("closing the relay") { }`) logs the failure;
 * - `catch (Throwable | Exception | RuntimeException)` whose body neither logs nor throws.
 * A typed catch (IOException and so on) that does nothing is a decision about that one exception, not a swallow.
 */
class SwallowedErrorsGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val packages = listOf(
        "network", "cloud", "membership", "security", "iroh", "tailscale", "friends", "domain", "di", "update", "drop",
        "data", "platform", "presentation", "cli", "ui", "widget", "i18n"
    )
    private val log = Regex("""println|\bLog\.[wedi]\(|\blog\w*\(|\breport\(|\bfailSecure\(|\bonError\(|\bonLog\b|Diagnostics\.log|IrohLog\.add|\bdriveLog\w*\(|\brecord\(|\bthrow\b""")
    private val wrappers = Regex("""\b(runCatching|cancellableCatching|transferCatching)\s*(\([^)]*\))?\s*\{""")
    private val broadCatch = Regex("""catch \((_|\w+): (Throwable|Exception|RuntimeException)\)\s*\{""")

    private fun sources(): Sequence<File> = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "Test/" !in it.path && !it.name.endsWith("Test.kt") }
        .filter { file -> packages.any { "/com/fileapex/$it/" in file.path } }

    private fun blockEnd(text: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            if (text[i] == '{') depth++ else if (text[i] == '}') depth--
            i++
        }
        return i
    }

    /** The calls chained onto a wrapper (`.onFailure { }`, `.getOrNull()`, `?: return`) up to the end of the statement. */
    private fun chainAfter(text: String, from: Int): String {
        var i = from
        val out = StringBuilder()
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '.' && j + 1 < text.length && text[j + 1].isLetter()) {
                var k = j + 1
                while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                while (k < text.length && text[k] == ' ') k++
                while (k < text.length && (text[k] == '(' || text[k] == '{')) {
                    val open = text[k]
                    val close = if (open == '(') ')' else '}'
                    var depth = 1
                    k++
                    while (k < text.length && depth > 0) {
                        if (text[k] == open) depth++ else if (text[k] == close) depth--
                        k++
                    }
                }
                out.append(text, j, k)
                i = k
            } else if (j + 1 < text.length && text[j] == '?' && text[j + 1] == ':') {
                val lineEnd = text.indexOf('\n', j).let { if (it < 0) text.length else it }
                out.append(text, j, lineEnd)
                i = lineEnd
            } else {
                break
            }
        }
        return out.toString()
    }

    private fun findings(): List<String> = sources().flatMap { file ->
        val text = file.readText()
        val where = { index: Int -> "${file.relativeTo(root).path}:${text.substring(0, index).count { it == '\n' } + 1}" }
        val found = mutableListOf<String>()
        for (match in wrappers.findAll(text)) {
            val end = blockEnd(text, match.range.last + 1)
            val name = match.groupValues[1]
            val chain = chainAfter(text, end)
            val dropsOnly = Regex("""^\s*(\.(getOrNull|getOrDefault|isSuccess|isFailure)\([^)]*\))*\s*$""").containsMatchIn(chain.replace(Regex("""\?:.*"""), ""))
            val named = match.groupValues[2].contains('"')
            val handled = named || !dropsOnly && (log.containsMatchIn(chain) || (chain.contains("exceptionOrNull") && log.containsMatchIn(text.substring(end, minOf(text.length, end + 400)))))
            when {
                name == "runCatching" -> found += "${where(match.range.first)} raw runCatching"
                !handled -> found += "${where(match.range.first)} $name drops the failure"
            }
        }
        for (match in broadCatch.findAll(text)) {
            val body = text.substring(match.range.last + 1, blockEnd(text, match.range.last + 1))
            if (!log.containsMatchIn(body)) found += "${where(match.range.first)} broad catch without a log or a throw"
        }
        found
    }.sorted().toList()

    @Test
    fun noFailureOnAPeerCloudTunnelOrKeyPathIsDiscarded() {
        val found = findings()
        assertTrue("${found.size} discarded failures:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
