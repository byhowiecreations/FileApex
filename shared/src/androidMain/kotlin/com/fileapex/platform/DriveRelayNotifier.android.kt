package com.fileapex.platform

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices

private lateinit var driveNotifierContext: Context

fun initAndroidDriveRelayNotifier(context: Context) {
    driveNotifierContext = context.applicationContext
    if (FileApexServices.settings.driveRelayNotificationsEnabled.value) {
        AndroidNotificationChannels.ensureDriveRelayChannel(driveNotifierContext)
    }
}

actual object DriveRelayNotifier {
    actual fun onDriveEnabledAndGranted() {
        FileApexServices.settings.setDriveRelayNotificationsEnabled(true)
        if (::driveNotifierContext.isInitialized) {
            AndroidNotificationChannels.ensureDriveRelayChannel(driveNotifierContext)
        }
    }

    actual fun notifyPosted(fileNames: List<String>, targetNames: List<String>) {
        if (fileNames.isEmpty()) return
        val targets = targetNames.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "paired devices" }
        val title = if (fileNames.size == 1) "Sent via Google Drive Relay" else "${fileNames.size} files sent via Drive Relay"
        val body = "${fileNames.joinToString(", ")} → $targets"
        post(title, body)
    }

    actual fun notifyFailed(fileName: String, queued: Boolean) {
        val title = "Google Drive Relay failed"
        val body = if (queued) {
            "${fileName.ifBlank { "File" }} queued until Drive is available"
        } else {
            "${fileName.ifBlank { "File" }} could not be posted to Drive"
        }
        post(title, body)
    }

    actual fun notifyRetrieved(fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        val title = if (fileNames.size == 1) "Received via Google Drive Relay" else "${fileNames.size} files received via Drive Relay"
        post(title, fileNames.joinToString(", "), DRIVE_RETRIEVE_TAG, NOTIFICATION_ID_RETRIEVED)
    }

    actual fun retractRetrieved(fileName: String) {
        if (!::driveNotifierContext.isInitialized) return
        val manager = NotificationManagerCompat.from(driveNotifierContext)
        runCatching { manager.cancel(DRIVE_RETRIEVE_TAG, NOTIFICATION_ID_RETRIEVED) }
        runCatching { manager.cancel(NOTIFICATION_ID_RETRIEVED) }
        runCatching {
            manager.cancel(NOTIFICATION_ID_BASE + ("Received via Google Drive Relay".hashCode() and 0xFFFF))
        }
        if (fileName.isNotBlank()) {
            runCatching { manager.cancel(NOTIFICATION_ID_BASE + (fileName.hashCode() and 0xFFFF)) }
        }
    }

    private fun post(
        title: String,
        body: String,
        tag: String? = null,
        id: Int = NOTIFICATION_ID_BASE + (title.hashCode() and 0xFFFF)
    ) {
        if (!FileApexServices.settings.driveRelayNotificationsEnabled.value) return
        if (!::driveNotifierContext.isInitialized) return
        val manager = NotificationManagerCompat.from(driveNotifierContext)
        if (!manager.areNotificationsEnabled()) return
        AndroidNotificationChannels.ensureDriveRelayChannel(driveNotifierContext)
        val notification = NotificationCompat.Builder(
            driveNotifierContext,
            AndroidNotificationChannels.DRIVE_RELAY
        )
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setLargeIcon(
                BitmapFactory.decodeResource(
                    driveNotifierContext.resources,
                    AndroidNotificationChannels.largeIcon
                )
            )
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            if (tag.isNullOrBlank()) {
                manager.notify(id, notification)
            } else {
                manager.notify(tag, id, notification)
            }
        }
    }
}

private const val NOTIFICATION_ID_BASE = 6200
private const val DRIVE_RETRIEVE_TAG = "fileapex.drive.retrieved"
private const val NOTIFICATION_ID_RETRIEVED = 6201
