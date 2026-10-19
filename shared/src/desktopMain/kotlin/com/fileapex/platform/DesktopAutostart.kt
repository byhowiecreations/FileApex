package com.fileapex.platform

import com.fileapex.security.CommandRunner
import com.fileapex.security.ProcessCommandRunner
import java.io.File

/**
 * Start FileApex when the user signs in, so a rebooted PC comes back reachable. Windows writes a per-user `Run` value
 * (no administrator rights); Linux writes an XDG autostart file. Both start the installed launcher with
 * `--autostart`, which opens the window minimized. A development run (no launcher path) registers nothing. The Mac is
 * not handled here.
 */
object DesktopAutostart {
    const val FLAG = "--autostart"
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "FileApex"

    /** True for the launch that the sign-in entry started; set by main(). */
    @Volatile
    var launchedAtSignIn: Boolean = false

    fun isAutostartLaunch(args: Array<String>): Boolean = args.any { it.equals(FLAG, ignoreCase = true) }

    fun windowsRunValue(executable: String): String = "\"$executable\" $FLAG"

    fun linuxDesktopEntry(executable: String): String = """
        [Desktop Entry]
        Type=Application
        Name=FileApex
        Comment=Share files between your devices
        Exec="$executable" $FLAG
        Terminal=false
        X-GNOME-Autostart-enabled=true
    """.trimIndent() + "\n"

    /** The launcher to start at sign-in, or null when this is not an installed app. An AppImage names its current path. */
    fun executablePath(
        jpackagePath: String = System.getProperty("jpackage.app-path").orEmpty(),
        appImagePath: String = System.getenv("APPIMAGE").orEmpty()
    ): String? = appImagePath.ifBlank { jpackagePath }.takeIf { it.isNotBlank() }

    /** Applies the user's choice for this OS; returns false only when it could not be applied. */
    fun apply(enabled: Boolean): Boolean {
        val executable = executablePath()
        return when (DesktopPlatformPaths.desktopOs) {
            DesktopPlatformPaths.DesktopOs.Windows -> setWindows(enabled, executable, ProcessCommandRunner)
            DesktopPlatformPaths.DesktopOs.Linux -> setLinux(enabled, executable, File(System.getProperty("user.home")))
            else -> true
        }
    }

    fun setWindows(enabled: Boolean, executable: String?, runner: CommandRunner): Boolean {
        if (!enabled) {
            // Nothing registered is the state we wanted, and `reg delete` fails when the value is missing.
            if (runner.run(listOf("reg", "query", RUN_KEY, "/v", VALUE_NAME), null).first != 0) return true
            return runner.run(listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f"), null).first == 0
        }
        if (executable == null) return false
        return runner.run(
            listOf("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", windowsRunValue(executable), "/f"), null
        ).first == 0
    }

    fun setLinux(enabled: Boolean, executable: String?, home: File): Boolean {
        val file = File(home, ".config/autostart/fileapex.desktop")
        if (!enabled) {
            if (file.exists()) file.delete()
            return true
        }
        if (executable == null) return false
        return runCatching {
            file.parentFile.mkdirs()
            file.writeText(linuxDesktopEntry(executable))
        }.isSuccess
    }
}
