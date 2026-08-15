package com.fileapex.platform

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.di.FileApexServices

private lateinit var noteNotifierContext: Context

fun initAndroidNoteReceiveNotifier(context: Context) {
    noteNotifierContext = context.applicationContext
    AndroidNotificationChannels.ensureNoteMessagesChannel(noteNotifierContext)
}

actual fun notifyNoteReceived(sourceDeviceName: String, content: String) {
    if (content.isBlank()) return
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

    val title = "Note from ${sourceDeviceName.ifBlank { "Paired Device" }}"
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
        .build()

    runCatching {
        manager.notify(NOTE_NOTIFICATION_ID_BASE + (content.hashCode() and 0xFFFF), notification)
    }.onFailure { error ->
        println("NoteReceiveNotifier: notify failed :: ${error.message}")
    }
}

private const val NOTE_NOTIFICATION_ID_BASE = 5200
