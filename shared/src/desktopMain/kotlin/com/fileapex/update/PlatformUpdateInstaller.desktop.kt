package com.fileapex.update

import java.io.File
import kotlin.system.exitProcess

actual object PlatformUpdateInstaller {
    actual fun updateCacheDirectory(): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "FileApexUpdates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    actual fun selectAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        val dmg = assets.firstOrNull { it.name.endsWith(".dmg", ignoreCase = true) }
        if (dmg != null) return dmg
        return assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
    }

    actual fun installAndRelaunch(localFilePath: String, remoteVersion: String) {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        check(osName.contains("mac")) {
            "Desktop auto-update install is only implemented for macOS (os=$osName)"
        }
        val asset = File(localFilePath)
        check(asset.isFile) { "Update package missing at $localFilePath" }

        val targetApp = resolveInstallTargetApp()
        val scriptFile = File(updateCacheDirectory(), "fileapex-apply-update.sh")
        scriptFile.writeText(
            buildMacUpdateScript(
                assetPath = asset.absolutePath,
                targetAppPath = targetApp.absolutePath
            )
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
