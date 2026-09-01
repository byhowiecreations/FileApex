package com.fileapex.update

import com.fileapex.data.note.NoteRecord

expect object BulletinApkUpdateCoordinator {
    fun handleIncomingApkUpdate(note: NoteRecord)
    fun triggerDirectApkInstall(localPath: String, version: String, fileName: String)
}
