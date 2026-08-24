package com.fileapex.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.update.PendingUpdateOffer

private lateinit var updateNotifierContext: Context

fun initAndroidUpdateAvailableNotifier(context: Context) {
    updateNotifierContext = context.applicationContext
    AndroidNotificationChannels.ensureAppUpdatesChannel(updateNotifierContext)
}

actual fun notifyAppUpdateAvailable(offer: PendingUpdateOffer) {
    if (!::updateNotifierContext.isInitialized) {
        println("UpdateAvailableNotifier: skipped - not initialized")
        return
    }
    val manager = NotificationManagerCompat.from(updateNotifierContext)
    if (!manager.areNotificationsEnabled()) {
        println("UpdateAvailableNotifier: skipped - notifications disabled")
        return
    }

    val title = com.fileapex.i18n.AppI18n.t("update_available_title", offer.remoteVersion)
    val body = offer.notificationDetail()

    // Activity PendingIntents — BroadcastReceiver startActivity is blocked by BAL on Samsung/API 31+.
    val contentIntent = activityPendingIntent(
        requestCode = REQUEST_OPEN_UPDATE,
        extras = mapOf(EXTRA_SHOW_UPDATE_SHEET to true)
    )
    val downloadIntent = activityPendingIntent(
        requestCode = REQUEST_DOWNLOAD_UPDATE,
        extras = mapOf(
            EXTRA_DOWNLOAD_UPDATE to true
        )
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
        .setSmallIcon(AndroidNotificationChannels.smallIcon)
        .setLargeIcon(
            BitmapFactory.decodeResource(updateNotifierContext.resources, AndroidNotificationChannels.largeIcon)
        )
        .setContentTitle(title)
        .setContentText(body.lineSequence().firstOrNull() ?: title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(contentIntent)
        .addAction(
            android.R.drawable.stat_sys_download,
            com.fileapex.i18n.AppI18n.t("install"),
            downloadIntent
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            com.fileapex.i18n.AppI18n.t("skip"),
            skipIntent
        )
        .setAutoCancel(true)
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

private fun activityPendingIntent(requestCode: Int, extras: Map<String, Boolean>): PendingIntent {
    val launch = updateNotifierContext.packageManager
        .getLaunchIntentForPackage(updateNotifierContext.packageName)
        ?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
        ?: Intent().setClassName(updateNotifierContext.packageName, MAIN_ACTIVITY_CLASS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
    return PendingIntent.getActivity(
        updateNotifierContext,
        requestCode,
        launch,
        pendingIntentFlags()
    )
}

private fun pendingIntentFlags(): Int {
    return PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}

const val EXTRA_SHOW_UPDATE_SHEET = "com.fileapex.extra.SHOW_UPDATE_SHEET"
const val EXTRA_DOWNLOAD_UPDATE = "com.fileapex.extra.DOWNLOAD_UPDATE"

private const val NOTIFICATION_ID = 4301
private const val REQUEST_OPEN_UPDATE = 4302
private const val REQUEST_DOWNLOAD_UPDATE = 4303
private const val REQUEST_SKIP_UPDATE = 4304
private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"
