package com.fileapex.update

import com.fileapex.i18n.AppI18n
import java.io.File
import kotlin.text.Charsets
import kotlin.system.exitProcess

actual object PlatformUpdateInstaller {
    actual fun updateCacheDirectory(): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "FileApexUpdates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    actual fun selectAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        if (isWindows()) {
            return assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
        }
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val wantsSilicon = arch == "aarch64" || arch == "arm64"
        return macAssetFor(assets, wantsSilicon)
    }

    actual fun installAndRelaunch(localFilePath: String, remoteVersion: String) {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (osName.contains("win")) {
            val installer = File(localFilePath)
            check(installer.isFile) { AppI18n.t("update_file_missing") }
            ProcessBuilder("cmd", "/c", "start", "", installer.absolutePath).start()
            exitProcess(0)
        }
        check(osName.contains("mac")) {
            AppI18n.t("update_mac_only")
        }
        val asset = File(localFilePath)
        check(asset.isFile) { AppI18n.t("update_file_missing") }

        val targetApp = resolveInstallTargetApp()
        val scriptFile = File(updateCacheDirectory(), "fileapex-apply-update.sh")
        scriptFile.writeText(
            buildMacUpdateScript(
                assetPath = asset.absolutePath,
                targetAppPath = targetApp.absolutePath
            ),
            Charsets.UTF_8
        )
        scriptFile.setExecutable(true)

        println(
            "PlatformUpdateInstaller: spawning macOS update script for $remoteVersion → " +
                targetApp.absolutePath
        )
        ProcessBuilder("/bin/bash", scriptFile.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        // Clear the path so the replacement bundle can overwrite / relaunch.
        exitProcess(0)
    }

    private fun resolveInstallTargetApp(): File {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir.isNullOrBlank()) {
            val message =
                "CRITICAL: cannot resolve FileApex.app bundle " +
                    "(compose.application.resources.dir is missing). Aborting update — " +
                    "refusing to fall back to /Applications/FileApex.app"
            System.err.println("PlatformUpdateInstaller: $message")
            error(message)
        }
        // …/FileApex.app/Contents/app  →  FileApex.app
        val appBundle = File(resourcesDir).parentFile?.parentFile
        if (appBundle == null || !appBundle.name.endsWith(".app") || !appBundle.isDirectory) {
            val message =
                "CRITICAL: cannot resolve FileApex.app from resources.dir=$resourcesDir. " +
                    "Aborting update — refusing to fall back to /Applications/FileApex.app"
            System.err.println("PlatformUpdateInstaller: $message")
            error(message)
        }
        return appBundle
    }

    private fun buildMacUpdateScript(assetPath: String, targetAppPath: String): String {
        val asset = shellQuote(assetPath)
        val target = shellQuote(targetAppPath)
        val d = Char(36).toString()
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("set -euo pipefail")
            appendLine("sleep 1")
            appendLine("ASSET=$asset")
            appendLine("TARGET=$target")
            appendLine("TMP_DIR=\"${d}(mktemp -d /tmp/FileApexUpdate.XXXXXX)\"")
            appendLine("cleanup() { rm -rf \"${d}TMP_DIR\"; }")
            appendLine("trap cleanup EXIT")
            appendLine()
            appendLine("if [[ \"${d}ASSET\" == *.dmg ]]; then")
            appendLine("  ATTACH_OUT=\"${d}(hdiutil attach -nobrowse -readonly \"${d}ASSET\")\"")
            appendLine(
                "  MOUNT=\"${d}(printf '%s\\n' \"${d}ATTACH_OUT\" | " +
                    "awk -F'\\t' '/\\/Volumes\\/{print ${d}NF; exit}')\""
            )
            appendLine("  if [[ -z \"${d}MOUNT\" ]]; then")
            appendLine(
                "    MOUNT=\"${d}(printf '%s\\n' \"${d}ATTACH_OUT\" | " +
                    "grep -o '/Volumes/[^ ]*' | tail -1)\""
            )
            appendLine("  fi")
            appendLine(
                "  SRC_APP=\"${d}(find \"${d}MOUNT\" -maxdepth 3 -name '*.app' -print -quit)\""
            )
            appendLine("  if [[ -z \"${d}SRC_APP\" ]]; then")
            appendLine("    echo \"FileApex update: no .app found in DMG\" >&2")
            appendLine("    hdiutil detach \"${d}MOUNT\" -quiet || true")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("  ditto \"${d}SRC_APP\" \"${d}TARGET\"")
            appendLine("  hdiutil detach \"${d}MOUNT\" -quiet || true")
            appendLine("elif [[ \"${d}ASSET\" == *.zip ]]; then")
            appendLine("  unzip -q \"${d}ASSET\" -d \"${d}TMP_DIR\"")
            appendLine(
                "  SRC_APP=\"${d}(find \"${d}TMP_DIR\" -maxdepth 4 -name '*.app' -print -quit)\""
            )
            appendLine("  if [[ -z \"${d}SRC_APP\" ]]; then")
            appendLine("    echo \"FileApex update: no .app found in ZIP\" >&2")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("  ditto \"${d}SRC_APP\" \"${d}TARGET\"")
            appendLine("else")
            appendLine("  echo \"FileApex update: unsupported asset ${d}ASSET\" >&2")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine()
            appendLine("open -a FileApex || open \"${d}TARGET\"")
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

private val siliconTags = listOf("silicon", "arm64", "aarch64")
private val intelTags = listOf("intel", "x86_64", "x64", "amd64")

/** The DMG built for this Mac's chip. A DMG tagged for the other chip is never chosen. */
internal fun macAssetFor(assets: List<GitHubReleaseAsset>, wantsSilicon: Boolean): GitHubReleaseAsset? {
    fun GitHubReleaseAsset.tagged(tags: List<String>) = tags.any { name.contains(it, ignoreCase = true) }
    val installers = assets.filter {
        it.name.endsWith(".dmg", ignoreCase = true) || it.name.endsWith(".zip", ignoreCase = true)
    }
    val own = if (wantsSilicon) siliconTags else intelTags
    val other = if (wantsSilicon) intelTags else siliconTags
    val matching = installers.filter { it.tagged(own) }.ifEmpty { installers.filter { !it.tagged(other) && !it.tagged(own) } }
    return matching.firstOrNull { it.name.endsWith(".dmg", ignoreCase = true) } ?: matching.firstOrNull()
}
