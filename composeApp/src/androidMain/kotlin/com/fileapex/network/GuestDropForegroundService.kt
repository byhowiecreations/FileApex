package com.fileapex.network

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fileapex.MainActivity
import com.fileapex.drop.GuestDropHub
import com.fileapex.drop.GuestDropNotice
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.AndroidNotificationChannels
import com.fileapex.platform.log.Log

/**
 * Keeps FileApex running, with a visible notice, while a file link is open, so the owner can use the phone for other things.
 * It runs only for that: it does not start the LAN share server. It stops when the last link ends or the notice's Stop is tapped.
 */
class GuestDropForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GuestDropHub.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val notice = noticeFrom(intent)
        if (notice == GuestDropNotice.Off) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            AndroidNotificationChannels.ensureGuestLinkChannel(this)
            val notification = build(this, notice)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "link foreground start denied :: ${error.message}")
            stopSelf()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "link foreground start not allowed :: ${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "link foreground quota ended (type=$fgsType) - closing links")
        GuestDropHub.stopAll()
        stopSelf(startId)
    }

    /** Swiping the app away ends the links on purpose; a process Android may kill at any time is no place to keep serving from. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        GuestDropHub.stopAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GuestDropFgs"
        private const val NOTIFICATION_ID = 7
        private const val CONTENT_REQUEST = 1_701
        private const val STOP_REQUEST = 1_702
        private const val ACTION_STOP = "com.fileapex.action.STOP_GUEST_LINKS"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_NAME = "name"

        /** Called from the app on every change of the notice: starts, updates or stops the service. */
        fun apply(context: Context, notice: GuestDropNotice) {
            val app = context.applicationContext
            if (notice == GuestDropNotice.Off) {
                app.stopService(Intent(app, GuestDropForegroundService::class.java))
                return
            }
            val intent = Intent(app, GuestDropForegroundService::class.java).apply {
                when (notice) {
                    is GuestDropNotice.One -> { putExtra(EXTRA_COUNT, 1); putExtra(EXTRA_NAME, notice.name) }
                    is GuestDropNotice.Many -> putExtra(EXTRA_COUNT, notice.count)
                    GuestDropNotice.Off -> Unit
                }
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "link foreground start blocked :: ${error.message}")
            } catch (error: SecurityException) {
                Log.w(TAG, "link foreground start denied :: ${error.message}")
            }
        }

        private fun noticeFrom(intent: Intent?): GuestDropNotice {
            val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
            return when {
                count <= 0 -> GuestDropNotice.Off
                count == 1 -> GuestDropNotice.One(intent?.getStringExtra(EXTRA_NAME).orEmpty())
                else -> GuestDropNotice.Many(count)
            }
        }

        private fun build(context: Context, notice: GuestDropNotice): Notification {
            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(
                context, CONTENT_REQUEST,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                immutable
            )
            val stop = PendingIntent.getService(
                context, STOP_REQUEST,
                Intent(context, GuestDropForegroundService::class.java).setAction(ACTION_STOP),
                immutable
            )
            val text = when (notice) {
                is GuestDropNotice.One -> AppI18n.t("share_link_notice_one", notice.name)
                is GuestDropNotice.Many -> AppI18n.t("share_link_notice_many", notice.count)
                GuestDropNotice.Off -> ""
            }
            return NotificationCompat.Builder(context, AndroidNotificationChannels.GUEST_LINK)
                .setContentTitle(AppI18n.t("share_link_notice_title"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(AndroidNotificationChannels.smallIcon)
                .setContentIntent(open)
                .addAction(0, AppI18n.t("share_link_stop"), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
