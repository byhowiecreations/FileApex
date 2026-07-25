package com.fileapex.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.shared.R
import com.fileapex.update.PendingUpdateOffer

private lateinit var updateNotifierContext: Context

fun initAndroidUpdateAvailableNotifier(context: Context) {
    updateNotifierContext = context.applicationContext
    AndroidNotificationChannels.ensureAppUpdatesChannel(updateNotifierContext)
}

actual fun notifyAppUpdateAvailable(offer: PendingUpdateOffer) {
    if (!::updateNotifierContext.isInitialized) {
        println("UpdateAvailableNotifier: skipped — not initialized")
        return
    }
    val manager = NotificationManagerCompat.from(updateNotifierContext)
    if (!manager.areNotificationsEnabled()) {
        println("UpdateAvailableNotifier: skipped — notifications disabled")
        return
    }

    val title = "FileApex ${offer.remoteVersion} available"
    val body = offer.notificationDetail()

    val contentIntent = PendingIntent.getBroadcast(
        updateNotifierContext,
        REQUEST_OPEN_UPDATE,
        Intent(updateNotifierContext, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationActions.ACTION_OPEN_UPDATE
        },
        pendingIntentFlags()
    )
    val downloadIntent = PendingIntent.getBroadcast(
        updateNotifierContext,
        REQUEST_DOWNLOAD_UPDATE,
        Intent(updateNotifierContext, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationActions.ACTION_DOWNLOAD_UPDATE
        },
        pendingIntentFlags()
    )
    val skipIntent = PendingIntent.getBroadcast(
        updateNotifierContext,
        REQUEST_SKIP_UPDATE,
        Intent(updateNotifierContext, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationActions.ACTION_SKIP_UPDATE
        },
        pendingIntentFlags()
    )

    val notification = NotificationCompat.Builder(
        updateNotifierContext,
        AndroidNotificationChannels.APP_UPDATES
    )
        .setSmallIcon(R.drawable.ic_fileapex_notification)
        .setContentTitle(title)
        .setContentText(body.lineSequence().firstOrNull() ?: title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(contentIntent)
        .addAction(
            android.R.drawable.stat_sys_download,
            "Install",
            downloadIntent
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Skip",
            skipIntent
        )
        .setAutoCancel(false)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    runCatching {
        manager.notify(NOTIFICATION_ID, notification)
    }.onFailure { error ->
        println("UpdateAvailableNotifier: notify failed :: ${error.message}")
    }
}

actual fun dismissAppUpdateNotification() {
    if (!::updateNotifierContext.isInitialized) return
    NotificationManagerCompat.from(updateNotifierContext).cancel(NOTIFICATION_ID)
}

private fun pendingIntentFlags(): Int {
    return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

private const val NOTIFICATION_ID = 4301
private const val REQUEST_OPEN_UPDATE = 4302
private const val REQUEST_DOWNLOAD_UPDATE = 4303
private const val REQUEST_SKIP_UPDATE = 4304
