package com.fileapex.platform

expect fun notifyNoteReceived(sourceDeviceName: String, content: String, noteId: String)

expect fun retractNoteNotification(noteId: String)

expect fun retractNoteNotifications(noteIds: List<String>, previewTexts: List<String> = emptyList())
