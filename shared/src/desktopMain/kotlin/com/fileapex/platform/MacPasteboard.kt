package com.fileapex.platform

import java.util.concurrent.TimeUnit

internal object MacPasteboard {
    fun readPlainText(): String? {
        if (!DesktopPlatformPaths.isMacOs()) return null
        val pasted = runProcess(listOf("/usr/bin/pbpaste"), 400L)
        if (!pasted.isNullOrBlank()) return pasted
        return runProcess(
            listOf(
                "/usr/bin/osascript",
                "-e",
                "try\nreturn the clipboard as text\nend try"
            ),
            700L
        )
    }

    private fun runProcess(command: List<String>, timeoutMs: Long): String? {
        return runCatching {
            val builder = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["LANG"] = "en_US.UTF-8"
            val process = builder.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
