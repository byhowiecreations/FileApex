package com.fileapex.platform

import com.fileapex.util.AddressRedaction
import com.fileapex.update.FileApexAppVersion
import java.io.File
import kotlin.text.Charsets
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JOptionPane

/**
 * Handles early startup uncaught exceptions by capturing telemetry and writing
 * a clear, human-readable diagnostic crash log directly to the user's Desktop.
 */
object DesktopCrashHandler {
    private const val DIALOG_DETAIL_MAX = 160
    private var isInstalled = false

    fun install() {
        if (isInstalled) return
        isInstalled = true

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(thread, throwable)
            } finally {
                originalHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun handleCrash(thread: Thread, throwable: Throwable) {
        val logFile = writeCrashLogToDesktop(thread, throwable)
        showCrashDialog(logFile, throwable)
    }

    private fun writeCrashLogToDesktop(thread: Thread, throwable: Throwable): File {
        val desktopDir = DesktopPlatformPaths.userDesktopDirectory()
        val targetLogFile = File(desktopDir, "FileApex-error.log")

        // Prefer a single Desktop log; remove a mistaken home-folder copy from older builds.
        runCatching {
            val homeMistaken = File(System.getProperty("user.home").orEmpty(), "FileApex-error.log")
            if (homeMistaken.isFile && homeMistaken.canonicalFile != targetLogFile.canonicalFile) {
                homeMistaken.delete()
            }
        }

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTraceString = AddressRedaction.redact(sw.toString())

        val timeStampReadable = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val logContent = buildString {
            appendLine("================================================================================")
            appendLine("                      FILEAPEX EARLY STARTUP ERROR LOG                         ")
            appendLine("================================================================================")
            appendLine("Timestamp:     $timeStampReadable")
            appendLine("App Version:   v${FileApexAppVersion.NAME} (Code: ${FileApexAppVersion.CODE})")
            appendLine("OS Name:       ${System.getProperty("os.name")}")
            appendLine("OS Arch:       ${System.getProperty("os.arch")}")
            appendLine("OS Version:    ${System.getProperty("os.version")}")
            appendLine("Java Vendor:   ${System.getProperty("java.vendor")}")
            appendLine("Java Version:  ${System.getProperty("java.version")}")
            appendLine("Java Home:     ${System.getProperty("java.home")}")
            appendLine("Thread Name:   ${thread.name}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("EXCEPTION DETAILS")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Exception Class: ${throwable::class.qualifiedName ?: throwable::class.java.name}")
            appendLine("Message:         ${throwable.message?.let { shortened(AddressRedaction.redact(it)) } ?: "(No exception message)"}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("STACK TRACE")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(stackTraceString.trimEnd())
            appendLine("--------------------------------------------------------------------------------")
            appendLine("SYSTEM PROPERTIES & ENVIRONMENT")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("skiko.renderApi = ${System.getProperty("skiko.renderApi") ?: "(not set)"}")
            appendLine("================================================================================")
        }

        runCatching {
            targetLogFile.writeText(logContent, Charsets.UTF_8)
        }

        return targetLogFile
    }

    private fun shortened(text: String): String {
        val oneLine = text.replace('\n', ' ')
        return if (oneLine.length <= DIALOG_DETAIL_MAX) oneLine else oneLine.take(DIALOG_DETAIL_MAX) + "... (full text in the log)"
    }

    private fun showCrashDialog(logFile: File, throwable: Throwable) {
        val detailMessage = throwable.message?.takeIf { it.isNotBlank() }?.let(::shortened)
            ?: throwable::class.simpleName
            ?: crashCopy("crash_unknown_error", "Unknown error")
        val dialogMessage = crashCopy(
            "crash_startup_body",
            "FileApex encountered a critical startup error:\n$detailMessage\n\nA detailed diagnostic log has been saved to your Desktop:\n${logFile.absolutePath}",
            detailMessage,
            logFile.absolutePath
        )
        val dialogTitle = crashCopy("crash_startup_title", "FileApex Startup Error")

        runCatching {
            JOptionPane.showMessageDialog(
                null,
                dialogMessage,
                dialogTitle,
                JOptionPane.ERROR_MESSAGE
            )
        }.onFailure {
            System.err.println("FileApex Error Log generated: ${logFile.name}")
            System.err.println(dialogMessage)
        }
    }

    private fun crashCopy(key: String, fallback: String, vararg args: Any): String {
        return runCatching { com.fileapex.i18n.AppI18n.t(key, *args) }.getOrDefault(fallback)
    }
}
