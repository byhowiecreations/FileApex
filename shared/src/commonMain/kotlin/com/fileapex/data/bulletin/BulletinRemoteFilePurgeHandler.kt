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

import com.fileapex.update.BulletinApkUpdatePolicy
import com.fileapex.update.currentAppVersionName
import com.fileapex.update.isInstalledVersionStrictlyNewer
import com.fileapex.update.isRemoteVersionNewer
import com.fileapex.platform.UniqueFileNames

data class BulletinRemotePurgePrompt(
    val messageId: String,
    val fileName: String,
    val localPath: String
)

object BulletinRemoteFilePurgeCoordinator {
    private val _pendingPrompts = MutableSharedFlow<BulletinRemotePurgePrompt>(replay = 4, extraBufferCapacity = 8)
    val pendingPrompts: SharedFlow<BulletinRemotePurgePrompt> = _pendingPrompts.asSharedFlow()

    fun requestPrompt(prompt: BulletinRemotePurgePrompt) {
        _pendingPrompts.tryEmit(prompt)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearPrompts() {
        _pendingPrompts.resetReplayCache()
    }
}

object BulletinRemoteFilePurgeHandler {
    suspend fun handle(messageId: String) {
        val repository = FileApexServices.bulletinBoardRepository
        val message = repository.getMessage(messageId) ?: return
        if (message.isPinned) {
            println("BulletinRemoteFilePurge: skip $messageId - locked note")
            return
        }
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

        val isAutoUpdateApk = BulletinApkUpdatePolicy.matchesAutoUpdateApk(meta.fileName)
        if (isAutoUpdateApk) {
            if (!localPath.isNullOrBlank()) {
                if (scrubLocalFile(localPath, downloadsDir)) {
                    println("BulletinRemoteFilePurge: auto-purged staged APK $localPath for $messageId")
                } else {
                    println("BulletinRemoteFilePurge: delete failed $localPath for $messageId")
                }
            }
            pruneMatchingAutoUpdateApks(meta.fileName, downloadsDir)
            pruneStaleAutoUpdateApks(downloadsDir)
            val pending = com.fileapex.update.PendingUpdateStore.load()
            if (pending != null && (pending.originNoteId == messageId || pending.assetName == meta.fileName)) {
                com.fileapex.update.PendingUpdateStore.save(null)
                com.fileapex.platform.dismissAppUpdateNotification()
            }
            return
        }

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
        BulletinRemoteFilePurgeCoordinator.clearPrompts()
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

    private fun pruneMatchingAutoUpdateApks(fileName: String, downloadsDir: String) {
        val root = Path(downloadsDir)
        if (!SystemFileSystem.exists(root)) return
        runCatching {
            for (child in SystemFileSystem.list(root)) {
                val childName = child.name
                if (UniqueFileNames.matchesOriginalOrCollision(fileName, childName) ||
                    childName.equals(fileName, ignoreCase = true)
                ) {
                    val candidate = child.toString()
                    if (BulletinRemoteFilePurgeResolver.isSafeDeletePath(candidate, downloadsDir)) {
                        runCatching { SystemFileSystem.delete(child) }
                    }
                }
            }
        }
    }

    fun pruneStaleAutoUpdateApks(downloadsDir: String = defaultDownloadsDir()) {
        val root = Path(downloadsDir)
        if (!SystemFileSystem.exists(root)) return
        val currentVersion = currentAppVersionName()
        runCatching {
            for (child in SystemFileSystem.list(root)) {
                val childName = child.name
                if (BulletinApkUpdatePolicy.matchesAutoUpdateApk(childName)) {
                    val apkVersion = BulletinApkUpdatePolicy.extractVersionFromApkName(childName)
                    val candidate = child.toString()
                    val shouldPrune = when {
                        apkVersion == null -> true
                        isInstalledVersionStrictlyNewer(currentVersion, apkVersion) -> true
                        apkVersion == currentVersion -> {
                            val activeOffer = com.fileapex.update.PendingUpdateStore.load()
                            if (activeOffer?.assetName == childName || activeOffer?.localFilePath == candidate) {
                                false
                            } else {
                                val record = com.fileapex.network.TransferTransactionJournal.findRecordByFilePath(candidate)
                                record != null && record.installed
                            }
                        }
                        else -> false
                    }
                    if (shouldPrune) {
                        if (BulletinRemoteFilePurgeResolver.isSafeDeletePath(candidate, downloadsDir)) {
                            runCatching { SystemFileSystem.delete(child) }
                        }
                    }
                }
            }
        }
    }
}
