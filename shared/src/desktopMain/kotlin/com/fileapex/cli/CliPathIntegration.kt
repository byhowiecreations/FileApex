package com.fileapex.cli

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

object CliPathIntegration {

    /**
     * Installs or updates the CLI symlink/wrapper and PATH environment variable
     * silently during desktop application startup.
     */
    fun ensureInstalled() {
        runCatching {
            when {
                DesktopPlatformPaths.isMacOs() -> installMacOs()
                DesktopPlatformPaths.isWindows() -> installWindows()
            }
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

    private fun installWindows() {
        val currentCommand = ProcessHandle.current().info().command().orElse(null) ?: return
        val exeFile = File(currentCommand)
        val installDir = exeFile.parentFile ?: return

        // Ensure fileapex.cmd wrapper exists in the installation directory
        val cmdWrapper = File(installDir, "fileapex.cmd")
        val cmdContent = "@echo off\r\n\"${exeFile.absolutePath}\" %*\r\n"
        if (!cmdWrapper.exists() || cmdWrapper.readText() != cmdContent) {
            runCatching { cmdWrapper.writeText(cmdContent) }
        }

        // Check and append to user PATH environment variable via PowerShell + broadcast WM_SETTINGCHANGE
        val installPath = installDir.absolutePath
        val psScript = """
            ${'$'}currentPath = [Environment]::GetEnvironmentVariable('Path', 'User')
            if (-not ${'$'}currentPath) { ${'$'}currentPath = "" }
            ${'$'}entries = ${'$'}currentPath -split ';' | Where-Object { ${'$'}_.Trim() -ne "" }
            ${'$'}target = '$installPath'
            if (${'$'}entries -notcontains ${'$'}target) {
                ${'$'}newPath = if (${'$'}currentPath -eq "") { ${'$'}target } else { "${'$'}currentPath;${'$'}target" }
                [Environment]::SetEnvironmentVariable('Path', ${'$'}newPath, 'User')
                
                # Broadcast WM_SETTINGCHANGE so active shells/terminals pick up the change immediately
                ${'$'}signature = @'
                [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
                public static extern IntPtr SendMessageTimeout(
                    IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
                    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
                ${'$'}type = Add-Type -MemberDefinition ${'$'}signature -Name Win32Utils -Namespace Win32 -PassThru
                ${'$'}result = [UIntPtr]::Zero
                [Win32.Win32Utils]::SendMessageTimeout([IntPtr]0xffff, 0x001A, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]${'$'}result)
            }
        """.trimIndent()

        val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psScript)
        pb.redirectErrorStream(true)
        val process = pb.start()
        process.waitFor()
    }
}
