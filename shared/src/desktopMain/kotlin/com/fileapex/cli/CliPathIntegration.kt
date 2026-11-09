package com.fileapex.cli

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

object CliPathIntegration {

    /**
     * Installs or updates the macOS CLI wrapper. Windows gets its PATH entry from the installer (`windows/FileApex.iss`).
     */
    fun ensureInstalled() {
        runCatching {
            if (DesktopPlatformPaths.isMacOs()) installMacOs()
        }.onFailure { error ->
            println("CliPathIntegration: Silent installation notice: ${error.message}")
        }
    }

    private fun installMacOs() {
        val userHome = File(System.getProperty("user.home") ?: return)
        val localBin = File(userHome, ".local/bin")
        if (!localBin.exists()) {
            localBin.mkdirs()
        }

        // Detect current app executable or bundle
        val executable = resolveMacExecutable() ?: return
        val targetScript = File(localBin, "fileapex")

        // Write launcher script or symlink
        val scriptContent = """
            |#!/bin/sh
            |exec "$executable" "$@"
        """.trimMargin()

        if (!targetScript.exists() || targetScript.readText().trim() != scriptContent.trim()) {
            targetScript.writeText(scriptContent + "\n")
            targetScript.setExecutable(true, false)
        }

        // Check if ~/.local/bin is already in shell configs (~/.zshrc and ~/.bash_profile)
        val exportLine = "export PATH=\"\$HOME/.local/bin:\$PATH\""
        val shellFiles = listOf(File(userHome, ".zshrc"), File(userHome, ".bash_profile"))

        for (rcFile in shellFiles) {
            val content = if (rcFile.exists()) rcFile.readText() else ""
            if (!content.contains(".local/bin")) {
                val updated = if (content.isNotEmpty() && !content.endsWith("\n")) {
                    "$content\n$exportLine\n"
                } else {
                    "$content$exportLine\n"
                }
                rcFile.writeText(updated)
            }
        }
    }

    private fun resolveMacExecutable(): String? {
        val macAppPath = "/Applications/FileApex.app/Contents/MacOS/FileApex"
        if (File(macAppPath).exists()) {
            return macAppPath
        }
        val userAppPath = "${System.getProperty("user.home")}/Applications/FileApex.app/Contents/MacOS/FileApex"
        if (File(userAppPath).exists()) {
            return userAppPath
        }
        val currentCommand = ProcessHandle.current().info().command().orElse(null)
        if (!currentCommand.isNullOrBlank() && File(currentCommand).exists()) {
            return currentCommand
        }
        return macAppPath
    }
}
