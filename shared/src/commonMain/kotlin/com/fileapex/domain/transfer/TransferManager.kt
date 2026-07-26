package com.fileapex.domain.transfer

import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.transfer.FileTransferService
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.presence.PeerPresenceMonitor
import com.fileapex.network.FileApexClient
import com.fileapex.platform.DownloadsPaths
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.domain.transfer.verifiedFromDisk
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Single source of truth for outbound Multi Copy and explorer transfer actions.
 *
 * UI (explorer), Share Extension job handoff, and copy/paste/download must call here —
 * not [FileTransferService.multiCopyToDevices] or [MultiCopyBroadcastEngine] directly.
 * Stream fan-out stays in [FileTransferService] / [MultiCopyBroadcastEngine]; this class owns
 * readiness, device-option resolution, and public transfer entry points.
 */
class TransferManager(
    private val deviceRepository: () -> DeviceRepository,
    private val client: FileApexClient,
    private val transferService: FileTransferService,
    private val readinessCheck: () -> Boolean,
    private val identityProvider: () -> LocalIdentity,
    private val onlineDeviceIds: () -> Set<String>,
    private val presenceMonitor: () -> PeerPresenceMonitor
) {
    /**
     * Block until [FileApexServices] has finished init (DB + repositories).
     * External launch paths (Share Extension URI) must call this before enqueuing work.
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS) {
        if (isReady()) return
        val deadline = TimeUtils.now() + timeoutMs
        while (!isReady()) {
            check(TimeUtils.now() < deadline) {
                "TransferManager is not ready — FileApexServices.init was not completed"
            }
            delay(READY_POLL_MS)
        }
    }

    fun isReady(): Boolean = readinessCheck()

    /**
     * In-app Multi Copy picker: This device + currently online paired peers
     * (excluding the device being browsed as the source).
     */
    suspend fun buildInAppDeviceOptions(sourceDeviceId: String): List<MultiCopyDeviceOption> {
        awaitReady()
        val identity = identityProvider()
        val onlineIds = onlineDeviceIds()
        val localHost = NetworkUtils.preferredLanIpv4()
        val options = mutableListOf(
            MultiCopyDeviceOption(
                deviceId = LocalIdentity.LOCAL_DEVICE_ID,
                deviceName = "This device (${identity.deviceName})",
                isLocal = true,
                host = localHost,
                port = identity.sharePort,
                destinationRoot = defaultDownloadsDir()
            )
        )
        val peers = deviceRepository().listDevices()
            .filter { it.deviceId != sourceDeviceId }
            .filter { it.deviceId in onlineIds }
            .sortedBy { it.deviceName.lowercase() }
        for (peer in peers) {
            options += resolveRemoteOption(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                host = peer.lastKnownIp,
                port = peer.port,
                rootPath = peer.rootPath,
                peerPlatform = peer.platform,
                appVersion = peer.clientVersion.takeIf { it.isNotEmpty() }
            )
        }
        return options
    }

    /**
     * Android system Share sheet picker: online paired peers only (no "This device").
     */
    suspend fun buildShareSheetDeviceOptions(): List<MultiCopyDeviceOption> {
        awaitReady()
        val identity = identityProvider()
        return buildInAppDeviceOptions(sourceDeviceId = identity.deviceId)
            .filter { !it.isLocal }
    }

    /**
     * Direct Share / one-tap send: use roster endpoint only — no pre-send network probe.
     * Upload starts immediately; [prepareForTransfer] handles stale endpoints only when needed.
     */
    suspend fun resolveRemoteDeviceOptionsForImmediateSend(
        deviceIds: List<String>
    ): List<MultiCopyDeviceOption> {
        awaitReady()
        require(deviceIds.isNotEmpty()) { "Select at least one destination device" }
        val wanted = deviceIds.toSet()
        val peers = deviceRepository().listDevices().filter { it.deviceId in wanted }
        check(peers.isNotEmpty()) { "Selected devices are not in the paired roster" }
        return peers.map { peer ->
            resolveRemoteOptionFromRoster(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                host = peer.lastKnownIp,
                port = peer.port,
                rootPath = peer.rootPath,
                peerPlatform = peer.platform,
                appVersion = peer.clientVersion.takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * Extension / handoff path: resolve explicit paired device IDs into transfer options
     * (no online filter — caller already chose destinations).
     */
    suspend fun resolveRemoteDeviceOptions(deviceIds: List<String>): List<MultiCopyDeviceOption> {
        awaitReady()
        require(deviceIds.isNotEmpty()) { "Select at least one destination device" }
        val wanted = deviceIds.toSet()
        val peers = deviceRepository().listDevices().filter { it.deviceId in wanted }
        check(peers.isNotEmpty()) { "Selected devices are not in the paired roster" }
        return peers.map { peer ->
            resolveRemoteOption(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                host = peer.lastKnownIp,
                port = peer.port,
                rootPath = peer.rootPath,
                peerPlatform = peer.platform,
                appVersion = peer.clientVersion.takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * Canonical outbound send: sources + fully resolved device options → stream broadcast.
     */
    suspend fun sendToDevices(
        sources: List<MultiCopySource>,
        selectedDevices: List<MultiCopyDeviceOption>,
        skipTransferPrepare: Boolean = false
    ): TransferBatchResult {
        awaitReady()
        require(sources.isNotEmpty()) { "Select at least one file" }
        require(selectedDevices.isNotEmpty()) { "Select at least one destination device" }
        val verifiedSources = sources.verifiedFromDisk()
        val remoteTargets = selectedDevices.filter { !it.isLocal }
        val devicesForTransfer = if (remoteTargets.isEmpty()) {
            selectedDevices
        } else {
            if (!skipTransferPrepare) {
                presenceMonitor().prepareForTransfer(remoteTargets)
            }
            refreshRemoteHosts(selectedDevices)
        }
        val results = transferService.multiCopyToDevices(verifiedSources, devicesForTransfer)
        return TransferBatchResult.from(results, verifiedSources, devicesForTransfer)
    }

    fun copyLocalFiles(
        localIdentity: LocalIdentity,
        items: List<RemoteFileItem>,
        hostForPeers: String
    ) {
        transferService.copyLocalFiles(localIdentity, items, hostForPeers)
    }

    fun copyRemoteFiles(
        sourceDeviceId: String,
        sourceDeviceName: String,
        host: String,
        port: Int,
        items: List<RemoteFileItem>
    ) {
        transferService.copyRemoteFiles(sourceDeviceId, sourceDeviceName, host, port, items)
    }

    suspend fun pasteIntoLocal(targetDirectory: String): List<String> =
        transferService.pasteIntoLocal(targetDirectory)

    suspend fun pasteIntoRemote(host: String, port: Int, targetDirectory: String): List<String> =
        transferService.pasteIntoRemote(host, port, targetDirectory)

    suspend fun downloadRemoteToDownloads(
        host: String,
        port: Int,
        items: List<RemoteFileItem>
    ): List<String> = transferService.downloadRemoteToDownloads(host, port, items)

    /**
     * Share Extension job consumer: local absolute paths + paired device IDs.
     */
    suspend fun sendLocalPathsToDeviceIds(
        absolutePaths: List<String>,
        deviceIds: List<String>
    ): TransferBatchResult {
        awaitReady()
        require(absolutePaths.isNotEmpty()) { "Nothing to send" }
        val sources = absolutePaths.map { path ->
            val local = Path(path)
            check(SystemFileSystem.exists(local)) { "Missing file: $path" }
            val metadata = SystemFileSystem.metadataOrNull(local)
            check(metadata != null && !metadata.isDirectory) { "Missing file: $path" }
            MultiCopySource.Local(
                fileName = local.name,
                sizeBytes = metadata.size,
                absolutePath = path
            )
        }
        val options = resolveRemoteDeviceOptions(deviceIds)
        return sendToDevices(sources, options)
    }

    private suspend fun refreshRemoteHosts(
        options: List<MultiCopyDeviceOption>
    ): List<MultiCopyDeviceOption> = options.map { option ->
        if (option.isLocal) return@map option
        val peer = deviceRepository().getDevice(option.deviceId) ?: return@map option
        val host = peer.lastKnownIp.trim()
        if (!NetworkUtils.isUsableLanIpv4(host)) return@map option
        if (host == option.host.trim() && peer.port == option.port) return@map option
        option.copy(host = host, port = peer.port)
    }

    private fun resolveRemoteOptionFromRoster(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        rootPath: String,
        peerPlatform: String,
        appVersion: String?
    ): MultiCopyDeviceOption {
        val downloadsRoot = DownloadsPaths.resolveReceiveRoot(
            downloadsPath = "",
            rootPath = rootPath,
            platform = peerPlatform
        )
        return MultiCopyDeviceOption(
            deviceId = deviceId,
            deviceName = deviceName,
            isLocal = false,
            host = host,
            port = port,
            destinationRoot = downloadsRoot,
            appVersion = appVersion
        )
    }

    private suspend fun resolveRemoteOption(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        rootPath: String,
        peerPlatform: String,
        appVersion: String?
    ): MultiCopyDeviceOption {
        val downloadsRoot = runCatching {
            val remote = client.fetchPeerNodeState(host, port)
            presenceMonitor().notifyPassiveReachability(deviceId)
            DownloadsPaths.resolveReceiveRoot(
                downloadsPath = remote.downloadsPath,
                rootPath = rootPath,
                platform = remote.platform.ifBlank { peerPlatform }
            )
        }.getOrElse {
            DownloadsPaths.resolveReceiveRoot(
                downloadsPath = "",
                rootPath = rootPath,
                platform = peerPlatform
            )
        }
        return MultiCopyDeviceOption(
            deviceId = deviceId,
            deviceName = deviceName,
            isLocal = false,
            host = host,
            port = port,
            destinationRoot = downloadsRoot,
            appVersion = appVersion
        )
    }

    companion object {
        private const val DEFAULT_READY_TIMEOUT_MS = 60_000L
        private const val READY_POLL_MS = 50L
    }
}

/**
 * Normalized outcome of a multi-device send, shared by explorer UI and extension handoff.
 */
data class TransferBatchResult(
    val results: List<MultiCopyResult>,
    val summaryMessage: String,
    val allFailed: Boolean,
    val partialFailure: Boolean
) {
    companion object {
        fun from(
            results: List<MultiCopyResult>,
            sources: List<MultiCopySource>,
            selectedDevices: List<MultiCopyDeviceOption>
        ): TransferBatchResult {
            val failCount = results.sumOf { it.failures.size }
            val okDevices = results.flatMap { it.succeededDeviceIds }.toSet()
            val allFailed = okDevices.isEmpty() && failCount > 0
            val message = when {
                failCount == 0 -> {
                    val deviceLabel = if (selectedDevices.size == 1) {
                        selectedDevices.first().deviceName
                    } else {
                        "${selectedDevices.size} devices"
                    }
                    if (sources.size == 1) {
                        "Sent ${sources.first().fileName} to $deviceLabel"
                    } else {
                        "Sent ${sources.size} files to $deviceLabel"
                    }
                }
                allFailed -> "Send failed for all destinations"
                else -> "Send finished with $failCount error(s)"
            }
            return TransferBatchResult(
                results = results,
                summaryMessage = message,
                allFailed = allFailed,
                partialFailure = failCount > 0 && !allFailed
            )
        }
    }
}
