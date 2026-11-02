package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Adds the menu entry (AppImage only; packages register their own) and a desktop shortcut on first launch. */
object LinuxDesktopIntegration {
    private const val ICON_NAME = "fileapex"
    private const val ICON_RESOURCE = "FileApex.png"
    private const val DESKTOP_FILE = "fileapex.desktop"

    fun ensureInstalled() {
        if (!DesktopPlatformPaths.isLinux()) return
        runCatching { install() }
            .onFailure { println("LinuxDesktopIntegration: skipped (${it::class.simpleName})") }
    }

    /** Where the integration writes; the real values come from the user's session, tests pass temporary folders. */
    internal class Layout(val home: File, val dataHome: File, val desktop: File?, val supportDirectory: File, val icon: File?)

    private fun install() {
        val appImage = System.getenv("APPIMAGE")?.trim().orEmpty().takeIf { File(it).isFile }
        val launcher = appImage ?: ProcessHandle.current().info().command().orElse(null) ?: return
        val home = File(System.getProperty("user.home") ?: return)
        install(
            Layout(home, dataHome(home), userDesktopDir(home), DesktopPlatformPaths.applicationSupportDirectory(), bundledIcon()),
            launcher, appImage != null
        )
    }

    internal fun install(layout: Layout, launcher: String, appImage: Boolean) {
        val marker = File(layout.supportDirectory, ".desktop-integration")
        val stamp = desktopEntry(launcher, ICON_NAME)
        if (marker.isFile && marker.readText() == stamp) return

        val iconTarget = File(layout.dataHome, "icons/hicolor/512x512/apps/$ICON_NAME.png")
        if (layout.icon != null) {
            iconTarget.parentFile.mkdirs()
            Files.copy(layout.icon.toPath(), iconTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val iconRef = if (iconTarget.isFile) iconTarget.absolutePath else ICON_NAME
        val entry = desktopEntry(execPath = launcher, icon = iconRef)

        if (appImage) {
            val menuFile = File(layout.dataHome, "applications/$DESKTOP_FILE")
            menuFile.parentFile.mkdirs()
            menuFile.writeText(entry)
            menuFile.setExecutable(true)
            runQuietly("update-desktop-database", menuFile.parentFile.absolutePath)
        }

        val desktop = layout.desktop
        if (desktop != null && desktop.isDirectory) {
            val shortcut = File(desktop, "FileApex.desktop")
            shortcut.writeText(entry)
            runCatching {
                Files.setPosixFilePermissions(shortcut.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            // GNOME shows an untrusted launcher as a text file.
            runQuietly("gio", "set", shortcut.absolutePath, "metadata::trusted", "true")
        }
        marker.writeText(stamp)
    }

    internal fun desktopEntry(execPath: String, icon: String): String =
        """
        [Desktop Entry]
        Type=Application
        Name=FileApex
        Comment=Local-first file explorer for your own devices
        Exec="$execPath" %U
        Icon=$icon
        Terminal=false
        Categories=Utility;FileTools;Network;
        MimeType=x-scheme-handler/fileapex;
        StartupWMClass=FileApex
        """.trimIndent() + "\n"

    private fun bundledIcon(): File? {
        val resources = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() } ?: return null
        return File(resources, ICON_RESOURCE).takeIf { it.isFile }
    }

    private fun dataHome(home: File): File {
        val xdg = System.getenv("XDG_DATA_HOME")?.trim().orEmpty()
        return if (xdg.startsWith("/")) File(xdg) else File(home, ".local/share")
    }

    private fun userDesktopDir(home: File): File = DesktopPlatformPaths.xdgUserDirectory("DESKTOP") ?: File(home, "Desktop")

    private fun runQuietly(vararg command: String) {
        runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
