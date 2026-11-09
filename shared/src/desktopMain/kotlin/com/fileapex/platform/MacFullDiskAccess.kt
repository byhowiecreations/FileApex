package com.fileapex.platform

import com.fileapex.security.CommandRunner
import com.fileapex.security.ProcessCommandRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * macOS has no API that grants Full Disk Access; the user switches it on in System Settings.
 * Without it each protected folder raises its own prompt, which blocks a remote session.
 */
object MacFullDiskAccess {
    private const val PRIVACY_PANE = "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"

    fun defaultProbes(home: File): List<File> = listOf(
        File(home, "Library/Safari"),
        File("/Library/Application Support/com.apple.TCC/TCC.db")
    )

    /** True when a protected probe can be read, or when none exists so nothing can be judged. */
    fun isGranted(probes: List<File> = defaultProbes(File(System.getProperty("user.home")))): Boolean {
        val existing = probes.filter { it.exists() }
        if (existing.isEmpty()) return true
        return existing.any { canRead(it) }
    }

    fun openSettings(runner: CommandRunner = ProcessCommandRunner) {
        runner.run(listOf("open", PRIVACY_PANE), null)
    }

    private fun canRead(probe: File): Boolean = try {
        if (probe.isDirectory) Files.newDirectoryStream(probe.toPath()).use { it.iterator(); true }
        else probe.inputStream().use { it.read(); true }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
