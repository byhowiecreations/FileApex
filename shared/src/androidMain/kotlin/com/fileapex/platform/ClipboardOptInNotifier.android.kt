package com.fileapex.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n

private lateinit var clipboardOptInContext: Context
private const val CLIPBOARD_OPT_IN_TAG = "fileapex.clipboard.optin"
private const val CLIPBOARD_OPT_IN_NOTIFICATION_ID = 5301
private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"

fun initAndroidClipboardOptInNotifier(context: Context) {
    clipboardOptInContext = context.applicationContext
    AndroidNotificationChannels.ensureNoteMessagesChannel(clipboardOptInContext)
}

actual fun notifyClipboardOptInRequested(senderDeviceName: String) {
    if (FileApexServices.settings.clipboardOptInPromptShown.value) return
    if (FileApexServices.settings.clipboardSharingEnabled.value) return
    if (!::clipboardOptInContext.isInitialized) return

    val manager = NotificationManagerCompat.from(clipboardOptInContext)
    if (!manager.areNotificationsEnabled()) return

    val title = AppI18n.t("clipboard_opt_in_notification_title")
    val text = AppI18n.t("clipboard_opt_in_notification_text", senderDeviceName)

    val launchIntent = clipboardOptInContext.packageManager
        .getLaunchIntentForPackage(clipboardOptInContext.packageName)
        ?.apply { applyOptInExtras(senderDeviceName) }
        ?: Intent().setClassName(clipboardOptInContext.packageName, MAIN_ACTIVITY_CLASS)
            .apply { applyOptInExtras(senderDeviceName) }

    val pendingIntent = PendingIntent.getActivity(
        clipboardOptInContext,
        CLIPBOARD_OPT_IN_NOTIFICATION_ID,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    )

    val notification = NotificationCompat.Builder(clipboardOptInContext, AndroidNotificationChannels.NOTE_MESSAGES)
        .setSmallIcon(AndroidNotificationChannels.smallIcon)
        .setLargeIcon(
            BitmapFactory.decodeResource(
                clipboardOptInContext.resources,
                AndroidNotificationChannels.largeIcon
            )
        )
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    runCatching {
        manager.notify(CLIPBOARD_OPT_IN_TAG, CLIPBOARD_OPT_IN_NOTIFICATION_ID, notification)
    }.onFailure { error ->
        println("ClipboardOptInNotifier: notify failed :: ${error.message}")
    }
}

private fun Intent.applyOptInExtras(senderDeviceName: String) {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    putExtra(EXTRA_SHOW_CLIPBOARD_OPT_IN, true)
    putExtra(EXTRA_CLIPBOARD_OPT_IN_SENDER, senderDeviceName)
}
