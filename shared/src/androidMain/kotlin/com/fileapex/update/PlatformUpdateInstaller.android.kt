package com.fileapex.update

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.FileApexFileProvider
import java.io.File
import java.io.RandomAccessFile

actual object PlatformUpdateInstaller {
    actual fun updateCacheDirectory(): String {
        val context = requireContext()
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    actual fun selectAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.contains("debug", ignoreCase = true)
        }
        return apks.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            lower.contains("arm64v8a") || lower.contains("arm64-v8a")
        } ?: apks.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            !lower.contains("armv7") &&
                !lower.contains("armeabi") &&
                !lower.contains("x86")
        } ?: apks.firstOrNull()
    }

    actual fun installAndRelaunch(localFilePath: String, remoteVersion: String) {
        val context = requireContext()
        val apkFile = File(localFilePath)
        check(apkFile.isFile) { AppI18n.t("update_file_missing") }
        validateApkFile(apkFile)

        // Never launch system Settings from install/update paths (BAL). Read-only check only.
        if (!PlatformInstallPermission.canRequestPackageInstalls()) {
            error(AppI18n.t("update_allow_unknown_apps"))
        }

        val uri = FileProvider.getUriForFile(
            context,
            FileApexFileProvider.authority(context),
            apkFile
        )

        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        grantUriToResolvers(installIntent, uri)
        grantUriToResolvers(viewIntent, uri)

        println(
            "PlatformUpdateInstaller: launching system installer for $remoteVersion " +
                "(${apkFile.name}, ${apkFile.length()} bytes)"
        )
        val launched = runCatching {
            context.startActivity(installIntent)
            true
        }.getOrElse { error ->
            println(
                "PlatformUpdateInstaller: ACTION_INSTALL_PACKAGE failed - ${error.message}; " +
                    "falling back to ACTION_VIEW"
            )
            false
        }
        if (!launched) {
            context.startActivity(viewIntent)
        }
    }

    private fun grantUriToResolvers(intent: Intent, uri: android.net.Uri) {
        val context = requireContext()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_DEFAULT_ONLY
        } else {
            0
        }
        val matches = context.packageManager.queryIntentActivities(intent, flags)
        for (resolve in matches) {
            val packageName = resolve.activityInfo?.packageName ?: continue
            runCatching {
                context.grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun validateApkFile(apkFile: File) {
        check(apkFile.length() > 1_024L) {
            AppI18n.t("update_too_small")
        }
        RandomAccessFile(apkFile, "r").use { raf ->
            val magic = ByteArray(4)
            check(raf.read(magic) == 4) { AppI18n.t("update_invalid_package") }
            val isZip =
                magic[0] == 0x50.toByte() &&
                    magic[1] == 0x4B.toByte() &&
                    (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte() || magic[2] == 0x07.toByte())
            check(isZip) {
                AppI18n.t("update_html_download")
            }
        }
        val context = androidAppContextOrNull()
        if (context != null) {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_ACTIVITIES
            )
            check(archiveInfo != null) {
                AppI18n.t("update_invalid_package")
            }
        }
    }

    private fun requireContext() =
        androidAppContextOrNull()
            ?: error(AppI18n.t("initialization_incomplete"))
}
