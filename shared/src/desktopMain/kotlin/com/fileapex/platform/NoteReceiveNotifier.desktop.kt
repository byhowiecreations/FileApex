package com.fileapex.platform

import com.fileapex.di.FileApexServices

actual fun notifyNoteReceived(sourceDeviceName: String, content: String) {
    if (content.isBlank()) return
    if (!FileApexServices.settings.notesNotificationsEnabled.value) return
    println("NoteReceiveNotifier (Desktop): Note received from $sourceDeviceName — ${content.take(40)}")
}
