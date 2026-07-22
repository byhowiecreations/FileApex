package com.omninode.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Single source of truth for OmniNode Android notification channel ids and creation.
 */
object AndroidNotificationChannels {
    const val APP_UPDATES = "omninode_app_updates"
    const val TRANSFER_RECEIVE = "omninode_transfer_receive"

    fun ensureAppUpdatesChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            APP_UPDATES,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a newer OmniNode build is available"
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
            description = "Alerts when OmniNode receives files from paired devices"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
