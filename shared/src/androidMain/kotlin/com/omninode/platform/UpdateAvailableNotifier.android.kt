package com.omninode.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private lateinit var updateNotifierContext: Context

fun initAndroidUpdateAvailableNotifier(context: Context) {
    updateNotifierContext = context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a newer OmniNode build is available"
        }
        val manager = updateNotifierContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

actual fun notifyAppUpdateAvailable(versionLabel: String, detail: String?) {
    if (!::updateNotifierContext.isInitialized) {
        println("UpdateAvailableNotifier: skipped — not initialized")
        return
    }
    val manager = NotificationManagerCompat.from(updateNotifierContext)
    if (!manager.areNotificationsEnabled()) {
        println("UpdateAvailableNotifier: skipped — notifications disabled")
        return
    }
    val title = "OmniNode $versionLabel available"
    val body = detail?.takeIf { it.isNotBlank() }
        ?: "A newer build is ready. Installing…"
    val notification = NotificationCompat.Builder(updateNotifierContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    runCatching {
        manager.notify(NOTIFICATION_ID, notification)
    }.onFailure { error ->
        println("UpdateAvailableNotifier: notify failed :: ${error.message}")
    }
}

private const val CHANNEL_ID = "omninode_app_updates"
private const val NOTIFICATION_ID = 4301
