package com.fileapex.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.i18n.AppI18n
import java.io.File

actual fun openLocalFile(absolutePath: String, displayName: String) {
    val context = androidAppContextOrNull() ?: return
    val file = File(absolutePath)
    if (!file.isFile) return

    val fileName = displayName.ifBlank { file.name }

    if (com.fileapex.update.BulletinApkUpdatePolicy.matchesAutoUpdateApk(fileName)) {
        val version = com.fileapex.update.BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
        val sig = com.fileapex.update.BulletinApkUpdatePolicy.buildFileSignature(fileName, file.length(), file.lastModified())
        com.fileapex.update.PendingUpdateStore.markProcessedFile(sig)
        val offer = com.fileapex.update.PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = "FileApex $version",
            releaseNotes = null,
            assetName = fileName,
            assetDownloadUrl = "",
            assetSizeBytes = file.length(),
            localFilePath = file.absolutePath
        )
        com.fileapex.update.PendingUpdateStore.save(offer)
        notifyAppUpdateAvailable(offer)
        runCatching {
            com.fileapex.update.PlatformUpdateInstaller.installAndRelaunch(
                localFilePath = file.absolutePath,
                remoteVersion = version
            )
        }.onFailure { error ->
            println("openLocalFile: FileApex APK install failed - ${error.message}")
        }
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        FileApexFileProvider.authority(context),
        file
    )
    val mimeType = resolveMimeType(fileName)

    val testIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val resolveInfo = runCatching {
        context.packageManager.resolveActivity(testIntent, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrNull()

    if (resolveInfo != null) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(viewIntent)
        }.onFailure {
            val chooser = Intent.createChooser(viewIntent, fileName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
    } else {
        val downloadsDir = defaultDownloadsDir()
        val targetPath = if (!file.absolutePath.startsWith(downloadsDir)) {
            val destPath = UniqueFileNames.resolveInDirectory(downloadsDir, fileName)
            runCatching {
                file.copyTo(File(destPath), overwrite = true)
                destPath
            }.getOrDefault(file.absolutePath)
        } else {
            file.absolutePath
        }
        val targetName = File(targetPath).name
        BriefToast.show(AppI18n.t("no_handler_saved_to_storage", targetName))
    }
}

fun resolveMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase().trim()
    if (ext.isEmpty()) return "*/*"
    val mapped = runCatching {
        MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
    }.getOrNull()
    if (!mapped.isNullOrBlank()) return mapped
    return when (ext) {
        "csv" -> "text/csv"
        "log" -> "text/plain"
        "json" -> "application/json"
        "sql" -> "application/sql"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "xml" -> "text/xml"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

