package com.fileapex.platform

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fileapex.MainActivity
import com.fileapex.R
import android.app.PendingIntent

/**
 * SSOT for the static share-server foreground notification.
 *
 * Posted **once** per FGS lifetime — never re-issued on AlarmManager re-asserts, heartbeat
 * housekeeping, or routine [com.fileapex.network.FileShareServerService] restarts while promoted.
 *
 * File transfer alerts use [AndroidNotificationChannels.TRANSFER_RECEIVE] / transfer receive
 * notifier with separate notification ids — they must not touch this pipeline.
 */
object ShareServerForegroundNotification {
    private const val TAG = "ShareServerFgNotify"
    const val NOTIFICATION_ID = 1
    private const val CONTENT_REQUEST_CODE = 1_101

    @Volatile
    private var posted = false

    /** @return true when [Service.startForeground] ran for the first time this service instance. */
    fun postOnce(service: Service): Boolean {
        if (posted) {
            Log.d(TAG, "Static server notification already posted — skipping re-post")
            return false
        }
        AndroidNotificationChannels.ensureShareServerChannel(service)
        val notification = buildStaticNotification(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val preferred = preferredForegroundServiceType()
            try {
                service.startForeground(NOTIFICATION_ID, notification, preferred)
            } catch (error: SecurityException) {
                if (preferred == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
                    Log.w(
                        TAG,
                        "connectedDevice FGS denied — falling back to dataSync :: ${error.message}"
                    )
                    service.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    throw error
                }
            }
        } else {
            @Suppress("DEPRECATION")
            service.startForeground(NOTIFICATION_ID, notification)
        }
        posted = true
        Log.i(TAG, "Static server notification posted (one-time)")
        return true
    }

    fun resetPostedState() {
        posted = false
    }

    fun isPosted(): Boolean = posted

    private fun buildStaticNotification(context: Context): Notification {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(context, CONTENT_REQUEST_CODE, launch, pendingFlags)
        return NotificationCompat.Builder(context, AndroidNotificationChannels.SHARE_SERVER_ACTIVE)
            .setContentTitle("FileApex Server Active")
            .setContentText("Local WiFi secure ecosystem running...")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun preferredForegroundServiceType(): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
    }
}
