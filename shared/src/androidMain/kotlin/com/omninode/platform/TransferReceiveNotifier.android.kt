package com.omninode.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.omninode.di.OmniNodeServices

private lateinit var notifierContext: Context

fun initAndroidTransferReceiveNotifier(context: Context) {
    notifierContext = context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File transfers",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when OmniNode receives files from paired devices"
        }
        val manager = notifierContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

actual fun notifyFilesReceived(fileNames: List<String>) {
    if (fileNames.isEmpty()) return
    if (!OmniNodeServices.settings.fileTransferNotificationsEnabled.value) return
    if (!::notifierContext.isInitialized) return

    val title = if (fileNames.size == 1) {
        "File received"
    } else {
        "${fileNames.size} files received"
    }
    val body = fileNames.joinToString(separator = ", ")

    val notification = NotificationCompat.Builder(notifierContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    runCatching {
        NotificationManagerCompat.from(notifierContext)
            .notify(NOTIFICATION_ID_BASE + (fileNames.hashCode() and 0xFFFF), notification)
    }
}

private const val CHANNEL_ID = "omninode_transfer_receive"
private const val NOTIFICATION_ID_BASE = 4200
