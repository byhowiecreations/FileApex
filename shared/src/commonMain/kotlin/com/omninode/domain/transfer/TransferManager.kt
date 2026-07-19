package com.omninode.domain.transfer

import com.omninode.data.device.DeviceRepository
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.transfer.FileTransferService
import com.omninode.network.OmniNodeClient
import com.omninode.platform.TransferPaths
import com.omninode.platform.defaultDownloadsDir
import com.omninode.platform.localIpv4Addresses
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Single source of truth for outbound Multi Copy orchestration.
 *
 * UI (explorer), Finder Sync / Share job handoff, and any future entry points must call here —
 * not [FileTransferService.multiCopyToDevices] or [MultiCopyBroadcastEngine] directly from Views.
 * Stream fan-out stays in [FileTransferService] / [MultiCopyBroadcastEngine]; this class owns
 * readiness, device-option resolution, local-path source building, and result summaries.
 */
class TransferManager(
    private val deviceRepository: () -> DeviceRepository,
    private val client: OmniNodeClient,
    private val transferService: FileTransferService,
    private val readinessCheck: () -> Boolean,
    private val identityProvider: () -> LocalIdentity,
    private val onlineDeviceIds: () -> Set<String>
) {
    /**
     * Block until [OmniNodeServices] has finished init (DB + repositories).
     * External launch paths (Finder Sync URI) must call this before enqueuing work.
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS) {
        if (isReady()) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isReady()) {
            check(System.currentTimeMillis() < deadline) {
                "TransferManager is not ready — OmniNodeServices.init was not completed"
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
        val localHost = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
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
            options += resolveRemoteOption(peer.deviceId, peer.deviceName, peer.lastKnownIp, peer.port, peer.rootPath)
        }
        return options
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
                rootPath = peer.rootPath
            )
        }
    }

    /**
     * Canonical outbound send: sources + fully resolved device options → stream broadcast.
     */
    suspend fun sendToDevices(
        sources: List<MultiCopySource>,
        selectedDevices: List<MultiCopyDeviceOption>
    ): TransferBatchResult {
        awaitReady()
        require(sources.isNotEmpty()) { "Select at least one file" }
        require(selectedDevices.isNotEmpty()) { "Select at least one destination device" }
        val results = transferService.multiCopyToDevices(sources, selectedDevices)
        return TransferBatchResult.from(results, sources, selectedDevices)
    }

    /**
     * Finder Sync / Share job consumer: local absolute paths + paired device IDs.
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

    private suspend fun resolveRemoteOption(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        rootPath: String
    ): MultiCopyDeviceOption {
        val downloadsRoot = runCatching {
            val remote = client.fetchIdentity(host, port)
            remote.downloadsPath.trim().ifBlank {
                TransferPaths.fallbackDownloadsPath(rootPath)
            }
        }.getOrElse {
            TransferPaths.fallbackDownloadsPath(rootPath)
        }
        return MultiCopyDeviceOption(
            deviceId = deviceId,
            deviceName = deviceName,
            isLocal = false,
            host = host,
            port = port,
            destinationRoot = downloadsRoot
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
