package com.fileapex.platform

expect fun notifyNoteReceived(
    sourceDeviceName: String,
    content: String,
    noteId: String,
    critical: Boolean = false,
)

expect fun retractNoteNotification(noteId: String)

expect fun retractNoteNotifications(noteIds: List<String>, previewTexts: List<String> = emptyList())

expect fun notifyDirectAlert(
    sourceDeviceName: String,
    content: String,
)
