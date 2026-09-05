package com.fileapex.update

import com.fileapex.data.note.NoteRecord

actual object BulletinApkUpdateCoordinator {
    actual fun handleIncomingApkUpdate(note: NoteRecord) {
        // Desktop platforms (macOS/Windows) do not auto-install Android APKs.
    }

    actual fun triggerDirectApkInstall(localPath: String, version: String, fileName: String) {
        // Desktop platforms (macOS/Windows) do not auto-install Android APKs.
    }

    actual fun isUpdateInFlight(noteId: String): Boolean = false
}
