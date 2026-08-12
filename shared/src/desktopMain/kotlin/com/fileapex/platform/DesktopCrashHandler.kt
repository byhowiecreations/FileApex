package com.fileapex.platform

import com.fileapex.update.FileApexAppVersion
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Handles early startup uncaught exceptions by capturing telemetry and writing
 * a clear, human-readable diagnostic crash log directly to the user's Desktop.
 */
object DesktopCrashHandler {
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
        val userHome = System.getProperty("user.home").orEmpty()
        val desktopDir = File(userHome, "Desktop").let {
            if (it.exists() && it.isDirectory) it else File(userHome)
        }

        var targetLogFile = File(desktopDir, "FileApex-error.log")
        if (targetLogFile.exists()) {
            val timeStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            targetLogFile = File(desktopDir, "FileApex-error-$timeStamp.log")
        }

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTraceString = sw.toString()

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
            appendLine("Message:         ${throwable.message ?: "(No exception message)"}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("STACK TRACE")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(stackTraceString.trimEnd())
            appendLine("--------------------------------------------------------------------------------")
            appendLine("SYSTEM PROPERTIES & ENVIRONMENT")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("skiko.renderApi = ${System.getProperty("skiko.renderApi") ?: "(not set)"}")
            appendLine("user.home       = ${System.getProperty("user.home")}")
            appendLine("user.name       = ${System.getProperty("user.name")}")
            appendLine("user.dir        = ${System.getProperty("user.dir")}")
            appendLine("================================================================================")
        }

        runCatching {
            targetLogFile.writeText(logContent)
        }

        return targetLogFile
    }

    private fun showCrashDialog(logFile: File, throwable: Throwable) {
        val detailMessage = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.simpleName ?: "Unknown error"
        val dialogMessage = """
            FileApex encountered a critical startup error:
            $detailMessage

            A detailed diagnostic log has been saved to your Desktop:
            ${logFile.absolutePath}
        """.trimIndent()

        runCatching {
            JOptionPane.showMessageDialog(
                null,
                dialogMessage,
                "FileApex Startup Error",
                JOptionPane.ERROR_MESSAGE
            )
        }.onFailure {
            // Fallback for headless or AWT failure: output to stderr
            System.err.println("FileApex Error Log generated at: ${logFile.absolutePath}")
            System.err.println(dialogMessage)
        }
    }
}
