package com.omninode.update

import android.content.Intent
import androidx.core.content.FileProvider
import com.omninode.data.settings.androidAppContextOrNull
import java.io.File

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
        return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    actual fun installAndRelaunch(localFilePath: String, remoteVersion: String) {
        val context = requireContext()
        val apkFile = File(localFilePath)
        check(apkFile.isFile) { "APK missing at $localFilePath" }

        if (!PlatformInstallPermission.ensureCanRequestPackageInstalls()) {
            error(
                "Allow “Install unknown apps” for OmniNode, then reopen the app to finish updating"
            )
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        println(
            "PlatformUpdateInstaller: launching system installer for $remoteVersion " +
                "(${apkFile.name})"
        )
        context.startActivity(installIntent)
    }

    private fun requireContext() =
        androidAppContextOrNull()
            ?: error("Android application context not initialized for update install")
}
