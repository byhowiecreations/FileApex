package com.fileapex.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Single source of truth for FileApex Android notification channel ids and creation.
 */
object AndroidNotificationChannels {
    const val APP_UPDATES = "fileapex_app_updates"
    const val TRANSFER_RECEIVE = "fileapex_transfer_receive"
    /** Persistent share-server FGS alert — static after first post ([ShareServerForegroundNotification]). */
    const val SHARE_SERVER_ACTIVE = "fileapex_share_server_active_v2"
    private const val LEGACY_SHARE_SERVER_CHANNEL = "fileapex_share_server_active"
    private const val LEGACY_SHARE_SERVER_CHANNEL_V1 = "FileApexServerChannel"

    fun ensureAppUpdatesChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            APP_UPDATES,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a newer FileApex build is available"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun ensureTransferReceiveChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            TRANSFER_RECEIVE,
            "File transfers",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when FileApex receives files from paired devices"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun ensureShareServerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(SHARE_SERVER_ACTIVE)
        if (existing != null && existing.importance == NotificationManager.IMPORTANCE_LOW) {
            return
        }
        migrateLegacyShareServerChannels(manager)
        val channel = NotificationChannel(
            SHARE_SERVER_ACTIVE,
            "FileApex Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent alert while the FileApex share server is running"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** One-time legacy cleanup — not on the FGS [startForeground] critical path. */
    fun migrateLegacyShareServerChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        migrateLegacyShareServerChannels(context.getSystemService(NotificationManager::class.java))
    }

    private fun migrateLegacyShareServerChannels(manager: NotificationManager) {
        manager.deleteNotificationChannel(LEGACY_SHARE_SERVER_CHANNEL_V1)
        manager.deleteNotificationChannel(LEGACY_SHARE_SERVER_CHANNEL)
        val existing = manager.getNotificationChannel(SHARE_SERVER_ACTIVE)
        if (existing != null && existing.importance != NotificationManager.IMPORTANCE_LOW) {
            manager.deleteNotificationChannel(SHARE_SERVER_ACTIVE)
        }
    }
}
