package com.fileapex.friends

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import com.fileapex.platform.FileApexFileProvider
import com.fileapex.platform.resolveMimeType
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.i18n.AppI18n
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.platform.AndroidNotificationChannels

private lateinit var friendsNotifierContext: Context

fun initAndroidFriendsNotifier(context: Context) {
    friendsNotifierContext = context.applicationContext
}

private const val ACTION_ACCEPT = "com.fileapex.friends.ACCEPT"
private const val ACTION_DECLINE = "com.fileapex.friends.DECLINE"
private const val ACTION_ACCEPT_ALWAYS = "com.fileapex.friends.ACCEPT_ALWAYS"
private const val EXTRA_OFFER_ID = "com.fileapex.friends.OFFER_ID"
/** The receiver in [FriendsHub] gives up after two minutes, so the notification goes away with it. */
private const val OFFER_TIMEOUT_MS = 120_000L
private const val NOTIFICATION_TAG = "friends_offer"
private const val ACTION_CANCEL_TRANSFER = "com.fileapex.friends.CANCEL_TRANSFER"
private const val EXTRA_TRANSFER_ID = "com.fileapex.friends.TRANSFER_ID"
private const val TRANSFER_TAG = "friends_transfer"
private const val TRANSFER_REDRAW_MS = 1_000L
private val lastTransferPost = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, FriendTransferStatus>>()

private fun transferNotificationId(transferId: String) = 4500 + (transferId.hashCode() and 0xFFF)

private fun notificationId(offerId: String) = 4400 + (offerId.hashCode() and 0xFFF)

private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

