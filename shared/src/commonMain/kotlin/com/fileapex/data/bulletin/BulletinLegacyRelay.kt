package com.fileapex.data.bulletin

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.note.NoteRecord
import com.fileapex.di.FileApexServices
import com.fileapex.network.ServerLifecycleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** LAN + FCM note paths for peers that predate bulletin batch sync (before v0.8.1a). */
object BulletinLegacyRelay {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun dispatchMessage(message: MessageEntity, peers: List<PairedDeviceEntity>) {
        if (peers.isEmpty()) return
        val record = message.toNoteRecord()
        FcmWakeCoordinator.dispatchNoteWakeToLinkedPeers(
            noteId = record.noteId,
            content = record.content,
            driveFileId = record.driveFileId,
            checksum = record.checksum,
            attachmentName = record.attachmentFileName,
            attachmentSizeBytes = record.attachmentSizeBytes
        )
        scope.launch {
            dispatchLanMessage(record, peers)
        }
    }

    fun dispatchTombstone(messageId: String, peers: List<PairedDeviceEntity>, snapshot: NoteRecord?) {
        if (peers.isEmpty()) return
        val driveFileId = snapshot?.driveFileId?.takeIf { it.isNotBlank() }
        val checksum = snapshot?.checksum?.takeIf { it.isNotBlank() }
        val attachmentName = snapshot?.attachmentFileName?.takeIf { it.isNotBlank() }
        FcmWakeCoordinator.dispatchNoteDeleteToLinkedPeers(
            noteId = messageId,
            driveFileId = driveFileId,
            checksum = checksum,
            attachmentName = attachmentName
        )
        scope.launch {
            for (device in peers) {
                val host = device.lastKnownIp
                val port = device.port
                if (host.isBlank() || port <= 0) continue
                runCatching {
                    FileApexServices.client.postNoteDelete(
                        host = host,
                        port = port,
                        noteId = messageId,
                        driveFileId = driveFileId,
                        checksum = checksum,
                        attachmentName = attachmentName
                    )
                }
            }
        }
    }

    private suspend fun dispatchLanMessage(record: NoteRecord, peers: List<PairedDeviceEntity>) {
        val localPath = record.attachmentLocalPath
        val fileName = record.attachmentFileName
        val lanAttach = !localPath.isNullOrBlank() &&
            !fileName.isNullOrBlank() &&
            record.attachmentSizeBytes <= DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES
        ServerLifecycleManager.ensureRunning()
        for (device in peers) {
            val host = device.lastKnownIp
            val port = device.port
            if (host.isBlank() || port <= 0) continue
            runCatching {
                FileApexServices.client.postNote(host, port, record)
                if (lanAttach) {
                    FileApexServices.client.uploadNoteAttachment(
                        host = host,
                        port = port,
                        noteId = record.noteId,
                        fileName = fileName!!,
                        localSourcePath = localPath!!
                    )
                }
            }
        }
    }
}
