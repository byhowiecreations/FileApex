package com.fileapex.platform

import com.fileapex.util.TimeUtils
import java.io.File
import kotlin.text.Charsets
import java.time.Instant

/**
 * Registers the Share Extension with pluginkit **only** when FileApex is
 * running from `/Applications/FileApex.app`. Never registers project/`current/` builds.
 *
 * Appexes are signed with sandbox entitlements at package time. This registrar
 * must not codesign or xattr the installed bundle — that invalidates the host
 * signature and makes Gatekeeper re-scan on every launch.
 *
 * Deprecated Finder Sync (`com.fileapex.FinderSync`) is unregistered on every launch.
 */
object MacOsExtensionRegistrar {
    private const val ApplicationsAppPath = "/Applications/FileApex.app"
    private const val DeprecatedFinderSyncId = "com.fileapex.FinderSync"
    private const val ShareExtensionId = "com.fileapex.ShareExtension"
    private const val BulletinShareExtensionId = "com.fileapex.BulletinShareExtension"
    private const val DeprecatedFinderAppexName = "FileApexFinderSync.appex"
    private const val ShareAppexName = "FileApexShareExtension.appex"
    private const val BulletinShareAppexName = "FileApexBulletinShareExtension.appex"
    private const val Pluginkit = "/usr/bin/pluginkit"

