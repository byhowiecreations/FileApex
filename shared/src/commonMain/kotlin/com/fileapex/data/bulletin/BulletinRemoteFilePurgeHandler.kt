package com.fileapex.data.bulletin

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.di.FileApexServices
import com.fileapex.platform.defaultDownloadsDir
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
        if (!BulletinRemoteFilePurgePolicy.shouldScrubLocalCopy(
                isAndroid = currentPlatformLabel() == "Android",
                selfDeviceId = loadLocalIdentity().deviceId,
                originNode = meta.originNode,
                messageOriginDeviceId = message.originDeviceId
            )
        ) {
            println("BulletinRemoteFilePurge: skip $messageId - sender or non-phone")
            return
        }
        val downloadsDir = defaultDownloadsDir()
        val localPath = BulletinRemoteFilePurgeResolver.resolve(meta, downloadsDir)
        if (localPath.isNullOrBlank()) {
            println(
                "BulletinRemoteFilePurge: skip $messageId name=${meta.fileName} - no FileApex downloads copy"
            )
            return
        }

        when (FileApexServices.settings.bulletinRemoteFilePurgePreference.value) {
            BulletinRemoteFilePurgePreference.ENABLED -> {
                if (scrubLocalFile(localPath, downloadsDir)) {
                    println("BulletinRemoteFilePurge: deleted $localPath for $messageId")
                } else {
                    println("BulletinRemoteFilePurge: delete failed $localPath for $messageId")
                }
            }
            BulletinRemoteFilePurgePreference.DISABLED -> {
                println("BulletinRemoteFilePurge: skip $messageId - remote purge disabled")
            }
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
            scrubLocalFile(localPath, defaultDownloadsDir())
        }
    }

    fun scrubLocalFile(localPath: String, downloadsDir: String = defaultDownloadsDir()): Boolean {
        if (!BulletinRemoteFilePurgeResolver.isSafeDeletePath(localPath, downloadsDir)) {
            println("BulletinRemoteFilePurge: refused path outside downloads $localPath")
            return false
        }
        return runCatching {
            val path = Path(localPath)
            if (SystemFileSystem.exists(path)) {
                SystemFileSystem.delete(path)
            }
            true
        }.getOrElse { error ->
            println("BulletinRemoteFilePurge: delete error ${error.message}")
            false
        }
    }
}
