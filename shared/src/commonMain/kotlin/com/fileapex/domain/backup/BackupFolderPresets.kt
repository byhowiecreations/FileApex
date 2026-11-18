package com.fileapex.domain.backup

import com.fileapex.util.PathUtils
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import com.fileapex.util.cancellableCatching

/** Presets and structured destination path helpers for device backups. */
object BackupFolderPresets {
    enum class PresetKind { Photos, Screenshots }

    data class DetectedPreset(val kind: PresetKind, val path: String)

    /** Removes path separators and control characters so device names map to safe subfolder names. */
    fun sanitizeFolderSegment(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[/\\:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
        return cleaned.ifBlank { "Device" }
    }

    /** Identifies whether [sourcePath] or [relativePath] belongs to Photos or Screenshots. */
    fun categoryForSource(sourcePath: String, relativePath: String): String? {
        val normalizedSource = sourcePath.replace('\\', '/').trimEnd('/').lowercase()
        val normalizedRel = relativePath.replace('\\', '/').lowercase()
        if (
            normalizedSource.endsWith("/screenshots") ||
            normalizedRel.startsWith("screenshots/") ||
            normalizedSource.contains("/screenshots/")
        ) {
            return "Screenshots"
        }
        if (
            normalizedSource.endsWith("/dcim/camera") ||
            normalizedSource.endsWith("/dcim") ||
            normalizedSource.endsWith("/pictures") ||
            normalizedRel.startsWith("camera/") ||
            normalizedRel.startsWith("dcim/")
        ) {
            return "Photos"
        }
        return null
    }

    /** Routes backups under `@inbox/<device>/<Photos|Screenshots|Folder>/...` without repeating prefixes. */
    fun remoteTargetPath(
        destinationRoot: String,
        sourceDeviceName: String,
        sourcePath: String,
        fileRelativePath: String
    ): String {
        val deviceSegment = sanitizeFolderSegment(sourceDeviceName)
        val cleanRel = fileRelativePath.replace('\\', '/').trimStart('/')
        val category = categoryForSource(sourcePath, cleanRel)
        val routedRelative = if (category != null) {
            val trimmedRel = when {
                category == "Screenshots" && cleanRel.startsWith("Screenshots/", ignoreCase = true) ->
                    cleanRel.substringAfter('/')
                category == "Photos" && cleanRel.startsWith("Camera/", ignoreCase = true) ->
                    cleanRel.substringAfter('/')
                else -> cleanRel
            }
            if (trimmedRel.isBlank()) category else "$category/$trimmedRel"
        } else {
            cleanRel
        }
        return PathUtils.join(PathUtils.join(destinationRoot, deviceSegment), routedRelative)
    }

    /** Probes standard Android OEM locations for Camera photos and Screenshots, returning existing folders. */
    fun detectAndroidMediaPresets(
        storageRoot: String,
        directoryExists: (String) -> Boolean = { candidate ->
            cancellableCatching("BackupFolderPresets: SystemFileSystem.metadataOrNull") {
                SystemFileSystem.metadataOrNull(Path(candidate))?.isDirectory == true
            }.getOrDefault(false)
        }
    ): List<DetectedPreset> {
        val root = storageRoot.trimEnd('/', '\\')
        if (root.isBlank()) return emptyList()
        val found = ArrayList<DetectedPreset>()
        val cameraCandidates = listOf("$root/DCIM/Camera", "$root/DCIM", "$root/Pictures")
        cameraCandidates.firstOrNull(directoryExists)?.let {
            found += DetectedPreset(PresetKind.Photos, it)
        }
        // Pixel/Stock uses Pictures/Screenshots; others may use DCIM/Screenshots.
        val screenshotCandidates = listOf("$root/Pictures/Screenshots", "$root/DCIM/Screenshots")
        screenshotCandidates.firstOrNull(directoryExists)?.let {
            found += DetectedPreset(PresetKind.Screenshots, it)
        }
        return found
    }
}
