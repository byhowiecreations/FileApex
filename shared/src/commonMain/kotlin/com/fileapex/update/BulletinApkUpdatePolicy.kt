package com.fileapex.update

/**
 * Selective auto-download and update policy for Bulletin Board APK payloads.
 *
 * Restricts automatic background downloading strictly to APK files matching:
 * `FileApex-v[0-9]+\.[0-9]+\.[0-9]+[a-zA-Z]*\.apk`
 */
object BulletinApkUpdatePolicy {
    val APK_NAME_REGEX = Regex("^FileApex-v[0-9]+\\.[0-9]+\\.[0-9]+[a-zA-Z]*\\.apk$")

    fun matchesAutoUpdateApk(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return APK_NAME_REGEX.matches(fileName.trim())
    }

    fun extractVersionFromApkName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val trimmed = fileName.trim()
        if (matchesAutoUpdateApk(trimmed)) {
            return trimmed.removePrefix("FileApex-").removeSuffix(".apk")
        }
        val match = Regex("""v?[0-9]+\.[0-9]+\.[0-9]+[a-zA-Z0-9]*""").find(trimmed)
        return match?.value?.let { if (it.startsWith("v")) it else "v$it" }
    }

    fun buildFileSignature(fileName: String?, fileSizeBytes: Long = 0L, timestampEpochMs: Long = 0L): String {
        val name = fileName.orEmpty().trim()
        if (name.isBlank()) return ""
        return "${name}_${fileSizeBytes}_${timestampEpochMs}"
    }

    fun shouldAutoUpdateNote(
        fileName: String?,
        noteId: String?,
        noteEpochMs: Long = 0L,
        fileSizeBytes: Long = 0L
    ): Boolean {
        if (!matchesAutoUpdateApk(fileName)) return false
        val id = noteId.orEmpty()
        if (id.isNotBlank() && PendingUpdateStore.getNoteInstallStatus(id) != null) return false
        val sig = buildFileSignature(fileName, fileSizeBytes, noteEpochMs)
        return !PendingUpdateStore.isNoteProcessed(
            noteId = id,
            timestampEpochMs = noteEpochMs,
            signature = sig
        )
    }

    fun shouldAutoUpdateDirectFile(
        fileName: String?,
        fileSizeBytes: Long = 0L,
        modifiedEpochMs: Long = 0L
    ): Boolean {
        if (!matchesAutoUpdateApk(fileName)) return false
        val sig = buildFileSignature(fileName, fileSizeBytes, modifiedEpochMs)
        return !PendingUpdateStore.isFileProcessed(sig)
    }
}
