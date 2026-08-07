package com.fileapex.platform

import java.io.File

object DesktopWindowsRegistration {
    fun registerWindowsContextMenuAndSendTo() {
        if (!DesktopPlatformPaths.isWindows()) return
        val currentExe = findExecutablePath() ?: return

        runCatching {
            val psScript = """
                ${'$'}sendToPath = [Environment]::GetFolderPath([Environment+SpecialFolder]::SendTo)
                if (Test-Path ${'$'}sendToPath) {
                    ${'$'}shortcutPath = Join-Path ${'$'}sendToPath "FileApex.lnk"
                    ${'$'}wshShell = New-Object -ComObject WScript.Shell
                    ${'$'}shortcut = ${'$'}wshShell.CreateShortcut(${'$'}shortcutPath)
                    ${'$'}shortcut.TargetPath = "$currentExe"
                    ${'$'}shortcut.IconLocation = "$currentExe,0"
                    ${'$'}shortcut.Save()
                }

                # 1. Direct Context Menu for All Files
                ${'$'}regFiles = "HKCU:\Software\Classes\*\shell\FileApex"
                New-Item -Path ${'$'}regFiles -Force | Out-Null
                Set-ItemProperty -Path ${'$'}regFiles -Name "(default)" -Value "Send with FileApex"
                Set-ItemProperty -Path ${'$'}regFiles -Name "Icon" -Value "$currentExe"
                New-Item -Path "${'$'}regFiles\command" -Force | Out-Null
                Set-ItemProperty -Path "${'$'}regFiles\command" -Name "(default)" -Value "`"$currentExe`" `"%1`""

                # 2. Direct Context Menu for Folders/Directories
                ${'$'}regDirs = "HKCU:\Software\Classes\Directory\shell\FileApex"
                New-Item -Path ${'$'}regDirs -Force | Out-Null
                Set-ItemProperty -Path ${'$'}regDirs -Name "(default)" -Value "Send with FileApex"
                Set-ItemProperty -Path ${'$'}regDirs -Name "Icon" -Value "$currentExe"
                New-Item -Path "${'$'}regDirs\command" -Force | Out-Null
                Set-ItemProperty -Path "${'$'}regDirs\command" -Name "(default)" -Value "`"$currentExe`" `"%1`""

                # 3. System File Associations (Windows 11 System Share Sheet integration)
                ${'$'}regSysAssoc = "HKCU:\Software\Classes\SystemFileAssociations\*\shell\FileApex"
                New-Item -Path ${'$'}regSysAssoc -Force | Out-Null
                Set-ItemProperty -Path ${'$'}regSysAssoc -Name "(default)" -Value "FileApex"
                Set-ItemProperty -Path ${'$'}regSysAssoc -Name "Icon" -Value "$currentExe"
                New-Item -Path "${'$'}regSysAssoc\command" -Force | Out-Null
                Set-ItemProperty -Path "${'$'}regSysAssoc\command" -Name "(default)" -Value "`"$currentExe`" `"%1`""

                # 4. Windows Registered Application & Shell Open With
                ${'$'}regApp = "HKCU:\Software\Classes\Applications\FileApex.exe"
                New-Item -Path ${'$'}regApp -Force | Out-Null
                Set-ItemProperty -Path ${'$'}regApp -Name "FriendlyAppName" -Value "FileApex"
                New-Item -Path "${'$'}regApp\SupportedTypes" -Force | Out-Null
                Set-ItemProperty -Path "${'$'}regApp\SupportedTypes" -Name ".*" -Value ""
                New-Item -Path "${'$'}regApp\shell\open\command" -Force | Out-Null
                Set-ItemProperty -Path "${'$'}regApp\shell\open\command" -Name "(default)" -Value "`"$currentExe`" `"%1`""
            """.trimIndent()

            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                .redirectErrorStream(true)
                .start()
        }
    }

    private fun findExecutablePath(): String? {
        val launcher = System.getProperty("jpackage.app-path")
        if (!launcher.isNullOrBlank() && File(launcher).isFile) {
            return File(launcher).absolutePath
        }
        val javaHome = System.getProperty("java.home").orEmpty()
        val javaw = File(javaHome, "bin/javaw.exe")
        if (javaw.isFile) return javaw.absolutePath
        return null
    }
}
