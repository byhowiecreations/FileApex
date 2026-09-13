package com.fileapex.update

import com.fileapex.data.note.NoteRecord

expect object BulletinApkUpdateCoordinator {
    fun handleIncomingApkUpdate(note: NoteRecord)
    fun triggerDirectApkInstall(
        localPath: String,
        version: String,
        fileName: String,
        transactionId: String = "",
        transactionTimestampEpochMs: Long = 0L,
        senderDeviceId: String = ""
    )
    fun isUpdateInFlight(noteId: String): Boolean
}
