package com.omninode.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Defers share-server FGS start until [MainActivity] is in the foreground when background
 * promotion is blocked (Android 15+ dataSync / background FGS limits).
 * Uses device-protected storage so [LOCKED_BOOT_COMPLETED] can set the flag safely.
 *
 * [mark] posts a lightweight tap-to-restore notification; [consume]/[clear] dismiss it.
 */
object ShareServerPendingStart {
    private const val TAG = "ShareServerPendingStart"
    private const val PREFS = "omninode_share_server"
    private const val KEY_PENDING = "pending_foreground_start"
    private const val CHANNEL_ID = "omninode_share_server_recovery"
    private const val NOTIFICATION_ID = 4401
    private const val PENDING_INTENT_REQUEST = 44_011

    fun mark(context: Context) {
        val prefs = directBootPrefs(context)
        val alreadyPending = prefs.getBoolean(KEY_PENDING, false)
        prefs.edit()
            .putBoolean(KEY_PENDING, true)
            .commit()
        // Avoid re-alerting if the nudge is already showing.
        if (!alreadyPending) {
            postRecoveryNotification(context.applicationContext)
        }
    }

    fun clear(context: Context) {
        directBootPrefs(context).edit()
            .remove(KEY_PENDING)
            .commit()
        cancelRecoveryNotification(context.applicationContext)
    }

    /**
     * Returns true when a background start was previously suppressed, clears the flag,
     * and dismisses the recovery notification.
     */
    fun consume(context: Context): Boolean {
        val prefs = directBootPrefs(context)
        val pending = prefs.getBoolean(KEY_PENDING, false)
        if (pending) {
            prefs.edit().remove(KEY_PENDING).commit()
            cancelRecoveryNotification(context.applicationContext)
            Log.i(TAG, "Consumed pending share-server restart")
        }
        return pending
    }

    fun isPending(context: Context): Boolean =
        directBootPrefs(context).getBoolean(KEY_PENDING, false)

    private fun postRecoveryNotification(context: Context) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "Recovery notification skipped — notifications disabled")
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.omninode.MainActivity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST,
            launch,
            flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OmniNode server paused")
            .setContentText("Tap to restore local Wi‑Fi file sharing.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Recovery notification failed :: ${error.message}")
        }
    }

    private fun cancelRecoveryNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Server recovery",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Prompts to restore the OmniNode share server after background limits"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun directBootPrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
