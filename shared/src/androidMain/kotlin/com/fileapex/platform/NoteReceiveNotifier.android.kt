package com.fileapex.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices

private lateinit var noteNotifierContext: Context

fun initAndroidNoteReceiveNotifier(context: Context) {
    noteNotifierContext = context.applicationContext
    AndroidNotificationChannels.ensureNoteMessagesChannel(noteNotifierContext)
}

actual fun notifyNoteReceived(sourceDeviceName: String, content: String, noteId: String) {
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

    val extras = Bundle().apply {
        putString(EXTRA_NOTE_ID, noteId)
        putString(EXTRA_NOTE_PREVIEW, content)
    }
    val title = "Bulletin Board · ${sourceDeviceName.ifBlank { "Paired Device" }}"
    val notification = NotificationCompat.Builder(noteNotifierContext, AndroidNotificationChannels.NOTE_MESSAGES)
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
        .setAutoCancel(true)
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
    if (notification.channelId == AndroidNotificationChannels.NOTE_MESSAGES) return true
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

private const val NOTE_NOTIFICATION_TAG = "fileapex.note"
private const val NOTE_NOTIFICATION_ID_BASE = 5200
private const val EXTRA_NOTE_ID = "fileapex.noteId"
private const val EXTRA_NOTE_PREVIEW = "fileapex.notePreview"
