package com.fileapex.platform

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.domain.notifications.NotificationAppInfo
import com.fileapex.domain.notifications.NotificationPayload
import com.fileapex.domain.notifications.NotificationBroadcastPolicy
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.i18n.AppI18n

private const val SETTINGS_PROMPT_TAG = "fileapex.settings.prompt"
private const val SETTINGS_PROMPT_NOTIFICATION_ID = 5302
private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"
private const val LISTENER_SERVICE_CLASS = "com.fileapex.platform.FileApexNotificationListenerService"

actual fun isNotificationAccessGranted(): Boolean {
    val context = androidAppContextOrNull() ?: return false
    return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

private fun notificationAccessIntent(context: Context): Intent {
    val detail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val intent = if (detail) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context.packageName, LISTENER_SERVICE_CLASS).flattenToString()
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

actual fun openNotificationAccessSettings() {
    val context = androidAppContextOrNull() ?: return
    try {
        context.startActivity(notificationAccessIntent(context))
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

actual fun showPhoneSettingsPrompt(page: String, requesterName: String) {
    val context = androidAppContextOrNull() ?: return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val (title, text) = when (page) {
        PhoneSettingsPage.BROADCAST_NOTIFICATIONS -> AppI18n.t("notif_access_prompt_title") to
            AppI18n.t("notif_access_prompt_text", requesterName)
        PhoneSettingsPage.CLIPBOARD -> AppI18n.t("clipboard_settings_prompt_title") to
            AppI18n.t("clipboard_settings_prompt_text", requesterName)
        else -> return
    }
    val launch = Intent().setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_OPEN_SETTINGS_PAGE, page)
    val pendingIntent = PendingIntent.getActivity(
        context,
        SETTINGS_PROMPT_NOTIFICATION_ID,
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, AndroidNotificationChannels.NOTE_MESSAGES)
        .setSmallIcon(AndroidNotificationChannels.smallIcon)
        .setLargeIcon(BitmapFactory.decodeResource(context.resources, AndroidNotificationChannels.largeIcon))
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    try {
        manager.notify(SETTINGS_PROMPT_TAG, SETTINGS_PROMPT_NOTIFICATION_ID, notification)
    } catch (error: SecurityException) {
        println("NotificationAccess: prompt not posted - ${error::class.simpleName}")
    }
}

actual fun listNotificationApps(): List<NotificationAppInfo> {
    val context = androidAppContextOrNull() ?: return emptyList()
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                val isSystem = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                NotificationAppInfo(
                    packageName = pkg,
                    label = label,
                    kind = NotificationBroadcastPolicy.classify(null, pkg),
                    group = NotificationBroadcastPolicy.groupFor(pkg, label, info.category, isSystem)
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        .sortedBy { it.label.lowercase() }
}

actual fun dismissPhoneNotifications(keys: List<String>, allowedPackages: Set<String>) {
    FileApexNotificationListenerService.instance?.dismiss(keys, allowedPackages)
}

actual fun snapshotPhoneNotifications(allowedPackages: Set<String>): List<NotificationPayload> =
    FileApexNotificationListenerService.instance?.snapshot(allowedPackages).orEmpty()

actual fun replyToPhoneNotification(key: String, text: String, allowedPackages: Set<String>): Boolean =
    FileApexNotificationListenerService.instance?.reply(key, text, allowedPackages) == true

actual fun supportsDevicePopups(): Boolean = false

actual fun showDevicePopup(title: String, text: String) = Unit
