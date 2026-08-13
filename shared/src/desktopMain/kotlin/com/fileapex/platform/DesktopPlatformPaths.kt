package com.fileapex.platform

import java.io.File

/**
 * Desktop-only SSOT for on-disk app data paths (macOS + Windows).
 *
 * Android uses its own storage layer in `androidMain` — this type is not referenced there.
 *
 * | OS      | Root |
 * |---------|------|
 * | macOS   | `~/Library/Application Support/com.fileapex/` |
 * | Windows | `%LOCALAPPDATA%\FileApex\` |
 */
object DesktopPlatformPaths {
    const val BUNDLE_ID = "com.fileapex"
    const val DATABASE_FILE_NAME = "fileapex.db"
    private const val SEND_JOBS_DIR_NAME = "send-jobs"
    private const val IDENTITY_FILE_NAME = "identity.properties"
    private const val ROSTER_RESOLVED_MARKER = ".roster-resolved"
    private const val EXTENSION_REGISTRAR_STAMP = "extension-registrar.stamp"
    private const val EXTENSION_REGISTRAR_LOG = "extension-registrar.log"

    enum class DesktopOs {
        MacOs,
        Windows,
        Other
    }

    val desktopOs: DesktopOs
        get() = detectDesktopOs()

    fun isMacOs(): Boolean = desktopOs == DesktopOs.MacOs

    fun isWindows(): Boolean = desktopOs == DesktopOs.Windows

    /** Primary writable app-data directory (created on demand). */
    fun applicationSupportDirectory(): File = ensureDirectory(primaryAppDataDirectory())

    fun databaseFile(): File = File(applicationSupportDirectory(), DATABASE_FILE_NAME)

    fun rosterResolvedMarkerFile(): File =
        File(applicationSupportDirectory(), ROSTER_RESOLVED_MARKER)

    fun sendJobsDirectory(): File =
        ensureDirectory(File(applicationSupportDirectory(), SEND_JOBS_DIR_NAME))

    fun identityPropertiesFile(): File =
        File(applicationSupportDirectory(), IDENTITY_FILE_NAME)

    fun extensionRegistrarStampFile(): File =
        File(applicationSupportDirectory(), EXTENSION_REGISTRAR_STAMP)

    fun extensionRegistrarLogFile(): File =
        File(applicationSupportDirectory(), EXTENSION_REGISTRAR_LOG)

    fun databasePreV3BackupFile(): File =
        File(applicationSupportDirectory(), "$DATABASE_FILE_NAME.pre-v3-backup")

    /**
     * Older database locations to import from on first launch (macOS only today).
     */
    fun legacyDatabaseMigrationCandidates(): List<File> {
        val home = userHomeDirectory()
        return when (desktopOs) {
            DesktopOs.MacOs -> listOf(
                File(home, ".fileapex/$DATABASE_FILE_NAME"),
                File(home, "Library/Group Containers/group.$BUNDLE_ID/Database/$DATABASE_FILE_NAME")
            )
            DesktopOs.Windows,
            DesktopOs.Other -> emptyList()
        }
    }

    /**
     * Additional roster recovery sources when the active DB is empty (macOS legacy installs).
     */
    fun legacyRosterRecoveryCandidates(): List<File> {
        val home = userHomeDirectory()
        return when (desktopOs) {
            DesktopOs.MacOs -> legacyDatabaseMigrationCandidates() +
                listOf(databasePreV3BackupFile())
            DesktopOs.Windows,
            DesktopOs.Other -> listOfNotNull(
                databasePreV3BackupFile().takeIf { it.isFile }
            )
        }
    }

    /** Legacy desktop identity location (`~/.fileapex/identity.properties` on macOS). */
    fun legacyIdentityPropertiesCandidates(): List<File> {
        val home = userHomeDirectory()
        return when (desktopOs) {
            DesktopOs.MacOs -> listOf(File(home, ".fileapex/$IDENTITY_FILE_NAME"))
            DesktopOs.Windows,
            DesktopOs.Other -> emptyList()
        }
    }

    private fun primaryAppDataDirectory(): File {
        return when (desktopOs) {
            DesktopOs.Windows -> windowsLocalAppDataDirectory()
            DesktopOs.MacOs -> macApplicationSupportDirectory()
            DesktopOs.Other -> macApplicationSupportDirectory()
        }
    }

    private fun windowsLocalAppDataDirectory(): File {
        val localAppData = System.getenv("LOCALAPPDATA")?.trim().orEmpty()
        if (localAppData.isNotEmpty() && !isUnsafePath(localAppData)) {
            return File(localAppData, "FileApex")
        }
        val programData = System.getenv("ProgramData")?.trim().orEmpty()
        if (programData.isNotEmpty() && !isUnsafePath(programData)) {
            return File(programData, "FileApex")
        }
        return File("C:\\ProgramData\\FileApex")
    }

    private fun isUnsafePath(path: String): Boolean =
        path.contains("!") || path.contains("#")

    private fun macApplicationSupportDirectory(): File {
        return File(userHomeDirectory(), "Library/Application Support/$BUNDLE_ID")
    }

    private fun userHomeDirectory(): File =
        File(System.getProperty("user.home") ?: ".")

    private fun ensureDirectory(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun detectDesktopOs(): DesktopOs {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            name.contains("windows") -> DesktopOs.Windows
            name.contains("mac") || name.contains("darwin") -> DesktopOs.MacOs
            else -> DesktopOs.Other
        }
    }
}
