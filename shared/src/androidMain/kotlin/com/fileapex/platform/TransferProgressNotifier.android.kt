package com.fileapex.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.LiveTransferStats
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.domain.transfer.TransferNotificationContent
import com.fileapex.domain.transfer.transferNotificationContent
import com.fileapex.i18n.AppI18n
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps one ongoing progress notification while a transfer runs, so progress stays visible after the user leaves the app.
 * On Android 16 it asks to be promoted to a Live Update (status bar chip); older versions show a normal progress notification.
 * It follows the Live Transfer setting and the system notification permission, and updates at most twice a second.
 */
object TransferProgressNotifier {
    private const val NOTIFICATION_ID = 3999
    private const val UPDATE_INTERVAL_MS = 500L
    private const val ACTION_CANCEL = "com.fileapex.action.CANCEL_TRANSFER"
    /** Android 16 Live Updates: asks the system to show the notification as a status bar chip. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    @Volatile
    private var started = false

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        AndroidNotificationChannels.ensureTransferProgressChannel(appContext)
        scope.launch {
            val settings = FileApexServices.settings
            combine(
                TransferActivityGuard.statsFlow,
                settings.liveTransferCapsuleEnabled,
                settings.fileTransferNotificationsEnabled,
                FileApexServices.transferQueue.pendingItems
            ) { stats, capsule, notifications, pending -> Snapshot(stats, capsule && notifications, pending.size) }
                .collectLatest { snapshot ->
                    val manager = NotificationManagerCompat.from(appContext)
                    if (snapshot.shown && manager.areNotificationsEnabled()) {
                        val content = transferNotificationContent(snapshot.stats, snapshot.queueCount)
                        cancellableCatching("TransferProgressNotifier notify") {
                            manager.notify(NOTIFICATION_ID, build(appContext, content))
                        }.onFailure { println("TransferProgressNotifier: notify failed :: ${it.message}") }
                        delay(UPDATE_INTERVAL_MS)
                    } else {
                        manager.cancel(NOTIFICATION_ID)
                    }
                }
        }
    }

    private class Snapshot(val stats: LiveTransferStats, enabled: Boolean, val queueCount: Int) {
        val shown = enabled && stats.isActive
    }

    private fun build(context: Context, content: TransferNotificationContent): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, TransferCancelReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelLabel = AppI18n.t("cancel")
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setProgressIndeterminate(content.indeterminate)
                .setProgress(content.percent)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
            val builder = Notification.Builder(context, AndroidNotificationChannels.TRANSFER_PROGRESS)
                .setSmallIcon(AndroidNotificationChannels.smallIcon)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setSubText(content.subText.ifBlank { null })
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setStyle(style)
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                .setShortCriticalText(if (content.indeterminate) "..." else "${content.percent}%")
            if (content.cancelable) {
                builder.addAction(
                    Notification.Action.Builder(Icon.createWithResource(context, AndroidNotificationChannels.smallIcon), cancelLabel, cancel).build()
                )
            }
            return builder.build()
        }
        val builder = NotificationCompat.Builder(context, AndroidNotificationChannels.TRANSFER_PROGRESS)
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText.ifBlank { null })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, content.percent, content.indeterminate)
        if (content.cancelable) builder.addAction(0, cancelLabel, cancel)
        return builder.build()
    }
}

/** The Cancel button on the progress notification; only this app can send it. */
class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TransferActivityGuard.cancelActiveTransfers()
    }
}
