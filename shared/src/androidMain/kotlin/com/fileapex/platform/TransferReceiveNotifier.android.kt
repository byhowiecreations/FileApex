package com.fileapex.platform

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices

private lateinit var notifierContext: Context

fun initAndroidTransferReceiveNotifier(context: Context) {
    notifierContext = context.applicationContext
    AndroidNotificationChannels.ensureTransferReceiveChannel(notifierContext)
}

actual fun notifyFilesReceived(fileNames: List<String>) {
    if (fileNames.isEmpty()) return
    if (!FileApexServices.settings.fileTransferNotificationsEnabled.value) {
        println("TransferReceiveNotifier: skipped — notifications disabled in Settings")
        return
    }
    if (!::notifierContext.isInitialized) {
        println("TransferReceiveNotifier: skipped — notifier not initialized")
        return
    }

    val manager = NotificationManagerCompat.from(notifierContext)
    if (!manager.areNotificationsEnabled()) {
        println("TransferReceiveNotifier: skipped — system notifications disabled for FileApex")
        return
    }

    val title = if (fileNames.size == 1) {
        "File received"
    } else {
        "${fileNames.size} files received"
    }
    val body = fileNames.joinToString(separator = ", ")

    val notification = NotificationCompat.Builder(notifierContext, AndroidNotificationChannels.TRANSFER_RECEIVE)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    runCatching {
        manager.notify(NOTIFICATION_ID_BASE + (fileNames.hashCode() and 0xFFFF), notification)
    }.onFailure { error ->
        println("TransferReceiveNotifier: notify failed :: ${error.message}")
    }
}
private const val NOTIFICATION_ID_BASE = 4200
