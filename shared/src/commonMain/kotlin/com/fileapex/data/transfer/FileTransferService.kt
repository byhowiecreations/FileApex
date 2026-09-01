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
import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient
import com.fileapex.util.PathUtils
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.fileapex.domain.transfer.LocalTransferTree
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
        require(items.isNotEmpty()) { AppI18n.t("select_at_least_one_file_to_copy") }
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
                    isLocalSource = true,
                    isDirectory = item.isDirectory
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
        require(items.isNotEmpty()) { AppI18n.t("select_at_least_one_file_to_copy") }
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
                    isLocalSource = false,
                    isDirectory = item.isDirectory
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
        require(sources.isNotEmpty()) { AppI18n.t("select_at_least_one_file") }
        require(selectedDevices.isNotEmpty()) { AppI18n.t("select_destination_device") }
        val semaphore = kotlinx.coroutines.sync.Semaphore(6)
        coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        val perFileDestinations = selectedDevices.map { option ->
                            if (option.isLocal) {
                                SystemFileSystem.createDirectories(Path(option.destinationRoot))
                            }
                            val preferred = PathUtils.join(option.destinationRoot, source.relativeDestPath)
                            val fileTarget = if (option.isLocal) {
                                if (source.isDirectory) {
                                    preferred.also { SystemFileSystem.createDirectories(Path(it)) }
                                } else {
                                    UniqueFileNames.resolve(preferred).also { resolved ->
                                        Path(resolved).parent?.let { parent ->
                                            SystemFileSystem.createDirectories(parent)
                                        }
                                    }
                                }
                            } else {
                                preferred
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
            }.awaitAll()
        }
    }

    suspend fun listRemoteRecursively(
        host: String,
        port: Int,
        baseRemotePath: String,
        relativePrefix: String
    ): List<MultiCopySource.Remote> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MultiCopySource.Remote>()
        val name = baseRemotePath.substringAfterLast('/').substringAfterLast('\\')
        out += MultiCopySource.Remote(
            fileName = name,
            sizeBytes = 0L,
            absolutePath = baseRemotePath,
            host = host,
            port = port,
            isDirectory = true,
            relativeDestPath = relativePrefix
        )
        val children = runCatching { client.listFiles(host, port, baseRemotePath) }.getOrDefault(emptyList())
        for (child in children) {
            if (LocalTransferTree.isIgnoredTransferFile(child.name)) continue
            val relative = "$relativePrefix/${child.name}"
            if (child.isDirectory) {
                out += listRemoteRecursively(host, port, child.absolutePath, relative)
            } else {
                out += MultiCopySource.Remote(
                    fileName = child.name,
                    sizeBytes = child.sizeBytes,
                    absolutePath = child.absolutePath,
                    host = host,
                    port = port,
                    isDirectory = false,
                    relativeDestPath = relative
                )
            }
        }
        out
    }

    suspend fun pasteIntoLocal(targetDirectory: String): List<String> = withContext(Dispatchers.IO) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { AppI18n.t("clipboard_empty") }
        val targetPaths = mutableListOf<String>()
        for (payload in payloads) {
            val targetPath = UniqueFileNames.resolveInDirectory(targetDirectory, payload.fileName)
            if (payload.isDirectory) {
                if (payload.isLocalSource) {
                    copyLocalDirectoryRecursively(payload.remoteAbsolutePath, targetPath)
                } else {
                    SystemFileSystem.createDirectories(Path(targetPath))
                    val remoteTree = listRemoteRecursively(payload.sourceHost, payload.sourcePort, payload.remoteAbsolutePath, payload.fileName)
                    val semaphore = kotlinx.coroutines.sync.Semaphore(6)
                    coroutineScope {
                        remoteTree.map { remoteSource ->
                            async {
                                semaphore.withPermit {
                                    val dest = PathUtils.join(targetDirectory, remoteSource.relativeDestPath)
                                    if (remoteSource.isDirectory) {
                                        SystemFileSystem.createDirectories(Path(dest))
                                    } else {
                                        Path(dest).parent?.let { SystemFileSystem.createDirectories(it) }
                                        client.downloadToLocal(
                                            host = payload.sourceHost,
                                            port = payload.sourcePort,
                                            remotePath = remoteSource.absolutePath,
                                            localTargetPath = dest,
                                            expectedSizeBytes = remoteSource.sizeBytes.takeIf { it > 0L }
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                when {
                    payload.isLocalSource -> copyLocalToLocal(payload.remoteAbsolutePath, targetPath)
                    else -> client.downloadToLocal(
                        host = payload.sourceHost,
                        port = payload.sourcePort,
                        remotePath = payload.remoteAbsolutePath,
                        localTargetPath = targetPath,
                        expectedSizeBytes = payload.sizeBytes.takeIf { it > 0L }
                    )
                }
            }
            targetPaths += targetPath
        }
        targetPaths
    }

    suspend fun pasteIntoRemote(
        host: String,
        port: Int,
        targetDirectory: String
    ): List<String> = withContext(Dispatchers.IO) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { AppI18n.t("clipboard_empty") }
        val targetPaths = mutableListOf<String>()
        for (payload in payloads) {
            val remoteTarget = PathUtils.join(targetDirectory, payload.fileName)
            if (payload.isDirectory) {
                if (payload.isLocalSource) {
                    client.createDirectory(host, port, remoteTarget)
                    val localTree = LocalTransferTree.expandAbsolutePaths(listOf(payload.remoteAbsolutePath))
                    val semaphore = kotlinx.coroutines.sync.Semaphore(6)
                    coroutineScope {
                        localTree.map { localSource ->
                            async {
                                semaphore.withPermit {
                                    val dest = PathUtils.join(targetDirectory, localSource.relativeDestPath)
                                    if (localSource.isDirectory) {
                                        client.createDirectory(host, port, dest)
                                    } else {
                                        client.uploadFromLocal(host, port, localSource.absolutePath, dest)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    client.createDirectory(host, port, remoteTarget)
                    val remoteTree = listRemoteRecursively(payload.sourceHost, payload.sourcePort, payload.remoteAbsolutePath, payload.fileName)
                    val semaphore = kotlinx.coroutines.sync.Semaphore(6)
                    val tempBase = defaultTempDir()
                    coroutineScope {
                        remoteTree.map { remoteSource ->
                            async {
                                semaphore.withPermit {
                                    val dest = PathUtils.join(targetDirectory, remoteSource.relativeDestPath)
                                    if (remoteSource.isDirectory) {
                                        client.createDirectory(host, port, dest)
                                    } else {
                                        val tempFile = PathUtils.join(tempBase, "fileapex-paste-${remoteSource.fileName}")
                                        try {
                                            client.downloadToLocal(
                                                host = payload.sourceHost,
                                                port = payload.sourcePort,
                                                remotePath = remoteSource.absolutePath,
                                                localTargetPath = tempFile,
                                                expectedSizeBytes = remoteSource.sizeBytes.takeIf { it > 0L }
                                            )
                                            client.uploadFromLocal(host, port, tempFile, dest)
                                        } finally {
                                            runCatching {
                                                val p = Path(tempFile)
                                                if (SystemFileSystem.exists(p)) SystemFileSystem.delete(p)
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
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
                                localTargetPath = tempLocal,
                                expectedSizeBytes = payload.sizeBytes.takeIf { it > 0L }
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
            }
            targetPaths += remoteTarget
        }
        targetPaths
    }

    /**
     * Streams remote file(s) onto this device under Downloads/FileApex.
     */
    suspend fun downloadRemoteToDownloads(
        host: String,
        port: Int,
        items: List<RemoteFileItem>
    ): List<String> = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { AppI18n.t("select_at_least_one_file_to_download") }
        val downloadsRoot = defaultDownloadsDir()
        SystemFileSystem.createDirectories(Path(downloadsRoot))
        val semaphore = kotlinx.coroutines.sync.Semaphore(6)
        val downloadedPaths = mutableListOf<String>()

        for (item in items) {
            if (item.isDirectory) {
                val targetDir = UniqueFileNames.resolveInDirectory(downloadsRoot, item.name)
                SystemFileSystem.createDirectories(Path(targetDir))
                val remoteTree = listRemoteRecursively(host, port, item.absolutePath, item.name)
                coroutineScope {
                    remoteTree.map { remoteSource ->
                        async {
                            semaphore.withPermit {
                                val destPath = PathUtils.join(downloadsRoot, remoteSource.relativeDestPath)
                                if (remoteSource.isDirectory) {
                                    SystemFileSystem.createDirectories(Path(destPath))
                                } else {
                                    Path(destPath).parent?.let { SystemFileSystem.createDirectories(it) }
                                    client.downloadToLocal(
                                        host = host,
                                        port = port,
                                        remotePath = remoteSource.absolutePath,
                                        localTargetPath = destPath,
                                        expectedSizeBytes = remoteSource.sizeBytes.takeIf { it > 0L }
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                downloadedPaths += targetDir
            } else {
                val targetPath = UniqueFileNames.resolveInDirectory(downloadsRoot, item.name)
                client.downloadToLocal(
                    host = host,
                    port = port,
                    remotePath = item.absolutePath,
                    localTargetPath = targetPath,
                    expectedSizeBytes = item.sizeBytes.takeIf { it > 0L }
                )
                downloadedPaths += targetPath
            }
        }
        downloadedPaths
    }

    private fun copyLocalDirectoryRecursively(sourceDir: String, targetDir: String) {
        val sourcePath = Path(sourceDir)
        val targetPath = Path(targetDir)
        if (!SystemFileSystem.exists(targetPath)) {
            SystemFileSystem.createDirectories(targetPath)
        }
        val children = runCatching { SystemFileSystem.list(sourcePath).toList() }.getOrDefault(emptyList())
        for (child in children) {
            if (LocalTransferTree.isIgnoredTransferFile(child.name)) continue
            val childTarget = PathUtils.join(targetDir, child.name)
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            if (metadata.isDirectory) {
                copyLocalDirectoryRecursively(child.toString(), childTarget)
            } else {
                copyLocalToLocal(child.toString(), childTarget)
            }
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
