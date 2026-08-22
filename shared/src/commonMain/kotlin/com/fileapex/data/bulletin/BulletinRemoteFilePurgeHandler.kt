package com.fileapex.data.bulletin

import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

data class BulletinRemotePurgePrompt(
    val messageId: String,
    val fileName: String,
    val localPath: String
)

object BulletinRemoteFilePurgeCoordinator {
    private val _pendingPrompts = MutableSharedFlow<BulletinRemotePurgePrompt>(extraBufferCapacity = 8)
    val pendingPrompts: SharedFlow<BulletinRemotePurgePrompt> = _pendingPrompts.asSharedFlow()

    fun requestPrompt(prompt: BulletinRemotePurgePrompt) {
        _pendingPrompts.tryEmit(prompt)
    }
}

object BulletinRemoteFilePurgeHandler {
    suspend fun handle(messageId: String) {
        val repository = FileApexServices.bulletinBoardRepository
        val message = repository.getMessage(messageId) ?: return
        val meta = repository.decodeFileMetadata(message) ?: return
        val localPath = meta.localPath?.takeIf { it.isNotBlank() } ?: return
        val path = Path(localPath)
        if (!SystemFileSystem.exists(path)) return

        when (FileApexServices.settings.bulletinRemoteFilePurgePreference.value) {
            BulletinRemoteFilePurgePreference.ENABLED -> scrubLocalFile(localPath)
            BulletinRemoteFilePurgePreference.DISABLED -> Unit
            BulletinRemoteFilePurgePreference.UNCONFIGURED -> {
                BulletinRemoteFilePurgeCoordinator.requestPrompt(
                    BulletinRemotePurgePrompt(
                        messageId = messageId,
                        fileName = meta.fileName,
                        localPath = localPath
                    )
                )
            }
        }
    }

    fun resolveFirstTimePrompt(deleteFiles: Boolean, localPath: String) {
        val preference = if (deleteFiles) {
            BulletinRemoteFilePurgePreference.ENABLED
        } else {
            BulletinRemoteFilePurgePreference.DISABLED
        }
        FileApexServices.settings.setBulletinRemoteFilePurgePreference(preference)
        if (deleteFiles) {
            scrubLocalFile(localPath)
        }
    }

    fun scrubLocalFile(localPath: String) {
        runCatching {
            val path = Path(localPath)
            if (SystemFileSystem.exists(path)) {
                SystemFileSystem.delete(path)
            }
        }
    }
}
