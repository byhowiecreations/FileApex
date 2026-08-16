package com.fileapex.platform

import com.fileapex.di.FileApexServices

actual fun notifyNoteReceived(sourceDeviceName: String, content: String, noteId: String) {
    if (content.isBlank() || noteId.isBlank()) return
    if (!FileApexServices.settings.notesNotificationsEnabled.value) return
    println("NoteReceiveNotifier (Desktop): Note received from $sourceDeviceName - ${content.take(40)}")
}

actual fun retractNoteNotification(noteId: String) {
    retractNoteNotifications(listOf(noteId))
}

actual fun retractNoteNotifications(noteIds: List<String>, previewTexts: List<String>) {
}
