package com.fileapex.update

import com.fileapex.di.FileApexServices
import com.fileapex.platform.defaultDownloadsDir
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The newest Bulletin Board APK already on this device that is newer than the installed app, if there is one. */
internal fun resolveFallbackBulletinApkOffer(): PendingUpdateOffer? {
    val noteRepo = FileApexServices.noteRepository
    val candidateNote = noteRepo.notes.value.reversed().firstOrNull { note ->
        BulletinApkUpdatePolicy.matchesAutoUpdateApk(note.attachmentFileName) &&
            PendingUpdateStore.getNoteInstallStatus(note.noteId) == null
    }
    if (candidateNote != null) {
        val fileName = candidateNote.attachmentFileName.orEmpty()
        val version = BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
        if (isRemoteVersionNewer(currentAppVersionName(), version)) {
            val localPath = noteRepo.resolveLocalAttachmentPath(candidateNote)
            if (!localPath.isNullOrBlank() && AppUpdateCoordinator.fileExists(localPath)) {
                val apkSize = runCatching { SystemFileSystem.metadataOrNull(Path(localPath))?.size }.getOrNull()
                    ?: candidateNote.attachmentSizeBytes
                return PendingUpdateOffer(
                    remoteVersion = version,
                    releaseTitle = "FileApex $version",
                    releaseNotes = candidateNote.content.takeIf { it.isNotBlank() },
                    assetName = fileName,
                    assetDownloadUrl = "",
                    assetSizeBytes = apkSize,
                    localFilePath = localPath,
                    originNoteId = candidateNote.noteId
                )
            }
        }
    }

    val downloadsDir = defaultDownloadsDir()
    val dir = Path(downloadsDir)
    if (SystemFileSystem.exists(dir)) {
        val apks = runCatching {
            SystemFileSystem.list(dir).filter { path ->
                val name = path.name
                BulletinApkUpdatePolicy.matchesAutoUpdateApk(name)
            }
        }.getOrNull().orEmpty()

        val newestApk = apks.maxByOrNull { path ->
            runCatching { SystemFileSystem.metadataOrNull(path)?.size }.getOrNull() ?: 0L
        }
        if (newestApk != null) {
            val meta = runCatching { SystemFileSystem.metadataOrNull(newestApk) }.getOrNull()
            if (meta != null && meta.size > 1024L) {
                val fileName = newestApk.name
                val version = BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
                if (isRemoteVersionNewer(currentAppVersionName(), version)) {
                    val matchingNote = noteRepo.notes.value.firstOrNull {
                        it.attachmentFileName == fileName
                    }
                    if (matchingNote == null || PendingUpdateStore.getNoteInstallStatus(matchingNote.noteId) == null) {
                        return PendingUpdateOffer(
                            remoteVersion = version,
                            releaseTitle = "FileApex $version",
                            releaseNotes = null,
                            assetName = fileName,
                            assetDownloadUrl = "",
                            assetSizeBytes = meta.size,
                            localFilePath = newestApk.toString(),
                            originNoteId = matchingNote?.noteId
                        )
                    }
                }
            }
        }
    }
    return null
}
