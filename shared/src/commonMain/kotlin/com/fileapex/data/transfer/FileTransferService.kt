package com.fileapex.data.transfer

import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.data.files.DirectoryListing
import com.fileapex.data.files.LocalFileRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.model.ClipboardPayload
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.MultiCopyBroadcastEngine
import com.fileapex.domain.transfer.MultiCopyDestination
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.MultiCopyResult
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.network.FileApexClient
import com.fileapex.util.PathUtils
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlinx.io.write

/**
 * Stream I/O for copy/paste/download/browse listing.
 * Outbound Multi Copy and explorer transfer actions enter through [com.fileapex.domain.transfer.TransferManager].
 */
class FileTransferService(
    private val localFiles: LocalFileRepository = LocalFileRepository(),
    private val client: FileApexClient
) {
    private val multiCopyEngine = MultiCopyBroadcastEngine(client)

    suspend fun listLocal(path: String): DirectoryListing = withContext(Dispatchers.IO) {
        localFiles.listDirectory(path).getOrThrow()
    }

    suspend fun listRemote(host: String, port: Int, path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            client.listFiles(host, port, path)
        }

    fun copyLocalFile(
        localIdentity: LocalIdentity,
        item: RemoteFileItem,
        hostForPeers: String
    ) {
        copyLocalFiles(localIdentity, listOf(item), hostForPeers)
    }

    fun copyLocalFiles(
        localIdentity: LocalIdentity,
        items: List<RemoteFileItem>,
        hostForPeers: String
    ) {
        require(items.isNotEmpty()) { "Select at least one file to copy" }
        require(items.none { it.isDirectory }) { "Directories are not supported for COPY yet" }
        TransferClipboard.copyAll(
            items.map { item ->
                ClipboardPayload(
                    sourceDeviceId = localIdentity.deviceId,
                    sourceDeviceName = localIdentity.deviceName,
                    sourceHost = hostForPeers,
                    sourcePort = localIdentity.sharePort,
                    remoteAbsolutePath = item.absolutePath,
                    fileName = item.name,
                    sizeBytes = item.sizeBytes,
                    mimeType = item.mimeType,
                    isLocalSource = true
                )
            }
        )
    }

    fun copyRemoteFile(
        sourceDeviceId: String,
        sourceDeviceName: String,
        host: String,
        port: Int,
        item: RemoteFileItem
    ) {
        copyRemoteFiles(sourceDeviceId, sourceDeviceName, host, port, listOf(item))
    }

    fun copyRemoteFiles(
        sourceDeviceId: String,
        sourceDeviceName: String,
        host: String,
        port: Int,
        items: List<RemoteFileItem>
    ) {
        require(items.isNotEmpty()) { "Select at least one file to copy" }
        require(items.none { it.isDirectory }) { "Directories are not supported for COPY yet" }
        TransferClipboard.copyAll(
            items.map { item ->
                ClipboardPayload(
                    sourceDeviceId = sourceDeviceId,
                    sourceDeviceName = sourceDeviceName,
                    sourceHost = host,
                    sourcePort = port,
                    remoteAbsolutePath = item.absolutePath,
                    fileName = item.name,
                    sizeBytes = item.sizeBytes,
                    mimeType = item.mimeType,
                    isLocalSource = false
                )
            }
        )
    }

    /**
     * Broadcast selected file(s) to destinations. Engine-only — call via
     * [com.fileapex.domain.transfer.TransferManager.sendToDevices].
     */
    internal suspend fun multiCopyToDevices(
        sources: List<MultiCopySource>,
        selectedDevices: List<MultiCopyDeviceOption>
    ): List<MultiCopyResult> = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Select at least one file" }
        require(selectedDevices.isNotEmpty()) { "Select at least one destination device" }
        sources.map { source ->
            val perFileDestinations = selectedDevices.map { option ->
                if (option.isLocal) {
                    SystemFileSystem.createDirectories(Path(option.destinationRoot))
                }
                val fileTarget = if (option.isLocal) {
                    UniqueFileNames.resolveInDirectory(option.destinationRoot, source.fileName)
                } else {
                    // Remote server also resolves collisions; preferred name is fine here.
                    PathUtils.join(option.destinationRoot, source.fileName)
                }
                if (option.isLocal) {
                    MultiCopyDestination.LocalDevice(
                        deviceId = option.deviceId,
                        deviceName = option.deviceName,
                        absolutePath = fileTarget
                    )
                } else {
                    MultiCopyDestination.RemoteDevice(
                        deviceId = option.deviceId,
                        deviceName = option.deviceName,
                        host = option.host,
                        port = option.port,
                        absolutePath = fileTarget
                    )
                }
            }
            multiCopyEngine.broadcast(listOf(source), perFileDestinations).first()
        }
    }

    suspend fun pasteIntoLocal(targetDirectory: String): List<String> = withContext(Dispatchers.IO) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { "Clipboard is empty" }
        payloads.map { payload ->
            val targetPath = UniqueFileNames.resolveInDirectory(targetDirectory, payload.fileName)
            when {
                payload.isLocalSource -> copyLocalToLocal(payload.remoteAbsolutePath, targetPath)
                else -> client.downloadToLocal(
                    host = payload.sourceHost,
                    port = payload.sourcePort,
                    remotePath = payload.remoteAbsolutePath,
                    localTargetPath = targetPath
                )
            }
            targetPath
        }
    }

    suspend fun pasteIntoRemote(
        host: String,
        port: Int,
        targetDirectory: String
    ): List<String> = withContext(Dispatchers.IO) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { "Clipboard is empty" }
        payloads.map { payload ->
            val remoteTarget = PathUtils.join(targetDirectory, payload.fileName)
            val tempLocal = PathUtils.join(defaultTempDir(), "fileapex-paste-${payload.fileName}")
            try {
                when {
                    payload.isLocalSource -> {
                        client.uploadFromLocal(host, port, payload.remoteAbsolutePath, remoteTarget)
                    }
                    else -> {
                        client.downloadToLocal(
                            host = payload.sourceHost,
                            port = payload.sourcePort,
                            remotePath = payload.remoteAbsolutePath,
                            localTargetPath = tempLocal
                        )
                        client.uploadFromLocal(host, port, tempLocal, remoteTarget)
                    }
                }
            } finally {
                runCatching {
                    val path = Path(tempLocal)
                    if (SystemFileSystem.exists(path)) {
                        SystemFileSystem.delete(path)
                    }
                }
            }
            remoteTarget
        }
    }

    /**
     * Streams remote file(s) onto this device under Downloads/FileApex.
     */
    suspend fun downloadRemoteToDownloads(
        host: String,
        port: Int,
        items: List<RemoteFileItem>
    ): List<String> = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "Select at least one file to download" }
        require(items.none { it.isDirectory }) { "Directories cannot be downloaded yet" }
        val downloadsRoot = defaultDownloadsDir()
        SystemFileSystem.createDirectories(Path(downloadsRoot))
        items.map { item ->
            val targetPath = UniqueFileNames.resolveInDirectory(downloadsRoot, item.name)
            client.downloadToLocal(
                host = host,
                port = port,
                remotePath = item.absolutePath,
                localTargetPath = targetPath
            )
            targetPath
        }
    }

    private fun copyLocalToLocal(source: String, target: String) {
        val sourcePath = Path(source)
        val targetPath = Path(target)
        targetPath.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        SystemFileSystem.source(sourcePath).buffered().use { input ->
            SystemFileSystem.sink(targetPath).buffered().use { output ->
                val buffer = ByteArray(8192)
                while (!input.exhausted()) {
                    val read = input.readAtMostTo(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
        }
    }
}

internal expect fun defaultTempDir(): String