actual object FriendsNotifier {
    actual fun offerPending(offer: PendingFriendOffer) {
        if (!::friendsNotifierContext.isInitialized) return
        // In front, the Accept window is already showing.
        if (PresenceForegroundRefresh.isForeground.value) return
        val context = friendsNotifierContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        AndroidNotificationChannels.ensureFriendsIncomingChannel(context)
        val id = notificationId(offer.id)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val openPending = open?.let { PendingIntent.getActivity(context, id, it, flags()) }
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, id * 4 + code,
            Intent(name).setPackage(context.packageName).putExtra(EXTRA_OFFER_ID, offer.id),
            flags()
        )
        val body = AppI18n.t("friends_offer_body", offer.fileName, formatSize(offer.sizeBytes))
        val notification = NotificationCompat.Builder(context, AndroidNotificationChannels.FRIENDS_INCOMING)
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, AndroidNotificationChannels.largeIcon))
            .setContentTitle(AppI18n.t("friends_offer_title", offer.friendLabel))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPending)
            .addAction(0, AppI18n.t("friends_accept"), action(ACTION_ACCEPT, 0))
            .addAction(0, AppI18n.t("friends_decline"), action(ACTION_DECLINE, 1))
            .addAction(0, AppI18n.t("friends_accept_always"), action(ACTION_ACCEPT_ALWAYS, 2))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_TAG, id, notification) }
            .onFailure { println("FriendsNotifier: notify failed :: ${it.message}") }
    }

    actual fun transferChanged(transfer: FriendTransfer) {
        if (!::friendsNotifierContext.isInitialized || transfer.status == FriendTransferStatus.QUEUED) return
        val context = friendsNotifierContext
        val manager = NotificationManagerCompat.from(context)
        val id = transferNotificationId(transfer.id)
        if (transfer.status.finished) {
            lastTransferPost.remove(transfer.id)
            runCatching { manager.cancel(TRANSFER_TAG, id) }
            return
        }
        val now = System.currentTimeMillis()
        val last = lastTransferPost[transfer.id]
        if (last != null && last.second == transfer.status && now - last.first < TRANSFER_REDRAW_MS) return
        if (!manager.areNotificationsEnabled()) return
        lastTransferPost[transfer.id] = now to transfer.status
        AndroidNotificationChannels.ensureFriendsTransfersChannel(context)
        val title = AppI18n.t(if (transfer.direction == FriendTransferDirection.SEND) "friends_notif_sending" else "friends_notif_receiving", transfer.friendLabel)
        val percent = (transfer.fraction * 100).toInt()
        val cancel = PendingIntent.getBroadcast(
            context, id,
            Intent(ACTION_CANCEL_TRANSFER).setPackage(context.packageName).putExtra(EXTRA_TRANSFER_ID, transfer.id),
            flags()
        )
        val steady = transfer.status == FriendTransferStatus.ACTIVE && transfer.totalBytes > 0
        val notification = NotificationCompat.Builder(context, AndroidNotificationChannels.FRIENDS_TRANSFERS)
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setContentTitle(title)
            .setContentText(if (steady) "${transfer.fileName} · $percent%" else "${transfer.fileName} · ${AppI18n.t(statusKey(transfer))}")
            .setProgress(100, percent, !steady)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, AppI18n.t("friends_cancel"), cancel)
            .build()
        runCatching { manager.notify(TRANSFER_TAG, id, notification) }
            .onFailure { println("FriendsNotifier: progress notify failed :: ${it.message}") }
    }

    actual fun fileReceived(file: FriendReceivedFile) {
        if (!::friendsNotifierContext.isInitialized) return
        // In front, the in-app card is showing.
        if (PresenceForegroundRefresh.isForeground.value) return
        if (!com.fileapex.di.FileApexServices.settings.fileTransferNotificationsEnabled.value) return
        val context = friendsNotifierContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        AndroidNotificationChannels.ensureTransferReceiveChannel(context)
        val id = 4200 + (file.path.hashCode() and 0xFFFF)
        val target = File(file.path)
        val viewUri = runCatching { FileProvider.getUriForFile(context, FileApexFileProvider.authority(context), target) }.getOrNull()
        val view = viewUri?.let {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it, resolveMimeType(file.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        val folder = target.parentFile?.absolutePath.orEmpty()
        val location = if (folder.startsWith("$root/")) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + folder.removePrefix("$root/")),
                    "vnd.android.document/directory"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val locationPending = PendingIntent.getActivity(context, id * 2, location, flags())
        val builder = NotificationCompat.Builder(context, AndroidNotificationChannels.TRANSFER_RECEIVE)
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, AndroidNotificationChannels.largeIcon))
            .setContentTitle(AppI18n.t("friends_received_title", file.friendLabel))
            .setContentText(file.fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(file.fileName))
            .setContentIntent(locationPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        view?.let { builder.addAction(0, AppI18n.t("friends_view"), PendingIntent.getActivity(context, id * 2 + 1, it, flags())) }
        builder.addAction(0, AppI18n.t("friends_open_location"), locationPending)
        runCatching { manager.notify(id, builder.build()) }
            .onFailure { println("FriendsNotifier: received notify failed :: ${it.message}") }
    }

    actual fun offerResolved(offerId: String) {
        if (!::friendsNotifierContext.isInitialized) return
        runCatching { NotificationManagerCompat.from(friendsNotifierContext).cancel(NOTIFICATION_TAG, notificationId(offerId)) }
    }
}

/** Accept and Decline buttons of the notification; the offer lives in this process, so the hub answers it directly. */
class FriendsOfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL_TRANSFER) {
            intent.getStringExtra(EXTRA_TRANSFER_ID)?.let { FriendsHub.cancel(it) }
            return
        }
        val offerId = intent.getStringExtra(EXTRA_OFFER_ID) ?: return
        FriendsHub.decide(offerId, intent.action != ACTION_DECLINE, intent.action == ACTION_ACCEPT_ALWAYS)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, notificationId(offerId)) }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** String key of the short status shown while a transfer is not yet moving bytes. */
fun statusKey(transfer: FriendTransfer): String = when (transfer.status) {
    FriendTransferStatus.PREPARING -> "friends_status_preparing"
    FriendTransferStatus.CONNECTING -> "friends_status_connecting"
    FriendTransferStatus.WAITING -> "friends_status_waiting"
    FriendTransferStatus.VERIFYING -> "friends_status_verifying"
    else -> if (transfer.direction == FriendTransferDirection.SEND) "friends_status_sending" else "friends_status_receiving"
}
