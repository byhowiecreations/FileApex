package com.fileapex.platform

import com.fileapex.util.TimeUtils
import java.io.File
import java.time.Instant

/**
 * Registers the Share Extension with pluginkit **only** when FileApex is
 * running from `/Applications/FileApex.app`. Never registers project/`current/` builds.
 *
 * Also re-signs the PlugIn with the sandbox entitlements shipped under
 * `Contents/Resources/ExtensionEntitlements/` — without `app-sandbox`, pluginkit
 * accepts `-a` then silently drops the plugin (Share menu disappears).
 *
 * Deprecated Finder Sync (`com.fileapex.FinderSync`) is unregistered on every launch.
 */
object MacOsExtensionRegistrar {
    private const val ApplicationsAppPath = "/Applications/FileApex.app"
    private const val DeprecatedFinderSyncId = "com.fileapex.FinderSync"
    private const val ShareExtensionId = "com.fileapex.ShareExtension"
    private const val DeprecatedFinderAppexName = "FileApexFinderSync.appex"
    private const val ShareAppexName = "FileApexShareExtension.appex"
    private const val Pluginkit = "/usr/bin/pluginkit"
    private const val Codesign = "/usr/bin/codesign"

    /** Runs pluginkit/codesign off the critical path so the window can appear first. */
    fun registerOnLaunchDeferred() {
        if (!DesktopPlatformPaths.isMacOs()) return
        Thread(
            {
                runCatching { registerOnLaunch() }
                    .onFailure { error -> log("deferred registration failed — ${error.message}") }
            },
            "FileApex-ExtensionRegistrar"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun registerOnLaunch() {
        if (!DesktopPlatformPaths.isMacOs()) return

        // Always purge the deprecated Finder Sync extension (any install location).
        removeAllRegistrations(DeprecatedFinderSyncId)
        removeNonApplicationsRegistrations(ShareExtensionId)

        val bundle = resolveRunningAppBundle()
        if (bundle == null || !isApplicationsBundle(bundle)) {
            log(
                "skip pluginkit — not running from $ApplicationsAppPath " +
                    "(running=${bundle?.absolutePath ?: "unknown"})"
            )
            return
        }

        val appsRoot = File(ApplicationsAppPath)
        val share = File(appsRoot, "Contents/PlugIns/$ShareAppexName")
        val legacyFinder = File(appsRoot, "Contents/PlugIns/$DeprecatedFinderAppexName")
        val entsDir = File(appsRoot, "Contents/Resources/ExtensionEntitlements")
        val shareEnts = File(entsDir, "ShareExtension.entitlements")
        val hostEnts = File(entsDir, "FileApex.entitlements")

        if (legacyFinder.isDirectory) {
            log("removing deprecated $DeprecatedFinderAppexName from $ApplicationsAppPath")
            runCapture(Pluginkit, "-r", legacyFinder.absolutePath)
            legacyFinder.deleteRecursively()
        }

        if (!share.isDirectory) {
            log("Share PlugIn missing under $ApplicationsAppPath")
            return
        }
        if (!shareEnts.isFile) {
            log(
                "ExtensionEntitlements missing under $entsDir — " +
                    "re-copy current/FileApex.app to /Applications"
            )
            return
        }

        val stamp = registrationStamp(appsRoot, share, shareEnts)
        if (readStamp() == stamp) {
            log("skip pluginkit — unchanged since last successful registration")
            return
        }

        // Restore sandbox entitlements before pluginkit (adhoc).
        val signShare = runCapture(
            Codesign, "--force", "--sign", "-",
            "--entitlements", shareEnts.absolutePath, share.absolutePath
        )
        if (hostEnts.isFile) {
            runCapture(
                Codesign, "--force", "--sign", "-",
                "--entitlements", hostEnts.absolutePath, appsRoot.absolutePath
            )
        }
        runCapture("/usr/bin/xattr", "-cr", appsRoot.absolutePath)

        val addShare = runCapture(Pluginkit, "-a", share.absolutePath)
        val useShare = runCapture(Pluginkit, "-e", "use", "-i", ShareExtensionId)
        val ignoreFinder = runCapture(Pluginkit, "-e", "ignore", "-i", DeprecatedFinderSyncId)
        log(
            "registered Share from $ApplicationsAppPath " +
                "(signShare=$signShare addShare=$addShare useShare=$useShare " +
                "ignoreFinder=$ignoreFinder)"
        )
        writeStamp(stamp)
    }

    private fun registrationStamp(appsRoot: File, shareAppex: File, shareEnts: File): String =
        listOf(
            appsRoot.lastModified(),
            shareAppex.lastModified(),
            shareEnts.lastModified(),
            appsRoot.length()
        ).joinToString(":")

    private fun readStamp(): String? {
        val file = DesktopPlatformPaths.extensionRegistrarStampFile()
        if (!file.isFile) return null
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeStamp(stamp: String) {
        runCatching {
            DesktopPlatformPaths.applicationSupportDirectory()
            DesktopPlatformPaths.extensionRegistrarStampFile().writeText(stamp)
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
            val text = process.inputStream.bufferedReader().readText().trim()
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
