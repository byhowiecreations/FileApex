package com.fileapex.platform

import com.fileapex.update.FileApexAppVersion
import com.fileapex.util.TimeUtils
import java.net.URLEncoder
import java.time.Instant

actual object Diagnostics {
    private const val MAX_LOG_EVENTS = 200
    private const val FEEDBACK_EMAIL = "byhowiecreations@gmail.com"
    private const val GOOGLE_FORM_BASE_URL =
        "https://docs.google.com/forms/d/e/1FAIpQLSePtAhGfpA1iPxP6S_yTjlMTAQcYX66S76bZTpVVuGeOFrWXg/viewform?usp=pp_url"
    private const val GITHUB_ISSUES_URL = "https://github.com/byhowiecreations/FileApex/issues"

    private val lock = Any()
    private val logBuffer = ArrayDeque<String>(MAX_LOG_EVENTS)

    actual fun log(event: String) {
        val now = TimeUtils.now()
        val iso = Instant.ofEpochMilli(now).toString()
        val entry = "[$iso] $event"
        synchronized(lock) {
            if (logBuffer.size >= MAX_LOG_EVENTS) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
        }
        DesktopLifecycleLog.log(event)
    }

    actual fun dumpLogs(): List<String> = synchronized(lock) {
        val result = mutableListOf<String>()
        result.addAll(logBuffer)
        runCatching {
            val file = DesktopPlatformPaths.applicationSupportDirectory().resolve("desktop-lifecycle.log")
            if (file.isFile) {
                val lines = file.readLines().takeLast(100)
                if (result.isEmpty()) {
                    result.addAll(lines)
                } else {
                    for (line in lines) {
                        if (!result.contains(line)) {
                            result.add(line)
                        }
                    }
                }
            }
        }
        result
    }

    fun dumpDiagnosticsFile(): java.io.File {
        val dir = DesktopPlatformPaths.applicationSupportDirectory().resolve("logs")
        if (!dir.exists()) dir.mkdirs()
        val file = dir.resolve("fileapex_diagnostics.txt")
        val logs = dumpLogs()
        val nowIso = Instant.ofEpochMilli(TimeUtils.now()).toString()
        val osName = System.getProperty("os.name").orEmpty()
        val osVersion = System.getProperty("os.version").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()
        val javaVersion = System.getProperty("java.version").orEmpty()
        val jvmVendor = System.getProperty("java.vendor").orEmpty()

        file.bufferedWriter().use { writer ->
            writer.appendLine("=== FileApex Diagnostics Log ===")
            writer.appendLine("App Version: FileApex v${FileApexAppVersion.NAME} (Code: ${FileApexAppVersion.CODE})")
            writer.appendLine("Channel: Desktop")
            writer.appendLine("OS: $osName $osVersion ($osArch)")
            writer.appendLine("Java: $javaVersion ($jvmVendor)")
            writer.appendLine("Exported At: $nowIso")
            writer.appendLine("Captured Events: ${logs.size}")
            writer.appendLine("================================")
            if (logs.isEmpty()) {
                writer.appendLine("(No operational events recorded in buffer)")
            } else {
                logs.forEach { writer.appendLine(it) }
            }
        }
        return file
    }

    private fun revealInFileManager(file: java.io.File) {
        runCatching {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.contains("mac") -> {
                    ProcessBuilder("open", "-R", file.absolutePath).start()
                }
                os.contains("windows") -> {
                    ProcessBuilder("explorer.exe", "/select,\"${file.absolutePath}\"").start()
                }
                else -> {
                    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                        java.awt.Desktop.getDesktop().open(file.parentFile)
                    }
                }
            }
        }
    }

    actual fun sendFeedbackEmail(): Boolean {
        val file = dumpDiagnosticsFile()
        val logs = dumpLogs()
        val nowIso = Instant.ofEpochMilli(TimeUtils.now()).toString()
        val osName = System.getProperty("os.name").orEmpty()
        val osVersion = System.getProperty("os.version").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()

        runCatching {
            PlatformClipboard.setSystemClipboardText(file.readText())
        }
        revealInFileManager(file)

        val recentTail = logs.takeLast(15)
        val bodyText = buildString {
            appendLine("Please describe the issue or feedback below:")
            appendLine()
            appendLine()
            appendLine("--- System Telemetry ---")
            appendLine("App: FileApex v${FileApexAppVersion.NAME} (${FileApexAppVersion.CODE})")
            appendLine("OS: $osName $osVersion ($osArch)")
            appendLine("Exported At: $nowIso")
            appendLine("Log File: ${file.absolutePath}")
            appendLine()
            appendLine("[Full diagnostics have been copied to your clipboard and revealed in Finder/Explorer for drag-and-drop]")
            if (recentTail.isNotEmpty()) {
                appendLine()
                appendLine("--- Recent Operational Events ---")
                recentTail.forEach { appendLine(it) }
            }
        }

        val subject = URLEncoder.encode("FileApex v${FileApexAppVersion.NAME} Feedback & Diagnostics", "UTF-8")
        val encodedBody = URLEncoder.encode(bodyText, "UTF-8")
        val mailto = "mailto:$FEEDBACK_EMAIL?subject=$subject&body=$encodedBody"
        return runCatching {
            PlatformClipboard.openUrlInDefaultBrowser(mailto)
            true
        }.getOrDefault(false)
    }

    actual fun openAnonymousFeedbackForm() {
        val telemetry = "Version: ${FileApexAppVersion.NAME} (${FileApexAppVersion.CODE}), " +
            "Platform: ${System.getProperty("os.name")}, " +
            "Arch: ${System.getProperty("os.arch")}"
        val encodedTelemetry = URLEncoder.encode(telemetry, "UTF-8")
        val fullUrl = "$GOOGLE_FORM_BASE_URL&entry.1576900639=$encodedTelemetry"
        PlatformClipboard.openUrlInDefaultBrowser(fullUrl)
    }

    actual fun openGitHubIssuesTracker() {
        PlatformClipboard.openUrlInDefaultBrowser(GITHUB_ISSUES_URL)
    }
}
