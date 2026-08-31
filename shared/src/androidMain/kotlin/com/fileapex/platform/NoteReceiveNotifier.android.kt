package com.fileapex.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.data.note.NoteNotifyPolicy
import com.fileapex.di.FileApexServices

private lateinit var noteNotifierContext: Context

fun initAndroidNoteReceiveNotifier(context: Context) {
    noteNotifierContext = context.applicationContext
    AndroidNotificationChannels.ensureNoteMessagesChannel(noteNotifierContext)
    AndroidNotificationChannels.ensureBulletinCriticalChannel(noteNotifierContext)
}

actual fun notifyNoteReceived(
    sourceDeviceName: String,
    content: String,
    noteId: String,
    critical: Boolean,
) {
    if (content.isBlank() || noteId.isBlank()) return
    if (!FileApexServices.settings.notesNotificationsEnabled.value) {
        println("NoteReceiveNotifier: skipped - note notifications disabled in Settings")
        return
    }
    if (!::noteNotifierContext.isInitialized) {
        println("NoteReceiveNotifier: skipped - notifier not initialized")
        return
    }

    val manager = NotificationManagerCompat.from(noteNotifierContext)
    if (!manager.areNotificationsEnabled()) {
        println("NoteReceiveNotifier: skipped - system notifications disabled for FileApex")
        return
    }

    val useCritical = critical || NoteNotifyPolicy.isCriticalBulletin(content)
    val channelId = if (useCritical) {
        AndroidNotificationChannels.BULLETIN_CRITICAL
    } else {
        AndroidNotificationChannels.NOTE_MESSAGES
    }

    val extras = Bundle().apply {
        putString(EXTRA_NOTE_ID, noteId)
        putString(EXTRA_NOTE_PREVIEW, content)
    }
    val title = NoteNotifyPolicy.notificationTitle(sourceDeviceName)
    val openIntent = noteOpenPendingIntent(noteId)
    val notification = NotificationCompat.Builder(noteNotifierContext, channelId)
        .setSmallIcon(AndroidNotificationChannels.noteSmallIcon)
        .setLargeIcon(
            BitmapFactory.decodeResource(
                noteNotifierContext.resources,
                AndroidNotificationChannels.noteLargeIcon
            )
        )
        .setContentTitle(title)
        .setContentText(content)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        .setContentIntent(openIntent)
        .addAction(
            0,
            com.fileapex.i18n.AppI18n.t("open"),
            openIntent
        )
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .addExtras(extras)
        .build()

    runCatching {
        manager.notify(NOTE_NOTIFICATION_TAG, notificationIdForNote(noteId), notification)
    }.onFailure { error ->
        println("NoteReceiveNotifier: notify failed :: ${error.message}")
    }
}

actual fun retractNoteNotification(noteId: String) {
    retractNoteNotifications(listOf(noteId))
}

actual fun retractNoteNotifications(noteIds: List<String>, previewTexts: List<String>) {
    if (!::noteNotifierContext.isInitialized) return
    val ids = noteIds.filter { it.isNotBlank() }.toSet()
    val previews = previewTexts.filter { it.isNotBlank() }.toSet()
    if (ids.isEmpty() && previews.isEmpty()) return
    val manager = NotificationManagerCompat.from(noteNotifierContext)
    val computed = linkedSetOf<Int>()
    for (noteId in ids) {
        computed += candidateNotificationIds(noteId)
    }
    for (preview in previews) {
        computed += candidateNotificationIds(preview)
    }
    for (id in computed) {
        runCatching { manager.cancel(NOTE_NOTIFICATION_TAG, id) }
        runCatching { manager.cancel(id) }
    }
    val system = noteNotifierContext.getSystemService(NotificationManager::class.java) ?: return
    for (posted in system.activeNotifications.orEmpty()) {
        if (!isNoteNotification(posted.tag, posted.id, posted.notification) &&
            posted.id !in computed
        ) {
            continue
        }
        if (matchesRetract(posted.notification, ids, previews) || posted.id in computed) {
            if (posted.tag.isNullOrBlank()) {
                runCatching { system.cancel(posted.id) }
            } else {
                runCatching { system.cancel(posted.tag, posted.id) }
            }
        }
    }
}

private fun isNoteNotification(tag: String?, id: Int, notification: Notification): Boolean {
    if (tag == NOTE_NOTIFICATION_TAG) return true
    val channel = notification.channelId
    if (channel == AndroidNotificationChannels.NOTE_MESSAGES ||
        channel == AndroidNotificationChannels.BULLETIN_CRITICAL
    ) {
        return true
    }
    return id >= NOTE_NOTIFICATION_ID_BASE && id < NOTE_NOTIFICATION_ID_BASE + 0x10000
}

private fun matchesRetract(
    notification: Notification,
    noteIds: Set<String>,
    previews: Set<String>
): Boolean {
    val extras = notification.extras
    val extraId = extras.getString(EXTRA_NOTE_ID).orEmpty()
    if (extraId.isNotBlank() && extraId in noteIds) return true
    val extraPreview = extras.getString(EXTRA_NOTE_PREVIEW).orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
    return previews.any { preview ->
        preview == extraPreview || preview == text || preview == big
    }
}

private fun candidateNotificationIds(key: String): List<Int> {
    var hash = key.hashCode()
    if (hash == Int.MIN_VALUE) hash = 0
    return listOf(
        NOTE_NOTIFICATION_ID_BASE + (hash and 0x7FFF),
        NOTE_NOTIFICATION_ID_BASE + (hash and 0xFFFF)
    )
}

private fun notificationIdForNote(noteId: String): Int {
    var hash = noteId.hashCode()
    if (hash == Int.MIN_VALUE) hash = 0
    return NOTE_NOTIFICATION_ID_BASE + (hash and 0x7FFF)
}

private fun noteOpenPendingIntent(noteId: String): PendingIntent {
    val launch = noteNotifierContext.packageManager
        .getLaunchIntentForPackage(noteNotifierContext.packageName)
        ?.apply { applyNoteOpenExtras(noteId) }
        ?: Intent().setClassName(noteNotifierContext.packageName, MAIN_ACTIVITY_CLASS)
            .apply { applyNoteOpenExtras(noteId) }
    return PendingIntent.getActivity(
        noteNotifierContext,
        notificationIdForNote(noteId),
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    )
}

private fun Intent.applyNoteOpenExtras(noteId: String) {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    putExtra(EXTRA_OPEN_NOTE_ID, noteId)
}

const val EXTRA_OPEN_NOTE_ID = "com.fileapex.extra.OPEN_NOTE_ID"

private const val NOTE_NOTIFICATION_TAG = "fileapex.note"
private const val NOTE_NOTIFICATION_ID_BASE = 5200
private const val EXTRA_NOTE_ID = "fileapex.noteId"
private const val EXTRA_NOTE_PREVIEW = "fileapex.notePreview"
private const val MAIN_ACTIVITY_CLASS = "com.fileapex.MainActivity"
