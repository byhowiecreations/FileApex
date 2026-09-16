package com.fileapex.platform

import com.fileapex.di.FileApexServices

actual fun notifyNoteReceived(
    sourceDeviceName: String,
    content: String,
    noteId: String,
    critical: Boolean,
) {
    if (content.isBlank() || noteId.isBlank()) return
    if (!FileApexServices.settings.notesNotificationsEnabled.value) return
    println("NoteReceiveNotifier (Desktop): Note received from $sourceDeviceName - ${content.take(40)}")
}

actual fun retractNoteNotification(noteId: String) {
    retractNoteNotifications(listOf(noteId))
}

actual fun retractNoteNotifications(noteIds: List<String>, previewTexts: List<String>) {
}

actual fun notifyDirectAlert(
    sourceDeviceName: String,
    content: String,
) {
    if (content.isBlank()) return
    println("AlertNotifier (Desktop): Alert from $sourceDeviceName - ${content.take(40)}")
}