    /** Runs pluginkit off the critical path so the window can appear first. */
    fun registerOnLaunchDeferred() {
        if (!DesktopPlatformPaths.isMacOs()) return
        Thread(
            {
                runCatching { registerOnLaunch() }
                    .onFailure { error -> log("deferred registration failed - ${error.message}") }
            },
            "FileApex-ExtensionRegistrar"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun registerOnLaunch() {
        if (!DesktopPlatformPaths.isMacOs()) return

        val bundle = resolveRunningAppBundle()
        if (bundle == null || !isApplicationsBundle(bundle)) {
            removeAllRegistrations(DeprecatedFinderSyncId)
            removeNonApplicationsRegistrations(ShareExtensionId)
            removeNonApplicationsRegistrations(BulletinShareExtensionId)
            log(
                "skip pluginkit - not running from $ApplicationsAppPath " +
                    "(running=${bundle?.absolutePath ?: "unknown"})"
            )
            return
        }

        val appsRoot = File(ApplicationsAppPath)
        val share = File(appsRoot, "Contents/PlugIns/$ShareAppexName")
        val bulletin = File(appsRoot, "Contents/PlugIns/$BulletinShareAppexName")
        val legacyFinder = File(appsRoot, "Contents/PlugIns/$DeprecatedFinderAppexName")
        val entsDir = File(appsRoot, "Contents/Resources/ExtensionEntitlements")
        val shareEnts = File(entsDir, "ShareExtension.entitlements")
        val bulletinEnts = File(entsDir, "BulletinShareExtension.entitlements")

        if (legacyFinder.isDirectory) {
            log("removing deprecated $DeprecatedFinderAppexName from $ApplicationsAppPath")
            runCapture(Pluginkit, "-r", legacyFinder.absolutePath)
            legacyFinder.deleteRecursively()
        }

        if (!share.isDirectory) {
            log("Share PlugIn missing under $ApplicationsAppPath")
            return
        }
        if (!bulletin.isDirectory) {
            log("Bulletin Share PlugIn missing under $ApplicationsAppPath")
            return
        }
        if (!shareEnts.isFile || !bulletinEnts.isFile) {
            log(
                "ExtensionEntitlements missing under $entsDir - " +
                    "re-copy current/FileApex.app to /Applications"
            )
            return
        }

        val stamp = registrationStamp(share, bulletin, shareEnts, bulletinEnts)
        if (readStamp() == stamp) {
            log("skip pluginkit - unchanged since last successful registration")
            return
        }

        // Never codesign or xattr the host/appexes here. Packaging already signed them;
        // mutating nested code invalidates the host signature and makes Gatekeeper
        // re-scan the bundle on every launch.
        val addShare = runCapture(Pluginkit, "-a", share.absolutePath)
        val addBulletin = runCapture(Pluginkit, "-a", bulletin.absolutePath)
        val useShare = runCapture(Pluginkit, "-e", "use", "-i", ShareExtensionId)
        val useBulletin = runCapture(Pluginkit, "-e", "use", "-i", BulletinShareExtensionId)
        val ignoreFinder = runCapture(Pluginkit, "-e", "ignore", "-i", DeprecatedFinderSyncId)
        log(
            "registered Share + Bulletin from $ApplicationsAppPath " +
                "(addShare=$addShare addBulletin=$addBulletin " +
                "useShare=$useShare useBulletin=$useBulletin ignoreFinder=$ignoreFinder)"
        )
        writeStamp(registrationStamp(share, bulletin, shareEnts, bulletinEnts))
    }

    private fun registrationStamp(
        shareAppex: File,
        bulletinAppex: File,
        shareEnts: File,
        bulletinEnts: File
    ): String =
        listOf(
            shareAppex.lastModified(),
            bulletinAppex.lastModified(),
            shareEnts.lastModified(),
            bulletinEnts.lastModified()
        ).joinToString(":")

    private fun readStamp(): String? {
        val file = DesktopPlatformPaths.extensionRegistrarStampFile()
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeStamp(stamp: String) {
        runCatching {
            DesktopPlatformPaths.applicationSupportDirectory()
            DesktopPlatformPaths.extensionRegistrarStampFile().writeText(stamp, Charsets.UTF_8)
        }
    }

    private fun isMacOs(): Boolean = DesktopPlatformPaths.isMacOs()

    private fun isApplicationsBundle(bundle: File): Boolean {
        return try {
            bundle.canonicalFile == File(ApplicationsAppPath).canonicalFile
        } catch (_: Exception) {
            bundle.absolutePath == ApplicationsAppPath
        }
    }

    private fun resolveRunningAppBundle(): File? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            val fromResources = File(resourcesDir).parentFile?.parentFile
            if (fromResources != null && fromResources.name.endsWith(".app")) {
                return fromResources
            }
        }
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        var cursor: File? = File(command).canonicalFile.parentFile
        repeat(6) {
            val current = cursor ?: return null
            if (current.name.endsWith(".app")) return current
            cursor = current.parentFile
        }
        return null
    }

    private fun removeAllRegistrations(bundleId: String) {
        val listing = runCapture(Pluginkit, "-mAvvv")
        val paths = pathsForBundle(listing, bundleId)
        for (path in paths) {
            log("removing $bundleId plugin $path")
            runCapture(Pluginkit, "-r", path)
        }
    }

    private fun removeNonApplicationsRegistrations(bundleId: String) {
        val listing = runCapture(Pluginkit, "-mAvvv")
        val paths = pathsForBundle(listing, bundleId)
        for (path in paths) {
            if (path.startsWith("/Applications/")) continue
            log("removing non-Applications plugin $path")
            runCapture(Pluginkit, "-r", path)
        }
    }

    private fun pathsForBundle(listing: String, bundleId: String): List<String> {
        val paths = mutableListOf<String>()
        var inBundle = false
        for (line in listing.lines()) {
            val trimmed = line.trim()
            if (trimmed.contains(bundleId) && !trimmed.startsWith("Path")) {
                inBundle = true
                continue
            }
            if (inBundle) {
                if (trimmed.startsWith("Path = ")) {
                    paths += trimmed.removePrefix("Path = ").trim()
                    inBundle = false
                } else if (
                    trimmed.contains("com.") &&
                    (trimmed.startsWith("+") || trimmed.startsWith("-") ||
                        trimmed.matches(Regex("^com\\..*")))
                ) {
                    inBundle = false
                }
            }
        }
        return paths
    }

    private fun runCapture(vararg args: String): String {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            val code = process.waitFor()
            if (code != 0) "exit=$code ${text.take(200)}" else "ok${if (text.isEmpty()) "" else " $text"}"
        } catch (error: Exception) {
            "failed: $error"
        }
    }

    private fun log(message: String) {
        val line = "MacOsExtensionRegistrar: $message"
        println(line)
        try {
            DesktopPlatformPaths.applicationSupportDirectory()
            DesktopPlatformPaths.extensionRegistrarLogFile()
                .appendText("${Instant.ofEpochMilli(TimeUtils.now())} $line\n")
        } catch (_: Exception) {
            // Best-effort diagnostics only.
        }
    }
}
