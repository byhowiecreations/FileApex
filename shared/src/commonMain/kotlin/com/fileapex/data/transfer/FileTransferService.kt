package com.fileapex.data.transfer

import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.data.files.DirectoryListing
import com.fileapex.data.files.LocalFileRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.model.ClipboardPayload
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.LocalTransferTree
import com.fileapex.domain.transfer.MultiCopyBroadcastEngine
import com.fileapex.domain.transfer.MultiCopyDestination
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.MultiCopyResult
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.domain.transfer.TransferBatchScheduler
import com.fileapex.domain.transfer.TransferJob
import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient
import com.fileapex.network.SocketFileStreamer
import com.fileapex.network.TransferRuntime
import com.fileapex.network.transferCatching
import com.fileapex.platform.prepareLocalDirectoryAccess
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.PathUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Stream I/O for copy/paste/download/browse listing.
 * Outbound Multi Copy and explorer transfer actions enter through [com.fileapex.domain.transfer.TransferManager].
 * Every multi-item operation runs through [TransferBatchScheduler].
 */
class FileTransferService(
    private val localFiles: LocalFileRepository = LocalFileRepository(),
    private val client: FileApexClient
) {
    private val multiCopyEngine = MultiCopyBroadcastEngine(client)

    suspend fun listLocal(path: String, bypassCache: Boolean = false): DirectoryListing {
        prepareLocalDirectoryAccess(path)
        return withContext(Dispatchers.IO) {
            localFiles.listDirectory(path, bypassCache = bypassCache).getOrThrow()
        }
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
    ): List<MultiCopyResult> = withContext(TransferRuntime.outbound) {
        require(sources.isNotEmpty()) { AppI18n.t("select_at_least_one_file") }
        require(selectedDevices.isNotEmpty()) { AppI18n.t("select_destination_device") }
        TransferActivityGuard.addBatchBytes(
            sources.filterNot { it.isDirectory }.sumOf { it.sizeBytes } * selectedDevices.size
        )
        val jobs = withDistinctDestinations(sources).map { source ->
            TransferJob(
                label = source.fileName,
                sizeBytes = source.sizeBytes,
                isDirectory = source.isDirectory,
                relativePath = source.relativeDestPath
            ) {
                multiCopyEngine.broadcast(listOf(source), destinationsFor(source, selectedDevices)).first()
            }
        }
        TransferBatchScheduler.runAll(jobs) { job, failure ->
            MultiCopyResult(
                fileName = job.label,
                succeededDeviceIds = emptySet(),
                failures = selectedDevices.associate { option ->
                    option.deviceId to (failure.message ?: AppI18n.t("transfer_failed_on", option.deviceName))
                }
            )
        }
    }

    private fun destinationsFor(
        source: MultiCopySource,
        selectedDevices: List<MultiCopyDeviceOption>
    ): List<MultiCopyDestination> = selectedDevices.map { option ->
        val preferred = PathUtils.join(option.destinationRoot, source.relativeDestPath)
        if (option.isLocal) {
            SystemFileSystem.createDirectories(Path(option.destinationRoot))
            val target = if (source.isDirectory) {
                preferred.also { SystemFileSystem.createDirectories(Path(it)) }
            } else {
                UniqueFileNames.resolve(preferred).also { resolved ->
                    Path(resolved).parent?.let { SystemFileSystem.createDirectories(it) }
                }
            }
            MultiCopyDestination.LocalDevice(
                deviceId = option.deviceId,
                deviceName = option.deviceName,
                absolutePath = target
            )
        } else {
            MultiCopyDestination.RemoteDevice(
                deviceId = option.deviceId,
                deviceName = option.deviceName,
                host = option.host,
                port = option.port,
                absolutePath = preferred
            )
        }
    }

    /**
     * Concurrent workers would race on one part file when two flat sources share a name,
     * so later duplicates get the usual `name (n).ext` before anything is scheduled.
     */
    private fun withDistinctDestinations(sources: List<MultiCopySource>): List<MultiCopySource> {
        val taken = HashSet<String>()
        return sources.map { source ->
            val key = source.relativeDestPath.lowercase()
            if (taken.add(key) || source.isDirectory) return@map source
            val folder = source.relativeDestPath.substringBeforeLast('/', missingDelimiterValue = "")
            val name = source.relativeDestPath.substringAfterLast('/')
            var index = 1
            var candidate: String
            do {
                val numbered = UniqueFileNames.numbered(name, index++)
                candidate = if (folder.isEmpty()) numbered else "$folder/$numbered"
            } while (!taken.add(candidate.lowercase()))
            when (source) {
                is MultiCopySource.Local -> source.copy(relativeDestPath = candidate)
                is MultiCopySource.Remote -> source.copy(relativeDestPath = candidate)
            }
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
        val children = transferCatching { client.listFiles(host, port, baseRemotePath) }.getOrDefault(emptyList())
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

    suspend fun pasteIntoLocal(targetDirectory: String): List<String> = withContext(TransferRuntime.outbound) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { AppI18n.t("clipboard_empty") }
        val targetPaths = mutableListOf<String>()
        val jobs = mutableListOf<TransferJob<JobOutcome>>()
        for (payload in payloads) {
            val targetPath = UniqueFileNames.resolveInDirectory(targetDirectory, payload.fileName, targetPaths.toSet())
            targetPaths += targetPath
            if (!payload.isDirectory) {
                jobs += fileJob(payload.fileName, payload.sizeBytes, payload.fileName) {
                    copyIntoLocal(payload, payload.remoteAbsolutePath, targetPath, payload.sizeBytes)
                }
                continue
            }
            SystemFileSystem.createDirectories(Path(targetPath))
            for (entry in treeOf(payload)) {
                val dest = rebase(targetPath, payload.fileName, entry.relativeDestPath)
                jobs += if (entry.isDirectory) {
                    directoryJob(entry) { SystemFileSystem.createDirectories(Path(dest)) }
                } else {
                    fileJob(entry.fileName, entry.sizeBytes, entry.relativeDestPath) {
                        copyIntoLocal(payload, entry.absolutePath, dest, entry.sizeBytes)
                    }
                }
            }
        }
        runOrThrow(jobs, "paste_failed")
        targetPaths
    }

    suspend fun pasteIntoRemote(
        host: String,
        port: Int,
        targetDirectory: String
    ): List<String> = withContext(TransferRuntime.outbound) {
        val payloads = TransferClipboard.peekAll()
        check(payloads.isNotEmpty()) { AppI18n.t("clipboard_empty") }
        val targetPaths = mutableListOf<String>()
        val jobs = mutableListOf<TransferJob<JobOutcome>>()
        for (payload in payloads) {
            val remoteTarget = PathUtils.join(targetDirectory, payload.fileName)
            targetPaths += remoteTarget
            if (!payload.isDirectory) {
                jobs += fileJob(payload.fileName, payload.sizeBytes, payload.fileName) {
                    copyIntoRemote(payload, payload.remoteAbsolutePath, payload.sizeBytes, host, port, remoteTarget)
                }
                continue
            }
            for (entry in treeOf(payload)) {
                val dest = PathUtils.join(targetDirectory, entry.relativeDestPath)
                jobs += if (entry.isDirectory) {
                    directoryJob(entry) { client.createDirectory(host, port, dest) }
                } else {
                    fileJob(entry.fileName, entry.sizeBytes, entry.relativeDestPath) {
                        copyIntoRemote(payload, entry.absolutePath, entry.sizeBytes, host, port, dest)
                    }
                }
            }
        }
        runOrThrow(jobs, "paste_failed")
        targetPaths
    }

    /**
     * Streams remote file(s) onto this device under Downloads/FileApex.
     */
    suspend fun downloadRemoteToDownloads(
        host: String,
        port: Int,
        items: List<RemoteFileItem>,
        destinationDirectory: String? = null
    ): List<String> = withContext(TransferRuntime.outbound) {
        require(items.isNotEmpty()) { AppI18n.t("select_at_least_one_file_to_download") }
        val downloadsRoot = destinationDirectory?.takeIf { it.isNotBlank() } ?: defaultDownloadsDir()
        SystemFileSystem.createDirectories(Path(downloadsRoot))
        val downloadedPaths = mutableListOf<String>()
        val jobs = mutableListOf<TransferJob<JobOutcome>>()
        for (item in items) {
            val targetPath = UniqueFileNames.resolveInDirectory(downloadsRoot, item.name, downloadedPaths.toSet())
            downloadedPaths += targetPath
            if (!item.isDirectory) {
                jobs += fileJob(item.name, item.sizeBytes, item.name) {
                    download(host, port, item.absolutePath, targetPath, item.sizeBytes)
                }
                continue
            }
            SystemFileSystem.createDirectories(Path(targetPath))
            for (entry in listRemoteRecursively(host, port, item.absolutePath, item.name)) {
                val dest = rebase(targetPath, item.name, entry.relativeDestPath)
                jobs += if (entry.isDirectory) {
                    directoryJob(entry) { SystemFileSystem.createDirectories(Path(dest)) }
                } else {
                    fileJob(entry.fileName, entry.sizeBytes, entry.relativeDestPath) {
                        download(host, port, entry.absolutePath, dest, entry.sizeBytes)
                    }
                }
            }
        }
        runOrThrow(jobs, "download_failed")
        downloadedPaths
    }

    private suspend fun treeOf(payload: ClipboardPayload): List<MultiCopySource> =
        if (payload.isLocalSource) {
            LocalTransferTree.expandAbsolutePaths(listOf(payload.remoteAbsolutePath))
        } else {
            listRemoteRecursively(payload.sourceHost, payload.sourcePort, payload.remoteAbsolutePath, payload.fileName)
        }

    private suspend fun copyIntoLocal(payload: ClipboardPayload, sourcePath: String, target: String, sizeBytes: Long) {
        if (payload.isLocalSource) {
            copyLocalToLocal(sourcePath, target)
        } else {
            download(payload.sourceHost, payload.sourcePort, sourcePath, target, sizeBytes)
        }
    }

    private suspend fun copyIntoRemote(
        payload: ClipboardPayload,
        sourcePath: String,
        sizeBytes: Long,
        host: String,
        port: Int,
        remoteTarget: String
    ) {
        if (payload.isLocalSource) {
            client.uploadFromLocal(
                host = host,
                port = port,
                localSourcePath = sourcePath,
                remoteTargetPath = remoteTarget,
                transactionId = generateDeviceId(),
                transactionTimestampEpochMs = TimeUtils.now()
            )
        } else {
            client.relayRemoteFile(
                sourceHost = payload.sourceHost,
                sourcePort = payload.sourcePort,
                sourcePath = sourcePath,
                sizeBytes = sizeBytes,
                host = host,
                port = port,
                remoteTargetPath = remoteTarget,
                transactionId = generateDeviceId(),
                transactionTimestampEpochMs = TimeUtils.now()
            )
        }
    }

    private suspend fun download(host: String, port: Int, remotePath: String, target: String, sizeBytes: Long) {
        Path(target).parent?.let { SystemFileSystem.createDirectories(it) }
        client.downloadToLocal(
            host = host,
            port = port,
            remotePath = remotePath,
            localTargetPath = target,
            expectedSizeBytes = sizeBytes.takeIf { it > 0L }
        )
    }

    private fun rebase(targetRoot: String, rootName: String, relativeDestPath: String): String {
        val inner = relativeDestPath.removePrefix(rootName).trimStart('/')
        return if (inner.isEmpty()) targetRoot else PathUtils.join(targetRoot, inner)
    }

    private fun fileJob(
        label: String,
        sizeBytes: Long,
        relativePath: String,
        action: suspend () -> Unit
    ) = TransferJob(label, sizeBytes, isDirectory = false, relativePath = relativePath) {
        action()
        JobOutcome(label)
    }

    private fun directoryJob(entry: MultiCopySource, action: suspend () -> Unit) =
        TransferJob(entry.fileName, 0L, isDirectory = true, relativePath = entry.relativeDestPath) {
            action()
            JobOutcome(entry.fileName)
        }

    private suspend fun runOrThrow(jobs: List<TransferJob<JobOutcome>>, failureKey: String) {
        val outcomes = TransferBatchScheduler.runAll(jobs) { job, failure -> JobOutcome(job.label, failure) }
        val failed = outcomes.filter { it.failure != null }
        if (failed.isEmpty()) return
        val first = failed.first()
        val firstError = checkNotNull(first.failure)
        if (outcomes.size == 1) throw firstError
        error(
            "${AppI18n.t(failureKey)} (${failed.size}/${outcomes.size}): ${first.label}: " +
                (firstError.message ?: firstError::class.simpleName.orEmpty())
        )
    }

    private fun copyLocalToLocal(source: String, target: String) {
        Path(target).parent?.let { SystemFileSystem.createDirectories(it) }
        val partPath = SocketFileStreamer.partPathFor(target)
        val sourceSize = SocketFileStreamer.fileLength(source)
        val offset = SocketFileStreamer.fileLength(partPath).coerceIn(0L, sourceSize)
        SocketFileStreamer.openAppender(partPath, offset).use { raf ->
            SocketFileStreamer.streamFromOffset(source, offset) { buffer, length ->
                raf.write(buffer, 0, length)
            }
        }
        SocketFileStreamer.finalizePart(partPath, target)
    }

    private class JobOutcome(val label: String, val failure: Throwable? = null)
}
