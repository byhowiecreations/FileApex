package com.fileapex.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.update.FileApexAppVersion
import com.fileapex.util.TimeUtils
import java.io.File
import java.net.URLEncoder
import java.time.Instant

actual object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_LOG_EVENTS = 200
    private const val FEEDBACK_EMAIL = "byhowiecreations@gmail.com"
    private const val GOOGLE_FORM_BASE_URL =
        "https://docs.google.com/forms/d/e/1FAIpQLSePtAhGfpA1iPxP6S_yTjlMTAQcYX66S76bZTpVVuGeOFrWXg/viewform?usp=pp_url"
    private const val GITHUB_ISSUES_URL = "https://github.com/byhowiecreations/FileApex/issues"

    private val lock = Any()
    private val logBuffer = ArrayDeque<String>(MAX_LOG_EVENTS)

    actual fun log(event: String) {
        val now = TimeUtils.now()
        val iso = Instant.ofEpochMilli(now).toString()
        val entry = "[$iso] $event"
        synchronized(lock) {
            if (logBuffer.size >= MAX_LOG_EVENTS) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
        }
        Log.i(TAG, entry)
    }

    actual fun dumpLogs(): List<String> = synchronized(lock) { logBuffer.toList() }

    fun dumpDiagnosticsFile(context: Context): File {
        val cacheFile = File(context.cacheDir, "fileapex_diagnostics.txt")
        val logs = dumpLogs()
        val nowIso = Instant.ofEpochMilli(TimeUtils.now()).toString()
        val flavor = if (FileApexServices.isPlayStoreBuild) "play" else "github"
        cacheFile.bufferedWriter().use { writer ->
            writer.appendLine("=== FileApex Diagnostics Log ===")
            writer.appendLine("App Version: ${FileApexAppVersion.NAME} (Code: ${FileApexAppVersion.CODE})")
            writer.appendLine("Channel: ${FileApexServices.buildChannel} ($flavor)")
            writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            writer.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("Exported At: $nowIso")
            writer.appendLine("Captured Events: ${logs.size}")
            writer.appendLine("================================")
            if (logs.isEmpty()) {
                writer.appendLine("(No operational events recorded in buffer)")
            } else {
                logs.forEach { writer.appendLine(it) }
            }
        }
        return cacheFile
    }

    fun getDiagnosticsShareableUri(context: Context): Uri {
        val file = dumpDiagnosticsFile(context)
        return FileProvider.getUriForFile(
            context,
            FileApexFileProvider.authority(context),
            file
        )
    }

    actual fun sendFeedbackEmail(): Boolean {
        val context = androidAppContextOrNull() ?: return false
        return try {
            val file = dumpDiagnosticsFile(context)
            val uri = getDiagnosticsShareableUri(context)
            val flavor = if (FileApexServices.isPlayStoreBuild) "play" else "github"
            val body = buildString {
                appendLine("Please describe the issue or feedback below:")
                appendLine()
                appendLine()
                appendLine("--- Device Telemetry ---")
                appendLine("App Version: ${FileApexAppVersion.NAME} (Code: ${FileApexAppVersion.CODE})")
                appendLine("Flavor: $flavor")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Diagnostics File: ${file.name}")
            }
            val selectorIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
            }
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "FileApex v${FileApexAppVersion.NAME} Feedback & Diagnostics")
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("Diagnostics", uri)
                selector = selectorIntent
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val targets = context.packageManager.queryIntentActivities(
                selectorIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).ifEmpty {
                context.packageManager.queryIntentActivities(selectorIntent, 0)
            }
            for (resolveInfo in targets) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                context.startActivity(emailIntent)
                true
            } catch (anfe: android.content.ActivityNotFoundException) {
                val fallbackIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$FEEDBACK_EMAIL")
                    putExtra(Intent.EXTRA_SUBJECT, "FileApex v${FileApexAppVersion.NAME} Feedback & Diagnostics")
                    putExtra(Intent.EXTRA_TEXT, body)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch default email client", e)
            false
        }
    }

    actual fun openAnonymousFeedbackForm() {
        val context = androidAppContextOrNull() ?: return
        try {
            val flavor = if (FileApexServices.isPlayStoreBuild) "play" else "github"
            val telemetry = "Version: ${FileApexAppVersion.NAME} (Code: ${FileApexAppVersion.CODE}), " +
                "Flavor: $flavor, " +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val encodedTelemetry = URLEncoder.encode(telemetry, "UTF-8")
            val fullUrl = "$GOOGLE_FORM_BASE_URL&entry.1576900639=$encodedTelemetry"
            launchCustomTabOrBrowser(context, fullUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open anonymous feedback form", e)
        }
    }

    actual fun openGitHubIssuesTracker() {
        val context = androidAppContextOrNull() ?: return
        try {
            launchCustomTabOrBrowser(context, GITHUB_ISSUES_URL)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open GitHub issues tracker", e)
        }
    }

    private fun launchCustomTabOrBrowser(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}
