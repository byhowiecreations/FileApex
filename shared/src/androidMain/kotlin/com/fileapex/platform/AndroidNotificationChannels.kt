package com.fileapex.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.fileapex.shared.R

object AndroidNotificationChannels {
    /** Monochrome app silhouette — every FileApex status-bar notification icon. */
    val smallIcon: Int = R.drawable.ic_fileapex_notification

    /** Full-color app icon for notification shade / expanded headers. */
    val largeIcon: Int = R.drawable.ic_fileapex_large

    /** Note alerts: app icon + N badge (status bar and shade). */
    val noteSmallIcon: Int = R.drawable.ic_fileapex_note_notification
    val noteLargeIcon: Int = R.drawable.ic_fileapex_note_large
    private const val TAG = "NotificationChannels"
    private const val PREFS_NAME = "fileapex_notification_channels"
    private const val KEY_SHARE_SERVER_MIGRATED = "share_server_v2_channel_migrated"

    const val APP_UPDATES = "fileapex_app_updates"
    const val TRANSFER_RECEIVE = "fileapex_transfer_receive"
    const val NOTE_MESSAGES = "fileapex_note_messages"
    const val DRIVE_RELAY = "fileapex_drive_relay"
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
            ?.createNotificationChannel(channel)
    }

    fun ensureNoteMessagesChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTE_MESSAGES,
            "Notes & Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when new notes or shared messages arrive from paired devices"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
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
            ?.createNotificationChannel(channel)
    }

    fun ensureDriveRelayChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DRIVE_RELAY,
            "Google Drive Relay",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when FileApex posts or retrieves files through Google Drive Relay"
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun ensureShareServerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(SHARE_SERVER_ACTIVE)
        if (existing != null && existing.importance == NotificationManager.IMPORTANCE_LOW) {
            return
        }
        migrateLegacyShareServerChannels(context)
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHARE_SERVER_MIGRATED, false)) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val activeNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNotifications ?: emptyArray()
        } else {
            emptyArray()
        }
        val activeChannelIds = activeNotifications.mapNotNull { it.notification.channelId }.toSet()

        var deferred = false

        if (activeChannelIds.contains(LEGACY_SHARE_SERVER_CHANNEL_V1)) {
            Log.w(TAG, "Cannot delete legacy channel $LEGACY_SHARE_SERVER_CHANNEL_V1 while active - deferring migration")
            deferred = true
        } else {
            runCatching { manager.deleteNotificationChannel(LEGACY_SHARE_SERVER_CHANNEL_V1) }
        }

        if (activeChannelIds.contains(LEGACY_SHARE_SERVER_CHANNEL)) {
            Log.w(TAG, "Cannot delete legacy channel $LEGACY_SHARE_SERVER_CHANNEL while active - deferring migration")
            deferred = true
        } else {
            runCatching { manager.deleteNotificationChannel(LEGACY_SHARE_SERVER_CHANNEL) }
        }

        val existing = manager.getNotificationChannel(SHARE_SERVER_ACTIVE)
        if (existing != null && existing.importance != NotificationManager.IMPORTANCE_LOW) {
            if (activeChannelIds.contains(SHARE_SERVER_ACTIVE)) {
                Log.w(
                    TAG,
                    "Cannot delete channel $SHARE_SERVER_ACTIVE while FGS notification is active - deferring migration"
                )
                deferred = true
            } else {
                runCatching { manager.deleteNotificationChannel(SHARE_SERVER_ACTIVE) }
            }
        }

        if (deferred) return

        prefs.edit().putBoolean(KEY_SHARE_SERVER_MIGRATED, true).apply()
        Log.i(TAG, "Legacy share server notification channel migration complete")
    }
}


